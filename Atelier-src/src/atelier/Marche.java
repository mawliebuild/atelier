package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prix moyens de la place du marche de habbo.fr, demandes au jeu lui-meme.
 *
 * GetMarketplaceItemStats (int categorie : 1 sol / 2 mur / 3 rare limite,
 * int typeId de la furnidata [, String parametre d'une affiche]), comme le
 * client (MarketPlaceLogic.requestOfferStats) ; le serveur repond
 * MarketplaceItemStats (parseur du client) :
 *     int prixMoyen, int offresEnCours, int jours,
 *     int n, n x (int decalageJour, int prixMoyen, int vendus),
 *     int categorie, int typeId, int prixLePlusBas, int prixConseille
 * (les deux derniers entiers sont recents : l'ancien format, sans eux, est
 * encore accepte). C'est ce qu'affiche la fenetre du marche dans le jeu.
 * Sans aucune reponse apres MUET demandes, le marche n'est plus interroge
 * pendant la session (muet).
 *
 * La reponse est reconnue A SON CONTENU (longueur coherente, et categorie +
 * typeId d'une demande en attente) : l'interception par nom echoue parfois en
 * silence. Les reponses a NOS demandes sont bloquees, le client ne les a pas
 * demandees.
 *
 * Les prix sont gardes dans prix-marche.json (dossier de l'Atelier, voir
 * PrixFichier) et redemandes apres 24 h. Jamais de rafale vers le serveur :
 * au moins 0,5 s entre deux demandes, quel que soit l'appelant. Un mobi
 * sans reponse n'est redemande qu'apres 30 min.
 */
public final class Marche {

    public static final class Prix {
        public final int moyen, offres, vendus;
        public final long le;
        Prix(int moyen, int offres, int vendus, long le) {
            this.moyen = moyen; this.offres = offres; this.vendus = vendus; this.le = le;
        }
    }

    private static final long VALIDITE = 24 * 3600_000L;
    private static final String NOM = "prix-marche.json", ANCIEN = "prix.json";
    static final long ECART_MS = 500, REESSAI_MS = 30 * 60_000L;
    private static final Object rythme = new Object();
    private static long derniereDemande = 0;
    /** Cle -> moment de la derniere demande restee sans reponse. */
    private static final Map<String, Long> sansReponse = new ConcurrentHashMap<>();
    private static final Map<String, Prix> prix = new ConcurrentHashMap<>();
    private static final Map<String, Object> attente = new ConcurrentHashMap<>();
    private static volatile boolean installe = false, charge = false;
    /** Demandes sans reponse tant qu'aucune n'a jamais repondu ; au-dela de MUET, plus de demande. */
    static final int MUET = 20;
    private static volatile int muettes = 0;
    private static volatile boolean aRepondu = false, muetDit = false;

    private Marche() { }

    static String cle(boolean mur, int typeId) { return (mur ? "2:" : "1:") + typeId; }

    public static Prix prix(boolean mur, int typeId) {
        chargerUneFois();
        return prix.get(cle(mur, typeId));
    }

    public static boolean aJour(boolean mur, int typeId) {
        Prix p = prix(mur, typeId);
        return p != null && !PrixFichier.perime(p.le, VALIDITE, System.currentTimeMillis());
    }

    /** Reste sans reponse il y a moins de 30 min : inutile de redemander. */
    static boolean sansReponseRecente(boolean mur, int typeId) {
        Long t = sansReponse.get(cle(mur, typeId));
        return t != null && System.currentTimeMillis() - t < REESSAI_MS;
    }

    /** Le marche n'a jamais repondu a MUET demandes de suite : on ne l'interroge plus pendant la session. */
    public static boolean muet() { return !aRepondu && muettes >= MUET; }

    /** Attend son tour : au moins ECART_MS depuis la demande precedente. */
    private static void attendreTour() throws InterruptedException {
        long attente;
        synchronized (rythme) {
            long t = System.currentTimeMillis();
            long depart = Math.max(t, derniereDemande + ECART_MS);
            derniereDemande = depart;
            attente = depart - t;
        }
        if (attente > 0) Thread.sleep(attente);
    }

    /**
     * Demande le prix d'un mobi et attend la reponse (au plus 4 s).
     * @return le prix, ou null si le serveur n'a pas repondu.
     */
    public static Prix demander(Moteur gp, boolean mur, int typeId) throws InterruptedException {
        if (muet()) return null;
        installer(gp);
        chargerUneFois();
        attendreTour();
        String k = cle(mur, typeId);
        Prix avant = prix.get(k);
        Object signal = new Object();
        attente.put(k, signal);
        try {
            synchronized (signal) {
                gp.sendToServer(new HPacket("GetMarketplaceItemStats", HMessage.Direction.TOSERVER,
                        mur ? 2 : 1, typeId));
                long fin = System.currentTimeMillis() + 4000;
                while (attente.containsKey(k)) {
                    long reste = fin - System.currentTimeMillis();
                    if (reste <= 0) break;
                    signal.wait(reste);
                }
            }
        } finally {
            attente.remove(k);
        }
        Prix p = prix.get(k);
        if (p == null || p == avant) {
            sansReponse.put(k, System.currentTimeMillis());
            if (!aRepondu && ++muettes >= MUET && !muetDit) {
                muetDit = true;
                Journal.debug("prix du marché du jeu : aucune réponse à " + MUET + " demandes, le marché n'est plus interrogé pendant cette session.");
            }
            return null;
        }
        aRepondu = true;
        sansReponse.remove(k);
        return p;
    }

    private static synchronized void installer(Moteur gp) {
        if (installe) return;
        gp.intercept(HMessage.Direction.TOCLIENT, m -> {
            if (attente.isEmpty()) return;                 // rien demande : rien a lire
            try { lire(m); } catch (Throwable ignored) { }
        });
        installe = true;
    }

    private static void lire(HMessage m) {
        int[] d = decoder(m.getPacket());
        if (d == null) return;
        String k = cle(d[0] == 2, d[1]);
        Object signal = attente.get(k);
        if (signal == null) return;
        prix.put(k, new Prix(d[2], d[3], d[4], System.currentTimeMillis()));
        m.setBlocked(true);                                // reponse a NOTRE demande
        attente.remove(k);
        synchronized (signal) { signal.notifyAll(); }
    }

    /**
     * Reconnait un MarketplaceItemStats a son contenu (logique pure) :
     * {categorie (1 / 2), typeId, prixMoyen, offres, vendus}, ou null.
     * 8 entiers fixes (6 dans l'ancien format) + 3 par jour d'historique,
     * apres l'en-tete de 6 octets.
     */
    static int[] decoder(HPacket brut) {
        int taille = brut.getBytesLength();
        if (taille < 6 + 24) return null;
        int n = brut.readInteger(6 + 12);
        if (n < 0 || n > 10_000 || (6 + 32 + 12 * n != taille && 6 + 24 + 12 * n != taille)) return null;
        int fin = 6 + 16 + 12 * n;
        int categorie = brut.readInteger(fin), typeId = brut.readInteger(fin + 4);
        if (categorie != 1 && categorie != 2) return null;
        int vendus = 0;
        for (int i = 0; i < n; i++) vendus += brut.readInteger(6 + 16 + 12 * i + 8);
        return new int[]{categorie, typeId, brut.readInteger(6), brut.readInteger(10), vendus};
    }

    // -------------------------------------------------------------- stockage

    private static synchronized void chargerUneFois() {
        if (charge) return;
        charge = true;
        JSONObject o = PrixFichier.lire(NOM, ANCIEN);
        if (o == null) return;
        try {
            for (String k : o.keySet()) {
                JSONObject j = o.optJSONObject(k);
                if (j == null || !k.matches("[12]:\\d+")) continue;
                prix.putIfAbsent(k, new Prix(j.optInt("moyen"), j.optInt("offres"), j.optInt("vendus"), j.optLong("le")));
            }
        } catch (Throwable t) {
            Journal.debug("prix : fichier illisible, il sera refait : " + t);
        }
    }

    public static synchronized void sauver() {
        chargerUneFois();      // ne jamais ecraser le fichier par une memoire pas encore lue
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Prix> e : new TreeMap<>(prix).entrySet()) {
                JSONObject j = new JSONObject();
                j.put("moyen", e.getValue().moyen);
                j.put("offres", e.getValue().offres);
                j.put("vendus", e.getValue().vendus);
                j.put("le", e.getValue().le);
                o.put(e.getKey(), j);
            }
            if (!PrixFichier.ecrire(NOM, o)) System.err.println("[Atelier] prix : sauvegarde impossible.");
        } catch (Throwable t) {
            System.err.println("[Atelier] prix : sauvegarde impossible : " + t);
        }
    }
}


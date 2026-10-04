package atelier;

import extension.GPresets;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prix moyens de la place du marche de habbo.fr, demandes au jeu lui-meme.
 *
 * MarketplaceGetItemStats (int categorie : 1 sol / 2 mur, int typeId) ; le
 * serveur repond MarketplaceItemStats :
 *     int prixMoyen, int offresEnCours, int jours,
 *     int n, n x (int decalageJour, int prixMoyen, int vendus),
 *     int categorie, int typeId
 * C'est ce qu'affiche la fenetre du marche dans le jeu.
 *
 * La reponse est reconnue A SON CONTENU (longueur coherente, et categorie +
 * typeId d'une demande en attente) : l'interception par nom echoue parfois en
 * silence. Les reponses a NOS demandes sont bloquees, le client ne les a pas
 * demandees.
 *
 * Les prix sont gardes dans repertoire/prix.json et redemandes apres 24 h.
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
    private static final File FICHIER = new File("repertoire", "prix.json");
    private static final Map<String, Prix> prix = new ConcurrentHashMap<>();
    private static final Map<String, Object> attente = new ConcurrentHashMap<>();
    private static volatile boolean installe = false, charge = false;

    private Marche() { }

    static String cle(boolean mur, int typeId) { return (mur ? "2:" : "1:") + typeId; }

    public static Prix prix(boolean mur, int typeId) {
        chargerUneFois();
        return prix.get(cle(mur, typeId));
    }

    public static boolean aJour(boolean mur, int typeId) {
        Prix p = prix(mur, typeId);
        return p != null && System.currentTimeMillis() - p.le < VALIDITE;
    }

    /**
     * Demande le prix d'un mobi et attend la reponse (au plus 4 s).
     * @return le prix, ou null si le serveur n'a pas repondu.
     */
    public static Prix demander(GPresets gp, boolean mur, int typeId) throws InterruptedException {
        installer(gp);
        String k = cle(mur, typeId);
        Object signal = new Object();
        attente.put(k, signal);
        try {
            synchronized (signal) {
                gp.sendToServer(new HPacket("MarketplaceGetItemStats", HMessage.Direction.TOSERVER,
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
        return prix.get(k);
    }

    private static synchronized void installer(GPresets gp) {
        if (installe) return;
        gp.intercept(HMessage.Direction.TOCLIENT, m -> {
            if (attente.isEmpty()) return;                 // rien demande : rien a lire
            try { lire(m); } catch (Throwable ignored) { }
        });
        installe = true;
    }

    private static void lire(HMessage m) {
        HPacket brut = m.getPacket();
        int taille = brut.getBytesLength();
        // 6 entiers fixes + 3 par jour d'historique, apres l'en-tete de 6 octets.
        if (taille < 6 + 24 || (taille - 6 - 24) % 12 != 0) return;
        int n = brut.readInteger(6 + 12);
        if (n < 0 || 6 + 24 + 12 * n != taille) return;
        int fin = 6 + 16 + 12 * n;
        int categorie = brut.readInteger(fin), typeId = brut.readInteger(fin + 4);
        if (categorie != 1 && categorie != 2) return;
        String k = cle(categorie == 2, typeId);
        Object signal = attente.get(k);
        if (signal == null) return;

        int moyen = brut.readInteger(6), offres = brut.readInteger(10);
        int vendus = 0;
        for (int i = 0; i < n; i++) vendus += brut.readInteger(6 + 16 + 12 * i + 8);
        prix.put(k, new Prix(moyen, offres, vendus, System.currentTimeMillis()));
        m.setBlocked(true);                                // reponse a NOTRE demande
        attente.remove(k);
        synchronized (signal) { signal.notifyAll(); }
    }

    // -------------------------------------------------------------- stockage

    private static synchronized void chargerUneFois() {
        if (charge) return;
        charge = true;
        if (!FICHIER.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(FICHIER.toPath()), StandardCharsets.UTF_8));
            for (String k : o.keySet()) {
                JSONObject j = o.getJSONObject(k);
                prix.put(k, new Prix(j.optInt("moyen"), j.optInt("offres"), j.optInt("vendus"), j.optLong("le")));
            }
        } catch (Throwable t) {
            Journal.debug("prix : fichier illisible, il sera refait : " + t);
        }
    }

    public static synchronized void sauver() {
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
            File d = FICHIER.getParentFile();
            if (!d.exists()) d.mkdirs();
            File tmp = new File(d, "prix.json.tmp");
            Files.write(tmp.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FICHIER.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Atelier] prix : sauvegarde impossible : " + t);
        }
    }
}

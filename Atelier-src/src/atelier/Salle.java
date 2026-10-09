package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Acces partage a la salle ouverte, pour les outils de l'Atelier.
 *
 * Tout passe par le moteur de l'Atelier, qui tient deja l'etat de la salle (EtatSalle),
 * la furnidata et l'inventaire. Ici on ne fait que rassembler les lectures
 * que chaque outil recopiait, et une seule ecoute des clics du jeu :
 *
 *   surClicCase  un clic sur le sol (deplacement d'avatar : x,y)
 *   surClicMobi  un clic sur un mobi de sol (son identifiant dans le paquet)
 *
 * Reconnaissance par CONTENU, comme dans OngletWired : un intercept par nom
 * echoue en silence quand le nom ne se resout plus.
 */
public final class Salle {

    private Salle() { }

    // ------------------------------------------------------------- lectures

    public static Moteur gp() { return AtelierLauncher.moteur(); }

    /** L'etat de la salle, ou null hors salle / Atelier pas pret. */
    public static EtatSalle etat() {
        Moteur gp = gp();
        if (gp == null) return null;
        EtatSalle s = gp.getFloorState();
        return (s == null || !s.inRoom()) ? null : s;
    }

    public static boolean dansUneSalle() { return etat() != null; }

    /** Identifiant de la salle ouverte, -1 hors salle. */
    public static int salleId() {
        try { EtatSalle s = etat(); return s == null ? -1 : s.getRoomId(); } catch (Throwable t) { return -1; }
    }

    private static volatile int salleVue = -1;
    private static volatile long salleVueA = 0;

    /**
     * Est-on dans la meme salle depuis au moins ms millisecondes ? Le jeu charge
     * la salle juste apres l'entree : mieux vaut ne rien lui envoyer a ce moment.
     * (La premiere fois qu'une salle est vue compte comme l'entree.)
     */
    public static boolean installeeDepuis(long ms) {
        int id = salleId();
        long t = System.currentTimeMillis();
        if (id != salleVue) { salleVue = id; salleVueA = t; }
        return id != -1 && t - salleVueA >= ms;
    }

    /**
     * Copie des mobis de sol ; jamais null. Sans les dalles fictives de la
     * grille dans le jeu, si le moteur de l'Atelier les a vues passer.
     */
    public static List<HFloorItem> sols() {
        EtatSalle s = etat();
        if (s == null) return List.of();
        try {
            List<HFloorItem> l = s.getItems();
            if (l == null) return List.of();
            l.removeIf(it -> {
                if (!idFictif(it.getId())) return false;
                return true;
            });
            return l;
        }
        catch (Throwable t) { return List.of(); }
    }

    /** Plus bas des identifiants fictifs vus dans le jeu (0x7FFF0000 et au-dessus : jamais un vrai mobi). */
    static final int ID_FICTIF_MIN = 0x7FFF0000;
    private static final java.util.Set<Integer> fictifsVus = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Identifiant qui n'est pas un vrai mobi du serveur : marqueurs de la
     * grille, fantomes des calques, et la plage 0x7FFF0000.. (un « mobi »
     * 0x7FFF0001 pris pour une dalle magique faisait rater toute une reprise).
     * A filtrer partout ou l'on cherche un mobi a deplacer ou ramasser.
     */
    static boolean idFictif(int id) {
        // ATTENTION : les mobis poses depuis le Builders Club ont de VRAIS ids dans la
        // plage 0x7FFF.... (constate en jeu : 2147418113, 2147418118...). Seuls nos
        // marqueurs de grille et nos fantomes sont fictifs.
        return GrilleCalcul.estFictif(id) || GroupeFantomes.estFantome(id);
    }

    /** Copie des mobis muraux ; jamais null. */
    public static List<HWallItem> murs() {
        EtatSalle s = etat();
        if (s == null) return List.of();
        try {
            List<HWallItem> l = s.getWallItems();
            if (l == null) return List.of();
            l.removeIf(it -> idFictif(it.getId()));
            return l;
        }
        catch (Throwable t) { return List.of(); }
    }

    public static HFloorItem sol(int id) {
        EtatSalle s = etat();
        try { return s == null ? null : s.furniFromId(id); } catch (Throwable t) { return null; }
    }

    public static HWallItem mur(int id) {
        EtatSalle s = etat();
        try { return s == null ? null : s.wallItemFromId(id); } catch (Throwable t) { return null; }
    }

    public static boolean furnidataPrete() {
        Moteur gp = gp();
        try { return gp != null && gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady(); }
        catch (Throwable t) { return false; }
    }

    /** Nom technique (classname) d'un type ; null si inconnu. */
    public static String classe(int typeId, boolean mural) {
        if (!furnidataPrete()) return null;
        try {
            Furnidata fd = gp().getFurniDataTools();
            return mural ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
        } catch (Throwable t) { return null; }
    }

    /** Nom affiche dans le jeu, a defaut le nom technique. */
    public static String nom(int typeId, boolean mural) {
        String c = classe(typeId, mural);
        if (c == null) return "type " + typeId;
        try {
            Furnidata fd = gp().getFurniDataTools();
            String n = mural ? fd.getWallItemDetails(c).name : fd.getFloorItemDetails(c).name;
            if (n != null && !n.isBlank()) return n;
        } catch (Throwable ignored) { }
        return c;
    }

    public static Furnidata.Mobi details(String classe) {
        if (classe == null || !furnidataPrete()) return null;
        try { return gp().getFurniDataTools().getFloorItemDetails(classe); }
        catch (Throwable t) { return null; }
    }

    /**
     * Emprise au sol d'un mobi, rotation comprise : {largeur en x, longueur en y}.
     * Rotation 2 et 6 echangent les deux cotes.
     */
    public static int[] emprise(HFloorItem it) {
        int lx = 1, ly = 1;
        Furnidata.Mobi d = details(classe(it.getTypeId(), false));
        if (d != null) { lx = Math.max(1, d.xDim); ly = Math.max(1, d.yDim); }
        int rot = rotation(it);
        return (rot == 2 || rot == 6) ? new int[]{ly, lx} : new int[]{lx, ly};
    }

    /** Rotation 0..7 d'un mobi de sol. */
    public static int rotation(HFloorItem it) {
        try { return it.getFacing().ordinal(); } catch (Throwable t) { return 0; }
    }

    /**
     * Hauteur propre d'un mobi (champ prive sizeZ de HFloorItem), a defaut
     * 0 pour un mobi inconnu. Meme lecture que OngletWired.hauteurDe.
     */
    public static double hauteur(HFloorItem it) {
        if (it == null) return 0;
        try {
            java.lang.reflect.Field f = HFloorItem.class.getDeclaredField("sizeZ");
            f.setAccessible(true);
            Object v = f.get(it);
            if (v != null) return Double.parseDouble(String.valueOf(v));
        } catch (Throwable ignored) { }
        return 0;
    }

    /** Hauteur du sol nu d'une case (0..), -1 si case hors plan ou vide. */
    public static int hauteurSol(int x, int y) {
        EtatSalle s = etat();
        if (s == null) return -1;
        try {
            char c = s.floorHeight(x, y);
            if (c == 'x' || c == 'X' || c == 0) return -1;
            return PoseOutils.hauteurCaractere(c);
        } catch (Throwable t) { return -1; }
    }

    // -------------------------------------------------------------- envois

    public static void envoyer(HPacket p) {
        Moteur gp = gp();
        if (gp != null) gp.sendToServer(p);
    }

    /**
     * Ecart minimal entre deux envois d'une rafale (poses, deplacements,
     * @altitude...) : le serveur refuse ou ignore les envois trop rapproches.
     * Un seul rythme pour toutes les rafales de l'Atelier, meme lancees par
     * des fils differents.
     *
     * Reglable dans Parametres > Envois (preference « envoi.ecart », 50..300 ms,
     * 150 par defaut). Une action refusee est d'abord REESSAYEE (REESSAIS fois,
     * apres pauseReessai) ; si des actions restent refusees malgre leurs
     * reessais, le FREIN (signalerRefus) remonte l'ecart a 150 ms pour la
     * session (jamais plus). ECART_MS reste la valeur par defaut, pour les
     * rythmes qui s'y comparent.
     */
    public static final long ECART_MS = 150;
    static final int ECART_MIN = 50, ECART_MAX = 300, ECART_DEFAUT = 150, ECART_PAS = 10;
    static final String PREF_ECART = "envoi.ecart";

    private static final Object RYTHME = new Object();
    private static long prochainEnvoi = 0;
    private static final Frein FREIN = new Frein(lireEcart());
    private static final List<Runnable> ecouteursEcart = new CopyOnWriteArrayList<>();

    /** Ecart effectif entre deux envois d'une rafale (reglage, ou plus si le frein a joue). */
    public static int ecart() { return FREIN.effectif(); }

    /** Le reglage de l'utilisatrice (50..300 ms). */
    public static int ecartVoulu() { return FREIN.voulu(); }

    /** Le frein a-t-il allonge l'ecart pendant cette session ? */
    public static boolean freine() { return FREIN.effectif() > FREIN.voulu(); }

    /** Change le reglage (borne, enregistre) ; le frein repart de zero. */
    public static void ecartVoulu(int ms) {
        int v = borner(ms);
        FREIN.regler(v);
        try { java.util.prefs.Preferences.userRoot().node("atelier").putInt(PREF_ECART, v); } catch (Throwable ignored) { }
        Journal.debug("envois : écart réglé à " + v + " ms.");
        prevenirEcart();
    }

    /** Appele (sur un fil quelconque) quand l'ecart voulu ou effectif change. */
    public static void surChangementEcart(Runnable r) { ecouteursEcart.add(r); }

    private static void prevenirEcart() {
        for (Runnable r : ecouteursEcart) try { r.run(); } catch (Throwable ignored) { }
    }

    /** Reglage borne a 50..300 ms, au pas de 10 (logique pure). */
    static int borner(int ms) {
        int v = Math.max(ECART_MIN, Math.min(ECART_MAX, ms));
        return (int) (Math.round(v / (double) ECART_PAS) * ECART_PAS);
    }

    private static int lireEcart() {
        try { return borner(java.util.prefs.Preferences.userRoot().node("atelier").getInt(PREF_ECART, ECART_DEFAUT)); }
        catch (Throwable t) { return ECART_DEFAUT; }
    }

    /** Reessais d'une action refusee ou ignoree, avant de passer a la suivante. */
    static final int REESSAIS = 2;

    /** Courte attente avant de renvoyer une action refusee : 2 fois l'ecart courant. */
    static long attenteReessai() { return 2L * ecart(); }

    public static void pauseReessai() { sommeil(attenteReessai()); }

    /**
     * Une action d'une rafale est RESTEE refusee ou ignoree apres ses reessais
     * (mobi pas apparu, pas arrive, dalle pas ramassee...). Assez de tels refus
     * recents : l'ecart passe a 150 ms pour le reste de la session (s'il etait
     * plus bas), et on le dit une fois. Pas pour un refus definitif (pas dans
     * l'inventaire, BC refuse, pas de droits, salle quittee, Arreter).
     * @param quoi pour le diagnostic (« pose », « déplacement »...)
     */
    public static void signalerRefus(String quoi) {
        int[] cran = FREIN.signal(true, System.currentTimeMillis());
        if (cran == null) return;
        Journal.debug("frein : refus après réessai (dernier : " + quoi + "), écart " + cran[0] + " → " + cran[1] + " ms.");
        Journal.erreur("Le jeu refuse des actions même après réessai : l'Atelier passe à " + cran[1] + " ms.");
        prevenirEcart();
    }

    /** Une action d'une rafale a bien ete prise par le jeu, au besoin apres reessai (fenetre du frein). */
    public static void signalerReussite() { FREIN.signal(false, System.currentTimeMillis()); }

    /** n refus d'un coup (meme raison). */
    public static void signalerRefus(String quoi, int n) { for (int i = 0; i < n; i++) signalerRefus(quoi); }

    /** n reussites d'un coup. */
    public static void signalerReussite(int n) { for (int i = 0; i < n; i++) signalerReussite(); }

    /**
     * Le frein (logique pure, l'heure est donnee) : fenetre glissante des
     * FENETRE dernieres actions signalees, de moins de FENETRE_MS ; une action
     * n'y est un refus que si elle est restee refusee APRES ses reessais.
     * SEUIL refus ou plus : l'ecart effectif passe a 150 ms s'il etait plus bas
     * (une seule fois), sinon rien ; jamais au-dela de 150.
     */
    static final class Frein {
        static final int FENETRE = 20, SEUIL = 3;
        /** Large : une action refusee apres 2 reessais prend plusieurs secondes. */
        static final long FENETRE_MS = 60_000;
        private final long[] temps = new long[FENETRE];
        private final boolean[] refus = new boolean[FENETRE];
        private int n = 0, tete = 0;              // tete : prochaine case ecrite
        private volatile int voulu, effectif;

        Frein(int voulu) { this.voulu = voulu; this.effectif = voulu; }

        int voulu() { return voulu; }
        int effectif() { return effectif; }

        synchronized void regler(int v) {
            voulu = v; effectif = v; n = 0; tete = 0;
        }

        /** L'ecart apres le frein (logique pure) : sous 150, 150 ; sinon inchange. */
        static int cran(int e) { return Math.max(e, ECART_DEFAUT); }

        /** Note une action ; rend {avant, apres} si l'ecart vient de monter, null sinon. */
        synchronized int[] signal(boolean estRefus, long t) {
            if (effectif >= ECART_DEFAUT) return null;          // deja a 150 ou plus : le frein ne fait rien
            temps[tete] = t; refus[tete] = estRefus;
            tete = (tete + 1) % FENETRE;
            if (n < FENETRE) n++;
            if (!estRefus) return null;
            int compte = 0;
            for (int i = 0; i < n; i++) {
                int k = Math.floorMod(tete - 1 - i, FENETRE);
                if (t - temps[k] > FENETRE_MS) break;          // plus ancien : hors fenetre (et les suivants aussi)
                if (refus[k]) compte++;
            }
            if (compte < SEUIL) return null;
            n = 0;
            int avant = effectif, apres = cran(avant);
            if (apres == avant) return null;
            effectif = apres;
            return new int[]{avant, apres};
        }
    }

    /** Attend son tour (ecart() apres l'envoi precedent) ; a appeler juste avant d'envoyer. */
    public static void espacer() {
        long attente;
        int e = ecart();
        synchronized (RYTHME) {
            long t = System.currentTimeMillis();
            long a = Math.max(t, prochainEnvoi);
            prochainEnvoi = a + e;
            attente = a - t;
        }
        if (attente > 0) sommeil(attente);
    }

    /** Un envoi vient de finir (apres une operation longue) : le suivant attendra ecart() a partir de maintenant. */
    public static void envoiFait() {
        int e = ecart();
        synchronized (RYTHME) { prochainEnvoi = Math.max(prochainEnvoi, System.currentTimeMillis() + e); }
    }

    /** envoyer, a son tour dans le rythme des rafales. */
    public static void envoyerEspace(HPacket p) { espacer(); envoyer(p); }

    /** MoveObject(id, x, y, rotation) : deplace un mobi de sol. */
    public static void deplacerSol(int id, int x, int y, int rot) {
        envoyer(new HPacket("MoveObject", HMessage.Direction.TOSERVER, id, x, y, rot));
    }

    /** MoveWallItem(id, position) : deplace un mobi mural. */
    public static void deplacerMur(int id, String position) {
        envoyer(new HPacket("MoveWallItem", HMessage.Direction.TOSERVER, id, position));
    }

    /** PickupObject(categorie, id) : 2 = sol, 1 = mur (client Flash). */
    public static void ramasser(int id, boolean mural) {
        envoyer(new HPacket("PickupObject", HMessage.Direction.TOSERVER, mural ? 1 : 2, id));
    }

    /** PlaceObject depuis l'inventaire : "idInventaire x y rot" pour un mobi de sol. */
    /** Format de l'ancien moteur de pose : « -idInventaire x y rot ». */
    public static void poserSol(int idInventaire, int x, int y, int rot) {
        envoyer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                "-" + Math.abs(idInventaire) + " " + x + " " + y + " " + rot));
    }

    // ------------------------------------------------------- ecoute des clics

    private static final List<Consumer<HPoint>> clicsCase = new CopyOnWriteArrayList<>();
    private static final List<Consumer<HFloorItem>> clicsMobi = new CopyOnWriteArrayList<>();
    private static volatile boolean branche = false, enCours = false;

    /** Appele (hors fil JavaFX) a chaque clic sur une case du sol. */
    public static void surClicCase(Consumer<HPoint> c) { clicsCase.add(c); installer(); }

    /** Appele (hors fil JavaFX) a chaque clic sur un mobi de sol. */
    public static void surClicMobi(Consumer<HFloorItem> c) { clicsMobi.add(c); installer(); }

    public static void retirer(Object ecouteur) {
        clicsCase.remove(ecouteur);
        clicsMobi.remove(ecouteur);
    }

    /**
     * Branche l'ecoute une seule fois. Les onglets sont construits AVANT que
     * le moteur de l'Atelier soit demarre : on reessaie donc jusqu'a ce qu'il soit la.
     */
    public static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900 && !branche; i++) {
                Moteur gp = gp();
                if (gp != null) {
                    try {
                        gp.intercept(HMessage.Direction.TOSERVER, m -> {
                            try { examiner(m); } catch (Throwable ignored) { }
                        });
                        branche = true;
                        Journal.debug("ecoute partagee des clics active.");
                        return;
                    } catch (Throwable ignored) { }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-salle-clics");
        t.setDaemon(true);
        t.start();
    }

    private static void examiner(HMessage m) {
        int taille = m.getPacket().getBytesLength();
        if (taille > 40) return;
        EtatSalle s = etat();
        if (s == null) return;
        HPacket p = m.getPacket();                  // lectures a position fixe : pas de copie

        // Clic au sol : deplacement d'avatar, deux petits entiers x,y.
        if (taille >= 14 && taille <= 20 && !clicsCase.isEmpty()) {
            try {
                int a = p.readInteger(6), b = p.readInteger(10);
                if (a >= 0 && a < 200 && b >= 0 && b < 200 && s.floorHeight(a, b) != 'x') {
                    HPoint c = new HPoint(a, b);
                    for (Consumer<HPoint> k : clicsCase) k.accept(c);
                    return;
                }
            } catch (Throwable ignored) { }
        }

        if (clicsMobi.isEmpty()) return;
        for (int off = 6; off + 4 <= taille; off++) {
            int v;
            try { v = p.readInteger(off); } catch (Throwable e) { break; }
            HFloorItem it = s.furniFromId(v);
            if (it != null) { for (Consumer<HFloorItem> k : clicsMobi) k.accept(it); return; }
        }
        // forme texte des identifiants (client Flash)
        byte[] o = p.toBytes();
        for (int i = 6; i < o.length; ) {
            if (o[i] < '0' || o[i] > '9') { i++; continue; }
            int d = i;
            while (i < o.length && o[i] >= '0' && o[i] <= '9') i++;
            if (i - d > 10) continue;
            try {
                long v = Long.parseLong(new String(o, d, i - d, java.nio.charset.StandardCharsets.ISO_8859_1));
                if (v <= 0 || v > Integer.MAX_VALUE) continue;
                HFloorItem it = s.furniFromId((int) v);
                if (it != null) { for (Consumer<HFloorItem> k : clicsMobi) k.accept(it); return; }
            } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- divers

    public static void sommeil(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Lance un travail hors du fil JavaFX, en fil demon nomme. */
    public static void tache(String nom, Runnable r) {
        Thread t = new Thread(() -> {
            try { r.run(); } catch (Throwable e) {
                System.err.println("[Atelier] " + nom + " : " + e);
                e.printStackTrace();
            }
        }, "atelier-" + nom);
        t.setDaemon(true);
        t.start();
    }
}

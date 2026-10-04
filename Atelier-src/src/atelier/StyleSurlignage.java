package atelier;

import java.util.prefs.Preferences;

/**
 * Comment le jeu met en valeur ce qui est choisi (selection des calques, mobi
 * d'une fenetre ouverte, Monster Plants...) : contour, remplissage ou les deux,
 * couleur et epaisseur. Le reglage vaut partout.
 *
 * Le client modifie le recoit par un chuchotement qu'il n'affiche pas :
 * « atelier:style=<contour|remplissage|les2>;<RRGGBB>;<1-10> », renvoye a chaque
 * changement, en entrant dans un appart et a la connexion (le jeu oublie tout
 * en changeant de salle).
 */
final class StyleSurlignage {

    private StyleSurlignage() { }

    enum Mode {
        CONTOUR("contour", "Contour"), REMPLISSAGE("remplissage", "Remplissage"), LES2("les2", "Contour + remplissage");
        final String code, nom;
        Mode(String code, String nom) { this.code = code; this.nom = nom; }
        static Mode de(String code) {
            for (Mode m : values()) if (m.code.equals(code)) return m;
            return CONTOUR;
        }
    }

    static final String COULEUR_DEFAUT = "FFE14A";   // le jaune d'avant
    static final int EPAISSEUR_DEFAUT = 3;

    private static final Preferences prefs = Preferences.userRoot().node("atelier");
    private static volatile Mode mode = Mode.de(prefs.get("surlignage.mode", "contour"));
    private static volatile String couleur = couleurValide(prefs.get("surlignage.couleur", COULEUR_DEFAUT));
    private static volatile int epaisseur = borne(prefs.getInt("surlignage.epaisseur", EPAISSEUR_DEFAUT));

    static Mode mode() { return mode; }
    static String couleur() { return couleur; }
    static int epaisseur() { return epaisseur; }

    /** Change le style, le retient et l'envoie au jeu. */
    static void regler(Mode m, String rrggbb, int ep) {
        mode = m == null ? Mode.CONTOUR : m;
        couleur = couleurValide(rrggbb);
        epaisseur = borne(ep);
        prefs.put("surlignage.mode", mode.code);
        prefs.put("surlignage.couleur", couleur);
        prefs.putInt("surlignage.epaisseur", epaisseur);
        envoye = null;                       // a renvoyer
        Salle.tache("surlignage-style", StyleSurlignage::envoyerSiBesoin);
    }

    /** Logique pure : le message pour le client. */
    static String message(Mode m, String rrggbb, int ep) {
        return "atelier:style=" + (m == null ? Mode.CONTOUR : m).code + ";" + couleurValide(rrggbb) + ";" + borne(ep);
    }

    static String couleurValide(String c) {
        if (c == null) return COULEUR_DEFAUT;
        String s = c.trim().replace("#", "").toUpperCase(java.util.Locale.ROOT);
        if (s.startsWith("0X")) s = s.substring(2);
        if (s.length() == 8) s = s.substring(0, 6);              // RRGGBBAA de JavaFX
        return s.matches("[0-9A-F]{6}") ? s : COULEUR_DEFAUT;
    }

    static int borne(int ep) { return Math.max(1, Math.min(10, ep)); }

    // ------------------------------------------------------------ envoi

    private static volatile String envoye = null;   // « salle|message » deja envoye
    private static volatile boolean demarre = false;

    /** Surveille l'entree dans un appart pour renvoyer le style (le jeu l'oublie). */
    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(1000);
                try { envoyerSiBesoin(); } catch (Throwable e) { Journal.debug("style de mise en valeur : " + e); }
            }
        }, "atelier-surlignage-style");
        t.setDaemon(true);
        t.start();
    }

    private static void envoyerSiBesoin() {
        if (!Salle.installeeDepuis(3000)) return;
        if (!ClientModifie.saitStyle()) return;
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        String m = message(mode, couleur, epaisseur);
        String cle = Salle.salleId() + "|" + m;
        if (cle.equals(envoye)) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, m, 0, 0, 0, -1));
        envoye = cle;
        Journal.debug("Style de mise en valeur envoyé au jeu : " + m);
    }
}

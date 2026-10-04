package atelier;

import java.util.prefs.Preferences;

/**
 * Comment le jeu met en valeur ce qui est choisi (selection des calques, mobi
 * d'une fenetre ouverte, Monster Plants...) : contour, remplissage ou les deux,
 * couleur et epaisseur. Le reglage vaut partout.
 *
 * Le client modifie le recoit par un chuchotement qu'il n'affiche pas :
 * « atelier:style=<contour|remplissage|les2>;<RRGGBB>;<1-10>;<opacite 0-100> », renvoye a chaque
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

    static final Mode MODE_DEFAUT = Mode.LES2;
    static final String COULEUR_DEFAUT = "1FC8C8";   // bleu turquoise
    static final int EPAISSEUR_DEFAUT = 4;
    static final int OPACITE_DEFAUT = 45;            // % du remplissage

    private static final Preferences prefs = Preferences.userRoot().node("atelier");
    static {
        // Nouveau defaut (contour + remplissage rose) : applique une fois, meme si l'ancien
        // defaut (contour jaune) avait ete enregistre.
        if (prefs.getInt("surlignage.version", 1) < 2) {
            prefs.put("surlignage.mode", MODE_DEFAUT.code);
            prefs.put("surlignage.couleur", COULEUR_DEFAUT);
            prefs.putInt("surlignage.epaisseur", EPAISSEUR_DEFAUT);
            prefs.putInt("surlignage.opacite", OPACITE_DEFAUT);
            prefs.putInt("surlignage.version", 2);
        }
        // Defaut du contour passe de 3 a 4 px : seulement si l'ancien defaut etait garde.
        if (prefs.getInt("surlignage.version", 2) < 3) {
            if (prefs.getInt("surlignage.epaisseur", EPAISSEUR_DEFAUT) == 3) prefs.putInt("surlignage.epaisseur", EPAISSEUR_DEFAUT);
            prefs.putInt("surlignage.version", 3);
        }
        // Defaut passe du rose au turquoise : seulement si le rose d'avant etait garde.
        if (prefs.getInt("surlignage.version", 3) < 4) {
            if ("FF5FA2".equalsIgnoreCase(prefs.get("surlignage.couleur", ""))) prefs.put("surlignage.couleur", COULEUR_DEFAUT);
            prefs.putInt("surlignage.version", 4);
        }
    }
    private static volatile Mode mode = Mode.de(prefs.get("surlignage.mode", MODE_DEFAUT.code));
    private static volatile String couleur = couleurValide(prefs.get("surlignage.couleur", COULEUR_DEFAUT));
    private static volatile int epaisseur = borne(prefs.getInt("surlignage.epaisseur", EPAISSEUR_DEFAUT));
    private static volatile int opacite = opaciteValide(prefs.getInt("surlignage.opacite", OPACITE_DEFAUT));

    static Mode mode() { return mode; }
    static String couleur() { return couleur; }
    static int epaisseur() { return epaisseur; }
    static int opacite() { return opacite; }

    /** Change le style (opacite inchangee), le retient et l'envoie au jeu. */
    static void regler(Mode m, String rrggbb, int ep) { regler(m, rrggbb, ep, opacite); }

    /** Change le style, le retient et l'envoie au jeu. */
    static void regler(Mode m, String rrggbb, int ep, int op) {
        mode = m == null ? Mode.CONTOUR : m;
        couleur = couleurValide(rrggbb);
        epaisseur = borne(ep);
        opacite = opaciteValide(op);
        prefs.put("surlignage.mode", mode.code);
        prefs.put("surlignage.couleur", couleur);
        prefs.putInt("surlignage.epaisseur", epaisseur);
        prefs.putInt("surlignage.opacite", opacite);
        envoye = null;                       // a renvoyer
        Salle.tache("surlignage-style", StyleSurlignage::envoyerSiBesoin);
    }

    /** Logique pure : le message pour le client. */
    static String message(Mode m, String rrggbb, int ep) { return message(m, rrggbb, ep, OPACITE_DEFAUT); }

    static String message(Mode m, String rrggbb, int ep, int op) {
        return "atelier:style=" + (m == null ? Mode.CONTOUR : m).code + ";" + couleurValide(rrggbb) + ";" + borne(ep)
                + ";" + opaciteValide(op);
    }

    static int opaciteValide(int op) { return Math.max(10, Math.min(100, op)); }

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
        Moteur gp = Salle.gp();
        if (gp == null) return;
        String m = message(mode, couleur, epaisseur, opacite);
        String cle = Salle.salleId() + "|" + m;
        if (cle.equals(envoye)) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, m, 0, 0, 0, -1));
        envoye = cle;
        Journal.debug("Style de mise en valeur envoyé au jeu : " + m);
    }
}

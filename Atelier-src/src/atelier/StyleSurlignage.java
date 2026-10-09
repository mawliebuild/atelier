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
 * en changeant de salle). Les reglages sont enregistres tout de suite dans
 * « mise-en-valeur.json » (dossier de donnees de l'Atelier), relus au demarrage.
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

    /** Version des reglages : les migrations ci-dessous ne s'appliquent qu'a une version plus ancienne. */
    static final int VERSION = 4;
    static final String FICHIER = "mise-en-valeur.json";

    private static volatile Mode mode;
    private static volatile String couleur;
    private static volatile int epaisseur;
    private static volatile int opacite;

    static {
        Reglage r = charger();
        mode = r.mode();
        couleur = r.couleur();
        epaisseur = r.epaisseur();
        opacite = r.opacite();
    }

    // ------------------------------------------------------------ disque

    /**
     * Les reglages sont dans un petit fichier JSON du dossier de donnees de
     * l'Atelier (Dossiers.donneesAtelier(), celui de l'utilisatrice meme sous
     * sudo), ecrit a CHAQUE changement.
     *
     * Avant : java.util.prefs. Sous sudo, ce sont les preferences de root
     * (/var/root), pas celles de l'utilisatrice : un lancement sans sudo (ou
     * l'inverse) lisait un autre jeu de reglages. Et sur Mac, les preferences
     * ne sont ecrites sur le disque que toutes les 30 s ou a l'arret propre de
     * Java : fermer le Terminal ou arreter l'Atelier de force perdait le
     * dernier choix. Les anciennes preferences sont reprises une fois.
     */
    record Reglage(Mode mode, String couleur, int epaisseur, int opacite) {
        static Reglage defaut() { return new Reglage(MODE_DEFAUT, COULEUR_DEFAUT, EPAISSEUR_DEFAUT, OPACITE_DEFAUT); }
    }

    /** Le fichier des reglages (la propriete « atelier.miseenvaleur.fichier » le remplace, pour les essais). */
    static java.io.File fichier() {
        String force = System.getProperty("atelier.miseenvaleur.fichier");
        if (force != null && !force.isBlank()) return new java.io.File(force);
        return new java.io.File(Dossiers.donneesAtelier(), FICHIER);
    }

    private static Reglage charger() {
        java.io.File f = fichier();
        if (f.isFile()) {
            try {
                return lire(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Journal.erreur("Réglages de mise en valeur illisibles (" + f + ") : réglages par défaut.");
                return Reglage.defaut();
            }
        }
        // Premier lancement avec le fichier : reprise des anciennes preferences, puis ecriture.
        Reglage r;
        try { r = reprise(Preferences.userRoot().node("atelier")); }
        catch (Throwable t) { r = Reglage.defaut(); }
        enregistrer(r);
        return r;
    }

    /** Logique pure : le contenu du fichier. */
    static String texte(Reglage r) {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("version", VERSION);
        o.put("mode", r.mode().code);
        o.put("couleur", couleurValide(r.couleur()));
        o.put("epaisseur", borne(r.epaisseur()));
        o.put("opacite", opaciteValide(r.opacite()));
        return o.toString(2);
    }

    /**
     * Logique pure : relit le fichier. Une valeur absente ou fausse prend le
     * defaut, sans toucher aux autres. Aucune migration ne s'applique a un
     * fichier : tout ce qu'il contient a ete choisi (ou repris) expres.
     */
    static Reglage lire(String texte) {
        org.json.JSONObject o = new org.json.JSONObject(texte);
        return new Reglage(Mode.de(o.optString("mode", MODE_DEFAUT.code)),
                couleurValide(o.optString("couleur", COULEUR_DEFAUT)),
                borne(o.optInt("epaisseur", EPAISSEUR_DEFAUT)),
                opaciteValide(o.optInt("opacite", OPACITE_DEFAUT)));
    }

    /**
     * Reprise des anciennes preferences (lecture seule). Les migrations d'avant
     * ne valent que pour une version plus ancienne que la leur, et seulement
     * pour un ancien DEFAUT garde tel quel :
     *   - rien d'enregistre : les defauts ;
     *   - version 1 (contour jaune d'origine, jamais choisi) : les defauts ;
     *   - avant 3 : 3 px (ancien defaut) devient 4 px ;
     *   - avant 4 : le rose (ancien defaut) devient turquoise.
     * Une preference ecrite par regler() ne porte pas toujours la version :
     * si des reglages existent sans version, ils sont gardes.
     */
    static Reglage reprise(Preferences p) {
        String m = p.get("surlignage.mode", null);
        if (m == null) return Reglage.defaut();
        int version = p.getInt("surlignage.version", -1);
        if (version == 1) return Reglage.defaut();
        if (version < 0) version = VERSION;                 // choisi sans version : on n'y touche pas
        String c = p.get("surlignage.couleur", COULEUR_DEFAUT);
        int ep = p.getInt("surlignage.epaisseur", EPAISSEUR_DEFAUT);
        int op = p.getInt("surlignage.opacite", OPACITE_DEFAUT);
        if (version < 3 && ep == 3) ep = EPAISSEUR_DEFAUT;
        if (version < 4 && "FF5FA2".equalsIgnoreCase(c)) c = COULEUR_DEFAUT;
        return new Reglage(Mode.de(m), couleurValide(c), borne(ep), opaciteValide(op));
    }

    private static volatile boolean erreurDite = false;

    static synchronized void enregistrerCourant() {
        enregistrer(new Reglage(mode, couleur, epaisseur, opacite));
    }

    /** Ecrit le fichier tout de suite (fichier temporaire puis remplacement), a l'utilisatrice meme sous sudo. */
    static synchronized void enregistrer(Reglage r) {
        java.io.File f = fichier();
        try {
            java.io.File d = f.getAbsoluteFile().getParentFile();
            if (d != null && !d.isDirectory()) {
                java.nio.file.Files.createDirectories(d.toPath());
                rendre(d.toPath());
            }
            java.nio.file.Path tmp = new java.io.File(f.getAbsolutePath() + ".tmp").toPath();
            java.nio.file.Files.write(tmp, texte(r).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            rendre(f.toPath());
            erreurDite = false;
        } catch (Throwable t) {
            if (!erreurDite) {
                erreurDite = true;
                Journal.erreur("Réglage de mise en valeur non enregistré (" + f + ") : " + t.getMessage());
            }
        }
    }

    /** Sous sudo : le fichier appartient a l'utilisatrice, pas a root (au mieux). */
    private static void rendre(java.nio.file.Path p) {
        String sudo = Dossiers.WINDOWS ? null : Dossiers.utilisatriceSudo(Dossiers.sudoUser());
        if (sudo == null || !"root".equals(System.getProperty("user.name"))) return;
        try {
            java.nio.file.attribute.UserPrincipal u =
                    p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(sudo);
            if (!u.equals(java.nio.file.Files.getOwner(p))) java.nio.file.Files.setOwner(p, u);
        } catch (Throwable t) {
            Journal.debug("mise en valeur : propriétaire de " + p + " non changé : " + t);
        }
    }

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
        // tout de suite, sans attendre la fermeture ; chaque ecriture prend les
        // valeurs du moment : la derniere ecrite est toujours le dernier choix
        Salle.tache("surlignage-enregistrer", StyleSurlignage::enregistrerCourant);
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

    private static volatile String envoye = null;   // « salle|chargement|message » deja envoye
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
        // Le jeu oublie le style a chaque chargement de salle, meme la meme
        // (rechargement, Habbo relance) : la generation de l'etat de salle compte.
        EtatSalle s = Salle.etat();
        String cle = Salle.salleId() + "|" + (s == null ? 0 : s.generation()) + "|" + m;
        if (cle.equals(envoye)) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, m, 0, 0, 0, -1));
        envoye = cle;
    }
}

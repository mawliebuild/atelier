package atelier;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le client du jeu modifie pour l'Atelier (HabboAir.swf).
 *
 * Tout passe par les scripts de ~/Documents/Atelier-swf : construire.py
 * fabrique le SWF, installer-mod.sh le pose dans Habbo.app, restaurer.sh
 * remet l'original. L'Atelier tourne en root (sudo) : $HOME vaut alors
 * /var/root, donc on reconstruit les chemins a partir de SUDO_USER, et on
 * lance les scripts AU NOM de l'utilisatrice (sudo -u), pour que les
 * fichiers du Launcher et de travail ne deviennent pas la propriete de root.
 *
 * Windows : pas de sudo, pas de Habbo.app. Le client est
 * %APPDATA%\Habbo Launcher\downloads\air\<version>\...\HabboAir.swf, et tout passe
 * par Atelier-swf\modifier-jeu.py (le meme que lance « Lancer l'Atelier.bat ») :
 * il garde l'original, construit les modifs pour CETTE version et les installe ;
 * --restaurer remet l'original. Python et Java sont ceux embarques dans le
 * dossier de l'Atelier.
 */
public final class ClientModifie {

    /** Version du client pour laquelle la modification est faite. */
    public static final String VERSION_PREVUE = "16";

    /** Les modifications, dans l'ordre d'affichage : nom pour construire.py, libelle. */
    public static final String[][] MODIFS = {
            {"icones", "Boutons-icônes (Tous / Sol / Muraux / Disposition)"},
            {"categories", "Menus Catégorie (collections, comme pixelsemotion) et Année"},
            {"recherche", "Recherche du haut étendue (nom, catégorie, année, type)"},
            {"pagination", "Pagination avec flèches et numéro de page"},
            {"surlignage", "Mobis sélectionnés des calques mis en valeur (au lieu du clignotement)"},
            {"grille", "Voir grille : traits autour des cases, par-dessus les mobis"},
    };

    private ClientModifie() { }

    // ------------------------------------------------------------ chemins

    /** L'utilisatrice reelle (pas root). */
    public static String utilisatrice() {
        String u = System.getenv("SUDO_USER");
        if (u != null && !u.isBlank() && !u.equals("root")) return u;
        String moi = System.getProperty("user.name");
        if (moi != null && !moi.isBlank() && !moi.equals("root")) return moi;
        return "leajeux";
    }

    /** Windows : pas de sudo, pas de Habbo.app ; le client est un dossier avec HabboAir.swf. */
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    public static File maison() {
        if (WINDOWS) return new File(System.getProperty("user.home"));
        File f = new File("/Users/" + utilisatrice());
        return f.isDirectory() ? f : new File(System.getProperty("user.home"));
    }

    /**
     * Le dossier de l'Atelier (celui d'Atelier.jar : Documents\Atelier sous
     * Windows, ou le lanceur l'installe), ou null s'il est introuvable.
     */
    static File dossierAtelier() {
        try {
            File f = new File(ClientModifie.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (f.isFile()) f = f.getParentFile();
            if (f != null && new File(f, "Atelier.jar").isFile()) return f;
        } catch (Throwable ignored) { }
        File d = new File(System.getProperty("user.dir", ""));
        if (new File(d, "Atelier.jar").isFile()) return d;
        d = new File(maison(), "Documents/Atelier");
        return d.isDirectory() ? d : null;
    }

    public static File dossierSwf() {
        if (WINDOWS) {
            // a cote du dossier de l'Atelier (le lanceur copie les deux dans Documents)
            File a = dossierAtelier();
            if (a != null && a.getParentFile() != null) {
                File s = new File(a.getParentFile(), "Atelier-swf");
                if (s.isDirectory()) return s;
            }
        }
        return new File(maison(), "Documents/Atelier-swf");
    }

    public static File dossierLauncher() {
        if (WINDOWS) {
            String ad = System.getenv("APPDATA");
            return new File(ad != null ? ad : maison() + "/AppData/Roaming", "Habbo Launcher");
        }
        return new File(maison(), "Library/Application Support/Habbo Launcher");
    }

    public static File appJeu() {
        File v = new File(dossierLauncher(), "downloads/air/" + VERSION_PREVUE);
        if (!WINDOWS) return new File(v, "Habbo.app");
        // Windows : les numeros de version ne suivent pas ceux du Mac. modifier-jeu.py
        // adapte chaque client installe ; on lit le plus recent qui a un HabboAir.swf.
        return appJeuWindows(new File(dossierLauncher(), "downloads/air").listFiles(File::isDirectory), v);
    }

    /** Logique pure : le dossier de version au plus grand numero qui contient le client, sinon v. */
    static File appJeuWindows(File[] l, File v) {
        File recent = null;
        if (l != null)
            for (File d : l) {
                if (!new File(d, "HabboAir.swf").isFile() && chercher(d, "HabboAir.swf", 4) == null) continue;
                if (recent == null || numero(d) > numero(recent)) recent = d;
            }
        return recent != null ? recent : v;
    }

    private static int numero(File d) {
        try { return Integer.parseInt(d.getName().replaceAll("\\D", "")); } catch (Exception e) { return -1; }
    }

    /** Le client modifie deja construit (livre dans le paquet) : toutes les modifs. Mac seulement. */
    public static File swfPret() {
        return new File(dossierSwf(), "travail/HabboAir-atelier.swf");
    }

    // ------------------------------------------------------------ Windows : modifier-jeu.py

    /** Le script qui adapte et installe les modifs pour le client de cet ordinateur. */
    static File scriptJeu() { return new File(dossierSwf(), "modifier-jeu.py"); }

    /** Son journal (le detail d'un echec). */
    static File journalJeu() { return new File(dossierSwf(), "modifier-jeu.log"); }

    /**
     * Une seule voie pour modifier le jeu : modifier-jeu.py (s'adapte a chaque
     * version), lance par le Python embarque. Sur un Mac sans Python embarque
     * (poste de dev), on garde l'ancienne voie construire.py + installer-mod.sh.
     */
    static boolean parScript() { return WINDOWS || (pythonEmbarque() != null && scriptJeu().isFile()); }

    /** Python embarque de l'Atelier (Windows : <Atelier>\python\python.exe), ou null. */
    static File pythonEmbarque() {
        File a = dossierAtelier();
        if (a == null) return null;
        File p = new File(a, WINDOWS ? "python/python.exe" : "python/python/bin/python3");
        return p.isFile() ? p : null;
    }

    /**
     * Logique pure : la commande qui lance modifier-jeu.py (avec --restaurer
     * pour remettre l'original).
     */
    static List<String> commandeScript(String python, String script, boolean restaurer) {
        List<String> c = new ArrayList<>();
        c.add(python);
        c.add(script);
        if (restaurer) c.add("--restaurer");
        return c;
    }

    /**
     * Logique pure : le message de resultat de modifier-jeu.py d'apres son
     * code de sortie (0 bien, 2 Habbo ouvert, autre : souci) et sa derniere ligne.
     */
    static String bilanScript(int code, String derniere, boolean restaurer, String journal) {
        String l = derniere == null ? "" : derniere.replaceFirst("^(OK|!|…)\\s*", "").trim();
        if (code == 0) return !l.isEmpty() ? l
                : restaurer ? "Jeu d'origine remis." : "Modifs du jeu installées.";
        if (code == 2) return "Habbo est ouvert : ferme-le puis recommence.";
        return (restaurer ? "Échec de la remise du jeu d'origine" : "Échec de l'installation des modifs du jeu")
                + (l.isEmpty() ? "" : " : " + l) + ". Détail dans " + journal + ".";
    }

    /** Lance modifier-jeu.py (Windows, et Mac avec Python embarque) et dit le resultat. */
    private static Resultat script(boolean restaurer, Consumer<String> ligne) {
        File py = pythonEmbarque();
        String python = py != null ? py.getPath() : python();
        if (python == null) {
            String m = "Python embarqué introuvable (" + new File(dossierAtelier() == null ? new File("Atelier")
                    : dossierAtelier(), WINDOWS ? "python\\python.exe" : "python/python/bin/python3") + ") : re-télécharge le paquet complet de l'Atelier.";
            Journal.erreur(m);
            return new Resultat(1, m);
        }
        Resultat r = lancer(commandeScript(python, scriptJeu().getPath(), restaurer), ligne);
        String m = bilanScript(r.code, r.derniereLigne(), restaurer, journalJeu().getPath());
        if (r.code == 0) Journal.succes(m); else Journal.erreur(m);
        return new Resultat(r.code, r.sortie + m + "\n");
    }

    /**
     * Windows : l'original garde par modifier-jeu.py (etat-jeu.json ->
     * origines/HabboAir-<12 premiers caracteres>.swf) ; si le client installe
     * n'est pas modifie, c'est lui l'original.
     */
    private static File swfOrigineWindows() {
        File inst = swfInstalle();
        String h = empreinte(inst);
        if (h == null) return new File(dossierSwf(), "origines/HabboAir-inconnu.swf");
        try {
            String s = new String(Files.readAllBytes(new File(dossierSwf(), "etat-jeu.json").toPath()), StandardCharsets.UTF_8);
            String o = origineDansEtat(s, h);
            if (o != null) return new File(dossierSwf(), "origines/HabboAir-" + o.substring(0, Math.min(12, o.length())) + ".swf");
        } catch (Exception ignored) { }
        return inst;
    }

    /** Logique pure : dans etat-jeu.json, l'empreinte de l'original du client modifie « h », ou null. */
    static String origineDansEtat(String json, String h) {
        try {
            org.json.JSONObject m = new org.json.JSONObject(json).optJSONObject("modifies");
            if (m == null || !m.has(h)) return null;
            Object v = m.get(h);
            if (v instanceof org.json.JSONObject) return ((org.json.JSONObject) v).optString("origine", null);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) { return null; }
    }

    /** Le client installe est-il celui pour lequel les modifs sont faites (d'origine ou deja modifie par nous) ? */
    public static boolean clientPrevu() {
        if (WINDOWS) return swfInstalle().isFile();     // modifier-jeu.py s'adapte a chaque version
        String e = empreinte(swfInstalle());
        if (e == null) return false;
        return e.equals(empreinte(swfOrigine())) || e.equals(empreinte(swfPret()))
                || e.equals(empreinte(swfConstruit()));
    }

    public static File swfInstalle() {
        if (!WINDOWS) return new File(appJeu(), "Contents/Resources/HabboAir.swf");
        File direct = new File(appJeu(), "HabboAir.swf");
        if (direct.isFile()) return direct;
        File f = chercher(appJeu(), "HabboAir.swf", 4);
        return f != null ? f : direct;
    }

    /** Premier fichier de ce nom sous d (profondeur limitee), ou null. */
    private static File chercher(File d, String nom, int profondeur) {
        File[] l = d.listFiles();
        if (l == null || profondeur < 0) return null;
        for (File f : l) if (f.isFile() && f.getName().equalsIgnoreCase(nom)) return f;
        for (File f : l) if (f.isDirectory()) { File r = chercher(f, nom, profondeur - 1); if (r != null) return r; }
        return null;
    }

    // ------------------------------------------------- ce que sait le client installe

    private static volatile String cleLueur = "";
    private static volatile boolean lueur = false;
    private static volatile boolean capture = false;
    private static volatile boolean grille = false;
    private static volatile boolean zone = false;
    private static volatile boolean dalles = false;
    private static volatile boolean annuler = false;
    private static volatile boolean cases = false;
    private static volatile boolean style = false;

    /** Le client installe sait-il changer le style de mise en valeur (« atelier:style= ») ? */
    public static boolean saitStyle() {
        saitSurligner();
        return style;
    }

    /** Le client installe sait-il le mode Cases (« atelier:cases= », clics dans le vide) ? */
    public static boolean saitCases() {
        saitSurligner();
        return cases;
    }

    /** Le client installe sait-il annuler un deplacement (« atelier:annuler », Echap) ? */
    public static boolean saitAnnuler() {
        saitSurligner();
        return annuler;
    }

    /** Le client installe sait-il laisser les clics traverser les dalles magiques (« atelier:dalles= ») ? */
    public static boolean saitDalles() {
        saitSurligner();
        return dalles;
    }

    /** Le client installe sait-il laisser les clics traverser les mobis (« atelier:zone= ») ? */
    public static boolean saitZone() {
        saitSurligner();
        return zone;
    }

    /** Le client installe sait-il dessiner la grille (« atelier:grille= ») ? */
    public static boolean saitGrille() {
        saitSurligner();
        return grille;
    }

    /**
     * Le client installe sait-il photographier la salle lui-meme
     * (« atelier:capture », ecrit le PNG dans /tmp sans dialogue) ? Lu en meme
     * temps que saitSurligner.
     */
    public static boolean saitCapturer() {
        saitSurligner();
        return capture;
    }

    /**
     * Le client installe sait-il mettre en valeur des mobis (« atelier:surligner= »,
     * option « surlignage » de construire.py) ? On le lit dans le SWF lui-meme :
     * sans ce code, le message s'afficherait en clair dans le chat du jeu.
     * Resultat garde tant que le fichier ne change pas (date et taille).
     */
    public static boolean saitSurligner() {
        File f = swfInstalle();
        String cle = f.lastModified() + ":" + f.length();
        if (cle.equals(cleLueur)) return lueur;
        return lireClient(f, cle);
    }

    /** Une seule lecture du SWF a la fois (il pese des dizaines de Mo une fois decompresse). */
    private static synchronized boolean lireClient(File f, String cle) {
        if (cle.equals(cleLueur)) return lueur;          // lu entre-temps par un autre fil
        boolean r = false, cap = false, gr = false, zo = false, da = false, an = false, ca = false, st = false;
        try {
            byte[] tout = java.nio.file.Files.readAllBytes(f.toPath());
            byte[] corps = tout;
            if (tout.length > 8 && tout[0] == 'C' && tout[1] == 'W' && tout[2] == 'S') {
                java.util.zip.Inflater inf = new java.util.zip.Inflater();
                inf.setInput(tout, 8, tout.length - 8);
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(tout.length * 3);
                byte[] tampon = new byte[1 << 16];
                while (!inf.finished()) {
                    int n = inf.inflate(tampon);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    o.write(tampon, 0, n);
                }
                inf.end();
                corps = o.toByteArray();
            }
            r = contient(corps, "atelier:surligner=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            cap = contient(corps, "atelier:capture".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            gr = contient(corps, "atelier:grille=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            zo = contient(corps, "atelier:zone=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            da = contient(corps, "atelier:dalles=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            an = contient(corps, "atelier:annuler".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            ca = contient(corps, "atelier:cases=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            st = contient(corps, "atelier:style=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Throwable t) {
            Journal.debug("Lecture du client impossible (" + t + ") : sélection en clignotement.");
        }
        lueur = r;
        capture = cap;
        grille = gr;
        zone = zo;
        dalles = da;
        annuler = an;
        cases = ca;
        style = st;
        cleLueur = cle;
        Journal.debug("Client du jeu : " + (r ? "mise en valeur de la sélection disponible."
                : "pas de mise en valeur de la sélection (client d'origine ou ancienne version) : clignotement."));
        return r;
    }

    private static boolean contient(byte[] t, byte[] m) {
        outer:
        for (int i = 0; i + m.length <= t.length; i++) {
            for (int j = 0; j < m.length; j++) if (t[i + j] != m[j]) continue outer;
            return true;
        }
        return false;
    }

    public static File swfOrigine() {
        if (WINDOWS) return swfOrigineWindows();
        return new File(dossierSwf(), "Habbo.app.origine/Contents/Resources/HabboAir.swf");
    }

    /**
     * Le SWF fabrique par l'Atelier (a part, pour ne pas ecraser les essais faits a la main).
     * Windows : rien n'est fabrique a l'avance, modifier-jeu.py construit et installe
     * d'un coup ; on rend donc le script lui-meme (il existe quand tout est pret).
     */
    public static File swfConstruit() {
        if (parScript()) return scriptJeu();
        return new File(dossierSwf(), "travail/HabboAir-atelier-parametres.swf");
    }

    /** python3 : le premier qui existe. */
    public static String python() {
        if (WINDOWS) {
            for (String n : new String[]{"py", "python", "python3"})
                try {
                    Process p = new ProcessBuilder(n, "--version").redirectErrorStream(true).start();
                    String v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (p.waitFor() == 0 && v.contains("Python 3")) return n;
                } catch (Exception ignored) { }
            return null;
        }
        for (String p : new String[]{"/usr/bin/python3", "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3", "/Library/Frameworks/Python.framework/Versions/Current/bin/python3"})
            if (new File(p).canExecute()) return p;
        return null;
    }

    // ------------------------------------------------------------ verifications

    /** Ce qui manque pour travailler, ou null si tout est la. */
    public static String manque() {
        File d = dossierSwf();
        if (!d.isDirectory()) return "Dossier introuvable : " + d + ".";
        if (parScript()) {
            for (String s : new String[]{"modifier-jeu.py", "construire.py"})
                if (!new File(d, s).isFile()) return "Script introuvable : " + new File(d, s) + ".";
            if (pythonEmbarque() == null && python() == null)
                return "Python embarqué introuvable dans le dossier de l'Atelier : re-télécharge le paquet complet.";
            return null;
        }
        for (String s : WINDOWS ? new String[]{"construire.py"} : new String[]{"construire.py", "installer-mod.sh", "restaurer.sh"})
            if (!new File(d, s).isFile()) return "Script introuvable : " + new File(d, s) + ".";
        if (!swfOrigine().isFile()) return "Client d'origine introuvable : " + swfOrigine() + ".";
        // Windows : Python ne sert qu'a reconstruire avec des modifs decochees (voir construire)
        if (!WINDOWS && python() == null) return "Python 3 introuvable (/usr/bin/python3). Installe les outils "
                + "de ligne de commande Xcode : xcode-select --install.";
        if (WINDOWS && !swfInstalle().isFile()) return "Client Habbo introuvable : " + swfInstalle()
                + ". Lance Habbo une fois par le Launcher.";
        return null;
    }

    /** Habbo (le jeu) est-il ouvert ? */
    public static boolean jeuOuvert() {
        if (WINDOWS) {
            try {
                Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq Habbo.exe", "/NH").redirectErrorStream(true).start();
                String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                return o.toLowerCase().contains("habbo.exe");
            } catch (Exception e) { return false; }
        }
        try {
            Process p = new ProcessBuilder("pgrep", "-f", "Habbo.app/Contents/MacOS/Habbo")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Versions du client AIR connues du Launcher (installees, puis la derniere
     * annoncee). Un avertissement si ce n'est pas la 16, sinon null.
     */
    public static String avertissementVersion() {
        if (parScript()) return null;       // modifier-jeu.py s'adapte a chaque version : pas d'avertissement
        File f = new File(dossierLauncher(), "versions.json");
        if (!f.isFile()) return null;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<String> installees = new ArrayList<>();
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(s);
            String annoncee = null;
            while (m.find()) {
                String o = m.group();
                String v = champ(o, "version");
                if (v == null) continue;
                if (o.contains("\"client\"")) {
                    if ("air".equals(champ(o, "client"))) installees.add(v);
                } else if (o.contains("habbo-clients/air/")) annoncee = v;
            }
            List<String> autres = new ArrayList<>();
            for (String v : installees) if (!v.equals(VERSION_PREVUE)) autres.add(v);
            if (!autres.isEmpty() || (!installees.isEmpty() && !installees.contains(VERSION_PREVUE)))
                return "Le Launcher a installé la version " + String.join(", ", autres)
                        + " du client. La modification est faite pour la version " + VERSION_PREVUE
                        + " : elle ne s'applique qu'à celle-ci.";
            if (annoncee != null && !annoncee.equals(VERSION_PREVUE))
                return "Le Launcher annonce la version " + annoncee + " du client. La modification est faite "
                        + "pour la version " + VERSION_PREVUE + " : après la mise à jour, il faudra l'adapter.";
        } catch (Exception e) {
            Journal.debug("Lecture de versions.json : " + e);
        }
        return null;
    }

    private static String champ(String objet, String nom) {
        Matcher m = Pattern.compile("\"" + nom + "\"\\s*:\\s*\"([^\"]*)\"").matcher(objet);
        return m.find() ? m.group(1) : null;
    }

    /** SHA-256 d'un fichier, en hexadecimal, ou null. */
    public static String empreinte(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] tampon = new byte[1 << 16];
            int n;
            while ((n = in.read(tampon)) > 0) md.update(tampon, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ scripts

    /** Resultat d'un script : code de sortie et tout ce qu'il a ecrit. */
    public static final class Resultat {
        public final int code;
        public final String sortie;
        Resultat(int code, String sortie) { this.code = code; this.sortie = sortie; }
        public boolean reussi() { return code == 0; }
        /** La derniere ligne non vide (le message utile des scripts). */
        public String derniereLigne() {
            String[] l = sortie.trim().split("\n");
            return l.length == 0 ? "" : l[l.length - 1].trim();
        }
    }

    public static Resultat construire(List<String> sans, Consumer<String> ligne) {
        // Windows : modifier-jeu.py construit (toutes les modifs) et installe d'un coup, dans installer().
        if (parScript()) return dire(0, sans.isEmpty() ? "Modifs du jeu prêtes à installer."
                : "Toutes les modifs du jeu sont installées (le choix n'est pas encore possible).", ligne);
        // Toutes les modifs : le client deja construit suffit, pas besoin de Python.
        if (sans.isEmpty() && swfPret().isFile()) {
            try {
                swfConstruit().getParentFile().mkdirs();
                Files.copy(swfPret().toPath(), swfConstruit().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return dire(0, "Client modifié prêt (toutes les modifs).", ligne);
            } catch (Exception e) {
                return dire(1, "Copie du client modifié impossible : " + e, ligne);
            }
        }
        if (python() == null) return dire(1, WINDOWS
                ? "Python 3 introuvable : il faut l'installer (python.org, coche « Add python.exe to PATH ») "
                  + "pour décocher des modifs. Avec toutes les modifs cochées, pas besoin de Python."
                : "Python 3 introuvable : xcode-select --install.", ligne);
        List<String> c = new ArrayList<>();
        c.add(python());
        c.add(new File(dossierSwf(), "construire.py").getPath());
        c.add(swfConstruit().getPath());
        if (WINDOWS) { c.add("--origine"); c.add(swfOrigine().getPath()); }
        if (!sans.isEmpty()) { c.add("--sans"); c.add(String.join(",", sans)); }
        return lancer(c, ligne);
    }

    public static Resultat installer(Consumer<String> ligne) {
        if (parScript()) return script(false, ligne);
        List<String> c = new ArrayList<>();
        c.add("/bin/sh");
        c.add(new File(dossierSwf(), "installer-mod.sh").getPath());
        c.add(swfConstruit().getPath());
        return lancer(c, ligne);
    }

    public static Resultat restaurer(Consumer<String> ligne) {
        if (parScript()) return script(true, ligne);
        List<String> c = new ArrayList<>();
        c.add("/bin/sh");
        c.add(new File(dossierSwf(), "restaurer.sh").getPath());
        return lancer(c, ligne);
    }

    private static Resultat dire(int code, String m, Consumer<String> ligne) {
        Journal.info(m);
        if (ligne != null) ligne.accept(m);
        return new Resultat(code, m);
    }

    /**
     * Lance une commande dans le dossier Atelier-swf, au nom de l'utilisatrice
     * si l'Atelier tourne en root. Chaque ligne ecrite part dans le terminal
     * et dans `ligne`.
     */
    private static Resultat lancer(List<String> commande, Consumer<String> ligne) {
        List<String> c = new ArrayList<>();
        String u = utilisatrice();
        boolean root = "root".equals(System.getProperty("user.name"));
        if (root) {
            c.add("/usr/bin/sudo"); c.add("-u"); c.add(u); c.add("-H");
            c.add("/usr/bin/env"); c.add("HOME=" + maison().getPath());
            c.add("JAVA_HOME=" + System.getProperty("java.home"));   // sudo vide l'environnement
        }
        c.addAll(commande);
        Journal.debug("Lance : " + String.join(" ", c));
        StringBuilder tout = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(c).directory(dossierSwf()).redirectErrorStream(true);
            pb.environment().put("HOME", maison().getPath());
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
            if (WINDOWS) {
                // le Java embarque (celui qui fait tourner l'Atelier) sert a construire.py ;
                // sortie en UTF-8 quoi que dise la page de code de la console
                pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
                pb.environment().put("PYTHONIOENCODING", "utf-8");
            }
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    Journal.debug(l);
                    tout.append(l).append('\n');
                    if (ligne != null && !l.isBlank()) ligne.accept(l.trim());
                }
            }
            int code = p.waitFor();
            Journal.debug("Code de sortie : " + code);
            return new Resultat(code, tout.toString());
        } catch (Exception e) {
            System.err.println("[Atelier] Impossible de lancer " + commande.get(0) + " : " + e);
            return new Resultat(-1, tout + "Impossible de lancer " + commande.get(0) + " : " + e.getMessage());
        }
    }
}

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

    public static File dossierSwf() { return new File(maison(), "Documents/Atelier-swf"); }

    public static File dossierLauncher() {
        if (WINDOWS) {
            String ad = System.getenv("APPDATA");
            return new File(ad != null ? ad : maison() + "/AppData/Roaming", "Habbo Launcher");
        }
        return new File(maison(), "Library/Application Support/Habbo Launcher");
    }

    public static File appJeu() {
        File v = new File(dossierLauncher(), "downloads/air/" + VERSION_PREVUE);
        return WINDOWS ? v : new File(v, "Habbo.app");
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

    /** Windows : copie du client d'origine, faite avant la premiere installation. */
    private static File sauvegardeWindows() { return new File(dossierSwf(), "HabboAir-origine-windows.swf"); }

    // ------------------------------------------------- ce que sait le client installe

    private static volatile String cleLueur = "";
    private static volatile boolean lueur = false;
    private static volatile boolean capture = false;
    private static volatile boolean grille = false;
    private static volatile boolean zone = false;
    private static volatile boolean dalles = false;
    private static volatile boolean annuler = false;

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
        boolean r = false, cap = false, gr = false, zo = false, da = false, an = false;
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
        } catch (Throwable t) {
            System.out.println("[Atelier] Lecture du client impossible (" + t + ") : sélection en clignotement.");
        }
        lueur = r;
        capture = cap;
        grille = gr;
        zone = zo;
        dalles = da;
        annuler = an;
        cleLueur = cle;
        System.out.println("[Atelier] Client du jeu : " + (r ? "mise en valeur de la sélection disponible."
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
        return new File(dossierSwf(), "Habbo.app.origine/Contents/Resources/HabboAir.swf");
    }

    /** Le SWF fabrique par l'Atelier (a part, pour ne pas ecraser les essais faits a la main). */
    public static File swfConstruit() {
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
        for (String s : WINDOWS ? new String[]{"construire.py"} : new String[]{"construire.py", "installer-mod.sh", "restaurer.sh"})
            if (!new File(d, s).isFile()) return "Script introuvable : " + new File(d, s) + ".";
        if (!swfOrigine().isFile()) return "Client d'origine introuvable : " + swfOrigine() + ".";
        if (python() == null) return WINDOWS
                ? "Python 3 introuvable. Installe-le depuis python.org (coche « Add python.exe to PATH »)."
                : "Python 3 introuvable (/usr/bin/python3). Installe les outils "
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
            System.out.println("[Atelier] Lecture de versions.json : " + e);
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
        List<String> c = new ArrayList<>();
        c.add(python());
        c.add(new File(dossierSwf(), "construire.py").getPath());
        c.add(swfConstruit().getPath());
        if (!sans.isEmpty()) { c.add("--sans"); c.add(String.join(",", sans)); }
        return lancer(c, ligne);
    }

    public static Resultat installer(Consumer<String> ligne) {
        if (WINDOWS) return installerWindows(ligne);
        List<String> c = new ArrayList<>();
        c.add("/bin/sh");
        c.add(new File(dossierSwf(), "installer-mod.sh").getPath());
        c.add(swfConstruit().getPath());
        return lancer(c, ligne);
    }

    public static Resultat restaurer(Consumer<String> ligne) {
        if (WINDOWS) return restaurerWindows(ligne);
        List<String> c = new ArrayList<>();
        c.add("/bin/sh");
        c.add(new File(dossierSwf(), "restaurer.sh").getPath());
        return lancer(c, ligne);
    }

    /** Windows : pas de signature ; on garde l'original une fois, puis on copie le SWF construit. */
    private static Resultat installerWindows(Consumer<String> ligne) {
        try {
            if (jeuOuvert()) return dire(1, "Ferme Habbo d'abord, puis recommence.", ligne);
            File dest = swfInstalle(), construit = swfConstruit();
            if (!construit.isFile()) return dire(1, "SWF construit introuvable : " + construit, ligne);
            if (!dest.isFile()) return dire(1, "Client Habbo introuvable : " + dest, ligne);
            File sauve = sauvegardeWindows();
            if (!sauve.isFile()) Files.copy(dest.toPath(), sauve.toPath());
            Files.copy(construit.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dire(0, "SWF modifie installe. Lance Habbo par le Launcher, comme d'habitude.", ligne);
        } catch (Exception e) {
            return dire(1, "Installation impossible : " + e, ligne);
        }
    }

    private static Resultat restaurerWindows(Consumer<String> ligne) {
        try {
            if (jeuOuvert()) return dire(1, "Ferme Habbo d'abord, puis recommence.", ligne);
            File sauve = sauvegardeWindows();
            if (!sauve.isFile()) return dire(1, "Pas de copie d'origine : le client n'a jamais été modifié par l'Atelier.", ligne);
            Files.copy(sauve.toPath(), swfInstalle().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dire(0, "Client d'origine remis.", ligne);
        } catch (Exception e) {
            return dire(1, "Restauration impossible : " + e, ligne);
        }
    }

    private static Resultat dire(int code, String m, Consumer<String> ligne) {
        System.out.println("[Atelier] " + m);
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
        }
        c.addAll(commande);
        System.out.println("[Atelier] Lance : " + String.join(" ", c));
        StringBuilder tout = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(c).directory(dossierSwf()).redirectErrorStream(true);
            pb.environment().put("HOME", maison().getPath());
            pb.environment().put("PYTHONUNBUFFERED", "1");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    System.out.println("[Atelier] " + l);
                    tout.append(l).append('\n');
                    if (ligne != null && !l.isBlank()) ligne.accept(l.trim());
                }
            }
            int code = p.waitFor();
            System.out.println("[Atelier] Code de sortie : " + code);
            return new Resultat(code, tout.toString());
        } catch (Exception e) {
            System.out.println("[Atelier] Impossible de lancer " + commande.get(0) + " : " + e);
            return new Resultat(-1, tout + "Impossible de lancer " + commande.get(0) + " : " + e.getMessage());
        }
    }
}

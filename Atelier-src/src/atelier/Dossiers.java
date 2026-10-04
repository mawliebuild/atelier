package atelier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Les dossiers de l'Atelier selon le systeme, au meme endroit.
 *
 *   donnees   macOS    ~/Library/Application Support/Atelier
 *             Windows  %APPDATA%\Atelier            (AppData\Roaming)
 *             autres   ~/.atelier
 *   images    macOS    ~/Pictures                   (Images dans le Finder)
 *             Windows  dossier « Images » du compte (souvent ~\Pictures,
 *                      parfois deplace dans OneDrive)
 *
 * « ~ » est le dossier de l'utilisatrice REELLE : sur Mac l'Atelier tourne
 * sous sudo, user.home vaut alors celui de root et SUDO_USER donne le vrai
 * nom. Sous Windows il n'y a pas de sudo : user.home (%USERPROFILE%).
 *
 * Logique pure : le systeme, les variables d'environnement et le test
 * « ce dossier existe » sont passes en parametres, pour tester le cas
 * Windows depuis un Mac. Les chemins rendus utilisent le separateur du
 * systeme vise. Les methodes sans parametre lisent le systeme courant.
 * Rien de natif ici (le dossier Images de Windows est lu par CaptureWindows).
 */
final class Dossiers {

    private Dossiers() { }

    static final String OS = System.getProperty("os.name", "");
    static final boolean WINDOWS = windows(OS);
    static final boolean MAC = mac(OS);

    static boolean windows(String os) { return os != null && os.toLowerCase(Locale.ROOT).contains("win"); }
    static boolean mac(String os) { return os != null && os.toLowerCase(Locale.ROOT).contains("mac"); }

    static String sep(String os) { return windows(os) ? "\\" : "/"; }

    /** a + sep + b + sep + c..., sans doubler le separateur. */
    static String joindre(String os, String a, String... suite) {
        String s = sep(os);
        StringBuilder b = new StringBuilder(a);
        for (String p : suite) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0 && !b.toString().endsWith(s) && !b.toString().endsWith("/")) b.append(s);
            b.append(p);
        }
        return b.toString();
    }

    private static boolean vide(String s) { return s == null || s.isBlank(); }

    // ------------------------------------------------------------ logique pure

    /** L'utilisatrice derriere sudo, ou null (pas sous sudo, ou sudo vers root). */
    static String utilisatriceSudo(String sudoUser) {
        return (!vide(sudoUser) && !"root".equals(sudoUser)) ? sudoUser : null;
    }

    /** Dossier personnel de l'utilisatrice reelle. */
    static String maison(String os, String userHome, String sudoUser, Predicate<String> estDossier) {
        if (windows(os)) return userHome;
        String u = utilisatriceSudo(sudoUser);
        if (u == null) return userHome;
        String m = mac(os) ? "/Users/" + u : "/home/" + u;
        return estDossier == null || estDossier.test(m) ? m : userHome;
    }

    /** %APPDATA% (Windows), ou maison\AppData\Roaming s'il manque. */
    static String appData(String os, String maison, String appDataEnv) {
        return !vide(appDataEnv) ? appDataEnv : joindre(os, maison, "AppData", "Roaming");
    }

    /** Dossier de donnees d'une appli (« Atelier », « G-Presets », « Habbo Launcher »...). */
    static String donnees(String os, String maison, String appDataEnv, String appli) {
        if (windows(os)) return joindre(os, appData(os, maison, appDataEnv), appli);
        if (mac(os)) return joindre(os, maison, "Library", "Application Support", appli);
        return joindre(os, maison, "." + appli.toLowerCase(Locale.ROOT).replace(' ', '-'));
    }

    /**
     * Le dossier Images. Windows : le dossier connu du compte s'il est lu
     * (imagesConnu), sinon ~\Pictures s'il existe, sinon celui de OneDrive
     * (« Pictures » ou « Images »), sinon ~\Pictures quand meme.
     */
    static String images(String os, String maison, String imagesConnu, String oneDrive, Predicate<String> estDossier) {
        if (!windows(os)) return joindre(os, maison, "Pictures");
        if (!vide(imagesConnu)) return imagesConnu;
        String local = joindre(os, maison, "Pictures");
        if (estDossier == null || estDossier.test(local)) return local;
        if (!vide(oneDrive))
            for (String n : new String[]{"Pictures", "Images"}) {
                String d = joindre(os, oneDrive, n);
                if (estDossier.test(d)) return d;
            }
        return local;
    }

    /**
     * Les dossiers ou un jeu ou un navigateur enregistre d'habitude une image :
     * Bureau, Images, Telechargements, Documents. Sous Windows, aussi leurs
     * equivalents dans OneDrive (noms anglais ou francais). Sans doublon.
     */
    static List<String> habituels(String os, String maison, String images, String oneDrive) {
        List<String> l = new ArrayList<>();
        ajouter(l, joindre(os, maison, "Desktop"));
        ajouter(l, images != null ? images : joindre(os, maison, "Pictures"));
        ajouter(l, joindre(os, maison, "Pictures"));
        ajouter(l, joindre(os, maison, "Downloads"));
        ajouter(l, joindre(os, maison, "Documents"));
        if (windows(os) && !vide(oneDrive))
            for (String n : new String[]{"Desktop", "Bureau", "Pictures", "Images", "Documents"})
                ajouter(l, joindre(os, oneDrive, n));
        return l;
    }

    private static void ajouter(List<String> l, String d) {
        for (String x : l) if (x.equalsIgnoreCase(d)) return;
        l.add(d);
    }

    // ------------------------------------------------------------ systeme courant

    private static final Predicate<String> EXISTE = p -> new File(p).isDirectory();

    static String sudoUser() { return System.getenv("SUDO_USER"); }

    /** OneDrive personnel (Windows), ou null. */
    static String oneDrive() {
        String o = System.getenv("OneDrive");
        if (vide(o)) o = System.getenv("OneDriveConsumer");
        if (vide(o)) o = System.getenv("OneDriveCommercial");
        return vide(o) ? null : o;
    }

    static File maison() {
        return new File(maison(OS, System.getProperty("user.home"), sudoUser(), EXISTE));
    }

    /** Dossier de donnees d'une appli, pour l'utilisatrice reelle. */
    static File donnees(String appli) {
        return new File(donnees(OS, maison().getPath(), System.getenv("APPDATA"), appli));
    }

    /** Le dossier interne de l'Atelier (calques, prix...). */
    static File donneesAtelier() { return donnees("Atelier"); }

    private static volatile String imagesMemo = null;

    /** Le dossier Images de l'utilisatrice reelle. */
    static File images() {
        String m = imagesMemo;
        if (m == null) {
            String connu = null;
            if (WINDOWS) {
                try { connu = CaptureWindows.dossierImages(); } catch (Throwable t) {
                    Journal.debug("Dossier Images de Windows illisible : " + t);
                }
                if (connu != null && !new File(connu).isDirectory()) connu = null;
            }
            m = images(OS, maison().getPath(), connu, oneDrive(), EXISTE);
            imagesMemo = m;
        }
        return new File(m);
    }

    /** Les dossiers habituels qui existent. */
    static List<File> habituels() {
        List<File> l = new ArrayList<>();
        for (String s : habituels(OS, maison().getPath(), images().getPath(), oneDrive())) {
            File f = new File(s);
            if (f.isDirectory() && !l.contains(f)) l.add(f);
        }
        return l;
    }
}

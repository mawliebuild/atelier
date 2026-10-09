package atelier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Sans l'Atelier, Habbo redevient le jeu normal : quand l'Atelier se ferme
 * (fenetre, terminal ferme, Cmd+Q...), on lance « modifier-jeu.py
 * --restaurer-apres », detache, qui attend que Habbo soit ferme puis remet
 * le jeu d'origine. « Lancer l'Atelier » reinstalle le jeu modifie (et
 * arrete ce gardien s'il attend encore).
 *
 * Les chemins partent du dossier de l'Atelier (Documents/Atelier, d'ou le
 * jar est lance) : Documents/Atelier/python et Documents/Atelier-swf.
 */
final class RetourJeuOrigine {

    private RetourJeuOrigine() { }

    static void installer() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> lancer("--restaurer-apres"), "atelier-retour-jeu"));
        // l'Atelier peut etre lance sans « Lancer l'Atelier » : il remet lui-meme le jeu modifie
        // (des que Habbo est ferme, tout de suite s'il l'est deja)
        lancer("--installer-quand-ferme");
    }

    private static void lancer(String mode) {
        try {
            File atelier = dossierAtelier();
            if (atelier == null) return;
            File docs = atelier.getParentFile();
            File script = new File(docs, "Atelier-swf" + File.separator + "modifier-jeu.py");
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            File py = win ? new File(atelier, "python" + File.separator + "pythonw.exe")
                          : new File(atelier, "python/python/bin/python3");
            if (win && !py.isFile()) py = new File(atelier, "python" + File.separator + "python.exe");
            if (!script.isFile() || !py.isFile()) return;
            List<String> cmd = new ArrayList<>();
            if (!win) {
                // sous sudo : rendu a l'utilisatrice reelle ; nohup : survit a la fermeture du terminal
                String u = Dossiers.sudoUser();
                if (u != null && !u.isBlank() && "root".equals(System.getProperty("user.name"))) {
                    cmd.add("/usr/bin/sudo"); cmd.add("-u"); cmd.add(u);
                }
                cmd.add("/usr/bin/nohup");
            }
            cmd.add(py.getAbsolutePath());
            cmd.add(script.getAbsolutePath());
            cmd.add(mode);
            new ProcessBuilder(cmd).directory(script.getParentFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Throwable ignored) { }
    }

    /** Le dossier Documents/Atelier : celui du jar, sinon le dossier courant. */
    private static File dossierAtelier() {
        try {
            File jar = new File(RetourJeuOrigine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File d = jar.isFile() ? jar.getParentFile() : null;
            if (d != null && new File(d, "python").isDirectory()) return d;
        } catch (Throwable ignored) { }
        File d = new File(System.getProperty("user.dir"));
        return new File(d, "python").isDirectory() ? d : null;
    }
}

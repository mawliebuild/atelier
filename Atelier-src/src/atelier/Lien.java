package atelier;

import java.util.ArrayList;
import java.util.List;

/**
 * Ouvre une adresse web dans le navigateur de l'utilisatrice : sous Windows par
 * « explorer », sur Mac par « open » au nom de l'utilisatrice reelle (l'Atelier
 * tourne sous sudo : sinon le navigateur de root s'ouvrirait).
 */
final class Lien {

    private Lien() { }

    static void ouvrir(String url) {
        Salle.tache("lien", () -> {
            try {
                if (Dossiers.WINDOWS) { new ProcessBuilder("explorer.exe", url).start(); return; }
                List<String> cmd = new ArrayList<>(Capture.prefixeUtilisateur());
                cmd.add("/usr/bin/open");
                cmd.add(url);
                Process p = new ProcessBuilder(cmd).start();
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
                    new ProcessBuilder("/usr/bin/open", url).start();
            } catch (Throwable t) {
                Journal.erreur("Impossible d'ouvrir le lien : " + url);
            }
        });
    }

    /** Un lien cliquable (texte souligne, main au survol). */
    static javafx.scene.control.Hyperlink lien(String texte, String url) {
        javafx.scene.control.Hyperlink h = new javafx.scene.control.Hyperlink(texte);
        h.setOnAction(e -> { ouvrir(url); h.setVisited(false); });
        h.setTooltip(new javafx.scene.control.Tooltip(url));
        return h;
    }
}

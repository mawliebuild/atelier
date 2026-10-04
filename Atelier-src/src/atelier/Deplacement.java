package atelier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Barres deplacables (barre du haut, barres en bas a droite) : on attrape la
 * barre n'importe ou, meme sur un bouton, et on la glisse. Un vrai glissement
 * (plus de quelques pixels) ne declenche pas le bouton ; un simple clic marche
 * comme avant. La barre garde un DECALAGE par rapport a sa place normale
 * (calculee d'apres la fenetre du jeu) : elle suit donc le jeu s'il bouge.
 * Clic droit : « Remettre à sa place ». Decalages gardes dans les preferences
 * (« barre.<cle>.dx / dy »).
 */
final class Deplacement {

    private Deplacement() { }

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userRoot().node("atelier");
    /** En dessous, c'est un clic, pas un glissement. */
    private static final double SEUIL = 5;

    static double dx(String cle) { return PREFS.getDouble("barre." + cle + ".dx", 0); }

    static double dy(String cle) { return PREFS.getDouble("barre." + cle + ".dy", 0); }

    /**
     * Rend la barre deplacable. « replacer » la remet a sa place normale +
     * decalage (appele apres « Remettre à sa place »).
     */
    static void activer(Stage stage, Parent racine, String cle, Runnable replacer) {
        double[] depart = new double[4];          // souris x, y ; fenetre x, y
        boolean[] glisse = {false};
        racine.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            depart[0] = e.getScreenX(); depart[1] = e.getScreenY();
            depart[2] = stage.getX(); depart[3] = stage.getY();
            glisse[0] = false;
        });
        racine.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isPrimaryButtonDown()) return;
            double mx = e.getScreenX() - depart[0], my = e.getScreenY() - depart[1];
            if (!glisse[0] && Math.hypot(mx, my) < SEUIL) return;
            glisse[0] = true;
            stage.setX(depart[2] + mx);
            stage.setY(depart[3] + my);
            e.consume();
        });
        racine.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (!glisse[0]) return;
            glisse[0] = false;
            PREFS.putDouble("barre." + cle + ".dx", dx(cle) + stage.getX() - depart[2]);
            PREFS.putDouble("barre." + cle + ".dy", dy(cle) + stage.getY() - depart[3]);
            desarmer(racine);                     // le bouton sous la souris ne se declenche pas
            e.consume();
        });
        // un glissement n'est pas un clic
        racine.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && !e.isStillSincePress()
                    && Math.hypot(e.getScreenX() - depart[0], e.getScreenY() - depart[1]) >= SEUIL) e.consume();
        });

        MenuItem remettre = new MenuItem("Remettre à sa place");
        remettre.setOnAction(a -> {
            PREFS.remove("barre." + cle + ".dx");
            PREFS.remove("barre." + cle + ".dy");
            replacer.run();
        });
        ContextMenu menu = new ContextMenu(remettre);
        racine.setOnContextMenuRequested(e -> {
            remettre.setDisable(dx(cle) == 0 && dy(cle) == 0);
            menu.show(stage, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private static void desarmer(Node n) {
        if (n instanceof ButtonBase) ((ButtonBase) n).disarm();
        if (n instanceof Parent) for (Node c : ((Parent) n).getChildrenUnmodifiable()) desarmer(c);
    }
}

package atelier;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Redimensionnement a la souris pour une fenetre sans decoration.
 *
 * G-Earth ouvre sa fenetre en StageStyle.TRANSPARENT et se dessine sa propre
 * barre de titre : le systeme ne fournit donc aucune poignee. On les recree
 * sur les bords droit et bas, plus le coin.
 */
public final class Redimension {

    private static final int MARGE = 6;      // zone sensible, en pixels
    private static final int MIN_L = 340;
    private static final int MIN_H = 460;

    private Redimension() { }

    public static void installer(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return;

        final boolean[] redim = {false, false};   // {droite, bas}
        final double[] depart = {0, 0, 0, 0};     // sourisX, sourisY, largeur, hauteur

        scene.setOnMouseMoved(e -> {
            boolean d = e.getSceneX() >= scene.getWidth()  - MARGE;
            boolean b = e.getSceneY() >= scene.getHeight() - MARGE;
            if (d && b)      scene.setCursor(Cursor.SE_RESIZE);
            else if (d)      scene.setCursor(Cursor.E_RESIZE);
            else if (b)      scene.setCursor(Cursor.S_RESIZE);
            else             scene.setCursor(Cursor.DEFAULT);
        });

        scene.setOnMousePressed(e -> {
            redim[0] = e.getSceneX() >= scene.getWidth()  - MARGE;
            redim[1] = e.getSceneY() >= scene.getHeight() - MARGE;
            depart[0] = e.getScreenX();
            depart[1] = e.getScreenY();
            depart[2] = stage.getWidth();
            depart[3] = stage.getHeight();
        });

        scene.setOnMouseDragged(e -> {
            if (redim[0]) {
                double l = depart[2] + (e.getScreenX() - depart[0]);
                if (l >= MIN_L) stage.setWidth(l);
            }
            if (redim[1]) {
                double h = depart[3] + (e.getScreenY() - depart[1]);
                if (h >= MIN_H) stage.setHeight(h);
            }
        });

        scene.setOnMouseReleased(e -> {
            redim[0] = redim[1] = false;
            scene.setCursor(Cursor.DEFAULT);
        });
    }
}

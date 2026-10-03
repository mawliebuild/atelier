package atelier;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Une petite barre d'icones posee contre la barre de mesure (BarreMesure), en
 * bas a droite du jeu, dans le gris de la barre du haut. Chaque icone ouvre
 * la fenetre de l'Atelier sur un outil (un second clic la referme) ; son nom
 * apparait en bulle au survol.
 *
 *   horizontale  a GAUCHE de la barre de mesure, sur sa ligne : « Ma salle »
 *                (mobis, recherche, couleur de decor) ;
 *   verticale    AU-DESSUS de la barre de mesure, alignee a droite : un menu
 *                lateral (Construction, Murs, Floor), de haut en bas.
 *
 * Visible seulement dans un appart, et seulement si sa condition tient (le
 * mode Construction pour la barre verticale).
 */
public class BarreIcones implements Ancrage.Ancrable {

    private static final double DROITE = BarreMesure.DROITE, ECART = 6;

    /** Une icone : cle du menu (Navigation), nom (bulle), trace de l'icone. */
    public record Entree(String cle, String nom, String icone) { }

    private final Stage stage = new Stage();
    private final boolean verticale;
    private final List<ToggleButton> boutons = new ArrayList<>();
    private final javafx.stage.Popup bulle = new javafx.stage.Popup();
    private final javafx.scene.control.Label bulleTexte = new javafx.scene.control.Label();
    private Consumer<String> surChoix = c -> { };
    private BooleanSupplier condition = () -> true;
    private Stage voisine;
    private double[] habbo;

    public BarreIcones(String css, List<Entree> entrees, boolean verticale) {
        this.verticale = verticale;
        this.reduite = prefs.getBoolean(cleReduite(), false);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        Pane barre = verticale ? new VBox(5) : new HBox(5);
        if (barre instanceof VBox) ((VBox) barre).setAlignment(Pos.CENTER);
        else ((HBox) barre).setAlignment(Pos.CENTER_LEFT);
        barre.getStyleClass().addAll("barre", "barre-icones");

        bulleTexte.setStyle("-fx-background-color: #ECEAE0; -fx-text-fill: #1D1C19;"
                + " -fx-border-color: #000000; -fx-border-radius: 4; -fx-background-radius: 4;"
                + " -fx-padding: 3 7 3 7; -fx-font-size: 12px; -fx-font-weight: bold;");
        bulle.getContent().add(bulleTexte);

        for (Entree e : entrees) {
            ToggleButton b = new ToggleButton();
            b.setGraphic(Icones.trace(e.icone(), "icone-barre"));
            b.getStyleClass().add("barre-bouton");
            b.setFocusTraversable(false);
            b.setUserData(e.cle());
            b.setOnAction(a -> surChoix.accept(e.cle()));
            survol(b, e.nom());
            boutons.add(b);
            barre.getChildren().add(b);
        }

        // Petite fleche pour reduire / deplier : a gauche (horizontale), en
        // haut (verticale). Reduite, il ne reste qu'elle. Pas d'animation.
        fleche.getStyleClass().add("barre-bouton");
        fleche.setFocusTraversable(false);
        fleche.setStyle(verticale
                ? "-fx-min-width: 36; -fx-pref-width: 36; -fx-max-width: 36;"
                  + " -fx-min-height: 14; -fx-pref-height: 14; -fx-max-height: 14; -fx-padding: 0;"
                : "-fx-min-width: 14; -fx-pref-width: 14; -fx-max-width: 14;"
                  + " -fx-min-height: 36; -fx-pref-height: 36; -fx-max-height: 36; -fx-padding: 0;");
        fleche.setOnAction(a -> { reduite = !reduite; prefs.putBoolean(cleReduite(), reduite); appliquerReduite(); });
        barre.getChildren().add(0, fleche);
        appliquerReduite();

        HBox racine = new HBox(barre);
        racine.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 3 0;");
        Scene scene = new Scene(racine);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);
        // Repliee ou depliee, la barre garde son coin ancre (droite / bas).
        stage.widthProperty().addListener((o, a, b) -> { if (stage.isShowing()) replacer(); });
        stage.heightProperty().addListener((o, a, b) -> { if (stage.isShowing()) replacer(); });
        BarrePremierPlan.menu(stage);
    }

    // ------------------------------------------------------------ reduction

    private final javafx.scene.control.Button fleche = new javafx.scene.control.Button();
    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userRoot().node("atelier");
    private boolean reduite;

    private String cleReduite() { return verticale ? "barre.verticale.reduite" : "barre.horizontale.reduite"; }

    /**
     * Trace de la fleche (boite de 10) : elle montre le sens du mouvement.
     * La barre est ancree a droite (horizontale) ou en bas (verticale) : elle
     * se replie donc vers la droite / le bas, et se deplie vers la gauche / le haut.
     */
    static String trace(boolean verticale, boolean reduite) {
        if (verticale) return reduite ? "M1 7l4-4 4 4" : "M1 3l4 4 4-4";   // haut : deplier, bas : reduire
        return reduite ? "M7 1L3 5l4 4" : "M3 1l4 4-4 4";                  // gauche : deplier, droite : reduire
    }

    private void appliquerReduite() {
        fleche.setGraphic(Icones.trace(trace(verticale, reduite), "icone-barre"));
        for (ToggleButton b : boutons) { b.setVisible(!reduite); b.setManaged(!reduite); }
        survol(fleche, reduite ? "Déplier le menu" : "Réduire le menu");
        bulle.hide();
        if (stage.isShowing()) { stage.sizeToScene(); replacer(); }
    }

    public Stage fenetre() { return stage; }
    public void surChoix(Consumer<String> c) { surChoix = c; }

    /** Condition de plus pour etre visible (en plus d'etre dans un appart). */
    public void condition(BooleanSupplier c) { condition = c; }

    /** Allume l'icone de l'outil ouvert ; une cle qui n'est pas ici les eteint toutes. */
    public void actif(String cle) {
        for (ToggleButton b : boutons) b.setSelected(b.getUserData().equals(cle));
    }

    // ---------------------------------------------------------------- bulle

    /** Le nom au survol : au-dessus du bouton (horizontale), a sa gauche (verticale). */
    private void survol(javafx.scene.layout.Region b, String nom) {
        b.setOnMouseEntered(e -> {
            bulleTexte.setText(nom);
            javafx.geometry.Bounds r = b.localToScreen(b.getBoundsInLocal());
            if (r == null) return;
            bulle.show(stage, r.getMinX(), r.getMinY());
            if (verticale) {
                bulle.setX(r.getMinX() - bulle.getWidth() - 8);
                bulle.setY(r.getMinY() + r.getHeight() / 2 - bulle.getHeight() / 2);
            } else {
                bulle.setX(r.getMinX() + r.getWidth() / 2 - bulle.getWidth() / 2);
                bulle.setY(r.getMinY() - bulle.getHeight() - 6);
            }
        });
        b.setOnMouseExited(e -> bulle.hide());
        b.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> bulle.hide());
    }

    // ---------------------------------------------------------- visibilite

    private volatile boolean actif = false;
    private volatile boolean vu = false;
    private Thread suivi;

    /** L'Atelier est affiche (connecte) : la barre suit alors l'entree dans les apparts. */
    public void visible(boolean a) {
        actif = a;
        BarrePremierPlan.connecte(a);   // connectee : l'Atelier suit le premier plan de Habbo
        rafraichir();
        if (!a || suivi != null) return;
        suivi = new Thread(() -> {
            while (true) {
                rafraichir();
                try { Thread.sleep(800); } catch (InterruptedException e) { return; }
            }
        }, "atelier-barre-icones");
        suivi.setDaemon(true);
        suivi.start();
    }

    /** Montre ou cache selon l'etat actuel (appelable de n'importe quel fil). */
    public void rafraichir() {
        Platform.runLater(() -> {
            boolean dedans;
            try { dedans = actif && Salle.dansUneSalle() && condition.getAsBoolean(); }
            catch (Throwable t) { dedans = false; }
            if (dedans == vu) return;
            vu = dedans;
            if (dedans) { stage.show(); stage.sizeToScene(); replacer(); }
            else { bulle.hide(); stage.hide(); }
        });
    }

    // ------------------------------------------------------------ placement

    /** Se pose contre la barre de mesure, et suit ses changements. */
    public void contre(Stage s) {
        voisine = s;
        s.showingProperty().addListener((o, a, b) -> replacer());
        s.widthProperty().addListener((o, a, b) -> replacer());
        s.heightProperty().addListener((o, a, b) -> replacer());
    }

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(this::replacer);
    }

    private void replacer() {
        double l = stage.getWidth(), h = stage.getHeight();
        double droite, bas;   // bord droit et bas de la zone, en coordonnees ecran
        if (habbo == null) {
            javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
            droite = e.getMaxX() - DROITE;
            bas = e.getMaxY() - BarreMesure.BAS;
        } else {
            droite = habbo[0] + habbo[2] - DROITE;
            bas = habbo[1] + habbo[3] - BarreMesure.BAS;
        }
        boolean v = voisine != null && voisine.isShowing();
        if (verticale) {
            stage.setX(droite - l);
            stage.setY(bas - (v ? voisine.getHeight() + ECART : 0) - h);
        } else {
            stage.setX(droite - (v ? voisine.getWidth() + ECART : 0) - l);
            stage.setY(bas - h);
        }
    }
}

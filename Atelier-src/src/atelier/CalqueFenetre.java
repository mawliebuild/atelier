package atelier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Petite fenetre facon Habbo (comme « Couleur de décor ») pour les actions des
 * calques qui demandent des fleches ou une saisie : deplacer, dupliquer,
 * hauteur, miroir, pivoter, confirmer une suppression, familles. Le panneau
 * des calques ne grandit plus.
 *
 * Barre de titre bleue (deplacable) avec la croix rouge, corps creme, une
 * ligne d'etat (progression, etat) et des boutons en bas. Elle s'ouvre a
 * droite du panneau des calques ; deplacee a la main, elle garde sa place.
 * Echap ferme. Tout se fait sur le fil JavaFX.
 */
final class CalqueFenetre {

    private final Stage stage = new Stage();
    private final Label titre = new Label();
    private final VBox contenu = new VBox(8);
    private final HBox boutons = new HBox(6);
    private final Label etat = Ui.etat();
    private final Window proche;
    private Runnable surFermeture = () -> { };
    private double prisX, prisY;
    private boolean deplacee = false;

    CalqueFenetre(String css, String titreTexte, Window proche) {
        this.proche = proche;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(titreTexte);
        titre.setText(Ui.majuscule(titreTexte));
        titre.getStyleClass().add("fenetre-titre");

        Button croix = new Button();
        croix.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        croix.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        croix.setFocusTraversable(false);
        croix.setOnAction(e -> fermer());
        HBox droite = new HBox(croix);
        droite.setAlignment(Pos.CENTER_RIGHT);
        droite.setPickOnBounds(false);
        droite.setPadding(new Insets(0, 6, 0, 0));
        StackPane barre = new StackPane(titre, droite);
        barre.getStyleClass().add("fenetre-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setCursor(Cursor.MOVE);
        barre.setOnMousePressed(e -> { prisX = e.getScreenX() - stage.getX(); prisY = e.getScreenY() - stage.getY(); });
        barre.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - prisX);
            stage.setY(e.getScreenY() - prisY);
            deplacee = true;
        });

        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.managedProperty().bind(boutons.visibleProperty());
        VBox corps = new VBox(10, contenu, etat, boutons);
        corps.setPadding(new Insets(12, 14, 14, 14));
        corps.setPrefWidth(300);
        corps.getStyleClass().add("fenetre-corps");

        VBox cadre = new VBox(barre, corps);
        cadre.getStyleClass().add("fenetre");
        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));
        Scene sc = new Scene(racine);
        sc.setFill(Color.TRANSPARENT);
        if (css != null) sc.getStylesheets().add(css);
        CalqueStyle.appliquer(sc);
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fermer(); });
        OutilHistorique.installerRaccourcis(sc);          // Cmd/Ctrl+Z ici aussi
        stage.setScene(sc);
        stage.setOnHidden(e -> { Runnable r = surFermeture; surFermeture = () -> { }; r.run(); });
        BarrePremierPlan.fenetre(stage);
    }

    Stage stage() { return stage; }

    void titre(String t) { titre.setText(Ui.majuscule(t)); }

    /** Remplace le contenu (au-dessus de l'etat et des boutons). */
    void contenu(Node... n) {
        contenu.getChildren().setAll(n);
        Ui.majusculesAuto(contenu);
        ajuster();
    }

    /** Remplace les boutons du bas. */
    void boutons(Node... b) {
        boutons.getChildren().setAll(b);
        Ui.majusculesAuto(boutons);
        ajuster();
    }

    /** Ligne d'etat : progression, ce qui va se passer. Vide = rien. */
    void dire(String s) {
        etat.setText(s == null ? "" : Ui.majuscule(s));
        ajuster();
    }

    void surFermeture(Runnable r) { surFermeture = r == null ? () -> { } : r; }

    boolean ouverte() { return stage.isShowing(); }

    void montrer() {
        if (!stage.isShowing()) {
            stage.show();
            if (!deplacee) placer();
        }
        ajuster();
        stage.toFront();
    }

    void fermer() { if (stage.isShowing()) stage.hide(); }

    /** La hauteur suit le contenu. */
    void ajuster() {
        if (!stage.isShowing()) return;
        javafx.application.Platform.runLater(() -> { if (stage.isShowing()) stage.sizeToScene(); });
    }

    /** A droite du panneau des calques, en haut. */
    private void placer() {
        stage.sizeToScene();
        if (proche != null && proche.isShowing()) {
            stage.setX(proche.getX() + proche.getWidth() + 8);
            stage.setY(proche.getY());
        } else stage.centerOnScreen();
    }

    // ------------------------------------------------------------ morceaux

    /** Un bouton texte ; principal = bleu Habbo. */
    static Button bouton(String texte, boolean principal, Runnable r) {
        Button b = new Button(Ui.majuscule(texte));
        if (principal) { b.getStyleClass().add("primaire"); b.setDefaultButton(true); }
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    /** Bulle qui s'affiche vite au survol. */
    static Tooltip bulle(String texte) {
        Tooltip t = new Tooltip(Ui.majuscule(texte));
        t.setShowDelay(Duration.millis(150));
        t.setHideDelay(Duration.millis(80));
        t.setShowDuration(Duration.seconds(30));
        t.setWrapText(true);
        t.setMaxWidth(300);
        return t;
    }
}

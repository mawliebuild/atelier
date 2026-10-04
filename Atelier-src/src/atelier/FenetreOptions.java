package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Petite fenetre facon Habbo (comme l'aperçu de la photo) qui demande les
 * informations d'une action avant de la lancer : source des mobis, ce qu'il
 * faut prendre... Contenu libre, puis « Annuler » et le bouton d'action.
 * Echap ferme. Fil JavaFX.
 */
final class FenetreOptions {

    private final Stage stage = new Stage();
    private double prisX, prisY;

    /**
     * @param css      feuille de style de l'Atelier (null = aucune)
     * @param titre    titre de la barre
     * @param action   libelle du bouton principal
     * @param valider  appele au clic du bouton principal ; renvoie un message
     *                 d'erreur pour rester ouverte, ou null pour fermer
     */
    static void ouvrir(String css, String titre, String action, java.util.function.Supplier<String> valider, Node... contenu) {
        Platform.runLater(() -> {
            try { new FenetreOptions(css, titre, action, valider, contenu); }
            catch (Throwable t) { Journal.erreur("La fenêtre « " + titre + " » n'a pas pu s'ouvrir", t); }
        });
    }

    private FenetreOptions(String css, String titre, String action, java.util.function.Supplier<String> valider, Node... contenu) {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(titre);

        // Erreur de saisie : elle reste DANS la fenetre (encore ouverte), pas dans le jeu.
        // Pas Ui.etat() : elle enverrait l'erreur au jeu et se cacherait.
        Label etat = new Label("");
        etat.setWrapText(true);
        etat.setMaxWidth(Double.MAX_VALUE);
        etat.getStyleClass().add("etat-ligne");
        etat.visibleProperty().bind(etat.textProperty().isNotEmpty());
        etat.managedProperty().bind(etat.visibleProperty());
        Button ok = new Button(action);
        ok.getStyleClass().add("primaire");
        ok.setDefaultButton(true);
        Button annuler = new Button("Annuler");
        annuler.setOnAction(e -> stage.close());
        ok.setOnAction(e -> {
            String err = valider == null ? null : valider.get();
            if (err != null) { etat.setText(Ui.majuscule(Ui.accorder(err))); Journal.info(err); }
            else stage.close();
        });

        VBox corps = new VBox(10);
        corps.getChildren().addAll(contenu);
        HBox boutons = new HBox(8, annuler, ok);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        corps.getChildren().addAll(boutons, etat);
        corps.setPadding(new Insets(12, 14, 14, 14));
        corps.setPrefWidth(340);
        corps.getStyleClass().add("fenetre-corps");

        // barre de titre facon Habbo
        Label t = new Label(titre);
        t.getStyleClass().add("fenetre-titre");
        Button croix = new Button();
        croix.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        croix.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        croix.setFocusTraversable(false);
        croix.setOnAction(e -> stage.close());
        HBox droite = new HBox(croix);
        droite.setAlignment(Pos.CENTER_RIGHT);
        droite.setPickOnBounds(false);
        droite.setPadding(new Insets(0, 6, 0, 0));
        StackPane barre = new StackPane(t, droite);
        barre.getStyleClass().add("fenetre-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setCursor(Cursor.MOVE);
        barre.setOnMousePressed(e -> { prisX = e.getScreenX() - stage.getX(); prisY = e.getScreenY() - stage.getY(); });
        barre.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - prisX); stage.setY(e.getScreenY() - prisY); });

        VBox cadre = new VBox(barre, corps);
        cadre.getStyleClass().add("fenetre");
        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));
        Scene sc = new Scene(racine);
        sc.setFill(Color.TRANSPARENT);
        if (css != null) sc.getStylesheets().add(css);
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(sc);
        Ui.majusculesAuto(corps);
        stage.show();
        stage.centerOnScreen();
    }

    // ------------------------------------------------------------ morceaux

    /** Choix de la source des mobis : inventaire, BC, inventaire puis BC. */
    static final class Source {
        final ToggleGroup g = new ToggleGroup();
        final RadioButton inv = new RadioButton("Inventaire");
        final RadioButton bc = new RadioButton("BC");
        final RadioButton invBc = new RadioButton("Inventaire, puis BC");

        Source(Generateur.Source defaut) {
            inv.setToggleGroup(g); bc.setToggleGroup(g); invBc.setToggleGroup(g);
            (defaut == Generateur.Source.BC ? bc : defaut == Generateur.Source.INVENTAIRE_PUIS_BC ? invBc : inv).setSelected(true);
        }

        Generateur.Source valeur() {
            if (bc.isSelected()) return Generateur.Source.BC;
            if (invBc.isSelected()) return Generateur.Source.INVENTAIRE_PUIS_BC;
            return Generateur.Source.INVENTAIRE;
        }

        Node bloc() { return Ui.bloc("Mobis pris dans", new VBox(6, inv, bc, invBc)); }
    }
}

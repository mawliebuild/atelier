package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * La photo de l'appart, juste apres la capture : on la voit, on choisit le
 * fond AUTOUR de la salle, et on enregistre.
 *
 *   Couleur de décor   la couleur du toner de l'appart (s'il est allume) ;
 *   Transparent        pas de fond (damier dans l'apercu) ;
 *   Autre couleur      la palette : le fond change en temps reel.
 *
 * Temps reel sans recalcul : l'image detouree (fond transparent) est posee
 * une fois sur un panneau dont seule la couleur change. Le vrai compositage
 * (Capture.cadrer) ne se fait qu'a l'enregistrement, en PNG.
 */
final class ApercuCapture {

    private static final double LARGEUR_MAX = 900, HAUTEUR_MAX = 600;

    /** Ouvre l'apercu (fil JavaFX). planche : la salle detouree ; r : ses reglages. */
    static void ouvrir(String css, Capture.Planche planche, Capture.Reglages r, Runnable reglages) {
        Platform.runLater(() -> {
            try { new ApercuCapture(css, planche, r, reglages); }
            catch (Throwable t) { Journal.erreur("L'aperçu de la photo n'a pas pu s'ouvrir", t); }
        });
    }

    private final Stage stage = new Stage();
    private final Capture.Planche planche;
    private final Capture.Reglages r;
    private final StackPane fond = new StackPane();
    private final Label etat = Ui.etat();
    private double prisX, prisY;

    private ApercuCapture(String css, Capture.Planche planche, Capture.Reglages r, Runnable reglages) {
        this.planche = planche;
        this.r = r;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Photo de l'appart");

        // --- image detouree, posee sur le fond colore
        Capture.Reglages sans = copie(r);
        sans.fond = -1;
        BufferedImage img = Capture.cadrer(planche, sans);
        ImageView vue = new ImageView(versFx(img));
        vue.setPreserveRatio(true);
        double k = Math.min(1, Math.min(LARGEUR_MAX / img.getWidth(), HAUTEUR_MAX / img.getHeight()));
        vue.setFitWidth(img.getWidth() * k);
        vue.setFitHeight(img.getHeight() * k);
        fond.getChildren().add(vue);
        fond.setMaxSize(vue.getFitWidth(), vue.getFitHeight());

        // --- choix du fond
        Capture.Toner toner = null;
        try { toner = Capture.tonerDeLaSalle(); } catch (Throwable ignored) { }
        boolean tonerOk = toner != null && toner.allume();
        int couleurDecor = tonerOk ? toner.couleur() : 0;

        ToggleGroup g = new ToggleGroup();
        RadioButton decor = new RadioButton("Couleur de décor");
        RadioButton transparent = new RadioButton("Transparent");
        RadioButton autre = new RadioButton("Autre couleur");
        for (RadioButton b : new RadioButton[]{decor, transparent, autre}) b.setToggleGroup(g);
        decor.setDisable(!tonerOk);
        if (!tonerOk) decor.setTooltip(new Tooltip("Pas de couleur de décor allumée dans l'appart."));
        ColorPicker palette = new ColorPicker(tonerOk ? couleur(couleurDecor) : Color.web("#7AB6D3"));
        palette.setPrefWidth(56);
        palette.disableProperty().bind(autre.selectedProperty().not());

        Runnable maj = () -> {
            if (decor.isSelected()) peindre(couleur(couleurDecor));
            else if (autre.isSelected()) peindre(palette.getValue());
            else peindre(null);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> maj.run());
        palette.valueProperty().addListener((o, a, b) -> maj.run());
        (tonerOk ? decor : transparent).setSelected(true);
        maj.run();

        Button enregistrer = new Button("Enregistrer");
        enregistrer.getStyleClass().add("primaire");
        Button fermer = new Button("Fermer");
        fermer.setOnAction(e -> stage.close());
        enregistrer.setOnAction(e -> {
            int f = decor.isSelected() ? couleurDecor : autre.isSelected() ? rgb(palette.getValue()) : -1;
            enregistrer.setDisable(true);
            Salle.tache("capture-enregistrer", () -> enregistrer(f, enregistrer));
        });
        Hyperlink plus = new Hyperlink("Plus de réglages");
        plus.setOnAction(e -> { if (reglages != null) reglages.run(); });

        VBox corps = new VBox(10,
                fond,
                Ui.ligne(decor, transparent, autre, palette),
                Ui.ligne(enregistrer, fermer, plus),
                etat);
        corps.setPadding(new Insets(12, 14, 14, 14));
        corps.setAlignment(Pos.TOP_CENTER);
        corps.getStyleClass().add("fenetre-corps");

        // --- barre de titre facon Habbo
        Label titre = new Label("Photo de l'appart");
        titre.getStyleClass().add("fenetre-titre");
        Button croix = new Button();
        croix.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        croix.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        croix.setFocusTraversable(false);
        croix.setOnAction(e -> stage.close());
        HBox boutons = new HBox(croix);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.setPickOnBounds(false);
        boutons.setPadding(new Insets(0, 6, 0, 0));
        StackPane barre = new StackPane(titre, boutons);
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
        FenetresVolantes.suivre(stage);
        stage.show();
        stage.centerOnScreen();
    }

    /** Couleur du fond de l'apercu ; null = damier (transparent). */
    private void peindre(Color c) {
        if (c == null) fond.setBackground(new Background(new BackgroundFill(damier(), null, null)));
        else fond.setBackground(new Background(new BackgroundFill(c, null, null)));
    }

    private static ImagePattern damier() {
        WritableImage w = new WritableImage(16, 16);
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                w.getPixelWriter().setColor(x, y, ((x / 8 + y / 8) % 2 == 0) ? Color.web("#E6E3D8") : Color.web("#FFFFFF"));
        return new ImagePattern(w, 0, 0, 16, 16, false);
    }

    /** Hors fil JavaFX : compose avec le fond choisi et enregistre en PNG. */
    private void enregistrer(int fondRgb, Button bouton) {
        try {
            Capture.Reglages rr = copie(r);
            rr.fond = fondRgb;
            rr.garderFond = false;
            String salle = null;
            try { salle = NomSalle.nomValide(Salle.gp()); } catch (Throwable ignored) { }
            File sortie = Capture.nomDeFichier(salle, Capture.Format.PNG);
            Capture.ecrirePng(Capture.cadrer(planche, rr), sortie);
            Capture.rendre(sortie);
            Ui.succes(etat, "Photo enregistrée : " + sortie.getName() + " (Images › Atelier).");
            Platform.runLater(() -> bouton.setDisable(false));
        } catch (Throwable t) {
            Ui.erreur(etat, "Échec de l'enregistrement de la photo", t);
            Platform.runLater(() -> bouton.setDisable(false));
        }
    }

    // ------------------------------------------------------------ outils

    private static Capture.Reglages copie(Capture.Reglages r) {
        Capture.Reglages c = new Capture.Reglages();
        c.tolerance = r.tolerance;
        c.garderFond = r.garderFond;
        c.seulementSalle = r.seulementSalle;
        c.fond = r.fond;
        return c;
    }

    static WritableImage versFx(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = b.getRGB(0, 0, w, h, null, 0, w);
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return img;
    }

    private static Color couleur(int rgb) {
        return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int rgb(Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16)
             | ((int) Math.round(c.getGreen() * 255) << 8)
             | (int) Math.round(c.getBlue() * 255);
    }
}

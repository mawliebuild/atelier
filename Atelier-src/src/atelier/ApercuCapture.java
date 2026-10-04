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
 * La photo de l'appart, juste apres la capture : on la voit (animee pour un
 * GIF), on choisit le fond AUTOUR de la salle, et on enregistre.
 *
 *   Couleur de décor   la couleur du toner de l'appart (s'il est allume) ;
 *   Transparent        pas de fond (damier dans l'apercu ; pas en JPG) ;
 *   Autre couleur      la palette : le fond change en temps reel.
 *
 * Temps reel sans recalcul : les images detourees (fond transparent) sont
 * posees sur un panneau dont seule la couleur change. Le vrai compositage ne se
 * fait qu'a l'enregistrement, dans le format choisi avant la photo.
 * « Enregistrer » ouvre la fenetre du systeme (Finder / Explorateur) dans
 * Telechargements : on y choisit l'endroit et le nom.
 */
final class ApercuCapture {

    private static final double LARGEUR_MAX = 900, HAUTEUR_MAX = 600;

    /** Ouvre l'apercu. planches : la salle detouree (plusieurs = GIF anime) ; r : ses reglages. */
    static void ouvrir(String css, java.util.List<Capture.Planche> planches, Capture.Reglages r,
                       Capture.Format format, int delaiMs) {
        Platform.runLater(() -> {
            try { new ApercuCapture(css, planches, r, format, delaiMs); }
            catch (Throwable t) { Journal.erreur("L'aperçu de la photo n'a pas pu s'ouvrir", t); }
        });
    }

    private final Stage stage = new Stage();
    private final java.util.List<Capture.Planche> planches;
    private final Capture.Reglages r;
    private final Capture.Format format;
    private final int delai;
    private final StackPane fond = new StackPane();
    private javafx.animation.Timeline anim;

    private ApercuCapture(String css, java.util.List<Capture.Planche> planches, Capture.Reglages r,
                          Capture.Format format, int delaiMs) {
        this.planches = planches;
        this.r = r;
        this.format = format;
        this.delai = delaiMs;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Photo de l'appart");

        // --- images detourees (meme decoupe pour toutes), posees sur le fond colore
        Capture.Reglages sans = copie(r);
        sans.fond = -1;
        java.util.List<BufferedImage> imgs = images(sans);
        BufferedImage img0 = imgs.get(0);
        ImageView vue = new ImageView(versFx(img0));
        vue.setPreserveRatio(true);
        double k = Math.min(1, Math.min(LARGEUR_MAX / img0.getWidth(), HAUTEUR_MAX / img0.getHeight()));
        vue.setFitWidth(img0.getWidth() * k);
        vue.setFitHeight(img0.getHeight() * k);
        fond.getChildren().add(vue);
        fond.setMaxSize(vue.getFitWidth(), vue.getFitHeight());
        if (imgs.size() > 1) {
            java.util.List<WritableImage> fx = new java.util.ArrayList<>();
            for (BufferedImage b : imgs) fx.add(versFx(b));
            int[] i = {0};
            anim = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(Math.max(50, delaiMs)), e -> vue.setImage(fx.get(i[0] = (i[0] + 1) % fx.size()))));
            anim.setCycleCount(javafx.animation.Animation.INDEFINITE);
            anim.play();
            stage.setOnHidden(e -> anim.stop());
        }

        // --- choix du fond
        Capture.Toner toner = null;
        try { toner = Capture.tonerDeLaSalle(); } catch (Throwable ignored) { }
        boolean tonerOk = toner != null && toner.allume();
        int couleurDecor = tonerOk ? toner.couleur() : 0;
        boolean jpg = format == Capture.Format.JPG;

        ToggleGroup g = new ToggleGroup();
        RadioButton decor = new RadioButton("Couleur de décor");
        RadioButton transparent = new RadioButton("Transparent");
        RadioButton autre = new RadioButton("Autre couleur");
        for (RadioButton b : new RadioButton[]{decor, transparent, autre}) b.setToggleGroup(g);
        decor.setDisable(!tonerOk);
        if (!tonerOk) decor.setTooltip(new Tooltip("Pas de couleur de décor allumée dans l'appart."));
        transparent.setDisable(jpg);
        if (jpg) transparent.setTooltip(new Tooltip("Le JPG n'a pas de transparence."));
        ColorPicker palette = new ColorPicker(tonerOk ? couleur(couleurDecor) : jpg ? Color.WHITE : Color.web("#7AB6D3"));
        palette.setPrefWidth(56);
        palette.disableProperty().bind(autre.selectedProperty().not());

        Runnable maj = () -> {
            if (decor.isSelected()) peindre(couleur(couleurDecor));
            else if (autre.isSelected()) peindre(palette.getValue());
            else peindre(null);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> maj.run());
        palette.valueProperty().addListener((o, a, b) -> maj.run());
        (tonerOk ? decor : jpg ? autre : transparent).setSelected(true);
        maj.run();

        Button enregistrer = new Button("Enregistrer…");
        enregistrer.getStyleClass().add("primaire");
        enregistrer.setDefaultButton(true);
        enregistrer.setGraphic(Icones.petite(Icones.ENREGISTRER, 16, true));
        enregistrer.setGraphicTextGap(7);
        Button fermer = new Button("Fermer");
        fermer.setOnAction(e -> stage.close());
        enregistrer.setOnAction(e -> {
            int f = decor.isSelected() ? couleurDecor : autre.isSelected() ? rgb(palette.getValue()) : -1;
            java.io.File dest = choisirFichier();
            if (dest == null) return;
            enregistrer.setDisable(true);
            Salle.tache("capture-enregistrer", () -> enregistrer(f, dest, enregistrer));
        });

        Button galerie = new Button("Ajouter à la galerie");
        galerie.setGraphic(Icones.petite(Icones.GALERIE, 16, false));
        galerie.setGraphicTextGap(7);
        galerie.setOnAction(e -> {
            int f = decor.isSelected() ? couleurDecor : autre.isSelected() ? rgb(palette.getValue()) : -1;
            String salle = null;
            try { salle = NomSalle.nomValide(Salle.gp()); } catch (Throwable ignored) { }
            java.io.File dest = OngletGalerie.fichierLibre(Capture.nomDeFichier(salle, format).getName());
            galerie.setDisable(true);
            Salle.tache("capture-galerie", () -> {
                enregistrer(f, dest, galerie, false);
                if (dest.isFile()) { OngletGalerie.actualiser(); Journal.succes("Photo ajoutée à la galerie."); }
            });
        });

        Label info = Ui.discret(format.name() + (imgs.size() > 1 ? " animé · " + imgs.size() + " images" : "")
                + " · " + img0.getWidth() + " × " + img0.getHeight() + " px");
        VBox corps = new VBox(10,
                fond,
                info,
                Ui.bloc("Fond autour de l'appart", Ui.ligne(decor, transparent, autre, palette)),
                PhotoAppart.boutonsBas(fermer, galerie, enregistrer));
        corps.setStyle("-fx-padding: 12 14 14 14;");   // la feuille (.fenetre-corps) l'emporte sur setPadding
        corps.setAlignment(Pos.TOP_CENTER);
        corps.getStyleClass().add("fenetre-corps");

        Scene sc = new Scene(PhotoAppart.habiller(stage, "Photo de l'appart", corps));
        sc.setFill(Color.TRANSPARENT);
        if (css != null) sc.getStylesheets().add(css);
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(sc);
        FenetresVolantes.suivre(stage);
        stage.show();
        stage.centerOnScreen();
    }

    /** Les images a la meme decoupe (une seule hors GIF anime). */
    private java.util.List<BufferedImage> images(Capture.Reglages rr) {
        if (planches.size() > 1) {
            java.util.List<BufferedImage> l = Capture.assembler(planches, rr);
            if (!l.isEmpty()) return l;
        }
        return java.util.List.of(Capture.cadrer(planches.get(planches.size() - 1), rr));
    }

    /** Fenetre d'enregistrement du systeme, ouverte dans Telechargements, nom propose. */
    private java.io.File choisirFichier() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Enregistrer la photo");
        java.io.File tel = new java.io.File(Capture.maisonReelle(), "Downloads");
        if (tel.isDirectory()) fc.setInitialDirectory(tel);
        String salle = null;
        try { salle = NomSalle.nomValide(Salle.gp()); } catch (Throwable ignored) { }
        fc.setInitialFileName(Capture.nomDeFichier(salle, format).getName());
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "Image " + format.name(), "*." + format.ext));
        // la fenetre du systeme doit passer devant l'apercu
        stage.setAlwaysOnTop(false);
        try {
            java.io.File f = fc.showSaveDialog(stage);
            if (f != null && !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith("." + format.ext))
                f = new java.io.File(f.getParentFile(), f.getName() + "." + format.ext);
            return f;
        } finally {
            stage.setAlwaysOnTop(true);
        }
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

    /** Hors fil JavaFX : compose avec le fond choisi et enregistre dans le format choisi. */
    private void enregistrer(int fondRgb, java.io.File sortie, Button bouton) { enregistrer(fondRgb, sortie, bouton, true); }

    private void enregistrer(int fondRgb, java.io.File sortie, Button bouton, boolean dire) {
        try {
            Capture.Reglages rr = copie(r);
            rr.fond = fondRgb;
            rr.garderFond = false;
            java.util.List<BufferedImage> imgs = images(rr);
            switch (format) {
                case PNG -> Capture.ecrirePng(imgs.get(imgs.size() - 1), sortie);
                case JPG -> Capture.ecrireJpg(imgs.get(imgs.size() - 1), fondRgb < 0 ? 0xFFFFFF : fondRgb, sortie);
                case GIF -> Capture.ecrireGif(imgs, imgs.size() > 1 ? delai : 0, sortie);
            }
            Capture.rendre(sortie);
            if (dire) Journal.succes("Photo enregistrée : " + sortie.getName() + ".");
        } catch (Throwable t) {
            Journal.erreur("Échec de l'enregistrement de la photo", t);
        } finally {
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

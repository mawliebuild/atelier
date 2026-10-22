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
    private final StackPane fond = new StackPane();
    private final ImageView vue = new ImageView();
    private javafx.animation.Timeline anim;
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    /** Les images capturees, detourees, sans fond ni recadrage. */
    private java.util.List<BufferedImage> base;
    /** Reglages de l'apercu : zone gardee (null = tout), nombre d'images gardees, duree d'une image (ms). */
    private int[] coupe;
    private int gardees, delai;
    private boolean recadrage = false;
    private final Label dimLbl = Ui.discret("");
    private final Label info = Ui.discret("");
    private final Recadreur recadreur = new Recadreur(this::majDimensions);
    private Button valider;

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
        base = images(sans);
        gardees = base.size();
        fond.getChildren().addAll(vue, recadreur);
        vue.setPreserveRatio(true);
        recadreur.setVisible(false);
        stage.setOnHidden(e -> { if (anim != null) anim.stop(); });
        afficher();

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

        // --- recadrage : un bouton pour y entrer ; Valider / Annuler et les dimensions pendant
        Button recadrer = new Button("Recadrer");
        recadrer.setTooltip(Ui.bulle("Choisir la zone de la photo à garder. Pour un GIF, toutes ses images sont recadrées."));
        Button toutReprendre = new Button("Annuler le recadrage");
        toutReprendre.setTooltip(Ui.bulle("Revenir à la photo entière."));
        valider = new Button("Valider");
        valider.setTooltip(Ui.bulle("Garder la zone choisie (Entrée)."));
        Button annulerCadre = new Button("Annuler");
        annulerCadre.setTooltip(Ui.bulle("Quitter le recadrage sans rien changer (Échap)."));
        dimLbl.setWrapText(true);
        dimLbl.setMinWidth(0);
        HBox ligneCadre = new HBox(8);
        ligneCadre.setAlignment(Pos.CENTER_LEFT);
        Runnable majCadre = () -> {
            if (recadrage) ligneCadre.getChildren().setAll(valider, annulerCadre, dimLbl);
            else if (coupe != null) ligneCadre.getChildren().setAll(recadrer, toutReprendre);
            else ligneCadre.getChildren().setAll(recadrer);
            enregistrer.setDisable(recadrage);
            galerie.setDisable(recadrage);
            enregistrer.setDefaultButton(!recadrage);
            valider.setDefaultButton(recadrage);
        };
        recadrer.setOnAction(e -> { recadrage = true; afficher(); majCadre.run(); });
        annulerCadre.setOnAction(e -> { recadrage = false; afficher(); majCadre.run(); });
        toutReprendre.setOnAction(e -> { coupe = null; afficher(); majCadre.run(); });
        valider.setOnAction(e -> {
            if (!recadreur.aCadre()) return;
            if (!recadreur.toutLImage()) coupe = recadreur.zone();
            recadrage = false;
            afficher();
            majCadre.run();
        });
        majCadre.run();

        java.util.List<javafx.scene.Node> blocs = new java.util.ArrayList<>(java.util.List.of(fond, info, ligneCadre));

        // --- GIF : duree d'une image et nombre d'images gardees, en temps reel
        if (base.size() > 1) {
            Spinner<Integer> duree = new Spinner<>(50, 5000, Math.max(50, Math.min(5000, delai)), 50);
            duree.setEditable(true);
            duree.setPrefWidth(95);
            duree.valueProperty().addListener((o, x, v) -> {
                if (v == null) return;
                delai = v;
                PREFS.putInt("photo.gif.delai", v);
                afficher();
            });
            Spinner<Integer> nombre = new Spinner<>(2, base.size(), base.size(), 1);
            nombre.setEditable(true);
            nombre.setPrefWidth(95);
            nombre.valueProperty().addListener((o, x, v) -> {
                if (v == null) return;
                gardees = v;
                afficher();
            });
            GridPane grille = new GridPane();
            grille.setHgap(10);
            grille.setVgap(6);
            grille.addRow(0, new Label("Durée d'une image (ms)"), duree);
            grille.addRow(1, new Label("Images gardées"), nombre);
            blocs.add(Ui.bloc("Animation", grille,
                    Ui.aide("Moins d'images gardées : elles sont prises à intervalles réguliers parmi les "
                            + base.size() + " capturées, comme des photos plus espacées.")));
        }
        blocs.add(Ui.bloc("Fond autour de l'appart", Ui.ligne(decor, transparent, autre, palette)));
        blocs.add(PhotoAppart.boutonsBas(fermer, galerie, enregistrer));
        VBox corps = new VBox(10, blocs.toArray(new javafx.scene.Node[0]));
        corps.setStyle("-fx-padding: 12 14 14 14;");   // la feuille (.fenetre-corps) l'emporte sur setPadding
        corps.setAlignment(Pos.TOP_CENTER);
        corps.getStyleClass().add("fenetre-corps");

        Scene sc = new Scene(PhotoAppart.habiller(stage, "Photo de l'appart", corps));
        sc.setFill(Color.TRANSPARENT);
        if (css != null) sc.getStylesheets().add(css);
        sc.setOnKeyPressed(e -> {
            if (e.getCode() != javafx.scene.input.KeyCode.ESCAPE) return;
            if (recadrage) annulerCadre.fire();
            else stage.close();
        });
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

    /** Les indices des images gardees : gardees, a intervalles reguliers parmi n. */
    private static int[] indices(int n, int gardees) {
        int k = Math.max(1, Math.min(n, gardees));
        int[] t = new int[k];
        for (int i = 0; i < k; i++) t[i] = k == 1 ? n - 1 : (int) Math.round(i * (n - 1) / (double) (k - 1));
        return t;
    }

    /** Les images telles qu'elles seront enregistrees : gardees, puis recadrees (si recadre vaut vrai). */
    private java.util.List<BufferedImage> finales(java.util.List<BufferedImage> toutes, boolean recadre) {
        java.util.List<BufferedImage> l = new java.util.ArrayList<>();
        int[] idx = toutes.size() > 1 ? indices(toutes.size(), gardees) : new int[]{toutes.size() - 1};
        for (int i : idx) l.add(recadre && coupe != null ? couper(toutes.get(i), coupe) : toutes.get(i));
        return l;
    }

    private static BufferedImage couper(BufferedImage b, int[] z) {
        int x = Math.min(z[0], b.getWidth() - 1), y = Math.min(z[1], b.getHeight() - 1);
        int w = Math.max(1, Math.min(z[2], b.getWidth() - x)), h = Math.max(1, Math.min(z[3], b.getHeight() - y));
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = o.createGraphics();
        g.drawImage(b, -x, -y, null);
        g.dispose();
        return o;
    }

    /**
     * Met l'apercu a jour : images gardees, recadrees (sauf pendant le
     * recadrage, ou on voit la photo entiere sous le cadre), a la vitesse choisie.
     */
    private void afficher() {
        if (anim != null) anim.stop();
        java.util.List<BufferedImage> l = finales(base, !recadrage);
        java.util.List<WritableImage> fx = new java.util.ArrayList<>();
        for (BufferedImage b : l) fx.add(versFx(b));
        BufferedImage img0 = l.get(0);
        double k = Math.min(1, Math.min(LARGEUR_MAX / img0.getWidth(), HAUTEUR_MAX / img0.getHeight()));
        double w = img0.getWidth() * k, h = img0.getHeight() * k;
        vue.setImage(fx.get(0));
        vue.setFitWidth(w);
        vue.setFitHeight(h);
        fond.setMaxSize(w, h);
        recadreur.setVisible(recadrage);
        if (recadrage) recadreur.image(w, h, img0.getWidth(), img0.getHeight());
        if (fx.size() > 1) {
            int[] i = {0};
            anim = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(Math.max(50, delai)), e -> vue.setImage(fx.get(i[0] = (i[0] + 1) % fx.size()))));
            anim.setCycleCount(javafx.animation.Animation.INDEFINITE);
            anim.play();
        }
        BufferedImage fin = coupe != null && !recadrage ? l.get(0) : img0;
        info.setText(format.name() + (fx.size() > 1 ? " animé · " + Ui.accorder(fx.size() + " image(s)") : "")
                + " · " + fin.getWidth() + " × " + fin.getHeight() + " px");
        if (stage.getScene() != null) stage.sizeToScene();
    }

    private void majDimensions() {
        int[] z = recadreur.zone();
        dimLbl.setText(z == null ? "Trace un rectangle sur la photo." : "Zone gardée : " + z[2] + " × " + z[3] + " px");
        if (valider != null) valider.setDisable(z == null);
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
            java.util.List<BufferedImage> imgs = finales(images(rr), true);
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

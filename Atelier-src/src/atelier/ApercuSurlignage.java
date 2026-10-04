package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Reglages « Mise en valeur » : un mobi d'exemple (la Plante base verte) dessine
 * avec le style choisi, et les choix : contour, remplissage ou les deux,
 * couleur, epaisseur. Le style vaut partout (calques, fenetres, Monster Plants).
 */
final class ApercuSurlignage {

    private ApercuSurlignage() { }

    private static final String NOM_EXEMPLE = "Plante base verte";

    static VBox section() {
        StyleSurlignage.demarrer();      // renvoie le style au jeu a chaque appart
        // --- l'apercu
        StackPane scene = new StackPane();
        scene.setPrefSize(180, 150);
        scene.setMinSize(180, 150);
        scene.setStyle("-fx-background-color: #ECEAE0; -fx-background-radius: 6;");
        StackPane modele = new StackPane(planteDessinee());
        scene.getChildren().add(modele);
        chargerPlante(modele);

        // --- les choix
        ToggleGroup g = new ToggleGroup();
        HBox modes = new HBox(6);
        for (StyleSurlignage.Mode m : StyleSurlignage.Mode.values()) {
            ToggleButton b = new ToggleButton(m.nom);
            b.setToggleGroup(g);
            b.setUserData(m);
            b.setSelected(m == StyleSurlignage.mode());
            modes.getChildren().add(b);
        }
        ColorPicker couleur = new ColorPicker(Color.web("#" + StyleSurlignage.couleur()));
        couleur.getStyleClass().add("button");
        Slider epaisseur = new Slider(1, 10, StyleSurlignage.epaisseur());
        epaisseur.setMajorTickUnit(1);
        epaisseur.setMinorTickCount(0);
        epaisseur.setSnapToTicks(true);
        epaisseur.setPrefWidth(160);
        Label valeurEp = new Label(StyleSurlignage.epaisseur() + " px");
        Button defaut = new Button("Revenir au jaune");
        Label etat = Ui.discret("");

        Runnable appliquer = () -> {
            StyleSurlignage.Mode m = g.getSelectedToggle() == null ? StyleSurlignage.mode()
                    : (StyleSurlignage.Mode) g.getSelectedToggle().getUserData();
            Color c = couleur.getValue();
            int ep = (int) Math.round(epaisseur.getValue());
            valeurEp.setText(ep + " px");
            modele.setEffect(effet(m, c, ep));
            StyleSurlignage.regler(m, hex(c), ep);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) a.setSelected(true);       // toujours un mode choisi
            else appliquer.run();
        });
        couleur.setOnAction(e -> appliquer.run());
        epaisseur.valueProperty().addListener((o, a, b) -> {
            if (!epaisseur.isValueChanging()) appliquer.run();
            else valeurEp.setText(Math.round(b.doubleValue()) + " px");
        });
        epaisseur.valueChangingProperty().addListener((o, a, b) -> { if (!b) appliquer.run(); });
        defaut.setOnAction(e -> {
            for (Toggle t : g.getToggles()) if (t.getUserData() == StyleSurlignage.Mode.CONTOUR) t.setSelected(true);
            couleur.setValue(Color.web("#" + StyleSurlignage.COULEUR_DEFAUT));
            epaisseur.setValue(StyleSurlignage.EPAISSEUR_DEFAUT);
            appliquer.run();
        });
        modele.setEffect(effet(StyleSurlignage.mode(), Color.web("#" + StyleSurlignage.couleur()), StyleSurlignage.epaisseur()));

        // le client du jeu sait-il appliquer ce style ? (lecture du SWF hors fil JavaFX)
        Salle.tache("surlignage-client", () -> {
            boolean ok = ClientModifie.saitStyle();
            Platform.runLater(() -> etat.setText(ok ? ""
                    : "Le jeu garde le style d'origine tant que le client modifié n'est pas à jour : relance « Lancer l'Atelier »."));
        });

        // libelles dans une colonne : la couleur et le curseur commencent au meme endroit
        GridPane reglages = new GridPane();
        reglages.setHgap(10); reglages.setVgap(10);
        HBox ligneCouleur = new HBox(8, couleur, defaut);
        ligneCouleur.setAlignment(Pos.CENTER_LEFT);
        HBox ligneEp = new HBox(8, epaisseur, valeurEp);
        ligneEp.setAlignment(Pos.CENTER_LEFT);
        reglages.addRow(0, new Label("Couleur"), ligneCouleur);
        reglages.addRow(1, new Label("Épaisseur"), ligneEp);
        etat.setMaxWidth(Double.MAX_VALUE);
        modes.getChildren().forEach(n -> ((ToggleButton) n).setMinWidth(Region.USE_PREF_SIZE));
        javafx.scene.layout.FlowPane modesFlux = new javafx.scene.layout.FlowPane(6, 6);
        modesFlux.getChildren().setAll(new java.util.ArrayList<>(modes.getChildren()));
        VBox choix = new VBox(12, modesFlux, reglages, etat);
        HBox.setHgrow(choix, Priority.ALWAYS);
        HBox corps = new HBox(18, scene, choix);
        corps.setAlignment(Pos.CENTER_LEFT);
        return new VBox(12, Ui.bloc("Mise en valeur dans le jeu", corps,
                Ui.aide("Vaut pour tout ce qui est choisi : sélection des calques, mobis d'une fenêtre, Monster Plants.")));
    }

    /** L'effet JavaFX qui imite celui du jeu (contour = halo plein, remplissage = teinte). */
    static Effect effet(StyleSurlignage.Mode m, Color c, int ep) {
        Effect teinte = null;
        if (m != StyleSurlignage.Mode.CONTOUR) {
            ColorInput couche = new ColorInput(-500, -500, 2000, 2000, Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.5));
            Blend b = new Blend(BlendMode.SRC_ATOP);
            b.setTopInput(couche);
            teinte = b;
        }
        if (m == StyleSurlignage.Mode.REMPLISSAGE) return teinte;
        DropShadow halo = new DropShadow(BlurType.GAUSSIAN, c, Math.max(2, ep * 2.5), 0.85, 0, 0);
        if (teinte != null) halo.setInput(teinte);
        return halo;
    }

    static String hex(Color c) {
        return String.format("%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    /** La vraie icone de la Plante base verte (furnidata), sinon le dessin de secours. */
    private static void chargerPlante(StackPane modele) {
        Thread t = new Thread(() -> {
            for (int essai = 0; essai < 40; essai++) {
                Image img = icone();
                if (img != null) {
                    Platform.runLater(() -> {
                        ImageView iv = new ImageView(img);
                        iv.setSmooth(false);                        // pixels nets, comme dans le jeu
                        iv.setPreserveRatio(true);
                        iv.setFitHeight(Math.min(120, img.getHeight() * 3));
                        modele.getChildren().setAll(iv);
                    });
                    return;
                }
                Salle.sommeil(3000);                                // furnidata pas encore chargee
            }
        }, "atelier-apercu-plante");
        t.setDaemon(true);
        t.start();
    }

    private static Image icone() {
        try {
            extension.GPresets gp = AtelierLauncher.moteur();
            if (gp == null || gp.getFurniDataTools() == null || !gp.getFurniDataTools().isReady()) return null;
            for (NomsMobis.Nom n : NomsMobis.chercher(NOM_EXEMPLE, 3)) {
                if (!NomsMobis.normaliser(n.nom).equals(NomsMobis.normaliser(NOM_EXEMPLE))) continue;
                for (String cls : n.classes) {
                    furnidata.details.FloorItemDetails d = gp.getFurniDataTools().getFloorItemDetails(cls);
                    if (d == null || d.revision <= 0) continue;
                    String c = cls.contains("*") ? cls.substring(0, cls.indexOf('*')) : cls;
                    Image img = new Image("https://images.habbo.com/dcr/hof_furni/" + d.revision + "/" + c + "_icon.png",
                            false);
                    if (!img.isError() && img.getWidth() > 0) return img;
                }
            }
        } catch (Throwable e) { Journal.debug("aperçu de la plante : " + e); }
        return null;
    }

    /** Dessin de secours : un pot et des feuilles vertes. */
    private static Node planteDessinee() {
        SVGPath pot = new SVGPath();
        pot.setContent("M38 70 L62 70 L58 96 L42 96 Z");
        pot.setFill(Color.web("#B5651D"));
        SVGPath feuilles = new SVGPath();
        feuilles.setContent("M50 70 C30 60 26 40 34 26 C42 40 46 52 50 70 Z M50 70 C70 60 74 40 66 26 "
                + "C58 40 54 52 50 70 Z M50 70 C46 50 46 30 50 14 C54 30 54 50 50 70 Z");
        feuilles.setFill(Color.web("#4E9A3A"));
        Pane p = new Pane(feuilles, pot);
        p.setPrefSize(100, 100);
        p.setMaxSize(100, 100);
        p.setPadding(new Insets(0));
        return p;
    }
}

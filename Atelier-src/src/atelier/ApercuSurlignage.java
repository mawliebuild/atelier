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

    /** Couleurs proposees d'un clic (la premiere est le defaut). */
    private static final String[][] TEINTES = {
            {"1FC8C8", "Turquoise"}, {"FF5FA2", "Rose"}, {"FFE14A", "Jaune"}, {"3EB6F0", "Bleu"}, {"5BD16B", "Vert"},
            {"A77BFF", "Violet"}, {"FF8A3D", "Orange"}, {"FFFFFF", "Blanc"}};

    static VBox section() {
        StyleSurlignage.demarrer();      // renvoie le style au jeu a chaque appart

        // --- l'apercu : la plante sur une dalle de sol, comme dans le jeu
        StackPane modele = new StackPane(planteDessinee());
        chargerPlante(modele);
        StackPane scene = new StackPane(modele);
        scene.setPrefSize(190, 170);
        scene.setMinSize(190, 170);
        scene.setStyle("-fx-background-color: linear-gradient(to bottom, #DCD8CB, #ECEAE0);"
                + " -fx-background-radius: 8; -fx-border-color: #CFCABB; -fx-border-radius: 8;");
        Label legende = Ui.discret("Aperçu");
        VBox colApercu = new VBox(6, scene, legende);
        colApercu.setAlignment(Pos.TOP_CENTER);

        // --- etat courant (lu une fois, puis suivi par les controles)
        final StyleSurlignage.Mode[] mode = {StyleSurlignage.mode()};
        final Color[] couleur = {Color.web("#" + StyleSurlignage.couleur())};

        // Mode : trois boutons groupes
        ToggleGroup g = new ToggleGroup();
        HBox modes = new HBox(0);
        modes.getStyleClass().add("segmente");
        for (StyleSurlignage.Mode m : StyleSurlignage.Mode.values()) {
            ToggleButton b = new ToggleButton(m.nom);
            b.setToggleGroup(g);
            b.setUserData(m);
            b.setSelected(m == mode[0]);
            b.setMinWidth(Region.USE_PREF_SIZE);
            Ui.bulle(b, m == StyleSurlignage.Mode.CONTOUR ? "Un anneau de couleur autour du mobi."
                    : m == StyleSurlignage.Mode.REMPLISSAGE ? "Le mobi entier est teinté."
                    : "Anneau et teinte ensemble : le plus visible.");
            modes.getChildren().add(b);
        }

        // Couleur : pastilles + choix libre
        FlowPane pastilles = new FlowPane(6, 6);
        ColorPicker libre = new ColorPicker(couleur[0]);
        libre.getStyleClass().add("button");
        Ui.bulle(libre, "Une autre couleur");

        // Epaisseur et opacite
        Slider epaisseur = new Slider(1, 10, StyleSurlignage.epaisseur());
        epaisseur.setMajorTickUnit(1); epaisseur.setMinorTickCount(0); epaisseur.setSnapToTicks(true);
        HBox.setHgrow(epaisseur, Priority.ALWAYS);
        Label valeurEp = new Label();
        valeurEp.setMinWidth(44);
        Slider opacite = new Slider(10, 100, StyleSurlignage.opacite());
        opacite.setMajorTickUnit(10); opacite.setMinorTickCount(1); opacite.setBlockIncrement(5);
        HBox.setHgrow(opacite, Priority.ALWAYS);
        Label valeurOp = new Label();
        valeurOp.setMinWidth(44);
        Label lEp = new Label("Épaisseur du contour");
        Label lOp = new Label("Opacité du remplissage");

        Button defaut = new Button("Par défaut");
        Ui.bulle(defaut, "Contour + remplissage, turquoise, épaisseur 4, opacité 45 %");
        Label etat = Ui.discret("");
        etat.setMaxWidth(Double.MAX_VALUE);

        Runnable[] appliquer = new Runnable[1];
        Runnable majPastilles = () -> {
            pastilles.getChildren().clear();
            for (String[] t : TEINTES) {
                Color c = Color.web("#" + t[0]);
                boolean choisie = hex(c).equals(hex(couleur[0]));
                Button p = new Button();
                p.setFocusTraversable(false);
                p.setMinSize(24, 24); p.setPrefSize(24, 24); p.setMaxSize(24, 24);
                p.setStyle("-fx-background-color: " + (choisie ? "#3E86AC, white, " : "#B8B4A8, ") + "#" + t[0] + ";"
                        + " -fx-background-insets: " + (choisie ? "0, 2, 4" : "0, 1") + ";"
                        + " -fx-background-radius: 12; -fx-cursor: hand; -fx-padding: 0;");
                Ui.bulle(p, t[1]);
                p.setOnAction(e -> { couleur[0] = c; libre.setValue(c); appliquer[0].run(); });
                pastilles.getChildren().add(p);
            }
            pastilles.getChildren().add(libre);
        };
        appliquer[0] = () -> {
            int ep = (int) Math.round(epaisseur.getValue());
            int op = (int) Math.round(opacite.getValue());
            valeurEp.setText(ep + " px");
            valeurOp.setText(op + " %");
            boolean contour = mode[0] != StyleSurlignage.Mode.REMPLISSAGE;
            boolean remplissage = mode[0] != StyleSurlignage.Mode.CONTOUR;
            epaisseur.setDisable(!contour); lEp.setDisable(!contour); valeurEp.setDisable(!contour);
            opacite.setDisable(!remplissage); lOp.setDisable(!remplissage); valeurOp.setDisable(!remplissage);
            modele.setEffect(effet(mode[0], couleur[0], ep, op));
            majPastilles.run();
            StyleSurlignage.regler(mode[0], hex(couleur[0]), ep, op);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }   // toujours un mode choisi
            mode[0] = (StyleSurlignage.Mode) b.getUserData();
            appliquer[0].run();
        });
        libre.setOnAction(e -> { couleur[0] = libre.getValue(); appliquer[0].run(); });
        for (Slider sl : new Slider[]{epaisseur, opacite}) {
            sl.valueProperty().addListener((o, a, b) -> {
                // apercu en direct ; envoi au jeu a la fin du glisser
                int ep = (int) Math.round(epaisseur.getValue()), op = (int) Math.round(opacite.getValue());
                valeurEp.setText(ep + " px"); valeurOp.setText(op + " %");
                modele.setEffect(effet(mode[0], couleur[0], ep, op));
                if (!sl.isValueChanging()) appliquer[0].run();
            });
            sl.valueChangingProperty().addListener((o, a, b) -> { if (!b) appliquer[0].run(); });
        }
        defaut.setOnAction(e -> {
            couleur[0] = Color.web("#" + StyleSurlignage.COULEUR_DEFAUT);
            libre.setValue(couleur[0]);
            epaisseur.setValue(StyleSurlignage.EPAISSEUR_DEFAUT);
            opacite.setValue(StyleSurlignage.OPACITE_DEFAUT);
            for (Toggle t : g.getToggles()) if (t.getUserData() == StyleSurlignage.MODE_DEFAUT) t.setSelected(true);
            mode[0] = StyleSurlignage.MODE_DEFAUT;
            appliquer[0].run();
        });

        // premier affichage (sans renvoyer au jeu ce qui y est deja)
        valeurEp.setText(StyleSurlignage.epaisseur() + " px");
        valeurOp.setText(StyleSurlignage.opacite() + " %");
        modele.setEffect(effet(mode[0], couleur[0], StyleSurlignage.epaisseur(), StyleSurlignage.opacite()));
        majPastilles.run();
        boolean c0 = mode[0] != StyleSurlignage.Mode.REMPLISSAGE, r0 = mode[0] != StyleSurlignage.Mode.CONTOUR;
        epaisseur.setDisable(!c0); lEp.setDisable(!c0); valeurEp.setDisable(!c0);
        opacite.setDisable(!r0); lOp.setDisable(!r0); valeurOp.setDisable(!r0);

        // le client du jeu sait-il appliquer ce style ? (lecture du SWF hors fil JavaFX)
        Salle.tache("surlignage-client", () -> {
            boolean ok = ClientModifie.saitStyle();
            Platform.runLater(() -> etat.setText(ok ? ""
                    : "Le jeu garde le style d'origine tant que le client modifié n'est pas à jour : relance « Lancer l'Atelier »."));
        });

        HBox ligneEp = new HBox(8, epaisseur, valeurEp);
        ligneEp.setAlignment(Pos.CENTER_LEFT);
        HBox ligneOp = new HBox(8, opacite, valeurOp);
        ligneOp.setAlignment(Pos.CENTER_LEFT);
        VBox choix = new VBox(6,
                Ui.etiquette("Style"), modes,
                espace(), Ui.etiquette("Couleur"), pastilles,
                espace(), lEp, ligneEp,
                lOp, ligneOp,
                espace(), defaut, etat);
        choix.setMinWidth(220);
        HBox.setHgrow(choix, Priority.ALWAYS);
        HBox corps = new HBox(20, colApercu, choix);
        corps.setAlignment(Pos.TOP_LEFT);
        return new VBox(12, Ui.bloc("Mise en valeur dans le jeu", corps,
                Ui.aide("Vaut pour tout ce qui est choisi : sélection des calques, mobis d'une fenêtre, Monster Plants.")));
    }

    private static Region espace() { Region r = new Region(); r.setMinHeight(4); return r; }

    /** L'effet JavaFX qui imite celui du jeu (contour = halo plein, remplissage = teinte). */
    static Effect effet(StyleSurlignage.Mode m, Color c, int ep) { return effet(m, c, ep, StyleSurlignage.OPACITE_DEFAUT); }

    static Effect effet(StyleSurlignage.Mode m, Color c, int ep, int opacite) {
        Effect teinte = null;
        if (m != StyleSurlignage.Mode.CONTOUR) {
            double a = Math.max(0.1, Math.min(1, opacite / 100.0));
            ColorInput couche = new ColorInput(-500, -500, 2000, 2000, Color.color(c.getRed(), c.getGreen(), c.getBlue(), a));
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
            Moteur gp = AtelierLauncher.moteur();
            if (gp == null || gp.getFurniDataTools() == null || !gp.getFurniDataTools().isReady()) return null;
            for (NomsMobis.Nom n : NomsMobis.chercher(NOM_EXEMPLE, 3)) {
                if (!NomsMobis.normaliser(n.nom).equals(NomsMobis.normaliser(NOM_EXEMPLE))) continue;
                for (String cls : n.classes) {
                    Furnidata.Mobi d = gp.getFurniDataTools().getFloorItemDetails(cls);
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

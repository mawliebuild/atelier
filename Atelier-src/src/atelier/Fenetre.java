package atelier;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.prefs.Preferences;

/**
 * La fenetre de l'Atelier, dans le style des fenetres du jeu : barre de
 * titre bleue au nom du menu ouvert, bouton reduire, croix rouge, corps creme.
 *
 * Par defaut elle se pose a droite, sous le panneau de mission du jeu, et
 * laisse l'inventaire libre a gauche. Deplacee a la main, elle garde sa place
 * PAR RAPPORT A LA FENETRE DU JEU : si Habbo bouge, elle suit, au meme endroit.
 *
 * Sa hauteur suit le contenu, sans depasser la barre d'outils du jeu en bas.
 * A sa place par defaut, elle s'arrete aussi au-dessus des barres du bas de
 * l'Atelier ; une fois deplacee a la main, elle passe librement PAR-DESSUS
 * (les menus fixes sont a un niveau macOS plus bas : voir BarrePremierPlan).
 *
 * Elle peut devenir transparente quand la souris est ailleurs, pour voir les
 * mobis derriere pendant qu'on place : le reglage vit dans Parametres.
 */
public class Fenetre implements Ancrage.Ancrable {

    public static final double LARGEUR = 334;
    /** Largeur actuelle : celle par defaut, ou plus large pour certains menus (Salle). */
    private double largeur = LARGEUR;
    /** Hauteur de la barre de titre macOS, comprise dans le cadre de la fenetre Habbo. */
    private static final double TITRE_MAC = 28;
    /** Sous le panneau de mission du jeu, en haut a droite. */
    private static final double HAUT_DEFAUT = 312;
    private static final double DROITE_DEFAUT = 8;
    /** Barre d'outils du jeu, en bas, a laisser libre. */
    private static final double BAS_LIBRE = 56;
    private static final double TITRE = 30, OMBRE = 3;

    private static Fenetre instance;
    public static Fenetre instance() { return instance; }

    private final Stage stage = new Stage();
    private final VBox cadre = new VBox();
    private final Label titre = new Label();
    /** Le « i » de la fenetre affichee, a gauche du bouton reduire. */
    private final HBox aide = new HBox();
    private final StackPane barreTitre = new StackPane();
    private final Region zone;

    private final Preferences prefs = Preferences.userRoot().node("atelier");
    private double droite = prefs.getDouble("fenetre.droite", DROITE_DEFAUT);
    private double haut   = prefs.getDouble("fenetre.haut", HAUT_DEFAUT);
    /** Opacite quand la souris est ailleurs, de 20 a 100 %. */
    private double fondu  = prefs.getDouble("fenetre.fondu", 100);
    /** Deplacee a la main : elle ne se raccourcit plus pour laisser les barres du bas libres. */
    private boolean deplacee = prefs.getBoolean("fenetre.deplacee", false);

    private double[] habbo;
    private boolean reduite = false;
    private Region suivie;
    private final ChangeListener<Number> surHauteur = (o, a, b) -> ajuster();
    private Runnable surFermeture = () -> { };
    private double prisX, prisY, avantX, avantY;

    public Fenetre(String css, Region zone) {
        this.zone = zone;
        instance = this;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        construireTitre();

        zone.getStyleClass().add("fenetre-corps");
        VBox.setVgrow(zone, Priority.ALWAYS);
        cadre.getChildren().addAll(barreTitre, zone);
        cadre.getStyleClass().add("fenetre");

        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, OMBRE, 0));
        racine.setOnMouseEntered(e -> stage.setOpacity(1));
        racine.setOnMouseExited(e -> stage.setOpacity(fondu / 100));

        Scene scene = new Scene(racine, LARGEUR, 500);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);
        stage.setWidth(LARGEUR);
        placerParDefaut();
        // Au-dessus des menus fixes et du panneau des calques.
        BarrePremierPlan.fenetre(stage);
    }

    public Stage fenetre() { return stage; }
    public boolean ouverte() { return stage.isShowing(); }
    public void surFermeture(Runnable r) { surFermeture = r; }

    private final java.util.List<Stage> obstacles = new java.util.ArrayList<>();

    /**
     * Une petite fenetre de l'Atelier (barres du bas) que celle-ci ne recouvre
     * pas A SA PLACE PAR DEFAUT : quand elle est visible et dessous, la hauteur
     * s'arrete au-dessus. Deplacee a la main, la fenetre passe par-dessus.
     */
    public void eviter(Stage s) {
        obstacles.add(s);
        s.showingProperty().addListener((o, a, b) -> Platform.runLater(this::ajuster));
        s.yProperty().addListener((o, a, b) -> Platform.runLater(this::ajuster));
    }

    public void ouvrir() {
        if (reduite) reduire();
        if (!stage.isShowing()) stage.show();
        ajuster();
    }

    public void fermer() {
        stage.hide();
        surFermeture.run();
    }

    /** Le menu affiche : son nom en titre, et sa hauteur suivie. */
    public void montrer(String nom, Region contenu) { montrer(nom, contenu, null); }

    /**
     * Change la largeur (le bord droit ne bouge pas : la fenetre s'elargit
     * vers la gauche). 0 = largeur par defaut.
     */
    public void largeur(double l) {
        double voulue = l <= 0 ? LARGEUR : l;
        if (Math.abs(voulue - largeur) < 0.5) return;
        double droiteEcran = stage.getX() + largeur;
        largeur = voulue;
        stage.setWidth(largeur);
        // tres large (Reglages) : jamais au-dela du bord gauche de l'ecran
        double minX = javafx.stage.Screen.getPrimary().getVisualBounds().getMinX() + 8;
        stage.setX(Math.max(minX, droiteEcran - largeur));
        ajuster();
    }

    /** Idem, avec l'aide de toute la fenetre (un « i » dans la barre de titre), ou null. */
    public void montrer(String nom, Region contenu, Button info) {
        titre.setText(nom);
        if (info == null) aide.getChildren().clear(); else aide.getChildren().setAll(info);
        if (suivie != null) suivie.heightProperty().removeListener(surHauteur);
        suivie = contenu;
        suivie.heightProperty().addListener(surHauteur);
        Platform.runLater(this::ajuster);
    }

    // ---------------------------------------------------------------- titre

    private void construireTitre() {
        titre.getStyleClass().add("fenetre-titre");

        Button reduire = bouton(Icones.REDUIRE, "fenetre-reduire");
        reduire.setOnAction(e -> reduire());
        Button fermer = bouton(Icones.FERMER, "fenetre-fermer");
        fermer.setOnAction(e -> fermer());

        aide.setAlignment(Pos.CENTER);
        aide.setPadding(new Insets(0, 4, 0, 0));
        HBox boutons = new HBox(4, aide, reduire, fermer);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.setPickOnBounds(false);
        boutons.setPadding(new Insets(0, 6, 0, 0));

        barreTitre.getChildren().addAll(titre, boutons);
        barreTitre.getStyleClass().add("fenetre-barre");
        barreTitre.setMinHeight(TITRE);
        barreTitre.setPrefHeight(TITRE);
        barreTitre.setMaxHeight(TITRE);
        barreTitre.setCursor(Cursor.MOVE);

        barreTitre.setOnMousePressed(e -> {
            prisX = e.getScreenX() - stage.getX();
            prisY = e.getScreenY() - stage.getY();
            avantX = stage.getX();
            avantY = stage.getY();
        });
        barreTitre.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - prisX);
            stage.setY(e.getScreenY() - prisY);
        });
        barreTitre.setOnMouseReleased(e -> memoriserPlace());
        barreTitre.setOnMouseClicked(e -> { if (e.getClickCount() == 2) reduire(); });
    }

    private static Button bouton(String icone, String classe) {
        Button b = new Button();
        b.setGraphic(Icones.trace(icone, "icone-fenetre"));
        b.getStyleClass().addAll("fenetre-bouton", classe);
        b.setFocusTraversable(false);
        return b;
    }

    private void reduire() {
        reduite = !reduite;
        zone.setVisible(!reduite);
        zone.setManaged(!reduite);
        if (reduite) cadre.getStyleClass().add("reduite");
        else         cadre.getStyleClass().remove("reduite");
        ajuster();
    }

    // --------------------------------------------------------- transparence

    public double fondu() { return fondu; }

    /** Opacite appliquee quand la souris quitte la fenetre (20 a 100). */
    public void fondu(double v) {
        fondu = Math.max(20, Math.min(100, v));
        prefs.putDouble("fenetre.fondu", fondu);
    }

    // ------------------------------------------------------------ placement

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(() -> {
            stage.setX(hx + hl - largeur - droite);
            stage.setY(hy + haut);
            ajuster();
        });
    }

    private void placerParDefaut() {
        javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setX(e.getMaxX() - largeur - DROITE_DEFAUT);
        stage.setY(e.getMinY() + HAUT_DEFAUT - TITRE_MAC);
    }

    /** Retient la place choisie, relative au coin haut-droit du jeu. */
    private void memoriserPlace() {
        // simple clic sur le titre : rien n'a bouge
        if (Math.abs(stage.getX() - avantX) < 1 && Math.abs(stage.getY() - avantY) < 1) return;
        if (!deplacee) { deplacee = true; prefs.putBoolean("fenetre.deplacee", true); }
        if (habbo == null) { ajuster(); return; }
        droite = habbo[0] + habbo[2] - largeur - stage.getX();
        haut = stage.getY() - habbo[1];
        prefs.putDouble("fenetre.droite", droite);
        prefs.putDouble("fenetre.haut", haut);
        ajuster();
    }

    /** Hauteur : celle du contenu, bornee par le bas de la fenetre du jeu. */
    private void ajuster() {
        double voulue = TITRE + 2 + OMBRE;
        if (!reduite) {
            double contenu = (suivie == null) ? 300 : suivie.prefHeight(largeur - 2);
            voulue += contenu + 2;
        }
        double max = (habbo == null)
                ? javafx.stage.Screen.getPrimary().getVisualBounds().getMaxY() - stage.getY() - 20
                : habbo[1] + habbo[3] - BAS_LIBRE - stage.getY();
        if (!deplacee) for (Stage o : obstacles) {
            if (!o.isShowing() || o.getY() <= stage.getY()) continue;
            boolean croise = o.getX() < stage.getX() + largeur && o.getX() + o.getWidth() > stage.getX();
            if (croise) max = Math.min(max, o.getY() - 6 - stage.getY());
        }
        double h = reduite ? voulue : Math.max(160, Math.min(voulue, max));
        if (Math.abs(stage.getHeight() - h) > 0.5) stage.setHeight(h);
    }
}

package atelier;

import extension.GPresets;
import game.FloorState;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * La barre d'outils posee en haut au centre de la fenetre du jeu.
 *
 * Une icone par menu, sans texte : le nom apparait dans une bulle sous
 * l'icone au survol. Le bouton du menu ouvert reste
 * allume. Au bout, un resume de la salle toujours visible ; un clic dessus
 * ouvre « Ma salle ».
 *
 * La barre ne grandit pas au survol : les autres boutons ne bougent jamais
 * sous la souris.
 */
public class BarreOutils implements Ancrage.Ancrable {

    /** Hauteur de la barre de titre macOS, comprise dans le cadre de la fenetre Habbo. */
    private static final double TITRE_MAC = 28;
    private static final double MARGE_HAUT = 6;

    private static BarreOutils instance;
    public static BarreOutils instance() { return instance; }

    private final Stage stage = new Stage();
    private final HBox barre = new HBox(5);
    private final List<ToggleButton> boutons = new ArrayList<>();
    private final Popup bulle = new Popup();
    private final Label bulleTexte = new Label();

    private final Circle voyant = new Circle(4);
    private final Label salle = new Label("Atelier");
    private final Label detail = new Label("Démarrage...");
    private final Button etat = new Button();

    private Consumer<String> surChoix = c -> { };
    private HBox historique;

    private final Stage stageHistorique = new Stage();

    /** Mode Construction : la petite barre annuler / retablir apparait, a droite de celle-ci. */
    public void construction(boolean on) {
        if (on) { stageHistorique.show(); stageHistorique.sizeToScene(); collerHistorique(); }
        else stageHistorique.hide();
    }

    public Stage fenetreHistorique() { return stageHistorique; }

    private void collerHistorique() {
        if (!stageHistorique.isShowing()) return;
        stageHistorique.setX(stage.getX() + stage.getWidth() + 6);
        stageHistorique.setY(stage.getY());
    }
    private Runnable surEtat = () -> { };
    private double[] habbo;


    public BarreOutils(String css, List<Navigation.Menu> menus, int separerAvant) {
        instance = this;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        barre.getStyleClass().add("barre");
        barre.setAlignment(Pos.CENTER_LEFT);

        for (int i = 0; i < menus.size(); i++) {
            Navigation.Menu m = menus.get(i);
            if (i == separerAvant) barre.getChildren().add(separateur());

            ToggleButton b = new ToggleButton();
            b.setGraphic(Icones.trace(m.icone, "icone-barre"));
            b.getStyleClass().add("barre-bouton");
            b.setFocusTraversable(false);
            b.setUserData(m.cle);
            b.setOnAction(e -> surChoix.accept(m.cle));
            survol(b, m.nom);
            boutons.add(b);
            barre.getChildren().add(b);
        }

        barre.getChildren().add(separateur());
        voyant.getStyleClass().add("voyant-attente");
        salle.getStyleClass().add("barre-salle");
        detail.getStyleClass().add("barre-detail");
        detail.managedProperty().bind(detail.textProperty().isNotEmpty());
        detail.visibleProperty().bind(detail.textProperty().isNotEmpty());
        HBox resume = new HBox(7, voyant, salle, detail);
        resume.setAlignment(Pos.CENTER_LEFT);
        etat.setGraphic(resume);
        etat.getStyleClass().add("barre-etat");
        etat.setFocusTraversable(false);
        etat.setOnAction(e -> surEtat.run());
        barre.getChildren().add(etat);

        // Mode Construction : annuler / retablir, a droite de la barre.
        Button annuler = new Button(), retablir = new Button();
        annuler.setGraphic(Icones.trace(Icones.ANNULER, "icone-barre"));
        retablir.setGraphic(Icones.trace(Icones.RETABLIR, "icone-barre"));
        for (Button b : new Button[]{annuler, retablir}) {
            b.getStyleClass().add("barre-bouton");
            b.setFocusTraversable(false);
        }
        annuler.setOnAction(e -> Salle.tache("annuler", Historique::annuler));
        retablir.setOnAction(e -> Salle.tache("retablir", Historique::retablir));
        survol(annuler, "Annuler   Cmd+Z");
        survol(retablir, "Rétablir   Cmd+Maj+Z");
        // A part, comme les petites barres du bas : sa propre fenetre, collee a
        // droite de la barre du haut, visible seulement en mode Construction.
        historique = new HBox(5, annuler, retablir);
        historique.setAlignment(Pos.CENTER_LEFT);
        historique.getStyleClass().add("barre");
        HBox racineH = new HBox(historique);
        racineH.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 3 0;");
        Scene sh = new Scene(racineH);
        sh.setFill(Color.TRANSPARENT);
        if (css != null) sh.getStylesheets().add(css);
        stageHistorique.initStyle(StageStyle.TRANSPARENT);
        stageHistorique.setAlwaysOnTop(true);
        stageHistorique.setTitle(AtelierLauncher.NOM);
        stageHistorique.setScene(sh);
        stage.xProperty().addListener((o, a, b) -> collerHistorique());
        stage.yProperty().addListener((o, a, b) -> collerHistorique());
        stage.widthProperty().addListener((o, a, b) -> collerHistorique());
        Runnable maj = () -> Platform.runLater(() -> {
            annuler.setDisable(!Historique.peutAnnuler());
            retablir.setDisable(!Historique.peutRetablir());
        });
        Historique.ecouter(maj);
        javafx.animation.Timeline t = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> maj.run()));
        t.setCycleCount(javafx.animation.Animation.INDEFINITE);
        t.play();
        maj.run();

        bulleTexte.setStyle("-fx-background-color: #ECEAE0; -fx-text-fill: #1D1C19;"
                + " -fx-border-color: #000000; -fx-border-radius: 4; -fx-background-radius: 4;"
                + " -fx-padding: 3 7 3 7; -fx-font-size: 12px; -fx-font-weight: bold;");
        bulle.getContent().add(bulleTexte);

        // Petite fleche a gauche pour reduire / deplier (comme les barres du bas).
        fleche.getStyleClass().add("barre-bouton");
        fleche.setFocusTraversable(false);
        fleche.setStyle("-fx-min-width: 14; -fx-pref-width: 14; -fx-max-width: 14;"
                + " -fx-min-height: 36; -fx-pref-height: 36; -fx-max-height: 36; -fx-padding: 0;");
        fleche.setOnAction(a -> { reduite = !reduite; prefs.putBoolean("barre.haut.reduite", reduite); appliquerReduite(); });
        barre.getChildren().add(0, fleche);
        appliquerReduite();

        HBox racine = new HBox(barre);
        racine.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 3 0;");
        Deplacement.activer(stage, racine, "haut", this::recentrer);
        Scene scene = new Scene(racine);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);

        BarrePremierPlan.menu(stage);
        BarrePremierPlan.menu(stageHistorique);
        demarrerResume();
    }

    public Stage fenetre() { return stage; }
    public void surChoix(Consumer<String> c) { surChoix = c; }
    public void surEtat(Runnable r) { surEtat = r; }
    /** Comme un clic sur le bouton du menu : ouvre, ou ferme s'il l'est deja. */
    public void surChoixDirect(String cle) { surChoix.accept(cle); }
    public void montrer() { stage.show(); }

    /** Allume le bouton du menu ouvert ; null = aucun. */
    public void actif(String cle) {
        for (ToggleButton b : boutons) b.setSelected(b.getUserData().equals(cle));
        if (reduite) appliquerReduite();
    }

    // ------------------------------------------------------------ reduction

    private final Button fleche = new Button();
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("atelier");
    private boolean reduite = prefs.getBoolean("barre.haut.reduite", false);

    /**
     * Reduite : il ne reste que la fleche et l'icone du menu ouvert (aucune
     * si rien n'est ouvert) ; le resume de la salle se cache aussi.
     */
    private void appliquerReduite() {
        fleche.setGraphic(Icones.trace(BarreIcones.trace(false, !reduite), "icone-barre"));
        for (javafx.scene.Node n : barre.getChildren()) {
            if (n == fleche) continue;
            boolean voir = !reduite || (n instanceof ToggleButton && ((ToggleButton) n).isSelected());
            n.setVisible(voir);
            n.setManaged(voir);
        }
        survol(fleche, reduite ? "Déplier le menu" : "Réduire le menu");
        bulle.hide();
        if (stage.isShowing()) { stage.sizeToScene(); recentrer(); }
    }

    // ---------------------------------------------------------------- outils

    private static Region separateur() {
        Region s = new Region();
        s.getStyleClass().add("barre-sep");
        return s;
    }

    // ---------------------------------------------------------------- bulle

    /** Bulle sous le bouton, sans delai ni animation. */
    private void survol(Region b, String nom) {
        b.setOnMouseEntered(e -> {
            bulleTexte.setText(WindowsClavier.texte(nom));
            Bounds r = b.localToScreen(b.getBoundsInLocal());
            if (r == null) return;
            bulle.show(stage, r.getMinX(), r.getMaxY() + 6);
            bulle.setX(r.getMinX() + r.getWidth() / 2 - bulle.getWidth() / 2);
        });
        b.setOnMouseExited(e -> bulle.hide());
        b.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> bulle.hide());
    }

    // ------------------------------------------------------------ placement

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(this::recentrer);
    }

    private void recentrer() {
        // place normale + decalage choisi en glissant la barre (Deplacement)
        double dx = Deplacement.dx("haut"), dy = Deplacement.dy("haut");
        if (habbo == null) {
            javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
            stage.setX(e.getMinX() + e.getWidth() / 2 - stage.getWidth() / 2 + dx);
            stage.setY(e.getMinY() + MARGE_HAUT + dy);
            return;
        }
        stage.setX(habbo[0] + habbo[2] / 2 - stage.getWidth() / 2 + dx);
        stage.setY(habbo[1] + TITRE_MAC + MARGE_HAUT + dy);
    }

    // --------------------------------------------------------- resume salle

    /** Le resume se met a jour seul, une fois par seconde. */
    private void demarrerResume() {
        Thread t = new Thread(() -> {
            while (true) {
                try { majResume(); } catch (Throwable ignored) { }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-resume");
        t.setDaemon(true);
        t.start();
    }

    /** « 331 sols · 17 murs | 348 mobis » (logique pure, testee a part). */
    static String resume(int sols, int murs) {
        return n(sols, "sol") + " · " + n(murs, "mur") + " | " + n(sols + murs, "mobi");
    }

    private static String n(int n, String mot) {
        return n + " " + mot + (n > 1 ? "s" : "");
    }

    private void majResume() {
        GPresets gp = AtelierLauncher.moteur();
        String niveau, nom, sous;
        if (gp == null) {
            niveau = "attente"; nom = "Atelier"; sous = "Connexion à Habbo...";
        } else {
            FloorState s = gp.getFloorState();
            if (s == null || !s.inRoom()) {
                // Dans la barre, on est forcement connectee : point vert, « Connectée »
                // seul, sans detail.
                niveau = "ok"; nom = "Connectée"; sous = "";
            } else {
                String n = NomSalle.nomValide(gp);
                if (n == null || n.isEmpty()) n = "Salle";
                // getItems/getWallItems recopient toute la salle : une fois chacun.
                java.util.List<?> so = s.getItems(), mu = s.getWallItems();
                int sols = (so == null) ? 0 : so.size();
                int murs = (mu == null) ? 0 : mu.size();
                niveau = "ok"; nom = n;
                sous = resume(sols, murs);
            }
        }
        final String nv = niveau, no = nom, so = sous;
        Platform.runLater(() -> {
            voyant.getStyleClass().setAll("voyant-" + nv);
            boolean change = !no.equals(salle.getText()) || !so.equals(detail.getText());
            salle.setText(no);
            detail.setText(so);
            if (change) { stage.sizeToScene(); recentrer(); }
        });
    }
}

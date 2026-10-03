package atelier;

import gearth.protocol.HConnection;
import gearth.protocol.connection.HState;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Ce qu'on voit a l'ouverture : une seule petite fenetre dans le coin de
 * l'ecran, qui lance la connexion toute seule.
 *
 * La barre d'outils et la fenetre de l'Atelier n'apparaissent qu'une fois
 * connectee : avant, leurs outils n'ont rien sur quoi agir. Si la connexion
 * tombe, elles s'effacent et cet ecran revient.
 *
 * Le clic automatique ne part qu'une fois, au demarrage. Apres une coupure,
 * c'est a toi de relancer : un proxy qui echoue aussitot relancerait sinon
 * la connexion en boucle.
 *
 * Le bouton du controleur de G-Earth est reutilise tel quel (fire) : toute sa
 * logique de connexion reste la sienne. On ne fait que renommer son libelle,
 * lie au systeme de traduction, d'ou le unbind.
 */
public class EcranConnexion {

    private static final double LARGEUR = 340;

    private final Stage stage = new Stage();
    private final Label message = new Label();
    private final Label detail = new Label();
    private final Button action = new Button();

    private final HConnection connexion;
    private final Button boutonGEarth;
    private final Runnable surConnectee, surDeconnectee;

    private boolean autoFait = false;
    private boolean dejaConnectee = false;
    private long derniereReprise = 0;
    private boolean preparation = false, coupure = false;
    private double prisX, prisY;

    public EcranConnexion(String css, HConnection connexion, Button boutonGEarth,
                          Runnable surConnectee, Runnable surDeconnectee) {
        this.connexion = connexion;
        this.boutonGEarth = boutonGEarth;
        this.surConnectee = surConnectee;
        this.surDeconnectee = surDeconnectee;

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        // --- barre de titre, comme les fenetres du jeu ---
        Label titre = new Label(AtelierLauncher.NOM);
        titre.getStyleClass().add("fenetre-titre");
        Button quitter = new Button();
        quitter.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        quitter.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        quitter.setFocusTraversable(false);
        quitter.setOnAction(e -> { Platform.exit(); System.exit(0); });
        HBox droite = new HBox(quitter);
        droite.setAlignment(Pos.CENTER_RIGHT);
        droite.setPickOnBounds(false);
        droite.setPadding(new Insets(0, 6, 0, 0));
        StackPane barre = new StackPane(titre, droite);
        barre.getStyleClass().add("fenetre-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setCursor(Cursor.MOVE);
        barre.setOnMousePressed(e -> { prisX = e.getScreenX() - stage.getX(); prisY = e.getScreenY() - stage.getY(); });
        barre.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - prisX); stage.setY(e.getScreenY() - prisY); });

        // --- corps ---
        message.getStyleClass().add("connexion-message");
        message.setWrapText(true);
        detail.getStyleClass().add("connexion-detail");
        detail.setWrapText(true);
        action.getStyleClass().addAll("button", "primaire", "connexion-action");
        action.setMaxWidth(Double.MAX_VALUE);
        action.setDefaultButton(true);
        action.setOnAction(e -> cliquer());

        VBox corps = new VBox(10, message, detail, action);
        // Pas la classe « fenetre-corps » : son -fx-padding (fait pour les
        // menus) ecraserait celui-ci, la feuille de style passant avant setPadding.
        corps.getStyleClass().add("connexion-corps");

        VBox cadre = new VBox(barre, corps);
        cadre.getStyleClass().add("fenetre");

        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));

        Scene scene = new Scene(racine, LARGEUR, Region.USE_COMPUTED_SIZE);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);
        stage.setWidth(LARGEUR);
    }

    /** Montre l'ecran, suit l'etat de la connexion, et clique « Se connecter ». */
    public void demarrer() {
        if (boutonGEarth != null) boutonGEarth.textProperty().unbind();
        connexion.getStateObservable().addListener(
                (ancien, nouveau) -> Platform.runLater(() -> appliquer(nouveau)));
        appliquer(connexion.getState());
        surveillerBoucle();
        if (connexion.getState() != HState.CONNECTED) {
            montrer();
            cliquerQuandPret();
        }
    }

    /**
     * En bas a droite de l'ecran : au centre, elle cachait la page Habbo qu'on
     * est justement en train de charger.
     */
    private void montrer() {
        stage.show();
        stage.sizeToScene();
        javafx.geometry.Rectangle2D e = Screen.getPrimary().getVisualBounds();
        stage.setX(e.getMaxX() - stage.getWidth() - 20);
        stage.setY(e.getMaxY() - stage.getHeight() - 20);
    }

    /**
     * Le bouton de G-Earth reste desactive tant que son controleur n'a pas fini
     * de s'initialiser : on attend qu'il soit utilisable, puis on clique une fois.
     */
    private void cliquerQuandPret() {
        Timeline t = new Timeline();
        t.getKeyFrames().add(new KeyFrame(Duration.millis(300), e -> {
            if (autoFait) { t.stop(); return; }
            if (boutonGEarth == null || boutonGEarth.isDisabled()) return;
            autoFait = true;
            t.stop();
            // G-Earth peut avoir deja lance la connexion de lui-meme.
            if (connexion.getState() == HState.NOT_CONNECTED) lancerConnexion();
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private void cliquer() {
        if (boutonGEarth == null) return;
        if (connexion.getState() == HState.NOT_CONNECTED) lancerConnexion();
        else boutonGEarth.fire();
    }

    /**
     * Lance la connexion de G-Earth, apres avoir remis /etc/hosts et le cache
     * DNS d'aplomb (GardeConnexion) : sans cela, une reconnexion juste apres
     * une coupure pouvait faire boucler G-Earth sur lui-meme.
     */
    private void lancerConnexion() {
        if (boutonGEarth == null || preparation) return;
        preparation = true;
        regler("Préparation de la connexion...", "", "Annuler");
        Salle.tache("garde-connexion", () -> {
            String r = GardeConnexion.assainir();
            Platform.runLater(() -> {
                preparation = false;
                if (r != null) InfoJeu.dire(r);
                if (connexion.getState() == HState.NOT_CONNECTED && !boutonGEarth.isDisabled())
                    boutonGEarth.fire();
                else appliquer(connexion.getState());
            });
        });
    }

    /**
     * Coupe-circuit : si G-Earth boucle quand meme (threads par milliers), on
     * annule la tentative avant que la JVM n'atteigne la limite de macOS, puis
     * on relance une fois, proprement.
     */
    private void surveillerBoucle() {
        Timeline t = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            HState s = connexion.getState();
            if (s == HState.CONNECTED || s == HState.NOT_CONNECTED || coupure) return;
            int n = GardeConnexion.threads();
            if (n < 1500) return;
            coupure = true;
            System.err.println("[Atelier] G-Earth boucle sur lui-meme (" + n + " threads) : tentative annulee.");
            if (boutonGEarth != null) boutonGEarth.fire();     // « Annuler » pendant l'attente
            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(Duration.seconds(4));
            p.setOnFinished(ev -> {
                coupure = false;
                if (connexion.getState() == HState.NOT_CONNECTED) lancerConnexion();
            });
            p.play();
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private void appliquer(HState etat) {
        if (etat == null) etat = HState.NOT_CONNECTED;
        switch (etat) {
            case CONNECTED:
                regler("Connectée", "", "Se déconnecter");
                dejaConnectee = true;
                stage.hide();
                surConnectee.run();
                return;
            case PREPARING:
            case PREPARED:
                regler("Préparation de la connexion...", "", "Annuler");
                break;
            case WAITING_FOR_CLIENT:
                regler("Charge la page Habbo et connecte-toi.", "", "Annuler");
                break;
            case ABORTING:
                regler("Annulation...", "", "Annuler");
                break;
            default:
                if (dejaConnectee)
                    regler("Connexion perdue", "Charge la page Habbo et connecte-toi : l'Atelier se reconnecte tout seul.", "Se connecter");
                else if (autoFait)
                    regler("Pas connectée", "La connexion n'a pas abouti.", "Se connecter");
                else
                    regler("Connexion à Habbo...", "", "Se connecter");
                if (dejaConnectee) {
                    dejaConnectee = false;
                    surDeconnectee.run();
                    montrer();
                    // Reconnexion automatique, une fois par minute au plus : sans
                    // garde-fou, un proxy qui echoue aussitot tournerait en boucle.
                    if (System.currentTimeMillis() - derniereReprise > 60_000) {
                        derniereReprise = System.currentTimeMillis();
                        javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(Duration.seconds(2));
                        p.setOnFinished(ev -> {
                            if (connexion.getState() == HState.NOT_CONNECTED && boutonGEarth != null
                                    && !boutonGEarth.isDisabled()) lancerConnexion();
                        });
                        p.play();
                    }
                }
        }
        if (stage.isShowing()) {
            // Le texte change de hauteur : on garde le coin bas-droit en place.
            double bas = stage.getY() + stage.getHeight();
            stage.sizeToScene();
            stage.setY(bas - stage.getHeight());
        }
    }

    private void regler(String m, String d, String libelle) {
        message.setText(m);
        detail.setText(d);
        detail.setVisible(!d.isEmpty());
        detail.setManaged(!d.isEmpty());
        action.setText(libelle);
        if (boutonGEarth != null) boutonGEarth.setText(libelle);
    }
}

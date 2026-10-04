package atelier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Le MODE FLOOR (bouton Floor de la barre du bas), pendant du mode
 * Construction : il en sort, et sa palette prend la place du panneau des
 * calques, en haut a gauche du jeu, dans le meme gris.
 *
 * La grille du jeu montre le floor en travail (ModeCases) : cases possibles
 * (blanc pale), ajoutees (vert), retirees (rouge), hauteur changee (bleu),
 * porte (orange). La palette donne les outils (un seul actif, touches 1 a 8
 * quand elle a le focus), la hauteur N, le pinceau, annuler / retablir, le
 * compte des changements en attente aux couleurs de la grille, la legende,
 * les murs et le sol (derriere la roue), Appliquer et Quitter.
 *
 * Quitter avec des changements pas appliques demande quoi faire, dans la
 * palette : Appliquer, Abandonner ou Rester. Fil JavaFX.
 *
 * Dans le jeu (Habbo devant, chat vide ; RaccourcisGlobaux, seulement pendant
 * le mode) : 1 a 8 choisissent l'outil, Cmd/Ctrl+Z et Cmd/Ctrl+Maj+Z annulent
 * et retablissent dans le floor, Echap fait comme dans la palette : ferme la
 * question si elle est ouverte (Rester), sinon oublie le premier coin d'un
 * rectangle en attente, sinon Quitter (question s'il reste des changements).
 */
public class BarreFloor implements Ancrage.Ancrable {

    // Couleurs de la grille du jeu (client modifie) : la legende et le compte les reprennent.
    static final String VERT = "#2ECC40", ROUGE = "#E74C3C", BLEU = "#2E86FF", ORANGE = "#FF9F1A";

    private static final double LARGEUR = 276;
    private static final double TITRE_MAC = 28;
    private static final double GAUCHE = 8, HAUT = TITRE_MAC + 8;
    private static final String CLE = "floor";

    // ------------------------------------------------------- logique pure

    /** Ce que fait « Quitter » : sortir tout de suite, demander, ou attendre la fin d'un envoi. */
    enum Sortie { DIRECTE, CONFIRMER, ATTENDRE }

    static Sortie sortie(int[] changements, boolean occupe) {
        if (occupe) return Sortie.ATTENDRE;
        return total(changements) == 0 ? Sortie.DIRECTE : Sortie.CONFIRMER;
    }

    static int total(int[] c) {
        if (c == null) return 0;
        int n = 0;
        for (int v : c) n += Math.max(0, v);
        return n;
    }

    /** « Aucun changement en attente. », « 1 changement en attente », « 6 changements en attente ». */
    static String enAttente(int[] c) {
        int n = total(c);
        if (n == 0) return "Aucun changement en attente.";
        return n + (n > 1 ? " changements en attente" : " changement en attente");
    }

    /** « 0 ajoutée », « 1 ajoutée », « 3 ajoutées ». */
    static String compte(int n, String singulier, String pluriel) {
        return n + " " + (n > 1 ? pluriel : singulier);
    }

    /** Hauteur des murs : -1 = automatique. */
    static String texteMur(int h) { return h < 0 ? "Auto" : String.valueOf(h); }

    private static final String[] EPAISSEURS = {"Très fin", "Fin", "Normal", "Épais"};

    /** Epaisseur -2..1 en mots. */
    static String texteEpaisseur(int e) { return EPAISSEURS[Math.max(0, Math.min(3, e + 2))]; }

    // ------------------------------------------------- branchement global

    private static Runnable demande = () -> { };

    /** Ce que fait une demande d'entrer en mode Floor (AtelierLauncher). */
    static void surDemande(Runnable r) { demande = r == null ? () -> { } : r; }

    /** Entrer en mode Floor (ancien CalqueActions.cases). */
    static void demander() { demande.run(); }

    // ------------------------------------------------------------- fenetre

    private final Stage stage = new Stage();
    private final VBox cadre = new VBox();
    private final VBox corps = new VBox(8);
    private final Label etat = new Label();
    private final Map<ModeCases.Outil, ToggleButton> outils = new EnumMap<>(ModeCases.Outil.class);
    private final Label aideOutil = new Label();
    private final Label valeurN = new Label();
    private final List<ToggleButton> pinceaux = new ArrayList<>();
    private final ToggleButton rect = new ToggleButton();
    private final Button annuler, retablir, effacer, revenir, appliquer, arreter;
    private final ToggleButton reglages = new ToggleButton();
    private final VBox murs = new VBox(5);
    private final Label mur = new Label(), epMur = new Label(), epSol = new Label();
    private final Label total = new Label();
    private final HBox[] pastilles = new HBox[4];
    private final Label[] nombres = new Label[4];
    private final VBox confirmation = new VBox(6);
    private final Label confirmationTexte = new Label();
    private final Button confirmerAppliquer, confirmerAbandonner, confirmerRester;
    private final Timeline chrono;

    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("atelier");
    private boolean reduit = prefs.getBoolean("floor.reduit", false);
    private boolean actif = false, demarrage = false;
    private Runnable ensuite = null;
    private long debutEnvoi = 0;
    private Consumer<Boolean> surEtat = b -> { };
    private double[] habbo;

    public BarreFloor(String css) {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        // --- barre de titre (deplacable, clic droit : remettre a sa place)
        Label titre = new Label("Mode Floor");
        titre.getStyleClass().add("calques-titre");
        titre.setGraphic(Icones.trace(Icones.FLOOR, "icone-barre"));
        titre.setGraphicTextGap(7);
        ((javafx.scene.shape.SVGPath) titre.getGraphic()).setScaleX(0.7);
        ((javafx.scene.shape.SVGPath) titre.getGraphic()).setScaleY(0.7);
        Button reduire = new Button();
        reduire.setGraphic(Icones.trace(Icones.REDUIRE, "icone-fenetre"));
        reduire.getStyleClass().addAll("fenetre-bouton", "calques-reduire");
        reduire.setFocusTraversable(false);
        reduire.setOnAction(e -> basculerReduit());
        reduire.setTooltip(CalqueFenetre.bulle("Réduire la palette"));
        Button fermer = new Button();
        fermer.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        fermer.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        fermer.setFocusTraversable(false);
        fermer.setOnAction(e -> quitter(null));
        fermer.setTooltip(CalqueFenetre.bulle("Quitter le mode Floor (Échap)"));
        HBox boutonsTitre = new HBox(4, reduire, fermer);
        boutonsTitre.setAlignment(Pos.CENTER_RIGHT);
        boutonsTitre.setPickOnBounds(false);
        StackPane barre = new StackPane(titre, boutonsTitre);
        StackPane.setAlignment(titre, Pos.CENTER_LEFT);
        barre.getStyleClass().add("calques-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setOnMouseClicked(e -> { if (e.getClickCount() == 2) basculerReduit(); });

        // --- outils
        ToggleGroup gOutils = new ToggleGroup();
        FlowPane rangeeOutils = new FlowPane(3, 3);
        int touche = 1;
        for (ModeCases.Outil o : ModeCases.Outil.values()) {
            ToggleButton b = new ToggleButton();
            b.setGraphic(Icones.trace(icone(o), "icone-barre"));
            b.getStyleClass().addAll("barre-bouton", "calques-icone");
            b.setFocusTraversable(false);
            b.setToggleGroup(gOutils);
            b.setUserData(o);
            b.setTooltip(CalqueFenetre.bulle(o.libelle + " (touche " + touche++ + ") : " + o.aide));
            outils.put(o, b);
            rangeeOutils.getChildren().add(b);
        }
        gOutils.selectedToggleProperty().addListener((ob, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            ModeCases.Outil o = (ModeCases.Outil) b.getUserData();
            ModeCases.outil(o);
            aideOutil.setText(o.libelle + " : " + o.aide);
            ajuster();
        });
        aideOutil.setWrapText(true);
        aideOutil.setMaxWidth(LARGEUR - 20);
        aideOutil.setMinHeight(Region.USE_PREF_SIZE);
        aideOutil.getStyleClass().add("calques-aide");

        // --- hauteur N
        valeurN.getStyleClass().add("floor-valeur");
        valeurN.setOnScroll(e -> ModeCases.valeur(ModeCases.valeur() + (e.getDeltaY() > 0 ? 1 : -1)));
        Node n = pas(valeurN, "Hauteur N (Fixer, Ajouter, Auto) : −1",
                () -> ModeCases.valeur(ModeCases.valeur() - 1),
                "Hauteur N : +1", () -> ModeCases.valeur(ModeCases.valeur() + 1));
        valeurN.setTooltip(CalqueFenetre.bulle("Hauteur N des cases ajoutées ou fixées. La pipette la prend sur une case."));

        // --- pinceau et rectangle
        ToggleGroup gPinceau = new ToggleGroup();
        HBox rangeePinceau = new HBox(3);
        rangeePinceau.setAlignment(Pos.CENTER_LEFT);
        for (int i = 1; i <= 5; i++) {
            final int k = i;
            ToggleButton b = new ToggleButton(String.valueOf(i));
            b.getStyleClass().addAll("barre-bouton", "calques-icone", "floor-texte");
            b.setFocusTraversable(false);
            b.setToggleGroup(gPinceau);
            b.setTooltip(CalqueFenetre.bulle("Pinceau " + i + " × " + i + " cases"));
            b.setOnAction(e -> { if (!b.isSelected()) b.setSelected(true); ModeCases.pinceau(k); });
            pinceaux.add(b);
            rangeePinceau.getChildren().add(b);
        }
        rect.setGraphic(Icones.trace(Icones.CADRE, "icone-barre"));
        rect.getStyleClass().addAll("barre-bouton", "calques-icone");
        rect.setFocusTraversable(false);
        rect.setTooltip(CalqueFenetre.bulle("Rectangle : clique deux coins dans le jeu, l'outil s'applique à toute la zone"));
        rect.setOnAction(e -> {
            ModeCases.rectangle(rect.isSelected());
            for (ToggleButton b : pinceaux) b.setDisable(rect.isSelected());
            if (rect.isSelected()) InfoJeu.consigne("Rectangle : clique le premier coin.");
        });
        Region sepP = new Region();
        sepP.setMinWidth(5);
        rangeePinceau.getChildren().addAll(sepP, rect);

        // --- historique
        annuler = icone(Icones.ANNULER, "Annuler le dernier changement (Cmd+Z)", () -> Salle.tache("floor-annuler", ModeCases::annuler));
        retablir = icone(Icones.RETABLIR, "Rétablir (Cmd+Maj+Z)", () -> Salle.tache("floor-retablir", ModeCases::retablir));
        effacer = icone(Icones.CORBEILLE, "Tout effacer : oublie les changements pas encore appliqués", () -> Salle.tache("floor-effacer", ModeCases::effacer));
        revenir = icone(Icones.PIVOTER_INVERSE, "Remettre le floor d'avant le dernier « Appliquer » (l'appart se recharge)",
                () -> Salle.tache("floor-revenir", ModeCases::revenir));
        Region sepH = new Region();
        sepH.setMinWidth(5);
        HBox rangeeHisto = new HBox(3, annuler, retablir, effacer, sepH, revenir);
        rangeeHisto.setAlignment(Pos.CENTER_LEFT);

        // --- murs et sol (derriere la roue)
        reglages.setGraphic(Icones.trace(Icones.REGLAGES, "icone-barre"));
        reglages.getStyleClass().addAll("barre-bouton", "calques-icone");
        reglages.setFocusTraversable(false);
        reglages.setTooltip(CalqueFenetre.bulle("Murs et sol : hauteur des murs, épaisseurs"));
        reglages.setOnAction(e -> { murs.setVisible(reglages.isSelected()); ajuster(); });
        for (Label l : List.of(mur, epMur, epSol)) l.getStyleClass().add("floor-valeur");
        GridPane gMurs = new GridPane();
        gMurs.setHgap(8); gMurs.setVgap(4);
        gMurs.addRow(0, petitTexte("Hauteur des murs"), pas(mur, "Murs plus bas", () -> changerMurs(-1, 0, 0), "Murs plus hauts", () -> changerMurs(1, 0, 0)));
        gMurs.addRow(1, petitTexte("Épaisseur des murs"), pas(epMur, "Murs plus fins", () -> changerMurs(0, -1, 0), "Murs plus épais", () -> changerMurs(0, 1, 0)));
        gMurs.addRow(2, petitTexte("Épaisseur du sol"), pas(epSol, "Sol plus fin", () -> changerMurs(0, 0, -1), "Sol plus épais", () -> changerMurs(0, 0, 1)));
        mur.setTooltip(CalqueFenetre.bulle("Auto : le jeu choisit la hauteur des murs"));
        murs.getChildren().add(gMurs);
        murs.getStyleClass().add("floor-boite");
        murs.managedProperty().bind(murs.visibleProperty());
        murs.setVisible(false);

        VBox reglage = new VBox(6,
                rangee("Hauteur N", n),
                rangee("Pinceau", rangeePinceau),
                rangee("Historique", rangeeHisto),
                rangee("Murs et sol", reglages));

        // --- en attente : compte aux couleurs de la grille
        total.getStyleClass().add("floor-total");
        String[] couleurs = {VERT, ROUGE, BLEU, ORANGE};
        String[] bulles = {"Cases ajoutées (vertes dans le jeu)", "Cases retirées (rouges)",
                "Cases dont la hauteur change (bleues)", "Porte déplacée ou tournée, murs ou sol changés (orange)"};
        GridPane gCompte = new GridPane();
        gCompte.setHgap(10); gCompte.setVgap(3);
        for (int i = 0; i < 4; i++) {
            nombres[i] = new Label();
            nombres[i].getStyleClass().add("floor-pastille-texte");
            pastilles[i] = new HBox(6, losange(couleurs[i], false), nombres[i]);
            pastilles[i].setAlignment(Pos.CENTER_LEFT);
            Label tip = nombres[i];
            tip.setTooltip(CalqueFenetre.bulle(bulles[i]));
            gCompte.add(pastilles[i], i % 2, i / 2);
        }
        VBox attente = new VBox(4, total, gCompte);
        attente.getStyleClass().add("floor-boite");

        // --- legende
        FlowPane legende = new FlowPane(9, 3,
                cle(losange(null, true), "Disponible"),
                cle(losange(VERT, false), "Ajoutée"),
                cle(losange(ROUGE, false), "Retirée"),
                cle(losange(BLEU, false), "Hauteur"),
                cle(losange(ORANGE, false), "Porte"));
        legende.setPrefWrapLength(LARGEUR - 20);
        Label chiffre = petitTexte("Le chiffre sur une case = sa hauteur.");
        chiffre.setWrapText(true);

        // --- etat, confirmation, boutons du bas
        etat.setWrapText(true);
        etat.setMaxWidth(LARGEUR - 18);
        etat.setMinHeight(Region.USE_PREF_SIZE);
        etat.getStyleClass().add("calques-etat");
        etat.visibleProperty().bind(etat.textProperty().isNotEmpty());
        etat.managedProperty().bind(etat.visibleProperty());

        confirmationTexte.setWrapText(true);
        confirmationTexte.setMaxWidth(LARGEUR - 34);
        confirmationTexte.setMinHeight(Region.USE_PREF_SIZE);
        confirmationTexte.getStyleClass().add("floor-confirmation-texte");
        confirmerAppliquer = texte("Appliquer", true, "Envoyer le floor puis quitter", this::confirmeAppliquer);
        confirmerAbandonner = texte("Abandonner", false, "Quitter sans appliquer : les changements sont oubliés", this::confirmeAbandonner);
        confirmerRester = texte("Rester", false, "Revenir au mode Floor", this::cacherConfirmation);
        HBox bc = new HBox(5, confirmerAppliquer, confirmerAbandonner, confirmerRester);
        bc.setAlignment(Pos.CENTER_RIGHT);
        confirmation.getChildren().addAll(confirmationTexte, bc);
        confirmation.getStyleClass().add("floor-confirmation");
        confirmation.managedProperty().bind(confirmation.visibleProperty());
        confirmation.setVisible(false);

        appliquer = texte("Appliquer", true, "Envoyer tous les changements au jeu : l'appart se recharge une seule fois",
                () -> Salle.tache("floor-appliquer", ModeCases::appliquer));
        appliquer.setGraphic(Icones.petite(Icones.ENREGISTRER, 15, true));
        appliquer.setGraphicTextGap(6);
        appliquer.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(appliquer, Priority.ALWAYS);
        arreter = texte("Arrêter", false, "Cesser d'attendre le retour du jeu", ModeCases::arreterAttente);
        arreter.managedProperty().bind(arreter.visibleProperty());
        arreter.setVisible(false);
        Button quitterB = texte("Quitter", false, "Quitter le mode Floor (Échap)", () -> quitter(null));
        quitterB.setGraphic(Icones.petite(Icones.VIDER, 13, true));
        quitterB.setGraphicTextGap(5);
        HBox bas = new HBox(5, appliquer, arreter, quitterB);
        bas.setAlignment(Pos.CENTER_LEFT);

        corps.getChildren().addAll(
                section("Outil"), rangeeOutils, aideOutil,
                reglage, murs,
                section("En attente"), attente,
                section("Légende de la grille"), legende, chiffre,
                etat, confirmation, bas);
        corps.getStyleClass().add("calques-corps");
        corps.setPadding(new Insets(7, 8, 8, 8));

        cadre.getChildren().addAll(barre, corps);
        cadre.getStyleClass().addAll("calques-panneau", "floor-panneau");
        cadre.setPrefWidth(LARGEUR);
        cadre.setMinWidth(LARGEUR);
        cadre.setMaxWidth(LARGEUR);
        appliquerReduit();

        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));
        Scene scene = new Scene(racine);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        CalqueStyle.appliquer(scene);
        scene.getStylesheets().add(STYLE);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::touche);
        stage.setScene(scene);
        Deplacement.activer(stage, barre, CLE, this::placer);
        BarrePremierPlan.menu(stage);
        Ui.majusculesAuto(corps);

        chrono = new Timeline(new KeyFrame(Duration.seconds(1), e -> majEnvoi()));
        chrono.setCycleCount(Timeline.INDEFINITE);

        ModeCases.surChangement(() -> Platform.runLater(this::rafraichir));
        RaccourcisGlobaux.surFloor(
                () -> {
                    Salle.tache("floor-echap", EchapDeplacement::lacherMobi);   // mobi tenu : lache (sinon rien)
                    Platform.runLater(this::echap);
                },
                i -> Platform.runLater(() -> choisirOutil(i, true)),
                () -> historiqueJeu(true),
                () -> historiqueJeu(false));
        synchroniser();
        rafraichir();
    }

    public Stage fenetre() { return stage; }

    /** Le mode est ouvert (palette affichee). */
    public boolean actif() { return actif; }

    /** Appele a chaque entree / sortie du mode (bouton Floor allume ou non). */
    public void surEtat(Consumer<Boolean> c) { surEtat = c == null ? b -> { } : c; }

    // ------------------------------------------------------------ cycle

    /** Entre en mode Floor : palette affichee, floor lu, grille du jeu en edition. */
    public void entrer() {
        if (actif) { stage.toFront(); return; }
        actif = true;
        demarrage = true;
        RaccourcisGlobaux.floor(true);
        surEtat.accept(true);
        cacherConfirmation();
        synchroniser();
        dire("Lecture du floor de l'appart…");
        montrer();
        Salle.tache("floor-demarrer", () -> {
            String err = ModeCases.demarrer();
            Platform.runLater(() -> {
                demarrage = false;
                if (!actif) { ModeCases.arreter(); return; }     // quitte entre-temps
                if (err != null) { Journal.erreur(err); sortir(); return; }
                dire("");
                synchroniser();
                rafraichir();
            });
        });
    }

    /**
     * Quitter : tout de suite s'il n'y a rien en attente, sinon la question
     * (Appliquer / Abandonner / Rester) dans la palette. « ensuite » part une
     * fois sorti (ex. entrer en mode Construction) ; rien si on reste.
     */
    public void quitter(Runnable ensuite) {
        if (!actif) { if (ensuite != null) ensuite.run(); return; }
        if (demarrage) { sortir(); if (ensuite != null) ensuite.run(); return; }
        switch (sortie(ModeCases.changements(), ModeCases.occupe())) {
            case ATTENDRE:
                InfoJeu.consigne("Attends la fin de l'envoi du floor.");
                return;
            case DIRECTE:
                sortir();
                InfoJeu.consigne("Mode Floor quitté.");
                if (ensuite != null) ensuite.run();
                return;
            default:
                this.ensuite = ensuite;
                montrerConfirmation();
        }
    }

    /** Sortie sans question (Atelier masque, deconnexion). */
    public void arreter() { if (actif) sortir(); }

    private void sortir() {
        actif = false;
        ensuite = null;
        chrono.stop();
        RaccourcisGlobaux.floor(false);
        ModeCases.arreter();
        cacherConfirmation();
        dire("");
        stage.hide();
        surEtat.accept(false);
    }

    private void montrerConfirmation() {
        int[] c = ModeCases.changements();
        confirmationTexte.setText(Ui.majuscule(enAttente(c)) + " : les appliquer avant de quitter ?");
        confirmation.setVisible(true);
        if (reduit) basculerReduit();
        ajuster();
        stage.toFront();
    }

    private void cacherConfirmation() {
        ensuite = null;
        confirmation.setVisible(false);
        ajuster();
    }

    private void confirmeAppliquer() {
        Runnable apres = ensuite;
        for (Button b : List.of(confirmerAppliquer, confirmerAbandonner, confirmerRester)) b.setDisable(true);
        Salle.tache("floor-appliquer", () -> {
            ModeCases.appliquer();
            Platform.runLater(() -> {
                for (Button b : List.of(confirmerAppliquer, confirmerAbandonner, confirmerRester)) b.setDisable(false);
                if (!actif) return;
                if (total(ModeCases.changements()) == 0) {
                    sortir();
                    if (apres != null) apres.run();
                } else cacherConfirmation();          // refuse : la raison est dite dans le jeu, on reste
            });
        });
    }

    private void confirmeAbandonner() {
        Runnable apres = ensuite;
        sortir();
        InfoJeu.consigne("Mode Floor quitté : changements abandonnés.");
        if (apres != null) apres.run();
    }

    // ------------------------------------------------------------ etat

    /** Les choix de la palette suivent ModeCases (outil, pinceau, rectangle). */
    private void synchroniser() {
        ToggleButton o = outils.get(ModeCases.outil());
        if (o != null) o.setSelected(true);
        ModeCases.Outil t = ModeCases.outil();
        aideOutil.setText(t.libelle + " : " + t.aide);
        int p = ModeCases.pinceau();
        for (int i = 0; i < pinceaux.size(); i++) pinceaux.get(i).setSelected(i + 1 == p);
        rect.setSelected(ModeCases.rectangle());
        for (ToggleButton b : pinceaux) b.setDisable(rect.isSelected());
    }

    /** Compte, boutons, valeurs : apres chaque clic dans le jeu (ModeCases.surChangement). */
    private void rafraichir() {
        if (actif && !demarrage && !ModeCases.actif() && !ModeCases.occupe()) {
            // sorti de l'appart : le mode s'est arrete tout seul
            sortir();
            return;
        }
        int[] c = ModeCases.changements();
        int n = total(c);
        boolean libre = !ModeCases.occupe() && !demarrage;
        total.setText(Ui.majuscule(enAttente(c)));
        String[][] mots = {{"ajoutée", "ajoutées"}, {"retirée", "retirées"}, {"hauteur", "hauteurs"}};
        for (int i = 0; i < 4; i++) {
            nombres[i].setText(i == 3 ? (c[3] > 0 ? "Porte ou murs changés" : "Porte ou murs")
                    : compte(c[i], mots[i][0], mots[i][1]));
            pastilles[i].setOpacity(c[i] > 0 ? 1 : 0.35);
        }
        valeurN.setText(String.valueOf(ModeCases.valeur()));
        appliquer.setDisable(n == 0 || !libre);
        effacer.setDisable(n == 0 || !libre);
        annuler.setDisable(!ModeCases.peutAnnuler() || !libre);
        retablir.setDisable(!ModeCases.peutRetablir() || !libre);
        revenir.setDisable(!ModeCases.peutRevenir());
        FloorModele t = ModeCases.travail();
        mur.setText(t == null ? "—" : texteMur(t.hauteurMur));
        epMur.setText(t == null ? "—" : texteEpaisseur(t.epMur));
        epSol.setText(t == null ? "—" : texteEpaisseur(t.epSol));
        if (ModeCases.occupe()) {
            if (debutEnvoi == 0) { debutEnvoi = System.currentTimeMillis(); chrono.playFromStart(); }
            majEnvoi();
        } else if (debutEnvoi != 0) {
            debutEnvoi = 0;
            chrono.stop();
            arreter.setVisible(false);
            dire("");
        }
        ajuster();
    }

    /** Progression de l'envoi ; « Arreter » apres quelques secondes. */
    private void majEnvoi() {
        if (debutEnvoi == 0) return;
        long s = (System.currentTimeMillis() - debutEnvoi) / 1000;
        dire("Envoi du floor au jeu, l'appart va se recharger…" + (s >= 2 ? " " + s + " s" : ""));
        arreter.setVisible(s >= 3);
    }

    private void changerMurs(int dMur, int dEpMur, int dEpSol) {
        FloorModele t = ModeCases.travail();
        if (t == null) return;
        int h = Math.max(-1, Math.min(15, t.hauteurMur + dMur));
        int em = Math.max(-2, Math.min(1, t.epMur + dEpMur));
        int es = Math.max(-2, Math.min(1, t.epSol + dEpSol));
        Salle.tache("floor-murs", () -> ModeCases.murs(h, em, es));
    }

    private void dire(String s) {
        etat.setText(s == null ? "" : WindowsClavier.texte(Ui.majuscule(s)));
        ajuster();
    }

    /** Echap (palette ou jeu) : ferme la question, sinon oublie le coin en attente, sinon Quitter. */
    private void echap() {
        if (!actif) return;
        if (confirmation.isVisible()) { cacherConfirmation(); return; }
        if (ModeCases.oublierCoin()) { InfoJeu.consigne("Rectangle annulé : clique le premier coin."); return; }
        quitter(null);
    }

    /** Outil n° i (0..7, ordre de la palette). */
    private void choisirOutil(int i, boolean dansLeJeu) {
        ModeCases.Outil[] t = ModeCases.Outil.values();
        if (!actif || i < 0 || i >= t.length) return;
        ToggleButton b = outils.get(t[i]);
        if (b == null) return;
        b.setSelected(true);
        if (dansLeJeu) InfoJeu.consigne("Outil " + t[i].libelle + ".");
    }

    /** Cmd/Ctrl+Z (annuler) ou Cmd/Ctrl+Maj+Z (retablir) dans le jeu, sur le floor en travail. */
    private void historiqueJeu(boolean annulerIci) {
        if (!actif || demarrage || ModeCases.occupe()) return;
        if (annulerIci ? !ModeCases.peutAnnuler() : !ModeCases.peutRetablir()) {
            InfoJeu.consigne(annulerIci ? "Rien à annuler dans le floor." : "Rien à rétablir dans le floor.");
            return;
        }
        Salle.tache(annulerIci ? "floor-annuler" : "floor-retablir", annulerIci ? ModeCases::annuler : ModeCases::retablir);
    }

    /** Chiffre 1..9 d'une touche : rangee des chiffres (AZERTY sans Maj compris) ou pave numerique ; 0 sinon. */
    static int chiffre(KeyCode k, String texte) {
        if (k != null) {
            String n = k.getName();             // « 1 », « Numpad 1 »...
            if (k.isDigitKey() && n.length() > 0) {
                char c = n.charAt(n.length() - 1);
                if (c >= '1' && c <= '9') return c - '0';
            }
        }
        if (texte != null && texte.length() == 1 && texte.charAt(0) >= '1' && texte.charAt(0) <= '9') return texte.charAt(0) - '0';
        return 0;
    }

    /** Touches quand la palette a le focus : 1 a 8 les outils, Cmd+Z, Echap. */
    private void touche(KeyEvent e) {
        if (!actif) return;
        if (e.getCode() == KeyCode.ESCAPE) {
            echap();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.Z && e.isShortcutDown()) {
            if (e.isShiftDown()) retablir.fire(); else annuler.fire();
            e.consume();
            return;
        }
        if (e.isShortcutDown() || e.isAltDown()) return;
        int k = chiffre(e.getCode(), e.getText());
        if (k >= 1 && k <= ModeCases.Outil.values().length) {
            choisirOutil(k - 1, false);
            e.consume();
        }
    }

    // ------------------------------------------------------------ morceaux

    private static String icone(ModeCases.Outil o) {
        switch (o) {
            case MONTER: return Icones.CASE_MONTER;
            case DESCENDRE: return Icones.CASE_DESCENDRE;
            case FIXER: return Icones.CASE_FIXER;
            case AJOUTER: return Icones.CASE_AJOUTER;
            case SUPPRIMER: return Icones.CASE_RETIRER;
            case PORTE: return Icones.PORTE;
            case PIPETTE: return Icones.PIPETTE;
            default: return Icones.CASE_AUTO;
        }
    }

    private static Button icone(String trace, String bulle, Runnable r) {
        Button b = new Button();
        b.setGraphic(Icones.trace(trace, "icone-barre"));
        b.getStyleClass().addAll("barre-bouton", "calques-icone");
        b.setFocusTraversable(false);
        b.setTooltip(CalqueFenetre.bulle(bulle));
        b.setOnAction(e -> r.run());
        return b;
    }

    private static Button texte(String t, boolean principal, String bulle, Runnable r) {
        Button b = new Button(t);
        b.getStyleClass().add("calques-bouton");
        if (principal) b.getStyleClass().add("calques-principal");
        b.setFocusTraversable(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
        if (bulle != null) b.setTooltip(CalqueFenetre.bulle(bulle));
        b.setOnAction(e -> r.run());
        return b;
    }

    /** [−] valeur [+] */
    private static Node pas(Label valeur, String bulleMoins, Runnable moins, String bullePlus, Runnable plus) {
        ButtonBase m = icone("M6 12h12", bulleMoins, moins), p = icone("M12 6v12 M6 12h12", bullePlus, plus);
        valeur.setMinWidth(54);
        valeur.setAlignment(Pos.CENTER);
        HBox h = new HBox(3, m, valeur, p);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Une rangee avec son intitule a gauche (comme le panneau des calques). */
    private static Node rangee(String titre, Node contenu) {
        Label t = new Label(titre);
        t.getStyleClass().add("calques-intitule");
        t.setMinWidth(74);
        HBox h = new HBox(6, t, contenu);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label section(String titre) {
        Label t = new Label(titre);
        t.getStyleClass().add("calques-intitule");
        return t;
    }

    private static Label petitTexte(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("calques-aide");
        return l;
    }

    /** Une case isometrique de la couleur de la grille ; possible = blanc pale filete de gris. */
    static Polygon losange(String couleur, boolean possible) {
        Polygon p = new Polygon(0, 5, 10, 0, 20, 5, 10, 10);
        if (possible) {
            p.setFill(Color.rgb(255, 255, 255, 0.5));
            p.setStroke(Color.web("#9A978E"));
        } else {
            p.setFill(Color.web(couleur));
            p.setStroke(Color.web("#111111"));
        }
        p.setStrokeWidth(1);
        return p;
    }

    private static Node cle(Node signe, String mot) {
        Label l = new Label(mot);
        l.getStyleClass().add("floor-legende");
        HBox h = new HBox(4, signe, l);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    // ------------------------------------------------------- affichage

    private void montrer() {
        if (!stage.isShowing()) stage.show();
        ajuster();
        stage.toFront();
    }

    private void basculerReduit() {
        reduit = !reduit;
        prefs.putBoolean("floor.reduit", reduit);
        appliquerReduit();
        ajuster();
    }

    private void appliquerReduit() {
        corps.setVisible(!reduit);
        corps.setManaged(!reduit);
        if (reduit) cadre.getStyleClass().add("reduite");
        else cadre.getStyleClass().remove("reduite");
    }

    /** La hauteur suit le contenu. */
    private void ajuster() {
        if (!stage.isShowing()) return;
        stage.sizeToScene();
        placer();
    }

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(this::placer);
    }

    /** En haut a gauche du jeu, a la place du panneau des calques (+ decalage choisi en glissant). */
    private void placer() {
        double x, y;
        if (habbo != null) { x = habbo[0] + GAUCHE; y = habbo[1] + HAUT; }
        else {
            javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
            x = e.getMinX() + GAUCHE;
            y = e.getMinY() + HAUT - TITRE_MAC;
        }
        stage.setX(x + Deplacement.dx(CLE));
        stage.setY(y + Deplacement.dy(CLE));
    }

    // ------------------------------------------------------------ style

    /** Regles propres a la palette (en plus de styling.css et CalqueStyle). */
    static final String CSS = String.join("\n",
            ".floor-panneau .calques-icone.floor-texte { -fx-text-fill: #E9E6DC; -fx-font-size: 12px; -fx-font-weight: bold; }",
            ".floor-panneau .calques-icone.floor-texte:selected { -fx-text-fill: #FFFFFF; }",
            ".floor-panneau .label.floor-valeur {",
            "  -fx-text-fill: #F2EFE6; -fx-font-size: 12px; -fx-font-weight: bold;",
            "  -fx-background-color: #111111, #26282B; -fx-background-insets: 0, 1; -fx-background-radius: 4, 3;",
            "  -fx-padding: 4 6 4 6; }",
            ".floor-boite {",
            "  -fx-background-color: #111111, #26282B; -fx-background-insets: 0, 1;",
            "  -fx-background-radius: 5, 4; -fx-padding: 6 8 6 8; }",
            ".floor-panneau .label.floor-total { -fx-text-fill: #F2EFE6; -fx-font-size: 12px; -fx-font-weight: bold; }",
            ".floor-panneau .label.floor-pastille-texte { -fx-text-fill: #F2EFE6; -fx-font-size: 11px; }",
            ".floor-panneau .label.floor-legende { -fx-text-fill: #C9C6BC; -fx-font-size: 10px; }",
            ".floor-confirmation {",
            "  -fx-background-color: #9FD2EA, #2B4F63; -fx-background-insets: 0, 1;",
            "  -fx-background-radius: 5, 4; -fx-padding: 7 8 7 8; }",
            ".floor-panneau .label.floor-confirmation-texte { -fx-text-fill: #FFFFFF; -fx-font-size: 12px; -fx-font-weight: bold; }"
    );

    private static final String STYLE = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));
}

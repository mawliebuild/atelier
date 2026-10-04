package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Les calques, facon Photoshop, dans leur propre panneau a gauche du jeu (le
 * gris de la barre d'icones du haut). Il apparait quand le menu Construction
 * est ouvert (dans un appart seulement) et se reduit a sa barre de titre.
 * Compact, sans defilement : tous les boutons sont des icones avec une bulle
 * rapide ; ce qui demande des fleches ou une saisie s'ouvre dans une petite
 * fenetre a cote (CalqueActions).
 *
 * De haut en bas :
 *   - les outils : mode selection (Option + C), zone, vider la selection ;
 *     deplacer, pivoter, miroir, hauteur (sur le calque choisi, ou la
 *     selection de mobis si elle est plus recente) ; regle, grille,
 *     escalier, familles ;
 *   - la liste : une ligne par calque = oeil, cadenas, nom, nombre de mobis.
 *     En bas, les calques de base : « Mobis » (tous les mobis hors calque),
 *     « Mur » et « Sol » (verrouilles ; le sol ne se masque pas). Cmd/Ctrl +
 *     clic et Maj + clic choisissent plusieurs calques ; double-clic sur le
 *     nom = renommer sur place ; glisser-deposer = changer l'ordre ;
 *     clic droit = le reste ;
 *   - la barre du bas, comme Photoshop : verrouiller, masquer, fusionner,
 *     nouveau calque, dupliquer, supprimer ; sur TOUS les calques choisis.
 *
 * Supprimer un calque RAMASSE ses mobis (Cmd+Z les repose) ; pour les
 * garder, on fusionne. Les resultats des actions sont dits dans le jeu.
 * Ctrl/Cmd+C copie le calque vise, Ctrl/Cmd+V le colle (GroupePressePapier).
 *
 * Le moteur est dans Groupes (calques) et Calques (masquage cote client).
 */
public class PanneauCalques implements Ancrage.Ancrable {

    private static final double LARGEUR = 276;
    private static final double TITRE_MAC = 28;
    /** Hauteur d'une ligne de la liste, et nombre de lignes visibles au plus (au-dela, la liste defile). */
    private static final double LIGNE = 27;
    private static final int LIGNES_MAX = 11;

    private final Stage stage = new Stage();
    private final VBox cadre = new VBox();
    private final VBox corps = new VBox(6);
    private final Label etat = new Label();

    // --- liste
    private final ListView<Groupes.Info> liste = new ListView<>();
    private final Label selectionLbl = new Label();
    private final Tooltip selectionBulle = CalqueFenetre.bulle("");
    private ToggleButton modeSel;
    private Button bVerrou, bOeil, bFusion, bNouveau, bDupliquer, bSupprimer;
    // --- cible des actions : le calque clique dans la liste, ou la selection de mobis
    /** Vrai quand la selection de mobis est plus recente que le clic sur un calque. */
    private boolean preferSelection = false;
    private int derniereSelection = 0;
    private boolean choixParProgramme = false;
    /** Calque a selectionner dans la liste des qu'il y apparait (cree a l'instant). */
    private String aChoisir = null;
    /** Calque en cours de renommage sur place (la liste n'est pas rafraichie pendant). */
    private String renommage = null;
    private boolean rafraichirApres = false;
    // --- branchements (AtelierLauncher)
    private Runnable surMesure = null;
    private Runnable surEscalier = null;
    private Button regle;

    private final Preferences prefs = Preferences.userRoot().node("atelier");
    /** Fixe, en haut a gauche du jeu, juste sous la barre de titre macOS. */
    private static final double GAUCHE = 8, HAUT = TITRE_MAC + 8;
    private boolean reduit = prefs.getBoolean("calques.reduit", false);
    private volatile boolean actif = false;
    private double[] habbo;

    private final String css;
    private final Node familles;
    private final CalqueActions actions;

    public PanneauCalques(String css, Node familles) {
        this.css = css;
        this.familles = familles;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Calques");               // BarrePremierPlan.estPanneau le reconnait a ce titre
        actions = new CalqueActions(css, stage, this::choisirCalque);

        // --- barre de titre, grise comme la barre du haut
        Label titre = new Label("Calques");
        titre.getStyleClass().add("calques-titre");
        Button reduire = new Button();
        reduire.setGraphic(Icones.trace(Icones.REDUIRE, "icone-fenetre"));
        reduire.getStyleClass().addAll("fenetre-bouton", "calques-reduire");
        reduire.setFocusTraversable(false);
        reduire.setOnAction(e -> basculer());
        HBox boutons = new HBox(reduire);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.setPickOnBounds(false);
        StackPane barre = new StackPane(titre, boutons);
        StackPane.setAlignment(titre, Pos.CENTER_LEFT);
        barre.getStyleClass().add("calques-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setOnMouseClicked(e -> { if (e.getClickCount() == 2) basculer(); });

        corps.getChildren().addAll(outils(), selectionLigne(), listeEtBarre(), etat);
        corps.getStyleClass().add("calques-corps");
        corps.setPadding(new Insets(7, 8, 8, 8));
        etat.setWrapText(true);
        etat.getStyleClass().add("calques-etat");
        etat.visibleProperty().bind(etat.textProperty().isNotEmpty());
        etat.managedProperty().bind(etat.visibleProperty());

        cadre.getChildren().addAll(barre, corps);
        cadre.getStyleClass().add("calques-panneau");
        cadre.setPrefWidth(LARGEUR);
        appliquerReduit();

        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));
        Scene scene = new Scene(racine, LARGEUR, 420);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        CalqueStyle.appliquer(scene);
        stage.setScene(scene);
        stage.setWidth(LARGEUR);
        // Cmd/Cmd+Z (annuler), Cmd/Ctrl+C et V (copier / coller un calque) quand
        // le panneau a le focus ; dans le jeu, c'est RaccourcisGlobaux.
        OutilHistorique.installerRaccourcis(scene);
        RaccourcisGlobaux.surCalques(this::copierCalque, this::collerCalque);
        BarrePremierPlan.menu(stage);
        // met en cache la lecture du SWF (~11 Mo) avant le premier rafraichissement
        Thread lecture = new Thread(ClientModifie::saitSurligner, "calques-surligner");
        lecture.setDaemon(true);
        lecture.start();

        Groupes.ecouter(this::rafraichir);
        Zone.ecouter(this::zoneChoisie);
        Ui.majusculesAuto(corps);
        suivreSalle();
    }

    /**
     * Le menu Construction est ouvert : le panneau apparait (dans un appart
     * seulement), et disparait quand on change de menu ou ferme la fenetre.
     */
    public void actif(boolean a) {
        if (a == actif) return;
        actif = a;
        RaccourcisGlobaux.calques(a);           // Ctrl+C / Ctrl+V dans le jeu, comme la touche S
        if (!a) Platform.runLater(() -> { actions.fermer(); stage.hide(); });
        else Platform.runLater(() -> {
            if (actif && Salle.dansUneSalle()) { Groupes.installer(); rafraichir(); stage.show(); placer(); }
        });
    }

    // ================================================================ outils

    /** Les icones du haut : selection, actions sur le contenu, outils. */
    private Node outils() {
        modeSel = new ToggleButton();
        modeSel.setGraphic(Icones.trace(Icones.CURSEUR, "icone-barre"));
        modeSel.getStyleClass().addAll("barre-bouton", "calques-icone");
        modeSel.setFocusTraversable(false);
        modeSel.setTooltip(CalqueFenetre.bulle("Mode sélection (Option + C) : chaque clic sur un mobi dans le jeu l'ajoute à la sélection, ou l'en retire"));
        modeSel.setOnAction(e -> {
            boolean on = modeSel.isSelected();
            Groupes.modeSelection(on);
            if (!on) Groupes.viderSelection();
        });
        Button zone = icone(Icones.CADRE, "Sélectionner une zone : deux clics dans le jeu. Ses mobis deviennent un nouveau calque.", () -> {
            selectionParZone = true;
            Zone.demarrerChoix();
            dire("Clique les deux coins de la zone dans le jeu.");
            InfoJeu.consigne("Sélection par zone : clique le premier coin.");
        });
        Button vider = icone(Icones.VIDER, "Vider la sélection de mobis", () -> { Groupes.viderSelection(); dire(""); });

        Button deplacer = icone(Icones.DEPLACER, "Déplacer le calque choisi (flèches, pivot, fantômes dans le jeu)",
                () -> surCible(actions::deplacerDepuisPanneau));
        Button pivoter = icone(Icones.PIVOTER, "Pivoter le calque choisi d'un bloc (le tout tourne ensemble), ou une copie pivotée à côté",
                () -> surCible(actions::pivoter));
        Button miroir = icone(Icones.MIROIR, "Miroir du calque choisi : retourné sur place, ou copie miroir à côté",
                () -> surCible(actions::miroir));
        Button hauteur = icone(Icones.HAUTEUR, "Hauteur des mobis du calque choisi (+1, -0,5 ou une valeur)",
                () -> surCible(actions::hauteur));

        Button remplir = icone(Icones.REMPLIR, "Remplir une zone avec un mobi : choisis la zone (deux cases), puis pose "
                + "le mobi depuis ton inventaire, il couvre toute la zone (re-clique pour annuler)",
                RemplirZone::lancer);

        Button etats = icone(Icones.ETAT, "Changer l'état des mobis d'une zone : choisis la zone (deux cases), "
                + "puis chaque clic sur le bouton de la fenêtre les change tous", actions::etatsZone);

        regle = icone(Icones.REGLE, "Mesurer : clique deux cases dans le jeu", () -> {
            Runnable r = surMesure;
            if (r == null) dire("La règle n'est pas encore branchée."); else r.run();
        });
        // visible seulement une fois branchee (surMesure) : jamais de bouton mort
        regle.setVisible(false);
        regle.managedProperty().bind(regle.visibleProperty());
        ToggleButton grille = new ToggleButton();
        grille.setGraphic(Icones.trace(Icones.GRILLE, "icone-barre"));
        grille.getStyleClass().addAll("barre-bouton", "calques-icone");
        grille.setFocusTraversable(false);
        grille.setTooltip(CalqueFenetre.bulle("Voir la grille (Option + G) : traits autour des cases, par-dessus les mobis (chez toi seulement)"));
        grille.setSelected(GrilleVue.voulue());
        GrilleVue.ecouter(() -> grille.setSelected(GrilleVue.voulue()));
        grille.setOnAction(e -> {
            boolean on = grille.isSelected();
            if (!GrilleVue.montrer(on)) grille.setSelected(false);
        });
        Button escalier = icone(Icones.ESCALIER, "Escalier / rampe : ouvre la fenêtre de l'outil", () -> {
            Runnable r = surEscalier;
            if (r == null) dire("L'escalier n'est pas encore branché."); else r.run();
        });
        Button fam = icone(Icones.FILTRES, "Familles : masquer d'un coup les wired, les dalles, les muraux, un nom…",
                () -> actions.familles(familles));

        // Rangees nommees, comme avant : Selection, Actions, Composants, Masquer
        // (les familles s'ouvrent dans leur fenetre).
        VBox l = new VBox(4,
                rangee("Sélection", modeSel, zone, vider),
                rangee("Actions", deplacer, pivoter, miroir, hauteur, remplir, etats),
                rangee("Composants", escalier, regle, grille),
                rangee("Masquer", fam));
        return l;
    }

    /** Bouton Floor (barre du bas) : l'appart passe en edition du floor, fenetre d'outils a cote. */
    void ouvrirFloor() { actions.cases(); }

    /** Une rangee d'icones avec son intitule a gauche. */
    private static Node rangee(String titre, Node... icones) {
        Label t = new Label(titre);
        t.getStyleClass().add("calques-intitule");
        t.setMinWidth(68);
        FlowPane f = new FlowPane(3, 3, icones);
        f.setAlignment(Pos.CENTER_LEFT);
        f.setPrefWrapLength(LARGEUR - 90);
        HBox h = new HBox(6, t, f);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(f, Priority.ALWAYS);
        return h;
    }

    private static Region separateur() {
        Region r = new Region();
        r.setMinWidth(4); r.setPrefWidth(4);
        return r;
    }

    private static Button icone(String chemin, String aide, Runnable r) {
        Button b = new Button();
        b.setGraphic(Icones.trace(chemin, "icone-barre"));
        b.getStyleClass().addAll("barre-bouton", "calques-icone");
        b.setFocusTraversable(false);
        b.setTooltip(CalqueFenetre.bulle(aide));
        b.setOnAction(e -> r.run());
        return b;
    }

    /** La ligne d'etat de la selection de mobis (aide complete dans la bulle). */
    private Node selectionLigne() {
        selectionLbl.getStyleClass().add("calques-selection");
        selectionLbl.setTooltip(selectionBulle);
        selectionLbl.setMaxWidth(Double.MAX_VALUE);
        return selectionLbl;
    }

    /** Applique une action au calque vise (calque choisi, ou la selection si elle est plus recente). */
    private void surCible(java.util.function.Consumer<Groupes.Info> f) {
        Groupes.Info i = cible();
        if (i == null) { dire("Choisis d'abord un calque dans la liste, ou sélectionne des mobis (Option + C puis clic dans le jeu)."); return; }
        if (i.decor) { dire("« " + i.nom + " » se masque seulement."); return; }
        dire("");
        f.accept(i);
    }

    // ================================================================= liste

    private Node listeEtBarre() {
        liste.getStyleClass().addAll("calques-liste", "calques-photoshop");
        liste.setPlaceholder(new Label("Entre dans un appart."));
        liste.setCellFactory(l -> new Cellule());
        liste.setFixedCellSize(LIGNE);
        liste.setPrefHeight(3 * LIGNE + 6);
        liste.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        liste.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!choixParProgramme && b != null) preferSelection = false;
            majBarre();
        });
        liste.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Groupes.Info>) c -> majBarre());
        // Suppr / retour arriere : supprimer ; Cmd/Ctrl+E : fusionner (comme Photoshop)
        liste.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (renommage != null) return;
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) { supprimerChoisis(); e.consume(); }
            else if (e.getCode() == KeyCode.E && e.isShortcutDown()) { fusionnerChoisis(); e.consume(); }
        });

        bVerrou = icone(Icones.CADENAS, "Verrouiller ou déverrouiller les calques choisis", this::verrouillerChoisis);
        bOeil = icone(Icones.OEIL, "Masquer ou afficher les calques choisis (chez toi seulement)", this::basculerVisibiliteChoisis);
        bFusion = icone(Icones.FUSIONNER, "Fusionner : plusieurs calques dans le plus haut ; un seul avec celui du dessous (Cmd + E)",
                this::fusionnerChoisis);
        bNouveau = icone(Icones.CALQUE_NOUVEAU, "Nouveau calque : les mobis sélectionnés y passent (vide sans sélection)", this::nouveauCalque);
        bDupliquer = icone(Icones.DUPLIQUER, "Dupliquer le calque choisi : la copie devient un nouveau calque, puis tu la déplaces",
                () -> surCible(actions::dupliquer));
        bSupprimer = icone(Icones.CORBEILLE, "Supprimer les calques choisis : leurs mobis sont ramassés (Cmd+Z les repose). Pour les garder, fusionne.",
                this::supprimerChoisis);
        Region espace = new Region();
        HBox.setHgrow(espace, Priority.ALWAYS);
        HBox bas = new HBox(3, bVerrou, bOeil, bFusion, espace, bNouveau, bDupliquer, bSupprimer);
        bas.setAlignment(Pos.CENTER_LEFT);
        bas.getStyleClass().add("calques-bas");
        return new VBox(0, liste, bas);
    }

    /** Une ligne : l'oeil, le cadenas, le nom, le nombre de mobis ; clic droit pour le reste. */
    private final class Cellule extends ListCell<Groupes.Info> {
        private final Button oeil = new Button();
        private final Button cadenas = new Button();
        private final Label nom = new Label();
        private final Label nombre = new Label();
        private final TextField saisie = new TextField();
        private final HBox ligne;
        private final HBox corpsLigne;
        private long ctrlClic = 0;

        Cellule() {
            oeil.getStyleClass().add("calques-oeil");
            oeil.setFocusTraversable(false);
            cadenas.getStyleClass().add("calques-cadenas");
            cadenas.setFocusTraversable(false);
            nom.getStyleClass().add("calques-nom");
            nom.setMinWidth(0);
            nom.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nom, Priority.ALWAYS);
            HBox.setHgrow(saisie, Priority.ALWAYS);
            saisie.getStyleClass().add("calques-renommer");
            nombre.getStyleClass().add("calques-nombre");
            nombre.setMinWidth(Region.USE_PREF_SIZE);
            corpsLigne = new HBox(4, nom, nombre);
            corpsLigne.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(corpsLigne, Priority.ALWAYS);
            ligne = new HBox(2, oeil, cadenas, corpsLigne);
            ligne.setAlignment(Pos.CENTER_LEFT);
            // survol d'un calque : ses mobis s'allument dans le jeu
            MiseEnValeur.auSurvol(this, () -> {
                Groupes.Info i = getItem();
                if (i == null || i.decor) return List.of();
                List<String> j = new ArrayList<>();
                List<java.util.Set<Integer>> m = Groupes.mobis(i.id);
                for (int s : m.get(0)) j.add("s" + s);
                for (int w : m.get(1)) j.add("m" + w);
                return j;
            });

            oeil.setOnAction(e -> { Groupes.Info i = getItem(); if (i != null) basculerVisibilite(List.of(i)); });
            cadenas.setOnAction(e -> {
                Groupes.Info i = getItem();
                if (i == null) return;
                if (i.decor) { dire("« " + i.nom + " » est toujours verrouillé : il se masque seulement."); return; }
                verrouiller(List.of(i), !i.verrou);
            });
            nom.setOnMouseClicked(e -> {
                Groupes.Info i = getItem();
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && i != null) {
                    if (i.normal()) commencerRenommage(i);
                    else dire("« " + i.nom + " » est un calque de base : il ne se renomme pas.");
                }
            });
            saisie.setOnAction(e -> finirRenommage(true));
            saisie.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (e.getCode() == KeyCode.ESCAPE) { finirRenommage(false); e.consume(); } });
            saisie.focusedProperty().addListener((o, a, b) -> { if (!b && renommage != null) finirRenommage(true); });

            // Ctrl + clic (Mac : sinon un clic droit) choisit un calque de plus, comme Cmd + clic
            addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (isEmpty() || e.getButton() != MouseButton.PRIMARY || !e.isControlDown() || e.isMetaDown()) return;
                int i = getIndex();
                MultipleSelectionModel<Groupes.Info> sm = liste.getSelectionModel();
                if (sm.isSelected(i)) sm.clearSelection(i); else sm.select(i);
                ctrlClic = System.currentTimeMillis();
                e.consume();
            });
            addEventFilter(MouseEvent.MOUSE_RELEASED, e -> { if (System.currentTimeMillis() - ctrlClic < 600 && e.isControlDown()) e.consume(); });
            addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> { if (System.currentTimeMillis() - ctrlClic < 600) e.consume(); });
            setOnContextMenuRequested(e -> {
                // clic droit sur un calque pas choisi : il devient le seul choisi
                if (getItem() != null && !liste.getSelectionModel().isSelected(getIndex()))
                    liste.getSelectionModel().clearAndSelect(getIndex());
            });

            // glisser-deposer : l'ordre des calques (pas les calques de base)
            setOnDragDetected(e -> {
                Groupes.Info i = getItem();
                if (i == null || !i.normal() || renommage != null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent c = new ClipboardContent();
                c.putString("calque:" + i.id);
                db.setContent(c);
                e.consume();
            });
            setOnDragOver(e -> {
                if (deposable(e)) e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            setOnDragEntered(e -> {
                if (!deposable(e)) return;
                if (!getStyleClass().contains("calques-depot")) getStyleClass().add("calques-depot");
            });
            setOnDragExited(e -> getStyleClass().removeAll("calques-depot"));
            setOnDragDropped(e -> {
                boolean ok = false;
                if (deposable(e)) {
                    String id = e.getDragboard().getString().substring(7);
                    Groupes.Info cible = getItem();
                    int place = cible.normal() ? liste.getItems().indexOf(cible) : Groupes.nombreCalques();
                    ok = Groupes.placer(id, place);
                    if (ok) choisirCalque(id);
                }
                e.setDropCompleted(ok);
                e.consume();
            });
        }

        private boolean deposable(DragEvent e) {
            Groupes.Info i = getItem();
            Dragboard db = e.getDragboard();
            if (i == null || db == null || !db.hasString() || !db.getString().startsWith("calque:")) return false;
            String id = db.getString().substring(7);
            return !id.equals(i.id) && (i.normal() || Groupes.MOBIS.equals(i.id));
        }

        @Override protected void updateItem(Groupes.Info i, boolean vide) {
            super.updateItem(i, vide);
            getStyleClass().removeAll("calques-base-premiere", "calques-depot");
            if (vide || i == null) { setGraphic(null); setText(null); setContextMenu(null); return; }
            if (Groupes.MOBIS.equals(i.id)) getStyleClass().add("calques-base-premiere");
            if (i.base) { if (!ligne.getStyleClass().contains("calques-base")) ligne.getStyleClass().add("calques-base"); }
            else ligne.getStyleClass().remove("calques-base");

            // oeil
            if (!i.masquable) {
                oeil.setGraphic(Icones.trace(Icones.OEIL, "icone-barre"));
                if (!oeil.getStyleClass().contains("grise")) oeil.getStyleClass().add("grise");
                oeil.setTooltip(CalqueFenetre.bulle("Le sol ne peut pas être masqué : le jeu ne sait pas le cacher sans casser les clics et la marche."));
            } else {
                oeil.getStyleClass().remove("grise");
                oeil.setGraphic(Icones.trace(i.visible ? Icones.OEIL : Icones.OEIL_FERME, "icone-barre"));
                oeil.setTooltip(CalqueFenetre.bulle(i.visible ? "Masquer « " + i.nom + " » (chez toi seulement)" : "Afficher « " + i.nom + " »"));
            }
            // cadenas
            cadenas.setGraphic(Icones.trace(i.verrou ? Icones.CADENAS : Icones.CADENAS_OUVERT, "icone-barre"));
            if (i.verrou) cadenas.getStyleClass().remove("ouvert");
            else if (!cadenas.getStyleClass().contains("ouvert")) cadenas.getStyleClass().add("ouvert");
            cadenas.setTooltip(CalqueFenetre.bulle(i.decor ? "Toujours verrouillé : " + (Groupes.MUR.equals(i.id)
                            ? "les murs de l'appart se masquent seulement." : "le sol de l'appart reste tel quel.")
                    : i.verrou ? "Verrouillé : rien ne bouge. Clique pour déverrouiller."
                    : "Verrouiller : plus rien ne pourra le modifier (déplacer, pivoter, supprimer…)"));

            nom.setText(i.nom);
            nom.setOpacity(i.visible ? 1 : 0.5);
            nom.setTooltip(CalqueFenetre.bulle(bulleNom(i)));
            nombre.setText(i.decor ? "" : String.valueOf(i.nombre()));
            nombre.setVisible(!i.decor);
            if (i.id.equals(renommage)) {
                saisie.setText(i.nom);
                corpsLigne.getChildren().setAll(saisie);
                Platform.runLater(() -> { saisie.requestFocus(); saisie.selectAll(); });
            } else corpsLigne.getChildren().setAll(nom, nombre);
            setText(null);
            setGraphic(ligne);
            ContextMenu m = new ContextMenu();
            m.setOnShowing(e -> remplirMenu(m, i));
            m.getItems().add(new MenuItem(""));          // rempli a l'ouverture
            setContextMenu(m);
        }
    }

    private static String bulleNom(Groupes.Info i) {
        if (Groupes.MOBIS.equals(i.id)) return "Mobis : tous les mobis qui ne sont dans aucun autre calque (" + i.nombre() + "). Il ne se supprime pas.";
        if (Groupes.MUR.equals(i.id)) return "Mur : les murs de l'appart. L'œil les masque chez toi seulement.";
        if (Groupes.SOL.equals(i.id)) return "Sol : le sol de l'appart. Il reste visible.";
        return i.nom + " : " + i.sols + " sol(s), " + i.murs + " mural(aux). Double-clic pour renommer, glisse pour changer l'ordre.";
    }

    // ============================================================ renommer

    private void commencerRenommage(Groupes.Info i) {
        renommage = i.id;
        liste.refresh();
    }

    private void finirRenommage(boolean garder) {
        String id = renommage;
        if (id == null) return;
        String texte = null;
        for (Node n : liste.lookupAll(".calques-renommer"))
            if (n instanceof TextField && n.isVisible() && ((TextField) n).getParent() != null) texte = ((TextField) n).getText();
        renommage = null;
        if (garder && texte != null && !texte.isBlank()) Groupes.renommer(id, texte.trim());
        liste.refresh();
        if (rafraichirApres) { rafraichirApres = false; rafraichir(); }
        liste.requestFocus();
    }

    // ========================================================== menu contextuel

    private void remplirMenu(ContextMenu m, Groupes.Info i) {
        m.getItems().clear();
        List<Groupes.Info> choisis = calquesChoisis();
        if (!choisis.contains(i)) choisis = List.of(i);
        final List<Groupes.Info> sel = choisis;
        boolean multi = sel.size() >= 2;
        List<MenuItem> l = new ArrayList<>();

        if (!multi && i.decor) {
            l.add(item(i.visible ? "Masquer" : "Afficher", () -> basculerVisibilite(List.of(i)), !i.masquable));
            l.add(desactive(Groupes.MUR.equals(i.id) ? "Les murs de l'appart : toujours verrouillés." : "Le sol de l'appart : toujours verrouillé, toujours visible."));
            m.getItems().setAll(l);
            return;
        }
        if (!multi) {
            l.add(item("Ajouter la sélection à ce calque", () -> ajouterSelection(i), false));
            l.add(item("Sélectionner ses mobis", () -> { Groupes.selectionnerCalque(i.id); Groupes.montrerSelection(); }, false));
            l.add(new SeparatorMenuItem());
            l.add(item("Renommer", () -> commencerRenommage(i), !i.normal()));
            l.add(item("Dupliquer…", () -> actions.dupliquer(i), false));
            l.add(item("Déplacer…", () -> actions.deplacer(i, false), false));
            l.add(item("Pivoter…", () -> actions.pivoter(i), false));
            l.add(item("Miroir…", () -> actions.miroir(i), false));
            l.add(item("Hauteur…", () -> actions.hauteur(i), false));
            l.add(new SeparatorMenuItem());
            l.add(item("Monter", () -> Groupes.monter(i.id), !i.normal()));
            l.add(item("Descendre", () -> Groupes.descendre(i.id), !i.normal()));
            Menu avec = new Menu("Fusionner avec");
            for (Groupes.Info x : liste.getItems()) {
                if (x == null || x.decor || x.id.equals(i.id) || !i.normal()) continue;
                MenuItem it = new MenuItem(Ui.majuscule(x.nom) + " (" + x.nombre() + ")");
                it.setMnemonicParsing(false);
                it.setOnAction(e -> fusionnerDans(i, x));
                avec.getItems().add(it);
            }
            avec.setDisable(avec.getItems().isEmpty());
            l.add(avec);
        }
        boolean unVerrouillable = false, unOuvert = false, unVisible = false;
        for (Groupes.Info x : sel) {
            if (!x.decor) { unVerrouillable = true; if (!x.verrou) unOuvert = true; }
            if (x.masquable && x.visible) unVisible = true;
        }
        String combien = multi ? " les " + sel.size() + " calques" : "";
        l.add(item((unVisible ? "Masquer" : "Afficher") + combien, () -> basculerVisibilite(sel), false));
        final boolean verrouillerTous = unOuvert;
        l.add(item((unOuvert ? "Verrouiller" : "Déverrouiller") + combien, () -> verrouiller(sel, verrouillerTous), !unVerrouillable));
        String cible = Groupes.cibleFusion(ids(sel));
        String nomCible = cible == null ? null : nomDe(cible);
        l.add(item(multi ? "Fusionner les calques" + (nomCible != null ? " dans « " + nomCible + " »" : "")
                        : "Fusionner avec le calque du dessous" + (nomCible != null ? " (« " + nomCible + " »)" : ""),
                () -> fusionner(sel), cible == null));
        l.add(new SeparatorMenuItem());
        boolean supprimable = true;
        for (Groupes.Info x : sel) if (!x.normal()) supprimable = false;
        l.add(item("Supprimer" + combien + " (ramasse les mobis)", () -> supprimer(sel), !supprimable));
        m.getItems().setAll(l);
    }

    private static MenuItem item(String texte, Runnable r, boolean inactif) {
        MenuItem it = new MenuItem(Ui.majuscule(texte));
        it.setMnemonicParsing(false);
        it.setOnAction(e -> r.run());
        it.setDisable(inactif);
        return it;
    }

    private static MenuItem desactive(String texte) {
        MenuItem it = new MenuItem(Ui.majuscule(texte));
        it.setDisable(true);
        return it;
    }

    private String nomDe(String id) {
        for (Groupes.Info x : liste.getItems()) if (x.id.equals(id)) return x.nom;
        return null;
    }

    // ===================================================== actions sur les calques

    /** Les calques choisis dans la liste (Cmd/Ctrl/Maj + clic), dans l'ordre de la liste. */
    private List<Groupes.Info> calquesChoisis() {
        List<Groupes.Info> r = new ArrayList<>();
        for (Groupes.Info x : liste.getItems())
            if (liste.getSelectionModel().getSelectedItems().contains(x) && !r.contains(x)) r.add(x);
        return r;
    }

    private static List<String> ids(List<Groupes.Info> l) {
        List<String> r = new ArrayList<>();
        for (Groupes.Info x : l) r.add(x.id);
        return r;
    }

    private void verrouillerChoisis() {
        List<Groupes.Info> l = calquesChoisis();
        if (l.isEmpty()) { dire("Choisis d'abord un ou plusieurs calques."); return; }
        boolean unOuvert = false;
        for (Groupes.Info x : l) if (!x.decor && !x.verrou) unOuvert = true;
        verrouiller(l, unOuvert);
    }

    private void verrouiller(List<Groupes.Info> l, boolean v) {
        List<String> ids = new ArrayList<>();
        for (Groupes.Info x : l) if (!x.decor) ids.add(x.id);
        if (ids.isEmpty()) { dire("Mur et Sol sont toujours verrouillés."); return; }
        int n = Groupes.verrouiller(ids, v);
        if (n == 0) return;
        String quoi = n == 1 ? "« " + nomDe(ids.get(0)) + " »" : n + " calques";
        Journal.succes(quoi + (v ? (n == 1 ? " verrouillé." : " verrouillés.") : (n == 1 ? " déverrouillé." : " déverrouillés.")));
    }

    private void basculerVisibiliteChoisis() {
        List<Groupes.Info> l = calquesChoisis();
        if (l.isEmpty()) { dire("Choisis d'abord un ou plusieurs calques."); return; }
        basculerVisibilite(l);
    }

    /** Un calque visible parmi eux : tous sont masques ; sinon tous reaffiches. */
    private void basculerVisibilite(List<Groupes.Info> l) {
        boolean unVisible = false, unMasquable = false;
        for (Groupes.Info x : l) if (x.masquable) { unMasquable = true; if (x.visible) unVisible = true; }
        if (!unMasquable) { dire("Le sol ne peut pas être masqué : le jeu ne sait pas le cacher sans casser les clics."); return; }
        boolean masquer = unVisible;
        List<Groupes.Info> faire = new ArrayList<>();
        for (Groupes.Info x : l) if (x.masquable && x.visible == masquer) faire.add(x);
        int[] reste = {faire.size()};
        List<String> echecs = new ArrayList<>();
        for (Groupes.Info x : faire) {
            Groupes.Progression p = new Groupes.Progression() {
                @Override public void fin(Groupes.Resultat r) {
                    if (!r.ok && r.reussis == 0) echecs.add(r.message);
                    if (--reste[0] > 0) return;
                    if (!echecs.isEmpty()) Journal.erreur(echecs.get(0));
                    else if (faire.size() == 1) Journal.succes(r.message.startsWith("Murs") ? r.message
                            : "« " + x.nom + " » " + (masquer ? "masqué" : "affiché") + " : " + r.message);
                    else Journal.succes(faire.size() + " calques " + (masquer ? "masqués chez toi." : "réaffichés."));
                }
            };
            if (masquer) Groupes.masquer(x.id, p); else Groupes.afficher(x.id, p);
        }
    }

    private void fusionnerChoisis() {
        List<Groupes.Info> l = calquesChoisis();
        if (l.isEmpty()) { dire("Choisis d'abord un calque (Cmd + clic pour plusieurs)."); return; }
        fusionner(l);
    }

    /** « Fusionner les calques » (plusieurs -> le plus haut) ou « avec celui du dessous » (un seul). */
    private void fusionner(List<Groupes.Info> l) {
        if (actions.occupe()) { refus("Une action est déjà en cours."); return; }
        List<String> ids = ids(l);
        String refus = Groupes.refusFusion(ids);
        if (refus != null) { refus(refus); return; }
        String nomCible = nomDe(Groupes.cibleFusion(ids));
        GroupeModele.Fusion f = Groupes.fusionner(ids);
        if (f == null) { refus("Fusion impossible."); return; }
        choisirCalque(Groupes.MOBIS.equals(f.cible) ? Groupes.MOBIS : f.cible);
        if (f.sources.size() == 1) {
            String s = null;
            for (Groupes.Info x : l) if (x.id.equals(f.sources.get(0))) s = x.nom;
            Journal.succes(GroupePressePapier.messageFusion(s == null ? "?" : s, nomCible, f.mobis));
        } else Journal.succes(f.sources.size() + " calques fusionnés dans « " + nomCible + " » ("
                + GroupePressePapier.mobis(f.mobis) + ").");
    }

    /** « Fusionner avec » un calque precis (menu contextuel). */
    private void fusionnerDans(Groupes.Info source, Groupes.Info cible) {
        if (actions.occupe()) { refus("Une action est déjà en cours."); return; }
        if (source.verrou || cible.verrou) { refus("« " + (source.verrou ? source.nom : cible.nom) + " » est verrouillé : déverrouille-le pour fusionner."); return; }
        int n = Groupes.fusionner(source.id, cible.id);
        if (n < 0) { refus("Fusion impossible."); return; }
        choisirCalque(cible.id);
        Journal.succes(GroupePressePapier.messageFusion(source.nom, cible.nom, n));
    }

    /**
     * Nouveau calque : les mobis selectionnes y PASSENT (ils sortent de leur
     * ancien calque). Sans selection : un calque vide, au-dessus du calque choisi.
     */
    private void nouveauCalque() {
        Groupes.Selection s = Groupes.selection();
        if (!Salle.dansUneSalle()) { dire("Entre dans un appart."); return; }
        if (s.vide()) {
            Groupes.Info c = liste.getSelectionModel().getSelectedItem();
            String id = Groupes.creer(null, List.of(), List.of(), c != null && c.normal() ? c.id : null);
            if (id != null) { choisirCalque(id); Journal.succes("Calque « " + nomCree(id) + " » créé (vide). Clic droit, puis « Ajouter la sélection ».");  }
            return;
        }
        String refus = Groupes.refusAjout(null, s.sols, s.murs);
        if (refus != null) { refus(refus); return; }
        int n = s.nombre();
        String id = Groupes.creerDepuisSelection(null);
        if (id == null) { dire("Entre dans un appart."); return; }
        choisirCalque(id);
        Journal.succes("Calque « " + nomCree(id) + " » créé avec " + GroupePressePapier.mobis(n) + ".");
    }

    private static String nomCree(String id) {
        Groupes.Info c = Groupes.info(id);
        return c == null ? "nouveau" : c.nom;
    }

    private void ajouterSelection(Groupes.Info i) {
        Groupes.Selection s = Groupes.selection();
        if (s.vide()) { dire("Sélectionne d'abord des mobis (Option + C puis clic dans le jeu, ou une zone)."); return; }
        String refus = Groupes.refusAjout(i.id, s.sols, s.murs);
        if (refus != null) { refus(refus); return; }
        if (!Groupes.ajouter(i.id, s.sols, s.murs)) { refus("Ajout impossible."); return; }
        Groupes.viderSelection();
        Journal.succes(GroupePressePapier.mobis(s.nombre()) + " passé(s) dans « " + i.nom + " ».");
    }

    private void supprimerChoisis() {
        List<Groupes.Info> l = calquesChoisis();
        if (l.isEmpty()) { dire("Choisis d'abord un ou plusieurs calques."); return; }
        supprimer(l);
    }

    /**
     * Supprimer = ramasser leurs mobis, tout de suite (Cmd+Z les repose).
     * Confirmation courte seulement s'il y a des wired ou beaucoup de mobis.
     */
    private void supprimer(List<Groupes.Info> l) {
        if (actions.occupe()) { refus("Une action est déjà en cours."); return; }
        Groupes.Suppression s = Groupes.preparerSuppression(ids(l));
        if (s.refus != null) { refus(s.refus); return; }
        if (s.aConfirmer()) actions.confirmerSuppression(s, null);
        else actions.supprimer(s, this::dire, null);
    }

    /** Refus : dans le panneau, et au Journal (console + jeu), une seule fois. */
    private void refus(String s) {
        dire(s);
        Journal.erreur(s);
    }

    /** Active / desactive les icones du bas selon les calques choisis. */
    private void majBarre() {
        if (bVerrou == null) return;
        List<Groupes.Info> l = calquesChoisis();
        boolean rien = l.isEmpty();
        boolean unVerrouillable = false, unOuvert = false, unMasquable = false, unVisible = false, supprimable = !rien;
        for (Groupes.Info x : l) {
            if (!x.decor) { unVerrouillable = true; if (!x.verrou) unOuvert = true; }
            if (x.masquable) { unMasquable = true; if (x.visible) unVisible = true; }
            if (!x.normal()) supprimable = false;
        }
        bVerrou.setDisable(!unVerrouillable);
        bVerrou.setGraphic(Icones.trace(unOuvert || rien ? Icones.CADENAS : Icones.CADENAS_OUVERT, "icone-barre"));
        bVerrou.getTooltip().setText(unOuvert || rien ? "Verrouiller les calques choisis" : "Déverrouiller les calques choisis");
        bOeil.setDisable(!unMasquable);
        bOeil.setGraphic(Icones.trace(unVisible || rien ? Icones.OEIL_FERME : Icones.OEIL, "icone-barre"));
        bOeil.getTooltip().setText(unVisible || rien ? "Masquer les calques choisis (chez toi seulement)" : "Afficher les calques choisis");
        bFusion.setDisable(rien || Groupes.cibleFusion(ids(l)) == null);
        bSupprimer.setDisable(!supprimable);
        Groupes.Info c = cible();
        bDupliquer.setDisable(c == null || c.decor);
    }

    // ======================================================== copier / coller

    /** Ctrl+C : retient le calque vise (calque clique, ou la selection si elle est plus recente). */
    private void copierCalque() {
        if (!Salle.dansUneSalle()) { direAussiJeu("Entre dans un appart pour copier un calque."); return; }
        Groupes.Info i = cible();
        if (i == null || i.decor) { direAussiJeu("Choisis un calque dans la liste, ou sélectionne des mobis, puis Option + Maj + C."); return; }
        List<java.util.Set<Integer>> ids = Groupes.mobis(i.id);
        int n = ids.get(0).size() + ids.get(1).size();
        if (n == 0) { direAussiJeu("Le calque « " + i.nom + " » est vide : rien à copier."); return; }
        GroupePressePapier.Copie c = new GroupePressePapier.Copie(Groupes.salle(), i.id, i.nom, ids.get(0), ids.get(1));
        GroupePressePapier.retenir(c);
        RaccourcisGlobaux.collagePossible(true);
        Journal.succes(GroupePressePapier.messageCopie(i.nom, n));
        // Pour coller dans un autre appart : positions relatives et reglage des
        // wired, lus maintenant (on ne pourra plus les lire une fois parti).
        Salle.tache("calques-copie", () -> {
            try {
                WiredCollage.Copie w = WiredCollage.capturer(c.sols, () -> GroupePressePapier.copie() != c);
                c.portable = w;
                if (w == null) c.probleme = Groupes.salle() != c.salle ? "tu as changé d'appart avant la fin de la copie"
                        : "aucun mobi de sol ; les mobis muraux se collent seulement dans l'appart d'origine";
            } catch (Throwable t) {
                c.probleme = String.valueOf(t.getMessage());
                System.err.println("[Atelier] copie de calque : " + t);
            } finally { c.prete = true; }
        });
    }

    /** Ctrl+V : meme appart = copie posee sur place puis Deplacer ; autre appart = pose par le moteur de pose. */
    private void collerCalque() {
        GroupePressePapier.Copie c = GroupePressePapier.copie();
        int salle = Salle.dansUneSalle() ? Groupes.salle() : -1;
        switch (GroupePressePapier.choisir(salle)) {
            case RIEN:
                direAussiJeu("Rien à coller : copie d'abord un calque (Option + Maj + C).");
                return;
            case HORS_SALLE:
                direAussiJeu("Entre dans un appart pour coller.");
                return;
            case AUTRE_PAS_PRETE:
                direAussiJeu("La copie se prépare encore (lecture des wired). Réessaie dans un instant.");
                return;
            case AUTRE_IMPOSSIBLE:
                direAussiJeu("Ce calque ne peut pas être collé dans un autre appart"
                        + (c.probleme == null ? "" : " (" + c.probleme + ")") + ". Colle dans l'appart d'origine.");
                return;
            case AUTRE_APPART:
                collerAilleurs(c);
                return;
            default:
                actions.coller(c);
        }
    }

    /** Autre appart : le moteur de pose place la copie (clic sur la case du coin haut-gauche), puis nouveau calque. */
    private void collerAilleurs(GroupePressePapier.Copie c) {
        int murs = c.murs.size();
        // panneau seulement : WiredCollage donne la consigne dans le jeu au moment d'attendre le clic
        dire("Clique dans le jeu la case du coin haut-gauche où coller « " + c.nom + " ».");
        WiredCollage.collerCalque(c.portable, "Coller le calque « " + c.nom + " »", stage, poses -> {
            if (poses.isEmpty()) return;
            String id = Groupes.creer(null, poses, List.of());
            Platform.runLater(() -> {
                choisirCalque(id);
                Journal.succes("Calque « " + (id == null ? "nouveau" : nomCree(id)) + " » créé avec les " + GroupePressePapier.mobis(poses.size())
                        + " collés." + (murs > 0 ? " " + murs + " mobi(s) mural(aux) pas collé(s) : seulement dans l'appart d'origine." : ""));
            });
        });
    }

    /** Dans le panneau, et dans le jeu si le panneau n'a pas le focus (raccourci tape dans Habbo). */
    private void direAussiJeu(String s) {
        dire(s);
        if (!stage.isFocused()) InfoJeu.consigne(s);
    }

    // ============================================================ rafraichir

    private String erreurVue = null;

    private void rafraichir() {
        // lu hors du fil JavaFX quand c'est possible : la 1re fois decompresse le SWF
        boolean lueur = ClientModifie.saitSurligner();
        Platform.runLater(() -> {
            if (renommage != null) { rafraichirApres = true; return; }
            Groupes.Info choisi = liste.getSelectionModel().getSelectedItem();
            // les autres calques choisis (Cmd/Maj+clic) restent choisis
            List<String> autres = new ArrayList<>();
            for (Groupes.Info x : liste.getSelectionModel().getSelectedItems())
                if (x != null && x != choisi) autres.add(x.id);
            List<Groupes.Info> l = Groupes.lister();
            choixParProgramme = true;
            try {
                liste.getItems().setAll(l);
                liste.getSelectionModel().clearSelection();
                // Groupes.signaler l'a deja mise au Journal : ici seulement quand elle change
                String err = Groupes.erreur();
                if (err != null && !err.equals(erreurVue)) dire(err);
                erreurVue = err;
                String voulu = aChoisir != null ? aChoisir : choisi == null ? null : choisi.id;
                // d'abord les autres, puis le calque principal : getSelectedItem() reste lui
                if (aChoisir == null)
                    for (Groupes.Info i : l) if (autres.contains(i.id) && !i.id.equals(voulu)) liste.getSelectionModel().select(i);
                if (voulu != null)
                    for (Groupes.Info i : l) if (i.id.equals(voulu)) {
                        liste.getSelectionModel().select(i);
                        liste.scrollTo(i);
                        if (voulu.equals(aChoisir)) { aChoisir = null; preferSelection = false; }
                        break;
                    }
            } finally { choixParProgramme = false; }
            liste.setPrefHeight(Math.min(Math.max(l.size(), 3), LIGNES_MAX) * LIGNE + 6);
            Groupes.Selection s = Groupes.selection();
            // des mobis viennent d'etre selectionnes : les actions portent sur eux
            if (s.nombre() > derniereSelection) preferSelection = true;
            derniereSelection = s.nombre();
            boolean mode = Groupes.modeSelection();
            if (modeSel != null && modeSel.isSelected() != mode) modeSel.setSelected(mode);
            selectionLbl.setText(s.vide() ? (mode ? "Mode sélection : clique des mobis dans le jeu." : "Aucun mobi sélectionné.")
                    : GroupePressePapier.mobis(s.nombre()) + " sélectionné" + (s.nombre() > 1 ? "s" : "")
                      + (s.murs.isEmpty() ? "" : " (dont " + s.murs.size() + " mural" + (s.murs.size() > 1 ? "aux" : "") + ")")
                      + (preferSelection ? " : les actions portent sur eux." : "."));
            selectionBulle.setText(s.vide()
                    ? "En mode Construction : Option + C (ou l'icône flèche), puis clique des mobis dans le jeu. Option + C pour arrêter."
                    : (lueur ? "Ils sont mis en valeur dans le jeu tant qu'ils sont sélectionnés."
                             : "Ils clignotent dans le jeu tant qu'ils sont sélectionnés.")
                      + " L'icône « nouveau calque » les fait passer dans un nouveau calque.");
            majBarre();
            placer();
        });
    }

    // ============================================================= selection

    /** Le calque clique dans la liste, ou la selection si elle est plus recente ; null si rien. */
    private Groupes.Info cible() {
        Groupes.Info sel = Groupes.info(Groupes.SELECTION);
        Groupes.Info cal = liste.getSelectionModel().getSelectedItem();
        if (cal != null && Groupes.info(cal.id) == null) cal = null;
        if (preferSelection && sel != null) return sel;
        return cal != null ? cal : sel;
    }

    /** Selectionne ce calque dans la liste (seul), maintenant ou des qu'il y apparait. */
    private void choisirCalque(String id) {
        if (id == null) return;
        Runnable r = () -> {
            aChoisir = id;
            preferSelection = false;
            choixParProgramme = true;
            try {
                for (Groupes.Info i : liste.getItems())
                    if (i.id.equals(id)) { liste.getSelectionModel().clearAndSelect(liste.getItems().indexOf(i)); liste.scrollTo(i); aChoisir = null; break; }
            } finally { choixParProgramme = false; }
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    // ================================================================= zone

    private boolean selectionParZone = false;

    private void zoneChoisie() {
        if (!selectionParZone) return;
        if (Zone.choixEnCours()) {
            dire(Zone.texte());
            if (Zone.premierCoinChoisi()) InfoJeu.consigne("Clique le deuxième coin.");
            return;
        }
        if (!Zone.definie()) return;
        selectionParZone = false;
        // La zone devient directement un nouveau calque (numerote), choisi dans la liste.
        List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
        for (HFloorItem it : Zone.mobisTouches())
            if (!GroupeFantomes.estFantome(it.getId()) && !GrilleCalcul.estFictif(it.getId())) sols.add(it.getId());
        for (HWallItem w : Salle.murs()) {
            if (GroupeFantomes.estFantome(w.getId())) continue;
            int[] c = GroupeCalcul.caseMur(w.getLocation());
            if (c != null && Zone.contient(c[0], c[1])) murs.add(w.getId());
        }
        String coins = "(" + Zone.minX() + "," + Zone.minY() + ") → (" + Zone.maxX() + "," + Zone.maxY() + ")";
        dire("");
        if (sols.isEmpty() && murs.isEmpty()) {
            Journal.erreur("Aucun mobi dans la zone " + coins + " : pas de calque créé.");
            return;
        }
        String refus = Groupes.refusAjout(null, sols, murs);
        if (refus != null) { refus(refus); return; }
        String id = Groupes.creer(null, sols, murs);
        if (id == null) { dire("Entre dans un appart."); return; }
        int n = sols.size() + murs.size();
        Journal.succes("Calque « " + nomCree(id) + " » créé avec la zone " + coins + " (" + GroupePressePapier.mobis(n) + ").");
        choisirCalque(id);
    }

    /** « :w=5,7 l=25,832 l » -> {5, 7}. Logique pure. */
    static int[] caseMurale(String position) {
        if (position == null) return null;
        int i = position.indexOf(":w=");
        if (i < 0) return null;
        int fin = position.indexOf(' ', i);
        String[] xy = position.substring(i + 3, fin < 0 ? position.length() : fin).split(",");
        try { return new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())}; }
        catch (Exception e) { return null; }
    }

    // ============================================================ branchements

    /** Ce que fait « Escalier » (AtelierLauncher : ouvrir le menu Escalier / rampe). */
    public void surEscalier(Runnable r) { surEscalier = r; }

    /** Ce que fait la regle (AtelierLauncher : basculer la mesure de BarreMesure). */
    public void surMesure(Runnable r) {
        surMesure = r;
        Platform.runLater(() -> { if (regle != null) regle.setVisible(r != null); placer(); });
    }

    /** Efface l'etat quand il n'a plus change depuis quelques secondes (pas d'animation). */
    private final javafx.animation.PauseTransition effacer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(7));

    /** Etat du panneau (progression, consigne) ; vide = rien. Il s'efface tout seul. */
    private void dire(String s) {
        String t = WindowsClavier.texte(Ui.majuscule(s));
        Runnable r = () -> {
            etat.setText(t == null ? "" : t);
            effacer.stop();
            if (t != null && !t.isEmpty()) {
                effacer.setOnFinished(e -> { if (t.equals(etat.getText())) { etat.setText(""); placer(); } });
                effacer.playFromStart();
            }
            placer();
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    // ============================================================ affichage

    /** Apparait dans un appart, disparait hors d'un appart. */
    private void suivreSalle() {
        Thread t = new Thread(() -> {
            boolean vu = false;
            while (true) {
                try {
                    boolean dedans = actif && Salle.dansUneSalle();
                    if (dedans != vu) {
                        vu = dedans;
                        Platform.runLater(() -> {
                            if (dedans) { Groupes.installer(); rafraichir(); stage.show(); placer(); }
                            else { actions.fermer(); Groupes.annulerApercu(); stage.hide(); }
                        });
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(800); } catch (InterruptedException e) { return; }
            }
        }, "atelier-calques-panneau");
        t.setDaemon(true);
        t.start();
    }

    private void basculer() {
        reduit = !reduit;
        prefs.putBoolean("calques.reduit", reduit);
        appliquerReduit();
        placer();
    }

    private void appliquerReduit() {
        corps.setVisible(!reduit);
        corps.setManaged(!reduit);
        if (reduit) cadre.getStyleClass().add("reduite");
        else cadre.getStyleClass().remove("reduite");
    }

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(this::placer);
    }

    /** En haut a gauche du jeu ; la hauteur suit le contenu (jamais de defilement du panneau). */
    private void placer() {
        double x, y, max;
        if (habbo != null) {
            x = habbo[0] + GAUCHE;
            y = habbo[1] + HAUT;
            max = habbo[1] + habbo[3] - y - 60;
        } else {
            javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
            x = e.getMinX() + GAUCHE;
            y = e.getMinY() + HAUT - TITRE_MAC;
            max = e.getMaxY() - y - 20;
        }
        stage.setX(x);
        stage.setY(y);
        double voulu = reduit ? 35 : cadre.prefHeight(LARGEUR) + 3;
        stage.setHeight(Math.max(35, Math.min(voulu, Math.max(200, max))));
    }
}

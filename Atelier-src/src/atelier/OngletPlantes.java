package atelier;

import atelier.PlanteSuivi.Plante;
import atelier.PlanteVue.Bilan;
import atelier.PlanteVue.Filtre;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Monster Plants : les plantes monstres de la salle ouverte.
 *
 *  - En haut, un resume (combien a soigner, a recolter, mortes, adultes) et
 *    les actions de masse : « Soigner les N », « Récolter les N »... Chacune
 *    montre d'abord un apercu chiffre, puis agit apres « Confirmer ».
 *  - Le tableau : nom, rarete, proprietaire. Tri par colonne,
 *    recherche, filtres rapides. La selection et le defilement tiennent
 *    pendant les mises a jour. L'etat (a soigner, mortes...) sert aux
 *    comptages et aux filtres, sans colonne.
 *  - Actions sur la selection (multi-selection) : boutons sous le tableau et
 *    clic droit. Reproduction : deux plantes choisies, ou tous les couples.
 *
 * La liste vient des paquets recus passivement (PlanteSuivi). Aucun envoi
 * vers une plante hors d'un geste de l'utilisatrice : un bouton (lancer())
 * ou un clic de souris sur une ligne (cliquerDansLeJeu()), les deux sous
 * PlanteSuivi.enActionExplicite. Les fiches (vie) se lisent en
 * arriere-plan, une a la fois, sans rien ouvrir dans le jeu : la vie sert a
 * compter les plantes a soigner.
 *
 * Clic sur une ligne : la plante a la fleche de selection du jeu
 * (MiseEnValeur, jetons « p<index> ») ET elle est cliquee dans le jeu
 * (LookTo + GetPetInfo, la fiche du jeu s'ouvre). Un envoi par clic, rien
 * si on reclique la meme ligne dans la seconde ; une selection faite par le
 * programme (rafraichissement) n'envoie rien. Le survol d'une action de
 * masse montre les plantes concernees.
 */
public class OngletPlantes {

    /** Pause entre deux envois d'une rafale (au moins 150 ms). */
    private static final long PAUSE_MS = 700;

    // Liste maitresse (ordre d'urgence), puis filtre, puis tri des colonnes.
    private final ObservableList<Plante> lignes = FXCollections.observableArrayList();
    private final FilteredList<Plante> filtrees = new FilteredList<>(lignes, p -> true);
    private final SortedList<Plante> triees = new SortedList<>(filtrees);
    private TableView<Plante> table;

    private Label resume, details, etat, progresTxt, apercu;
    private ProgressBar progres;
    private HBox progression;
    private VBox confirmation;
    private Button confirmer, stop;
    private TextField monNomTxt, recherche;
    private HBox ligneMonNom;
    private Label reproLbl;

    private Button bSoigner, bRecolter, bComposter, bReproduire;                // masse
    private Button sSoigner, sRecolter, sComposter, sReproduire;        // selection
    private MenuItem mSoigner, mRecolter, mComposter, mReproduire;      // clic droit
    private final Map<Filtre, ToggleButton> filtres = new EnumMap<>(Filtre.class);
    private Filtre filtre = Filtre.TOUTES;

    /** Ids des plantes choisies, ecrits sur le fil JavaFX, lus par MiseEnValeur (autre fil). */
    private volatile List<Integer> idsChoisis = List.of();

    /** Garde du clic sur une ligne (logique pure, testee). */
    private final PlanteVue.Clic clic = new PlanteVue.Clic();
    /** Les clics partent ici, un a la fois, 150 ms au moins entre deux. */
    private final java.util.concurrent.ExecutorService fileClics =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-plantes-clic");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean occupe = new AtomicBoolean(false);
    private volatile boolean arret = false;
    private final AtomicBoolean majPrevue = new AtomicBoolean(false);
    private int tic = 0;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab plantes = new Tab("Monster Plants", defiler(volet()));
        plantes.setClosable(false);
        volets.getTabs().add(plantes);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        Tab t = new Tab("Monster Plants", racine);
        t.setClosable(false);
        return t;
    }

    private static ScrollPane defiler(Pane p) {
        ScrollPane sp = new ScrollPane(p);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    /** Bulle rapide (150 ms) sur un controle. */
    private static <T extends Control> T bulle(T c, String texte) {
        Tooltip t = new Tooltip(WindowsClavier.texte(texte));
        t.setShowDelay(Duration.millis(150));
        t.setWrapText(true);
        t.setMaxWidth(280);
        c.setTooltip(t);
        return c;
    }

    private static Button bouton(String texte, String aide) {
        Button b = bulle(new Button(texte), aide);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private Pane volet() {
        etat = Ui.etat();

        // --- resume et actions de masse
        resume = Ui.valeur("Aucune plante dans cette salle");
        resume.setWrapText(true);
        details = new Label();
        details.getStyleClass().add("etat-ligne");
        details.setWrapText(true);

        bSoigner = bouton("Soigner", "Le soin du jour (respect d'animal) pour les plantes dont le bien-être baisse. "
                + "Les plus pressées d'abord. Nombre de soins limité par jour.");
        bSoigner.getStyleClass().add("primaire");
        bSoigner.setOnAction(e -> proposerSoins(cibles(PlanteVue::aSoigner)));
        bRecolter = bouton("Récolter", "Tes plantes adultes : tu reçois leur récompense, la plante disparaît.");
        bRecolter.setOnAction(e -> proposerRecolte(cibles(this::aRecolter)));
        bComposter = bouton("Composter", "Tes plantes mortes : elles disparaissent. C'est définitif.");
        bComposter.setOnAction(e -> proposerCompost(cibles(this::aComposter)));
        bReproduire = bouton("Reproduire", "Associe tes plantes adultes deux par deux, des plus rares aux moins rares. "
                + "Une graine par couple, dans ton inventaire.");
        bReproduire.setOnAction(e -> proposerReproductionTout());
        MiseEnValeur.auSurvol(bSoigner, () -> jetons(cibles(PlanteVue::aSoigner)));
        MiseEnValeur.auSurvol(bRecolter, () -> jetons(cibles(this::aRecolter)));
        MiseEnValeur.auSurvol(bComposter, () -> jetons(cibles(this::aComposter)));
        MiseEnValeur.auSurvol(bReproduire, () -> {
            List<Plante> l = new ArrayList<>();
            for (Plante[] c : couplesPossibles()) { l.add(c[0]); l.add(c[1]); }
            return jetons(l);
        });
        FlowPane masse = new FlowPane(6, 6, bSoigner, bRecolter, bComposter, bReproduire);

        monNomTxt = new TextField();
        monNomTxt.setPromptText("Ton nom Habbo");
        monNomTxt.setPrefColumnCount(12);
        monNomTxt.textProperty().addListener((o, a, b) -> prevoirMaj());
        Label monNomLbl = new Label("Qui es-tu ?");
        ligneMonNom = new HBox(6, monNomLbl, monNomTxt);
        ligneMonNom.setAlignment(Pos.CENTER_LEFT);
        bulle(monNomTxt, "Ton nom n'est pas encore détecté. Écris-le pour récolter ou composter tes plantes.");

        // --- confirmation (apercu chiffre) et progression
        apercu = new Label();
        apercu.setWrapText(true);
        Button annuler = new Button("Annuler");
        annuler.setOnAction(e -> cacherConfirmation());
        confirmer = new Button("Confirmer");
        confirmer.getStyleClass().add("primaire");
        confirmation = new VBox(6, apercu, Ui.ligne(annuler, confirmer));
        montrer(confirmation, false);

        progres = new ProgressBar(0);
        progres.setPrefWidth(110);
        progresTxt = new Label();
        progresTxt.getStyleClass().add("etat-ligne");
        progresTxt.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progresTxt, Priority.ALWAYS);
        stop = bouton("Arrêter", "Interrompt après la plante en cours.");
        stop.setOnAction(e -> { arret = true; progresTxt.setText("Arrêt demandé…"); });
        progression = new HBox(8, progres, progresTxt, stop);
        progression.setAlignment(Pos.CENTER_LEFT);
        montrer(progression, false);

        VBox blocResume = Ui.bloc("Résumé", resume, details, masse, ligneMonNom, confirmation, progression);

        // --- recherche et filtres
        recherche = new TextField();
        recherche.setPromptText("Chercher un nom");
        recherche.setPrefColumnCount(10);
        recherche.textProperty().addListener((o, a, b) -> appliquerFiltre());
        ToggleGroup groupe = new ToggleGroup();
        HBox boutonsFiltre = new HBox(4);
        boutonsFiltre.setAlignment(Pos.CENTER_LEFT);
        for (Filtre f : Filtre.values()) {
            ToggleButton b = new ToggleButton(f.texte);
            b.setToggleGroup(groupe);
            b.setUserData(f);
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setFocusTraversable(false);
            filtres.put(f, b);
            boutonsFiltre.getChildren().add(b);
        }
        filtres.get(Filtre.TOUTES).setSelected(true);
        groupe.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }   // toujours un filtre choisi
            filtre = (Filtre) b.getUserData();
            appliquerFiltre();
        });
        FlowPane barre = Ui.ligne(recherche, boutonsFiltre);
        barre.setHgap(8);

        // --- tableau
        table = new TableView<>(triees);
        triees.comparatorProperty().bind(table.comparatorProperty());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setStyle("-fx-font-size: 11px;");
        Label vide = new Label("Aucune plante à montrer.");
        vide.getStyleClass().add("aide-vide");
        table.setPlaceholder(vide);
        table.getColumns().add(colonne("Nom", 110, PlanteVue.PAR_NOM, (c, p) -> c.setText(p.nom)));
        TableColumn<Plante, Plante> rar = colonne("Rareté", 62, PlanteVue.PAR_RARETE,
                (c, p) -> c.setText(PlanteVue.rarete(p.rarete)));
        table.getColumns().add(rar);
        table.getColumns().add(colonne("Propriétaire", 90, PlanteVue.PAR_PROPRIO, (c, p) -> {
            String n = p.proprioNom == null || p.proprioNom.isEmpty() ? "?" : p.proprioNom;
            c.setText(aMoi(p) ? n + " (toi)" : n);
        }));
        table.setRowFactory(tv -> {
            TableRow<Plante> row = new TableRow<>() {
                @Override protected void updateItem(Plante p, boolean vide) {
                    super.updateItem(p, vide);
                    setOpacity(p != null && !vide && p.morte ? 0.55 : 1);
                }
            };
            // Seul un vrai clic de souris passe ici (pas une selection par le programme).
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> surClicLigne(row, e));
            return row;
        });
        table.setContextMenu(menuClicDroit());
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Plante>) ch -> surSelection());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> surSelection());   // la derniere cliquee d'abord
        table.setPrefHeight(280);
        table.setMinHeight(160);
        VBox.setVgrow(table, Priority.ALWAYS);

        // --- actions sur la selection
        sSoigner = bouton("Soigner", "Le soin du jour pour les plantes choisies.");
        sSoigner.setOnAction(e -> agirSelection("soigner"));
        sRecolter = bouton("Récolter", "Tes plantes adultes choisies.");
        sRecolter.setOnAction(e -> agirSelection("recolter"));
        sComposter = bouton("Composter", "Tes plantes mortes choisies (définitif).");
        sComposter.setOnAction(e -> agirSelection("composter"));
        sReproduire = bouton("Reproduire ces 2", "Deux plantes adultes, à toi ou dont la reproduction est permise. "
                + "Tu reçois une graine.");
        sReproduire.setOnAction(e -> agirSelection("reproduire"));
        FlowPane actionsSel = new FlowPane(6, 6, sSoigner, sRecolter, sComposter, sReproduire);
        reproLbl = new Label("Reproduction : fais-en une à la main une fois dans le jeu, l'Atelier apprend comment faire.");
        reproLbl.getStyleClass().add("etat-ligne");
        reproLbl.setWrapText(true);
        montrer(reproLbl, false);

        Label aide = Ui.aide("Clique une ligne : la plante est choisie dans le jeu, comme si tu cliquais dessus. "
                + "Cmd/Ctrl + clic ou Maj + clic pour en choisir plusieurs. Clic droit : les actions.");
        VBox blocListe = Ui.bloc("Plantes de la salle", barre, table, actionsSel, reproLbl, aide);
        VBox.setVgrow(blocListe, Priority.ALWAYS);

        VBox v = new VBox(12, blocResume, blocListe, etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        PlanteSuivi.ecouter(this::prevoirMaj);
        MiseEnValeur.fournir("plantes", this::plantesEnValeur);
        // fenetre ouverte : rien de choisi, toutes les plantes sont mises en valeur
        MiseEnValeur.aLOuverture("plantes", () -> javafx.application.Platform.runLater(() -> table.getSelectionModel().clearSelection()));
        PlanteSuivi.installer();

        demarrerFichesAuto();
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            try { majResume(); } catch (Throwable ignored) { }
            if (++tic % 5 == 0) { try { rafraichirLignes(true); } catch (Throwable ignored) { } }
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        rafraichir();
        surSelection();
        return v;
    }

    private static void montrer(Node n, boolean v) { n.setVisible(v); n.setManaged(v); }

    /** Une colonne dont la valeur est la plante elle-meme : le tri suit les valeurs vivantes. */
    private static TableColumn<Plante, Plante> colonne(String titre, double larg, Comparator<Plante> tri,
                                                        BiConsumer<TableCell<Plante, Plante>, Plante> remplir) {
        TableColumn<Plante, Plante> c = new TableColumn<>(titre);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setComparator(tri);
        c.setPrefWidth(larg);
        c.setMaxWidth(larg * 30);
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Plante p, boolean vide) {
                super.updateItem(p, vide);
                setGraphic(null);
                setText(null);
                if (vide || p == null) return;
                try { remplir.accept(this, p); } catch (Throwable t) { setGraphic(null); setText("?"); }
            }
        });
        return c;
    }

    private ContextMenu menuClicDroit() {
        mSoigner = new MenuItem("Soigner");
        mSoigner.setOnAction(e -> agirSelection("soigner"));
        mRecolter = new MenuItem("Récolter");
        mRecolter.setOnAction(e -> agirSelection("recolter"));
        mComposter = new MenuItem("Composter…");
        mComposter.setOnAction(e -> agirSelection("composter"));
        mReproduire = new MenuItem("Reproduire ces 2");
        mReproduire.setOnAction(e -> agirSelection("reproduire"));
        ContextMenu m = new ContextMenu(mSoigner, mRecolter, mComposter, new SeparatorMenuItem(), mReproduire);
        m.setOnShowing(e -> majBoutonsSelection());
        return m;
    }

    // ------------------------------------------------------ selection

    private List<Plante> selection() {
        return table == null ? List.of() : new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    /** Fil JavaFX : retient les ids choisis (plante focalisee d'abord) pour la fleche du jeu. */
    private void surSelection() {
        List<Integer> ids = new ArrayList<>();
        Plante f = table.getSelectionModel().getSelectedItem();
        if (f != null) ids.add(f.id);
        for (Plante p : table.getSelectionModel().getSelectedItems())
            if (p != null && !ids.contains(p.id)) ids.add(p.id);
        idsChoisis = List.copyOf(ids);
        majBoutonsSelection();
    }

    /**
     * Pour MiseEnValeur (fil de la mise en valeur) : ne lit aucun controle JavaFX.
     * Lignes choisies : ces plantes ; rien de choisi : toutes les plantes de la
     * salle. Jetons « a » : contour comme les mobis, plusieurs a la fois.
     */
    private Collection<String> plantesEnValeur() {
        List<String> r = new ArrayList<>();
        List<Integer> ids = idsChoisis;
        if (ids.isEmpty()) {
            for (Plante p : PlanteSuivi.plantes()) if (p.index >= 0) r.add("a" + p.index);
            return r;
        }
        for (int id : ids) {
            Plante p = PlanteSuivi.plante(id);
            if (p != null && p.index >= 0) r.add("a" + p.index);
        }
        return r;
    }

    /**
     * Clic de souris sur une ligne (fil JavaFX) : la plante est cliquee dans
     * le jeu. Clic gauche seulement, ligne restee choisie (un Cmd/Ctrl + clic
     * qui la retire n'envoie rien), garde anti-repetition de PlanteVue.Clic.
     */
    private void surClicLigne(TableRow<Plante> row, MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || row.isEmpty()) return;
        Plante p = row.getItem();
        if (p == null || occupe.get()) return;          // un travail en cours : seulement la fleche
        if (Salle.gp() == null || !Salle.dansUneSalle() || !PlanteSuivi.branche()) return;
        if (!clic.accepter(p.id, true, row.isSelected(), System.currentTimeMillis())) return;
        int id = p.id;
        fileClics.execute(() -> {
            Salle.espacer();                            // clics enchaines : rythme commun des envois
            try { PlanteSuivi.enActionExplicite(() -> PlanteSuivi.cliquer(id)); }
            catch (Throwable t) { Journal.debug("clic sur la plante " + id + " : " + t); }
        });
    }

    private static List<String> jetons(List<Plante> l) {
        List<String> r = new ArrayList<>();
        for (Plante p : l) if (p.index >= 0) r.add("a" + p.index);
        return r;
    }

    private void majBoutonsSelection() {
        List<Plante> sel = selection();
        boolean libre = !occupe.get();
        int vivantes = 0, recolte = 0, compost = 0;
        for (Plante p : sel) {
            if (!p.morte) vivantes++;
            if (aRecolter(p)) recolte++;
            if (aComposter(p)) compost++;
        }
        boolean couple = Reproduction.appris() && coupleImpossible(sel) == null;
        sSoigner.setDisable(!libre || vivantes == 0);
        sRecolter.setDisable(!libre || recolte == 0);
        sComposter.setDisable(!libre || compost == 0);
        sReproduire.setDisable(!libre || !couple);
        if (mSoigner != null) {
            mSoigner.setDisable(sSoigner.isDisable());
            mRecolter.setDisable(sRecolter.isDisable());
            mComposter.setDisable(sComposter.isDisable());
            mReproduire.setDisable(sReproduire.isDisable());
        }
    }

    // ------------------------------------------------------- mises a jour

    private void prevoirMaj() {
        if (majPrevue.compareAndSet(false, true))
            Platform.runLater(() -> { majPrevue.set(false); rafraichir(); });
    }

    private void rafraichir() {
        rafraichirLignes(false);
        majResume();
    }

    /**
     * Met la liste a jour SANS la remplacer : on retire les plantes parties,
     * on ajoute les nouvelles ; la selection et le defilement restent. Les
     * valeurs (vie...) sont relues par table.refresh().
     * @param retrier remet aussi l'ordre d'urgence et reapplique le filtre
     *                (les etats changent avec le temps).
     */
    private void rafraichirLignes(boolean retrier) {
        garderSelection(() -> {
            Map<Integer, Plante> actuelles = new HashMap<>();
            for (Plante p : PlanteSuivi.plantes()) actuelles.put(p.id, p);
            boolean change = lignes.removeIf(p -> actuelles.get(p.id) != p);
            Set<Integer> deja = new HashSet<>();
            for (Plante p : lignes) deja.add(p.id);
            List<Plante> nouvelles = new ArrayList<>();
            for (Plante p : actuelles.values()) if (!deja.contains(p.id)) nouvelles.add(p);
            if (!nouvelles.isEmpty()) {
                nouvelles.sort(PlanteVue.URGENCE);
                lignes.addAll(nouvelles);
                change = true;
            }
            if (change || retrier) {
                List<Plante> ordre = new ArrayList<>(lignes);
                ordre.sort(PlanteVue.URGENCE);
                if (!ordre.equals(lignes)) FXCollections.sort(lignes, PlanteVue.URGENCE);
                if (filtreAChange()) appliquerFiltre();
            }
        });
        table.refresh();
    }

    /** Une plante entre ou sort du filtre (son etat a change) ? */
    private boolean filtreAChange() {
        String r = recherche.getText();
        Set<Plante> dedans = Collections.newSetFromMap(new IdentityHashMap<>());
        dedans.addAll(filtrees);
        for (Plante p : lignes) if (PlanteVue.garder(p, filtre, r, aMoi(p)) != dedans.contains(p)) return true;
        return false;
    }

    private void appliquerFiltre() {
        Filtre f = filtre;
        String r = recherche.getText();
        garderSelection(() -> filtrees.setPredicate(p -> PlanteVue.garder(p, f, r, aMoi(p))));
        majResume();
    }

    private boolean gardee = false;

    /** Fait r puis rechoisit les memes plantes (par id). */
    private void garderSelection(Runnable r) {
        if (gardee) { r.run(); return; }          // deja dans une mise a jour gardee
        List<Integer> ids = new ArrayList<>(idsChoisis);
        gardee = true;
        try { r.run(); } finally { gardee = false; }
        if (ids.isEmpty()) return;
        TableView.TableViewSelectionModel<Plante> sm = table.getSelectionModel();
        Set<Integer> voulus = new HashSet<>(ids);
        boolean pareil = sm.getSelectedItems().size() == voulus.size();
        for (Plante p : sm.getSelectedItems()) if (p == null || !voulus.contains(p.id)) { pareil = false; break; }
        if (pareil) return;
        sm.clearSelection();
        int focus = -1;
        for (int i = 0; i < table.getItems().size(); i++) {
            Plante p = table.getItems().get(i);
            if (voulus.contains(p.id)) { sm.select(i); if (p.id == ids.get(0)) focus = i; }
        }
        if (focus >= 0) { sm.select(focus); table.getFocusModel().focus(focus); }
    }

    private void majResume() {
        List<Plante> toutes = new ArrayList<>(lignes);
        Bilan b = PlanteVue.bilan(toutes, this::aMoi);
        boolean libre = !occupe.get();

        if (Salle.gp() == null) resume.setText("L'Atelier n'est pas encore prêt.");
        else if (!Salle.dansUneSalle()) resume.setText("Entre dans un appart pour voir ses plantes.");
        else if (!PlanteSuivi.branche()) resume.setText("L'écoute des plantes s'installe…");
        else if (!PlanteSuivi.listeRecue && b.total == 0) resume.setText("Ressors et reviens dans l'appart pour voir ses plantes.");
        else resume.setText(PlanteVue.resume(b));

        // Ligne discrete : soins du jour, qui tu es.
        List<String> d = new ArrayList<>();
        int s = PlanteSuivi.soinsRestants;
        d.add(s < 0 ? "Soins du jour : ?" : "Soins du jour : " + s);
        String moi = moi();
        if (moi != null) d.add("Toi : " + moi);
        details.setText(String.join(" · ", d));
        montrer(ligneMonNom, PlanteSuivi.monId <= 0 && PlanteSuivi.monNom == null);

        int couples = couplesPossibles().size();
        bSoigner.setText(PlanteVue.libelle("Soigner", b.aSoigner));
        bRecolter.setText(PlanteVue.libelle("Récolter", b.aRecolter));
        bComposter.setText(PlanteVue.libelle("Composter", b.aComposter));
        bReproduire.setText(couples == 0 ? "Reproduire" : couples == 1 ? "Reproduire 1 couple" : "Reproduire " + couples + " couples");
        bSoigner.setDisable(!libre || b.aSoigner == 0);
        bRecolter.setDisable(!libre || b.aRecolter == 0);
        bComposter.setDisable(!libre || b.aComposter == 0);
        bReproduire.setDisable(!libre || couples == 0 || !Reproduction.appris());
        montrer(reproLbl, !Reproduction.appris());

        int[] n = {b.total, b.aSoigner, b.aRecolter, b.mortes, b.miennes};
        for (Filtre f : Filtre.values()) {
            int k = n[f.ordinal()];
            filtres.get(f).setText(f == Filtre.TOUTES || k == 0 ? f.texte : f.texte + " " + k);
        }
        majBoutonsSelection();
    }

    // ---------------------------------------------------------- qui suis-je

    /** Nom saisi : lu sur le fil JavaFX seulement, puis passe aux travaux. */
    private volatile String nomSaisi = "";

    private boolean aMoi(Plante p) {
        if (Platform.isFxApplicationThread() && monNomTxt != null) nomSaisi = monNomTxt.getText();
        return PlanteSuivi.estAMoi(p, nomSaisi);
    }

    private boolean aRecolter(Plante p) { return PlanteVue.aRecolter(p, aMoi(p)); }
    private boolean aComposter(Plante p) { return PlanteVue.aComposter(p, aMoi(p)); }

    private String moi() {
        if (PlanteSuivi.monNom != null) return PlanteSuivi.monNom;
        String s = nomSaisi;
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Les plantes de la salle qui passent ce test, les plus urgentes d'abord. */
    private List<Plante> cibles(Predicate<Plante> test) {
        List<Plante> l = new ArrayList<>();
        for (Plante p : PlanteSuivi.plantes()) if (test.test(p)) l.add(p);
        l.sort(PlanteVue.URGENCE);
        return l;
    }

    // ----------------------------------------------- apercu puis action

    /** Montre l'apercu chiffre ; l'action ne part qu'apres « Confirmer ». */
    private void proposer(String texte, String libelle, Runnable action) {
        apercu.setText(Ui.accorder(texte));
        confirmer.setText(libelle);
        confirmer.setOnAction(e -> { cacherConfirmation(); action.run(); });
        montrer(confirmation, true);
    }

    private void cacherConfirmation() { montrer(confirmation, false); }

    private static List<Integer> ids(List<Plante> l) {
        List<Integer> r = new ArrayList<>();
        for (Plante p : l) r.add(p.id);
        return r;
    }

    private void proposerSoins(List<Plante> l) {
        if (l.isEmpty()) { dire("Aucune plante à soigner."); return; }
        int s = PlanteSuivi.soinsRestants;
        String reste = s < 0 ? "" : s == 0 ? " Le jeu dit qu'il ne te reste aucun soin aujourd'hui."
                : " Il te reste " + s + " soin(s) aujourd'hui.";
        List<Integer> ids = ids(l);
        proposer("Soigner " + l.size() + " plante(s) : " + PlanteVue.noms(l, 4) + "." + reste,
                PlanteVue.libelle("Soigner", l.size()), () -> lancer("plantes-soigner", () -> soigner(ids)));
    }

    private void proposerRecolte(List<Plante> l) {
        if (l.isEmpty()) { dire("Aucune de tes plantes n'est prête à récolter."); return; }
        List<Integer> ids = ids(l);
        proposer("Récolter " + l.size() + " plante(s) : " + PlanteVue.noms(l, 4) + ". Elles disparaissent.",
                PlanteVue.libelle("Récolter", l.size()), () -> lancer("plantes-recolter", () -> recolter(ids)));
    }

    private void proposerCompost(List<Plante> l) {
        if (l.isEmpty()) { dire("Aucune de tes plantes n'est morte ici."); return; }
        List<Integer> ids = ids(l);
        proposer("Composter " + l.size() + " plante(s) morte(s) : " + PlanteVue.noms(l, 4) + ". C'est définitif.",
                PlanteVue.libelle("Composter", l.size()), () -> lancer("plantes-composter", () -> composter(ids)));
    }

    private void proposerReproductionTout() {
        if (!Reproduction.appris()) { dire("Fais d'abord une reproduction à la main dans le jeu : l'Atelier apprend comment faire."); return; }
        List<Plante[]> couples = couplesPossibles();
        if (couples.isEmpty()) { dire("Aucun couple possible : il faut deux plantes adultes qui peuvent se reproduire."); return; }
        StringBuilder sb = new StringBuilder(couples.size() + " couple(s), des plus rares aux moins rares :");
        int i = 0;
        for (Plante[] c : couples) {
            if (++i > 6) { sb.append("\n…"); break; }
            sb.append("\n• ").append(c[0].nom).append(" + ").append(c[1].nom)
              .append(" (rareté ").append(c[0].rarete).append(" et ").append(c[1].rarete).append(')');
        }
        List<int[]> l = new ArrayList<>();
        for (Plante[] c : couples) l.add(new int[]{c[0].id, c[1].id});
        proposer(sb.toString(), "Reproduire", () -> lancer("plantes-reproduction", () -> reproduire(l)));
    }

    /** Boutons sous le tableau et clic droit. Une seule plante : tout de suite ; plusieurs : apercu d'abord. */
    private void agirSelection(String quoi) {
        List<Plante> sel = selection();
        if (sel.isEmpty()) { dire("Choisis une plante dans le tableau."); return; }
        sel.sort(PlanteVue.URGENCE);
        switch (quoi) {
            case "soigner": {
                List<Plante> l = new ArrayList<>();
                for (Plante p : sel) if (!p.morte) l.add(p);
                if (l.size() == 1) { int id = l.get(0).id; lancer("plantes-soigner", () -> soigner(List.of(id))); }
                else proposerSoins(l);
                break;
            }
            case "recolter": {
                List<Plante> l = new ArrayList<>();
                for (Plante p : sel) if (aRecolter(p)) l.add(p);
                if (l.size() == 1) { int id = l.get(0).id; lancer("plantes-recolter", () -> recolter(List.of(id))); }
                else proposerRecolte(l);
                break;
            }
            case "composter": {
                List<Plante> l = new ArrayList<>();
                for (Plante p : sel) if (aComposter(p)) l.add(p);
                proposerCompost(l);                       // definitif : toujours un apercu
                break;
            }
            case "reproduire": {
                String non = coupleImpossible(sel);
                if (non != null) { dire(non); return; }
                if (!Reproduction.appris()) { dire("Fais d'abord une reproduction à la main dans le jeu : l'Atelier apprend comment faire."); return; }
                List<int[]> l = List.<int[]>of(new int[]{sel.get(0).id, sel.get(1).id});
                lancer("plantes-reproduction", () -> reproduire(l));
                break;
            }
            default: break;
        }
    }

    private String coupleImpossible(List<Plante> sel) {
        return PlanteVue.coupleImpossible(sel, p -> Reproduction.disponible(p, nomSaisi));
    }

    private List<Plante[]> couplesPossibles() {
        List<Plante> dispo = new ArrayList<>();
        for (Plante p : PlanteSuivi.plantes()) if (Reproduction.disponible(p, nomSaisi)) dispo.add(p);
        return Reproduction.couples(dispo);
    }

    // -------------------------------------------------------------- travaux

    private void dire(String s) {
        if (Platform.isFxApplicationThread()) etat.setText(s);
        else Platform.runLater(() -> etat.setText(s));
    }

    /** Resultat d'une action : une seule fois, dans le jeu (Journal). */
    private void succes(String s) { Ui.succes(etat, s); }
    private void erreur(String s) { Ui.erreur(etat, s); }

    private void avancer(int i, int n, String texte) {
        Platform.runLater(() -> {
            progres.setProgress(n <= 0 ? ProgressBar.INDETERMINATE_PROGRESS : i / (double) n);
            progresTxt.setText(Ui.accorder(texte + (n > 1 ? " (" + i + "/" + n + ")" : "")));
        });
    }

    /** Un seul travail reseau a la fois, hors fil JavaFX. Seul endroit ou les envois vers les plantes sont permis. */
    private void lancer(String nom, Runnable r) {
        if (!occupe.compareAndSet(false, true)) { dire("Un travail est déjà en cours : « Arrêter » pour l'interrompre."); return; }
        arret = false;
        cacherConfirmation();
        progres.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progresTxt.setText("");
        montrer(progression, true);
        majResume();
        Salle.tache(nom, () -> {
            try { PlanteSuivi.enActionExplicite(r); }
            catch (Throwable t) { Ui.erreur(etat, "Erreur pendant le travail sur les plantes", t); }
            finally {
                occupe.set(false);
                Platform.runLater(() -> { montrer(progression, false); rafraichir(); });
            }
        });
    }

    private boolean pret() {
        if (Salle.gp() == null) { erreur("Impossible : l'Atelier n'est pas encore prêt."); return false; }
        if (!Salle.dansUneSalle()) { erreur("Impossible : entre d'abord dans un appart."); return false; }
        if (!PlanteSuivi.branche()) { dire("L'écoute des plantes s'installe : encore un instant."); return false; }
        return true;
    }

    /** Envoie GetPetInfo et attend la fiche (1,5 s au plus). */
    private boolean lireInfo(Plante p) {
        Salle.espacer();                                // souvent juste apres un autre envoi (soin, reproduction, autre fiche)
        long t0 = System.currentTimeMillis();
        if (!PlanteSuivi.demanderInfo(p.id)) return false;
        return attendreFiche(p.id, t0);
    }

    private static boolean attendreFiche(int id, long t0) {
        for (int i = 0; i < 30; i++) {
            Salle.sommeil(50);
            Plante q = PlanteSuivi.plante(id);
            if (q != null && q.infoRecue >= t0) return true;
        }
        return false;
    }

    /** Resultat d'un soin : fait, refuse par le serveur, ou sans reponse claire. */
    private enum Soin { FAIT, REFUSE, INCERTAIN }

    /**
     * Traite une plante et verifie l'effet : notification de respect, ou refus
     * explicite du serveur (PetRespectFailed), ou fiche relue avec plus de
     * respect / plus de temps de vie.
     */
    private Soin traiter(Plante p) {
        int avantR = p.respect;
        long avantV = p.finVie;
        long t0 = System.currentTimeMillis();
        if (!PlanteSuivi.traiter(p.id)) return Soin.INCERTAIN;
        for (int i = 0; i < 30; i++) {
            Salle.sommeil(50);
            if (PlanteSuivi.dernierRespect >= t0) { Salle.sommeil(200); lireInfo(p); return Soin.FAIT; }
            if (PlanteSuivi.dernierRefusSoin >= t0) return Soin.REFUSE;
        }
        Salle.sommeil(250);
        if (lireInfo(p) && ((avantR >= 0 && p.respect > avantR)
                || (p.finVie > 0 && (avantV <= 0 || p.finVie > avantV + 60_000)))) return Soin.FAIT;
        return PlanteSuivi.dernierRefusSoin >= t0 ? Soin.REFUSE : Soin.INCERTAIN;
    }

    /**
     * Soigne ces plantes, une par une. On s'arrete quand le serveur refuse
     * trois fois de suite : plus de soins aujourd'hui.
     */
    private void soigner(List<Integer> ids) {
        if (!pret()) return;
        int faits = 0, refus = 0, incertains = 0, refusDeSuite = 0, i = 0;
        String fin = null, seul = null;
        for (int id : ids) {
            if (arret) { fin = "arrêté"; break; }
            Plante p = PlanteSuivi.plante(id);
            i++;
            if (p == null || p.morte) continue;
            seul = p.nom;
            avancer(i, ids.size(), "Soin de " + p.nom);
            Soin r = traiter(p);
            if (r == Soin.FAIT) { faits++; refusDeSuite = 0; }
            else if (r == Soin.REFUSE) {
                refus++;
                if (++refusDeSuite >= 3) { fin = "le jeu refuse : plus de soins aujourd'hui"; break; }
            } else { incertains++; refusDeSuite = 0; }
            if (i < ids.size()) Salle.sommeil(PAUSE_MS);
        }
        try { PlanteSuivi.demanderProfil(); } catch (Throwable ignored) { }   // soins restants a jour
        prevoirMaj();
        if (ids.size() == 1 && seul != null) {
            if (faits == 1) succes(seul + " soignée.");
            else if (refus == 1) erreur(seul + " : soin refusé par le jeu (déjà soignée, ou plus de soins aujourd'hui).");
            else erreur(seul + " : soin envoyé, effet non confirmé.");
            return;
        }
        String bilan = faits + " plante(s) soignée(s)"
                + (incertains > 0 ? ", " + incertains + " sans confirmation" : "")
                + (refus > 0 ? ", " + refus + " refusée(s)" : "")
                + (fin != null ? " — " + fin + "." : ".");
        if (faits == 0) erreur(bilan); else succes(bilan);
    }

    private void recolter(List<Integer> ids) {
        if (!pret()) return;
        int n = 0, i = 0;
        String seul = null;
        for (int id : ids) {
            if (arret) break;
            Plante p = PlanteSuivi.plante(id);
            i++;
            if (p == null || p.morte) continue;
            seul = p.nom;
            avancer(i, ids.size(), "Récolte de " + p.nom);
            if (PlanteSuivi.recolter(p.id)) n++;
            if (i < ids.size()) Salle.sommeil(1000);
        }
        prevoirMaj();
        if (n == 0) erreur("Échec : aucune récolte envoyée.");
        else if (ids.size() == 1) succes("Récolte de " + seul + " demandée.");
        else succes(n + " récolte(s) demandée(s)" + (arret ? " (arrêté)." : "."));
    }

    private void composter(List<Integer> ids) {
        if (!pret()) return;
        int n = 0, i = 0;
        for (int id : ids) {
            if (arret) break;
            Plante p = PlanteSuivi.plante(id);
            i++;
            if (p == null || !p.morte) continue;
            avancer(i, ids.size(), "Compostage de " + p.nom);
            if (PlanteSuivi.composter(p.id)) n++;
            if (i < ids.size()) Salle.sommeil(1000);
        }
        prevoirMaj();
        if (n == 0 && !arret) erreur("Échec : aucun compostage envoyé.");
        else succes(n + " plante(s) compostée(s)" + (arret ? " (arrêté)." : "."));
    }

    private void reproduire(List<int[]> couples) {
        if (!pret()) return;
        int ok = 0, echecs = 0, i = 0;
        for (int[] c : couples) {
            if (arret) break;
            Plante a = PlanteSuivi.plante(c[0]), b = PlanteSuivi.plante(c[1]);
            i++;
            if (a == null || b == null) { echecs++; continue; }
            avancer(i, couples.size(), "Reproduction de " + a.nom + " + " + b.nom);
            if (Reproduction.reproduire(a.id, b.id)) ok++; else echecs++;
            lireInfo(a);
            lireInfo(b);
            if (i < couples.size()) Salle.sommeil(1000);
        }
        prevoirMaj();
        String bilan = ok + " reproduction(s) réussie(s)" + (echecs > 0 ? ", " + echecs + " sans réponse du jeu" : "")
                + (arret ? " (arrêté)." : ".") + (ok > 0 ? " Les graines sont dans ton inventaire." : "");
        if (ok == 0) erreur(bilan); else succes(bilan);
    }

    // ----------------------------------------------------- liste auto

    /**
     * Les fiches des plantes (vie) se lisent toutes seules, sans
     * rien ouvrir dans le jeu (PlanteSuivi.demanderInfoSilencieuse) : celles
     * jamais lues ou lues il y a plus de 10 min. Une a la fois : on attend la
     * reponse avant la suivante, puis 150 ms. La vie sert a compter les
     * plantes a soigner (resume, bouton Soigner, filtre).
     *
     * La liste des plantes, elle, n'est jamais redemandee (pas de
     * GetHeightMap : le jeu rechargerait toute la salle) ; si l'ecoute s'est
     * installee apres l'entree dans l'appart, le resume demande d'y revenir.
     */
    private final Map<Integer, Long> demandees = new java.util.concurrent.ConcurrentHashMap<>();

    private void demarrerFichesAuto() {
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(2000);
                try {
                    if (!Salle.installeeDepuis(3000) || occupe.get()) continue;
                    List<Plante> tri = PlanteSuivi.plantes();
                    tri.sort(PlanteVue.URGENCE);
                    List<Plante> l = PlanteSuivi.aLire(tri, System.currentTimeMillis());
                    int n = 0, recues = 0;
                    long maintenant = System.currentTimeMillis();
                    for (Plante p : l) {
                        if (occupe.get() || n >= 40) break;
                        Long d = demandees.get(p.id);
                        if (d != null && maintenant - d < 60_000) continue;     // deja demandee il y a peu
                        demandees.put(p.id, maintenant);
                        n++;
                        Salle.espacer();
                        long t0 = System.currentTimeMillis();
                        PlanteSuivi.demanderInfoSilencieuse(p.id);
                        if (attendreFiche(p.id, t0)) recues++;
                    }
                    if (n > 0) {
                        Journal.debug("Fiches auto : " + n + " demandée(s), " + recues + " reçue(s) ; "
                                + PlanteSuivi.plantes().size() + " plante(s) suivie(s).");
                        prevoirMaj();
                    }
                } catch (Throwable ignored) { }
            }
        }, "atelier-plantes-fiches");
        t.setDaemon(true);
        t.start();
    }
}

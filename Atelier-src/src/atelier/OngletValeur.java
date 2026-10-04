package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Patrimoine : la valeur de tes mobis (inventaire et apparts).
 *
 * Les prix se chargent en tache de fond des le demarrage (PrixChargement) :
 * fichier d'abord (affichage immediat), puis habbofurni.xyz s'il a plus de
 * 24 h, puis la place du marche du jeu pour les mobis absents du site.
 *
 * Le tableau est calcule hors du fil FX, au plus une fois toutes les 300 ms ;
 * les lignes sont mises a jour SUR PLACE (memes objets) : la selection et le
 * defilement restent. Il n'est retrie qu'a la fin du chargement, ou quand tu
 * changes le tri, le filtre ou la recherche.
 *
 * Cliquer une ou plusieurs lignes met en valeur dans le jeu (contour) les
 * mobis de ces types poses dans l'appart (MiseEnValeur, cle « patrimoine »).
 *
 * Ce n'est qu'une estimation : le prix moyen des ventes recentes, pas ce
 * qu'un acheteur paiera demain. Les mobis non vendables (BC, non
 * echangeables) ne comptent pas.
 */
public class OngletValeur {

    /** Une ligne du tableau : un mobi different. Mise a jour sur place, fil FX. */
    public static final class Ligne {
        final String cle;
        final boolean mur;
        final int typeId;
        String classe;
        int revision;
        boolean vendable;
        private final SimpleStringProperty nom = new SimpleStringProperty();
        private final SimpleIntegerProperty quantite = new SimpleIntegerProperty();
        private final SimpleIntegerProperty prix = new SimpleIntegerProperty(-1);
        private final SimpleLongProperty total = new SimpleLongProperty(-1);
        private final SimpleObjectProperty<PrixCalcul.Source> source = new SimpleObjectProperty<>(PrixCalcul.Source.AUCUNE);

        Ligne(PrixCalcul.Ligne c) { cle = c.cle; mur = c.mur; typeId = c.typeId; maj(c); }

        void maj(PrixCalcul.Ligne c) {
            classe = c.classe;
            revision = c.revision;
            vendable = c.vendable;
            if (!Objects.equals(nom.get(), c.nom)) nom.set(c.nom);
            if (quantite.get() != c.quantite) quantite.set(c.quantite);
            if (prix.get() != c.prix) prix.set(c.prix);
            if (total.get() != c.total()) total.set(c.total());
            if (source.get() != c.source) source.set(c.source);
        }
        public String getNom() { return nom.get(); }
        public int getQuantite() { return quantite.get(); }
        public int getPrix() { return prix.get(); }
        public long getTotal() { return total.get(); }
    }

    /** Une ligne du tableau des apparts : un appart visite ou tu as des mobis. */
    public static final class Appart {
        final int id;
        private final SimpleStringProperty nom, vu;
        private final SimpleIntegerProperty mobis;
        private final SimpleLongProperty valeur;
        Appart(int id, String nom, String vu, int mobis, long valeur) {
            this.id = id;
            this.nom = new SimpleStringProperty(nom);
            this.vu = new SimpleStringProperty(vu);
            this.mobis = new SimpleIntegerProperty(mobis);
            this.valeur = new SimpleLongProperty(valeur);
        }
    }

    /** 50 credits = 1 lingot. */
    static String enLingots(long credits) { return PrixTexte.lingots(credits); }

    /** « 12 345 crédits · 246,9 lingots ». */
    static String credits(long c) { return PrixTexte.credits(c); }

    private static final Collator COLLATOR = Collator.getInstance(Locale.FRANCE);
    static { COLLATOR.setStrength(Collator.SECONDARY); }

    // ------------------------------------------------------------- interface

    private Label valeur, resume, detail, etatPrix, progressionTexte, compteVisible;
    private ProgressBar barre;
    private final Tooltip bulleProgression = new Tooltip();
    private HBox ligneProgression, ligneDate;
    private Button actualiser, arreter;
    private TextField recherche;
    private ComboBox<PrixCalcul.Filtre> filtre;
    private TableView<Ligne> table;
    private TableView<Appart> tableApparts;
    private final ObservableList<Ligne> visibles = FXCollections.observableArrayList();
    private final ObservableList<Appart> apparts = FXCollections.observableArrayList();
    /** Toutes les lignes, par cle Marche ; fil FX seulement. */
    private final Map<String, Ligne> toutes = new LinkedHashMap<>();
    private boolean inventaireRecu = false, etaitEnCours = false, premierCalcul = true;

    /** Types choisis dans le tableau, recopies sur le fil FX (lus par MiseEnValeur sur un autre fil). */
    private volatile Set<Integer> choisisSols = Set.of(), choisisMurs = Set.of();

    public Tab construire() {
        // ---- en haut : la valeur totale
        valeur = new Label("—");
        valeur.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        resume = new Label("Prix en cours de chargement.");
        resume.setWrapText(true);
        detail = new Label();
        detail.setWrapText(true);
        detail.getStyleClass().add("etat-ligne");

        // ---- la ligne du chargement : barre pendant, date et bouton apres (meme hauteur : rien ne saute)
        barre = new ProgressBar(0);
        barre.setPrefWidth(90);
        barre.setMinWidth(60);
        progressionTexte = new Label();
        progressionTexte.getStyleClass().add("etat-ligne");
        progressionTexte.setMinWidth(0);
        progressionTexte.setPrefWidth(0);           // prend la place qui reste, jamais celle du bouton
        progressionTexte.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressionTexte, Priority.ALWAYS);
        arreter = new Button("Arrêter");
        arreter.setOnAction(e -> PrixChargement.arreter());
        arreter.setMinWidth(Region.USE_PREF_SIZE);
        arreter.setMinWidth(76);
        ligneProgression = new HBox(8, barre, progressionTexte, arreter);
        Tooltip.install(ligneProgression, bulleProgression);
        ligneProgression.setAlignment(Pos.CENTER_LEFT);

        etatPrix = new Label();
        etatPrix.getStyleClass().add("etat-ligne");
        etatPrix.setWrapText(true);
        etatPrix.setMinWidth(0);
        HBox.setHgrow(etatPrix, Priority.ALWAYS);
        etatPrix.setMaxWidth(Double.MAX_VALUE);
        actualiser = new Button("Actualiser les prix");
        actualiser.setTooltip(new Tooltip("Relit tout de suite habbofurni.xyz et redemande au jeu les prix manquants."));
        actualiser.setOnAction(e -> { PrixChargement.actualiser(); majProgression(); });
        actualiser.setMinWidth(Region.USE_PREF_SIZE);
        ligneDate = new HBox(8, etatPrix, actualiser);
        ligneDate.setAlignment(Pos.CENTER_LEFT);
        StackPane chargement = new StackPane(ligneDate, ligneProgression);
        chargement.setAlignment(Pos.CENTER_LEFT);
        ligneProgression.setVisible(false);

        // ---- recherche et filtre
        recherche = new TextField();
        recherche.setPromptText("Chercher un mobi");
        recherche.setPrefColumnCount(10);
        HBox.setHgrow(recherche, Priority.ALWAYS);
        recherche.textProperty().addListener((o, a, b) -> refiltrer());
        filtre = new ComboBox<>(FXCollections.observableArrayList(PrixCalcul.Filtre.values()));
        filtre.setValue(PrixCalcul.Filtre.TOUS);
        filtre.valueProperty().addListener((o, a, b) -> refiltrer());
        compteVisible = new Label();
        compteVisible.getStyleClass().add("etat-ligne");
        HBox outils = new HBox(8, recherche, filtre);
        outils.setAlignment(Pos.CENTER_LEFT);

        construireTable();
        construireApparts();

        VBox v = new VBox(12,
                Ui.bloc("Valeur estimée", valeur, resume, detail, chargement,
                        Ui.aide("Prix de habbofurni.xyz pour l'hôtel FR (moyenne du marché, ou "
                                + "estimation du site pour les rares). Pour les mobis absents du site, "
                                + "prix moyen de la place du marché du jeu. Les prix se chargent tout seuls "
                                + "au lancement et sont gardés 24 h. C'est une estimation : le prix réel "
                                + "dépend des acheteurs. Les mobis BC et non échangeables ne se vendent pas.")),
                Ui.bloc("Dans mon inventaire", outils, compteVisible, table,
                        Ui.aide("Clique une ligne (Maj ou Cmd/Ctrl pour plusieurs) : ces mobis s'entourent "
                                + "dans l'appart. Double-clic sur un prix pour fixer le tien ; vide = prix "
                                + "automatique. Clique un titre de colonne pour trier.")),
                Ui.bloc("Dans les apparts", tableApparts,
                        Ui.aide("Tes mobis posés dans tes apparts et chez les autres. Un appart est "
                                + "compté quand tu y entres, tel qu'il était à ton dernier passage. "
                                + "Clic droit : ne plus le compter.")));
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // Le chargement part tout de suite, fenetre ouverte ou non.
        PrixChargement.ecouter(this::rafraichir);
        PrixChargement.demarrer();
        rafraichir();
        // La barre suit le chargement deux fois par seconde (lecture de quelques compteurs).
        Timeline suivi = new Timeline(new KeyFrame(Duration.millis(500), e -> majProgression()));
        suivi.setCycleCount(Timeline.INDEFINITE);
        suivi.play();
        majProgression();

        Tab t = new Tab("Valeur de mes mobis", sp);
        t.setClosable(false);
        return t;
    }

    private void construireTable() {
        table = new TableView<>(visibles);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Inventaire pas encore reçu."));
        table.setPrefHeight(340);
        table.setFixedCellSize(28);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Mise en valeur : copie des types choisis sur le fil FX ; le fournisseur
        // (appele hors du fil FX) ne lit que cette copie.
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Ligne>) c -> {
            Set<Integer> sols = new HashSet<>(), murs = new HashSet<>();
            for (Ligne l : table.getSelectionModel().getSelectedItems()) {
                if (l == null) continue;
                (l.mur ? murs : sols).add(l.typeId);
            }
            choisisSols = Collections.unmodifiableSet(sols);
            choisisMurs = Collections.unmodifiableSet(murs);
        });
        MiseEnValeur.fournir("patrimoine", () -> {
            Set<Integer> s = choisisSols, m = choisisMurs;
            if (s.isEmpty() && m.isEmpty()) return List.of();
            List<String> r = new ArrayList<>();
            if (!s.isEmpty()) r.addAll(MiseEnValeur.solsOu(it -> s.contains(it.getTypeId())));
            if (!m.isEmpty()) r.addAll(MiseEnValeur.mursOu(w -> m.contains(w.getTypeId())));
            return r;
        });

        TableColumn<Ligne, Ligne> cIcone = new TableColumn<>("");
        cIcone.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        cIcone.setCellFactory(x -> new CelluleIcone());
        cIcone.setSortable(false);
        cIcone.setResizable(false);
        cIcone.setPrefWidth(30);
        cIcone.setMinWidth(30);
        cIcone.setMaxWidth(30);

        TableColumn<Ligne, String> cNom = new TableColumn<>("Mobi");
        cNom.setCellValueFactory(c -> c.getValue().nom);
        cNom.setComparator(COLLATOR::compare);
        cNom.setCellFactory(x -> new TableCell<>() {
            private final Tooltip bulle = new Tooltip();
            @Override protected void updateItem(String s, boolean vide) {
                super.updateItem(s, vide);
                Ligne l = vide ? null : getTableRow() == null ? null : getTableRow().getItem();
                setText(vide ? null : s);
                setStyle(l != null && !l.vendable ? "-fx-opacity: 0.55;" : null);
                if (l == null) { setTooltip(null); return; }
                bulle.setText(s + (l.classe != null ? "\n" + l.classe : ""));
                setTooltip(bulle);
            }
        });
        cNom.setPrefWidth(150);
        cNom.setMinWidth(80);

        TableColumn<Ligne, Number> cQte = nombres("Qté", l -> l.quantite, 42);
        cQte.setMinWidth(38);
        TableColumn<Ligne, Number> cPrix = new TableColumn<>("Prix");
        cPrix.setCellValueFactory(c -> c.getValue().prix);
        cPrix.setPrefWidth(66);
        cPrix.setMinWidth(58);
        // Le prix se modifie d'un double-clic ; vide = revenir au prix automatique.
        table.setEditable(true);
        cPrix.setEditable(true);
        cPrix.setCellFactory(x -> new CellulePrix());
        cPrix.setOnEditCommit(e -> {
            Ligne l = e.getRowValue();
            int v = e.getNewValue() == null ? -1 : e.getNewValue().intValue();
            Salle.tache("prix-perso", () -> PrixPerso.fixer(l.mur, l.typeId, v));
        });
        TableColumn<Ligne, Number> cTotal = nombres("Total", l -> l.total, 74);
        cTotal.setMinWidth(64);
        TableColumn<Ligne, PrixCalcul.Source> cSource = new TableColumn<>("Source");
        cSource.setCellValueFactory(c -> c.getValue().source);
        cSource.setComparator(Comparator.comparing(s -> s == null ? 99 : s.ordinal()));
        cSource.setCellFactory(x -> new TableCell<>() {
            private final Tooltip bulle = new Tooltip();
            @Override protected void updateItem(PrixCalcul.Source s, boolean vide) {
                super.updateItem(s, vide);
                if (vide || s == null) { setText(null); setTooltip(null); return; }
                setText(court(s));
                bulle.setText(s.texte);
                setTooltip(bulle);
                setStyle("-fx-opacity: 0.75;");
            }
        });
        cSource.setPrefWidth(58);
        cSource.setMinWidth(50);
        // Fenetre etroite : la source passe dans la bulle du prix, le nom garde sa place.
        cSource.visibleProperty().bind(table.widthProperty().greaterThanOrEqualTo(430));

        table.getColumns().addAll(List.of(cIcone, cNom, cQte, cPrix, cTotal, cSource));
        cTotal.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(cTotal);

        MenuItem auto = new MenuItem("Remettre le prix automatique");
        auto.setOnAction(e -> {
            List<Ligne> choix = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            Salle.tache("prix-perso", () -> { for (Ligne l : choix) PrixPerso.fixer(l.mur, l.typeId, -1); });
        });
        table.setContextMenu(new ContextMenu(auto));
    }

    private static String court(PrixCalcul.Source s) {
        switch (s) {
            case PERSO: return "✎ Toi";
            case SITE: return "Site";
            case SITE_ESTIMATION: return "Estim.";
            case JEU: return "Jeu";
            case NON_VENDABLE: return "Non vend.";
            default: return "—";
        }
    }

    private void construireApparts() {
        tableApparts = new TableView<>(apparts);
        tableApparts.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableApparts.setPlaceholder(new Label("Entre dans un appart où tu as posé des mobis."));
        tableApparts.setPrefHeight(180);
        TableColumn<Appart, String> aNom = new TableColumn<>("Appart");
        aNom.setCellValueFactory(c -> c.getValue().nom);
        aNom.setComparator(COLLATOR::compare);
        aNom.setPrefWidth(140);
        TableColumn<Appart, Number> aMobis = new TableColumn<>("Mobis");
        aMobis.setCellValueFactory(c -> c.getValue().mobis);
        aMobis.setCellFactory(x -> celluleNombre());
        aMobis.setPrefWidth(50);
        TableColumn<Appart, Number> aValeur = new TableColumn<>("Valeur");
        aValeur.setCellValueFactory(c -> c.getValue().valeur);
        aValeur.setCellFactory(x -> celluleNombre());
        aValeur.setPrefWidth(80);
        TableColumn<Appart, String> aVu = new TableColumn<>("Vu le");
        aVu.setCellValueFactory(c -> c.getValue().vu);
        aVu.setPrefWidth(50);
        tableApparts.getColumns().addAll(List.of(aNom, aMobis, aValeur, aVu));
        aValeur.setSortType(TableColumn.SortType.DESCENDING);
        tableApparts.getSortOrder().add(aValeur);
        MenuItem oublier = new MenuItem("Ne plus compter cet appart");
        oublier.setOnAction(e -> {
            Appart a = tableApparts.getSelectionModel().getSelectedItem();
            if (a != null) Salle.tache("patrimoine-oublier", () -> Patrimoine.oublier(a.id));
        });
        tableApparts.setContextMenu(new ContextMenu(oublier));
    }

    private interface Prop { javafx.beans.value.ObservableValue<Number> de(Ligne l); }

    /** Colonne de nombres : alignee a droite, « — » quand le prix manque. */
    private static TableColumn<Ligne, Number> nombres(String titre, Prop p, double largeur) {
        TableColumn<Ligne, Number> c = new TableColumn<>(titre);
        c.setCellValueFactory(x -> p.de(x.getValue()));
        c.setCellFactory(x -> celluleNombre());
        c.setPrefWidth(largeur);
        return c;
    }

    private static <S> TableCell<S, Number> celluleNombre() {
        TableCell<S, Number> cell = new TableCell<>() {
            @Override protected void updateItem(Number n, boolean vide) {
                super.updateItem(n, vide);
                setText(vide || n == null ? null : n.longValue() < 0 ? "—" : PrixTexte.nombre(n.longValue()));
            }
        };
        cell.setAlignment(Pos.CENTER_RIGHT);
        return cell;
    }

    /** Icone du mobi (chargee en fond par JavaFX, seulement pour les lignes visibles). */
    private static final class CelluleIcone extends TableCell<Ligne, Ligne> {
        private final ImageView vue = new ImageView();
        CelluleIcone() {
            vue.setFitWidth(PrixVignettes.TAILLE);
            vue.setFitHeight(PrixVignettes.TAILLE);
            vue.setPreserveRatio(true);
            setAlignment(Pos.CENTER);
        }
        @Override protected void updateItem(Ligne l, boolean vide) {
            super.updateItem(l, vide);
            Image i = vide || l == null ? null : PrixVignettes.icone(l.classe, l.revision);
            vue.setImage(i);
            setGraphic(i == null ? null : vue);
            setText(null);
        }
    }

    /**
     * Cellule de prix : affiche « 1 234 » (« ✎ 1 234 » si tu l'as fixe toi-meme),
     * et se modifie d'un double-clic. Entree valide, Echap annule ; un champ
     * vide remet le prix automatique.
     */
    private final class CellulePrix extends TableCell<Ligne, Number> {
        private TextField champ;
        private final Tooltip bulle = new Tooltip();

        CellulePrix() { setAlignment(Pos.CENTER_RIGHT); }

        @Override public void startEdit() {
            Ligne l = getTableRow() == null ? null : getTableRow().getItem();
            if (l == null || !l.vendable) return;
            super.startEdit();
            Number n = getItem();
            champ = new TextField(n == null || n.intValue() < 0 ? "" : String.valueOf(n.intValue()));
            champ.setOnAction(e -> {
                String t = champ.getText().replaceAll("[^0-9]", "");
                try { commitEdit(t.isEmpty() ? -1 : Integer.parseInt(t)); }
                catch (NumberFormatException x) { cancelEdit(); }
            });
            champ.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) cancelEdit(); });
            setText(null);
            setGraphic(champ);
            champ.requestFocus();
            champ.selectAll();
        }

        @Override public void cancelEdit() { super.cancelEdit(); afficherValeur(getItem(), false); }

        @Override protected void updateItem(Number n, boolean vide) {
            super.updateItem(n, vide);
            if (isEditing()) return;
            afficherValeur(n, vide);
        }

        private void afficherValeur(Number n, boolean vide) {
            setGraphic(null);
            Ligne l = getTableRow() == null ? null : getTableRow().getItem();
            if (vide || n == null || l == null) { setText(null); setTooltip(null); return; }
            boolean perso = l.source.get() == PrixCalcul.Source.PERSO;
            setText(n.intValue() < 0 ? "—" : (perso ? "✎ " : "") + PrixTexte.nombre(n.intValue()));
            bulle.setText("Source : " + l.source.get().texte + (l.vendable ? "\nDouble-clic pour changer le prix" : ""));
            setTooltip(bulle);
        }
    }

    // ------------------------------------------------------------- calcul (hors fil FX)

    private static final ScheduledExecutorService calcul = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "atelier-valeur");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean planifie = new AtomicBoolean(false);

    /** Redemande un calcul, au plus un toutes les 300 ms ; n'importe quel fil. */
    private void rafraichir() {
        if (!planifie.compareAndSet(false, true)) return;
        calcul.schedule(() -> {
            planifie.set(false);
            try {
                Instantane i = calculer();
                if (i != null) Platform.runLater(() -> appliquer(i));
            } catch (Throwable t) {
                Journal.debug("valeur : calcul impossible : " + t);
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    /** Ce qu'on affiche, calcule d'un coup hors du fil FX. */
    private static final class Instantane {
        boolean inventaireRecu;
        final List<PrixCalcul.Ligne> lignes = new ArrayList<>();
        PrixCalcul.Totaux totaux;
        final List<Appart> apparts = new ArrayList<>();
        long totalApparts;
        int mobisApparts;
    }

    private static PrixCalcul.Sources sources() {
        return new PrixCalcul.Sources() {
            @Override public Integer perso(boolean mur, int typeId) { return PrixPerso.prix(mur, typeId); }
            @Override public PrixSite.Prix site(String classe) { return PrixSite.prix(classe); }
            @Override public Integer jeu(boolean mur, int typeId) {
                Marche.Prix p = Marche.prix(mur, typeId);
                return p == null || p.moyen <= 0 ? null : p.moyen;
            }
        };
    }

    private static Instantane calculer() {
        GPresets gp = AtelierLauncher.moteur();
        Instantane r = new Instantane();
        PrixCalcul.Sources s = sources();

        // --- inventaire
        List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
        r.inventaireRecu = inv != null;
        if (inv != null) {
            Map<String, PrixCalcul.Compte> g = new LinkedHashMap<>();
            for (HInventoryItem it : inv) {
                boolean mur = it.getType() == HProductType.WallItem;
                if (!mur && it.getType() != HProductType.FloorItem) continue;
                PrixCalcul.compter(g, mur, it.getTypeId(), it.isSellable() && it.getSecondsToExpiration() <= 0);
            }
            for (Map.Entry<String, PrixCalcul.Compte> e : g.entrySet()) {
                PrixCalcul.Compte c = e.getValue();
                String classe = PrixTexte.classe(gp, c.mur, c.typeId);
                r.lignes.add(new PrixCalcul.Ligne(e.getKey(), PrixTexte.nom(gp, c.mur, c.typeId, classe), classe,
                        c.mur, c.typeId, c.quantite, c.vendable,
                        PrixCalcul.prix(c.mur, c.typeId, classe, c.vendable, s),
                        PrixTexte.revision(gp, c.mur, classe)));
            }
        }
        r.totaux = PrixCalcul.totaux(r.lignes);

        // --- apparts
        for (Patrimoine.Appart a : Patrimoine.apparts()) {
            long v = 0;
            for (Map.Entry<String, Integer> e : a.mobis.entrySet()) {
                boolean mur = e.getKey().startsWith("2:");
                int typeId;
                try { typeId = Integer.parseInt(e.getKey().substring(2)); } catch (NumberFormatException x) { continue; }
                int p = PrixCalcul.prix(mur, typeId, PrixTexte.classe(gp, mur, typeId), true, s).prix;
                if (p > 0) v += (long) p * e.getValue();
            }
            r.totalApparts += v;
            r.mobisApparts += a.total();
            String moi = Patrimoine.pseudo();
            String nom = (a.nom == null ? "Appart " + a.id : a.nom)
                    + (a.proprietaire != null && !a.proprietaire.equalsIgnoreCase(String.valueOf(moi))
                       ? "  (chez " + a.proprietaire + ")" : "");
            r.apparts.add(new Appart(a.id, nom, a.vuLe == null ? "" : jourMois(a.vuLe), a.total(), v));
        }
        return r;
    }

    // ------------------------------------------------------------- affichage (fil FX)

    private void appliquer(Instantane i) {
        // --- lignes : sur place
        boolean structure = premierCalcul;
        Set<String> vues = new HashSet<>();
        for (PrixCalcul.Ligne c : i.lignes) {
            vues.add(c.cle);
            Ligne l = toutes.get(c.cle);
            if (l == null) { toutes.put(c.cle, new Ligne(c)); structure = true; }
            else l.maj(c);
        }
        if (toutes.keySet().retainAll(vues)) structure = true;
        inventaireRecu = i.inventaireRecu;

        boolean enCours = PrixChargement.etat().enCours;
        // Retri seulement quand la liste change, au premier calcul, ou a la fin du chargement.
        boolean trier = structure || (etaitEnCours && !enCours);
        etaitEnCours = enCours;
        premierCalcul = false;
        refiltrer(trier);

        // --- apparts : petite liste, on garde l'appart choisi
        Appart avant = tableApparts.getSelectionModel().getSelectedItem();
        apparts.setAll(i.apparts);
        tableApparts.sort();
        if (avant != null)
            for (Appart a : apparts) if (a.id == avant.id) { tableApparts.getSelectionModel().select(a); break; }

        // --- totaux
        PrixCalcul.Totaux t = i.totaux;
        long total = t.valeur + i.totalApparts;
        valeur.setText(PrixSite.nombre() == 0 && t.avecPrix == 0 && enCours ? "…" : credits(total));
        if (!i.inventaireRecu) {
            resume.setText("Inventaire pas encore reçu : seuls tes apparts comptent pour l'instant.");
        } else {
            StringBuilder b = new StringBuilder(PrixTexte.nombre(t.mobis) + " mobis");
            if (t.sansPrix > 0) b.append(" · ").append(PrixTexte.nombre(t.sansPrix)).append(" sans prix");
            if (t.nonVendables > 0) b.append(" · ").append(PrixTexte.nombre(t.nonVendables)).append(" non vendables");
            resume.setText(b.toString());
        }
        detail.setText("Inventaire " + PrixTexte.nombre(t.valeur)
                + (i.apparts.isEmpty() ? "" : " · apparts " + PrixTexte.nombre(i.totalApparts)
                    + " (" + PrixTexte.nombre(i.mobisApparts) + " mobis, " + i.apparts.size() + " appart"
                    + (i.apparts.size() > 1 ? "s" : "") + ")")
                + (t.parJeu > 0 ? " · " + PrixTexte.nombre(t.parJeu) + " au prix du marché du jeu" : ""));
        majProgression();
    }

    /** Applique filtre et recherche ; ne touche a la liste que si son contenu change. */
    private void refiltrer() { refiltrer(true); }

    private void refiltrer(boolean trier) {
        if (table == null) return;
        PrixCalcul.Filtre f = filtre.getValue() == null ? PrixCalcul.Filtre.TOUS : filtre.getValue();
        String q = PrixCalcul.plat(recherche.getText());
        List<Ligne> garder = new ArrayList<>();
        for (Ligne l : toutes.values())
            if (f.garde(l.source.get(), l.prix.get()) && PrixCalcul.correspond(q, l.nom.get(), l.classe))
                garder.add(l);
        boolean change = garder.size() != visibles.size() || !new HashSet<>(visibles).containsAll(garder);
        if (change || trier) {
            List<Ligne> choisies = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (change) visibles.setAll(garder);
            table.sort();
            // la selection suit les memes lignes (tri ou nouvelle liste)
            List<Ligne> apres = table.getSelectionModel().getSelectedItems();
            if (!choisies.isEmpty() && !(apres.size() == choisies.size() && apres.containsAll(choisies))) {
                table.getSelectionModel().clearSelection();
                for (Ligne l : choisies) {
                    int idx = visibles.indexOf(l);
                    if (idx >= 0) table.getSelectionModel().select(idx);
                }
            }
        }
        boolean filtrage = f != PrixCalcul.Filtre.TOUS || !q.isEmpty();
        compteVisible.setText(filtrage ? PrixTexte.nombre(visibles.size()) + " sur " + PrixTexte.nombre(toutes.size())
                + " mobis différents" : "");
        compteVisible.setVisible(filtrage);
        compteVisible.setManaged(filtrage);
        table.setPlaceholder(new Label(!inventaireRecu ? "Inventaire pas encore reçu."
                : toutes.isEmpty() ? "Aucun mobi dans ton inventaire." : "Aucun mobi ne correspond."));
    }

    /** La ligne de chargement : barre pendant, date et bouton apres. Fil FX. */
    private void majProgression() {
        if (barre == null) return;
        PrixChargement.Etat e = PrixChargement.etat();
        ligneProgression.setVisible(e.enCours);
        ligneDate.setVisible(!e.enCours);
        actualiser.setDisable(e.enCours);
        if (e.enCours) {
            double p = e.total > 0 ? Math.min(1, (double) e.fait / e.total) : 0;
            if (Math.abs(barre.getProgress() - p) > 1e-4) barre.setProgress(p);
            String t = e.total > 0
                    ? e.source + " : " + PrixTexte.nombre(e.fait) + " / " + PrixTexte.nombre(e.total)
                    : e.source + "…";
            if (!t.equals(progressionTexte.getText())) {
                progressionTexte.setText(t);
                // le detail (pages du site / mobis demandes au jeu) dans la bulle
                bulleProgression.setText(t + (e.unite.isEmpty() ? "" : " " + e.unite)
                        + "\nLes prix déjà connus restent affichés pendant le chargement.");
            }
            if (etaitEnCours != e.enCours) rafraichir();
            return;
        }
        if (etaitEnCours) rafraichir();   // fin du chargement : dernier calcul et tri
        long d = PrixChargement.derniereMaj();
        String echec = PrixSite.etat();
        String t = d > 0 ? "Prix du " + PrixTexte.dateHeure(d) : "Prix pas encore lus sur habbofurni.xyz.";
        if (echec != null && !echec.isBlank() && !echec.endsWith("…")) t = echec;
        if (!t.equals(etatPrix.getText())) etatPrix.setText(t);
    }

    private static String jourMois(String iso) {
        return iso.length() >= 10 ? iso.substring(8, 10) + "/" + iso.substring(5, 7) : iso;
    }

    // ------------------------------------------------------------- pour les autres volets

    /** Prix unitaire d'un mobi, pour les autres volets (Salle) ; -1 si inconnu. */
    public static int prixUnitaire(GPresets gp, boolean mur, int typeId) {
        try {
            return PrixCalcul.prix(mur, typeId, PrixTexte.classe(gp, mur, typeId), true, sources()).prix;
        } catch (Throwable t) { return -1; }
    }
}

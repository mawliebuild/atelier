package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.text.NumberFormat;
import java.util.*;

/**
 * Valeur de l'inventaire : chaque mobi avec son prix, et le total si on
 * vendait tout.
 *
 * Prix d'abord d'apres habbofurni.xyz (voir PrixSite : prix de l'hotel FR,
 * y compris l'estimation du site pour les rares peu vendus). Pour les mobis
 * absents du site, la place du marche du jeu (voir Marche), un mobi a la fois,
 * une demande toutes les 0,5 s, gardee 24 h.
 *
 * Ce n'est qu'une estimation : le prix moyen des ventes recentes, pas ce
 * qu'un acheteur paiera demain. Les mobis non vendables (BC, non
 * echangeables) ne comptent pas.
 */
public class OngletValeur {

    public static final class Ligne {
        private final SimpleStringProperty nom;
        private final SimpleIntegerProperty quantite, prix, total;
        final boolean mur, vendable;
        final int typeId;
        Ligne(String n, int q, int p, boolean mur, int typeId, boolean vendable) {
            nom = new SimpleStringProperty(n);
            quantite = new SimpleIntegerProperty(q);
            prix = new SimpleIntegerProperty(p);
            total = new SimpleIntegerProperty(p < 0 ? -1 : p * q);
            this.mur = mur; this.typeId = typeId; this.vendable = vendable;
        }
        public String getNom() { return nom.get(); }
        public int getQuantite() { return quantite.get(); }
        public int getPrix() { return prix.get(); }
        public int getTotal() { return total.get(); }
    }

    private static final NumberFormat NOMBRE = NumberFormat.getIntegerInstance(Locale.FRANCE);
    private static final NumberFormat LINGOTS = NumberFormat.getNumberInstance(Locale.FRANCE);
    static { LINGOTS.setMaximumFractionDigits(1); LINGOTS.setMinimumFractionDigits(0); }

    /** 50 credits = 1 lingot. */
    static String enLingots(long credits) { return LINGOTS.format(credits / 50.0); }

    /** « 12 345 crédits · 246,9 lingots ». */
    static String credits(long c) { return NOMBRE.format(c) + " crédits · " + enLingots(c) + " lingots"; }

    private Label valeur, detail, detailApparts, progression, etat;
    private TableView<Appart> tableApparts;
    private final ObservableList<Appart> apparts = FXCollections.observableArrayList();

    /** Une ligne du tableau des apparts : un appart visite ou tu as des mobis. */
    public static final class Appart {
        final int id;
        private final SimpleStringProperty nom, vu;
        private final SimpleIntegerProperty mobis, valeur;
        Appart(int id, String nom, String vu, int mobis, int valeur) {
            this.id = id;
            this.nom = new SimpleStringProperty(nom);
            this.vu = new SimpleStringProperty(vu);
            this.mobis = new SimpleIntegerProperty(mobis);
            this.valeur = new SimpleIntegerProperty(valeur);
        }
    }
    private Button maj, arreter;
    private TableView<Ligne> table;
    private final ObservableList<Ligne> lignes = FXCollections.observableArrayList();
    private volatile boolean enCours = false, stop = false;
    private volatile int vuTaille = -1;

    public Tab construire() {
        valeur = Ui.valeur("—");
        valeur.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        detail = Ui.etat();
        detailApparts = Ui.etat();
        progression = Ui.etat();
        etat = Ui.etat();

        maj = new Button("Mettre à jour les prix");
        maj.getStyleClass().add("primaire");
        maj.setOnAction(e -> lancer(true));
        arreter = new Button("Arrêter");
        arreter.setDisable(true);
        arreter.setOnAction(e -> stop = true);

        table = new TableView<>(lignes);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Inventaire pas encore reçu."));
        table.setPrefHeight(320);
        TableColumn<Ligne, String> cNom = new TableColumn<>("Mobi");
        cNom.setCellValueFactory(c -> c.getValue().nom);
        TableColumn<Ligne, Number> cQte = colonne("Qté", l -> l.quantite);
        TableColumn<Ligne, Number> cPrix = colonne("Prix", l -> l.prix);
        // Le prix se modifie d'un double-clic ; vide = revenir au prix automatique.
        table.setEditable(true);
        cPrix.setEditable(true);
        cPrix.setCellFactory(x -> new CellulePrix());
        cPrix.setOnEditCommit(e -> {
            Ligne l = e.getRowValue();
            int v = e.getNewValue() == null ? -1 : e.getNewValue().intValue();
            PrixPerso.fixer(l.mur, l.typeId, v);
            afficher();
        });
        cNom.setPrefWidth(150);
        table.getColumns().addAll(List.of(cNom, cQte, cPrix));
        cPrix.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(cPrix);

        tableApparts = new TableView<>(apparts);
        tableApparts.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableApparts.setPlaceholder(new Label("Entre dans un appart où tu as posé des mobis."));
        tableApparts.setPrefHeight(180);
        TableColumn<Appart, String> aNom = new TableColumn<>("Appart");
        aNom.setCellValueFactory(c -> c.getValue().nom);
        aNom.setPrefWidth(140);
        TableColumn<Appart, Number> aMobis = new TableColumn<>("Mobis");
        aMobis.setCellValueFactory(c -> c.getValue().mobis);
        TableColumn<Appart, Number> aValeur = new TableColumn<>("Valeur");
        aValeur.setCellValueFactory(c -> c.getValue().valeur);
        aValeur.setCellFactory(x -> new TableCell<>() {
            @Override protected void updateItem(Number n, boolean vide) {
                super.updateItem(n, vide);
                setText(vide || n == null ? null : NOMBRE.format(n.intValue()) + " (" + enLingots(n.intValue()) + " L)");
                setStyle("-fx-alignment: center-right;");
            }
        });
        TableColumn<Appart, String> aVu = new TableColumn<>("Vu le");
        aVu.setCellValueFactory(c -> c.getValue().vu);
        tableApparts.getColumns().addAll(List.of(aNom, aMobis, aValeur, aVu));
        aValeur.setSortType(TableColumn.SortType.DESCENDING);
        tableApparts.getSortOrder().add(aValeur);
        MenuItem oublier = new MenuItem("Ne plus compter cet appart");
        oublier.setOnAction(e -> {
            Appart a = tableApparts.getSelectionModel().getSelectedItem();
            if (a != null) Patrimoine.oublier(a.id);
        });
        tableApparts.setContextMenu(new ContextMenu(oublier));

        VBox v = new VBox(12,
                Ui.bloc("Valeur estimée", valeur, detail, detailApparts,
                        Ui.aide("Prix de habbofurni.xyz pour l'hôtel FR (moyenne du marché, ou "
                                + "estimation du site pour les rares). Pour les mobis absents du site, "
                                + "prix moyen de la place du marché du jeu. C'est une estimation : le "
                                + "prix réel dépend des acheteurs. Les mobis BC et non échangeables "
                                + "ne se vendent pas.")),
                Ui.bloc("Prix", Ui.ligne(maj, arreter), progression),
                Ui.bloc("Dans mon inventaire", table),
                Ui.bloc("Dans les apparts", tableApparts,
                        Ui.aide("Tes mobis posés dans tes apparts et chez les autres. Un appart est "
                                + "compté quand tu y entres, tel qu'il était à ton dernier passage. "
                                + "Clic droit : ne plus le compter.")),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        suivre();
        Patrimoine.surMaj(() -> Platform.runLater(() -> { afficher(); lancer(false); }));
        Patrimoine.demarrer();
        PrixSite.surMaj(() -> Platform.runLater(() -> {
            afficher();
            if (!PrixSite.etat().isEmpty()) progression.setText(PrixSite.etat());
            // Prix du site connus : on complete les manquants par le marche du jeu.
            if (PrixSite.fini()) lancer(false);
        }));
        PrixSite.demarrer();
        Tab t = new Tab("Valeur de mes mobis", sp);
        t.setClosable(false);
        return t;
    }

    private interface Prop { SimpleIntegerProperty de(Ligne l); }

    /**
     * Cellule de prix : affiche « 1 234 » (« ✎ 1 234 » si tu l'as fixe toi-meme),
     * et se modifie d'un double-clic. Entree valide, Echap annule ; un champ
     * vide remet le prix automatique.
     */
    private final class CellulePrix extends TableCell<Ligne, Number> {
        private TextField champ;

        @Override public void startEdit() {
            Ligne l = getTableRow() == null ? null : getTableRow().getItem();
            if (l == null || !l.vendable) return;
            super.startEdit();
            Number n = getItem();
            champ = new TextField(n == null || n.intValue() < 0 ? "" : String.valueOf(n.intValue()));
            champ.setOnAction(e -> {
                String t = champ.getText().replaceAll("[^0-9]", "");
                commitEdit(t.isEmpty() ? -1 : Integer.parseInt(t));
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
            setStyle("-fx-alignment: center-right;");
            Ligne l = getTableRow() == null ? null : getTableRow().getItem();
            if (vide || n == null || l == null) { setText(null); setTooltip(null); return; }
            boolean perso = PrixPerso.prix(l.mur, l.typeId) != null;
            setText(n.intValue() < 0 ? "—" : (perso ? "✎ " : "") + NOMBRE.format(n.intValue()));
            setTooltip(l.vendable ? new Tooltip("Double-clic pour changer le prix") : null);
        }
    }

    /** Colonne de nombres : alignee a droite, « — » quand le prix manque. */
    private static TableColumn<Ligne, Number> colonne(String titre, Prop p) {
        TableColumn<Ligne, Number> c = new TableColumn<>(titre);
        c.setCellValueFactory(x -> p.de(x.getValue()));
        c.setCellFactory(x -> new TableCell<>() {
            @Override protected void updateItem(Number n, boolean vide) {
                super.updateItem(n, vide);
                setText(vide || n == null ? null : n.intValue() < 0 ? "—" : NOMBRE.format(n.intValue()));
                setStyle("-fx-alignment: center-right;");
            }
        });
        c.setPrefWidth(60);
        return c;
    }

    // ------------------------------------------------------------- donnees

    /** Recalcule le tableau des que l'inventaire arrive ou change, puis complete les prix manquants. */
    private void suivre() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
                    if (inv != null && inv.size() != vuTaille) {
                        vuTaille = inv.size();
                        Platform.runLater(this::afficher);
                        lancer(false);
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-valeur");
        t.setDaemon(true);
        t.start();
    }

    /** Une ligne par mobi different (sol ou mur, typeId). */
    private static Map<String, int[]> grouper(List<HInventoryItem> inv, Map<String, Boolean> vendable) {
        Map<String, int[]> g = new LinkedHashMap<>();
        for (HInventoryItem it : inv) {
            boolean mur = it.getType() == HProductType.WallItem;
            if (!mur && it.getType() != HProductType.FloorItem) continue;
            String k = Marche.cle(mur, it.getTypeId());
            g.computeIfAbsent(k, x -> new int[1])[0]++;
            // Vendable si au moins un exemplaire est autorise sur la place du
            // marche (drapeau du serveur) et n'est pas une location BC.
            vendable.merge(k, it.isSellable() && it.getSecondsToExpiration() <= 0, Boolean::logicalOr);
        }
        return g;
    }

    /** Prix unitaire d'un mobi, pour les autres volets (Salle) ; -1 si inconnu. */
    public static int prixUnitaire(GPresets gp, boolean mur, int typeId) {
        try { return prixDe(gp, mur, typeId, new boolean[1]); } catch (Throwable t) { return -1; }
    }

    /** Prix d'un mobi (cle Marche « 1:typeId » / « 2:typeId ») : habbofurni, sinon le marche du jeu ; -1 si aucun. */
    private static int prixDe(GPresets gp, boolean mur, int typeId, boolean[] parJeu) {
        Integer perso = PrixPerso.prix(mur, typeId);
        if (perso != null) return perso;
        PrixSite.Prix ps = PrixSite.prix(classe(gp, mur, typeId));
        if (ps != null) return ps.moyen;
        Marche.Prix pm = Marche.prix(mur, typeId);
        if (pm != null && pm.moyen > 0) { parJeu[0] = true; return pm.moyen; }
        return -1;
    }

    private void afficher() {
        GPresets gp = AtelierLauncher.gpresets();
        if (gp == null) return;
        long total = 0;
        boolean[] jeu = {false};

        // --- inventaire ---
        List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
        int sansPrix = 0, nonVendables = 0, avecPrix = 0, parJeu = 0;
        if (inv != null) {
            Map<String, Boolean> vendable = new HashMap<>();
            Map<String, int[]> groupes = grouper(inv, vendable);
            List<Ligne> neuves = new ArrayList<>();
            for (Map.Entry<String, int[]> e : groupes.entrySet()) {
                boolean mur = e.getKey().startsWith("2:");
                int typeId = Integer.parseInt(e.getKey().substring(2));
                int q = e.getValue()[0];
                boolean v = vendable.getOrDefault(e.getKey(), false);
                jeu[0] = false;
                int prix = v ? prixDe(gp, mur, typeId, jeu) : -1;
                if (!v) nonVendables += q;
                else if (prix < 0) sansPrix += q;
                else { total += (long) prix * q; avecPrix += q; if (jeu[0]) parJeu += q; }
                neuves.add(new Ligne(nom(gp, mur, typeId) + (v ? "" : "  (non vendable)"), q, prix, mur, typeId, v));
            }
            lignes.setAll(neuves);
            table.sort();
        }
        long totalInventaire = total;

        // --- apparts ---
        long totalApparts = 0;
        int mobisApparts = 0;
        List<Appart> lesApparts = new ArrayList<>();
        for (Patrimoine.Appart a : Patrimoine.apparts()) {
            long v = 0;
            for (Map.Entry<String, Integer> e : a.mobis.entrySet()) {
                boolean mur = e.getKey().startsWith("2:");
                int p = prixDe(gp, mur, Integer.parseInt(e.getKey().substring(2)), jeu);
                if (p > 0) v += (long) p * e.getValue();
            }
            totalApparts += v;
            mobisApparts += a.total();
            String nom = (a.nom == null ? "Appart " + a.id : a.nom)
                    + (a.proprietaire != null && !a.proprietaire.equalsIgnoreCase(String.valueOf(Patrimoine.pseudo()))
                       ? "  (chez " + a.proprietaire + ")" : "");
            lesApparts.add(new Appart(a.id, nom, a.vuLe == null ? "" : jourMois(a.vuLe), a.total(),
                    (int) Math.min(Integer.MAX_VALUE, v)));
        }
        apparts.setAll(lesApparts);
        tableApparts.sort();
        total += totalApparts;

        valeur.setText(credits(total));
        detail.setText("Inventaire : " + credits(totalInventaire) + "  ·  "
                + NOMBRE.format(avecPrix) + " mobis avec un prix"
                + (parJeu > 0 ? " (dont " + NOMBRE.format(parJeu) + " au prix du marché du jeu)" : "")
                + (sansPrix > 0 ? "  ·  " + NOMBRE.format(sansPrix) + " sans prix connu" : "")
                + (nonVendables > 0 ? "  ·  " + NOMBRE.format(nonVendables) + " non vendables" : ""));
        detailApparts.setText("Apparts : " + credits(totalApparts) + "  ·  "
                + NOMBRE.format(mobisApparts) + " mobis dans " + lesApparts.size() + " appart(s)"
                + (PrixSite.misAJour() > 0 ? "  ·  prix habbofurni du "
                    + new java.text.SimpleDateFormat("dd/MM", Locale.FRANCE).format(new Date(PrixSite.misAJour())) : ""));
    }

    private static String jourMois(String iso) {
        return iso.length() >= 10 ? iso.substring(8, 10) + "/" + iso.substring(5, 7) : iso;
    }

    private static String classe(GPresets gp, boolean mur, int typeId) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd == null || !fd.isReady()) return null;
            return mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
        } catch (Throwable t) { return null; }
    }

    private static String nom(GPresets gp, boolean mur, int typeId) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                String cls = mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
                if (cls != null) {
                    String n = mur ? (fd.getWallItemDetails(cls) == null ? null : fd.getWallItemDetails(cls).name)
                                   : (fd.getFloorItemDetails(cls) == null ? null : fd.getFloorItemDetails(cls).name);
                    return (n == null || n.isEmpty()) ? cls : n;
                }
            }
        } catch (Throwable ignored) { }
        return "type " + typeId;
    }

    // ---------------------------------------------------------- mise a jour

    /** forcer : redemander aussi les prix encore valables. */
    private synchronized void lancer(boolean forcer) {
        if (enCours) return;
        // Tant que habbofurni n'est pas lu, on ne sait pas quels prix manquent :
        // inutile de tout demander au jeu.
        if (!forcer && !PrixSite.fini()) return;
        GPresets gp = AtelierLauncher.gpresets();
        List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
        if (gp == null) return;
        if (inv == null) inv = List.of();
        Map<String, Boolean> vendable = new HashMap<>();
        List<String> aDemander = new ArrayList<>();
        Set<String> cles = new LinkedHashSet<>(grouper(inv, vendable).keySet());
        for (Patrimoine.Appart a : Patrimoine.apparts())
            for (String k : a.mobis.keySet()) if (cles.add(k)) vendable.put(k, true);
        for (String k : cles) {
            if (!vendable.getOrDefault(k, false)) continue;
            boolean mur = k.startsWith("2:");
            int typeId = Integer.parseInt(k.substring(2));
            // Le marche du jeu ne sert que pour les mobis absents de habbofurni.
            if (PrixSite.prix(classe(gp, mur, typeId)) != null) continue;
            if (forcer || !Marche.aJour(mur, typeId)) aDemander.add(k);
        }
        if (aDemander.isEmpty()) {
            Platform.runLater(() -> progression.setText("Tous les prix sont à jour."));
            return;
        }
        enCours = true;
        stop = false;
        Platform.runLater(() -> { maj.setDisable(true); arreter.setDisable(false); });

        Thread t = new Thread(() -> {
            int fait = 0, echecs = 0;
            try {
                for (String k : aDemander) {
                    if (stop) break;
                    boolean mur = k.startsWith("2:");
                    int typeId = Integer.parseInt(k.substring(2));
                    try {
                        if (Marche.demander(gp, mur, typeId) == null) echecs++;
                    } catch (InterruptedException e) { break; }
                    fait++;
                    final int f = fait, n = aDemander.size();
                    Platform.runLater(() -> progression.setText("Prix " + f + " / " + n + "..."));
                    if (fait % 15 == 0) Platform.runLater(this::afficher);
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                }
            } finally {
                Marche.sauver();
                final int f = fait, ec = echecs, n = aDemander.size();
                final boolean arrete = stop;
                Platform.runLater(() -> {
                    afficher();
                    maj.setDisable(false);
                    arreter.setDisable(true);
                    progression.setText((arrete ? "Arrêté : " : "Terminé : ") + f + " prix sur " + n
                            + (ec > 0 ? "  ·  " + ec + " sans réponse du jeu (redemandés automatiquement plus tard)" : ""));
                });
                enCours = false;
            }
        }, "atelier-prix");
        t.setDaemon(true);
        t.start();
    }

    private void dire(String s) { Platform.runLater(() -> etat.setText(s)); }
}

package atelier;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Valeur > Mes depenses : tes soldes (credits, duckets, diamants), ce que tu
 * as depense et gagne sur une periode, des graphiques (par jour ou par mois,
 * et par type), et la liste des mouvements (achats au catalogue, marche,
 * echanges, troc, taches quotidiennes, autres variations). Les donnees
 * viennent de Depenses ; ici, seulement l'affichage et les filtres.
 *
 * Les images sont celles du jeu (client Habbo, ressources/atelier/depenses) :
 * monnaies du porte-monnaie, catalogue, marche, echange, coffre wired,
 * taches ; et l'icone de chaque mobi (PrixVignettes).
 *
 * Pas de defilement horizontal : le tableau et les graphiques se calent sur
 * la largeur, le detail passe a la ligne, la bulle donne le texte entier.
 */
public class OngletDepenses {

    private static final String ROUGE = "#B83A2E", VERT = "#2E8B3A", GRIS = "#6F6B60";
    private static final DateTimeFormatter JOUR_HEURE = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE);
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE);
    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("MMM yy", Locale.FRANCE);
    private static final String[] IMAGE_MONNAIE = {"credits", "duckets", "diamants"};

    /** Les periodes du bilan et de la liste. */
    private enum Periode {
        JOUR("Aujourd'hui"), SEMAINE("7 jours"), MOIS("30 jours"), TOUT("Tout");
        final String texte;
        Periode(String t) { texte = t; }
        /** Debut de la periode (epoque ms), 0 pour tout. */
        long debut() {
            LocalDate j = LocalDate.now();
            switch (this) {
                case JOUR: return ms(j);
                case SEMAINE: return ms(j.minusDays(6));
                case MOIS: return ms(j.minusDays(29));
                default: return 0;
            }
        }
    }

    /** Les periodes des graphiques. */
    private enum Echelle {
        MOIS("Mois"), ANNEE("Année"), TOUT("Tout");
        final String texte;
        Echelle(String t) { texte = t; }
    }

    private static final String TOUTES = "Toutes les monnaies", TOUS = "Tous les types";

    private final Label[] solde = new Label[Depenses.NB], soldeDetail = new Label[Depenses.NB];
    private final Tooltip[] bulleSolde = new Tooltip[Depenses.NB];
    private final Label[] depense = new Label[Depenses.NB], gain = new Label[Depenses.NB];
    private Label depuis, compte, etat, titreGraphe, videBarres;
    private final ToggleGroup periodes = new ToggleGroup(), echelles = new ToggleGroup(), monnaiesGraphe = new ToggleGroup();
    private ComboBox<String> monnaie, genre;
    private TextField recherche;
    private TableView<Depenses.Mouvement> table;
    private final ObservableList<Depenses.Mouvement> visibles = FXCollections.observableArrayList();
    private List<Depenses.Mouvement> tous = List.of();
    private final AtomicBoolean prevu = new AtomicBoolean(false);
    private BarChart<String, Number> barres;
    private Camembert depensesParType, gainsParType;

    public Tab construire() {
        Depenses.demarrer();

        // ---- soldes
        FlowPane ligneSoldes = new FlowPane(22, 8);
        ligneSoldes.setAlignment(Pos.CENTER_LEFT);
        for (int m = 0; m < Depenses.NB; m++) {
            solde[m] = new Label("—");
            solde[m].setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
            soldeDetail[m] = new Label(Depenses.PLURIEL[m]);
            soldeDetail[m].getStyleClass().add("etat-ligne");
            VBox texte = new VBox(0, solde[m], soldeDetail[m]);
            HBox h = new HBox(8, monnaie(m, 30), texte);
            h.setAlignment(Pos.CENTER_LEFT);
            bulleSolde[m] = Ui.bulle("");
            Tooltip.install(h, bulleSolde[m]);
            ligneSoldes.getChildren().add(h);
        }

        // ---- bilan de la periode
        FlowPane choixPeriode = Ui.boutons();
        for (Periode p : Periode.values()) choixPeriode.getChildren().add(bascule(p.texte, p, periodes, p == Periode.SEMAINE, null));
        GridPane bilan = new GridPane();
        bilan.setHgap(12);
        bilan.setVgap(5);
        ColumnConstraints c0 = new ColumnConstraints(), c1 = new ColumnConstraints(), c2 = new ColumnConstraints();
        c0.setMinWidth(Region.USE_PREF_SIZE);
        c1.setHgrow(Priority.ALWAYS);
        c2.setHgrow(Priority.ALWAYS);
        bilan.getColumnConstraints().addAll(c0, c1, c2);
        for (int m = 0; m < Depenses.NB; m++) {
            HBox n = new HBox(6, monnaie(m, 15), new Label(Ui.majuscule(Depenses.PLURIEL[m])));
            n.setAlignment(Pos.CENTER_LEFT);
            depense[m] = new Label("—");
            depense[m].setWrapText(true);
            depense[m].setStyle("-fx-text-fill: " + ROUGE + ";");
            gain[m] = new Label("—");
            gain[m].setWrapText(true);
            gain[m].setStyle("-fx-text-fill: " + VERT + ";");
            bilan.addRow(m, n, depense[m], gain[m]);
        }
        depuis = new Label();
        depuis.getStyleClass().add("etat-ligne");
        depuis.setWrapText(true);

        // ---- graphiques
        FlowPane choixEchelle = Ui.boutons();
        for (Echelle e : Echelle.values()) choixEchelle.getChildren().add(bascule(e.texte, e, echelles, e == Echelle.MOIS, null));
        FlowPane choixMonnaie = Ui.boutons();
        for (int m = 0; m < Depenses.NB; m++)
            choixMonnaie.getChildren().add(bascule(Ui.majuscule(Depenses.PLURIEL[m]), m, monnaiesGraphe, m == 0, monnaie(m, 15)));
        construireGraphes();
        titreGraphe = new Label();
        titreGraphe.setStyle("-fx-font-weight: bold;");
        titreGraphe.setWrapText(true);
        videBarres = new Label("Rien sur cette période.");
        videBarres.getStyleClass().add("etat-ligne");
        StackPane zoneBarres = new StackPane(barres, videBarres);
        FlowPane legende = Ui.ligne(pastille(ROUGE, "Dépensé"), pastille(VERT, "Gagné"));
        FlowPane camemberts = new FlowPane(16, 12, depensesParType, gainsParType);
        camemberts.setAlignment(Pos.TOP_LEFT);

        // ---- filtres de la liste
        monnaie = new ComboBox<>(FXCollections.observableArrayList(TOUTES, "Crédits", "Duckets", "Diamants"));
        monnaie.setValue(TOUTES);
        monnaie.setCursor(Cursor.HAND);
        monnaie.valueProperty().addListener((o, a, b) -> refiltrer());
        List<String> types = new ArrayList<>();
        types.add(TOUS);
        for (Depenses.Genre g : Depenses.Genre.values()) types.add(g.texte);
        genre = new ComboBox<>(FXCollections.observableArrayList(types));
        genre.setValue(TOUS);
        genre.setCursor(Cursor.HAND);
        genre.valueProperty().addListener((o, a, b) -> refiltrer());
        recherche = new TextField();
        recherche.setPromptText("Chercher un mobi ou un pseudo");
        recherche.setPrefColumnCount(14);
        recherche.textProperty().addListener((o, a, b) -> refiltrer());
        compte = new Label();
        compte.getStyleClass().add("etat-ligne");

        Button troc = Ui.bouton(null, "Lire les ventes du troc");
        troc.setGraphic(source(Depenses.Genre.TROC, 18));
        Ui.bulle(troc, "Dans l'appart de ta boutique troc : relit le journal des transactions du jeu "
                + "et ajoute les ventes pas encore notées, même celles d'avant ce suivi.");
        troc.setCursor(Cursor.HAND);
        etat = Ui.etat();
        troc.setOnAction(e -> {
            troc.setDisable(true);
            Salle.tache("depenses-troc", () -> Depenses.lireVentesTroc(bilanTroc -> Platform.runLater(() -> {
                troc.setDisable(false);
                Ui.vu(etat, bilanTroc);
            })));
        });

        construireTable();

        VBox v = new VBox(12,
                Ui.bloc("Mes soldes", ligneSoldes,
                        Ui.aide("Les soldes que le jeu donne à l'Atelier. 50 crédits = 1 lingot.")),
                Ui.bloc("Bilan", choixPeriode, bilan, depuis,
                        Ui.aide("En rouge ce que tu as dépensé, en vert ce que tu as gagné, sur la période choisie. "
                                + "Les pièces et lingots donnés dans un échange ou au troc comptent pour leur valeur "
                                + "en crédits. Les crédits retirés du coffre de ta boutique troc ne comptent pas : "
                                + "chaque vente du troc est déjà comptée.")),
                Ui.bloc("Graphiques", choixEchelle, choixMonnaie, titreGraphe, zoneBarres, legende, camemberts,
                        Ui.aide("Mois : jour par jour sur 30 jours. Année : mois par mois sur 12 mois. "
                                + "Tout : depuis le début du suivi. Les deux cercles montrent d'où viennent "
                                + "tes dépenses et tes gains, par type.")),
                Ui.bloc("Mouvements", Ui.ligne(monnaie, genre), recherche, compte, table, Ui.boutons(troc), etat,
                        Ui.aide("Achats au catalogue, achats et ventes au marché, échanges, achats et ventes au troc, "
                                + "récompenses des tâches quotidiennes. Une variation de solde qu'aucune action "
                                + "connue n'explique est notée « Autre » (cadeaux, remboursements…). "
                                + "Le jeu ne donne pas l'historique : le suivi commence au premier lancement "
                                + "de cette fonction, sauf les ventes du troc, relues depuis le journal du jeu. "
                                + "Clic droit sur une ligne : la retirer.")));
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // un seul ecouteur pour les trois groupes de choix ; toujours un choix fait
        for (ToggleGroup g : List.of(periodes, echelles, monnaiesGraphe))
            g.selectedToggleProperty().addListener((o, a, b) -> {
                if (b == null && a != null) a.setSelected(true);
                else refiltrer();
            });
        Depenses.ecouter(this::prevoir);
        prevoir();

        Tab t = new Tab("Mes dépenses", sp);
        t.setClosable(false);
        return t;
    }

    private static ToggleButton bascule(String texte, Object valeur, ToggleGroup g, boolean choisi, Node image) {
        ToggleButton b = new ToggleButton(texte);
        if (image != null) { b.setGraphic(image); b.setGraphicTextGap(6); }
        b.setUserData(valeur);
        b.setToggleGroup(g);
        b.setSelected(choisi);
        b.setCursor(Cursor.HAND);
        return b;
    }

    private static HBox pastille(String couleur, String texte) {
        Region r = new Region();
        r.setMinSize(11, 11);
        r.setMaxSize(11, 11);
        r.setStyle("-fx-background-color: " + couleur + "; -fx-background-radius: 2;");
        HBox h = new HBox(5, r, new Label(texte));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static long ms(LocalDate j) { return j.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }

    private static LocalDate jour(long t) { return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate(); }

    // ------------------------------------------------------------- images du jeu

    private static final Map<String, Image> IMAGES = new HashMap<>();

    /** Une image du jeu de ressources/atelier/depenses ; null si absente. */
    private static Image image(String nom) {
        return IMAGES.computeIfAbsent(nom, n -> {
            try {
                java.net.URL u = OngletDepenses.class.getResource("/atelier/depenses/" + n + ".png");
                return u == null ? null : new Image(u.toExternalForm());
            } catch (Throwable t) { return null; }
        });
    }

    /** L'image a une hauteur donnee ; les petites images de pixels restent nettes. */
    private static Node vue(Image i, double hauteur) {
        if (i == null) { Region r = new Region(); r.setMinSize(hauteur, hauteur); return r; }
        ImageView v = new ImageView(i);
        v.setPreserveRatio(true);
        v.setFitHeight(hauteur);
        v.setSmooth(hauteur < i.getHeight());     // agrandie : pixels nets, comme dans le jeu
        return v;
    }

    /** L'icone d'une monnaie (porte-monnaie du jeu). */
    private static Node monnaie(int m, double taille) { return vue(image(IMAGE_MONNAIE[m]), taille); }

    /** L'icone de la source d'un mouvement. */
    private static Node source(Depenses.Genre g, double taille) {
        String n;
        switch (g) {
            case CATALOGUE: n = "catalogue"; break;
            case MARCHE: n = "marche"; break;
            case ECHANGE: n = "echange"; break;
            case TROC: n = "troc"; break;
            case TACHE: n = "tache"; break;
            default: n = "autre";
        }
        return vue(image(n), taille);
    }

    // ------------------------------------------------------------- graphiques

    /** Un camembert et sa legende (types, montants, parts), dans une colonne qui se replie. */
    private static final class Camembert extends VBox {
        final Label titre = new Label();
        final PieChart graphe = new PieChart();
        final VBox legende = new VBox(3);
        final Label vide = new Label("Rien sur cette période.");

        Camembert() {
            super(6);
            titre.setStyle("-fx-font-weight: bold;");
            titre.setWrapText(true);
            graphe.setLegendVisible(false);
            graphe.setLabelsVisible(false);
            graphe.setAnimated(false);
            graphe.setStartAngle(90);
            graphe.setMinSize(150, 150);
            graphe.setPrefSize(170, 170);
            graphe.setMaxSize(190, 190);
            vide.getStyleClass().add("etat-ligne");
            getChildren().addAll(titre, graphe, vide, legende);
            setMinWidth(0);
            setPrefWidth(250);
            setMaxWidth(320);
        }

        void montrer(String t, Map<Depenses.Genre, Long> parType, int m) {
            titre.setText(t);
            long total = 0;
            for (long v : parType.values()) total += v;
            List<PieChart.Data> l = new ArrayList<>();
            legende.getChildren().clear();
            for (Map.Entry<Depenses.Genre, Long> e : parType.entrySet()) {
                if (e.getValue() <= 0) continue;
                PieChart.Data d = new PieChart.Data(e.getKey().texte, e.getValue());
                String c = e.getKey().couleur;
                d.nodeProperty().addListener((o, a, n) -> { if (n != null) n.setStyle("-fx-pie-color: " + c + ";"); });
                l.add(d);
                long pc = Math.round(100.0 * e.getValue() / total);
                Label lt = new Label(e.getKey().texte + " : " + PrixTexte.nombre(e.getValue()) + " (" + pc + " %)");
                lt.setWrapText(true);
                lt.setMinWidth(0);
                Region r = new Region();
                r.setMinSize(11, 11);
                r.setMaxSize(11, 11);
                r.setStyle("-fx-background-color: " + c + "; -fx-background-radius: 2;");
                HBox h = new HBox(6, r, source(e.getKey(), 16), lt);
                h.setAlignment(Pos.CENTER_LEFT);
                legende.getChildren().add(h);
            }
            graphe.getData().setAll(l);
            for (PieChart.Data d : l) if (d.getNode() != null) d.getNode().setStyle("-fx-pie-color: " + couleurDe(d) + ";");
            boolean rien = l.isEmpty();
            graphe.setVisible(!rien);
            graphe.setManaged(!rien);
            vide.setVisible(rien);
            vide.setManaged(rien);
        }

        private static String couleurDe(PieChart.Data d) {
            for (Depenses.Genre g : Depenses.Genre.values()) if (g.texte.equals(d.getName())) return g.couleur;
            return GRIS;
        }
    }

    private void construireGraphes() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setMinorTickVisible(false);
        y.setForceZeroInRange(true);
        barres = new BarChart<>(x, y);
        barres.setLegendVisible(false);
        barres.setAnimated(false);
        barres.setCategoryGap(3);
        barres.setBarGap(0);
        barres.setMinWidth(0);
        barres.setMinHeight(210);
        barres.setPrefHeight(240);
        barres.setVerticalGridLinesVisible(false);
        depensesParType = new Camembert();
        gainsParType = new Camembert();
    }

    /** Une tranche de temps des barres : son libelle et ses bornes. */
    private record Tranche(String libelle, String bulle, long debut, long fin) { }

    private List<Tranche> tranches(Echelle e) {
        List<Tranche> l = new ArrayList<>();
        LocalDate auj = LocalDate.now();
        boolean parJour = e == Echelle.MOIS;
        LocalDate depuisLe = e == Echelle.MOIS ? auj.minusDays(29) : auj.withDayOfMonth(1).minusMonths(11);
        if (e == Echelle.TOUT) {
            long d = Depenses.debut();
            LocalDate premier = d > 0 ? jour(d) : auj;
            for (Depenses.Mouvement m : tous) { LocalDate j = jour(m.t()); if (j.isBefore(premier)) premier = j; }
            parJour = !premier.isBefore(auj.minusDays(45));
            depuisLe = parJour ? premier : premier.withDayOfMonth(1);
        }
        if (parJour) {
            for (LocalDate j = depuisLe; !j.isAfter(auj); j = j.plusDays(1))
                l.add(new Tranche(String.valueOf(j.getDayOfMonth()), JOUR.format(j), ms(j), ms(j.plusDays(1))));
        } else {
            for (YearMonth ym = YearMonth.from(depuisLe); !ym.isAfter(YearMonth.from(auj)); ym = ym.plusMonths(1))
                l.add(new Tranche(MOIS.format(ym.atDay(1)), Ui.majuscule(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)
                        .format(ym.atDay(1))), ms(ym.atDay(1)), ms(ym.plusMonths(1).atDay(1))));
        }
        return l;
    }

    private void majGraphes() {
        Toggle te = echelles.getSelectedToggle(), tm = monnaiesGraphe.getSelectedToggle();
        Echelle e = te == null ? Echelle.MOIS : (Echelle) te.getUserData();
        int m = tm == null ? 0 : (Integer) tm.getUserData();
        List<Tranche> ts = tranches(e);
        long[] dep = new long[ts.size()], gag = new long[ts.size()];
        Map<Depenses.Genre, Long> depType = new EnumMap<>(Depenses.Genre.class), gagType = new EnumMap<>(Depenses.Genre.class);
        long debutGraphe = ts.isEmpty() ? 0 : ts.get(0).debut();
        boolean rien = true;
        for (Depenses.Mouvement x : tous) {
            long v = x.montant(m);
            if (x.transfert() || v == 0 || x.t() < debutGraphe) continue;
            for (int i = 0; i < ts.size(); i++) {
                Tranche t = ts.get(i);
                if (x.t() < t.debut() || x.t() >= t.fin()) continue;
                if (v < 0) { dep[i] -= v; depType.merge(x.genre(), -v, Long::sum); }
                else { gag[i] += v; gagType.merge(x.genre(), v, Long::sum); }
                rien = false;
                break;
            }
        }
        XYChart.Series<String, Number> sDep = new XYChart.Series<>(), sGag = new XYChart.Series<>();
        String unite = Depenses.PLURIEL[m];
        for (int i = 0; i < ts.size(); i++) {
            Tranche t = ts.get(i);
            sDep.getData().add(barre(t.libelle(), dep[i], ROUGE, t.bulle() + " : " + PrixTexte.nombre(dep[i]) + " " + unite + " dépensés"));
            sGag.getData().add(barre(t.libelle(), gag[i], VERT, t.bulle() + " : " + PrixTexte.nombre(gag[i]) + " " + unite + " gagnés"));
        }
        barres.getData().setAll(List.of(sDep, sGag));
        barres.setVisible(!rien);
        videBarres.setVisible(rien);
        String quand = e == Echelle.MOIS ? "sur 30 jours" : e == Echelle.ANNEE ? "sur 12 mois" : "depuis le début";
        titreGraphe.setText(Ui.majuscule(unite) + " " + quand);
        depensesParType.montrer("Dépenses par type", depType, m);
        gainsParType.montrer("Gains par type", gagType, m);
    }

    /** Une barre coloree, avec sa bulle (date et montant). */
    private static XYChart.Data<String, Number> barre(String x, long v, String couleur, String bulle) {
        XYChart.Data<String, Number> d = new XYChart.Data<>(x, v);
        Runnable style = () -> {
            Node n = d.getNode();
            if (n == null) return;
            n.setStyle("-fx-bar-fill: " + couleur + ";");
            Tooltip.install(n, Ui.bulle(bulle));
        };
        d.nodeProperty().addListener((o, a, n) -> style.run());
        style.run();
        return d;
    }

    // ------------------------------------------------------------- tableau

    private void construireTable() {
        table = new TableView<>(visibles);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Aucun mouvement pour l'instant."));
        table.setPrefHeight(380);

        // date, puis la source (image du jeu et type) : la colonne reste etroite
        TableColumn<Depenses.Mouvement, Depenses.Mouvement> cQuand = new TableColumn<>("Quand");
        cQuand.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        cQuand.setCellFactory(x -> new TableCell<>() {
            private final Label date = new Label(), type = new Label();
            private final VBox boite = new VBox(2, date, type);
            {
                type.getStyleClass().add("etat-ligne");
                type.setPadding(Insets.EMPTY);
                type.setWrapText(false);              // une ligne : sinon la rangee grandit a tort
                type.setMinWidth(0);
                type.setGraphicTextGap(4);
                setAlignment(Pos.TOP_LEFT);
            }
            @Override protected void updateItem(Depenses.Mouvement m, boolean vide) {
                super.updateItem(m, vide);
                setText(null);
                if (vide || m == null) { setGraphic(null); setTooltip(null); return; }
                date.setText(JOUR_HEURE.format(Instant.ofEpochMilli(m.t()).atZone(ZoneId.systemDefault())));
                type.setText(m.genre().texte);
                type.setGraphic(source(m.genre(), 16));
                setGraphic(boite);
                setTooltip(Ui.bulle(m.genre().texte));
            }
        });
        cQuand.setSortable(false);
        cQuand.setPrefWidth(116);
        cQuand.setMinWidth(100);

        // detail : icone du mobi, texte qui passe a la ligne, texte entier dans la bulle
        TableColumn<Depenses.Mouvement, Depenses.Mouvement> cDetail = new TableColumn<>("Détail");
        cDetail.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        cDetail.setCellFactory(x -> new TableCell<>() {
            private final Text texte = new Text();
            private final ImageView icone = new ImageView();
            private final StackPane cadre = new StackPane(icone);
            private final HBox boite = new HBox(6, cadre, texte);
            private final Tooltip bulle = Ui.bulle("");
            {
                texte.getStyleClass().add("texte-cellule");

                icone.setFitWidth(PrixVignettes.TAILLE);
                icone.setFitHeight(PrixVignettes.TAILLE);
                icone.setPreserveRatio(true);
                cadre.setMinSize(PrixVignettes.TAILLE, PrixVignettes.TAILLE);
                cadre.setMaxSize(PrixVignettes.TAILLE, PrixVignettes.TAILLE);
                boite.setAlignment(Pos.TOP_LEFT);
                setAlignment(Pos.TOP_LEFT);
            }
            /** Le texte se replie a la largeur de la colonne ; la ligne prend la hauteur qu'il faut. */
            private void replier(double largeur) {
                double l = (largeur > 0 ? largeur : cDetail.getWidth()) - PrixVignettes.TAILLE - 18;
                texte.setWrappingWidth(Math.max(20, l));
            }
            @Override protected double computePrefHeight(double largeur) {
                if (getGraphic() == null) return super.computePrefHeight(largeur);
                replier(largeur);
                return Math.max(PrixVignettes.TAILLE, texte.getLayoutBounds().getHeight()) + snappedTopInset() + snappedBottomInset() + 2;
            }
            @Override protected void layoutChildren() {
                replier(getWidth());
                super.layoutChildren();
            }
            @Override protected void updateItem(Depenses.Mouvement m, boolean vide) {
                super.updateItem(m, vide);
                setText(null);
                if (vide || m == null) { setGraphic(null); setTooltip(null); return; }
                String t = detail(m);
                // tres long (gros echange) : coupe, le reste dans la bulle
                texte.setText(t.length() > 220 ? t.substring(0, 219) + "…" : t);
                texte.setOpacity(m.transfert() ? 0.6 : 1);
                icone.setImage(iconeMobi(m));
                setGraphic(boite);
                bulle.setText(t + (m.transfert() ? "\nDéjà compté dans les ventes du troc : pas dans le bilan." : ""));
                setTooltip(bulle);
            }
        });
        cDetail.setSortable(false);
        cDetail.setPrefWidth(230);
        cDetail.setMinWidth(100);

        TableColumn<Depenses.Mouvement, Depenses.Mouvement> cMontant = new TableColumn<>("Montant");
        cMontant.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        cMontant.setCellFactory(x -> new TableCell<>() {
            private final VBox boite = new VBox(2);
            { boite.setAlignment(Pos.TOP_RIGHT); setAlignment(Pos.TOP_RIGHT); }
            @Override protected void updateItem(Depenses.Mouvement m, boolean vide) {
                super.updateItem(m, vide);
                setText(null);
                if (vide || m == null) { setGraphic(null); setTooltip(null); return; }
                boite.getChildren().clear();
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Depenses.NB; i++) {
                    long v = m.montant(i);
                    if (v == 0) continue;
                    Label l = new Label(signe(v) + PrixTexte.nombre(Math.abs(v)), monnaie(i, 15));
                    l.setContentDisplay(ContentDisplay.RIGHT);
                    l.setGraphicTextGap(4);
                    l.setStyle("-fx-text-fill: " + (m.transfert() ? GRIS : v < 0 ? ROUGE : VERT) + "; -fx-font-weight: bold;");
                    boite.getChildren().add(l);
                    if (b.length() > 0) b.append('\n');
                    b.append(signe(v)).append(montantTexte(i, Math.abs(v)));
                }
                if (boite.getChildren().isEmpty()) {
                    Label l = new Label("—");
                    l.setStyle("-fx-text-fill: " + GRIS + ";");
                    boite.getChildren().add(l);
                    b.append(m.genre() == Depenses.Genre.CATALOGUE ? "Gratuit" : "Pas de crédits, duckets ni diamants");
                }
                setGraphic(boite);
                setTooltip(Ui.bulle(b.toString()));
            }
        });
        cMontant.setSortable(false);
        cMontant.setPrefWidth(96);
        cMontant.setMinWidth(80);

        table.getColumns().addAll(List.of(cQuand, cDetail, cMontant));
        // le texte du detail se replie selon la largeur : hauteur des lignes recalculee
        cDetail.widthProperty().addListener((o, a, b) -> {
            if (Math.abs(a.doubleValue() - b.doubleValue()) >= 1) Platform.runLater(table::refresh);
        });

        MenuItem retirer = new MenuItem("Retirer cette ligne");
        retirer.setOnAction(e -> {
            for (Depenses.Mouvement m : new ArrayList<>(table.getSelectionModel().getSelectedItems()))
                Depenses.retirer(m.id());
        });
        table.setContextMenu(new ContextMenu(retirer));
    }

    /** L'icone du mobi du mouvement (images du jeu, chargee en fond) ; null sans mobi. */
    private static Image iconeMobi(Depenses.Mouvement m) {
        if (m.classe() == null) return null;
        try {
            return PrixVignettes.icone(m.classe(), PrixTexte.revision(AtelierLauncher.moteur(), m.mur(), m.classe()));
        } catch (Throwable t) { return null; }
    }

    /** Le detail, avec la personne de l'echange ou du troc. */
    private static String detail(Depenses.Mouvement m) {
        String p = m.personne();
        if (p == null || p.isBlank()) return m.detail();
        if (m.genre() == Depenses.Genre.TROC)
            return m.detail() + (m.detail().startsWith("Vente") ? " · Acheteur : " : " · Vendeur : ") + p;
        return m.detail() + " · Avec " + p;
    }

    private static String signe(long v) { return v < 0 ? "−" : "+"; }

    /** « 1 234 crédits (24,7 lingots) », « 1 diamant ». */
    private static String montantTexte(int m, long v) {
        String t = PrixTexte.nombre(v) + " " + (v > 1 ? Depenses.PLURIEL[m] : Depenses.SINGULIER[m]);
        return m == Depenses.CREDITS && v >= 50 ? t + " (" + PrixTexte.lingots(v) + " lingots)" : t;
    }

    // ------------------------------------------------------------- mise a jour (fil FX)

    /** Un rafraichissement au plus par passage du fil FX ; n'importe quel fil. */
    private void prevoir() {
        if (!prevu.compareAndSet(false, true)) return;
        Platform.runLater(() -> { prevu.set(false); appliquer(); });
    }

    private void appliquer() {
        tous = Depenses.mouvements();
        Depenses.Soldes s = Depenses.soldes();
        for (int m = 0; m < Depenses.NB; m++) {
            long v = s.de(m);
            solde[m].setText(v < 0 ? "—" : PrixTexte.nombre(v));
            String d = v == 1 ? Depenses.SINGULIER[m] : Depenses.PLURIEL[m];
            if (m == Depenses.CREDITS && v >= 0) d += " · " + PrixTexte.lingots(v) + " lingots";
            soldeDetail[m].setText(d);
            bulleSolde[m].setText(v < 0 ? "Le jeu n'a pas encore donné ce solde."
                    : "Solde au " + PrixTexte.dateHeure(s.le()));
        }
        long debut = Depenses.debut();
        depuis.setText(debut > 0 ? "Suivi depuis le " + PrixTexte.dateHeure(debut) + "." : "Le suivi commence dès que le jeu envoie tes soldes.");
        refiltrer();
    }

    private Periode periode() {
        Toggle t = periodes.getSelectedToggle();
        return t == null ? Periode.SEMAINE : (Periode) t.getUserData();
    }

    private void refiltrer() {
        if (table == null) return;
        long debut = periode().debut();
        int m = monnaie.getValue() == null ? -1 : monnaie.getSelectionModel().getSelectedIndex() - 1;
        Depenses.Genre g = null;
        for (Depenses.Genre x : Depenses.Genre.values()) if (x.texte.equals(genre.getValue())) g = x;
        String q = PrixCalcul.plat(recherche.getText());

        List<Depenses.Mouvement> periodeSeule = new ArrayList<>(), garder = new ArrayList<>();
        for (Depenses.Mouvement x : tous) {
            if (x.t() < debut) continue;
            periodeSeule.add(x);
            if (m >= 0 && !x.touche(m)) continue;
            if (g != null && x.genre() != g) continue;
            if (!q.isEmpty() && !PrixCalcul.plat(detail(x)).contains(q)) continue;
            garder.add(x);
        }
        Collections.reverse(garder);              // le plus recent en haut
        if (!garder.equals(visibles)) visibles.setAll(garder);

        // le bilan : toute la periode (les filtres de la liste ne le changent pas)
        for (int i = 0; i < Depenses.NB; i++) {
            Depenses.Total t = Depenses.total(periodeSeule, i);
            depense[i].setText("Dépensé " + PrixTexte.nombre(t.depense())
                    + (i == Depenses.CREDITS && t.depense() >= 50 ? " (" + PrixTexte.lingots(t.depense()) + " lingots)" : ""));
            gain[i].setText("Gagné " + PrixTexte.nombre(t.gain())
                    + (i == Depenses.CREDITS && t.gain() >= 50 ? " (" + PrixTexte.lingots(t.gain()) + " lingots)" : ""));
        }
        boolean filtre = m >= 0 || g != null || !q.isEmpty();
        compte.setText(filtre ? Ui.accorder(PrixTexte.nombre(garder.size()) + " mouvement(s) sur " + PrixTexte.nombre(periodeSeule.size()))
                : Ui.accorder(PrixTexte.nombre(garder.size()) + " mouvement(s)"));
        table.setPlaceholder(new Label(tous.isEmpty() ? "Aucun mouvement pour l'instant." : "Aucun mouvement ne correspond."));
        majGraphes();
    }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Onglet Apparts, en deux volets :
 *   - Ma salle      : agir sur la salle courante (liste, copie Discord, retrait).
 *                     La liste suit la salle en temps reel, sans bouton « Relire ».
 *   - Copier appart : poser un appart enregistre dans la salle courante
 *
 * Le journal est partage, dans un panneau a separateur deplacable.
 */
public class OngletApparts {

    public static class Ligne {
        private final SimpleStringProperty nom;
        private final SimpleIntegerProperty quantite;
        private final String type;          // "sol" | "mur"
        /** D'ou ce mobi-ci a ete pose : "BC" (id 0x7FFF0000..) ou "Inventaire". */
        private final String origine;
        private final String proprietaire;  // le pseudo du poseur, ou "—"
        /** Le TYPE existe au catalogue Builders Club : OUI, NON ou "?" (catalogue pas encore lu). */
        private final String auBc;
        public Ligne(String n, int q, String t, String o, String pr) { this(n, q, t, o, pr, "?"); }
        public Ligne(String n, int q, String t, String o, String pr, String bc) {
            nom = new SimpleStringProperty(n);
            quantite = new SimpleIntegerProperty(q);
            type = t; origine = o; proprietaire = pr; auBc = bc;
        }
        public String getNom() { return nom.get(); }
        public int getQuantite() { return quantite.get(); }
        public String getType() { return type; }
        public String getOrigine() { return origine; }
        public String getProprietaire() { return proprietaire; }
        public String getAuBc() { return auBc; }
        /** Pose depuis le Builders Club : disparait sans abonnement, ne se ramasse pas. */
        public boolean estPoseBc() { return "BC".equals(origine); }
        /** Prix de la ligne (quantite comprise), en credits ; -1 inconnu. Pas dans « Copier la liste ». */
        private long prix = -1;
        /** Classe et revision du mobi, pour son icone dans le tableau (vides si inconnues). */
        private volatile String classe;
        private volatile int revision;
        public String getPrix() {
            return prix < 0 ? "—" : java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(prix) + " c";
        }
    }

    /** Ligne d'etat de chaque volet : ce sont eux que Navigation monte, pas la racine. */
    private final Label etatSalle = Ui.etat(), etatAppart = Ui.etat();
    /** Progression du collage ou de la copie (et des « 3 / 10 » de etatAppart) ; « Arrêter le collage » est a part. */
    private final Avancement.Barre barreAppart = new Avancement.Barre().pour(etatAppart).sansArret();
    /** Le collage en cours, pour sa barre. */
    private volatile Avancement.Suivi avCollage;

    // volet « ma salle »
    private RadioButton oTout, oBc, oInv, tTout, tSols, tMurs;
    private TableView<Ligne> tableSalle;
    /** Ligne choisie dans « Ma salle », recopiee sur le fil FX. */
    private volatile Ligne ligneChoisie;
    private Ui.Voyant vInv, vBc, vFurni, vSalle;
    private VBox blocEtat, contenuSalle, blocEtat2, contenuAppart;
    private Ui.Voyant vInv2, vBc2, vFurni2, vSalle2;
    private Ui.Voyant vPaquets;
    private Label salleActuelle, salleActuelle2;
    private ComboBox<String> choixProprio;
    private HBox ligneProprio;
    private TextField rechercheSalle;
    /** « 12 mobis posés depuis le BC » au-dessus du tableau. */
    private Label compteBc;

    /** Choix par defaut du filtre par personne. */
    private static final String TOUT_LE_MONDE = "Tout le monde";
    /** Ce qu'on affiche quand le serveur n'a pas donne de poseur. */
    private static final String INCONNU = "Inconnu";
    private TextField nomNouvelAppart;
    private RadioButton etTout, etZone;
    private CheckBox cpSols, cpMurs, cpWired;
    private Label coinsLbl;
    private final List<Ligne> brutSalle = new ArrayList<>();

    // volet « copier un appart »
    private ComboBox<String> choixAppart;
    private Label cptAppart;
    private RadioButton sInv, sBc, sBcInv, sInvBc;
    private TableView<Ligne> tableAppart;
    private final List<Ligne> brutAppart = new ArrayList<>();

    // ------------------------------------------------------------------ UI

    public Pane construire() {
        Tab t1 = new Tab("Mon appart", defiler(voletSalle()));
        Tab t2 = new Tab("Dupliquer un appart", defiler(voletAppart()));
        t1.setClosable(false); t2.setClosable(false);

        VBox racine = Ui.sousMenu(t1, t2);

        chargerListeApparts();
        return racine;
    }

    private static ScrollPane defiler(Pane contenu) {
        ScrollPane sp = new ScrollPane(contenu);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // jamais de defilement en largeur (pave tactile compris)
        sp.hvalueProperty().addListener((o, a, b) -> { if (b.doubleValue() != sp.getHmin()) sp.setHvalue(sp.getHmin()); });
        return sp;
    }

    // -------------------------------------------------------- volet ma salle

    /**
     * Volet « Mon appart », de haut en bas :
     *   1. l'appart actuel et ses chiffres (sols, muraux, BC, valeur), puis des
     *      pastilles seulement pour ce qui manque ou charge ;
     *   2. les filtres sur une ligne qui passe a la ligne (et « Effacer les filtres ») ;
     *   3. le tableau, qui prend la hauteur restante ;
     *   4. les actions : « Copier la liste » en principal, les autres a cote.
     * Hors d'un appart (ou pendant le premier chargement), un etat vide prend la place.
     */
    private Pane voletSalle() {
        vInv = new Ui.Voyant("Inventaire");
        vBc  = new Ui.Voyant("Catalogue BC");
        vFurni = new Ui.Voyant("Liste des mobis");
        vSalle = new Ui.Voyant("Appart");
        vPaquets = new Ui.Voyant("Paquets");

        ToggleGroup origSalle = new ToggleGroup();
        oTout = radio("Tout", origSalle, true);
        oBc   = radio("BC", origSalle, false);
        oInv  = radio("Inventaire", origSalle, false);
        for (RadioButton r : new RadioButton[]{oTout, oBc, oInv})
            r.setOnAction(e -> filtrerSalle());
        oBc.setTooltip(bulle("Posés depuis le Builders Club : ils disparaissent sans abonnement BC."));
        oInv.setTooltip(bulle("Posés depuis un inventaire : de vrais mobis, qui se ramassent."));

        ToggleGroup typeSalle = new ToggleGroup();
        tTout = radio("Tout", typeSalle, true);
        tSols = radio("Sols", typeSalle, false);
        tMurs = radio("Muraux", typeSalle, false);
        for (RadioButton r : new RadioButton[]{tTout, tSols, tMurs})
            r.setOnAction(e -> filtrerSalle());

        tableSalle = table();
        // Le fournisseur tourne hors du fil FX : il ne lit que la copie de la selection.
        tableSalle.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> ligneChoisie = b);
        // fenetre Salle ouverte : la ligne choisie s'allume dans l'appart (tous ses exemplaires)
        final Object[] memo = {null, 0L, java.util.List.of()};   // seulement sur le fil de MiseEnValeur
        MiseEnValeur.fournir("salle-mobis", () -> {
            Ligne l = ligneChoisie;
            if (l == null) return java.util.List.of();
            long t = System.currentTimeMillis();
            if (l == memo[0] && t - (long) memo[1] < 2000) return (java.util.List<String>) memo[2];
            Moteur gp = AtelierLauncher.moteur();
            boolean mur = "mur".equals(l.type);
            java.util.List<String> r = mur
                    ? MiseEnValeur.mursOu(w -> l.getNom().equals(nom(gp, Salle.classe(w.getTypeId(), true), true)))
                    : MiseEnValeur.solsOu(it -> l.getNom().equals(nom(gp, Salle.classe(it.getTypeId(), false), false)));
            memo[0] = l; memo[1] = t; memo[2] = r;
            return r;
        });

        // ---- 1. l'appart actuel et ses chiffres
        salleActuelle = Ui.valeur("—");
        salleActuelle.setWrapText(true);
        salleActuelle.setMaxWidth(Double.MAX_VALUE);
        chSols = chiffre("Mobis au sol");
        chMurs = chiffre("Mobis accrochés au mur");
        chBc = chiffre("Mobis posés depuis le Builders Club : ils disparaissent sans abonnement BC.");
        chValeur = chiffre("");
        chValeur.setTooltip(bulle(""));
        chValeur.getStyleClass().add("chiffre-fort");
        chNoms = chiffre("La liste des mobis se télécharge depuis habbo.fr : les noms en français arrivent tout seuls.");
        chNoms.getStyleClass().setAll("label", "badge", "badge-attente");
        chNoms.setText("Noms techniques");
        chNoms.setVisible(false); chNoms.setManaged(false);
        compteBc = chBc;

        // Limites de l'appart : repliees par defaut, un lien les montre
        VBox blocLimites = LimitesSalle.bloc();
        Hyperlink voirLimites = new Hyperlink("Limites");
        voirLimites.setTooltip(bulle("Montre ou cache les limites de l'appart (mobis, animaux, bots)."));
        boolean limites = PREFS.getBoolean("appart.limites", false);
        blocLimites.setVisible(limites); blocLimites.setManaged(limites);
        voirLimites.setOnAction(e -> {
            boolean voir = !blocLimites.isVisible();
            blocLimites.setVisible(voir); blocLimites.setManaged(voir);
            PREFS.putBoolean("appart.limites", voir);
        });
        javafx.scene.layout.FlowPane chiffres = new javafx.scene.layout.FlowPane(6, 4,
                chSols, chMurs, chBc, chValeur, chNoms, voirLimites);
        chiffres.setAlignment(Pos.CENTER_LEFT);
        chiffres.setRowValignment(javafx.geometry.VPos.CENTER);
        majChiffres(0, 0, true);

        // Pastilles d'etat : seulement ce qui manque ou charge (bulle = la raison)
        javafx.scene.layout.FlowPane pastilles = new javafx.scene.layout.FlowPane(10, 4);
        pastilles.setAlignment(Pos.CENTER_LEFT);
        pastilles.getStyleClass().add("pastilles-appart");
        Object[][] suivis = {{vFurni, "Liste des mobis", false}, {vBc, "Catalogue BC", true},
                {vInv, "Inventaire", true}, {vPaquets, "Paquets", false}};
        for (Object[] s : suivis) {
            Ui.Voyant v = (Ui.Voyant) s[0];
            Pastille p = new Pastille((String) s[1], (Boolean) s[2]);
            miroirs.put(v, p);
            pastilles.getChildren().add(p);
            p.visibleProperty().addListener((o, a, b) -> majPastilles(pastilles));
        }
        majPastilles(pastilles);
        // apres les pastilles : leur premier etat ne doit pas leur echapper
        demarrerVoyants();
        demarrerSuiviSalle();

        VBox enTete = new VBox(4, salleActuelle, chiffres, pastilles);

        // ---- 2. les filtres
        rechercheSalle = new TextField();
        rechercheSalle.setPromptText("Chercher un mobi");
        rechercheSalle.textProperty().addListener((o, a, b) -> filtrerSalle());
        rechercheSalle.setMaxWidth(Double.MAX_VALUE);
        rechercheSalle.setMinWidth(90);
        Button effacerRecherche = Icones.seul(Icones.VIDER, "Effacer la recherche");
        effacerRecherche.setOnAction(e -> rechercheSalle.clear());
        effacerRecherche.visibleProperty().bind(rechercheSalle.textProperty().isNotEmpty());
        HBox boiteRecherche = new HBox(4, Icones.petit(Icones.LOUPE), rechercheSalle, effacerRecherche);
        boiteRecherche.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(rechercheSalle, Priority.ALWAYS);

        choixProprio = new ComboBox<>();
        choixProprio.setPrefWidth(150);
        choixProprio.setMaxWidth(Double.MAX_VALUE);
        choixProprio.getItems().add(TOUT_LE_MONDE);
        choixProprio.setValue(TOUT_LE_MONDE);
        choixProprio.valueProperty().addListener((o, a, b) -> filtrerSalle());
        choixProprio.setTooltip(bulle("Seulement les mobis posés par cette personne."));

        effacerFiltres = Icones.sur(new Button("Effacer les filtres"), Icones.VIDER);
        effacerFiltres.setOnAction(e -> effacerFiltresSalle());
        effacerFiltres.setVisible(false); effacerFiltres.setManaged(false);

        ligneProprio = groupeFiltre("Personne", choixProprio);
        // « Personne » seulement si les mobis de l'appart sont a plusieurs personnes
        // (la liste contient « tout le monde » + un nom par proprietaire).
        ligneProprio.visibleProperty().bind(javafx.beans.binding.Bindings.size(choixProprio.getItems()).greaterThan(2));
        ligneProprio.managedProperty().bind(ligneProprio.visibleProperty());

        javafx.scene.layout.FlowPane filtres = new javafx.scene.layout.FlowPane(14, 6,
                boiteRecherche,
                groupeFiltre("Origine", oTout, oBc, oInv),
                groupeFiltre("Type", tTout, tSols, tMurs),
                ligneProprio, effacerFiltres);
        filtres.setAlignment(Pos.CENTER_LEFT);
        filtres.setRowValignment(javafx.geometry.VPos.CENTER);
        // la recherche prend une ligne entiere en fenetre etroite, 240 px sinon
        boiteRecherche.prefWidthProperty().bind(javafx.beans.binding.Bindings.min(240, filtres.widthProperty()));
        boiteRecherche.setMinWidth(Region.USE_PREF_SIZE);

        // ---- 3. le tableau
        tableSalle.setPrefHeight(420);
        tableSalle.setMinHeight(160);
        VBox.setVgrow(tableSalle, Priority.ALWAYS);
        // En etroit, les colonnes secondaires se cachent : jamais de barre en largeur.
        TableColumn<Ligne, ?> colType = tableSalle.getColumns().get(3),
                colQui = tableSalle.getColumns().get(5), colPrix = tableSalle.getColumns().get(6);
        javafx.beans.binding.BooleanBinding large = tableSalle.widthProperty().greaterThan(520),
                moyen = tableSalle.widthProperty().greaterThan(400);
        colType.visibleProperty().bind(large);
        // « Poseur » : n'a de sens qu'avec plusieurs poseurs, comme le filtre.
        colQui.visibleProperty().bind(ligneProprio.visibleProperty().and(large));
        colPrix.visibleProperty().bind(moyen);

        videTable = new Label("Aucun mobi à afficher.");
        videTable.getStyleClass().add("aide-vide");
        videTable.setWrapText(true);
        Button effacerVide = Icones.sur(new Button("Effacer les filtres"), Icones.VIDER);
        effacerVide.setOnAction(e -> effacerFiltresSalle());
        effacerVide.visibleProperty().bind(effacerFiltres.visibleProperty());
        effacerVide.managedProperty().bind(effacerFiltres.visibleProperty());
        VBox placeholder = new VBox(8, videTable, effacerVide);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.setPadding(new Insets(12));
        tableSalle.setPlaceholder(placeholder);

        VBox blocMobis = Ui.bloc("Mobis", filtres, tableSalle,
                Ui.aide("La liste suit l'appart en temps réel. Clique une ligne pour l'allumer dans l'appart, "
                        + "un intitulé de colonne pour trier. Survole la colonne BC pour son sens."));
        // Le tableau prend toute la hauteur restante (bloc, boite, tableau).
        VBox.setVgrow(blocMobis, Priority.ALWAYS);
        if (blocMobis.getChildren().size() > 1) VBox.setVgrow(blocMobis.getChildren().get(1), Priority.ALWAYS);

        // ---- 4. les actions
        Button copier = Icones.sur(new Button("Copier la liste"), Icones.DUPLIQUER);
        copier.setOnAction(e -> copier(tableSalle, "Mon appart"));
        copier.getStyleClass().add("primaire");
        copier.setMinWidth(Region.USE_PREF_SIZE);
        copier.setTooltip(bulle("Copie le tableau filtré, prêt à coller : Sols puis Muraux, « - Nom ×Quantité », "
                + "sans les mobis techniques (dalles magiques, contrôleur invisible…)."));

        Button listeBc = Icones.sur(new Button("Liste des mobis BC"), Icones.LISTE);
        listeBc.setMinWidth(Region.USE_PREF_SIZE);
        listeBc.setTooltip(bulle("Copie les mobis posés depuis le BC, regroupés par nom (Sols puis Muraux), "
                + "prêts à envoyer à qui les remplacera."));
        listeBc.setOnAction(e -> copierListeBc());

        // Remplacer des mobis (BC ou les miens) par ceux d'autres personnes : RemplacerParZone
        Button remplacerMiens = Icones.sur(new Button("Remplacer des mobis…"), Icones.DEPLACER);
        remplacerMiens.setMinWidth(Region.USE_PREF_SIZE);
        remplacerMiens.setTooltip(bulle("Des mobis d'autres personnes prennent la place des mobis du même type : "
                + "ceux du BC ou les tiens."));
        remplacerMiens.setOnAction(e -> RemplacerParZone.lancer(remplacerMiens.getScene() == null
                || remplacerMiens.getScene().getStylesheets().isEmpty() ? null
                : remplacerMiens.getScene().getStylesheets().get(0)));
        javafx.scene.layout.FlowPane actions = Ui.boutons(copier, listeBc, remplacerMiens);

        contenuSalle = new VBox(10, enTete, blocLimites, blocMobis, actions);
        contenuSalle.setFillWidth(true);
        VBox.setVgrow(contenuSalle, Priority.ALWAYS);

        // ---- hors d'un appart, ou premier chargement : un etat clair a la place
        videTitre = Ui.valeur("Pas dans un appart");
        videTitre.setWrapText(true);
        videTexte = Ui.discret("Entre dans un appart : ses mobis s'affichent ici tout seuls.");
        videTexte.setMaxWidth(320);
        videTexte.setAlignment(Pos.CENTER);
        videTexte.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        videCharge = new ProgressIndicator();
        videCharge.setPrefSize(22, 22);
        videCharge.setMaxSize(22, 22);
        videCharge.setVisible(false); videCharge.setManaged(false);
        etatVide = new VBox(8, videCharge, videTitre, videTexte);
        etatVide.setAlignment(Pos.CENTER);
        etatVide.setPadding(new Insets(28, 8, 28, 8));
        etatVide.setVisible(false); etatVide.setManaged(false);

        VBox v = new VBox(10, contenuSalle, etatVide, etatSalle);
        VBox.setVgrow(contenuSalle, Priority.ALWAYS);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        return v;
    }

    // champs du volet « Mon appart » (en-tete, etats vides)
    private Label chSols, chMurs, chBc, chValeur, chNoms, videTable, videTitre, videTexte;
    private Button effacerFiltres;
    private ProgressIndicator videCharge;
    private VBox etatVide;
    /** Voyant -> sa pastille dans l'en-tete de « Mon appart ». */
    private static final Map<Ui.Voyant, Pastille> miroirs =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Un chiffre cle de l'en-tete : petite etiquette arrondie, avec sa bulle. */
    private static Label chiffre(String aide) {
        Label l = new Label("");
        l.getStyleClass().addAll("badge", "chiffre-appart");
        l.setMinWidth(Region.USE_PREF_SIZE);
        if (aide != null && !aide.isEmpty()) l.setTooltip(bulle(aide));
        return l;
    }

    /** Les chiffres de tout l'appart (pas du tableau filtre). Fil FX. */
    private void majChiffres(int sols, int murs, boolean fd) {
        if (chSols == null) return;
        chSols.setText(sols + (sols > 1 ? " sols" : " sol"));
        chMurs.setText(Ui.accorder(murs + " mural(aux)"));
        long total = 0; int sansPrix = 0;
        for (Ligne l : brutSalle) { if (l.prix >= 0) total += l.prix; else sansPrix++; }
        boolean connu = total > 0;
        chValeur.setVisible(connu); chValeur.setManaged(connu);
        if (connu) {
            chValeur.setText("≈ " + java.text.NumberFormat.getIntegerInstance(Locale.FRANCE).format(total) + " c");
            chValeur.getTooltip().setText(Ui.accorder("Valeur estimée : total de la colonne Prix pour tout l'appart"
                    + (sansPrix > 0 ? ", sans " + sansPrix + " sorte(s) de mobi(s) au prix inconnu." : ".")));
        }
        chNoms.setVisible(!fd); chNoms.setManaged(!fd);
    }

    /** La rangee de pastilles n'existe que si l'une d'elles a quelque chose a dire. */
    private static void majPastilles(javafx.scene.layout.FlowPane f) {
        boolean une = false;
        for (javafx.scene.Node n : f.getChildren()) une |= n.isVisible();
        f.setVisible(une); f.setManaged(une);
    }

    /** Un groupe de filtre : son nom en gras, puis ses choix ; il ne se coupe pas, la ligne passe dessous. */
    private static HBox groupeFiltre(String nom, javafx.scene.Node... choix) {
        HBox h = new HBox(8, Ui.etiquette(nom));
        h.getChildren().addAll(choix);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setMinWidth(Region.USE_PREF_SIZE);
        return h;
    }

    /** Un filtre est-il actif (recherche, origine, type, personne) ? */
    private boolean filtreActif() {
        return (rechercheSalle != null && rechercheSalle.getText() != null && !rechercheSalle.getText().isBlank())
                || !oTout.isSelected() || !tTout.isSelected()
                || (choixProprio.getValue() != null && !TOUT_LE_MONDE.equals(choixProprio.getValue()));
    }

    /** « Effacer les filtres » : tout revient a « Tout ». */
    private void effacerFiltresSalle() {
        oTout.setSelected(true);
        tTout.setSelected(true);
        choixProprio.setValue(TOUT_LE_MONDE);
        rechercheSalle.clear();
        filtrerSalle();
    }

    /** Texte du tableau vide et bouton « Effacer les filtres » (fil FX). */
    private void majVide() {
        boolean actif = filtreActif();
        if (effacerFiltres != null) { effacerFiltres.setVisible(actif); effacerFiltres.setManaged(actif); }
        if (videTable != null)
            videTable.setText(brutSalle.isEmpty() ? "Aucun mobi dans cet appart."
                    : "Aucun mobi ne correspond aux filtres.");
    }

    /**
     * Pastille compacte de l'en-tete : un rond de couleur et le nom, la raison
     * en bulle. Cachee quand tout va bien. absentEstAttente : « absent » veut
     * dire « demande en cours » (inventaire, catalogue), pas un echec.
     */
    private static final class Pastille extends HBox {
        private final javafx.scene.shape.Circle rond = new javafx.scene.shape.Circle(4);
        private final String nom;
        private final Tooltip aide = bulle("");
        private final boolean absentEstAttente;
        Pastille(String nom, boolean absentEstAttente) {
            super(5);
            this.nom = nom;
            this.absentEstAttente = absentEstAttente;
            Label n = new Label(nom);
            n.getStyleClass().add("pastille-nom");
            n.setMinWidth(Region.USE_PREF_SIZE);
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(rond, n);
            Tooltip.install(this, aide);
            setVisible(false); setManaged(false);
        }
        void regler(String niveau, String raison) {
            boolean ok = "ok".equals(niveau);
            boolean attente = "attente".equals(niveau) || absentEstAttente;
            rond.getStyleClass().setAll(ok ? "pastille-ok" : attente ? "pastille-attente" : "pastille-echec");
            aide.setText(nom + " : " + Ui.majuscule(raison));
            setVisible(!ok); setManaged(!ok);
        }
    }

    /**
     * Voyants des prerequis : couleur et raison en clair, rafraichis chaque
     * seconde. C'est ce qui manquait : avant, un chargement rate ne se voyait
     * qu'au moment ou une action echouait.
     */
    private void demarrerVoyants() {
        Thread t = new Thread(() -> {
            while (true) {
                try { majVoyants(); } catch (Throwable ignored) { }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-voyants");
        t.setDaemon(true);
        t.start();
    }

    private void majVoyants() {
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) {
            reglerDuo(vSalle, vSalle2, "absent", "L'Atelier n'est pas encore prêt");
            return;
        }

        NomSalle.installer();
        SelectionMur.installer();

        long so = SelectionMur.sortants(), en = SelectionMur.entrants();
        if (so == 0 && en == 0)
            regler(vPaquets, "absent", "Aucun paquet reçu — l'extension n'est pas branchée au flux");
        else
            regler(vPaquets, "ok", so + " envoyés · " + en + " reçus");

        EtatSalle s = gp.getFloorState();
        boolean dansSalle = (s != null && s.inRoom());
        if (s == null)         reglerDuo(vSalle, vSalle2, "absent", "État d'appart indisponible");
        else if (!dansSalle)   reglerDuo(vSalle, vSalle2, "absent", manqueSalle(s));
        else {
            // Le nom seulement : le modele (« model_a ») ne dit rien a personne.
            String n = NomSalle.nomValide(gp);
            reglerDuo(vSalle, vSalle2, "ok", (n == null || n.isEmpty()) ? "Dans un appart" : n);
        }

        boolean fd = false;
        try { fd = gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady(); }
        catch (Throwable ignored) { }
        regler(vFurni, fd ? "ok" : "attente",
                fd ? "Noms en français disponibles" : "Téléchargement depuis habbo.fr...");

        String ei = "?";
        try { ei = String.valueOf(gp.getInventory().getState()); } catch (Throwable ignored) { }
        if ("LOADED".equals(ei))       reglerDuo(vInv, vInv2, "ok", "Chargé");
        else if ("LOADING".equals(ei)) reglerDuo(vInv, vInv2, "attente", "Chargement en cours...");
        else                           reglerDuo(vInv, vInv2, "absent", "Demande automatique en cours...");

        String eb = "?";
        try { eb = String.valueOf(gp.getCatalog().getState()); } catch (Throwable ignored) { }
        if ("COLLECTED".equals(eb))              reglerDuo(vBc, vBc2, "ok", "Chargé");
        else if ("AWAITING_INDEX".equals(eb))    reglerDuo(vBc, vBc2, "attente", "Lecture de l'index...");
        else if ("COLLECTING_PAGES".equals(eb))  reglerDuo(vBc, vBc2, "attente", "Lecture des pages...");
        else                                     reglerDuo(vBc, vBc2, "absent", "Demande automatique en cours...");

        // Le contenu ne doit etre masque QUE par ce qui le rend inutilisable :
        // etre hors d'une salle. La furnidata ne change que les noms affiches, le
        // catalogue BC que la pose depuis le BC — ce sont des degradations, pas
        // des blocages. Exiger les trois masquait le contenu en permanence des
        // que l'un n'arrivait jamais.
        if (!dansSalle) {
            entreeSalle = 0;
        } else if (entreeSalle == 0) {
            entreeSalle = System.currentTimeMillis();
        }
        // On laisse quelques secondes aux voyants, puis le contenu passe devant.
        boolean attenteCourte = dansSalle && !fd
                && (System.currentTimeMillis() - entreeSalle) < 8000;
        montrerEtat(!dansSalle || attenteCourte, dansSalle);
    }

    /**
     * L'etat et le contenu s'excluent : tant que tout n'est pas charge, on ne
     * montre que les voyants ; des que c'est pret, ils disparaissent et le
     * contenu prend toute la place. Jamais les deux a la fois.
     */
    private String dernierEtat = null;

    private void montrerEtat(boolean enAttente, boolean dansSalle) {
        if (fenetreGelee) return;
        String cle = enAttente + "/" + dansSalle;
        if (cle.equals(dernierEtat)) return;
        dernierEtat = cle;
        Platform.runLater(() -> {
            if (blocEtat != null) {
                blocEtat.setVisible(false);   // les Prérequis en haut du volet le disent deja
                blocEtat.setManaged(false);
            }
            if (contenuSalle != null) {
                contenuSalle.setVisible(!enAttente);
                contenuSalle.setManaged(!enAttente);
            }
            // « Mon appart » : a la place du contenu, un etat clair (hors appart, ou chargement)
            if (etatVide != null) {
                etatVide.setVisible(enAttente);
                etatVide.setManaged(enAttente);
                videCharge.setVisible(dansSalle);
                videCharge.setManaged(dansSalle);
                videTitre.setText(dansSalle ? "Chargement de la liste des mobis…" : "Pas dans un appart");
                videTexte.setText(dansSalle ? "Les noms en français arrivent de habbo.fr, quelques secondes."
                        : "Entre dans un appart : ses mobis s'affichent ici tout seuls.");
            }
            if (contenuAppart != null) {
                contenuAppart.setVisible(!enAttente);
                contenuAppart.setManaged(!enAttente);
            }
            if (blocEtat2 != null) {
                blocEtat2.setVisible(false);
                blocEtat2.setManaged(false);
            }
        });
    }

    /** Regle les deux voyants de meme role, un par volet. */
    private static void regler(Ui.Voyant v, String niveau, String raison) {
        if (v == null) return;
        // Rafraichi chaque seconde : ne rien poster quand rien n'a change.
        String cle = niveau + "|" + raison;
        if (cle.equals(dejaRegle.put(v, cle))) return;
        Platform.runLater(() -> {
            v.regler(niveau, raison);
            Pastille p = miroirs.get(v);     // sa pastille dans l'en-tete de « Mon appart »
            if (p != null) p.regler(niveau, raison);
        });
    }

    private static final Map<Ui.Voyant, String> dejaRegle =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Dit CE QUI manque pour etre « dans une salle » (EtatSalle.manque), au lieu d'un message muet. */
    private static String manqueSalle(EtatSalle s) {
        String m = s.manque();
        return m == null ? "Pas dans un appart" : "Pas encore reçu : " + Character.toLowerCase(m.charAt(0)) + m.substring(1);
    }

    private void reglerDuo(Ui.Voyant a, Ui.Voyant b, String niveau, String raison) {
        regler(a, niveau, raison);
        regler(b, niveau, raison);
    }

    // ----------------------------------------------------- volet copier appart

    private Pane voletAppart() {
        salleActuelle2 = Ui.valeur("—");
        salleActuelle2.setWrapText(true);

        nomNouvelAppart = new TextField();
        nomNouvelAppart.setPromptText("Nom (par défaut : celui de l'appart)");
        nomNouvelAppart.setMaxWidth(Double.MAX_VALUE);
        nomNouvelAppart.setOnAction(e -> copierSalleVersAppart());

        // --- 1. copier : l'appart entier, ou une zone (choisie dans le jeu, grille a l'appui)
        ToggleGroup gEtendue = new ToggleGroup();
        etTout = radio("L'appart entier", gEtendue, true);
        etZone = radio("Une zone", gEtendue, false);
        coinsLbl = Ui.valeur(Zone.texte());
        coinsLbl.setWrapText(true);
        Zone.ecouter(() -> coinsLbl.setText(Zone.texte()));
        Zone.ecouter(this::suivreZone);
        Button choisirZone = Icones.sur(new Button("Choisir la zone dans le jeu"), Icones.CIBLE);
        choisirZone.setOnAction(e -> choisirZone());
        VBox blocZone = new VBox(6, coinsLbl, choisirZone);
        blocZone.visibleProperty().bind(etZone.selectedProperty());
        blocZone.managedProperty().bind(blocZone.visibleProperty());
        etZone.setOnAction(e -> { if (!Zone.definie()) choisirZone(); });

        cpSols  = new CheckBox("Sols");   cpSols.setSelected(true);
        cpMurs  = new CheckBox("Muraux");   cpMurs.setSelected(true);
        cpWired = new CheckBox("Wired");  cpWired.setSelected(true);

        Button copierSalle = Icones.sur(plein("Copier", e -> copierSalleVersAppart()), Icones.DUPLIQUER);
        copierSalle.getStyleClass().add("primaire");

        // --- 2. mes copies : une carte par copie (apercu, nom, contenu, date)
        choixAppart = new ComboBox<>();             // garde la selection (lue partout) ; la liste l'affiche
        choixAppart.valueProperty().addListener((o, a, b) -> { lireAppart(); majFloor(); });
        // la liste montre le dossier ouvert : ses sous-dossiers d'abord, puis ses copies
        ListView<String> liste = new ListView<>(elements);
        listeCopies = liste;
        liste.setPrefHeight(250);
        liste.setMinHeight(150);
        Label vide = new Label("Dossier vide.");
        vide.setWrapText(true);
        vide.getStyleClass().add("aide-vide");
        videCopies = vide;
        liste.setPlaceholder(vide);
        liste.setCellFactory(lv -> new CelluleCopie(lv));
        liste.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && !b.startsWith(DOS)) choixAppart.setValue(b);
        });
        choixAppart.valueProperty().addListener((o, a, b) -> {
            if (b != null && !b.equals(liste.getSelectionModel().getSelectedItem())) liste.getSelectionModel().select(b);
        });
        // Entree sur un dossier choisi au clavier : l'ouvrir (Retour sur Mac, Entree sous Windows)
        liste.setOnKeyPressed(e -> {
            String it = liste.getSelectionModel().getSelectedItem();
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && it != null && it.startsWith(DOS)) {
                naviguer(CopiesDossiers.dossier(it.substring(1)));
                e.consume();
            }
        });
        fil.setAlignment(Pos.CENTER_LEFT);
        fil.setRowValignment(javafx.geometry.VPos.CENTER);
        Button nouveauDossier = Ui.bouton(Icones.DOSSIER_NOUVEAU, "Nouveau dossier…");
        nouveauDossier.setOnAction(e -> nouveauDossier());

        cptAppart = new Label("--");
        cptAppart.getStyleClass().add("salle-compte");
        cptAppart.setWrapText(true);

        ToggleGroup source = new ToggleGroup();
        sInv   = radio("Inventaire", source, true);
        sBc    = radio("BC", source, false);
        sBcInv = radio("BC, puis inventaire", source, false);
        sInvBc = radio("Inventaire, puis BC", source, false);

        avecFloor = new CheckBox("Recréer aussi le floor d'origine (pose à la même place)");
        avecFloor.setWrapText(true);
        avecFloor.setSelected(true);

        Button poser = Icones.sur(plein("Coller ici", e -> collerAppart(false)), Icones.COLLER);
        poser.getStyleClass().add("primaire");
        Button completer = Icones.sur(plein("Compléter l'appart", e -> collerAppart(true)), Icones.COLLER);
        completer.setTooltip(bulle("Comme Coller ici, mais sans repartir de zéro : les mobis déjà à leur place "
                + "(case, hauteur, rotation) ne sont pas touchés, leur état est remis s'il le faut ; les mobis du même type "
                + "mal placés sont déplacés à la bonne place ; seul ce qui manque est posé. Les autres mobis de l'appart restent où ils sont."));
        completer.visibleProperty().bind(collageEnCours.not());
        completer.managedProperty().bind(collageEnCours.not());
        // Pendant un collage : « Arrêter » a la place de « Coller ici »
        Button arreter = plein("Arrêter le collage", e -> arreterCollage());
        // jamais declenche par une touche (Entree / Espace envoyees pendant que l'on joue)
        arreter.setFocusTraversable(false);
        arreter.visibleProperty().bind(collageEnCours);
        arreter.managedProperty().bind(collageEnCours);
        poser.visibleProperty().bind(collageEnCours.not());
        poser.managedProperty().bind(collageEnCours.not());
        boutonArreter = arreter;
        // Actions sur la copie choisie : des icones, avec leur bulle
        Button renommer = Icones.seul(Icones.CRAYON, "Renommer la copie");
        renommer.setOnAction(e -> renommerCopie(choixAppart.getValue()));
        Button supprimer = Icones.seul(Icones.CORBEILLE, "Supprimer la copie (définitif)");
        supprimer.setOnAction(e -> supprimerCopie(choixAppart.getValue()));
        Button apercu = Icones.seul(Icones.CAPTURE, "Reprendre l'aperçu : nouvelle photo de la copie choisie, "
                + "depuis l'appart où tu es (pour une zone : la zone choisie). "
                + "Utile pour les copies faites avant les aperçus.");
        apercu.getTooltip().setWrapText(true);
        apercu.getTooltip().setMaxWidth(300);
        apercu.setOnAction(e -> reprendreApercu(liste));
        for (Button b : new Button[]{poser, completer, renommer, supprimer, apercu})
            b.disableProperty().bind(choixAppart.valueProperty().isNull());
        HBox actions = new HBox(4, renommer, apercu, supprimer);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox sousListe = new HBox(8, cptAppart, actions);
        sousListe.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cptAppart, Priority.ALWAYS);
        cptAppart.setMaxWidth(Double.MAX_VALUE);

        // Source des mobis : les 4 choix restent affiches.
        VBox choixSource = new VBox(4, sInv, sInvBc, sBc, sBcInv);
        choixSource.setPadding(new Insets(0, 0, 0, 4));

        Label salleLbl = Ui.etiquette("Appart actuel");
        salleLbl.setMinWidth(Region.USE_PREF_SIZE);
        HBox ligneSalle = new HBox(8, salleLbl, salleActuelle2);
        ligneSalle.setAlignment(Pos.BASELINE_LEFT);

        contenuAppart = new VBox(14,
                Ui.bloc("Copier",
                        ligneSalle,
                        Ui.ligne(etTout, etZone), blocZone,
                        Ui.ligne(cpSols, cpMurs, cpWired),
                        nomNouvelAppart,
                        copierSalle,
                        Ui.aide("L'appart entier garde aussi son floor. Une zone se choisit en cliquant deux cases "
                                + "dans le jeu (les mobis laissent passer le clic, la grille s'affiche). "
                                + "Une photo de l'appart (ou de la zone seule) est prise à chaque copie : "
                                + "elle s'affiche dans « Mes copies », clique-la pour l'agrandir.")),
                Ui.bloc("Mes copies",
                        fil,
                        liste,
                        sousListe,
                        Ui.boutons(nouveauDossier),
                        Ui.aide("Les copies d'un appart entier vont dans « Apparts », les zones dans « Zones » "
                                + "(ou dans le dossier ouvert, s'il est du bon côté). Clique un dossier pour l'ouvrir, "
                                + "le chemin en haut pour remonter. Glisse une copie sur un dossier (ou sur le chemin) "
                                + "pour l'y ranger ; clic droit : renommer, déplacer, supprimer. "
                                + "Clique un aperçu pour l'agrandir. Les icônes sous la liste renomment, "
                                + "reprennent la photo ou suppriment la copie choisie.")),
                Ui.bloc("Coller dans l'appart où je suis",
                        choixSource,
                        avecFloor,
                        poser, completer, boutonArreter,
                        Ui.aide("Sans le floor (ou pour une zone), clique dans le jeu la case du coin "
                                + "haut-gauche. Les mobis introuvables sont signalés et le reste est collé. "
                                + "« Compléter l'appart » reprend ce qui est déjà posé : ce qui est bien placé reste, "
                                + "le reste est déplacé, seul ce qui manque est posé.")));
        contenuAppart.setFillWidth(true);

        vSalle2 = new Ui.Voyant("Appart");
        vFurni2 = new Ui.Voyant("Liste des mobis");
        vInv2   = new Ui.Voyant("Inventaire");
        vBc2    = new Ui.Voyant("Catalogue BC");
        blocEtat2 = Ui.bloc("État", vSalle2, vFurni2, vInv2, vBc2);

        VBox v = new VBox(14, blocEtat2, contenuAppart, barreAppart, etatAppart);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        surveillerDossier();
        return v;
    }

    private CheckBox avecFloor;
    private volatile boolean choixZone = false, deuxiemeDit = false;

    /** Zone de la copie : deux clics dans le jeu, guides par des messages du personnage. */
    private void choisirZone() {
        choixZone = true; deuxiemeDit = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    private void suivreZone() {
        if (!choixZone) return;
        if (!Zone.choixEnCours()) { choixZone = false; if (Zone.definie()) InfoJeu.consigne("Zone choisie : " + Zone.largeur() + " × " + Zone.longueur() + "."); return; }
        if (Zone.premierCoinChoisi() && !deuxiemeDit) { deuxiemeDit = true; InfoJeu.consigne("Choisis le deuxième point de la zone."); }
    }

    /** La case « floor » ne vaut que pour une copie d'appart entier (qui a son floor). */
    private void majFloor() {
        if (avecFloor == null) return;
        String nom = choixAppart.getValue();
        boolean a = false;
        if (nom != null) try {
            a = lirePreset(new File(dossierApparts(), nom + ".json")).has("atelierFloor");
        } catch (Throwable ignored) { }
        avecFloor.setDisable(!a);
        if (!a) avecFloor.setSelected(false); else avecFloor.setSelected(true);
    }

    /** Vignettes des apercus, par fichier et date (relues si la photo change). */
    private final Map<String, javafx.scene.image.Image> vignettes = new HashMap<>();

    /** La photo de la copie (ou un cadre vide) ; un clic l'ouvre en grand devant le jeu. */
    private javafx.scene.Node vignetteCopie(String nom, Control lv) {
        File png = ApercuPreset.de(new File(dossierApparts(), nom + ".json"));
        StackPane cadre = new StackPane();
        cadre.setMinSize(88, 60); cadre.setPrefSize(88, 60); cadre.setMaxSize(88, 60);
        cadre.setStyle("-fx-background-color: rgba(0,0,0,0.08); -fx-background-radius: 4;");
        if (!png.isFile()) {
            // pas encore de photo : un appareil grise, l'icone « reprendre l'apercu » sous la liste en fait une
            javafx.scene.Node ic = Icones.petit(Icones.CAPTURE);
            ic.setOpacity(0.45);
            cadre.getChildren().add(ic);
            Tooltip.install(cadre, bulle("Pas encore d'aperçu."));
            return cadre;
        }
        javafx.scene.image.Image img = vignettes.computeIfAbsent(png.getPath() + "@" + png.lastModified(),
                k -> new javafx.scene.image.Image(png.toURI().toString(), 88 * 3, 60 * 3, true, true, true));
        javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
        iv.setFitWidth(88); iv.setFitHeight(60); iv.setPreserveRatio(true);
        cadre.getChildren().add(iv);
        cadre.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(cadre, bulle("Agrandir"));
        cadre.setOnMouseClicked(e -> {
            e.consume();
            String css = lv.getScene() == null || lv.getScene().getStylesheets().isEmpty()
                    ? null : lv.getScene().getStylesheets().get(0);
            OngletGalerie.Visionneuse.ouvrir(css, png);
        });
        return cadre;
    }

    /** Reprend la photo de la copie choisie depuis l'appart courant (zone : la zone choisie). */
    private void reprendreApercu(ListView<String> liste) {
        String nom = choixAppart.getValue();
        if (nom == null) return;
        File f = new File(dossierApparts(), nom + ".json");
        boolean zone;
        try { zone = !lirePreset(f).has("atelierFloor"); } catch (Throwable t) { zone = false; }
        if (zone && !Zone.definie()) { choisirZone(); direJeu("Choisis d'abord la zone : clique ses deux coins dans le jeu."); return; }
        final boolean z = zone;
        Salle.tache("Aperçu", () -> {
            String err = ApercuPreset.prendre(ApercuPreset.de(f), z);
            direJeu(err == null ? "Aperçu de « " + CopiesDossiers.court(nom) + " » repris." : err);
            Platform.runLater(liste::refresh);
        });
    }

    /** « 120 mobis · appart entier · 3 oct. 10:12 » (lu une fois par fichier et date). */
    private final Map<String, String> resumes = new HashMap<>();

    private String resumeCopie(String nom) {
        File f = new File(dossierApparts(), nom + ".json");
        String cle = nom + "@" + f.lastModified();
        return resumes.computeIfAbsent(cle, k -> {
            try {
                JSONObject o = lirePreset(f);
                JSONArray a = o.optJSONArray("furni"), b = o.optJSONArray("wallFurni");
                int n = (a == null ? 0 : a.length()) + (b == null ? 0 : b.length());
                String quand = new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.FRANCE).format(new Date(f.lastModified()));
                return n + " mobi(s) · " + (o.has("atelierFloor") ? "appart entier" : "zone") + " · " + quand;
            } catch (Throwable t) { return "Illisible"; }
        });
    }

    private void renommerCopie(String nom) {
        if (nom == null) return;
        String ancien = CopiesDossiers.court(nom);
        File src = new File(dossierApparts(), nom + ".json");
        TextInputDialog d = new TextInputDialog(ancien);
        d.setTitle("Renommer la copie");
        d.setHeaderText(null);
        d.setContentText("Nouveau nom :");
        devant(d);
        d.showAndWait().ifPresent(n -> {
            n = n.replaceAll("[<>:\"/\\\\|?*]", "-").trim();
            if (n.isEmpty() || n.equals(ancien)) return;
            if (CopiesDossiers.interne(n)) { note("Renommage impossible : un nom de copie ne commence pas par « _atelier »."); return; }
            File dest = new File(src.getParentFile(), n + ".json");
            if (dest.exists()) { note("Renommage impossible : une copie s'appelle déjà « " + n + " » dans ce dossier."); return; }
            if (!src.renameTo(dest)) { note("Renommage impossible pour « " + ancien + " »."); return; }
            File png = ApercuPreset.de(src);
            if (png.isFile() && !png.renameTo(ApercuPreset.de(dest))) Journal.debug("aperçu non renommé : " + png);
            note("« " + ancien + " » renommée en « " + n + " ».");
            final String nouveau = CopiesDossiers.relatif(dest);
            chargerListeApparts();
            Platform.runLater(() -> choixAppart.setValue(nouveau));
        });
    }

    private void supprimerCopie(String nom) {
        if (nom == null) return;
        String court = CopiesDossiers.court(nom);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer la copie « " + court + " » ? C'est définitif.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        devant(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        File f = new File(dossierApparts(), nom + ".json");
        if (!f.delete()) { note("Impossible de supprimer « " + court + " »."); return; }
        File png = ApercuPreset.de(f);
        if (png.isFile() && !png.delete()) Journal.debug("aperçu non supprimé : " + png);
        note("Copie « " + court + " » supprimée.");
        chargerListeApparts();
    }

    /**
     * La liste se tient a jour toute seule : on surveille la date du dossier des
     * copies et celle du dossier ouvert, ce qui evite un bouton « Recharger » a
     * cliquer apres chaque export.
     */
    /** Dates des dossiers de l'arbre des copies (4 niveaux), pour voir un ajout n'importe ou. */
    private static long signature(File d, int prof) {
        if (d == null || !d.isDirectory() || prof > 4) return 0;
        long s = d.lastModified();
        File[] l = d.listFiles(File::isDirectory);
        if (l != null) for (File f : l) s = s * 31 + signature(f, prof + 1);
        return s;
    }

    /** Dates et tailles des fichiers d'un dossier (copie reecrite sous le meme nom). */
    private static long signatureFichiers(File d) {
        long s = d.lastModified();
        File[] l = d.listFiles(File::isFile);
        if (l != null) for (File f : l) s = s * 31 + f.lastModified() + f.length();
        return s;
    }

    private void surveillerDossier() {
        Thread t = new Thread(() -> {
            String vue = "";
            while (true) {
                try {
                    File d = dossierApparts();
                    File c = courant;
                    // tous les dossiers (une copie ajoutee dans un sous-dossier, ex. Zones/Generes) et les
                    // fichiers du dossier ouvert (une copie reecrite sous le meme nom)
                    String m = signature(d, 0) + "|" + (c == null ? "" : c.getPath() + "@" + signatureFichiers(c));
                    if (!m.equals(vue)) {
                        vue = m;
                        Platform.runLater(this::chargerListeApparts);
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-dossier-apparts");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------- dossiers des copies

    /** Prefixe des dossiers dans la liste (caractere de controle 1, puis « Apparts/Noel ») ; le reste : des copies. */
    private static final String DOS = "\u0001";
    /** Contenu d'un glisser de copie : « atelier-copie:Apparts/Noel/Loft ». */
    private static final String GLISSE_COPIE = "atelier-copie:";
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String PREF_DOSSIER = "copies.dossier";
    private static final String FOND_DEPOT = "-fx-background-color: rgba(62,134,172,0.30);";

    /** Ce que montre « Mes copies » : dossiers (prefixe DOS) puis copies du dossier ouvert. */
    private final ObservableList<String> elements = FXCollections.observableArrayList();
    private ListView<String> listeCopies;
    private Label videCopies;
    /** Fil d'Ariane : « Mes copies › Apparts › Noel », chaque partie cliquable. */
    private final FlowPane fil = new FlowPane(2, 2);
    /** Dossier ouvert (retenu dans les preferences). */
    private volatile File courant;

    /** Le dossier ouvert, s'il existe encore (sinon celui retenu, sinon la racine). */
    private File ici() {
        File r = dossierApparts();
        if (courant == null) {
            String rel = PREFS.get(PREF_DOSSIER, "");
            File d = r;
            for (String p : rel.split("/")) if (!p.isEmpty() && !p.equals(".") && !p.equals("..")) d = new File(d, p);
            courant = d;
        }
        if (!courant.isDirectory() || !CopiesDossiers.dans(courant, r)) courant = r;
        return courant;
    }

    private void naviguer(File d) {
        File r = dossierApparts();
        if (d == null || !d.isDirectory() || !CopiesDossiers.dans(d, r)) d = r;
        courant = d;
        try { PREFS.put(PREF_DOSSIER, d.equals(r) ? "" : CopiesDossiers.relatif(d)); PREFS.flush(); } catch (Throwable ignored) { }
        chargerListeApparts();
        if (listeCopies != null) listeCopies.scrollTo(0);
    }

    /**
     * Boite de dialogue DEVANT la fenetre de l'Atelier (toujours au premier
     * plan) : rattachee a elle, et elle-meme au premier plan.
     */
    private <D extends Dialog<?>> D devant(D d) {
        javafx.stage.Window w = listeCopies == null || listeCopies.getScene() == null ? null : listeCopies.getScene().getWindow();
        if (w != null && d.getOwner() == null) d.initOwner(w);
        ((javafx.stage.Stage) d.getDialogPane().getScene().getWindow()).setAlwaysOnTop(true);
        return d;
    }

    private void succesCopies(String m) { Ui.succes(etatAppart, Ui.accorder(Ui.majuscule(m))); }

    private void erreurCopies(String m) { Ui.erreur(etatAppart, Ui.accorder(Ui.majuscule(m))); }

    /** « dans « Apparts › Noel » » pour un message. */
    private static String dansDossier(File d) { return " dans « " + CopiesDossiers.lisible(d) + " »"; }

    private void nouveauDossier() {
        File ici = ici();
        if (CopiesDossiers.arbre(ici) == null) {
            erreurCopies("Ouvre d'abord « Apparts » ou « Zones » : les dossiers se créent dedans.");
            return;
        }
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Nouveau dossier");
        d.setHeaderText(null);
        d.setContentText("Nom du dossier (dans « " + ici.getName() + " ») :");
        devant(d);
        d.showAndWait().ifPresent(brut -> {
            String nom = OngletGalerie.nomDeDossier(brut);
            if (nom.isEmpty()) { erreurCopies("Donne un nom au dossier."); return; }
            File n = new File(ici, nom);
            if (n.exists()) { erreurCopies("« " + nom + " » existe déjà ici."); return; }
            if (!n.mkdir()) { erreurCopies("Impossible de créer le dossier « " + nom + " »."); return; }
            Capture.rendre(n);
            succesCopies("Dossier « " + nom + " » créé" + dansDossier(ici) + ".");
            chargerListeApparts();
        });
    }

    private void renommerDossier(File d) {
        if (CopiesDossiers.fixe(d)) { erreurCopies("« " + d.getName() + " » est un dossier fixe : il ne se renomme pas."); return; }
        TextInputDialog dlg = new TextInputDialog(d.getName());
        dlg.setTitle("Renommer le dossier");
        dlg.setHeaderText(null);
        dlg.setContentText("Nouveau nom :");
        devant(dlg);
        dlg.showAndWait().ifPresent(brut -> {
            String nom = OngletGalerie.nomDeDossier(brut);
            if (nom.isEmpty()) { erreurCopies("Donne un nom au dossier."); return; }
            if (nom.equals(d.getName())) return;
            File n = new File(d.getParentFile(), nom);
            // « noel » -> « Noel » : meme dossier sur un disque qui ignore la casse
            if (n.exists() && !nom.equalsIgnoreCase(d.getName())) { erreurCopies("« " + nom + " » existe déjà ici."); return; }
            // la copie choisie, lue avant : elle garde sa place dans la liste apres le renommage
            String v = choixAppart.getValue();
            String ancien = CopiesDossiers.relatif(d), nouveau = CopiesDossiers.relatif(n);
            if (!d.renameTo(n)) { erreurCopies("Impossible de renommer le dossier « " + d.getName() + " »."); return; }
            if (v != null && v.startsWith(ancien + "/")) choixAppart.setValue(nouveau + v.substring(ancien.length()));
            File c = courant;
            if (c != null && (c.equals(d) || c.getPath().startsWith(d.getPath() + File.separator)))
                naviguer(new File(n, c.getPath().substring(d.getPath().length())));
            succesCopies("Dossier renommé en « " + nom + " ».");
            chargerListeApparts();
        });
    }

    /**
     * Supprime un dossier, SEULEMENT s'il est vide (sous-dossiers vides
     * compris) : jamais de copie perdue d'un clic. Les deux dossiers fixes,
     * jamais.
     */
    private void supprimerDossier(File d) {
        if (CopiesDossiers.fixe(d)) { erreurCopies("« " + d.getName() + " » est un dossier fixe : il ne se supprime pas."); return; }
        int[] c = new int[3];   // copies, autres fichiers, sous-dossiers
        CopiesDossiers.inventaire(d, c, 0);
        if (c[0] > 0) {
            erreurCopies("Le dossier « " + d.getName() + " » n'est pas vide : " + c[0] + " copie(s) dedans. "
                    + "Seul un dossier vide se supprime : déplace ou supprime d'abord ses copies.");
            return;
        }
        if (c[1] > 0) {
            erreurCopies("Le dossier « " + d.getName() + " » contient " + c[1] + " autre(s) fichier(s) : il n'est pas supprimé.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer le dossier vide « " + d.getName() + " » ?"
                + (c[2] > 0 ? Ui.accorder(" Ses " + c[2] + " sous-dossier(s) vide(s) aussi.").replace("Ses 1 ", "Son ") : ""),
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        devant(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        File cour = courant;
        boolean dedans = cour != null && (cour.equals(d) || cour.getPath().startsWith(d.getPath() + File.separator));
        if (!CopiesDossiers.effacer(d, 0)) {
            erreurCopies("Impossible de supprimer entièrement le dossier « " + d.getName() + " ».");
            chargerListeApparts();
            return;
        }
        if (dedans) naviguer(d.getParentFile());
        succesCopies("Dossier « " + d.getName() + " » supprimé.");
        chargerListeApparts();
    }

    /** Range la copie dans dir (meme arbre seulement) ; son apercu la suit. */
    private void deplacerCopie(File json, File dir) {
        if (json == null || dir == null || !json.isFile() || dir.equals(json.getParentFile())) return;
        if (!CopiesDossiers.accepte(dir, json)) {
            erreurCopies(CopiesDossiers.estZone(json)
                    ? "Une zone se range dans « Zones » ou ses dossiers, pas ailleurs."
                    : "Une copie d'appart se range dans « Apparts » ou ses dossiers, pas ailleurs.");
            return;
        }
        String court = json.getName().substring(0, json.getName().length() - 5);
        boolean choisie = CopiesDossiers.relatif(json).equals(choixAppart.getValue());
        File dest;
        try {
            dest = CopiesDossiers.deplacer(json, dir);
        } catch (Throwable t) {
            erreurCopies("La copie « " + court + " » n'a pas pu être déplacée (" + lisible(t) + ").");
            return;
        }
        String n = dest.getName().substring(0, dest.getName().length() - 5);
        succesCopies("Copie « " + court + " » rangée" + dansDossier(dir)
                + (n.equals(court) ? "" : " sous le nom « " + n + " »") + ".");
        if (choisie && dir.equals(ici())) choixAppart.setValue(CopiesDossiers.relatif(dest));
        chargerListeApparts();
    }

    /** « Deplacer vers… » : choisir un dossier dans l'arbre de la copie (Apparts ou Zones). */
    private void choisirDossierCopie(String rel) {
        File json = new File(dossierApparts(), rel + ".json");
        if (!json.isFile()) return;
        File tete = CopiesDossiers.arbreAttendu(json);
        TreeItem<File> rac = branche(tete, 0);
        if (rac.getChildren().isEmpty() && tete.equals(json.getParentFile())) {
            erreurCopies("Aucun dossier où la ranger : crée-en un dans « " + tete.getName() + " » avec « Nouveau dossier ».");
            return;
        }
        TreeView<File> tv = new TreeView<>(rac);
        tv.setCellFactory(x -> new TreeCell<>() {
            @Override protected void updateItem(File d, boolean vide) {
                super.updateItem(d, vide);
                if (vide || d == null) { setText(null); setGraphic(null); setOpacity(1); return; }
                setText(d.getName());
                setGraphic(Icones.petite(Icones.DOSSIER, 15, false));
                // son dossier actuel : grise
                setOpacity(d.equals(json.getParentFile()) ? 0.5 : 1);
            }
        });
        TreeItem<File> actuel = deplier(rac, json.getParentFile());
        if (actuel != null) tv.getSelectionModel().select(actuel);
        tv.setPrefSize(340, 300);

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Déplacer vers…");
        dlg.setHeaderText(null);
        ButtonType deplacer = new ButtonType("Déplacer", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(deplacer, ButtonType.CANCEL);
        Label l = new Label("Choisis le dossier où ranger « " + CopiesDossiers.court(rel) + " » :");
        l.setWrapText(true);
        dlg.getDialogPane().setContent(new VBox(8, l, tv));
        javafx.scene.Node ok = dlg.getDialogPane().lookupButton(deplacer);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            TreeItem<File> s = tv.getSelectionModel().getSelectedItem();
            return s == null || s.getValue().equals(json.getParentFile());
        }, tv.getSelectionModel().selectedItemProperty()));
        // double-clic sur un dossier : deplace tout de suite
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !ok.isDisabled()) { dlg.setResult(deplacer); dlg.close(); }
        });
        devant(dlg);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != deplacer) return;
        TreeItem<File> s = tv.getSelectionModel().getSelectedItem();
        if (s != null) deplacerCopie(json, s.getValue());
    }

    private static TreeItem<File> branche(File d, int prof) {
        TreeItem<File> t = new TreeItem<>(d);
        if (prof < 20) for (File s : CopiesDossiers.sousDossiers(d)) t.getChildren().add(branche(s, prof + 1));
        return t;
    }

    /** Deplie tout l'arbre ; rend l'element du dossier cherche. */
    private static TreeItem<File> deplier(TreeItem<File> t, File cherche) {
        t.setExpanded(true);
        TreeItem<File> r = t.getValue().equals(cherche) ? t : null;
        for (TreeItem<File> c : t.getChildren()) { TreeItem<File> x = deplier(c, cherche); if (r == null) r = x; }
        return r;
    }

    /** La copie glissee depuis la liste (null si ce n'en est pas une). */
    private static File copieGlissee(javafx.scene.input.DragEvent e) {
        String t = e.getDragboard().hasString() ? e.getDragboard().getString() : null;
        if (t == null || !t.startsWith(GLISSE_COPIE)) return null;
        File f = new File(dossierApparts(), t.substring(GLISSE_COPIE.length()) + ".json");
        return f.isFile() ? f : null;
    }

    /** Fil d'Ariane : chaque partie ouvre son dossier, et recoit une copie glissee. */
    private void construireFil() {
        fil.getChildren().clear();
        File r = dossierApparts();
        List<File> parts = new ArrayList<>();
        for (File x = ici(); x != null; x = x.getParentFile()) { parts.add(0, x); if (x.equals(r)) break; }
        for (int i = 0; i < parts.size(); i++) {
            File d = parts.get(i);
            boolean dernier = i == parts.size() - 1;
            if (i > 0) {
                Label s = new Label("›");
                s.setStyle("-fx-opacity: 0.55;");
                fil.getChildren().add(s);
            }
            Button b = new Button(i == 0 ? "Mes copies" : d.getName());
            if (i == 0) b.setGraphic(Icones.petite(Icones.DOSSIER, 15, false));
            b.setGraphicTextGap(5);
            b.setFocusTraversable(false);
            b.setMinWidth(Region.USE_PREF_SIZE);
            String base = "-fx-background-color: transparent; -fx-background-radius: 4; -fx-padding: 2 5 2 5; -fx-cursor: hand; "
                    + (dernier ? "-fx-font-weight: bold; -fx-text-fill: #3B382F;" : "-fx-text-fill: #2F6F92;");
            b.setStyle(base);
            if (!dernier) b.setTooltip(Ui.bulle(i == 0 ? "Ouvrir « Mes copies »."
                    : "Ouvrir « " + d.getName() + " ». Glisse une copie ici pour l'y ranger."));
            b.hoverProperty().addListener((o, x, h) -> b.setStyle(base + (h && !dernier ? "-fx-underline: true;" : "")));
            b.setOnAction(e -> naviguer(d));
            b.setOnDragOver(e -> {
                File p = copieGlissee(e);
                if (p != null && CopiesDossiers.accepte(d, p)) { e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE); e.consume(); }
            });
            b.setOnDragEntered(e -> {
                File p = copieGlissee(e);
                if (p != null && CopiesDossiers.accepte(d, p)) b.setStyle(base + "-fx-background-color: #3E86AC; -fx-text-fill: white;");
            });
            b.setOnDragExited(e -> b.setStyle(base));
            b.setOnDragDropped(e -> {
                File p = copieGlissee(e);
                if (p == null) return;
                Platform.runLater(() -> deplacerCopie(p, d));
                e.setDropCompleted(true);
                e.consume();
            });
            fil.getChildren().add(b);
        }
    }

    /** Une ligne de « Mes copies » : un dossier (clic : l'ouvrir) ou une copie (apercu, nom, contenu, date). */
    private final class CelluleCopie extends ListCell<String> {
        private final ListView<String> lv;

        CelluleCopie(ListView<String> lv) {
            this.lv = lv;
            // la cellule suit la largeur de la liste : le texte passe a la ligne, pas d'ascenseur de cote
            setPrefWidth(0);
            // un dossier s'ouvre d'un clic (le 2e clic d'un double-clic ne rouvre pas le dossier suivant)
            setOnMouseClicked(e -> {
                String it = getItem();
                if (it == null || !it.startsWith(DOS)) return;
                // CTRL+clic sur Mac = clic droit : il ouvre le menu, pas le dossier
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 1 && e.isStillSincePress()
                        && !e.isControlDown() && !e.isPopupTrigger()) {
                    naviguer(CopiesDossiers.dossier(it.substring(1)));
                    e.consume();
                }
            });
            // glisser une copie : sur un dossier de la liste ou du chemin en haut
            setOnDragDetected(e -> {
                String it = getItem();
                if (it == null || it.startsWith(DOS)) return;
                javafx.scene.input.Dragboard db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                ClipboardContent c = new ClipboardContent();
                c.putString(GLISSE_COPIE + it);
                db.setContent(c);
                e.consume();
            });
            setOnDragOver(e -> {
                File d = dossierIci(), p = copieGlissee(e);
                if (d != null && p != null && CopiesDossiers.accepte(d, p)) { e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE); e.consume(); }
            });
            setOnDragEntered(e -> {
                File d = dossierIci(), p = copieGlissee(e);
                if (d != null && p != null && CopiesDossiers.accepte(d, p)) setStyle(FOND_DEPOT);
            });
            setOnDragExited(e -> setStyle(""));
            setOnDragDropped(e -> {
                File d = dossierIci(), p = copieGlissee(e);
                if (d == null || p == null) return;
                Platform.runLater(() -> deplacerCopie(p, d));
                e.setDropCompleted(true);
                e.consume();
            });
        }

        /** Le dossier de cette ligne (null pour une copie). */
        private File dossierIci() {
            String it = getItem();
            return it == null || !it.startsWith(DOS) || isEmpty() ? null : CopiesDossiers.dossier(it.substring(1));
        }

        @Override protected void updateItem(String nom, boolean vide) {
            super.updateItem(nom, vide);
            setStyle("");
            if (vide || nom == null) { setText(null); setGraphic(null); setContextMenu(null); return; }
            setText(null);
            if (nom.startsWith(DOS)) {
                File d = CopiesDossiers.dossier(nom.substring(1));
                setGraphic(carteDossier(d));
                setContextMenu(menuDossier(d));
                return;
            }
            Label n = new Label(CopiesDossiers.court(nom));
            n.setStyle("-fx-font-weight: bold;");
            n.setWrapText(true);
            String[] r = Ui.accorder(resumeCopie(nom)).split(" · ");
            Label d = Ui.discret(r.length >= 2 ? Ui.majuscule(r[0] + " · " + r[1]) : r[0]);
            d.setStyle("-fx-font-style: normal; -fx-opacity: 0.7; -fx-font-size: 11px;");
            VBox texte = new VBox(2, n, d);
            if (r.length >= 3) {
                Label q = Ui.discret(r[2]);
                q.setStyle("-fx-font-style: normal; -fx-opacity: 0.55; -fx-font-size: 11px;");
                texte.getChildren().add(q);
            }
            texte.setAlignment(Pos.CENTER_LEFT);
            texte.setMinWidth(0);
            HBox.setHgrow(texte, Priority.ALWAYS);
            HBox h = new HBox(10, vignetteCopie(nom, lv), texte);
            h.setAlignment(Pos.CENTER_LEFT);
            h.setPadding(new Insets(2, 0, 2, 0));
            setGraphic(h);
            setContextMenu(menuCopie(nom));
        }
    }

    /** Ligne d'un dossier : une icone de dossier, son nom, ce qu'il contient. */
    private javafx.scene.Node carteDossier(File d) {
        StackPane cadre = new StackPane();
        cadre.setMinSize(88, 60); cadre.setPrefSize(88, 60); cadre.setMaxSize(88, 60);
        cadre.setStyle("-fx-background-color: rgba(0,0,0,0.05); -fx-background-radius: 4;");
        javafx.scene.Node ic = Icones.petite(Icones.DOSSIER, 40, false);
        ic.setOpacity(0.6);
        ic.setMouseTransparent(true);
        cadre.getChildren().add(ic);
        Label n = new Label(d.getName());
        n.setStyle("-fx-font-weight: bold;");
        n.setWrapText(true);
        int nC = CopiesDossiers.compter(d, 0), nS = CopiesDossiers.sousDossiers(d).size();
        String resume = (nC == 0 ? "Aucune copie" : Ui.accorder(nC + " copie(s)"))
                + (nS == 0 ? "" : " · " + Ui.accorder(nS + " dossier(s)"));
        Label r = Ui.discret(resume);
        r.setStyle("-fx-font-style: normal; -fx-opacity: 0.7; -fx-font-size: 11px;");
        VBox texte = new VBox(2, n, r);
        if (CopiesDossiers.fixe(d)) {
            Label q = Ui.discret(d.getName().equals(CopiesDossiers.ZONES) ? "Copies de zones" : "Copies d'apparts entiers");
            q.setStyle("-fx-font-style: normal; -fx-opacity: 0.55; -fx-font-size: 11px;");
            texte.getChildren().add(q);
        }
        texte.setAlignment(Pos.CENTER_LEFT);
        texte.setMinWidth(0);
        HBox.setHgrow(texte, Priority.ALWAYS);
        HBox h = new HBox(10, cadre, texte);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(2, 0, 2, 0));
        h.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(h, bulle("Ouvrir « " + d.getName() + " »."
                + (CopiesDossiers.fixe(d) ? "" : " Clic droit : renommer, supprimer.")));
        return h;
    }

    private ContextMenu menuDossier(File d) {
        MenuItem ouvrir = new MenuItem("Ouvrir");
        ouvrir.setOnAction(e -> naviguer(d));
        if (CopiesDossiers.fixe(d)) return new ContextMenu(ouvrir);
        MenuItem renommer = new MenuItem("Renommer…");
        renommer.setOnAction(e -> renommerDossier(d));
        MenuItem suppr = new MenuItem("Supprimer le dossier");
        suppr.setOnAction(e -> supprimerDossier(d));
        return new ContextMenu(ouvrir, renommer, new SeparatorMenuItem(), suppr);
    }

    private ContextMenu menuCopie(String rel) {
        MenuItem renommer = new MenuItem("Renommer…");
        renommer.setOnAction(e -> renommerCopie(rel));
        MenuItem vers = new MenuItem("Déplacer vers…");
        vers.setOnAction(e -> choisirDossierCopie(rel));
        MenuItem suppr = new MenuItem("Supprimer la copie");
        suppr.setOnAction(e -> supprimerCopie(rel));
        return new ContextMenu(renommer, vers, new SeparatorMenuItem(), suppr);
    }

    /**
     * Pose l'appart choisi dans la salle courante.
     *
     * TAPIS DE DALLES (PoseTapis) : 1. les dalles magiques sous toutes les
     * cases des mobis de sol, au niveau du sol ; 2. chaque mobi de sol (wired
     * compris), du bas vers le haut, pose (inventaire / BC) puis mis a sa
     * hauteur (@altitude) ; 3. les muraux (PoseMuraux), puis les reglages des
     * wired, les fonds des publicites et les valeurs des variables des mobis
     * (ReglagesWired) ; 4. les dalles ramassees ; 5. verification, une
     * nouvelle tentative par mobi. Sans @altitude, les mobis en hauteur
     * passent par la dalle, un par un. Le
     * fichier est relu ici (CopieAppart) : c'est bien l'appart choisi qui est pose.
     */
    /** completer : on part de ce qui est deja dans la salle (CompleterAppart). */
    private void collerAppart(boolean completer) {
        // Pendant le collage, chaque message va aussi dans le chat du jeu :
        // on a les yeux sur le jeu, pas sur le volet.
        java.util.function.Consumer<String> dire = this::direJeu;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { dire.accept("Collage impossible : l'Atelier n'est pas encore prêt."); return; }
        String nom = choixAppart.getValue();
        if (nom == null) { dire.accept("Choisis d'abord un appart."); return; }

        EtatSalle s = gp.getFloorState();
        if (s == null || !s.inRoom()) { dire.accept("Collage impossible : tu n'es pas dans un appart."); return; }

        Generateur.Source src = sBc.isSelected()    ? Generateur.Source.BC
                              : sInv.isSelected()   ? Generateur.Source.INVENTAIRE
                              : sBcInv.isSelected() ? Generateur.Source.BC_PUIS_INVENTAIRE
                              : Generateur.Source.INVENTAIRE_PUIS_BC;
        // Lu ici, sur le fil FX : majFloor() peut changer la case pendant le collage.
        boolean floorVoulu = avecFloor == null || avecFloor.isSelected();

        Salle.tache("coller", () -> {
            // floor + pose : la salle se recharge, la fenetre ne doit pas basculer sur les prerequis
            gelerFenetre(true);
            try {
                File f = new File(dossierApparts(), nom + ".json");
                if (!f.isFile()) { dire.accept("Collage impossible : fichier introuvable (" + f.getName() + ")."); return; }
                JSONObject brut = lirePreset(f);
                CopieAppart cfg = CopieAppart.lire(brut);
                JSONObject floor = brut.optJSONObject("atelierFloor");

                // Une classe inconnue de la furnidata : on le dit avant de rien poser.
                if (furnidataPrete()) {
                    Furnidata fd = gp.getFurniDataTools();
                    for (CopieAppart.MobiSol pf : cfg.sols)
                        if (fd.getFloorTypeId(pf.classe) == null) {
                            dire.accept("Collage impossible : « " + pf.classe + " » inconnu du jeu.");
                            return;
                        }
                }

                // Une pose deja en cours.
                if (PoseCopie.occupee()) {
                    dire.accept("Collage impossible : une autre pose n'est pas finie. Attends-la, ou tape :abort dans le jeu.");
                    return;
                }

                int n = cfg.sols.size() + cfg.murs.size();
                gearth.extensions.parsers.HPoint racine, clic = null;
                if (floor != null && floorVoulu) {
                    // Copie d'un appart complet : son floor d'abord, puis tout
                    // revient a sa place d'origine, sans clic.
                    if (!appliquerFloor(floor, dire)) return;
                    racine = new gearth.extensions.parsers.HPoint(floor.optInt("x0", 0), floor.optInt("y0", 0));
                } else {
                    dire.accept("« " + CopiesDossiers.court(nom) + " » : clique dans le jeu la case où mettre le coin haut-gauche de l'appart. "
                            + "Ton avatar ne bougera pas.");
                    Empreinte.Boite boite = new Empreinte.Boite();       // taille de la copie, pour l'empreinte
                    for (CopieAppart.MobiSol pf : cfg.sols) boite.sol(pf.classe, pf.x, pf.y, pf.rotation);
                    for (CopieAppart.MobiMur pw : cfg.murs) boite.cases(pw.position.x(), pw.position.y(), 1, 1);
                    racine = Generateur.Dalle.attendreClic(120_000, boite.largeur(), boite.profondeur());
                    if (racine == null) { dire.accept("Collage impossible : pas de clic dans le jeu en 2 minutes."); return; }
                    // La case cliquee = coin haut-gauche des MOBIS copies. Une copie d'appart
                    // complet garde les positions depuis le coin (0,0) de l'appart d'origine :
                    // sans son floor, on les ramene a ce coin (sinon tout tombe hors du sol).
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                    for (CopieAppart.MobiSol pf : cfg.sols) { minX = Math.min(minX, pf.x); minY = Math.min(minY, pf.y); }
                    for (CopieAppart.MobiMur pw : cfg.murs) {
                        minX = Math.min(minX, pw.position.x()); minY = Math.min(minY, pw.position.y());
                    }
                    if (minX != Integer.MAX_VALUE && (minX != 0 || minY != 0)) {
                        clic = racine;
                        racine = new gearth.extensions.parsers.HPoint(racine.getX() - minX, racine.getY() - minY);
                        Journal.debug("collage « " + nom + " » : positions ramenées au coin des mobis (" + minX + "," + minY + ").");
                    } else clic = racine;
                }

                // Tapis de dalles (PoseTapis) : dalles sous toutes les cases des mobis de sol,
                // puis chaque mobi (wired compris), du bas vers le haut, pose puis mis a sa
                // hauteur (@altitude) ; puis les muraux et les reglages des wired ; enfin
                // les dalles ramassees et la verification.
                boolean avecWired = aDesWired(brut);
                boolean floorRemis = floor != null && floorVoulu;
                double ancre = cfg.ancre == null ? 0 : cfg.ancre;
                gearth.extensions.parsers.HPoint repere = clic != null ? clic : racine;   // case de reference des hauteurs
                double sol0 = Math.max(0, Salle.hauteurSol(repere.getX(), repere.getY()));
                Furnidata fdt = gp.getFurniDataTools();
                List<PoseTapis.Piece> voulues = new ArrayList<>();
                for (CopieAppart.MobiSol pf : cfg.sols) {
                    int x = racine.getX() + pf.x, y = racine.getY() + pf.y;
                    // floor de la copie remis tel quel : z est deja l'altitude absolue d'origine
                    double z = Generateur.arrondi(floorRemis ? Math.max(0, pf.z) : Math.max(0, pf.z - ancre + sol0));
                    int[] e = PoseTapis.emprise(pf.classe, pf.rotation);
                    String nomMobi = pf.nom;
                    if (nomMobi == null || nomMobi.isBlank())
                        try { nomMobi = Generateur.nomSol(fdt, pf.classe); } catch (Throwable ignored) { nomMobi = pf.classe; }
                    voulues.add(PoseTapis.Piece.nouveau(pf.classe, pf.etat, nomMobi, x, y, z, pf.rotation, e[0], e[1], pf.id));
                }
                // Completer : ce qui est deja a sa place reste, le meme type mal place est deplace
                CompleterAppart.Plan cp = completer ? CompleterAppart.plan(voulues, cfg, racine) : null;
                List<PoseTapis.Piece> sols = cp != null ? cp.pieces : voulues;
                CopieAppart cfgMurs = cfg;
                if (cp != null) {
                    cfgMurs = cfg.copie();
                    cfgMurs.murs = new ArrayList<>(cp.mursAPoser);
                    Journal.debug("compléter « " + nom + " » : sols " + cp.solsGardes + " gardés, " + cp.solsDeplaces
                            + " déplacés, " + cp.solsNouveaux + " à poser ; muraux " + cp.mursGardes + " gardés, "
                            + cp.mursDeplaces + " déplacés, " + cp.mursAPoser.size() + " à poser ; "
                            + cp.etats.size() + " états à remettre.");
                    if (sols.isEmpty() && cp.mursAPoser.isEmpty() && cp.mursADeplacer.isEmpty() && cp.etats.isEmpty()) {
                        dire.accept("« " + CopiesDossiers.court(nom) + " » est déjà complet : tout est à sa place, rien à faire.");
                        return;
                    }
                }
                final CopieAppart mursCfg = cfgMurs;
                // Tout est une seule action pour Ctrl+Z (les dalles n'y vont pas).
                collageArrete = false;
                dernierBilanMuraux = null;
                Avancement.Suivi av = Avancement.debut(completer ? "Compléter l'appart" : "Collage de l'appart", this::arreterCollage);
                avCollage = av;
                barreAppart.suivre(av);
                Platform.runLater(() -> collageEnCours.set(true));
                int salle0 = Groupes.salleCourante();
                // changement de salle : seulement une autre vraie salle, vue 2 fois de suite
                // (un rechargement de la salle donne un instant 0 / -1)
                int[] autreSalle = {0};
                java.util.function.BooleanSupplier stop = () -> {
                    if (collageArrete) return true;
                    int sc = Groupes.salleCourante();
                    if (sc > 0 && sc != salle0) {
                        if (++autreSalle[0] >= 2) { Journal.debug("collage : arrêt, autre salle (" + sc + ")."); return true; }
                    } else autreSalle[0] = 0;
                    return false;
                };
                long[] derniere = {0};
                PoseTapis.Suivi suivi = (k, tot, texte) -> {
                    String etape = Avancement.nomDe(texte);
                    av.texte(etape.equals("En cours") ? null : etape);
                    if (tot > 0) av.compte(k, tot);
                    long t = System.currentTimeMillis();
                    if (tot > 0 && k < tot && t - derniere[0] < 250) return;
                    derniere[0] = t;
                    note(texte);
                };
                PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(src, () -> collageArrete, this::note, suivi, false);
                int[] mursPoses = {0}, mursDeplaces = {0};
                int[] etatsFaux = {0};
                ReglagesWired.Bilan[] rw = {null, null};
                PoseTapis.Bilan[] tb = {null};
                List<PoseTapis.Piece> piecesFinales = sols;
                final gearth.extensions.parsers.HPoint coin = racine;
                Runnable etape3 = () -> {
                    // muraux : leur position, leur hauteur et leur decalage (variables du jeu)
                    if (cp != null && !cp.mursADeplacer.isEmpty() && !stop.getAsBoolean()) {
                        note("Muraux à déplacer…");
                        mursDeplaces[0] = CompleterAppart.deplacerMuraux(cp, stop);
                    }
                    if (!mursCfg.murs.isEmpty() && !stop.getAsBoolean()) {
                        note("Muraux…");
                        mursPoses[0] = poserMuraux(mursCfg, src, coin, stop);
                    }
                    // les reglages des wired, sur les wired poses (cle -> id reel)
                    if (avecWired && !stop.getAsBoolean()) {
                        note("Réglages des wired…");
                        Map<Integer, Integer> cles = new LinkedHashMap<>();
                        if (cp != null) cles.putAll(cp.cles);
                        for (PoseTapis.Piece p : piecesFinales) if (p.obtenu >= 0 && p.cle != -1) cles.put(p.cle, p.obtenu);
                        rw[0] = ReglagesWired.appliquer(cfg, cles, coin);
                    }
                };
                Historique.grouper(true);
                try {
                    tb[0] = PoseTapis.executer(sols, jeu, suivi, etape3);
                    // completer : l'etat des mobis gardes et deplaces
                    if (cp != null && !cp.etats.isEmpty() && !stop.getAsBoolean()) {
                        note("États…");
                        etatsFaux[0] = PoseDirecte.etats(gp, cp.etats, stop);
                    }
                    // les wired repris a la dalle a la verification (nouveaux ids) : leurs reglages
                    if (avecWired && !stop.getAsBoolean()) {
                        Map<Integer, Integer> repris = new LinkedHashMap<>();
                        for (PoseTapis.Piece p : sols)
                            if (p.parDalle && p.obtenu >= 0 && p.cle != -1 && Wired.estWired(p.classe)) repris.put(p.cle, p.obtenu);
                        if (!repris.isEmpty()) {
                            note("Réglages des wired repris…");
                            rw[1] = ReglagesWired.appliquer(cfg, repris, racine);
                        }
                    }
                    // tout a la fin : les dalles magiques posees par CE collage sont retirees
                    // (jamais celles deja dans l'appart) ; un nouvel Arreter pendant le retrait l'interrompt
                    int arrets0 = arretsCollage;
                    PoseTapis.ramasserLaissees(tb[0], jeu, () -> arretsCollage != arrets0, suivi);
                } finally {
                    Historique.grouper(false);
                    Platform.runLater(() -> collageEnCours.set(false));
                }
                PoseTapis.Bilan b = tb[0];
                boolean arrete = collageArrete || b.arrete;
                if (b.sortie) { av.fin(Avancement.Issue.PARTIEL); dire.accept("Collage interrompu : tu as changé d'appart."); return; }
                // Un seul bilan. Un manque ou une hauteur fausse en fait une erreur (Journal.ERREUR).
                b.arrete = arrete;
                String bilan = b.texte(completer ? "placé" : "posé");
                if (cp != null) bilan = "Compléter : " + CompleterAppart.resume(cp) + " ; "
                        + Character.toLowerCase(bilan.charAt(0)) + bilan.substring(1);
                bilan = bilan.substring(0, bilan.length() - 1)
                        + (cp != null && cp.mursDeplaces > mursDeplaces[0] && !arrete
                                ? " ; " + (cp.mursDeplaces - mursDeplaces[0]) + " mural(aux) pas arrivé(s) à sa place" : "")
                        + (!mursCfg.murs.isEmpty() ? " ; muraux : " + mursPoses[0] + "/" + mursCfg.murs.size()
                                + (mursPoses[0] < mursCfg.murs.size() && !arrete ? " (" + (mursCfg.murs.size() - mursPoses[0]) + " refusé(s)"
                                        + pasAuBcMuraux() + raisonsMuraux() + ")" : "")
                                + (dernierBilanMuraux != null && dernierBilanMuraux.etatsFaux > 0
                                        ? ", " + dernierBilanMuraux.etatsFaux + " pas dans le bon état" : "") : "")
                        + (b.etatsFaux + etatsFaux[0] > 0 ? " ; " + (b.etatsFaux + etatsFaux[0]) + " pas dans le bon état" : "");
                ReglagesWired.Bilan r0 = rw[0];
                if (r0 != null && !r0.possible)
                    bilan += " ; réglages des wired pas appliqués (" + r0.raison + ")";
                else if (r0 != null && r0.attendus > 0) {
                    int regles = r0.regles + (rw[1] != null ? rw[1].regles : 0);
                    int attendus = r0.attendus + (rw[1] != null ? rw[1].attendus : 0);
                    int rates = r0.rates + (rw[1] != null ? rw[1].rates : 0);
                    bilan += " ; " + regles + " wired réglé(s) sur " + attendus
                            + (rates > 0 ? ", " + rates + " pas confirmé(s)" : "")
                            + (r0.relusDifferents > 0 ? ", " + r0.relusDifferents + " relu(s) différent(s)" : "");
                }
                bilan += "." + (b.sansAltitude ? PoseTapis.SANS_ALTITUDE : "");
                av.fin(Ui.accorder(bilan));
                dire.accept(Ui.accorder(bilan));
            } catch (Throwable t) {
                Journal.debug("collage : " + t);
                dire.accept("Collage impossible : " + lisible(t) + ".");
            } finally {
                gelerFenetre(false);
                Avancement.Suivi a = avCollage;
                avCollage = null;
                if (a != null && !a.finie()) a.fin(Avancement.Issue.ECHEC);
            }
        });
    }

    /** Pendant un collage : la fenetre Apparts et les Prerequis ne changent pas d'aspect. */
    private static volatile boolean fenetreGelee = false;

    private void gelerFenetre(boolean oui) {
        fenetreGelee = oui;
        Prerequis.geler(oui);
        if (!oui) dernierEtat = null;          // rattrape l'etat reel a la fin
    }

    /** « , dont 2 pas au Builders Club » pour les muraux du dernier collage, ou "". */
    private String pasAuBcMuraux() {
        PoseMuraux.Bilan b = dernierBilanMuraux;
        return b == null || b.pasAuBc.isEmpty() ? "" : ", dont " + b.pasAuBc.size() + " pas au Builders Club";
    }

    /** « : raison donnée par le jeu » des muraux refuses du dernier collage, ou "". */
    private String raisonsMuraux() {
        PoseMuraux.Bilan b = dernierBilanMuraux;
        if (b == null || b.raisons.isEmpty()) return "";
        return " : " + String.join(", ", b.raisons.keySet());
    }

    /** Demande d'arret du collage en cours (pose hybride) : plus rien ne part ensuite. */
    private volatile boolean collageArrete = false;
    /** Nombre de demandes d'arret (un Arreter pendant le retrait final des dalles l'interrompt). */
    private volatile int arretsCollage = 0;
    /** Un collage tourne : « Arrêter le collage » remplace « Coller ici ». Fil JavaFX. */
    private final javafx.beans.property.BooleanProperty collageEnCours = new javafx.beans.property.SimpleBooleanProperty(false);
    private Button boutonArreter;

    /** Arrete le collage en cours (rafale ou reprise a la dalle). A brancher sur un bouton « Arrêter ». */
    void arreterCollage() {
        Journal.debug("collage : arrêt demandé (bouton « Arrêter le collage »).");
        collageArrete = true;
        arretsCollage++;
        PoseCopie.arreter();
    }

    /**
     * Les muraux d'une copie (PoseMuraux : position, altitude, decalage, etat,
     * variables). Rend le nombre de muraux poses. Sans message pendant la pose.
     */
    /** Le bilan des muraux du dernier collage (raisons de refus, etats faux), null sans. */
    private volatile PoseMuraux.Bilan dernierBilanMuraux;

    private int poserMuraux(CopieAppart cfg, Generateur.Source src, gearth.extensions.parsers.HPoint racine,
                            java.util.function.BooleanSupplier stop) {
        Moteur gp = AtelierLauncher.moteur();
        PoseMuraux pm = gp == null ? null : gp.poseMuraux();
        if (pm == null) return 0;
        if (!PoseCopie.prendre()) { Journal.debug("muraux : une autre pose est en cours"); return 0; }
        try {
            PoseMuraux.Bilan b = pm.poser(cfg, racine, PoseCopie.source(src), null,
                    () -> stop.getAsBoolean() || PoseCopie.arretDemande());
            Journal.debug("muraux : " + b.texte());
            dernierBilanMuraux = b;
            return b.ids.size();
        } catch (Throwable t) {
            Journal.debug("muraux : " + t);
            return 0;
        } finally {
            PoseCopie.rendre();
        }
    }

    /** Le preset a-t-il des reglages wired (que seul le moteur de pose sait poser) ? */
    private static boolean aDesWired(JSONObject brut) {
        JSONObject w = brut.optJSONObject("wired");
        if (w == null) return false;
        for (String k : w.keySet()) { JSONArray a = w.optJSONArray(k); if (a != null && a.length() > 0) return true; }
        return false;
    }

    /**
     * Applique le floor enregistre avec l'appart (UpdateFloorProperties), puis
     * attend que la salle se recharge avec le nouveau plan. false = abandon
     * (la raison est dite).
     */
    /**
     * Meme plan, aux lignes et colonnes vides de la fin pres : le jeu les retire
     * quand il enregistre le floor (un plan envoye avec une marge revient sans).
     */
    static boolean memePlan(String a, String b) {
        return rogne(a).equals(rogne(b));
    }

    private static String rogne(String plan) {
        if (plan == null) return "";
        List<String> l = new ArrayList<>(Arrays.asList(plan.replace("\r\n", "\n").replace('\r', '\n').split("\n")));
        while (!l.isEmpty() && l.get(l.size() - 1).replace("x", "").replace("X", "").isEmpty()) l.remove(l.size() - 1);
        int larg = 0;
        for (String r : l) {
            int k = r.length();
            while (k > 0 && (r.charAt(k - 1) == 'x' || r.charAt(k - 1) == 'X')) k--;
            larg = Math.max(larg, k);
        }
        StringBuilder sb = new StringBuilder();
        for (String r : l) {
            String t = r.length() > larg ? r.substring(0, larg) : r;
            while (t.length() < larg) t += "x";
            sb.append(t.toLowerCase(java.util.Locale.ROOT)).append('\n');
        }
        return sb.toString();
    }

    private boolean appliquerFloor(JSONObject f, java.util.function.Consumer<String> dire) {
        FloorModele m = FloorModele.depuisTexte(f.optString("plan", null));
        if (m == null) { dire.accept("Collage impossible : floor de l'appart illisible."); return false; }
        m.porteX = f.optInt("porteX", -1); m.porteY = f.optInt("porteY", -1);
        m.porteDir = f.optInt("porteDir", 2); m.porteConnue = f.optBoolean("porteConnue", m.porteX >= 0);
        m.hauteurMur = f.optInt("hauteurMur", -1);
        m.epMur = f.optInt("epMur", 0); m.epSol = f.optInt("epSol", 0);
        // Deja le meme floor ENTIER (plan, porte, hauteur des murs, epaisseurs) : rien a renvoyer.
        // Le plan seul ne suffit pas : la hauteur des murs et les epaisseurs suivent aussi l'appart copie.
        try {
            FloorReseau.installer();
            FloorReseau.demanderPorte();
            Salle.sommeil(700);
            FloorSession.Lecture l = FloorSession.lire();
            FloorModele a = l.modele;
            if (a != null && memePlan(a.texte(), m.texte()) && a.porteX == m.porteX && a.porteY == m.porteY
                    && a.porteDir == m.porteDir && (m.hauteurMur < 0 || a.hauteurMur == m.hauteurMur)
                    && a.epMur == m.epMur && a.epSol == m.epSol) {
                dire.accept("Le floor est déjà le bon (plan, murs, épaisseurs) : je passe directement aux mobis.");
                return true;
            }
        } catch (Throwable ignored) { }
        if (!m.porteConnue || !m.existe(m.porteX, m.porteY)) {
            dire.accept("Collage impossible : porte du floor inconnue (recopie l'appart d'origine avec l'Atelier).");
            return false;
        }
        FloorReseau.installer();
        long t0 = System.currentTimeMillis();
        if (!FloorReseau.envoyerPlan(m)) { dire.accept("Collage impossible : envoi du floor au jeu impossible."); return false; }
        for (int i = 0; i < 100; i++) {          // 15 s au plus
            Salle.sommeil(150);
            if (FloorReseau.erreurRecue > t0) {
                dire.accept("Collage impossible : le jeu a refusé le floor (" + FloorReseau.erreur + ").");
                return false;
            }
            EtatSalle e = Salle.etat();
            String p = e == null ? null : e.getRawFloorplan();
            FloorModele r = p == null ? null : FloorModele.depuisTexte(p);
            if (FloorReseau.planRecu > t0 && r != null && memePlan(r.texte(), m.texte())) {
                Salle.sommeil(2500);              // le temps que les mobis de la salle arrivent
                dire.accept("Floor appliqué. Je pose les mobis...");
                return true;
            }
        }
        dire.accept("Collage impossible : le floor n'est pas revenu du jeu en 15 s (droits de l'appart ?).");
        return false;
    }

    /**
     * Enregistre la salle courante (ou une zone) comme appart, par
     * l'enregistrement du moteur (EnregistrementCopie) : mobis au sol, muraux
     * et wired. Les wired de la zone pas encore lus le sont d'abord
     * (WiredLecteur.lireMaintenant). Pour un appart complet, le floor (plan,
     * porte, murs) est ajoute au fichier sous « atelierFloor » : la copie
     * n'enregistre qu'une empreinte du plan. Chaque etape est dite dans le
     * volet, dans le jeu et dans le terminal.
     */
    private void copierSalleVersAppart() {
        java.util.function.Consumer<String> dire = this::direJeu;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { dire.accept("Copie impossible : l'Atelier n'est pas encore prêt."); return; }
        EtatSalle s = gp.getFloorState();
        if (s == null || !s.inRoom()) { dire.accept("Copie impossible : tu n'es pas dans un appart."); return; }

        String saisi = nomNouvelAppart.getText() == null ? "" : nomNouvelAppart.getText().trim();
        if (saisi.isEmpty()) {
            // A defaut, le nom de la salle : plus parlant qu'un nom genere.
            String n = NomSalle.nomValide(gp);
            saisi = (n == null || n.isEmpty()) ? "Sans nom" : n;
        }
        boolean sols = cpSols.isSelected(), murs = cpMurs.isSelected(), wired = cpWired.isSelected();
        if (!sols && !murs && !wired) { dire.accept("Coche au moins un type à copier."); return; }

        int x0, y0, lx, ly;
        boolean complet = !etZone.isSelected();
        if (complet) {
            x0 = 0; y0 = 0;
            lx = Math.max(1, s.getFloorplanWidth()); ly = Math.max(1, s.getFloorplanHeight());
        } else {
            if (!Zone.definie()) { choisirZone(); dire.accept("Choisis d'abord la zone : clique ses deux coins dans le jeu."); return; }
            x0 = Zone.minX(); y0 = Zone.minY();
            lx = Zone.largeur(); ly = Zone.longueur();
        }

        // Caracteres refuses par le moteur de l'Atelier dans un nom ; un nom deja pris recoit (2), (3)...
        String base = saisi.replaceAll("[<>:\"/\\\\|?*]", "-").trim();
        if (CopiesDossiers.interne(base)) base = base.substring(1);
        if (base.isEmpty()) base = "Sans nom";
        // Apparts ou Zones selon le type ; le dossier ouvert s'il est du bon cote
        final File dest = CopiesDossiers.dossierPour(!complet, ici());
        final String nomFinal = CopiesDossiers.libre(dest, base);
        nomNouvelAppart.setText(nomFinal);

        Salle.tache("copier", () -> {
            if (!copieEnCours.compareAndSet(false, true)) {
                dire.accept("Copie impossible : une autre copie n'est pas finie. Attends-la.");
                return;
            }
            try {
                EnregistrementCopie enr = gp.enregistrement();
                if (enr == null) { dire.accept("Copie impossible : liste des mobis pas encore chargée."); return; }

                String quoi = (sols ? "sols" : "") + (murs ? (sols ? " + " : "") + "muraux" : "")
                        + (wired ? " + wired" : "") + (complet ? " + floor" : "");
                dire.accept("Copie de « " + nomFinal + " » (" + (complet ? "appart complet" : "zone " + lx + "×" + ly)
                        + ", " + quoi + ")... ne touche pas à l'appart.");
                // Comme avant : sans les sols, seulement les muraux ; sinon les sols de la zone.
                EnregistrementCopie.Options opt = new EnregistrementCopie.Options();
                opt.sols = !(!sols && murs);
                opt.murs = murs;
                opt.wired = wired;
                EnregistrementCopie.Zone zone = new EnregistrementCopie.Zone(x0, y0, lx, ly);
                Journal.debug("copie : « " + nomFinal + " », zone " + x0 + "," + y0 + " " + lx + "×" + ly + ".");

                // Les wired de la zone pas encore lus : on les ouvre maintenant (sans fenetre).
                if (wired && opt.sols) {
                    List<Integer> aLire = new ArrayList<>();
                    for (HFloorItem it : EnregistrementCopie.solsDeLaZone(s, zone)) {
                        String cls = Salle.classe(it.getTypeId(), false);
                        if (ReglageWired.Genre.deClasse(cls) != null && WiredLecteur.config(it.getId()) == null) aLire.add(it.getId());
                    }
                    if (!aLire.isEmpty()) {
                        note("Lecture des wired : 0/" + aLire.size());
                        WiredLecteur.lireMaintenant(aLire, () -> false, (k, t) -> note("Lecture des wired : " + k + "/" + t));
                    }
                }

                EnregistrementCopie.Bilan eb = enr.enregistrer(nomFinal, zone, opt, dest, () -> false);
                if (eb.erreur != null || eb.fichier == null) {
                    dire.accept("Copie impossible : " + (eb.erreur == null ? "rien n'a été écrit" : eb.erreur) + ".");
                    return;
                }
                File fichier = eb.fichier;
                Capture.rendre(fichier);

                JSONObject o = new JSONObject(new String(Files.readAllBytes(fichier.toPath()), StandardCharsets.UTF_8));
                String floor = "";
                if (complet) {
                    FloorReseau.installer();
                    FloorReseau.demanderPorte();
                    Salle.sommeil(900);
                    FloorSession.Lecture l = FloorSession.lire();
                    if (l.modele == null) {
                        floor = " Échec pour le floor : " + (l.erreur == null ? "plan illisible" : l.erreur) + ".";
                    } else {
                        FloorModele fm = l.modele;
                        JSONObject f = new JSONObject();
                        f.put("plan", fm.texte());
                        f.put("porteX", fm.porteX); f.put("porteY", fm.porteY); f.put("porteDir", fm.porteDir);
                        f.put("porteConnue", fm.porteConnue);
                        f.put("hauteurMur", fm.hauteurMur);
                        f.put("epMur", fm.epMur); f.put("epSol", fm.epSol);
                        f.put("x0", x0); f.put("y0", y0);
                        o.put("atelierFloor", f);
                        // Ecriture atomique : surveillerDossier relit la liste pendant ce temps.
                        File tmp = new File(fichier.getParentFile(), fichier.getName() + ".tmp");
                        Files.write(tmp.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
                        try {
                            Files.move(tmp.toPath(), fichier.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                            Files.move(tmp.toPath(), fichier.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        floor = " Floor copié (" + fm.largeur + "×" + fm.longueur + (fm.porteConnue ? "" : ", porte inconnue") + ").";
                    }
                }
                JSONArray fs = o.optJSONArray("furni"), ws = o.optJSONArray("wallFurni");
                int nWired = 0;
                JSONObject w = o.optJSONObject("wired");
                if (w != null) for (String k : w.keySet()) { JSONArray a = w.optJSONArray(k); if (a != null) nWired += a.length(); }
                // l'apercu en image (appart entier, ou la zone seule)
                String ap = ApercuPreset.prendre(ApercuPreset.de(fichier), !complet);
                if (ap != null) floor += " Pas d'aperçu : " + ap;
                // la liste montre le dossier de la nouvelle copie, elle choisie
                String rel = CopiesDossiers.relatif(fichier);
                Platform.runLater(() -> {
                    if (!dest.equals(ici())) naviguer(dest); else chargerListeApparts();
                    if (choixAppart.getItems().contains(rel)) choixAppart.setValue(rel);
                });
                dire.accept("Appart « " + nomFinal + " » enregistré" + dansDossier(dest) + " : " + (fs == null ? 0 : fs.length()) + " sols, "
                        + (ws == null ? 0 : ws.length()) + " mural(aux), " + nWired + " réglages wired." + floor
                        + (eb.avertissement != null ? " " + eb.avertissement : ""));
            } catch (Throwable t) {
                Journal.debug("copie : " + t);
                dire.accept("Copie impossible : " + lisible(t) + ".");
            } finally {
                copieEnCours.set(false);
            }
        });
    }

    /** Une copie de salle est en cours (une seule a la fois). */
    private static final java.util.concurrent.atomic.AtomicBoolean copieEnCours = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Suit la salle en temps reel.
     *
     * Toutes les 300 ms, une empreinte bon marche de la salle : identifiant,
     * nombre de mobis et somme melangee des (id, type). Elle ne bouge que si
     * l'ENSEMBLE des mobis change (pose, retrait) : un deplacement ne change
     * rien a la liste, donc ne coute rien. Les changements sont regroupes
     * (350 ms de calme, 1 s au plus) : une pose de centaines de mobis met la
     * liste a jour une fois par seconde, sans saccade. Le comptage se fait
     * hors du fil JavaFX, avec un cache type -> nom/origine ; le tableau n'est
     * touche que si son contenu change.
     */
    private void demarrerSuiviSalle() {
        Thread t = new Thread(() -> {
            WiredLecteur.Regroupeur reg = new WiredLecteur.Regroupeur(350, 1000);
            int salleVue = Integer.MIN_VALUE;
            String nomVu = null;
            while (true) {
                try {
                    Moteur gp = AtelierLauncher.moteur();
                    EtatSalle s = gp == null ? null : gp.getFloorState();
                    boolean dedans = s != null && s.inRoom();
                    int salle = dedans ? s.getRoomId() : -1;
                    if (salle != salleVue) { salleVue = salle; reg.forcer(); }
                    // Les prix arrivent apres la liste (habbofurni, marche du jeu, prix fixes a la
                    // main) : on recompte quand habbofurni change, et toutes les 30 s s'il en manque.
                    long now = System.currentTimeMillis();
                    String prixVus = PrixSite.nombre() + "/" + PrixSite.misAJour();
                    if (!prixVus.equals(prixSignature) || (prixManquants && now - prixCalcule > 30_000)) {
                        prixSignature = prixVus;
                        prixCalcule = now;
                        if (dedans) reg.forcer();
                    }
                    List<HFloorItem> so = dedans ? s.getItems() : null;
                    List<HWallItem> mu = dedans ? s.getWallItems() : null;
                    // le catalogue BC qui arrive change la colonne « BC » : on recompte
                    String sig = empreinte(salle, so, mu, furnidataPrete()) + (catalogueBcPret(gp) ? "/bc" : "");
                    if (reg.tic(sig, System.currentTimeMillis())) calculerSalle(gp, so, mu, dedans);
                    // Le nom arrive parfois apres les mobis : suivi a part, sans recalcul.
                    String n = texteNomSalle(gp);
                    if (!n.equals(nomVu)) { nomVu = n; Platform.runLater(this::majNomSalle); }
                } catch (Throwable ignored) { }
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
        }, "atelier-suivi-salle");
        t.setDaemon(true);
        t.start();
    }

    /** Empreinte de l'ensemble des mobis, independante de l'ordre. Logique pure. */
    static String empreinte(int salle, List<HFloorItem> so, List<HWallItem> mu, boolean fd) {
        if (salle < 0) return "hors";
        long h = 0;
        if (so != null) for (HFloorItem it : so) h += WiredLecteur.melange(((long) it.getId() << 20) ^ it.getTypeId());
        if (mu != null) for (HWallItem it : mu) h += WiredLecteur.melange(~(((long) it.getId() << 20) ^ it.getTypeId()));
        return empreinte(salle, so == null ? 0 : so.size(), mu == null ? 0 : mu.size(), h, fd);
    }

    static String empreinte(int salle, int nSols, int nMurs, long somme, boolean fd) {
        return salle + "/" + nSols + "/" + nMurs + "/" + Long.toHexString(somme) + (fd ? "/fd" : "");
    }

    /** Cache type -> {nom, existe au BC}, cle (typeId << 1 | mur). Rempli seulement furnidata ET catalogue BC prets. */
    private final Map<Long, String[]> cacheTypes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Suivi des prix pour la colonne Prix (fil atelier-suivi-salle seulement). */
    private String prixSignature = null;
    private long prixCalcule = 0;
    private volatile boolean prixManquants = false;

    private String[] nomOrigine(Moteur gp, int typeId, boolean mur, boolean fd) {
        long k = ((long) typeId << 1) | (mur ? 1 : 0);
        String[] r = cacheTypes.get(k);
        if (r != null) return r;
        r = new String[]{ nom(gp, cls(gp, typeId, mur), mur), existeAuBc(gp, cls(gp, typeId, mur), mur) };
        if (fd && catalogueBcPret(gp)) cacheTypes.put(k, r);
        return r;
    }

    /**
     * Pose depuis le Builders Club : le serveur donne a ces mobis de VRAIS ids
     * dans la plage 0x7FFF0000.. (sols comme muraux). Nos marqueurs de grille
     * et nos fantomes, fictifs, n'en sont pas.
     */
    static boolean poseDepuisBc(int id) {
        return id >= Salle.ID_FICTIF_MIN && !Salle.idFictif(id);
    }

    private static boolean catalogueBcPret(Moteur gp) {
        try { return gp != null && gp.getCatalog() != null && gp.getCatalog().pret(); }
        catch (Throwable t) { return false; }
    }

    /** Compte la salle hors du fil JavaFX, puis met le tableau a jour. */
    private void calculerSalle(Moteur gp, List<HFloorItem> sols, List<HWallItem> murs, boolean dedans) {
        if (!dedans) {
            Platform.runLater(() -> {
                brutSalle.clear();
                majCompteBc(0);
                majChiffres(0, 0, true);
                majNomSalle();
                majProprietaires();
                filtrerSalle();
            });
            return;
        }
        boolean fd = furnidataPrete();
        if (!fd) cacheTypes.clear();
        Map<String, int[]> compte = new LinkedHashMap<>();
        Map<String, Integer> types = new HashMap<>();   // « nom|mur » -> typeId, pour le prix
        int nSols = 0, nMurs = 0, nBc = 0;
        if (sols != null) for (HFloorItem it : sols) {
            nSols++;
            String[] no = nomOrigine(gp, it.getTypeId(), false, fd);
            boolean bc = poseDepuisBc(it.getId());
            if (bc) nBc++;
            cle(compte, no[0], bc ? "BC" : INVENTAIRE, poseur(it.getOwnerName()), no[1], false);
            types.putIfAbsent(no[0] + "|sol", it.getTypeId());
        }
        if (murs != null) for (HWallItem it : murs) {
            nMurs++;
            String[] no = nomOrigine(gp, it.getTypeId(), true, fd);
            boolean bc = poseDepuisBc(it.getId());
            if (bc) nBc++;
            cle(compte, no[0], bc ? "BC" : INVENTAIRE, poseur(it.getOwnerName()), no[1], true);
            types.putIfAbsent(no[0] + "|mur", it.getTypeId());
        }
        final int poseesBc = nBc;
        List<Ligne> lignes = new ArrayList<>();
        versLignes(compte, lignes);
        Map<String, Integer> prixConnus = new HashMap<>();
        for (Ligne l : lignes) {
            Integer t = types.get(l.getNom() + "|" + l.getType());
            if (t == null) continue;
            int u = prixConnus.computeIfAbsent(l.getNom() + "|" + l.getType(),
                    k -> OngletValeur.prixUnitaire(gp, "mur".equals(l.getType()), t));
            if (u >= 0) l.prix = (long) u * l.getQuantite();
            if (fd) {   // l'icone du mobi (affichage seulement)
                boolean m = "mur".equals(l.getType());
                l.classe = cls(gp, t, m);
                l.revision = PrixTexte.revision(gp, m, l.classe);
            }
        }
        boolean manque = false;
        for (Ligne l : lignes) if (l.prix < 0) { manque = true; break; }
        prixManquants = manque;
        final int a = nSols, b = nMurs;
        Platform.runLater(() -> {
            brutSalle.clear();
            brutSalle.addAll(lignes);
            majCompteBc(poseesBc);
            majChiffres(a, b, fd);
            majProprietaires();
            filtrerSalle();
        });
    }

    private static String texteNomSalle(Moteur gp) {
        String n = (gp == null) ? null : NomSalle.nomValide(gp);
        String prop = NomSalle.proprietaire();
        return (n == null || n.isEmpty()) ? "—"
                : (prop == null || prop.isEmpty()) ? n : n + "  —  à " + prop;
    }

    /** « Nom de la salle — à Pseudo », ou un tiret hors d'une salle. */
    private void majNomSalle() {
        String txt = texteNomSalle(AtelierLauncher.moteur());
        if (salleActuelle  != null) salleActuelle.setText(txt);
        if (salleActuelle2 != null) salleActuelle2.setText(txt);
    }

    private void lireAppart() {
        brutAppart.clear();
        String nom = choixAppart.getValue();
        if (nom == null) {
            cptAppart.setText("--");
            if (tableAppart != null) tableAppart.setItems(FXCollections.observableArrayList());
            return;
        }

        JSONObject o;
        try {
            File f = new File(dossierApparts(), nom + ".json");
            o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Journal.erreur("Lecture impossible de la copie « " + nom + " »", e);
            return;
        }

        Moteur gp = AtelierLauncher.moteur();
        JSONArray sols = o.optJSONArray("furni"), murs = o.optJSONArray("wallFurni");
        int nSols = sols == null ? 0 : sols.length(), nMurs = murs == null ? 0 : murs.length();
        cptAppart.setText(nSols + " sols · " + nMurs + (nMurs > 1 ? " muraux · " : " mural · ") + (nSols + nMurs) + " mobis");

        Map<String, int[]> compte = new LinkedHashMap<>();
        agreger(gp, sols, false, compte);
        agreger(gp, murs, true, compte);
        versLignes(compte, brutAppart);
        // Ce tableau n'est pas (encore) affiche dans le volet : sans ce test,
        // la lecture s'arretait ici sur une NullPointerException.
        if (tableAppart != null) tableAppart.setItems(FXCollections.observableArrayList(brutAppart));

        // Le compte est deja affiche (cptAppart) : pas de message en plus.
        if (!furnidataPrete()) attendreFurnidata(this::lireAppart);
    }

    private void agreger(Moteur gp, JSONArray arr, boolean mur, Map<String, int[]> compte) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            String c = w.optString("className", null);
            if (c == null || c.isEmpty()) c = w.optString("name", null);
            // Un appart enregistre ne retient ni qui avait pose quoi, ni d'ou.
            cle(compte, nom(gp, base(c), mur), INVENTAIRE, "—", existeAuBc(gp, base(c), mur), mur);
        }
    }

    private static void cle(Map<String, int[]> m, String nom, String origine,
                            String proprietaire, String auBc, boolean mur) {
        int[] c = m.computeIfAbsent(nom + "\0" + origine + "\0" + proprietaire + "\0" + auBc,
                                    k -> new int[2]);
        c[mur ? 1 : 0]++;
    }

    /** Le pseudo du poseur tel que le serveur l'a donne, ou « inconnu ». */
    private static String poseur(String s) {
        return (s == null || s.trim().isEmpty()) ? INCONNU : s.trim();
    }

    private static void versLignes(Map<String, int[]> compte, List<Ligne> sortie) {
        List<Map.Entry<String, int[]>> l = new ArrayList<>(compte.entrySet());
        // A egalite, ordre alphabetique : stable d'une mise a jour a l'autre.
        l.sort((a, b) -> {
            int c = Integer.compare(b.getValue()[0] + b.getValue()[1],
                                    a.getValue()[0] + a.getValue()[1]);
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        for (Map.Entry<String, int[]> e : l) {
            String[] p = e.getKey().split("\0", 4);
            String nom = p[0];
            String orig = p.length > 1 ? p[1] : "?";
            String pr   = p.length > 2 ? p[2] : "—";
            String bc   = p.length > 3 ? p[3] : "?";
            if (e.getValue()[0] > 0)
                sortie.add(new Ligne(nom, e.getValue()[0], "sol", orig, pr, bc));
            if (e.getValue()[1] > 0)
                sortie.add(new Ligne(nom, e.getValue()[1], "mur", orig, pr, bc));
        }
    }

    private void filtrerSalle() {
        majVide();
        String orig = oBc.isSelected() ? "BC" : oInv.isSelected() ? INVENTAIRE : null;
        String type = tSols.isSelected() ? "sol" : tMurs.isSelected() ? "mur" : null;
        String qui = choixProprio.getValue();
        if (TOUT_LE_MONDE.equals(qui)) qui = null;
        String cherche = rechercheSalle == null || rechercheSalle.getText() == null ? ""
                : rechercheSalle.getText().trim().toLowerCase(Locale.FRANCE);
        List<Ligne> vue = new ArrayList<>();
        for (Ligne l : brutSalle)
            if ((orig == null || orig.equals(l.getOrigine()))
                    && (type == null || type.equals(l.getType()))
                    && (qui == null || qui.equals(l.getProprietaire()))
                    && (cherche.isEmpty() || l.getNom().toLowerCase(Locale.FRANCE).contains(cherche))) vue.add(l);
        // Le tri choisi en cliquant un intitule de colonne tient malgre le suivi en temps reel.
        if (tableSalle.getComparator() != null) vue.sort(tableSalle.getComparator());

        // Mise a jour EN PLACE et seulement si quelque chose a change : le suivi
        // en temps reel ne fait donc sauter ni l'ascenseur ni la selection.
        if (tableSalle.getItems() == null)
            tableSalle.setItems(FXCollections.observableArrayList());
        ObservableList<Ligne> items = tableSalle.getItems();
        if (memesLignes(items, vue)) return;

        Ligne sel = tableSalle.getSelectionModel().getSelectedItem();
        String choisi = sel == null ? null : cleLigne(sel, false);
        for (int i = 0; i < vue.size(); i++) {
            if (i < items.size()) {
                if (!cleLigne(items.get(i), true).equals(cleLigne(vue.get(i), true))) items.set(i, vue.get(i));
            } else items.add(vue.get(i));
        }
        if (items.size() > vue.size()) items.remove(vue.size(), items.size());

        if (choisi != null) {
            Ligne actuel = tableSalle.getSelectionModel().getSelectedItem();
            if (actuel == null || !choisi.equals(cleLigne(actuel, false))) {
                tableSalle.getSelectionModel().clearSelection();
                for (Ligne l : items)
                    if (choisi.equals(cleLigne(l, false))) { tableSalle.getSelectionModel().select(l); break; }
            }
        }
    }

    /** Cle d'une ligne : avec ou sans la quantite (la selection suit le mobi). */
    static String cleLigne(Ligne l, boolean avecQuantite) {
        // Avec la quantite, aussi le prix : une ligne dont le prix arrive est remplacee.
        return l.getNom() + "\0" + l.getType() + "\0" + l.getOrigine() + "\0" + l.getProprietaire() + "\0" + l.getAuBc()
                + (avecQuantite ? "\0" + l.getQuantite() + "\0" + l.prix + "\0" + l.revision : "");
    }

    static boolean memesLignes(List<Ligne> a, List<Ligne> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!cleLigne(a.get(i), true).equals(cleLigne(b.get(i), true))) return false;
        return true;
    }

    /**
     * Remplit la liste des poseurs a partir de ce qui vient d'etre lu.
     *
     * Le choix en cours est conserve s'il existe encore : sans ca, chaque
     * relecture automatique de la salle remettrait le filtre a zero.
     */
    private void majProprietaires() {
        java.util.TreeSet<String> noms = new java.util.TreeSet<>(
                String.CASE_INSENSITIVE_ORDER);
        for (Ligne l : brutSalle) noms.add(l.getProprietaire());

        String garde = choixProprio.getValue();
        List<String> items = new ArrayList<>();
        items.add(TOUT_LE_MONDE);
        items.addAll(noms);

        if (!items.equals(choixProprio.getItems())) {
            choixProprio.getItems().setAll(items);
            choixProprio.setValue(items.contains(garde) ? garde : TOUT_LE_MONDE);
        }
    }

    // ------------------------------------------------------------------ noms

    /** Valeurs de « existe au BC ». */
    static final String OUI = "Oui", NON = "Non";

    /**
     * Le TYPE de mobi existe-t-il au catalogue Builders Club (on peut en poser
     * d'autres) ? Comme la pose (OffresBc) : furnidata « bc » vrai ET produit
     * dans le catalogue BC. Catalogue pas encore lu : « bcofferid » propre au
     * mobi -> oui, sinon « ? ». Ne dit rien de l'origine de CE mobi-ci.
     */
    static String existeAuBc(Moteur gp, String classe, boolean mur) {
        try {
            Furnidata fd = gp == null ? null : gp.getFurniDataTools();
            if (fd == null || !fd.isReady() || classe == null) return "?";
            String c = base(classe);
            Furnidata.Mobi d = mur ? fd.mur(c) : fd.sol(c);
            if (d == null && c.contains("*")) d = mur ? fd.mur(c.substring(0, c.indexOf('*'))) : fd.sol(c.substring(0, c.indexOf('*')));
            if (d == null) return "?";
            if (!d.isBC) return NON;
            CatalogueBc cat = gp.getCatalog();
            if (cat != null && cat.pret()) {
                CatalogueBc.Produit p = mur ? cat.unProduitMur(d.id) : cat.produitSol(d.id);
                return p != null && p.offerId() > 0 ? OUI : NON;
            }
            return d.bcOfferId > 0 && !OffresBc.partagee(fd, d) ? OUI : "?";
        } catch (Throwable t) { return "?"; }
    }

    /** Origine affichee dans la colonne et comparee par le filtre. */
    private static final String INVENTAIRE = "Inventaire";

    /** L'export ajoute un index d'instance : "classe[0]", "[1]"... a retirer, sinon rien ne se regroupe. */
    private static String base(String cls) {
        if (cls == null) return null;
        int i = cls.indexOf('[');
        if (i > 0) cls = cls.substring(0, i);
        return cls.trim();
    }

    private static String cls(Moteur gp, int typeId, boolean mur) {
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady())
                return mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
        } catch (Throwable ignored) { }
        return "type " + typeId;
    }

    private static String nom(Moteur gp, String className, boolean mur) {
        String c = base(className);
        if (c == null || c.isEmpty()) return "Inconnu";
        if (gp != null) try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                String n = detail(fd, c, mur);
                if (n == null && c.contains("*"))
                    n = detail(fd, c.substring(0, c.indexOf('*')), mur);
                if (n != null) return n;
            }
        } catch (Throwable ignored) { }
        return c;
    }

    private static String detail(Furnidata fd, String c, boolean mur) {
        try {
            Object d = mur ? fd.getWallItemDetails(c) : fd.getFloorItemDetails(c);
            if (d == null) return null;
            String n = ((Furnidata.Mobi) d).name;
            return (n == null || n.isEmpty()) ? null : n;
        } catch (Throwable t) { return null; }
    }

    // --------------------------------------------------------------- Discord

    /** Format de la liste BC : « Sols (n) » puis « Muraux (n) », une ligne « - Nom ×Quantite » par mobi. */
    private void copier(TableView<Ligne> t, String titre) {
        if (t.getItems().isEmpty()) { noteSalle("Rien à copier : la liste est vide."); return; }
        // Les lignes sont detaillees par type et par poseur ; pour le client on
        // regroupe par mobi, sinon un meme meuble pose par deux personnes
        // apparaitrait deux fois.
        Map<String, Integer> sols = new TreeMap<>(ordreNoms()), murs = new TreeMap<>(ordreNoms());
        for (Ligne l : t.getItems()) {
            if (technique(l.classe)) continue;
            ("mur".equals(l.getType()) ? murs : sols).merge(l.getNom(), l.getQuantite(), Integer::sum);
        }
        int nSols = 0, nMurs = 0;
        for (int v : sols.values()) nSols += v;
        for (int v : murs.values()) nMurs += v;
        if (nSols + nMurs == 0) { noteSalle("Rien à copier : il n'y a que des mobis techniques."); return; }
        StringBuilder sb = new StringBuilder();
        section(sb, "Sols", sols, nSols, "\n");
        section(sb, "Muraux", murs, nMurs, "\n");
        ClipboardContent c = new ClipboardContent();
        c.putString(sb.toString().replaceFirst("^\n", ""));
        Clipboard.getSystemClipboard().setContent(c);
        noteSalle(Ui.accorder("Liste copiée : " + (sols.size() + murs.size()) + " ligne(s), " + (nSols + nMurs) + " objet(s)."));
    }

    /**
     * Mobis techniques laisses hors des listes copiees : dalles magiques,
     * controleur invisible, dalle chaise invisible, cache zone.
     */
    static boolean technique(String classe) {
        if (classe == null) return false;
        String c = classe.toLowerCase(Locale.ROOT);
        return c.startsWith("tile_stackmagic") || c.equals("conf_invis_control")
                || c.equals("room_invisible_sit_tile") || c.equals("conf_area_hide");
    }

    // ------------------------------------------------------------ mobis BC

    /** Chiffre « n BC » de l'en-tete (fil FX). */
    private void majCompteBc(int n) {
        if (compteBc == null) return;
        compteBc.setText(n + " BC");
        compteBc.getStyleClass().remove("chiffre-bc");
        if (n > 0) compteBc.getStyleClass().add("chiffre-bc");
    }

    /**
     * Liste des mobis de l'appart poses depuis le Builders Club (ids
     * 0x7FFF0000..), regroupes par nom (Salle.nom), Sols puis Muraux, tries par
     * nom, sans en-tete ni mobis techniques. Null hors d'une salle ou sans mobi BC.
     * nb[0] recoit le nombre de mobis.
     */
    private static String listeBc(String fin, int[] nb) {
        Map<String, Integer> sols = new TreeMap<>(ordreNoms()), murs = new TreeMap<>(ordreNoms());
        for (HFloorItem it : Salle.sols())
            if (it != null && poseDepuisBc(it.getId()) && !technique(Salle.classe(it.getTypeId(), false))) sols.merge(Salle.nom(it.getTypeId(), false), 1, Integer::sum);
        for (HWallItem it : Salle.murs())
            if (it != null && poseDepuisBc(it.getId()) && !technique(Salle.classe(it.getTypeId(), true))) murs.merge(Salle.nom(it.getTypeId(), true), 1, Integer::sum);
        int nSols = 0, nMurs = 0;
        for (int v : sols.values()) nSols += v;
        for (int v : murs.values()) nMurs += v;
        nb[0] = nSols + nMurs;
        if (nb[0] == 0) return null;

        StringBuilder sb = new StringBuilder();
        section(sb, "Sols", sols, nSols, fin);
        section(sb, "Muraux", murs, nMurs, fin);
        return sb.substring(fin.length());
    }

    private static void section(StringBuilder sb, String titre, Map<String, Integer> m, int total, String fin) {
        if (m.isEmpty()) return;
        sb.append(fin).append(titre).append(" (").append(total).append(')').append(fin);
        for (Map.Entry<String, Integer> e : m.entrySet())
            sb.append("- ").append(e.getKey()).append(" ×").append(e.getValue()).append(fin);
    }

    /** Ordre alphabetique francais (accents, majuscules) ; jamais deux noms confondus. */
    private static Comparator<String> ordreNoms() {
        java.text.Collator c = java.text.Collator.getInstance(Locale.FRANCE);
        c.setStrength(java.text.Collator.SECONDARY);
        return (a, b) -> { int r = c.compare(a, b); return r != 0 ? r : a.compareTo(b); };
    }

    /** Bouton « Liste des mobis BC » : dans le presse-papiers, confirme dans le jeu. */
    private void copierListeBc() {
        if (!Salle.dansUneSalle()) { InfoJeu.dire("Entre dans un appart pour lister ses mobis BC."); return; }
        int[] nb = {0};
        String txt = listeBc("\n", nb);
        if (txt == null) { InfoJeu.dire("Aucun mobi posé depuis le BC dans cet appart."); return; }
        ClipboardContent c = new ClipboardContent();
        c.putString(txt);
        Clipboard.getSystemClipboard().setContent(c);
        InfoJeu.dire(Ui.accorder("Liste de " + nb[0] + " mobi(s) BC copiée."));
    }

    // ---------------------------------------------------------------- apparts

    /**
     * Lit un appart enregistre et complete ce qu'exige le moteur de pose : un appart
     * venu d'ailleurs (ou d'une ancienne version) peut avoir des mobis sans
     * « id » ni « name », et PresetConfig s'arrete alors sur
     * « JSONObject["id"] not found ». Chaque mobi sans id en recoit un, au-dela
     * des ids existants pour ne pas en doubler un (le wired s'y refere).
     */
    static JSONObject lirePreset(File f) throws java.io.IOException {
        JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        int max = 0, ajoutes = 0;
        for (String cle : new String[]{"furni", "wallFurni"}) {
            JSONArray a = o.optJSONArray(cle);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m != null && m.has("id")) max = Math.max(max, m.optInt("id", 0));
            }
        }
        for (String cle : new String[]{"furni", "wallFurni"}) {
            JSONArray a = o.optJSONArray(cle);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m == null) continue;
                if (!m.has("id") || m.isNull("id")) { m.put("id", ++max); ajoutes++; }
                if (!m.has("name") || m.isNull("name")) m.put("name", m.optString("className", ""));
            }
        }
        if (ajoutes > 0)
            Journal.debug(f.getName() + " : " + ajoutes + " mobi(s) sans id, numérotés à la lecture.");
        return o;
    }

    /**
     * Le dossier des copies d'apparts : le meme qu'avant (« presets » dans le
     * dossier de l'ancien module, Dossiers.ancienModule : %APPDATA%\G-Presets\presets
     * sous Windows, ~/Library/Application Support/G-Presets/presets sur Mac, avec
     * user.home du processus). Cree au besoin ; les copies laissees a l'ancienne
     * place (« presets » a cote du jar) y sont recopiees une fois, comme avant.
     */
    static File dossierApparts() {
        File base = Dossiers.ancienModule();
        File d = base != null ? new File(base, "presets")
                : Paths.get(System.getProperty("user.home"), "Library", "Application Support", "G-Presets", "presets").toFile();
        d.mkdirs();
        migrerAnciennesCopies(d);
        migrerDepuisRoot(base);
        // les deux dossiers fixes (Apparts, Zones) ; les copies de la racine y sont rangees une fois
        CopiesDossiers.preparer(d);
        return d;
    }

    private static volatile boolean migrationRootFaite = false;

    /**
     * Sous sudo, les anciennes versions rangeaient tout (copies d'apparts et leurs
     * apercus, configs wired) chez root : /var/root/Library/Application Support/
     * G-Presets. On recopie ici, une fois, ce qui n'existe pas encore, sans rien
     * ecraser ; les originaux restent en place.
     */
    private static synchronized void migrerDepuisRoot(File cible) {
        if (migrationRootFaite || cible == null) return;
        migrationRootFaite = true;
        try {
            File racine = new File(System.getProperty("user.home"), "Library/Application Support/G-Presets");
            if (!racine.isDirectory() || racine.getCanonicalPath().equals(cible.getCanonicalPath())) return;
            int[] n = {0};
            recopier(racine, cible, n);
            if (n[0] > 0) Journal.info(Ui.accorder(n[0] + " fichier(s) de copies retrouvé(s) et remis dans ton dossier."));
        } catch (Throwable t) {
            Journal.debug("copies : reprise depuis le dossier de root impossible (" + t + ")");
        }
    }

    private static void recopier(File de, File vers, int[] n) throws java.io.IOException {
        File[] l = de.listFiles();
        if (l == null) return;
        if (!vers.isDirectory() && vers.mkdirs()) Capture.rendre(vers);
        for (File f : l) {
            if (f.getName().equals("Cache")) continue;          // le cache de connexion est repris ailleurs
            File g = new File(vers, f.getName());
            if (f.isDirectory()) { recopier(f, g, n); continue; }
            if (g.exists()) continue;
            // une copie deja rangee dans Apparts ou Zones ne revient pas a la racine
            if (CopiesDossiers.dejaRange(vers, f.getName())) continue;
            Files.copy(f.toPath(), g.toPath());
            Capture.rendre(g);
            n[0]++;
        }
    }

    private static volatile boolean migrationFaite = false;

    /** Recopie (sans ecraser) les copies du dossier « presets » voisin du jar, une seule fois. */
    private static synchronized void migrerAnciennesCopies(File cible) {
        if (migrationFaite) return;
        migrationFaite = true;
        try {
            File jar = new File(OngletApparts.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File ancien = jar.getParentFile() == null ? null : new File(jar.getParentFile(), "presets");
            File[] l = ancien == null || !ancien.isDirectory() ? null : ancien.listFiles((x, n) -> n.endsWith(".json"));
            if (l == null) return;
            int n = 0;
            for (File f : l) {
                File g = new File(cible, f.getName());
                if (g.exists() || CopiesDossiers.dejaRange(cible, f.getName())) continue;
                Files.copy(f.toPath(), g.toPath());
                n++;
            }
            if (n > 0) Journal.debug(n + (n > 1 ? " copies reprises de " : " copie reprise de ") + ancien + ".");
        } catch (Throwable t) {
            Journal.debug("copies : reprise de l'ancien dossier impossible (" + t + ")");
        }
    }

    private void chargerListeApparts() {
        // Toujours sur le fil graphique : un setAll() concurrent faisait planter
        // ListChangeBuilder pendant que la liste etait deja en cours de mise a jour.
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::chargerListeApparts);
            return;
        }
        // le dossier ouvert : ses sous-dossiers, puis ses copies (jamais les fichiers internes « _atelier… »)
        File ici = ici();
        List<String> elems = new ArrayList<>(), noms = new ArrayList<>();
        for (File d : CopiesDossiers.sousDossiers(ici)) elems.add(DOS + CopiesDossiers.relatif(d));
        for (File f : CopiesDossiers.copiesDe(ici)) noms.add(CopiesDossiers.relatif(f));
        elems.addAll(noms);
        construireFil();
        if (videCopies != null) {
            File a = CopiesDossiers.arbre(ici);
            videCopies.setText(a == null ? "Aucune copie pour l'instant : copie un appart au-dessus."
                    : "Dossier vide. " + (a.getName().equals(CopiesDossiers.ZONES)
                        ? "Une zone copiée pendant qu'il est ouvert arrive ici"
                        : "Un appart entier copié pendant qu'il est ouvert arrive ici")
                    + " ; tu peux aussi y glisser une copie par le chemin en haut.");
        }
        String garde = choixAppart.getValue();
        if (!elems.equals(elements)) elements.setAll(elems);
        if (!noms.equals(choixAppart.getItems())) choixAppart.getItems().setAll(noms);
        String v = garde != null && noms.contains(garde) ? garde : noms.isEmpty() ? null : noms.get(0);
        choixAppart.setValue(v);
        if (listeCopies != null) {
            if (v == null) listeCopies.getSelectionModel().clearSelection();
            else if (!v.equals(listeCopies.getSelectionModel().getSelectedItem())) listeCopies.getSelectionModel().select(v);
            listeCopies.refresh();      // le contenu des dossiers a pu changer
        }
    }

    // ----------------------------------------------------------------- outils

    /** Raison d'une exception, sans le nom de classe Java quand il y a un message. */
    private static String lisible(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static boolean furnidataPrete() {
        try {
            Moteur gp = AtelierLauncher.moteur();
            return gp != null && gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady();
        } catch (Throwable t) { return false; }
    }

    private volatile long entreeSalle = 0;
    private volatile boolean attente = false;

    private void attendreFurnidata(Runnable apres) {
        if (attente) return;
        attente = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 120 && !furnidataPrete(); i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { attente = false; return; }
            }
            attente = false;
            if (furnidataPrete()) Platform.runLater(apres);   // recalcul automatique : pas de message
        }, "atelier-furnidata");
        t.setDaemon(true);
        t.start();
    }

    private static Button plein(String texte,
                                javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(texte);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(h);
        return b;
    }

    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    private static javafx.scene.layout.Pane ligne(String etiquette, javafx.scene.Node... n) {
        javafx.scene.Node[] tout = new javafx.scene.Node[n.length + 1];
        tout[0] = Ui.etiquette(etiquette);
        System.arraycopy(n, 0, tout, 1, n.length);
        return Ui.ligne(tout);
    }

    private static TableView<Ligne> table() {
        TableView<Ligne> t = new TableView<>();
        // Colonnes proportionnelles : en colonne etroite, rien ne sort du cadre.
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // Colonnes courtes a largeur FIXE (leur contenu tient toujours sur une
        // ligne) ; « Mobi » et « À qui » se partagent le reste et passent a la
        // ligne si besoin — jamais en dessous d'une largeur lisible.
        TableColumn<Ligne, Ligne> ci = new TableColumn<>("");
        ci.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        ci.setCellFactory(c -> new CelluleIcone());
        ci.setSortable(false);
        fixe(ci, 30);
        TableColumn<Ligne, String> cn = new TableColumn<>("Mobi");
        cn.setCellValueFactory(new PropertyValueFactory<>("nom"));
        cn.setComparator(String.CASE_INSENSITIVE_ORDER);
        cn.setMinWidth(90); cn.setPrefWidth(200); cn.setMaxWidth(4000);
        TableColumn<Ligne, Integer> cq = new TableColumn<>("Qté");
        cq.setCellValueFactory(new PropertyValueFactory<>("quantite"));
        cq.setStyle("-fx-alignment: CENTER-RIGHT;");
        fixe(cq, 48);
        TableColumn<Ligne, String> ct = new TableColumn<>("Type");
        ct.setCellValueFactory(new PropertyValueFactory<>("type"));
        // la valeur reste « sol » / « mur » (filtre, copie) ; seul l'affichage prend la majuscule
        ct.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean vide) {
                super.updateItem(v, vide);
                setText(vide || v == null ? null : Ui.majuscule(v));
            }
        });
        fixe(ct, 50);
        // « BC » : posé depuis le BC (badge plein) et existe au BC (coche), deux infos distinctes
        TableColumn<Ligne, Ligne> co = new TableColumn<>();
        Label teteBc = new Label("BC");
        teteBc.setTooltip(bulle("« Posé BC » : posé depuis le Builders Club, disparaît sans abonnement. "
                + "« Inv. » : posé depuis un inventaire.\n"
                + "Coche : existe au BC. Croix : pas au BC. « ? » : catalogue BC pas encore lu."));
        co.setGraphic(teteBc);
        co.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        co.setCellFactory(c -> new CelluleBc());
        // tri : posés depuis le BC d'abord, puis ce qui existe au BC
        co.setComparator(Comparator.comparingInt((Ligne l) -> l.estPoseBc() ? 0 : 1)
                .thenComparingInt(l -> OUI.equals(l.getAuBc()) ? 0 : "?".equals(l.getAuBc()) ? 1 : 2));
        fixe(co, 92);
        TableColumn<Ligne, String> cp = new TableColumn<>("Poseur");
        cp.setCellValueFactory(new PropertyValueFactory<>("proprietaire"));
        cp.setComparator(String.CASE_INSENSITIVE_ORDER);
        cp.setMinWidth(80); cp.setPrefWidth(110); cp.setMaxWidth(2000);
        TableColumn<Ligne, String> cx = new TableColumn<>("Prix");
        cx.setCellValueFactory(new PropertyValueFactory<>("prix"));
        cx.setStyle("-fx-alignment: CENTER-RIGHT;");
        // tri sur le nombre (« 1 029 c » apres « 147 c »), les prix inconnus en dernier
        cx.setComparator(Comparator.comparingLong(OngletApparts::valeurPrix));
        fixe(cx, 82);
        t.getColumns().add(ci); t.getColumns().add(cn); t.getColumns().add(cq);
        t.getColumns().add(ct); t.getColumns().add(co);
        t.getColumns().add(cp); t.getColumns().add(cx);
        Ui.retourALaLigne(cn); Ui.retourALaLigne(cp);
        t.setPrefHeight(220);
        t.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    /** « 1 029 c » -> 1029 ; « — » (inconnu) -> -1. Logique pure. */
    static long valeurPrix(String p) {
        if (p == null) return -1;
        String c = p.replaceAll("[^0-9]", "");
        if (c.isEmpty()) return -1;
        try { return Long.parseLong(c); } catch (NumberFormatException e) { return -1; }
    }

    /** Icone du mobi (images.habbo.com), chargee en fond, seulement pour les lignes visibles. */
    private static final class CelluleIcone extends TableCell<Ligne, Ligne> {
        private final javafx.scene.image.ImageView vue = new javafx.scene.image.ImageView();
        CelluleIcone() {
            vue.setFitWidth(PrixVignettes.TAILLE);
            vue.setFitHeight(PrixVignettes.TAILLE);
            vue.setPreserveRatio(true);
            setAlignment(Pos.CENTER);
        }
        @Override protected void updateItem(Ligne l, boolean vide) {
            super.updateItem(l, vide);
            javafx.scene.image.Image i = vide || l == null ? null : PrixVignettes.icone(l.classe, l.revision);
            vue.setImage(i);
            setGraphic(i == null ? null : vue);
            setText(null);
        }
    }

    /**
     * Colonne « BC » : un badge pour l'origine de CE mobi (« Posé BC » plein,
     * « Inv. » discret) et un signe pour l'existence du TYPE au BC (coche
     * verte, croix grise, « ? »), avec une bulle qui dit les deux en clair.
     */
    private static final class CelluleBc extends TableCell<Ligne, Ligne> {
        private final Label badge = new Label();
        private final javafx.scene.shape.SVGPath signe = new javafx.scene.shape.SVGPath();
        private final Label inconnu = new Label("?");
        private final javafx.scene.layout.StackPane place = new javafx.scene.layout.StackPane(signe, inconnu);
        private final HBox boite = new HBox(6, badge, place);
        private final Tooltip aide = bulle("");
        CelluleBc() {
            boite.setAlignment(Pos.CENTER_LEFT);
            badge.setMinWidth(Region.USE_PREF_SIZE);
            signe.setFill(null);
            signe.setStrokeWidth(2.2);
            signe.setScaleX(0.6); signe.setScaleY(0.6);
            place.setMinSize(16, 16); place.setPrefSize(16, 16); place.setMaxSize(16, 16);
            inconnu.setStyle("-fx-text-fill: #8A857A; -fx-font-weight: bold;");
            setAlignment(Pos.CENTER_LEFT);
        }
        @Override protected void updateItem(Ligne l, boolean vide) {
            super.updateItem(l, vide);
            setText(null);
            if (vide || l == null) { setGraphic(null); setTooltip(null); return; }
            boolean pose = l.estPoseBc();
            badge.setText(pose ? "Posé BC" : "Inv.");
            badge.setStyle(pose
                    ? "-fx-background-color: #E8862A; -fx-text-fill: white; -fx-font-weight: bold; "
                      + "-fx-font-size: 10.5px; -fx-padding: 1 5 1 5; -fx-background-radius: 8;"
                    : "-fx-text-fill: #8A857A; -fx-font-size: 10.5px; -fx-padding: 1 5 1 5;");
            String bc = l.getAuBc();
            boolean oui = OUI.equals(bc), non = NON.equals(bc);
            signe.setContent(oui ? "M5 12l5 5 9-10" : "M7 7l10 10 M17 7L7 17");
            signe.setStroke(javafx.scene.paint.Color.web(oui ? "#3C9A4E" : "#A9A397"));
            signe.setVisible(oui || non);
            inconnu.setVisible(!oui && !non);
            aide.setText((pose
                    ? "Posé depuis le Builders Club : il disparaîtra si l'abonnement BC s'arrête, "
                      + "et ne se ramasse pas dans l'inventaire."
                    : "Posé depuis un inventaire : c'est un vrai mobi, il se ramasse.")
                    + "\n" + (oui ? "Existe au BC : on peut en poser d'autres depuis le Builders Club."
                    : non ? "N'existe pas au BC : impossible d'en poser d'autres depuis le Builders Club."
                    : "Existe au BC : inconnu pour l'instant (catalogue BC pas encore lu)."));
            setTooltip(aide);
            setGraphic(boite);
        }
    }

    /** Bulle d'aide rapide (150 ms), qui passe a la ligne. */
    private static Tooltip bulle(String texte) {
        Tooltip t = new Tooltip(texte);
        t.setShowDelay(javafx.util.Duration.millis(150));
        t.setWrapText(true);
        t.setMaxWidth(300);
        return t;
    }

    private static void fixe(TableColumn<Ligne, ?> c, double l) {
        c.setMinWidth(l); c.setPrefWidth(l); c.setMaxWidth(l);
        c.setResizable(false);
    }

    /**
     * Ligne d'etat du volet « Dupliquer un appart ». Ui.etat envoie seule les
     * resultats (succes, erreur) dans le jeu et la console : rien a doubler ici.
     */
    private void note(String s) {
        Platform.runLater(() -> etatAppart.setText(s));
    }

    /** Ligne d'etat du volet « Ma salle ». */
    private void noteSalle(String s) {
        Platform.runLater(() -> etatSalle.setText(s));
    }

    /**
     * Une etape pendant un collage ou une copie : on a les yeux sur le jeu, elle
     * y est dite aussi. Un RESULTAT (succes, erreur) n'est pas redit : la ligne
     * d'etat l'envoie deja au jeu par le Journal.
     */
    private void direJeu(String m) {
        note(m);
        Journal.Genre g = Journal.genre(m);
        if (g != Journal.Genre.SUCCES && g != Journal.Genre.ERREUR) InfoJeu.dire(m);
    }
}

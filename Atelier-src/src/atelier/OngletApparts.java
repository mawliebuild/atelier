package atelier;

import extension.GPresets;
import game.FloorState;
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
        private final String origine;       // "BC" | "Inventaire" | "?"
        private final String proprietaire;  // le pseudo du poseur, ou "—"
        public Ligne(String n, int q, String t, String o, String pr) {
            nom = new SimpleStringProperty(n);
            quantite = new SimpleIntegerProperty(q);
            type = t; origine = o; proprietaire = pr;
        }
        public String getNom() { return nom.get(); }
        public int getQuantite() { return quantite.get(); }
        public String getType() { return type; }
        public String getOrigine() { return origine; }
        public String getProprietaire() { return proprietaire; }
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

    // volet « ma salle »
    private RadioButton oTout, oBc, oInv, tTout, tSols, tMurs;
    private TableView<Ligne> tableSalle;
    /** Ligne choisie dans « Ma salle », recopiee sur le fil FX. */
    private volatile Ligne ligneChoisie;
    private Label cptSalle;
    private Ui.Voyant vInv, vBc, vFurni, vSalle;
    private VBox blocEtat, contenuSalle, blocEtat2, contenuAppart;
    private Ui.Voyant vInv2, vBc2, vFurni2, vSalle2;
    private Ui.Voyant vPaquets;
    private Label salleActuelle, salleActuelle2;
    private ComboBox<String> choixProprio;
    private HBox ligneProprio;
    private TextField rechercheSalle;

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
        Tab t1 = new Tab("Ma salle", defiler(voletSalle()));
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
        return sp;
    }

    // -------------------------------------------------------- volet ma salle

    private Pane voletSalle() {
        cptSalle = new Label("--");

        vInv = new Ui.Voyant("Inventaire");
        vBc  = new Ui.Voyant("Catalogue BC");
        vFurni = new Ui.Voyant("Furnidata");
        vSalle = new Ui.Voyant("Salle");
        vPaquets = new Ui.Voyant("Paquets");
        demarrerVoyants();
        demarrerSuiviSalle();

        ToggleGroup origSalle = new ToggleGroup();
        oTout = radio("Les deux", origSalle, true);
        oBc   = radio("BC", origSalle, false);
        oInv  = radio("Inventaire", origSalle, false);
        for (RadioButton r : new RadioButton[]{oTout, oBc, oInv})
            r.setOnAction(e -> filtrerSalle());

        ToggleGroup typeSalle = new ToggleGroup();
        tTout = radio("Tout", typeSalle, true);
        tSols = radio("Sols", typeSalle, false);
        tMurs = radio("Murs", typeSalle, false);
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
            GPresets gp = AtelierLauncher.moteur();
            boolean mur = "mur".equals(l.type);
            java.util.List<String> r = mur
                    ? MiseEnValeur.mursOu(w -> l.getNom().equals(nom(gp, Salle.classe(w.getTypeId(), true), true)))
                    : MiseEnValeur.solsOu(it -> l.getNom().equals(nom(gp, Salle.classe(it.getTypeId(), false), false)));
            memo[0] = l; memo[1] = t; memo[2] = r;
            return r;
        });

        Button copier = Icones.sur(new Button("Copier la liste"), Icones.DUPLIQUER);
        copier.setOnAction(e -> copier(tableSalle, "Ma salle"));
        copier.getStyleClass().add("primaire");
        copier.setMinWidth(Region.USE_PREF_SIZE);
        copier.setTooltip(bulle("Le tableau tel qu'il est filtré, prêt à coller (une ligne « - Nom xQuantité » par mobi)."));

        // Recherche par nom, au-dessus du tableau
        rechercheSalle = new TextField();
        rechercheSalle.setPromptText("Chercher un mobi");
        rechercheSalle.textProperty().addListener((o, a, b) -> filtrerSalle());
        rechercheSalle.setMaxWidth(Double.MAX_VALUE);
        Button effacerRecherche = Icones.seul(Icones.VIDER, "Effacer la recherche");
        effacerRecherche.setOnAction(e -> rechercheSalle.clear());
        effacerRecherche.visibleProperty().bind(rechercheSalle.textProperty().isNotEmpty());
        HBox barreMobis = new HBox(8, Icones.petit(Icones.LOUPE), rechercheSalle, effacerRecherche, copier);
        barreMobis.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(rechercheSalle, Priority.ALWAYS);


        choixProprio = new ComboBox<>();
        choixProprio.setMaxWidth(Double.MAX_VALUE);
        choixProprio.getItems().add(TOUT_LE_MONDE);
        choixProprio.setValue(TOUT_LE_MONDE);
        choixProprio.valueProperty().addListener((o, a, b) -> filtrerSalle());

        salleActuelle = Ui.valeur("—");
        blocEtat = Ui.bloc("État", vPaquets, vSalle, vFurni, vInv, vBc);

        // Grand par defaut : la fenetre prend toute la hauteur dispo, et seul le
        // tableau defile (la fenetre, elle, ne defile pas).
        tableSalle.setPrefHeight(900);
        tableSalle.setMinHeight(140);
        VBox.setVgrow(tableSalle, Priority.ALWAYS);

        // En tete : le nom de la salle en valeur, le compte juste dessous.
        salleActuelle.setWrapText(true);
        cptSalle.getStyleClass().add("salle-compte");
        cptSalle.setWrapText(true);
        VBox enTete = new VBox(2, salleActuelle, cptSalle);

        // Filtres : une ligne chacun, l'intitule a gauche, sans boites imbriquees.
        VBox filtres = new VBox(8,
                filtre("Origine", Ui.ligne(oTout, oBc, oInv)),
                filtre("Type", Ui.ligne(tTout, tSols, tMurs)),
                ligneProprio = filtre("À qui", choixProprio));
        // « À qui » seulement si les mobis de l'appart sont a plusieurs personnes
        // (la liste contient « tout le monde » + un nom par proprietaire).
        ligneProprio.visibleProperty().bind(javafx.beans.binding.Bindings.size(choixProprio.getItems()).greaterThan(2));
        ligneProprio.managedProperty().bind(ligneProprio.visibleProperty());

        // En haut : la salle a gauche, les filtres a droite.
        VBox blocSalle = Ui.bloc("Salle", enTete), blocFiltres = Ui.bloc("Filtres", filtres);
        HBox haut = new HBox(12, blocSalle, blocFiltres);
        HBox.setHgrow(blocSalle, Priority.ALWAYS);
        HBox.setHgrow(blocFiltres, Priority.ALWAYS);
        blocSalle.setMaxWidth(Double.MAX_VALUE);
        blocFiltres.setMaxWidth(Double.MAX_VALUE);
        blocSalle.setPrefWidth(200);
        blocFiltres.setPrefWidth(300);

        // « À qui » : la colonne n'a de sens qu'avec plusieurs poseurs, comme le filtre.
        for (TableColumn<Ligne, ?> c : tableSalle.getColumns())
            if ("À qui".equals(c.getText())) c.visibleProperty().bind(ligneProprio.visibleProperty());
        Label videSalle = new Label("Aucun mobi à afficher.");
        videSalle.getStyleClass().add("aide-vide");
        tableSalle.setPlaceholder(videSalle);

        VBox blocMobis = Ui.bloc("Mobis", barreMobis, tableSalle,
                Ui.aide("La liste suit la salle toute seule, en temps réel. Clique un intitulé de colonne "
                        + "pour trier ; la ligne choisie s'allume dans l'appart. "
                        + "Copier la liste : le tableau tel qu'il est filtré, prêt à coller."));
        // Le tableau prend toute la hauteur restante.
        VBox.setVgrow(blocMobis, Priority.ALWAYS);
        VBox.setVgrow(blocMobis.getChildren().get(1), Priority.ALWAYS);

        contenuSalle = new VBox(12, haut, LimitesSalle.bloc(), blocMobis);
        contenuSalle.setFillWidth(true);
        VBox.setVgrow(contenuSalle, Priority.ALWAYS);

        // Les voyants techniques (paquets, furnidata...) ne s'affichent plus :
        // la liste se remplit d'elle-meme, et ce qui manque se voit dans le tableau.
        VBox v = new VBox(12, contenuSalle, etatSalle);
        VBox.setVgrow(contenuSalle, Priority.ALWAYS);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        return v;
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
        GPresets gp = AtelierLauncher.moteur();
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

        FloorState s = gp.getFloorState();
        boolean dansSalle = (s != null && s.inRoom());
        if (s == null)         reglerDuo(vSalle, vSalle2, "absent", "État de salle indisponible");
        else if (!dansSalle)   reglerDuo(vSalle, vSalle2, "absent", manqueSalle(s));
        else {
            // Le nom seulement : le modele (« model_a ») ne dit rien a personne.
            String n = NomSalle.nomValide(gp);
            reglerDuo(vSalle, vSalle2, "ok", (n == null || n.isEmpty()) ? "Dans une salle" : n);
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
        montrerEtat(!dansSalle || attenteCourte);
    }

    /**
     * L'etat et le contenu s'excluent : tant que tout n'est pas charge, on ne
     * montre que les voyants ; des que c'est pret, ils disparaissent et le
     * contenu prend toute la place. Jamais les deux a la fois.
     */
    private Boolean dernierEtat = null;

    private void montrerEtat(boolean enAttente) {
        if (Boolean.valueOf(enAttente).equals(dernierEtat)) return;
        dernierEtat = enAttente;
        Platform.runLater(() -> {
            if (blocEtat != null) {
                blocEtat.setVisible(false);   // les Prérequis en haut du volet le disent deja
                blocEtat.setManaged(false);
            }
            if (contenuSalle != null) {
                contenuSalle.setVisible(!enAttente);
                contenuSalle.setManaged(!enAttente);
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
        Platform.runLater(() -> v.regler(niveau, raison));
    }

    private static final Map<Ui.Voyant, String> dejaRegle =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Dit CE QUI manque a inRoom(), au lieu d'un « pas dans une salle » muet.
     * inRoom() exige furnimap + wallIdToItem + floorplan : nommer le champ nul
     * indique quel paquet n'est pas recu.
     */
    private static String manqueSalle(FloorState s) {
        java.util.List<String> manque = new java.util.ArrayList<>();
        for (String champ : new String[]{"furnimap", "wallIdToItem", "floorplan"}) {
            try {
                java.lang.reflect.Field f = FloorState.class.getDeclaredField(champ);
                f.setAccessible(true);
                if (f.get(s) == null) manque.add(champ);
            } catch (Throwable ignored) { }
        }
        if (manque.isEmpty()) return "Pas dans une salle";
        return "Paquet(s) non reçu(s) : " + String.join(", ", manque);
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
        cpMurs  = new CheckBox("Murs");   cpMurs.setSelected(true);
        cpWired = new CheckBox("Wired");  cpWired.setSelected(true);

        Button copierSalle = Icones.sur(plein("Copier", e -> copierSalleVersAppart()), Icones.DUPLIQUER);
        copierSalle.getStyleClass().add("primaire");

        // --- 2. mes copies : une carte par copie (apercu, nom, contenu, date)
        choixAppart = new ComboBox<>();             // garde la selection (lue partout) ; la liste l'affiche
        choixAppart.valueProperty().addListener((o, a, b) -> { lireAppart(); majFloor(); });
        ListView<String> liste = new ListView<>(choixAppart.getItems());
        liste.setPrefHeight(250);
        liste.setMinHeight(150);
        Label vide = new Label("Aucune copie pour l'instant : copie un appart au-dessus.");
        vide.setWrapText(true);
        vide.getStyleClass().add("aide-vide");
        liste.setPlaceholder(vide);
        liste.setCellFactory(lv -> new ListCell<>() {
            {
                // la cellule suit la largeur de la liste : le texte passe a la ligne, pas d'ascenseur de cote
                setPrefWidth(0);
            }
            @Override protected void updateItem(String nom, boolean vide) {
                super.updateItem(nom, vide);
                if (vide || nom == null) { setText(null); setGraphic(null); return; }
                Label n = new Label(nom);
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
                setText(null);
            }
        });
        liste.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (b != null) choixAppart.setValue(b); });
        choixAppart.valueProperty().addListener((o, a, b) -> {
            if (b != null && !b.equals(liste.getSelectionModel().getSelectedItem())) liste.getSelectionModel().select(b);
        });

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

        Button poser = Icones.sur(plein("Coller ici", e -> collerAppart()), Icones.COLLER);
        poser.getStyleClass().add("primaire");
        // Pendant un collage : « Arrêter » a la place de « Coller ici »
        Button arreter = plein("Arrêter le collage", e -> arreterCollage());
        arreter.visibleProperty().bind(collageEnCours);
        arreter.managedProperty().bind(collageEnCours);
        poser.visibleProperty().bind(collageEnCours.not());
        poser.managedProperty().bind(collageEnCours.not());
        boutonArreter = arreter;
        // Actions sur la copie choisie : des icones, avec leur bulle
        Button renommer = Icones.seul(Icones.CRAYON, "Renommer la copie");
        renommer.setOnAction(e -> renommerCopie());
        Button supprimer = Icones.seul(Icones.CORBEILLE, "Supprimer la copie (définitif)");
        supprimer.setOnAction(e -> supprimerCopie());
        Button apercu = Icones.seul(Icones.CAPTURE, "Reprendre l'aperçu : nouvelle photo de la copie choisie, "
                + "depuis l'appart où tu es (pour une zone : la zone choisie). "
                + "Utile pour les copies faites avant les aperçus.");
        apercu.getTooltip().setWrapText(true);
        apercu.getTooltip().setMaxWidth(300);
        apercu.setOnAction(e -> reprendreApercu(liste));
        for (Button b : new Button[]{poser, renommer, supprimer, apercu})
            b.disableProperty().bind(liste.getSelectionModel().selectedItemProperty().isNull());
        HBox actions = new HBox(4, renommer, apercu, supprimer);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox sousListe = new HBox(8, cptAppart, actions);
        sousListe.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cptAppart, Priority.ALWAYS);
        cptAppart.setMaxWidth(Double.MAX_VALUE);

        // Source des mobis : un reglage rare, replie derriere un ⚙ (le choix en cours reste ecrit).
        Label sourceTxt = new Label();
        sourceTxt.setWrapText(true);
        Runnable majSource = () -> sourceTxt.setText("Mobis pris : " + (sInv.isSelected() ? "inventaire"
                : sBc.isSelected() ? "BC" : sBcInv.isSelected() ? "BC, puis inventaire" : "inventaire, puis BC") + ".");
        source.selectedToggleProperty().addListener((o, a, b) -> majSource.run());
        majSource.run();
        VBox choixSource = new VBox(4, sInv, sInvBc, sBc, sBcInv);
        choixSource.setPadding(new Insets(0, 0, 0, 4));
        choixSource.setVisible(false);
        choixSource.setManaged(false);
        Button reglerSource = Icones.seul(Icones.REGLAGES, "Changer d'où viennent les mobis");
        reglerSource.setOnAction(e -> {
            boolean v = !choixSource.isVisible();
            choixSource.setVisible(v);
            choixSource.setManaged(v);
        });
        HBox ligneSource = new HBox(8, sourceTxt, reglerSource);
        ligneSource.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sourceTxt, Priority.ALWAYS);
        sourceTxt.setMaxWidth(Double.MAX_VALUE);

        Label salleLbl = Ui.etiquette("Salle actuelle");
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
                        liste,
                        sousListe,
                        Ui.aide("Clique un aperçu pour l'agrandir. Les icônes sous la liste renomment, "
                                + "reprennent la photo ou suppriment la copie choisie.")),
                Ui.bloc("Coller dans l'appart où je suis",
                        ligneSource, choixSource,
                        avecFloor,
                        poser, boutonArreter,
                        Ui.aide("Sans le floor (ou pour une zone), clique dans le jeu la case du coin "
                                + "haut-gauche. Les mobis introuvables sont signalés et le reste est collé.")));
        contenuAppart.setFillWidth(true);

        vSalle2 = new Ui.Voyant("Salle");
        vFurni2 = new Ui.Voyant("Furnidata");
        vInv2   = new Ui.Voyant("Inventaire");
        vBc2    = new Ui.Voyant("Catalogue BC");
        blocEtat2 = Ui.bloc("État", vSalle2, vFurni2, vInv2, vBc2);

        VBox v = new VBox(14, blocEtat2, contenuAppart, etatAppart);
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
            direJeu(err == null ? "Aperçu de « " + nom + " » repris." : err);
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

    private void renommerCopie() {
        String nom = choixAppart.getValue();
        if (nom == null) return;
        TextInputDialog d = new TextInputDialog(nom);
        d.setTitle("Renommer la copie");
        d.setHeaderText(null);
        d.setContentText("Nouveau nom :");
        d.showAndWait().ifPresent(n -> {
            n = n.replaceAll("[<>:\"/\\\\|?*]", "-").trim();
            if (n.isEmpty() || n.equals(nom)) return;
            File src = new File(dossierApparts(), nom + ".json"), dest = new File(dossierApparts(), n + ".json");
            if (dest.exists()) { note("Renommage impossible : une copie s'appelle déjà « " + n + " »."); return; }
            if (!src.renameTo(dest)) { note("Renommage impossible pour « " + nom + " »."); return; }
            File png = ApercuPreset.de(src);
            if (png.isFile() && !png.renameTo(ApercuPreset.de(dest))) System.err.println("[Atelier] aperçu non renommé : " + png);
            note("« " + nom + " » renommée en « " + n + " ».");
            final String nouveau = n;
            chargerListeApparts();
            Platform.runLater(() -> choixAppart.setValue(nouveau));
        });
    }

    private void supprimerCopie() {
        String nom = choixAppart.getValue();
        if (nom == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer la copie « " + nom + " » ? C'est définitif.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        File f = new File(dossierApparts(), nom + ".json");
        if (!f.delete()) { note("Impossible de supprimer « " + nom + " »."); return; }
        File png = ApercuPreset.de(f);
        if (png.isFile() && !png.delete()) System.err.println("[Atelier] aperçu non supprimé : " + png);
        note("Copie « " + nom + " » supprimée.");
        chargerListeApparts();
    }

    /**
     * La liste se tient a jour toute seule : on surveille la date du dossier des
     * apparts, ce qui evite un bouton « Recharger » a cliquer apres chaque export.
     */
    private void surveillerDossier() {
        Thread t = new Thread(() -> {
            long vue = 0;
            while (true) {
                try {
                    File d = dossierApparts();
                    long m = d.exists() ? d.lastModified() : 0;
                    if (m != vue) {
                        boolean premier = vue == 0;
                        vue = m;
                        Platform.runLater(() -> {
                            chargerListeApparts();
                            // Le moteur de l'Atelier garde sa propre liste : on la lui fait relire aussi.
                            GPresets gp = AtelierLauncher.moteur();
                            if (!premier && gp != null) try { gp.reloadPresetsClick(null); } catch (Throwable ignored) { }
                        });
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-dossier-apparts");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Pose l'appart choisi dans la salle courante.
     *
     * On reutilise le moteur de pose plutot que de le reecrire : il gere
     * deja les dalles, les hauteurs, le wired et les etats.
     *
     * Avant, on selectionnait l'appart dans presetListView puis on envoyait
     * :ip. Mais une selection ne CHARGE pas l'appart — le moteur de l'Atelier ne le charge
     * qu'au double-clic —, donc c'etait l'appart charge precedemment qui etait
     * pose. Desormais on lit le fichier nous-memes et on le donne directement a
     * l'importeur (Generateur.importer), qui verifie aussi qu'aucun import
     * n'est deja en cours et que le moteur de l'Atelier est pret.
     */
    private void collerAppart() {
        // Pendant le collage, chaque message va aussi dans le chat du jeu :
        // on a les yeux sur le jeu, pas sur le volet.
        java.util.function.Consumer<String> dire = this::direJeu;
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { dire.accept("Collage impossible : l'Atelier n'est pas encore prêt."); return; }
        String nom = choixAppart.getValue();
        if (nom == null) { dire.accept("Choisis d'abord un appart."); return; }

        FloorState s = gp.getFloorState();
        if (s == null || !s.inRoom()) { dire.accept("Collage impossible : tu n'es pas dans une salle."); return; }

        Generateur.Source src = sBc.isSelected()    ? Generateur.Source.BC
                              : sInv.isSelected()   ? Generateur.Source.INVENTAIRE
                              : sBcInv.isSelected() ? Generateur.Source.BC_PUIS_INVENTAIRE
                              : Generateur.Source.INVENTAIRE_PUIS_BC;
        // Lu ici, sur le fil FX : majFloor() peut changer la case pendant le collage.
        boolean floorVoulu = avecFloor == null || avecFloor.isSelected();

        Salle.tache("coller", () -> {
            try {
                File f = new File(dossierApparts(), nom + ".json");
                if (!f.isFile()) { dire.accept("Collage impossible : fichier introuvable (" + f.getName() + ")."); return; }
                JSONObject brut = lirePreset(f);
                extension.tools.presetconfig.PresetConfig cfg =
                        new extension.tools.presetconfig.PresetConfig(brut);
                JSONObject floor = brut.optJSONObject("atelierFloor");

                // Une classe inconnue de la furnidata fait planter l'import du
                // moteur de pose (getFloorTypeId(...).intValue()) : on le dit avant.
                if (furnidataPrete()) {
                    furnidata.FurniDataTools fd = gp.getFurniDataTools();
                    for (extension.tools.presetconfig.furni.PresetFurni pf : cfg.getFurniture())
                        if (fd.getFloorTypeId(pf.getClassName()) == null) {
                            dire.accept("Collage impossible : « " + pf.getClassName() + " » inconnu de la furnidata.");
                            return;
                        }
                }

                // Une pose deja en cours : le moteur de pose refuserait (« Already importing »).
                extension.tools.GPresetImporter imp = gp.getImporter();
                try {
                    if (imp.getState() != extension.tools.GPresetImporter.BuildingImportState.NONE) {
                        dire.accept("Collage impossible : une autre pose n'est pas finie. Attends-la, ou tape :abort dans le jeu.");
                        return;
                    }
                } catch (Throwable ignored) { }

                // Tout s'enchaine, comme pour l'Escalier : un seul clic dans le
                // jeu (le coin), puis l'Atelier pose la dalle magique lui-meme
                // a cote, la donne au moteur de pose, et la pose demarre.
                List<Generateur.Mobi> mobis = new ArrayList<>();
                for (extension.tools.presetconfig.furni.PresetFurni pf : cfg.getFurniture()) {
                    gearth.extensions.parsers.HPoint l = pf.getLocation();
                    mobis.add(new Generateur.Mobi(pf.getClassName(), pf.getState(),
                            l.getX(), l.getY(), l.getZ(), pf.getRotation()));
                }
                int n = cfg.getFurniture().size() + cfg.getWallFurniture().size();
                gearth.extensions.parsers.HPoint racine, clic = null;
                if (floor != null && floorVoulu) {
                    // Copie d'un appart complet : son floor d'abord, puis tout
                    // revient a sa place d'origine, sans clic.
                    if (!appliquerFloor(floor, dire)) return;
                    racine = new gearth.extensions.parsers.HPoint(floor.optInt("x0", 0), floor.optInt("y0", 0));
                } else {
                    dire.accept("« " + nom + " » : clique dans le jeu la case où mettre le coin haut-gauche de l'appart. "
                            + "Ton avatar ne bougera pas.");
                    racine = Generateur.Dalle.attendreClic(120_000);
                    if (racine == null) { dire.accept("Collage impossible : pas de clic dans le jeu en 2 minutes."); return; }
                    // La case cliquee = coin haut-gauche des MOBIS copies. Une copie d'appart
                    // complet garde les positions depuis le coin (0,0) de l'appart d'origine :
                    // sans son floor, on les ramene a ce coin (sinon tout tombe hors du sol).
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                    for (extension.tools.presetconfig.furni.PresetFurni pf : cfg.getFurniture()) {
                        minX = Math.min(minX, pf.getLocation().getX()); minY = Math.min(minY, pf.getLocation().getY());
                    }
                    for (extension.tools.presetconfig.furni.PresetWallFurni pw : cfg.getWallFurniture()) {
                        minX = Math.min(minX, pw.getLocation().getX()); minY = Math.min(minY, pw.getLocation().getY());
                    }
                    if (minX != Integer.MAX_VALUE && (minX != 0 || minY != 0)) {
                        clic = racine;
                        racine = new gearth.extensions.parsers.HPoint(racine.getX() - minX, racine.getY() - minY);
                        Journal.debug("collage « " + nom + " » : positions ramenées au coin des mobis (" + minX + "," + minY + ").");
                    } else clic = racine;
                }

                // Pose directe, mobi par mobi, chacun a son altitude (@altitude), sans dalle
                // magique, wired compris. Les reglages des wired sont ensuite appliques par le
                // moteur de pose (ReglagesWired), sur les wired qu'on vient de poser. Si ce
                // n'est pas possible (autre version du moteur, valeurs de variables portees
                // par les mobis), on garde l'ancien chemin : dalle magique + moteur de pose.
                boolean avecWired = aDesWired(brut);
                String pasDirect = avecWired ? ReglagesWired.nonGere(cfg) : null;
                if (pasDirect != null) Journal.debug("collage « " + nom + " » : moteur de pose complet (" + pasDirect + ")");
                if (pasDirect == null) {
                    double ancre = cfg.getSrcAnchorFloorHeight() == null ? 0 : cfg.getSrcAnchorFloorHeight();
                    gearth.extensions.parsers.HPoint repere = clic != null ? clic : racine;   // case de reference des hauteurs
                    double sol0 = Math.max(0, Salle.hauteurSol(repere.getX(), repere.getY()));
                    List<PoseDirecte.Sol> sols = new ArrayList<>();
                    for (extension.tools.presetconfig.furni.PresetFurni pf : cfg.getFurniture()) {
                        gearth.extensions.parsers.HPoint l = pf.getLocation();
                        sols.add(new PoseDirecte.Sol(pf.getClassName(), racine.getX() + l.getX(), racine.getY() + l.getY(),
                                Math.max(0, l.getZ() - ancre + sol0), pf.getRotation(), pf.getState(), pf.getFurniId()));
                    }
                    // Pose HYBRIDE (PoseHybride) : 1. les sols en rafale (inventaire / BC), chacun
                    // a son altitude (@altitude) ; 2. ceux que le jeu refuse ou laisse a une
                    // mauvaise hauteur, et seulement eux, repris avec la dalle magique (moteur
                    // de pose) ; 3. sans @altitude, les sols en hauteur passent directement par
                    // la dalle. Tout est une seule action pour Ctrl+Z.
                    collageArrete = false;
                    Platform.runLater(() -> collageEnCours.set(true));
                    int salle0 = Groupes.salleCourante();
                    java.util.function.BooleanSupplier stop = () -> collageArrete || Groupes.salleCourante() != salle0;
                    long[] derniere = {0};
                    java.util.function.BiConsumer<String, int[]> etape = (quoi, kn) -> {
                        long t = System.currentTimeMillis();
                        if (kn[0] < kn[1] && t - derniere[0] < 250) return;
                        derniere[0] = t;
                        note(quoi + " : " + kn[0] + "/" + kn[1]);
                    };
                    PoseDirecte.Resultat pr;
                    PoseHybride.Bilan rb = null;
                    boolean altitude = true;
                    int mursPoses = 0;
                    ReglagesWired.Bilan rw = null;
                    Map<Integer, Integer> cles;
                    Historique.grouper(true);
                    try {
                        boolean enHauteur = false;
                        for (PoseDirecte.Sol so : sols)
                            if (!PoseHybride.parRafale(so.z, Salle.hauteurSol(so.x, so.y), false)) { enHauteur = true; break; }
                        if (enHauteur) altitude = PoseHybride.altitudeDisponible(this::note);
                        List<PoseDirecte.Sol> rafale = new ArrayList<>();
                        List<PoseHybride.Piece> reprise = new ArrayList<>();
                        for (PoseDirecte.Sol so : sols) {
                            if (PoseHybride.parRafale(so.z, Salle.hauteurSol(so.x, so.y), altitude)) rafale.add(so);
                            else reprise.add(PoseHybride.Piece.depuis(so, -1));
                        }
                        pr = PoseDirecte.poser(rafale, List.of(), src, m -> { }, stop,
                                (k, tot) -> etape.accept("Pose rapide", new int[]{k, tot}), altitude);
                        // verification : refuses et mauvaises hauteurs -> dalle
                        if (!stop.getAsBoolean()) {
                            for (PoseDirecte.Sol so : pr.solsRefuses) reprise.add(PoseHybride.Piece.depuis(so, -1));
                            for (Map.Entry<Integer, PoseDirecte.Sol> e : pr.solsPoses.entrySet()) {
                                gearth.extensions.parsers.HFloorItem now = Salle.sol(e.getKey());
                                if (now != null && PoseHybride.trier(true, true, now.getTile().getZ(), e.getValue().z)
                                        == PoseHybride.Issue.HAUTEUR) reprise.add(PoseHybride.Piece.depuis(e.getValue(), e.getKey()));
                            }
                            if (!reprise.isEmpty()) {
                                Journal.debug("collage « " + nom + " » : " + reprise.size() + " mobi(s) repris à la dalle.");
                                note("Reprise à la dalle : 0/" + reprise.size());
                                rb = PoseHybride.reprendre(reprise, src, m -> Journal.debug("reprise : " + m), stop,
                                        (k, tot) -> etape.accept("Reprise à la dalle", new int[]{k, tot}));
                            }
                        }
                        // furniId du preset -> id reel, apres la reprise (nouveaux ids)
                        cles = new LinkedHashMap<>(pr.cles);
                        if (rb != null) {
                            Set<Integer> partis = rb.ramassesSols;
                            cles.values().removeIf(partis::contains);
                            cles.putAll(rb.cles);
                        }
                        // muraux : le moteur de pose, en « muraux seulement » (pas de dalle) ; il regle
                        // aussi leur hauteur et leur decalage (variables du jeu), sinon refuses
                        if (!cfg.getWallFurniture().isEmpty() && !stop.getAsBoolean())
                            mursPoses = poserMuraux(gp, imp, cfg, nom, src, racine);
                        // enfin les reglages des wired, sur les wired poses (cle -> id reel)
                        if (avecWired && !stop.getAsBoolean()) {
                            note("Réglages des wired…");
                            rw = ReglagesWired.appliquer(gp, imp, cfg, cles, racine);
                        }
                    } finally {
                        Historique.grouper(false);
                        Platform.runLater(() -> collageEnCours.set(false));
                    }
                    boolean arrete = stop.getAsBoolean();
                    int solsPoses = pr.sols.size() - (rb == null ? 0 : rb.ramassesSols.size()) + (rb == null ? 0 : rb.obtenus());
                    int hauteursFausses = rb == null ? pr.hauteursFausses : rb.hauteursFausses;
                    if (rb != null && (rb.raison != null || rb.arrete))       // pas reprises : restees fausses
                        for (Map.Entry<Integer, PoseDirecte.Sol> e : pr.solsPoses.entrySet()) {
                            if (rb.ramassesSols.contains(e.getKey())) continue;
                            gearth.extensions.parsers.HFloorItem now = Salle.sol(e.getKey());
                            if (now != null && Math.abs(now.getTile().getZ() - e.getValue().z) > PoseHybride.TOLERANCE) hauteursFausses++;
                        }
                    int introuvables = Math.max(0, pr.manquants - pr.solsRefuses.size());
                    // Un seul bilan. Un manque ou une hauteur fausse en fait une erreur (Journal.ERREUR).
                    String bilan = PoseHybride.bilan("posé", n, solsPoses + mursPoses,
                            rb == null ? 0 : rb.obtenus(), hauteursFausses, arrete);
                    bilan = bilan.substring(0, bilan.length() - 1)
                            + (introuvables > 0 ? " ; " + introuvables + " introuvable(s) dans la source choisie" : "")
                            + (pr.etatsFaux > 0 ? " ; " + pr.etatsFaux + " pas dans le bon état" : "")
                            + (rb != null && rb.raison != null && !arrete ? " ; reprise à la dalle impossible (" + rb.raison + ")" : "");
                    if (rw != null && !rw.possible)
                        bilan += " ; réglages des wired pas appliqués (" + rw.raison + ")";
                    else if (rw != null && rw.attendus > 0)
                        bilan += " ; " + rw.regles + " wired réglé(s) sur " + rw.attendus
                                + (rw.rates > 0 ? ", " + rw.rates + " pas confirmé(s)" : "")
                                + (rw.relusDifferents > 0 ? ", " + rw.relusDifferents + " relu(s) différent(s)" : "");
                    bilan += "." + (!altitude ? PoseHybride.SANS_ALTITUDE : "");
                    dire.accept(Ui.accorder(bilan));
                    return;
                }

                // Le moteur de pose place chaque mobi en racine + sa position dans le fichier
                // (sans retrancher le minimum) : le trace se calcule pareil.
                List<int[]> trace = Generateur.Dalle.trace(mobis, 0, 0, racine);
                int[] depart = new int[]{racine.getX(), racine.getY()};
                Generateur.Dalle.Pret dalle = Generateur.Dalle.preparer(gp, mobis, trace, depart, dire);
                if (dalle == null) { Generateur.Dalle.finIgnorer(); return; }

                boolean ok = Generateur.importer(gp, imp, cfg, nom, src, racine, dire,
                        "« " + nom + " » (" + n + " mobi(s)) envoyé au moteur de pose. ", dalle.ou);
                if (ok) Generateur.Dalle.apresImport(imp, dalle.poseeParAtelier, dire);
                else if (dalle.poseeParAtelier > 0)
                    Generateur.Dalle.ramasser(dalle.poseeParAtelier, dire,
                            "La pose n'a pas démarré : j'ai ramassé la dalle magique.");
                else Generateur.Dalle.finIgnorer();
            } catch (Throwable t) {
                t.printStackTrace();
                dire.accept("Collage impossible : " + lisible(t) + ".");
            }
        });
    }

    /** Demande d'arret du collage en cours (pose hybride) : plus rien ne part ensuite. */
    private volatile boolean collageArrete = false;
    /** Un collage tourne : « Arrêter le collage » remplace « Coller ici ». Fil JavaFX. */
    private final javafx.beans.property.BooleanProperty collageEnCours = new javafx.beans.property.SimpleBooleanProperty(false);
    private Button boutonArreter;

    /** Arrete le collage en cours (rafale ou reprise a la dalle). A brancher sur un bouton « Arrêter ». */
    void arreterCollage() { collageArrete = true; }

    /**
     * Les muraux d'un preset, par le moteur de pose en « muraux seulement »
     * (« :ip x,y » sans mobi de sol : pas de dalle magique). Attend la fin de la
     * pose ; renvoie le nombre de muraux apparus. Sans message pendant la pose.
     */
    private int poserMuraux(GPresets gp, extension.tools.GPresetImporter imp,
                            extension.tools.presetconfig.PresetConfig cfg, String nom,
                            Generateur.Source src, gearth.extensions.parsers.HPoint racine) {
        try {
            JSONObject o = cfg.toJsonObject();
            o.put("furni", new JSONArray());
            // le moteur de pose exige « wired » (getJSONObject) : vide, pas absent
            JSONObject w = new JSONObject();
            for (String k : new String[]{"conditions", "effects", "triggers"}) w.put(k, new JSONArray());
            JSONObject vm = o.optJSONObject("wired") == null ? null : o.getJSONObject("wired").optJSONObject("variables_map");
            if (vm != null) w.put("variables_map", vm);
            o.put("wired", w);
            o.put("bindings", new JSONArray());
            o.remove("adsBackgrounds");
            extension.tools.presetconfig.PresetConfig seuls = new extension.tools.presetconfig.PresetConfig(o);
            int avant = Salle.murs().size();
            if (!Generateur.importer(gp, imp, seuls, nom, src, racine, m -> { }, "")) return 0;
            long fin = System.currentTimeMillis() + 120_000;
            Salle.sommeil(800);
            while (System.currentTimeMillis() < fin) {
                extension.tools.GPresetImporter.BuildingImportState st = null;
                try { st = imp.getState(); } catch (Throwable ignored) { }
                if (st == null || st == extension.tools.GPresetImporter.BuildingImportState.NONE) break;
                Salle.sommeil(250);
            }
            return Math.max(0, Salle.murs().size() - avant);
        } catch (Throwable t) {
            Journal.debug("muraux : " + t);
            return 0;
        }
    }

    /** Le preset a-t-il des reglages wired (que seul le moteur de pose sait poser) ? */
    private static boolean aDesWired(JSONObject brut) {
        JSONObject w = brut.optJSONObject("wired");
        if (w == null) return false;
        for (String k : w.keySet()) { JSONArray a = w.optJSONArray(k); if (a != null && a.length() > 0) return true; }
        return false;
    }

    /** Remplit un champ prive de l'export de l'Atelier ; dit dans le terminal s'il manque. */
    private static void remplir(Object cible, String champ, Object valeur) {
        try {
            java.lang.reflect.Field f = cible.getClass().getDeclaredField(champ);
            f.setAccessible(true);
            f.set(cible, valeur);
        } catch (Throwable t) {
            Journal.debug("copie : champ " + champ + " inaccessible (" + t + ")");
        }
    }

    /**
     * Applique le floor enregistre avec l'appart (UpdateFloorProperties), puis
     * attend que la salle se recharge avec le nouveau plan. false = abandon
     * (la raison est dite).
     */
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
            if (a != null && a.texte().equals(m.texte()) && a.porteX == m.porteX && a.porteY == m.porteY
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
            FloorState e = Salle.etat();
            String p = e == null ? null : e.getRawFloorplan();
            FloorModele r = p == null ? null : FloorModele.depuisTexte(p);
            if (FloorReseau.planRecu > t0 && r != null && r.texte().equals(m.texte())) {
                Salle.sommeil(2500);              // le temps que les mobis de la salle arrivent
                dire.accept("Floor appliqué. Je pose les mobis...");
                return true;
            }
        }
        dire.accept("Collage impossible : le floor n'est pas revenu du jeu en 15 s (droits de la salle ?).");
        return false;
    }

    /** Enregistre la salle courante comme nouvel appart. */
    /**
     * Enregistre la salle courante (ou une zone) comme appart, par l'export de
     * l'Atelier : mobis au sol, muraux et wired, comme « :exportpreset ». Son
     * attemptExport (prive) est appele directement avec le rectangle et le nom,
     * sans ses questions dans le chat. Pour un appart complet, le floor (plan,
     * porte, murs) est ajoute au fichier sous « atelierFloor » : le moteur de l'Atelier
     * n'enregistre qu'une empreinte du plan, et ignore cette cle a la relecture.
     * Chaque etape est dite dans le volet, dans le jeu et dans le terminal.
     */
    private void copierSalleVersAppart() {
        java.util.function.Consumer<String> dire = this::direJeu;
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { dire.accept("Copie impossible : l'Atelier n'est pas encore prêt."); return; }
        FloorState s = gp.getFloorState();
        if (s == null || !s.inRoom()) { dire.accept("Copie impossible : tu n'es pas dans une salle."); return; }

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
        String nom = base;
        for (int k = 2; new File(dossierApparts(), nom + ".json").exists(); k++) nom = base + " (" + k + ")";
        final String nomFinal = nom;
        nomNouvelAppart.setText(nomFinal);

        Salle.tache("copier", () -> {
            try {
                extension.tools.GPresetExporter exp = gp.getExporter();
                if (exp == null) { dire.accept("Copie impossible : l'export de l'Atelier n'est pas prêt."); return; }
                if (exp.getState() != extension.tools.GPresetExporter.PresetExportState.NONE) {
                    dire.accept("Copie impossible : une autre copie n'est pas finie dans l'Atelier. Attends-la, ou tape :abort.");
                    return;
                }

                // les cases du moteur de l'Atelier suivent celles du volet
                CountDownLatch pret = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        if (gp.noExportWiredCbx != null) gp.noExportWiredCbx.setSelected(!wired);
                        if (gp.noExportWallItemsCbx != null) gp.noExportWallItemsCbx.setSelected(!murs);
                    } finally { pret.countDown(); }
                });
                pret.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    java.lang.reflect.Field f = extension.tools.GPresetExporter.class.getDeclaredField("wallOnlyExport");
                    f.setAccessible(true);
                    f.setBoolean(exp, !sols && murs);
                } catch (Throwable t) { Journal.debug("copie : wallOnlyExport inaccessible (" + t + ")"); }

                String quoi = (sols ? "sols" : "") + (murs ? (sols ? " + " : "") + "murs" : "")
                        + (wired ? " + wired" : "") + (complet ? " + floor" : "");
                dire.accept("Copie de « " + nomFinal + " » (" + (complet ? "appart complet" : "zone " + lx + "×" + ly)
                        + ", " + quoi + ")... ne touche pas à la salle.");
                Journal.debug("copie : attemptExport(\"" + nomFinal + "\", " + x0 + ", " + y0
                        + ", " + lx + ", " + ly + ")");
                // Ce que le parcours dans le chat (:exportpreset, deux clics, le nom)
                // aurait rempli : la fin de l'export (apres la lecture des wired et
                // des variables) relit les deux coins et le nom. Vides, elle
                // plantait sur rectCorner1 null.
                remplir(exp, "rectCorner1", new gearth.extensions.parsers.HPoint(x0, y0));
                remplir(exp, "rectCorner2", new gearth.extensions.parsers.HPoint(x0 + lx - 1, y0 + ly - 1));
                remplir(exp, "exportName", nomFinal);
                java.lang.reflect.Method m = extension.tools.GPresetExporter.class.getDeclaredMethod(
                        "attemptExport", String.class, int.class, int.class, int.class, int.class);
                m.setAccessible(true);
                m.invoke(exp, nomFinal, x0, y0, lx, ly);

                // Fini quand le fichier est la et que l'export est revenu au repos.
                // Les wired se lisent un par un : ca peut prendre plusieurs minutes.
                File fichier = new File(dossierApparts(), nomFinal + ".json");
                long debut = System.currentTimeMillis(), dernierPoint = debut;
                while (true) {
                    Salle.sommeil(500);
                    boolean repos = exp.getState() == extension.tools.GPresetExporter.PresetExportState.NONE;
                    if (repos && fichier.isFile()) { Salle.sommeil(500); break; }
                    long ecoule = System.currentTimeMillis() - debut;
                    if (repos && ecoule > 5000) {
                        dire.accept("Copie impossible : l'Atelier s'est arrêté sans écrire « " + nomFinal + " ». Regarde son message dans le jeu.");
                        return;
                    }
                    if (ecoule > 10 * 60_000L) { dire.accept("Copie impossible : plus de 10 minutes. Tape :abort dans le jeu."); return; }
                    if (System.currentTimeMillis() - dernierPoint > 15_000) {
                        dernierPoint = System.currentTimeMillis();
                        note("Copie en cours (" + (ecoule / 1000) + " s, état de l'export : " + exp.getState() + ")...");
                    }
                }

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
                        // Ecriture atomique : surveillerDossier fait relire le moteur de l'Atelier pendant ce temps.
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
                dire.accept("Appart « " + nomFinal + " » enregistré : " + (fs == null ? 0 : fs.length()) + " sols, "
                        + (ws == null ? 0 : ws.length()) + " murs, " + nWired + " réglages wired." + floor);
            } catch (Throwable t) {
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                c.printStackTrace();
                dire.accept("Copie impossible : " + lisible(c) + ".");
            }
        });
    }

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
                    GPresets gp = AtelierLauncher.moteur();
                    FloorState s = gp == null ? null : gp.getFloorState();
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
                    String sig = empreinte(salle, so, mu, furnidataPrete());
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

    /** Cache type -> {nom, origine}, cle (typeId << 1 | mur). Rempli seulement furnidata prete. */
    private final Map<Long, String[]> cacheTypes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Suivi des prix pour la colonne Prix (fil atelier-suivi-salle seulement). */
    private String prixSignature = null;
    private long prixCalcule = 0;
    private volatile boolean prixManquants = false;

    private String[] nomOrigine(GPresets gp, int typeId, boolean mur, boolean fd) {
        long k = ((long) typeId << 1) | (mur ? 1 : 0);
        String[] r = cacheTypes.get(k);
        if (r != null) return r;
        r = new String[]{ nom(gp, cls(gp, typeId, mur), mur), origine(gp, typeId, mur) };
        if (fd) cacheTypes.put(k, r);
        return r;
    }

    /** Compte la salle hors du fil JavaFX, puis met le tableau a jour. */
    private void calculerSalle(GPresets gp, List<HFloorItem> sols, List<HWallItem> murs, boolean dedans) {
        if (!dedans) {
            Platform.runLater(() -> {
                brutSalle.clear();
                cptSalle.setText("Pas dans une salle");
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
        int nSols = 0, nMurs = 0;
        if (sols != null) for (HFloorItem it : sols) {
            nSols++;
            String[] no = nomOrigine(gp, it.getTypeId(), false, fd);
            cle(compte, no[0], no[1], poseur(it.getOwnerName()), false);
            types.putIfAbsent(no[0] + "|sol", it.getTypeId());
        }
        if (murs != null) for (HWallItem it : murs) {
            nMurs++;
            String[] no = nomOrigine(gp, it.getTypeId(), true, fd);
            cle(compte, no[0], no[1], poseur(it.getOwnerName()), true);
            types.putIfAbsent(no[0] + "|mur", it.getTypeId());
        }
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
            cptSalle.setText(a + " sols · " + b + " murs · " + (a + b) + " mobis"
                    + (fd ? "" : " (noms techniques : furnidata en chargement)"));
            majProprietaires();
            filtrerSalle();
        });
    }

    private static String texteNomSalle(GPresets gp) {
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

        GPresets gp = AtelierLauncher.moteur();
        JSONArray sols = o.optJSONArray("furni"), murs = o.optJSONArray("wallFurni");
        int nSols = sols == null ? 0 : sols.length(), nMurs = murs == null ? 0 : murs.length();
        cptAppart.setText(nSols + " sols · " + nMurs + " murs · " + (nSols + nMurs) + " mobis");

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

    private void agreger(GPresets gp, JSONArray arr, boolean mur, Map<String, int[]> compte) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            String c = w.optString("className", null);
            if (c == null || c.isEmpty()) c = w.optString("name", null);
            Integer tid = null;
            try {
                furnidata.FurniDataTools fd = gp.getFurniDataTools();
                if (fd != null && fd.isReady())
                    tid = mur ? fd.getWallTypeId(base(c)) : fd.getFloorTypeId(base(c));
            } catch (Throwable ignored) { }
            // Un appart enregistre ne retient pas qui avait pose quoi.
            cle(compte, nom(gp, base(c), mur),
                    tid == null ? INVENTAIRE : origine(gp, tid, mur), "—", mur);
        }
    }

    private static void cle(Map<String, int[]> m, String nom, String origine,
                            String proprietaire, boolean mur) {
        int[] c = m.computeIfAbsent(nom + "\0" + origine + "\0" + proprietaire,
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
            String[] p = e.getKey().split("\0", 3);
            String nom = p[0];
            String orig = p.length > 1 ? p[1] : "?";
            String pr   = p.length > 2 ? p[2] : "—";
            if (e.getValue()[0] > 0)
                sortie.add(new Ligne(nom, e.getValue()[0], "sol", orig, pr));
            if (e.getValue()[1] > 0)
                sortie.add(new Ligne(nom, e.getValue()[1], "mur", orig, pr));
        }
    }

    private void filtrerSalle() {
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
        return l.getNom() + "\0" + l.getType() + "\0" + l.getOrigine() + "\0" + l.getProprietaire()
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

    /**
     * BC ou non, d'apres le drapeau isBC de la furnidata.
     *
     * La date d'expiration ne marchait pas : les murs posés depuis le BC ne
     * l'ont pas toujours renseignée, et tout se retrouvait classé « inventaire ».
     * isBC est une propriété du mobi lui-même, donc fiable.
     */
    private static String origine(GPresets gp, int typeId, boolean mur) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                String cls = mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
                if (cls != null) {
                    // FurniDetails est package-private : on passe par les
                    // sous-classes publiques, qui heritent du champ isBC.
                    if (mur) {
                        furnidata.details.WallItemDetails d = fd.getWallItemDetails(cls);
                        if (d != null) return d.isBC ? "BC" : INVENTAIRE;
                    } else {
                        furnidata.details.FloorItemDetails d = fd.getFloorItemDetails(cls);
                        if (d != null) return d.isBC ? "BC" : INVENTAIRE;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return INVENTAIRE;
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

    private static String cls(GPresets gp, int typeId, boolean mur) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady())
                return mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
        } catch (Throwable ignored) { }
        return "type " + typeId;
    }

    private static String nom(GPresets gp, String className, boolean mur) {
        String c = base(className);
        if (c == null || c.isEmpty()) return "Inconnu";
        if (gp != null) try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                String n = detail(fd, c, mur);
                if (n == null && c.contains("*"))
                    n = detail(fd, c.substring(0, c.indexOf('*')), mur);
                if (n != null) return n;
            }
        } catch (Throwable ignored) { }
        return c;
    }

    private static String detail(furnidata.FurniDataTools fd, String c, boolean mur) {
        try {
            Object d = mur ? fd.getWallItemDetails(c) : fd.getFloorItemDetails(c);
            if (d == null) return null;
            String n = mur ? ((furnidata.details.WallItemDetails) d).name
                           : ((furnidata.details.FloorItemDetails) d).name;
            return (n == null || n.isEmpty()) ? null : n;
        } catch (Throwable t) { return null; }
    }

    // --------------------------------------------------------------- Discord

    /** Format demande : "- Nom xQuantite", une ligne par mobi. */
    private void copier(TableView<Ligne> t, String titre) {
        if (t.getItems().isEmpty()) { noteSalle("Rien à copier : la liste est vide."); return; }
        // Les lignes sont detaillees par type et par poseur ; pour le client on
        // regroupe par mobi, sinon un meme meuble pose par deux personnes
        // apparaitrait deux fois.
        Map<String, Integer> parMobi = new LinkedHashMap<>();
        for (Ligne l : t.getItems())
            parMobi.merge(l.getNom(), l.getQuantite(), Integer::sum);

        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> e : parMobi.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" x").append(e.getValue()).append('\n');
            total += e.getValue();
        }
        ClipboardContent c = new ClipboardContent();
        c.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(c);
        noteSalle("Liste copiée : " + parMobi.size() + " ligne(s), " + total + " objet(s).");
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
     * Le dossier des presets : le MEME que le moteur de presets embarque
     * (PresetConfigUtils.presetPath : %APPDATA%\G-Presets\presets sous Windows,
     * ~/Library/Application Support/G-Presets/presets sur Mac, avec user.home
     * du processus, comme lui). Repli identique s'il est absent.
     */
    static File dossierApparts() {
        try { return new File(extension.tools.presetconfig.PresetConfigUtils.presetPath()); }
        catch (Throwable ignored) { }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String ad = System.getenv("APPDATA");
            if (ad == null || ad.isEmpty()) ad = System.getProperty("user.home") + "/AppData/Roaming";
            return Paths.get(ad, "G-Presets", "presets").toFile();
        }
        return Paths.get(System.getProperty("user.home"),
                "Library", "Application Support", "G-Presets", "presets").toFile();
    }

    private void chargerListeApparts() {
        // Toujours sur le fil graphique : un setAll() concurrent faisait planter
        // ListChangeBuilder pendant que la liste etait deja en cours de mise a jour.
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::chargerListeApparts);
            return;
        }
        File[] fs = dossierApparts().listFiles((d, n) -> n.endsWith(".json"));
        List<String> noms = new ArrayList<>();
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparing(File::getName));
            for (File f : fs) noms.add(f.getName().substring(0, f.getName().length() - 5));
        }
        String garde = choixAppart.getValue();
        choixAppart.getItems().setAll(noms);
        if (garde != null && noms.contains(garde)) choixAppart.setValue(garde);
        else if (!noms.isEmpty()) choixAppart.setValue(noms.get(0));
    }

    // ----------------------------------------------------------------- outils

    /** Raison d'une exception, sans le nom de classe Java quand il y a un message. */
    private static String lisible(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static boolean furnidataPrete() {
        try {
            GPresets gp = AtelierLauncher.moteur();
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

    /** Une ligne de filtre : son nom a gauche (largeur fixe), le choix a droite. */
    private static HBox filtre(String nom, javafx.scene.Node choix) {
        Label l = Ui.etiquette(nom);
        l.setMinWidth(62); l.setPrefWidth(62);
        HBox h = new HBox(8, l, choix);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(choix, Priority.ALWAYS);
        return h;
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
        cn.setMinWidth(120); cn.setPrefWidth(200); cn.setMaxWidth(4000);
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
        TableColumn<Ligne, String> co = new TableColumn<>("Origine");
        co.setCellValueFactory(new PropertyValueFactory<>("origine"));
        fixe(co, 78);
        TableColumn<Ligne, String> cp = new TableColumn<>("À qui");
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

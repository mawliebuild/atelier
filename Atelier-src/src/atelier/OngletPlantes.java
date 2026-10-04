package atelier;

import atelier.PlanteSuivi.Plante;
import extension.GPresets;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Monster Plants : les plantes monstres de la salle ouverte.
 *
 *  - liste des plantes (paquet Users a l'entree de la salle, voir PlanteSuivi)
 *  - details lus PASSIVEMENT : fiches (PetInfo) que le jeu recoit quand tu
 *    cliques une plante toi-meme, mises a jour de statut / niveau
 *  - fiches lues toutes seules en arriere-plan (demarrerFichesAuto), sans
 *    rien ouvrir dans le jeu.
 *  - « Traiter » (le soin quotidien = respect d'animal), une ou toutes
 *  - pour ses propres plantes : « Recolter » les adultes, « Composter » les mortes
 *
 * Rien ne deplace l'avatar : un double-clic pose seulement la zone partagee
 * sur la case de la plante.
 */
public class OngletPlantes {

    /** Sous ce delai, une plante est en danger (rouge vif). */
    private static final long URGENT_S = 6 * 3600;
    /** Sous ce delai, une plante merite un soin bientot (rouge pale). */
    private static final long BIENTOT_S = 24 * 3600;

    private final ObservableList<Plante> lignes = FXCollections.observableArrayList();
    private TableView<Plante> table;
    private Label etat, resume, soinsLbl;
    private TextField monNomTxt;
    private Ui.Voyant vSalle, vListe, vInfos, vMoi;
    private Button stop;
    private Label reproLbl;
    private final Label apercuRepro = new Label();
    private Button confirmerRepro, annulerRepro;
    private VBox confirmationRepro;

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

    private Pane volet() {
        etat = Ui.etat();
        resume = Ui.valeur("Aucune plante vue");
        soinsLbl = new Label("");
        soinsLbl.setWrapText(true);

        vSalle = new Ui.Voyant("Salle");
        vListe = new Ui.Voyant("Plantes");
        vInfos = new Ui.Voyant("Détails");
        vMoi = new Ui.Voyant("Moi");

        // --- tableau
        table = new TableView<>(lignes);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-font-size: 11px;");
        table.setPlaceholder(new Label("Aucune plante vue dans cette salle."));
        table.getColumns().add(colonne("Plante", 105,
                p -> p.nom + "\n" + (p.proprioNom == null || p.proprioNom.isEmpty() ? "?" : p.proprioNom)));
        // Etat et croissance disent la meme chose : une seule colonne. Une
        // plante qui pousse montre son niveau et le temps restant.
        table.getColumns().add(colonne("État", 96, p -> {
            if (p.morte || p.recoltable || p.adulte()) return p.etat();
            return "Pousse " + p.croissance();
        }));
        table.getColumns().add(colonne("Vie", 52, p -> {
            if (p.morte) return "—";
            long r = p.resteVie();
            return r < 0 ? "?" : PlanteSuivi.duree(r);
        }));
        table.getColumns().add(colonne("Rar.", 30, p -> p.rarete < 0 ? "?" : String.valueOf(p.rarete)));
        table.setRowFactory(tv -> {
            TableRow<Plante> r = new TableRow<>() {
                @Override protected void updateItem(Plante p, boolean vide) {
                    super.updateItem(p, vide);
                    styler(this);
                }
            };
            r.selectedProperty().addListener((o, a, b) -> styler(r));
            r.setOnMouseClicked(e -> {
            });
            return r;
        });
        table.setPrefHeight(260);
        table.setMinHeight(170);
        VBox.setVgrow(table, Priority.ALWAYS);

        stop = new Button("Arrêter");
        stop.setDisable(true);
        stop.setOnAction(e -> { arret = true; dire("Arrêt demandé…"); });

        // --- soins
        Button traiter = new Button("Traiter");
        traiter.setOnAction(e -> {
            Plante p = choisie();
            if (p == null) { dire("Choisis une plante dans le tableau."); return; }
            lancer("plantes-traiter", () -> traiterUne(p, true));
        });
        Button traiterToutes = new Button("Tout traiter");
        // survol : la premiere plante a traiter a la fleche du jeu (une seule a la fois)
        MiseEnValeur.auSurvol(traiterToutes, () -> {
            for (Plante p : trier(PlanteSuivi.plantes()))
                if (!p.morte && p.aBesoin() && p.index >= 0) return List.of("p" + p.index);
            return List.of();
        });
        traiterToutes.getStyleClass().add("primaire");
        traiterToutes.setMaxWidth(Double.MAX_VALUE);
        traiterToutes.setOnAction(e -> lancer("plantes-traiter-toutes", this::traiterToutes));

        // --- mes plantes
        monNomTxt = new TextField();
        monNomTxt.setPromptText("Mon nom Habbo (si non détecté)");
        Button recolter = new Button("Récolter");
        recolter.setTooltip(new Tooltip("Ta plante adulte choisie dans le tableau : tu récupères ses graines / sa récompense (la plante disparaît)."));
        recolter.setOnAction(e -> {
            Plante p = choisie();
            if (p == null) { dire("Choisis une plante dans le tableau."); return; }
            String nom = monNomTxt.getText();
            lancer("plantes-recolter", () -> recolterUne(p, nom, true));
        });
        Button toutRecolter = new Button("Tout récolter");
        toutRecolter.setTooltip(new Tooltip("Récolte toutes tes plantes adultes de la salle, une par une."));
        toutRecolter.setOnAction(e -> {
            String nom = monNomTxt.getText();
            lancer("plantes-tout-recolter", () -> toutRecolter(nom));
        });
        Button composter = new Button("Composter");
        composter.setTooltip(new Tooltip("Ta plante MORTE choisie : elle est retirée définitivement (en échange d'un petit gain dans le jeu)."));
        composter.setOnAction(e -> composter());
        Button toutComposter = new Button("Tout composter");
        toutComposter.setTooltip(new Tooltip("Composte toutes tes plantes MORTES de l'appart (définitif)."));
        toutComposter.setOnAction(e -> toutComposter());

        // --- reproduction
        reproLbl = new Label();                    // consigne permanente : pas un resultat
        reproLbl.getStyleClass().add("etat-ligne");
        reproLbl.setWrapText(true);
        Button reproduire = new Button("Reproduire toutes les plantes");
        reproduire.getStyleClass().add("primaire");
        reproduire.setMaxWidth(Double.MAX_VALUE);
        reproduire.setOnAction(e -> preparerReproduction());
        confirmerRepro = new Button("Confirmer");
        confirmerRepro.getStyleClass().add("primaire");
        annulerRepro = new Button("Annuler");
        confirmationRepro = new VBox(6, apercuRepro, Ui.ligne(annulerRepro, confirmerRepro));
        apercuRepro.setWrapText(true);
        confirmationRepro.setVisible(false);
        confirmationRepro.setManaged(false);
        annulerRepro.setOnAction(e -> montrerConfirmation(false));

        // --- toutes les actions au meme endroit ; « i » explique chaque bouton
        for (Button b : new Button[]{traiterToutes, reproduire}) b.setMaxWidth(Double.MAX_VALUE);
        // Un seul « Traiter » : il traite tout seul celles qui en ont besoin.
        // Les boutons gardent leur texte entier (retour a la ligne si la fenetre est etroite).
        FlowPane autres = new FlowPane(6, 6, recolter, toutRecolter, composter, toutComposter);
        for (Button b : new Button[]{recolter, toutRecolter, composter, toutComposter}) b.setMinWidth(Region.USE_PREF_SIZE);
        VBox boutons = new VBox(6, traiterToutes, reproduire, autres);
        Label aideActions = Ui.aide(
                "Tout traiter : le soin du jour (respect d'animal) pour les plantes qui en ont besoin (bien-être bas) ; "
                + "il remonte au maximum. Nombre de soins limité par jour.\n"
                + "Reproduire toutes les plantes : couples des plus hauts niveaux de rareté aux plus bas (une plante "
                + "seule va avec le niveau juste en dessous). La première fois, fais une reproduction à la main "
                + "dans le jeu : l'Atelier apprend comment faire.\n"
                + "Récolter : ta plante ADULTE choisie te donne sa récompense (graines…), elle disparaît.\n"
                + "Tout récolter : toutes tes plantes adultes de l'appart, une par une.\n"
                + "Composter : supprime ta plante MORTE choisie (définitif).\n"
                + "Tout composter : toutes tes plantes mortes de l'appart, après confirmation (définitif).\n"
                + "Récolter et Composter ne marchent que sur tes plantes. Choisis une ligne du tableau : "
                + "la plante a la flèche de sélection dans le jeu.");
        VBox actions = Ui.bloc("Actions", soinsLbl, boutons, confirmationRepro, reproLbl, aideActions);

        VBox v = new VBox(12,
                Ui.bloc("Prérequis", vSalle, vListe, vInfos, vMoi),
                actions,
                Ui.bloc("Plantes de la salle", resume, table, Ui.ligne(stop)),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        PlanteSuivi.ecouter(this::prevoirMaj);
        MiseEnValeur.fournir("plantes", this::plantesEnValeur);
        PlanteSuivi.installer();

        demarrerListeAuto();
        demarrerFichesAuto();
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            try { majVoyants(); } catch (Throwable ignored) { }
            try { majRepro(); } catch (Throwable ignored) { }
            if (++tic % 20 == 0) { try { table.refresh(); } catch (Throwable ignored) { } }
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        rafraichir();
        return v;
    }

    private static TableColumn<Plante, String> colonne(String titre, double larg, Function<Plante, String> f) {
        TableColumn<Plante, String> c = new TableColumn<>(titre);
        c.setCellValueFactory(d -> {
            String s;
            try { s = d.getValue() == null ? "" : f.apply(d.getValue()); } catch (Throwable t) { s = "?"; }
            return new SimpleStringProperty(s);
        });
        c.setPrefWidth(larg);
        c.setMaxWidth(larg * 30);
        c.setSortable(false);
        return c;
    }

    /** Rouge si elle va bientot mourir, gris si morte ; rien si selectionnee. */
    private static void styler(TableRow<Plante> r) {
        Plante p = r.getItem();
        if (p == null || r.isEmpty() || r.isSelected()) { r.setStyle(""); return; }
        if (p.morte) { r.setStyle("-fx-opacity: 0.5;"); return; }
        long v = p.resteVie();
        if (v >= 0 && v < URGENT_S) r.setStyle("-fx-background-color: #f2b8b0;");
        else if (v >= 0 && v < BIENTOT_S) r.setStyle("-fx-background-color: #fbe1dc;");
        else r.setStyle("");
    }

    /** Fenetre Monster Plants ouverte : la plante choisie dans le tableau a la fleche du jeu. */
    private Collection<String> plantesEnValeur() {
        Plante p = choisie();
        return p != null && p.index >= 0 ? List.of("p" + p.index) : List.of();
    }

    private Plante choisie() {
        return table == null ? null : table.getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------- mises a jour

    private void prevoirMaj() {
        if (majPrevue.compareAndSet(false, true))
            Platform.runLater(() -> { majPrevue.set(false); rafraichir(); });
    }

    /** Ordre : la plus urgente d'abord, inconnues ensuite, mortes a la fin. */
    private static int rangUrgence(Plante p) { return p.morte ? 2 : (p.resteVie() < 0 ? 1 : 0); }

    private static List<Plante> trier(List<Plante> l) {
        l.sort(Comparator.comparingInt(OngletPlantes::rangUrgence)
                .thenComparingLong(Plante::resteVie)
                .thenComparing(p -> p.nom == null ? "" : p.nom, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    private void rafraichir() {
        Plante sel = choisie();
        List<Plante> l = trier(PlanteSuivi.plantes());
        lignes.setAll(l);
        if (sel != null) {
            for (Plante p : l) if (p.id == sel.id) { table.getSelectionModel().select(p); break; }
        }
        table.refresh();
        int vivantes = 0, mortes = 0, aRecolter = 0, danger = 0;
        for (Plante p : l) {
            if (p.morte) mortes++; else vivantes++;
            if (!p.morte && p.recoltable) aRecolter++;
            long v = p.resteVie();
            if (!p.morte && v >= 0 && v < URGENT_S) danger++;
        }
        resume.setText(l.isEmpty() ? "Aucune plante vue"
                : l.size() + " plante(s) · " + vivantes + " vivante(s) · " + mortes + " morte(s)"
                  + (aRecolter > 0 ? " · " + aRecolter + " à récolter" : "")
                  + (danger > 0 ? " · " + danger + " en danger" : ""));
        int s = PlanteSuivi.soinsRestants;
        soinsLbl.setText(s < 0 ? "Soins restants aujourd'hui : inconnu (lu à la connexion)."
                : "Soins restants aujourd'hui : " + s);
        if ((monNomTxt.getText() == null || monNomTxt.getText().isBlank()) && PlanteSuivi.monNom != null)
            monNomTxt.setPromptText("Moi : " + PlanteSuivi.monNom);
    }

    private void majVoyants() {
        GPresets gp = Salle.gp();
        if (gp == null) vSalle.regler("absent", "L'Atelier n'est pas encore prêt");
        else if (!Salle.dansUneSalle()) vSalle.regler("absent", "Entre dans un appart.");
        else vSalle.regler("ok", "Oui.");

        int n = lignes.size();
        if (!PlanteSuivi.branche()) vListe.regler("attente", "Écoute en cours d'installation…");
        else if (PlanteSuivi.nbUsers == 0)
            vListe.regler("absent", "Demandée au jeu…");
        else vListe.regler(n > 0 ? "ok" : "attente", n > 0 ? n + " suivie(s)" : "Aucune vue");

        // Combien de plantes ont leur fiche, et non le nombre de paquets recus
        // (qui montait sans fin : le jeu en redemande aussi de lui-meme).
        int avecFiche = 0;
        for (Plante p : lignes) if (p.infos) avecFiche++;
        if (n == 0) vInfos.regler("attente", "Aucune plante");
        else vInfos.regler(avecFiche == n ? "ok" : "attente",
                avecFiche + " / " + n + " lu(s)"
                + (avecFiche < n ? (occupe.get() ? " — lecture en cours…" : " — lecture automatique en cours…") : ""));

        String moi = PlanteSuivi.monNom;
        String saisi = monNomTxt.getText();
        if (moi != null) vMoi.regler("ok", moi + (PlanteSuivi.monId > 0 ? " (#" + PlanteSuivi.monId + ")" : ""));
        else if (saisi != null && !saisi.isBlank()) vMoi.regler("ok", saisi.trim() + " (saisi)");
        else vMoi.regler("attente", "Pas encore détecté (il arrive à la connexion)");
    }

    private void dire(String s) {
        if (Platform.isFxApplicationThread()) etat.setText(s);
        else Platform.runLater(() -> etat.setText(s));
    }

    /** Resultat d'une action : dans le jeu (Journal), le genre dit explicitement. */
    private void succes(String s) { Ui.succes(etat, s); }
    private void erreur(String s) { Ui.erreur(etat, s); }

    // -------------------------------------------------------------- travaux

    /** Un seul travail reseau a la fois, hors fil JavaFX. */
    private void lancer(String nom, Runnable r) {
        if (!occupe.compareAndSet(false, true)) { dire("Un travail est déjà en cours (« Arrêter » pour l'interrompre)."); return; }
        arret = false;
        Platform.runLater(() -> stop.setDisable(false));
        Salle.tache(nom, () -> {
            // Seul endroit ou les envois vers les plantes sont permis : un bouton.
            try { PlanteSuivi.enActionExplicite(r); }
            catch (Throwable t) { Ui.erreur(etat, "Erreur pendant le travail sur les plantes", t); }
            finally {
                occupe.set(false);
                Platform.runLater(() -> stop.setDisable(true));
            }
        });
    }

    private boolean pret() {
        if (Salle.gp() == null) { erreur("Impossible : l'Atelier n'est pas encore prêt."); return false; }
        if (!Salle.dansUneSalle()) { erreur("Impossible : entre d'abord dans une salle."); return false; }
        if (!PlanteSuivi.branche()) { dire("L'écoute des paquets s'installe : encore un instant."); return false; }
        return true;
    }

    /** Envoie GetPetInfo et attend la fiche (1,5 s au plus). */
    private boolean lireInfo(Plante p) {
        long t0 = System.currentTimeMillis();
        if (!PlanteSuivi.demanderInfo(p.id)) return false;
        for (int i = 0; i < 30; i++) {
            Salle.sommeil(50);
            Plante q = PlanteSuivi.plante(p.id);
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

    private boolean traiterUne(Plante p, boolean seule) {
        if (!pret()) return false;
        if (p.morte) { if (seule) dire(p.nom + " est morte : on ne peut plus la traiter."); return false; }
        Soin r = traiter(p);
        prevoirMaj();
        if (seule) {
            if (r == Soin.FAIT) succes(p.nom + " traitée.");
            else erreur(r == Soin.REFUSE ? p.nom + " : soin refusé par le jeu (déjà traitée, ou plus de soins aujourd'hui)."
                    : p.nom + " : soin envoyé, effet non confirmé.");
        }
        return r == Soin.FAIT;
    }

    /**
     * Traite TOUTES les plantes vivantes (ou seulement celles qui en ont besoin
     * si la case est cochee). On ne s'arrete plus sur une simple absence de
     * confirmation : seulement quand le serveur refuse trois fois de suite,
     * signe qu'il n'y a plus de soins aujourd'hui.
     */
    private void traiterToutes() {
        if (!pret()) return;
        boolean filtre = true;          // seulement celles qui en ont besoin
        List<Plante> l = new ArrayList<>();
        for (Plante p : trier(PlanteSuivi.plantes()))
            if (!p.morte && (!filtre || p.aBesoin())) l.add(p);
        if (l.isEmpty()) { dire(filtre ? "Aucune plante n'a besoin de soin." : "Aucune plante vivante."); return; }
        int faits = 0, refus = 0, incertains = 0, refusDeSuite = 0, i = 0;
        String fin = null;
        for (Plante p : l) {
            if (arret) { fin = "arrêté"; break; }
            dire("Soins… " + (++i) + "/" + l.size() + " : " + p.nom);
            Soin r = traiter(p);
            if (r == Soin.FAIT) { faits++; refusDeSuite = 0; }
            else if (r == Soin.REFUSE) {
                refus++;
                if (++refusDeSuite >= 3) { fin = "le jeu refuse : plus de soins disponibles aujourd'hui"; break; }
            } else { incertains++; refusDeSuite = 0; }
            Salle.sommeil(700);
        }
        try { PlanteSuivi.demanderProfil(); } catch (Throwable ignored) { }   // soins restants a jour
        prevoirMaj();
        String bilan = faits + " plante(s) traitée(s)"
                + (incertains > 0 ? ", " + incertains + " sans confirmation" : "")
                + (refus > 0 ? ", " + refus + " refusée(s) (déjà traitées ?)" : "")
                + (fin != null ? " — " + fin + "." : ".");
        if (faits == 0 && (refus > 0 || incertains > 0)) erreur(bilan); else succes(bilan);
    }

    // ----------------------------------------------------- liste auto

    /**
     * Surveillance passive. Si l'ecoute s'est installee apres ton entree dans
     * la salle, la liste Users a ete manquee : on redemande le contenu de la
     * salle (GetHeightMap, comme le moteur de l'Atelier), une fois par salle. Ce n'est pas
     * un clic : aucune plante n'est visee ni selectionnee. On ne demande
     * JAMAIS la fiche d'une plante ici (le jeu la montrerait comme un clic).
     */
    private void demarrerListeAuto() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    int salle = -1;
                    try { salle = Salle.etat() == null ? -1 : Salle.etat().getRoomId(); } catch (Throwable ignored) { }
                    if (salle > 0 && salle != salleListeDemandee && PlanteSuivi.branche()
                            && PlanteSuivi.nbUsers == 0 && PlanteSuivi.plantes().isEmpty()) {
                        salleListeDemandee = salle;
                        try { PlanteSuivi.redemanderSalle(); } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
                Salle.sommeil(2000);
            }
        }, "atelier-plantes-liste");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Les fiches des plantes (vie, croissance, rarete) se lisent toutes seules,
     * vite et sans rien ouvrir dans le jeu (PlanteSuivi.demanderInfoSilencieuse) :
     * celles jamais lues ou lues il y a plus de 10 min, une toutes les 150 ms.
     */
    private final java.util.Map<Integer, Long> demandees = new java.util.concurrent.ConcurrentHashMap<>();

    private void demarrerFichesAuto() {
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(2000);
                try {
                    if (!Salle.installeeDepuis(3000) || occupe.get()) continue;
                    List<Plante> l = PlanteSuivi.aLire(trier(PlanteSuivi.plantes()), System.currentTimeMillis());
                    int n = 0;
                    long maintenant = System.currentTimeMillis();
                    for (Plante p : l) {
                        if (occupe.get() || n >= 40) break;
                        Long d = demandees.get(p.id);
                        if (d != null && maintenant - d < 60_000) continue;     // deja demandee il y a peu
                        demandees.put(p.id, maintenant);
                        n++;
                        PlanteSuivi.demanderInfoSilencieuse(p.id);
                        Salle.sommeil(150);
                    }
                    if (n > 0) prevoirMaj();
                } catch (Throwable ignored) { }
            }
        }, "atelier-plantes-fiches");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------- reproduction

    private volatile int salleListeDemandee = -1;
    private List<Plante[]> couplesPrevus = List.of();

    private List<Plante[]> couplesPossibles() {
        String nom = monNomTxt.getText();
        List<Plante> dispo = new ArrayList<>();
        for (Plante p : PlanteSuivi.plantes()) if (Reproduction.disponible(p, nom)) dispo.add(p);
        return Reproduction.couples(dispo);
    }

    private void majRepro() {
        int n = couplesPossibles().size();
        reproLbl.setText((Reproduction.appris() ? "" : "Pas encore appris : fais une reproduction à la main une fois.  ")
                + (n == 0 ? "Aucun couple possible pour l'instant." : n + " couple(s) possible(s)."));
    }

    private void preparerReproduction() {
        if (!Reproduction.appris()) {
            dire("Fais d'abord une reproduction à la main dans le jeu : l'Atelier apprend comment faire.");
            return;
        }
        couplesPrevus = couplesPossibles();
        if (couplesPrevus.isEmpty()) { dire("Aucun couple possible : il faut des plantes adultes qui peuvent se reproduire."); return; }
        StringBuilder sb = new StringBuilder(couplesPrevus.size() + " couple(s), des plus hauts niveaux aux plus bas :");
        int i = 0;
        for (Plante[] c : couplesPrevus) {
            if (++i > 8) { sb.append("\n…"); break; }
            sb.append("\n• ").append(c[0].nom).append(" (niveau ").append(c[0].rarete).append(") + ")
              .append(c[1].nom).append(" (niveau ").append(c[1].rarete).append(')');
        }
        apercuRepro.setText(sb.toString());
        confirmerRepro.setOnAction(e -> {
            montrerConfirmation(false);
            List<Plante[]> l = couplesPrevus;
            lancer("plantes-reproduction", () -> reproduireTout(l));
        });
        montrerConfirmation(true);
    }

    private void montrerConfirmation(boolean v) {
        confirmationRepro.setVisible(v);
        confirmationRepro.setManaged(v);
    }

    private void reproduireTout(List<Plante[]> couples) {
        if (!pret()) return;
        int ok = 0, echecs = 0, i = 0;
        for (Plante[] c : couples) {
            if (arret) break;
            dire("Reproduction " + (++i) + "/" + couples.size() + " : " + c[0].nom + " + " + c[1].nom);
            if (Reproduction.reproduire(c[0].id, c[1].id)) ok++; else echecs++;
            lireInfo(c[0]);
            lireInfo(c[1]);
            Salle.sommeil(1000);
        }
        prevoirMaj();
        String bilan = ok + " reproduction(s) réussie(s)" + (echecs > 0 ? ", " + echecs + " sans résultat du jeu" : "")
                + (arret ? " (arrêté)." : ".") + (ok > 0 ? " Les graines sont dans ton inventaire." : "");
        if (ok == 0 && echecs > 0) erreur(bilan); else succes(bilan);
    }

    private boolean recolterUne(Plante p, String nom, boolean seule) {
        if (!pret()) return false;
        if (!aMoiConnu(nom)) return false;
        if (!PlanteSuivi.estAMoi(p, nom)) { if (seule) dire(p.nom + " n'est pas à toi."); return false; }
        if (p.morte) { if (seule) dire(p.nom + " est morte : composte-la plutôt."); return false; }
        if (!p.recoltable && !p.adulte()) { if (seule) dire(p.nom + " n'est pas encore adulte."); return false; }
        if (!PlanteSuivi.recolter(p.id)) { if (seule) erreur("Échec : récolte de " + p.nom + " non envoyée."); return false; }
        if (seule) succes("Récolte de " + p.nom + " demandée.");
        return true;
    }

    private void toutRecolter(String nom) {
        if (!pret() || !aMoiConnu(nom)) return;
        int n = 0;
        for (Plante p : trier(PlanteSuivi.plantes())) {
            if (arret) break;
            if (p.morte || !(p.recoltable || p.adulte()) || !PlanteSuivi.estAMoi(p, nom)) continue;
            dire("Récolte de " + p.nom + "…");
            if (recolterUne(p, nom, false)) n++;
            Salle.sommeil(1000);
        }
        if (n == 0) dire("Aucune de tes plantes n'est prête à récolter.");
        else succes(n + " récolte(s) demandée(s)" + (arret ? " (arrêté)." : "."));
    }

    private void composter() {
        Plante p = choisie();
        if (p == null) { dire("Choisis une plante morte dans le tableau."); return; }
        String nom = monNomTxt.getText();
        if (!aMoiConnu(nom)) return;
        if (!PlanteSuivi.estAMoi(p, nom)) { dire(p.nom + " n'est pas à toi."); return; }
        if (!p.morte) { dire(p.nom + " est vivante : on ne composte que les plantes mortes."); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Composter " + p.nom + " ? C'est définitif : la plante disparaît.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("Composter");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) { dire("Compostage annulé."); return; }
        lancer("plantes-composter", () -> {
            if (!pret()) return;
            if (!PlanteSuivi.composter(p.id)) erreur("Échec : compostage de " + p.nom + " non envoyé.");
            else succes("Compostage de " + p.nom + " demandé.");
        });
    }

    /** Composte toutes MES plantes mortes de la salle, apres une seule confirmation. */
    private void toutComposter() {
        String nom = monNomTxt.getText();
        if (!aMoiConnu(nom)) return;
        List<Plante> mortes = new ArrayList<>();
        for (Plante p : PlanteSuivi.plantes()) if (p.morte && PlanteSuivi.estAMoi(p, nom)) mortes.add(p);
        if (mortes.isEmpty()) { dire("Aucune de tes plantes n'est morte dans cet appart."); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Composter tes " + mortes.size() + " plante(s) morte(s) ? C'est définitif : elles disparaissent.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("Tout composter");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) { dire("Compostage annulé."); return; }
        lancer("plantes-tout-composter", () -> {
            if (!pret()) return;
            int n = 0;
            for (Plante p : mortes) {
                if (arret) break;
                dire("Compostage de " + p.nom + "…");
                if (PlanteSuivi.composter(p.id)) n++;
                Salle.sommeil(1000);
            }
            String bilan = n + " plante(s) compostée(s)" + (arret ? " (arrêté)." : ".");
            if (n == 0 && !arret) erreur("Échec : aucun compostage envoyé.");
            else succes(bilan);
        });
    }

    private boolean aMoiConnu(String nom) {
        if (PlanteSuivi.monId > 0 || PlanteSuivi.monNom != null || (nom != null && !nom.isBlank())) return true;
        dire("Je ne sais pas qui tu es : écris ton nom Habbo dans le champ « Mes plantes ».");
        return false;
    }
}

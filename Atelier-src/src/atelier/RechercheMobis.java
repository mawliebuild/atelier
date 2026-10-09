package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.text.Collator;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/**
 * Patrimoine > Recherche de mobis : ou trouver des mobis precis.
 *
 * Tu choisis un ou plusieurs mobis (nom francais, suggestions avec vignette)
 * et la quantite voulue. « Chercher dans les apparts Troc » :
 *   1. lit la liste des apparts de la categorie Troc du navigateur ;
 *   2. demande confirmation (nombre d'apparts, duree) ;
 *   3. regarde le catalogue (vendu ? prix) et la place du marche (offres,
 *      prix le plus bas, moyenne) ;
 *   4. entre dans chaque appart l'un apres l'autre, a un rythme humain (4 a
 *      6 s de pause, au hasard ; au plus 10 s pour charger), compte les mobis
 *      cherches poses (sols et muraux, par type), puis passe au suivant ;
 *   5. te ramene dans l'appart de depart.
 * Les apparts fermes (sonnette, mot de passe, invisibles) sont ignores ; un
 * refus d'entree fait passer au suivant. Rien n'est pose ni achete.
 *
 * Logique pure : RechercheCalcul ; echanges avec le jeu : RechercheReseau.
 */
final class RechercheMobis {

    private static final Preferences PREFS = Preferences.userRoot().node("atelier");
    private static final String PREF_MONDE = "recherche.avecMonde", PREF_LIMITE = "recherche.limite";
    /** Pause entre deux apparts (au hasard dans l'intervalle) et attente du chargement. */
    static final long PAUSE_MIN_MS = 4000, PAUSE_MAX_MS = 6000, CHARGEMENT_MS = 10_000;
    private static final Collator COLLATOR = Collator.getInstance(Locale.FRANCE);
    static { COLLATOR.setStrength(Collator.SECONDARY); }

    /** Un mobi choisi : son type et la quantite voulue (fil FX). */
    private final class Choisi {
        final Furnidata.Mobi mobi;
        final Spinner<Integer> quantite = new Spinner<>(1, 9999, 1);
        final HBox ligne;
        Choisi(Furnidata.Mobi m) {
            mobi = m;
            quantite.setEditable(true);
            quantite.setPrefWidth(78);
            Ui.bulle(quantite, "Quantité voulue.");
            Label nom = new Label(nom(m));
            nom.setMinWidth(0);
            nom.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nom, Priority.ALWAYS);
            Ui.bulle(nom, nom(m) + "\n" + m.className + (m.mural ? " (mural)" : ""));
            Button retirer = Ui.boutonIcone(Icones.RETIRER, "Retirer de la recherche");
            retirer.setOnAction(e -> retirer(this));
            ligne = new HBox(8, vignette(m), nom, quantite, retirer);
            ligne.setAlignment(Pos.CENTER_LEFT);
        }
    }

    // ---------------------------------------------------------------- interface

    private TextField champ;
    private final ContextMenu suggestions = new ContextMenu();
    private final List<Choisi> choisis = new ArrayList<>();
    private final VBox listeChoisis = new VBox(6);
    private Label aucunChoisi;
    private CheckBox avecMonde;
    private Spinner<Integer> limite;
    private Button lancer, arreter;
    private ProgressBar barre;
    private Label progression, resume, etat;
    private HBox ligneProgression;
    private final VBox cartes = new VBox(10);
    private final Map<String, Carte> parCle = new LinkedHashMap<>();
    private final AtomicBoolean arret = new AtomicBoolean(false);
    private volatile boolean enCours = false;

    Tab construire() {
        champ = new TextField();
        champ.setPromptText("Nom du mobi");
        champ.setMaxWidth(Double.MAX_VALUE);
        champ.textProperty().addListener((o, a, b) -> proposer(b));
        champ.setOnAction(e -> {
            List<Furnidata.Mobi> l = chercher(champ.getText(), 1);
            if (!l.isEmpty()) ajouter(l.get(0));
        });
        champ.focusedProperty().addListener((o, a, b) -> { if (!b) suggestions.hide(); });
        aucunChoisi = Ui.discret("Aucun mobi choisi : tape un nom ci-dessus.");
        listeChoisis.getChildren().setAll(aucunChoisi);

        avecMonde = new CheckBox("Seulement les apparts où il y a du monde");
        avecMonde.setSelected(PREFS.getBoolean(PREF_MONDE, false));
        avecMonde.selectedProperty().addListener((o, a, b) -> PREFS.putBoolean(PREF_MONDE, b));
        Ui.bulle(avecMonde, "Garde seulement les apparts Troc où au moins une personne est présente.");
        limite = new Spinner<>(5, 100, Math.max(5, Math.min(100, PREFS.getInt(PREF_LIMITE, 30))), 5);
        limite.setEditable(true);
        limite.setPrefWidth(80);
        limite.valueProperty().addListener((o, a, b) -> { if (b != null) PREFS.putInt(PREF_LIMITE, b); });
        Label au = new Label("Nombre d'apparts au plus");
        Ui.bulle(au, "Limite par recherche : au-delà, les apparts les moins peuplés sont laissés de côté.");
        HBox ligneLimite = new HBox(8, au, limite);
        ligneLimite.setAlignment(Pos.CENTER_LEFT);

        lancer = Ui.bouton(Icones.TROC, "Chercher dans les apparts Troc");
        lancer.getStyleClass().add("primaire");
        lancer.setOnAction(e -> lancer());

        barre = new ProgressBar(0);
        barre.setPrefWidth(90);
        barre.setMinWidth(60);
        progression = new Label();
        progression.getStyleClass().add("etat-ligne");
        progression.setMinWidth(0);
        progression.setPrefWidth(0);
        progression.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progression, Priority.ALWAYS);
        arreter = Ui.bouton(Icones.ARRET, "Arrêter");
        arreter.setMinWidth(Region.USE_PREF_SIZE);
        arreter.setOnAction(e -> { arret.set(true); progression.setText("Arrêt en cours…"); });
        ligneProgression = new HBox(8, barre, progression, arreter);
        ligneProgression.setAlignment(Pos.CENTER_LEFT);
        ligneProgression.managedProperty().bind(ligneProgression.visibleProperty());
        ligneProgression.setVisible(false);
        etat = Ui.etat();

        resume = new Label("Lance une recherche pour voir où trouver tes mobis.");
        resume.setWrapText(true);
        resume.getStyleClass().add("etat-ligne");

        VBox v = new VBox(12,
                Ui.bloc("Mobis cherchés", champ, listeChoisis,
                        Ui.aide("Tape un bout de nom, puis choisis le mobi dans la liste (Entrée prend le "
                                + "premier). Règle la quantité voulue ; la croix le retire.")),
                Ui.bloc("Apparts Troc", avecMonde, ligneLimite, Ui.boutons(lancer), ligneProgression, etat,
                        Ui.aide("L'Atelier lit la liste des apparts de la catégorie Troc du navigateur, "
                                + "puis ton avatar entre dans chacun, l'un après l'autre, à un rythme "
                                + "humain : 4 à 6 secondes entre deux apparts. Les apparts fermés (sonnette, "
                                + "mot de passe) sont ignorés. Il regarde aussi le catalogue et la place du "
                                + "marché. Rien n'est posé ni acheté. À la fin, tu reviens dans ton appart.")),
                Ui.bloc("Résultats", resume, cartes,
                        Ui.aide("Pour chaque mobi : les apparts Troc où il est posé, du plus fourni au "
                                + "moins fourni. Double-clic sur un appart (ou « Y aller ») pour t'y rendre. "
                                + "Clique un titre de colonne pour trier.")));
        v.setFillWidth(true);
        v.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Recherche de mobis", sp);
        t.setClosable(false);
        majBoutons();
        return t;
    }

    // ---------------------------------------------------------------- choix des mobis

    static String nom(Furnidata.Mobi m) {
        return m.name == null || m.name.isBlank() ? m.className : m.name.trim();
    }

    static Node vignette(Furnidata.Mobi m) {
        ImageView iv = new ImageView();
        iv.setFitWidth(PrixVignettes.TAILLE);
        iv.setFitHeight(PrixVignettes.TAILLE);
        iv.setPreserveRatio(true);
        Image i = PrixVignettes.icone(m.className, m.revision);
        iv.setImage(i);
        StackPane s = new StackPane(iv);
        s.setMinSize(PrixVignettes.TAILLE, PrixVignettes.TAILLE);
        s.setPrefSize(PrixVignettes.TAILLE, PrixVignettes.TAILLE);
        return s;
    }

    /** Index : nom normalise -> mobis de ce nom (sols et muraux). Construit une fois. */
    private static volatile Map<String, List<Furnidata.Mobi>> index = null;

    private static Map<String, List<Furnidata.Mobi>> index() {
        Map<String, List<Furnidata.Mobi>> i = index;
        if (i != null) return i;
        if (!Salle.furnidataPrete()) return Map.of();
        Furnidata fd = Salle.gp().getFurniDataTools();
        Map<String, List<Furnidata.Mobi>> m = new HashMap<>();
        List<Furnidata.Mobi> tous = new ArrayList<>(fd.tousSols());
        tous.addAll(fd.tousMurs());
        for (Furnidata.Mobi d : tous)
            m.computeIfAbsent(NomsMobis.normaliser(nom(d)), k -> new ArrayList<>()).add(d);
        index = m;
        return m;
    }

    /** Les mobis dont le nom correspond, les meilleurs d'abord (au plus max). */
    static List<Furnidata.Mobi> chercher(String texte, int max) {
        List<Furnidata.Mobi> r = new ArrayList<>();
        Map<String, List<Furnidata.Mobi>> ix = index();
        if (ix.isEmpty()) return r;
        for (NomsMobis.Nom n : NomsMobis.chercher(texte == null ? "" : texte, max)) {
            List<Furnidata.Mobi> l = ix.get(NomsMobis.normaliser(n.nom));
            if (l == null) continue;
            for (Furnidata.Mobi m : l) { r.add(m); if (r.size() >= max) return r; }
        }
        return r;
    }

    private void proposer(String texte) {
        List<Furnidata.Mobi> l = chercher(texte, 10);
        if (l.isEmpty() || !champ.isFocused()) { suggestions.hide(); return; }
        List<MenuItem> items = new ArrayList<>();
        for (Furnidata.Mobi m : l) {
            Label n = new Label(nom(m));
            Label c = new Label(m.mural ? "mural" : "");
            c.getStyleClass().add("etat-ligne");
            HBox h = new HBox(8, vignette(m), n, c);
            h.setAlignment(Pos.CENTER_LEFT);
            CustomMenuItem it = new CustomMenuItem(h, true);
            it.setOnAction(e -> ajouter(m));
            items.add(it);
        }
        suggestions.getItems().setAll(items);
        if (!suggestions.isShowing()) suggestions.show(champ, Side.BOTTOM, 0, 0);
    }

    private void ajouter(Furnidata.Mobi m) {
        suggestions.hide();
        champ.clear();
        for (Choisi c : choisis)
            if (c.mobi.mural == m.mural && c.mobi.id == m.id) { c.quantite.getValueFactory().increment(1); return; }
        choisis.add(new Choisi(m));
        majListe();
    }

    private void retirer(Choisi c) {
        choisis.remove(c);
        majListe();
    }

    private void majListe() {
        if (choisis.isEmpty()) listeChoisis.getChildren().setAll(aucunChoisi);
        else {
            List<Node> l = new ArrayList<>();
            for (Choisi c : choisis) l.add(c.ligne);
            listeChoisis.getChildren().setAll(l);
        }
        majBoutons();
    }

    private void majBoutons() {
        if (lancer == null) return;
        lancer.setDisable(enCours || choisis.isEmpty());
        ligneProgression.setVisible(enCours);
        champ.setDisable(enCours);
        avecMonde.setDisable(enCours);
        limite.setDisable(enCours);
        for (Choisi c : choisis) c.ligne.setDisable(enCours);
    }

    // ---------------------------------------------------------------- lancement

    private void lancer() {
        if (enCours) return;
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.furnidataPrete()) { Ui.erreur(etat, "Le jeu n'est pas encore prêt : connecte-toi d'abord."); return; }
        if (choisis.isEmpty()) return;
        List<RechercheCalcul.Cible> cibles = new ArrayList<>();
        for (Choisi c : choisis) {
            c.quantite.commitValue();
            Integer q = c.quantite.getValue();
            Furnidata.Mobi m = c.mobi;
            cibles.add(new RechercheCalcul.Cible(m.className, m.mural, m.id, nom(m), m.revision, m.offerId,
                    q == null ? 1 : Math.max(1, q)));
        }
        limite.commitValue();
        boolean monde = avecMonde.isSelected();
        int max = limite.getValue() == null ? 30 : limite.getValue();
        int depart = Salle.salleId();

        enCours = true;
        arret.set(false);
        majBoutons();
        barre.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progression.setText("Lecture de la liste des apparts Troc…");
        Salle.tache("recherche-liste", () -> {
            RechercheCalcul.Choix choix = null;
            String erreur = null;
            try {
                RechercheReseau.installer(gp);
                RechercheReseau.actif = true;
                List<RechercheCalcul.Appart> troc = listeTroc(gp);
                if (troc == null) erreur = "Catégorie « Troc » introuvable dans le navigateur du jeu.";
                else {
                    choix = RechercheCalcul.choisir(troc, monde, max, depart);
                    if (choix.visites().isEmpty())
                        erreur = monde ? "Aucun appart Troc ouvert avec du monde en ce moment."
                                       : "Aucun appart Troc ouvert en ce moment.";
                }
            } catch (Throwable t) {
                erreur = "Lecture de la liste Troc impossible (" + t.getClass().getSimpleName() + ").";
                Journal.debug("recherche : " + t);
            } finally {
                RechercheReseau.actif = false;
            }
            RechercheCalcul.Choix c = choix;
            String err = erreur;
            Platform.runLater(() -> {
                if (err != null || arret.get()) {
                    finir();
                    if (err != null) Ui.erreur(etat, err);
                    return;
                }
                if (!confirmer(c)) { finir(); return; }
                Salle.tache("recherche", () -> tournee(gp, cibles, c.visites(), depart));
            });
        });
    }

    /** Les apparts de la categorie Troc ; null si la categorie est introuvable. */
    private List<RechercheCalcul.Appart> listeTroc(Moteur gp) throws InterruptedException {
        RechercheCalcul.Blocs accueil = RechercheReseau.chercher(gp, "hotel_view", 6000);
        RechercheCalcul.Bloc bloc = accueil == null ? null : RechercheCalcul.blocTroc(accueil.blocs());
        if (bloc != null) {
            Journal.debug("recherche : catégorie Troc = " + bloc.code() + " (« " + bloc.texte() + " »).");
            Salle.sommeil(600);
            RechercheCalcul.Blocs tout = RechercheReseau.chercher(gp, bloc.code(), 6000);
            List<RechercheCalcul.Appart> l = tout == null ? bloc.apparts() : tout.apparts();
            if (!l.isEmpty()) return l;
        }
        // codes connus, au cas ou l'accueil du navigateur ne montre pas la categorie
        for (String code : RechercheCalcul.CODES_TROC) {
            if (arret.get()) return null;
            Salle.sommeil(600);
            RechercheCalcul.Blocs b = RechercheReseau.chercher(gp, code, 4000);
            if (b != null && !b.apparts().isEmpty()) {
                Journal.debug("recherche : catégorie Troc trouvée par le code " + code + ".");
                return b.apparts();
            }
        }
        return null;
    }

    private boolean confirmer(RechercheCalcul.Choix c) {
        int n = c.visites().size();
        int min = RechercheCalcul.minutes(n, (PAUSE_MIN_MS + PAUSE_MAX_MS) / 2, 3000);
        StringBuilder detail = new StringBuilder();
        if (c.fermes() > 0) detail.append("\n").append(c.fermes()).append(c.fermes() > 1 ? " apparts fermés" : " appart fermé")
                .append(" (sonnette, mot de passe) : ignorés.");
        if (c.vides() > 0) detail.append("\n").append(c.vides()).append(c.vides() > 1 ? " apparts vides" : " appart vide").append(" : ignorés.");
        if (c.auDela() > 0) detail.append("\n").append(c.auDela()).append(" de plus au-delà de ta limite de ")
                .append(n).append(".");
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Ton avatar va entrer dans " + n + (n > 1 ? " apparts Troc" : " appart Troc")
                        + ", l'un après l'autre, à un rythme humain (4 à 6 secondes entre deux apparts) : "
                        + "environ " + min + " min. Rien n'est posé ni acheté."
                        + (Salle.salleId() > 0 ? " À la fin, tu reviens dans ton appart." : "")
                        + detail,
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("Recherche dans les apparts Troc");
        try { if (lancer.getScene() != null) a.initOwner(lancer.getScene().getWindow()); } catch (Throwable ignored) { }
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void finir() {
        enCours = false;
        majBoutons();
        progression.setText("");
        barre.setProgress(0);
    }

    // ---------------------------------------------------------------- la tournee (fil de fond)

    private void tournee(Moteur gp, List<RechercheCalcul.Cible> cibles, List<RechercheCalcul.Appart> visites, int depart) {
        Map<String, RechercheCalcul.Resultat> res = new LinkedHashMap<>();
        for (RechercheCalcul.Cible c : cibles) res.put(c.cle(), new RechercheCalcul.Resultat(c));
        Platform.runLater(() -> preparerCartes(cibles));
        int visites_ = 0, refus = 0, sansReponse = 0;
        boolean echecEntree = false, arrete = false;
        Random hasard = new Random();
        RechercheReseau.actif = true;
        try {
            // 1. catalogue et place du marche
            int i = 0;
            for (RechercheCalcul.Resultat r : res.values()) {
                if (arret.get()) break;
                int k = ++i;
                dire("Catalogue et marché : " + r.cible.nom() + " (" + k + "/" + res.size() + ")",
                        (double) k / (res.size() + visites.size()));
                r.catalogue = RechercheReseau.catalogue(gp, r.cible);
                if (arret.get()) break;
                r.marche = RechercheReseau.marche(gp, r.cible);
                montrer(r);
            }

            // 2. les apparts, un par un
            int echecsDeSuite = 0;
            for (int j = 0; j < visites.size() && !arret.get(); j++) {
                RechercheCalcul.Appart a = visites.get(j);
                dire("Appart " + (j + 1) + "/" + visites.size() + " : " + a.nom(),
                        (double) (res.size() + j) / (res.size() + visites.size()));
                RechercheReseau.Entree e = a.id() == Salle.salleId() && RechercheReseau.chargee(gp, a.id())
                        ? RechercheReseau.Entree.OK
                        : RechercheReseau.entrer(gp, a.id(), CHARGEMENT_MS, arret::get);
                if (e == RechercheReseau.Entree.ARRET) break;
                if (e == RechercheReseau.Entree.OK) {
                    visites_++;
                    echecsDeSuite = 0;
                    List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
                    for (HFloorItem f : Salle.sols()) sols.add(f.getTypeId());
                    for (HWallItem w : Salle.murs()) murs.add(w.getTypeId());
                    Map<String, Integer> n = RechercheCalcul.compter(sols, murs, cibles);
                    for (RechercheCalcul.Resultat r : res.values()) {
                        int q = n.getOrDefault(r.cible.cle(), 0);
                        if (q > 0) { r.ajouter(a, q); montrer(r); }
                    }
                    Journal.debug("recherche : " + a.nom() + " (" + a.id() + ") : " + n);
                } else if (e == RechercheReseau.Entree.REFUS) {
                    refus++;
                    Journal.debug("recherche : entrée refusée dans " + a.nom() + " (" + a.id() + ").");
                } else {
                    sansReponse++;
                    echecsDeSuite++;
                    Journal.debug("recherche : " + a.nom() + " (" + a.id() + ") pas chargé à temps.");
                    // aucune facon d'entrer n'a marche deux fois de suite : inutile d'insister
                    if (visites_ == 0 && echecsDeSuite >= 2) { echecEntree = true; break; }
                }
                if (j < visites.size() - 1 && !arret.get())
                    attendre(RechercheCalcul.pause(hasard, PAUSE_MIN_MS, PAUSE_MAX_MS));
            }

            arrete = arret.get();
            // 3. retour a l'appart de depart
            if (depart > 0 && Salle.salleId() != depart && !echecEntree) {
                dire("Retour dans ton appart…", 1);
                arret.set(false);                 // le retour se fait meme apres Arreter
                attendre(3000);
                RechercheReseau.Entree e = RechercheReseau.entrer(gp, depart, CHARGEMENT_MS, () -> false);
                if (e != RechercheReseau.Entree.OK) Journal.debug("recherche : retour à " + depart + " : " + e);
            }
        } catch (Throwable t) {
            Journal.debug("recherche : " + t);
            Ui.erreur(etat, "Recherche interrompue : " + t.getClass().getSimpleName() + ".");
            Platform.runLater(this::finir);
            return;
        } finally {
            RechercheReseau.actif = false;
        }

        int trouves = 0;
        Set<Integer> avec = new HashSet<>();
        for (RechercheCalcul.Resultat r : res.values()) {
            if (r.totalPose() > 0) trouves++;
            for (RechercheCalcul.Trouve t : r.apparts) avec.add(t.appart().id());
        }
        String bilan = visites_ + (visites_ > 1 ? " apparts visités" : " appart visité")
                + (refus > 0 ? ", " + refus + " refusé" + (refus > 1 ? "s" : "") : "")
                + (sansReponse > 0 ? ", " + sansReponse + " sans réponse" : "");
        String fin;
        if (echecEntree) fin = null;
        else fin = (trouves == 0 ? "aucun des mobis cherchés n'est posé." : trouves + (trouves > 1 ? " mobis trouvés" : " mobi trouvé")
                + " dans " + avec.size() + (avec.size() > 1 ? " apparts." : " appart."));
        String bilanFinal = bilan;
        boolean arreteFinal = arrete, echecFinal = echecEntree;
        Platform.runLater(() -> {
            finir();
            resume.setText(Ui.majuscule(bilanFinal) + ".");
            if (echecFinal) Ui.erreur(etat, "Impossible d'entrer dans les apparts Troc : le jeu ne suit pas. Réessaie plus tard.");
            else Ui.succes(etat, (arreteFinal ? "Recherche arrêtée : " : "Recherche finie : ") + bilanFinal + ", " + fin);
        });
    }

    /** Pause interrompue par Arreter. */
    private void attendre(long ms) {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin && !arret.get()) Salle.sommeil(Math.min(150, fin - System.currentTimeMillis()));
    }

    private void dire(String texte, double part) {
        Platform.runLater(() -> {
            if (arret.get() && !texte.startsWith("Retour")) return;
            progression.setText(texte);
            progression.setTooltip(Ui.bulle(texte));
            barre.setProgress(Math.max(0, Math.min(1, part)));
        });
    }

    // ---------------------------------------------------------------- resultats (fil FX)

    /** L'instantane d'un resultat, pour le fil FX. */
    private void montrer(RechercheCalcul.Resultat r) {
        List<RechercheCalcul.Trouve> l = new ArrayList<>(r.apparts);
        RechercheCalcul.Marche m = r.marche;
        RechercheCalcul.Catalogue c = r.catalogue;
        Platform.runLater(() -> {
            Carte k = parCle.get(r.cible.cle());
            if (k != null) k.maj(l, c, m);
        });
    }

    private void preparerCartes(List<RechercheCalcul.Cible> cibles) {
        parCle.clear();
        List<Node> l = new ArrayList<>();
        for (RechercheCalcul.Cible c : cibles) {
            if (!l.isEmpty()) l.add(new Separator());
            Carte k = new Carte(c);
            parCle.put(c.cle(), k);
            l.add(k.racine);
        }
        cartes.getChildren().setAll(l);
        resume.setText("Recherche en cours…");
    }

    /** Une ligne du tableau d'un mobi : un appart et la quantite posee. */
    public static final class LigneAppart {
        final RechercheCalcul.Appart appart;
        private final SimpleStringProperty nom, proprio;
        private final SimpleIntegerProperty quantite, presents;
        LigneAppart(RechercheCalcul.Trouve t) {
            appart = t.appart();
            nom = new SimpleStringProperty(t.appart().nom());
            proprio = new SimpleStringProperty(t.appart().proprio());
            quantite = new SimpleIntegerProperty(t.quantite());
            presents = new SimpleIntegerProperty(t.appart().presents());
        }
    }

    /** Le resultat d'un mobi : en-tete, catalogue, marche, verdict, tableau des apparts. */
    private final class Carte {
        final RechercheCalcul.Cible cible;
        final VBox racine;
        final Label trouve = new Label("Apparts Troc : recherche en cours…");
        final Label catalogue = new Label("Catalogue : …"), marche = new Label("Marché : …");
        final Label verdict = new Label();
        final ObservableList<LigneAppart> lignes = FXCollections.observableArrayList();
        final TableView<LigneAppart> table = new TableView<>(lignes);
        final Button aller = Ui.bouton(Icones.PORTE, "Y aller");

        Carte(RechercheCalcul.Cible c) {
            cible = c;
            Label nom = new Label(c.nom());
            nom.setStyle("-fx-font-weight: bold;");
            nom.setMinWidth(0);
            nom.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nom, Priority.ALWAYS);
            Label voulu = new Label("Voulu : " + PrixTexte.nombre(c.voulu()));
            voulu.setMinWidth(Region.USE_PREF_SIZE);
            ImageView iv = new ImageView(PrixVignettes.icone(c.classe(), c.revision()));
            iv.setFitWidth(PrixVignettes.TAILLE);
            iv.setFitHeight(PrixVignettes.TAILLE);
            iv.setPreserveRatio(true);
            HBox tete = new HBox(8, iv, nom, voulu);
            tete.setAlignment(Pos.CENTER_LEFT);
            for (Label l : List.of(trouve, catalogue, marche, verdict)) { l.setWrapText(true); l.setMinHeight(Region.USE_PREF_SIZE); }

            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.setFixedCellSize(26);
            table.setPlaceholder(new Label("Pas encore trouvé dans un appart Troc."));
            TableColumn<LigneAppart, String> cNom = new TableColumn<>("Appart");
            cNom.setCellValueFactory(x -> x.getValue().nom);
            cNom.setComparator(COLLATOR::compare);
            cNom.setPrefWidth(150);
            TableColumn<LigneAppart, String> cProprio = new TableColumn<>("Propriétaire");
            cProprio.setCellValueFactory(x -> x.getValue().proprio);
            cProprio.setComparator(COLLATOR::compare);
            cProprio.setPrefWidth(100);
            TableColumn<LigneAppart, Number> cQte = nombre("Qté", x -> x.quantite, 46);
            TableColumn<LigneAppart, Number> cMonde = nombre("Monde", x -> x.presents, 54);
            cMonde.visibleProperty().bind(table.widthProperty().greaterThanOrEqualTo(380));
            table.getColumns().addAll(List.of(cNom, cProprio, cQte, cMonde));
            cQte.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().add(cQte);
            table.setRowFactory(tv -> {
                TableRow<LigneAppart> row = new TableRow<>();
                row.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !row.isEmpty()) allerA(row.getItem().appart);
                });
                return row;
            });
            table.setVisible(false);
            table.managedProperty().bind(table.visibleProperty());
            aller.setDisable(true);
            Ui.bulle(aller, "Entre dans l'appart choisi dans le tableau.");
            aller.setOnAction(e -> {
                LigneAppart l = table.getSelectionModel().getSelectedItem();
                if (l != null) allerA(l.appart);
            });
            table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> aller.setDisable(b == null || enCours));
            aller.visibleProperty().bind(table.visibleProperty());
            aller.managedProperty().bind(aller.visibleProperty());

            racine = new VBox(4, tete, trouve, catalogue, marche, verdict, table, aller);
            racine.setFillWidth(true);
            maj(List.of(), RechercheCalcul.Catalogue.INCONNU, RechercheCalcul.Marche.INCONNU);
            catalogue.setText("Catalogue : …");
            marche.setText("Marché : …");
            verdict.setText("");
        }

        void maj(List<RechercheCalcul.Trouve> l, RechercheCalcul.Catalogue c, RechercheCalcul.Marche m) {
            List<LigneAppart> n = new ArrayList<>();
            int total = 0;
            for (RechercheCalcul.Trouve t : l) { n.add(new LigneAppart(t)); total += t.quantite(); }
            lignes.setAll(n);
            table.sort();
            table.setVisible(!n.isEmpty());
            table.setPrefHeight(26 + 26 * Math.min(6, Math.max(1, n.size())) + 2);
            trouve.setText(n.isEmpty() ? "Apparts Troc : pas encore trouvé."
                    : "Apparts Troc : " + PrixTexte.nombre(total) + " posé" + (total > 1 ? "s" : "") + " dans "
                      + n.size() + (n.size() > 1 ? " apparts." : " appart."));
            catalogue.setText(texteCatalogue(c));
            marche.setText(texteMarche(m));
            RechercheCalcul.Verdict v = RechercheCalcul.verdict(cible.voulu(), c, m, total);
            verdict.setText("Quantité voulue atteignable ? " + v.texte + ".");
            verdict.getStyleClass().removeAll("etat-ok", "etat-attente", "etat-absent");
            verdict.getStyleClass().add(switch (v) {
                case CATALOGUE, MARCHE -> "etat-ok";
                case AVEC_TROC, TROC -> "etat-attente";
                default -> "etat-absent";
            });
        }
    }

    private interface Prop { javafx.beans.value.ObservableValue<Number> de(LigneAppart l); }

    private static TableColumn<LigneAppart, Number> nombre(String titre, Prop p, double largeur) {
        TableColumn<LigneAppart, Number> c = new TableColumn<>(titre);
        c.setCellValueFactory(x -> p.de(x.getValue()));
        c.setCellFactory(x -> {
            TableCell<LigneAppart, Number> cell = new TableCell<>() {
                @Override protected void updateItem(Number n, boolean vide) {
                    super.updateItem(n, vide);
                    setText(vide || n == null ? null : PrixTexte.nombre(n.longValue()));
                }
            };
            cell.setAlignment(Pos.CENTER_RIGHT);
            return cell;
        });
        c.setPrefWidth(largeur);
        c.setMinWidth(largeur - 6);
        return c;
    }

    static String texteCatalogue(RechercheCalcul.Catalogue c) {
        if (c == null) return "Catalogue : …";
        if (c.vendu()) {
            String p = c.credits() > 0 ? PrixTexte.nombre(c.credits()) + " crédit" + (c.credits() > 1 ? "s" : "") : "";
            if (c.points() > 0) p += (p.isEmpty() ? "" : " + ") + PrixTexte.nombre(c.points()) + " " + points(c.typePoints(), c.points());
            return "Catalogue : en vente" + (p.isEmpty() ? "." : ", " + p + ".");
        }
        return c.repondu() ? "Catalogue : pas en vente." : "Catalogue : pas en vente en ce moment.";
    }

    private static String points(int type, int n) {
        String s = switch (type) {
            case 0 -> "duckets";
            case 5 -> "diamants";
            default -> "points";
        };
        return n > 1 || type == 0 ? s : s.substring(0, s.length() - 1);
    }

    static String texteMarche(RechercheCalcul.Marche m) {
        if (m == null || !m.repondu()) return "Marché : pas de réponse du jeu.";
        StringBuilder b = new StringBuilder("Marché : ");
        if (m.offres() <= 0) b.append("aucune offre");
        else {
            b.append(PrixTexte.nombre(m.offres())).append(m.offres() > 1 ? " offres" : " offre");
            if (m.prixMin() > 0) b.append(" · dès ").append(PrixTexte.nombre(m.prixMin())).append(" crédits");
        }
        if (m.prixMoyen() > 0) b.append(" · moyenne ").append(PrixTexte.nombre(m.prixMoyen())).append(" crédits");
        return b.append(".").toString();
    }

    // ---------------------------------------------------------------- aller dans un appart

    private void allerA(RechercheCalcul.Appart a) {
        if (enCours) return;
        Moteur gp = Salle.gp();
        if (gp == null) return;
        Salle.tache("recherche-aller", () -> {
            RechercheReseau.installer(gp);
            RechercheReseau.actif = true;
            try {
                RechercheReseau.Entree e = RechercheReseau.entrer(gp, a.id(), CHARGEMENT_MS, () -> false);
                if (e == RechercheReseau.Entree.REFUS) Ui.erreur(etat, "Entrée refusée dans « " + a.nom() + " ».");
                else if (e == RechercheReseau.Entree.DELAI) Ui.erreur(etat, "« " + a.nom() + " » ne s'est pas chargé.");
            } finally {
                RechercheReseau.actif = false;
            }
        });
    }
}

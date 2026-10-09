package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mobis > Estimation mobi : la fiche d'un mobi, comme sur habbofurni.xyz.
 *
 * Tu tapes un nom (francais ou technique), tu choisis dans les suggestions
 * (avec vignette, comme dans la Recherche de mobis) et la fiche montre :
 *   - le prix moyen de habbofurni.xyz (moyenne du marche ou estimation du
 *     site), tire de l'export horaire deja lu par PrixSite : AUCUNE demande
 *     de plus au site (accord avec habbofurni) ;
 *   - le prix de la place du marche du jeu (Marche), connu ou demande au
 *     jeu d'un clic ;
 *   - le catalogue Builders Club (CatalogueBc) ;
 *   - le nombre d'exemplaires dans ton inventaire.
 * La source est citee avec un lien, comme le demande habbofurni.xyz.
 *
 * La fiche se remet a jour quand les prix ou l'inventaire changent
 * (ecouteurs de PrixSite et PrixChargement).
 */
final class EstimationMobi {

    private static final String SITE = "https://habbofurni.xyz";

    private TextField champ;
    private final ContextMenu suggestions = new ContextMenu();
    /** Vrai pendant qu'on ecrit nous-memes dans le champ : pas de suggestions. */
    private boolean ecritureInterne = false;
    private Furnidata.Mobi choisi;

    private Label aucun;
    private VBox details;
    private final HBox tete = new HBox(10);
    private final Label nom = Ui.valeur(""), classe = new Label(), genre = new Label();
    private final Label prixSite = Ui.valeur(""), sourceSite = new Label(), dateSite = new Label();
    private final Label prixMarche = Ui.valeur(""), detailMarche = new Label();
    private final Label bc = new Label(), inventaire = new Label();
    private Hyperlink voir;
    private String adresseVoir = SITE;
    private Button demander;
    private Label etat;
    private volatile boolean demandeEnCours = false;
    private final AtomicBoolean planifie = new AtomicBoolean(false);

    Tab construire() {
        champ = new TextField();
        champ.setPromptText("Nom du mobi (français ou technique)");
        champ.setMaxWidth(Double.MAX_VALUE);
        champ.textProperty().addListener((o, a, b) -> { if (!ecritureInterne) proposer(b); });
        champ.setOnAction(e -> {
            List<Furnidata.Mobi> l = RechercheMobis.chercher(champ.getText(), 1);
            if (!l.isEmpty()) choisir(l.get(0));
        });
        champ.focusedProperty().addListener((o, a, b) -> { if (!b) suggestions.hide(); });
        aucun = Ui.discret("Aucun mobi choisi : tape un nom ci-dessus.");

        // ---- en-tete de la fiche : vignette, nom, classe
        classe.getStyleClass().add("etat-ligne");
        genre.getStyleClass().add("etat-ligne");
        for (Label l : List.of(nom, classe, genre, prixSite, sourceSite, dateSite, prixMarche, detailMarche, bc, inventaire)) {
            l.setWrapText(true);
            l.setMinWidth(0);
            l.setMinHeight(Region.USE_PREF_SIZE);
        }
        sourceSite.getStyleClass().add("etat-ligne");
        dateSite.getStyleClass().add("etat-ligne");
        detailMarche.getStyleClass().add("etat-ligne");
        tete.setAlignment(Pos.CENTER_LEFT);

        // ---- habbofurni : lien de la fiche du site, et source citee (obligation du site)
        voir = new Hyperlink("Voir sur habbofurni.xyz");
        voir.setCursor(Cursor.HAND);
        voir.setOnAction(e -> { Lien.ouvrir(adresseVoir); voir.setVisited(false); });
        voir.setTooltip(Ui.bulle(adresseVoir));
        Hyperlink source = Lien.lien("Prix moyens de habbofurni.xyz", SITE);
        source.setCursor(Cursor.HAND);

        // ---- marche du jeu
        demander = Ui.bouton(Icones.MARCHE, "Demander au marché Habbo");
        demander.setCursor(Cursor.HAND);
        demander.setMinWidth(Region.USE_PREF_SIZE);
        Ui.bulle(demander, "Demande au jeu le prix moyen de ce mobi à la place du marché.");
        demander.setOnAction(e -> demanderMarche());
        etat = Ui.etat();

        VBox mobi = new VBox(Ui.DANS_BLOC, tete);
        details = new VBox(12,
                Ui.bloc("Prix habbofurni", prixSite, sourceSite, dateSite, voir, source,
                        Ui.aide("Prix de habbofurni.xyz pour l'hôtel FR : la moyenne des ventes de la "
                                + "place du marché, ou une estimation du site pour les rares peu vendus. "
                                + "Ils viennent du fichier que le site met à jour chaque heure, déjà lu par "
                                + "l'Atelier. C'est une estimation : le prix réel dépend des acheteurs.")),
                Ui.bloc("Marché Habbo", prixMarche, detailMarche, Ui.boutons(demander), etat,
                        Ui.aide("Le prix moyen affiché par la place du marché du jeu, ses offres en "
                                + "cours et les ventes des derniers jours. Il est gardé 24 heures.")),
                Ui.bloc("Catalogue et inventaire", bc, inventaire));
        details.setFillWidth(true);

        VBox v = new VBox(12,
                Ui.bloc("Mobi", champ, aucun, mobi,
                        Ui.aide("Tape un bout de nom, français ou technique, puis choisis le mobi dans "
                                + "la liste (Entrée prend le premier).")),
                details);
        v.setFillWidth(true);
        v.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));
        mobi.visibleProperty().bind(aucun.visibleProperty().not());
        mobi.managedProperty().bind(mobi.visibleProperty());
        details.visibleProperty().bind(mobi.visibleProperty());
        details.managedProperty().bind(details.visibleProperty());
        aucun.managedProperty().bind(aucun.visibleProperty());

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // Prix du site, du jeu, ou inventaire qui changent : fiche a refaire.
        PrixSite.surMaj(this::rafraichir);
        PrixChargement.ecouter(this::rafraichir);
        majFiche();

        Tab t = new Tab("Estimation mobi", sp);
        t.setClosable(false);
        return t;
    }

    // ---------------------------------------------------------------- choix du mobi

    private void proposer(String texte) {
        List<Furnidata.Mobi> l = RechercheMobis.chercher(texte, 10);
        if (l.isEmpty() || !champ.isFocused()) { suggestions.hide(); return; }
        List<MenuItem> items = new ArrayList<>();
        for (Furnidata.Mobi m : l) {
            Label n = new Label(RechercheMobis.nom(m));
            Label c = new Label(m.className + (m.mural ? " · mural" : ""));
            c.getStyleClass().add("etat-ligne");
            HBox h = new HBox(8, RechercheMobis.vignette(m), n, c);
            h.setAlignment(Pos.CENTER_LEFT);
            CustomMenuItem it = new CustomMenuItem(h, true);
            it.setOnAction(e -> choisir(m));
            items.add(it);
        }
        suggestions.getItems().setAll(items);
        if (!suggestions.isShowing()) suggestions.show(champ, Side.BOTTOM, 0, 0);
    }

    private void choisir(Furnidata.Mobi m) {
        suggestions.hide();
        choisi = m;
        ecritureInterne = true;
        try { champ.setText(RechercheMobis.nom(m)); champ.positionCaret(champ.getText().length()); }
        finally { ecritureInterne = false; }
        etat.setText("");
        String n = RechercheMobis.nom(m);
        adresseVoir = SITE + "/?s=" + URLEncoder.encode(n, StandardCharsets.UTF_8);
        voir.setTooltip(Ui.bulle(adresseVoir));
        Node vignette = RechercheMobis.vignette(m);
        VBox noms = new VBox(2, nom, classe, genre);
        noms.setMinWidth(0);
        HBox.setHgrow(noms, Priority.ALWAYS);
        tete.getChildren().setAll(vignette, noms);
        majFiche();
    }

    // ---------------------------------------------------------------- marche du jeu

    private void demanderMarche() {
        Furnidata.Mobi m = choisi;
        Moteur gp = Salle.gp();
        if (m == null || demandeEnCours) return;
        if (gp == null) { Ui.erreur(etat, "Le jeu n'est pas encore prêt : connecte-toi d'abord."); return; }
        demandeEnCours = true;
        majFiche();
        Salle.tache("estimation-marche", () -> {
            Marche.Prix p = null;
            try {
                p = Marche.demander(gp, m.mural, m.id);
                if (p != null) Marche.sauver();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Journal.debug("estimation : marché : " + t);
            }
            boolean repondu = p != null;
            Platform.runLater(() -> {
                demandeEnCours = false;
                majFiche();
                if (!repondu && choisi == m)
                    Ui.erreur(etat, Marche.muet() ? "Le marché Habbo ne répond pas pendant cette session."
                                                  : "Le marché Habbo n'a pas répondu. Réessaie dans un moment.");
            });
        });
    }

    // ---------------------------------------------------------------- la fiche (fil FX)

    /** Redemande l'affichage, regroupe ; n'importe quel fil. */
    private void rafraichir() {
        if (!planifie.compareAndSet(false, true)) return;
        Platform.runLater(() -> { planifie.set(false); majFiche(); });
    }

    private void majFiche() {
        Furnidata.Mobi m = choisi;
        aucun.setVisible(m == null);
        if (m == null) {
            aucun.setText(Salle.furnidataPrete() ? "Aucun mobi choisi : tape un nom ci-dessus."
                                                 : "Les noms des mobis arrivent avec le jeu : connecte-toi d'abord.");
            return;
        }
        String n = RechercheMobis.nom(m);
        nom.setText(n);
        classe.setText(m.className);
        List<String> g = new ArrayList<>();
        g.add(m.mural ? "Mural" : "Sol");
        if (m.isRare) g.add("rare");
        if (m.isBC) g.add("Builders Club");
        genre.setText(String.join(" · ", g));
        Ui.bulle(classe, m.className);

        majSite(m);
        majMarche(m);
        majCatalogue(m);
        majInventaire(m);
    }

    private void majSite(Furnidata.Mobi m) {
        PrixSite.Prix p = PrixSite.prix(m.className);
        if (p != null) {
            prixSite.setText(PrixTexte.credits(p.moyen));
            sourceSite.setText("market".equals(p.source) ? "Moyenne du marché"
                    : "site".equals(p.source) ? "Estimation habbofurni"
                    : "Source du site : " + p.source);
        } else if (!PrixSite.fini() || PrixSite.enLecture()) {
            prixSite.setText("—");
            sourceSite.setText("Prix habbofurni en cours de chargement…");
        } else if (PrixSite.nombre() == 0) {
            prixSite.setText("—");
            String e = PrixSite.etat();
            sourceSite.setText(e != null && !e.isBlank() ? e : "Prix habbofurni indisponibles pour l'instant.");
        } else {
            prixSite.setText("—");
            sourceSite.setText("Pas de prix sur habbofurni pour ce mobi.");
        }
        long d = PrixSite.misAJour();
        dateSite.setText(d > 0 ? "Export habbofurni lu le " + PrixTexte.dateHeure(d) : "");
        dateSite.setVisible(d > 0);
        dateSite.setManaged(d > 0);
    }

    private void majMarche(Furnidata.Mobi m) {
        Marche.Prix p = Marche.prix(m.mural, m.id);
        if (p == null) {
            prixMarche.setText("—");
            detailMarche.setText("Prix du marché Habbo pas encore connu pour ce mobi.");
        } else {
            prixMarche.setText(p.moyen > 0 ? "Moyenne " + PrixTexte.credits(p.moyen) : "Aucune vente récente");
            List<String> d = new ArrayList<>();
            d.add(PrixTexte.nombre(p.offres) + " offre(s) en cours");
            d.add(PrixTexte.nombre(p.vendus) + " vendu(s) ces derniers jours");
            if (p.le > 0) d.add("demandé le " + PrixTexte.dateHeure(p.le));
            detailMarche.setText(Ui.accorder(String.join(" · ", d)));
        }
        demander.setText(demandeEnCours ? "Demande en cours…"
                : p == null ? "Demander au marché Habbo" : "Redemander au marché Habbo");
        demander.setDisable(demandeEnCours || Salle.gp() == null || Marche.muet());
    }

    private void majCatalogue(Furnidata.Mobi m) {
        CatalogueBc cat = null;
        try { Moteur gp = Salle.gp(); cat = gp == null ? null : gp.getCatalog(); } catch (Throwable ignored) { }
        CatalogueBc.Produit p = cat == null ? null : m.mural ? cat.unProduitMur(m.id) : cat.produitSol(m.id);
        String t;
        if (p != null) t = "Builders Club : au catalogue BC (page " + p.pageId() + "), posable avec le BC.";
        else if (cat != null && cat.pret()) t = "Builders Club : pas au catalogue BC.";
        else if (m.bcOfferId > 0) t = "Builders Club : au catalogue BC, d'après la liste des mobis.";
        else t = "Builders Club : catalogue BC pas encore lu.";
        bc.setText(t);
    }

    private void majInventaire(Furnidata.Mobi m) {
        List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
        if (inv == null) { inventaire.setText("Inventaire pas encore reçu."); return; }
        int total = 0, vendables = 0;
        try {
            for (HInventoryItem it : new ArrayList<>(inv)) {
                boolean mur = it.getType() == HProductType.WallItem;
                if (!mur && it.getType() != HProductType.FloorItem) continue;
                if (mur != m.mural || it.getTypeId() != m.id) continue;
                total++;
                if (it.isSellable() && it.getSecondsToExpiration() <= 0) vendables++;
            }
        } catch (Throwable t) {
            Journal.debug("estimation : inventaire illisible : " + t);
        }
        if (total == 0) { inventaire.setText("Dans mon inventaire : aucun."); return; }
        StringBuilder b = new StringBuilder("Dans mon inventaire : " + PrixTexte.nombre(total));
        if (vendables < total) b.append(" (").append(PrixTexte.nombre(vendables)).append(" vendable(s))");
        int prix = vendables > 0 ? OngletValeur.prixUnitaire(Salle.gp(), m.mural, m.id) : -1;
        if (prix > 0) b.append(" · valeur ").append(PrixTexte.credits((long) prix * vendables));
        inventaire.setText(Ui.accorder(b.append(".").toString()));
    }
}

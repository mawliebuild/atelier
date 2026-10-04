package atelier;

import gearth.extensions.parsers.HFloorItem;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Analyse des wired de la salle, en trois volets : Graphe, Vérificateur,
 * Rechercher.
 *
 * Les reglages de chaque wired sont lus par WiredLecteur (Open + reponse
 * bloquee, comme l'export de l'Atelier) puis gardes en cache par id.
 * Tout est automatique : la lecture demarre seule en entrant dans une salle
 * et se complete seule quand un wired change ; les trois volets se
 * recalculent d'eux-memes. Chaque volet a sa petite ligne d'etat, car la
 * Navigation de l'Atelier peut les ranger dans des menus differents.
 */
public class OngletAnalyseWired {

    private WiredAnalyse analyse;

    // Graphe
    private WiredGraphe graphe;
    private VBox detail;
    private Label etatGraphe;
    private Stage grande;
    private WiredGraphe grapheGrand;
    private VBox detailGrand;

    // Verificateur
    private ListView<WiredAnalyse.Probleme> problemes;
    private Label resumeVerif, etatVerif;
    /** Copie des problemes, lue hors fil JavaFX par MiseEnValeur (jamais la ListView). */
    private volatile List<WiredAnalyse.Probleme> derniersProblemes = List.of();

    // Rechercher
    private ListView<WiredAnalyse.Resultat> resultats;
    private Label etatRecherche, attenteClic;
    /** Derniere recherche, refaite d'elle-meme quand les donnees changent. */
    private String dernierMode = null, dernierTexte = null;
    private int dernierMobi = 0;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab g = new Tab("Graphe", defiler(voletGraphe()));
        Tab v = new Tab("Vérificateur", defiler(voletVerificateur()));
        Tab r = new Tab("Rechercher", defiler(voletRechercher()));
        g.setClosable(false); v.setClosable(false); r.setClosable(false);
        volets.getTabs().addAll(g, v, r);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        WiredLecteur.installer();      // lance aussi le suivi automatique
        WiredLecteur.ecouterFin(this::rafraichir);
        Salle.surClicMobi(this::clicMobi);
        // Analyse (sans reseau) des qu'un volet s'affiche : piles et ordre visibles
        // meme avant toute lecture.
        graphe.sceneProperty().addListener((o, av, ap) -> { if (ap != null) rafraichir(); });
        problemes.sceneProperty().addListener((o, av, ap) -> { if (ap != null) rafraichir(); });

        Tab t = new Tab("Analyse wired", racine);
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

    private static VBox volet(Node... n) {
        VBox v = new VBox(12, n);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        return v;
    }

    /**
     * Petite ligne d'etat de la lecture, une par volet, tenue a jour toute
     * seule : « 42 wired lus », « Lecture… 12 / 42 », « Pas de droits wired ici. »
     */
    private Node bandeau() {
        // progression permanente : ligne discrete, sans passer par le Journal
        Label prog = new Label("Wired : " + WiredLecteur.message());
        prog.setWrapText(true);
        prog.setMaxWidth(Double.MAX_VALUE);
        prog.getStyleClass().add("etat-ligne");
        WiredLecteur.ecouterProgres(() -> dire(prog, "Wired : " + WiredLecteur.message()));
        return prog;
    }

    // -------------------------------------------------------------- Graphe

    private Pane voletGraphe() {
        graphe = new WiredGraphe(350, 420);
        graphe.setPrefHeight(420);
        graphe.setMinHeight(420);
        graphe.surChoix(this::choisirPile);
        detail = new VBox(4, Ui.discret("Clique une pile du graphe pour voir ses wired."));
        etatGraphe = Ui.etat();

        Button agrandir = new Button("Agrandir");
        agrandir.setOnAction(e -> agrandir());
        Button recentrer = new Button("Recentrer");
        recentrer.setOnAction(e -> graphe.ajuster());

        return volet(bandeau(),
                Ui.bloc("Graphe des piles", graphe, Ui.ligne(agrandir, recentrer), legende(),
                        Ui.aide("Molette : zoom · glisser : déplacer · une flèche va d'une pile "
                                + "qui agit sur un mobi vers la pile que ce mobi déclenche.")),
                Ui.bloc("Pile choisie", detail),
                etatGraphe);
    }

    private Node legende() {
        List<Node> n = new ArrayList<>();
        for (Wired.Rang r : Wired.Rang.values()) {
            if (r == Wired.Rang.AUTRE) continue;
            Rectangle c = new Rectangle(9, 9, WiredGraphe.couleur(r));
            Label l = new Label(r.libelle, c);
            l.setStyle("-fx-font-size: 10px;");
            n.add(l);
        }
        javafx.scene.layout.FlowPane f = new javafx.scene.layout.FlowPane(8, 3);
        f.getChildren().addAll(n);
        return f;
    }

    private void choisirPile(WiredAnalyse.Pile p) {
        if (graphe != null && graphe.choisie() != p) graphe.choisir(p);
        if (grapheGrand != null && grapheGrand.choisie() != p) grapheGrand.choisir(p);
        remplirDetail(detail, p);
        if (detailGrand != null) remplirDetail(detailGrand, p);
    }

    private void remplirDetail(VBox boite, WiredAnalyse.Pile p) {
        boite.getChildren().clear();
        if (p == null) {
            boite.getChildren().add(Ui.discret("Clique une pile du graphe pour voir ses wired."));
            return;
        }
        boite.getChildren().add(Ui.valeur("Case " + p.caseTexte() + " · " + p.wired.size() + " wired"));
        // Du bas vers le haut de la pile : l'ordre de fonctionnement.
        for (WiredAnalyse.Fil f : p.wired) {
            Rectangle c = new Rectangle(9, 9, WiredGraphe.couleur(f.rang));
            Label l = new Label(WiredAnalyse.detail(f), c);
            l.setWrapText(true);
            l.setStyle("-fx-font-size: 11px;");
            l.setAlignment(Pos.TOP_LEFT);
            boite.getChildren().add(l);
        }
        if (!p.sortants.isEmpty()) {
            StringBuilder b = new StringBuilder("Mène vers : ");
            for (WiredAnalyse.Lien li : p.sortants)
                b.append(li.vers.caseTexte()).append(li.signal ? " (signal)" : "").append("  ");
            boite.getChildren().add(petit(b.toString()));
        }
        if (!p.entrants.isEmpty()) {
            StringBuilder b = new StringBuilder("Déclenchée par : ");
            for (WiredAnalyse.Lien li : p.entrants)
                b.append(li.de.caseTexte()).append(li.signal ? " (signal)" : "").append("  ");
            boite.getChildren().add(petit(b.toString()));
        }
        if (p.signalSansLien) boite.getChildren().add(petit("Envoie un signal : destinataire non retrouvé."));
        if (p.receptionSansLien) boite.getChildren().add(petit("Reçoit un signal : émetteur non retrouvé."));
        Button zone = new Button("Prendre cette case comme zone");
        zone.setOnAction(e -> {
            Zone.definir(p.x, p.y, p.x, p.y);
            Journal.succes("Zone définie sur la case " + p.caseTexte() + ".");
        });
        boite.getChildren().add(zone);
    }

    private static Label petit(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }

    private void agrandir() {
        if (grande != null) { grande.show(); grande.toFront(); return; }
        grapheGrand = new WiredGraphe(820, 680);
        grapheGrand.surChoix(this::choisirPile);
        detailGrand = new VBox(4);
        detailGrand.setPadding(new Insets(10));
        ScrollPane sp = new ScrollPane(detailGrand);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setPrefWidth(320);
        BorderPane bp = new BorderPane(grapheGrand);
        bp.setRight(sp);
        bp.setStyle("-fx-background-color: #f4efe3;");
        Scene sc = new Scene(bp, 1160, 720);
        try {
            if (graphe.getScene() != null) sc.getStylesheets().addAll(graphe.getScene().getStylesheets());
        } catch (Throwable ignored) { }
        grande = new Stage();
        grande.setTitle("Atelier — graphe des wired");
        grande.setScene(sc);
        try {
            if (graphe.getScene() != null && graphe.getScene().getWindow() != null)
                grande.initOwner(graphe.getScene().getWindow());
        } catch (Throwable ignored) { }
        grande.setOnHidden(e -> { grande = null; grapheGrand = null; detailGrand = null; });
        grapheGrand.montrer(analyse());
        remplirDetail(detailGrand, graphe.choisie());
        if (graphe.choisie() != null) grapheGrand.choisir(graphe.choisie());
        grande.show();
    }

    // --------------------------------------------------------- Verificateur

    private Pane voletVerificateur() {
        resumeVerif = Ui.valeur("Pas encore analysé.");
        resumeVerif.setWrapText(true);
        etatVerif = Ui.etat();
        problemes = new ListView<>();
        // fenetre Wired ouverte : les wired des piles a probleme s'allument dans l'appart
        MiseEnValeur.fournir("wired", () -> {
            java.util.Set<Long> cases = new java.util.HashSet<>();
            for (WiredAnalyse.Probleme q : derniersProblemes)
                cases.add(((long) q.x << 32) | (q.y & 0xffffffffL));
            if (cases.isEmpty()) return java.util.List.of();
            return MiseEnValeur.solsOu(it -> cases.contains(((long) it.getTile().getX() << 32) | (it.getTile().getY() & 0xffffffffL))
                    && Wired.estWired(Salle.classe(it.getTypeId(), false)));
        });
        problemes.setPrefHeight(360);
        problemes.setMinHeight(200);
        VBox.setVgrow(problemes, Priority.ALWAYS);
        problemes.setPlaceholder(Ui.discret("Aucun problème trouvé (ou pas encore analysé)."));
        problemes.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(WiredAnalyse.Probleme q, boolean vide) {
                super.updateItem(q, vide);
                if (vide || q == null) { setText(null); setGraphic(null); return; }
                Label l = new Label(q.gravite.libelle.toUpperCase(Locale.ROOT) + " · ("
                        + q.x + "," + q.y + ")  " + q.texte);
                l.setWrapText(true);
                l.maxWidthProperty().bind(lv.widthProperty().subtract(30));
                String coul = q.gravite == WiredAnalyse.Gravite.ERREUR ? "#b02020"
                        : q.gravite == WiredAnalyse.Gravite.ATTENTION ? "#a86400" : "#555555";
                l.setStyle("-fx-text-fill: " + coul + "; -fx-font-size: 11px;");
                setText(null);
                setGraphic(l);
            }
        });
        problemes.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            WiredAnalyse.Probleme q = problemes.getSelectionModel().getSelectedItem();
            if (q == null) return;
            Zone.definir(q.x, q.y, q.x, q.y);
            Journal.succes("Zone définie sur la case (" + q.x + "," + q.y + ").");
        });

        return volet(bandeau(),
                Ui.bloc("Problèmes", resumeVerif, problemes,
                        Ui.aide("Double-clic sur un problème : sa case devient la zone de travail.")),
                etatVerif);
    }

    // ------------------------------------------------------------ Rechercher

    private Pane voletRechercher() {
        etatRecherche = Ui.etat();
        attenteClic = Ui.discret("Clique un mobi dans le jeu : ses wired s'affichent ici.");

        ChoiceBox<String> mode = new ChoiceBox<>(FXCollections.observableArrayList(
                "Texte", "Variable", "Id de mobi"));
        mode.setValue("Texte");
        TextField champ = new TextField();
        champ.setPromptText("Nom, texte, id...");
        HBox.setHgrow(champ, Priority.ALWAYS);
        // Recherche en direct, pendant la frappe : pas de bouton.
        Runnable go = () -> chercher(mode.getValue(), champ.getText());
        champ.textProperty().addListener((o, av, ap) -> go.run());
        mode.valueProperty().addListener((o, av, ap) -> go.run());
        champ.setOnAction(e -> go.run());
        HBox ligne = new HBox(6, mode, champ);
        ligne.setAlignment(Pos.CENTER_LEFT);

        resultats = new ListView<>();
        resultats.setPrefHeight(300);
        resultats.setMinHeight(160);
        resultats.setPlaceholder(Ui.discret("Aucun résultat."));
        resultats.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(WiredAnalyse.Resultat r, boolean vide) {
                super.updateItem(r, vide);
                if (vide || r == null) { setText(null); setGraphic(null); return; }
                Rectangle c = new Rectangle(9, 9, WiredGraphe.couleur(r.fil.rang));
                Label l = new Label(r.fil.nom + "  ·  case " + r.fil.caseTexte() + "\n" + r.role, c);
                l.setWrapText(true);
                l.maxWidthProperty().bind(lv.widthProperty().subtract(30));
                l.setStyle("-fx-font-size: 11px;");
                setText(null);
                setGraphic(l);
            }
        });
        resultats.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            WiredAnalyse.Resultat r = resultats.getSelectionModel().getSelectedItem();
            if (r == null) return;
            Zone.definir(r.fil.x, r.fil.y, r.fil.x, r.fil.y);
            Journal.succes("Zone définie sur la case " + r.fil.caseTexte() + ".");
        });

        return volet(bandeau(),
                Ui.bloc("Par mobi", attenteClic),
                Ui.bloc("Par texte ou variable", ligne,
                        Ui.aide("Texte : nom du wired, nom technique ou texte réglé. "
                                + "Variable : identifiant (ou nom si la liste de la salle est connue).")),
                Ui.bloc("Résultats", resultats,
                        Ui.aide("Double-clic : la case du wired devient la zone de travail.")),
                etatRecherche);
    }

    /**
     * Appele hors fil JavaFX par Salle a chaque clic sur un mobi. Pris en
     * compte seulement quand le volet Rechercher est affiche : plus besoin
     * d'« armer » un bouton avant de cliquer.
     */
    private void clicMobi(HFloorItem it) {
        if (it == null) return;
        Platform.runLater(() -> {
            if (!affiche(resultats)) return;
            String nom = Salle.nom(it.getTypeId(), false);
            attenteClic.setText("Mobi choisi : " + nom + " (id " + it.getId() + ", case ("
                    + it.getTile().getX() + "," + it.getTile().getY() + "))");
            chercherMobi(it.getId());
        });
    }

    /** Le noeud est-il vraiment a l'ecran (fenetre ouverte, volet choisi) ? */
    private static boolean affiche(Node n) {
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null
                || !n.getScene().getWindow().isShowing()) return false;
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    private void chercher(String mode, String texte) {
        if (texte == null || texte.isBlank()) {
            dernierMode = null; dernierTexte = null;
            if (dernierMobi == 0) {
                resultats.getItems().clear();
                dire(etatRecherche, "");
            }
            return;
        }
        dernierMode = mode; dernierTexte = texte; dernierMobi = 0;
        if ("Id de mobi".equals(mode)) {
            try { chercherMobi(Integer.parseInt(texte.trim())); }
            catch (NumberFormatException e) { dire(etatRecherche, "L'id d'un mobi est un nombre."); }
            return;
        }
        WiredAnalyse a = analyse();
        List<WiredAnalyse.Resultat> r = "Variable".equals(mode) ? a.parVariable(texte) : a.parTexte(texte);
        montrerResultats(a, r, "Variable".equals(mode) ? "la variable « " + texte.trim() + " »"
                : "« " + texte.trim() + " »");
    }

    private void chercherMobi(int id) {
        dernierMobi = id;
        WiredAnalyse a = analyse();
        montrerResultats(a, a.parMobi(id), "le mobi " + id);
    }

    /** Refait la derniere recherche apres un changement (fil JavaFX). */
    private void refaireRecherche() {
        if (dernierMobi != 0) chercherMobi(dernierMobi);
        else if (dernierTexte != null) chercher(dernierMode, dernierTexte);
    }

    private void montrerResultats(WiredAnalyse a, List<WiredAnalyse.Resultat> r, String quoi) {
        majListe(resultats, r, x -> x.fil.id + "|" + x.role);
        if (!a.salle) { dire(etatRecherche, "Pas de salle ouverte."); return; }
        String avert = a.nbLus == 0 && a.parId.size() > 0
                ? " Réglages pas encore lus : ça se fait tout seul, patiente un peu."
                : (a.nbLus < a.parId.size() ? " (" + a.nbLus + " / " + a.parId.size()
                   + " wired lus : résultats partiels.)" : "");
        dire(etatRecherche, r.size() + " wired pour " + quoi + "." + avert);
    }

    /**
     * Remplace le contenu d'une liste seulement s'il a change, en gardant la
     * selection : les mises a jour automatiques ne font pas sauter la liste.
     */
    private static <T> void majListe(ListView<T> lv, List<T> neuf,
                                     java.util.function.Function<T, String> cle) {
        javafx.collections.ObservableList<T> vieux = lv.getItems();
        if (vieux != null && vieux.size() == neuf.size()) {
            boolean pareil = true;
            for (int i = 0; i < neuf.size() && pareil; i++)
                pareil = cle.apply(vieux.get(i)).equals(cle.apply(neuf.get(i)));
            if (pareil) return;
        }
        T choisi = lv.getSelectionModel().getSelectedItem();
        String cleChoisie = choisi == null ? null : cle.apply(choisi);
        if (vieux == null) lv.setItems(FXCollections.observableArrayList(neuf));
        else vieux.setAll(neuf);
        if (cleChoisie != null)
            for (T x : neuf) if (cleChoisie.equals(cle.apply(x))) { lv.getSelectionModel().select(x); break; }
    }

    // ----------------------------------------------------------- analyse

    private WiredAnalyse analyse() {
        if (analyse == null) analyse = WiredAnalyse.maintenant();
        return analyse;
    }

    private WiredAnalyse rafraichirSansInterface() {
        analyse = WiredAnalyse.maintenant();
        return analyse;
    }

    /** Refait l'analyse et met a jour les trois volets (fil JavaFX). */
    private void rafraichir() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(this::rafraichir); return; }
        WiredAnalyse a = rafraichirSansInterface();
        try {
            graphe.montrer(a);
            if (grapheGrand != null) grapheGrand.montrer(a);
            WiredAnalyse.Pile p = graphe.choisie();
            remplirDetail(detail, p);
            if (detailGrand != null) remplirDetail(detailGrand, p);
            dire(etatGraphe, a.erreur != null ? "Analyse des wired impossible : " + a.erreur
                    : !a.salle ? "Pas de salle ouverte."
                    : a.piles.size() + " pile(s) · " + a.parId.size() + " wired · "
                      + a.liens.size() + " flèche(s)"
                      + (a.nbLus < a.parId.size() ? " · " + (a.parId.size() - a.nbLus)
                         + " non lu(s) : flèches incomplètes" : "")
                      + (a.nbIgnores > 0 ? " · " + a.nbIgnores + " wired ignoré(s), pas encore lisible(s)" : ""));
        } catch (Throwable t) { dire(etatGraphe, "Erreur d'affichage : " + t); }

        try {
            List<WiredAnalyse.Probleme> l = a.verifier();
            derniersProblemes = List.copyOf(l);
            majListe(problemes, l, q -> q.gravite + "|" + q.x + "|" + q.y + "|" + q.texte);
            int err = 0, att = 0, inf = 0;
            for (WiredAnalyse.Probleme q : l) {
                if (q.gravite == WiredAnalyse.Gravite.ERREUR) err++;
                else if (q.gravite == WiredAnalyse.Gravite.ATTENTION) att++;
                else inf++;
            }
            resumeVerif.setText(!a.salle ? "Pas de salle ouverte."
                    : l.isEmpty() ? "Aucun problème trouvé."
                    : err + " erreur(s) · " + att + " attention · " + inf + " info");
            Map<String, String> vars = WiredLecteur.variables();
            dire(etatVerif, a.nbLus == 0 && a.parId.size() > 0
                    ? "Réglages pas encore lus : seuls l'ordre et la composition des piles sont vérifiés pour l'instant."
                    : a.nbLus + " / " + a.parId.size() + " wired lus"
                      + (vars == null ? " · liste des variables inconnue (variables non vérifiées)" : ""));
        } catch (Throwable t) { dire(etatVerif, "Erreur de vérification : " + t); }

        try { refaireRecherche(); } catch (Throwable t) { dire(etatRecherche, "Erreur de recherche : " + t); }
    }

    private static void dire(Label l, String s) {
        if (l == null) return;
        if (Platform.isFxApplicationThread()) l.setText(s);
        else Platform.runLater(() -> l.setText(s));
    }
}

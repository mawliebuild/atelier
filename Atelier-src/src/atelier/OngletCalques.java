package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;

/**
 * Section Calques, en deux volets : « Calques » et « Rechercher un mobi ».
 *
 * Calques : des cases « visible » par famille de mobis. Decocher une case
 * masque ces mobis CHEZ TOI seulement (voir Calques) ; la recocher les
 * fait revenir, a leur etat le plus recent.
 *
 * Rechercher un mobi : on tape un bout de nom, on obtient la liste des mobis
 * de la salle regroupes par type, et on peut les faire clignoter dans le jeu,
 * masquer tout le reste, ou poser la zone autour d'eux.
 */
public class OngletCalques {

    // ----------------------------------------------------------------- UI

    public Tab construire() {
        Calques.installer();

        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab calques = new Tab("Calques", defiler(voletCalques()));
        Tab recherche = new Tab("Rechercher un mobi", defiler(voletRecherche()));
        calques.setClosable(false); recherche.setClosable(false);
        volets.getTabs().addAll(calques, recherche);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        Tab t = new Tab("Calques", racine);
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

    private static VBox volet(javafx.scene.Node... n) {
        VBox v = new VBox(12, n);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        return v;
    }

    // ============================================================ calques

    private static final String PARTOUT = "Partout", DANS = "Seulement dans la zone", HORS = "Seulement hors zone";

    /** Un calque de l'interface : sa case « visible » et son compteur. */
    private final class Ligne {
        final String cle;
        final CheckBox visible;
        final Label compte = new Label("");
        Ligne(String cle, String libelle) {
            this.cle = cle;
            visible = new CheckBox(libelle);
            visible.setSelected(true);
            visible.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(visible, Priority.ALWAYS);
            compte.setStyle("-fx-opacity: 0.7;");
            visible.selectedProperty().addListener((o, a, b) -> { if (!silence) appliquer(this); });
        }
        HBox noeud() {
            HBox h = new HBox(8, visible, compte);
            h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return h;
        }
    }

    private final List<Ligne> lignes = new ArrayList<>();
    private Ligne lWired, lTech, lMurs, lSols, lNom;
    private TextField nomTexte;
    /** Les noms a masquer (tags) : un mobi est pris s'il correspond a l'un d'eux. */
    private record Filtre(String texte, boolean exact) { }
    private final List<Filtre> filtres = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final javafx.scene.layout.FlowPane tags = new javafx.scene.layout.FlowPane(5, 5);
    private final ContextMenu suggestions = new ContextMenu();
    private ChoiceBox<String> portee;
    private Label etatCalques, totalLbl;
    /** Vrai pendant qu'on recoche les cases par programme (changement de salle). */
    private boolean silence = false;

    private Pane voletCalques() {
        lWired = new Ligne(Calques.WIRED, "Wired");
        lTech = new Ligne(Calques.TECHNIQUES, "Dalles et mobis invisibles");
        lMurs = new Ligne(Calques.MURAUX, "Mobis muraux");
        lSols = new Ligne(Calques.SOLS, "Mobis de sol ordinaires");
        lNom = new Ligne(Calques.NOM, "Nom contient…");
        lignes.addAll(List.of(lWired, lTech, lMurs, lSols, lNom));

        nomTexte = new TextField();
        nomTexte.setPromptText("Un bout de nom, puis Entrée");
        // Pendant la frappe : les vrais noms du jeu qui contiennent ce texte.
        nomTexte.textProperty().addListener((o, a, b) -> proposer(b));
        // Entree : le meilleur nom trouve devient un tag.
        nomTexte.setOnAction(e -> {
            String t = nomTexte.getText() == null ? "" : nomTexte.getText().trim();
            if (t.isEmpty()) return;
            List<NomsMobis.Nom> l = NomsMobis.chercher(t, 1);
            if (l.isEmpty()) ajouterFiltre(new Filtre(t, false));
            else ajouterFiltre(new Filtre(l.get(0).nom, true));
        });
        tags.setPrefWrapLength(220);
        tags.managedProperty().bind(tags.visibleProperty());
        tags.setVisible(false);

        portee = new ChoiceBox<>();
        portee.getItems().addAll(PARTOUT, DANS, HORS);
        portee.setValue(PARTOUT);
        portee.setMaxWidth(Double.MAX_VALUE);
        portee.valueProperty().addListener((o, a, b) -> reappliquerTout());
        Zone.ecouter(() -> { if (!PARTOUT.equals(portee.getValue())) reappliquerTout(); });

        totalLbl = new Label("Aucun mobi masqué");
        totalLbl.getStyleClass().add("calques-total");
        etatCalques = Ui.etat();

        Button tout = new Button("Tout réafficher");
        tout.getStyleClass().addAll("primaire", "calques-tout");
        tout.setMaxWidth(Double.MAX_VALUE);
        tout.setOnAction(e -> toutReafficher());

        // Version compacte : elle vit dans le panneau Calques, qui a deja sa
        // propre zone. Les mobis poses apres coup sont masques tout seuls.
        VBox v = volet(
                Ui.bloc("Calques visibles", lWired.noeud(), lTech.noeud(), lMurs.noeud(), lSols.noeud(),
                        lNom.noeud(), nomTexte, tags),
                totalLbl,
                tout,
                etatCalques);
        javafx.animation.Timeline suivi = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> reappliquerSiChange()));
        suivi.setCycleCount(javafx.animation.Animation.INDEFINITE);
        suivi.play();

        Calques.ecouter(this::majCompteurs);
        Calques.surOubli(() -> {
            recocher();
            dire(etatCalques, "");      // changement de salle : automatique, pas de message
        });
        majCompteurs();
        return v;
    }

    /** Jusqu'a 8 noms du jeu sous le champ ; un clic en fait un tag. */
    private void proposer(String texte) {
        List<NomsMobis.Nom> l = NomsMobis.chercher(texte == null ? "" : texte, 8);
        if (l.isEmpty() || !nomTexte.isFocused()) { suggestions.hide(); return; }
        List<MenuItem> items = new ArrayList<>();
        for (NomsMobis.Nom n : l) {
            MenuItem m = new MenuItem(n.nom);
            m.setOnAction(e -> ajouterFiltre(new Filtre(n.nom, true)));
            items.add(m);
        }
        suggestions.getItems().setAll(items);
        if (!suggestions.isShowing()) suggestions.show(nomTexte, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void ajouterFiltre(Filtre f) {
        suggestions.hide();
        nomTexte.clear();
        for (Filtre x : filtres)
            if (NomsMobis.normaliser(x.texte()).equals(NomsMobis.normaliser(f.texte()))) return;
        filtres.add(f);
        majTags();
        if (!lNom.visible.isSelected()) appliquer(lNom);
        else lNom.visible.setSelected(false);          // un nom ajoute : on le masque
    }

    private void retirerFiltre(Filtre f) {
        filtres.remove(f);
        majTags();
        if (lNom.visible.isSelected()) return;
        if (filtres.isEmpty()) lNom.visible.setSelected(true);   // plus rien a masquer
        else appliquer(lNom);
    }

    /** Un tag par nom : le nom et une croix pour l'enlever. */
    private void majTags() {
        List<javafx.scene.Node> n = new ArrayList<>();
        for (Filtre f : filtres) {
            Label nom = new Label(f.exact() ? f.texte() : "Contient « " + f.texte() + " »");
            nom.getStyleClass().add("tag-nom");
            Button x = new Button("×");
            x.getStyleClass().add("tag-croix");
            x.setFocusTraversable(false);
            x.setOnAction(e -> retirerFiltre(f));
            HBox tag = new HBox(4, nom, x);
            tag.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            tag.getStyleClass().add("tag");
            n.add(tag);
        }
        tags.getChildren().setAll(n);
        tags.setVisible(!n.isEmpty());
    }

    private String salleVue = null;

    /** Des mobis ont ete poses ou retires : les calques decoches s'appliquent aussi a eux. */
    private void reappliquerSiChange() {
        boolean unDecoche = false;
        for (Ligne l : lignes) if (!l.visible.isSelected()) { unDecoche = true; break; }
        String sig = Salle.sols().size() + "/" + Salle.murs().size();
        if (sig.equals(salleVue)) return;
        boolean premier = salleVue == null;
        salleVue = sig;
        if (unDecoche && !premier) reappliquerTout();
    }

    /** Masque ou reaffiche un calque selon sa case. */
    private void appliquer(Ligne l) { appliquer(l, false); }

    /**
     * auto : recalcul automatique (mobis poses, portee ou zone changee) ;
     * pas de message de resultat, les compteurs suffisent. Fil JavaFX.
     */
    private void appliquer(Ligne l, boolean auto) {
        boolean visible = l.visible.isSelected();
        String nomCalque = l.visible.getText();        // lu ici : fil JavaFX
        List<Filtre> fs = new ArrayList<>(filtres);
        String port = portee.getValue();
        if (!visible && l == lNom && fs.isEmpty()) {
            dire(etatCalques, "Écris un bout de nom, puis Entrée : il devient un tag à masquer.");
            silencieux(() -> l.visible.setSelected(true));
            return;
        }
        Salle.tache("calques", () -> {
            if (!verifierPret(etatCalques)) {
                Platform.runLater(() -> silencieux(() -> l.visible.setSelected(true)));
                return;
            }
            if (visible) {
                int n = Calques.reafficher(l.cle);
                if (!auto) dire(etatCalques, "« " + nomCalque + " » : " + n + " mobi(s) réaffiché(s).");
                return;
            }
            if (!Salle.furnidataPrete() && l != lMurs) {
                dire(etatCalques, "Furnidata pas encore chargée : impossible de reconnaître les mobis.");
                Platform.runLater(() -> silencieux(() -> l.visible.setSelected(true)));
                return;
            }
            List<HFloorItem> sols = new ArrayList<>();
            List<HWallItem> murs = new ArrayList<>();
            choisir(l, fs, port, sols, murs);
            int[] r = Calques.regler(l.cle, sols, murs);
            if (!auto) dire(etatCalques, "« " + nomCalque + " » : " + (sols.size() + murs.size())
                    + " mobi(s) masqué(s)" + (r[0] < sols.size() + murs.size() ? " (dont déjà masqués par un autre calque)" : "")
                    + ".");
        });
    }

    /** Les mobis d'un calque, portee comprise. Hors fil JavaFX. */
    private void choisir(Ligne l, List<Filtre> n, String port, List<HFloorItem> sols, List<HWallItem> murs) {
        for (HFloorItem it : Salle.sols()) {
            if (!dansPortee(it, port)) continue;
            String c = Salle.classe(it.getTypeId(), false);
            boolean pris;
            if (l == lWired) pris = Wired.estWired(c);
            else if (l == lTech) pris = Calques.estTechnique(c);
            else if (l == lSols) pris = c != null && Calques.estOrdinaire(c);
            else if (l == lNom) pris = correspond(it.getTypeId(), false, n);
            else pris = false;
            if (pris) sols.add(it);
        }
        if (l == lMurs || l == lNom) {
            for (HWallItem it : Salle.murs())
                if (l == lMurs || correspond(it.getTypeId(), true, n)) murs.add(it);
        }
    }

    private static boolean dansPortee(HFloorItem it, String port) {
        if (PARTOUT.equals(port) || !Zone.definie()) return true;
        boolean dedans = Zone.contient(it.getTile().getX(), it.getTile().getY());
        return DANS.equals(port) ? dedans : !dedans;
    }

    /** Le mobi correspond-il a l'un des tags ? */
    private static boolean correspond(int typeId, boolean mural, List<Filtre> fs) {
        String c = Salle.classe(typeId, mural), nom = Salle.nom(typeId, mural);
        for (Filtre f : fs) if (NomsMobis.correspond(f.texte(), f.exact(), nom, c)) return true;
        return false;
    }

    /** Recalcule tous les calques masques (portee changee, mobis poses depuis). */
    private void reappliquerTout() {
        for (Ligne l : lignes) if (!l.visible.isSelected()) appliquer(l, true);
    }

    private void toutReafficher() {
        silencieux(this::recocherCases);
        Salle.tache("calques", () -> {
            if (!verifierPret(etatCalques)) return;
            int n = Calques.reafficherTout();
            dire(etatCalques, n + " mobi(s) réaffiché(s).");
            Platform.runLater(() -> { if (rechercheEtat != null) rechercheEtat.setText(""); });
        });
    }

    private void recocher() { silencieux(this::recocherCases); }

    private void recocherCases() { for (Ligne l : lignes) l.visible.setSelected(true); }

    private void silencieux(Runnable r) {
        boolean avant = silence;
        silence = true;
        try { r.run(); } finally { silence = avant; }
    }

    private void majCompteurs() {
        for (Ligne l : lignes) {
            int n = Calques.nombre(l.cle);
            l.compte.setText(n > 0 ? n + " masqué(s)" : "");
        }
        int t = Calques.total();
        if (totalLbl != null) totalLbl.setText(t == 0 ? "Aucun mobi masqué" : t + " mobi(s) masqué(s) en tout");
        if (rechercheCompte != null) {
            int r = Calques.nombre(Calques.RECHERCHE);
            rechercheCompte.setText(r > 0 ? r + " mobi(s) masqué(s) par la recherche" : "");
        }
    }

    // ============================================================ recherche

    /** Une ligne de l'arbre : un groupe (type) ou un mobi. */
    private static final class Entree {
        final String texte;
        final List<HFloorItem> sols = new ArrayList<>();
        final List<HWallItem> murs = new ArrayList<>();
        Entree(String t) { texte = t; }
        @Override public String toString() { return texte; }
    }

    private TextField rechercheTexte;
    private TreeView<Entree> arbre;
    private Label rechercheResume, rechercheEtat, rechercheCompte;
    /** Les resultats de la derniere recherche. */
    private volatile List<HFloorItem> trouvesSols = List.of();
    private volatile List<HWallItem> trouvesMurs = List.of();
    private volatile boolean clignote = false;

    private Pane voletRecherche() {
        rechercheTexte = new TextField();
        rechercheTexte.setPromptText("Nom ou nom technique (vide = tout)");
        HBox.setHgrow(rechercheTexte, Priority.ALWAYS);
        Button chercher = new Button("Chercher");
        chercher.setOnAction(e -> chercher());
        rechercheTexte.setOnAction(e -> chercher());
        HBox ligneRecherche = new HBox(8, rechercheTexte, chercher);
        ligneRecherche.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        rechercheResume = Ui.valeur("—");

        arbre = new TreeView<>(new TreeItem<>(new Entree("")));
        arbre.setShowRoot(false);
        arbre.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        arbre.setPrefHeight(280);
        arbre.setMinHeight(160);
        VBox.setVgrow(arbre, Priority.ALWAYS);

        Button montrer = new Button("Montrer dans le jeu");
        montrer.getStyleClass().add("primaire");
        montrer.setMaxWidth(Double.MAX_VALUE);
        montrer.setOnAction(e -> montrer());

        Button masquerReste = new Button("Masquer tout le reste");
        masquerReste.setOnAction(e -> masquerLeReste());
        Button reafficher = new Button("Tout réafficher");
        reafficher.setOnAction(e -> {
            Salle.tache("recherche", () -> {
                if (!verifierPret(rechercheEtat)) return;
                int n = Calques.reafficher(Calques.RECHERCHE);
                dire(rechercheEtat, n + " mobi(s) réaffiché(s).");
            });
        });
        Button zone = new Button("Définir la zone autour");
        zone.setOnAction(e -> zoneAutour());

        rechercheCompte = Ui.discret("");          // un compteur, pas un resultat
        rechercheCompte.visibleProperty().bind(rechercheCompte.textProperty().isNotEmpty());
        rechercheCompte.managedProperty().bind(rechercheCompte.visibleProperty());
        rechercheEtat = Ui.etat();

        Label aide = Ui.aide("Sélectionne une ou plusieurs lignes (un type entier ou un mobi) ; "
                + "sans sélection, les boutons agissent sur tous les résultats. "
                + "« Montrer » fait clignoter les mobis chez toi seulement. "
                + "« Masquer tout le reste » ne laisse visibles que les résultats ; "
                + "recharger la salle réaffiche tout.");

        VBox v = volet(
                Ui.bloc("Rechercher", ligneRecherche, rechercheResume),
                Ui.bloc("Mobis trouvés", arbre),
                montrer,
                Ui.ligne(masquerReste, reafficher, zone),
                rechercheCompte,
                aide,
                rechercheEtat);
        majCompteurs();
        return v;
    }

    private void chercher() {
        String texte = rechercheTexte.getText() == null ? "" : rechercheTexte.getText().trim().toLowerCase(Locale.ROOT);
        Salle.tache("recherche", () -> {
            if (!Salle.dansUneSalle()) { dire(rechercheEtat, "Pas dans une salle (ou l'Atelier pas encore prêt)."); return; }
            if (!Salle.furnidataPrete())
                dire(rechercheEtat, "La furnidata n'est pas encore là : recherche sur les numéros de type seulement.");

            // type -> mobis, groupes tries par nombre decroissant
            Map<String, Entree> groupes = new LinkedHashMap<>();
            List<HFloorItem> sols = new ArrayList<>();
            List<HWallItem> murs = new ArrayList<>();
            for (HFloorItem it : Salle.sols()) {
                String nom = Salle.nom(it.getTypeId(), false), cls = Salle.classe(it.getTypeId(), false);
                if (!contient(nom, cls, texte)) continue;
                sols.add(it);
                groupes.computeIfAbsent("s" + it.getTypeId(), k -> new Entree(libelle(nom, cls))).sols.add(it);
            }
            for (HWallItem it : Salle.murs()) {
                String nom = Salle.nom(it.getTypeId(), true), cls = Salle.classe(it.getTypeId(), true);
                if (!contient(nom, cls, texte)) continue;
                murs.add(it);
                groupes.computeIfAbsent("m" + it.getTypeId(), k -> new Entree(libelle(nom, cls) + "  (mural)")).murs.add(it);
            }
            List<Entree> liste = new ArrayList<>(groupes.values());
            liste.sort((a, b) -> Integer.compare(b.sols.size() + b.murs.size(), a.sols.size() + a.murs.size()));

            TreeItem<Entree> racine = new TreeItem<>(new Entree(""));
            for (Entree g : liste) {
                int n = g.sols.size() + g.murs.size();
                Entree titre = new Entree(n + " × " + g.texte);
                titre.sols.addAll(g.sols); titre.murs.addAll(g.murs);
                TreeItem<Entree> noeud = new TreeItem<>(titre);
                List<HFloorItem> ts = new ArrayList<>(g.sols);
                ts.sort(Comparator.<HFloorItem>comparingInt(i -> i.getTile().getX())
                        .thenComparingInt(i -> i.getTile().getY())
                        .thenComparingDouble(i -> i.getTile().getZ()));
                for (HFloorItem it : ts) {
                    Entree e = new Entree(String.format(Locale.ROOT, "#%d   case (%d,%d)   alt. %.2f   rot. %d%s",
                            it.getId(), it.getTile().getX(), it.getTile().getY(), it.getTile().getZ(),
                            Salle.rotation(it), Calques.estMasque(it.getId(), false) ? "   · masqué" : ""));
                    e.sols.add(it);
                    noeud.getChildren().add(new TreeItem<>(e));
                }
                for (HWallItem it : g.murs) {
                    Entree e = new Entree("#" + it.getId() + "   " + (it.getLocation() == null ? "?" : it.getLocation().trim())
                            + (Calques.estMasque(it.getId(), true) ? "   · masqué" : ""));
                    e.murs.add(it);
                    noeud.getChildren().add(new TreeItem<>(e));
                }
                racine.getChildren().add(noeud);
            }
            trouvesSols = List.copyOf(sols);
            trouvesMurs = List.copyOf(murs);
            String resume = (sols.size() + murs.size()) + " mobi(s) : " + sols.size() + " au sol, "
                    + murs.size() + " au mur, " + liste.size() + " type(s)";
            Platform.runLater(() -> {
                arbre.setRoot(racine);
                rechercheResume.setText(resume);
            });
        });
    }

    private static boolean contient(String nom, String cls, String texte) {
        if (texte.isEmpty()) return true;
        return (nom != null && nom.toLowerCase(Locale.ROOT).contains(texte))
                || (cls != null && cls.toLowerCase(Locale.ROOT).contains(texte));
    }

    private static String libelle(String nom, String cls) {
        if (cls == null || cls.equals(nom)) return nom;
        return nom + "  ·  " + cls;
    }

    /** Les mobis selectionnes dans l'arbre, a defaut tous les resultats. Fil JavaFX. */
    private Object[] selection() {
        Set<HFloorItem> s = new LinkedHashSet<>();
        Set<HWallItem> w = new LinkedHashSet<>();
        for (TreeItem<Entree> t : arbre.getSelectionModel().getSelectedItems()) {
            if (t == null || t.getValue() == null) continue;
            s.addAll(t.getValue().sols);
            w.addAll(t.getValue().murs);
        }
        if (s.isEmpty() && w.isEmpty()) { s.addAll(trouvesSols); w.addAll(trouvesMurs); }
        return new Object[]{new ArrayList<>(s), new ArrayList<>(w)};
    }

    @SuppressWarnings("unchecked")
    private void montrer() {
        Object[] sel = selection();
        List<HFloorItem> s = (List<HFloorItem>) sel[0];
        List<HWallItem> w = (List<HWallItem>) sel[1];
        if (s.isEmpty() && w.isEmpty()) { rechercheEtat.setText("Lance d'abord une recherche."); return; }
        if (clignote) return;
        clignote = true;
        Salle.tache("clignote", () -> {
            try {
                if (!verifierPret(rechercheEtat)) return;
                dire(rechercheEtat, "Clignotement de " + (s.size() + w.size()) + " mobi(s)…");
                Calques.clignoter(s, w, 4, 350);
                dire(rechercheEtat, (s.size() + w.size()) + " mobi(s) montré(s)."
                        + (s.size() + w.size() > 0 && Calques.total() > 0 ? " (Ceux masqués par un calque restent masqués.)" : ""));
            } finally { clignote = false; }
        });
    }

    @SuppressWarnings("unchecked")
    private void masquerLeReste() {
        Object[] sel = selection();
        List<HFloorItem> s = (List<HFloorItem>) sel[0];
        List<HWallItem> w = (List<HWallItem>) sel[1];
        if (s.isEmpty() && w.isEmpty()) { rechercheEtat.setText("Lance d'abord une recherche."); return; }
        Salle.tache("recherche", () -> {
            if (!verifierPret(rechercheEtat)) return;
            Set<Integer> gardeS = new HashSet<>(), gardeW = new HashSet<>();
            for (HFloorItem it : s) gardeS.add(it.getId());
            for (HWallItem it : w) gardeW.add(it.getId());
            List<HFloorItem> autresS = new ArrayList<>();
            List<HWallItem> autresW = new ArrayList<>();
            for (HFloorItem it : Salle.sols()) if (!gardeS.contains(it.getId())) autresS.add(it);
            for (HWallItem it : Salle.murs()) if (!gardeW.contains(it.getId())) autresW.add(it);
            Calques.regler(Calques.RECHERCHE, autresS, autresW);
            dire(rechercheEtat, (autresS.size() + autresW.size()) + " autre(s) mobi(s) masqué(s) ; "
                    + (s.size() + w.size()) + " restent visibles.");
        });
    }

    @SuppressWarnings("unchecked")
    private void zoneAutour() {
        List<HFloorItem> s = (List<HFloorItem>) selection()[0];
        if (s.isEmpty()) { rechercheEtat.setText("Aucun mobi de sol dans la sélection."); return; }
        Salle.tache("recherche", () -> {
            int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
            for (HFloorItem it : s) {
                int x = it.getTile().getX(), y = it.getTile().getY();
                int[] e = Salle.emprise(it);
                x1 = Math.min(x1, x); y1 = Math.min(y1, y);
                x2 = Math.max(x2, x + e[0] - 1); y2 = Math.max(y2, y + e[1] - 1);
            }
            final int ax = x1, ay = y1, bx = x2, by = y2;
            Platform.runLater(() -> {
                Zone.definir(ax, ay, bx, by);
                rechercheEtat.setText("Zone : " + Zone.texte());
            });
        });
    }

    // ============================================================== outils

    private static boolean verifierPret(Label etat) {
        if (Salle.gp() == null) { dire(etat, "L'Atelier n'est pas encore prêt."); return false; }
        if (!Calques.pret()) { dire(etat, "Les calques s'installent : encore un instant."); return false; }
        if (Calques.ecoutes() == 0) { dire(etat, "Aucune écoute de paquets : masquer est désactivé par prudence."); return false; }
        if (!Salle.dansUneSalle()) { dire(etat, "Pas dans une salle."); return false; }
        return true;
    }

    private static void dire(Label l, String texte) {
        if (l == null) return;
        Platform.runLater(() -> l.setText(texte));
    }
}

package atelier;

import atelier.EcartsDefaut.Ecart;
import atelier.EcartsDefaut.Ligne;
import atelier.EcartsDefaut.Origine;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

/**
 * Volet « Écarts » (Murs › Aligner des murs) : un mobi mural par carte
 * compacte, avec seulement l'essentiel.
 *
 *     [vignette]  Poster                      [enregistré] [⚙]
 *                 → 2 pans · ↑ 32 px
 *
 * Le reste est range : le ⚙ d'une carte ouvre Modifier / Revenir au defaut /
 * Supprimer ; le ⚙ du haut, l'export et le filtre. L'origine detaillee est
 * dans la bulle de la pastille. Le panneau d'edition n'apparait que quand on
 * modifie un ecart (⚙ › Modifier, double-clic, ou un clic sur un mobi mural
 * dans le jeu).
 *
 * Contient aussi les petits elements partages avec EcartsBandeau : vignette
 * du mobi, pastille d'origine, bouton ⚙ a menu.
 */
public final class EcartsVolet {

    private final ListView<Ligne> liste = new ListView<>();
    private final TextField recherche = new TextField();
    private final CheckMenuItem seulementLesMiens = new CheckMenuItem("Seulement mes écarts");
    private final Label etat = Ui.etat();
    private List<Ligne> toutes = new ArrayList<>();

    // edition : le mobi clique dans le jeu, ou celui choisi par « Modifier »
    private final ImageView eImage = vignetteVue();
    private final Label eNom = Ui.valeur("");
    private final Label ePastille = pastilleVue();
    private final Spinner<Integer> eDroite = spin(EcartsDefaut.BASE_DROITE), eHaut = spin(EcartsDefaut.BASE_HAUT);
    private VBox edition;
    private int editType = -1;
    private String editNomTexte = null;

    public Tab construire() {
        // --- haut : recherche, aide, reglages
        recherche.setPromptText("Rechercher un mobi");
        recherche.textProperty().addListener((o, a, n) -> filtrer());
        HBox.setHgrow(recherche, Priority.ALWAYS);
        recherche.setMaxWidth(Double.MAX_VALUE);
        seulementLesMiens.setOnAction(e -> filtrer());
        MenuItem exporter = new MenuItem("Exporter mes écarts comme défauts…");
        exporter.setOnAction(e -> exporter());
        Button general = boutonReglages("Options et export",
                () -> List.of(seulementLesMiens, new SeparatorMenuItem(), exporter));
        Button info = Ui.info(new ArrayList<>(List.of(
                Ui.aide("« En ligne » et « En grille » reprennent tout seuls l'écart du mobi cliqué."))));
        HBox haut = new HBox(6, recherche, info, general);
        haut.setAlignment(Pos.CENTER_LEFT);

        // --- edition, cachee tant qu'on ne modifie rien
        eNom.setMinWidth(0);
        HBox.setHgrow(eNom, Priority.ALWAYS);
        eNom.setMaxWidth(Double.MAX_VALUE);
        Button fermer = boutonIcone(Icones.VIDER, "Fermer");
        fermer.setOnAction(e -> montrer(edition, false));
        HBox tete = new HBox(8, cadre(eImage, 34), eNom, ePastille, fermer);
        tete.setAlignment(Pos.CENTER_LEFT);
        Button enregistrer = new Button("Enregistrer");
        enregistrer.getStyleClass().add("primaire");
        enregistrer.setOnAction(e -> enregistrerEdition());
        edition = new VBox(8, tete, champ("→ Côte à côte", eDroite, "pans"),
                champ("↑ En hauteur", eHaut, "px"), enregistrer);
        edition.getStyleClass().add("boite");
        edition.setPadding(new Insets(10));
        montrer(edition, false);

        // --- la liste
        liste.setPrefHeight(320);
        liste.setCellFactory(l -> new Carte());
        Label vide = new Label("Aucun écart pour l'instant. Pose des copies : l'écart se retient tout seul.");
        vide.setWrapText(true);
        liste.setPlaceholder(vide);
        VBox.setVgrow(liste, Priority.ALWAYS);

        VBox v = new VBox(10, haut, edition, liste, etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(() -> Platform.runLater(this::depuisLeJeu));
        Ecarts.ecouter(() -> Platform.runLater(this::recharger));
        String err = EcartsDefaut.erreur();
        if (err != null) Journal.debug(err);       // deja dit une fois par EcartsDefaut
        depuisLeJeu();
        recharger();

        Tab t = new Tab("Écarts", sp);
        t.setClosable(false);
        // La furnidata arrive parfois apres : les noms et vignettes se completent a l'ouverture.
        t.selectedProperty().addListener((o, a, n) -> { if (n) recharger(); });
        return t;
    }

    // ---------------------------------------------------------- liste

    private void recharger() {
        toutes = Ecarts.liste();
        filtrer();
    }

    private void filtrer() {
        String q = NomsMobis.normaliser(recherche.getText() == null ? "" : recherche.getText().trim());
        boolean miens = seulementLesMiens.isSelected();
        List<Ligne> r = new ArrayList<>();
        for (Ligne l : toutes) {
            if (miens && l.enregistre == null) continue;
            Ecart e = l.effectif;
            String foin = NomsMobis.normaliser((e.nom == null ? "" : e.nom) + " " + (e.classe == null ? "" : e.classe));
            if (q.isEmpty() || foin.contains(q)) r.add(l);
        }
        liste.getItems().setAll(r);
    }

    /** Une carte : vignette, nom, ecart court, pastille d'origine, ⚙. */
    private final class Carte extends ListCell<Ligne> {
        private final ImageView img = vignetteVue();
        private final Label nom = new Label(), ecart = Ui.discret("");
        private final Label pastille = pastilleVue();
        private final Tooltip bulleEcart = bulle("");
        private Ligne ligne;
        private final Button reglages = boutonReglages("Modifier, revenir au défaut…", this::menu);
        private final HBox boite;
        {
            nom.setStyle("-fx-font-weight: bold;");
            nom.setMinWidth(0);
            ecart.setMinWidth(0);
            ecart.setTooltip(bulleEcart);
            VBox textes = new VBox(1, nom, ecart);
            HBox.setHgrow(textes, Priority.ALWAYS);
            textes.setMaxWidth(Double.MAX_VALUE);
            textes.setMinWidth(0);
            textes.setAlignment(Pos.CENTER_LEFT);
            boite = new HBox(8, cadre(img, 34), textes, pastille, reglages);
            boite.setAlignment(Pos.CENTER_LEFT);
            boite.setPadding(new Insets(2, 0, 2, 0));
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && ligne != null) editer(ligne.effectif);
            });
        }

        private List<MenuItem> menu() {
            List<MenuItem> m = new ArrayList<>();
            if (ligne == null) return m;
            Ecart e = ligne.effectif;
            MenuItem modifier = new MenuItem("Modifier…");
            modifier.setDisable(e.type < 0);
            modifier.setOnAction(x -> editer(e));
            m.add(modifier);
            if (ligne.enregistre != null) {
                Ligne l = ligne;
                MenuItem oublier = new MenuItem(l.defaut != null ? "Revenir au défaut" : "Supprimer");
                oublier.setOnAction(x -> {
                    Ecarts.oublier(l.enregistre.type);
                    Ui.succes(etat, l.defaut != null ? "« " + e.nom + " » reprend l'écart livré avec le programme."
                            : "Écart de « " + e.nom + " » supprimé.");
                });
                m.add(new SeparatorMenuItem());
                m.add(oublier);
            }
            return m;
        }

        @Override protected void updateItem(Ligne l, boolean vide) {
            super.updateItem(l, vide);
            setText(null);
            ligne = vide ? null : l;
            if (vide || l == null) { setGraphic(null); return; }
            Ecart e = l.effectif;
            nom.setText(e.nom);
            ecart.setText(EcartsDefaut.court(e.droite, e.haut));
            bulleEcart.setText(EcartsDefaut.enClair(e.droite, e.haut));
            majPastille(pastille, EcartsDefaut.pastille(l), EcartsDefaut.detail(l),
                    l.enregistre != null ? Ton.ACCENT : Ton.DISCRET);
            montrerVignette(img, e.classe, 34);
            setGraphic(boite);
        }
    }

    // ------------------------------------------------------- edition

    /** Le mobi clique dans le jeu devient celui qu'on modifie. */
    private void depuisLeJeu() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null || m.typeId < 0 || m.typeId == editType) return;
        Ecarts.Mobi x = Ecarts.pour(m.typeId, m.position, m.nom);
        editer(m.typeId, x.nom, x.classe, x.droite.valeur, x.haut.valeur);
    }

    private void editer(Ecart e) { editer(e.type, e.nom, e.classe, e.droite, e.haut); }

    private void editer(int type, String nom, String classe, int droite, int haut) {
        if (type < 0) return;
        editType = type; editNomTexte = nom;
        Ecarts.Mobi x = Ecarts.pour(type, null, nom);
        eNom.setText(nom);
        boolean[] axes = {true, false};
        Origine[] o = {x.droite.origine, x.haut.origine};
        majPastille(ePastille, EcartsDefaut.pastille(axes, o),
                "→ " + x.droite.origine.texte + "\n↑ " + x.haut.origine.texte,
                x.enregistre() ? Ton.ACCENT : Ton.DISCRET);
        montrerVignette(eImage, classe != null ? classe : x.classe, 34);
        eDroite.getValueFactory().setValue(droite > 0 ? droite : x.droite.valeur);
        eHaut.getValueFactory().setValue(haut > 0 ? haut : x.haut.valeur);
        montrer(edition, true);
    }

    private void enregistrerEdition() {
        if (editType < 0) return;
        Generateur.prendre(eDroite); Generateur.prendre(eHaut);
        Ecarts.enregistrer(editType, eDroite.getValue(), eHaut.getValue());
        montrer(edition, false);
        Ui.succes(etat, "Écart de « " + editNomTexte + " » enregistré : "
                + EcartsDefaut.court(eDroite.getValue(), eHaut.getValue()) + ".");
    }

    // -------------------------------------------------------- export

    private void exporter() {
        List<Ecart> l = new ArrayList<>();
        for (Ligne x : Ecarts.liste()) l.add(x.effectif);
        if (l.isEmpty()) { Ui.erreur(etat, "Aucun écart à exporter pour l'instant."); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Exporter mes écarts comme défauts");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fichier JSON", "*.json"));
        File dossier = new File(System.getProperty("user.home"), "Documents/Atelier-src/ressources/atelier");
        if (!dossier.isDirectory()) dossier = new File(System.getProperty("user.home"));
        fc.setInitialDirectory(dossier);
        fc.setInitialFileName("ecarts-defaut.json");
        File f = fc.showSaveDialog(liste.getScene() == null ? null : liste.getScene().getWindow());
        if (f == null) return;
        try {
            String json = EcartsDefaut.ecrire(l);
            Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
            // verifier apres coup : le fichier se relit et contient bien tout
            int relus = EcartsDefaut.lire(Files.readString(f.toPath(), StandardCharsets.UTF_8)).size();
            int sansClasse = 0;
            for (Ecart e : l) if (e.classe == null) sansClasse++;
            Ui.succes(etat, relus + " écart(s) exporté(s) dans " + f.getName() + ". Range-le à la place de "
                    + "ressources/atelier/ecarts-defaut.json : la prochaine version les aura."
                    + (sansClasse > 0 ? " " + sansClasse + " sans nom technique : ouvre une salle, puis réexporte." : ""));
        } catch (Throwable t) {
            Ui.erreur(etat, "Export impossible", t);
        }
    }

    // ------------------------------------------- elements partages

    /** Ton d'une pastille. */
    enum Ton { ACCENT, DISCRET, ATTENTION }

    /** Une pastille d'origine, vide : la remplir avec majPastille. */
    static Label pastilleVue() {
        Label l = new Label();
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setTooltip(bulle(""));
        return l;
    }

    static void majPastille(Label p, String texte, String bulle, Ton ton) {
        p.setText(Ui.majuscule(texte));
        p.getTooltip().setText(bulle);
        String c = ton == Ton.ACCENT ? "#3E86AC" : ton == Ton.ATTENTION ? "#B08A3E" : "#8A857A";
        p.setStyle("-fx-font-size: 10px; -fx-text-fill: " + c + "; -fx-border-color: " + c + ";"
                + " -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 0 6 0 6;");
    }

    /** Bulle rapide. */
    static Tooltip bulle(String texte) {
        Tooltip t = new Tooltip(texte);
        t.setShowDelay(javafx.util.Duration.millis(150));
        t.setWrapText(true);
        t.setMaxWidth(300);
        return t;
    }

    /** Petit bouton icone, sans fond, avec bulle. */
    static Button boutonIcone(String icone, String aide) {
        javafx.scene.shape.SVGPath ic = Icones.trace(icone, "icone");
        ic.setStyle("-fx-stroke: #5A564C;");
        ic.setScaleX(0.62); ic.setScaleY(0.62);
        Button b = new Button();
        b.setGraphic(new Group(ic));
        b.setFocusTraversable(false);
        b.setStyle("-fx-background-color: transparent; -fx-padding: 2; -fx-cursor: hand;");
        b.setTooltip(bulle(aide));
        return b;
    }

    /** Un ⚙ qui ouvre un petit menu, refait a chaque clic (il suit l'etat). */
    static Button boutonReglages(String aide, Supplier<List<MenuItem>> items) {
        Button b = boutonIcone(Icones.REGLAGES, aide);
        ContextMenu m = new ContextMenu();
        b.setOnAction(e -> {
            if (m.isShowing()) { m.hide(); return; }
            m.getItems().setAll(items.get());
            if (!m.getItems().isEmpty()) m.show(b, Side.BOTTOM, 0, 2);
        });
        return b;
    }

    /** Vue de vignette en pixels nets. */
    static ImageView vignetteVue() {
        ImageView v = new ImageView();
        v.setPreserveRatio(true);
        v.setSmooth(false);
        return v;
    }

    /** Une case de taille fixe, centree, pour que les lignes restent alignees. */
    static StackPane cadre(ImageView v, double boite) {
        StackPane c = new StackPane(v);
        c.setMinSize(boite, boite); c.setPrefSize(boite, boite); c.setMaxSize(boite, boite);
        return c;
    }

    /** Montre l'icone du mobi (chargee en fond) ; rien si inconnue. */
    static void montrerVignette(ImageView v, String classe, double boite) {
        Image i = vignette(classe);
        v.setImage(i);
        ajuster(v, boite);
        if (i != null && i.getProgress() < 1)
            i.progressProperty().addListener((o, a, n) -> { if (n.doubleValue() >= 1 && v.getImage() == i) ajuster(v, boite); });
    }

    /** Taille reelle (pixels nets) ; reduite seulement si l'icone deborde. */
    private static void ajuster(ImageView v, double boite) {
        Image i = v.getImage();
        if (i == null || i.isError() || i.getWidth() <= 0) { v.setFitWidth(0); v.setFitHeight(0); v.setSmooth(false); return; }
        boolean grande = i.getWidth() > boite || i.getHeight() > boite;
        v.setFitWidth(grande ? boite : 0);
        v.setFitHeight(grande ? boite : 0);
        v.setSmooth(grande);
    }

    private static final Map<String, Image> vignettes = new HashMap<>();

    /** L'icone officielle du mobi mural, chargee en fond, gardee en cache ; null si inconnue. Fil JavaFX. */
    static Image vignette(String classe) {
        if (classe == null || !Salle.furnidataPrete()) return null;
        Image i = vignettes.get(classe);
        if (i != null) return i.isError() ? null : i;
        try {
            furnidata.details.WallItemDetails d = AtelierLauncher.moteur().getFurniDataTools().getWallItemDetails(classe);
            if (d == null || d.revision <= 0) return null;
            String c = classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe;
            i = new Image("https://images.habbo.com/dcr/hof_furni/" + d.revision + "/" + c + "_icon.png", true);
            vignettes.put(classe, i);
            return i;
        } catch (Throwable t) {
            Journal.debug("Écarts : vignette de " + classe + " impossible : " + t);
            return null;
        }
    }

    static void montrer(Node n, boolean v) { n.setVisible(v); n.setManaged(v); }

    // --------------------------------------------------------- outils

    private static Spinner<Integer> spin(int defaut) {
        Spinner<Integer> s = new Spinner<>(1, 500, defaut);
        s.setEditable(true);
        s.setPrefWidth(90);
        Generateur.valider(s);
        return s;
    }

    private static HBox champ(String libelle, Spinner<Integer> s, String unite) {
        Label l = new Label(libelle);
        l.setMinWidth(100); l.setPrefWidth(100);
        l.setWrapText(true);
        HBox h = new HBox(8, l, s, new Label(unite));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}

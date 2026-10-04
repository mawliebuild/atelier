package atelier;

import atelier.EcartsDefaut.Ecart;
import atelier.EcartsDefaut.Ligne;
import atelier.EcartsDefaut.Origine;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Volet « Écarts enregistrés » (Murs › Aligner des murs) : la liste lisible
 * des ecarts connus, un mobi par ligne (vignette, nom, ecart en clair, d'ou il
 * vient), avec Modifier et Supprimer, une recherche, et l'export des ecarts
 * comme defauts des prochaines versions.
 */
public final class EcartsVolet {

    private final ListView<Ligne> liste = new ListView<>();
    private final TextField recherche = new TextField();
    private final Label etat = Ui.etat();
    private List<Ligne> toutes = new ArrayList<>();

    // edition : le mobi clique dans le jeu, ou celui choisi par « Modifier »
    private final Label editNom = Ui.valeur("Clique un mobi mural dans le jeu, ou « Modifier » dans la liste.");
    private final Spinner<Integer> eDroite = spin(EcartsDefaut.BASE_DROITE), eHaut = spin(EcartsDefaut.BASE_HAUT);
    private final Button eEnregistrer = new Button("Enregistrer");
    private VBox edition;
    private int editType = -1;
    private String editNomTexte = null;

    public Tab construire() {
        editNom.setWrapText(true);
        eEnregistrer.getStyleClass().add("primaire");
        eEnregistrer.setOnAction(e -> enregistrerEdition());
        edition = new VBox(8, champ("→ Côte à côte", eDroite, "pans"), champ("↑ En hauteur", eHaut, "px"), eEnregistrer);
        edition.setDisable(true);

        recherche.setPromptText("Rechercher un mobi");
        recherche.textProperty().addListener((o, a, n) -> filtrer());

        liste.setPrefHeight(260);
        liste.setCellFactory(l -> new Cellule());
        liste.setPlaceholder(new Label("Aucun écart pour l'instant. Pose des copies dans « En ligne » ou « En grille » : l'écart se retient tout seul."));
        ((Label) liste.getPlaceholder()).setWrapText(true);

        Button exporter = new Button("Exporter mes écarts comme défauts");
        exporter.setMaxWidth(Double.MAX_VALUE);
        exporter.setOnAction(e -> exporter());

        VBox v = new VBox(12,
                Ui.discret("Chaque mobi mural garde son écart. « En ligne » et « En grille » le reprennent "
                        + "tout seuls quand tu cliques ce mobi. Ton écart passe avant celui livré avec le programme."),
                Ui.bloc("Modifier un écart", editNom, edition),
                Ui.bloc("Écarts connus", recherche, liste),
                Ui.bloc("Pour les prochaines versions", exporter,
                        Ui.aide("Crée un fichier avec tous les écarts de la liste. Range-le à la place de "
                                + "ressources/atelier/ecarts-defaut.json : la prochaine version les aura par défaut.")),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(() -> Platform.runLater(this::depuisLeJeu));
        Ecarts.ecouter(() -> Platform.runLater(this::recharger));
        String err = EcartsDefaut.erreur();
        if (err != null) etat.setText(err);
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
        List<Ligne> r = new ArrayList<>();
        for (Ligne l : toutes) {
            Ecart e = l.effectif;
            String foin = NomsMobis.normaliser((e.nom == null ? "" : e.nom) + " " + (e.classe == null ? "" : e.classe));
            if (q.isEmpty() || foin.contains(q)) r.add(l);
        }
        liste.getItems().setAll(r);
    }

    /** Une ligne : vignette, nom, ecart en clair et origine, Modifier / Supprimer. */
    private final class Cellule extends ListCell<Ligne> {
        private final ImageView img = new ImageView();
        private final Label nom = new Label(), detail = Ui.discret("");
        private final Button modifier = new Button("Modifier"), supprimer = new Button("Supprimer");
        private final HBox boite;
        {
            img.setFitWidth(26); img.setFitHeight(26); img.setPreserveRatio(true);
            StackPane cadre = new StackPane(img);
            cadre.setMinSize(28, 28); cadre.setPrefSize(28, 28);
            nom.setWrapText(true);
            nom.setStyle("-fx-font-weight: bold;");
            VBox textes = new VBox(2, nom, detail);
            HBox.setHgrow(textes, Priority.ALWAYS);
            textes.setMaxWidth(Double.MAX_VALUE);
            textes.setMinWidth(0);
            modifier.setFocusTraversable(false); supprimer.setFocusTraversable(false);
            VBox boutons = new VBox(4, modifier, supprimer);
            boutons.setAlignment(Pos.CENTER_RIGHT);
            boite = new HBox(8, cadre, textes, boutons);
            boite.setAlignment(Pos.CENTER_LEFT);
        }
        @Override protected void updateItem(Ligne l, boolean vide) {
            super.updateItem(l, vide);
            setText(null);
            if (vide || l == null) { setGraphic(null); return; }
            Ecart e = l.effectif;
            nom.setText(e.nom);
            detail.setText(enClair(e) + "\n" + origine(l));
            img.setImage(vignette(e.classe));
            modifier.setOnAction(x -> editer(e.type, e.nom, e.droite, e.haut));
            modifier.setDisable(e.type < 0);
            if (l.enregistre != null) {
                supprimer.setText(l.defaut != null ? "Revenir au défaut" : "Supprimer");
                supprimer.setOnAction(x -> {
                    Ecarts.oublier(l.enregistre.type);
                    etat.setText(l.defaut != null ? "« " + e.nom + " » remis à l'écart livré avec le programme."
                            : "Écart de « " + e.nom + " » supprimé.");
                });
                supprimer.setVisible(true); supprimer.setManaged(true);
            } else {
                supprimer.setVisible(false); supprimer.setManaged(false);
            }
            setGraphic(boite);
        }
    }

    /** « → 2 pans côte à côte · ↑ 32 px en hauteur ». */
    static String enClair(Ecart e) {
        List<String> p = new ArrayList<>();
        if (e.droite > 0) p.add("→ " + e.droite + (e.droite > 1 ? " pans" : " pan") + " côte à côte");
        if (e.haut > 0) p.add("↑ " + e.haut + " px en hauteur");
        return p.isEmpty() ? "Aucun écart" : String.join("  ·  ", p);
    }

    private static String origine(Ligne l) {
        boolean d = l.effectif.droite <= 0 || l.origine(true) == Origine.ENREGISTRE;
        boolean h = l.effectif.haut <= 0 || l.origine(false) == Origine.ENREGISTRE;
        if (l.enregistre == null) return "Livré avec le programme.";
        if (l.defaut == null) return "Enregistré par toi.";
        if (d && h) return "Enregistré par toi (remplace le défaut).";
        return "En partie enregistré par toi, le reste livré avec le programme.";
    }

    private static final Map<String, Image> vignettes = new HashMap<>();

    /** L'icone officielle du mobi mural, chargee en fond ; null si inconnue. */
    private static Image vignette(String classe) {
        if (classe == null || !Salle.furnidataPrete()) return null;
        Image i = vignettes.get(classe);
        if (i != null) return i.isError() ? null : i;
        try {
            furnidata.details.WallItemDetails d = AtelierLauncher.moteur().getFurniDataTools().getWallItemDetails(classe);
            if (d == null || d.revision <= 0) return null;
            String c = classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe;
            i = new Image("https://images.habbo.com/dcr/hof_furni/" + d.revision + "/" + c + "_icon.png",
                    26, 26, true, true, true);           // en fond : jamais de blocage
            vignettes.put(classe, i);
            return i;
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------- edition

    /** Le mobi clique dans le jeu devient celui qu'on modifie. */
    private void depuisLeJeu() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null || m.typeId < 0 || m.typeId == editType) return;
        Ecarts.Mobi x = Ecarts.pour(m.typeId, m.position, m.nom);
        editer(m.typeId, x.nom, x.droite.valeur, x.haut.valeur);
    }

    private void editer(int type, String nom, int droite, int haut) {
        if (type < 0) return;
        editType = type; editNomTexte = nom;
        Ecarts.Mobi x = Ecarts.pour(type, null, nom);
        editNom.setText(nom + "\n" + (x.enregistre() ? "Écart enregistré par toi."
                : x.droite.origine == Origine.DEFAUT || x.haut.origine == Origine.DEFAUT
                ? "Écart livré avec le programme." : "Pas encore d'écart retenu."));
        eDroite.getValueFactory().setValue(droite > 0 ? droite : x.droite.valeur);
        eHaut.getValueFactory().setValue(haut > 0 ? haut : x.haut.valeur);
        edition.setDisable(false);
    }

    private void enregistrerEdition() {
        if (editType < 0) { etat.setText("Clique d'abord un mobi mural dans le jeu."); return; }
        Generateur.prendre(eDroite); Generateur.prendre(eHaut);
        Ecarts.enregistrer(editType, eDroite.getValue(), eHaut.getValue());
        int t = editType;
        editType = -1;                                 // pour que editer() relise l'origine
        editer(t, editNomTexte, eDroite.getValue(), eHaut.getValue());
        etat.setText("Écart de « " + editNomTexte + " » enregistré : " + enClair(
                new Ecart(null, t, null, eDroite.getValue(), eHaut.getValue())) + ".");
    }

    // -------------------------------------------------------- export

    private void exporter() {
        List<Ecart> l = new ArrayList<>();
        for (Ligne x : Ecarts.liste()) l.add(x.effectif);
        if (l.isEmpty()) { etat.setText("Aucun écart à exporter pour l'instant."); return; }
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
            etat.setText(relus + " écart(s) exporté(s) dans " + f.getName() + "."
                    + (sansClasse > 0 ? " " + sansClasse + " sans nom technique : ouvre une salle pour les compléter, puis réexporte." : ""));
        } catch (Throwable t) {
            etat.setText("Export impossible : " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

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

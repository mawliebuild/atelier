package atelier;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Menu « Galerie » : des photos (ses apparts, ceux des autres...) a garder
 * sous la main pendant qu'on construit. On les ajoute (bouton, glisser-deposer,
 * coller une image copiee) ; elles sont COPIEES dans ~/Pictures/Atelier/Galerie,
 * et rangees dans des dossiers (de vrais dossiers sur le disque, sur plusieurs
 * niveaux). Chaque dossier a son ordre.txt et son tags.properties.
 * Un clic ouvre la photo dans une visionneuse flottante qui reste devant le
 * jeu : molette ou boutons pour zoomer (autour du curseur), glisser pour se
 * deplacer, double-clic pour basculer entre « ajuster » et 100 %, et une
 * transparence pour la poser par-dessus le jeu.
 */
public class OngletGalerie {

    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    /** Largeur mini d'une carte : le nombre de colonnes suit la largeur de la fenetre. */
    private static final double CARTE_MIN = 150;
    /** Ecart regulier entre les cartes (en ligne et en colonne). */
    private static final double ECART = 10;
    /** Fichier de l'ordre choisi (glisser-deposer), un nom par ligne : un par dossier. */
    private static final String ORDRE = "ordre.txt";
    /** Tags de chaque photo du dossier : « nom du fichier = Noël, Loft ». Un par dossier. */
    private static final String TAGS = "tags.properties";
    /** Fichiers de rangement : un dossier qui n'a plus qu'eux est vide. */
    private static final Set<String> RANGEMENT = Set.of(ORDRE, TAGS, ORDRE + ".tmp", ".ds_store", "thumbs.db", "desktop.ini");
    /** Profondeur maxi parcourue (garde-fou contre les liens qui bouclent). */
    private static final int PROFONDEUR = 16;
    /** Contenu glisse d'une carte photo : « galerie:<chemin> ». */
    private static final String GLISSE = "galerie:";
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String PREF_DOSSIER = "galerie.dossier";
    /** Taille des cartes retenue : grandes (une par rangee, toute la largeur) ou normales. */
    private static final String PREF_GRANDES = "galerie.grandesCartes";
    /** Pictogramme « Grandes cartes » : une seule grande carte. */
    private static final String ICONE_GRANDES = "M4 4h16v12H4z M4 20h16";

    private final String css;
    /** La grille : des rangees de cartes de meme largeur, refaites quand la largeur change. */
    private final VBox grille = new VBox(ECART);
    private final TextField recherche = new TextField();
    private final Label compteur = new Label();
    /** Tags existants qui contiennent le mot tape : un clic les met dans la recherche. */
    private final FlowPane suggestions = new FlowPane(4, 4);
    /** Fil d'Ariane : « Galerie › Apparts › Noël », chaque partie cliquable. */
    private final FlowPane fil = new FlowPane(2, 2);
    private ScrollPane defilement;
    /** Dossier affiche (retenu dans les preferences). */
    private File courant;
    /** Photos du dossier courant puis de ses sous-dossiers (dossier par dossier, chacun dans son ordre). */
    private final LinkedHashMap<File, List<File>> arbre = new LinkedHashMap<>();
    /** Tags de chaque photo (pour filtrer sans relire le disque). */
    private final Map<File, List<String>> tagsPhotos = new HashMap<>();
    /** Tags normalises (minuscules sans accents) : la recherche ne renormalise rien a chaque touche. */
    private final Map<File, List<String>> tagsNormalises = new HashMap<>();
    /** Tous les tags du dossier courant et de ses sous-dossiers (suggestions). */
    private final Set<String> tagsConnus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    /** Cartes des photos, faites seulement pour les rangees proches de l'ecran. */
    private final Map<File, VBox> cartes = new HashMap<>();
    private final List<VBox> cartesDossiers = new ArrayList<>();
    /** Vignettes : sur le disque (dossier de l'Atelier) et les dernieres en memoire. */
    private final VignettesCache vignettes = new VignettesCache();
    /** Ce que montre la grille, dans l'ordre : cartes de dossiers (VBox) puis photos (File). */
    private final List<Object> elements = new ArrayList<>();
    /** Les rangees de la grille : remplies seulement pres de l'ecran, sinon un espace reserve de leur hauteur. */
    private final List<Rangee> rangees = new ArrayList<>();
    /** Recherche : on attend la fin de la frappe avant de filtrer. */
    private final javafx.animation.PauseTransition delaiRecherche = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
    private boolean majPrevue = false;
    /** Hauteur d'une carte photo en plus de son cadre (marges, pastilles), mesuree sur une rangee affichee. */
    private double supplement = 46;
    private int colonnes = 0;
    private double largeurCarte = 0;
    /** Mode « Grandes cartes » : une carte par rangee, sur toute la largeur, photo entiere. */
    private boolean grandes = PREFS.getBoolean(PREF_GRANDES, false);
    private Button boutonTaille;
    /** Comme supplement, pour les grandes cartes (les pastilles y passent moins a la ligne). */
    private double supplementGrand = 40;
    /** Largeur / hauteur de chaque photo, des que sa vignette est connue (hauteur des grandes cartes). */
    private final Map<File, Double> ratios = new HashMap<>();
    /** mettreAJour en cours ; restauration de la position en cours ou prevue. */
    private boolean dansMaj, enRestauration, restaurationPrevue;
    private double[] ancrePrevue;

    /** Une rangee de la grille : les elements [debut, fin[. */
    private static final class Rangee {
        final HBox noeud = new HBox(ECART);
        final int debut, fin;
        /** Une carte de dossier, ou une photo d'un sous-dossier (avec son lieu) : plus haute. */
        boolean dossiers, lieu;
        boolean remplie;
        double hauteur;
        Rangee(int debut, int fin) { this.debut = debut; this.fin = fin; noeud.setFillHeight(true); }
    }

    /** Messages : dans le jeu (message du personnage), pas dans la fenetre. */
    private static void dire(String m) { InfoJeu.dire(Ui.majuscule(m)); }
    private static void succes(String m) { Journal.succes(Ui.accorder(Ui.majuscule(m))); }
    private static void erreur(String m) { Journal.erreur(Ui.accorder(Ui.majuscule(m))); }

    public OngletGalerie(String css) { this.css = css; instance = this; courant = dossierRetenu(); }

    private static volatile OngletGalerie instance;

    /**
     * Un fichier libre a la RACINE de la galerie pour ce nom (« nom (2).png »...) :
     * pour y ajouter une image d'ailleurs (photo de l'appart). La racine plutot
     * que le dossier courant : on ajoute depuis une autre fenetre, sans voir
     * la galerie, et on sait ainsi toujours ou retrouver la photo.
     */
    static File fichierLibre(String nom) {
        nom = nom.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        return libre(dossier(), nom.isEmpty() ? "Photo.png" : nom);
    }

    /** Relit la galerie (apres un ajout fait d'ailleurs, ex. la photo de l'appart). */
    static void actualiser() {
        OngletGalerie g = instance;
        if (g != null) Platform.runLater(g::rafraichir);
    }

    /** La racine de la galerie. */
    static File dossier() {
        File d = new File(Capture.dossierSortie(), "Galerie");
        if (!d.isDirectory() && d.mkdirs()) Capture.rendre(d);
        return d;
    }

    public Tab construire() {
        Button ajouter = Ui.bouton(Icones.PLUS, "Ajouter des photos…");
        ajouter.getStyleClass().add("primaire");
        ajouter.setOnAction(e -> choisir());
        Button coller = Ui.bouton(Icones.COLLER, "Coller une image");
        coller.setOnAction(e -> collerPressePapier());
        Button nouveau = Ui.bouton(Icones.DOSSIER_NOUVEAU, "Nouveau dossier…");
        nouveau.setOnAction(e -> nouveauDossier());
        boutonTaille = Ui.boutonIcone(ICONE_GRANDES, "Grandes cartes");
        boutonTaille.setOnAction(e -> basculerTaille());
        majBoutonTaille();

        VBox v = new VBox(10,
                Ui.bloc("Photos", Ui.boutons(ajouter, coller, nouveau, boutonTaille),
                        Ui.aide("Garde ici des captures de tes apparts ou d'autres apparts. Clique une photo pour "
                                + "l'ouvrir à côté du jeu : molette pour zoomer, glisser pour se déplacer, "
                                + "double-clic pour l'ajuster. Tu peux aussi glisser des images ici, "
                                + "ou glisser une photo sur une autre pour changer l'ordre. "
                                + "Range les photos dans des dossiers : glisse une photo sur un dossier, "
                                + "ou sur un nom du chemin en haut pour la remonter. "
                                + "« + Tag » range une photo (Noël, Loft, Villa…) ; la recherche trouve "
                                + "les photos dont un tag contient le texte tapé, dans ce dossier et ses sous-dossiers.")),
                fil, barreRecherche(), suggestions, grille);
        v.setPadding(new Insets(12, 14, 14, 14));
        v.setFillWidth(true);
        suggestions.setVisible(false);
        suggestions.setManaged(false);
        fil.setAlignment(Pos.CENTER_LEFT);
        fil.setRowValignment(javafx.geometry.VPos.CENTER);

        grille.setFillWidth(true);
        grille.widthProperty().addListener((o, a, b) -> disposer());

        // glisser-deposer d'images depuis le Finder / l'Explorateur : dans le dossier courant
        v.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        v.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) { List<File> l = new ArrayList<>(e.getDragboard().getFiles()); Platform.runLater(() -> importer(l, ici())); }
            e.setDropCompleted(ok);
            e.consume();
        });

        rafraichir();
        Ui.rienDeCoupe(v);
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        defilement = sp;
        // chargement paresseux : les rangees proches de l'ecran se remplissent, les lointaines se vident
        sp.vvalueProperty().addListener((o, a, b) -> planifier());
        sp.viewportBoundsProperty().addListener((o, a, b) -> {
            // grandes cartes : leur hauteur maxi suit la hauteur de la vue
            if (grandes && Math.abs(a.getHeight() - b.getHeight()) > 0.5) Platform.runLater(this::redimensionner);
            planifier();
        });
        grille.heightProperty().addListener((o, a, b) -> planifier());
        // tout defilement d'un conteneur parent deplace la grille : on suit aussi
        grille.localToSceneTransformProperty().addListener((o, a, b) -> planifier());
        // jamais de defilement en largeur (pave tactile compris)
        sp.hvalueProperty().addListener((o, a, b) -> { if (b.doubleValue() != sp.getHmin()) sp.setHvalue(sp.getHmin()); });
        // de temps en temps : les vignettes des photos disparues partent
        vignettes.menage(() -> { List<File> l = new ArrayList<>(); toutesLesPhotos(dossier(), l, 0); return l; });
        Tab t = new Tab("Galerie", sp);
        t.setClosable(false);
        return t;
    }

    /** Champ de recherche (loupe, croix pour effacer) et le compteur de photos. */
    private Node barreRecherche() {
        recherche.setPromptText("Chercher un tag…");
        recherche.setStyle("-fx-padding: 5 28 5 28;");
        recherche.setMaxWidth(Double.MAX_VALUE);
        Node loupe = Ui.pictogramme(Icones.LOUPE);
        loupe.setMouseTransparent(true);
        loupe.setOpacity(0.7);
        Button effacer = Ui.boutonIcone(Icones.VIDER, "Effacer la recherche");
        effacer.setStyle("-fx-background-color: transparent; -fx-padding: 2 4 2 4; -fx-cursor: hand;");
        effacer.setFocusTraversable(false);
        effacer.visibleProperty().bind(recherche.textProperty().isNotEmpty());
        effacer.setOnAction(e -> { recherche.clear(); recherche.requestFocus(); });
        StackPane champ = new StackPane(recherche, loupe, effacer);
        StackPane.setAlignment(loupe, Pos.CENTER_LEFT);
        StackPane.setMargin(loupe, new Insets(0, 0, 0, 8));
        StackPane.setAlignment(effacer, Pos.CENTER_RIGHT);
        StackPane.setMargin(effacer, new Insets(0, 3, 0, 0));
        champ.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(champ, Priority.ALWAYS);
        // suggestions tout de suite ; la grille apres une courte pause de frappe (vide : tout de suite)
        delaiRecherche.setOnFinished(e -> filtrer());
        recherche.textProperty().addListener((o, a, b) -> {
            majSuggestions();
            if (b == null || b.isEmpty()) filtrer(); else delaiRecherche.playFromStart();
        });
        recherche.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !recherche.getText().isEmpty()) { recherche.clear(); e.consume(); }
        });
        compteur.setStyle("-fx-opacity: 0.65;");
        compteur.setMinWidth(Region.USE_PREF_SIZE);
        HBox ligne = new HBox(10, champ, compteur);
        ligne.setAlignment(Pos.CENTER_LEFT);
        return ligne;
    }

    // ------------------------------------------------------------ dossiers

    /** Le dossier retenu la derniere fois (s'il existe encore), sinon la racine. */
    private static File dossierRetenu() {
        File racine = dossier();
        String rel = PREFS.get(PREF_DOSSIER, "");
        if (rel.isEmpty()) return racine;
        File d = racine;
        for (String p : rel.split("/")) if (!p.isEmpty() && !p.equals(".") && !p.equals("..")) d = new File(d, p);
        return d.isDirectory() && dans(d, racine) ? d : racine;
    }

    /** Retient le dossier courant (chemin depuis la racine, avec des « / »). */
    private void retenir() {
        StringBuilder sb = new StringBuilder();
        List<File> parts = chemin(courant);
        for (int i = 1; i < parts.size(); i++) sb.append(i > 1 ? "/" : "").append(parts.get(i).getName());
        try { PREFS.put(PREF_DOSSIER, sb.toString()); PREFS.flush(); } catch (Throwable ignored) { }
    }

    /** f est-il la racine ou dedans (chemins canoniques : pas de « .. » ni de lien qui sort) ? */
    private static boolean dans(File f, File racine) {
        try {
            String r = racine.getCanonicalPath(), c = f.getCanonicalPath();
            return c.equals(r) || c.startsWith(r + File.separator);
        } catch (Exception e) { return false; }
    }

    /** d est-il s ou un de ses sous-dossiers ? */
    private static boolean sousOuEgal(File d, File s) {
        return d.equals(s) || d.getPath().startsWith(s.getPath() + File.separator);
    }

    /** Les dossiers de la racine jusqu'a d (racine comprise). */
    private static List<File> chemin(File d) {
        File racine = dossier();
        List<File> l = new ArrayList<>();
        for (File x = d; x != null; x = x.getParentFile()) {
            l.add(0, x);
            if (x.equals(racine)) return l;
        }
        return new ArrayList<>(List.of(racine));
    }

    /** « Apparts › Noël » : le chemin de d apres « depuis » (depuis null : depuis « Galerie »). */
    private static String cheminLisible(File depuis, File d) {
        List<File> parts = chemin(d);
        StringBuilder sb = new StringBuilder();
        boolean apres = depuis == null;
        for (int i = 0; i < parts.size(); i++) {
            File x = parts.get(i);
            if (!apres) { if (x.equals(depuis)) apres = true; continue; }
            if (sb.length() > 0) sb.append(" › ");
            sb.append(i == 0 ? "Galerie" : x.getName());
        }
        return sb.toString();
    }

    private static String nomDe(File d) { return d.equals(dossier()) ? "Galerie" : d.getName(); }

    /** « dans « Noël » » pour un message, rien a la racine. */
    private static String ou(File d) { return d.equals(dossier()) ? "" : " dans « " + d.getName() + " »"; }

    /** Le dossier courant, s'il existe encore (sinon la racine). */
    private File ici() {
        if (courant == null || !courant.isDirectory()) courant = dossier();
        return courant;
    }

    /** Les sous-dossiers visibles de d, par ordre alphabetique. */
    static List<File> sousDossiers(File d) {
        File[] l = d.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<File> r = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        java.text.Collator c = java.text.Collator.getInstance(Locale.FRENCH);
        c.setStrength(java.text.Collator.SECONDARY);
        r.sort((a, b) -> c.compare(a.getName(), b.getName()));
        return r;
    }

    /** Les photos de d : l'ordre choisi d'abord ; les nouvelles (pas encore rangees) en tete, les plus recentes d'abord. */
    static List<File> photosDe(File d) {
        File[] l = d.listFiles(f -> f.isFile() && !f.isHidden() && EXTENSIONS.contains(extension(f)));
        List<File> photos = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        List<String> ordre = lireOrdre(d);
        // rang et date lus une seule fois par photo (milliers de photos : pas de indexOf ni de lecture disque a chaque comparaison)
        Map<String, Integer> rang = new HashMap<>();
        for (int i = 0; i < ordre.size(); i++) rang.putIfAbsent(ordre.get(i), i);
        Map<File, Long> date = new HashMap<>();
        for (File f : photos) date.put(f, f.lastModified());
        photos.sort(Comparator.comparingInt((File f) -> rang.getOrDefault(f.getName(), -1))
                .thenComparing(Comparator.comparingLong((File f) -> date.get(f)).reversed()));
        return photos;
    }

    /** Toutes les photos sous d (pour le menage des vignettes), sans lire ordre ni tags. */
    private static void toutesLesPhotos(File d, List<File> l, int prof) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) { if (prof < PROFONDEUR) toutesLesPhotos(f, l, prof + 1); }
            else if (EXTENSIONS.contains(extension(f))) l.add(f);
        }
    }

    private void naviguer(File d) {
        if (d == null || !d.isDirectory() || !dans(d, dossier())) d = dossier();
        courant = d;
        retenir();
        if (!recherche.getText().isEmpty()) recherche.clear();
        rafraichir();
        if (defilement != null) defilement.setVvalue(0);
    }

    /** Nom de dossier propre : sans caracteres interdits, sans point au debut ni a la fin, avec une majuscule. */
    static String nomDeDossier(String brut) {
        String n = brut == null ? "" : brut.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        n = n.replaceFirst("^[.\\s]+", "").replaceFirst("[.\\s]+$", "");
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return Ui.majuscule(n);
    }

    private javafx.stage.Window fenetre() { return grille.getScene() == null ? null : grille.getScene().getWindow(); }

    /**
     * Boite de dialogue DEVANT la fenetre de l'Atelier (toujours au premier
     * plan) : rattachee a elle, et elle-meme au premier plan. Sans ca, elle
     * s'ouvrait derriere.
     */
    private <D extends Dialog<?>> D devant(D d) {
        javafx.stage.Window w = fenetre();
        if (w != null && d.getOwner() == null) d.initOwner(w);
        ((Stage) d.getDialogPane().getScene().getWindow()).setAlwaysOnTop(true);
        return d;
    }

    private void nouveauDossier() {
        File ici = ici();
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Nouveau dossier");
        d.setHeaderText(null);
        d.setContentText(ici.equals(dossier()) ? "Nom du dossier :" : "Nom du dossier (dans « " + ici.getName() + " ») :");
        devant(d);
        d.showAndWait().ifPresent(brut -> {
            String nom = nomDeDossier(brut);
            if (nom.isEmpty()) { erreur("Donne un nom au dossier."); return; }
            File n = new File(ici, nom);
            if (n.exists()) { erreur("« " + nom + " » existe déjà ici."); return; }
            if (!n.mkdir()) { erreur("Impossible de créer le dossier « " + nom + " »."); return; }
            Capture.rendre(n);
            succes("Dossier « " + nom + " » créé" + ou(ici) + ".");
            rafraichir();
        });
    }

    private void renommerDossier(File d) {
        TextInputDialog dlg = new TextInputDialog(d.getName());
        dlg.setTitle("Renommer le dossier");
        dlg.setHeaderText(null);
        dlg.setContentText("Nouveau nom :");
        devant(dlg);
        dlg.showAndWait().ifPresent(brut -> {
            String nom = nomDeDossier(brut);
            if (nom.isEmpty()) { erreur("Donne un nom au dossier."); return; }
            if (nom.equals(d.getName())) return;
            File n = new File(d.getParentFile(), nom);
            // « noel » -> « Noel » : meme dossier sur un disque qui ignore la casse
            if (n.exists() && !nom.equalsIgnoreCase(d.getName())) { erreur("« " + nom + " » existe déjà ici."); return; }
            if (!d.renameTo(n)) { erreur("Impossible de renommer le dossier « " + d.getName() + " »."); return; }
            if (courant != null && sousOuEgal(courant, d)) {
                courant = new File(n, courant.getPath().substring(d.getPath().length()));
                retenir();
            }
            succes("Dossier renommé en « " + nom + " ».");
            rafraichir();
        });
    }

    /**
     * Supprime un dossier, SEULEMENT s'il est vide (sous-dossiers vides
     * compris) : jamais de photo perdue d'un clic. S'il contient des photos,
     * on le dit et on ne touche a rien.
     */
    private void supprimerDossier(File d) {
        int[] c = new int[3];   // photos, autres fichiers, sous-dossiers
        inventaire(d, c, 0);
        if (c[0] > 0) {
            erreur("Le dossier « " + d.getName() + " » n'est pas vide : " + c[0] + " photo(s) dedans. "
                    + "Seul un dossier vide se supprime : déplace ou supprime d'abord ses photos.");
            return;
        }
        if (c[1] > 0) {
            erreur("Le dossier « " + d.getName() + " » contient " + c[1] + " autre(s) fichier(s) : il n'est pas supprimé.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer le dossier vide « " + d.getName() + " » ?"
                + (c[2] > 0 ? Ui.accorder(" Ses " + c[2] + " sous-dossier(s) vide(s) aussi.").replace("Ses 1 ", "Son ") : ""),
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        devant(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        if (!effacer(d, 0)) { erreur("Impossible de supprimer entièrement le dossier « " + d.getName() + " »."); rafraichir(); return; }
        succes("Dossier « " + d.getName() + " » supprimé.");
        rafraichir();
    }

    /** Compte photos (cachees comprises), autres fichiers et sous-dossiers de d. */
    private static void inventaire(File d, int[] c, int prof) {
        File[] l = d.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory()) {
                c[2]++;
                if (prof < PROFONDEUR) inventaire(f, c, prof + 1); else c[1]++;
            } else if (EXTENSIONS.contains(extension(f))) c[0]++;
            else if (!RANGEMENT.contains(f.getName().toLowerCase(Locale.ROOT)) && !f.getName().startsWith("._")) c[1]++;
        }
    }

    /** Efface d (deja verifie vide de photos) : fichiers de rangement, sous-dossiers vides, puis lui. */
    private static boolean effacer(File d, int prof) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) {
            if (f.isDirectory()) { if (prof >= PROFONDEUR || !effacer(f, prof + 1)) return false; }
            else if (EXTENSIONS.contains(extension(f))) return false;      // ceinture et bretelles
            else if (!f.delete()) return false;
        }
        return d.delete();
    }

    /**
     * Range la photo dans le dossier dir. Ses tags la suivent (tags.properties
     * du nouveau dossier), et elle quitte l'ordre de l'ancien.
     */
    private void deplacerVers(File photo, File dir) {
        File de = photo.getParentFile();
        if (dir == null || de == null || dir.equals(de) || !photo.isFile() || !dir.isDirectory() || !dans(dir, dossier())) return;
        File dest = libre(dir, photo.getName());
        try {
            Files.move(photo.toPath(), dest.toPath());
        } catch (Throwable t) {
            Journal.erreur("La photo n'a pas pu être déplacée", t);
            return;
        }
        Capture.rendre(dest);
        Properties pDe = lireTags(de);
        String t = pDe.getProperty(photo.getName());
        if (t != null) {
            // d'abord dans le nouveau dossier, puis retire de l'ancien : jamais perdus
            Properties pA = lireTags(dir);
            pA.setProperty(dest.getName(), t);
            ecrireTags(dir, pA);
            pDe.remove(photo.getName());
            ecrireTags(de, pDe);
        }
        List<String> o = lireOrdre(de);
        if (o.remove(photo.getName())) ecrireOrdreNoms(de, o);
        List<String> oA = lireOrdre(dir);
        if (!oA.isEmpty()) { oA.remove(dest.getName()); oA.add(0, dest.getName()); ecrireOrdreNoms(dir, oA); }
        succes("Photo rangée dans « " + nomDe(dir) + " ».");
        rafraichir();
    }

    /** « Deplacer vers… » : choisir un dossier dans l'arbre de la galerie. */
    private void choisirDossier(File photo) {
        File racine = dossier();
        TreeItem<File> rac = branche(racine, 0);
        if (rac.getChildren().isEmpty() && racine.equals(photo.getParentFile())) {
            erreur("Aucun dossier où la ranger : crée-en un avec « Nouveau dossier ».");
            return;
        }
        TreeView<File> tv = new TreeView<>(rac);
        tv.setCellFactory(x -> new TreeCell<>() {
            @Override protected void updateItem(File d, boolean vide) {
                super.updateItem(d, vide);
                if (vide || d == null) { setText(null); setGraphic(null); setOpacity(1); return; }
                setText(d.equals(racine) ? "Galerie" : d.getName());
                setGraphic(Icones.petite(d.equals(racine) ? Icones.GALERIE : Icones.DOSSIER, 15, false));
                // son dossier actuel : grise
                setOpacity(d.equals(photo.getParentFile()) ? 0.5 : 1);
            }
        });
        // tout deplie, et le dossier actuel de la photo selectionne
        TreeItem<File> actuel = deplier(rac, photo.getParentFile());
        if (actuel != null) tv.getSelectionModel().select(actuel);
        tv.setPrefSize(340, 300);

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Déplacer vers…");
        dlg.setHeaderText(null);
        ButtonType deplacer = new ButtonType("Déplacer", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(deplacer, ButtonType.CANCEL);
        dlg.getDialogPane().setContent(new VBox(8, new Label("Choisis le dossier où ranger cette photo :"), tv));
        Node ok = dlg.getDialogPane().lookupButton(deplacer);
        ok.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            TreeItem<File> s = tv.getSelectionModel().getSelectedItem();
            return s == null || s.getValue().equals(photo.getParentFile());
        }, tv.getSelectionModel().selectedItemProperty()));
        // double-clic sur un dossier : deplace tout de suite
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !ok.isDisabled()) { dlg.setResult(deplacer); dlg.close(); }
        });
        devant(dlg);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != deplacer) return;
        TreeItem<File> s = tv.getSelectionModel().getSelectedItem();
        if (s != null) deplacerVers(photo, s.getValue());
    }

    private static TreeItem<File> branche(File d, int prof) {
        TreeItem<File> t = new TreeItem<>(d);
        if (prof < PROFONDEUR) for (File s : sousDossiers(d)) t.getChildren().add(branche(s, prof + 1));
        return t;
    }

    /** Deplie tout l'arbre ; rend l'element du dossier cherche. */
    private static TreeItem<File> deplier(TreeItem<File> t, File cherche) {
        t.setExpanded(true);
        TreeItem<File> r = t.getValue().equals(cherche) ? t : null;
        for (TreeItem<File> c : t.getChildren()) { TreeItem<File> x = deplier(c, cherche); if (r == null) r = x; }
        return r;
    }

    /** La photo glissee depuis une carte (null si ce n'en est pas une). */
    private static File photoGlissee(DragEvent e) {
        String t = e.getDragboard().hasString() ? e.getDragboard().getString() : null;
        if (t == null || !t.startsWith(GLISSE)) return null;
        File f = new File(t.substring(GLISSE.length()));
        return f.isFile() && f.getAbsolutePath().startsWith(dossier().getAbsolutePath() + File.separator) ? f : null;
    }

    /** Fil d'Ariane : chaque partie ouvre son dossier, et recoit une photo glissee. */
    private void construireFil() {
        fil.getChildren().clear();
        List<File> parts = chemin(ici());
        for (int i = 0; i < parts.size(); i++) {
            File d = parts.get(i);
            boolean dernier = i == parts.size() - 1;
            if (i > 0) {
                Label s = new Label("›");
                s.setStyle("-fx-opacity: 0.55;");
                fil.getChildren().add(s);
            }
            Button b = new Button(i == 0 ? "Galerie" : d.getName());
            if (i == 0) b.setGraphic(Icones.petite(Icones.GALERIE, 15, false));
            b.setGraphicTextGap(5);
            b.setFocusTraversable(false);
            b.setMinWidth(Region.USE_PREF_SIZE);
            String base = "-fx-background-color: transparent; -fx-background-radius: 4; -fx-padding: 2 5 2 5; -fx-cursor: hand; "
                    + (dernier ? "-fx-font-weight: bold; -fx-text-fill: #3B382F;" : "-fx-text-fill: #2F6F92;");
            b.setStyle(base);
            if (!dernier) b.setTooltip(Ui.bulle("Ouvrir « " + nomDe(d) + " ». Glisse une photo ici pour l'y ranger."));
            b.hoverProperty().addListener((o, x, h) -> b.setStyle(base + (h && !dernier ? "-fx-underline: true;" : "")));
            b.setOnAction(e -> naviguer(d));
            b.setOnDragOver(e -> {
                File p = photoGlissee(e);
                if (p != null && !d.equals(p.getParentFile())) { e.acceptTransferModes(TransferMode.MOVE); e.consume(); }
            });
            b.setOnDragEntered(e -> {
                File p = photoGlissee(e);
                if (p != null && !d.equals(p.getParentFile())) b.setStyle(base + "-fx-background-color: #3E86AC; -fx-text-fill: white;");
            });
            b.setOnDragExited(e -> b.setStyle(base));
            b.setOnDragDropped(e -> {
                File p = photoGlissee(e);
                if (p == null) return;
                Platform.runLater(() -> deplacerVers(p, d));
                e.setDropCompleted(true);
                e.consume();
            });
            fil.getChildren().add(b);
        }
    }

    // ------------------------------------------------------------ recherche

    /** Minuscules sans accents : « Noël » -> « noel ». */
    static String normaliser(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /** Les mots de la recherche (espaces ou virgules), normalises. */
    static List<String> mots(String requete) {
        List<String> l = new ArrayList<>();
        for (String m : normaliser(requete).split("[\\s,]+")) if (!m.isEmpty()) l.add(m);
        return l;
    }

    /**
     * La photo correspond-elle ? Chaque mot de la recherche doit se trouver
     * DANS au moins un de ses tags (« hallo villa » : Halloween + Villa).
     * Recherche vide : toutes les photos.
     */
    static boolean correspond(List<String> tags, String requete) {
        List<String> ms = mots(requete);
        if (ms.isEmpty()) return true;
        List<String> ts = new ArrayList<>();
        for (String t : tags) ts.add(normaliser(t));
        for (String m : ms) if (ts.stream().noneMatch(t -> t.contains(m))) return false;
        return true;
    }

    /** Tags existants qui contiennent le dernier mot tape (sans ceux deja tapes en entier). */
    static List<String> suggestionsPour(Collection<String> connus, String requete) {
        List<String> r = new ArrayList<>();
        if (requete == null || requete.isEmpty() || Character.isWhitespace(requete.charAt(requete.length() - 1))) return r;
        List<String> ms = mots(requete);
        if (ms.isEmpty()) return r;
        String dernier = ms.get(ms.size() - 1);
        for (String t : connus) {
            String n = normaliser(t);
            if (n.contains(dernier) && !ms.contains(n)) r.add(t);
        }
        // ceux qui commencent par le mot d'abord
        r.sort(Comparator.comparing((String t) -> !normaliser(t).startsWith(dernier)).thenComparing(String.CASE_INSENSITIVE_ORDER));
        return r.size() > 8 ? new ArrayList<>(r.subList(0, 8)) : r;
    }

    /** Remplace le dernier mot de la recherche par le tag choisi. */
    private void choisirSuggestion(String tag) {
        String q = recherche.getText();
        int i = q.length();
        while (i > 0 && !Character.isWhitespace(q.charAt(i - 1)) && q.charAt(i - 1) != ',') i--;
        recherche.setText(q.substring(0, i) + tag + " ");
        recherche.requestFocus();
        recherche.positionCaret(recherche.getText().length());
    }

    // ------------------------------------------------------------ ajout

    private void choisir() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Ajouter des photos à la galerie");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        // Bureau (OneDrive compris sous Windows), sinon le premier dossier habituel
        for (File d : Dossiers.habituels()) if (d.getName().matches("(?i)desktop|bureau")) { fc.setInitialDirectory(d); break; }
        if (fc.getInitialDirectory() == null && !Dossiers.habituels().isEmpty()) fc.setInitialDirectory(Dossiers.habituels().get(0));
        List<File> l = fc.showOpenMultipleDialog(fenetre());
        if (l != null) importer(l, ici());
    }

    /** Image ou fichiers copies (capture d'ecran copiee avec Cmd+Ctrl+Maj+4, par exemple). */
    private void collerPressePapier() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasFiles()) { importer(cb.getFiles(), ici()); return; }
        if (!cb.hasImage()) { dire("Rien à coller : copie d'abord une image."); return; }
        Image img = cb.getImage();
        File ici = ici();
        try {
            File f = libre(ici, "Photo collée.png");
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader pr = img.getPixelReader();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
            javax.imageio.ImageIO.write(bi, "png", f);
            Capture.rendre(f);
            succes("Image ajoutée" + ou(ici) + ".");
            rafraichir();
        } catch (Throwable t) {
            Journal.erreur("Image impossible à enregistrer", t);
        }
    }

    /** Copie les images dans le dossier dir de la galerie. */
    private void importer(List<File> fichiers, File dir) {
        if (dir == null || !dir.isDirectory()) dir = dossier();
        int n = 0, ignores = 0, rates = 0;
        String raison = null;
        for (File f : fichiers) {
            if (!f.isFile() || !EXTENSIONS.contains(extension(f))) { ignores++; continue; }
            try {
                File dest = libre(dir, f.getName());
                Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                Capture.rendre(dest);
                n++;
            } catch (Throwable t) {
                rates++;
                if (raison == null) raison = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                System.err.println("[Atelier] galerie : " + f + " : " + t);
            }
        }
        String ign = ignores > 0 ? ignores + " ignorée(s) (pas une image PNG, JPG, GIF ou BMP)" : "";
        String rat = rates > 0 ? rates + " photo(s) n'ont pas pu être copiée(s) : " + raison : "";
        if (n > 0) {
            String m = n + " photo(s) ajoutée(s)" + ou(dir) + (ign.isEmpty() ? "" : ", " + ign) + ".";
            if (rates > 0) erreur(m + " " + Ui.majuscule(rat) + "."); else succes(m);
        } else if (rates > 0) {
            erreur("Aucune photo ajoutée. " + Ui.majuscule(rat) + (ign.isEmpty() ? "" : " ; " + ign) + ".");
        } else {
            erreur("Aucune photo ajoutée : pas une image PNG, JPG, GIF ou BMP.");
        }
        rafraichir();
    }

    /** Un nom de fichier libre dans le dossier d (« nom (2).png »...). */
    private static File libre(File d, String nom) {
        File f = new File(d, nom);
        String base = nom.contains(".") ? nom.substring(0, nom.lastIndexOf('.')) : nom;
        String ext = nom.contains(".") ? nom.substring(nom.lastIndexOf('.')) : "";
        for (int i = 2; f.exists(); i++) f = new File(d, base + " (" + i + ")" + ext);
        return f;
    }

    private static String extension(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    // ------------------------------------------------------------ vignettes

    /** Relit le dossier courant et ses sous-dossiers, puis filtre (les cartes se font a la demande). */
    private void rafraichir() {
        File racine = dossier();
        if (courant == null || !courant.isDirectory() || !dans(courant, racine)) { courant = racine; retenir(); }
        viderTout();
        arbre.clear();
        tagsPhotos.clear();
        tagsNormalises.clear();
        tagsConnus.clear();
        cartes.clear();
        cartesDossiers.clear();
        parcourir(courant, 0);
        for (List<String> t : tagsPhotos.values()) tagsConnus.addAll(t);
        for (File d : sousDossiers(courant)) cartesDossiers.add(carteDossier(d));
        construireFil();
        filtrer();
    }

    private void parcourir(File d, int prof) {
        Properties p = lireTags(d);
        List<File> l = photosDe(d);
        arbre.put(d, l);
        for (File f : l) {
            List<String> t = tagsDe(p, f);
            tagsPhotos.put(f, t);
            if (!t.isEmpty()) {
                List<String> n = new ArrayList<>(t.size());
                for (String x : t) n.add(normaliser(x));
                tagsNormalises.put(f, n);
            }
        }
        if (prof < PROFONDEUR) for (File s : sousDossiers(d)) parcourir(s, prof + 1);
    }

    private VBox carte(File f) {
        return cartes.computeIfAbsent(f, x -> vignette(x, tagsPhotos.getOrDefault(x, List.of())));
    }

    private boolean cherche() { return !mots(recherche.getText()).isEmpty(); }

    /** Comme correspond(), sur des tags deja normalises et des mots deja decoupes. */
    private static boolean correspondNormalise(List<String> tagsNorm, List<String> ms) {
        if (ms.isEmpty()) return true;
        for (String m : ms) {
            boolean ok = false;
            for (String t : tagsNorm) if (t.contains(m)) { ok = true; break; }
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Sans recherche : les dossiers puis les photos du dossier courant. Avec
     * une recherche : les photos du dossier courant ET de ses sous-dossiers
     * dont un tag contient chaque mot. Rien n'est relu sur le disque.
     */
    private void filtrer() {
        delaiRecherche.stop();
        List<String> ms = mots(recherche.getText());
        elements.clear();
        if (ms.isEmpty()) {
            elements.addAll(cartesDossiers);
            List<File> ici = arbre.getOrDefault(courant, List.of());
            elements.addAll(ici);
            int n = ici.size();
            compteur.setText(n + (n > 1 ? " photos" : " photo"));
            compteur.setVisible(n > 0);
        } else {
            int n = 0, k = 0;
            for (List<File> l : arbre.values()) for (File f : l) {
                n++;
                if (correspondNormalise(tagsNormalises.getOrDefault(f, List.of()), ms)) { elements.add(f); k++; }
            }
            compteur.setText(k + " / " + n + (n > 1 ? " photos" : " photo"));
            compteur.setVisible(n > 0);
        }
        majSuggestions();
        colonnes = 0;
        disposer();
    }

    /** Suggestions : les tags existants (ici et dessous) qui contiennent le mot en cours. */
    private void majSuggestions() {
        suggestions.getChildren().clear();
        for (String t : suggestionsPour(tagsConnus, recherche.getText())) {
            Button b = new Button(t);
            b.setFocusTraversable(false);
            b.setStyle(PASTILLE + "-fx-background-color: #E4EEF3; -fx-text-fill: #2F6F92; -fx-cursor: hand;");
            b.setOnAction(e -> choisirSuggestion(t));
            suggestions.getChildren().add(b);
        }
        boolean s = !suggestions.getChildren().isEmpty();
        suggestions.setVisible(s);
        suggestions.setManaged(s);
    }

    /**
     * Range les elements en rangees de meme largeur de carte : autant de
     * colonnes que la largeur en permet (cartes de CARTE_MIN px au moins).
     * Les rangees ne sont d'abord que des espaces reserves (la barre de
     * defilement reste juste) ; seules celles proches de l'ecran recoivent
     * leurs cartes (voir mettreAJour).
     */
    private void disposer() {
        double w = grille.getWidth();
        if (w <= 0) w = 520;
        int cols = grandes ? 1 : Math.max(2, (int) Math.floor((w + ECART) / (CARTE_MIN + ECART)));
        double lc = Math.floor((w - ECART * (cols - 1)) / cols);
        if (cols == colonnes && Math.abs(lc - largeurCarte) < 0.5 && !grille.getChildren().isEmpty()) return;
        if (cols == colonnes && !rangees.isEmpty()) {
            // meme nombre de colonnes : on ajuste les tailles sans rien refaire
            largeurCarte = lc;
            redimensionner();
            return;
        }
        colonnes = cols;
        largeurCarte = lc;
        viderTout();
        grille.getChildren().clear();
        if (elements.isEmpty()) {
            boolean racine = ici().equals(dossier());
            Label vide = Ui.discret(cherche() ? "Aucune photo avec ce tag, ni ici ni dans les sous-dossiers."
                    : racine ? "Aucune photo. Ajoute-en, colle une image ou crée un dossier."
                    : "Dossier vide. Ajoute des photos ici, ou glisse une photo sur son nom dans le chemin en haut.");
            vide.setPadding(new Insets(16, 4, 16, 4));
            grille.getChildren().add(vide);
            cartes.clear();
            return;
        }
        List<Node> noeuds = new ArrayList<>();
        for (int i = 0; i < elements.size(); i += cols) {
            Rangee r = new Rangee(i, Math.min(i + cols, elements.size()));
            for (int j = r.debut; j < r.fin; j++) {
                Object e = elements.get(j);
                if (e instanceof VBox) r.dossiers = true;
                else if (e instanceof File && !courant.equals(((File) e).getParentFile())) r.lieu = true;
            }
            reserver(r, estimation(r));
            rangees.add(r);
            noeuds.add(r.noeud);
        }
        grille.getChildren().setAll(noeuds);
        mettreAJour();
    }

    /** Hauteur prevue d'une rangee pas encore affichee. */
    private double estimation(Rangee r) {
        double cadre = grandes ? hauteurCadre(elements.get(r.debut)) : Math.round(Math.max(60, largeurCarte - 12) * 0.75);
        double h = cadre + (grandes ? supplementGrand : supplement) + (r.lieu ? 26 : 0);
        return r.dossiers ? Math.max(h, cadre + 52) : h;
    }

    /** Memes rangees, nouvelle taille de carte : les cartes affichees s'ajustent, les autres reservent leur place. */
    private void redimensionner() {
        if (rangees.isEmpty()) return;
        double[] a = grandes ? ancreActuelle() : null;
        for (Rangee r : rangees) {
            if (r.remplie) {
                for (Node n : r.noeud.getChildren()) {
                    dimensionner((VBox) n, largeurCarte);
                    if (grandes) chargerGrande(n);
                }
            } else reserver(r, estimation(r));
        }
        // grandes cartes : les hauteurs changent au-dessus de la vue, la photo du haut reste en haut
        if (a != null) restaurerPlusTard(a);
        planifier();
    }

    /**
     * Hauteur du cadre de la photo d'un element (photo ou carte de dossier).
     * Normal : 4:3. Grandes cartes : la photo entiere sur toute la largeur
     * (ratio de sa vignette, 4:3 tant qu'il n'est pas connu), au plus la
     * hauteur de la vue.
     */
    private double hauteurCadre(Object e) {
        double l = Math.max(60, largeurCarte - 12);
        if (!grandes) return Math.round(l * 0.75);
        File p = e instanceof File ? (File) e : e instanceof Node ? (File) ((Node) e).getProperties().get(PHOTO) : null;
        if (p == null) return Math.round(Math.min(l * 0.75, 180));
        double r = ratios.getOrDefault(p, 4.0 / 3);
        return Math.round(Math.min(hauteurMax(), (l - 6) / r + 6));
    }

    /** Hauteur maxi d'une grande carte : la photo entiere tient dans la vue. */
    private double hauteurMax() {
        double vue = defilement == null ? 0 : defilement.getViewportBounds().getHeight();
        return Math.max(240, (vue > 0 ? vue : 800) - 90);
    }

    /** Echelle de l'ecran (2 en Retina, 1,25 / 1,5 sous Windows...). */
    private double echelle() {
        try {
            javafx.stage.Window w = fenetre();
            return w == null || w.getOutputScaleX() <= 0 ? 1 : w.getOutputScaleX();
        } catch (Throwable t) { return 1; }
    }

    /** Grandes cartes : la photo lue a la taille de la carte (en pixels reels), pour rester nette. */
    private void chargerGrande(Node carte) {
        ImageView iv = (ImageView) carte.getProperties().get(VUE);
        File p = (File) carte.getProperties().get(PHOTO);
        if (iv == null || p == null) return;
        double k = echelle();
        double l = (Math.max(60, largeurCarte - 12) - 6) * k;
        if (l <= VignettesCache.LARGEUR + 10) return;     // la vignette suffit
        vignettes.chargerGrande(p, iv, (int) Math.ceil(l), (int) Math.ceil((hauteurMax() - 6) * k));
    }

    // ------------------------------------------------------------ taille des cartes

    private void majBoutonTaille() {
        if (boutonTaille == null) return;
        String t = grandes ? "Petites cartes" : "Grandes cartes";
        boutonTaille.setGraphic(Ui.pictogramme(grandes ? Icones.INVENTAIRE : ICONE_GRANDES));
        boutonTaille.setTooltip(Ui.bulle(t));
        boutonTaille.setAccessibleText(t);
    }

    /** Petites <-> grandes cartes, retenu ; la photo en haut de la vue y reste. */
    private void basculerTaille() {
        double[] a = ancreActuelle();
        if (a != null && a[0] >= 0) a[1] = 0;
        grandes = !grandes;
        try { PREFS.putBoolean(PREF_GRANDES, grandes); PREFS.flush(); } catch (Throwable ignored) { }
        majBoutonTaille();
        colonnes = 0;
        disposer();
        restaurer(a);
    }

    /**
     * Ce qui est en haut de la vue : {indice du premier element de la
     * rangee, decalage en px dans la rangee}, ou {-1, position en px} si le
     * haut de la grille est visible. null sans defilement.
     */
    private double[] ancreActuelle() {
        if (defilement == null || defilement.getContent() == null) return null;
        double contenu = defilement.getContent().getLayoutBounds().getHeight();
        double vue = defilement.getViewportBounds().getHeight();
        double haut = Math.max(0, contenu - vue) * defilement.getVvalue();
        double debut = haut - grille.getLayoutY();
        if (debut <= 0 || rangees.isEmpty()) return new double[]{-1, haut};
        for (Rangee r : rangees) {
            double y = r.noeud.getLayoutY();
            if (y + r.noeud.getHeight() + ECART > debut) return new double[]{r.debut, debut - y};
        }
        return new double[]{-1, haut};
    }

    /** Remet l'ancre en haut de la vue (quelques passes : les rangees remplies changent de hauteur). */
    private void restaurer(double[] a) {
        if (a == null || defilement == null || defilement.getContent() == null) return;
        enRestauration = true;
        try {
            for (int i = 0; i < 4; i++) {
                defilement.applyCss();
                defilement.layout();
                double y;
                if (a[0] < 0) y = a[1];
                else {
                    Rangee r = null;
                    for (Rangee x : rangees) if (a[0] >= x.debut && a[0] < x.fin) { r = x; break; }
                    if (r == null) return;
                    y = grille.getLayoutY() + r.noeud.getLayoutY() + a[1];
                }
                double contenu = defilement.getContent().getLayoutBounds().getHeight();
                double vue = defilement.getViewportBounds().getHeight();
                defilement.setVvalue(contenu > vue ? Math.max(0, Math.min(1, y / (contenu - vue))) : 0);
                if (!mettreAJour()) break;
            }
        } finally { enRestauration = false; }
    }

    /** Restauration au prochain tour (hors d'une mise en page) ; la premiere ancre demandee compte. */
    private void restaurerPlusTard(double[] a) {
        if (a == null || a[0] < 0 || enRestauration || restaurationPrevue) return;
        restaurationPrevue = true;
        ancrePrevue = a;
        Platform.runLater(() -> { restaurationPrevue = false; restaurer(ancrePrevue); ancrePrevue = null; });
    }

    /** La vignette d'une carte est arrivee : son ratio est connu ; une grande carte prend la bonne hauteur. */
    private void ratioConnu(VBox carte, Image img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
        File p = (File) carte.getProperties().get(PHOTO);
        if (p == null) return;
        double r = img.getWidth() / img.getHeight();
        Double ancien = ratios.put(p, r);
        if (!grandes || carte.getParent() == null || (ancien != null && Math.abs(ancien - r) / r < 0.01)) return;
        Region cadre = (Region) carte.getProperties().get(CADRE);
        double avant = cadre.getPrefHeight();
        double[] a = dansMaj || enRestauration ? null : ancreActuelle();
        dimensionner(carte, largeurCarte);
        if (Math.abs(cadre.getPrefHeight() - avant) < 0.5) return;
        Object i = carte.getProperties().get(INDEX);
        if (a != null && a[0] >= 0 && i instanceof Integer && (Integer) i < a[0]) restaurerPlusTard(a);
    }

    /** La rangee devient un espace reserve de hauteur h. */
    private static void reserver(Rangee r, double h) {
        r.hauteur = h;
        r.noeud.setMinHeight(h);
        r.noeud.setPrefHeight(h);
        r.noeud.setMaxHeight(h);
    }

    /** Une mise a jour des rangees au prochain tour (defilement, taille...). */
    private void planifier() {
        if (majPrevue) return;
        majPrevue = true;
        Platform.runLater(() -> { majPrevue = false; mettreAJour(); });
    }

    /**
     * Remplit les rangees proches de l'ecran (un ecran au-dessus et en
     * dessous), vide celles qui en sont loin (plus de trois ecrans) : peu de
     * cartes et peu d'images a la fois, quel que soit le nombre de photos.
     */
    private boolean mettreAJour() {
        if (rangees.isEmpty()) return false;
        // grandes cartes : une rangee remplie au-dessus de la vue change de hauteur, la vue suit
        double[] a = grandes && !enRestauration && !restaurationPrevue ? ancreActuelle() : null;
        boolean rempli = false, dessus = false;
        // Partie visible de la grille, en coordonnees de scene : juste quel que
        // soit le conteneur qui defile (la galerie ou la fenetre qui la porte).
        double vueH = 0, debut = 0, fin = 0;
        if (grille.getScene() != null) {
            double haut = 0, bas = grille.getScene().getHeight();
            for (javafx.scene.Parent p = grille.getParent(); p != null; p = p.getParent()) {
                if (!(p instanceof ScrollPane)) continue;
                javafx.geometry.Bounds b = p.localToScene(p.getBoundsInLocal());
                haut = Math.max(haut, b.getMinY());
                bas = Math.min(bas, b.getMaxY());
            }
            double g = grille.localToScene(0, 0).getY();
            vueH = Math.max(0, bas - haut);
            debut = haut - g;
            fin = bas - g;
        }
        if (vueH <= 0) { vueH = 800; debut = 0; fin = vueH; }
        double y = 0;
        double cadre = Math.round(Math.max(60, largeurCarte - 12) * 0.75);
        dansMaj = true;
        try {
            for (Rangee r : rangees) {
                if (r.remplie && r.noeud.getHeight() > 0 && r.noeud.getChildren().size() > 0) {
                    r.hauteur = r.noeud.getHeight();
                    if (!r.dossiers && !r.lieu) {
                        if (!grandes) supplement = Math.max(20, r.hauteur - cadre);
                        else {
                            Region c = (Region) r.noeud.getChildren().get(0).getProperties().get(CADRE);
                            if (c != null && c.getHeight() > 0) supplementGrand = Math.max(20, r.hauteur - c.getHeight());
                        }
                    }
                }
                double bas = y + r.hauteur;
                boolean proche = bas >= debut - vueH && y <= fin + vueH;
                boolean loin = bas < debut - 3 * vueH || y > fin + 3 * vueH;
                if (proche && !r.remplie) {
                    remplir(r);
                    rempli = true;
                    if (a != null && a[0] >= 0 && r.fin <= a[0]) dessus = true;
                }
                else if (loin && r.remplie) vider(r);
                y = bas + ECART;
            }
        } finally { dansMaj = false; }
        // les cartes qui ne sont plus dans une rangee sont oubliees (elles se refont a la demande)
        cartes.values().removeIf(c -> c.getParent() == null);
        if (dessus) restaurerPlusTard(a);
        return rempli;
    }

    private void remplir(Rangee r) {
        r.remplie = true;
        r.noeud.setMinHeight(Region.USE_COMPUTED_SIZE);
        r.noeud.setPrefHeight(Region.USE_COMPUTED_SIZE);
        r.noeud.setMaxHeight(Region.USE_COMPUTED_SIZE);
        List<Node> l = new ArrayList<>();
        for (int j = r.debut; j < r.fin; j++) {
            Object e = elements.get(j);
            VBox c = e instanceof File ? carte((File) e) : (VBox) e;
            c.getProperties().put(INDEX, j);
            dimensionner(c, largeurCarte);
            l.add(c);
        }
        r.noeud.getChildren().setAll(l);
        for (Node n : l) {
            ImageView iv = (ImageView) n.getProperties().get(VUE);
            File p = (File) n.getProperties().get(PHOTO);
            if (iv != null && p != null) {
                vignettes.charger(p, iv);
                if (grandes) chargerGrande(n);
            }
        }
    }

    /** La rangee rend ses cartes et leurs images ; elle garde sa hauteur (espace reserve). */
    private void vider(Rangee r) {
        double h = r.noeud.getHeight() > 0 ? r.noeud.getHeight() : r.hauteur;
        for (Node n : r.noeud.getChildren()) {
            ImageView iv = (ImageView) n.getProperties().get(VUE);
            if (iv != null) vignettes.relacher(iv);
        }
        r.noeud.getChildren().clear();
        r.remplie = false;
        reserver(r, h);
    }

    private void viderTout() {
        for (Rangee r : rangees) if (r.remplie) vider(r);
        rangees.clear();
    }

    private static Properties lireTags(File d) {
        Properties p = new Properties();
        File f = new File(d, TAGS);
        if (f.isFile()) try (java.io.Reader r = Files.newBufferedReader(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Throwable ignored) { }
        return p;
    }

    private static void ecrireTags(File d, Properties p) {
        File f = new File(d, TAGS);
        try (java.io.Writer w = Files.newBufferedWriter(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            p.store(w, "Tags de la galerie");
        } catch (Throwable t) { Journal.erreur("Les tags de la galerie n'ont pas pu être enregistrés", t); }
        Capture.rendre(f);
    }

    static List<String> tagsDe(Properties p, File f) {
        List<String> l = new ArrayList<>();
        for (String t : p.getProperty(f.getName(), "").split(",")) {
            t = t.trim();
            if (!t.isEmpty() && l.stream().noneMatch(t::equalsIgnoreCase)) l.add(Ui.majuscule(t));
        }
        return l;
    }

    /** Tous les tags de la galerie (tous dossiers), pour proposer la meme ecriture partout. */
    private static Set<String> tousLesTags() {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ramasserTags(dossier(), s, 0);
        return s;
    }

    private static void ramasserTags(File d, Set<String> s, int prof) {
        Properties p = lireTags(d);
        for (String k : p.stringPropertyNames()) if (new File(d, k).isFile()) s.addAll(tagsDe(p, new File(d, k)));
        if (prof < PROFONDEUR) for (File x : sousDossiers(d)) ramasserTags(x, s, prof + 1);
    }

    private void editerTags(File f) {
        File d = f.getParentFile();
        Properties p = lireTags(d);
        Set<String> connus = tousLesTags();
        TextInputDialog dlg = new TextInputDialog(String.join(", ", tagsDe(p, f)));
        dlg.setTitle("Tags");
        dlg.setHeaderText(null);
        dlg.setContentText(connus.isEmpty() ? "Tags (séparés par des virgules) :"
                : "Tags (séparés par des virgules)\nDéjà utilisés : " + String.join(", ", connus) + "\n");
        devant(dlg);
        dlg.showAndWait().ifPresent(n -> {
            List<String> l = new ArrayList<>();
            for (String t : n.split(",")) {
                t = t.replaceAll("[=:\\p{Cntrl}]", "").trim();
                if (t.isEmpty()) continue;
                // meme ecriture qu'un tag deja utilise (« noel » -> « Noël » si deja la)
                String tt = t;
                String k = connus.stream().filter(c -> c.equalsIgnoreCase(tt)).findFirst().orElse(Ui.majuscule(t));
                if (l.stream().noneMatch(k::equalsIgnoreCase)) l.add(k);
            }
            if (l.isEmpty()) p.remove(f.getName()); else p.setProperty(f.getName(), String.join(", ", l));
            ecrireTags(d, p);
            rafraichir();
        });
    }

    private static List<String> lireOrdre(File d) {
        try {
            File f = new File(d, ORDRE);
            if (f.isFile()) return new ArrayList<>(Files.readAllLines(f.toPath()));
        } catch (Throwable ignored) { }
        return new ArrayList<>();
    }

    private static void ecrireOrdre(File d, List<File> l) {
        List<String> noms = new ArrayList<>();
        for (File x : l) noms.add(x.getName());
        ecrireOrdreNoms(d, noms);
    }

    private static void ecrireOrdreNoms(File d, List<String> noms) {
        File f = new File(d, ORDRE);
        java.nio.file.Path tmp = f.toPath().resolveSibling(ORDRE + ".tmp");
        try {
            // fichier temporaire puis remplacement : jamais d'ordre.txt a moitie ecrit
            Files.write(tmp, noms);
            try {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Capture.rendre(f);
        } catch (Throwable t) {
            try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
            Journal.erreur("L'ordre de la galerie n'a pas pu être enregistré", t);
        }
    }

    /** Glisser la photo « source » sur « cible » (meme dossier) : elle prend sa place. */
    private void deplacer(File source, File cible) {
        File d = cible.getParentFile();
        if (d == null || !d.equals(source.getParentFile())) return;
        List<File> l = photosDe(d);
        int i = l.indexOf(source), j = l.indexOf(cible);
        if (i < 0 || j < 0 || i == j) return;
        l.remove(i);
        l.add(j, source);
        ecrireOrdre(d, l);
        rafraichir();
    }

    private static final String VUE = "galerie.vue", CADRE = "galerie.cadre", PHOTO = "galerie.photo", INDEX = "galerie.index";
    /** Pastille de tag : petite, arrondie. */
    private static final String PASTILLE = "-fx-font-size: 11px; -fx-background-radius: 9; -fx-background-insets: 0; "
            + "-fx-pref-height: -1; -fx-min-height: 0; -fx-padding: 1 7 1 7;";
    private static final String CARTE = "-fx-padding: 6; -fx-effect: dropshadow(gaussian, rgba(60,55,45,0.16), 6, 0, 0, 1);";
    private static final String CARTE_SURVOL = "-fx-padding: 6; -fx-effect: dropshadow(gaussian, rgba(60,55,45,0.30), 9, 0, 0, 2);";
    private static final String FOND_VIGNETTE = "-fx-background-color: #F1EFE7; -fx-background-radius: 3;";
    private static final String FOND_DOSSIER = "-fx-background-color: #E9E4D6; -fx-background-radius: 3;";
    private static final String FOND_DEPOT = "-fx-background-color: #3E86AC; -fx-background-radius: 3; -fx-opacity: 0.85;";

    /**
     * Taille de la vignette selon la largeur de la carte (cadre 4:3, photo
     * entiere dedans). Grandes cartes : cadre a la forme de la photo.
     */
    private void dimensionner(VBox carte, double largeur) {
        double l = Math.max(60, largeur - 12);        // moins la marge interieure de la carte
        double h = grandes ? hauteurCadre(carte) : Math.round(l * 0.75);
        carte.setPrefWidth(largeur);
        carte.setMinWidth(largeur);
        carte.setMaxWidth(largeur);
        ImageView iv = (ImageView) carte.getProperties().get(VUE);
        StackPane cadre = (StackPane) carte.getProperties().get(CADRE);
        iv.setFitWidth(l - 6);
        iv.setFitHeight(h - 6);
        cadre.setMinSize(l, h);
        cadre.setPrefSize(l, h);
        cadre.setMaxSize(l, h);
    }

    /** Ombre plus marquee au survol ou au focus. */
    private static void survol(VBox b) {
        b.getStyleClass().add("boite");
        b.setStyle(CARTE);
        b.setCursor(Cursor.HAND);
        b.setFocusTraversable(true);
        b.hoverProperty().addListener((o, x, y) -> b.setStyle(y || b.isFocused() ? CARTE_SURVOL : CARTE));
        b.focusedProperty().addListener((o, x, y) -> b.setStyle(y || b.isHover() ? CARTE_SURVOL : CARTE));
    }

    private static void menu(VBox b, ContextMenu cm) {
        b.setOnContextMenuRequested(e -> {
            if (e.isKeyboardTrigger()) cm.show(b, javafx.geometry.Side.BOTTOM, 0, 0);
            else cm.show(b, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /**
     * Carte d'un sous-dossier : apercu de sa premiere photo (ou une icone),
     * son nom, le nombre de photos dedans. Clic : l'ouvrir. On y glisse une
     * photo pour l'y ranger, ou des images du Finder pour les y ajouter.
     */
    private VBox carteDossier(File d) {
        List<File> dedans = new ArrayList<>();
        int nSous = 0;
        for (Map.Entry<File, List<File>> e : arbre.entrySet()) {
            if (sousOuEgal(e.getKey(), d)) dedans.addAll(e.getValue());
            if (d.equals(e.getKey().getParentFile())) nSous++;
        }
        File premiere = dedans.isEmpty() ? null : dedans.get(0);
        ImageView iv = new ImageView();     // sa vignette arrive quand la carte approche de l'ecran
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        StackPane cadre = new StackPane(iv);
        cadre.setStyle(FOND_DOSSIER);
        if (premiere == null) {
            Node ic = Icones.petite(Icones.DOSSIER, 56, false);
            ic.setOpacity(0.45);
            ic.setMouseTransparent(true);
            cadre.getChildren().add(ic);
        } else {
            // petit dossier en haut a gauche : on voit que ce n'est pas une photo
            StackPane badge = new StackPane(Icones.petite(Icones.DOSSIER, 16, false));
            badge.setStyle("-fx-background-color: rgba(255,255,255,0.92); -fx-background-radius: 4; -fx-padding: 3;");
            badge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            badge.setMouseTransparent(true);
            StackPane.setAlignment(badge, Pos.TOP_LEFT);
            StackPane.setMargin(badge, new Insets(4));
            cadre.getChildren().add(badge);
        }
        // renommer, supprimer : au survol, en haut a droite
        Button ren = Ui.boutonIcone(Icones.CRAYON, "Renommer le dossier");
        ren.setFocusTraversable(false);
        ren.setOnAction(e -> renommerDossier(d));
        Button sup = Ui.boutonIcone(Icones.CORBEILLE, "Supprimer le dossier (seulement s'il est vide)");
        sup.setFocusTraversable(false);
        sup.setOnAction(e -> supprimerDossier(d));
        HBox actions = new HBox(4, ren, sup);
        actions.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        actions.setPickOnBounds(false);
        StackPane.setAlignment(actions, Pos.TOP_RIGHT);
        StackPane.setMargin(actions, new Insets(4));
        cadre.getChildren().add(actions);

        Label nom = new Label(d.getName());
        nom.setStyle("-fx-font-weight: bold;");
        nom.setMinWidth(0);
        nom.setMaxWidth(Double.MAX_VALUE);
        nom.setTooltip(Ui.bulle(d.getName()));
        HBox.setHgrow(nom, Priority.ALWAYS);
        Node ic = Icones.petite(Icones.DOSSIER, 16, false);
        HBox tete = new HBox(6, ic, nom);
        tete.setAlignment(Pos.CENTER_LEFT);
        int n = dedans.size();
        String resume = (n == 0 ? "Aucune photo" : n + (n > 1 ? " photos" : " photo"))
                + (nSous == 0 ? "" : " · " + nSous + (nSous > 1 ? " dossiers" : " dossier"));
        Label info = new Label(resume);
        info.setStyle("-fx-font-size: 11px; -fx-opacity: 0.65;");

        VBox b = new VBox(5, cadre, tete, info);
        b.setAlignment(Pos.TOP_LEFT);
        survol(b);
        b.setAccessibleText("Dossier " + d.getName() + ", " + resume);
        b.getProperties().put(VUE, iv);
        b.getProperties().put(CADRE, cadre);
        if (premiere != null) b.getProperties().put(PHOTO, premiere);
        iv.imageProperty().addListener((o, x, img) -> ratioConnu(b, img));
        actions.visibleProperty().bind(b.hoverProperty().or(b.focusedProperty()));
        b.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()
                    && !surUnBouton(e.getPickResult().getIntersectedNode(), b)) naviguer(d);
        });
        b.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER: case SPACE: naviguer(d); e.consume(); break;
                case F2: renommerDossier(d); e.consume(); break;
                case DELETE: case BACK_SPACE: supprimerDossier(d); e.consume(); break;
                default:
            }
        });
        // y deposer une photo (la ranger) ou des images du Finder (les y ajouter)
        b.setOnDragOver(e -> {
            File p = photoGlissee(e);
            if (p != null && !d.equals(p.getParentFile())) { e.acceptTransferModes(TransferMode.MOVE); e.consume(); }
            else if (p == null && e.getDragboard().hasFiles()) { e.acceptTransferModes(TransferMode.COPY); e.consume(); }
        });
        b.setOnDragEntered(e -> {
            File p = photoGlissee(e);
            if ((p != null && !d.equals(p.getParentFile())) || (p == null && e.getDragboard().hasFiles())) {
                cadre.setStyle(FOND_DEPOT);
                iv.setOpacity(0.5);
            }
        });
        b.setOnDragExited(e -> { cadre.setStyle(FOND_DOSSIER); iv.setOpacity(1); });
        b.setOnDragDropped(e -> {
            File p = photoGlissee(e);
            if (p != null) Platform.runLater(() -> deplacerVers(p, d));
            else if (e.getDragboard().hasFiles()) {
                List<File> l = new ArrayList<>(e.getDragboard().getFiles());
                Platform.runLater(() -> importer(l, d));
            } else return;
            e.setDropCompleted(true);
            e.consume();
        });

        MenuItem ouvrir = new MenuItem("Ouvrir");
        ouvrir.setOnAction(e -> naviguer(d));
        MenuItem renommer = new MenuItem("Renommer…");
        renommer.setOnAction(e -> renommerDossier(d));
        MenuItem suppr = new MenuItem("Supprimer le dossier");
        suppr.setOnAction(e -> supprimerDossier(d));
        menu(b, new ContextMenu(ouvrir, renommer, new SeparatorMenuItem(), suppr));
        return b;
    }

    private VBox vignette(File f, List<String> sesTags) {
        ImageView iv = new ImageView();     // sa vignette arrive quand la carte approche de l'ecran
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        StackPane cadre = new StackPane(iv);
        cadre.setStyle(FOND_VIGNETTE);
        // supprimer : en haut a droite de la photo, au survol (ou quand la carte a le focus)
        Button btSuppr = Ui.boutonIcone(Icones.CORBEILLE, "Supprimer de la galerie");
        btSuppr.setFocusTraversable(false);
        btSuppr.setOnAction(e -> supprimer(f));
        StackPane.setAlignment(btSuppr, Pos.TOP_RIGHT);
        StackPane.setMargin(btSuppr, new Insets(4));
        cadre.getChildren().add(btSuppr);

        VBox b = new VBox(6, cadre);
        // resultat d'une recherche dans un sous-dossier : son dossier, cliquable
        File sonDossier = f.getParentFile();
        if (sonDossier != null && !sonDossier.equals(courant)) {
            Button lieu = new Button(cheminLisible(courant, sonDossier));
            lieu.setGraphic(Icones.petite(Icones.DOSSIER, 13, false));
            lieu.setGraphicTextGap(4);
            lieu.setStyle(PASTILLE + "-fx-background-color: #EEEAE0; -fx-text-fill: #5A564C; -fx-cursor: hand;");
            lieu.setFocusTraversable(false);
            lieu.setMinWidth(0);
            lieu.setTooltip(Ui.bulle("Dans " + cheminLisible(null, sonDossier) + ". Clic : ouvrir ce dossier."));
            lieu.setOnAction(e -> naviguer(sonDossier));
            HBox ligne = new HBox(lieu);
            ligne.setMinWidth(0);
            b.getChildren().add(ligne);
        }
        b.getChildren().add(pastilles(f, sesTags));
        b.setAlignment(Pos.TOP_CENTER);
        survol(b);
        b.setAccessibleText("Photo" + (sesTags.isEmpty() ? "" : " : " + String.join(", ", sesTags)));
        b.getProperties().put(VUE, iv);
        b.getProperties().put(CADRE, cadre);
        b.getProperties().put(PHOTO, f);
        iv.imageProperty().addListener((o, x, img) -> ratioConnu(b, img));
        btSuppr.visibleProperty().bind(b.hoverProperty().or(b.focusedProperty()));
        b.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()
                    && !surUnBouton(e.getPickResult().getIntersectedNode(), b)) Visionneuse.ouvrir(css, f);
        });
        // clavier : Entree ouvre, Suppr supprime (le menu du clic droit reste au clavier aussi)
        b.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER: case SPACE: Visionneuse.ouvrir(css, f); e.consume(); break;
                case DELETE: case BACK_SPACE: supprimer(f); e.consume(); break;
                default:
            }
        });
        // reorganiser : glisser une photo sur une autre (du meme dossier) ; ou sur un dossier
        b.setOnDragDetected(e -> {
            javafx.scene.input.Dragboard db = b.startDragAndDrop(TransferMode.MOVE);
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(GLISSE + f.getAbsolutePath());
            db.setContent(c);
            // image glissee de la taille d'une petite carte, meme depuis une grande carte
            javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
            double lv = iv.getBoundsInLocal().getWidth();
            if (grandes && lv > CARTE_MIN + 20) sp.setTransform(javafx.scene.transform.Transform.scale((CARTE_MIN + 20) / lv, (CARTE_MIN + 20) / lv));
            db.setDragView(iv.snapshot(sp, null));
            e.consume();
        });
        b.setOnDragOver(e -> {
            File p = photoGlissee(e);
            if (p != null && !p.equals(f) && sonDossier != null && sonDossier.equals(p.getParentFile())) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        b.setOnDragEntered(e -> {
            File p = photoGlissee(e);
            if (p != null && !p.equals(f) && sonDossier != null && sonDossier.equals(p.getParentFile())) cadre.setStyle(FOND_DEPOT);
        });
        b.setOnDragExited(e -> cadre.setStyle(FOND_VIGNETTE));
        b.setOnDragDropped(e -> {
            File p = photoGlissee(e);
            if (p == null) return;
            Platform.runLater(() -> deplacer(p, f));
            e.setDropCompleted(true);
            e.consume();
        });

        MenuItem ouvrir = new MenuItem("Ouvrir");
        ouvrir.setOnAction(e -> Visionneuse.ouvrir(css, f));
        MenuItem tagsItem = new MenuItem("Modifier les tags…");
        tagsItem.setOnAction(e -> editerTags(f));
        MenuItem versDossier = new MenuItem("Déplacer vers…");
        versDossier.setOnAction(e -> choisirDossier(f));
        MenuItem suppr = new MenuItem("Supprimer de la galerie");
        suppr.setOnAction(e -> supprimer(f));
        MenuItem calque = new MenuItem("Utiliser comme photo de référence");
        calque.setOnAction(e -> CalquePhoto.utiliser(f, css, b.getScene() == null ? null : b.getScene().getWindow()));
        ContextMenu cm = new ContextMenu(ouvrir, tagsItem, versDossier, calque);
        if (sonDossier != null && !sonDossier.equals(courant)) {
            MenuItem voir = new MenuItem("Ouvrir son dossier");
            voir.setOnAction(e -> naviguer(sonDossier));
            cm.getItems().add(voir);
        }
        cm.getItems().addAll(new SeparatorMenuItem(), suppr);
        menu(b, cm);
        return b;
    }

    /** Le clic est-il tombe sur un bouton de la carte (supprimer, tags) ? */
    private static boolean surUnBouton(javafx.scene.Node n, javafx.scene.Node carte) {
        for (; n != null && n != carte; n = n.getParent()) if (n instanceof ButtonBase) return true;
        return false;
    }

    /** Les tags de la photo en pastilles (× pour retirer), et « + Tag » pour en ajouter. */
    private FlowPane pastilles(File f, List<String> sesTags) {
        FlowPane fp = new FlowPane(4, 4);
        fp.setMinWidth(0);
        fp.setPrefWrapLength(100);
        for (String t : sesTags) {
            Label l = new Label(t);
            l.setStyle("-fx-font-size: 11px; -fx-text-fill: #2F6F92;");
            Button x = new Button("×");
            x.setFocusTraversable(false);
            x.setStyle("-fx-background-color: transparent; -fx-pref-height: -1; -fx-min-height: 0; -fx-padding: 0 0 0 4; "
                    + "-fx-font-size: 11px; -fx-text-fill: #7C776C; -fx-cursor: hand;");
            x.setTooltip(Ui.bulle("Retirer le tag « " + t + " »"));
            x.setOnAction(e -> retirerTag(f, t));
            HBox chip = new HBox(0, l, x);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setStyle(PASTILLE + "-fx-padding: 1 4 1 7; -fx-background-color: #E4EEF3;");
            fp.getChildren().add(chip);
        }
        Button plus = new Button("+ Tag");
        plus.setFocusTraversable(false);
        plus.setStyle(PASTILLE + "-fx-background-color: transparent; -fx-border-color: #B9B3A5; -fx-border-width: 1; -fx-border-radius: 9; "
                + "-fx-border-style: segments(3, 2); -fx-text-fill: #5A564C; -fx-cursor: hand;");
        plus.setTooltip(Ui.bulle("Ajouter un tag à cette photo"));
        plus.setOnAction(e -> menuAjoutTag(f, sesTags).show(plus, javafx.geometry.Side.BOTTOM, 0, 0));
        fp.getChildren().add(plus);
        // la largeur suit la carte : les pastilles passent a la ligne
        fp.widthProperty().addListener((o, a, w) -> fp.setPrefWrapLength(Math.max(40, w.doubleValue())));
        return fp;
    }

    /** Tags deja utilises que la photo n'a pas, puis « Nouveau tag… ». */
    private ContextMenu menuAjoutTag(File f, List<String> sesTags) {
        Set<String> connus = tousLesTags();
        ContextMenu m = new ContextMenu();
        for (String t : connus) {
            if (sesTags.stream().anyMatch(t::equalsIgnoreCase)) continue;
            MenuItem it = new MenuItem(t);
            it.setOnAction(e -> ajouterTag(f, t));
            m.getItems().add(it);
        }
        if (!m.getItems().isEmpty()) m.getItems().add(new SeparatorMenuItem());
        MenuItem nouveau = new MenuItem("Nouveau tag…");
        nouveau.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Nouveau tag");
            d.setHeaderText(null);
            d.setContentText("Tag (plusieurs : séparés par des virgules) :");
            devant(d);
            d.showAndWait().ifPresent(n -> { for (String t : n.split(",")) ajouterTag(f, t); });
        });
        m.getItems().add(nouveau);
        return m;
    }

    private void ajouterTag(File f, String brut) {
        String t = brut == null ? "" : brut.replaceAll("[=:,\\p{Cntrl}]", "").trim();
        if (t.isEmpty()) return;
        File d = f.getParentFile();
        Properties p = lireTags(d);
        Set<String> connus = tousLesTags();
        // meme ecriture qu'un tag deja utilise (« noel » -> « Noël » si deja la)
        String k = connus.stream().filter(c -> c.equalsIgnoreCase(t)).findFirst().orElse(Ui.majuscule(t));
        List<String> l = tagsDe(p, f);
        if (l.stream().anyMatch(k::equalsIgnoreCase)) return;
        l.add(k);
        p.setProperty(f.getName(), String.join(", ", l));
        ecrireTags(d, p);
        rafraichir();
    }

    private void retirerTag(File f, String t) {
        File d = f.getParentFile();
        Properties p = lireTags(d);
        List<String> l = tagsDe(p, f);
        l.removeIf(t::equalsIgnoreCase);
        if (l.isEmpty()) p.remove(f.getName()); else p.setProperty(f.getName(), String.join(", ", l));
        ecrireTags(d, p);
        rafraichir();
    }

    /** Supprime la copie de la galerie (l'image d'origine n'est pas touchee), apres confirmation. */
    private void supprimer(File f) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer cette photo de la galerie ? "
                + "L'image d'origine n'est pas touchée.", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        devant(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        if (!f.delete()) { erreur("Impossible de supprimer la photo."); return; }
        File d = f.getParentFile();
        Properties p = lireTags(d);
        if (p.remove(f.getName()) != null) ecrireTags(d, p);
        List<String> o = lireOrdre(d);
        if (o.remove(f.getName())) ecrireOrdreNoms(d, o);
        succes("Photo supprimée de la galerie.");
        rafraichir();
    }

    // ============================================================ visionneuse

    /**
     * Une photo dans une fenetre flottante facon Habbo, devant le jeu :
     * zoom a la molette (autour du curseur) ou aux boutons, glisser pour se
     * deplacer, double-clic : ajuster / 100 %, transparence reglable, coin
     * bas-droit pour redimensionner.
     */
    static final class Visionneuse {

        private final Stage stage = new Stage();
        private final ImageView vue;
        private final Pane zone = new Pane();
        private final Label zoomLbl = new Label();
        private double echelle = 1, prisX, prisY, depX, depY;

        /**
         * Ouvre la photo : lue en arriere-plan (le fil JavaFX ne bloque pas),
         * a la taille de l'ecran au plus (une photo de 6000 px ne prend pas
         * des centaines de Mo). « 100 % » reste la taille reelle de la photo.
         */
        static void ouvrir(String css, File f) {
            Platform.runLater(() -> {
                double[] max = ecranMax();
                Thread t = new Thread(() -> {
                    try {
                        int[] dim = dimensions(f);
                        Image img = lire(f, dim, max);
                        Platform.runLater(() -> {
                            if (img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
                                Journal.erreur("Photo illisible : « " + f.getName() + " ».");
                                return;
                            }
                            double rw = dim != null ? dim[0] : img.getWidth(), rh = dim != null ? dim[1] : img.getHeight();
                            try { new Visionneuse(css, f, img, rw, rh); }
                            catch (Throwable e) { Journal.erreur("La photo n'a pas pu s'ouvrir", e); }
                        });
                    } catch (Throwable e) {
                        Platform.runLater(() -> Journal.erreur("La photo n'a pas pu s'ouvrir", e));
                    }
                }, "Atelier photo");
                t.setDaemon(true);
                t.start();
            });
        }

        /** Le plus grand ecran, en pixels reels (Retina compris). Fil JavaFX. */
        private static double[] ecranMax() {
            double bw = 1920, bh = 1080;
            try {
                for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
                    bw = Math.max(bw, s.getBounds().getWidth() * s.getOutputScaleX());
                    bh = Math.max(bh, s.getBounds().getHeight() * s.getOutputScaleY());
                }
            } catch (Throwable ignored) { }
            return new double[]{bw, bh};
        }

        /** La photo, reduite a la taille de l'ecran si elle est plus grande. Hors du fil JavaFX. */
        private static Image lire(File f, int[] dim, double[] max) {
            String uri = f.toURI().toString();
            return dim != null && (dim[0] > max[0] || dim[1] > max[1])
                    ? new Image(uri, max[0], max[1], true, true, false)
                    : new Image(uri);
        }

        /** Largeur et hauteur de la photo sans la decoder (null si inconnues). */
        private static int[] dimensions(File f) {
            try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(f)) {
                if (in == null) return null;
                Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(in);
                if (!it.hasNext()) return null;
                javax.imageio.ImageReader r = it.next();
                try { r.setInput(in, true, true); return new int[]{r.getWidth(0), r.getHeight(0)}; }
                finally { r.dispose(); }
            } catch (Throwable t) { return null; }
        }

        /** Taille reelle de la photo (l'image chargee peut etre plus petite) ; change apres un recadrage. */
        private double reelleL, reelleH;

        private Visionneuse(String css, File f, Image img, double reelleL, double reelleH) {
            this.reelleL = reelleL;
            this.reelleH = reelleH;
            this.fichier = f;
            vue = new ImageView(img);
            vue.setPreserveRatio(true);
            vue.setSmooth(true);
            zone.getChildren().addAll(vue, calque);
            preparerCalque();
            zone.setStyle("-fx-background-color: #1e1e1e;");
            Rectangle2 clip = new Rectangle2(zone);
            zone.setClip(clip.r);
            zone.setPrefSize(Math.min(760, Math.max(360, reelleL)), Math.min(520, Math.max(220, reelleH)));
            // sans ca, la zone ne descend jamais sous la taille de l'image : la fenetre coupait
            zone.setMinSize(0, 0);
            zone.setCursor(Cursor.OPEN_HAND);

            // zoom a la molette, autour du curseur
            zone.setOnScroll(e -> {
                double f2 = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
                zoomer(f2, e.getX(), e.getY());
            });
            zone.setOnZoom(e -> zoomer(e.getZoomFactor(), e.getX(), e.getY()));     // pincement du trackpad
            // en mode recadrage, la souris trace / deplace / redimensionne le cadre au lieu de deplacer la photo
            zone.setOnMousePressed(e -> {
                if (recadrage) { appuiCadre(e.getX(), e.getY()); return; }
                depX = e.getX() - vue.getLayoutX(); depY = e.getY() - vue.getLayoutY(); zone.setCursor(Cursor.CLOSED_HAND);
            });
            zone.setOnMouseDragged(e -> {
                if (recadrage) { glisserCadre(e.getX(), e.getY()); return; }
                ajustee = false; vue.setLayoutX(e.getX() - depX); vue.setLayoutY(e.getY() - depY);
            });
            zone.setOnMouseReleased(e -> {
                if (recadrage) { lacherCadre(); zone.setCursor(curseur(prise(e.getX(), e.getY()))); return; }
                zone.setCursor(Cursor.OPEN_HAND);
            });
            zone.setOnMouseMoved(e -> { if (recadrage) zone.setCursor(curseur(prise(e.getX(), e.getY()))); });
            zone.setOnMouseClicked(e -> {
                if (recadrage) return;
                if (e.getClickCount() == 2) { if (Math.abs(echelle - 1) < 0.01) ajuster(); else reel(); }
            });
            // le cadre suit le zoom, le deplacement de la photo et la taille de la fenetre
            vue.layoutXProperty().addListener((o, a, b) -> dessinerCadre());
            vue.layoutYProperty().addListener((o, a, b) -> dessinerCadre());
            vue.fitWidthProperty().addListener((o, a, b) -> dessinerCadre());
            zone.widthProperty().addListener((o, a, b) -> dessinerCadre());
            zone.heightProperty().addListener((o, a, b) -> dessinerCadre());

            Button moins = new Button("−"), plus = new Button("+"), ajust = new Button("Ajuster"), cent = new Button("100 %");
            moins.setOnAction(e -> zoomer(1 / 1.25, zone.getWidth() / 2, zone.getHeight() / 2));
            plus.setOnAction(e -> zoomer(1.25, zone.getWidth() / 2, zone.getHeight() / 2));
            ajust.setOnAction(e -> ajuster());
            cent.setOnAction(e -> reel());
            Slider opacite = new Slider(0.2, 1, 1);
            opacite.setPrefWidth(110);
            opacite.valueProperty().addListener((o, a, b) -> stage.setOpacity(b.doubleValue()));
            Label lOp = new Label("Transparence");
            // les outils passent a la ligne quand la fenetre est etroite
            HBox zoom = new HBox(6, moins, zoomLbl, plus, ajust, cent);
            zoom.setAlignment(Pos.CENTER_LEFT);
            this.zoomBox = zoom;
            HBox transp = new HBox(6, lOp, opacite);
            transp.setAlignment(Pos.CENTER_LEFT);
            this.transp = transp;

            // recadrage : un bouton pour y entrer ; Valider / Annuler et les dimensions pendant
            recadrer = boutonMain("Recadrer", "Choisir une zone de la photo à garder. Le fichier est remplacé, "
                    + "sous le même nom : ses tags et sa place dans la galerie restent.");
            recadrer.setOnAction(e -> entrerRecadrage());
            defaire = boutonMain("Annuler le recadrage", "Remettre la photo telle qu'elle était à l'ouverture. "
                    + "Possible seulement tant que la visionneuse reste ouverte : une fois fermée, le recadrage est définitif.");
            defaire.setOnAction(e -> annulerRecadrage());
            valider = boutonMain("Valider", "Remplacer la photo par la zone choisie (Entrée).");
            valider.setOnAction(e -> validerRecadrage());
            Button annuler = boutonMain("Annuler", "Quitter le recadrage sans rien changer (Échap).");
            annuler.setOnAction(e -> sortirRecadrage());
            dimLbl.setMinWidth(0);
            dimLbl.setWrapText(true);
            HBox choix = new HBox(6, valider, annuler);
            choix.setAlignment(Pos.CENTER_LEFT);
            this.choix = choix;
            HBox action = new HBox(6, recadrer);
            action.setAlignment(Pos.CENTER_LEFT);
            this.action = action;

            FlowPane outils = new FlowPane(12, 6, zoom, transp, action);
            this.outils = outils;
            outils.setAlignment(Pos.CENTER_LEFT);
            outils.setMinWidth(0);
            zoomLbl.setMinWidth(48);
            zoomLbl.setAlignment(Pos.CENTER);

            // poignee de redimensionnement (coin bas-droit)
            Region poignee = new Region();
            poignee.setPrefSize(14, 14);
            poignee.setMaxSize(14, 14);
            poignee.setCursor(Cursor.SE_RESIZE);
            poignee.setStyle("-fx-background-color: linear-gradient(to bottom right, transparent 50%, #9a9a9a 50%);");
            final double[] tailleDep = new double[4];
            poignee.setOnMousePressed(e -> { tailleDep[0] = e.getScreenX(); tailleDep[1] = e.getScreenY(); tailleDep[2] = stage.getWidth(); tailleDep[3] = stage.getHeight(); e.consume(); });
            poignee.setOnMouseDragged(e -> {
                stage.setWidth(Math.max(LARGEUR_MIN, tailleDep[2] + e.getScreenX() - tailleDep[0]));
                stage.setHeight(Math.max(HAUTEUR_MIN, tailleDep[3] + e.getScreenY() - tailleDep[1]));
                if (!reduite) hauteurDepliee = stage.getHeight();
                e.consume();
            });
            HBox bas = new HBox(outils, poignee);
            HBox.setHgrow(outils, Priority.ALWAYS);
            bas.setAlignment(Pos.BOTTOM_RIGHT);

            VBox corps = new VBox(8, zone, bas);
            VBox.setVgrow(zone, Priority.ALWAYS);
            corps.setPadding(new Insets(8, 8, 4, 8));
            corps.getStyleClass().add("fenetre-corps");

            // barre de titre facon Habbo
            titre.setText(f.getName().replaceFirst("\\.[^.]+$", ""));
            titre.getStyleClass().add("fenetre-titre");
            // « − » replie la fenetre sur sa barre (elle reste la, comme les calques) ; « × » la ferme
            Button reduire = new Button();
            reduire.setGraphic(Icones.trace(Icones.REDUIRE, "icone-fenetre"));
            reduire.getStyleClass().add("fenetre-bouton");
            reduire.setFocusTraversable(false);
            reduire.setTooltip(new Tooltip("Réduire (la photo reste ouverte)"));
            Button croix = new Button();
            croix.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
            croix.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
            croix.setFocusTraversable(false);
            croix.setTooltip(new Tooltip("Fermer"));
            croix.setOnAction(e -> stage.close());
            reduire.setOnAction(e -> replier(corps));
            HBox droite = new HBox(4, reduire, croix);
            droite.setAlignment(Pos.CENTER_RIGHT);
            droite.setPickOnBounds(false);
            droite.setPadding(new Insets(0, 6, 0, 0));
            StackPane barre = new StackPane(titre, droite);
            barre.getStyleClass().add("fenetre-barre");
            barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
            barre.setCursor(Cursor.MOVE);
            barre.setOnMousePressed(e -> { prisX = e.getScreenX() - stage.getX(); prisY = e.getScreenY() - stage.getY(); });
            barre.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - prisX); stage.setY(e.getScreenY() - prisY); });
            barre.setOnMouseClicked(e -> { if (e.getClickCount() == 2) replier(corps); });

            VBox cadre = new VBox(barre, corps);
            VBox.setVgrow(corps, Priority.ALWAYS);
            cadre.getStyleClass().add("fenetre");
            StackPane racine = new StackPane(cadre);
            racine.setStyle("-fx-background-color: transparent;");
            racine.setPadding(new Insets(0, 0, 3, 0));
            Scene sc = new Scene(racine);
            sc.setFill(Color.TRANSPARENT);
            if (css != null) sc.getStylesheets().add(css);
            // en mode recadrage, et seulement quand la visionneuse a le focus : Entree valide, Echap annule
            sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (!recadrage) return;
                switch (e.getCode()) {
                    case ESCAPE: sortirRecadrage(); e.consume(); break;
                    case ENTER: validerRecadrage(); e.consume(); break;
                    default:
                }
            });
            sc.setOnKeyPressed(e -> {
                switch (e.getCode()) {
                    case ESCAPE: stage.close(); break;
                    case PLUS: case ADD: case EQUALS: zoomer(1.25, zone.getWidth() / 2, zone.getHeight() / 2); break;
                    case MINUS: case SUBTRACT: zoomer(1 / 1.25, zone.getWidth() / 2, zone.getHeight() / 2); break;
                    case DIGIT0: case NUMPAD0: ajuster(); break;
                    default:
                }
            });
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            stage.setTitle(titre.getText());
            stage.setScene(sc);
            stage.setMinWidth(LARGEUR_MIN);
            FenetresVolantes.suivre(stage);
            // visionneuse fermee : l'original n'est plus garde, le recadrage devient definitif
            stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> { original = null; fichierOriginal = null; });
            stage.show();
            stage.centerOnScreen();
            hauteurDepliee = stage.getHeight();
            // redimensionner la fenetre garde la photo ajustee tant qu'on n'a pas zoome a la main
            zone.widthProperty().addListener((o, a, b) -> { if (ajustee) ajuster(); });
            zone.heightProperty().addListener((o, a, b) -> { if (ajustee) ajuster(); });
            Platform.runLater(this::ajuster);
        }

        private static final double LARGEUR_MIN = 360, HAUTEUR_MIN = 260;
        private boolean reduite = false, ajustee = true;
        private double hauteurDepliee;

        /** Replie la fenetre sur sa barre de titre, ou la deplie a sa taille d'avant. */
        private void replier(VBox corps) {
            reduite = !reduite;
            corps.setVisible(!reduite);
            corps.setManaged(!reduite);
            if (reduite) { hauteurDepliee = stage.getHeight(); stage.sizeToScene(); }
            else stage.setHeight(Math.max(HAUTEUR_MIN, hauteurDepliee));
        }

        /** Zoom de facteur f autour du point (x, y) de la zone. */
        private void zoomer(double f, double x, double y) {
            ajustee = false;
            double n = Math.max(0.05, Math.min(16, echelle * f));
            double k = n / echelle;
            vue.setLayoutX(x - (x - vue.getLayoutX()) * k);
            vue.setLayoutY(y - (y - vue.getLayoutY()) * k);
            appliquer(n);
        }

        /** Toute la photo dans la fenetre, centree. */
        private void ajuster() {
            ajustee = true;
            double w = Math.max(1, zone.getWidth()), h = Math.max(1, zone.getHeight());
            double n = Math.min(w / reelleL, h / reelleH);
            appliquer(n);
            vue.setLayoutX((w - reelleL * n) / 2);
            vue.setLayoutY((h - reelleH * n) / 2);
        }

        /** Taille reelle (100 %), centree. */
        private void reel() {
            ajustee = false;
            appliquer(1);
            vue.setLayoutX((zone.getWidth() - reelleL) / 2);
            vue.setLayoutY((zone.getHeight() - reelleH) / 2);
        }

        /** Echelle n par rapport a la taille reelle de la photo. */
        private void appliquer(double n) {
            echelle = n;
            vue.setFitWidth(reelleL * n);
            vue.setFitHeight(reelleH * n);
            // pixel art net quand on agrandit, lisse quand on reduit (par rapport a l'image chargee)
            vue.setSmooth(reelleL * n < vue.getImage().getWidth() - 0.5);
            zoomLbl.setText(Math.round(n * 100) + " %");
        }

        // ------------------------------------------------------------ recadrage

        /** La photo affichee (son nom change seulement pour un GIF recadre, enregistre en PNG). */
        private File fichier;
        private final Label titre = new Label();
        private final Label dimLbl = new Label();
        private Button recadrer, defaire, valider;
        private HBox zoomBox, transp, choix, action;
        private FlowPane outils;
        private boolean recadrage = false, occupe = false;
        /** Cadre choisi, en pixels de la photo d'origine (cx0 < cx1, cy0 < cy1) ; aCadre faux tant qu'aucun n'est trace. */
        private double cx0, cy0, cx1, cy1;
        private boolean aCadre = false;
        /** Ce que la souris a pris a l'appui (bords, deplacement ou nouveau cadre), et ou. */
        private int prise;
        private double appuiX, appuiY;
        private final double[] cadreDep = new double[4];
        /** Octets de la photo avant le premier recadrage, gardes tant que la visionneuse est ouverte. */
        private byte[] original;
        private File fichierOriginal;

        private static final int GAUCHE = 1, DROITE = 2, HAUT = 4, BAS = 8, DEPLACER = 16;
        /** Distance (en pixels d'ecran) a laquelle un bord ou un coin se prend. */
        private static final double TOLERANCE = 7, POIGNEE = 8;

        /** Le dessin du cadre : l'exterieur assombri, le cadre clair et ses 8 poignees. */
        private final Pane calque = new Pane();
        private final javafx.scene.shape.Rectangle[] ombres = new javafx.scene.shape.Rectangle[4];
        private final javafx.scene.shape.Rectangle cadreR = new javafx.scene.shape.Rectangle();
        private final javafx.scene.shape.Rectangle[] poignees = new javafx.scene.shape.Rectangle[8];

        /** Un bouton de la visionneuse, avec la main au survol et sa bulle. */
        private static Button boutonMain(String texte, String bulle) {
            Button b = new Button(texte);
            b.setCursor(Cursor.HAND);
            b.setTooltip(Ui.bulle(bulle));
            return b;
        }

        private void preparerCalque() {
            calque.setMouseTransparent(true);
            calque.setManaged(false);
            calque.setVisible(false);
            for (int i = 0; i < ombres.length; i++) {
                ombres[i] = new javafx.scene.shape.Rectangle();
                ombres[i].setFill(Color.rgb(0, 0, 0, 0.55));
                calque.getChildren().add(ombres[i]);
            }
            cadreR.setFill(Color.TRANSPARENT);
            cadreR.setStroke(Color.rgb(255, 255, 255, 0.95));
            cadreR.setStrokeWidth(1.5);
            cadreR.setStrokeType(javafx.scene.shape.StrokeType.OUTSIDE);
            calque.getChildren().add(cadreR);
            for (int i = 0; i < poignees.length; i++) {
                poignees[i] = new javafx.scene.shape.Rectangle(POIGNEE, POIGNEE);
                poignees[i].setFill(Color.WHITE);
                poignees[i].setStroke(Color.rgb(30, 30, 30, 0.8));
                poignees[i].setStrokeWidth(1);
                calque.getChildren().add(poignees[i]);
            }
        }

        /** Position du cadre a l'ecran (dans la zone) : x0, y0, x1, y1. */
        private double[] cadreEcran() {
            double ox = vue.getLayoutX(), oy = vue.getLayoutY();
            return new double[]{ox + cx0 * echelle, oy + cy0 * echelle, ox + cx1 * echelle, oy + cy1 * echelle};
        }

        /** Redessine le cadre (appele a chaque zoom, deplacement ou changement de taille). */
        private void dessinerCadre() {
            calque.setVisible(recadrage);
            if (!recadrage) return;
            for (Node n : calque.getChildren()) n.setVisible(aCadre);
            if (!aCadre) return;
            double w = zone.getWidth(), h = zone.getHeight();
            double[] c = cadreEcran();
            double x0 = c[0], y0 = c[1], x1 = c[2], y1 = c[3];
            placer(ombres[0], 0, 0, w, y0);                     // dessus
            placer(ombres[1], 0, y1, w, h - y1);                // dessous
            placer(ombres[2], 0, y0, x0, y1 - y0);              // gauche
            placer(ombres[3], x1, y0, w - x1, y1 - y0);         // droite
            placer(cadreR, x0, y0, x1 - x0, y1 - y0);
            double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            double[][] pts = {{x0, y0}, {mx, y0}, {x1, y0}, {x1, my}, {x1, y1}, {mx, y1}, {x0, y1}, {x0, my}};
            for (int i = 0; i < poignees.length; i++) {
                poignees[i].setX(pts[i][0] - POIGNEE / 2);
                poignees[i].setY(pts[i][1] - POIGNEE / 2);
            }
        }

        private static void placer(javafx.scene.shape.Rectangle r, double x, double y, double w, double h) {
            r.setX(x);
            r.setY(y);
            r.setWidth(Math.max(0, w));
            r.setHeight(Math.max(0, h));
        }

        /** Point de la zone -> pixel de la photo d'origine (arrondi), borne a la photo si demande. */
        private double[] versImage(double x, double y, boolean borne) {
            double ix = Math.round((x - vue.getLayoutX()) / echelle), iy = Math.round((y - vue.getLayoutY()) / echelle);
            if (borne) { ix = borne(ix, 0, reelleL); iy = borne(iy, 0, reelleH); }
            return new double[]{ix, iy};
        }

        private static double borne(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

        /** Ce que la souris prend en (x, y) : des bords (GAUCHE | HAUT...), DEPLACER, ou 0 (nouveau cadre). */
        private int prise(double x, double y) {
            if (!aCadre) return 0;
            double[] c = cadreEcran();
            boolean dansV = y >= c[1] - TOLERANCE && y <= c[3] + TOLERANCE;
            boolean dansH = x >= c[0] - TOLERANCE && x <= c[2] + TOLERANCE;
            int p = 0;
            double dg = Math.abs(x - c[0]), dd = Math.abs(x - c[2]), dh = Math.abs(y - c[1]), db = Math.abs(y - c[3]);
            // cadre tres petit a l'ecran : le bord le plus proche gagne
            if (dansV && Math.min(dg, dd) <= TOLERANCE) p |= dg <= dd ? GAUCHE : DROITE;
            if (dansH && Math.min(dh, db) <= TOLERANCE) p |= dh <= db ? HAUT : BAS;
            if (p == 0 && x > c[0] && x < c[2] && y > c[1] && y < c[3]) p = DEPLACER;
            return p;
        }

        private static Cursor curseur(int p) {
            switch (p) {
                case GAUCHE | HAUT: return Cursor.NW_RESIZE;
                case DROITE | BAS: return Cursor.SE_RESIZE;
                case DROITE | HAUT: return Cursor.NE_RESIZE;
                case GAUCHE | BAS: return Cursor.SW_RESIZE;
                case GAUCHE: case DROITE: return Cursor.H_RESIZE;
                case HAUT: case BAS: return Cursor.V_RESIZE;
                case DEPLACER: return Cursor.MOVE;
                default: return Cursor.CROSSHAIR;
            }
        }

        private void appuiCadre(double x, double y) {
            if (occupe) return;
            prise = prise(x, y);
            double[] p = versImage(x, y, prise == 0);
            appuiX = p[0];
            appuiY = p[1];
            cadreDep[0] = cx0; cadreDep[1] = cy0; cadreDep[2] = cx1; cadreDep[3] = cy1;
            if (prise == 0) { aCadre = false; dessinerCadre(); afficherDimensions(); }
        }

        private void glisserCadre(double x, double y) {
            if (occupe) return;
            if (prise == 0) {
                // nouveau cadre, de l'appui jusqu'a la souris
                double[] p = versImage(x, y, true);
                cx0 = Math.min(appuiX, p[0]); cx1 = Math.max(appuiX, p[0]);
                cy0 = Math.min(appuiY, p[1]); cy1 = Math.max(appuiY, p[1]);
                aCadre = cx1 - cx0 >= 1 && cy1 - cy0 >= 1;
            } else if (prise == DEPLACER) {
                double[] p = versImage(x, y, false);
                double w = cadreDep[2] - cadreDep[0], h = cadreDep[3] - cadreDep[1];
                cx0 = borne(cadreDep[0] + p[0] - appuiX, 0, reelleL - w);
                cy0 = borne(cadreDep[1] + p[1] - appuiY, 0, reelleH - h);
                cx1 = cx0 + w;
                cy1 = cy0 + h;
            } else {
                // redimensionnement par un bord ou un coin : au moins 1 pixel, jamais hors de la photo
                double[] p = versImage(x, y, true);
                cx0 = cadreDep[0]; cy0 = cadreDep[1]; cx1 = cadreDep[2]; cy1 = cadreDep[3];
                if ((prise & GAUCHE) != 0) cx0 = borne(p[0], 0, cx1 - 1);
                if ((prise & DROITE) != 0) cx1 = borne(p[0], cx0 + 1, reelleL);
                if ((prise & HAUT) != 0) cy0 = borne(p[1], 0, cy1 - 1);
                if ((prise & BAS) != 0) cy1 = borne(p[1], cy0 + 1, reelleH);
            }
            dessinerCadre();
            afficherDimensions();
        }

        private void lacherCadre() { afficherDimensions(); }

        /** Les dimensions du cadre, en pixels de la photo d'origine. */
        private void afficherDimensions() {
            if (!aCadre) dimLbl.setText("Trace un rectangle sur la photo.");
            else dimLbl.setText("Zone gardée : " + Math.round(cx1 - cx0) + " × " + Math.round(cy1 - cy0) + " px");
            valider.setDisable(!aCadre || occupe);
        }

        private void entrerRecadrage() {
            if (occupe) return;
            recadrage = true;
            aCadre = false;
            outils.getChildren().setAll(zoomBox, dimLbl, choix);
            zone.setCursor(Cursor.CROSSHAIR);
            ajuster();
            dessinerCadre();
            afficherDimensions();
            zone.requestFocus();
        }

        private void sortirRecadrage() {
            recadrage = false;
            aCadre = false;
            outils.getChildren().setAll(zoomBox, transp, action);
            zone.setCursor(Cursor.OPEN_HAND);
            dessinerCadre();
        }

        /** « Annuler le recadrage » n'apparait que si un original est garde. */
        private void majAction() {
            if (original != null) action.getChildren().setAll(recadrer, defaire);
            else action.getChildren().setAll(recadrer);
            defaire.setDisable(false);
        }

        private void nouveauFichier(File f) {
            fichier = f;
            titre.setText(f.getName().replaceFirst("\\.[^.]+$", ""));
            stage.setTitle(titre.getText());
        }

        /**
         * Remplace le fichier par la zone choisie, au meme format et sous le
         * meme nom (tags et ordre restent valables). Un GIF : sa premiere
         * image seulement, en PNG (« nom.png »), tags et ordre suivent.
         * La photo est relue sur le disque en pleine taille (celle affichee
         * peut etre reduite a la taille de l'ecran).
         */
        private void validerRecadrage() {
            if (occupe || !recadrage) return;
            if (!aCadre) { dire("Trace d'abord un rectangle sur la photo."); return; }
            if (cx0 <= 0 && cy0 <= 0 && cx1 >= reelleL && cy1 >= reelleH) {
                sortirRecadrage();
                dire("La zone choisie est la photo entière : rien à recadrer.");
                return;
            }
            occupe = true;
            afficherDimensions();
            File src = fichier;
            double rl = reelleL, rh = reelleH, x0 = cx0, y0 = cy0, x1 = cx1, y1 = cy1;
            double[] max = ecranMax();
            boolean premier = original == null;
            Thread t = new Thread(() -> {
                try {
                    byte[] avant = premier ? Files.readAllBytes(src.toPath()) : null;
                    java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(src);
                    if (bi == null) throw new java.io.IOException("photo illisible");
                    // au cas ou la taille lue differe de celle annoncee
                    double kx = bi.getWidth() / rl, ky = bi.getHeight() / rh;
                    int ix = (int) borne(Math.round(x0 * kx), 0, bi.getWidth() - 1);
                    int iy = (int) borne(Math.round(y0 * ky), 0, bi.getHeight() - 1);
                    int iw = (int) borne(Math.round(x1 * kx) - ix, 1, bi.getWidth() - ix);
                    int ih = (int) borne(Math.round(y1 * ky) - iy, 1, bi.getHeight() - iy);
                    boolean alpha = bi.getColorModel().hasAlpha();
                    java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(iw, ih,
                            alpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = out.createGraphics();
                    g.drawImage(bi, -ix, -iy, null);
                    g.dispose();

                    String ext = extension(src);
                    boolean gif = ext.equals("gif");
                    File cible = gif ? libre(src.getParentFile(), src.getName().replaceFirst("\\.[^.]+$", "") + ".png") : src;
                    remplacer(cible, f -> ecrireImage(out, gif ? "png" : ext, f));
                    if (gif && !src.delete()) Journal.debug("recadrage : le GIF d'origine n'a pas pu etre supprime : " + src);
                    Image img = lire(cible, new int[]{iw, ih}, max);
                    Platform.runLater(() -> {
                        if (premier) { original = avant; fichierOriginal = src; }
                        if (gif) renommerDansGalerie(src.getParentFile(), src.getName(), cible.getName());
                        nouveauFichier(cible);
                        if (!img.isError()) vue.setImage(img);
                        reelleL = iw;
                        reelleH = ih;
                        occupe = false;
                        sortirRecadrage();
                        majAction();
                        ajuster();
                        OngletGalerie.actualiser();
                        if (gif) succes("GIF recadré : seule sa première image est gardée, enregistrée en PNG (« " + cible.getName() + " »).");
                        else succes("Photo recadrée : " + iw + " × " + ih + " px.");
                    });
                } catch (Throwable e) {
                    Platform.runLater(() -> {
                        occupe = false;
                        afficherDimensions();
                        Journal.erreur("La photo n'a pas pu être recadrée", e);
                    });
                }
            }, "Atelier recadrage");
            t.setDaemon(true);
            t.start();
        }

        /** Reecrit la photo d'avant le premier recadrage (seulement tant que la visionneuse est ouverte). */
        private void annulerRecadrage() {
            if (occupe || original == null) return;
            occupe = true;
            defaire.setDisable(true);
            byte[] octets = original;
            File cible = fichierOriginal, actuel = fichier;
            double[] max = ecranMax();
            Thread t = new Thread(() -> {
                try {
                    remplacer(cible, f -> Files.write(f.toPath(), octets));
                    boolean renomme = !actuel.equals(cible);
                    if (renomme && !actuel.delete()) Journal.debug("recadrage : le PNG du GIF recadre n'a pas pu etre supprime : " + actuel);
                    int[] dim = dimensions(cible);
                    Image img = lire(cible, dim, max);
                    Platform.runLater(() -> {
                        if (renomme) renommerDansGalerie(cible.getParentFile(), actuel.getName(), cible.getName());
                        nouveauFichier(cible);
                        if (!img.isError()) vue.setImage(img);
                        reelleL = dim != null ? dim[0] : img.getWidth();
                        reelleH = dim != null ? dim[1] : img.getHeight();
                        original = null;
                        fichierOriginal = null;
                        occupe = false;
                        majAction();
                        ajuster();
                        OngletGalerie.actualiser();
                        succes("Recadrage annulé : la photo d'origine est revenue.");
                    });
                } catch (Throwable e) {
                    Platform.runLater(() -> {
                        occupe = false;
                        defaire.setDisable(false);
                        Journal.erreur("Le recadrage n'a pas pu être annulé", e);
                    });
                }
            }, "Atelier recadrage");
            t.setDaemon(true);
            t.start();
        }

        private interface Ecriture { void vers(File f) throws Exception; }

        /**
         * Ecrit dans un fichier temporaire a cote, puis le met a la place : jamais
         * de photo a moitie ecrite. Sous sudo, le fichier est rendu a l'utilisatrice.
         * La date du fichier change : la vignette est refaite (cle chemin + taille + date).
         */
        private static void remplacer(File cible, Ecriture e) throws Exception {
            java.nio.file.Path tmp = cible.toPath().resolveSibling("." + cible.getName() + ".recadrage.tmp");
            try {
                e.vers(tmp.toFile());
                try {
                    Files.move(tmp, cible.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException x) {
                    Files.move(tmp, cible.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
            }
            Capture.rendre(cible);
        }

        /** Au format de l'extension : JPG en qualite 0,95, BMP sans transparence, PNG sinon. */
        private static void ecrireImage(java.awt.image.BufferedImage img, String ext, File f) throws Exception {
            switch (ext) {
                case "jpg": case "jpeg":
                    Capture.ecrireJpg(img, 0xFFFFFF, f);
                    break;
                case "bmp": {
                    java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = rgb.createGraphics();
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, img.getWidth(), img.getHeight());
                    g.drawImage(img, 0, 0, null);
                    g.dispose();
                    if (!javax.imageio.ImageIO.write(rgb, "bmp", f)) throw new java.io.IOException("aucun encodeur BMP");
                    break;
                }
                default:
                    Capture.ecrirePng(img, f);
            }
        }

        /** Un GIF devenu PNG (ou l'inverse) : ses tags et sa place dans l'ordre suivent le nouveau nom. */
        private static void renommerDansGalerie(File d, String ancien, String nouveau) {
            Properties p = lireTags(d);
            String v = p.getProperty(ancien);
            if (v != null) {
                p.remove(ancien);
                p.setProperty(nouveau, v);
                ecrireTags(d, p);
            }
            List<String> o = lireOrdre(d);
            int i = o.indexOf(ancien);
            if (i >= 0) {
                o.set(i, nouveau);
                ecrireOrdreNoms(d, o);
            }
        }

        /** Le contenu de la zone ne deborde pas (rectangle qui suit sa taille). */
        private static final class Rectangle2 {
            final javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle();
            Rectangle2(Region z) {
                r.widthProperty().bind(z.widthProperty());
                r.heightProperty().bind(z.heightProperty());
            }
        }
    }
}

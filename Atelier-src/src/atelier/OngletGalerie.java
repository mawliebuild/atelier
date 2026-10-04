package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
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
 * coller une image copiee) ; elles sont COPIEES dans ~/Pictures/Atelier/Galerie.
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
    /** Fichier de l'ordre choisi (glisser-deposer), un nom par ligne. */
    private static final String ORDRE = "ordre.txt";
    /** Tags de chaque photo : « nom du fichier = Noël, Loft ». */
    private static final String TAGS = "tags.properties";

    private final String css;
    /** La grille : des rangees de cartes de meme largeur, refaites quand la largeur change. */
    private final VBox grille = new VBox(ECART);
    private final TextField recherche = new TextField();
    private final Label compteur = new Label();
    /** Tags existants qui contiennent le mot tape : un clic les met dans la recherche. */
    private final FlowPane suggestions = new FlowPane(4, 4);
    /** Les cartes, dans l'ordre, et les tags de chaque photo (pour filtrer sans relire le disque). */
    private final LinkedHashMap<File, VBox> cartes = new LinkedHashMap<>();
    private final Map<File, List<String>> tagsPhotos = new HashMap<>();
    private final List<VBox> visibles = new ArrayList<>();
    private int colonnes = 0;
    private double largeurCarte = 0;

    /** Messages : dans le jeu (message du personnage), pas dans la fenetre. */
    private static void dire(String m) { InfoJeu.dire(Ui.majuscule(m)); }
    private static void succes(String m) { Journal.succes(Ui.accorder(Ui.majuscule(m))); }
    private static void erreur(String m) { Journal.erreur(Ui.accorder(Ui.majuscule(m))); }

    public OngletGalerie(String css) { this.css = css; instance = this; }

    private static volatile OngletGalerie instance;

    /** Un fichier libre dans la galerie pour ce nom (« nom (2).png »...) : pour y ajouter une image d'ailleurs. */
    static File fichierLibre(String nom) {
        nom = nom.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        return libre(nom.isEmpty() ? "Photo.png" : nom);
    }

    /** Relit la galerie (apres un ajout fait d'ailleurs, ex. la photo de l'appart). */
    static void actualiser() {
        OngletGalerie g = instance;
        if (g != null) Platform.runLater(g::rafraichir);
    }

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

        VBox v = new VBox(10,
                Ui.bloc("Photos", Ui.boutons(ajouter, coller),
                        Ui.aide("Garde ici des captures de tes apparts ou d'autres apparts. Clique une photo pour "
                                + "l'ouvrir à côté du jeu : molette pour zoomer, glisser pour se déplacer, "
                                + "double-clic pour l'ajuster. Tu peux aussi glisser des images ici, "
                                + "ou glisser une photo sur une autre pour changer l'ordre. "
                                + "« + Tag » range une photo (Noël, Loft, Villa…) ; la recherche trouve "
                                + "les photos dont un tag contient le texte tapé.")),
                barreRecherche(), suggestions, grille);
        v.setPadding(new Insets(12, 14, 14, 14));
        v.setFillWidth(true);
        suggestions.setVisible(false);
        suggestions.setManaged(false);

        grille.setFillWidth(true);
        grille.widthProperty().addListener((o, a, b) -> disposer());

        // glisser-deposer d'images depuis le Finder / l'Explorateur
        v.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        v.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) importer(e.getDragboard().getFiles());
            e.setDropCompleted(ok);
            e.consume();
        });

        rafraichir();
        Ui.rienDeCoupe(v);
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
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
        recherche.textProperty().addListener((o, a, b) -> filtrer());
        recherche.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !recherche.getText().isEmpty()) { recherche.clear(); e.consume(); }
        });
        compteur.setStyle("-fx-opacity: 0.65;");
        compteur.setMinWidth(Region.USE_PREF_SIZE);
        HBox ligne = new HBox(10, champ, compteur);
        ligne.setAlignment(Pos.CENTER_LEFT);
        return ligne;
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
        List<File> l = fc.showOpenMultipleDialog(grille.getScene() == null ? null : grille.getScene().getWindow());
        if (l != null) importer(l);
    }

    /** Image ou fichiers copies (capture d'ecran copiee avec Cmd+Ctrl+Maj+4, par exemple). */
    private void collerPressePapier() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasFiles()) { importer(cb.getFiles()); return; }
        if (!cb.hasImage()) { dire("Rien à coller : copie d'abord une image."); return; }
        Image img = cb.getImage();
        try {
            File f = libre("Photo collée.png");
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader pr = img.getPixelReader();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
            javax.imageio.ImageIO.write(bi, "png", f);
            Capture.rendre(f);
            succes("Image ajoutée.");
            rafraichir();
        } catch (Throwable t) {
            Journal.erreur("Image impossible à enregistrer", t);
        }
    }

    private void importer(List<File> fichiers) {
        int n = 0, ignores = 0, rates = 0;
        String raison = null;
        for (File f : fichiers) {
            if (!f.isFile() || !EXTENSIONS.contains(extension(f))) { ignores++; continue; }
            try {
                File dest = libre(f.getName());
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
            String m = n + " photo(s) ajoutée(s)" + (ign.isEmpty() ? "" : ", " + ign) + ".";
            if (rates > 0) erreur(m + " " + Ui.majuscule(rat) + "."); else succes(m);
        } else if (rates > 0) {
            erreur("Aucune photo ajoutée. " + Ui.majuscule(rat) + (ign.isEmpty() ? "" : " ; " + ign) + ".");
        } else {
            erreur("Aucune photo ajoutée : pas une image PNG, JPG, GIF ou BMP.");
        }
        rafraichir();
    }

    /** Un nom de fichier libre dans la galerie (« nom (2).png »...). */
    private static File libre(String nom) {
        File d = dossier();
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

    /** Relit le dossier, refait les cartes, puis filtre. */
    private void rafraichir() {
        File[] l = dossier().listFiles(f -> f.isFile() && EXTENSIONS.contains(extension(f)));
        List<File> photos = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        // ordre choisi d'abord ; les photos nouvelles (pas encore rangees) en tete, les plus recentes d'abord
        List<String> ordre = lireOrdre();
        photos.sort(Comparator.comparingInt((File f) -> { int i = ordre.indexOf(f.getName()); return i < 0 ? -1 : i; })
                .thenComparing(Comparator.comparingLong(File::lastModified).reversed()));
        toutes = photos;
        Properties tags = lireTags();
        // les images deja chargees sont gardees : seules les nouvelles se chargent
        Map<File, Image> images = new HashMap<>();
        for (Map.Entry<File, VBox> e : cartes.entrySet()) {
            Object im = e.getValue().getProperties().get(IMAGE);
            if (im instanceof Image) images.put(e.getKey(), (Image) im);
        }
        cartes.clear();
        tagsPhotos.clear();
        for (File f : photos) {
            List<String> t = tagsDe(tags, f);
            tagsPhotos.put(f, t);
            cartes.put(f, vignette(f, t, images.get(f)));
        }
        filtrer();
    }

    /** Garde les cartes dont un tag contient chaque mot de la recherche. */
    private void filtrer() {
        String q = recherche.getText();
        visibles.clear();
        for (Map.Entry<File, VBox> e : cartes.entrySet())
            if (correspond(tagsPhotos.getOrDefault(e.getKey(), List.of()), q)) visibles.add(e.getValue());
        int n = cartes.size();
        compteur.setText(mots(q).isEmpty() || n == 0
                ? n + (n > 1 ? " photos" : " photo")
                : visibles.size() + " / " + n + (n > 1 ? " photos" : " photo"));
        compteur.setVisible(n > 0);
        // suggestions : les tags existants qui contiennent le mot en cours
        Set<String> connus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (List<String> t : tagsPhotos.values()) connus.addAll(t);
        suggestions.getChildren().clear();
        for (String t : suggestionsPour(connus, q)) {
            Button b = new Button(t);
            b.setFocusTraversable(false);
            b.setStyle(PASTILLE + "-fx-background-color: #E4EEF3; -fx-text-fill: #2F6F92; -fx-cursor: hand;");
            b.setOnAction(e -> choisirSuggestion(t));
            suggestions.getChildren().add(b);
        }
        boolean s = !suggestions.getChildren().isEmpty();
        suggestions.setVisible(s);
        suggestions.setManaged(s);
        colonnes = 0;
        disposer();
    }

    /**
     * Range les cartes visibles en rangees de meme hauteur : autant de
     * colonnes que la largeur en permet (cartes de CARTE_MIN px au moins),
     * toutes de la meme largeur.
     */
    private void disposer() {
        double w = grille.getWidth();
        if (w <= 0) w = 520;
        int cols = Math.max(2, (int) Math.floor((w + ECART) / (CARTE_MIN + ECART)));
        double lc = Math.floor((w - ECART * (cols - 1)) / cols);
        if (cols == colonnes && Math.abs(lc - largeurCarte) < 0.5 && !grille.getChildren().isEmpty()) return;
        colonnes = cols;
        largeurCarte = lc;
        for (VBox c : visibles) dimensionner(c, lc);
        for (Node n : grille.getChildren()) if (n instanceof HBox) ((HBox) n).getChildren().clear();
        grille.getChildren().clear();
        if (visibles.isEmpty()) {
            Label vide = Ui.discret(cartes.isEmpty() ? "Aucune photo. Ajoute-en ou colle une image."
                    : "Aucune photo avec ce tag.");
            vide.setPadding(new Insets(16, 4, 16, 4));
            grille.getChildren().add(vide);
            return;
        }
        for (int i = 0; i < visibles.size(); i += cols) {
            HBox rangee = new HBox(ECART);
            rangee.setFillHeight(true);
            for (int j = i; j < Math.min(i + cols, visibles.size()); j++) rangee.getChildren().add(visibles.get(j));
            grille.getChildren().add(rangee);
        }
    }

    /** Toutes les photos dans l'ordre (filtre ou non : l'ordre reste complet). */
    private List<File> toutes = new ArrayList<>();

    private static Properties lireTags() {
        Properties p = new Properties();
        File f = new File(dossier(), TAGS);
        if (f.isFile()) try (java.io.Reader r = Files.newBufferedReader(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Throwable ignored) { }
        return p;
    }

    private static void ecrireTags(Properties p) {
        File f = new File(dossier(), TAGS);
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

    private void editerTags(File f) {
        Properties p = lireTags();
        Set<String> connus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File x : toutes) connus.addAll(tagsDe(p, x));
        TextInputDialog d = new TextInputDialog(String.join(", ", tagsDe(p, f)));
        d.setTitle("Tags");
        d.setHeaderText(null);
        d.setContentText(connus.isEmpty() ? "Tags (séparés par des virgules) :"
                : "Tags (séparés par des virgules)\nDéjà utilisés : " + String.join(", ", connus) + "\n");
        d.showAndWait().ifPresent(n -> {
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
            ecrireTags(p);
            rafraichir();
        });
    }

    private static List<String> lireOrdre() {
        try {
            File f = new File(dossier(), ORDRE);
            if (f.isFile()) return new ArrayList<>(Files.readAllLines(f.toPath()));
        } catch (Throwable ignored) { }
        return new ArrayList<>();
    }

    private static void ecrireOrdre(List<File> l) {
        List<String> noms = new ArrayList<>();
        for (File x : l) noms.add(x.getName());
        ecrireOrdreNoms(noms);
    }

    private static void ecrireOrdreNoms(List<String> noms) {
        File f = new File(dossier(), ORDRE);
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

    /** Glisser la photo « source » sur « cible » : elle prend sa place. */
    private void deplacer(File source, File cible) {
        List<File> l = new ArrayList<>(toutes);
        int i = l.indexOf(source), j = l.indexOf(cible);
        if (i < 0 || j < 0 || i == j) return;
        l.remove(i);
        l.add(j, source);
        ecrireOrdre(l);
        rafraichir();
    }

    private static final String IMAGE = "galerie.image", VUE = "galerie.vue", CADRE = "galerie.cadre";
    /** Pastille de tag : petite, arrondie. */
    private static final String PASTILLE = "-fx-font-size: 11px; -fx-background-radius: 9; -fx-background-insets: 0; "
            + "-fx-pref-height: -1; -fx-min-height: 0; -fx-padding: 1 7 1 7;";
    private static final String CARTE = "-fx-padding: 6; -fx-effect: dropshadow(gaussian, rgba(60,55,45,0.16), 6, 0, 0, 1);";
    private static final String CARTE_SURVOL = "-fx-padding: 6; -fx-effect: dropshadow(gaussian, rgba(60,55,45,0.30), 9, 0, 0, 2);";
    private static final String FOND_VIGNETTE = "-fx-background-color: #F1EFE7; -fx-background-radius: 3;";
    private static final String FOND_DEPOT = "-fx-background-color: #3E86AC; -fx-background-radius: 3; -fx-opacity: 0.85;";

    /** Taille de la vignette selon la largeur de la carte (cadre 4:3, photo entiere dedans). */
    private static void dimensionner(VBox carte, double largeur) {
        double l = Math.max(60, largeur - 12);        // moins la marge interieure de la carte
        double h = Math.round(l * 0.75);
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

    private VBox vignette(File f, List<String> sesTags, Image deja) {
        // chargee assez grande pour rester nette sur un ecran Retina
        Image img = deja != null ? deja : new Image(f.toURI().toString(), 600, 600, true, true, true);
        ImageView iv = new ImageView(img);
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

        VBox b = new VBox(6, cadre, pastilles(f, sesTags));
        b.getStyleClass().add("boite");
        b.setStyle(CARTE);
        b.setAlignment(Pos.TOP_CENTER);
        b.setCursor(Cursor.HAND);
        b.setFocusTraversable(true);
        b.setAccessibleText("Photo" + (sesTags.isEmpty() ? "" : " : " + String.join(", ", sesTags)));
        b.getProperties().put(IMAGE, img);
        b.getProperties().put(VUE, iv);
        b.getProperties().put(CADRE, cadre);
        btSuppr.visibleProperty().bind(b.hoverProperty().or(b.focusedProperty()));
        b.hoverProperty().addListener((o, x, y) -> b.setStyle(y ? CARTE_SURVOL : CARTE));
        b.focusedProperty().addListener((o, x, y) -> b.setStyle(y || b.isHover() ? CARTE_SURVOL : CARTE));
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
        // reorganiser : glisser une photo sur une autre
        b.setOnDragDetected(e -> {
            javafx.scene.input.Dragboard db = b.startDragAndDrop(TransferMode.MOVE);
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString("galerie:" + f.getName());
            db.setContent(c);
            db.setDragView(iv.snapshot(null, null));
            e.consume();
        });
        b.setOnDragOver(e -> {
            String t = e.getDragboard().getString();
            if (t != null && t.startsWith("galerie:") && !t.equals("galerie:" + f.getName())) e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        b.setOnDragEntered(e -> {
            String t = e.getDragboard().getString();
            if (t != null && t.startsWith("galerie:") && !t.equals("galerie:" + f.getName())) cadre.setStyle(FOND_DEPOT);
        });
        b.setOnDragExited(e -> cadre.setStyle(FOND_VIGNETTE));
        b.setOnDragDropped(e -> {
            String t = e.getDragboard().getString();
            boolean ok = t != null && t.startsWith("galerie:");
            if (ok) {
                File src = new File(dossier(), t.substring("galerie:".length()));
                Platform.runLater(() -> deplacer(src, f));
            }
            e.setDropCompleted(ok);
            e.consume();
        });

        MenuItem ouvrir = new MenuItem("Ouvrir");
        ouvrir.setOnAction(e -> Visionneuse.ouvrir(css, f));
        MenuItem tagsItem = new MenuItem("Modifier les tags…");
        tagsItem.setOnAction(e -> editerTags(f));
        MenuItem suppr = new MenuItem("Supprimer de la galerie");
        suppr.setOnAction(e -> supprimer(f));
        ContextMenu cm = new ContextMenu(ouvrir, tagsItem, new SeparatorMenuItem(), suppr);
        b.setOnContextMenuRequested(e -> {
            if (e.isKeyboardTrigger()) cm.show(b, javafx.geometry.Side.BOTTOM, 0, 0);
            else cm.show(b, e.getScreenX(), e.getScreenY());
            e.consume();
        });
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
        Properties p = lireTags();
        Set<String> connus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File x : toutes) connus.addAll(tagsDe(p, x));
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
            d.showAndWait().ifPresent(n -> { for (String t : n.split(",")) ajouterTag(f, t); });
        });
        m.getItems().add(nouveau);
        return m;
    }

    private void ajouterTag(File f, String brut) {
        String t = brut == null ? "" : brut.replaceAll("[=:,\\p{Cntrl}]", "").trim();
        if (t.isEmpty()) return;
        Properties p = lireTags();
        Set<String> connus = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File x : toutes) connus.addAll(tagsDe(p, x));
        // meme ecriture qu'un tag deja utilise (« noel » -> « Noël » si deja la)
        String k = connus.stream().filter(c -> c.equalsIgnoreCase(t)).findFirst().orElse(Ui.majuscule(t));
        List<String> l = tagsDe(p, f);
        if (l.stream().anyMatch(k::equalsIgnoreCase)) return;
        l.add(k);
        p.setProperty(f.getName(), String.join(", ", l));
        ecrireTags(p);
        rafraichir();
    }

    private void retirerTag(File f, String t) {
        Properties p = lireTags();
        List<String> l = tagsDe(p, f);
        l.removeIf(t::equalsIgnoreCase);
        if (l.isEmpty()) p.remove(f.getName()); else p.setProperty(f.getName(), String.join(", ", l));
        ecrireTags(p);
        rafraichir();
    }

    /** Supprime la copie de la galerie (l'image d'origine n'est pas touchee), apres confirmation. */
    private void supprimer(File f) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer cette photo de la galerie ? "
                + "L'image d'origine n'est pas touchée.", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        if (grille.getScene() != null) a.initOwner(grille.getScene().getWindow());
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        if (!f.delete()) { erreur("Impossible de supprimer la photo."); return; }
        Properties p = lireTags();
        if (p.remove(f.getName()) != null) ecrireTags(p);
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

        static void ouvrir(String css, File f) {
            Platform.runLater(() -> {
                try {
                    Image img = new Image(f.toURI().toString());
                    if (img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
                        Journal.erreur("Photo illisible : « " + f.getName() + " ».");
                        return;
                    }
                    new Visionneuse(css, f, img);
                }
                catch (Throwable t) { Journal.erreur("La photo n'a pas pu s'ouvrir", t); }
            });
        }

        private Visionneuse(String css, File f, Image img) {
            vue = new ImageView(img);
            vue.setPreserveRatio(true);
            vue.setSmooth(true);
            zone.getChildren().add(vue);
            zone.setStyle("-fx-background-color: #1e1e1e;");
            Rectangle2 clip = new Rectangle2(zone);
            zone.setClip(clip.r);
            zone.setPrefSize(Math.min(760, Math.max(360, img.getWidth())), Math.min(520, Math.max(220, img.getHeight())));
            // sans ca, la zone ne descend jamais sous la taille de l'image : la fenetre coupait
            zone.setMinSize(0, 0);
            zone.setCursor(Cursor.OPEN_HAND);

            // zoom a la molette, autour du curseur
            zone.setOnScroll(e -> {
                double f2 = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
                zoomer(f2, e.getX(), e.getY());
            });
            zone.setOnZoom(e -> zoomer(e.getZoomFactor(), e.getX(), e.getY()));     // pincement du trackpad
            zone.setOnMousePressed(e -> { depX = e.getX() - vue.getLayoutX(); depY = e.getY() - vue.getLayoutY(); zone.setCursor(Cursor.CLOSED_HAND); });
            zone.setOnMouseDragged(e -> { ajustee = false; vue.setLayoutX(e.getX() - depX); vue.setLayoutY(e.getY() - depY); });
            zone.setOnMouseReleased(e -> zone.setCursor(Cursor.OPEN_HAND));
            zone.setOnMouseClicked(e -> { if (e.getClickCount() == 2) { if (Math.abs(echelle - 1) < 0.01) ajuster(); else reel(); } });

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
            HBox transp = new HBox(6, lOp, opacite);
            transp.setAlignment(Pos.CENTER_LEFT);
            FlowPane outils = new FlowPane(12, 6, zoom, transp);
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
            Label titre = new Label(f.getName().replaceFirst("\\.[^.]+$", ""));
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
            Image img = vue.getImage();
            double w = Math.max(1, zone.getWidth()), h = Math.max(1, zone.getHeight());
            double n = Math.min(w / img.getWidth(), h / img.getHeight());
            appliquer(n);
            vue.setLayoutX((w - img.getWidth() * n) / 2);
            vue.setLayoutY((h - img.getHeight() * n) / 2);
        }

        /** Taille reelle (100 %), centree. */
        private void reel() {
            ajustee = false;
            Image img = vue.getImage();
            appliquer(1);
            vue.setLayoutX((zone.getWidth() - img.getWidth()) / 2);
            vue.setLayoutY((zone.getHeight() - img.getHeight()) / 2);
        }

        private void appliquer(double n) {
            echelle = n;
            vue.setFitWidth(vue.getImage().getWidth() * n);
            vue.setFitHeight(vue.getImage().getHeight() * n);
            // pixel art net quand on agrandit, lisse quand on reduit
            vue.setSmooth(n < 1);
            zoomLbl.setText(Math.round(n * 100) + " %");
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

package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
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
    /** Vignettes : 3 par ligne dans la fenetre elargie (580 px). */
    private static final double VIGNETTE = 140;
    /** Fichier de l'ordre choisi (glisser-deposer), un nom par ligne. */
    private static final String ORDRE = "ordre.txt";
    /** Tags de chaque photo : « nom du fichier = Noël, Loft ». */
    private static final String TAGS = "tags.properties";
    /** Tags choisis pour filtrer : une photo s'affiche si elle les a tous. */
    private final Set<String> filtre = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final FlowPane barreTags = new FlowPane(6, 6);

    private final String css;
    private final TilePane grille = new TilePane(10, 10);
    /** Messages : dans le jeu (message du personnage), pas dans la fenetre. */
    private static void dire(String m) { InfoJeu.dire(Ui.majuscule(m)); }
    private static void succes(String m) { Journal.succes(Ui.majuscule(m)); }
    private static void erreur(String m) { Journal.erreur(Ui.majuscule(m)); }

    public OngletGalerie(String css) { this.css = css; }

    static File dossier() {
        File d = new File(Capture.dossierSortie(), "Galerie");
        if (!d.isDirectory() && d.mkdirs()) Capture.rendre(d);
        return d;
    }

    public Tab construire() {
        Button ajouter = new Button("Ajouter des photos…");
        ajouter.getStyleClass().add("primaire");
        ajouter.setOnAction(e -> choisir());
        Button coller = new Button("Coller une image");
        coller.setOnAction(e -> collerPressePapier());

        grille.setPrefColumns(3);
        grille.setPrefTileWidth(VIGNETTE + 12);
        grille.setPadding(new Insets(4));
        grille.setAlignment(Pos.TOP_LEFT);

        VBox v = new VBox(12,
                Ui.bloc("Photos", Ui.ligne(ajouter, coller),
                        Ui.aide("Ajoute des captures de tes apparts ou d'autres apparts. Clique une photo pour "
                                + "l'ouvrir à côté du jeu : molette pour zoomer, glisser pour se déplacer, "
                                + "double-clic pour l'ajuster. Tu peux aussi glisser des images ici. "
                                + "Glisse une photo sur une autre pour changer l'ordre. Clic droit, « Tags… » "
                                + "pour lui mettre des tags (Noël, Loft, Villa…) et filtrer avec les boutons.")),
                barreTags, grille);
        v.setPadding(new Insets(12, 14, 14, 14));
        v.setFillWidth(true);

        // glisser-deposer d'images depuis le Finder
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
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Galerie", sp);
        t.setClosable(false);
        return t;
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

    private void rafraichir() {
        File[] l = dossier().listFiles(f -> f.isFile() && EXTENSIONS.contains(extension(f)));
        List<File> photos = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        // ordre choisi d'abord ; les photos nouvelles (pas encore rangees) en tete, les plus recentes d'abord
        List<String> ordre = lireOrdre();
        photos.sort(Comparator.comparingInt((File f) -> { int i = ordre.indexOf(f.getName()); return i < 0 ? -1 : i; })
                .thenComparing(Comparator.comparingLong(File::lastModified).reversed()));
        toutes = photos;
        Properties tags = lireTags();
        // les tags qui n'existent plus ne filtrent plus
        Set<String> existants = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File f : photos) existants.addAll(tagsDe(tags, f));
        filtre.retainAll(existants);
        construireBarreTags(existants);
        List<File> visibles = new ArrayList<>();
        for (File f : photos) {
            Set<String> t = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            t.addAll(tagsDe(tags, f));
            if (t.containsAll(filtre)) visibles.add(f);
        }
        grille.getChildren().clear();
        for (File f : visibles) grille.getChildren().add(vignette(f, tagsDe(tags, f)));
        if (photos.isEmpty()) grille.getChildren().add(Ui.discret("Aucune photo pour l'instant."));
        else if (visibles.isEmpty()) grille.getChildren().add(Ui.discret("Aucune photo n'a tous ces tags."));
    }

    /** Toutes les photos dans l'ordre (filtre ou non : l'ordre reste complet). */
    private List<File> toutes = new ArrayList<>();

    private void construireBarreTags(Set<String> existants) {
        barreTags.getChildren().clear();
        if (existants.isEmpty()) return;
        ToggleButton tous = new ToggleButton("Toutes");
        tous.setSelected(filtre.isEmpty());
        tous.setOnAction(e -> { filtre.clear(); rafraichir(); });
        barreTags.getChildren().add(tous);
        for (String t : existants) {
            ToggleButton b = new ToggleButton(t);
            b.setSelected(filtre.contains(t));
            b.setOnAction(e -> { if (b.isSelected()) filtre.add(t); else filtre.remove(t); rafraichir(); });
            barreTags.getChildren().add(b);
        }
    }

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

    private VBox vignette(File f, List<String> sesTags) {
        // chargee en 3x : nette sur un ecran Retina (avant : floue)
        ImageView iv = new ImageView(new Image(f.toURI().toString(), VIGNETTE * 3, VIGNETTE * 3, true, true, true));
        iv.setFitWidth(VIGNETTE);
        iv.setFitHeight(VIGNETTE);
        iv.setPreserveRatio(true);
        StackPane cadre = new StackPane(iv);
        cadre.setPrefSize(VIGNETTE + 8, VIGNETTE + 8);
        cadre.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 4;");
        Label nom = new Label(f.getName().replaceFirst("\\.[^.]+$", ""));
        nom.setMaxWidth(VIGNETTE + 8);
        nom.setStyle("-fx-font-size: 11px;");
        VBox b = new VBox(4, cadre, nom);
        if (!sesTags.isEmpty()) {
            Label t = new Label(String.join(" · ", sesTags));
            t.setMaxWidth(VIGNETTE + 8);
            t.setStyle("-fx-font-size: 10px; -fx-text-fill: #7C776C;");
            b.getChildren().add(t);
        }
        b.setAlignment(Pos.TOP_CENTER);
        b.setCursor(Cursor.HAND);
        b.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()) Visionneuse.ouvrir(css, f);
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
        b.setOnDragEntered(e -> { if (e.getDragboard().hasString()) cadre.setStyle("-fx-background-color: rgba(255,225,74,0.45); -fx-background-radius: 4;"); });
        b.setOnDragExited(e -> cadre.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 4;"));
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
        MenuItem tagsItem = new MenuItem("Tags…");
        tagsItem.setOnAction(e -> editerTags(f));
        MenuItem renommer = new MenuItem("Renommer…");
        renommer.setOnAction(e -> renommer(f));
        MenuItem suppr = new MenuItem("Retirer de la galerie");
        suppr.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Retirer « " + f.getName() + " » de la galerie ? "
                    + "La copie de la galerie est supprimée (l'image d'origine n'est pas touchée).",
                    ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                if (!f.delete()) erreur("Impossible de retirer « " + f.getName() + " ».");
                else { Properties p = lireTags(); if (p.remove(f.getName()) != null) ecrireTags(p); }
                rafraichir();
            }
        });
        ContextMenu cm = new ContextMenu(ouvrir, tagsItem, renommer, suppr);
        b.setOnContextMenuRequested(e -> cm.show(b, e.getScreenX(), e.getScreenY()));
        return b;
    }

    private void renommer(File f) {
        String ext = f.getName().contains(".") ? f.getName().substring(f.getName().lastIndexOf('.')) : "";
        TextInputDialog d = new TextInputDialog(f.getName().replaceFirst("\\.[^.]+$", ""));
        d.setTitle("Renommer");
        d.setHeaderText(null);
        d.setContentText("Nouveau nom :");
        d.showAndWait().ifPresent(n -> {
            n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
            if (n.isEmpty()) return;
            File dest = new File(f.getParentFile(), Ui.majuscule(n) + ext);
            if (dest.exists()) { erreur("Renommage impossible : une photo porte déjà ce nom."); return; }
            if (!f.renameTo(dest)) erreur("Impossible de renommer.");
            else {
                // les tags et la place dans l'ordre suivent la photo
                Properties p = lireTags();
                String t = (String) p.remove(f.getName());
                if (t != null) { p.setProperty(dest.getName(), t); ecrireTags(p); }
                List<String> o = lireOrdre();
                int i = o.indexOf(f.getName());
                if (i >= 0) { o.set(i, dest.getName()); ecrireOrdreNoms(o); }
            }
            rafraichir();
        });
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

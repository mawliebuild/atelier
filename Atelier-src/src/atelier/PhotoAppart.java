package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/**
 * Photo de l'appart (appareil photo de la barre du bas).
 *
 *   1. une petite fenetre : le format (PNG, JPG, GIF) et, pour un GIF, ses
 *      reglages (nombre d'images, intervalle, duree de chaque image) ;
 *   2. « Prendre la photo » : le jeu modifie se photographie lui-meme
 *      (« atelier:capture », comme :screenshot, sans autorisation) ; en GIF,
 *      plusieurs photos d'affilee ;
 *   3. l'apercu (ApercuCapture) : choix du fond, puis Enregistrer ouvre la
 *      fenetre d'enregistrement du systeme, dans Telechargements.
 * Les choix sont retenus d'une fois a l'autre.
 */
final class PhotoAppart {

    private PhotoAppart() { }

    private static final Preferences PREFS = Preferences.userRoot().node("atelier");
    private static final long ATTENTE_JEU_MS = 8_000;
    private static final AtomicBoolean occupe = new AtomicBoolean(false);
    private static Stage ouverte;

    /** Ouvre la fenetre de preparation (ou la ramene devant). */
    static void ouvrir(String css) {
        Platform.runLater(() -> {
            if (ouverte != null && ouverte.isShowing()) { ouverte.toFront(); return; }
            ouverte = fenetre(css);
        });
    }

    private static Stage fenetre(String css) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Photo de l'appart");

        // --- format
        ToggleGroup g = new ToggleGroup();
        HBox formats = new HBox(6);
        String choisi = PREFS.get("photo.format", "PNG");
        for (Capture.Format f : Capture.Format.values()) {
            ToggleButton b = new ToggleButton(f.name());
            b.setToggleGroup(g);
            b.setUserData(f);
            b.setPrefWidth(70);
            b.setFocusTraversable(false);
            if (f.name().equals(choisi)) b.setSelected(true);
            formats.getChildren().add(b);
        }
        if (g.getSelectedToggle() == null) g.getToggles().get(0).setSelected(true);
        Label aideFormat = Ui.discret("");
        aideFormat.setWrapText(true);

        // --- GIF
        Spinner<Integer> nb = spinner(2, 30, PREFS.getInt("photo.gif.nb", 8), 1);
        Spinner<Integer> intervalle = spinner(200, 10_000, PREFS.getInt("photo.gif.intervalle", 1000), 100);
        Spinner<Integer> delai = spinner(50, 5000, PREFS.getInt("photo.gif.delai", 400), 50);
        GridPane grille = new GridPane();
        grille.setHgap(10); grille.setVgap(6);
        grille.addRow(0, new Label("Nombre d'images"), nb);
        grille.addRow(1, new Label("Entre deux photos (ms)"), intervalle);
        grille.addRow(2, new Label("Durée d'une image (ms)"), delai);
        VBox blocGif = Ui.bloc("Animation GIF", grille,
                Ui.aide("L'Atelier prend plusieurs photos d'affilée et les assemble : idéal pour les mobis "
                        + "animés, les wired ou une fête."));
        blocGif.managedProperty().bind(blocGif.visibleProperty());

        Runnable majFormat = () -> {
            Capture.Format f = (Capture.Format) g.getSelectedToggle().getUserData();
            blocGif.setVisible(f == Capture.Format.GIF);
            aideFormat.setText(switch (f) {
                case PNG -> "Image nette, fond transparent possible.";
                case JPG -> "Image plus légère, fond toujours plein.";
                case GIF -> "Image animée (plusieurs photos).";
            });
            stage.sizeToScene();
        };
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            majFormat.run();
        });

        Button prendre = new Button("Prendre la photo");
        prendre.getStyleClass().add("primaire");
        prendre.setDefaultButton(true);
        prendre.setMaxWidth(Double.MAX_VALUE);
        prendre.setGraphic(Icones.petite(Icones.CAPTURE, 16, true));
        prendre.setGraphicTextGap(7);
        Button annuler = new Button("Annuler");
        annuler.setCancelButton(true);
        annuler.setOnAction(e -> stage.close());
        prendre.setOnAction(e -> {
            Capture.Format f = (Capture.Format) g.getSelectedToggle().getUserData();
            int n = f == Capture.Format.GIF ? val(nb, 8) : 1;
            int iv = val(intervalle, 1000), d = val(delai, 400);
            PREFS.put("photo.format", f.name());
            PREFS.putInt("photo.gif.nb", val(nb, 8));
            PREFS.putInt("photo.gif.intervalle", iv);
            PREFS.putInt("photo.gif.delai", d);
            stage.close();
            prendre(css, f, n, iv, d);
        });
        HBox.setHgrow(prendre, Priority.ALWAYS);

        VBox corps = new VBox(12,
                Ui.bloc("Format", formats, aideFormat),
                blocGif,
                boutonsBas(annuler, prendre));
        corps.setStyle("-fx-padding: 12 14 14 14;");   // la feuille (.fenetre-corps) l'emporte sur setPadding
        corps.setPrefWidth(320);
        corps.getStyleClass().add("fenetre-corps");
        majFormat.run();

        Scene sc = new Scene(habiller(stage, "Photo de l'appart", corps));
        sc.setFill(Color.TRANSPARENT);
        if (css != null) sc.getStylesheets().add(css);
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(sc);
        FenetresVolantes.suivre(stage);
        stage.show();
        stage.centerOnScreen();
        return stage;
    }

    /** Hors fil JavaFX : n photos par le jeu, detourees, puis l'apercu. */
    private static void prendre(String css, Capture.Format format, int n, int intervalle, int delai) {
        if (!Salle.dansUneSalle()) { Journal.erreur("Photo impossible : tu n'es pas dans un appart."); return; }
        if (!ClientModifie.saitCapturer()) {
            Journal.erreur("Photo impossible : le jeu n'est pas modifié pour l'Atelier. Ferme Habbo et relance "
                    + "« Lancer l'Atelier ».");
            return;
        }
        if (!occupe.compareAndSet(false, true)) { Journal.erreur("Une photo est déjà en cours."); return; }
        InfoJeu.consigne(n > 1 ? "Photos de l'appart (" + n + ")…" : "Photo de l'appart…");
        Salle.tache("photo-appart", () -> {
            try {
                Capture.Reglages r = new Capture.Reglages();
                List<Capture.Planche> planches = new ArrayList<>();
                String erreur = null;
                for (int i = 0; i < n; i++) {
                    long t0 = System.currentTimeMillis();
                    Capture.Ou<java.io.File> j = Capture.captureParLeJeu(ATTENTE_JEU_MS);
                    if (j.valeur == null) { erreur = j.erreur; break; }
                    try { planches.add(Capture.traiter(Capture.lire(j.valeur), r)); }
                    catch (IllegalStateException ex) { erreur = "Rien à garder : " + ex.getMessage() + "."; break; }
                    finally { j.valeur.delete(); }
                    if (n > 1 && i < n - 1) {
                        InfoJeu.consigne("Photo " + (i + 1) + " / " + n + "…");
                        Salle.sommeil(Math.max(0, intervalle - (System.currentTimeMillis() - t0)));
                    }
                }
                if (planches.isEmpty()) { Journal.erreur("Photo impossible : " + (erreur == null ? "rien reçu du jeu." : erreur)); return; }
                if (erreur != null) Journal.erreur(Ui.accorder("Série arrêtée après " + planches.size() + " photo(s) : " + erreur));
                ApercuCapture.ouvrir(css, planches, r, format, delai);
            } catch (Throwable t) {
                Journal.erreur("Échec de la photo", t);
            } finally {
                occupe.set(false);
            }
        });
    }

    // ------------------------------------------------------------ outils

    private static Spinner<Integer> spinner(int min, int max, int val, int pas) {
        Spinner<Integer> s = new Spinner<>(min, max, Math.max(min, Math.min(max, val)), pas);
        s.setEditable(true);
        s.setPrefWidth(95);
        return s;
    }

    private static int val(Spinner<Integer> s, int defaut) {
        try {
            String t = s.getEditor().getText();
            if (t != null && !t.isBlank()) return Integer.parseInt(t.trim());
            return s.getValue();
        } catch (Throwable e) { return defaut; }
    }

    /** Rangee de boutons du bas, alignee a droite comme les autres fenetres. */
    static HBox boutonsBas(javafx.scene.Node... b) {
        HBox h = new HBox(8, b);
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    /** Cadre facon Habbo (barre de titre deplacable, croix) autour du corps. */
    static StackPane habiller(Stage stage, String titreTexte, Region corps) {
        Label titre = new Label(titreTexte);
        titre.getStyleClass().add("fenetre-titre");
        Button croix = new Button();
        croix.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        croix.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        croix.setFocusTraversable(false);
        croix.setOnAction(e -> stage.close());
        HBox boutons = new HBox(croix);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.setPickOnBounds(false);
        boutons.setPadding(new Insets(0, 6, 0, 0));
        StackPane barre = new StackPane(titre, boutons);
        barre.getStyleClass().add("fenetre-barre");
        barre.setMinHeight(30); barre.setPrefHeight(30); barre.setMaxHeight(30);
        barre.setCursor(Cursor.MOVE);
        double[] pris = new double[2];
        barre.setOnMousePressed(e -> { pris[0] = e.getScreenX() - stage.getX(); pris[1] = e.getScreenY() - stage.getY(); });
        barre.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - pris[0]); stage.setY(e.getScreenY() - pris[1]); });
        VBox cadre = new VBox(barre, corps);
        cadre.getStyleClass().add("fenetre");
        StackPane racine = new StackPane(cadre);
        racine.setStyle("-fx-background-color: transparent;");
        racine.setPadding(new Insets(0, 0, 3, 0));
        Ui.majusculesAuto(corps);
        return racine;
    }
}

package atelier;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Volet « Historique » : Annuler / Retablir les constructions de la salle.
 * Le moteur est dans Historique ; ici seulement l'interface et les raccourcis.
 */
public final class OutilHistorique {

    private final ListView<String> liste = new ListView<>();
    private final Button annuler = new Button("Annuler");
    private final Button retablir = new Button("Rétablir");
    private final CheckBox enregistrer = new CheckBox("Enregistrer les modifications");
    private final Label resume = Ui.valeur("—");
    private final Label etat = Ui.etat();
    /** Suivi automatique (rien a annoncer dans le jeu) : texte discret qui reste en place. */
    private final Label suivi = Ui.discret("");
    private final Label raccourcis = Ui.discret("");

    public Tab construire() {
        Historique.demarrer();

        annuler.getStyleClass().add("primaire");
        annuler.setMaxWidth(Double.MAX_VALUE);
        retablir.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(annuler, Priority.ALWAYS);
        HBox.setHgrow(retablir, Priority.ALWAYS);
        annuler.setOnAction(e -> Historique.annuler());
        retablir.setOnAction(e -> Historique.retablir());
        HBox boutons = new HBox(8, annuler, retablir);

        enregistrer.setSelected(!Historique.enPause());
        enregistrer.setOnAction(e -> Historique.pause(!enregistrer.isSelected()));

        Button vider = new Button("Vider");
        vider.setOnAction(e -> Historique.vider());

        liste.setPrefHeight(230);
        liste.setMinHeight(120);
        liste.setPlaceholder(Ui.discret("Aucune modification enregistrée."));
        liste.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(String s, boolean vide) {
                super.updateItem(s, vide);
                if (vide || s == null) { setText(null); setStyle(""); return; }
                boolean annulee = s.startsWith(ANNULEE);
                setText(annulee ? s.substring(ANNULEE.length()) + "  (annulé)" : s);
                setStyle(annulee ? "-fx-opacity: 0.5; -fx-font-style: italic;" : "");
            }
        });

        Label aide = Ui.aide("Toute modification de la salle est enregistrée, qu'elle vienne du jeu "
                + "ou des outils de l'Atelier. Les changements faits en rafale (moins de 0,6 s "
                + "d'écart) comptent pour une seule action. Raccourcis : Cmd/Ctrl+Z annule, "
                + "Cmd/Ctrl+Maj+Z ou Cmd/Ctrl+Y rétablit — dans l'Atelier ET dans le jeu (Habbo au "
                + "premier plan). Pendant que tu tapes dans le chat, les touches restent au jeu. "
                + "Le jeu ne permet pas de choisir la hauteur "
                + "d'un déplacement : un mobi empilé peut revenir à une autre hauteur (signalé). "
                + "Un mobi ramassé est reposé depuis l'inventaire. L'historique est vidé quand "
                + "on change de salle.");

        VBox contenu = new VBox(12,
                Ui.bloc("Actions", resume, boutons),
                Ui.bloc("Dernières modifications", liste),
                Ui.bloc("Réglages", Ui.ligne(enregistrer, vider), raccourcis),
                aide,
                suivi,
                etat);
        contenu.setPadding(new Insets(12, 14, 14, 14));
        contenu.setFillWidth(true);

        suivi.managedProperty().bind(suivi.textProperty().isNotEmpty());
        suivi.visibleProperty().bind(suivi.managedProperty());
        Historique.ecouter(this::rafraichir);
        rafraichir();

        Tab t = new Tab("Historique", defiler(contenu));
        t.setClosable(false);
        return t;
    }

    private static final String ANNULEE = "\u0000";

    private void rafraichir() {
        List<String> faites = Historique.actions();
        List<String> annulees = Historique.actionsAnnulees();
        List<String> tout = new ArrayList<>();
        for (String s : annulees) tout.add(ANNULEE + s);
        tout.addAll(faites);
        liste.getItems().setAll(tout);

        boolean occupe = Historique.occupe();
        annuler.setDisable(!Historique.peutAnnuler());
        retablir.setDisable(!Historique.peutRetablir());
        enregistrer.setSelected(!Historique.enPause());

        if (occupe) resume.setText("Envoi en cours…");
        else resume.setText(faites.isEmpty() ? "Rien à annuler"
                : "Annuler : " + faites.get(0));
        // Seuls les resultats de tes actions (annuler, retablir, vider, pause) passent
        // par la ligne d'etat (et donc dans le jeu) ; le suivi automatique reste discret.
        String m = Historique.message();
        if (automatique(m)) {
            suivi.setText(m);
            if (etat.getText() != null && etat.getText().endsWith("…")) etat.setText("");
        }
        else { suivi.setText(""); etat.setText(m); }
        raccourcis.setText(RaccourcisGlobaux.etat());
    }

    /** Message du suivi automatique de la salle (pas le resultat d'une action). Logique pure. */
    static boolean automatique(String m) {
        return m == null || m.startsWith("Enregistré : ") || m.startsWith("Nouvelle salle")
                || m.startsWith("Salle suivie") || m.startsWith("En attente");
    }

    private static ScrollPane defiler(Pane p) {
        ScrollPane sp = new ScrollPane(p);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    // ------------------------------------------------------------ raccourcis

    /**
     * Cmd/Ctrl+Z = annuler ; Cmd/Ctrl+Maj+Z ou Ctrl+Y (Cmd+Y) = retablir.
     * En mode Construction, Cmd/Ctrl+C et Cmd/Ctrl+V copient / collent un calque.
     * Ignore quand le focus est dans un champ texte (ses propres Annuler, Copier, Coller).
     * Ne marche que lorsque cette scene a le focus ; dans le jeu, c'est
     * RaccourcisGlobaux (Habbo au premier plan).
     */
    /** Cle a poser dans getProperties() d'un noeud qui gere lui-meme Cmd+Z. */
    public static final String RACCOURCIS_PROPRES = "atelier.raccourcisPropres";

    public static void installerRaccourcis(Scene scene) {
        if (scene == null) return;
        Historique.demarrer();
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            Node f = scene.getFocusOwner();
            if (f instanceof TextInputControl) return;
            // Un composant qui a son propre Annuler (l'editeur de floor) le garde.
            if (f != null && f.getProperties().containsKey(RACCOURCIS_PROPRES)) return;
            boolean mod = e.isShortcutDown() || e.isControlDown();
            if (!mod || e.isAltDown()) return;
            if (e.getCode() == KeyCode.Z) {
                if (e.isShiftDown()) Historique.retablir(); else Historique.annuler();
                e.consume();
            } else if (e.getCode() == KeyCode.Y && !e.isShiftDown()) {
                Historique.retablir();
                e.consume();
            } else if ((e.getCode() == KeyCode.C || e.getCode() == KeyCode.V) && !e.isShiftDown()
                    && !(f instanceof ComboBoxBase && ((ComboBoxBase<?>) f).isEditable())
                    && !(f instanceof Spinner && ((Spinner<?>) f).isEditable())) {
                // copier / coller un calque, seulement en mode Construction (sinon la touche passe)
                if (RaccourcisGlobaux.toucheCalques(e.getCode() == KeyCode.C)) e.consume();
            }
        });
    }
}

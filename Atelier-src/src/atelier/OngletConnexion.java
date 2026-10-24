package atelier;

import gearth.ui.subforms.connection.ConnectionController;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Reconstruit l'ecran de connexion en une seule colonne.
 *
 * Son FXML d'origine etale les controles sur deux colonnes dans des GridPane
 * imbriques larges de 650 px, avec des positions absolues : dans une fenetre
 * etroite, la moitie sort du cadre. Relacher les largeurs ne suffit pas, il faut
 * refaire la mise en page.
 *
 * On ne recree AUCUN controle : on reprend ceux du controleur, qui sont tous
 * publics, et on les replace dans une colonne. Ses champs pointent sur les memes
 * objets, donc toute sa logique continue de fonctionner — libelles traduits,
 * activation des champs, changements d'etat. Ses libelles (anglais ou
 * franglais) sont montres en francais par l'Atelier : voir FRANCAIS.
 */
public final class OngletConnexion {

    private OngletConnexion() { }

    public static void reconstruire(Tab onglet, ConnectionController c) {
        if (c == null || onglet == null) return;

        // Detacher proprement chaque controle de son parent d'origine.
        for (Node n : new Node[]{
                c.lblInpPort, c.inpPort, c.lblInpHost, c.inpHost,
                c.cbx_autodetect, c.btnConnect,
                c.lblClient, c.rd_flash, c.rd_unity, c.rd_nitro, c.rd_origins,
                c.lblHotelVersion, c.txtfield_hotelversion,
                c.lblStateHead, c.lblState,
                c.lblHost, c.outHost, c.lblPort, c.outPort})
            detacher(n);

        // Les libelles viennent de la bibliotheque de connexion (anglais ou
        // franglais) : on montre leur version francaise, qui suit leurs
        // changements (« Pas connectée » -> « Connectée »).
        traduireDedans(c.btnConnect);
        traduireDedans(c.cbx_autodetect);
        Label etat = traduit(c.lblState);

        VBox col = new VBox(12,
                Ui.bloc("Connexion",
                        champ(traduit(c.lblInpHost), c.inpHost),
                        champ(traduit(c.lblInpPort), c.inpPort),
                        c.cbx_autodetect,
                        c.btnConnect),
                Ui.bloc("Client",
                        Ui.ligne(c.rd_flash, c.rd_unity, c.rd_nitro, c.rd_origins)),
                Ui.bloc("État",
                        champ(traduit(c.lblStateHead), etat),
                        champ(traduit(c.lblHotelVersion), c.txtfield_hotelversion),
                        champ(traduit(c.lblHost), c.outHost),
                        champ(traduit(c.lblPort), c.outPort)));

        col.setFillWidth(true);
        col.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));

        for (Node n : new Node[]{c.inpHost, c.inpPort, c.btnConnect,
                c.txtfield_hotelversion, c.outHost, c.outPort, etat}) {
            if (n instanceof Region) {
                Region r = (Region) n;
                r.setPrefWidth(Region.USE_COMPUTED_SIZE);
                r.setMaxWidth(Double.MAX_VALUE);
            }
        }
        // Le libelle du client n'a plus lieu d'etre : l'intitule du bloc le dit.
        if (c.lblClient != null) { c.lblClient.setVisible(false); c.lblClient.setManaged(false); }

        ScrollPane sp = new ScrollPane(col);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        onglet.setContent(sp);
    }

    /** Libelles de la bibliotheque (francais ou anglais, selon sa langue) -> texte de l'Atelier. */
    private static final java.util.Map<String, String> FRANCAIS = java.util.Map.ofEntries(
            java.util.Map.entry("host", "Adresse"),
            java.util.Map.entry("port", "Port"),
            java.util.Map.entry("hôtel version", "Version de l'hôtel"),
            java.util.Map.entry("hotel version", "Version de l'hôtel"),
            java.util.Map.entry("état de connexion", "État"),
            java.util.Map.entry("connection state", "État"),
            java.util.Map.entry("pas connecté", "Pas connectée"),
            java.util.Map.entry("not connected", "Pas connectée"),
            java.util.Map.entry("connecté", "Connectée"),
            java.util.Map.entry("connected", "Connectée"),
            java.util.Map.entry("waiting for connection", "En attente de connexion"),
            java.util.Map.entry("connecter", "Se connecter"),
            java.util.Map.entry("connect", "Se connecter"),
            java.util.Map.entry("abort", "Annuler"),
            java.util.Map.entry("détection auto", "Détection automatique"),
            java.util.Map.entry("auto-detect", "Détection automatique"),
            java.util.Map.entry("type de client", "Client"),
            java.util.Map.entry("client type", "Client"));

    /** La version de l'Atelier d'un libelle ; inconnu : tel quel, sans les deux-points. */
    static String francais(String t) {
        if (t == null) return "";
        String cle = t.trim();
        if (cle.endsWith(":")) cle = cle.substring(0, cle.length() - 1).trim();
        String f = FRANCAIS.get(cle.toLowerCase(java.util.Locale.ROOT));
        return f != null ? f : Ui.majuscule(cle);
    }

    /**
     * Une etiquette qui montre la traduction de l'originale et suit ses
     * changements (texte, couleur, style). L'originale reste au controleur.
     */
    private static Label traduit(Label orig) {
        if (orig == null) return null;
        Label vu = new Label();
        vu.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> francais(orig.getText()), orig.textProperty()));
        vu.styleProperty().bind(orig.styleProperty());
        vu.textFillProperty().bind(orig.textFillProperty());
        vu.getStyleClass().setAll(orig.getStyleClass());
        orig.getStyleClass().addListener((javafx.collections.ListChangeListener<String>) ch ->
                vu.getStyleClass().setAll(orig.getStyleClass()));
        return vu;
    }

    /**
     * Bouton ou case a cocher : il garde son role (le controleur s'en sert),
     * mais montre sa traduction a la place de son texte.
     */
    private static void traduireDedans(Labeled l) {
        if (l == null) return;
        Label vu = new Label();
        vu.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> francais(l.getText()), l.textProperty()));
        vu.fontProperty().bind(l.fontProperty());
        vu.textFillProperty().bind(l.textFillProperty());
        l.setGraphic(vu);
        l.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    /** Une etiquette a gauche, son controle qui prend le reste de la largeur. */
    private static HBox champ(Label etiquette, Node controle) {
        HBox h = new HBox(8);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        if (etiquette != null) {
            etiquette.setMinWidth(92);
            etiquette.setPrefWidth(92);
            etiquette.setMaxWidth(92);
            etiquette.setWrapText(true);
            h.getChildren().add(etiquette);
        }
        if (controle != null) {
            HBox.setHgrow(controle, Priority.ALWAYS);
            h.getChildren().add(controle);
        }
        return h;
    }

    private static void detacher(Node n) {
        if (n == null) return;
        Parent p = n.getParent();
        if (p instanceof Pane) ((Pane) p).getChildren().remove(n);
    }
}

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
 * activation des champs, changements d'etat.
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

        VBox col = new VBox(12,
                Ui.bloc("Connexion",
                        champ(c.lblInpHost, c.inpHost),
                        champ(c.lblInpPort, c.inpPort),
                        c.cbx_autodetect,
                        c.btnConnect),
                Ui.bloc("Client",
                        Ui.ligne(c.rd_flash, c.rd_unity, c.rd_nitro, c.rd_origins)),
                Ui.bloc("État",
                        champ(c.lblStateHead, c.lblState),
                        champ(c.lblHotelVersion, c.txtfield_hotelversion),
                        champ(c.lblHost, c.outHost),
                        champ(c.lblPort, c.outPort)));

        col.setFillWidth(true);
        col.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));

        for (Node n : new Node[]{c.inpHost, c.inpPort, c.btnConnect,
                c.txtfield_hotelversion, c.outHost, c.outPort, c.lblState}) {
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

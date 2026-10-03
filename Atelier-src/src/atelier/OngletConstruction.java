package atelier;

import javafx.geometry.Insets;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Outils de construction : Escalier, Miroir.
 *
 * Un onglet a deux volets, comme OngletWired : la Navigation de l'Atelier
 * les ressort par leur nom (« Escalier », « Miroir »). Le volet Remplissage
 * (OutilMotif) a ete retire a la demande de l'utilisatrice.
 * Chaque volet porte sa propre ligne d'etat, puisqu'il peut etre extrait seul.
 */
public class OngletConstruction {

    public Tab construire() {
        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        volets.getTabs().addAll(
                new OutilEscalier().construire(),
                new OutilMiroir().construire());
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        Tab t = new Tab("Construction", racine);
        t.setClosable(false);
        return t;
    }
}

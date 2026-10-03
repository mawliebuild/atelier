package atelier;

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Onglet Infos : les outils qui renseignent sans rien changer a la salle.
 *
 *   Liste de courses  ce qu'il faut pour un appart, et ce qui manque
 *
 * « Plan et mesure » et « Compteur » ont ete retires a la demande de
 * l'utilisatrice : la mesure vit dans la petite barre en bas a droite
 * (BarreMesure).
 */
public class OngletInfos {

    public Tab construire() {
        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab courses = new Tab("Liste de courses", defiler(new OutilCourses().construire()));
        courses.setClosable(false);
        volets.getTabs().add(courses);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        Tab t = new Tab("Infos", racine);
        t.setClosable(false);
        return t;
    }

    private static ScrollPane defiler(Pane p) {
        ScrollPane sp = new ScrollPane(p);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }
}

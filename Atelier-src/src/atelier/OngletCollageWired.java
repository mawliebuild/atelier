package atelier;

import gearth.extensions.parsers.HFloorItem;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu Wired : copier puis coller une configuration wired (reglages, positions
 * et mobis qu'ils utilisent). Le moteur est WiredCollage ; ce volet ne fait que
 * choisir QUOI copier : les mobis selectionnes (Option + C ou zone, dans les
 * calques), ou tous les wired de l'appart.
 */
public class OngletCollageWired {

    private Label resume;

    public Tab construire() {
        resume = Ui.valeur("");
        resume.setWrapText(true);

        Button copierSel = Generateur.principal("Copier les wired sélectionnés", () -> {
            Groupes.Selection s = Groupes.selection();
            if (s.sols.isEmpty()) {
                InfoJeu.dire("Sélectionne d'abord des mobis (Option + C puis clic dans le jeu, ou zone).");
                return;
            }
            WiredCollage.copier(new ArrayList<>(s.sols), fenetre());
            plusTard();
        });
        Button copierTout = new Button("Copier tous les wired de l'appart");
        copierTout.setMaxWidth(Double.MAX_VALUE);
        copierTout.setOnAction(e -> {
            List<Integer> ids = new ArrayList<>();
            for (HFloorItem it : Salle.sols())
                if (Wired.estWired(Salle.classe(it.getTypeId(), false))) ids.add(it.getId());
            if (ids.isEmpty()) { InfoJeu.dire("Aucun wired dans cet appart."); return; }
            WiredCollage.copier(ids, fenetre());
            plusTard();
        });
        Button coller = new Button("Coller la config");
        coller.setMaxWidth(Double.MAX_VALUE);
        coller.setOnAction(e -> {
            if (!WiredCollage.aUneCopie()) { InfoJeu.dire("Copie d'abord une config wired."); return; }
            WiredCollage.collerDans(null, fenetre());
        });

        VBox v = new VBox(12,
                Ui.bloc("Copier",
                        copierSel, copierTout,
                        Ui.aide("Copie les réglages des wired, leurs positions et les mobis qu'ils utilisent.")),
                Ui.bloc("Coller",
                        resume, coller,
                        Ui.aide("Aperçu d'abord, puis clique dans le jeu la case du coin haut-gauche de la destination.")));
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        majResume();

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Copier / coller la config", sp);
        t.setClosable(false);
        return t;
    }

    private Window fenetre() {
        Node n = resume;
        return n == null || n.getScene() == null ? null : n.getScene().getWindow();
    }

    private void majResume() {
        String r = WiredCollage.resumeCopie();
        String t = r == null ? "Aucune config copiée." : "Copiée : " + r + ".";
        Platform.runLater(() -> { if (resume != null) resume.setText(t); });
    }

    /** La copie tourne en fond : le resume suit quelques secondes. */
    private void plusTard() {
        Salle.tache("collage-wired-resume", () -> {
            for (int i = 0; i < 30; i++) { Salle.sommeil(1000); majResume(); }
        });
    }
}

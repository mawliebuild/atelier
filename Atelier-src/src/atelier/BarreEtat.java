package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Barre d'etat permanente, visible depuis les trois onglets.
 *
 * Elle porte les compteurs a garder sous les yeux en continu : sols / murs /
 * total pour la salle et pour l'appart charge, plus le modele de salle (dont
 * depend le placement des murs).
 */
public class BarreEtat extends HBox {

    private final Label etat      = new Label("Déconnectée");
    private final Label salle     = new Label("Aucune salle");
    private final Label cptSalle  = new Label("--");
    private final Label cptAppart = new Label("--");
    private final Label ecart     = new Label("");

    public BarreEtat() {
        super(10);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 12, 6, 12));

        etat.setStyle("-fx-font-weight: bold;");
        Region espace = new Region();
        HBox.setHgrow(espace, Priority.ALWAYS);

        getChildren().addAll(
                etat,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                salle,
                espace,
                new Label("Salle"), cptSalle,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new Label("Appart"), cptAppart,
                ecart);
    }

    // --- mise a jour, appelable depuis n'importe quel thread ---

    public void connexion(boolean connectee) {
        sur(() -> etat.setText(connectee ? "Connectée" : "Déconnectée"));
    }

    public void salle(String nom, String modele) {
        sur(() -> salle.setText(nom == null ? "Aucune salle"
                : nom + (modele == null || modele.isEmpty() ? "" : "  ·  " + modele)));
    }

    public void compteursSalle(int sols, int murs) {
        sur(() -> cptSalle.setText(format(sols, murs)));
    }

    public void compteursAppart(int sols, int murs) {
        sur(() -> cptAppart.setText(format(sols, murs)));
    }

    /** null = rien a signaler ; sinon le nombre d'objets qui different. */
    public void ecart(Integer nb) {
        sur(() -> {
            if (nb == null)   ecart.setText("");
            else if (nb == 0) ecart.setText("Identique");
            else              ecart.setText(nb + " à corriger");
        });
    }

    private static String format(int sols, int murs) {
        return sols + " sols · " + murs + " murs · " + (sols + murs);
    }

    private static void sur(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}

package atelier;

import extension.GPresets;
import gearth.extensions.InternalExtensionFormCreator;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Charge l'interface du module Presets et rend son controleur, pour que
 * InternalExtensionFormLauncher puisse le brancher comme extension interne.
 *
 * On ne construit surtout PAS `new GPresets()` a la main : c'est le FXMLLoader
 * qui doit l'instancier, puisque gpresets.fxml declare
 * fx:controller="extension.GPresets" et que son initialize() est appele la.
 */
public class AppartsCreator extends InternalExtensionFormCreator<GPresets> {

    private Parent racine;

    @Override
    public GPresets createForm(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(GPresets.class.getResource("ui/gpresets.fxml"));
        loader.setClassLoader(GPresets.class.getClassLoader());
        // MoteurPresets a la place de GPresets : memes champs @FXML, meme
        // initialize(), mais ses messages dans le jeu sont en francais.
        loader.setControllerFactory(c -> {
            try {
                return c == GPresets.class ? new MoteurPresets() : c.getDeclaredConstructor().newInstance();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        racine = loader.load();

        // L'interface d'origine du module Presets vit dans SA fenetre, masquee par
        // defaut. L'onglet Apparts porte notre propre interface ; un Node ne peut
        // appartenir qu'a un seul graphe de scene, d'ou cette separation nette.
        Scene scene = new Scene(racine);
        java.net.URL css = GPresets.class.getResource("ui/styles.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Presets de l'Atelier (reglages avances)");

        return (GPresets) loader.getController();
    }

    public Parent racine() {
        return racine;
    }

    public static String css() {
        java.net.URL u = GPresets.class.getResource("ui/styles.css");
        return u == null ? null : u.toExternalForm();
    }
}

package atelier;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les fenetres ouvertes « a cote » (photos de la Galerie, apercu d'une
 * capture) : elles se ferment avec l'Atelier, quand on le quitte ou que la
 * connexion au jeu tombe, au lieu de rester seules a l'ecran.
 */
final class FenetresVolantes {

    private FenetresVolantes() { }

    private static final Set<Stage> ouvertes = ConcurrentHashMap.newKeySet();

    /** A appeler a l'ouverture (fil JavaFX). */
    static void suivre(Stage s) {
        ouvertes.add(s);
        s.setOnHidden(e -> ouvertes.remove(s));
    }

    static void fermerToutes() {
        Runnable r = () -> { for (Stage s : ouvertes.toArray(new Stage[0])) try { s.close(); } catch (Throwable ignored) { } ouvertes.clear(); };
        if (Platform.isFxApplicationThread()) r.run(); else try { Platform.runLater(r); } catch (Throwable ignored) { }
    }
}

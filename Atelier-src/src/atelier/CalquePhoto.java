package atelier;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Calque photo de reference : une photo (de la Galerie, en general) posee en
 * transparence par-dessus la salle, pour reproduire ou s'inspirer d'un build.
 *
 * Le client modifie l'affiche (« atelier:calque=<fichier>;<opacite>;<dx>;<dy>;<echelle> ») :
 * coin cale sur la case (0,0) de la salle, decale de dx/dy pixels (zoom 1),
 * il suit le defilement et le zoom du jeu. La photo est copiee dans le
 * dossier personnel (.atelier-calque-<n>.<ext>) : le jeu ne lit que la.
 * Elle reste affichee fenetre fermee (« Masquer » la retire) et revient
 * toute seule quand on change de salle.
 */
final class CalquePhoto {

    private CalquePhoto() { }

    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String PREFIXE = ".atelier-calque-";

    /** Nom du fichier copie (dans le dossier personnel) ; null = pas de photo. */
    private static volatile String fichier = null;
    private static volatile String nomPhoto = "";
    private static volatile boolean visible = false;
    private static volatile int opacite = PREFS.getInt("calque.opacite", 50);
    private static volatile int echelle = PREFS.getInt("calque.echelle", 100);
    private static volatile int dx = PREFS.getInt("calque.dx", 0), dy = PREFS.getInt("calque.dy", 0);

    private static CalqueFenetre fenetre;
    private static Label nom, position;
    private static Button masquer;
    private static volatile boolean suiviLance = false;

    /** Ouvre (ou ramene) la fenetre du calque photo, sous le panneau des calques. */
    static void ouvrir(String css, Window proche) {
        if (fenetre != null && fenetre.ouverte()) { fenetre.montrer(); return; }
        if (!ClientModifie.saitCalque()) {
            InfoJeu.dire("Relance « Lancer l'Atelier » (Habbo fermé) : le jeu doit être mis à jour pour afficher une photo.");
            return;
        }
        CalqueFenetre f = new CalqueFenetre(css, "Photo de référence", proche);
        fenetre = f;

        nom = new Label();
        nom.setWrapText(true);
        Button choisir = CalqueFenetre.bouton("Choisir une photo…", false, () -> choisir(f));

        Slider sOpacite = curseur(10, 90, opacite);
        Label lOpacite = new Label();
        sOpacite.valueProperty().addListener((o, a, b) -> {
            opacite = b.intValue(); lOpacite.setText("Opacité : " + opacite + " %");
            PREFS.putInt("calque.opacite", opacite); envoyer();
        });
        lOpacite.setText("Opacité : " + opacite + " %");

        Slider sEchelle = curseur(25, 300, echelle);
        Label lEchelle = new Label();
        sEchelle.valueProperty().addListener((o, a, b) -> {
            echelle = b.intValue(); lEchelle.setText("Taille : " + echelle + " %");
            PREFS.putInt("calque.echelle", echelle); envoyer();
        });
        lEchelle.setText("Taille : " + echelle + " %");

        position = new Label();
        Button recentrer = CalqueFenetre.bouton("Recentrer", false, () -> { dx = 0; dy = 0; deplace(); });

        // boutons flèches : comme les touches du clavier (Maj-clic : 10 pixels)
        javafx.scene.layout.GridPane croix = new javafx.scene.layout.GridPane();
        croix.setHgap(6); croix.setVgap(6);
        croix.setAlignment(javafx.geometry.Pos.CENTER);
        croix.add(fleche("↑", 0), 1, 0);
        croix.add(fleche("←", 3), 0, 1);
        croix.add(fleche("→", 1), 2, 1);
        croix.add(fleche("↓", 2), 1, 2);

        f.contenu(Ui.bloc("Photo", nom, choisir),
                Ui.bloc("Réglages", lOpacite, sOpacite, lEchelle, sEchelle),
                Ui.bloc("Position", croix, position, recentrer),
                Ui.aide("Flèches (boutons ou clavier) : déplacer la photo d'un pixel (Maj : 10 pixels). "
                        + "Une photo prise au zoom normal du jeu tombe juste à 100 %. "
                        + "Elle reste affichée quand tu fermes la fenêtre : « Masquer » la retire."));
        masquer = CalqueFenetre.bouton("Masquer", false, () -> { visible = !visible; envoyer(); majTextes(); });
        f.boutons(CalqueFenetre.bouton("Fermer", false, f::fermer), masquer);
        f.fleches(new RaccourcisGlobaux.Fleches() {
            @Override public void fleche(int direction, boolean maj) {
                javafx.application.Platform.runLater(() -> pousser(direction, maj));
            }
            @Override public void entree() { }
            @Override public void echap() { javafx.application.Platform.runLater(f::fermer); }
        });
        majTextes();
        f.montrer();
        suivre();
    }

    /** Un bouton flèche : 0 haut, 1 droite, 2 bas, 3 gauche ; Maj-clic = 10 pixels. */
    private static Button fleche(String texte, int direction) {
        Button b = new Button(texte);
        b.getStyleClass().add("fleche");
        b.setPrefSize(46, 30);
        b.setOnMouseClicked(e -> pousser(direction, e.isShiftDown()));
        return b;
    }

    /** Deplace la photo d'un pixel dans la direction (10 avec Maj). */
    private static void pousser(int direction, boolean maj) {
        int pas = maj ? 10 : 1;
        switch (direction) {
            case 0 -> dy -= pas;
            case 1 -> dx += pas;
            case 2 -> dy += pas;
            default -> dx -= pas;
        }
        deplace();
    }

    private static Slider curseur(int min, int max, int val) {
        Slider s = new Slider(min, max, Math.max(min, Math.min(max, val)));
        s.setBlockIncrement(5);
        return s;
    }

    private static void deplace() {
        PREFS.putInt("calque.dx", dx); PREFS.putInt("calque.dy", dy);
        envoyer();
        majTextes();
    }

    private static void majTextes() {
        if (nom != null) nom.setText(fichier == null ? "Aucune photo choisie." : nomPhoto);
        if (position != null) position.setText("Position : " + dx + ", " + dy);
        if (masquer != null) {
            masquer.setText(visible ? "Masquer" : "Afficher");
            masquer.setDisable(fichier == null);
        }
    }

    /** Choix d'une photo, en partant du dossier de la Galerie ; copiee dans le dossier personnel. */
    private static void choisir(CalqueFenetre f) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Photo de référence");
        File dossier = OngletGalerie.dossier();
        if (dossier.isDirectory()) fc.setInitialDirectory(dossier);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif",
                "*.PNG", "*.JPG", "*.JPEG", "*.GIF"));
        File choisie = fc.showOpenDialog(f.stage());
        if (choisie != null) prendre(choisie);
    }

    /** Depuis la Galerie (clic droit) : ouvre la fenetre et prend cette photo. */
    static void utiliser(File photo, String css, Window proche) {
        ouvrir(css, proche);
        if (fenetre != null && fenetre.ouverte()) prendre(photo);
    }

    /** Copie la photo dans le dossier personnel et l'affiche dans le jeu. */
    private static void prendre(File choisie) {
        String ext = extension(choisie.getName());
        if (ext == null) { InfoJeu.dire("Choisis une image PNG, JPG ou GIF."); return; }
        try {
            File maison = Capture.maisonReelle();
            File[] anciens = maison.listFiles((d, n) -> n.startsWith(PREFIXE));
            if (anciens != null) for (File a : anciens) a.delete();
            // nom neuf a chaque photo : le jeu recharge quand le nom change
            File dest = new File(maison, PREFIXE + System.currentTimeMillis() + "." + ext);
            Files.copy(choisie.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Capture.rendre(dest);
            fichier = dest.getName();
            nomPhoto = choisie.getName();
            visible = true;
            envoyer();
            majTextes();
        } catch (Exception e) {
            Journal.debug("Calque photo : copie impossible (" + e + ")");
            InfoJeu.dire("Impossible de lire cette photo.");
        }
    }

    private static String extension(String n) {
        int i = n.lastIndexOf('.');
        if (i < 0) return null;
        String e = n.substring(i + 1).toLowerCase(Locale.ROOT);
        return e.equals("png") || e.equals("jpg") || e.equals("jpeg") || e.equals("gif") ? e : null;
    }

    /** Le message au jeu : la photo et ses reglages, ou rien (photo cachee). */
    static String message() {
        if (!visible || fichier == null) return "atelier:calque=";
        return "atelier:calque=" + fichier + ";" + opacite + ";" + dx + ";" + dy + ";" + echelle;
    }

    private static void envoyer() {
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.dansUneSalle()) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, message(), 0, 0, 0, -1));
    }

    /** Nouvelle salle : le canevas est neuf, la photo affichee est renvoyee. */
    private static synchronized void suivre() {
        if (suiviLance) return;
        suiviLance = true;
        Salle.tache("calque-photo", () -> {
            int vue = -1;
            while (true) {
                Salle.sommeil(1000);
                int s = Salle.dansUneSalle() ? Salle.salleId() : -1;
                if (s == vue) continue;
                if (s > 0 && !Salle.installeeDepuis(3000)) continue;
                vue = s;
                if (s > 0 && visible && fichier != null) envoyer();
            }
        });
    }
}

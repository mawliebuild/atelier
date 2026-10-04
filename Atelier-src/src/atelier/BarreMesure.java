package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Petite barre en bas a droite du jeu, dans le meme gris que la barre du haut :
 *
 *   [salle]           ouvre la fenetre Salle (liste des mobis, prix)
 *   [floor]           entre dans le mode Floor (BarreFloor), ou en sort ; allume pendant le mode
 *   [appareil photo]  prend la photo de l'appart
 *   [regle]               un clic, puis deux cases dans le jeu : le nombre
 *                         de cases entre les deux est dit dans le chat ;
 *   1 234 cases           le nombre total de cases de l'appart, toujours
 *                         affiche, independant de la regle.
 *
 * Pendant la mesure, les deux clics au sol sont retenus : l'avatar ne bouge pas.
 * Visible seulement dans un appart.
 */
public class BarreMesure implements Ancrage.Ancrable {

    private static final double MARGE_DROITE = 12, MARGE_BAS = 64;

    private final Stage stage = new Stage();
    private final Label cases = new Label("—");
    private final Button regle = new Button();
    private Button floorB;
    private final Popup bulle = new Popup();
    private final Label bulleTexte = new Label();

    private volatile boolean actif = false;
    private volatile boolean mesure = false;
    private volatile int[] premier = null;
    private double[] habbo;

    public BarreMesure(String css) {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(AtelierLauncher.NOM);

        Button inventaire = new Button();
        inventaire.setGraphic(Icones.trace(Icones.INVENTAIRE, "icone-barre"));
        inventaire.getStyleClass().add("barre-bouton");
        inventaire.setFocusTraversable(false);
        inventaire.setOnAction(e -> surInventaire.run());
        survol(inventaire, "Inventaire");

        Button salleB = bouton(Icones.SALLE, "Salle", () -> surSalle.run());
        floorB = bouton(Icones.FLOOR, "Mode Floor : éditer les cases dans le jeu", () -> surFloor.run());

        Button photo = new Button();
        photo.setGraphic(Icones.trace(Icones.CAPTURE, "icone-barre"));
        photo.getStyleClass().add("barre-bouton");
        photo.setFocusTraversable(false);
        photo.setOnAction(e -> surPhoto.run());
        survol(photo, "Capture de l'appart");

        regle.setGraphic(Icones.trace(Icones.REGLE, "icone-barre"));
        regle.getStyleClass().add("barre-bouton");
        regle.setFocusTraversable(false);
        regle.setOnAction(e -> basculerMesure());
        survol(regle, "Mesurer : clique deux cases");

        cases.getStyleClass().add("barre-salle");
        HBox texte = new HBox(cases);
        texte.setAlignment(Pos.CENTER_LEFT);
        texte.getStyleClass().add("barre-etat");
        texte.setMinHeight(36);

        Region sep = new Region();
        sep.getStyleClass().add("barre-sep");

        HBox barre = new HBox(5, salleB, photo, floorB, sep, texte);
        barre.getStyleClass().add("barre");
        barre.setAlignment(Pos.CENTER_LEFT);

        // Petite fleche a gauche pour reduire / deplier, comme les autres barres :
        // reduite, il ne reste qu'elle. La barre reste ancree a droite.
        fleche.getStyleClass().add("barre-bouton");
        fleche.setFocusTraversable(false);
        fleche.setStyle("-fx-min-width: 14; -fx-pref-width: 14; -fx-max-width: 14;"
                + " -fx-min-height: 36; -fx-pref-height: 36; -fx-max-height: 36; -fx-padding: 0;");
        fleche.setOnAction(a -> { reduite = !reduite; prefs.putBoolean("barre.mesure.reduite", reduite); appliquerReduite(barre); });
        barre.getChildren().add(0, fleche);
        appliquerReduite(barre);

        bulleTexte.setStyle("-fx-background-color: #ECEAE0; -fx-text-fill: #1D1C19;"
                + " -fx-border-color: #000000; -fx-border-radius: 4; -fx-background-radius: 4;"
                + " -fx-padding: 3 7 3 7; -fx-font-size: 12px; -fx-font-weight: bold;");
        bulle.getContent().add(bulleTexte);

        HBox racine = new HBox(barre);
        racine.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 3 0;");
        Deplacement.activer(stage, racine, "mesure", this::placer);
        Scene scene = new Scene(racine);
        scene.setFill(Color.TRANSPARENT);
        if (css != null) scene.getStylesheets().add(css);
        stage.setScene(scene);
        BarrePremierPlan.menu(stage);
        // repliee ou depliee, la barre garde son bord droit
        stage.widthProperty().addListener((o, a, b) -> { if (stage.isShowing()) placer(); });

        suivre();
    }

    private final Button fleche = new Button();
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("atelier");
    private boolean reduite = prefs.getBoolean("barre.mesure.reduite", false);

    private void appliquerReduite(HBox barre) {
        fleche.setGraphic(Icones.trace(BarreIcones.trace(false, reduite), "icone-barre"));
        for (javafx.scene.Node n : barre.getChildren()) {
            if (n == fleche) continue;
            n.setVisible(!reduite);
            n.setManaged(!reduite);
        }
        survol(fleche, reduite ? "Déplier le menu" : "Réduire le menu");
        bulle.hide();
        if (stage.isShowing()) { stage.sizeToScene(); placer(); }
    }

    public Stage fenetre() { return stage; }

    private Runnable surPhoto = () -> { };
    private Runnable surInventaire = () -> { }, surSalle = () -> { }, surFloor = () -> { };
    public void surSalle(Runnable r) { surSalle = r; }
    public void surFloor(Runnable r) { surFloor = r; }

    /** Mode Floor ouvert : le bouton Floor reste allume (comme la regle pendant une mesure). */
    public void floorActif(boolean on) {
        Runnable r = () -> {
            floorB.getStyleClass().remove("actif");
            if (on) {
                floorB.getStyleClass().add("actif");
                floorB.setStyle("-fx-background-color: #9FD2EA, #7AB6D3, #3E86AC;");
            } else floorB.setStyle("");
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    private Button bouton(String icone, String nom, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icones.trace(icone, "icone-barre"));
        b.getStyleClass().add("barre-bouton");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        survol(b, nom);
        return b;
    }
    /** Ce que fait le bouton Inventaire (ouvrir sa fenetre). */
    public void surInventaire(Runnable r) { surInventaire = r; }
    /** Ce que fait l'appareil photo (par defaut : capturer tout de suite). */
    public void surPhoto(Runnable r) { surPhoto = r; }
    /** Bas de la barre au-dessus du bas du jeu, et marge a droite : BarreIcones s'aligne dessus. */
    public static final double BAS = MARGE_BAS, DROITE = MARGE_DROITE;

    public void actif(boolean a) {
        actif = a;
        if (!a) Platform.runLater(stage::hide);
    }

    // --------------------------------------------------------------- mesure

    public void basculerMesure() {
        mesure = !mesure;
        premier = null;
        mesures++;
        brancher();
        Salle.tache("mesure-dalles", BarreMesure::retirerDalles);
        InfoJeu.consigne(mesure ? "Mesure : clique la première case." : "Mesure annulée.");
        regle.getStyleClass().remove("actif");
        if (mesure) {
            regle.getStyleClass().add("actif");
            regle.setStyle("-fx-background-color: #9FD2EA, #7AB6D3, #3E86AC;");
        } else {
            regle.setStyle("");
        }
        stage.sizeToScene();
        placer();
    }

    private volatile boolean branche = false;

    /** Retient les clics au sol pendant la mesure (meme paquet que le moteur de l'Atelier : MoveAvatar). */
    private synchronized void brancher() {
        if (branche) return;
        GPresets gp = Salle.gp();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOSERVER, "MoveAvatar", m -> {
                if (!mesure) return;
                try {
                    int n = m.getPacket().getBytesLength();
                    if (n < 14 || n > 20) return;
                    HPacket p = m.getPacket();
                    int x = p.readInteger(6), y = p.readInteger(10);
                    if (x < 0 || y < 0) return;
                    m.setBlocked(true);
                    clic(x, y);
                } catch (Throwable ignored) { }
            });
            branche = true;
        } catch (Throwable t) {
            System.err.println("[Atelier] mesure : écoute des clics impossible : " + t);
        }
    }

    private void clic(int x, int y) {
        int[] a = premier;
        if (a == null) {
            premier = new int[]{x, y};
            Salle.tache("mesure-dalles", () -> poserDalle(ID_A, x, y));
            InfoJeu.consigne("Première case (" + x + "," + y + "). Clique la deuxième case.");
            return;
        }
        mesure = false;
        premier = null;
        String r = resultat(a[0], a[1], x, y);
        int tour = ++mesures;
        Salle.tache("mesure-dalles", () -> {
            InfoJeu.consigne("Mesure : " + r + ".");
            // Le resultat est dit : les dalles n'ont plus rien a montrer.
            if (tour == mesures) retirerDalles();
        });
        Platform.runLater(this::regleInactive);
    }

    private void regleInactive() {
        regle.getStyleClass().remove("actif");
        regle.setStyle("");
    }

    // ------------------------------------------------- dalles fictives

    /**
     * Deux dalles magiques 1×1 FICTIVES (chez toi seulement, comme la grille) :
     * la 1re et la 2e case. Identifiants tout en haut de la plage fictive de
     * GrilleCalcul : un clic dessus est bloque, recharger la salle les efface.
     */
    private static final int ID_A = GrilleCalcul.BASE + 2 * GrilleCalcul.TAILLE_CALQUE - 1,
                             ID_B = ID_A - 1;
    private static final long DUREE_DALLES_MS = 10_000;
    private volatile int mesures = 0;

    private static void poserDalle(int id, int x, int y) {
        GPresets gp = Salle.gp();
        if (gp == null) return;
        GrilleReseau.installer();
        GrilleReseau.resoudre();
        GrilleReseau.Modele mod = GrilleReseau.MODELES.get(2);   // dalle magique 1×1
        if (!mod.disponible()) return;
        try {
            GrilleCalcul.Marqueur m = new GrilleCalcul.Marqueur(id, x, y, Math.max(0, Salle.hauteurSol(x, y)), 0);
            if (GrilleReseau.affiches.containsKey(id)) gp.sendToClient(GrilleReseau.retrait(id));
            if (gp.sendToClient(GrilleReseau.ajout(m, mod))) GrilleReseau.affiches.put(id, m);
        } catch (Throwable ignored) { }
    }

    private static void retirerDalles() {
        GPresets gp = Salle.gp();
        for (int id : new int[]{ID_A, ID_B}) {
            if (GrilleReseau.affiches.remove(id) == null || gp == null) continue;
            try { gp.sendToClient(GrilleReseau.retrait(id)); } catch (Throwable ignored) { }
        }
    }

    /** « 7 cases entre (3,4) et (9,4) » ; en diagonale, aussi la zone. Logique pure. */
    static String resultat(int ax, int ay, int bx, int by) {
        int dx = Math.abs(bx - ax), dy = Math.abs(by - ay);
        int entre = Math.max(dx, dy) + 1;                 // cases parcourues, les deux comprises
        String s = entre + (entre > 1 ? " cases" : " case") + " de (" + ax + "," + ay + ") à (" + bx + "," + by + ")";
        if (dx > 0 && dy > 0) s += " · zone " + (dx + 1) + " × " + (dy + 1);
        return s;
    }

    // ----------------------------------------------------- nombre de cases

    /** Cases praticables du plan de la salle ; -1 si inconnu. */
    static int compterCases() {
        FloorState s = Salle.etat();
        if (s == null) return -1;
        try {
            int n = 0, l = s.getFloorplanWidth(), h = s.getFloorplanHeight();
            for (int x = 0; x < l; x++) for (int y = 0; y < h; y++) if (Salle.hauteurSol(x, y) >= 0) n++;
            return n;
        } catch (Throwable t) { return -1; }
    }

    private int salleVue = -1;
    private String planVu = null;

    private void majCases() {
        int n = compterCases();
        cases.setText(n < 0 ? "—" : java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(n)
                + (n > 1 ? " cases" : " case"));
    }

    /** Apparait dans un appart ; le compte suit les changements du plan (editeur de floor). */
    private void suivre() {
        Thread t = new Thread(() -> {
            boolean vu = false;
            while (true) {
                try {
                    boolean dedans = actif && Salle.dansUneSalle();
                    String plan = null;
                    if (dedans) try { plan = Salle.etat().getRoomId() + "/" + Salle.etat().getRawFloorplan().hashCode(); }
                                catch (Throwable ignored) { }
                    final String p = plan;
                    if (dedans != vu || (dedans && p != null && !p.equals(planVu))) {
                        vu = dedans;
                        planVu = p;
                        Platform.runLater(() -> {
                            if (dedans) { brancher(); majCases(); stage.sizeToScene(); stage.show(); placer(); }
                            else { mesure = false; premier = null; regleInactive(); stage.hide(); }
                        });
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-barre-mesure");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ placement

    private void survol(Button b, String texte) {
        b.setOnMouseEntered(e -> {
            bulleTexte.setText(WindowsClavier.texte(texte));
            javafx.geometry.Bounds r = b.localToScreen(b.getBoundsInLocal());
            if (r == null) return;
            bulle.show(stage, r.getMinX(), r.getMinY() - 30);
            bulle.setX(r.getMinX() + r.getWidth() / 2 - bulle.getWidth() / 2);
        });
        b.setOnMouseExited(e -> bulle.hide());
        b.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> bulle.hide());
    }

    @Override
    public void placer(double hx, double hy, double hl, double hh) {
        habbo = new double[]{hx, hy, hl, hh};
        Platform.runLater(this::placer);
    }

    private void placer() {
        double x, y;
        if (habbo != null) {
            x = habbo[0] + habbo[2] - stage.getWidth() - MARGE_DROITE;
            y = habbo[1] + habbo[3] - stage.getHeight() - MARGE_BAS;
        } else {
            javafx.geometry.Rectangle2D e = javafx.stage.Screen.getPrimary().getVisualBounds();
            x = e.getMaxX() - stage.getWidth() - MARGE_DROITE;
            y = e.getMaxY() - stage.getHeight() - MARGE_BAS;
        }
        stage.setX(x + Deplacement.dx("mesure"));      // glissee ailleurs (Deplacement)
        stage.setY(y + Deplacement.dy("mesure"));
    }
}

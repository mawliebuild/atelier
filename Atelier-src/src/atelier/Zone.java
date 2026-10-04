package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * La zone de travail partagee : un rectangle de cases de la salle.
 *
 * Miroir, remplissage, escalier, compteur... agissent tous sur « la zone ».
 * Elle se choisit de deux facons, qui ecrivent ici :
 *   - dans le jeu : demarrerChoix(), puis deux clics au sol (les deux coins) ;
 *   - sur le plan de l'Atelier (Plan) : un glisser.
 *
 * Les ecouteurs sont prevenus sur le fil JavaFX.
 */
public final class Zone {

    private Zone() { }

    private static volatile int x1 = -1, y1 = -1, x2 = -1, y2 = -1;
    private static volatile boolean choixEnCours = false;
    private static volatile HPoint premierCoin;
    /** Le premier coin, en rectangle : une case, ou tout l'encombrement d'un mobi clique. */
    private static volatile int[] premier;
    /** Dernier clic sur un mobi : le deplacement d'avatar qui le suit parfois ne compte pas. */
    private static volatile long clicMobiA = 0;
    /** La grille a ete allumee pour le choix : on l'eteint apres. */
    private static volatile boolean grilleAuto = false;
    private static volatile long choixDebut = 0;
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    static {
        Salle.surClicCase(Zone::clic);
        Salle.surClicMobi(Zone::clicMobi);
    }

    public static boolean definie() { return x1 >= 0; }
    public static int minX() { return Math.min(x1, x2); }
    public static int maxX() { return Math.max(x1, x2); }
    public static int minY() { return Math.min(y1, y2); }
    public static int maxY() { return Math.max(y1, y2); }
    public static int largeur() { return definie() ? maxX() - minX() + 1 : 0; }
    public static int longueur() { return definie() ? maxY() - minY() + 1 : 0; }

    public static boolean contient(int x, int y) {
        return definie() && x >= minX() && x <= maxX() && y >= minY() && y <= maxY();
    }

    /** Les mobis de sol dont la case d'origine est dans la zone. */
    public static List<HFloorItem> mobis() {
        List<HFloorItem> r = new ArrayList<>();
        if (!definie()) return r;
        for (HFloorItem it : Salle.sols())
            if (contient(it.getTile().getX(), it.getTile().getY())) r.add(it);
        return r;
    }

    /**
     * Les mobis de sol qui TOUCHENT la zone (une de leurs cases dedans) : tout
     * ce qu'il y a entre les deux coins, meme un grand mobi qui deborde.
     */
    public static List<HFloorItem> mobisTouches() {
        List<HFloorItem> r = new ArrayList<>();
        if (!definie()) return r;
        for (HFloorItem it : Salle.sols()) {
            int[] e = Salle.emprise(it);
            int x = it.getTile().getX(), y = it.getTile().getY();
            if (x <= maxX() && x + e[0] - 1 >= minX() && y <= maxY() && y + e[1] - 1 >= minY()) r.add(it);
        }
        return r;
    }

    /** Pendant le choix : le premier coin est-il deja clique ? */
    public static boolean premierCoinChoisi() { return choixEnCours && premierCoin != null; }

    public static String texte() {
        if (choixEnCours)
            return premierCoin == null ? "Clique le premier coin dans le jeu..."
                    : "(" + premierCoin.getX() + "," + premierCoin.getY() + ") → clique le second coin...";
        if (!definie()) return "Aucune zone";
        return "(" + minX() + "," + minY() + ") → (" + maxX() + "," + maxY() + ")   ·   "
                + largeur() + " × " + longueur();
    }

    public static void definir(int ax, int ay, int bx, int by) {
        x1 = ax; y1 = ay; x2 = bx; y2 = by;
        choixEnCours = false;
        premierCoin = null; premier = null;
        eteindreGrille();
        prevenir();
    }

    public static void effacer() {
        x1 = y1 = x2 = y2 = -1;
        choixEnCours = false;
        premierCoin = null; premier = null;
        eteindreGrille();
        prevenir();
    }

    /**
     * Les deux prochains clics dans le jeu donnent les coins : une case du sol,
     * ou un mobi (alors tout son encombrement compte). La grille du jeu
     * s'affiche pendant le choix (client modifie), pour viser les cases.
     */
    public static void demarrerChoix() {
        choixEnCours = true;
        premierCoin = null; premier = null;
        long debut = System.currentTimeMillis();
        choixDebut = debut;
        // les mobis laissent passer les clics jusqu'au second coin (2 minutes au plus)
        Salle.tache("zone-clics", () -> {
            GrilleVue.clicsAuSol(true);
            Salle.sommeil(120_000);
            if (choixEnCours && choixDebut == debut) { choixEnCours = false; premierCoin = null; premier = null; eteindreGrille(); prevenir(); }
        });
        if (!GrilleVue.voulue())
            Salle.tache("zone-grille", () -> {
                if (choixEnCours && !GrilleVue.voulue() && ClientModifie.saitGrille() && GrilleVue.montrer(true))
                    grilleAuto = true;
            });
        prevenir();
    }

    private static void eteindreGrille() {
        Salle.tache("zone-clics", () -> GrilleVue.clicsAuSol(false));
        if (!grilleAuto) return;
        grilleAuto = false;
        Salle.tache("zone-grille", () -> GrilleVue.montrer(false));
    }

    public static boolean choixEnCours() { return choixEnCours; }

    public static void ecouter(Runnable r) { ecouteurs.add(r); }

    private static void clic(HPoint c) {
        if (!choixEnCours || c == null) return;
        if (System.currentTimeMillis() - clicMobiA < 600) return;    // suite d'un clic sur un mobi
        coin(new int[]{c.getX(), c.getY(), c.getX(), c.getY()});
    }

    private static void clicMobi(HFloorItem it) {
        if (!choixEnCours || it == null || it.getTile() == null) return;
        clicMobiA = System.currentTimeMillis();
        int[] e = Salle.emprise(it);
        int x = it.getTile().getX(), y = it.getTile().getY();
        coin(new int[]{x, y, x + e[0] - 1, y + e[1] - 1});
    }

    /** Un coin (rectangle) : le premier est retenu, le second ferme la zone (les deux rectangles compris). */
    private static synchronized void coin(int[] r) {
        if (!choixEnCours) return;
        int[] p = premier;
        if (p == null) {
            premier = r;
            premierCoin = new HPoint(r[0], r[1]);
            prevenir();
            return;
        }
        definir(Math.min(p[0], r[0]), Math.min(p[1], r[1]), Math.max(p[2], r[2]), Math.max(p[3], r[3]));
    }

    private static void prevenir() {
        Platform.runLater(() -> {
            for (Runnable r : ecouteurs) {
                try { r.run(); }
                catch (Throwable t) { System.err.println("[Atelier] zone : écouteur : " + t); }
            }
        });
    }

    /**
     * Petit bloc d'interface reutilisable : la zone en texte, « Choisir dans
     * le jeu » et « Effacer ». Chaque outil qui travaille sur la zone le pose
     * en haut de son volet.
     */
    public static javafx.scene.layout.VBox bloc() {
        javafx.scene.control.Label l = Ui.valeur(texte());
        javafx.scene.control.Button choisir = new javafx.scene.control.Button("Choisir dans le jeu");
        choisir.setOnAction(e -> demarrerChoix());
        javafx.scene.control.Button eff = new javafx.scene.control.Button("Effacer");
        eff.setOnAction(e -> effacer());
        ecouter(() -> l.setText(texte()));
        return Ui.bloc("Zone", l, Ui.ligne(choisir, eff));
    }
}

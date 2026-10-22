package atelier;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Le cadre de recadrage, pose par-dessus une image affichee (taille fixe a
 * l'ecran) : on trace un rectangle, puis on le deplace ou on le redimensionne
 * par ses bords et ses coins. L'exterieur est assombri. La zone est rendue en
 * pixels de l'image reelle (l'affichage peut etre reduit).
 */
final class Recadreur extends Pane {

    private static final int GAUCHE = 1, DROITE = 2, HAUT = 4, BAS = 8, DEPLACER = 16;
    /** Distance (en pixels d'ecran) a laquelle un bord ou un coin se prend. */
    private static final double TOLERANCE = 7, POIGNEE = 8;

    private final Runnable changement;
    private double echelle = 1, reelleL = 1, reelleH = 1;
    /** Cadre en pixels de l'image reelle (cx0 < cx1, cy0 < cy1) ; aCadre faux tant qu'aucun n'est trace. */
    private double cx0, cy0, cx1, cy1;
    private boolean aCadre = false;
    private int prise;
    private double appuiX, appuiY;
    private final double[] cadreDep = new double[4];

    private final Rectangle[] ombres = new Rectangle[4];
    private final Rectangle cadreR = new Rectangle();
    private final Rectangle[] poignees = new Rectangle[8];

    /** changement : appele a chaque modification du cadre (pour afficher ses dimensions). */
    Recadreur(Runnable changement) {
        this.changement = changement;
        setPickOnBounds(true);
        setCursor(Cursor.CROSSHAIR);
        for (int i = 0; i < ombres.length; i++) {
            ombres[i] = new Rectangle();
            ombres[i].setFill(Color.rgb(0, 0, 0, 0.55));
            getChildren().add(ombres[i]);
        }
        cadreR.setFill(Color.TRANSPARENT);
        cadreR.setStroke(Color.rgb(255, 255, 255, 0.95));
        cadreR.setStrokeWidth(1.5);
        cadreR.setStrokeType(javafx.scene.shape.StrokeType.INSIDE);
        getChildren().add(cadreR);
        for (int i = 0; i < poignees.length; i++) {
            poignees[i] = new Rectangle(POIGNEE, POIGNEE);
            poignees[i].setFill(Color.WHITE);
            poignees[i].setStroke(Color.rgb(30, 30, 30, 0.8));
            poignees[i].setStrokeWidth(1);
            getChildren().add(poignees[i]);
        }
        for (Node n : getChildren()) n.setMouseTransparent(true);
        setOnMousePressed(e -> { appui(e.getX(), e.getY()); e.consume(); });
        setOnMouseDragged(e -> { glisser(e.getX(), e.getY()); e.consume(); });
        setOnMouseReleased(e -> { setCursor(curseur(prise(e.getX(), e.getY()))); e.consume(); });
        setOnMouseMoved(e -> setCursor(curseur(prise(e.getX(), e.getY()))));
        dessiner();
    }

    /** L'image affichee fait largeur x hauteur a l'ecran pour reelleL x reelleH pixels ; le cadre est efface. */
    void image(double largeur, double hauteur, int reelleL, int reelleH) {
        this.reelleL = Math.max(1, reelleL);
        this.reelleH = Math.max(1, reelleH);
        this.echelle = largeur / this.reelleL;
        setMinSize(largeur, hauteur);
        setPrefSize(largeur, hauteur);
        setMaxSize(largeur, hauteur);
        effacer();
    }

    void effacer() {
        aCadre = false;
        dessiner();
        changement.run();
    }

    boolean aCadre() { return aCadre; }

    /** Le cadre trace, en pixels de l'image : {x, y, largeur, hauteur} ; null sans cadre. */
    int[] zone() {
        if (!aCadre) return null;
        int x = (int) Math.round(cx0), y = (int) Math.round(cy0);
        return new int[]{x, y, (int) Math.max(1, Math.round(cx1) - x), (int) Math.max(1, Math.round(cy1) - y)};
    }

    /** Le cadre couvre-t-il toute l'image ? */
    boolean toutLImage() {
        return aCadre && cx0 <= 0 && cy0 <= 0 && cx1 >= reelleL && cy1 >= reelleH;
    }

    // ------------------------------------------------------------ dessin

    private void dessiner() {
        for (Node n : getChildren()) n.setVisible(aCadre);
        if (!aCadre) return;
        double w = getPrefWidth(), h = getPrefHeight();
        double x0 = cx0 * echelle, y0 = cy0 * echelle, x1 = cx1 * echelle, y1 = cy1 * echelle;
        placer(ombres[0], 0, 0, w, y0);
        placer(ombres[1], 0, y1, w, h - y1);
        placer(ombres[2], 0, y0, x0, y1 - y0);
        placer(ombres[3], x1, y0, w - x1, y1 - y0);
        placer(cadreR, x0, y0, x1 - x0, y1 - y0);
        double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        double[][] pts = {{x0, y0}, {mx, y0}, {x1, y0}, {x1, my}, {x1, y1}, {mx, y1}, {x0, y1}, {x0, my}};
        for (int i = 0; i < poignees.length; i++) {
            poignees[i].setX(pts[i][0] - POIGNEE / 2);
            poignees[i].setY(pts[i][1] - POIGNEE / 2);
        }
    }

    private static void placer(Rectangle r, double x, double y, double w, double h) {
        r.setX(x);
        r.setY(y);
        r.setWidth(Math.max(0, w));
        r.setHeight(Math.max(0, h));
    }

    // ------------------------------------------------------------ souris

    private double[] versImage(double x, double y, boolean borne) {
        double ix = Math.round(x / echelle), iy = Math.round(y / echelle);
        if (borne) { ix = borne(ix, 0, reelleL); iy = borne(iy, 0, reelleH); }
        return new double[]{ix, iy};
    }

    private static double borne(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private int prise(double x, double y) {
        if (!aCadre) return 0;
        double c0 = cx0 * echelle, c1 = cy0 * echelle, c2 = cx1 * echelle, c3 = cy1 * echelle;
        boolean dansV = y >= c1 - TOLERANCE && y <= c3 + TOLERANCE;
        boolean dansH = x >= c0 - TOLERANCE && x <= c2 + TOLERANCE;
        int p = 0;
        double dg = Math.abs(x - c0), dd = Math.abs(x - c2), dh = Math.abs(y - c1), db = Math.abs(y - c3);
        if (dansV && Math.min(dg, dd) <= TOLERANCE) p |= dg <= dd ? GAUCHE : DROITE;
        if (dansH && Math.min(dh, db) <= TOLERANCE) p |= dh <= db ? HAUT : BAS;
        if (p == 0 && x > c0 && x < c2 && y > c1 && y < c3) p = DEPLACER;
        return p;
    }

    private static Cursor curseur(int p) {
        switch (p) {
            case GAUCHE | HAUT: return Cursor.NW_RESIZE;
            case DROITE | BAS: return Cursor.SE_RESIZE;
            case DROITE | HAUT: return Cursor.NE_RESIZE;
            case GAUCHE | BAS: return Cursor.SW_RESIZE;
            case GAUCHE: case DROITE: return Cursor.H_RESIZE;
            case HAUT: case BAS: return Cursor.V_RESIZE;
            case DEPLACER: return Cursor.MOVE;
            default: return Cursor.CROSSHAIR;
        }
    }

    private void appui(double x, double y) {
        prise = prise(x, y);
        double[] p = versImage(x, y, prise == 0);
        appuiX = p[0];
        appuiY = p[1];
        cadreDep[0] = cx0; cadreDep[1] = cy0; cadreDep[2] = cx1; cadreDep[3] = cy1;
        if (prise == 0) { aCadre = false; dessiner(); changement.run(); }
    }

    private void glisser(double x, double y) {
        if (prise == 0) {
            double[] p = versImage(x, y, true);
            cx0 = Math.min(appuiX, p[0]); cx1 = Math.max(appuiX, p[0]);
            cy0 = Math.min(appuiY, p[1]); cy1 = Math.max(appuiY, p[1]);
            aCadre = cx1 - cx0 >= 1 && cy1 - cy0 >= 1;
        } else if (prise == DEPLACER) {
            double[] p = versImage(x, y, false);
            double w = cadreDep[2] - cadreDep[0], h = cadreDep[3] - cadreDep[1];
            cx0 = borne(cadreDep[0] + p[0] - appuiX, 0, reelleL - w);
            cy0 = borne(cadreDep[1] + p[1] - appuiY, 0, reelleH - h);
            cx1 = cx0 + w;
            cy1 = cy0 + h;
        } else {
            double[] p = versImage(x, y, true);
            cx0 = cadreDep[0]; cy0 = cadreDep[1]; cx1 = cadreDep[2]; cy1 = cadreDep[3];
            if ((prise & GAUCHE) != 0) cx0 = borne(p[0], 0, cx1 - 1);
            if ((prise & DROITE) != 0) cx1 = borne(p[0], cx0 + 1, reelleL);
            if ((prise & HAUT) != 0) cy0 = borne(p[1], 0, cy1 - 1);
            if ((prise & BAS) != 0) cy1 = borne(p[1], cy0 + 1, reelleH);
        }
        dessiner();
        changement.run();
    }
}

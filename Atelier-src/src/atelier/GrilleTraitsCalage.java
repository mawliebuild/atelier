package atelier;

import java.util.*;

/**
 * Logique PURE de la grille en traits (GrilleTraits) : aucun JavaFX, aucun
 * reseau, aucun natif. Testee a part.
 *
 * PROJECTION (client Habbo). L = largeur d'une case a l'ecran (64 points au
 * zoom 1, 32 au zoom 0,5). Un coin de case (cx, cy) a la hauteur h :
 *
 *     ecranX = ox + (cx - cy) · L/2
 *     ecranY = oy + (cx + cy) · L/4  -  h · RELIEF · L
 *
 * RELIEF = 1/2 : une unite de hauteur de sol (« 1 » dans le plan) = 1,0 de
 * hauteur de pile = 32 points au zoom 1, comme un bloc 1×1 de hauteur 1.
 * (A verifier en jeu sur un plan a etages : c'est la seule hypothese.)
 *
 * Le centre de la case (x, y) est le coin (x+½, y+½). L'origine (ox, oy) est
 * en points, PAR RAPPORT AU COIN HAUT-GAUCHE DE LA FENETRE HABBO (barre de
 * titre comprise) : deplacer la fenetre ne casse pas le calage ; deplacer la
 * vue dans le jeu, ou changer de zoom, si.
 *
 * CALAGE. Chaque clic au sol donne une paire case ↔ position de la souris.
 * Le modele est LINEAIRE en (ox, oy, L) : moindres carres directs. Comme on
 * clique n'importe ou dans le losange, chaque paire a jusqu'a ±L/2 en x et
 * ±L/4 en y d'erreur : on accroche L a 32 ou 64 quand il en est proche, et
 * avec L connu un seul clic suffit a retrouver l'origine.
 *
 * SUIVI CONTINU (Suivi) : a chaque clic pour marcher, le calage s'affine. Un
 * clic loin de la grille actuelle (la vue a bouge, ou clic sur un mobi haut)
 * donne un calage PROVISOIRE affiche aussitot ; le clic suivant tranche :
 * coherent avec le provisoire = la vue a bouge ; coherent avec l'ancien = le
 * clic precedent etait une erreur, on revient en arriere.
 */
final class GrilleTraitsCalage {

    private GrilleTraitsCalage() { }

    static final double RELIEF = 0.5;
    /** Largeurs de case connues (zoom 0,5 et 1). */
    static final double[] LARGEURS = {32, 64};
    /** Ecart relatif sous lequel on accroche L a une largeur connue. */
    static final double ACCROCHE = 0.25;
    /** Ecart (en « losanges ») au-dela duquel un clic ne colle plus au calage. */
    static final double SEUIL = 1.5;
    /** Etalement minimal (en cases) pour estimer L soi-meme. */
    static final double ETALEMENT_MIN = 4;

    // ------------------------------------------------------------ calage

    static final class Calage {
        final double l, ox, oy;
        Calage(double l, double ox, double oy) { this.l = l; this.ox = ox; this.oy = oy; }

        /** Coin (cx, cy) a la hauteur h -> {x, y} ecran (relatif a la fenetre Habbo). */
        double[] coin(double cx, double cy, double h) {
            return new double[]{ox + (cx - cy) * l / 2, oy + (cx + cy) * l / 4 - h * RELIEF * l};
        }
        double[] centre(int x, int y, double h) { return coin(x + 0.5, y + 0.5, h); }

        double zoom() { return l / 64; }

        @Override public String toString() {
            return String.format(Locale.ROOT, "L=%.2f o=(%.1f, %.1f)", l, ox, oy);
        }
        String texte() { return String.format(Locale.ROOT, "%.4f;%.3f;%.3f", l, ox, oy); }
        static Calage lire(String s) {
            try {
                String[] p = s.split(";");
                Calage c = new Calage(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                return c.l > 4 && c.l < 400 ? c : null;
            } catch (Throwable t) { return null; }
        }
    }

    /** Une paire : case cliquee (hauteur de son sol) et souris a l'ecran (relative a la fenetre Habbo). */
    static final class Point {
        final int x, y; final double h, sx, sy;
        Point(int x, int y, double h, double sx, double sy) { this.x = x; this.y = y; this.h = h; this.sx = sx; this.sy = sy; }
        double a() { return (x - y) / 2.0; }
        double b() { return (x + y + 1) / 4.0 - h * RELIEF; }
        @Override public String toString() { return "(" + x + "," + y + " h" + h + " @" + sx + "," + sy + ")"; }
    }

    /** L accroche a 32 / 64 si proche, sinon tel quel. */
    static double accrocher(double l) {
        for (double k : LARGEURS) if (Math.abs(l / k - 1) < ACCROCHE) return k;
        return l;
    }

    /** Etalement des cases en coordonnees (a, b), equivalent en cases. */
    static double etalement(List<Point> pts) {
        if (pts.size() < 2) return 0;
        double ma = 0, mb = 0;
        for (Point p : pts) { ma += p.a(); mb += p.b(); }
        ma /= pts.size(); mb /= pts.size();
        double v = 0;
        for (Point p : pts) v += sq(p.a() - ma) + sq(p.b() - mb);
        // a et b valent une demi- et un quart de case : on ramene en « cases » (diagonale).
        return 2 * Math.sqrt(v / pts.size()) * 2;
    }

    /**
     * Moindres carres. lConnue != null : L impose, on ne cherche que l'origine
     * (un point suffit). Sinon L libre (au moins deux cases differentes et
     * assez eloignees), puis accroche a 32 / 64 et origine recalculee.
     * null si impossible.
     */
    static Calage resoudre(List<Point> pts, Double lConnue) {
        if (pts == null || pts.isEmpty()) return null;
        int n = pts.size();
        double ma = 0, mb = 0, mx = 0, my = 0;
        for (Point p : pts) { ma += p.a(); mb += p.b(); mx += p.sx; my += p.sy; }
        ma /= n; mb /= n; mx /= n; my /= n;
        double l;
        if (lConnue != null) l = lConnue;
        else {
            double num = 0, den = 0;
            for (Point p : pts) {
                double da = p.a() - ma, db = p.b() - mb;
                num += da * (p.sx - mx) + db * (p.sy - my);
                den += da * da + db * db;
            }
            if (den < 1e-9) return null;
            l = num / den;
            if (!(l > 8 && l < 300)) return null;
            l = accrocher(l);
        }
        return new Calage(l, mx - l * ma, my - l * mb);
    }

    /**
     * Distance d'un clic au centre predit de sa case, en « losanges » :
     * |dx|/(L/2) + |dy|/(L/4). ≤ 1 = le clic tombe dans la case.
     */
    static double ecart(Calage c, Point p) {
        double[] e = c.centre(p.x, p.y, p.h);
        return Math.abs(p.sx - e[0]) / (c.l / 2) + Math.abs(p.sy - e[1]) / (c.l / 4);
    }

    /** Plus grand ecart parmi des points (qualite du calage). */
    static double pire(Calage c, List<Point> pts) {
        double m = 0;
        for (Point p : pts) m = Math.max(m, ecart(c, p));
        return m;
    }

    // ------------------------------------------------------------ suivi

    /** Ce qui s'est passe a l'ajout d'un clic. */
    enum Evenement { AUCUN, PREMIER, AFFINE, PROVISOIRE, DEPLACE, ZOOM, ANNULE }

    /**
     * Calage qui se corrige a chaque clic. lIndice : largeur deja connue (salle
     * precedente, preferences) pour caler des le premier clic ; null sinon.
     */
    static final class Suivi {
        static final int GARDES = 30;

        private Calage cal, prov;
        private final List<Point> pts = new ArrayList<>();
        private final List<Point> provPts = new ArrayList<>();
        private Double lIndice;

        Suivi(Calage depart, Double lIndice) {
            this.cal = depart;
            this.lIndice = depart != null ? Double.valueOf(depart.l) : lIndice;
        }

        /** Le calage a afficher (le provisoire s'il y en a un), ou null. */
        Calage calage() { return prov != null ? prov : cal; }
        boolean provisoire() { return prov != null; }
        int points() { return prov != null ? provPts.size() : pts.size(); }

        /** Oublie tout (nouvelle salle sans calage memorise), garde l'indice de largeur. */
        void reinitialiser(Calage depart) {
            Calage c = calage();
            if (c != null) lIndice = c.l;
            cal = depart; prov = null; pts.clear(); provPts.clear();
            if (depart != null) lIndice = depart.l;
        }

        Evenement ajouter(Point p) {
            if (cal == null) {
                pts.add(p);
                Calage c = resoudreSuivi(pts, lIndice);
                if (c == null) return Evenement.AUCUN;
                cal = c;
                return Evenement.PREMIER;
            }
            if (prov != null) {
                boolean colleProv = ecart(prov, p) <= SEUIL, colleAncien = ecart(cal, p) <= SEUIL;
                if (colleProv && (!colleAncien || ecart(prov, p) <= ecart(cal, p))) {
                    provPts.add(p);
                    pts.clear(); pts.addAll(provPts);
                    cal = resoudreSuivi(pts, prov.l);
                    prov = null; provPts.clear();
                    return Evenement.DEPLACE;
                }
                if (colleAncien) {
                    prov = null; provPts.clear();
                    ajouterCoherent(p);
                    return Evenement.ANNULE;
                }
                // Ni l'un ni l'autre : changement de zoom ? On essaie L libre sur les deux derniers.
                Point avant = provPts.get(provPts.size() - 1);
                Calage libre = resoudre(List.of(avant, p), null);
                if (libre != null && etalement(List.of(avant, p)) >= 2
                        && ecart(libre, avant) <= SEUIL && ecart(libre, p) <= SEUIL && libre.l != cal.l) {
                    pts.clear(); pts.add(avant); pts.add(p);
                    cal = libre; prov = null; provPts.clear();
                    return Evenement.ZOOM;
                }
                provPts.clear(); provPts.add(p);
                prov = resoudre(provPts, cal.l);
                return Evenement.PROVISOIRE;
            }
            if (ecart(cal, p) <= SEUIL) { ajouterCoherent(p); return Evenement.AFFINE; }
            provPts.clear(); provPts.add(p);
            prov = resoudre(provPts, cal.l);
            return Evenement.PROVISOIRE;
        }

        private void ajouterCoherent(Point p) {
            pts.add(p);
            while (pts.size() > GARDES) pts.remove(0);
            Calage c = resoudreSuivi(pts, cal.l);
            if (c != null) cal = c;
        }

        /**
         * L libre si les cases sont assez etalees (et le resultat coherent),
         * sinon L connu ; null si ni l'un ni l'autre.
         */
        private static Calage resoudreSuivi(List<Point> pts, Double lConnue) {
            if (pts.size() >= 2 && etalement(pts) >= ETALEMENT_MIN) {
                Calage libre = resoudre(pts, null);
                if (libre != null && pire(libre, pts) <= SEUIL) return libre;
            }
            return lConnue == null ? null : resoudre(pts, lConnue);
        }
    }

    // ------------------------------------------------------------ traits

    /** Un trait entre deux coins (cx, cy), a la hauteur h. */
    static final class Segment {
        final int x1, y1, x2, y2, h;
        Segment(int x1, int y1, int x2, int y2, int h) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.h = h; }
        @Override public String toString() { return "(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")h" + h; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Segment)) return false;
            Segment s = (Segment) o;
            return x1 == s.x1 && y1 == s.y1 && x2 == s.x2 && y2 == s.y2 && h == s.h;
        }
        @Override public int hashCode() { return Objects.hash(x1, y1, x2, y2, h); }
    }

    /** zone = {x1, y1, x2, y2} (bornes comprises) ou null pour tout le plan. */
    static boolean garde(int[][] plan, int x, int y, int[] zone) {
        if (x < 0 || y < 0 || x >= plan.length || y >= plan[x].length || plan[x][y] < 0) return false;
        return zone == null || (x >= zone[0] && x <= zone[2] && y >= zone[1] && y <= zone[3]);
    }

    /**
     * Les traits a tracer : chaque arete de case une seule fois. Une arete
     * entre deux cases de meme hauteur est tracee une fois ; entre deux
     * hauteurs differentes (relief), une fois a chaque hauteur. Sans relief,
     * une arete partagee = un trait (h = la plus haute des deux cases, pour la
     * couleur ; c'est le dessin qui le pose a plat).
     * plan[x][y] = hauteur, -1 = pas de case.
     */
    static List<Segment> segments(int[][] plan, int[] zone, boolean relief) {
        List<Segment> l = new ArrayList<>();
        if (plan == null) return l;
        int w = plan.length, lo = 0;
        for (int[] c : plan) lo = Math.max(lo, c.length);
        for (int x = 0; x <= w; x++)
            for (int y = 0; y <= lo; y++) {
                // Arete « le long de x » : coin (x,y) -> (x+1,y), entre les cases (x,y-1) et (x,y).
                if (x < w) arete(l, plan, zone, relief, x, y, x + 1, y, x, y - 1, x, y);
                // Arete « le long de y » : coin (x,y) -> (x,y+1), entre les cases (x-1,y) et (x,y).
                if (y < lo) arete(l, plan, zone, relief, x, y, x, y + 1, x - 1, y, x, y);
            }
        return l;
    }

    private static void arete(List<Segment> l, int[][] plan, int[] zone, boolean relief,
                              int cx1, int cy1, int cx2, int cy2, int ax, int ay, int bx, int by) {
        boolean a = garde(plan, ax, ay, zone), b = garde(plan, bx, by, zone);
        if (!a && !b) return;
        int ha = a ? plan[ax][ay] : -1, hb = b ? plan[bx][by] : -1;
        if (!relief) { l.add(new Segment(cx1, cy1, cx2, cy2, Math.max(ha, hb))); return; }
        if (a) l.add(new Segment(cx1, cy1, cx2, cy2, ha));
        if (b && hb != ha) l.add(new Segment(cx1, cy1, cx2, cy2, hb));
    }

    /** Les cases gardees (pour les coordonnees et le remplissage) : {x, y, h}. */
    static List<int[]> cases(int[][] plan, int[] zone) {
        List<int[]> l = new ArrayList<>();
        if (plan == null) return l;
        for (int x = 0; x < plan.length; x++)
            for (int y = 0; y < plan[x].length; y++)
                if (garde(plan, x, y, zone)) l.add(new int[]{x, y, plan[x][y]});
        return l;
    }

    /** Une couleur par hauteur (teinte en degres), stable et bien separee. */
    static double teinte(int h) { return ((h * 137.508) % 360 + 360) % 360; }

    private static double sq(double v) { return v * v; }
}

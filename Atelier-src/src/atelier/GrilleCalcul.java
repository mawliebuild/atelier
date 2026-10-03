package atelier;

import java.util.*;

/**
 * Logique PURE de la grille dans le jeu (aucun JavaFX, aucun reseau) : quelles
 * cases recoivent un marqueur, a quelle altitude, de quelle couleur, avec quel
 * identifiant fictif ; et la difference entre ce qui est affiche et ce qu'on
 * veut afficher (pour n'envoyer que le necessaire).
 *
 * Identifiants fictifs : une plage tout en haut des entiers, que Habbo
 * n'attribue pas aux vrais mobis (on verifie quand meme la salle avant
 * d'envoyer). L'identifiant depend de la case et du calque : la meme case
 * garde le meme identifiant, ce qui rend la mise a jour simple.
 */
final class GrilleCalcul {

    private GrilleCalcul() { }

    /** Debut de la plage reservee : 2 140 000 000 .. 2 140 524 287. */
    static final int BASE = 2_140_000_000;
    static final int COTE = 512;
    static final int TAILLE_CALQUE = COTE * COTE;

    /** Calques : la grille elle-meme, et l'apercu des changements de l'editeur de floor. */
    static final int GRILLE = 0, APERCU = 1;

    static boolean estFictif(int id) { return id >= BASE && id < BASE + 2 * TAILLE_CALQUE; }

    static int id(int calque, int x, int y) {
        if (x < 0 || y < 0 || x >= COTE || y >= COTE) throw new IllegalArgumentException("case hors limites");
        return BASE + calque * TAILLE_CALQUE + x * COTE + y;
    }

    static int[] caseDe(int id) {
        int r = (id - BASE) % TAILLE_CALQUE;
        return new int[]{r / COTE, r % COTE};
    }

    /** Un marqueur a afficher : case, altitude, variante de couleur. */
    static final class Marqueur {
        final int id, x, y, variante;
        final double z;
        Marqueur(int id, int x, int y, double z, int variante) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.variante = variante;
        }
        boolean meme(Marqueur o) {
            return o != null && o.id == id && o.variante == variante && Math.abs(o.z - z) < 1e-6;
        }
        @Override public String toString() { return "(" + x + "," + y + " z" + z + " v" + variante + ")"; }
    }

    /** Un mobi de la salle, reduit a ce qui compte pour la pile. */
    static final class Pose {
        final int x, y, ex, ey;
        final double haut;
        Pose(int x, int y, int ex, int ey, double z, double hauteur) {
            this.x = x; this.y = y; this.ex = Math.max(1, ex); this.ey = Math.max(1, ey);
            this.haut = z + Math.max(0, hauteur);
        }
    }

    /** Reglages de la grille. */
    static final class Reglages {
        boolean zoneSeulement;
        int zx1, zy1, zx2, zy2;        // inclus
        boolean damier;                // une case sur deux
        boolean couleurHauteur;        // variante = hauteur
        int variantes = 1;             // nombre de variantes de couleur disponibles
        boolean dessusMobis;           // poser sur le haut de la pile plutot que sur le sol
    }

    /**
     * Les marqueurs de la grille. plan[x][y] = hauteur du sol, -1 = pas de case.
     */
    static List<Marqueur> grille(int[][] plan, Reglages r, List<Pose> mobis) {
        List<Marqueur> l = new ArrayList<>();
        if (plan == null) return l;
        double[][] dessus = r.dessusMobis ? piles(plan, mobis) : null;
        for (int x = 0; x < plan.length && x < COTE; x++)
            for (int y = 0; y < plan[x].length && y < COTE; y++) {
                int h = plan[x][y];
                if (h < 0) continue;
                if (r.zoneSeulement && (x < Math.min(r.zx1, r.zx2) || x > Math.max(r.zx1, r.zx2)
                        || y < Math.min(r.zy1, r.zy2) || y > Math.max(r.zy1, r.zy2))) continue;
                if (r.damier && ((x + y) & 1) != 0) continue;
                double z = h;
                if (dessus != null && dessus[x][y] > z) z = dessus[x][y];
                int v = r.couleurHauteur ? variante(h, r.variantes) : 0;
                l.add(new Marqueur(id(GRILLE, x, y), x, y, z, v));
            }
        return l;
    }

    static int variante(int hauteur, int variantes) {
        if (variantes <= 1) return 0;
        return Math.floorMod(hauteur, variantes);
    }

    /** Haut de la pile de mobis sur chaque case (0 si aucun). */
    static double[][] piles(int[][] plan, List<Pose> mobis) {
        int w = plan.length, l = w == 0 ? 0 : plan[0].length;
        double[][] d = new double[w][l];
        if (mobis == null) return d;
        for (Pose p : mobis)
            for (int x = p.x; x < p.x + p.ex; x++)
                for (int y = p.y; y < p.y + p.ey; y++)
                    if (x >= 0 && y >= 0 && x < w && y < l && p.haut > d[x][y]) d[x][y] = p.haut;
        return d;
    }

    /** Variantes de l'apercu des changements de l'editeur. */
    static final int AJOUTEE = 0, MONTEE = 1, DESCENDUE = 2, SUPPRIMEE = 3;

    /**
     * Apercu : un marqueur sur chaque case que l'editeur va changer, pose a la
     * NOUVELLE hauteur (a l'ancienne pour une case supprimee). variante :
     * AJOUTEE / MONTEE / DESCENDUE / SUPPRIMEE.
     */
    static List<Marqueur> apercu(int[][] avant, int[][] apres) {
        List<Marqueur> l = new ArrayList<>();
        int w = Math.max(avant == null ? 0 : avant.length, apres == null ? 0 : apres.length);
        for (int x = 0; x < w && x < COTE; x++) {
            int la = Math.max(lon(avant, x), lon(apres, x));
            for (int y = 0; y < la && y < COTE; y++) {
                int a = at(avant, x, y), n = at(apres, x, y);
                if (a == n) continue;
                int v; double z;
                if (a < 0) { v = AJOUTEE; z = n; }
                else if (n < 0) { v = SUPPRIMEE; z = a; }
                else { v = n > a ? MONTEE : DESCENDUE; z = n; }
                l.add(new Marqueur(id(APERCU, x, y), x, y, z, v));
            }
        }
        return l;
    }

    private static int lon(int[][] p, int x) { return p == null || x >= p.length ? 0 : p[x].length; }
    private static int at(int[][] p, int x, int y) { return p == null || x >= p.length || y >= p[x].length ? -1 : p[x][y]; }

    /** Ce qu'il faut envoyer pour passer de « affiche » a « voulu ». */
    static final class Diff {
        final List<Integer> retirer = new ArrayList<>();
        final List<Marqueur> ajouter = new ArrayList<>();
        int total() { return retirer.size() + ajouter.size(); }
    }

    /**
     * Un marqueur change (altitude ou couleur) est retire puis renvoye. Seuls
     * les marqueurs du calque donne sont concernes.
     */
    static Diff diff(Map<Integer, Marqueur> affiche, List<Marqueur> voulu, int calque) {
        Diff d = new Diff();
        Map<Integer, Marqueur> v = new LinkedHashMap<>();
        for (Marqueur m : voulu) v.put(m.id, m);
        for (Map.Entry<Integer, Marqueur> e : affiche.entrySet()) {
            int id = e.getKey();
            if (calqueDe(id) != calque) continue;
            Marqueur n = v.get(id);
            if (n == null || !n.meme(e.getValue())) d.retirer.add(id);
        }
        for (Marqueur m : v.values()) {
            Marqueur a = affiche.get(m.id);
            if (a == null || !a.meme(m)) d.ajouter.add(m);
        }
        return d;
    }

    static int calqueDe(int id) { return (id - BASE) / TAILLE_CALQUE; }

    /** Altitude au format du paquet (point decimal, comme le serveur). */
    static String altitude(double z) {
        if (Math.abs(z - Math.rint(z)) < 1e-9) return String.valueOf((long) Math.rint(z)) + ".0";
        return String.format(Locale.ROOT, "%.4f", z).replaceAll("0+$", "");
    }
}

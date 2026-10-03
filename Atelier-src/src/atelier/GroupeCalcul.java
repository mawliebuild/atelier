package atelier;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geometrie PURE des calques (aucun JavaFX, aucun reseau) : ou va chaque
 * mobi quand on decale le groupe de (dx, dy), a quelle altitude, et dans quel
 * ORDRE les deplacer pour que le groupe ne se gene pas lui-meme.
 *
 * Ordre de deplacement. MoveObject pose le mobi sur le dessus de la pile de la
 * case d'arrivee, et refuse une case occupee par un mobi non empilable. Donc :
 *   - si la case d'arrivee de A est occupee par B (du groupe, pas empile avec
 *     A), B part d'abord : la place est libre quand A arrive ;
 *   - deux mobis empiles (cases de depart communes) partent du bas vers le
 *     haut : celui du dessus se repose sur celui du dessous, comme avant.
 * Ces contraintes forment un graphe ; on le parcourt (Kahn) en prenant
 * d'abord, a egalite, le mobi le plus « en avant » dans le sens du decalage
 * puis le plus bas. Un cycle (rare) est casse par cette meme priorite ; les
 * hauteurs sont de toute facon remises apres coup par @altitude.
 */
final class GroupeCalcul {

    private GroupeCalcul() { }

    /** Un mobi du groupe, reduit a ce qui compte. */
    static final class Element {
        final int id;
        final boolean mural;
        final int x, y, ex, ey, rot;     // sol : case d'origine, emprise (rotation comprise), rotation
        final double z;                  // sol : altitude absolue
        final int solDessous;            // sol : hauteur du sol nu sous la case d'origine (-1 inconnu)
        final String position;           // mur : « :w=x,y l=a,b r »

        private Element(int id, boolean mural, int x, int y, int ex, int ey, int rot, double z, int sol, String pos) {
            this.id = id; this.mural = mural; this.x = x; this.y = y;
            this.ex = Math.max(1, ex); this.ey = Math.max(1, ey); this.rot = rot & 7;
            this.z = z; this.solDessous = sol; this.position = pos;
        }

        static Element sol(int id, int x, int y, double z, int ex, int ey, int rot, int solDessous) {
            return new Element(id, false, x, y, ex, ey, rot, z, solDessous, null);
        }

        static Element mur(int id, String position) {
            int[] c = caseMur(position);
            return new Element(id, true, c == null ? 0 : c[0], c == null ? 0 : c[1], 1, 1, 0, 0, -1, position);
        }

        /** Altitude au-dessus du sol nu de sa case. */
        double auDessusDuSol() { return Math.max(0, z - Math.max(0, solDessous)); }

        @Override public String toString() {
            return mural ? "mur " + id + " " + position : "sol " + id + " (" + x + "," + y + " z" + z + ")";
        }
    }

    /** Une destination calculee. */
    static final class Cible {
        final Element e;
        final int x, y;            // sol
        final double z;            // sol : altitude voulue
        final String position;     // mur : nouvelle position (null = illisible)
        final boolean horsPlan;    // au moins une case d'arrivee hors du plan
        final int rot;             // sol : rotation a l'arrivee (celle d'origine, sauf miroir)
        Cible(Element e, int x, int y, double z, String position, boolean horsPlan) {
            this(e, x, y, z, position, horsPlan, e.rot);
        }
        Cible(Element e, int x, int y, double z, String position, boolean horsPlan, int rot) {
            this.e = e; this.x = x; this.y = y; this.z = z; this.position = position; this.horsPlan = horsPlan;
            this.rot = rot & 7;
        }
        @Override public String toString() {
            return e.mural ? "mur " + e.id + " -> " + position : "sol " + e.id + " -> (" + x + "," + y + " z" + z + ")" + (horsPlan ? " HORS PLAN" : "");
        }
    }

    /** Hauteur du sol nu d'une case, -1 = hors plan. */
    interface Sol { int hauteur(int x, int y); }

    /**
     * Les destinations. Un sol garde son altitude AU-DESSUS du sol nu : sur un
     * sol plat, l'altitude absolue ne change pas ; sur une marche, elle suit.
     */
    static List<Cible> cibles(Collection<Element> elements, int dx, int dy, Sol sol) {
        List<Cible> r = new ArrayList<>();
        for (Element e : elements) {
            if (e.mural) {
                String p = decalerMur(e.position, dx, dy);
                r.add(new Cible(e, e.x + dx, e.y + dy, 0, p, p == null));
                continue;
            }
            int nx = e.x + dx, ny = e.y + dy;
            boolean hors = false;
            for (int i = 0; i < e.ex && !hors; i++)
                for (int j = 0; j < e.ey && !hors; j++)
                    if (sol != null && sol.hauteur(nx + i, ny + j) < 0) hors = true;
            int h = sol == null ? Math.max(0, e.solDessous) : sol.hauteur(nx, ny);
            r.add(new Cible(e, nx, ny, altitude(e.z, e.solDessous, h), null, hors));
        }
        return r;
    }

    /**
     * Copie miroir des mobis de SOL (les muraux ne sont pas retournes, ils sont
     * laisses de cote) : symetrie dans le cadre qui les contient, gauche↔droite
     * (surX) ou haut↔bas, rotations retournees comme OutilMiroir, puis la copie
     * est posee juste a cote du cadre, a « ecart » cases (a droite pour surX,
     * en dessous sinon). Logique pure, hors Sol.
     */
    static List<Cible> miroir(Collection<Element> elements, boolean surX, int ecart, Sol sol) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Element e : elements) {
            if (e.mural) continue;
            minX = Math.min(minX, e.x); minY = Math.min(minY, e.y);
            maxX = Math.max(maxX, e.x + e.ex - 1); maxY = Math.max(maxY, e.y + e.ey - 1);
        }
        List<Cible> r = new ArrayList<>();
        if (minX == Integer.MAX_VALUE) return r;
        int sx = minX + maxX, sy = minY + maxY;
        int decX = surX ? maxX - minX + 1 + Math.max(0, ecart) : 0;
        int decY = surX ? 0 : maxY - minY + 1 + Math.max(0, ecart);
        for (Element e : elements) {
            if (e.mural) continue;
            int rot2 = OutilMiroir.miroirRot(e.rot, surX);
            // l'axe du mobi ne change pas (2<->6, 0<->4) : meme emprise
            int nx = (surX ? sx - e.x - e.ex + 1 : e.x) + decX;
            int ny = (surX ? e.y : sy - e.y - e.ey + 1) + decY;
            boolean hors = false;
            for (int i = 0; i < e.ex && !hors; i++)
                for (int j = 0; j < e.ey && !hors; j++)
                    if (sol != null && sol.hauteur(nx + i, ny + j) < 0) hors = true;
            int h = sol == null ? Math.max(0, e.solDessous) : sol.hauteur(nx, ny);
            r.add(new Cible(e, nx, ny, altitude(e.z, e.solDessous, h), null, hors, rot2));
        }
        return r;
    }

    /**
     * Pivot d'un quart de tour des mobis de SOL (les muraux sont laisses de
     * cote). Sens horaire = comme le bouton « tourner » du jeu : rotation + 2
     * (N→E→S→O), et la case (x, y) devient (-y, x).
     *   toutLeCalque  le bloc entier tourne dans son cadre ; le nouveau cadre
     *                 garde le meme centre (a une demi-case pres) ;
     *   sinon         chaque mobi tourne sur sa case d'origine.
     * L'emprise d'un mobi s'echange (ex <-> ey). Un mobi a deux rotations
     * seulement (0 et 2) refusera 4 ou 6 : rotationRepli(cible) donne alors la
     * rotation equivalente sur le meme axe. Logique pure, hors Sol.
     *
     * Le bloc tourne autour d'un point fixe (coordonnees DOUBLEES, voir
     * centrePivot) : redonner le meme point aux tours suivants ramene le bloc
     * exactement a sa place apres 4 tours (ou un tour dans chaque sens).
     */
    static List<Cible> pivot(Collection<Element> elements, boolean horaire, boolean toutLeCalque, Sol sol) {
        return pivot(elements, horaire, toutLeCalque, sol, null);
    }

    /**
     * Centre de rotation d'un bloc, en coordonnees doublees {2·cx, 2·cy} : le
     * centre de son cadre. Les deux doivent avoir la meme parite (sinon une
     * rotation d'un quart de tour tomberait entre deux cases) : la coordonnee
     * impaire est alors arrondie vers le bas d'une demi-case. null sans sol.
     */
    static int[] centrePivot(Collection<Element> elements) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Element e : elements) {
            if (e.mural) continue;
            minX = Math.min(minX, e.x); minY = Math.min(minY, e.y);
            maxX = Math.max(maxX, e.x + e.ex - 1); maxY = Math.max(maxY, e.y + e.ey - 1);
        }
        if (minX == Integer.MAX_VALUE) return null;
        int cx2 = minX + maxX, cy2 = minY + maxY;
        if (((cx2 ^ cy2) & 1) != 0) { if ((cx2 & 1) != 0) cx2--; else cy2--; }
        return new int[]{cx2, cy2};
    }

    /** Idem, autour du centre doublé donne (null = centre du cadre). */
    static List<Cible> pivot(Collection<Element> elements, boolean horaire, boolean toutLeCalque, Sol sol, int[] centre2) {
        List<Cible> r = new ArrayList<>();
        int[] c2 = centre2 != null && ((centre2[0] ^ centre2[1]) & 1) == 0 ? centre2 : centrePivot(elements);
        if (c2 == null) return r;
        int px2 = c2[0], py2 = c2[1];
        for (Element e : elements) {
            if (e.mural) continue;
            int rot2 = (e.rot + (horaire ? 2 : 6)) & 7;
            int nx, ny;
            if (!toutLeCalque) { nx = e.x; ny = e.y; }
            else {
                // centre du mobi (double), tourne autour du point, puis coin haut-gauche
                int mx2 = 2 * e.x + e.ex - 1, my2 = 2 * e.y + e.ey - 1;
                int qx2, qy2;
                if (horaire) { qx2 = px2 - (my2 - py2); qy2 = py2 + (mx2 - px2); }
                else         { qx2 = px2 + (my2 - py2); qy2 = py2 - (mx2 - px2); }
                nx = (qx2 - (e.ey - 1)) / 2;               // emprise tournee : ey en x
                ny = (qy2 - (e.ex - 1)) / 2;
            }
            int lx = e.ey, ly = e.ex;                       // emprise tournee
            boolean hors = false;
            for (int i = 0; i < lx && !hors; i++)
                for (int j = 0; j < ly && !hors; j++)
                    if (sol != null && sol.hauteur(nx + i, ny + j) < 0) hors = true;
            int hs = sol == null ? Math.max(0, e.solDessous) : sol.hauteur(nx, ny);
            r.add(new Cible(e, nx, ny, altitude(e.z, e.solDessous, hs), null, hors, rot2));
        }
        return r;
    }

    /**
     * Deplacement avec pivot : le bloc tourne de « quarts » quarts de tour
     * horaires (0..3) autour du centre de son cadre, puis glisse de (dx, dy).
     * Sans pivot, c'est cibles() (muraux compris) ; avec pivot, les muraux
     * restent de cote. Logique pure, hors Sol.
     */
    static List<Cible> transformer(Collection<Element> elements, int quarts, int dx, int dy, Sol sol) {
        int q = ((quarts % 4) + 4) % 4;
        if (q == 0) return cibles(elements, dx, dy, sol);
        List<Element> cur = new ArrayList<>();
        for (Element e : elements) if (!e.mural) cur.add(e);
        if (cur.isEmpty()) return new ArrayList<>();
        int[] centre = centrePivot(cur);
        List<Cible> r = null;
        for (int k = 0; k < q; k++) {
            r = pivot(cur, true, true, null, centre);
            List<Element> suivant = new ArrayList<>();
            for (Cible c : r) suivant.add(Element.sol(c.e.id, c.x, c.y, c.e.z, c.e.ey, c.e.ex, c.rot, c.e.solDessous));
            cur = suivant;
        }
        List<Cible> l = new ArrayList<>();
        for (int i = 0; i < cur.size(); i++) {
            Element e = cur.get(i), orig = r.get(i).e;
            int nx = e.x + dx, ny = e.y + dy;
            boolean h = false;
            for (int a = 0; a < e.ex && !h; a++)
                for (int b = 0; b < e.ey && !h; b++)
                    if (sol != null && sol.hauteur(nx + a, ny + b) < 0) h = true;
            int hs = sol == null ? Math.max(0, orig.solDessous) : sol.hauteur(nx, ny);
            l.add(new Cible(orig, nx, ny, altitude(orig.z, orig.solDessous, hs), null, h, e.rot));
        }
        return l;
    }

    /**
     * Copie TOURNEE posee a cote du bloc (pour Dupliquer) : quarts = 1 (quart
     * de tour horaire), 3 (inverse) ou 2 (demi-tour). Le bloc tourne d'un seul
     * tenant (emprises echangees, rotations +2 par quart), puis il est pousse
     * a cote de l'original, une case d'ecart : a droite, sinon a gauche, en
     * dessous ou au-dessus, la premiere place entierement dans le plan. Les
     * muraux sont laisses de cote. Logique pure, hors Sol.
     */
    static List<Cible> copieTournee(Collection<Element> elements, int quarts, Sol sol) {
        List<Element> sols = new ArrayList<>();
        for (Element e : elements) if (!e.mural) sols.add(e);
        if (sols.isEmpty()) return new ArrayList<>();
        int[] cadre = cadre(sols);
        List<Cible> r = null;
        List<Element> cur = sols;
        int n = ((quarts % 4) + 4) % 4;
        if (n == 0) n = 4;
        for (int k = 0; k < n; k++) {
            r = pivot(cur, true, true, null, null);
            List<Element> suivant = new ArrayList<>();
            for (Cible c : r) suivant.add(Element.sol(c.e.id, c.x, c.y, c.e.z, c.e.ey, c.e.ex, c.rot, c.e.solDessous));
            cur = suivant;
        }
        int[] tourne = cadre(cur);
        int lx = tourne[2] - tourne[0], ly = tourne[3] - tourne[1];
        // {dx, dy} candidats : a droite, a gauche, en dessous, au-dessus (alignes sur le haut / la gauche)
        int[][] places = {
                {cadre[2] + 2 - tourne[0], cadre[1] - tourne[1]},
                {cadre[0] - 2 - lx - tourne[0], cadre[1] - tourne[1]},
                {cadre[0] - tourne[0], cadre[3] + 2 - tourne[1]},
                {cadre[0] - tourne[0], cadre[1] - 2 - ly - tourne[1]}};
        List<Cible> premier = null;
        for (int[] d : places) {
            List<Cible> l = new ArrayList<>();
            boolean hors = false;
            for (int i = 0; i < cur.size(); i++) {
                Element e = cur.get(i);
                int nx = e.x + d[0], ny = e.y + d[1];
                boolean h = false;
                for (int a = 0; a < e.ex && !h; a++)
                    for (int b = 0; b < e.ey && !h; b++)
                        if (sol != null && sol.hauteur(nx + a, ny + b) < 0) h = true;
                hors |= h;
                Element orig = r.get(i).e;
                int hs = sol == null ? Math.max(0, orig.solDessous) : sol.hauteur(nx, ny);
                l.add(new Cible(orig, nx, ny, altitude(orig.z, orig.solDessous, hs), null, h, e.rot));
            }
            if (premier == null) premier = l;
            if (!hors) return l;
        }
        return premier;
    }

    /** {minX, minY, maxX, maxY} des cases couvertes par des mobis de sol. */
    private static int[] cadre(Collection<Element> els) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Element e : els) {
            if (e.mural) continue;
            minX = Math.min(minX, e.x); minY = Math.min(minY, e.y);
            maxX = Math.max(maxX, e.x + e.ex - 1); maxY = Math.max(maxY, e.y + e.ey - 1);
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /** Rotation equivalente sur le meme axe (pour un mobi qui n'a que 0 et 2). */
    static int rotationRepli(int rot) { return (rot + 4) & 7; }

    /**
     * Rotation envoyee a l'essai n (0, 1, 2) : la voulue, puis l'equivalente
     * sur le meme axe, puis celle d'origine (mobi a une seule orientation :
     * il change seulement de case). -1 = rien a essayer (deja essayee).
     */
    static int rotationEssai(int voulue, int origine, int essai) {
        voulue &= 7; origine &= 7;
        switch (essai) {
            case 0: return voulue;
            case 1: return rotationRepli(voulue) == voulue ? -1 : rotationRepli(voulue);
            case 2: return origine == voulue || origine == rotationRepli(voulue) ? -1 : origine;
            default: return -1;
        }
    }

    /** La rotation lue r convient : voulue, son equivalente, ou celle d'origine si envoye sans tourner. */
    static boolean rotationAcceptee(int r, int voulue, int origine, boolean sansTourner) {
        r &= 7; voulue &= 7;
        return r == voulue || r == rotationRepli(voulue) || (sansTourner && r == (origine & 7));
    }

    /** z - sol de depart + sol d'arrivee, arrondi au centieme, jamais sous 0. */
    static double altitude(double z, int solDepart, int solArrivee) {
        double v = z - Math.max(0, solDepart) + Math.max(0, solArrivee);
        return Math.round(Math.max(0, v) * 100.0) / 100.0;
    }

    // ------------------------------------------------------------- murs

    private static final Pattern W = Pattern.compile(":w=(-?\\d+),(-?\\d+)");

    /** {x, y} d'une position murale, null si illisible. */
    static int[] caseMur(String pos) {
        if (pos == null) return null;
        Matcher m = W.matcher(pos);
        if (!m.find()) return null;
        try { return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}; }
        catch (NumberFormatException e) { return null; }
    }

    /** Decale « :w=x,y » de (dx, dy), le reste (l=.., r/l, a=..) inchange. null si illisible. */
    static String decalerMur(String pos, int dx, int dy) {
        if (pos == null) return null;
        Matcher m = W.matcher(pos);
        if (!m.find()) return null;
        int x, y;
        try { x = Integer.parseInt(m.group(1)); y = Integer.parseInt(m.group(2)); }
        catch (NumberFormatException e) { return null; }
        return pos.substring(0, m.start()) + ":w=" + (x + dx) + "," + (y + dy) + pos.substring(m.end());
    }

    // ------------------------------------------------------------- ordre

    private static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /** Ordre de deplacement des sols (les murs sont laisses a la fin, dans l'ordre recu). */
    static List<Element> ordre(List<Element> elements, int dx, int dy) {
        List<Element> sols = new ArrayList<>(), murs = new ArrayList<>();
        for (Element e : elements) (e.mural ? murs : sols).add(e);
        int n = sols.size();
        // cases de depart -> mobis
        Map<Long, List<Integer>> occupe = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Element e = sols.get(i);
            for (int a = 0; a < e.ex; a++)
                for (int b = 0; b < e.ey; b++)
                    occupe.computeIfAbsent(cle(e.x + a, e.y + b), k -> new ArrayList<>()).add(i);
        }
        List<Set<Integer>> apres = new ArrayList<>();     // i -> ceux qui attendent i
        int[] attend = new int[n];
        for (int i = 0; i < n; i++) apres.add(new HashSet<>());

        // 1. empiles : du bas vers le haut
        for (List<Integer> pile : occupe.values()) {
            if (pile.size() < 2) continue;
            List<Integer> p = new ArrayList<>(pile);
            p.sort((a, b) -> Double.compare(sols.get(a).z, sols.get(b).z) != 0
                    ? Double.compare(sols.get(a).z, sols.get(b).z) : Integer.compare(sols.get(a).id, sols.get(b).id));
            for (int k = 0; k + 1 < p.size(); k++) lier(apres, attend, p.get(k), p.get(k + 1));
        }
        // 2. la case d'arrivee de A est occupee par B (non empile avec A) : B d'abord
        if (dx != 0 || dy != 0) for (int i = 0; i < n; i++) {
            Element a = sols.get(i);
            Set<Integer> depart = new HashSet<>();
            for (int u = 0; u < a.ex; u++)
                for (int v = 0; v < a.ey; v++) {
                    List<Integer> l = occupe.get(cle(a.x + u, a.y + v));
                    if (l != null) depart.addAll(l);
                }
            for (int u = 0; u < a.ex; u++)
                for (int v = 0; v < a.ey; v++) {
                    List<Integer> l = occupe.get(cle(a.x + dx + u, a.y + dy + v));
                    if (l == null) continue;
                    for (int j : l) if (j != i && !depart.contains(j)) lier(apres, attend, j, i);
                }
        }
        // 3. Kahn, priorite : le plus en avant, puis le plus bas, puis l'id
        Comparator<Integer> prio = (a, b) -> {
            Element p = sols.get(a), q = sols.get(b);
            long pa = (long) p.x * dx + (long) p.y * dy, pb = (long) q.x * dx + (long) q.y * dy;
            if (pa != pb) return Long.compare(pb, pa);
            if (Double.compare(p.z, q.z) != 0) return Double.compare(p.z, q.z);
            return Integer.compare(p.id, q.id);
        };
        TreeSet<Integer> prets = new TreeSet<>(prio), restants = new TreeSet<>(prio);
        for (int i = 0; i < n; i++) { restants.add(i); if (attend[i] == 0) prets.add(i); }
        List<Element> r = new ArrayList<>(n + murs.size());
        while (!restants.isEmpty()) {
            Integer i = prets.isEmpty() ? restants.first() : prets.first();   // cycle : on casse
            prets.remove(i);
            restants.remove(i);
            r.add(sols.get(i));
            for (int j : apres.get(i)) {
                if (!restants.contains(j)) continue;
                if (--attend[j] <= 0) prets.add(j);
            }
        }
        r.addAll(murs);
        return r;
    }

    private static void lier(List<Set<Integer>> apres, int[] attend, int avant, int ensuite) {
        if (avant == ensuite) return;
        if (apres.get(avant).add(ensuite)) attend[ensuite]++;
    }
}

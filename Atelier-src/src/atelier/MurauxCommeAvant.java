package atelier;

import gearth.extensions.parsers.HWallItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « Remettre les muraux comme avant » : apres un changement de floor (cases ajoutees ou
 * retirees, hauteurs, hauteur des murs), le jeu redessine les muraux ailleurs, car leur
 * position murale « :w=x,y l=dx,dy r|l » se lit avec les hauteurs du plan. On garde le
 * plan d'avant ; le bouton recalcule pour chaque mural la position qui le remet au meme
 * endroit a l'ecran avec le nouveau plan, puis le deplace (MoveWallItem), en une seule
 * action d'historique.
 *
 * Geometrie du client (RoomPlaneParser + geometrie « legacy », getLocation, S = 64) :
 *   case du sol : sa hauteur ; porte : sa hauteur + hauteur du mur ;
 *   case vide : min(20, f) + hauteur du mur, f = hauteurMur (la plus haute case si -1),
 *   hauteur du mur = hauteurMur + 3.6 (3.6 si -1) ; hors du plan : 0.
 *   « r » : X = x + dx/32 - 0.5, Y = y + 0.5, Z = h - (dy - dx/2)/32
 *   « l » : X = x + 0.5, Y = y + (32 - dx)/32 - 0.5, Z = h - (dy - (32 - dx)/2)/32
 * Un point peut glisser le long de (1, 1, 1) sans bouger a l'ecran.
 */
final class MurauxCommeAvant {

    private MurauxCommeAvant() { }

    /** Le plan tel que le jeu l'utilise pour placer les muraux. */
    record Geometrie(String[] lignes, int hauteurMur, int porteX, int porteY) {

        double h(int x, int y) {
            int haut = lignes.length, larg = 0;
            for (String l : lignes) larg = Math.max(larg, l.length());
            if (x < 0 || y < 0 || x >= larg || y >= haut) return 0;
            double mur = hauteurMur != -1 ? hauteurMur + 3.6 : 3.6;
            Integer c = hauteur(x, y);
            if (c != null) return x == porteX && y == porteY ? c + mur : c;
            int plusHaute = 0;
            for (int j = 0; j < haut; j++)
                for (int i = 0; i < lignes[j].length(); i++) {
                    Integer v = hauteur(i, j);
                    if (v != null) plusHaute = Math.max(plusHaute, v);
                }
            return Math.min(20, hauteurMur != -1 ? hauteurMur : plusHaute) + mur;
        }

        private Integer hauteur(int x, int y) {
            if (y < 0 || y >= lignes.length || x < 0 || x >= lignes[y].length()) return null;
            char ch = Character.toLowerCase(lignes[y].charAt(x));
            if (ch == 'x') return null;
            int v = Character.digit(ch, 36);
            return v < 0 ? 0 : v;
        }

        boolean memePlan(Geometrie o) {
            return o != null && hauteurMur == o.hauteurMur && java.util.Arrays.equals(lignes, o.lignes);
        }
    }

    /** Une position murale lue « :w=x,y l=dx,dy r|l ». */
    record Position(int x, int y, int dx, int dy, char cote) {
        String texte() { return ":w=" + x + "," + y + " l=" + dx + "," + dy + " " + cote; }
    }

    private static final Pattern POSITION = Pattern.compile(":w=(-?\\d+),(-?\\d+) l=(-?\\d+),(-?\\d+) ([lr])");

    static Position lire(String s) {
        if (s == null) return null;
        Matcher m = POSITION.matcher(s);
        if (!m.find()) return null;
        return new Position(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5).charAt(0));
    }

    /** Logique pure : le point d'ancrage (x, y, z) que le jeu donne a cette position. */
    static double[] point(Position p, Geometrie g) {
        double h = g.h(p.x(), p.y());
        if (p.cote() == 'r')
            return new double[]{p.x() + p.dx() / 32.0 - 0.5, p.y() + 0.5, h - (p.dy() - p.dx() / 2.0) / 32};
        return new double[]{p.x() + 0.5, p.y() + (32 - p.dx()) / 32.0 - 0.5, h - (p.dy() - (32 - p.dx()) / 2.0) / 32};
    }

    /** Logique pure : la position qui donne ce point a l'ecran avec la geometrie g. */
    static Position position(double[] pt, char cote, Geometrie g) {
        double x = pt[0], y = pt[1], z = pt[2];
        if (cote == 'l') {
            double t = Math.round(x - 0.5) + 0.5 - x;
            x += t; y += t; z += t;
            int wx = (int) Math.round(x - 0.5);
            int wy = (int) Math.floor(y + 0.5);
            int dx = 32 - (int) Math.round((y - wy + 0.5) * 32);
            if (dx >= 32) { dx = 0; wy--; }
            int dy = (int) Math.round((g.h(wx, wy) - z) * 32 + (32 - dx) / 2.0);
            return new Position(wx, wy, dx, dy, 'l');
        }
        double t = Math.round(y - 0.5) + 0.5 - y;
        x += t; y += t; z += t;
        int wy = (int) Math.round(y - 0.5);
        int wx = (int) Math.floor(x + 0.5);
        int dx = (int) Math.round((x - wx + 0.5) * 32);
        if (dx >= 32) { dx = 0; wx++; }
        int dy = (int) Math.round((g.h(wx, wy) - z) * 32 + dx / 2.0);
        return new Position(wx, wy, dx, dy, 'r');
    }

    // ------------------------------------------------------------ suivi du plan

    private static volatile int salle = -1;
    private static volatile Geometrie courante = null, avant = null;
    /** Positions des muraux quand le plan « avant » etait en place (id -> position). */
    private static volatile Map<Integer, String> positionsAvant = Map.of();
    /** Positions vues juste apres le changement (le jeu a pu en reecrire) : la reference du « deplace a la main ». */
    private static volatile Map<Integer, String> positionsApres = null;
    private static volatile long changeA = 0;
    /** Dernieres positions connues (rafraichies en continu, jamais pendant un rechargement). */
    private static volatile Map<Integer, String> dernieres = Map.of();
    private static volatile boolean demarre = false;

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        FloorReseau.ecouter(MurauxCommeAvant::surFloor);
        Salle.tache("muraux-comme-avant", () -> {
            while (true) {
                Salle.sommeil(2000);
                try {
                    List<HWallItem> l = Salle.murs();
                    if (l == null || l.isEmpty() || !Salle.dansUneSalle()) continue;
                    Map<Integer, String> m = new HashMap<>();
                    for (HWallItem w : l) m.put(w.getId(), w.getLocation());
                    // apres un changement de plan : la 1re photo stable sert de reference
                    if (avant != null && positionsApres == null && System.currentTimeMillis() - changeA > 3000)
                        positionsApres = new HashMap<>(m);
                    dernieres = m;
                } catch (Throwable ignored) { }
            }
        });
    }

    /** Un paquet de l'editeur de floor : nouveau plan (ou nouvelle porte). */
    private static void surFloor() {
        String plan = FloorReseau.dernierPlan;
        if (plan == null) return;
        int s = Salle.salleId();
        Geometrie g = new Geometrie(plan.split("\r"), FloorReseau.dernierMur, FloorReseau.porteX, FloorReseau.porteY);
        Geometrie c = courante;
        // pendant le rechargement qui suit un changement de floor, la salle peut etre -1 un
        // instant : seule une AUTRE vraie salle efface ce qui est garde
        if (s > 0 && s != salle) {
            boolean neuve = salle > 0;
            salle = s;
            if (neuve || c == null) {
                courante = g; avant = null; positionsAvant = Map.of(); positionsApres = null; dernieres = Map.of();
                return;
            }
        }
        if (c != null && !c.memePlan(g) && !dernieres.isEmpty()) {
            // le plan vient de changer : celui d'avant et les positions d'avant sont gardes
            avant = c;
            positionsAvant = new HashMap<>(dernieres);
            positionsApres = null;
            changeA = System.currentTimeMillis();
            Journal.debug("Muraux : plan changé, " + positionsAvant.size() + " position(s) gardée(s) pour « comme avant ».");
        }
        if (c == null || !c.memePlan(g)) courante = g;
        else courante = new Geometrie(c.lignes(), c.hauteurMur(), FloorReseau.porteX, FloorReseau.porteY);
    }

    // ------------------------------------------------------------ action

    /** Le bouton : remet chaque mural a sa place a l'ecran d'avant le dernier changement de floor. */
    static void remettre() {
        Geometrie a = avant, n = courante;
        if (a == null || n == null || positionsAvant.isEmpty()) {
            InfoJeu.dire("Rien à remettre : pas de changement de floor depuis que tu es dans l'appart.");
            return;
        }
        List<HWallItem> l = Salle.murs();
        List<Object[]> envois = new ArrayList<>();
        int deplaces = 0, inconnus = 0, aLaMain = 0, enPlace = 0;
        Map<Integer, String> apres = positionsApres;
        for (HWallItem w : l == null ? List.<HWallItem>of() : l) {
            Position p = lire(positionsAvant.get(w.getId())), ici = lire(w.getLocation());
            if (p == null || ici == null) { inconnus++; continue; }
            // deplace a la main depuis le changement de floor (pas par le jeu au rechargement) : on n'y touche pas
            Position ref = apres == null ? null : lire(apres.get(w.getId()));
            if (ref != null && !ref.equals(ici)) { aLaMain++; continue; }
            Position q = position(point(p, a), p.cote(), n);
            if (q.equals(ici)) { enPlace++; continue; }
            envois.add(new Object[]{w.getId(), q.texte()});
        }
        Journal.debug("Muraux comme avant : plan avant/après " + (a.memePlan(n) ? "IDENTIQUES" : "différents")
                + ", " + positionsAvant.size() + " suivi(s), " + envois.size() + " à déplacer, "
                + enPlace + " déjà en place, " + aLaMain + " déplacé(s) à la main, " + inconnus + " sans position d'avant.");
        if (envois.isEmpty()) { InfoJeu.dire("Les muraux sont déjà à leur place."); return; }
        Historique.grouper(true);
        try {
            for (Object[] e : envois) {
                Salle.espacer();
                Salle.deplacerMur((Integer) e[0], (String) e[1]);
                deplaces++;
            }
        } finally {
            Historique.grouper(false);
        }
        InfoJeu.dire(Ui.accorder(deplaces + " mobi(s) mural(aux) remis comme avant le changement de floor."));
    }
}

package atelier;

import java.util.ArrayList;
import java.util.List;

/**
 * Un plan de sol (floor plan) tel que l'editeur :floor de Habbo le manipule.
 *
 * Cases indexees [x][y] comme dans le jeu : x = position dans la ligne,
 * y = numero de ligne. Hauteur 0..35 (caracteres 0-9 puis a-z), -1 = pas de
 * case (caractere 'x'). Meme lecture que FloorState.parseFloorPlan de
 * G-Presets (verifie au javap : lignes coupees sur '\r', floorplan[x][y] =
 * ligne[y].charAt(x)) et que PresetUtils.heightFromChar (chiffre -> 0..9,
 * minuscule -> 10.., 'x' -> 256 = pas de case).
 *
 * Objet simple, copiable : l'historique Annuler/Retablir garde des copies.
 */
final class FloorModele {

    /**
     * Hauteur maxi ecrite : 32 ('w'). Les caracteres vont de 0-9 puis a-z, mais
     * 'x' est reserve au vide (il vaudrait 33) : au-dela de 'w' on ne pourrait
     * plus monter case par case. Une salle lue avec 'y'/'z' (34/35) est
     * conservee telle quelle tant qu'on ne touche pas ces cases.
     */
    static final int HAUTEUR_MAX = 32;

    int largeur, longueur;
    int[][] h;                 // [x][y], -1 = pas de case
    int porteX = -1, porteY = -1, porteDir = 2;
    boolean porteConnue = false;
    int hauteurMur = -1;       // -1 = automatique
    int epMur = 0, epSol = 0;  // epaisseurs Habbo -2..1
    boolean epConnues = false;
    int echelle = 0;           // octet "scale" de FloorHeightMap (renvoye tel quel)

    FloorModele(int largeur, int longueur) {
        this.largeur = Math.max(1, largeur);
        this.longueur = Math.max(1, longueur);
        h = new int[this.largeur][this.longueur];
        for (int[] c : h) java.util.Arrays.fill(c, -1);
    }

    FloorModele copie() {
        FloorModele m = new FloorModele(largeur, longueur);
        for (int x = 0; x < largeur; x++) m.h[x] = h[x].clone();
        m.porteX = porteX; m.porteY = porteY; m.porteDir = porteDir; m.porteConnue = porteConnue;
        m.hauteurMur = hauteurMur; m.epMur = epMur; m.epSol = epSol; m.epConnues = epConnues;
        m.echelle = echelle;
        return m;
    }

    boolean dansPlan(int x, int y) { return x >= 0 && y >= 0 && x < largeur && y < longueur; }

    /** Hauteur de la case, -1 si pas de case ou hors plan. */
    int at(int x, int y) { return dansPlan(x, y) ? h[x][y] : -1; }

    boolean existe(int x, int y) { return at(x, y) >= 0; }

    boolean poser(int x, int y, int v) {
        if (!dansPlan(x, y)) return false;
        v = v < 0 ? -1 : Math.min(HAUTEUR_MAX, v);
        if (h[x][y] == v) return false;
        h[x][y] = v;
        return true;
    }

    int nbCases() {
        int n = 0;
        for (int x = 0; x < largeur; x++) for (int y = 0; y < longueur; y++) if (h[x][y] >= 0) n++;
        return n;
    }

    int hauteurMaxi() {
        int m = 0;
        for (int x = 0; x < largeur; x++) for (int y = 0; y < longueur; y++) m = Math.max(m, h[x][y]);
        return m;
    }

    // ------------------------------------------------------------ texte

    static char car(int v) {
        if (v < 0) return 'x';
        if (v < 10) return (char) ('0' + v);
        return (char) ('a' + Math.min(25, v - 10));
    }

    static int hauteurDe(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'z' && c != 'x') return c - 'a' + 10;
        // 'x' (et toute autre lettre / majuscule) : pas de case.
        return -1;
    }

    /** Plan au format Habbo : lignes separees par '\r'. */
    String texte() {
        StringBuilder sb = new StringBuilder(largeur * longueur + longueur);
        for (int y = 0; y < longueur; y++) {
            if (y > 0) sb.append('\r');
            for (int x = 0; x < largeur; x++) {
                int v = h[x][y];
                if (v == 33) v = 32;   // 'x' = vide : 33 n'est pas representable
                sb.append(car(Math.min(35, v)));
            }
        }
        return sb.toString();
    }

    /** Lit un plan brut (lignes separees par \r et/ou \n). null si vide. */
    static FloorModele depuisTexte(String brut) {
        if (brut == null) return null;
        String[] lignes = brut.replace("\r\n", "\r").replace('\n', '\r').split("\r");
        List<String> ok = new ArrayList<>();
        for (String l : lignes) ok.add(l);
        while (!ok.isEmpty() && ok.get(ok.size() - 1).isEmpty()) ok.remove(ok.size() - 1);
        if (ok.isEmpty()) return null;
        int w = 0;
        for (String l : ok) w = Math.max(w, l.length());
        if (w == 0) return null;
        FloorModele m = new FloorModele(w, ok.size());
        for (int y = 0; y < ok.size(); y++) {
            String l = ok.get(y);
            for (int x = 0; x < l.length(); x++) m.h[x][y] = hauteurDe(l.charAt(x));
        }
        return m;
    }

    // ------------------------------------------------------- redimension

    /**
     * Ajoute une rangee vide sur un bord. bord : 0 = x fin, 1 = y fin,
     * 2 = x debut, 3 = y debut. Les deux derniers decalent toutes les cases
     * (et la porte) d'un cran ; les mobis, eux, gardent leurs coordonnees.
     */
    FloorModele agrandi(int bord) {
        int dx = bord == 2 ? 1 : 0, dy = bord == 3 ? 1 : 0;
        int w = largeur + (bord == 0 || bord == 2 ? 1 : 0);
        int l = longueur + (bord == 1 || bord == 3 ? 1 : 0);
        FloorModele m = copieDecalee(w, l, dx, dy);
        return m;
    }

    /**
     * Retire les rangees vides des bords. Si debutAussi, aussi en x=0 / y=0
     * (ce qui decale les coordonnees). Renvoie this si rien a retirer.
     */
    FloorModele rogne(boolean debutAussi) {
        int minX = largeur, minY = longueur, maxX = -1, maxY = -1;
        for (int x = 0; x < largeur; x++)
            for (int y = 0; y < longueur; y++)
                if (h[x][y] >= 0) {
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
        if (maxX < 0) return this;
        // La porte doit rester dans le plan.
        if (porteConnue && dansPlan(porteX, porteY)) {
            minX = Math.min(minX, porteX); minY = Math.min(minY, porteY);
            maxX = Math.max(maxX, porteX); maxY = Math.max(maxY, porteY);
        }
        if (!debutAussi) { minX = 0; minY = 0; }
        int w = maxX - minX + 1, l = maxY - minY + 1;
        if (w == largeur && l == longueur) return this;
        return copieDecalee(w, l, -minX, -minY);
    }

    private FloorModele copieDecalee(int w, int l, int dx, int dy) {
        FloorModele m = new FloorModele(w, l);
        for (int x = 0; x < largeur; x++)
            for (int y = 0; y < longueur; y++)
                if (m.dansPlan(x + dx, y + dy)) m.h[x + dx][y + dy] = h[x][y];
        m.porteX = porteX + dx; m.porteY = porteY + dy; m.porteDir = porteDir; m.porteConnue = porteConnue;
        m.hauteurMur = hauteurMur; m.epMur = epMur; m.epSol = epSol; m.epConnues = epConnues;
        m.echelle = echelle;
        return m;
    }

    // -------------------------------------------------------- comparaison

    /** Hauteur dans un autre modele a la meme coordonnee (-1 si absente). */
    static int ancien(FloorModele o, int x, int y) { return o == null ? -1 : o.at(x, y); }

    /** Nombre de cases dont la hauteur (ou l'existence) differe. */
    int differences(FloorModele o) {
        if (o == null) return 0;
        int n = 0;
        int W = Math.max(largeur, o.largeur), L = Math.max(longueur, o.longueur);
        for (int x = 0; x < W; x++) for (int y = 0; y < L; y++) if (at(x, y) != o.at(x, y)) n++;
        return n;
    }

    boolean memeReglages(FloorModele o) {
        return o != null && porteX == o.porteX && porteY == o.porteY && porteDir == o.porteDir
                && hauteurMur == o.hauteurMur && epMur == o.epMur && epSol == o.epSol;
    }

    boolean memeTaille(FloorModele o) { return o != null && largeur == o.largeur && longueur == o.longueur; }

    static String direction(int d) {
        switch (((d % 8) + 8) % 8) {
            case 0: return "0 · haut-droite (−y)";
            case 1: return "1 · droite";
            case 2: return "2 · bas-droite (+x)";
            case 3: return "3 · bas";
            case 4: return "4 · bas-gauche (+y)";
            case 5: return "5 · gauche";
            case 6: return "6 · haut-gauche (−x)";
            default: return "7 · haut";
        }
    }

    /** Vecteur (dx, dy) en cases d'une direction Habbo 0..7 (0 = −y, 2 = +x, 4 = +y, 6 = −x). */
    static int[] vecteur(int d) {
        int[][] v = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
        return v[((d % 8) + 8) % 8];
    }
}

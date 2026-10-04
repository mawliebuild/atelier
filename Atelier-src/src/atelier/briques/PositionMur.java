package atelier;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Position d'un mural, telle que le jeu l'ecrit : « :w=x,y l=ox,oy r|l »,
 * parfois suivie de « a=altitude » (altitude reglee par la variable -123).
 *
 * Remplace utils.WallPosition : meme expression reguliere, meme toString()
 * (sans l'altitude) et meme complet() (ex-toFullString, avec l'altitude).
 */
record PositionMur(int x, int y, int decalageX, int decalageY, char cote, int altitude) {

    private static final Pattern MOTIF = Pattern.compile(
            ":w=(-?\\d+),(-?\\d+)\\s+l=(-?\\d+),(-?\\d+)\\s+([rl])(?:\\s+a=(-?\\d+))?");

    /** Sans altitude (0). */
    PositionMur(int x, int y, int decalageX, int decalageY, char cote) {
        this(x, y, decalageX, decalageY, cote, 0);
    }

    /**
     * Lit « :w=3,4 l=10,20 r [a=150] » (le motif peut etre au milieu du texte).
     * @throws IllegalArgumentException si le texte n'a pas ce format
     */
    static PositionMur lire(String texte) {
        Matcher m = texte == null ? null : MOTIF.matcher(texte);
        if (m == null || !m.find()) throw new IllegalArgumentException("Position murale illisible : " + texte);
        return new PositionMur(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5).charAt(0),
                m.group(6) != null ? Integer.parseInt(m.group(6)) : 0);
    }

    /** Comme lire(), mais null si le texte est illisible. */
    static PositionMur lireOuNull(String texte) {
        try { return lire(texte); } catch (IllegalArgumentException e) { return null; }
    }

    /** Mur de gauche (« l »). */
    boolean gauche() { return cote == 'l'; }

    PositionMur avecAltitude(int a) { return new PositionMur(x, y, decalageX, decalageY, cote, a); }

    PositionMur deplacee(int dx, int dy) { return new PositionMur(x + dx, y + dy, decalageX, decalageY, cote, altitude); }

    /** « :w=x,y l=a,b c », sans l'altitude (format attendu par le jeu). */
    @Override public String toString() {
        return String.format(Locale.ROOT, ":w=%d,%d l=%d,%d %c", x, y, decalageX, decalageY, cote);
    }

    /** « :w=x,y l=a,b c a=alt ». */
    String complet() {
        return String.format(Locale.ROOT, ":w=%d,%d l=%d,%d %c a=%d", x, y, decalageX, decalageY, cote, altitude);
    }
}

package atelier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Apercu en image d'un ensemble de mobis de sol (une copie wired) : photo
 * prise PAR LE JEU (Capture.captureParLeJeu), decoupee autour des mobis.
 *
 * Trois photos : sans mise en valeur (A), avec les mobis en valeur (B), puis
 * de nouveau sans (C). Ce qui change entre A et C bouge tout seul (mobis
 * animes, avatars) et ne compte pas. Ce qui change nettement entre A et B,
 * quelle que soit la couleur ou le style de la mise en valeur (contour,
 * remplissage...), donne le cadre des mobis a l'ecran. On compte par petits
 * carres de pixels : un carre ne compte que s'il a assez de pixels changes
 * (les petites etincelles d'animation sont ignorees). Si rien ne change, la
 * photo entiere est gardee.
 */
final class ApercuMobis {

    private ApercuMobis() { }

    private static final int LARGEUR_MAX = 900;
    private static final int MARGE = 24;
    /** Cote des carres de comptage, en pixels. */
    private static final int CARRE = 8;
    /** Pixels changes au minimum pour qu'un carre compte. */
    private static final int SEUIL_CARRE = 6;

    /** Pendant la photo : la fenetre ne met rien d'autre en valeur. */
    private static volatile boolean enCours = false;

    static boolean enCours() { return enCours; }

    /**
     * Prend l'apercu des mobis de sol donnes et l'enregistre dans « png ».
     * Hors du fil JavaFX. Renvoie null si tout va bien, sinon le message d'erreur.
     */
    static String prendre(File png, Collection<Integer> idsSols) {
        if (!ClientModifie.saitCapturer()) return "Le jeu installé ne sait pas prendre de photo : aperçu impossible.";
        enCours = true;
        try {
            Salle.sommeil(600);                        // la mise en valeur de la fenetre s'eteint
            BufferedImage a = photo();
            if (a == null) return "Photo du jeu impossible.";
            BufferedImage img = a;
            List<String> jetons = new ArrayList<>();
            if (idsSols != null) for (Integer id : idsSols) if (id != null && Salle.sol(id) != null) jetons.add("s" + id);
            if (!jetons.isEmpty() && ClientModifie.saitSurligner()) {
                BufferedImage b = null, c = null;
                try {
                    MiseEnValeur.survol(jetons);
                    Salle.sommeil(700);                // GroupeSelection l'envoie au jeu (toutes les 250 ms)
                    b = photo();
                } finally {
                    MiseEnValeur.survol(List.of());
                }
                if (b != null) {
                    Salle.sommeil(700);
                    c = photo();
                    BufferedImage coupee = decouper(a, b, c);
                    if (coupee != null) img = coupee;
                    else Journal.debug("Aperçu wired : aucun changement net, photo entière gardée.");
                }
            }
            ImageIO.write(reduire(img), "png", png);
            Capture.rendre(png);
            return null;
        } catch (Throwable t) {
            return "Aperçu impossible : " + t.getMessage();
        } finally {
            enCours = false;
        }
    }

    private static BufferedImage photo() {
        Capture.Ou<File> r = Capture.captureParLeJeu(5000);
        if (r.valeur == null) { Journal.debug("Aperçu wired : " + r.erreur); return null; }
        try { return ImageIO.read(r.valeur); }
        catch (Throwable t) { return null; }
        finally { r.valeur.delete(); }
    }

    /** Cadre de ce qui change entre a et b (sans ce qui bouge entre a et c), decoupe dans a. */
    private static BufferedImage decouper(BufferedImage a, BufferedImage b, BufferedImage c) {
        int w = a.getWidth(), h = a.getHeight();
        if (b.getWidth() != w || b.getHeight() != h) return null;
        boolean avecC = c != null && c.getWidth() == w && c.getHeight() == h;
        int cw = (w + CARRE - 1) / CARRE, ch = (h + CARRE - 1) / CARRE;
        int[] change = new int[cw * ch];
        boolean[] anime = new boolean[cw * ch];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y);
                int k = (y / CARRE) * cw + x / CARRE;
                if (different(p, b.getRGB(x, y))) change[k]++;
                if (avecC && different(p, c.getRGB(x, y))) anime[k] = true;
            }
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                int k = j * cw + i;
                if (change[k] < SEUIL_CARRE || anime[k]) continue;
                if (i < x0) x0 = i; if (i > x1) x1 = i;
                if (j < y0) y0 = j; if (j > y1) y1 = j;
            }
        }
        if (x1 < 0) return null;
        int px0 = Math.max(0, x0 * CARRE - MARGE), py0 = Math.max(0, y0 * CARRE - MARGE);
        int px1 = Math.min(w - 1, (x1 + 1) * CARRE - 1 + MARGE), py1 = Math.min(h - 1, (y1 + 1) * CARRE - 1 + MARGE);
        return a.getSubimage(px0, py0, px1 - px0 + 1, py1 - py0 + 1);
    }

    /** Ecart net (pas un simple bruit de quelques niveaux). */
    private static boolean different(int p, int q) {
        int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255))
                + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
        return d > 60;
    }

    private static BufferedImage reduire(BufferedImage img) {
        if (img.getWidth() <= LARGEUR_MAX) return img;
        int w = LARGEUR_MAX, h = Math.max(1, img.getHeight() * w / img.getWidth());
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return r;
    }
}

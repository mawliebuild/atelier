package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Apercu en image d'une copie d'appart : une photo prise PAR LE JEU (client
 * modifie, Capture.captureParLeJeu), enregistree a cote du preset
 * (« nom.png » pres de « nom.json »).
 *
 * Appart entier : la photo telle quelle. Zone : deux photos, sans puis avec
 * les mobis de la zone en valeur (halo du client) ; les pixels qui prennent
 * la couleur de mise en valeur (StyleSurlignage) donnent le cadre de la zone
 * dans le canevas, qu'on decoupe (avec une marge) dans la premiere photo. Si
 * rien ne change (aucun mobi, client sans halo...), la photo entiere est
 * gardee et la raison va au journal.
 */
final class ApercuPreset {

    private ApercuPreset() { }

    /** Largeur maxi de l'apercu enregistre. */
    private static final int LARGEUR_MAX = 900;
    /** Marge autour du cadre de la zone, en pixels de la photo. */
    private static final int MARGE = 24;
    /** Marge du haut, un peu plus grande (bords des mobis hauts, ombres). */
    private static final int MARGE_HAUT = 40;

    /** Le fichier d'apercu d'un preset (« nom.json » -> « nom.png »). */
    static File de(File preset) {
        String n = preset.getName().replaceFirst("\\.json$", "");
        return new File(preset.getParentFile(), n + ".png");
    }

    /**
     * Prend l'apercu et l'enregistre dans « png ». A appeler hors du fil JavaFX.
     * zone = true : seulement la zone choisie (Zone). Renvoie null si tout va
     * bien, sinon le message d'erreur.
     */
    static String prendre(File png, boolean zone) {
        if (!ClientModifie.saitCapturer()) return "Le jeu installé ne sait pas prendre de photo : aperçu impossible.";
        Capture.Ou<File> a = Capture.captureParLeJeu(5000);
        if (a.valeur == null) return a.erreur;
        try {
            BufferedImage img = ImageIO.read(a.valeur);
            if (img == null) return "Photo du jeu illisible.";
            if (zone && Zone.definie()) {
                BufferedImage coupee = decouperZone(img);
                if (coupee != null) img = coupee;
            } else if (zone) {
                Journal.debug("aperçu zone : plus de zone choisie, photo entière");
            }
            ImageIO.write(reduire(img), "png", png);
            Capture.rendre(png);
            return null;
        } catch (Throwable t) {
            return "Aperçu impossible : " + t.getMessage();
        } finally {
            a.valeur.delete();
        }
    }

    /**
     * Cadre de la zone : difference entre la photo et une photo avec les mobis
     * de la zone en valeur (sols qui touchent la zone, muraux de ses cases, comme
     * GroupeSelection.zone). Les deux photos viennent du meme canevas du jeu :
     * zoom, defilement, ecran Retina ou Windows n'y changent rien, et les mobis
     * hauts ou muraux sont dans le cadre puisque c'est leur halo qu'on mesure.
     * null (photo entiere) si le cadre ne se trouve pas ; la raison va au journal.
     */
    private static BufferedImage decouperZone(BufferedImage sans) {
        List<String> jetons = new ArrayList<>();
        for (HFloorItem it : Zone.mobisTouches())
            if (!GroupeFantomes.estFantome(it.getId())) jetons.add("s" + it.getId());
        for (HWallItem w : Salle.murs()) {
            if (GroupeFantomes.estFantome(w.getId())) continue;
            int[] c = GroupeCalcul.caseMur(w.getLocation());
            if (c != null && Zone.contient(c[0], c[1])) jetons.add("m" + w.getId());
        }
        if (jetons.isEmpty()) { Journal.debug("aperçu zone : aucun mobi dans la zone, photo entière"); return null; }
        if (!ClientModifie.saitSurligner()) { Journal.debug("aperçu zone : le jeu ne sait pas mettre en valeur, photo entière"); return null; }
        BufferedImage avec;
        try {
            MiseEnValeur.survol(jetons);
            Salle.sommeil(700);                       // GroupeSelection l'envoie au jeu (toutes les 250 ms)
            Capture.Ou<File> b = Capture.captureParLeJeu(5000);
            if (b.valeur == null) { Journal.debug("aperçu zone : seconde photo absente (" + b.erreur + "), photo entière"); return null; }
            try { avec = ImageIO.read(b.valeur); } finally { b.valeur.delete(); }
        } catch (Throwable t) {
            Journal.debug("aperçu zone : seconde photo impossible (" + t + "), photo entière");
            return null;
        } finally {
            MiseEnValeur.survol(List.of());
        }
        if (avec == null || avec.getWidth() != sans.getWidth() || avec.getHeight() != sans.getHeight()) {
            Journal.debug("aperçu zone : les deux photos ne se superposent pas (vue déplacée ?), photo entière");
            return null;
        }
        int w = sans.getWidth(), h = sans.getHeight();
        int halo = couleurHalo();
        boolean[] m = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = sans.getRGB(x, y), q = avec.getRGB(x, y);
                m[y * w + x] = different(p, q) && versHalo(p, q, halo);
            }
        // un pixel seul (mobi anime ailleurs) ne compte pas : il faut un voisin
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y * w + x] || !voisin(m, w, h, x, y)) continue;
                if (x < x0) x0 = x; if (x > x1) x1 = x;
                if (y < y0) y0 = y; if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) { Journal.debug("aperçu zone : halo introuvable sur la photo, photo entière"); return null; }
        x0 = Math.max(0, x0 - MARGE); y0 = Math.max(0, y0 - MARGE_HAUT);
        x1 = Math.min(w - 1, x1 + MARGE); y1 = Math.min(h - 1, y1 + MARGE);
        return sans.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** Ecart net (pas un simple bruit d'animation de quelques niveaux). */
    private static boolean different(int p, int q) {
        return ecart(p, q) > 60;
    }

    private static int ecart(int p, int q) {
        return Math.abs(((p >> 16) & 255) - ((q >> 16) & 255))
                + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
    }

    /**
     * Le pixel s'est rapproche nettement de la couleur de mise en valeur
     * (StyleSurlignage : contour plein ou remplissage en transparence).
     */
    private static boolean versHalo(int p, int q, int halo) {
        return ecart(q, halo) < ecart(p, halo) - 40;
    }

    /** Couleur choisie dans les reglages de mise en valeur (RRGGBB). */
    private static int couleurHalo() {
        try { return Integer.parseInt(StyleSurlignage.couleur(), 16); }
        catch (Throwable t) { return Integer.parseInt(StyleSurlignage.COULEUR_DEFAUT, 16); }
    }

    private static boolean voisin(boolean[] m, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int a = x + dx, b = y + dy;
                if (a >= 0 && b >= 0 && a < w && b < h && m[b * w + a]) return true;
            }
        return false;
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

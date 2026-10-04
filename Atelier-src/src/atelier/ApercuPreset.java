package atelier;

import gearth.extensions.parsers.HFloorItem;

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
 * les mobis de la zone en valeur (halo du client) ; les pixels qui deviennent
 * jaunes (le halo) donnent le cadre de la zone a l'ecran, qu'on decoupe (avec une marge) dans
 * la premiere photo. Si rien ne change (aucun mobi, client sans halo...),
 * la photo entiere est gardee.
 */
final class ApercuPreset {

    private ApercuPreset() { }

    /** Largeur maxi de l'apercu enregistre. */
    private static final int LARGEUR_MAX = 900;
    /** Marge autour du cadre de la zone, en pixels de la photo. */
    private static final int MARGE = 24;

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

    /** Cadre de la zone : difference entre la photo et une photo avec la zone en valeur. */
    private static BufferedImage decouperZone(BufferedImage sans) {
        List<String> jetons = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (Zone.contient(it.getTile().getX(), it.getTile().getY())) jetons.add("s" + it.getId());
        }
        if (jetons.isEmpty() || !ClientModifie.saitSurligner()) return null;
        BufferedImage avec;
        try {
            MiseEnValeur.survol(jetons);
            Salle.sommeil(700);                       // GroupeSelection l'envoie au jeu (toutes les 250 ms)
            Capture.Ou<File> b = Capture.captureParLeJeu(5000);
            if (b.valeur == null) return null;
            try { avec = ImageIO.read(b.valeur); } finally { b.valeur.delete(); }
        } catch (Throwable t) {
            return null;
        } finally {
            MiseEnValeur.survol(List.of());
        }
        if (avec == null || avec.getWidth() != sans.getWidth() || avec.getHeight() != sans.getHeight()) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < sans.getHeight(); y++) {
            for (int x = 0; x < sans.getWidth(); x++) {
                int q = avec.getRGB(x, y);
                if (jaune(q) && different(sans.getRGB(x, y), q)) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return null;
        x0 = Math.max(0, x0 - MARGE); y0 = Math.max(0, y0 - MARGE);
        x1 = Math.min(sans.getWidth() - 1, x1 + MARGE); y1 = Math.min(sans.getHeight() - 1, y1 + MARGE);
        return sans.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** Ecart net (pas un simple bruit d'animation de quelques niveaux). */
    private static boolean different(int p, int q) {
        int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255))
                + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
        return d > 60;
    }

    /** Proche du jaune du halo (0xFFE14A) : ecarte les mobis animes ailleurs. */
    private static boolean jaune(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r > 170 && g > 140 && b < g - 50;
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

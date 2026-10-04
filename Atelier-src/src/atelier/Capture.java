package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.stuffdata.IStuffData;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Moteur de la Capture de l'appart.
 *
 * Le client Flash sait rendre TOUTE la salle en image avec la commande chat
 * « :screenshot », quelle que soit la taille de l'ecran — sur fond transparent,
 * sans la couleur de decor (le toner). L'Atelier ne dessine rien : il fait
 * taper la commande dans le jeu (declencher : AppleScript sur Mac, SendInput
 * sous Windows, voir CaptureWindows), recupere l'image que
 * le jeu vient d'ecrire sur le disque, puis :
 *
 *   1. repere l'arriere-plan (deja transparent, ou un aplat uni) ;
 *   2. detoure : l'exterieur = ce qui est relie au bord de l'image sans
 *      traverser la salle (les zones noires DANS la salle restent) ;
 *   3. recadre sur la salle, petite marge ;
 *   4. pose la salle sur un fond : la couleur de decor (toner HSL -> RGB),
 *      ou rien (transparent) ; la salle elle-meme n'est JAMAIS teintee ;
 *   5. enregistre en PNG, JPG (fond uni) ou GIF (image seule ou animation).
 *
 * Tout le traitement d'image travaille sur des tableaux ARGB : il se teste hors
 * de l'appli (aucune dependance a JavaFX ni au proxy dans cette partie).
 * Aucun appel a java.awt.Toolkit : BufferedImage et ImageIO suffisent.
 */
public final class Capture {

    private Capture() { }

    // =================================================================
    //  Reglages et resultats
    // =================================================================

    /** Les options du traitement. */
    public static final class Reglages {
        /** Ecart maximal (par canal, 0-255) pour qu'un pixel compte comme le fond. */
        public int tolerance = 12;
        /** Marge autour de la salle, en pixels. */
        public int marge = 6;
        /** Garder l'arriere-plan : simple recadrage, rien de transparent. */
        public boolean garderFond = false;
        /** Ne garder que le plus grand ensemble connexe (la salle). */
        public boolean seulementSalle = false;
        /** Ecart (pixels) franchi pour relier deux morceaux de la salle. */
        public int ecart = 4;
        /**
         * Couleur posee DERRIERE la salle (0xRRGGBB) : l'exterieur detoure, la
         * marge et les trous transparents. -1 = fond transparent. La salle
         * (sol, murs, mobis) garde ses couleurs telles que rendues par le jeu.
         */
        public int fond = -1;
    }

    /**
     * Une image traitee AVANT recadrage final : la salle seule (sur son
     * propre cadre serre), et sa place dans l'image d'origine. C'est ce qu'on
     * garde pour assembler un GIF anime avec la meme decoupe.
     */
    public static final class Planche {
        public final BufferedImage image;   // ARGB, cadre serre, sans marge
        public final int x, y;              // coin dans l'image d'origine
        public final int largeurSource, hauteurSource;
        public final int fond;              // couleur d'arriere-plan reperee (ARGB)
        public final boolean fondTransparent;
        public Planche(BufferedImage image, int x, int y, int ls, int hs, int fond, boolean ft) {
            this.image = image; this.x = x; this.y = y;
            this.largeurSource = ls; this.hauteurSource = hs;
            this.fond = fond; this.fondTransparent = ft;
        }
    }

    // =================================================================
    //  Couleur de decor
    // =================================================================

    /**
     * HSL (les trois sur 0..255, comme le paquet et le stuffdata du toner)
     * vers 0xRRGGBB. teinte 255 = 360 degres.
     */
    public static int hslVersRgb(int teinte, int saturation, int luminosite) {
        double h = (teinte & 0xFF) / 255.0 * 360.0;
        double s = (saturation & 0xFF) / 255.0;
        double l = (luminosite & 0xFF) / 255.0;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double hp = (h % 360) / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r = 0, g = 0, b = 0;
        if (hp < 1)      { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else             { r = c; b = x; }
        double m = l - c / 2;
        int R = clamp((int) Math.round((r + m) * 255));
        int G = clamp((int) Math.round((g + m) * 255));
        int B = clamp((int) Math.round((b + m) * 255));
        return (R << 16) | (G << 8) | B;
    }

    /** Etat d'un toner lu dans la salle. */
    public static final class Toner {
        public final int id, etat, teinte, saturation, luminosite;
        Toner(int id, int etat, int h, int s, int l) {
            this.id = id; this.etat = etat; teinte = h; saturation = s; luminosite = l;
        }
        public boolean allume() { return etat != 0; }
        public int couleur() { return hslVersRgb(teinte, saturation, luminosite); }
        @Override public String toString() {
            return "toner " + id + (allume() ? " allumé" : " éteint")
                    + " (teinte " + teinte + ", saturation " + saturation + ", luminosité " + luminosite + ")";
        }
    }

    /**
     * Le toner de la salle (mobi roombg_color), lu dans EtatSalle de
     * le moteur de l'Atelier — qui suit ObjectUpdate / ObjectDataUpdate. Le stuffdata est un
     * IntArrayStuffData : [etat, teinte, saturation, luminosite]. Prefere un
     * toner allume s'il y en a plusieurs. null si aucun ou illisible.
     */
    public static Toner tonerDeLaSalle() {
        Toner trouve = null;
        try {
            for (HFloorItem it : Salle.sols()) {
                String c = Salle.classe(it.getTypeId(), false);
                if (c == null || !c.toLowerCase(Locale.ROOT).startsWith("roombg_color")) continue;
                Toner t = lireToner(it);
                if (t == null) continue;
                if (trouve == null || (t.allume() && !trouve.allume())) trouve = t;
            }
        } catch (Throwable ignored) { }
        return trouve;
    }

    private static Toner lireToner(HFloorItem it) {
        try {
            IStuffData sd = it.getStuff();
            if (!(sd instanceof List)) return null;
            List<?> v = (List<?>) sd;
            if (v.size() < 4) return null;
            int[] n = new int[4];
            for (int i = 0; i < 4; i++) n[i] = ((Number) v.get(i)).intValue();
            return new Toner(it.getId(), n[0], n[1], n[2], n[3]);
        } catch (Throwable t) { return null; }
    }

    // =================================================================
    //  Arriere-plan et decoupe
    // =================================================================

    static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    static boolean proche(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tol
            && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tol
            && Math.abs((a & 0xFF) - (b & 0xFF)) <= tol;
    }

    /** Part des pixels du bord quasi transparents (alpha < 16). */
    static double partBordTransparent(int[] px, int w, int h) {
        int n = 0, t = 0;
        for (int x = 0; x < w; x++) {
            n += 2;
            if ((px[x] >>> 24) < 16) t++;
            if ((px[(h - 1) * w + x] >>> 24) < 16) t++;
        }
        for (int y = 1; y < h - 1; y++) {
            n += 2;
            if ((px[y * w] >>> 24) < 16) t++;
            if ((px[y * w + w - 1] >>> 24) < 16) t++;
        }
        return n == 0 ? 0 : (double) t / n;
    }

    /** Couleur opaque la plus frequente sur le bord (ARGB, alpha force a 255). */
    static int couleurDuBord(int[] px, int w, int h) {
        HashMap<Integer, Integer> c = new HashMap<>();
        java.util.function.IntConsumer compter = i -> {
            int p = px[i];
            if ((p >>> 24) < 16) return;
            c.merge(p | 0xFF000000, 1, Integer::sum);
        };
        for (int x = 0; x < w; x++) { compter.accept(x); compter.accept((h - 1) * w + x); }
        for (int y = 1; y < h - 1; y++) { compter.accept(y * w); compter.accept(y * w + w - 1); }
        int meilleur = 0xFF000000, n = -1;
        for (Map.Entry<Integer, Integer> e : c.entrySet())
            if (e.getValue() > n) { n = e.getValue(); meilleur = e.getKey(); }
        return meilleur;
    }

    /**
     * Masque « appartient a la salle » : alpha suffisant si le fond est deja
     * transparent, sinon tout ce qui s'ecarte de la couleur de fond.
     */
    static boolean[] masque(int[] px, boolean fondTransparent, int fond, int tol) {
        boolean[] m = new boolean[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if ((p >>> 24) < 16) continue;
            m[i] = fondTransparent || !proche(p, fond, tol);
        }
        return m;
    }

    /** Dilatation carree de rayon r (separable, fenetre glissante). */
    static boolean[] dilater(boolean[] m, int w, int h, int r) {
        if (r <= 0) return m.clone();
        boolean[] a = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            int base = y * w, n = 0;
            for (int x = 0; x < Math.min(r, w); x++) if (m[base + x]) n++;
            for (int x = 0; x < w; x++) {
                int ent = x + r, sor = x - r - 1;
                if (ent < w && m[base + ent]) n++;
                if (sor >= 0 && m[base + sor]) n--;
                a[base + x] = n > 0;
            }
        }
        boolean[] b = new boolean[m.length];
        for (int x = 0; x < w; x++) {
            int n = 0;
            for (int y = 0; y < Math.min(r, h); y++) if (a[y * w + x]) n++;
            for (int y = 0; y < h; y++) {
                int ent = y + r, sor = y - r - 1;
                if (ent < h && a[ent * w + x]) n++;
                if (sor >= 0 && a[sor * w + x]) n--;
                b[y * w + x] = n > 0;
            }
        }
        return b;
    }

    /**
     * Garde le plus grand ensemble connexe (8 voisins) du masque, apres avoir
     * relie les morceaux separes de moins de « ecart » pixels.
     */
    static boolean[] plusGrandeComposante(boolean[] m, int w, int h, int ecart) {
        boolean[] d = dilater(m, w, h, ecart);
        int[] etiq = new int[m.length];
        int[] file = new int[m.length];
        int meilleure = 0, taille = 0, courante = 0;
        for (int s = 0; s < m.length; s++) {
            if (!d[s] || etiq[s] != 0) continue;
            courante++;
            int tete = 0, queue = 0, n = 0;
            file[queue++] = s; etiq[s] = courante;
            while (tete < queue) {
                int i = file[tete++];
                if (m[i]) n++;
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = yy * w + xx;
                        if (d[j] && etiq[j] == 0) { etiq[j] = courante; file[queue++] = j; }
                    }
                }
            }
            if (n > taille) { taille = n; meilleure = courante; }
        }
        boolean[] r = new boolean[m.length];
        if (meilleure == 0) return r;
        for (int i = 0; i < m.length; i++) r[i] = m[i] && etiq[i] == meilleure;
        return r;
    }

    /**
     * L'exterieur : ce qui n'est pas la salle ET touche le bord de l'image par
     * un chemin hors salle (4 voisins). Les trous enfermes dans la salle (un
     * contour noir sur fond noir, par exemple) restent opaques.
     */
    static boolean[] exterieur(boolean[] salle, int w, int h) {
        boolean[] ext = new boolean[salle.length];
        int[] file = new int[salle.length];
        int queue = 0;
        for (int x = 0; x < w; x++) {
            for (int i : new int[]{x, (h - 1) * w + x})
                if (!salle[i] && !ext[i]) { ext[i] = true; file[queue++] = i; }
        }
        for (int y = 0; y < h; y++) {
            for (int i : new int[]{y * w, y * w + w - 1})
                if (!salle[i] && !ext[i]) { ext[i] = true; file[queue++] = i; }
        }
        int tete = 0;
        while (tete < queue) {
            int i = file[tete++];
            int x = i % w, y = i / w;
            if (x > 0)     { int j = i - 1; if (!salle[j] && !ext[j]) { ext[j] = true; file[queue++] = j; } }
            if (x < w - 1) { int j = i + 1; if (!salle[j] && !ext[j]) { ext[j] = true; file[queue++] = j; } }
            if (y > 0)     { int j = i - w; if (!salle[j] && !ext[j]) { ext[j] = true; file[queue++] = j; } }
            if (y < h - 1) { int j = i + w; if (!salle[j] && !ext[j]) { ext[j] = true; file[queue++] = j; } }
        }
        return ext;
    }

    /**
     * Le traitement complet d'une image du jeu : arriere-plan, exterieur rendu
     * transparent, cadre serre. AUCUNE teinte : le fond de couleur est pose
     * ensuite, par cadrer. Leve IllegalStateException si l'image ne contient
     * rien d'autre que le fond.
     */
    public static Planche traiter(BufferedImage source, Reglages r) {
        int w = source.getWidth(), h = source.getHeight();
        int[] px = source.getRGB(0, 0, w, h, null, 0, w);

        boolean fondTransparent = partBordTransparent(px, w, h) > 0.5;
        int fond = fondTransparent ? 0 : couleurDuBord(px, w, h);

        boolean[] salle = masque(px, fondTransparent, fond, Math.max(0, r.tolerance));
        if (r.seulementSalle) salle = plusGrandeComposante(salle, w, h, Math.max(0, r.ecart));

        // Boite englobante de la salle.
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (salle[y * w + x]) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
        if (x1 < 0) throw new IllegalStateException("Aucune salle trouvée dans l'image (rien que le fond).");

        if (!r.garderFond) {
            boolean[] ext = exterieur(salle, w, h);
            for (int i = 0; i < px.length; i++) if (ext[i]) px[i] = 0;
        }

        int lw = x1 - x0 + 1, lh = y1 - y0 + 1;
        BufferedImage out = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, lw, lh, px, y0 * w + x0, w);
        return new Planche(out, x0, y0, w, h, fond, fondTransparent);
    }

    /**
     * Pose la planche sur son cadre final, marge comprise. Le fond du cadre :
     * l'arriere-plan d'origine si on le garde, sinon r.fond (couleur de decor)
     * ou transparent. La planche est posee PAR-DESSUS (alpha compose) : ses
     * pixels opaques — toute la salle — restent intacts, seuls l'exterieur
     * detoure et les pixels transparents prennent la couleur.
     */
    public static BufferedImage cadrer(Planche p, Reglages r) {
        return cadrer(p, p.x, p.y, p.image.getWidth(), p.image.getHeight(), r);
    }

    /** Pose la planche dans le cadre (cx, cy, cw, ch) de l'image d'origine, marge en plus. */
    public static BufferedImage cadrer(Planche p, int cx, int cy, int cw, int ch, Reglages r) {
        int m = Math.max(0, r.marge);
        int W = cw + 2 * m, H = ch + 2 * m;
        int[] dst = new int[W * H];
        if (r.garderFond && !p.fondTransparent) Arrays.fill(dst, p.fond | 0xFF000000);
        else if (!r.garderFond && r.fond >= 0) Arrays.fill(dst, (r.fond & 0xFFFFFF) | 0xFF000000);
        int dx = p.x - cx + m, dy = p.y - cy + m;
        int pw = p.image.getWidth(), ph = p.image.getHeight();
        int[] src = p.image.getRGB(0, 0, pw, ph, null, 0, pw);
        for (int y = 0; y < ph; y++) {
            int ty = y + dy;
            if (ty < 0 || ty >= H) continue;
            for (int x = 0; x < pw; x++) {
                int tx = x + dx;
                if (tx < 0 || tx >= W) continue;
                int i = ty * W + tx;
                dst[i] = r.garderFond ? src[y * pw + x] : pardessus(src[y * pw + x], dst[i]);
            }
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, W, H, dst, 0, W);
        return out;
    }

    /** Compose « s par-dessus d » (ARGB non premultiplie). s opaque -> s tel quel. */
    static int pardessus(int s, int d) {
        int sa = s >>> 24;
        if (sa == 255) return s;
        if (sa == 0) return d;
        int da = d >>> 24;
        if (da == 0) return s;
        // a = sa + da*(1-sa) ; c = (cs*sa + cd*da*(1-sa)) / a  (sur 0..255)
        int dPart = da * (255 - sa) / 255;
        int a = sa + dPart;
        int rr = (((s >> 16) & 0xFF) * sa + ((d >> 16) & 0xFF) * dPart) / a;
        int gg = (((s >> 8) & 0xFF) * sa + ((d >> 8) & 0xFF) * dPart) / a;
        int bb = ((s & 0xFF) * sa + (d & 0xFF) * dPart) / a;
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    // =================================================================
    //  Ecriture
    // =================================================================

    public enum Format {
        PNG("png"), JPG("jpg"), GIF("gif");
        public final String ext;
        Format(String e) { ext = e; }
    }

    public static void ecrirePng(BufferedImage img, File f) throws IOException {
        if (!ImageIO.write(img, "png", f)) throw new IOException("aucun encodeur PNG");
    }

    /** JPG : la transparence est posee sur un fond uni (0xRRGGBB). */
    public static void ecrireJpg(BufferedImage img, int fondRgb, File f) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int fr = (fondRgb >> 16) & 0xFF, fg = (fondRgb >> 8) & 0xFF, fb = fondRgb & 0xFF;
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24;
            int r = (((p >> 16) & 0xFF) * a + fr * (255 - a)) / 255;
            int g = (((p >> 8) & 0xFF) * a + fg * (255 - a)) / 255;
            int b = ((p & 0xFF) * a + fb * (255 - a)) / 255;
            px[i] = (r << 16) | (g << 8) | b;
        }
        rgb.setRGB(0, 0, w, h, px, 0, w);
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            wr.setOutput(out);
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.95f);
            wr.write(null, new IIOImage(rgb, null, null), p);
        } finally { wr.dispose(); }
    }

    /**
     * GIF, une image ou une animation en boucle infinie. Transparence par
     * couleur cle : l'index 0 de chaque palette. delaiMs ignore pour une
     * image seule.
     */
    public static void ecrireGif(List<BufferedImage> images, int delaiMs, File f) throws IOException {
        if (images == null || images.isEmpty()) throw new IOException("aucune image");
        ImageWriter wr = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            wr.setOutput(out);
            wr.prepareWriteSequence(null);
            boolean anime = images.size() > 1;
            for (int k = 0; k < images.size(); k++) {
                BufferedImage ind = indexer(images.get(k));
                ImageWriteParam param = wr.getDefaultWriteParam();
                IIOMetadata md = wr.getDefaultImageMetadata(new ImageTypeSpecifier(ind), param);
                String nom = md.getNativeMetadataFormatName();
                IIOMetadataNode racine = (IIOMetadataNode) md.getAsTree(nom);

                IIOMetadataNode gce = enfant(racine, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", anime ? "restoreToBackgroundColor" : "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "TRUE");
                gce.setAttribute("transparentColorIndex", "0");
                gce.setAttribute("delayTime", String.valueOf(anime ? Math.max(2, delaiMs / 10) : 0));

                if (anime && k == 0) {
                    IIOMetadataNode apps = enfant(racine, "ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0});          // boucle infinie
                    apps.appendChild(app);
                }
                md.setFromTree(nom, racine);
                wr.writeToSequence(new IIOImage(ind, null, md), param);
            }
            wr.endWriteSequence();
        } finally { wr.dispose(); }
    }

    private static IIOMetadataNode enfant(IIOMetadataNode racine, String nom) {
        for (int i = 0; i < racine.getLength(); i++)
            if (racine.item(i).getNodeName().equalsIgnoreCase(nom)) return (IIOMetadataNode) racine.item(i);
        IIOMetadataNode n = new IIOMetadataNode(nom);
        racine.appendChild(n);
        return n;
    }

    /**
     * Image indexee 8 bits : index 0 = transparent (alpha < 128), puis 255
     * couleurs au plus. Les graphismes du jeu ont peu de couleurs ; au-dela,
     * on regroupe par paliers (4 bits par canal) et on prend les plus
     * frequentes, chaque pixel allant a la plus proche.
     */
    static BufferedImage indexer(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        HashMap<Integer, Integer> freq = new HashMap<>();
        for (int p : px) if ((p >>> 24) >= 128) freq.merge(p & 0xFFFFFF, 1, Integer::sum);

        List<Integer> palette;
        if (freq.size() <= 255) {
            palette = new ArrayList<>(freq.keySet());
        } else {
            HashMap<Integer, long[]> paliers = new HashMap<>();   // cle -> {n, somme r, g, b}
            for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
                int c = e.getKey(), n = e.getValue();
                int cle = ((c >> 20) & 0xF) << 8 | ((c >> 12) & 0xF) << 4 | ((c >> 4) & 0xF);
                long[] s = paliers.computeIfAbsent(cle, z -> new long[4]);
                s[0] += n; s[1] += (long) ((c >> 16) & 0xFF) * n;
                s[2] += (long) ((c >> 8) & 0xFF) * n; s[3] += (long) (c & 0xFF) * n;
            }
            List<long[]> l = new ArrayList<>(paliers.values());
            l.sort((a, b) -> Long.compare(b[0], a[0]));
            palette = new ArrayList<>();
            for (int i = 0; i < Math.min(255, l.size()); i++) {
                long[] s = l.get(i);
                palette.add((int) (s[1] / s[0]) << 16 | (int) (s[2] / s[0]) << 8 | (int) (s[3] / s[0]));
            }
        }
        int n = palette.size() + 1;
        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        HashMap<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            int c = palette.get(i);
            r[i + 1] = (byte) (c >> 16); g[i + 1] = (byte) (c >> 8); b[i + 1] = (byte) c;
            index.put(c, i + 1);
        }
        IndexColorModel cm = new IndexColorModel(8, Math.max(2, n), r, g, b, 0);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
        byte[] dst = ((java.awt.image.DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if ((p >>> 24) < 128) { dst[i] = 0; continue; }
            int c = p & 0xFFFFFF;
            Integer k = index.get(c);
            if (k == null) {
                int best = 1; long d = Long.MAX_VALUE;
                for (int j = 0; j < palette.size(); j++) {
                    int q = palette.get(j);
                    long dr = ((c >> 16) & 0xFF) - ((q >> 16) & 0xFF);
                    long dg = ((c >> 8) & 0xFF) - ((q >> 8) & 0xFF);
                    long db = (c & 0xFF) - (q & 0xFF);
                    long dd = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                    if (dd < d) { d = dd; best = j + 1; }
                }
                k = best;
                index.put(c, k);
            }
            dst[i] = (byte) (int) k;
        }
        return out;
    }

    /**
     * Assemble des planches de la meme salle avec UNE meme decoupe (l'union
     * de leurs cadres). Les planches d'une autre taille d'origine sont
     * ecartees.
     */
    public static List<BufferedImage> assembler(List<Planche> planches, Reglages r) {
        List<BufferedImage> out = new ArrayList<>();
        if (planches.isEmpty()) return out;
        Planche ref = planches.get(planches.size() - 1);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        List<Planche> gardees = new ArrayList<>();
        for (Planche p : planches) {
            if (p.largeurSource != ref.largeurSource || p.hauteurSource != ref.hauteurSource) continue;
            gardees.add(p);
            x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y);
            x1 = Math.max(x1, p.x + p.image.getWidth()); y1 = Math.max(y1, p.y + p.image.getHeight());
        }
        for (Planche p : gardees) out.add(cadrer(p, x0, y0, x1 - x0, y1 - y0, r));
        return out;
    }

    // =================================================================
    //  Dossiers et fichiers
    // =================================================================

    /** L'utilisateur reel : celui qui a lance sudo, sinon l'utilisateur courant. */
    public static String utilisateurReel() {
        String s = System.getenv("SUDO_USER");
        if (s != null && !s.isBlank() && !"root".equals(s)) return s;
        return System.getProperty("user.name");
    }

    /** Sous Windows : capture par user32 / gdi32 (CaptureWindows) au lieu d'AppleScript. */
    static final boolean WINDOWS = Dossiers.WINDOWS;

    /** Dossier personnel de l'utilisateur reel (pas /var/root sous sudo ; user.home sous Windows). */
    public static File maisonReelle() {
        return Dossiers.maison();
    }

    private static boolean sousSudo() {
        if (WINDOWS) return false;
        String s = System.getenv("SUDO_USER");
        return s != null && !s.isBlank() && "root".equals(System.getProperty("user.name"));
    }

    /** Images › Atelier de l'utilisateur reel (~/Pictures/Atelier ; Windows : dossier Images du compte), cree au besoin. */
    public static File dossierSortie() {
        File d = new File(Dossiers.images(), "Atelier");
        if (!d.isDirectory() && d.mkdirs()) rendre(d);
        return d;
    }

    /** Sous sudo, rend le fichier a l'utilisateur reel. */
    public static void rendre(File f) {
        if (!sousSudo()) return;
        try {
            Process p = new ProcessBuilder("/usr/sbin/chown", utilisateurReel() + ":staff", f.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
    }

    /** NomDeLaSalle_aaaa-mm-jj_hh-mm-ss.ext, sans caractere interdit. */
    public static File nomDeFichier(String salle, Format f) {
        String base = (salle == null || salle.isBlank()) ? "Salle" : salle;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) base = "Salle";
        if (base.length() > 60) base = base.substring(0, 60).trim();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        File d = dossierSortie();
        File out = new File(d, base + "_" + date + "." + f.ext);
        for (int i = 2; out.exists(); i++) out = new File(d, base + "_" + date + "_" + i + "." + f.ext);
        return out;
    }

    /**
     * Dossiers surveilles par defaut, chez l'utilisateur reel : Bureau,
     * Images, Telechargements, Documents (sous Windows, aussi ceux de
     * OneDrive), le dossier personnel lui-meme, et leurs sous-dossiers (un
     * niveau) dont le nom contient « habbo ».
     */
    public static List<File> dossiersParDefaut() {
        File m = maisonReelle();
        List<File> l = new ArrayList<>();
        for (File d : Dossiers.habituels()) {
            if (l.contains(d)) continue;
            l.add(d);
            File[] sous = d.listFiles(File::isDirectory);
            if (sous != null)
                for (File s : sous)
                    if (estDossierHabbo(s)) l.add(s);
        }
        l.add(m);
        File[] sous = m.listFiles(File::isDirectory);
        if (sous != null)
            for (File s : sous)
                if (estDossierHabbo(s)) l.add(s);
        return l;
    }

    private static boolean estDossierHabbo(File d) {
        String n = d.getName().toLowerCase(Locale.ROOT);
        return n.contains("habbo") && !n.endsWith(".app") && !n.startsWith(".");
    }

    public static boolean estImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && !n.startsWith(".")
                && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"));
    }

    // =================================================================
    //  Declencher « :screenshot » dans le jeu
    // =================================================================

    /**
     * Fait taper « :screenshot » + Entree dans l'appli Habbo, par AppleScript
     * (System Events). keystroke tape des CARACTERES : la disposition du
     * clavier (AZERTY) est prise en compte. Repli « coller » : la commande
     * passe par le presse-papiers puis Cmd+V (l'ancien contenu texte est remis).
     *
     * Exige l'autorisation « Accessibilite » (et « Automatisation » vers
     * System Events) pour l'appli qui lance l'Atelier. Sous sudo, osascript est
     * lance dans la session de l'utilisateur reel (launchctl asuser).
     *
     * @return null si c'est parti, sinon un message clair en francais.
     */
    public static String declencher(boolean coller) {
        if (WINDOWS) return CaptureWindows.declencher(coller);
        long pid = ProcessHandle.current().pid();
        List<String> l = new ArrayList<>();
        l.add("tell application \"System Events\"");
        l.add("if not (exists process \"Habbo\") then error \"HABBO_ABSENT\"");
        l.add("set frontmost of process \"Habbo\" to true");
        l.add("end tell");
        l.add("delay 0.6");
        if (coller) {
            l.add("set ancien to missing value");
            l.add("try");
            l.add("set ancien to the clipboard as text");
            l.add("end try");
            l.add("set the clipboard to \":screenshot\"");
            l.add("tell application \"System Events\" to keystroke \"v\" using command down");
        } else {
            l.add("tell application \"System Events\" to keystroke \":screenshot\"");
        }
        l.add("delay 0.2");
        l.add("tell application \"System Events\" to key code 36");
        if (coller) {
            l.add("delay 0.2");
            l.add("try");
            l.add("if ancien is not missing value then set the clipboard to ancien");
            l.add("end try");
        }
        // Rendre la main a l'Atelier, sans echouer si ce n'est pas possible.
        l.add("delay 0.4");
        l.add("try");
        l.add("tell application \"System Events\" to set frontmost of (first process whose unix id is "
                + pid + ") to true");
        l.add("end try");

        return osascript(l)[0];
    }

    /**
     * Lance un script AppleScript (une ligne par element), dans la session de
     * l'utilisateur reel sous sudo.
     * @return {erreur expliquee ou null, sortie brute}
     */
    static String[] osascript(List<String> lignes) {
        List<String> cmd = new ArrayList<>(prefixeUtilisateur());
        cmd.add("/usr/bin/osascript");
        for (String s : lignes) { cmd.add("-e"); cmd.add(s); }
        try {
            Sortie r = executer(new ProcessBuilder(cmd), 20);
            if (r == null)
                return new String[]{"Osascript ne répond pas (une fenêtre d'autorisation macOS attend peut-être une réponse).", ""};
            if (r.code == 0) return new String[]{null, r.texte.trim()};
            return new String[]{expliquer(r.texte), r.texte};
        } catch (Throwable t) {
            return new String[]{"Impossible de lancer osascript : " + t.getMessage(), ""};
        }
    }

    /** Sortie d'un processus termine. */
    static final class Sortie {
        final int code; final String texte;
        Sortie(int code, String texte) { this.code = code; this.texte = texte; }
    }

    /**
     * Lance un processus et attend au plus « secondes ». La sortie (erreurs
     * comprises) va dans un fichier temporaire : un processus bloque (fenetre
     * d'autorisation macOS) ne bloque donc pas la lecture, et le delai sert.
     * null si le delai est depasse (le processus est alors tue).
     */
    static Sortie executer(ProcessBuilder pb, long secondes) throws IOException, InterruptedException {
        File tmp = File.createTempFile("atelier-proc-", ".txt");
        try {
            Process p = pb.redirectErrorStream(true).redirectOutput(tmp).start();
            if (!p.waitFor(secondes, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new Sortie(p.exitValue(), new String(java.nio.file.Files.readAllBytes(tmp.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            tmp.delete();
        }
    }

    /** Traduit une erreur d'osascript en consigne pour l'utilisatrice. */
    static String expliquer(String sortie) {
        String s = sortie == null ? "" : sortie;
        String m = s.toLowerCase(Locale.ROOT);
        if (s.contains("HABBO_ABSENT"))
            return "L'appli Habbo n'est pas ouverte : lance-la et entre dans la salle.";
        if (m.contains("-1719") || m.contains("-25211") || m.contains("assistive") || m.contains("accessibilit"))
            return "macOS bloque la frappe au clavier. Réglages Système › Confidentialité et sécurité › "
                    + "Accessibilité : active Terminal (ou l'appli qui lance l'Atelier, ou java), "
                    + "puis relance l'Atelier.";
        if (m.contains("-1743") || m.contains("not authorized") || m.contains("pas autoris"))
            return "macOS bloque le pilotage de « System Events ». Réglages Système › Confidentialité et "
                    + "sécurité › Automatisation : sous Terminal (ou java), coche « System Events ».";
        return "La commande n'a pas pu être tapée : " + s.trim();
    }

    /**
     * Sous sudo, prefixe pour lancer une commande dans la session graphique
     * de l'utilisateur reel ; sinon rien.
     */
    static List<String> prefixeUtilisateur() {
        if (!sousSudo()) return List.of();
        String u = utilisateurReel();
        try {
            Process p = new ProcessBuilder("/usr/bin/id", "-u", u).redirectErrorStream(true).start();
            String uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (uid.matches("\\d+"))
                return List.of("/bin/launchctl", "asuser", uid, "/usr/bin/sudo", "-u", u);
        } catch (Throwable ignored) { }
        return List.of("/usr/bin/sudo", "-u", u);
    }

    /**
     * Images creees depuis « depuisMs », trouvees par Spotlight dans le
     * dossier de l'utilisateur reel (hors Library, hors nos propres sorties,
     * hors fichiers caches). Liste vide si Spotlight ne repond pas.
     */
    public static List<File> imagesRecentesSpotlight(long depuisMs) {
        if (WINDOWS) return imagesRecentesParcours(maisonReelle(), depuisMs, dossierSortie(), 4, 1500);
        List<File> out = new ArrayList<>();
        long secondes = Math.max(10, (System.currentTimeMillis() - depuisMs) / 1000 + 5);
        File maison = maisonReelle();
        String requete = "kMDItemFSCreationDate >= $time.now(-" + secondes + ")"
                + " && kMDItemContentTypeTree == \"public.image\"";
        try {
            Sortie r = executer(new ProcessBuilder("/usr/bin/mdfind", "-onlyin", maison.getAbsolutePath(), requete), 8);
            if (r == null) return out;
            String sortie = r.texte;
            String exclu = dossierSortie().getAbsolutePath() + "/";
            String lib = new File(maison, "Library").getAbsolutePath() + "/";
            for (String ligne : sortie.split("\n")) {
                String chemin = ligne.trim();
                if (chemin.isEmpty() || chemin.startsWith(exclu) || chemin.startsWith(lib)) continue;
                if (chemin.contains("/.")) continue;
                File f = new File(chemin);
                if (estImage(f) && f.lastModified() >= depuisMs) out.add(f);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * Sans Spotlight (Windows) : parcours du dossier personnel, « profondeur »
     * niveaux au plus et « budgetMs » au plus, a la recherche des images
     * modifiees depuis « depuisMs ». Saute AppData, les dossiers caches
     * (« . », « $ »), les liens et le dossier « exclu » (nos propres photos).
     * On ne lit les fichiers que des dossiers modifies depuis (un fichier cree
     * change la date de son dossier), mais on descend partout.
     */
    static List<File> imagesRecentesParcours(File racine, long depuisMs, File exclu, int profondeur, long budgetMs) {
        List<File> out = new ArrayList<>();
        long fin = System.currentTimeMillis() + budgetMs;
        ArrayDeque<Object[]> file = new ArrayDeque<>();
        file.add(new Object[]{racine, 0});
        String sansCa = exclu == null ? null : exclu.getAbsolutePath();
        while (!file.isEmpty() && System.currentTimeMillis() < fin) {
            Object[] e = file.poll();
            File d = (File) e[0];
            int niveau = (Integer) e[1];
            File[] l = d.listFiles();
            if (l == null) continue;
            boolean recent = d.lastModified() >= depuisMs - 2000;
            for (File f : l) {
                String n = f.getName();
                if (n.startsWith(".") || n.startsWith("$")) continue;
                if (f.isDirectory()) {
                    if (niveau >= profondeur || n.equalsIgnoreCase("AppData") || n.equalsIgnoreCase("Library")
                            || n.equalsIgnoreCase("node_modules") || f.getAbsolutePath().equals(sansCa)) continue;
                    try { if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue; } catch (Throwable ignored) { }
                    file.add(new Object[]{f, niveau + 1});
                } else if (recent && estImage(f) && f.lastModified() >= depuisMs) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    // =================================================================
    //  Mode experimental : fenetre Habbo agrandie, capturee seule
    // =================================================================

    /** Position et taille de la fenetre 1 de Habbo (points), par System Events. */
    public static final class Cadre {
        public final int x, y, l, h;
        public Cadre(int x, int y, int l, int h) { this.x = x; this.y = y; this.l = l; this.h = h; }
        @Override public String toString() { return l + "×" + h + " à " + x + "," + y; }
    }

    /** Resultat d'une operation : la valeur, ou un message d'erreur. */
    /** Ou le client modifie ecrit sa photo (CAPTURE_FICHIER de construire.py : dossier personnel). */
    static final File FICHIER_JEU = new File(maisonReelle(), ".atelier-capture.png");

    /**
     * Photo de la salle prise PAR LE JEU (client modifie, « atelier:capture ») :
     * aucune frappe au clavier ni capture d'ecran, donc aucune autorisation
     * macOS. Le jeu ecrit FICHIER_JEU ; on le deplace dans un fichier a nous
     * (pour les series) et on le renvoie. null + erreur si rien n'arrive.
     */
    public static Ou<File> captureParLeJeu(long attenteMs) {
        Moteur gp = Salle.gp();
        if (gp == null) return new Ou<>(null, "L'Atelier n'est pas relié au jeu : capture impossible.");
        long t0 = System.currentTimeMillis();
        try { java.nio.file.Files.deleteIfExists(FICHIER_JEU.toPath()); } catch (Throwable ignored) { }
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:capture", 0, 0, 0, -1));
        long fin = t0 + attenteMs, avant = -1;
        while (System.currentTimeMillis() < fin) {
            Salle.sommeil(150);
            long t = FICHIER_JEU.length();
            if (t > 0 && t == avant && FICHIER_JEU.lastModified() >= t0 - 2000) {
                try {
                    File dest = File.createTempFile("atelier-capture-", ".png");
                    java.nio.file.Files.move(FICHIER_JEU.toPath(), dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return new Ou<>(dest, null);
                } catch (IOException e) {
                    return new Ou<>(null, "Photo du jeu illisible : " + e.getMessage());
                }
            }
            avant = t;
        }
        return new Ou<>(null, "Le jeu n'a pas renvoyé de photo. Es-tu bien dans un appart ?");
    }

    public static final class Ou<T> {
        public final T valeur; public final String erreur;
        Ou(T v, String e) { valeur = v; erreur = e; }
    }

    private static List<String> versHabbo(String... corps) {
        List<String> l = new ArrayList<>();
        l.add("tell application \"System Events\"");
        l.add("if not (exists process \"Habbo\") then error \"HABBO_ABSENT\"");
        l.add("tell process \"Habbo\"");
        l.addAll(Arrays.asList(corps));
        l.add("end tell");
        l.add("end tell");
        return l;
    }

    /** Lit le cadre de la fenetre du jeu. */
    public static Ou<Cadre> lireCadreHabbo() {
        if (WINDOWS) return CaptureWindows.lireCadre();
        String[] r = osascript(versHabbo("get (position of window 1) & (size of window 1)"));
        if (r[0] != null) return new Ou<>(null, r[0]);
        Cadre c = lireCadreTexte(r[1]);
        return c == null ? new Ou<>(null, "Réponse inattendue de System Events : " + r[1]) : new Ou<>(c, null);
    }

    /** « 0, 25, 1440, 875 » -> Cadre ; null si illisible. */
    static Cadre lireCadreTexte(String t) {
        if (t == null) return null;
        String[] m = t.trim().split("\\s*,\\s*");
        if (m.length != 4) return null;
        try {
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) v[i] = (int) Math.round(Double.parseDouble(m[i].trim()));
            return new Cadre(v[0], v[1], v[2], v[3]);
        } catch (NumberFormatException e) { return null; }
    }

    /** Place la fenetre (position puis taille, puis position a nouveau). null si OK. */
    public static String reglerCadreHabbo(Integer x, Integer y, int l, int h) {
        if (WINDOWS) return CaptureWindows.reglerCadre(x, y, l, h);
        List<String> c = new ArrayList<>();
        if (x != null) c.add("set position of window 1 to {" + x + ", " + y + "}");
        c.add("set size of window 1 to {" + l + ", " + h + "}");
        if (x != null) c.add("set position of window 1 to {" + x + ", " + y + "}");
        return osascript(versHabbo(c.toArray(new String[0])))[0];
    }

    // --- CoreGraphics par JNA

    @com.sun.jna.Structure.FieldOrder({"x", "y", "l", "h"})
    public static class CGRect extends com.sun.jna.Structure {
        public double x, y, l, h;
        public static class ByValue extends CGRect implements com.sun.jna.Structure.ByValue { }
    }

    private interface CG extends com.sun.jna.Library {
        com.sun.jna.Pointer CGWindowListCopyWindowInfo(int option, int relativeTo);
        com.sun.jna.Pointer CGWindowListCreateImage(CGRect.ByValue r, int liste, int idFenetre, int options);
        long CGImageGetWidth(com.sun.jna.Pointer img);
        long CGImageGetHeight(com.sun.jna.Pointer img);
        void CGImageRelease(com.sun.jna.Pointer img);
        com.sun.jna.Pointer CGColorSpaceCreateDeviceRGB();
        void CGColorSpaceRelease(com.sun.jna.Pointer cs);
        com.sun.jna.Pointer CGBitmapContextCreate(com.sun.jna.Pointer data, long l, long h, long bpc,
                                                  long octetsParLigne, com.sun.jna.Pointer cs, int info);
        void CGContextDrawImage(com.sun.jna.Pointer ctx, CGRect.ByValue r, com.sun.jna.Pointer img);
        void CGContextRelease(com.sun.jna.Pointer ctx);
        byte CGPreflightScreenCaptureAccess();
        byte CGRequestScreenCaptureAccess();
    }
    private interface CF extends com.sun.jna.Library {
        long CFArrayGetCount(com.sun.jna.Pointer a);
        com.sun.jna.Pointer CFArrayGetValueAtIndex(com.sun.jna.Pointer a, long i);
        com.sun.jna.Pointer CFDictionaryGetValue(com.sun.jna.Pointer d, com.sun.jna.Pointer k);
        com.sun.jna.Pointer CFStringCreateWithCString(com.sun.jna.Pointer alloc, String s, int enc);
        byte CFStringGetCString(com.sun.jna.Pointer s, byte[] b, long n, int enc);
        byte CFNumberGetValue(com.sun.jna.Pointer n, int type, double[] v);
        void CFRelease(com.sun.jna.Pointer p);
    }
    private static CG cg; private static CF cf;
    private static com.sun.jna.Pointer kNom, kNumero, kCouche, kCadre, kX, kY, kL, kH;

    private static synchronized void chargerCg() {
        if (cg != null) return;
        cf = com.sun.jna.Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CF.class);
        int utf8 = 0x08000100;
        kNom = cf.CFStringCreateWithCString(null, "kCGWindowOwnerName", utf8);
        kNumero = cf.CFStringCreateWithCString(null, "kCGWindowNumber", utf8);
        kCouche = cf.CFStringCreateWithCString(null, "kCGWindowLayer", utf8);
        kCadre = cf.CFStringCreateWithCString(null, "kCGWindowBounds", utf8);
        kX = cf.CFStringCreateWithCString(null, "X", utf8);
        kY = cf.CFStringCreateWithCString(null, "Y", utf8);
        kL = cf.CFStringCreateWithCString(null, "Width", utf8);
        kH = cf.CFStringCreateWithCString(null, "Height", utf8);
        cg = com.sun.jna.Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", CG.class);
    }

    private static double nombre(com.sun.jna.Pointer d, com.sun.jna.Pointer k) {
        com.sun.jna.Pointer v = d == null ? null : cf.CFDictionaryGetValue(d, k);
        if (v == null) return Double.NaN;
        double[] r = {0};
        cf.CFNumberGetValue(v, 13, r);                      // kCFNumberDoubleType
        return r[0];
    }

    /**
     * La fenetre « Habbo » (meme reconnaissance qu'Ancrage, toutes fenetres,
     * meme hors ecran) : {numero, x, y, largeur, hauteur} en points, ou null.
     */
    static double[] fenetreHabboCg() {
        chargerCg();
        com.sun.jna.Pointer liste = cg.CGWindowListCopyWindowInfo(0, 0);   // toutes les fenetres
        if (liste == null) return null;
        try {
            double[] meilleure = null;
            byte[] tampon = new byte[128];
            long n = cf.CFArrayGetCount(liste);
            for (long i = 0; i < n; i++) {
                com.sun.jna.Pointer d = cf.CFArrayGetValueAtIndex(liste, i);
                com.sun.jna.Pointer v = cf.CFDictionaryGetValue(d, kNom);
                if (v == null) continue;
                Arrays.fill(tampon, (byte) 0);
                if (cf.CFStringGetCString(v, tampon, tampon.length, 0x08000100) == 0) continue;
                String app = new String(tampon, java.nio.charset.StandardCharsets.UTF_8).replace("\0", "").trim();
                if (!"Habbo".equals(app)) continue;
                if (nombre(d, kCouche) != 0) continue;
                com.sun.jna.Pointer c = cf.CFDictionaryGetValue(d, kCadre);
                double[] f = {nombre(d, kNumero), nombre(c, kX), nombre(c, kY), nombre(c, kL), nombre(c, kH)};
                if (Double.isNaN(f[0]) || !(f[3] >= 300) || !(f[4] >= 200)) continue;
                if (meilleure == null || f[3] * f[4] > meilleure[3] * meilleure[4]) meilleure = f;
            }
            return meilleure;
        } finally {
            try { cf.CFRelease(liste); } catch (Throwable ignored) { }
        }
    }

    /** Une capture de la fenetre, barre de titre retiree, et l'echelle pixels/points. */
    public static final class Photo {
        public final BufferedImage image; public final double echelle;
        Photo(BufferedImage i, double e) { image = i; echelle = e; }
    }

    /**
     * Capture la fenetre Habbo SEULE (CGWindowListCreateImage), meme recouverte
     * ou en partie hors ecran, en resolution nominale (1 pixel par point : une
     * fenetre de 3000×2000 reste a 24 Mo). Retire la barre de titre (titrePt).
     */
    public static Ou<Photo> capturerFenetreHabbo(int titrePt) {
        if (WINDOWS) return CaptureWindows.capturer();      // zone client : pas de barre a retirer
        try {
            double[] f = fenetreHabboCg();
            if (f == null) return new Ou<>(null, "Fenêtre Habbo introuvable : l'appli doit être ouverte, pas réduite.");
            CGRect.ByValue nul = new CGRect.ByValue();
            nul.x = Double.POSITIVE_INFINITY; nul.y = Double.POSITIVE_INFINITY; nul.l = 0; nul.h = 0;   // CGRectNull
            com.sun.jna.Pointer img = cg.CGWindowListCreateImage(nul, 8, (int) f[0], 1 | 16);
            if (img == null) return new Ou<>(null, AUTORISATION_ECRAN);
            BufferedImage b;
            try { b = versImage(img); } finally { cg.CGImageRelease(img); }
            if (b == null || sansContenu(b)) return new Ou<>(null, AUTORISATION_ECRAN);
            double echelle = b.getWidth() / Math.max(1.0, f[3]);
            int haut = (int) Math.round(Math.max(0, titrePt) * echelle);
            if (haut > 0 && haut < b.getHeight() - 10) b = b.getSubimage(0, haut, b.getWidth(), b.getHeight() - haut);
            return new Ou<>(new Photo(b, echelle), null);
        } catch (UnsatisfiedLinkError e) {
            return new Ou<>(null, "Cette version de macOS ne fournit plus la capture de fenêtre "
                    + "(CGWindowListCreateImage). Utilise :screenshot.");
        } catch (Throwable t) {
            return new Ou<>(null, "Capture de la fenêtre impossible : " + t);
        }
    }

    public static final String AUTORISATION_ECRAN = WINDOWS
            ? "Image vide : Windows n'a pas pu photographier la fenêtre Habbo. Laisse-la ouverte (pas réduite) "
              + "et visible à l'écran, puis réessaie, ou reviens à :screenshot."
            : "Image vide : macOS refuse la capture. Réglages Système › Confidentialité et sécurité › "
              + "Enregistrement de l'écran (et audio système) : active Terminal (ou java), puis relance l'Atelier.";

    /** Demande l'autorisation d'enregistrement de l'ecran si elle manque. true si accordee. */
    public static boolean autorisationEcran() {
        if (WINDOWS) return true;                            // aucune autorisation sous Windows
        try {
            chargerCg();
            if (cg.CGPreflightScreenCaptureAccess() != 0) return true;
            cg.CGRequestScreenCaptureAccess();
            return false;
        } catch (Throwable t) { return true; }   // inconnu : on essaie quand meme
    }

    /** CGImage -> BufferedImage ARGB, par un contexte bitmap BGRA (premultiplie). */
    private static BufferedImage versImage(com.sun.jna.Pointer img) {
        int w = (int) cg.CGImageGetWidth(img), h = (int) cg.CGImageGetHeight(img);
        if (w <= 0 || h <= 0) return null;
        com.sun.jna.Memory m = new com.sun.jna.Memory((long) w * h * 4);
        m.clear();
        com.sun.jna.Pointer cs = cg.CGColorSpaceCreateDeviceRGB();
        // kCGImageAlphaPremultipliedFirst (2) | kCGBitmapByteOrder32Little (2 << 12) : un int = ARGB
        com.sun.jna.Pointer ctx = cg.CGBitmapContextCreate(m, w, h, 8, (long) w * 4, cs, 2 | (2 << 12));
        try {
            if (ctx == null) return null;
            CGRect.ByValue r = new CGRect.ByValue();
            r.x = 0; r.y = 0; r.l = w; r.h = h;
            cg.CGContextDrawImage(ctx, r, img);
        } finally {
            if (ctx != null) cg.CGContextRelease(ctx);
            if (cs != null) cg.CGColorSpaceRelease(cs);
        }
        int[] px = m.getIntArray(0, w * h);
        for (int i = 0; i < px.length; i++) {                 // depremultiplier
            int p = px[i], a = p >>> 24;
            if (a == 255 || a == 0) continue;
            int r = Math.min(255, ((p >> 16) & 0xFF) * 255 / a);
            int g = Math.min(255, ((p >> 8) & 0xFF) * 255 / a);
            int b = Math.min(255, (p & 0xFF) * 255 / a);
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, w, h, px, 0, w);
        return b;
    }

    /**
     * Sans l'autorisation, macOS rend une image transparente, noire ou d'une
     * seule couleur. Echantillonne : plus de 99,5 % d'une meme couleur = vide.
     */
    static boolean sansContenu(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        HashMap<Integer, Integer> c = new HashMap<>();
        int n = 0, transparents = 0;
        int pas = Math.max(1, (int) Math.sqrt((double) w * h / 40000));
        for (int y = 0; y < h; y += pas)
            for (int x = 0; x < w; x += pas) {
                int p = b.getRGB(x, y);
                n++;
                if ((p >>> 24) < 16) { transparents++; continue; }
                c.merge(p, 1, Integer::sum);
            }
        if (n == 0 || transparents > n * 0.9) return true;
        int max = 0;
        for (int v : c.values()) max = Math.max(max, v);
        return max > n * 0.995;
    }

    /** Lit une image et la rend en ARGB. */
    public static BufferedImage lire(File f) throws IOException {
        BufferedImage b = ImageIO.read(f);
        if (b == null) throw new IOException("format d'image non reconnu");
        if (b.getType() == BufferedImage.TYPE_INT_ARGB) return b;
        BufferedImage a = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] px = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
        a.setRGB(0, 0, b.getWidth(), b.getHeight(), px, 0, b.getWidth());
        return a;
    }
}

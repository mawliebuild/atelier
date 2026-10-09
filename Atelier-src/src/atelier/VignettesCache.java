package atelier;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vignettes de la Galerie : de petites images (LARGEUR x HAUTEUR au plus)
 * gardees sur le disque, dans le dossier de donnees de l'Atelier (jamais dans
 * la Galerie elle-meme), et les dernieres affichees gardees en memoire (LRU).
 *
 * Cle d'une vignette : chemin + taille + date de modification de la photo
 * (une photo modifiee a donc une nouvelle vignette ; l'ancienne devient
 * orpheline et part au prochain menage). Fabrication en arriere-plan sur
 * quelques fils de basse priorite, jamais sur le fil JavaFX ; les gros JPEG
 * sont lus sous-echantillonnes (peu de memoire).
 *
 * Toutes les methodes non statiques s'appellent depuis le fil JavaFX.
 */
final class VignettesCache {

    /** Boite de la vignette : nette en Retina pour des cartes d'environ 170 px de large. */
    static final int LARGEUR = 320, HAUTEUR = 240;
    /** Vignettes gardees en memoire (environ 300 Ko chacune). */
    private static final int MEMOIRE = 300;
    /** Change si le format des vignettes change : les anciennes deviennent orphelines. */
    private static final String VERSION = "v1-" + LARGEUR + "x" + HAUTEUR;
    private static final String EXT = ".vgn";
    /** Propriete de l'ImageView : la demande en attente pour elle. */
    private static final String DEMANDE = "vignettes.demande";

    private final LinkedHashMap<String, Image> memoire = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) { return size() > MEMOIRE; }
    };
    private final Map<String, Demande> enCours = new HashMap<>();
    /** Grandes images (mode « Grandes cartes ») : quelques ecrans en memoire, bornees en octets. */
    private static final long MEMOIRE_GRANDES = 96L << 20;
    /** Les grandes images se lisent par paliers de taille (en pixels reels). */
    private static final int PALIER = 256;
    private static final String DEMANDE_GRANDE = "vignettes.demandeGrande";
    /** Propriete de l'ImageView : elle montre une grande image. */
    private static final String GRANDE = "vignettes.grande";
    private final LinkedHashMap<String, Image> grandes = new LinkedHashMap<>(16, 0.75f, true);
    private long octetsGrandes = 0;
    private final Map<String, Demande> grandesEnCours = new HashMap<>();
    /** Photos illisibles : pas de nouvel essai tant qu'elles ne changent pas. */
    private final Set<String> illisibles = new HashSet<>();
    private final ThreadPoolExecutor fils;
    private File dossier;
    private boolean menageFait = false;

    private static final class Demande {
        final File photo;
        final String cle;
        final List<ImageView> vues = new ArrayList<>();
        volatile boolean annulee;
        Demande(File photo, String cle) { this.photo = photo; this.cle = cle; }
    }

    VignettesCache() {
        AtomicInteger n = new AtomicInteger();
        int nb = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
        fils = new ThreadPoolExecutor(nb, nb, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Atelier vignettes " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        fils.allowCoreThreadTimeOut(true);
        ImageIO.setUseCache(false);
    }

    /** Le dossier des vignettes : dans les donnees de l'Atelier (Application Support, %APPDATA%). */
    private synchronized File dossier() {
        if (dossier == null) {
            File d = new File(Dossiers.donneesAtelier(), "Vignettes");
            if (!d.isDirectory() && d.mkdirs()) { Capture.rendre(d.getParentFile()); Capture.rendre(d); }
            dossier = d;
        }
        return dossier;
    }

    /** Cle de la photo (null si elle n'existe plus). */
    static String cle(File photo) {
        long t = photo.length(), m = photo.lastModified();
        if (m == 0 && t == 0) return null;
        return empreinte(VERSION + "|" + photo.getAbsolutePath() + "|" + t + "|" + m);
    }

    private static String empreinte(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * Met la vignette de la photo dans iv : tout de suite si elle est en
     * memoire, sinon plus tard (lue sur le disque ou fabriquee en arriere-plan).
     */
    void charger(File photo, ImageView iv) {
        annuler(iv);
        iv.getProperties().remove(GRANDE);
        String cle = photo == null ? null : cle(photo);
        if (cle == null || illisibles.contains(cle)) { iv.setImage(null); return; }
        Image m = memoire.get(cle);
        if (m != null) { iv.setImage(m); return; }
        iv.setImage(null);
        Demande d = enCours.get(cle);
        if (d == null) {
            d = new Demande(photo, cle);
            enCours.put(cle, d);
            Demande dd = d;
            fils.execute(() -> travailler(dd));
        }
        d.annulee = false;
        d.vues.add(iv);
        iv.getProperties().put(DEMANDE, d);
    }

    /** iv ne veut plus de vignette (carte loin de l'ecran) : sa demande est abandonnee si personne d'autre ne l'attend. */
    void annuler(ImageView iv) {
        Object o = iv.getProperties().remove(DEMANDE);
        if (o instanceof Demande) {
            Demande d = (Demande) o;
            d.vues.remove(iv);
            if (d.vues.isEmpty()) d.annulee = true;
        }
    }

    /** Relache l'image de iv (et ses demandes, petite et grande). */
    void relacher(ImageView iv) {
        annuler(iv);
        annulerGrande(iv);
        iv.getProperties().remove(GRANDE);
        iv.setImage(null);
    }

    private void travailler(Demande d) {
        if (d.annulee) {
            Platform.runLater(() -> {
                if (enCours.get(d.cle) != d) return;
                if (d.vues.isEmpty()) enCours.remove(d.cle);
                else { d.annulee = false; fils.execute(() -> travailler(d)); }   // redemandee entre-temps
            });
            return;
        }
        Image img = null;
        try { img = obtenir(d.photo, d.cle); }
        catch (Throwable t) { Journal.debug("Vignette impossible pour " + d.photo.getName() + " : " + t); }
        Image fin = img;
        Platform.runLater(() -> {
            enCours.remove(d.cle, d);
            if (fin == null) illisibles.add(d.cle); else memoire.put(d.cle, fin);
            for (ImageView iv : d.vues) {
                if (iv.getProperties().get(DEMANDE) != d) continue;
                iv.getProperties().remove(DEMANDE);
                // une grande image deja la (mode « Grandes cartes ») : la vignette arrivee en retard ne la remplace pas
                if (!Boolean.TRUE.equals(iv.getProperties().get(GRANDE))) iv.setImage(fin);
            }
            d.vues.clear();
        });
    }

    /** La vignette depuis le disque, sinon fabriquee et enregistree (fil d'arriere-plan). */
    private Image obtenir(File photo, String cle) throws Exception {
        File c = new File(dossier(), cle + EXT);
        if (c.isFile()) {
            Image im = new Image(c.toURI().toString());
            if (!im.isError() && im.getWidth() > 0) return im;
            Files.deleteIfExists(c.toPath());
        }
        byte[] b = fabriquer(photo);
        if (b == null) return null;
        File tmp = new File(dossier(), cle + "." + Thread.currentThread().getId() + ".tmp");
        try {
            Files.write(tmp.toPath(), b);
            try {
                Files.move(tmp.toPath(), c.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), c.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Capture.rendre(c);
        } catch (Throwable t) {
            Files.deleteIfExists(tmp.toPath());
            Journal.debug("Vignette non enregistrée (" + photo.getName() + ") : " + t);
        }
        Image im = new Image(new ByteArrayInputStream(b));
        return im.isError() ? null : im;
    }

    // ------------------------------------------------------------ grandes images

    /**
     * Mode « Grandes cartes » : la photo lue a la taille de la carte (pixels
     * reels de l'ecran), en arriere-plan, pour rester nette sur toute la
     * largeur. Pas de fichier sur le disque : seulement les dernieres en
     * memoire, bornees en octets. La vignette reste affichee en attendant.
     */
    void chargerGrande(File photo, ImageView iv, int largeurPx, int hauteurPx) {
        annulerGrande(iv);
        String c = photo == null ? null : cle(photo);
        if (c == null || illisibles.contains(c)) return;
        int lb = palier(largeurPx), hb = palier(hauteurPx);
        String k = c + "|" + lb + "x" + hb;
        Image m = grandes.get(k);
        if (m != null) { montrerGrande(iv, m); return; }
        Demande d = grandesEnCours.get(k);
        if (d == null) {
            d = new Demande(photo, k);
            grandesEnCours.put(k, d);
            Demande dd = d;
            fils.execute(() -> travaillerGrande(dd, lb, hb));
        }
        d.annulee = false;
        d.vues.add(iv);
        iv.getProperties().put(DEMANDE_GRANDE, d);
    }

    /** iv ne veut plus de grande image : sa demande est abandonnee si personne d'autre ne l'attend. */
    void annulerGrande(ImageView iv) {
        Object o = iv.getProperties().remove(DEMANDE_GRANDE);
        if (o instanceof Demande) {
            Demande d = (Demande) o;
            d.vues.remove(iv);
            if (d.vues.isEmpty()) d.annulee = true;
        }
    }

    /** Taille arrondie au palier superieur : un redimensionnement de quelques pixels ne relit pas la photo. */
    private static int palier(int px) { return Math.max(PALIER, (px + PALIER - 1) / PALIER * PALIER); }

    private static void montrerGrande(ImageView iv, Image img) {
        iv.getProperties().put(GRANDE, Boolean.TRUE);
        iv.setImage(img);
    }

    private void travaillerGrande(Demande d, int l, int h) {
        if (d.annulee) {
            Platform.runLater(() -> {
                if (grandesEnCours.get(d.cle) != d) return;
                if (d.vues.isEmpty()) grandesEnCours.remove(d.cle);
                else { d.annulee = false; fils.execute(() -> travaillerGrande(d, l, h)); }
            });
            return;
        }
        Image img = null;
        try { img = lireGrande(d.photo, l, h); }
        catch (Throwable t) { Journal.debug("Grande image impossible pour " + d.photo.getName() + " : " + t); }
        Image fin = img;
        Platform.runLater(() -> {
            grandesEnCours.remove(d.cle, d);
            if (fin != null) garderGrande(d.cle, fin);
            for (ImageView iv : d.vues) {
                if (iv.getProperties().get(DEMANDE_GRANDE) != d) continue;
                iv.getProperties().remove(DEMANDE_GRANDE);
                if (fin != null) montrerGrande(iv, fin);
            }
            d.vues.clear();
        });
    }

    /** En memoire, les plus anciennes partent au-dela de MEMOIRE_GRANDES octets. */
    private void garderGrande(String k, Image img) {
        Image avant = grandes.put(k, img);
        if (avant != null) octetsGrandes -= octets(avant);
        octetsGrandes += octets(img);
        Iterator<Map.Entry<String, Image>> it = grandes.entrySet().iterator();
        while (octetsGrandes > MEMOIRE_GRANDES && grandes.size() > 1 && it.hasNext()) {
            Map.Entry<String, Image> e = it.next();
            if (e.getKey().equals(k)) continue;
            octetsGrandes -= octets(e.getValue());
            it.remove();
        }
    }

    private static long octets(Image i) { return (long) (i.getWidth() * i.getHeight() * 4); }

    /** La photo dans la boite l x h (ratio garde), jamais agrandie. Hors du fil JavaFX. */
    private static Image lireGrande(File photo, int l, int h) {
        int[] dim = dimensions(photo);
        String uri = photo.toURI().toString();
        Image im = dim != null && dim[0] <= l && dim[1] <= h ? new Image(uri) : new Image(uri, l, h, true, true, false);
        return im.isError() || im.getWidth() <= 0 ? null : im;
    }

    /** Largeur et hauteur de la photo sans la decoder (null si inconnues). */
    private static int[] dimensions(File f) {
        try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
            if (in == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try { r.setInput(in, true, true); return new int[]{r.getWidth(0), r.getHeight(0)}; }
            finally { r.dispose(); }
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ fabrication

    /**
     * Vignette encodee de la photo (JPEG si opaque, PNG sinon), ou null si
     * illisible. JavaFX decode directement a la taille de la vignette (ligne
     * par ligne : un JPEG de 4000 px ne prend jamais toute sa place en
     * memoire), 6 fois plus vite qu'ImageIO pour les JPEG ; ImageIO
     * sous-echantillonne en secours. Ne touche jamais a la photo.
     */
    static byte[] fabriquer(File photo) throws Exception {
        BufferedImage src = lireParJavaFx(photo);
        if (src == null) src = lireReduite(photo);
        if (src == null) return null;
        double k = Math.min(1.0, Math.min((double) LARGEUR / src.getWidth(), (double) HAUTEUR / src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage dst = reduire(src, w, h, alpha);
        if (alpha && opaque(dst)) { dst = enRgb(dst); alpha = false; }
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
        if (alpha) ImageIO.write(dst, "png", out);
        else ecrireJpeg(dst, out);
        return out.toByteArray();
    }

    /** Secours : lecture ImageIO avec sous-echantillonnage (garde au moins 2x la taille voulue). */
    private static BufferedImage lireReduite(File photo) {
        try (ImageInputStream in = ImageIO.createImageInputStream(photo)) {
            if (in == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                int pas = Math.max(1, Math.min(w / (LARGEUR * 2), h / (HAUTEUR * 2)));
                ImageReadParam p = r.getDefaultReadParam();
                if (pas > 1) p.setSourceSubsampling(pas, pas, 0, 0);
                return r.read(0, p);
            } finally { r.dispose(); }
        } catch (Throwable t) {
            return null;
        }
    }

    /** JavaFX lit l'image directement a la taille de la vignette. */
    private static BufferedImage lireParJavaFx(File photo) {
        try {
            Image im = new Image(photo.toURI().toString(), LARGEUR, HAUTEUR, true, true, false);
            if (im.isError() || im.getWidth() <= 0) return null;
            int w = (int) im.getWidth(), h = (int) im.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] ligne = new int[w];
            javafx.scene.image.PixelReader pr = im.getPixelReader();
            for (int y = 0; y < h; y++) {
                pr.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), ligne, 0, w);
                bi.setRGB(0, y, w, 1, ligne, 0, w);
            }
            return bi;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reduction par moities successives puis bilineaire : net et sans crenelage. */
    private static BufferedImage reduire(BufferedImage src, int w, int h, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        do {
            int nw = Math.max(w, cw / 2), nh = Math.max(h, ch / 2);
            if (cw <= w * 2 && ch <= h * 2) { nw = w; nh = h; }
            BufferedImage n = new BufferedImage(nw, nh, type);
            Graphics2D g = n.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, nw, nh); }
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = n;
            cw = nw;
            ch = nh;
        } while (cw != w || ch != h);
        return cur;
    }

    private static boolean opaque(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] l = new int[w];
        for (int y = 0; y < h; y++) {
            b.getRGB(0, y, w, 1, l, 0, w);
            for (int p : l) if ((p >>> 24) != 0xFF) return false;
        }
        return true;
    }

    private static BufferedImage enRgb(BufferedImage b) {
        BufferedImage n = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = n.createGraphics();
        g.drawImage(b, 0, 0, null);
        g.dispose();
        return n;
    }

    private static void ecrireJpeg(BufferedImage b, ByteArrayOutputStream out) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream o = ImageIO.createImageOutputStream(out)) {
            w.setOutput(o);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.86f);
            w.write(null, new IIOImage(b, null, null), p);
        } finally { w.dispose(); }
    }

    // ------------------------------------------------------------ menage

    /**
     * Une fois par session, en arriere-plan : efface les vignettes qui ne
     * correspondent plus a aucune photo (photo supprimee, deplacee ou
     * modifiee). « photos » est appele hors du fil JavaFX.
     */
    void menage(java.util.function.Supplier<Collection<File>> photos) {
        if (menageFait) return;
        menageFait = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(20_000);
                Set<String> gardees = new HashSet<>();
                for (File f : photos.get()) { String c = cle(f); if (c != null) gardees.add(c); }
                if (gardees.isEmpty()) return;     // galerie introuvable (disque absent ?) : on ne touche a rien
                File[] l = dossier().listFiles();
                if (l == null) return;
                long avant = System.currentTimeMillis() - 10 * 60_000L;   // jamais celles en train d'etre faites
                int n = 0;
                for (File f : l) {
                    String nom = f.getName();
                    String c = nom.endsWith(EXT) ? nom.substring(0, nom.length() - EXT.length()) : null;
                    boolean orpheline = c != null ? !gardees.contains(c) : nom.endsWith(".tmp");
                    if (orpheline && f.isFile() && f.lastModified() < avant && f.delete()) n++;
                }
                if (n > 0) Journal.debug(Ui.accorder("Galerie : " + n + " vignette(s) orpheline(s) effacée(s)."));
            } catch (Throwable e) {
                Journal.debug("Ménage des vignettes interrompu : " + e);
            }
        }, "Atelier vignettes ménage");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}

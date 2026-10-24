package atelier;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.Inflater;

/**
 * Les etats d'un type de mobi, lus dans son fichier officiel :
 *     https://images.habbo.com/dcr/hof_furni/{revision}/{classe}.swf
 *
 * Le fichier contient le XML de visualisation (couches, couleurs, et une
 * animation par etat) et les images de chaque couche. Nombre d'etats : la plus
 * grande animation entre 0 et 99 (sans transition), plus un ; au-dela de 99, ce
 * sont des transitions (porte qui s'ouvre, de qui roule). On sait aussi
 * dessiner le mobi dans un etat : la premiere image de chaque couche, en 64.
 *
 * Fichiers gardes sur le disque (donnees de l'Atelier, dossier « Mobis ») :
 * une seule lecture reseau par revision. Telechargement et lecture sur un fil
 * de fond, jamais sur le fil JavaFX ; les reponses reviennent sur le fil FX.
 */
final class EtatsMobis {

    private EtatsMobis() { }

    /** Boite de l'image d'un etat (un peu plus grande qu'une icone). */
    static final double TAILLE = 36;
    /** Taille des images dessinees (celle du jeu, zoom normal). */
    private static final String TAILLE_JEU = "64";

    /** Ce qu'on sait d'un type de mobi. etats == 0 : inconnu (fichier introuvable ou illisible). */
    static final class Mobi {
        final int etats;
        private final Modele modele;
        /** Images deja dessinees (fil FX). */
        private final Map<String, Image> images = new HashMap<>();
        Mobi(int etats, Modele modele) { this.etats = etats; this.modele = modele; }
        boolean dessinable() { return modele != null; }
    }

    private static final Map<String, Mobi> connus = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Mobi> e) { return size() > 80; }
    };
    private static final Map<String, List<Consumer<Mobi>>> attente = new HashMap<>();
    private static final ExecutorService fil = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Atelier etats des mobis");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    /** Nom technique sans la couleur (« lampe*3 » -> « lampe »). */
    static String base(String classe) {
        return classe == null ? null : (classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe);
    }

    /** Couleur d'une variante (« lampe*3 » -> 3), 0 sans couleur. */
    static int couleur(String classe) {
        if (classe == null || !classe.contains("*")) return 0;
        try { return Integer.parseInt(classe.substring(classe.indexOf('*') + 1).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String cle(String classe, int revision) { return base(classe) + "@" + revision; }

    /** Deja connu (en memoire), sinon null. Fil FX. */
    static Mobi connu(String classe, int revision) {
        if (classe == null || revision <= 0) return null;
        return connus.get(cle(classe, revision));
    }

    /**
     * Charge le type en fond (disque, sinon reseau), puis appelle fini sur le
     * fil FX. Une seule lecture par type, meme demande plusieurs fois. Fil FX.
     */
    static void demander(String classe, int revision, Consumer<Mobi> fini) {
        if (classe == null || classe.isBlank() || revision <= 0) { fini.accept(new Mobi(0, null)); return; }
        String c = cle(classe, revision);
        Mobi m = connus.get(c);
        if (m != null) { fini.accept(m); return; }
        List<Consumer<Mobi>> l = attente.get(c);
        if (l != null) { l.add(fini); return; }
        l = new ArrayList<>();
        l.add(fini);
        attente.put(c, l);
        String b = base(classe);
        fil.execute(() -> {
            Mobi lu;
            try { lu = lire(b, revision); }
            catch (Throwable t) {
                Journal.debug("Etats de " + b + " : illisibles (" + t + ")");
                lu = new Mobi(0, null);
            }
            Mobi r = lu;
            Platform.runLater(() -> {
                connus.put(c, r);
                List<Consumer<Mobi>> ll = attente.remove(c);
                if (ll != null) for (Consumer<Mobi> f : ll) f.accept(r);
            });
        });
    }

    /** L'image deja dessinee pour cet etat, sinon null. Fil FX. */
    static Image dessinee(Mobi m, String classe, int etat, int rotation) {
        return m == null ? null : m.images.get(etat + "|" + rotation + "|" + couleur(classe));
    }

    /**
     * Dessine le mobi dans cet etat (en fond), puis fini sur le fil FX avec
     * l'image, ou null si on ne sait pas la dessiner. Fil FX.
     */
    static void dessiner(Mobi m, String classe, int etat, int rotation, Consumer<Image> fini) {
        if (m == null || m.modele == null) { fini.accept(null); return; }
        String k = etat + "|" + rotation + "|" + couleur(classe);
        Image deja = m.images.get(k);
        if (deja != null) { fini.accept(deja); return; }
        int coul = couleur(classe);
        fil.execute(() -> {
            Rendu r;
            try { r = m.modele.dessiner(etat, rotation, coul); }
            catch (Throwable t) { Journal.debug("Etat " + etat + " de " + m.modele.classe + " : dessin impossible (" + t + ")"); r = null; }
            Rendu rr = r;
            Platform.runLater(() -> {
                Image i = null;
                if (rr != null) {
                    WritableImage w = new WritableImage(rr.l, rr.h);
                    w.getPixelWriter().setPixels(0, 0, rr.l, rr.h, PixelFormat.getIntArgbInstance(), rr.argb, 0, rr.l);
                    i = w;
                    m.images.put(k, i);
                }
                fini.accept(i);
            });
        });
    }

    // ------------------------------------------------------------- lecture

    private static File dossier() {
        File d = new File(Dossiers.donneesAtelier(), "Mobis");
        if (!d.isDirectory() && d.mkdirs()) { Capture.rendre(d.getParentFile()); Capture.rendre(d); }
        return d;
    }

    /** Fil de fond. */
    private static Mobi lire(String classe, int revision) throws Exception {
        String nom = classe.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + revision + ".swf";
        File f = new File(dossier(), nom);
        byte[] swf = null;
        if (f.isFile() && f.length() > 0) swf = Files.readAllBytes(f.toPath());
        if (swf == null) {
            swf = telecharger("https://images.habbo.com/dcr/hof_furni/" + revision + "/" + classe + ".swf");
            if (swf == null) { Journal.debug("Etats de " + classe + " : fichier introuvable"); return new Mobi(0, null); }
            try {
                File tmp = new File(f.getParentFile(), nom + ".tmp");
                Files.write(tmp.toPath(), swf);
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Capture.rendre(f);
            } catch (Exception e) { Journal.debug("Etats de " + classe + " : pas de copie sur le disque (" + e + ")"); }
        }
        Modele m = Modele.depuis(classe, swf);
        if (m == null) return new Mobi(0, null);
        Journal.debug("Etats de " + classe + " : " + m.etats);
        return new Mobi(m.etats, m);
    }

    private static byte[] telecharger(String adresse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", PrixReseau.UA);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
        } finally { c.disconnect(); }
    }

    // ------------------------------------------------------------- modele

    private static final class Rendu {
        final int l, h;
        final int[] argb;
        Rendu(int l, int h, int[] argb) { this.l = l; this.h = h; this.argb = argb; }
    }

    /** Une image du fichier : pixels ARGB non premultiplies. */
    private static final class Bitmap {
        final int l, h;
        final int[] argb;
        Bitmap(int l, int h, int[] argb) { this.l = l; this.h = h; this.argb = argb; }
    }

    private static final class Asset {
        int x, y;
        boolean miroir;
        String source;
    }

    private static final class Couche {
        int z = 0, alpha = 255;
        boolean ajout = false;
    }

    /** Le mobi tel que le fichier le decrit (taille 64). Lecture seule apres construction. */
    private static final class Modele {
        final String classe;
        int etats = 1;
        int couches = 0;
        final List<Integer> directions = new ArrayList<>();
        final Map<Integer, Couche> parCouche = new HashMap<>();
        /** direction -> couche -> z propre a cette direction. */
        final Map<Integer, Map<Integer, Integer>> zDirection = new HashMap<>();
        /** couleur -> couche -> RGB. */
        final Map<Integer, Map<Integer, Integer>> couleurs = new HashMap<>();
        /** etat (animation) -> couche -> image (frame). */
        final Map<Integer, Map<Integer, Integer>> images = new HashMap<>();
        final Map<String, Asset> assets = new HashMap<>();
        /** nom de symbole -> image. */
        final Map<String, Bitmap> bitmaps = new HashMap<>();

        Modele(String classe) { this.classe = classe; }

        static Modele depuis(String classe, byte[] swf) throws Exception {
            byte[] b = deplier(swf);
            if (b == null) return null;
            Map<Integer, byte[]> binaires = new HashMap<>();
            Map<Integer, Bitmap> parId = new HashMap<>();
            Map<Integer, List<String>> noms = new HashMap<>();
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            int bits = (b[0] & 0xFF) >> 3;
            int p = (5 + 4 * bits + 7) / 8 + 4;       // rectangle, cadence, nombre d'images
            while (p + 2 <= b.length) {
                int entete = bb.getShort(p) & 0xFFFF;
                p += 2;
                int type = entete >> 6, l = entete & 0x3F;
                if (l == 0x3F) { l = bb.getInt(p); p += 4; }
                if (l < 0 || p + l > b.length) break;
                if (type == 0) break;
                if (type == 87 && l > 6) {
                    binaires.put(bb.getShort(p) & 0xFFFF, Arrays.copyOfRange(b, p + 6, p + l));
                } else if ((type == 36 || type == 20) && l > 7) {
                    Bitmap bm = bitmap(b, p, l, type == 36);
                    if (bm != null) parId.put(bb.getShort(p) & 0xFFFF, bm);
                } else if (type == 76) {
                    int n = bb.getShort(p) & 0xFFFF, q = p + 2;
                    for (int i = 0; i < n && q + 2 < p + l; i++) {
                        int id = bb.getShort(q) & 0xFFFF;
                        q += 2;
                        int fin = q;
                        while (fin < p + l && b[fin] != 0) fin++;
                        noms.computeIfAbsent(id, k -> new ArrayList<>()).add(new String(b, q, fin - q, java.nio.charset.StandardCharsets.ISO_8859_1));
                        q = fin + 1;
                    }
                }
                p += l;
            }
            Modele m = new Modele(classe);
            Document vis = null, ass = null;
            for (Map.Entry<Integer, byte[]> e : binaires.entrySet()) {
                String debut = new String(e.getValue(), 0, Math.min(300, e.getValue().length), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (vis == null && debut.contains("<visualizationData")) vis = xml(e.getValue());
                else if (ass == null && debut.contains("<assets") && !debut.contains("<manifest")) {
                    Document d = xml(e.getValue());          // le manifeste a aussi des <assets>, sans position
                    if ("assets".equals(d.getDocumentElement().getNodeName())) ass = d;
                }
            }
            if (vis == null) return null;
            for (Map.Entry<Integer, List<String>> e : noms.entrySet()) {
                Bitmap bm = parId.get(e.getKey());
                if (bm != null) for (String n : e.getValue()) m.bitmaps.put(n, bm);
            }
            if (ass != null) {
                NodeList l = ass.getElementsByTagName("asset");
                for (int i = 0; i < l.getLength(); i++) {
                    Element a = (Element) l.item(i);
                    Asset x = new Asset();
                    x.x = entier(a, "x", 0);
                    x.y = entier(a, "y", 0);
                    x.miroir = "1".equals(a.getAttribute("flipH"));
                    x.source = a.getAttribute("source").isEmpty() ? null : a.getAttribute("source");
                    m.assets.put(a.getAttribute("name"), x);
                }
            }
            m.lireVisualisation(vis);
            return m;
        }

        private void lireVisualisation(Document vis) {
            NodeList l = vis.getElementsByTagName("visualization");
            Element v = null;
            for (int i = 0; i < l.getLength(); i++) {
                Element e = (Element) l.item(i);
                if (TAILLE_JEU.equals(e.getAttribute("size"))) { v = e; break; }
                if (v == null) v = e;
            }
            if (v == null) return;
            couches = entier(v, "layerCount", 0);
            for (Element e : enfants(enfant(v, "layers"), "layer")) {
                Couche c = new Couche();
                c.z = entier(e, "z", 0);
                c.alpha = entier(e, "alpha", 255);
                c.ajout = "ADD".equalsIgnoreCase(e.getAttribute("ink"));
                parCouche.put(entier(e, "id", -1), c);
            }
            for (Element d : enfants(enfant(v, "directions"), "direction")) {
                int id = entier(d, "id", 0);
                directions.add(id);
                for (Element e : enfants(d, "layer"))
                    if (e.hasAttribute("z")) zDirection.computeIfAbsent(id, k -> new HashMap<>()).put(entier(e, "id", -1), entier(e, "z", 0));
            }
            for (Element c : enfants(enfant(v, "colors"), "color")) {
                Map<Integer, Integer> cl = new HashMap<>();
                for (Element e : enfants(c, "colorLayer")) {
                    try { cl.put(entier(e, "id", -1), Integer.parseInt(e.getAttribute("color").trim(), 16)); }
                    catch (NumberFormatException ignored) { }
                }
                couleurs.put(entier(c, "id", -1), cl);
            }
            int max = 0;
            for (Element a : enfants(enfant(v, "animations"), "animation")) {
                if (a.hasAttribute("transitionTo") || a.hasAttribute("transitionFrom")) continue;
                int id = entier(a, "id", -1);
                if (id < 0 || id > 99) continue;              // au-dela : transitions, roulement du de...
                max = Math.max(max, id);
                Map<Integer, Integer> parC = new HashMap<>();
                for (Element al : enfants(a, "animationLayer")) {
                    Element seq = enfant(al, "frameSequence");
                    List<Element> fr = enfants(seq, "frame");
                    if (!fr.isEmpty()) parC.put(entier(al, "id", -1), entier(fr.get(0), "id", 0));
                }
                images.put(id, parC);
            }
            etats = max + 1;
        }

        /** Fil de fond. Null si rien a dessiner. */
        Rendu dessiner(int etat, int rotation, int couleur) {
            int dir = directions.isEmpty() ? 0 : directions.get(0);
            if (directions.contains(rotation)) dir = rotation;
            Map<Integer, Integer> frames = images.getOrDefault(etat, Collections.emptyMap());
            Map<Integer, Integer> zDir = zDirection.getOrDefault(dir, Collections.emptyMap());
            Map<Integer, Integer> teintes = couleurs.getOrDefault(couleur, Collections.emptyMap());
            final class Morceau { Bitmap bm; int gauche, haut, z, ordre, alpha, teinte = -1; boolean miroir, ajout; }
            List<Morceau> ms = new ArrayList<>();
            for (int i = 0; i < couches && i < 26; i++) {
                String n = classe + "_" + TAILLE_JEU + "_" + (char) ('a' + i) + "_" + dir + "_" + frames.getOrDefault(i, 0);
                Asset a = assets.get(n);
                if (a == null) continue;
                Bitmap bm = bitmaps.get(classe + "_" + (a.source != null ? a.source : n));
                if (bm == null) continue;
                Couche c = parCouche.getOrDefault(i, new Couche());
                Morceau m = new Morceau();
                m.bm = bm;
                m.miroir = a.miroir;
                m.gauche = a.miroir ? a.x - bm.l : -a.x;
                m.haut = -a.y;
                m.z = zDir.getOrDefault(i, c.z);
                m.ordre = i;
                m.alpha = c.alpha;
                m.ajout = c.ajout;
                if (teintes.containsKey(i)) m.teinte = teintes.get(i);
                ms.add(m);
            }
            if (ms.isEmpty()) return null;
            ms.sort(Comparator.<Morceau>comparingInt(m -> m.z).thenComparingInt(m -> m.ordre));
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (Morceau m : ms) {
                x0 = Math.min(x0, m.gauche); y0 = Math.min(y0, m.haut);
                x1 = Math.max(x1, m.gauche + m.bm.l); y1 = Math.max(y1, m.haut + m.bm.h);
            }
            int L = x1 - x0, H = y1 - y0;
            if (L <= 0 || H <= 0 || (long) L * H > 4_000_000L) return null;
            // composition en premultiplie (0..1)
            float[] r = new float[L * H], g = new float[L * H], bl = new float[L * H], al = new float[L * H];
            for (Morceau m : ms) {
                float tr = 1, tg = 1, tb = 1;
                if (m.teinte >= 0) { tr = ((m.teinte >> 16) & 0xFF) / 255f; tg = ((m.teinte >> 8) & 0xFF) / 255f; tb = (m.teinte & 0xFF) / 255f; }
                float ka = m.alpha / 255f;
                for (int y = 0; y < m.bm.h; y++) {
                    int dy = m.haut - y0 + y;
                    for (int x = 0; x < m.bm.l; x++) {
                        int px = m.bm.argb[y * m.bm.l + (m.miroir ? m.bm.l - 1 - x : x)];
                        float sa = ((px >>> 24) & 0xFF) / 255f * ka;
                        if (sa <= 0) continue;
                        float sr = ((px >> 16) & 0xFF) / 255f * tr * sa, sg = ((px >> 8) & 0xFF) / 255f * tg * sa, sb = (px & 0xFF) / 255f * tb * sa;
                        int o = dy * L + (m.gauche - x0 + x);
                        if (m.ajout) {
                            r[o] = Math.min(1, r[o] + sr); g[o] = Math.min(1, g[o] + sg); bl[o] = Math.min(1, bl[o] + sb);
                            al[o] = Math.max(al[o], Math.max(r[o], Math.max(g[o], bl[o])));
                        } else {
                            float k = 1 - sa;
                            r[o] = sr + r[o] * k; g[o] = sg + g[o] * k; bl[o] = sb + bl[o] * k; al[o] = sa + al[o] * k;
                        }
                    }
                }
            }
            int[] out = new int[L * H];
            for (int i = 0; i < out.length; i++) {
                float a = al[i];
                if (a <= 0.002f) continue;
                int A = Math.round(Math.min(1, a) * 255);
                int R = Math.round(Math.min(1, r[i] / a) * 255), G = Math.round(Math.min(1, g[i] / a) * 255), B = Math.round(Math.min(1, bl[i] / a) * 255);
                out[i] = (A << 24) | (R << 16) | (G << 8) | B;
            }
            return new Rendu(L, H, out);
        }
    }

    // ------------------------------------------------------------- outils

    /** Le corps du SWF (apres les 8 octets d'en-tete), deplie si compresse ; null si format inconnu. */
    private static byte[] deplier(byte[] swf) throws Exception {
        if (swf == null || swf.length < 9) return null;
        String sig = new String(swf, 0, 3, java.nio.charset.StandardCharsets.ISO_8859_1);
        if ("FWS".equals(sig)) return Arrays.copyOfRange(swf, 8, swf.length);
        if (!"CWS".equals(sig)) return null;                 // ZWS (LZMA) : pas utilise par le jeu
        return deflater(swf, 8, swf.length - 8);
    }

    private static byte[] deflater(byte[] b, int debut, int l) throws Exception {
        Inflater inf = new Inflater();
        try {
            inf.setInput(b, debut, l);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, l * 3));
            byte[] t = new byte[16384];
            while (!inf.finished()) {
                int n = inf.inflate(t);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(t, 0, n);
            }
            return out.toByteArray();
        } finally { inf.end(); }
    }

    /** DefineBitsLossless (20) ou DefineBitsLossless2 (36, avec transparence). */
    private static Bitmap bitmap(byte[] b, int p, int l, boolean alpha) throws Exception {
        int format = b[p + 2] & 0xFF;
        int w = (b[p + 3] & 0xFF) | ((b[p + 4] & 0xFF) << 8), h = (b[p + 5] & 0xFF) | ((b[p + 6] & 0xFF) << 8);
        if (w <= 0 || h <= 0 || (long) w * h > 4_000_000L) return null;
        int[] px = new int[w * h];
        if (format == 5) {
            byte[] d = deflater(b, p + 7, l - 7);
            if (d.length < w * h * 4) return null;
            for (int i = 0; i < w * h; i++) {
                int a = alpha ? d[i * 4] & 0xFF : 255;
                int r = d[i * 4 + 1] & 0xFF, g = d[i * 4 + 2] & 0xFF, bl = d[i * 4 + 3] & 0xFF;
                if (alpha && a > 0 && a < 255) {         // premultiplie dans le fichier
                    r = Math.min(255, r * 255 / a); g = Math.min(255, g * 255 / a); bl = Math.min(255, bl * 255 / a);
                }
                px[i] = a == 0 ? 0 : (a << 24) | (r << 16) | (g << 8) | bl;
            }
        } else if (format == 3) {
            int n = (b[p + 7] & 0xFF) + 1, e = alpha ? 4 : 3;
            byte[] d = deflater(b, p + 8, l - 8);
            int ligne = (w + 3) & ~3;
            if (d.length < n * e + ligne * h) return null;
            int[] pal = new int[n];
            for (int i = 0; i < n; i++) {
                int r = d[i * e] & 0xFF, g = d[i * e + 1] & 0xFF, bl = d[i * e + 2] & 0xFF, a = alpha ? d[i * e + 3] & 0xFF : 255;
                if (alpha && a > 0 && a < 255) { r = Math.min(255, r * 255 / a); g = Math.min(255, g * 255 / a); bl = Math.min(255, bl * 255 / a); }
                pal[i] = a == 0 ? 0 : (a << 24) | (r << 16) | (g << 8) | bl;
            }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int k = d[n * e + y * ligne + x] & 0xFF;
                    px[y * w + x] = k < n ? pal[k] : 0;
                }
        } else return null;
        return new Bitmap(w, h, px);
    }

    private static Document xml(byte[] d) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(d));
    }

    private static Element enfant(Element e, String nom) {
        if (e == null) return null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && nom.equals(n.getNodeName())) return (Element) n;
        return null;
    }

    private static List<Element> enfants(Element e, String nom) {
        List<Element> r = new ArrayList<>();
        if (e == null) return r;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && nom.equals(n.getNodeName())) r.add((Element) n);
        return r;
    }

    private static int entier(Element e, String att, int defaut) {
        String s = e.getAttribute(att);
        if (s == null || s.isEmpty()) return defaut;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException x) { return defaut; }
    }
}

package atelier;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prix des mobis de l'hotel FR d'apres habbofurni.xyz.
 *
 * Le site range environ 9 000 mobis dans ses archives, 30 par page. Chaque
 * fiche porte le nom de classe du mobi, son prix moyen en credits et l'origine
 * de ce prix : « market » (moyenne de la place du marche) ou « site »
 * (estimation du site, pour les rares peu vendus).
 *
 * On lit toutes les pages une fois par 24 h, en tache de fond, 4 pages a la
 * fois avec une pause entre chaque, et on garde le resultat dans
 * repertoire/prix-habbofurni.json. Entre deux lectures, les prix viennent du
 * fichier : l'Atelier ne redemande rien.
 */
public final class PrixSite {

    public static final class Prix {
        public final int moyen;
        public final String source;     // "market" | "site" | autre valeur du site
        Prix(int moyen, String source) { this.moyen = moyen; this.source = source; }
    }

    private static final String ARCHIVES = "https://habbofurni.xyz/archives-des-mobis/";
    private static final long VALIDITE = 24 * 3600_000L;
    private static final File FICHIER = new File("repertoire", "prix-habbofurni.json");

    private static final Map<String, Prix> prix = new ConcurrentHashMap<>();
    private static volatile long misAJour = 0;
    private static volatile boolean demarre = false;
    private static volatile String etat = "";
    /** Vrai quand la lecture (ou la decision de ne pas relire) est terminee. */
    private static volatile boolean fini = false;
    public static boolean fini() { return fini; }
    private static volatile Runnable surMaj = () -> { };

    private PrixSite() { }

    /**
     * Le prix d'une classe de la furnidata. Le site ecrit parfois la couleur
     * par defaut (« norja_c24_bed*0 ») quand la furnidata ne l'ecrit pas.
     */
    public static Prix prix(String classe) {
        if (classe == null) return null;
        Prix p = prix.get(classe);
        if (p == null && !classe.contains("*")) p = prix.get(classe + "*0");
        if (p == null && classe.endsWith("*0")) p = prix.get(classe.substring(0, classe.length() - 2));
        return p;
    }
    public static long misAJour() { return misAJour; }
    public static String etat() { return etat; }
    public static int nombre() { return prix.size(); }
    public static void surMaj(Runnable r) { surMaj = (r == null) ? () -> { } : r; }

    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            try { travailler(); }
            finally { fini = true; surMaj.run(); }
        }, "atelier-prix-site");
        t.setDaemon(true);
        t.start();
    }

    private static void travailler() {
        charger();
        surMaj.run();
        if (System.currentTimeMillis() - misAJour < VALIDITE && !prix.isEmpty()) {
            etat = "";
            return;
        }
        // Premiere page seule : elle donne le nombre de pages.
        String premiere = lire(ARCHIVES);
        if (premiere == null) { echec(0); return; }
        int pages = Math.max(1, dernierePage(premiere));
        Map<String, Prix> lus = new ConcurrentHashMap<>(analyser(premiere));
        prix.putAll(lus);

        // Le reste a 4 pages a la fois, chaque fil laissant 300 ms entre deux
        // pages : environ une minute au lieu de dix, sans marteler le site.
        // Les prix sont visibles au fur et a mesure.
        java.util.concurrent.atomic.AtomicInteger faites = new java.util.concurrent.atomic.AtomicInteger(1);
        java.util.concurrent.atomic.AtomicInteger echecs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService fils = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "atelier-prix-page");
            t.setDaemon(true);
            return t;
        });
        final int total = pages;
        for (int p = 2; p <= pages; p++) {
            final int page = p;
            fils.submit(() -> {
                if (echecs.get() >= 10) return;              // site en panne : on arrete
                String html = lire(ARCHIVES + "page/" + page + "/");
                if (html == null) echecs.incrementAndGet();
                else {
                    Map<String, Prix> m = analyser(html);
                    lus.putAll(m);
                    prix.putAll(m);
                }
                int n = faites.incrementAndGet();
                etat = "Lecture des prix sur habbofurni.xyz : " + n + " / " + total + " pages...";
                if (n % 10 == 0) surMaj.run();
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            });
        }
        fils.shutdown();
        try { fils.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES); }
        catch (InterruptedException e) { return; }

        if (lus.size() < 100 || echecs.get() >= 10) { echec(lus.size()); return; }
        misAJour = System.currentTimeMillis();
        sauver();
        etat = "";
        Journal.debug("prix habbofurni : " + lus.size() + " mobis lus sur " + pages
                + " pages" + (echecs.get() > 0 ? " (" + echecs.get() + " pages en échec)" : "") + ".");
    }

    /** Lecture ratee (site en panne, mise en page changee) : on garde l'ancien fichier. */
    private static void echec(int lus) {
        etat = misAJour == 0 ? "Le site habbofurni.xyz n'a pas pu être lu : prix du marché du jeu à la place."
                             : "Le site habbofurni.xyz n'a pas pu être relu : prix gardés du " + date(misAJour) + ".";
        System.err.println("[Atelier] prix habbofurni : lecture ratée (" + lus + " prix).");
    }

    private static String lire(String adresse) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh) Atelier/1.0 (outil de build Habbo)");
            if (c.getResponseCode() != 200) return null;
            return new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- analyse

    private static final Pattern ARTICLE = Pattern.compile(
            "<article([^>]*\\bclass=\"[^\"]*\\bfurni\\b[^\"]*\"[^>]*)>(.*?)</article>", Pattern.DOTALL);
    private static final Pattern PAGE = Pattern.compile("/page/(\\d+)/");

    /** Les mobis d'une page d'archives : classe -> prix. Logique pure, testable. */
    static Map<String, Prix> analyser(String html) {
        Map<String, Prix> r = new LinkedHashMap<>();
        Matcher m = ARTICLE.matcher(html);
        while (m.find()) {
            String attrs = m.group(1), corps = m.group(2);
            String tld = attribut(attrs, "data-tld");
            if (tld != null && !"fr".equals(tld)) continue;          // prix d'un autre hotel
            if ("no".equals(attribut(attrs, "data-has-price"))) continue;
            String classe = attribut(corps, "title");
            String moyen = attribut(attrs, "data-price-avg");
            if (classe == null || moyen == null) continue;
            try {
                int v = (int) Math.round(Double.parseDouble(moyen.replace(",", ".")));
                if (v <= 0) continue;
                String src = attribut(attrs, "data-price-source");
                r.put(classe.trim(), new Prix(v, src == null ? "?" : src));
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    static int dernierePage(String html) {
        int max = 1;
        Matcher m = PAGE.matcher(html);
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return Math.min(max, 2000);
    }

    private static String attribut(String texte, String nom) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(nom) + "=[\"']([^\"']*)[\"']").matcher(texte);
        return m.find() ? m.group(1) : null;
    }

    private static String date(long t) {
        return new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(t));
    }

    // -------------------------------------------------------------- stockage

    private static void charger() {
        if (!FICHIER.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(FICHIER.toPath()), StandardCharsets.UTF_8));
            misAJour = o.optLong("misAJour", 0);
            JSONObject p = o.optJSONObject("prix");
            if (p != null) for (String k : p.keySet()) {
                JSONObject j = p.getJSONObject(k);
                prix.put(k, new Prix(j.optInt("moyen"), j.optString("source", "?")));
            }
        } catch (Throwable t) {
            Journal.debug("prix habbofurni : fichier illisible, il sera refait : " + t);
        }
    }

    private static synchronized void sauver() {
        try {
            JSONObject p = new JSONObject();
            for (Map.Entry<String, Prix> e : new TreeMap<>(prix).entrySet()) {
                JSONObject j = new JSONObject();
                j.put("moyen", e.getValue().moyen);
                j.put("source", e.getValue().source);
                p.put(e.getKey(), j);
            }
            JSONObject o = new JSONObject();
            o.put("misAJour", misAJour);
            o.put("prix", p);
            File d = FICHIER.getParentFile();
            if (!d.exists()) d.mkdirs();
            File tmp = new File(d, "prix-habbofurni.json.tmp");
            Files.write(tmp.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FICHIER.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Atelier] prix habbofurni : sauvegarde impossible : " + t);
        }
    }
}

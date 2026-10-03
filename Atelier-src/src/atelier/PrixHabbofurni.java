package atelier;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prix estimes d'apres habbofurni.xyz : la moyenne des ventes entre joueurs
 * sur habbo.fr, PAS le prix du catalogue. Indicatif seulement.
 *
 * Une page par mobi : https://habbofurni.xyz/furni_habbo/<classe>/ ; le prix
 * est dans la balise meta description :
 *   « ... Sa valeur moyenne est de 949 crédits sur habbo.fr — ... »
 *
 * Variantes de couleur « classe*3 » : le site les nomme « classe_3 » (verifie :
 * rare_dragonlamp*1 -> 404, rare_dragonlamp_1 -> 200). On essaie donc
 * « classe_3 », puis la classe de base (prix signale « de la base »).
 *
 * Politesse envers le site : au plus 2 requetes a la fois, ~300 ms entre deux
 * departs. Cache memoire + disque (prix.json), valable 24 h.
 */
public final class PrixHabbofurni {

    private PrixHabbofurni() { }

    public enum Statut { OK, BASE, SANS_VALEUR, INTROUVABLE }

    /** Un prix connu (ou l'absence connue de prix). */
    public static final class Entree {
        public final Double credits;   // null si pas de valeur
        public final Statut statut;
        public final long date;
        Entree(Double c, Statut s, long d) { credits = c; statut = s; date = d; }
    }

    private static final long VALIDITE = 24L * 3600 * 1000;
    private static final String SITE = "https://habbofurni.xyz/furni_habbo/";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Map<String, Entree> memoire = new ConcurrentHashMap<>();
    private static final Set<String> enCours = ConcurrentHashMap.newKeySet();
    private static volatile boolean chargeDisque = false;
    private static final ExecutorService fils = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "atelier-prix");
        t.setDaemon(true);
        return t;
    });
    private static final Object rythme = new Object();
    private static long dernierDepart = 0;
    private static volatile int echecsReseau = 0;
    private static volatile HttpClient client;

    // ------------------------------------------------------------ lectures

    /** Le prix en cache s'il a moins de 24 h, sinon null. */
    public static Entree enCache(String classe) {
        if (classe == null) return null;
        chargerDisque();
        Entree e = memoire.get(classe);
        return (e != null && System.currentTimeMillis() - e.date < VALIDITE) ? e : null;
    }

    public static boolean enAttente(String classe) { return classe != null && enCours.contains(classe); }

    /** Date de la mise a jour la plus recente du cache (0 si vide). */
    public static long dateCache() {
        chargerDisque();
        long d = 0;
        for (Entree e : memoire.values()) d = Math.max(d, e.date);
        return d;
    }

    // ------------------------------------------------------------ requetes

    /**
     * Demande les prix des classes donnees (hors cache valide, sauf si forcer).
     * chaque : appele (hors fil FX) apres chaque prix ; fin : a la fin, avec un
     * message d'erreur ou null si tout s'est bien passe.
     */
    public static void demander(Collection<String> classes, boolean forcer,
                                Consumer<String> chaque, Consumer<String> fin) {
        chargerDisque();
        List<String> afaire = new ArrayList<>();
        for (String c : new LinkedHashSet<>(classes))
            if (c != null && !c.isBlank() && (forcer || enCache(c) == null) && enCours.add(c)) afaire.add(c);
        if (afaire.isEmpty()) { if (fin != null) fin.accept(null); return; }
        echecsReseau = 0;
        CountDownLatch reste = new CountDownLatch(afaire.size());
        final String[] erreur = {null};
        for (String c : afaire) {
            fils.submit(() -> {
                try {
                    if (echecsReseau >= 3) return;     // site injoignable : on arrete
                    Entree e = chercher(c);
                    memoire.put(c, e);
                    echecsReseau = 0;
                } catch (IOException ex) {
                    echecsReseau++;
                    erreur[0] = "habbofurni.xyz injoignable (" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : " : " + ex.getMessage()) + ")";
                } catch (Throwable t) {
                    erreur[0] = "erreur de lecture des prix : " + t;
                } finally {
                    enCours.remove(c);
                    reste.countDown();
                    if (chaque != null) try { chaque.accept(c); } catch (Throwable ignored) { }
                }
            });
        }
        Salle.tache("prix-fin", () -> {
            try { reste.await(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
            sauver();
            if (fin != null) fin.accept(erreur[0]);
        });
    }

    /** Une classe : essais successifs selon la forme du nom. */
    private static Entree chercher(String classe) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        int etoile = classe.indexOf('*');
        if (etoile < 0) {
            Double[] v = page(classe);
            return v == null ? new Entree(null, Statut.INTROUVABLE, now)
                    : new Entree(v[0], v[0] == null ? Statut.SANS_VALEUR : Statut.OK, now);
        }
        String base = classe.substring(0, etoile);
        Double[] v = page(base + "_" + classe.substring(etoile + 1));
        if (v != null) return new Entree(v[0], v[0] == null ? Statut.SANS_VALEUR : Statut.OK, now);
        v = page(base);
        if (v != null) return new Entree(v[0], v[0] == null ? Statut.SANS_VALEUR : Statut.BASE, now);
        return new Entree(null, Statut.INTROUVABLE, now);
    }

    /**
     * Lit une page. null = page inexistante (404) ; {null} = page sans valeur ;
     * {prix} sinon. IOException si le site ne repond pas.
     */
    private static Double[] page(String nom) throws IOException, InterruptedException {
        attendreTour();
        String url = SITE + URLEncoder.encode(nom, StandardCharsets.UTF_8).replace("+", "%20") + "/";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Accept", "text/html")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .GET().build();
        HttpResponse<String> r = client().send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 404 || r.statusCode() == 410) return null;
        if (r.statusCode() != 200) throw new IOException("réponse HTTP " + r.statusCode());
        return new Double[]{extraire(r.body())};
    }

    private static HttpClient client() {
        if (client == null)
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        return client;
    }

    private static void attendreTour() throws InterruptedException {
        long attente;
        synchronized (rythme) {
            long t = System.currentTimeMillis();
            long depart = Math.max(t, dernierDepart + 300);
            dernierDepart = depart;
            attente = depart - t;
        }
        if (attente > 0) Thread.sleep(attente);
    }

    private static final Pattern META = Pattern.compile(
            "<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALEUR = Pattern.compile(
            "valeur\\s+moyenne\\s+est\\s+de\\s+([0-9][0-9\\s\\u00A0\\u202F.,]*)\\s*cr[ée]dits?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Le prix dans la page, ou null si la page n'en donne pas. */
    static Double extraire(String html) {
        if (html == null) return null;
        String desc = null;
        Matcher m = META.matcher(html);
        if (m.find()) desc = m.group(1);
        Double v = desc == null ? null : nombreDans(entites(desc));
        if (v == null) v = nombreDans(entites(html));
        return v;
    }

    private static Double nombreDans(String s) {
        Matcher m = VALEUR.matcher(s);
        if (!m.find()) return null;
        return nombre(m.group(1));
    }

    /** « 1 234 », « 1.234 », « 1,5 », « 12.5 » -> nombre. */
    static Double nombre(String brut) {
        String s = brut.replaceAll("[\\s\\u00A0\\u202F]", "");
        s = s.replaceAll("[.,]+$", "");
        if (s.isEmpty()) return null;
        if (s.matches("\\d{1,3}([.,]\\d{3})+")) s = s.replaceAll("[.,]", "");      // milliers
        else if (s.matches("\\d{1,3}(\\.\\d{3})+,\\d+")) s = s.replace(".", "").replace(',', '.');
        else if (s.matches("\\d{1,3}(,\\d{3})+\\.\\d+")) s = s.replace(",", "");
        else s = s.replace(',', '.');
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String entites(String s) {
        return s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&#8239;", " ")
                .replace("&eacute;", "é").replace("&#233;", "é").replace("&amp;", "&");
    }

    // ------------------------------------------------------------ disque

    /**
     * Le dossier de l'utilisatrice reelle : sous sudo, user.home vaut celui
     * de root ; SUDO_USER donne le vrai nom.
     */
    static File fichier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String sudo = System.getenv("SUDO_USER");
        String home = (sudo != null && !sudo.isBlank() && !"root".equals(sudo))
                ? (os.contains("mac") ? "/Users/" + sudo : "/home/" + sudo)
                : System.getProperty("user.home");
        File d;
        if (os.contains("win")) {
            String ad = System.getenv("APPDATA");
            d = new File(ad != null ? ad : home + "/AppData/Roaming", "Atelier");
        } else if (os.contains("mac")) {
            d = new File(home, "Library/Application Support/Atelier");
        } else d = new File(home, ".atelier");
        return new File(d, "prix.json");
    }

    private static synchronized void chargerDisque() {
        if (chargeDisque) return;
        chargeDisque = true;
        try {
            File f = fichier();
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (String k : o.keySet()) {
                JSONObject e = o.optJSONObject(k);
                if (e == null) continue;
                Double v = e.has("v") && !e.isNull("v") ? e.optDouble("v") : null;
                Statut s;
                try { s = Statut.valueOf(e.optString("s", "OK")); } catch (Exception x) { s = Statut.OK; }
                memoire.putIfAbsent(k, new Entree(v, s, e.optLong("t", 0)));
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] cache des prix illisible : " + t);
        }
    }

    private static synchronized void sauver() {
        try {
            JSONObject o = new JSONObject();
            long limite = System.currentTimeMillis() - 7 * VALIDITE;   // on oublie au bout d'une semaine
            for (Map.Entry<String, Entree> e : memoire.entrySet()) {
                if (e.getValue().date < limite) continue;
                JSONObject j = new JSONObject();
                j.put("v", e.getValue().credits == null ? JSONObject.NULL : e.getValue().credits);
                j.put("s", e.getValue().statut.name());
                j.put("t", e.getValue().date);
                o.put(e.getKey(), j);
            }
            File f = fichier();
            File d = f.getParentFile();
            if (d != null) d.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Atelier] cache des prix non enregistré : " + t);
        }
    }
}

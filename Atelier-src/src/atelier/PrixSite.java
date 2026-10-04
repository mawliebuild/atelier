package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prix des mobis de l'hotel FR d'apres habbofurni.xyz.
 *
 * Source : l'export JSON du site (EXPORT), mis a jour chaque heure, lu en UNE
 * demande au plus une fois par heure (le site prefere ce fichier aux ~300 pages
 * d'archives d'avant ; le texte qui suit decrit cette ancienne lecture).
 *
 * Le site range environ 9 000 mobis dans ses archives (~300 pages de 30).
 * Chaque fiche porte le nom de classe du mobi, son prix moyen en credits et
 * l'origine de ce prix : « market » (moyenne de la place du marche) ou
 * « site » (estimation du site, pour les rares peu vendus).
 *
 * Au demarrage de l'Atelier, les prix viennent d'abord du fichier
 * (prix-habbofurni.json, voir PrixFichier) : affichage immediat. S'il a plus
 * de 24 h, on relit le site en tache de fond :
 *   - pages compressees (gzip) : ~70 Ko au lieu de ~440 Ko par page, et
 *     repli sur l'autre adresse du site si la premiere refuse (PrixReseau) ;
 *   - 3 pages a la fois au plus, et au plus une page lancee toutes les
 *     300 ms (tous fils confondus) ; si le site dit « trop de demandes »
 *     (503 / 429 : mesure le 4 oct. apres ~270 pages a 4 fils), pause de
 *     15 s et deux fois moins vite, la page est redemandee ; delais de
 *     8 s / 20 s ; au-dela de 10 echecs, on arrete (site en panne) ;
 *   - les pages ratees sont redemandees une fois a la fin ;
 *   - le travail est enregistre toutes les 20 pages : un Atelier ferme en
 *     pleine lecture reprend ou il en etait au lancement suivant (lecture
 *     commencee il y a moins de 24 h).
 * Les prix lus remplacent les anciens au fur et a mesure ; un mobi absent de
 * la nouvelle lecture garde son ancien prix.
 */
public final class PrixSite {

    public static final class Prix {
        public final int moyen;
        public final String source;     // "market" | "site" | autre valeur du site
        Prix(int moyen, String source) { this.moyen = moyen; this.source = source; }
    }

    private static final String ARCHIVES = "https://habbofurni.xyz/archives-des-mobis/";
    /** L'export est refait toutes les heures : pas besoin de le relire plus souvent. */
    static final long VALIDITE = 3600_000L;
    private static final String NOM = "prix-habbofurni.json";
    private static final int FILS = 3, ECHECS_MAX = 10, SAUVER_TOUTES = 20;
    private static final long ECART_MS = 300, ECART_MAX_MS = 2_000;

    private static final Map<String, Prix> prix = new ConcurrentHashMap<>();
    private static volatile long misAJour = 0;
    private static volatile boolean demarre = false;
    private static volatile String etat = "";
    /** Vrai quand la lecture (ou la decision de ne pas relire) est terminee. */
    private static volatile boolean fini = false;
    private static volatile boolean enLecture = false, stop = false;
    private static volatile int pagesFaites = 0, pagesTotal = 0;
    /** Lecture en cours (reprise) : debut et pages deja lues. */
    private static volatile long lectureDebut = 0;
    private static final Set<Integer> pagesLues = ConcurrentHashMap.newKeySet();
    /** Bilan de la derniere lecture : null = reussie, sinon le probleme. */
    private static volatile String dernierEchec = null;
    private static final AtomicLong octets = new AtomicLong();
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

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
    public static boolean fini() { return fini; }
    static boolean enLecture() { return enLecture; }
    static int pagesFaites() { return pagesFaites; }
    static int pagesTotal() { return pagesTotal; }
    static String dernierEchec() { return dernierEchec; }

    /** Ajoute un ecouteur, appele (hors fil FX) quand des prix changent ou que la lecture avance. */
    public static void surMaj(Runnable r) { if (r != null) ecouteurs.add(r); }

    private static void notifier() {
        for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable t) { Journal.debug("prix habbofurni : écouteur : " + t); }
    }

    /** Lit le fichier puis, s'il est vieux, le site. Une seule fois ; n'importe quel fil. */
    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        enLecture = true;           // lecture du fichier : l'interface montre « chargement »
        Thread t = new Thread(() -> {
            try {
                charger();
                notifier();
                if (!PrixFichier.perime(misAJour, VALIDITE, System.currentTimeMillis()) && !prix.isEmpty()) {
                    etat = "";
                    return;
                }
                lire();
            } finally { enLecture = false; fini = true; notifier(); }
            relireChaqueHeure();
        }, "atelier-prix-site");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Tant que l'Atelier est ouvert : le fichier du site est relu des qu'il a
     * plus d'une heure (VALIDITE), sans rien a faire. Verifie toutes les
     * 5 minutes ; jamais pendant une lecture deja en cours.
     */
    private static void relireChaqueHeure() {
        long essai = System.currentTimeMillis();     // une tentative par heure au plus, meme si le site ne repond pas
        while (true) {
            dormir(5 * 60_000L);
            try {
                long t = System.currentTimeMillis();
                if (enLecture || t - essai < VALIDITE || !PrixFichier.perime(misAJour, VALIDITE, t)) continue;
                essai = t;
                lire();
                notifier();
            } catch (Throwable t) {
                Journal.debug("prix habbofurni : relecture automatique : " + t);
            }
        }
    }

    /**
     * Relit tout le site maintenant, meme si les prix sont recents.
     * fin (hors fil FX) recoit null si tout va bien, sinon le probleme.
     * false si une lecture est deja en cours.
     */
    static synchronized boolean actualiser(java.util.function.Consumer<String> fin) {
        if (enLecture) return false;
        demarre = true;
        enLecture = true;           // tout de suite, pour que l'interface le voie
        Thread t = new Thread(() -> {
            try {
                if (misAJour == 0 && prix.isEmpty()) charger();
                // lecture complete : on oublie la reprise
                lectureDebut = 0;
                pagesLues.clear();
                lire();
            } finally {
                fini = true;
                // fin d'abord : « Actualiser » passe avant le passage automatique
                if (fin != null) try { fin.accept(dernierEchec); } catch (Throwable ignored) { }
                notifier();
            }
        }, "atelier-prix-site");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Arrete la lecture en cours (le travail deja fait est garde). */
    static void arreter() { stop = true; }

    // ---------------------------------------------------------------- lecture

    private static void lire() {
        enLecture = true;
        stop = false;
        dernierEchec = null;
        octets.set(0);
        long t0 = System.currentTimeMillis();
        try {
            lireTout(t0);
        } finally {
            enLecture = false;
            pagesFaites = 0;
            pagesTotal = 0;
        }
    }

    /**
     * L'export officiel de habbofurni.xyz (fichier JSON mis a jour toutes les
     * heures, donne par le site pour l'Atelier) : UNE seule demande au lieu des
     * ~300 pages des archives. Format : {"prices": {classe: [prix, "market"|"site"]}}.
     */
    private static final String EXPORT = "https://habbofurni.xyz/wp-content/uploads/hbf-export/prices-fr.json";

    private static void lireTout(long t0) {
        etat = "Lecture des prix sur habbofurni.xyz…";
        pagesTotal = 1;
        pagesFaites = 0;
        notifier();
        PrixReseau.Reponse r = lire(EXPORT);
        if (r == null || r.code != 200 || r.corps == null) {
            echec(0, r == null ? "le site ne répond pas" : "réponse " + r.code);
            return;
        }
        Map<String, Prix> m = new java.util.HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(r.corps);
            org.json.JSONObject p = o.getJSONObject("prices");
            for (String classe : p.keySet()) {
                org.json.JSONArray v = p.optJSONArray(classe);
                if (v == null || v.length() < 1) continue;
                int moyen = v.optInt(0, -1);
                if (moyen < 0) continue;
                m.put(classe, new Prix(moyen, v.length() > 1 ? v.optString(1, "site") : "site"));
            }
        } catch (Throwable t) {
            echec(0, "fichier des prix illisible (" + t.getMessage() + ")");
            return;
        }
        if (m.size() < 100) { echec(m.size(), "fichier des prix presque vide"); return; }
        prix.putAll(m);                 // un mobi absent du fichier garde son ancien prix
        pagesFaites = 1;
        misAJour = System.currentTimeMillis();
        lectureDebut = 0;
        pagesLues.clear();
        sauver();
        etat = "";
        Journal.debug("prix habbofurni : " + m.size() + " mobis lus dans l'export en "
                + (System.currentTimeMillis() - t0) + " ms, " + (r.octets / 1024) + " Ko reçus.");
    }

    /** Lecture ratee (site en panne, mise en page changee) : on garde les anciens prix. */
    private static void echec(int lus, String pourquoi) {
        sauver();   // garde ce qui a ete lu, et la reprise
        etat = misAJour == 0 ? "Le site habbofurni.xyz n'a pas pu être lu : prix du marché du jeu à la place."
                             : "Le site habbofurni.xyz n'a pas pu être relu : prix gardés du " + date(misAJour) + ".";
        dernierEchec = pourquoi;
        Journal.debug("prix habbofurni : lecture ratée (" + pourquoi + ", " + lus + " prix).");
    }

    private static PrixReseau.Reponse page(int n) {
        return lire(n <= 1 ? ARCHIVES : ARCHIVES + "page/" + n + "/");
    }

    /** Une page (compressee, voir PrixReseau) ; null si le site ne repond pas. */
    private static PrixReseau.Reponse lire(String adresse) {
        try {
            PrixReseau.Reponse r = PrixReseau.lire(adresse);
            octets.addAndGet(r.octets);
            if (r.code != 200) Journal.debug("prix habbofurni : " + adresse + " : réponse " + r.code);
            return r;
        } catch (Throwable t) {
            Journal.debug("prix habbofurni : " + adresse + " : " + t);
            return null;
        }
    }

    // Rythme commun a tous les fils : au plus une page toutes les ecartMs.
    private static final Object rythme = new Object();
    private static long dernierDepart = 0;
    private static volatile long ecartMs = ECART_MS, pauseJusqua = 0;

    private static void attendreTour() {
        long attente;
        synchronized (rythme) {
            long t = System.currentTimeMillis();
            long depart = Math.max(Math.max(t, pauseJusqua), dernierDepart + ecartMs);
            dernierDepart = depart;
            attente = depart - t;
        }
        if (attente > 0) dormir(attente);
    }

    /** Le site dit « trop de demandes » : pause de 15 s pour tous, puis deux fois moins vite. */
    private static void ralentir(int code) {
        synchronized (rythme) {
            long t = System.currentTimeMillis();
            if (pauseJusqua > t) return;            // un autre fil vient deja de ralentir
            pauseJusqua = t + 15_000;
            ecartMs = Math.min(ECART_MAX_MS, ecartMs * 2);
        }
        Journal.debug("prix habbofurni : site occupé (" + code + "), pause de 15 s puis une page toutes les " + ecartMs + " ms.");
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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

    private static final Map<String, Pattern> ATTRIBUTS = new ConcurrentHashMap<>();

    private static String attribut(String texte, String nom) {
        Pattern p = ATTRIBUTS.computeIfAbsent(nom,
                n -> Pattern.compile("\\b" + Pattern.quote(n) + "=[\"']([^\"']*)[\"']"));
        Matcher m = p.matcher(texte);
        return m.find() ? m.group(1) : null;
    }

    private static String date(long t) {
        return new java.text.SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(t));
    }

    // -------------------------------------------------------------- stockage

    /** Fichier -> memoire. Logique testable (voir PrixTests). */
    static synchronized void charger() {
        JSONObject o = PrixFichier.lire(NOM, NOM);
        if (o == null) return;
        try {
            misAJour = o.optLong("misAJour", 0);
            JSONObject p = o.optJSONObject("prix");
            if (p != null) for (String k : p.keySet()) {
                JSONObject j = p.optJSONObject(k);
                if (j == null) continue;
                int v = j.optInt("moyen");
                if (v > 0) prix.putIfAbsent(k, new Prix(v, j.optString("source", "?")));
            }
            JSONObject l = o.optJSONObject("lecture");
            if (l != null) {
                lectureDebut = l.optLong("debut", 0);
                JSONArray a = l.optJSONArray("pages");
                if (a != null) for (int i = 0; i < a.length(); i++) pagesLues.add(a.optInt(i));
            }
        } catch (Throwable t) {
            Journal.debug("prix habbofurni : fichier illisible, il sera refait : " + t);
        }
    }

    static synchronized void sauver() {
        JSONObject p = new JSONObject();
        for (Map.Entry<String, Prix> e : new TreeMap<>(prix).entrySet()) {
            JSONObject j = new JSONObject();
            j.put("moyen", e.getValue().moyen);
            j.put("source", e.getValue().source);
            p.put(e.getKey(), j);
        }
        JSONObject o = new JSONObject();
        o.put("misAJour", misAJour);
        if (lectureDebut > 0 && !pagesLues.isEmpty()) {
            JSONObject l = new JSONObject();
            l.put("debut", lectureDebut);
            l.put("pages", new JSONArray(new TreeSet<>(pagesLues)));
            o.put("lecture", l);
        }
        o.put("prix", p);
        if (!PrixFichier.ecrire(NOM, o))
            System.err.println("[Atelier] prix habbofurni : sauvegarde impossible.");
    }

    /** Tests : vide la memoire. */
    static synchronized void oublierPourTest() {
        prix.clear(); misAJour = 0; lectureDebut = 0; pagesLues.clear();
    }
    static void poserPourTest(String classe, int moyen, String source, long date) {
        prix.put(classe, new Prix(moyen, source)); misAJour = date;
    }
}

package atelier;

import java.util.*;
import java.util.regex.*;

/**
 * Regroupe les lignes de furnidata en familles nommees en francais.
 *
 * La furnidata compte 327 valeurs de « furniline » — hween_2011 a hween_2025,
 * xmas_*, easter_*, nft2023... Les presenter telles quelles serait illisible :
 * on les regroupe en une vingtaine de familles, avec l'annee a part quand elle
 * existe.
 */
public final class Categories {

    /** Famille -> motif de reconnaissance sur la ligne de furnidata. */
    private static final LinkedHashMap<String, Pattern> FAMILLES = new LinkedHashMap<>();
    static {
        f("Halloween",        "^h?ween.*|^halloween.*");
        f("Noël",             "^xmas.*|^christmas.*|^santa.*|^nordic.*");
        f("Pâques",           "^easter.*|^bunny.*");
        f("Saint-Valentin",   "^val.*|^valentine.*|^love.*");
        f("Été",              "^summer.*|^beach.*|^tiki.*|^sunny.*");
        f("Rares",            "^rare$|^bonusrare$|^rare_.*|^ltd.*");
        f("Builders Club",    "^buildersclub.*|^bc_.*");
        f("Wired",            "^wired.*");
        f("NFT",              "^nft.*");
        f("Habbo Club",       "^habbo_club.*|^hc_.*|^club_.*");
        f("Duckets",          "^duckets.*");
        f("Diamants",         "^diamond.*");
        f("Trophées",         "^trophies.*|^trophy.*");
        f("Classiques",       "^classics.*|^hhistory.*");
        f("Publicités",       "^ad_.*");
        f("Jeux",             "^bb_.*|^snowwar.*|^football.*|^game.*|^sf_.*");
        f("Animaux",          "^pet.*|^horse.*|^petfood.*");
        f("Bazar",            "^bazaar.*|^pura.*|^iced.*|^mode.*|^plasto.*|^diner.*");
        f("Nouvel An",        "^nye.*|^newyear.*");
        f("Carnaval",         "^carnival.*|^brasil.*");
    }

    private static void f(String nom, String motif) {
        FAMILLES.put(nom, Pattern.compile(motif, Pattern.CASE_INSENSITIVE));
    }

    public static final String TOUTES = "Toutes les catégories";
    public static final String AUTRES = "Autres";
    public static final String TOUTES_ANNEES = "Toutes les années";

    private Categories() { }

    /** Les familles, dans l'ordre d'affichage. */
    public static List<String> familles() {
        List<String> l = new ArrayList<>();
        l.add(TOUTES);
        l.addAll(FAMILLES.keySet());
        l.add(AUTRES);
        return l;
    }

    /** Mots du catalogue francais (chemin des pages) -> famille. */
    private static final LinkedHashMap<String, Pattern> MOTS_CATALOGUE = new LinkedHashMap<>();
    static {
        m("Halloween",      "halloween|épouvante|frisson");
        m("Noël",           "noël|noel|hiver|nordique");
        m("Pâques",         "pâques|paques|lapin");
        m("Saint-Valentin", "valentin|amour");
        m("Été",            "été|plage|tropical");
        m("Rares",          "\\brares?\\b|ltd");
        m("Builders Club",  "builders club");
        m("Wired",          "wired");
        m("NFT",            "\\bnft\\b");
        m("Habbo Club",     "habbo club");
        m("Trophées",       "trophée");
        m("Nouvel An",      "nouvel an");
        m("Carnaval",       "carnaval");
    }

    private static void m(String nom, String motif) {
        MOTS_CATALOGUE.put(nom, Pattern.compile(motif, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /**
     * Famille d'un mobi, en croisant trois indices : sa furniline, puis son nom
     * de classe (« hween_c24_lamp » meme quand la furniline est vide ou neutre),
     * puis le chemin de sa page dans le catalogue francais.
     */
    public static String famille(String ligne, String classe, String chemin) {
        String f = famille(ligne);
        if (!AUTRES.equals(f)) return f;
        if (classe != null && !classe.isEmpty()) {
            f = famille(classe.toLowerCase(java.util.Locale.ROOT));
            if (!AUTRES.equals(f)) return f;
        }
        if (chemin != null)
            for (Map.Entry<String, Pattern> e : MOTS_CATALOGUE.entrySet())
                if (e.getValue().matcher(chemin).find()) return e.getKey();
        return AUTRES;
    }

    /** Famille d'une ligne de furnidata. */
    public static String famille(String ligne) {
        if (ligne == null || ligne.isEmpty()) return AUTRES;
        // Quelques centaines de lignes differentes pour des milliers de mobis :
        // chaque ligne n'est passee aux expressions qu'une seule fois.
        return familleDe.computeIfAbsent(ligne, l -> {
            for (Map.Entry<String, Pattern> e : FAMILLES.entrySet())
                if (e.getValue().matcher(l).matches()) return e.getKey();
            return AUTRES;
        });
    }

    private static final Map<String, String> familleDe = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> anneeDe = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern ANNEE = Pattern.compile("(19|20)\\d{2}");

    /** Annee contenue dans la ligne, ou null. Gere xmas_2024 comme nft2024. */
    public static String annee(String ligne) {
        if (ligne == null) return null;
        String a = anneeDe.computeIfAbsent(ligne, l -> {
            Matcher m = ANNEE.matcher(l);
            return m.find() ? m.group() : "";
        });
        return a.isEmpty() ? null : a;
    }

    /** true si la ligne appartient a la famille et a l'annee demandees. */
    public static boolean correspond(String ligne, String famille, String annee) {
        if (famille == null || TOUTES.equals(famille)) return true;
        if (!famille.equals(famille(ligne))) return false;
        if (annee == null || TOUTES_ANNEES.equals(annee)) return true;
        return annee.equals(annee(ligne));
    }
}

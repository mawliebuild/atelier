package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Ecarts d'alignement des mobis muraux : la logique pure (sans JavaFX, sans jeu).
 *
 * Un ecart, c'est deux valeurs pour un mobi :
 *     droite  en pans de mur, entre deux copies cote a cote ;
 *     haut    en pixels, entre deux copies l'une au-dessus de l'autre.
 * Une valeur <= 0 veut dire « pas connue ».
 *
 * Un mobi se reconnait a son nom technique (classe, ex. « poster ») de
 * preference : il ne change pas d'un hotel a l'autre. Le numero de type sert
 * de secours quand la furnidata n'est pas encore chargee.
 *
 * Fichier JSON (ressource embarquee ressources/atelier/ecarts-defaut.json,
 * et fichier exporte par « Exporter mes ecarts comme defauts ») :
 *     { "version": 1,
 *       "ecarts": [ { "classe": "poster", "type": 4001, "nom": "Poster",
 *                     "droite": 1, "haut": 32 } ] }
 *
 * Ordre de priorite, axe par axe : enregistre par l'utilisatrice > livre avec
 * le programme > mesure sur les copies deja posees > valeur de base.
 */
public final class EcartsDefaut {

    private EcartsDefaut() { }

    /** Valeurs de base, quand on ne sait rien du mobi. */
    public static final int BASE_DROITE = 1, BASE_HAUT = 32;

    public static final String RESSOURCE = "/atelier/ecarts-defaut.json";

    /** D'ou vient une valeur. */
    public enum Origine {
        ENREGISTRE("enregistré par toi"),
        DEFAUT("livré avec le programme"),
        MESURE("mesuré sur les copies déjà posées"),
        BASE("valeur de base");
        public final String texte;
        Origine(String t) { texte = t; }
    }

    /** Les ecarts d'un mobi. */
    public static final class Ecart {
        public String classe;     // nom technique, peut etre null
        public int type = -1;     // numero de type, -1 si inconnu
        public String nom;        // nom affiche, peut etre null
        public int droite = -1;   // pans ; <= 0 : inconnu
        public int haut = -1;     // pixels ; <= 0 : inconnu

        public Ecart() { }
        public Ecart(String classe, int type, String nom, int droite, int haut) {
            this.classe = EcartsDefaut.vide(classe) ? null : classe;
            this.type = type; this.nom = EcartsDefaut.vide(nom) ? null : nom;
            this.droite = droite; this.haut = haut;
        }
        public Ecart copie() { return new Ecart(classe, type, nom, droite, haut); }

        /** Meme mobi : meme classe, ou a defaut meme type. */
        public boolean memeMobi(Ecart o) {
            if (o == null) return false;
            if (classe != null && o.classe != null) return classe.equalsIgnoreCase(o.classe);
            return type >= 0 && type == o.type;
        }
        public boolean vide() { return droite <= 0 && haut <= 0; }
        @Override public String toString() {
            return (classe != null ? classe : "#" + type) + " → " + droite + " ↑ " + haut;
        }
    }

    /** Une valeur et son origine. */
    public static final class Valeur {
        public final int valeur;
        public final Origine origine;
        Valeur(int v, Origine o) { valeur = v; origine = o; }
        @Override public String toString() { return valeur + " (" + origine + ")"; }
    }

    // --------------------------------------------------------------- JSON

    /** Lit un fichier d'ecarts. Les lignes illisibles sont sautees, pas d'exception sur une entree. */
    public static List<Ecart> lire(String json) {
        List<Ecart> r = new ArrayList<>();
        if (json == null || json.isBlank()) return r;
        JSONObject o = new JSONObject(json);          // un fichier mal forme leve : l'appelant le dit
        JSONArray a = o.optJSONArray("ecarts");
        if (a == null) return r;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            Ecart x = new Ecart(e.optString("classe", null), e.optInt("type", -1), e.optString("nom", null),
                    e.optInt("droite", -1), e.optInt("haut", -1));
            if (x.classe == null && x.type < 0) continue;
            if (x.vide()) continue;
            r.add(x);
        }
        return r;
    }

    /** Ecrit un fichier d'ecarts, trie par nom, lisible a l'oeil. */
    public static String ecrire(Collection<Ecart> ecarts) {
        List<Ecart> l = new ArrayList<>(ecarts);
        l.sort(Comparator.comparing(EcartsDefaut::cleTri));
        // Ecrit a la main pour garder l'ordre des champs (JSONObject ne le garde pas).
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"version\": 1,\n");
        b.append("  \"explication\": ").append(JSONObject.quote(
                "Écarts par défaut des mobis muraux pour Murs › Aligner des murs. "
                + "droite = pans de mur entre deux copies côte à côte, haut = pixels entre deux rangées. "
                + "Un écart enregistré dans l'Atelier passe avant celui-ci.")).append(",\n");
        b.append("  \"ecarts\": [");
        boolean premier = true;
        for (Ecart e : l) {
            if (e.vide() || (e.classe == null && e.type < 0)) continue;
            b.append(premier ? "\n" : ",\n");
            premier = false;
            b.append("    {");
            List<String> champs = new ArrayList<>();
            if (e.classe != null) champs.add("\"classe\": " + JSONObject.quote(e.classe));
            if (e.type >= 0) champs.add("\"type\": " + e.type);
            if (e.nom != null) champs.add("\"nom\": " + JSONObject.quote(e.nom));
            if (e.droite > 0) champs.add("\"droite\": " + e.droite);
            if (e.haut > 0) champs.add("\"haut\": " + e.haut);
            b.append(String.join(", ", champs)).append("}");
        }
        b.append(premier ? "]\n}\n" : "\n  ]\n}\n");
        return b.toString();
    }

    private static String cleTri(Ecart e) {
        String n = e.nom != null ? e.nom : e.classe != null ? e.classe : "";
        return n.toLowerCase(Locale.ROOT) + "\u0000" + (e.classe == null ? "" : e.classe) + "\u0000" + e.type;
    }

    // --------------------------------------------------------- ressource

    private static volatile List<Ecart> livres;
    private static volatile String erreur;

    /** Les ecarts livres dans le jar ; liste vide si absents. Lus une fois. */
    public static List<Ecart> livres() {
        List<Ecart> l = livres;
        if (l != null) return l;
        synchronized (EcartsDefaut.class) {
            if (livres != null) return livres;
            List<Ecart> r = new ArrayList<>();
            try (InputStream in = EcartsDefaut.class.getResourceAsStream(RESSOURCE)) {
                if (in != null) r = lire(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Throwable t) {
                erreur = "Écarts par défaut illisibles : " + t.getMessage();
                Journal.erreur(erreur, t);
            }
            livres = Collections.unmodifiableList(r);
            return livres;
        }
    }

    /** Le probleme rencontre en lisant la ressource, ou null. */
    public static String erreur() { livres(); return erreur; }

    // ------------------------------------------------------------ fusion

    /** Le defaut livre pour ce mobi, ou null. */
    public static Ecart chercher(List<Ecart> l, String classe, int type) {
        Ecart cle = new Ecart(classe, type, null, -1, -1);
        for (Ecart e : l) if (cle.memeMobi(e)) return e;
        // classe connue d'un cote seulement : on retombe sur le type
        if (type >= 0) for (Ecart e : l) if (e.type == type) return e;
        return null;
    }

    /**
     * Valeur effective d'un axe. {@code mesure} <= 0 : rien de mesure.
     */
    public static Valeur resoudre(int enregistre, int defaut, int mesure, int base) {
        if (enregistre > 0) return new Valeur(enregistre, Origine.ENREGISTRE);
        if (defaut > 0) return new Valeur(defaut, Origine.DEFAUT);
        if (mesure > 0) return new Valeur(mesure, Origine.MESURE);
        return new Valeur(base, Origine.BASE);
    }

    /** Une ligne de la liste fusionnee : les valeurs effectives et d'ou elles viennent. */
    public static final class Ligne {
        public final Ecart effectif;          // valeurs effectives (enregistre > defaut)
        public final Ecart enregistre;        // ou null
        public final Ecart defaut;            // ou null
        Ligne(Ecart eff, Ecart enr, Ecart def) { effectif = eff; enregistre = enr; defaut = def; }
        public Origine origine(boolean axeDroite) {
            int v = axeDroite ? (enregistre == null ? -1 : enregistre.droite) : (enregistre == null ? -1 : enregistre.haut);
            return v > 0 ? Origine.ENREGISTRE : Origine.DEFAUT;
        }
    }

    /**
     * Fusionne les defauts livres et les ecarts enregistres : un mobi par ligne,
     * l'enregistre passe avant le defaut, axe par axe. Logique pure.
     */
    public static List<Ligne> fusionner(List<Ecart> defauts, List<Ecart> enregistres) {
        List<Ligne> r = new ArrayList<>();
        Set<Ecart> pris = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Ecart enr : enregistres) {
            Ecart def = null;
            for (Ecart d : defauts) if (!pris.contains(d) && (enr.memeMobi(d) || (enr.type >= 0 && enr.type == d.type))) { def = d; break; }
            if (def != null) pris.add(def);
            Ecart eff = enr.copie();
            if (def != null) {
                if (eff.droite <= 0) eff.droite = def.droite;
                if (eff.haut <= 0) eff.haut = def.haut;
                if (eff.classe == null) eff.classe = def.classe;
                if (eff.type < 0) eff.type = def.type;
                if (eff.nom == null) eff.nom = def.nom;
            }
            r.add(new Ligne(eff, enr, def));
        }
        for (Ecart d : defauts) if (!pris.contains(d)) r.add(new Ligne(d.copie(), null, d));
        r.sort(Comparator.comparing(l -> cleTri(l.effectif)));
        return r;
    }

    // ------------------------------------------------------------ mesure

    /**
     * Mesure l'ecart sur les copies deja posees du meme mobi : {pans, pixels},
     * -1 quand rien ne se mesure. Logique pure.
     *
     * Cote a cote : meme face, memes offsets, meme coordonnee « fixe » du mur
     * (x sur une face 'l', y sur une face 'r'), l'autre coordonnee differe.
     * L'un au-dessus de l'autre : meme face, meme pan, meme offX, offY differe.
     * On garde le plus petit ecart non nul trouve a partir du mobi de reference.
     */
    public static int[] mesurer(String reference, Collection<String> autres) {
        int[] r = {-1, -1};
        int[] p = position(reference);
        if (p == null || autres == null) return r;
        for (String s : autres) {
            int[] q = position(s);
            if (q == null || q[4] != p[4]) continue;
            boolean faceL = p[4] == 'l';
            int fixeP = faceL ? p[0] : p[1], fixeQ = faceL ? q[0] : q[1];
            int courP = faceL ? p[1] : p[0], courQ = faceL ? q[1] : q[0];
            if (fixeP == fixeQ && p[2] == q[2] && p[3] == q[3] && courP != courQ) {
                int d = Math.abs(courP - courQ);
                if (r[0] < 0 || d < r[0]) r[0] = d;
            }
            if (p[0] == q[0] && p[1] == q[1] && p[2] == q[2] && p[3] != q[3]) {
                int d = Math.abs(p[3] - q[3]);
                if (r[1] < 0 || d < r[1]) r[1] = d;
            }
        }
        return r;
    }

    /** « :w=21,25 l=3,-257 r » -> {21, 25, 3, -257, 'r'} ; null si illisible. */
    static int[] position(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(":w=(-?\\d+),(-?\\d+)\\s+l=(-?\\d+),(-?\\d+)\\s+([lr])").matcher(s);
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5).charAt(0)};
    }

    private static boolean vide(String s) { return s == null || s.isBlank(); }
}

package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ce que la furnidata dit d'un mobi, reduit a ce qui sert a le classer.
 *
 * Le champ « category » de la furnidata est souvent « other » (le gazon, par
 * exemple) : seul, il classerait mal. On le croise donc avec ce que le mobi
 * PERMET (s'asseoir, s'allonger, marcher dessus), avec le fait qu'il soit
 * mural, et avec son nom de classe.
 */
public final class Fiche {

    public static final String TOUS_TYPES = "Tous les types";
    public static final List<String> TYPES = Arrays.asList(
            TOUS_TYPES, "Assises", "Lits", "Tables", "Sols et dalles", "Muraux",
            "Lumières", "Plantes", "Rangements", "Wired", "Téléporteurs", "Autres");

    public final String classe;
    public final boolean mur;
    public final String ligne;       // furniline : sert a la collection
    public final String type;        // une valeur de TYPES
    public final boolean bc, rare;

    private Fiche(String classe, boolean mur, String ligne, String type, boolean bc, boolean rare) {
        this.classe = classe; this.mur = mur;
        this.ligne = ligne; this.type = type; this.bc = bc; this.rare = rare;
    }

    /**
     * L'annee du mobi : celle du repertoire (catalogue, nouveaute, estimation),
     * sinon celle ecrite dans sa collection. Lue a chaque fois : le repertoire
     * se complete pendant que l'Atelier tourne.
     */
    // Famille et annee demandent plusieurs expressions regulieres par mobi :
    // calculees UNE fois par type, et recalculees seulement quand le repertoire
    // du catalogue change (generation). Filtrer un gros inventaire ne refait
    // plus ces calculs a chaque changement de filtre.
    private static volatile int generation = 0;
    private int genCalcul = -1;
    private String anneeCalc, familleCalc;
    private boolean estimeeCalc;

    /** Le repertoire du catalogue a change : les familles et annees sont a recalculer. */
    public static void invalider() { generation++; }

    private synchronized void calculer() {
        int g = generation;
        if (genCalcul == g) return;
        Repertoire.Entree e = Repertoire.entree(classe, mur);
        anneeCalc = (e != null && e.annee != null) ? e.annee : Categories.annee(ligne);
        familleCalc = Categories.famille(ligne, classe, e == null ? null : e.chemin);
        estimeeCalc = e != null && e.annee != null && e.estimee();
        genCalcul = g;
    }

    public String annee() { calculer(); return anneeCalc; }

    /** Famille (Halloween, Noël...) : furniline, puis classe, puis page du catalogue. */
    public String famille() { calculer(); return familleCalc; }

    public boolean anneeEstimee() { calculer(); return estimeeCalc; }

    /** Une fiche par (sol|mur, typeId) : la furnidata ne change pas en cours de route. */
    private static final Map<String, Fiche> cache = new ConcurrentHashMap<>();

    /** null si la furnidata n'est pas encore chargee (on ne retient pas cet echec). */
    public static Fiche de(Moteur gp, HInventoryItem it) {
        boolean mur = it.getType() == HProductType.WallItem;
        String cle = (mur ? "m" : "s") + it.getTypeId();
        Fiche f = cache.get(cle);
        if (f != null) return f;
        f = lire(gp, it.getTypeId(), mur);
        if (f != null) cache.put(cle, f);
        return f;
    }

    private static Fiche lire(Moteur gp, int typeId, boolean mur) {
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd == null || !fd.isReady()) return null;
            if (mur) {
                String cls = fd.getWallItemName(typeId);
                Furnidata.Mobi d = (cls == null) ? null : fd.getWallItemDetails(cls);
                if (d == null) return new Fiche(cls, true, null, "Muraux", false, false);
                return new Fiche(cls, true, d.furniline, "Muraux", d.isBC, d.isRare);
            }
            String cls = fd.getFloorItemName(typeId);
            Furnidata.Mobi d = (cls == null) ? null : fd.getFloorItemDetails(cls);
            if (d == null) return new Fiche(cls, false, null, "Autres", false, false);
            return new Fiche(cls, false, d.furniline, typeSol(cls, d.category, d.canSitOn, d.canLayOn, d.canStandOn),
                    d.isBC, d.isRare);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Le type d'un mobi de sol. Logique pure, testable sans le jeu. */
    static String typeSol(String classe, String categorie, boolean assis, boolean couche, boolean marche) {
        String c = (classe == null) ? "" : classe.toLowerCase(Locale.ROOT);
        String k = (categorie == null) ? "" : categorie.toLowerCase(Locale.ROOT);
        if (c.startsWith("wf_")) return "Wired";
        if (couche) return "Lits";
        if (assis) return "Assises";
        if (k.contains("teleport") || c.contains("teleport") || c.startsWith("tele_")) return "Téléporteurs";
        if (k.contains("table") || c.contains("table")) return "Tables";
        if (k.contains("light") || k.contains("lamp") || c.contains("lamp") || c.contains("light"))
            return "Lumières";
        if (k.contains("plant") || k.contains("flower") || c.contains("plant") || c.contains("flower")
                || c.contains("tree")) return "Plantes";
        if (k.contains("shelf") || k.contains("storage") || c.contains("shelf")
                || c.contains("bookcase") || c.contains("cabinet")) return "Rangements";
        if (marche) return "Sols et dalles";
        return "Autres";
    }

    // ------------------------------------------------------------------ filtre

    public enum Bc { TOUS, BC, HORS_BC }

    /** Le choix courant de la fenetre, fige : lu par le fil des paquets. */
    public record Filtre(String famille, String annee, String type, Bc bc, boolean rares) {
        public boolean actif() {
            return !Categories.TOUTES.equals(famille)
                    || (annee != null && !Categories.TOUTES_ANNEES.equals(annee))
                    || !TOUS_TYPES.equals(type)
                    || bc != Bc.TOUS || rares;
        }

        public boolean garde(Fiche f) {
            if (!actif()) return true;
            if (!Categories.TOUTES.equals(famille)
                    && (f == null ? !Categories.AUTRES.equals(famille) : !famille.equals(f.famille())))
                return false;
            if (annee != null && !Categories.TOUTES_ANNEES.equals(annee)
                    && (f == null || !annee.equals(f.annee()))) return false;
            if (!TOUS_TYPES.equals(type) && (f == null || !type.equals(f.type))) return false;
            if (bc == Bc.BC && (f == null || !f.bc)) return false;
            if (bc == Bc.HORS_BC && f != null && f.bc) return false;
            return !rares || (f != null && f.rare);
        }

        /** Pour les messages : « Noël 2024 · Assises · BC ». */
        public String libelle() {
            StringBuilder sb = new StringBuilder();
            if (!Categories.TOUTES.equals(famille)) sb.append(famille);
            if (annee != null && !Categories.TOUTES_ANNEES.equals(annee)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(annee);
            }
            if (!TOUS_TYPES.equals(type)) sep(sb).append(type);
            if (bc == Bc.BC) sep(sb).append("BC");
            if (bc == Bc.HORS_BC) sep(sb).append("hors BC");
            if (rares) sep(sb).append("rares");
            return sb.length() == 0 ? "tout" : sb.toString();
        }

        private static StringBuilder sep(StringBuilder sb) {
            return sb.length() == 0 ? sb : sb.append(" · ");
        }
    }
}

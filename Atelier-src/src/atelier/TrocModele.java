package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * La config troc ecrite par l'Atelier, en code (comme le ferait generer.py
 * avec WIRED.md), sans partir d'une copie wired : une WiredCollage.Copie,
 * collee en ligne de piles par ConfigTroc.
 *
 * PRINCIPE : aucun wired ne depend du mobi vendu ni de son prix ; une seule
 * config par salle sert a toutes les vitrines.
 *   - le prix est la variable de mobi « prix » posee sur une VITRINE (par
 *     l'Atelier, fenetre « Vendre au troc » : WiredSetObjectVariableValue) ;
 *   - le mobi vendu est le TYPE de la vitrine cliquee ;
 *   - le stock est le coffre a mobis (wf_storage_furni1) ;
 *   - l'argent va au coffre a credits (wf_storage_coins2).
 *
 * LES PILES (une par groupe logique, posees cote a cote, les wf_var d'abord) :
 *   0. « prix » : wf_var_furni prix (a la valeur, permanente) + wf_xtra_varfx_number
 *      « gold » : le prix s'affiche en dore au-dessus de chaque vitrine.
 *   1. « ctx » : wf_var_context ctx (a la valeur), seule sur sa case.
 *   2. le coffre a mobis.   3. le coffre a credits.
 *   4. ACHAT (clic) : wf_trg_click_furni (source 200) + wf_slc_furni_with_var
 *      « prix » (mode 0 : tous les mobis qui ont la variable) : tout clic sur une
 *      vitrine declenche. wf_xtra_scan_chest_furni_by_type met dans ctx le nombre
 *      de mobis du type clique (source 0) dans le coffre a mobis (source 100).
 *      wf_act_send_signal vers l'antenne, en faisant suivre la vitrine (source 0)
 *      et l'acheteur (source 0) ; ctx suit le signal (variable de contexte).
 *   5. TRANSACTION (signal) : wf_trg_recv_signal (antenne) + wf_cnd_var_val_match
 *      ctx > 0 (le coffre a ce type). Effets : wf_act_cancel_transaction (toute
 *      transaction en cours) puis wf_act_init_transaction : coffres = credits +
 *      mobis, contrat = le contrat personnalise, multiplicateur automatique
 *      limite par ctx (on n'achete pas plus que le stock), expiration 300 s.
 *      Si la condition echoue : wf_act_neg_call_stacks -> la pile 6.
 *   6. MESSAGE : wf_act_show_message « Il n'y a plus de $(mobi) disponible. »
 *      (visible par l'acheteur seul) + wf_xtra_text_output_furni_name « mobi »
 *      (le mobi du signal, source 201).
 *   7. CONTRAT : wf_xtra_custom_contract (paiement : credits, montant = variable
 *      de mobi « prix » de la vitrine ; recompense : 1 mobi du type de la
 *      vitrine) + l'antenne wf_antenna2 du signal.
 *
 * D'APRES LA COPIE « ~Vide2 — 6 oct. 12h14 » de l'utilisatrice (memes options,
 * memes sources), avec ces differences :
 *   - plus de piles « :#(ctx) », « :stop », « clic pour poser le prix » ni de
 *     variable utilisateur prix_utilisateur (et son affichage) : le prix est
 *     donne par l'Atelier (bouton « Vendre au troc ») ;
 *   - wf_var_context ctx sur sa propre case : sur la case du wf_var_user et de
 *     son wf_xtra_varfx_number, l'add-on avait deux wf_var dans sa pile
 *     (WIRED.md : un add-on varfx agit sur LE wf_var de sa pile) ;
 *   - wf_act_send_signal fait suivre l'utilisateur DECLENCHEUR (source 0) et
 *     non « les utilisateurs du selecteur » (200) : le selecteur de la pile ne
 *     choisit que des mobis, la source 200 ne transmettait personne ;
 *   - le contrat et l'antenne ont leur propre pile, a cote du message.
 */
final class TrocModele {

    private TrocModele() { }

    /** Ids fictifs des variables de la copie (WIRED.md : positifs, jamais 0) ; le collage les traduit par le nom. */
    static final String V_PRIX = "900001", V_CTX = "900003";
    static final String NOM_PRIX = "prix", NOM_CTX = "ctx";
    static final String MESSAGE = "Il n'y a plus de $(mobi) disponible.";

    /** Les 22 options du varfx « gold » de la copie de l'utilisatrice (WIRED.md, exemple reel). */
    static final int[] VARFX_GOLD = {0, 2, 0, 7, 0, 3000, 0, 3, -1, 201, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 0};

    /** Hauteur d'empilement d'une boite (relevee dans la copie de l'utilisatrice). */
    static double hauteur(String classe) {
        if (classe.startsWith("wf_var_")) return 1.2;
        if (classe.startsWith("wf_slc_")) return 1.0;
        if (classe.startsWith("wf_xtra_")) return 0.37;
        if (classe.startsWith("wf_storage_") || classe.startsWith("wf_antenna")) return 0.5;
        return 0.65;
    }

    // ================================================================ construction

    /** Les piles en cours : une case par pile, le long de x. */
    private static final class Plan {
        final List<WiredCollage.Piece> pieces = new ArrayList<>();
        int x = -1, id = 0;
        double z = 0;

        void pile() { x++; z = 0; }

        /** Pose un mobi en haut de la pile en cours ; config null = pas un wired. Rend son id. */
        int pose(String classe, JSONObject config) {
            int i = ++id;
            String genre = config == null ? null : WiredLecteur.genreDe(classe);
            if (config != null) config.put("wiredId", i);
            pieces.add(new WiredCollage.Piece(i, classe, "0", x, 0, WiredCollage.arrondi(z), 0, genre, config, null));
            z += hauteur(classe);
            return i;
        }

        /** Reserve un id (mobi pose plus tard, cite avant). */
        int reserver() { return ++id; }

        /** Pose sous un id reserve. */
        void pose(int i, String classe, JSONObject config) {
            String genre = config == null ? null : WiredLecteur.genreDe(classe);
            if (config != null) config.put("wiredId", i);
            pieces.add(new WiredCollage.Piece(i, classe, "0", x, 0, WiredCollage.arrondi(z), 0, genre, config, null));
            z += hauteur(classe);
        }
    }

    private static JSONArray ints(int... v) { JSONArray a = new JSONArray(); for (int i : v) a.put(i); return a; }

    private static JSONArray textes(String... v) { JSONArray a = new JSONArray(); for (String s : v) a.put(s); return a; }

    /** Un reglage au format des copies (WIRED.md, section 1). */
    private static JSONObject r(int[] options, String texte, int[] items, int[] items2,
                                int[] sourcesMobis, int[] sourcesAvatars, String... variables) {
        JSONObject o = new JSONObject();
        o.put("options", ints(options));
        o.put("config", texte == null ? "" : texte);
        o.put("items", ints(items));
        o.put("secondItems", ints(items2));
        o.put("furniSources", ints(sourcesMobis));
        o.put("userSources", ints(sourcesAvatars));
        o.put("variableIds", textes(variables));
        return o;
    }

    private static final int[] RIEN = {};

    private static int[] t(int... v) { return v; }

    /** Effet : delai 0. */
    private static JSONObject effet(JSONObject o) { o.put("delay", 0); return o; }

    /** Condition : tous correspondent. */
    private static JSONObject condition(JSONObject o) { o.put("quantifier", 0); return o; }

    /** Selecteur : ni filtre ni inversion. */
    private static JSONObject selecteur(JSONObject o) { o.put("filter", false); o.put("inverse", false); return o; }

    /** wf_var : variableId inutile (retrouve par le nom). */
    private static JSONObject variable(JSONObject o) { o.put("variableId", ""); return o; }

    /** La config troc, piles le long de x a partir de (0, 0). Logique pure. */
    static WiredCollage.Copie copie() { return copie(TrocCoffres.COFFRE_MOBIS, TrocCoffres.COFFRE_CREDITS); }

    /**
     * La config troc avec ces coffres (une variante que l'utilisatrice a dans
     * son inventaire : les coffres ne sont pas au Builders Club). Logique pure.
     */
    static WiredCollage.Copie copie(String classeCoffreMobis, String classeCoffreCredits) {
        Plan p = new Plan();

        // 0. variable de mobi « prix » et son affichage dore
        p.pile();
        p.pose("wf_var_furni", variable(r(t(1, 10), NOM_PRIX, RIEN, RIEN, RIEN, RIEN)));
        p.pose("wf_xtra_varfx_number", r(VARFX_GOLD, "gold", RIEN, RIEN, RIEN, RIEN, "0", "0", "0"));

        // 1. variable de contexte « ctx » (stock du type clique)
        p.pile();
        p.pose("wf_var_context", variable(r(t(1), NOM_CTX, RIEN, RIEN, RIEN, RIEN)));

        // 2-3. les coffres
        p.pile();
        int coffreMobis = p.pose(classeCoffreMobis, null);
        p.pile();
        int coffreCredits = p.pose(classeCoffreCredits, null);

        // ids cites avant d'etre poses
        int antenne = p.reserver(), message = p.reserver(), contrat = p.reserver();

        // 4. achat : clic sur une vitrine
        p.pile();
        p.pose("wf_trg_click_furni", r(RIEN, "", RIEN, RIEN, t(200), RIEN));
        p.pose("wf_slc_furni_with_var", selecteur(r(t(2, 0, 0, 0, -10), "", RIEN, RIEN, t(100), t(0), V_PRIX, "0")));
        p.pose("wf_act_send_signal", effet(r(t(0, 0), "", t(antenne), RIEN, t(100, 0), t(0))));
        p.pose("wf_xtra_scan_chest_furni_by_type", r(t(0), "", t(coffreMobis), RIEN, t(0, 100), RIEN, V_CTX));

        // 5. transaction : le signal, si le coffre a ce type
        p.pile();
        p.pose("wf_trg_recv_signal", r(RIEN, "", t(antenne), RIEN, t(100), RIEN));
        p.pose("wf_cnd_var_val_match", condition(r(t(-20, 2, 0, 0, 0, -10), "", RIEN, RIEN, t(0, 200), t(0, 200), V_CTX, "0")));
        p.pose("wf_act_cancel_transaction", effet(r(t(1), "", RIEN, RIEN, t(100), t(0))));
        p.pose("wf_act_init_transaction", effet(r(t(2, 500, 1, -20, 0, 300), "", t(coffreCredits, coffreMobis), t(contrat),
                t(100, 101, 200), t(0, 200), V_CTX, "0", "0")));
        p.pose("wf_act_neg_call_stacks", effet(r(RIEN, "", t(message), RIEN, t(100), RIEN)));

        // 6. message « plus disponible » (appele quand le coffre n'a plus ce type)
        p.pile();
        p.pose(message, "wf_act_show_message", effet(r(t(0, 221, -1), MESSAGE, RIEN, RIEN, RIEN, t(0))));
        p.pose("wf_xtra_text_output_furni_name", r(t(0), "mobi", RIEN, RIEN, t(201), RIEN));

        // 7. contrat (prix de la vitrine en credits contre 1 mobi de son type) et antenne
        p.pile();
        p.pose(contrat, "wf_xtra_custom_contract", r(t(1, 0, 1, 1, 0, 1, 1, 0, 1, -20), "", RIEN, RIEN,
                t(0, 0, 0, 200), t(200, 200), V_PRIX, "0"));
        p.pose(antenne, "wf_antenna2", null);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(V_PRIX, NOM_PRIX);
        vars.put(V_CTX, NOM_CTX);
        List<WiredCollage.Piece> l = new ArrayList<>(p.pieces);
        // du bas vers le haut, comme WiredCollage.construire (ordre d'empilement de la pose)
        l.sort(Comparator.comparingDouble((WiredCollage.Piece x) -> x.z).thenComparingInt(x -> x.y).thenComparingInt(x -> x.x));
        return new WiredCollage.Copie(l, vars, -1, 0);
    }

    /** Ce que fait la config, pour l'aperçu (texte court). */
    static String resume() {
        return "Deux coffres (le stock et la recette) et les wired de vente : un clic sur une vitrine "
                + "ouvre l'achat au prix de la vitrine.";
    }
}

package atelier;

import java.util.Locale;

/**
 * Classement des mobis wired, pour les empiler dans le bon ordre.
 *
 * Les rangs sont ceux du fonctionnement de Habbo, du bas vers le haut. Les
 * prefixes viennent de la furnidata reelle de habbo.fr (267 classes « wf_ ») :
 *
 *   BOITES (une fenetre de reglage, lue par Open -> WiredFurni*) :
 *   wf_trg_*        27   declencheurs
 *   wf_slc_*        20   selecteurs
 *   wf_cnd_*        44   conditions (dont wf_cnd_not_* pour les negatives)
 *   wf_act_*        53   effets (dont send_signal et neg_*)
 *   wf_xtra_*       31   add-ons (dont wf_xtra_filter_* : selecteurs filtres)
 *   wf_var_*         8   variables (fenetre WiredFurniVariable)
 *   et leurs variantes « personnalisees » ou anciennes : wf_test_trg / _cnd /
 *   _act / _xtra / _slc / _var, wf_proto_trg_*, wf_proto_cnd_*,
 *   wf_ltdproto_act_*.
 *
 *   MOBIS WIRED (pas de fenetre de reglage wired, jamais lus ni verifies) :
 *   dalles (wf_colortile, wf_arrowplate*, wf_pressureplate, wf_ringplate,
 *   wf_tile1/2, wf_numbertile1/2, wf_teamcolortile, wf_teamcolorquarter),
 *   portes et murs (wf_glassdoor, wf_firegate, wf_fx_firegate, wf_maze),
 *   antennes (wf_antenna1/2), compteurs (wf_upcounter1/2, wf_game_upcounter1/2,
 *   wf_teammeter1..4, wf_vu, wf_numberscreen), boutons et leviers
 *   (wf_button*, wf_floor_switch1/2, wf_knob, wf_slider, wf_toggle), jetons
 *   (wf_token1..11), connexions (wf_wire1..4), blobs, roue, balle, pyramide
 *   (wf_pyramid, wf_proto_pyramid), wf_box, wf_screenseparator, coffres
 *   (wf_storage_*), contrats (wf_contract_*), wf_room_linker, wf_test_26secret*.
 */
public final class Wired {

    /** Du bas vers le haut de la pile. */
    public enum Rang {
        DECLENCHEUR      (1, "Déclencheur"),
        SELECTEUR        (2, "Sélecteur"),
        SELECTEUR_FILTRE (3, "Sélecteur filtre"),
        CONDITION        (4, "Condition"),
        /**
         * Add-on qui change la facon dont les conditions de la pile sont
         * evaluees (wf_xtra_or_eval : « au moins une condition est remplie »).
         * Il agit au moment des conditions : range juste apres elles.
         */
        CONDITION_ADDON  (5, "Add-on de condition"),
        EFFET            (6, "Effet"),
        EFFET_SIGNAL     (7, "Effet envoyer un signal"),
        EFFET_NEGATIF    (8, "Effet négatif"),
        ADDON            (9, "Add-on"),
        /** Mobi de la famille wired qui n'est pas une boite : dalle, porte, antenne, compteur... */
        MOBI_WIRED       (10, "Mobi wired"),
        AUTRE            (11, "Pas un wired");

        public final int ordre;
        public final String libelle;
        Rang(int o, String l) { ordre = o; libelle = l; }
        @Override public String toString() { return ordre + ". " + libelle; }
    }

    private Wired() { }

    /**
     * true si le mobi est de la famille wired (« wf_ ») : boite OU mobi wired
     * (dalle colorée, antenne...). Pour les calques, les comptes, le miroir.
     * Pour lire, verifier ou ranger des reglages : estBoite.
     */
    public static boolean estWired(String classe) {
        return classe != null && classe.toLowerCase(Locale.ROOT).startsWith("wf_");
    }

    /**
     * true si le mobi est une BOITE wired (declencheur, selecteur, condition,
     * effet, add-on, variable) : elle a une fenetre de reglage, se lit, se
     * verifie et se range dans une pile.
     */
    public static boolean estBoite(String classe) {
        String c = normaliser(classe);
        return c != null && (c.startsWith("wf_trg_") || c.startsWith("wf_slc_") || c.startsWith("wf_cnd_")
                || c.startsWith("wf_act_") || c.startsWith("wf_xtra_") || c.startsWith("wf_var_"));
    }

    /** Mobi de la famille wired qui n'est pas une boite (dalle, porte, antenne, compteur...). */
    public static boolean estMobiWired(String classe) {
        return estWired(classe) && !estBoite(classe);
    }

    /**
     * Nom technique en minuscules, variantes ramenees a la forme commune :
     * wf_test_trg -> wf_trg_, wf_proto_cnd_x -> wf_cnd_x, wf_ltdproto_act_x ->
     * wf_act_x. Logique pure ; null si classe est null.
     */
    static String normaliser(String classe) {
        if (classe == null) return null;
        String c = classe.toLowerCase(Locale.ROOT);
        for (String p : new String[]{"wf_test_", "wf_ltdproto_", "wf_proto_"}) {
            if (!c.startsWith(p)) continue;
            String reste = c.substring(p.length());
            for (String g : new String[]{"trg", "slc", "cnd", "act", "xtra", "var"})
                if (reste.equals(g) || reste.startsWith(g + "_")) return "wf_" + g + "_" + reste.substring(g.length()).replaceFirst("^_", "");
            return c;
        }
        return c;
    }

    /**
     * Add-ons qui portent sur les CONDITIONS de la pile. Dans la furnidata de
     * habbo.fr, un seul : wf_xtra_or_eval (« Wired Add-on : au moins une
     * condition est remplie »), qui fait un OU des conditions au lieu du ET
     * habituel. Les noms « and_eval », « none_eval »… sont prevus si le jeu en
     * ajoute (meme famille *_eval).
     */
    static boolean estAddonDeCondition(String c) {
        return c != null && c.startsWith("wf_xtra_") && c.endsWith("_eval");
    }

    /**
     * Rang d'un wired d'apres son nom technique.
     *
     * Deux cas sont reconnus par leur nom exact, car leur prefixe seul ne suffit
     * pas : « Envoyer un signal » (wf_act_send_signal) et les effets negatifs
     * (wf_act_neg_*), qui sont des wf_act_ mais doivent monter au-dessus des
     * effets ordinaires. L'add-on « au moins une condition est remplie »
     * (wf_xtra_or_eval) est en fait une regle des conditions : CONDITION_ADDON,
     * juste au-dessus d'elles. Les variables (wf_var_) sont rangees avec les add-ons ;
     * les autres « wf_ » sont des mobis wired (MOBI_WIRED), pas des boites.
     */
    public static Rang rang(String classe) {
        String c = normaliser(classe);
        if (c == null) return Rang.AUTRE;

        if (c.startsWith("wf_trg_"))  return Rang.DECLENCHEUR;
        if (c.startsWith("wf_slc_"))  return Rang.SELECTEUR;
        // Les selecteurs filtres sont des wf_xtra_filter_*, pas des wf_slc_ :
        // ils affinent une selection deja faite, d'ou leur place juste au-dessus.
        if (c.startsWith("wf_xtra_filter_")) return Rang.SELECTEUR_FILTRE;
        if (c.startsWith("wf_cnd_"))  return Rang.CONDITION;
        if (c.startsWith("wf_act_")) {
            if (c.startsWith("wf_act_neg_"))        return Rang.EFFET_NEGATIF;
            if (c.equals("wf_act_send_signal"))     return Rang.EFFET_SIGNAL;
            return Rang.EFFET;
        }
        if (estAddonDeCondition(c)) return Rang.CONDITION_ADDON;
        if (c.startsWith("wf_xtra_")) return Rang.ADDON;
        if (c.startsWith("wf_var_"))  return Rang.ADDON;
        if (c.startsWith("wf_"))      return Rang.MOBI_WIRED;
        return Rang.AUTRE;
    }
}

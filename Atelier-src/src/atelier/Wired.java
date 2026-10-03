package atelier;

/**
 * Classement des mobis wired, pour les empiler dans le bon ordre.
 *
 * Les rangs sont ceux du fonctionnement de Habbo, du bas vers le haut. Les
 * prefixes viennent de la furnidata reelle de habbo.fr :
 *   wf_trg_*        27 mobis   declencheurs
 *   wf_slc_*        20 mobis   selecteurs
 *   wf_cnd_*        44 mobis   conditions (dont wf_cnd_not_* pour les negatives)
 *   wf_act_*        53 mobis   effets (dont send_signal et neg_*)
 *   wf_xtra_*       31 mobis   add-ons
 */
public final class Wired {

    /** Du bas vers le haut de la pile. */
    public enum Rang {
        DECLENCHEUR      (1, "Déclencheur"),
        SELECTEUR        (2, "Sélecteur"),
        SELECTEUR_FILTRE (3, "Sélecteur filtre"),
        CONDITION        (4, "Condition"),
        EFFET            (5, "Effet"),
        EFFET_SIGNAL     (6, "Effet envoyer un signal"),
        EFFET_NEGATIF    (7, "Effet négatif"),
        ADDON            (8, "Add-on"),
        AUTRE            (9, "Pas un wired");

        public final int ordre;
        public final String libelle;
        Rang(int o, String l) { ordre = o; libelle = l; }
        @Override public String toString() { return ordre + ". " + libelle; }
    }

    private Wired() { }

    /** true si le mobi est un wired. */
    public static boolean estWired(String classe) {
        return classe != null && classe.startsWith("wf_");
    }

    /**
     * Rang d'un wired d'apres son nom technique.
     *
     * Deux cas sont reconnus par leur nom exact, car leur prefixe seul ne suffit
     * pas : « Envoyer un signal » (wf_act_send_signal) et les effets negatifs
     * (wf_act_neg_*), qui sont des wf_act_ mais doivent monter au-dessus des
     * effets ordinaires.
     */
    public static Rang rang(String classe) {
        if (classe == null) return Rang.AUTRE;
        String c = classe.toLowerCase();

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
        if (c.startsWith("wf_xtra_")) return Rang.ADDON;
        if (c.startsWith("wf_"))      return Rang.ADDON;   // variables, compteurs...
        return Rang.AUTRE;
    }

}

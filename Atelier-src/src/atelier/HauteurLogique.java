package atelier;

import java.util.*;
import java.util.function.IntFunction;

/**
 * Logique pure de l'outil « Hauteur fixe » (testable sans le jeu) :
 *   - reperer les dalles magiques d'une salle (par type connu ou par nom de classe) ;
 *   - compter celles deja la et rediger le message d'un reglage.
 */
final class HauteurLogique {

    private HauteurLogique() { }

    /** Prefixe des noms de classe des dalles magiques (tile_stackmagic, tile_stackmagic1, 2, 4x4...). */
    static final String PREFIXE_DALLE = "tile_stackmagic";

    /** Un mobi de sol vu de loin : id, type, case. */
    static final class Mobi {
        final int id, type, x, y;
        Mobi(int id, int type, int x, int y) { this.id = id; this.type = type; this.x = x; this.y = y; }
    }

    static boolean estDalle(int type, Set<Integer> typesDalles, IntFunction<String> classe) {
        if (typesDalles != null && typesDalles.contains(type)) return true;
        String c = classe == null ? null : classe.apply(type);
        return c != null && c.toLowerCase(Locale.ROOT).startsWith(PREFIXE_DALLE);
    }

    /** Ids des dalles magiques parmi les mobis, dans l'ordre. */
    static List<Integer> dalles(Collection<Mobi> mobis, Set<Integer> typesDalles, IntFunction<String> classe) {
        List<Integer> r = new ArrayList<>();
        for (Mobi m : mobis) if (estDalle(m.type, typesDalles, classe)) r.add(m.id);
        return r;
    }

    /** {total, dont deja la (pas posees par l'Atelier)}. */
    static int[] compte(List<Integer> toutes, Collection<Integer> nos) {
        Set<Integer> n = new HashSet<>(nos);
        int deja = 0;
        for (int id : toutes) if (!n.contains(id)) deja++;
        return new int[]{toutes.size(), deja};
    }

    /**
     * Message dans le jeu apres un reglage de hauteur (une fois par reglage) :
     * dalles reglees (dont deja la), rates, dalles d'un calque verrouille laissees.
     */
    static String messageReglage(String hauteur, boolean dansAppart, int total, int deja, int ratees, int verrou) {
        String v = verrou > 0 ? " " + verrou + " dalle(s) d'un calque verrouillé laissée(s) en place." : "";
        if (!dansAppart) return "Hauteur " + hauteur + " retenue : entre dans un appart pour régler ses dalles.";
        if (total == 0)
            return verrou > 0 ? "Hauteur " + hauteur + " : aucune dalle réglée." + v
                    : "Hauteur " + hauteur + " retenue : aucune dalle magique dans cet appart. Couvre-le pour poser tes mobis à cette hauteur.";
        return "Hauteur " + hauteur + " : " + total + " dalle(s) réglée(s)"
                + (deja > 0 ? ", dont " + deja + " déjà là" : "")
                + (ratees > 0 ? ", " + ratees + " sans réponse." : ".") + v;
    }
}

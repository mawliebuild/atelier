package atelier;

import java.util.*;

/**
 * Copier / coller des calques (Ctrl+C / Ctrl+V, Cmd aussi), en memoire.
 *
 * Ctrl+C retient les ids du calque vise (ou de la selection) et l'appart.
 * En arriere-plan, une copie « portable » (WiredCollage.Copie : positions
 * relatives, reglage des wired) est preparee pour pouvoir coller ailleurs.
 *
 * Ctrl+V :
 *   - meme appart  : comme Dupliquer (copie posee sur place, nouveau calque,
 *                    puis les fleches) ; on peut coller plusieurs fois ;
 *   - autre appart : la copie portable est posee par le moteur de pose apres un clic
 *                    sur la case du coin haut-gauche (sols seulement) ;
 *   - sinon        : un message clair.
 *
 * La logique de decision est pure (testable) ; l'etat est une seule copie.
 */
final class GroupePressePapier {

    private GroupePressePapier() { }

    /** Ce que Ctrl+V doit faire. */
    enum Collage { RIEN, HORS_SALLE, MEME_APPART, AUTRE_APPART, AUTRE_PAS_PRETE, AUTRE_IMPOSSIBLE }

    /** Une copie de calque. */
    static final class Copie {
        final int salle;
        final String nom, calqueId;
        final List<Integer> sols, murs;
        /** Copie pour un autre appart : null tant qu'elle se prepare (ou si impossible). */
        volatile WiredCollage.Copie portable;
        /** Vrai une fois la preparation finie (reussie ou non). */
        volatile boolean prete;
        /** Pourquoi la copie portable manque (null si elle est la). */
        volatile String probleme;

        Copie(int salle, String calqueId, String nom, Collection<Integer> sols, Collection<Integer> murs) {
            this.salle = salle; this.calqueId = calqueId; this.nom = nom;
            this.sols = List.copyOf(new LinkedHashSet<>(sols));
            this.murs = List.copyOf(new LinkedHashSet<>(murs));
        }

        int nombre() { return sols.size() + murs.size(); }
    }

    private static volatile Copie copie = null;

    static Copie copie() { return copie; }

    static void retenir(Copie c) { copie = c; }

    static boolean aUneCopie() { Copie c = copie; return c != null && c.nombre() > 0; }

    /**
     * Logique pure : que faire de Ctrl+V.
     *
     * @param salleCourante -1 hors salle
     * @param pretePortable la copie pour un autre appart est preparee
     * @param portableOk    ... et elle contient au moins un mobi de sol
     */
    static Collage choisir(Copie c, int salleCourante, boolean pretePortable, boolean portableOk) {
        if (c == null || c.nombre() == 0) return Collage.RIEN;
        if (salleCourante == -1) return Collage.HORS_SALLE;
        if (salleCourante == c.salle) return Collage.MEME_APPART;
        if (!pretePortable) return Collage.AUTRE_PAS_PRETE;
        return portableOk ? Collage.AUTRE_APPART : Collage.AUTRE_IMPOSSIBLE;
    }

    static Collage choisir(int salleCourante) {
        Copie c = copie;
        return choisir(c, salleCourante, c != null && c.prete, c != null && c.portable != null && !c.portable.pieces.isEmpty());
    }

    /** « 1 mobi », « 3 mobis ». */
    static String mobis(int n) { return n + (n > 1 ? " mobis" : " mobi"); }

    /** Message de Ctrl+C. */
    static String messageCopie(String nom, int n) {
        return "Calque « " + nom + " » copié (" + mobis(n) + "). Option + Maj + V pour coller (Cmd + V dans le panneau).";
    }

    /** Message de fusion. */
    static String messageFusion(String source, String cible, int n) {
        return "« " + source + " » fusionné dans « " + cible + " » (" + mobis(n) + ").";
    }
}

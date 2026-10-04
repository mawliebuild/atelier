package atelier;

import atelier.PlanteSuivi.Plante;

import java.util.*;
import java.util.function.Predicate;

/**
 * Logique pure du menu Monster Plants : comptages, filtres, tris, textes des
 * cellules et garde du clic sur une ligne. Aucun envoi, aucun controle JavaFX :
 * tout se teste avec un petit main (PlanteVueTest).
 */
final class PlanteVue {

    private PlanteVue() { }

    /** Sous ce delai, une plante est en danger. */
    static final long URGENT_S = 6 * 3600;

    // ------------------------------------------------------------- etat

    /** A besoin d'un soin, d'apres une vie CONNUE (une vie inconnue ne compte pas). */
    static boolean aSoigner(Plante p) {
        return p != null && !p.morte && p.resteVie() >= 0 && p.aBesoin();
    }

    /** Adulte et vivante (le jeu la laisse recolter par sa proprietaire). */
    static boolean adulte(Plante p) {
        return p != null && !p.morte && (p.recoltable || p.adulte());
    }

    /** Ma plante adulte : on peut la recolter. */
    static boolean aRecolter(Plante p, boolean aMoi) { return aMoi && adulte(p); }

    /** Ma plante morte : on peut la composter. */
    static boolean aComposter(Plante p, boolean aMoi) { return aMoi && p != null && p.morte; }

    // ---------------------------------------------------------- comptages

    /** Les chiffres du resume en haut du menu. */
    static final class Bilan {
        int total, aSoigner, adultes, mortes;
        /** Seulement les miennes : ce que les boutons peuvent faire. */
        int aRecolter, aComposter, miennes;
    }

    static Bilan bilan(Collection<Plante> l, Predicate<Plante> aMoi) {
        Bilan b = new Bilan();
        for (Plante p : l) {
            if (p == null) continue;
            boolean moi = aMoi.test(p);
            b.total++;
            if (moi) b.miennes++;
            if (p.morte) b.mortes++;
            if (aSoigner(p)) b.aSoigner++;
            if (adulte(p)) b.adultes++;
            if (aRecolter(p, moi)) b.aRecolter++;
            if (aComposter(p, moi)) b.aComposter++;
        }
        return b;
    }

    /** Resume d'une ligne : « 12 plantes · 3 à soigner · 1 morte · 5 adultes ». */
    static String resume(Bilan b) {
        if (b.total == 0) return "Aucune plante dans cette salle";
        StringBuilder s = new StringBuilder(b.total + (b.total > 1 ? " plantes" : " plante"));
        if (b.aSoigner > 0) s.append(" · ").append(b.aSoigner).append(" à soigner");
        if (b.aRecolter > 0) s.append(" · ").append(b.aRecolter).append(" à récolter");
        if (b.mortes > 0) s.append(" · ").append(b.mortes).append(b.mortes > 1 ? " mortes" : " morte");
        if (b.adultes > 0) s.append(" · ").append(b.adultes).append(b.adultes > 1 ? " adultes" : " adulte");
        return s.toString();
    }

    /** Texte d'un bouton d'action : « Soigner », « Soigner 1 plante », « Soigner les 4 ». */
    static String libelle(String verbe, int n) {
        if (n <= 0) return verbe;
        if (n == 1) return verbe + " 1 plante";
        return verbe + " les " + n;
    }

    /** « Rose, Bob et 3 autres ». */
    static String noms(List<Plante> l, int max) {
        StringBuilder s = new StringBuilder();
        int n = Math.min(max, l.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) s.append(i == l.size() - 1 ? " et " : ", ");
            s.append(l.get(i).nom);
        }
        int reste = l.size() - n;
        if (reste > 0) s.append(" et ").append(reste).append(reste > 1 ? " autres" : " autre");
        return s.toString();
    }

    // ------------------------------------------------------------ filtres

    enum Filtre {
        TOUTES("Toutes"), A_SOIGNER("À soigner"), A_RECOLTER("À récolter"), MORTES("Mortes"), A_MOI("À moi");
        final String texte;
        Filtre(String t) { texte = t; }
    }

    /** La plante passe-t-elle le filtre et la recherche (nom ou proprietaire) ? */
    static boolean garder(Plante p, Filtre f, String recherche, boolean aMoi) {
        if (p == null) return false;
        switch (f == null ? Filtre.TOUTES : f) {
            case A_SOIGNER:  if (!aSoigner(p)) return false; break;
            case A_RECOLTER: if (!aRecolter(p, aMoi)) return false; break;
            case MORTES:     if (!p.morte) return false; break;
            case A_MOI:      if (!aMoi) return false; break;
            default: break;
        }
        if (recherche == null || recherche.isBlank()) return true;
        String r = sansAccents(recherche.trim());
        return sansAccents(p.nom).contains(r) || sansAccents(p.proprioNom).contains(r);
    }

    static String sansAccents(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // --------------------------------------------------------------- tris

    private static String nom(Plante p) { return p.nom == null ? "" : p.nom; }

    /** Rang d'urgence : vivantes a vie connue, puis inconnues, puis mortes. */
    private static int rangUrgence(Plante p) { return p.morte ? 2 : (p.resteVie() < 0 ? 1 : 0); }

    /** Ordre par defaut : la plus urgente d'abord, mortes a la fin. */
    static final Comparator<Plante> URGENCE = Comparator.comparingInt(PlanteVue::rangUrgence)
            .thenComparingLong(Plante::resteVie)
            .thenComparing(PlanteVue::nom, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(p -> p.id);

    static final Comparator<Plante> PAR_NOM = Comparator.comparing(PlanteVue::nom, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(p -> p.id);

    static final Comparator<Plante> PAR_PROPRIO = Comparator
            .comparing((Plante p) -> p.proprioNom == null ? "" : p.proprioNom, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PAR_NOM);

    static final Comparator<Plante> PAR_RARETE = Comparator.comparingInt((Plante p) -> p.rarete).thenComparing(PAR_NOM);

    static final Comparator<Plante> PAR_CROISSANCE = Comparator.comparingDouble(PlanteVue::partCroissanceTri)
            .thenComparing(PAR_NOM);

    private static double partCroissanceTri(Plante p) {
        if (p.morte) return 2;
        double c = partCroissance(p);
        return c < 0 ? 1.5 : c;
    }

    // ------------------------------------------------------------- textes

    /** Etoiles de rarete (0 a 10 -> 0 a 5 etoiles) et le niveau : « ★★★ 6 ». */
    static String rarete(int r) {
        if (r < 0) return "?";
        int e = Math.min(5, (r + 1) / 2);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < e; i++) s.append('★');
        if (s.length() > 0) s.append(' ');
        return s.append(r).toString();
    }

    /** Part de croissance (0..1), -1 inconnue. Adulte : 1. */
    static double partCroissance(Plante p) {
        if (p.recoltable || p.adulte()) return 1;
        if (p.niveau <= 0) return -1;
        int max = p.niveauMax > 0 ? p.niveauMax : 7;
        return Math.max(0, Math.min(1, p.niveau / (double) max));
    }

    static String texteCroissance(Plante p) {
        if (p.recoltable || p.adulte()) return "Adulte";
        if (p.niveau <= 0) return "?";
        String s = p.niveau + "/" + (p.niveauMax > 0 ? p.niveauMax : 7);
        long r = p.resteCroissance();
        if (r > 0) s += " · " + PlanteSuivi.duree(r);
        return s;
    }

    // ---------------------------------------------------- clic sur une ligne

    /**
     * Decide si un clic sur une ligne part vers le jeu (comme un clic sur la
     * plante). Seulement un clic de souris de l'utilisatrice sur une ligne qui
     * reste choisie ; jamais une selection faite par le programme. Meme plante
     * recliquee moins d'une seconde apres le dernier envoi : rien ne repart.
     */
    static final class Clic {
        static final long DELAI_MS = 1000;
        private int dernierId = Integer.MIN_VALUE;
        private long dernierT = 0;

        /** @return vrai s'il faut envoyer le clic au jeu (et le retient). */
        synchronized boolean accepter(int id, boolean parUtilisatrice, boolean choisie, long maintenant) {
            if (!parUtilisatrice || !choisie) return false;
            if (id == dernierId && maintenant - dernierT < DELAI_MS) return false;
            dernierId = id;
            dernierT = maintenant;
            return true;
        }
    }

    // ------------------------------------------------------- reproduction

    /** Deux plantes choisies forment-elles un couple possible ? null si oui, sinon la raison. */
    static String coupleImpossible(List<Plante> sel, Predicate<Plante> disponible) {
        if (sel.size() != 2) return "Choisis deux plantes dans le tableau.";
        Plante a = sel.get(0), b = sel.get(1);
        if (a.id == b.id) return "Choisis deux plantes différentes.";
        for (Plante p : sel) {
            if (p.morte) return p.nom + " est morte.";
            if (!adulte(p)) return p.nom + " n'est pas encore adulte.";
            if (!disponible.test(p)) return p.nom + " ne peut pas se reproduire (pas à toi, ou pas permis).";
        }
        return null;
    }
}

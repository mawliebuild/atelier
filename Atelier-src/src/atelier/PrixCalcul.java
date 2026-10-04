package atelier;

import java.text.Normalizer;
import java.util.*;

/**
 * Le calcul de la valeur, sans JavaFX ni reseau : logique pure, testee.
 *
 *  - fusion des sources : ton prix (PrixPerso) > habbofurni.xyz (PrixSite) >
 *    place du marche du jeu (Marche) ; un mobi non vendable n'a pas de prix ;
 *  - comptage de l'inventaire par mobi different (sol / mur, typeId) ;
 *  - totaux (valeur, mobis, avec / sans prix, non vendables) ;
 *  - filtre et recherche du tableau.
 */
final class PrixCalcul {

    private PrixCalcul() { }

    /** D'ou vient le prix d'une ligne. */
    enum Source {
        PERSO("Ton prix"),
        SITE("Habbofurni"),
        SITE_ESTIMATION("Habbofurni (estimation)"),
        JEU("Marché du jeu"),
        AUCUNE("Aucun prix"),
        NON_VENDABLE("Non vendable");

        final String texte;
        Source(String t) { texte = t; }
    }

    /** Ou chercher les prix. Chaque methode rend null si elle ne sait pas. */
    interface Sources {
        Integer perso(boolean mur, int typeId);
        PrixSite.Prix site(String classe);
        /** Prix moyen du marche du jeu (> 0), ou null. */
        Integer jeu(boolean mur, int typeId);
    }

    /** Un prix et sa source ; prix -1 si aucun. */
    static final class Prix {
        final int prix;
        final Source source;
        Prix(int prix, Source source) { this.prix = prix; this.source = source; }
    }

    static Prix prix(boolean mur, int typeId, String classe, boolean vendable, Sources s) {
        if (!vendable) return new Prix(-1, Source.NON_VENDABLE);
        Integer perso = s.perso(mur, typeId);
        if (perso != null && perso >= 0) return new Prix(perso, Source.PERSO);
        PrixSite.Prix ps = classe == null ? null : s.site(classe);
        if (ps != null && ps.moyen > 0)
            return new Prix(ps.moyen, "site".equals(ps.source) ? Source.SITE_ESTIMATION : Source.SITE);
        Integer j = s.jeu(mur, typeId);
        if (j != null && j > 0) return new Prix(j, Source.JEU);
        return new Prix(-1, Source.AUCUNE);
    }

    // ---------------------------------------------------------------- comptage

    /** Un mobi different de l'inventaire : combien, et s'il se vend. */
    static final class Compte {
        final boolean mur;
        final int typeId;
        int quantite;
        boolean vendable;
        Compte(boolean mur, int typeId) { this.mur = mur; this.typeId = typeId; }
    }

    /**
     * Ajoute un exemplaire. Vendable si au moins un exemplaire est autorise
     * sur la place du marche et n'est pas une location (BC).
     */
    static void compter(Map<String, Compte> g, boolean mur, int typeId, boolean vendable) {
        Compte c = g.computeIfAbsent(Marche.cle(mur, typeId), k -> new Compte(mur, typeId));
        c.quantite++;
        c.vendable |= vendable;
    }

    // ------------------------------------------------------------------ lignes

    /** Une ligne calculee (le tableau en fait une ligne JavaFX). */
    static final class Ligne {
        final String cle, nom, classe;
        final boolean mur, vendable;
        final int typeId, quantite, prix, revision;
        final Source source;
        Ligne(String cle, String nom, String classe, boolean mur, int typeId, int quantite,
              boolean vendable, Prix p, int revision) {
            this.cle = cle; this.nom = nom; this.classe = classe; this.mur = mur; this.typeId = typeId;
            this.quantite = quantite; this.vendable = vendable; this.prix = p.prix; this.source = p.source;
            this.revision = revision;
        }
        long total() { return prix < 0 ? -1 : (long) prix * quantite; }
    }

    // ------------------------------------------------------------------ totaux

    static final class Totaux {
        long valeur;
        int mobis, avecPrix, sansPrix, nonVendables, parJeu, lignes;
    }

    static Totaux totaux(Collection<Ligne> lignes) {
        Totaux t = new Totaux();
        for (Ligne l : lignes) {
            t.lignes++;
            t.mobis += l.quantite;
            if (l.source == Source.NON_VENDABLE) t.nonVendables += l.quantite;
            else if (l.prix < 0) t.sansPrix += l.quantite;
            else {
                t.avecPrix += l.quantite;
                t.valeur += l.total();
                if (l.source == Source.JEU) t.parJeu += l.quantite;
            }
        }
        return t;
    }

    // ------------------------------------------------------- filtre, recherche

    enum Filtre {
        TOUS("Tous"), AVEC_PRIX("Avec prix"), SANS_PRIX("Sans prix"), NON_VENDABLES("Non vendables");
        final String texte;
        Filtre(String t) { texte = t; }
        @Override public String toString() { return texte; }

        boolean garde(Source s, int prix) {
            switch (this) {
                case AVEC_PRIX: return s != Source.NON_VENDABLE && prix >= 0;
                case SANS_PRIX: return s != Source.NON_VENDABLE && prix < 0;
                case NON_VENDABLES: return s == Source.NON_VENDABLE;
                default: return true;
            }
        }
    }

    /** Minuscules sans accents, pour chercher « fauteuil » dans « Fauteuil Club ». */
    static String plat(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    /** Tous les mots cherches sont dans le nom ou la classe. */
    static boolean correspond(String recherchePlate, String nom, String classe) {
        if (recherchePlate == null || recherchePlate.isEmpty()) return true;
        String dans = plat(nom) + " " + plat(classe);
        for (String mot : recherchePlate.split("\\s+"))
            if (!mot.isEmpty() && !dans.contains(mot)) return false;
        return true;
    }
}

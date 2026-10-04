package atelier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Ce que la FENETRE OUVERTE met en valeur dans le jeu, en plus de la selection
 * des calques : la plante choisie (Monster Plants), le mobi Couleur de decor,
 * le wired choisi (Wired). Chaque outil donne ses jetons (« s12 » mobi de sol,
 * « m34 » mural, « p5 » animal d'index 5) ; seuls ceux de la fenetre ouverte
 * comptent. GroupeSelection les envoie au client avec la selection
 * (contour jaune ; fleche de selection du jeu pour un animal).
 */
final class MiseEnValeur {

    private MiseEnValeur() { }

    /** Cle du menu ouvert (Navigation) ; null = aucune fenetre. */
    private static volatile String ouverte = null;
    private static final Map<String, List<Supplier<Collection<String>>>> fournisseurs = new ConcurrentHashMap<>();
    /** Survol d'un bouton ou d'une ligne (calques, « Tout traiter »...) : prioritaire, quelle que soit la fenetre. */
    private static volatile List<String> survol = List.of();

    /** Le menu affiche dans la fenetre (null : fermee). */
    static void fenetre(String cle) { ouverte = cle; }

    /** Ce que le menu « cle » met en valeur quand il est ouvert. */
    static void fournir(String cle, Supplier<Collection<String>> f) {
        fournisseurs.computeIfAbsent(cle, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(f);
    }

    /** Le temps d'un survol : ces jetons sont mis en valeur (null ou vide : fin du survol). */
    static void survol(Collection<String> jetons) { survol = jetons == null ? List.of() : new ArrayList<>(jetons); }

    /** Pour un noeud JavaFX : mis en valeur tant que la souris est dessus. */
    static void auSurvol(javafx.scene.Node n, Supplier<Collection<String>> f) {
        n.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> { try { survol(f.get()); } catch (Throwable ignored) { } });
        n.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> survol(null));
    }

    /** Jetons des mobis de sol de ces types (ou de cette classe) dans la salle. */
    static List<String> solsOu(java.util.function.Predicate<gearth.extensions.parsers.HFloorItem> garder) {
        List<String> r = new ArrayList<>();
        for (gearth.extensions.parsers.HFloorItem it : Salle.sols()) if (garder.test(it)) r.add("s" + it.getId());
        return r;
    }

    static List<String> mursOu(java.util.function.Predicate<gearth.extensions.parsers.HWallItem> garder) {
        List<String> r = new ArrayList<>();
        for (gearth.extensions.parsers.HWallItem w : Salle.murs()) if (garder.test(w)) r.add("m" + w.getId());
        return r;
    }

    /** Les jetons a mettre en valeur maintenant. */
    static List<String> jetons() {
        LinkedHashSet<String> r = new LinkedHashSet<>(survol);
        String c = ouverte;
        List<Supplier<Collection<String>>> l = c == null ? null : fournisseurs.get(c);
        if (l != null) for (Supplier<Collection<String>> f : l) {
            try { Collection<String> x = f.get(); if (x != null) r.addAll(x); } catch (Throwable ignored) { }
        }
        return new ArrayList<>(r);
    }
}

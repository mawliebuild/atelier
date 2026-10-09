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

    static {
        // La fenetre Wired : tous les wired, la ligne choisie ou la pile cliquee (ChoixWired).
        fournir("wired", ChoixWired::jetons);
    }

    /**
     * Le menu affiche dans la fenetre (null : fermee). Ouvrir la fenetre Wired
     * repart de « rien de choisi » : tous les wired de l'appart.
     */
    static void fenetre(String cle) {
        if ("wired".equals(cle) && !"wired".equals(ouverte)) ChoixWired.rien();
        if (cle != null && !cle.equals(ouverte)) {
            List<Runnable> l = aLOuverture.get(cle);
            if (l != null) for (Runnable r : l) try { r.run(); } catch (Throwable ignored) { }
        }
        ouverte = cle;
    }

    private static final Map<String, List<Runnable>> aLOuverture = new ConcurrentHashMap<>();

    /** A chaque ouverture du menu « cle » (pas quand il est deja ouvert). */
    static void aLOuverture(String cle, Runnable r) {
        aLOuverture.computeIfAbsent(cle, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(r);
    }

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

    /**
     * Ce que la fenetre Wired met en valeur (cle « wired ») ; le dernier choix
     * l'emporte :
     *  - rien de choisi (ouverture de la fenetre) : toutes les boites wired de l'appart ;
     *  - une ligne choisie (Ordre, Vérificateur, Rechercher...) : ce wired seul ;
     *  - une case cliquee dans le jeu, ou une pile choisie : toute la pile de la case.
     * Etat dans un seul champ volatile (jamais de controle JavaFX lu ici) :
     * le fournisseur est appele hors du fil JavaFX.
     */
    static final class ChoixWired {
        private ChoixWired() { }

        /** ids non vide : ces wired ; sinon case (x,y) si avecCase ; sinon tout. */
        record Choix(List<Integer> ids, boolean avecCase, int x, int y) { }

        private static volatile Choix choix = new Choix(List.of(), false, 0, 0);

        /** Rien de choisi : tous les wired de l'appart. */
        static void rien() { choix = new Choix(List.of(), false, 0, 0); }

        /** Lignes choisies : ces wired seuls (vide : rien de choisi). */
        static void lignes(Collection<Integer> ids) {
            List<Integer> l = new ArrayList<>();
            if (ids != null) for (Integer i : ids) if (i != null && i > 0) l.add(i);
            choix = l.isEmpty() ? new Choix(List.of(), false, 0, 0) : new Choix(List.copyOf(l), false, 0, 0);
        }

        /** Case cliquee, ou pile choisie : toute la pile de cette case. */
        static void pile(int x, int y) { choix = new Choix(List.of(), true, x, y); }

        /** La pile de cette case est-elle le choix en cours ? */
        static boolean pileChoisie(int x, int y) {
            Choix c = choix;
            return c.avecCase() && c.x() == x && c.y() == y;
        }

        /** Les jetons du choix en cours (fil quelconque). Logique de selection : choisir(). */
        static List<String> jetons() {
            return choisir(choix, WiredLecteur.boitesDeLaSalle());
        }

        /** Logique pure : les jetons du choix c parmi ces boites wired de la salle. */
        static List<String> choisir(Choix c, List<gearth.extensions.parsers.HFloorItem> boites) {
            List<String> r = new ArrayList<>();
            if (!c.ids().isEmpty()) {
                for (int id : c.ids()) r.add("s" + id);
                return r;
            }
            for (gearth.extensions.parsers.HFloorItem it : boites) {
                try {
                    if (c.avecCase() && (it.getTile().getX() != c.x() || it.getTile().getY() != c.y())) continue;
                    r.add("s" + it.getId());
                } catch (Throwable ignored) { }
            }
            return r;
        }
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

    // ------------------------------------------------------------ opacite (muraux)

    /**
     * Mise en valeur par l'opacite seule (ni contour ni couleur) : ces muraux a « pourcent » %
     * d'opacite, tous les autres mobis a 100 %. Liste vide : tout a 100 %. Chaque appel
     * remplace le precedent (le jeu remet tout a 100 % avant). Le choix d'une zone remet
     * aussi tout a 100 % : renvoyer apres si besoin. Sans le client qui le sait, rien.
     */
    static void opaciteMuraux(Collection<Integer> idsMuraux, int pourcent) {
        if (!ClientModifie.saitOpacite() || !Salle.dansUneSalle()) return;
        Moteur gp = Salle.gp();
        if (gp == null) return;
        StringBuilder b = new StringBuilder();
        if (idsMuraux != null)
            for (int id : idsMuraux) { if (b.length() > 0) b.append(','); b.append('m').append(id); }
        if (b.length() > 0) b.append(';').append(Math.max(5, Math.min(100, pourcent)));
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:opacite=" + b, 0, 0, 0, -1));
    }

    /** Tout a 100 % d'opacite. */
    static void effacerOpacite() { opaciteMuraux(List.of(), 100); }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;

import java.util.*;

/**
 * Le fil de l'apercu fantome : l'interface dit ce qu'elle veut voir (calque
 * ou mobis donnes, transformation, sortes de mobis) aussi souvent qu'elle
 * veut ; ce fil n'envoie que la difference avec ce qui est deja affiche, et
 * repart aussitot si la demande change en route (seule la derniere compte).
 * Sert au deplacement d'un calque et a la copie a placer (Dupliquer, Coller).
 */
final class GroupeApercu {

    private GroupeApercu() { }

    /** Ce qu'on veut voir en fantomes. Immuable. */
    static final class Demande {
        /** calque d'origine (null : mobis donnes, ex. copie collee) */
        final String calque;
        /** null : les mobis du calque, relus a chaque calcul */
        final List<Set<Integer>> ids;
        final GroupeCalcul.Transfo tr;
        /** sortes montrees : sols (hors wired), muraux, wired */
        final boolean sols, murs, wired;

        Demande(String calque, List<Set<Integer>> ids, GroupeCalcul.Transfo tr, boolean sols, boolean murs, boolean wired) {
            this.calque = calque;
            this.ids = ids == null ? null : List.of(Set.copyOf(ids.get(0)), Set.copyOf(ids.get(1)));
            this.tr = tr == null ? GroupeCalcul.Transfo.NEUTRE : tr;
            this.sols = sols; this.murs = murs; this.wired = wired;
        }

        boolean tout() { return sols && murs && wired; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Demande)) return false;
            Demande d = (Demande) o;
            return Objects.equals(calque, d.calque) && Objects.equals(ids, d.ids) && tr.equals(d.tr)
                    && sols == d.sols && murs == d.murs && wired == d.wired;
        }
        @Override public int hashCode() { return Objects.hash(calque, ids, tr, sols, murs, wired); }
    }

    private static final Object M = new Object();
    private static Demande demande;        // null = rien a montrer
    private static long generation = 0;
    private static volatile boolean demarre = false;

    static String calque() { synchronized (M) { return demande == null ? null : demande.calque; } }

    static Demande demande() { synchronized (M) { return demande; } }

    static void vouloir(String id, int x, int y) { vouloir(id, x, y, 0); }

    static void vouloir(String id, int x, int y, int q) {
        vouloir(id == null ? null : new Demande(id, null, new GroupeCalcul.Transfo(x, y, q, 0), true, true, true));
    }

    static void vouloir(Demande d) {
        synchronized (M) {
            if (Objects.equals(d, demande) && (d != null || GroupeFantomes.nombre() == 0)) return;
            demande = d;
            generation++;
            M.notifyAll();
        }
    }

    /** Le client a jete les fantomes (rechargement, autre salle) : plus rien a montrer. */
    static void perdu() {
        synchronized (M) { demande = null; generation++; M.notifyAll(); }
    }

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Salle.tache("calques-apercu", GroupeApercu::boucle);
    }

    private static void boucle() {
        long faite = 0;
        while (true) {
            Demande d; long g;
            synchronized (M) {
                while (generation == faite) {
                    try { M.wait(2000); } catch (InterruptedException e) { return; }
                    // calque modifie en route (mobis ajoutes / retires) : on recalcule de temps en temps
                    if (generation == faite && demande != null) break;
                }
                d = demande; g = generation;
            }
            try {
                List<GroupeFantomes.Fantome> voulu = d == null ? List.of() : voulu(d);
                GroupeFantomes.Diff diff = GroupeFantomes.diff(GroupeFantomes.affiches, voulu);
                if (diff.total() > 0) {
                    int ko = GroupeFantomes.appliquer(diff, () -> { synchronized (M) { return generation == g; } });
                    if (ko > 0) Journal.debug("calques : apercu, " + ko + " envoi(s) refuse(s).");
                    Groupes.prevenir();
                }
            } catch (Throwable t) {
                Journal.debug("calques : apercu : " + t);
            }
            faite = g;
        }
    }

    /** Les fantomes du calque a (dx, dy). Sols hors du plan : pas de fantome. */
    static List<GroupeFantomes.Fantome> voulu(String id, int x, int y) { return voulu(id, x, y, 0); }

    /** Idem, le bloc tourne de q quarts de tour (fantomes tournes aussi). */
    static List<GroupeFantomes.Fantome> voulu(String id, int x, int y, int q) {
        return voulu(new Demande(id, null, new GroupeCalcul.Transfo(x, y, q, 0), true, true, true));
    }

    static List<GroupeFantomes.Fantome> voulu(Demande d) {
        List<Set<Integer>> ids = d.ids != null ? d.ids : Groupes.mobis(d.calque);
        if (!d.tout()) ids = filtrer(ids, d.sols, d.murs, d.wired);
        List<GroupeFantomes.Fantome> r = new ArrayList<>();
        List<GroupeCalcul.Element> els = GroupeActions.elements(ids.get(0), ids.get(1));
        boolean tourne = !d.tr.glissement();
        for (GroupeCalcul.Cible c : GroupeCalcul.copie(els, d.tr, Salle::hauteurSol)) {
            if (c.horsPlan || (c.e.mural && c.position == null)) continue;
            int f = GroupeFantomes.idPour(c.e.id, c.e.mural);
            r.add(new GroupeFantomes.Fantome(f, c.e.id, c.e.mural, c.x, c.y, c.z, c.position, tourne ? c.rot : -1));
        }
        return r;
    }

    /** Garde les sortes voulues : sols (hors wired), muraux, wired. Les absents de la salle partent. */
    static List<Set<Integer>> filtrer(List<Set<Integer>> ids, boolean sols, boolean murs, boolean wired) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int id : ids.get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            boolean w = Wired.estWired(Salle.classe(it.getTypeId(), false));
            if (w ? wired : sols) s.add(id);
        }
        Set<Integer> m = murs ? new LinkedHashSet<>(ids.get(1)) : new LinkedHashSet<>();
        return List.of(s, m);
    }
}

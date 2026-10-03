package atelier;

import java.util.*;

/**
 * Le fil de l'apercu fantome : l'interface dit ce qu'elle veut voir (calque,
 * dx, dy) aussi souvent qu'elle veut ; ce fil n'envoie que la difference avec
 * ce qui est deja affiche, et repart aussitot si la demande change en route
 * (seule la derniere demande compte).
 */
final class GroupeApercu {

    private GroupeApercu() { }

    private static final Object M = new Object();
    private static String calque;        // null = rien a montrer
    private static int dx, dy, quarts;
    private static long generation = 0;
    private static volatile boolean demarre = false;

    static String calque() { synchronized (M) { return calque; } }

    static void vouloir(String id, int x, int y) { vouloir(id, x, y, 0); }

    static void vouloir(String id, int x, int y, int q) {
        synchronized (M) {
            if (Objects.equals(id, calque) && x == dx && y == dy && q == quarts && (id != null || GroupeFantomes.nombre() == 0)) return;
            calque = id; dx = x; dy = y; quarts = q;
            generation++;
            M.notifyAll();
        }
    }

    /** Le client a jete les fantomes (rechargement, autre salle) : plus rien a montrer. */
    static void perdu() {
        synchronized (M) { calque = null; generation++; M.notifyAll(); }
    }

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Salle.tache("calques-apercu", GroupeApercu::boucle);
    }

    private static void boucle() {
        long faite = 0;
        while (true) {
            String id; int x, y, q; long g;
            synchronized (M) {
                while (generation == faite) {
                    try { M.wait(2000); } catch (InterruptedException e) { return; }
                    // calque modifie en route (mobis ajoutes / retires) : on recalcule de temps en temps
                    if (generation == faite && calque != null) break;
                }
                id = calque; x = dx; y = dy; q = quarts; g = generation;
            }
            try {
                List<GroupeFantomes.Fantome> voulu = id == null ? List.of() : voulu(id, x, y, q);
                GroupeFantomes.Diff d = GroupeFantomes.diff(GroupeFantomes.affiches, voulu);
                if (d.total() > 0) {
                    int ko = GroupeFantomes.appliquer(d, () -> { synchronized (M) { return generation == g; } });
                    if (ko > 0) System.err.println("[Atelier] calques : apercu, " + ko + " envoi(s) refuse(s).");
                    Groupes.prevenir();
                }
            } catch (Throwable t) {
                System.err.println("[Atelier] calques : apercu : " + t);
            }
            faite = g;
        }
    }

    /** Les fantomes du calque a (dx, dy). Sols hors du plan : pas de fantome. */
    static List<GroupeFantomes.Fantome> voulu(String id, int x, int y) { return voulu(id, x, y, 0); }

    /** Idem, le bloc tourne de q quarts de tour (fantomes tournes aussi). */
    static List<GroupeFantomes.Fantome> voulu(String id, int x, int y, int q) {
        List<Set<Integer>> ids = Groupes.mobis(id);
        List<GroupeFantomes.Fantome> r = new ArrayList<>();
        List<GroupeCalcul.Element> els = GroupeActions.elements(ids.get(0), ids.get(1));
        for (GroupeCalcul.Cible c : GroupeCalcul.transformer(els, q, x, y, Salle::hauteurSol)) {
            if (c.horsPlan) continue;
            int f = GroupeFantomes.idPour(c.e.id, c.e.mural);
            r.add(new GroupeFantomes.Fantome(f, c.e.id, c.e.mural, c.x, c.y, c.z, c.position, q == 0 ? -1 : c.rot));
        }
        return r;
    }
}

package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HInventoryItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Remplir une zone avec un mobi (Actions des calques) : on choisit la zone
 * (deux cases dans le jeu), puis on pose UN exemplaire du mobi depuis
 * l'inventaire. Cette pose est retenue (pas envoyee) : elle donne le mobi,
 * sa taille (furnidata, tournee selon la rotation) et sa rotation. La zone
 * est alors couverte en grille ; si la taille ne tombe pas juste, la derniere
 * rangee / colonne est recalee contre le bord et chevauche la precedente
 * (comme les dalles magiques), donc la zone est remplie jusqu'au bout.
 * Pose mobi par mobi (PoseDirecte : inventaire puis BC, altitude au sol).
 */
final class RemplirZone {

    private RemplirZone() { }

    private enum Etape { RIEN, ZONE, MOBI, POSE }

    private static volatile Etape etape = Etape.RIEN;
    private static volatile boolean deuxiemeDit = false, branche = false, stop = false;

    static boolean enCours() { return etape != Etape.RIEN; }

    /** Bouton « Remplir une zone ». Un second clic pendant le choix annule. */
    static void lancer() {
        if (etape == Etape.POSE) { stop = true; InfoJeu.consigne("Remplissage arrêté."); return; }
        if (etape != Etape.RIEN) { annuler(); return; }
        brancher();
        stop = false;
        deuxiemeDit = false;
        etape = Etape.ZONE;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    static void annuler() {
        if (etape == Etape.RIEN) return;
        etape = Etape.RIEN;
        InfoJeu.consigne("Remplissage annulé.");
    }

    private static synchronized void brancher() {
        if (branche) return;
        Zone.ecouter(RemplirZone::suivreZone);
        GPresets gp = Salle.gp();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOSERVER, "PlaceObject", RemplirZone::surPose);
            branche = true;
        } catch (Throwable t) {
            System.err.println("[Atelier] remplir une zone : écoute indisponible : " + t);
        }
    }

    private static void suivreZone() {
        if (etape != Etape.ZONE) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxiemeDit) {
                deuxiemeDit = true;
                InfoJeu.consigne("Choisis le deuxième point de la zone.");
            }
            return;
        }
        if (!Zone.definie()) { annuler(); return; }
        etape = Etape.MOBI;
        InfoJeu.consigne("Zone de " + Zone.largeur() + " × " + Zone.longueur()
                + " : pose maintenant le mobi depuis ton inventaire, il remplira la zone.");
    }

    /** PlaceObject « -id x y rot » (sol) : retenu, il donne le mobi et la rotation. */
    private static void surPose(HMessage m) {
        if (etape != Etape.MOBI) return;
        try {
            HPacket p = new HPacket(m.getPacket());
            p.resetReadIndex();
            String s = p.readString();
            if (s == null || s.indexOf(':') >= 0) return;            // mural : on laisse passer
            String[] t = s.trim().split("\\s+");
            if (t.length != 4) return;
            long id = Math.abs(Long.parseLong(t[0]));
            int rot = Integer.parseInt(t[3]);
            m.setBlocked(true);
            etape = Etape.POSE;
            Salle.tache("remplir-zone", () -> {
                try { remplir(id, rot); }
                finally { etape = Etape.RIEN; }
            });
        } catch (Throwable ignored) { }
    }

    private static void remplir(long invId, int rot) {
        GPresets gp = Salle.gp();
        if (gp == null || !Zone.definie()) return;
        HInventoryItem modele = null;
        try {
            for (HInventoryItem it : gp.getInventory().getInventoryItems())
                if (it != null && Math.abs((long) it.getId()) == invId) { modele = it; break; }
        } catch (Throwable ignored) { }
        if (modele == null) { Journal.erreur("Mobi introuvable dans l'inventaire : remplissage impossible."); return; }
        String classe = Salle.classe(modele.getTypeId(), false);
        furnidata.details.FloorItemDetails d = classe == null ? null : Salle.details(classe);
        if (d == null) { Journal.erreur("Taille du mobi inconnue (furnidata) : remplissage impossible."); return; }
        boolean tourne = rot == 2 || rot == 6;
        int a = Math.max(1, tourne ? d.yDim : d.xDim), b = Math.max(1, tourne ? d.xDim : d.yDim);
        int zx = Zone.minX(), zy = Zone.minY(), w = Zone.largeur(), h = Zone.longueur();
        if (a > w || b > h) {
            Journal.erreur("Le mobi (" + a + " × " + b + ") est plus grand que la zone (" + w + " × " + h + ").");
            return;
        }
        List<PoseDirecte.Sol> sols = new ArrayList<>();
        int horsPlan = 0;
        for (int y : departs(zy, h, b)) {
            for (int x : departs(zx, w, a)) {
                int z = hauteur(x, y, a, b);
                if (z < 0) { horsPlan++; continue; }
                sols.add(new PoseDirecte.Sol(classe, x, y, z, rot));
            }
        }
        if (sols.isEmpty()) { Journal.erreur("Aucune place pour ce mobi dans la zone."); return; }
        InfoJeu.consigne("Remplissage : " + sols.size() + " mobis à poser (" + a + " × " + b + " cases)…");
        PoseDirecte.Resultat r = PoseDirecte.poser(sols, List.of(), Generateur.Source.INVENTAIRE_PUIS_BC,
                InfoJeu::dire, () -> stop, (fait, total) -> { });
        String fin = "Zone remplie : " + r.sols.size() + " mobis posés";
        if (r.manquants > 0) fin += ", " + r.manquants + " manquants (ni dans l'inventaire ni au BC)";
        if (horsPlan > 0) fin += ", " + horsPlan + " places hors du sol";
        if (r.hauteursFausses > 0) fin += ", " + r.hauteursFausses + " hauteurs à reprendre";
        fin += ".";
        if (r.sols.isEmpty()) Journal.erreur(fin); else Journal.succes(fin);
    }

    /**
     * Debuts des mobis sur une longueur : 0, n, 2n... et, si ca ne tombe pas
     * juste, un dernier cale contre le bord (il chevauche le precedent).
     * Logique pure, testee par l'exemple : longueur 3, mobi 2 -> 0, 1.
     */
    static List<Integer> departs(int debut, int longueur, int n) {
        List<Integer> r = new ArrayList<>();
        int k = 0;
        for (; k + n <= longueur; k += n) r.add(debut + k);
        if (k < longueur && longueur >= n) r.add(debut + longueur - n);
        return r;
    }

    /** Hauteur du sol sous le mobi (la plus haute de ses cases), -1 si une case est hors plan. */
    private static int hauteur(int x, int y, int a, int b) {
        int max = -1;
        for (int i = 0; i < a; i++) for (int j = 0; j < b; j++) {
            int z = Salle.hauteurSol(x + i, y + j);
            if (z < 0) return -1;
            max = Math.max(max, z);
        }
        return max;
    }
}

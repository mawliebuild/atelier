package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

/**
 * L'empreinte dans le jeu (client modifie) : un rectangle noir peu opaque qui
 * suit la souris pendant qu'on attend un clic pour placer quelque chose de
 * taille connue, ou entre les deux coins d'une zone.
 *
 *   « atelier:empreinte=l;p;RRGGBB;op[;h] »          l x p cases, la case survolee en coin (x, y min)
 *   « atelier:empreinte=coin;x;y;RRGGBB;op[;h] »     du coin (x, y) jusqu'a la case survolee
 *   « atelier:empreinte=0 »                           efface
 *
 * Rien n'est envoye si le client installe ne connait pas ces messages.
 * effacer() est toujours sur (sans effet si rien n'est affiche) ; l'empreinte
 * s'efface aussi d'elle-meme en changeant de salle.
 */
final class Empreinte {

    private Empreinte() { }

    static final String COULEUR = "000000";
    static final int OPACITE = 35;

    /** Salle ou l'empreinte est affichee, -1 : rien d'affiche. */
    private static volatile int affichee = -1;
    private static volatile boolean surveille = false;

    // ------------------------------------------------------------ messages (logique pure)

    static String messageTaille(int l, int p, int h) {
        return "atelier:empreinte=" + Math.max(1, l) + ";" + Math.max(1, p) + ";" + COULEUR + ";" + OPACITE + suffixe(h);
    }

    static String messageCoin(int x, int y, int h) {
        return "atelier:empreinte=coin;" + x + ";" + y + ";" + COULEUR + ";" + OPACITE + suffixe(h);
    }

    static final String EFFACE = "atelier:empreinte=0";

    private static String suffixe(int h) { return h > 0 ? ";" + h : ""; }

    // ------------------------------------------------------------ envoi

    /** Rectangle de l cases en x sur p cases en y, qui suit la souris. */
    static void montrer(int l, int p) {
        if (!sait()) return;
        int salle = Salle.salleId();
        if (salle == -1) return;
        if (envoyer(messageTaille(l, p, hauteurCourante()))) marquer(salle);
    }

    /** Du coin (x, y) de la salle jusqu'a la case survolee. */
    static void coin(int x, int y) {
        if (!sait()) return;
        int salle = Salle.salleId();
        if (salle == -1) return;
        int h = Salle.hauteurSol(x, y);
        if (h < 0) h = hauteurCourante();
        if (envoyer(messageCoin(x, y, h))) marquer(salle);
    }

    /** Efface l'empreinte ; sans effet si rien n'est affiche. */
    static synchronized void effacer() {
        if (affichee == -1) return;
        affichee = -1;
        if (Salle.salleId() != -1) envoyer(EFFACE);
    }

    private static synchronized void marquer(int salle) {
        affichee = salle;
        if (surveille) return;
        surveille = true;
        // changement de salle : l'empreinte n'a plus de sens, on l'efface
        Salle.tache("empreinte-salle", () -> {
            while (true) {
                Salle.sommeil(500);
                int a;
                synchronized (Empreinte.class) {
                    a = affichee;
                    if (a == -1) { surveille = false; return; }
                }
                if (Salle.salleId() != a) effacer();
            }
        });
    }

    private static boolean envoyer(String m) {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        try {
            gp.sendToClient(new HPacket("Whisper", HMessage.Direction.TOCLIENT, -1, m, 0, 0, 0, -1));
            return true;
        } catch (Throwable t) {
            Journal.debug("empreinte : " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ hauteur du plan

    private static volatile int hauteurSalle = -1, hauteurValeur = 0;

    /** Hauteur de sol la plus frequente de la salle (calculee une fois par salle). */
    static int hauteurCourante() {
        int id = Salle.salleId();
        if (id == hauteurSalle) return hauteurValeur;
        int[] n = new int[64];
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++) {
                int h = Salle.hauteurSol(x, y);
                if (h >= 0 && h < n.length) n[h]++;
            }
        int best = 0;
        for (int h = 1; h < n.length; h++) if (n[h] > n[best]) best = h;
        hauteurValeur = best;
        hauteurSalle = id;
        return best;
    }

    // ------------------------------------------------------------ taille d'un ensemble de mobis

    /** Boite englobante de mobis de sol (emprise furnidata et rotation comprises). */
    static final class Boite {
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        Boite sol(String classe, int x, int y, int rot) {
            Furnidata.Mobi d = Salle.details(classe);
            int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
            rot &= 7;
            if (rot == 2 || rot == 6) { int t = lx; lx = ly; ly = t; }
            return cases(x, y, lx, ly);
        }

        Boite cases(int x, int y, int lx, int ly) {
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + lx - 1); maxY = Math.max(maxY, y + ly - 1);
            return this;
        }

        boolean vide() { return minX == Integer.MAX_VALUE; }
        int largeur() { return vide() ? 1 : maxX - minX + 1; }
        int profondeur() { return vide() ? 1 : maxY - minY + 1; }
    }

    // ------------------------------------------------------------ detection

    private static volatile java.lang.reflect.Method saitM;
    private static volatile boolean saitCherche = false;

    /** ClientModifie.saitEmpreinte(), cherchee par reflexion : false si elle n'existe pas. */
    static boolean sait() {
        if (!saitCherche) {
            try { saitM = ClientModifie.class.getDeclaredMethod("saitEmpreinte"); saitM.setAccessible(true); }
            catch (Throwable t) { saitM = null; }
            saitCherche = true;
        }
        java.lang.reflect.Method m = saitM;
        if (m == null) return false;
        try { return Boolean.TRUE.equals(m.invoke(null)); } catch (Throwable t) { return false; }
    }
}

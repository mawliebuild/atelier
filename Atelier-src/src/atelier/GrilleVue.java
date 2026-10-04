package atelier;

/**
 * « Voir grille » (Actions des calques) : des traits autour de chaque case du
 * sol, dessines PAR LE JEU au-dessus des mobis (client modifie, option
 * « grille » de Atelier-swf/construire.py). Chez toi seulement.
 *
 * L'Atelier envoie au client un chuchotement « atelier:grille=<plan> » (le
 * plan du sol, lignes separees par « / ») ; le client ne l'affiche pas, il
 * dessine la grille et la recale tout seul quand la vue bouge ou zoome.
 * « atelier:grille= » seul la cache. Le plan est renvoye quand il change, et
 * de temps en temps (en rentrant dans une salle, le jeu refait son canevas).
 */
final class GrilleVue {

    private GrilleVue() { }

    private static final long RAPPEL_MS = 5000;

    private static volatile boolean voulue = false;
    private static volatile boolean demarre = false;
    private static volatile String envoye = null;
    private static volatile long envoyeA = 0;

    static boolean voulue() { return voulue; }

    /**
     * Plan impose par le mode Cases : « plan|marques » (voir ModeCases), envoye
     * a la place du plan de la salle, meme si la grille n'est pas voulue.
     * null = plan de la salle (et grille seulement si voulue).
     */
    private static volatile String impose = null;

    static void imposer(String planEtMarques) {
        impose = planEtMarques;
        demarrer();
        if (planEtMarques == null) { if (!voulue) envoyer(""); else { envoye = null; envoyerSiBesoin(); } }
        else { envoye = null; envoyerSiBesoin(); }
    }

    private static final java.util.List<Runnable> ecouteurs = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Prevenu (fil JavaFX) quand la grille est montree ou cachee. */
    static void ecouter(Runnable r) { ecouteurs.add(r); }

    /** Montre ou cache la grille ; false (message dit) si le client ne sait pas la dessiner. */
    static boolean montrer(boolean oui) {
        if (oui && !ClientModifie.saitGrille()) {
            Journal.erreur("Grille impossible : le client du jeu ne sait pas la dessiner. Installe le client "
                    + "modifié (Paramètres › Inventaire), puis relance Habbo.");
            return false;
        }
        voulue = oui;
        javafx.application.Platform.runLater(() -> {
            for (Runnable r : ecouteurs) {
                try { r.run(); } catch (Throwable t) { System.err.println("[Atelier] grille : écouteur : " + t); }
            }
        });
        demarrer();
        if (!oui && impose == null) envoyer("");
        else { envoye = null; envoyerSiBesoin(); }
        return true;
    }

    private static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(1000);
                try { if (voulue || impose != null) envoyerSiBesoin(); } catch (Throwable ignored) { }
            }
        }, "atelier-grille-vue");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Pendant le choix d'une zone : les mobis du jeu laissent passer les clics
     * (client modifie), pour viser la case du sol derriere un mobi haut.
     * Hors fil JavaFX de preference (lecture du client la premiere fois).
     */
    static void clicsAuSol(boolean oui) {
        if (!ClientModifie.saitZone()) return;
        extension.GPresets gp = Salle.gp();
        if (gp == null || !Salle.dansUneSalle()) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:zone=" + (oui ? "1" : "0"), 0, 0, 0, -1));
    }

    private static void envoyerSiBesoin() {
        String plan = impose != null && Salle.dansUneSalle() ? impose : plan();
        if (plan == null) { envoye = null; return; }       // hors salle : on renverra en entrant
        if (!Salle.installeeDepuis(3000)) return;          // le jeu charge encore la salle
        if (plan.equals(envoye) && System.currentTimeMillis() - envoyeA < RAPPEL_MS) return;
        envoyer(plan);
    }

    private static void envoyer(String plan) {
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:grille=" + plan, 0, 0, 0, -1));
        envoye = plan; envoyeA = System.currentTimeMillis();
    }

    /** Le plan brut de la salle, lignes jointes par « / » ; null hors salle. */
    private static String plan() {
        try {
            game.FloorState s = Salle.etat();
            if (s == null) return null;
            String brut = s.getRawFloorplan();
            if (brut == null || brut.isBlank()) return null;
            return brut.trim().replace("\r\n", "/").replace('\r', '/').replace('\n', '/').toLowerCase();
        } catch (Throwable t) { return null; }
    }
}

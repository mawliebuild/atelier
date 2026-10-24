package atelier;

/**
 * Mode Construction : masquer les encadres du jeu autour de l'appart (colonne
 * du haut a droite : porte-monnaie, groupe, « Promouvoir », quetes, offres ;
 * bulles de notification). Le client modifie (« atelier:interface=0 / =1 »,
 * option « interface » de Atelier-swf/construire.py) les garde caches meme
 * quand le jeu les remontre (changement d'appart).
 *
 * Le choix (bouton oeil de la petite barre du mode Construction) est retenu ;
 * il ne s'applique qu'en mode Construction. En sortant du mode, l'interface
 * revient. Rien n'est dit dans le jeu : l'effet se voit.
 */
final class InterfaceJeu {

    private InterfaceJeu() { }

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String CLE = "interface.jeu.masquee";

    private static volatile boolean masquee = PREFS.getBoolean(CLE, false);
    private static volatile boolean construction = false;
    private static volatile boolean disponible = false;
    /** Ce qui a ete envoye : « <salle>:0 » (masquee) ou null (visible, l'etat du jeu d'origine). */
    private static volatile String envoye = null;
    private static boolean demarre = false;

    /** Le choix de l'utilisatrice (retenu), qu'on soit en mode Construction ou non. */
    static boolean masquee() { return masquee; }

    /** Le client installe connait-il la commande ? (lu en arriere-plan) */
    static boolean disponible() { return disponible; }

    static void basculer() {
        masquee = !masquee;
        PREFS.putBoolean(CLE, masquee);
        appliquer();
    }

    /** Entree / sortie du mode Construction. */
    static void construction(boolean on) {
        construction = on;
        demarrer();
        appliquer();
    }

    private static void appliquer() {
        Salle.tache("interface-jeu", InterfaceJeu::verifier);
    }

    private static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(1500);
                try { verifier(); } catch (Throwable ignored) { }
            }
        }, "atelier-interface-jeu");
        t.setDaemon(true);
        t.start();
        // l'Atelier ferme pendant que l'interface est cachee : on la remontre si on peut
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (envoye != null) envoyer(false); } catch (Throwable ignored) { }
        }, "atelier-interface-jeu-fin"));
    }

    private static synchronized void verifier() {
        disponible = ClientModifie.saitInterface();
        if (!disponible) return;
        // hors d'un appart, le jeu ne recoit pas le chuchotement : on attend d'y etre
        if (!Salle.installeeDepuis(3000)) return;
        boolean voulu = construction && masquee;
        if (voulu) {
            String cle = Salle.salleId() + ":0";
            if (cle.equals(envoye)) return;
            if (envoyer(true)) { envoye = cle; Journal.debug("interface du jeu masquée."); }
        } else if (envoye != null) {
            if (envoyer(false)) { envoye = null; Journal.debug("interface du jeu remontrée."); }
        }
    }

    private static boolean envoyer(boolean masquer) {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:interface=" + (masquer ? "0" : "1"), 0, 0, 0, -1));
        return true;
    }
}

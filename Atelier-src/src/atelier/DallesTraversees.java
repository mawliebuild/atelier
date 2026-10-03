package atelier;

/**
 * Les mobis poses sur une dalle magique : dans le jeu, la dalle prend le clic
 * et on ne peut pas changer leur etat. Avec le client modifie (option
 * « grille » de Atelier-swf/construire.py), l'Atelier demande au jeu de
 * laisser passer les clics a travers ses dalles magiques (« atelier:dalles=1 ») :
 * un double-clic tombe alors sur le mobi pose dessus. La demande est refaite
 * quand les dalles de la salle changent (le jeu les retrouve lui-meme), et de
 * temps en temps (en rentrant dans une salle, le jeu refait tout).
 * Reglage dans Hauteur fixe ; actif par defaut.
 */
final class DallesTraversees {

    private DallesTraversees() { }

    private static final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("atelier");
    private static volatile boolean actif = prefs.getBoolean("dalles.traversees", true);
    private static volatile String envoye = null;
    private static volatile long envoyeA = 0;
    private static volatile boolean demarre = false;

    static boolean actif() { return actif; }

    static void actif(boolean oui) {
        actif = oui;
        prefs.putBoolean("dalles.traversees", oui);
        envoye = null;
    }

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(1500);
                try { verifier(); } catch (Throwable ignored) { }
            }
        }, "atelier-dalles-traversees");
        t.setDaemon(true);
        t.start();
    }

    private static void verifier() {
        if (!Salle.installeeDepuis(3000)) { envoye = null; return; }
        if (!ClientModifie.saitDalles()) return;
        java.util.List<Integer> ids = new java.util.ArrayList<>(OutilHauteur.toutesDalles());
        java.util.Collections.sort(ids);
        String cle = Salle.salleId() + ":" + (actif ? ids.toString() : "non");
        long t = System.currentTimeMillis();
        boolean rappel = actif && !ids.isEmpty() && t - envoyeA > 20_000;
        if (cle.equals(envoye) && !rappel) return;
        if (!actif && envoye == null && ids.isEmpty()) { envoye = cle; return; }
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:dalles=" + (actif && !ids.isEmpty() ? "1" : "0"), 0, 0, 0, -1));
        envoye = cle; envoyeA = t;
    }
}

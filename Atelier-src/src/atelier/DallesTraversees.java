package atelier;

/**
 * Les mobis poses sur une dalle magique : dans le jeu, la dalle prend le clic
 * et on ne peut pas changer leur etat. Avec le client modifie (option
 * « grille » de Atelier-swf/construire.py), l'Atelier demande au jeu de
 * laisser passer les clics a travers ses dalles magiques (« atelier:dalles=1 ») :
 * un double-clic tombe alors sur le mobi pose dessus. La demande est refaite
 * quand les dalles de la salle changent (le jeu les retrouve lui-meme), et de
 * temps en temps (en rentrant dans une salle, le jeu refait tout).
 * Toujours actif (plus de reglage).
 */
final class DallesTraversees {

    private DallesTraversees() { }

    /** Toujours actif : changer l'etat d'un mobi pose sur une dalle se fait tout seul. */
    private static volatile boolean actif = true;
    private static volatile String envoye = null;
    private static volatile long envoyeA = 0;
    private static volatile boolean demarre = false;

    static boolean actif() { return actif; }

    /** Garde pour compatibilite : le reglage n'existe plus, rien ne coupe le passage des clics. */
    static void actif(boolean oui) { envoye = null; }

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

    private static volatile boolean ecoute = false;

    /**
     * Diagnostic du double-clic : chaque UseFurniture envoye par le jeu est note
     * (mobi, dalle dessous ou non) avec son etat avant / 1 s apres. Intercepteur :
     * lecture a index fixe, le reste sur un autre fil.
     */
    private static void ecouter(Moteur gp) {
        if (ecoute) return;
        ecoute = true;
        gp.intercept(gearth.protocol.HMessage.Direction.TOSERVER, "UseFurniture", m -> {
            try {
                int id = m.getPacket().readInteger(6);
                Salle.tache("diag-double-clic", () -> diagnostic(id));
            } catch (Throwable ignored) { }
        });
    }

    private static void diagnostic(int id) {
        gearth.extensions.parsers.HFloorItem it = Salle.sol(id);
        if (it == null) { Journal.debug("double-clic : UseFurniture " + id + " (mobi inconnu de l'Atelier)."); return; }
        String cl = Salle.classe(it.getTypeId(), false);
        String avant = Generateur.etatDe(it);
        boolean dalle = false;
        for (int d : OutilHauteur.toutesDalles()) {
            gearth.extensions.parsers.HFloorItem di = Salle.sol(d);
            if (di != null && d != id && di.getTile().getX() <= it.getTile().getX() && di.getTile().getY() <= it.getTile().getY()
                    && di.getTile().getX() + 8 > it.getTile().getX() && di.getTile().getY() + 8 > it.getTile().getY()) { dalle = true; break; }
        }
        Salle.sommeil(1000);
        gearth.extensions.parsers.HFloorItem apres = Salle.sol(id);
        String ap = apres == null ? "?" : Generateur.etatDe(apres);
        Journal.debug("double-clic : " + cl + " " + id + (dalle ? " (dalle proche dessous)" : "")
                + ", état " + avant + " -> " + ap + (avant.equals(ap) ? " : INCHANGÉ" : ""));
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
        Moteur gp = Salle.gp();
        if (gp == null) return;
        ecouter(gp);
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:dalles=" + (actif && !ids.isEmpty() ? "1" : "0"), 0, 0, 0, -1));
        envoye = cle; envoyeA = t;
    }
}

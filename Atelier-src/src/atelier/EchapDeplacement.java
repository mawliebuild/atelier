package atelier;

/**
 * Echap dans le jeu annule un deplacement commence par Option + clic : le
 * mobi est relache et reste a sa place (sinon, cliquer ailleurs le posait la,
 * parfois en hauteur sur d'autres mobis).
 *
 * Echap n'est PAS un raccourci global (il resterait pris partout) : on lit
 * seulement l'etat de la touche (ToucheOption.echap), et quand Habbo est
 * devant, on demande au client modifie de tout annuler (« atelier:annuler »,
 * cancelRoomObjectInsert : le jeu remet le mobi a sa place).
 */
final class EchapDeplacement {

    private EchapDeplacement() { }

    private static volatile boolean demarre = false;

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            boolean avant = false;
            while (true) {
                Salle.sommeil(40);
                try {
                    boolean bas = ToucheOption.echap();
                    if (bas && !avant) annuler();
                    avant = bas;
                } catch (Throwable ignored) { }
            }
        }, "atelier-echap-deplacement");
        t.setDaemon(true);
        t.start();
    }

    private static void annuler() {
        if (!Salle.dansUneSalle()) return;
        if (!RaccourcisGlobaux.estHabbo(RaccourcisGlobaux.Devant.app())) return;
        if (!ClientModifie.saitAnnuler()) return;
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:annuler", 0, 0, 0, -1));
    }
}

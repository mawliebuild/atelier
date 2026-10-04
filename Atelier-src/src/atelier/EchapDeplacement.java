package atelier;

/**
 * Echap dans le jeu annule un deplacement commence par Option + clic (Alt + clic
 * sous Windows) : le
 * mobi est relache et reste a sa place (sinon, cliquer ailleurs le posait la,
 * parfois en hauteur sur d'autres mobis).
 *
 * Echap n'est PAS un raccourci global (il resterait pris partout) : on lit
 * seulement l'etat de la touche (ToucheOption.echap), et quand Habbo est
 * devant, on demande au client modifie de tout annuler (« atelier:annuler »,
 * cancelRoomObjectInsert : le jeu remet le mobi a sa place).
 *
 * Meme code sous Mac et Windows : ToucheOption.echap lit CGEventSourceKeyState
 * (Mac) ou GetAsyncKeyState(VK_ESCAPE) (Windows), et Devant.app donne l'appli
 * au premier plan sur les deux.
 *
 * Mode Floor : Echap y est un vrai raccourci (RaccourcisGlobaux, enregistre
 * seulement pendant ce mode) qui sort du mode. Pour qu'un seul appui ne fasse
 * pas deux choses dans le desordre, la lecture ci-dessous se tait alors, et le
 * raccourci du mode Floor appelle lui-meme lacherMobi() d'abord (sans effet si
 * aucun mobi n'est tenu ; en mode Floor le jeu avale les clics, donc un
 * deplacement Option + clic n'y commence pas), puis fait l'etape du Floor.
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
        if (ModeCases.actif()) return;          // mode Floor : c'est son raccourci Echap qui s'en charge
        if (!Salle.dansUneSalle()) return;
        if (!RaccourcisGlobaux.estHabbo(RaccourcisGlobaux.Devant.app())) return;
        lacherMobi();
    }

    /** Demande au client modifie de relacher un mobi tenu (Option + clic). Sans effet sinon. */
    static void lacherMobi() {
        if (!Salle.dansUneSalle()) return;
        if (!ClientModifie.saitAnnuler()) return;
        Moteur gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:annuler", 0, 0, 0, -1));
    }
}

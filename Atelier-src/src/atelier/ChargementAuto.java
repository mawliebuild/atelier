package atelier;

import extension.GPresets;
import game.FloorState;

/**
 * Charge l'inventaire et le catalogue BC tout seul, une fois dans une salle.
 *
 * Aucune manipulation dans le jeu n'est necessaire : loadInventoryClick et
 * loadBCClick se contentent d'appeler requestInventory() et requestIndex(), qui
 * envoient les paquets RequestFurniInventory et GetCatalogIndex. Le serveur
 * repond de lui-meme — inutile d'ouvrir son sac ou le catalogue.
 *
 * Toujours actif, sans reglage. Deux garde-fous : une seule tentative a la fois,
 * et un delai avant de reessayer — le scrapage du catalogue parcourt toutes les
 * pages, on evite de le relancer en boucle.
 */
public final class ChargementAuto {

    private static final long DELAI_AVANT_NOUVEL_ESSAI = 25_000;   // ms

    private static volatile boolean demarre = false;
    private static long dernierEssaiInv = 0;
    private static long dernierEssaiBc  = 0;

    private ChargementAuto() { }

    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                try { verifier(); } catch (Throwable ignored) { }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-chargement-auto");
        t.setDaemon(true);
        t.start();
    }

    private static void verifier() {
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) return;

        FloorState s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;     // rien a faire hors d'une salle

        long maintenant = System.currentTimeMillis();

        String inv = etat(() -> String.valueOf(gp.getInventory().getState()));
        if (!"LOADED".equals(inv) && !"LOADING".equals(inv)
                && maintenant - dernierEssaiInv > DELAI_AVANT_NOUVEL_ESSAI) {
            dernierEssaiInv = maintenant;
            try {
                gp.getInventory().requestInventory();
                Journal.debug("inventaire demande automatiquement.");
            } catch (Throwable t) {
                System.err.println("[Atelier] demande d'inventaire impossible : " + t);
            }
        }

        String bc = etat(() -> String.valueOf(gp.getCatalog().getState()));
        boolean bcEnCours = "AWAITING_INDEX".equals(bc) || "COLLECTING_PAGES".equals(bc);
        if (!"COLLECTED".equals(bc) && !bcEnCours
                && maintenant - dernierEssaiBc > DELAI_AVANT_NOUVEL_ESSAI) {
            dernierEssaiBc = maintenant;
            try {
                gp.getCatalog().requestIndex();
                Journal.debug("catalogue BC demande automatiquement.");
            } catch (Throwable t) {
                System.err.println("[Atelier] demande de catalogue impossible : " + t);
            }
        }
    }

    private interface Sonde { String lire() throws Throwable; }

    private static String etat(Sonde s) {
        try { return s.lire(); } catch (Throwable t) { return "?"; }
    }
}

package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Charge l'inventaire et le catalogue BC tout seul, une fois dans une salle.
 *
 * Aucune manipulation dans le jeu n'est necessaire : Moteur.demanderInventaire
 * et Moteur.demanderCatalogue envoient RequestFurniInventory et
 * GetCatalogIndex. Le serveur repond de lui-meme — inutile d'ouvrir son sac ou
 * le catalogue ; ces reponses ne vont pas au jeu, qui ne les a pas demandees.
 *
 * Toujours actif, sans reglage. Garde-fous : une seule tentative a la fois,
 * un delai avant de reessayer — la lecture du catalogue parcourt toutes les
 * pages, on evite de la relancer en boucle — et pas de nouvelle demande
 * d'inventaire juste apres celle d'un autre outil de l'Atelier (deux reponses
 * de 25 000 mobis a la suite, dont une pouvait arriver au jeu sans qu'il l'ait
 * demandee, et le figeait).
 *
 * Pendant la lecture des pages du catalogue BC (des centaines de pages, une
 * toutes les 200 ms environ), les pages demandees par le jeu ne sont jamais
 * perdues :
 *   - une page BC demandee par le jeu est renvoyee au serveur tout de suite
 *     (sa reponse est une vraie page BC, sans danger pour la lecture) ;
 *   - une page du catalogue normal est gardee et redemandee des la fin de la
 *     lecture ;
 *   - les pages lues pour l'Atelier ne sont pas envoyees au jeu, qui ne les a
 *     pas demandees : le moteur les lit quand meme (un paquet bloque reste lu).
 */
public final class ChargementAuto {

    private static final long DELAI_AVANT_NOUVEL_ESSAI = 25_000;   // ms
    /** Apres une demande d'inventaire de l'Atelier, la reponse peut mettre ce temps a commencer. */
    private static final long REPONSE_INVENTAIRE_MS = 30_000;

    private static volatile boolean demarre = false;
    private static long dernierEssaiInv = 0;
    private static long dernierEssaiBc  = 0;
    /** Derniere demande d'inventaire faite par n'importe quel outil de l'Atelier. */
    private static volatile long inventaireDemandeLe = 0;

    private ChargementAuto() { }

    /** A appeler juste avant chaque requestInventory() de l'Atelier. */
    static void inventaireDemande() { inventaireDemandeLe = System.currentTimeMillis(); }

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
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return;
        brancherCatalogue(gp);

        EtatSalle s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;     // rien a faire hors d'une salle

        long maintenant = System.currentTimeMillis();

        String inv = etat(() -> String.valueOf(gp.getInventory().getState()));
        if (!"LOADED".equals(inv) && !"LOADING".equals(inv)
                && maintenant - dernierEssaiInv > DELAI_AVANT_NOUVEL_ESSAI
                && maintenant - inventaireDemandeLe > REPONSE_INVENTAIRE_MS) {
            dernierEssaiInv = maintenant;
            try {
                inventaireDemande();
                if (gp.demanderInventaire()) Journal.debug("inventaire demandé automatiquement.");
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
                indexDemande();
                gp.demanderCatalogue();
                Journal.debug("catalogue BC demandé automatiquement.");
            } catch (Throwable t) {
                System.err.println("[Atelier] demande de catalogue impossible : " + t);
            }
        }
    }

    private interface Sonde { String lire() throws Throwable; }

    private static String etat(Sonde s) {
        try { return s.lire(); } catch (Throwable t) { return "?"; }
    }

    // ------------------------------------------------- catalogue pendant la collecte BC

    private static volatile boolean catalogueBranche = false;
    /** Pages demandees par le jeu (id -> heure) : leur reponse doit lui parvenir. */
    private static final Map<Integer, Long> pagesDuJeu = new ConcurrentHashMap<>();
    /** Derniere page du catalogue normal demandee par le jeu pendant la collecte. */
    private static volatile HPacket pageEnAttente = null;
    private static volatile long pageEnAttenteLe = 0;
    private static volatile boolean surveillanceLancee = false;

    /** Derniere demande d'index faite par le jeu, et par l'Atelier (repertoire). */
    private static volatile long indexDuJeuLe = 0, indexAtelierLe = 0;

    /** A appeler juste avant que l'Atelier demande un index du catalogue. */
    static void indexDemande() { indexAtelierLe = System.currentTimeMillis(); }

    /**
     * Un index que le jeu n'a pas demande (reponse a l'Atelier ou a son moteur)
     * est bloque : le jeu n'a pas a reconstruire tout son catalogue pour rien.
     * Le moteur et le repertoire le lisent quand meme (un paquet bloque reste lu).
     */
    private static void indexRecu(Moteur gp, HMessage m) {
        long t = System.currentTimeMillis();
        if (t - indexDuJeuLe < 15_000) return;                       // le jeu l'attend
        boolean atelier = t - indexAtelierLe < 15_000;
        boolean moteur = false;
        try { moteur = "AWAITING_INDEX".equals(String.valueOf(gp.getCatalog().getState())); }
        catch (Throwable ignored) { }
        if (atelier || moteur) m.setBlocked(true);
    }

    private static boolean collecteBc(Moteur gp) {
        try { return "COLLECTING_PAGES".equals(String.valueOf(gp.getCatalog().getState())); }
        catch (Throwable t) { return false; }
    }

    private static synchronized void brancherCatalogue(Moteur gp) {
        if (catalogueBranche) return;
        try {
            // GetCatalogPage(int page, int offre, String catalogue). Hors collecte : rien.
            gp.intercept(HMessage.Direction.TOSERVER, "GetCatalogPage", m -> {
                try { pageDemandee(gp, m); } catch (Throwable ignored) { }
            });
            // CatalogPage(int page, String catalogue, ...). Hors collecte : rien.
            gp.intercept(HMessage.Direction.TOCLIENT, "CatalogPage", m -> {
                try { pageRecue(gp, m); } catch (Throwable ignored) { }
            });
            // Index du catalogue : celui que le jeu demande lui parvient ; ceux que
            // l'Atelier ou son moteur demandent (gros paquets) ne lui sont plus envoyes.
            gp.intercept(HMessage.Direction.TOSERVER, "GetCatalogIndex", m -> indexDuJeuLe = System.currentTimeMillis());
            gp.intercept(HMessage.Direction.TOCLIENT, "CatalogIndex", m -> {
                try { indexRecu(gp, m); } catch (Throwable ignored) { }
            });
            catalogueBranche = true;
            Journal.debug("catalogue : pages du jeu protégées pendant la collecte BC.");
        } catch (Throwable t) {
            Journal.debug("catalogue : écoute des pages indisponible (" + t + ")");
            catalogueBranche = true;          // pas de nouvel essai toutes les 3 s
        }
    }

    /**
     * Intercepteur (fil des paquets) : tests de taille et lecture sur place,
     * l'envoi part sur un autre fil. L'ordre des ecoutes n'est pas garanti :
     * le paquet est toujours bloque ici, et c'est une copie qui part.
     */
    private static void pageDemandee(Moteur gp, HMessage m) {
        if (!collecteBc(gp)) return;
        HPacket p = m.getPacket();
        int n = p.getBytesLength();
        if (n < 16 || n > 200) return;
        int page = p.readInteger(6);
        String type = p.readString(14);
        HPacket copie = new HPacket(p);
        m.setBlocked(true);
        pagesDuJeu.put(page, System.currentTimeMillis());
        if ("BUILDERS_CLUB".equals(type)) {
            Salle.tache("catalogue-page-bc", () -> {
                Salle.espacer();             // au milieu de la rafale des pages BC du moteur
                try { gp.sendToServer(copie); } catch (Throwable ignored) { }
            });
            return;
        }
        pageEnAttente = copie;
        pageEnAttenteLe = System.currentTimeMillis();
        surveillerFinDeCollecte(gp);
    }

    /** Les pages collectees par le moteur ne vont plus au jeu (qui ne les a pas demandees). */
    private static void pageRecue(Moteur gp, HMessage m) {
        if (!collecteBc(gp)) return;
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 12) return;
        int page = p.readInteger(6);
        Long t = pagesDuJeu.get(page);
        if (t != null && System.currentTimeMillis() - t < 15_000) { pagesDuJeu.remove(page); return; }
        if (!"BUILDERS_CLUB".equals(p.readString(10))) return;
        m.setBlocked(true);
    }

    /** Redemande la page normale gardee des que la collecte BC est finie (au plus 3 min apres). */
    private static synchronized void surveillerFinDeCollecte(Moteur gp) {
        if (surveillanceLancee) return;
        surveillanceLancee = true;
        Salle.tache("catalogue-attente", () -> {
            try {
                InfoJeu.dire("Le catalogue BC se charge : ta page s'ouvrira dès que c'est fini.");
                while (true) {
                    Salle.sommeil(250);
                    HPacket p = pageEnAttente;
                    if (p == null) return;
                    if (System.currentTimeMillis() - pageEnAttenteLe > 180_000) { pageEnAttente = null; return; }
                    if (collecteBc(gp)) continue;
                    pageEnAttente = null;
                    Salle.sommeil(200);          // laisser le moteur finir la collecte
                    Salle.espacer();
                    try { gp.sendToServer(p); } catch (Throwable ignored) { }
                    Journal.debug("catalogue : page demandée pendant la collecte BC renvoyée.");
                    return;
                }
            } finally {
                surveillanceLancee = false;
                if (pageEnAttente != null) surveillerFinDeCollecte(gp);   // arrivee entre-temps
            }
        });
    }
}

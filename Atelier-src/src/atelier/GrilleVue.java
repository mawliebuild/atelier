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
        if (planEtMarques == null) { if (!voulue && !grilleAttente) envoyer(""); else { envoye = null; envoyerSiBesoin(); } }
        else { envoye = null; envoyerSiBesoin(); }
    }

    private static final java.util.List<Runnable> ecouteurs = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Prevenu (fil JavaFX) quand la grille est montree ou cachee. */
    static void ecouter(Runnable r) { ecouteurs.add(r); }

    /** Montre ou cache la grille ; false (message dit) si le client ne sait pas la dessiner. */
    static boolean montrer(boolean oui) {
        if (oui && !ClientModifie.saitGrille()) {
            Journal.erreur("Grille impossible : le client du jeu ne sait pas la dessiner. Installe le client "
                    + "modifié (Réglages › Connexion › Jeu modifié), puis relance Habbo.");
            return false;
        }
        voulue = oui;
        javafx.application.Platform.runLater(() -> {
            for (Runnable r : ecouteurs) {
                try { r.run(); } catch (Throwable t) { System.err.println("[Atelier] grille : écouteur : " + t); }
            }
        });
        demarrer();
        if (!oui && impose == null && !grilleAttente) envoyer("");
        else { envoye = null; envoyerSiBesoin(); }
        return true;
    }

    private static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(1000);
                try { if (voulue || impose != null || grilleAttente) envoyerSiBesoin(); } catch (Throwable ignored) { }
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
    private static volatile boolean auSol = false, gardeLancee = false;

    /**
     * Garde-fou : tant que le jeu laisse passer les clics au sol, les mobis et
     * les ANIMAUX ne se cliquent plus. Des que plus rien ne le demande (choix
     * de zone fini ou abandonne, choix de cases wired coupe), on le coupe.
     */
    private static synchronized void garde() {
        if (gardeLancee) return;
        gardeLancee = true;
        Salle.tache("clics-au-sol-garde", () -> {
            while (true) {
                Salle.sommeil(1500);
                try {
                    if (auSol && attentes.isEmpty() && !Zone.choixEnCours() && !OngletCollageWired.choixCasesActif) {
                        Journal.debug("clics au sol : plus rien ne les demande, coupés.");
                        clicsAuSol(false);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    static void clicsAuSol(boolean oui) {
        if (!oui && !attentes.isEmpty()) return;          // une attente de clics les veut encore
        auSol = oui;
        if (oui) garde();
        if (!ClientModifie.saitZone()) return;
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.dansUneSalle()) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:zone=" + (oui ? "1" : "0"), 0, 0, 0, -1));
    }

    // ------------------------------------------------------------ attente de clics

    /**
     * Les attentes de clics dans le jeu en cours (zone en deux coins, mesure,
     * case de depart d'une pose...), chacune sous sa cle.
     */
    private static final java.util.Set<String> attentes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** La grille est montree pour une attente (pas voulue par l'utilisatrice). */
    private static volatile boolean grilleAttente = false;
    /** Les clics au sol sont allumes pour une attente. */
    private static boolean auSolAttente = false;
    /** Un seul fil applique les changements, dans l'ordre des appels. */
    private static final java.util.concurrent.ExecutorService ATTENTE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-attente-clics");
                t.setDaemon(true);
                return t;
            });

    /**
     * LE helper de toutes les attentes de clics de cases dans le jeu : tant
     * qu'au moins une attente est ouverte, la grille du jeu s'affiche (client
     * modifie) et un clic compte pour la CASE meme s'il y a un mobi devant
     * (« atelier:zone=1 »). A la fin (clics faits, annules ou delai passe),
     * tout revient comme avant : une grille deja affichee par l'utilisatrice
     * reste, le mode Cases du collage de wired garde ses clics au sol.
     * Appel sans danger depuis n'importe quel fil ; fermer une cle deja
     * fermee ne fait rien.
     */
    static void attente(String cle, boolean oui) {
        if (cle == null) return;
        boolean change = oui ? attentes.add(cle) : attentes.remove(cle);
        if (change) ATTENTE.submit(GrilleVue::appliquerAttente);
    }

    static boolean attenteEnCours() { return !attentes.isEmpty(); }

    private static void appliquerAttente() {
        try {
            boolean veut = !attentes.isEmpty();
            if (veut != auSolAttente) {
                auSolAttente = veut;
                if (veut) clicsAuSol(true);
                else if (!OngletCollageWired.choixCasesActif) clicsAuSol(false);
            }
            if (veut && !grilleAttente && !voulue && impose == null && ClientModifie.saitGrille()) {
                grilleAttente = true;
                demarrer();
                envoye = null;
                envoyerSiBesoin();
            } else if (!veut && grilleAttente) {
                grilleAttente = false;
                if (!voulue && impose == null) envoyer("");
            }
        } catch (Throwable t) {
            Journal.debug("attente de clics : " + t);
        }
    }

    private static void envoyerSiBesoin() {
        String plan = impose != null && Salle.dansUneSalle() ? impose : plan();
        if (plan == null) { envoye = null; return; }       // hors salle : on renverra en entrant
        if (!Salle.installeeDepuis(3000)) return;          // le jeu charge encore la salle
        if (plan.equals(envoye) && System.currentTimeMillis() - envoyeA < RAPPEL_MS) return;
        envoyer(plan);
    }

    private static void envoyer(String plan) {
        Moteur gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:grille=" + plan, 0, 0, 0, -1));
        envoye = plan; envoyeA = System.currentTimeMillis();
    }

    /** Le plan brut de la salle, lignes jointes par « / » ; null hors salle. */
    private static String plan() {
        try {
            EtatSalle s = Salle.etat();
            if (s == null) return null;
            String brut = s.getRawFloorplan();
            if (brut == null || brut.isBlank()) return null;
            return brut.trim().replace("\r\n", "/").replace('\r', '/').replace('\n', '/').toLowerCase();
        } catch (Throwable t) { return null; }
    }
}

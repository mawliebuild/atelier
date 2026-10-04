package atelier;

import extension.GPresets;

/**
 * Un message dans le chat du jeu (sans prefixe), comme si ton avatar chuchotait — chez toi
 * SEULEMENT : GPresets.sendVisualChatInfo envoie un Whisper au client, jamais
 * au serveur. Personne d'autre ne le voit, il n'est dans aucun historique.
 *
 * Sert a dire, a la FIN d'une operation longue, si tout s'est bien passe :
 * tu n'as pas a regarder la fenetre de l'Atelier pendant que tu joues.
 *
 * Pas de spam :
 *   - seulement les operations qui ont dure au moins DUREE_MIN (une action
 *     eclair se voit deja dans le jeu) ;
 *   - un meme message n'est pas repete dans les REPETITION_MS ;
 *   - au plus un message toutes les ECART_MS (le suivant attend son tour,
 *     un plus recent remplace celui qui attend).
 */
public final class InfoJeu {

    private InfoJeu() { }

    static final long DUREE_MIN = 3000, REPETITION_MS = 15000, ECART_MS = 2500;

    private static long dernierEnvoi = 0;
    private static String dernierTexte = null;
    private static String enAttente = null;
    private static boolean planifie = false;

    /** Heure de debut, a passer ensuite a fin(). */
    public static long debut() { return System.currentTimeMillis(); }

    /** Fin d'une operation commencee a debut : message seulement si elle a ete longue. */
    public static void fin(long debut, String message) {
        if (System.currentTimeMillis() - debut < DUREE_MIN) return;
        dire(message);
    }

    /** Message de fin, sans condition de duree (operation longue par nature). */
    public static synchronized void dire(String message) {
        if (message == null || message.isBlank()) return;
        String m = Ui.majuscule(Ui.accorder(message.trim()));
        Journal.jeu(m);
        long now = System.currentTimeMillis();
        if (m.equals(dernierTexte) && now - dernierEnvoi < REPETITION_MS) return;
        if (now - dernierEnvoi >= ECART_MS && !planifie) { envoyer(m); return; }
        enAttente = m;                     // le plus recent remplace celui qui attendait
        if (planifie) return;
        planifie = true;
        long attente = Math.max(0, ECART_MS - (now - dernierEnvoi));
        Thread t = new Thread(() -> {
            try { Thread.sleep(attente); } catch (InterruptedException ignored) { }
            synchronized (InfoJeu.class) {
                planifie = false;
                String a = enAttente;
                enAttente = null;
                if (a != null) envoyer(a);
            }
        }, "atelier-info-jeu");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Consigne a suivre tout de suite (une etape d'un outil, comme les messages
     * du moteur de l'Atelier) : envoyee sans attendre, seulement si on est dans un appart.
     */
    public static synchronized void consigne(String message) {
        if (message == null || message.isBlank()) return;
        String m = Ui.majuscule(Ui.accorder(message.trim()));
        Journal.jeu(m);
        envoyer(m);
    }

    /**
     * Le chat du jeu n'affiche que les caracteres latins (accents compris) : les
     * autres devenaient « ? » (« … » en fin de message). On les remplace par leur
     * equivalent simple ; ce qui n'en a pas est retire.
     */
    static String pourLeJeu(String m) {
        if (m == null) return "";
        m = m.replace("…", "...").replace("’", "'").replace("‘", "'").replace("“", "\"").replace("”", "\"")
             .replace("—", "-").replace("–", "-").replace("−", "-").replace("→", "->").replace("←", "<-")
             .replace("⌘", "Cmd").replace("⌥", "Option").replace("⇧", "Maj").replace("⌃", "Ctrl")
             .replace("\u202f", " ").replace("≈", "~").replace("≤", "<=").replace("≥", ">=");
        StringBuilder b = new StringBuilder(m.length());
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c <= 0xFF) b.append(c);              // latin-1 : passe tel quel
        }
        return b.toString().replaceAll(" {2,}", " ").trim();
    }

    private static void envoyer(String m) {
        m = pourLeJeu(m);
        dernierEnvoi = System.currentTimeMillis();
        dernierTexte = m;
        GPresets gp = Salle.gp();
        if (gp == null || !Salle.dansUneSalle()) return;
        try { gp.sendVisualChatInfo(m); }
        catch (Throwable t) { System.err.println("[Atelier] message du jeu pas envoyé : " + t); }
    }
}

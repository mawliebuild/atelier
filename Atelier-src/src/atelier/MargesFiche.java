package atelier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * La fiche du jeu (InfoStand : mobi, avatar, animal, bot), en bas a droite,
 * passait sous les menus de l'Atelier (menu lateral du mode Construction,
 * barre du bas). L'Atelier dit au client la place prise par ce qui la gene
 * VRAIMENT, d'apres ce qui est affiche :
 *
 *     « atelier:marges=<droite>;<bas>;<largeur> »
 *
 * en points de l'ecran : droite = distance du bord droit de la fenetre Habbo
 * au bord gauche du menu qui gene (0 : rien), bas = distance du bas de la
 * fenetre au haut de la barre qui gene (0 : rien), largeur = largeur de la
 * fenetre Habbo ; le client en tire son echelle (Retina, mise a l'echelle de
 * Windows). « 0;0 » : la fiche revient a sa place d'origine.
 *
 * Responsive : relu toutes les 400 ms (mode Construction, menu reduit,
 * barre cachee ou glissee ailleurs, fenetre redimensionnee) ; envoye au
 * changement, a l'entree dans un appart (la fiche est refaite avec la salle)
 * et toutes les 30 s.
 */
final class MargesFiche {

    private MargesFiche() { }

    /** Place d'origine de la fiche dans le jeu (pixels du jeu, a peu pres des points). */
    static final double FICHE_DROITE = 5, FICHE_BAS = 57, FICHE_LARGEUR = 200, FICHE_HAUTEUR = 330;
    /** Ecart laisse par le client entre la fiche et le menu qui la genait. */
    static final double ECART = 6;

    private static Stage[] genes = new Stage[0];
    private static Timeline boucle;
    private static String envoye = null;
    private static long envoyeA = 0;
    private static int salleEnvoi = -1, envoisSalle = 0;

    /** Les menus de l'Atelier qui peuvent couvrir la fiche (fil JavaFX). */
    static void demarrer(Stage... s) {
        genes = s == null ? new Stage[0] : s;
        if (boucle != null) return;
        boucle = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            try { verifier(); } catch (Throwable t) { Journal.debug("marges de la fiche : " + t); }
        }));
        boucle.setCycleCount(Timeline.INDEFINITE);
        boucle.play();
    }

    /** Fil JavaFX : les fenetres se lisent ici. */
    private static void verifier() {
        double[] h = Ancrage.cadreHabbo();
        if (h == null || !ClientModifie.saitMarges() || !Salle.dansUneSalle()) { envoye = null; return; }
        List<double[]> r = new ArrayList<>();
        for (Stage s : genes) {
            if (s == null || !s.isShowing() || s.getOpacity() <= 0.05) continue;
            if (s.getWidth() < 4 || s.getHeight() < 4) continue;
            r.add(new double[]{s.getX(), s.getY(), s.getWidth(), s.getHeight()});
        }
        int[] m = marges(h[0], h[1], h[2], h[3], r);
        String texte = "atelier:marges=" + m[0] + ";" + m[1] + ";" + Math.round(h[2]);
        int salle = Salle.salleId();
        if (salle != salleEnvoi) { salleEnvoi = salle; envoisSalle = 0; }
        long t = System.currentTimeMillis();
        // la fiche est refaite avec chaque salle : redit une fois peu apres l'entree
        boolean redire = envoisSalle < 3 && t - envoyeA > 2500;
        if (texte.equals(envoye) && !redire && t - envoyeA < 30_000) return;
        if (!texte.equals(envoye)) Journal.debug("marges de la fiche : droite " + m[0] + ", bas " + m[1] + ".");
        envoye = texte;
        envoyeA = t;
        envoisSalle++;
        Salle.tache("marges-fiche", () -> {
            Moteur gp = Salle.gp();
            if (gp != null) gp.sendToClient(new gearth.protocol.HPacket("Whisper",
                    gearth.protocol.HMessage.Direction.TOCLIENT, -1, texte, 0, 0, 0, -1));
        });
    }

    /**
     * Marges {droite, bas} (points, arrondies) pour que la fiche ne passe sous
     * aucun des rectangles r {x, y, largeur, hauteur} (coordonnees de l'ecran),
     * dans la fenetre Habbo (hx, hy, hl, hh). Chaque menu qui couvre la fiche
     * la pousse soit vers la gauche, soit vers le haut : on garde le choix qui
     * la deplace le moins. Ce qui ne la couvre pas ne compte pas. Logique pure.
     */
    static int[] marges(double hx, double hy, double hl, double hh, List<double[]> r) {
        double droite = hx + hl, bas = hy + hh;
        List<double[]> g = new ArrayList<>();
        for (double[] x : r) {
            // seulement ce qui est dans la fenetre du jeu
            if (x[0] >= droite || x[0] + x[2] <= hx || x[1] >= bas || x[1] + x[3] <= hy) continue;
            g.add(x);
        }
        int n = g.size();
        double meilleur = Double.MAX_VALUE;
        int[] choix = {0, 0};
        for (int masque = 0; masque < (1 << n); masque++) {     // bit a 1 : pousse vers la gauche
            double d = 0, b = 0;
            for (int i = 0; i < n; i++)
                if ((masque & (1 << i)) != 0 && couvre(g.get(i), droite, bas, 0, 0)) d = Math.max(d, droite - g.get(i)[0]);
            for (int i = 0; i < n; i++)
                if ((masque & (1 << i)) == 0 && couvre(g.get(i), droite, bas, decalage(d, FICHE_DROITE), 0))
                    b = Math.max(b, bas - g.get(i)[1]);
            double dx = decalage(d, FICHE_DROITE), dy = decalage(b, FICHE_BAS);
            boolean libre = true;
            for (double[] x : g) if (couvre(x, droite, bas, dx, dy)) { libre = false; break; }
            if (!libre) continue;
            if (dx + dy < meilleur) { meilleur = dx + dy; choix = new int[]{(int) Math.round(d), (int) Math.round(b)}; }
        }
        return choix;
    }

    /** Decalage de la fiche pour une marge m, sa place d'origine etant a o du bord (comme le client). */
    static double decalage(double m, double o) { return m <= 0 ? 0 : Math.max(0, m + ECART - o); }

    /** Le rectangle x couvre-t-il la fiche decalee de dx vers la gauche et dy vers le haut ? */
    static boolean couvre(double[] x, double droite, double bas, double dx, double dy) {
        double fd = droite - FICHE_DROITE - dx, fg = fd - FICHE_LARGEUR;
        double fb = bas - FICHE_BAS - dy, fh = fb - FICHE_HAUTEUR;
        return x[0] < fd && x[0] + x[2] > fg && x[1] < fb && x[1] + x[3] > fh;
    }
}

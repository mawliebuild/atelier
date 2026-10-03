package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Volet « Plan et mesure » : la carte de la salle, puis une mesure entre deux
 * points A et B.
 *
 * Les points se choisissent sur la carte (mode « Mesure ») ou dans le jeu
 * (« Mesurer dans le jeu », puis deux clics : sur le sol ou sur un mobi).
 * La distance est donnee de deux facons :
 *   - Chebyshev : max(dx, dy), le nombre de pas d'un avatar (diagonales permises) ;
 *   - Manhattan : dx + dy, sans diagonale.
 * L'altitude d'un point est celle du mobi clique, ou a defaut le sommet de la
 * pile sur la case (le sol nu s'il n'y a rien).
 */
public class OutilMesure {

    private static final Color COUL_A = Color.web("#1f7a3a"), COUL_B = Color.web("#c0392b");

    /** Un point mesure : case et altitude. */
    private static final class Point {
        final int x, y; final double z;
        Point(int x, int y, double z) { this.x = x; this.y = y; this.z = z; }
        @Override public String toString() { return "(" + x + ", " + y + ") · alt. " + Plan.fmt(z); }
    }

    private Plan plan;
    private Point a, b;
    private Label lA, lB, lDx, lDist, lDz, lClic, etat;
    private Button enJeu;
    private volatile int clicsAttendus = 0;
    private volatile long dernierClicCase = 0;
    private volatile int dernierCaseX = -1, dernierCaseY = -1;
    private volatile long dernierClicMobi = 0;
    private volatile HFloorItem dernierMobi;

    public VBox construire() {
        plan = new Plan();
        plan.activerClics("Mesure", this::clicPlan);

        lA = Ui.valeur("—");
        lB = Ui.valeur("—");
        lDx = new Label("—");
        lDist = new Label("—");
        lDz = new Label("—");
        for (Label l : new Label[]{lDx, lDist, lDz}) l.setWrapText(true);
        lClic = new Label("Aucun clic pour l'instant.");
        lClic.setWrapText(true);
        etat = Ui.etat();

        enJeu = new Button("Mesurer dans le jeu");
        enJeu.getStyleClass().add("primaire");
        enJeu.setMaxWidth(Double.MAX_VALUE);
        enJeu.setOnAction(e -> {
            if (clicsAttendus > 0) { clicsAttendus = 0; majBouton(); note("Mesure dans le jeu annulée."); return; }
            if (!Salle.dansUneSalle()) { note(Salle.gp() == null ? "G-Presets pas encore prêt." : "Entre d'abord dans une salle."); return; }
            a = null; b = null;
            plan.retirerMarque("A"); plan.retirerMarque("B");
            clicsAttendus = 2;
            majBouton();
            majMesure();
            note("Clique le point A dans le jeu (sur le sol ou sur un mobi).");
        });

        Button effacer = new Button("Effacer A et B");
        effacer.setOnAction(e -> {
            a = null; b = null; clicsAttendus = 0; majBouton();
            plan.retirerMarque("A"); plan.retirerMarque("B");
            majMesure();
        });
        Button modeMesure = new Button("Choisir sur la carte");
        modeMesure.setOnAction(e -> {
            plan.choisirMode(Plan.Mode.CLIC);
            note("Clique deux cases sur la carte : A puis B.");
        });

        Salle.surClicCase(this::clicCaseJeu);
        Salle.surClicMobi(this::clicMobiJeu);

        VBox v = new VBox(12,
                Ui.bloc("Plan de la salle", plan,
                        Ui.aide("Maj + glisser (ou mode « Sélection ») choisit la zone de travail.")),
                Ui.bloc("Mesure",
                        Ui.ligne(Ui.etiquette("A :"), lA),
                        Ui.ligne(Ui.etiquette("B :"), lB),
                        lDx, lDist, lDz,
                        enJeu, Ui.ligne(modeMesure, effacer)),
                Ui.bloc("Dernier clic", lClic),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        majMesure();
        return v;
    }

    // ------------------------------------------------------------- clics

    /** Clic sur la carte, mode « Mesure » (fil JavaFX). */
    private void clicPlan(int x, int y) {
        int hs = Salle.hauteurSol(x, y);
        if (hs < 0) { note("(" + x + ", " + y + ") n'est pas une case."); return; }
        HFloorItem haut = mobiDuHaut(x, y);
        double z = altitudeCase(x, y);
        decrireClic(x, y, haut, "carte");
        ajouterPoint(new Point(x, y, z));
    }

    /** Clic au sol dans le jeu (hors fil JavaFX). */
    private void clicCaseJeu(HPoint c) {
        if (c == null) return;
        dernierClicCase = System.currentTimeMillis();
        dernierCaseX = c.getX(); dernierCaseY = c.getY();
        final int x = c.getX(), y = c.getY();
        final double z = altitudeCase(x, y);
        final HFloorItem haut = mobiDuHaut(x, y);
        // Clic sur un mobi juste avant, et l'avatar marche vers lui : meme clic.
        HFloorItem m = dernierMobi;
        final boolean doublon = m != null && System.currentTimeMillis() - dernierClicMobi < 500 && couvre(m, x, y);
        Platform.runLater(() -> {
            if (doublon) return;
            decrireClic(x, y, haut, "jeu");
            if (clicsAttendus > 0) prendreEnJeu(new Point(x, y, z));
        });
    }

    /** Clic sur un mobi dans le jeu (hors fil JavaFX). */
    private void clicMobiJeu(HFloorItem it) {
        if (it == null) return;
        final int x = it.getTile().getX(), y = it.getTile().getY();
        final double z = it.getTile().getZ();
        dernierClicMobi = System.currentTimeMillis();
        dernierMobi = it;
        // Un clic sur un mobi peut aussi faire marcher l'avatar vers sa case :
        // ne pas compter deux points pour un seul clic.
        boolean doublon = System.currentTimeMillis() - dernierClicCase < 500
                && dernierCaseX == x && dernierCaseY == y;
        Platform.runLater(() -> {
            decrireClic(x, y, it, "jeu");
            if (clicsAttendus <= 0) return;
            if (doublon) {
                // Le point vient d'etre pris sur la case : on prefere l'altitude du mobi.
                if (b != null && b.x == x && b.y == y) b = new Point(x, y, z);
                else if (a != null && b == null && a.x == x && a.y == y) a = new Point(x, y, z);
                majMesure();
                return;
            }
            prendreEnJeu(new Point(x, y, z));
        });
    }

    private void prendreEnJeu(Point p) {
        ajouterPoint(p);
        clicsAttendus--;
        if (clicsAttendus <= 0) { clicsAttendus = 0; note("Mesure terminée."); }
        else note("Clique maintenant le point B dans le jeu.");
        majBouton();
    }

    private void ajouterPoint(Point p) {
        if (a == null || b != null) { a = p; b = null; plan.retirerMarque("B"); plan.marquer("A", p.x, p.y, COUL_A); }
        else { b = p; plan.marquer("B", p.x, p.y, COUL_B); }
        majMesure();
    }

    private void majBouton() {
        enJeu.setText(clicsAttendus > 0 ? "Annuler (clique " + (clicsAttendus == 2 ? "A" : "B") + " dans le jeu)"
                : "Mesurer dans le jeu");
    }

    // ------------------------------------------------------------- calculs

    private void majMesure() {
        lA.setText(a == null ? "—" : a.toString());
        lB.setText(b == null ? "—" : b.toString());
        if (a == null || b == null) {
            lDx.setText("Choisis deux points pour mesurer.");
            lDist.setText("");
            lDz.setText("");
            return;
        }
        int dx = b.x - a.x, dy = b.y - a.y;
        int cheb = Math.max(Math.abs(dx), Math.abs(dy)), manh = Math.abs(dx) + Math.abs(dy);
        lDx.setText("dx = " + signe(dx) + "   ·   dy = " + signe(dy)
                + "   ·   rectangle " + (Math.abs(dx) + 1) + " × " + (Math.abs(dy) + 1) + " cases");
        lDist.setText("Distance : " + cheb + " pas d'avatar (Chebyshev) · " + manh + " cases sans diagonale (Manhattan)");
        double dz = b.z - a.z;
        lDz.setText("Différence d'altitude (B − A) : " + (dz > 0 ? "+" : "") + Plan.fmt(dz));
    }

    private static String signe(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /** Altitude d'une case : sommet de la pile, a defaut le sol nu. */
    private static double altitudeCase(int x, int y) {
        double z = Double.NaN;
        for (HFloorItem it : Salle.sols())
            if (couvre(it, x, y)) {
                double h = it.getTile().getZ() + Salle.hauteur(it);
                if (Double.isNaN(z) || h > z) z = h;
            }
        if (!Double.isNaN(z)) return z;
        return Math.max(0, Salle.hauteurSol(x, y));
    }

    /** Le mobi le plus haut qui couvre la case, ou null. */
    private static HFloorItem mobiDuHaut(int x, int y) {
        HFloorItem m = null;
        double best = -1;
        for (HFloorItem it : Salle.sols())
            if (couvre(it, x, y) && it.getTile().getZ() > best) { best = it.getTile().getZ(); m = it; }
        return m;
    }

    private static boolean couvre(HFloorItem it, int x, int y) {
        try {
            int[] e = Salle.emprise(it);
            int x0 = it.getTile().getX(), y0 = it.getTile().getY();
            return x >= x0 && y >= y0 && x < x0 + e[0] && y < y0 + e[1];
        } catch (Throwable t) { return false; }
    }

    private void decrireClic(int x, int y, HFloorItem it, String ou) {
        StringBuilder sb = new StringBuilder();
        int hs = Salle.hauteurSol(x, y);
        sb.append("Case (").append(x).append(", ").append(y).append(") · sol ")
          .append(hs < 0 ? "pas de case" : String.valueOf(hs)).append("   [").append(ou).append("]");
        List<HFloorItem> tous = Salle.sols();
        int n = 0;
        for (HFloorItem m : tous) if (couvre(m, x, y)) n++;
        sb.append("\n").append(n).append(n > 1 ? " mobis sur la case" : " mobi sur la case");
        if (it != null) {
            int[] e = Salle.emprise(it);
            String cls = Salle.classe(it.getTypeId(), false);
            sb.append("\nMobi : ").append(Salle.nom(it.getTypeId(), false));
            if (cls != null) sb.append(" (").append(cls).append(")");
            sb.append("\nid ").append(it.getId())
              .append(" · rotation ").append(Salle.rotation(it))
              .append(" · altitude ").append(Plan.fmt(it.getTile().getZ()))
              .append(" · hauteur propre ").append(Plan.fmt(Salle.hauteur(it)))
              .append("\nEmprise ").append(e[0]).append(" × ").append(e[1])
              .append(" · origine (").append(it.getTile().getX()).append(", ").append(it.getTile().getY()).append(")");
        }
        lClic.setText(sb.toString());
    }

    private void note(String s) { Platform.runLater(() -> etat.setText(s)); }
}

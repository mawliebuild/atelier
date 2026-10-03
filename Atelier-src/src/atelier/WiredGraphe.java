package atelier;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.function.Consumer;

/**
 * Le graphe des piles de wired, dessine sur un Canvas.
 *
 * Molette : zoom autour du pointeur. Glisser : deplacer. Clic sur une boite :
 * la pile est choisie (rappel « surChoix »). Double-clic dans le vide : recentrer.
 */
public final class WiredGraphe extends Pane {

    private final Canvas toile = new Canvas();
    private WiredAnalyse modele;
    private WiredAnalyse.Pile choisie;
    private Consumer<WiredAnalyse.Pile> surChoix = p -> { };

    private double echelle = 1, tx = 0, ty = 0;
    private double pressX, pressY, departTx, departTy;
    private boolean glisse;
    private boolean ajusteAuDessin = true;

    private static final Color FOND = Color.web("#fbf8f0");
    private static final Color FILET = Color.web("#b9b2a2");
    private static final Color TEXTE = Color.web("#2b2b2b");
    private static final Color LIEN = Color.web("#5b7fa6");
    private static final Color SIGNAL = Color.web("#e07020");
    private static final Color CHOIX = Color.web("#1e88e5");

    public WiredGraphe(double largeur, double hauteur) {
        getChildren().add(toile);
        setPrefSize(largeur, hauteur);
        setMinHeight(200);
        setStyle("-fx-border-color: #b9b2a2; -fx-border-width: 1;");
        toile.widthProperty().bind(widthProperty());
        toile.heightProperty().bind(heightProperty());
        toile.widthProperty().addListener((o, a, b) -> dessiner());
        toile.heightProperty().addListener((o, a, b) -> {
            if (ajusteAuDessin) ajuster(); else dessiner();
        });

        toile.setOnScroll(e -> {
            double f = e.getDeltaY() > 0 ? 1.15 : (e.getDeltaY() < 0 ? 1 / 1.15 : 1);
            double ne = Math.max(0.15, Math.min(4, echelle * f));
            f = ne / echelle;
            // zoom autour du pointeur
            tx = e.getX() - (e.getX() - tx) * f;
            ty = e.getY() - (e.getY() - ty) * f;
            echelle = ne;
            ajusteAuDessin = false;
            dessiner();
            e.consume();
        });
        toile.setOnMousePressed(e -> {
            pressX = e.getX(); pressY = e.getY();
            departTx = tx; departTy = ty;
            glisse = false;
            e.consume();
        });
        toile.setOnMouseDragged(e -> {
            double dx = e.getX() - pressX, dy = e.getY() - pressY;
            if (Math.abs(dx) + Math.abs(dy) > 4) glisse = true;
            if (glisse) {
                tx = departTx + dx; ty = departTy + dy;
                ajusteAuDessin = false;
                dessiner();
            }
            e.consume();
        });
        toile.setOnMouseReleased(e -> e.consume());
        toile.setOnMouseClicked(e -> {
            if (glisse || e.getButton() != MouseButton.PRIMARY) return;
            WiredAnalyse.Pile p = touchee(e.getX(), e.getY());
            if (p != null) {
                choisie = p;
                dessiner();
                try { surChoix.accept(p); } catch (Throwable ignored) { }
            } else if (e.getClickCount() == 2) {
                ajuster();
            }
            e.consume();
        });
    }

    public void surChoix(Consumer<WiredAnalyse.Pile> c) { surChoix = c == null ? p -> { } : c; }

    public void montrer(WiredAnalyse a) {
        boolean premier = modele == null || modele.piles.isEmpty();
        modele = a;
        // garder la pile choisie si elle existe encore
        WiredAnalyse.Pile ancienne = choisie;
        choisie = null;
        if (ancienne != null && a != null)
            for (WiredAnalyse.Pile p : a.piles)
                if (p.x == ancienne.x && p.y == ancienne.y) choisie = p;
        if (premier || ajusteAuDessin) ajuster(); else dessiner();
    }

    public WiredAnalyse.Pile choisie() { return choisie; }

    public void choisir(WiredAnalyse.Pile p) { choisie = p; dessiner(); }

    /** Tout le graphe dans la fenetre. */
    public void ajuster() {
        ajusteAuDessin = true;
        double w = toile.getWidth(), h = toile.getHeight();
        if (modele == null || modele.piles.isEmpty() || w < 10 || h < 10) {
            echelle = 1; tx = w / 2; ty = 20;
            dessiner();
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (WiredAnalyse.Pile p : modele.piles) {
            minX = Math.min(minX, p.gx); minY = Math.min(minY, p.gy);
            maxX = Math.max(maxX, p.gx + p.larg); maxY = Math.max(maxY, p.gy + p.haut);
        }
        double marge = 16;
        double ex = (w - 2 * marge) / Math.max(1, maxX - minX);
        double ey = (h - 2 * marge) / Math.max(1, maxY - minY);
        echelle = Math.max(0.15, Math.min(1.2, Math.min(ex, ey)));
        tx = w / 2 - (minX + maxX) / 2 * echelle;
        ty = marge - minY * echelle;
        dessiner();
    }

    private WiredAnalyse.Pile touchee(double sx, double sy) {
        if (modele == null) return null;
        double x = (sx - tx) / echelle, y = (sy - ty) / echelle;
        for (WiredAnalyse.Pile p : modele.piles)
            if (x >= p.gx && x <= p.gx + p.larg && y >= p.gy && y <= p.gy + p.haut) return p;
        return null;
    }

    // ------------------------------------------------------------------ dessin

    public static Color couleur(Wired.Rang r) {
        switch (r) {
            case DECLENCHEUR:      return Color.web("#e0a020");
            case SELECTEUR:        return Color.web("#8e6ad8");
            case SELECTEUR_FILTRE: return Color.web("#b39ddb");
            case CONDITION:        return Color.web("#3fa34d");
            case EFFET:            return Color.web("#2f7fd0");
            case EFFET_SIGNAL:     return Color.web("#e07020");
            case EFFET_NEGATIF:    return Color.web("#c03030");
            case ADDON:            return Color.web("#8a8a8a");
            default:               return Color.web("#444444");
        }
    }

    private void dessiner() {
        GraphicsContext g = toile.getGraphicsContext2D();
        double w = toile.getWidth(), h = toile.getHeight();
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.setFill(FOND);
        g.fillRect(0, 0, w, h);
        if (modele == null || modele.piles.isEmpty()) {
            g.setFill(TEXTE.deriveColor(0, 1, 1, 0.6));
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(modele == null || !modele.salle ? "Pas de salle ouverte."
                    : "Aucun wired dans cette salle.", w / 2, h / 2);
            return;
        }
        g.setTransform(echelle, 0, 0, echelle, tx, ty);
        g.setLineCap(StrokeLineCap.ROUND);

        for (WiredAnalyse.Lien li : modele.liens) fleche(g, li);
        for (WiredAnalyse.Pile p : modele.piles) boite(g, p);
        g.setTransform(1, 0, 0, 1, 0, 0);
    }

    private void fleche(GraphicsContext g, WiredAnalyse.Lien li) {
        WiredAnalyse.Pile a = li.de, b = li.vers;
        g.setStroke(li.signal ? SIGNAL : LIEN);
        g.setLineWidth(1.6);
        g.setLineDashes(li.signal ? new double[]{6, 4} : null);
        double x1, y1, x2, y2, cx, cy;
        if (b.gy > a.gy + a.haut - 1) {             // vers le bas : bas de A -> haut de B
            x1 = a.gx + a.larg / 2; y1 = a.gy + a.haut;
            x2 = b.gx + b.larg / 2; y2 = b.gy;
            cx = (x1 + x2) / 2; cy = (y1 + y2) / 2;
        } else {                                     // retour ou meme couche : par le cote
            x1 = a.gx + a.larg; y1 = a.gy + a.haut / 2;
            x2 = b.gx + b.larg; y2 = b.gy + b.haut / 2;
            if (b.gx > a.gx + 1) x2 = b.gx;              // B a droite : de la droite de A a la gauche de B
            else if (b.gx < a.gx - 1) x1 = a.gx;         // B a gauche : de la gauche de A a la droite de B
            cx = (x1 + x2) / 2 + (Math.abs(b.gx - a.gx) < 1 ? 60 : 0);
            cy = Math.min(y1, y2) - 40;
        }
        g.beginPath();
        g.moveTo(x1, y1);
        g.quadraticCurveTo(cx, cy, x2, y2);
        g.stroke();
        g.setLineDashes(null);
        // pointe
        double ang = Math.atan2(y2 - cy, x2 - cx);
        double l = 8;
        g.setFill(li.signal ? SIGNAL : LIEN);
        g.fillPolygon(new double[]{x2, x2 - l * Math.cos(ang - 0.4), x2 - l * Math.cos(ang + 0.4)},
                new double[]{y2, y2 - l * Math.sin(ang - 0.4), y2 - l * Math.sin(ang + 0.4)}, 3);
        if (li.signal) {
            g.setFont(Font.font(9));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.BOTTOM);
            g.fillText("signal", (x1 + x2) / 2, (y1 + y2) / 2 - 2);
        }
    }

    private void boite(GraphicsContext g, WiredAnalyse.Pile p) {
        boolean sel = p == choisie;
        boolean orpheline = !p.aDeclencheur() && p.aEffet();
        g.setFill(Color.WHITE);
        g.fillRoundRect(p.gx, p.gy, p.larg, p.haut, 8, 8);
        g.setStroke(sel ? CHOIX : (orpheline ? Color.web("#c03030") : FILET));
        g.setLineWidth(sel ? 2.4 : 1);
        g.strokeRoundRect(p.gx, p.gy, p.larg, p.haut, 8, 8);

        // en-tete : la case
        g.setFill(TEXTE);
        g.setFont(Font.font("System", FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("Case " + p.caseTexte() + "  ·  " + p.wired.size(), p.gx + 7, p.gy + 10);
        if (p.signalSansLien || p.receptionSansLien) {
            g.setFill(SIGNAL);
            g.setFont(Font.font(9));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(p.signalSansLien ? "Signal →?" : "?→ Signal", p.gx + p.larg - 6, p.gy + 10);
            g.setTextAlign(TextAlignment.LEFT);
        }

        g.setFont(Font.font(10));
        double y = p.gy + WiredAnalyse.ENTETE + WiredAnalyse.LIGNE / 2;
        int n = 0;
        // Affichee du haut vers le bas comme une fiche : l'ordre de la regle.
        for (WiredAnalyse.Fil f : p.wired) {
            if (n >= WiredAnalyse.LIGNES_MAX) break;
            g.setFill(couleur(f.rang));
            g.fillRect(p.gx + 7, y - 4, 8, 8);
            g.setFill(f.conf == null ? TEXTE.deriveColor(0, 1, 1, 0.55) : TEXTE);
            g.fillText(WiredAnalyse.court(f.nom, 24), p.gx + 20, y);
            y += WiredAnalyse.LIGNE;
            n++;
        }
        if (p.wired.size() > WiredAnalyse.LIGNES_MAX) {
            g.setFill(TEXTE.deriveColor(0, 1, 1, 0.6));
            g.fillText("+ " + (p.wired.size() - WiredAnalyse.LIGNES_MAX) + " autre(s)", p.gx + 20, y);
        }
    }
}

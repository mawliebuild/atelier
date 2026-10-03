package atelier;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.*;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.*;

/**
 * Vue isometrique EN RELIEF du plan en cours d'edition.
 *
 * Meme projection que Plan (x vers le bas-droite, y vers le bas-gauche, demi-
 * case 16 x 8 a zoom 1), mais chaque case est un petit bloc pose a sa hauteur :
 * dessus + deux faces avant, dessines de l'arriere vers l'avant, pour voir les
 * marches. Les mobis de sol sont des blocs poses a leur altitude, par-dessus,
 * de la couleur de leur type, avec leur icone officielle sur le dessus
 * (chargee en tache de fond, gardee en cache). Affichage : sans mobis, avec,
 * en transparence, ou seulement ceux touches par un changement.
 *
 *   clic / glisser gauche   applique l'outil (pinceau, ou rectangle)
 *   clic droit / milieu / Espace + glisser   deplacer la vue
 *   molette                 zoom autour du pointeur
 *   Ctrl/Cmd+Z, Ctrl/Cmd+Y (ou Maj+Z)   annuler / retablir
 *   fleches gauche/droite   tourner la porte (outil Porte)
 */
final class FloorVue extends Pane {

    private static final double DEMI_L = 16, DEMI_H = 8;

    private final FloorSession s;
    private final Canvas canvas = new Canvas(340, 300);
    /** Texte de survol (case, hauteur, mobi). */
    final StringProperty survol = new SimpleStringProperty("Survole le plan pour lire une case.");
    boolean chiffres = true, grille = true;

    private double zoom = 1, ox = 170, oy = 30;
    private String cadrage = "";
    private int survolX = -1, survolY = -1;
    private FloorSession.Mobi mobiSurvole;

    private boolean espace, deplace, trait;
    private double dernierX, dernierY;
    private Set<Long> dejaVu;
    private int rx1 = -1, ry1, rx2, ry2;

    FloorVue(FloorSession session, double largeur, double hauteur) {
        s = session;
        setPrefSize(largeur, hauteur);
        setMinSize(100, 120);
        getChildren().add(canvas);
        setStyle("-fx-border-color: #b9ad8f; -fx-border-width: 1; -fx-background-color: #f4efe2;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> { ox += (b.doubleValue() - a.doubleValue()) / 2; dessiner(); });
        canvas.heightProperty().addListener((o, a, b) -> dessiner());
        canvas.setFocusTraversable(true);
        s.ecouter(this::surChangement);
        s.relief.addListener((o, a, b) -> { recentrer(); dessiner(); });
        s.affichage.addListener((o, a, b) -> { mobiSurvole = null; dessiner(); });
        s.icones.addListener((o, a, b) -> dessiner());
        VUES.add(new java.lang.ref.WeakReference<>(this));
        brancher();
        dessiner();
    }

    private void surChangement() {
        FloorModele m = s.courant;
        String c = m == null ? "" : s.salleId + ":" + m.largeur + "x" + m.longueur;
        if (!c.equals(cadrage)) { cadrage = c; recentrer(); }
        majSurvol();
        dessiner();
    }

    // ---------------------------------------------------------- geometrie

    private double hl() { return DEMI_L * zoom; }
    private double hh() { return DEMI_H * zoom; }
    /** Pixels par niveau de hauteur (2 demi-cases = reel ; reduit par le reglage Relief). */
    private double hz() { return 2 * hh() * s.relief.get(); }
    private double epaisseur() { return Math.max(2, hh() * 0.35); }
    private double ex(double fx, double fy) { return ox + (fx - fy) * hl(); }
    private double ey(double fx, double fy, double z) { return oy + (fx + fy) * hh() - z * hz(); }

    void recentrer() {
        FloorModele d = s.courant;
        double W = Math.max(60, canvas.getWidth()), H = Math.max(60, canvas.getHeight());
        if (d == null) { zoom = 1; ox = W / 2; oy = 30; return; }
        double uMin = 1e9, uMax = -1e9, vMin = 1e9, vMax = -1e9;
        double k = 2 * s.relief.get();  // niveaux -> unites v (demi-hauteurs) a zoom 1
        for (int x = 0; x < d.largeur; x++)
            for (int y = 0; y < d.longueur; y++) {
                int h = d.h[x][y];
                if (h < 0) continue;
                uMin = Math.min(uMin, x - y - 1); uMax = Math.max(uMax, x - y + 1);
                vMin = Math.min(vMin, x + y - h * k); vMax = Math.max(vMax, x + y + 2.5);
            }
        if (uMin > 1e8) { uMin = -d.longueur; uMax = d.largeur; vMin = 0; vMax = d.largeur + d.longueur; }
        double marge = 30;
        zoom = Math.min((W - marge) / ((uMax - uMin) * DEMI_L), (H - marge) / ((vMax - vMin) * DEMI_H));
        zoom = Math.max(0.12, Math.min(6, zoom));
        ox = W / 2 - (uMin + uMax) / 2 * hl();
        oy = H / 2 - (vMin + vMax) / 2 * hh();
    }

    /**
     * Case sous le point : le premier bloc touche en partant de l'avant (dessus
     * OU face laterale), sinon la case du plan a hauteur 0 (pour Ajouter).
     */
    int[] caseSous(double px, double py) {
        FloorModele d = s.courant;
        if (d == null) return new int[]{-1, -1};
        double a = (px - ox) / hl(), b0 = (py - oy) / hh(), k = hz() / hh(), ep = epaisseur() / hh();
        for (int sm = d.largeur + d.longueur - 2; sm >= 0; sm--)
            for (int x = Math.min(sm, d.largeur - 1); x >= Math.max(0, sm - d.longueur + 1); x--) {
                int y = sm - x, h = d.h[x][y];
                if (h < 0) continue;
                if (touche(a, b0 - ep, b0 + h * k, x, y, 1, 1)) return new int[]{x, y};
            }
        int fx = (int) Math.floor((a + b0) / 2), fy = (int) Math.floor((b0 - a) / 2);
        return new int[]{fx, fy};
    }

    /** Le prisme de base [x,x+lx)x[y,y+ly) couvre-t-il un b dans [bBas, bHaut] (unites demi-hauteur) ? */
    private static boolean touche(double a, double bBas, double bHaut, int x, int y, int lx, int ly) {
        double lo = Math.max(2 * x - a, 2 * y + a), hi = Math.min(2 * (x + lx) - a, 2 * (y + ly) + a);
        if (lo >= hi) return false;
        return hi > bBas && lo <= bHaut;
    }

    /** Les mobis a montrer, selon l'affichage choisi. */
    private List<FloorSession.Mobi> visibles() {
        FloorSession.Affichage a = s.affichage.get();
        if (a == FloorSession.Affichage.SANS) return List.of();
        if (a == FloorSession.Affichage.TOUCHES) {
            Set<Integer> d = s.bilan.mobisEnDanger;
            List<FloorSession.Mobi> l = new ArrayList<>();
            for (FloorSession.Mobi m : s.mobis) if (d.contains(m.id)) l.add(m);
            return l;
        }
        return s.mobis;
    }

    private FloorSession.Mobi mobiSous(double px, double py) {
        double a = (px - ox) / hl(), b0 = (py - oy) / hh(), k = hz() / hh();
        List<FloorSession.Mobi> l = trier(visibles());
        for (int i = l.size() - 1; i >= 0; i--) {
            FloorSession.Mobi m = l.get(i);
            double haut = Math.max(0.05, m.haut);
            if (touche(a, b0 + m.z * k, b0 + (m.z + haut) * k, m.x, m.y, m.ex, m.ey)) return m;
        }
        return null;
    }

    private static List<FloorSession.Mobi> trier(List<FloorSession.Mobi> src) {
        List<FloorSession.Mobi> l = new ArrayList<>(src);
        l.sort(Comparator.comparingInt((FloorSession.Mobi m) -> m.x + m.ex + m.y + m.ey).thenComparingDouble(m -> m.z));
        return l;
    }

    // -------------------------------------------------------------- souris

    private void brancher() {
        canvas.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            double a = (e.getX() - ox) / hl(), b = (e.getY() - oy) / hh();
            zoom *= e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
            zoom = Math.max(0.12, Math.min(10, zoom));
            ox = e.getX() - a * hl();
            oy = e.getY() - b * hh();
            dessiner();
            e.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            canvas.requestFocus();
            dernierX = e.getX(); dernierY = e.getY();
            if (e.getButton() == MouseButton.SECONDARY || e.getButton() == MouseButton.MIDDLE
                    || (e.getButton() == MouseButton.PRIMARY && espace)) { deplace = true; return; }
            if (e.getButton() != MouseButton.PRIMARY || !s.pret()) return;
            int[] c = caseSous(e.getX(), e.getY());
            FloorSession.Outil o = s.outil.get();
            if (o == FloorSession.Outil.PORTE || o == FloorSession.Outil.PIPETTE) {
                s.cliquer(c[0], c[1]);
                if (o == FloorSession.Outil.PIPETTE) s.change();
                return;
            }
            s.memoriser();
            trait = true;
            dejaVu = new HashSet<>();
            if (s.rectangle.get()) { rx1 = rx2 = c[0]; ry1 = ry2 = c[1]; dessiner(); }
            else peindre(c);
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (deplace) {
                ox += e.getX() - dernierX; oy += e.getY() - dernierY;
                dernierX = e.getX(); dernierY = e.getY();
                dessiner();
                return;
            }
            survolA(e.getX(), e.getY());
            if (!trait) { dessiner(); return; }
            int[] c = caseSous(e.getX(), e.getY());
            if (s.rectangle.get()) { rx2 = c[0]; ry2 = c[1]; dessiner(); }
            else peindre(c);
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (deplace) { deplace = false; return; }
            if (!trait) return;
            trait = false;
            if (s.rectangle.get() && rx1 != -1 && s.courant != null) {
                FloorModele m = s.courant;
                int x1 = Math.max(0, Math.min(rx1, rx2)), x2 = Math.min(m.largeur - 1, Math.max(rx1, rx2));
                int y1 = Math.max(0, Math.min(ry1, ry2)), y2 = Math.min(m.longueur - 1, Math.max(ry1, ry2));
                int n = 0;
                for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) if (s.appliquer(x, y, dejaVu)) n++;
                rx1 = -1;
                s.etat.set(n == 0 ? "Rien n'a changé dans ce rectangle." : n + " case(s) modifiée(s).");
                s.oublierSiInutile();
                s.change();
            } else {
                s.oublierSiInutile();
            }
            dejaVu = null;
        });
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> { survolA(e.getX(), e.getY()); dessiner(); });
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            survolX = survolY = -1; mobiSurvole = null; majSurvol(); dessiner();
        });
        canvas.getProperties().put(OutilHistorique.RACCOURCIS_PROPRES, true);
        canvas.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE) { espace = true; e.consume(); return; }
            if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
                if (e.isShiftDown()) s.retablir(); else s.annuler();
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.Y) {
                s.retablir(); e.consume();
            } else if (s.outil.get() == FloorSession.Outil.PORTE && s.courant != null
                    && (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT)) {
                s.reglerDirection(s.courant.porteDir + (e.getCode() == KeyCode.RIGHT ? 1 : 7));
                e.consume();
            }
        });
        canvas.addEventHandler(KeyEvent.KEY_RELEASED, e -> { if (e.getCode() == KeyCode.SPACE) espace = false; });
        canvas.focusedProperty().addListener((o, a, b) -> { if (!b) espace = false; });
    }

    private void peindre(int[] c) {
        boolean ch = false;
        for (int[] p : s.pinceau(c[0], c[1])) ch |= s.appliquer(p[0], p[1], dejaVu);
        if (ch) s.change();
    }

    private void survolA(double px, double py) {
        int[] c = caseSous(px, py);
        survolX = c[0]; survolY = c[1];
        mobiSurvole = mobiSous(px, py);
        majSurvol();
    }

    private void majSurvol() {
        FloorModele d = s.courant;
        if (d == null) { survol.set(Salle.gp() == null ? "G-Presets pas encore prêt." : "Pas de plan chargé."); return; }
        if (survolX < 0 && survolY < 0 && mobiSurvole == null) { survol.set("Survole le plan pour lire une case."); return; }
        StringBuilder sb = new StringBuilder();
        int x = survolX, y = survolY;
        if (!d.dansPlan(x, y)) sb.append("(").append(x).append(", ").append(y).append(") · hors du plan");
        else {
            int h = d.h[x][y], a = FloorModele.ancien(s.original, x, y);
            sb.append("(").append(x).append(", ").append(y).append(") · ");
            sb.append(h < 0 ? "pas de case" : "hauteur " + h);
            if (a != h) sb.append(" (avant : ").append(a < 0 ? "vide" : String.valueOf(a)).append(")");
            if (d.porteConnue && d.porteX == x && d.porteY == y) sb.append(" · porte");
        }
        if (mobiSurvole != null)
            sb.append("\n").append(mobiSurvole.nom).append(mobiSurvole.wired ? " (wired)" : "")
              .append(" · alt. ").append(fmt(mobiSurvole.z))
              .append(" · ").append(mobiSurvole.ex).append("×").append(mobiSurvole.ey);
        survol.set(sb.toString());
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.FRANCE, "%.2f", v).replaceAll("0+$", "").replaceAll(",$", "");
    }

    /** Vrai si le noeud est reellement a l'ecran (meme test que Plan.estAffiche). */
    static boolean estAffiche(javafx.scene.Node n) {
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null
                || !n.getScene().getWindow().isShowing()) return false;
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    // -------------------------------------------------------------- dessin

    private static final Color BAS = Color.web("#efe5c8"), HAUT = Color.web("#8fb0d8");

    void dessiner() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth(), H = canvas.getHeight();
        g.setFill(Color.web("#f4efe2"));
        g.fillRect(0, 0, W, H);
        FloorModele d = s.courant;
        if (d == null) {
            g.setFill(Color.web("#7a6f58"));
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(Salle.gp() == null ? "G-Presets pas encore prêt" : "Entre dans une salle : son plan s'affiche tout seul.", W / 2, H / 2);
            return;
        }
        FloorModele o = s.original;
        double hmax = Math.max(1, d.hauteurMaxi());
        double ep = epaisseur();

        // Cadre du plan (rectangle de la grille) au niveau 0.
        if (grille) {
            g.setStroke(Color.web("#d6cbb0"));
            g.setLineWidth(1);
            g.setLineDashes(4, 4);
            polygone(g, new double[]{0, d.largeur, d.largeur, 0}, new double[]{0, 0, d.longueur, d.longueur}, 0, false);
            g.setLineDashes((double[]) null);
        }

        // Fantomes des cases supprimees (a leur ancienne hauteur), sous le reste.
        if (o != null) {
            g.setLineDashes(3, 3);
            for (int x = 0; x < o.largeur; x++)
                for (int y = 0; y < o.longueur; y++)
                    if (o.h[x][y] >= 0 && d.at(x, y) < 0) {
                        g.setFill(Color.rgb(220, 60, 50, 0.10));
                        g.setStroke(Color.rgb(200, 50, 40, 0.8));
                        g.setLineWidth(1);
                        losange(g, x, y, o.h[x][y], 0.04, true, true);
                    }
            g.setLineDashes((double[]) null);
        }

        // Cases, de l'arriere vers l'avant.
        boolean texte = chiffres && zoom >= 1.15;
        for (int sm = 0; sm <= d.largeur + d.longueur - 2; sm++)
            for (int x = Math.max(0, sm - d.longueur + 1); x <= Math.min(sm, d.largeur - 1); x++) {
                int y = sm - x, h = d.h[x][y];
                if (h < 0) continue;
                double cx = ex(x + 0.5, y + 0.5), cy = ey(x + 0.5, y + 0.5, h);
                if (cx < -hl() * 2 || cx > W + hl() * 2 || cy < -hh() * 3 || cy > H + hh() * 2 + h * hz()) continue;
                Color dessus = BAS.interpolate(HAUT, h / hmax);
                double haut = h * hz();
                // face gauche (bord y+1) puis face droite (bord x+1)
                face(g, x, y + 1, x + 1, y + 1, h, haut + ep, dessus.deriveColor(0, 1, 0.80, 1));
                face(g, x + 1, y + 1, x + 1, y, h, haut + ep, dessus.deriveColor(0, 1, 0.66, 1));
                g.setFill(dessus);
                losange(g, x, y, h, 0, true, false);
                if (grille && zoom >= 0.5) {
                    g.setStroke(Color.web("#b9ad8f"));
                    g.setLineWidth(0.6);
                    losange(g, x, y, h, 0, false, true);
                }
                int a = FloorModele.ancien(o, x, y);
                if (a != h) {
                    g.setStroke(a < 0 ? Color.web("#2e9e4f") : Color.web("#e07b00"));
                    g.setLineWidth(Math.max(1.5, Math.min(3, zoom * 1.6)));
                    losange(g, x, y, h, 0.08, false, true);
                }
                if (texte) {
                    g.setFill(a != h ? Color.web("#8a4a00") : Color.web("#5b5040"));
                    g.setFont(Font.font(null, a != h ? FontWeight.BOLD : FontWeight.NORMAL, Math.min(12, 5 + zoom * 2.5)));
                    g.setTextAlign(TextAlignment.CENTER);
                    g.setTextBaseline(VPos.CENTER);
                    g.fillText(String.valueOf(h), cx, cy);
                }
            }

        // Mobis.
        List<FloorSession.Mobi> vis = visibles();
        if (!vis.isEmpty()) {
            Set<Integer> danger = s.bilan.mobisEnDanger;
            boolean fantome = s.affichage.get() == FloorSession.Affichage.TRANSPARENTS;
            boolean avecIcones = s.icones.get() && zoom >= 0.55;
            for (FloorSession.Mobi m : trier(vis)) {
                Color c = couleur(m);
                if (m == mobiSurvole) c = c.brighter();
                bloc(g, m, c, danger.contains(m.id), fantome ? 0.30 : 0.88);
                if (avecIcones) icone(g, m, fantome ? 0.45 : 1);
            }
        }

        // Porte.
        if (d.porteConnue) porte(g, d);

        // Rectangle en cours.
        if (trait && s.rectangle.get() && rx1 != -1) {
            int x1 = Math.min(rx1, rx2), x2 = Math.max(rx1, rx2) + 1, y1 = Math.min(ry1, ry2), y2 = Math.max(ry1, ry2) + 1;
            g.setFill(Color.rgb(40, 120, 220, 0.18));
            g.setStroke(Color.web("#2878dc"));
            g.setLineWidth(2);
            g.setLineDashes(5, 4);
            double z = d.at(rx1, ry1) < 0 ? 0 : d.at(rx1, ry1);
            polygone(g, new double[]{x1, x2, x2, x1}, new double[]{y1, y1, y2, y2}, z, true);
            g.setLineDashes((double[]) null);
        }

        // Survol (avec la taille du pinceau).
        if (survolX >= 0 || survolY >= 0) {
            g.setStroke(Color.web("#1b1b1b"));
            g.setLineWidth(1.5);
            List<int[]> cases = (s.outil.get() == FloorSession.Outil.PORTE || s.outil.get() == FloorSession.Outil.PIPETTE
                    || s.rectangle.get()) ? List.of(new int[]{survolX, survolY}) : s.pinceau(survolX, survolY);
            for (int[] p : cases) {
                if (!d.dansPlan(p[0], p[1])) continue;
                losange(g, p[0], p[1], Math.max(0, d.h[p[0]][p[1]]), 0, false, true);
            }
        }

        if (grille && zoom >= 0.35) axes(g, d);
    }

    /** Face verticale le long de l'arete (x1,y1)-(x2,y2), du niveau h jusqu'a hautPx plus bas. */
    private void face(GraphicsContext g, double x1, double y1, double x2, double y2, double h, double hautPx, Color c) {
        double ax = ex(x1, y1), ay = ey(x1, y1, h), bx = ex(x2, y2), by = ey(x2, y2, h);
        g.setFill(c);
        g.fillPolygon(new double[]{ax, bx, bx, ax}, new double[]{ay, by, by + hautPx, ay + hautPx}, 4);
    }

    private void losange(GraphicsContext g, int x, int y, double z, double r, boolean remplir, boolean contour) {
        polygoneR(g, x + r, y + r, x + 1 - r, y + 1 - r, z, remplir, contour);
    }

    private void polygoneR(GraphicsContext g, double x1, double y1, double x2, double y2, double z, boolean remplir, boolean contour) {
        double[] px = {ex(x1, y1), ex(x2, y1), ex(x2, y2), ex(x1, y2)};
        double[] py = {ey(x1, y1, z), ey(x2, y1, z), ey(x2, y2, z), ey(x1, y2, z)};
        if (remplir) g.fillPolygon(px, py, 4);
        if (contour) g.strokePolygon(px, py, 4);
    }

    private void polygone(GraphicsContext g, double[] fx, double[] fy, double z, boolean remplir) {
        double[] px = new double[fx.length], py = new double[fx.length];
        for (int i = 0; i < fx.length; i++) { px[i] = ex(fx[i], fy[i]); py[i] = ey(fx[i], fy[i], z); }
        if (remplir) g.fillPolygon(px, py, fx.length);
        g.strokePolygon(px, py, fx.length);
    }

    /** Un mobi : bloc [x, x+ex] x [y, y+ey] de l'altitude z a z + hauteur. */
    /** Couleur par type : violet pour les wired, sinon une teinte stable par type. */
    static Color couleur(FloorSession.Mobi m) {
        return m.wired ? Color.web("#8e44ad") : Color.hsb((m.typeId * 47L) % 360, 0.42, 0.86);
    }

    private void bloc(GraphicsContext g, FloorSession.Mobi m, Color c, boolean danger, double a) {
        double r = 0.08;
        double x1 = m.x + r, y1 = m.y + r, x2 = m.x + m.ex - r, y2 = m.y + m.ey - r;
        double z = m.z, haut = Math.max(0.04, m.haut);
        double hp = haut * hz();
        if (hp < 2) hp = 2;
        Color cg = c.deriveColor(0, 1, 0.80, a), cd = c.deriveColor(0, 1, 0.64, a);
        // faces avant
        double[] gx = {ex(x1, y2), ex(x2, y2), ex(x2, y2), ex(x1, y2)};
        double[] gy = {ey(x1, y2, z) - hp, ey(x2, y2, z) - hp, ey(x2, y2, z), ey(x1, y2, z)};
        g.setFill(cg); g.fillPolygon(gx, gy, 4);
        double[] dx = {ex(x2, y2), ex(x2, y1), ex(x2, y1), ex(x2, y2)};
        double[] dy = {ey(x2, y2, z) - hp, ey(x2, y1, z) - hp, ey(x2, y1, z), ey(x2, y2, z)};
        g.setFill(cd); g.fillPolygon(dx, dy, 4);
        double[] tx = {ex(x1, y1), ex(x2, y1), ex(x2, y2), ex(x1, y2)};
        double[] ty = {ey(x1, y1, z) - hp, ey(x2, y1, z) - hp, ey(x2, y2, z) - hp, ey(x1, y2, z) - hp};
        g.setFill(c.deriveColor(0, 1, 1, a)); g.fillPolygon(tx, ty, 4);
        g.setStroke(danger ? Color.web("#d0302a") : c.darker());
        g.setLineWidth(danger ? 2 : 0.7);
        g.strokePolygon(tx, ty, 4);
        if (danger) { g.strokePolygon(gx, gy, 4); g.strokePolygon(dx, dy, 4); }
    }

    /** Icone officielle posee au centre du dessus du bloc, a l'echelle du zoom. */
    private void icone(GraphicsContext g, FloorSession.Mobi m, double alpha) {
        javafx.scene.image.Image img = IconesMobis.pour(m.classe, m.revision);
        if (img == null || img.getWidth() <= 0) return;
        double z = m.z + Math.max(0.04, m.haut);
        double cx = ex(m.x + m.ex / 2.0, m.y + m.ey / 2.0), cy = ey(m.x + m.ex / 2.0, m.y + m.ey / 2.0, z);
        double largeurMax = (m.ex + m.ey) * hl() * 0.8;
        double k = Math.min(zoom * 0.9, largeurMax / img.getWidth());
        double w = img.getWidth() * k, h = img.getHeight() * k;
        if (w < 6) return;
        double av = g.getGlobalAlpha();
        g.setGlobalAlpha(alpha);
        g.drawImage(img, cx - w / 2, cy - h * 0.8, w, h);
        g.setGlobalAlpha(av);
    }

    /** Vues ouvertes, redessinees quand une icone arrive. */
    private static final List<java.lang.ref.WeakReference<FloorVue>> VUES = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static void redessinerTout() {
        javafx.application.Platform.runLater(() -> {
            for (java.lang.ref.WeakReference<FloorVue> r : VUES) {
                FloorVue v = r.get();
                if (v == null) VUES.remove(r); else v.dessiner();
            }
        });
    }

    /**
     * Cache des icones officielles :
     *   https://images.habbo.com/dcr/hof_furni/{revision}/{classe}_icon.png
     * (variante « classe*3 » : essai « classe_3 », puis « classe »). Une seule
     * file de telechargement, un echec n'est pas retente pendant la session.
     */
    static final class IconesMobis {
        private IconesMobis() { }
        private static final Map<String, javafx.scene.image.Image> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private static final Set<String> demandees = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static final java.util.concurrent.ExecutorService file =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "atelier-floor-icones");
                    t.setDaemon(true);
                    return t;
                });

        static javafx.scene.image.Image pour(String classe, int revision) {
            if (classe == null || revision <= 0) return null;
            String cle = revision + "/" + classe;
            javafx.scene.image.Image i = cache.get(cle);
            if (i != null || !demandees.add(cle)) return i;
            file.submit(() -> {
                for (String nom : noms(classe)) {
                    try {
                        javafx.scene.image.Image img = new javafx.scene.image.Image(
                                "https://images.habbo.com/dcr/hof_furni/" + revision + "/" + nom + "_icon.png", false);
                        if (!img.isError() && img.getWidth() > 0) { cache.put(cle, img); redessinerTout(); return; }
                    } catch (Throwable ignored) { }
                }
            });
            return null;
        }

        /** Noms de fichier a essayer pour une classe. */
        static List<String> noms(String classe) {
            int e = classe.indexOf('*');
            if (e < 0) return List.of(classe);
            return List.of(classe.substring(0, e) + "_" + classe.substring(e + 1), classe.substring(0, e));
        }
    }

    private void porte(GraphicsContext g, FloorModele d) {
        int x = d.porteX, y = d.porteY;
        double z = Math.max(0, d.at(x, y));
        boolean ok = d.existe(x, y);
        Color c = ok ? Color.web("#1f9d55") : Color.web("#d0302a");
        g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.45));
        g.setStroke(c);
        g.setLineWidth(2);
        losange(g, x, y, z, 0.12, true, true);
        int[] v = FloorModele.vecteur(d.porteDir);
        double cx = ex(x + 0.5, y + 0.5), cy = ey(x + 0.5, y + 0.5, z);
        double tx = ex(x + 0.5 + v[0] * 0.75, y + 0.5 + v[1] * 0.75), ty = ey(x + 0.5 + v[0] * 0.75, y + 0.5 + v[1] * 0.75, z);
        g.setLineWidth(Math.max(1.5, Math.min(3, zoom * 1.5)));
        g.strokeLine(cx, cy, tx, ty);
        double ang = Math.atan2(ty - cy, tx - cx), t = Math.max(4, Math.min(9, zoom * 5));
        g.strokeLine(tx, ty, tx - t * Math.cos(ang - 0.5), ty - t * Math.sin(ang - 0.5));
        g.strokeLine(tx, ty, tx - t * Math.cos(ang + 0.5), ty - t * Math.sin(ang + 0.5));
        g.setFill(c.darker());
        g.setFont(Font.font(null, FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        g.fillText("Porte", cx, cy - hh() * 0.7);
    }

    private void axes(GraphicsContext g, FloorModele d) {
        g.setFont(Font.font(Math.max(9, Math.min(12, 7 + zoom * 2))));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFill(Color.web("#8a7d62"));
        int pas = zoom < 0.8 ? 10 : 5;
        for (int x = 0; x < d.largeur; x += pas) g.fillText("x" + x, ex(x + 0.5, -0.8), ey(x + 0.5, -0.8, 0));
        for (int y = 0; y < d.longueur; y += pas) g.fillText("y" + y, ex(-0.8, y + 0.5), ey(-0.8, y + 0.5, 0));
    }
}

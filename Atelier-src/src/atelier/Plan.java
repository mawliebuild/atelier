package atelier;

import game.FloorState;
import gearth.extensions.parsers.HFloorItem;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Carte de la salle, en vue isometrique comme dans le jeu.
 *
 * Chaque case est un losange : x descend vers la droite, y descend vers la
 * gauche. Le sol est dessine a plat (la hauteur du sol ne fait que l'assombrir)
 * pour que le survol tombe toujours sur la bonne case ; les mobis sont un
 * losange plus petit, teinte selon l'altitude du sommet de la pile (bleu = bas,
 * rouge = haut), violet pour les wired.
 *
 *   molette        zoom autour du pointeur
 *   glisser        deplacer la carte
 *   Maj + glisser  choisir un rectangle -> Zone.definir (ou mode « Sélection »)
 *   clic           en mode clic (voir activerClics), prevenir l'outil appelant
 *
 * Composant reutilisable : chaque outil peut en poser un. Il se relit tout seul
 * chaque seconde tant qu'il est affiche, et ne redessine que si la salle change.
 */
public class Plan extends VBox {

    public enum Mode { DEPLACER, SELECTION, CLIC }

    /** Taille d'une demi-case a zoom 1, en pixels. */
    private static final double DEMI_L = 16, DEMI_H = 8;
    private static final double HAUTEUR_CARTE = 300;

    // ---------------------------------------------------------- instantane

    /** Ce qu'on sait de la salle a un instant donne (lu hors fil JavaFX). */
    static final class Instantane {
        int largeur, longueur, salleId;
        int[][] sol;          // hauteur du sol, -1 = pas de case
        int[][] nombre;       // mobis qui couvrent la case
        double[][] sommet;    // altitude du sommet de la pile, NaN si rien
        boolean[][] wired, autres;
        double altMax;
        long signature;
        boolean dansPlan(int x, int y) { return x >= 0 && y >= 0 && x < largeur && y < longueur; }
        boolean caseValide(int x, int y) { return dansPlan(x, y) && sol[x][y] >= 0; }
    }

    /** Lit la salle courante ; null hors salle. Jamais d'exception. */
    static Instantane lire() {
        try {
            FloorState s = Salle.etat();
            if (s == null) return null;
            int w = s.getFloorplanWidth(), h = s.getFloorplanHeight();
            if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;
            Instantane i = new Instantane();
            i.largeur = w; i.longueur = h;
            try { i.salleId = s.getRoomId(); } catch (Throwable ignored) { }
            i.sol = new int[w][h];
            i.nombre = new int[w][h];
            i.sommet = new double[w][h];
            i.wired = new boolean[w][h];
            i.autres = new boolean[w][h];
            long sig = 17L * w + 31L * h + i.salleId;
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++) {
                    int hs = -1;
                    try {
                        char c = s.floorHeight(x, y);
                        if (c != 'x' && c != 'X' && c != 0) hs = extension.tools.PresetUtils.heightFromChar(c);
                    } catch (Throwable ignored) { }
                    i.sol[x][y] = hs;
                    i.sommet[x][y] = Double.NaN;
                    sig = sig * 31 + hs;
                }
            Map<Integer, Boolean> estWired = new HashMap<>();
            for (HFloorItem it : Salle.sols()) {
                try {
                    int x0 = it.getTile().getX(), y0 = it.getTile().getY();
                    double z = it.getTile().getZ();
                    double haut = z + Salle.hauteur(it);
                    int[] e = Salle.emprise(it);
                    boolean wd = estWired.computeIfAbsent(it.getTypeId(),
                            t -> Wired.estWired(Salle.classe(t, false)));
                    for (int dx = 0; dx < e[0]; dx++)
                        for (int dy = 0; dy < e[1]; dy++) {
                            int x = x0 + dx, y = y0 + dy;
                            if (!i.dansPlan(x, y)) continue;
                            i.nombre[x][y]++;
                            if (Double.isNaN(i.sommet[x][y]) || haut > i.sommet[x][y]) i.sommet[x][y] = haut;
                            if (wd) i.wired[x][y] = true; else i.autres[x][y] = true;
                        }
                    if (haut > i.altMax) i.altMax = haut;
                    sig = sig * 31 + it.getId();
                    sig = sig * 31 + x0 * 1000 + y0;
                    sig = sig * 31 + Double.hashCode(haut);
                    sig = sig * 31 + Salle.rotation(it) + it.getTypeId() * 8L + e[0] * 64L + e[1];
                } catch (Throwable ignored) { }
            }
            i.signature = sig;
            return i;
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------- etat

    private final Canvas canvas = new Canvas(350, HAUTEUR_CARTE);
    private final Pane cadre = new Pane(canvas);
    private final Label survol = Ui.discret("Survole la carte pour lire une case.");
    private final Label legende = new Label();
    private final CheckBox grille = new CheckBox("Grille et coordonnées");
    private final ToggleGroup modes = new ToggleGroup();
    private final ToggleButton bDeplacer = new ToggleButton("Déplacer");
    private final ToggleButton bSelection = new ToggleButton("Sélection");
    private ToggleButton bClic;
    private final javafx.scene.layout.FlowPane barre;

    private Instantane donnees;
    private double zoom = 1, ox = 175, oy = 20;
    private int salleCentree = Integer.MIN_VALUE;
    private volatile boolean lectureEnCours = false;

    private int survolX = -1, survolY = -1;
    private double appuiX, appuiY, dernierX, dernierY;
    private boolean glisse, enSelection;
    private int selX1 = -1, selY1, selX2, selY2;

    private BiConsumer<Integer, Integer> surClic;

    /** Reperes poses par les outils (points A et B de la mesure...). */
    private static final class Marque {
        final int x, y; final Color couleur;
        Marque(int x, int y, Color c) { this.x = x; this.y = y; couleur = c; }
    }
    private final Map<String, Marque> marques = new LinkedHashMap<>();

    // ----------------------------------------------------------------- UI

    public Plan() {
        super(6);
        setFillWidth(true);

        bDeplacer.setToggleGroup(modes);
        bSelection.setToggleGroup(modes);
        bDeplacer.setSelected(true);
        bDeplacer.setUserData(Mode.DEPLACER);
        bSelection.setUserData(Mode.SELECTION);
        // Toujours un mode choisi : un second clic ne doit pas tout desactiver.
        modes.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) a.setSelected(true);
        });
        bDeplacer.setTooltip(new Tooltip("Glisser déplace la carte. Maj + glisser choisit une zone."));
        bSelection.setTooltip(new Tooltip("Glisser choisit la zone de travail (rectangle de cases)."));

        Button recentrer = new Button("Recentrer");
        recentrer.setOnAction(e -> { recentrer(); dessiner(); });
        barre = Ui.ligne(bDeplacer, bSelection, recentrer);

        grille.selectedProperty().addListener((o, a, b) -> dessiner());

        cadre.setPrefSize(350, HAUTEUR_CARTE);
        cadre.setMinSize(0, HAUTEUR_CARTE);
        cadre.setMaxWidth(Double.MAX_VALUE);
        cadre.setStyle("-fx-border-color: #b9ad8f; -fx-border-width: 1;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(cadre.widthProperty());
        clip.heightProperty().bind(cadre.heightProperty());
        cadre.setClip(clip);
        canvas.widthProperty().bind(cadre.widthProperty());
        canvas.heightProperty().bind(cadre.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> {
            // Garder le meme point au centre quand la largeur change.
            ox += (b.doubleValue() - a.doubleValue()) / 2;
            dessiner();
        });
        canvas.heightProperty().addListener((o, a, b) -> dessiner());

        survol.setWrapText(true);
        legende.setWrapText(true);
        legende.setStyle("-fx-font-size: 10px; -fx-opacity: 0.7;");
        legende.setText("Couleur des mobis : bleu = bas, rouge = haut · violet = wired.");

        getChildren().addAll(barre, cadre, survol, Ui.ligne(grille), legende);

        brancherSouris();
        Zone.ecouter(this::dessiner);

        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> tic()));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        Platform.runLater(this::tic);
    }

    /**
     * Ajoute un troisieme mode : en le choisissant, un clic sur une case est
     * transmis a l'outil (x, y) sur le fil JavaFX.
     */
    public void activerClics(String libelle, BiConsumer<Integer, Integer> c) {
        surClic = c;
        if (bClic == null) {
            bClic = new ToggleButton(libelle);
            bClic.setUserData(Mode.CLIC);
            bClic.setToggleGroup(modes);
            barre.getChildren().add(2, bClic);
        } else bClic.setText(libelle);
    }

    public Mode mode() {
        Toggle t = modes.getSelectedToggle();
        return t == null ? Mode.DEPLACER : (Mode) t.getUserData();
    }

    public void choisirMode(Mode m) {
        if (m == Mode.CLIC && bClic != null) bClic.setSelected(true);
        else if (m == Mode.SELECTION) bSelection.setSelected(true);
        else bDeplacer.setSelected(true);
    }

    public void marquer(String nom, int x, int y, Color c) { marques.put(nom, new Marque(x, y, c)); dessiner(); }
    public void retirerMarque(String nom) { if (marques.remove(nom) != null) dessiner(); }
    public void effacerMarques() { marques.clear(); dessiner(); }

    /** Le dernier instantane lu (peut etre null). */
    Instantane donnees() { return donnees; }

    /** Relit la salle tout de suite (sinon c'est fait chaque seconde). */
    public void rafraichir() { lectureEnCours = false; tic(); }

    /** Vrai si le noeud est reellement a l'ecran (fenetre ouverte, volet choisi). */
    public static boolean estAffiche(Node n) {
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null
                || !n.getScene().getWindow().isShowing()) return false;
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    private void tic() {
        if (lectureEnCours || !estAffiche(this)) return;
        lectureEnCours = true;
        Salle.tache("plan", () -> {
            Instantane i = null;
            try { i = lire(); } catch (Throwable ignored) { }
            final Instantane lu = i;
            Platform.runLater(() -> {
                lectureEnCours = false;
                appliquer(lu);
            });
        });
    }

    private void appliquer(Instantane i) {
        Instantane avant = donnees;
        if (i == null && avant == null) { dessiner(); majSurvol(); return; }
        if (i != null && avant != null && i.signature == avant.signature) return;
        donnees = i;
        if (i != null && (i.salleId != salleCentree || avant == null
                || avant.largeur != i.largeur || avant.longueur != i.longueur)) {
            salleCentree = i.salleId;
            recentrer();
        }
        if (i != null)
            legende.setText("Couleur des mobis : bleu = bas, rouge = haut (max "
                    + fmt(i.altMax) + ") · violet = wired.");
        dessiner();
        majSurvol();
    }

    // ---------------------------------------------------------- geometrie

    private double hl() { return DEMI_L * zoom; }
    private double hh() { return DEMI_H * zoom; }
    private double ecranX(double fx, double fy) { return ox + (fx - fy) * hl(); }
    private double ecranY(double fx, double fy) { return oy + (fx + fy) * hh(); }

    /** Case sous le point ecran (px, py) : {x, y}. */
    private int[] caseSous(double px, double py) {
        double a = (px - ox) / hl(), b = (py - oy) / hh();
        return new int[]{(int) Math.floor((a + b) / 2), (int) Math.floor((b - a) / 2)};
    }

    /** Ajuste zoom et decalage pour que tout le plan tienne dans le cadre. */
    public void recentrer() {
        Instantane d = donnees;
        double W = Math.max(50, canvas.getWidth()), H = Math.max(50, canvas.getHeight());
        if (d == null) { zoom = 1; ox = W / 2; oy = 20; return; }
        double uMin = Double.MAX_VALUE, uMax = -Double.MAX_VALUE, vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE;
        for (int x = 0; x < d.largeur; x++)
            for (int y = 0; y < d.longueur; y++) {
                if (d.sol[x][y] < 0) continue;
                uMin = Math.min(uMin, x - y - 1); uMax = Math.max(uMax, x - y + 1);
                vMin = Math.min(vMin, x + y);     vMax = Math.max(vMax, x + y + 2);
            }
        if (uMin == Double.MAX_VALUE) { uMin = -d.longueur; uMax = d.largeur; vMin = 0; vMax = d.largeur + d.longueur; }
        double marge = 24;
        zoom = Math.min((W - marge) / ((uMax - uMin) * DEMI_L), (H - marge) / ((vMax - vMin) * DEMI_H));
        zoom = Math.max(0.15, Math.min(6, zoom));
        ox = W / 2 - (uMin + uMax) / 2 * hl();
        oy = H / 2 - (vMin + vMax) / 2 * hh();
    }

    // ------------------------------------------------------------- souris

    private void brancherSouris() {
        canvas.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            double a = (e.getX() - ox) / hl(), b = (e.getY() - oy) / hh();
            zoom *= e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
            zoom = Math.max(0.15, Math.min(8, zoom));
            ox = e.getX() - a * hl();
            oy = e.getY() - b * hh();
            dessiner();
            e.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            appuiX = dernierX = e.getX();
            appuiY = dernierY = e.getY();
            glisse = false;
            enSelection = e.isShiftDown() || mode() == Mode.SELECTION;
            if (enSelection) {
                int[] c = caseSous(e.getX(), e.getY());
                selX1 = selX2 = c[0]; selY1 = selY2 = c[1];
            }
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isPrimaryButtonDown()) return;
            if (Math.abs(e.getX() - appuiX) + Math.abs(e.getY() - appuiY) > 4) glisse = true;
            if (enSelection) {
                int[] c = caseSous(e.getX(), e.getY());
                selX2 = c[0]; selY2 = c[1];
            } else {
                ox += e.getX() - dernierX;
                oy += e.getY() - dernierY;
            }
            dernierX = e.getX(); dernierY = e.getY();
            survolA(e.getX(), e.getY());
            dessiner();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            Instantane d = donnees;
            if (enSelection) {
                enSelection = false;
                if (d != null) {
                    int ax = borne(selX1, d.largeur), ay = borne(selY1, d.longueur);
                    int bx = borne(selX2, d.largeur), by = borne(selY2, d.longueur);
                    Zone.definir(ax, ay, bx, by);
                }
                selX1 = -1;
                dessiner();
                return;
            }
            if (!glisse && mode() == Mode.CLIC && surClic != null) {
                int[] c = caseSous(e.getX(), e.getY());
                if (d != null && d.dansPlan(c[0], c[1])) {
                    try { surClic.accept(c[0], c[1]); } catch (Throwable ignored) { }
                }
            }
        });
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> { survolA(e.getX(), e.getY()); dessiner(); });
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> { survolX = survolY = -1; majSurvol(); dessiner(); });
    }

    private static int borne(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }

    private void survolA(double px, double py) {
        int[] c = caseSous(px, py);
        survolX = c[0]; survolY = c[1];
        majSurvol();
    }

    private void majSurvol() {
        Instantane d = donnees;
        if (d == null) { survol.setText(Salle.gp() == null ? "L'Atelier n'est pas encore prêt."
                : "Pas dans une salle (ou plan pas encore reçu)."); return; }
        if (survolX < 0 && survolY < 0) { survol.setText("Survole la carte pour lire une case."); return; }
        int x = survolX, y = survolY;
        if (!d.dansPlan(x, y)) { survol.setText("(" + x + ", " + y + ") · hors du plan"); return; }
        if (d.sol[x][y] < 0) { survol.setText("(" + x + ", " + y + ") · pas de case"); return; }
        StringBuilder sb = new StringBuilder("(" + x + ", " + y + ") · sol " + d.sol[x][y]);
        int n = d.nombre[x][y];
        if (n == 0) sb.append(" · aucun mobi");
        else sb.append(" · ").append(n).append(n > 1 ? " mobis" : " mobi")
                .append(" · sommet ").append(fmt(d.sommet[x][y]));
        survol.setText(sb.toString());
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.FRANCE, "%.2f", v).replaceAll("0+$", "").replaceAll(",$", "");
    }

    // ------------------------------------------------------------- dessin

    private void dessiner() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth(), H = canvas.getHeight();
        g.setFill(Color.web("#f4efe2"));
        g.fillRect(0, 0, W, H);
        Instantane d = donnees;
        if (d == null) {
            g.setFill(Color.web("#7a6f58"));
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(Salle.gp() == null ? "L'Atelier n'est pas encore prêt" : "Pas de salle à afficher", W / 2, H / 2);
            return;
        }
        boolean avecGrille = grille.isSelected();
        Color base = Color.web("#ddd3b8");
        double alt = Math.max(1, d.altMax);

        // Ordre du jeu : de l'arriere (x+y petit) vers l'avant.
        for (int s = 0; s <= d.largeur + d.longueur - 2; s++)
            for (int x = Math.max(0, s - d.longueur + 1); x <= Math.min(s, d.largeur - 1); x++) {
                int y = s - x;
                int hs = d.sol[x][y];
                if (hs < 0) continue;
                if (!visible(x, y, W, H)) continue;
                Color c = base.deriveColor(0, 1, Math.max(0.55, 1 - hs * 0.035), 1);
                losange(g, x, y, 0, c, null, 0);
                if (avecGrille) {
                    boolean fort = x % 5 == 0 || y % 5 == 0;
                    g.setStroke(fort ? Color.web("#8f8268") : Color.web("#c1b596"));
                    g.setLineWidth(fort ? 1 : 0.6);
                    contour(g, x, y, 0);
                } else if (zoom >= 0.7) {
                    g.setStroke(Color.web("#cbbf9f"));
                    g.setLineWidth(0.5);
                    contour(g, x, y, 0);
                }
                if (d.nombre[x][y] > 0) {
                    Color m;
                    if (d.wired[x][y] && !d.autres[x][y]) m = Color.web("#8e44ad");
                    else {
                        double t = Math.max(0, Math.min(1, d.sommet[x][y] / alt));
                        m = Color.hsb(215 - 215 * t, 0.62, 0.88);
                    }
                    Color bord = d.wired[x][y] && d.autres[x][y] ? Color.web("#8e44ad") : m.darker();
                    losange(g, x, y, 0.16, m, bord, d.wired[x][y] && d.autres[x][y] ? 1.6 : 0.8);
                    if (d.nombre[x][y] >= 2 && zoom >= 1.2) {
                        g.setFill(Color.WHITE);
                        g.setFont(Font.font(Math.min(11, 6 + zoom * 1.5)));
                        g.setTextAlign(TextAlignment.CENTER);
                        g.setTextBaseline(VPos.CENTER);
                        g.fillText(String.valueOf(d.nombre[x][y]), ecranX(x + 0.5, y + 0.5), ecranY(x + 0.5, y + 0.5));
                    }
                }
            }

        // Zone de travail.
        if (Zone.definie())
            rectangle(g, Zone.minX(), Zone.minY(), Zone.maxX(), Zone.maxY(),
                    Color.rgb(255, 160, 0, 0.22), Color.web("#e07b00"), false);
        // Rectangle en cours de choix.
        if (enSelection && selX1 >= 0)
            rectangle(g, Math.min(selX1, selX2), Math.min(selY1, selY2),
                    Math.max(selX1, selX2), Math.max(selY1, selY2),
                    Color.rgb(40, 120, 220, 0.20), Color.web("#2878dc"), true);

        // Case survolee.
        if (d.dansPlan(survolX, survolY)) {
            g.setStroke(Color.web("#1b1b1b"));
            g.setLineWidth(1.6);
            contour(g, survolX, survolY, 0);
        }

        // Reperes.
        g.setFont(Font.font(null, javafx.scene.text.FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        for (Map.Entry<String, Marque> e : marques.entrySet()) {
            Marque m = e.getValue();
            double cx = ecranX(m.x + 0.5, m.y + 0.5), cy = ecranY(m.x + 0.5, m.y + 0.5);
            double r = Math.max(3.5, Math.min(7, hh() * 0.6));
            g.setFill(m.couleur);
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.2);
            g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setFill(m.couleur.darker());
            g.fillText(e.getKey(), cx, cy - r - 1);
        }

        if (avecGrille) axes(g, d);
    }

    private boolean visible(int x, int y, double W, double H) {
        double cx = ecranX(x + 0.5, y + 0.5), cy = ecranY(x + 0.5, y + 0.5);
        return cx > -hl() * 2 && cx < W + hl() * 2 && cy > -hh() * 2 && cy < H + hh() * 2;
    }

    /** Losange de la case (x, y), retreci de r (0 = case entiere). */
    private void losange(GraphicsContext g, int x, int y, double r, Color fond, Color bord, double ep) {
        double[] px = {ecranX(x + r, y + r), ecranX(x + 1 - r, y + r), ecranX(x + 1 - r, y + 1 - r), ecranX(x + r, y + 1 - r)};
        double[] py = {ecranY(x + r, y + r), ecranY(x + 1 - r, y + r), ecranY(x + 1 - r, y + 1 - r), ecranY(x + r, y + 1 - r)};
        g.setFill(fond);
        g.fillPolygon(px, py, 4);
        if (bord != null) { g.setStroke(bord); g.setLineWidth(ep); g.strokePolygon(px, py, 4); }
    }

    private void contour(GraphicsContext g, int x, int y, double r) {
        double[] px = {ecranX(x + r, y + r), ecranX(x + 1 - r, y + r), ecranX(x + 1 - r, y + 1 - r), ecranX(x + r, y + 1 - r)};
        double[] py = {ecranY(x + r, y + r), ecranY(x + 1 - r, y + r), ecranY(x + 1 - r, y + 1 - r), ecranY(x + r, y + 1 - r)};
        g.strokePolygon(px, py, 4);
    }

    private void rectangle(GraphicsContext g, int x1, int y1, int x2, int y2, Color fond, Color bord, boolean tirets) {
        double[] px = {ecranX(x1, y1), ecranX(x2 + 1, y1), ecranX(x2 + 1, y2 + 1), ecranX(x1, y2 + 1)};
        double[] py = {ecranY(x1, y1), ecranY(x2 + 1, y1), ecranY(x2 + 1, y2 + 1), ecranY(x1, y2 + 1)};
        g.setFill(fond);
        g.fillPolygon(px, py, 4);
        g.setStroke(bord);
        g.setLineWidth(2);
        if (tirets) g.setLineDashes(5, 4);
        g.strokePolygon(px, py, 4);
        g.setLineDashes((double[]) null);
    }

    /** Numeros d'axes tous les 5 : x le long du bord haut-droit, y le long du bord haut-gauche. */
    private void axes(GraphicsContext g, Instantane d) {
        g.setFont(Font.font(Math.max(9, Math.min(12, 7 + zoom * 2))));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFill(Color.web("#5b5040"));
        for (int x = 0; x < d.largeur; x += 5) {
            int y0 = premiereCase(d, x, true);
            g.fillText("x" + x, ecranX(x + 0.5, y0 - 0.9), ecranY(x + 0.5, y0 - 0.9));
        }
        for (int y = 0; y < d.longueur; y += 5) {
            int x0 = premiereCase(d, y, false);
            g.fillText("y" + y, ecranX(x0 - 0.9, y + 0.5), ecranY(x0 - 0.9, y + 0.5));
        }
    }

    /** Premiere case existante sur la colonne x (ou la rangee y), pour coller le numero au plan. */
    private static int premiereCase(Instantane d, int v, boolean colonneX) {
        int n = colonneX ? d.longueur : d.largeur;
        for (int k = 0; k < n; k++) {
            int x = colonneX ? v : k, y = colonneX ? k : v;
            if (d.sol[x][y] >= 0) return k;
        }
        return 0;
    }
}

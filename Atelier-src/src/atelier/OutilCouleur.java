package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

/**
 * Regle la Couleur de Decor a partir d'un code hexadecimal.
 *
 * Le mobi « Couleur de Decor » (roombg_color) ne s'ajuste dans le jeu qu'avec
 * trois curseurs teinte / saturation / luminosite. On colle ici un code pris
 * dans n'importe quel selecteur de couleur, et l'Atelier envoie la teinte
 * correspondante.
 *
 * Le paquet SetRoomBackgroundColorData porte (id, teinte, saturation,
 * luminosite), les trois de 0 a 255 : teinte 255 = 360°, saturation et
 * luminosite 255 = 100%. La roue du mobi est en HSL.
 *
 * Le mobi est trouve tout seul dans la salle (roombg_color*) ; s'il y en a
 * plusieurs, le premier (le plus petit identifiant). Un reglage fait a la
 * main dans le jeu sur un autre mobi le designe a la place.
 */
public class OutilCouleur {

    private TextField code;
    private ColorPicker palette;
    private volatile boolean majEnCours = false;
    private Label etat;
    private boolean installe = false;

    private volatile int idMobi = -1;

    /**
     * Echelles du mobi, connues : les trois valeurs vont de 0 a 255.
     *   teinte      0 = 0°    255 = 360°
     *   saturation  0 = 0%    255 = 100%
     *   luminosite  0 = 0%    255 = 100%
     */
    private static final int MAX = 255;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        // fenetre Couleur de decor ouverte : le mobi Couleur de decor est mis en valeur dans le jeu
        MiseEnValeur.fournir("salle-couleur", () -> { int id = cible(); return id > 0 ? java.util.List.of("s" + id) : java.util.List.<String>of(); });
        code = new TextField();
        code.setPromptText("#ff56c2");
        code.setMaxWidth(Double.MAX_VALUE);
        code.textProperty().addListener((o, a, b) -> majApercu());
        // Entree applique la couleur tout de suite (filtre : rien ne peut l'avaler avant).
        code.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) { appliquer(); e.consume(); }
        });

        // Un vrai selecteur : un clic ouvre la palette et la roue de JavaFX,
        // comme sur un site. Il reste synchronise avec le champ hexadecimal.
        palette = new ColorPicker(Color.web("#ff56c2"));
        palette.setPrefWidth(56);
        // Choisir dans la palette change la couleur dans le jeu tout de suite.
        palette.valueProperty().addListener((o, a, c) -> {
            if (majEnCours || c == null) return;
            majEnCours = true;
            try { code.setText(enHexa(c)); } finally { majEnCours = false; }
            enDirect(c);
        });

        etat = Ui.etat();

        // Le champ s'etire, la palette garde sa taille ; dessous, ce que le mobi recoit.
        palette.setMinWidth(Region.USE_PREF_SIZE);
        palette.setStyle("-fx-color-label-visible: false;");   // la pastille seule, sans nom coupe
        HBox saisie = new HBox(8, Icones.petit(Icones.GOUTTE), code, palette);
        saisie.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(code, Priority.ALWAYS);
        Label valeurs = Ui.discret("");
        valeurs.setStyle("-fx-font-style: normal;");
        Runnable majValeurs = () -> {
            Color c = palette.getValue();
            if (c == null) { valeurs.setText(""); return; }
            double[] hsl = versHsl(c);
            valeurs.setText(String.format(java.util.Locale.FRANCE, "Teinte\u00a0%d° · saturation\u00a0%d\u00a0%% · luminosité\u00a0%d\u00a0%%",
                    Math.round(hsl[0]), Math.round(hsl[1] * 100), Math.round(hsl[2] * 100)));
        };
        palette.valueProperty().addListener((o, a, c) -> majValeurs.run());
        majValeurs.run();

        VBox v = new VBox(12,
                Ui.bloc("Couleur",
                        saisie, valeurs,
                        Ui.aide("Colle un code (#ff56c2) ou choisis dans la palette : "
                                + "la couleur change dans le jeu toute seule.")),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        installer();

        Tab t = new Tab("Couleur de décor", sp);
        t.setClosable(false);
        return t;
    }

    /** Un mobi Couleur de decor de la salle (le premier), ou null. */
    static gearth.extensions.parsers.HFloorItem trouver() {
        gearth.extensions.parsers.HFloorItem premier = null;
        for (gearth.extensions.parsers.HFloorItem it : Salle.sols()) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || !c.toLowerCase(java.util.Locale.ROOT).startsWith("roombg_color")) continue;
            if (premier == null || it.getId() < premier.getId()) premier = it;
        }
        return premier;
    }

    /** Le mobi vise : celui regle a la main s'il est encore la, sinon le premier trouve. */
    private int cible() {
        if (idMobi > 0 && Salle.sol(idMobi) != null) return idMobi;
        gearth.extensions.parsers.HFloorItem it = trouver();
        return it == null ? -1 : it.getId();
    }

    /** Un code complet tape ou colle : la palette suit et la couleur part dans le jeu. */
    private void majApercu() {
        if (majEnCours) return;
        String t = code.getText() == null ? "" : code.getText().trim();
        if (!t.matches("#?[0-9a-fA-F]{6}")) return;
        Color c = lire(t);
        if (c == null) return;
        majEnCours = true;
        try { palette.setValue(c); } finally { majEnCours = false; }
        enDirect(c);
    }

    /** Couleur depuis teinte (0-360), saturation et luminosite (0-1), au sens HSL. */
    static Color depuisHsl(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double hp = (h % 360) / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
        double m = l - c / 2;
        return Color.color(clamp(r + m), clamp(g + m), clamp(b + m));
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    // ------------------------------------------------------------ temps reel

    /** La derniere couleur voulue en direct ; un seul fil l'envoie, au plus toutes les 150 ms. */
    private volatile Color voulue = null;
    private volatile boolean envoiEnCours = false;

    private void enDirect(Color c) {
        voulue = c;
        if (envoiEnCours) return;
        envoiEnCours = true;
        Thread t = new Thread(() -> {
            Color tentee = null;               // la derniere couleur TENTEE (envoyee ou non)
            try {
                while (true) {
                    Color v = voulue;
                    if (v == null || v.equals(tentee)) break;
                    Salle.espacer();                 // rythme commun des envois
                    v = voulue;                      // la plus recente, apres l'attente
                    if (v == null) break;
                    tentee = v;
                    if (!envoyer(v, false)) break;   // echec : dit une fois, on s'arrete
                }
            } finally {
                envoiEnCours = false;
                Color v = voulue;
                // une NOUVELLE couleur arrivee pendant la fin de boucle : on repart
                if (v != null && !v.equals(tentee)) enDirect(v);
            }
        }, "atelier-couleur-direct");
        t.setDaemon(true);
        t.start();
    }

    /** #rrggbb a partir d'une couleur, pour remplir le champ. */
    private static String enHexa(Color c) {
        return String.format("#%02x%02x%02x",
                Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }

    // ------------------------------------------------------------ apprentissage

    private synchronized void installer() {
        if (installe) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 600 && !installe; i++) {
                brancher();
                if (installe) return;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-couleur");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void brancher() {
        if (installe) return;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOSERVER, "SetRoomBackgroundColorData", m -> {
                try { apprendre(m); } catch (Throwable ignored) { }
            });
            installe = true;
            Journal.debug("couleur de décor : écoute active.");
        } catch (Throwable t) {
            dire("Écoute indisponible : " + t);
        }
    }

    /**
     * Reconnait le paquet de reglage : quatre entiers, dont trois dans une plage
     * de couleur plausible. On en deduit l'objet et les echelles reellement
     * employees par le client.
     */
    private void apprendre(HMessage m) {
        int taille = m.getPacket().getBytesLength();
        if (taille < 18 || taille > 26) return;          // 4 entiers = 22 octets
        HPacket p = m.getPacket();

        int[] v = new int[4];
        try {
            for (int i = 0; i < 4; i++) v[i] = p.readInteger(6 + i * 4);
        } catch (Throwable e) { return; }

        // trois valeurs de couleur plausibles apres l'identifiant
        for (int i = 1; i < 4; i++)
            if (v[i] < 0 || v[i] > 360) return;
        if (v[0] <= 0) return;
        // seulement un vrai mobi Couleur de decor de la salle
        gearth.extensions.parsers.HFloorItem it = Salle.sol(v[0]);
        String c = it == null ? null : Salle.classe(it.getTypeId(), false);
        if (c == null || !c.toLowerCase(java.util.Locale.ROOT).startsWith("roombg_color")) return;

        idMobi = v[0];
        Journal.debug("couleur de décor apprise : id=" + v[0]
                + " h=" + v[1] + " s=" + v[2] + " l=" + v[3] + " taille=" + taille);
    }

    // ------------------------------------------------------------- application

    private void appliquer() {
        Color c = lire(code.getText());
        if (c == null) { dire("Code invalide — attendu : #ff56c2"); return; }
        envoyer(c, true);
    }

    /**
     * Envoie la couleur au mobi. Le changement se voit dans le jeu : pas de
     * message de reussite ; un echec est dit une fois dans la ligne d'etat.
     * @return true si le paquet est parti
     */
    private boolean envoyer(Color c, boolean parler) {
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { dire("L'Atelier n'est pas encore prêt."); return false; }
        int id = cible();
        if (id < 0) { dire("Pose un mobi Couleur de décor dans l'appart."); return false; }

        // HSL, pas HSB : le mobi suit la roue HSL. Les accesseurs de JavaFX
        // (getSaturation/getBrightness) donnent du HSB — pour #ff56c2 ils
        // annoncent 66 % de saturation la ou le HSL en demande 100.
        double[] hsl = versHsl(c);
        int h = (int) Math.round(hsl[0] * MAX / 360.0);
        int s = (int) Math.round(hsl[1] * MAX);
        int l = (int) Math.round(hsl[2] * MAX);

        boolean ok;
        try {
            ok = gp.sendToServer(new HPacket("SetRoomBackgroundColorData",
                    HMessage.Direction.TOSERVER, id, h, s, l));
        } catch (Throwable t) { ok = false; }
        if (!ok) { dire("Couleur non envoyée : la connexion au jeu a échoué."); return false; }
        dire("");                                   // efface une consigne restee affichee
        return true;
    }

    /**
     * Teinte, saturation et luminosite au sens HSL.
     * @return {teinte 0-360, saturation 0-1, luminosite 0-1}
     */
    static double[] versHsl(Color c) {
        double r = c.getRed(), v = c.getGreen(), b = c.getBlue();
        double max = Math.max(r, Math.max(v, b));
        double min = Math.min(r, Math.min(v, b));
        double l = (max + min) / 2.0;

        if (max == min) return new double[]{0, 0, l};       // gris

        double d = max - min;
        double s = (l > 0.5) ? d / (2.0 - max - min) : d / (max + min);

        double h;
        if (max == r)      h = ((v - b) / d + (v < b ? 6 : 0));
        else if (max == v) h = ((b - r) / d + 2);
        else               h = ((r - v) / d + 4);
        h *= 60;

        return new double[]{h, s, l};
    }

    /** Accepte #ff56c2, ff56c2, ou une couleur nommee. */
    private static Color lire(String t) {
        if (t == null) return null;
        String v = t.trim();
        if (v.isEmpty()) return null;
        if (!v.startsWith("#") && v.matches("[0-9a-fA-F]{6}")) v = "#" + v;
        try { return Color.web(v); } catch (Throwable e) { return null; }
    }

    private void dire(String s) {
        Platform.runLater(() -> etat.setText(s));
    }
}

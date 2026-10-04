package atelier;

import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Palette des « petits blocs » du catalogue BC (bc_block_small*1 a *69), dans
 * le meme ordre que dans le jeu : une case de couleur par bloc, 11 par ligne,
 * qui defile. Un clic choisit la couleur (case encadree).
 */
final class PaletteBlocs {

    /** Couleur de chaque petit bloc (index 0 = bc_block_small*1), lue dans la furnidata. */
    static final String[] COULEURS = {
            "f7ebbc", "ffd837", "ff9900", "ff99bc", "e14218", "672913", "007223", "92d13d", "ac5ab1", "835ab1",
            "5eaaf8", "abd0d2", "525252", "ffffff", "99ffcc", "666600", "6f3d0c", "778084", "bcc0c4", "a1e8fd",
            "2cbfff", "0074ff", "0000ff", "0000c8", "0085ff", "6685ff", "bcb7ff", "ddb6fe", "9c73ff", "683eff",
            "3f1bc7", "96008e", "de00d7", "fe72ff", "feb6fe", "f5bfe1", "ff549c", "e7005f", "ae0024", "ff3600",
            "ff7852", "f5d1b3", "fde1b1", "fea13d", "e76000", "8d1600", "513100", "ad8100", "fdd97e", "9abd00",
            "dbf96f", "bafa00", "00ba00", "007900", "2e632e", "00aa00", "52da50", "b6fa70", "8aae00", "0d380c",
            "005900", "00a943", "4efa99", "aef0d8", "00f8fd", "00e9e5", "008895", "004059", "336666"};

    static String classe(int numero) { return "bc_block_small*" + numero; }

    private static final int PAR_LIGNE = 11, COTE = 24;

    private final ScrollPane vue;
    private final List<Region> cases = new ArrayList<>();
    private int numero;
    private IntConsumer surChoix = n -> { };

    /** numero : 1 a 69. */
    PaletteBlocs(int numero) {
        this.numero = Math.max(1, Math.min(COULEURS.length, numero));
        GridPane g = new GridPane();
        g.setHgap(4); g.setVgap(4);
        g.setPadding(new Insets(4));
        for (int i = 0; i < COULEURS.length; i++) {
            final int n = i + 1;
            Region c = new Region();
            c.setMinSize(COTE, COTE); c.setPrefSize(COTE, COTE); c.setMaxSize(COTE, COTE);
            c.setCursor(Cursor.HAND);
            Tooltip.install(c, new Tooltip("Petit bloc " + n));
            c.setOnMouseClicked(e -> choisir(n));
            cases.add(c);
            g.add(c, i % PAR_LIGNE, i / PAR_LIGNE);
        }
        dessiner();
        vue = new ScrollPane(g);
        vue.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        vue.setPrefViewportHeight(3 * (COTE + 4) + 6);
        vue.setFitToWidth(true);
        vue.setStyle("-fx-background-color: #ECEAE0; -fx-border-color: #A7A196; -fx-border-radius: 4; -fx-background-radius: 4;");
    }

    Region vue() { return vue; }

    int numero() { return numero; }

    String classe() { return classe(numero); }

    void surChoix(IntConsumer c) { surChoix = c == null ? n -> { } : c; }

    void choisir(int n) {
        numero = Math.max(1, Math.min(COULEURS.length, n));
        dessiner();
        surChoix.accept(numero);
    }

    /** Comme dans le BC : la couleur, un liseré clair en haut ; la case choisie est encadree. */
    private void dessiner() {
        for (int i = 0; i < cases.size(); i++) {
            Color c = Color.web("#" + COULEURS[i]);
            String clair = toHex(c.interpolate(Color.WHITE, 0.35));
            boolean choisie = i + 1 == numero;
            cases.get(i).setStyle("-fx-background-color: " + clair + ", #" + COULEURS[i] + ";"
                    + " -fx-background-insets: 0, 5 0 0 0; -fx-background-radius: 3;"
                    + " -fx-border-color: " + (choisie ? "#1D1C19" : "#FFFFFF") + ";"
                    + " -fx-border-width: " + (choisie ? 2 : 1) + "; -fx-border-radius: 3;");
        }
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }
}

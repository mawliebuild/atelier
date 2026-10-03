package atelier;

import javafx.scene.Scene;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Les quelques regles de style propres au panneau des calques refait facon
 * Photoshop (icones compactes, lignes de calques, calques de base, glisser-
 * deposer). Elles completent styling.css sans le modifier : ajoutees a la
 * scene comme feuille « data: » (JavaFX 17 et plus), APRES la feuille de
 * l'Atelier. Memes couleurs que le reste du panneau (gris de la barre du
 * haut, bleu Habbo).
 */
final class CalqueStyle {

    private CalqueStyle() { }

    static final String CSS = String.join("\n",
            "/* icones compactes du panneau */",
            ".calques-icone {",
            "  -fx-background-color: #111111, #55585D, #34363A;",
            "  -fx-background-insets: 0, 1, 2 1 1 1; -fx-background-radius: 5, 4, 4;",
            "  -fx-min-width: 28; -fx-min-height: 28; -fx-pref-width: 28; -fx-pref-height: 28;",
            "  -fx-max-width: 28; -fx-max-height: 28; -fx-padding: 0; -fx-border-width: 0; -fx-cursor: hand;",
            "}",
            ".calques-icone:hover { -fx-background-color: #111111, #62656B, #45484D; }",
            ".calques-icone:selected, .calques-icone:selected:hover { -fx-background-color: #9FD2EA, #7AB6D3, #3E86AC; }",
            ".calques-icone:disabled { -fx-opacity: 0.4; }",
            ".calques-icone .icone { -fx-scale-x: 0.74; -fx-scale-y: 0.74; }",
            ".calques-bas {",
            "  -fx-background-color: #111111, #26282B; -fx-background-insets: 0, 1;",
            "  -fx-background-radius: 0 0 5 5, 0 0 4 4; -fx-padding: 3 4 3 4;",
            "}",
            ".calques-liste.calques-photoshop { -fx-background-radius: 5 5 0 0, 4 4 0 0; }",
            "/* une ligne de calque */",
            ".calques-liste .list-cell { -fx-padding: 2 4 2 2; }",
            ".calques-liste .list-cell:filled:selected .calques-nombre { -fx-background-color: #2B6688; -fx-text-fill: #FFFFFF; }",
            ".calques-liste .list-cell.calques-base-premiere { -fx-border-color: #47494D transparent transparent transparent; -fx-border-width: 1 0 0 0; }",
            ".calques-liste .list-cell.calques-depot { -fx-border-color: #9FD2EA transparent transparent transparent; -fx-border-width: 2 0 0 0; }",
            ".calques-liste .list-cell.calques-depot-bas { -fx-border-color: transparent transparent #9FD2EA transparent; -fx-border-width: 0 0 2 0; }",
            ".calques-base .calques-nom { -fx-font-style: italic; -fx-font-weight: normal; -fx-text-fill: #C9C6BC; }",
            ".calques-liste .list-cell:filled:selected .calques-base .calques-nom { -fx-text-fill: #FFFFFF; }",
            ".calques-cadenas {",
            "  -fx-background-color: transparent; -fx-padding: 0;",
            "  -fx-min-width: 20; -fx-min-height: 22; -fx-pref-width: 20; -fx-pref-height: 22;",
            "  -fx-cursor: hand; -fx-scale-x: 0.7; -fx-scale-y: 0.7;",
            "}",
            ".calques-cadenas.ouvert { -fx-opacity: 0.3; }",
            ".calques-cadenas.ouvert:hover { -fx-opacity: 0.8; }",
            ".calques-oeil.grise { -fx-opacity: 0.28; -fx-cursor: default; }",
            ".calques-renommer {",
            "  -fx-background-color: #111111, #34363A; -fx-background-insets: 0, 1;",
            "  -fx-text-fill: #F2EFE6; -fx-font-size: 12px; -fx-padding: 1 4 1 4;",
            "}",
            ".calques-selection { -fx-text-fill: #C9C6BC; -fx-font-size: 11px; }",
            "/* fenetres d'action (style Habbo clair) */",
            ".fenetre .button.calques-fleche { -fx-min-width: 64; -fx-font-size: 14px; }",
            ".fenetre .calques-valeur { -fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1D1C19; }"
    );

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));

    /** Ajoute ces regles a la scene (apres la feuille de l'Atelier). */
    static void appliquer(Scene s) {
        if (s == null) return;
        try { if (!s.getStylesheets().contains(URI)) s.getStylesheets().add(URI); }
        catch (Throwable t) { System.err.println("[Atelier] calques : style compact indisponible : " + t); }
    }
}

package atelier;

import javafx.scene.shape.SVGPath;

/**
 * Pictogrammes de l'Atelier, en traits.
 *
 * Grille de 24 pour la barre ; de 10 pour les boutons de fenetre, qui font
 * 20 px. Des SVGPath plutot que des caracteres Unicode : un glyphe depend de la
 * police installee et ne s'aligne jamais deux fois pareil. La couleur et
 * l'epaisseur du trait viennent de la feuille de style (classe « icone »).
 */
public final class Icones {

    public static final String MURS       = "M3 5h18v14H3z M8 9h8v6H8z";
    public static final String SALLE      = "M3 11l9-7 9 7 M5 10v10h14V10 M10 20v-6h4v6";
    public static final String APPARTS    = "M8 8h12v12H8z M4 16V4h12";
    public static final String WIRED      = "M13 2L4 14h7l-1 8 9-12h-7z";
    /** Une truelle sur une marche : les outils qui construisent. */
    public static final String CONSTRUCTION = "M3 21h6v-5h6v-5h6 M14 3l7 7 M17.5 6.5l-6 6";
    /** Un dallage isometrique : le plan de sol. */
    public static final String FLOOR      = "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5 M12 13v5";
    /** Une pousse a deux feuilles : les monster plants. */
    public static final String PLANTES    = "M12 21v-9 M12 12c0-4-3-7-8-7 0 4 3 7 8 7z M12 14c0-4 3-7 8-7 0 4-3 7-8 7z M7 21h10";
    /** Deux carres decales : dupliquer. */
    public static final String DUPLIQUER  = "M9 9h11v11H9z M5 15H4V4h11v1";
    /** Un axe pointille entre deux triangles : miroir / symetrie. */
    public static final String ESCALIER   = "M3 20h5v-4h4v-4h4V8h5";
    public static final String HAUTEUR    = "M12 3v18 M8 7l4-4 4 4 M8 17l4 4 4-4 M3 21h6 M15 21h6";
    public static final String PIVOTER    = "M20 12a8 8 0 1 1-2.34-5.66 M20 4v4h-4";
    public static final String MIROIR     = "M12 3v3 M12 9v3 M12 15v3 M12 21v0 M9 6L3 18h6z M15 6l6 12h-6z";
    /** Une courbe qui monte : patrimoine, valeur, investissements. */
    public static final String PATRIMOINE = "M3 17l6-6 4 4 8-8 M14 7h7v7 M3 21h18";
    public static final String INVENTAIRE ="M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z";
    public static final String REGLAGES   = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
            + " M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06"
            + "a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09"
            + "A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06"
            + "a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09"
            + "A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06"
            + "a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09"
            + "a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06"
            + "a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09"
            + "a1.65 1.65 0 0 0-1.51 1z";
    public static final String OEIL       = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0.01 0z";
    public static final String OEIL_FERME = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M4 20L20 4";
    public static final String PLUS       = "M12 5v14 M5 12h14";
    public static final String CURSEUR    = "M5 3l14 8-6 2-2 6z";
    /** Un losange quadrille : la grille des cases. */
    public static final String GRILLE     = "M12 3l9 9-9 9-9-9z M7.5 7.5l9 9 M16.5 7.5l-9 9";
    public static final String CADRE      = "M4 4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M4 8V4 M12 4h0 M20 12h0 M12 20h0 M4 12h0";
    public static final String REGLE      = "M3 17L17 3l4 4L7 21z M7 13l2 2 M10 10l2 2 M13 7l2 2";
    /** Un cadre photo (paysage) : la Galerie. */
    public static final String GALERIE    = "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6 M15.5 8.5a1.5 1.5 0 1 0 0.01 0z";
    public static final String CAPTURE    = "M4 8h3l2-3h6l2 3h3v11H4z M12 10.5a3.5 3.5 0 1 0 0.01 0z";
    public static final String LOUPE      = "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z M15.5 15.5L20 20";
    public static final String GOUTTE     = "M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z";
    public static final String LISTE      = "M8 6h12 M8 12h12 M8 18h12 M4 6h.01 M4 12h.01 M4 18h.01";
    public static final String DEPLACER   = "M12 3v18 M3 12h18 M9 6l3-3 3 3 M9 18l3 3 3-3 M6 9l-3 3 3 3 M18 9l3 3-3 3";
    public static final String ALIGNER    = "M3 9h5v5H3z M10 9h5v5h-5z M17 9h4v5h-4z M3 18h18";
    public static final String CADENAS    = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 7 0v3";
    public static final String ANNULER    = "M9 14L4 9l5-5 M4 9h11a5 5 0 0 1 0 10h-3";
    public static final String RETABLIR   = "M15 14l5-5-5-5 M20 9H9a5 5 0 0 0 0 10h3";
    /** Cadenas ouvert : calque deverrouille. */
    public static final String CADENAS_OUVERT = "M6 11h12v9H6z M8.5 11V8a3.5 3.5 0 0 1 6.9-0.8";
    /** Une poubelle : supprimer. */
    public static final String CORBEILLE  = "M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13 M10 11v6 M14 11v6";
    /** Une fleche qui descend sur deux feuillets : fusionner. */
    public static final String FUSIONNER  = "M12 2v8 M8.5 6.5L12 10l3.5-3.5 M3 13l9 4.5 9-4.5 M3 17.5l9 4.5 9-4.5";
    /** Deux feuillets empiles et un plus : nouveau calque. */
    public static final String CALQUE_NOUVEAU = "M3 9l9-5 9 5-9 5z M3 14l9 5 9-5 M18 15v6 M15 18h6";
    /** Une croix : vider la selection. */
    public static final String VIDER      = "M6 6l12 12 M18 6L6 18";
    /** Trois traits et des curseurs : les familles (filtres). */
    public static final String FILTRES    = "M4 6h16 M4 12h16 M4 18h16 M8 4v4 M15 10v4 M10 16v4";
    public static final String REDUIRE    = "M2 5h6";
    public static final String FERMER     = "M2 2l6 6 M8 2L2 8";

    private Icones() { }

    public static SVGPath trace(String chemin, String classe) {
        SVGPath p = new SVGPath();
        p.setContent(chemin);
        p.getStyleClass().addAll("icone", classe);
        return p;
    }
}

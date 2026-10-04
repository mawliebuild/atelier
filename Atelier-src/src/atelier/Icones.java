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
    /** Un carre rempli de petites cases : remplir une zone avec un mobi. */
    public static final String REMPLIR = "M4 4h16v16H4z M4 9.3h16 M4 14.6h16 M9.3 4v16 M14.6 4v16";
    /** Un interrupteur : changer l'etat des mobis. */
    public static final String ETAT = "M7 7h10a5 5 0 0 1 0 10H7A5 5 0 0 1 7 7z M16 9.5a2.5 2.5 0 1 0 0.01 0z";
    /** Un livre ouvert : la Documentation. */
    public static final String DOCUMENTATION = "M3 5c3-1 6-1 9 1v14c-3-2-6-2-9-1z M21 5c-3-1-6-1-9 1v14c3-2 6-2 9-1z";
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
    public static final String CRAYON     = "M4 20l4-1 11-11-3-3L5 16z M14 7l3 3";
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

    // --- ajouts (agent c) : boutons des fenetres claires
    /** Fleche qui tourne dans le sens inverse (anti-horaire) : pendant de PIVOTER. */
    public static final String PIVOTER_INVERSE = "M4 12a8 8 0 1 0 2.34-5.66 M4 4v4h4";
    /** Une fleche qui descend dans un plateau : enregistrer / telecharger. */
    public static final String ENREGISTRER = "M12 4v11 M7.5 10.5L12 15l4.5-4.5 M4 15v5h16v-5";
    /** Une prise : la connexion au jeu. */
    public static final String CONNEXION  = "M9 3v5 M15 3v5 M6 8h12v3a6 6 0 0 1-12 0z M12 17v4";
    /** Une fenetre avec sa barre : les fenetres de l'Atelier. */
    public static final String FENETRES   = "M3 5h18v14H3z M3 9h18";
    /** Un cylindre : les donnees (caches). */
    public static final String DONNEES    = "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3";
    /** Une etincelle : la mise en valeur. */
    public static final String ETINCELLE  = "M12 3v4 M12 17v4 M3 12h4 M17 12h4 M6 6l2.5 2.5 M15.5 15.5L18 18 M6 18l2.5-2.5 M15.5 8.5L18 6";
    /** Une page qui sort d'un presse-papiers : coller. */
    public static final String COLLER     = "M9 4h6v3H9z M7 5H5v16h14V5h-2 M9 12h6 M9 16h4";

    // --- ajouts (fenetres Salle, Apparts, Hauteur, Couleur, Escalier) ---
    /** Une dalle et une fleche qui descend : couvrir de dalles. */
    public static final String COUVRIR    = "M12 2v7 M9 6l3 3 3-3 M3 15l9-4.5 9 4.5-9 4.5z";
    /** Une dalle et une fleche qui remonte : ramasser. */
    public static final String RAMASSER   = "M12 9V2 M9 5l3-3 3 3 M3 15l9-4.5 9 4.5-9 4.5z";
    /** Une dalle barree : sans dalles. */
    public static final String SANS_DALLE = "M3 13l9-4.5 9 4.5-9 4.5z M4 4l16 16";
    /** Une dalle sous un mobi : avec dalles. */
    public static final String AVEC_DALLE = "M3 15l9-4.5 9 4.5-9 4.5z M9 4h6v6H9z";
    /** Une mire : choisir dans le jeu. */
    public static final String CIBLE      = "M12 2v5 M12 17v5 M2 12h5 M17 12h5 M12 8a4 4 0 1 0 0.01 0z";
    /** Interrupteur marche / arret. */
    public static final String MARCHE     = "M12 3v8 M7.5 6.5a7 7 0 1 0 9 0";
    /** Un carre plein : arreter. */
    public static final String ARRET      = "M7 7h10v10H7z";
    /** Deux fleches opposees : trier. */
    public static final String TRIER      = "M7 4v16 M4 7l3-3 3 3 M17 20V4 M14 17l3 3 3-3";

    private Icones() { }

    /**
     * Pictogramme d'un bouton de fenetre (fond clair), 16 px, classe
     * « icone-bouton ». Trait sombre par defaut, blanc sur un bouton
     * « primaire » ; une regle CSS sur .icone-bouton passe devant.
     */
    public static javafx.scene.Node petit(String chemin) {
        SVGPath p = trace(chemin, "icone-bouton");
        p.setStroke(javafx.scene.paint.Color.web("#5A564C"));
        p.setScaleX(0.68); p.setScaleY(0.68);
        return new javafx.scene.Group(p);
    }

    /** Pose un pictogramme devant le texte d'un bouton (blanc sur un bouton principal). */
    public static <B extends javafx.scene.control.Labeled> B sur(B b, String chemin) {
        javafx.scene.Node g = petit(chemin);
        SVGPath p = (SVGPath) ((javafx.scene.Group) g).getChildren().get(0);
        Runnable teinte = () -> p.setStroke(javafx.scene.paint.Color.web(
                b.getStyleClass().contains("primaire") ? "#FFFFFF" : "#5A564C"));
        teinte.run();
        b.getStyleClass().addListener((javafx.collections.ListChangeListener<String>) c -> teinte.run());
        b.setGraphic(g);
        b.setGraphicTextGap(6);
        return b;
    }

    /** Bouton a pictogramme seul, avec sa bulle (rapide : 150 ms). */
    public static javafx.scene.control.Button seul(String chemin, String bulle) {
        javafx.scene.control.Button b = new javafx.scene.control.Button();
        b.setGraphic(petit(chemin));
        javafx.scene.control.Tooltip t = new javafx.scene.control.Tooltip(bulle);
        t.setShowDelay(javafx.util.Duration.millis(150));
        b.setTooltip(t);
        b.setAccessibleText(bulle);
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return b;
    }

    public static SVGPath trace(String chemin, String classe) {
        SVGPath p = new SVGPath();
        p.setContent(chemin);
        p.getStyleClass().addAll("icone", classe);
        return p;
    }

    /**
     * Petit pictogramme pour un bouton des fenetres claires (taille en px,
     * 16 a 18 en general). Dans un Group : la mise a l'echelle compte dans la
     * taille du bouton. clair = trait blanc (bouton « primaire »), sinon gris fonce.
     */
    public static javafx.scene.Node petite(String chemin, double taille, boolean clair) {
        SVGPath p = trace(chemin, "icone-bouton");
        double k = taille / 24.0;
        p.setScaleX(k);
        p.setScaleY(k);
        p.setStyle("-fx-stroke: " + (clair ? "#FFFFFF" : "#5A564C") + ";");
        return new javafx.scene.Group(p);
    }
}

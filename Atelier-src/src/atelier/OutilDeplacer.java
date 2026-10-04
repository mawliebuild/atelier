package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import utils.WallPosition;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Deplace un mobi mural, avec les reglages d'un deplaceur d'affiches
 * classique, integre a l'Atelier.
 *
 * Une position de mur s'ecrit ":w=x,y l=offX,offY d" :
 *   - w=x,y        le pan de mur        -> bloc « Position »
 *   - l=offX,offY  le decalage dedans   -> bloc « Decalage »
 *   - d            la face, 'l' ou 'r'  -> bouton « Pivoter »
 * D'ou deux modes de deplacement, chacun avec son propre pas.
 */
public class OutilDeplacer {

    private Spinner<Integer> pasPosition, pasDecalage;
    private Label nomSel;
    private TextField codeSel;
    private RadioButton sInv, sBc, sInvBc;
    private Label etat;
    private VBox commandes, secondaires;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        // fenetre ouverte : le mobi mural choisi reste allume dans le jeu
        MiseEnValeur.fournir("murs-deplacer", () -> {
            SelectionMur.Mur m = SelectionMur.courant();
            return m == null ? java.util.List.<String>of() : java.util.List.of("m" + m.id);
        });
        nomSel = Ui.valeur("Aucun mur sélectionné");
        codeSel = new TextField();
        codeSel.setStyle("-fx-font-family: monospace;");
        codeSel.setMaxWidth(Double.MAX_VALUE);
        codeSel.setPromptText(":w=21,25 l=3,-257 r");
        codeSel.setOnAction(e -> appliquerCode());   // Entree applique le code
        codeSel.setTooltip(bulle("Position du mur. Modifie-la puis Entrée pour y envoyer le mur."));

        pasPosition = spin(1);
        pasDecalage = spin(1);

        ToggleGroup src = new ToggleGroup();
        sInv   = radio("Inventaire", src, true);
        sBc    = radio("BC", src, false);
        sInvBc = radio("Inv. puis BC", src, false);

        Button pivoter = new Button("Pivoter");
        pivoter.setMaxWidth(Double.MAX_VALUE);
        pivoter.setGraphic(Icones.petite(Icones.PIVOTER, 16, false));
        pivoter.setGraphicTextGap(7);
        pivoter.setTooltip(bulle("Passer le mobi sur l'autre face du mur (gauche ↔ droite)"));
        pivoter.setOnAction(e -> pivoter());

        Button dupliquer = new Button("Dupliquer ce mur");
        dupliquer.getStyleClass().add("primaire");
        dupliquer.setMaxWidth(Double.MAX_VALUE);
        dupliquer.setGraphic(Icones.petite(Icones.DUPLIQUER, 16, true));
        dupliquer.setGraphicTextGap(7);
        dupliquer.setTooltip(bulle("Poser une copie de ce mur à la position du code ci-dessus"));
        dupliquer.setOnAction(e -> dupliquer());

        etat = Ui.etat();

        // Deplacement : les deux croix, puis Pivoter qui est du meme ordre.
        commandes = new VBox(8,
                bloc("Position", pasPosition, true),
                bloc("Offset", pasDecalage, false),
                pivoter);

        // Source et Dupliquer vont ensemble : la source sert a la duplication.
        secondaires = new VBox(8,
                Ui.bloc("Source", Ui.ligne(sInv, sBc, sInvBc)),
                dupliquer);

        // Les prerequis disparaissent une fois remplis : le nom du mobi choisi
        // doit donc rester visible ici.
        VBox v = new VBox(12, Ui.bloc("Mur choisi", nomSel, codeSel), commandes, secondaires, etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(this::majAffichage);
        majAffichage();

        Tab t = new Tab("Déplacer un mur", sp);
        t.setClosable(false);
        return t;
    }

    /**
     * Les quatre sens, disposes comme dans le Poster mover d'origine.
     *
     * Position : grille 2x2 aux coins, chaque fleche pointant en diagonale.
     *   ↖ x-      ↗ y-
     *   ↙ y+      ↘ x+
     *
     * Offset : losange. Haut et bas sont DROITS, gauche et droite restent
     * en diagonale — c'est ce qu'on voit a l'ecran : offY monte et descend
     * a la verticale, tandis que offX longe le pan de mur, donc en biais.
     *        ↑ offY-
     *   ↖ offX-   ↘ offX+     (mur de gauche ; ↙ et ↗ sur le mur de droite)
     *        ↓ offY+
     */
    private VBox bloc(String titre, Spinner<Integer> pas, boolean surLePan) {
        GridPane croix = new GridPane();
        croix.setHgap(6); croix.setVgap(6);
        croix.setAlignment(Pos.CENTER);

        if (surLePan) {
            // 2x2 : haut-gauche, haut-droite, bas-gauche, bas-droite
            croix.add(fleche("↖", -1,  0, pas, true), 0, 0);
            croix.add(fleche("↗",  0, -1, pas, true), 1, 0);
            croix.add(fleche("↙",  0,  1, pas, true), 0, 1);
            croix.add(fleche("↘",  1,  0, pas, true), 1, 1);
        } else {
            // losange : haut, gauche, droite, bas
            croix.add(fleche("↑",  0, -1, pas, false), 1, 0);
            offGauche = fleche("↙", -1,  0, pas, false);
            offDroite = fleche("↗",  1,  0, pas, false);
            croix.add(offGauche, 0, 1);
            croix.add(offDroite, 2, 1);
            croix.add(fleche("↓",  0,  1, pas, false), 1, 2);
        }

        Region ecart = new Region();
        HBox.setHgrow(ecart, Priority.ALWAYS);
        HBox entete = new HBox(8, Ui.intitule(titre), ecart, new Label("Pas"), pas);
        entete.setAlignment(Pos.CENTER_LEFT);

        VBox b = new VBox(8, entete, croix);
        b.setPadding(new Insets(9, 10, 10, 10));
        b.getStyleClass().add("cadre");
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    /** Gauche et droite de l'Offset : leur biais suit le pan du mur choisi. */
    private Button offGauche, offDroite;

    /**
     * offX longe le pan. Constate en jeu : sur le mur de gauche (« l »),
     * gauche = ↖ et droite = ↘ ; sur le mur de droite (« r »), gauche = ↙ et
     * droite = ↗.
     */
    private void orienterOffset(String position) {
        if (offGauche == null) return;
        // la position peut finir par l'altitude (« ... r a=0 ») : on lit le cote du mur avant
        String p = SelectionMur.normaliser(position);
        boolean murDroite = p.endsWith(" r") || p.equals("r");
        // constate en jeu : mur de gauche ↖ / ↘, mur de droite ↙ / ↗
        offGauche.setText(murDroite ? "↙" : "↖");
        offDroite.setText(murDroite ? "↗" : "↘");
    }

    private Button fleche(String texte, int sx, int sy, Spinner<Integer> pas, boolean surLePan) {
        Button b = new Button(texte);
        b.getStyleClass().add("fleche");
        b.setPrefSize(46, 30);
        b.setOnAction(e -> deplacer(sx * pas.getValue(), sy * pas.getValue(), surLePan));
        return b;
    }

    private static Spinner<Integer> spin(int defaut) {
        Spinner<Integer> s = new Spinner<>(1, 500, defaut);
        s.setEditable(true);
        s.setPrefWidth(80);
        return s;
    }

    private void majAffichage() {
        SelectionMur.Mur m = SelectionMur.courant();
        boolean actif = (m != null);
        // Desactiver plutot que laisser cliquer dans le vide : l'interface dit
        // d'elle-meme qu'il faut d'abord choisir un mur.
        commandes.setDisable(!actif);
        secondaires.setDisable(!actif);
        codeSel.setDisable(!actif);
        if (!actif) {
            nomSel.setText("Aucun mur sélectionné");
            nomSel.getStyleClass().setAll("label", "etat-absent");
            codeSel.setText("");
            return;
        }
        // Nom relu a chaque fois : si la furnidata n'etait pas prete au clic,
        // m.nom vaut encore « type N ».
        String nom = (m.typeId >= 0) ? Salle.nom(m.typeId, true) : m.nom;
        nomSel.setText(nom + "   ·   id " + m.id);
        nomSel.getStyleClass().setAll("label", "etat-ok");
        codeSel.setText(m.position);
        orienterOffset(m.position);
    }

    // ------------------------------------------------------------ mouvement

    private void deplacer(int dx, int dy, boolean surLePan) {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null) { echec("Aucun mur sélectionné."); return; }
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }

        WallPosition p;
        try { p = new WallPosition(m.position); }
        catch (Throwable t) { echec("Position illisible : " + m.position); return; }

        // Correspondance reprise telle quelle du deplaceur d'affiches classique :
        //   Position : haut = y-, bas = y+, gauche = x-, droite = x+
        //   Offset   : haut = offY-, bas = offY+, gauche = offX-, droite = offX+
        // Ce qui trompait, ce n'etait pas les axes mais la disposition en croix :
        // sur une grille isometrique, ces quatre sens sont des DIAGONALES a
        // l'ecran, d'ou les boutons en losange ci-dessous.
        // Correspondance classique, telle quelle : haut = y-, bas = y+,
        // gauche = x-, droite = x+. L'adaptation selon la face du mur ne vaut que
        // pour l'Aligner, qui pose des copies en ligne.
        String cible = surLePan
                ? position(p.getX() + dx, p.getY() + dy,
                           p.getOffsetX(), p.getOffsetY(), p.getDirection())
                : position(p.getX(), p.getY(),
                           p.getOffsetX() + dx, p.getOffsetY() + dy, p.getDirection());

        envoyer(gp, m, cible);
    }

    /** Deplace le mur selectionne exactement au code saisi : copier-coller de position. */
    private void appliquerCode() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null) { echec("Aucun mur sélectionné."); return; }
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }
        String code = codeSel.getText() == null ? "" : codeSel.getText().trim();
        try { new WallPosition(code); }
        catch (Throwable t) {
            echec("Code invalide : « " + code + " » (attendu : :w=x,y l=oX,oY r)");
            return;
        }
        envoyer(gp, m, code);
    }

    /** Pose une copie du mur selectionne a la position du code. */
    private void dupliquer() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null) { echec("Aucun mur sélectionné."); return; }
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }
        if (m.typeId < 0) {
            echec("Type du mur inconnu : refais un Option + clic sur le mur.");
            return;
        }
        String code = codeSel.getText() == null ? "" : codeSel.getText().trim();
        try { new WallPosition(code); }
        catch (Throwable t) { echec("Code invalide : « " + code + " »"); return; }

        PoseMur.Source src = sBc.isSelected() ? PoseMur.Source.BC
                : sInvBc.isSelected() ? PoseMur.Source.INVENTAIRE_PUIS_BC
                : PoseMur.Source.INVENTAIRE;
        PoseMur.Resultat r = PoseMur.poser(gp, m.typeId, m.etat, code, src,
                new java.util.HashSet<>());
        if (r.ok) reussi("Copie posée " + r.detail + ".");
        else echec("Échec de la copie : " + r.detail + ".");
    }

    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    private void pivoter() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null) { echec("Aucun mur sélectionné."); return; }
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }
        try {
            WallPosition p = new WallPosition(m.position);
            char autre = (p.getDirection() == 'l') ? 'r' : 'l';
            envoyer(gp, m, position(p.getX(), p.getY(),
                    p.getOffsetX(), p.getOffsetY(), autre));
        } catch (Throwable t) { echec("Position illisible."); }
    }

    private void envoyer(GPresets gp, SelectionMur.Mur m, String cible) {
        // un mobi d'un calque verrouille ne bouge pas
        String v = Groupes.refusVerrouMobis(List.of(), List.of(m.id));
        if (v != null) { echec(v); return; }
        gp.sendToServer(new HPacket("MoveWallItem", HMessage.Direction.TOSERVER, m.id, cible));
        // On tient la position a jour localement : les clics s'enchainent sans
        // attendre la confirmation du serveur.
        SelectionMur.majPosition(cible);
        note("");   // deplacement eclair, deja visible dans le jeu : pas de message
    }

    private static String position(int x, int y, int ox, int oy, char dir) {
        return String.format(java.util.Locale.ROOT, ":w=%d,%d l=%d,%d %c", x, y, ox, oy, dir);
    }

    // ---------------------------------------------------------------- outils

    private static Tooltip bulle(String s) { return CalqueFenetre.bulle(s); }

    private static Label gras(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private void note(String s) {
        Platform.runLater(() -> etat.setText(s));
    }

    /** Resultat rate : ligne d'etat + Journal (une seule fois, meme si le texte ne dit pas « échec »). */
    private void echec(String s) {
        note(s);
        if (Journal.genre(s) != Journal.Genre.ERREUR) Journal.erreur(s);
    }

    /** Resultat reussi : ligne d'etat + Journal. */
    private void reussi(String s) {
        note(s);
        if (Journal.genre(s) != Journal.Genre.SUCCES) Journal.succes(s);
    }
}

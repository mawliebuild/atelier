package atelier;

import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Deplace un mobi mural, avec les reglages d'un deplaceur d'affiches
 * classique, integre a l'Atelier.
 *
 * Une position de mur s'ecrit ":w=x,y l=offX,offY d" :
 *   - w=x,y        le pan de mur        -> bloc « Position »
 *   - l=offX,offY  le decalage dedans   -> bloc « Decalage »
 *   - d            la face, 'l' ou 'r'  -> bouton « Pivoter »
 * D'ou deux modes de deplacement, chacun avec son propre pas.
 *
 * Plusieurs muraux a la fois : fenetre ouverte, chaque mural clique dans le
 * jeu s'ajoute a la selection (ou s'en retire) ; avec « Tous ceux de ce type »,
 * tous les muraux de la salle du meme type (meme poster) viennent ensemble.
 * Fleches, Pivoter, code et Dupliquer valent alors pour toute la selection :
 * chaque mural du meme decalage, au rythme Salle.espacer(), un seul Ctrl+Z
 * et un seul bilan dans le jeu. Un seul mural : comme avant, sans message.
 */
public class OutilDeplacer {

    private Spinner<Integer> pasPosition, pasDecalage;
    private Label nomSel;
    private TextField codeSel;
    private RadioButton sInv, sBc, sInvBc;
    private Label etat;
    private VBox commandes, secondaires;
    /** Selection multiple : « Tous ceux de ce type », le compte, la liste. */
    private CheckBox parType;
    private Label compteSel;
    private VBox lignesSel;
    private ScrollPane defilSel;
    private Button viderSel;
    private Node racine;
    /** Le dernier clic deja traite (chaque clic dans le jeu cree un nouvel objet). */
    private Object dernierVu;
    /** Le dernier clic a retire son mural : il n'est plus choisi, meme s'il reste courant(). */
    private boolean courantRetire = false;
    /** Les envois a plusieurs muraux, l'un apres l'autre (fil a part). */
    private final java.util.concurrent.ExecutorService fil =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-murs-deplacer");
                t.setDaemon(true);
                return t;
            });

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        // fenetre ouverte : les muraux choisis restent en valeur dans le jeu (surligner)
        MiseEnValeur.fournir("murs-deplacer", OutilDeplacer::jetonsRepli);
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

        // --- selection de plusieurs muraux
        parType = new CheckBox("Tous ceux de ce type");
        parType.setTooltip(bulle("Un clic sur un mural choisit tous les muraux de la salle du même type "
                + "(même poster pour les posters). Un nouveau clic les retire. Un clic sur un autre type l'ajoute aussi : "
                + "plusieurs types peuvent être choisis ensemble."));
        parType.selectedProperty().addListener((o, a, oui) -> { if (oui) toutLeTypeDuChoisi(); });
        compteSel = Ui.discret("");
        viderSel = new Button("Vider");
        viderSel.setTooltip(bulle("Plus aucun mural choisi"));
        viderSel.setOnAction(e -> vider());
        Region ecartSel = new Region();
        HBox.setHgrow(ecartSel, Priority.ALWAYS);
        HBox enteteSel = new HBox(8, compteSel, ecartSel, viderSel);
        enteteSel.setAlignment(Pos.CENTER_LEFT);
        lignesSel = new VBox(2);
        lignesSel.setFillWidth(true);
        defilSel = new ScrollPane(lignesSel);
        defilSel.setFitToWidth(true);
        defilSel.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        defilSel.setMaxHeight(112);           // la fenetre ne doit pas trop grandir
        defilSel.setPrefViewportHeight(24);
        defilSel.managedProperty().bind(defilSel.visibleProperty());

        Button commeAvant = new Button("Remettre les muraux comme avant");
        commeAvant.setMaxWidth(Double.MAX_VALUE);
        commeAvant.setTooltip(bulle("Après un changement du floor, remet les muraux au même endroit à l'écran."));
        commeAvant.setOnAction(e -> Salle.tache("muraux", MurauxCommeAvant::remettre));

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
        VBox v = new VBox(12, Ui.bloc("Mur choisi", nomSel, codeSel, parType, enteteSel, defilSel,
                Ui.aide("Option+clic sur d'autres muraux, de n'importe quel type, pour les ajouter : "
                        + "ils se déplacent tous ensemble.")),
                commandes, secondaires, commeAvant, etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        racine = sp;
        dernierVu = SelectionMur.courant();   // un clic d'avant l'ouverture ne s'ajoute pas
        SelectionMur.installer();
        SelectionMur.ecouter(this::surChangement);
        majAffichage();
        suivreFenetre();

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
        List<SelectionMur.Mur> choisis = cibles();
        SelectionMur.Mur m = reference();
        boolean actif = (m != null);
        majListe(choisis);
        surligner(ids(choisis));
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
        String nom = nom(m);
        nomSel.setText(choisis.size() > 1 ? nom + "   ·   id " + m.id + "   ·   référence"
                : nom + "   ·   id " + m.id);
        nomSel.getStyleClass().setAll("label", "etat-ok");
        codeSel.setText(m.position);
        orienterOffset(m.position);
    }

    private static String nom(SelectionMur.Mur m) {
        return (m.typeId >= 0) ? Salle.nom(m.typeId, true) : m.nom;
    }

    /** Le compte et une ligne par mural choisi, avec son bouton pour le retirer. */
    private void majListe(List<SelectionMur.Mur> choisis) {
        int n = SelectionMur.nombre();
        compteSel.setText(n == 0 ? "Un clic sur un mural l'ajoute, un autre le retire."
                : Ui.accorder(n + " mural(aux) choisi(s)"));
        viderSel.setDisable(n == 0);
        lignesSel.getChildren().clear();
        if (n == 0) { defilSel.setVisible(false); return; }
        for (SelectionMur.Mur m : SelectionMur.liste()) {
            Label l = new Label(nom(m) + "  ·  id " + m.id);
            l.setMaxWidth(Double.MAX_VALUE);
            l.setMinWidth(0);
            HBox.setHgrow(l, Priority.ALWAYS);
            Button retirer = new Button();
            retirer.setGraphic(Icones.petite(Icones.FERMER, 10, false));
            retirer.setFocusTraversable(false);
            retirer.setStyle("-fx-padding: 2 5 2 5;");
            retirer.setTooltip(bulle("Retirer ce mural de la sélection"));
            retirer.setOnAction(e -> retirer(m.id));
            HBox ligne = new HBox(6, l, retirer);
            ligne.setAlignment(Pos.CENTER_LEFT);
            lignesSel.getChildren().add(ligne);
        }
        defilSel.setVisible(true);
        defilSel.setPrefViewportHeight(Math.min(4, n) * 26);
    }

    // ------------------------------------------------------------ selection

    /**
     * Les muraux a deplacer : ceux de la liste ; sans liste, le dernier mural
     * cliqué (comme avant), sauf si ce clic l'a justement retire.
     */
    private List<SelectionMur.Mur> cibles() {
        List<SelectionMur.Mur> l = SelectionMur.liste();
        if (!l.isEmpty()) return l;
        SelectionMur.Mur c = SelectionMur.courant();
        return (c == null || courantRetire) ? List.of() : List.of(c);
    }

    /** Le mural de reference (code affiche, Dupliquer) : le dernier clique s'il est choisi, sinon le premier. */
    private SelectionMur.Mur reference() {
        SelectionMur.Mur c = SelectionMur.courant();
        List<SelectionMur.Mur> l = cibles();
        if (l.isEmpty()) return null;
        if (c != null) for (SelectionMur.Mur m : l) if (m.id == c.id) return m;
        return l.get(0);
    }

    private static List<Integer> ids(List<SelectionMur.Mur> l) {
        List<Integer> r = new ArrayList<>();
        for (SelectionMur.Mur m : l) r.add(m.id);
        return r;
    }

    /** Un clic dans le jeu, ou un changement de la selection (fil JavaFX). */
    private void surChangement() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m != null && m != dernierVu) {
            dernierVu = m;
            // Fenetre fermee : le clic sert aux autres outils, la liste ne bouge pas.
            if (visible()) clic(m);
            else courantRetire = false;
        }
        majAffichage();
    }

    /** Ajoute le mural clique (ou tout son type), ou le retire s'il etait choisi. */
    private void clic(SelectionMur.Mur m) {
        if (SelectionMur.nombre() > 0 && SelectionMur.salleListe() != Salle.salleId()) SelectionMur.vider();
        if (parType.isSelected()) {
            List<SelectionMur.Mur> type = SelectionMur.memeTypeDansLaSalle(m);
            if (SelectionMur.contient(m.id)) SelectionMur.retirer(ids(type));
            else SelectionMur.ajouter(type);
        } else {
            SelectionMur.basculer(m);
        }
        courantRetire = !SelectionMur.contient(m.id);
    }

    /** La case « Tous ceux de ce type » vient d'etre cochee : le type du mural de reference s'ajoute. */
    private void toutLeTypeDuChoisi() {
        SelectionMur.Mur r = reference();
        if (r == null) return;
        SelectionMur.ajouter(SelectionMur.memeTypeDansLaSalle(r));
        courantRetire = false;
    }

    private void retirer(int id) {
        SelectionMur.retirer(List.of(id));
        SelectionMur.Mur c = SelectionMur.courant();
        if (c != null && c.id == id) courantRetire = true;
    }

    private void vider() {
        courantRetire = true;
        SelectionMur.vider();
        majAffichage();
    }

    // ------------------------------------------------------- mise en valeur

    /** Les muraux mis en valeur maintenant (fil quelconque). */
    private static volatile List<Integer> enValeur = List.of();
    /** La fenetre « Deplacer un mur » est-elle a l'ecran (lu hors fil JavaFX) ? */
    private static volatile boolean fenetreVisible = false;
    /** A renvoyer au jeu (selection changee, zone choisie, nouvelle salle). */
    private static volatile boolean aRenvoyer = true;

    /**
     * Point unique de la mise en valeur des muraux choisis ; chaque appel
     * remplace le precedent (liste vide : plus rien).
     *
     * Client qui sait l'opacite : ces muraux a 90 % d'opacite, sans contour ni
     * remplissage (envoye par le fil de suivreFenetre, hors JavaFX). Sinon,
     * repli : les jetons « m<id> » de MiseEnValeur (contour du style choisi).
     */
    static void surligner(List<Integer> murs) {
        List<Integer> l = murs == null ? List.of() : List.copyOf(murs);
        if (l.equals(enValeur)) return;
        enValeur = l;
        aRenvoyer = true;
    }

    /** Repli sans l'opacite : les jetons « m<id> » (fil de la mise en valeur). */
    private static List<String> jetonsRepli() {
        if (opacitePossible()) return List.of();
        List<String> r = new ArrayList<>();
        for (int id : enValeur) r.add("m" + id);
        return r;
    }

    private static volatile boolean opaciteOk = false;
    private static volatile long opaciteVueA = 0;

    /** Le client sait-il l'opacite ? Relu au plus toutes les 10 s (lecture du fichier du jeu). */
    private static boolean opacitePossible() {
        long t = System.currentTimeMillis();
        if (t - opaciteVueA > 10_000) { opaciteOk = ClientModifie.saitOpacite(); opaciteVueA = t; }
        return opaciteOk;
    }

    /** Le contenu de l'outil est-il affiche (menu choisi et fenetre ouverte) ? Fil JavaFX. */
    private boolean visible() {
        Node n = racine;
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null
                || !n.getScene().getWindow().isShowing()) return false;
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    /**
     * Suit l'ouverture de la fenetre et envoie l'opacite au jeu : a chaque
     * changement, en entrant dans une salle, apres le choix d'une zone (le jeu
     * remet alors tout a 100 %) ; tout a 100 % a la fermeture, quand la
     * selection est videe et au changement de salle (qui vide aussi la liste).
     */
    private void suivreFenetre() {
        javafx.animation.Timeline t = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(400), e -> {
                    boolean v = visible();
                    if (v != fenetreVisible) { fenetreVisible = v; aRenvoyer = true; }
                    if (SelectionMur.nombre() > 0 && SelectionMur.salleListe() != Salle.salleId()) {
                        courantRetire = true;
                        SelectionMur.vider();      // les muraux de l'autre salle n'existent plus ici
                    }
                }));
        t.setCycleCount(javafx.animation.Animation.INDEFINITE);
        t.play();
        Salle.tache("murs-deplacer-opacite", () -> {
            boolean envoye = false;        // une opacite est en place dans le jeu
            boolean zone = false;
            int salle = -1;
            long renvoiZone = 0, renvoiZone2 = 0;
            while (true) {
                Salle.sommeil(250);
                try {
                    boolean z = Zone.choixEnCours() || OngletCollageWired.choixCasesActif;
                    long now = System.currentTimeMillis();
                    // la zone a remis tout a 100 % (tout de suite, ou un peu apres) : on renvoie
                    if (zone && !z) { renvoiZone = now + 400; renvoiZone2 = now + 2000; }
                    zone = z;
                    if (renvoiZone != 0 && now >= renvoiZone) { renvoiZone = 0; aRenvoyer = true; }
                    if (renvoiZone2 != 0 && now >= renvoiZone2) { renvoiZone2 = 0; aRenvoyer = true; }
                    if (!Salle.installeeDepuis(1500)) { salle = -1; continue; }
                    int s = Salle.salleId();
                    if (s != salle) {
                        salle = s;
                        if (envoye) { MiseEnValeur.effacerOpacite(); envoye = false; }
                        aRenvoyer = true;
                    }
                    if (!aRenvoyer || !opacitePossible()) continue;
                    aRenvoyer = false;
                    List<Integer> l = fenetreVisible ? enValeur : List.of();
                    if (!l.isEmpty()) { MiseEnValeur.opaciteMuraux(l, 90); envoye = true; }
                    else if (envoye) { MiseEnValeur.effacerOpacite(); envoye = false; }
                } catch (Throwable e) {
                    Journal.debug("opacité des muraux : " + e);
                }
            }
        });
    }

    // ------------------------------------------------------------ mouvement

    private void deplacer(int dx, int dy, boolean surLePan) {
        // Correspondance reprise telle quelle du deplaceur d'affiches classique :
        //   Position : haut = y-, bas = y+, gauche = x-, droite = x+
        //   Offset   : haut = offY-, bas = offY+, gauche = offX-, droite = offX+
        // Sur une grille isometrique, ces quatre sens sont des DIAGONALES a
        // l'ecran, d'ou les boutons en losange. L'adaptation selon la face du
        // mur ne vaut que pour l'Aligner, qui pose des copies en ligne.
        // Plusieurs muraux : chacun bouge du meme decalage.
        bouger(p -> surLePan
                ? position(p.x() + dx, p.y() + dy, p.decalageX(), p.decalageY(), p.cote())
                : position(p.x(), p.y(), p.decalageX() + dx, p.decalageY() + dy, p.cote()));
    }

    /**
     * Deplace le mur de reference exactement au code saisi : copier-coller de
     * position. Les autres muraux choisis suivent du meme decalage.
     */
    private void appliquerCode() {
        Function<PositionMur, String> f = decalageDuCode();
        if (f != null) bouger(f);
    }

    /**
     * Du mural de reference au code saisi : le meme decalage, pour chaque
     * mural (changer la face dans le code la change pour tous). null : code
     * invalide (deja signale).
     */
    private Function<PositionMur, String> decalageDuCode() {
        SelectionMur.Mur ref = reference();
        if (ref == null) { echec("Aucun mur sélectionné."); return null; }
        String code = codeSel.getText() == null ? "" : codeSel.getText().trim();
        PositionMur c, r;
        try { c = PositionMur.lire(code); }
        catch (Throwable t) {
            echec("Code invalide : « " + code + " » (attendu : :w=x,y l=oX,oY r)");
            return null;
        }
        try { r = PositionMur.lire(ref.position); }
        catch (Throwable t) { echec("Position illisible : " + ref.position); return null; }
        int dx = c.x() - r.x(), dy = c.y() - r.y();
        int dox = c.decalageX() - r.decalageX(), doy = c.decalageY() - r.decalageY();
        boolean autreFace = c.cote() != r.cote();
        return p -> position(p.x() + dx, p.y() + dy, p.decalageX() + dox, p.decalageY() + doy,
                autreFace ? c.cote() : p.cote());
    }

    /** Pose une copie de chaque mural choisi : celle de la reference au code, les autres du meme decalage. */
    private void dupliquer() {
        List<SelectionMur.Mur> choisis = cibles();
        if (choisis.isEmpty()) { echec("Aucun mur sélectionné."); return; }
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }
        for (SelectionMur.Mur m : choisis)
            if (m.typeId < 0) { echec("Type du mur inconnu : refais un Option + clic sur le mur."); return; }
        Function<PositionMur, String> f = decalageDuCode();
        if (f == null) return;

        PoseMur.Source src = sBc.isSelected() ? PoseMur.Source.BC
                : sInvBc.isSelected() ? PoseMur.Source.INVENTAIRE_PUIS_BC
                : PoseMur.Source.INVENTAIRE;
        if (choisis.size() == 1) {
            SelectionMur.Mur m = choisis.get(0);
            String code = codeSel.getText().trim();
            PoseMur.Resultat r = PoseMur.poser(gp, m.typeId, m.etat, code, src, new java.util.HashSet<>());
            if (r.ok) reussi("Copie posée " + r.detail + ".");
            else echec("Échec de la copie : " + r.detail + ".");
            return;
        }
        fil.submit(() -> {
            java.util.Set<Integer> deja = new java.util.HashSet<>();
            int ok = 0, rates = 0;
            String raison = null;
            Historique.grouper(true);
            try {
                for (SelectionMur.Mur m : choisis) {
                    String pos = SelectionMur.positionActuelle(m, FRAICHEUR);
                    PositionMur p = pos == null ? null : PositionMur.lireOuNull(pos);
                    if (p == null) { rates++; raison = "plus dans la salle"; continue; }
                    Salle.espacer();
                    PoseMur.Resultat r = PoseMur.poser(gp, m.typeId, m.etat, f.apply(p), src, deja);
                    if (r.ok) ok++; else { rates++; raison = r.detail; }
                }
            } finally {
                Historique.grouper(false);
            }
            if (rates == 0) reussi(ok + " copie(s) posée(s).");
            else echec(ok + " copie(s) posée(s), " + rates + " ratée(s) : " + raison + ".");
        });
    }

    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    /** Chaque mural choisi passe sur l'autre face de son mur. */
    private void pivoter() {
        bouger(p -> position(p.x(), p.y(), p.decalageX(), p.decalageY(), p.cote() == 'l' ? 'r' : 'l'));
    }

    /** Position locale plus juste que celle de la salle pendant ce temps apres un envoi (ms). */
    private static final long FRAICHEUR = 2500;

    /**
     * Deplace les muraux choisis : chacun vers cible(sa position). Un seul
     * mural : envoi immediat, sans message (comme avant). Plusieurs : au
     * rythme des rafales, un seul Ctrl+Z, un seul bilan dans le jeu.
     */
    private void bouger(Function<PositionMur, String> cible) {
        List<SelectionMur.Mur> choisis = cibles();
        if (choisis.isEmpty()) { echec("Aucun mur sélectionné."); return; }
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { echec("L'Atelier n'est pas encore prêt."); return; }
        // un mobi d'un calque verrouille ne bouge pas
        String v = Groupes.refusVerrouMobis(List.of(), ids(choisis));
        if (v != null) { echec(v); return; }

        if (choisis.size() == 1) {
            SelectionMur.Mur m = choisis.get(0);
            String pos = SelectionMur.positionActuelle(m, FRAICHEUR);
            if (pos == null) pos = m.position;      // salle pas lue : la position connue
            PositionMur p;
            try { p = PositionMur.lire(pos); }
            catch (Throwable t) { echec("Position illisible : " + pos); return; }
            envoyer(gp, m, cible.apply(p));
            return;
        }
        fil.submit(() -> {
            int ok = 0, absents = 0;
            int salle = Salle.salleId();
            Historique.grouper(true);
            try {
                for (SelectionMur.Mur m : choisis) {
                    if (Salle.salleId() != salle) break;              // changement de salle : on s'arrete
                    String pos = SelectionMur.positionActuelle(m, FRAICHEUR);
                    PositionMur p = pos == null ? null : PositionMur.lireOuNull(pos);
                    if (p == null) { absents++; continue; }
                    String c = cible.apply(p);
                    Salle.espacer();
                    SelectionMur.ignorerEnvoi(m.id, 800);
                    Salle.deplacerMur(m.id, c);
                    SelectionMur.majPosition(m, c);
                    ok++;
                }
            } finally {
                Historique.grouper(false);
            }
            if (absents == 0 && ok == choisis.size()) reussi(ok + " mural(aux) déplacé(s).");
            else if (absents > 0) echec(ok + " mural(aux) déplacé(s), " + absents + " introuvable(s) dans la salle.");
            else echec("Déplacement arrêté : tu as changé de salle (" + ok + " mural(aux) déplacé(s)).");
        });
    }

    private void envoyer(Moteur gp, SelectionMur.Mur m, String cible) {
        SelectionMur.ignorerEnvoi(m.id, 800);
        gp.sendToServer(new HPacket("MoveWallItem", HMessage.Direction.TOSERVER, m.id, cible));
        // On tient la position a jour localement : les clics s'enchainent sans
        // attendre la confirmation du serveur.
        SelectionMur.majPosition(m, cible);
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

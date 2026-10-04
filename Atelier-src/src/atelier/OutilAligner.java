package atelier;


import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Aligne des copies d'un mobi mural en ligne droite.
 *
 * Geometrie, deduite de deux posters reellement alignes a l'horizontale :
 *     :w=21,25 l=3,-257 r
 *     :w=21,25 l=11,-253 r     ->  offX +8, offY +4
 *
 * L'horizontale sur un mur est donc une DIAGONALE isometrique : offX et offY
 * varient ensemble dans un rapport 2:1. Faire varier offX seul ne donne jamais
 * une ligne droite. La verticale, elle, ne touche que offY.
 *
 * Deux volets (deux sections du menu Murs) :
 *   Aligner  une ligne de copies, dans un sens ;
 *   Grille   une zone de copies, par exemple 5 × 3 : le mur choisi est en bas
 *            a gauche, les colonnes vont a droite et les rangees vers le haut,
 *            avec un espacement pour chacun.
 * L'ecart de chaque mobi est retenu (Ecarts, EcartsDefaut) : sous les
 * champs, EcartsBandeau montre le mobi et l'origine de son ecart (actions
 * dans son ⚙) ; le volet « Écarts » (EcartsVolet) en fait la liste en cartes.
 */
public class OutilAligner {

    private Spinner<Integer> nombre, espacement;
    private RadioButton dHaut, dBas, dDroite, dGauche;
    private RadioButton sInv, sBc, sInvBc;
    private Label selLbl, etat;
    private Button ajouter;
    private VBox reglages;
    private EcartsBandeau bandeauLigne, bandeauGrille;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        // fenetre ouverte : le mobi mural choisi reste allume dans le jeu
        MiseEnValeur.fournir("murs-aligner", () -> {
            SelectionMur.Mur m = SelectionMur.courant();
            return m == null ? java.util.List.<String>of() : java.util.List.of("m" + m.id);
        });
        selLbl = Ui.valeur("Aucun mur sélectionné");

        nombre = spin(1, 200, 5);
        espacement = spin(1, 500, 1);

        ToggleGroup dir = new ToggleGroup();
        dDroite = radio("→ À droite", dir, true);
        dGauche = radio("← À gauche", dir, false);
        dHaut   = radio("↑ Au-dessus", dir, false);
        dBas    = radio("↓ En dessous", dir, false);

        ToggleGroup src = new ToggleGroup();
        sInv   = radio("Inventaire", src, true);
        sBc    = radio("BC", src, false);
        sInvBc = radio("Inv. puis BC", src, false);

        ajouter = new Button("Ajouter les copies");
        ajouter.getStyleClass().add("primaire");
        ajouter.setMaxWidth(Double.MAX_VALUE);
        ajouter.setOnAction(e -> { if (enCours) arreter = true; else lancer(); });

        etat = Ui.etat();

        // L'ecart du mobi choisi, d'ou il vient, et de quoi l'enregistrer (EcartsBandeau).
        Label unite = new Label("pans");
        bandeauLigne = EcartsBandeau.ligne(espacement, () -> dDroite.isSelected() || dGauche.isSelected());
        dir.selectedToggleProperty().addListener((o, a, n) -> {
            unite.setText(dDroite.isSelected() || dGauche.isSelected() ? "pans" : "px");
            bandeauLigne.sensChange();
        });
        HBox ecartLigne = champ("Écart", espacement, null);
        ecartLigne.getChildren().add(unite);

        // Sens : deux colonnes (droite / gauche, puis haut / bas) au lieu de quatre lignes
        GridPane sens = new GridPane();
        sens.setHgap(14); sens.setVgap(6);
        sens.addRow(0, dDroite, dGauche);
        sens.addRow(1, dHaut, dBas);
        reglages = new VBox(10,
                Ui.bloc("Copies",
                        champ("Nombre", nombre, "copies"),
                        ecartLigne,
                        bandeauLigne,
                        Ui.aide("Écart : en pans de mur vers la droite ou la gauche, en pixels vers le haut ou le bas.")),
                Ui.bloc("Sens", sens),
                Ui.bloc("Source", Ui.ligne(sInv, sBc, sInvBc)));

        VBox v = new VBox(12, reglages, ajouter, etat);   // le mur choisi est dit dans les prerequis
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(this::majSelection);
        majSelection();

        Tab t = new Tab("Aligner", sp);
        t.setClosable(false);
        return t;
    }

    // ---------------------------------------------------------- volet Grille

    private Spinner<Integer> colonnes, rangees, pasDroite, pasHaut;
    private RadioButton gInv, gBc, gInvBc;
    private Button ajouterGrille;
    private VBox reglagesGrille;
    private Label etatGrille;

    /** Le volet « Grille » : une zone de copies, a droite et vers le haut. */
    public Tab construireGrille() {
        colonnes = spin(1, 50, 5);
        rangees = spin(1, 50, 3);
        pasDroite = spin(1, 500, 1);
        pasHaut = spin(1, 500, 32);

        ToggleGroup src = new ToggleGroup();
        gInv   = radio("Inventaire", src, true);
        gBc    = radio("BC", src, false);
        gInvBc = radio("Inv. puis BC", src, false);

        ajouterGrille = new Button("Poser la grille");
        ajouterGrille.getStyleClass().add("primaire");
        ajouterGrille.setMaxWidth(Double.MAX_VALUE);
        ajouterGrille.setOnAction(e -> { if (enCours) arreter = true; else lancerGrille(); });

        etatGrille = Ui.etat();
        bandeauGrille = EcartsBandeau.grille(pasDroite, pasHaut);

        reglagesGrille = new VBox(10,
                Ui.bloc("Grille",
                        ligneGrille("→ Vers la droite", colonnes, pasDroite, "pans"),
                        ligneGrille("↑ Vers le haut", rangees, pasHaut, "px"),
                        bandeauGrille,
                        Ui.aide("Le mur choisi est la copie en bas à gauche. Vers la droite : combien "
                                + "de copies sur la ligne, et l'écart en pans de mur. Vers le haut : "
                                + "combien de rangées, et l'écart en pixels. 5 × 3 = 15 mobis, dont "
                                + "celui déjà posé.")),
                Ui.bloc("Source", Ui.ligne(gInv, gBc, gInvBc)));

        VBox v = new VBox(12, reglagesGrille, ajouterGrille, etatGrille);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(this::majSelection);
        majSelection();

        Tab t = new Tab("Grille", sp);
        t.setClosable(false);
        return t;
    }

    // ------------------------------------------------- volet Ecarts enregistres

    /**
     * Le volet « Écarts enregistrés » : la liste des ecarts par mobi
     * (EcartsVolet). Les volets « En ligne » et « En grille » montrent et
     * enregistrent l'ecart du mobi choisi eux-memes (EcartsBandeau).
     */
    public Tab construireEcarts() {
        return new EcartsVolet().construire();
    }

    /** « → Vers la droite », puis « Nombre [5] » et « Écart [1] pans », chacun sur sa ligne. */
    private static VBox ligneGrille(String sens, Spinner<Integer> nombre, Spinner<Integer> ecart, String unite) {
        return new VBox(6, Ui.etiquette(sens), champ("Nombre", nombre, null), champ("Écart", ecart, unite));
    }

    /**
     * Un champ par ligne : libelle a largeur fixe, saisie, unite. Rien ne deborde
     * quand la fenetre est etroite. La saisie au clavier est prise a la sortie
     * du champ, sans attendre Entree.
     */
    private static HBox champ(String libelle, Spinner<Integer> s, String unite) {
        Label l = new Label(libelle);
        l.setMinWidth(70); l.setPrefWidth(70);
        l.setWrapText(true);
        Generateur.valider(s);
        HBox h = unite == null ? new HBox(8, l, s) : new HBox(8, l, s, new Label(unite));
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    /**
     * Decalages de la grille, sans la case d'origine : {pans vers la droite,
     * pixels en Y}. Le haut = offY qui diminue. Logique pure.
     */
    static List<int[]> decalagesGrille(int colonnes, int rangees, int pasDroite, int pasHaut) {
        List<int[]> r = new ArrayList<>();
        for (int j = 0; j < rangees; j++)
            for (int i = 0; i < colonnes; i++)
                if (i != 0 || j != 0) r.add(new int[]{i * pasDroite, -j * pasHaut});
        return r;
    }

    private void lancerGrille() {
        for (Spinner<Integer> sp : List.of(colonnes, rangees, pasDroite, pasHaut)) Generateur.prendre(sp);
        if (SelectionMur.courant() != null) bandeauGrille.enregistrerAPose();   // l'ecart se retient pour ce mobi
        List<int[]> d = decalagesGrille(colonnes.getValue(), rangees.getValue(),
                pasDroite.getValue(), pasHaut.getValue());
        if (d.isEmpty()) { direGrille("Grille impossible : en 1 × 1, c'est le mur déjà posé."); return; }
        PoseMur.Source src = gBc.isSelected() ? PoseMur.Source.BC
                : gInvBc.isSelected() ? PoseMur.Source.INVENTAIRE_PUIS_BC
                : PoseMur.Source.INVENTAIRE;
        lancerAvec(d, src, this::direGrille, "Grille");
    }

    private void direGrille(String s) { Platform.runLater(() -> etatGrille.setText(s)); }

    private void majSelection() {
        SelectionMur.Mur m = SelectionMur.courant();
        boolean actif = (m != null);
        if (enCours) return;   // pendant une pose, les boutons servent a Arreter (finPose remet tout)
        if (reglages != null) { reglages.setDisable(!actif); ajouter.setDisable(!actif); }
        if (reglagesGrille != null) { reglagesGrille.setDisable(!actif); ajouterGrille.setDisable(!actif); }
        String aide = actif ? "Mur choisi : « " + m.nom + " »." : "Clique un mobi mural dans le jeu pour le choisir.";
        if (etatGrille != null && (!actif || etatGrille.getText().isEmpty() || etatGrille.getText().startsWith("Clique un mobi")
                || etatGrille.getText().startsWith("Mur choisi")))
            Platform.runLater(() -> etatGrille.setText(aide));

    }

    // --------------------------------------------------------------- action

    private void lancer() {
        Generateur.prendre(nombre); Generateur.prendre(espacement);
        int n = nombre.getValue(), pas = espacement.getValue();
        if (SelectionMur.courant() != null) bandeauLigne.enregistrerAPose();   // l'ecart se retient pour ce mobi
        boolean horizontal = dDroite.isSelected() || dGauche.isSelected();
        int signe = (dDroite.isSelected() || dBas.isSelected()) ? 1 : -1;
        List<int[]> d = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            d.add(horizontal ? new int[]{signe * pas * i, 0} : new int[]{0, signe * pas * i});
        // La source est lue ici, sur le fil de l'interface, pas dans le fil de pose.
        PoseMur.Source src = sBc.isSelected() ? PoseMur.Source.BC
                : sInvBc.isSelected() ? PoseMur.Source.INVENTAIRE_PUIS_BC
                : PoseMur.Source.INVENTAIRE;
        lancerAvec(d, src, this::dire, "Aligner");
    }

    /**
     * Pose des copies du mur choisi, decalees de {pans vers la droite, pixels en Y}.
     *
     * Horizontale : c'est le PAN DE MUR qui change, pas les offsets. Mesure sur
     * un mur de face 'l' : :w=5,7 -> :w=5,6 pour aller a droite, donc droite =
     * y - pas. Sur une face 'r', c'est l'axe x qui court le long du mur, dans
     * l'autre sens. Verticale : offY seul (vers le bas quand il augmente).
     */
    private void lancerAvec(List<int[]> decalages, PoseMur.Source src,
                            java.util.function.Consumer<String> dire, String nomOutil) {
        SelectionMur.Mur ref = SelectionMur.courant();
        if (ref == null) { dire.accept("Pose impossible : clique d'abord un mur dans le jeu."); return; }
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { dire.accept("Pose impossible : l'Atelier n'est pas encore prêt."); return; }
        if (ref.typeId < 0) { dire.accept("Pose impossible : type du mur inconnu, reclique le mur dans le jeu."); return; }

        PositionMur p;
        try { p = PositionMur.lire(ref.position); }
        catch (Throwable t) { dire.accept("Pose impossible : position du mur illisible (" + ref.position + ")."); return; }

        List<String> cibles = new ArrayList<>();
        boolean faceGauche = (p.cote() == 'l');
        for (int[] d : decalages) {
            int wx = p.x(), wy = p.y();
            if (faceGauche) wy -= d[0]; else wx += d[0];
            cibles.add(String.format(java.util.Locale.ROOT, ":w=%d,%d l=%d,%d %c",
                    wx, wy, p.decalageX(), p.decalageY() + d[1], p.cote()));
        }

        // Pose en masse : apercu chiffre, puis un second clic pour confirmer.
        long now = System.currentTimeMillis();
        String sig = nomOutil + ":" + cibles.size() + ":" + ref.id;
        if (cibles.size() > SEUIL_CONFIRMATION && !(sig.equals(aConfirmer) && now - aConfirmerA < 15000)) {
            aConfirmer = sig;
            aConfirmerA = now;
            dire.accept(cibles.size() + " copies de « " + ref.nom + " » à poser (source : "
                    + (src == PoseMur.Source.BC ? "BC" : src == PoseMur.Source.INVENTAIRE ? "inventaire" : "inventaire puis BC")
                    + "). Reclique pour confirmer.");
            return;
        }
        aConfirmer = null;

        enCours = true;
        arreter = false;
        debutPose();
        dire.accept("Pose de " + cibles.size() + " copie(s) de « " + ref.nom + " »…");
        Thread t = new Thread(() -> {
            try { poser(gp, ref, cibles, src, dire, nomOutil); }
            catch (Throwable e) { Journal.erreur(nomOutil + " : pose interrompue", e); }
            finally { enCours = false; Platform.runLater(this::finPose); }
        }, "atelier-aligner");
        t.setDaemon(true);
        t.start();
    }

    /** Au-dela, la pose demande un second clic (apercu chiffre). */
    static final int SEUIL_CONFIRMATION = 20;
    private volatile boolean enCours = false, arreter = false;
    private String aConfirmer = null;
    private long aConfirmerA = 0;
    private String texteAjouter, texteGrille;

    /** Pendant la pose : un seul fil, et les deux boutons deviennent « Arrêter ». */
    private void debutPose() {
        if (ajouter != null) {
            texteAjouter = ajouter.getText();
            ajouter.setText("Arrêter"); ajouter.setDisable(false);
            if (reglages != null) reglages.setDisable(true);
        }
        if (ajouterGrille != null) {
            texteGrille = ajouterGrille.getText();
            ajouterGrille.setText("Arrêter"); ajouterGrille.setDisable(false);
            if (reglagesGrille != null) reglagesGrille.setDisable(true);
        }
    }

    private void finPose() {
        if (ajouter != null && texteAjouter != null) ajouter.setText(texteAjouter);
        if (ajouterGrille != null && texteGrille != null) ajouterGrille.setText(texteGrille);
        majSelection();
    }

    /**
     * Pose les copies une par une et VERIFIE chacune : une copie compte quand
     * un nouveau mobi mural de ce type apparait dans la salle (2 s au plus).
     * Avant, une demande envoyee comptait comme posee, meme refusee par le jeu.
     * Chaque copie est journalisee dans le terminal ; le bilan va dans le
     * volet et dans le jeu.
     */
    private void poser(Moteur gp, SelectionMur.Mur ref, List<String> cibles, PoseMur.Source src,
                       java.util.function.Consumer<String> dire, String nomOutil) {
        java.util.Set<Integer> utilises = new java.util.HashSet<>();
        int pose = 0, echecs = 0, k = 0;
        String raison = "";
        String arret = null;
        int salle = Salle.salleId();
        long dernierEnvoi = 0;
        Journal.debug(nomOutil + " : " + cibles.size() + " copie(s) de « " + ref.nom
                + " » (type " + ref.typeId + ", état " + ref.etat + ") depuis " + ref.position + ", source " + src);
        for (String cible : cibles) {
            if (arreter) { arret = "pose arrêtée"; break; }
            if (Salle.salleId() != salle) { arret = "pose interrompue, tu as changé de salle"; break; }
            // au moins 150 ms entre deux envois, meme si la copie precedente est apparue tout de suite
            long ecart = System.currentTimeMillis() - dernierEnvoi;
            if (ecart < 150) Salle.sommeil(150 - ecart);
            k++;
            java.util.Set<Integer> avant = new java.util.HashSet<>();
            for (gearth.extensions.parsers.HWallItem w : Salle.murs()) avant.add(w.getId());
            dernierEnvoi = System.currentTimeMillis();
            PoseMur.Resultat r = PoseMur.poser(gp, ref.typeId, ref.etat, cible, src, utilises);
            String ligne;
            if (!r.ok) {
                echecs++; raison = r.detail;
                ligne = "pas envoyée : " + r.detail;
            } else if (apparu(avant, ref.typeId)) {
                pose++;
                ligne = "posée " + r.detail;
            } else {
                echecs++; raison = "refusée par le jeu (position hors du mur ?)";
                ligne = "envoyée " + r.detail + ", mais refusée par le jeu";
            }
            Journal.debug(nomOutil + " " + k + "/" + cibles.size() + " " + cible + " : " + ligne);
            dire.accept(nomOutil + " : " + k + "/" + cibles.size() + " — " + ligne + "…");   // « … » : progression, pas un resultat
            // tout manque dans l'inventaire / au BC : inutile d'insister
            if (!r.ok && pose == 0 && k >= 2) {
                echecs += cibles.size() - k;
                break;
            }
        }
        String bilan = arret != null
                ? arret + " (" + pose + " / " + cibles.size() + " copie(s) posée(s)"
                        + (echecs > 0 ? ", " + echecs + " échec(s) : " + raison : "") + ")."
                : pose + " copie(s) posée(s)" + (echecs > 0 ? ", " + echecs + " échec(s) : " + raison : "") + ".";
        bilan = nomOutil + " : " + bilan;
        // un seul message de resultat (la ligne d'etat garde le meme texte, sans le redire)
        if (echecs > 0 || (arret != null && arret.contains("salle"))) Journal.erreur(bilan);
        else Journal.succes(bilan);
        dire.accept(bilan);
    }

    /** Un nouveau mobi mural du type est-il apparu ? Attend 2 s au plus. */
    private static boolean apparu(java.util.Set<Integer> avant, int typeId) {
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { return false; }
            for (gearth.extensions.parsers.HWallItem w : Salle.murs())
                if (!avant.contains(w.getId()) && w.getTypeId() == typeId) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- outils

    private static Spinner<Integer> spin(int min, int max, int defaut) {
        Spinner<Integer> s = new Spinner<>(min, max, defaut);
        s.setEditable(true);
        s.setPrefWidth(90);
        return s;
    }

    private static Label gras(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    private static Button plein(String texte,
                                javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(texte);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(h);
        return b;
    }

    private void dire(String s) {
        Platform.runLater(() -> etat.setText(s));
    }
}

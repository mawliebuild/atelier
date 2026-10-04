package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import utils.WallPosition;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Faux mur : poser des mobis muraux en cliquant une case du sol.
 *
 * Aucun repere a placer. Le mobi deja selectionne sert de modele — son type,
 * son etat, ses decalages et sa face — et cliquer une case pose une copie sur
 * le pan de mur correspondant a cette case.
 *
 * La correspondance vient d'une mesure reelle :
 *     :w=5,7 l=25,832 l   ->   :w=5,6 l=25,832 l   en allant a droite
 * Sur une face 'l', le mur court donc le long de l'axe y : la case (x,y) donne
 * le pan w=?,y. Sur une face 'r', c'est l'axe x.
 *
 * Les decalages du modele sont conserves : c'est eux qui fixent la hauteur, que
 * le clic ne peut pas transmettre — un paquet de clic ne porte que la case.
 */
public class OutilFauxMur {

    private Label modele, etat;
    private CheckBox poserAuClic;
    private RadioButton sInv, sBc, sInvBc;
    private Spinner<Integer> hauteur;
    private final java.util.Set<Integer> invUtilises = new java.util.HashSet<>();
    private boolean installe = false;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        modele = Ui.valeur("Aucun mobi sélectionné");
        etat = Ui.etat();

        poserAuClic = new CheckBox("Poser en cliquant une case du sol");

        hauteur = new Spinner<>(-2000, 2000, 0);
        hauteur.setEditable(true);
        hauteur.setPrefWidth(96);

        ToggleGroup g = new ToggleGroup();
        sInv   = radio("Inventaire", g, true);
        sBc    = radio("BC", g, false);
        sInvBc = radio("Inv. puis BC", g, false);

        // Copies lisibles depuis le fil des paquets : il ne doit pas lire les
        // controles de l'interface, qui appartiennent au fil JavaFX.
        poserAuClic.selectedProperty().addListener((o, a, b) -> actif = b);
        hauteur.valueProperty().addListener((o, a, b) -> { if (b != null) decalage = b; });
        g.selectedToggleProperty().addListener((o, a, b) -> source =
                sBc.isSelected() ? PoseMur.Source.BC
                : sInvBc.isSelected() ? PoseMur.Source.INVENTAIRE_PUIS_BC
                : PoseMur.Source.INVENTAIRE);

        VBox v = new VBox(12,
                Ui.bloc("Modèle", modele,
                        Ui.aide("Clique dans le jeu le mobi mural à reproduire : sa hauteur "
                                + "et sa face servent de modèle pour tout le faux mur.")),
                Ui.bloc("Pose",
                        poserAuClic,
                        Ui.ligne(Ui.etiquette("Décalage de hauteur"), hauteur),
                        Ui.ligne(Ui.etiquette("Source :"), sInv, sBc, sInvBc)),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SelectionMur.installer();
        SelectionMur.ecouter(this::majModele);
        majModele();
        installer();

        Tab t = new Tab("Faux mur", sp);
        t.setClosable(false);
        return t;
    }

    private void majModele() {
        SelectionMur.Mur m = SelectionMur.courant();
        boolean ok = (m != null);
        modele.setText(ok ? m.nom + "   ·   " + m.position : "Aucun mobi sélectionné");
        modele.getStyleClass().setAll("label", ok ? "etat-ok" : "etat-absent");
    }

    // -------------------------------------------------------------- ecoute

    private synchronized void installer() {
        if (installe) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 600 && !installe; i++) {
                brancher();
                if (installe) return;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-fauxmur");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void brancher() {
        if (installe) return;
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) return;
        try {
            // seulement le clic au sol (MoveAvatar) : pas de pose fantome sur
            // LookTo, PickupObject ou tout autre paquet de deux entiers
            gp.intercept(HMessage.Direction.TOSERVER, "MoveAvatar", m -> {
                try { if (actif) poser(gp, m); } catch (Throwable ignored) { }
            });
            installe = true;
            Journal.debug("faux mur : écoute active.");
        } catch (Throwable ignored) { }
    }

    private void poser(GPresets gp, HMessage m) {
        int taille = m.getPacket().getBytesLength();
        if (taille < 14 || taille > 20) return;      // un clic au sol : deux entiers

        SelectionMur.Mur ref = SelectionMur.courant();
        if (ref == null || ref.typeId < 0) return;

        FloorState s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;

        HPacket p = m.getPacket();                    // lecture a position fixe : pas de copie
        int cx, cy;
        try { cx = p.readInteger(6); cy = p.readInteger(10); }
        catch (Throwable e) { return; }
        if (cx < 0 || cx > 200 || cy < 0 || cy > 200) return;
        if (Salle.hauteurSol(cx, cy) < 0) return;    // case non jouable

        WallPosition w;
        try { w = new WallPosition(ref.position); } catch (Throwable e) { return; }

        // Face 'l' : le mur suit l'axe y, la case donne donc w=x,cy.
        // Face 'r' : il suit l'axe x, la case donne w=cx,y.
        int wx = w.getX(), wy = w.getY();
        if (w.getDirection() == 'l') wy = cy; else wx = cx;

        String cible = String.format(java.util.Locale.ROOT, ":w=%d,%d l=%d,%d %c",
                wx, wy, w.getOffsetX(), w.getOffsetY() + decalage, w.getDirection());

        // La pose part sur un autre fil : faite ici, elle retenait le clic du
        // jeu (et le deplacement de l'avatar) le temps de l'envoi. Un seul fil,
        // pour que les poses partent dans l'ordre des clics.
        PoseMur.Source src = source;
        poses.submit(() -> {
            PoseMur.Resultat r = PoseMur.poser(gp, ref.typeId, ref.etat, cible, src, invUtilises);
            // la pose se voit dans le jeu : seul l'echec est dit
            dire(r.ok ? "" : "Échec de la pose : " + r.detail + ".");
        });
    }

    private volatile boolean actif = false;
    private volatile int decalage = 0;
    private volatile PoseMur.Source source = PoseMur.Source.INVENTAIRE;
    private static final java.util.concurrent.ExecutorService poses =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-faux-mur");
                t.setDaemon(true);
                return t;
            });

    // ---------------------------------------------------------------- outils

    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    private void dire(String s) {
        Platform.runLater(() -> etat.setText(s));
    }
}

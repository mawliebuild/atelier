package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.util.*;

/**
 * Section Wired, volet « Ordre » : on choisit une case de la salle, on lit la
 * pile de BOITES wired qui s'y trouve (les mobis wired comme les dalles ou les
 * antennes n'en font pas partie), et on la remet dans l'ordre de
 * fonctionnement de Habbo en ecrivant @altitude sur chaque wired (voir
 * remettreEnOrdre).
 *
 * Mise en valeur dans le jeu (MiseEnValeur.ChoixWired) : une ligne choisie =
 * ce wired seul ; une case cliquee dans le jeu = toute sa pile.
 */
public class OngletWired {

    public static class LigneWired {
        private final SimpleIntegerProperty rang;
        private final SimpleStringProperty type, nom;
        private final SimpleStringProperty altitude;
        final int id;
        final Wired.Rang r;
        public LigneWired(int id, Wired.Rang r, String nom, double z) {
            this.id = id; this.r = r;
            rang = new SimpleIntegerProperty(r.ordre);
            type = new SimpleStringProperty(r.libelle);
            this.nom = new SimpleStringProperty(nom);
            altitude = new SimpleStringProperty(String.format(Locale.ROOT, "%.2f", z));
        }
        public int getRang() { return rang.get(); }
        public String getType() { return type.get(); }
        public String getNom() { return nom.get(); }
        public String getAltitude() { return altitude.get(); }
    }

    private Label caseLbl, etat, altLbl;
    private TableView<LigneWired> table;
    private Button remettre, tous, arreter;
    private ProgressBar barre;
    /**
     * La pile lue sur la case choisie, du bas vers le haut. Liste figee,
     * remplacee d'un bloc : elle est ecrite par le fil des paquets et lue par
     * l'interface et par la remise en ordre, qui ne doivent jamais la voir
     * a moitie remplie.
     */
    private volatile List<LigneWired> piles = List.of();
    private volatile HPoint caseChoisie;
    private boolean ecouteInstallee = false;
    /** Remise en ordre en cours : le suivi de la case ne relit pas (messages). */
    private volatile boolean enRangement = false;
    /** Relire la case au prochain tour du suivi (fin de remise en ordre). */
    private volatile boolean caseRelue = false;
    /** Un seul rangement a la fois (double-clic sur le bouton). */
    private final java.util.concurrent.atomic.AtomicBoolean rangementPris = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Selection du tableau changee par le programme (fil JavaFX seulement) : pas un choix de l'utilisatrice. */
    private boolean selectionAuto = false;

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        TabPane volets = new TabPane();
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Seul le volet « Ordre » est monte par la Navigation : plus de volet
        // Dalle magique (son fil interrogeait la salle chaque seconde pour rien).
        Tab ordre = new Tab("Ordre", defiler(voletOrdre()));
        ordre.setClosable(false);
        volets.getTabs().add(ordre);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);

        VBox racine = new VBox(6, volets);
        racine.setPadding(new Insets(10));

        Tab t = new Tab("Wired", racine);
        t.setClosable(false);
        return t;
    }

    private static ScrollPane defiler(Pane p) {
        ScrollPane sp = new ScrollPane(p);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    // ------------------------------------------------------------------ ordre

    private Pane voletOrdre() {
        caseLbl = Ui.valeur("Aucune case choisie");
        etat = Ui.etat();

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // clic sur une ligne : CE wired seul est mis en valeur dans le jeu
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<LigneWired>) ch -> {
                    if (selectionAuto) return;            // selection posee par le programme
                    List<Integer> ids = new ArrayList<>();
                    for (LigneWired l : table.getSelectionModel().getSelectedItems()) if (l != null) ids.add(l.id);
                    if (!ids.isEmpty()) MiseEnValeur.ChoixWired.lignes(ids);
                });
        Salle.surClicCase(c -> {
            if (c == null || !WiredLecteur.actif() || enRangement) return;   // fenetre Wired fermee : rien
            Salle.tache("wired-clic-case", () -> clicCase(c));
        });
        TableColumn<LigneWired, Integer> cr = new TableColumn<>("#");
        cr.setCellValueFactory(new PropertyValueFactory<>("rang"));
        cr.setMaxWidth(500); cr.setPrefWidth(32);
        TableColumn<LigneWired, String> ct = new TableColumn<>("Type");
        ct.setCellValueFactory(new PropertyValueFactory<>("type"));
        ct.setMaxWidth(3000); ct.setPrefWidth(120);
        TableColumn<LigneWired, String> cn = new TableColumn<>("Mobi");
        cn.setCellValueFactory(new PropertyValueFactory<>("nom"));
        cn.setMaxWidth(3000); cn.setPrefWidth(120);
        TableColumn<LigneWired, String> ca = new TableColumn<>("Alt.");
        ca.setCellValueFactory(new PropertyValueFactory<>("altitude"));
        ca.setMaxWidth(800); ca.setPrefWidth(52);
        table.getColumns().add(cr); table.getColumns().add(ct);
        table.getColumns().add(cn); table.getColumns().add(ca);
        // noms longs (« Effet : envoyer un signal ») : a la ligne plutot que coupes
        Ui.retourALaLigne(ct);
        Ui.retourALaLigne(cn);
        table.setPlaceholder(Ui.discret("Clique un wired dans le jeu : sa pile s'affiche ici."));
        table.setPrefHeight(240);
        table.setMinHeight(160);
        VBox.setVgrow(table, Priority.ALWAYS);

        altLbl = Ui.valeur("@altitude sera trouvée automatiquement");

        remettre = new Button("Remettre dans l'ordre");
        remettre.getStyleClass().add("primaire");
        remettre.setMaxWidth(Double.MAX_VALUE);
        remettre.setDisable(true);
        remettre.setOnAction(e -> lancer("Rangement des wired interrompu", this::remettreEnOrdre));

        tous = Ui.bouton(Icones.TRIER, "Remettre en ordre tous les wired de l'appart");
        tous.setMaxWidth(Double.MAX_VALUE);
        Ui.bulle(tous, "Trouve toutes les piles de wired de l'appart dont l'ordre n'est pas bon "
                + "et les remet en ordre en une fois. Les piles déjà dans l'ordre ne reçoivent rien.");
        tous.setOnAction(e -> lancer("Rangement des wired de l'appart interrompu", this::toutRemettreEnOrdre));

        arreter = Ui.bouton(Icones.ARRET, "Arrêter");
        Ui.bulle(arreter, "Arrêter la remise en ordre : les wired déjà envoyés restent où ils sont.");
        arreter.setOnAction(e -> { arret.set(true); arreter.setDisable(true); dire("Arrêt demandé…"); });
        arreter.managedProperty().bind(arreter.visibleProperty());
        arreter.setVisible(false);

        barre = new ProgressBar(0);
        barre.setMaxWidth(Double.MAX_VALUE);
        barre.managedProperty().bind(barre.visibleProperty());
        barre.setVisible(false);

        Label aide = Ui.aide("Clique un wired dans le jeu : toute la pile de sa case "
                + "est lue. L'ordre visé, du bas vers le haut : déclencheur, sélecteur, "
                + "sélecteur filtre, condition, add-on de condition (« au moins une "
                + "condition est remplie »), effet, effet envoyer un signal, "
                + "effet négatif, add-on. Les mobis wired (dalles, antennes, compteurs…) "
                + "ne bougent pas.");

        VBox v = new VBox(12,
                Ui.bloc("Case choisie", caseLbl, aide),
                Ui.bloc("Pile de wired", table),
                Ui.bloc("Réglage d'altitude", altLbl,
                        Ui.aide("Rien à faire : l'Atelier vérifie @altitude tout seul "
                                + "au premier rangement, sur un wired de la pile.")),
                remettre,
                tous,
                barre,
                arreter,
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        installerEcoute();
        suivreCase();
        return v;
    }

    /**
     * Lance une remise en ordre hors du fil JavaFX : une seule a la fois,
     * boutons bloques, barre de progression et bouton « Arrêter » visibles.
     */
    private void lancer(String siErreur, Runnable travail) {
        if (!rangementPris.compareAndSet(false, true)) return;     // deja en cours
        arret.set(false);
        remettre.setDisable(true);
        tous.setDisable(true);
        barre.setProgress(0);
        barre.setVisible(true);
        arreter.setDisable(false);
        arreter.setVisible(true);
        enRangement = true;
        Salle.tache("wired", () -> {
            try { travail.run(); }
            catch (Throwable t) { dire(""); Journal.erreur(siErreur, t); }
            finally {
                enRangement = false;
                caseRelue = true;
                rangementPris.set(false);
                Platform.runLater(() -> {
                    remettre.setDisable(piles.size() < 2);
                    tous.setDisable(false);
                    barre.setVisible(false);
                    arreter.setVisible(false);
                });
            }
        });
    }

    private void progression(double part) {
        Platform.runLater(() -> barre.setProgress(Math.max(0, Math.min(1, part))));
    }

    /**
     * La pile affichee suit la salle : si un wired de la case choisie est
     * pose, retire ou bouge (par l'utilisatrice ou par la remise en ordre), la
     * pile est relue toute seule. Test bon marche toutes les 500 ms : les mobis
     * de UNE case seulement.
     */
    private void suivreCase() {
        Thread t = new Thread(() -> {
            String vu = null;
            while (true) {
                try {
                    HPoint c = caseChoisie;
                    Moteur gp = AtelierLauncher.moteur();
                    EtatSalle s = gp == null ? null : gp.getFloorState();
                    if (c != null && s != null && s.inRoom()) {
                        StringBuilder b = new StringBuilder();
                        List<HFloorItem> l = s.getFurniOnTile(c.getX(), c.getY());
                        if (l != null) for (HFloorItem it : l)
                            b.append(it.getId()).append('@').append(it.getTile().getZ()).append(';');
                        String sig = c.getX() + "," + c.getY() + ":" + b;
                        if (!enRangement) {
                            if (caseRelue || (vu != null && !sig.equals(vu))) {
                                caseRelue = false;
                                lireCase(gp, c);
                            }
                            vu = sig;
                        }
                    } else vu = null;
                } catch (Throwable ignored) { }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "atelier-wired-case");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------- selection

    /**
     * Ecoute les paquets sortants et reconnait un clic sur un mobi de SOL, par
     * son identifiant — sans dependre d'un nom de paquet, qui echoue en silence
     * quand il ne se resout plus.
     */
    /**
     * L'onglet est construit AVANT que le moteur de l'Atelier soit demarre : un seul essai
     * echouait donc en silence, et rien ne reessayait. On insiste jusqu'a ce
     * qu'il soit la.
     */
    private void installerEcoute() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 600 && !ecouteInstallee; i++) {
                brancher();
                if (ecouteInstallee) {
                    Journal.debug("ecoute des piles wired active.");
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            // un seul message, a la fin des essais (pas un par seconde)
            if (!ecouteInstallee)
                Journal.erreur("Écoute des clics des wired impossible" + (erreurEcoute == null ? "." : " : " + erreurEcoute));
        }, "atelier-wired-ecoute");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void brancher() {
        if (ecouteInstallee) return;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOSERVER, m -> {
                try { examiner(gp, m); } catch (Throwable ignored) { }
            });
            ecouteInstallee = true;
        } catch (Throwable t) {
            erreurEcoute = String.valueOf(t);
        }
    }

    private volatile String erreurEcoute = null;

    private void examiner(Moteur gp, HMessage m) {
        // Appele pour chaque paquet envoye : tests bon marche d'abord, copie ensuite.
        int taille = m.getPacket().getBytesLength();
        if (taille > 40 || taille < 10) return;
        if (!WiredLecteur.actif() || enRangement) return;     // fenetre Wired fermee : rien

        EtatSalle s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;

        HPacket p = m.getPacket();                  // lectures a position fixe : pas de copie

        // Tous les offsets, pas seulement les multiples de 4.
        for (int off = 6; off + 4 <= taille; off++) {
            int v;
            try { v = p.readInteger(off); } catch (Throwable e) { break; }
            HFloorItem it = s.furniFromId(v);
            if (it != null && Wired.estBoite(classe(gp, it.getTypeId()))) { choisir(gp, it.getTile()); return; }
        }
        // Et la forme texte, que le client Flash utilise pour certains
        // identifiants : seulement les suites de chiffres du paquet, plutot que
        // de recopier tous les mobis de la salle a chaque clic.
        byte[] octets = p.toBytes();
        for (int i = 6; i < octets.length; ) {
            if (octets[i] < '0' || octets[i] > '9') { i++; continue; }
            int debut = i;
            while (i < octets.length && octets[i] >= '0' && octets[i] <= '9') i++;
            if (i - debut > 10) continue;
            try {
                long v = Long.parseLong(new String(octets, debut, i - debut,
                        java.nio.charset.StandardCharsets.ISO_8859_1));
                if (v <= 0 || v > Integer.MAX_VALUE) continue;
                HFloorItem it = s.furniFromId((int) v);
                if (it != null && Wired.estBoite(classe(gp, it.getTypeId()))) { choisir(gp, it.getTile()); return; }
            } catch (Throwable ignored) { }
        }
    }

    /** Un wired touche dans le jeu : toute sa pile est mise en valeur et lue (hors du fil des paquets). */
    private void choisir(Moteur gp, HPoint c) {
        if (c == null) return;
        MiseEnValeur.ChoixWired.pile(c.getX(), c.getY());
        Salle.tache("wired-case", () -> { lireCase(gp, c); toutChoisir(c); });
    }

    /**
     * Clic sur une case du sol, fenetre Wired ouverte : TOUTE la pile de wired
     * de la case est mise en valeur, lue et choisie dans le tableau. Une case
     * sans wired revient a « rien de choisi » : tous les wired de l'appart.
     */
    private void clicCase(HPoint c) {
        if (wiredSur(c).isEmpty()) {
            MiseEnValeur.ChoixWired.rien();
            Platform.runLater(() -> {
                selectionAuto = true;
                try { table.getSelectionModel().clearSelection(); } finally { selectionAuto = false; }
            });
            return;
        }
        MiseEnValeur.ChoixWired.pile(c.getX(), c.getY());
        Moteur gp = AtelierLauncher.moteur();
        if (gp != null) lireCase(gp, c);
        toutChoisir(c);
    }

    /** Toutes les lignes de la pile choisies dans le tableau (sans changer la mise en valeur : c'est la pile). */
    private void toutChoisir(HPoint c) {
        // apres la mise a jour du tableau par lireCase (meme file JavaFX)
        Platform.runLater(() -> {
            HPoint cc = caseChoisie;
            if (cc == null || cc.getX() != c.getX() || cc.getY() != c.getY()) return;
            selectionAuto = true;
            try { table.getSelectionModel().selectAll(); } finally { selectionAuto = false; }
        });
    }

    /** Les boites wired posees sur une case. */
    private static List<HFloorItem> wiredSur(HPoint c) {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : WiredLecteur.boitesDeLaSalle())
            if (it.getTile().getX() == c.getX() && it.getTile().getY() == c.getY()) r.add(it);
        return r;
    }

    /** Lit toute la pile d'une case et la classe. */
    private void lireCase(Moteur gp, HPoint c) {
        if (c == null) return;
        caseChoisie = c;
        List<LigneWired> lue = new ArrayList<>();

        EtatSalle s = gp.getFloorState();
        List<HFloorItem> dessus;
        try { dessus = s.getFurniOnTile(c.getX(), c.getY()); }
        catch (Throwable t) { dire("Lecture de la case impossible : " + t); return; }
        if (dessus == null) dessus = Collections.emptyList();

        int nonWired = 0;
        for (HFloorItem it : dessus) {
            String cls = classe(gp, it.getTypeId());
            if (!Wired.estBoite(cls)) { nonWired++; continue; }   // mobi wired ou autre : ne bouge pas
            lue.add(new LigneWired(it.getId(), Wired.rang(cls),
                    nomLisible(gp, cls), it.getTile().getZ()));
        }
        // Presentee dans l'ordre reel, du bas vers le haut.
        lue.sort(Comparator.comparingDouble(l -> Double.parseDouble(l.getAltitude())));
        piles = List.copyOf(lue);
        final List<LigneWired> pile = piles;

        final int autres = nonWired;
        Platform.runLater(() -> {
            caseLbl.setText("Case (" + c.getX() + "," + c.getY() + ")   ·   "
                    + pile.size() + " wired"
                    + (autres > 0 ? "   ·   " + autres + " autre(s) mobi(s), sans bouger" : ""));
            // Meme pile (relecture automatique sans changement) : on ne touche a rien.
            boolean pareil = table.getItems() != null && table.getItems().size() == pile.size();
            for (int i = 0; pareil && i < pile.size(); i++)
                pareil = table.getItems().get(i).id == pile.get(i).id
                        && table.getItems().get(i).getAltitude().equals(pile.get(i).getAltitude());
            if (!pareil) {
                selectionAuto = true;
                try { table.setItems(FXCollections.observableArrayList(pile)); } finally { selectionAuto = false; }
            }
            remettre.setDisable(pile.size() < 2 || rangementPris.get());
            dire(pile.size() < 2
                    ? "Il faut au moins deux wired sur la case pour les réordonner."
                    : verdict());
        });
    }

    /** Dit si la pile est deja dans l'ordre, et ce qui cloche sinon. */
    private String verdict() {
        List<LigneWired> pile = piles;
        for (int i = 1; i < pile.size(); i++) {
            if (pile.get(i).getRang() < pile.get(i - 1).getRang())
                return "Pile dans le désordre : « " + pile.get(i).getType()
                        + " » est au-dessus de « " + pile.get(i - 1).getType() + " ».";
        }
        return "Pile déjà dans le bon ordre.";
    }

    // ------------------------------------------------------------ remise en ordre

    /** Ecart tolere entre l'altitude lue et l'altitude voulue (le jeu compte en centiemes). */
    static final double TOLERANCE = 0.015;
    /** Attente de la mise a jour du jeu apres une ecriture (un nouvel envoi ensuite si rien). */
    private static final long ARRIVEE_MS = 2500;
    /** Ecritures d'une meme hauteur avant d'abandonner ce wired pour la passe. */
    private static final int ENVOIS = 2;
    /** Relecture finale : la pile doit rester en place ce temps-la (dernieres mises a jour du jeu)... */
    private static final long CALME_MS = 250;
    /** ...dans cette limite ; au-dela, elle est tenue pour mal rangee. */
    private static final long RELECTURE_MS = 900;
    /** Pas du suivi des arrivees (lecture de l'etat de la salle en memoire, sans envoi). */
    private static final long SUIVI_MS = 15;
    /** Passes « par relais » au plus, apres la passe directe (chacune : tout monter, puis redescendre). */
    private static final int PASSES = 3;

    /**
     * Un wired de la pile a ranger, lu frais dans la salle au moment du
     * rangement (pas le tableau affiche, qui peut etre perime).
     */
    static final class Place {
        final int id, rang;
        final double z, h;
        final String nom;
        /** Altitude visee, en centiemes exacts. */
        double voulu;
        Place(int id, int rang, double z, double h, String nom) {
            this.id = id; this.rang = rang; this.z = z; this.h = h; this.nom = nom;
        }
    }

    /** Une ecriture de @altitude : ce wired (id), a cette hauteur ; montee = vers le relais. */
    record Etape(int id, double z, boolean montee, String nom) { }

    /**
     * Une ecriture de la passe directe, et les ecritures (leurs numeros dans
     * le plan) qui doivent etre ARRIVEES avant elle : celles qui liberent sa
     * place, et la precedente du meme wired.
     */
    record Coup(Etape etape, int[] apres) { }

    /**
     * Calcul pur de la remise en ordre (sans le jeu) : ordre voulu, altitudes
     * visees, wired deja en place, relais et suite des ecritures. Teste hors du jeu.
     */
    static final class Rangement {
        private Rangement() { }

        /** Ordre voulu du bas vers le haut : par rang, et a rang egal dans l'ordre actuel. */
        static List<Place> ordonner(Collection<Place> pile) {
            List<Place> r = new ArrayList<>(pile);
            r.sort(Comparator.comparingDouble((Place p) -> p.z).thenComparingInt(p -> p.id));   // ordre actuel
            r.sort(Comparator.comparingInt((Place p) -> p.rang));           // tri stable : egalites gardees
            return r;
        }

        /**
         * true si les RANGS de la pile ne se suivent pas du bas vers le haut
         * (un wired au-dessus d'un autre de rang plus grand). Deux wired a la
         * meme altitude ne comptent pas comme un desordre. Seules ces piles
         * sont corrigees par « tous les wired de l'appart » ; une pile dans le
         * bon ordre mais avec des trous ne recoit aucun envoi.
         */
        static boolean desordre(Collection<Place> pile) {
            List<Place> l = new ArrayList<>();
            for (Place p : pile) if (p.z >= 0) l.add(p);
            l.sort(Comparator.comparingLong((Place p) -> Math.round(p.z * 100)).thenComparingInt(p -> p.rang));
            for (int i = 1; i < l.size(); i++) if (l.get(i).rang < l.get(i - 1).rang) return true;
            return false;
        }

        /** Hauteur de depart : le plus bas wired de la pile, sinon le sol de la case. */
        static double depart(Collection<Place> pile, double sol) {
            double d = Double.MAX_VALUE;
            for (Place p : pile) if (p.z >= 0) d = Math.min(d, p.z);
            return d == Double.MAX_VALUE ? Math.max(0, sol) : d;
        }

        /**
         * Chaque wired se pose sur le precedent : altitude = depart + hauteurs
         * de ceux d'en dessous. Le calcul se fait en centiemes entiers (pas de
         * derive des arrondis : 0,1 + 0,2 ne donne pas 0,30000000000000004).
         */
        static void viser(List<Place> ordre, double depart) {
            long c = Math.round(depart * 100);
            for (Place p : ordre) {
                p.voulu = c / 100.0;
                c += Math.round(p.h * 100);
            }
        }

        static boolean enPlace(double z, double voulu) {
            return z >= 0 && Math.abs(z - voulu) <= TOLERANCE;
        }

        /** Les wired qui ne sont pas a leur altitude (z < 0 : disparu). */
        static List<Place> malPlaces(List<Place> ordre, java.util.function.IntToDoubleFunction z) {
            List<Place> r = new ArrayList<>();
            for (Place p : ordre) if (!enPlace(z.applyAsDouble(p.id), p.voulu)) r.add(p);
            return r;
        }

        /** Les intervalles [a, a+ha] et [b, b+hb] se recouvrent (au-dela de la tolerance). */
        static boolean recouvre(double a, double ha, double b, double hb) {
            return a < b + Math.max(hb, 0) - TOLERANCE && b < a + Math.max(ha, 0) - TOLERANCE;
        }

        /**
         * Passe DIRECTE : chaque wired mal place va droit a sa hauteur visee, des
         * que sa place est libre (aucun wired pas encore range ne l'occupe). Les
         * wired deja a leur place ne recoivent rien. Quand plus aucune place
         * n'est libre (permutation circulaire, par exemple deux wired a
         * echanger), UN wired qui gene monte au relais, au-dessus de tout, et
         * libere la sienne ; il redescendra a son tour. On n'ecrit donc jamais
         * un wired par-dessus un autre qui n'est pas encore range.
         *
         * Chaque ecriture dit lesquelles doivent etre arrivees avant elle : les
         * ecritures sans lien partent sans attendre (au rythme commun).
         * Ecritures : les mal places + une par cycle, contre deux par wired
         * deplace pour la passe par relais.
         */
        static List<Coup> direct(List<Place> ordre, java.util.function.IntToDoubleFunction z) {
            int n = ordre.size();
            double[] pos = new double[n];
            boolean[] fini = new boolean[n], auRelais = new boolean[n];
            int[] dernier = new int[n];
            double haut = 0;
            for (int i = 0; i < n; i++) {
                Place p = ordre.get(i);
                pos[i] = z.applyAsDouble(p.id);
                fini[i] = pos[i] < 0 || enPlace(pos[i], p.voulu);       // disparu, ou deja a sa place
                dernier[i] = -1;
                haut = Math.max(haut, Math.max(pos[i], p.voulu) + Math.max(p.h, 0));
            }
            long relais = Math.round(Math.ceil(haut + 1) * 100);
            List<Coup> r = new ArrayList<>();
            List<double[]> quitte = new ArrayList<>();                   // place laissee par chaque ecriture
            while (true) {
                int libre = -1, gene = -1;
                boolean reste = false;
                for (int i = 0; i < n && libre < 0; i++) {
                    if (fini[i]) continue;
                    reste = true;
                    int occupant = occupant(ordre, pos, fini, i);
                    if (occupant < 0) libre = i;
                    else if (gene < 0 && !auRelais[occupant]) gene = occupant;
                }
                if (!reste) break;
                if (libre >= 0) {
                    Place p = ordre.get(libre);
                    List<Integer> apres = new ArrayList<>();
                    if (dernier[libre] >= 0) apres.add(dernier[libre]);
                    for (int k = 0; k < quitte.size(); k++) {
                        double[] q = quitte.get(k);
                        if (k != dernier[libre] && recouvre(q[0], q[1], p.voulu, p.h)) apres.add(k);
                    }
                    quitte.add(new double[]{pos[libre], p.h});
                    r.add(new Coup(new Etape(p.id, p.voulu, false, p.nom), entiers(apres)));
                    dernier[libre] = r.size() - 1;
                    pos[libre] = p.voulu;
                    fini[libre] = true;
                } else {
                    if (gene < 0) break;                                // impossible : un occupant au relais ne gene rien
                    Place p = ordre.get(gene);
                    double zr = relais / 100.0;
                    relais += Math.max(1, Math.round(p.h * 100));
                    quitte.add(new double[]{pos[gene], p.h});
                    r.add(new Coup(new Etape(p.id, zr, true, p.nom),
                            dernier[gene] >= 0 ? new int[]{dernier[gene]} : new int[0]));
                    dernier[gene] = r.size() - 1;
                    pos[gene] = zr;
                    auRelais[gene] = true;
                }
            }
            return r;
        }

        /** Un wired pas encore range qui occupe la place visee de ordre[i], sinon -1. */
        private static int occupant(List<Place> ordre, double[] pos, boolean[] fini, int i) {
            Place p = ordre.get(i);
            for (int j = 0; j < ordre.size(); j++) {
                if (j == i || fini[j] || pos[j] < 0) continue;
                if (recouvre(pos[j], ordre.get(j).h, p.voulu, p.h)) return j;
            }
            return -1;
        }

        private static int[] entiers(List<Integer> l) {
            int[] r = new int[l.size()];
            for (int i = 0; i < r.length; i++) r[i] = l.get(i);
            return r;
        }

        /**
         * Socle : combien de wired du BAS sont deja a leur place et n'ont aucun
         * wired a deplacer en dessous de leur sommet. Eux ne bougent pas.
         */
        static int socle(List<Place> ordre, java.util.function.IntToDoubleFunction z) {
            int k = 0;
            while (k < ordre.size() && enPlace(z.applyAsDouble(ordre.get(k).id), ordre.get(k).voulu)) k++;
            while (k > 0) {
                Place haut = ordre.get(k - 1);
                double sommet = haut.voulu + Math.max(haut.h, 0) - TOLERANCE;
                boolean libre = true;
                for (int i = k; i < ordre.size() && libre; i++) {
                    double zz = z.applyAsDouble(ordre.get(i).id);
                    if (zz >= 0 && zz < sommet) libre = false;
                }
                if (libre) break;
                k--;                    // un wired a deplacer est coince dans le socle : le socle raccourcit
            }
            return k;
        }

        /**
         * Altitudes de relais des wired a deplacer (a partir de « debut »), toutes
         * AU-DESSUS de la pile actuelle ET de la pile voulue (1 de marge au moins),
         * chacune a sa place relative : aucun chevauchement.
         */
        static double[] relais(List<Place> ordre, int debut, java.util.function.IntToDoubleFunction z) {
            double haut = 0;
            for (Place p : ordre) {
                double zz = z.applyAsDouble(p.id);
                haut = Math.max(haut, Math.max(zz, p.voulu) + Math.max(p.h, 0));
            }
            long base = Math.round(Math.ceil(haut + 1) * 100);
            double[] r = new double[ordre.size()];
            if (debut >= ordre.size()) return r;
            double v0 = ordre.get(debut).voulu;
            for (int i = debut; i < r.length; i++)
                r[i] = (base + Math.round((ordre.get(i).voulu - v0) * 100)) / 100.0;
            return r;
        }

        /**
         * La suite des ecritures d'une passe PAR RELAIS (secours, quand la passe
         * directe n'a pas suffi). D'abord la MONTEE : chaque wired a deplacer
         * (hors socle) va a son relais, en commencant par le plus haut, pour que
         * la pile soit hors de portee. Puis la DESCENTE, du BAS vers le HAUT :
         * chaque wired va a sa hauteur visee ; il n'a alors en dessous que des
         * wired deja places, et rien entre eux et lui.
         */
        static List<Etape> plan(List<Place> ordre, java.util.function.IntToDoubleFunction z) {
            int k = socle(ordre, z);
            List<Etape> r = new ArrayList<>();
            if (k >= ordre.size()) return r;
            double[] rel = relais(ordre, k, z);
            List<Integer> montee = new ArrayList<>();
            for (int i = k; i < ordre.size(); i++) if (z.applyAsDouble(ordre.get(i).id) >= 0) montee.add(i);
            montee.sort(Comparator.comparingDouble((Integer i) -> -z.applyAsDouble(ordre.get(i).id)));
            for (int i : montee) r.add(new Etape(ordre.get(i).id, rel[i], true, ordre.get(i).nom));
            for (int i = k; i < ordre.size(); i++) {
                Place p = ordre.get(i);
                if (z.applyAsDouble(p.id) < 0) continue;            // disparu de la case
                r.add(new Etape(p.id, p.voulu, false, p.nom));
            }
            return r;
        }
    }

    /**
     * Une pile a ranger : sa case, son ordre voulu, le plan de la passe
     * directe et le suivi de chaque ecriture.
     */
    private static final class Chantier {
        static final int ATTENTE = 0, ENVOYE = 1, ARRIVE = 2, RATE = 3;
        final HPoint c;
        final List<Place> ordre;
        final java.util.function.IntToDoubleFunction zDe;
        List<Coup> coups = List.of();
        int[] etat = new int[0], envois = new int[0];
        long[] echeance = new long[0];
        /** Une ecriture de la passe directe n'est pas arrivee : la suite attend la passe par relais. */
        boolean bloque = false;
        final Set<Integer> bouges = new HashSet<>();
        int passes = 0;
        List<Place> faux = List.of();

        Chantier(Moteur gp, HPoint c, List<Place> ordre) {
            this.c = c; this.ordre = ordre;
            this.zDe = id -> altitudeSur(gp, id, c);
        }

        void planifier() {
            coups = Rangement.direct(ordre, zDe);
            etat = new int[coups.size()];
            envois = new int[coups.size()];
            echeance = new long[coups.size()];
        }

        boolean fini() {
            if (bloque) return true;
            for (int e : etat) if (e != ARRIVE) return false;
            return true;
        }

        String ou() { return "(" + c.getX() + "," + c.getY() + ")"; }
    }

    /** Demande d'arret de la remise en ordre en cours (bouton « Arrêter »). */
    private final java.util.concurrent.atomic.AtomicBoolean arret = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Lit une pile et calcule son ordre voulu et ses altitudes visees. null
     * s'il y a moins de deux wired sur la case.
     */
    private static Chantier preparer(Moteur gp, HPoint c) {
        List<Place> pile = lirePile(gp, c);
        if (pile.size() < 2) return null;
        List<Place> ordre = Rangement.ordonner(pile);
        double depart = Rangement.depart(pile, Salle.hauteurSol(c.getX(), c.getY()));
        Rangement.viser(ordre, depart);
        StringBuilder plan = new StringBuilder();
        for (Place p : ordre)
            plan.append(String.format(Locale.ROOT, " [%d %s rang=%d z=%.2f h=%.2f -> %.2f]",
                    p.id, p.nom, p.rang, p.z, p.h, p.voulu));
        Journal.debug("rangement wired (" + c.getX() + "," + c.getY() + ") depart=" + depart + plan);
        return new Chantier(gp, c, ordre);
    }

    /**
     * Remet la pile choisie en ordre en ecrivant @altitude sur chaque wired
     * (voir ranger).
     */
    private void remettreEnOrdre() {
        Moteur gp = AtelierLauncher.moteur();
        HPoint c = caseChoisie;
        if (gp == null || c == null) { dire("Aucune case choisie."); return; }

        // Lue fraiche dans la salle, pas dans le tableau (qui peut etre perime).
        Chantier ch = preparer(gp, c);
        if (ch == null) {
            dire("Il faut au moins deux wired sur la case pour les réordonner.");
            return;
        }
        if (Rangement.malPlaces(ch.ordre, ch.zDe).isEmpty()) {
            dire("");
            Journal.succes("Pile de wired déjà dans le bon ordre.");
            return;
        }
        long t0 = System.currentTimeMillis();
        if (!ranger(gp, List.of(ch))) return;
        Journal.debug("rangement : pile " + ch.ou() + " en " + (System.currentTimeMillis() - t0) + " ms");

        dire("");
        if (arret.get() && !ch.faux.isEmpty()) { Journal.erreur("Rangement arrêté : " + bilanFaux(ch) + "."); return; }
        if (ch.faux.isEmpty()) {
            Journal.succes(Ui.accorder("Pile de wired " + ch.ou() + " rangée et vérifiée : "
                    + ch.bouges.size() + " wired remis à leur place"
                    + (ch.passes == 0 ? "." : ", avec " + ch.passes + " passe(s) par relais.")));
            return;
        }
        int n = ch.faux.size();
        Journal.erreur(n + " wired " + (n == 1 ? "reste mal placé" : "restent mal placés")
                + " sur la case " + ch.ou() + " : " + bilanFaux(ch)
                + ". Le jeu n'a pas suivi : réessaie, ou vérifie que tu as les droits.");
    }

    /**
     * Toutes les piles de wired de l'appart dont l'ordre n'est pas bon
     * (Rangement.desordre), remises en ordre en une seule action. Les piles
     * deja dans l'ordre ne recoivent aucun envoi.
     */
    private void toutRemettreEnOrdre() {
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { dire("L'Atelier n'est pas connecté au jeu."); return; }
        dire("Lecture des piles de l'appart…");
        Map<Long, HPoint> cases = new LinkedHashMap<>();
        for (HFloorItem it : WiredLecteur.boitesDeLaSalle()) {
            HPoint t = it.getTile();
            cases.putIfAbsent(((long) t.getX() << 32) | (t.getY() & 0xffffffffL), new HPoint(t.getX(), t.getY()));
        }
        List<Chantier> aCorriger = new ArrayList<>();
        for (HPoint c : cases.values()) {
            Chantier ch = preparer(gp, c);
            if (ch != null && Rangement.desordre(ch.ordre)) aCorriger.add(ch);
        }
        int total = cases.size();
        if (aCorriger.isEmpty()) {
            dire("");
            Journal.succes(Ui.accorder(total == 0 ? "Aucun wired dans l'appart."
                    : "Toutes les piles de wired sont déjà dans l'ordre (" + total + " pile(s) dans l'appart)."));
            return;
        }
        long t0 = System.currentTimeMillis();
        if (!ranger(gp, aCorriger)) return;
        Journal.debug("rangement : " + aCorriger.size() + " pile(s) en " + (System.currentTimeMillis() - t0) + " ms");

        dire("");
        int ok = 0;
        StringBuilder d = new StringBuilder();
        for (Chantier ch : aCorriger) {
            if (ch.faux.isEmpty()) { ok++; continue; }
            if (d.length() > 0) d.append(" ; ");
            d.append("case ").append(ch.ou()).append(" : ").append(bilanFaux(ch));
        }
        String compte = ok + " pile(s) remise(s) en ordre sur " + aCorriger.size() + " à corriger ("
                + total + " pile(s) dans l'appart)";
        if (ok == aCorriger.size()) { Journal.succes(Ui.accorder(compte + ".")); return; }
        Journal.erreur(Ui.accorder((arret.get() ? "Rangement arrêté. " : "") + compte
                + ". Restent mal placés : " + d + "."));
    }

    /** « « Nom » à 1,20 au lieu de 0,60 ; « Autre » a quitté la case ». */
    private static String bilanFaux(Chantier ch) {
        StringBuilder d = new StringBuilder();
        for (Place p : ch.faux) {
            double z = ch.zDe.applyAsDouble(p.id);
            if (d.length() > 0) d.append(" ; ");
            d.append("« ").append(p.nom).append(" » ");
            d.append(z < 0 ? "a quitté la case"
                    : "à " + hauteurTexte(z) + " au lieu de " + hauteurTexte(p.voulu));
        }
        return d.toString();
    }

    /**
     * Range ces piles en ecrivant @altitude sur leurs wired.
     *
     * @altitude est une variable de type Mobi, inscriptible : on l'ecrit
     * directement, sans deplacer ni reposer aucun mobi. La variable est celle
     * d'OutilMiroir.Altitude, toujours verifiee sur un vrai mobi avant d'etre
     * tenue pour bonne (la premiere ecriture de la session passe par mettre()).
     *
     * 1. Passe DIRECTE (Rangement.direct) : chaque wired mal place va droit a
     *    sa hauteur, des que sa place est libre ; les piles avancent ensemble,
     *    envois entrelaces au rythme commun (Salle.espacer) : une ecriture ne
     *    depend que des ecritures de SA pile qui liberent sa place, suivies
     *    par leur arrivee dans l'etat de la salle (pas d'attente fixe).
     * 2. Relecture : la pile doit rester en place un court temps de calme.
     * 3. Secours seulement pour les piles encore de travers : passes PAR RELAIS
     *    (tout monter au-dessus de la pile, puis redescendre du bas vers le haut).
     *
     * Remplit faux, bouges et passes de chaque chantier. false si @altitude est
     * introuvable (erreur deja dite).
     */
    private boolean ranger(Moteur gp, List<Chantier> chantiers) {
        for (Chantier ch : chantiers) ch.planifier();
        int total = 0;
        for (Chantier ch : chantiers) total += ch.coups.size();
        Journal.debug("rangement : passe directe, " + chantiers.size() + " pile(s), " + total + " écriture(s)");

        // Premiere ecriture de la session : elle verifie (ou trouve) @altitude.
        if (!OutilMiroir.Altitude.confirmee()) {
            for (Chantier ch : chantiers) {
                if (ch.coups.isEmpty()) continue;
                Etape e = ch.coups.get(0).etape();              // sans dependance : la premiere du plan
                if (!verifierAltitude(gp, e, ch.c)) return false;
                ch.etat[0] = Chantier.ENVOYE;
                ch.envois[0] = 1;
                ch.echeance[0] = System.currentTimeMillis() + ARRIVEE_MS;
                if (e.montee()) ch.bouges.remove(e.id()); else ch.bouges.add(e.id());
                break;
            }
        }

        avancer(chantiers, total);
        attendreCalme(chantiers);

        // Secours : passes par relais, pile par pile, pour celles encore de travers.
        for (Chantier ch : chantiers) {
            while (!ch.faux.isEmpty() && ch.passes < PASSES && !arret.get()) {
                ch.passes++;
                passeParRelais(gp, ch);
                attendreCalme(List.of(ch));
                Journal.debug("rangement " + ch.ou() + " : passe par relais " + ch.passes
                        + ", mal placés : " + ch.faux.size());
            }
        }
        return true;
    }

    /**
     * La passe directe de toutes les piles : a chaque tour, les arrivees sont
     * relevees dans l'etat de la salle, puis chaque pile envoie au plus UNE
     * ecriture prete (toutes celles dont elle depend sont arrivees). Les
     * ecritures deja faites (wired deja a sa hauteur) ne partent pas.
     */
    private void avancer(List<Chantier> chantiers, int total) {
        int faits = 0;
        while (!arret.get()) {
            long t = System.currentTimeMillis();
            boolean reste = false, envoye = false;
            faits = 0;
            // (1) arrivees et delais
            for (Chantier ch : chantiers) {
                for (int k = 0; k < ch.coups.size(); k++) {
                    if (ch.etat[k] == Chantier.ARRIVE) { faits++; continue; }
                    if (ch.etat[k] != Chantier.ENVOYE) continue;
                    Etape e = ch.coups.get(k).etape();
                    double z = ch.zDe.applyAsDouble(e.id());
                    if (Rangement.enPlace(z, e.z())) { ch.etat[k] = Chantier.ARRIVE; faits++; }
                    else if (z < 0) { ch.etat[k] = Chantier.RATE; ch.bloque = true;
                        Journal.debug("rangement " + ch.ou() + " : wired " + e.id() + " plus sur la case."); }
                    else if (t > ch.echeance[k]) {
                        if (ch.envois[k] < ENVOIS) ch.etat[k] = Chantier.ATTENTE;    // renvoyee au tour suivant
                        else {
                            ch.etat[k] = Chantier.RATE; ch.bloque = true;
                            Journal.debug("rangement " + ch.ou() + " : " + e.id() + (e.montee() ? " pas monté à " : " pas arrivé à ")
                                    + hauteurTexte(e.z()) + " (lu " + hauteurTexte(z) + ")");
                        }
                    }
                }
            }
            // (2) un envoi par pile prete, piles entrelacees
            for (Chantier ch : chantiers) {
                if (ch.fini()) continue;
                reste = true;
                for (int k = 0; k < ch.coups.size(); k++) {
                    if (ch.etat[k] != Chantier.ATTENTE || !pret(ch, k)) continue;
                    Etape e = ch.coups.get(k).etape();
                    double z = ch.zDe.applyAsDouble(e.id());
                    if (z < 0) { ch.etat[k] = Chantier.RATE; ch.bloque = true; break; }
                    if (Rangement.enPlace(z, e.z())) { ch.etat[k] = Chantier.ARRIVE; continue; }   // deja la : rien a envoyer
                    if (arret.get()) break;
                    Salle.espacer();
                    try { OutilMiroir.Altitude.ecrire(e.id(), e.z()); } finally { Salle.envoiFait(); }
                    ch.etat[k] = Chantier.ENVOYE;
                    ch.envois[k]++;
                    ch.echeance[k] = System.currentTimeMillis() + ARRIVEE_MS;
                    if (e.montee()) ch.bouges.remove(e.id()); else ch.bouges.add(e.id());
                    envoye = true;
                    break;
                }
            }
            dire("Mise en ordre : " + faits + " / " + total + "…");
            progression(total == 0 ? 1 : (double) faits / total);
            if (!reste) break;
            if (!envoye) sommeil(SUIVI_MS);
        }
    }

    /** Toutes les ecritures dont celle-ci depend sont arrivees. */
    private static boolean pret(Chantier ch, int k) {
        for (int a : ch.coups.get(k).apres()) if (ch.etat[a] != Chantier.ARRIVE) return false;
        return true;
    }

    /**
     * Relecture finale : chaque pile doit etre en place et y rester CALME_MS
     * (une mise a jour tardive du jeu la defait parfois) ; au plus RELECTURE_MS.
     * Remplit faux.
     */
    private void attendreCalme(List<Chantier> chantiers) {
        long debut = System.currentTimeMillis(), stable = debut;
        while (true) {
            boolean tout = true;
            for (Chantier ch : chantiers) {
                ch.faux = Rangement.malPlaces(ch.ordre, ch.zDe);
                if (!ch.faux.isEmpty()) tout = false;
            }
            long t = System.currentTimeMillis();
            if (!tout) stable = t;
            if (tout && t - stable >= CALME_MS) return;
            if (t - debut >= RELECTURE_MS || arret.get()) return;
            sommeil(SUIVI_MS * 2);
        }
    }

    /**
     * Une passe de secours par relais (Rangement.plan), wired par wired, chacun
     * attendu a sa hauteur avant le suivant. Les wired deja a la hauteur de
     * l'etape ne recoivent rien.
     */
    private void passeParRelais(Moteur gp, Chantier ch) {
        List<Etape> etapes = Rangement.plan(ch.ordre, ch.zDe);
        Set<Integer> ids = new HashSet<>();
        for (Place p : ch.ordre) ids.add(p.id);
        int n = 0;
        for (Etape e : etapes) {
            if (arret.get()) return;
            n++;
            // jamais une hauteur sur un mobi qui n'est pas un wired de CETTE pile
            if (!ids.contains(e.id())) { Journal.debug("rangement : id " + e.id() + " hors pile, ignoré"); continue; }
            double z = ch.zDe.applyAsDouble(e.id());
            if (z < 0) { Journal.debug("rangement : wired " + e.id() + " plus sur la case."); continue; }
            dire("Case " + ch.ou() + ", passe par relais " + ch.passes + " : "
                    + (e.montee() ? "montée" : "mise en place") + " " + n + " / " + etapes.size() + "…");
            progression((double) n / Math.max(1, etapes.size()));
            if (Rangement.enPlace(z, e.z())) continue;
            boolean arrive = ecrire(gp, e, ch.c);
            if (!e.montee()) ch.bouges.add(e.id());
            if (!arrive)
                Journal.debug("rangement : " + e.id() + (e.montee() ? " pas monté à " : " pas arrivé à ")
                        + hauteurTexte(e.z()) + " (lu " + hauteurTexte(ch.zDe.applyAsDouble(e.id())) + ")");
        }
    }

    /**
     * Premiere ecriture de la session : OutilMiroir.Altitude.mettre verifie la
     * variable retenue sur ce wired, sinon la cherche (liste du jeu, puis
     * essais). false (erreur dite une fois) si @altitude reste introuvable.
     */
    private boolean verifierAltitude(Moteur gp, Etape e, HPoint c) {
        dire("Vérification de @altitude…");
        boolean dejaRate = OutilMiroir.Altitude.rate();
        Journal.debug("rangement : @altitude " + OutilMiroir.Altitude.variable()
                + " pas encore vérifiée, essai sur le wired " + e.id() + " -> " + e.z());
        Salle.espacer();
        try { OutilMiroir.Altitude.mettre(e.id(), e.z()); } finally { Salle.envoiFait(); }
        if (OutilMiroir.Altitude.confirmee()) {
            String var = OutilMiroir.Altitude.variable();
            Platform.runLater(() -> {
                altLbl.setText("@altitude vérifiée — variable « " + var + " »");
                altLbl.getStyleClass().setAll("label", "etat-ok");
            });
            return true;
        }
        dire("");
        // Altitude.trouver dit deja l'erreur quand sa recherche echoue ; sinon on la dit ici.
        if (dejaRate || !OutilMiroir.Altitude.rate())
            Journal.erreur("@altitude introuvable : règle-la une fois dans l'éditeur :wired, puis recommence.");
        return false;
    }

    /**
     * Ecrit la hauteur et suit l'arrivee du wired ; sans nouvelles, un second
     * envoi (le premier a pu etre ignore). true si le wired est arrive.
     */
    private boolean ecrire(Moteur gp, Etape e, HPoint c) {
        for (int i = 0; i < ENVOIS && !arret.get(); i++) {
            Salle.espacer();
            try { OutilMiroir.Altitude.ecrire(e.id(), e.z()); } finally { Salle.envoiFait(); }
            if (attendreArrivee(gp, e.id(), e.z(), c)) return true;
        }
        return false;
    }

    /** Les boites wired de la case, lues maintenant dans la salle. */
    private static List<Place> lirePile(Moteur gp, HPoint c) {
        List<Place> r = new ArrayList<>();
        List<HFloorItem> l;
        try { l = gp.getFloorState().getFurniOnTile(c.getX(), c.getY()); }
        catch (Throwable t) { return r; }
        if (l == null) return r;
        for (HFloorItem it : l) {
            String cls = classe(gp, it.getTypeId());
            if (!Wired.estBoite(cls)) continue;        // mobis wired (dalle, antenne...) : ne bougent pas
            r.add(new Place(it.getId(), Wired.rang(cls).ordre, it.getTile().getZ(),
                    hauteurDe(gp, it.getId()), nomLisible(gp, cls)));
        }
        return r;
    }

    /** Attend qu'un wired soit a l'altitude voulue sur sa case (mise a jour du jeu recue). */
    private static boolean attendreArrivee(Moteur gp, int id, double voulu, HPoint c) {
        long fin = System.currentTimeMillis() + ARRIVEE_MS;
        while (true) {
            if (Rangement.enPlace(altitudeSur(gp, id, c), voulu)) return true;
            if (System.currentTimeMillis() > fin) return false;
            sommeil(SUIVI_MS);
        }
    }

    private static String hauteurTexte(double z) {
        return String.format(Locale.FRANCE, "%.2f", z);
    }

    /** Altitude actuelle d'un mobi s'il est toujours sur la case c, sinon -1. */
    private static double altitudeSur(Moteur gp, int id, HPoint c) {
        try {
            HFloorItem it = gp.getFloorState().furniFromId(id);
            if (it != null && it.getTile().getX() == c.getX() && it.getTile().getY() == c.getY())
                return it.getTile().getZ();
        } catch (Throwable ignored) { }
        return -1;
    }

    /** Hauteur occupee par un mobi, lue sur lui-meme. */
    private static double hauteurDe(Moteur gp, int id) {
        try {
            HFloorItem it = gp.getFloorState().furniFromId(id);
            double h = Salle.hauteur(it);
            if (h > 0) return h;
        } catch (Throwable ignored) { }
        return 0.30;     // hauteur habituelle d'un wired, a defaut
    }

    // ---------------------------------------------------------------- outils

    private static String classe(Moteur gp, int typeId) {
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) return fd.getFloorItemName(typeId);
        } catch (Throwable ignored) { }
        return null;
    }

    private static String nomLisible(Moteur gp, String cls) {
        if (cls == null) return "(inconnu)";
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                Furnidata.Mobi d = fd.getFloorItemDetails(cls);
                if (d != null && d.name != null && !d.name.isEmpty()) {
                    // Les noms wired commencent tous par « Effet WIRED : » etc. :
                    // on garde la partie utile.
                    int i = d.name.indexOf(':');
                    return i > 0 && i < d.name.length() - 2
                            ? d.name.substring(i + 1).trim() : d.name;
                }
            }
        } catch (Throwable ignored) { }
        return cls;
    }

    private static void sommeil(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void dire(String s) {
        Platform.runLater(() -> etat.setText(s));
    }
}

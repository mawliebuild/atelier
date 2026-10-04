package atelier;

import extension.GPresets;
import game.FloorState;
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
 * Section Wired, en deux volets : Dalle magique et Ordre.
 *
 * Ordre : on choisit une case de la salle, on lit la pile de wired qui s'y
 * trouve, et on la remet dans l'ordre de fonctionnement de Habbo.
 *
 * Methode de remise en ordre : les wired sont retires de la case puis reposes
 * un par un dans le bon ordre. Chacun se pose sur le precedent, donc la pile se
 * reconstruit SANS TROU, sans avoir a calculer les altitudes. Corriger les
 * altitudes une par une demanderait une dalle magique et laisserait des ecarts
 * des qu'un wired n'a pas la hauteur attendue.
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
    private Button remettre;
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
    /** Ids des wired choisis dans le tableau : lus hors fil JavaFX par MiseEnValeur. */
    private volatile List<Integer> idsChoisis = List.of();
    /** Case cliquee dans le jeu (fenetre Wired ouverte) : seuls ses wired sont mis en valeur. */
    private volatile HPoint caseValeur = null;

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
        // fenetre Wired ouverte : le wired choisi dans le tableau est mis en valeur dans le jeu
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<LigneWired>) ch -> {
                    List<Integer> ids = new ArrayList<>();
                    for (LigneWired l : table.getSelectionModel().getSelectedItems()) if (l != null) ids.add(l.id);
                    idsChoisis = List.copyOf(ids);
                });
        MiseEnValeur.fournir("wired", this::jetonsMisEnValeur);
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
        remettre.setOnAction(e -> {
            if (!rangementPris.compareAndSet(false, true)) return;     // deja en cours
            remettre.setDisable(true);
            enRangement = true;
            Salle.tache("wired", () -> {
                try { remettreEnOrdre(); }
                catch (Throwable t) { dire(""); Journal.erreur("Rangement des wired interrompu", t); }
                finally {
                    enRangement = false;
                    caseRelue = true;
                    rangementPris.set(false);
                    Platform.runLater(() -> remettre.setDisable(piles.size() < 2));
                }
            });
        });

        Label aide = Ui.aide("Clique un wired dans le jeu : toute la pile de sa case "
                + "est lue. L'ordre visé, du bas vers le haut : déclencheur, sélecteur, "
                + "sélecteur filtre, condition, effet, effet envoyer un signal, "
                + "effet négatif, add-on.");

        VBox v = new VBox(12,
                Ui.bloc("Case choisie", caseLbl, aide),
                Ui.bloc("Pile de wired", table),
                Ui.bloc("Réglage d'altitude", altLbl,
                        Ui.aide("Rien à faire : l'Atelier cherche @altitude tout seul "
                                + "au premier rangement, en vérifiant sur un wired.")),
                remettre,
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        installerEcoute();
        suivreCase();
        return v;
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
                    GPresets gp = AtelierLauncher.moteur();
                    FloorState s = gp == null ? null : gp.getFloorState();
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
        GPresets gp = AtelierLauncher.moteur();
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

    private void examiner(GPresets gp, HMessage m) {
        // Appele pour chaque paquet envoye : tests bon marche d'abord, copie ensuite.
        int taille = m.getPacket().getBytesLength();
        if (taille > 40 || taille < 10) return;
        if (!WiredLecteur.actif() || enRangement) return;     // fenetre Wired fermee : rien

        FloorState s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;

        HPacket p = m.getPacket();                  // lectures a position fixe : pas de copie
        apprendreAltitude(gp, p, taille, s);

        // Tous les offsets, pas seulement les multiples de 4.
        for (int off = 6; off + 4 <= taille; off++) {
            int v;
            try { v = p.readInteger(off); } catch (Throwable e) { break; }
            HFloorItem it = s.furniFromId(v);
            if (it != null && Wired.estWired(classe(gp, it.getTypeId()))) { choisir(gp, it.getTile()); return; }
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
                if (it != null && Wired.estWired(classe(gp, it.getTypeId()))) { choisir(gp, it.getTile()); return; }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Coordonnees d'un clic sur le sol. Un deplacement d'avatar porte deux
     * petits entiers plausibles comme x,y : on s'en sert pour designer une case.
     */
    private static HPoint caseDuClic(HPacket p, int taille) {
        if (taille < 14) return null;
        try {
            int a = p.readInteger(6), b = p.readInteger(10);
            if (a >= 0 && a < 200 && b >= 0 && b < 200) return new HPoint(a, b);
        } catch (Throwable ignored) { }
        return null;
    }

    /** Un wired touche dans le jeu : sa case est lue hors du fil des paquets. */
    private void choisir(GPresets gp, HPoint c) {
        if (c == null) return;
        caseValeur = new HPoint(c.getX(), c.getY());
        Salle.tache("wired-case", () -> lireCase(gp, c));
    }

    /**
     * Clic sur une case du sol, fenetre Wired ouverte : ses wired sont mis en
     * valeur (eux seuls), et sa pile est lue et choisie dans le tableau. Une
     * case sans wired efface la mise en valeur.
     */
    private void clicCase(HPoint c) {
        if (wiredSur(c).isEmpty()) {
            caseValeur = null;
            idsChoisis = List.of();
            Platform.runLater(() -> table.getSelectionModel().clearSelection());
            return;
        }
        caseValeur = c;
        GPresets gp = AtelierLauncher.moteur();
        if (gp != null) lireCase(gp, c);
        // apres la mise a jour du tableau par lireCase (meme file JavaFX)
        Platform.runLater(() -> {
            HPoint cc = caseChoisie;
            if (cc != null && cc.getX() == c.getX() && cc.getY() == c.getY())
                table.getSelectionModel().selectAll();
        });
    }

    /** Les wired poses sur une case. */
    private static List<HFloorItem> wiredSur(HPoint c) {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : WiredLecteur.wiredDeLaSalle())
            if (it.getTile().getX() == c.getX() && it.getTile().getY() == c.getY()) r.add(it);
        return r;
    }

    /**
     * Ce que la fenetre Wired met en valeur (MiseEnValeur ne le demande que
     * quand elle est ouverte) : les wired de la case cliquee, restreints aux
     * lignes choisies du tableau s'il y en a sur cette case ; sans case, les
     * lignes choisies.
     */
    private Collection<String> jetonsMisEnValeur() {
        HPoint cv = caseValeur;
        List<Integer> choisis = idsChoisis;
        List<String> r = new ArrayList<>();
        if (cv == null) {
            for (int id : choisis) r.add("s" + id);
            return r;
        }
        List<HFloorItem> surCase = wiredSur(cv);
        boolean restreindre = false;
        for (HFloorItem it : surCase) if (choisis.contains(it.getId())) { restreindre = true; break; }
        for (HFloorItem it : surCase)
            if (!restreindre || choisis.contains(it.getId())) r.add("s" + it.getId());
        return r;
    }

    /** Lit toute la pile d'une case et la classe. */
    private void lireCase(GPresets gp, HPoint c) {
        if (c == null) return;
        caseChoisie = c;
        List<LigneWired> lue = new ArrayList<>();

        FloorState s = gp.getFloorState();
        List<HFloorItem> dessus;
        try { dessus = s.getFurniOnTile(c.getX(), c.getY()); }
        catch (Throwable t) { dire("Lecture de la case impossible : " + t); return; }
        if (dessus == null) dessus = Collections.emptyList();

        int nonWired = 0;
        for (HFloorItem it : dessus) {
            String cls = classe(gp, it.getTypeId());
            if (!Wired.estWired(cls)) { nonWired++; continue; }
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
                    + (autres > 0 ? "   ·   " + autres + " autre(s) mobi" : ""));
            // Meme pile (relecture automatique sans changement) : on ne touche a rien.
            boolean pareil = table.getItems() != null && table.getItems().size() == pile.size();
            for (int i = 0; pareil && i < pile.size(); i++)
                pareil = table.getItems().get(i).id == pile.get(i).id
                        && table.getItems().get(i).getAltitude().equals(pile.get(i).getAltitude());
            if (!pareil) table.setItems(FXCollections.observableArrayList(pile));
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
    /** Attente maximale de l'arrivee d'un wired a sa hauteur, apres l'envoi. */
    private static final long ARRIVEE_MS = 1500;

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

    /**
     * Calcul pur de la remise en ordre (sans le jeu) : ordre voulu, altitudes
     * visees, wired mal places. Teste hors du jeu.
     */
    static final class Rangement {
        private Rangement() { }

        /** Ordre voulu du bas vers le haut : par rang, et a rang egal dans l'ordre actuel. */
        static List<Place> ordonner(Collection<Place> pile) {
            List<Place> r = new ArrayList<>(pile);
            r.sort(Comparator.comparingDouble((Place p) -> p.z));           // ordre actuel
            r.sort(Comparator.comparingInt((Place p) -> p.rang));           // tri stable : egalites gardees
            return r;
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

        /**
         * Altitudes de relais, toutes au-dessus de la pile actuelle ET de la
         * pile voulue, chacune a sa place relative (pas de chevauchement).
         */
        static double[] relais(List<Place> ordre, java.util.function.IntToDoubleFunction z) {
            double haut = 0;
            for (Place p : ordre) {
                double zz = z.applyAsDouble(p.id);
                haut = Math.max(haut, Math.max(zz, p.voulu) + Math.max(p.h, 0));
            }
            long base = Math.round(Math.ceil(haut + 1) * 100);
            double[] r = new double[ordre.size()];
            for (int i = 0; i < r.length; i++)
                r[i] = (base + Math.round((ordre.get(i).voulu - ordre.get(0).voulu) * 100)) / 100.0;
            return r;
        }
    }

    /**
     * Remet la pile en ordre en ecrivant @altitude sur chaque wired.
     *
     * @altitude est une variable de type Mobi, inscriptible — l'editeur :wired
     * le montre. On l'ecrit donc directement, sans deplacer ni reposer aucun
     * mobi, ni poser de dalle.
     *
     * Le premier envoi passe par OutilMiroir.Altitude.mettre, qui verifie la
     * variable retenue (preferences) et sinon la relit ou la cherche : une
     * variable fausse ne bouge rien. Ensuite les wired sont regles du bas vers
     * le haut, UN par UN : chacun est attendu a sa hauteur avant le suivant.
     * Si un wired deja place bouge avec un autre (empilement), ou s'il en
     * reste de travers, la passe suivante passe par un relais : tous montent
     * au-dessus de la pile, puis redescendent du bas vers le haut.
     */
    private void remettreEnOrdre() {
        GPresets gp = AtelierLauncher.moteur();
        HPoint c = caseChoisie;
        if (gp == null || c == null) { dire("Aucune case choisie."); return; }

        // Lue fraiche dans la salle, pas dans le tableau (qui peut etre perime).
        List<Place> pile = lirePile(gp, c);
        if (pile.size() < 2) {
            dire("Il faut au moins deux wired sur la case pour les réordonner.");
            return;
        }
        List<Place> ordre = Rangement.ordonner(pile);
        double depart = Rangement.depart(pile, Salle.hauteurSol(c.getX(), c.getY()));
        Rangement.viser(ordre, depart);
        java.util.function.IntToDoubleFunction zDe = id -> altitudeDe(gp, id);
        StringBuilder plan = new StringBuilder();
        for (Place p : ordre)
            plan.append(String.format(Locale.ROOT, " [%d %s rang=%d z=%.2f h=%.2f -> %.2f]",
                    p.id, p.nom, p.rang, p.z, p.h, p.voulu));
        Journal.debug("rangement wired (" + c.getX() + "," + c.getY() + ") depart=" + depart + plan);

        List<Place> faux = Rangement.malPlaces(ordre, zDe);
        if (faux.isEmpty()) {
            dire("");
            Journal.succes("Pile de wired déjà dans le bon ordre.");
            return;
        }

        // @altitude verifiee sur le premier wired a bouger (le plus bas de travers),
        // de preference un qui bouge assez pour que la verification le voie
        Place essai = faux.get(0);
        for (Place p : faux) {
            double z = altitudeDe(gp, p.id);
            if (z >= 0 && Math.abs(z - p.voulu) > 0.05) { essai = p; break; }
        }
        if (!assurerAltitude(gp, essai)) {
            dire("");
            Journal.erreur("@altitude introuvable : règle-la une fois dans l'éditeur :wired, puis recommence.");
            return;
        }
        idVariable = OutilMiroir.Altitude.variable();
        String var = idVariable;
        Platform.runLater(() -> {
            altLbl.setText("@altitude vérifiée — variable « " + var + " »");
            altLbl.getStyleClass().setAll("label", "etat-ok");
        });

        int passe = 0;
        boolean emporte = false;
        while (passe < 3) {
            passe++;
            if (passe == 2) {
                dire("Passe 2 : les wired passent par le haut de la pile...");
                passeParRelais(gp, ordre);
            } else {
                dire("Passe " + passe + " : réglage de " + ordre.size() + " wired...");
                emporte |= passeDirecte(gp, ordre);
            }
            sommeil(300);
            faux = Rangement.malPlaces(ordre, zDe);
            Journal.debug("rangement : passe " + passe + ", " + "mal placés : " + faux.size()
                    + (emporte ? ", empilement vu" : ""));
            if (faux.isEmpty()) break;
            dire(Ui.accorder("Passe " + passe + " : " + faux.size() + " wired encore de travers, nouvelle passe..."));
        }

        dire("");
        if (faux.isEmpty()) {
            Journal.succes(Ui.accorder("Pile de wired rangée et vérifiée en " + passe + " passe(s)."));
            return;
        }
        StringBuilder d = new StringBuilder();
        for (Place p : faux) {
            double z = altitudeDe(gp, p.id);
            if (d.length() > 0) d.append(" ; ");
            d.append("« ").append(p.nom).append(" » (").append(c.getX()).append(',').append(c.getY()).append(") ");
            d.append(z < 0 ? "a disparu de la case"
                    : "à " + hauteurTexte(z) + " au lieu de " + hauteurTexte(p.voulu));
        }
        int n = faux.size();
        Journal.erreur(n + " wired " + (n == 1 ? "reste mal placé" : "restent mal placés")
                + Ui.accorder(" après " + passe + " passe(s) : ") + d + ".");
    }

    /** Les wired de la case, lus maintenant dans la salle. */
    private static List<Place> lirePile(GPresets gp, HPoint c) {
        List<Place> r = new ArrayList<>();
        List<HFloorItem> l;
        try { l = gp.getFloorState().getFurniOnTile(c.getX(), c.getY()); }
        catch (Throwable t) { return r; }
        if (l == null) return r;
        for (HFloorItem it : l) {
            String cls = classe(gp, it.getTypeId());
            if (!Wired.estWired(cls)) continue;
            r.add(new Place(it.getId(), Wired.rang(cls).ordre, it.getTile().getZ(),
                    hauteurDe(gp, it.getId()), nomLisible(gp, cls)));
        }
        return r;
    }

    /**
     * La variable @altitude est-elle sure ? Sinon le premier wired a bouger
     * passe par OutilMiroir.Altitude.mettre (verification de la variable
     * retenue, relecture de la liste du jeu, recherche), et en dernier recours
     * par l'essai local sur ce wired.
     */
    private boolean assurerAltitude(GPresets gp, Place p) {
        if (OutilMiroir.Altitude.confirmee()) return true;
        dire("Vérification de @altitude...");
        Journal.debug("rangement : @altitude " + OutilMiroir.Altitude.variable()
                + " pas encore vérifiée, essai sur le wired " + p.id + " -> " + p.voulu);
        envoyer(p.id, p.voulu);
        if (OutilMiroir.Altitude.confirmee()) return true;
        if (OutilMiroir.Altitude.connue() && attendreArrivee(gp, p.id, p.voulu)) return true;
        return trouverAltitude(gp, p.id, p.voulu) && OutilMiroir.Altitude.connue();
    }

    /** Envoie l'altitude d'un wired a son tour dans le rythme commun (au moins Salle.ECART_MS entre deux paquets). */
    private static void envoyer(int id, double z) {
        Salle.espacer();
        try {
            if (OutilMiroir.Altitude.confirmee()) OutilMiroir.Altitude.ecrire(id, z);
            else OutilMiroir.Altitude.mettre(id, z);
        } finally { Salle.envoiFait(); }
    }

    /** Attend qu'un wired soit a l'altitude voulue (suivi des arrivees, comme PoseDirecte). */
    private static boolean attendreArrivee(GPresets gp, int id, double voulu) {
        long fin = System.currentTimeMillis() + ARRIVEE_MS;
        while (true) {
            if (Rangement.enPlace(altitudeDe(gp, id), voulu)) return true;
            if (System.currentTimeMillis() > fin) return false;
            sommeil(50);
        }
    }

    /**
     * Du bas vers le haut, un wired a la fois, chacun attendu a sa hauteur.
     * Rend true si un wired deja place a bouge avec un autre (empilement).
     */
    private boolean passeDirecte(GPresets gp, List<Place> ordre) {
        boolean emporte = false;
        for (int k = 0; k < ordre.size(); k++) {
            Place p = ordre.get(k);
            double z = altitudeDe(gp, p.id);
            if (z < 0) { Journal.debug("rangement : wired " + p.id + " plus sur la case."); continue; }
            if (Rangement.enPlace(z, p.voulu)) continue;
            double[] avant = new double[k];
            for (int i = 0; i < k; i++) avant[i] = altitudeDe(gp, ordre.get(i).id);
            envoyer(p.id, p.voulu);
            boolean arrive = attendreArrivee(gp, p.id, p.voulu);
            Journal.debug("rangement : " + p.id + " " + hauteurTexte(z) + " -> " + hauteurTexte(p.voulu)
                    + (arrive ? " arrivé" : " PAS arrivé (lu " + hauteurTexte(altitudeDe(gp, p.id)) + ")"));
            for (int i = 0; i < k; i++) {
                double apres = altitudeDe(gp, ordre.get(i).id);
                if (avant[i] >= 0 && Math.abs(apres - avant[i]) > TOLERANCE) {
                    emporte = true;
                    Journal.debug("rangement : empilement, " + ordre.get(i).id + " a bougé avec "
                            + p.id + " (" + hauteurTexte(avant[i]) + " -> " + hauteurTexte(apres) + ")");
                }
            }
        }
        return emporte;
    }

    /**
     * Passe sure meme si un wired emporte ceux qui sont dessus : tous montent
     * d'abord au-dessus de la pile, puis redescendent a leur place du bas vers
     * le haut. Un wired qui descend n'a alors au-dessus de lui que des wired
     * pas encore places : ceux deja places, plus bas, ne bougent pas.
     */
    private void passeParRelais(GPresets gp, List<Place> ordre) {
        double[] relais = Rangement.relais(ordre, id -> altitudeDe(gp, id));
        for (int k = ordre.size() - 1; k >= 0; k--) {
            Place p = ordre.get(k);
            if (altitudeDe(gp, p.id) < 0) continue;
            envoyer(p.id, relais[k]);
            if (!attendreArrivee(gp, p.id, relais[k]))
                Journal.debug("rangement : relais, " + p.id + " pas arrivé à " + hauteurTexte(relais[k]));
        }
        for (Place p : ordre) {
            if (altitudeDe(gp, p.id) < 0) continue;
            envoyer(p.id, p.voulu);
            if (!attendreArrivee(gp, p.id, p.voulu))
                Journal.debug("rangement : descente, " + p.id + " pas arrivé à " + hauteurTexte(p.voulu)
                        + " (lu " + hauteurTexte(altitudeDe(gp, p.id)) + ")");
        }
    }

    private static String hauteurTexte(double z) {
        return String.format(Locale.FRANCE, "%.2f", z);
    }

    /**
     * Capture l'identifiant de la variable @altitude depuis l'editeur :wired.
     *
     * On reconnait le paquet a sa forme exacte : (int 0, int idMobi, String "-nnn",
     * int valeur), ou idMobi est un WIRED de la salle. Ce n'est retenu que si
     * l'altitude de ce wired devient bien valeur / 100 juste apres : une autre
     * variable reglee dans l'editeur n'est pas prise pour @altitude.
     */
    private void apprendreAltitude(GPresets gp, HPacket paquet, int taille, FloorState s) {
        if (idVariable != null || taille < 18 || taille > 40) return;
        try {
            HPacket p = new HPacket(paquet);
            p.resetReadIndex();
            if (p.readInteger() != 0) return;
            int idMobi = p.readInteger();
            String var = p.readString();
            int valeur = p.readInteger();
            if (p.getReadIndex() != p.getBytesLength()) return;
            if (var == null || !var.matches("-?\\d{1,6}")) return;

            HFloorItem it = s.furniFromId(idMobi);
            if (it == null || !Wired.estWired(classe(gp, it.getTypeId()))) return;
            double avant = it.getTile().getZ(), voulu = valeur / 100.0;
            if (Math.abs(voulu - avant) < 0.01) return;            // rien a observer
            // verification hors du fil des paquets
            Salle.tache("wired-altitude", () -> {
                for (int i = 0; i < 12; i++) {
                    sommeil(100);
                    double z = altitudeDe(gp, idMobi);
                    if (z >= 0 && Math.abs(z - voulu) < 0.05) {
                        if (idVariable != null) return;
                        idVariable = var;
                        facteurAlt = 100;              // @altitude est toujours en centiemes
                        OutilMiroir.Altitude.apprendre(var, facteurAlt);
                        Platform.runLater(() -> {
                            altLbl.setText("@altitude apprise — variable « " + var + " »");
                            altLbl.getStyleClass().setAll("label", "etat-ok");
                        });
                        Journal.debug("@altitude apprise (verifiee) : mobi=" + idMobi
                                + " variable=" + var + " valeur=" + valeur);
                        return;
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    private volatile String idVariable;

    /**
     * Trouve l'identifiant de @altitude tout seul, sans rien demander (dernier
     * recours, apres OutilMiroir.Altitude.mettre).
     *
     * Les variables internes portent des identifiants negatifs que rien
     * n'expose : ni la furnidata, ni les parseurs du moteur de l'Atelier. On les essaie
     * donc un par un sur UN wired a remettre, avec SA hauteur voulue : celui qui
     * l'y amene est le bon — c'est une mesure, pas une supposition, et l'essai
     * reussi est deja la correction.
     */
    private boolean trouverAltitude(GPresets gp, int cible, double voulu) {
        double avant = altitudeDe(gp, cible);
        if (avant < 0) return false;
        int salle = Groupes.salleCourante();
        // Les variables de la liste du jeu (autres que @altitude) ne sont pas
        // essayees : leur valeur serait ecrasee par l'essai.
        Map<String, String> connues = WiredLecteur.variables();
        Journal.debug("rangement : essai des variables -100 à -140 sur le wired " + cible
                + " (" + hauteurTexte(avant) + " -> " + hauteurTexte(voulu) + ")");

        for (int v = -100; v >= -140; v--) {
            if (!Salle.dansUneSalle() || Groupes.salleCourante() != salle) return false;   // salle changee
            String candidat = String.valueOf(v);
            if (connues != null && connues.containsKey(candidat)) continue;
            Salle.envoyerEspace(new HPacket("WiredSetObjectVariableValue",
                    HMessage.Direction.TOSERVER, 0, cible, candidat,
                    (int) Math.round(Math.max(0, voulu) * 100)));
            long fin = System.currentTimeMillis() + 400;
            boolean bouge = false;
            while (!bouge && System.currentTimeMillis() < fin) {
                sommeil(50);
                bouge = Rangement.enPlace(altitudeDe(gp, cible), voulu);
            }
            if (bouge) {
                idVariable = candidat;
                facteurAlt = 100;
                OutilMiroir.Altitude.apprendre(candidat, 100);
                Platform.runLater(() -> {
                    altLbl.setText("@altitude trouvée — variable « " + candidat + " »");
                    altLbl.getStyleClass().setAll("label", "etat-ok");
                });
                Journal.debug("@altitude = variable " + candidat);
                return true;
            }
        }
        return false;
    }

    /** Altitude actuelle d'un mobi, ou -1 si inconnue. */
    private static double altitudeDe(GPresets gp, int id) {
        try {
            HFloorItem it = gp.getFloorState().furniFromId(id);
            if (it != null) return it.getTile().getZ();
        } catch (Throwable ignored) { }
        return -1;
    }
    private volatile int facteurAlt = 100;

    /** Hauteur occupee par un mobi, lue sur lui-meme. */
    private static double hauteurDe(GPresets gp, int id) {
        try {
            HFloorItem it = gp.getFloorState().furniFromId(id);
            if (it != null) {
                java.lang.reflect.Field f = HFloorItem.class.getDeclaredField("sizeZ");
                f.setAccessible(true);
                Object v = f.get(it);
                if (v != null) {
                    double h = Double.parseDouble(String.valueOf(v));
                    if (h > 0) return h;
                }
            }
        } catch (Throwable ignored) { }
        return 0.30;     // hauteur habituelle d'un wired, a defaut
    }

    /** SetCustomStackingHeight(mobi, hauteur en centiemes). */
    private static void regler(GPresets gp, int mobi, double hauteur) {
        int h = (int) Math.round(Math.max(0, hauteur) * 100);
        gp.sendToServer(new HPacket("SetCustomStackingHeight",
                HMessage.Direction.TOSERVER, mobi, h));
    }

    // ---------------------------------------------------------------- outils

    private static String classe(GPresets gp, int typeId) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) return fd.getFloorItemName(typeId);
        } catch (Throwable ignored) { }
        return null;
    }

    private static String nomLisible(GPresets gp, String cls) {
        if (cls == null) return "(inconnu)";
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                furnidata.details.FloorItemDetails d = fd.getFloorItemDetails(cls);
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

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
    /** Id du wired choisi dans le tableau : lu hors fil JavaFX par MiseEnValeur. */
    private volatile int idChoisi = 0;
    /** Variable qui n'a pas range la pile : pas reprise telle quelle d'OutilMiroir. */
    private volatile String variableRatee = null;

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
        table.getSelectionModel().selectedItemProperty().addListener((o, av, ap) -> idChoisi = ap == null ? 0 : ap.id);
        MiseEnValeur.fournir("wired", () -> {
            int id = idChoisi;
            return id == 0 ? java.util.List.<String>of() : java.util.List.of("s" + id);
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
        table.setPrefHeight(260);
        table.setMinHeight(180);
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

        HPacket p = new HPacket(m.getPacket());
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
        Salle.tache("wired-case", () -> lireCase(gp, c));
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

    /**
     * Retire les wired de la case, puis les repose du bas vers le haut.
     *
     * Chacun se pose sur le precedent : la pile se reconstruit sans trou, sans
     * avoir a calculer d'altitude. Il faut une case libre voisine comme relais.
     */
    /**
     * Remet la pile en ordre en ecrivant @altitude sur chaque wired.
     *
     * @altitude est une variable de type Mobi, inscriptible — l'editeur :wired
     * le montre. On l'ecrit donc directement, sans deplacer ni reposer aucun
     * mobi, ni poser de dalle.
     */
    private void remettreEnOrdre() {
        GPresets gp = AtelierLauncher.moteur();
        HPoint c = caseChoisie;
        if (gp == null || c == null) { dire("Aucune case choisie."); return; }
        if (idVariable == null && OutilMiroir.Altitude.connue()
                && !Objects.equals(OutilMiroir.Altitude.variable(), variableRatee)) {
            idVariable = OutilMiroir.Altitude.variable();
            facteurAlt = 100;
        }
        if (idVariable == null) {
            dire("Recherche de @altitude...");
            // d'abord la liste des variables du jeu (sure) ; tatonner en dernier
            if (OutilMiroir.Altitude.demanderListe() && OutilMiroir.Altitude.variable() != null) {
                idVariable = OutilMiroir.Altitude.variable();
                facteurAlt = 100;
            } else if (!trouverAltitude(gp, c)) {
                dire("");
                Journal.erreur("@altitude introuvable : règle-la une fois dans l'éditeur :wired, puis recommence.");
                return;
            }
        }

        List<LigneWired> ordre = new ArrayList<>(piles);
        ordre.sort(Comparator.comparingInt(LigneWired::getRang));
        // la pile repart de la ou elle est (sol sureleve, posee sur un mobi...), pas de 0
        double base = Double.MAX_VALUE;
        for (LigneWired l : ordre) { double z = altitudeDe(gp, l.id); if (z >= 0) base = Math.min(base, z); }
        if (base == Double.MAX_VALUE) base = Math.max(0, Salle.hauteurSol(c.getX(), c.getY()));
        final double depart = base;

        // Jusqu'a trois passes : la premiere laisse parfois un wired de travers,
        // le temps que le serveur applique les altitudes precedentes.
        int passe = 0, restants = -1;
        while (passe < 3) {
            passe++;
            dire("Passe " + passe + " : réglage de " + ordre.size() + " wired...");
            double cumul = depart;
            for (LigneWired l : ordre) {
                ecrireAltitude(gp, l.id, cumul);
                cumul += hauteurDe(gp, l.id);
                sommeil(200);
            }
            sommeil(900);
            restants = malPlaces(gp, c, ordre, depart);
            if (restants == 0) break;
            dire("Passe " + passe + " : " + restants + " wired encore de travers, "
                    + "nouvelle passe...");
        }

        final int r = restants;
        dire("");
        if (r == 0) {
            OutilMiroir.Altitude.apprendre(idVariable, 100);      // verifiee : partagee avec le Miroir
            Journal.succes("Pile de wired rangée et vérifiée en " + passe + " passe(s).");
        } else {
            // la variable n'a pas range la pile : on l'oublie (on cherchera a nouveau)
            variableRatee = idVariable;
            idVariable = null;
            Platform.runLater(() -> {
                altLbl.setText("@altitude sera trouvée automatiquement");
                altLbl.getStyleClass().setAll("label");
            });
            Journal.erreur(r + " wired restent mal placés après " + passe + " passes.");
        }
    }

    /**
     * Ecrit @altitude sur un mobi.
     *
     * WiredSetObjectVariableValue(int 0, int idMobi, String idVariable, int valeur).
     * L'identifiant de la variable n'est pas devinable : il est capture en
     * observant un reglage fait dans l'editeur :wired.
     */
    private void ecrireAltitude(GPresets gp, int idMobi, double hauteur) {
        int v = (int) Math.round(Math.max(0, hauteur) * facteurAlt);
        gp.sendToServer(new HPacket("WiredSetObjectVariableValue",
                HMessage.Direction.TOSERVER, 0, idMobi, idVariable, v));
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
     * Trouve l'identifiant de @altitude tout seul, sans rien demander.
     *
     * Les variables internes portent des identifiants negatifs que rien
     * n'expose : ni la furnidata, ni les parseurs du moteur de l'Atelier. On les essaie
     * donc un par un sur UN wired, en verifiant a chaque fois si son altitude a
     * bouge. Celui qui la fait bouger est le bon — c'est une mesure, pas une
     * supposition, et la valeur d'origine est remise ensuite.
     */
    private boolean trouverAltitude(GPresets gp, HPoint c) {
        List<LigneWired> pile = piles;
        if (pile.isEmpty()) return false;
        int cible = pile.get(pile.size() - 1).id;      // le plus haut de la pile

        double avant = altitudeDe(gp, cible);
        if (avant < 0) return false;
        double essai = avant + 1.0;                       // un ecart bien visible
        int salle = Groupes.salleCourante();
        // Les variables de la liste du jeu (autres que @altitude) ne sont pas
        // essayees : leur valeur serait ecrasee par l'essai.
        Map<String, String> connues = WiredLecteur.variables();

        for (int v = -100; v >= -140; v--) {
            if (!Salle.dansUneSalle() || Groupes.salleCourante() != salle) return false;   // salle changee
            String candidat = String.valueOf(v);
            if (connues != null && connues.containsKey(candidat)) continue;
            gp.sendToServer(new HPacket("WiredSetObjectVariableValue",
                    HMessage.Direction.TOSERVER, 0, cible, candidat,
                    (int) Math.round(essai * 100)));
            sommeil(260);

            double apres = altitudeDe(gp, cible);
            if (apres >= 0 && Math.abs(apres - avant) > 0.05) {
                idVariable = candidat;
                facteurAlt = 100;
                OutilMiroir.Altitude.apprendre(candidat, 100);
                // remettre le mobi comme il etait
                gp.sendToServer(new HPacket("WiredSetObjectVariableValue",
                        HMessage.Direction.TOSERVER, 0, cible, candidat,
                        (int) Math.round(avant * 100)));
                sommeil(200);
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

    /**
     * Combien de wired ne sont pas a l'altitude voulue.
     *
     * On relit la salle : sans cette verification, une premiere passe
     * incomplete passait inapercue.
     */
    private int malPlaces(GPresets gp, HPoint c, List<LigneWired> ordre, double depart) {
        int faux = 0;
        double cumul = depart;
        for (LigneWired l : ordre) {
            double reelle = altitudeDe(gp, l.id);
            if (reelle < 0 || Math.abs(reelle - cumul) > 0.06) faux++;
            cumul += hauteurDe(gp, l.id);
        }
        return faux;
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

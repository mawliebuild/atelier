package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HProductType;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.*;
import java.util.function.Consumer;

/**
 * « Vendre au troc » : ordre « atelier:troc=<id> » envoye par le bouton de
 * l'inventaire du jeu modifie (OngletInventaire.ordreDuJeu).
 *
 * Une petite fenetre (demandee) : image et nom du mobi, nombre possede,
 * Quantite (boutons 1, 10, Tout), Prix a l'unite (le dernier prix donne a ce
 * mobi, sinon le prix par defaut de Config troc), Total, Vendre / Annuler
 * (Entree / Echap).
 *
 * Sans config troc dans la salle (les deux coffres), la MEME fenetre explique
 * qu'il faut d'abord la poser, avec « Poser la config troc » : aperçu court,
 * clic dans le jeu sur la case de depart (empreinte noire), barre de
 * progression, ligne d'etat et Arreter (WiredCollage.Panneau). Quand la pose
 * est finie, la config est cherchee (coffres dans la salle, variable « prix »
 * dans la liste redemandee au jeu) ; vue, la fenetre se ferme et un message
 * dans le jeu dit de cliquer « Vendre au troc » sur un mobi.
 *
 * Avec la config, apres Vendre, sur un fil de travail :
 *   1. la config troc doit etre dans la salle (sinon : la coller) ;
 *   2. la VITRINE : celle de l'Atelier deja posee pour ce type, sinon une
 *      nouvelle, posee sur la case cliquee dans le jeu, depuis le Builders
 *      Club si le mobi y est, sinon un exemplaire de l'inventaire ;
 *   3. son prix : variable de mobi « prix » (WiredSetObjectVariableValue) ;
 *   4. les N exemplaires vont dans le coffre a mobis (TrocCoffres.deposer).
 * Bilan une fois, dans le jeu.
 */
final class TrocVente {

    private TrocVente() { }

    /**
     * L'id envoye par le jeu : le premier entier du mobi dans FurniList
     * (getPlacementId), ou getId. Il peut etre NEGATIF (id d'inventaire du
     * jeu, ex. « troc=-6950797 ») : compare tel quel d'abord, puis au signe
     * pres (un client qui enverrait l'oppose).
     */
    static HInventoryItem trouver(List<HInventoryItem> inv, int id) {
        if (inv == null) return null;
        for (HInventoryItem it : inv) if (it != null && it.getPlacementId() == id) return it;
        for (HInventoryItem it : inv) if (it != null && it.getId() == id) return it;
        if (id == 0 || id == Integer.MIN_VALUE) return null;
        for (HInventoryItem it : inv) if (it != null && it.getPlacementId() == -id) return it;
        for (HInventoryItem it : inv) if (it != null && it.getId() == -id) return it;
        return null;
    }

    // ================================================================ choix de la vitrine (logique pure)

    /** Un exemplaire possede : id d'inventaire (getPlacementId), id de pose (getId), echangeable. */
    record Exemplaire(int inventaire, int pose, boolean echangeable) { }

    /** Ce qui sera fait : la vitrine prise dans l'inventaire (ou null), les exemplaires a deposer, le maximum. */
    record Choix(Exemplaire vitrine, List<Exemplaire> aDeposer, int max) { }

    /**
     * @param bc            la vitrine peut venir du Builders Club
     * @param vitrineEnPlace une vitrine de l'Atelier pour ce type est deja dans la salle
     * @param voulu         quantite demandee (bornee au maximum)
     */
    static Choix choisir(List<Exemplaire> possedes, boolean bc, boolean vitrineEnPlace, int voulu) {
        List<Exemplaire> echangeables = new ArrayList<>(), autres = new ArrayList<>();
        for (Exemplaire e : possedes) (e.echangeable() ? echangeables : autres).add(e);
        echangeables.sort(Comparator.comparingInt(Exemplaire::inventaire));
        Exemplaire vitrine = null;
        if (!bc && !vitrineEnPlace) {
            // de preference un exemplaire qui ne pourrait pas etre depose de toute facon
            if (!autres.isEmpty()) vitrine = autres.get(0);
            else if (!echangeables.isEmpty()) vitrine = echangeables.remove(echangeables.size() - 1);
        }
        int max = echangeables.size();
        int n = Math.max(0, Math.min(voulu, max));
        return new Choix(vitrine, List.copyOf(echangeables.subList(0, n)), max);
    }

    /** « Trône » -> « Trônes » (premier mot) au-dela d'un exemplaire. Logique pure. */
    static String pluriel(String nom, int n) {
        if (nom == null || nom.isBlank() || n < 2) return nom;
        int fin = nom.indexOf(' ');
        String mot = fin < 0 ? nom : nom.substring(0, fin);
        char d = Character.toLowerCase(mot.charAt(mot.length() - 1));
        if (d == 's' || d == 'x' || d == 'z' || !Character.isLetter(d)) return nom;
        return mot + "s" + (fin < 0 ? "" : nom.substring(fin));
    }

    /** Le bilan en jeu : « 12 Trônes mis en vente à 5 crédits l'unité. » Logique pure. */
    static String bilan(String nom, int n, int prix) {
        return n + " " + pluriel(nom, n) + " mis en vente à " + prix + (prix > 1 ? " crédits" : " crédit") + " l'unité.";
    }

    // ================================================================ fenetre (fil JavaFX)

    private static CalqueFenetre ouverte;
    /** La fenetre de pose de la config, tant qu'elle est ouverte. */
    private static CalqueFenetre pose;
    private static volatile boolean venteEnCours = false;

    static final String CONFIG_POSEE = "La config troc est posée. Clique « Vendre au troc » sur un mobi pour le mettre en vente.";

    /** Ouvre la fenetre de vente pour le mobi d'inventaire « id ». Fil JavaFX. */
    static void ouvrir(int id, List<HInventoryItem> inventaire) {
        TrocCoffres.installer();
        if (venteEnCours) { Journal.erreur("Une vente en troc est déjà en cours."); return; }
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.furnidataPrete()) { Journal.erreur("L'Atelier n'est pas encore prêt."); return; }
        if (!Salle.dansUneSalle()) { Journal.erreur("Entre d'abord dans la salle de ton troc."); return; }
        // pose de la config en cours : sa fenetre revient devant
        if (pose != null && pose.ouverte() && WiredCollage.occupe()) { pose.montrer(); return; }
        // pas de config (ou ramassee : coffres seuls, sans les wired de vente) : la fenetre de pose
        if (!TrocCoffres.dansLaSalle().complets() || !ConfigTroc.wiredEnPlace()) {
            if (ouverte != null) ouverte.fermer();
            fenetrePose(inventaire);
            return;
        }
        HInventoryItem it = trouver(inventaire, id);
        if (it == null) {
            Journal.erreur(inventaire == null || inventaire.isEmpty()
                    ? "L'Atelier ne connaît pas encore ton inventaire : ferme et rouvre l'inventaire du jeu, puis réessaie."
                    : "Ce mobi (id " + id + ") n'est pas dans l'inventaire connu de l'Atelier : ferme et rouvre "
                      + "l'inventaire du jeu, puis réessaie.");
            return;
        }
        if (it.getType() != HProductType.FloorItem) {
            Journal.erreur("Vente en troc : seulement les mobis de sol pour l'instant (la vitrine se pose au sol).");
            return;
        }
        int type = it.getTypeId();
        String classe = Salle.classe(type, false);
        if (classe == null) { Journal.erreur("Ce mobi est inconnu de la furnidata."); return; }
        String nom = Salle.nom(type, false);
        List<Exemplaire> possedes = new ArrayList<>();
        for (HInventoryItem x : inventaire)
            if (x != null && x.getType() == HProductType.FloorItem && x.getTypeId() == type)
                possedes.add(new Exemplaire(x.getPlacementId(), x.getId(), x.isTradeable()));
        OffresBc.Offre offre = PoseDirecte.offreSol(gp, classe);
        int salle = Salle.salleId();
        boolean enPlace = salle != -1 && ConfigTroc.vitrineDe(salle, type) != null;
        Choix tout = choisir(possedes, offre != null, enPlace, Integer.MAX_VALUE);
        if (ouverte != null) ouverte.fermer();
        fenetre(type, classe, nom, possedes, offre, enPlace, tout.max());
    }

    private static String css() {
        try {
            java.net.URL u = gearth.GEarth.class.getResource("/gearth/ui/themes/G-Earth/styling.css");
            return u == null ? null : u.toExternalForm();
        } catch (Throwable t) { return null; }
    }

    private static void fenetre(int type, String classe, String nom, List<Exemplaire> possedes,
                                OffresBc.Offre offre, boolean enPlace, int max) {
        CalqueFenetre f = new CalqueFenetre(css(), "Vendre au troc", null);
        ouverte = f;
        f.surFermeture(() -> { if (ouverte == f) ouverte = null; });

        // ---- image et nom
        ImageView iv = new ImageView();
        iv.setFitWidth(44);
        iv.setFitHeight(44);
        iv.setPreserveRatio(true);
        Furnidata.Mobi d = Salle.details(classe);
        if (d != null && d.revision > 0) {
            String c = classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe;
            try { iv.setImage(new Image("https://images.habbo.com/dcr/hof_furni/" + d.revision + "/" + c + "_icon.png", true)); }
            catch (Throwable ignored) { }
        }
        StackPane image = new StackPane(iv);
        image.setMinSize(48, 48);
        Label titre = new Label(nom);
        titre.setStyle("-fx-font-weight: bold;");
        titre.setWrapText(true);
        int echangeables = 0;
        for (Exemplaire e : possedes) if (e.echangeable()) echangeables++;
        int bloques = possedes.size() - echangeables;
        Label possede = new Label(Ui.accorder("Tu en possèdes " + possedes.size() + ".")
                + (bloques > 0 ? Ui.accorder(" " + bloques + " non échangeable(s).") : ""));
        possede.setWrapText(true);
        VBox noms = new VBox(3, titre, possede);
        HBox tete = new HBox(10, image, noms);
        tete.setAlignment(Pos.CENTER_LEFT);

        String origine = enPlace ? "Ta vitrine est déjà dans la salle : son prix sera mis à jour."
                : offre != null ? "La vitrine sera posée depuis le Builders Club : tout peut être vendu."
                : Ui.accorder("Un exemplaire sert de vitrine : " + max + " à vendre au plus.");
        Label note = Ui.discret(origine + (enPlace ? "" : " Après Vendre, clique dans le jeu la case de la vitrine."));
        note.setWrapText(true);

        // ---- quantite (raccourcis 1, 10, tout), prix, total
        int depart = Math.max(max, 0);
        Spinner<Integer> quantite = new Spinner<>(Math.min(1, max), depart, depart);
        quantite.setEditable(true);
        quantite.setPrefWidth(96);
        quantite.setDisable(max <= 0);
        HBox rapides = new HBox(4);
        for (int k : new int[]{1, 10}) {
            if (k >= max) continue;
            Button b = new Button(String.valueOf(k));
            b.setFocusTraversable(false);
            b.setOnAction(e -> quantite.getValueFactory().setValue(k));
            rapides.getChildren().add(b);
        }
        Button tout = new Button("Tout");
        tout.setFocusTraversable(false);
        tout.setDisable(max <= 0);
        tout.setOnAction(e -> quantite.getValueFactory().setValue(max));
        rapides.getChildren().add(tout);
        HBox ligneQuantite = new HBox(6, quantite, rapides);
        ligneQuantite.setAlignment(Pos.CENTER_LEFT);

        ConfigTroc.Vitrine enPlaceV = Salle.salleId() == -1 ? null : ConfigTroc.vitrineDe(Salle.salleId(), type);
        int prixDepart = enPlaceV != null && enPlaceV.prix() > 0 ? enPlaceV.prix() : ConfigTroc.prixPropose(type);
        TextField prix = new TextField(String.valueOf(prixDepart));
        prix.setPrefColumnCount(6);
        HBox lignePrix = new HBox(6, prix, Ui.etiquette("crédits"));
        lignePrix.setAlignment(Pos.CENTER_LEFT);
        Label total = new Label();
        total.setStyle("-fx-font-weight: bold;");
        Runnable majTotal = () -> {
            try {
                int q = Integer.parseInt(quantite.getEditor().getText().trim());
                int p = Integer.parseInt(prix.getText().trim());
                total.setText(q < 1 || p < 1 ? "—" : q + " × " + p + " = " + ((long) q * p) + (q * (long) p > 1 ? " crédits" : " crédit"));
            } catch (NumberFormatException e) { total.setText("—"); }
        };
        quantite.getEditor().textProperty().addListener((o, x, y) -> majTotal.run());
        quantite.valueProperty().addListener((o, x, y) -> majTotal.run());
        prix.textProperty().addListener((o, x, y) -> majTotal.run());
        majTotal.run();
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(6);
        g.addRow(0, Ui.etiquette("Quantité"), ligneQuantite);
        g.addRow(1, Ui.etiquette("Prix à l'unité"), lignePrix);
        g.addRow(2, Ui.etiquette("Total"), total);

        // ---- config de la salle : la variable « prix » (les coffres y sont)
        Label config = Ui.discret("");
        config.setWrapText(true);
        config.managedProperty().bind(config.visibleProperty());
        config.setVisible(false);
        int salleOuverte = Salle.salleId();
        Salle.tache("troc-prix-present", () -> {
            if (ConfigTroc.idPrix(2500) != null || Salle.salleId() != salleOuverte) return;
            javafx.application.Platform.runLater(() -> {
                if (ouverte != f || !f.ouverte()) return;
                config.setText("La variable « prix » manque dans cette salle : la config troc est incomplète.");
                config.setVisible(true);
                f.ajuster();
            });
        });

        Runnable vendre = () -> {
            int q, p;
            try { q = Integer.parseInt(quantite.getEditor().getText().trim()); }   // texte tape, pas encore valide
            catch (NumberFormatException e) { f.dire("Quantité invalide."); return; }
            try { p = Integer.parseInt(prix.getText().trim()); }
            catch (NumberFormatException e) { f.dire("Prix invalide : un nombre entier de crédits."); return; }
            if (max <= 0) { f.dire("Aucun exemplaire à vendre."); return; }
            if (q < 1 || q > max) { f.dire(Ui.accorder("Quantité entre 1 et " + max + ".")); return; }
            if (p < 1) { f.dire("Le prix doit être d'au moins 1 crédit."); return; }
            if (!TrocCoffres.dansLaSalle().complets()) { f.dire("Pas de config troc dans cette salle : rouvre « Vendre au troc » pour la poser."); return; }
            f.fermer();
            Vente v = new Vente(Salle.salleId(), type, classe, nom, p, q, possedes, offre);
            venteEnCours = true;
            Salle.tache("troc-vente", () -> {
                try { vendre(v); }
                catch (Throwable t) { Journal.erreur("Vente en troc interrompue", t); }
                finally { venteEnCours = false; }
            });
        };
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button ok = CalqueFenetre.bouton(Icones.TROC, "Vendre", null, true, vendre);
        ok.setDisable(max <= 0);
        // Entree depuis les champs aussi (le Spinner garde la touche pour lui)
        f.stage().getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !ok.isDisabled()) { e.consume(); vendre.run(); }
        });
        f.contenu(tete, note, g, config);
        f.boutons(annuler, ok);
        f.montrer();
        prix.requestFocus();
        prix.selectAll();
    }

    // ================================================================ pose de la config (fil JavaFX)

    /** Les classes des mobis de sol de l'inventaire. */
    private static Set<String> classesSol(List<HInventoryItem> inventaire) {
        Set<String> r = new HashSet<>();
        if (inventaire != null) for (HInventoryItem x : inventaire) {
            if (x == null || x.getType() != HProductType.FloorItem) continue;
            String c = Salle.classe(x.getTypeId(), false);
            if (c != null) r.add(c);
        }
        return r;
    }

    /**
     * Ce qui manque dans l'inventaire pour poser la config : les coffres (ils
     * ne sont pas au Builders Club). null si rien ne manque. Logique pure.
     */
    static String coffresManquants(Set<String> classes) {
        boolean m = false, c = false;
        for (String k : TrocCoffres.CLASSES_MOBIS) m |= classes.contains(k);
        for (String k : TrocCoffres.CLASSES_CREDITS) c |= classes.contains(k);
        if (m && c) return null;
        String quoi = !m && !c ? "un coffre à mobis et un coffre à crédits" : !m ? "un coffre à mobis" : "un coffre à crédits";
        return "Il te faut " + quoi + " dans ton inventaire : les coffres ne sont pas au Builders Club. "
                + "Après l'achat, ferme et rouvre l'inventaire du jeu.";
    }

    /**
     * La fenetre « Vendre au troc » quand la salle n'a pas la config : une
     * phrase, « Poser la config troc », puis l'aperçu, le clic dans le jeu et
     * la progression, dans la fenetre.
     */
    private static void fenetrePose(List<HInventoryItem> inventaire) {
        CalqueFenetre f = new CalqueFenetre(css(), "Vendre au troc", null);
        ouverte = f;
        pose = f;
        f.surFermeture(() -> { if (ouverte == f) ouverte = null; if (pose == f) pose = null; });

        Label explication = new Label("Pour vendre ici, la salle a besoin de la config troc (deux coffres et "
                + "les wired de vente). Pose-la d'abord, une seule fois.");
        explication.setWrapText(true);
        explication.managedProperty().bind(explication.visibleProperty());
        Set<String> classes = classesSol(inventaire);
        String manque = inventaire == null || inventaire.isEmpty() ? null : coffresManquants(classes);
        Label manqueLbl = Ui.discret(manque == null ? "" : manque);
        manqueLbl.setWrapText(true);
        manqueLbl.managedProperty().bind(manqueLbl.visibleProperty());
        manqueLbl.setVisible(manque != null);

        // les wired viennent surtout du BC, les coffres de l'inventaire
        WiredCollage.Panneau suivi = new WiredCollage.Panneau().sourceDefaut(1).court(true);
        Button annuler = CalqueFenetre.bouton("Fermer", false, f::fermer);
        Button poser = CalqueFenetre.bouton(Icones.COLLER, "Poser la config troc",
                "Aperçu ici, puis clic dans le jeu sur la case de départ de la ligne de piles.", true, null);
        poser.setDefaultButton(false);       // Entree reste a Confirmer de l'aperçu
        poser.setDisable(manque != null);
        HBox boutons = new HBox(6, annuler, poser);
        boutons.setAlignment(Pos.CENTER_RIGHT);
        boutons.managedProperty().bind(boutons.visibleProperty());

        Runnable accueil = () -> {
            explication.setVisible(true);
            boutons.setVisible(true);
            f.ajuster();
        };
        poser.setOnAction(e -> {
            f.dire("");
            explication.setVisible(false);
            manqueLbl.setVisible(false);
            boutons.setVisible(false);
            ConfigTroc.collerIci(suivi, ConfigTroc.coffresAPoser(classes));
            if (!suivi.actif()) accueil.run();                  // refus (dit dans le jeu)
            f.ajuster();
        });
        int salle = Salle.salleId();
        suivi.surAnnuler(accueil);
        suivi.surBilan((ok, bilan) -> apresPose(f, salle, ok, bilan, accueil));
        // la fenetre suit la hauteur du panneau (aperçu, progression)
        suivi.noeud().heightProperty().addListener((o, a, b) -> f.ajuster());

        f.contenu(explication, manqueLbl, boutons, suivi.noeud());
        f.boutons();
        f.montrer();

        // coffres absents de l'inventaire connu : ils viennent peut-etre d'y revenir (config
        // ramassee) ; l'Atelier relit l'inventaire et debloque le bouton s'ils y sont
        if (manque != null) Salle.tache("troc-inventaire", () -> {
            Moteur gp = Salle.gp();
            if (gp == null) return;
            ChargementAuto.inventaireDemande();
            try { gp.demanderInventaire(); } catch (Throwable ignored) { }
            for (int i = 0; i < 20 && f.ouverte(); i++) {
                Salle.sommeil(1000);
                List<HInventoryItem> inv2;
                try { inv2 = new ArrayList<>(gp.getInventory().getInventoryItems()); }
                catch (Throwable t) { continue; }
                Set<String> c2 = classesSol(inv2);
                if (coffresManquants(c2) == null) {
                    javafx.application.Platform.runLater(() -> {
                        classes.clear();
                        classes.addAll(c2);
                        manqueLbl.setVisible(false);
                        poser.setDisable(false);
                        f.ajuster();
                    });
                    return;
                }
            }
        });
    }

    /**
     * Fin de la pose (bilan du collage, dit ici une seule fois) : la config
     * est cherchee jusqu'a ce qu'elle soit vue ; vue, la fenetre se ferme et
     * le jeu dit que c'est bon. Sinon le bilan (ou ce qui manque) est dit.
     */
    private static void apresPose(CalqueFenetre f, int salle, boolean ok, String bilan, Runnable accueil) {
        boolean posee = ok || (bilan != null && bilan.contains("posé(s) en ("));
        if (!posee || (bilan != null && bilan.startsWith("Arrêté"))) {            // rien de pose (refus, pas de clic) ou arret voulu
            WiredCollage.direBilan(ok, bilan);
            javafx.application.Platform.runLater(accueil);
            return;
        }
        // quelque chose a ete pose : on laisse le temps aux coffres d'apparaitre et a la variable d'etre creee
        javafx.application.Platform.runLater(() -> f.dire("Recherche de la config troc dans la salle…"));
        Salle.tache("troc-config-posee", () -> {
            ConfigTroc.Etat e = ConfigTroc.attendreConfig(salle, 12_000, () -> false);
            if (e.complete()) {
                if (!ok) Journal.info("Config troc : " + bilan);
                Journal.succes(CONFIG_POSEE);
                javafx.application.Platform.runLater(f::fermer);
                return;
            }
            if (!ok) WiredCollage.direBilan(false, bilan);
            else Journal.erreur("La pose est finie, mais la config troc n'est pas vue dans la salle. " + e.texte());
            javafx.application.Platform.runLater(() -> {
                f.dire(e.texte());
                accueil.run();
            });
        });
    }

    // ================================================================ vente (fil de travail)

    record Vente(int salle, int type, String classe, String nom, int prix, int quantite,
                 List<Exemplaire> possedes, OffresBc.Offre offre) { }

    private static void vendre(Vente v) {
        Consumer<String> dire = InfoJeu::consigne;
        if (Salle.salleId() != v.salle()) { Journal.erreur("Tu as changé de salle : vente annulée."); return; }
        TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
        if (!c.complets()) { Journal.erreur("Pas de config troc dans cette salle : clique « Vendre au troc » sur un mobi pour la poser."); return; }
        String idPrix = ConfigTroc.idPrix(3000);
        if (idPrix == null) { Journal.erreur("Variable « prix » introuvable dans cette salle : la config troc est-elle complète ?"); return; }

        ConfigTroc.Vitrine vitrine = ConfigTroc.vitrineDe(v.salle(), v.type());
        Choix ch = choisir(v.possedes(), v.offre() != null, vitrine != null, v.quantite());
        if (ch.aDeposer().isEmpty()) { Journal.erreur("Aucun exemplaire échangeable à vendre."); return; }

        boolean nouvelle = vitrine == null;
        if (nouvelle) {
            vitrine = poserVitrine(v, ch);
            if (vitrine == null) return;                    // erreur deja dite
        }

        // le prix sur la vitrine : creer la variable (nouvelle vitrine) ou changer sa valeur
        Canal canal = Canal.duMoteur();
        HPacket p = nouvelle ? PoseOutils.variableSol(vitrine.id(), idPrix, v.prix())
                : new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER, 0, vitrine.id(), idPrix, v.prix(), 0);
        if (canal == null || !PoseOutils.envoyerVariable(canal, p)) {
            Journal.erreur("Le prix n'a pas pu être donné à la vitrine.");
            return;
        }
        ConfigTroc.prixVitrine(v.salle(), vitrine.id(), v.prix());
        ConfigTroc.dernierPrix(v.type(), v.prix());

        List<Integer> ids = new ArrayList<>();
        for (Exemplaire e : ch.aDeposer()) ids.add(e.inventaire());
        if (ids.size() > 100) dire.accept(Ui.accorder("Dépôt de " + ids.size() + " mobi(s) dans le coffre…"));
        String err = TrocCoffres.deposer(c.mobis(), ids, dire);
        if (err != null) {
            Journal.erreur(err + (nouvelle ? " La vitrine sera ramassée si le coffre n'a pas ce mobi." : ""));
            return;
        }
        Journal.succes(bilan(v.nom(), ids.size(), v.prix()));
    }

    /** Pose une nouvelle vitrine sur la case cliquee ; null (erreur dite) si elle n'apparait pas. */
    private static ConfigTroc.Vitrine poserVitrine(Vente v, Choix ch) {
        boolean bc = v.offre() != null;
        if (!bc && ch.vitrine() == null) { Journal.erreur("Pas d'exemplaire pour la vitrine."); return null; }
        InfoJeu.consigne("Clique dans le jeu la case où poser la vitrine.");
        Empreinte.Boite emp = new Empreinte.Boite().sol(v.classe(), 0, 0, 0);
        HPoint caseVitrine = Generateur.Dalle.attendreClic(120_000, emp.largeur(), emp.profondeur());
        if (caseVitrine == null) { Journal.erreur("Pas de clic dans le jeu en 2 minutes : vente annulée."); return null; }
        if (Salle.salleId() != v.salle()) { Journal.erreur("Tu as changé de salle : vente annulée."); return null; }

        PoseOutils.Signaux.installer();
        Set<Integer> avant = new HashSet<>();
        for (HFloorItem it : Salle.sols()) avant.add(it.getId());
        long t0 = System.currentTimeMillis();
        int x = caseVitrine.getX(), y = caseVitrine.getY();
        HPacket pose = bc ? PoseOutils.poseSolBc(v.offre(), x, y, 0)
                : PoseOutils.poseSolInventaire(ch.vitrine().pose(), x, y, 0);
        Salle.envoyerEspace(pose);
        int[] id = {0};
        PoseOutils.attendre(() -> {
            for (HFloorItem it : Salle.sols())
                if (!avant.contains(it.getId()) && it.getTypeId() == v.type()) { id[0] = it.getId(); return true; }
            return false;
        }, 5000, null);
        if (id[0] == 0) {
            String r = PoseOutils.Signaux.raisonPose(t0, x, y);
            Journal.erreur("La vitrine n'a pas pu être posée" + (r == null ? " (case occupée ?)" : " : " + r) + ". Vente annulée.");
            return null;
        }
        ConfigTroc.Vitrine vit = new ConfigTroc.Vitrine(id[0], v.type(), bc, v.prix());
        ConfigTroc.ajouterVitrine(v.salle(), vit);
        return vit;
    }
}

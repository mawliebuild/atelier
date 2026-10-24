package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HProductType;
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

    /**
     * Vitrine seule : pas de vitrine en place, pas de Builders Club, rien a
     * deposer, mais un exemplaire pour la vitrine. Logique pure.
     */
    static boolean vitrineSeule(int possedes, boolean bc, boolean vitrineEnPlace, int max) {
        return possedes > 0 && !bc && !vitrineEnPlace && max <= 0;
    }

    /**
     * La quantite qu'on peut demander : le maximum vendable, plus l'exemplaire
     * pris pour la vitrine parmi les echangeables (hors BC, pas de vitrine en
     * place). Logique pure.
     */
    static int quantiteMax(int echangeables, int max) { return Math.max(max, Math.min(echangeables, max + 1)); }

    /** La phrase sous la quantite quand la vitrine est prise dans le lot ; null sinon. Logique pure. */
    static String phraseVitrineDuLot(int q, int max) {
        if (max < 1 || q <= max) return null;
        return "Le dernier servira de vitrine : " + (max > 1 ? max + " seront vendus." : "1 sera vendu.");
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

    /** Une vente est en cours (vitrine posee, depot pas fini) : ne pas ramasser de vitrine. */
    static boolean venteEnCours() { return venteEnCours; }

    static final String CONFIG_POSEE = "La boutique troc est posée. Clique « Vendre au troc » sur un mobi pour le mettre en vente.";

    /** Ouvre la fenetre de vente pour le mobi d'inventaire « id ». Fil JavaFX. */
    static void ouvrir(int id, List<HInventoryItem> inventaire) {
        TrocCoffres.installer();
        if (venteEnCours) { Journal.erreur("Une vente en troc est déjà en cours."); return; }
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.furnidataPrete()) { Journal.erreur("L'Atelier n'est pas encore prêt."); return; }
        if (!Salle.dansUneSalle()) { Journal.erreur("Entre d'abord dans l'appart de ton troc."); return; }
        // pose de la config en cours : sa fenetre revient devant
        if (pose != null && pose.ouverte() && WiredCollage.occupe()) { pose.montrer(); return; }
        if (WiredCollage.occupe()) { Journal.erreur("Une pose est en cours : attends qu'elle se termine."); return; }
        TrocCoffres.Coffres cs = TrocCoffres.dansLaSalle();
        boolean wired = ConfigTroc.wiredEnPlace();
        // les wired de vente sont la, un coffre manque : l'Atelier le pose depuis l'inventaire, puis la vente
        if (wired && !cs.complets()) { completerCoffres(id, inventaire); return; }
        // pas de config (ou ramassee : coffres seuls, sans les wired de vente) : la fenetre de pose
        if (!cs.complets() || !wired) {
            ConfigTroc.diagnostic("Vendre au troc");
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
        if (classe == null) { Journal.erreur("Ce mobi est inconnu du jeu."); return; }
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

    private static volatile boolean coffresEnCours = false;

    /** Pose les coffres qui manquent (TrocPoseCoffres), puis rouvre la vente de ce mobi. Fil JavaFX. */
    private static void completerCoffres(int id, List<HInventoryItem> inventaire) {
        if (coffresEnCours) return;
        coffresEnCours = true;
        Salle.tache("troc-coffres", () -> {
            try {
                TrocPoseCoffres.Resultat r = TrocPoseCoffres.completer(inventaire);
                if (!r.ok()) { Journal.erreur(r.erreur()); return; }
                if (r.poses() > 0)
                    Journal.succes(Ui.accorder(r.poses() + " coffre(s) posé(s) depuis ton inventaire pour la boutique troc."));
                javafx.application.Platform.runLater(() -> {
                    if (TrocCoffres.dansLaSalle().complets()) ouvrir(id, inventaire);
                });
            } catch (Throwable t) {
                Journal.erreur("Pose des coffres de la boutique troc interrompue", t);
            } finally { coffresEnCours = false; }
        });
    }

    static String css() {
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

        // rien a mettre en stock (un seul exemplaire, ou aucun echangeable) : il est pose en vitrine seule, avec son prix
        boolean seule = vitrineSeule(possedes.size(), offre != null, enPlace, max);
        String origine = enPlace ? "Ta vitrine est déjà dans l'appart : son prix sera mis à jour."
                : offre != null ? "La vitrine sera posée depuis le Builders Club : tout peut être vendu."
                : seule ? (possedes.size() == 1 ? "Tu en possèdes un : il sera posé en vitrine avec son prix, rien n'est mis en stock."
                        : "Aucun n'est échangeable : un exemplaire sera posé en vitrine avec son prix, rien n'est mis en stock.")
                : "Un exemplaire sert de vitrine.";
        Label note = Ui.discret(origine + (enPlace ? "" : seule
                ? " Après « Poser la vitrine », clique dans le jeu la case de la vitrine."
                : " Après Vendre, clique dans le jeu la case de la vitrine."));
        note.setWrapText(true);

        // ---- quantite (raccourcis 1, 10, tout), prix, total
        // par defaut le MAXIMUM, vitrine comprise quand elle est prise parmi les echangeables :
        // la vente met alors en stock ce qui est vraiment vendable (une phrase le dit)
        int limite = quantiteMax(echangeables, max);
        int depart = Math.max(limite, 0);
        Spinner<Integer> quantite = new Spinner<>(Math.min(1, limite), depart, depart);
        quantite.setEditable(true);
        quantite.setPrefWidth(96);
        quantite.setDisable(limite <= 0);
        HBox rapides = new HBox(4);
        for (int k : new int[]{1, 10}) {
            if (k >= limite) continue;
            Button b = new Button(String.valueOf(k));
            b.setFocusTraversable(false);
            b.setOnAction(e -> quantite.getValueFactory().setValue(k));
            rapides.getChildren().add(b);
        }
        Button tout = new Button("Tout");
        tout.setFocusTraversable(false);
        tout.setDisable(limite <= 0);
        tout.setOnAction(e -> quantite.getValueFactory().setValue(limite));
        rapides.getChildren().add(tout);
        HBox ligneQuantite = new HBox(6, quantite, rapides);
        ligneQuantite.setAlignment(Pos.CENTER_LEFT);
        Label vitrineDuLot = Ui.discret("");
        vitrineDuLot.setWrapText(true);
        vitrineDuLot.managedProperty().bind(vitrineDuLot.visibleProperty());
        Runnable majVitrineDuLot = () -> {
            int q;
            try { q = Integer.parseInt(quantite.getEditor().getText().trim()); } catch (NumberFormatException e) { q = 0; }
            String t = phraseVitrineDuLot(q, max);
            vitrineDuLot.setText(t == null ? "" : t);
            boolean vis = t != null;
            if (vitrineDuLot.isVisible() != vis) { vitrineDuLot.setVisible(vis); f.ajuster(); }
        };
        quantite.getEditor().textProperty().addListener((o, x, y) -> majVitrineDuLot.run());
        quantite.valueProperty().addListener((o, x, y) -> majVitrineDuLot.run());
        String t0 = phraseVitrineDuLot(depart, max);
        vitrineDuLot.setText(t0 == null ? "" : t0);
        vitrineDuLot.setVisible(t0 != null);
        VBox blocQuantite = new VBox(3, ligneQuantite, vitrineDuLot);

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
                int q = Math.min(Integer.parseInt(quantite.getEditor().getText().trim()), Math.max(max, 1));   // vendables seulement
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
        if (seule) g.addRow(0, Ui.etiquette("Prix à l'unité"), lignePrix);     // pas de quantite ni de total
        else {
            g.addRow(0, Ui.etiquette("Quantité"), blocQuantite);
            g.addRow(1, Ui.etiquette("Prix à l'unité"), lignePrix);
            g.addRow(2, Ui.etiquette("Total"), total);
        }

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
                config.setText("La variable « prix » manque dans cet appart : la boutique troc est incomplète.");
                config.setVisible(true);
                f.ajuster();
            });
        });

        Runnable vendre = () -> {
            int q = 0, p;
            if (!seule) {
                try { q = Integer.parseInt(quantite.getEditor().getText().trim()); }   // texte tape, pas encore valide
                catch (NumberFormatException e) { f.dire("Quantité invalide."); return; }
            }
            try { p = Integer.parseInt(prix.getText().trim()); }
            catch (NumberFormatException e) { f.dire("Prix invalide : un nombre entier de crédits."); return; }
            if (!seule && max <= 0) { f.dire("Aucun exemplaire à vendre."); return; }
            if (!seule && (q < 1 || q > limite)) { f.dire(Ui.accorder("Quantité entre 1 et " + limite + ".")); return; }
            if (!seule) q = Math.min(q, max);            // la vitrine prise dans le lot n'est pas mise en stock
            if (p < 1) { f.dire("Le prix doit être d'au moins 1 crédit."); return; }
            if (!TrocCoffres.dansLaSalle().complets() || !ConfigTroc.wiredEnPlace()) { f.dire("Pas de boutique troc dans cet appart : rouvre « Vendre au troc » pour la poser."); return; }
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
        Button ok = CalqueFenetre.bouton(Icones.TROC, seule ? "Poser la vitrine" : "Vendre",
                seule ? "Pose ton exemplaire en vitrine, avec son prix affiché ; rien n'est mis en stock." : null, true, vendre);
        ok.setDisable(max <= 0 && !seule);
        ok.setCursor(javafx.scene.Cursor.HAND);
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
    static String coffresManquants(Set<String> classes) { return coffresManquants(classes, new TrocCoffres.Coffres(0, 0)); }

    /** Pareil, les coffres deja dans l'appart (repris par la pose) ne manquent pas. Logique pure. */
    static String coffresManquants(Set<String> classes, TrocCoffres.Coffres enSalle) {
        boolean m = enSalle.mobis() != 0, c = enSalle.credits() != 0;
        for (String k : TrocCoffres.CLASSES_MOBIS) m |= classes.contains(k);
        for (String k : TrocCoffres.CLASSES_CREDITS) c |= classes.contains(k);
        if (m && c) return null;
        String cm = "un coffre à mobis (« " + TrocPoseCoffres.nomCoffre(TrocCoffres.COFFRE_MOBIS) + " »)";
        String cc = "un coffre à crédits (« " + TrocPoseCoffres.nomCoffre(TrocCoffres.COFFRE_CREDITS) + " »)";
        String quoi = !m && !c ? cm + " et " + cc : !m ? cm : cc;
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

        Label explication = new Label("Pour vendre ici, l'appart a besoin de la boutique troc (deux coffres et "
                + "les wired de vente). Pose-la d'abord, une seule fois.");
        explication.setWrapText(true);
        explication.managedProperty().bind(explication.visibleProperty());
        Set<String> classes = classesSol(inventaire);
        TrocCoffres.Coffres enSalle = TrocCoffres.dansLaSalle();
        if (enSalle.mobis() != 0 || enSalle.credits() != 0)
            explication.setText(explication.getText() + " " + (enSalle.complets() ? "Les deux coffres déjà dans l'appart sont repris."
                    : enSalle.mobis() != 0 ? "Le coffre à mobis déjà dans l'appart est repris." : "Le coffre à crédits déjà dans l'appart est repris."));
        String manque = inventaire == null || inventaire.isEmpty() ? null : coffresManquants(classes, enSalle);
        Label manqueLbl = Ui.discret(manque == null ? "" : manque);
        manqueLbl.setWrapText(true);
        manqueLbl.managedProperty().bind(manqueLbl.visibleProperty());
        manqueLbl.setVisible(manque != null);

        // les wired viennent surtout du BC, les coffres de l'inventaire
        // source automatique, sans menu : le BC s'il a le mobi (les wired), sinon l'inventaire (les coffres)
        WiredCollage.Panneau suivi = new WiredCollage.Panneau().sourceFixe(2).court(true);
        Button annuler = CalqueFenetre.bouton("Fermer", false, f::fermer);
        Button poser = CalqueFenetre.bouton(Icones.COLLER, "Poser la boutique troc",
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
        // apres Confirmer : la consigne est dans le jeu et l'avancement dans le bandeau, la fenetre se ferme
        suivi.surLancement(f::fermer);
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
                if (coffresManquants(c2, TrocCoffres.dansLaSalle()) == null) {
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

    /** Le nombre de mobis poses, lu au debut du bilan du collage (« [Arrêté. ]12 / 20 … ») ; 0 sinon. Logique pure. */
    static int posesDuBilan(String bilan) {
        if (bilan == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:Arrêté\\. )?(\\d+) / ").matcher(bilan);
        try { return m.find() ? Integer.parseInt(m.group(1)) : 0; } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Fin de la pose (bilan du collage, dit ici une seule fois) : la config
     * est cherchee jusqu'a ce qu'elle soit vue ; vue, la fenetre se ferme et
     * le jeu dit que c'est bon. Sinon le bilan (ou ce qui manque) est dit.
     */
    private static void apresPose(CalqueFenetre f, int salle, boolean ok, String bilan, Runnable accueil) {
        boolean posee = ok || posesDuBilan(bilan) > 0;
        if (!posee || (bilan != null && bilan.startsWith("Arrêté"))) {            // rien de pose (refus, pas de clic) ou arret voulu
            WiredCollage.direBilan(ok, bilan);
            javafx.application.Platform.runLater(accueil);
            return;
        }
        // quelque chose a ete pose : on laisse le temps aux coffres d'apparaitre et a la variable
        // d'etre creee ; l'avancement est dans le bandeau du haut (la fenetre est deja fermee)
        javafx.application.Platform.runLater(f::fermer);
        Avancement.Suivi av = Avancement.debut("Boutique troc");
        av.texte("Recherche de la boutique troc dans l'appart…").part(-1);
        Salle.tache("troc-config-posee", () -> {
            try {
                ConfigTroc.Etat e = ConfigTroc.attendreConfig(salle, 12_000, () -> false);
                if (!e.complete()) {
                    ConfigTroc.diagnostic("après la pose");
                    av.fin(Avancement.Issue.ECHEC);
                    if (!ok) WiredCollage.direBilan(false, bilan);
                    else Journal.erreur("La pose est finie, mais la boutique troc n'est pas vue dans l'appart. " + e.texte());
                    return;
                }
                // coffres deja dans l'appart, repris : les wired sont relies a eux (rien si deja bon)
                av.texte("Liaison des wired aux coffres…");
                TrocPoseCoffres.Resultat lien = TrocPoseCoffres.relier();
                if (!lien.ok()) {
                    av.fin(Avancement.Issue.ECHEC);
                    Journal.erreur("La boutique troc est posée, mais " + Character.toLowerCase(lien.erreur().charAt(0)) + lien.erreur().substring(1));
                    return;
                }
                if (!ok && lien.relies() == 0) Journal.debug("Boutique troc : " + bilan);
                av.fin(Avancement.Issue.REUSSI);
                Journal.succes(CONFIG_POSEE);                      // un seul message
            } catch (Throwable t) {
                av.fin(Avancement.Issue.ECHEC);
                Journal.erreur("Recherche de la boutique troc interrompue", t);
            }
        });
    }

    // ================================================================ vente (fil de travail)

    record Vente(int salle, int type, String classe, String nom, int prix, int quantite,
                 List<Exemplaire> possedes, OffresBc.Offre offre) { }

    private static void vendre(Vente v) {
        if (Salle.salleId() != v.salle()) { Journal.erreur("Tu as changé d'appart : vente annulée."); return; }
        TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
        if (!c.complets()) { Journal.erreur("Pas de boutique troc dans cet appart : clique « Vendre au troc » sur un mobi pour la poser."); return; }
        String idPrix = ConfigTroc.idPrix(3000);
        if (idPrix == null) { Journal.erreur("Variable « prix » introuvable dans cet appart : la boutique troc est-elle complète ?"); return; }

        ConfigTroc.Vitrine vitrine = ConfigTroc.vitrineDe(v.salle(), v.type());
        Choix ch = choisir(v.possedes(), v.offre() != null, vitrine != null, v.quantite());
        boolean seule = v.quantite() == 0;                 // vitrine seule : rien a deposer, voulu
        if (ch.aDeposer().isEmpty() && !seule) { Journal.erreur("Aucun exemplaire échangeable à vendre."); return; }

        boolean nouvelle = vitrine == null;
        if (nouvelle) {
            vitrine = poserVitrine(v, ch, seule);
            if (vitrine == null) return;                    // erreur deja dite
        }

        // le prix sur la vitrine : creer la variable (nouvelle vitrine) ou changer sa valeur
        Canal canal = Canal.duMoteur();
        // meme paquet pour creer ou changer : variable de l'utilisatrice, (0, id, var, valeur, 1) comme le client
        HPacket p = PoseOutils.variableSol(vitrine.id(), idPrix, v.prix());
        if (canal == null || !PoseOutils.envoyerVariable(canal, p)) {
            // la vitrine reste (jamais ramassee en silence) : marquee seule, le prix se change dans l'onglet
            if (nouvelle) ConfigTroc.vitrineSeule(v.salle(), vitrine.id(), true);
            Journal.erreur(nouvelle ? "La vitrine est posée, mais son prix n'a pas pu être réglé : change-le dans Troc › Ma boutique troc. "
                    + "Rien n'a été mis en stock." : "Le prix n'a pas pu être donné à la vitrine.");
            return;
        }
        ConfigTroc.prixVitrine(v.salle(), vitrine.id(), v.prix());
        ConfigTroc.dernierPrix(v.type(), v.prix());

        // vitrine seule : posee avec son prix, c'est tout (ca se voit dans le jeu : pas de message)
        if (seule) return;

        List<Integer> ids = new ArrayList<>();
        for (Exemplaire e : ch.aDeposer()) ids.add(e.inventaire());
        // l'avancement du depot : dans le bandeau du haut seulement
        Avancement.Suivi av = Avancement.debut("Vente au troc");
        av.texte(Ui.accorder("Dépôt de " + ids.size() + " mobi(s) dans le coffre…")).part(-1);
        String err;
        try { err = TrocCoffres.deposer(c.mobis(), ids, t -> av.texte(t)); }
        catch (RuntimeException x) { av.fin(Avancement.Issue.ECHEC); throw x; }
        av.fin(err == null ? Avancement.Issue.REUSSI : Avancement.Issue.ECHEC);
        if (err != null) {
            Journal.erreur(err + (nouvelle ? " La vitrine sera ramassée si le coffre n'a pas ce mobi." : ""));
            return;
        }
        // la vitrine a maintenant du stock : ramassee comme les autres quand il sera vide
        if (vitrine.seule()) ConfigTroc.vitrineSeule(v.salle(), vitrine.id(), false);
        Journal.succes(bilan(v.nom(), ids.size(), v.prix()));
    }

    /** Le mobi apparu depuis « avant » : de ce type, ou de cette classe, ou sur cette case ; 0 sinon. */
    private static int nouvelleVitrine(Set<Integer> avant, int type, String classe, int x, int y) {
        int memeType = 0, surCase = 0;
        for (HFloorItem it : Salle.sols()) {
            if (avant.contains(it.getId())) continue;
            boolean ici = it.getTile().getX() == x && it.getTile().getY() == y;
            String c = Salle.classe(it.getTypeId(), false);
            boolean type0 = it.getTypeId() == type || (c != null && c.equals(classe));
            if (type0 && ici) return it.getId();
            if (type0 && memeType == 0) memeType = it.getId();
            if (ici && surCase == 0) surCase = it.getId();
        }
        return memeType != 0 ? memeType : surCase;
    }

    /** Pose une nouvelle vitrine sur la case cliquee ; null (erreur dite) si elle n'apparait pas. */
    private static ConfigTroc.Vitrine poserVitrine(Vente v, Choix ch, boolean seule) {
        boolean bc = v.offre() != null;
        if (!bc && ch.vitrine() == null) { Journal.erreur("Pas d'exemplaire pour la vitrine."); return null; }
        // l'etat d'avant est pris AVANT le clic : si le jeu pose lui-meme le mobi au clic, son
        // ObjectAdd peut arriver avant notre envoi ; pris apres, la vitrine passait pour ancienne
        Set<Integer> avant = new HashSet<>();
        for (HFloorItem it : Salle.sols()) avant.add(it.getId());
        InfoJeu.consigne("Clique dans le jeu la case où poser la vitrine.");
        // aperçu du mobi sur la case cliquee, Tourner, Poser, croix (TrocApercu)
        TrocApercu.Choix choix = TrocApercu.choisir(v.type(), v.classe(), v.nom(), 120_000);
        if (Salle.salleId() != v.salle()) { Journal.erreur("Tu as changé d'appart : vente annulée."); return null; }
        if (choix == null) { Journal.erreur("Pas de clic dans le jeu en 2 minutes : vente annulée."); return null; }
        if (choix.annule()) return null;                     // la croix ou Echap : voulu, rien a dire
        int rot = choix.rot();

        PoseOutils.Signaux.installer();
        long t0 = System.currentTimeMillis();
        int x = choix.x(), y = choix.y();
        HPacket pose = bc ? PoseOutils.poseSolBc(v.offre(), x, y, rot)
                : PoseOutils.poseSolInventaire(ch.vitrine().pose(), x, y, rot);
        Journal.debug("Troc : vitrine envoyée en (" + x + "," + y + ") r" + rot + ", " + (bc ? "BC " + v.offre()
                : "inventaire " + ch.vitrine().pose() + " (placement " + ch.vitrine().inventaire() + ")") + ", type " + v.type()
                + ", " + avant.size() + " mobis avant.");
        Salle.envoyerEspace(pose);
        int[] id = {0};
        // le nouveau mobi : meme type, ou meme classe, ou sur la case cliquee (un type mal lu ne
        // doit pas faire croire a un echec) ; jusqu'a 12 s (serveur lent, confirmation BC dans le jeu)
        PoseOutils.attendre(() -> { id[0] = nouvelleVitrine(avant, v.type(), v.classe(), x, y); return id[0] != 0; }, 12_000, null);
        if (id[0] == 0) {
            StringBuilder vus = new StringBuilder();
            for (HFloorItem it : Salle.sols())
                if (!avant.contains(it.getId()))
                    vus.append(vus.length() == 0 ? "" : ", ").append(it.getId()).append(" type ").append(it.getTypeId())
                            .append(" (").append(it.getTile().getX()).append(",").append(it.getTile().getY()).append(")");
            Journal.debug("Troc : vitrine pas vue en 12 s ; nouveaux mobis : " + (vus.length() == 0 ? "aucun" : vus) + ".");
            String r = PoseOutils.Signaux.raisonPose(t0, x, y);
            Journal.erreur("La vitrine n'a pas été vue dans l'appart" + (r == null ? "" : " : " + r) + ". Vente annulée.");
            return null;
        }
        Journal.debug("Troc : vitrine vue, id " + id[0] + " en " + (System.currentTimeMillis() - t0) + " ms.");
        ConfigTroc.Vitrine vit = new ConfigTroc.Vitrine(id[0], v.type(), bc, v.prix(), seule);
        ConfigTroc.ajouterVitrine(v.salle(), vit);
        return vit;
    }
}

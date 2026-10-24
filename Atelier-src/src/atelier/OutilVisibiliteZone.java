package atelier;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;

/**
 * Fenetre « Visibilité zone » (menu lateral du mode Construction) : les zones
 * de l'appart a cacher chez toi seulement, comme un Sélecteur de zone. Le
 * travail est dans VisibiliteZone ; ici la liste, le choix des deux coins et
 * celui des raccourcis.
 *
 * Une carte par zone : pastille (verte visible, grise masquee), nom, taille,
 * puis Masquer / Afficher, le raccourci, les muraux et la corbeille. Au retour
 * dans l'appart, toutes les zones sont de nouveau visibles (VisibiliteZone) :
 * l'interface le rappelle des qu'une zone est masquee.
 */
final class OutilVisibiliteZone {

    private final VBox liste = new VBox(6);
    private Label etat, resume;
    private Button nouvelle, annuler, toutAfficher, toutMasquer;
    private StackPane enveloppeNouvelle;
    private final Tooltip horsAppart = Ui.bulle("Entre dans un appart pour choisir une zone.");
    private Label rappel;
    private FlowPane groupe;
    /** Un choix de coins lance d'ici est en cours (Zone est partagee avec d'autres outils). */
    private boolean attente = false, secondDit = false;
    /** Le bouton qui attend une touche (un seul a la fois), et de quoi l'arreter. */
    private Runnable arreterCapture = null;
    /** La zone dont le nom est en cours de modification (la liste ne se redessine pas dessous). */
    private VisibiliteZone.ZoneVue enRenommage = null;
    /** Carte survolee (sa zone est mise en valeur dans le jeu), ou null. */
    private VisibiliteZone.ZoneVue survolee = null;
    /** Zone mise en valeur par un clic sur sa carte (numero, appart), -1 : aucune. */
    private int fixee = -1, fixeeSalle = -1;

    Tab construire() {
        VisibiliteZone.installer();
        etat = Ui.etat();
        nouvelle = Ui.bouton(Icones.PLUS, "Nouvelle zone");
        nouvelle.getStyleClass().add("primaire");
        nouvelle.setMinWidth(Region.USE_PREF_SIZE);
        nouvelle.setOnAction(e -> commencer());
        nouvelle.setTooltip(Ui.bulle("Puis clique deux coins dans le jeu."));
        enveloppeNouvelle = new StackPane(nouvelle);
        annuler = Ui.bouton(Icones.VIDER, "Annuler");
        annuler.setMinWidth(Region.USE_PREF_SIZE);
        annuler.setOnAction(e -> Zone.effacer());
        annuler.setVisible(false); annuler.setManaged(false);
        Zone.ecouter(this::suivreZone);
        VisibiliteZone.ecouter(this::remplir);

        // tout masquer / tout afficher, des qu'il y a deux zones
        resume = Ui.discret("");
        rappel = Ui.discret("Au retour dans l'appart, tout redevient visible.");
        toutMasquer = Ui.bouton(Icones.OEIL_FERME, "Tout masquer");
        toutMasquer.setMinWidth(Region.USE_PREF_SIZE);
        toutMasquer.setOnAction(e -> { for (VisibiliteZone.ZoneVue z : VisibiliteZone.zones()) if (!z.masquee) VisibiliteZone.masquer(z, true); });
        toutAfficher = Ui.bouton(Icones.OEIL, "Tout afficher");
        toutAfficher.setMinWidth(Region.USE_PREF_SIZE);
        toutAfficher.setOnAction(e -> { for (VisibiliteZone.ZoneVue z : VisibiliteZone.zones()) if (z.masquee) VisibiliteZone.masquer(z, false); });
        groupe = Ui.boutons(toutMasquer, toutAfficher);

        VBox v = new VBox(Ui.ENTRE_BLOCS,
                Ui.bloc("Nouvelle zone", Ui.boutons(enveloppeNouvelle, annuler), etat,
                        Ui.aide("Clique deux coins dans le jeu (le clic vise la case, même derrière un mobi). "
                                + "Masquer une zone cache son sol et ses mobis chez toi seulement : rien ne change "
                                + "dans l'appart, et les clics passent aux cases derrière.")),
                Ui.bloc("Zones de l'appart", resume, liste, rappel, groupe,
                        Ui.aide("Chaque zone peut avoir son raccourci : il marche dans le jeu (Habbo au premier plan, "
                                + "barre de chat vide). Les zones et leurs raccourcis sont propres à toi et à cet appart, "
                                + "et retrouvés en revenant."),
                        Ui.aide("En revenant dans l'appart (ou s'il se recharge), toutes les zones sont de nouveau visibles : "
                                + "rien ne se masque tout seul.")));
        v.setPadding(new Insets(12, 14, 14, 14));
        v.setFillWidth(true);
        remplir();
        // fenetre fermee : plus rien de mis en valeur par ce volet
        javafx.beans.value.ChangeListener<Boolean> montre = (o, a, b) -> { if (!b) relacher(); };
        v.sceneProperty().addListener((o, a, b) -> {
            if (a != null && a.getWindow() != null) a.getWindow().showingProperty().removeListener(montre);
            if (b == null) { relacher(); return; }
            if (b.getWindow() != null) b.getWindow().showingProperty().addListener(montre);
            b.windowProperty().addListener((o2, a2, b2) -> {
                if (a2 != null) a2.showingProperty().removeListener(montre);
                if (b2 != null) b2.showingProperty().addListener(montre);
            });
        });
        Tab t = new Tab("Visibilité zone", v);
        t.setClosable(false);
        return t;
    }

    // --------------------------------------------------------- nouvelle zone

    private void commencer() {
        if (!Salle.dansUneSalle()) { Ui.erreur(etat, "Entre dans un appart pour choisir une zone."); return; }
        attente = true; secondDit = false;
        Zone.effacer();
        Zone.demarrerChoix();
        etat.setText("Étape 1 sur 2 : clique le premier coin dans le jeu…");
        InfoJeu.consigne("Clique le premier coin de la zone.");
        nouvelle.setDisable(true);
        annuler.setVisible(true); annuler.setManaged(true);
    }

    private void suivreZone() {
        if (!attente) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !secondDit) {
                secondDit = true;
                etat.setText("Étape 2 sur 2 : clique le deuxième coin…");
                InfoJeu.consigne("Choisis le deuxième point.");
            }
            return;
        }
        attente = false;
        annuler.setVisible(false); annuler.setManaged(false);
        reglerNouvelle();
        if (!Zone.definie()) { etat.setText(""); return; }   // annule ou delai passe
        VisibiliteZone.ZoneVue z = VisibiliteZone.ajouter(Zone.minX(), Zone.minY(), Zone.maxX(), Zone.maxY());
        if (z == null) { Ui.erreur(etat, "Zone pas ajoutée : tu n'es plus dans l'appart."); return; }
        etat.setText("");
    }

    /** « Nouvelle zone » : impossible hors appart (la bulle dit pourquoi), ou pendant un choix. */
    private void reglerNouvelle() {
        boolean dedans = VisibiliteZone.salle() != -1;
        nouvelle.setDisable(attente || !dedans);
        // un bouton desactive ne montre pas sa bulle : elle est posee sur son enveloppe
        if (!dedans) Tooltip.install(enveloppeNouvelle, horsAppart);
        else Tooltip.uninstall(enveloppeNouvelle, horsAppart);
    }

    // ----------------------------------------------------------------- liste

    private void remplir() {
        if (arreterCapture != null) return;          // pas pendant le choix d'une touche (la liste reviendra)
        if (enRenommage != null) return;             // ni pendant un renommage
        // les cartes sont refaites : le survol reprendra au prochain mouvement de souris
        if (fixee != -1 && zoneFixee() == null) { fixee = -1; if (survolee == null) sortir(); }
        if (survolee != null) sortir();
        reglerNouvelle();
        liste.getChildren().clear();
        groupe.setVisible(false); groupe.setManaged(false);
        rappel.setVisible(false); rappel.setManaged(false);
        if (VisibiliteZone.salle() == -1) {
            montrerResume(null);
            liste.getChildren().add(vide(Icones.SALLE, "Pas dans un appart", "Entre dans un appart pour voir ses zones."));
            return;
        }
        List<VisibiliteZone.ZoneVue> l = VisibiliteZone.zones();
        if (l.isEmpty()) {
            montrerResume(null);
            liste.getChildren().add(vide(Icones.ZONE_VISIBLE, "Aucune zone ici",
                    "Clique « Nouvelle zone », puis deux coins dans le jeu."));
            return;
        }
        int masquees = 0;
        for (VisibiliteZone.ZoneVue z : l) if (z.masquee) masquees++;
        String r = Ui.accorder(l.size() + " zone(s)");
        if (masquees > 0) r += " · " + Ui.accorder(masquees + " masquée(s)");
        montrerResume(r);
        rappel.setVisible(masquees > 0); rappel.setManaged(masquees > 0);
        for (VisibiliteZone.ZoneVue z : l) liste.getChildren().add(carte(z));
        if (l.size() >= 2) {
            groupe.setVisible(true); groupe.setManaged(true);
            toutMasquer.setDisable(masquees == l.size());
            toutAfficher.setDisable(masquees == 0);
        }
    }

    private void montrerResume(String texte) {
        resume.setText(texte == null ? "" : texte);
        resume.setVisible(texte != null);
        resume.setManaged(texte != null);
    }

    /** Etat vide : un pictogramme, un titre court, une consigne discrete. */
    private static Node vide(String icone, String titre, String consigne) {
        Label t = new Label(titre);
        t.setStyle("-fx-font-weight: bold;");
        VBox textes = new VBox(0, t, Ui.discret(consigne));
        textes.setMinWidth(0);
        HBox.setHgrow(textes, Priority.ALWAYS);
        HBox h = new HBox(8, Icones.petit(icone), textes);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(4, 2, 4, 2));
        return h;
    }

    private Node carte(VisibiliteZone.ZoneVue z) {
        // ---- haut : pastille, nom, taille
        Circle pastille = new Circle(4.5);
        pastille.getStyleClass().add(z.masquee ? "pastille-eteinte" : "pastille-ok");
        Tooltip.install(pastille, Ui.bulle(z.masquee ? "Masquée chez toi" : "Visible"));

        Label nom = new Label(z.nom);
        nom.getStyleClass().add("zone-nom");
        nom.setWrapText(true);
        nom.setMinWidth(0);
        nom.setMinHeight(Region.USE_PREF_SIZE);
        nom.setMaxWidth(Double.MAX_VALUE);
        nom.setTooltip(Ui.bulle("Double-clique pour renommer."));
        nom.setCursor(javafx.scene.Cursor.HAND);
        HBox.setHgrow(nom, Priority.ALWAYS);
        TextField champ = new TextField(z.nom);
        champ.setPrefColumnCount(8);
        champ.setMinWidth(60);
        champ.setMaxWidth(Double.MAX_VALUE);
        champ.setVisible(false); champ.setManaged(false);
        HBox.setHgrow(champ, Priority.ALWAYS);
        Runnable editer = () -> {
            enRenommage = z;
            nom.setVisible(false); nom.setManaged(false);
            champ.setText(z.nom);
            champ.setVisible(true); champ.setManaged(true);
            champ.requestFocus();
            champ.selectAll();
        };
        Runnable valider = () -> {
            if (enRenommage != z) return;
            enRenommage = null;
            String t = champ.getText();
            if (t != null && !t.isBlank() && !t.trim().equals(z.nom)) VisibiliteZone.renommer(z, t);   // redessine
            else remplir();
        };
        champ.setOnAction(e -> valider.run());
        champ.focusedProperty().addListener((o, a, b) -> { if (!b) valider.run(); });
        nom.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) editer.run(); });

        Label taille = Ui.discret(z.taille() + " cases");
        taille.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(taille, Ui.bulle("Largeur × longueur, en cases."));
        Label badge = new Label("Masquée");
        badge.getStyleClass().addAll("badge", "badge-neutre");
        badge.setMinWidth(Region.USE_PREF_SIZE);
        badge.setVisible(z.masquee); badge.setManaged(z.masquee);

        Button renommer = Ui.boutonIcone(Icones.CRAYON, "Renommer la zone");
        renommer.setOnAction(e -> editer.run());
        Button suppr = Ui.boutonIcone(Icones.CORBEILLE, "Supprimer la zone (elle redevient visible)");
        suppr.setOnAction(e -> confirmerSuppression(z, suppr));

        HBox haut = new HBox(7, pastille, nom, champ, badge, taille, renommer, suppr);
        haut.setAlignment(Pos.CENTER_LEFT);

        // ---- bas : l'action principale, puis le raccourci et les muraux
        Button basculer = Ui.bouton(z.masquee ? Icones.OEIL : Icones.OEIL_FERME, z.masquee ? "Afficher" : "Masquer");
        basculer.setMinWidth(Region.USE_PREF_SIZE);
        basculer.setTooltip(Ui.bulle(z.masquee ? "Rendre la zone visible." : "Cacher le sol et les mobis de la zone, chez toi seulement."));
        basculer.setOnAction(e -> VisibiliteZone.basculer(z));

        Button touche = Ui.bouton(z.touche == null ? Icones.PLUS : null,
                z.touche == null ? "Raccourci" : VisibiliteZone.texteTouche(z.touche, z.mods));
        touche.setMinWidth(Region.USE_PREF_SIZE);
        touche.setTooltip(Ui.bulle(z.touche == null
                ? "Choisir un raccourci : clique, puis appuie sur une lettre ou un chiffre avec "
                  + (WindowsClavier.WINDOWS ? "Ctrl ou Alt" : "⌘, ⌃ ou ⌥") + ", ou F1 à F12. Échap annule."
                : "Raccourci de la zone dans le jeu. Clique pour le changer ; Échap annule, Retour arrière le retire."));
        touche.setOnAction(e -> capturer(z, touche));

        CheckBox muraux = new CheckBox("Muraux");
        muraux.setSelected(z.muraux);
        muraux.setTooltip(Ui.bulle("Cacher aussi les muraux accrochés au bord de la zone."));
        muraux.selectedProperty().addListener((o, a, b) -> VisibiliteZone.muraux(z, b));
        FlowPane bas = Ui.boutons(basculer, touche, muraux);
        bas.setHgap(8);

        VBox c = new VBox(6, haut, bas);
        c.setFillWidth(true);
        c.getStyleClass().add("zone-carte");
        if (z.masquee) c.getStyleClass().add("masquee");
        // survol : la zone mise en valeur dans le jeu ; clic : elle le reste (un second clic l'enleve)
        c.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(c, Ui.bulle("Survole la carte pour voir la zone dans le jeu. Clique pour la garder en valeur, clique encore pour l'enlever."));
        c.setUserData(z);
        marquerFixee(c, z);
        c.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> entrer(z));
        c.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> { if (survolee == z) sortir(); });
        c.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) return;
            // les boutons, la case Muraux, le champ et le nom (double-clic : renommer) gardent leur clic
            for (Object n = e.getTarget(); n instanceof Node && n != c; n = ((Node) n).getParent())
                if (n instanceof javafx.scene.control.ButtonBase || n instanceof javafx.scene.control.TextInputControl || n == nom) return;
            fixee = fixee == z.num && fixeeSalle == VisibiliteZone.salle() ? -1 : z.num;
            fixeeSalle = VisibiliteZone.salle();
            for (Node n : liste.getChildren())
                if (n instanceof VBox && n.getUserData() instanceof VisibiliteZone.ZoneVue)
                    marquerFixee((VBox) n, (VisibiliteZone.ZoneVue) n.getUserData());
        });
        return c;
    }

    // ------------------------------------------------- mise en valeur dans le jeu

    /** Bord bleu sur la carte dont la zone est gardee en valeur par un clic. */
    private void marquerFixee(VBox c, VisibiliteZone.ZoneVue z) {
        boolean oui = fixee == z.num && fixeeSalle == VisibiliteZone.salle();
        c.setStyle(oui ? "-fx-border-color: #3E86AC; -fx-border-width: 1.5;" : "");
    }

    private VisibiliteZone.ZoneVue zoneFixee() {
        if (fixee == -1 || fixeeSalle != VisibiliteZone.salle() || VisibiliteZone.salle() == -1) return null;
        for (VisibiliteZone.ZoneVue z : VisibiliteZone.zones()) if (z.num == fixee) return z;
        return null;
    }

    /** Souris sur une carte : ses mobis en valeur et son rectangle au sol (une seule fois par entree). */
    private void entrer(VisibiliteZone.ZoneVue z) {
        if (survolee == z) return;
        survolee = z;
        montrer(z);
    }

    /** Souris hors de la carte : retour a la zone fixee par un clic, sinon plus rien. */
    private void sortir() {
        survolee = null;
        VisibiliteZone.ZoneVue f = zoneFixee();
        if (f != null) montrer(f);
        else { MiseEnValeur.survol(null); Empreinte.effacerZone(); }
    }

    private static void montrer(VisibiliteZone.ZoneVue z) {
        if (VisibiliteZone.salle() == -1 || !Salle.dansUneSalle()) return;   // hors appart : rien
        List<String> j = VisibiliteZone.jetons(z);
        MiseEnValeur.survol(j.isEmpty() ? null : j);
        Empreinte.zone(z.x1, z.y1, z.x2, z.y2);
    }

    /** Fenetre fermee : plus de zone fixee ni survolee. */
    private void relacher() {
        if (fixee == -1 && survolee == null) return;
        fixee = -1;
        sortir();
    }

    /** Confirmation legere : le premier clic arme la corbeille (rouge), le second supprime. */
    private static void confirmerSuppression(VisibiliteZone.ZoneVue z, Button b) {
        if (b.getStyleClass().contains("danger")) { VisibiliteZone.supprimer(z); return; }
        b.getStyleClass().add("danger");
        Tooltip t = b.getTooltip();
        b.setTooltip(Ui.bulle("Clique encore pour supprimer « " + z.nom + " »."));
        javafx.geometry.Bounds r = b.localToScreen(b.getBoundsInLocal());
        if (r != null) b.getTooltip().show(b, r.getMinX(), r.getMaxY() + 2);
        PauseTransition p = new PauseTransition(Duration.seconds(3));
        p.setOnFinished(e -> {
            b.getStyleClass().remove("danger");
            if (b.getTooltip() != null) b.getTooltip().hide();
            b.setTooltip(t);
        });
        p.play();
    }

    // -------------------------------------------------------------- raccourci

    /** Le prochain appui (avec ses modificateurs) devient le raccourci de la zone. */
    private void capturer(VisibiliteZone.ZoneVue z, Button b) {
        if (arreterCapture != null) arreterCapture.run();
        Window w = b.getScene() == null ? null : b.getScene().getWindow();
        if (w == null) return;
        b.setGraphic(null);
        b.setText("Appuie sur une touche…");
        b.getStyleClass().add("capture-touche");
        PauseTransition delai = new PauseTransition(Duration.seconds(10));
        EventHandler<KeyEvent>[] filtre = new EventHandler[1];
        Runnable fin = () -> {
            w.removeEventFilter(KeyEvent.KEY_PRESSED, filtre[0]);
            delai.stop();
            arreterCapture = null;
            remplir();
        };
        // Sur la fenetre : avant ses propres filtres (Echap ferme la fenetre, Cmd+Z annule...).
        filtre[0] = e -> {
            e.consume();
            KeyCode c = e.getCode();
            if (c.isModifierKey()) return;                     // on attend la touche elle-meme
            if (c == KeyCode.ESCAPE) { fin.run(); return; }
            if (c == KeyCode.BACK_SPACE || c == KeyCode.DELETE) {
                VisibiliteZone.raccourci(z, null, 0);
                fin.run();
                return;
            }
            String t = toucheDe(e);
            if (t == null) {
                Ui.erreur(etat, "Touche refusée : choisis une lettre, un chiffre ou F1 à F12.");
                fin.run();
                return;
            }
            String refus = VisibiliteZone.raccourci(z, t, modificateurs(e));
            if (refus != null) Ui.erreur(etat, refus);
            else etat.setText("");
            fin.run();
        };
        w.addEventFilter(KeyEvent.KEY_PRESSED, filtre[0]);
        arreterCapture = fin;
        delai.setOnFinished(e -> { if (arreterCapture == fin) fin.run(); });
        delai.play();
    }

    /** Modificateurs a la facon de RaccourcisGlobaux (Mac) ; sous Windows, Ctrl compte comme Cmd. */
    private static int modificateurs(KeyEvent e) {
        int m = 0;
        if (e.isShiftDown()) m |= RaccourcisGlobaux.Carbon.MAJ;
        if (e.isAltDown()) m |= RaccourcisGlobaux.Carbon.OPTION;
        if (WindowsClavier.WINDOWS) {
            if (e.isControlDown()) m |= RaccourcisGlobaux.Carbon.CMD;
        } else {
            if (e.isMetaDown()) m |= RaccourcisGlobaux.Carbon.CMD;
            if (e.isControlDown()) m |= RaccourcisGlobaux.Carbon.CTRL;
        }
        return m;
    }

    /** Rangee des chiffres en AZERTY, sans Maj : Mac puis Windows. */
    private static final String AZERTY_MAC = "à&é\"'(§è!ç", AZERTY_WIN = "à&é\"'(-è_ç";

    /** « a ».. « z », « 0 ».. « 9 » (rangee ou pave), « F1 ».. « F12 » ; null pour une autre touche. */
    static String toucheDe(KeyEvent e) {
        KeyCode c = e.getCode();
        if (c.isLetterKey()) return c.getName().toLowerCase(Locale.ROOT);
        if (c.isFunctionKey()) {
            String n = c.getName();
            return RaccourcisGlobaux.numeroF(n) > 0 ? n : null;
        }
        switch (c) {
            case DIGIT0: case NUMPAD0: return "0";
            case DIGIT1: case NUMPAD1: case AMPERSAND: return "1";
            case DIGIT2: case NUMPAD2: return "2";
            case DIGIT3: case NUMPAD3: case QUOTEDBL: return "3";
            case DIGIT4: case NUMPAD4: case QUOTE: return "4";
            case DIGIT5: case NUMPAD5: case LEFT_PARENTHESIS: return "5";
            case DIGIT6: case NUMPAD6: return "6";
            case DIGIT7: case NUMPAD7: return "7";
            case DIGIT8: case NUMPAD8: case EXCLAMATION_MARK: case UNDERSCORE: return "8";
            case DIGIT9: case NUMPAD9: return "9";
            default:
        }
        String t = e.getText();
        if (t != null && t.length() == 1) {
            int i = AZERTY_MAC.indexOf(t.charAt(0));
            if (i < 0) i = AZERTY_WIN.indexOf(t.charAt(0));
            if (i >= 0) return String.valueOf(i);
        }
        return null;
    }
}

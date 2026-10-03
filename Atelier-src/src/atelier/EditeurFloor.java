package atelier;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Editeur de floor visuel : le plan de sol de la salle, en relief, avec les
 * mobis, modifiable directement, puis applique dans Habbo par « Valider »
 * (comme l'editeur :floor du jeu, mais en voyant ou sont les mobis).
 *
 * Un seul volet : « Éditeur de floor », avec un petit apercu et un gros bouton
 * « Ouvrir l'éditeur » : une fenetre style Habbo qui couvre presque toute la
 * fenetre du jeu (meme session : tout est synchronise). En haut de la grande
 * fenetre, l'affichage des mobis : sans, avec, en transparence, seulement ceux
 * touches ; les icones officielles des mobis sont posees sur leurs blocs.
 *
 * Fichiers : FloorModele (le plan), FloorSession (original / en cours /
 * historique / outil / bilan), FloorVue (dessin et souris), FloorReseau
 * (paquets ; voir son en-tete pour ce qui est verifie ou suppose).
 */
public class EditeurFloor {

    private final FloorSession s = new FloorSession();
    private FloorVue vue;
    private Label resume, compteur, alertes, etatLigne;
    private Spinner<Integer> mur;
    private ComboBox<String> epMur, epSol;
    private CheckBox apercu, apercuHauteurs;
    private Button bAnnuler, bRetablir, bValider;
    private final List<Runnable> majBoutons = new ArrayList<>();
    private boolean maj = false;
    private volatile boolean lectureEnCours = false, validationEnCours = false;
    private long planVu = 0, erreurVue = 0;
    private final PauseTransition attenteApercu = new PauseTransition(Duration.millis(600));
    private Stage grande;
    private VBox racine;

    public Tab construire() {
        Tab t = new Tab("Éditeur de floor", defiler(volet()));
        t.setClosable(false);
        TabPane volets = new TabPane(t);
        volets.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        volets.setMinHeight(200);
        VBox.setVgrow(volets, Priority.ALWAYS);
        VBox r = new VBox(6, volets);
        r.setPadding(new Insets(10));
        Tab outil = new Tab("Floor", r);
        outil.setClosable(false);
        return outil;
    }

    private static ScrollPane defiler(Pane p) {
        ScrollPane sp = new ScrollPane(p);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    // ---------------------------------------------------------------- volet

    private Pane volet() {
        racine = new VBox(12);
        racine.setPadding(new Insets(12, 14, 14, 14));
        racine.setFillWidth(true);

        resume = Ui.valeur("Pas encore lu.");
        resume.setWrapText(true);
        Button ouvrir = new Button("Ouvrir l'éditeur");
        ouvrir.getStyleClass().add("primaire");
        ouvrir.setMaxWidth(Double.MAX_VALUE);
        ouvrir.setStyle("-fx-font-size: 14px; -fx-padding: 10 14 10 14;");
        ouvrir.setOnAction(e -> ouvrirGrande());
        VBox blocOuvrir = new VBox(6, ouvrir,
                Ui.aide("L'éditeur s'ouvre en grand, par-dessus le jeu, avec tes mobis posés dessus. "
                        + "Tout reste synchronisé avec ce volet."));

        vue = new FloorVue(s, 340, 240);
        vue.setMaxWidth(Double.MAX_VALUE);
        Label survol = Ui.discret("");
        survol.textProperty().bind(vue.survol);
        Button recentrer = new Button("Recentrer");
        recentrer.setOnAction(e -> { vue.recentrer(); vue.dessiner(); });
        ComboBox<FloorSession.Affichage> mobis = choixAffichage();
        CheckBox chiffres = new CheckBox("Hauteurs");
        chiffres.setSelected(true);
        chiffres.selectedProperty().addListener((o, a, b) -> { vue.chiffres = b; vue.dessiner(); });
        ComboBox<String> relief = choixRelief();

        VBox blocPlan = Ui.bloc("Aperçu du plan", resume, Ui.ligne(recentrer), vue, survol,
                Ui.ligne(new Label("Relief"), relief, chiffres), Ui.ligne(new Label("Mobis"), mobis));

        VBox blocOutils = Ui.bloc("Outils", outils(false).toArray(new Node[0]));

        // Taille du plan.
        Button xFin = petit("+ x fin", "Ajoute une colonne vide côté x (bord bas-droite).", () -> s.agrandir(0));
        Button yFin = petit("+ y fin", "Ajoute une rangée vide côté y (bord bas-gauche).", () -> s.agrandir(1));
        Button xDeb = petit("+ x début", "Ajoute une colonne en x = 0 : DÉCALE toutes les cases (pas les mobis).", () -> s.agrandir(2));
        Button yDeb = petit("+ y début", "Ajoute une rangée en y = 0 : DÉCALE toutes les cases (pas les mobis).", () -> s.agrandir(3));
        CheckBox rognerDebut = new CheckBox("Aussi au début (décale)");
        Button rogner = new Button("Rogner");
        rogner.setTooltip(new Tooltip("Retire les colonnes / rangées vides des bords."));
        rogner.setOnAction(e -> s.rogner(rognerDebut.isSelected()));
        VBox blocTaille = Ui.bloc("Taille du plan", Ui.ligne(xFin, yFin, xDeb, yDeb), Ui.ligne(rogner, rognerDebut),
                Ui.aide("Ajouter au début décale le sol d'un cran sous les mobis, qui gardent leurs coordonnées."));

        // Murs et sol.
        mur = new Spinner<>(-1, 15, -1);
        mur.setEditable(true);
        mur.setPrefWidth(80);
        mur.valueProperty().addListener((o, a, b) -> { if (!maj && b != null) s.reglerMur(b); });
        epMur = choixEpaisseur();
        epSol = choixEpaisseur();
        epMur.valueProperty().addListener((o, a, b) -> { if (!maj) reglerEpaisseurs(); });
        epSol.valueProperty().addListener((o, a, b) -> { if (!maj) reglerEpaisseurs(); });
        VBox blocMurs = Ui.bloc("Murs et sol",
                Ui.ligne(new Label("Hauteur des murs"), mur, Ui.discret("−1 = auto")),
                Ui.ligne(new Label("Épaisseur murs"), epMur),
                Ui.ligne(new Label("Épaisseur sol"), epSol));

        // Changements.
        compteur = Ui.valeur("Aucune case modifiée.");
        alertes = new Label("");
        alertes.setWrapText(true);
        alertes.setStyle("-fx-text-fill: #a0331f;");
        Button revenir = new Button("Revenir à l'original");
        revenir.setOnAction(e -> s.revenir());
        VBox blocChang = Ui.bloc("Changements", compteur, alertes, Ui.ligne(revenir));

        // Apercu experimental.
        apercu = new CheckBox("Aperçu dans le jeu (expérimental)");
        apercuHauteurs = new CheckBox("Envoyer aussi la carte des hauteurs");
        apercuHauteurs.setDisable(true);
        apercu.selectedProperty().addListener((o, a, b) -> basculerApercu(b));
        apercuHauteurs.selectedProperty().addListener((o, a, b) -> { if (apercu.isSelected()) attenteApercu.playFromStart(); });
        attenteApercu.setOnFinished(e -> envoyerApercu(false));
        Button vrai = new Button("Renvoyer le vrai plan au jeu");
        vrai.setOnAction(e -> envoyerApercu(true));
        VBox blocApercu = Ui.bloc("Aperçu dans Habbo", apercu, apercuHauteurs, Ui.ligne(vrai),
                Ui.aide("Envoie le nouveau plan à TON jeu seulement (rien ne change sur le serveur). "
                        + "Expérimental : le jeu peut l'ignorer ou mal l'afficher ; recharger la salle remet le vrai plan."));

        bValider = new Button("Valider : appliquer dans Habbo");
        bValider.getStyleClass().add("primaire");
        bValider.setMaxWidth(Double.MAX_VALUE);
        bValider.setOnAction(e -> valider());

        etatLigne = Ui.etat();
        etatLigne.textProperty().bind(s.etat);

        Label aide = Ui.aide("Clic ou glisser = outil · clic droit (ou Espace) + glisser = déplacer · molette = zoom · "
                + "Ctrl/Cmd+Z / Y = annuler / rétablir (clique d'abord dans le plan). "
                + "Vert = case ajoutée, orange = hauteur changée, pointillés rouges = case supprimée, "
                + "mobi cerclé de rouge = touché par un changement. Wired en violet.");

        racine.getChildren().addAll(blocOuvrir, blocPlan, blocOutils, blocTaille, blocMurs, blocChang, blocApercu, bValider, etatLigne, aide);

        s.ecouter(this::surChangement);
        FloorReseau.ecouter(() -> Platform.runLater(this::surReseau));

        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> tic()));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        surChangement();
        return racine;
    }

    // --------------------------------------------------------- outils (x2)

    /** Barre d'outils liee a la session : utilisable dans les deux fenetres. */
    private List<Node> outils(boolean large) {
        List<Node> l = new ArrayList<>();
        ToggleGroup g = new ToggleGroup();
        List<ToggleButton> boutons = new ArrayList<>();
        for (FloorSession.Outil o : FloorSession.Outil.values()) {
            ToggleButton b = new ToggleButton(o.libelle);
            b.setUserData(o);
            b.setToggleGroup(g);
            b.setTooltip(new Tooltip(o.aide));
            if (s.outil.get() == o) b.setSelected(true);
            boutons.add(b);
        }
        g.selectedToggleProperty().addListener((ob, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            s.outil.set((FloorSession.Outil) b.getUserData());
        });
        s.outil.addListener((ob, a, b) -> { for (ToggleButton t : boutons) if (t.getUserData() == b) t.setSelected(true); });

        Spinner<Integer> n = new Spinner<>(0, FloorModele.HAUTEUR_MAX, s.valeur.get());
        n.setEditable(true);
        n.setPrefWidth(72);
        n.valueProperty().addListener((ob, a, b) -> { if (b != null) s.valeur.set(b); });
        s.valeur.addListener((ob, a, b) -> { if (!b.equals(n.getValue())) n.getValueFactory().setValue(b.intValue()); });

        ToggleGroup gp = new ToggleGroup();
        List<ToggleButton> tailles = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ToggleButton b = new ToggleButton(i + "×" + i);
            b.setUserData(i);
            b.setToggleGroup(gp);
            if (s.pinceau.get() == i) b.setSelected(true);
            tailles.add(b);
        }
        gp.selectedToggleProperty().addListener((ob, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            s.pinceau.set((Integer) b.getUserData());
        });
        s.pinceau.addListener((ob, a, b) -> { for (ToggleButton t : tailles) if (b.equals(t.getUserData())) t.setSelected(true); });

        CheckBox rect = new CheckBox("Rectangle");
        rect.setTooltip(new Tooltip("Glisser trace un rectangle ; l'outil s'applique à toutes ses cases au relâchement."));
        rect.selectedProperty().bindBidirectional(s.rectangle);

        ComboBox<String> dir = new ComboBox<>();
        for (int i = 0; i < 8; i++) dir.getItems().add(FloorModele.direction(i));
        dir.setTooltip(new Tooltip("Direction de la porte (aussi : flèches ← → avec l'outil Porte)."));
        Runnable majDir = () -> {
            if (s.courant == null) return;
            int d = ((s.courant.porteDir % 8) + 8) % 8;
            if (dir.getSelectionModel().getSelectedIndex() != d) dir.getSelectionModel().select(d);
        };
        dir.getSelectionModel().selectedIndexProperty().addListener((ob, a, b) -> {
            if (b.intValue() >= 0 && s.courant != null && s.courant.porteDir != b.intValue()) s.reglerDirection(b.intValue());
        });
        s.ecouter(majDir);

        Button an = new Button("↶ Annuler");
        an.setOnAction(e -> s.annuler());
        Button re = new Button("↷ Rétablir");
        re.setOnAction(e -> s.retablir());
        Runnable majHist = () -> { an.setDisable(!s.peutAnnuler()); re.setDisable(!s.peutRetablir()); };
        s.ecouter(majHist);
        majBoutons.add(majHist);
        majHist.run();

        Label aideOutil = Ui.discret(s.outil.get().aide);
        s.outil.addListener((ob, a, b) -> aideOutil.setText(b.aide));

        FlowPane ligneOutils = Ui.ligne(boutons.toArray(new Node[0]));
        ligneOutils.setHgap(6);
        FlowPane lignePinceau = Ui.ligne(new Label("N"), n, new Label("Pinceau"));
        lignePinceau.getChildren().addAll(tailles);
        lignePinceau.getChildren().add(rect);
        lignePinceau.setHgap(6);
        l.add(ligneOutils);
        l.add(lignePinceau);
        l.add(aideOutil);
        l.add(Ui.ligne(new Label("Porte"), dir));
        if (!large) l.add(Ui.ligne(an, re));   // en grand : dans la barre du haut
        return l;
    }

    private static Button petit(String t, String aide, Runnable r) {
        Button b = new Button(t);
        b.setTooltip(new Tooltip(aide));
        b.setOnAction(e -> r.run());
        return b;
    }

    private ComboBox<String> choixRelief() {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().addAll("Plat", "Réduit", "Réel");
        Runnable lire = () -> {
            double r = s.relief.get();
            int i = r <= 0 ? 0 : r < 0.75 ? 1 : 2;
            if (c.getSelectionModel().getSelectedIndex() != i) c.getSelectionModel().select(i);
        };
        lire.run();
        c.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
            if (b.intValue() >= 0) s.relief.set(b.intValue() == 0 ? 0 : b.intValue() == 1 ? 0.5 : 1);
        });
        s.relief.addListener((o, a, b) -> lire.run());
        return c;
    }

    /** Choix de l'affichage des mobis, lie a la session (les deux fenetres suivent). */
    private ComboBox<FloorSession.Affichage> choixAffichage() {
        ComboBox<FloorSession.Affichage> c = new ComboBox<>();
        c.getItems().addAll(FloorSession.Affichage.values());
        c.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(FloorSession.Affichage a) { return a == null ? "" : a.libelle; }
            @Override public FloorSession.Affichage fromString(String t) { return null; }
        });
        c.setValue(s.affichage.get());
        c.valueProperty().addListener((o, a, b) -> { if (b != null) s.affichage.set(b); });
        s.affichage.addListener((o, a, b) -> { if (c.getValue() != b) c.setValue(b); });
        return c;
    }

    /** Index 0..3 -> valeur Habbo -2..1 (comme l'editeur du jeu : 4 choix). */
    private static ComboBox<String> choixEpaisseur() {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().addAll("Très fin (−2)", "Fin (−1)", "Normal (0)", "Épais (1)");
        c.getSelectionModel().select(2);
        return c;
    }

    private void reglerEpaisseurs() {
        int a = epMur.getSelectionModel().getSelectedIndex(), b = epSol.getSelectionModel().getSelectedIndex();
        if (a < 0 || b < 0) return;
        s.reglerEpaisseurs(a - 2, b - 2);
    }

    // ------------------------------------------------------------ mise a jour

    private void surChangement() {
        maj = true;
        try {
            FloorModele c = s.courant;
            if (c == null) {
                resume.setText(Salle.gp() == null ? "G-Presets pas encore prêt." : "Pas de plan chargé.");
                compteur.setText("—");
                alertes.setText("");
                bValider.setDisable(true);
                return;
            }
            String porte = c.porteConnue ? "porte (" + c.porteX + ", " + c.porteY + ") dir. " + c.porteDir : "porte inconnue";
            resume.setText(c.largeur + " × " + c.longueur + " · " + c.nbCases() + " cases · " + porte
                    + " · " + s.mobis.size() + " mobis");
            if (mur.getValue() == null || mur.getValue() != c.hauteurMur) mur.getValueFactory().setValue(Math.max(-1, Math.min(15, c.hauteurMur)));
            epMur.getSelectionModel().select(Math.max(0, Math.min(3, c.epMur + 2)));
            epSol.getSelectionModel().select(Math.max(0, Math.min(3, c.epSol + 2)));

            FloorSession.Bilan b = s.bilan;
            StringBuilder cpt = new StringBuilder();
            cpt.append(b.casesModifiees == 0 ? "Aucune case modifiée" : b.casesModifiees + (b.casesModifiees > 1 ? " cases modifiées" : " case modifiée"));
            if (b.casesModifiees > 0)
                cpt.append(" (").append(b.ajoutees).append(" ajoutée(s), ").append(b.supprimees).append(" supprimée(s), ")
                        .append(b.changees).append(" de hauteur)");
            if (s.original != null && !c.memeReglages(s.original)) cpt.append(" · porte / murs modifiés");
            if (s.original != null && !c.memeTaille(s.original)) cpt.append(" · taille ").append(c.largeur).append("×").append(c.longueur);
            compteur.setText(cpt.toString() + ".");
            List<String> l = new ArrayList<>();
            for (String e : b.erreurs) l.add("✖ " + e);
            for (String a : b.avertissements) l.add("⚠ " + a);
            alertes.setText(String.join("\n", l));
            bValider.setDisable(validationEnCours);
            if (apercu != null && apercu.isSelected()) attenteApercu.playFromStart();
        } finally { maj = false; }
    }

    private boolean aRelire = false;

    private boolean affiche() { return FloorVue.estAffiche(racine) || (grande != null && grande.isShowing()); }

    /** Empreinte des mobis de la salle : poses, retraits, deplacements. */
    private String mobisVus = null;

    private static String empreinteMobis() {
        long h = 0;
        int n = 0;
        for (gearth.extensions.parsers.HFloorItem it : Salle.sols()) {
            n++;
            h += (((long) it.getId()) * 31 + it.getTile().getX()) * 131
                    + it.getTile().getY() * 7 + Double.hashCode(it.getTile().getZ()) + Salle.rotation(it);
        }
        return n + "/" + h;
    }

    private void tic() {
        if (!affiche()) return;
        if (aRelire && !validationEnCours && Salle.dansUneSalle()) { aRelire = false; relire(true, true); return; }
        // Mobis poses, retires ou deplaces dans le jeu : l'editeur suit tout seul,
        // en gardant les changements du plan en cours.
        if (s.pret() && Salle.dansUneSalle() && !validationEnCours && !lectureEnCours) {
            String m = empreinteMobis();
            if (mobisVus != null && !m.equals(mobisVus)) { mobisVus = m; relire(true, false); return; }
            mobisVus = m;
        }
        if (!s.pret()) {
            if (Salle.dansUneSalle()) relire(false, true);
            else surChangement();
        } else if (!Salle.dansUneSalle() && !validationEnCours) {
            s.etat.set("Tu n'es plus dans une salle : le plan affiché est celui de la dernière salle lue.");
        }
    }

    private void surReseau() {
        s.completer();
        if (FloorReseau.erreurRecue > erreurVue) {
            erreurVue = FloorReseau.erreurRecue;
            if (!validationEnCours) s.etat.set("Habbo a refusé le plan : " + FloorReseau.erreur);
        }
        if (FloorReseau.planRecu > planVu) {
            planVu = FloorReseau.planRecu;
            if (!validationEnCours) {
                // Nouvelle salle ou salle rechargee : on relit quand G-Presets a fini de lire,
                // seulement si l'editeur est a l'ecran (sinon a la prochaine ouverture).
                if (affiche()) {
                    PauseTransition p = new PauseTransition(Duration.millis(900));
                    p.setOnFinished(e -> relire(true, true));
                    p.play();
                } else aRelire = true;
            }
        }
        s.change();
    }

    /**
     * Relit la salle. garder : si l'edition en cours a des changements et que
     * c'est la meme salle, on les garde (seuls l'original et les mobis changent).
     */
    private void relire(boolean garder, boolean demander) {
        if (lectureEnCours) return;
        lectureEnCours = true;
        boolean garderEdition = garder && s.modifie();
        Salle.tache("floor-lire", () -> {
            FloorSession.Lecture l = FloorSession.lire();
            if (l.modele != null && demander) {
                // La porte et les cases occupees viennent du serveur.
                FloorReseau.demanderPorte();
                Salle.sommeil(200);
                FloorReseau.demanderOccupees();
            }
            Platform.runLater(() -> {
                lectureEnCours = false;
                if (l.modele == null) {
                    s.etat.set(l.erreur == null ? "Lecture impossible." : l.erreur);
                    surChangement();
                    return;
                }
                int avant = s.salleId;
                s.charger(l, garderEdition);
                if (garderEdition && avant == l.salleId)
                    s.etat.set("Salle mise à jour : tes changements sont gardés (« Revenir à l'original » pour repartir du plan actuel).");
                else
                    s.etat.set("Plan lu : " + l.modele.largeur + " × " + l.modele.longueur + ", "
                            + l.modele.nbCases() + " cases, " + s.mobis.size() + " mobis."
                            + (l.modele.porteConnue ? "" : " Porte demandée au serveur…"));
            });
        });
    }

    // ------------------------------------------------------------ apercu

    private void basculerApercu(boolean oui) {
        apercuHauteurs.setDisable(!oui);
        if (oui) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "L'aperçu envoie le nouveau plan à ton jeu SEULEMENT, à chaque changement. Rien n'est modifié "
                            + "sur le serveur, et les autres ne voient rien.\n\nC'est EXPÉRIMENTAL : le jeu peut ignorer "
                            + "le paquet, mal placer les avatars ou les mobis, voire se figer. Pour revenir au vrai plan : "
                            + "décoche l'aperçu, ou quitte et reviens dans la salle.\n\nActiver ?",
                    ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText("Aperçu dans le jeu");
            proprietaire(a);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                Platform.runLater(() -> apercu.setSelected(false));
                return;
            }
            envoyerApercu(false);
        } else envoyerApercu(true);
    }

    private void envoyerApercu(boolean vrai) {
        FloorModele m = vrai ? s.original : s.courant;
        if (m == null) { s.etat.set("Pas de plan à envoyer."); return; }
        if (!Salle.dansUneSalle()) { s.etat.set("Pas dans une salle : aperçu impossible."); return; }
        FloorModele copie = m.copie();
        boolean hm = apercuHauteurs.isSelected();
        Salle.tache("floor-apercu", () -> {
            boolean ok = FloorReseau.apercuClient(copie, hm);
            Platform.runLater(() -> s.etat.set(!ok ? "Aperçu : envoi au jeu impossible."
                    : vrai ? "Vrai plan renvoyé au jeu (si l'affichage reste faux, recharge la salle)."
                    : "Aperçu envoyé au jeu (expérimental)."));
        });
    }

    // ------------------------------------------------------------ valider

    private void valider() {
        if (!s.pret()) { s.etat.set("Rien à valider : le plan de la salle n'est pas encore lu."); return; }
        if (Salle.gp() == null) { s.etat.set("G-Presets n'est pas encore prêt."); return; }
        if (!Salle.dansUneSalle()) { s.etat.set("Tu dois être dans la salle pour appliquer le plan."); return; }
        FloorSession.Bilan b = s.bilan();
        if (!b.erreurs.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.ERROR, String.join("\n", b.erreurs), ButtonType.OK);
            a.setHeaderText("Le plan ne peut pas être envoyé");
            proprietaire(a);
            a.showAndWait();
            return;
        }
        if (!s.modifie()) { s.etat.set("Aucun changement à appliquer."); return; }
        if (Salle.etat() != null) {
            int id = -1;
            try { id = Salle.etat().getRoomId(); } catch (Throwable ignored) { }
            if (id != s.salleId && id != -1) {
                s.etat.set("Tu as changé de salle : le plan de la nouvelle salle se charge, valide ensuite.");
                return;
            }
        }
        FloorModele c = s.courant;
        StringBuilder t = new StringBuilder();
        t.append("Plan ").append(c.largeur).append(" × ").append(c.longueur).append(", ").append(c.nbCases()).append(" cases.\n");
        t.append(b.casesModifiees).append(" case(s) modifiée(s) : ").append(b.ajoutees).append(" ajoutée(s), ")
                .append(b.supprimees).append(" supprimée(s), ").append(b.changees).append(" changée(s) de hauteur.\n");
        t.append("Porte (").append(c.porteX).append(", ").append(c.porteY).append("), direction ").append(c.porteDir)
                .append(" · murs ").append(c.hauteurMur < 0 ? "auto" : String.valueOf(c.hauteurMur))
                .append(" · épaisseurs ").append(c.epMur).append(" / ").append(c.epSol).append("\n");
        if (!b.avertissements.isEmpty()) {
            t.append("\nAttention :\n");
            for (String a : b.avertissements) t.append("• ").append(a).append("\n");
        }
        t.append("\nLe serveur va RECHARGER la salle pour tout le monde : les avatars présents (toi compris) "
                + "seront renvoyés à la porte. Il faut être propriétaire de la salle (ou avoir les droits).\n\nAppliquer ?");
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, t.toString(), ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Appliquer le nouveau plan dans Habbo");
        a.getDialogPane().setPrefWidth(460);
        proprietaire(a);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) { s.etat.set("Validation annulée."); return; }

        FloorModele envoi = c.copie();
        validationEnCours = true;
        bValider.setDisable(true);
        s.etat.set("Envoi du plan…");
        Salle.tache("floor-valider", () -> {
            long t0 = System.currentTimeMillis();
            boolean envoye = FloorReseau.envoyerPlan(envoi);
            String fin;
            boolean ok = false;
            if (!envoye) fin = "Envoi impossible (G-Presets ou connexion pas prêts).";
            else {
                fin = null;
                for (int i = 0; i < 80 && fin == null; i++) {
                    Salle.sommeil(100);
                    if (FloorReseau.erreurRecue > t0) fin = "Habbo a refusé le plan : " + FloorReseau.erreur;
                    else if (FloorReseau.planRecu > t0) { fin = "Plan appliqué : la salle a été rechargée."; ok = true; }
                }
                if (fin == null)
                    fin = "Pas de rechargement de la salle après 8 s : le plan a sans doute été refusé "
                            + "(il faut être propriétaire ou avoir les droits), ou il est invalide.";
            }
            final String message = fin;
            final boolean reussi = ok;
            if (reussi) Salle.sommeil(1500);   // laisser G-Presets relire la salle
            Platform.runLater(() -> {
                validationEnCours = false;
                planVu = FloorReseau.planRecu;
                erreurVue = FloorReseau.erreurRecue;
                bValider.setDisable(false);
                s.etat.set(message);
                InfoJeu.dire("Floor : " + message);
                if (reussi) {
                    if (apercu.isSelected()) apercu.setSelected(false);
                    relireApres(message);
                }
            });
        });
    }

    private void relireApres(String message) {
        lectureEnCours = false;
        Salle.tache("floor-relire", () -> {
            FloorSession.Lecture l = FloorSession.lire();
            if (l.modele != null) { FloorReseau.demanderPorte(); Salle.sommeil(200); FloorReseau.demanderOccupees(); }
            Platform.runLater(() -> {
                if (l.modele != null) s.charger(l, false);
                s.etat.set(message + (l.modele != null ? " Plan relu." : ""));
            });
        });
    }

    private void proprietaire(Alert a) {
        try {
            Window w = grande != null && grande.isShowing() && grande.isFocused() ? grande
                    : racine.getScene() != null ? racine.getScene().getWindow() : null;
            if (w != null) a.initOwner(w);
            Stage st = (Stage) a.getDialogPane().getScene().getWindow();
            st.setAlwaysOnTop(true);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------- editeur en grand

    private double prisX, prisY, prisL, prisH;

    /**
     * L'editeur en grand : une fenetre style Habbo (barre bleue, reduire,
     * croix rouge) posee sur presque toute la fenetre du jeu.
     */
    private void ouvrirGrande() {
        if (grande != null) {
            placerGrande();
            grande.setIconified(false);
            grande.show();
            grande.toFront();
            return;
        }
        FloorVue v = new FloorVue(s, 900, 640);
        v.chiffres = vue.chiffres;
        Label survol = Ui.discret("");
        survol.textProperty().bind(v.survol);
        Label etat = Ui.etat();
        etat.textProperty().bind(s.etat);

        // --- barre d'affichage, en haut ---
        ToggleGroup gAff = new ToggleGroup();
        List<ToggleButton> modes = new ArrayList<>();
        for (FloorSession.Affichage a : FloorSession.Affichage.values()) {
            ToggleButton b = new ToggleButton(a.libelle);
            b.setUserData(a);
            b.setToggleGroup(gAff);
            b.setTooltip(new Tooltip(a.aide));
            if (s.affichage.get() == a) b.setSelected(true);
            modes.add(b);
        }
        gAff.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            s.affichage.set((FloorSession.Affichage) b.getUserData());
        });
        s.affichage.addListener((o, a, b) -> { for (ToggleButton t : modes) if (t.getUserData() == b) t.setSelected(true); });

        CheckBox icones = new CheckBox("Icônes");
        icones.setTooltip(new Tooltip("Pose l'image officielle de chaque mobi sur son bloc (chargée en fond)."));
        icones.selectedProperty().bindBidirectional(s.icones);
        CheckBox chiffres = new CheckBox("Hauteurs");
        chiffres.setSelected(v.chiffres);
        chiffres.selectedProperty().addListener((o, a, b) -> { v.chiffres = b; v.dessiner(); });
        ComboBox<String> relief = choixRelief();
        Button recentrer = new Button("Recentrer");
        recentrer.setOnAction(e -> { v.recentrer(); v.dessiner(); });

        Button an = new Button("↶ Annuler");
        an.setOnAction(e -> s.annuler());
        Button re = new Button("↷ Rétablir");
        re.setOnAction(e -> s.retablir());
        Runnable majHist = () -> { an.setDisable(!s.peutAnnuler()); re.setDisable(!s.peutRetablir()); };
        s.ecouter(majHist);
        majHist.run();

        Button valider = new Button("Valider : appliquer dans Habbo");
        valider.getStyleClass().add("primaire");
        valider.disableProperty().bind(bValider.disableProperty());
        valider.setOnAction(e -> valider());

        FlowPane affichage = Ui.ligne(modes.toArray(new Node[0]));
        affichage.setHgap(4);
        Region ressort = new Region();
        HBox.setHgrow(ressort, Priority.ALWAYS);
        HBox barre = new HBox(10, affichage, new Separator(javafx.geometry.Orientation.VERTICAL), icones, chiffres,
                new Label("Relief"), relief, recentrer, ressort, an, re, valider);
        barre.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        barre.setPadding(new Insets(8, 10, 8, 10));

        // --- colonne de gauche : outils, taille, changements ---
        Label cpt = Ui.valeur("");
        cpt.setWrapText(true);
        Label al = new Label();
        al.setWrapText(true);
        al.setStyle("-fx-text-fill: #a0331f;");
        Runnable maj = () -> { cpt.setText(compteur.getText()); al.setText(alertes.getText()); };
        s.ecouter(maj);
        maj.run();
        Button revenir = new Button("Revenir à l'original");
        revenir.setOnAction(e -> s.revenir());
        VBox gauche = new VBox(Ui.ENTRE_BLOCS);
        gauche.setPadding(new Insets(10, 12, 12, 12));
        gauche.getChildren().addAll(
                Ui.bloc("Outils", outils(true).toArray(new Node[0])),
                Ui.bloc("Taille du plan",
                        Ui.ligne(petit("+ x fin", "Ajoute une colonne vide côté x.", () -> s.agrandir(0)),
                                petit("+ y fin", "Ajoute une rangée vide côté y.", () -> s.agrandir(1))),
                        Ui.ligne(petit("+ x début", "Ajoute une colonne en x = 0 : DÉCALE les cases (pas les mobis).", () -> s.agrandir(2)),
                                petit("+ y début", "Ajoute une rangée en y = 0 : DÉCALE les cases (pas les mobis).", () -> s.agrandir(3))),
                        Ui.ligne(petit("Rogner", "Retire les colonnes / rangées vides de fin.", () -> s.rogner(false)))),
                Ui.bloc("Changements", cpt, al, Ui.ligne(revenir)),
                Ui.aide("Clic ou glisser = outil · clic droit (ou Espace) + glisser = déplacer · molette = zoom · "
                        + "Ctrl/Cmd+Z / Y = annuler / rétablir. Vert = case ajoutée, orange = hauteur changée, "
                        + "pointillés rouges = case supprimée, contour rouge = mobi touché. Wired en violet."));
        ScrollPane sp = new ScrollPane(gauche);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setPrefWidth(290);
        sp.setMinWidth(240);

        VBox centre = new VBox(6, v, survol, etat);
        centre.setPadding(new Insets(0, 10, 10, 6));
        VBox.setVgrow(v, Priority.ALWAYS);

        BorderPane corps = new BorderPane(centre);
        corps.setTop(barre);
        corps.setLeft(sp);
        corps.getStyleClass().add("fenetre-corps");
        VBox.setVgrow(corps, Priority.ALWAYS);

        // --- cadre style Habbo ---
        grande = new Stage();
        grande.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        grande.setTitle("Atelier — éditeur de floor");
        grande.setAlwaysOnTop(true);

        Label titre = new Label("Éditeur de floor");
        titre.getStyleClass().add("fenetre-titre");
        Button reduire = new Button();
        reduire.setGraphic(Icones.trace(Icones.REDUIRE, "icone-fenetre"));
        reduire.getStyleClass().addAll("fenetre-bouton", "fenetre-reduire");
        reduire.setFocusTraversable(false);
        reduire.setOnAction(e -> grande.setIconified(true));
        Button fermer = new Button();
        fermer.setGraphic(Icones.trace(Icones.FERMER, "icone-fenetre"));
        fermer.getStyleClass().addAll("fenetre-bouton", "fenetre-fermer");
        fermer.setFocusTraversable(false);
        fermer.setOnAction(e -> grande.hide());
        HBox boutons = new HBox(4, reduire, fermer);
        boutons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        boutons.setPickOnBounds(false);
        boutons.setPadding(new Insets(0, 6, 0, 0));
        StackPane barreTitre = new StackPane(titre, boutons);
        barreTitre.getStyleClass().add("fenetre-barre");
        barreTitre.setMinHeight(30);
        barreTitre.setPrefHeight(30);
        barreTitre.setMaxHeight(30);
        barreTitre.setCursor(javafx.scene.Cursor.MOVE);
        barreTitre.setOnMousePressed(e -> { prisX = e.getScreenX() - grande.getX(); prisY = e.getScreenY() - grande.getY(); });
        barreTitre.setOnMouseDragged(e -> { grande.setX(e.getScreenX() - prisX); grande.setY(e.getScreenY() - prisY); });
        barreTitre.setOnMouseClicked(e -> { if (e.getClickCount() == 2) placerGrande(); });

        // Poignee de redimensionnement, coin bas-droit.
        Region poignee = new Region();
        poignee.setPrefSize(16, 16);
        poignee.setMaxSize(16, 16);
        poignee.setCursor(javafx.scene.Cursor.SE_RESIZE);
        poignee.setOnMousePressed(e -> { prisX = e.getScreenX(); prisY = e.getScreenY(); prisL = grande.getWidth(); prisH = grande.getHeight(); });
        poignee.setOnMouseDragged(e -> {
            grande.setWidth(Math.max(700, prisL + e.getScreenX() - prisX));
            grande.setHeight(Math.max(450, prisH + e.getScreenY() - prisY));
        });

        VBox cadre = new VBox(barreTitre, corps);
        cadre.getStyleClass().add("fenetre");
        StackPane racineG = new StackPane(cadre, poignee);
        StackPane.setAlignment(poignee, javafx.geometry.Pos.BOTTOM_RIGHT);
        racineG.setStyle("-fx-background-color: transparent;");
        racineG.setPadding(new Insets(0, 0, 3, 0));

        Scene sc = new Scene(racineG, 1100, 750);
        sc.setFill(javafx.scene.paint.Color.TRANSPARENT);
        try { if (racine.getScene() != null) sc.getStylesheets().addAll(racine.getScene().getStylesheets()); }
        catch (Throwable ignored) { }
        grande.setScene(sc);
        // Echap ferme la grande fenetre (quand le plan n'a pas le clavier pour un raccourci).
        sc.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { grande.hide(); e.consume(); }
        });
        placerGrande();
        // La vue reste abonnee a la session : on la garde avec la fenetre (rouverte telle quelle).
        grande.show();
        grande.toFront();
        if (!s.pret() && Salle.dansUneSalle()) relire(false, true);
        Platform.runLater(() -> { v.recentrer(); v.dessiner(); });
    }

    /** Presque toute la fenetre du jeu ; a defaut 90 % de l'ecran principal. */
    private void placerGrande() {
        if (grande == null) return;
        double[] r = EditeurFloor.cadreGrand(cadreHabbo(),
                javafx.stage.Screen.getPrimary().getVisualBounds());
        grande.setX(r[0]); grande.setY(r[1]); grande.setWidth(r[2]); grande.setHeight(r[3]);
    }

    /** Hauteur de la barre de titre macOS comprise dans le cadre de la fenetre Habbo. */
    private static final double TITRE_MAC = 28, MARGE = 14;

    /** Calcul pur de la place de la grande fenetre (teste a part). */
    static double[] cadreGrand(double[] habbo, javafx.geometry.Rectangle2D ecran) {
        if (habbo != null && habbo.length == 4 && habbo[2] >= 600 && habbo[3] >= 400) {
            double x = habbo[0] + MARGE, y = habbo[1] + TITRE_MAC + MARGE / 2;
            double l = habbo[2] - 2 * MARGE, h = habbo[3] - TITRE_MAC - MARGE / 2 - MARGE;
            return new double[]{x, y, l, h};
        }
        double l = ecran.getWidth() * 0.9, h = ecran.getHeight() * 0.9;
        return new double[]{ecran.getMinX() + (ecran.getWidth() - l) / 2, ecran.getMinY() + (ecran.getHeight() - h) / 2, l, h};
    }

    /**
     * Cadre de la fenetre Habbo {x, y, largeur, hauteur} ou null. Ancrage ne
     * l'expose pas : on essaie, dans l'ordre, un accesseur Ancrage.cadreHabbo()
     * (recommande), le cadre retenu par Fenetre, puis le dernier cadre vu par
     * Ancrage. Lecture seule, par reflexion, jamais d'exception.
     */
    static double[] cadreHabbo() {
        try {
            java.lang.reflect.Method m = Ancrage.class.getDeclaredMethod("cadreHabbo");
            m.setAccessible(true);
            Object o = m.invoke(null);
            if (o instanceof double[] && ((double[]) o).length == 4 && ((double[]) o)[2] > 0) return ((double[]) o).clone();
        } catch (Throwable ignored) { }
        try {
            Fenetre f = Fenetre.instance();
            if (f != null) {
                java.lang.reflect.Field c = Fenetre.class.getDeclaredField("habbo");
                c.setAccessible(true);
                Object o = c.get(f);
                if (o instanceof double[] && ((double[]) o).length == 4 && ((double[]) o)[2] > 0) return ((double[]) o).clone();
            }
        } catch (Throwable ignored) { }
        try {
            double[] d = new double[4];
            String[] noms = {"dernierX", "dernierY", "dernierL", "dernierH"};
            for (int i = 0; i < 4; i++) {
                java.lang.reflect.Field c = Ancrage.class.getDeclaredField(noms[i]);
                c.setAccessible(true);
                d[i] = c.getDouble(null);
            }
            if (d[2] > 0 && d[3] > 0) return d;
        } catch (Throwable ignored) { }
        return null;
    }

    /** Session de l'editeur (pour l'apercu dans le jeu de GrilleJeu). */
    static FloorSession session() { return FloorSession.active; }
}

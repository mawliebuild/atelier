package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menu Wired : copier puis coller une configuration wired (reglages, positions
 * et mobis qu'ils utilisent). Le moteur est WiredCollage ; ce volet choisit
 * QUOI copier et garde les copies NOMMEES, comme les apparts :
 *   - des cases choisies dans le jeu (un clic ajoute ou retire la pile de la
 *     case ; ou une zone en deux clics : toutes les piles dedans) ;
 *   - les mobis selectionnes (calques) ;
 *   - tous les wired de l'appart.
 * « Mes configs wired » : chaque copie avec sa photo (clic : agrandir), son
 * nom et son resume ; Coller ici, Renommer, Supprimer, Reprendre l'apercu.
 *
 * Aucune fenetre a part : l'aperçu du collage (Confirmer / Annuler), la
 * progression (barre, Arreter) et les confirmations (renommer, supprimer) sont
 * dans l'onglet, comme OutilHauteur ; les bilans partent en message dans le jeu.
 */
public class OngletCollageWired {

    private ListView<String> liste;
    private Label compteur, cptListe;
    private TextField nomCopie;
    private CheckBox avecCibles;
    private Button choisirCases;
    /** Suivi dans l'onglet : la copie (sous les boutons de copie) et le collage (sous « Coller ici »). */
    private final WiredCollage.Panneau suiviCopie = new WiredCollage.Panneau(), suiviCollage = new WiredCollage.Panneau();
    /** Renommer / supprimer, en ligne sous la liste (pas de fenetre de confirmation). */
    private VBox ligneRenommer, ligneSupprimer;
    private TextField nouveauNom;
    private Label questionSupprimer;

    /** Cases choisies (x << 32 | y), dans la salle salleCases. */
    private final Set<Long> cases = ConcurrentHashMap.newKeySet();
    private volatile int salleCases = -1;
    private volatile boolean modeCases = false, zoneDemandee = false, deuxiemeDit = false;
    /** Le mode « Choisir des cases » est-il allume (les clics traversent les mobis) ? Lu par GrilleVue. */
    static volatile boolean choixCasesActif = false;
    private volatile long clicMobiA = 0;

    public Tab construire() {
        // ------------------------------------------------ 1. copier
        nomCopie = new TextField();
        nomCopie.setPromptText("Nom (par défaut : la salle et la date)");
        nomCopie.setMaxWidth(Double.MAX_VALUE);
        avecCibles = new CheckBox("Copier aussi les mobis choisis par ces wired");
        avecCibles.setSelected(true);
        avecCibles.setWrapText(true);

        compteur = Ui.valeur("");
        compteur.setWrapText(true);
        choisirCases = new Button("Choisir des cases");
        choisirCases.setGraphic(Icones.petite(Icones.WIRED, 16, false));
        choisirCases.setGraphicTextGap(7);
        choisirCases.setOnAction(e -> basculerModeCases());
        Button zone = new Button("Zone en deux clics");
        zone.setOnAction(e -> choisirZone());
        Button vider = new Button("Vider");
        vider.setGraphic(Icones.petite(Icones.VIDER, 14, false));
        vider.setGraphicTextGap(6);
        vider.setOnAction(e -> { cases.clear(); majCompteur(); });

        Button copierPiles = new Button("Copier ces piles");
        copierPiles.getStyleClass().add("primaire");
        copierPiles.setGraphic(Icones.petite(Icones.DUPLIQUER, 16, true));
        copierPiles.setGraphicTextGap(7);
        copierPiles.setMaxWidth(Double.MAX_VALUE);
        copierPiles.setOnAction(e -> {
            List<Integer> ids = mobisDesCases();
            if (ids.isEmpty()) {
                InfoJeu.consigne("Clique d'abord les cases des piles à copier (bouton « Choisir des cases »).");
                return;
            }
            arreterModeCases(false);
            copier(ids);
        });
        MiseEnValeur.auSurvol(copierPiles, this::jetonsDesCases);

        Button copierSel = new Button("Copier les wired sélectionnés");
        copierSel.setMaxWidth(Double.MAX_VALUE);
        copierSel.setOnAction(e -> {
            Groupes.Selection s = Groupes.selection();
            if (s.sols.isEmpty()) {
                InfoJeu.consigne("Sélectionne d'abord des mobis (Option + C puis clic dans le jeu, ou zone).");
                return;
            }
            copier(new ArrayList<>(s.sols));
        });
        Button copierTout = new Button("Copier tous les wired de l'appart");
        copierTout.setMaxWidth(Double.MAX_VALUE);
        copierTout.setOnAction(e -> {
            List<Integer> ids = new ArrayList<>();
            for (HFloorItem it : WiredLecteur.boitesDeLaSalle()) ids.add(it.getId());
            if (ids.isEmpty()) { Journal.erreur("Aucun wired dans cet appart."); return; }
            copier(ids);
        });
        // survol : ce qui va etre copie s'allume dans le jeu
        MiseEnValeur.auSurvol(copierTout, () -> MiseEnValeur.solsOu(it -> Wired.estBoite(Salle.classe(it.getTypeId(), false))));
        MiseEnValeur.auSurvol(copierSel, () -> {
            List<String> j = new ArrayList<>();
            for (int s : Groupes.selection().sols) j.add("s" + s);
            return j;
        });
        HBox autres = new HBox(8, copierSel, copierTout);
        HBox.setHgrow(copierSel, Priority.ALWAYS);
        HBox.setHgrow(copierTout, Priority.ALWAYS);

        // fenetre Wired ouverte : les wired des cases choisies sont mis en valeur dans le jeu
        MiseEnValeur.fournir("wired", () -> ApercuMobis.enCours() ? List.<String>of() : jetonsDesCases());
        Salle.surClicCase(this::clicCase);
        Salle.surClicMobi(this::clicMobi);
        Zone.ecouter(this::suivreZone);

        // ------------------------------------------------ 2. mes configs wired
        liste = new ListView<>();
        liste.setPrefHeight(250);
        liste.setMinHeight(150);
        Label vide = new Label("Aucune config pour l'instant : copie des wired au-dessus.");
        vide.setWrapText(true);
        vide.getStyleClass().add("aide-vide");
        liste.setPlaceholder(vide);
        liste.setCellFactory(lv -> new ListCell<>() {
            { setPrefWidth(0); }
            @Override protected void updateItem(String nom, boolean estVide) {
                super.updateItem(nom, estVide);
                if (estVide || nom == null) { setText(null); setGraphic(null); return; }
                Label n = new Label(nom);
                n.setStyle("-fx-font-weight: bold;");
                n.setWrapText(true);
                Label d = Ui.discret(Ui.majuscule(WiredCollage.resumeCopie(nom)));
                d.setStyle("-fx-font-style: normal; -fx-opacity: 0.7; -fx-font-size: 11px;");
                d.setWrapText(true);
                VBox texte = new VBox(2, n, d);
                texte.setAlignment(Pos.CENTER_LEFT);
                texte.setMinWidth(0);
                HBox.setHgrow(texte, Priority.ALWAYS);
                HBox h = new HBox(10, vignette(nom, lv), texte);
                h.setAlignment(Pos.CENTER_LEFT);
                h.setPadding(new Insets(2, 0, 2, 0));
                setGraphic(h);
                setText(null);
            }
        });
        liste.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) WiredCollage.choisir(b);
            majCptListe();
        });

        cptListe = new Label("");
        cptListe.getStyleClass().add("salle-compte");
        cptListe.setWrapText(true);
        Button renommer = Icones.seul(Icones.CRAYON, "Renommer la config");
        renommer.setOnAction(e -> renommer());
        Button supprimer = Icones.seul(Icones.CORBEILLE, "Supprimer la config (définitif)");
        supprimer.setOnAction(e -> supprimer());
        Button apercu = Icones.seul(Icones.CAPTURE, "Reprendre l'aperçu : nouvelle photo des wired de la config "
                + "choisie, dans l'appart où elle a été copiée.");
        apercu.getTooltip().setWrapText(true);
        apercu.getTooltip().setMaxWidth(300);
        apercu.setOnAction(e -> reprendreApercu());
        Button coller = Icones.sur(new Button("Coller ici"), Icones.COLLER);
        coller.getStyleClass().add("primaire");
        coller.setMaxWidth(Double.MAX_VALUE);
        coller.setOnAction(e -> {
            String nom = liste.getSelectionModel().getSelectedItem();
            if (nom == null) { InfoJeu.consigne("Choisis d'abord une config dans la liste."); return; }
            WiredCollage.collerDans(nom, null, suiviCollage);
        });
        for (Button b : new Button[]{coller, renommer, supprimer, apercu})
            b.disableProperty().bind(liste.getSelectionModel().selectedItemProperty().isNull());
        HBox actions = new HBox(4, renommer, apercu, supprimer);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox sousListe = new HBox(8, cptListe, actions);
        sousListe.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cptListe, Priority.ALWAYS);
        cptListe.setMaxWidth(Double.MAX_VALUE);
        construireLignes();

        VBox v = new VBox(14,
                Ui.bloc("Copier des piles de wired",
                        Ui.ligne(choisirCases, zone, vider),
                        compteur,
                        copierPiles,
                        Ui.aide("« Choisir des cases » : chaque clic sur une case dans le jeu ajoute ou retire "
                                + "sa pile de wired (elle s'allume). « Zone en deux clics » ajoute toutes les "
                                + "piles entre deux coins.")),
                Ui.bloc("Autres copies",
                        autres,
                        Ui.aide("Les wired sélectionnés dans les calques, ou tous ceux de l'appart.")),
                suiviCopie.noeud(),
                Ui.bloc("Réglages de la copie",
                        nomCopie, avecCibles,
                        Ui.aide("Sans nom, la copie prend celui de la salle et la date. Une copie existante "
                                + "n'est jamais écrasée. Une photo est prise à chaque copie.")),
                Ui.bloc("Mes configs wired",
                        liste, sousListe, ligneRenommer, ligneSupprimer,
                        Ui.aide("Clique un aperçu pour l'agrandir. Les icônes sous la liste renomment, "
                                + "reprennent la photo ou suppriment la config choisie.")),
                Ui.bloc("Coller",
                        coller,
                        suiviCollage.noeud(),
                        Ui.aide("Aperçu d'abord, puis Confirmer, puis clique dans le jeu la case du coin "
                                + "haut-gauche de la destination.")));
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));
        majCompteur();

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Copier / coller la config", sp);
        t.setClosable(false);
        // la fenetre se ferme ou passe a autre chose : le choix des cases s'arrete
        t.selectedProperty().addListener((o, a, b) -> { if (!b) arreterModeCases(false); });
        v.sceneProperty().addListener((o, a, sc) -> {
            if (sc == null) { arreterModeCases(false); return; }
            sc.windowProperty().addListener((o2, w0, w) -> {
                if (w != null) w.showingProperty().addListener((o3, x, montre) -> { if (!montre) arreterModeCases(false); });
            });
            if (sc.getWindow() != null)
                sc.getWindow().showingProperty().addListener((o3, x, montre) -> { if (!montre) arreterModeCases(false); });
        });
        surveillerDossier();
        return t;
    }

    // ============================================================ copier

    private void copier(List<Integer> ids) {
        String nom = nomCopie.getText() == null ? "" : nomCopie.getText().trim();
        WiredCollage.copier(ids, suiviCopie, nom.isEmpty() ? null : nom, avecCibles.isSelected(), fait ->
                Platform.runLater(() -> {
                    nomCopie.clear();
                    charger(fait);
                }));
    }

    // ============================================================ cases choisies

    private static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /** Les cases ne valent que pour la salle ou elles ont ete choisies. */
    private void verifierSalle() {
        int s = Salle.salleId();
        if (s != salleCases) { cases.clear(); salleCases = s; }
    }

    private void basculerModeCases() {
        if (modeCases) { arreterModeCases(true); return; }
        if (!Salle.dansUneSalle()) { Journal.erreur("Tu n'es pas dans une salle."); return; }
        verifierSalle();
        modeCases = true;
        choixCasesActif = true;
        choisirCases.setText("Arrêter le choix");
        Salle.tache("wired-cases-clics", () -> GrilleVue.clicsAuSol(true));
        InfoJeu.consigne("Clique les cases des piles à copier : un clic ajoute, un autre retire.");
        majCompteur();
    }

    private void arreterModeCases(boolean dire) {
        if (!modeCases) return;
        modeCases = false;
        choixCasesActif = false;
        Runnable r = () -> { if (choisirCases != null) choisirCases.setText("Choisir des cases"); };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
        if (!Zone.choixEnCours()) Salle.tache("wired-cases-clics", () -> GrilleVue.clicsAuSol(false));
        if (dire) InfoJeu.consigne("Choix des cases terminé : " + texteCompteur() + ".");
    }

    private void clicCase(HPoint c) {
        if (!modeCases || c == null || Zone.choixEnCours()) return;
        if (System.currentTimeMillis() - clicMobiA < 600) return;    // suite d'un clic sur un mobi
        basculer(c.getX(), c.getY());
    }

    private void clicMobi(HFloorItem it) {
        if (!modeCases || it == null || it.getTile() == null || Zone.choixEnCours()) return;
        clicMobiA = System.currentTimeMillis();
        basculer(it.getTile().getX(), it.getTile().getY());
    }

    private void basculer(int x, int y) {
        verifierSalle();
        long k = cle(x, y);
        if (cases.remove(k)) {
            InfoJeu.consigne("Case (" + x + "," + y + ") retirée : " + texteCompteur() + ".");
        } else {
            int n = 0;
            for (HFloorItem it : WiredLecteur.wiredDeLaSalle())
                if (it.getTile().getX() == x && it.getTile().getY() == y) n++;
            if (n == 0) { InfoJeu.consigne("Aucun wired sur la case (" + x + "," + y + ")."); return; }
            cases.add(k);
            InfoJeu.consigne("Pile (" + x + "," + y + ") ajoutée, " + n + " wired : " + texteCompteur() + ".");
        }
        majCompteur();
    }

    /** Zone en deux clics : toutes les piles de wired dedans s'ajoutent. */
    private void choisirZone() {
        if (!Salle.dansUneSalle()) { Journal.erreur("Tu n'es pas dans une salle."); return; }
        verifierSalle();
        zoneDemandee = true; deuxiemeDit = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Clique le premier coin de la zone.");
    }

    private void suivreZone() {
        if (!zoneDemandee) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxiemeDit) { deuxiemeDit = true; InfoJeu.consigne("Clique le second coin de la zone."); }
            return;
        }
        zoneDemandee = false;
        if (modeCases) Salle.tache("wired-cases-clics", () -> GrilleVue.clicsAuSol(true));   // la zone l'a coupe
        if (!Zone.definie()) return;
        verifierSalle();
        int ajout = 0;
        for (HFloorItem it : WiredLecteur.wiredDeLaSalle()) {
            int x = it.getTile().getX(), y = it.getTile().getY();
            if (Zone.contient(x, y) && cases.add(cle(x, y))) ajout++;
        }
        InfoJeu.consigne(ajout == 0 ? "Aucune nouvelle pile de wired dans cette zone."
                : ajout + " pile(s) ajoutée(s) : " + texteCompteur() + ".");
        majCompteur();
    }

    /** Tous les mobis de sol poses sur les cases choisies (wired et autres). */
    private List<Integer> mobisDesCases() {
        verifierSalle();
        List<Integer> r = new ArrayList<>();
        if (cases.isEmpty()) return r;
        for (HFloorItem it : Salle.sols())
            if (it.getTile() != null && cases.contains(cle(it.getTile().getX(), it.getTile().getY()))) r.add(it.getId());
        return r;
    }

    private List<String> jetonsDesCases() {
        List<String> r = new ArrayList<>();
        if (cases.isEmpty() || Salle.salleId() != salleCases) return r;
        for (HFloorItem it : WiredLecteur.wiredDeLaSalle())
            if (cases.contains(cle(it.getTile().getX(), it.getTile().getY()))) r.add("s" + it.getId());
        return r;
    }

    private String texteCompteur() {
        verifierSalle();
        return Ui.accorder(cases.size() + " case(s), " + jetonsDesCases().size() + " wired");
    }

    private void majCompteur() {
        String t = cases.isEmpty()
                ? (modeCases ? "Clique les cases des piles dans le jeu…" : "Aucune case choisie.")
                : Ui.majuscule(texteCompteur()) + (modeCases ? " · choix en cours" : "");
        Platform.runLater(() -> { if (compteur != null) compteur.setText(t); });
    }

    // ============================================================ mes configs

    /** Vignettes des apercus, par fichier et date. */
    private final Map<String, javafx.scene.image.Image> vignettes = new HashMap<>();

    private Node vignette(String nom, Control lv) {
        File png = WiredCollage.fichierPng(nom);
        StackPane cadre = new StackPane();
        cadre.setMinSize(88, 60); cadre.setPrefSize(88, 60); cadre.setMaxSize(88, 60);
        cadre.setStyle("-fx-background-color: rgba(0,0,0,0.08); -fx-background-radius: 4;");
        if (!png.isFile()) {
            Node ic = Icones.petit(Icones.CAPTURE);
            ic.setOpacity(0.45);
            cadre.getChildren().add(ic);
            Tooltip.install(cadre, new Tooltip("Pas encore d'aperçu."));
            return cadre;
        }
        javafx.scene.image.Image img = vignettes.computeIfAbsent(png.getPath() + "@" + png.lastModified(),
                k -> new javafx.scene.image.Image(png.toURI().toString(), 88 * 3, 60 * 3, true, true, true));
        javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
        iv.setFitWidth(88); iv.setFitHeight(60); iv.setPreserveRatio(true);
        cadre.getChildren().add(iv);
        cadre.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(cadre, new Tooltip("Agrandir"));
        cadre.setOnMouseClicked(e -> {
            e.consume();
            String css = lv.getScene() == null || lv.getScene().getStylesheets().isEmpty()
                    ? null : lv.getScene().getStylesheets().get(0);
            OngletGalerie.Visionneuse.ouvrir(css, png);
        });
        return cadre;
    }

    /** Relit la liste ; garde (ou prend) la selection « garder ». Fil JavaFX. */
    private void charger(String garder) {
        String sel = garder != null ? garder : liste.getSelectionModel().getSelectedItem();
        List<String> noms = WiredCollage.noms();
        liste.getItems().setAll(noms);
        if (sel == null || !noms.contains(sel)) sel = WiredCollage.choisie();
        if (sel != null && noms.contains(sel)) liste.getSelectionModel().select(sel);
        liste.refresh();
        majCptListe();
    }

    private void majCptListe() {
        if (cptListe == null) return;
        int n = liste.getItems().size();
        cptListe.setText(n == 0 ? "" : Ui.accorder(n + " config(s) wired"));
    }

    /** Les lignes « renommer » et « supprimer », cachees jusqu'au clic sur leur icone. */
    private void construireLignes() {
        nouveauNom = new TextField();
        nouveauNom.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nouveauNom, Priority.ALWAYS);
        Button ok = new Button("Renommer");
        ok.getStyleClass().add("primaire");
        ok.setMinWidth(Region.USE_PREF_SIZE);
        Button annulerNom = new Button("Annuler");
        annulerNom.setMinWidth(Region.USE_PREF_SIZE);
        nouveauNom.setOnAction(e -> validerNom());
        ok.setOnAction(e -> validerNom());
        annulerNom.setOnAction(e -> montrer(ligneRenommer, false));
        HBox h = new HBox(6, nouveauNom, annulerNom, ok);
        h.setAlignment(Pos.CENTER_LEFT);
        ligneRenommer = new VBox(4, Ui.etiquette("Nouveau nom"), h);
        montrer(ligneRenommer, false);

        questionSupprimer = new Label();
        questionSupprimer.setWrapText(true);
        questionSupprimer.setMinHeight(Region.USE_PREF_SIZE);
        Button oui = new Button("Supprimer");
        oui.getStyleClass().add("primaire");
        Button non = new Button("Annuler");
        non.setOnAction(e -> montrer(ligneSupprimer, false));
        oui.setOnAction(e -> validerSuppression());
        HBox b = new HBox(6, non, oui);
        b.setAlignment(Pos.CENTER_RIGHT);
        ligneSupprimer = new VBox(4, questionSupprimer, b);
        montrer(ligneSupprimer, false);
        // une autre config choisie : les questions en cours ne valent plus
        liste.getSelectionModel().selectedItemProperty().addListener((o, x, y) -> {
            montrer(ligneRenommer, false);
            montrer(ligneSupprimer, false);
        });
    }

    private static void montrer(Node n, boolean v) { n.setVisible(v); n.setManaged(v); }

    private void renommer() {
        String nom = liste.getSelectionModel().getSelectedItem();
        if (nom == null) return;
        montrer(ligneSupprimer, false);
        nouveauNom.setText(nom);
        montrer(ligneRenommer, true);
        nouveauNom.requestFocus();
        nouveauNom.selectAll();
    }

    private void validerNom() {
        String nom = liste.getSelectionModel().getSelectedItem();
        montrer(ligneRenommer, false);
        if (nom == null) return;
        String n = nouveauNom.getText();
        String err = WiredCollage.renommer(nom, n);
        if (err != null) { Journal.erreur(err); return; }
        String nouveau = WiredCollage.nettoyer(n);
        if (!nouveau.equals(nom)) Journal.succes("« " + nom + " » renommée en « " + nouveau + " ».");
        charger(nouveau);
    }

    private void supprimer() {
        String nom = liste.getSelectionModel().getSelectedItem();
        if (nom == null) return;
        montrer(ligneRenommer, false);
        questionSupprimer.setText("Supprimer « " + nom + " » ? C'est définitif.");
        montrer(ligneSupprimer, true);
    }

    private void validerSuppression() {
        String nom = liste.getSelectionModel().getSelectedItem();
        montrer(ligneSupprimer, false);
        if (nom == null) return;
        if (!WiredCollage.supprimer(nom)) { Journal.erreur("Impossible de supprimer « " + nom + " »."); return; }
        Journal.succes("Config « " + nom + " » supprimée.");
        liste.getSelectionModel().clearSelection();
        charger(null);
    }

    /** Nouvelle photo de la config choisie : ses mobis doivent etre dans la salle ou l'on est. */
    private void reprendreApercu() {
        String nom = liste.getSelectionModel().getSelectedItem();
        if (nom == null) return;
        List<Integer> ids = new ArrayList<>();
        for (Integer id : WiredCollage.idsDe(nom)) if (Salle.sol(id) != null) ids.add(id);
        if (ids.isEmpty()) {
            Journal.erreur("Les wired de « " + nom + " » ne sont pas dans cette salle : va dans l'appart où tu l'as copiée.");
            return;
        }
        Salle.tache("wired-apercu", () -> {
            String err = ApercuMobis.prendre(WiredCollage.fichierPng(nom), ids);
            if (err == null) Journal.succes("Aperçu de « " + nom + " » repris.");
            else Journal.erreur(err);
            Platform.runLater(() -> { vignettes.clear(); liste.refresh(); });
        });
    }

    /** La liste se tient a jour toute seule : on surveille la date du dossier des copies. */
    private void surveillerDossier() {
        Thread t = new Thread(() -> {
            long vue = -1;
            while (true) {
                try {
                    File d = WiredCollage.dossierCopies();
                    long m = d.exists() ? d.lastModified() : 0;
                    if (m != vue) {
                        vue = m;
                        Platform.runLater(() -> charger(null));
                    }
                    majCompteurSiSalleChange();
                } catch (Throwable e) {
                    Journal.debug("Configs wired : surveillance du dossier : " + e);
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-dossier-copies-wired");
        t.setDaemon(true);
        t.start();
    }

    /** Changement de salle : les cases choisies ne valent plus. */
    private void majCompteurSiSalleChange() {
        if (!cases.isEmpty() && Salle.salleId() != salleCases) {
            cases.clear();
            if (modeCases) arreterModeCases(false);
            majCompteur();
        }
    }
}

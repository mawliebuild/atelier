package atelier;

import extension.GPresets;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Rubrique Parametres.
 *
 * Le maintien de la fenetre au premier plan, et les actions de reglage qui
 * passent par les methodes publiques de G-Presets.
 */
public class OngletParametres {

    private final Stage fenetre;
    private CheckBox devant;
    private Label etat;

    public OngletParametres(Stage fenetre) {
        this.fenetre = fenetre;
    }

    public Tab construire() {
        etat = Ui.etat();

        devant = new CheckBox("Garder l'Atelier devant le jeu");
        devant.setSelected(fenetre.isAlwaysOnTop());
        devant.selectedProperty().addListener((o, a, b) -> {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows())
                if (w instanceof Stage) ((Stage) w).setAlwaysOnTop(b);
            dire(b ? "La barre et la fenêtre restent devant Habbo."
                   : "L'Atelier passe derrière quand tu cliques dans le jeu.");
        });

        Slider fondu = new Slider(20, 100, Fenetre.instance() != null ? Fenetre.instance().fondu()
                : java.util.prefs.Preferences.userRoot().node("atelier").getDouble("fenetre.fondu", 100));
        fondu.setMajorTickUnit(5);
        fondu.setMinorTickCount(0);
        fondu.setSnapToTicks(true);
        fondu.setBlockIncrement(5);
        fondu.setFocusTraversable(false);
        HBox.setHgrow(fondu, Priority.ALWAYS);
        Label fonduValeur = new Label(Math.round(fondu.getValue()) + " %");
        fonduValeur.setMinWidth(40);
        fondu.valueProperty().addListener((o, x, v) -> {
            fonduValeur.setText(Math.round(v.doubleValue()) + " %");
            if (Fenetre.instance() != null) Fenetre.instance().fondu(v.doubleValue());
        });
        HBox ligneFondu = new HBox(8, fondu, fonduValeur);
        ligneFondu.setAlignment(Pos.CENTER_LEFT);
        Label aideFondu = Ui.aide("À 100 %, la fenêtre reste opaque. Plus bas, elle devient "
                + "transparente quand ta souris est sur le jeu, pour voir les mobis derrière.");

        Button cacheBc = plein("Vider le cache BC",
                gp -> gp.clearBCClick(null), "Cache BC vidé");
        Button cacheWired = plein("Vider le cache wired",
                gp -> gp.clearWiredClick(null), "Cache wired vidé");

        cacheBc.setMaxWidth(Double.MAX_VALUE);
        cacheWired.setMaxWidth(Double.MAX_VALUE);

        // Deux colonnes : l'Atelier lui-meme a gauche, le client du jeu a droite.
        VBox gauche = new VBox(12,
                Ui.bloc("Fenêtres", devant, Ui.etiquette("Transparence quand la souris est sur le jeu"), ligneFondu, aideFondu),
                Ui.bloc("Raccourcis clavier", raccourcis()),
                Ui.bloc("Données", Ui.ligne(cacheBc, cacheWired),
                        Ui.aide("Vider un cache force G-Presets à relire le catalogue BC ou les réglages wired.")));
        VBox droite = new VBox(12, blocInventaire());
        for (VBox c : new VBox[]{gauche, droite}) {
            c.setFillWidth(true);
            c.setMinWidth(300);
            HBox.setHgrow(c, Priority.ALWAYS);
            c.setPrefWidth(10_000);
        }
        HBox colonnes = new HBox(18, gauche, droite);
        colonnes.setFillHeight(false);
        VBox v = new VBox(12, colonnes, etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(14, 18, 18, 18));
        v.setPrefHeight(2000);          // la fenetre prend presque toute la hauteur

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Tab t = new Tab("Paramètres", sp);
        t.setClosable(false);
        return t;
    }

    /** Les raccourcis de l'Atelier, pour s'en souvenir. */
    private static VBox raccourcis() {
        String[][] l = {
                {"⌥ Option + C", "Mode calque (en mode Construction) : clic sur un mobi = sélection"},
                {"⌥ Option + G", "Afficher / cacher la grille (mode Construction)"},
                {"⌥ Option + Maj + C / V", "Copier / coller un calque, dans le jeu"},
                {"⌘ Cmd + Z / ⌘ Maj + Z", "Annuler / rétablir dans la salle"},
                {"Échap", "Dans le jeu : relâcher le mobi pris avec Option + clic (il reste à sa place)"},
                {":h 2,5", "Dans le chat : hauteur fixe des dalles magiques"}};
        GridPane g = new GridPane();
        g.setHgap(14); g.setVgap(6);
        for (int i = 0; i < l.length; i++) {
            Label k = new Label(l[i][0]);
            k.setStyle("-fx-font-weight: bold;");
            k.setMinWidth(Region.USE_PREF_SIZE);
            Label t = new Label(l[i][1]);
            t.setWrapText(true);
            g.add(k, 0, i);
            g.add(t, 1, i);
        }
        return new VBox(g);
    }

    // ------------------------------------------------------------ inventaire

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userRoot().node("atelier");

    private final java.util.Map<String, CheckBox> modifs = new java.util.LinkedHashMap<>();
    private Ui.Voyant voyantClient;
    private Label optionsInstallees, versionJeu, etatInv;
    private Button appliquer, revenir, annulerAttente;
    private volatile boolean occupe, attendre;

    /**
     * Section Inventaire : choisir les modifications du client du jeu, puis
     * les construire et les installer (scripts de ~/Documents/Atelier-swf).
     */
    private VBox blocInventaire() {
        VBox cases = new VBox(4);
        for (String[] m : ClientModifie.MODIFS) {
            CheckBox c = new CheckBox(m[1]);
            c.setSelected(PREFS.getBoolean("inventaire.mod." + m[0], true));
            c.selectedProperty().addListener((o, a, b) -> {
                PREFS.putBoolean("inventaire.mod." + m[0], b);
                majCases();
            });
            modifs.put(m[0], c);
            cases.getChildren().add(c);
        }
        majCases();

        voyantClient = new Ui.Voyant("Client");
        voyantClient.regler("attente", "Lecture…");
        optionsInstallees = Ui.discret("");
        optionsInstallees.managedProperty().bind(optionsInstallees.visibleProperty());
        optionsInstallees.setVisible(false);
        versionJeu = Ui.discret("");
        versionJeu.setStyle("");
        versionJeu.getStyleClass().add("note");
        versionJeu.managedProperty().bind(versionJeu.visibleProperty());
        versionJeu.setVisible(false);
        etatInv = Ui.etat();

        appliquer = new Button("Appliquer au jeu");
        appliquer.setMaxWidth(Double.MAX_VALUE);
        appliquer.setOnAction(e -> appliquerAuJeu());
        revenir = new Button("Revenir à l'inventaire d'origine");
        revenir.setMaxWidth(Double.MAX_VALUE);
        revenir.setOnAction(e -> revenirOrigine());
        annulerAttente = new Button("Ne plus attendre la fermeture de Habbo");
        annulerAttente.setMaxWidth(Double.MAX_VALUE);
        annulerAttente.managedProperty().bind(annulerAttente.visibleProperty());
        annulerAttente.setVisible(false);
        annulerAttente.setOnAction(e -> attendre = false);

        Label aide = Ui.aide("Ces modifications changent le client du jeu (HabboAir.swf) : l'Atelier "
                + "le reconstruit avec les cases cochées, puis l'installe dans Habbo. Habbo doit être fermé "
                + "pendant l'installation ; la prochaine ouverture par le Launcher utilise le nouvel inventaire. "
                + "Faite pour la version " + ClientModifie.VERSION_PREVUE + " du client.");

        Salle.tache("inventaire-etat", this::lireEtatClient);
        return Ui.bloc("Client du jeu modifié", cases, appliquer, revenir, annulerAttente,
                voyantClient, optionsInstallees, versionJeu, etatInv, aide);
    }

    /** La recherche vit dans le menu Categorie : sans menus, pas de recherche. */
    private void majCases() {
        CheckBox cat = modifs.get("categories"), rech = modifs.get("recherche");
        if (cat != null && rech != null) rech.setDisable(!cat.isSelected());
    }

    /** Les modifications decochees, a passer a construire.py --sans. */
    private java.util.List<String> sans() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CheckBox> e : modifs.entrySet()) {
            boolean voulu = e.getValue().isSelected();
            if (e.getKey().equals("recherche") && !modifs.get("categories").isSelected()) voulu = false;
            if (!voulu) l.add(e.getKey());
        }
        return l;
    }

    private static String libelle(String nom) {
        for (String[] m : ClientModifie.MODIFS) if (m[0].equals(nom)) return m[1];
        return nom;
    }

    /** Client d'origine ou modifie, et quelles options sont installees (hors fil JavaFX). */
    private void lireEtatClient() {
        String av = ClientModifie.avertissementVersion();
        String installe = ClientModifie.empreinte(ClientModifie.swfInstalle());
        String origine = ClientModifie.empreinte(ClientModifie.swfOrigine());
        String niveau, raison, options = null;
        if (installe == null) {
            niveau = "absent"; raison = "Client du jeu introuvable (version " + ClientModifie.VERSION_PREVUE + ").";
        } else if (origine == null) {
            niveau = "absent"; raison = "Client d'origine introuvable, comparaison impossible.";
        } else if (installe.equals(origine)) {
            niveau = "attente"; raison = "D'origine (inventaire normal).";
        } else {
            niveau = "ok"; raison = "Modifié pour l'Atelier.";
            if (installe.equals(PREFS.get("inventaire.installe.sha", ""))) {
                java.util.List<String> avec = new java.util.ArrayList<>();
                java.util.Set<String> sansInst = new java.util.HashSet<>(
                        java.util.Arrays.asList(PREFS.get("inventaire.installe.sans", "").split(",")));
                for (String[] m : ClientModifie.MODIFS) if (!sansInst.contains(m[0])) avec.add(m[1]);
                options = avec.isEmpty() ? "Installé sans aucune modification de l'inventaire."
                        : "Installé avec : " + String.join(", ", avec) + ".";
            } else options = "Options installées inconnues (modifié en dehors de l'Atelier).";
        }
        System.out.println("[Atelier] Client du jeu : " + raison + (options != null ? " " + options : ""));
        final String n = niveau, r = raison, o = options;
        Platform.runLater(() -> {
            voyantClient.regler(n, r);
            optionsInstallees.setText(o == null ? "" : o);
            optionsInstallees.setVisible(o != null);
            versionJeu.setText(av == null ? "" : av);
            versionJeu.setVisible(av != null);
        });
    }

    private void appliquerAuJeu() {
        if (occupe) return;
        String manque = ClientModifie.manque();
        if (manque != null) { direInv(manque); return; }
        java.util.List<String> sans = sans();
        occuper(true);
        Salle.tache("inventaire-appliquer", () -> {
            try {
                direInv("Construction du client du jeu" + (sans.isEmpty() ? "" : " (sans : "
                        + String.join(", ", sans) + ")") + "…");
                ClientModifie.Resultat c = ClientModifie.construire(sans,
                        l -> direInv("Construction : " + l));
                if (!c.reussi() || !ClientModifie.swfConstruit().isFile()) {
                    direInv("Échec de la construction : " + c.derniereLigne());
                    return;
                }
                if (!attendreFermeture("Client construit, mais Habbo est ouvert. Ferme Habbo (Cmd+Q) : "
                        + "j'installe dès qu'il est fermé (garde l'Atelier ouvert).")) {
                    direInv("Installation annulée : Habbo est toujours ouvert. Ferme Habbo (Cmd+Q) puis "
                            + "clique de nouveau sur Appliquer.");
                    return;
                }
                direInv("Installation dans Habbo…");
                ClientModifie.Resultat i = ClientModifie.installer(l -> direInv("Installation : " + l));
                if (!i.reussi()) {
                    direInv("Échec de l'installation : " + i.derniereLigne());
                    return;
                }
                PREFS.put("inventaire.installe.sans", String.join(",", sans));
                String sha = ClientModifie.empreinte(ClientModifie.swfInstalle());
                if (sha != null) PREFS.put("inventaire.installe.sha", sha);
                direInv("Inventaire modifié installé. Lance Habbo par le Launcher, comme d'habitude.");
            } finally {
                occuper(false);
                lireEtatClient();
            }
        });
    }

    private void revenirOrigine() {
        if (occupe) return;
        String manque = ClientModifie.manque();
        if (manque != null) { direInv(manque); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "L'Atelier remet le client du jeu d'origine : l'inventaire redevient celui de Habbo, "
                        + "sans les boutons, menus ni pagination de l'Atelier.\n\nHabbo doit être fermé. Continuer ?",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Revenir à l'inventaire d'origine");
        try {
            if (fenetre != null) a.initOwner(fenetre);
            ((Stage) a.getDialogPane().getScene().getWindow()).setAlwaysOnTop(true);
        } catch (Throwable ignored) { }
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        occuper(true);
        Salle.tache("inventaire-restaurer", () -> {
            try {
                if (!attendreFermeture("Habbo est ouvert. Ferme Habbo (Cmd+Q) : je remets l'original dès "
                        + "qu'il est fermé (garde l'Atelier ouvert).")) {
                    direInv("Rien n'a changé : Habbo est toujours ouvert. Ferme Habbo (Cmd+Q) puis "
                            + "clique de nouveau sur Revenir.");
                    return;
                }
                direInv("Remise du client d'origine…");
                ClientModifie.Resultat r = ClientModifie.restaurer(l -> direInv(l));
                if (!r.reussi()) {
                    direInv("Échec de la remise du client d'origine : " + r.derniereLigne());
                    return;
                }
                PREFS.remove("inventaire.installe.sha");
                PREFS.remove("inventaire.installe.sans");
                direInv("Client d'origine rétabli. Lance Habbo par le Launcher, comme d'habitude.");
            } finally {
                occuper(false);
                lireEtatClient();
            }
        });
    }

    /**
     * Si Habbo est ouvert : le dit, puis attend sa fermeture (30 minutes au
     * plus, ou jusqu'au bouton d'annulation). Vrai si Habbo est ferme.
     */
    private boolean attendreFermeture(String message) {
        if (!ClientModifie.jeuOuvert()) return true;
        direInv(message);
        attendre = true;
        Platform.runLater(() -> annulerAttente.setVisible(true));
        try {
            long fin = System.currentTimeMillis() + 30 * 60_000L;
            while (attendre && System.currentTimeMillis() < fin) {
                Salle.sommeil(2000);
                if (!ClientModifie.jeuOuvert()) {
                    // Laisser au jeu le temps de lacher ses fichiers.
                    Salle.sommeil(2000);
                    return !ClientModifie.jeuOuvert();
                }
            }
            return false;
        } finally {
            attendre = false;
            Platform.runLater(() -> annulerAttente.setVisible(false));
        }
    }

    private void occuper(boolean oui) {
        occupe = oui;
        Platform.runLater(() -> {
            appliquer.setDisable(oui);
            revenir.setDisable(oui);
            for (CheckBox c : modifs.values()) c.setDisable(oui);
            if (!oui) majCases();
        });
    }

    private void direInv(String s) {
        String t = Ui.majuscule(s);
        System.out.println("[Atelier] Inventaire : " + t);
        Platform.runLater(() -> etatInv.setText(t));
    }

    // --------------------------------------------------------------- outils

    private interface Action { void faire(GPresets gp) throws Throwable; }

    private Button plein(String texte, Action a, String succes) {
        Button b = new Button(texte);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            GPresets gp = AtelierLauncher.gpresets();
            if (gp == null) { dire("G-Presets pas encore prêt."); return; }
            try { a.faire(gp); dire(succes); }
            catch (Throwable t) { dire("Erreur : " + t); }
        });
        return b;
    }

    private void dire(String s) {
        Platform.runLater(() -> etat.setText(s));
    }
}

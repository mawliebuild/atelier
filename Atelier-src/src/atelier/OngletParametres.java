package atelier;


import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fenetre Reglages : une barre de menu a gauche (Connexion, Fenetres, Envois,
 * Donnees), la partie choisie a droite. Connexion porte aussi l'etat du jeu
 * modifie (le message dit si les modifs de l'Atelier sont bien installees).
 * L'Atelier reste toujours devant le jeu ; les raccourcis sont dans
 * Documentation ; les modifs du jeu s'installent toutes, d'office, au lancement.
 */
public class OngletParametres {

    private final Stage fenetre;
    private Tab connexion;
    private Label etat;
    private Ui.Voyant voyantClient;
    private Label versionJeu;

    public OngletParametres(Stage fenetre) {
        this.fenetre = fenetre;
    }

    /** L'onglet de connexion, montre dans la partie Connexion. */
    public OngletParametres avecConnexion(Tab connexion) {
        this.connexion = connexion;
        return this;
    }

    public Tab construire() {
        etat = Ui.etat();

        // --- Connexion (+ etat du jeu modifie)
        voyantClient = new Ui.Voyant("Jeu");
        voyantClient.regler("attente", "Lecture…");
        versionJeu = Ui.discret("");
        versionJeu.getStyleClass().add("etat-ligne");
        versionJeu.managedProperty().bind(versionJeu.visibleProperty());
        versionJeu.setVisible(false);
        VBox partConnexion = new VBox(12);
        Node contenuConnexion = connexion == null ? null : connexion.getContent();
        if (contenuConnexion != null) { connexion.setContent(null); partConnexion.getChildren().add(contenuConnexion); }
        partConnexion.getChildren().add(Ui.bloc("Jeu modifié", voyantClient, versionJeu,
                Ui.aide("Les modifs du jeu (inventaire, grille, floor dans l'appart…) s'installent toutes "
                        + "seules au lancement de l'Atelier, Habbo fermé. Si ce n'est pas « Modifié pour l'Atelier », "
                        + "envoie ce message à celle qui t'a donné l'Atelier.")));
        Salle.tache("jeu-etat", this::lireEtatClient);

        // --- Fenetres
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
        VBox partFenetres = new VBox(12, Ui.bloc("Transparence quand la souris est sur le jeu", ligneFondu,
                Ui.aide("À 100 %, la fenêtre reste opaque. Plus bas, elle devient "
                        + "transparente quand ta souris est sur le jeu, pour voir les mobis derrière.")));

        // --- Envois
        VBox partEnvois = envois();

        // --- Donnees
        Button cacheBc = plein("Vider le cache BC", gp -> { if (gp.getCatalog() != null) gp.getCatalog().viderCache(); }, "Cache BC vidé.");
        Button cacheWired = plein("Vider le cache wired", gp -> WiredLecteur.viderCache(), "Cache wired vidé.");
        VBox partDonnees = new VBox(12, Ui.bloc("Caches", cacheBc, cacheWired,
                Ui.aide("Vider un cache force l'Atelier à relire le catalogue BC ou les réglages wired.")));

        Map<String, VBox> parties = new LinkedHashMap<>();
        parties.put("Connexion", partConnexion);
        parties.put("Fenêtres", partFenetres);
        parties.put("Envois", partEnvois);
        parties.put("Mise en valeur", ApercuSurlignage.section());
        parties.put("Données", partDonnees);
        parties.put("À propos", aPropos());

        // barre de menu a gauche
        ScrollPane droite = new ScrollPane();
        droite.setFitToWidth(true);
        droite.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(droite, Priority.ALWAYS);
        VBox menu = new VBox(4);
        menu.setPadding(new Insets(14, 10, 14, 14));
        menu.setMinWidth(150);
        menu.setPrefWidth(150);
        menu.setStyle("-fx-border-color: transparent #E2DFD6 transparent transparent; -fx-border-width: 0 1 0 0;");
        Map<String, String> icones = Map.of("Connexion", Icones.CONNEXION, "Fenêtres", Icones.FENETRES,
                "Envois", CHRONO, "Mise en valeur", Icones.ETINCELLE, "Données", Icones.DONNEES);
        ToggleGroup g = new ToggleGroup();
        for (Map.Entry<String, VBox> e : parties.entrySet()) {
            VBox p = e.getValue();
            p.setFillWidth(true);
            p.setPadding(new Insets(14, 18, 18, 14));
            ToggleButton b = new ToggleButton(e.getKey());
            b.setToggleGroup(g);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setFocusTraversable(false);
            String ic = icones.get(e.getKey());
            if (ic != null) {
                // trait blanc sur le bouton choisi (fond bleu), gris sinon
                javafx.scene.Node gris = Icones.petite(ic, 16, false), blanc = Icones.petite(ic, 16, true);
                b.setGraphic(gris);
                b.setGraphicTextGap(8);
                b.selectedProperty().addListener((o, x, sel) -> b.setGraphic(sel ? blanc : gris));
            }
            b.setOnAction(a -> { if (!b.isSelected()) b.setSelected(true); droite.setContent(new VBox(p, etat)); });
            menu.getChildren().add(b);
        }
        ((ToggleButton) menu.getChildren().get(0)).setSelected(true);
        for (Node n : menu.getChildren()) ((ToggleButton) n).setMinHeight(30);
        droite.setContent(new VBox(partConnexion, etat));

        HBox racine = new HBox(menu, droite);
        racine.setPrefHeight(480);           // assez pour le menu de gauche, sans prendre tout l'ecran
        Tab t = new Tab("Paramètres", racine);
        t.setClosable(false);
        return t;
    }

    /** Icone de la partie Envois : un chronometre. */
    private static final String CHRONO = "M12 6a7.5 7.5 0 1 0 0.01 0z M12 9.5v4l2.5 1.5 M9.5 2.5h5 M12 2.5V6";

    /**
     * Vitesse des envois : l'ecart entre deux envois d'une rafale (Salle.ecart),
     * 50..300 ms au pas de 10 ; une ligne discrete dit si le frein l'a remonte
     * a 150 ms (actions refusees meme apres reessai).
     */
    static VBox envois() {
        Slider vitesse = new Slider(Salle.ECART_MIN, Salle.ECART_MAX, Salle.ecartVoulu());
        vitesse.setMajorTickUnit(Salle.ECART_PAS);
        vitesse.setMinorTickCount(0);
        vitesse.setSnapToTicks(true);
        vitesse.setBlockIncrement(Salle.ECART_PAS);
        vitesse.setFocusTraversable(false);
        HBox.setHgrow(vitesse, Priority.ALWAYS);
        Label valeur = new Label(Salle.ecartVoulu() + " ms");
        valeur.setMinWidth(52);
        Label frein = Ui.discret("");
        frein.managedProperty().bind(frein.visibleProperty());
        Runnable majFrein = () -> {
            boolean f = Salle.freine();
            frein.setText(f ? "Ralenti à " + Salle.ecart() + " ms après des refus" : "");
            frein.setVisible(f);
        };
        majFrein.run();
        Salle.surChangementEcart(() -> Platform.runLater(majFrein));
        // enregistre au lacher (pas a chaque pas du glissement)
        vitesse.valueProperty().addListener((o, x, v) -> {
            valeur.setText(Salle.borner((int) Math.round(v.doubleValue())) + " ms");
            if (!vitesse.isValueChanging()) Salle.ecartVoulu((int) Math.round(v.doubleValue()));
        });
        vitesse.valueChangingProperty().addListener((o, x, enCours) -> {
            if (!enCours) Salle.ecartVoulu((int) Math.round(vitesse.getValue()));
        });
        Button defaut = new Button("Par défaut (" + Salle.ECART_DEFAUT + " ms)");
        Ui.bulle(defaut, "Remet l'écart conseillé entre deux envois.");
        defaut.setOnAction(e -> {
            vitesse.setValue(Salle.ECART_DEFAUT);
            Salle.ecartVoulu(Salle.ECART_DEFAUT);     // aussi si le curseur y etait deja : le frein repart de zero
        });
        HBox ligne = new HBox(8, vitesse, valeur);
        ligne.setAlignment(Pos.CENTER_LEFT);
        return new VBox(12, Ui.bloc("Vitesse des envois", ligne,
                Ui.discret("Plus bas = plus rapide, mais le jeu peut refuser des actions ou te déconnecter. "
                        + "Si le jeu refuse une action, l'Atelier la réessaie ; s'il refuse encore souvent, "
                        + "il ralentit à " + Salle.ECART_DEFAUT + " ms."),
                frein, defaut,
                Ui.aide("Temps entre deux envois d'une rafale (poses, déplacements, hauteurs…). "
                        + "Les envois volontairement plus lents (marché, plantes, catalogue) ne changent pas.")));
    }

    /**
     * Credits : l'Atelier repose, pour la connexion au jeu, sur G-Earth
     * (licence MIT : la mention du droit d'auteur doit accompagner le logiciel)
     * et, pour une partie de la pose, sur G-Presets.
     */
    private VBox aPropos() {
        Label atelier = Ui.aide("L'Atelier est un outil de construction pour Habbo : calques, floor dans l'appart, "
                + "copies d'apparts et de wired, patrimoine, galerie…");
        atelier.setWrapText(true);
        Label base = new Label("La connexion au jeu repose sur G-Earth, de sirjonasxx (licence MIT), "
                + "et une partie de la pose des mobis sur G-Presets, de sirjonasxx (modifié par TH et Zyker). "
                + "Merci à eux !");
        base.setWrapText(true);
        Label mit = Ui.discret("G-Earth : Copyright (c) sirjonasxx. Publié sous licence MIT : utilisation, "
                + "modification et distribution autorisées, à condition de garder cette mention.");
        mit.setWrapText(true);
        Label sources = new Label("Liste des messages du jeu : sulek.dev. Prix moyens des mobis : habbofurni.xyz.");
        sources.setWrapText(true);
        return new VBox(12,
                Ui.bloc("L'Atelier", atelier),
                Ui.bloc("Crédits", base,
                        Lien.lien("G-Earth sur GitHub", "https://github.com/sirjonasxx/G-Earth"), mit),
                Ui.bloc("Données", sources,
                        Ui.ligne(Lien.lien("sulek.dev", "https://sulek.dev"),
                                 Lien.lien("habbofurni.xyz", "https://habbofurni.xyz"))));
    }

    /** Le jeu installe contient-il les modifs de l'Atelier ? (hors fil JavaFX) */
    private void lireEtatClient() {
        String av = ClientModifie.avertissementVersion();
        String installe = ClientModifie.empreinte(ClientModifie.swfInstalle());
        String niveau, raison;
        // Le jeu est adapte automatiquement a chaque version (modifier-jeu.py) : on
        // regarde seulement si le client installe contient le code de l'Atelier.
        if (installe == null) {
            niveau = "absent"; raison = "Jeu introuvable : ouvre Habbo une fois par le Launcher, ferme-le, puis relance l'Atelier.";
        } else if (!ClientModifie.saitSurligner()) {
            niveau = "absent"; raison = "D'origine : les modifs ne sont pas installées. Ferme Habbo puis relance "
                    + "« Lancer l'Atelier » ; si ça reste ainsi, envoie le fichier Atelier-swf/modifier-jeu.log.";
        } else {
            niveau = "ok"; raison = "Modifié pour l'Atelier.";
        }
        Journal.debug("Jeu : " + raison);
        final String n = niveau, r = raison;
        Platform.runLater(() -> {
            voyantClient.regler(n, r);
            versionJeu.setText(av == null ? "" : av);
            versionJeu.setVisible(av != null);
        });
    }

    // --------------------------------------------------------------- outils

    private interface Action { void faire(Moteur gp) throws Throwable; }

    private Button plein(String texte, Action a, String succes) {
        Button b = new Button(texte);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            Moteur gp = AtelierLauncher.moteur();
            if (gp == null) { Journal.erreur("L'Atelier n'est pas encore prêt."); return; }
            try { a.faire(gp); Journal.succes(succes); }
            catch (Throwable t) { t.printStackTrace(); Journal.erreur("Échec : " + t.getMessage()); }
        });
        return b;
    }
}

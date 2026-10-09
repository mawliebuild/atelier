package atelier;

import gearth.GEarth;
import gearth.extensions.InternalExtensionFormLauncher;
import gearth.protocol.HConnection;
import gearth.services.extension_handler.ExtensionHandler;
import gearth.ui.GEarthController;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.lang.reflect.Field;

/**
 * L'Atelier : son proxy en francais, avec son moteur embarque (Moteur), presente comme
 * une barre d'outils et une fenetre posees sur le jeu.
 *
 * On herite de gearth.GEarth et on laisse son start() faire son travail
 * (chargement du FXML, barre de titre, theme), puis on remanie l'interface deja
 * construite : aucun bytecode d'origine n'est modifie.
 *
 * Les onglets retires ne sont pas detruits, seulement decroches du TabPane :
 * leurs controleurs restent vivants, car d'autres parties du proxy s'y
 * referent (ExtensionsController interroge par exemple ExtraController).
 */
public class AtelierLauncher extends GEarth {

    public static final String NOM = "Atelier";

    private static volatile Moteur moteur;

    /** Le moteur de l'Atelier vivant dans cette JVM, ou null s'il n'est pas encore pret. */
    public static Moteur moteur() { return moteur; }

    @Override
    public void start(Stage stage) throws Exception {
        // Le proxy est deja traduit et lit sa langue depuis son cache au demarrage.
        // On n'appelle setLanguage() que si elle n'est pas deja en francais : cet
        // appel parcourt toutes les chaines a rafraichir et leve une NPE quand la
        // fenetre du Extension Store n'est pas ouverte (bug du proxy).
        try {
            if (gearth.ui.translations.LanguageBundle.getLanguage()
                    != gearth.ui.translations.Language.FRENCH) {
                gearth.ui.translations.LanguageBundle.setLanguage(
                        gearth.ui.translations.Language.FRENCH);
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] francais non applique : " + t);
        }

        Ui.chargerPolices();
        super.start(stage);
        ResterVisible.demarrer();
        DallesTraversees.demarrer();
        MurauxCommeAvant.demarrer();
        EchapDeplacement.demarrer();  // Echap dans le jeu : le mobi pris (Option + clic) reste a sa place  // clics a travers les dalles magiques (etat des mobis poses dessus)     // Option + clic dans le jeu ne masque plus l'Atelier
        Platform.runLater(() -> {
            try {
                remanier(stage);
            } catch (Throwable t) {
                System.err.println("[Atelier] remaniement impossible : " + t);
                t.printStackTrace();
            }
        });
    }

    // ------------------------------------------------------------------ UI

    private void remanier(Stage stage) {
        Parent racine = stage.getScene().getRoot();

        TabPane onglets = (TabPane) chercher(racine, n -> n instanceof TabPane);
        if (onglets == null) {
            System.err.println("[Atelier] TabPane introuvable, interface laissee telle quelle.");
            return;
        }

        // Premier plan actif d'emblee : la case de Parametres lit cet etat a sa
        // construction, il doit donc etre pose avant elle.
        stage.setAlwaysOnTop(true);

        GEarthController ctrl = controleur();

        // 1. Ne garder que Connexion. Son libelle est LIE au systeme de traduction
        //    (TranslatableString) : un setText leve "A bound value cannot be set".
        Tab connexion = (ctrl != null) ? ctrl.tab_Connection : parTexte(onglets, "Connexion");
        if (connexion == null) connexion = parTexte(onglets, "Connection");
        onglets.getTabs().clear();
        if (connexion != null) {
            if (ctrl != null) OngletConnexion.reconstruire(connexion, ctrl.connectionController);
            onglets.getTabs().add(connexion);
        }

        // 2. Build et Apparts.
        Tab build = ongletProvisoire("Build",
                "Outils muraux",
                "Déplacer un mur au pixel, ou en aligner des copies.");
        onglets.getTabs().add(build);

        Tab apparts = ongletProvisoire("Apparts",
                "Chargement des apparts...",
                "Le moteur de l'Atelier démarre.");
        onglets.getTabs().add(apparts);

        // L'onglet Build porte nos deux outils muraux. L'ancien module d'outils de
        // construction a ete ECARTE : son jar embarquait une classe
        // de furnidata homonyme de celle de l'ancien module de pose, avec un constructeur different. Deux classes de
        // meme nom ne peuvent pas cohabiter dans un chargeur — d'ou le
        // NoSuchMethodError pendant connectionStart, et la deconnexion. Son
        // « Poster mover » est reecrit dans OutilDeplacer.
        OutilAligner aligner = new OutilAligner();
        build.setContent(Ui.sousMenu(
                new OutilDeplacer().construire(),
                aligner.construire(),
                aligner.construireGrille(),
                aligner.construireEcarts(),
                new OutilFauxMur().construire(),
                new OutilCouleur().construire()));

        onglets.getTabs().add(new OngletInventaire().construire());
        onglets.getTabs().add(new OngletWired().construire());
        // Reglages : la connexion y est une partie du menu de gauche
        onglets.getTabs().add(new OngletParametres(stage).avecConnexion(connexion).construire());

        brancherApparts(stage, apparts);

        // Le resume de la salle vit dans la barre d'outils (BarreOutils).
        Parent parent = onglets.getParent();

        // 4. Rendre l'interieur elastique : le FXML du proxy fige
        //    prefWidth/prefHeight sur le TabPane sans aucun VGrow, donc agrandir
        //    la fenetre laissait le contenu a sa taille d'origine.
        onglets.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (parent instanceof VBox) VBox.setVgrow(onglets, Priority.ALWAYS);
        if (parent instanceof Pane) {
            Pane p = (Pane) parent;
            p.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            if (p instanceof Region)
                ((Region) p).setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        }
        Parent grandParent = (parent == null) ? null : parent.getParent();
        if (grandParent instanceof VBox && parent != null)
            VBox.setVgrow(parent, Priority.ALWAYS);

        // 5. Nom de la fenetre.
        stage.setTitle(NOM);
        Node titre = chercher(racine, n -> n instanceof Label && "titleLabel".equals(n.getId()));
        if (titre instanceof Label) ((Label) titre).setText(NOM);

        // 6. L'Atelier se pose sur le jeu : une barre d'icones en haut au
        //    centre, et une fenetre facon Habbo a droite, sous la mission. A
        //    gauche, l'inventaire du jeu reste libre. La fenetre d'origine
        //    s'efface, elle n'a plus rien a montrer.
        String css = null;
        try {
            java.net.URL u = gearth.GEarth.class.getResource(
                    "/gearth/ui/themes/G-Earth/styling.css");
            if (u != null) css = u.toExternalForm();
        } catch (Throwable ignored) { }

        java.util.Map<String, Tab> parNom = new java.util.HashMap<>();
        for (Tab t : onglets.getTabs()) parNom.put(t.getText(), t);
        onglets.getTabs().clear();
        Tab tBuild = parNom.get("Build"), tApparts = parNom.get("Apparts"),
            tWired = parNom.get("Wired"), tInv = parNom.get("Inventaire"),
            tConnexion = connexion, tParam = parNom.get("Paramètres");

        // Les menus suivent le travail, pas l'origine du code :
        //   Construction  un mode : menu lateral en bas a droite (Construction,
        //                 Murs, Floor) et panneau des calques
        //   Murs       les outils qui agissent sur le mur selectionne
        //   Ma salle   ce qui agit sur la salle ouverte : boutons en bas a droite
        //   Apparts    poser un appart enregistre
        //   Wired, Inventaire
        //   Reglages   connexion et parametres, qu'on ne touche qu'au demarrage
        //   Construction  ce qui pose ou deplace des mobis en serie, et l'historique
        //   Floor         l'editeur visuel du plan de sol
        //   Plantes       les monster plants de la salle
        Tab tCalques = new OngletCalques().construire();
        Tab tConstruction = new OngletConstruction().construire();
        Tab tHistorique = new OutilHistorique().construire();
        Tab tAnalyse = new OngletAnalyseWired().construire();
        Tab tPlantes = new OngletPlantes().construire();

        // Le volet « Calques » quitte les menus : il vit dans son propre
        // panneau a gauche du jeu (PanneauCalques). On le retire de l'onglet
        // AVANT que la Navigation n'en extraie les autres volets.
        Node voletCalques = extraireVolet(tCalques, "Calques");

        Navigation nav = new Navigation();
        // Chaque volet annonce ce qu'il lui faut (bloc « Prérequis » en haut).
        Prerequis.Condition SALLE = Prerequis.Condition.SALLE, DROITS = Prerequis.Condition.DROITS,
                WIRED = Prerequis.Condition.DROITS_WIRED, NOMS = Prerequis.Condition.NOMS,
                INV = Prerequis.Condition.INVENTAIRE, BC = Prerequis.Condition.BC,
                MUR = Prerequis.Condition.MUR, COULEUR = Prerequis.Condition.COULEUR;
        // Murs : deux boutons dans le menu lateral, Deplacer et Aligner (ligne / grille).
        nav.ajouter("murs-deplacer", "Déplacer un mur", Icones.DEPLACER,
                Navigation.source(tBuild, "Déplacer un mur", "Déplacer").avec(SALLE, DROITS, MUR));
        nav.ajouter("murs-aligner", "Aligner des murs", Icones.ALIGNER,
                Navigation.source(tBuild, "Aligner", "En ligne").avec(SALLE, DROITS, MUR, INV, BC),
                Navigation.source(tBuild, "Grille", "En grille").avec(SALLE, DROITS, MUR, INV, BC),
                Navigation.source(tBuild, "Écarts", "Écarts par mobi").avec(NOMS));
        // « Ma salle » n'est plus dans la barre du haut : chacun de ses outils a
        // son bouton en bas a droite du jeu (BarreSalle), qui ouvre la fenetre.
        nav.ajouter("salle-mobis", "Salle", Icones.SALLE,
                Navigation.source(tApparts, "Ma salle", "Salle")).pleineHauteur();   // seul le tableau defile
        nav.ajouter("salle-couleur", "Couleur de décor", Icones.GOUTTE,
                Navigation.source(tBuild, "Couleur de décor").avec(SALLE, DROITS, COULEUR));
        Tab tHauteur = new OutilHauteur().construire();
        // « Construction » (barre du haut) bascule seulement le mode : pas de fenetre.
        nav.ajouter("construction", "Construction", Icones.CONSTRUCTION);
        nav.ajouter("hauteur-mobis", "Hauteur mobis", Icones.HAUTEUR,
                Navigation.source(tHauteur, null, "Hauteur fixe").avec(SALLE, DROITS, INV, BC));
        // Escalier : ouvert par « Composants » du panneau des calques
        // (le miroir, lui, est dans ses Actions). Aucun bouton dans les barres.
        nav.ajouter("composant-escalier", "Escalier", Icones.ESCALIER,
                Navigation.source(tConstruction, "Escalier", "Escalier").avec(SALLE, DROITS, NOMS, INV, BC));
        // Floor : plus de fenetre d'editeur ; le bouton Floor ouvre le mode Floor
        // (grille du jeu + palette a la place des calques, voir ModeCases / BarreFloor).
        nav.ajouter("apparts", "Apparts", Icones.APPARTS,
                Navigation.source(tApparts, "Dupliquer un appart"));
        Tab tCollageWired = new OngletCollageWired().construire();
        nav.ajouter("wired", "Wired", Icones.WIRED,
                Navigation.source(tWired, "Ordre", "Remettre en ordre").avec(SALLE, WIRED, NOMS),
                Navigation.source(tAnalyse, "Vérificateur", "Vérificateur de wired").avec(SALLE, WIRED, NOMS),
                Navigation.source(tCollageWired, null, "Copier / coller la config").avec(SALLE, WIRED, NOMS));
        nav.ajouter("plantes", "Monster Plants", Icones.PLANTES,
                Navigation.source(tPlantes, "Monster Plants")).pleineHauteur();   // seul le tableau defile
        Tab[] tPatrimoine = new OngletValeur().construireVolets();
        // Le filtrage de l'inventaire se fait dans le jeu (client modifie) : plus de
        // menu Inventaire. L'onglet reste construit (cache de l'inventaire, liaison
        // avec le jeu). La valeur des mobis passe dans « Patrimoine », en haut, ou
        // viendra ce qui touche aux credits et aux investissements.
        nav.ajouter("patrimoine", "Mobis", Icones.PATRIMOINE,
                // la recherche et l'estimation n'ont pas besoin de l'inventaire : prerequis separes
                Navigation.source(tPatrimoine[0], null, "Valeur de mes mobis").avec(NOMS, INV),
                Navigation.source(tPatrimoine[1], null, "Recherche de mobis").avec(NOMS),
                Navigation.source(tPatrimoine[2], null, "Estimation mobi").avec(NOMS),
                // boutique wired de la salle : « Vendre au troc » dans l'inventaire du jeu (TrocVente)
                Navigation.source(new ConfigTroc().construire(), null, "Config troc").avec(SALLE, NOMS));
        // Galerie : des photos d'apparts a garder a cote du jeu pendant qu'on construit.
        Tab tGalerie = new OngletGalerie(css).construire();
        nav.ajouter("galerie", "Galerie", Icones.GALERIE, Navigation.source(tGalerie)).pleineHauteur();
        // Documentation : toutes les commandes clavier et souris.
        Tab tDoc = new OngletDocumentation().construire();
        nav.ajouter("documentation", "Documentation", Icones.DOCUMENTATION, Navigation.source(tDoc)).pleineHauteur();
        java.util.List<Navigation.Source> reglages = new java.util.ArrayList<>();
        reglages.add(Navigation.source(tParam));
        nav.ajouter("reglages", "Réglages", Icones.REGLAGES,
                reglages.toArray(new Navigation.Source[0])).pleineHauteur();

        Fenetre fenetre = new Fenetre(css, nav.zone());
        BarreMesure barreMesure = new BarreMesure(css);
        PanneauCalques panneauCalques = new PanneauCalques(css,
                voletCalques != null ? voletCalques : Ui.colonne(Ui.discret("Calques indisponibles.")));
        // La regle est passee de la barre de mesure aux Actions des calques.
        panneauCalques.surMesure(barreMesure::basculerMesure);
        // Barre du haut : les menus, sauf « Ma salle » (icones en bas a droite)
        // Murs / Floor (menu lateral) et Wired (avec « Ma salle ») : tout cela
        // n'apparait qu'en mode Construction. Hors de ce mode, dans un appart,
        // seule la barre de mesure (photo, regle, cases) reste.
        // Ouvrir l'une de ces fenetres (ou la capture) ne fait pas sortir du mode.
        java.util.Set<String> lateral = java.util.Set.of("construction", "hauteur-mobis", "murs-deplacer", "murs-aligner", "floor",
                "salle-mobis", "wired", "salle-couleur", "salle-capture", "inventaire", "composant-escalier");
        java.util.List<Navigation.Menu> enHaut = new java.util.ArrayList<>();
        java.util.List<BarreIcones.Entree> enBas = new java.util.ArrayList<>();
        for (Navigation.Menu m : nav.menus()) {
            if (m.cle.equals("salle-capture")) continue;   // appareil photo de BarreMesure
            if (m.cle.startsWith("composant-")) continue;   // panneau des calques (Composants)
            if (m.cle.equals("hauteur-mobis")) continue;    // menu lateral
            if (m.cle.startsWith("salle-") || m.cle.equals("wired")) continue;
            else if (m.cle.startsWith("murs-") || m.cle.equals("floor")) continue;
            else if (m.cle.equals("inventaire")) continue;   // bouton de la barre du bas (BarreMesure)
            else enHaut.add(m);
        }
        // « Ma salle », de gauche a droite : Salle, Wired (a la place de
        // l'ancienne loupe), Couleur de decor.
        for (String cle : new String[0]) {   // plus rien : Wired et Couleur sont dans le menu lateral
            Navigation.Menu m = nav.menu(cle);
            if (m != null) enBas.add(new BarreIcones.Entree(m.cle, m.nom, m.icone));
        }
        // Menu lateral, de haut en bas : la fenetre Construction, Murs, Floor
        // (Floor juste au-dessus du nombre de cases).
        java.util.List<BarreIcones.Entree> enCote = new java.util.ArrayList<>();
        // puis Wired, Couleur de decor et Inventaire.
        for (String cle : new String[]{"hauteur-mobis", "murs-deplacer", "murs-aligner",
                "wired", "salle-couleur"}) {
            Navigation.Menu m = nav.menu(cle);
            if (m != null) enCote.add(new BarreIcones.Entree(m.cle, m.nom, m.icone));
        }
        BarreOutils barre = new BarreOutils(css, enHaut, enHaut.size() - 1);
        BarreIcones barreSalle = new BarreIcones(css, enBas, false);
        BarreIcones barreConstruction = new BarreIcones(css, enCote, true);
        barreSalle.contre(barreMesure.fenetre());
        barreConstruction.contre(barreMesure.fenetre());
        fenetre.eviter(barreMesure.fenetre());
        fenetre.eviter(barreSalle.fenetre());
        fenetre.eviter(barreConstruction.fenetre());

        // Mode Construction : le bouton du haut ne s'ouvre pas sur une fenetre,
        // il fait apparaitre le menu lateral et les calques. Ouvrir un autre
        // menu du haut en sort.
        final boolean[] construction = {false};
        barreConstruction.condition(() -> construction[0]);
        barreSalle.condition(() -> construction[0] && !enBas.isEmpty());   // vide : jamais affichee
        java.util.function.Consumer<Boolean> modeConstruction = on -> {
            construction[0] = on;
            barreConstruction.rafraichir();
            barreSalle.rafraichir();
            barre.construction(on);
            RaccourcisGlobaux.toucheP(on);
            if (!on && Groupes.modeSelection()) {
                Groupes.modeSelection(false);
                Groupes.viderSelection();
                InfoJeu.consigne("Mode calque arrêté, sélection vidée.");
            }
            panneauCalques.actif(on);
            if (!on) barreConstruction.actif(null);
        };

        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("atelier");
        java.util.function.Consumer<String> ouvrir = cle -> {
            if (!lateral.contains(cle) && construction[0]) modeConstruction.accept(false);
            nav.afficher(cle);
            fenetre.ouvrir();
            barre.actif(construction[0] ? "construction" : cle);
            barreSalle.actif(cle);
            barreConstruction.actif(cle);
            WiredLecteur.actif("wired".equals(cle));   // les wired ne se lisent qu'outil ouvert
            prefs.put("menu", cle);
        };
        panneauCalques.surEscalier(() -> ouvrir.accept("composant-escalier"));
        nav.surChangement(m -> {
            // Largeur par defaut de chaque menu, verifiee hors jeu pour que rien
            // ne soit coupe (0 = Fenetre.LARGEUR, 334). Le bord gauche de la
            // fenetre se tire pour l'elargir ; la largeur choisie est retenue
            // par menu (double-clic sur le bord : retour au defaut).
            //   Salle : tableau a 6 colonnes. Patrimoine : 6 colonnes (Source se
            //   masque sous 430). Plantes : rangee de filtres + 4 colonnes.
            //   Couleur de decor : un champ et une palette. Deplacer un mur :
            //   deux blocs de fleches. Reglages : 620 (a 760 le contenu flottait).
            double ecran = javafx.stage.Screen.getPrimary().getVisualBounds().getWidth();
            double l = switch (m.cle) {
                case "reglages" -> Math.min(620, ecran * 0.6);
                case "salle-mobis", "galerie", "documentation" -> 580;
                case "patrimoine" -> 480;
                case "plantes" -> 440;
                case "salle-couleur" -> Fenetre.LARGEUR - 50;
                case "murs-deplacer" -> Fenetre.LARGEUR - 80;
                default -> 0;
            };
            fenetre.largeur(m.cle, l);
            fenetre.montrer(m.nom, m.contenu(), m.info());
            MiseEnValeur.fenetre(m.cle);
        });
        java.util.function.Consumer<String> basculer = cle -> {
            Navigation.Menu m = nav.actif();
            if (fenetre.ouverte() && m != null && m.cle.equals(cle)) fenetre.fermer();
            else ouvrir.accept(cle);
        };
        // Changer le mode Construction (meme effet qu'un clic sur son bouton).
        java.util.function.Consumer<Boolean> changerConstruction = on -> {
            Navigation.Menu m = nav.actif();
            if (!on && fenetre.ouverte() && m != null && lateral.contains(m.cle)
                    && !java.util.Set.of("salle-capture", "salle-mobis", "floor").contains(m.cle))
                fenetre.fermer();   // la capture reste hors du mode
            modeConstruction.accept(on);
            barre.actif(on ? "construction"
                    : (fenetre.ouverte() && m != null ? m.cle : null));
        };
        // Mode Floor (bouton Floor de la barre du bas) : il sort du mode
        // Construction et sa palette remplace le panneau des calques. Le
        // quitter avec des changements en attente demande quoi en faire.
        BarreFloor barreFloor = new BarreFloor(css);
        barreFloor.surEtat(barreMesure::floorActif);
        Runnable entrerFloor = () -> {
            if (construction[0]) changerConstruction.accept(false);
            barreFloor.entrer();
        };
        BarreFloor.surDemande(entrerFloor);
        barre.surChoix(cle -> {
            if (!"construction".equals(cle)) { basculer.accept(cle); return; }
            if (barreFloor.actif()) { barreFloor.quitter(() -> changerConstruction.accept(true)); return; }
            changerConstruction.accept(!construction[0]);
        });
        barreSalle.surChoix(basculer);
        barreConstruction.surChoix(basculer);
        // L'appareil photo prend la photo tout de suite (fenetre agrandie), puis
        // ouvre l'apercu ; « Plus de reglages » ouvre la fenetre Capture.
        final String cssPhoto = css;
        barreMesure.surSalle(() -> basculer.accept("salle-mobis"));
        barreMesure.surFloor(() -> { if (barreFloor.actif()) barreFloor.quitter(null); else entrerFloor.run(); });
        // appareil photo : format (et reglages GIF) avant la photo, puis l'apercu
        barreMesure.surPhoto(() -> PhotoAppart.ouvrir(cssPhoto));
        barre.surEtat(() -> ouvrir.accept("salle-mobis"));
        fenetre.surFermeture(() -> {
            MiseEnValeur.fenetre(null);         // plus rien a mettre en valeur pour la fenetre
            WiredLecteur.actif(false);
            barre.actif(construction[0] ? "construction" : null);
            barreSalle.actif(null);
            barreConstruction.actif(null);
        });

        // Option + C (mode Construction) : mode calque, chaque clic sur un mobi le
        // selectionne. Option + C a nouveau : fin du mode, la selection est videe.
        Runnable basculerCalque = () -> {
            if (!construction[0]) return;
            boolean on = !Groupes.modeSelection();
            Groupes.modeSelection(on);
            if (!on) Groupes.viderSelection();
            InfoJeu.consigne(on ? "Mode calque : clique des mobis pour les sélectionner (Option + C pour arrêter)."
                                : "Mode calque arrêté, sélection vidée.");
        };
        RaccourcisGlobaux.surP(() -> Platform.runLater(basculerCalque));
        // Option + G (mode Construction) : la grille du jeu, affichee ou cachee.
        Runnable basculerGrille = () -> {
            if (!construction[0]) return;
            Salle.tache("grille-raccourci", () -> {
                boolean on = !GrilleVue.voulue();
                if (GrilleVue.montrer(on)) InfoJeu.dire(on ? "Grille affichée." : "Grille cachée.");
            });
        };
        RaccourcisGlobaux.surG(() -> Platform.runLater(basculerGrille));

        // Raccourci : Echap ferme la fenetre. Pas de touche pour ouvrir les menus.
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> touches = e -> {
            javafx.scene.Node f = ((javafx.scene.Scene) e.getSource()).getFocusOwner();
            if (f instanceof TextInputControl) return;
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { fenetre.fermer(); e.consume(); }
            else if (e.getCode() == javafx.scene.input.KeyCode.C && e.isAltDown() && !e.isShiftDown() && !e.isShortcutDown() && construction[0]) {
                basculerCalque.run(); e.consume();
            }
            else if (e.getCode() == javafx.scene.input.KeyCode.G && e.isAltDown() && !e.isShortcutDown() && construction[0]) {
                basculerGrille.run(); e.consume();
            }
        };
        fenetre.fenetre().getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, touches);
        barre.fenetre().getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, touches);
        barreSalle.fenetre().getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, touches);
        barreConstruction.fenetre().getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, touches);
        // Cmd/Ctrl+Z et Cmd/Ctrl+Maj+Z : annuler / retablir dans la salle.
        OutilHistorique.installerRaccourcis(fenetre.fenetre().getScene());
        OutilHistorique.installerRaccourcis(barre.fenetre().getScene());
        // Et dans le jeu lui-meme, quand Habbo est au premier plan et le chat vide.
        RaccourcisGlobaux.installer();

        stage.setOpacity(0);
        stage.setWidth(1);
        stage.setHeight(1);
        Ancrage.demarrer(barre, fenetre, panneauCalques, barreMesure, barreSalle, barreConstruction, barreFloor);

        // 7. A l'ouverture, seul l'ecran de connexion s'affiche, et se connecte
        //    de lui-meme. Une fois connectee : la barre seulement, aucun menu
        //    ouvert — c'est a toi de choisir. Les calques apparaissent a gauche
        //    des qu'on entre dans un appart. Tout repart si la connexion tombe.
        Runnable afficherAtelier = () -> {
            barre.montrer();
            barre.actif(null);
            barreSalle.visible(true);
            barreSalle.actif(null);
            barreConstruction.visible(true);
            barreMesure.actif(true);
            Ancrage.recoller();
        };
        Runnable masquerAtelier = () -> {
            FenetresVolantes.fermerToutes();      // photos de la galerie, apercu de capture
            fenetre.fenetre().hide();
            barre.fenetre().hide();
            barreSalle.visible(false);
            barreFloor.arreter();
            modeConstruction.accept(false);
            barreConstruction.visible(false);
            barreMesure.actif(false);
        };
        HConnection hc = connexionHabbo();
        if (hc == null) {
            Journal.info("connexion introuvable, l'Atelier s'affiche directement.");
            afficherAtelier.run();
        } else {
            new EcranConnexion(css, hc,
                    ctrl == null ? null : ctrl.connectionController.btnConnect,
                    afficherAtelier, masquerAtelier).demarrer();
        }
    }

    // -------------------------------------------------------- embarquements

    /**
     * Fait tourner le moteur de l'Atelier (Moteur) DANS la JVM de l'Atelier, via
     * le mecanisme que le proxy utilise pour ses propres extensions internes
     * (logger, store).
     *
     * ExtensionFormCreator.runExtensionForm est inutilisable : il appelle
     * Application.launch(), interdit une seconde fois dans la meme JVM.
     * InternalExtensionFormLauncher fait le meme travail sans JavaFX Application.
     */
    private void brancherApparts(Stage stage, Tab onglet) {
        ExtensionHandler handler = gestionnaireExtensions();
        if (handler == null) {
            Journal.erreur("Moteur de l'Atelier introuvable : le gestionnaire de modules de l'Atelier manque.");
            majOnglet(onglet, "Apparts indisponibles",
                    "Le gestionnaire de modules de l'Atelier n'a pas été trouvé.");
            return;
        }
        final Moteur.Createur creator = new Moteur.Createur();
        handler.addExtensionProducer(observer -> {
            try {
                Moteur gp = new InternalExtensionFormLauncher<Moteur.Createur, Moteur>()
                        .launch(creator, observer);
                if (gp == null) {
                    Journal.erreur("Échec du chargement du moteur de l'Atelier : rien n'a été rendu.");
                    majOnglet(onglet, "Échec du chargement",
                            "Le moteur de l'Atelier n'a pas démarré.");
                    return;
                }
                // les briques ecoutent des maintenant : la salle demandee a l'activation est vue
                gp.brancher();
                moteur = gp;
                Journal.info("Atelier démarré.");

                Platform.runLater(() -> onglet.setContent(new OngletApparts().construire()));
                ChargementAuto.demarrer();
                InventaireExport.demarrer();     // inventaire pour le generateur d'apparts
            } catch (Throwable t) {
                Journal.erreur("Chargement du moteur de l'Atelier impossible", t);
                majOnglet(onglet, "Échec du chargement", String.valueOf(t));
            }
        });
    }

    /** hConnection est prive dans GEarthController ; on le lit par reflexion. */
    static HConnection connexionHabbo() {
        try {
            GEarthController c = controleur();
            if (c == null) return null;
            Field f = GEarthController.class.getDeclaredField("hConnection");
            f.setAccessible(true);
            return (HConnection) f.get(c);
        } catch (Throwable t) {
            System.err.println("[Atelier] connexion introuvable : " + t);
            return null;
        }
    }

    private static ExtensionHandler gestionnaireExtensions() {
        HConnection h = connexionHabbo();
        return (h == null) ? null : h.getExtensionHandler();
    }

    private static void majOnglet(Tab onglet, String titre, String detail) {
        Platform.runLater(() -> onglet.setContent(contenuProvisoire(titre, detail)));
    }

    private static Tab ongletProvisoire(String nom, String titre, String detail) {
        Tab tab = new Tab(nom, contenuProvisoire(titre, detail));
        tab.setClosable(false);
        return tab;
    }

    private static VBox contenuProvisoire(String titre, String detail) {
        Label t = new Label(titre);
        t.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label d = new Label(detail);
        d.setWrapText(true);
        VBox contenu = new VBox(8, t, d);
        contenu.setPadding(new Insets(24));
        return contenu;
    }

    // -------------------------------------------------------------- helpers

    private static GEarthController controleur() {
        try {
            Field f = GEarth.class.getDeclaredField("controller");
            f.setAccessible(true);
            Object o = f.get(GEarth.main);
            return (o instanceof GEarthController) ? (GEarthController) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Retire un volet (par son nom) du TabPane interieur d'un onglet, et rend son contenu. */
    private static Node extraireVolet(Tab onglet, String nom) {
        Node c = (onglet == null) ? null : onglet.getContent();
        Node tp = (c == null) ? null : chercher(c, n -> n instanceof TabPane);
        if (!(tp instanceof TabPane)) return null;
        for (Tab v : new java.util.ArrayList<>(((TabPane) tp).getTabs())) {
            if (!nom.equals(v.getText())) continue;
            Node n = v.getContent();
            v.setContent(null);
            ((TabPane) tp).getTabs().remove(v);
            while (n instanceof ScrollPane && ((ScrollPane) n).getContent() != null) {
                Node d = ((ScrollPane) n).getContent();
                ((ScrollPane) n).setContent(null);
                n = d;
            }
            return n;
        }
        return null;
    }

    private static Tab parTexte(TabPane pane, String texte) {
        for (Tab t : pane.getTabs())
            if (texte.equalsIgnoreCase(t.getText())) return t;
        return null;
    }

    private interface Critere { boolean ok(Node n); }

    private static Node chercher(Node depart, Critere c) {
        if (depart == null) return null;
        if (c.ok(depart)) return depart;
        if (depart instanceof Parent) {
            for (Node enfant : ((Parent) depart).getChildrenUnmodifiable()) {
                Node r = chercher(enfant, c);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        // GEarth.main() appelle launch(args), qui deduit la classe de l'appelant
        // et lancerait GEarth au lieu de l'Atelier : on la designe explicitement.
        GEarth.args = args;
        Moteur.fusionnerCache();      // avant le proxy : il relit sa langue et ses hotels au demarrage
        GardeConnexion.sansCacheDns();
        GardeConnexion.assainir();
        RetourJeuOrigine.installer();      // sans l'Atelier, Habbo redevient le jeu normal
        Application.launch(AtelierLauncher.class, args);
    }
}

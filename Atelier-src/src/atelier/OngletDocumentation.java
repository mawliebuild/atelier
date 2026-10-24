package atelier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Documentation : toutes les commandes clavier (et souris) de l'Atelier,
 * rangees par endroit ou elles marchent, avec une recherche. Les touches
 * s'affichent a la facon du systeme (⌘ ⌥ ⇧ sur Mac, Ctrl Alt Maj sur Windows).
 */
public final class OngletDocumentation {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** Une commande : ses combinaisons (« | » = ou), ce qu'elle fait. */
    private record Commande(String touches, String quoi) { }

    private record Section(String titre, String ou, List<Commande> commandes) { }

    private final List<Section> sections = new ArrayList<>();
    private final VBox liste = new VBox(12);

    private Section section(String titre, String ou) {
        Section s = new Section(titre, ou, new ArrayList<>());
        sections.add(s);
        return s;
    }

    private static void c(Section s, String touches, String quoi) { s.commandes().add(new Commande(touches, quoi)); }

    private void remplir() {
        Section jeu = section("Dans le jeu", "Habbo au premier plan, barre de chat vide.");
        c(jeu, "CMD+Z", "Annuler la dernière action sur l'appart.");
        c(jeu, "CMD+SHIFT+Z | CMD+Y", "Rétablir l'action annulée.");
        c(jeu, "OPT+C", "Mode Construction : activer ou couper la sélection des calques (cliquer les mobis pour les choisir).");
        c(jeu, "OPT+G", "Mode Construction : afficher ou cacher la grille des cases.");
        // Windows : Ctrl + Maj (Alt + Maj change la langue du clavier)
        c(jeu, MAC ? "OPT+SHIFT+C" : "CMD+SHIFT+C", "Mode Construction, panneau des calques actif : copier le calque.");
        c(jeu, MAC ? "OPT+SHIFT+V" : "CMD+SHIFT+V", "Coller le calque copié (dans le même appart : à sa place puis Déplacer ; ailleurs : collage complet).");
        c(jeu, "Échap", "Pendant un déplacement lancé par " + touche("OPT") + "+clic : lâcher le mobi, il reste où il était.");
        c(jeu, "OPT+Clic", "Prendre ou déplacer un mobi (commande du jeu) ; sert aussi à choisir les mobis muraux de Déplacer un mural (fenêtre ouverte, chaque clic en ajoute un ou le retire).");
        c(jeu, "Clic", "Sur une dalle magique : la sélectionner (comme dans le jeu normal).");
        c(jeu, "Double-clic", "Sur un mobi posé sur une dalle magique : changer son état (le double-clic traverse la dalle).");
        c(jeu, "Clic", "Inventaire du jeu, « Vendre au troc » : quantité et prix (Entrée vend, Échap annule), puis un clic sur une case du jeu y montre la vitrine en aperçu ; un autre clic la déplace.");
        c(jeu, "↑ | → | ↓ | ←", "Fenêtre Vitrine ouverte : déplacer l'aperçu de la vitrine d'une case (Maj : 5 cases). Aussi dans le jeu (Habbo au premier plan, chat vide).");
        c(jeu, "Entrée | Échap", "Fenêtre Vitrine ouverte : Entrée pose la vitrine (avec la rotation choisie par Tourner), Échap annule la vente. Aussi dans le jeu.");

        Section chat = section("Commandes du chat", "À taper dans le chat du jeu : le message n'est pas envoyé.");
        c(chat, ":h 2,5", "Hauteur fixe des dalles magiques (de 0 à 40, virgule ou point).");
        c(chat, ":h", "Rappelle la hauteur fixe actuelle.");

        Section fen = section("Fenêtres de l'Atelier", "Quand une fenêtre de l'Atelier est active (pas pendant la saisie d'un texte).");
        c(fen, "Échap", "Fermer la fenêtre ouverte (outil, calque, options, aperçu de capture).");
        c(fen, "CMD+Z", "Annuler.");
        c(fen, "CMD+SHIFT+Z | CMD+Y", "Rétablir.");
        c(fen, "OPT+C", "Mode Construction : sélection des calques.");
        c(fen, "OPT+G", "Mode Construction : grille des cases.");
        c(fen, "CMD+C | CMD+V", "Panneau des calques actif : copier / coller le calque.");
        c(fen, "Double-clic", "Sur la barre de titre : réduire la fenêtre.");

        Section menus = section("Barre du haut", "Les rubriques en haut du jeu (Valeur, Troc, Réglages…).");
        c(menus, "Survol", "Sur une rubrique : une liste se déroule dessous, sans clic, avec ses sous-rubriques (ou seulement son nom si elle n'en a pas). Glisser d'une rubrique à l'autre change de liste ; elle se ferme quand la souris quitte la rubrique et la liste.");
        c(menus, "Clic", "Sur une sous-rubrique de la liste : ouvrir la fenêtre sur cette partie seule (titre « Rubrique › Sous-rubrique »).");
        c(menus, "Clic", "Sur la rubrique elle-même : ouvrir ou fermer sa fenêtre entière, comme avant.");
        c(menus, "Échap", "Fermer la liste déroulée (aussi dans le jeu).");

        Section liens = section("Liens d'apparts", "Apparts › Liens d'apparts : les apparts retenus, rangés par rubrique.");
        c(liens, "Double-clic", "Sur un appart : y aller (comme « Y aller »).");
        c(liens, "Glisser", "Un appart sur le nom d'une rubrique : l'y ranger, à la fin. Sur un autre appart : le placer juste avant ou après lui (un trait bleu montre où), dans la même rubrique ou une autre.");
        c(liens, "Clic droit", "Sur un appart : y aller, le renommer, le ranger, copier son lien, le retirer (« Annuler » le remet pendant quelques secondes).");
        c(liens, "Clic droit", "Sur le nom d'une rubrique : la renommer, la monter, la descendre ou la retirer (« Annuler » la remet).");
        c(liens, "Clic", "Sur le nom d'une rubrique : la replier ou la déplier (retenu d'une fois sur l'autre).");
        c(liens, "Entrée", "Dans « Numéro ou lien » (lien « Ajouter par numéro ou lien… ») : l'ajouter.");

        Section calques = section("Panneau des calques", "Liste des calques, à gauche du jeu.");
        c(calques, MAC ? "Suppr | Retour arrière" : "Suppr", "Supprimer les calques choisis et ramasser leurs mobis (" + touche("CMD") + "+Z les remet).");
        c(calques, "CMD+E", "Fusionner les calques choisis.");
        c(calques, "Double-clic", "Sur un nom : le renommer (Entrée valide, Échap annule).");
        c(calques, "CMD+Clic | SHIFT+Clic", "Choisir plusieurs calques.");
        c(calques, "Clic droit", "Choisir ce calque seul et ouvrir son menu.");
        c(calques, "Glisser", "Changer l'ordre des calques.");
        c(calques, "Double-clic", "Sur la barre du panneau : le replier ou le déplier.");
        c(calques, "Entrée", "Dans le filtre par nom : transformer le texte en étiquette.");
        c(calques, "Clic", "Remplir une zone, après le choix de la zone : un clic sur un mobi de l'appart le prend comme modèle (au lieu de le poser depuis l'inventaire).");
        c(calques, "Clic", "Remplacer dans une zone, bouton Pipette : un clic sur un mobi de la zone choisit le type à remplacer.");
        c(calques, "Clic", "Rangée Masquer, bouton Sol : cacher le sol de tout l'appart chez toi seulement ; les mobis restent visibles et les clics passent aux cases derrière (re-clique pour le remontrer, il revient aussi en changeant d'appart).");
        c(calques, "Clic", "Escalier, bouton Pipette : un clic sur un petit bloc de l'appart choisit sa couleur (re-clique le bouton pour annuler).");
        c(calques, "↑ | → | ↓ | ←", "Fenêtre Déplacer, Dupliquer ou Coller ouverte : déplacer l'aperçu d'une case, comme un clic sur la flèche (↑ = ↗, → = ↘, ↓ = ↙, ← = ↖). Aussi dans le jeu (Habbo au premier plan, chat vide).");
        c(calques, "SHIFT+Flèche", "Même chose, de 5 cases d'un coup.");
        c(calques, "↑ | → | ↓ | ←", "Fenêtre Photo de référence ouverte : déplacer la photo d'un pixel (Maj : 10 pixels). Aussi avec les boutons flèches de la fenêtre (Maj-clic : 10 pixels), et dans le jeu (Habbo au premier plan, chat vide).");
        c(calques, "Entrée", "Fenêtre Déplacer, Dupliquer ou Coller ouverte : Confirmer ou Poser (aussi dans le jeu, chat vide).");
        c(calques, "Échap", "Fenêtre Déplacer, Dupliquer ou Coller ouverte : Annuler (aussi dans le jeu, chat vide, sauf en mode Floor).");

        Section floor = section("Floor dans l'appart", "Bouton Floor (barre du bas) : la grille montre le floor prévu, cases fantômes autour.");
        c(floor, "Clic", "Dans l'appart : appliquer l'outil choisi à la case (même dans le vide, sur une case fantôme).");
        c(floor, "Clic | Clic", "Outil Rectangle : deux clics pour les deux coins.");
        c(floor, "Clic", "Outil Porte : place la porte ; un clic sur la porte la tourne.");
        c(floor, "Entrée", "Fenêtre Floor active : Appliquer (l'appart se recharge une fois).");
        c(floor, "Échap", "Fenêtre Floor active : fermer et quitter l'édition.");

        Section modeFloor = section("Mode Floor", WindowsClavier.texte("Pendant le mode Floor, dans le jeu (Habbo au premier plan, barre de chat vide)."));
        c(modeFloor, "1 | 2 | 3 | 4 | 5 | 6 | 7 | 8", WindowsClavier.texte("Choisir l'outil, dans l'ordre de la palette (Auto, Monter, Descendre, Fixer, Ajouter, Supprimer, Porte, Pipette). Rangée des chiffres, sans Maj (en AZERTY : & é \" ' ( - è _)."));
        c(modeFloor, "Échap", WindowsClavier.texte("Quitter le mode Floor (question s'il reste des changements). Avec un premier coin de rectangle en attente : l'oublier d'abord."));
        c(modeFloor, "CMD+Z", WindowsClavier.texte("Annuler le dernier changement du floor (pas des mobis)."));
        c(modeFloor, "CMD+SHIFT+Z | CMD+Y", WindowsClavier.texte("Rétablir le changement du floor annulé."));

        // Raccourcis choisis par l'utilisatrice : rien de fixe a montrer, on dit ou les choisir.
        Section zone = section("Visibilité zone", "Mode Construction, menu de droite, icône Visibilité zone (une dalle sous un œil).");
        c(zone, "Clic | Clic", "« Nouvelle zone » : cliquer les deux coins dans le jeu (le clic vise la case, même derrière un mobi). La grille s'affiche pendant le choix.");
        c(zone, "Clic", "« Masquer » / « Afficher » sur une zone : la cacher (sol et mobis cachés chez toi seulement, les clics passent aux cases derrière) ou la rendre visible. « Tout masquer » et « Tout afficher » agissent sur toutes les zones. En revenant dans l'appart, toutes les zones sont de nouveau visibles.");
        c(zone, "Double-clic", "Sur le nom d'une zone (ou son crayon) : le renommer ; Entrée valide.");
        c(zone, "Survol", "Sur la carte d'une zone : ses mobis sont mis en valeur dans le jeu et son rectangle est dessiné en bleu au sol (même masquée). La souris sortie, tout s'efface.");
        c(zone, "Clic | Clic", "Sur la carte d'une zone, hors des boutons et du nom : la garder en valeur (bord bleu), puis l'enlever. Le survol d'une autre carte la remplace le temps du survol.");
        c(zone, "Clic | Clic", "Corbeille d'une zone : un premier clic l'arme (rouge), un second dans les 3 secondes la supprime.");
        c(zone, "Ton raccourci", ("Dans le jeu (Habbo au premier plan, barre de chat vide) : masquer ou rendre visible la zone. "
                + "Ces raccourcis n'existent pas d'origine : c'est toi qui les choisis, zone par zone, avec le bouton « Raccourci » de la zone, "
                + "puis l'appui voulu (lettre ou chiffre avec " + (MAC ? "⌘, ⌃ ou ⌥" : "Ctrl ou Alt") + ", ou F1 à F12). Ils sont propres à toi et à l'appart de la zone ; "
                + "un raccourci déjà pris par l'Atelier ou par une autre zone est refusé."));
        c(zone, "Échap | Retour arrière", "Pendant le choix du raccourci : Échap annule, Retour arrière retire le raccourci de la zone.");

        Section plan = section("Plan de l'appart", "Plan de l'appart et mesure.");
        c(plan, "Molette", "Zoomer.");
        c(plan, "Glisser", "Déplacer le plan.");
        c(plan, "SHIFT+Glisser", "Choisir un rectangle comme zone de travail.");

        Section wired = section("Wired", "Graphe et vérificateur.");
        c(wired, "Molette", "Graphe : zoomer.");
        c(wired, "Glisser", "Graphe : déplacer la vue.");
        c(wired, "Clic", "Graphe : choisir une pile.");
        c(wired, "Double-clic", "Graphe, dans le vide : recadrer la vue.");
        c(wired, "Double-clic", "Vérificateur, sur un problème ou un wired : sa case devient la zone de travail.");

        Section galerie = section("Galerie", "Fenêtre Galerie et visionneuse de photos.");
        c(galerie, "Molette | Pincer", "Zoomer dans la photo.");
        c(galerie, "Glisser", "Se déplacer dans la photo.");
        c(galerie, "Double-clic", "Passer de « ajustée » à 100 %.");
        c(galerie, "Clic droit", "Ouvrir, taguer, déplacer, supprimer une photo, l'enregistrer sur l'ordi, ou l'utiliser comme photo de référence sur l'appart.");
        c(galerie, "Glisser", "Une photo sur une autre : changer l'ordre ; des images depuis " + (MAC ? "le Finder" : "l'Explorateur") + " : les ajouter.");
        // Mac : Cmd+Ctrl+Maj+4 copie une zone ; Windows : Win+Maj+S (Outil Capture d'écran) aussi.
        c(galerie, MAC ? "CMD+CTRL+SHIFT+4" : "Win+SHIFT+S", "Capture d'écran copiée, puis « Coller une image » pour l'ajouter.");
        c(galerie, "Double-clic", "Sur la barre de la visionneuse : la replier.");
        // Entree et Echap : memes touches sur Mac (Retour) et Windows ; actives seulement dans la visionneuse
        c(galerie, "Glisser | Entrée | Échap", "Visionneuse, bouton Recadrer : tracer la zone à garder, la déplacer ou la redimensionner par ses coins et ses bords ; Entrée valide, Échap annule. « Annuler le recadrage » remet l'original tant que la visionneuse reste ouverte.");
        c(galerie, "Glisser | Entrée | Échap", "Aperçu après une photo de l'appart, bouton Recadrer : tracer la zone à garder (un GIF est recadré en entier) ; Entrée valide, Échap annule.");
        c(galerie, "Clic | Entrée", "Sur un dossier : l'ouvrir. Le chemin en haut (Galerie › …) permet de remonter.");
        c(galerie, "F2", "Sur un dossier : le renommer.");
        c(galerie, "Suppr", "Sur un dossier vide : le supprimer (un dossier avec des photos n'est jamais supprimé).");
        c(galerie, "Glisser", "Une photo sur un dossier, ou sur le chemin en haut : l'y ranger (ses tags la suivent).");

        // Clic, clic droit, glisser, Entree : memes gestes sur Mac (Retour, clic droit = CTRL+clic) et Windows
        Section copies = section("Mes copies", "Apparts, onglet « Dupliquer un appart », liste des copies.");
        c(copies, "Clic | Double-clic | Entrée", "Sur un dossier : l'ouvrir. Le chemin en haut (Mes copies › …) permet de remonter.");
        c(copies, "Clic droit", "Sur un dossier : l'ouvrir, le renommer, le supprimer s'il est vide (« Apparts » et « Zones » sont fixes).");
        c(copies, "Clic droit", "Sur une copie : la renommer, la déplacer vers un dossier, la supprimer.");
        c(copies, "Glisser", "Une copie sur un dossier, ou sur le chemin en haut : l'y ranger (son aperçu la suit). Une zone reste dans « Zones », un appart dans « Apparts ».");
        c(copies, "Clic", "Sur un aperçu : l'agrandir.");

        // Clics seulement : memes gestes sur Mac et Windows
        Section taches = section("Tâches quotidiennes", "Icône calendrier de la barre du haut : les 3 tâches du jour (Missions > Tâches quotidiennes).");
        c(taches, "Clic", "« Faire les tâches » : l'Atelier fait celles qui ne coûtent rien (souvent chez Ness, :roomid 45273021), réclame les récompenses ; tu restes là où la dernière tâche t'a menée. L'avancement s'affiche dans le jeu.");
        c(taches, "Clic", "« Arrêter » : interrompt les tâches en cours.");
        c(taches, "Clic", "« Actualiser » : redemande la liste du jour au jeu.");
        c(taches, "Clic", "Les tâches qui font dépenser (crédits, duckets, diamants, effet à acheter) ne sont jamais faites : « Impossible (dépense) ». Celles marquées « À faire soi-même » montrent leur consigne ; quand tu en fais une, le mobi double-cliqué est retenu pour la prochaine fois.");

        Section barres = section("Barres","Barre du haut et barres en bas à droite du jeu.");
        c(barres, "Glisser", "Déplacer la barre (un simple clic garde son effet habituel).");
        c(barres, "Clic droit", "Remettre la barre à sa place.");
        c(barres, "Clic", "Sur la petite flèche : réduire ou déplier la barre.");

        Section autres = section("Autres", "Champs de saisie.");
        c(autres, "Double-clic", "Valeur › Valeur de mes mobis : sur un prix, le modifier (Entrée valide, Échap annule, vide = prix automatique).");
        c(autres, "Entrée", "Couleur de décor : appliquer le code couleur tapé.");
        c(autres, "Clic droit", "Valeur › Mes dépenses : sur un mouvement, le retirer de la liste.");
    }

    /** Nom d'une touche a la facon du systeme. */
    private static String touche(String t) {
        switch (t) {
            case "CMD":   return MAC ? "⌘" : "Ctrl";
            case "OPT":   return MAC ? "⌥" : "Alt";
            case "SHIFT": return MAC ? "⇧" : "Maj";
            case "CTRL":  return MAC ? "⌃" : "Ctrl";
            default:      return t;
        }
    }

    public Tab construire() {
        remplir();
        TextField recherche = new TextField();
        recherche.setPromptText("Rechercher une commande…");
        recherche.textProperty().addListener((o, a, b) -> afficher(b));
        afficher("");

        VBox v = new VBox(12,
                Ui.bloc("Commandes clavier", recherche,
                        Ui.aide("Toutes les commandes de l'Atelier, rangées par endroit. Les raccourcis du jeu ne "
                                + "marchent que quand Habbo est au premier plan et que la barre de chat est vide ; "
                                + "sinon les touches vont au jeu normalement.")),
                liste);
        v.setPadding(new Insets(12, 14, 14, 14));
        v.setFillWidth(true);
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Documentation", sp);
        t.setClosable(false);
        return t;
    }

    private void afficher(String filtre) {
        String f = filtre == null ? "" : filtre.trim().toLowerCase(Locale.ROOT);
        liste.getChildren().clear();
        for (Section s : sections) {
            boolean toute = f.isEmpty() || s.titre().toLowerCase(Locale.ROOT).contains(f);
            List<Node> lignes = new ArrayList<>();
            for (Commande k : s.commandes()) {
                if (toute || k.quoi().toLowerCase(Locale.ROOT).contains(f)
                        || lisible(k.touches()).toLowerCase(Locale.ROOT).contains(f)) lignes.add(ligne(k));
            }
            if (lignes.isEmpty()) continue;
            Label ou = Ui.discret(s.ou());
            ou.setWrapText(true);
            lignes.add(0, ou);
            liste.getChildren().add(Ui.bloc(s.titre(), lignes.toArray(new Node[0])));
        }
        if (liste.getChildren().isEmpty()) liste.getChildren().add(Ui.discret("Aucune commande ne correspond."));
    }

    private static String lisible(String touches) {
        StringBuilder b = new StringBuilder();
        for (String alt : touches.split("\\s*\\|\\s*")) {
            if (b.length() > 0) b.append(" ou ");
            String[] p = alt.split("\\+");
            for (int i = 0; i < p.length; i++) b.append(i > 0 ? " " : "").append(touche(p[i]));
        }
        return b.toString();
    }

    private static Node ligne(Commande k) {
        // une combinaison longue passe a la ligne dans sa colonne, sans deborder sur le texte
        javafx.scene.layout.FlowPane touches = new javafx.scene.layout.FlowPane(4, 3);
        touches.setAlignment(Pos.CENTER_LEFT);
        touches.setPrefWrapLength(190);
        String[] alts = k.touches().split("\\s*\\|\\s*");
        for (int a = 0; a < alts.length; a++) {
            if (a > 0) touches.getChildren().add(petit("ou"));
            String[] p = alts[a].split("\\+");
            for (int i = 0; i < p.length; i++) {
                if (i > 0) touches.getChildren().add(petit("+"));
                touches.getChildren().add(capuchon(touche(p[i])));
            }
        }
        touches.setMinWidth(190);
        touches.setPrefWidth(190);
        Label quoi = new Label(k.quoi());
        quoi.setWrapText(true);
        quoi.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(quoi, Priority.ALWAYS);
        HBox h = new HBox(10, touches, quoi);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(2, 0, 2, 0));
        return h;
    }

    /** Une touche dessinee comme un capuchon de clavier. */
    private static Label capuchon(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-background-color: #F4F2EC; -fx-border-color: #C9C4B8; -fx-border-width: 1 1 2 1;"
                + " -fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 1 6 1 6;"
                + " -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #4A463E;");
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    private static Label petit(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 10px; -fx-text-fill: #7C776C;");
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }
}

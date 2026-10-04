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
        c(jeu, "OPT+Clic", "Prendre ou déplacer un mobi (commande du jeu) ; sert aussi à choisir un mobi mural pour Déplacer un mur.");

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

        Section calques = section("Panneau des calques", "Liste des calques, à gauche du jeu.");
        c(calques, MAC ? "Suppr | Retour arrière" : "Suppr", "Supprimer les calques choisis et ramasser leurs mobis (" + touche("CMD") + "+Z les remet).");
        c(calques, "CMD+E", "Fusionner les calques choisis.");
        c(calques, "Double-clic", "Sur un nom : le renommer (Entrée valide, Échap annule).");
        c(calques, "CMD+Clic | SHIFT+Clic", "Choisir plusieurs calques.");
        c(calques, "Clic droit", "Choisir ce calque seul et ouvrir son menu.");
        c(calques, "Glisser", "Changer l'ordre des calques.");
        c(calques, "Double-clic", "Sur la barre du panneau : le replier ou le déplier.");
        c(calques, "Entrée", "Dans le filtre par nom : transformer le texte en étiquette.");

        Section floor = section("Floor dans l'appart", "Bouton Floor (barre du bas) : la grille montre le floor prévu, cases fantômes autour.");
        c(floor, "Clic", "Dans l'appart : appliquer l'outil choisi à la case (même dans le vide, sur une case fantôme).");
        c(floor, "Clic | Clic", "Outil Rectangle : deux clics pour les deux coins.");
        c(floor, "Clic", "Outil Porte : place la porte ; un clic sur la porte la tourne.");
        c(floor, "Entrée", "Fenêtre Floor active : Appliquer (l'appart se recharge une fois).");
        c(floor, "Échap", "Fenêtre Floor active : fermer et quitter l'édition.");

        Section plan = section("Plan de l'appart", "Plan de la salle et mesure.");
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
        c(galerie, "Clic droit", "Ouvrir, renommer ou supprimer une photo.");
        c(galerie, "Glisser", "Une photo sur une autre : changer l'ordre ; des images depuis " + (MAC ? "le Finder" : "l'Explorateur") + " : les ajouter.");
        // Mac : Cmd+Ctrl+Maj+4 copie une zone ; Windows : Win+Maj+S (Outil Capture d'écran) aussi.
        c(galerie, MAC ? "CMD+CTRL+SHIFT+4" : "Win+SHIFT+S", "Capture d'écran copiée, puis « Coller une image » pour l'ajouter.");
        c(galerie, "Double-clic", "Sur la barre de la visionneuse : la replier.");

        Section barres = section("Barres", "Barre du haut et barres en bas à droite du jeu.");
        c(barres, "Glisser", "Déplacer la barre (un simple clic garde son effet habituel).");
        c(barres, "Clic droit", "Remettre la barre à sa place.");
        c(barres, "Clic", "Sur la petite flèche : réduire ou déplier la barre.");

        Section autres = section("Autres", "Champs de saisie.");
        c(autres, "Double-clic", "Patrimoine : sur un prix, le modifier (Entrée valide, Échap annule, vide = prix automatique).");
        c(autres, "Entrée", "Couleur de décor : appliquer le code couleur tapé.");
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
        HBox touches = new HBox(4);
        touches.setAlignment(Pos.CENTER_LEFT);
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

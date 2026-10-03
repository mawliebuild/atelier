package atelier;

import gearth.extensions.parsers.HFloorItem;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Les actions des calques qui demandent des fleches ou une saisie, chacune
 * dans sa petite fenetre facon Habbo (CalqueFenetre), a droite du panneau :
 * deplacer (fleches, pivot, fantomes), dupliquer (options), hauteur, miroir,
 * pivoter, confirmer une suppression, coller. Une seule a la fois : en ouvrir
 * une ferme la precedente (et son apercu).
 *
 * Les fenetres montrent ce qui va se passer et la progression (avec
 * Arreter) ; le RESULTAT d'une action part dans le jeu (InfoJeu.dire).
 * Fil JavaFX.
 */
final class CalqueActions {

    private final String css;
    private final Window panneau;
    /** Choisir un calque dans la liste du panneau (cree a l'instant). */
    private final Consumer<String> choisir;
    private CalqueFenetre courante;
    private volatile Groupes.Tache tache;
    private Generateur.Source source = Generateur.Source.INVENTAIRE;

    CalqueActions(String css, Window panneau, Consumer<String> choisir) {
        this.css = css;
        this.panneau = panneau;
        this.choisir = choisir;
    }

    /** Une action tourne (dans une fenetre ou ailleurs). */
    boolean occupe() {
        Groupes.Tache t = tache;
        return Groupes.occupe() || (t != null && t.enCours());
    }

    /** Ferme la fenetre ouverte (changement de salle, panneau cache). */
    void fermer() {
        CalqueFenetre f = courante;
        if (f != null) f.fermer();
        courante = null;
    }

    private CalqueFenetre ouvrir(String titre) {
        fermer();
        CalqueFenetre f = new CalqueFenetre(css, titre, panneau);
        courante = f;
        return f;
    }

    /** Resultat d'une action : dans le jeu (et la console). */
    static void resultat(String s) {
        if (s == null || s.isBlank()) return;
        InfoJeu.dire(Ui.majuscule(s));
    }

    /** Refus immediat (verrou, action en cours...) : dans le jeu tout de suite. */
    static void refus(String s) {
        if (s == null || s.isBlank()) return;
        InfoJeu.consigne(Ui.majuscule(s));
    }

    // =============================================================== deplacer

    /**
     * Deplacer (et pivoter) un calque : fleches seules (dans le jeu, x va vers
     * le bas-droite et y vers le bas-gauche), fantomes a l'arrivee, Confirmer.
     * apresCopie : la copie vient d'etre posee sur l'original.
     */
    void deplacer(Groupes.Info i, boolean apresCopie) {
        String v = Groupes.refusVerrou(i.id);
        if (v != null) { refus(v); return; }
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir(apresCopie ? "Déplacer la copie" : "Déplacer « " + i.nom + " »");
        int[] d = {0, 0, 0};                        // dx, dy, quarts
        Label quoi = new Label();
        quoi.getStyleClass().add("calques-valeur");
        Button confirmer = CalqueFenetre.bouton("Confirmer", true, () -> { });
        Runnable maj = () -> {
            Groupes.Info k = Groupes.info(i.id);
            if (k == null) { f.fermer(); return; }
            quoi.setText(decalage(d[0], d[1], d[2]));
            if (d[0] == 0 && d[1] == 0 && d[2] == 0) {
                Groupes.annulerApercu();
                f.dire(apresCopie ? "La copie est posée sur l'original. Déplace-la avec les flèches, pivote-la si besoin, puis Confirmer. Fermer la laisse ici."
                        : "Choisis où aller avec les flèches : des fantômes montrent l'arrivée dans le jeu (chez toi seulement).");
                confirmer.setDisable(true);
            } else if (d[2] == 0) {
                Groupes.Simulation s = Groupes.simuler(i.id, d[0], d[1]);
                f.dire(Ui.majuscule(s.toString()) + ". Les fantômes montrent l'arrivée.");
                Groupes.previsualiser(i.id, d[0], d[1]);
                confirmer.setDisable(false);
            } else {
                f.dire("Chaque mobi part à sa place finale et garde sa hauteur. Les muraux ne pivotent pas. Les fantômes montrent l'arrivée.");
                Groupes.previsualiser(i.id, d[0], d[1], d[2]);
                confirmer.setDisable(false);
            }
        };
        Button xm = fleche("↖", "Vers le haut à gauche", () -> { d[0]--; maj.run(); });
        Button xp = fleche("↘", "Vers le bas à droite", () -> { d[0]++; maj.run(); });
        Button ym = fleche("↗", "Vers le haut à droite", () -> { d[1]--; maj.run(); });
        Button yp = fleche("↙", "Vers le bas à gauche", () -> { d[1]++; maj.run(); });
        GridPane fleches = new GridPane();
        fleches.setHgap(5); fleches.setVgap(5);
        fleches.add(xm, 0, 0); fleches.add(ym, 1, 0);
        fleches.add(yp, 0, 1); fleches.add(xp, 1, 1);
        Button pivI = fleche("↺", "Un quart de tour dans le sens inverse", () -> { d[2] = (d[2] + 3) & 3; maj.run(); });
        Button pivH = fleche("↻", "Un quart de tour dans le sens horaire", () -> { d[2] = (d[2] + 1) & 3; maj.run(); });
        VBox pivots = new VBox(5, pivI, pivH);
        HBox commandes = new HBox(14, fleches, pivots);
        commandes.setAlignment(Pos.CENTER_LEFT);
        f.contenu(Ui.bloc("Flèches et pivot", commandes, quoi));
        Button fermer = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = CalqueFenetre.bouton("Arrêter", false, () -> { Groupes.Tache t = tache; if (t != null) t.arreter(); });
        arreter.setVisible(false);
        arreter.managedProperty().bind(arreter.visibleProperty());
        confirmer.setOnAction(e -> {
            for (Button b : List.of(xm, xp, ym, yp, pivI, pivH, confirmer, fermer)) b.setDisable(true);
            arreter.setVisible(true);
            int dx = d[0], dy = d[1], q = d[2];
            f.dire("Envoi au jeu…");
            tache = Groupes.deplacer(i.id, dx, dy, q, progression(f, r -> f.fermer()));
        });
        f.boutons(fermer, arreter, confirmer);
        f.surFermeture(Groupes::annulerApercu);
        maj.run();
        f.montrer();
    }

    /** Icone du panneau : deplacer le calque vise. */
    void deplacerDepuisPanneau(Groupes.Info i) { deplacer(i, false); }

    private static String decalage(int dx, int dy, int q) {
        List<String> l = new ArrayList<>();
        if (dx != 0) l.add(Math.abs(dx) + (dx > 0 ? " ↘" : " ↖"));
        if (dy != 0) l.add(Math.abs(dy) + (dy > 0 ? " ↙" : " ↗"));
        if (q == 1) l.add("quart de tour ↻");
        if (q == 2) l.add("demi-tour");
        if (q == 3) l.add("quart de tour ↺");
        return l.isEmpty() ? "Sur place" : Ui.majuscule(String.join(", ", l));
    }

    private static Button fleche(String texte, String aide, Runnable r) {
        Button b = new Button(texte);
        b.getStyleClass().add("calques-fleche");
        b.setTooltip(CalqueFenetre.bulle(aide));
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    // ============================================================== dupliquer

    /**
     * Dupliquer : quoi copier (sols, murs, wired) et d'ou viennent les mobis ;
     * la copie est posee a la meme place (dalles magiques dessous), devient un
     * nouveau calque, puis la fenetre Deplacer s'ouvre pour elle.
     */
    void dupliquer(Groupes.Info i) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        Groupes.Compte c = Groupes.compter(i.id);
        int autres = c.sols - c.wired;
        if (c.total() == 0) { refus("« " + i.nom + " » est vide : rien à dupliquer."); return; }
        CalqueFenetre f = ouvrir("Dupliquer « " + i.nom + " »");
        CheckBox sols = new CheckBox("Sols (" + autres + ")");
        CheckBox murs = new CheckBox("Muraux (" + c.murs + ")");
        CheckBox wired = new CheckBox("Wired, avec leur réglage (" + c.wired + ")");
        sols.setSelected(autres > 0); sols.setDisable(autres == 0);
        murs.setSelected(c.murs > 0); murs.setDisable(c.murs == 0);
        wired.setSelected(c.wired > 0); wired.setDisable(c.wired == 0);
        FenetreOptions.Source src = new FenetreOptions.Source(source);
        f.contenu(Ui.bloc("À copier", sols, murs, wired), src.bloc());
        f.dire("La copie est posée à la même place, chaque mobi à sa hauteur, puis tu la déplaces avec les flèches.");
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Dupliquer", true, () -> { });
        ok.setOnAction(e -> {
            if (!sols.isSelected() && !murs.isSelected() && !wired.isSelected()) { f.dire("Coche au moins une sorte de mobis."); return; }
            boolean bs = sols.isSelected(), bm = murs.isSelected(), bw = wired.isSelected();
            Generateur.Source so = src.valeur();
            source = so;
            for (Node n : List.of(sols, murs, wired, ok, annuler)) n.setDisable(true);
            arreter.setVisible(true);
            f.dire("Pose de la copie…");
            tache = Groupes.dupliquerOptions(i.id, bs, bm, bw, so, progression(f, this::apresCopie));
        });
        f.boutons(annuler, arreter, ok);
        f.montrer();
    }

    /** Une copie vient d'etre posee : elle devient le calque choisi, et on propose de la deplacer. */
    private void apresCopie(Groupes.Resultat r) {
        fermer();
        if (r == null || r.nouveauCalque == null) return;
        choisir.accept(r.nouveauCalque);
        Groupes.Info n = Groupes.info(r.nouveauCalque);
        if (n != null) deplacer(n, true);
    }

    /** Ctrl+V dans le meme appart : copie posee sur place, puis Deplacer. */
    void coller(GroupePressePapier.Copie c) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Coller « " + c.nom + " »");
        f.contenu(new Label(GroupePressePapier.mobis(c.nombre()) + " à poser sur place, dans un nouveau calque."));
        f.dire("Pose de la copie…");
        Button arreter = arreter();
        arreter.setVisible(true);
        f.boutons(arreter);
        f.montrer();
        tache = Groupes.collerCopie(c.sols, c.murs, c.calqueId, progression(f, this::apresCopie));
    }

    // ================================================================ hauteur

    /**
     * Hauteur de tous les mobis du calque : « +1 » ou « -0,5 » decale chacun
     * (la forme est gardee), « 2 » met tout a la meme altitude. La fenetre
     * reste ouverte pour regler encore.
     */
    void hauteur(Groupes.Info i) {
        String v = Groupes.refusVerrou(i.id);
        if (v != null) { refus(v); return; }
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Hauteur « " + i.nom + " »");
        TextField valeur = new TextField("+1");
        valeur.setPrefColumnCount(6);
        List<Node> boutons = new ArrayList<>();
        Button arreter = arreter();
        Consumer<String> appliquer = txt -> {
            String s = txt.trim().replace(',', '.').replace(" ", "");
            boolean relatif = s.startsWith("+") || s.startsWith("-");
            double n;
            try { n = Double.parseDouble(s); } catch (NumberFormatException ex) {
                f.dire("« " + txt + " » n'est pas un nombre. Exemple : +1, -0,5 ou 2.");
                return;
            }
            if (relatif && n == 0) return;
            if (occupe()) { f.dire("Une action est déjà en cours."); return; }
            for (Node b : boutons) b.setDisable(true);
            arreter.setVisible(true);
            f.dire("Hauteurs en cours…");
            tache = Groupes.hauteur(i.id, relatif, n, progression(f, r -> {
                for (Node b : boutons) b.setDisable(false);
                arreter.setVisible(false);
                f.dire("");
            }));
        };
        HBox rapides = new HBox(5);
        for (String q : new String[]{"-1", "-0,5", "+0,5", "+1"}) {
            Button b = new Button(q);
            b.setFocusTraversable(false);
            b.setTooltip(CalqueFenetre.bulle(q.startsWith("-") ? "Descendre chaque mobi de " + q.substring(1) : "Monter chaque mobi de " + q.substring(1)));
            b.setOnAction(e -> appliquer.accept(q));
            rapides.getChildren().add(b);
            boutons.add(b);
        }
        Button ok = CalqueFenetre.bouton("Appliquer", true, () -> appliquer.accept(valeur.getText()));
        boutons.add(ok);
        boutons.add(valeur);
        valeur.setOnAction(e -> appliquer.accept(valeur.getText()));
        HBox saisie = new HBox(6, new Label("Valeur :"), valeur);
        saisie.setAlignment(Pos.CENTER_LEFT);
        f.contenu(Ui.bloc("Monter ou descendre", rapides),
                Ui.bloc("Autre valeur", saisie,
                        Ui.discret("+1 ou -0,5 décale chaque mobi ; un nombre seul (ex. 2) les met tous à cette hauteur.")));
        f.boutons(CalqueFenetre.bouton("Fermer", false, f::fermer), arreter, ok);
        f.montrer();
    }

    // ================================================================= miroir

    /** Miroir : l'axe, et retourner sur place ou poser une copie a cote (nouveau calque). */
    void miroir(Groupes.Info i) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Miroir « " + i.nom + " »");
        ToggleGroup gAxe = new ToggleGroup(), gMode = new ToggleGroup();
        ToggleButton axeX = bascule("↔ Gauche / droite", gAxe, true), axeY = bascule("↕ Haut / bas", gAxe, false);
        ToggleButton copie = bascule("Copie à côté", gMode, true), place = bascule("Sur place", gMode, false);
        Groupes.Compte c = Groupes.compter(i.id);
        Runnable maj = () -> {
            boolean surX = gAxe.getSelectedToggle() != axeY, enCopie = gMode.getSelectedToggle() != place;
            f.dire((surX ? "Gauche ↔ droite" : "Haut ↔ bas") + ", "
                    + (enCopie ? "copie posée juste " + (surX ? "à droite" : "en dessous") + " : elle devient un nouveau calque."
                               : "retournés sur place, dans le cadre qui les contient.")
                    + (c.murs > 0 ? " Les " + c.murs + " mobi(s) mural(aux) ne sont pas retournés." : ""));
        };
        for (ToggleGroup g : List.of(gAxe, gMode))
            g.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); else maj.run(); });
        f.contenu(Ui.bloc("Axe", new HBox(5, axeX, axeY)), Ui.bloc("Résultat", new HBox(5, copie, place)));
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Confirmer", true, () -> { });
        ok.setOnAction(e -> {
            boolean surX = gAxe.getSelectedToggle() != axeY, enCopie = gMode.getSelectedToggle() != place;
            if (!enCopie) {
                String v = Groupes.refusVerrou(i.id);
                if (v != null) { f.dire(v); return; }
            }
            for (Node n : List.of(axeX, axeY, copie, place, ok, annuler)) n.setDisable(true);
            arreter.setVisible(true);
            if (enCopie) {
                f.dire("Pose de la copie miroir…");
                tache = Groupes.dupliquerMiroir(i.id, surX, 1, source, progression(f, this::apresCopie));
            } else miroirSurPlace(f, i.id, surX);
        });
        f.boutons(annuler, arreter, ok);
        maj.run();
        f.montrer();
    }

    /** Miroir sur place : les mobis de sol bougent (OutilMiroir). */
    private void miroirSurPlace(CalqueFenetre f, String id, boolean surX) {
        List<HFloorItem> sols = new ArrayList<>();
        for (int s : Groupes.mobis(id).get(0)) { HFloorItem it = Salle.sol(s); if (it != null) sols.add(it); }
        Salle.tache("calques-miroir", () -> {
            final String[] dernier = {""};
            OutilMiroir.surPlace(sols, surX, m -> { dernier[0] = m; Platform.runLater(() -> f.dire(m)); });
            Platform.runLater(() -> { resultat(dernier[0]); f.fermer(); });
        });
    }

    private static ToggleButton bascule(String texte, ToggleGroup g, boolean choisi) {
        ToggleButton b = new ToggleButton(texte);
        b.setToggleGroup(g);
        b.setSelected(choisi);
        b.setFocusTraversable(false);
        return b;
    }

    // ================================================================ pivoter

    /**
     * Pivoter : tout de suite (Ctrl+Z annule), d'un bloc ou chaque mobi sur sa
     * case ; ou une copie pivotee posee a cote (nouveau calque, puis Deplacer).
     * La fenetre reste ouverte pour tourner encore.
     */
    void pivoter(Groupes.Info i) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Pivoter « " + i.nom + " »");
        List<Button> tous = new ArrayList<>();
        Button arreter = arreter();
        Consumer<Function<Groupes.Progression, Groupes.Tache>> lancer = act -> {
            if (occupe()) { f.dire("Une action est déjà en cours."); return; }
            for (Button b : tous) b.setDisable(true);
            arreter.setVisible(true);
            f.dire("Pivot en cours…");
            tache = act.apply(progression(f, r -> {
                for (Button b : tous) b.setDisable(false);
                arreter.setVisible(false);
                f.dire("");
            }));
        };
        Consumer<Function<Groupes.Progression, Groupes.Tache>> modifier = act -> {
            String v = Groupes.refusVerrou(i.id);
            if (v != null) { f.dire(v); return; }
            lancer.accept(act);
        };
        Button blocI = petit("↺ Inverse", "Tout le calque d'un bloc, un quart de tour dans le sens inverse",
                () -> modifier.accept(p -> Groupes.pivoter(i.id, false, true, p)));
        Button blocH = petit("↻ Horaire", "Tout le calque d'un bloc, un quart de tour dans le sens horaire",
                () -> modifier.accept(p -> Groupes.pivoter(i.id, true, true, p)));
        Button chacunI = petit("↺ Inverse", "Chaque mobi tourne sur sa case, sens inverse",
                () -> modifier.accept(p -> Groupes.pivoter(i.id, false, false, p)));
        Button chacunH = petit("↻ Horaire", "Chaque mobi tourne sur sa case, sens horaire",
                () -> modifier.accept(p -> Groupes.pivoter(i.id, true, false, p)));
        Button copieH = petit("↻ Horaire", "Copie tournée d'un quart de tour horaire, posée à côté", () -> copieTournee(f, i, 1, tous, arreter));
        Button copieI = petit("↺ Inverse", "Copie tournée d'un quart de tour inverse, posée à côté", () -> copieTournee(f, i, 3, tous, arreter));
        Button copieD = petit("Demi-tour", "Copie tournée d'un demi-tour, posée à côté", () -> copieTournee(f, i, 2, tous, arreter));
        tous.addAll(List.of(blocI, blocH, chacunI, chacunH, copieH, copieI, copieD));
        f.contenu(Ui.bloc("Tout le calque d'un bloc", new HBox(5, blocI, blocH)),
                Ui.bloc("Chaque mobi sur sa case", new HBox(5, chacunI, chacunH)),
                Ui.bloc("Copie pivotée à côté", new HBox(5, copieH, copieI, copieD)));
        f.dire("Tout de suite, sans confirmer : Ctrl+Z pour annuler.");
        f.boutons(CalqueFenetre.bouton("Fermer", false, f::fermer), arreter);
        f.montrer();
    }

    private void copieTournee(CalqueFenetre f, Groupes.Info i, int quarts, List<Button> tous, Button arreter) {
        if (occupe()) { f.dire("Une action est déjà en cours."); return; }
        for (Button b : tous) b.setDisable(true);
        arreter.setVisible(true);
        f.dire("Pose de la copie pivotée…");
        tache = Groupes.dupliquerTourne(i.id, quarts, source, progression(f, this::apresCopie));
    }

    private static Button petit(String texte, String aide, Runnable r) {
        Button b = new Button(texte);
        b.setTooltip(CalqueFenetre.bulle(aide));
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    // ============================================================== supprimer

    /**
     * Confirmation courte avant de supprimer des calques dont les mobis
     * comptent : des wired (reglages perdus) ou beaucoup de mobis.
     */
    void confirmerSuppression(Groupes.Suppression s, Runnable apres) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Supprimer " + s.quoi() + " ?");
        Label l = new Label("Ses " + GroupePressePapier.mobis(s.mobis) + " vont être ramassés"
                + (s.wired > 0 ? ", dont " + s.wired + " wired : leurs réglages seront perdus" : "")
                + ". Ctrl+Z les repose ensuite. Pour garder les mobis, fusionne plutôt le calque.");
        l.setWrapText(true);
        l.setMaxWidth(280);
        f.contenu(l);
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Supprimer", true, () -> { });
        ok.setOnAction(e -> {
            ok.setDisable(true); annuler.setDisable(true);
            arreter.setVisible(true);
            f.dire("Ramassage…");
            tache = Groupes.supprimer(s.ids, progression(f, r -> { f.fermer(); if (apres != null) apres.run(); }));
        });
        f.boutons(annuler, arreter, ok);
        f.montrer();
    }

    /** Supprimer sans confirmation : la progression va dans le panneau (dire). */
    void supprimer(Groupes.Suppression s, Consumer<String> dire, Runnable apres) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        tache = Groupes.supprimer(s.ids, new Groupes.Progression() {
            @Override public void progres(int fait, int total, String texte) {
                dire.accept(total > 0 ? texte + " (" + fait + "/" + total + ")" : texte);
            }
            @Override public void fin(Groupes.Resultat r) {
                dire.accept("");
                resultat(r.message);
                if (apres != null) apres.run();
            }
        });
    }

    // ============================================================== familles

    private CalqueFenetre familles;

    /** Les familles (wired, dalles, muraux... a cocher) dans leur fenetre. */
    void familles(Node contenu) {
        if (familles == null) {
            familles = new CalqueFenetre(css, "Familles de mobis", panneau);
            familles.contenu(contenu);
            familles.boutons(CalqueFenetre.bouton("Fermer", false, familles::fermer));
        }
        if (familles.ouverte()) familles.fermer(); else familles.montrer();
    }

    // ================================================================ outils

    private Button arreter() {
        Button b = CalqueFenetre.bouton("Arrêter", false, () -> { Groupes.Tache t = tache; if (t != null) t.arreter(); });
        b.setVisible(false);
        b.managedProperty().bind(b.visibleProperty());
        return b;
    }

    /** Progression dans la fenetre ; resultat dans le jeu ; puis « apres » (fil JavaFX). */
    private static Groupes.Progression progression(CalqueFenetre f, Consumer<Groupes.Resultat> apres) {
        return new Groupes.Progression() {
            @Override public void progres(int fait, int total, String texte) {
                f.dire(total > 0 ? texte + " (" + fait + "/" + total + ")" : texte);
            }
            @Override public void fin(Groupes.Resultat r) {
                resultat(r.message);
                if (!r.ok && !r.arrete && r.reussis == 0) f.dire(r.message);       // echec : reste lisible dans la fenetre
                if (apres != null) apres.accept(r);
            }
        };
    }
}

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** Resultat d'une action : dans le jeu et la console (Journal), succes ou erreur selon le texte. */
    static void resultat(String s) {
        if (s == null || s.isBlank()) return;
        String m = Ui.majuscule(s);
        if (Journal.genre(m) == Journal.Genre.ERREUR) Journal.erreur(m); else Journal.succes(m);
    }

    /**
     * Resultat d'une action de Groupes : erreur seulement si elle a echoue (pas
     * si arretee). Une action rapide qui a tout reussi (deplacer, pivoter,
     * masquer...) se voit deja dans le jeu : pas de message, la console garde
     * le bilan. Les actions longues, les arrets et les echecs sont dits.
     */
    static void resultat(Groupes.Resultat r) {
        if (r == null || r.message == null || r.message.isBlank()) return;
        String m = Ui.majuscule(r.message);
        if (r.ok && !r.arrete && r.echecs == 0 && r.duree >= 0 && r.duree < InfoJeu.DUREE_MIN
                && Journal.genre(m) != Journal.Genre.ERREUR) {
            Journal.debug("calques : " + m);
            return;
        }
        if (r.ok || r.arrete) Journal.succes(m); else Journal.erreur(m);
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
        // Pendant l'apercu, les mobis d'origine sont caches chez toi : seuls les fantomes
        // bougent (pas d'impression de copie). Ils reviennent avant le vrai deplacement.
        boolean[] caches = {false};
        Runnable cacher = () -> {
            if (caches[0]) return;
            caches[0] = true;
            Salle.tache("deplacer-cacher", () -> {
                List<java.util.Set<Integer>> ids = Groupes.mobis(i.id);
                List<HFloorItem> sols = new ArrayList<>();
                List<gearth.extensions.parsers.HWallItem> murs = new ArrayList<>();
                for (int id : ids.get(0)) { HFloorItem it = Salle.sol(id); if (it != null) sols.add(it); }
                for (int id : ids.get(1)) { gearth.extensions.parsers.HWallItem w = Salle.mur(id); if (w != null) murs.add(w); }
                Calques.masquer(APERCU_DEPLACER, sols, murs);
            });
        };
        Runnable montrer = () -> {
            if (!caches[0]) return;
            caches[0] = false;
            Calques.reafficher(APERCU_DEPLACER);
        };
        Label quoi = new Label();
        quoi.getStyleClass().add("calques-valeur");
        Button confirmer = CalqueFenetre.bouton("Confirmer", true, () -> { });
        Runnable maj = () -> {
            Groupes.Info k = Groupes.info(i.id);
            if (k == null) { f.fermer(); return; }
            quoi.setText(decalage(d[0], d[1], d[2]));
            if (d[0] == 0 && d[1] == 0 && d[2] == 0) {
                Groupes.annulerApercu();
                montrer.run();
                f.dire(apresCopie ? "La copie est posée sur l'original. Déplace-la avec les flèches, pivote-la si besoin, puis Confirmer. Fermer la laisse ici."
                        : "Choisis où aller avec les flèches : des fantômes montrent l'arrivée dans le jeu (chez toi seulement).");
                confirmer.setDisable(true);
            } else if (d[2] == 0) {
                Groupes.Simulation s = Groupes.simuler(i.id, d[0], d[1]);
                f.dire(Ui.majuscule(s.toString()) + ". Les fantômes montrent l'arrivée.");
                Groupes.previsualiser(i.id, d[0], d[1]);
                cacher.run();
                confirmer.setDisable(false);
            } else {
                f.dire("Chaque mobi part à sa place finale et garde sa hauteur. Les muraux ne pivotent pas. Les fantômes montrent l'arrivée.");
                Groupes.previsualiser(i.id, d[0], d[1], d[2]);
                cacher.run();
                confirmer.setDisable(false);
            }
        };
        // un clic sur une fleche et une touche flechee passent par ici
        java.util.function.BiConsumer<Integer, Integer> bouger = (dx, dy) -> { d[0] += dx; d[1] += dy; maj.run(); };
        Button xm = fleche("↖", "Vers le haut à gauche (touche ←)", () -> bouger.accept(-1, 0));
        Button xp = fleche("↘", "Vers le bas à droite (touche →)", () -> bouger.accept(1, 0));
        Button ym = fleche("↗", "Vers le haut à droite (touche ↑)", () -> bouger.accept(0, -1));
        Button yp = fleche("↙", "Vers le bas à gauche (touche ↓)", () -> bouger.accept(0, 1));
        GridPane fleches = new GridPane();
        fleches.setHgap(5); fleches.setVgap(5);
        fleches.add(xm, 0, 0); fleches.add(ym, 1, 0);
        fleches.add(yp, 0, 1); fleches.add(xp, 1, 1);
        Button pivI = fleche("", "Un quart de tour dans le sens inverse", () -> { d[2] = (d[2] + 3) & 3; maj.run(); });
        Button pivH = fleche("", "Un quart de tour dans le sens horaire", () -> { d[2] = (d[2] + 1) & 3; maj.run(); });
        pivI.setGraphic(Icones.petite(Icones.PIVOTER_INVERSE, 15, false));
        pivH.setGraphic(Icones.petite(Icones.PIVOTER, 15, false));
        VBox pivots = new VBox(5, pivI, pivH);
        HBox commandes = new HBox(14, fleches, pivots);
        commandes.setAlignment(Pos.CENTER_LEFT);
        f.contenu(Ui.bloc("Flèches et pivot", commandes, quoi, aideClavier("Confirmer")));
        Button fermer = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = CalqueFenetre.bouton("Arrêter", false, () -> { Groupes.Tache t = tache; if (t != null) t.arreter(); });
        arreter.setVisible(false);
        arreter.managedProperty().bind(arreter.visibleProperty());
        confirmer.setOnAction(e -> {
            for (Button b : List.of(xm, xp, ym, yp, pivI, pivH, confirmer, fermer)) b.setDisable(true);
            arreter.setVisible(true);
            int dx = d[0], dy = d[1], q = d[2];
            Groupes.annulerApercu();
            montrer.run();                          // les vrais mobis reviennent, puis partent
            tache = Groupes.deplacer(i.id, dx, dy, q, progression(f, r -> f.fermer()));
        });
        f.boutons(fermer, arreter, confirmer);
        f.fleches(clavier(f, bouger, xm, confirmer, fermer));
        f.surFermeture(() -> { Groupes.annulerApercu(); montrer.run(); });
        maj.run();
        f.montrer();
    }

    /** Raison de masquage des mobis d'origine pendant l'apercu de Deplacer. */
    static final String APERCU_DEPLACER = "apercu-deplacer";

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

    /**
     * Logique pure : touche flechee -> {dx, dy} en cases du jeu, comme les
     * boutons a l'ecran tournes d'un huitieme de tour : ↑ = ↗ (y - 1),
     * → = ↘ (x + 1), ↓ = ↙ (y + 1), ← = ↖ (x - 1). Maj : 5 cases d'un coup.
     * direction : 0 = haut, 1 = droite, 2 = bas, 3 = gauche ; autre : {0, 0}.
     */
    static int[] pasFleche(int direction, boolean maj) {
        int n = maj ? PAS_MAJ : 1;
        switch (direction) {
            case 0: return new int[]{0, -n};
            case 1: return new int[]{n, 0};
            case 2: return new int[]{0, n};
            case 3: return new int[]{-n, 0};
            default: return new int[]{0, 0};
        }
    }

    /** Cases parcourues par Maj + fleche. */
    static final int PAS_MAJ = 5;

    /** Rappel des touches, sous les fleches. */
    private static Label aideClavier(String valider) {
        Label l = Ui.discret(WindowsClavier.texte("Clavier : ↑ ↗, → ↘, ↓ ↙, ← ↖ (Maj : " + PAS_MAJ
                + " cases). Entrée : " + valider + ", Échap : Annuler. Marche aussi dans le jeu, chat vide."));
        l.setWrapText(true);
        l.setMaxWidth(280);
        return l;
    }

    /**
     * Les touches d'une fenetre a fleches : exactement le code des boutons
     * (bouger, valider, annuler), et rien quand ils sont grises (envoi en cours).
     */
    private static RaccourcisGlobaux.Fleches clavier(CalqueFenetre f, java.util.function.BiConsumer<Integer, Integer> bouger,
                                                    Node uneFleche, Button valider, Button annuler) {
        return new RaccourcisGlobaux.Fleches() {
            @Override public void fleche(int direction, boolean maj) {
                if (!f.ouverte() || uneFleche.isDisabled()) return;
                int[] p = pasFleche(direction, maj);
                Journal.debug("calques : touche flèche " + direction + (maj ? " + Maj" : "") + " -> " + p[0] + ", " + p[1]);
                bouger.accept(p[0], p[1]);
            }
            @Override public void entree() {
                if (f.ouverte() && !valider.isDisabled()) valider.fire();
            }
            @Override public void echap() {
                if (f.ouverte() && !annuler.isDisabled()) annuler.fire();
            }
        };
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
     * Dupliquer : RIEN n'est pose tout de suite. Une copie fantome (chez toi
     * seulement) apparait juste a cote du calque ; fleches, pivots et miroir
     * la placent sans rien envoyer au serveur ; « Poser » la pose vraiment a
     * cette place (tapis de dalles : voir PoseTapis) et elle devient un nouveau
     * calque. Annuler, Echap ou fermer : les fantomes partent, rien n'est pose.
     * Permis sur un calque verrouille (c'est une copie).
     */
    void dupliquer(Groupes.Info i) {
        copier("Dupliquer « " + i.nom + " »", i.id, null, dessus(i.id), GroupeCalcul.Transfo.NEUTRE);
    }

    /** Ctrl+V dans le meme appart : meme fenetre que Dupliquer, avec les mobis copies. */
    void coller(GroupePressePapier.Copie c) {
        List<Set<Integer>> ids = List.of(new LinkedHashSet<>(c.sols), new LinkedHashSet<>(c.murs));
        copier("Coller « " + c.nom + " »", null, ids, Groupes.dessusCollage(c.calqueId), GroupeCalcul.Transfo.NEUTRE);
    }

    /** La copie va au-dessus de son calque (en haut si c'est la selection). */
    private static String dessus(String calqueId) { return Groupes.SELECTION.equals(calqueId) ? null : calqueId; }

    /**
     * La fenetre de la copie a placer.
     * @param calque calque d'origine (null : mobis donnes)
     * @param ids    null : les mobis du calque
     * @param base   pivot / miroir de depart (copie pivotee, copie miroir)
     */
    private void copier(String titre, String calque, List<Set<Integer>> ids, String dessus, GroupeCalcul.Transfo base) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        List<Set<Integer>> lus = ids != null ? ids : Groupes.mobis(calque);
        int[] n = GroupeActions.sortes(lus);                      // sols, wired, muraux
        if (n[0] + n[1] + n[2] == 0) { refus("Rien à copier : aucun de ces mobis n'est dans l'appart."); return; }
        int salle = Groupes.salle();
        CalqueFenetre f = ouvrir(titre);
        CheckBox sols = new CheckBox("Sols (" + n[0] + ")");
        CheckBox murs = new CheckBox("Muraux (" + n[2] + ")");
        CheckBox wired = new CheckBox("Wired, avec leur réglage (" + n[1] + ")");
        sols.setSelected(n[0] > 0); sols.setDisable(n[0] == 0);
        murs.setSelected(n[2] > 0); murs.setDisable(n[2] == 0);
        wired.setSelected(n[1] > 0 && base.miroir == 0); wired.setDisable(n[1] == 0);
        GroupeCalcul.Transfo[] tr = {GroupeCalcul.depart(GroupeActions.elements(lus.get(0), lus.get(1)), base, Salle::hauteurSol)};
        FenetreOptions.Source src = new FenetreOptions.Source(source);
        Node blocSource = src.bloc();
        Label quoi = new Label();
        quoi.getStyleClass().add("calques-valeur");
        Button poser = CalqueFenetre.bouton("Poser", true, () -> { });
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        boolean[] pose = {false};

        Button[] mir = new Button[2];
        Runnable maj = () -> {
            if (pose[0]) return;
            if (Groupes.salle() != salle || (calque != null && Groupes.info(calque) == null)) { f.fermer(); return; }
            boolean bs = sols.isSelected(), bm = murs.isSelected(), bw = wired.isSelected();
            // le collage wired ne sait pas retourner : miroir ou wired, pas les deux
            for (Button b : mir) b.setDisable(bw);
            wired.setDisable(n[1] == 0 || tr[0].miroir != 0);
            quoi.setText(decalage(tr[0].dx, tr[0].dy, tr[0].quarts) + (tr[0].miroir != 0 ? ", miroir" : ""));
            if (!bs && !bm && !bw) {
                Groupes.annulerApercu();
                poser.setDisable(true);
                f.dire("Coche au moins une sorte de mobis.");
                return;
            }
            Groupes.previsualiserCopie(calque, ids, tr[0], bs, bm, bw);
            poser.setDisable(false);
            f.dire("Les fantômes montrent la copie, chez toi seulement. Place-la, puis Poser."
                    + (bm && n[2] > 0 && !tr[0].glissement() ? " Les muraux ne pivotent pas : ils ne seront pas copiés." : ""));
        };
        java.util.function.Consumer<java.util.function.UnaryOperator<GroupeCalcul.Transfo>> changer = op -> { tr[0] = op.apply(tr[0]); maj.run(); };
        // un clic sur une fleche et une touche flechee passent par ici
        java.util.function.BiConsumer<Integer, Integer> bouger = (dx, dy) -> changer.accept(t -> t.deplace(dx, dy));
        Button xm = fleche("↖", "Vers le haut à gauche (touche ←)", () -> bouger.accept(-1, 0));
        Button xp = fleche("↘", "Vers le bas à droite (touche →)", () -> bouger.accept(1, 0));
        Button ym = fleche("↗", "Vers le haut à droite (touche ↑)", () -> bouger.accept(0, -1));
        Button yp = fleche("↙", "Vers le bas à gauche (touche ↓)", () -> bouger.accept(0, 1));
        GridPane fleches = new GridPane();
        fleches.setHgap(5); fleches.setVgap(5);
        fleches.add(xm, 0, 0); fleches.add(ym, 1, 0);
        fleches.add(yp, 0, 1); fleches.add(xp, 1, 1);
        Button pivI = fleche("", "Un quart de tour dans le sens inverse", () -> changer.accept(t -> t.pivote(false)));
        Button pivH = fleche("", "Un quart de tour dans le sens horaire", () -> changer.accept(t -> t.pivote(true)));
        pivI.setGraphic(Icones.petite(Icones.PIVOTER_INVERSE, 15, false));
        pivH.setGraphic(Icones.petite(Icones.PIVOTER, 15, false));
        mir[0] = fleche("↔", "Miroir gauche ↔ droite (pas avec les wired)", () -> changer.accept(GroupeCalcul.Transfo::miroirX));
        mir[1] = fleche("↕", "Miroir haut ↕ bas (pas avec les wired)", () -> changer.accept(GroupeCalcul.Transfo::miroirY));
        VBox pivots = new VBox(5, pivI, pivH);
        VBox miroirs = new VBox(5, mir[0], mir[1]);
        HBox commandes = new HBox(14, fleches, pivots, miroirs);
        commandes.setAlignment(Pos.CENTER_LEFT);
        for (CheckBox c : List.of(sols, murs, wired)) c.selectedProperty().addListener((o, a, b) -> maj.run());

        List<Node> controles = List.of(sols, murs, wired, blocSource, xm, xp, ym, yp, pivI, pivH, mir[0], mir[1], poser, annuler);
        poser.setOnAction(e -> {
            boolean bs = sols.isSelected(), bm = murs.isSelected(), bw = wired.isSelected();
            if (!bs && !bm && !bw) { f.dire("Coche au moins une sorte de mobis."); return; }
            if (occupe()) { f.dire("Une action est déjà en cours."); return; }
            Generateur.Source so = src.valeur();
            source = so;
            pose[0] = true;
            for (Node x : controles) x.setDisable(true);
            arreter.setVisible(true);
            tache = Groupes.poserCopie(calque, ids, dessus, tr[0], bs, bm, bw, so, progression(f, r -> {
                if (r.nouveauCalque == null && r.reussis == 0 && !r.arrete && f.ouverte()) {
                    // rien n'est pose (refus, moteur occupe...) : on garde la copie fantome pour reessayer
                    pose[0] = false;
                    arreter.setVisible(false);
                    for (Node x : controles) x.setDisable(false);
                    sols.setDisable(n[0] == 0);
                    murs.setDisable(n[2] == 0);
                    maj.run();
                    f.dire(r.message);
                    return;
                }
                apresCopie(r);
            }));
        });
        Runnable garde = () -> {
            if (!f.ouverte()) return;
            if (Groupes.salle() != salle) { f.fermer(); return; }
            // appart recharge : le client a jete les fantomes, on les remontre
            if (!pose[0] && GroupeApercu.demande() == null
                    && (sols.isSelected() || murs.isSelected() || wired.isSelected())) maj.run();
        };
        Groupes.ecouter(garde);
        f.surFermeture(() -> { Groupes.retirerEcouteur(garde); Groupes.annulerApercu(); });
        f.contenu(Ui.bloc("Place la copie", commandes, quoi, aideClavier("Poser")), Ui.bloc("À copier", sols, murs, wired), blocSource);
        f.boutons(annuler, arreter, poser);
        f.fleches(clavier(f, bouger, xm, poser, annuler));
        maj.run();
        f.montrer();
    }

    /** Une copie vient d'etre posee : elle devient le calque choisi (mis en valeur). */
    private void apresCopie(Groupes.Resultat r) {
        fermer();
        if (r == null || r.nouveauCalque == null) return;
        choisir.accept(r.nouveauCalque);
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
            tache = Groupes.hauteur(i.id, relatif, n, progression(f, r -> {
                for (Node b : boutons) b.setDisable(false);
                arreter.setVisible(false);
                f.dire(r.ok ? "" : r.message);        // echec : la raison reste lisible
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

    /** Miroir : l'axe, et retourner sur place, ou une copie a cote (fantomes, puis Poser : nouveau calque). */
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
                    + (enCopie ? "une copie fantôme apparaît à côté : tu la places, puis Poser. Elle devient un nouveau calque."
                               : "retournés sur place, dans le cadre qui les contient.")
                    + (c.murs == 1 ? " 1 mural n'est pas retourné." : c.murs > 1 ? " " + c.murs + " muraux ne sont pas retournés." : ""));
        };
        for (ToggleGroup g : List.of(gAxe, gMode))
            g.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); else maj.run(); });
        f.contenu(Ui.bloc("Axe", new HBox(5, axeX, axeY)), Ui.bloc("Résultat", new HBox(5, copie, place)));
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Confirmer", true, () -> { });
        ok.setOnAction(e -> {
            boolean surX = gAxe.getSelectedToggle() != axeY, enCopie = gMode.getSelectedToggle() != place;
            if (enCopie) {
                // copie : d'abord en fantomes, posee vraiment par « Poser » (dalle magique)
                GroupeCalcul.Transfo m = surX ? GroupeCalcul.Transfo.NEUTRE.miroirX() : GroupeCalcul.Transfo.NEUTRE.miroirY();
                copier("Copie miroir de « " + i.nom + " »", i.id, null, dessus(i.id), m);
                return;
            }
            String v = Groupes.refusVerrou(i.id);
            if (v != null) { f.dire(v); return; }
            if (occupe()) { f.dire("Une action est déjà en cours."); return; }
            for (Node n : List.of(axeX, axeY, copie, place, ok, annuler)) n.setDisable(true);
            arreter.setVisible(true);
            // sur place : tapis de dalles, par Groupes (une action a la fois, verrou, Arreter)
            tache = Groupes.miroirSurPlace(i.id, surX, progression(f, r -> {
                arreter.setVisible(false);
                if (r.ok || r.arrete || r.reussis > 0) f.fermer();
                else for (Node n : List.of(axeX, axeY, copie, place, ok, annuler)) n.setDisable(false);
            }));
        });
        f.boutons(annuler, arreter, ok);
        maj.run();
        f.montrer();
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
     * Pivoter : tout le calque d'un bloc (comme une voiture entiere). Chaque
     * clic tourne d'abord des FANTOMES (chez toi seulement, rien n'est
     * envoye) ; « Appliquer » pivote vraiment (dalles du calque s'il en a,
     * Cmd+Z annule). La copie pivotee ouvre la fenetre de copie (fantomes,
     * puis Poser). La fenetre reste ouverte pour tourner encore.
     */
    void pivoter(Groupes.Info i) {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        CalqueFenetre f = ouvrir("Pivoter « " + i.nom + " »");
        int[] q = {0};
        boolean[] enCours = {false};
        Label quoi = new Label();
        quoi.getStyleClass().add("calques-valeur");
        Button arreter = arreter();
        Button appliquer = CalqueFenetre.bouton("Appliquer", true, () -> { });
        Runnable maj = () -> {
            if (enCours[0]) return;
            if (Groupes.info(i.id) == null) { f.fermer(); return; }
            quoi.setText(decalage(0, 0, q[0]));
            if (q[0] == 0) {
                Groupes.annulerApercu();
                appliquer.setDisable(true);
                f.dire("Tourne le bloc : des fantômes montrent le résultat, chez toi seulement. Rien ne bouge avant Appliquer.");
            } else {
                Groupes.previsualiser(i.id, 0, 0, q[0]);
                appliquer.setDisable(false);
                f.dire("Les fantômes montrent le résultat. Appliquer pour pivoter vraiment (Cmd+Z annule). Les muraux ne pivotent pas.");
            }
        };
        Button blocI = petit(Icones.PIVOTER_INVERSE, "Inverse", "Un quart de tour dans le sens inverse (fantômes)",
                () -> { q[0] = (q[0] + 3) & 3; maj.run(); });
        Button blocH = petit(Icones.PIVOTER, "Horaire", "Un quart de tour dans le sens horaire (fantômes)",
                () -> { q[0] = (q[0] + 1) & 3; maj.run(); });
        Button copieH = petit(Icones.PIVOTER, "Horaire", "Copie tournée d'un quart de tour horaire, à placer puis poser", () -> copieTournee(i, 1));
        Button copieI = petit(Icones.PIVOTER_INVERSE, "Inverse", "Copie tournée d'un quart de tour inverse, à placer puis poser", () -> copieTournee(i, 3));
        Button copieD = petit("Demi-tour", "Copie tournée d'un demi-tour, à placer puis poser", () -> copieTournee(i, 2));
        Button fermer = CalqueFenetre.bouton("Fermer", false, f::fermer);
        List<Button> tous = List.of(blocI, blocH, copieH, copieI, copieD, appliquer, fermer);
        appliquer.setOnAction(e -> {
            if (q[0] == 0) return;
            String v = Groupes.refusVerrou(i.id);
            if (v != null) { f.dire(v); return; }
            if (occupe()) { f.dire("Une action est déjà en cours."); return; }
            enCours[0] = true;
            for (Button b : tous) b.setDisable(true);
            arreter.setVisible(true);
            f.dire("Pivot en cours…");
            // meme calcul que les fantomes (GroupeCalcul.transformer, centre du cadre)
            tache = Groupes.deplacer(i.id, 0, 0, q[0], progression(f, r -> {
                enCours[0] = false;
                for (Button b : tous) b.setDisable(false);
                arreter.setVisible(false);
                if (r.reussis > 0) q[0] = 0;
                maj.run();
                if (!r.ok) f.dire(r.message);        // echec : la raison reste lisible
            }));
        });
        f.contenu(Ui.bloc("Tout le calque d'un bloc", new HBox(5, blocI, blocH), quoi),
                Ui.bloc("Copie pivotée à côté", new HBox(5, copieH, copieI, copieD)));
        f.boutons(fermer, arreter, appliquer);
        f.surFermeture(Groupes::annulerApercu);
        maj.run();
        f.montrer();
    }

    // ================================================================ cases

    /**
     * Floor dans l'appart (bouton Floor) : c'est maintenant un MODE, comme
     * Construction, avec sa palette a la place du panneau des calques
     * (BarreFloor). Garde pour les anciens appels.
     */
    void cases() {
        fermer();
        BarreFloor.demander();
    }

    // ========================================================= etats d'une zone

    private boolean zoneEtats = false, deuxiemeEtats = false, ecouteEtats = false;

    /**
     * Changer l'etat des mobis d'une zone : deux cases dans le jeu, puis une
     * fenetre dont le bouton « utilise » (comme un double-clic) tous les mobis
     * de sol qui touchent la zone, a chaque clic. Wired et dalles magiques
     * laisses de cote.
     */
    void etatsZone() {
        if (!ecouteEtats) { ecouteEtats = true; Zone.ecouter(() -> Platform.runLater(this::suivreZoneEtats)); }
        zoneEtats = true;
        zoneRemplacer = false;                    // un seul choix de zone a la fois
        zoneRamasser = false;
        deuxiemeEtats = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    private void suivreZoneEtats() {
        if (!zoneEtats) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxiemeEtats) {
                deuxiemeEtats = true;
                InfoJeu.consigne("Choisis le deuxième point de la zone.");
            }
            return;
        }
        zoneEtats = false;
        if (!Zone.definie()) return;
        fenetreEtats();
    }

    private static List<HFloorItem> mobisAEtat() {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : Zone.mobisTouches()) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || Wired.estWired(c) || c.toLowerCase(java.util.Locale.ROOT).startsWith("tile_stackmagic")) continue;
            r.add(it);
        }
        return r;
    }

    private void fenetreEtats() {
        CalqueFenetre f = ouvrir("État et rotation d'une zone");
        Label quoi = Ui.valeur("");
        Runnable maj = () -> quoi.setText(Ui.accorder("Zone " + Zone.largeur() + " × " + Zone.longueur()
                + " : " + mobisAEtat().size() + " mobi(s)."));
        maj.run();
        boolean[] envoi = {false};
        Button changer = CalqueFenetre.bouton("Changer l'état", true, () -> {
            if (envoi[0]) return;
            List<HFloorItem> l = mobisAEtat();
            if (l.isEmpty()) { Journal.erreur("Aucun mobi dans la zone."); return; }
            envoi[0] = true;
            Salle.tache("etats-zone", () -> {
                try {
                    // au rythme commun (150 ms) : plus vite, le jeu en ignorait une partie
                    java.util.Map<Integer, String> avant = new java.util.LinkedHashMap<>();
                    for (HFloorItem it : l) avant.put(it.getId(), Generateur.etatDe(it));
                    java.util.function.Supplier<List<Integer>> inchanges = () -> {
                        List<Integer> r = new ArrayList<>();
                        for (java.util.Map.Entry<Integer, String> e : avant.entrySet()) {
                            HFloorItem now = Salle.sol(e.getKey());
                            if (now != null && Generateur.etatDe(now).equals(e.getValue())) r.add(e.getKey());
                        }
                        return r;
                    };
                    List<Integer> aFaire = new ArrayList<>(avant.keySet());
                    for (int passe = 0; passe < 2 && !aFaire.isEmpty(); passe++) {
                        for (int id : aFaire)
                            Salle.envoyerEspace(new gearth.protocol.HPacket("UseFurniture",
                                    gearth.protocol.HMessage.Direction.TOSERVER, id, 0));
                        PoseDirecte.suivre(() -> inchanges.get().size(), 600, 2500);
                        aFaire = inchanges.get();       // seconde passe : seulement ceux qui n'ont pas bouge
                    }
                    if (!aFaire.isEmpty())
                        Journal.erreur(Ui.accorder(aFaire.size() + " mobi(s) sur " + avant.size()
                                + " n'ont pas changé d'état (pas d'autre état, ou refusé par le jeu)."));
                } finally {
                    envoi[0] = false;
                    Platform.runLater(maj);
                }
            });
        });
        Button pivoter = CalqueFenetre.bouton("Pivoter", false, () -> { });
        pivoter.setTooltip(CalqueFenetre.bulle("Chaque mobi de la zone tourne d'un quart de tour sur lui-même "
                + "(même case, même hauteur). Re-clique pour continuer à tourner. Cmd+Z annule."));
        pivoter.setOnAction(e -> {
            if (envoi[0] || occupe()) { refus("Une action est déjà en cours."); return; }
            List<Integer> ids = new ArrayList<>();
            for (HFloorItem it : mobisAEtat()) if (!GroupeFantomes.estFantome(it.getId())) ids.add(it.getId());
            if (ids.isEmpty()) { Journal.erreur("Aucun mobi dans la zone."); return; }
            pivoter.setDisable(true);
            changer.setDisable(true);
            tache = Groupes.pivoterMobis(ids, true, progression(f, r -> {
                pivoter.setDisable(false);
                changer.setDisable(false);
                maj.run();
            }));
        });
        // ---- un etat precis, par type de mobi de la zone
        VBox types = new VBox(4);
        java.util.Map<Integer, Spinner<Integer>> voulus = new java.util.LinkedHashMap<>();
        VBox[] blocEtats = {null};
        Runnable[] majTypes = {null};
        majTypes[0] = () -> {
            // le bloc n'apparait que si un type de la zone a au moins deux etats
            boolean un = remplirTypes(types, voulus, () -> { if (courante == f) majTypes[0].run(); });
            if (blocEtats[0] != null && blocEtats[0].isManaged() != un) {
                blocEtats[0].setVisible(un);
                blocEtats[0].setManaged(un);
                f.ajuster();
            }
        };
        ScrollPane defTypes = new ScrollPane(types);
        defTypes.setFitToWidth(true);
        defTypes.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        defTypes.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        defTypes.prefViewportHeightProperty().bind(javafx.beans.binding.Bindings.min(220, types.heightProperty()));
        defTypes.setMaxHeight(224);
        Button mettre = CalqueFenetre.bouton("Mettre à ces états", false, () -> { });
        mettre.setTooltip(CalqueFenetre.bulle("Chaque mobi de la zone est mis directement à l'état choisi pour son type, "
                + "sans cliquer à la main. Les mobis déjà à cet état ne bougent pas."));
        mettre.setOnAction(e -> {
            if (envoi[0] || occupe()) { refus("Une action est déjà en cours."); return; }
            java.util.Map<Integer, String> cibles = new java.util.LinkedHashMap<>();
            for (HFloorItem it : mobisAEtat()) {
                Spinner<Integer> sp = voulus.get(it.getTypeId());
                if (sp == null || GroupeFantomes.estFantome(it.getId())) continue;
                String v = String.valueOf(sp.getValue());
                if (!v.equals(Generateur.etatDe(it))) cibles.put(it.getId(), v);
            }
            if (cibles.isEmpty()) return;               // deja tous a l'etat voulu : rien a dire, ca se voit
            envoi[0] = true;
            mettre.setDisable(true);
            changer.setDisable(true);
            pivoter.setDisable(true);
            boolean[] arret = {false};
            Avancement.Suivi av = Avancement.debut("Mise aux états", () -> arret[0] = true).part(-1);
            f.suivre(av);
            Salle.tache("etats-zone-precis", () -> {
                int faux = 0;
                boolean fini = false;
                try { faux = PoseDirecte.etats(Salle.gp(), cibles, () -> arret[0]); fini = true; }
                finally {
                    int n = faux;
                    if (fini) av.fin(cibles.size() - n, n, arret[0]); else av.fin(Avancement.Issue.ECHEC);
                    Platform.runLater(() -> {
                        envoi[0] = false;
                        mettre.setDisable(false);
                        changer.setDisable(false);
                        pivoter.setDisable(false);
                        f.dire("");
                        maj.run();
                        majTypes[0].run();
                        if (n > 0) Journal.erreur(Ui.accorder(n + " mobi(s) sur " + cibles.size()
                                + " n'ont pas pu être mis à l'état voulu (cet état n'existe pas pour ce mobi, ou refusé par le jeu)."));
                    });
                }
            });
        });

        Button autre = CalqueFenetre.bouton("Nouvelle zone", false, () -> { f.fermer(); etatsZone(); });
        blocEtats[0] = Ui.bloc("Mettre à un état précis", defTypes, Ui.boutons(mettre),
                Ui.aide("Pour chaque type de mobi de la zone qui a plusieurs états : choisis son état (0 = le premier, "
                        + "l'image montre le mobi dans cet état), puis « Mettre à ces états ». "
                        + "Par exemple les blocs : directement l'état 4, sans cliquer 4 fois."));
        blocEtats[0].setVisible(false);              // montre des qu'un type a plusieurs etats
        blocEtats[0].setManaged(false);
        majTypes[0].run();
        f.contenu(Ui.bloc("Zone", quoi,
                Ui.aide("« Changer l'état » fait comme un double-clic sur tous les mobis de la zone ; "
                        + "« Pivoter » les fait tourner chacun sur place. Les deux se combinent, dans l'ordre que tu veux "
                        + "(les wired et les dalles magiques ne sont pas touchés).")),
                blocEtats[0]);
        f.boutons(CalqueFenetre.bouton("Fermer", false, f::fermer), autre, pivoter, changer);
        f.montrer();
    }

    /**
     * Une rangee par type de mobi de la zone QUI A PLUSIEURS ETATS : image du
     * mobi dans l'etat choisi, nom ×n, etats actuels, et l'etat voulu (par
     * defaut le plus courant), borne au vrai nombre d'etats (EtatsMobis, lu
     * dans le fichier du mobi, en fond : relancer est appele a chaque reponse).
     * Garde les choix deja faits. Vrai s'il y a au moins une rangee.
     */
    private static boolean remplirTypes(VBox types, java.util.Map<Integer, Spinner<Integer>> voulus, Runnable relancer) {
        java.util.Map<Integer, List<HFloorItem>> parType = new java.util.TreeMap<>();
        for (HFloorItem it : mobisAEtat())
            if (!GroupeFantomes.estFantome(it.getId())) parType.computeIfAbsent(it.getTypeId(), k -> new ArrayList<>()).add(it);
        List<Integer> ordre = new ArrayList<>(parType.keySet());
        java.text.Collator col = java.text.Collator.getInstance(java.util.Locale.FRANCE);
        ordre.sort((a, b) -> col.compare(Salle.nom(a, false), Salle.nom(b, false)));
        List<Node> rangs = new ArrayList<>();
        java.util.Map<Integer, Spinner<Integer>> garde = new java.util.HashMap<>(voulus);
        voulus.clear();
        for (int type : ordre) {
            List<HFloorItem> l = parType.get(type);
            java.util.Map<String, Integer> etats = new java.util.TreeMap<>((a, b) -> {
                try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                catch (NumberFormatException x) { return a.compareTo(b); }
            });
            for (HFloorItem it : l) etats.merge(Generateur.etatDe(it), 1, Integer::sum);
            String courant = null;
            int max = -1;
            for (java.util.Map.Entry<String, Integer> e : etats.entrySet()) if (e.getValue() > max) { max = e.getValue(); courant = e.getKey(); }
            int defaut = 0;
            try { defaut = Integer.parseInt(courant); } catch (Exception ignored) { }
            int vuMax = 0;                                  // plus grand etat deja vu dans la zone
            for (String e : etats.keySet()) try { vuMax = Math.max(vuMax, Integer.parseInt(e)); } catch (NumberFormatException ignored) { }

            String classe = Salle.classe(type, false);
            int revision = PrixTexte.revision(Salle.gp(), false, classe);
            EtatsMobis.Mobi m = EtatsMobis.connu(classe, revision);
            int n;
            if (m == null && revision > 0) {
                EtatsMobis.demander(classe, revision, x -> relancer.run());
                continue;                                   // la rangee viendra avec la reponse
            }
            if (m != null && m.etats > 0) n = Math.max(m.etats, vuMax + 1);
            else n = vuMax >= 1 ? 100 : 1;                  // nombre inconnu : seulement si la zone montre deja un autre etat
            if (n < 2) continue;

            Spinner<Integer> sp = garde.get(type);
            if (sp != null && ((SpinnerValueFactory.IntegerSpinnerValueFactory) sp.getValueFactory()).getMax() != n - 1) sp = null;
            if (sp == null) {
                sp = new Spinner<>(0, n - 1, Math.max(0, Math.min(n - 1, defaut)));
                sp.setEditable(n > 20);                     // nombre inconnu : on peut taper l'etat
                sp.setPrefWidth(74);
                sp.setMinWidth(74);
                sp.setCursor(javafx.scene.Cursor.HAND);
            }
            voulus.put(type, sp);
            List<String> act = new ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : etats.entrySet())
                act.add(e.getKey() + (etats.size() > 1 ? " ×" + e.getValue() : ""));

            // image : le mobi dans l'etat choisi (l'icone en attendant)
            int[] rot = new int[8];
            for (HFloorItem it : l) rot[Math.floorMod(Salle.rotation(it), 8)]++;
            int dir = 0;
            for (int i = 1; i < 8; i++) if (rot[i] > rot[dir]) dir = i;
            javafx.scene.image.ImageView v = new javafx.scene.image.ImageView();
            javafx.scene.image.Image icone = PrixVignettes.icone(classe, revision);
            javafx.scene.image.Image deja = EtatsMobis.dessinee(m, classe, sp.getValue(), dir);
            v.setImage(deja != null ? deja : icone);
            v.setFitWidth(EtatsMobis.TAILLE);
            v.setFitHeight(EtatsMobis.TAILLE);
            v.setPreserveRatio(true);
            v.setSmooth(true);
            javafx.scene.layout.StackPane cadre = new javafx.scene.layout.StackPane(v);
            cadre.setMinSize(EtatsMobis.TAILLE + 4, EtatsMobis.TAILLE + 4);
            cadre.setPrefSize(EtatsMobis.TAILLE + 4, EtatsMobis.TAILLE + 4);
            cadre.setMaxSize(EtatsMobis.TAILLE + 4, EtatsMobis.TAILLE + 4);
            if (m != null && m.dessinable()) {
                int d = dir;
                int[] demande = {0};
                java.util.function.IntConsumer montrer = etat -> {
                    javafx.scene.image.Image i = EtatsMobis.dessinee(m, classe, etat, d);
                    if (i != null) { v.setImage(i); v.setOpacity(1); return; }
                    int k = ++demande[0];
                    v.setOpacity(0.45);                     // on garde l'image precedente, en plus pale, le temps du dessin
                    EtatsMobis.dessiner(m, classe, etat, d, img -> {
                        if (k != demande[0]) return;        // un autre etat a ete choisi entre-temps
                        if (img != null) v.setImage(img);
                        v.setOpacity(1);
                    });
                };
                // le Spinner est garde d'une mise a jour a l'autre : un seul ecouteur, celui de la rangee actuelle
                Object ancien = sp.getProperties().get("etats.ecouteur");
                if (ancien instanceof javafx.beans.value.ChangeListener)
                    sp.valueProperty().removeListener((javafx.beans.value.ChangeListener<? super Integer>) ancien);
                javafx.beans.value.ChangeListener<Integer> ec = (o, a, b) -> { if (b != null) montrer.accept(b); };
                sp.getProperties().put("etats.ecouteur", ec);
                sp.valueProperty().addListener(ec);
                if (deja == null) montrer.accept(sp.getValue());
            }

            Label nom = new Label(Ui.majuscule(Salle.nom(type, false)) + (l.size() > 1 ? "  ×" + l.size() : ""));
            nom.setStyle("-fx-font-weight: bold;");
            nom.setWrapText(true);
            nom.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            String etatsTexte = (etats.size() > 1 ? "États actuels : " : "État actuel : ") + String.join(", ", act);
            if (m != null && m.etats > 0) etatsTexte += Ui.accorder(" (" + n + " état(s) : 0 à " + (n - 1) + ")");
            Label det = Ui.discret(etatsTexte);
            det.setWrapText(true);
            det.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            VBox textes = new VBox(0, nom, det);
            textes.setMinWidth(0);
            textes.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(textes, javafx.scene.layout.Priority.ALWAYS);
            HBox h = new HBox(8, cadre, textes, sp);
            h.setAlignment(Pos.CENTER_LEFT);
            rangs.add(h);
        }
        types.getChildren().setAll(rangs);
        return !rangs.isEmpty();
    }

    // ===================================================== remplacer dans une zone

    private boolean zoneRemplacer = false, deuxiemeRemplacer = false, ecouteRemplacer = false;

    /**
     * Remplacer dans une zone : deux cases dans le jeu (comme etatsZone), puis
     * la fenetre « Remplacer dans la zone » (RemplacerZone) : un type de mobi
     * de la zone est ramasse et remplace par un autre, au meme endroit.
     */
    void remplacerZone() {
        if (RemplacerZone.enCours()) { refus("Un remplacement est déjà en cours."); return; }
        if (!ecouteRemplacer) { ecouteRemplacer = true; Zone.ecouter(() -> Platform.runLater(this::suivreZoneRemplacer)); }
        zoneRemplacer = true;
        zoneEtats = false;
        zoneRamasser = false;
        deuxiemeRemplacer = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    private void suivreZoneRemplacer() {
        if (!zoneRemplacer) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxiemeRemplacer) {
                deuxiemeRemplacer = true;
                InfoJeu.consigne("Choisis le deuxième point de la zone.");
            }
            return;
        }
        zoneRemplacer = false;
        if (!Zone.definie()) return;
        RemplacerZone.fenetre(ouvrir("Remplacer dans la zone"), this::remplacerZone);
    }

    // ===================================================== ramasser dans une zone

    private boolean zoneRamasser = false, deuxiemeRamasser = false, ecouteRamasser = false;

    /** Un autre choix de zone commence (selection par zone du panneau) : on oublie les notres. */
    void oublierChoixZone() { zoneEtats = false; zoneRemplacer = false; zoneRamasser = false; }

    /**
     * Tout ramasser dans une zone : deux cases dans le jeu (coin et coin
     * oppose), puis tous les mobis de sol qui touchent la zone et les muraux
     * accroches au-dessus de ses cases vont dans l'inventaire. Une seule
     * action pour Ctrl+Z ; toujours une fenetre de confirmation avant.
     */
    void ramasserZone() {
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        if (!ecouteRamasser) { ecouteRamasser = true; Zone.ecouter(() -> Platform.runLater(this::suivreZoneRamasser)); }
        zoneRamasser = true;
        zoneEtats = false;
        zoneRemplacer = false;
        deuxiemeRamasser = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Tout ramasser : clique le premier coin de la zone.");
    }

    private void suivreZoneRamasser() {
        if (!zoneRamasser) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxiemeRamasser) {
                deuxiemeRamasser = true;
                InfoJeu.consigne("Clique le coin opposé de la zone.");
            }
            return;
        }
        zoneRamasser = false;
        if (!Zone.definie()) return;
        List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
        int wired = 0;
        for (HFloorItem it : Zone.mobisTouches()) {
            if (GroupeFantomes.estFantome(it.getId()) || GrilleCalcul.estFictif(it.getId())) continue;
            sols.add(it.getId());
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null && Wired.estWired(c)) wired++;
        }
        for (gearth.extensions.parsers.HWallItem w : Salle.murs()) {
            if (GroupeFantomes.estFantome(w.getId())) continue;
            int[] c = GroupeCalcul.caseMur(w.getLocation());
            if (c != null && Zone.contient(c[0], c[1])) murs.add(w.getId());
        }
        String coins = "(" + Zone.minX() + "," + Zone.minY() + ") → (" + Zone.maxX() + "," + Zone.maxY() + ")";
        if (sols.isEmpty() && murs.isEmpty()) { refus("Aucun mobi dans la zone " + coins + "."); return; }
        String v = Groupes.refusVerrouMobis(sols, murs);
        if (v != null) { InfoJeu.consigne(v); return; }
        if (occupe()) { refus("Une action est déjà en cours."); return; }
        int n = sols.size() + murs.size();
        // toujours une confirmation : on ne ramasse pas une zone par erreur
        CalqueFenetre f = ouvrir("Tout ramasser dans la zone ?");
        Label l = new Label(WindowsClavier.texte("Zone " + coins + " : " + GroupePressePapier.mobis(n) + (n > 1 ? " vont être ramassés" : " va être ramassé")
                + (wired > 0 ? ", dont " + wired + " wired : leurs réglages seront perdus" : "")
                + (n > 1 ? ". Cmd+Z les repose ensuite." : ". Cmd+Z le repose ensuite.")));
        l.setWrapText(true);
        l.setMaxWidth(280);
        f.contenu(l);
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Ramasser", true, () -> { });
        ok.getStyleClass().setAll("button", "danger");     // sans retour : rouge
        ok.setOnAction(e -> {
            ok.setDisable(true); annuler.setDisable(true);
            arreter.setVisible(true);
            f.dire("Ramassage…");
            tache = Groupes.ramasserMobis(sols, murs, progression(f, r -> f.fermer()));
        });
        f.boutons(annuler, arreter, ok);
        f.montrer();
    }

    /** Copie tournee : la fenetre de copie, deja tournee et posee a cote (fantomes, puis Poser). */
    private void copieTournee(Groupes.Info i, int quarts) {
        copier("Copie pivotée de « " + i.nom + " »", i.id, null, dessus(i.id), new GroupeCalcul.Transfo(0, 0, quarts, 0));
    }

    /** Idem, avec son pictogramme devant le texte. */
    private static Button petit(String icone, String texte, String aide, Runnable r) {
        Button b = petit(texte, aide, r);
        b.setGraphic(Icones.petite(icone, 15, false));
        b.setGraphicTextGap(5);
        return b;
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
                + ". Cmd+Z les repose ensuite. Pour garder les mobis, fusionne plutôt le calque.");
        l.setWrapText(true);
        l.setMaxWidth(280);
        f.contenu(l);
        Button annuler = CalqueFenetre.bouton("Annuler", false, f::fermer);
        Button arreter = arreter();
        Button ok = CalqueFenetre.bouton("Supprimer", true, () -> { });
        ok.getStyleClass().setAll("button", "danger");     // sans retour : rouge
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
                resultat(r);
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
                // « Mobis : 120/483 » porte deja son compte : pas de second « (120/483) »
                boolean compte = texte != null && texte.matches(".*\\d+\\s*/\\s*\\d+.*");
                f.dire(total > 0 && !compte ? texte + " (" + fait + "/" + total + ")" : texte);
            }
            @Override public void fin(Groupes.Resultat r) {
                resultat(r);
                if (!r.ok && !r.arrete && r.reussis == 0) f.dire(r.message);       // echec : reste lisible dans la fenetre
                if (apres != null) apres.accept(r);
            }
        };
    }
}

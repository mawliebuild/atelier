package atelier;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu « Tâches quotidiennes » (barre du haut) : les 3 taches du jour (et la
 * bonus), leur progression et leur statut, et les boutons pour les faire
 * faire par l'Atelier. Le travail est dans TachesQuotidiennes ; ici, seulement
 * l'affichage, redessine a chaque nouvelle du serveur.
 *
 * Statuts : Réclamée, Faite, En cours (l'Atelier la fait), À faire,
 * Impossible (elle fait dépenser), À faire soi-même (pas automatisable, ou
 * l'Atelier n'a pas reussi).
 */
public class OngletTaches {

    private final VBox liste = new VBox(10);
    private final Button faire = Ui.bouton(Icones.MARCHE, "Faire les tâches cochées");
    private final Button toutCocher = new Button("Tout cocher"), toutDecocher = new Button("Tout décocher");
    private final Button arreter = Ui.bouton(Icones.ARRET, "Arrêter");
    private final Button actualiser = Ui.bouton(Icones.PIVOTER, "Actualiser");
    private final Avancement.Barre avancement = new Avancement.Barre().sansArret();   // « Arrêter » est dans les boutons

    public Tab construire() {
        TachesQuotidiennes.installer();
        TachesQuotidiennes.ecouter(() -> Platform.runLater(this::redessiner));

        faire.getStyleClass().add("primaire");
        faire.setOnAction(e -> TachesQuotidiennes.lancer());
        toutCocher.setOnAction(e -> { for (TachesQuotidiennes.Tache t : TachesQuotidiennes.taches()) TachesQuotidiennes.cocher(t, true); redessiner(); });
        toutDecocher.setOnAction(e -> { for (TachesQuotidiennes.Tache t : TachesQuotidiennes.taches()) TachesQuotidiennes.cocher(t, false); redessiner(); });
        arreter.setOnAction(e -> TachesQuotidiennes.arreter());
        actualiser.setOnAction(e -> TachesQuotidiennes.demander());
        liste.setFillWidth(true);

        VBox racine = Ui.colonne(
                Ui.bloc("Tâches du jour",
                        Ui.aide("L'Atelier fait à ta place les tâches qui ne coûtent rien : il t'emmène chez Ness "
                                + "(:roomid 45273021), double-clique les mobis, s'assied, danse, salue… (tu restes "
                                + "là où la dernière tâche t'a menée). Les tâches qui font dépenser des crédits, duckets ou diamants ne sont "
                                + "jamais faites. Les récompenses sont réclamées à la fin. Décoche celles que tu ne veux pas, "
                                + "ou clique « Faire » sur une ligne pour ne faire que celle-là."),
                        liste, Ui.boutons(toutCocher, toutDecocher)),
                avancement,
                Ui.boutons(faire, arreter, actualiser));
        redessiner();
        // a l'ouverture du menu : la liste du jour, si on ne l'a pas encore
        racine.sceneProperty().addListener((o, a, b) -> {
            if (b != null && !TachesQuotidiennes.listeRecue()) TachesQuotidiennes.demander();
        });

        Tab t = new Tab("Tâches quotidiennes", racine);
        t.setClosable(false);
        return t;
    }

    private void redessiner() {
        boolean actif = TachesQuotidiennes.actif();
        boolean une = false;
        for (TachesQuotidiennes.Tache t : TachesQuotidiennes.taches()) if (faisable(t) && TachesQuotidiennes.cochee(t)) une = true;
        faire.setDisable(actif || !une);
        toutCocher.setDisable(actif);
        toutDecocher.setDisable(actif);
        arreter.setDisable(!actif);
        avancement.suivre(TachesQuotidiennes.suivi());
        List<Node> l = new ArrayList<>();
        List<TachesQuotidiennes.Tache> taches = TachesQuotidiennes.taches();
        if (taches.isEmpty())
            l.add(Ui.discret(TachesQuotidiennes.listeRecue()
                    ? "Aucune tâche quotidienne aujourd'hui."
                    : "Pas encore de liste : ouvre Missions > Tâches quotidiennes dans le jeu, ou clique sur Actualiser."));
        for (TachesQuotidiennes.Tache t : taches) l.add(ligne(t, actif));
        liste.getChildren().setAll(l);
    }

    /** L'Atelier peut-il la faire : pas encore faite, automatisable, sans depense, pas ratee. */
    private static boolean faisable(TachesQuotidiennes.Tache t) {
        TachesQuotidiennes.Action x = t.action();
        return t.statut == TachesQuotidiennes.EN_COURS && x.genre() != TachesQuotidiennes.Genre.DEPENSE
                && x.genre() != TachesQuotidiennes.Genre.SOI_MEME && x.automatique();
    }

    private Node ligne(TachesQuotidiennes.Tache t, boolean actif) {
        TachesQuotidiennes.Action x = t.action();
        boolean faisable = faisable(t);
        CheckBox coche = new CheckBox();
        coche.setSelected(faisable && TachesQuotidiennes.cochee(t));
        coche.setDisable(!faisable || actif);
        coche.setFocusTraversable(false);
        Ui.bulle(coche, faisable ? "Cochée : faite par « Faire les tâches cochées » (choix retenu pour les prochains jours)."
                : "L'Atelier ne peut pas la faire.");
        coche.selectedProperty().addListener((o, a, b) -> { TachesQuotidiennes.cocher(t, b); redessiner(); });
        Button seule = new Button("Faire");
        seule.setMinWidth(Region.USE_PREF_SIZE);
        seule.setDisable(!faisable || actif);
        Ui.bulle(seule, "Faire seulement cette tâche.");
        seule.setOnAction(e -> TachesQuotidiennes.lancer(java.util.Set.of(t.id)));
        Label nom = Ui.etiquette(x.nom() + (t.bonus ? " (bonus)" : t.ancienne() ? " (jour passé)" : ""));
        nom.setWrapText(true);
        nom.setMinHeight(Region.USE_PREF_SIZE);
        nom.setMaxWidth(Double.MAX_VALUE);

        Label statut = new Label(statut(t));
        statut.setMinWidth(Region.USE_PREF_SIZE);
        statut.setMaxWidth(Region.USE_PREF_SIZE);
        statut.getStyleClass().addAll("badge", classe(t));
        // le statut sous le nom : sur la meme ligne, il ne laissait au nom qu'une colonne etroite
        VBox textes = new VBox(3, nom, statut);
        textes.setMinWidth(0);
        textes.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textes, Priority.ALWAYS);
        HBox tete = new HBox(8, coche, textes, seule);
        tete.setAlignment(Pos.TOP_LEFT);

        int requis = Math.max(1, t.requis);
        ProgressBar barre = new ProgressBar(t.statut != TachesQuotidiennes.EN_COURS ? 1 : Math.min(1, t.faits / (double) requis));
        barre.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(barre, Priority.ALWAYS);
        if (t.statut != TachesQuotidiennes.EN_COURS) Avancement.colorer(barre, Avancement.Issue.REUSSI);   // faite : verte
        Label compte = new Label(Math.min(t.faits, requis) + " / " + requis);
        compte.setMinWidth(Region.USE_PREF_SIZE);
        HBox progression = new HBox(8, barre, compte);
        progression.setAlignment(Pos.CENTER_LEFT);

        VBox v = new VBox(4, tete, progression);
        if (t.statut == TachesQuotidiennes.EN_COURS) v.getChildren().add(Ui.discret(x.consigne()));
        v.setFillWidth(true);
        return v;
    }

    private static String statut(TachesQuotidiennes.Tache t) {
        if (t.statut == TachesQuotidiennes.RECLAMEE) return "Réclamée";
        if (t.statut == TachesQuotidiennes.FAITE) return "Faite";
        TachesQuotidiennes.Action x = t.action();
        if (x.genre() == TachesQuotidiennes.Genre.DEPENSE) return "Impossible (dépense)";
        if (!x.automatique() || TachesQuotidiennes.echec(t)) return "À faire soi-même";
        if (TachesQuotidiennes.enCours() == t.id) return "En cours";
        return "À faire";
    }

    /** Badge du statut : vert (faite), orange (a faire, en cours), rouge (impossible, a faire soi-meme). */
    private static String classe(TachesQuotidiennes.Tache t) {
        if (t.statut != TachesQuotidiennes.EN_COURS) return "badge-ok";
        TachesQuotidiennes.Action x = t.action();
        if (x.genre() == TachesQuotidiennes.Genre.DEPENSE || !x.automatique() || TachesQuotidiennes.echec(t)) return "badge-echec";
        return "badge-attente";
    }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Volet « Grille dans le jeu » : une grille VISIBLE DANS HABBO, chez toi
 * seulement. L'Atelier envoie a ton jeu des mobis fictifs (une dalle plate
 * 1×1 par case) que le serveur ne connait pas ; personne d'autre ne les voit
 * et recharger la salle les fait disparaitre.
 *
 *   - grille entiere, ou seulement la zone choisie, ou une case sur deux ;
 *   - couleur selon la hauteur du sol (modeles a plusieurs couleurs) ;
 *   - apercu dans le jeu des cases que l'editeur de floor va changer, avant
 *     « Valider » (posees a leur NOUVELLE hauteur, mise a jour automatique).
 *
 * Tout se met a jour seul quand un reglage, la zone ou l'edition change ; tout
 * est oublie au changement de salle, au rechargement et a la deconnexion.
 * Logique : GrilleCalcul (pure, testee) ; paquets et ecoutes : GrilleReseau.
 */
public class GrilleJeu {

    /** Au-dela, on demande de confirmer (aperçu chiffré). */
    private static final int SEUIL_CONFIRMATION = 1500;

    private final ComboBox<GrilleReseau.Modele> modele = new ComboBox<>();
    private final RadioButton tout = new RadioButton("Tout le plan");
    private final RadioButton zone = new RadioButton("Zone seulement");
    private final CheckBox damier = new CheckBox("Une case sur deux");
    private final CheckBox couleurs = new CheckBox("Couleur selon la hauteur");
    private final CheckBox dessus = new CheckBox("Par-dessus les mobis");
    private final CheckBox apercu = new CheckBox("Montrer dans le jeu les cases que l'éditeur va changer");
    private final Label etatGrille = Ui.valeur("Grille cachée.");
    private final Label prevision = Ui.discret("");
    private final Label etatApercu = Ui.discret("");
    private final Label etat = Ui.etat();
    private final Button afficher = new Button("Afficher la grille");
    private final Button retirer = new Button("Retirer");
    private final Button arreter = new Button("Arrêter");
    private final ProgressBar barre = new ProgressBar(0);

    private boolean grilleVoulue = false, confirmer = false;
    private volatile boolean travail = false, aRefaire = false;
    private final boolean[] arret = {false};
    private final PauseTransition attente = new PauseTransition(Duration.millis(500));
    private final PauseTransition attenteApercu = new PauseTransition(Duration.millis(700));

    public Tab construire() {
        ScrollPane sp = new ScrollPane(volet());
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Grille dans le jeu", sp);
        t.setClosable(false);
        return t;
    }

    private VBox volet() {
        modele.setPromptText("En attente de la furnidata…");
        modele.setTooltip(new Tooltip("Le mobi utilisé comme marqueur (doit exister dans la furnidata)."));
        ToggleGroup ou = new ToggleGroup();
        tout.setToggleGroup(ou);
        zone.setToggleGroup(ou);
        tout.setSelected(true);
        damier.setTooltip(new Tooltip("Moitié moins de marqueurs : plus léger, et on compte les cases d'un coup d'œil."));
        couleurs.setTooltip(new Tooltip("Une couleur par hauteur de sol (le modèle doit avoir plusieurs couleurs)."));
        dessus.setTooltip(new Tooltip("Pose le marqueur sur le haut de la pile de mobis au lieu du sol."));

        afficher.getStyleClass().add("primaire");
        afficher.setOnAction(e -> cliquerAfficher());
        retirer.setOnAction(e -> { grilleVoulue = false; confirmer = false; lancer(); });
        arreter.setOnAction(e -> arret[0] = true);
        arreter.setDisable(true);
        barre.setMaxWidth(Double.MAX_VALUE);
        barre.setVisible(false);
        barre.setManaged(false);

        VBox blocGrille = Ui.bloc("Grille dans le jeu", etatGrille, prevision,
                Ui.ligne(afficher, retirer, arreter), barre,
                Ui.aide("Des dalles fictives, chez toi seulement : le serveur et les autres ne voient rien. "
                        + "Recharger la salle les fait disparaître."));

        VBox blocZone = Zone.bloc();
        blocZone.visibleProperty().bind(zone.selectedProperty());
        blocZone.managedProperty().bind(zone.selectedProperty());

        VBox blocReglages = Ui.bloc("Réglages",
                Ui.ligne(new Label("Marqueur"), modele),
                Ui.ligne(tout, zone),
                Ui.ligne(damier, couleurs),
                Ui.ligne(dessus));

        apercu.setWrapText(true);
        VBox blocApercu = Ui.bloc("Aperçu de l'éditeur de floor", apercu, etatApercu,
                Ui.aide("Avant « Valider » : une dalle fictive sur chaque case changée, posée à sa NOUVELLE hauteur "
                        + "(à l'ancienne pour une case supprimée). Couleurs : 1re = ajoutée, 2e = montée, "
                        + "3e = descendue, 4e = supprimée (selon le marqueur). Une case ajoutée hors du sol actuel "
                        + "peut ne pas s'afficher."));

        VBox v = Ui.colonne(blocGrille, blocReglages, blocZone, blocApercu, etat);

        // Tout reglage relance la mise a jour (si la grille est affichee) et la prevision.
        Runnable change = () -> { confirmer = false; majPrevision(); if (grilleVoulue) attente.playFromStart(); };
        for (Toggle t : List.of(tout, zone)) ((RadioButton) t).selectedProperty().addListener((o, a, b) -> change.run());
        for (CheckBox c : List.of(damier, couleurs, dessus)) c.selectedProperty().addListener((o, a, b) -> change.run());
        modele.valueProperty().addListener((o, a, b) -> {
            // Changer de marqueur : tout est renvoye (autre type).
            if (a != null && b != null && a != b && !GrilleReseau.affiches.isEmpty()) toutRenvoyer = true;
            change.run();
            if (apercu.isSelected()) attenteApercu.playFromStart();
        });
        Zone.ecouter(() -> { if (zone.isSelected()) change.run(); });
        attente.setOnFinished(e -> lancer());
        attenteApercu.setOnFinished(e -> lancer());
        apercu.selectedProperty().addListener((o, a, b) -> lancer());

        GrilleReseau.ecouter(() -> Platform.runLater(this::surReseau));
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> tic()));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        majBoutons();
        return v;
    }

    private boolean toutRenvoyer = false;
    private boolean sessionEcoutee = false;
    private int salleVue = -1;

    // ------------------------------------------------------------- automatique

    private void tic() {
        if (modele.getItems().isEmpty() && GrilleReseau.resoudre()) remplirModeles();
        // L'apercu suit l'editeur : on s'abonne des que sa session existe.
        FloorSession s = EditeurFloor.session();
        if (s != null && !sessionEcoutee) {
            sessionEcoutee = true;
            s.ecouter(() -> { majApercu(); if (apercu.isSelected()) attenteApercu.playFromStart(); });
        }
        // Changement de salle, sortie, deconnexion : le client a tout jete.
        int ici = GrilleReseau.salleCourante();
        if (ici != salleVue) {
            if (salleVue != -1 || ici == -1) {
                if (!GrilleReseau.affiches.isEmpty())
                    GrilleReseau.oublier(ici == -1 ? "Tu n'es plus dans une salle : la grille est retirée."
                            : "Tu as changé de salle : la grille est retirée.");
                if (grilleVoulue || apercu.isSelected()) {
                    grilleVoulue = false;
                    apercu.setSelected(false);
                    etat.setText(ici == -1 ? "Tu n'es plus dans une salle : la grille est retirée."
                            : "Tu as changé de salle : la grille est retirée.");
                }
            }
            salleVue = ici;
            majPrevision();
        }
        majBoutons();
    }

    private void surReseau() {
        String ev = GrilleReseau.evenement;
        if (ev != null) {
            GrilleReseau.evenement = null;
            etat.setText(ev);
            if (GrilleReseau.affiches.isEmpty() && !travail) {
                grilleVoulue = false;
                if (apercu.isSelected()) apercu.setSelected(false);
            }
        }
        majBoutons();
    }

    private void remplirModeles() {
        List<GrilleReseau.Modele> l = new ArrayList<>();
        for (GrilleReseau.Modele m : GrilleReseau.MODELES) if (m.disponible()) l.add(m);
        modele.getItems().setAll(l);
        if (l.isEmpty()) {
            modele.setPromptText("Aucun marqueur trouvé");
            etat.setText("Aucun des mobis marqueurs n'est dans la furnidata : grille impossible.");
        } else modele.getSelectionModel().select(0);
        modele.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(GrilleReseau.Modele m, boolean vide) {
                super.updateItem(m, vide);
                setText(vide || m == null ? null : m.nom + (m.variantes() > 1 ? " · " + m.variantes() + " couleurs" : ""));
                setTooltip(vide || m == null ? null : new Tooltip(m.aide));
            }
        });
        majPrevision();
    }

    // ------------------------------------------------------------- calculs

    private GrilleCalcul.Reglages reglages() {
        GrilleCalcul.Reglages r = new GrilleCalcul.Reglages();
        r.zoneSeulement = zone.isSelected();
        if (r.zoneSeulement) {
            r.zx1 = Zone.minX(); r.zy1 = Zone.minY(); r.zx2 = Zone.maxX(); r.zy2 = Zone.maxY();
        }
        r.damier = damier.isSelected();
        r.couleurHauteur = couleurs.isSelected();
        GrilleReseau.Modele m = modele.getValue();
        r.variantes = m == null ? 1 : m.variantes();
        r.dessusMobis = dessus.isSelected();
        return r;
    }

    /** Plan du sol de la salle, [x][y] -> hauteur, -1 = vide ; null hors salle. */
    static int[][] planSalle() {
        try {
            game.FloorState s = Salle.etat();
            if (s == null) return null;
            FloorModele m = FloorModele.depuisTexte(s.getRawFloorplan());
            return m == null ? null : m.h;
        } catch (Throwable t) { return null; }
    }

    static List<GrilleCalcul.Pose> poses() {
        List<GrilleCalcul.Pose> l = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            try {
                if (GrilleCalcul.estFictif(it.getId())) continue;
                int[] e = Salle.emprise(it);
                l.add(new GrilleCalcul.Pose(it.getTile().getX(), it.getTile().getY(), e[0], e[1],
                        it.getTile().getZ(), Salle.hauteur(it)));
            } catch (Throwable ignored) { }
        }
        return l;
    }

    private int prevu = 0;

    private void majPrevision() {
        if (zone.isSelected() && !Zone.definie()) {
            prevu = 0;
            prevision.setText("Choisis d'abord une zone (dans le jeu : deux clics au sol).");
            majBoutons();
            return;
        }
        int[][] p = planSalle();
        if (p == null) { prevu = 0; prevision.setText("Entre dans une salle pour afficher la grille."); majBoutons(); return; }
        GrilleCalcul.Reglages r = reglages();
        prevu = GrilleCalcul.grille(p, r, r.dessusMobis ? poses() : null).size();
        prevision.setText(prevu + (prevu > 1 ? " dalles fictives" : " dalle fictive") + " · environ "
                + Math.max(1, Math.round(prevu / (double) GrilleReseau.PAR_LOT * GrilleReseau.PAUSE / 1000.0)) + " s d'envoi.");
        majBoutons();
    }

    private void majApercu() {
        FloorSession s = EditeurFloor.session();
        if (s == null || s.original == null || s.courant == null) { etatApercu.setText("Ouvre l'éditeur de floor et lis la salle."); return; }
        int n = GrilleCalcul.apercu(s.original.h, s.courant.h).size();
        etatApercu.setText(n == 0 ? "Aucune case changée dans l'éditeur pour l'instant."
                : n + (n > 1 ? " cases changées" : " case changée") + " dans l'éditeur"
                + (apercu.isSelected() ? " · " + GrilleReseau.nombre(GrilleCalcul.APERCU) + " affichée(s) dans le jeu." : "."));
    }

    // ------------------------------------------------------------- actions

    private void cliquerAfficher() {
        if (modele.getValue() == null) { etat.setText("Pas de marqueur disponible (furnidata pas encore prête ?)."); return; }
        if (!Salle.dansUneSalle()) { etat.setText("Entre d'abord dans une salle."); return; }
        if (zone.isSelected() && !Zone.definie()) { etat.setText("Choisis d'abord une zone, ou « Tout le plan »."); return; }
        majPrevision();
        if (prevu > SEUIL_CONFIRMATION && !confirmer && !grilleVoulue) {
            confirmer = true;
            etat.setText(prevu + " dalles, c'est beaucoup pour le jeu. Clique « Confirmer » pour les afficher, "
                    + "ou choisis une zone / une case sur deux.");
            majBoutons();
            return;
        }
        confirmer = false;
        grilleVoulue = true;
        lancer();
    }

    /** Calcule ce qu'on veut afficher, et envoie la difference (un seul travail a la fois). */
    private void lancer() {
        if (travail) { aRefaire = true; return; }
        GrilleReseau.Modele mod = modele.getValue();
        if (mod == null) { majBoutons(); return; }
        boolean veutGrille = grilleVoulue;
        GrilleCalcul.Reglages r = reglages();
        boolean veutApercu = apercu.isSelected();
        FloorSession s = EditeurFloor.session();
        int[][] avant = null, apres = null;
        if (veutApercu && s != null && s.original != null && s.courant != null) {
            avant = copie(s.original.h); apres = copie(s.courant.h);
            if (s.salleId != GrilleReseau.salleCourante()) {
                etat.setText("L'éditeur charge le plan de la nouvelle salle…");
                avant = apres = null;
            }
        }
        boolean renvoyer = toutRenvoyer;
        toutRenvoyer = false;
        final int[][] fa = avant, fp = apres;
        travail = true;
        arret[0] = false;
        majBoutons();
        Salle.tache("grille-envoi", () -> {
            String fin;
            try {
                fin = travailler(mod, veutGrille, r, veutApercu, fa, fp, renvoyer);
            } catch (Throwable t) {
                fin = "Grille : erreur " + t.getMessage();
            }
            String message = fin;
            Platform.runLater(() -> {
                travail = false;
                barre.setVisible(false); barre.setManaged(false);
                if (message != null) etat.setText(message);
                majApercu();
                majBoutons();
                if (aRefaire) { aRefaire = false; lancer(); }
            });
        });
    }

    /** Hors fil FX. Renvoie le message de fin. */
    private String travailler(GrilleReseau.Modele mod, boolean veutGrille, GrilleCalcul.Reglages r,
                              boolean veutApercu, int[][] avant, int[][] apres, boolean renvoyer) {
        if (Salle.gp() == null) return "G-Presets n'est pas encore prêt.";
        if (!GrilleReseau.affiches.isEmpty() && GrilleReseau.salle != GrilleReseau.salleCourante())
            GrilleReseau.affiches.clear();   // autre salle : le client a deja tout jete
        if ((veutGrille || veutApercu) && GrilleReseau.collision())
            return "Un vrai mobi de la salle utilise un identifiant réservé à la grille : grille refusée par prudence.";
        if (renvoyer) {
            GrilleCalcul.Diff tout = new GrilleCalcul.Diff();
            tout.retirer.addAll(GrilleReseau.affiches.keySet());
            GrilleReseau.appliquer(tout, mod, arret, n -> { });
        }

        List<GrilleCalcul.Marqueur> grille = new ArrayList<>();
        if (veutGrille) {
            int[][] p = planSalle();
            if (p == null) return "Pas dans une salle : grille impossible.";
            grille = GrilleCalcul.grille(p, r, r.dessusMobis ? poses() : null);
        }
        List<GrilleCalcul.Marqueur> ap = veutApercu && avant != null ? GrilleCalcul.apercu(avant, apres) : List.of();

        java.util.Map<Integer, GrilleCalcul.Marqueur> vus = new java.util.HashMap<>(GrilleReseau.affiches);
        GrilleCalcul.Diff dg = GrilleCalcul.diff(vus, grille, GrilleCalcul.GRILLE);
        GrilleCalcul.Diff da = GrilleCalcul.diff(vus, ap, GrilleCalcul.APERCU);
        int total = dg.total() + da.total();
        if (total == 0) return null;
        Platform.runLater(() -> {
            barre.setProgress(0);
            barre.setVisible(total > GrilleReseau.PAR_LOT); barre.setManaged(total > GrilleReseau.PAR_LOT);
        });
        int fait1 = dg.total();
        int ok = GrilleReseau.appliquer(dg, mod, arret, n -> Platform.runLater(() -> barre.setProgress(n / (double) total)));
        ok += GrilleReseau.appliquer(da, mod, arret, n -> Platform.runLater(() -> barre.setProgress((fait1 + n) / (double) total)));

        int g = GrilleReseau.nombre(GrilleCalcul.GRILLE), a = GrilleReseau.nombre(GrilleCalcul.APERCU);
        // Verification : ce qu'on croit affiche correspond-il a ce qu'on voulait ?
        String verif = (veutGrille && g != grille.size()) || (veutApercu && a != ap.size())
                ? " Attention : " + (grille.size() + ap.size() - g - a) + " envoi(s) n'ont pas abouti." : "";
        if (arret[0]) return "Arrêté : " + g + " dalle(s) de grille et " + a + " d'aperçu affichée(s)." + verif;
        if (ok < total) verif += " (" + (total - ok) + " envoi(s) refusé(s) par G-Earth.)";
        String s = (g == 0 && a == 0) ? "Grille retirée du jeu."
                : (g > 0 ? g + " dalle(s) de grille" : "") + (g > 0 && a > 0 ? " et " : "")
                + (a > 0 ? a + " case(s) d'aperçu" : "") + " affichée(s) dans le jeu.";
        if (GrilleReseau.vusParGPresets)
            s += " G-Presets voit ces dalles : les autres outils les ignorent seulement s'ils filtrent les identifiants de la grille.";
        return s + verif;
    }

    private static int[][] copie(int[][] h) {
        int[][] c = new int[h.length][];
        for (int i = 0; i < h.length; i++) c[i] = h[i].clone();
        return c;
    }

    private void majBoutons() {
        int g = GrilleReseau.nombre(GrilleCalcul.GRILLE);
        etatGrille.setText(g == 0 ? "Grille cachée." : "Grille affichée : " + g + (g > 1 ? " dalles." : " dalle."));
        boolean pret = modele.getValue() != null && Salle.dansUneSalle();
        afficher.setText(confirmer ? "Confirmer : " + prevu + " dalles"
                : grilleVoulue ? "Grille affichée" : "Afficher la grille" + (prevu > 0 ? " (" + prevu + ")" : ""));
        afficher.setDisable(travail || !pret || grilleVoulue || prevu == 0);
        retirer.setDisable(travail || (g == 0 && !grilleVoulue));
        arreter.setDisable(!travail);
        apercu.setDisable(modele.getValue() == null);
    }
}

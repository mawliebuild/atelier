package atelier;

import gearth.extensions.parsers.HPoint;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Escalier en petits blocs (bc_block_small*1..69, couleur choisie comme au
 * catalogue BC) : chaque marche un peu plus loin et un peu plus haut.
 * Pose directe (PoseDirecte) : les blocs partent en rafale et chacun recoit son
 * altitude (@altitude) des qu'il apparait ; pas de dalle magique.
 */
public class OutilEscalier {

    private PaletteBlocs palette;
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private Generateur.ChoixSource source;
    private Spinner<Integer> marches, largeur, pas;
    private Spinner<Double> montee, hauteurMobi;
    private ComboBox<String> rotation;
    private RadioButton xPlus, xMoins, yPlus, yMoins;
    private CheckBox rampe, remplir;
    private Label apercu, departLbl, etat;
    private volatile HPoint depart;
    private volatile boolean attenteDepart = false;
    /** Pipette : le prochain clic sur un mobi de la salle choisit le petit bloc. */
    private volatile boolean attentePipette = false;
    private Button pipette;

    public Tab construire() {
        etat = Ui.etat();
        palette = new PaletteBlocs(PREFS.getInt("escalier.bloc", 14));
        source = new Generateur.ChoixSource();

        marches = Generateur.entier(1, 200, 8);
        largeur = Generateur.entier(1, 30, 1);
        pas = Generateur.entier(1, 20, 1);
        montee = Generateur.decimal(0.01, 20, 0.5, 0.25);
        hauteurMobi = Generateur.decimal(0, 20, 0, 0.05);

        rotation = new ComboBox<>();
        rotation.getItems().addAll("0 (nord)", "2 (est)", "4 (sud)", "6 (ouest)");
        rotation.getSelectionModel().select(0);

        ToggleGroup g = new ToggleGroup();
        xPlus  = Generateur.radio("x +", g, true);
        xMoins = Generateur.radio("x −", g, false);
        yPlus  = Generateur.radio("y +", g, false);
        yMoins = Generateur.radio("y −", g, false);

        rampe = new CheckBox("Rampe (pas 1, montée fine)");
        remplir = new CheckBox("Remplir dessous (escalier plein)");
        rampe.setWrapText(true); remplir.setWrapText(true);

        departLbl = Ui.valeur("Au clic dans le jeu, après « Poser »");
        departLbl.setWrapText(true);
        Button choisirDepart = Icones.sur(new Button("Choisir la case de départ"), Icones.CIBLE);
        choisirDepart.setOnAction(e -> {
            attenteDepart = true;
            departLbl.setText("Clique la case de la 1re marche dans le jeu...");
        });
        Button oublier = Icones.sur(new Button("Oublier"), Icones.VIDER);
        oublier.setOnAction(e -> { depart = null; attenteDepart = false;
            departLbl.setText("Au clic dans le jeu, après « Poser »"); });
        Salle.surClicCase(c -> {
            if (!attenteDepart) return;
            attenteDepart = false;
            depart = c;
            Platform.runLater(() -> departLbl.setText("1re marche en (" + c.getX() + "," + c.getY() + ")"));
        });

        pipette = Icones.sur(new Button("Pipette"), Icones.PIPETTE);
        pipette.setTooltip(new Tooltip("Clique ensuite un petit bloc dans le jeu : sa couleur est choisie ici. Re-clique pour annuler."));
        pipette.setOnAction(e -> {
            attentePipette = !attentePipette;
            pipette.setText(attentePipette ? "Annuler la pipette" : "Pipette");
            InfoJeu.consigne(attentePipette ? "Clique un petit bloc dans le jeu pour prendre sa couleur." : "Pipette annulée.");
        });
        Salle.surClicMobi(it -> {
            if (!attentePipette) return;
            attentePipette = false;
            int n = numeroBloc(Salle.classe(it.getTypeId(), false));
            Platform.runLater(() -> {
                pipette.setText("Pipette");
                if (n > 0) palette.choisir(n);             // surChoix retient la preference
            });
            if (n <= 0) InfoJeu.consigne("Ce n'est pas un petit bloc : choisis-en un dans la palette.");
        });

        apercu = Ui.valeur("—");
        apercu.setWrapText(true);

        // l'apercu suit tous les reglages
        palette.surChoix(n -> {
            PREFS.putInt("escalier.bloc", n);
            Generateur.Modele m = modele();
            if (m != null && m.hauteur > 0) hauteurMobi.getValueFactory().setValue(m.hauteur);
            ajusterPas();
            majApercu();
        });
        Generateur.Modele m0 = modele();
        if (m0 != null && m0.hauteur > 0) hauteurMobi.getValueFactory().setValue(m0.hauteur);
        g.selectedToggleProperty().addListener((o, a, b) -> { ajusterPas(); majApercu(); });
        rotation.valueProperty().addListener((o, a, b) -> { ajusterPas(); majApercu(); });
        rampe.selectedProperty().addListener((o, a, b) -> {
            pas.setDisable(b);
            if (b) {
                pas.getValueFactory().setValue(1);
                double h = hauteurMobi.getValue();
                montee.getValueFactory().setValue(h > 0 && h <= 1 ? Generateur.arrondi(h / 4) : 0.25);
            } else ajusterPas();
            majApercu();
        });
        for (Spinner<?> s : List.of(marches, largeur, pas, montee, hauteurMobi))
            s.valueProperty().addListener((o, a, b) -> majApercu());
        remplir.selectedProperty().addListener((o, a, b) -> majApercu());

        Tab t = new Tab("Escalier", Generateur.defiler(
                Ui.aide("Un escalier en petits blocs de la couleur choisie : chaque bloc est posé "
                        + "puis mis à sa hauteur exacte, sans dalle magique."),
                Ui.bloc("Couleur des petits blocs", palette.vue(), Ui.ligne(pipette)),
                Ui.bloc("Marches",
                        formulaire("Nombre", marches, "Largeur (cases)", largeur, "Pas (cases)", pas,
                                "Montée", montee, "Hauteur du mobi", hauteurMobi),
                        Ui.aide("Montée : hauteur gagnée à chaque marche (0,25 / 0,5 / 1,0…). "
                                + "Hauteur du mobi : lue sur le mobi cliqué, sert à « remplir dessous »."),
                        rampe, remplir),
                Ui.bloc("Direction et rotation",
                        formulaire("Sens", Ui.ligne(xPlus, xMoins, yPlus, yMoins), "Rotation", rotation)),
                Ui.bloc("Départ", departLbl, Ui.ligne(choisirDepart, oublier),
                        Ui.aide("Sans case de départ, tu cliques dans le jeu après « Poser » : "
                                + "le clic donne le coin haut-gauche (x min, y min) de l'escalier. "
                                + "Ton avatar ne bouge pas.")),
                source.bloc(),
                Ui.bloc("Aperçu", apercu),
                Icones.sur(Generateur.principal("Poser l'escalier", this::poser), Icones.ESCALIER),
                etat));
        t.setClosable(false);
        majApercu();
        return t;
    }

    /**
     * Les reglages en formulaire : le nom a gauche (une colonne alignee), le
     * champ a droite. Chaque paire reste ensemble, rien ne passe a la ligne
     * au milieu d'une paire (« Nombre » d'un cote, son champ de l'autre).
     */
    private static javafx.scene.layout.GridPane formulaire(Object... paires) {
        javafx.scene.layout.GridPane g = new javafx.scene.layout.GridPane();
        g.setHgap(10); g.setVgap(6);
        javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
        c0.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        c1.setFillWidth(false);
        g.getColumnConstraints().addAll(c0, c1);
        for (int i = 0; i + 1 < paires.length; i += 2) {
            Label l = Ui.etiquette((String) paires[i]);
            l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            javafx.scene.Node n = (javafx.scene.Node) paires[i + 1];
            if (n instanceof Spinner) ((Spinner<?>) n).setPrefWidth(96);   // tous les champs de meme largeur
            if (n instanceof javafx.scene.layout.Region && !(n instanceof Control))
                javafx.scene.layout.GridPane.setFillWidth(n, true);
            g.addRow(i / 2, l, n);
        }
        return g;
    }

    /** N de « bc_block_small*N » (1 a 69) ; 0 si ce n'est pas un petit bloc. */
    static int numeroBloc(String classe) {
        if (classe == null || !classe.startsWith("bc_block_small*")) return 0;
        try {
            int n = Integer.parseInt(classe.substring("bc_block_small*".length()));
            return n >= 1 && n <= PaletteBlocs.COULEURS.length ? n : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------ calcul

    private int rot() { return 2 * Math.max(0, rotation.getSelectionModel().getSelectedIndex()); }

    /** Direction de montee {dx, dy}. */
    private int[] dir() {
        if (xMoins.isSelected()) return new int[]{-1, 0};
        if (yPlus.isSelected())  return new int[]{0, 1};
        if (yMoins.isSelected()) return new int[]{0, -1};
        return new int[]{1, 0};
    }

    /** Pas par defaut : l'emprise du mobi dans le sens de la montee. */
    private void ajusterPas() {
        if (rampe.isSelected()) return;
        Generateur.Modele m = modele();
        if (m == null) return;
        int[] e = m.emprise(rot());
        pas.getValueFactory().setValue(dir()[0] != 0 ? e[0] : e[1]);
    }

    private List<Generateur.Mobi> plan(Generateur.Modele m) {
        List<Generateur.Mobi> r = new ArrayList<>();
        int[] d = dir();
        int[] e = m.emprise(rot());
        int lat = d[0] != 0 ? e[1] : e[0];               // pas perpendiculaire
        int px = d[0] != 0 ? 0 : 1, py = d[0] != 0 ? 1 : 0;
        int n = marches.getValue(), l = largeur.getValue(), p = pas.getValue();
        double mo = montee.getValue(), h = hauteurMobi.getValue();
        boolean plein = remplir.isSelected() && h > 0;
        for (int i = 0; i < n; i++) {
            double z = Generateur.arrondi(i * mo);
            for (int k = 0; k < l; k++) {
                int x = i * p * d[0] + k * lat * px;
                int y = i * p * d[1] + k * lat * py;
                r.add(new Generateur.Mobi(m.classe, m.etat, x, y, z, rot()));
                if (plein && z > 0.005) {
                    double zz = z - h;
                    while (zz > 0.005) {
                        r.add(new Generateur.Mobi(m.classe, m.etat, x, y, Generateur.arrondi(zz), rot()));
                        zz -= h;
                    }
                    r.add(new Generateur.Mobi(m.classe, m.etat, x, y, 0, rot()));
                }
            }
        }
        return r;
    }

    /** Le petit bloc choisi (classe, emprise, hauteur lues dans la furnidata). */
    private Generateur.Modele modele() {
        try { return Generateur.modele(palette.classe(), "0", 0); } catch (Throwable t) { return null; }
    }

    private void majApercu() {
        Generateur.Modele m = modele();
        if (m == null) { apercu.setText("Liste des mobis pas encore chargée."); return; }
        List<Generateur.Mobi> p = plan(m);
        double h = hauteurMobi.getValue();
        double derniere = Generateur.arrondi((marches.getValue() - 1) * montee.getValue());
        StringBuilder sb = new StringBuilder();
        sb.append(p.size()).append(" mobi(s) nécessaires\n");
        sb.append(String.format(Locale.ROOT, "Dernière marche posée à %.2f", derniere));
        if (h > 0) sb.append(String.format(Locale.ROOT, ", sommet ≈ %.2f", derniere + h));
        int longueur = (marches.getValue() - 1) * pas.getValue() + 1;
        sb.append("\nLongueur ≈ ").append(longueur).append(" case(s)");
        if (remplir.isSelected() && h <= 0) sb.append("\n⚠ Remplir dessous : indique la hauteur du mobi.");
        if (!m.empilable) sb.append("\n⚠ Ce mobi n'est pas empilable : les marches hautes risquent d'échouer.");
        if (p.size() > 300) sb.append("\n⚠ Beaucoup de mobis : la pose sera longue.");
        apercu.setText(sb.toString());
    }

    // ------------------------------------------------------------ pose

    private void poser() {
        Generateur.prendre(marches); Generateur.prendre(largeur); Generateur.prendre(pas);
        Generateur.prendre(montee); Generateur.prendre(hauteurMobi);
        Generateur.Modele m = modele();
        if (m == null) { Journal.erreur("Liste des mobis pas encore chargée : réessaie dans un instant."); return; }
        if (remplir.isSelected() && hauteurMobi.getValue() <= 0) {
            Journal.erreur("Remplir dessous : indique la hauteur du bloc."); return;
        }
        List<Generateur.Mobi> p = plan(m);
        Generateur.Source src = source.source();
        HPoint dep = depart;
        Salle.tache("escalier", () -> {
            HPoint d = dep;
            if (d == null) {
                InfoJeu.consigne("Clique dans le jeu la case de la 1re marche.");
                // empreinte de l'escalier sous la souris (si la 1re marche est bien le coin du haut)
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
                for (Generateur.Mobi b : p) {
                    x0 = Math.min(x0, b.x); y0 = Math.min(y0, b.y);
                    x1 = Math.max(x1, b.x); y1 = Math.max(y1, b.y);
                }
                d = !p.isEmpty() && x0 == 0 && y0 == 0
                        ? Generateur.Dalle.attendreClic(120_000, x1 + 1, y1 + 1)
                        : Generateur.Dalle.attendreClic(120_000);
                if (d == null) { Journal.erreur("Escalier annulé : pas de clic dans le jeu en 2 minutes."); return; }
            }
            double sol = Math.max(0, Salle.hauteurSol(d.getX(), d.getY()));
            List<PoseDirecte.Sol> sols = new ArrayList<>();
            for (Generateur.Mobi b : p)
                sols.add(new PoseDirecte.Sol(b.classe, d.getX() + b.x, d.getY() + b.y, Generateur.arrondi(sol + b.z), b.rot));
            PoseDirecte.Resultat r = PoseDirecte.poser(sols, List.of(), src, x -> { }, () -> false, (k, t) -> { });
            String bilan = Ui.accorder("Escalier : " + r.sols.size() + " bloc(s) posé(s) sur " + sols.size()
                    + (r.manquants > 0 ? ", " + r.manquants + " manquant(s) (ni dans l'inventaire ni au BC)" : "")
                    + (r.hauteursFausses > 0 ? ", " + r.hauteursFausses + " pas à la bonne hauteur" : "") + ".");
            if (r.manquants > 0 || r.hauteursFausses > 0) Journal.erreur(bilan); else Journal.succes(bilan);
        });
    }
}

package atelier;

import gearth.extensions.parsers.HPoint;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Escalier : des copies d'un mobi, chacune un peu plus loin et un peu plus haut.
 *
 * Rien n'est pose ici : l'escalier devient un appart temporaire
 * (_atelier_escalier.json) que le moteur de pose installe avec sa dalle magique, ce qui
 * donne les hauteurs exactes sans rien empiler a la main. S'il n'y a pas de
 * dalle magique dans la salle, Generateur en pose une (1×1) a cote du depart
 * et la ramasse quand l'escalier est fini.
 */
public class OutilEscalier {

    private Generateur.ChoixModele modele;
    private Generateur.ChoixSource source;
    private Spinner<Integer> marches, largeur, pas;
    private Spinner<Double> montee, hauteurMobi;
    private ComboBox<String> rotation;
    private RadioButton xPlus, xMoins, yPlus, yMoins;
    private CheckBox rampe, remplir;
    private Label apercu, departLbl, etat;
    private volatile HPoint depart;
    private volatile boolean attenteDepart = false;

    public Tab construire() {
        etat = Ui.etat();
        modele = new Generateur.ChoixModele("Mobi modèle", false, etat);
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

        departLbl = Ui.valeur("Au clic dans le jeu, après « Poser »");
        departLbl.setWrapText(true);
        Button choisirDepart = new Button("Choisir la case de départ");
        choisirDepart.setOnAction(e -> {
            attenteDepart = true;
            departLbl.setText("Clique la case de la 1re marche dans le jeu...");
        });
        Button oublier = new Button("Oublier");
        oublier.setOnAction(e -> { depart = null; attenteDepart = false;
            departLbl.setText("Au clic dans le jeu, après « Poser »"); });
        Salle.surClicCase(c -> {
            if (!attenteDepart) return;
            attenteDepart = false;
            depart = c;
            Platform.runLater(() -> departLbl.setText("1re marche en (" + c.getX() + "," + c.getY() + ")"));
        });

        apercu = Ui.valeur("—");
        apercu.setWrapText(true);

        // l'apercu suit tous les reglages
        modele.ecouter(() -> {
            Generateur.Modele m = modele.modele();
            if (m != null && m.hauteur > 0) hauteurMobi.getValueFactory().setValue(m.hauteur);
            ajusterPas();
            majApercu();
        });
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
                Ui.aide("Génère un escalier comme appart temporaire, que l'Atelier pose "
                        + "avec sa dalle magique (hauteurs exactes). Pas de dalle dans la salle ? "
                        + "Je pose une dalle 1×1 à côté du départ (inventaire, sinon BC), "
                        + "puis je la ramasse à la fin."),
                modele.bloc(),
                Ui.bloc("Marches",
                        Ui.ligne(Ui.etiquette("Nombre"), marches, Ui.etiquette("Largeur"), largeur),
                        Ui.ligne(Ui.etiquette("Pas (cases)"), pas),
                        Ui.ligne(Ui.etiquette("Montée"), montee, Ui.etiquette("Haut. mobi"), hauteurMobi),
                        Ui.aide("Montée : hauteur gagnée à chaque marche (0,25 / 0,5 / 1,0…). "
                                + "Hauteur du mobi : lue sur le mobi cliqué, sert à « remplir dessous »."),
                        rampe, remplir),
                Ui.bloc("Direction et rotation",
                        Ui.ligne(xPlus, xMoins, yPlus, yMoins),
                        Ui.ligne(Ui.etiquette("Rotation"), rotation)),
                Ui.bloc("Départ", departLbl, Ui.ligne(choisirDepart, oublier),
                        Ui.aide("Sans case de départ, tu cliques dans le jeu après « Poser » : "
                                + "le clic donne le coin haut-gauche (x min, y min) de l'escalier. "
                                + "Ton avatar ne bouge pas.")),
                source.bloc(),
                Ui.bloc("Aperçu", apercu),
                Generateur.principal("Poser l'escalier", this::poser),
                etat));
        t.setClosable(false);
        majApercu();
        return t;
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
        Generateur.Modele m = modele.modele();
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

    private void majApercu() {
        Generateur.Modele m = modele.modele();
        if (m == null) { apercu.setText("Choisis d'abord le mobi modèle."); return; }
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
        Generateur.Modele m = modele.modele();
        if (m == null) { etat.setText("Choisis d'abord le mobi modèle (clic dans le jeu ou nom technique)."); return; }
        if (remplir.isSelected() && hauteurMobi.getValue() <= 0) {
            etat.setText("Remplir dessous : indique la hauteur du mobi."); return;
        }
        List<Generateur.Mobi> p = plan(m);
        Generateur.Source src = source.source();
        HPoint dep = depart;
        etat.setText("Préparation de l'escalier (" + p.size() + " mobis)...");
        Salle.tache("escalier", () -> Generateur.poser("_atelier_escalier", p, src, dep,
                s -> Generateur.dire(etat, s)));
    }
}

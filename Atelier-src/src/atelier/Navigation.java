package atelier;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Les menus de l'Atelier, composes a partir des volets des outils.
 *
 * Chaque classe Onglet* / Outil* construit un onglet, parfois decoupe en
 * volets (un TabPane interieur). Un menu de la barre n'est plus lie a UN
 * onglet : il rassemble des volets venus de plusieurs onglets. C'est ce qui
 * permet de ranger « Ma salle » (tire d'Apparts) a cote de « Couleur de decor »
 * (tire de Build) sans toucher aux classes qui les construisent.
 *
 * Un menu a un seul volet le montre tel quel ; a plusieurs, chaque volet
 * devient une section repliable, la premiere ouverte.
 *
 * Le contenu d'Apparts arrive plus tard, quand le moteur de l'Atelier a demarre : on suit
 * donc le contenu de chaque onglet, et on remonte les menus qui en dependent.
 */
public class Navigation {

    /**
     * Un volet d'un onglet ; volet null = tout l'onglet. titre : l'intitule de
     * la section, quand le nom du volet ne convient pas dans son nouveau menu.
     * prerequis : les conditions affichees en haut du volet (bloc Prerequis).
     */
    public record Source(Tab onglet, String volet, String titre, Prerequis.Condition[] prerequis) {
        /** Le meme volet, avec un bloc « Prérequis » en haut. */
        public Source avec(Prerequis.Condition... c) { return new Source(onglet, volet, titre, c); }
    }

    private static final Prerequis.Condition[] AUCUN = new Prerequis.Condition[0];

    public static Source source(Tab onglet) { return new Source(onglet, null, null, AUCUN); }
    public static Source source(Tab onglet, String volet) { return new Source(onglet, volet, null, AUCUN); }
    public static Source source(Tab onglet, String volet, String titre) {
        return new Source(onglet, volet, titre, AUCUN);
    }

    public static final class Menu {
        public final String cle, nom, icone;
        final List<Source> sources;
        final VBox sections = new VBox(0);
        final ScrollPane defilement = new ScrollPane();
        Menu(String cle, String nom, String icone, List<Source> sources) {
            this.cle = cle; this.nom = nom; this.icone = icone; this.sources = sources;
        }
        /** Le contenu monte : sa hauteur suit ce qui est ouvert. */
        public Region contenu() { return sections; }
        /**
         * La fenetre ne defile pas : le contenu prend exactement sa hauteur, et
         * c'est a lui de faire defiler ce qu'il faut (un tableau, par exemple).
         * Si la fenetre est plus basse que le minimum du contenu (plafond de
         * hauteur, petit ecran), l'ascenseur revient plutot que de couper le bas.
         */
        public void pleineHauteur() {
            defilement.setFitToHeight(true);
            defilement.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }

        /** L'aide de toute la fenetre (menu a un seul volet), pour la barre de titre ; null sinon. */
        Button info;
        public Button info() { return info; }
    }

    private final StackPane zone = new StackPane();
    private final List<Menu> menus = new ArrayList<>();
    private final Map<Tab, LinkedHashMap<String, Node>> volets = new HashMap<>();
    private Menu actif;
    private Consumer<Menu> surChangement = m -> { };

    public Navigation() {
        zone.getStyleClass().add("nav-zone");
        zone.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public Region zone() { return zone; }
    public List<Menu> menus() { return menus; }
    public Menu actif() { return actif; }
    public void surChangement(Consumer<Menu> c) { surChangement = c; }

    public Menu menu(String cle) {
        for (Menu m : menus) if (m.cle.equals(cle)) return m;
        return null;
    }

    public Menu ajouter(String cle, String nom, String icone, Source... sources) {
        Menu m = new Menu(cle, nom, icone, Arrays.asList(sources));
        m.sections.getStyleClass().add("nav-sections");
        m.sections.setFillWidth(true);
        m.defilement.setContent(m.sections);
        m.defilement.setFitToWidth(true);
        m.defilement.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        m.defilement.getStyleClass().add("nav-defilement");
        m.defilement.setVisible(false);
        m.defilement.setManaged(false);
        garderPlace(m.defilement, m.sections);
        menus.add(m);
        zone.getChildren().add(m.defilement);

        for (Source s : m.sources) {
            if (volets.containsKey(s.onglet())) continue;
            extraire(s.onglet());
            s.onglet().contentProperty().addListener((ChangeListener<Node>) (o, a, n) -> {
                extraire(s.onglet());
                for (Menu x : menus)
                    if (x.sources.stream().anyMatch(y -> y.onglet() == s.onglet())) monter(x);
            });
        }
        monter(m);
        return m;
    }

    public void afficher(String cle) {
        Menu m = menu(cle);
        if (m == null) return;
        actif = m;
        for (Menu x : menus) {
            boolean v = (x == m);
            x.defilement.setVisible(v);
            x.defilement.setManaged(v);
        }
        surChangement.accept(m);
    }

    // ------------------------------------------------------- montage du menu

    /** Range les volets d'un onglet par nom ; sans volets, une seule entree (cle null). */
    private void extraire(Tab onglet) {
        LinkedHashMap<String, Node> carte = new LinkedHashMap<>();
        Node c = onglet.getContent();
        if (c != null) {
            TabPane tp = premierTabPane(c);
            if (tp != null && !tp.getTabs().isEmpty()) {
                List<Tab> liste = new ArrayList<>(tp.getTabs());
                tp.getTabs().clear();          // libere les contenus
                for (Tab v : liste) {
                    Node n = v.getContent();
                    v.setContent(null);
                    if (n != null) carte.put(v.getText(), deballer(n));
                }
            } else {
                carte.put(null, deballer(c));
            }
        }
        volets.put(onglet, carte);
    }

    private void monter(Menu m) {
        List<String> noms = new ArrayList<>();
        List<Node> noeuds = new ArrayList<>();
        List<Prerequis.Condition[]> conds = new ArrayList<>();
        for (Source s : m.sources) {
            LinkedHashMap<String, Node> carte = volets.getOrDefault(s.onglet(), new LinkedHashMap<>());
            if (s.volet() == null) {
                for (Map.Entry<String, Node> e : carte.entrySet()) {
                    noms.add(e.getKey() != null ? e.getKey()
                            : s.titre() != null ? s.titre() : s.onglet().getText());
                    noeuds.add(e.getValue());
                    conds.add(s.prerequis());
                }
            } else if (carte.containsKey(s.volet())) {
                noms.add(s.titre() != null ? s.titre() : s.volet());
                noeuds.add(carte.get(s.volet()));
                conds.add(s.prerequis());
            } else {
                // Onglet pas encore pret (Apparts avant le moteur de l'Atelier) : un Node ne
                // peut avoir qu'un parent, on ne reprend donc pas le contenu
                // provisoire, partage par deux menus.
                noms.add(s.titre() != null ? s.titre() : s.volet());
                noeuds.add(Ui.colonne(Ui.discret("Chargement en cours…")));
                conds.add(AUCUN);
            }
        }

        // Bloc « Prérequis » en haut des volets qui en ont, et majuscules
        // imposees sur tous les textes du volet.
        List<List<Label>> aides = new ArrayList<>();
        for (int i = 0; i < noeuds.size(); i++) {
            Node n = noeuds.get(i);
            // Les aides vont dans des boutons « i » : celui de leur bloc, sinon
            // celui de la section (retenu sur le volet, qui survit aux remontages).
            aides.add(Ui.aidesEnBulles(n));
            Prerequis.Condition[] c = conds.get(i);
            if (c != null && c.length > 0) {
                // Tant qu'un prerequis manque (ou charge encore), l'outil dessous
                // est grise : on ne peut pas lancer ce qui echouerait.
                Prerequis pr = prerequisEnHaut(c);
                n.disableProperty().unbind();
                n.disableProperty().bind(pr.pretProperty().not());
                VBox avec = new VBox(0, pr, n);
                avec.setFillWidth(true);
                VBox.setVgrow(n, Priority.ALWAYS);
                n = avec;
                noeuds.set(i, n);
            }
            Ui.majusculesAuto(n);
            // Ni bouton rogne (« Arr... »), ni texte ampute de sa derniere ligne.
            Ui.rienDeCoupe(n);
        }
        m.sections.getChildren().clear();
        if (noeuds.size() == 1) {
            Node n = noeuds.get(0);
            // Aides hors des blocs : elles valent pour toute la fenetre, leur
            // « i » va dans la barre de titre (Fenetre), a gauche du « − ».
            m.info = aides.get(0).isEmpty() ? null : Ui.info(aides.get(0));
            VBox.setVgrow(n, Priority.ALWAYS);
            m.sections.getChildren().add(n);
        } else {
            m.info = null;     // a plusieurs sections : chaque section a son « i »
            for (int i = 0; i < noeuds.size(); i++)
                m.sections.getChildren().add(section(noms.get(i), noeuds.get(i), i == 0, aides.get(i)));
        }
        Journal.debug("menu " + m.nom + " : " + noeuds.size() + " volet(s)");
    }

    private static Prerequis prerequisEnHaut(Prerequis.Condition[] c) {
        Prerequis p = new Prerequis(c);
        p.setPadding(new javafx.geometry.Insets(12, 14, 0, 14));
        return p;
    }

    /**
     * Une section repliable : un intitule cliquable, et son contenu dessous.
     * La premiere du menu s'ouvre d'emblee, les autres restent fermees.
     */
    private Node section(String nom, Node contenu, boolean ouvert, List<Label> aides) {
        Label fleche = new Label(ouvert ? "▾" : "▸");
        fleche.getStyleClass().add("nav-fleche");

        Button tete = new Button(nom == null ? "" : nom);
        tete.setGraphic(fleche);
        tete.setContentDisplay(ContentDisplay.LEFT);
        tete.setGraphicTextGap(7);
        tete.setAlignment(Pos.CENTER_LEFT);
        tete.setMaxWidth(Double.MAX_VALUE);
        tete.setFocusTraversable(false);
        tete.getStyleClass().add("nav-sous");
        if (ouvert) tete.getStyleClass().add("ouvert");

        VBox corps = new VBox(contenu);
        corps.getStyleClass().add("nav-corps");
        corps.setFillWidth(true);
        corps.setVisible(ouvert);
        corps.setManaged(ouvert);

        HBox ligne = new HBox(6, tete);
        ligne.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tete, Priority.ALWAYS);
        ligne.setPadding(new javafx.geometry.Insets(0, 10, 0, 0));
        // Aides hors des blocs : un « i » a droite de l'intitule de la section.
        if (!aides.isEmpty()) ligne.getChildren().add(Ui.info(aides));

        tete.setOnAction(a -> {
            boolean v = !corps.isVisible();
            corps.setVisible(v);
            corps.setManaged(v);
            fleche.setText(v ? "▾" : "▸");
            if (v) tete.getStyleClass().add("ouvert");
            else   tete.getStyleClass().remove("ouvert");
        });

        VBox s = new VBox(ligne, corps);
        s.getStyleClass().add("nav-section");
        s.setFillWidth(true);
        return s;
    }

    /**
     * Le defilement garde sa place, en pixels, quand le contenu change de
     * hauteur (un message qui apparait, un prerequis qui change, une case
     * cochee qui deplie des options) : sans cela, la position relative
     * (vvalue, de 0 a 1) restait la meme et la vue glissait toute seule
     * ailleurs. Seul un defilement fait par l'utilisatrice la deplace.
     */
    static void garderPlace(ScrollPane sp, Region contenu) {
        final double[] decalage = {0};
        final boolean[] interne = {false};
        Runnable recaler = () -> {
            double vue = sp.getViewportBounds() == null ? 0 : sp.getViewportBounds().getHeight();
            double plage = contenu.getHeight() - vue;
            if (plage <= 0) return;
            double v = Math.max(0, Math.min(1, decalage[0] / plage));
            if (Math.abs(v - sp.getVvalue()) < 1e-6) return;
            interne[0] = true;
            try { sp.setVvalue(v); } finally { interne[0] = false; }
        };
        sp.vvalueProperty().addListener((o, a, b) -> {
            if (interne[0]) return;
            double vue = sp.getViewportBounds() == null ? 0 : sp.getViewportBounds().getHeight();
            decalage[0] = Math.max(0, b.doubleValue() * (contenu.getHeight() - vue));
        });
        contenu.heightProperty().addListener((o, a, b) -> recaler.run());
        sp.viewportBoundsProperty().addListener((o, a, b) -> recaler.run());
    }

    /**
     * Retire l'ascenseur que chaque outil porte deja : c'est l'ascenseur du
     * menu qui defile, un seul pour tout.
     */
    private static Node deballer(Node n) {
        while (n instanceof ScrollPane) {
            Node dedans = ((ScrollPane) n).getContent();
            if (dedans == null) break;
            ((ScrollPane) n).setContent(null);
            n = dedans;
        }
        return n;
    }

    /** Premier TabPane rencontre sous ce noeud, en largeur. */
    private static TabPane premierTabPane(Node n) {
        List<Node> file = new ArrayList<>();
        file.add(n);
        for (int i = 0; i < file.size(); i++) {
            Node c = file.get(i);
            if (c instanceof TabPane) return (TabPane) c;
            if (c instanceof ScrollPane) {
                Node d = ((ScrollPane) c).getContent();
                if (d != null) file.add(d);
            } else if (c instanceof Parent) {
                file.addAll(((Parent) c).getChildrenUnmodifiable());
            }
        }
        return null;
    }
}

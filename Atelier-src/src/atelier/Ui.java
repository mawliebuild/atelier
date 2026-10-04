package atelier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Styles partages des onglets de l'Atelier.
 *
 * Style des fenetres du jeu : un intitule discret en majuscules, puis le
 * contenu dans une boite blanche a filet, sur le fond creme de la fenetre.
 */
public final class Ui {

    public static final double DANS_BLOC  = 6;

    public static final double ENTRE_BLOCS = 18;

    private Ui() { }

    /** Intitule de bloc : petit, espace, discret. */
    public static Label intitule(String texte) {
        Label l = new Label(texte.toUpperCase());
        l.getStyleClass().add("intitule");
        return l;
    }

    /**
     * Un bloc : son intitule, puis son contenu dans une boite blanche a filet,
     * comme les listes des fenetres du jeu. Les textes d'aide (Ui.aide) passes
     * dans le contenu ne s'affichent pas dedans : ils vont dans un bouton « i »
     * a cote de l'intitule.
     */
    public static VBox bloc(String titre, Node... contenu) {
        java.util.List<Node> garder = new java.util.ArrayList<>();
        java.util.List<Label> aides = new java.util.ArrayList<>();
        for (Node n : contenu) {
            if (estAide(n)) aides.add((Label) n);
            else garder.add(n);
        }
        VBox boite = new VBox(DANS_BLOC, garder.toArray(new Node[0]));
        boite.getStyleClass().add("boite");
        boite.setFillWidth(true);
        HBox tete = new HBox(6, intitule(titre));
        tete.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(DANS_BLOC, tete, boite);
        v.setFillWidth(true);
        v.getProperties().put(TETE, tete);
        for (Label a : aides) ajouterAide(tete, a);
        return v;
    }

    private static final String TETE = "atelier.bloc.tete";
    private static final String AIDE = "atelier.aide";
    private static final String INFO = "atelier.info";

    private static boolean estAide(Node n) {
        return n instanceof Label && Boolean.TRUE.equals(n.getProperties().get(AIDE))
                && !((Label) n).textProperty().isBound();
    }

    /**
     * Un bouton « i » entoure : au survol (ou au clic), une bulle avec l'aide.
     * Le texte est relu a chaque ouverture : une aide qui change suit.
     */
    public static Button info(java.util.List<Label> aides) {
        Button b = new Button("i");
        b.getStyleClass().add("info-bouton");
        b.setFocusTraversable(false);
        javafx.scene.control.Tooltip t = new javafx.scene.control.Tooltip();
        t.getStyleClass().add("info-bulle");
        t.setWrapText(true);
        t.setMaxWidth(300);
        t.setShowDelay(javafx.util.Duration.millis(80));
        t.setHideDelay(javafx.util.Duration.millis(80));
        t.setShowDuration(javafx.util.Duration.INDEFINITE);
        Runnable relire = () -> {
            StringBuilder sb = new StringBuilder();
            for (Label l : aides) {
                String x = l.getText();
                if (x == null || x.isBlank()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(majuscule(x.trim()));
            }
            t.setText(sb.toString());
        };
        t.setOnShowing(e -> relire.run());
        b.setTooltip(t);
        b.setOnAction(e -> {
            if (t.isShowing()) { t.hide(); return; }
            relire.run();
            javafx.geometry.Bounds r = b.localToScreen(b.getBoundsInLocal());
            if (r != null) t.show(b, r.getMaxX() + 4, r.getMaxY() + 2);
        });
        b.getProperties().put(INFO, aides);
        return b;
    }

    /** Range une aide dans le bouton « i » d'une tete (le cree au besoin). */
    @SuppressWarnings("unchecked")
    private static void ajouterAide(javafx.scene.layout.Pane tete, Label aide) {
        for (Node n : tete.getChildren()) {
            Object l = n.getProperties().get(INFO);
            if (l instanceof java.util.List) { ((java.util.List<Label>) l).add(aide); return; }
        }
        java.util.List<Label> l = new java.util.ArrayList<>();
        l.add(aide);
        tete.getChildren().add(info(l));
    }

    /**
     * Retire du volet les textes d'aide restes en ligne et les range dans le
     * bouton « i » du bloc qui les contient. Ceux qui ne sont dans aucun bloc
     * sont retires aussi, et rendus : a l'appelant de leur donner un « i ».
     * Les aides orphelines sont retenues sur la racine : un second appel (menu
     * remonte) les rend de nouveau.
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<Label> aidesEnBulles(Node racine) {
        java.util.List<Label> orphelines = (java.util.List<Label>) racine.getProperties()
                .computeIfAbsent("atelier.aides.orphelines", k -> new java.util.ArrayList<Label>());
        java.util.List<Label> trouvees = new java.util.ArrayList<>();
        chercherAides(racine, trouvees);
        for (Label a : trouvees) {
            if (!(a.getParent() instanceof javafx.scene.layout.Pane)) continue;
            javafx.scene.layout.Pane tete = null;
            for (javafx.scene.Parent p = a.getParent(); p != null && tete == null; p = p.getParent()) {
                Object t = p.getProperties().get(TETE);
                if (t instanceof javafx.scene.layout.Pane) tete = (javafx.scene.layout.Pane) t;
                if (p == racine) break;
            }
            ((javafx.scene.layout.Pane) a.getParent()).getChildren().remove(a);
            if (tete != null) ajouterAide(tete, a);
            else orphelines.add(a);
        }
        return orphelines;
    }

    private static void chercherAides(Node n, java.util.List<Label> l) {
        if (n == null) return;
        if (estAide(n)) { l.add((Label) n); return; }
        if (n instanceof javafx.scene.control.ScrollPane) chercherAides(((javafx.scene.control.ScrollPane) n).getContent(), l);
        else if (n instanceof javafx.scene.control.TitledPane) chercherAides(((javafx.scene.control.TitledPane) n).getContent(), l);
        else if (n instanceof javafx.scene.layout.Pane)
            for (Node e : new java.util.ArrayList<>(((javafx.scene.layout.Pane) n).getChildren())) chercherAides(e, l);
    }

    /** La pile de blocs d'un onglet. */
    public static VBox colonne(Node... blocs) {
        VBox v = new VBox(ENTRE_BLOCS, blocs);
        v.setPadding(new Insets(14, 16, 16, 16));
        return v;
    }

    /**
     * Une ligne d'options. En colonne etroite, les options passent a la ligne
     * d'elles-memes plutot que de deborder : c'est un FlowPane, pas un HBox.
     */
    public static javafx.scene.layout.FlowPane ligne(Node... n) {
        javafx.scene.layout.FlowPane f = new javafx.scene.layout.FlowPane(12, 6, n);
        f.setAlignment(Pos.CENTER_LEFT);
        f.setRowValignment(javafx.geometry.VPos.CENTER);
        return f;
    }

    public static Label etiquette(String texte) {
        Label l = new Label(WindowsClavier.texte(texte));
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Valeur mise en avant : c'est le sujet sur lequel on travaille. */
    public static Label valeur(String texte) {
        Label l = new Label(WindowsClavier.texte(texte));
        l.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        return l;
    }

    /**
     * Ligne d'etat d'une fenetre : texte discret, sans encadre, qui n'occupe
     * aucune place tant qu'il n'y a rien a dire. Elle ne garde que la
     * progression et les consignes. Les RESULTATS (succes, erreur) partent
     * dans le jeu et la console (Journal) ; hors d'un appart, ou le jeu ne
     * peut pas les afficher, ils restent ici.
     */
    public static Label etat() {
        Label l = new Label("");
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);   // toutes ses lignes, jamais rognee
        l.getStyleClass().add("etat-ligne");
        javafx.beans.property.BooleanProperty montre = new javafx.beans.property.SimpleBooleanProperty(false);
        l.textProperty().addListener((o, a, b) -> {
            // pas de « (s) » : accorde selon le nombre (rappelle l'ecouteur une fois)
            String c = accorder(b);
            if (c != null && !c.equals(b) && !l.textProperty().isBound()) { l.setText(c); return; }
            if (c != null) b = c;   // texte lie (bind) : on classe la version accordee
            if (b == null || b.isBlank()) { montre.set(false); return; }
            // genre donne par Ui.succes / Ui.erreur, sinon devine sur le texte
            Object force = l.getProperties().remove(GENRE_ETAT);
            Journal.Genre g = force instanceof Journal.Genre ? (Journal.Genre) force : Journal.genre(b);
            boolean resultat = g == Journal.Genre.SUCCES || g == Journal.Genre.ERREUR;
            if (resultat) {
                if (g == Journal.Genre.ERREUR) Journal.erreur(b); else Journal.succes(b);
            } else if (g == Journal.Genre.INFO) Journal.debug(b);
            montre.set(!resultat || !Salle.dansUneSalle());
        });
        l.visibleProperty().bind(montre);
        l.managedProperty().bind(l.visibleProperty());
        return l;
    }

    private static final String GENRE_ETAT = "atelier.etat.genre";

    /**
     * Resultat reussi d'une action, dans une ligne d'etat faite par Ui.etat() :
     * le genre est dit, pas devine sur le texte. Appelable depuis n'importe quel fil.
     */
    public static void succes(Label etat, String m) { resultat(etat, m, Journal.Genre.SUCCES); }

    /** Echec d'une action, dans une ligne d'etat faite par Ui.etat(). */
    public static void erreur(Label etat, String m) { resultat(etat, m, Journal.Genre.ERREUR); }

    /** Echec avec sa cause : la trace complete va dans la console. */
    public static void erreur(Label etat, String m, Throwable t) {
        if (t != null) t.printStackTrace();
        erreur(etat, t == null ? m : m + " (" + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : " : " + t.getMessage()) + ")");
    }

    private static void resultat(Label etat, String m, Journal.Genre g) {
        if (etat == null || m == null || m.isBlank()) return;
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(() -> resultat(etat, m, g));
            return;
        }
        // le meme resultat deux fois de suite doit etre redit : on vide d'abord
        if (accorder(m).equals(etat.getText())) etat.setText("");
        etat.getProperties().put(GENRE_ETAT, g);
        etat.setText(m);
        etat.getProperties().remove(GENRE_ETAT);
    }

    /** Les polices du jeu (Ubuntu), embarquees dans l'Atelier. A appeler au demarrage. */
    public static void chargerPolices() {
        for (String f : new String[]{"Ubuntu-Regular", "Ubuntu-Medium", "Ubuntu-Bold"}) {
            try (java.io.InputStream in = Ui.class.getResourceAsStream("/atelier/polices/" + f + ".ttf")) {
                if (in != null) javafx.scene.text.Font.loadFont(in, 12);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Texte d'aide : il ne s'affiche pas dans le volet, il part dans le bouton
     * « i » du bloc (ou de la section) qui le contient. Pour un texte discret
     * qui doit RESTER visible (un etat, une consigne qui change), Ui.discret.
     */
    public static Label aide(String texte) {
        Label l = discret(texte);
        l.getProperties().put(AIDE, Boolean.TRUE);
        return l;
    }

    /** Texte discret, en italique gris, qui reste en place. */
    public static Label discret(String texte) {
        Label l = new Label(WindowsClavier.texte(texte));
        l.setWrapText(true);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        l.setStyle("-fx-opacity: 0.6; -fx-font-style: italic;");
        return l;
    }

    /**
     * Ligne d'etat d'un prerequis : une pastille de couleur, le nom, et la
     * raison en clair. Pas d'animation, seulement la couleur et le texte.
     */
    public static final class Voyant extends HBox {
        private final javafx.scene.shape.Circle pastille = new javafx.scene.shape.Circle(4.5);
        private final Label texte = new Label();

        public Voyant(String nom) {
            super(7);
            setAlignment(Pos.CENTER_LEFT);
            Label n = new Label(nom);
            // Largeur fixe pour aligner les raisons ; un nom long passe a la ligne
            // (« Couleur de / décor ») au lieu d'etre coupe.
            n.setMinWidth(78); n.setPrefWidth(78); n.setMaxWidth(78);
            n.setWrapText(true);
            n.setMinHeight(javafx.scene.control.Control.USE_PREF_SIZE);
            texte.setWrapText(true);
            getChildren().addAll(pastille, n, texte);
            regler("absent", "inconnu");
        }

        /** niveau : "ok", "attente" ou "absent". */
        public void regler(String niveau, String raison) {
            pastille.getStyleClass().setAll("etat-pastille-" + niveau);
            texte.getStyleClass().setAll("label", "etat-" + niveau);
            texte.setText(majuscule(raison));
        }
    }

    /**
     * Sous-menu d'un onglet : un TabPane enveloppe avec la même marge partout,
     * pour que Build et Apparts soient identiques par construction plutôt que
     * par recopie — c'est ce qui les avait fait diverger.
     */
    public static VBox sousMenu(javafx.scene.control.Tab... volets) {
        javafx.scene.control.TabPane p = new javafx.scene.control.TabPane();
        p.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        p.getTabs().addAll(volets);
        p.setMinHeight(200);
        p.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(p, Priority.ALWAYS);

        VBox v = new VBox(6, p);
        v.setPadding(new Insets(10));
        v.setFillWidth(true);
        return v;
    }

    /** Premiere lettre en majuscule (« chargé » -> « Chargé »). */
    /**
     * Accorde les « (s) » d'un message selon le nombre qui precede : « 1 dalle(s)
     * posée(s) » -> « 1 dalle posée », « 3 dalle(s) » -> « 3 dalles ». Aussi
     * « mural(aux) » -> mural / muraux et « niveau(x) ». Sans nombre dans la
     * phrase, on met le pluriel. 0 et 1 : singulier (comme en francais).
     */
    public static String accorder(String t) {
        t = WindowsClavier.texte(t);   // noms des touches selon la plateforme (Option -> Alt sous Windows)
        if (t == null || t.indexOf('(') < 0) return t;
        java.util.regex.Matcher m = ACCORD.matcher(t);
        StringBuilder r = new StringBuilder();
        long dernier = -1;
        int fin = 0;
        while (m.find()) {
            r.append(t, fin, m.start());
            fin = m.end();
            if (m.group(1) != null) {                       // un nombre
                try { dernier = Long.parseLong(m.group(1).replace(" ", "")); } catch (NumberFormatException e) { dernier = -1; }
                r.append(m.group());
            } else if (m.group(2) != null) {                // fin de phrase : on oublie le nombre
                dernier = -1;
                r.append(m.group());
            } else {
                String mot = m.group(3), suffixe = m.group(4);
                boolean pluriel = dernier < 0 || dernier >= 2;
                switch (suffixe) {
                    case "aux": r.append(pluriel && mot.endsWith("al") ? mot.substring(0, mot.length() - 2) + "aux" : mot); break;
                    case "x":   r.append(pluriel ? mot + "x" : mot); break;
                    case "es":  r.append(pluriel ? mot + "es" : mot); break;
                    case "e":   r.append(mot); break;
                    default:    r.append(pluriel ? mot + "s" : mot);
                }
            }
        }
        r.append(t.substring(fin));
        return r.toString();
    }

    private static final java.util.regex.Pattern ACCORD = java.util.regex.Pattern.compile(
            "(\\d[\\d\u202f\u00a0]*)|([.!?;](?:\\s|$))|([\\p{L}'’]+)\\((s|x|aux|es|e)\\)");

    public static String majuscule(String t) {
        if (t == null || t.isEmpty()) return t;
        int i = 0;
        while (i < t.length() && !Character.isLetter(t.charAt(i))) {
            // « ? », « — », « 3 mobis » : on ne touche pas a ce qui commence par un chiffre.
            if (Character.isDigit(t.charAt(i))) return t;
            i++;
        }
        if (i >= t.length() || Character.isUpperCase(t.charAt(i))) return t;
        return t.substring(0, i) + Character.toUpperCase(t.charAt(i)) + t.substring(i + 1);
    }

    /**
     * Toutes les phrases de l'interface commencent par une majuscule : on
     * l'impose une fois pour toutes sur les textes d'un volet (etiquettes,
     * cases, boutons), y compris quand ils changent ensuite. Les cellules des
     * tableaux et des listes (des noms de mobis, des pseudos) ne sont pas
     * touchees.
     */
    public static void majusculesAuto(Node n) {
        if (n == null) return;
        if (n instanceof javafx.scene.control.Labeled) {
            javafx.scene.control.Labeled l = (javafx.scene.control.Labeled) n;
            if (l.getProperties().putIfAbsent("atelier.majuscule", Boolean.TRUE) == null) {
                if (!l.textProperty().isBound()) {
                    l.setText(majuscule(l.getText()));
                    l.textProperty().addListener((o, a, b) -> {
                        String m = majuscule(b);
                        if (m != null && !m.equals(b) && !l.textProperty().isBound()) l.setText(m);
                    });
                }
            }
        }
        if (n instanceof javafx.scene.control.ComboBox || n instanceof javafx.scene.control.ChoiceBox)
            majusculesListe(n);
        if (n instanceof javafx.scene.control.TableView
                && ((javafx.scene.control.TableView<?>) n).getPlaceholder() == null) {
            javafx.scene.control.Label p = new javafx.scene.control.Label("Rien à afficher pour l'instant.");
            p.getStyleClass().add("aide-vide");
            ((javafx.scene.control.TableView<?>) n).setPlaceholder(p);
        }
        if (n instanceof javafx.scene.control.ScrollPane) {
            majusculesAuto(((javafx.scene.control.ScrollPane) n).getContent());
        } else if (n instanceof javafx.scene.control.TitledPane) {
            majusculesAuto(((javafx.scene.control.TitledPane) n).getContent());
        } else if (n instanceof javafx.scene.layout.Pane || n instanceof javafx.scene.Group) {
            for (Node e : ((javafx.scene.Parent) n).getChildrenUnmodifiable()) majusculesAuto(e);
        }
    }

    /**
     * Les cellules de la colonne passent a la ligne au lieu de couper le texte
     * (« … ») : la rangee prend la hauteur qu'il faut.
     */
    public static <S, T> void retourALaLigne(javafx.scene.control.TableColumn<S, T> col) {
        col.setCellFactory(c -> new javafx.scene.control.TableCell<S, T>() {
            private final javafx.scene.text.Text texte = new javafx.scene.text.Text();
            {
                texte.wrappingWidthProperty().bind(
                        javafx.beans.binding.Bindings.max(20, col.widthProperty().subtract(12)));
                texte.getStyleClass().add("texte-cellule");
                setPrefHeight(javafx.scene.control.Control.USE_COMPUTED_SIZE);
            }
            @Override protected void updateItem(T v, boolean vide) {
                super.updateItem(v, vide);
                if (vide || v == null) { setGraphic(null); setText(null); return; }
                texte.setText(String.valueOf(v));
                setText(null);
                setGraphic(texte);
            }
        });
    }

    /**
     * Listes deroulantes de textes : chaque choix s'affiche avec sa majuscule
     * (« Tout le monde »). La valeur, elle, ne change pas : le code qui la
     * compare a « tout le monde » continue de marcher.
     */
    @SuppressWarnings("unchecked")
    private static void majusculesListe(Node n) {
        if (n.getProperties().putIfAbsent("atelier.majuscule.liste", Boolean.TRUE) != null) return;
        javafx.util.StringConverter<Object> conv = new javafx.util.StringConverter<>() {
            @Override public String toString(Object o) { return o == null ? "" : majuscule(String.valueOf(o)); }
            @Override public Object fromString(String s) { return s; }
        };
        if (n instanceof javafx.scene.control.ComboBox) {
            javafx.scene.control.ComboBox<Object> c = (javafx.scene.control.ComboBox<Object>) n;
            if (c.isEditable() || !toutTexte(c.getItems())) return;
            c.setConverter(conv);
        } else {
            javafx.scene.control.ChoiceBox<Object> c = (javafx.scene.control.ChoiceBox<Object>) n;
            if (c.getConverter() != null || !toutTexte(c.getItems())) return;
            c.setConverter(conv);
        }
    }

    private static boolean toutTexte(java.util.List<?> l) {
        for (Object o : l) if (!(o instanceof String)) return false;
        return true;
    }

    public static void etirer(Node n) {
        HBox.setHgrow(n, Priority.ALWAYS);
        VBox.setVgrow(n, Priority.ALWAYS);
    }

    /**
     * Rien n'est rogne dans un volet, meme quand la fenetre manque de place :
     * - un bouton a texte pose dans une rangee (HBox) garde toute sa largeur
     *   (« Arr... » non) : c'est l'etiquette voisine qui cede ;
     * - un texte qui passe a la ligne garde toutes ses lignes (une colonne
     *   trop courte serre plutot le tableau, ou fait defiler la fenetre).
     * Ce qui a deja une taille minimale reglee n'est pas touche.
     */
    public static void rienDeCoupe(Node n) {
        if (n == null) return;
        if (n instanceof javafx.scene.control.ButtonBase && !(n instanceof javafx.scene.control.Hyperlink)) {
            javafx.scene.control.ButtonBase b = (javafx.scene.control.ButtonBase) n;
            if (b.getParent() instanceof HBox && b.getText() != null && !b.getText().isBlank()
                    && b.getMinWidth() == javafx.scene.layout.Region.USE_COMPUTED_SIZE)
                b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            return;
        }
        if (n instanceof Label) {
            Label l = (Label) n;
            if (l.isWrapText() && l.getMinHeight() == javafx.scene.layout.Region.USE_COMPUTED_SIZE)
                l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            return;
        }
        if (n instanceof javafx.scene.control.ScrollPane) rienDeCoupe(((javafx.scene.control.ScrollPane) n).getContent());
        else if (n instanceof javafx.scene.control.TitledPane) rienDeCoupe(((javafx.scene.control.TitledPane) n).getContent());
        else if (n instanceof javafx.scene.layout.Pane)
            for (Node e : ((javafx.scene.layout.Pane) n).getChildren()) rienDeCoupe(e);
    }

    // ------------------------------------------------------------- bulles

    /** Delai d'apparition des bulles de l'Atelier : rapide, sans etre nerveux. */
    public static final javafx.util.Duration DELAI_BULLE = javafx.util.Duration.millis(150);

    /** Une bulle rapide (150 ms), qui passe a la ligne au-dela de 300 px. */
    public static javafx.scene.control.Tooltip bulle(String texte) {
        javafx.scene.control.Tooltip t = new javafx.scene.control.Tooltip(WindowsClavier.texte(texte));
        t.setShowDelay(DELAI_BULLE);
        t.setWrapText(true);
        t.setMaxWidth(300);
        return t;
    }

    /** Pose une bulle rapide sur un controle ; le rend pour enchainer. */
    public static <C extends javafx.scene.control.Control> C bulle(C c, String texte) {
        c.setTooltip(bulle(texte));
        return c;
    }

    /**
     * Toutes les bulles d'une scene apparaissent vite : celles laissees au
     * delai par defaut de JavaFX (une seconde) passent a 150 ms au moment ou
     * la souris arrive dessus. Le delai CSS (-fx-show-delay) n'est lu qu'apres
     * le premier affichage : il ne suffit pas. Les bulles reglees a la main
     * (le « i », plus rapide) ne sont pas touchees.
     */
    public static void bullesRapides(javafx.scene.Scene scene) {
        if (scene == null || scene.getProperties().putIfAbsent("atelier.bulles", Boolean.TRUE) != null) return;
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            Object o = e.getTarget();
            for (Node n = o instanceof Node ? (Node) o : null; n != null; n = n.getParent()) {
                javafx.scene.control.Tooltip t = n instanceof javafx.scene.control.Control
                        ? ((javafx.scene.control.Control) n).getTooltip() : null;
                if (t == null) {
                    Object x = n.getProperties().get("javafx.scene.control.Tooltip");   // Tooltip.install
                    if (x instanceof javafx.scene.control.Tooltip) t = (javafx.scene.control.Tooltip) x;
                }
                if (t != null) {
                    if (t.getShowDelay().toMillis() >= 999) t.setShowDelay(DELAI_BULLE);
                    if (t.getMaxWidth() <= 0 || t.getMaxWidth() == Double.MAX_VALUE) { t.setMaxWidth(320); t.setWrapText(true); }
                    return;
                }
            }
        });
    }

    // ------------------------------------------------------------- boutons

    /** Pictogramme de bouton : la grille de 24 ramenee a 16 px, trait de la couleur du texte. */
    public static Node pictogramme(String icone) {
        javafx.scene.shape.SVGPath ic = Icones.trace(icone, "icone-bouton");
        ic.setScaleX(16.0 / 24); ic.setScaleY(16.0 / 24);
        // le Group prend la taille reduite : sans lui, le bouton garde la place des 24 px
        return new javafx.scene.Group(ic);
    }

    /** Bouton pictogramme + texte court (le texte reste lisible, l'icone aide a reperer). */
    public static Button bouton(String icone, String texte) {
        Button b = new Button(WindowsClavier.texte(texte));
        if (icone != null) b.setGraphic(pictogramme(icone));
        b.setGraphicTextGap(6);
        b.getStyleClass().add("avec-icone");
        return b;
    }

    /** Bouton pictogramme seul, carre, avec sa bulle rapide (le sens doit etre evident). */
    public static Button boutonIcone(String icone, String bulle) {
        Button b = new Button();
        b.setGraphic(pictogramme(icone));
        b.getStyleClass().add("bouton-icone");
        b.setTooltip(bulle(bulle));
        b.setMinWidth(Button.USE_PREF_SIZE);
        return b;
    }

    /** Une rangee de boutons qui passe a la ligne plutot que de deborder (FlowPane). */
    public static javafx.scene.layout.FlowPane boutons(Node... n) {
        javafx.scene.layout.FlowPane f = new javafx.scene.layout.FlowPane(6, 6, n);
        f.setAlignment(Pos.CENTER_LEFT);
        f.setRowValignment(javafx.geometry.VPos.CENTER);
        f.getStyleClass().add("rangee-boutons");
        return f;
    }
}

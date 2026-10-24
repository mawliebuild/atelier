package atelier;

import gearth.protocol.HMessage;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * « Liens d'apparts » : des apparts retenus pour y retourner en un clic,
 * ranges dans des rubriques que l'utilisatrice nomme elle-meme (Troc,
 * Decoration...). Enregistres dans liens-apparts.json (dossier de l'Atelier).
 *
 * En haut, l'appart actuel et « Retenir » ; dessous, les rubriques (repliables,
 * avec leur nombre) et leurs apparts. Glisser un appart le range ou change son
 * ordre ; retirer un appart ou une rubrique se rattrape avec « Annuler ».
 */
public class OngletLiensApparts {

    private static final String FICHIER = "liens-apparts.json";
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String CLE_RUBRIQUE = "liensApparts.rubrique", CLE_FERMEES = "liensApparts.fermees";
    private static final String SANS_NOM = "Appart n°";
    private static final String GLISSE = "atelier-lien:";

    /** Un appart retenu. « vu » : derniere visite (0 = jamais vu depuis qu'il est retenu). */
    private static final class Lien {
        int id;
        String nom, proprio;
        long ajoute, vu;
        JSONObject json() {
            JSONObject o = new JSONObject().put("id", id).put("nom", nom).put("proprio", proprio == null ? "" : proprio).put("ajoute", ajoute);
            if (vu > 0) o.put("vu", vu);
            return o;
        }
    }

    /** Une rubrique, dans l'ordre choisi. */
    private static final class Rubrique {
        String nom;
        final List<Lien> liens = new ArrayList<>();
        Rubrique(String nom) { this.nom = nom; }
    }

    private final List<Rubrique> rubriques = new ArrayList<>();
    private final VBox liste = new VBox(2);
    private final Label actuelNom = Ui.valeur("");
    private final Label actuelDetail = Ui.discret("");
    private final Label actuelBadge = new Label();
    private final ComboBox<String> choixRubrique = new ComboBox<>();
    private final TextField recherche = new TextField();
    private final Set<String> fermees = new HashSet<>();
    private Button retenir;
    private StackPane enveloppeRetenir;
    private final Tooltip pourquoiRetenir = Ui.bulle("");
    private int salleVue = -2, derniereVisite = -2;
    /** L'appart vers lequel on est en route (« Y aller » en cours), -1 sinon. */
    private int enRoute = -1;

    // retirer se rattrape : le bandeau « Annuler » reste quelques secondes
    private final HBox annulation = new HBox(8);
    private final Label annulationTexte = new Label();
    private Runnable annulationAction = null;
    private final PauseTransition annulationDelai = new PauseTransition(Duration.seconds(6));

    public Tab construire() {
        lire();
        for (String s : PREFS.get(CLE_FERMEES, "").split("\n")) if (!s.isBlank()) fermees.add(s);

        // ---- l'appart ou je suis
        actuelNom.setWrapText(true);
        actuelNom.setMinHeight(Region.USE_PREF_SIZE);
        actuelBadge.getStyleClass().addAll("badge", "badge-ok");
        actuelBadge.setMinWidth(Region.USE_PREF_SIZE);
        VBox actuelTextes = new VBox(0, actuelNom, actuelDetail);
        actuelTextes.setMinWidth(0);
        actuelTextes.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(actuelTextes, Priority.ALWAYS);
        HBox ligneActuel = new HBox(8, Icones.petit(Icones.SALLE), actuelTextes, actuelBadge);
        ligneActuel.setAlignment(Pos.CENTER_LEFT);

        choixRubrique.setEditable(true);
        choixRubrique.setPromptText("Rubrique");
        choixRubrique.setMaxWidth(Double.MAX_VALUE);
        choixRubrique.setMinWidth(90);
        Ui.bulle(choixRubrique, "La rubrique où ranger l'appart. Tape un nouveau nom pour en créer une.");
        HBox.setHgrow(choixRubrique, Priority.ALWAYS);
        choixRubrique.getEditor().textProperty().addListener((o, a, b) -> reglerRetenir());
        choixRubrique.valueProperty().addListener((o, a, b) -> reglerRetenir());
        retenir = Icones.sur(new Button("Retenir"), Icones.PLUS);
        retenir.getStyleClass().add("primaire");
        retenir.setMinWidth(Region.USE_PREF_SIZE);
        retenir.setOnAction(e -> retenirActuel());
        enveloppeRetenir = new StackPane(retenir);
        enveloppeRetenir.setMinWidth(Region.USE_PREF_SIZE);
        HBox ligneRetenir = new HBox(8, choixRubrique, enveloppeRetenir);
        ligneRetenir.setAlignment(Pos.CENTER_LEFT);

        // ---- ajout par numero : replie par defaut
        TextField parNumero = new TextField();
        parNumero.setPromptText("Numéro ou lien");
        parNumero.setMinWidth(80);
        HBox.setHgrow(parNumero, Priority.ALWAYS);
        Button ajouter = new Button("Ajouter");
        ajouter.setMinWidth(Region.USE_PREF_SIZE);
        StackPane enveloppeAjouter = new StackPane(ajouter);
        enveloppeAjouter.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip pourquoiAjouter = Ui.bulle("Tape le numéro d'un appart, ou colle son lien.");
        Runnable reglerAjouter = () -> {
            boolean ok = numero(parNumero.getText()) > 0;
            ajouter.setDisable(!ok);
            if (ok) Tooltip.uninstall(enveloppeAjouter, pourquoiAjouter);
            else Tooltip.install(enveloppeAjouter, pourquoiAjouter);
        };
        parNumero.textProperty().addListener((o, a, b) -> reglerAjouter.run());
        reglerAjouter.run();
        Runnable ajout = () -> {
            String t = parNumero.getText() == null ? "" : parNumero.getText().trim();
            if (t.isEmpty()) return;
            int id = numero(t);
            if (id <= 0) { Journal.erreur("Numéro d'appart introuvable dans « " + t + " »."); return; }
            ajouterLien(id, null, null, rubriqueChoisie());
            parNumero.clear();
        };
        ajouter.setOnAction(e -> ajout.run());
        parNumero.setOnAction(e -> ajout.run());
        HBox ligneNumero = new HBox(8, parNumero, enveloppeAjouter);
        ligneNumero.setAlignment(Pos.CENTER_LEFT);
        ligneNumero.setVisible(false); ligneNumero.setManaged(false);
        Hyperlink deplier = new Hyperlink("Ajouter par numéro ou lien…");
        deplier.setPadding(new Insets(0));
        deplier.setOnAction(e -> {
            boolean montre = !ligneNumero.isVisible();
            ligneNumero.setVisible(montre); ligneNumero.setManaged(montre);
            deplier.setText(montre ? "Masquer l'ajout par numéro" : "Ajouter par numéro ou lien…");
            if (montre) parNumero.requestFocus();
        });

        // ---- mes liens
        recherche.setPromptText("Chercher");
        recherche.setMinWidth(60);
        Ui.bulle(recherche, "Par nom d'appart, propriétaire, numéro ou rubrique.");
        HBox.setHgrow(recherche, Priority.ALWAYS);
        recherche.textProperty().addListener((o, a, b) -> dessiner());
        Button nouvelle = Ui.boutonIcone(Icones.DOSSIER_NOUVEAU, "Nouvelle rubrique");
        nouvelle.setOnAction(e -> nouvelleRubrique(null));
        HBox ligneRecherche = new HBox(8, Icones.petit(Icones.LOUPE), recherche, nouvelle);
        ligneRecherche.setAlignment(Pos.CENTER_LEFT);

        annulationTexte.setWrapText(true);
        annulationTexte.setMinWidth(0);
        annulationTexte.setMinHeight(Region.USE_PREF_SIZE);
        annulationTexte.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(annulationTexte, Priority.ALWAYS);
        Button annuler = Icones.sur(new Button("Annuler"), Icones.ANNULER);
        annuler.setMinWidth(Region.USE_PREF_SIZE);
        annuler.setOnAction(e -> { Runnable r = annulationAction; cacherAnnulation(); if (r != null) r.run(); });
        annulation.getChildren().setAll(annulationTexte, annuler);
        annulation.setAlignment(Pos.CENTER_LEFT);
        annulation.getStyleClass().add("lien-annulation");
        annulation.setVisible(false); annulation.setManaged(false);
        annulationDelai.setOnFinished(e -> cacherAnnulation());

        ScrollPane def = new ScrollPane(liste);
        def.setFitToWidth(true);
        def.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        def.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        def.prefViewportHeightProperty().bind(Bindings.min(340, liste.heightProperty()));
        def.setMaxHeight(344);
        def.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        liste.setFillWidth(true);

        VBox racine = Ui.colonne(
                Ui.bloc("Appart actuel", ligneActuel, ligneRetenir, deplier, ligneNumero,
                        Ui.aide("Retiens l'appart où tu es, ou ajoutes-en un par son numéro ou son lien. "
                                + "Tape un nouveau nom de rubrique pour en créer une.")),
                Ui.bloc("Mes liens", ligneRecherche, annulation, def,
                        Ui.aide("« Y aller » ou double-clic : y retourner. Glisse un appart pour le ranger ou changer "
                                + "son ordre. Clic droit : renommer, ranger, copier le lien, retirer.")));

        // l'appart actuel, suivi en continu (le nom arrive un peu apres l'entree)
        Timeline suivi = new Timeline(new KeyFrame(Duration.millis(1200), e -> suivreSalle()));
        suivi.setCycleCount(Timeline.INDEFINITE);
        suivi.play();
        suivreSalle();
        dessiner();

        Tab t = new Tab("Liens d'apparts", racine);
        t.setClosable(false);
        return t;
    }

    // ------------------------------------------------------------ donnees

    private void lire() {
        rubriques.clear();
        JSONObject o = PrixFichier.lire(FICHIER, null);
        JSONArray a = o == null ? null : o.optJSONArray("rubriques");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            Rubrique ru = new Rubrique(r.optString("nom", "Mes apparts"));
            JSONArray l = r.optJSONArray("liens");
            if (l != null) for (int j = 0; j < l.length(); j++) {
                JSONObject x = l.optJSONObject(j);
                if (x == null || x.optInt("id") <= 0) continue;
                Lien li = new Lien();
                li.id = x.optInt("id");
                li.nom = x.optString("nom", SANS_NOM + li.id);
                li.proprio = x.optString("proprio", "");
                li.ajoute = x.optLong("ajoute", 0);
                li.vu = x.optLong("vu", 0);
                ru.liens.add(li);
            }
            rubriques.add(ru);
        }
        if (rubriques.isEmpty()) rubriques.add(new Rubrique("Mes apparts"));
    }

    private void ecrire() {
        JSONArray a = new JSONArray();
        for (Rubrique r : rubriques) {
            JSONArray l = new JSONArray();
            for (Lien x : r.liens) l.put(x.json());
            a.put(new JSONObject().put("nom", r.nom).put("liens", l));
        }
        if (!PrixFichier.ecrire(FICHIER, new JSONObject().put("rubriques", a)))
            Journal.erreur("Les liens d'apparts n'ont pas pu être enregistrés.");
    }

    private Rubrique rubrique(String nom) {
        if (nom == null) return null;
        for (Rubrique r : rubriques) if (r.nom.equalsIgnoreCase(nom.trim())) return r;
        return null;
    }

    /** Le nom de rubrique tape ou choisi, sans rien creer. */
    private String nomChoisi() {
        String n = choixRubrique.getEditor().getText();
        if (n == null || n.isBlank()) n = choixRubrique.getValue();
        if (n == null || n.isBlank()) n = PREFS.get(CLE_RUBRIQUE, rubriques.get(0).nom);
        return Ui.majuscule(n.trim());
    }

    /** La rubrique tapee ou choisie (creee si nouvelle). */
    private Rubrique rubriqueChoisie() {
        String n = nomChoisi();
        Rubrique r = rubrique(n);
        if (r == null) { r = new Rubrique(n); rubriques.add(r); }
        PREFS.put(CLE_RUBRIQUE, r.nom);
        return r;
    }

    private Lien trouver(int id) {
        for (Rubrique r : rubriques) for (Lien l : r.liens) if (l.id == id) return l;
        return null;
    }

    private Rubrique rubriqueDe(Lien l) {
        for (Rubrique r : rubriques) if (r.liens.contains(l)) return r;
        return null;
    }

    private int total() {
        int n = 0;
        for (Rubrique r : rubriques) n += r.liens.size();
        return n;
    }

    /** Ajoute (ou range a nouveau) un appart ; deja retenu : il change de rubrique. */
    private void ajouterLien(int id, String nom, String proprio, Rubrique r) {
        Lien l = trouver(id);
        if (l != null) {
            Rubrique avant = rubriqueDe(l);
            if (avant == r) { Journal.erreur("« " + l.nom + " » est déjà dans « " + r.nom + " »."); return; }
            if (avant != null) avant.liens.remove(l);
        } else {
            l = new Lien();
            l.id = id;
            l.ajoute = System.currentTimeMillis();
            if (id == Salle.salleId()) l.vu = l.ajoute;
        }
        if (nom != null && !nom.isBlank()) l.nom = nom;
        else if (l.nom == null) l.nom = SANS_NOM + id;
        if (proprio != null) l.proprio = proprio;
        r.liens.add(l);
        fermees.remove(r.nom);
        memoriserFermees();
        ecrire();
        dessiner();
        reglerRetenir();
    }

    private void retenirActuel() {
        int id = Salle.salleId();
        if (!Salle.dansUneSalle() || id <= 0) { Journal.erreur("Entre d'abord dans un appart pour le retenir."); return; }
        ajouterLien(id, NomSalle.nomValide(Salle.gp()), NomSalle.proprietaire(), rubriqueChoisie());
    }

    /** Range l'appart dans la rubrique, a la place donnee (meme rubrique : change son ordre). */
    private void deplacer(Lien l, Rubrique cible, int index) {
        Rubrique avant = rubriqueDe(l);
        if (avant == null) return;
        int i0 = avant.liens.indexOf(l);
        avant.liens.remove(l);
        if (avant == cible && i0 < index) index--;
        index = Math.max(0, Math.min(index, cible.liens.size()));
        cible.liens.add(index, l);
        if (fermees.remove(cible.nom)) memoriserFermees();
        ecrire();
    }

    private void nouvelleRubrique(Lien aRanger) {
        String n = demander("Nouvelle rubrique", "Nom de la rubrique (par exemple Troc, Décoration) :", "");
        if (n == null) return;
        Rubrique x = rubrique(n);
        if (x == null) { x = new Rubrique(n); rubriques.add(x); }
        if (aRanger != null && rubriqueDe(aRanger) != x) deplacer(aRanger, x, x.liens.size());
        ecrire();
        dessiner();
    }

    /** « 12345 », « habbo.fr/room/12345 », « :roomid 12345 » : le numero, 0 sinon. */
    static int numero(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,10})").matcher(s);
        int r = 0;
        while (m.find()) {
            try { r = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { r = 0; }
        }
        return r;
    }

    // ------------------------------------------------------------ annuler un retrait

    private void proposerAnnulation(String texte, Runnable restaurer) {
        annulationTexte.setText(texte);
        annulationAction = restaurer;
        annulation.setVisible(true); annulation.setManaged(true);
        annulationDelai.playFromStart();
    }

    private void cacherAnnulation() {
        annulationDelai.stop();
        annulationAction = null;
        annulation.setVisible(false); annulation.setManaged(false);
    }

    private void retirerLien(Lien l, Rubrique r) {
        int i = r.liens.indexOf(l);
        if (i < 0) return;
        r.liens.remove(i);
        ecrire();
        dessiner();
        reglerRetenir();
        proposerAnnulation("« " + l.nom + " » retiré.", () -> {
            if (trouver(l.id) != null) return;                    // revenu entre-temps
            Rubrique ou = rubriques.contains(r) ? r : rubriques.get(0);
            ou.liens.add(Math.min(i, ou.liens.size()), l);
            ecrire();
            dessiner();
            reglerRetenir();
        });
    }

    private void retirerRubrique(Rubrique r) {
        int i = rubriques.indexOf(r);
        if (i < 0) return;
        rubriques.remove(i);
        boolean remplacee = rubriques.isEmpty();
        if (remplacee) rubriques.add(new Rubrique("Mes apparts"));
        ecrire();
        dessiner();
        reglerRetenir();
        String quoi = r.liens.isEmpty() ? "Rubrique « " + r.nom + " » retirée."
                : "Rubrique « " + r.nom + " » retirée, avec " + Ui.accorder(r.liens.size() + " appart(s)") + ".";
        proposerAnnulation(quoi, () -> {
            if (rubriques.contains(r)) return;
            if (remplacee && rubriques.size() == 1 && rubriques.get(0).liens.isEmpty()) rubriques.clear();
            r.liens.removeIf(l -> trouver(l.id) != null);         // deja retenus ailleurs entre-temps
            rubriques.add(Math.min(i, rubriques.size()), r);
            ecrire();
            dessiner();
            reglerRetenir();
        });
    }

    // ------------------------------------------------------------ appart actuel

    private void suivreSalle() {
        int id = Salle.dansUneSalle() ? Salle.salleId() : -1;
        String nom = id > 0 ? NomSalle.nomValide(Salle.gp()) : null;
        String pr = id > 0 ? NomSalle.proprietaire() : null;
        if (id <= 0) {
            actuelNom.setText("Pas dans un appart");
            actuelDetail.setText("Entre dans un appart pour le retenir.");
        } else {
            actuelNom.setText(nom == null || nom.isBlank() ? SANS_NOM + id : nom);
            actuelDetail.setText(pr == null || pr.isBlank() ? "N°" + id : "De " + pr + " · n°" + id);
        }
        // un appart ajoute par son numero prend son vrai nom quand on y passe
        Lien l = id > 0 ? trouver(id) : null;
        if (l != null && nom != null && !nom.isBlank() && (!nom.equals(l.nom) && l.nom.startsWith(SANS_NOM) || pr != null && !pr.isBlank() && !pr.equals(l.proprio))) {
            if (l.nom.startsWith(SANS_NOM)) l.nom = nom;
            if (pr != null && !pr.isBlank()) l.proprio = pr;
            ecrire();
            salleVue = -2;
        }
        // derniere visite d'un appart retenu
        if (id != derniereVisite) {
            derniereVisite = id;
            if (l != null) { l.vu = System.currentTimeMillis(); ecrire(); }
        }
        // la liste des rubriques du choix
        List<String> noms = new ArrayList<>();
        for (Rubrique r : rubriques) noms.add(r.nom);
        if (!noms.equals(choixRubrique.getItems())) {
            String tape = choixRubrique.getEditor().getText();
            choixRubrique.getItems().setAll(noms);
            if (tape == null || tape.isBlank()) choixRubrique.setValue(PREFS.get(CLE_RUBRIQUE, noms.get(0)));
        }
        if (id != salleVue) { salleVue = id; dessiner(); }
        reglerRetenir();
    }

    /** « Retenir » : desactive quand c'est impossible, la bulle dit pourquoi. */
    private void reglerRetenir() {
        if (retenir == null) return;
        int id = Salle.dansUneSalle() ? Salle.salleId() : -1;
        Lien l = id > 0 ? trouver(id) : null;
        Rubrique ou = l == null ? null : rubriqueDe(l);
        String cible = rubriques.isEmpty() ? "" : nomChoisi();
        String refus = null;
        if (id <= 0) refus = "Entre dans un appart pour le retenir.";
        else if (ou != null && ou.nom.equalsIgnoreCase(cible)) refus = "Déjà dans « " + ou.nom + " ».";
        retenir.setDisable(refus != null);
        retenir.setText(ou != null && refus == null ? "Ranger ici" : "Retenir");
        if (refus != null) { pourquoiRetenir.setText(refus); Tooltip.install(enveloppeRetenir, pourquoiRetenir); }
        else Tooltip.uninstall(enveloppeRetenir, pourquoiRetenir);
        retenir.setTooltip(Ui.bulle(ou != null ? "Il est dans « " + ou.nom + " » : il passera dans « " + cible + " »."
                : "Retenir l'appart dans « " + cible + " »."));
        boolean montre = ou != null;
        actuelBadge.setText(montre ? "Dans " + ou.nom : "");
        actuelBadge.setVisible(montre); actuelBadge.setManaged(montre);
    }

    // ------------------------------------------------------------ dessin

    private void dessiner() {
        String f = recherche.getText() == null ? "" : recherche.getText().trim().toLowerCase(Locale.ROOT);
        int ici = Salle.dansUneSalle() ? Salle.salleId() : -1;
        int total = total();
        List<Node> n = new ArrayList<>();
        recherche.setDisable(total == 0);
        if (total == 0 && f.isEmpty()) {
            n.add(vide(Icones.SALLE, "Aucun appart retenu",
                    ici > 0 ? "Clique « Retenir » pour garder celui-ci." : "Entre dans un appart, puis clique « Retenir »."));
            if (rubriques.size() == 1) { liste.getChildren().setAll(n); return; }
        }
        for (Rubrique r : rubriques) {
            boolean rubriqueVue = f.isEmpty() || r.nom.toLowerCase(Locale.ROOT).contains(f);
            List<Lien> vus = new ArrayList<>();
            for (Lien l : r.liens)
                if (rubriqueVue || l.nom.toLowerCase(Locale.ROOT).contains(f)
                        || (l.proprio != null && l.proprio.toLowerCase(Locale.ROOT).contains(f))
                        || String.valueOf(l.id).contains(f))
                    vus.add(l);
            if (!f.isEmpty() && vus.isEmpty() && !rubriqueVue) continue;
            boolean ouverte = !f.isEmpty() || !fermees.contains(r.nom);
            n.add(tete(r, ouverte, f.isEmpty() ? -1 : vus.size()));
            if (!ouverte) continue;
            if (vus.isEmpty() && total > 0) {
                Label v = Ui.discret(f.isEmpty() ? "Vide : glisse un appart sur le nom de la rubrique." : "Aucun appart ici.");
                v.setPadding(new Insets(0, 0, 4, 22));
                n.add(v);
            }
            for (Lien l : vus) n.add(rangee(l, r, l.id == ici));
        }
        if (n.isEmpty()) {
            n.add(vide(Icones.LOUPE, "Aucun résultat", "Rien ne correspond à « " + recherche.getText().trim() + " »."));
            Hyperlink effacer = new Hyperlink("Effacer la recherche");
            effacer.setPadding(new Insets(0, 0, 0, 26));
            effacer.setOnAction(e -> recherche.clear());
            n.add(effacer);
        }
        liste.getChildren().setAll(n);
    }

    /** Etat vide : un pictogramme, un titre court, une consigne discrete. */
    private static Node vide(String icone, String titre, String consigne) {
        Label t = new Label(titre);
        t.setStyle("-fx-font-weight: bold;");
        VBox textes = new VBox(0, t, Ui.discret(consigne));
        textes.setMinWidth(0);
        HBox.setHgrow(textes, Priority.ALWAYS);
        HBox h = new HBox(8, Icones.petit(icone), textes);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(4, 2, 4, 2));
        return h;
    }

    private static boolean glisseUnLien(DragEvent e) {
        Dragboard db = e.getDragboard();
        return db.hasString() && db.getString().startsWith(GLISSE);
    }

    /** vus : nombre d'apparts trouves par la recherche (-1 sans recherche). */
    private Node tete(Rubrique r, boolean ouverte, int vus) {
        Label fleche = new Label(ouverte ? "▾" : "▸");
        fleche.getStyleClass().add("nav-fleche");
        fleche.setMinWidth(10);
        Label nom = new Label(r.nom);
        nom.getStyleClass().add("lien-rubrique-nom");
        nom.setWrapText(true);
        nom.setMinWidth(0);
        nom.setMinHeight(Region.USE_PREF_SIZE);
        nom.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nom, Priority.ALWAYS);
        Label compte = new Label(vus < 0 ? String.valueOf(r.liens.size()) : vus + " / " + r.liens.size());
        compte.getStyleClass().addAll("badge", "badge-neutre");
        compte.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(compte, Ui.bulle(Ui.accorder(r.liens.size() + " appart(s) dans la rubrique")));

        // actions secondaires : visibles au survol de la tete seulement
        int i = rubriques.indexOf(r);
        Button monter = Ui.boutonIcone(Icones.CASE_MONTER, "Monter la rubrique");
        monter.setOnAction(e -> monterRubrique(r, -1));
        monter.setDisable(i == 0);
        Button renommer = Ui.boutonIcone(Icones.CRAYON, "Renommer la rubrique");
        renommer.setOnAction(e -> renommerRubrique(r));
        Button retirer = Ui.boutonIcone(Icones.CORBEILLE, "Retirer la rubrique (Annuler pour la remettre)");
        retirer.setOnAction(e -> retirerRubrique(r));
        HBox actions = new HBox(2, monter, renommer, retirer);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);

        HBox h = new HBox(6, fleche, nom, compte, actions);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("lien-rubrique");
        h.setCursor(Cursor.HAND);
        actions.opacityProperty().bind(Bindings.when(h.hoverProperty()).then(1.0).otherwise(0.0));
        if (i > 0) VBox.setMargin(h, new Insets(6, 0, 0, 0));
        Tooltip.install(nom, Ui.bulle(ouverte ? "Clique pour replier." : "Clique pour déplier."));
        h.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            for (Node x = e.getTarget() instanceof Node ? (Node) e.getTarget() : null; x != null && x != h; x = x.getParent())
                if (x instanceof ButtonBase) return;
            if (!fermees.remove(r.nom)) fermees.add(r.nom);
            memoriserFermees();
            dessiner();
        });

        // clic droit : les memes actions, et descendre
        ContextMenu menu = new ContextMenu();
        MenuItem mRenommer = new MenuItem("Renommer…");
        mRenommer.setOnAction(e -> renommerRubrique(r));
        MenuItem mMonter = new MenuItem("Monter");
        mMonter.setDisable(i == 0);
        mMonter.setOnAction(e -> monterRubrique(r, -1));
        MenuItem mDescendre = new MenuItem("Descendre");
        mDescendre.setDisable(i == rubriques.size() - 1);
        mDescendre.setOnAction(e -> monterRubrique(r, 1));
        MenuItem mRetirer = new MenuItem("Retirer la rubrique");
        mRetirer.setOnAction(e -> retirerRubrique(r));
        menu.getItems().addAll(mRenommer, mMonter, mDescendre, new SeparatorMenuItem(), mRetirer);
        h.setOnContextMenuRequested(e -> { menu.show(h, e.getScreenX(), e.getScreenY()); e.consume(); });

        // deposer un appart sur la rubrique : il y est range, a la fin
        h.setOnDragOver(e -> {
            if (glisseUnLien(e)) e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        h.setOnDragEntered(e -> { if (glisseUnLien(e) && !h.getStyleClass().contains("cible")) h.getStyleClass().add("cible"); });
        h.setOnDragExited(e -> h.getStyleClass().remove("cible"));
        h.setOnDragDropped(e -> {
            Lien l = glisseUnLien(e) ? trouver(numero(e.getDragboard().getString())) : null;
            if (l != null) deplacer(l, r, r.liens.size());
            e.setDropCompleted(l != null);
            e.consume();
            Platform.runLater(this::dessiner);
        });
        return h;
    }

    private void monterRubrique(Rubrique r, int sens) {
        int i = rubriques.indexOf(r), j = i + sens;
        if (i < 0 || j < 0 || j >= rubriques.size()) return;
        Collections.swap(rubriques, i, j);
        ecrire();
        dessiner();
    }

    private void renommerRubrique(Rubrique r) {
        String nv = demander("Renommer la rubrique", "Nouveau nom :", r.nom);
        if (nv == null || nv.equals(r.nom)) return;
        Rubrique autre = rubrique(nv);
        if (autre != null && autre != r) { autre.liens.addAll(r.liens); rubriques.remove(r); }   // meme nom : fusion
        else { if (fermees.remove(r.nom)) fermees.add(nv); r.nom = nv; }
        memoriserFermees();
        ecrire();
        dessiner();
        reglerRetenir();
    }

    private Node rangee(Lien l, Rubrique r, boolean ici) {
        Label nom = new Label(l.nom);
        nom.setStyle("-fx-font-weight: bold;");
        nom.setWrapText(true);
        nom.setMinHeight(Region.USE_PREF_SIZE);
        List<String> d = new ArrayList<>();
        if (l.proprio != null && !l.proprio.isBlank()) d.add("De " + l.proprio);
        d.add((d.isEmpty() ? "N°" : "n°") + l.id);
        if (!ici && l.vu > 0) d.add("vu " + ilYa(l.vu));
        Label det = Ui.discret(String.join(" · ", d));
        VBox textes = new VBox(0, nom, det);
        textes.setMinWidth(0);
        textes.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textes, Priority.ALWAYS);

        Node action;
        if (ici) {
            Label b = new Label("Tu y es");
            b.getStyleClass().addAll("badge", "badge-ok");
            b.setMinWidth(Region.USE_PREF_SIZE);
            action = b;
        } else {
            Button aller = Icones.sur(new Button(enRoute == l.id ? "En route…" : "Y aller"), Icones.MARCHE);
            aller.getStyleClass().add("lien-aller");
            aller.setMinWidth(Region.USE_PREF_SIZE);
            aller.setOnAction(e -> aller(l));
            StackPane env = new StackPane(aller);
            env.setMinWidth(Region.USE_PREF_SIZE);
            if (enRoute == l.id) aller.setDisable(true);
            else if (Salle.gp() == null) {
                aller.setDisable(true);
                Tooltip.install(env, Ui.bulle("Le jeu n'est pas encore connecté."));
            } else aller.setTooltip(Ui.bulle("Aller dans « " + l.nom + " » (ou double-clique la ligne)."));
            action = env;
        }

        Node icone = Icones.petit(Icones.SALLE);
        HBox h = new HBox(8, icone, textes, action);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("lien-rangee");
        if (ici) h.getStyleClass().add("ici");
        VBox.setMargin(h, new Insets(0, 0, 0, 12));
        h.setCursor(ici ? Cursor.DEFAULT : Cursor.HAND);
        h.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !ici) aller(l);
        });

        // glisser : ranger ailleurs ou changer l'ordre
        h.setOnDragDetected(e -> {
            Dragboard db = h.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent c = new ClipboardContent();
            c.putString(GLISSE + l.id);
            db.setContent(c);
            try {
                SnapshotParameters p = new SnapshotParameters();
                p.setFill(Color.TRANSPARENT);
                db.setDragView(h.snapshot(p, null), e.getX(), e.getY());
            } catch (Throwable ignored) { }
            h.getStyleClass().add("traine");
            e.consume();
        });
        h.setOnDragDone(e -> { h.getStyleClass().remove("traine"); e.consume(); });
        h.setOnDragOver(e -> {
            if (!glisseUnLien(e) || numero(e.getDragboard().getString()) == l.id) { e.consume(); return; }
            e.acceptTransferModes(TransferMode.MOVE);
            boolean avant = e.getY() < h.getHeight() / 2;
            h.getStyleClass().removeAll("cible-avant", "cible-apres");
            h.getStyleClass().add(avant ? "cible-avant" : "cible-apres");
            e.consume();
        });
        h.setOnDragExited(e -> h.getStyleClass().removeAll("cible-avant", "cible-apres"));
        h.setOnDragDropped(e -> {
            Lien g = glisseUnLien(e) ? trouver(numero(e.getDragboard().getString())) : null;
            boolean avant = e.getY() < h.getHeight() / 2;
            if (g != null && g != l) {
                int i = r.liens.indexOf(l);
                deplacer(g, r, avant ? i : i + 1);
            }
            e.setDropCompleted(g != null);
            e.consume();
            Platform.runLater(this::dessiner);
        });

        // clic droit : tout le reste
        ContextMenu menu = new ContextMenu();
        MenuItem mAller = new MenuItem("Y aller");
        mAller.setDisable(ici);
        mAller.setOnAction(e -> aller(l));
        MenuItem mRenommer = new MenuItem("Renommer…");
        mRenommer.setOnAction(e -> {
            String nv = demander("Renommer l'appart", "Nom affiché :", l.nom);
            if (nv != null) { l.nom = nv; ecrire(); dessiner(); }
        });
        Menu ranger = new Menu("Ranger dans");
        for (Rubrique x : rubriques) {
            MenuItem it = new MenuItem(x.nom);
            it.setDisable(x == r);
            it.setOnAction(e -> { deplacer(l, x, x.liens.size()); memoriserFermees(); dessiner(); reglerRetenir(); });
            ranger.getItems().add(it);
        }
        MenuItem nouvelleR = new MenuItem("Nouvelle rubrique…");
        nouvelleR.setOnAction(e -> { nouvelleRubrique(l); reglerRetenir(); });
        ranger.getItems().addAll(new SeparatorMenuItem(), nouvelleR);
        MenuItem copier = new MenuItem("Copier le lien");
        copier.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString("https://www.habbo.fr/room/" + l.id);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
        });
        MenuItem retirer = new MenuItem("Retirer");
        retirer.setOnAction(e -> retirerLien(l, r));
        menu.getItems().addAll(mAller, mRenommer, ranger, copier, new SeparatorMenuItem(), retirer);
        h.setOnContextMenuRequested(e -> { menu.show(h, e.getScreenX(), e.getScreenY()); e.consume(); });
        return h;
    }

    /** « à l'instant », « il y a 5 min », « il y a 3 h », « hier », « il y a 4 jours », « il y a 2 mois ». */
    static String ilYa(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "à l'instant";
        long m = s / 60;
        if (m < 60) return "il y a " + m + " min";
        long h = m / 60;
        if (h < 24) return "il y a " + h + " h";
        long j = h / 24;
        if (j < 2) return "hier";
        if (j < 31) return "il y a " + j + " jours";
        long mo = j / 30;
        if (mo < 12) return "il y a " + mo + " mois";
        return Ui.accorder("il y a " + (j / 365) + " an(s)");
    }

    private void memoriserFermees() { PREFS.put(CLE_FERMEES, String.join("\n", fermees)); }

    // ------------------------------------------------------------ aller dans un appart

    /** Comme un lien du jeu (RoomForward) ; sans effet : OpenFlatConnection au serveur. */
    private void aller(Lien l) {
        Moteur gp = Salle.gp();
        if (gp == null) { Journal.erreur("Le jeu n'est pas encore connecté."); return; }
        if (enRoute == l.id) return;
        int id = l.id;
        enRoute = id;
        dessiner();
        Salle.tache("aller-appart", () -> {
            try {
                for (int essai = 0; essai < 2; essai++) {
                    Salle.espacer();
                    if (essai == 0)
                        gp.sendToClient(RechercheReseau.paquet(HMessage.Direction.TOCLIENT, new String[]{"RoomForward"}, id));
                    else
                        gp.sendToServer(RechercheReseau.paquet(HMessage.Direction.TOSERVER, new String[]{"OpenFlatConnection"}, id, "", -1));
                    Salle.envoiFait();
                    long fin = System.currentTimeMillis() + 6000;
                    while (System.currentTimeMillis() < fin) {
                        if (Salle.salleId() == id) return;
                        Salle.sommeil(200);
                    }
                }
                Journal.erreur("Impossible d'entrer dans « " + l.nom + " » (appart fermé, supprimé, ou réservé à ses amis ?).");
            } finally {
                Platform.runLater(() -> { if (enRoute == id) { enRoute = -1; dessiner(); } });
            }
        });
    }

    // ------------------------------------------------------------ petites fenetres

    private static String demander(String titre, String question, String valeur) {
        TextInputDialog d = new TextInputDialog(valeur);
        d.setTitle(titre);
        d.setHeaderText(null);
        d.setContentText(question);
        Optional<String> r = d.showAndWait();
        if (r.isEmpty() || r.get().isBlank()) return null;
        return Ui.majuscule(r.get().trim());
    }
}

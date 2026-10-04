package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Volet « Liste de courses » : ce qu'il faut pour monter un appart, et ce qui
 * manque dans l'inventaire.
 *
 * Source : un appart enregistre (wired compris, ce sont des mobis
 * comme les autres), la zone, ou toute la salle. Pour chaque mobi : besoin,
 * quantite en inventaire (+ dans la salle si l'option est cochee, comme le
 * « useRoomFurni » du moteur de l'Atelier), manque, et s'il est au catalogue BC.
 *
 * Le BC se lit dans le moteur de l'Atelier (getFloorProduct / getAnyWallProduct) ; tant
 * qu'il n'est pas charge, on se rabat sur son cache disque (BC_CATALOG_*.txt).
 * Ces donnees ne contiennent pas de prix : pas de colonne prix.
 */
public class OutilCourses {

    /** Une ligne du tableau. */
    public static final class Ligne {
        final String nom, classe; final boolean mural, wired;
        final int besoin, inventaire, salle;
        final String bc;      // "oui" | "non" | "?"
        Ligne(String nom, String classe, boolean mural, boolean wired, int besoin, int inventaire, int salle, String bc) {
            this.nom = nom; this.classe = classe; this.mural = mural; this.wired = wired;
            this.besoin = besoin; this.inventaire = inventaire; this.salle = salle; this.bc = bc;
        }
        int dispo() { return Math.max(0, inventaire) + salle; }
        int manque() { return Math.max(0, besoin - dispo()); }
    }

    /** Un besoin, avant comparaison. */
    private static final class Besoin {
        String classe; Integer typeId; boolean mural; int nombre;
    }

    private static final String S_APPART = "Un appart enregistré", S_ZONE = "La zone", S_SALLE = "Toute la salle";

    private ToggleGroup gSource;
    private RadioButton rAppart, rZone, rSalle;
    private ComboBox<String> choixAppart;
    private CheckBox avecSalle, seulementManquants;
    private TableView<Ligne> table;
    private Label total, etat;
    private Ui.Voyant vInv, vBc;
    private List<Ligne> lignes = new ArrayList<>();
    private String titreSource = "";
    private volatile boolean enCours = false;
    /** Une demande arrivee pendant un calcul : on recalcule a la fin. */
    private volatile boolean aRefaire = false;

    // prix estimes (habbofurni.xyz)
    private CheckBox prixDeTout;
    private Label avertPrix, estimation;
    private final java.util.concurrent.atomic.AtomicBoolean rafraichPrevu = new java.util.concurrent.atomic.AtomicBoolean();
    private String erreurPrix = null;

    public VBox construire() {
        gSource = new ToggleGroup();
        rAppart = radio(S_APPART, true);
        rZone = radio(S_ZONE, false);
        rSalle = radio(S_SALLE, false);

        choixAppart = new ComboBox<>();
        choixAppart.setMaxWidth(Double.MAX_VALUE);
        choixAppart.setPromptText("Aucun appart dans le dossier des apparts");
        choixAppart.setOnShowing(e -> chargerListe());
        choixAppart.valueProperty().addListener((o, a, b) -> { if (rAppart.isSelected()) calculer(); });
        choixAppart.disableProperty().bind(rAppart.selectedProperty().not());

        avecSalle = new CheckBox("Compter aussi les mobis déjà dans la salle");
        avecSalle.setWrapText(true);
        avecSalle.selectedProperty().addListener((o, a, b) -> calculer());
        avecSalle.disableProperty().bind(rSalle.selectedProperty());
        seulementManquants = new CheckBox("N'afficher que ce qui manque");
        seulementManquants.selectedProperty().addListener((o, a, b) -> afficher());

        gSource.selectedToggleProperty().addListener((o, a, b) -> calculer());
        Zone.ecouter(() -> { if (rZone.isSelected()) calculer(); });

        vInv = new Ui.Voyant("Inventaire");
        vBc = new Ui.Voyant("Catalogue BC");

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Rien à afficher"));
        TableColumn<Ligne, String> cNom = new TableColumn<>("Mobi");
        cNom.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nom
                + (c.getValue().mural ? " (mur)" : "")));
        cNom.setPrefWidth(140);
        TableColumn<Ligne, Integer> cBes = colNombre("Besoin", l -> l.besoin);
        TableColumn<Ligne, Integer> cInv = colNombre("Inv.", l -> l.inventaire);
        TableColumn<Ligne, Integer> cMan = colNombre("Manque", Ligne::manque);
        TableColumn<Ligne, String> cBc = new TableColumn<>("BC");
        cBc.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().bc));
        cBc.setPrefWidth(38);
        cInv.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean vide) {
                super.updateItem(v, vide);
                if (vide || v == null) { setText(null); setTooltip(null); return; }
                setText(v < 0 ? "?" : String.valueOf(v));
                Ligne l = getTableRow() == null ? null : getTableRow().getItem();
                setTooltip(l != null && l.salle > 0 ? new Tooltip("+ " + l.salle + " déjà dans la salle") : null);
            }
        });
        cMan.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean vide) {
                super.updateItem(v, vide);
                if (vide || v == null) { setText(null); setStyle(""); return; }
                setText(v == 0 ? "—" : String.valueOf(v));
                setStyle(v > 0 ? "-fx-text-fill: #b03a2e; -fx-font-weight: bold;" : "");
            }
        });
        table.getColumns().add(cNom);
        table.getColumns().add(cBes);
        table.getColumns().add(cInv);
        table.getColumns().add(cMan);
        table.getColumns().add(cBc);

        TableColumn<Ligne, String> cPrix = new TableColumn<>("Prix ~");
        cPrix.setCellValueFactory(c -> new ReadOnlyStringWrapper(textePrix(c.getValue())));
        cPrix.setPrefWidth(52);
        cPrix.setStyle("-fx-alignment: CENTER-RIGHT;");
        cPrix.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean vide) {
                super.updateItem(v, vide);
                if (vide || v == null) { setText(null); setTooltip(null); return; }
                setText(v);
                Ligne l = getTableRow() == null ? null : getTableRow().getItem();
                PrixHabbofurni.Entree e = l == null ? null : PrixHabbofurni.enCache(l.classe);
                String t = null;
                if (e != null && e.statut == PrixHabbofurni.Statut.BASE)
                    t = "Prix de la couleur de base (variante introuvable sur habbofurni.xyz)";
                else if (e != null && e.statut == PrixHabbofurni.Statut.INTROUVABLE) t = "Mobi absent de habbofurni.xyz";
                else if (e != null && e.statut == PrixHabbofurni.Statut.SANS_VALEUR) t = "Pas de valeur sur habbofurni.xyz";
                else if (e != null) t = "Prix moyen de revente sur habbo.fr, par unité (indicatif)";
                setTooltip(t == null ? null : new Tooltip(t));
            }
        });
        table.getColumns().add(cPrix);
        table.setPrefHeight(300);
        table.setMinHeight(200);
        VBox.setVgrow(table, Priority.ALWAYS);

        total = Ui.valeur("—");
        total.setWrapText(true);
        etat = Ui.etat();

        Button copier = new Button("Copier la liste");
        copier.setOnAction(e -> copier());

        prixDeTout = new CheckBox("Prix de tout (pas seulement des manquants)");
        prixDeTout.setWrapText(true);
        prixDeTout.selectedProperty().addListener((o, a, b) -> { if (b) lancerPrix(false); majTotaux(); });
        estimation = Ui.valeur("");
        estimation.setWrapText(true);
        avertPrix = new Label();
        avertPrix.setWrapText(true);
        avertPrix.getStyleClass().add("etat-ligne");   // texte discret, sans encadre
        avertPrix.setMaxWidth(Double.MAX_VALUE);
        majAvertissement();

        // Tout se recalcule seul : au changement de source, et quand
        // l'inventaire ou la salle changent (vérifié toutes les 2 s).
        VBox v = new VBox(12,
                Ui.bloc("Source", new VBox(4, rAppart, rZone, rSalle), choixAppart, avecSalle),
                Ui.bloc("Liste", seulementManquants, table, total, estimation, avertPrix,
                        Ui.ligne(copier), prixDeTout),
                etat);
        javafx.animation.Timeline suivi = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> suivreChangements()));
        suivi.setCycleCount(javafx.animation.Animation.INDEFINITE);
        suivi.play();
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        chargerListe();
        return v;
    }

    private RadioButton radio(String t, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(gSource);
        r.setSelected(sel);
        return r;
    }

    private static TableColumn<Ligne, Integer> colNombre(String titre, java.util.function.ToIntFunction<Ligne> f) {
        TableColumn<Ligne, Integer> c = new TableColumn<>(titre);
        c.setCellValueFactory(x -> new ReadOnlyObjectWrapper<>(f.applyAsInt(x.getValue())));
        c.setPrefWidth(52);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    // ------------------------------------------------------------ apparts

    private void chargerListe() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(this::chargerListe); return; }
        List<String> noms = new ArrayList<>();
        try {
            File[] fs = OngletApparts.dossierApparts().listFiles((d, n) -> n.endsWith(".json"));
            if (fs != null) {
                Arrays.sort(fs, Comparator.comparing(File::getName));
                for (File f : fs) noms.add(f.getName().substring(0, f.getName().length() - 5));
            }
        } catch (Throwable ignored) { }
        String garde = choixAppart.getValue();
        if (!noms.equals(choixAppart.getItems())) choixAppart.getItems().setAll(noms);
        if (garde != null && noms.contains(garde)) choixAppart.setValue(garde);
        else if (!noms.isEmpty() && garde == null) choixAppart.setValue(noms.get(0));
    }

    // ------------------------------------------------------------ calcul

    private void calculer() {
        if (enCours) { aRefaire = true; return; }
        aRefaire = false;
        final String source = rAppart.isSelected() ? S_APPART : rZone.isSelected() ? S_ZONE : S_SALLE;
        final String appart = choixAppart.getValue();
        final boolean salle = avecSalle.isSelected() && !rSalle.isSelected();
        enCours = true;
        etat.setText("Calcul...");
        Salle.tache("courses", () -> {
            String msg;
            List<Ligne> res = null;
            String titre = "";
            try {
                Map<String, Besoin> b;
                if (S_APPART.equals(source)) {
                    if (appart == null) throw new IllegalStateException("aucun appart choisi");
                    b = besoinsAppart(appart);
                    titre = "appart « " + appart + " »";
                } else if (S_ZONE.equals(source)) {
                    if (!Zone.definie()) throw new IllegalStateException("aucune zone définie (onglet Plan ou « Choisir dans le jeu »)");
                    if (!Salle.dansUneSalle()) throw new IllegalStateException("pas dans une salle");
                    b = besoinsSalle(true);
                    titre = "zone " + Zone.texte();
                } else {
                    if (!Salle.dansUneSalle()) throw new IllegalStateException("pas dans une salle");
                    b = besoinsSalle(false);
                    titre = "salle actuelle";
                }
                res = comparer(b, salle, S_ZONE.equals(source));
                msg = null;
            } catch (Throwable t) {
                msg = t instanceof IllegalStateException ? t.getMessage() : "erreur : " + t;
            }
            final List<Ligne> r = res; final String m = msg; final String ti = titre;
            Platform.runLater(() -> {
                enCours = false;
                // la source a change pendant le calcul : ce resultat est perime
                if (aRefaire) { aRefaire = false; calculer(); return; }
                majVoyants();
                if (m != null) { etat.setText("Impossible : " + m + "."); lignes = new ArrayList<>(); afficher(); return; }
                lignes = r; titreSource = ti;
                afficher();
                lancerPrix(false);
                String n = "Liste calculée pour " + ti + ".";
                if (!Salle.furnidataPrete()) n += " Furnidata pas encore chargée : noms techniques, inventaire non comparable.";
                if (!inventaireCharge()) n += " L'inventaire n'est PAS chargé : la colonne « Inv. » est inconnue (?).";
                etat.setText(n);
            });
        });
    }

    private static Map<String, Besoin> besoinsAppart(String nom) throws Exception {
        File f = new File(OngletApparts.dossierApparts(), nom + ".json");
        extension.tools.presetconfig.PresetConfig pc =
                new extension.tools.presetconfig.PresetConfig(OngletApparts.lirePreset(f));
        Map<String, Besoin> m = new LinkedHashMap<>();
        if (pc.getFurniture() != null)
            for (extension.tools.presetconfig.furni.PresetFurni p : pc.getFurniture())
                ajouter(m, base(p.getClassName()), null, false);
        if (pc.getWallFurniture() != null)
            for (extension.tools.presetconfig.furni.PresetWallFurni p : pc.getWallFurniture())
                ajouter(m, base(p.getClassName()), null, true);
        return m;
    }

    private static Map<String, Besoin> besoinsSalle(boolean zone) {
        Map<String, Besoin> m = new LinkedHashMap<>();
        for (HFloorItem it : Salle.sols()) {
            if (zone && !Zone.contient(it.getTile().getX(), it.getTile().getY())) continue;
            ajouter(m, Salle.classe(it.getTypeId(), false), it.getTypeId(), false);
        }
        for (HWallItem w : Salle.murs()) {
            if (zone) {
                int[] p = OutilCompteur.caseMur(w.getLocation());
                if (p == null || !Zone.contient(p[0], p[1])) continue;
            }
            ajouter(m, Salle.classe(w.getTypeId(), true), w.getTypeId(), true);
        }
        return m;
    }

    private static void ajouter(Map<String, Besoin> m, String classe, Integer typeId, boolean mural) {
        String cle = (mural ? "W:" : "F:") + (classe != null ? classe : "#" + typeId);
        Besoin b = m.computeIfAbsent(cle, k -> new Besoin());
        b.classe = classe; b.mural = mural;
        if (typeId != null) b.typeId = typeId;
        b.nombre++;
    }

    /** L'export ajoute parfois un index d'instance « classe[0] » : a retirer. */
    private static String base(String cls) {
        if (cls == null) return null;
        int i = cls.indexOf('[');
        if (i > 0) cls = cls.substring(0, i);
        return cls.trim();
    }

    private static Integer typeId(String classe, boolean mural) {
        if (classe == null || !Salle.furnidataPrete()) return null;
        try {
            furnidata.FurniDataTools fd = Salle.gp().getFurniDataTools();
            Integer t = mural ? fd.getWallTypeId(classe) : fd.getFloorTypeId(classe);
            if (t == null && classe.contains("*")) {
                String b = classe.substring(0, classe.indexOf('*'));
                t = mural ? fd.getWallTypeId(b) : fd.getFloorTypeId(b);
            }
            return t;
        } catch (Throwable e) { return null; }
    }

    private static List<Ligne> comparer(Map<String, Besoin> besoins, boolean avecSalle, boolean horsZone) {
        GPresets gp = Salle.gp();
        boolean inv = inventaireCharge();
        Map<Integer, Integer> invSol = new HashMap<>(), invMur = new HashMap<>();
        if (gp != null && inv) {
            try {
                for (gearth.extensions.parsers.HInventoryItem it : gp.getInventory().getInventoryItems()) {
                    if (it.getType() == gearth.extensions.parsers.HProductType.WallItem)
                        invMur.merge(it.getTypeId(), 1, Integer::sum);
                    else if (it.getType() == gearth.extensions.parsers.HProductType.FloorItem)
                        invSol.merge(it.getTypeId(), 1, Integer::sum);
                }
            } catch (Throwable t) { inv = false; }
        }
        Map<Integer, Integer> salleSol = new HashMap<>(), salleMur = new HashMap<>();
        if (avecSalle) {
            for (HFloorItem it : Salle.sols())
                if (!horsZone || !Zone.contient(it.getTile().getX(), it.getTile().getY()))
                    salleSol.merge(it.getTypeId(), 1, Integer::sum);
            for (HWallItem w : Salle.murs()) {
                if (horsZone) {
                    int[] p = OutilCompteur.caseMur(w.getLocation());
                    if (p != null && Zone.contient(p[0], p[1])) continue;
                }
                salleMur.merge(w.getTypeId(), 1, Integer::sum);
            }
        }
        Catalogue cat = Catalogue.lire(gp);

        List<Ligne> r = new ArrayList<>();
        for (Besoin b : besoins.values()) {
            Integer tid = b.typeId != null ? b.typeId : typeId(b.classe, b.mural);
            String nom = tid != null ? Salle.nom(tid, b.mural) : null;
            if (nom == null || nom.startsWith("type ")) nom = b.classe != null ? b.classe : "type " + b.typeId;
            int qInv = -1, qSalle = 0;
            if (tid != null) {
                if (inv) qInv = (b.mural ? invMur : invSol).getOrDefault(tid, 0);
                qSalle = (b.mural ? salleMur : salleSol).getOrDefault(tid, 0);
            }
            r.add(new Ligne(nom, b.classe, b.mural, Wired.estWired(b.classe), b.nombre, qInv, qSalle,
                    cat.auBc(gp, tid, b.classe, b.mural)));
        }
        r.sort((x, y) -> x.manque() != y.manque() ? Integer.compare(y.manque(), x.manque())
                : x.nom.compareToIgnoreCase(y.nom));
        return r;
    }

    static boolean inventaireCharge() {
        try { return "LOADED".equals(String.valueOf(Salle.gp().getInventory().getState())); }
        catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------- catalogue BC

    /**
     * Disponibilite au BC. D'abord le catalogue charge par le moteur de l'Atelier ; sinon
     * son cache disque, lignes « F|W  classe  page  offre  [param] ».
     */
    private static final class Catalogue {
        boolean charge;
        Set<String> cache;   // "F:classe" / "W:classe", null si pas de cache

        static Catalogue lire(GPresets gp) {
            Catalogue c = new Catalogue();
            try { c.charge = gp != null && "COLLECTED".equals(String.valueOf(gp.getCatalog().getState())); }
            catch (Throwable ignored) { }
            if (!c.charge) c.cache = cacheDisque();
            return c;
        }

        String auBc(GPresets gp, Integer tid, String classe, boolean mural) {
            if (charge && tid != null) {
                try {
                    Object p = mural ? gp.getCatalog().getAnyWallProduct(tid) : gp.getCatalog().getFloorProduct(tid);
                    return p != null ? "oui" : "non";
                } catch (Throwable ignored) { }
            }
            if (cache != null && classe != null)
                return cache.contains((mural ? "W:" : "F:") + classe) ? "oui" : "non";
            return "?";
        }
    }

    private static volatile Set<String> cacheBc;
    private static volatile long cacheBcDate = -1;

    /** Le plus recent BC_CATALOG_*.txt de ~/Documents/Atelier/catalog, ou null. */
    private static Set<String> cacheDisque() {
        try {
            File d = new File(Dossiers.maison(), "Documents" + File.separator + "Atelier" + File.separator + "catalog");
            File[] fs = d.listFiles((x, n) -> n.startsWith("BC_CATALOG_") && n.endsWith(".txt"));
            if (fs == null || fs.length == 0) return null;
            File plusRecent = Collections.max(Arrays.asList(fs), Comparator.comparingLong(File::lastModified));
            if (cacheBc != null && cacheBcDate == plusRecent.lastModified()) return cacheBc;
            Set<String> s = new HashSet<>();
            for (String l : Files.readAllLines(plusRecent.toPath(), StandardCharsets.UTF_8)) {
                String[] p = l.split("\t");
                if (p.length >= 2 && (p[0].equals("F") || p[0].equals("W"))) s.add(p[0] + ":" + p[1].trim());
            }
            cacheBc = s; cacheBcDate = plusRecent.lastModified();
            return s;
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ affichage

    private void afficher() {
        List<Ligne> vue = new ArrayList<>();
        for (Ligne l : lignes)
            if (!seulementManquants.isSelected() || l.manque() > 0) vue.add(l);
        table.getItems().setAll(vue);
        majTotaux();
    }

    private void majTotaux() {
        majEstimation();
        majAvertissement();
        int manque = 0, types = 0, auBc = 0, horsBc = 0, besoin = 0;
        for (Ligne l : lignes) {
            besoin += l.besoin;
            if (l.manque() > 0) {
                manque += l.manque(); types++;
                if ("oui".equals(l.bc)) auBc += l.manque(); else horsBc += l.manque();
            }
        }
        if (lignes.isEmpty()) { total.setText("—"); return; }
        if (!inventaireCharge()) {
            total.setText("Besoin : " + besoin + " mobis (" + lignes.size() + " types). Inventaire non chargé : "
                    + "les manquants ne sont pas fiables.");
            return;
        }
        total.setText(manque == 0 ? "Rien ne manque (" + besoin + " mobis, " + lignes.size() + " types)."
                : "Manquants : " + manque + " mobis (" + types + " types) · dont " + auBc
                  + " au BC, " + horsBc + " hors BC ou inconnus.");
    }

    private void majVoyants() {
        GPresets gp = Salle.gp();
        if (gp == null) { vInv.regler("absent", "L'Atelier n'est pas encore prêt"); vBc.regler("absent", "L'Atelier n'est pas encore prêt"); return; }
        String ei = "?";
        int n = 0;
        try { ei = String.valueOf(gp.getInventory().getState()); n = gp.getInventory().getInventoryItems().size(); }
        catch (Throwable ignored) { }
        if ("LOADED".equals(ei)) vInv.regler("ok", "Chargé (" + n + " objets).");
        else if ("LOADING".equals(ei)) vInv.regler("attente", "Chargement en cours...");
        else vInv.regler("absent", "Non chargé : ouvre l'inventaire dans le jeu");
        String eb = "?";
        try { eb = String.valueOf(gp.getCatalog().getState()); } catch (Throwable ignored) { }
        if ("COLLECTED".equals(eb)) vBc.regler("ok", "Chargé.");
        else if (cacheDisque() != null) vBc.regler("attente", "Pas chargé : cache disque utilisé");
        else vBc.regler("absent", "Pas chargé : colonne BC inconnue (?)");
    }

    private void copier() {
        if (lignes.isEmpty()) { etat.setText("Rien à copier."); return; }
        StringBuilder sb = new StringBuilder("Liste de courses — " + titreSource + "\n");
        if (!inventaireCharge()) sb.append("(inventaire non chargé : quantités en inventaire inconnues)\n");
        sb.append("\nÀ trouver :\n");
        boolean rien = true;
        for (Ligne l : lignes) {
            if (l.manque() <= 0) continue;
            rien = false;
            sb.append("- ").append(l.nom).append(l.mural ? " (mur)" : "").append(" x").append(l.manque())
              .append("   (besoin ").append(l.besoin).append(", inventaire ")
              .append(l.inventaire < 0 ? "?" : String.valueOf(l.inventaire));
            if (l.salle > 0) sb.append(", salle ").append(l.salle);
            sb.append(", BC : ").append(l.bc);
            PrixHabbofurni.Entree e = PrixHabbofurni.enCache(l.classe);
            if (e != null && e.credits != null)
                sb.append(", ~").append(Plan.fmt(e.credits)).append(" cr/u")
                  .append(e.statut == PrixHabbofurni.Statut.BASE ? " (prix de la base)" : "");
            sb.append(")\n");
        }
        if (rien) sb.append("(rien)\n");
        sb.append("\nDéjà couvert :\n");
        for (Ligne l : lignes)
            if (l.manque() <= 0)
                sb.append("- ").append(l.nom).append(l.mural ? " (mur)" : "").append(" x").append(l.besoin).append('\n');
        sb.append('\n').append(total.getText()).append('\n');
        if (!estimation.getText().isEmpty()) sb.append(estimation.getText()).append('\n');
        sb.append('\n').append(avertPrix.getText()).append('\n');
        ClipboardContent c = new ClipboardContent();
        c.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(c);
        etat.setText("Liste copiée dans le presse-papiers.");
    }

    private String vuInventaireSalle = null;

    /** Recalcule si l'inventaire ou la salle ont change depuis le dernier calcul. */
    private void suivreChangements() {
        GPresets gp = Salle.gp();
        if (gp == null) return;
        int inv = -1, salle = -1;
        try { inv = gp.getInventory().getInventoryItems().size(); } catch (Throwable ignored) { }
        try { salle = Salle.sols().size() + Salle.murs().size(); } catch (Throwable ignored) { }
        String sig = inv + "/" + salle + "/" + Salle.furnidataPrete();
        if (sig.equals(vuInventaireSalle)) return;
        boolean premier = vuInventaireSalle == null;
        vuInventaireSalle = sig;
        majVoyants();
        if (!premier || rAppart.isSelected()) calculer();
    }

    // ------------------------------------------------------- prix estimes

    private static String textePrix(Ligne l) {
        if (l == null || l.classe == null) return "?";
        PrixHabbofurni.Entree e = PrixHabbofurni.enCache(l.classe);
        if (e == null) return PrixHabbofurni.enAttente(l.classe) ? "…" : "?";
        if (e.credits == null) return "?";
        return Plan.fmt(e.credits) + (e.statut == PrixHabbofurni.Statut.BASE ? "*" : "");
    }

    /** Demande les prix manquants (ou tous si forcer), sans bloquer la liste. */
    private void lancerPrix(boolean forcer) {
        List<String> classes = new ArrayList<>();
        for (Ligne l : lignes)
            if (l.classe != null && (prixDeTout.isSelected() || l.manque() > 0)) classes.add(l.classe);
        if (classes.isEmpty()) { if (forcer) etat.setText("Aucun mobi dont chercher le prix."); return; }
        erreurPrix = null;
        if (forcer) etat.setText("Lecture des prix sur habbofurni.xyz (" + classes.size() + " mobis)...");
        PrixHabbofurni.demander(classes, forcer, c -> rafraichirBientot(), err -> Platform.runLater(() -> {
            erreurPrix = err;
            table.refresh();
            majTotaux();
            if (err != null) etat.setText("Prix indisponibles : " + err + ". La liste reste utilisable sans prix.");
            else if (forcer) etat.setText("Prix actualisés.");
        }));
        table.refresh();
        majEstimation();
    }

    /** Au plus un rafraichissement du tableau toutes les 400 ms pendant la lecture des prix. */
    private void rafraichirBientot() {
        if (!rafraichPrevu.compareAndSet(false, true)) return;
        Salle.tache("prix-rafraichir", () -> {
            Salle.sommeil(400);
            rafraichPrevu.set(false);
            Platform.runLater(() -> { table.refresh(); majEstimation(); });
        });
    }

    private void majEstimation() {
        if (estimation == null) return;
        double somme = 0;
        int sansPrix = 0, attente = 0, avecPrix = 0;
        boolean base = false;
        for (Ligne l : lignes) {
            if (l.manque() <= 0) continue;
            PrixHabbofurni.Entree e = PrixHabbofurni.enCache(l.classe);
            if (e != null && e.credits != null) {
                somme += e.credits * l.manque(); avecPrix++;
                if (e.statut == PrixHabbofurni.Statut.BASE) base = true;
            } else if (l.classe != null && PrixHabbofurni.enAttente(l.classe)) attente++;
            else sansPrix++;
        }
        if (lignes.isEmpty() || avecPrix + sansPrix + attente == 0) { estimation.setText(""); return; }
        StringBuilder sb = new StringBuilder("Total estimé des manquants : ≈ ")
                .append(String.format(Locale.FRANCE, "%,d", Math.round(somme))).append(" crédits");
        if (sansPrix > 0) sb.append(" (").append(sansPrix).append(sansPrix > 1 ? " types sans prix)" : " type sans prix)");
        if (attente > 0) sb.append(" · lecture en cours (").append(attente).append(")…");
        if (base) sb.append(" · * = prix de la couleur de base");
        estimation.setText(sb.toString());
    }

    private void majAvertissement() {
        if (avertPrix == null) return;
        long d = PrixHabbofurni.dateCache();
        String quand = d <= 0 ? "Pas encore de prix en cache"
                : "Mise à jour du " + new java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(new Date(d));
        String t = "Prix indicatifs d'après habbofurni.xyz (moyenne des ventes entre joueurs sur habbo.fr) : "
                + "ce ne sont pas toujours les vrais prix. (" + quand + ")";
        if (erreurPrix != null) t += "\nSite injoignable pour l'instant : prix incomplets.";
        avertPrix.setText(t);
    }
}

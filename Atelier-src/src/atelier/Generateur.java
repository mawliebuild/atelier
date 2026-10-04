package atelier;

import extension.GPresets;
import extension.tools.GPresetImporter;
import extension.tools.presetconfig.PresetConfig;
import extension.tools.presetconfig.furni.PresetFurni;
import extension.tools.presetconfig.wired.PresetWireds;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Fabrique un appart temporaire et le fait poser par le moteur de pose.
 *
 * Escalier, remplissage et copie miroir ne posent rien eux-memes : ils
 * decrivent les mobis voulus (classe, case relative, altitude au-dessus du
 * sol, rotation, etat), et le moteur de pose s'occupe du reste — dalle magique,
 * hauteurs, inventaire ou BC — exactement comme pour « Coller cet appart ».
 *
 * Ce qui a ete verifie dans le bytecode du moteur de pose (v1.3.8) :
 *  - PresetFurni(JSONObject) exige « name » : on le renseigne toujours ;
 *  - les cases sont posees a racine + (x,y) du preset, sans recentrage ;
 *  - z est absolu, decale de importZDelta = solLePlusBas(destination)
 *    - srcAnchorFloorHeight. Avec srcAnchorFloorHeight = 0, z devient donc
 *    une altitude AU-DESSUS DU SOL de la destination ;
 *  - selectionner un appart dans presetListView ne le charge PAS (il faut un
 *    double-clic) : on le donne directement a l'importeur ;
 *  - « :ip x,y » fixe la racine sans clic ; reste la case de la dalle magique,
 *    que l'on donne nous-memes a l'importeur (voir Dalle). Sans racine, on
 *    attend le clic du coin dans le jeu (bloque) avant de lancer « :ip x,y ».
 *  - Sans dalle magique de la bonne taille dans la salle, l'Atelier en pose une
 *    (inventaire, sinon BC) a cote du trace et la ramasse a la fin (Dalle).
 */
public final class Generateur {

    private Generateur() { }

    /** D'ou le moteur de pose prend les meubles (ses quatre boutons radio). */
    public enum Source { INVENTAIRE, BC, BC_PUIS_INVENTAIRE, INVENTAIRE_PUIS_BC }

    /** Un mobi du preset : case relative (origine 0,0), altitude au-dessus du sol. */
    public static final class Mobi {
        public final String classe, etat;
        public final int x, y, rot;
        public final double z;
        public Mobi(String classe, String etat, int x, int y, double z, int rot) {
            this.classe = classe; this.etat = etat == null ? "0" : etat;
            this.x = x; this.y = y; this.z = z; this.rot = rot;
        }
    }

    // ------------------------------------------------------------ modele

    /** Le mobi a reproduire : sa classe, son etat, et ce qu'on sait de sa taille. */
    public static final class Modele {
        public final String classe, etat, nom;
        public final int xDim, yDim;
        /** Hauteur propre (0 si inconnue). */
        public final double hauteur;
        public final boolean empilable;

        Modele(String classe, String etat, String nom, int xDim, int yDim, double hauteur, boolean empilable) {
            this.classe = classe; this.etat = etat == null || etat.isEmpty() ? "0" : etat;
            this.nom = nom; this.xDim = Math.max(1, xDim); this.yDim = Math.max(1, yDim);
            this.hauteur = hauteur; this.empilable = empilable;
        }

        /** Emprise {x, y} pour une rotation : 2 et 6 echangent les cotes. */
        public int[] emprise(int rot) {
            return (rot == 2 || rot == 6) ? new int[]{yDim, xDim} : new int[]{xDim, yDim};
        }

        @Override public String toString() {
            return (nom == null || nom.equals(classe) ? classe : nom + " (" + classe + ")")
                    + "  ·  " + xDim + "×" + yDim
                    + (hauteur > 0 ? String.format(Locale.ROOT, "  ·  h %.2f", hauteur) : "");
        }
    }

    /** Modele lu sur un mobi de la salle ; null si sa classe est inconnue. */
    public static Modele modeleDe(HFloorItem it) {
        if (it == null) return null;
        String c = Salle.classe(it.getTypeId(), false);
        if (c == null) return null;
        return modele(c, etatDe(it), Salle.hauteur(it));
    }

    /** Modele d'apres un nom technique ; null s'il est inconnu de la furnidata. */
    public static Modele modele(String classe, String etat, double hauteur) {
        if (classe == null || classe.isBlank()) return null;
        classe = classe.trim();
        if (!Salle.furnidataPrete()) return null;
        Integer tid = null;
        try { tid = Salle.gp().getFurniDataTools().getFloorTypeId(classe); } catch (Throwable ignored) { }
        if (tid == null) return null;
        furnidata.details.FloorItemDetails d = Salle.details(classe);
        int lx = d == null ? 1 : d.xDim, ly = d == null ? 1 : d.yDim;
        String nom = (d != null && d.name != null && !d.name.isBlank()) ? d.name : classe;
        boolean emp = true;
        try { emp = Salle.gp().getFurniDataTools().isStackable(classe); } catch (Throwable ignored) { }
        return new Modele(classe, etat, nom, lx, ly, hauteur, emp);
    }

    /** Etat d'un mobi tel que le moteur de pose l'exporte : la chaine « legacy » de son stuff. */
    public static String etatDe(HFloorItem it) {
        try {
            String s = it.getStuff() == null ? null : it.getStuff().getLegacyString();
            return (s == null || s.isEmpty()) ? "0" : s;
        } catch (Throwable t) { return "0"; }
    }

    // ------------------------------------------------- choix d'un modele (UI)

    /**
     * Petit bloc d'interface : « Cliquer le mobi modele dans le jeu », ou saisie
     * d'un nom technique. facultatif = un choix « aucun » possible (mobi B).
     */
    public static final class ChoixModele {
        private volatile Modele courant;
        private volatile boolean enAttente = false;
        private final Label lbl = Ui.valeur("Aucun mobi choisi");
        private final TextField saisie = new TextField();
        private final List<Runnable> ecouteurs = new ArrayList<>();
        private final Label etat;
        private final VBox bloc;

        public ChoixModele(String titre, boolean facultatif, Label etat) {
            this.etat = etat;
            lbl.setWrapText(true);
            Button cliquer = new Button("Cliquer le mobi modèle dans le jeu");
            cliquer.setMaxWidth(Double.MAX_VALUE);
            cliquer.setOnAction(e -> {
                enAttente = true;
                lbl.setText("Clique le mobi dans le jeu...");
            });
            saisie.setPromptText("Ou nom technique (ex. shelves_norja)");
            saisie.setPrefColumnCount(14);
            Button ok = new Button("OK");
            ok.setOnAction(e -> depuisSaisie());
            saisie.setOnAction(e -> depuisSaisie());
            List<javafx.scene.Node> n = new ArrayList<>(List.of(lbl, cliquer, Ui.ligne(saisie, ok)));
            if (facultatif) {
                Button aucun = new Button("Aucun");
                aucun.setOnAction(e -> regler(null));
                n.add(Ui.ligne(aucun, Ui.discret("Vide = cases laissées libres")));
            }
            bloc = Ui.bloc(titre, n.toArray(new javafx.scene.Node[0]));

            Salle.surClicMobi(it -> {
                if (!enAttente) return;
                enAttente = false;
                Modele m = modeleDe(it);
                Platform.runLater(() -> {
                    if (m == null) {
                        lbl.setText("Mobi pas encore reconnu : les noms des mobis se chargent…");
                    } else regler(m);
                });
            });
        }

        private void depuisSaisie() {
            String t = saisie.getText() == null ? "" : saisie.getText().trim();
            if (t.isEmpty()) return;
            if (!Salle.furnidataPrete()) { dire(etat, "Les noms des mobis se chargent encore : encore un instant."); return; }
            Modele m = Generateur.modele(t, "0", courant != null && t.equals(courant.classe) ? courant.hauteur : 0);
            if (m == null) { dire(etat, "« " + t + " » : nom technique inconnu de la furnidata."); return; }
            regler(m);
        }

        private void regler(Modele m) {
            courant = m;
            enAttente = false;
            lbl.setText(m == null ? "Aucun mobi choisi" : m.toString());
            for (Runnable r : ecouteurs) r.run();
        }

        public Modele modele() { return courant; }
        public VBox bloc() { return bloc; }
        public void ecouter(Runnable r) { ecouteurs.add(r); }
    }

    // ------------------------------------------------- choix de la source (UI)

    /** Les quatre sources du moteur de pose, en boutons radio. */
    public static final class ChoixSource {
        private final RadioButton inv, bc, bcInv, invBc;
        private final VBox bloc;

        public ChoixSource() {
            ToggleGroup g = new ToggleGroup();
            inv   = radio("Inventaire seul", g, true);
            bc    = radio("BC seul", g, false);
            bcInv = radio("BC, puis inventaire", g, false);
            invBc = radio("Inventaire, puis BC", g, false);
            bloc = Ui.bloc("Source des meubles", new VBox(4, inv, bc, bcInv, invBc));
        }

        public Source source() {
            if (bc.isSelected()) return Source.BC;
            if (bcInv.isSelected()) return Source.BC_PUIS_INVENTAIRE;
            if (invBc.isSelected()) return Source.INVENTAIRE_PUIS_BC;
            return Source.INVENTAIRE;
        }

        public VBox bloc() { return bloc; }
    }

    // ------------------------------------------------------------ pose

    /**
     * Ecrit l'appart, le donne au moteur de pose et lance son import.
     *
     * @param fichier  nom reserve, sans extension (ex. « _atelier_escalier »)
     * @param racine   case de la salle ou mettre l'origine (0,0) du preset ;
     *                 null = l'utilisatrice clique elle-meme dans le jeu
     * @param dire     ligne d'etat (appelee hors fil FX)
     * @return true si l'import a ete lance
     */
    public static boolean poser(String fichier, List<Mobi> mobis, Source source,
                                HPoint racine, Consumer<String> dire) {
        try {
            return poser0(fichier, mobis, source, racine, dire);
        } catch (Throwable t) {
            dire.accept("Pose impossible : " + t);
            return false;
        }
    }

    private static boolean poser0(String fichier, List<Mobi> mobis, Source source,
                                  HPoint racine, Consumer<String> dire) throws Exception {
        GPresets gp = Salle.gp();
        if (gp == null) { dire.accept("L'Atelier n'est pas encore prêt."); return false; }
        if (!Salle.dansUneSalle()) { dire.accept("Tu n'es pas dans une salle."); return false; }
        if (!Salle.furnidataPrete()) { dire.accept("Furnidata pas encore chargée."); return false; }
        if (mobis == null || mobis.isEmpty()) { dire.accept("Rien à poser."); return false; }

        GPresetImporter imp = gp.getImporter();
        if (imp == null) { dire.accept("Moteur de pose introuvable."); return false; }
        try {
            if (imp.getState() != GPresetImporter.BuildingImportState.NONE) {
                dire.accept("Le moteur de pose est déjà en train d'importer — termine ou tape :abort dans le jeu.");
                return false;
            }
        } catch (Throwable ignored) { }

        // 1. le preset : sans murs ni wired ; les classes doivent etre connues,
        //    sinon le moteur de pose plante (getFloorTypeId(...).intValue()).
        furnidata.FurniDataTools fd = gp.getFurniDataTools();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Mobi m : mobis) { minX = Math.min(minX, m.x); minY = Math.min(minY, m.y); }
        List<PresetFurni> furni = new ArrayList<>();
        int id = 1;
        for (Mobi m : mobis) {
            if (fd.getFloorTypeId(m.classe) == null) {
                dire.accept("« " + m.classe + " » inconnu de la furnidata : pose annulée.");
                return false;
            }
            // origine ramenee a (0,0) : le moteur de pose pose a racine + (x,y)
            PresetFurni p = new PresetFurni(id++, m.classe,
                    new HPoint(m.x - minX, m.y - minY, Math.max(0, arrondi(m.z))), m.rot & 7, m.etat);
            String nom = m.classe;
            try {
                furnidata.details.FloorItemDetails d = fd.getFloorItemDetails(m.classe);
                if (d != null && d.name != null && !d.name.isBlank()) nom = d.name;
            } catch (Throwable ignored) { }
            p.setFurniName(nom);
            furni.add(p);
        }
        if (racine != null && (minX != 0 || minY != 0))
            racine = new HPoint(racine.getX() + minX, racine.getY() + minY);

        PresetWireds w = new PresetWireds(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        PresetConfig cfg = new PresetConfig(furni, new ArrayList<>(), w, new ArrayList<>(), new ArrayList<>());
        cfg.setSrcAnchorFloorHeight(0.0);       // z = altitude au-dessus du sol

        // aller-retour JSON : ce que le moteur de pose relira est exactement ceci
        String json = cfg.toJsonObject().toString(2);
        PresetConfig relu = new PresetConfig(new org.json.JSONObject(json));

        // 2. le fichier, dans le dossier des apparts
        File dossier = OngletApparts.dossierApparts();
        if (!dossier.exists()) dossier.mkdirs();
        File f = new File(dossier, fichier + ".json");
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));

        // 3. la case du coin : sans racine, on attend le clic nous-memes (bloque,
        //    pour que l'avatar n'aille pas se poser sur le trace).
        if (racine == null) {
            dire.accept("Dans le jeu : clique la case où mettre le coin haut-gauche (x min, y min). "
                    + "Ton avatar ne bougera pas.");
            racine = Dalle.attendreClic(120_000);
            if (racine == null) { dire.accept("Pas de clic dans le jeu en 2 minutes : pose annulée."); return false; }
        }

        // 4. la dalle magique : posee par l'Atelier si la salle n'en a pas
        List<int[]> trace = Dalle.trace(mobis, minX, minY, racine);
        int[] depart = trace.isEmpty() ? new int[]{racine.getX(), racine.getY()}
                : new int[]{racine.getX() + mobis.get(0).x - minX, racine.getY() + mobis.get(0).y - minY};
        Dalle.Pret dalle = Dalle.preparer(gp, mobis, trace, depart, dire);
        if (dalle == null) { Dalle.finIgnorer(); return false; }

        boolean ok = importer(gp, imp, relu, fichier, source, racine, dire,
                mobis.size() + " mobi(s) envoyés au moteur de pose. ", dalle.ou);
        if (ok) Dalle.apresImport(imp, dalle.poseeParAtelier, dire);
        else if (dalle.poseeParAtelier > 0)
            Dalle.ramasser(dalle.poseeParAtelier, dire, "La pose n'a pas démarré (voir le message de "
                    + "l'Atelier dans le jeu) : j'ai ramassé la dalle magique.");
        else Dalle.finIgnorer();
        return ok;
    }

    /**
     * Fait poser un appart par le moteur de pose, sans passer par sa liste.
     *
     * Selectionner une ligne de presetListView NE charge PAS l'appart : le moteur de pose
     * ne le charge qu'au double-clic. Un « :ip » envoye juste apres posait donc
     * l'appart charge AVANT. Ici le preset est donne directement a l'importeur,
     * puis :ip est lance sur l'importeur lui-meme.
     *
     * @param racine null = l'utilisatrice clique dans le jeu ou poser
     * @param entete debut du message final (« 12 mobi(s) envoyés... »)
     */
    public static boolean importer(GPresets gp, GPresetImporter imp, PresetConfig relu, String fichier,
                                   Source source, HPoint racine, Consumer<String> dire, String entete)
            throws Exception {
        return importer(gp, imp, relu, fichier, source, racine, dire, entete, null);
    }

    /**
     * @param dalleOu case ou le moteur de pose doit ranger sa dalle magique (« empty area »),
     *                donnee a sa place ; null = l'utilisatrice la clique dans le jeu.
     */
    public static boolean importer(GPresets gp, GPresetImporter imp, PresetConfig relu, String fichier,
                                   Source source, HPoint racine, Consumer<String> dire, String entete,
                                   HPoint dalleOu)
            throws Exception {
        // 2 bis. les mobis introuvables (ni inventaire ni BC selon la source) sont
        // retires : le moteur de pose refuserait toute la pose pour un seul manquant.
        // On le dit, et on colle le reste.
        relu = sansManquants(gp, relu, source, dire);
        if (relu == null) return false;
        final PresetConfig aPoser = relu;

        // 3. sur le fil FX : liste rechargee, selection, source, preset charge
        final String[] erreur = {null};
        CountDownLatch fait = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                try { gp.reloadPresetsClick(null); } catch (Throwable ignored) { }
                switch (source) {
                    case BC:                 gp.onlyBcCbx.setSelected(true); break;
                    case BC_PUIS_INVENTAIRE: gp.preferBcCbx.setSelected(true); break;
                    case INVENTAIRE_PUIS_BC: gp.preferInvCbx.setSelected(true); break;
                    default:                 gp.onlyInvCbx.setSelected(true);
                }
                charger(gp, imp, aPoser, fichier);
            } catch (Throwable t) { erreur[0] = String.valueOf(t); }
            finally { fait.countDown(); }
        });
        if (!fait.await(5, TimeUnit.SECONDS)) { dire.accept("Le moteur de pose ne répond pas (fil graphique occupé)."); return false; }
        if (erreur[0] != null) { dire.accept("Préparation impossible : " + erreur[0]); return false; }

        try {
            if (!imp.isReady()) {
                dire.accept("Le moteur de pose n'est pas prêt à importer (inventaire ou catalogue BC pas chargé, "
                        + "droits de la salle ?) — vérifie l'état dans Apparts.");
                return false;
            }
        } catch (Throwable ignored) { }

        // la liste du moteur de pose se recharge en differe : on selectionne apres coup (cosmetique)
        Salle.sommeil(400);
        Platform.runLater(() -> {
            try {
                if (gp.presetListView != null && gp.presetListView.getItems().contains(fichier))
                    gp.presetListView.getSelectionModel().select(fichier);
            } catch (Throwable ignored) { }
        });

        // 4. la commande d'import
        String cmd = racine == null ? ":ip" : ":ip " + racine.getX() + "," + racine.getY();
        lancer(gp, imp, cmd);
        Salle.sommeil(300);
        if (dalleOu != null && racine != null) {
            if (Dalle.donnerCase(imp, dalleOu)) {
                dire.accept(entete + "Dalle magique en (" + dalleOu.getX() + "," + dalleOu.getY()
                        + ") : la pose se fait toute seule en (" + racine.getX() + "," + racine.getY()
                        + "). (:abort dans le jeu pour arrêter)");
                return true;
            }
            GPresetImporter.BuildingImportState st = null;
            try { st = imp.getState(); } catch (Throwable ignored) { }
            if (st == GPresetImporter.BuildingImportState.NONE) {
                dire.accept("Le moteur de pose n'a pas lancé la pose : regarde son message dans le jeu "
                        + "(mobis manquants dans la source choisie ?).");
                return false;
            }
        }
        dire.accept(entete
                + (racine == null
                    ? "Dans le jeu : clique d'abord une case LIBRE (dalle magique), puis la case où mettre le coin haut-gauche (x min, y min)."
                    : "Dans le jeu : clique une case LIBRE pour la dalle magique — la pose se fait ensuite toute seule en ("
                      + racine.getX() + "," + racine.getY() + ").")
                + " (:abort pour annuler)");
        return true;
    }

    /**
     * Donne le preset a l'importeur. selectPreset (prive) journalise et met
     * l'interface a jour ; a defaut, setPresetConfig suffit a l'import.
     */
    /**
     * Le preset sans les mobis qu'on n'a pas : pas assez dans l'inventaire et
     * absents du BC (selon la source). Les wired retires perdent leur reglage,
     * les selections vers un mobi retire sont enlevees. Si rien ne manque (ou
     * si l'inventaire n'est pas lu), le preset est rendu tel quel. null : il ne
     * reste rien a poser (message dit).
     */
    static PresetConfig sansManquants(GPresets gp, PresetConfig cfg, Source source, Consumer<String> dire) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            game.Inventory inv = gp.getInventory();
            game.BCCatalog cat = gp.getCatalog();
            boolean invPret = inv != null && inv.getState() == game.Inventory.InventoryState.LOADED;
            boolean prendInv = source != Source.BC, prendBc = source != Source.INVENTAIRE;
            if (prendInv && !invPret) return cfg;            // on ne sait pas : on laisse le moteur de pose juger
            org.json.JSONObject o = cfg.toJsonObject();
            Map<String, Integer> stock = new HashMap<>();
            Map<String, Integer> manque = new TreeMap<>();
            Set<Integer> retires = new HashSet<>();
            for (String cle : new String[]{"furni", "wallFurni"}) {
                org.json.JSONArray a = o.optJSONArray(cle);
                if (a == null) continue;
                boolean mur = cle.equals("wallFurni");
                org.json.JSONArray garde = new org.json.JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject f = a.getJSONObject(i);
                    String cl = f.optString("className");
                    Integer type = mur ? fd.getWallTypeId(cl) : fd.getFloorTypeId(cl);
                    if (type == null) { garde.put(f); continue; }      // inconnu : dit plus tard par l'appelant
                    boolean bc = false;
                    if (prendBc && cat != null) try {
                        bc = mur ? cat.getAnyWallProduct(type) != null : cat.getFloorProduct(type) != null;
                    } catch (Throwable ignored) { }
                    String k = (mur ? "m:" : "s:") + type;
                    if (!stock.containsKey(k)) {
                        int n = 0;
                        if (prendInv) try {
                            java.util.List<?> l = mur ? inv.getWallItemsByType(type) : inv.getFloorItemsByType(type);
                            n = l == null ? 0 : l.size();
                        } catch (Throwable ignored) { }
                        stock.put(k, n);
                    }
                    int reste = stock.get(k);
                    if (bc || reste > 0) {
                        if (!bc) stock.put(k, reste - 1);
                        garde.put(f);
                    } else {
                        retires.add(f.optInt("id"));
                        String nom = f.optString("name", cl);
                        manque.merge(nom.isBlank() ? cl : nom, 1, Integer::sum);
                    }
                }
                o.put(cle, garde);
            }
            if (retires.isEmpty()) return cfg;
            org.json.JSONObject w = o.optJSONObject("wired");
            if (w != null) for (String genre : w.keySet()) {
                org.json.JSONArray l = w.optJSONArray(genre);
                if (l == null) continue;
                org.json.JSONArray garde = new org.json.JSONArray();
                for (int i = 0; i < l.length(); i++) {
                    org.json.JSONObject x = l.optJSONObject(i);
                    if (x == null) { garde.put(l.get(i)); continue; }
                    if (retires.contains(x.optInt("wiredId", -1))) continue;
                    for (String champ : new String[]{"items", "secondItems"}) {
                        org.json.JSONArray it = x.optJSONArray(champ);
                        if (it == null) continue;
                        org.json.JSONArray g2 = new org.json.JSONArray();
                        for (int j = 0; j < it.length(); j++) if (!retires.contains(it.optInt(j))) g2.put(it.get(j));
                        x.put(champ, g2);
                    }
                    garde.put(x);
                }
                w.put(genre, garde);
            }
            org.json.JSONArray b = o.optJSONArray("bindings");
            if (b != null) {
                org.json.JSONArray garde = new org.json.JSONArray();
                for (int i = 0; i < b.length(); i++) {
                    org.json.JSONObject x = b.getJSONObject(i);
                    if (!retires.contains(x.optInt("furniId")) && !retires.contains(x.optInt("wiredId"))) garde.put(x);
                }
                o.put("bindings", garde);
            }
            StringBuilder liste = new StringBuilder();
            int k = 0;
            for (Map.Entry<String, Integer> e : manque.entrySet()) {
                if (k++ == 6) { liste.append(", …"); break; }
                if (liste.length() > 0) liste.append(", ");
                liste.append(e.getKey()).append(e.getValue() > 1 ? " ×" + e.getValue() : "");
            }
            int resteFurni = o.optJSONArray("furni") == null ? 0 : o.getJSONArray("furni").length();
            int resteMurs = o.optJSONArray("wallFurni") == null ? 0 : o.getJSONArray("wallFurni").length();
            String msg = retires.size() + " mobi(s) introuvable(s) (ni dans l'inventaire ni au BC) : " + liste
                    + ". Je colle le reste sans eux.";
            InfoJeu.consigne(msg);
            dire.accept(msg);
            if (resteFurni + resteMurs == 0) { dire.accept("Rien d'autre à poser."); return null; }
            return new PresetConfig(o);
        } catch (Throwable t) {
            System.err.println("[Atelier] tri des mobis manquants : " + t);
            return cfg;
        }
    }

    private static void charger(GPresets gp, GPresetImporter imp, PresetConfig cfg, String nom) {
        try {
            java.lang.reflect.Method m = GPresets.class.getDeclaredMethod("selectPreset", PresetConfig.class, String.class);
            m.setAccessible(true);
            m.invoke(gp, cfg, nom);
            if (imp.getPresetConfig() == cfg) return;
        } catch (Throwable ignored) { }
        imp.setPresetConfig(cfg);
    }

    /**
     * Lance « :ip ». D'abord en appelant directement le gestionnaire de chat de
     * l'importeur (rien ne part vers le serveur) ; a defaut, comme
     * OngletApparts : un paquet Chat que le moteur de pose intercepte et bloque.
     */
    private static void lancer(GPresets gp, GPresetImporter imp, String cmd) {
        try {
            java.lang.reflect.Method m = GPresetImporter.class.getDeclaredMethod("onChat", HMessage.class);
            m.setAccessible(true);
            HPacket p = new HPacket(4000, cmd, 0, -1);
            m.invoke(imp, new HMessage(p, HMessage.Direction.TOSERVER, -1));
            return;
        } catch (Throwable t) {
            Journal.debug("appel direct de :ip impossible (" + t + "), envoi par le chat.");
        }
        gp.sendToServer(new HPacket("Chat", HMessage.Direction.TOSERVER, cmd, 0, -1));
    }

    // ------------------------------------------------------------ dalle magique

    /**
     * La dalle magique (tile_stackmagic*) qu'il faut au moteur de pose pour poser a
     * hauteur exacte. S'il n'y en a pas de la bonne taille dans la salle,
     * l'Atelier la pose lui-meme, a cote du trace, puis la ramasse a la fin.
     *
     * Regles recopiees du bytecode du moteur de pose v1.3.8 (extension.tools.GPresetImporter) :
     *  - requiredStackTileDimension : 1×1 -> 1, 1×2 / 2×1 -> -1 (tile_stackmagic1),
     *    sinon le plus grand cote arrondi a 1, 2, 4, 6, 8 ;
     *  - collectRequiredStackTileDimensions : mobis empilables seulement, hors
     *    dalles ; ensemble vide -> {1} ;
     *  - hasStackTileForDimension : voir couvre() ;
     *  - selectMainStackTile : la dalle de plus petite dimension sert ;
     *  - StackTileSetting : Small tile_stackmagic (1), Medium tile_stackmagic1 (-1),
     *    Large tile_stackmagic2 (2), XL ..4x4 (4), XXL ..6x6 (6), XXXL ..8x8 (8).
     *
     * Paquets, memes sources :
     *  - PlaceObject(String "-idInventaire x y rot") : depot d'un mobi de sol depuis
     *    l'inventaire (GPresetImporter, format "-%d %d %d %d" avec HInventoryItem.getId) ;
     *  - BuildersClubPlaceRoomItem(int -1, int offerId, String "", int x, int y, int rot) :
     *    GPresetImporter.acquireStackTileFromBC (offre : BCCatalog.getFloorProduct,
     *    a defaut FloorItemDetails.bcOfferId) ;
     *  - PickupObject(int 2, int id) : Salle.ramasser.
     *
     * La case de la dalle (« Select where the stack tile should be placed ») est
     * donnee a l'importeur en appelant son gestionnaire prive moveAvatar(HMessage)
     * avec un faux clic (x, y) dans l'etat AWAITING_UNOCCUPIED_SPACE : c'est
     * exactement ce que fait un clic dans le jeu.
     */
    static final class Dalle {

        private Dalle() { }

        /** Ce que l'import utilisera : la case de la dalle, et l'id de la dalle posee par nous (ou -1). */
        static final class Pret {
            final HPoint ou;
            final int poseeParAtelier;
            Pret(HPoint ou, int id) { this.ou = ou; this.poseeParAtelier = id; }
        }

        // ---------------------------------------------- regles (logique pure)

        /** Dimension de dalle qu'exige un mobi (GPresetImporter.requiredStackTileDimension). */
        static int requise(int xDim, int yDim) {
            int x = Math.max(1, xDim), y = Math.max(1, yDim);
            if ((x == 1 && y == 2) || (x == 2 && y == 1)) return -1;
            int m = Math.max(x, y);
            if (m <= 1) return 1;
            if (m <= 2) return 2;
            if (m <= 4) return 4;
            if (m <= 6) return 6;
            return 8;
        }

        /** Une dalle de dimension d sert-elle un mobi qui exige r ? (hasStackTileForDimension) */
        static boolean couvre(int d, int r) {
            if (r == -1) return d == -1 || d >= 2;
            if (d == -1) return r <= 2;
            return d >= r;
        }

        /** Toutes les exigences r sont-elles couvertes par au moins une des dalles ? */
        static boolean toutCouvert(Collection<Integer> dalles, Set<Integer> exigences) {
            for (int r : exigences) {
                boolean ok = false;
                for (int d : dalles) if (couvre(d, r)) { ok = true; break; }
                if (!ok) return false;
            }
            return true;
        }

        /**
         * La dalle a poser : la 1×1 des qu'elle suffit, sinon la plus petite qui
         * couvre tout. La 1×2 (dimension -1) n'est prise que pour des mobis 1×2 :
         * Le moteur de pose l'accepte aussi pour un 2×2, mais elle ne le couvre pas.
         * null si aucune.
         */
        static extension.tools.StackTileSetting modele(Set<Integer> exigences) {
            List<extension.tools.StackTileSetting> ordre = new ArrayList<>();
            for (extension.tools.StackTileSetting t : extension.tools.StackTileSetting.values())
                if (t.getDimension() > 0) ordre.add(t);
            ordre.sort(Comparator.comparingInt(extension.tools.StackTileSetting::getDimension));
            for (extension.tools.StackTileSetting t : extension.tools.StackTileSetting.values())
                if (t.getDimension() < 0) ordre.add(exigences.contains(-1) ? 1 : ordre.size(), t);
            for (extension.tools.StackTileSetting t : ordre)
                if (toutCouvert(List.of(t.getDimension()), exigences)) return t;
            return null;
        }

        static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

        /**
         * Coin d'une dalle lx × ly qui couvre la case cliquee (x, y) : toutes ses
         * cases libres, hors du trace, de meme hauteur de sol. Prefere (x, y)
         * comme coin. null si impossible. Logique pure, hors hauteurSol.
         */
        static int[] coinAutour(int x, int y, int lx, int ly, Collection<int[]> trace,
                                java.util.function.BiPredicate<Integer, Integer> libre) {
            Set<Long> dessus = new HashSet<>();
            for (int[] c : trace) dessus.add(cle(c[0], c[1]));
            int[][] ordre = new int[lx * ly][];
            int k = 0;
            ordre[k++] = new int[]{x, y};
            for (int ox = x; ox > x - lx; ox--)
                for (int oy = y; oy > y - ly; oy--)
                    if (ox != x || oy != y) ordre[k++] = new int[]{ox, oy};
            for (Set<Long> interdit : List.of(dessus, Set.<Long>of()))     // hors trace d'abord
                for (int[] o : ordre) {
                    if (o[0] < 0 || o[1] < 0) continue;
                    int h = Salle.hauteurSol(o[0], o[1]);
                    boolean ok = true;
                    for (int i = 0; i < lx && ok; i++)
                        for (int j = 0; j < ly && ok; j++) {
                            int cx = o[0] + i, cy = o[1] + j;
                            if (interdit.contains(cle(cx, cy)) || !libre.test(cx, cy) || Salle.hauteurSol(cx, cy) != h) ok = false;
                        }
                    if (ok) return o;
                }
            return null;
        }

        /**
         * Case libre la plus proche du depart pour une dalle lx × ly.
         * D'abord a au moins une case du trace, sinon simplement hors du trace.
         *
         * @param libre   case jouable et sans mobi
         * @param exclues coins deja essayes sans succes
         */
        static int[] choisirCase(int ax, int ay, int lx, int ly, Collection<int[]> trace,
                                 java.util.function.BiPredicate<Integer, Integer> libre,
                                 Set<Long> exclues, int rayon) {
            Set<Long> dessus = new HashSet<>(), autour = new HashSet<>();
            for (int[] c : trace) {
                dessus.add(cle(c[0], c[1]));
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++) autour.add(cle(c[0] + dx, c[1] + dy));
            }
            List<int[]> candidats = new ArrayList<>();
            for (int dx = -rayon; dx <= rayon; dx++)
                for (int dy = -rayon; dy <= rayon; dy++) {
                    int x = ax + dx, y = ay + dy;
                    if (x < 0 || y < 0) continue;
                    candidats.add(new int[]{x, y, dx * dx + dy * dy});
                }
            candidats.sort((a, b) -> a[2] != b[2] ? Integer.compare(a[2], b[2])
                    : a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
            // En dernier recours, SUR le trace : dans une salle juste assez grande
            // pour la pose (6x6 dans 6x6), il n'y a pas d'autre place. Le moteur de pose
            // l'accepte : il deplace la dalle sous chaque mobi pendant la pose,
            // seules des cases vides au depart comptent.
            for (Set<Long> interdit : List.of(autour, dessus, Set.<Long>of())) {
                for (int[] c : candidats) {
                    if (exclues != null && exclues.contains(cle(c[0], c[1]))) continue;
                    boolean ok = true;
                    for (int i = 0; i < lx && ok; i++)
                        for (int j = 0; j < ly && ok; j++) {
                            int x = c[0] + i, y = c[1] + j;
                            if (interdit.contains(cle(x, y)) || !libre.test(x, y)) ok = false;
                        }
                    if (ok) return new int[]{c[0], c[1]};
                }
            }
            return null;
        }

        // ---------------------------------------------- lecture de la salle

        /** Cases de la salle que le preset va occuper (racine = coin x min, y min). */
        static List<int[]> trace(List<Mobi> mobis, int minX, int minY, HPoint racine) {
            List<int[]> r = new ArrayList<>();
            Set<Long> vu = new HashSet<>();
            for (Mobi m : mobis) {
                furnidata.details.FloorItemDetails d = Salle.details(m.classe);
                int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
                int rot = m.rot & 7;
                if (rot == 2 || rot == 6) { int t = lx; lx = ly; ly = t; }
                int x0 = racine.getX() + m.x - minX, y0 = racine.getY() + m.y - minY;
                for (int i = 0; i < lx; i++)
                    for (int j = 0; j < ly; j++)
                        if (vu.add(cle(x0 + i, y0 + j))) r.add(new int[]{x0 + i, y0 + j});
            }
            return r;
        }

        /** Les exigences du preset (collectRequiredStackTileDimensions). */
        static Set<Integer> exigences(List<Mobi> mobis) {
            Set<Integer> r = new HashSet<>();
            furnidata.FurniDataTools fd = Salle.gp().getFurniDataTools();
            for (Mobi m : mobis) {
                if (m.classe.startsWith("tile_stackmagic")) continue;
                boolean emp = true;
                try { emp = fd.isStackable(m.classe); } catch (Throwable ignored) { }
                if (!emp) continue;
                furnidata.details.FloorItemDetails d = Salle.details(m.classe);
                r.add(d == null ? 1 : requise(d.xDim, d.yDim));
            }
            if (r.isEmpty()) r.add(1);
            return r;
        }

        private static extension.tools.StackTileSetting reglage(HFloorItem it) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || !c.startsWith("tile_stackmagic")) return null;
            try { return extension.tools.StackTileSetting.fromClassName(c); } catch (Throwable t) { return null; }
        }

        /** Les identifiants de type de toutes les dalles magiques. */
        static Set<Integer> typesDalles() {
            Set<Integer> r = new HashSet<>();
            try {
                furnidata.FurniDataTools fd = Salle.gp().getFurniDataTools();
                for (extension.tools.StackTileSetting t : extension.tools.StackTileSetting.values()) {
                    Integer id = fd.getFloorTypeId(t.getClassName());
                    if (id != null) r.add(id);
                }
            } catch (Throwable ignored) { }
            return r;
        }

        /** Cases occupees par des mobis de sol, sauf celui d'identifiant sauf. */
        private static Set<Long> occupees(int sauf) {
            Set<Long> r = new HashSet<>();
            for (HFloorItem it : new ArrayList<>(Salle.sols())) {
                if (it.getId() == sauf) continue;
                int[] e = Salle.emprise(it);
                int x = it.getTile().getX(), y = it.getTile().getY();
                for (int i = 0; i < e[0]; i++)
                    for (int j = 0; j < e[1]; j++) r.add(cle(x + i, y + j));
            }
            return r;
        }

        static int[] empriseDalle(extension.tools.StackTileSetting t) {
            furnidata.details.FloorItemDetails d = Salle.details(t.getClassName());
            if (d != null) return new int[]{Math.max(1, d.xDim), Math.max(1, d.yDim)};
            int n = Math.max(1, Math.abs(t.getDimension()));
            return t.getDimension() == -1 ? new int[]{1, 2} : new int[]{n, n};
        }

        // ---------------------------------------------- preparation

        /**
         * S'assure qu'une dalle utilisable est dans la salle et choisit sa case.
         * null = impossible (la raison est dite).
         */
        static Pret preparer(GPresets gp, List<Mobi> mobis, List<int[]> trace, int[] depart,
                             Consumer<String> dire) {
            Set<Integer> exig = exigences(mobis);

            // les dalles deja la
            List<HFloorItem> dalles = new ArrayList<>();
            List<Integer> dims = new ArrayList<>();
            for (HFloorItem it : new ArrayList<>(Salle.sols())) {
                extension.tools.StackTileSetting t = reglage(it);
                if (t != null) { dalles.add(it); dims.add(t.getDimension()); }
            }
            // pendant l'import, les va-et-vient de la dalle ne sont pas des actions a annuler
            for (int type : typesDalles()) Historique.ignorerType(type, 30 * 60_000L);

            if (!dalles.isEmpty() && toutCouvert(dims, exig)) {
                // celle que le moteur de pose prendra : la plus petite dimension
                int k = 0;
                for (int i = 1; i < dims.size(); i++) if (dims.get(i) < dims.get(k)) k = i;
                HFloorItem principale = dalles.get(k);
                int[] e = Salle.emprise(principale);
                int px = principale.getTile().getX(), py = principale.getTile().getY();
                Set<Long> autour = new HashSet<>();
                for (int[] c : trace)
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++) autour.add(cle(c[0] + dx, c[1] + dy));
                boolean gene = false;
                for (int i = 0; i < e[0] && !gene; i++)
                    for (int j = 0; j < e[1] && !gene; j++) gene = autour.contains(cle(px + i, py + j));
                if (!gene) {
                    dire.accept("Dalle magique trouvée en (" + px + "," + py + ") : elle reste là.");
                    return new Pret(new HPoint(px, py), -1);
                }
                Set<Long> occ = occupees(principale.getId());
                int[] c = choisirCase(depart[0], depart[1], e[0], e[1], trace,
                        (x, y) -> Salle.hauteurSol(x, y) >= 0 && !occ.contains(cle(x, y)), null, 12);
                if (c == null) {
                    dire.accept("La dalle magique est sur le tracé et je ne trouve pas de case libre "
                            + "à côté : libère un peu de place près du départ.");
                    return null;
                }
                dire.accept("Dalle magique déplacée par le moteur de pose en (" + c[0] + "," + c[1] + ").");
                return new Pret(new HPoint(c[0], c[1]), -1);
            }

            // il faut la poser nous-memes
            extension.tools.StackTileSetting t = modele(exig);
            if (t == null) { dire.accept("Aucune dalle magique ne convient à ce mobi."); return null; }
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            Integer type = fd.getFloorTypeId(t.getClassName());
            if (type == null) { dire.accept("« " + t.getClassName() + " » inconnue de la furnidata."); return null; }
            int[] e = empriseDalle(t);
            String taille = e[0] + "×" + e[1];

            Set<Long> exclues = new HashSet<>();
            Set<Integer> invPris = new HashSet<>();
            String raison = "pas de place libre de " + taille + " (cases sans mobi, de même hauteur de sol)";
            int clics = 0;
            for (int essai = 1; essai <= 3; essai++) {
                Set<Long> occ = occupees(-1);
                java.util.function.BiPredicate<Integer, Integer> libre =
                        (x, y) -> Salle.hauteurSol(x, y) >= 0 && !occ.contains(cle(x, y));
                // toute la salle, pas seulement les abords du depart
                int[] c = choisirCase(depart[0], depart[1], e[0], e[1], trace, libre, exclues, 64);
                // Rien trouve : c'est toi qui choisis la place, plutot que d'abandonner.
                while (c == null && clics < 2) {
                    clics++;
                    String q = "Pas de place libre de " + taille + " trouvée pour la dalle magique : clique une case "
                            + "libre (" + taille + " sans mobi autour).";
                    dire.accept(q);
                    InfoJeu.dire(q);
                    HPoint ici = attendreClic(60_000);
                    if (ici == null) { raison = "pas de clic en 1 minute"; break; }
                    c = coinAutour(ici.getX(), ici.getY(), e[0], e[1], trace, libre);
                    if (c == null) {
                        raison = "pas assez de place autour de (" + ici.getX() + "," + ici.getY() + ") : il faut "
                                + taille + " cases libres, sans mobi, de même hauteur de sol";
                        if (clics < 2) dire.accept(Character.toUpperCase(raison.charAt(0)) + raison.substring(1) + ".");
                    }
                }
                if (c == null) break;
                exclues.add(cle(c[0], c[1]));

                Set<Integer> avant = new HashSet<>();
                for (HFloorItem it : new ArrayList<>(Salle.sols())) avant.add(it.getId());

                String d = envoyer(gp, type, t, c[0], c[1], 0, invPris);
                if (d == null) {
                    dire.accept("Dalle magique " + taille + " : ni dans ton inventaire, ni au catalogue BC "
                            + "(catalogue BC pas chargé ?). Pose annulée.");
                    return null;
                }
                dire.accept("Je pose une dalle magique " + taille + " en (" + c[0] + "," + c[1] + ") " + d + "...");
                for (int i = 0; i < 40; i++) {           // 6 s au plus
                    Salle.sommeil(150);
                    for (HFloorItem it : new ArrayList<>(Salle.sols())) {
                        if (avant.contains(it.getId()) || it.getTypeId() != type) continue;
                        if (it.getTile().getX() != c[0] || it.getTile().getY() != c[1]) continue;
                        dire.accept("Dalle magique " + taille + " posée en (" + c[0] + "," + c[1] + ") " + d + ".");
                        Salle.sommeil(300);
                        return new Pret(new HPoint(c[0], c[1]), it.getId());
                    }
                }
                raison = "elle n'est pas apparue (case refusée par le jeu ? avatar dessus ?)";
                Salle.sommeil(200);
            }
            dire.accept("Impossible de poser la dalle magique : " + raison + ". Pose annulée.");
            return null;
        }

        /** Envoie la pose ; renvoie « depuis l'inventaire » / « depuis le BC », ou null. */
        static String envoyer(GPresets gp, int type, extension.tools.StackTileSetting t,
                              int x, int y, int rot, Set<Integer> invPris) {
            return envoyer(gp, type, t, x, y, rot, invPris, true);
        }

        /** Les textes que renvoie envoyer, pour savoir d'ou venait la dalle. */
        static final String INVENTAIRE = "depuis ton inventaire", BC = "depuis le catalogue BC";

        /** @param bcPermis false = inventaire seulement (le BC a deja refuse). */
        static String envoyer(GPresets gp, int type, extension.tools.StackTileSetting t,
                              int x, int y, int rot, Set<Integer> invPris, boolean bcPermis) {
            return envoyer(gp, type, t, x, y, rot, invPris, bcPermis, true);
        }

        /** @param invPermis false = BC seulement : l'inventaire n'est pas touche. */
        static String envoyer(GPresets gp, int type, extension.tools.StackTileSetting t,
                              int x, int y, int rot, Set<Integer> invPris, boolean bcPermis,
                              boolean invPermis) {
            if (invPermis) try {
                game.Inventory inv = gp.getInventory();
                if (inv != null && inv.getState() == game.Inventory.InventoryState.LOADED) {
                    List<gearth.extensions.parsers.HInventoryItem> l = inv.getFloorItemsByType(type);
                    if (l != null) for (gearth.extensions.parsers.HInventoryItem it : l) {
                        if (it == null || invPris.contains(it.getId())) continue;
                        invPris.add(it.getId());
                        gp.sendToServer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                                "-" + it.getId() + " " + x + " " + y + " " + rot));
                        return INVENTAIRE;
                    }
                }
            } catch (Throwable ignored) { }
            if (!bcPermis) return null;
            int offre = -1;
            try {
                game.BCCatalog cat = gp.getCatalog();
                game.BCCatalog.SingleFurniProduct p = cat == null ? null : cat.getFloorProduct(type);
                if (p != null) offre = p.getOfferId();
            } catch (Throwable ignored) { }
            if (offre <= 0) {
                furnidata.details.FloorItemDetails d = Salle.details(t.getClassName());
                if (d != null) offre = d.bcOfferId;
            }
            if (offre <= 0) return null;
            gp.sendToServer(new HPacket("BuildersClubPlaceRoomItem", HMessage.Direction.TOSERVER,
                    -1, offre, "", x, y, rot));
            return BC;
        }

        // ---------------------------------------------- pendant / apres l'import

        /** Donne la case de la dalle a l'importeur, comme un clic. true si accepte. */
        static boolean donnerCase(GPresetImporter imp, HPoint ou) {
            try {
                if (imp.getState() != GPresetImporter.BuildingImportState.AWAITING_UNOCCUPIED_SPACE) return false;
                java.lang.reflect.Method m = GPresetImporter.class.getDeclaredMethod("moveAvatar", HMessage.class);
                m.setAccessible(true);
                HPacket p = new HPacket(4001, ou.getX(), ou.getY());
                m.invoke(imp, new HMessage(p, HMessage.Direction.TOSERVER, -1));
                return imp.getState() != GPresetImporter.BuildingImportState.AWAITING_UNOCCUPIED_SPACE;
            } catch (Throwable t) {
                Journal.debug("case de la dalle non transmise (" + t + ")");
                return false;
            }
        }

        /**
         * Quand le moteur de pose a fini (ou :abort) : ramasse la dalle que l'Atelier a
         * posee (id > 0) et rend la dalle a l'historique.
         */
        static void apresImport(GPresetImporter imp, int id, Consumer<String> dire) {
            Salle.tache("dalle-ramasser", () -> {
                long fin = System.currentTimeMillis() + 30 * 60_000L;
                while (System.currentTimeMillis() < fin) {
                    Salle.sommeil(500);
                    GPresetImporter.BuildingImportState s;
                    try { s = imp.getState(); } catch (Throwable t) { s = GPresetImporter.BuildingImportState.NONE; }
                    if (s == GPresetImporter.BuildingImportState.NONE) break;
                    if (!Salle.dansUneSalle()) {
                        finIgnorer();
                        if (id > 0) {
                            String m = "Tu as quitté la salle : la dalle magique n'a pas pu être ramassée, ramasse-la à la main.";
                            dire.accept(m);
                            InfoJeu.dire(m);
                        }
                        return;
                    }
                }
                Salle.sommeil(1500);
                if (id > 0) ramasser(id, dire, "Terminé. J'ai ramassé la dalle magique que j'avais posée.");
                else { finIgnorer(); InfoJeu.dire("Pose terminée."); }
            });
        }

        static void ramasser(int id, Consumer<String> dire, String message) {
            if (Salle.sol(id) == null) { finIgnorer(); return; }
            Salle.ramasser(id, false);
            for (int i = 0; i < 20 && Salle.sol(id) != null; i++) Salle.sommeil(150);
            finIgnorer();
            String fin = Salle.sol(id) == null ? message
                    : "La dalle magique (id " + id + ") n'a pas pu être ramassée : ramasse-la à la main.";
            dire.accept(fin);
            InfoJeu.dire(fin);    // meme texte : InfoJeu le dedoublonne si la ligne d'etat l'a deja dit
        }

        static void finIgnorer() {
            for (int type : typesDalles()) Historique.ignorerType(type, 2500);
        }

        // ---------------------------------------------- clic de la racine

        private static volatile java.util.concurrent.CompletableFuture<HPoint> attente;
        private static volatile boolean ecoute = false;
        /** En-tete de MoveAvatar, appris au premier clic au sol (-1 : pas encore connu). */
        private static volatile int enteteMove = -1;

        /**
         * Attend un clic au sol dans le jeu et le BLOQUE (l'avatar ne marche pas
         * sur le trace). null si rien avant le delai.
         */
        static HPoint attendreClic(long delaiMs) {
            brancher();
            java.util.concurrent.CompletableFuture<HPoint> f = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<HPoint> ancienne = attente;
            attente = f;
            if (ancienne != null) ancienne.complete(null);
            // repli : ecoute partagee de Salle, toujours branchee aussi (le clic
            // n'est alors pas bloque) ; le premier des deux qui voit le clic gagne.
            Consumer<HPoint> repli = f::complete;
            Salle.surClicCase(repli);
            try { return f.get(delaiMs, TimeUnit.MILLISECONDS); }
            catch (Throwable t) { return null; }
            finally {
                if (attente == f) attente = null;
                Salle.retirer(repli);
            }
        }

        private static synchronized void brancher() {
            if (ecoute) return;
            GPresets gp = Salle.gp();
            if (gp == null) return;
            // Apprend l'en-tete de MoveAvatar (pose AVANT l'ecoute par contenu, pour
            // passer avant elle) : un LookTo (meme forme x, y) n'est alors plus pris
            // pour le clic attendu.
            try {
                gp.intercept(HMessage.Direction.TOSERVER, "MoveAvatar", m -> {
                    try { enteteMove = m.getPacket().headerId(); } catch (Throwable ignored) { }
                });
            } catch (Throwable t) {
                Journal.debug("en-tete MoveAvatar pas resolu : " + t);
            }
            try {
                // Reconnu par son CONTENU (deux petits entiers x, y), pas par son nom :
                // un intercept par nom echoue en silence quand le nom ne se resout
                // pas, et le clic n'arrivait alors jamais.
                gp.intercept(HMessage.Direction.TOSERVER, m -> {
                    java.util.concurrent.CompletableFuture<HPoint> f = attente;
                    if (f == null) return;
                    try {
                        int n = m.getPacket().getBytesLength();
                        if (n < 14 || n > 20) return;
                        HPacket p = m.getPacket();
                        int e = enteteMove;
                        if (e >= 0 ? p.headerId() != e : n != 14) return;   // seulement MoveAvatar
                        int x = p.readInteger(6), y = p.readInteger(10);
                        if (x < 0 || y < 0 || Salle.hauteurSol(x, y) < 0) return;
                        m.setBlocked(true);
                        attente = null;
                        f.complete(new HPoint(x, y));
                    } catch (Throwable ignored) { }
                });
                ecoute = true;
            } catch (Throwable t) {
                Journal.debug("ecoute des clics (MoveAvatar) impossible : " + t);
            }
        }
    }

    // ------------------------------------------------------------ outils

    /** Arrondi au centieme : les altitudes de Habbo ne vont pas plus loin. */
    public static double arrondi(double z) { return Math.round(z * 100.0) / 100.0; }

    static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    static void dire(Label etat, String s) {
        if (etat == null) return;
        if (Platform.isFxApplicationThread()) etat.setText(s);
        else Platform.runLater(() -> etat.setText(s));
    }

    /** Volet standard : VBox (12,14,14,14) espacement 12 dans un ScrollPane sans barre horizontale. */
    static ScrollPane defiler(javafx.scene.Node... contenu) {
        VBox v = new VBox(12, contenu);
        v.setFillWidth(true);
        v.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    static Button principal(String texte, Runnable r) {
        Button b = new Button(texte);
        b.getStyleClass().add("primaire");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> r.run());
        return b;
    }

    static Spinner<Integer> entier(int min, int max, int defaut) {
        Spinner<Integer> s = new Spinner<>(min, max, defaut);
        s.setEditable(true);
        s.setPrefWidth(80);
        valider(s);
        return s;
    }

    static Spinner<Double> decimal(double min, double max, double defaut, double pas) {
        Spinner<Double> s = new Spinner<>(min, max, defaut, pas);
        s.getValueFactory().setConverter(new javafx.util.StringConverter<Double>() {
            @Override public String toString(Double d) {
                return d == null ? "" : String.format(Locale.ROOT, "%.2f", d);
            }
            @Override public Double fromString(String t) {
                return Double.parseDouble(t.trim().replace(',', '.'));
            }
        });
        s.getEditor().setText(String.format(Locale.ROOT, "%.2f", defaut));
        s.setEditable(true);
        s.setPrefWidth(90);
        valider(s);
        return s;
    }

    /** Prend le texte tape dans un Spinner editable (une saisie invalide remet la valeur). */
    static <T> void prendre(Spinner<T> s) {
        try {
            SpinnerValueFactory<T> f = s.getValueFactory();
            T v = f.getConverter().fromString(s.getEditor().getText().trim().replace(',', '.'));
            if (v != null) f.setValue(v);
        } catch (Throwable t) {
            try { s.getEditor().setText(s.getValueFactory().getConverter().toString(s.getValue())); }
            catch (Throwable ignored) { }
        }
    }

    /** Un Spinner editable ne prend la saisie qu'a Entree : on la prend aussi en quittant le champ. */
    static <T> void valider(Spinner<T> s) {
        s.focusedProperty().addListener((o, a, f) -> {
            if (f) return;
            prendre(s);
        });
    }
}

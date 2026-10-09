package atelier;

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
 * Fabrique une copie temporaire et la fait poser avec la dalle magique.
 *
 * Escalier, remplissage et copie miroir ne posent rien eux-memes : ils
 * decrivent les mobis voulus (classe, case relative, altitude au-dessus du
 * sol, rotation, etat), et la pose (PoseCopie, briques du moteur) s'occupe du
 * reste — dalle magique, hauteurs, inventaire ou BC — exactement comme pour
 * « Coller cet appart ».
 *
 * Regles gardees de l'ancien moteur de pose :
 *  - les cases sont posees a racine + (x,y) de la copie, sans recentrage ;
 *  - z est decale du sol le plus bas sous la copie dans la salle, moins la
 *    hauteur d'ancrage ; avec une ancre 0, z est donc une altitude AU-DESSUS
 *    DU SOL de la destination ;
 *  - sans racine, on attend le clic du coin dans le jeu (bloque) ;
 *  - sans dalle magique de la bonne taille dans la salle, la pose en met une
 *    (inventaire, sinon BC) a cote du trace et la ramasse a la fin (Dalle).
 */
public final class Generateur {

    private Generateur() { }

    /** D'ou la pose prend les meubles (inventaire, BC, ou l'un puis l'autre). */
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
        Furnidata.Mobi d = Salle.details(classe);
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
            saisie.setPromptText("Nom technique");
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
     * Pose ces mobis avec la dalle magique (PoseCopie), synchrone : rend la
     * main quand la pose est finie, bilan dit.
     *
     * @param fichier  nom de l'outil (« _atelier_escalier »), pour le journal
     * @param racine   case de la salle ou mettre l'origine (0,0) des mobis ;
     *                 null = l'utilisatrice clique elle-meme dans le jeu
     * @param dire     ligne d'etat (appelee hors fil FX)
     * @return true si la pose a eu lieu
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
        Moteur gp = Salle.gp();
        if (gp == null) { dire.accept("L'Atelier n'est pas encore prêt."); return false; }
        if (!Salle.dansUneSalle()) { dire.accept("Tu n'es pas dans une salle."); return false; }
        if (!Salle.furnidataPrete()) { dire.accept("Furnidata pas encore chargée."); return false; }
        if (mobis == null || mobis.isEmpty()) { dire.accept("Rien à poser."); return false; }
        if (PoseCopie.occupee()) {
            dire.accept("L'Atelier est déjà en train de poser : attends la fin, ou tape :abort dans le jeu.");
            return false;
        }

        // 1. la copie : sans murs ni wired ; les classes doivent etre connues
        Furnidata fd = gp.getFurniDataTools();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Mobi m : mobis) { minX = Math.min(minX, m.x); minY = Math.min(minY, m.y); }
        CopieAppart cfg = new CopieAppart();
        cfg.ancre = 0.0;       // z = altitude au-dessus du sol
        int id = 1;
        for (Mobi m : mobis) {
            if (fd.getFloorTypeId(m.classe) == null) {
                dire.accept("« " + m.classe + " » inconnu de la furnidata : pose annulée.");
                return false;
            }
            // origine ramenee a (0,0) : la pose se fait a racine + (x,y)
            CopieAppart.MobiSol p = new CopieAppart.MobiSol(id++, m.classe, m.x - minX, m.y - minY,
                    Math.max(0, arrondi(m.z)), m.rot & 7, m.etat);
            p.nom = nomSol(fd, m.classe);
            cfg.sols.add(p);
        }
        if (racine != null && (minX != 0 || minY != 0))
            racine = new HPoint(racine.getX() + minX, racine.getY() + minY);

        // 2. la case du coin : sans racine, on attend le clic nous-memes (bloque,
        //    pour que l'avatar n'aille pas se poser sur le trace).
        if (racine == null) {
            dire.accept("Dans le jeu : clique la case où mettre le coin haut-gauche (x min, y min). "
                    + "Ton avatar ne bougera pas.");
            Empreinte.Boite b = new Empreinte.Boite();
            for (Mobi m : mobis) b.sol(m.classe, m.x, m.y, m.rot);
            racine = Dalle.attendreClic(120_000, b.largeur(), b.profondeur());
            if (racine == null) { dire.accept("Pas de clic dans le jeu en 2 minutes : pose annulée."); return false; }
        }

        // 3. la dalle magique : celle de la salle, sinon une case libre a cote du trace
        List<int[]> trace = Dalle.trace(mobis, minX, minY, racine);
        int[] depart = trace.isEmpty() ? new int[]{racine.getX(), racine.getY()}
                : new int[]{racine.getX() + mobis.get(0).x - minX, racine.getY() + mobis.get(0).y - minY};
        Dalle.Pret dalle = Dalle.preparer(mobis, trace, depart, dire);
        if (dalle == null) return false;

        Journal.debug("pose " + fichier + " : " + cfg.sols.size() + " mobis en " + racine.getX() + "," + racine.getY() + ".");
        PoseCopie.Resultat r = poserCopie(cfg, source, racine, dire, dalle.ou, null, null);
        if (r == null) return false;
        String bilan = r.texte();
        dire.accept(bilan);
        InfoJeu.dire(bilan);
        return r.lancee;
    }

    /** Nom affiche d'un mobi de sol, a defaut sa classe. */
    static String nomSol(Furnidata fd, String classe) {
        try {
            Furnidata.Mobi d = fd.getFloorItemDetails(classe);
            if (d != null && d.name != null && !d.name.isBlank()) return d.name;
        } catch (Throwable ignored) { }
        return classe;
    }

    /**
     * Pose une copie (coin = racine) avec la dalle magique, apres avoir retire
     * ce qui manque dans la source choisie (le dit). Synchrone.
     *
     * @param caseDalle case ou poser la dalle s'il en faut une (Dalle.preparer) ; null = cherchee
     * @return le resultat, ou null s'il ne reste rien a poser (dit)
     */
    static PoseCopie.Resultat poserCopie(CopieAppart cfg, Source source, HPoint racine, Consumer<String> dire,
                                         HPoint caseDalle, java.util.function.BooleanSupplier stop, Runnable suivi) {
        return poserCopie(cfg, source, racine, dire, caseDalle, stop, suivi, null);
    }

    /** Comme poserCopie, avec la table des ids completee avant les reglages (PoseCopie.poser). */
    static PoseCopie.Resultat poserCopie(CopieAppart cfg, Source source, HPoint racine, Consumer<String> dire,
                                         HPoint caseDalle, java.util.function.BooleanSupplier stop, Runnable suivi,
                                         Consumer<Map<Integer, Integer>> completer) {
        CopieAppart aPoser = sansManquants(Salle.gp(), cfg, source, dire);
        if (aPoser == null) return null;
        PoseCopie.Resultat r = PoseCopie.poser(aPoser, racine, source, caseDalle, dire, stop, suivi, completer);
        if (!r.lancee) dire.accept(r.texte());
        return r;
    }

    /**
     * La copie sans les mobis qu'on n'a pas : pas assez dans l'inventaire et
     * absents du BC (selon la source). Les wired retires perdent leur reglage,
     * les selections vers un mobi retire sont enlevees. Si rien ne manque (ou
     * si l'inventaire n'est pas lu), la copie est rendue telle quelle. null : il
     * ne reste rien a poser (message dit).
     */
    static CopieAppart sansManquants(Moteur gp, CopieAppart cfg, Source source, Consumer<String> dire) {
        try {
            Furnidata fd = gp.getFurniDataTools();
            Inventaire inv = gp.getInventory();
            CatalogueBc cat = gp.getCatalog();
            boolean invPret = inv != null && inv.getState() == Inventaire.Etat.LOADED;
            boolean prendInv = source != Source.BC, prendBc = source != Source.INVENTAIRE;
            if (prendInv && !invPret) return cfg;            // on ne sait pas : on laisse la pose juger
            org.json.JSONObject o = cfg.json();
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
                        bc = (mur ? OffresBc.mur(cat, fd, cl, null) : OffresBc.sol(cat, fd, cl)) != null;
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
            org.json.JSONArray ads = o.optJSONArray("adsBackgrounds");
            if (ads != null) {
                org.json.JSONArray garde = new org.json.JSONArray();
                for (int i = 0; i < ads.length(); i++) {
                    org.json.JSONObject x = ads.optJSONObject(i);
                    if (x == null || !retires.contains(x.optInt("furniId"))) garde.put(ads.get(i));
                }
                o.put("adsBackgrounds", garde);
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
            String msg = Ui.accorder(retires.size() + " mobi(s) introuvable(s) (ni dans l'inventaire ni au BC) : " + liste
                    + ". Je colle le reste sans eux.");
            InfoJeu.consigne(msg);
            dire.accept(msg);
            if (resteFurni + resteMurs == 0) { dire.accept("Rien d'autre à poser."); return null; }
            return CopieAppart.lire(o);
        } catch (Throwable t) {
            Journal.debug("tri des mobis manquants : " + t);
            return cfg;
        }
    }

    // ------------------------------------------------------------ dalle magique

    /**
     * La dalle magique (tile_stackmagic*) qu'il faut pour poser a hauteur
     * exacte : regles de taille, choix de sa case, clic dans le jeu.
     *
     * Regles (les memes que PoseDalle, recopiees du moteur de pose d'avant) :
     *  - requise : 1×1 -> 1, 1×2 / 2×1 -> -1 (tile_stackmagic1), sinon le plus
     *    grand cote arrondi a 1, 2, 4, 6, 8 ;
     *  - exigences : mobis empilables seulement, hors dalles ; ensemble vide -> {1} ;
     *  - couvre : voir couvre() ;
     *  - DalleMagique : UN tile_stackmagic (1), UN_DEUX tile_stackmagic1 (-1),
     *    DEUX tile_stackmagic2 (2), QUATRE ..4x4 (4), SIX ..6x6 (6), HUIT ..8x8 (8).
     *
     * Paquets (envoyer, pour OutilHauteur) :
     *  - PlaceObject(String "-idInventaire x y rot") : pose depuis l'inventaire ;
     *  - BuildersClubPlaceRoomItem(int page, int offre, String extra, int x, int y, int rot, false) :
     *    pose depuis le BC comme le client (OffresBc : page et offre du catalogue BC).
     *
     * La pose elle-meme (dalle posee, deplacee sous chaque mobi, ramassee) est
     * faite par PoseDalle ; ici on choisit seulement sa case quand la salle n'a
     * pas de dalle qui convient (preparer), en demandant un clic au besoin.
     */
    static final class Dalle {

        private Dalle() { }

        /** Ce que la pose utilisera : la case ou poser la dalle (null : une dalle de la salle sert). */
        static final class Pret {
            final HPoint ou;
            Pret(HPoint ou) { this.ou = ou; }
        }

        // ---------------------------------------------- regles (logique pure)

        /** Dimension de dalle qu'exige un mobi (requiredStackTileDimension de l'ancien moteur). */
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
        static DalleMagique modele(Set<Integer> exigences) {
            List<DalleMagique> ordre = new ArrayList<>();
            for (DalleMagique t : DalleMagique.values())
                if (t.dimension() > 0) ordre.add(t);
            ordre.sort(Comparator.comparingInt(DalleMagique::dimension));
            for (DalleMagique t : DalleMagique.values())
                if (t.dimension() < 0) ordre.add(exigences.contains(-1) ? 1 : ordre.size(), t);
            for (DalleMagique t : ordre)
                if (toutCouvert(List.of(t.dimension()), exigences)) return t;
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
                Furnidata.Mobi d = Salle.details(m.classe);
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
            Furnidata fd = Salle.gp().getFurniDataTools();
            for (Mobi m : mobis) {
                if (m.classe.startsWith("tile_stackmagic")) continue;
                boolean emp = true;
                try { emp = fd.isStackable(m.classe); } catch (Throwable ignored) { }
                if (!emp) continue;
                Furnidata.Mobi d = Salle.details(m.classe);
                r.add(d == null ? 1 : requise(d.xDim, d.yDim));
            }
            if (r.isEmpty()) r.add(1);
            return r;
        }

        private static DalleMagique reglage(HFloorItem it) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || !c.startsWith("tile_stackmagic")) return null;
            return DalleMagique.depuisClasse(c);
        }

        /** Les identifiants de type de toutes les dalles magiques. */
        static Set<Integer> typesDalles() {
            Set<Integer> r = new HashSet<>();
            try {
                Furnidata fd = Salle.gp().getFurniDataTools();
                for (DalleMagique t : DalleMagique.values()) {
                    Integer id = fd.getFloorTypeId(t.classe());
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

        static int[] empriseDalle(DalleMagique t) {
            Furnidata.Mobi d = Salle.details(t.classe());
            if (d != null) return new int[]{Math.max(1, d.xDim), Math.max(1, d.yDim)};
            int n = Math.max(1, Math.abs(t.dimension()));
            return t.dimension() == -1 ? new int[]{1, 2} : new int[]{n, n};
        }

        // ---------------------------------------------- preparation

        /**
         * La case de la dalle magique pour cette pose : aucune s'il y a dans la
         * salle des dalles qui couvrent tous les mobis (la pose s'en sert), sinon
         * une case libre pres du depart, hors du trace (ou un clic dans le jeu
         * s'il n'y en a pas). La dalle est posee, glissee sous chaque mobi et
         * ramassee par la pose (PoseDalle). null = impossible (la raison est dite).
         */
        static Pret preparer(List<Mobi> mobis, List<int[]> trace, int[] depart, Consumer<String> dire) {
            Set<Integer> exig = exigences(mobis);

            // les dalles deja la
            List<Integer> dims = new ArrayList<>();
            HFloorItem premiere = null;
            for (HFloorItem it : new ArrayList<>(Salle.sols())) {
                DalleMagique t = reglage(it);
                if (t != null) { dims.add(t.dimension()); if (premiere == null) premiere = it; }
            }
            if (premiere != null && toutCouvert(dims, exig)) {
                dire.accept("Dalle magique trouvée en (" + premiere.getTile().getX() + "," + premiere.getTile().getY()
                        + ") : elle sert à la pose, puis retourne à sa place.");
                return new Pret(null);
            }

            // il faut en poser une : sa case
            DalleMagique t = modele(exig);
            if (t == null) { dire.accept("Aucune dalle magique ne convient à ce mobi."); return null; }
            Integer type = Salle.gp() == null || Salle.gp().getFurniDataTools() == null ? null
                    : Salle.gp().getFurniDataTools().getFloorTypeId(t.classe());
            if (type == null) { dire.accept("« " + t.classe() + " » inconnue de la furnidata."); return null; }
            int[] e = empriseDalle(t);
            String taille = e[0] + "×" + e[1];

            String raison = "pas de place libre de " + taille + " (cases sans mobi, de même hauteur de sol)";
            Set<Long> occ = occupees(-1);
            java.util.function.BiPredicate<Integer, Integer> libre =
                    (x, y) -> Salle.hauteurSol(x, y) >= 0 && !occ.contains(cle(x, y));
            // toute la salle, pas seulement les abords du depart
            int[] c = choisirCase(depart[0], depart[1], e[0], e[1], trace, libre, null, 64);
            // Rien trouve : c'est toi qui choisis la place, plutot que d'abandonner.
            int clics = 0;
            while (c == null && clics < 2) {
                clics++;
                String q = "Pas de place libre de " + taille + " trouvée pour la dalle magique : clique une case "
                        + "libre (" + taille + " sans mobi autour).";
                dire.accept(q);
                InfoJeu.dire(q);
                HPoint ici = attendreClic(60_000, e[0], e[1]);
                if (ici == null) { raison = "pas de clic en 1 minute"; break; }
                c = coinAutour(ici.getX(), ici.getY(), e[0], e[1], trace, libre);
                if (c == null) {
                    raison = "pas assez de place autour de (" + ici.getX() + "," + ici.getY() + ") : il faut "
                            + taille + " cases libres, sans mobi, de même hauteur de sol";
                    if (clics < 2) dire.accept(Character.toUpperCase(raison.charAt(0)) + raison.substring(1) + ".");
                }
            }
            if (c == null) {
                dire.accept("Impossible de poser la dalle magique : " + raison + ". Pose annulée.");
                return null;
            }
            dire.accept("Dalle magique " + taille + " : posée en (" + c[0] + "," + c[1] + ") pendant la pose, puis ramassée.");
            return new Pret(new HPoint(c[0], c[1]));
        }

        /** Envoie la pose ; renvoie « depuis l'inventaire » / « depuis le BC », ou null. */
        static String envoyer(Moteur gp, int type, DalleMagique t,
                              int x, int y, int rot, Set<Integer> invPris) {
            return envoyer(gp, type, t, x, y, rot, invPris, true);
        }

        /** Les textes que renvoie envoyer, pour savoir d'ou venait la dalle. */
        static final String INVENTAIRE = "depuis ton inventaire", BC = "depuis le catalogue BC";

        /** @param bcPermis false = inventaire seulement (le BC a deja refuse). */
        static String envoyer(Moteur gp, int type, DalleMagique t,
                              int x, int y, int rot, Set<Integer> invPris, boolean bcPermis) {
            return envoyer(gp, type, t, x, y, rot, invPris, bcPermis, true);
        }

        /** @param invPermis false = BC seulement : l'inventaire n'est pas touche. */
        static String envoyer(Moteur gp, int type, DalleMagique t,
                              int x, int y, int rot, Set<Integer> invPris, boolean bcPermis,
                              boolean invPermis) {
            if (invPermis) try {
                Inventaire inv = gp.getInventory();
                if (inv != null && inv.getState() == Inventaire.Etat.LOADED) {
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
            OffresBc.Offre o = null;
            try { o = OffresBc.sol(gp.getCatalog(), gp.getFurniDataTools(), t.classe()); } catch (Throwable ignored) { }
            if (o == null) return null;
            gp.sendToServer(PoseOutils.poseSolBc(o, x, y, rot));
            return BC;
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
        static HPoint attendreClic(long delaiMs, int l, int p) {
            Empreinte.montrer(l, p);
            try { return attendreClic(delaiMs); }
            finally { Empreinte.effacer(); }
        }

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
            Moteur gp = Salle.gp();
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

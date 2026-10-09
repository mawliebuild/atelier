package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobis > Config troc : la boutique wired de la salle.
 *
 * La config est ecrite par l'Atelier (TrocModele) : coffre a mobis
 * (wf_storage_furni1), coffre a credits (wf_storage_coins2) et wired de vente.
 * Aucun wired ne depend du mobi ni du prix : le prix est la variable de mobi
 * « prix » posee sur une VITRINE, le mobi vendu est le type de la vitrine
 * cliquee, le stock est le contenu du coffre a mobis. Elle se pose en ligne de
 * piles (enLigne) depuis la fenetre « Vendre au troc » (TrocVente) : aperçu,
 * clic dans le jeu, progression, dans cette fenetre.
 *
 * L'onglet est la GESTION : la config presente ou non, les vitrines de la
 * salle (mobi, prix, stock), le coffre a credits, le prix par defaut. On y
 * change un prix, reprend le stock d'une vitrine, retire les credits. On vend
 * (et on pose la config) depuis l'inventaire du jeu (« Vendre au troc »). Les
 * resultats partent en message dans le jeu.
 *
 * Donnees (troc.json dans le dossier de l'Atelier) : prix par defaut, dernier
 * prix par type de mobi, et les vitrines posees par l'Atelier, par salle, avec
 * leur prix (seules celles-la sont ramassees automatiquement quand leur stock
 * est vide).
 */
final class ConfigTroc {

    static final int PRIX_DEFAUT = 5;

    // ================================================================ donnees

    /** Une vitrine posee par l'Atelier : id dans la salle, type de sol, posee depuis le BC, prix (0 = inconnu). */
    record Vitrine(int id, int type, boolean bc, int prix) {
        Vitrine(int id, int type, boolean bc) { this(id, type, bc, 0); }
    }

    /** Fichier impose (tests) ; null = Dossiers.donneesAtelier()/troc.json. */
    static volatile File fichierImpose = null;
    private static JSONObject donnees;

    static File fichier() {
        File f = fichierImpose;
        return f != null ? f : new File(Dossiers.donneesAtelier(), "troc.json");
    }

    private static synchronized JSONObject donnees() {
        if (donnees != null) return donnees;
        File f = fichier();
        try {
            donnees = f.isFile() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    : new JSONObject();
        } catch (Throwable t) {
            Journal.debug("Troc : " + f + " illisible (" + t + ")");
            donnees = new JSONObject();
        }
        return donnees;
    }

    /** Oublie ce qui est en memoire (tests : changement de fichier). */
    static synchronized void relire() { donnees = null; }

    private static synchronized void enregistrer() {
        File f = fichier();
        try {
            File d = f.getParentFile();
            if (d != null) d.mkdirs();
            File tmp = new File(d, f.getName() + ".tmp");
            Files.write(tmp.toPath(), donnees().toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Journal.debug("Troc : " + f + " non enregistré (" + t + ")");
        }
    }

    static synchronized int prixDefaut() { return Math.max(1, donnees().optInt("prix", PRIX_DEFAUT)); }

    static synchronized void prixDefaut(int p) { donnees().put("prix", Math.max(1, p)); enregistrer(); }

    /** Le dernier prix donne a ce type de mobi ; 0 s'il n'a jamais ete vendu. */
    static synchronized int dernierPrix(int type) {
        JSONObject d = donnees().optJSONObject("derniersPrix");
        return d == null ? 0 : Math.max(0, d.optInt(String.valueOf(type), 0));
    }

    static synchronized void dernierPrix(int type, int prix) {
        JSONObject d = donnees().optJSONObject("derniersPrix");
        if (d == null) { d = new JSONObject(); donnees().put("derniersPrix", d); }
        d.put(String.valueOf(type), Math.max(1, prix));
        enregistrer();
    }

    /** Le prix propose pour ce type : le dernier utilise, sinon le prix par defaut. */
    static int prixPropose(int type) {
        int p = dernierPrix(type);
        return p > 0 ? p : prixDefaut();
    }

    static synchronized List<Vitrine> vitrines(int salle) {
        List<Vitrine> l = new ArrayList<>();
        JSONObject v = donnees().optJSONObject("vitrines");
        JSONArray a = v == null ? null : v.optJSONArray(String.valueOf(salle));
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) l.add(new Vitrine(o.optInt("id"), o.optInt("type"), o.optBoolean("bc"), o.optInt("prixVente", 0)));
        }
        return l;
    }

    /** Ajoute la vitrine (ou met a jour son prix si elle est deja connue). */
    static synchronized void ajouterVitrine(int salle, Vitrine v) {
        JSONObject vs = donnees().optJSONObject("vitrines");
        if (vs == null) { vs = new JSONObject(); donnees().put("vitrines", vs); }
        JSONArray a = vs.optJSONArray(String.valueOf(salle));
        if (a == null) { a = new JSONArray(); vs.put(String.valueOf(salle), a); }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.optInt("id") != v.id()) continue;
            if (v.prix() > 0) { o.put("prixVente", v.prix()); enregistrer(); }
            return;
        }
        JSONObject o = new JSONObject().put("id", v.id()).put("type", v.type()).put("bc", v.bc());
        if (v.prix() > 0) o.put("prixVente", v.prix());
        a.put(o);
        enregistrer();
    }

    /** Retient le prix d'une vitrine connue. */
    static synchronized void prixVitrine(int salle, int id, int prix) {
        JSONObject vs = donnees().optJSONObject("vitrines");
        JSONArray a = vs == null ? null : vs.optJSONArray(String.valueOf(salle));
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.optInt("id") == id) { o.put("prixVente", prix); enregistrer(); return; }
        }
    }

    static synchronized void oublierVitrine(int salle, int id) {
        JSONObject vs = donnees().optJSONObject("vitrines");
        JSONArray a = vs == null ? null : vs.optJSONArray(String.valueOf(salle));
        if (a == null) return;
        for (int i = a.length() - 1; i >= 0; i--) if (a.getJSONObject(i).optInt("id") == id) a.remove(i);
        if (a.length() == 0) vs.remove(String.valueOf(salle));
        enregistrer();
    }

    /** La vitrine de l'Atelier pour ce type dans cette salle, encore posee ; null sinon. */
    static Vitrine vitrineDe(int salle, int type) {
        for (Vitrine v : vitrines(salle)) if (v.type() == type && Salle.sol(v.id()) != null) return v;
        return null;
    }

    // ================================================================ variable « prix »

    /**
     * L'id de la variable de mobi « prix » parmi celles de la salle : pas
     * interne, genre mobi (0) de preference. Logique pure ; null si absente.
     */
    static String choisirPrix(Collection<PoseOutils.Variable> l) {
        String repli = null;
        for (PoseOutils.Variable v : l) {
            if (v.nom() == null || v.typeInterne() == 1) continue;
            if (!v.nom().trim().equalsIgnoreCase(TrocModele.NOM_PRIX)) continue;
            if (v.genre() == 0) return v.id();
            if (repli == null) repli = v.id();
        }
        return repli;
    }

    private static final Map<String, PoseOutils.Variable> variables = new ConcurrentHashMap<>();
    private static volatile long attenteVariables = 0;
    private static volatile boolean variablesFinies = false, ecouteVariables = false;
    /**
     * Les morceaux de la liste sont lus UN PAR UN, dans l'ordre d'arrivee : lus
     * chacun sur son propre fil, le dernier morceau pouvait finir l'attente
     * avant qu'un morceau precedent (celui qui contenait « prix ») soit range.
     */
    private static final java.util.concurrent.ExecutorService FIL_VARIABLES =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-troc-variables");
                t.setDaemon(true);
                return t;
            });

    private static synchronized void ecouterVariables() {
        if (ecouteVariables) return;
        Moteur gp = Salle.gp();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariablesDiffs", m -> {
                // gros paquet frequent : copie seulement quand on attend la liste
                if (System.currentTimeMillis() > attenteVariables) return;
                try {
                    HPacket copie = new HPacket(m.getPacket());
                    FIL_VARIABLES.execute(() -> {
                        try {
                            PoseOutils.ListeVariables lv = PoseOutils.lireListeVariables(copie);
                            for (PoseOutils.Variable v : lv.variables()) if (v.id() != null) variables.put(v.id(), v);
                            if (lv.dernier()) variablesFinies = true;
                        } catch (Throwable ignored) { }
                    });
                } catch (Throwable ignored) { }
            });
            ecouteVariables = true;
        } catch (Throwable t) {
            Journal.debug("Troc : WiredAllVariablesDiffs non intercepté (" + t + ")");
        }
    }

    /**
     * L'id de la variable « prix » de la salle ouverte : la liste est TOUJOURS
     * redemandee au jeu (une variable creee par la pose n'est pas dans une
     * liste lue avant) ; null si absente. Fil de travail.
     */
    static synchronized String idPrix(long maxMs) {
        ecouterVariables();
        variables.clear();
        variablesFinies = false;
        attenteVariables = System.currentTimeMillis() + maxMs + 1000;
        Salle.envoyerEspace(PoseOutils.demandeVariables());
        long fin = System.currentTimeMillis() + maxMs;
        while (!variablesFinies && System.currentTimeMillis() < fin) Salle.sommeil(50);
        // les morceaux deja recus finissent d'etre ranges
        try { FIL_VARIABLES.submit(() -> { }).get(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Throwable ignored) { }
        attenteVariables = 0;
        String id = choisirPrix(variables.values());
        if (id != null) return id;
        // repli : la liste deja lue par la lecture des wired (sans le genre)
        return prixParNom(WiredLecteur.variables());
    }

    /** L'id de la variable nommee « prix » dans une table id -> nom ; null sinon. Logique pure. */
    static String prixParNom(Map<String, String> w) {
        if (w == null) return null;
        for (Map.Entry<String, String> e : w.entrySet())
            if (e.getValue() != null && e.getValue().trim().equalsIgnoreCase(TrocModele.NOM_PRIX)) return e.getKey();
        return null;
    }

    /**
     * Change le prix d'une vitrine deja dans la salle (variable « prix » deja
     * posee) et le retient. null si c'est fait, sinon l'erreur. Fil de travail.
     */
    static String changerPrix(int salle, Vitrine v, int prix) {
        if (prix < 1) return "Le prix doit être d'au moins 1 crédit.";
        if (Salle.salleId() != salle) return "Tu as changé de salle.";
        if (Salle.sol(v.id()) == null) return "Cette vitrine n'est plus dans la salle.";
        String idPrix = idPrix(3000);
        if (idPrix == null) return "Variable « prix » introuvable : la config troc est-elle posée ?";
        Canal canal = Canal.duMoteur();
        HPacket p = new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER, 0, v.id(), idPrix, prix, 0);
        if (canal == null || !PoseOutils.envoyerVariable(canal, p)) return "Le prix n'a pas pu être changé.";
        prixVitrine(salle, v.id(), prix);
        dernierPrix(v.type(), prix);
        return null;
    }

    // ================================================================ verification

    /** La phrase quand la salle n'a pas de config troc (onglet Config troc). */
    static final String SANS_CONFIG = "Clique « Vendre au troc » sur un mobi de ton inventaire pour poser la config.";

    /** Ce que la salle ouverte a de la config troc. */
    record Etat(int salle, TrocCoffres.Coffres coffres, String idPrix) {
        /** Les deux coffres et la variable « prix » : c'est tout ce qu'il faut pour vendre. */
        boolean complete() { return coffres.complets() && idPrix != null; }

        String texte() {
            if (salle == -1) return "Tu n'es pas dans une salle.";
            if (coffres.mobis() == 0 && coffres.credits() == 0 && idPrix == null) return SANS_CONFIG;
            List<String> manque = new ArrayList<>();
            if (coffres.mobis() == 0) manque.add("le coffre à mobis");
            if (coffres.credits() == 0) manque.add("le coffre à crédits");
            if (idPrix == null) manque.add("la variable « prix »");
            if (manque.isEmpty()) return "Config troc en place.";
            return "Config troc incomplète : il manque " + String.join(", ", manque) + ".";
        }
    }

    /** Les wired qui font la vente (sans eux, des coffres seuls ne vendent rien). */
    static final List<String> WIRED_DE_VENTE = List.of("wf_act_init_transaction", "wf_xtra_custom_contract");

    /**
     * Les wired de vente sont-ils dans la salle (etat de l'Atelier, en direct) ? Apres un
     * ramassage de la config (pickall…), des coffres peuvent rester alors que les wired
     * sont partis. Fil quelconque.
     */
    static boolean wiredEnPlace() {
        Set<String> vus = new HashSet<>();
        for (gearth.extensions.parsers.HFloorItem it : Salle.sols()) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null) vus.add(c);
        }
        return vus.containsAll(WIRED_DE_VENTE);
    }

    /** Fil de travail (demande la liste des variables). */
    static Etat verifier() {
        int salle = Salle.salleId();
        if (salle == -1) return new Etat(-1, new TrocCoffres.Coffres(0, 0), null);
        TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
        return new Etat(salle, c, idPrix(2500));
    }

    /**
     * Apres une pose : attend que la config soit vue (coffres arrives dans
     * l'etat de la salle, variable « prix » dans la liste REDEMANDEE au jeu),
     * jusqu'a maxMs. Rend le dernier etat lu. Fil de travail.
     */
    static Etat attendreConfig(int salle, long maxMs, java.util.function.BooleanSupplier stop) {
        long fin = System.currentTimeMillis() + maxMs;
        Etat e;
        String prix = null;
        while (true) {
            if (Salle.salleId() != salle) return new Etat(Salle.salleId(), new TrocCoffres.Coffres(0, 0), null);
            TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
            if (prix == null) prix = idPrix(2000);
            e = new Etat(salle, c, prix);
            if (e.complete() || System.currentTimeMillis() >= fin || (stop != null && stop.getAsBoolean())) return e;
            Salle.sommeil(700);
        }
    }

    /**
     * Les coffres a poser : pour chaque genre, la premiere variante que
     * l'inventaire contient (les coffres ne sont pas au Builders Club), sinon
     * celle de la config. Logique pure : classes de l'inventaire.
     */
    static String[] coffresAPoser(Collection<String> classesInventaire) {
        String m = TrocCoffres.COFFRE_MOBIS, c = TrocCoffres.COFFRE_CREDITS;
        if (classesInventaire != null) {
            for (String k : TrocCoffres.CLASSES_MOBIS) if (classesInventaire.contains(k)) { m = k; break; }
            for (String k : TrocCoffres.CLASSES_CREDITS) if (classesInventaire.contains(k)) { c = k; break; }
        }
        return new String[]{m, c};
    }

    /**
     * Pose la config troc de l'Atelier (TrocModele) dans la salle ouverte, en
     * LIGNE DE PILES (voir enLigne) : aperçu dans le panneau (fenetre « Vendre
     * au troc »), Confirmer, puis clic dans le jeu sur la case de depart de la
     * ligne. Les coffres sont pris dans l'inventaire (classes donnees par
     * coffresAPoser). Refuse si un collage est en cours ou si les coffres sont
     * deja dans la salle. Fil JavaFX.
     */
    static void collerIci(WiredCollage.Panneau suivi, String[] coffres) {
        int salle = Salle.salleId();
        if (salle == -1) { suivi.refus("Tu n'es pas dans une salle."); return; }
        if (WiredCollage.occupe()) { suivi.refus("Un collage est déjà en cours : attends qu'il se termine."); return; }
        TrocCoffres.Coffres deja = TrocCoffres.dansLaSalle();
        if (deja.complets() && wiredEnPlace()) { suivi.refus("La config troc est déjà dans cette salle."); return; }
        if (deja.mobis() != 0 || deja.credits() != 0) {
            suivi.refus("Les coffres d'une ancienne config troc sont encore dans la salle : ramasse-les "
                    + "(vide-les d'abord s'ils refusent), puis recommence.");
            return;
        }
        String[] cf = coffres == null || coffres.length < 2 ? coffresAPoser(null) : coffres;
        WiredCollage.Copie modele = TrocModele.copie(cf[0], cf[1]);
        // aperçu : la ligne le long de x, sol plat ; la vraie ligne est refaite au clic
        WiredCollage.Copie apercu = enLigne(modele, 0, 0, false, null);
        int n = piles(apercu).size();
        WiredCollage.collerDans(apercu, "Poser la config troc",
                TrocModele.resume() + " " + n + " piles sur une ligne de " + n + " cases.",
                null, suivi, true, "la case de départ de la ligne de piles", (c, coin) -> {
                    int k = piles(c).size();
                    boolean y = leLongDeY(k, coin.getX(), coin.getY(), Salle::hauteurSol);
                    return enLigne(c, coin.getX(), coin.getY(), y, Salle::hauteurSol);
                });
    }

    // ================================================================ ligne de piles (logique pure)

    /**
     * Les piles d'une copie : une par case d'origine (x, y), dans l'ordre de
     * lecture (y puis x) ; dans une pile, les pieces du bas vers le haut.
     */
    static List<List<WiredCollage.Piece>> piles(WiredCollage.Copie c) {
        Map<Long, List<WiredCollage.Piece>> m = new TreeMap<>();
        for (WiredCollage.Piece p : c.pieces)
            m.computeIfAbsent(((long) p.y << 32) | (p.x & 0xffffffffL), k -> new ArrayList<>()).add(p);
        List<List<WiredCollage.Piece>> r = new ArrayList<>();
        for (List<WiredCollage.Piece> l : m.values()) {
            l.sort(Comparator.comparingDouble(p -> p.z));
            r.add(l);
        }
        return r;
    }

    /**
     * La ligne de n piles partant de (ox, oy) le long de x sortirait-elle du
     * plan (case hors plan ou vide) alors que le long de y elle tient ? Sans
     * plan connu : le long de x.
     */
    static boolean leLongDeY(int n, int ox, int oy, java.util.function.IntBinaryOperator sol) {
        if (sol == null) return false;
        boolean x = true, y = true;
        for (int i = 0; i < n; i++) {
            if (sol.applyAsInt(ox + i, oy) < 0) x = false;
            if (sol.applyAsInt(ox, oy + i) < 0) y = false;
        }
        return !x && y;
    }

    /**
     * La copie remise en LIGNE DE PILES : la pile i (ordre de piles()) va en
     * (i, 0) (ou (0, i) le long de y), relativement au coin. Dans une pile,
     * ordre et ecarts de hauteur gardes : z = sol de la nouvelle case + ecart
     * au-dessus du bas de la pile. Les z de la copie etant comptes depuis le
     * sol le plus bas du rectangle de pose (PoseCopie.decalage, bornes
     * incluses), z = (sol de la case - ce sol le plus bas) + ecart.
     *
     * Les selections des wired designent les mobis par leur id : elles suivent
     * d'elles-memes. Les liaisons des wired « instantane » (position, altitude
     * memorisees) sont decalees comme le mobi qu'elles designent.
     *
     * @param ox, oy case de depart dans la salle (pour le sol)
     * @param sol    hauteur du sol nu d'une case (-1 = hors plan) ; null = sol plat
     */
    static WiredCollage.Copie enLigne(WiredCollage.Copie c, int ox, int oy, boolean leLongDeY,
                                      java.util.function.IntBinaryOperator sol) {
        List<List<WiredCollage.Piece>> piles = piles(c);
        int n = piles.size();
        int w = leLongDeY ? 1 : n, l = leLongDeY ? n : 1;
        int bas = 0;
        if (sol != null) {
            int b = 256;
            for (int x = ox; x <= ox + w; x++)
                for (int y = oy; y <= oy + l; y++) {
                    int h = sol.applyAsInt(x, y);
                    b = Math.min(b, h < 0 ? 256 : h);
                }
            bas = b >= 256 ? 0 : b;
        }
        // id -> {dx, dy, dz}
        Map<Integer, double[]> decalages = new HashMap<>();
        Map<Integer, double[]> places = new HashMap<>();
        // altitudes deja prises (en centiemes), toutes piles confondues
        Set<Long> pris = new HashSet<>();
        for (int i = 0; i < n; i++) {
            List<WiredCollage.Piece> pile = piles.get(i);
            int nx = leLongDeY ? 0 : i, ny = leLongDeY ? i : 0;
            int h = sol == null ? bas : sol.applyAsInt(ox + nx, oy + ny);
            double socle = Math.max(0, (h < 0 ? bas : h) - bas);
            double base = pile.get(0).z;
            // Deux mobis de piles differentes n'ont jamais la meme altitude : la
            // pose (dalle magique) ne renvoie la hauteur de la dalle que si elle
            // change, et une dalle deplacee sur une autre case retombe au sol ;
            // deux altitudes egales de suite posaient le second mobi DANS celui
            // du bas de sa pile. La pile entiere monte de 0,01 jusqu'a etre seule
            // a ses altitudes (les ecarts dans la pile sont gardes).
            double extra = 0;
            for (int essai = 0; essai < 100; essai++) {
                boolean libre = true;
                for (WiredCollage.Piece p : pile)
                    if (pris.contains(Math.round(WiredCollage.arrondi(socle + extra + (p.z - base)) * 100))) { libre = false; break; }
                if (libre) break;
                extra = WiredCollage.arrondi(extra + 0.01);
            }
            for (WiredCollage.Piece p : pile) {
                double z = WiredCollage.arrondi(socle + extra + (p.z - base));
                pris.add(Math.round(z * 100));
                places.put(p.id, new double[]{nx, ny, z});
                decalages.put(p.id, new double[]{nx - p.x, ny - p.y, z - p.z});
            }
        }
        List<WiredCollage.Piece> r = new ArrayList<>();
        for (WiredCollage.Piece p : c.pieces) {
            double[] o = places.get(p.id);
            List<WiredCollage.Liaison> li = new ArrayList<>();
            for (WiredCollage.Liaison b : p.liaisons) {
                double[] d = decalages.get(b.furniId);
                if (d == null) { li.add(b); continue; }
                li.add(new WiredCollage.Liaison(b.furniId, b.etat, b.rot,
                        b.x == null ? null : b.x + (int) d[0], b.y == null ? null : b.y + (int) d[1],
                        b.alt == null ? null : b.alt + (int) Math.round(d[2] * 100)));
            }
            r.add(new WiredCollage.Piece(p.id, p.classe, p.etat, (int) o[0], (int) o[1], o[2], p.rot,
                    p.genre, p.config, li));
        }
        // du bas vers le haut, comme WiredCollage.construire (ordre d'empilement de la pose)
        r.sort(Comparator.comparingDouble((WiredCollage.Piece p) -> p.z).thenComparingInt(p -> p.y).thenComparingInt(p -> p.x));
        return new WiredCollage.Copie(r, c.variables, c.salle, c.quand);
    }

    // ================================================================ vitrines et stock (logique pure)

    /** Une vitrine vue dans la salle : la vitrine, son nom, son stock (-1 = coffre pas lu). */
    record EnVente(Vitrine vitrine, String nom, int stock) { }

    /** « 5 crédits · 12 en stock » ; « Prix inconnu » si l'Atelier ne l'a pas retenu. Logique pure. */
    static String detail(int prix, int stock) {
        String p = prix > 0 ? prix + (prix > 1 ? " crédits" : " crédit") : "Prix inconnu";
        String s = stock < 0 ? "stock non lu" : stock == 0 ? "plus de stock" : stock + " en stock";
        return p + " · " + s;
    }

    // ================================================================ onglet

    private VBox racine, lignesVitrines;
    private TextField prix;
    private Label presence, creditsLbl, horsVitrinesLbl, etat;
    private Button relire, retirer;
    private volatile TrocCoffres.Coffres coffres = new TrocCoffres.Coffres(0, 0);
    private volatile boolean enCours = false;

    Tab construire() {
        TrocCoffres.installer();

        presence = Ui.valeur("—");
        presence.setWrapText(true);
        Button verifierBtn = new Button("Vérifier");
        verifierBtn.setOnAction(e -> majSalle(true));

        lignesVitrines = new VBox(6);
        lignesVitrines.setFillWidth(true);
        horsVitrinesLbl = Ui.discret("");
        horsVitrinesLbl.setWrapText(true);
        horsVitrinesLbl.managedProperty().bind(horsVitrinesLbl.visibleProperty());
        relire = new Button("Relire les coffres");
        relire.setOnAction(e -> majSalle(true));

        creditsLbl = Ui.valeur("—");
        creditsLbl.setWrapText(true);
        retirer = new Button("Retirer les crédits");
        retirer.setDisable(true);
        Ui.bulle(retirer, "Tous les crédits du coffre vont dans ton porte-monnaie.");
        retirer.setOnAction(e -> retirerCredits());

        prix = new TextField(String.valueOf(prixDefaut()));
        prix.setPrefColumnCount(6);
        prix.setOnAction(e -> enregistrerPrix());
        prix.focusedProperty().addListener((o, a, b) -> { if (!b) enregistrerPrix(); });
        HBox lignePrix = new HBox(8, prix, Ui.etiquette("crédits l'unité"));
        lignePrix.setAlignment(Pos.CENTER_LEFT);
        etat = Ui.etat();

        racine = new VBox(14,
                Ui.bloc("Dans cette salle",
                        presence,
                        Ui.ligne(verifierBtn),
                        Ui.aide("La config troc : un coffre à mobis (le stock), un coffre à crédits (la recette) "
                                + "et les wired de vente. Un clic sur une vitrine ouvre l'achat ; le prix est "
                                + "celui de la vitrine.")),
                Ui.bloc("Vitrines",
                        lignesVitrines, horsVitrinesLbl,
                        Ui.ligne(relire),
                        Ui.aide("Pour vendre : dans l'inventaire du jeu, choisis un mobi puis « Vendre au troc ». "
                                + "Une vitrine est ramassée toute seule quand son stock est vide.")),
                Ui.bloc("Coffre à crédits",
                        creditsLbl,
                        Ui.ligne(retirer)),
                Ui.bloc("Prix par défaut",
                        lignePrix,
                        Ui.aide("Proposé dans « Vendre au troc » pour un mobi jamais vendu ; sinon, "
                                + "c'est son dernier prix.")),
                etat);
        racine.setFillWidth(true);
        racine.setPadding(new Insets(12, 14, 14, 14));
        Ui.majusculesAuto(racine);
        majVitrines(List.of(), false);

        ScrollPane sp = new ScrollPane(racine);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab t = new Tab("Config troc", sp);
        t.setClosable(false);
        // la rubrique s'affiche : verification et contenu des coffres
        racine.sceneProperty().addListener((o, a, sc) -> {
            if (sc == null) return;
            if (sc.getWindow() != null) suivreFenetre(sc.getWindow());
            sc.windowProperty().addListener((o2, w0, w) -> { if (w != null) suivreFenetre(w); });
        });
        racine.visibleProperty().addListener((o, a, b) -> { if (b) majSalle(true); });
        return t;
    }

    private final Set<Window> suivies = Collections.newSetFromMap(new WeakHashMap<>());

    private void suivreFenetre(Window w) {
        if (!suivies.add(w)) return;
        w.showingProperty().addListener((o, a, montre) -> { if (montre) majSalle(true); });
        if (w.isShowing()) majSalle(true);
    }

    // ------------------------------------------------ prix par defaut

    private void enregistrerPrix() {
        try {
            int p = Integer.parseInt(prix.getText().trim());
            if (p < 1) throw new NumberFormatException();
            if (p != prixDefaut()) prixDefaut(p);
        } catch (NumberFormatException e) {
            prix.setText(String.valueOf(prixDefaut()));
        }
    }

    // ------------------------------------------------ salle et coffres

    /** Verifie la salle (et relit les coffres si demande), hors du fil JavaFX. */
    private void majSalle(boolean avecCoffres) {
        if (enCours) return;
        enCours = true;
        presence.setText("Vérification…");
        Salle.tache("troc-config", () -> {
            try {
                Etat e = verifier();
                coffres = e.coffres();
                Platform.runLater(() -> {
                    presence.setText(e.texte());
                    retirer.setDisable(e.coffres().credits() == 0);
                    relire.setDisable(!e.coffres().complets());
                });
                if (avecCoffres && e.coffres().complets()) {
                    TrocCoffres.verifierVitrines();
                    lireCoffres(e);
                } else if (!e.coffres().complets()) {
                    Platform.runLater(() -> {
                        creditsLbl.setText("—");
                        majVitrines(enVente(e.salle(), null), false);
                    });
                }
            } catch (Throwable t) {
                Journal.debug("Troc : vérification en erreur (" + t + ")");
            } finally { enCours = false; }
        });
    }

    /** Les vitrines de l'Atelier encore dans la salle, avec leur stock (contenu null : stock -1). */
    private static List<EnVente> enVente(int salle, List<TrocCoffres.Rangement> contenu) {
        List<EnVente> l = new ArrayList<>();
        if (salle == -1) return l;
        for (Vitrine v : vitrines(salle)) {
            if (Salle.sol(v.id()) == null) continue;
            l.add(new EnVente(v, Salle.nom(v.type(), false), contenu == null ? -1 : TrocCoffres.stock(contenu, v.type())));
        }
        l.sort(Comparator.comparing(EnVente::nom, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    private void lireCoffres(Etat e) {
        TrocCoffres.Coffres c = e.coffres();
        TrocCoffres.Credits cr = TrocCoffres.lireCredits(c.credits());
        List<TrocCoffres.Rangement> mob = TrocCoffres.lireMobis(c.mobis());
        String tCredits = cr == null
                ? (TrocCoffres.ouvertParElle(c.credits()) ? "Coffre ouvert dans le jeu : ferme-le pour le lire."
                        : "Pas de réponse du coffre.")
                : Ui.accorder(cr.credits() + " crédit(s) dans le coffre.");
        List<EnVente> l = enVente(e.salle(), mob);
        // mobis du coffre sans vitrine de l'Atelier
        StringBuilder hors = new StringBuilder();
        if (mob != null) {
            Set<String> avecVitrine = new HashSet<>();
            for (EnVente v : l) avecVitrine.add("s" + v.vitrine().type());
            int k = 0;
            for (Map.Entry<String, Integer> x : TrocCoffres.parType(mob).entrySet()) {
                if (avecVitrine.contains(x.getKey())) continue;
                if (k++ >= 6) { hors.append(", …"); break; }
                boolean mural = x.getKey().startsWith("m");
                int type = Integer.parseInt(x.getKey().substring(1));
                hors.append(hors.length() == 0 ? "" : ", ").append(Salle.nom(type, mural)).append(" × ").append(x.getValue());
            }
        }
        String tHors = hors.length() == 0 ? "" : "Dans le coffre sans vitrine : " + hors + ".";
        boolean pasLu = mob == null;
        String tMobis = !pasLu ? "" : TrocCoffres.ouvertParElle(c.mobis())
                ? "Coffre à mobis ouvert dans le jeu : ferme-le pour lire le stock." : "Le coffre à mobis ne répond pas.";
        boolean vide = cr != null && cr.credits() <= 0;
        Platform.runLater(() -> {
            creditsLbl.setText(tCredits);
            if (vide) retirer.setDisable(true);
            majVitrines(l, true);
            horsVitrinesLbl.setText(pasLu ? tMobis : tHors);
            horsVitrinesLbl.setVisible(!horsVitrinesLbl.getText().isEmpty());
        });
    }

    /** Les lignes des vitrines. Fil JavaFX. */
    private void majVitrines(List<EnVente> l, boolean configLue) {
        lignesVitrines.getChildren().clear();
        if (l.isEmpty()) {
            Label v = Ui.discret(configLue ? "Aucune vitrine posée par l'Atelier dans cette salle."
                    : "Aucune vitrine pour l'instant.");
            v.setWrapText(true);
            lignesVitrines.getChildren().add(v);
            return;
        }
        for (EnVente e : l) lignesVitrines.getChildren().add(ligne(e));
    }

    /** Une vitrine : nom, prix et stock ; changer le prix, reprendre le stock. */
    private Node ligne(EnVente e) {
        Label nom = new Label(e.nom());
        nom.setStyle("-fx-font-weight: bold;");
        nom.setWrapText(true);
        Label det = Ui.discret(detail(e.vitrine().prix(), e.stock()));
        det.setStyle("-fx-font-style: normal;");
        VBox texte = new VBox(1, nom, det);
        texte.setMinWidth(0);
        HBox.setHgrow(texte, Priority.ALWAYS);
        Button changer = Icones.seul(Icones.CRAYON, "Changer le prix");
        Button reprendre = Icones.seul(Icones.RAMASSER, "Reprendre le stock dans ton inventaire "
                + "(la vitrine est ensuite ramassée)");
        reprendre.setDisable(e.stock() <= 0);
        HBox actions = new HBox(4, changer, reprendre);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox h = new HBox(8, texte, actions);
        h.setAlignment(Pos.CENTER_LEFT);

        // changer le prix : en ligne, sous la vitrine
        TextField nouveau = new TextField(String.valueOf(e.vitrine().prix() > 0 ? e.vitrine().prix() : prixPropose(e.vitrine().type())));
        nouveau.setPrefColumnCount(6);
        Button ok = new Button("Changer");
        ok.getStyleClass().add("primaire");
        Button non = new Button("Annuler");
        HBox edition = new HBox(6, nouveau, Ui.etiquette("crédits"), non, ok);
        edition.setAlignment(Pos.CENTER_LEFT);
        edition.setVisible(false);
        edition.setManaged(false);
        VBox v = new VBox(4, h, edition);
        v.getProperties().put("vente", e);
        changer.setOnAction(a -> {
            boolean montre = !edition.isVisible();
            edition.setVisible(montre);
            edition.setManaged(montre);
            if (montre) { nouveau.requestFocus(); nouveau.selectAll(); }
        });
        non.setOnAction(a -> { edition.setVisible(false); edition.setManaged(false); });
        Runnable valider = () -> {
            int p;
            try { p = Integer.parseInt(nouveau.getText().trim()); }
            catch (NumberFormatException x) { Ui.erreur(etat, "Prix invalide : un nombre entier de crédits."); return; }
            if (p < 1) { Ui.erreur(etat, "Le prix doit être d'au moins 1 crédit."); return; }
            edition.setVisible(false);
            edition.setManaged(false);
            int salle = Salle.salleId();
            Salle.tache("troc-prix", () -> {
                String err = changerPrix(salle, e.vitrine(), p);
                if (err != null) Journal.erreur(err);
                else Journal.succes(e.nom() + " : " + p + (p > 1 ? " crédits." : " crédit."));
                Platform.runLater(() -> majSalle(false));
                Platform.runLater(() -> majVitrines(majPrix(e, p, err == null), true));
            });
        };
        ok.setOnAction(a -> valider.run());
        nouveau.setOnAction(a -> valider.run());
        reprendre.setOnAction(a -> reprendre(e));
        return v;
    }

    /** Les lignes actuelles, le prix de cette vitrine change (sans relire les coffres). */
    private List<EnVente> majPrix(EnVente e, int p, boolean fait) {
        List<EnVente> l = new ArrayList<>();
        for (Node n : lignesVitrines.getChildren()) if (n.getProperties().get("vente") instanceof EnVente x) l.add(x);
        if (l.isEmpty()) l.add(e);
        List<EnVente> r = new ArrayList<>();
        for (EnVente x : l) {
            if (fait && x.vitrine().id() == e.vitrine().id()) {
                Vitrine v = x.vitrine();
                r.add(new EnVente(new Vitrine(v.id(), v.type(), v.bc(), p), x.nom(), x.stock()));
            } else r.add(x);
        }
        return r;
    }

    /** Reprend tout le stock de cette vitrine dans l'inventaire ; la vitrine est ensuite ramassee. */
    private void reprendre(EnVente e) {
        TrocCoffres.Coffres c = coffres;
        if (c.mobis() == 0) { Journal.erreur("Pas de coffre à mobis dans cette salle."); return; }
        etat.setText("Reprise du stock…");
        Salle.tache("troc-reprise", () -> {
            TrocCoffres.Retrait r = TrocCoffres.retirerMobis(c.mobis(), e.vitrine().type(), Math.max(1, e.stock()));
            Platform.runLater(() -> etat.setText(""));
            if (r.erreur() != null) Journal.erreur(r.erreur());
            else Journal.succes(r.nombre() + " " + TrocVente.pluriel(e.nom(), r.nombre()) + " repris du coffre.");
            Platform.runLater(() -> majSalle(true));
        });
    }

    private void retirerCredits() {
        TrocCoffres.Coffres c = coffres;
        retirer.setDisable(true);
        Salle.tache("troc-retrait", () -> {
            String err = TrocCoffres.retirerCredits(c.credits());
            if (err != null) Journal.erreur(err);
            Platform.runLater(() -> majSalle(true));
        });
    }
}

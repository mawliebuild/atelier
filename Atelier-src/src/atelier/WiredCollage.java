package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Copier / coller une configuration wired.
 *
 * COPIER (copier) : on prend les mobis de sol d'un calque (ou d'une
 * selection). Pour chaque wired, on lit son reglage complet (WiredLecteur :
 * textes, mobis choisis, delai, options, sources, variables, filtre...). On
 * garde aussi les autres mobis, avec leur position RELATIVE (coin x min,
 * y min = 0,0 ; altitude au-dessus du sol le plus bas de la copie). La copie
 * est enregistree sous un nom dans le dossier « copies-wired » (a cote du
 * dossier des apparts), avec une photo d'apercu (ApercuMobis) : elle survit
 * a un changement de salle et a un redemarrage.
 *
 * COLLER (collerDans) : aperçu chiffre (wired, mobis lies, ce qu'il y a dans
 * l'inventaire / au BC, selections perdues), puis Confirmer, puis la case du
 * coin haut-gauche (donnee, ou cliquee dans le jeu). Aucune fenetre a part :
 * l'aperçu, la progression et Arreter sont dans l'onglet de l'outil (Panneau,
 * comme OutilHauteur), le bilan part en message dans le jeu. La pose passe par
 * le moteur de pose, exactement comme un appart exporte avec ses
 * wired : il pose les mobis (inventaire et/ou BC, dalle magique pour les
 * hauteurs exactes), puis enregistre le reglage de chaque wired (paquets
 * UpdateTrigger / UpdateCondition / UpdateAction / UpdateAddon /
 * UpdateSelector / UpdateVariable) en REMPLAÇANT les ids des mobis choisis
 * par ceux des mobis qu'il vient de poser (realFurniIdMap). Un mobi choisi
 * qui n'etait PAS dans la copie est retire de la selection (annonce dans
 * l'aperçu).
 *
 * Wired « instantane » (wf_act_match_to_sshot, wf_cnd_match_snapshot,
 * wf_cnd_not_match_snap, wf_trg_stuff_state) : comme l'export de
 * l'Atelier, l'etat / la position / la rotation memorises sont lus dans le
 * texte du reglage (« id,etat,rot,x,y[,alt];... ») et donnes en liaisons
 * (PresetWiredFurniBinding), positions ramenees au coin de la copie.
 *
 * VERIFICATION : apres la pose, les nouveaux wired sont retrouves par leur
 * classe et leur position relative, relus, et compares au reglage copie
 * (texte, options, delai, mobis choisis remappes).
 */
public final class WiredCollage {

    private WiredCollage() { }

    /** Les wired dont la selection garde etat / position (GPresetExporter.requireBindings). */
    static final Set<String> A_LIAISONS = Set.of("wf_act_match_to_sshot", "wf_cnd_match_snapshot",
            "wf_cnd_not_match_snap", "wf_trg_stuff_state");

    // =================================================================== modele

    /** Etat / position memorises par un wired « instantane » (positions relatives). */
    static final class Liaison {
        final int furniId;
        final String etat;          // null = pas memorise
        final Integer rot, alt;     // null = pas memorise
        final Integer x, y;         // null = pas memorise
        Liaison(int furniId, String etat, Integer rot, Integer x, Integer y, Integer alt) {
            this.furniId = furniId; this.etat = etat; this.rot = rot; this.x = x; this.y = y; this.alt = alt;
        }
        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("furniId", furniId);
            if (etat != null) o.put("etat", etat);
            if (rot != null) o.put("rot", rot);
            if (x != null) o.put("x", x);
            if (y != null) o.put("y", y);
            if (alt != null) o.put("alt", alt);
            return o;
        }
        static Liaison depuis(JSONObject o) {
            return new Liaison(o.getInt("furniId"), o.has("etat") ? o.getString("etat") : null,
                    o.has("rot") ? o.getInt("rot") : null, o.has("x") ? o.getInt("x") : null,
                    o.has("y") ? o.getInt("y") : null, o.has("alt") ? o.getInt("alt") : null);
        }
    }

    /** Un mobi de la copie. x, y, z relatifs une fois dans une Copie. */
    static final class Piece {
        final int id;               // id d'origine (sert d'id dans le preset)
        final String classe, etat;
        final int x, y, rot;
        final double z;
        /** declencheur / condition / effet / add-on / selecteur / variable ; null = pas un wired */
        final String genre;
        /** reglage du wired (ReglageWired.json) ; null = pas un wired ou pas lu */
        final JSONObject config;
        final List<Liaison> liaisons;

        Piece(int id, String classe, String etat, int x, int y, double z, int rot,
              String genre, JSONObject config, List<Liaison> liaisons) {
            this.id = id; this.classe = classe; this.etat = etat == null ? "0" : etat;
            this.x = x; this.y = y; this.z = z; this.rot = rot & 7;
            this.genre = genre; this.config = config;
            this.liaisons = liaisons == null ? List.of() : List.copyOf(liaisons);
        }

        boolean wired() { return genre != null; }

        Piece decalee(int dx, int dy, double dz) {
            List<Liaison> l = new ArrayList<>();
            for (Liaison b : liaisons)
                l.add(new Liaison(b.furniId, b.etat, b.rot, b.x == null ? null : b.x - dx,
                        b.y == null ? null : b.y - dy, b.alt));
            return new Piece(id, classe, etat, x - dx, y - dy, Math.max(0, arrondi(z - dz)), rot, genre, config, l);
        }

        /** Mobis designes par le reglage (selections 1 et 2, liaisons). */
        Set<Integer> references() {
            Set<Integer> s = new LinkedHashSet<>();
            if (config != null) {
                ajouterInts(s, config.optJSONArray("items"));
                ajouterInts(s, config.optJSONArray("secondItems"));
            }
            for (Liaison b : liaisons) s.add(b.furniId);
            s.remove(id);
            return s;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("classe", classe); o.put("etat", etat);
            o.put("x", x); o.put("y", y); o.put("z", z); o.put("rot", rot);
            if (genre != null) o.put("genre", genre);
            if (config != null) o.put("config", config);
            if (!liaisons.isEmpty()) {
                JSONArray a = new JSONArray();
                for (Liaison b : liaisons) a.put(b.json());
                o.put("liaisons", a);
            }
            return o;
        }

        static Piece depuis(JSONObject o) {
            List<Liaison> l = new ArrayList<>();
            JSONArray a = o.optJSONArray("liaisons");
            if (a != null) for (int i = 0; i < a.length(); i++) l.add(Liaison.depuis(a.getJSONObject(i)));
            return new Piece(o.getInt("id"), o.getString("classe"), o.optString("etat", "0"),
                    o.getInt("x"), o.getInt("y"), o.getDouble("z"), o.getInt("rot"),
                    o.has("genre") ? o.getString("genre") : null,
                    o.has("config") ? o.getJSONObject("config") : null, l);
        }
    }

    /** Une configuration copiee : positions relatives au coin (0,0). */
    static final class Copie {
        final List<Piece> pieces;
        /** Variables de la salle d'origine : id -> nom (le moteur de pose les retrouve par leur nom). */
        final Map<String, String> variables;
        final int salle;
        final long quand;

        Copie(List<Piece> pieces, Map<String, String> variables, int salle, long quand) {
            this.pieces = List.copyOf(pieces);
            this.variables = variables == null ? Map.of() : Map.copyOf(variables);
            this.salle = salle; this.quand = quand;
        }

        int nbWired() { int n = 0; for (Piece p : pieces) if (p.wired()) n++; return n; }
        int largeur() { int m = 0; for (Piece p : pieces) m = Math.max(m, p.x); return pieces.isEmpty() ? 0 : m + 1; }
        int longueur() { int m = 0; for (Piece p : pieces) m = Math.max(m, p.y); return pieces.isEmpty() ? 0 : m + 1; }

        String versJson() {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("salle", salle);
            o.put("quand", quand);
            JSONArray a = new JSONArray();
            for (Piece p : pieces) a.put(p.json());
            o.put("pieces", a);
            o.put("variables", new JSONObject(variables));
            return o.toString(1);
        }

        static Copie depuisJson(String s) {
            JSONObject o = new JSONObject(s);
            List<Piece> l = new ArrayList<>();
            JSONArray a = o.getJSONArray("pieces");
            for (int i = 0; i < a.length(); i++) l.add(Piece.depuis(a.getJSONObject(i)));
            Map<String, String> v = new HashMap<>();
            JSONObject vo = o.optJSONObject("variables");
            if (vo != null) for (String k : vo.keySet()) v.put(k, vo.optString(k, ""));
            return new Copie(l, v, o.optInt("salle", -1), o.optLong("quand", 0));
        }
    }

    // ============================================================ logique pure

    static double arrondi(double z) { return Math.round(z * 100.0) / 100.0; }

    private static void ajouterInts(Collection<Integer> c, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            int v = a.optInt(i, 0);
            if (v > 0) c.add(v);
        }
    }

    /**
     * Le texte d'un wired « instantane » : « id,etat,rot,x,y[,alt];... », N =
     * pas memorise (meme lecture que GPresetExporter.maybeRetrieveBindings).
     * Les morceaux illisibles sont ignores.
     */
    static List<Liaison> lireLiaisons(String texte) {
        List<Liaison> r = new ArrayList<>();
        if (texte == null || texte.isEmpty()) return r;
        for (String morceau : texte.split(";")) {
            String[] f = morceau.split(",");
            if (f.length < 5) continue;
            try {
                int id = Integer.parseInt(f[0].trim());
                String etat = f[1].equals("N") ? null : f[1];
                Integer rot = f[2].equals("N") ? null : Integer.valueOf(f[2].trim());
                boolean pos = !f[3].equals("N") && !f[4].equals("N");
                Integer x = pos ? Integer.valueOf(f[3].trim()) : null;
                Integer y = pos ? Integer.valueOf(f[4].trim()) : null;
                Integer alt = f.length >= 6 && !f[5].equals("N") ? Integer.valueOf(f[5].trim()) : null;
                r.add(new Liaison(id, etat, rot, x, y, alt));
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /**
     * Ramene des pieces aux positions de la salle au coin (0,0) ; altitude
     * comptee depuis le sol le plus bas sous la copie.
     *
     * @param sol hauteur du sol nu d'une case (-1 = inconnue)
     */
    static Copie construire(List<Piece> absolues, Map<String, String> variables, int salle,
                            java.util.function.IntBinaryOperator sol) {
        if (absolues.isEmpty()) return new Copie(List.of(), variables, salle, System.currentTimeMillis());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, solMin = Integer.MAX_VALUE;
        for (Piece p : absolues) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            int h = sol == null ? -1 : sol.applyAsInt(p.x, p.y);
            if (h >= 0) solMin = Math.min(solMin, h);
        }
        if (solMin == Integer.MAX_VALUE) solMin = 0;
        List<Piece> r = new ArrayList<>();
        for (Piece p : absolues) r.add(p.decalee(minX, minY, solMin));
        // du bas vers le haut : le moteur de pose empile dans cet ordre
        r.sort(Comparator.comparingDouble((Piece p) -> p.z).thenComparingInt(p -> p.y).thenComparingInt(p -> p.x));
        return new Copie(r, variables, salle, System.currentTimeMillis());
    }

    /** Ce que la pose va faire, chiffre. */
    static final class Plan {
        final List<Piece> aPoser = new ArrayList<>();
        int wired, lies, autres, sansReglage;
        /** selections vers un mobi de la copie (remappees) / vers un mobi hors copie (perdues) */
        int refsGardees, refsPerdues;
        /** wired dont au moins une selection sera perdue */
        final List<String> wiredTouches = new ArrayList<>();
        Set<Integer> ids() { Set<Integer> s = new HashSet<>(); for (Piece p : aPoser) s.add(p.id); return s; }
    }

    /**
     * Les pieces a poser : tous les wired, les mobis qu'ils designent, et
     * (avecAutres) les autres mobis de la copie.
     */
    static Plan planifier(Copie c, boolean avecAutres) {
        Plan p = new Plan();
        Set<Integer> dansCopie = new HashSet<>(), designes = new HashSet<>();
        for (Piece x : c.pieces) dansCopie.add(x.id);
        for (Piece x : c.pieces) if (x.wired()) designes.addAll(x.references());
        for (Piece x : c.pieces) {
            if (x.wired()) {
                p.aPoser.add(x);
                p.wired++;
                if (x.config == null) p.sansReglage++;
                int perdues = 0;
                for (Integer r : x.references()) {
                    if (dansCopie.contains(r)) p.refsGardees++;
                    else { p.refsPerdues++; perdues++; }
                }
                if (perdues > 0) p.wiredTouches.add(x.classe + " (" + perdues + ")");
            } else if (designes.contains(x.id)) {
                p.aPoser.add(x); p.lies++;
            } else if (avecAutres) {
                p.aPoser.add(x); p.autres++;
            }
        }
        return p;
    }

    /** Liste d'entiers d'un JSON, gardant seulement ceux de « garder ». */
    private static JSONArray filtrer(JSONArray a, Set<Integer> garder) {
        JSONArray r = new JSONArray();
        if (a == null) return r;
        for (int i = 0; i < a.length(); i++) {
            int v = a.optInt(i, 0);
            if (garder.contains(v)) r.put(v);
        }
        return r;
    }

    /** Un reglage a partir de son JSON et de son genre (« declencheur »...). null = genre inconnu. */
    static ReglageWired reglage(String genre, JSONObject o) {
        ReglageWired.Genre g = WiredLecteur.genre(genre);
        return g == null ? null : ReglageWired.depuisJson(g, o);
    }

    /**
     * La copie de la pose : les mobis (id = id d'origine), le reglage de chaque
     * wired (selections reduites aux mobis poses ; la pose les remplace par les
     * nouveaux ids), les liaisons des wired instantanes. z = altitude au-dessus
     * du sol (ancre 0).
     *
     * @param nom nom lisible d'une classe (peut renvoyer la classe)
     */
    static CopieAppart versPreset(Copie c, Plan plan, Function<String, String> nom) {
        Set<Integer> poses = plan.ids();
        CopieAppart cfg = new CopieAppart();
        Set<String> variablesUtiles = new HashSet<>();
        for (Piece p : plan.aPoser) {
            CopieAppart.MobiSol f = new CopieAppart.MobiSol(p.id, p.classe, p.x, p.y, p.z, p.rot, p.etat);
            String n = nom == null ? p.classe : nom.apply(p.classe);
            f.nom = n == null || n.isBlank() ? p.classe : n;
            cfg.sols.add(f);
            if (!p.wired() || p.config == null) continue;
            JSONObject o = new JSONObject(p.config.toString());
            o.put("wiredId", p.id);
            o.put("items", filtrer(o.optJSONArray("items"), poses));
            o.put("secondItems", filtrer(o.optJSONArray("secondItems"), poses));
            if (A_LIAISONS.contains(p.classe)) o.put("config", "");
            JSONArray vids = o.optJSONArray("variableIds");
            if (vids != null) for (int i = 0; i < vids.length(); i++) variablesUtiles.add(vids.optString(i));
            if (o.has("variableId")) variablesUtiles.add(o.optString("variableId"));
            ReglageWired w = reglage(p.genre, o);
            if (w != null) cfg.ajouter(w);
            for (Liaison b : p.liaisons) {
                if (!poses.contains(b.furniId)) continue;
                cfg.liaisons.add(new CopieAppart.Liaison(b.furniId, p.id, b.x == null || b.y == null ? null : b.x,
                        b.x == null || b.y == null ? null : b.y, b.rot, b.etat, b.alt));
            }
        }
        // « variables_map » : nom -> id d'origine (c.variables est id -> nom)
        for (Map.Entry<String, String> e : c.variables.entrySet())
            if (variablesUtiles.contains(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty())
                cfg.tableVariables.put(e.getValue(), e.getKey());
        cfg.ancre = 0.0;       // z = altitude au-dessus du sol
        return cfg;
    }

    /** Un mobi apparu dans la salle apres la pose. */
    static final class Neuf {
        final int id, x, y;
        final String classe;
        final double z;
        Neuf(int id, String classe, int x, int y, double z) { this.id = id; this.classe = classe; this.x = x; this.y = y; this.z = z; }
    }

    /**
     * Retrouve chaque piece posee parmi les nouveaux mobis : meme classe, meme
     * case (racine + position relative), du plus bas au plus haut. Renvoie
     * id d'origine -> nouvel id.
     */
    static Map<Integer, Integer> apparier(List<Piece> poses, int rx, int ry, List<Neuf> neufs) {
        Map<String, List<Piece>> attendus = new HashMap<>();
        for (Piece p : poses)
            attendus.computeIfAbsent(p.classe + "@" + (rx + p.x) + "," + (ry + p.y), k -> new ArrayList<>()).add(p);
        Map<String, List<Neuf>> vus = new HashMap<>();
        for (Neuf n : neufs)
            vus.computeIfAbsent(n.classe + "@" + n.x + "," + n.y, k -> new ArrayList<>()).add(n);
        Map<Integer, Integer> r = new LinkedHashMap<>();
        for (Map.Entry<String, List<Piece>> e : attendus.entrySet()) {
            List<Neuf> l = vus.get(e.getKey());
            if (l == null) continue;
            List<Piece> a = new ArrayList<>(e.getValue());
            a.sort(Comparator.comparingDouble(p -> p.z));
            l.sort(Comparator.comparingDouble(n -> n.z));
            for (int i = 0; i < a.size() && i < l.size(); i++) r.put(a.get(i).id, l.get(i).id);
        }
        return r;
    }

    private static List<Integer> ints(JSONArray a) {
        List<Integer> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optInt(i, 0));
        return r;
    }

    /**
     * Ce qui differe entre le reglage copie et le reglage relu du wired pose.
     * Liste vide = identique. Les selections sont comparees apres remappage
     * (les mobis hors pose ne comptent pas).
     */
    static List<String> ecarts(Piece p, WiredLecteur.Config lu, Map<Integer, Integer> idMap) {
        List<String> r = new ArrayList<>();
        if (p.config == null) return r;
        JSONObject o = p.config;
        if (!A_LIAISONS.contains(p.classe)) {
            String t = o.optString("config", "");
            if (!t.equals(lu.texte)) r.add("texte");
        }
        if (!ints(o.optJSONArray("options")).equals(lu.options)) r.add("options");
        if (o.has("delay") && lu.delai >= 0 && o.optInt("delay") != lu.delai) r.add("délai");
        if (!memeSelection(ints(o.optJSONArray("items")), lu.items, idMap)) r.add("mobis choisis");
        if (!memeSelection(ints(o.optJSONArray("secondItems")), lu.items2, idMap)) r.add("seconde sélection");
        return r;
    }

    private static boolean memeSelection(List<Integer> origine, List<Integer> lus, Map<Integer, Integer> idMap) {
        Set<Integer> attendu = new HashSet<>();
        for (Integer i : origine) { Integer n = idMap.get(i); if (n != null) attendu.add(n); }
        return attendu.equals(new HashSet<>(lus));
    }

    // ===================================================================== etat

    /*
     * Les copies sont NOMMEES, comme les apparts : un dossier « copies-wired »
     * (a cote du dossier des apparts), avec pour chaque copie « nom.json » et
     * son apercu « nom.png ». L'ancienne copie unique (atelier-copie-wired.json)
     * devient la copie « Copie wired » au premier chargement.
     */

    private static volatile boolean enCours = false;
    private static volatile boolean migree = false;
    /** Copie choisie dans la liste de la fenetre (null : la plus recente). */
    private static volatile String choisie = null;
    /** Copies lues, par nom et date du fichier. */
    private static final Map<String, Copie> lues = new java.util.concurrent.ConcurrentHashMap<>();

    /** Le dossier des copies wired (cree au besoin ; migration de l'ancienne copie unique). */
    static synchronized File dossierCopies() {
        File d = OngletApparts.dossierApparts();
        File parent = d.getParentFile() == null ? d : d.getParentFile();
        File dc = new File(parent, "copies-wired");
        if (!migree) {
            migree = true;
            File ancien = new File(parent, "atelier-copie-wired.json");
            try {
                if (!dc.isDirectory()) dc.mkdirs();
                if (ancien.isFile() && dc.isDirectory()) {
                    String n = "Copie wired";
                    for (int i = 2; new File(dc, n + ".json").exists(); i++) n = "Copie wired " + i;
                    Files.move(ancien.toPath(), new File(dc, n + ".json").toPath());
                    Journal.info("Ancienne copie wired reprise sous le nom « " + n + " ».");
                }
            } catch (Throwable t) {
                Journal.debug("Copie wired : migration impossible : " + t);
            }
        }
        return dc;
    }

    static File fichierJson(String nom) { return new File(dossierCopies(), nom + ".json"); }

    static File fichierPng(String nom) { return new File(dossierCopies(), nom + ".png"); }

    /** Les noms des copies, la plus recente d'abord. */
    static List<String> noms() {
        File[] fs = dossierCopies().listFiles((d, n) -> n.endsWith(".json") && !n.startsWith("_atelier"));
        List<File> l = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        l.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<String> r = new ArrayList<>();
        for (File f : l) r.add(f.getName().substring(0, f.getName().length() - 5));
        return r;
    }

    /** La copie « nom » (null si absente ou illisible). */
    static Copie lire(String nom) {
        if (nom == null) return null;
        File f = fichierJson(nom);
        if (!f.isFile()) return null;
        String cle = nom + "@" + f.lastModified();
        Copie c = lues.get(cle);
        if (c != null) return c;
        try {
            c = Copie.depuisJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            lues.put(cle, c);
            return c;
        } catch (Throwable t) {
            Journal.debug("Copie wired « " + nom + " » illisible : " + t);
            return null;
        }
    }

    /** Un nom de fichier sans caracteres interdits. */
    static String nettoyer(String n) {
        if (n == null) return "";
        String r = n.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "-").replaceAll("\\s+", " ").trim();
        while (r.startsWith(".")) r = r.substring(1).trim();
        if (r.length() > 80) r = r.substring(0, 80).trim();
        return r;
    }

    /** Le nom voulu, nettoye, sans ecraser une copie existante (« nom 2 », « nom 3 »...). */
    static String nomLibre(String voulu) {
        String n = nettoyer(voulu);
        if (n.isEmpty()) n = "Copie wired";
        if (!fichierJson(n).exists()) return n;
        for (int i = 2; ; i++) if (!fichierJson(n + " " + i).exists()) return n + " " + i;
    }

    /** Nom propose : la salle et la date (« Ma salle — 4 oct. 10h12 »). */
    static String nomPropose() {
        String salle = null;
        try { salle = NomSalle.nomValide(Salle.gp()); } catch (Throwable ignored) { }
        String quand = new java.text.SimpleDateFormat("d MMM HH'h'mm", Locale.FRANCE).format(new Date());
        String base = salle == null || salle.isBlank() ? "Wired" : salle.trim();
        return nettoyer(base + " — " + quand);
    }

    /** Renomme une copie (et son apercu). null si c'est fait, sinon le message d'erreur. */
    static String renommer(String ancien, String voulu) {
        String n = nettoyer(voulu);
        if (n.isEmpty()) return "Nom vide : la copie garde son nom.";
        if (n.equals(ancien)) return null;
        File src = fichierJson(ancien), dest = fichierJson(n);
        if (!src.isFile()) return "La copie « " + ancien + " » n'existe plus.";
        if (dest.exists()) return "Une copie s'appelle déjà « " + n + " ».";
        if (!src.renameTo(dest)) return "Renommage impossible pour « " + ancien + " ».";
        File png = fichierPng(ancien);
        if (png.isFile() && !png.renameTo(fichierPng(n))) Journal.debug("Aperçu wired non renommé : " + png);
        if (ancien.equals(choisie)) choisie = n;
        return null;
    }

    /** Supprime une copie (et son apercu). */
    static boolean supprimer(String nom) {
        File f = fichierJson(nom);
        if (!f.delete()) return false;
        File png = fichierPng(nom);
        if (png.isFile() && !png.delete()) Journal.debug("Aperçu wired non supprimé : " + png);
        if (nom.equals(choisie)) choisie = null;
        return true;
    }

    /** La copie choisie dans la liste (celle que « Coller » pose). */
    static void choisir(String nom) { choisie = nom; }

    /** La copie choisie, ou la plus recente ; null s'il n'y en a aucune. */
    static String choisie() {
        String c = choisie;
        if (c != null && fichierJson(c).isFile()) return c;
        List<String> l = noms();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Une configuration wired est-elle copiee ? */
    public static boolean aUneCopie() { return choisie() != null; }

    /** Resume de la copie choisie ; null sans copie. */
    public static String resumeCopie() { String n = choisie(); return n == null ? null : resumeCopie(n); }

    /** Nombre de piles : cases differentes occupees par des wired. */
    static int piles(Copie c) {
        Set<Long> cases = new HashSet<>();
        for (Piece p : c.pieces) if (p.wired()) cases.add(((long) p.x << 32) | (p.y & 0xffffffffL));
        return cases.size();
    }

    /** « 12 wired, 3 piles, 6×4 cases · 4 oct. 10:12 » ; « Illisible » si le fichier ne se lit pas. */
    static String resumeCopie(String nom) {
        Copie c = lire(nom);
        if (c == null) return "Illisible";
        long q = c.quand > 0 ? c.quand : fichierJson(nom).lastModified();
        String quand = new java.text.SimpleDateFormat("d MMM HH:mm", Locale.FRANCE).format(new Date(q));
        return Ui.accorder(c.nbWired() + " wired, " + piles(c) + " pile(s), "
                + c.largeur() + "×" + c.longueur() + " cases") + " · " + quand;
    }

    /** Les ids d'origine des mobis d'une copie (pour en reprendre la photo dans la salle d'origine). */
    static List<Integer> idsDe(String nom) {
        Copie c = lire(nom);
        List<Integer> r = new ArrayList<>();
        if (c != null) for (Piece p : c.pieces) r.add(p.id);
        return r;
    }

    // ================================================================== copier

    /**
     * Copie, enregistree sous un nom, puis photo d'apercu. Le suivi se fait
     * dans le panneau de l'onglet (progression, Arreter) ; bilan dans le jeu.
     *
     * @param nom        nom voulu (null ou vide : d'apres la salle et la date) ; jamais d'ecrasement
     * @param avecCibles ajoute les mobis choisis par ces wired (hors wired)
     * @param fin        appele (fil de travail) avec le nom enregistre, si la copie a reussi
     */
    static void copier(Collection<Integer> idsSols, Panneau b, String nom, boolean avecCibles, Consumer<String> fin) {
        if (enCours || PoseCopie.occupee()) { b.refus("Une copie ou un collage est déjà en cours."); return; }
        if (idsSols == null || idsSols.isEmpty()) { b.refus("Aucun mobi à copier : choisis des cases, un calque ou sélectionne des mobis."); return; }
        if (!Salle.dansUneSalle()) { b.refus("Tu n'es pas dans une salle."); return; }
        if (!Salle.furnidataPrete()) { b.refus("Furnidata pas encore chargée."); return; }
        List<Integer> ids = new ArrayList<>(new LinkedHashSet<>(idsSols));
        String voulu = nom == null || nettoyer(nom).isEmpty() ? nomPropose() : nom;
        enCours = true;
        b.demarrer();
        b.travail("Lecture des wired…");
        Salle.tache("wired-copier", () -> {
            String fait = null;
            try { fait = copier0(ids, b, voulu, avecCibles); }
            catch (Throwable t) { b.fin("Copie impossible : " + t); }
            finally { enCours = false; }
            if (fait != null && fin != null) {
                try { fin.accept(fait); } catch (Throwable t) { Journal.debug("Copie wired : suite : " + t); }
            }
        });
    }

    /** Le nom enregistre, ou null si rien n'a ete copie. */
    private static String copier0(List<Integer> ids, Suivi b, String voulu, boolean avecCibles) {
        EtatSalle fs = Salle.etat();
        if (fs == null) { b.fin("Tu n'es pas dans une salle."); return null; }
        int salle = fs.getRoomId();
        List<HFloorItem> mobis = new ArrayList<>();
        List<Integer> wired = new ArrayList<>();
        int absents = 0;
        for (Integer id : ids) {
            HFloorItem it = id == null ? null : Salle.sol(id);
            if (it == null) { absents++; continue; }
            mobis.add(it);
            String c = Salle.classe(it.getTypeId(), false);
            if (Wired.estBoite(c)) wired.add(id);
        }
        if (wired.isEmpty()) { b.fin("Aucun wired parmi ces " + mobis.size() + " mobi(s) : rien à copier."); return null; }

        // 1. les reglages (deja lus et a jour : pas redemandes)
        Map<Integer, WiredLecteur.Config> cfg = WiredLecteur.lireMaintenant(wired, b::arretee,
                (f, t) -> b.progres(f, t, "Lecture des réglages… " + f + " / " + t));
        if (b.arretee()) { b.fin("Arrêté : rien n'a été copié."); return null; }
        if (cfg.isEmpty()) {
            b.fin("Aucun réglage lu : as-tu les droits wired dans cette salle ? Rien n'a été copié.");
            return null;
        }

        // 1 bis. les mobis choisis par ces wired (pas deja copies)
        int cibles = 0;
        if (avecCibles) {
            Set<Integer> deja = new HashSet<>();
            for (HFloorItem it : mobis) deja.add(it.getId());
            for (WiredLecteur.Config c : cfg.values()) {
                List<Integer> l = new ArrayList<>();
                if (c.items != null) l.addAll(c.items);
                if (c.items2 != null) l.addAll(c.items2);
                for (Integer id : l) {
                    if (id == null || !deja.add(id)) continue;
                    HFloorItem it = Salle.sol(id);
                    if (it == null || Wired.estBoite(Salle.classe(it.getTypeId(), false))) continue;
                    mobis.add(it);
                    cibles++;
                }
            }
            Journal.debug("Copie wired : " + cibles + " mobi(s) choisi(s) par les wired ajouté(s).");
        }

        // 2. les pieces
        List<Piece> pieces = new ArrayList<>();
        int sansReglage = 0;
        for (HFloorItem it : mobis) {
            String cls = Salle.classe(it.getTypeId(), false);
            if (cls == null) continue;
            String genre = null;
            JSONObject json = null;
            List<Liaison> liaisons = List.of();
            if (Wired.estBoite(cls)) {
                WiredLecteur.Config c = cfg.get(it.getId());
                genre = c != null ? c.genre : WiredLecteur.genreDe(cls);
                if (c != null && c.brut != null) {
                    try {
                        json = c.brut.json();
                        if (A_LIAISONS.contains(cls)) liaisons = lireLiaisons(c.texte);
                    } catch (Throwable t) { json = null; }
                }
                if (json == null) sansReglage++;
            }
            pieces.add(new Piece(it.getId(), cls, Generateur.etatDe(it), it.getTile().getX(), it.getTile().getY(),
                    it.getTile().getZ(), Salle.rotation(it), genre, json, liaisons));
        }
        Map<String, String> vars = WiredLecteur.variables();
        Copie c = construire(pieces, vars == null ? Map.of() : vars, salle, Salle::hauteurSol);

        // 3. enregistrer sous son nom et verifier la relecture du fichier
        String nom = nomLibre(voulu);
        File f = fichierJson(nom);
        try {
            ecrireAtomique(f, c.versJson().getBytes(StandardCharsets.UTF_8));
            Copie relue = Copie.depuisJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (relue.pieces.size() != c.pieces.size()) {
                b.fin("Le fichier de la copie « " + nom + " » relu ne correspond pas : recommence la copie.");
                return null;
            }
        } catch (Throwable t) {
            b.fin("Copie non enregistrée : " + t.getMessage());
            return null;
        }
        choisie = nom;

        // 4. la photo, decoupee autour des mobis copies
        b.travail("Photo de l'aperçu…");
        List<Integer> photo = new ArrayList<>();
        for (HFloorItem it : mobis) photo.add(it.getId());
        String errPhoto = ApercuMobis.prendre(fichierPng(nom), photo);
        if (errPhoto != null) Journal.debug("Aperçu wired : " + errPhoto);

        Plan p = planifier(c, false);
        b.bilan(true, "Copie « " + nom + " » enregistrée : " + c.nbWired() + " wired en " + piles(c) + " pile(s)"
                + (sansReglage > 0 ? " dont " + sansReglage + " sans réglage lu (ils seront posés vides)" : "")
                + ", " + p.lies + " mobi(s) qu'ils utilisent, " + (c.pieces.size() - c.nbWired() - p.lies)
                + " autre(s) mobi(s). Zone de " + c.largeur() + "×" + c.longueur() + " cases."
                + (p.refsPerdues > 0 ? " " + p.refsPerdues + " sélection(s) vers des mobis hors copie ne suivront pas." : "")
                + (absents > 0 ? " " + absents + " mobi(s) introuvable(s) dans la salle ignoré(s)." : "")
                + (errPhoto != null ? " Pas d'aperçu : " + errPhoto : ""));
        return nom;
    }

    // =================================================================== coller

    /**
     * Colle la copie « nom » : aperçu chiffre dans le panneau de l'onglet,
     * Confirmer, puis clic dans le jeu sur la case du coin haut-gauche.
     *
     * @param origine case du coin haut-gauche (x min, y min) ; null = cliquee dans le jeu
     */
    static void collerDans(String nom, HPoint origine, Panneau b) {
        Copie c = lire(nom);
        if (c == null || c.nbWired() == 0) { b.refus("Rien à coller : copie d'abord une config wired."); return; }
        if (!pretAColler(b)) return;
        b.apercu(c, "Coller « " + nom + " »", resumeCopie(nom) + ".", false,
                origine == null ? "Après Confirmer, clique dans le jeu la case du coin haut-gauche."
                        : "À partir de (" + origine.getX() + "," + origine.getY() + ").",
                (avec, source) -> lancer(b, c, origine, avec, source, "la case du coin haut-gauche", null));
    }

    /** Peut-on lancer un collage ? Sinon le dit dans le jeu. */
    private static boolean pretAColler(Suivi b) {
        if (enCours || PoseCopie.occupee()) { b.fin("Une copie ou un collage est déjà en cours."); return false; }
        if (Salle.gp() == null) { b.fin("L'Atelier n'est pas encore prêt."); return false; }
        if (!Salle.dansUneSalle()) { b.fin("Tu n'es pas dans une salle."); return false; }
        if (!Salle.furnidataPrete()) { b.fin("Furnidata pas encore chargée."); return false; }
        return true;
    }

    // ================================================ coller une copie en memoire

    /** Une copie ou un collage wired est-il en cours (aperçu confirme, clic attendu, pose) ? */
    static boolean occupe() { return enCours || PoseCopie.occupee(); }

    /**
     * Colle une copie en memoire (pas une copie nommee du dossier) : aperçu dans
     * le panneau, Confirmer, clic dans le jeu (origine null), pose.
     *
     * @param resume     ce que fait la copie (aperçu) ; null = aucun
     * @param avecAutres « Poser aussi les autres mobis copiés » coche au depart
     * @param consigne   la case a cliquer (« la case de départ de la ligne ») ; null = le coin haut-gauche
     * @param auClic     transforme la copie (reduite aux pieces a poser) une fois la case connue
     *                   (cliquee ou donnee), juste avant la pose ; null = telle quelle
     */
    static void collerDans(Copie c, String titre, String resume, HPoint origine, Panneau b, boolean avecAutres, String consigne,
                           java.util.function.BiFunction<Copie, HPoint, Copie> auClic) {
        String t = titre == null || titre.isBlank() ? "Coller la config wired" : titre;
        if (c == null || c.nbWired() == 0) { b.refus("Rien à coller : la copie n'a aucun wired."); return; }
        if (enCours || PoseCopie.occupee()) { b.refus("Une copie ou un collage est déjà en cours."); return; }
        if (Salle.gp() == null) { b.refus("L'Atelier n'est pas encore prêt."); return; }
        if (!Salle.dansUneSalle()) { b.refus("Tu n'es pas dans une salle."); return; }
        if (!Salle.furnidataPrete()) { b.refus("Furnidata pas encore chargée."); return; }
        String cons = consigne == null || consigne.isBlank() ? "la case du coin haut-gauche" : consigne;
        b.apercu(c, t, resume, avecAutres,
                origine == null ? "Après Confirmer, clique dans le jeu " + cons + "."
                        : "À partir de (" + origine.getX() + "," + origine.getY() + ").",
                (avec, source) -> lancer(b, c, origine, avec, source, cons, auClic));
    }

    /**
     * Apres Confirmer : la case (cliquee dans le jeu si origine est null, avec
     * l'empreinte noire), la transformation auClic, puis la pose. Fil JavaFX.
     */
    private static void lancer(Panneau b, Copie c, HPoint origine, boolean avec, Generateur.Source s, String consigne,
                               java.util.function.BiFunction<Copie, HPoint, Copie> auClic) {
        if (enCours || PoseCopie.occupee()) { b.texte("Une copie ou un collage est déjà en cours."); return; }
        enCours = true;
        b.travail("Préparation de la pose…");
        Salle.tache("wired-coller", () -> {
            try {
                int salle = Salle.salleId();
                HPoint coin = origine;
                if (coin == null) {
                    b.travail("Dans le jeu : clique " + consigne + ". Ton avatar ne bougera pas.");
                    InfoJeu.consigne(Ui.majuscule("Clique dans le jeu " + consigne + "."));
                    Empreinte.Boite emp = new Empreinte.Boite();
                    for (Piece p : c.pieces) if (avec || p.genre != null) emp.sol(p.classe, p.x, p.y, p.rot);
                    coin = Generateur.Dalle.attendreClic(120_000, emp.largeur(), emp.profondeur());
                    if (b.arretee()) { b.fin("Arrêté avant la pose : rien n'a été posé."); return; }
                    if (coin == null) { b.fin("Pas de clic dans le jeu en 2 minutes : collage annulé, rien n'a été posé."); return; }
                    if (Salle.salleId() != salle) { b.fin("Tu as changé de salle : collage annulé, rien n'a été posé."); return; }
                }
                Copie aPoser = restreinte(c, planifier(c, avec));
                if (auClic != null) aPoser = auClic.apply(aPoser, coin);
                if (aPoser == null || aPoser.pieces.isEmpty()) { b.fin("Rien à poser."); return; }
                coller0(aPoser, planifier(aPoser, true), s, coin, b);
            }
            catch (Throwable t) { b.fin("Collage impossible : " + t); }
            finally { enCours = false; }
        });
    }

    /** La copie reduite aux pieces de ce plan (memes variables, salle, date). */
    static Copie restreinte(Copie c, Plan p) {
        Set<Integer> ids = p.ids();
        List<Piece> l = new ArrayList<>();
        for (Piece x : c.pieces) if (ids.contains(x.id)) l.add(x);
        return new Copie(l, c.variables, c.salle, c.quand);
    }

    // ====================================================== copier-coller de calques

    /**
     * Pour Ctrl+C sur un calque : TOUS les mobis de sol donnes, positions
     * relatives au coin, reglage des wired lu s'il y en a (sans toucher a la
     * copie wired ci-dessus). Fil de travail (la lecture des wired attend le
     * serveur). null hors salle ou si aucun mobi n'est dans la salle.
     */
    static Copie capturer(Collection<Integer> idsSols, java.util.function.BooleanSupplier stop) {
        EtatSalle fs = Salle.etat();
        if (fs == null || idsSols == null) return null;
        List<HFloorItem> mobis = new ArrayList<>();
        List<Integer> wired = new ArrayList<>();
        for (Integer id : idsSols) {
            HFloorItem it = id == null ? null : Salle.sol(id);
            if (it == null) continue;
            mobis.add(it);
            if (Wired.estBoite(Salle.classe(it.getTypeId(), false))) wired.add(id);
        }
        if (mobis.isEmpty()) return null;
        Map<Integer, WiredLecteur.Config> cfg = wired.isEmpty() ? Map.of()
                : WiredLecteur.lireMaintenant(wired, stop == null ? () -> false : stop, (f, t) -> { });
        // parti pendant la lecture : hauteurs du sol et classes ne seraient plus les bonnes
        EtatSalle apres = Salle.etat();
        if (apres == null || apres.getRoomId() != fs.getRoomId()) return null;
        List<Piece> pieces = new ArrayList<>();
        for (HFloorItem it : mobis) {
            String cls = Salle.classe(it.getTypeId(), false);
            if (cls == null) continue;
            String genre = null;
            JSONObject json = null;
            List<Liaison> liaisons = List.of();
            if (Wired.estBoite(cls)) {
                WiredLecteur.Config c = cfg.get(it.getId());
                genre = c != null ? c.genre : WiredLecteur.genreDe(cls);
                if (c != null && c.brut != null) {
                    try {
                        json = c.brut.json();
                        if (A_LIAISONS.contains(cls)) liaisons = lireLiaisons(c.texte);
                    } catch (Throwable t) { json = null; }
                }
            }
            pieces.add(new Piece(it.getId(), cls, Generateur.etatDe(it), it.getTile().getX(), it.getTile().getY(),
                    it.getTile().getZ(), Salle.rotation(it), genre, json, liaisons));
        }
        Map<String, String> vars = wired.isEmpty() ? null : WiredLecteur.variables();
        return construire(pieces, vars == null ? Map.of() : vars, fs.getRoomId(), Salle::hauteurSol);
    }

    /**
     * Ctrl+V d'un calque dans un AUTRE appart : la copie entiere (wired avec
     * leur reglage, mobis lies, autres mobis) est posee par le moteur
     * de pose, comme le collage wired, apres un clic sur la case du coin
     * haut-gauche dans le jeu (ce clic vaut confirmation ; Ctrl+Z annule).
     * Source : l'inventaire, comme Dupliquer (jamais d'achat sans le dire).
     *
     * @param fin appele (fil de travail) avec les ids des mobis poses (vide si rien)
     */
    static void collerCalque(Copie c, String titre, javafx.stage.Window parent, Consumer<List<Integer>> fin) {
        Suivi b = new Discret();
        Consumer<List<Integer>> f = fin == null ? l -> { } : fin;
        if (c == null || c.pieces.isEmpty()) { b.fin("Rien à coller."); f.accept(List.of()); return; }
        if (enCours) { b.fin("Une copie ou un collage est déjà en cours."); f.accept(List.of()); return; }
        if (Salle.gp() == null) { b.fin("L'Atelier n'est pas encore prêt."); f.accept(List.of()); return; }
        if (!Salle.dansUneSalle()) { b.fin("Tu n'es pas dans une salle."); f.accept(List.of()); return; }
        if (!Salle.furnidataPrete()) { b.fin("Furnidata pas encore chargée."); f.accept(List.of()); return; }
        Plan p = planifier(c, true);
        enCours = true;
        InfoJeu.consigne(Ui.majuscule(Ui.accorder(p.aPoser.size() + " mobi(s) à poser depuis ton inventaire, dont "
                + c.nbWired() + " wired avec leur réglage.")));
        Salle.tache("calques-coller-ailleurs", () -> {
            List<Integer> poses = List.of();
            try { poses = coller0(c, p, Generateur.Source.INVENTAIRE, null, b); }
            catch (Throwable t) { b.fin("Collage impossible : " + t); }
            finally { enCours = false; }
            try { f.accept(poses); } catch (Throwable t) { System.err.println("[Atelier] coller calque : " + t); }
        });
    }

    /**
     * Pour Dupliquer (calques) avec les wired : les mobis de sol donnes
     * (wired et autres) sont recopies A LEUR PLACE par l'importeur, wired
     * avec leur reglage et leurs selections remappees vers la copie. Fenetre
     * de suivi comme le collage. Fil de travail. Ids des mobis poses.
     */
    static List<Integer> collerSurPlace(Collection<Integer> idsSols, Generateur.Source source,
                                        java.util.function.BooleanSupplier stop) {
        Copie c = capturer(idsSols, stop);
        if (c == null || c.pieces.isEmpty()) return List.of();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Integer id : idsSols) {
            HFloorItem it = id == null ? null : Salle.sol(id);
            if (it == null) continue;
            minX = Math.min(minX, it.getTile().getX()); minY = Math.min(minY, it.getTile().getY());
        }
        if (minX == Integer.MAX_VALUE) return List.of();
        if (enCours) return List.of();
        Suivi b = new Discret();
        enCours = true;
        try { return coller0(c, planifier(c, true), source, new HPoint(minX, minY), b); }
        catch (Throwable t) { b.fin("Collage impossible : " + t); return List.of(); }
        finally { enCours = false; }
    }

    /** Disponibilite par classe : inventaire et BC. Hors fil JavaFX de preference (rapide). */
    private static String disponibilite(Moteur gp, Plan p, Generateur.Source source) {
        Map<String, Integer> besoin = new TreeMap<>();
        for (Piece x : p.aPoser) besoin.merge(x.classe, 1, Integer::sum);
        int manqueInv = 0, manqueBc = 0, inconnus = 0;
        List<String> manquants = new ArrayList<>();
        Furnidata fd = gp.getFurniDataTools();
        Inventaire inv = null;
        try { inv = gp.getInventory(); } catch (Throwable ignored) { }
        boolean invPret = false;
        try { invPret = inv != null && inv.getState() == Inventaire.Etat.LOADED; } catch (Throwable ignored) { }
        for (Map.Entry<String, Integer> e : besoin.entrySet()) {
            Integer type = null;
            try { type = fd.getFloorTypeId(e.getKey()); } catch (Throwable ignored) { }
            if (type == null) { inconnus += e.getValue(); manquants.add(e.getKey() + " (inconnu)"); continue; }
            int enInv = 0;
            if (invPret) try {
                List<gearth.extensions.parsers.HInventoryItem> l = inv.getFloorItemsByType(type);
                enInv = l == null ? 0 : l.size();
            } catch (Throwable ignored) { }
            boolean bc = false;
            try { bc = OffresBc.sol(gp.getCatalog(), fd, e.getKey()) != null; }     // comme la pose (OffresBc)
            catch (Throwable ignored) { }
            int manque = Math.max(0, e.getValue() - enInv);
            if (manque > 0) manqueInv += manque;
            if (!bc) manqueBc += e.getValue();
            boolean ok;
            switch (source) {
                case BC: ok = bc; break;
                case INVENTAIRE: ok = manque == 0; break;
                default: ok = manque == 0 || bc;
            }
            if (!ok) manquants.add(e.getKey() + " ×" + (source == Generateur.Source.BC ? e.getValue() : manque));
        }
        StringBuilder s = new StringBuilder();
        if (!invPret) s.append("Inventaire pas encore chargé : ouvre-le une fois dans le jeu. ");
        else s.append("Manquent dans l'inventaire : ").append(manqueInv).append(". ");
        s.append("Absents du BC : ").append(manqueBc).append(". ");
        if (inconnus > 0) s.append(inconnus).append(" de classe inconnue. ");
        if (manquants.isEmpty()) s.append("Avec cette source, tout peut être posé.");
        else s.append("Avec cette source, il manquera : ")
                .append(String.join(", ", manquants.subList(0, Math.min(8, manquants.size()))))
                .append(manquants.size() > 8 ? "…" : "").append(".");
        return s.toString();
    }

    private static String nomLisible(Moteur gp, String classe) {
        try {
            Furnidata.Mobi d = gp.getFurniDataTools().getFloorItemDetails(classe);
            if (d != null && d.name != null && !d.name.isBlank()) return d.name;
        } catch (Throwable ignored) { }
        return classe;
    }

    /** La pose elle-meme (fil de travail). */
    private static List<Integer> coller0(Copie c, Plan plan, Generateur.Source source, HPoint origine, Suivi b) throws Exception {
        Moteur gp = Salle.gp();
        EtatSalle fs = Salle.etat();
        if (gp == null || fs == null) { b.fin("Tu n'es plus dans une salle."); return List.of(); }
        int salle = fs.getRoomId();
        if (PoseCopie.occupee()) {
            b.fin("L'Atelier est déjà en train de poser : attends la fin, ou tape :abort dans le jeu.");
            return List.of();
        }
        Furnidata fd = gp.getFurniDataTools();
        for (Piece p : plan.aPoser)
            if (fd.getFloorTypeId(p.classe) == null) { b.fin("« " + p.classe + " » inconnu de la furnidata : collage annulé."); return List.of(); }

        // 1. la copie a poser (aller-retour JSON : exactement ce qu'une copie relue donnerait)
        CopieAppart relu = CopieAppart.lire(versPreset(c, plan, cl -> nomLisible(gp, cl)).json());

        // 2. la case du coin
        HPoint racine = origine;
        if (racine == null) {
            b.travail("Dans le jeu : clique la case du coin haut-gauche (x min, y min) de la zone de destination. "
                    + "Ton avatar ne bougera pas.");
            InfoJeu.consigne("Clique dans le jeu la case du coin haut-gauche où coller.");
            Empreinte.Boite emp = new Empreinte.Boite();
            for (Piece p : plan.aPoser) emp.sol(p.classe, p.x, p.y, p.rot);
            racine = Generateur.Dalle.attendreClic(120_000, emp.largeur(), emp.profondeur());
            if (b.arretee()) { b.fin("Arrêté avant la pose : rien n'a été posé."); return List.of(); }
            if (racine == null) { b.fin("Pas de clic dans le jeu en 2 minutes : collage annulé, rien n'a été posé."); return List.of(); }
        }
        // la salle a pu changer pendant l'attente du clic (ou depuis l'aperçu)
        if (Salle.salleId() != salle) { b.fin("Tu as changé de salle : collage annulé, rien n'a été posé."); return List.of(); }

        // 3. la pose avec la dalle magique (hauteurs exactes), comme Dupliquer, puis les reglages
        List<Generateur.Mobi> relatifs = new ArrayList<>();
        Map<Integer, Integer> attendus = new HashMap<>();
        for (Piece p : plan.aPoser) {
            relatifs.add(new Generateur.Mobi(p.classe, p.etat, p.x, p.y, p.z, p.rot));
            attendus.merge(fd.getFloorTypeId(p.classe), 1, Integer::sum);
        }
        Consumer<String> dire = b::texte;
        List<int[]> trace = Generateur.Dalle.trace(relatifs, 0, 0, racine);
        int[] depart = new int[]{racine.getX() + relatifs.get(0).x, racine.getY() + relatifs.get(0).y};
        Set<Integer> avant = new HashSet<>();
        for (HFloorItem it : Salle.sols()) avant.add(it.getId());
        Generateur.Dalle.Pret dalle = Generateur.Dalle.preparer(relatifs, trace, depart, dire);
        if (dalle == null) { b.fin("Collage annulé (dalle magique) : rien n'a été posé."); return List.of(); }
        // la dalle magique posee par la pose : son type ne compte pas parmi les nouveaux
        Set<Integer> typesDalles = Generateur.Dalle.typesDalles();

        int voulus = plan.aPoser.size();
        final HPoint coin = racine;
        b.travail(voulus + (voulus > 1 ? " mobis à poser avec la dalle magique." : " mobi à poser avec la dalle magique."));
        // une seule table des ids (copie -> reel) : celle de la pose, completee ici par
        // l'appariement AVANT l'envoi des reglages ; la verification se sert de la meme
        Consumer<Map<Integer, Integer>> completer = ids -> {
            PoseDirecte.suivre(() -> voulus - nouveaux(avant, attendus, typesDalles).size(), 500, 2500);
            int n = completerIds(ids, plan.aPoser, coin, avant, attendus, typesDalles);
            if (n > 0) Journal.debug("Collage wired : " + n + " mobi(s) apparu(s) en retard ajouté(s) à la table des ids.");
        };
        PoseCopie.Resultat pr = Generateur.poserCopie(relu, source, coin, dire, dalle.ou, b::arretee, () -> {
            int n = nouveaux(avant, attendus, typesDalles).size();
            b.progres(Math.min(n, voulus), voulus, "L'Atelier pose et règle : " + Math.min(n, voulus) + " / " + voulus
                    + (n >= voulus && PoseCopie.occupee() ? " (réglage des wired…)" : ""));
        }, completer);
        if (pr == null || !pr.lancee) {
            b.fin("La pose n'a pas démarré" + (pr != null && pr.raison != null ? " (" + pr.raison + ")" : "") + ". Rien n'a été posé.");
            return List.of();
        }
        EtatSalle apresPose = Salle.etat();
        if (apresPose == null || apresPose.getRoomId() != salle) {
            b.fin("Tu as quitté la salle pendant la pose : collage interrompu.");
            return List.of();
        }
        boolean arrete = pr.arrete;
        // suivi : seulement le temps que les derniers mobis apparaissent (au lieu
        // de 3,5 s fixes) ; la dalle de la pose ne compte pas (types des dalles)
        long finImport = System.currentTimeMillis();
        PoseDirecte.suivre(() -> voulus - nouveaux(avant, attendus, typesDalles).size(), 800, 3500);

        // 5. verifier : la table de la pose (completee avant les reglages), relecture forcee
        Set<Integer> dansPlan = plan.ids();
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : pr.ids.entrySet())
            if (dansPlan.contains(e.getKey())) idMap.put(e.getKey(), e.getValue());
        // apparus apres les reglages : comptes poses, mais sans reglage (hors verification)
        Map<Integer, Integer> tard = new LinkedHashMap<>(idMap);
        int enRetard = completerIds(tard, plan.aPoser, racine, avant, attendus, typesDalles);
        int poses = tard.size();
        ReglageWiredPose.Bilan rb = pr.wired;
        Set<Integer> nonAppliques = rb == null ? Set.of() : rb.nonAppliques();
        List<Integer> aRelire = new ArrayList<>();
        Map<Integer, Piece> parNouvelId = new HashMap<>();
        for (Piece p : plan.aPoser) {
            Integer n = idMap.get(p.id);
            if (n != null && p.wired() && p.config != null && !nonAppliques.contains(p.id)) { aRelire.add(n); parNouvelId.put(n, p); }
        }
        int conformes = 0, differents = 0, nonLus = 0;
        List<String> details = new ArrayList<>();
        if (!aRelire.isEmpty() && !arrete) {
            b.travail("Vérification : relecture des wired posés…");
            long reste = 800 - (System.currentTimeMillis() - finImport);   // le serveur applique les derniers reglages
            if (reste > 0) Salle.sommeil(reste);
            Map<Integer, WiredLecteur.Config> lus = WiredLecteur.lireMaintenant(aRelire, b::arretee,
                    (f, t) -> b.progres(f, t, "Vérification des réglages… " + f + " / " + t), true);
            for (Integer n : aRelire) {
                WiredLecteur.Config lu = lus.get(n);
                Piece p = parNouvelId.get(n);
                if (lu == null) { nonLus++; details.add("• " + nomLisible(gp, p.classe) + " : pas relu."); continue; }
                List<String> e = ecarts(p, lu, idMap);
                if (e.isEmpty()) conformes++;
                else { differents++; details.add("• " + nomLisible(gp, p.classe) + " : écart sur " + String.join(", ", e) + "."); }
            }
        }
        List<String> problemes = rb == null ? List.of() : rb.details();
        int manquants = Math.max(0, voulus - poses);
        StringBuilder bilan = new StringBuilder();
        bilan.append(arrete ? "Arrêté. " : "").append(poses).append(" / ").append(voulus).append(" mobi(s) posé(s) en (")
                .append(racine.getX()).append(",").append(racine.getY()).append(").");
        if (manquants > 0) bilan.append(" ").append(manquants).append(" manquant(s) (inventaire / BC ? case refusée ?).");
        if (enRetard > 0) bilan.append(" ").append(enRetard).append(" apparu(s) après les réglages, non réglé(s).");
        if (rb != null && rb.attendus > 0) bilan.append(" ").append(rb.texte());
        if (!aRelire.isEmpty()) bilan.append(" Réglages vérifiés : ").append(conformes).append(" identique(s)")
                .append(differents > 0 ? ", " + differents + " différent(s)" : "")
                .append(nonLus > 0 ? ", " + nonLus + " non relu(s)" : "").append(".");
        if (plan.refsPerdues > 0) bilan.append(" ").append(plan.refsPerdues).append(" sélection(s) vers des mobis hors copie non reprise(s).");
        for (String l : problemes) bilan.append("\n• ").append(l);
        for (String l : details) bilan.append("\n").append(l);
        boolean ok = arrete || (manquants == 0 && enRetard == 0 && differents == 0 && nonLus == 0 && problemes.isEmpty());
        b.bilan(ok, bilan.toString());
        return new ArrayList<>(tard.values());
    }

    /** Ecrit dans un .tmp puis le met en place d'un coup : jamais de fichier tronque. */
    private static void ecrireAtomique(File f, byte[] contenu) throws java.io.IOException {
        java.nio.file.Path tmp = new File(f.getParentFile(), f.getName() + ".tmp").toPath();
        Files.write(tmp, contenu);
        try {
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Complete la table des ids (copie -> reel) par l'appariement des nouveaux
     * mobis (apparier) : seulement les pieces absentes de la table, et jamais un
     * id reel deja pris. Rend le nombre d'ids ajoutes.
     */
    private static int completerIds(Map<Integer, Integer> ids, List<Piece> aPoser, HPoint racine, Set<Integer> avant,
                                    Map<Integer, Integer> attendus, Set<Integer> dalles) {
        List<Neuf> neufs = new ArrayList<>();
        for (Integer id : nouveaux(avant, attendus, dalles)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            neufs.add(new Neuf(id, Salle.classe(it.getTypeId(), false), it.getTile().getX(), it.getTile().getY(), it.getTile().getZ()));
        }
        Set<Integer> pris = new HashSet<>(ids.values());
        int n = 0;
        for (Map.Entry<Integer, Integer> e : apparier(aPoser, racine.getX(), racine.getY(), neufs).entrySet()) {
            if (ids.containsKey(e.getKey()) || pris.contains(e.getValue())) continue;
            ids.put(e.getKey(), e.getValue());
            pris.add(e.getValue());
            n++;
        }
        return n;
    }

    /**
     * Nouveaux mobis de sol des types attendus (au plus le nombre attendu par type).
     * Une dalle magique apparue est celle de la pose tant que la copie n'en demande pas.
     */
    private static List<Integer> nouveaux(Set<Integer> avant, Map<Integer, Integer> attendus, Set<Integer> dalles) {
        Map<Integer, Integer> reste = new HashMap<>(attendus);
        List<Integer> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (avant.contains(it.getId())) continue;
            if (dalles.contains(it.getTypeId()) && !attendus.containsKey(it.getTypeId())) continue;
            Integer k = reste.get(it.getTypeId());
            if (k == null || k <= 0) continue;
            reste.put(it.getTypeId(), k - 1);
            r.add(it.getId());
        }
        return r;
    }

    // ================================================================== suivi

    /**
     * Le suivi d'une copie ou d'un collage : progression, arret, bilan. Plus
     * aucune fenetre a part : soit le Panneau de l'onglet de l'outil (Copier /
     * coller la config, Config troc), soit rien a l'ecran (Discret : collage
     * d'un calque, Dupliquer avec les wired). Le bilan part toujours en message
     * dans le jeu, une fois.
     */
    interface Suivi {
        boolean arretee();
        /** Ligne d'etat (phase en cours). */
        void texte(String s);
        /** Phase sans fin connue : barre qui tourne. */
        void travail(String s);
        void progres(int fait, int total, String s);
        /** Fin sur un refus ou un echec. */
        default void fin(String s) { bilan(false, s); }
        /** Fin d'operation : message dans le jeu (succes ou erreur), la progression disparait. */
        void bilan(boolean ok, String s);
    }

    /** Message de fin dans le jeu ; un arret voulu reste en console. */
    static void direBilan(boolean ok, String s) {
        String t = Ui.majuscule(Ui.accorder(s));
        if (t.startsWith("Arrêté")) Journal.info("wired (copier/coller) : " + t);
        else if (ok) Journal.succes(t);
        else Journal.erreur(t);
    }

    /** Suivi sans rien a l'ecran : seul le bilan, en message dans le jeu. */
    static final class Discret implements Suivi {
        @Override public boolean arretee() { return false; }
        @Override public void texte(String s) { }
        @Override public void travail(String s) { }
        @Override public void progres(int fait, int total, String s) { }
        @Override public void bilan(boolean ok, String s) { direBilan(ok, s); }
    }

    /**
     * Le suivi DANS l'onglet de l'outil, comme OutilHauteur : un aperçu en
     * texte (resume, plan chiffre, source des mobis, « poser aussi les autres
     * mobis ») avec Annuler / Confirmer, puis une barre de progression avec
     * Arreter et une ligne d'etat. Le bilan part en message dans le jeu.
     * Un panneau par onglet ; noeud() se range ou l'on veut dans l'onglet.
     */
    static final class Panneau implements Suivi {
        private final VBox racine = new VBox(8);
        private final VBox apercu = new VBox(6);
        private final Label titre = new Label(), resume = new Label(), plan = new Label(), dispo = new Label();
        private final ChoiceBox<String> source = new ChoiceBox<>();
        private final CheckBox autres = new CheckBox("Poser aussi les autres mobis copiés");
        private final Button confirmer = new Button("Confirmer"), annuler = new Button("Annuler");
        private final ProgressBar barre = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        private final Button arreter = Icones.sur(new Button("Arrêter"), Icones.ARRET);
        private final HBox ligneProgres;
        private final Label etat = Ui.etat();
        private volatile boolean stop = false, actif = false;
        /** Ce que Confirmer lance (fil JavaFX), selon l'aperçu montre. */
        private Runnable surConfirmer = () -> { };
        /** Appele (fil JavaFX) a la fin de chaque operation (bilan). */
        private Runnable surFin = () -> { };
        /** Appele (fil JavaFX) quand l'aperçu est ferme par Annuler. */
        private Runnable surAnnuler = () -> { };
        /** Source des mobis choisie a chaque aperçu (index de la liste). */
        private int sourceDefaut = 0;
        /** Remplace le message de fin dans le jeu (fil de travail ou JavaFX) ; null = direBilan. */
        private volatile java.util.function.BiConsumer<Boolean, String> surBilan = null;
        /** Aperçu court (petite fenetre) : sans titre, plan en une phrase, disponibilite seulement s'il manque quelque chose. */
        private boolean court = false;

        Panneau() {
            titre.setStyle("-fx-font-weight: bold;");
            for (Label l : new Label[]{titre, resume, plan, dispo}) {
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                l.setMinHeight(Region.USE_PREF_SIZE);
            }
            dispo.setStyle("-fx-opacity: 0.75;");
            source.getItems().addAll("Inventaire seul", "Inventaire, puis BC", "BC, puis inventaire", "BC seul");
            source.getSelectionModel().select(0);
            autres.setWrapText(true);
            confirmer.getStyleClass().add("primaire");
            confirmer.setOnAction(e -> surConfirmer.run());
            annuler.setOnAction(e -> {
                fermerApercu();
                try { surAnnuler.run(); } catch (Throwable t) { Journal.debug("Suivi wired : annuler : " + t); }
            });
            HBox boutons = new HBox(8, annuler, confirmer);
            boutons.setAlignment(Pos.CENTER_RIGHT);
            HBox ligneSource = new HBox(8, Ui.etiquette("Source des mobis"), source);
            ligneSource.setAlignment(Pos.CENTER_LEFT);
            apercu.getChildren().setAll(titre, resume, plan, ligneSource, autres, dispo, boutons);
            apercu.setPadding(new Insets(8, 10, 8, 10));
            apercu.setStyle("-fx-background-color: rgba(127,127,127,0.10); -fx-background-radius: 6;");
            montrer(apercu, false);

            barre.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(barre, Priority.ALWAYS);
            arreter.setMinWidth(Region.USE_PREF_SIZE);
            arreter.setOnAction(e -> { stop = true; texte("Arrêt demandé…"); });
            ligneProgres = new HBox(8, barre, arreter);
            ligneProgres.setAlignment(Pos.CENTER_LEFT);
            montrer(ligneProgres, false);
            etat.setWrapText(true);

            racine.getChildren().setAll(apercu, ligneProgres, etat);
            racine.setFillWidth(true);
        }

        /** Le noeud a ranger dans l'onglet. */
        Region noeud() { return racine; }

        /** Source proposee a chaque aperçu : 0 inventaire seul, 1 inventaire puis BC, 2 BC puis inventaire, 3 BC seul. */
        Panneau sourceDefaut(int i) { sourceDefaut = Math.max(0, Math.min(3, i)); return this; }

        /** A la fin de chaque operation (fil JavaFX). */
        Panneau surFin(Runnable r) { surFin = r == null ? () -> { } : r; return this; }

        /**
         * Le bilan de fin n'est plus dit tel quel dans le jeu : il est donne a r
         * (ok, texte), qui dit lui-meme un seul message. Les refus d'avant
         * l'operation (refus) restent dits dans le jeu.
         */
        Panneau surBilan(java.util.function.BiConsumer<Boolean, String> r) { surBilan = r; return this; }

        /** Quand l'aperçu est ferme par Annuler (fil JavaFX). */
        Panneau surAnnuler(Runnable r) { surAnnuler = r == null ? () -> { } : r; return this; }

        /** Aperçu court, pour une petite fenetre (le titre est celui de la fenetre). */
        Panneau court(boolean c) { court = c; return this; }

        /** Une operation (aperçu, copie ou collage) est-elle en cours dans ce panneau ? */
        boolean actif() { return actif; }

        private static void montrer(Node n, boolean v) { n.setVisible(v); n.setManaged(v); }

        private static void fx(Runnable r) { if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r); }

        /** Debut d'une operation : arret remis a zero. */
        Panneau demarrer() { stop = false; actif = true; return this; }

        private void fermerApercu() {
            fx(() -> { montrer(apercu, false); etat.setText(""); });
            actif = false;
        }

        @Override public boolean arretee() { return stop; }

        @Override public void texte(String s) {
            String t = Ui.majuscule(Ui.accorder(s));
            fx(() -> etat.setText(t));
        }

        @Override public void travail(String s) {
            texte(s);
            fx(() -> { montrer(apercu, false); montrer(ligneProgres, true); barre.setProgress(ProgressBar.INDETERMINATE_PROGRESS); });
        }

        @Override public void progres(int fait, int total, String s) {
            texte(s);
            double v = total <= 0 ? ProgressBar.INDETERMINATE_PROGRESS : Math.min(1.0, fait / (double) total);
            fx(() -> { montrer(ligneProgres, true); barre.setProgress(v); });
        }

        @Override public void bilan(boolean ok, String s) {
            java.util.function.BiConsumer<Boolean, String> r = surBilan;
            if (r == null) direBilan(ok, s);
            else try { r.accept(ok, s); } catch (Throwable t) { direBilan(ok, s); }
            actif = false;
            fx(() -> {
                montrer(ligneProgres, false); montrer(apercu, false); etat.setText("");
                try { surFin.run(); } catch (Throwable t) { Journal.debug("Suivi wired : fin : " + t); }
            });
        }

        /** Refus avant toute operation (rien n'est lance) : message dans le jeu, panneau inchange. */
        void refus(String s) { direBilan(false, s); }

        private Generateur.Source source() {
            switch (source.getSelectionModel().getSelectedIndex()) {
                case 1: return Generateur.Source.INVENTAIRE_PUIS_BC;
                case 2: return Generateur.Source.BC_PUIS_INVENTAIRE;
                case 3: return Generateur.Source.BC;
                default: return Generateur.Source.INVENTAIRE;
            }
        }

        /**
         * Aperçu chiffre d'un collage : titre, resume, plan (selon « autres »),
         * disponibilite (selon la source), puis Confirmer -> lancer(avecAutres, source).
         *
         * @param consigne la suite apres Confirmer (« clique dans le jeu… »)
         */
        void apercu(Copie c, String titreTexte, String resumeTexte, boolean avecAutres, String consigne,
                    java.util.function.BiConsumer<Boolean, Generateur.Source> lancer) {
            demarrer();
            fx(() -> {
                Moteur gp = Salle.gp();
                titre.setText(Ui.majuscule(titreTexte));
                montrer(titre, !court);
                resume.setText(resumeTexte == null ? "" : Ui.majuscule(Ui.accorder(resumeTexte)));
                montrer(resume, resumeTexte != null && !resumeTexte.isBlank());
                autres.setSelected(avecAutres);
                source.getSelectionModel().select(sourceDefaut);
                // « autres mobis » : seulement s'il y en a dans la copie
                montrer(autres, planifier(c, true).autres > 0);
                Runnable maj = () -> {
                    Plan p = planifier(c, autres.isSelected());
                    if (court) {
                        plan.setText(Ui.majuscule(Ui.accorder(p.aPoser.size() + " mobi(s) à poser. " + consigne)));
                        String d = gp == null ? "" : disponibilite(gp, p, source());
                        boolean rienNeManque = d.endsWith("tout peut être posé.") && !d.startsWith("Inventaire pas encore chargé");
                        dispo.setText(rienNeManque ? "" : Ui.majuscule(Ui.accorder(d)));
                        montrer(dispo, !rienNeManque);
                        return;
                    }
                    montrer(dispo, true);
                    plan.setText(Ui.majuscule(Ui.accorder(p.aPoser.size() + " mobi(s) à poser : " + p.wired + " wired"
                            + (p.sansReglage > 0 ? " (dont " + p.sansReglage + " sans réglage, posé(s) vide(s))" : "")
                            + ", " + p.lies + " mobi(s) qu'ils utilisent"
                            + (p.autres > 0 ? ", " + p.autres + " autre(s)" : "") + ". "
                            + (p.refsPerdues > 0 ? p.refsPerdues + " sélection(s) vers des mobis hors copie perdue(s). " : "")
                            + consigne)));
                    dispo.setText(gp == null ? "" : Ui.majuscule(Ui.accorder(disponibilite(gp, p, source()))));
                };
                autres.setOnAction(e -> maj.run());
                source.setOnAction(e -> maj.run());
                maj.run();
                surConfirmer = () -> {
                    if (enCours) { texte("Une copie ou un collage est déjà en cours."); return; }
                    boolean avec = autres.isSelected();
                    Generateur.Source s = source();
                    lancer.accept(avec, s);
                };
                etat.setText("");
                montrer(ligneProgres, false);
                montrer(apercu, true);
            });
        }
    }
}

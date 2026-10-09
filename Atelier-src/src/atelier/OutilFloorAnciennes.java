package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Outil ponctuel, en ligne de commande (pas de bouton dans l'appli) : ajoute
 * un floor reconstitue (« atelierFloor ») aux anciennes copies d'apparts
 * COMPLETS, enregistrees avant que l'Atelier n'enregistre le floor.
 *
 *   sudo java -cp Atelier.jar atelier.OutilFloorAnciennes [--essai] [--zones-aussi]
 *        [--porte-connue] [dossier...]
 *
 * Sans dossier : Dossiers.ancienModule()/presets (dossier de l'utilisatrice,
 * SUDO_USER) puis /var/root/Library/Application Support/G-Presets/presets.
 * --essai : n'ecrit rien, montre ce qui serait fait.
 * --zones-aussi : traite aussi les copies qui ressemblent a une zone.
 * --porte-connue : marque la porte devinee comme connue (porteConnue = true) ;
 *   sans cela, OngletApparts.appliquerFloor refuse de coller avec ce floor.
 *
 * Ce que la copie contient : x, y = position de la case moins le coin de la
 * zone exportee. G-Presets « :ep all » prend le coin (0,0) : positions
 * absolues. « :ep » avec un rectangle (souvent tout l'appart, coin sur la
 * premiere case de sol) les rend relatives : les muraux (TOUS ceux de la salle,
 * meme pour une zone) tombent alors a x ou y = -1 devant le coin. Un mural
 * negatif ne dit donc PAS « zone ». z = altitude du mobi (mobis en hauteur
 * compris : dalle magique, @altitude). Au collage avec floor
 * (OngletApparts.collerAppart) : racine = (x0, y0), mobi en racine + (x, y),
 * a son z d'origine.
 *
 * Reconstitution (reconstituer, marches) :
 *   - interieur : mur gauche = colonne XL (plus petit x des muraux « l »), mur
 *     haut = ligne YR (plus petit y des « r ») ; sol de XL+1, YR+1 jusqu'au bout
 *     du roomLayout (positions absolues) ou des muraux et des mobis (relatives) ;
 *     sans roomLayout ni mural : rectangle des mobis + 1 (--zones-aussi) ;
 *   - zone (ignoree sans --zones-aussi) : mobis sur moins de la moitie de
 *     l'interieur en largeur ET en longueur ;
 *   - SOL PLAT a la hauteur la plus basse observee ; une marche a h n'est
 *     retenue que sur une zone contigue >= 6 cases (20 au-dessus de 9) dont le
 *     mobi le plus bas est pile a h, voisines coherentes, 3 niveaux au plus ;
 *     hauteurs isolees, non entieres, petites : ecartees (et comptees) ;
 *   - plan decale (x0, y0) si le mur gauche/haut tombe avant 0 ;
 *   - porte : colonne du mur gauche, ligne sans mural ni mobi devant, au milieu,
 *     direction 2 ; porteConnue = --porte-connue.
 *   Fichiers « _atelier... » (calque...) : jamais traites ni listes.
 *
 * Ecriture : sauvegarde « nom.json.avant-floor » (une fois), .tmp + move
 * atomique, memes droits ; proprietaire = SUDO_USER dans le dossier de
 * l'utilisatrice, celui d'origine ailleurs (/var/root reste a root). Seules
 * les cles « atelierFloor » (avec « atelierFloorReconstitue »: true dedans)
 * sont ajoutees ; meme indentation que le fichier.
 */
public final class OutilFloorAnciennes {

    static final String ROOT_PRESETS = "/var/root/Library/Application Support/G-Presets/presets";

    /** Emprise des mobis de sol : classe -> {xdim, ydim}. Vide si pas de cache. */
    static Map<String, int[]> dims = new HashMap<>();

    static boolean essai, zonesAussi, porteConnue;

    public static void main(String[] args) throws Exception {
        List<File> dossiers = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--essai")) essai = true;
            else if (a.equals("--zones-aussi")) zonesAussi = true;
            else if (a.equals("--porte-connue")) porteConnue = true;
            else if (a.equals("-h") || a.equals("--aide")) { aide(); return; }
            else if (a.startsWith("--")) { System.out.println("Option inconnue : " + a); aide(); System.exit(2); }
            else dossiers.add(new File(a));
        }
        if (dossiers.isEmpty()) {
            File am = Dossiers.ancienModule();
            if (am != null) dossiers.add(new File(am, "presets"));
            dossiers.add(new File(ROOT_PRESETS));
        }
        chargerFurnidata();
        System.out.println(essai ? "== ESSAI : rien ne sera écrit ==" : "== Écriture des floors ==");

        Set<String> vus = new LinkedHashSet<>();
        int[] total = new int[4];   // ajoutes, deja, ignores, erreurs
        for (File d : dossiers) {
            String canon;
            try { canon = d.getCanonicalPath(); } catch (Exception e) { canon = d.getAbsolutePath(); }
            if (!vus.add(canon)) continue;
            System.out.println();
            System.out.println("######## Dossier : " + d);
            // « _atelier... » : fichiers internes de l'Atelier (calque...), jamais des copies
            // les copies sont rangees en dossiers (Apparts, Zones, et leurs sous-dossiers) : on descend dedans
            File[] fs = copiesSous(d);
            if (fs == null) {
                System.out.println("  Introuvable ou illisible" + (d.exists() ? " (lance avec sudo ?)" : "") + ".");
                continue;
            }
            Arrays.sort(fs);
            if (fs.length == 0) System.out.println("  Aucune copie.");
            boolean chezUtilisatrice = chezUtilisatrice(d);
            for (File f : fs) traiterFichier(f, chezUtilisatrice, total);
        }
        System.out.println();
        System.out.println("== Bilan : " + total[0] + (essai ? " floor(s) à ajouter" : " floor(s) ajouté(s)")
                + ", " + total[1] + " copie(s) avaient déjà leur floor, " + total[2] + " ignorée(s), "
                + total[3] + " erreur(s). ==");
        if (essai && total[0] > 0) System.out.println("Relance sans --essai pour écrire.");
    }

    /** Les copies de d et de ses sous-dossiers ; null si d est illisible. */
    static File[] copiesSous(File d) {
        if (d.listFiles() == null) return null;
        List<File> r = new ArrayList<>();
        ramasser(d, r, 0);
        return r.toArray(new File[0]);
    }

    private static void ramasser(File d, List<File> r, int prof) {
        File[] l = d.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) { if (prof < 20) ramasser(f, r, prof + 1); }
            else if (f.getName().endsWith(".json") && !f.getName().startsWith("_atelier")) r.add(f);
        }
    }

    static void aide() {
        System.out.println("Usage : sudo java -cp Atelier.jar atelier.OutilFloorAnciennes [--essai] [--zones-aussi] [--porte-connue] [dossier...]");
    }

    // ------------------------------------------------------------ furnidata

    /** Lit le cache disque de la furnidata (Furnidata.fichierCache), en lecture seule. */
    static void chargerFurnidata() {
        File cache = Furnidata.fichierCache("fr");
        List<File> l = new ArrayList<>();
        l.add(cache);
        File[] autres = cache.getParentFile() == null ? null : cache.getParentFile().listFiles((x, n) -> n.startsWith("furnidata-") && n.endsWith(".json"));
        if (autres != null) { Arrays.sort(autres); l.addAll(Arrays.asList(autres)); }
        for (File f : l) {
            if (!f.isFile()) continue;
            try {
                lireFurnidata(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                System.out.println("Furnidata : " + f + " (" + dims.size() + " mobis de sol).");
                return;
            } catch (Exception e) {
                System.out.println("Furnidata illisible : " + f + " (" + e + ")");
            }
        }
        System.out.println("ATTENTION : pas de furnidata en cache (" + cache + ") : chaque mobi compte pour 1×1 case.");
    }

    static void lireFurnidata(String texte) {
        JSONArray a = new JSONObject(texte).getJSONObject("roomitemtypes").getJSONArray("furnitype");
        Map<String, int[]> m = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String c = o.optString("classname", null);
            if (c != null) m.put(c, new int[]{Math.max(1, o.optInt("xdim", 1)), Math.max(1, o.optInt("ydim", 1))});
        }
        if (m.isEmpty()) throw new IllegalStateException("furnidata vide");
        dims = m;
    }

    // ------------------------------------------------------------ reconstitution (logique pure)

    /** Resultat d'une reconstitution. */
    static final class Resultat {
        /** null = copie a ignorer (raison dans ignoree). */
        FloorModele modele;
        JSONObject floor;
        String ignoree;
        boolean doute;
        final List<String> notes = new ArrayList<>();
        int mobis, muraux, cases, inconnus, base, casesBase, casesMarches, bouchees;
        int dx, dy;
        /** Marches retenues : hauteur -> cases (dont bouchees). */
        final Map<Integer, Integer> marches = new TreeMap<>();
        /** Hauteurs ecartees : texte deja forme (« 7 : 3 cases, trop petite »). */
        final List<String> ecartees = new ArrayList<>();
    }

    /** Taille mini d'une zone pour retenir une marche ; au-dessus de 9, zone large. */
    static final int MARCHE_MIN = 6, MARCHE_HAUTE_MIN = 20, NIVEAUX_MAX = 3;

    /** Voisinage d'une zone de marche : jusqu'a 2 cases (diagonales comprises). */
    static final int[][] PROCHES;
    static {
        List<int[]> l = new ArrayList<>();
        for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) if (a != 0 || b != 0) l.add(new int[]{a, b});
        PROCHES = l.toArray(new int[0][]);
    }

    /**
     * Une copie sans « atelierFloor » : reconstitue son floor (sans rien modifier).
     *
     * Reperes (coordonnees de la copie) : mur gauche = colonne XL (plus petit x
     * des muraux « l ») ; mur haut = ligne YR (plus petit y des muraux « r ») ;
     * le sol commence en XL+1, YR+1. Fin : roomLayout si la copie est en
     * coordonnees absolues (« :ep all » de G-Presets, coin (0,0) : rien de
     * negatif), sinon le plus loin des mobis et des muraux (fin des pans).
     */
    static Resultat reconstituer(JSONObject brut) {
        Resultat r = new Resultat();
        CopieAppart c = CopieAppart.lire(brut);
        r.mobis = c.sols.size();
        r.muraux = c.murs.size();
        if (c.sols.isEmpty()) { r.ignoree = "aucun mobi au sol"; return r; }

        // --- emprise des mobis : z le plus bas par case
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        Map<Long, Double> basse = new HashMap<>();
        for (CopieAppart.MobiSol m : c.sols) {
            int[] d = dims.get(m.classe);
            if (d == null) { r.inconnus++; d = new int[]{1, 1}; }
            int rot = ((m.rotation % 8) + 8) % 8;
            int w = (rot == 2 || rot == 6) ? d[1] : d[0], l = (rot == 2 || rot == 6) ? d[0] : d[1];
            for (int i = 0; i < w; i++)
                for (int j = 0; j < l; j++) {
                    int x = m.x + i, y = m.y + j;
                    basse.merge(cle(x, y), m.z, Math::min);
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
        }

        // --- murs : pan gauche (« l », x constant), pan haut (« r », y constant)
        Integer xl = null, yr = null;
        int finY = Integer.MIN_VALUE, finX = Integer.MIN_VALUE;      // bout des pans
        Set<Integer> lignesMurGauche = new java.util.HashSet<>();
        boolean negatif = minX < 0 || minY < 0;
        for (CopieAppart.MobiMur w : c.murs) {
            PositionMur p = w.position;
            if (p.x() < 0 || p.y() < 0) negatif = true;
            if (p.gauche()) { xl = xl == null ? p.x() : Math.min(xl, p.x()); finY = Math.max(finY, p.y()); }
            else { yr = yr == null ? p.y() : Math.min(yr, p.y()); finX = Math.max(finX, p.x()); }
        }
        if (xl != null) for (CopieAppart.MobiMur w : c.murs) if (w.position.gauche() && w.position.x() == xl) lignesMurGauche.add(w.position.y());
        JSONObject rl = brut.optJSONObject("roomLayout");
        int planW = rl == null ? 0 : rl.optInt("floorplanWidth", 0), planL = rl == null ? 0 : rl.optInt("floorplanHeight", 0);
        boolean layout = planW > 0 && planL > 0;
        // G-Presets « :ep all » : coin (0,0), positions absolues, tout tient dans le roomLayout.
        boolean absolu = layout && !negatif && maxX < planW && maxY < planL
                && (xl == null || xl < planW) && (yr == null || yr < planL);
        if (!layout && c.murs.isEmpty()) {
            r.ignoree = "ni roomLayout ni mural : rien ne dit que c'est un appart entier";
            if (!zonesAussi) { r.ignoree += " ; --zones-aussi pour la traiter quand même"; return r; }
            r.ignoree = null;
            r.doute = true;
            r.notes.add("ni roomLayout ni mural : rectangle des mobis + 1");
        }

        // --- interieur des murs
        int x1 = xl != null ? Math.min(minX, xl + 1) : minX;
        int y1 = yr != null ? Math.min(minY, yr + 1) : minY;
        int x2, y2;
        if (absolu) { x2 = Math.max(maxX, planW - 1); y2 = Math.max(maxY, planL - 1); }
        else {
            x2 = Math.max(maxX, finX);
            y2 = Math.max(maxY, finY);
            if (xl == null && yr == null) { x2 = maxX + 1; y2 = maxY + 1; }
            if (layout) {   // coordonnees relatives : le roomLayout borne quand meme la taille
                x2 = Math.max(maxX, Math.min(x2, x1 + planW - 2));
                y2 = Math.max(maxY, Math.min(y2, y1 + planL - 2));
            }
        }
        r.notes.add(absolu ? "positions absolues (coin 0,0), taille du roomLayout " + planW + "×" + planL
                : "positions relatives (copie par rectangle" + (negatif ? ", muraux devant le coin" : "")
                + ") : taille d'après les muraux et les mobis" + (layout ? " (roomLayout " + planW + "×" + planL + ")" : ""));

        // --- appart entier ou zone : les mobis couvrent-ils l'interieur ?
        int iw = x2 - x1 + 1, il = y2 - y1 + 1, bw = maxX - minX + 1, bl = maxY - minY + 1;
        if (bw * 2 < iw && bl * 2 < il) {
            String zone = "mobis sur " + bw + "×" + bl + " cases seulement, intérieur " + iw + "×" + il + " (zone ?)";
            if (!zonesAussi) { r.ignoree = zone + " ; --zones-aussi pour la traiter quand même"; return r; }
            r.doute = true;
            r.notes.add(zone);
        }
        if (c.murs.isEmpty()) { r.doute = true; r.notes.add("aucun mural : bords gauche/haut = ceux des mobis"); }

        // --- decalage : colonne de la porte (x1-1) et ligne du mur (y1-1) dans le plan
        r.dx = Math.max(0, 1 - x1);
        r.dy = Math.max(0, 1 - y1);
        int W = x2 + r.dx + 1, L = y2 + r.dy + 1;
        if (W > 128 || L > 128) { r.ignoree = "plan trop grand (" + W + "×" + L + ")"; return r; }

        // --- hauteurs : sol plat a la hauteur la plus basse, marches seulement si confirmees
        double zMin = Double.MAX_VALUE;
        for (double z : basse.values()) zMin = Math.min(zMin, z);
        r.base = Math.max(0, Math.min(FloorModele.HAUTEUR_MAX, (int) Math.floor(zMin + 1e-6)));
        int[][] niv = new int[W][L];                  // -1 = pas de case, sinon hauteur
        for (int[] col : niv) Arrays.fill(col, -1);
        boolean[][] ancre = new boolean[W][L];
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) niv[x + r.dx][y + r.dy] = r.base;
        for (long k : basse.keySet()) niv[kx(k) + r.dx][ky(k) + r.dy] = r.base;
        marches(basse, r, niv, ancre);

        FloorModele m = new FloorModele(W, L);
        for (int x = 0; x < W; x++) for (int y = 0; y < L; y++) m.h[x][y] = niv[x][y];

        // --- porte : colonne du mur gauche, sur une ligne sans mural ni mobi devant, au milieu
        int px = x1 - 1 + r.dx, py = -1, meilleur = Integer.MAX_VALUE;
        for (int passe = 0; passe < 3 && py < 0; passe++)
            for (int y = y1; y <= y2; y++) {
                int yy = y + r.dy;
                if (!m.existe(px + 1, yy) || m.existe(px, yy)) continue;
                if (passe < 2 && basse.containsKey(cle(x1, y))) continue;
                if (passe < 1 && (lignesMurGauche.contains(y) || m.at(px + 1, yy) != r.base)) continue;
                int d = Math.abs(2 * y - (y1 + y2));
                if (d < meilleur) { meilleur = d; py = yy; }
            }
        if (py < 0) { r.ignoree = "aucune place pour la porte"; return r; }
        m.h[px][py] = m.at(px + 1, py);
        m.porteX = px; m.porteY = py; m.porteDir = 2;
        m.porteConnue = porteConnue;
        m.hauteurMur = rl != null && rl.has("wallHeight") ? rl.optInt("wallHeight", -1) : -1;
        // epMur, epSol : valeurs par defaut de FloorModele (0)

        r.modele = m;
        r.cases = m.nbCases();
        for (int x = 0; x < W; x++) for (int y = 0; y < L; y++)
            if (m.h[x][y] == r.base && !(x == px && y == py)) r.casesBase++;
        for (int v : r.marches.values()) r.casesMarches += v;
        if (r.inconnus > 0) r.notes.add(r.inconnus + " mobi(s) inconnu(s) de la furnidata : comptés 1×1");
        if (r.dx != 0 || r.dy != 0) r.notes.add("plan décalé de " + r.dx + "," + r.dy + " (x0,y0) : les mobis gardent leur place relative");

        // --- meme format que OngletApparts.copierSalleVersAppart
        JSONObject f = new JSONObject();
        f.put("plan", m.texte());
        f.put("porteX", m.porteX); f.put("porteY", m.porteY); f.put("porteDir", m.porteDir);
        f.put("porteConnue", m.porteConnue);
        f.put("hauteurMur", m.hauteurMur);
        f.put("epMur", m.epMur); f.put("epSol", m.epSol);
        f.put("x0", r.dx); f.put("y0", r.dy);
        f.put("atelierFloorReconstitue", true);
        r.floor = f;
        return r;
    }

    /**
     * Marches : une hauteur entiere h > base n'est retenue que sur une zone
     * contigue (cases a 2 au plus l'une de l'autre) d'au moins MARCHE_MIN cases (MARCHE_HAUTE_MIN
     * au-dessus de 9) dont le mobi le plus bas est pile a h, et dont les
     * voisines meublees sont surtout a une hauteur entiere <= h (un plateau
     * pose sur dalle magique est entoure de mobis a des hauteurs quelconques).
     * Au plus NIVEAUX_MAX hauteurs (les plus etendues). Le rectangle de la
     * marche (hors cases a mobis plus bas) et les vides enclos par elle prennent
     * sa hauteur.
     */
    static void marches(Map<Long, Double> basse, Resultat r, int[][] niv, boolean[][] ancre) {
        int W = niv.length, L = niv[0].length;
        int[][] v4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // hauteur entiere exacte du mobi le plus bas, par case (null = non entiere ou base)
        Map<Long, Integer> entiere = new HashMap<>();
        Map<String, Integer> nonEntieres = new TreeMap<>();
        for (Map.Entry<Long, Double> e : basse.entrySet()) {
            double z = e.getValue();
            long hz = Math.round(z);
            if (z < r.base + 1 - 1e-6) continue;
            if (Math.abs(z - hz) < 1e-3) entiere.put(e.getKey(), (int) hz);
            else nonEntieres.merge(fmt(Math.floor(z * 100) / 100), 1, Integer::sum);
        }
        // zones contigues par hauteur
        Map<Integer, List<List<Long>>> zones = new TreeMap<>();
        Set<Long> vu = new java.util.HashSet<>();
        for (long k : entiere.keySet()) {
            if (!vu.add(k)) continue;
            int h = entiere.get(k);
            List<Long> zone = new ArrayList<>();
            ArrayDeque<Long> f = new ArrayDeque<>();
            f.add(k);
            while (!f.isEmpty()) {
                long p = f.poll();
                zone.add(p);
                for (int[] d : PROCHES) {   // un trou d'une case (mobis espaces) ne coupe pas la zone
                    long q = cle(kx(p) + d[0], ky(p) + d[1]);
                    Integer hq = entiere.get(q);
                    if (hq != null && hq == h && vu.add(q)) f.add(q);
                }
            }
            zones.computeIfAbsent(h, x -> new ArrayList<>()).add(zone);
        }
        Map<Integer, List<List<Long>>> retenues = new HashMap<>();
        Map<Integer, Integer> aire = new HashMap<>();
        for (Map.Entry<Integer, List<List<Long>>> e : zones.entrySet()) {
            int h = e.getKey(), petites = 0, isolees = 0, incoherentes = 0, clairsemees = 0, plusGrande = 0;
            for (List<Long> zone : e.getValue()) {
                plusGrande = Math.max(plusGrande, zone.size());
                int min = h > 9 ? MARCHE_HAUTE_MIN : MARCHE_MIN;
                if (zone.size() < min) { if (zone.size() == 1) isolees++; else petites++; continue; }
                if (h > FloorModele.HAUTEUR_MAX) { incoherentes += zone.size(); continue; }
                int a1 = Integer.MAX_VALUE, b1 = Integer.MAX_VALUE, a2 = Integer.MIN_VALUE, b2 = Integer.MIN_VALUE;
                for (long p : zone) { a1 = Math.min(a1, kx(p)); b1 = Math.min(b1, ky(p)); a2 = Math.max(a2, kx(p)); b2 = Math.max(b2, ky(p)); }
                if (zone.size() * 10 < 4 * (a2 - a1 + 1) * (b2 - b1 + 1)) { clairsemees += zone.size(); continue; }   // < 40 % : mobis epars
                // voisines meublees hors zone : entieres et <= h pour au moins la moitie
                Set<Long> dedans = new java.util.HashSet<>(zone);
                int vois = 0, ok = 0;
                for (long p : zone)
                    for (int[] d : v4) {
                        long q = cle(kx(p) + d[0], ky(p) + d[1]);
                        if (dedans.contains(q) || !basse.containsKey(q)) continue;
                        vois++;
                        double z = basse.get(q);
                        if (z <= h + 1e-6 && Math.abs(z - Math.rint(z)) < 1e-3) ok++;
                    }
                if (vois > 0 && ok * 2 < vois) { incoherentes += zone.size(); continue; }
                retenues.computeIfAbsent(h, x -> new ArrayList<>()).add(zone);
                aire.merge(h, zone.size(), Integer::sum);
            }
            int ecartees = 0;
            for (List<Long> zone : e.getValue()) ecartees += zone.size();
            ecartees -= aire.getOrDefault(h, 0);
            if (ecartees > 0) {
                List<String> pourquoi = new ArrayList<>();
                if (isolees > 0) pourquoi.add(isolees + " isolée(s)");
                if (petites > 0) pourquoi.add("zones trop petites (" + plusGrande + " cases au plus, " + (h > 9 ? MARCHE_HAUTE_MIN : MARCHE_MIN) + " voulues)");
                if (clairsemees > 0) pourquoi.add(clairsemees + " case(s) trop éparses (mobis espacés, pas un sol)");
                if (incoherentes > 0) pourquoi.add(incoherentes + " case(s) incohérentes avec les voisines");
                r.ecartees.add(FloorModele.car(Math.min(35, h)) + " (" + h + ") : " + ecartees + " case(s), " + String.join(", ", pourquoi));
            }
        }
        // au plus NIVEAUX_MAX hauteurs, les plus etendues
        List<Integer> niveaux = new ArrayList<>(retenues.keySet());
        niveaux.sort((a, b) -> aire.get(b) - aire.get(a));
        for (int i = NIVEAUX_MAX; i < niveaux.size(); i++) {
            int h = niveaux.get(i);
            r.ecartees.add(FloorModele.car(Math.min(35, h)) + " (" + h + ") : " + aire.get(h) + " case(s), trop de niveaux distincts");
            retenues.remove(h);
        }
        if (!nonEntieres.isEmpty()) {
            int n = 0;
            for (int v : nonEntieres.values()) n += v;
            r.ecartees.add(n + " case(s) à hauteur non entière (mobis surélevés : " + abrege(nonEntieres.keySet()) + ")");
        }
        int dx = r.dx, dy = r.dy;
        for (Map.Entry<Integer, List<List<Long>>> e : retenues.entrySet())
            for (List<Long> zone : e.getValue())
                for (long p : zone) {
                    niv[kx(p) + dx][ky(p) + dy] = e.getKey();
                    ancre[kx(p) + dx][ky(p) + dy] = true;
                    r.marches.merge(e.getKey(), 1, Integer::sum);
                }
        // rectangle de chaque marche : ses cases vides (ou meublees plus haut) suivent,
        // sauf si trop de cases y portent des mobis plus bas (forme non rectangulaire)
        for (Map.Entry<Integer, List<List<Long>>> e : retenues.entrySet())
            for (List<Long> zone : e.getValue()) {
                int h = e.getKey(), a1 = Integer.MAX_VALUE, b1 = Integer.MAX_VALUE, a2 = Integer.MIN_VALUE, b2 = Integer.MIN_VALUE;
                for (long p : zone) { a1 = Math.min(a1, kx(p)); b1 = Math.min(b1, ky(p)); a2 = Math.max(a2, kx(p)); b2 = Math.max(b2, ky(p)); }
                int plusBas = 0, aireR = (a2 - a1 + 1) * (b2 - b1 + 1);
                for (int x = a1; x <= a2; x++) for (int y = b1; y <= b2; y++) {
                    Double z = basse.get(cle(x, y));
                    if (z != null && z < h - 1e-6) plusBas++;
                }
                if (plusBas * 10 > aireR) continue;
                for (int x = a1; x <= a2; x++) for (int y = b1; y <= b2; y++) {
                    int gx = x + dx, gy = y + dy;
                    Double z = basse.get(cle(x, y));
                    if (niv[gx][gy] < 0 || ancre[gx][gy] || (z != null && z < h - 1e-6)) continue;
                    niv[gx][gy] = h;
                    ancre[gx][gy] = true;
                    r.marches.merge(h, 1, Integer::sum);
                    r.bouchees++;
                }
            }

        // vides enclos par une seule marche : sa hauteur
        boolean[][] vuG = new boolean[W][L];
        for (int x = 0; x < W; x++)
            for (int y = 0; y < L; y++) {
                if (vuG[x][y] || niv[x][y] < 0 || ancre[x][y]) continue;
                List<int[]> region = new ArrayList<>();
                Set<Integer> bords = new java.util.HashSet<>();
                double plusBas = Double.MAX_VALUE;
                ArrayDeque<int[]> f = new ArrayDeque<>();
                f.add(new int[]{x, y});
                vuG[x][y] = true;
                while (!f.isEmpty()) {
                    int[] p = f.poll();
                    region.add(p);
                    Double z = basse.get(cle(p[0] - dx, p[1] - dy));
                    if (z != null) plusBas = Math.min(plusBas, z);
                    for (int[] d : v4) {
                        int a = p[0] + d[0], b = p[1] + d[1];
                        if (a < 0 || b < 0 || a >= W || b >= L || niv[a][b] < 0) { bords.add(-1); continue; }
                        if (ancre[a][b]) { bords.add(niv[a][b]); continue; }
                        if (!vuG[a][b]) { vuG[a][b] = true; f.add(new int[]{a, b}); }
                    }
                }
                if (bords.size() != 1 || bords.contains(-1)) continue;
                int h = bords.iterator().next();
                if (region.size() > r.marches.getOrDefault(h, 0) || plusBas < h - 1e-6) continue;
                for (int[] p : region) niv[p[0]][p[1]] = h;
                r.marches.merge(h, region.size(), Integer::sum);
                r.bouchees += region.size();
            }
    }

    static String abrege(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        if (l.size() <= 8) return String.join(" ", l);
        return String.join(" ", l.subList(0, 8)) + " …";
    }

    static long cle(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }
    static int kx(long k) { return (int) (k >> 32); }
    static int ky(long k) { return (int) k; }

    static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    // ------------------------------------------------------------ fichiers

    static void traiterFichier(File f, boolean chezUtilisatrice, int[] total) {
        String nom = f.getName().substring(0, f.getName().length() - 5);
        System.out.println();
        try {
            String texte = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JSONObject brut = new JSONObject(texte);
            if (brut.has("atelierFloor")) {
                System.out.println("« " + nom + " » : a déjà son floor" + (brut.optJSONObject("atelierFloor") != null
                        && brut.getJSONObject("atelierFloor").optBoolean("atelierFloorReconstitue") ? " (reconstitué)" : "") + ".");
                total[1]++;
                return;
            }
            Resultat r = reconstituer(brut);
            if (r.ignoree != null) {
                System.out.println("« " + nom + " » : ignorée — " + r.ignoree + " (" + r.mobis + " mobis au sol, "
                        + r.muraux + " muraux).");
                total[2]++;
                return;
            }
            resumer(nom, r);
            if (essai) { total[0]++; return; }

            brut.put("atelierFloor", r.floor);
            String sortie = indentation(texte) > 0 ? brut.toString(indentation(texte)) : brut.toString();
            ecrire(f, sortie, chezUtilisatrice);

            // relecture comme le collage : CopieAppart + plan du floor
            JSONObject relu = new JSONObject(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            CopieAppart.lire(relu);
            FloorModele fm = FloorModele.depuisTexte(relu.getJSONObject("atelierFloor").optString("plan", null));
            if (fm == null || !fm.texte().equals(r.modele.texte())) throw new IllegalStateException("relecture différente");
            System.out.println("  -> écrit et relu (sauvegarde : " + f.getName() + ".avant-floor).");
            total[0]++;
        } catch (Exception e) {
            System.out.println("« " + nom + " » : ERREUR — " + e);
            total[3]++;
        }
    }

    static void resumer(String nom, Resultat r) {
        FloorModele m = r.modele;
        System.out.println("« " + nom + " »" + (r.doute ? "  [À VÉRIFIER]" : "") + " : " + r.mobis + " mobis au sol, "
                + r.muraux + " muraux ; plan " + m.largeur + "×" + m.longueur + ", " + r.cases + " cases ; porte "
                + m.porteX + "," + m.porteY + " dir " + m.porteDir + (m.porteConnue ? "" : " (porteConnue=false)")
                + ", hauteur des murs " + m.hauteurMur + ".");
        StringBuilder mar = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : r.marches.entrySet())
            mar.append(mar.length() == 0 ? "" : ", ").append(FloorModele.car(e.getKey())).append(" : ").append(e.getValue());
        System.out.println("  " + r.casesBase + " case(s) à hauteur de base " + r.base + " ; marches retenues : "
                + (r.marches.isEmpty() ? "aucune" : mar + " case(s)" + (r.bouchees > 0 ? " (dont " + r.bouchees + " vides enclos)" : ""))
                + " ; hauteurs écartées : " + (r.ecartees.isEmpty() ? "aucune" : r.ecartees.size()) + ".");
        for (String e : r.ecartees) System.out.println("    écartée " + e);
        for (String n : r.notes) System.out.println("  ! " + n);
        System.out.println("  aperçu (plan entier, x = vide, P = porte) :");
        for (int y = 0; y < m.longueur; y++) {
            StringBuilder b = new StringBuilder("    ");
            for (int x = 0; x < m.largeur; x++)
                b.append(x == m.porteX && y == m.porteY ? 'P' : m.existe(x, y) ? FloorModele.car(Math.min(35, m.at(x, y))) : 'x');
            System.out.println(b);
        }
    }

    /** Largeur d'indentation du fichier d'origine (0 = sur une ligne). */
    static int indentation(String texte) {
        int i = texte.indexOf('\n');
        if (i < 0) return 0;
        int n = 0;
        while (i + 1 + n < texte.length() && texte.charAt(i + 1 + n) == ' ') n++;
        return n == 0 ? 2 : n;
    }

    /** Sauvegarde .avant-floor (une fois), puis ecriture atomique, droits et proprietaire gardes. */
    static void ecrire(File f, String texte, boolean chezUtilisatrice) throws Exception {
        Path p = f.toPath();
        PosixFileAttributes attr = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal proprio = attr.owner();
        GroupPrincipal groupe = attr.group();
        String sudo = System.getenv("SUDO_USER");
        if (chezUtilisatrice && sudo != null && !sudo.isBlank() && !"root".equals(sudo)) {
            UserPrincipalLookupService ls = p.getFileSystem().getUserPrincipalLookupService();
            try {
                proprio = ls.lookupPrincipalByName(sudo);
                groupe = ls.lookupPrincipalByGroupName("staff");
            } catch (Exception e) {
                System.out.println("  ! utilisatrice " + sudo + " introuvable : propriétaire d'origine gardé.");
            }
        }

        Path sauvegarde = p.resolveSibling(f.getName() + ".avant-floor");
        if (!Files.exists(sauvegarde, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(p, sauvegarde, StandardCopyOption.COPY_ATTRIBUTES);
            proprietaire(sauvegarde, proprio, groupe, attr);
        }

        Path tmp = p.resolveSibling(f.getName() + ".tmp");
        Files.write(tmp, texte.getBytes(StandardCharsets.UTF_8));
        proprietaire(tmp, proprio, groupe, attr);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void proprietaire(Path p, UserPrincipal u, GroupPrincipal g, PosixFileAttributes modele) {
        PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        try { v.setPermissions(modele.permissions()); } catch (Exception e) { System.out.println("  ! droits non recopiés sur " + p.getFileName() + " : " + e); }
        try {
            if (!u.equals(Files.getOwner(p))) v.setOwner(u);
            if (!g.equals(v.readAttributes().group())) v.setGroup(g);
        } catch (Exception e) {
            System.out.println("  ! propriétaire non changé sur " + p.getFileName() + " (" + u.getName() + ") : " + e);
        }
    }

    /** Dossier de l'utilisatrice reelle (sous sa maison, pas chez root). */
    static boolean chezUtilisatrice(File d) {
        try {
            String m = Dossiers.maison().getCanonicalPath();
            String c = d.getCanonicalPath();
            return !m.startsWith("/var/root") && !m.startsWith("/private/var/root") && c.startsWith(m + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}

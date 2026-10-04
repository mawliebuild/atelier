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
 * Ce que la copie contient (EnregistrementCopie.assembler) : x, y = position de
 * la case moins le coin de la zone (0,0 pour un appart complet, donc positions
 * absolues dans la salle d'origine) ; z = altitude absolue de la case du mobi
 * (getTile().getZ()) ; srcAnchorFloorHeight = sol le plus bas de la zone. Au
 * collage avec floor (OngletApparts.collerAppart) : racine = (x0, y0), mobi en
 * racine + (x, y), a z - ancre + hauteur de la case (x0, y0) (0 si pas de case).
 *
 * Reconstitution :
 *   - cases = emprise des mobis de sol (furnidata du cache disque de l'Atelier,
 *     rotation 2/6 = dimensions echangees ; 1x1 si inconnu) ;
 *   - hauteur = partie entiere du plus petit z pose sur la case, 0..35 ;
 *   - murs : un mural sur le mur gauche (« l », w=X,Y) etend la ligne Y jusqu'a
 *     X+1 ; sur le mur droit/haut (« r », w=X,Y) la colonne X jusqu'a Y+1 (le mur
 *     est sur la case vide X, comme la porte du modele a) ; entre deux muraux du
 *     meme pan, les lignes intermediaires aussi ;
 *   - trous interieurs (vides non atteignables depuis le bord) : bouches avec la
 *     hauteur la plus frequente des voisines ;
 *   - taille : roomLayout (floorplanWidth/Height) s'il existe, sinon le
 *     rectangle englobant depuis (0,0) ;
 *   - porte : sur la case vide a gauche d'une case du bord gauche, sans mobi,
 *     direction 2 (vers +x), a la hauteur de sa voisine ; porteConnue = false.
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
            File[] fs = d.listFiles((x, n) -> n.endsWith(".json"));
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
        int mobis, muraux, cases, bouchees, parMurs, hors, inconnus, hMin, hMax;
        int minX, minY, maxX, maxY;
    }

    /** Une copie sans « atelierFloor » : reconstitue son floor (sans rien modifier). */
    static Resultat reconstituer(JSONObject brut) {
        Resultat r = new Resultat();
        CopieAppart c = CopieAppart.lire(brut);
        r.mobis = c.sols.size();
        r.muraux = c.murs.size();
        if (c.sols.isEmpty()) { r.ignoree = "aucun mobi au sol"; return r; }

        // --- appart complet ou zone ?
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
        r.minX = minX; r.minY = minY; r.maxX = maxX; r.maxY = maxY;
        int mursNegatifs = 0;
        for (CopieAppart.MobiMur w : c.murs) if (w.position.x() < 0 || w.position.y() < 0) mursNegatifs++;
        JSONObject rl = brut.optJSONObject("roomLayout");
        int planW = rl == null ? 0 : rl.optInt("floorplanWidth", 0), planL = rl == null ? 0 : rl.optInt("floorplanHeight", 0);

        String zone = null;
        if (mursNegatifs > 0) zone = mursNegatifs + " mural(aux) à position négative (copie d'une zone)";
        else if (minX < 0 || minY < 0) zone = "mobis à position négative";
        else if (minX == 0 && minY == 0) zone = "mobis collés au coin (0,0) : copie d'une zone probable";
        if (zone != null && !zonesAussi) { r.ignoree = zone + " ; --zones-aussi pour la traiter quand même"; return r; }
        if (zone != null) { r.doute = true; r.notes.add("ressemble à une zone : " + zone); }
        if (minX < 0 || minY < 0) { r.ignoree = "positions négatives : impossible sans décalage"; return r; }
        if (minX == 0 || minY == 0) { r.doute = true; r.notes.add("mobis sur la ligne/colonne 0 (zone ?)"); }
        if (c.murs.isEmpty()) { r.doute = true; r.notes.add("aucun mural : pas de repère de mur"); }
        if (planW > 0 && planL > 0) {
            if (maxX >= planW || maxY >= planL) { r.doute = true; r.notes.add("mobis hors du roomLayout " + planW + "×" + planL); }
            else if ((maxX - minX + 1) * 2 < planW && (maxY - minY + 1) * 2 < planL) {
                r.doute = true;
                r.notes.add("mobis sur moins de la moitié du roomLayout " + planW + "×" + planL + " (zone ?)");
            }
        } else r.notes.add("pas de roomLayout : taille = rectangle des mobis");

        // --- cases et hauteurs
        Map<Long, Integer> h = new HashMap<>();
        for (Map.Entry<Long, Double> e : basse.entrySet())
            h.put(e.getKey(), Math.max(0, Math.min(35, (int) Math.floor(e.getValue() + 1e-6))));
        Set<Long> meublees = new java.util.HashSet<>(h.keySet());

        // --- murs : etendre jusqu'au pan de mur (case vide du mur + 1)
        Map<Integer, int[]> panL = new HashMap<>(), panR = new HashMap<>();   // X du pan -> {yMin, yMax}
        for (CopieAppart.MobiMur w : c.murs) {
            PositionMur p = w.position;
            Map<Integer, int[]> pan = p.gauche() ? panL : panR;
            int le = p.gauche() ? p.x() : p.y(), long_ = p.gauche() ? p.y() : p.x();
            pan.merge(le, new int[]{long_, long_}, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        for (Map.Entry<Integer, int[]> e : panL.entrySet()) {
            int X = e.getKey();
            for (int y = e.getValue()[0]; y <= e.getValue()[1]; y++) {
                int premier = Integer.MAX_VALUE;
                for (long k : h.keySet()) if (ky(k) == y && kx(k) > X) premier = Math.min(premier, kx(k));
                if (premier == Integer.MAX_VALUE) continue;
                int hv = h.get(cle(premier, y));
                for (int x = Math.max(0, X + 1); x < premier; x++) if (h.putIfAbsent(cle(x, y), hv) == null) r.parMurs++;
            }
        }
        for (Map.Entry<Integer, int[]> e : panR.entrySet()) {
            int Y = e.getKey();
            for (int x = e.getValue()[0]; x <= e.getValue()[1]; x++) {
                int premier = Integer.MAX_VALUE;
                for (long k : h.keySet()) if (kx(k) == x && ky(k) > Y) premier = Math.min(premier, ky(k));
                if (premier == Integer.MAX_VALUE) continue;
                int hv = h.get(cle(x, premier));
                for (int y = Math.max(0, Y + 1); y < premier; y++) if (h.putIfAbsent(cle(x, y), hv) == null) r.parMurs++;
            }
        }

        // --- taille du plan (coordonnees absolues : le plan part de (0,0))
        int W = 0, L = 0;
        for (long k : h.keySet()) { W = Math.max(W, kx(k) + 1); L = Math.max(L, ky(k) + 1); }
        if (planW > 0 && planL > 0) {
            for (long k : new ArrayList<>(h.keySet()))
                if (kx(k) >= planW || ky(k) >= planL) { h.remove(k); r.hors++; }
            W = planW; L = planL;
        } else { W += 1; L += 1; }   // une rangee vide a droite/en bas, comme les modeles du jeu
        if (r.hors > 0) r.notes.add(r.hors + " case(s) hors du roomLayout retirée(s)");
        FloorModele m = new FloorModele(W, L);
        for (Map.Entry<Long, Integer> e : h.entrySet()) m.h[kx(e.getKey())][ky(e.getKey())] = e.getValue();

        // --- trous interieurs
        r.bouchees = boucherTrous(m);

        // --- porte : case vide a gauche d'une case du bord gauche, sans mobi
        int[] porte = choisirPorte(m, meublees);
        if (porte == null) { r.ignoree = "aucune place pour la porte"; return r; }
        if (porte[3] == 1) {        // case ajoutee pour la porte
            m.h[porte[0]][porte[1]] = porte[2];
        } else r.notes.add("porte posée sur une case existante (pas de place à gauche)");
        m.porteX = porte[0]; m.porteY = porte[1]; m.porteDir = 2;
        m.porteConnue = porteConnue;
        m.hauteurMur = rl != null && rl.has("wallHeight") ? rl.optInt("wallHeight", -1) : -1;
        // epMur, epSol : valeurs par defaut de FloorModele (0)

        // --- verification du collage (z = z - ancre + sol(0,0))
        double ancre = c.ancre == null ? 0 : c.ancre;
        int sol0 = m.existe(0, 0) ? m.at(0, 0) : 0;
        if (Math.abs(sol0 - ancre) > 1e-6)
            r.notes.add("au collage, les mobis seraient décalés de " + fmt(sol0 - ancre)
                    + " en hauteur (srcAnchorFloorHeight " + fmt(ancre) + ", case (0,0) " + sol0 + ")");

        r.modele = m;
        r.cases = m.nbCases();
        r.hMin = Integer.MAX_VALUE; r.hMax = 0;
        for (int x = 0; x < W; x++) for (int y = 0; y < L; y++)
            if (m.h[x][y] >= 0) { r.hMin = Math.min(r.hMin, m.h[x][y]); r.hMax = Math.max(r.hMax, m.h[x][y]); }
        if (r.inconnus > 0) r.notes.add(r.inconnus + " mobi(s) inconnu(s) de la furnidata : comptés 1×1");

        // --- meme format que OngletApparts.copierSalleVersAppart
        JSONObject f = new JSONObject();
        f.put("plan", m.texte());
        f.put("porteX", m.porteX); f.put("porteY", m.porteY); f.put("porteDir", m.porteDir);
        f.put("porteConnue", m.porteConnue);
        f.put("hauteurMur", m.hauteurMur);
        f.put("epMur", m.epMur); f.put("epSol", m.epSol);
        f.put("x0", 0); f.put("y0", 0);
        f.put("atelierFloorReconstitue", true);
        r.floor = f;
        return r;
    }

    /**
     * Bouche les vides non atteignables depuis le bord (4-voisinage), avec la
     * hauteur la plus frequente des voisines (la plus basse en cas d'egalite).
     */
    static int boucherTrous(FloorModele m) {
        int W = m.largeur, L = m.longueur;
        boolean[][] dehors = new boolean[W + 2][L + 2];   // grille bordee d'un cran
        ArrayDeque<int[]> f = new ArrayDeque<>();
        dehors[0][0] = true;
        f.add(new int[]{0, 0});
        int[][] v4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!f.isEmpty()) {
            int[] p = f.poll();
            for (int[] d : v4) {
                int a = p[0] + d[0], b = p[1] + d[1];
                if (a < 0 || b < 0 || a >= W + 2 || b >= L + 2 || dehors[a][b]) continue;
                if (a >= 1 && b >= 1 && a <= W && b <= L && m.h[a - 1][b - 1] >= 0) continue;
                dehors[a][b] = true;
                f.add(new int[]{a, b});
            }
        }
        int n = 0;
        boolean change = true;
        while (change) {
            change = false;
            List<int[]> lot = new ArrayList<>();
            for (int x = 0; x < W; x++)
                for (int y = 0; y < L; y++) {
                    if (m.h[x][y] >= 0 || dehors[x + 1][y + 1]) continue;
                    Map<Integer, Integer> freq = new TreeMap<>();
                    for (int[] d : v4) { int hv = m.at(x + d[0], y + d[1]); if (hv >= 0) freq.merge(hv, 1, Integer::sum); }
                    if (freq.isEmpty()) continue;
                    int best = -1, bn = 0;
                    for (Map.Entry<Integer, Integer> e : freq.entrySet()) if (e.getValue() > bn) { best = e.getKey(); bn = e.getValue(); }
                    lot.add(new int[]{x, y, best});
                }
            for (int[] t : lot) { m.h[t[0]][t[1]] = t[2]; n++; change = true; }
        }
        return n;
    }

    /**
     * {x, y, hauteur, ajoutee(1/0)} : de preference la case vide juste a gauche
     * (x-1) d'une case existante sans mobi... puis d'une case avec mobi ; a
     * defaut, une case existante sans mobi du bord gauche. null si rien.
     */
    static int[] choisirPorte(FloorModele m, Set<Long> meublees) {
        for (int passe = 0; passe < 2; passe++) {
            List<int[]> cand = new ArrayList<>();
            int meilleurX = Integer.MAX_VALUE;
            for (int y = 0; y < m.longueur; y++)
                for (int x = 1; x < m.largeur; x++) {
                    if (!m.existe(x, y) || m.existe(x - 1, y)) continue;
                    if (m.existe(x - 1, y - 1) || m.existe(x - 1, y + 1)) continue;   // la porte depasse seule
                    if (passe == 0 && meublees.contains(cle(x, y))) continue;
                    if (x - 1 < meilleurX) { meilleurX = x - 1; cand.clear(); }
                    if (x - 1 == meilleurX) cand.add(new int[]{x - 1, y, m.at(x, y), 1});
                }
            if (!cand.isEmpty()) return cand.get(cand.size() / 2);
        }
        for (int x = 0; x < m.largeur; x++)
            for (int y = 0; y < m.longueur; y++)
                if (m.existe(x, y) && !m.existe(x - 1, y) && !meublees.contains(cle(x, y))) return new int[]{x, y, m.at(x, y), 0};
        return null;
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
                + r.muraux + " muraux ; plan " + m.largeur + "×" + m.longueur + ", " + r.cases + " cases, hauteurs "
                + r.hMin + ".." + r.hMax + ", " + r.bouchees + " case(s) bouchée(s), " + r.parMurs
                + " ajoutée(s) jusqu'aux murs ; porte " + m.porteX + "," + m.porteY + " dir " + m.porteDir
                + (m.porteConnue ? "" : " (porteConnue=false)") + ", hauteur des murs " + m.hauteurMur + ".");
        for (String n : r.notes) System.out.println("  ! " + n);
        // apercu : rectangle des cases, P = porte, . = vide
        int x1 = m.largeur, y1 = m.longueur, x2 = -1, y2 = -1;
        for (int x = 0; x < m.largeur; x++) for (int y = 0; y < m.longueur; y++)
            if (m.existe(x, y)) { x1 = Math.min(x1, x); y1 = Math.min(y1, y); x2 = Math.max(x2, x); y2 = Math.max(y2, y); }
        System.out.println("  aperçu (cases " + x1 + ".." + x2 + " × " + y1 + ".." + y2 + ", P = porte) :");
        for (int y = y1; y <= y2; y++) {
            StringBuilder b = new StringBuilder("    ");
            for (int x = x1; x <= x2; x++)
                b.append(x == m.porteX && y == m.porteY ? 'P' : m.existe(x, y) ? FloorModele.car(Math.min(35, m.at(x, y) == 33 ? 32 : m.at(x, y))) : '.');
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

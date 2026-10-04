package atelier;

import gearth.extensions.parsers.HProductType;
import gearth.extensions.parsers.catalog.HCatalogIndex;
import gearth.extensions.parsers.catalog.HCatalogPage;
import gearth.extensions.parsers.catalog.HCatalogPageIndex;
import gearth.extensions.parsers.catalog.HOffer;
import gearth.extensions.parsers.catalog.HProduct;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Les produits du catalogue Builders Club : pour chaque type de mobi, l'offre
 * et la page qui permettent de le poser. Remplace game.BCCatalog, en ecoute
 * seule : rien n'est demande, rien n'est bloque (les pages demandees par le
 * jeu lui parviennent toujours ; la collecte active passera plus tard par
 * ChargementAuto).
 *
 * Paquets :
 *   TOSERVER GetCatalogIndex  String type          -> AWAITING_INDEX si « BUILDERS_CLUB »
 *   TOCLIENT CatalogIndex     (HCatalogIndex)      -> pages ayant des offres, empreinte
 *   TOCLIENT CatalogPage      int page, String type, ... (HCatalogPage)
 * Seules les offres non-animal a un seul produit comptent (comme l'original).
 *
 * Meme cache que l'original : <dossier du jar>/catalog/BC_CATALOG_<empreinte>.txt,
 * empreinte = ("id1,id2,...").hashCode(), lignes F\tclasse\tpage\toffre[\textra]
 * et W\tclasse\tpage\toffre\textra. Il est relu des que la furnidata est prete ;
 * il n'est ecrit que si ecritureCache est vrai (aujourd'hui, l'ancien moteur
 * l'ecrit deja).
 *
 * Etats : memes noms que l'original (NONE, AWAITING_INDEX, COLLECTING_PAGES,
 * COLLECTED). En ecoute seule, la collecte est finie quand toutes les pages de
 * l'index sont arrivees, ou FIN_MS apres la derniere (pages perdues).
 */
final class CatalogueBc {

    enum Etat { NONE, AWAITING_INDEX, COLLECTING_PAGES, COLLECTED }

    /** Un produit BC a un seul mobi (ex-BCCatalog.SingleFurniProduct). */
    record Produit(int pageId, int offerId, String extraParam) {
        int getPageId() { return pageId; }
        int getOfferId() { return offerId; }
        String getExtraParam() { return extraParam; }
    }

    static final String BC = "BUILDERS_CLUB";
    static final long FIN_MS = 5_000;

    /** Ecrire le cache en fin de collecte (faux tant que l'ancien moteur l'ecrit). */
    static volatile boolean ecritureCache = false;

    private final Supplier<Furnidata> furnidata;
    private final File dossier;
    private final Object verrou = new Object();

    private volatile Etat etat = Etat.NONE;
    private final Map<Integer, Produit> sols = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Produit>> murs = new ConcurrentHashMap<>();
    private Set<Integer> pagesAttendues = Set.of();
    private final Set<Integer> pagesRecues = new HashSet<>();
    private volatile String empreinte;
    private volatile boolean cacheARelire, cacheExiste;
    private volatile long dernierePageLe;

    CatalogueBc(Canal canal, Supplier<Furnidata> furnidata) {
        this(canal, furnidata, dossierParDefaut());
    }

    CatalogueBc(Canal canal, Supplier<Furnidata> furnidata, File dossier) {
        this.furnidata = furnidata;
        this.dossier = dossier;
        canal.intercept(HMessage.Direction.TOSERVER, "GetCatalogIndex", this::surDemandeIndex);
        canal.intercept(HMessage.Direction.TOCLIENT, "CatalogIndex", this::surIndex);
        canal.intercept(HMessage.Direction.TOCLIENT, "CatalogPage", this::surPage);
    }

    /** Le dossier « catalog » a cote du jar de l'Atelier (comme l'original), sinon dans user.dir. */
    static File dossierParDefaut() {
        try {
            File jar = new File(CatalogueBc.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File parent = jar.getParentFile();
            if (parent != null) return new File(parent, "catalog");
        } catch (Throwable ignored) { }
        return new File(System.getProperty("user.dir", "."), "catalog");
    }

    // ================================================================ ecouteurs

    private void surDemandeIndex(HMessage m) {
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 8 || !BC.equals(p.readString(6))) return;
        synchronized (verrou) { if (etat == Etat.NONE) etat = Etat.AWAITING_INDEX; }
    }

    private void surIndex(HMessage m) {
        HCatalogIndex index = new HCatalogIndex(m.getPacket());
        if (!BC.equals(index.getCatalogType())) return;
        List<Integer> pages = new ArrayList<>();
        pagesUtiles(pages, index.getRoot());
        String emp = empreinte(pages);
        boolean existe = fichierCache(emp).isFile();
        synchronized (verrou) {
            if (emp.equals(empreinte) && (etat == Etat.COLLECTED || etat == Etat.COLLECTING_PAGES)) return;
            effacer();
            empreinte = emp;
            pagesAttendues = new HashSet<>(pages);
            pagesRecues.clear();
            dernierePageLe = System.currentTimeMillis();
            etat = Etat.COLLECTING_PAGES;
            cacheARelire = true;
            cacheExiste = existe;
        }
        Journal.debug("Catalogue BC : index reçu, " + nb(pages.size(), "page utile", "pages utiles") + " (empreinte " + emp + ").");
        relireCacheSiPossible();                 // furnidata deja prete : tout de suite
    }

    private void surPage(HMessage m) {
        if (etat != Etat.COLLECTING_PAGES) return;
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 12) return;
        int id = p.readInteger(6);
        if (!BC.equals(p.readString(10))) return;          // lecture sur place, sans copie
        synchronized (verrou) {
            if (!pagesAttendues.contains(id) || cacheARelire && cacheExiste) return;
        }
        HCatalogPage page = new HCatalogPage(p);
        for (HOffer o : page.getOffers()) {
            if (o.isPet() || o.getProducts().size() != 1) continue;
            HProduct pr = o.getProducts().get(0);
            Produit prod = new Produit(page.getPageId(), o.getOfferId(), pr.getExtraParam());
            if (pr.getProductType() == HProductType.FloorItem) sols.put(pr.getFurniClassId(), prod);
            else if (pr.getProductType() == HProductType.WallItem)
                murs.computeIfAbsent(pr.getFurniClassId(), k -> new ConcurrentHashMap<>()).put(pr.getExtraParam(), prod);
        }
        boolean fini;
        synchronized (verrou) {
            pagesRecues.add(id);
            dernierePageLe = System.currentTimeMillis();
            fini = etat == Etat.COLLECTING_PAGES && pagesRecues.containsAll(pagesAttendues);
            if (fini) etat = Etat.COLLECTED;
        }
        if (fini) finCollecte();
    }

    // ================================================================ interne

    private static void pagesUtiles(List<Integer> pages, HCatalogPageIndex n) {
        if (n.getPageId() != -1 && !n.getOfferIds().isEmpty()) pages.add(n.getPageId());
        for (HCatalogPageIndex e : n.getChildren()) pagesUtiles(pages, e);
    }

    /** Meme empreinte que l'original : ("id1,id2,...").hashCode() en texte. */
    static String empreinte(List<Integer> pages) {
        return "" + pages.stream().map(String::valueOf).collect(Collectors.joining(",")).hashCode();
    }

    File fichierCache(String emp) { return new File(dossier, "BC_CATALOG_" + emp + ".txt"); }

    /** Sous verrou. */
    private void effacer() {
        sols.clear();
        murs.clear();
    }

    /**
     * Rattrapages faits a la lecture (pas de fil a nous) : relire le cache des
     * que la furnidata est prete ; finir une collecte dont des pages se sont perdues.
     */
    private void avancer() {
        relireCacheSiPossible();
        boolean fini = false;
        synchronized (verrou) {
            if (etat == Etat.COLLECTING_PAGES && !cacheARelire && !pagesRecues.isEmpty()
                    && System.currentTimeMillis() - dernierePageLe > FIN_MS) {
                etat = Etat.COLLECTED;
                fini = true;
            }
        }
        if (fini) finCollecte();
    }

    private void relireCacheSiPossible() {
        if (!cacheARelire) return;
        Furnidata f = furnidata == null ? null : furnidata.get();
        String emp = empreinte;
        File fichier = emp == null ? null : fichierCache(emp);
        if (fichier == null || !fichier.isFile()) { cacheARelire = false; return; }   // collecte des pages
        if (f == null || !f.pret()) return;                                           // plus tard
        Map<Integer, Produit> s = new HashMap<>();
        Map<Integer, Map<String, Produit>> w = new HashMap<>();
        try {
            for (String ligne : Files.readString(fichier.toPath(), StandardCharsets.UTF_8).split("\n")) {
                if (ligne.isEmpty()) continue;
                String[] c = ligne.split("\t");
                if (c.length < 4) continue;
                Produit p = new Produit(Integer.parseInt(c[2]), Integer.parseInt(c[3]), c.length >= 5 ? c[4] : "");
                if ("F".equals(c[0])) {
                    Integer t = f.typeSol(c[1]);
                    if (t != null) s.put(t, p);
                } else if ("W".equals(c[0])) {
                    Integer t = f.typeMur(c[1]);
                    if (t != null) w.computeIfAbsent(t, k -> new HashMap<>()).put(p.extraParam(), p);
                }
            }
        } catch (Exception e) {
            Journal.debug("Catalogue BC : cache illisible (" + e + "), les pages seront lues.");
            cacheARelire = false;
            return;
        }
        synchronized (verrou) {
            if (!cacheARelire || !emp.equals(empreinte)) return;
            cacheARelire = false;
            effacer();
            sols.putAll(s);
            w.forEach((k, v) -> murs.put(k, new ConcurrentHashMap<>(v)));
            etat = Etat.COLLECTED;
        }
        Journal.debug("Catalogue BC : " + nb(s.size(), "sol", "sols") + " et "
                + nb(w.values().stream().mapToInt(Map::size).sum(), "variante murale", "variantes murales") + " relus du cache.");
    }

    private void finCollecte() {
        Journal.debug("Catalogue BC : collecte finie, " + nb(sols.size(), "sol", "sols") + " et "
                + nb(nombreVariantesMurales(), "variante murale", "variantes murales") + ", " + nb(pagesRecues(), "page", "pages") + ".");
        if (ecritureCache) ecrireCache();
    }

    private static String nb(int n, String un, String plusieurs) { return n + " " + (n > 1 ? plusieurs : un); }

    private int pagesRecues() { synchronized (verrou) { return pagesRecues.size(); } }

    private int nombreVariantesMurales() { return murs.values().stream().mapToInt(Map::size).sum(); }

    /** Ecrit le cache au format de l'original (classes resolues par la furnidata). */
    boolean ecrireCache() {
        Furnidata f = furnidata == null ? null : furnidata.get();
        String emp = empreinte;
        if (etat != Etat.COLLECTED || f == null || !f.pret() || emp == null) return false;
        StringBuilder b = new StringBuilder();
        sols.forEach((type, p) -> {
            String c = f.classeSol(type);
            if (c == null) return;
            b.append("F\t").append(c).append('\t').append(p.pageId()).append('\t').append(p.offerId());
            if (!p.extraParam().isEmpty()) b.append('\t').append(p.extraParam());
            b.append('\n');
        });
        murs.forEach((type, variantes) -> {
            String c = f.classeMur(type);
            if (c == null) return;
            for (Produit p : variantes.values())
                b.append("W\t").append(c).append('\t').append(p.pageId()).append('\t').append(p.offerId())
                        .append('\t').append(p.extraParam()).append('\n');
        });
        try {
            dossier.mkdirs();
            Files.writeString(fichierCache(emp).toPath(), b.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            Journal.debug("Catalogue BC : cache non écrit : " + e);
            return false;
        }
    }

    // ================================================================ API francaise

    Etat etat() { avancer(); return etat; }

    boolean pret() { return etat() == Etat.COLLECTED; }

    /** Le produit BC d'un mobi de sol, ou null. */
    Produit produitSol(int type) { return sols.get(type); }

    /** La variante murale exacte, sinon l'unique variante, sinon celle sans parametre ; null sinon. */
    Produit produitMur(int type, String extra) {
        Map<String, Produit> v = murs.get(type);
        if (v == null) return null;
        Produit p = v.get(extra == null ? "" : extra);
        if (p != null) return p;
        if (v.size() == 1) return v.values().iterator().next();
        return v.get("");
    }

    /** Une variante murale quelconque, ou null. */
    Produit unProduitMur(int type) {
        Map<String, Produit> v = murs.get(type);
        if (v == null || v.isEmpty()) return null;
        return v.values().iterator().next();
    }

    /** Copies, pour les comparaisons et les listes. */
    Map<Integer, Produit> produitsSols() { return new HashMap<>(sols); }

    Map<Integer, Map<String, Produit>> produitsMurs() {
        Map<Integer, Map<String, Produit>> r = new HashMap<>();
        murs.forEach((k, v) -> r.put(k, new HashMap<>(v)));
        return r;
    }

    String empreinteIndex() { return empreinte; }

    /** Oublie tout (ex-clear). */
    void vider() {
        synchronized (verrou) {
            effacer();
            etat = Etat.NONE;
            empreinte = null;
            pagesAttendues = Set.of();
            pagesRecues.clear();
            cacheARelire = false;
        }
    }

    /** Efface les fichiers du cache (ex-clearCache). */
    void viderCache() {
        File[] l = dossier.listFiles();
        if (l != null) for (File f : l) if (!f.delete()) Journal.debug("Catalogue BC : " + f + " non effacé.");
    }

    // ================================================================ noms d'origine (BCCatalog)

    Etat getState() { return etat(); }

    Produit getFloorProduct(int type) { return produitSol(type); }

    Produit getWallProduct(int type, String extra) { return produitMur(type, extra); }

    Produit getAnyWallProduct(int type) { return unProduitMur(type); }

    void clear() { vider(); }

    void clearCache() { viderCache(); }
}

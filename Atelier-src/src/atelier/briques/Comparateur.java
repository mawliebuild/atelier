package atelier;

import extension.GPresets;
import game.BCCatalog;
import game.FloorState;
import game.Inventory;
import game.RoomPermissions;
import gearth.GEarth;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HConnection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Verification des nouvelles briques (EtatSalle, Droits, Inventaire,
 * CatalogueBc) contre l'ancien moteur, pendant qu'ils tournent cote a cote.
 *
 * Actif seulement avec -Datelier.briques.verif=true (et -Datelier.debug=true
 * pour voir le journal). Les briques ecoutent le canal du moteur actuel ;
 * toutes les 30 s, un fil de fond compare leurs resultats a ceux de FloorState,
 * RoomPermissions, Inventory et BCCatalog. Un ecart vu deux fois de suite (a
 * 1,5 s d'intervalle, pour ignorer un paquet en cours de route) est ecrit dans
 * Journal.debug, prefixe « Briques : ».
 *
 * Ne bloque rien, n'envoie rien, ne modifie rien. Classe provisoire : elle
 * disparaitra avec l'ancien moteur.
 */
final class Comparateur {

    static final boolean ACTIF = Boolean.getBoolean("atelier.briques.verif");
    static final long PERIODE_S = 30;
    private static final int MAX_LIGNES = 40;

    private static volatile Comparateur actif;

    final EtatSalle salle;
    final Droits droits;
    final Inventaire inventaire;
    final CatalogueBc catalogue;
    private volatile Furnidata furnidata;
    private int tour;

    private Comparateur(Canal canal) {
        salle = new EtatSalle(canal);
        droits = new Droits(canal);
        inventaire = new Inventaire(canal);
        catalogue = new CatalogueBc(canal, this::furnidata);
    }

    /** Les briques en verification, ou null (verification inactive). */
    static Comparateur actif() { return actif; }

    /**
     * A appeler des que le moteur est pret (avant son initExtension, pour voir
     * la salle qu'il demande au demarrage). Sans effet si la verification est inactive.
     */
    static synchronized void demarrer() {
        if (!ACTIF || actif != null) return;
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) { Journal.debug("Briques : moteur absent, vérification non lancée."); return; }
        Comparateur c = new Comparateur(Canal.de(gp));
        actif = c;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "atelier-briques-verif");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try { c.comparer(); } catch (Throwable t) { Journal.debug("Briques : comparaison en erreur : " + t); }
        }, PERIODE_S, PERIODE_S, TimeUnit.SECONDS);
        Journal.debug("Briques : vérification lancée (comparaison toutes les " + PERIODE_S + " s).");
    }

    // ================================================================ furnidata

    /** Notre furnidata, une fois l'hote connu. */
    private Furnidata furnidata() {
        Furnidata f = furnidata;
        if (f != null) return f;
        String h = hote();
        if (h == null) return null;
        f = Furnidata.pour(h);
        furnidata = f;
        Journal.debug("Briques : furnidata de l'hôtel « " + f.pays() + " » (hôte " + h + ").");
        return f;
    }

    /**
     * L'hote du jeu (« game-fr.habbo.com »), lu dans la connexion du proxy ; null si inconnu.
     * getDomain() est le nom demande par le jeu ; getServerHost() est l'adresse IP
     * resolue, inutilisable : « 52.17.x.x » donnait le pays « 17 », donc la furnidata
     * d'un autre hotel (ids de type decales) et un catalogue BC faux.
     */
    static String hote() {
        try {
            Field fc = GEarth.class.getDeclaredField("controller");
            fc.setAccessible(true);
            Object c = fc.get(GEarth.main);
            if (c == null) return null;
            Field fh = c.getClass().getDeclaredField("hConnection");
            fh.setAccessible(true);
            HConnection h = (HConnection) fh.get(c);
            if (h == null) return null;
            return hoteHabbo(h.getDomain(), h.getClientHost(), h.getServerHost());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Le premier nom d'hote Habbo de la liste (« game-xx.habbo... »), sinon null : jamais une IP. */
    static String hoteHabbo(String... candidats) {
        for (String s : candidats)
            if (s != null && s.toLowerCase().matches("game-[a-z0-9]{2}\\..*habbo.*")) return s;
        return null;
    }

    // ================================================================ comparaison

    private void comparer() throws InterruptedException {
        GPresets gp = AtelierLauncher.moteur();
        if (gp == null) return;
        tour++;
        List<String> premiers = ecarts(gp);
        List<String> durables = new ArrayList<>();
        if (!premiers.isEmpty()) {
            Thread.sleep(1500);
            Set<String> seconds = new HashSet<>(ecarts(gp));
            for (String e : premiers) if (seconds.contains(e)) durables.add(e);
        }
        String resume = resume();
        if (durables.isEmpty()) {
            Journal.debug("Briques : tour " + tour + ", aucun écart. " + resume);
            return;
        }
        Journal.debug("Briques : tour " + tour + ", " + accord(durables.size(), "écart", "écarts") + ". " + resume);
        int n = 0;
        for (String e : durables) {
            if (++n > MAX_LIGNES) { Journal.debug("Briques :   … et " + (durables.size() - MAX_LIGNES) + " de plus."); break; }
            Journal.debug("Briques :   " + e);
        }
    }

    private String resume() {
        return "Salle " + salle.salleId() + " : " + accord(salle.nombreSols(), "mobi de sol", "mobis de sol") + ", "
                + accord(salle.nombreMurs(), "mural", "muraux") + " ; inventaire " + inventaire.etat() + ", "
                + accord(inventaire.nombre(), "mobi", "mobis") + " ; catalogue BC " + catalogue.etat() + ", "
                + accord(catalogue.produitsSols().size(), "produit de sol", "produits de sol") + ".";
    }

    static String accord(int n, String un, String plusieurs) { return n + " " + (n > 1 ? plusieurs : un); }

    List<String> ecarts(GPresets gp) {
        List<String> e = new ArrayList<>();
        try { ecartsSalle(gp.getFloorState(), e); } catch (Throwable t) { e.add("Salle : lecture impossible (" + t + ")."); }
        try { ecartsDroits(gp.getPermissions(), e); } catch (Throwable t) { e.add("Droits : lecture impossible (" + t + ")."); }
        try { ecartsInventaire(gp.getInventory(), e); } catch (Throwable t) { e.add("Inventaire : lecture impossible (" + t + ")."); }
        try { ecartsFurnidata(gp.getFurniDataTools(), e); } catch (Throwable t) { e.add("Furnidata : lecture impossible (" + t + ")."); }
        try { ecartsCatalogue(gp.getCatalog(), e); } catch (Throwable t) { e.add("Catalogue BC : lecture impossible (" + t + ")."); }
        return e;
    }

    // ---------------------------------------------------------------- salle

    private void ecartsSalle(FloorState fs, List<String> e) {
        if (fs == null) return;
        boolean a = fs.inRoom(), b = salle.inRoom();
        if (a != b) {
            e.add("Salle : « dans une salle » " + a + " (ancien) contre " + b + " (brique)"
                    + (b ? "." : " ; manque : " + salle.manque()));
            return;
        }
        if (!a) return;
        egal(e, "Salle : numéro", fs.getRoomId(), salle.getRoomId());
        egal(e, "Salle : modèle", fs.getRoomModelName(), salle.getRoomModelName());
        egal(e, "Salle : plan brut", empreinteTexte(fs.getRawFloorplan()), empreinteTexte(salle.getRawFloorplan()));
        egal(e, "Salle : échelle", fs.getFloorScale(), salle.getFloorScale());
        egal(e, "Salle : hauteur des murs", fs.getFloorWallHeight(), salle.getFloorWallHeight());
        egal(e, "Salle : largeur du plan", fs.getFloorplanWidth(), salle.getFloorplanWidth());
        egal(e, "Salle : longueur du plan", fs.getFloorplanHeight(), salle.getFloorplanHeight());
        int casesPlan = 0, casesHauteur = 0;
        for (int x = 0; x < salle.getFloorplanWidth(); x++)
            for (int y = 0; y < salle.getFloorplanHeight(); y++) {
                if (fs.floorHeight(x, y) != salle.floorHeight(x, y)) casesPlan++;
                try { if (fs.getTileHeight(x, y) != salle.getTileHeight(x, y)) casesHauteur++; }
                catch (Throwable ignored) { }
            }
        if (casesPlan > 0) e.add("Salle : " + accord(casesPlan, "case du plan diffère", "cases du plan diffèrent") + ".");
        if (casesHauteur > 0) e.add("Salle : " + accord(casesHauteur, "hauteur de case diffère", "hauteurs de case diffèrent") + ".");

        // mobis de sol
        Map<Integer, HFloorItem> anciens = new HashMap<>();
        for (HFloorItem f : fs.getItems()) anciens.put(f.getId(), f);
        Map<Integer, EtatSalle.MobiSol> nouveaux = new HashMap<>();
        for (EtatSalle.MobiSol m : salle.sols()) nouveaux.put(m.id(), m);
        egal(e, "Salle : nombre de mobis de sol", anciens.size(), nouveaux.size());
        manquants(e, "Salle : mobis de sol absents de la brique", anciens.keySet(), nouveaux.keySet());
        manquants(e, "Salle : mobis de sol en trop dans la brique", nouveaux.keySet(), anciens.keySet());
        Set<Long> cases = new LinkedHashSet<>();
        Set<Integer> types = new HashSet<>();
        for (HFloorItem f : anciens.values()) {
            EtatSalle.MobiSol m = nouveaux.get(f.getId());
            if (m == null) continue;
            String av = f.getTypeId() + " " + f.getTile().getX() + "," + f.getTile().getY() + "," + f.getTile().getZ()
                    + " r" + (f.getFacing() == null ? 0 : f.getFacing().ordinal())
                    + " « " + (f.getStuff() == null ? null : f.getStuff().getLegacyString()) + " » " + f.getOwnerName();
            String ap = m.type() + " " + m.x() + "," + m.y() + "," + m.z() + " r" + m.rotation()
                    + " « " + m.etat() + " » " + m.proprietaire();
            if (!av.equals(ap)) e.add("Salle : mobi " + f.getId() + " : " + av + " (ancien) contre " + ap + " (brique).");
            cases.add(((long) m.x() << 32) | (m.y() & 0xFFFFFFFFL));
            types.add(m.type());
        }
        int casesMobis = 0;
        for (long c : cases) {
            int x = (int) (c >> 32), y = (int) c;
            if (fs.getFurniOnTile(x, y).size() != salle.getFurniOnTile(x, y).size()) casesMobis++;
        }
        if (casesMobis > 0) e.add("Salle : " + accord(casesMobis, "case", "cases") + " sans le même nombre de mobis.");
        for (int t : types)
            egal(e, "Salle : mobis de sol du type " + t, fs.getItemsFromType(t).size(), salle.getItemsFromType(t).size());

        // muraux
        Map<Integer, HWallItem> mAnciens = new HashMap<>();
        for (HWallItem w : fs.getWallItems()) mAnciens.put(w.getId(), w);
        Map<Integer, EtatSalle.MobiMur> mNouveaux = new HashMap<>();
        for (EtatSalle.MobiMur m : salle.murs()) mNouveaux.put(m.id(), m);
        egal(e, "Salle : nombre de muraux", mAnciens.size(), mNouveaux.size());
        manquants(e, "Salle : muraux absents de la brique", mAnciens.keySet(), mNouveaux.keySet());
        manquants(e, "Salle : muraux en trop dans la brique", mNouveaux.keySet(), mAnciens.keySet());
        for (HWallItem w : mAnciens.values()) {
            EtatSalle.MobiMur m = mNouveaux.get(w.getId());
            if (m == null) continue;
            String av = w.getTypeId() + " " + w.getLocation() + " « " + w.getState() + " »";
            String ap = m.type() + " " + m.position() + " « " + m.etat() + " »";
            if (!av.equals(ap)) e.add("Salle : mural " + w.getId() + " : " + av + " (ancien) contre " + ap + " (brique).");
        }
    }

    private static String empreinteTexte(String s) {
        return s == null ? "absent" : s.length() + " caractères, empreinte " + s.hashCode();
    }

    // ---------------------------------------------------------------- droits

    private void ecartsDroits(RoomPermissions p, List<String> e) {
        if (p == null) return;
        egal(e, "Droits : déplacer les mobis", p.canMoveFurni(), droits.canMoveFurni());
        egal(e, "Droits : régler les wired", p.canModifyWired(), droits.canModifyWired());
    }

    // ---------------------------------------------------------------- inventaire

    private void ecartsInventaire(Inventory inv, List<String> e) {
        if (inv == null) return;
        String a = String.valueOf(inv.getState()), b = String.valueOf(inventaire.getState());
        egal(e, "Inventaire : état", a, b);
        if (!"LOADED".equals(a) || !"LOADED".equals(b)) return;
        List<HInventoryItem> anciens = inv.getInventoryItems();
        egal(e, "Inventaire : nombre de mobis", anciens.size(), inventaire.nombre());
        Set<Integer> sols = new HashSet<>(inventaire.compteSols().keySet());
        Set<Integer> murs = new HashSet<>(inventaire.compteMurs().keySet());
        for (HInventoryItem it : anciens) (it.getType() == HProductType.FloorItem ? sols : murs).add(it.getTypeId());
        for (int t : sols) egal(e, "Inventaire : sols du type " + t, inv.getFloorItemsByType(t).size(), inventaire.getFloorItemsByType(t).size());
        for (int t : murs) egal(e, "Inventaire : muraux du type " + t, inv.getWallItemsByType(t).size(), inventaire.getWallItemsByType(t).size());
    }

    // ---------------------------------------------------------------- furnidata

    /** Notre furnidata doit etre celle de l'hotel : sinon les types du catalogue BC sont decales. */
    private void ecartsFurnidata(furnidata.FurniDataTools g, List<String> e) {
        Furnidata f = furnidata();
        if (g == null || !g.isReady() || f == null || !f.pret()) return;
        int diff = 0;
        String exemple = null;
        for (Furnidata.Mobi m : f.tousSols()) {
            Integer t = g.getFloorTypeId(m.className);
            if (!Objects.equals(t, m.id)) { diff++; if (exemple == null) exemple = m.className + " : " + t + " contre " + m.id; }
        }
        if (diff > 0) e.add("Furnidata (" + f.pays() + ") : " + accord(diff, "type de sol diffère", "types de sol diffèrent")
                + ", par exemple " + exemple + " (ancien contre brique).");
    }

    // ---------------------------------------------------------------- catalogue BC

    private void ecartsCatalogue(BCCatalog cat, List<String> e) {
        if (cat == null) return;
        String a = String.valueOf(cat.getState()), b = String.valueOf(catalogue.getState());
        egal(e, "Catalogue BC : état", a, b);
        if (!"COLLECTED".equals(a) || !"COLLECTED".equals(b)) return;
        Furnidata f = furnidata();
        Set<Integer> typesSol = new HashSet<>(catalogue.produitsSols().keySet());
        Map<Integer, Map<String, CatalogueBc.Produit>> nosMurs = catalogue.produitsMurs();
        Set<Integer> typesMur = new HashSet<>(nosMurs.keySet());
        if (f != null && f.pret()) {
            for (Furnidata.Mobi m : f.tousSols()) typesSol.add(m.id);
            for (Furnidata.Mobi m : f.tousMurs()) typesMur.add(m.id);
        } else {
            e.add("Catalogue BC : furnidata pas prête, seuls les produits de la brique sont comparés.");
        }
        int diffSols = 0, diffMurs = 0;
        List<String> exemples = new ArrayList<>();
        for (int t : typesSol) {
            String av = produit(cat.getFloorProduct(t)), ap = produit(catalogue.getFloorProduct(t));
            if (!av.equals(ap)) { diffSols++; if (exemples.size() < 10) exemples.add("sol " + t + " : " + av + " contre " + ap); }
        }
        for (int t : typesMur) {
            boolean av = cat.getAnyWallProduct(t) != null, ap = catalogue.getAnyWallProduct(t) != null;
            if (av != ap) { diffMurs++; if (exemples.size() < 10) exemples.add("mural " + t + " : " + av + " contre " + ap); continue; }
            Map<String, CatalogueBc.Produit> v = nosMurs.get(t);
            if (v == null) continue;
            for (String extra : v.keySet()) {
                String pa = produit(cat.getWallProduct(t, extra)), pb = produit(catalogue.getWallProduct(t, extra));
                if (!pa.equals(pb)) { diffMurs++; if (exemples.size() < 10) exemples.add("mural " + t + " « " + extra + " » : " + pa + " contre " + pb); }
            }
        }
        if (diffSols > 0) e.add("Catalogue BC : " + accord(diffSols, "produit de sol diffère", "produits de sol diffèrent") + ".");
        if (diffMurs > 0) e.add("Catalogue BC : " + accord(diffMurs, "produit mural diffère", "produits muraux diffèrent") + ".");
        for (String x : exemples) e.add("Catalogue BC :   " + x + " (ancien contre brique).");
    }

    private static String produit(BCCatalog.SingleFurniProduct p) {
        return p == null ? "aucun" : "page " + p.getPageId() + " offre " + p.getOfferId();
    }

    private static String produit(CatalogueBc.Produit p) {
        return p == null ? "aucun" : "page " + p.getPageId() + " offre " + p.getOfferId();
    }

    // ---------------------------------------------------------------- outils

    private static void egal(List<String> e, String quoi, Object ancien, Object brique) {
        if (!Objects.equals(ancien, brique)) e.add(quoi + " : " + ancien + " (ancien) contre " + brique + " (brique).");
    }

    private static void manquants(List<String> e, String quoi, Set<Integer> dans, Set<Integer> pas) {
        List<Integer> l = new ArrayList<>();
        for (int i : dans) if (!pas.contains(i)) l.add(i);
        if (l.isEmpty()) return;
        l.sort(null);
        e.add(quoi + " : " + l.size() + " (" + (l.size() > 10 ? l.subList(0, 10) + "…" : l.toString()) + ").");
    }
}

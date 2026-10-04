package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La furnidata de l'hotel : classes, ids de type, noms et details des mobis.
 *
 * Remplace FurniDataTools (ancien module), FloorItemDetails et WallItemDetails.
 * Meme source : https://www.habbo<domaine>/gamedata/furnidata_json/1, le pays
 * etant lu dans l'hote (« game-fr.habbo.com » -> fr ; s2 = sandbox). Format :
 * roomitemtypes.furnitype[] (sol) et wallitemtypes.furnitype[] (murs).
 *
 * En plus de l'original : cache disque dans Dossiers.donneesAtelier()/furnidata
 * (relu au demarrage, la furnidata est donc prete sans reseau), rafraichi au
 * plus une fois par jour ; delai, User-Agent et nouveaux essais. Tout se fait
 * sur un fil de fond, jamais sur le fil JavaFX. Une instance par pays, gardee
 * d'une connexion a l'autre (pour(hote)).
 *
 * API : les noms d'origine (isReady, getFloorTypeId, getWallTypeId,
 * getFloorItemName, getWallItemName, getFloorItemDetails, getWallItemDetails,
 * isStackable) pour une bascule mecanique, et leurs equivalents francais.
 * Les details sont un seul type, Mobi, aux champs nommes comme l'original.
 */
final class Furnidata {

    /** Les details d'un mobi (ex-FloorItemDetails / WallItemDetails). */
    static final class Mobi {
        /** Vrai pour un mural (wallitemtypes). */
        public final boolean mural;
        public final String className, category, name, description, furniline, adUrl, environment;
        public final int id, revision, offerId, rentOfferId;
        public final boolean isBC, isRare, isBuyOut, isRentBuyOut, isExcludedDynamic;
        /** Sol seulement (0, faux ou null pour un mural). */
        public final String customParams;
        public final int xDim, yDim, defaultDir, specialType, bcOfferId;
        public final boolean canStandOn, canSitOn, canLayOn;
        /** « partcolors.color[] », ou null. */
        public final List<String> partColors;

        Mobi(JSONObject o, boolean mural) {
            this.mural = mural;
            className = o.optString("classname", null);
            category = o.optString("category", null);
            name = o.optString("name", null);
            description = o.optString("description", null);
            furniline = o.optString("furniline", null);
            adUrl = o.optString("adurl", null);
            environment = o.optString("environment", null);
            id = o.optInt("id");
            revision = o.optInt("revision");
            offerId = o.optInt("offerid", -1);
            rentOfferId = o.optInt("rentofferid", -1);
            isBC = o.optBoolean("bc");
            isRare = o.optBoolean("rare");
            isBuyOut = o.optBoolean("buyout");
            isRentBuyOut = o.optBoolean("rentbuyout");
            isExcludedDynamic = o.optBoolean("excludeddynamic");
            customParams = mural ? null : o.optString("customparams", null);
            xDim = o.optInt("xdim", mural ? 0 : 1);
            yDim = o.optInt("ydim", mural ? 0 : 1);
            defaultDir = o.optInt("defaultdir");
            specialType = o.optInt("specialtype");
            bcOfferId = o.optInt("bcofferid", -1);
            canStandOn = o.optBoolean("canstandon");
            canSitOn = o.optBoolean("cansiton");
            canLayOn = o.optBoolean("canlayon");
            List<String> c = null;
            JSONObject pc = o.optJSONObject("partcolors");
            JSONArray ca = pc == null ? null : pc.optJSONArray("color");
            if (ca != null) {
                c = new ArrayList<>(ca.length());
                for (int i = 0; i < ca.length(); i++) c.add(ca.optString(i));
                c = Collections.unmodifiableList(c);
            }
            partColors = c;
        }

        // noms francais
        String classe() { return className; }
        String nom() { return name; }
        int largeur() { return xDim; }
        int longueur() { return yDim; }
        int offre() { return offerId; }
        int offreBc() { return bcOfferId; }

        @Override public String toString() { return (mural ? "mur " : "sol ") + className + " #" + id + " « " + name + " »"; }
    }

    /** Une furnidata lue (immuable une fois construite). */
    private static final class Donnees {
        final Map<String, Mobi> sols = new HashMap<>(), murs = new HashMap<>();
        final Map<Integer, String> classeSol = new HashMap<>(), classeMur = new HashMap<>();

        static Donnees lire(String texte) {
            JSONObject j = new JSONObject(texte);
            Donnees d = new Donnees();
            JSONArray a = j.getJSONObject("roomitemtypes").getJSONArray("furnitype");
            for (int i = 0; i < a.length(); i++) {
                Mobi m = new Mobi(a.getJSONObject(i), false);
                if (m.className == null) continue;
                d.sols.put(m.className, m);
                d.classeSol.put(m.id, m.className);
            }
            a = j.getJSONObject("wallitemtypes").getJSONArray("furnitype");
            for (int i = 0; i < a.length(); i++) {
                Mobi m = new Mobi(a.getJSONObject(i), true);
                if (m.className == null) continue;
                d.murs.put(m.className, m);
                d.classeMur.put(m.id, m.className);
            }
            if (d.sols.isEmpty()) throw new IllegalStateException("Furnidata vide");
            return d;
        }
    }

    /** Liste figee de l'original : ces mobis ne vont pas sur une dalle magique. */
    private static final Set<String> NON_EMPILABLES = Set.of("fball_gate", "es_tile", "bb_patch1",
            "es_skating_ice", "snowb_slope", "val11_floor", "easter11_grasspatch", "queue_tile1*0", "queue_tile1*1",
            "queue_tile1*2", "queue_tile1*3", "queue_tile1*4", "queue_tile1*5", "queue_tile1*6", "queue_tile1*7",
            "queue_tile1*8", "queue_tile1*9", "hc_rllr", "sf_roller", "room_info15_roller", "lt_c26_roller",
            "hblooza14_track", "hblooza14_track_crr", "hblooza14_track_crl", "easter_c20_rapids", "hween12_track_crl",
            "hween12_track", "bw_water_2", "bw_water_1", "hween_c15_sdwater", "val15_water",
            "jungle_c16_watertile", "jungle_c16_watertrap", "thai_c21_crystalwater", "bw_nt_water_2", "hween10_pond",
            "sunsetcafe_c20_shallow", "val13_water", "hole", "hole2", "hole1x1", "hole3", "hole4", "hole1x1test",
            "pet_breeding_bear", "pet_breeding_terrier", "pet_breeding_dog", "pet_breeding_pig", "pet_breeding_cat");

    private static final Map<String, String> DOMAINES = Map.of(
            "br", ".com.br", "de", ".de", "es", ".es", "fi", ".fi", "fr", ".fr",
            "it", ".it", "nl", ".nl", "tr", ".com.tr", "us", ".com");

    static final long UN_JOUR_MS = 24L * 3600 * 1000;
    private static final int DELAI_MS = 20_000;
    private static final int ESSAIS = 3;
    private static final String AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Map<String, Furnidata> PAR_PAYS = new ConcurrentHashMap<>();

    private final String pays;
    private final File cache;
    private volatile Donnees donnees;
    private volatile boolean enCours;
    private volatile long derniereVerif;
    private final List<Runnable> attente = new CopyOnWriteArrayList<>();

    private Furnidata(String pays, File cache) {
        this.pays = pays;
        this.cache = cache;
    }

    // ------------------------------------------------------------ creation

    /**
     * La furnidata de l'hotel de cet hote (« game-fr.habbo.com »), partagee
     * par pays. Lance au besoin le chargement (cache puis reseau) en fond.
     */
    static Furnidata pour(String hote) { return pour(hote, null); }

    /** Idem ; quandPrete est appele (sur le fil de fond) des que la furnidata est prete. */
    static Furnidata pour(String hote, Runnable quandPrete) {
        String p = pays(hote);
        Furnidata f = PAR_PAYS.computeIfAbsent(p, k -> new Furnidata(k, fichierCache(k)));
        f.charger(quandPrete);
        return f;
    }

    /** Pour les essais : une furnidata sur un fichier de cache choisi, sans partage. */
    static Furnidata essai(String pays, File cache) { return new Furnidata(pays, cache); }

    /** Le pays d'un hote : « game-fr.habbo.com » -> fr, « game-s2... » -> s2 ; fr a defaut. */
    static String pays(String hote) {
        if (hote != null) {
            Matcher m = Pattern.compile("game-([a-z0-9]{2})").matcher(hote.toLowerCase());
            if (m.find()) return m.group(1);
            // pas « game-xx… » (une adresse IP, par ex.) : on ne devine pas un pays a partir de
            // quelques caracteres (l'original le faisait, d'ou une furnidata d'un autre hotel)
            Journal.debug("Furnidata : hôte « " + hote + " » sans pays, hôtel fr par défaut.");
        }
        return "fr";
    }

    static String adresse(String pays) {
        if ("s2".equals(pays)) return "https://sandbox.habbo.com/gamedata/furnidata_json/1";
        return "https://www.habbo" + DOMAINES.getOrDefault(pays, ".com") + "/gamedata/furnidata_json/1";
    }

    static File fichierCache(String pays) {
        return new File(new File(Dossiers.donneesAtelier(), "furnidata"), "furnidata-" + pays + ".json");
    }

    /**
     * Charge en fond : le cache s'il existe (pret aussitot), puis le reseau si
     * le cache manque ou a plus d'un jour. Sans effet si un chargement est en
     * cours ou si tout est deja a jour.
     */
    void charger(Runnable quandPrete) {
        if (quandPrete != null) {
            if (donnees != null) lancerRappel(quandPrete);
            else {
                attente.add(quandPrete);
                if (donnees != null && attente.remove(quandPrete)) lancerRappel(quandPrete);   // publiee entre-temps
            }
        }
        synchronized (this) {
            if (enCours) return;
            if (donnees != null && (!perime() || System.currentTimeMillis() - derniereVerif < UN_JOUR_MS)) return;
            enCours = true;
        }
        Thread t = new Thread(this::chargerFond, "Atelier furnidata " + pays);
        t.setDaemon(true);
        t.start();
    }

    private void chargerFond() {
        try {
            if (donnees == null && cache.isFile()) {
                try {
                    publier(Donnees.lire(Files.readString(cache.toPath(), StandardCharsets.UTF_8)));
                    Journal.debug("Furnidata " + pays + " : relue du cache (" + donnees.sols.size() + " sols, "
                            + donnees.murs.size() + " murs).");
                } catch (Exception e) {
                    Journal.debug("Furnidata " + pays + " : cache illisible, il sera retéléchargé : " + e);
                }
            }
            if (donnees == null || perime()) telecharger();
            derniereVerif = System.currentTimeMillis();
        } finally {
            enCours = false;
        }
    }

    /** Vrai si le cache manque ou a plus d'un jour. */
    boolean perime() {
        return !cache.isFile() || System.currentTimeMillis() - cache.lastModified() > UN_JOUR_MS;
    }

    private void telecharger() {
        String url = adresse(pays);
        for (int essai = 1; essai <= ESSAIS; essai++) {
            try {
                String texte = lireUrl(url);
                Donnees d = Donnees.lire(texte);
                ecrireCache(texte);
                publier(d);
                Journal.debug("Furnidata " + pays + " : téléchargée (" + d.sols.size() + " sols, " + d.murs.size()
                        + " murs), essai " + essai + ".");
                return;
            } catch (Exception e) {
                Journal.debug("Furnidata " + pays + " : essai " + essai + " échoué : " + e);
                if (essai < ESSAIS) try { Thread.sleep(2000L * essai); } catch (InterruptedException ie) { return; }
            }
        }
        if (donnees != null) Journal.debug("Furnidata " + pays + " : réseau indisponible, on garde le cache.");
    }

    private static String lireUrl(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(DELAI_MS);
        c.setReadTimeout(DELAI_MS * 3);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept", "application/json");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(1 << 22);
            in.transferTo(b);
            return b.toString(StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private void ecrireCache(String texte) {
        try {
            File dossier = cache.getParentFile();
            if (dossier != null) dossier.mkdirs();
            File tmp = new File(dossier, cache.getName() + ".tmp");
            Files.writeString(tmp.toPath(), texte, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Journal.debug("Furnidata " + pays + " : cache non écrit : " + e);
        }
    }

    private void publier(Donnees d) {
        donnees = d;
        for (Runnable r : attente) if (attente.remove(r)) lancerRappel(r);
    }

    private static void lancerRappel(Runnable r) {
        try { r.run(); } catch (Throwable t) { Journal.debug("Furnidata : rappel en erreur : " + t); }
    }

    // ------------------------------------------------------------ API francaise

    String pays() { return pays; }

    File cache() { return cache; }

    boolean pret() { return donnees != null; }

    /** Id de type d'un mobi au sol, ou null. */
    Integer typeSol(String classe) { Mobi m = sol(classe); return m == null ? null : m.id; }

    /** Id de type d'un mural, ou null. */
    Integer typeMur(String classe) { Mobi m = mur(classe); return m == null ? null : m.id; }

    String classeSol(int type) { Donnees d = donnees; return d == null ? null : d.classeSol.get(type); }

    String classeMur(int type) { Donnees d = donnees; return d == null ? null : d.classeMur.get(type); }

    Mobi sol(String classe) { Donnees d = donnees; return d == null || classe == null ? null : d.sols.get(classe); }

    Mobi mur(String classe) { Donnees d = donnees; return d == null || classe == null ? null : d.murs.get(classe); }

    /** Faux pour les rouleaux, eaux, trous... (liste de l'original). */
    boolean empilable(String classe) { return !NON_EMPILABLES.contains(classe); }

    Collection<Mobi> tousSols() { Donnees d = donnees; return d == null ? List.of() : Collections.unmodifiableCollection(d.sols.values()); }

    Collection<Mobi> tousMurs() { Donnees d = donnees; return d == null ? List.of() : Collections.unmodifiableCollection(d.murs.values()); }

    // ------------------------------------------------------------ noms d'origine

    boolean isReady() { return pret(); }
    Integer getFloorTypeId(String classe) { return typeSol(classe); }
    Integer getWallTypeId(String classe) { return typeMur(classe); }
    String getFloorItemName(int type) { return classeSol(type); }
    String getWallItemName(int type) { return classeMur(type); }
    Mobi getFloorItemDetails(String classe) { return sol(classe); }
    Mobi getWallItemDetails(String classe) { return mur(classe); }
    boolean isStackable(String classe) { return empilable(classe); }
}

package atelier;

import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.InternalExtensionFormCreator;
import gearth.extensions.parsers.HFloorItem;
import gearth.misc.Cacher;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.stage.Stage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le moteur de l'Atelier : l'extension interne branchee sur le proxy.
 *
 * Il tient les briques (EtatSalle, Droits, Inventaire, CatalogueBc, une
 * Furnidata par hotel) branchees sur un Canal qui l'enveloppe, et les briques
 * de pose (ReglageWiredPose, PoseMuraux, EnregistrementCopie ; PoseDalle a la
 * demande). intercept, sendToServer et sendToClient sont ceux de
 * gearth.extensions.ExtensionForm.
 *
 * Ce que le moteur fait de lui-meme :
 *   - a l'activation, demande la salle ouverte (GetHeightMap), et bloque le
 *     RoomEntryInfo qui repond dans les 400 ms (le jeu ne recharge pas la salle) ;
 *   - a la connexion, charge la furnidata de l'hotel (game-xx, Furnidata.pour) ;
 *   - demanderInventaire : les FurniList de la reponse ne vont pas au jeu, qui
 *     ne les a pas demandees ;
 *   - demanderCatalogue : index du catalogue BC, puis ses pages une a une
 *     (180 ms d'ecart, plus 22 ms) quand le cache sur disque ne suffit pas ;
 *   - « :abort » (ou « :a ») tape dans le chat arrete la pose en cours et ne
 *     part pas au serveur ; pendant une pose, les avertissements du BC
 *     (BuildersClubPlacementWarning) ne vont pas au jeu.
 */
@ExtensionInfo(
        Title = "Atelier",
        Description = "Moteur de l'Atelier",
        Version = "2.0",
        Author = "Atelier"
)
public final class Moteur extends ExtensionForm {

    /** Pour InternalExtensionFormLauncher : pas de FXML, la fenetre de l'extension n'est jamais montree. */
    static final class Createur extends InternalExtensionFormCreator<Moteur> {
        @Override
        public Moteur createForm(Stage stage) {
            stage.setTitle(AtelierLauncher.NOM);
            return new Moteur();
        }
    }

    /** Ecart entre deux pages du catalogue BC (180 ms, plus le « ratelimit » de 22 ms d'origine). */
    static final long ECART_PAGES_MS = 180 + 22;
    /** Un RoomEntryInfo arrive moins de 400 ms apres notre GetHeightMap : il ne va pas au jeu. */
    static final long ENTREE_BLOQUEE_MS = 400;

    private volatile Canal canal;
    private volatile EtatSalle salle;
    private volatile Droits droits;
    private volatile Inventaire inventaire;
    private volatile CatalogueBc catalogue;
    private volatile Furnidata furnidata;
    private volatile ReglageWiredPose reglageWired;
    private final Map<Furnidata, PoseMuraux> poseMuraux = new ConcurrentHashMap<>();
    private final Map<Furnidata, EnregistrementCopie> enregistrements = new ConcurrentHashMap<>();
    private volatile boolean branche;

    private volatile long salleDemandeeLe = 0;
    private volatile boolean inventaireEnAttente;
    private volatile long inventaireDemandeLe = 0;
    private volatile boolean collecteEnCours;
    private volatile DalleMagique dalleReglee = DalleMagique.DEUX;

    // ================================================================ branchement

    /**
     * Cree les briques et les ecoutes. A appeler des que l'extension est
     * lancee (InternalExtensionFormLauncher.launch a rempli son champ
     * extension) ; sans effet ensuite. initExtension l'appelle aussi.
     */
    synchronized void brancher() {
        if (branche) return;
        Canal c = Canal.de(this);
        canal = c;
        salle = new EtatSalle(c);
        droits = new Droits(c);
        inventaire = new Inventaire(c);
        CatalogueBc.ecritureCache = true;          // plus d'autre moteur pour ecrire le cache
        catalogue = new CatalogueBc(c, () -> furnidata);
        reglageWired = new ReglageWiredPose(c, salle, droits);

        HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;
        // la salle demandee par nous : le jeu ne la recharge pas
        c.intercept(C, "RoomEntryInfo", m -> {
            if (System.currentTimeMillis() - salleDemandeeLe < ENTREE_BLOQUEE_MS) {
                m.setBlocked(true);
                salleDemandeeLe = 0;
            }
        });
        // l'inventaire demande par nous : les morceaux ne vont pas au jeu
        c.intercept(S, "RequestFurniInventory", m -> inventaireEnAttente = false);   // le jeu le veut aussi
        c.intercept(C, "FurniList", this::surMorceauInventaire);
        // le catalogue BC : ses pages, une fois l'index recu
        c.intercept(C, "CatalogIndex", m -> Salle.tache("catalogue-bc-collecte", this::collecter));
        // pendant une pose : « :abort » l'arrete, les avertissements BC restent caches
        c.intercept(S, "Chat", this::surChat);
        c.intercept(C, "BuildersClubPlacementWarning", m -> { if (PoseCopie.occupee()) m.setBlocked(true); });

        onConnect((hote, port, a, b, client) -> {
            String h = hoteDuJeu(hote);
            furnidata = Furnidata.pour(h);
            Journal.debug("Moteur : connexion à " + hote + " (hôte " + h + "), furnidata " + furnidata.pays() + ".");
        });
        dalleReglee = DalleMagique.depuisTexte(Cacher.getCacheContents().optString("stacktile", "2x2"));
        branche = true;
        Journal.debug("Moteur : briques branchées.");
    }

    @Override
    protected void initExtension() {
        brancher();
        // deja connecte avant l'activation : la furnidata de l'hotel tout de suite
        if (furnidata == null) {
            String h = hoteDuJeu(null);
            if (h != null) furnidata = Furnidata.pour(h);
        }
        demanderSalle();
    }

    @Override
    protected void onEndConnection() {
        PoseCopie.arreter();
        furnidata = null;
        inventaireEnAttente = false;
        EtatSalle s = salle;
        if (s != null) s.reset();
        Inventaire i = inventaire;
        if (i != null) i.vider();
    }

    // ================================================================ acces

    /** L'etat de la salle (null avant le branchement). */
    EtatSalle getFloorState() { return salle; }

    Inventaire getInventory() { return inventaire; }

    CatalogueBc getCatalog() { return catalogue; }

    Droits getPermissions() { return droits; }

    /** La furnidata de l'hotel connecte ; null avant la connexion et apres. */
    Furnidata getFurniDataTools() { return furnidata; }

    Canal canal() { return canal; }

    ReglageWiredPose reglageWired() { return reglageWired; }

    /** La pose a la dalle magique, pour la furnidata actuelle ; null sans furnidata. */
    PoseDalle poseDalle() {
        Furnidata f = furnidata;
        return f == null || canal == null ? null : new PoseDalle(canal, salle, inventaire, catalogue, f, droits);
    }

    /** La pose des muraux (une par furnidata : chacune ecoute ses paquets) ; null sans furnidata. */
    PoseMuraux poseMuraux() {
        Furnidata f = furnidata;
        if (f == null || canal == null) return null;
        return poseMuraux.computeIfAbsent(f, k -> new PoseMuraux(canal, salle, droits, inventaire, catalogue, k));
    }

    /** L'enregistrement des copies (une par furnidata) ; null sans furnidata. */
    EnregistrementCopie enregistrement() {
        Furnidata f = furnidata;
        if (f == null || canal == null) return null;
        return enregistrements.computeIfAbsent(f, k -> new EnregistrementCopie(canal, salle, k, droits, null));
    }

    /** La premiere dalle magique de la salle du modele regle (« stacktile », 2x2 par defaut), ou null. */
    HFloorItem stackTile() {
        EtatSalle s = salle;
        Furnidata f = furnidata;
        if (s == null || f == null || !s.inRoom() || !f.pret()) return null;
        List<HFloorItem> l = s.getItemsFromType(f, dalleReglee.classe());
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * L'hote du jeu (« game-fr.habbo.com ») : le nom demande par le jeu
     * (getDomain), sinon un autre nom Habbo connu de la connexion ; jamais une
     * adresse IP (« 52.17.x.x » donnait le pays « 17 », donc la furnidata d'un
     * autre hotel). null si aucun : Furnidata prend alors l'hotel fr.
     */
    static String hoteDuJeu(String donne) {
        String domaine = null, client = null, serveur = null;
        try {
            gearth.protocol.HConnection h = AtelierLauncher.connexionHabbo();
            if (h != null) { domaine = h.getDomain(); client = h.getClientHost(); serveur = h.getServerHost(); }
        } catch (Throwable ignored) { }
        return hoteHabbo(domaine, donne, client, serveur);
    }

    /** Le premier nom d'hote Habbo de la liste (« game-xx.habbo... »), sinon null : jamais une IP. */
    static String hoteHabbo(String... candidats) {
        for (String s : candidats)
            if (s != null && s.toLowerCase().matches("game-[a-z0-9]{2}\\..*habbo.*")) return s;
        return null;
    }

    // ================================================================ demandes

    /** Redemande la salle ouverte (GetHeightMap) ; la reponse RoomEntryInfo ne va pas au jeu. */
    void demanderSalle() {
        Salle.espacer();
        salleDemandeeLe = System.currentTimeMillis();
        sendToServer(new HPacket("GetHeightMap", HMessage.Direction.TOSERVER));
    }

    /** Demande tout l'inventaire (RequestFurniInventory) ; la reponse ne va pas au jeu. */
    void demanderInventaire() {
        Inventaire i = inventaire;
        if (i != null) i.vider();
        inventaireEnAttente = true;
        inventaireDemandeLe = System.currentTimeMillis();
        Salle.espacer();
        sendToServer(new HPacket("RequestFurniInventory", HMessage.Direction.TOSERVER));
    }

    /** Demande l'index du catalogue BC ; ses pages suivent (collecter). */
    void demanderCatalogue() {
        Salle.espacer();
        sendToServer(new HPacket("GetCatalogIndex", HMessage.Direction.TOSERVER, CatalogueBc.BC));
    }

    private void surMorceauInventaire(HMessage m) {
        if (!inventaireEnAttente) return;
        if (System.currentTimeMillis() - inventaireDemandeLe > 120_000) { inventaireEnAttente = false; return; }
        m.setBlocked(true);
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 14) return;
        int total = p.readInteger(6), index = p.readInteger(10);
        if (index >= total - 1) inventaireEnAttente = false;
    }

    /**
     * Les pages du catalogue BC que l'index annonce et que le cache ne donne
     * pas, une a une. Un seul passage a la fois ; CatalogueBc conclut seul
     * (toutes les pages recues, ou 5 s sans nouvelle page).
     */
    private void collecter() {
        synchronized (this) {
            if (collecteEnCours) return;
            collecteEnCours = true;
        }
        try {
            Salle.sommeil(300);                       // l'index d'abord lu par CatalogueBc
            CatalogueBc cat = catalogue;
            if (cat == null) return;
            Set<Integer> demandees = new LinkedHashSet<>();
            List<Integer> pages = cat.pagesAFaire();
            if (pages.isEmpty()) return;
            Journal.debug("Catalogue BC : " + pages.size() + (pages.size() > 1 ? " pages à lire." : " page à lire."));
            for (int page : pages) {
                if (cat.etat() != CatalogueBc.Etat.COLLECTING_PAGES) break;
                if (!cat.pagesAFaire().contains(page) || !demandees.add(page)) continue;
                Salle.espacer();
                sendToServer(new HPacket("GetCatalogPage", HMessage.Direction.TOSERVER, page, -1, CatalogueBc.BC));
                Salle.sommeil(ECART_PAGES_MS);
            }
        } finally {
            collecteEnCours = false;
        }
    }

    private void surChat(HMessage m) {
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 8 || p.getBytesLength() > 40) return;
        String t;
        try { t = p.readString(6).trim(); } catch (Throwable e) { return; }
        if (!t.equals(":abort") && !t.equals(":a")) return;
        m.setBlocked(true);
        if (PoseCopie.occupee()) {
            PoseCopie.arreter();
            InfoJeu.consigne("Pose arrêtée.");
        }
    }

    // ================================================================ messages dans le jeu

    /** Un message dans le chat du jeu, chez toi seulement (Whisper envoye au jeu, jamais au serveur). */
    void sendVisualChatInfo(String texte) {
        sendToClient(new HPacket("Whisper", HMessage.Direction.TOCLIENT, -1, pourLeChat(texte), 0, 30, 0, -1));
    }

    /**
     * Le chat de Habbo affiche mal les guillemets francais (le » devient un
     * symbole) : guillemets droits a la place. Tous les messages affiches dans
     * le jeu passent ici, ceux de l'Atelier (InfoJeu) compris.
     */
    static String pourLeChat(String m) {
        if (m == null) return null;
        return m.replace("« ", "\"").replace(" »", "\"").replace("«", "\"").replace("»", "\"")
                .replace(' ', ' ').replace('×', 'x');
    }

    // ================================================================ cache du proxy

    /**
     * Le cache du proxy (cache.json : langue, hotels, reglages) reste dans son
     * dossier d'origine, a cote du jar (Cacher). L'ancien moteur l'avait
     * deplace dans « Application Support » : ce qu'il y a ecrit est fusionne
     * ici une seule fois, sans rien ecraser (les listes d'hotels sont reunies).
     * A appeler avant le demarrage du proxy.
     */
    static void fusionnerCache() {
        try {
            String dossier = Cacher.getCacheDir();
            if (dossier == null) return;
            File ici = new File(dossier, "cache.json");
            File ancien = ancienCache();
            if (ancien == null || !ancien.isFile()) return;
            JSONObject a = new JSONObject(Files.readString(ancien.toPath(), StandardCharsets.UTF_8));
            JSONObject o = ici.isFile() ? new JSONObject(Files.readString(ici.toPath(), StandardCharsets.UTF_8)) : new JSONObject();
            if (o.optBoolean("atelierCacheFusionne")) return;
            int ajouts = 0;
            for (String k : a.keySet()) {
                Object v = a.get(k);
                if (!o.has(k)) { o.put(k, v); ajouts++; continue; }
                if (v instanceof JSONArray && o.get(k) instanceof JSONArray) {
                    JSONArray l = o.getJSONArray(k);
                    List<Object> vus = new ArrayList<>(l.toList());
                    for (Object x : ((JSONArray) v).toList())
                        if (!vus.contains(x)) { l.put(x); vus.add(x); ajouts++; }
                }
            }
            o.put("atelierCacheFusionne", true);
            new File(dossier).mkdirs();
            File tmp = new File(dossier, "cache.json.tmp");
            Files.writeString(tmp.toPath(), o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), ici.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Journal.debug("Cache du proxy : " + ajouts + (ajouts > 1 ? " réglages repris" : " réglage repris")
                    + " de l'ancien dossier.");
        } catch (Throwable t) {
            Journal.debug("Cache du proxy : fusion impossible (" + t + ").");
        }
    }

    /** L'ancien cache.json (dossier « G-Presets/Cache » d'Application Support ou d'APPDATA), ou null. */
    private static File ancienCache() {
        File base = Dossiers.ancienModule();
        return base == null ? null : new File(new File(base, "Cache"), "cache.json");
    }
}

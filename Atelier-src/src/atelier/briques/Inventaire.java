package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'inventaire de mobis. Remplace Inventory (ancien module), en ecoute seule : la demande
 * au serveur n'est pas faite ici (elle passera par ChargementAuto, qui saura
 * ne pas renvoyer la reponse au jeu), et aucun FurniList n'est bloque.
 *
 * Paquets (TOCLIENT) :
 *   FurniList             int total, int index, int n, n x HInventoryItem (par morceaux)
 *   FurniListAddOrUpdate  int n, n x HInventoryItem
 *   FurniListRemove       int id de placement
 *   FurniListInvalidate   l'inventaire du serveur a change (on le note seulement)
 *
 * Etats : memes noms que l'original (UNAVAILABLE, LOADING, LOADED), compares
 * aujourd'hui par leur texte. Lectures depuis tous les fils : verrou unique,
 * listes rendues en copie.
 *
 * Les ecouteurs ne font que copier les octets ; la lecture se fait sur un fil
 * basse priorite, une fois la serie entiere arrivee (25 000 mobis : le chat et
 * les autres paquets ne l'attendent jamais).
 */
final class Inventaire {

    enum Etat { UNAVAILABLE, LOADING, LOADED }

    /**
     * Un seul fil, basse priorite, pour lire les paquets d'inventaire dans leur
     * ordre d'arrivee. Les ecouteurs (fil des paquets) ne font que copier les
     * octets : le proxy tient un verrou commun aux deux sens pendant qu'ils
     * tournent, et le moindre travail ici retardait le chat et les autres paquets.
     */
    private static final java.util.concurrent.ExecutorService FIL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-inventaire-moteur");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /** Une serie sans nouveau morceau depuis ce temps est abandonnee. */
    static final long SERIE_PERDUE_MS = 30_000;

    private final Object verrou = new Object();
    private volatile Etat etat = Etat.UNAVAILABLE;
    private volatile boolean perime;
    /** Change a chaque modification du contenu (chargement, ajout, retrait). */
    private volatile int version;
    /** Heure du dernier morceau de FurniList vu passer (fil des paquets). */
    private volatile long dernierMorceauLe;
    /** Fil de lecture seulement : les morceaux bruts de la serie en cours. */
    private List<byte[]> morceaux;
    /**
     * Fil de lecture seulement : ajouts (mobi) et retraits (numero de placement)
     * arrives pendant le flux, rejoues sur la serie une fois lue — sinon ils
     * etaient ecrases par elle.
     */
    private final List<Object> pendantFlux = new ArrayList<>();
    private Map<Integer, HInventoryItem> parPlacement = new LinkedHashMap<>();
    private Map<Integer, Map<Integer, HInventoryItem>> solsParType = new HashMap<>();
    private Map<Integer, Map<Integer, HInventoryItem>> mursParType = new HashMap<>();

    Inventaire(Canal canal) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "FurniList", this::surListe);
        canal.intercept(C, "FurniListAddOrUpdate", m -> {
            byte[] b = m.getPacket().toBytes().clone();
            FIL.execute(() -> surAjouts(b));
        });
        canal.intercept(C, "FurniListRemove", m -> {
            HPacket p = m.getPacket();
            if (p.getBytesLength() < 10) return;
            int placement = p.readInteger(6);
            FIL.execute(() -> retirer(placement));
        });
        canal.intercept(C, "FurniListInvalidate", m -> perime = true);
    }

    // ================================================================ ecouteurs

    /** Fil des paquets : lecture a index fixe et copie des octets, rien d'autre. */
    private void surListe(HMessage m) {
        HPacket p = m.getPacket();
        if (p.getBytesLength() < 14) return;
        int total = p.readInteger(6), index = p.readInteger(10);
        byte[] b = p.toBytes().clone();                 // la connexion peut reutiliser l'original
        dernierMorceauLe = System.currentTimeMillis();
        if (index == 0) etat = Etat.LOADING;            // personne ne redemande pendant le flux
        FIL.execute(() -> morceau(total, index, b));
    }

    /**
     * Fil de lecture : les morceaux sont gardes bruts pendant le flux ; la
     * lecture des mobis et le classement se font une fois le dernier recu.
     */
    private void morceau(int total, int index, byte[] b) {
        if (index == 0) {
            synchronized (verrou) { viderSansVerrou(); etat = Etat.LOADING; }
            morceaux = new ArrayList<>(Math.max(1, Math.min(total, 500)));
            pendantFlux.clear();
        }
        // Morceau d'une serie commencee avant l'ecoute : on attend la suivante.
        if (morceaux == null) return;
        morceaux.add(b);
        if (index < total - 1) return;
        List<byte[]> serie = morceaux;
        morceaux = null;
        Map<Integer, HInventoryItem> pp = new LinkedHashMap<>();
        Map<Integer, Map<Integer, HInventoryItem>> sols = new HashMap<>(), murs = new HashMap<>();
        // Un morceau illisible ne perd que ses mobis : jamais une serie entiere
        // restee « en chargement » (les retraits seraient alors ignores).
        int illisibles = 0;
        for (byte[] o : serie) {
            try {
                HPacket p = new HPacket(o);
                p.resetReadIndex();
                p.readInteger(); p.readInteger();            // total, index
                for (HInventoryItem it : HInventoryItem.parse(p)) ajouter(it, pp, sols, murs);
            } catch (Throwable t) { illisibles++; }
        }
        if (illisibles > 0) Journal.debug(Ui.accorder("Inventaire : " + illisibles + " morceau(x) illisible(s), mobis ignorés."));
        for (Object c : pendantFlux) {
            if (c instanceof HInventoryItem) ajouter((HInventoryItem) c, pp, sols, murs);
            else enlever((Integer) c, pp, sols, murs);
        }
        pendantFlux.clear();
        synchronized (verrou) {
            parPlacement = pp;
            solsParType = sols;
            mursParType = murs;
            etat = Etat.LOADED;
            perime = false;
            version++;
        }
    }

    private void surAjouts(byte[] b) {
        HPacket p = new HPacket(b);
        p.resetReadIndex();
        int n = p.readInteger();
        List<HInventoryItem> lus = new ArrayList<>(Math.max(0, Math.min(n, 10_000)));
        try { for (int i = 0; i < n; i++) lus.add(new HInventoryItem(p)); }
        catch (Throwable t) { Journal.debug("Inventaire : ajout illisible (" + t + ")."); }
        if (morceaux != null) pendantFlux.addAll(lus);
        synchronized (verrou) {
            if (etat == Etat.UNAVAILABLE) return;               // comme l'original
            for (HInventoryItem it : lus) ajouter(it, parPlacement, solsParType, mursParType);
            version++;
        }
    }

    private void retirer(int placement) {
        if (morceaux != null) pendantFlux.add(placement);
        synchronized (verrou) {
            if (etat != Etat.LOADED) return;
            if (enlever(placement, parPlacement, solsParType, mursParType)) version++;
        }
    }

    private static boolean enlever(int placement, Map<Integer, HInventoryItem> pp,
                                   Map<Integer, Map<Integer, HInventoryItem>> sols,
                                   Map<Integer, Map<Integer, HInventoryItem>> murs) {
        HInventoryItem it = pp.remove(placement);
        if (it == null) return false;
        Map<Integer, HInventoryItem> t = (it.getType() == HProductType.FloorItem ? sols : murs).get(it.getTypeId());
        if (t != null) t.remove(it.getId());
        return true;
    }

    private static void ajouter(HInventoryItem it, Map<Integer, HInventoryItem> pp,
                                Map<Integer, Map<Integer, HInventoryItem>> sols,
                                Map<Integer, Map<Integer, HInventoryItem>> murs) {
        pp.put(it.getPlacementId(), it);
        (it.getType() == HProductType.FloorItem ? sols : murs)
                .computeIfAbsent(it.getTypeId(), k -> new LinkedHashMap<>()).put(it.getId(), it);
    }

    /** Sous verrou. */
    private void viderSansVerrou() {
        parPlacement = new LinkedHashMap<>();
        solsParType = new HashMap<>();
        mursParType = new HashMap<>();
        etat = Etat.UNAVAILABLE;
        version++;
    }

    // ================================================================ API francaise

    /** L'etat ; une serie restee sans morceau depuis SERIE_PERDUE_MS compte comme absente. */
    Etat etat() {
        Etat e = etat;
        if (e == Etat.LOADING && System.currentTimeMillis() - dernierMorceauLe > SERIE_PERDUE_MS) return Etat.UNAVAILABLE;
        return e;
    }

    boolean charge() { return etat == Etat.LOADED; }

    /** Vrai pendant qu'une serie de FurniList passe (ou attend d'etre lue). */
    boolean enChargement() { return etat() == Etat.LOADING; }

    /** Numero de version du contenu : change a chaque chargement, ajout ou retrait. */
    int version() { return version; }

    /** Vrai si le serveur a signale un changement (FurniListInvalidate) depuis le dernier chargement. */
    boolean perime() { return perime; }

    int nombre() { synchronized (verrou) { return parPlacement.size(); } }

    List<HInventoryItem> mobis() { synchronized (verrou) { return new ArrayList<>(parPlacement.values()); } }

    List<HInventoryItem> solsDeType(int type) { return copie(solsParType, type); }

    List<HInventoryItem> mursDeType(int type) { return copie(mursParType, type); }

    /** Nombre de mobis de sol de chaque type (type -> nombre). */
    Map<Integer, Integer> compteSols() { return comptes(solsParType); }

    /** Nombre de muraux de chaque type (type -> nombre). */
    Map<Integer, Integer> compteMurs() { return comptes(mursParType); }

    /** Vide l'inventaire (l'etat tout de suite, le contenu sur le fil de lecture, apres les morceaux deja recus). */
    void vider() {
        etat = Etat.UNAVAILABLE;
        FIL.execute(() -> { morceaux = null; pendantFlux.clear(); synchronized (verrou) { viderSansVerrou(); } });
    }

    private List<HInventoryItem> copie(Map<Integer, Map<Integer, HInventoryItem>> m, int type) {
        synchronized (verrou) {
            Map<Integer, HInventoryItem> t = m.get(type);
            return t == null ? Collections.emptyList() : new ArrayList<>(t.values());
        }
    }

    private Map<Integer, Integer> comptes(Map<Integer, Map<Integer, HInventoryItem>> m) {
        synchronized (verrou) {
            Map<Integer, Integer> r = new HashMap<>();
            m.forEach((k, v) -> { if (!v.isEmpty()) r.put(k, v.size()); });
            return r;
        }
    }

    // ================================================================ noms d'origine (Inventory)

    Etat getState() { return etat(); }

    List<HInventoryItem> getInventoryItems() { return mobis(); }

    List<HInventoryItem> getFloorItemsByType(int type) { return solsDeType(type); }

    List<HInventoryItem> getWallItemsByType(int type) { return mursDeType(type); }

    void clear() { vider(); }
}

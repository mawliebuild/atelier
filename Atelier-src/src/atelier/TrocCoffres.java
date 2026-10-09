package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Les coffres wired du troc (wf_storage_furni1 : coffre a mobis,
 * wf_storage_coins2 : coffre a credits) : lire leur contenu, y deposer des
 * mobis, retirer les credits, et ramasser les vitrines dont le stock est vide.
 *
 * Paquets (verifies dans le client, paquets _-8s / _-a2i / _-y17 / _-e2B) :
 *   OpenChestAndGetContents(int coffre)      -> ItemsChestContentsChunk / CoinsChestContents
 *   CloseChest(int coffre)
 *   StartAddingToChest(int coffre)           -> WiredTradeInitiate
 *   WiredTradeAddDeleteItems(bool retirer, int n, n x int id d'inventaire)
 *                                            -> WiredTradeItemsUpdate (..., bool canAccept, int)
 *   WiredTradeConfirm(bool) : false (accepter), 3 s, true (confirmer)
 *                                            -> WiredTradeCompleted / WiredTradeCancelled(int code)
 *   WiredTradeCancel()
 *   WithdrawCoinsFromChest(int coffre, int montant)
 *   WithdrawItemsFromChest(int coffre, bool mural, int type, String affiche, int quantite)
 *
 * Les ids deposes sont ceux de FurnitureItem.id dans le client (FurniModel.
 * requestSelectedFurniToTrading), c'est-a-dire le PREMIER entier d'un mobi de
 * FurniList : HInventoryItem.getPlacementId() (getId() est le 2e entier).
 *
 * Quand c'est l'Atelier qui ouvre un coffre ou depose, les reponses sont
 * BLOQUEES vers le jeu (sinon la fenetre du coffre ou l'onglet de troc de
 * l'inventaire s'ouvriraient). Ecoutes tres courtes : lecture a index fixe,
 * copie seulement d'un paquet qu'on garde, travail sur le fil « atelier-troc ».
 */
final class TrocCoffres {

    private TrocCoffres() { }

    static final String COFFRE_MOBIS = "wf_storage_furni1", COFFRE_CREDITS = "wf_storage_coins2";
    private static final HMessage.Direction S = HMessage.Direction.TOSERVER;

    // ================================================================ paquets (logique pure)

    /** Les valeurs de WiredTradeAddDeleteItems : (false, n, ids...). */
    static Object[] valeursAjout(List<Integer> ids) {
        Object[] v = new Object[ids.size() + 2];
        v[0] = false;
        v[1] = ids.size();
        for (int i = 0; i < ids.size(); i++) v[i + 2] = ids.get(i);
        return v;
    }

    static HPacket ouverture(int coffre)           { return new HPacket("OpenChestAndGetContents", S, coffre); }
    static HPacket fermeture(int coffre)           { return new HPacket("CloseChest", S, coffre); }
    static HPacket debutDepot(int coffre)          { return new HPacket("StartAddingToChest", S, coffre); }
    static HPacket ajout(List<Integer> ids)        { return new HPacket("WiredTradeAddDeleteItems", S, valeursAjout(ids)); }
    static HPacket confirmation(boolean finale)    { return new HPacket("WiredTradeConfirm", S, finale); }
    static HPacket annulation()                    { return new HPacket("WiredTradeCancel", S); }
    static HPacket retraitCredits(int coffre, int montant) { return new HPacket("WithdrawCoinsFromChest", S, coffre, montant); }
    static HPacket retraitMobis(int coffre, boolean mural, int type, String affiche, int quantite) {
        return new HPacket("WithdrawItemsFromChest", S, coffre, mural, type, affiche == null ? "" : affiche, quantite);
    }

    // ================================================================ reponses (logique pure)

    /** Un mobi range dans un coffre (ChestStorage du client). */
    record Rangement(int inventaireId, int verrou, long transaction, boolean mural, int typeId, String affiche,
                     boolean groupable, int special, int extra) { }

    /** ItemsChestContentsChunk : coffre, nombre de morceaux, numero du morceau, mobis. */
    record Morceau(int coffre, int total, int numero, List<Rangement> objets) { }

    /** CoinsChestContents : coffre, credits, mise a jour. */
    record Credits(int coffre, int credits, boolean maj) { }

    /** ChestStorage : int, int, long, (bool mural, int type, String affiche), bool, int, stuffData, int si sol. */
    static Rangement lireRangement(HPacket p) {
        int inv = p.readInteger();
        int verrou = p.readInteger();
        long tr = p.readLong();
        boolean mural = p.readBoolean();
        int type = p.readInteger();
        String affiche = p.readString();
        boolean groupable = p.readBoolean();
        int special = p.readInteger();
        IStuffData.read(p);
        int extra = mural ? 0 : p.readInteger();
        return new Rangement(inv, verrou, tr, mural, type, affiche, groupable, special, extra);
    }

    static Morceau lireMorceau(HPacket p) {
        p.resetReadIndex();
        int coffre = p.readInteger(), total = p.readInteger(), numero = p.readInteger();
        int n = p.readInteger();
        List<Rangement> l = new ArrayList<>();
        for (int i = 0; i < n && i < 100_000; i++) l.add(lireRangement(p));
        return new Morceau(coffre, total, numero, l);
    }

    static Credits lireCredits(HPacket p) {
        p.resetReadIndex();
        return new Credits(p.readInteger(), p.readInteger(), p.readBoolean());
    }

    /** WiredTradeItemsUpdate : canAccept est l'avant-dernier champ (bool, puis un int). Lecture a index fixe. */
    static boolean peutAccepter(HPacket p) { return p.readBoolean(p.getBytesLength() - 5); }

    /** Le contenu d'un coffre a mobis : nombre par type (sols « s123 », muraux « m45 »). */
    static Map<String, Integer> parType(List<Rangement> l) {
        Map<String, Integer> m = new TreeMap<>();
        if (l != null) for (Rangement r : l) m.merge((r.mural() ? "m" : "s") + r.typeId(), 1, Integer::sum);
        return m;
    }

    /** Combien d'exemplaires de ce type de sol dans le coffre. */
    static int stock(List<Rangement> l, int typeSol) {
        int n = 0;
        if (l != null) for (Rangement r : l) if (!r.mural() && r.typeId() == typeSol) n++;
        return n;
    }

    /** Raison d'un WiredTradeCancelled (textes du jeu). */
    static String raison(int code) {
        switch (code) {
            case 0: return "transaction annulée";
            case 1: return "transaction non valide";
            case 2: return "temps écoulé";
            case 3: return "transaction annulée";
            case 4: return "tu effectues déjà une transaction";
            case 5: return "mauvaise configuration wired";
            case 6: return "fonds insuffisants";
            case 7: return "fonds épuisés";
            case 8: return "tu ne peux pas échanger";
            case 9: return "le propriétaire du coffre ne peut pas échanger";
            case 10: return "transaction vide";
            case 11: return "coffre plein";
            case 12: return "fonctionnalité désactivée";
            case 13: return "coffre absent de la salle";
            case 14: return "trop de coffres";
            case 15: return "pas de coffre disponible";
            case 17: return "trop d'offres wired";
            case 18: return "envois trop rapides";
            case 19: return "le coffre dépasserait sa capacité";
            case 1000: return "erreur interne du jeu";
            case 1001: case 1002: return "erreur de base de données du jeu";
            default: return "code " + code;
        }
    }

    /** Les deux coffres d'une config troc dans la salle (0 = absent). */
    record Coffres(int mobis, int credits) {
        boolean complets() { return mobis != 0 && credits != 0; }
    }

    /** Un mobi de sol vu pour la recherche des coffres : id, classe, case. */
    record Pose(int id, String classe, int x, int y) { }

    /** Les coffres a mobis connus, celui de la config d'abord (variantes : furni2, coffre de depart). */
    static final List<String> CLASSES_MOBIS = List.of(COFFRE_MOBIS, "wf_storage_furni2", "wf_storage_furni_starter");
    /** Les coffres a credits connus, celui de la config d'abord. */
    static final List<String> CLASSES_CREDITS = List.of(COFFRE_CREDITS, "wf_storage_coins1");

    /**
     * 1 = coffre a mobis, 2 = coffre a credits, 0 = autre chose. Toutes les
     * variantes comptent (wf_storage_furni*, wf_storage_coins*) : une config
     * posee a la main ou par une ancienne version n'a pas forcement les memes
     * coffres que TrocModele. Logique pure.
     */
    static int genreCoffre(String classe) {
        if (classe == null) return 0;
        String c = classe.trim().toLowerCase(Locale.ROOT);
        int etoile = c.indexOf('*');
        if (etoile >= 0) c = c.substring(0, etoile);
        if (c.startsWith("wf_storage_furni")) return 1;
        if (c.startsWith("wf_storage_coins")) return 2;
        return 0;
    }

    /**
     * Les coffres de la config : le coffre a mobis (le premier trouve) et le
     * coffre a credits le plus proche de lui. Logique pure.
     */
    static Coffres trouver(List<Pose> sols) {
        Pose mob = null;
        for (Pose p : sols) if (genreCoffre(p.classe()) == 1) { mob = p; break; }
        Pose cr = null;
        long meilleur = Long.MAX_VALUE;
        for (Pose p : sols) {
            if (genreCoffre(p.classe()) != 2) continue;
            long d = mob == null ? 0 : (long) (p.x() - mob.x()) * (p.x() - mob.x()) + (long) (p.y() - mob.y()) * (p.y() - mob.y());
            if (d < meilleur) { meilleur = d; cr = p; }
        }
        return new Coffres(mob == null ? 0 : mob.id(), cr == null ? 0 : cr.id());
    }

    /** Les coffres de la salle ouverte. */
    static Coffres dansLaSalle() {
        List<Pose> l = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            String c = Salle.classe(it.getTypeId(), false);
            if (genreCoffre(c) != 0)
                l.add(new Pose(it.getId(), c, it.getTile().getX(), it.getTile().getY()));
        }
        return trouver(l);
    }

    // ================================================================ ecoutes

    private static final ExecutorService FIL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atelier-troc");
        t.setDaemon(true);
        return t;
    });

    /** Coffres que l'Atelier lit : id -> bloquer leurs reponses jusqu'a cet instant. */
    private static final Map<Integer, Long> bloques = new ConcurrentHashMap<>();
    /** Nos envois d'ouverture : id -> instant (notre propre envoi n'est pas pris pour celui du jeu). */
    private static final Map<Integer, Long> nosOuvertures = new ConcurrentHashMap<>();
    /** Coffres ouverts par l'utilisatrice dans le jeu : id -> instant. On ne les lit pas pendant ce temps. */
    private static final Map<Integer, Long> elleOuvre = new ConcurrentHashMap<>();
    private static final Map<Integer, Lecture> lectures = new ConcurrentHashMap<>();
    private static final Map<Integer, CompletableFuture<Credits>> credits = new ConcurrentHashMap<>();
    /** Dernier contenu connu des coffres a mobis : id -> mobis. */
    private static final Map<Integer, List<Rangement>> connus = new ConcurrentHashMap<>();

    /** Le depot de l'Atelier : evenements de troc bloques vers le jeu jusqu'a cet instant. */
    private static volatile long depotJusqua = 0;
    private static final BlockingQueue<Object[]> evenements = new LinkedBlockingQueue<>();
    /** La salle a signale un changement dans un coffre (vente, depot...) : relire. */
    private static volatile boolean changement = false;

    private static volatile boolean branche = false, enBranchement = false;

    /** Une lecture de coffre a mobis : morceaux recus. */
    private static final class Lecture {
        final Map<Integer, List<Rangement>> morceaux = new ConcurrentHashMap<>();
        volatile int total = -1;
        final CompletableFuture<List<Rangement>> fin = new CompletableFuture<>();
        void ajouter(Morceau m) {
            total = m.total();
            morceaux.put(m.numero(), m.objets());
            if (morceaux.size() >= Math.max(1, m.total())) fin.complete(tout());
        }
        List<Rangement> tout() {
            List<Rangement> l = new ArrayList<>();
            new TreeMap<>(morceaux).values().forEach(l::addAll);
            return l;
        }
    }

    private static boolean bloque(int coffre) {
        Long t = bloques.get(coffre);
        return t != null && System.currentTimeMillis() <= t;
    }

    /** Branche les ecoutes une seule fois (reessaie tant que le moteur n'est pas pret) et lance le suivi. */
    static synchronized void installer() {
        if (branche || enBranchement) return;
        enBranchement = true;
        Salle.tache("troc-ecoute", () -> {
            for (int i = 0; i < 900 && !branche; i++) {
                if (brancher()) { branche = true; break; }
                Salle.sommeil(1000);
            }
            enBranchement = false;
        });
        Salle.tache("troc-suivi", TrocCoffres::suivi);
    }

    private static boolean brancher() {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        ecouter(gp, C, "ItemsChestContentsChunk", m -> {
            HPacket p = m.getPacket();
            int id = p.readInteger(6);
            boolean notre = bloque(id);
            if (notre) m.setBlocked(true);
            if (!notre && !connus.containsKey(id)) return;          // un coffre qui ne nous concerne pas
            HPacket copie = new HPacket(p);
            FIL.execute(() -> {
                try {
                    Morceau mo = lireMorceau(copie);
                    Lecture l = lectures.get(id);
                    if (l != null) l.ajouter(mo);
                    else if (mo.total() <= 1) connus.put(id, mo.objets());   // ouvert par elle : contenu a jour
                } catch (Throwable t) { Journal.debug("Troc : morceau de coffre illisible (" + t + ")"); }
            });
        });
        ecouter(gp, C, "CoinsChestContents", m -> {
            HPacket p = m.getPacket();
            int id = p.readInteger(6);
            if (!bloque(id)) return;
            m.setBlocked(true);
            int c = p.readInteger(10);
            boolean maj = p.readBoolean(14);
            CompletableFuture<Credits> f = credits.get(id);
            if (f != null) f.complete(new Credits(id, c, maj));
        });
        ecouter(gp, C, "OpenChest", m -> { if (bloque(m.getPacket().readInteger(6))) m.setBlocked(true); });
        ecouter(gp, C, "ItemsChestContentsUpdated", m -> {
            int id = m.getPacket().readInteger(6);
            if (bloque(id)) m.setBlocked(true);
            else if (connus.containsKey(id)) changement = true;
        });
        ecouter(gp, C, "WiredTradeTransactionNotification", m -> changement = true);
        ecouter(gp, C, "WiredTradeInitiate", m -> troc(m, "debut"));
        ecouter(gp, C, "WiredTradeItemsUpdate", m -> troc(m, "maj"));
        ecouter(gp, C, "WiredTradeCompleted", m -> troc(m, "fin"));
        ecouter(gp, C, "WiredTradeCancelled", m -> troc(m, "annule"));
        // l'utilisatrice ouvre / ferme un coffre elle-meme : on ne le lit pas pendant ce temps
        ecouter(gp, S, "OpenChestAndGetContents", m -> {
            int id = m.getPacket().readInteger(6);
            Long n = nosOuvertures.get(id);
            if (n != null && System.currentTimeMillis() - n < 800) return;
            elleOuvre.put(id, System.currentTimeMillis());
        });
        ecouter(gp, S, "CloseChest", m -> elleOuvre.remove(m.getPacket().readInteger(6)));
        return true;
    }

    private static void ecouter(Moteur gp, HMessage.Direction d, String nom, Consumer<HMessage> c) {
        try {
            gp.intercept(d, nom, m -> { try { c.accept(m); } catch (Throwable ignored) { } });
        } catch (Throwable t) {
            Journal.debug("Troc : " + nom + " non intercepté (" + t + ")");
        }
    }

    /** Evenement du troc wired : bloque vers le jeu pendant NOTRE depot, et transmis au depot. */
    private static void troc(HMessage m, String genre) {
        if (System.currentTimeMillis() > depotJusqua) return;
        m.setBlocked(true);
        HPacket p = m.getPacket();
        boolean accepte = "maj".equals(genre) && peutAccepter(p);
        int code = "annule".equals(genre) ? p.readInteger(6) : -1;
        evenements.offer(new Object[]{genre, accepte, code});
    }

    /** L'utilisatrice a-t-elle ce coffre ouvert dans le jeu (depuis moins de 10 min) ? */
    static boolean ouvertParElle(int coffre) {
        Long t = elleOuvre.get(coffre);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 600_000) { elleOuvre.remove(coffre); return false; }
        return true;
    }

    // ================================================================ actions (fil de travail)

    /** Une seule operation sur les coffres a la fois. */
    private static final java.util.concurrent.locks.ReentrantLock OCCUPE = new java.util.concurrent.locks.ReentrantLock();

    static boolean occupe() { return OCCUPE.isLocked(); }

    private static void ouvrir(int coffre, long ms) {
        bloques.put(coffre, System.currentTimeMillis() + ms);
        nosOuvertures.put(coffre, System.currentTimeMillis());
        Salle.envoyerEspace(ouverture(coffre));
    }

    private static void fermer(int coffre) {
        Salle.envoyerEspace(fermeture(coffre));
        // les derniers paquets peuvent arriver apres la fermeture
        bloques.put(coffre, System.currentTimeMillis() + 1500);
    }

    /** Le contenu du coffre a mobis (null si pas de reponse). Ne lit pas un coffre ouvert dans le jeu. */
    static List<Rangement> lireMobis(int coffre) {
        if (coffre == 0 || ouvertParElle(coffre)) return connus.get(coffre);
        installer();
        OCCUPE.lock();
        try { return lireMobis0(coffre); }
        finally { OCCUPE.unlock(); }
    }

    private static List<Rangement> lireMobis0(int coffre) {
        Lecture l = new Lecture();
        lectures.put(coffre, l);
        try {
            ouvrir(coffre, 8000);
            List<Rangement> r;
            try { r = l.fin.get(5000, TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) { r = l.morceaux.isEmpty() ? null : l.tout(); }
            catch (Throwable t) { r = null; }
            fermer(coffre);
            if (r != null) connus.put(coffre, r);
            return r;
        } finally { lectures.remove(coffre); }
    }

    /** Les credits du coffre a credits (null si pas de reponse). */
    static Credits lireCredits(int coffre) {
        if (coffre == 0 || ouvertParElle(coffre)) return null;
        installer();
        OCCUPE.lock();
        try { return lireCredits0(coffre); }
        finally { OCCUPE.unlock(); }
    }

    private static Credits lireCredits0(int coffre) {
        CompletableFuture<Credits> f = new CompletableFuture<>();
        credits.put(coffre, f);
        try {
            ouvrir(coffre, 8000);
            Credits c;
            try { c = f.get(4000, TimeUnit.MILLISECONDS); } catch (Throwable t) { c = null; }
            fermer(coffre);
            return c;
        } finally { credits.remove(coffre); }
    }

    /** Retire tous les credits du coffre. null si c'est fait, sinon l'erreur. */
    static String retirerCredits(int coffre) {
        if (coffre == 0) return "Pas de coffre à crédits dans cette salle.";
        if (ouvertParElle(coffre)) return "Ferme d'abord le coffre dans le jeu.";
        installer();
        if (!OCCUPE.tryLock()) return "Le coffre est déjà en cours d'utilisation par l'Atelier.";
        try {
            CompletableFuture<Credits> f = new CompletableFuture<>();
            credits.put(coffre, f);
            ouvrir(coffre, 10_000);
            Credits c;
            try { c = f.get(4000, TimeUnit.MILLISECONDS); } catch (Throwable t) { c = null; }
            if (c == null) { fermer(coffre); return "Le coffre à crédits ne répond pas."; }
            if (c.credits() <= 0) { fermer(coffre); return "Le coffre à crédits est vide."; }
            CompletableFuture<Credits> apres = new CompletableFuture<>();
            credits.put(coffre, apres);
            Salle.envoyerEspace(retraitCredits(coffre, c.credits()));
            Credits a;
            try { a = apres.get(4000, TimeUnit.MILLISECONDS); } catch (Throwable t) { a = null; }
            fermer(coffre);
            if (a != null && a.credits() >= c.credits()) return "Le coffre a refusé le retrait des crédits.";
            Journal.succes(Ui.accorder(c.credits() + " crédit(s) retiré(s) du coffre."));
            return null;
        } finally {
            credits.remove(coffre);
            OCCUPE.unlock();
        }
    }

    /** Resultat d'une reprise de stock : nombre repris, ou l'erreur (en francais). */
    record Retrait(int nombre, String erreur) { }

    /**
     * Reprend « quantite » mobis de ce type de sol du coffre a mobis vers
     * l'inventaire (WithdrawItemsFromChest, comme le jeu, coffre ouvert), puis
     * relit le coffre pour verifier. Fil de travail.
     */
    static Retrait retirerMobis(int coffre, int type, int quantite) {
        if (coffre == 0) return new Retrait(0, "Pas de coffre à mobis dans cette salle.");
        if (ouvertParElle(coffre)) return new Retrait(0, "Ferme d'abord le coffre dans le jeu.");
        installer();
        if (!OCCUPE.tryLock()) return new Retrait(0, "Le coffre est déjà en cours d'utilisation par l'Atelier.");
        try {
            Lecture l = new Lecture();
            lectures.put(coffre, l);
            List<Rangement> r;
            try {
                ouvrir(coffre, 15_000);
                try { r = l.fin.get(5000, TimeUnit.MILLISECONDS); }
                catch (TimeoutException e) { r = l.morceaux.isEmpty() ? null : l.tout(); }
                catch (Throwable t) { r = null; }
            } finally { lectures.remove(coffre); }
            if (r == null) { fermer(coffre); return new Retrait(0, "Le coffre à mobis ne répond pas."); }
            int avant = stock(r, type);
            Rangement modele = null;
            for (Rangement x : r) if (!x.mural() && x.typeId() == type) { modele = x; break; }
            if (modele == null || avant == 0) { fermer(coffre); return new Retrait(0, "Ce mobi n'est plus dans le coffre."); }
            int n = Math.max(1, Math.min(quantite, avant));
            Salle.envoyerEspace(retraitMobis(coffre, false, type, modele.affiche(), n));
            Salle.sommeil(1200);
            fermer(coffre);
            Salle.sommeil(300);
            List<Rangement> apres = lireMobis0(coffre);
            if (apres == null) return new Retrait(n, null);           // pas relu : on suppose fait
            int repris = avant - stock(apres, type);
            if (repris <= 0) return new Retrait(0, "Le coffre a refusé la reprise du stock.");
            changement = true;
            return new Retrait(repris, null);
        } finally {
            OCCUPE.unlock();
        }
    }

    /**
     * Depose ces mobis (ids d'inventaire : getPlacementId) dans le coffre a
     * mobis, comme le jeu : ouverture, debut du depot, ajout, accepter, 3 s,
     * confirmer. null si c'est fait, sinon l'erreur (en francais).
     */
    static String deposer(int coffre, List<Integer> ids, Consumer<String> dire) {
        if (coffre == 0) return "Pas de coffre à mobis dans cette salle.";
        if (ids == null || ids.isEmpty()) return "Aucun mobi à déposer.";
        if (ids.size() > 1500) return "Pas plus de 1500 mobis à la fois.";
        if (ouvertParElle(coffre)) return "Ferme d'abord le coffre dans le jeu.";
        installer();
        if (!OCCUPE.tryLock()) return "Le coffre est déjà en cours d'utilisation par l'Atelier.";
        evenements.clear();
        depotJusqua = Long.MAX_VALUE;
        boolean fini = false;
        try {
            ouvrir(coffre, 60_000);
            Salle.envoyerEspace(debutDepot(coffre));
            Object[] e = attendre(6000, "debut", "annule");
            if (e == null) return "Le coffre n'a pas ouvert le dépôt (pas de réponse du jeu).";
            if ("annule".equals(e[0])) { fini = true; return "Dépôt refusé : " + raison((Integer) e[2]) + "."; }

            boolean accepte = false;
            int faits = 0;
            for (int i = 0; i < ids.size(); i += 100) {
                List<Integer> lot = ids.subList(i, Math.min(ids.size(), i + 100));
                Salle.envoyerEspace(ajout(lot));
                Object[] m = attendre(5000, "maj", "annule");
                if (m != null && "annule".equals(m[0])) { fini = true; return "Dépôt annulé : " + raison((Integer) m[2]) + "."; }
                if (m != null) accepte = (Boolean) m[1];
                faits += lot.size();
                if (dire != null && ids.size() > 100) dire.accept("Dépôt : " + faits + " / " + ids.size());
            }
            if (!accepte) {
                Object[] m = attendre(1500, "maj", "annule");
                if (m != null && "annule".equals(m[0])) { fini = true; return "Dépôt annulé : " + raison((Integer) m[2]) + "."; }
                accepte = m != null && (Boolean) m[1];
            }
            if (!accepte) return "Le coffre n'accepte pas ces mobis (non échangeables, ou coffre plein).";

            Salle.envoyerEspace(confirmation(false));
            Object[] a = attendre(3200, "annule");                  // le jeu impose 3 s avant de confirmer
            if (a != null) { fini = true; return "Dépôt annulé : " + raison((Integer) a[2]) + "."; }
            Salle.envoyerEspace(confirmation(true));
            Object[] f = attendre(10_000, "fin", "annule");
            if (f == null) return "Pas de confirmation du coffre : vérifie son contenu dans Config troc.";
            fini = true;
            if ("annule".equals(f[0])) return "Dépôt annulé : " + raison((Integer) f[2]) + ".";
            changement = true;
            return null;
        } finally {
            try {
                if (!fini) Salle.envoyerEspace(annulation());
                fermer(coffre);
            } finally {
                depotJusqua = System.currentTimeMillis() + 2000;     // les derniers evenements restent caches
                OCCUPE.unlock();
            }
        }
    }

    /** Le prochain evenement de troc parmi ces genres (les autres sont passes), ou null apres ms. */
    private static Object[] attendre(long ms, String... genres) {
        long fin = System.currentTimeMillis() + ms;
        Set<String> voulus = Set.of(genres);
        while (true) {
            long r = fin - System.currentTimeMillis();
            if (r <= 0) return null;
            Object[] e;
            try { e = evenements.poll(r, TimeUnit.MILLISECONDS); } catch (InterruptedException x) { return null; }
            if (e == null) return null;
            if (voulus.contains((String) e[0])) return e;
        }
    }

    // ================================================================ vitrines

    /**
     * Ramasse les vitrines posees par l'Atelier dans cette salle dont le type
     * n'est plus dans le coffre a mobis. Fil de travail. Rend le nombre ramassees.
     */
    static int verifierVitrines() {
        int salle = Salle.salleId();
        if (salle == -1) return 0;
        List<ConfigTroc.Vitrine> vs = ConfigTroc.vitrines(salle);
        if (vs.isEmpty()) return 0;
        // vitrines disparues de la salle (ramassees a la main) : oubliees
        for (ConfigTroc.Vitrine v : vs)
            if (Salle.sol(v.id()) == null && Salle.installeeDepuis(5000)) ConfigTroc.oublierVitrine(salle, v.id());
        vs = ConfigTroc.vitrines(salle);
        if (vs.isEmpty()) return 0;
        Coffres c = dansLaSalle();
        if (c.mobis() == 0) return 0;
        List<Rangement> contenu = lireMobis(c.mobis());
        if (contenu == null) return 0;
        int n = 0;
        List<String> noms = new ArrayList<>();
        for (ConfigTroc.Vitrine v : vs) {
            if (stock(contenu, v.type()) > 0 || Salle.sol(v.id()) == null) continue;
            PoseOutils.Signaux.ramassageEnCours(List.of(v.id()));
            Salle.envoyerEspace(PoseOutils.ramassageSol(v.id()));
            ConfigTroc.oublierVitrine(salle, v.id());
            noms.add(Salle.nom(v.type(), false));
            n++;
        }
        if (n == 1) Journal.succes("Plus de " + noms.get(0) + " dans le coffre : vitrine ramassée.");
        else if (n > 1) Journal.succes(n + " vitrines ramassées : leur stock est vide.");
        return n;
    }

    /** Relit le coffre de temps en temps, et quand la salle signale un changement. */
    private static void suivi() {
        long derniere = 0;
        int salleVue = -1;
        while (true) {
            Salle.sommeil(5000);
            try {
                int salle = Salle.salleId();
                if (salle == -1) { salleVue = -1; continue; }
                if (salle != salleVue) { salleVue = salle; derniere = System.currentTimeMillis() - 100_000; }
                if (ConfigTroc.vitrines(salle).isEmpty() || occupe() || !Salle.installeeDepuis(8000)) continue;
                long t = System.currentTimeMillis();
                boolean c = changement;
                if (!(c && t - derniere > 15_000) && t - derniere < 120_000) continue;
                changement = false;
                derniere = t;
                verifierVitrines();
            } catch (Throwable t) {
                Journal.debug("Troc : suivi des vitrines en erreur (" + t + ")");
            }
        }
    }
}

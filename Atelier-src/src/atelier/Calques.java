package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Moteur des calques : masquer et reafficher des mobis UNIQUEMENT chez le
 * client. Le serveur ne sait rien : les mobis restent dans la salle, a leur
 * place, et recharger la salle les fait tous revenir.
 *
 *   masquer     on envoie au client ObjectRemove (sol) ou ItemRemove (mur),
 *               comme si le mobi avait ete ramasse ;
 *   reafficher  on lui renvoie ObjectAdd / ItemAdd, construit depuis l'etat
 *               le plus recent connu (FloorState de G-Presets, a defaut la
 *               copie prise au moment de masquer).
 *
 * Pendant qu'un mobi est masque, tout ce que le serveur envoie a son sujet
 * (deplacement, changement d'etat, glissement sur un rouleau, mouvement wired)
 * est BLOQUE vers le client : un ObjectUpdate le ferait reapparaitre a moitie,
 * et le client Flash supporte mal les mises a jour d'un objet absent. Les
 * paquets qui portent plusieurs mobis (SlideObjectBundle, ObjectsDataUpdate,
 * WiredMovements) sont recopies sans les mobis masques. Les changements d'etat
 * bloques sont gardes et rejoues au moment de reafficher.
 *
 * FloorState de G-Presets ecoute les memes paquets (par nom) : le blocage ne
 * l'empeche pas de les lire, il garde donc la position et l'etat a jour. On ne
 * modifie jamais un paquet en place (il le lirait modifie) : on bloque
 * l'original et on envoie une copie.
 *
 * Un mobi peut etre masque par plusieurs calques a la fois : il ne revient que
 * quand plus aucun calque ne le masque.
 */
public final class Calques {

    private Calques() { }

    // ------------------------------------------------------------ calques

    public static final String WIRED = "wired";
    public static final String TECHNIQUES = "techniques";
    public static final String MURAUX = "muraux";
    public static final String SOLS = "sols";
    public static final String NOM = "nom";
    public static final String RECHERCHE = "recherche";
    public static final String CLIGNOTE = "clignote";

    /** Un mobi masque, avec ce qu'il faut pour le reafficher. */
    private static final class Masque {
        final int id;
        final boolean mural;
        volatile HFloorItem sol;          // copie au moment de masquer, puis derniere mise a jour lue
        volatile HWallItem mur;
        final String proprietaire;
        final Set<String> raisons = ConcurrentHashMap.newKeySet();
        /** Changements d'etat bloques, rejoues apres le retour du mobi (le dernier par sorte). */
        final Map<String, HPacket> rejouer = new ConcurrentHashMap<>();

        Masque(HFloorItem s) {
            id = s.getId(); mural = false; sol = s; mur = null;
            proprietaire = s.getOwnerName() == null ? "" : s.getOwnerName();
        }
        Masque(HWallItem w) {
            id = w.getId(); mural = true; sol = null; mur = w;
            proprietaire = w.getOwnerName() == null ? "" : w.getOwnerName();
        }
    }

    private static final Map<String, Masque> masques = new ConcurrentHashMap<>();
    private static final Object verrou = new Object();
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static final List<Runnable> oublis = new CopyOnWriteArrayList<>();
    private static volatile int salleDesMasques = -1;

    private static String cle(int id, boolean mural) { return (mural ? "m" : "s") + id; }

    public static boolean estMasque(int id, boolean mural) { return masques.containsKey(cle(id, mural)); }

    /** Mobis masques pour de bon : un clignotement (montrer, selection) ne compte pas. */
    public static int total() {
        int n = 0;
        for (Masque m : masques.values()) {
            for (String r : m.raisons)
                if (!CLIGNOTE.equals(r) && !"selection".equals(r)) { n++; break; }
        }
        return n;
    }

    /** Nombre de mobis masques par ce calque (meme s'ils le sont aussi par un autre). */
    public static int nombre(String calque) {
        int n = 0;
        for (Masque m : masques.values()) if (m.raisons.contains(calque)) n++;
        return n;
    }

    /** Prevenu sur le fil JavaFX a chaque changement (compteurs). */
    public static void ecouter(Runnable r) { ecouteurs.add(r); }

    /** Prevenu sur le fil JavaFX quand tout est oublie (changement de salle). */
    public static void surOubli(Runnable r) { oublis.add(r); }

    private static void prevenir() {
        Platform.runLater(() -> { for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { } });
    }

    // ------------------------------------------------------- classement

    /**
     * Mobis « techniques » : ceux qu'on pose pour construire et qu'on ne voit
     * pas (ou ne devrait pas voir) une fois l'appart fini. Reconnus a leur nom
     * technique :
     *   stackmagic / walkmagic   dalles magiques (hauteur, passage)
     *   invis                    tous les « invisible », room_invis*, conf_invis_*
     *   tile_ / bc_tile_ ne sont PAS compris : ce sont des dalles de decor.
     * Les wired (wf_) ont leur propre calque.
     */
    public static boolean estTechnique(String classe) {
        if (classe == null) return false;
        String c = classe.toLowerCase(Locale.ROOT);
        if (Wired.estWired(c)) return false;
        return c.contains("stackmagic") || c.contains("stack_magic")
                || c.contains("walkmagic")
                || c.contains("invis")
                || c.startsWith("room_invisible");
    }

    /** Sol « ordinaire » : ni wired, ni technique. */
    public static boolean estOrdinaire(String classe) {
        return !Wired.estWired(classe) && !estTechnique(classe);
    }

    // --------------------------------------------------- masquer / reafficher

    /**
     * Regle un calque : apres l'appel, il masque EXACTEMENT ces mobis. Ceux
     * qu'il masquait et qui n'y sont plus reviennent (si aucun autre calque ne
     * les masque), les nouveaux disparaissent. A appeler hors fil JavaFX.
     *
     * @return {masques ajoutes, mobis reaffiches}
     */
    public static int[] regler(String calque, Collection<HFloorItem> sols, Collection<HWallItem> murs) {
        if (!pret()) return new int[]{0, 0};
        verifierSalle();
        Set<String> voulus = new HashSet<>();
        if (sols != null) for (HFloorItem s : sols) if (s != null) voulus.add(cle(s.getId(), false));
        if (murs != null) for (HWallItem w : murs) if (w != null) voulus.add(cle(w.getId(), true));

        int ajoutes = 0, revenus = 0, envois = 0;
        synchronized (verrou) {
            // 1. ce que le calque ne doit plus masquer
            for (Map.Entry<String, Masque> e : new ArrayList<>(masques.entrySet())) {
                Masque m = e.getValue();
                if (!m.raisons.contains(calque) || voulus.contains(e.getKey())) continue;
                m.raisons.remove(calque);
                if (m.raisons.isEmpty()) { if (reafficher(m)) revenus++; pause(++envois); }
            }
            // 2. ce qu'il doit masquer en plus
            salleDesMasques = salleCourante();
            if (sols != null) for (HFloorItem s : sols) {
                if (s == null) continue;
                Masque m = masques.get(cle(s.getId(), false));
                if (m != null) { m.raisons.add(calque); continue; }
                m = new Masque(s);
                m.raisons.add(calque);
                masques.put(cle(s.getId(), false), m);       // AVANT l'envoi : bloque les mises a jour
                envoyerClient(retraitSol(s.getId()));
                ajoutes++; pause(++envois);
            }
            if (murs != null) for (HWallItem w : murs) {
                if (w == null) continue;
                Masque m = masques.get(cle(w.getId(), true));
                if (m != null) { m.raisons.add(calque); continue; }
                m = new Masque(w);
                m.raisons.add(calque);
                masques.put(cle(w.getId(), true), m);
                envoyerClient(retraitMur(w.getId()));
                ajoutes++; pause(++envois);
            }
        }
        prevenir();
        return new int[]{ajoutes, revenus};
    }

    /** Masque des mobis de plus sous ce calque, sans toucher a ceux qu'il masque deja. */
    public static int masquer(String calque, Collection<HFloorItem> sols, Collection<HWallItem> murs) {
        List<HFloorItem> s = new ArrayList<>();
        List<HWallItem> w = new ArrayList<>();
        for (Masque m : masques.values()) {
            if (!m.raisons.contains(calque)) continue;
            if (m.mural) w.add(m.mur); else s.add(m.sol);
        }
        if (sols != null) s.addAll(sols);
        if (murs != null) w.addAll(murs);
        return regler(calque, s, w)[0];
    }

    /** Le calque ne masque plus rien. @return mobis revenus. */
    public static int reafficher(String calque) {
        return regler(calque, List.of(), List.of())[1];
    }

    /** Tous les calques sont leves. @return mobis revenus. */
    public static int reafficherTout() {
        int n = 0, envois = 0;
        verifierSalle();
        synchronized (verrou) {
            for (Masque m : new ArrayList<>(masques.values())) {
                m.raisons.clear();
                if (reafficher(m)) n++;
                pause(++envois);
            }
            masques.clear();
        }
        prevenir();
        return n;
    }

    /**
     * Fait clignoter des mobis : masques puis reaffiches plusieurs fois. Ceux
     * deja masques par un autre calque restent masques (rien a montrer).
     * Bloquant : a appeler dans une tache.
     */
    public static void clignoter(Collection<HFloorItem> sols, Collection<HWallItem> murs, int fois, long ms) {
        for (int i = 0; i < fois; i++) {
            regler(CLIGNOTE, sols, murs);
            Salle.sommeil(ms);
            regler(CLIGNOTE, List.of(), List.of());
            if (i < fois - 1) Salle.sommeil(ms);
        }
    }

    /** Retire le masque et renvoie le mobi au client. Sous verrou. */
    private static boolean reafficher(Masque m) {
        masques.remove(cle(m.id, m.mural));
        if (Salle.etat() == null) return false;              // plus en salle : rien a renvoyer
        try {
            HPacket p;
            if (!m.mural) {
                // FloorState fait foi : absent = ramasse entre-temps.
                HFloorItem it = Salle.sol(m.id);
                if (it == null) return false;
                p = paquet("ObjectAdd");
                it.appendToPacket(p);
                String prop = it.getOwnerName();
                p.appendString(prop == null || prop.isEmpty() ? m.proprietaire : prop);
            } else {
                HWallItem it = Salle.mur(m.id);
                if (it == null) return false;
                p = paquet("ItemAdd");
                it.appendToPacket(p);
                String prop = it.getOwnerName();
                p.appendString(prop == null || prop.isEmpty() ? m.proprietaire : prop);
            }
            envoyerClient(p);
            for (HPacket r : m.rejouer.values()) envoyerClient(r);
            return true;
        } catch (Throwable t) {
            System.err.println("[Atelier] calques : reaffichage impossible de " + m.id + " : " + t);
            return false;
        }
    }

    /** ObjectRemove(String id, boolean expire, int idRamasseur, int delai). */
    private static HPacket retraitSol(int id) {
        HPacket p = paquet("ObjectRemove");
        p.appendString(String.valueOf(id));
        p.appendBoolean(false);
        p.appendInt(0);
        p.appendInt(0);
        return p;
    }

    /** ItemRemove(String id, int idRamasseur). */
    private static HPacket retraitMur(int id) {
        HPacket p = paquet("ItemRemove");
        p.appendString(String.valueOf(id));
        p.appendInt(0);
        return p;
    }

    /** Petit souffle tous les 40 envois : le client absorbe mieux. */
    private static void pause(int envois) {
        if (envois % 40 == 0) Salle.sommeil(20);
    }

    // ------------------------------------------------------------- salle

    private static int salleCourante() {
        try { game.FloorState s = Salle.etat(); return s == null ? -1 : s.getRoomId(); }
        catch (Throwable t) { return -1; }
    }

    /** Si on a change de salle depuis les masques, ils sont oublies. */
    private static void verifierSalle() {
        if (masques.isEmpty()) return;
        int c = salleCourante();
        if (c == -1 || c != salleDesMasques) oublier();
    }

    /** Tout oublier, sans rien envoyer : la nouvelle salle arrive complete chez le client. */
    public static void oublier() {
        boolean avait = !masques.isEmpty();
        masques.clear();
        salleDesMasques = -1;
        if (avait) System.out.println("[Atelier] calques : changement de salle, masques oublies.");
        prevenir();
        Platform.runLater(() -> { for (Runnable r : oublis) try { r.run(); } catch (Throwable ignored) { } });
    }

    // ------------------------------------------------------------ envois

    private static final Map<String, Integer> entetes = new ConcurrentHashMap<>();
    /** Empreintes de nos propres envois, au cas ou ils repasseraient par les ecoutes. */
    private static final Set<Integer> nosEnvois = ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<Integer> ordreEnvois = new ArrayDeque<>();

    /** Paquet vers le client : par l'en-tete deja vu, a defaut par son nom. */
    private static HPacket paquet(String nom) {
        Integer h = entetes.get(nom);
        return h != null ? new HPacket(h) : new HPacket(nom, HMessage.Direction.TOCLIENT);
    }

    private static void envoyerClient(HPacket p) {
        GPresets gp = Salle.gp();
        if (gp == null || p == null) return;
        try {
            int e = Arrays.hashCode(p.toBytes());
            synchronized (ordreEnvois) {
                nosEnvois.add(e);
                ordreEnvois.addLast(e);
                while (ordreEnvois.size() > 256) nosEnvois.remove(ordreEnvois.removeFirst());
            }
            gp.sendToClient(p);
        } catch (Throwable t) {
            System.err.println("[Atelier] calques : envoi au client impossible : " + t);
        }
    }

    // ------------------------------------------------------------- ecoute

    private static volatile boolean installe = false, enCours = false;
    private static volatile int ecoutesActives = 0;

    public static boolean pret() { return installe && Salle.gp() != null; }

    /** Nombre d'ecoutes par nom branchees (sur 15) : 0 = rien ne sera bloque. */
    public static int ecoutes() { return ecoutesActives; }

    /** Branche les ecoutes une seule fois, des que G-Presets est la. */
    public static synchronized void installer() {
        if (installe || enCours) return;
        enCours = true;
        Salle.tache("calques-ecoute", () -> {
            for (int i = 0; i < 900 && !installe; i++) {
                GPresets gp = Salle.gp();
                if (gp != null) { brancher(gp); return; }
                Salle.sommeil(1000);
            }
        });
    }

    private static final String[] NOMS = {
            "ObjectUpdate", "ObjectDataUpdate", "ObjectsDataUpdate", "SlideObjectBundle",
            "WiredFurniMove", "WiredMovements", "ObjectRemove", "ObjectAdd",
            "ItemUpdate", "ItemDataUpdate", "ItemRemove", "ItemAdd",
            "Objects", "Items", "RoomReady"
    };

    /**
     * Ecoutes PAR NOM : ce sont exactement les noms qu'ecoute FloorState de
     * G-Presets pour tenir la salle a jour ; s'ils ne se resolvaient pas, la
     * salle de G-Presets serait vide elle aussi.
     */
    private static void brancher(GPresets gp) {
        int n = 0;
        for (String nom : NOMS) {
            try {
                gp.intercept(HMessage.Direction.TOCLIENT, nom, m -> {
                    try { examiner(nom, m); } catch (Throwable ignored) { }
                });
                n++;
            } catch (Throwable t) {
                System.err.println("[Atelier] calques : ecoute " + nom + " indisponible : " + t);
            }
        }
        ecoutesActives = n;
        installe = true;
        System.out.println("[Atelier] calques : " + n + "/" + NOMS.length + " ecoutes actives.");
        prevenir();
    }

    private static void examiner(String nom, HMessage m) {
        HPacket brut = m.getPacket();
        if (brut == null) return;
        entetes.putIfAbsent(nom, brut.headerId());

        switch (nom) {
            case "Objects": case "Items": case "RoomReady":
                if (!masques.isEmpty()) oublier();
                return;
            case "ObjectAdd": case "ItemAdd":
                return;                                    // seulement pour apprendre l'en-tete
            default:
        }
        if (masques.isEmpty()) return;
        if (nosEnvois.contains(Arrays.hashCode(brut.toBytes()))) return;   // notre propre paquet

        HPacket p = new HPacket(brut);
        p.resetReadIndex();
        switch (nom) {
            case "ObjectUpdate": {                         // HFloorItem complet
                int id = p.readInteger();
                Masque k = masques.get(cle(id, false));
                if (k == null) return;
                m.setBlocked(true);
                try { p.resetReadIndex(); k.sol = new HFloorItem(p); } catch (Throwable ignored) { }
                return;
            }
            case "ObjectDataUpdate": {                     // String id, stuffdata
                int id = entier(p.readString());
                Masque k = masques.get(cle(id, false));
                if (k == null) return;
                m.setBlocked(true);
                k.rejouer.put("etat", new HPacket(brut));
                return;
            }
            case "ObjectRemove": {                         // ramasse pour de vrai : on l'oublie
                int id = entier(p.readString());
                if (masques.remove(cle(id, false)) != null) { m.setBlocked(true); prevenir(); }
                return;
            }
            case "WiredFurniMove": {                       // iiii s s i(id) ...
                p.readInteger(); p.readInteger(); p.readInteger(); p.readInteger();
                p.readString(); p.readString();
                int id = p.readInteger();
                if (masques.containsKey(cle(id, false))) m.setBlocked(true);
                return;
            }
            case "SlideObjectBundle": recopierGlissement(m, p); return;
            case "ObjectsDataUpdate": recopierEtats(m, p); return;
            case "WiredMovements":    recopierMouvements(m, p); return;
            case "ItemUpdate": {                           // HWallItem (id en texte)
                int id = entier(p.readString());
                Masque k = masques.get(cle(id, true));
                if (k == null) return;
                m.setBlocked(true);
                try { p.resetReadIndex(); k.mur = new HWallItem(p); } catch (Throwable ignored) { }
                return;
            }
            case "ItemDataUpdate": {                       // String id, String etat
                int id = entier(p.readString());
                Masque k = masques.get(cle(id, true));
                if (k == null) return;
                m.setBlocked(true);
                k.rejouer.put("etat", new HPacket(brut));
                return;
            }
            case "ItemRemove": {
                int id = entier(p.readString());
                if (masques.remove(cle(id, true)) != null) { m.setBlocked(true); prevenir(); }
                return;
            }
            default:
        }
    }

    private static int entier(String s) {
        try { return Integer.decode(s.trim()); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    private static byte[] reste(HPacket p) {
        byte[] o = p.toBytes();
        return Arrays.copyOfRange(o, Math.min(p.getReadIndex(), o.length), o.length);
    }

    /**
     * SlideObjectBundle : int ancienX, ancienY, nouveauX, nouveauY, int n,
     * n × (int id, String deZ, String versZ), puis rouleau et avatar (recopies tels quels).
     */
    private static void recopierGlissement(HMessage m, HPacket p) {
        int ox = p.readInteger(), oy = p.readInteger(), nx = p.readInteger(), ny = p.readInteger();
        int n = p.readInteger();
        if (n < 0 || n > 10000) return;
        List<Object[]> gardes = new ArrayList<>();
        boolean touche = false;
        for (int i = 0; i < n; i++) {
            int id = p.readInteger();
            String de = p.readString(), vers = p.readString();
            if (masques.containsKey(cle(id, false))) touche = true;
            else gardes.add(new Object[]{id, de, vers});
        }
        if (!touche) return;
        HPacket c = new HPacket(p.headerId());
        c.appendInt(ox); c.appendInt(oy); c.appendInt(nx); c.appendInt(ny);
        c.appendInt(gardes.size());
        for (Object[] g : gardes) { c.appendInt((Integer) g[0]); c.appendString((String) g[1]); c.appendString((String) g[2]); }
        c.appendBytes(reste(p));
        m.setBlocked(true);
        envoyerClient(c);
    }

    /** ObjectsDataUpdate : int n, n × (int id, stuffdata). */
    private static void recopierEtats(HMessage m, HPacket p) {
        int n = p.readInteger();
        if (n < 0 || n > 10000) return;
        List<Object[]> gardes = new ArrayList<>();
        boolean touche = false;
        for (int i = 0; i < n; i++) {
            int id = p.readInteger();
            IStuffData st = IStuffData.read(p);
            Masque k = masques.get(cle(id, false));
            if (k == null) { gardes.add(new Object[]{id, st}); continue; }
            touche = true;
            try {   // garde l'etat pour le rejouer, sous forme d'un ObjectDataUpdate seul
                HPacket r = paquet("ObjectDataUpdate");
                r.appendString(String.valueOf(id));
                st.appendToPacket(r);
                k.rejouer.put("etat", r);
            } catch (Throwable ignored) { }
        }
        if (!touche) return;
        m.setBlocked(true);
        if (gardes.isEmpty()) return;
        HPacket c = new HPacket(p.headerId());
        c.appendInt(gardes.size());
        for (Object[] g : gardes) { c.appendInt((Integer) g[0]); ((IStuffData) g[1]).appendToPacket(c); }
        envoyerClient(c);
    }

    /**
     * WiredMovements : int n, n × (int sorte, ...). Meme lecture que FloorState
     * de G-Presets : sorte 1 = mobi (ii, x, y, s, s, id, ii, B[i], B[i]).
     * Une sorte inconnue : on laisse passer le paquet tel quel.
     */
    private static void recopierMouvements(HMessage m, HPacket p) {
        byte[] o = p.toBytes();
        int n = p.readInteger();
        if (n < 0 || n > 10000) return;
        List<byte[]> gardes = new ArrayList<>();
        boolean touche = false;
        for (int i = 0; i < n; i++) {
            int debut = p.getReadIndex();
            int sorte = p.readInteger();
            boolean masque = false;
            switch (sorte) {
                case 0:
                    p.skip("iiiissiiiii");
                    if (p.readBoolean()) p.skip("i");
                    break;
                case 1: {
                    p.skip("ii"); p.readInteger(); p.readInteger();
                    p.skip("s"); p.readString();
                    int id = p.readInteger();
                    p.skip("ii");
                    if (p.readBoolean()) p.skip("i");
                    if (p.readBoolean()) p.skip("i");
                    masque = masques.containsKey(cle(id, false));
                    break;
                }
                case 2: p.skip("iBiiiiiiiii"); break;
                case 3: p.skip("iii"); break;
                default: return;
            }
            if (masque) touche = true;
            else gardes.add(Arrays.copyOfRange(o, debut, p.getReadIndex()));
        }
        if (!touche) return;
        m.setBlocked(true);
        if (gardes.isEmpty()) return;
        HPacket c = new HPacket(p.headerId());
        c.appendInt(gardes.size());
        for (byte[] g : gardes) c.appendBytes(g);
        c.appendBytes(reste(p));
        envoyerClient(c);
    }
}

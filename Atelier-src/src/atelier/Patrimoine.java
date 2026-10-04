package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tes mobis poses dans les apparts : les tiens, et ceux que tu as poses chez
 * les autres.
 *
 * Le serveur dit, pour chaque mobi d'une salle, l'identifiant de son
 * proprietaire. A chaque appart ou tu entres, on compte ceux qui sont a toi
 * et on le retient dans patrimoine.json (dossier de l'Atelier, voir
 * PrixFichier ; repris de l'ancien repertoire/), avec la date. On ne peut
 * pas voir un appart sans y entrer : seuls les apparts visites sont comptes,
 * et leur compte est celui du dernier passage.
 *
 * Ton identifiant vient du paquet UserObject, qu'on redemande au serveur
 * (InfoRetrieve) au demarrage : il est envoye a la connexion, souvent avant
 * que l'Atelier n'ecoute.
 *
 * Les mobis du Builders Club (drapeau isBC de la furnidata) ne se vendent pas :
 * ils ne sont pas comptes.
 */
public final class Patrimoine {

    public static final class Appart {
        public int id;
        public String nom, proprietaire, vuLe;
        /** "1:typeId" (sol) ou "2:typeId" (mur) -> nombre. */
        public final Map<String, Integer> mobis = new LinkedHashMap<>();
        public int total() { int n = 0; for (int v : mobis.values()) n += v; return n; }
    }

    private static final String NOM = "patrimoine.json";
    private static final Map<Integer, Appart> apparts = new ConcurrentHashMap<>();
    private static volatile int moi = -1;
    private static volatile String monPseudo = null;
    private static volatile boolean demarre = false;
    private static final List<Runnable> ecouteurs = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Patrimoine() { }

    /** Ajoute un ecouteur, appele (hors fil FX) quand un appart est compte ou oublie. */
    public static void surMaj(Runnable r) { if (r != null) ecouteurs.add(r); }

    private static void notifier() {
        for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { }
    }
    public static Collection<Appart> apparts() { return new ArrayList<>(apparts.values()); }
    public static String pseudo() { return monPseudo; }

    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        charger();
        Thread t = new Thread(Patrimoine::travailler, "atelier-patrimoine");
        t.setDaemon(true);
        t.start();
    }

    private static void travailler() {
        GPresets gp = null;
        while (gp == null) {
            gp = AtelierLauncher.moteur();
            if (gp == null) dormir(1000);
        }
        ecouterIdentite(gp);
        String vue = null;
        long demandeLe = 0;
        while (true) {
            try {
                if (moi < 0 && System.currentTimeMillis() - demandeLe > 20_000) {
                    demandeLe = System.currentTimeMillis();
                    try { gp.sendToServer(new HPacket("InfoRetrieve", HMessage.Direction.TOSERVER)); }
                    catch (Throwable ignored) { }
                }
                FloorState s = gp.getFloorState();
                if (moi >= 0 && s != null && s.inRoom()) {
                    List<HFloorItem> sols = s.getItems();
                    List<HWallItem> murs = s.getWallItems();
                    String sig = s.getRoomId() + "/" + (sols == null ? 0 : sols.size())
                            + "/" + (murs == null ? 0 : murs.size());
                    if (!sig.equals(vue)) {
                        vue = sig;
                        compter(gp, s.getRoomId(), sols, murs);
                    }
                }
            } catch (Throwable ignored) { }
            dormir(3000);
        }
    }

    /** UserObject : int id, String pseudo, ... Reconnu par nom (envoye rarement). */
    private static void ecouterIdentite(GPresets gp) {
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, "UserObject", m -> {
                try {
                    HPacket p = new HPacket(m.getPacket());
                    p.resetReadIndex();
                    int id = p.readInteger();
                    String pseudo = NomSalle.utf8(p.readString());
                    if (id > 0 && pseudo != null && !pseudo.isEmpty()) {
                        moi = id;
                        monPseudo = pseudo;
                    }
                } catch (Throwable ignored) { }
            });
        } catch (Throwable t) {
            System.err.println("[Atelier] patrimoine : identité illisible : " + t);
        }
    }

    private static void compter(GPresets gp, int salle, List<HFloorItem> sols, List<HWallItem> murs) {
        Appart a = new Appart();
        a.id = salle;
        a.nom = NomSalle.nomValide(gp);
        a.proprietaire = NomSalle.proprietaire();
        a.vuLe = java.time.LocalDate.now().toString();
        if (sols != null) for (HFloorItem it : sols)
            if (it.getOwnerId() == moi && !GrilleCalcul.estFictif(it.getId()) && !bc(gp, it.getTypeId(), false))
                a.mobis.merge(Marche.cle(false, it.getTypeId()), 1, Integer::sum);
        if (murs != null) for (HWallItem it : murs)
            if (it.getOwnerId() == moi && !bc(gp, it.getTypeId(), true))
                a.mobis.merge(Marche.cle(true, it.getTypeId()), 1, Integer::sum);

        Appart avant = apparts.get(salle);
        if (a.nom == null && avant != null) a.nom = avant.nom;
        if (a.mobis.isEmpty()) {
            if (apparts.remove(salle) == null) return;     // rien a toi ici, rien de change
        } else {
            apparts.put(salle, a);
        }
        sauver();
        notifier();
    }

    private static boolean bc(GPresets gp, int typeId, boolean mur) {
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            if (fd == null || !fd.isReady()) return false;
            String cls = mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
            if (cls == null) return false;
            return mur ? fd.getWallItemDetails(cls) != null && fd.getWallItemDetails(cls).isBC
                       : fd.getFloorItemDetails(cls) != null && fd.getFloorItemDetails(cls).isBC;
        } catch (Throwable t) { return false; }
    }

    public static void oublier(int salle) {
        if (apparts.remove(salle) != null) { sauver(); notifier(); }
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // -------------------------------------------------------------- stockage

    private static void charger() {
        JSONObject o = PrixFichier.lire(NOM, NOM);
        if (o == null) return;
        try {
            for (String k : o.keySet()) {
                JSONObject j = o.getJSONObject(k);
                Appart a = new Appart();
                a.id = Integer.parseInt(k);
                a.nom = j.optString("nom", null);
                a.proprietaire = j.optString("proprietaire", null);
                a.vuLe = j.optString("vuLe", null);
                JSONObject m = j.optJSONObject("mobis");
                if (m != null) for (String t : m.keySet()) a.mobis.put(t, m.getInt(t));
                apparts.put(a.id, a);
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] patrimoine : fichier illisible : " + t);
        }
    }

    private static synchronized void sauver() {
        try {
            JSONObject o = new JSONObject();
            for (Appart a : new TreeMap<>(apparts).values()) {
                JSONObject j = new JSONObject();
                if (a.nom != null) j.put("nom", a.nom);
                if (a.proprietaire != null) j.put("proprietaire", a.proprietaire);
                if (a.vuLe != null) j.put("vuLe", a.vuLe);
                j.put("mobis", new JSONObject(a.mobis));
                o.put(String.valueOf(a.id), j);
            }
            if (!PrixFichier.ecrire(NOM, o)) System.err.println("[Atelier] patrimoine : sauvegarde impossible.");
        } catch (Throwable t) {
            System.err.println("[Atelier] patrimoine : sauvegarde impossible : " + t);
        }
    }
}

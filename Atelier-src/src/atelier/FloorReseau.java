package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Paquets de l'editeur de floor.
 *
 * Noms verifies dans le cache Sulek du proxy (dossier Cache du proxy dans Documents, client
 * MAC63 de septembre 2026, tous marques "confident") :
 *   TOSERVER  UpdateFloorProperties (3182), GetRoomEntryTile (2814), GetOccupiedTiles (238)
 *   TOCLIENT  RoomEntryTile (3902), RoomOccupiedTiles (2847), RoomVisualizationSettings (815),
 *             FloorHeightMap (1741), HeightMap (3561), NotificationDialog
 *
 * Structures :
 *   FloorHeightMap   boolean echelle, int hauteurMur, String plan('\r')
 *                    -> VERIFIE : c'est exactement ce que lit EtatSalle.parseFloorPlan (javap).
 *   HeightMap        int largeur, int total, total x ushort (ligne par ligne, y puis x)
 *                    -> VERIFIE : EtatSalle.parseHeightmap ; hauteur = (v & 0x3FFF) / 256.
 *                    Valeur d'une case absente : SUPPOSEE Short.MAX_VALUE (emulateurs).
 *   RoomEntryTile    int x, int y, int direction                       (SUPPOSE, emulateurs + client)
 *   RoomOccupiedTiles int n, puis n x (int x, int y)                    (SUPPOSE)
 *   RoomVisualizationSettings boolean mursCaches, int epMur, int epSol  (SUPPOSE, valeurs -2..1)
 *   UpdateFloorProperties String plan('\r'), int porteX, int porteY, int porteDir,
 *                    int epMur, int epSol, int hauteurMur                (SUPPOSE, ordre des
 *                    emulateurs FloorPlanEditorSaveEvent ; hauteurMur lue en dernier, -1 = auto)
 *   NotificationDialog String type, int n, n x (String cle, String valeur) ; une erreur de
 *                    l'editeur arrive avec un type "floorplan_editor.error" (SUPPOSE).
 *
 * Toutes les ecoutes copient le paquet avant lecture : le moteur de l'Atelier lit les memes.
 */
final class FloorReseau {

    private FloorReseau() { }

    // Derniere porte recue.
    static volatile int porteX = -1, porteY = -1, porteDir = 2;
    static volatile long porteRecue = 0;
    static volatile int porteSalle = -1;

    // Cases occupees selon le serveur.
    static volatile Set<Long> occupees = Set.of();
    static volatile long occupeesRecues = 0;

    // Epaisseurs.
    static volatile boolean mursCaches = false;
    static volatile int epMur = 0, epSol = 0;
    static volatile long visuRecue = 0;

    // Dernier FloorHeightMap recu du serveur (arrivee dans une salle / rechargement).
    static volatile long planRecu = 0;
    static volatile String dernierPlan = null;
    static volatile int dernierMur = -1;
    static volatile boolean derniereEchelle = false;

    // Derniere erreur de l'editeur de floor.
    static volatile String erreur = null;
    static volatile long erreurRecue = 0;

    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static volatile boolean branche = false, enCours = false;

    /** Appele (hors fil FX) a chaque paquet utile recu. */
    static void ecouter(Runnable r) { ecouteurs.add(r); installer(); }

    static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    private static void prevenir() {
        for (Runnable r : ecouteurs) { try { r.run(); } catch (Throwable ignored) { } }
    }

    static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Salle.tache("floor-ecoute", () -> {
            for (int i = 0; i < 900 && !branche; i++) {
                Moteur gp = Salle.gp();
                if (gp != null) {
                    try {
                        brancher(gp);
                        branche = true;
                        Journal.debug("ecoute de l'editeur de floor active.");
                        return;
                    } catch (Throwable ignored) { }
                }
                Salle.sommeil(1000);
            }
        });
    }

    private static void brancher(Moteur gp) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        gp.intercept(C, "RoomEntryTile", m -> {
            try {
                HPacket p = new HPacket(m.getPacket());
                int x = p.readInteger(), y = p.readInteger(), d = p.readInteger();
                if (x < 0 || y < 0 || x > 300 || y > 300) return;
                porteX = x; porteY = y; porteDir = ((d % 8) + 8) % 8;
                porteRecue = System.currentTimeMillis();
                try { porteSalle = Salle.etat() == null ? -1 : Salle.etat().getRoomId(); } catch (Throwable ignored) { }
                prevenir();
            } catch (Throwable ignored) { }
        });
        gp.intercept(C, "RoomOccupiedTiles", m -> {
            try {
                HPacket p = new HPacket(m.getPacket());
                int n = p.readInteger();
                if (n < 0 || n > 100000) return;
                Set<Long> s = new HashSet<>();
                for (int i = 0; i < n; i++) s.add(cle(p.readInteger(), p.readInteger()));
                occupees = s;
                occupeesRecues = System.currentTimeMillis();
                prevenir();
            } catch (Throwable ignored) { }
        });
        gp.intercept(C, "RoomVisualizationSettings", m -> {
            try {
                HPacket p = new HPacket(m.getPacket());
                boolean cache = p.readBoolean();
                int a = p.readInteger(), b = p.readInteger();
                if (a < -2 || a > 1 || b < -2 || b > 1) return;   // structure inattendue
                mursCaches = cache; epMur = a; epSol = b;
                visuRecue = System.currentTimeMillis();
                prevenir();
            } catch (Throwable ignored) { }
        });
        gp.intercept(C, "FloorHeightMap", m -> {
            try {
                HPacket p = new HPacket(m.getPacket());
                boolean e = p.readBoolean();
                int mur = p.readInteger();
                String plan = p.readString();
                derniereEchelle = e; dernierMur = mur; dernierPlan = plan;
                planRecu = System.currentTimeMillis();
                prevenir();
            } catch (Throwable ignored) { }
        });
        try {
            gp.intercept(C, "NotificationDialog", m -> {
                try {
                    HPacket p = new HPacket(m.getPacket());
                    String type = p.readString();
                    if (type == null || !type.toLowerCase().contains("floor")) return;
                    StringBuilder sb = new StringBuilder();
                    int n = p.readInteger();
                    for (int i = 0; i < n && i < 20; i++) {
                        String k = p.readString(), v = p.readString();
                        if (v != null && !v.isBlank()) sb.append(sb.length() > 0 ? " · " : "").append(v);
                        else if (k != null) sb.append(sb.length() > 0 ? " · " : "").append(k);
                    }
                    erreur = sb.length() > 0 ? sb.toString() : type;
                    erreurRecue = System.currentTimeMillis();
                    prevenir();
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ envois

    static boolean pret() { return Salle.gp() != null; }

    static void demanderPorte() { envoyerServeur(new HPacket("GetRoomEntryTile", HMessage.Direction.TOSERVER)); }

    static void demanderOccupees() { envoyerServeur(new HPacket("GetOccupiedTiles", HMessage.Direction.TOSERVER)); }

    private static boolean envoyerServeur(HPacket p) {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        try { return gp.sendToServer(p); } catch (Throwable t) { return false; }
    }

    /** UpdateFloorProperties : applique le plan cote serveur (recharge la salle). */
    static boolean envoyerPlan(FloorModele m) {
        HPacket p = new HPacket("UpdateFloorProperties", HMessage.Direction.TOSERVER);
        p.appendString(m.texte());
        p.appendInt(m.porteX);
        p.appendInt(m.porteY);
        p.appendInt(((m.porteDir % 8) + 8) % 8);
        p.appendInt(m.epMur);
        p.appendInt(m.epSol);
        p.appendInt(m.hauteurMur);
        return envoyerServeur(p);
    }

    /**
     * EXPERIMENTAL : envoie le plan au CLIENT seulement, pour voir le sol dans le
     * jeu sans rien changer cote serveur. Le client Flash peut l'ignorer, ou
     * reconstruire la piece ; recharger la salle remet le vrai plan.
     */
    static boolean apercuClient(FloorModele m, boolean avecHeightMap) {
        Moteur gp = Salle.gp();
        if (gp == null || m == null) return false;
        try {
            HPacket f = new HPacket("FloorHeightMap", HMessage.Direction.TOCLIENT);
            f.appendBoolean(m.echelle != 0);
            f.appendInt(m.hauteurMur);
            f.appendString(m.texte());
            boolean ok = gp.sendToClient(f);
            if (avecHeightMap) {
                Salle.sommeil(80);
                HPacket hm = new HPacket("HeightMap", HMessage.Direction.TOCLIENT);
                hm.appendInt(m.largeur);
                hm.appendInt(m.largeur * m.longueur);
                for (int y = 0; y < m.longueur; y++)
                    for (int x = 0; x < m.largeur; x++) {
                        int v = m.h[x][y];
                        hm.appendUShort(v < 0 ? Short.MAX_VALUE : (v * 256) & 0x3FFF);
                    }
                ok &= gp.sendToClient(hm);
            }
            return ok;
        } catch (Throwable t) { return false; }
    }
}

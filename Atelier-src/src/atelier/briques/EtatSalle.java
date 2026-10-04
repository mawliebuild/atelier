package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'etat de la salle ouverte : mobis de sol et muraux, plan, hauteurs, porte,
 * murs. Remplace game.FloorState, en ecoute seule : rien n'est bloque, rien
 * n'est envoye (la demande de la salle, GetHeightMap, viendra plus tard).
 *
 * Paquets (TOCLIENT sauf Quit), memes lectures que FloorState :
 *   RoomReady          String modele                 -> tout remis a zero
 *   RoomEntryInfo      int salleId, ...
 *   FloorHeightMap     byte echelle, int hauteurMurs, String plan ('\r')
 *   HeightMap          int largeur, int total, total x ushort (ligne par ligne)
 *   HeightMapUpdate    byte n, n x (byte x, byte y, ushort)
 *   Objects / Items    proprietaires puis mobis (parseurs HFloorItem / HWallItem)
 *   ObjectAdd          HFloorItem, String proprietaire ; ObjectUpdate : HFloorItem
 *   ObjectRemove       String id ; ItemRemove : String id
 *   ItemAdd            HWallItem, String proprietaire ; ItemUpdate : HWallItem
 *   ObjectDataUpdate   String id, stuffdata ; ObjectsDataUpdate : int n, n x (int id, stuffdata)
 *   ItemDataUpdate     String id, String etat
 *   SlideObjectBundle, WiredFurniMove, WiredMovements : nouvelles positions
 *   CloseConnection, Quit (TOSERVER) : tout remis a zero
 * En plus de FloorState : RoomEntryTile (int x, int y, int direction) pour la
 * porte, RoomVisualizationSettings (boolean mursCaches, int epMur, int epSol).
 *
 * Fils : les ecouteurs ecrivent sous un verrou unique, les lectures (tous fils)
 * prennent le meme verrou et rendent des copies. Les paquets sont lus sur place,
 * sans copie. Les HFloorItem rendus par getItems sont ceux de l'etat (comme
 * FloorState) ; les fiches MobiSol / MobiMur sont des instantanes immuables.
 */
final class EtatSalle {

    /** Un mobi de sol fige a l'instant de la lecture. */
    record MobiSol(int id, int type, int x, int y, double z, int rotation, String etat, IStuffData donnees,
                   int proprietaireId, String proprietaire) {
        static MobiSol de(HFloorItem f) {
            HPoint t = f.getTile();
            IStuffData d = f.getStuff();
            return new MobiSol(f.getId(), f.getTypeId(), t.getX(), t.getY(), t.getZ(),
                    f.getFacing() == null ? 0 : f.getFacing().ordinal(),
                    d == null ? null : d.getLegacyString(), d, f.getOwnerId(), f.getOwnerName());
        }
    }

    /** Un mural fige a l'instant de la lecture (position au format du jeu, « :w=x,y l=dx,dy r »). */
    record MobiMur(int id, int type, String position, String etat, int proprietaireId, String proprietaire) {
        static MobiMur de(HWallItem w) {
            return new MobiMur(w.getId(), w.getTypeId(), w.getLocation(), w.getState(), w.getOwnerId(), w.getOwnerName());
        }

        /** La position lue, ou null si elle n'est pas au format attendu. */
        PositionMur positionMur() { return PositionMur.lireOuNull(position); }
    }

    private final Object verrou = new Object();

    // tout ce qui suit est garde par verrou (volatile pour les lectures simples sans verrou)
    private volatile int salleId;
    private volatile String modele;
    private volatile String planBrut;
    private volatile int echelle, hauteurMurs;
    private char[][] plan;                                  // [x][y]
    private int[][] hauteurs;                               // [x][y], valeurs brutes
    private Map<Integer, HFloorItem> sols;                  // null hors salle
    private Map<Integer, Map<Integer, HFloorItem>> parType;
    private Map<Long, Map<Integer, HFloorItem>> parCase;
    private Map<Integer, HWallItem> murs;                   // null avant le premier Items
    private volatile int porteX = -1, porteY = -1, porteDirection = -1;
    private volatile boolean mursCaches;
    private volatile int epaisseurMur = Integer.MIN_VALUE, epaisseurSol = Integer.MIN_VALUE;
    private volatile int generation;

    EtatSalle(Canal canal) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "Objects", this::surObjets);
        canal.intercept(C, "ObjectAdd", this::surAjoutSol);
        canal.intercept(C, "ObjectRemove", this::surRetraitSol);
        canal.intercept(C, "ObjectUpdate", this::surMajSol);
        canal.intercept(C, "SlideObjectBundle", this::surGlissement);
        canal.intercept(C, "WiredFurniMove", this::surMouvementWired);
        canal.intercept(C, "WiredMovements", this::surMouvementsWired);
        canal.intercept(C, "ObjectDataUpdate", this::surDonneesSol);
        canal.intercept(C, "ObjectsDataUpdate", this::surDonneesSols);
        canal.intercept(C, "Items", this::surMuraux);
        canal.intercept(C, "ItemAdd", this::surAjoutMur);
        canal.intercept(C, "ItemRemove", this::surRetraitMur);
        canal.intercept(C, "ItemUpdate", this::surMajMur);
        canal.intercept(C, "ItemDataUpdate", this::surDonneesMur);
        canal.intercept(C, "HeightMap", this::surHauteurs);
        canal.intercept(C, "HeightMapUpdate", this::surMajHauteurs);
        canal.intercept(C, "FloorHeightMap", this::surPlan);
        canal.intercept(C, "RoomEntryInfo", this::surEntree);
        canal.intercept(C, "RoomEntryTile", this::surPorte);
        canal.intercept(C, "RoomVisualizationSettings", this::surMursVisibles);
        canal.intercept(C, "CloseConnection", m -> reset());
        canal.intercept(HMessage.Direction.TOSERVER, "Quit", m -> reset());
        canal.intercept(C, "RoomReady", this::surSallePrete);
    }

    // ================================================================ ecouteurs

    private void surSallePrete(HMessage m) {
        String mod = null;
        try { mod = m.getPacket().readString(); } catch (Exception ignored) { }
        reset();
        synchronized (verrou) { modele = mod; }
    }

    private void surEntree(HMessage m) {
        int id = m.getPacket().readInteger();
        synchronized (verrou) { salleId = id; }
    }

    private void surPorte(HMessage m) {
        HPacket p = m.getPacket();
        int x = p.readInteger(), y = p.readInteger(), d = p.readInteger();
        synchronized (verrou) { porteX = x; porteY = y; porteDirection = d; }
    }

    private void surMursVisibles(HMessage m) {
        HPacket p = m.getPacket();
        boolean caches = p.readBoolean();
        int mur = p.readInteger(), sol = p.readInteger();
        synchronized (verrou) { mursCaches = caches; epaisseurMur = mur; epaisseurSol = sol; }
    }

    private void surPlan(HMessage m) {
        HPacket p = m.getPacket();
        byte ech = p.readByte();
        int hm = p.readInteger();
        String brut = p.readString();
        String[] lignes = brut.split("\r");
        int largeur = lignes.length == 0 ? 0 : lignes[0].length();
        char[][] pl = new char[largeur][lignes.length];
        for (int x = 0; x < largeur; x++)
            for (int y = 0; y < lignes.length; y++)
                pl[x][y] = x < lignes[y].length() ? lignes[y].charAt(x) : 'x';
        synchronized (verrou) {
            plan = pl;
            planBrut = brut;
            echelle = ech;
            hauteurMurs = hm;
        }
    }

    private void surHauteurs(HMessage m) {
        HPacket p = m.getPacket();
        int colonnes = p.readInteger();
        int total = p.readInteger();
        int lignes = colonnes <= 0 ? 0 : total / colonnes;
        int[][] h = new int[Math.max(colonnes, 0)][lignes];
        for (int y = 0; y < lignes; y++)
            for (int x = 0; x < colonnes; x++)
                h[x][y] = p.readUshort();
        synchronized (verrou) { hauteurs = h; }
    }

    private void surMajHauteurs(HMessage m) {
        HPacket p = m.getPacket();
        synchronized (verrou) {
            if (hauteurs == null) return;
            int n = p.readByte() & 0xFF;
            for (int i = 0; i < n; i++) {
                int x = p.readByte() & 0xFF, y = p.readByte() & 0xFF;
                int v = p.readUshort();
                if (x < hauteurs.length && y < hauteurs[x].length) hauteurs[x][y] = v;
            }
        }
    }

    private void surObjets(HMessage m) {
        HFloorItem[] lus = HFloorItem.parse(m.getPacket());
        synchronized (verrou) {
            if (sols == null) {
                sols = new HashMap<>(lus.length * 2 + 16);
                parType = new HashMap<>();
                parCase = new HashMap<>();
            }
            for (HFloorItem f : lus) ajouter(f);
            generation++;
        }
    }

    private void surAjoutSol(HMessage m) {
        if (!inRoom()) return;
        HPacket p = m.getPacket();
        HFloorItem f = new HFloorItem(p);
        f.setOwnerName(p.readString());
        synchronized (verrou) { if (sols != null) ajouter(f); }
    }

    private void surMajSol(HMessage m) {
        if (!inRoom()) return;
        HFloorItem f = new HFloorItem(m.getPacket());
        synchronized (verrou) {
            if (sols == null) return;
            HFloorItem ancien = retirer(f.getId());
            f.setOwnerName(ancien == null ? "" : ancien.getOwnerName());
            ajouter(f);
        }
    }

    private void surRetraitSol(HMessage m) {
        if (!inRoom()) return;
        int id = Integer.parseInt(m.getPacket().readString());
        synchronized (verrou) { if (sols != null) retirer(id); }
    }

    private void surGlissement(HMessage m) {
        if (!inRoom()) return;
        HPacket p = m.getPacket();
        p.readInteger();
        p.readInteger();
        int nx = p.readInteger(), ny = p.readInteger();
        int n = p.readInteger();
        synchronized (verrou) {
            for (int i = 0; i < n; i++) {
                int id = p.readInteger();
                p.readString();
                String nz = p.readString();
                deplacer(id, nx, ny, nz);
            }
        }
    }

    private void surMouvementWired(HMessage m) {
        if (!inRoom()) return;
        HPacket p = m.getPacket();
        p.readInteger();
        p.readInteger();
        int nx = p.readInteger(), ny = p.readInteger();
        p.readString();
        String nz = p.readString();
        int id = p.readInteger();
        synchronized (verrou) { deplacer(id, nx, ny, nz); }
    }

    /** Meme lecture que FloorState.onWiredMovements (genre 1 = mobi de sol). */
    private void surMouvementsWired(HMessage m) {
        if (!inRoom()) return;
        HPacket p = m.getPacket();
        int n = p.readInteger();
        synchronized (verrou) {
            for (int i = 0; i < n; i++) {
                switch (p.readInteger()) {
                    case 0 -> {
                        p.skip("iiiissiiiii");
                        if (p.readBoolean()) p.skip("i");
                    }
                    case 1 -> {
                        p.skip("ii");
                        int nx = p.readInteger(), ny = p.readInteger();
                        p.skip("s");
                        String nz = p.readString();
                        int id = p.readInteger();
                        p.skip("ii");
                        if (p.readBoolean()) p.skip("i");
                        if (p.readBoolean()) p.skip("i");
                        deplacer(id, nx, ny, nz);
                    }
                    case 2 -> p.skip("iBiiiiiiiii");
                    case 3 -> p.skip("iii");
                    default -> { return; }          // genre inconnu : la suite est illisible
                }
            }
        }
    }

    private void surDonneesSol(HMessage m) {
        HPacket p = m.getPacket();
        int id = Integer.parseInt(p.readString());
        donnees(p, id);
    }

    private void surDonneesSols(HMessage m) {
        HPacket p = m.getPacket();
        int n = p.readInteger();
        for (int i = 0; i < n; i++) {
            int id = p.readInteger();
            donnees(p, id);
        }
    }

    private void donnees(HPacket p, int id) {
        IStuffData d = IStuffData.read(p);
        synchronized (verrou) {
            HFloorItem f = sols == null ? null : sols.get(id);
            if (f != null && inRoom()) f.setStuff(d);
        }
    }

    private void surMuraux(HMessage m) {
        HWallItem[] lus = HWallItem.parse(m.getPacket());
        synchronized (verrou) {
            if (murs == null) murs = new HashMap<>(lus.length * 2 + 16);
            for (HWallItem w : lus) murs.put(w.getId(), w);
        }
    }

    private void surAjoutMur(HMessage m) {
        if (murs == null) return;
        HPacket p = m.getPacket();
        HWallItem w = new HWallItem(p);
        w.setOwnerName(p.readString());
        synchronized (verrou) { if (murs != null) murs.put(w.getId(), w); }
    }

    private void surMajMur(HMessage m) {
        if (murs == null) return;
        HWallItem w = new HWallItem(m.getPacket());
        synchronized (verrou) {
            if (murs == null) return;
            HWallItem ancien = murs.remove(w.getId());
            w.setOwnerName(ancien == null ? "" : ancien.getOwnerName());
            murs.put(w.getId(), w);
        }
    }

    private void surRetraitMur(HMessage m) {
        if (murs == null) return;
        int id = Integer.parseInt(m.getPacket().readString());
        synchronized (verrou) { if (murs != null) murs.remove(id); }
    }

    private void surDonneesMur(HMessage m) {
        HPacket p = m.getPacket();
        int id = Integer.parseInt(p.readString());
        synchronized (verrou) {
            HWallItem w = murs == null ? null : murs.get(id);
            if (w != null) w.setState(p.readString());
        }
    }

    // ================================================================ interne (sous verrou)

    private static long cle(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    private void ajouter(HFloorItem f) {
        sols.put(f.getId(), f);
        parType.computeIfAbsent(f.getTypeId(), k -> new LinkedHashMap<>()).put(f.getId(), f);
        HPoint t = f.getTile();
        parCase.computeIfAbsent(cle(t.getX(), t.getY()), k -> new LinkedHashMap<>(4)).put(f.getId(), f);
    }

    private HFloorItem retirer(int id) {
        HFloorItem f = sols.remove(id);
        if (f == null) return null;
        HPoint t = f.getTile();
        Map<Integer, HFloorItem> c = parCase.get(cle(t.getX(), t.getY()));
        if (c != null && c.remove(id) != null && c.isEmpty()) parCase.remove(cle(t.getX(), t.getY()));
        Map<Integer, HFloorItem> ty = parType.get(f.getTypeId());
        if (ty != null) ty.remove(id);
        return f;
    }

    private void deplacer(int id, int x, int y, String z) {
        HFloorItem f = sols == null ? null : sols.get(id);
        if (f == null) return;
        HPoint t = f.getTile();
        Map<Integer, HFloorItem> c = parCase.get(cle(t.getX(), t.getY()));
        if (c != null && c.remove(id) != null && c.isEmpty()) parCase.remove(cle(t.getX(), t.getY()));
        f.setTile(new HPoint(x, y, Double.parseDouble(z)));
        parCase.computeIfAbsent(cle(x, y), k -> new LinkedHashMap<>(4)).put(id, f);
    }

    // ================================================================ API francaise

    /** Vrai quand mobis de sol, muraux, plan, hauteurs et numero de salle sont connus. */
    boolean dansUneSalle() {
        synchronized (verrou) {
            return sols != null && murs != null && plan != null && hauteurs != null && salleId != 0;
        }
    }

    /** Ce qui manque pour etre « dans une salle », en francais ; null si rien ne manque. */
    String manque() {
        List<String> l = new ArrayList<>();
        synchronized (verrou) {
            if (salleId == 0) l.add("numéro de salle");
            if (plan == null) l.add("plan du sol");
            if (hauteurs == null) l.add("hauteurs");
            if (sols == null) l.add("mobis de sol");
            if (murs == null) l.add("mobis muraux");
        }
        return l.isEmpty() ? null : Ui.majuscule(String.join(", ", l)) + ".";
    }

    /** Augmente a chaque liste de mobis de sol recue (entree dans une salle, rechargement). */
    int generation() { return generation; }

    int salleId() { return salleId; }

    String modele() { return modele; }

    List<MobiSol> sols() {
        synchronized (verrou) {
            if (!dansUneSalle()) return new ArrayList<>();
            List<MobiSol> l = new ArrayList<>(sols.size());
            for (HFloorItem f : sols.values()) l.add(MobiSol.de(f));
            return l;
        }
    }

    List<MobiMur> murs() {
        synchronized (verrou) {
            if (murs == null) return new ArrayList<>();
            List<MobiMur> l = new ArrayList<>(murs.size());
            for (HWallItem w : murs.values()) l.add(MobiMur.de(w));
            return l;
        }
    }

    MobiSol sol(int id) {
        synchronized (verrou) {
            HFloorItem f = sols == null ? null : sols.get(id);
            return f == null ? null : MobiSol.de(f);
        }
    }

    MobiMur mur(int id) {
        synchronized (verrou) {
            HWallItem w = murs == null ? null : murs.get(id);
            return w == null ? null : MobiMur.de(w);
        }
    }

    int nombreSols() { synchronized (verrou) { return sols == null ? 0 : sols.size(); } }

    int nombreMurs() { synchronized (verrou) { return murs == null ? 0 : murs.size(); } }

    List<MobiSol> solsSurCase(int x, int y) {
        synchronized (verrou) {
            List<MobiSol> l = new ArrayList<>();
            if (!dansUneSalle()) return l;
            Map<Integer, HFloorItem> c = parCase.get(cle(x, y));
            if (c != null) for (HFloorItem f : c.values()) l.add(MobiSol.de(f));
            return l;
        }
    }

    List<MobiSol> solsDeType(int type) {
        synchronized (verrou) {
            List<MobiSol> l = new ArrayList<>();
            if (!dansUneSalle()) return l;
            Map<Integer, HFloorItem> t = parType.get(type);
            if (t != null) for (HFloorItem f : t.values()) l.add(MobiSol.de(f));
            return l;
        }
    }

    /** Le plan brut (lignes separees par '\r'), ou null. */
    String planBrut() { return planBrut; }

    /** Caractere du plan en (x, y) : '0'..'9', 'a'..'z' ou 'x' (hors plan). */
    char caseDuPlan(int x, int y) {
        synchronized (verrou) {
            return plan != null && x >= 0 && y >= 0 && x < plan.length && y < plan[x].length ? plan[x][y] : 'x';
        }
    }

    int largeurPlan() { synchronized (verrou) { return plan == null ? 0 : plan.length; } }

    int longueurPlan() { synchronized (verrou) { return plan == null || plan.length == 0 ? 0 : plan[0].length; } }

    /** Hauteur de la case (HeightMap), en cases ; NaN si inconnue. */
    double hauteurCase(int x, int y) {
        synchronized (verrou) {
            if (hauteurs == null || x < 0 || y < 0 || x >= hauteurs.length || y >= hauteurs[x].length) return Double.NaN;
            return (hauteurs[x][y] & 0x3FFF) / 256.0;
        }
    }

    /** Valeur brute de la HeightMap (ushort), ou -1. */
    int hauteurBrute(int x, int y) {
        synchronized (verrou) {
            if (hauteurs == null || x < 0 || y < 0 || x >= hauteurs.length || y >= hauteurs[x].length) return -1;
            return hauteurs[x][y];
        }
    }

    /** Echelle du plan (octet de FloorHeightMap). */
    int echelle() { return echelle; }

    /** Hauteur des murs (FloorHeightMap). */
    int hauteurMurs() { return hauteurMurs; }

    /** La porte (RoomEntryTile), ou null si le jeu ne l'a pas encore dite. */
    HPoint porte() { int x = porteX, y = porteY; return x < 0 ? null : new HPoint(x, y); }

    int porteDirection() { return porteDirection; }

    /** Epaisseur des murs (RoomVisualizationSettings), Integer.MIN_VALUE si inconnue. */
    int epaisseurMur() { return epaisseurMur; }

    /** Epaisseur du sol (RoomVisualizationSettings), Integer.MIN_VALUE si inconnue. */
    int epaisseurSol() { return epaisseurSol; }

    boolean mursCaches() { return mursCaches; }

    /** Oublie tout (sortie de salle, RoomReady, CloseConnection, Quit). */
    void reset() {
        synchronized (verrou) {
            hauteurs = null;
            sols = null;
            parType = null;
            parCase = null;
            plan = null;
            murs = null;
            salleId = 0;
            planBrut = null;
            echelle = 0;
            hauteurMurs = 0;
            modele = null;
            porteX = porteY = porteDirection = -1;
            mursCaches = false;
            epaisseurMur = epaisseurSol = Integer.MIN_VALUE;
        }
    }

    // ================================================================ noms d'origine (FloorState)

    boolean inRoom() { return dansUneSalle(); }

    int getRoomId() { return salleId; }

    String getRawFloorplan() { return planBrut; }

    int getFloorScale() { return echelle; }

    int getFloorWallHeight() { return hauteurMurs; }

    String getRoomModelName() { return modele; }

    int getFloorplanWidth() { return largeurPlan(); }

    int getFloorplanHeight() { return longueurPlan(); }

    char floorHeight(int x, int y) { return caseDuPlan(x, y); }

    /** Comme FloorState : (v & 0x3FFF) / 256 ; 0 si inconnue. */
    double getTileHeight(int x, int y) { double h = hauteurCase(x, y); return Double.isNaN(h) ? 0 : h; }

    HFloorItem furniFromId(int id) { synchronized (verrou) { return sols == null ? null : sols.get(id); } }

    HWallItem wallItemFromId(int id) { synchronized (verrou) { return murs == null ? null : murs.get(id); } }

    List<HFloorItem> getItems() {
        synchronized (verrou) { return dansUneSalle() ? new ArrayList<>(sols.values()) : new ArrayList<>(); }
    }

    List<HWallItem> getWallItems() {
        synchronized (verrou) { return murs == null ? new ArrayList<>() : new ArrayList<>(murs.values()); }
    }

    List<HFloorItem> getFurniOnTile(int x, int y) {
        synchronized (verrou) {
            if (!dansUneSalle()) return new ArrayList<>();
            Map<Integer, HFloorItem> c = parCase.get(cle(x, y));
            return c == null ? new ArrayList<>() : new ArrayList<>(c.values());
        }
    }

    List<HFloorItem> getItemsFromType(int type) {
        synchronized (verrou) {
            if (!dansUneSalle()) return new ArrayList<>();
            Map<Integer, HFloorItem> t = parType.get(type);
            return t == null ? new ArrayList<>() : new ArrayList<>(t.values());
        }
    }

    /** Par classe, via notre furnidata (ex-getItemsFromType(FurniDataTools, String)). */
    List<HFloorItem> getItemsFromType(Furnidata f, String classe) {
        Integer t = f == null || !f.pret() ? null : f.typeSol(classe);
        return t == null ? new ArrayList<>() : getItemsFromType(t);
    }
}

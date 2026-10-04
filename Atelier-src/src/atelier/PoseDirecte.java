package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Pose de copies mobi par mobi, SANS dalle magique ni moteur de pose :
 * on pose un mobi (inventaire ou BC selon la source), on attend qu'il
 * apparaisse, on lui donne son altitude (@altitude), puis le suivant. Du bas
 * vers le haut ; a la fin, une verification remet les altitudes qui ne
 * seraient pas prises. La dalle magique reste pour construire (Hauteur fixe).
 *
 * Les reglages des wired ne sont pas poses ici (la copie avec wired passe par
 * le collage wired du moteur de pose).
 */
final class PoseDirecte {

    private PoseDirecte() { }

    /** Un mobi de sol a poser : case, altitude ABSOLUE voulue, rotation, etat (null = laisse tel quel). */
    static final class Sol {
        final String classe, etat;
        final int x, y, rot;
        final double z;
        Sol(String classe, int x, int y, double z, int rot) { this(classe, x, y, z, rot, null); }
        Sol(String classe, int x, int y, double z, int rot, String etat) {
            this.classe = classe; this.x = x; this.y = y; this.z = z; this.rot = rot & 7; this.etat = etat;
        }
    }

    /** Un mobi mural a poser : position complete (« :w=x,y l=a,b r »). */
    static final class Mur {
        final String classe, position;
        Mur(String classe, String position) { this.classe = classe; this.position = position; }
    }

    static final class Resultat {
        final List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
        int manquants, hauteursFausses, etatsFaux;
    }

    /** Attente maxi qu'un mobi pose apparaisse. */
    private static final long ATTENTE_MS = 2500;

    static Resultat poser(List<Sol> sols, List<Mur> murs, Generateur.Source source, Consumer<String> dire,
                          BooleanSupplier stop, java.util.function.BiConsumer<Integer, Integer> progres) {
        Resultat r = new Resultat();
        GPresets gp = Salle.gp();
        if (gp == null) return r;
        furnidata.FurniDataTools fd = gp.getFurniDataTools();
        int total = sols.size() + murs.size(), fait = 0;
        Set<Integer> invPris = new HashSet<>();
        List<Sol> ordre = new ArrayList<>(sols);
        ordre.sort(Comparator.comparingDouble((Sol s) -> s.z).thenComparingInt(s -> s.y).thenComparingInt(s -> s.x));
        Map<Integer, Double> voulu = new LinkedHashMap<>();
        Map<Integer, String> etats = new LinkedHashMap<>();

        for (Sol s : ordre) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getFloorTypeId(s.classe);
            if (type == null) { r.manquants++; continue; }
            Set<Integer> avant = new HashSet<>();
            for (HFloorItem it : Salle.sols()) avant.add(it.getId());
            if (!envoyerSol(gp, type, s, source, invPris)) { r.manquants++; continue; }
            int id = attendreSol(avant, type, s.x, s.y);
            if (id < 0) { r.manquants++; continue; }
            r.sols.add(id);
            voulu.put(id, s.z);
            if (s.etat != null) etats.put(id, s.etat);
            HFloorItem it = Salle.sol(id);
            // rotation refusee a la pose (mobi a 2 orientations...) : on la redonne, puis son equivalente
            if (it != null && !GroupeCalcul.rotationAcceptee(Salle.rotation(it), s.rot, s.rot, false)) {
                for (int rot : new int[]{s.rot, GroupeCalcul.rotationRepli(s.rot)}) {
                    espacer();
                    Salle.deplacerSol(id, s.x, s.y, rot);
                    dernierEnvoi = System.currentTimeMillis();
                    for (int i = 0; i < 6; i++) {
                        Salle.sommeil(80);
                        it = Salle.sol(id);
                        if (it != null && Salle.rotation(it) == rot) break;
                    }
                    if (it != null && Salle.rotation(it) == rot) break;
                }
            }
            // son altitude tout de suite (au-dessus de ce qui est deja pose)
            if (it != null && Math.abs(it.getTile().getZ() - s.z) > 0.01) altitude(id, s.z);
            progres.accept(++fait, total);
        }
        for (Mur m : murs) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getWallTypeId(m.classe);
            if (type == null) { r.manquants++; continue; }
            Set<Integer> avant = new HashSet<>();
            for (HWallItem w : Salle.murs()) avant.add(w.getId());
            if (!envoyerMur(gp, type, m, source, invPris)) { r.manquants++; continue; }
            int id = attendreMur(avant, type);
            if (id < 0) { r.manquants++; continue; }
            r.murs.add(id);
            progres.accept(++fait, total);
        }

        // verification : les altitudes que le serveur n'aurait pas prises
        if (!voulu.isEmpty() && !stop.getAsBoolean()) {
            Salle.sommeil(600);
            for (int passe = 0; passe < 2; passe++) {
                List<Integer> faux = new ArrayList<>();
                for (Map.Entry<Integer, Double> e : voulu.entrySet()) {
                    HFloorItem it = Salle.sol(e.getKey());
                    if (it != null && Math.abs(it.getTile().getZ() - e.getValue()) > 0.02) faux.add(e.getKey());
                }
                if (faux.isEmpty()) break;
                dire.accept("Altitudes à reprendre : " + faux.size() + "…");
                for (int id : faux) altitude(id, voulu.get(id));   // altitude() espace les envois
                Salle.sommeil(700);
            }
            for (Map.Entry<Integer, Double> e : voulu.entrySet()) {
                HFloorItem it = Salle.sol(e.getKey());
                if (it != null && Math.abs(it.getTile().getZ() - e.getValue()) > 0.02) r.hauteursFausses++;
            }
        }
        if (!etats.isEmpty() && !stop.getAsBoolean()) r.etatsFaux = etats(gp, etats, stop);
        return r;
    }

    /**
     * Les etats (couleur d'un bloc, lampe allumee...) : comme le moteur de l'Atelier, on
     * « utilise » le mobi (UseFurniture) jusqu'a retrouver l'etat d'origine.
     * Seulement des etats numeriques ; tous les mobis a la fois, tour par
     * tour ; un mobi dont l'etat ne bouge pas quand on l'utilise est laisse.
     * @return nombre de mobis restes dans un autre etat
     */
    private static int etats(GPresets gp, Map<Integer, String> voulus, BooleanSupplier stop) {
        Map<Integer, String> reste = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : voulus.entrySet())
            if (e.getValue() != null && e.getValue().matches("\\d{1,2}")) reste.put(e.getKey(), e.getValue());
        for (int tour = 0; tour < 16 && !stop.getAsBoolean(); tour++) {
            Map<Integer, String> avant = new HashMap<>();
            for (Iterator<Map.Entry<Integer, String>> i = reste.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<Integer, String> e = i.next();
                HFloorItem it = Salle.sol(e.getKey());
                if (it == null) { i.remove(); continue; }
                String a = Generateur.etatDe(it);
                if (a.equals(e.getValue())) { i.remove(); continue; }
                avant.put(e.getKey(), a);
            }
            if (reste.isEmpty()) return 0;
            for (int id : reste.keySet()) {
                espacer();
                gp.sendToServer(new HPacket("UseFurniture", HMessage.Direction.TOSERVER, id, 0));
                dernierEnvoi = System.currentTimeMillis();
            }
            Salle.sommeil(500);
            // ceux que l'utilisation ne change pas ne changeront jamais
            for (Iterator<Map.Entry<Integer, String>> i = reste.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<Integer, String> e = i.next();
                HFloorItem it = Salle.sol(e.getKey());
                if (it == null || Generateur.etatDe(it).equals(avant.get(e.getKey()))) i.remove();
            }
        }
        int faux = 0;
        for (Map.Entry<Integer, String> e : voulus.entrySet()) {
            HFloorItem it = Salle.sol(e.getKey());
            if (it != null && e.getValue() != null && e.getValue().matches("\\d{1,2}")
                    && !Generateur.etatDe(it).equals(e.getValue())) faux++;
        }
        return faux;
    }

    /** @altitude : lue dans la liste du jeu ou retenue, cherchee seulement en dernier recours. */
    private static void altitude(int id, double z) {
        espacer();
        try { OutilMiroir.Altitude.mettre(id, z); } finally { dernierEnvoi = System.currentTimeMillis(); }
    }

    /** Au moins ECART_MS entre deux envois au serveur (pose ou altitude). */
    static final long ECART_MS = 150;
    private static volatile long dernierEnvoi = 0;

    private static void espacer() {
        long attente = ECART_MS - (System.currentTimeMillis() - dernierEnvoi);
        if (attente > 0) Salle.sommeil(attente);
    }

    /** Envoie un paquet en respectant l'ecart ; false si la connexion le refuse. */
    private static boolean envoyer(GPresets gp, HPacket p) {
        espacer();
        try { return gp.sendToServer(p); }
        catch (Throwable t) { return false; }
        finally { dernierEnvoi = System.currentTimeMillis(); }
    }

    // ---------------------------------------------------------------- envoi

    private static boolean envoyerSol(GPresets gp, int type, Sol s, Generateur.Source source, Set<Integer> invPris) {
        boolean invOk = source != Generateur.Source.BC, bcOk = source != Generateur.Source.INVENTAIRE;
        boolean bcDabord = source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE;
        if (bcDabord && bcOk && solBC(gp, type, s)) return true;
        if (invOk && solInventaire(gp, type, s, invPris)) return true;
        return !bcDabord && bcOk && solBC(gp, type, s);
    }

    private static boolean solInventaire(GPresets gp, int type, Sol s, Set<Integer> invPris) {
        try {
            game.Inventory inv = gp.getInventory();
            if (inv == null || inv.getState() != game.Inventory.InventoryState.LOADED) return false;
            List<gearth.extensions.parsers.HInventoryItem> l = inv.getFloorItemsByType(type);
            if (l != null) for (gearth.extensions.parsers.HInventoryItem it : l) {
                if (it == null || invPris.contains(it.getId())) continue;
                if (!envoyer(gp, new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        "-" + it.getId() + " " + s.x + " " + s.y + " " + s.rot))) return false;
                invPris.add(it.getId());
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean solBC(GPresets gp, int type, Sol s) {
        int offre = -1;
        try {
            game.BCCatalog cat = gp.getCatalog();
            game.BCCatalog.SingleFurniProduct p = cat == null ? null : cat.getFloorProduct(type);
            if (p != null) offre = p.getOfferId();
        } catch (Throwable ignored) { }
        if (offre <= 0) {
            furnidata.details.FloorItemDetails d = Salle.details(s.classe);
            if (d != null) offre = d.bcOfferId;
        }
        if (offre <= 0) return false;
        return envoyer(gp, new HPacket("BuildersClubPlaceRoomItem", HMessage.Direction.TOSERVER, -1, offre, "", s.x, s.y, s.rot));
    }

    private static boolean envoyerMur(GPresets gp, int type, Mur m, Generateur.Source source, Set<Integer> invPris) {
        boolean invOk = source != Generateur.Source.BC, bcOk = source != Generateur.Source.INVENTAIRE;
        boolean bcDabord = source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE;
        if (bcDabord && bcOk && murBC(gp, type, m)) return true;
        if (invOk) try {
            game.Inventory inv = gp.getInventory();
            if (inv != null && inv.getState() == game.Inventory.InventoryState.LOADED) {
                List<gearth.extensions.parsers.HInventoryItem> l = inv.getWallItemsByType(type);
                if (l != null) for (gearth.extensions.parsers.HInventoryItem it : l) {
                    if (it == null || invPris.contains(it.getId())) continue;
                    if (!envoyer(gp, new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                            it.getId() + " " + m.position))) break;
                    invPris.add(it.getId());
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return !bcDabord && bcOk && murBC(gp, type, m);
    }

    private static boolean murBC(GPresets gp, int type, Mur m) {
        int offre = -1;
        try {
            game.BCCatalog cat = gp.getCatalog();
            game.BCCatalog.SingleFurniProduct p = cat == null ? null : cat.getAnyWallProduct(type);
            if (p != null) offre = p.getOfferId();
        } catch (Throwable ignored) { }
        if (offre <= 0) return false;
        return envoyer(gp, new HPacket("BuildersClubPlaceWallItem", HMessage.Direction.TOSERVER, -1, offre, "", m.position));
    }

    // -------------------------------------------------------------- attente

    private static int attendreSol(Set<Integer> avant, int type, int x, int y) {
        long fin = System.currentTimeMillis() + ATTENTE_MS;
        while (System.currentTimeMillis() < fin) {
            Salle.sommeil(60);
            for (HFloorItem it : Salle.sols())
                if (!avant.contains(it.getId()) && it.getTypeId() == type
                        && it.getTile().getX() == x && it.getTile().getY() == y) return it.getId();
        }
        return -1;
    }

    private static int attendreMur(Set<Integer> avant, int type) {
        long fin = System.currentTimeMillis() + ATTENTE_MS;
        while (System.currentTimeMillis() < fin) {
            Salle.sommeil(60);
            for (HWallItem w : Salle.murs())
                if (!avant.contains(w.getId()) && w.getTypeId() == type) return w.getId();
        }
        return -1;
    }
}

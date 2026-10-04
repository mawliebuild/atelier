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
 * rafale suivie (inventaire ou BC selon la source) : les poses partent au
 * rythme commun (Salle.espacer), chaque mobi recoit son altitude (@altitude)
 * des qu'il apparait, pendant que les suivants partent. Du bas vers le haut ;
 * a la fin, une verification groupee remet les altitudes qui ne seraient pas
 * prises. La dalle magique reste pour construire (Hauteur fixe).
 *
 * Les reglages des wired ne sont pas poses ici : ReglagesWired les applique
 * ensuite, grace a Resultat.cles (furniId du preset -> id reel).
 *
 * Pose hybride (PoseHybride) : le Resultat dit, mobi par mobi, ce qui est
 * pose (solsPoses : id -> Sol) et ce que le jeu a refuse (solsRefuses,
 * mursRefuses) ; seuls ceux-la, et ceux restes a une mauvaise hauteur, sont
 * ensuite repris avec la dalle magique.
 */
final class PoseDirecte {

    private PoseDirecte() { }

    /** Un mobi de sol a poser : case, altitude ABSOLUE voulue, rotation, etat (null = laisse tel quel). */
    static final class Sol {
        final String classe, etat;
        final int x, y, rot;
        final double z;
        /** Cle libre (ex. furniId du preset), -1 = sans cle ; voir Resultat.cles. */
        final int cle;
        Sol(String classe, int x, int y, double z, int rot) { this(classe, x, y, z, rot, null); }
        Sol(String classe, int x, int y, double z, int rot, String etat) { this(classe, x, y, z, rot, etat, -1); }
        Sol(String classe, int x, int y, double z, int rot, String etat, int cle) {
            this.classe = classe; this.x = x; this.y = y; this.z = z; this.rot = rot & 7; this.etat = etat; this.cle = cle;
        }
    }

    /** Un mobi mural a poser : position complete (« :w=x,y l=a,b r »). */
    static final class Mur {
        final String classe, position;
        Mur(String classe, String position) { this.classe = classe; this.position = position; }
    }

    static final class Resultat {
        final List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
        /** Cle du Sol (s'il en a une) -> id reel du mobi pose. */
        final Map<Integer, Integer> cles = new LinkedHashMap<>();
        int manquants, hauteursFausses, etatsFaux;
        /** Mobis de sol apparus : id reel -> Sol voulu. */
        final Map<Integer, Sol> solsPoses = new LinkedHashMap<>();
        /** Poses envoyees mais refusees par le jeu (jamais apparues) : a reprendre avec la dalle. */
        final List<Sol> solsRefuses = new ArrayList<>();
        final List<Mur> mursRefuses = new ArrayList<>();
    }

    /** Attente maxi qu'un mobi pose apparaisse. */
    private static final long ATTENTE_MS = 2500;

    static Resultat poser(List<Sol> sols, List<Mur> murs, Generateur.Source source, Consumer<String> dire,
                          BooleanSupplier stop, java.util.function.BiConsumer<Integer, Integer> progres) {
        return poser(sols, murs, source, dire, stop, progres, true);
    }

    /**
     * @param avecAltitude false : aucune @altitude n'est envoyee (variable
     *                     inconnue) ; les hauteurs fausses sont seulement comptees
     */
    static Resultat poser(List<Sol> sols, List<Mur> murs, Generateur.Source source, Consumer<String> dire,
                          BooleanSupplier stop, java.util.function.BiConsumer<Integer, Integer> progres,
                          boolean avecAltitude) {
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

        // Rafale suivie : les poses partent l'une apres l'autre (espacees, voir
        // espacer) ; des qu'un mobi apparait, il recoit son altitude, pendant que
        // les suivants partent. On ne pose pas tout avant de regler les hauteurs.
        Deque<Attente> attente = new ArrayDeque<>();
        Set<Integer> connus = new HashSet<>();
        for (HFloorItem it : Salle.sols()) connus.add(it.getId());
        List<Object[]> aTourner = new ArrayList<>();          // {id, Sol} dont la rotation est a redonner
        int[] faits = {0};
        for (Sol s : ordre) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getFloorTypeId(s.classe);
            if (type == null) { r.manquants++; continue; }
            if (!envoyerSol(gp, type, s, source, invPris)) { r.manquants++; continue; }
            attente.add(new Attente(s, type, System.currentTimeMillis()));
            rattraper(attente, connus, r, voulu, etats, aTourner, faits, total, progres, avecAltitude);
        }
        long fin = System.currentTimeMillis() + ATTENTE_MS;
        while (!attente.isEmpty() && System.currentTimeMillis() < fin && !stop.getAsBoolean()) {
            Salle.sommeil(60);
            rattraper(attente, connus, r, voulu, etats, aTourner, faits, total, progres, avecAltitude);
        }
        r.manquants += attente.size();
        for (Attente a : attente) r.solsRefuses.add(a.s);
        fait = faits[0];
        // rotations refusees a la pose (mobi a 2 orientations...) : en rafale, la
        // rotation voulue pour tous, puis l'equivalente pour ceux qui l'ont refusee
        if (!aTourner.isEmpty() && !stop.getAsBoolean()) {
            List<Object[]> reste = new ArrayList<>(aTourner);
            for (int essai = 0; essai < 2 && !reste.isEmpty() && !stop.getAsBoolean(); essai++) {
                Map<Integer, Integer> envoye = new HashMap<>();
                for (Object[] o : reste) {
                    if (stop.getAsBoolean()) break;
                    Sol s = (Sol) o[1];
                    int rot = essai == 0 ? s.rot : GroupeCalcul.rotationRepli(s.rot);
                    Salle.espacer();
                    Salle.deplacerSol((Integer) o[0], s.x, s.y, rot);
                    envoye.put((Integer) o[0], rot);
                }
                final List<Object[]> l = reste;
                suivre(() -> tournes(l, envoye).size(), 500, 1200);
                reste = tournes(reste, envoye);
            }
            // l'altitude, que la rotation a pu changer
            for (Object[] o : aTourner) {
                if (stop.getAsBoolean()) break;
                HFloorItem it = Salle.sol((Integer) o[0]);
                Sol s = (Sol) o[1];
                if (avecAltitude && it != null && Math.abs(it.getTile().getZ() - s.z) > 0.01) altitude(it.getId(), s.z);
            }
        }

        // muraux : en rafale aussi, chacun reconnu a son type des qu'il apparait
        Deque<Object[]> mursAttendus = new ArrayDeque<>();           // {type, Mur}
        Set<Integer> mursConnus = new HashSet<>();
        for (HWallItem w : Salle.murs()) mursConnus.add(w.getId());
        for (Mur m : murs) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getWallTypeId(m.classe);
            if (type == null) { r.manquants++; continue; }
            if (!envoyerMur(gp, type, m, source, invPris)) { r.manquants++; continue; }
            mursAttendus.add(new Object[]{type, m});
            fait = rattraperMurs(mursAttendus, mursConnus, r, fait, total, progres);
        }
        fin = System.currentTimeMillis() + ATTENTE_MS;
        while (!mursAttendus.isEmpty() && System.currentTimeMillis() < fin && !stop.getAsBoolean()) {
            Salle.sommeil(60);
            fait = rattraperMurs(mursAttendus, mursConnus, r, fait, total, progres);
        }
        r.manquants += mursAttendus.size();
        for (Object[] o : mursAttendus) r.mursRefuses.add((Mur) o[1]);

        // verification : les altitudes que le serveur n'aurait pas prises ; on
        // attend seulement que les dernieres envoyees arrivent (suivi), puis on
        // renvoie en rafale celles qui manquent
        if (!avecAltitude) r.hauteursFausses = hauteursFausses(voulu).size();
        else if (!voulu.isEmpty() && !stop.getAsBoolean()) {
            suivre(() -> hauteursFausses(voulu).size(), 500, 1200);
            for (int passe = 0; passe < 2 && !stop.getAsBoolean(); passe++) {
                List<Integer> faux = hauteursFausses(voulu);
                if (faux.isEmpty()) break;
                Journal.debug("pose directe : " + faux.size() + " altitude(s) renvoyee(s), passe " + (passe + 1));
                for (int id : faux) altitude(id, voulu.get(id));   // altitude() espace les envois
                suivre(() -> hauteursFausses(voulu).size(), 500, 1200);
            }
            r.hauteursFausses = hauteursFausses(voulu).size();
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
                Salle.espacer();
                gp.sendToServer(new HPacket("UseFurniture", HMessage.Direction.TOSERVER, id, 0));
            }
            // jusqu'a ce que tous aient change d'etat (au plus 700 ms)
            suivre(() -> {
                int n = 0;
                for (int id : reste.keySet()) {
                    HFloorItem it = Salle.sol(id);
                    if (it != null && Generateur.etatDe(it).equals(avant.get(id))) n++;
                }
                return n;
            }, 700, 700);
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

    /** Une pose envoyee, en attente de son mobi. */
    private static final class Attente {
        final Sol s; final int type; final long envoye;
        Attente(Sol s, int type, long envoye) { this.s = s; this.type = type; this.envoye = envoye; }
    }

    /**
     * Les mobis apparus depuis le dernier passage : chacun est rattache a la
     * plus ancienne pose en attente de meme type sur sa case, et recoit tout de
     * suite son altitude. Les poses sans reponse depuis ATTENTE_MS sont perdues.
     */
    private static void rattraper(Deque<Attente> attente, Set<Integer> connus, Resultat r,
                                  Map<Integer, Double> voulu, Map<Integer, String> etats, List<Object[]> aTourner,
                                  int[] faits, int total, java.util.function.BiConsumer<Integer, Integer> progres,
                                  boolean avecAltitude) {
        if (attente.isEmpty()) return;
        for (HFloorItem it : Salle.sols()) {
            if (!connus.add(it.getId())) continue;
            Attente trouve = null;
            for (Attente a : attente)
                if (a.type == it.getTypeId() && a.s.x == it.getTile().getX() && a.s.y == it.getTile().getY()) { trouve = a; break; }
            if (trouve == null) continue;
            attente.remove(trouve);
            Sol s = trouve.s;
            int id = it.getId();
            r.sols.add(id);
            r.solsPoses.put(id, s);
            if (s.cle != -1) r.cles.put(s.cle, id);
            voulu.put(id, s.z);
            if (s.etat != null) etats.put(id, s.etat);
            if (!GroupeCalcul.rotationAcceptee(Salle.rotation(it), s.rot, s.rot, false)) aTourner.add(new Object[]{id, s});
            if (avecAltitude && Math.abs(it.getTile().getZ() - s.z) > 0.01) altitude(id, s.z);
            progres.accept(++faits[0], total);
        }
        long trop = System.currentTimeMillis() - 4 * ATTENTE_MS;
        attente.removeIf(a -> {
            boolean perdu = a.envoye < trop;
            if (perdu) { r.manquants++; r.solsRefuses.add(a.s); }
            return perdu;
        });
    }

    private static int rattraperMurs(Deque<Object[]> attendus, Set<Integer> connus, Resultat r, int fait, int total,
                                     java.util.function.BiConsumer<Integer, Integer> progres) {
        if (attendus.isEmpty()) return fait;
        for (HWallItem w : Salle.murs()) {
            if (!connus.add(w.getId())) continue;
            Object[] trouve = null;
            for (Object[] o : attendus) if ((Integer) o[0] == w.getTypeId()) { trouve = o; break; }
            if (trouve != null) { attendus.remove(trouve); r.murs.add(w.getId()); progres.accept(++fait, total); }
        }
        return fait;
    }

    /** @altitude : lue dans la liste du jeu ou retenue, cherchee seulement en dernier recours. */
    private static void altitude(int id, double z) {
        Salle.espacer();
        try { OutilMiroir.Altitude.mettre(id, z); } finally { Salle.envoiFait(); }
    }

    /** Ecart minimal entre deux envois au serveur (le rythme commun est dans Salle). */
    static final long ECART_MS = Salle.ECART_MS;

    /** Envoie un paquet a son tour dans le rythme des rafales ; false si la connexion le refuse. */
    private static boolean envoyer(GPresets gp, HPacket p) {
        Salle.espacer();
        try { return gp.sendToServer(p); }
        catch (Throwable t) { return false; }
    }

    /** Les mobis poses dont l'altitude n'est pas (encore) la bonne. */
    private static List<Integer> hauteursFausses(Map<Integer, Double> voulu) {
        List<Integer> faux = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : voulu.entrySet()) {
            HFloorItem it = Salle.sol(e.getKey());
            if (it != null && Math.abs(it.getTile().getZ() - e.getValue()) > 0.02) faux.add(e.getKey());
        }
        return faux;
    }

    /** Ceux de la liste {id, Sol} qui n'ont pas (encore) la rotation envoyee. */
    private static List<Object[]> tournes(List<Object[]> l, Map<Integer, Integer> envoye) {
        List<Object[]> r = new ArrayList<>();
        for (Object[] o : l) {
            Integer rot = envoye.get((Integer) o[0]);
            HFloorItem it = Salle.sol((Integer) o[0]);
            if (it == null) continue;                      // disparu : rien a tourner
            if (rot == null || Salle.rotation(it) != rot) r.add(o);
        }
        return r;
    }

    /**
     * Suivi d'une rafale : attend que « restants » tombe a 0, sans attendre
     * pour rien : on s'arrete aussi quand plus rien n'a bouge depuis calmeMs
     * (les restants sont refuses), et au plus maxMs. Rend le dernier compte.
     */
    static int suivre(java.util.function.IntSupplier restants, long calmeMs, long maxMs) {
        long debut = System.currentTimeMillis(), change = debut;
        int r = restants.getAsInt();
        while (r > 0) {
            long t = System.currentTimeMillis();
            if (t - debut >= maxMs || t - change >= calmeMs) break;
            Salle.sommeil(50);
            int n = restants.getAsInt();
            if (n != r) { r = n; change = System.currentTimeMillis(); }
        }
        return r;
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

}

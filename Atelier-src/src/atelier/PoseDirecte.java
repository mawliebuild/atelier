package atelier;

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
 * rythme commun (Salle.espacer), chaque mobi, des qu'il apparait, recoit
 * d'abord son etat d'origine (UseFurniture), PUIS son altitude (@altitude),
 * pendant que les suivants partent. Du bas vers le haut ;
 * a la fin, une verification groupee remet les altitudes qui ne seraient pas
 * prises. La dalle magique reste pour construire (Hauteur fixe).
 * Une pose (sol ou mural) sans reponse depuis ATTENTE_MS est RENVOYEE tout
 * de suite, pendant la rafale (Salle.REESSAIS fois au plus, apres
 * Salle.pauseReessai, avec le meme objet d'inventaire) ; une altitude
 * ignoree aussi. Seul ce qui reste refuse apres ses reessais compte pour le
 * frein (Salle.signalerRefus).
 *
 * Les reglages des wired ne sont pas poses ici : ReglagesWired les applique
 * ensuite, grace a Resultat.cles (furniId du preset -> id reel).
 *
 * Pose hybride (PoseHybride) : le Resultat dit, mobi par mobi, ce qui est
 * pose (solsPoses : id -> Sol) et ce que le jeu a refuse (solsRefuses,
 * mursRefuses) ; seuls ceux-la, et ceux restes a une mauvaise hauteur, sont
 * ensuite repris avec la dalle magique.
 *
 * PoseTapis se sert d'envoyerSol (une pose, inventaire ou BC) et d'etats.
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
        /** Actions passees apres reessai (debug). */
        int apresReessai;
        /** Mobis de sol poses dans une autre rotation que la voulue (refusee par le mobi : PoseTapis.Rotations). */
        int autresDirections;
    }

    /** Boite wired (wf_...) : jamais d'etat (UseFurniture ouvre sa fenetre et a fait planter le client). */
    static boolean wired(String classe) { return classe != null && classe.startsWith("wf_"); }

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
        Moteur gp = Salle.gp();
        if (gp == null) return r;
        Furnidata fd = gp.getFurniDataTools();
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
        Renvoi renvoi = new Renvoi(gp, source, invPris, stop);
        for (Sol s : ordre) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getFloorTypeId(s.classe);
            if (type == null) { r.manquants++; continue; }
            Attente a = new Attente(s, type);
            a.rot = a.rotDepart = renvoi.rots.choisir(a.p);   // la rotation qui passe pour sa classe
            if (!renvoi.sol(a)) { r.manquants++; continue; }
            attente.add(a);
            rattraper(attente, connus, r, voulu, etats, aTourner, faits, total, progres, avecAltitude, renvoi);
        }
        // les derniers : laisses apparaitre, renvoyes au besoin (chaque pose a au plus REESSAIS renvois)
        long fin = System.currentTimeMillis() + (Salle.REESSAIS + 1) * (ATTENTE_MS + Salle.attenteReessai() + 500);
        while (!attente.isEmpty() && System.currentTimeMillis() < fin && !stop.getAsBoolean() && Salle.dansUneSalle()) {
            Salle.sommeil(60);
            rattraper(attente, connus, r, voulu, etats, aTourner, faits, total, progres, avecAltitude, renvoi);
        }
        // arret, salle quittee : pas des refus du jeu
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
        Deque<AttenteMur> mursAttendus = new ArrayDeque<>();
        Set<Integer> mursConnus = new HashSet<>();
        for (HWallItem w : Salle.murs()) mursConnus.add(w.getId());
        for (Mur m : murs) {
            if (stop.getAsBoolean() || !Salle.dansUneSalle()) break;
            Integer type = fd.getWallTypeId(m.classe);
            if (type == null) { r.manquants++; continue; }
            AttenteMur a = new AttenteMur(m, type);
            if (!renvoi.mur(a)) { r.manquants++; continue; }
            mursAttendus.add(a);
            fait = rattraperMurs(mursAttendus, mursConnus, r, fait, total, progres, renvoi);
        }
        fin = System.currentTimeMillis() + (Salle.REESSAIS + 1) * (ATTENTE_MS + Salle.attenteReessai() + 500);
        while (!mursAttendus.isEmpty() && System.currentTimeMillis() < fin && !stop.getAsBoolean() && Salle.dansUneSalle()) {
            Salle.sommeil(60);
            fait = rattraperMurs(mursAttendus, mursConnus, r, fait, total, progres, renvoi);
        }
        r.manquants += mursAttendus.size();
        for (AttenteMur a : mursAttendus) r.mursRefuses.add(a.m);

        // verification : les altitudes que le serveur n'aurait pas prises ; on
        // attend seulement que les dernieres envoyees arrivent (suivi), puis on
        // renvoie en rafale celles qui manquent
        if (!avecAltitude) r.hauteursFausses = hauteursFausses(voulu).size();
        else if (!voulu.isEmpty() && !stop.getAsBoolean()) {
            suivre(() -> hauteursFausses(voulu).size(), 500, 1200);
            int ignorees = hauteursFausses(voulu).size();
            // les altitudes ignorees : renvoyees tout de suite, au plus REESSAIS fois
            for (int passe = 0; passe < Salle.REESSAIS && !stop.getAsBoolean(); passe++) {
                List<Integer> faux = hauteursFausses(voulu);
                if (faux.isEmpty()) break;
                Journal.debug("pose directe : " + faux.size() + " altitude(s) renvoyée(s), réessai " + (passe + 1));
                Salle.pauseReessai();
                for (int id : faux) altitude(id, voulu.get(id));   // altitude() espace les envois
                suivre(() -> hauteursFausses(voulu).size(), 500, 1200);
            }
            r.hauteursFausses = hauteursFausses(voulu).size();
            r.apresReessai += Math.max(0, ignorees - r.hauteursFausses);
            // seules celles restees fausses apres leurs reessais comptent pour le frein
            if (!stop.getAsBoolean()) {
                Salle.signalerReussite(voulu.size() - r.hauteursFausses);
                Salle.signalerRefus("altitude ignorée", r.hauteursFausses);
            }
        }
        if (r.apresReessai > 0) Journal.debug("pose directe : " + r.apresReessai + " action(s) passée(s) après réessai.");
        if (r.autresDirections > 0) Journal.debug("pose directe : " + PoseOutils.nombre(r.autresDirections,
                "mobi posé", "mobis posés") + " avec une autre direction.");
        // les etats restes faux juste apres leur pose : une derniere tentative, puis le compte
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
    static int etats(Moteur gp, Map<Integer, String> voulus, BooleanSupplier stop) {
        Map<Integer, String> reste = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : voulus.entrySet())
            if (e.getValue() != null && e.getValue().matches("\\d{1,2}") && !wiredDansLaSalle(e.getKey()))
                reste.put(e.getKey(), e.getValue());
        etatsParVariable(gp, reste, stop);
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
            if (it != null && e.getValue() != null && e.getValue().matches("\\d{1,2}") && !wiredDansLaSalle(e.getKey())
                    && !Generateur.etatDe(it).equals(e.getValue())) faux++;
        }
        return faux;
    }

    /**
     * D'abord la variable d'etat -110 (@state) : un seul envoi met l'etat voulu,
     * quel que soit l'ecart. Ceux qui y sont sont retires de reste ; les autres
     * passent ensuite par UseFurniture. Si la variable ne prend sur aucun mobi
     * (pas de droits, mobis qui la refusent), elle n'est plus essayee pendant
     * cette session de l'Atelier, au bout de 5 refus de suite.
     */
    private static volatile int refusVariable = 0;

    private static void etatsParVariable(Moteur gp, Map<Integer, String> reste, BooleanSupplier stop) {
        if (reste.isEmpty() || refusVariable >= 5) return;
        Map<Integer, String> envoyes = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : reste.entrySet()) {
            if (stop.getAsBoolean()) return;
            HFloorItem it = Salle.sol(e.getKey());
            if (it == null || Generateur.etatDe(it).equals(e.getValue())) continue;
            Salle.espacer();
            gp.sendToServer(new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER,
                    0, e.getKey(), "-110", Integer.parseInt(e.getValue())));
            envoyes.put(e.getKey(), e.getValue());
        }
        if (envoyes.isEmpty()) return;
        suivre(() -> {
            int n = 0;
            for (Map.Entry<Integer, String> e : envoyes.entrySet()) {
                HFloorItem it = Salle.sol(e.getKey());
                if (it != null && !Generateur.etatDe(it).equals(e.getValue())) n++;
            }
            return n;
        }, 800, 800);
        int pris = 0;
        for (Iterator<Map.Entry<Integer, String>> i = reste.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<Integer, String> e = i.next();
            HFloorItem it = Salle.sol(e.getKey());
            if (it == null || Generateur.etatDe(it).equals(e.getValue())) {
                if (envoyes.containsKey(e.getKey())) pris++;
                i.remove();
            }
        }
        if (pris > 0) refusVariable = 0;
        else if (++refusVariable >= 5) Journal.debug("états : la variable @state ne prend pas ici, retour aux clics.");
    }

    /** Ce mobi de la salle est-il une boite wired ? */
    private static boolean wiredDansLaSalle(int id) {
        HFloorItem it = Salle.sol(id);
        return it != null && wired(Salle.classe(it.getTypeId(), false));
    }

    /** Une pose envoyee, en attente de son mobi. */
    private static final class Attente {
        final Sol s; final int type;
        /** La meme pose pour PoseTapis.Rotations (rotation voulue, emprise). */
        final PoseTapis.Piece p;
        /** Rotation envoyee (la voulue, ou celle qui passe pour sa classe) ; rotations deja essayees. */
        int rot, rotDepart;
        final Set<Integer> essayees = new HashSet<>();
        /** Rotations marquees refusees par cette pose (oubliees si aucune ne passe). */
        final List<Integer> marquees = new ArrayList<>();
        long envoye;
        int essais;
        /** Ids d'inventaire pris par le dernier envoi (rendus avant un reessai). */
        final Set<Integer> inv = new HashSet<>();
        Attente(Sol s, int type) {
            this.s = s; this.type = type;
            int[] e = PoseTapis.emprise(s.classe, s.rot);
            this.p = PoseTapis.Piece.nouveau(s.classe, s.etat, s.classe, s.x, s.y, s.z, s.rot, e[0], e[1], -1);
            this.rot = s.rot;
        }
    }

    /** Un mural envoye, en attente. */
    private static final class AttenteMur {
        final Mur m; final int type;
        long envoye;
        int essais;
        final Set<Integer> inv = new HashSet<>();
        AttenteMur(Mur m, int type) { this.m = m; this.type = type; }
    }

    /**
     * Envoi et renvoi d'une pose : un renvoi rend d'abord l'objet d'inventaire
     * pris par l'envoi precedent (il n'est pas parti : il resert), puis
     * renvoie apres une courte attente (Salle.pauseReessai).
     */
    private static final class Renvoi {
        final Moteur gp; final Generateur.Source source; final Set<Integer> invPris; final BooleanSupplier stop;
        /** Rotations refusees / acceptees par classe pendant cette pose. */
        final PoseTapis.Rotations rots = new PoseTapis.Rotations("pose directe");
        Renvoi(Moteur gp, Generateur.Source source, Set<Integer> invPris, BooleanSupplier stop) {
            this.gp = gp; this.source = source; this.invPris = invPris; this.stop = stop;
        }
        /** Peut-on encore renvoyer (pas d'arret, meme salle) ? */
        boolean possible() { return !stop.getAsBoolean() && Salle.dansUneSalle(); }

        boolean sol(Attente a) {
            invPris.removeAll(a.inv);
            Set<Integer> avant = new HashSet<>(invPris);
            a.essayees.add(a.rot);
            Sol s = a.rot == a.s.rot ? a.s : new Sol(a.s.classe, a.s.x, a.s.y, a.s.z, a.rot, a.s.etat, a.s.cle);
            boolean ok = envoyerSol(gp, a.type, s, source, invPris);
            a.inv.clear();
            for (int id : invPris) if (!avant.contains(id)) a.inv.add(id);
            a.envoye = System.currentTimeMillis();
            return ok;
        }

        boolean mur(AttenteMur a) {
            invPris.removeAll(a.inv);
            Set<Integer> avant = new HashSet<>(invPris);
            boolean ok = envoyerMur(gp, a.type, a.m, source, invPris);
            a.inv.clear();
            for (int id : invPris) if (!avant.contains(id)) a.inv.add(id);
            a.envoye = System.currentTimeMillis();
            return ok;
        }
    }

    /**
     * Les mobis apparus depuis le dernier passage : chacun est rattache a la
     * plus ancienne pose en attente de meme type sur sa case, et recoit tout de
     * suite son altitude. Une pose sans reponse depuis ATTENTE_MS est renvoyee
     * tout de suite (au plus Salle.REESSAIS fois), puis comptee refusee.
     */
    private static void rattraper(Deque<Attente> attente, Set<Integer> connus, Resultat r,
                                  Map<Integer, Double> voulu, Map<Integer, String> etats, List<Object[]> aTourner,
                                  int[] faits, int total, java.util.function.BiConsumer<Integer, Integer> progres,
                                  boolean avecAltitude, Renvoi renvoi) {
        if (attente.isEmpty()) return;
        for (HFloorItem it : Salle.sols()) {
            if (!connus.add(it.getId())) continue;
            Attente trouve = null;
            for (Attente a : attente)
                if (a.type == it.getTypeId() && a.s.x == it.getTile().getX() && a.s.y == it.getTile().getY()) { trouve = a; break; }
            if (trouve == null) continue;
            attente.remove(trouve);
            Salle.signalerReussite();
            if (trouve.essais > 0) r.apresReessai++;
            Sol s = trouve.s;
            int id = it.getId();
            r.sols.add(id);
            r.solsPoses.put(id, s);
            if (s.cle != -1) r.cles.put(s.cle, id);
            voulu.put(id, s.z);
            // une autre rotation que la voulue : retenue pour sa classe
            renvoi.rots.accepter(trouve.p, trouve.rot);
            if (trouve.rot != s.rot) {
                r.autresDirections++;
                if (!trouve.marquees.isEmpty()) renvoi.rots.dire(trouve.p, trouve.marquees.get(0), trouve.rot, "posé");
            }
            // pose -> ETAT d'origine tout de suite -> puis sa HAUTEUR (@altitude) ; jamais d'etat pour une boite wired
            if (s.etat != null && !wired(s.classe) && renvoi.possible() && etats(renvoi.gp, Map.of(id, s.etat), renvoi.stop) > 0)
                etats.put(id, s.etat);                 // reste faux : une derniere tentative a la fin
            if (trouve.rot == s.rot && !GroupeCalcul.rotationAcceptee(Salle.rotation(it), s.rot, s.rot, false))
                aTourner.add(new Object[]{id, s});
            HFloorItem maintenant = Salle.sol(id);
            if (avecAltitude && maintenant != null && Math.abs(maintenant.getTile().getZ() - s.z) > 0.01) altitude(id, s.z);
            progres.accept(++faits[0], total);
        }
        long trop = System.currentTimeMillis() - ATTENTE_MS;
        List<Attente> aRenvoyer = new ArrayList<>();
        for (Iterator<Attente> i = attente.iterator(); i.hasNext(); ) {
            Attente a = i.next();
            if (a.envoye >= trop || !renvoi.possible()) continue;
            i.remove();
            if (autreRotation(a, renvoi)) { aRenvoyer.add(a); continue; }
            if (a.essais < Salle.REESSAIS) { aRenvoyer.add(a); continue; }
            r.manquants++; r.solsRefuses.add(a.s);
            Salle.signalerRefus("mobi pas apparu");           // refuse meme apres ses reessais
        }
        if (aRenvoyer.isEmpty()) return;
        Salle.pauseReessai();
        for (Attente a : aRenvoyer) {
            if (!renvoi.possible()) { attente.add(a); continue; }
            if (a.essayees.contains(a.rot)) a.essais++;       // une nouvelle rotation n'use pas les reessais
            Journal.debug("pose directe : " + a.s.classe + " en (" + a.s.x + "," + a.s.y + ") r" + a.rot
                    + " renvoyé (réessai " + a.essais + ").");
            if (renvoi.sol(a)) attente.add(a);
            else { r.manquants++; r.solsRefuses.add(a.s); }   // plus de source : pas un refus du jeu
        }
    }

    /**
     * Pose pas apparue : la rotation est-elle en cause ? Refus cant_set_item
     * (ou sans raison, apres un renvoi dans la meme rotation) d'une rotation
     * autre que 0 : la suivante des rotations possibles (PoseTapis.Rotations :
     * equivalente r4 -> r0, r6 -> r2..., direction par defaut, 0) est choisie
     * pour le renvoi, avant les reessais normaux. true si une autre rotation
     * part ; aucune ne reste : les refus marques sont oublies (pas la rotation).
     */
    private static boolean autreRotation(Attente a, Renvoi renvoi) {
        if (a.rot == 0 && a.s.rot == 0) return false;
        String raison = PoseOutils.Signaux.raisonPose(a.envoye, a.s.x, a.s.y);
        boolean empilement = PoseTapis.refusEmpilement(raison);
        if (!empilement && (raison != null || a.essais < 1)) return false;
        if (renvoi.rots.refuser(a.p, a.rot)) a.marquees.add(a.rot);
        int defaut = -1;
        try { Furnidata.Mobi d = Salle.details(a.s.classe); if (d != null) defaut = d.defaultDir; } catch (Throwable ignored) { }
        for (int rot : renvoi.rots.candidats(a.p, a.rot, defaut)) {
            if (a.essayees.contains(rot)) continue;
            a.rot = rot;
            return true;
        }
        renvoi.rots.oublier(a.p, a.marquees);
        a.marquees.clear();
        a.rot = a.rotDepart;
        return false;
    }

    private static int rattraperMurs(Deque<AttenteMur> attendus, Set<Integer> connus, Resultat r, int fait, int total,
                                     java.util.function.BiConsumer<Integer, Integer> progres, Renvoi renvoi) {
        if (attendus.isEmpty()) return fait;
        for (HWallItem w : Salle.murs()) {
            if (!connus.add(w.getId())) continue;
            AttenteMur trouve = null;
            for (AttenteMur a : attendus) if (a.type == w.getTypeId()) { trouve = a; break; }
            if (trouve != null) {
                attendus.remove(trouve); r.murs.add(w.getId()); progres.accept(++fait, total);
                Salle.signalerReussite();
                if (trouve.essais > 0) r.apresReessai++;
            }
        }
        // sans reponse depuis ATTENTE_MS : renvoye tout de suite, au plus REESSAIS fois, puis refuse
        long trop = System.currentTimeMillis() - ATTENTE_MS;
        List<AttenteMur> aRenvoyer = new ArrayList<>();
        for (Iterator<AttenteMur> i = attendus.iterator(); i.hasNext(); ) {
            AttenteMur a = i.next();
            if (a.envoye >= trop || !renvoi.possible()) continue;
            i.remove();
            if (a.essais < Salle.REESSAIS) { aRenvoyer.add(a); continue; }
            r.manquants++; r.mursRefuses.add(a.m);
            Salle.signalerRefus("mural pas apparu");
        }
        if (aRenvoyer.isEmpty()) return fait;
        Salle.pauseReessai();
        for (AttenteMur a : aRenvoyer) {
            if (!renvoi.possible()) { attendus.add(a); continue; }
            a.essais++;
            Journal.debug("pose directe : mural " + a.m.classe + " renvoyé (réessai " + a.essais + ").");
            if (renvoi.mur(a)) attendus.add(a);
            else { r.manquants++; r.mursRefuses.add(a.m); }
        }
        return fait;
    }

    /** @altitude : lue dans la liste du jeu ou retenue, cherchee seulement en dernier recours. */
    private static void altitude(int id, double z) {
        Salle.espacer();
        try { OutilMiroir.Altitude.mettre(id, z); } finally { Salle.envoiFait(); }
    }

    /** Ecart par defaut entre deux envois au serveur (le rythme commun, reglable, est Salle.ecart()). */
    static final long ECART_MS = Salle.ECART_MS;

    /** Envoie un paquet a son tour dans le rythme des rafales ; false si la connexion le refuse. */
    private static boolean envoyer(Moteur gp, HPacket p) {
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

    /** Le dernier envoi de pose de sol (pour le diagnostic d'un refus) : « BC offre 123 (catalogue) », « inventaire 456 ». */
    static volatile String dernierEnvoi = "";

    static boolean envoyerSol(Moteur gp, int type, Sol s, Generateur.Source source, Set<Integer> invPris) {
        boolean invOk = source != Generateur.Source.BC, bcOk = source != Generateur.Source.INVENTAIRE;
        boolean bcDabord = source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE;
        if (bcDabord && bcOk && solBC(gp, type, s)) return true;
        if (invOk && solInventaire(gp, type, s, invPris)) return true;
        return !bcDabord && bcOk && solBC(gp, type, s);
    }

    private static boolean solInventaire(Moteur gp, int type, Sol s, Set<Integer> invPris) {
        try {
            Inventaire inv = gp.getInventory();
            if (inv == null || inv.getState() != Inventaire.Etat.LOADED) return false;
            List<gearth.extensions.parsers.HInventoryItem> l = inv.getFloorItemsByType(type);
            if (l != null) for (gearth.extensions.parsers.HInventoryItem it : l) {
                if (it == null || invPris.contains(it.getId())) continue;
                if (!envoyer(gp, new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        "-" + it.getId() + " " + s.x + " " + s.y + " " + s.rot))) return false;
                invPris.add(it.getId());
                dernierEnvoi = "inventaire " + it.getId();
                derniereOffre = -1;
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** L'offre BC du dernier envoi de pose de sol (-1 : depuis l'inventaire). */
    static volatile int derniereOffre = -1;

    /**
     * Pose depuis le BC comme le client : page et offre du catalogue BC
     * (OffresBc : jamais l'offerid de la furnidata, jamais une offre de lot,
     * jamais un mobi que la furnidata ne dit pas « bc ») ; false sans offre.
     */
    private static boolean solBC(Moteur gp, int type, Sol s) {
        OffresBc.Offre o = offreSol(gp, s.classe);
        if (o == null) return false;
        dernierEnvoi = "BC " + o;
        derniereOffre = o.offre();
        return envoyer(gp, PoseOutils.poseSolBc(o, s.x, s.y, s.rot));
    }

    /** L'offre BC d'un mobi de sol, ou null (pas posable au Builders Club). */
    static OffresBc.Offre offreSol(Moteur gp, String classe) {
        try { return gp == null ? null : OffresBc.sol(gp.getCatalog(), gp.getFurniDataTools(), classe); }
        catch (Throwable t) { return null; }
    }

    private static boolean envoyerMur(Moteur gp, int type, Mur m, Generateur.Source source, Set<Integer> invPris) {
        boolean invOk = source != Generateur.Source.BC, bcOk = source != Generateur.Source.INVENTAIRE;
        boolean bcDabord = source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE;
        if (bcDabord && bcOk && murBC(gp, m)) return true;
        if (invOk) try {
            Inventaire inv = gp.getInventory();
            if (inv != null && inv.getState() == Inventaire.Etat.LOADED) {
                List<gearth.extensions.parsers.HInventoryItem> l = inv.getWallItemsByType(type);
                boolean postIt = PoseOutils.postIt(m.classe);
                if (l != null) for (gearth.extensions.parsers.HInventoryItem it : l) {
                    // un bloc de post-its sert a plusieurs poses (une feuille chacune)
                    if (it == null || (!postIt && invPris.contains(it.getId()))) continue;
                    HPacket p = postIt ? PoseOutils.posePostIt(it.getId(), m.position)
                            : new HPacket("PlaceObject", HMessage.Direction.TOSERVER, it.getId() + " " + m.position);
                    if (!envoyer(gp, p)) break;
                    invPris.add(it.getId());
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return !bcDabord && bcOk && murBC(gp, m);
    }

    /** Pose murale depuis le BC comme le client : (page, offre, extra du produit, position, false). */
    private static boolean murBC(Moteur gp, Mur m) {
        OffresBc.Offre o;
        try { o = gp == null ? null : OffresBc.mur(gp.getCatalog(), gp.getFurniDataTools(), m.classe, null); }
        catch (Throwable t) { o = null; }
        if (o == null) return false;
        return envoyer(gp, PoseOutils.poseMurBc(o.page(), o.offre(), o.extra(), m.position));
    }

    // -------------------------------------------------------------- attente

}

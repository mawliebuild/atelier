package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

/**
 * Pose d'une liste de mobis a des hauteurs exactes avec la dalle magique
 * (remplace la partie « dalle » de l'ancien importeur).
 *
 * Deroulement de poser() :
 *   1. la dalle : une dalle magique de la salle qui convient a tous les mobis
 *      (regles de requiredStackTileDimension / hasStackTileForDimension), sinon
 *      une dalle posee par nous (inventaire ou BC selon la source) sur la case
 *      donnee ou sur une case libre trouvee pres du premier mobi ;
 *   2. chaque mobi empilable, du bas vers le haut : la dalle passe sous sa case
 *      (StackTileUtils.findBestDropLocation), sa hauteur est reglee
 *      (SetCustomStackingHeight, z * 100), puis le mobi est pose dessus, a sa
 *      case et a sa rotation (PlaceObject ou BuildersClubPlaceRoomItem) ;
 *      les non empilables (rouleaux, eaux...) sont poses directement ; une
 *      dalle de la liste est posee a sa case puis recoit sa propre hauteur ;
 *   3. suivi des arrivees comme PoseDirecte.rattraper : chaque mobi apparu est
 *      rattache a la plus ancienne pose en attente de meme type sur sa case ;
 *   4. verification des hauteurs : un mobi a une mauvaise hauteur est repris
 *      une fois (dalle dessous, hauteur, MoveObject sur sa propre case) ;
 *   5. les etats voulus (variable -110 si l'on a des droits, sinon UseFurniture) ;
 *   6. la dalle : ramassee si nous l'avons posee, sinon remise a sa place.
 * Chaque envoi part au rythme commun (Salle.espacer, 150 ms).
 *
 * Aucun travail sur le fil JavaFX ; aucun message dans le jeu : le Bilan est
 * rendu a l'appelant. Arret possible a tout moment par stop.
 */
final class PoseDalle {

    /** Un mobi a poser : cle libre (id dans la copie), case et hauteur ABSOLUES, rotation, etat (null = laisse). */
    record Mobi(int cle, String classe, int x, int y, double z, int rotation, String etat) { }

    /** Ce que rend la pose. */
    static final class Bilan {
        /** Cle du mobi -> id reel du mobi pose. */
        final Map<Integer, Integer> ids = new LinkedHashMap<>();
        /** Mobis non poses (absents, refuses, arret). */
        final List<Mobi> manquants = new ArrayList<>();
        /** Cles des mobis poses restes a une mauvaise hauteur. */
        final List<Integer> hauteursFausses = new ArrayList<>();
        /** Cles des mobis restes a un autre etat que celui voulu. */
        final List<Integer> etatsFaux = new ArrayList<>();
        /** La dalle utilisee (-1 sans dalle), posee par nous, ramassee a la fin. */
        int dalle = -1;
        boolean dallePosee, dalleRamassee;
        boolean arrete;
        /** Raison d'un abandon (rien n'a ete pose), sinon null. */
        String erreur;

        /** Bilan en une phrase, pour l'appelant. */
        String texte() {
            if (erreur != null) return "Pose impossible : " + erreur + ".";
            StringBuilder b = new StringBuilder(PoseOutils.nombre(ids.size(), "mobi posé", "mobis posés"));
            if (!manquants.isEmpty()) b.append(", ").append(PoseOutils.nombre(manquants.size(), "manquant", "manquants"));
            if (!hauteursFausses.isEmpty())
                b.append(", ").append(PoseOutils.nombre(hauteursFausses.size(), "hauteur fausse", "hauteurs fausses"));
            if (!etatsFaux.isEmpty()) b.append(", ").append(PoseOutils.nombre(etatsFaux.size(), "état faux", "états faux"));
            if (dallePosee) b.append(dalleRamassee ? ", dalle magique ramassée" : ", dalle magique restée dans la salle");
            if (arrete) b.append(" (arrêtée)");
            return Ui.majuscule(b.toString()) + ".";
        }
    }

    /** La dalle de travail et sa place connue. */
    static final class Dalle {
        final int id;
        final DalleMagique modele;
        final boolean poseeParNous;
        final int origineX, origineY, origineRot;
        int x, y, rot;
        double hauteur = Double.NaN;

        Dalle(int id, DalleMagique modele, boolean poseeParNous, int x, int y, int rot) {
            this.id = id; this.modele = modele; this.poseeParNous = poseeParNous;
            this.origineX = this.x = x; this.origineY = this.y = y; this.origineRot = this.rot = rot;
        }
    }

    /** Attente maxi qu'une pose apparaisse ; sans nouvelle depuis ce delai (calme), le reste est refuse. */
    static final long ATTENTE_MS = 2500;

    private final Canal canal;
    private final EtatSalle salle;
    private final Inventaire inventaire;
    private final CatalogueBc catalogue;
    private final Furnidata furnidata;
    private final Droits droits;

    /** @param droits peut etre null : les etats passent alors par UseFurniture seulement */
    PoseDalle(Canal canal, EtatSalle salle, Inventaire inventaire, CatalogueBc catalogue, Furnidata furnidata, Droits droits) {
        this.canal = canal;
        this.salle = salle;
        this.inventaire = inventaire;
        this.catalogue = catalogue;
        this.furnidata = furnidata;
        this.droits = droits;
    }

    // ================================================================ regles (logique pure, recopiee de l'importeur)

    /** Dimension de dalle qu'exige un mobi (requiredStackTileDimension). */
    static int requise(int xDim, int yDim) {
        int x = Math.max(1, xDim), y = Math.max(1, yDim);
        if ((x == 1 && y == 2) || (x == 2 && y == 1)) return -1;
        int m = Math.max(x, y);
        if (m <= 1) return 1;
        if (m <= 2) return 2;
        if (m <= 4) return 4;
        if (m <= 6) return 6;
        return 8;
    }

    /** Une dalle de dimension d sert-elle un mobi qui exige r ? (hasStackTileForDimension) */
    static boolean couvre(int d, int r) {
        if (r == -1) return d == -1 || d >= 2;
        if (d == -1) return r <= 2;
        return d >= r;
    }

    static boolean couvreTout(int d, Collection<Integer> exigences) {
        for (int r : exigences) if (!couvre(d, r)) return false;
        return true;
    }

    /** La dalle a poser : la plus petite qui couvre tout ; la 1x2 seulement pour des mobis 1x2 (Generateur.Dalle.modele). */
    static DalleMagique modele(Set<Integer> exigences) {
        List<DalleMagique> ordre = new ArrayList<>();
        for (DalleMagique d : DalleMagique.values()) if (d.dimension() > 0) ordre.add(d);
        ordre.sort(Comparator.comparingInt(DalleMagique::dimension));
        ordre.add(exigences.contains(-1) ? 1 : ordre.size(), DalleMagique.UN_DEUX);
        for (DalleMagique d : ordre) if (couvreTout(d.dimension(), exigences)) return d;
        return null;
    }

    /** Le plan : caractere de hauteur de la case ('x' hors plan). */
    interface Plan { char hauteur(int x, int y); }

    /** Place et rotation de la dalle pour poser en (x, y) (StackTileUtils.findBestDropLocation) ; null si aucune. */
    static int[] placeDalle(int x, int y, int dimension, Plan plan) {
        if (dimension == -1) {
            char r = plan.hauteur(x, y);
            if (r == 'x') return null;
            if (plan.hauteur(x + 1, y) == r) return new int[]{x, y, 0};
            if (plan.hauteur(x, y + 1) == r) return new int[]{x, y, 2};
            if (plan.hauteur(x - 1, y) == r) return new int[]{x - 1, y, 0};
            if (plan.hauteur(x, y - 1) == r) return new int[]{x, y - 1, 2};
            return null;
        }
        List<int[]> possibles = new ArrayList<>();
        for (int x2 = x; x2 > x - dimension && x2 >= 0; --x2)
            for (int y2 = y; y2 > y - dimension && y2 >= 0; --y2) possibles.add(new int[]{x2, y2});
        possibles.sort(Comparator.comparingInt(p -> (x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1])));
        suivant:
        for (int[] p : possibles) {
            char ref = plan.hauteur(p[0], p[1]);
            if (ref == 'x') continue;
            for (int i = 0; i < dimension; i++)
                for (int j = 0; j < dimension; j++)
                    if (plan.hauteur(p[0] + i, p[1] + j) != ref) continue suivant;
            return new int[]{p[0], p[1], 0};
        }
        return null;
    }

    static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /**
     * Case libre la plus proche de (ax, ay) pour une dalle lx x ly : toutes ses
     * cases libres et de meme hauteur de sol, hors des cases interdites d'abord.
     */
    static int[] caseLibre(int ax, int ay, int lx, int ly, Set<Long> interdites, BiPredicate<Integer, Integer> libre,
                           Plan plan, int rayon) {
        List<int[]> candidats = new ArrayList<>();
        for (int dx = -rayon; dx <= rayon; dx++)
            for (int dy = -rayon; dy <= rayon; dy++) {
                int x = ax + dx, y = ay + dy;
                if (x >= 0 && y >= 0) candidats.add(new int[]{x, y, dx * dx + dy * dy});
            }
        candidats.sort((a, b) -> a[2] != b[2] ? Integer.compare(a[2], b[2])
                : a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        for (Set<Long> interdit : List.of(interdites, Set.<Long>of())) {
            suivant:
            for (int[] c : candidats) {
                char ref = plan.hauteur(c[0], c[1]);
                if (ref == 'x') continue;
                for (int i = 0; i < lx; i++)
                    for (int j = 0; j < ly; j++) {
                        int x = c[0] + i, y = c[1] + j;
                        if (interdit.contains(cle(x, y)) || !libre.test(x, y) || plan.hauteur(x, y) != ref) continue suivant;
                    }
                return new int[]{c[0], c[1]};
            }
        }
        return null;
    }

    // ================================================================ pose

    /**
     * Pose les mobis a leurs cases et hauteurs exactes.
     *
     * @param caseDalle case ou poser la dalle s'il faut en poser une ; null = case libre cherchee
     * @param stop      arret demande (les poses deja parties sont suivies, la dalle est rangee)
     */
    Bilan poser(List<Mobi> mobis, PoseOutils.Source source, HPoint caseDalle, BooleanSupplier stop) {
        Bilan b = new Bilan();
        if (stop == null) stop = () -> false;
        if (!salle.dansUneSalle()) { b.erreur = "pas dans une salle"; b.manquants.addAll(mobis); return b; }
        if (furnidata == null || !furnidata.pret()) { b.erreur = "furnidata pas prête"; b.manquants.addAll(mobis); return b; }

        List<Mobi> ordre = new ArrayList<>(mobis);
        ordre.sort(Comparator.comparingDouble(Mobi::z).thenComparingInt(Mobi::y).thenComparingInt(Mobi::x));
        boolean besoinDalle = false;
        for (Mobi m : ordre) if (avecDalle(m)) { besoinDalle = true; break; }

        Dalle dalle = null;
        Set<Integer> invPris = new HashSet<>();
        if (besoinDalle) {
            dalle = preparer(ordre, source, caseDalle, invPris, stop, b);
            if (dalle == null) { b.manquants.addAll(mobis); return b; }
            b.dalle = dalle.id;
            b.dallePosee = dalle.poseeParNous;
        }

        Deque<Attente> attente = new ArrayDeque<>();
        Set<Integer> connus = new HashSet<>();
        for (EtatSalle.MobiSol s : salle.sols()) connus.add(s.id());
        Map<Integer, Mobi> poses = new LinkedHashMap<>();           // id reel -> mobi

        try {
            for (Mobi m : ordre) {
                if (stop.getAsBoolean() || !salle.dansUneSalle()) { b.arrete = stop.getAsBoolean(); break; }
                Integer type = furnidata.typeSol(m.classe());
                if (type == null) { b.manquants.add(m); Journal.debug("Pose dalle : classe inconnue " + m.classe()); continue; }
                if (avecDalle(m) && dalle != null) glisserDalle(dalle, m.x(), m.y(), m.z());
                if (!envoyerPose(type, m, source, invPris)) {
                    b.manquants.add(m);
                    Journal.debug("Pose dalle : " + m.classe() + " ni dans l'inventaire ni au BC (source " + source + ")");
                    continue;
                }
                attente.add(new Attente(m, type, System.currentTimeMillis()));
                rattraper(attente, connus, poses, b);
            }
            // les dernieres poses : jusqu'a ce que plus rien n'arrive
            final BooleanSupplier arret = stop;
            PoseOutils.suivre(() -> { rattraper(attente, connus, poses, b); return attente.size(); },
                    ATTENTE_MS, 4 * ATTENTE_MS, arret);
            rattraper(attente, connus, poses, b);
            for (Attente a : attente) b.manquants.add(a.m);
            attente.clear();

            // hauteurs : verification, une reprise par la dalle, puis le compte
            if (!poses.isEmpty() && !stop.getAsBoolean()) {
                PoseOutils.suivre(() -> fausses(poses).size(), 500, 1500, stop);
                List<Integer> faux = fausses(poses);
                if (!faux.isEmpty() && dalle != null && !stop.getAsBoolean()) {
                    Journal.debug("Pose dalle : " + faux.size() + " hauteur(s) reprise(s)");
                    for (int id : faux) {
                        if (stop.getAsBoolean()) break;
                        Mobi m = poses.get(id);
                        if (!avecDalle(m)) continue;
                        deplacerSurDalle(dalle, id, m.x(), m.y(), m.rotation(), m.z());
                    }
                    PoseOutils.suivre(() -> fausses(poses).size(), 500, 1500, stop);
                }
                for (int id : fausses(poses)) b.hauteursFausses.add(poses.get(id).cle());
            }

            // etats
            for (Map.Entry<Integer, Mobi> e : poses.entrySet()) {
                if (stop.getAsBoolean()) break;
                String voulu = e.getValue().etat();
                if (voulu == null || DalleMagique.estDalle(e.getValue().classe())) continue;
                // boite wired : l'utiliser ouvre sa fenetre dans le jeu, son etat ne se regle pas
                if (e.getValue().classe() != null && e.getValue().classe().startsWith("wf_")) continue;
                // « 0 » (etat par defaut) sur un mobi sans etat utilisable : rien a regler
                if ((voulu.isEmpty() || voulu.equals("0")) && PoseOutils.etat(salle.furniFromId(e.getKey())) == null) continue;
                if (!PoseOutils.mettreEtat(canal, salle, droits, e.getKey(), voulu, stop)) b.etatsFaux.add(e.getValue().cle());
            }
            if (stop.getAsBoolean()) b.arrete = true;
        } finally {
            if (dalle != null) ranger(dalle, b);
        }
        Journal.debug("Pose dalle : " + b.texte());
        return b;
    }

    /**
     * Met un mobi deja pose a (x, y, rot) et a la hauteur z avec la dalle de
     * travail (moveFurni de l'importeur) : la dalle passe sous la case, sa
     * hauteur est reglee, puis MoveObject. Sert aussi aux liaisons des wired.
     */
    void deplacerSurDalle(Dalle dalle, int id, int x, int y, int rot, double z) {
        glisserDalle(dalle, x, y, z);
        PoseOutils.envoyer(canal, PoseOutils.deplacementSol(id, x, y, rot));
    }

    /**
     * Une dalle de la salle utilisable pour deplacerSurDalle (la plus petite qui
     * couvre exigence), ou null. Elle n'est ni posee ni ramassee.
     */
    Dalle dalleDeLaSalle(int exigence) {
        Dalle meilleure = null;
        List<EtatSalle.MobiSol> sols = salle.sols();
        for (EtatSalle.MobiSol s : sols) {
            if (Salle.idFictif(s.id())) continue;          // marqueur ou fantome : pas une vraie dalle (les 0x7FFF.. du BC en sont)
            DalleMagique d = DalleMagique.depuisClasse(furnidata.classeSol(s.type()));
            if (d == null || !couvre(d.dimension(), exigence)) continue;
            if (!utilisable(s, sols, furnidata)) continue;
            if (meilleure == null || rang(d) < rang(meilleure.modele))
                meilleure = new Dalle(s.id(), d, false, s.x(), s.y(), s.rotation());
        }
        return meilleure;
    }

    // ================================================================ dalles utilisables

    /**
     * Dalles qu'aucune pose ne doit prendre comme dalle de travail : celles
     * d'un tapis de dalles en cours (PoseTapis), meme pas encore ramassees.
     */
    static final Set<Integer> EXCLUES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Emprise {x, y, lx, ly} d'un mobi de sol (furnidata, rotation 2 / 6 echangent les cotes). */
    static int[] emprise(EtatSalle.MobiSol s, Furnidata fd) {
        Furnidata.Mobi d = fd == null ? null : fd.sol(fd.classeSol(s.type()));
        int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
        if (s.rotation() == 2 || s.rotation() == 6) { int t = lx; lx = ly; ly = t; }
        return new int[]{s.x(), s.y(), lx, ly};
    }

    /** Deux emprises {x, y, lx, ly} se chevauchent-elles ? Logique pure. */
    static boolean chevauche(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /**
     * Une dalle de la salle peut-elle servir de dalle de travail ? Non si elle
     * est EXCLUES (tapis en cours) ou si un autre mobi est sur son emprise
     * (la glisser sous un autre mobi laisserait la pile en l'air, et sa case
     * n'est pas libre).
     */
    static boolean utilisable(EtatSalle.MobiSol dalle, List<EtatSalle.MobiSol> sols, Furnidata fd) {
        if (EXCLUES.contains(dalle.id())) return false;
        int[] e = emprise(dalle, fd);
        for (EtatSalle.MobiSol o : sols)
            if (o.id() != dalle.id() && !Salle.idFictif(o.id()) && chevauche(e, emprise(o, fd))) return false;
        return true;
    }

    /**
     * La salle a-t-elle une dalle de travail utilisable qui couvre toutes ces
     * exigences (sinon une dalle neuve sera posee a part, puis ramassee) ?
     */
    static boolean dalleUtilisable(EtatSalle salle, Furnidata fd, Set<Integer> exigences) {
        if (salle == null || fd == null) return false;
        List<EtatSalle.MobiSol> sols = salle.sols();
        for (EtatSalle.MobiSol s : sols) {
            if (Salle.idFictif(s.id())) continue;
            DalleMagique d = DalleMagique.depuisClasse(fd.classeSol(s.type()));
            if (d != null && couvreTout(d.dimension(), exigences) && utilisable(s, sols, fd)) return true;
        }
        return false;
    }

    /** Remet une dalle a sa place d'origine (ou la ramasse si nous l'avons posee). */
    void ranger(Dalle dalle, Bilan b) {
        if (dalle.poseeParNous) {
            PoseOutils.Signaux.ramassageEnCours(List.of(dalle.id));
            boolean partie;
            try {
                PoseOutils.envoyer(canal, PoseOutils.ramassageSol(dalle.id));
                partie = PoseOutils.attendre(() -> salle.sol(dalle.id) == null || PoseOutils.Signaux.retireGroupe(dalle.id), 3000, null);
            } finally {
                PoseOutils.Signaux.ramassageFini(List.of(dalle.id));
            }
            if (b != null) b.dalleRamassee = partie;
            if (!partie) Journal.debug("Pose dalle : la dalle " + dalle.id + " n'a pas été ramassée");
        } else if (dalle.x != dalle.origineX || dalle.y != dalle.origineY || dalle.rot != dalle.origineRot) {
            PoseOutils.envoyer(canal, PoseOutils.deplacementSol(dalle.id, dalle.origineX, dalle.origineY, dalle.origineRot));
            dalle.x = dalle.origineX; dalle.y = dalle.origineY; dalle.rot = dalle.origineRot;
        }
    }

    // ================================================================ interne

    private static int rang(DalleMagique d) { return d.dimension() == -1 ? 2 : d.dimension(); }

    /** Pose avec la dalle : empilable et pas une dalle elle-meme. */
    private boolean avecDalle(Mobi m) {
        return m != null && furnidata.empilable(m.classe()) && !DalleMagique.estDalle(m.classe());
    }

    /** La dalle sous (x, y) a la hauteur z ; n'envoie que ce qui change. */
    private void glisserDalle(Dalle dalle, int x, int y, double z) {
        int[] p = placeDalle(x, y, dalle.modele.dimension(), salle::caseDuPlan);
        if (p == null) return;                      // comme l'importeur : sans place, le mobi est pose sans dalle
        if (p[0] != dalle.x || p[1] != dalle.y || p[2] != dalle.rot) {
            PoseOutils.envoyer(canal, PoseOutils.deplacementSol(dalle.id, p[0], p[1], p[2]));
            dalle.x = p[0]; dalle.y = p[1]; dalle.rot = p[2];
            dalle.hauteur = Double.NaN;             // deplacee : le serveur la remet au sol de la case, hauteur a redire
        }
        if (Double.isNaN(dalle.hauteur) || Math.round(dalle.hauteur * 100) != Math.round(z * 100)) {
            PoseOutils.envoyer(canal, PoseOutils.hauteurDalle(dalle.id, z));
            dalle.hauteur = z;
        }
    }

    /** Envoie la pose d'un mobi selon la source ; false si ni l'inventaire ni le BC ne l'ont. */
    private boolean envoyerPose(int type, Mobi m, PoseOutils.Source source, Set<Integer> invPris) {
        if (source.bcDabord() && source.bc() && poseBc(type, m)) return true;
        if (source.inventaire() && poseInventaire(type, m, invPris)) return true;
        return !source.bcDabord() && source.bc() && poseBc(type, m);
    }

    private boolean poseInventaire(int type, Mobi m, Set<Integer> invPris) {
        if (inventaire == null || !inventaire.charge()) return false;
        for (HInventoryItem it : inventaire.solsDeType(type)) {
            if (it == null || invPris.contains(it.getId())) continue;
            if (!PoseOutils.envoyer(canal, PoseOutils.poseSolInventaire(it.getId(), m.x(), m.y(), m.rotation()))) return false;
            invPris.add(it.getId());
            return true;
        }
        return false;
    }

    private boolean poseBc(int type, Mobi m) {
        OffresBc.Offre o = OffresBc.sol(catalogue, furnidata, m.classe());     // page et offre du catalogue BC
        return o != null && PoseOutils.envoyer(canal, PoseOutils.poseSolBc(o, m.x(), m.y(), m.rotation()));
    }

    /** Une pose envoyee, en attente de son mobi. */
    private static final class Attente {
        final Mobi m; final int type; final long envoye;
        Attente(Mobi m, int type, long envoye) { this.m = m; this.type = type; this.envoye = envoye; }
    }

    /** Rattache les mobis apparus aux poses en attente (meme type, meme case, la plus ancienne). */
    private void rattraper(Deque<Attente> attente, Set<Integer> connus, Map<Integer, Mobi> poses, Bilan b) {
        if (attente.isEmpty()) return;
        for (EtatSalle.MobiSol s : salle.sols()) {
            if (!connus.add(s.id())) continue;
            Attente trouve = null;
            for (Attente a : attente)
                if (a.type == s.type() && a.m.x() == s.x() && a.m.y() == s.y()) { trouve = a; break; }
            if (trouve == null) continue;
            attente.remove(trouve);
            poses.put(s.id(), trouve.m);
            b.ids.put(trouve.m.cle(), s.id());
            if (DalleMagique.estDalle(trouve.m.classe()))      // une dalle de la liste : sa propre hauteur
                PoseOutils.envoyer(canal, PoseOutils.hauteurDalle(s.id(), trouve.m.z()));
        }
    }

    private List<Integer> fausses(Map<Integer, Mobi> poses) {
        List<Integer> faux = new ArrayList<>();
        for (Map.Entry<Integer, Mobi> e : poses.entrySet()) {
            if (DalleMagique.estDalle(e.getValue().classe())) continue;    // hauteur de dalle : ni lue ni comparee
            EtatSalle.MobiSol s = salle.sol(e.getKey());
            if (s != null && Math.abs(s.z() - e.getValue().z()) > 0.02) faux.add(e.getKey());
        }
        return faux;
    }

    // ================================================================ la dalle de travail

    /** La dalle trouvee dans la salle, ou posee par nous ; null = impossible (b.erreur dit pourquoi). */
    private Dalle preparer(List<Mobi> ordre, PoseOutils.Source source, HPoint caseDalle, Set<Integer> invPris,
                           BooleanSupplier stop, Bilan b) {
        Set<Integer> exig = new HashSet<>();
        for (Mobi m : ordre) {
            if (!avecDalle(m)) continue;
            Furnidata.Mobi d = furnidata.sol(m.classe());
            exig.add(d == null ? 1 : requise(d.xDim, d.yDim));
        }
        if (exig.isEmpty()) exig.add(1);

        // une dalle de la salle qui couvre tout : la plus petite, LIBRE (ni dalle d'un tapis
        // en cours, ni dalle sous d'autres mobis : la glisser emporterait ou bloquerait la pile)
        Dalle trouvee = null;
        List<EtatSalle.MobiSol> sols = salle.sols();
        int ecartees = 0;
        for (EtatSalle.MobiSol s : sols) {
            if (Salle.idFictif(s.id())) continue;          // marqueur ou fantome : pas une vraie dalle (les 0x7FFF.. du BC en sont)
            DalleMagique d = DalleMagique.depuisClasse(furnidata.classeSol(s.type()));
            if (d == null || !couvreTout(d.dimension(), exig)) continue;
            if (!utilisable(s, sols, furnidata)) { ecartees++; continue; }
            if (trouvee == null || rang(d) < rang(trouvee.modele)) trouvee = new Dalle(s.id(), d, false, s.x(), s.y(), s.rotation());
        }
        if (ecartees > 0)
            Journal.debug("Pose dalle : " + ecartees + " dalle(s) de la salle écartée(s) (tapis en cours ou mobis posés dessus).");
        if (trouvee != null) {
            Journal.debug("Pose dalle : dalle " + trouvee.modele + " libre trouvée (" + trouvee.id + ") en " + trouvee.x + "," + trouvee.y);
            return trouvee;
        }

        DalleMagique modele = modele(exig);
        Integer type = modele == null ? null : furnidata.typeSol(modele.classe());
        if (type == null) { b.erreur = "aucune dalle magique ne convient"; return null; }
        int lx = modele.dimension() == -1 ? 1 : modele.dimension(), ly = modele.dimension() == -1 ? 2 : modele.dimension();
        Furnidata.Mobi fd = furnidata.sol(modele.classe());
        if (fd != null) { lx = Math.max(1, fd.xDim); ly = Math.max(1, fd.yDim); }

        Set<Long> trace = new HashSet<>();
        for (Mobi m : ordre) trace.add(cle(m.x(), m.y()));
        Set<Long> refusees = new HashSet<>();               // cases d'une dalle refusee : jamais redemandees
        int[] c;
        if (caseDalle != null) c = new int[]{caseDalle.getX(), caseDalle.getY()};
        else {
            c = autreCase(ordre.get(0), lx, ly, trace, refusees);
            if (c == null) { b.erreur = "pas de place libre de " + lx + "×" + ly + " pour la dalle magique"; return null; }
        }

        // Une dalle refusee sur sa case (avatar debout dessus, porte, case que le serveur
        // voit occupee...) l'est encore a l'essai suivant : chaque essai prend une AUTRE case.
        String envoi = null;
        for (int essai = 1; essai <= 3 && !stop.getAsBoolean(); essai++) {
            Set<Integer> avant = new HashSet<>();
            for (EtatSalle.MobiSol s : salle.sols()) avant.add(s.id());
            Mobi m = new Mobi(-1, modele.classe(), c[0], c[1], 0, 0, null);
            Set<Integer> invAvant = new HashSet<>(invPris);
            if (!envoyerPose(type, m, source, invPris)) { b.erreur = "dalle magique ni dans l'inventaire ni au BC"; return null; }
            envoi = invPris.size() > invAvant.size() ? "inventaire" : "BC " + OffresBc.sol(catalogue, furnidata, modele.classe());
            final int[] ici = c;
            final int[] trouveId = {-1};
            PoseOutils.attendre(() -> {
                for (EtatSalle.MobiSol s : salle.sols())
                    if (!avant.contains(s.id()) && s.type() == type && s.x() == ici[0] && s.y() == ici[1]) { trouveId[0] = s.id(); return true; }
                return false;
            }, 4000, stop);
            if (trouveId[0] > 0) {
                Journal.debug("Pose dalle : dalle " + modele + " posée (" + trouveId[0] + ") en " + c[0] + "," + c[1] + " (" + envoi + ")");
                return new Dalle(trouveId[0], modele, true, c[0], c[1], 0);
            }
            invPris.retainAll(invAvant);                  // la dalle de l'inventaire n'est pas partie
            for (int i = 0; i < lx; i++) for (int j = 0; j < ly; j++) refusees.add(cle(c[0] + i, c[1] + j));
            List<PoseOutils.Signaux.Signal> sig = PoseOutils.Signaux.depuis(System.currentTimeMillis() - 4500);
            Journal.debug("Pose dalle : la dalle n'est pas apparue en " + c[0] + "," + c[1] + " (essai " + essai + ", " + envoi
                    + ", plan " + salle.caseDuPlan(c[0], c[1]) + ", porte " + (porteSous(c, lx, ly) ? "dessous" : "ailleurs")
                    + ", serveur : " + (sig.isEmpty() ? "rien" : sig.get(sig.size() - 1).texte()) + ")");
            int[] autre = autreCase(ordre.get(0), lx, ly, trace, refusees);
            if (autre == null) break;
            c = autre;
        }
        b.erreur = stop.getAsBoolean() ? "arrêtée" : "la dalle magique n'est pas apparue" + (envoi == null ? "" : " (" + envoi + ")");
        b.arrete = stop.getAsBoolean();
        return null;
    }

    /** Une case libre pour la dalle pres du premier mobi, hors trace, hors porte et hors cases deja refusees ; null sinon. */
    private int[] autreCase(Mobi premier, int lx, int ly, Set<Long> trace, Set<Long> refusees) {
        Set<Long> occ = occupees();
        occ.addAll(refusees);
        HPoint porte = salle.porte();
        if (porte != null) occ.add(cle(porte.getX(), porte.getY()));
        return caseLibre(premier.x(), premier.y(), lx, ly, trace,
                (x, y) -> salle.caseDuPlan(x, y) != 'x' && !occ.contains(cle(x, y)), salle::caseDuPlan, 64);
    }

    /** La porte de la salle est-elle sous la dalle posee en c ? */
    private boolean porteSous(int[] c, int lx, int ly) {
        HPoint p = salle.porte();
        return p != null && p.getX() >= c[0] && p.getX() < c[0] + lx && p.getY() >= c[1] && p.getY() < c[1] + ly;
    }

    /** Cases occupees par des mobis de sol (emprise selon la furnidata et la rotation). */
    private Set<Long> occupees() {
        Set<Long> r = new HashSet<>();
        for (EtatSalle.MobiSol s : salle.sols()) {
            Furnidata.Mobi d = furnidata.sol(furnidata.classeSol(s.type()));
            int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
            if (s.rotation() == 2 || s.rotation() == 6) { int t = lx; lx = ly; ly = t; }
            for (int i = 0; i < lx; i++)
                for (int j = 0; j < ly; j++) r.add(cle(s.x() + i, s.y() + j));
        }
        return r;
    }
}

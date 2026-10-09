package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/**
 * Les actions qui touchent VRAIMENT la salle : deplacer, ramasser, dupliquer
 * un calque. Toujours hors fil JavaFX (Groupes les lance), avec progression,
 * Arreter, compte des echecs et verification dans la salle apres coup.
 */
final class GroupeActions {

    private GroupeActions() { }

    static final long PAUSE = 150;          // ms entre deux envois au serveur

    // ------------------------------------------------------------ lecture

    /** Les mobis presents dans la salle, reduits a leurs elements geometriques. */
    static List<GroupeCalcul.Element> elements(Collection<Integer> sols, Collection<Integer> murs) {
        List<GroupeCalcul.Element> r = new ArrayList<>();
        for (int id : sols) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            r.add(element(it));
        }
        for (int id : murs) {
            HWallItem w = Salle.mur(id);
            if (w == null) continue;
            r.add(GroupeCalcul.Element.mur(id, w.getLocation()));
        }
        return r;
    }

    static GroupeCalcul.Element element(HFloorItem it) {
        int[] e = Salle.emprise(it);
        int x = it.getTile().getX(), y = it.getTile().getY();
        return GroupeCalcul.Element.sol(it.getId(), x, y, it.getTile().getZ(), e[0], e[1],
                Salle.rotation(it), Salle.hauteurSol(x, y));
    }

    private static long cle(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /**
     * {sols du calque poses sur un mobi hors calque, cases d'arrivee occupees
     * par des mobis hors calque}.
     */
    static int[] occupations(List<GroupeCalcul.Element> els, Set<Integer> idsCalque, int dx, int dy) {
        Map<Long, List<HFloorItem>> autres = new HashMap<>();
        for (HFloorItem it : Salle.sols()) {
            if (idsCalque.contains(it.getId()) || GroupeFantomes.estFantome(it.getId())) continue;
            int[] e = Salle.emprise(it);
            for (int i = 0; i < e[0]; i++)
                for (int j = 0; j < e[1]; j++)
                    autres.computeIfAbsent(cle(it.getTile().getX() + i, it.getTile().getY() + j), k -> new ArrayList<>()).add(it);
        }
        int sur = 0;
        Set<Long> occupees = new HashSet<>();
        for (GroupeCalcul.Element e : els) {
            if (e.mural) continue;
            boolean pose = false;
            for (int i = 0; i < e.ex; i++)
                for (int j = 0; j < e.ey; j++) {
                    List<HFloorItem> l = autres.get(cle(e.x + i, e.y + j));
                    if (l != null) for (HFloorItem o : l) if (o.getTile().getZ() < e.z - 0.01) pose = true;
                    long c = cle(e.x + dx + i, e.y + dy + j);
                    if (autres.containsKey(c)) occupees.add(c);
                }
            if (pose) sur++;
        }
        return new int[]{sur, occupees.size()};
    }

    private static boolean memeSalle(int salle) { return Groupes.salleCourante() == salle; }

    // ------------------------------------------------------------ deplacer

    /**
     * Deplace tout le calque de (dx, dy) par le TAPIS DE DALLES (PoseTapis) :
     * dalles sous les cases d'arrivee, puis chaque mobi, du bas vers le haut,
     * MoveObject et sa hauteur (@altitude), puis les muraux, puis les dalles
     * ramassees. Une seule action pour Ctrl+Z.
     */
    static Groupes.Resultat deplacer(Groupes.Tache t, String calqueId, int dx, int dy) {
        if (dx == 0 && dy == 0) return Groupes.Resultat.refus("Décalage nul : rien à déplacer.");
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun mobi de ce calque dans la salle.");
        return bougerTapis(t, salle, GroupeCalcul.cibles(els, dx, dy, Salle::hauteurSol), false, "déplacé", null, b -> "");
    }

    // ------------------------------------------------------------ tapis de dalles

    private static boolean estWired(int id) {
        HFloorItem it = Salle.sol(id);
        return it != null && Wired.estWired(Salle.classe(it.getTypeId(), false));
    }

    /**
     * Mobis DEJA dans la salle, mis a leur cible par le tapis de dalles
     * (Deplacer, Pivoter, Miroir sur place) :
     *   1. dalles magiques sous toutes les cases d'arrivee, au niveau du sol ;
     *   2. chaque mobi de sol, du bas vers le haut : MoveObject (rotation
     *      voulue, puis l'equivalente, puis celle d'origine), puis @altitude ;
     *   3. les muraux (MoveWallItem ; les refuses repris a la dalle) ;
     *   4. ramassage des dalles ; 5. verification, une nouvelle tentative par mobi.
     * Les wired ne sont jamais ramasses (leur reglage serait perdu).
     * @param echange emprise echangee a l'arrivee (quart de tour impair)
     * @param prelude lance dans l'action (Ctrl+Z), avant le tapis ; peut etre null
     * @param suite   fin du message, calculee apres coup
     */
    private static Groupes.Resultat bougerTapis(Groupes.Tache t, int salle, List<GroupeCalcul.Cible> cibles,
                                                boolean echange, String participe, Runnable prelude,
                                                java.util.function.Function<PoseTapis.Bilan, String> suite) {
        List<PoseTapis.Piece> pieces = new ArrayList<>();
        List<GroupeCalcul.Cible> murs = new ArrayList<>();
        int impossibles = 0;
        for (GroupeCalcul.Cible c : cibles) {
            if (c.e.mural) { if (c.position == null) impossibles++; else murs.add(c); continue; }
            HFloorItem it = Salle.sol(c.e.id);
            if (it == null) continue;                                  // parti entre-temps
            if (c.horsPlan) { impossibles++; continue; }
            int lx = echange ? c.e.ey : c.e.ex, ly = echange ? c.e.ex : c.e.ey;
            pieces.add(PoseTapis.Piece.deplace(c.e.id, Salle.nom(it.getTypeId(), false), c.x, c.y,
                    Generateur.arrondi(Math.max(0, c.z)), c.rot, c.e.rot, lx, ly, !estWired(c.e.id)));
        }
        if (pieces.isEmpty() && murs.isEmpty())
            return Groupes.Resultat.refus(impossibles > 0 ? "Tout tomberait hors du plan : rien à faire." : "Aucun mobi à bouger.");
        int total = pieces.size() + murs.size() + impossibles;
        Journal.debug("calques : tapis de dalles, " + pieces.size() + " sol(s), " + murs.size() + " mural(aux), "
                + impossibles + " impossible(s).");

        PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(Generateur.Source.INVENTAIRE_PUIS_BC, t::arretee, t::dire,
                t::progres, true);
        int[] mursOk = {0};
        PoseHybride.Bilan[] rbMurs = {null};
        Runnable etape3 = murs.isEmpty() ? null : () -> {
            int n = 0;
            for (GroupeCalcul.Cible c : murs) {
                if (t.arretee() || !memeSalle(salle)) break;
                Salle.espacer();
                Salle.deplacerMur(c.e.id, SelectionMur.normaliser(c.position));
                t.progres(++n, murs.size(), "Muraux : " + n + "/" + murs.size());
            }
            PoseDirecte.suivre(() -> { int r = 0; for (GroupeCalcul.Cible c : murs) if (!arrive(c.e, c)) r++; return r; }, 700, 1500);
            List<GroupeCalcul.Cible> refuses = new ArrayList<>();
            for (GroupeCalcul.Cible c : murs) if (!arrive(c.e, c) && Salle.mur(c.e.id) != null) refuses.add(c);
            // les muraux pas arrives : renvoyes tout de suite, au plus REESSAIS fois
            int avantReessai = refuses.size();
            for (int k = 0; k < Salle.REESSAIS && !refuses.isEmpty() && !t.arretee() && memeSalle(salle); k++) {
                Salle.pauseReessai();
                for (GroupeCalcul.Cible c : refuses) {
                    if (t.arretee() || !memeSalle(salle)) break;
                    Salle.espacer();
                    Salle.deplacerMur(c.e.id, SelectionMur.normaliser(c.position));
                }
                final List<GroupeCalcul.Cible> l = refuses;
                PoseDirecte.suivre(() -> { int r = 0; for (GroupeCalcul.Cible c : l) if (!arrive(c.e, c)) r++; return r; }, 700, 1500);
                List<GroupeCalcul.Cible> encore = new ArrayList<>();
                for (GroupeCalcul.Cible c : refuses) if (!arrive(c.e, c) && Salle.mur(c.e.id) != null) encore.add(c);
                refuses = encore;
            }
            if (avantReessai > refuses.size())
                Journal.debug("calques : " + (avantReessai - refuses.size()) + " mural(aux) déplacé(s) après réessai.");
            if (!t.arretee() && memeSalle(salle)) {
                Salle.signalerReussite(n - refuses.size());
                Salle.signalerRefus("mural pas déplacé", refuses.size());
            }
            if (!refuses.isEmpty() && !t.arretee() && memeSalle(salle)) rbMurs[0] = reprendreCibles(t, salle, refuses);
        };
        PoseTapis.Bilan b;
        historiqueGrouper(true);           // dalles exceptees (hors historique) : une seule action pour Ctrl+Z
        try {
            if (prelude != null) prelude.run();
            b = PoseTapis.executer(pieces, jeu, t::progres, etape3);
        } finally {
            historiqueGrouper(false);
        }
        for (GroupeCalcul.Cible c : murs) if (arrive(c.e, c)) mursOk[0]++;
        if (rbMurs[0] != null) mursOk[0] += rbMurs[0].murs.size();
        boolean arrete = b.arrete || t.arretee();
        if (b.sortie || !memeSalle(salle))
            return new Groupes.Resultat(false, true, total, b.obtenus(), total - b.obtenus(), "Tu as quitté la salle pendant l'action.", null);
        int reussis = b.reussis() + mursOk[0];
        String msg = b.texte(participe)
                + (murs.isEmpty() ? "" : " Muraux : " + mursOk[0] + "/" + murs.size()
                        + (mursOk[0] < murs.size() && !arrete ? ", " + (murs.size() - mursOk[0]) + " refusé(s)." : "."))
                + (impossibles > 0 ? " " + impossibles + " impossible(s) (hors du plan ou position murale illisible)." : "")
                + (b.sansAltitude ? PoseTapis.SANS_ALTITUDE : "")
                + (suite == null ? "" : suite.apply(b))
                + (reussis > 0 ? " " + WindowsClavier.texte("Cmd+Z pour annuler.") : "");
        int echecs = Math.max(0, total - reussis);
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, total, reussis, echecs, Ui.accorder(msg.trim()), null);
    }

    /**
     * Reprise avec la dalle de muraux refuses : ramasses puis reposes a leur
     * arrivee par le moteur de pose ; leurs nouveaux ids restent dans leur
     * calque (Groupes.remplacerIds).
     */
    private static PoseHybride.Bilan reprendreCibles(Groupes.Tache t, int salle, List<GroupeCalcul.Cible> l) {
        List<PoseHybride.Piece> p = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) {
            if (c.e.mural) {
                HWallItem w = Salle.mur(c.e.id);
                String cls = w == null ? null : Salle.classe(w.getTypeId(), true);
                if (cls != null && c.position != null)
                    p.add(PoseHybride.Piece.mur(cls, w.getState(), SelectionMur.normaliser(c.position), c.e.id));
            } else {
                HFloorItem it = Salle.sol(c.e.id);
                String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
                if (cls != null) p.add(PoseHybride.Piece.sol(cls, Generateur.etatDe(it), c.x, c.y,
                        Generateur.arrondi(Math.max(0, c.z)), c.rot, c.e.id, -1));
            }
        }
        // le calque de chacun est note AVANT le ramassage (la surveillance des
        // calques oublie les ids disparus de la salle)
        List<Integer> anciensS = new ArrayList<>(), anciensM = new ArrayList<>();
        for (PoseHybride.Piece x : p) (x.mural ? anciensM : anciensS).add(x.ancien);
        Groupes.Appartenance avant = Groupes.appartenance(anciensS, anciensM);
        Journal.debug("calques : " + p.size() + " mobi(s) repris à la dalle.");
        t.progres(0, p.size(), "Reprise à la dalle : 0/" + p.size());
        PoseHybride.Bilan b = PoseHybride.reprendre(p, Generateur.Source.INVENTAIRE_PUIS_BC, t::dire,
                () -> t.arretee() || !memeSalle(salle), (f, n) -> t.progres(f, n, "Reprise à la dalle : " + f + "/" + n));
        b.voulus = l.size();
        // les ramasses qui n'ont pas ete reposes sortent du calque ; les autres y restent
        List<Integer> perdusS = new ArrayList<>(), perdusM = new ArrayList<>();
        for (int id : b.ramassesSols) if (!b.remplacesSols.containsKey(id)) perdusS.add(id);
        for (int id : b.ramassesMurs) if (!b.remplacesMurs.containsKey(id)) perdusM.add(id);
        if (!perdusS.isEmpty() || !perdusM.isEmpty()) Groupes.oublierIds(perdusS, perdusM);
        Groupes.remplacerIds(avant, b.remplacesSols, b.remplacesMurs);
        return b;
    }

    private static boolean arrive(GroupeCalcul.Element e, GroupeCalcul.Cible c) {
        if (c == null) return false;
        if (e.mural) {
            HWallItem w = Salle.mur(e.id);
            return w != null && c.position != null
                    && SelectionMur.normaliser(w.getLocation()).equals(SelectionMur.normaliser(c.position));
        }
        HFloorItem now = Salle.sol(e.id);
        return now != null && now.getTile().getX() == c.x && now.getTile().getY() == c.y;
    }

    // ------------------------------------------------------------ hauteur

    /**
     * Change l'altitude de tous les mobis de sol du calque par @altitude.
     * relatif : chacun monte/descend de « valeur » (la forme du calque est
     * gardee) ; sinon tous sont mis a « valeur ». Les ecritures partent en
     * rafale, sans pause, pour que tout change d'un coup ; une repasse lente
     * rattrape ensuite ceux que le serveur aurait laisses.
     */
    static Groupes.Resultat hauteur(Groupes.Tache t, String calqueId, boolean relatif, double valeur) {
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> sols = new ArrayList<>();
        for (GroupeCalcul.Element e : elements(ids.get(0), ids.get(1))) if (!e.mural) sols.add(e);
        if (sols.isEmpty()) return Groupes.Resultat.refus("Aucun mobi de sol dans ce calque (les muraux n'ont pas d'altitude).");
        Map<Integer, Double> voulu = new LinkedHashMap<>();
        for (GroupeCalcul.Element e : sols)
            voulu.put(e.id, Generateur.arrondi(Math.max(0, relatif ? e.z + valeur : valeur)));

        // le premier mobi (un qui doit vraiment bouger) verifie la variable retenue
        // avant la rafale : une variable fausse en preference est oubliee, puis
        // relue dans la liste du jeu ou cherchee (mettre)
        Map.Entry<Integer, Double> premier = null;
        for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
            HFloorItem now = Salle.sol(c.getKey());
            if (now != null && Math.abs(now.getTile().getZ() - c.getValue()) > 0.05) { premier = c; break; }
        }
        historiqueGrouper(true);           // la verification sur le premier fait partie de l'action (Ctrl+Z)
        boolean arrete = false;
        try {
            if (premier != null && !OutilMiroir.Altitude.confirmee()) {
                if (!OutilMiroir.Altitude.connue()) t.dire("Recherche de @altitude...");
                Salle.espacer();
                OutilMiroir.Altitude.mettre(premier.getKey(), premier.getValue());
                Salle.envoiFait();
            }
            if (premier != null && !OutilMiroir.Altitude.connue())
                return Groupes.Resultat.refus("@altitude inconnue. Règle-la une fois dans l'éditeur :wired, puis recommence.");
            Journal.debug("calques : hauteur, @altitude = variable " + OutilMiroir.Altitude.variable() + ", " + voulu.size() + " mobi(s)");
            // 1. rafale suivie : envois au rythme commun (le serveur jette les
            //    paquets trop rapproches), arrivees suivies a la fin
            int n = 0;
            for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
                if (t.arretee()) { arrete = true; break; }
                n++;
                HFloorItem now = Salle.sol(c.getKey());
                if (now != null && Math.abs(now.getTile().getZ() - c.getValue()) <= 0.05) continue;   // deja a sa hauteur
                Salle.espacer();
                OutilMiroir.Altitude.ecrire(c.getKey(), c.getValue());
                t.progres(n, voulu.size(), "Hauteurs : " + n + "/" + voulu.size());
            }
            Salle.envoiFait();
            t.progres(n, voulu.size(), "Hauteurs envoyées");
            // 2. suivi (on attend seulement que les hauteurs arrivent), puis repasse
            //    espacee pour les retardataires
            for (int passe = 1; passe <= 2; passe++) {
                PoseDirecte.suivre(() -> hauteursEncore(voulu).size(), 500, 1200);
                List<Map.Entry<Integer, Double>> encore = hauteursEncore(voulu);
                if (encore.isEmpty() || t.arretee()) break;
                int m = 0;
                for (Map.Entry<Integer, Double> c : encore) {
                    if (t.arretee()) { arrete = true; break; }
                    Salle.espacer();
                    OutilMiroir.Altitude.ecrire(c.getKey(), c.getValue());
                    t.progres(++m, encore.size(), "Repasse " + passe + " : " + m + "/" + encore.size());
                }
                if (arrete) break;
            }
        } finally {
            historiqueGrouper(false);
        }
        int rates = 0;
        for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
            HFloorItem now = Salle.sol(c.getKey());
            if (now == null || Math.abs(now.getTile().getZ() - c.getValue()) > 0.05) rates++;
        }
        int ok = voulu.size() - rates;
        String msg = ok + "/" + voulu.size() + " mobi(s) à la nouvelle hauteur"
                + (rates > 0 ? ", " + rates + " pas changé(s)." : ".")
                + (ids.get(1).isEmpty() ? "" : " Muraux laissés tels quels.")
                + " Ctrl+Z pour annuler.";
        return new Groupes.Resultat(rates == 0 && !arrete, arrete, voulu.size(), ok, rates, msg, null);
    }

    private static List<Map.Entry<Integer, Double>> hauteursEncore(Map<Integer, Double> voulu) {
        List<Map.Entry<Integer, Double>> encore = new ArrayList<>();
        for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
            HFloorItem now = Salle.sol(c.getKey());
            if (now != null && Math.abs(now.getTile().getZ() - c.getValue()) > 0.05) encore.add(c);
        }
        return encore;
    }

    // ------------------------------------------------------------ pivoter

    /** Apercu chiffre d'un pivot : {sols qui tournent, muraux laisses, hors du plan}. */
    static int[] simulerPivot(String calqueId, boolean horaire, boolean toutLeCalque) {
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        int murs = 0, hors = 0, sols = 0;
        for (GroupeCalcul.Element e : els) if (e.mural) murs++;
        int[] centre = centreRetenu(calqueId, Groupes.salleCourante(), els);
        for (GroupeCalcul.Cible c : GroupeCalcul.pivot(els, horaire, toutLeCalque, Salle::hauteurSol, centre)) {
            sols++;
            if (c.horsPlan) hors++;
        }
        return new int[]{sols, murs, hors};
    }

    /**
     * Le point autour duquel le dernier pivot a tourne, tant que le calque est
     * reste exactement ou ce pivot l'a laisse : les tours suivants tournent
     * autour du meme point, et 4 tours (ou un dans chaque sens) le ramenent
     * exactement a sa place, sans glisser d'une case.
     */
    private static final class DernierPivot {
        final int salle; final String calque; final int[] centre2; final Map<Integer, Long> places;
        DernierPivot(int salle, String calque, int[] centre2, Map<Integer, Long> places) {
            this.salle = salle; this.calque = calque; this.centre2 = centre2; this.places = places;
        }
    }
    private static volatile DernierPivot dernierPivot = null;

    /** id -> case + rotation des sols. */
    private static Map<Integer, Long> places(List<GroupeCalcul.Element> els) {
        Map<Integer, Long> m = new HashMap<>();
        for (GroupeCalcul.Element e : els) if (!e.mural) m.put(e.id, cle(e.x, e.y) * 8 + e.rot);
        return m;
    }

    private static int[] centreRetenu(String calqueId, int salle, List<GroupeCalcul.Element> els) {
        DernierPivot d = dernierPivot;
        if (d == null || d.salle != salle || !d.calque.equals(calqueId)) return null;
        return places(els).equals(d.places) ? d.centre2 : null;
    }

    /**
     * Historique (Ctrl+Z) : les envois d'une meme action en plusieurs passes
     * forment UNE action. Imbricable : une action qui en appelle une autre
     * (copie avec wired, puis mise en place) reste une seule action.
     */
    private static int profondeurGroupe = 0;
    private static synchronized void historiqueGrouper(boolean g) {
        if (g) { if (profondeurGroupe++ == 0) Historique.grouper(true); }
        else if (profondeurGroupe > 0 && --profondeurGroupe == 0) Historique.grouper(false);
    }


    /** « 3 déplacé(s) sans tourner », « 2 mural(aux) laissé(s)… » : fin du message d'un pivot. */
    private static String suitePivot(PoseTapis.Bilan b, int muraux) {
        int sansTourner = 0;
        for (PoseTapis.Piece p : b.pieces) if (p.sansTourner && p.obtenu == p.id) sansTourner++;
        return (sansTourner > 0 ? " " + sansTourner + " déplacé(s) sans tourner (une seule orientation)." : "")
                + (muraux > 0 ? " " + muraux + " mural(aux) laissé(s) tel(s) quel(s) : ils ne pivotent pas." : "");
    }

    /**
     * Pivote d'un quart de tour les mobis de sol du calque (ou de la
     * selection), par le tapis de dalles (voir bougerTapis). Lance par
     * Groupes.pivoter (verrou, fil a part). Chaque mobi essaie la rotation
     * voulue, puis l'equivalente sur le meme axe, puis celle d'origine (mobi a
     * une seule orientation : il change seulement de case).
     */
    static Groupes.Resultat pivoter(Groupes.Tache t, String calqueId, boolean horaire, boolean toutLeCalque) {
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        int[] centre = toutLeCalque ? centreRetenu(calqueId, salle, els) : null;
        if (centre == null) centre = GroupeCalcul.centrePivot(els);
        List<GroupeCalcul.Cible> cibles = GroupeCalcul.pivot(els, horaire, toutLeCalque, Salle::hauteurSol, centre);
        if (cibles.isEmpty())
            return Groupes.Resultat.refus(els.isEmpty() ? "Aucun mobi de ce calque dans la salle."
                    : "Aucun mobi de sol à pivoter (les muraux ne pivotent pas).");
        int muraux = els.size() - cibles.size();
        Journal.debug("calques : pivot " + (horaire ? "horaire" : "inverse") + ", "
                + cibles.size() + " sol(s), centre double " + (centre == null ? "-" : centre[0] + "," + centre[1]));
        Groupes.Resultat r = bougerTapis(t, salle, cibles, true, "pivoté", null,
                b -> (horaire ? " Sens horaire." : " Sens inverse.") + suitePivot(b, muraux));
        // le point de rotation est garde pour le tour suivant
        if (toutLeCalque && centre != null && memeSalle(salle)) {
            List<GroupeCalcul.Element> apres = elements(Groupes.mobis(calqueId).get(0), List.of());
            dernierPivot = new DernierPivot(salle, calqueId, centre, places(apres));
        }
        return r;
    }

    /**
     * Deplacement avec pivot (mode Deplacer : fleches + pivoter) : chaque mobi
     * de sol part directement a sa case et sa rotation finales, par le tapis
     * de dalles (voir bougerTapis).
     */
    static Groupes.Resultat deplacerTourne(Groupes.Tache t, String calqueId, int quarts, int dx, int dy) {
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        int q = ((quarts % 4) + 4) % 4;
        List<GroupeCalcul.Cible> cibles = GroupeCalcul.transformer(els, q, dx, dy, Salle::hauteurSol);
        if (cibles.isEmpty())
            return Groupes.Resultat.refus(els.isEmpty() ? "Aucun mobi de ce calque dans la salle."
                    : "Aucun mobi de sol à pivoter (les muraux ne pivotent pas).");
        int muraux = els.size() - cibles.size();
        Journal.debug("calques : deplacement avec pivot (" + q + " quart(s)), " + cibles.size() + " sol(s).");
        return bougerTapis(t, salle, cibles, (q & 1) == 1, q == 0 ? "déplacé" : "pivoté", null,
                b -> suitePivot(b, muraux));
    }

    /**
     * Miroir SUR PLACE des mobis de sol du calque, gauche↔droite (surX) ou
     * haut↔bas, dans le cadre qui les contient (memes cibles que les fantomes
     * du miroir : GroupeCalcul.copie), par le tapis de dalles (voir
     * bougerTapis). Les muraux ne sont pas retournes. Lance par
     * Groupes.miroirSurPlace (verrou, une action a la fois, Arreter).
     */
    static Groupes.Resultat miroirSurPlace(Groupes.Tache t, String calqueId, boolean surX) {
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), List.of());
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun mobi de sol à retourner.");
        GroupeCalcul.Transfo tr = surX ? GroupeCalcul.Transfo.NEUTRE.miroirX() : GroupeCalcul.Transfo.NEUTRE.miroirY();
        List<GroupeCalcul.Cible> cibles = GroupeCalcul.copie(els, tr, Salle::hauteurSol);
        int muraux = 0;
        for (int id : ids.get(1)) if (Salle.mur(id) != null) muraux++;
        int m = muraux;
        Journal.debug("calques : miroir sur place " + (surX ? "gauche-droite" : "haut-bas") + ", " + cibles.size() + " sol(s).");
        return bougerTapis(t, salle, cibles, (tr.quarts & 1) == 1, "retourné", null,
                b -> (surX ? " Gauche ↔ droite." : " Haut ↔ bas.")
                        + (m > 0 ? " " + m + " mobi(s) mural(aux) pas retourné(s)." : ""));
    }

    // ------------------------------------------------------------ ramasser

    static Groupes.Resultat ramasser(Groupes.Tache t, String calqueId) {
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        return ramasserIds(t, ids.get(0), ids.get(1));
    }

    /**
     * Ramasse ces mobis (supprimer un calque) : deux passes, puis verification.
     * Historique les enregistre en UNE action : Ctrl+Z les repose depuis
     * l'inventaire (a leur case et rotation ; hauteur et reglage des wired
     * ne reviennent pas).
     */
    static Groupes.Resultat ramasserIds(Groupes.Tache t, Collection<Integer> sols, Collection<Integer> murs) {
        int salle = Groupes.salleCourante();
        List<int[]> liste = new ArrayList<>();        // {id, mural}
        for (int id : sols) if (Salle.sol(id) != null) liste.add(new int[]{id, 0});
        for (int id : murs) if (Salle.mur(id) != null) liste.add(new int[]{id, 1});
        if (liste.isEmpty()) return new Groupes.Resultat(true, false, 0, 0, 0, "Aucun de ses mobis n'était encore dans la salle.", null);
        int n = 0;
        boolean arrete = false;
        historiqueGrouper(true);
        try {
            for (int passe = 1; passe <= 2; passe++) {
                List<int[]> encore = new ArrayList<>();
                for (int[] k : liste) if (present(k)) encore.add(k);
                if (encore.isEmpty()) break;
                List<int[]> envoyes = new ArrayList<>();
                for (int[] k : encore) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    Salle.espacer();
                    Salle.ramasser(k[0], k[1] == 1);
                    envoyes.add(k);
                    if (passe == 1) n++;
                    t.progres(n, liste.size(), "Ramassage" + (passe > 1 ? " (2e passe)" : "") + " : " + n + "/" + liste.size());
                }
                // suivi : seulement le temps que les derniers partent
                PoseDirecte.suivre(() -> presents(envoyes), arrete ? 400 : 800, 3000);
                if (arrete) break;
            }
        } finally {
            historiqueGrouper(false);
        }
        List<Integer> partisS = new ArrayList<>(), partisM = new ArrayList<>();
        int restes = 0;
        for (int[] k : liste) {
            if (present(k)) restes++;
            else (k[1] == 1 ? partisM : partisS).add(k[0]);
        }
        Groupes.oublierIds(partisS, partisM);
        int faits = liste.size() - restes;
        String msg = messageRamassage(faits, liste.size(), restes, arrete);
        return new Groupes.Resultat(restes == 0 && !arrete, arrete, liste.size(), faits, restes, msg, null);
    }

    /** « 12 mobis ramassés. Ctrl+Z pour les reposer. » (logique pure). */
    static String messageRamassage(int faits, int voulus, int restes, boolean arrete) {
        String m = (arrete ? "Arrêté. " : "") + (faits == voulus ? (faits > 1 ? faits + " mobis ramassés" : faits + " mobi ramassé")
                : faits + "/" + voulus + " mobi(s) ramassé(s)")
                + (restes > 0 && !arrete ? ", " + restes + " refusé(s) par le jeu (droits ? mobi verrouillé ?)" : "") + ".";
        if (faits > 0) m += faits > 1 ? " Ctrl+Z pour les reposer." : " Ctrl+Z pour le reposer.";
        return m;
    }

    private static boolean present(int[] k) { return k[1] == 1 ? Salle.mur(k[0]) != null : Salle.sol(k[0]) != null; }

    private static int presents(List<int[]> l) {
        int n = 0;
        for (int[] k : l) if (present(k)) n++;
        return n;
    }

    // ------------------------------------------------------------ dupliquer

    /** Un mur a poser. */
    static final class MurPose {
        final String classe, etat, position;   // position deja decalee
        MurPose(String classe, String etat, String position) { this.classe = classe; this.etat = etat; this.position = position; }
    }

    static Groupes.Resultat dupliquer(Groupes.Tache t, String calqueId, int dx, int dy, Generateur.Source source) {
        return dupliquer(t, calqueId, els -> GroupeCalcul.cibles(els, dx, dy, Salle::hauteurSol), source, "copie");
    }

    /**
     * Pose une copie des mobis du calque aux destinations calculees par
     * « calcul » (decalage, miroir...) ; les mobis poses deviennent un nouveau
     * calque « <nom> <suffixe> ».
     */
    static Groupes.Resultat dupliquer(Groupes.Tache t, String calqueId,
                                      java.util.function.Function<List<GroupeCalcul.Element>, List<GroupeCalcul.Cible>> calcul,
                                      Generateur.Source source, String suffixe) {
        // un calque normal, numerote (« Calque N »), au-dessus de l'original
        // (en haut si la source est la selection : elle n'est pas un calque)
        return dupliquer(t, Groupes.mobis(calqueId), Groupes.SELECTION.equals(calqueId) ? null : calqueId, calcul, source);
    }

    /**
     * Meme chose a partir d'ids donnes (sols, murs) : coller une copie (Ctrl+V).
     * Pose par le TAPIS DE DALLES (PoseTapis) : 1. dalles magiques sous toutes
     * les cases de la copie ; 2. chaque mobi de sol, du bas vers le haut, pose
     * depuis l'inventaire / le BC puis mis a sa hauteur (@altitude) ; 3. les
     * muraux ; 4. dalles ramassees ; 5. verification (une nouvelle tentative
     * par mobi). Sans @altitude, les mobis en hauteur passent par la dalle par
     * mobi. Une seule action pour Ctrl+Z.
     * @param dessus calque au-dessus duquel ranger le nouveau (null = en haut)
     */
    static Groupes.Resultat dupliquer(Groupes.Tache t, List<Set<Integer>> ids, String dessus,
                                      java.util.function.Function<List<GroupeCalcul.Element>, List<GroupeCalcul.Cible>> calcul,
                                      Generateur.Source source) {
        int salle = Groupes.salleCourante();
        Moteur gp = Salle.gp();
        if (gp == null) return Groupes.Resultat.refus("L'Atelier n'est pas encore prêt.");
        if (!Salle.furnidataPrete()) return Groupes.Resultat.refus("Furnidata pas encore chargée.");
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun de ces mobis n'est dans la salle.");

        // 1. ce qu'on pose (altitudes absolues d'arrivee)
        List<GroupeCalcul.Cible> cibles = calcul.apply(els);
        List<PoseTapis.Piece> sols = new ArrayList<>();
        List<PoseDirecte.Mur> murs = new ArrayList<>();
        int wired = 0, inconnus = 0, hors = 0, muraux = 0;
        for (GroupeCalcul.Element e : els) if (e.mural) muraux++;
        for (GroupeCalcul.Cible c : cibles) if (c.e.mural) muraux--;   // reste : muraux laisses de cote
        for (GroupeCalcul.Cible c : cibles) {
            if (c.e.mural) {
                HWallItem w = Salle.mur(c.e.id);
                String cls = w == null ? null : Salle.classe(w.getTypeId(), true);
                if (cls == null) { inconnus++; continue; }
                if (c.position == null) { hors++; continue; }
                murs.add(new PoseDirecte.Mur(cls, SelectionMur.normaliser(c.position)));
                continue;
            }
            HFloorItem it = Salle.sol(c.e.id);
            String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
            if (cls == null) { inconnus++; continue; }
            if (Wired.estWired(cls)) { wired++; continue; }
            if (c.horsPlan) { hors++; continue; }
            int[] e = PoseTapis.emprise(cls, c.rot);
            sols.add(PoseTapis.Piece.nouveau(cls, Generateur.etatDe(it), Salle.nom(it.getTypeId(), false), c.x, c.y,
                    Generateur.arrondi(Math.max(0, c.z)), c.rot, e[0], e[1], -1));
        }
        int voulus = sols.size() + murs.size();
        String ignores = (wired > 0 ? wired + " wired ignoré(s) (sans leur réglage). " : "")
                + (inconnus > 0 ? inconnus + " mobi(s) de classe inconnue ignoré(s). " : "")
                + (hors > 0 ? hors + " mobi(s) qui tomberaient hors du plan ignoré(s). " : "")
                + (muraux > 0 ? muraux + " mobi(s) mural(aux) laissé(s) de côté (ils ne pivotent pas). " : "");
        if (voulus == 0) return Groupes.Resultat.refus("Rien à poser. " + ignores);
        Journal.debug("calques : copie par tapis de dalles, " + sols.size() + " sol(s), " + murs.size() + " mural(aux).");

        java.util.function.BooleanSupplier stop = () -> t.arretee() || !memeSalle(salle);
        PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(source, t::arretee, t::dire, t::progres, false);
        List<Integer> nM = new ArrayList<>();
        int[] mursRefuses = {0};
        // 3. les muraux, apres les sols, pendant que le tapis est encore la
        Runnable etape3 = murs.isEmpty() ? null : () -> {
            PoseDirecte.Resultat pm = PoseDirecte.poser(List.of(), murs, source, t::dire, stop,
                    (f, n) -> t.progres(f, n, "Muraux : " + f + "/" + n), false);
            nM.addAll(pm.murs);
            if (!pm.mursRefuses.isEmpty() && !stop.getAsBoolean()) {
                List<PoseHybride.Piece> rep = new ArrayList<>();
                for (PoseDirecte.Mur m : pm.mursRefuses) rep.add(PoseHybride.Piece.mur(m.classe, "", m.position, -1));
                PoseHybride.Bilan rb = PoseHybride.reprendre(rep, source, m -> Journal.debug("reprise : " + m), stop,
                        (f, n) -> t.progres(f, n, "Reprise à la dalle : " + f + "/" + n));
                nM.addAll(rb.murs);
            }
            mursRefuses[0] = Math.max(0, murs.size() - nM.size());
        };
        PoseTapis.Bilan b;
        historiqueGrouper(true);           // dalles exceptees (hors historique) : une seule action pour Ctrl+Z
        try {
            b = PoseTapis.executer(sols, jeu, t::progres, etape3);
        } finally {
            historiqueGrouper(false);
        }
        boolean arrete = b.arrete || t.arretee();
        if (b.sortie || !memeSalle(salle))
            return new Groupes.Resultat(false, true, voulus, 0, voulus, "Tu as quitté la salle pendant la pose.", null);

        // les nouveaux mobis -> nouveau calque
        List<Integer> nS = new ArrayList<>();
        for (PoseTapis.Piece p : sols) if (p.obtenu >= 0) nS.add(p.obtenu);
        int obtenus = nS.size() + nM.size();
        String nouveau = obtenus > 0 ? Groupes.creer(null, nS, nM, dessus) : null;
        int reussis = b.reussis() + nM.size();
        int echecs = Math.max(0, voulus - reussis);
        String msg = (sols.isEmpty() ? (arrete ? "Arrêté. " : "") : b.texte("posé"))
                + (murs.isEmpty() ? "" : " Muraux : " + nM.size() + "/" + murs.size()
                        + (mursRefuses[0] > 0 && !arrete ? ", " + mursRefuses[0] + " refusé(s)." : "."))
                + (nouveau != null ? " Nouveau calque créé." : "")
                + (b.sansAltitude ? PoseTapis.SANS_ALTITUDE : "")
                + (b.etatsFaux > 0 ? " " + b.etatsFaux + " mobi(s) dans un autre état (couleur, allumé…)." : "")
                + (ignores.isEmpty() ? "" : " " + ignores.trim())
                + (obtenus > 0 ? " " + WindowsClavier.texte("Cmd+Z pour annuler.") : "");
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, voulus, obtenus, echecs,
                Ui.accorder(msg.trim()), nouveau);
    }

    // ------------------------------------------------- dupliquer (options)

    /**
     * Dupliquer avec les choix de la fenetre : quoi copier (sols, murs, wired)
     * et d'ou viennent les mobis. La copie est posee A LA MEME PLACE (dalle
     * magique du moteur de pose : hauteurs exactes). Avec les wired, sols et wired
     * passent ensemble par le collage wired (reglages et selections remappes
     * vers les mobis de la copie). Ensuite, s'il n'y a pas de dalle magique
     * dans la copie, des dalles (BC) sont posees sur sa zone et rangees dans
     * son calque : au deplacement, elles partent d'abord, puis chaque mobi
     * est pose a sa hauteur (deplacerAvecDalles).
     */
    static Groupes.Resultat dupliquerOptions(Groupes.Tache t, String calqueId, boolean sols, boolean murs,
                                             boolean wired, Generateur.Source source) {
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        Set<Integer> s = new LinkedHashSet<>(), w = new LinkedHashSet<>();
        Set<Integer> m = murs ? new LinkedHashSet<>(ids.get(1)) : new LinkedHashSet<>();
        for (int id : ids.get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            if (Wired.estWired(Salle.classe(it.getTypeId(), false))) { if (wired) w.add(id); }
            else if (sols) s.add(id);
        }
        if (s.isEmpty() && w.isEmpty() && m.isEmpty()) return Groupes.Resultat.refus("Rien à dupliquer avec ces choix.");
        String dessus = Groupes.SELECTION.equals(calqueId) ? null : calqueId;

        String nouveau;
        String msg;
        int voulus, obtenus;
        if (!w.isEmpty()) {
            Set<Integer> tous = new LinkedHashSet<>(s);
            tous.addAll(w);
            t.dire("Copie avec les wired et leur réglage…");
            List<Integer> nS = WiredCollage.collerSurPlace(tous, source, t::arretee);
            List<Integer> nM = m.isEmpty() || t.arretee() ? List.of() : poserMursSurPlace(t, m, source);
            voulus = tous.size() + m.size();
            obtenus = nS.size() + nM.size();
            nouveau = obtenus > 0 ? Groupes.creer(null, nS, nM, dessus) : null;
            msg = (t.arretee() ? "Arrêté. " : "") + obtenus + "/" + voulus + " mobi(s) posé(s), wired avec leur réglage"
                    + (nouveau != null ? " — nouveau calque créé" : "") + ".";
        } else {
            Groupes.Resultat r = dupliquer(t, List.of(s, m), dessus,
                    els -> GroupeCalcul.cibles(els, 0, 0, Salle::hauteurSol), source);
            if (r.nouveauCalque == null) return r;
            nouveau = r.nouveauCalque; msg = r.message; voulus = r.voulus; obtenus = r.reussis;
        }
        if (nouveau == null || t.arretee() || !memeSalle(salle))
            return new Groupes.Resultat(false, t.arretee(), voulus, obtenus, voulus - obtenus, msg, nouveau);

        return new Groupes.Resultat(obtenus == voulus, false, voulus, obtenus, voulus - obtenus, msg, nouveau);
    }

    /** Copie des muraux donnes a leur place (moteur de pose). Ids des nouveaux muraux. */
    private static List<Integer> poserMursSurPlace(Groupes.Tache t, Set<Integer> murs, Generateur.Source source) {
        Moteur gp = Salle.gp();
        if (gp == null) return List.of();
        List<MurPose> l = new ArrayList<>();
        Map<Integer, Integer> attendus = new HashMap<>();
        for (int id : murs) {
            HWallItem w = Salle.mur(id);
            String cls = w == null ? null : Salle.classe(w.getTypeId(), true);
            if (cls == null) continue;
            l.add(new MurPose(cls, w.getState(), SelectionMur.normaliser(w.getLocation())));
            attendus.merge(w.getTypeId(), 1, Integer::sum);
        }
        if (l.isEmpty()) return List.of();
        Set<Integer> avant = Groupes.idsMurs();
        try { if (poser(List.of(), l, source, t::dire, t::arretee, null) == null) return List.of(); }
        catch (Throwable e) { t.dire("Muraux : " + e); return List.of(); }
        // suivi : seulement le temps que les derniers muraux apparaissent
        int voulus = 0;
        for (int k : attendus.values()) voulus += k;
        final int v = voulus;
        PoseDirecte.suivre(() -> v - nouveaux(avant, attendus, true).size(), 800, 1500);
        return nouveaux(avant, attendus, true);
    }

    // ------------------------------------------- copie placee (fantomes, puis Poser)

    /** {sols hors wired, wired, muraux} presents dans la salle parmi ces ids. */
    static int[] sortes(List<Set<Integer>> ids) {
        int s = 0, w = 0, m = 0;
        for (int id : ids.get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            if (Wired.estWired(Salle.classe(it.getTypeId(), false))) w++; else s++;
        }
        for (int id : ids.get(1)) if (Salle.mur(id) != null) m++;
        return new int[]{s, w, m};
    }

    /**
     * Pose VRAIE de la copie placee avec les fantomes (Dupliquer, Coller, copie
     * pivotee ou miroir) : memes destinations que les fantomes
     * (GroupeCalcul.copie). Sans wired : le moteur de pose, avec la dalle
     * magique (hauteurs et piles exactes). Avec les wired : collage wired sur
     * place (reglages remappes), puis la copie part a sa place (deplacerAvecDalles,
     * meme pivot que les fantomes) ; pas de miroir dans ce cas.
     * Les mobis poses deviennent un nouveau calque.
     */
    static Groupes.Resultat poserCopie(Groupes.Tache t, List<Set<Integer>> ids, String dessus, GroupeCalcul.Transfo tr,
                                       boolean sols, boolean murs, boolean wired, Generateur.Source source) {
        int salle = Groupes.salleCourante();
        List<Set<Integer>> f = GroupeApercu.filtrer(ids, sols, murs, wired);
        Set<Integer> s = new LinkedHashSet<>(), w = new LinkedHashSet<>();
        for (int id : f.get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            (Wired.estWired(Salle.classe(it.getTypeId(), false)) ? w : s).add(id);
        }
        Set<Integer> m = new LinkedHashSet<>(f.get(1));
        if (s.isEmpty() && w.isEmpty() && m.isEmpty()) return Groupes.Resultat.refus("Rien à copier avec ces choix.");
        if (w.isEmpty())   // pose hybride : rafale, puis la dalle pour les seuls refuses
            return dupliquer(t, List.of(s, m), dessus, els -> GroupeCalcul.copie(els, tr, Salle::hauteurSol), source);

        if (tr.miroir != 0) return Groupes.Resultat.refus("Le miroir ne marche pas avec les wired. Décoche les wired ou enlève le miroir.");
        Set<Integer> tous = new LinkedHashSet<>(s);
        tous.addAll(w);
        boolean muraux = !m.isEmpty() && tr.glissement();
        t.dire("Copie avec les wired et leur réglage…");
        List<Integer> nS, nM;
        // collage + mise en place : une seule action pour Ctrl+Z
        historiqueGrouper(true);
        try {
            nS = WiredCollage.collerSurPlace(tous, source, t::arretee);
            nM = !muraux || t.arretee() ? List.of() : poserMursSurPlace(t, m, source);
            int voulus = tous.size() + (muraux ? m.size() : 0);
            int obtenus = nS.size() + nM.size();
            String nouveau = obtenus > 0 && memeSalle(salle) ? Groupes.creer(null, nS, nM, dessus) : null;
            String msg = (t.arretee() ? "Arrêté. " : "") + obtenus + "/" + voulus + " mobi(s) posé(s), wired avec leur réglage"
                    + (nouveau != null ? " — nouveau calque créé" : "") + "."
                    + (!m.isEmpty() && !muraux ? " Les muraux ne pivotent pas : laissés de côté." : "");
            if (nouveau == null || t.arretee() || !memeSalle(salle))
                return new Groupes.Resultat(false, t.arretee(), voulus, obtenus, voulus - obtenus, msg, nouveau);
            if (!tr.neutre()) {
                // la copie est sur l'original : elle part a la place des fantomes (pose
                // hybride : MoveObject en rafale, la dalle pour les seuls refuses)
                t.dire("Copie posée. Je la mets à sa place…");
                Groupes.Resultat r = deplacerAvecDalles(t, nouveau, tr.quarts, tr.dx, tr.dy);
                msg += " Mise en place : " + Ui.majuscule(r.message);
                return new Groupes.Resultat(obtenus == voulus && r.ok, r.arrete, voulus, obtenus, voulus - obtenus, msg, nouveau);
            }
            return new Groupes.Resultat(obtenus == voulus, false, voulus, obtenus, voulus - obtenus, msg, nouveau);
        } finally {
            historiqueGrouper(false);
        }
    }

    /**
     * Pose une copie aux destinations de « calcul » par le moteur de pose,
     * AVEC la dalle magique (comme avant PoseDirecte) : chaque mobi a sa
     * hauteur exacte, piles respectees. Le z donne au moteur est l'altitude
     * au-dessus du sol le plus bas sous la copie (il y ajoute ce sol). Les
     * mobis poses deviennent un nouveau calque. Une seule action pour Ctrl+Z.
     */
    static Groupes.Resultat dupliquerDalle(Groupes.Tache t, List<Set<Integer>> ids, String dessus,
                                           java.util.function.Function<List<GroupeCalcul.Element>, List<GroupeCalcul.Cible>> calcul,
                                           Generateur.Source source) {
        int salle = Groupes.salleCourante();
        Moteur gp = Salle.gp();
        if (gp == null) return Groupes.Resultat.refus("L'Atelier n'est pas encore prêt.");
        if (!Salle.furnidataPrete()) return Groupes.Resultat.refus("Furnidata pas encore chargée.");
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun de ces mobis n'est dans la salle.");

        // 1. ce qu'on pose : altitude au-dessus du sol le plus bas sous la copie
        List<GroupeCalcul.Cible> cibles = calcul.apply(els);
        int solMin = GroupeCalcul.solMin(cibles, Salle::hauteurSol);
        List<Generateur.Mobi> mobis = new ArrayList<>();
        List<MurPose> murs = new ArrayList<>();
        Map<Integer, Integer> attendusSols = new HashMap<>(), attendusMurs = new HashMap<>();   // type -> nombre
        int wired = 0, inconnus = 0, hors = 0;
        int muraux = 0;
        for (GroupeCalcul.Element e : els) if (e.mural) muraux++;
        for (GroupeCalcul.Cible c : cibles) if (c.e.mural) muraux--;   // reste : muraux laisses de cote
        for (GroupeCalcul.Cible c : cibles) {
            if (c.e.mural) {
                HWallItem w = Salle.mur(c.e.id);
                String cls = w == null ? null : Salle.classe(w.getTypeId(), true);
                if (cls == null) { inconnus++; continue; }
                if (c.position == null) { hors++; continue; }
                murs.add(new MurPose(cls, w.getState(), SelectionMur.normaliser(c.position)));
                attendusMurs.merge(w.getTypeId(), 1, Integer::sum);
                continue;
            }
            HFloorItem it = Salle.sol(c.e.id);
            String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
            if (cls == null) { inconnus++; continue; }
            if (Wired.estWired(cls)) { wired++; continue; }
            if (c.horsPlan) { hors++; continue; }
            mobis.add(new Generateur.Mobi(cls, Generateur.etatDe(it), c.x, c.y, GroupeCalcul.zPreset(c, solMin), c.rot));
            attendusSols.merge(it.getTypeId(), 1, Integer::sum);
        }
        int voulus = mobis.size() + murs.size();
        String ignores = (wired > 0 ? wired + " wired ignoré(s) (sans leur réglage). " : "")
                + (inconnus > 0 ? inconnus + " mobi(s) de classe inconnue ignoré(s). " : "")
                + (hors > 0 ? hors + " mobi(s) qui tomberaient hors du plan ignoré(s). " : "")
                + (muraux > 0 ? muraux + " mobi(s) mural(aux) laissé(s) de côté (ils ne pivotent pas). " : "");
        if (voulus == 0) return Groupes.Resultat.refus("Rien à poser. " + ignores);
        Journal.debug("calques : copie par le moteur de pose, " + mobis.size() + " sol(s), " + murs.size()
                + " mural(aux), sol min " + solMin + ".");

        // 2. la salle avant
        Set<Integer> avantS = Groupes.idsSols(), avantM = Groupes.idsMurs();

        // 3. la pose (copie + dalle magique), en comptant les nouveaux mobis
        boolean arrete, sortie;
        historiqueGrouper(true);
        try {
            PoseCopie.Resultat r;
            try {
                r = poser(mobis, murs, source, t::dire, t::arretee, () -> {
                    int n = nouveaux(avantS, attendusSols, false).size() + nouveaux(avantM, attendusMurs, true).size();
                    t.progres(Math.min(n, voulus), voulus, "Pose de la copie : " + n + "/" + voulus);
                });
            }
            catch (Throwable e) { return Groupes.Resultat.refus("Pose impossible : " + e); }
            if (r == null || !r.lancee)
                return new Groupes.Resultat(false, false, voulus, 0, voulus, "La pose n'a pas démarré. " + ignores, null);
            arrete = r.arrete && memeSalle(salle);
            sortie = !memeSalle(salle);
            if (!sortie) PoseDirecte.suivre(() -> voulus - nouveaux(avantS, attendusSols, false).size()
                    - nouveaux(avantM, attendusMurs, true).size(), 800, 2000);
        } finally {
            historiqueGrouper(false);
        }
        if (sortie) return new Groupes.Resultat(false, true, voulus, 0, voulus, "Tu as quitté la salle pendant la pose.", null);

        // 5. les nouveaux mobis -> nouveau calque
        List<Integer> nS = nouveaux(avantS, attendusSols, false), nM = nouveaux(avantM, attendusMurs, true);
        int obtenus = nS.size() + nM.size();
        String nouveau = obtenus > 0 ? Groupes.creer(null, nS, nM, dessus) : null;
        int echecs = Math.max(0, voulus - obtenus);
        String msg = (arrete ? "Arrêté. " : "") + obtenus + "/" + voulus + " mobi(s) posé(s)"
                + (nouveau != null ? " — nouveau calque créé" : "")
                + (echecs > 0 && !arrete ? ", " + echecs + " manquant(s) (inventaire / BC ? case refusée ?)" : "")
                + ". " + ignores + (obtenus > 0 ? WindowsClavier.texte("Cmd+Z pour annuler.") : "");
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, voulus, obtenus, echecs, msg.trim(), nouveau);
    }

    // ------------------------------------------ deplacer avec dalles magiques

    /**
     * Deplacer (et pivoter) un calque, par le tapis de dalles (bougerTapis).
     * Si le calque a ses propres dalles magiques, elles partent d'abord a leur
     * place d'arrivee (dans la meme action) ; puis le reste suit le tapis.
     */
    static Groupes.Resultat deplacerAvecDalles(Groupes.Tache t, String calqueId, int quarts, int dx, int dy) {
        int q = ((quarts % 4) + 4) % 4;
        if (dx == 0 && dy == 0 && q == 0) return Groupes.Resultat.refus("Rien à faire : ni déplacement ni pivot.");
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        Set<Integer> types = Generateur.Dalle.typesDalles();
        Set<Integer> dalles = new HashSet<>();
        for (GroupeCalcul.Element e : els) {
            if (e.mural) continue;
            HFloorItem it = Salle.sol(e.id);
            if (it != null && types.contains(it.getTypeId())) dalles.add(e.id);
        }
        if (dalles.isEmpty())
            return q == 0 ? deplacer(t, calqueId, dx, dy) : deplacerTourne(t, calqueId, q, dx, dy);

        List<GroupeCalcul.Cible> cibles = GroupeCalcul.transformer(els, q, dx, dy, Salle::hauteurSol);
        List<GroupeCalcul.Cible> cDalles = new ArrayList<>(), autres = new ArrayList<>();
        for (GroupeCalcul.Cible c : cibles)
            (!c.e.mural && !c.horsPlan && dalles.contains(c.e.id) ? cDalles : autres).add(c);
        int muraux = 0;
        for (GroupeCalcul.Element e : els) if (e.mural) muraux++;
        for (GroupeCalcul.Cible c : cibles) if (c.e.mural) muraux--;
        int m = muraux;
        // les dalles du calque d'abord : rafale suivie, une reprise de celles qui manquent
        Runnable prelude = () -> {
            List<GroupeCalcul.Cible> aFaire = nonPlaces(cDalles);
            int voulues = aFaire.size(), apresPremiere = -1;
            // la rafale, puis jusqu'a REESSAIS renvois de celles pas arrivees
            for (int passe = 0; passe <= Salle.REESSAIS && !aFaire.isEmpty(); passe++) {
                if (passe > 0) Salle.pauseReessai();
                int n = 0;
                for (GroupeCalcul.Cible c : aFaire) {
                    if (t.arretee() || !memeSalle(salle)) return;
                    Salle.espacer();
                    Salle.deplacerSol(c.e.id, c.x, c.y, c.rot);
                    t.progres(++n, aFaire.size(), "Dalles du calque : " + n + "/" + aFaire.size());
                }
                final List<GroupeCalcul.Cible> l = aFaire;
                PoseDirecte.suivre(() -> nonPlaces(l).size(), 700, 1500);
                aFaire = nonPlaces(aFaire);
                if (passe == 0) apresPremiere = aFaire.size();
            }
            if (t.arretee() || !memeSalle(salle)) return;
            if (apresPremiere > aFaire.size())
                Journal.debug("calques : " + (apresPremiere - aFaire.size()) + " dalle(s) du calque déplacée(s) après réessai.");
            // seules celles restees refusees apres leurs reessais comptent pour le frein
            Salle.signalerReussite(voulues - aFaire.size());
            Salle.signalerRefus("dalle du calque pas déplacée", aFaire.size());
        };
        return bougerTapis(t, salle, autres, (q & 1) == 1, q != 0 ? "pivoté" : "déplacé", prelude,
                b -> " " + (cDalles.size() - nonPlaces(cDalles).size()) + "/" + cDalles.size()
                        + " dalle(s) magique(s) du calque à leur place."
                        + (q != 0 ? suitePivot(b, m) : ""));
    }

    private static List<GroupeCalcul.Cible> nonPlaces(List<GroupeCalcul.Cible> l) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) if (!place(c)) r.add(c);
        return r;
    }

    /** Le mobi de sol est-il a sa case d'arrivee (rotation voulue ou equivalente) ? */
    private static boolean place(GroupeCalcul.Cible c) {
        HFloorItem now = Salle.sol(c.e.id);
        return now != null && now.getTile().getX() == c.x && now.getTile().getY() == c.y
                && GroupeCalcul.rotationAcceptee(Salle.rotation(now), c.rot, c.e.rot, false);
    }

    /**
     * Les mobis apparus depuis « avant », dont le type est attendu (au plus le
     * nombre attendu par type, les plus recents ignores au-dela) : la dalle
     * magique de l'Atelier et les poses d'autres joueurs ne sont pas prises.
     */
    static List<Integer> nouveaux(Set<Integer> avant, Map<Integer, Integer> attendus, boolean mural) {
        Map<Integer, Integer> reste = new HashMap<>(attendus);
        List<Integer> r = new ArrayList<>();
        if (mural) {
            for (HWallItem w : Salle.murs()) {
                if (avant.contains(w.getId()) || GroupeFantomes.estFantome(w.getId())) continue;
                Integer k = reste.get(w.getTypeId());
                if (k == null || k <= 0) continue;
                reste.put(w.getTypeId(), k - 1);
                r.add(w.getId());
            }
        } else {
            for (HFloorItem it : Salle.sols()) {
                if (avant.contains(it.getId()) || GroupeFantomes.estFantome(it.getId())) continue;
                Integer k = reste.get(it.getTypeId());
                if (k == null || k <= 0) continue;
                reste.put(it.getTypeId(), k - 1);
                r.add(it.getId());
            }
        }
        return r;
    }

    /**
     * Pose avec la dalle magique (PoseCopie) des sols et des murs aux positions
     * ABSOLUES voulues, synchrone. La copie est ramenee a l'origine (min x,
     * min y) et le coin mis a ce point ; z est l'altitude au-dessus du sol le
     * plus bas sous la copie (ancre 0).
     *
     * @param stop  arret demande (peut etre null)
     * @param suivi appele toutes les 400 ms pendant la pose (peut etre null)
     * @return le resultat ; null si la pose n'a pas pu commencer (la raison est dite)
     */
    static PoseCopie.Resultat poser(List<Generateur.Mobi> mobis, List<MurPose> murs, Generateur.Source source,
                                    Consumer<String> dire, java.util.function.BooleanSupplier stop, Runnable suivi) {
        if (!Salle.dansUneSalle()) { dire.accept("Tu n'es pas dans une salle."); return null; }
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.furnidataPrete()) { dire.accept("L'Atelier n'est pas encore prêt."); return null; }
        if (PoseCopie.occupee()) {
            dire.accept("L'Atelier est déjà en train de poser : attends la fin, ou tape :abort dans le jeu.");
            return null;
        }
        Furnidata fd = gp.getFurniDataTools();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Generateur.Mobi m : mobis) { minX = Math.min(minX, m.x); minY = Math.min(minY, m.y); }
        List<PositionMur> positions = new ArrayList<>();
        for (MurPose w : murs) {
            PositionMur p = PositionMur.lire(w.position);
            positions.add(p);
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y());
        }
        HPoint racine = new HPoint(minX, minY);

        CopieAppart cfg = new CopieAppart();
        cfg.ancre = 0.0;
        List<Generateur.Mobi> relatifs = new ArrayList<>();
        int id = 1;
        for (Generateur.Mobi m : mobis) {
            if (fd.getFloorTypeId(m.classe) == null) { dire.accept("« " + m.classe + " » inconnu de la furnidata : pose annulée."); return null; }
            CopieAppart.MobiSol p = new CopieAppart.MobiSol(id++, m.classe, m.x - minX, m.y - minY,
                    Math.max(0, Generateur.arrondi(m.z)), m.rot & 7, m.etat);
            p.nom = nomSol(fd, m.classe);
            cfg.sols.add(p);
            relatifs.add(new Generateur.Mobi(m.classe, m.etat, m.x - minX, m.y - minY, m.z, m.rot));
        }
        for (int i = 0; i < murs.size(); i++) {
            MurPose w = murs.get(i);
            if (fd.getWallTypeId(w.classe) == null) { dire.accept("« " + w.classe + " » (mural) inconnu de la furnidata : pose annulée."); return null; }
            PositionMur rel = positions.get(i).deplacee(-minX, -minY);
            CopieAppart.MobiMur pw = new CopieAppart.MobiMur(id++, w.classe, rel, w.etat == null ? "" : w.etat);
            try {
                Furnidata.Mobi d = fd.getWallItemDetails(w.classe);
                pw.nom = d != null && d.name != null && !d.name.isBlank() ? d.name : w.classe;
            } catch (Throwable e) { pw.nom = w.classe; }
            cfg.murs.add(pw);
        }
        // meme salle : les murs ne sont pas recales
        EtatSalle fs = Salle.etat();
        if (fs != null && fs.getRawFloorplan() != null)
            cfg.disposition = CopieAppart.Disposition.depuisPlan(fs.getRoomModelName(), fs.getFloorplanWidth(),
                    fs.getFloorplanHeight(), fs.getFloorScale(), fs.getFloorWallHeight(), fs.getRawFloorplan());

        HPoint caseDalle = null;
        // Les dalles de la salle ne servent que LIBRES (ni d'un tapis en cours, ni sous d'autres
        // mobis : PoseDalle.utilisable) ; sinon la pose en met une neuve a part (case libre
        // cherchee par PoseDalle) et la ramasse a la fin.
        boolean dallesLa = false;
        Set<Integer> typesDalles = Generateur.Dalle.typesDalles();
        for (HFloorItem it : Salle.sols()) if (typesDalles.contains(it.getTypeId())) { dallesLa = true; break; }
        if (!relatifs.isEmpty() && dallesLa
                && !PoseDalle.dalleUtilisable(Salle.etat(), fd, Generateur.Dalle.exigences(relatifs))) {
            Journal.debug("pose à la dalle : aucune dalle de la salle n'est libre (tapis en cours, mobis posés dessus) : "
                    + "une dalle neuve sera posée à part.");
            dire.accept("Dalle magique : celles de la salle sont prises, une neuve est posée à part puis ramassée.");
        } else if (!relatifs.isEmpty()) {
            List<int[]> trace = Generateur.Dalle.trace(relatifs, 0, 0, racine);
            int[] depart = new int[]{racine.getX() + relatifs.get(0).x, racine.getY() + relatifs.get(0).y};
            Generateur.Dalle.Pret dalle = Generateur.Dalle.preparer(relatifs, trace, depart, dire);
            if (dalle == null) return null;
            caseDalle = dalle.ou;
        }
        dire.accept(Ui.accorder((cfg.sols.size() + cfg.murs.size()) + " mobi(s) à poser avec la dalle magique… "
                + "(:abort dans le jeu pour arrêter)"));
        PoseCopie.Resultat r = Generateur.poserCopie(cfg, source, racine, dire, caseDalle, stop, suivi);
        if (r == null || !r.lancee) return null;
        String bilan = r.texte();
        dire.accept(bilan);
        InfoJeu.dire(bilan);
        return r;
    }

    private static String nomSol(Furnidata fd, String classe) { return Generateur.nomSol(fd, classe); }
}

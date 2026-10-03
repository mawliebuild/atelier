package atelier;

import extension.GPresets;
import extension.tools.GPresetImporter;
import extension.tools.presetconfig.PresetConfig;
import extension.tools.presetconfig.furni.PresetFurni;
import extension.tools.presetconfig.furni.PresetWallFurni;
import extension.tools.presetconfig.wired.PresetWireds;
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

    static Groupes.Resultat deplacer(Groupes.Tache t, String calqueId, int dx, int dy) {
        if (dx == 0 && dy == 0) return Groupes.Resultat.refus("Décalage nul : rien à déplacer.");
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun mobi de ce calque dans la salle.");

        Map<String, GroupeCalcul.Cible> cibles = new HashMap<>();
        for (GroupeCalcul.Cible c : GroupeCalcul.cibles(els, dx, dy, Salle::hauteurSol))
            cibles.put((c.e.mural ? "m" : "s") + c.e.id, c);
        List<GroupeCalcul.Element> ordre = GroupeCalcul.ordre(els, dx, dy);
        List<GroupeCalcul.Element> reste = new ArrayList<>();
        int impossibles = 0;
        for (GroupeCalcul.Element e : ordre) {
            GroupeCalcul.Cible c = cibles.get((e.mural ? "m" : "s") + e.id);
            if (c == null || c.horsPlan || (e.mural && c.position == null)) impossibles++;
            else reste.add(e);
        }
        int total = els.size();
        int fait = 0;
        boolean arrete = false;

        // deux passes : la seconde reprend ceux qu'un voisin genait encore
        for (int passe = 1; passe <= 2 && !reste.isEmpty(); passe++) {
            int n = 0;
            for (GroupeCalcul.Element e : reste) {
                if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                GroupeCalcul.Cible c = cibles.get((e.mural ? "m" : "s") + e.id);
                if (e.mural) Salle.deplacerMur(e.id, SelectionMur.normaliser(c.position));
                else Salle.deplacerSol(e.id, c.x, c.y, e.rot);
                n++;
                t.progres(passe == 1 ? ++fait : fait, total, "Passe " + passe + " : " + n + "/" + reste.size() + " envoyé(s)...");
                Salle.sommeil(PAUSE);
            }
            if (arrete) break;
            Salle.sommeil(900);
            List<GroupeCalcul.Element> encore = new ArrayList<>();
            for (GroupeCalcul.Element e : reste) if (!arrive(e, cibles.get((e.mural ? "m" : "s") + e.id))) encore.add(e);
            reste = encore;
        }
        if (arrete) Salle.sommeil(900);

        // verification + hauteurs
        int arrives = 0;
        List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
        for (GroupeCalcul.Element e : els) {
            GroupeCalcul.Cible c = cibles.get((e.mural ? "m" : "s") + e.id);
            if (c == null || !arrive(e, c)) continue;
            arrives++;
            if (e.mural) continue;
            HFloorItem now = Salle.sol(e.id);
            if (now != null && Math.abs(now.getTile().getZ() - c.z) > 0.05) aRegler.add(c);
        }
        String hauteurs = arrete ? "" : hauteurs(t, aRegler);
        int echecs = total - arrives;
        String msg = (arrete ? "Arrêté. " : "") + arrives + "/" + total + " mobi(s) déplacé(s) de (" + dx + "," + dy + ")"
                + (impossibles > 0 ? ", " + impossibles + " impossible(s) (hors du plan ou position murale illisible)" : "")
                + (echecs - impossibles > 0 && !arrete ? ", " + (echecs - impossibles) + " bloqué(s) (case occupée ?)" : "")
                + "." + hauteurs;
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, total, arrives, echecs, msg, null);
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

    /** Remet les altitudes par @altitude (comme OutilMiroir). @return fin du message */
    private static String hauteurs(Groupes.Tache t, List<GroupeCalcul.Cible> aRegler) {
        if (aRegler.isEmpty()) return "";
        if (!OutilMiroir.Altitude.connue()) {
            t.dire("Recherche de @altitude...");
            GroupeCalcul.Cible k = aRegler.get(0);
            OutilMiroir.Altitude.chercher(k.e.id, k.z);
        }
        if (!OutilMiroir.Altitude.connue())
            return " ⚠ " + aRegler.size() + " mobi(s) ont changé de hauteur (posés sur le dessus de la pile) : "
                    + "@altitude inconnue. Règle-la une fois dans l'éditeur :wired, puis refais le déplacement.";
        for (int passe = 1; passe <= 2 && !aRegler.isEmpty(); passe++) {
            int n = 0;
            for (GroupeCalcul.Cible c : aRegler) {
                if (t.arretee()) break;
                OutilMiroir.Altitude.ecrire(c.e.id, c.z);
                t.progres(++n, aRegler.size(), "Hauteurs, passe " + passe + " : " + n + "/" + aRegler.size());
                Salle.sommeil(PAUSE + 50);
            }
            Salle.sommeil(800);
            List<GroupeCalcul.Cible> encore = new ArrayList<>();
            for (GroupeCalcul.Cible c : aRegler) {
                HFloorItem now = Salle.sol(c.e.id);
                if (now == null || Math.abs(now.getTile().getZ() - c.z) > 0.05) encore.add(c);
            }
            aRegler = encore;
        }
        return aRegler.isEmpty() ? " Hauteurs remises." : " " + aRegler.size() + " hauteur(s) non remise(s).";
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

        if (!OutilMiroir.Altitude.connue()) {
            t.dire("Recherche de @altitude...");
            Map.Entry<Integer, Double> k = voulu.entrySet().iterator().next();
            OutilMiroir.Altitude.chercher(k.getKey(), k.getValue());
        }
        if (!OutilMiroir.Altitude.connue())
            return Groupes.Resultat.refus("@altitude inconnue. Règle-la une fois dans l'éditeur :wired, puis recommence.");

        historiqueGrouper(true);
        boolean arrete = false;
        try {
            // 1. rafale : tout d'un coup
            int n = 0;
            for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
                OutilMiroir.Altitude.ecrire(c.getKey(), c.getValue());
                n++;
            }
            t.progres(n, voulu.size(), "Hauteurs envoyées");
            // 2. verification, puis repasse lente pour les retardataires
            for (int passe = 1; passe <= 2; passe++) {
                Salle.sommeil(900);
                List<Map.Entry<Integer, Double>> encore = new ArrayList<>();
                for (Map.Entry<Integer, Double> c : voulu.entrySet()) {
                    HFloorItem now = Salle.sol(c.getKey());
                    if (now != null && Math.abs(now.getTile().getZ() - c.getValue()) > 0.05) encore.add(c);
                }
                if (encore.isEmpty() || t.arretee()) break;
                int m = 0;
                for (Map.Entry<Integer, Double> c : encore) {
                    if (t.arretee()) { arrete = true; break; }
                    OutilMiroir.Altitude.ecrire(c.getKey(), c.getValue());
                    t.progres(++m, encore.size(), "Repasse " + passe + " : " + m + "/" + encore.size());
                    Salle.sommeil(PAUSE);
                }
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

    /** Historique (Ctrl+Z) : les envois d'une meme action en plusieurs passes forment UNE action. */
    private static void historiqueGrouper(boolean g) { Historique.grouper(g); }

    /**
     * Pivote d'un quart de tour les mobis de sol du calque (ou de la
     * selection). Lance par Groupes.pivoter (verrou, fil a part).
     *   1. la rotation voulue, en repassant tant que des mobis arrivent (un
     *      mobi du bloc libere la case d'un autre) ;
     *   2. la rotation equivalente sur le meme axe (mobis a 2 orientations) ;
     *   3. la rotation d'origine (mobis a une seule orientation : ils changent
     *      seulement de case ; sans objet pour « chaque mobi »).
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
        List<GroupeCalcul.Cible> reste = new ArrayList<>();
        int impossibles = 0;
        for (GroupeCalcul.Cible c : cibles) if (c.horsPlan) impossibles++; else reste.add(c);
        // du bas vers le haut : un mobi empile se repose sur celui du dessous
        reste.sort(Comparator.comparingDouble((GroupeCalcul.Cible c) -> c.e.z).thenComparingInt(c -> c.e.id));
        int total = cibles.size();
        boolean arrete = false;
        Set<Integer> sansTourner = new HashSet<>();      // envoyes avec leur rotation d'origine
        System.out.println("[Atelier] calques : pivot " + (horaire ? "horaire" : "inverse") + ", "
                + reste.size() + " sol(s), centre double " + (centre == null ? "-" : centre[0] + "," + centre[1]));

        historiqueGrouper(true);
        try {
            List<GroupeCalcul.Cible> aFaire = new ArrayList<>(reste);
            int fait = 0;
            for (int essai = 0; essai <= 2 && !aFaire.isEmpty() && !arrete; essai++) {
                for (int repasse = 0; repasse < (essai == 0 ? 4 : 1) && !aFaire.isEmpty(); repasse++) {
                    List<GroupeCalcul.Cible> envoyes = new ArrayList<>();
                    for (GroupeCalcul.Cible c : aFaire) {
                        if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                        int rot = GroupeCalcul.rotationEssai(c.rot, c.e.rot, essai);
                        if (rot < 0) continue;
                        // rotation d'origine : utile seulement si le mobi change de case
                        if (essai == 2 && c.x == c.e.x && c.y == c.e.y) continue;
                        if (essai == 2) sansTourner.add(c.e.id);
                        Salle.deplacerSol(c.e.id, c.x, c.y, rot);
                        envoyes.add(c);
                        if (essai == 0 && repasse == 0) fait++;
                        t.progres(Math.min(fait, total), total, (essai == 0 && repasse == 0 ? "Pivot : "
                                : "Pivot, nouvel essai : ") + envoyes.size() + " envoyé(s)...");
                        Salle.sommeil(PAUSE);
                    }
                    if (arrete || envoyes.isEmpty()) break;
                    int avant = aFaire.size();
                    aFaire = attendreArrivees(aFaire, sansTourner, 1200);
                    if (aFaire.size() == avant) break;        // plus rien n'avance : essai suivant
                }
            }
            if (arrete) Salle.sommeil(900);
        } finally {
            historiqueGrouper(false);
        }

        int tournes = 0, deplaces = 0;
        List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
        for (GroupeCalcul.Cible c : reste) {
            if (!pivote(c, sansTourner)) continue;
            HFloorItem now = Salle.sol(c.e.id);
            if (now != null && Salle.rotation(now) == c.e.rot && sansTourner.contains(c.e.id)) deplaces++;
            else tournes++;
            if (now != null && Math.abs(now.getTile().getZ() - c.z) > 0.05) aRegler.add(c);
        }
        int arrives = tournes + deplaces;
        String hauteurs = arrete ? "" : hauteurs(t, aRegler);
        // le point de rotation est garde pour le tour suivant
        if (toutLeCalque && centre != null && memeSalle(salle)) {
            List<GroupeCalcul.Element> apres = elements(ids.get(0), List.of());
            dernierPivot = new DernierPivot(salle, calqueId, centre, places(apres));
        }
        int echecs = total - arrives;
        int bloques = echecs - impossibles;
        String msg = (arrete ? "Arrêté. " : "") + tournes + "/" + total + " mobi(s) pivoté(s)"
                + (horaire ? " (sens horaire)" : " (sens inverse)")
                + (deplaces > 0 ? ", " + deplaces + " déplacé(s) sans tourner (une seule orientation)" : "")
                + (impossibles > 0 ? ", " + impossibles + " impossible(s) (hors du plan)" : "")
                + (bloques > 0 && !arrete ? ", " + bloques + " bloqué(s) (case occupée ou orientation refusée)" : "")
                + (muraux > 0 ? ", " + muraux + " mural(aux) laissé(s) tel(s) quel(s)" : "")
                + "." + hauteurs + (arrives > 0 ? " Ctrl+Z pour annuler." : "");
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, total, arrives, echecs, msg, null);
    }

    /**
     * Deplacement avec pivot (mode Deplacer : fleches + pivoter) : chaque mobi
     * de sol part directement a sa case et sa rotation finales, du bas vers
     * le haut, puis les hauteurs sont remises (@altitude). Memes essais de
     * rotation que pivoter.
     */
    static Groupes.Resultat deplacerTourne(Groupes.Tache t, String calqueId, int quarts, int dx, int dy) {
        boolean horaire = true;
        int salle = Groupes.salleCourante();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        int[] centre = GroupeCalcul.centrePivot(els);
        List<GroupeCalcul.Cible> cibles = GroupeCalcul.transformer(els, quarts, dx, dy, Salle::hauteurSol);
        if (cibles.isEmpty())
            return Groupes.Resultat.refus(els.isEmpty() ? "Aucun mobi de ce calque dans la salle."
                    : "Aucun mobi de sol à pivoter (les muraux ne pivotent pas).");
        int muraux = els.size() - cibles.size();
        List<GroupeCalcul.Cible> reste = new ArrayList<>();
        int impossibles = 0;
        for (GroupeCalcul.Cible c : cibles) if (c.horsPlan) impossibles++; else reste.add(c);
        // du bas vers le haut : un mobi empile se repose sur celui du dessous
        reste.sort(Comparator.comparingDouble((GroupeCalcul.Cible c) -> c.e.z).thenComparingInt(c -> c.e.id));
        int total = cibles.size();
        boolean arrete = false;
        Set<Integer> sansTourner = new HashSet<>();      // envoyes avec leur rotation d'origine
        System.out.println("[Atelier] calques : deplacement avec pivot (" + quarts + " quart(s)), "
                + reste.size() + " sol(s), centre double " + (centre == null ? "-" : centre[0] + "," + centre[1]));

        historiqueGrouper(true);
        try {
            List<GroupeCalcul.Cible> aFaire = new ArrayList<>(reste);
            int fait = 0;
            for (int essai = 0; essai <= 2 && !aFaire.isEmpty() && !arrete; essai++) {
                for (int repasse = 0; repasse < (essai == 0 ? 4 : 1) && !aFaire.isEmpty(); repasse++) {
                    List<GroupeCalcul.Cible> envoyes = new ArrayList<>();
                    for (GroupeCalcul.Cible c : aFaire) {
                        if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                        int rot = GroupeCalcul.rotationEssai(c.rot, c.e.rot, essai);
                        if (rot < 0) continue;
                        // rotation d'origine : utile seulement si le mobi change de case
                        if (essai == 2 && c.x == c.e.x && c.y == c.e.y) continue;
                        if (essai == 2) sansTourner.add(c.e.id);
                        Salle.deplacerSol(c.e.id, c.x, c.y, rot);
                        envoyes.add(c);
                        if (essai == 0 && repasse == 0) fait++;
                        t.progres(Math.min(fait, total), total, (essai == 0 && repasse == 0 ? "Pivot : "
                                : "Pivot, nouvel essai : ") + envoyes.size() + " envoyé(s)...");
                        Salle.sommeil(PAUSE);
                    }
                    if (arrete || envoyes.isEmpty()) break;
                    int avant = aFaire.size();
                    aFaire = attendreArrivees(aFaire, sansTourner, 1200);
                    if (aFaire.size() == avant) break;        // plus rien n'avance : essai suivant
                }
            }
            if (arrete) Salle.sommeil(900);
        } finally {
            historiqueGrouper(false);
        }

        int tournes = 0, deplaces = 0;
        List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
        for (GroupeCalcul.Cible c : reste) {
            if (!pivote(c, sansTourner)) continue;
            HFloorItem now = Salle.sol(c.e.id);
            if (now != null && Salle.rotation(now) == c.e.rot && sansTourner.contains(c.e.id)) deplaces++;
            else tournes++;
            if (now != null && Math.abs(now.getTile().getZ() - c.z) > 0.05) aRegler.add(c);
        }
        int arrives = tournes + deplaces;
        String hauteurs = arrete ? "" : hauteurs(t, aRegler);
        int echecs = total - arrives;
        int bloques = echecs - impossibles;
        String msg = (arrete ? "Arrêté. " : "") + tournes + "/" + total + " mobi(s) pivoté(s)"
                + " et déplacé(s)"
                + (deplaces > 0 ? ", " + deplaces + " déplacé(s) sans tourner (une seule orientation)" : "")
                + (impossibles > 0 ? ", " + impossibles + " impossible(s) (hors du plan)" : "")
                + (bloques > 0 && !arrete ? ", " + bloques + " bloqué(s) (case occupée ou orientation refusée)" : "")
                + (muraux > 0 ? ", " + muraux + " mural(aux) laissé(s) tel(s) quel(s)" : "")
                + "." + hauteurs + (arrives > 0 ? " Ctrl+Z pour annuler." : "");
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, total, arrives, echecs, msg, null);
    }

    /** Attend (au plus ms) que les mobis envoyes arrivent ; rend ceux qui manquent encore. */
    private static List<GroupeCalcul.Cible> attendreArrivees(List<GroupeCalcul.Cible> l, Set<Integer> sansTourner, long ms) {
        long fin = System.currentTimeMillis() + ms;
        List<GroupeCalcul.Cible> encore = l;
        while (true) {
            Salle.sommeil(150);
            List<GroupeCalcul.Cible> e = new ArrayList<>();
            for (GroupeCalcul.Cible c : encore) if (!pivote(c, sansTourner)) e.add(c);
            encore = e;
            if (encore.isEmpty() || System.currentTimeMillis() >= fin) return encore;
        }
    }

    /**
     * Arrive a sa case avec la rotation voulue ou son equivalente sur le meme
     * axe ; ou, s'il a ete envoye sans tourner, avec sa rotation d'origine.
     */
    private static boolean pivote(GroupeCalcul.Cible c, Set<Integer> sansTourner) {
        HFloorItem now = Salle.sol(c.e.id);
        if (now == null || now.getTile().getX() != c.x || now.getTile().getY() != c.y) return false;
        return GroupeCalcul.rotationAcceptee(Salle.rotation(now), c.rot, c.e.rot, sansTourner.contains(c.e.id));
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
                for (int[] k : encore) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    Salle.ramasser(k[0], k[1] == 1);
                    if (passe == 1) n++;
                    t.progres(n, liste.size(), "Ramassage" + (passe > 1 ? " (2e passe)" : "") + " : " + n + "/" + liste.size());
                    Salle.sommeil(PAUSE);
                }
                if (arrete) break;
                for (int i = 0; i < 20 && !aucunPresent(encore); i++) Salle.sommeil(150);
            }
            Salle.sommeil(300);
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

    private static boolean aucunPresent(List<int[]> l) {
        for (int[] k : l) if (present(k)) return false;
        return true;
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
     * @param dessus calque au-dessus duquel ranger le nouveau (null = en haut)
     */
    static Groupes.Resultat dupliquer(Groupes.Tache t, List<Set<Integer>> ids, String dessus,
                                      java.util.function.Function<List<GroupeCalcul.Element>, List<GroupeCalcul.Cible>> calcul,
                                      Generateur.Source source) {
        int salle = Groupes.salleCourante();
        GPresets gp = Salle.gp();
        if (gp == null) return Groupes.Resultat.refus("G-Presets pas encore prêt.");
        if (!Salle.furnidataPrete()) return Groupes.Resultat.refus("Furnidata pas encore chargée.");
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun mobi de ce calque dans la salle.");

        // 1. ce qu'on pose : altitude relative au sol le plus bas sous le calque
        int solMin = Integer.MAX_VALUE;
        for (GroupeCalcul.Element e : els) if (!e.mural && e.solDessous >= 0) solMin = Math.min(solMin, e.solDessous);
        if (solMin == Integer.MAX_VALUE) solMin = 0;
        List<Generateur.Mobi> mobis = new ArrayList<>();
        List<MurPose> murs = new ArrayList<>();
        Map<Integer, Integer> attendusSols = new HashMap<>(), attendusMurs = new HashMap<>();   // type -> nombre
        int wired = 0, inconnus = 0, hors = 0;
        List<GroupeCalcul.Cible> cibles = calcul.apply(els);
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
            mobis.add(new Generateur.Mobi(cls, Generateur.etatDe(it), c.x, c.y,
                    Math.max(0, c.e.z - solMin), c.rot));
            attendusSols.merge(it.getTypeId(), 1, Integer::sum);
        }
        int voulus = mobis.size() + murs.size();
        String ignores = (wired > 0 ? wired + " wired ignoré(s) (sans leur réglage). " : "")
                + (inconnus > 0 ? inconnus + " mobi(s) de classe inconnue ignoré(s). " : "")
                + (hors > 0 ? hors + " mobi(s) qui tomberaient hors du plan ignoré(s). " : "")
                + (muraux > 0 ? muraux + " mobi(s) mural(aux) laissé(s) de côté. " : "");
        if (voulus == 0) return Groupes.Resultat.refus("Rien à dupliquer. " + ignores);

        // 2. la salle avant
        Set<Integer> avantS = Groupes.idsSols(), avantM = Groupes.idsMurs();

        // 3. la pose (appart temporaire + dalle magique)
        GPresetImporter imp = gp.getImporter();
        if (imp == null) return Groupes.Resultat.refus("Importeur de G-Presets introuvable.");
        Consumer<String> dire = t::dire;
        boolean lance;
        try { lance = poser(gp, imp, mobis, murs, source, dire); }
        catch (Throwable e) { return Groupes.Resultat.refus("Pose impossible : " + e); }
        if (!lance) return new Groupes.Resultat(false, false, voulus, 0, voulus, "La pose n'a pas démarré. " + ignores, null);

        // 4. attendre la fin de G-Presets, en comptant les nouveaux mobis
        long fin = System.currentTimeMillis() + 30 * 60_000L;
        boolean arrete = false, sortie = false;
        while (System.currentTimeMillis() < fin) {
            Salle.sommeil(500);
            if (!memeSalle(salle)) { sortie = true; break; }
            if (t.arretee() && !arrete) { arrete = true; abandonner(imp, gp); }
            GPresetImporter.BuildingImportState st;
            try { st = imp.getState(); } catch (Throwable e) { st = GPresetImporter.BuildingImportState.NONE; }
            int n = nouveaux(avantS, attendusSols, false).size() + nouveaux(avantM, attendusMurs, true).size();
            t.progres(Math.min(n, voulus), voulus, "G-Presets pose la copie : " + n + "/" + voulus
                    + (st == GPresetImporter.BuildingImportState.AWAITING_UNOCCUPIED_SPACE ? " — clique une case LIBRE dans le jeu pour la dalle magique" : ""));
            if (st == GPresetImporter.BuildingImportState.NONE) break;
        }
        if (sortie) return new Groupes.Resultat(false, true, voulus, 0, voulus, "Tu as quitté la salle pendant la pose.", null);
        Salle.sommeil(3500);           // la dalle de l'Atelier est ramassee ~1,5 s apres la fin

        // 5. les nouveaux mobis -> nouveau calque
        List<Integer> nS = nouveaux(avantS, attendusSols, false), nM = nouveaux(avantM, attendusMurs, true);
        int obtenus = nS.size() + nM.size();
        String nouveau = obtenus > 0 ? Groupes.creer(null, nS, nM, dessus) : null;
        int echecs = Math.max(0, voulus - obtenus);
        String msg = (arrete ? "Arrêté. " : "") + obtenus + "/" + voulus + " mobi(s) posé(s)"
                + (nouveau != null ? " — nouveau calque créé" : "")
                + (echecs > 0 && !arrete ? ", " + echecs + " manquant(s) (inventaire / BC ? case refusée ?)" : "")
                + ". " + ignores;
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, voulus, obtenus, echecs, msg.trim(), nouveau);
    }

    // ------------------------------------------------- dupliquer (options)

    /**
     * Dupliquer avec les choix de la fenetre : quoi copier (sols, murs, wired)
     * et d'ou viennent les mobis. La copie est posee A LA MEME PLACE (dalle
     * magique de G-Presets : hauteurs exactes). Avec les wired, sols et wired
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
            msg = obtenus + "/" + voulus + " mobi(s) posé(s), wired avec leur réglage"
                    + (nouveau != null ? " — nouveau calque créé" : "") + ".";
        } else {
            Groupes.Resultat r = dupliquer(t, List.of(s, m), dessus,
                    els -> GroupeCalcul.cibles(els, 0, 0, Salle::hauteurSol), source);
            if (r.nouveauCalque == null) return r;
            nouveau = r.nouveauCalque; msg = r.message; voulus = r.voulus; obtenus = r.reussis;
        }
        if (nouveau == null || t.arretee() || !memeSalle(salle))
            return new Groupes.Resultat(false, t.arretee(), voulus, obtenus, voulus - obtenus, msg, nouveau);

        // dalles magiques sous la copie (si elle n'en a pas)
        Set<Integer> types = Generateur.Dalle.typesDalles();
        Set<Long> cases = new HashSet<>();
        boolean aDesDalles = false;
        for (int id : Groupes.mobis(nouveau).get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) continue;
            if (types.contains(it.getTypeId())) { aDesDalles = true; break; }
            int[] e = Salle.emprise(it);
            for (int i = 0; i < e[0]; i++)
                for (int j = 0; j < e[1]; j++) cases.add(OutilHauteur.cle(it.getTile().getX() + i, it.getTile().getY() + j));
        }
        if (!aDesDalles && !cases.isEmpty()) {
            t.dire("Dalles magiques sous la copie…");
            List<Integer> dalles = OutilHauteur.couvrirCases(cases, t::dire, t::arretee);
            if (!dalles.isEmpty()) {
                Groupes.ajouterSansVerrou(nouveau, dalles, List.of());
                msg += " " + dalles.size() + " dalle(s) magique(s) posée(s) sous la copie : elles la suivront au déplacement.";
            }
        }
        return new Groupes.Resultat(obtenus == voulus, false, voulus, obtenus, voulus - obtenus, msg, nouveau);
    }

    /** Copie des muraux donnes a leur place (G-Presets). Ids des nouveaux muraux. */
    private static List<Integer> poserMursSurPlace(Groupes.Tache t, Set<Integer> murs, Generateur.Source source) {
        GPresets gp = Salle.gp();
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
        GPresetImporter imp = gp.getImporter();
        try { if (imp == null || !poser(gp, imp, List.of(), l, source, t::dire)) return List.of(); }
        catch (Throwable e) { t.dire("Muraux : " + e); return List.of(); }
        long fin = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < fin) {
            Salle.sommeil(500);
            GPresetImporter.BuildingImportState st;
            try { st = imp.getState(); } catch (Throwable e) { st = GPresetImporter.BuildingImportState.NONE; }
            if (st == GPresetImporter.BuildingImportState.NONE) break;
        }
        Salle.sommeil(1500);
        return nouveaux(avant, attendus, true);
    }

    // ------------------------------------------ deplacer avec dalles magiques

    /**
     * Deplacer (et pivoter) un calque qui a ses dalles magiques : les dalles
     * partent d'abord a leur place d'arrivee ; puis, du bas vers le haut, les
     * dalles sous chaque mobi sont reglees a SA hauteur et il y est pose : il
     * arrive exactement a cette hauteur (changer la hauteur d'une dalle ne
     * bouge pas les mobis deja dessus). Sans dalle dans le calque : comme
     * avant (deplacer, ou deplacerTourne avec @altitude).
     */
    static Groupes.Resultat deplacerAvecDalles(Groupes.Tache t, String calqueId, int quarts, int dx, int dy) {
        int q = ((quarts % 4) + 4) % 4;
        if (dx == 0 && dy == 0 && q == 0) return Groupes.Resultat.refus("Rien à faire : ni déplacement ni pivot.");
        int salle = Groupes.salleCourante();
        GPresets gp = Salle.gp();
        List<Set<Integer>> ids = Groupes.mobis(calqueId);
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        Set<Integer> types = Generateur.Dalle.typesDalles();
        Set<Integer> dalles = new HashSet<>();
        for (GroupeCalcul.Element e : els) {
            if (e.mural) continue;
            HFloorItem it = Salle.sol(e.id);
            if (it != null && types.contains(it.getTypeId())) dalles.add(e.id);
        }
        if (dalles.isEmpty() || gp == null)
            return q == 0 ? deplacer(t, calqueId, dx, dy) : deplacerTourne(t, calqueId, q, dx, dy);

        List<GroupeCalcul.Cible> cibles = GroupeCalcul.transformer(els, q, dx, dy, Salle::hauteurSol);
        List<GroupeCalcul.Cible> cDalles = new ArrayList<>(), cMobis = new ArrayList<>(), cMurs = new ArrayList<>();
        int hors = 0;
        for (GroupeCalcul.Cible c : cibles) {
            if (c.e.mural) { if (c.position != null) cMurs.add(c); else hors++; continue; }
            if (c.horsPlan) { hors++; continue; }
            (dalles.contains(c.e.id) ? cDalles : cMobis).add(c);
        }
        int total = cDalles.size() + cMobis.size() + cMurs.size();
        int fait = 0;
        boolean arrete = false;
        historiqueGrouper(true);
        int malPlaces = 0, hauteursFausses = 0;
        try {
            // 1. les dalles d'abord
            for (int passe = 0; passe < 2 && !arrete; passe++) {
                for (GroupeCalcul.Cible c : cDalles) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    if (place(c)) continue;
                    Salle.deplacerSol(c.e.id, c.x, c.y, c.rot);
                    if (passe == 0) t.progres(++fait, total, "Dalles magiques : " + fait + "/" + cDalles.size());
                    Salle.sommeil(PAUSE);
                }
                Salle.sommeil(900);
            }
            // 2. les mobis, du bas vers le haut, chacun a sa hauteur
            cMobis.sort(Comparator.comparingDouble((GroupeCalcul.Cible c) -> c.z).thenComparingInt(c -> c.e.id));
            Map<Integer, Integer> hauteurDalle = new HashMap<>();
            int n = 0;
            for (GroupeCalcul.Cible c : cMobis) {
                if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                int lx = q % 2 == 1 ? c.e.ey : c.e.ex, ly = q % 2 == 1 ? c.e.ex : c.e.ey;
                int valeur = (int) Math.round(Math.max(0, c.z) * 100);
                for (GroupeCalcul.Cible d : cDalles) {
                    int dlx = q % 2 == 1 ? d.e.ey : d.e.ex, dly = q % 2 == 1 ? d.e.ex : d.e.ey;
                    boolean dessous = d.x <= c.x + lx - 1 && d.x + dlx - 1 >= c.x && d.y <= c.y + ly - 1 && d.y + dly - 1 >= c.y;
                    if (!dessous || Objects.equals(hauteurDalle.get(d.e.id), valeur)) continue;
                    gp.sendToServer(new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, d.e.id, valeur));
                    hauteurDalle.put(d.e.id, valeur);
                    Salle.sommeil(250);           // le serveur ignore les reglages trop rapproches
                }
                boolean ok = false;
                for (int essai = 0; essai <= 2 && !ok; essai++) {
                    int rot = GroupeCalcul.rotationEssai(c.rot, c.e.rot, essai);
                    if (rot < 0) continue;
                    Salle.deplacerSol(c.e.id, c.x, c.y, rot);
                    for (int i = 0; i < 8 && !ok; i++) { Salle.sommeil(150); ok = place(c) || placeTourne(c, rot); }
                }
                if (!ok) malPlaces++;
                else {
                    HFloorItem now = Salle.sol(c.e.id);
                    if (now != null && Math.abs(now.getTile().getZ() - c.z) > 0.03) hauteursFausses++;
                }
                t.progres(++fait, total, "Mobis à leur hauteur : " + (++n) + "/" + cMobis.size());
            }
            // 3. les muraux (sans pivot seulement : ils ne tournent pas)
            for (GroupeCalcul.Cible c : cMurs) {
                if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                Salle.deplacerMur(c.e.id, SelectionMur.normaliser(c.position));
                t.progres(++fait, total, "Muraux…");
                Salle.sommeil(PAUSE);
            }
        } finally {
            historiqueGrouper(false);
        }
        String msg = (arrete ? "Arrêté. " : "") + (cMobis.size() - malPlaces) + "/" + cMobis.size()
                + " mobi(s) posé(s) à leur hauteur sur " + cDalles.size() + " dalle(s) magique(s)"
                + (q != 0 ? ", pivotés" : "") + "."
                + (malPlaces > 0 ? " " + malPlaces + " bloqué(s) (case occupée ou orientation refusée)." : "")
                + (hauteursFausses > 0 ? " " + hauteursFausses + " à une hauteur différente." : "")
                + (hors > 0 ? " " + hors + " hors du plan, laissé(s) sur place." : "")
                + (q != 0 && !ids.get(1).isEmpty() ? " Les muraux ne pivotent pas." : "")
                + " Ctrl+Z pour annuler.";
        int echecs = malPlaces + hors;
        return new Groupes.Resultat(echecs == 0 && !arrete, arrete, total, total - echecs, echecs, msg, null);
    }

    /** Le mobi de sol est-il a sa case d'arrivee (rotation voulue ou equivalente) ? */
    private static boolean place(GroupeCalcul.Cible c) {
        HFloorItem now = Salle.sol(c.e.id);
        return now != null && now.getTile().getX() == c.x && now.getTile().getY() == c.y
                && GroupeCalcul.rotationAcceptee(Salle.rotation(now), c.rot, c.e.rot, false);
    }

    /** Arrive avec la rotation envoyee (repli d'un mobi a une seule orientation). */
    private static boolean placeTourne(GroupeCalcul.Cible c, int rot) {
        HFloorItem now = Salle.sol(c.e.id);
        return now != null && now.getTile().getX() == c.x && now.getTile().getY() == c.y && Salle.rotation(now) == (rot & 7);
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

    /** « :abort » donne directement a l'importeur (rien ne part au serveur), a defaut par le chat. */
    private static void abandonner(GPresetImporter imp, GPresets gp) {
        try {
            java.lang.reflect.Method m = GPresetImporter.class.getDeclaredMethod("onChat", HMessage.class);
            m.setAccessible(true);
            m.invoke(imp, new HMessage(new HPacket(4000, ":abort", 0, -1), HMessage.Direction.TOSERVER, -1));
        } catch (Throwable t) {
            gp.sendToServer(new HPacket("Chat", HMessage.Direction.TOSERVER, ":abort", 0, -1));
        }
    }

    /**
     * Comme Generateur.poser, mais avec des murs : sols et murs aux positions
     * ABSOLUES voulues. Le preset est ramene a l'origine (min x, min y) et la
     * racine mise a ce coin : G-Presets pose sols et murs a racine + (x, y)
     * (placeWallItems : WallPosition.x + rootLocation.x).
     */
    static boolean poser(GPresets gp, GPresetImporter imp, List<Generateur.Mobi> mobis, List<MurPose> murs,
                         Generateur.Source source, Consumer<String> dire) throws Exception {
        if (!Salle.dansUneSalle()) { dire.accept("Tu n'es pas dans une salle."); return false; }
        try {
            if (imp.getState() != GPresetImporter.BuildingImportState.NONE) {
                dire.accept("G-Presets est déjà en train d'importer — termine ou tape :abort dans le jeu.");
                return false;
            }
        } catch (Throwable ignored) { }
        furnidata.FurniDataTools fd = gp.getFurniDataTools();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Generateur.Mobi m : mobis) { minX = Math.min(minX, m.x); minY = Math.min(minY, m.y); }
        List<utils.WallPosition> positions = new ArrayList<>();
        for (MurPose w : murs) {
            utils.WallPosition p = new utils.WallPosition(w.position);
            positions.add(p);
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY());
        }
        HPoint racine = new HPoint(minX, minY);

        List<PresetFurni> furni = new ArrayList<>();
        List<Generateur.Mobi> relatifs = new ArrayList<>();
        int id = 1;
        for (Generateur.Mobi m : mobis) {
            if (fd.getFloorTypeId(m.classe) == null) { dire.accept("« " + m.classe + " » inconnu de la furnidata : pose annulée."); return false; }
            PresetFurni p = new PresetFurni(id++, m.classe,
                    new HPoint(m.x - minX, m.y - minY, Math.max(0, Generateur.arrondi(m.z))), m.rot & 7, m.etat);
            p.setFurniName(nomSol(fd, m.classe));
            furni.add(p);
            relatifs.add(new Generateur.Mobi(m.classe, m.etat, m.x - minX, m.y - minY, m.z, m.rot));
        }
        List<PresetWallFurni> wall = new ArrayList<>();
        for (int i = 0; i < murs.size(); i++) {
            MurPose w = murs.get(i);
            if (fd.getWallTypeId(w.classe) == null) { dire.accept("« " + w.classe + " » (mural) inconnu de la furnidata : pose annulée."); return false; }
            utils.WallPosition p = positions.get(i);
            utils.WallPosition rel = new utils.WallPosition(p.getX() - minX, p.getY() - minY,
                    p.getOffsetX(), p.getOffsetY(), p.getDirection(), p.getAltitude());
            PresetWallFurni pw = new PresetWallFurni(id++, w.classe, rel, w.etat == null ? "" : w.etat);
            try {
                furnidata.details.WallItemDetails d = fd.getWallItemDetails(w.classe);
                pw.setFurniName(d != null && d.name != null && !d.name.isBlank() ? d.name : w.classe);
            } catch (Throwable e) { pw.setFurniName(w.classe); }
            wall.add(pw);
        }

        PresetWireds wi = new PresetWireds(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        PresetConfig cfg = new PresetConfig(furni, wall, wi, new ArrayList<>(), new ArrayList<>());
        cfg.setSrcAnchorFloorHeight(0.0);
        try {   // meme salle : les murs ne sont pas recales
            game.FloorState fs = Salle.etat();
            if (fs != null && fs.getRawFloorplan() != null)
                cfg.setRoomLayout(new extension.tools.presetconfig.RoomLayoutInfo(fs.getRoomModelName(),
                        fs.getFloorplanWidth(), fs.getFloorplanHeight(), fs.getFloorScale(),
                        fs.getFloorWallHeight(), fs.getRawFloorplan()));
        } catch (Throwable ignored) { }
        String json = cfg.toJsonObject().toString(2);
        PresetConfig relu = new PresetConfig(new org.json.JSONObject(json));

        String fichier = "_atelier_calque";
        File dossier = OngletApparts.dossierApparts();
        if (!dossier.exists()) dossier.mkdirs();
        Files.write(new File(dossier, fichier + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));

        String entete = (furni.size() + wall.size()) + " mobi(s) envoyés à G-Presets. ";
        if (furni.isEmpty()) {
            // murs seuls : G-Presets les pose des « :ip x,y », sans dalle magique
            boolean ok = Generateur.importer(gp, imp, relu, fichier, source, racine, s -> { }, entete, null);
            if (ok) dire.accept(entete + "Pose des murs en cours (:abort dans le jeu pour arrêter).");
            else dire.accept("G-Presets n'a pas lancé la pose (regarde son message dans le jeu).");
            return ok;
        }
        List<int[]> trace = Generateur.Dalle.trace(relatifs, 0, 0, racine);
        int[] depart = new int[]{racine.getX() + relatifs.get(0).x, racine.getY() + relatifs.get(0).y};
        Generateur.Dalle.Pret dalle = Generateur.Dalle.preparer(gp, relatifs, trace, depart, dire);
        if (dalle == null) { Generateur.Dalle.finIgnorer(); return false; }
        boolean ok = Generateur.importer(gp, imp, relu, fichier, source, racine, dire, entete, dalle.ou);
        if (ok) Generateur.Dalle.apresImport(imp, dalle.poseeParAtelier, dire);
        else if (dalle.poseeParAtelier > 0)
            Generateur.Dalle.ramasser(dalle.poseeParAtelier, dire, "La pose n'a pas démarré : j'ai ramassé la dalle magique.");
        else Generateur.Dalle.finIgnorer();
        return ok;
    }

    private static String nomSol(furnidata.FurniDataTools fd, String classe) {
        try {
            furnidata.details.FloorItemDetails d = fd.getFloorItemDetails(classe);
            if (d != null && d.name != null && !d.name.isBlank()) return d.name;
        } catch (Throwable ignored) { }
        return classe;
    }
}

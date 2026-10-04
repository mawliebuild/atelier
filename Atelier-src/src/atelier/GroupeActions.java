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

        // pose hybride : sans @altitude, les sols en hauteur vont directement a la dalle
        List<GroupeCalcul.Cible> directs = new ArrayList<>();
        boolean altitude = trierAltitude(t, cibles.values(), directs);
        for (GroupeCalcul.Cible c : directs) reste.remove(c.e);

        historiqueGrouper(true);           // rafale + reprise a la dalle : une seule action (Ctrl+Z)
        List<GroupeCalcul.Cible> repris;
        PoseHybride.Bilan rb = null;
        try {
            // rafale suivie, deux passes : la seconde reprend ceux qu'un voisin genait
            // encore. Pendant les envois, chaque sol arrive recoit tout de suite son
            // altitude (@altitude connue) ; on n'attend que les derniers en vol.
            Set<Integer> hautEnvoyee = new HashSet<>();
            for (int passe = 1; passe <= 2 && !reste.isEmpty(); passe++) {
                int n = 0;
                List<GroupeCalcul.Cible> enVol = new ArrayList<>();
                for (GroupeCalcul.Element e : reste) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    GroupeCalcul.Cible c = cibles.get((e.mural ? "m" : "s") + e.id);
                    Salle.espacer();
                    if (e.mural) Salle.deplacerMur(e.id, SelectionMur.normaliser(c.position));
                    else Salle.deplacerSol(e.id, c.x, c.y, e.rot);
                    enVol.add(c);
                    n++;
                    t.progres(passe == 1 ? ++fait : fait, total, "Pose rapide" + (passe > 1 ? " (2e passe)" : "") + " : " + n + "/" + reste.size());
                    hauteursArrivees(enVol, k -> arrive(k.e, k), hautEnvoyee);
                }
                final List<GroupeCalcul.Element> l = reste;
                PoseDirecte.suivre(() -> nonArrives(l, cibles).size(), arrete ? 400 : 700, 1500);
                hauteursArrivees(enVol, k -> arrive(k.e, k), hautEnvoyee);
                if (arrete) break;
                reste = nonArrives(reste, cibles);
            }
            if (!hautEnvoyee.isEmpty()) attendreHauteurs(new ArrayList<>(cibles.values()), hautEnvoyee);

            // verification + hauteurs (@altitude), puis reprise a la dalle des refuses
            List<GroupeCalcul.Cible> valides = new ArrayList<>();
            for (GroupeCalcul.Element e : els) {
                GroupeCalcul.Cible c = cibles.get((e.mural ? "m" : "s") + e.id);
                if (c == null || c.horsPlan || (e.mural && c.position == null)) continue;
                valides.add(c);
            }
            if (!arrete && altitude) {
                List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
                for (GroupeCalcul.Cible c : valides)
                    if (!c.e.mural && arrive(c.e, c) && !directs.contains(c) && hauteurFausse(c)) aRegler.add(c);
                hauteurs(t, aRegler);
            }
            repris = arrete ? List.of() : aReprendre(valides, c -> arrive(c.e, c));
            if (!repris.isEmpty()) rb = reprendreCibles(t, salle, repris);
        } finally {
            historiqueGrouper(false);
        }
        arrete = arrete || t.arretee() || !memeSalle(salle);
        return bilanDeplacement("déplacé", total, impossibles, repris, rb, altitude, arrete,
                c -> arrive(c.e, c), cibles.values(), "");
    }

    // ------------------------------------------------------------ pose hybride

    private static boolean estWired(int id) {
        HFloorItem it = Salle.sol(id);
        return it != null && Wired.estWired(Salle.classe(it.getTypeId(), false));
    }

    /** Le sol n'est pas (encore) a son altitude voulue. */
    private static boolean hauteurFausse(GroupeCalcul.Cible c) {
        HFloorItem now = Salle.sol(c.e.id);
        return now != null && PoseHybride.trier(true, true, now.getTile().getZ(), c.z) == PoseHybride.Issue.HAUTEUR;
    }

    /**
     * Pose hybride, avant la rafale : s'il y a des sols a poser en hauteur et
     * que @altitude n'est pas disponible, ceux-la (hors wired : la dalle ne
     * garde pas leur reglage) vont dans « directs » : ils passeront directement
     * par la dalle. @return @altitude disponible
     */
    private static boolean trierAltitude(Groupes.Tache t, Collection<GroupeCalcul.Cible> cibles, List<GroupeCalcul.Cible> directs) {
        List<GroupeCalcul.Cible> enHauteur = new ArrayList<>();
        for (GroupeCalcul.Cible c : cibles) {
            if (c == null || c.e.mural || c.horsPlan) continue;
            if (!PoseHybride.parRafale(c.z, Salle.hauteurSol(c.x, c.y), false)) enHauteur.add(c);
        }
        if (enHauteur.isEmpty()) return true;
        if (PoseHybride.altitudeDisponible(t::dire)) return true;
        for (GroupeCalcul.Cible c : enHauteur) if (!estWired(c.e.id)) directs.add(c);
        return false;
    }

    /**
     * Apres la rafale : ceux qui ne sont pas arrives (refuses) ou qui sont a
     * une mauvaise hauteur, et seulement eux. Les wired restent (la dalle
     * perdrait leur reglage) : ils comptent comme bloques.
     */
    private static List<GroupeCalcul.Cible> aReprendre(List<GroupeCalcul.Cible> l,
                                                      java.util.function.Predicate<GroupeCalcul.Cible> arrive) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) {
            if (c.e.mural ? Salle.mur(c.e.id) == null : Salle.sol(c.e.id) == null) continue;     // disparu
            if (!c.e.mural && estWired(c.e.id)) continue;
            HFloorItem now = c.e.mural ? null : Salle.sol(c.e.id);
            PoseHybride.Issue i = PoseHybride.trier(true, arrive.test(c), now == null ? c.z : now.getTile().getZ(), c.z);
            if (i != PoseHybride.Issue.POSE) r.add(c);
        }
        return r;
    }

    /**
     * Reprise avec la dalle d'un deplacement : ces mobis sont ramasses puis
     * reposes a leur arrivee par le moteur de pose ; leurs nouveaux ids
     * restent dans leur calque (Groupes.remplacerIds).
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

    /**
     * Le bilan unique d'un deplacement / pivot hybride, verifie dans la salle.
     * @param arrive  « arrive a sa place » pour un mobi qui n'a pas ete repris
     */
    private static Groupes.Resultat bilanDeplacement(String participe, int total, int impossibles,
                                                     List<GroupeCalcul.Cible> repris, PoseHybride.Bilan rb,
                                                     boolean altitude, boolean arrete,
                                                     java.util.function.Predicate<GroupeCalcul.Cible> arrive,
                                                     Collection<GroupeCalcul.Cible> toutes, String suite) {
        Set<GroupeCalcul.Cible> parDalle = new HashSet<>(repris);
        int reussis = 0, hauteurs = 0, wiredBloques = 0;
        for (GroupeCalcul.Cible c : toutes) {
            if (c == null || c.horsPlan || (c.e.mural && c.position == null) || parDalle.contains(c)) continue;
            if (!arrive.test(c)) { if (!c.e.mural && estWired(c.e.id)) wiredBloques++; continue; }
            reussis++;
            if (!c.e.mural && hauteurFausse(c)) hauteurs++;
        }
        int dalle = rb == null ? 0 : rb.obtenus();
        if (rb != null) hauteurs += rb.hauteursFausses;
        reussis += dalle;
        int voulus = total - impossibles;
        String msg = PoseHybride.bilan(participe, voulus, reussis, dalle, hauteurs, arrete)
                + (impossibles > 0 ? " " + impossibles + " impossible(s) (hors du plan ou position murale illisible)." : "")
                + (wiredBloques > 0 ? " " + wiredBloques + " wired bloqué(s) : la dalle ne garde pas leur réglage." : "")
                + (rb != null && rb.raison != null && !arrete ? " Reprise à la dalle impossible : " + rb.raison + "." : "")
                + (!altitude ? PoseHybride.SANS_ALTITUDE : "")
                + suite
                + (reussis > 0 ? " " + WindowsClavier.texte("Cmd+Z pour annuler.") : "");
        int echecs = total - reussis;
        return new Groupes.Resultat(echecs == 0 && hauteurs == 0 && !arrete, arrete, total, reussis, echecs,
                Ui.accorder(msg.trim()), null);
    }

    private static List<GroupeCalcul.Element> nonArrives(List<GroupeCalcul.Element> l, Map<String, GroupeCalcul.Cible> cibles) {
        List<GroupeCalcul.Element> r = new ArrayList<>();
        for (GroupeCalcul.Element e : l) if (!arrive(e, cibles.get((e.mural ? "m" : "s") + e.id))) r.add(e);
        return r;
    }

    /**
     * Rafale suivie : les sols de « enVol » deja arrives (selon « arrive »)
     * en sortent, et recoivent tout de suite leur altitude s'ils ne sont pas a
     * la bonne (une fois par mobi, @altitude deja connue seulement : sinon la
     * verification de la fin s'en charge).
     */
    private static void hauteursArrivees(List<GroupeCalcul.Cible> enVol,
                                         java.util.function.Predicate<GroupeCalcul.Cible> arrive, Set<Integer> envoyees) {
        boolean connue = OutilMiroir.Altitude.connue();
        for (Iterator<GroupeCalcul.Cible> i = enVol.iterator(); i.hasNext(); ) {
            GroupeCalcul.Cible c = i.next();
            if (c.e.mural) { i.remove(); continue; }
            if (!arrive.test(c)) continue;
            i.remove();
            if (!connue || !OutilMiroir.Altitude.connue()) continue;   // inconnue ou fausse : la fin s'en charge
            HFloorItem now = Salle.sol(c.e.id);
            if (now == null || Math.abs(now.getTile().getZ() - c.z) <= 0.05 || !envoyees.add(c.e.id)) continue;
            Salle.espacer();
            OutilMiroir.Altitude.ecrireVerifiee(c.e.id, c.z);   // le premier verifie la variable
            Salle.envoiFait();
        }
    }

    /** Laisse arriver les dernieres altitudes envoyees en route (suivi court). */
    private static void attendreHauteurs(List<GroupeCalcul.Cible> cibles, Set<Integer> envoyees) {
        List<GroupeCalcul.Cible> l = new ArrayList<>();
        for (GroupeCalcul.Cible c : cibles) if (c != null && envoyees.contains(c.e.id)) l.add(c);
        PoseDirecte.suivre(() -> hauteursFausses(l).size(), 400, 900);
    }

    /** Les sols pas (encore) a leur altitude voulue. */
    private static List<GroupeCalcul.Cible> hauteursFausses(List<GroupeCalcul.Cible> l) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) {
            HFloorItem now = Salle.sol(c.e.id);
            if (now == null || Math.abs(now.getTile().getZ() - c.z) > 0.05) r.add(c);
        }
        return r;
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
        // le premier mobi verifie la variable retenue (ou la retrouve) avant la rafale
        if (!OutilMiroir.Altitude.confirmee()) {
            if (!OutilMiroir.Altitude.connue()) t.dire("Recherche de @altitude...");
            GroupeCalcul.Cible k = aRegler.get(0);
            Salle.espacer();
            OutilMiroir.Altitude.mettre(k.e.id, k.z);
            Salle.envoiFait();
            aRegler = hauteursFausses(aRegler);
            if (aRegler.isEmpty()) return " Hauteurs remises.";
        }
        if (!OutilMiroir.Altitude.connue())
            return " ⚠ " + aRegler.size() + " mobi(s) ont changé de hauteur (posés sur le dessus de la pile) : "
                    + "@altitude inconnue. Règle-la une fois dans l'éditeur :wired, puis refais le déplacement.";
        // rafale espacee, puis suivi : on renvoie seulement celles qui manquent
        for (int passe = 1; passe <= 2 && !aRegler.isEmpty(); passe++) {
            int n = 0;
            for (GroupeCalcul.Cible c : aRegler) {
                if (t.arretee()) break;
                Salle.espacer();
                OutilMiroir.Altitude.ecrire(c.e.id, c.z);
                t.progres(++n, aRegler.size(), "Hauteurs, passe " + passe + " : " + n + "/" + aRegler.size());
            }
            final List<GroupeCalcul.Cible> l = aRegler;
            PoseDirecte.suivre(() -> hauteursFausses(l).size(), 500, 1200);
            aRegler = hauteursFausses(aRegler);
            if (t.arretee()) break;
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
        Journal.debug("calques : pivot " + (horaire ? "horaire" : "inverse") + ", "
                + reste.size() + " sol(s), centre double " + (centre == null ? "-" : centre[0] + "," + centre[1]));

        // pose hybride : sans @altitude, les sols en hauteur vont directement a la dalle
        List<GroupeCalcul.Cible> directs = new ArrayList<>();
        boolean altitude = trierAltitude(t, reste, directs);
        reste.removeAll(directs);
        Set<Integer> hautEnvoyee = new HashSet<>();
        List<GroupeCalcul.Cible> repris = List.of();
        PoseHybride.Bilan rb = null;
        historiqueGrouper(true);           // rafale + reprise a la dalle : une seule action (Ctrl+Z)
        try {
            arrete = rafalePivot(t, salle, reste, sansTourner, total, hautEnvoyee);
            if (!hautEnvoyee.isEmpty()) attendreHauteurs(reste, hautEnvoyee);
            if (!arrete && altitude) {
                List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
                for (GroupeCalcul.Cible c : reste) if (pivote(c, sansTourner) && hauteurFausse(c)) aRegler.add(c);
                hauteurs(t, aRegler);
            }
            List<GroupeCalcul.Cible> tous = new ArrayList<>(reste);
            tous.addAll(directs);
            repris = arrete ? List.of() : aReprendre(tous, c -> pivote(c, sansTourner));
            if (!repris.isEmpty()) rb = reprendreCibles(t, salle, repris);
        } finally {
            historiqueGrouper(false);
        }
        int deplaces = 0;
        for (GroupeCalcul.Cible c : reste) {
            if (repris.contains(c) || !pivote(c, sansTourner)) continue;
            HFloorItem now = Salle.sol(c.e.id);
            if (now != null && Salle.rotation(now) == c.e.rot && sansTourner.contains(c.e.id)) deplaces++;
        }
        String suite = (deplaces > 0 ? " " + deplaces + " déplacé(s) sans tourner (une seule orientation)." : "")
                + (muraux > 0 ? " " + muraux + " mural(aux) laissé(s) tel(s) quel(s) : ils ne pivotent pas." : "");
        // le point de rotation est garde pour le tour suivant
        if (toutLeCalque && centre != null && memeSalle(salle)) {
            List<GroupeCalcul.Element> apres = elements(Groupes.mobis(calqueId).get(0), List.of());
            dernierPivot = new DernierPivot(salle, calqueId, centre, places(apres));
        }
        arrete = arrete || t.arretee() || !memeSalle(salle);
        return bilanDeplacement("pivoté", total, impossibles, repris, rb, altitude, arrete,
                c -> pivote(c, sansTourner), cibles, (horaire ? " Sens horaire." : " Sens inverse.") + suite);
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
        Journal.debug("calques : deplacement avec pivot (" + quarts + " quart(s)), "
                + reste.size() + " sol(s), centre double " + (centre == null ? "-" : centre[0] + "," + centre[1]));

        // pose hybride : sans @altitude, les sols en hauteur vont directement a la dalle
        List<GroupeCalcul.Cible> directs = new ArrayList<>();
        boolean altitude = trierAltitude(t, reste, directs);
        reste.removeAll(directs);
        Set<Integer> hautEnvoyee = new HashSet<>();
        List<GroupeCalcul.Cible> repris = List.of();
        PoseHybride.Bilan rb = null;
        historiqueGrouper(true);           // rafale + reprise a la dalle : une seule action (Ctrl+Z)
        try {
            arrete = rafalePivot(t, salle, reste, sansTourner, total, hautEnvoyee);
            if (!hautEnvoyee.isEmpty()) attendreHauteurs(reste, hautEnvoyee);
            if (!arrete && altitude) {
                List<GroupeCalcul.Cible> aRegler = new ArrayList<>();
                for (GroupeCalcul.Cible c : reste) if (pivote(c, sansTourner) && hauteurFausse(c)) aRegler.add(c);
                hauteurs(t, aRegler);
            }
            List<GroupeCalcul.Cible> tous = new ArrayList<>(reste);
            tous.addAll(directs);
            repris = arrete ? List.of() : aReprendre(tous, c -> pivote(c, sansTourner));
            if (!repris.isEmpty()) rb = reprendreCibles(t, salle, repris);
        } finally {
            historiqueGrouper(false);
        }
        int deplaces = 0;
        for (GroupeCalcul.Cible c : reste) {
            if (repris.contains(c) || !pivote(c, sansTourner)) continue;
            HFloorItem now = Salle.sol(c.e.id);
            if (now != null && Salle.rotation(now) == c.e.rot && sansTourner.contains(c.e.id)) deplaces++;
        }
        String suite = (deplaces > 0 ? " " + deplaces + " déplacé(s) sans tourner (une seule orientation)." : "")
                + (muraux > 0 ? " " + muraux + " mural(aux) laissé(s) tel(s) quel(s) : ils ne pivotent pas." : "");
        arrete = arrete || t.arretee() || !memeSalle(salle);
        return bilanDeplacement("pivoté", total, impossibles, repris, rb, altitude, arrete,
                c -> pivote(c, sansTourner), cibles, suite);
    }

    /**
     * Rafale suivie du pivot (pivoter, deplacerTourne) : les mobis partent au
     * rythme commun ; ceux deja arrives recoivent leur altitude pendant que les
     * suivants partent ; a la fin de chaque rafale, on suit seulement les
     * derniers en vol, puis on reprend en groupe ceux qui manquent :
     *   essai 0 : la rotation voulue, en repassant tant que des mobis arrivent ;
     *   essai 1 : la rotation equivalente sur le meme axe ;
     *   essai 2 : la rotation d'origine (mobis a une seule orientation).
     * @return true si arrete (Arreter ou salle quittee)
     */
    private static boolean rafalePivot(Groupes.Tache t, int salle, List<GroupeCalcul.Cible> reste,
                                       Set<Integer> sansTourner, int total, Set<Integer> hautEnvoyee) {
        boolean arrete = false;
        List<GroupeCalcul.Cible> aFaire = new ArrayList<>(reste);
        java.util.function.Predicate<GroupeCalcul.Cible> arrivee = k -> pivote(k, sansTourner);
        int fait = 0;
        for (int essai = 0; essai <= 2 && !aFaire.isEmpty() && !arrete; essai++) {
            for (int repasse = 0; repasse < (essai == 0 ? 4 : 1) && !aFaire.isEmpty(); repasse++) {
                List<GroupeCalcul.Cible> envoyes = new ArrayList<>(), enVol = new ArrayList<>();
                for (GroupeCalcul.Cible c : aFaire) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    int rot = GroupeCalcul.rotationEssai(c.rot, c.e.rot, essai);
                    if (rot < 0) continue;
                    // rotation d'origine : utile seulement si le mobi change de case, et
                    // seulement pour une emprise carree (un mobi a une seule orientation).
                    // Sinon il serait pose dans l'autre sens que le bloc : on le signale.
                    if (essai == 2 && (c.x == c.e.x && c.y == c.e.y || c.e.ex != c.e.ey)) continue;
                    if (essai == 2) sansTourner.add(c.e.id);
                    Salle.espacer();
                    Salle.deplacerSol(c.e.id, c.x, c.y, rot);
                    envoyes.add(c);
                    enVol.add(c);
                    if (essai == 0 && repasse == 0) fait++;
                    t.progres(Math.min(fait, total), total, essai == 0 && repasse == 0
                            ? "Pose rapide : " + Math.min(fait, total) + "/" + total
                            : "Pose rapide, nouvel essai : " + envoyes.size() + " envoyé(s)…");
                    hauteursArrivees(enVol, arrivee, hautEnvoyee);
                }
                if (envoyes.isEmpty()) break;
                final List<GroupeCalcul.Cible> l = aFaire;
                PoseDirecte.suivre(() -> nonPivotes(l, sansTourner).size(), arrete ? 400 : 700, 1500);
                hauteursArrivees(enVol, arrivee, hautEnvoyee);
                if (arrete) break;
                int avant = aFaire.size();
                aFaire = nonPivotes(aFaire, sansTourner);
                if (aFaire.size() == avant) break;        // plus rien n'avance : essai suivant
            }
        }
        return arrete;
    }

    private static List<GroupeCalcul.Cible> nonPivotes(List<GroupeCalcul.Cible> l, Set<Integer> sansTourner) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) if (!pivote(c, sansTourner)) r.add(c);
        return r;
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
     * Pose HYBRIDE (PoseHybride) : rafale directe (inventaire / BC) avec
     * @altitude, puis reprise avec la dalle magique des seuls mobis refuses ou
     * restes a une mauvaise hauteur ; sans @altitude, les mobis en hauteur
     * passent directement par la dalle. Une seule action pour Ctrl+Z.
     * @param dessus calque au-dessus duquel ranger le nouveau (null = en haut)
     */
    static Groupes.Resultat dupliquer(Groupes.Tache t, List<Set<Integer>> ids, String dessus,
                                      java.util.function.Function<List<GroupeCalcul.Element>, List<GroupeCalcul.Cible>> calcul,
                                      Generateur.Source source) {
        int salle = Groupes.salleCourante();
        GPresets gp = Salle.gp();
        if (gp == null) return Groupes.Resultat.refus("L'Atelier n'est pas encore prêt.");
        if (!Salle.furnidataPrete()) return Groupes.Resultat.refus("Furnidata pas encore chargée.");
        List<GroupeCalcul.Element> els = elements(ids.get(0), ids.get(1));
        if (els.isEmpty()) return Groupes.Resultat.refus("Aucun de ces mobis n'est dans la salle.");

        // 1. ce qu'on pose (altitudes absolues d'arrivee)
        List<GroupeCalcul.Cible> cibles = calcul.apply(els);
        List<PoseDirecte.Sol> sols = new ArrayList<>();
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
            sols.add(new PoseDirecte.Sol(cls, c.x, c.y, Generateur.arrondi(Math.max(0, c.z)), c.rot, Generateur.etatDe(it)));
        }
        int voulus = sols.size() + murs.size();
        String ignores = (wired > 0 ? wired + " wired ignoré(s) (sans leur réglage). " : "")
                + (inconnus > 0 ? inconnus + " mobi(s) de classe inconnue ignoré(s). " : "")
                + (hors > 0 ? hors + " mobi(s) qui tomberaient hors du plan ignoré(s). " : "")
                + (muraux > 0 ? muraux + " mobi(s) mural(aux) laissé(s) de côté (ils ne pivotent pas). " : "");
        if (voulus == 0) return Groupes.Resultat.refus("Rien à poser. " + ignores);

        java.util.function.BooleanSupplier stop = () -> t.arretee() || !memeSalle(salle);
        PoseDirecte.Resultat pr;
        PoseHybride.Bilan rb = null;
        boolean altitude = true;
        historiqueGrouper(true);           // rafale + reprise a la dalle : une seule action (Ctrl+Z)
        try {
            // 2. rafale ou dalle directe (sans @altitude : les mobis en hauteur)
            boolean enHauteur = false;
            for (PoseDirecte.Sol so : sols)
                if (!PoseHybride.parRafale(so.z, Salle.hauteurSol(so.x, so.y), false)) { enHauteur = true; break; }
            if (enHauteur) altitude = PoseHybride.altitudeDisponible(t::dire);
            List<PoseDirecte.Sol> rafale = new ArrayList<>();
            List<PoseHybride.Piece> reprise = new ArrayList<>();
            for (PoseDirecte.Sol so : sols) {
                if (PoseHybride.parRafale(so.z, Salle.hauteurSol(so.x, so.y), altitude)) rafale.add(so);
                else reprise.add(PoseHybride.Piece.depuis(so, -1));
            }
            Journal.debug("calques : copie hybride, " + rafale.size() + " sol(s) en rafale, " + reprise.size()
                    + " directement à la dalle, " + murs.size() + " mural(aux), @altitude " + (altitude ? "oui" : "non") + ".");

            // 3. la rafale
            int nRafale = rafale.size() + murs.size();
            pr = PoseDirecte.poser(rafale, murs, source, t::dire, stop,
                    (f, n) -> t.progres(f, n, "Pose rapide : " + f + "/" + n), altitude);

            // 4. verification : refuses et mauvaises hauteurs -> dalle
            if (!stop.getAsBoolean()) {
                for (PoseDirecte.Sol so : pr.solsRefuses) reprise.add(PoseHybride.Piece.depuis(so, -1));
                for (PoseDirecte.Mur m : pr.mursRefuses) reprise.add(PoseHybride.Piece.mur(m.classe, "", m.position, -1));
                for (Map.Entry<Integer, PoseDirecte.Sol> e : pr.solsPoses.entrySet()) {
                    HFloorItem now = Salle.sol(e.getKey());
                    if (now == null) continue;
                    if (PoseHybride.trier(true, true, now.getTile().getZ(), e.getValue().z) == PoseHybride.Issue.HAUTEUR)
                        reprise.add(PoseHybride.Piece.depuis(e.getValue(), e.getKey()));
                }
                if (!reprise.isEmpty()) {
                    Journal.debug("calques : " + reprise.size() + " mobi(s) repris à la dalle (" + pr.solsRefuses.size()
                            + " refusé(s), " + pr.mursRefuses.size() + " mural(aux) refusé(s)).");
                    t.progres(0, reprise.size(), "Reprise à la dalle : 0/" + reprise.size());
                    rb = PoseHybride.reprendre(reprise, source, t::dire, stop,
                            (f, n) -> t.progres(f, n, "Reprise à la dalle : " + f + "/" + n));
                }
            }
            Journal.debug("calques : rafale " + (pr.sols.size() + pr.murs.size()) + "/" + nRafale + ".");
        } finally {
            historiqueGrouper(false);
        }
        boolean arrete = t.arretee();
        if (!memeSalle(salle)) return new Groupes.Resultat(false, true, voulus, 0, voulus, "Tu as quitté la salle pendant la pose.", null);

        // 5. les nouveaux mobis -> nouveau calque
        List<Integer> nS = new ArrayList<>(), nM = new ArrayList<>(pr.murs);
        Set<Integer> partis = rb == null ? Set.of() : rb.ramassesSols;
        for (int id : pr.sols) if (!partis.contains(id)) nS.add(id);
        int parDalle = 0, hauteursFausses = 0;
        if (rb != null) {
            nS.addAll(rb.sols); nM.addAll(rb.murs);
            parDalle = rb.obtenus();
            hauteursFausses = rb.hauteursFausses;
        }
        // restees fausses sans reprise (reprise impossible ou arretee)
        if (rb == null || rb.raison != null || rb.arrete)
            for (Map.Entry<Integer, PoseDirecte.Sol> e : pr.solsPoses.entrySet()) {
                if (partis.contains(e.getKey())) continue;
                HFloorItem now = Salle.sol(e.getKey());
                if (now != null && Math.abs(now.getTile().getZ() - e.getValue().z) > PoseHybride.TOLERANCE) hauteursFausses++;
            }
        int obtenus = nS.size() + nM.size();
        String nouveau = obtenus > 0 ? Groupes.creer(null, nS, nM, dessus) : null;
        int echecs = Math.max(0, voulus - obtenus);
        String msg = PoseHybride.bilan("posé", voulus, obtenus, parDalle, hauteursFausses, arrete)
                + (nouveau != null ? " Nouveau calque créé." : "")
                + (rb != null && rb.raison != null && !arrete ? " Reprise à la dalle impossible : " + rb.raison + "." : "")
                + (!altitude ? PoseHybride.SANS_ALTITUDE : "")
                + (pr.etatsFaux > 0 ? " " + pr.etatsFaux + " mobi(s) dans un autre état (couleur, allumé…)." : "")
                + (ignores.isEmpty() ? "" : " " + ignores.trim())
                + (obtenus > 0 ? " " + WindowsClavier.texte("Cmd+Z pour annuler.") : "");
        return new Groupes.Resultat(echecs == 0 && hauteursFausses == 0 && !arrete, arrete, voulus, obtenus, echecs,
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
            Salle.sommeil(200);
            GPresetImporter.BuildingImportState st;
            try { st = imp.getState(); } catch (Throwable e) { st = GPresetImporter.BuildingImportState.NONE; }
            if (st == GPresetImporter.BuildingImportState.NONE) break;
            if (t.arretee()) { abandonner(imp, gp); break; }   // Arreter : on rend la main (verrou)
        }
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
        GPresets gp = Salle.gp();
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

        // 3. la pose (appart temporaire + dalle magique)
        GPresetImporter imp = gp.getImporter();
        if (imp == null) return Groupes.Resultat.refus("Moteur de pose introuvable.");
        boolean arrete = false, sortie = false;
        historiqueGrouper(true);
        try {
            boolean lance;
            try { lance = poser(gp, imp, mobis, murs, source, t::dire); }
            catch (Throwable e) { return Groupes.Resultat.refus("Pose impossible : " + e); }
            if (!lance) return new Groupes.Resultat(false, false, voulus, 0, voulus, "La pose n'a pas démarré. " + ignores, null);

            // 4. attendre la fin du moteur de pose, en comptant les nouveaux mobis
            long fin = System.currentTimeMillis() + 30 * 60_000L;
            while (System.currentTimeMillis() < fin) {
                Salle.sommeil(500);
                if (!memeSalle(salle)) { sortie = true; break; }
                if (t.arretee() && !arrete) { arrete = true; abandonner(imp, gp); }
                GPresetImporter.BuildingImportState st;
                try { st = imp.getState(); } catch (Throwable e) { st = GPresetImporter.BuildingImportState.NONE; }
                int n = nouveaux(avantS, attendusSols, false).size() + nouveaux(avantM, attendusMurs, true).size();
                t.progres(Math.min(n, voulus), voulus, "Pose de la copie : " + n + "/" + voulus
                        + (st == GPresetImporter.BuildingImportState.AWAITING_UNOCCUPIED_SPACE
                           ? ". Clique une case libre dans le jeu pour la dalle magique" : ""));
                if (st == GPresetImporter.BuildingImportState.NONE) break;
            }
            if (!sortie) Salle.sommeil(3500);   // la dalle de l'Atelier est ramassee ~1,5 s apres la fin
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
        historiqueGrouper(true);           // dalles, mobis et reprise a la dalle : une seule action (Ctrl+Z)
        Map<Integer, Integer> rotEnvoyee = new HashMap<>();
        java.util.function.Predicate<GroupeCalcul.Cible> arrive = c -> {
            Integer r = rotEnvoyee.get(c.e.id);
            return place(c) || r != null && placeTourne(c, r);
        };
        List<GroupeCalcul.Cible> repris = List.of();
        PoseHybride.Bilan rb = null;
        try {
            // 1. les dalles d'abord : rafale suivie, reprise groupee de celles qui manquent
            List<GroupeCalcul.Cible> dallesAFaire = new ArrayList<>();
            for (GroupeCalcul.Cible c : cDalles) if (!place(c)) dallesAFaire.add(c);
            fait += cDalles.size() - dallesAFaire.size();
            for (int passe = 0; passe < 2 && !arrete && !dallesAFaire.isEmpty(); passe++) {
                for (GroupeCalcul.Cible c : dallesAFaire) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    Salle.espacer();
                    Salle.deplacerSol(c.e.id, c.x, c.y, c.rot);
                    if (passe == 0) t.progres(++fait, total, "Dalles magiques : " + fait + "/" + cDalles.size());
                }
                final List<GroupeCalcul.Cible> l = dallesAFaire;
                PoseDirecte.suivre(() -> nonPlaces(l).size(), arrete ? 400 : 700, 1500);
                dallesAFaire = nonPlaces(dallesAFaire);
            }
            // 2. les mobis, du bas vers le haut, chacun a sa hauteur. Rafale : pour
            //    chaque mobi, les dalles dessous a SA hauteur puis le mobi, sans
            //    attendre qu'il arrive (le serveur traite les paquets dans l'ordre :
            //    le mobi est pose avant que la dalle change pour le suivant). Ceux
            //    qui manquent sont repris en groupe avec la rotation suivante.
            cMobis.sort(Comparator.comparingDouble((GroupeCalcul.Cible c) -> c.z).thenComparingInt(c -> c.e.id));
            Map<Integer, Integer> hauteurDalle = new HashMap<>();
            long[] dernierReglage = {0};
            List<GroupeCalcul.Cible> aFaire = new ArrayList<>(cMobis);
            int n = 0;
            for (int essai = 0; essai <= 2 && !aFaire.isEmpty() && !arrete; essai++) {
                boolean envoye = false;
                for (GroupeCalcul.Cible c : aFaire) {
                    if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                    int rot = GroupeCalcul.rotationEssai(c.rot, c.e.rot, essai);
                    if (rot < 0 || essai == 2 && c.e.ex != c.e.ey) continue;   // pas dans l'autre sens que le bloc
                    reglerDalles(gp, c, q, cDalles, hauteurDalle, dernierReglage);
                    Salle.espacer();
                    Salle.deplacerSol(c.e.id, c.x, c.y, rot);
                    rotEnvoyee.put(c.e.id, rot);
                    envoye = true;
                    if (essai == 0) t.progres(++fait, total, "Pose rapide : " + (++n) + "/" + cMobis.size());
                }
                if (!envoye) break;
                final List<GroupeCalcul.Cible> l = aFaire;
                PoseDirecte.suivre(() -> malPoses(l, rotEnvoyee).size(), arrete ? 400 : 700, 2000);
                aFaire = malPoses(aFaire, rotEnvoyee);
            }
            // 3. les muraux (sans pivot seulement : ils ne tournent pas)
            for (GroupeCalcul.Cible c : cMurs) {
                if (t.arretee() || !memeSalle(salle)) { arrete = true; break; }
                Salle.espacer();
                Salle.deplacerMur(c.e.id, SelectionMur.normaliser(c.position));
                t.progres(++fait, total, "Muraux…");
            }
            // 4. verification : les mobis refuses ou a une mauvaise hauteur, et
            //    seulement eux, repris avec la dalle du moteur de pose
            if (!arrete && memeSalle(salle)) {
                PoseDirecte.suivre(() -> aReprendre(cMobis, arrive).size(), 500, 1200);
                repris = aReprendre(cMobis, arrive);
                if (!repris.isEmpty()) rb = reprendreCibles(t, salle, repris);
            }
        } finally {
            historiqueGrouper(false);
        }
        arrete = arrete || t.arretee() || !memeSalle(salle);
        String suite = " Sur " + cDalles.size() + " dalle(s) magique(s) du calque."
                + (q != 0 && !ids.get(1).isEmpty() ? " Les muraux ne pivotent pas." : "");
        return bilanDeplacement(q != 0 ? "pivoté" : "déplacé", cMobis.size() + hors, hors, repris, rb, true, arrete,
                arrive, cMobis, suite);
    }

    /** Le serveur ignore les reglages de hauteur de dalle trop rapproches : au moins ce temps entre deux. */
    private static final long ECART_DALLE_MS = 250;

    /** Met les dalles magiques sous le mobi a SA hauteur (celles qui n'y sont pas deja). */
    private static void reglerDalles(GPresets gp, GroupeCalcul.Cible c, int q, List<GroupeCalcul.Cible> cDalles,
                                     Map<Integer, Integer> hauteurDalle, long[] dernierReglage) {
        int lx = q % 2 == 1 ? c.e.ey : c.e.ex, ly = q % 2 == 1 ? c.e.ex : c.e.ey;
        int valeur = (int) Math.round(Math.max(0, c.z) * 100);
        for (GroupeCalcul.Cible d : cDalles) {
            int dlx = q % 2 == 1 ? d.e.ey : d.e.ex, dly = q % 2 == 1 ? d.e.ex : d.e.ey;
            boolean dessous = d.x <= c.x + lx - 1 && d.x + dlx - 1 >= c.x && d.y <= c.y + ly - 1 && d.y + dly - 1 >= c.y;
            if (!dessous || Objects.equals(hauteurDalle.get(d.e.id), valeur)) continue;
            long reste = ECART_DALLE_MS - (System.currentTimeMillis() - dernierReglage[0]);
            if (reste > 0) Salle.sommeil(reste);
            Salle.espacer();
            gp.sendToServer(new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, d.e.id, valeur));
            dernierReglage[0] = System.currentTimeMillis();
            hauteurDalle.put(d.e.id, valeur);
        }
    }

    private static List<GroupeCalcul.Cible> nonPlaces(List<GroupeCalcul.Cible> l) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) if (!place(c)) r.add(c);
        return r;
    }

    /** Ceux qui ne sont pas a leur case avec la rotation voulue, son equivalente ou celle envoyee. */
    private static List<GroupeCalcul.Cible> malPoses(List<GroupeCalcul.Cible> l, Map<Integer, Integer> rotEnvoyee) {
        List<GroupeCalcul.Cible> r = new ArrayList<>();
        for (GroupeCalcul.Cible c : l) {
            Integer rot = rotEnvoyee.get(c.e.id);
            if (!(place(c) || rot != null && placeTourne(c, rot))) r.add(c);
        }
        return r;
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
    static void abandonner(GPresetImporter imp, GPresets gp) {
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
     * racine mise a ce coin : le moteur de pose pose sols et murs a racine + (x, y)
     * (placeWallItems : WallPosition.x + rootLocation.x).
     */
    static boolean poser(GPresets gp, GPresetImporter imp, List<Generateur.Mobi> mobis, List<MurPose> murs,
                         Generateur.Source source, Consumer<String> dire) throws Exception {
        if (!Salle.dansUneSalle()) { dire.accept("Tu n'es pas dans une salle."); return false; }
        try {
            if (imp.getState() != GPresetImporter.BuildingImportState.NONE) {
                dire.accept("Le moteur de pose est déjà en train d'importer — termine ou tape :abort dans le jeu.");
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

        String entete = (furni.size() + wall.size()) + " mobi(s) envoyés au moteur de pose. ";
        if (furni.isEmpty()) {
            // murs seuls : le moteur de pose les pose des « :ip x,y », sans dalle magique
            boolean ok = Generateur.importer(gp, imp, relu, fichier, source, racine, s -> { }, entete, null);
            if (ok) dire.accept(entete + "Pose des murs en cours (:abort dans le jeu pour arrêter).");
            else dire.accept("Le moteur de pose n'a pas lancé la pose (regarde son message dans le jeu).");
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

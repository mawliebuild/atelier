package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;

import java.util.*;

/**
 * « Compléter » une copie d'appart : au lieu de tout reposer, on part de ce
 * qui est deja dans la salle.
 * <ul>
 *   <li>un mobi de la copie deja la (meme classe, meme case, meme hauteur,
 *       meme rotation) n'est pas touche ; seul son etat est remis s'il differe ;</li>
 *   <li>sinon un mobi de meme classe de la salle qui ne sert a rien d'autre
 *       est deplace a sa place (le plus proche d'abord) ;</li>
 *   <li>sinon il est pose (inventaire ou BC, comme un collage).</li>
 * </ul>
 * Les mobis de la salle qui ne servent pas restent ou ils sont.
 */
final class CompleterAppart {

    private CompleterAppart() { }

    /** Ce que le complement fait, calcule avant la pose. */
    static final class Plan {
        /** Les pieces a donner a PoseTapis : deplacements et nouveaux (les gardes n'y sont pas). */
        final List<PoseTapis.Piece> pieces = new ArrayList<>();
        /** Cle de la copie -> id du mobi garde ou deplace (reglages des wired). */
        final Map<Integer, Integer> cles = new LinkedHashMap<>();
        /** Id -> etat voulu, pour les gardes et les deplaces (PoseDirecte.etats). */
        final Map<Integer, String> etats = new LinkedHashMap<>();
        /** Les muraux a poser (ni gardes ni deplaces). */
        final List<CopieAppart.MobiMur> mursAPoser = new ArrayList<>();
        /** Muraux a deplacer : id -> position voulue (format MoveWallItem). */
        final Map<Integer, String> mursADeplacer = new LinkedHashMap<>();
        int solsGardes, solsDeplaces, solsNouveaux, mursGardes, mursDeplaces;
    }

    private static boolean memeEtat(String voulu, String lu) {
        if (voulu == null || voulu.isEmpty()) return true;              // pas d'etat dans la copie : tout convient
        return PoseMuraux.memeEtat(voulu, lu == null ? "" : lu);
    }

    private static boolean compte(HFloorItem it) {
        return it != null && it.getTile() != null
                && !GroupeFantomes.estFantome(it.getId()) && !GrilleCalcul.estFictif(it.getId());
    }

    /**
     * Les sols : voulues sont les pieces « nouveau » d'un collage (classe, case,
     * hauteur absolue, rotation, etat, cle).
     */
    static Plan plan(List<PoseTapis.Piece> voulues, CopieAppart cfg, HPoint coin) {
        Plan p = new Plan();
        Map<String, List<HFloorItem>> libres = new HashMap<>();
        for (HFloorItem it : Salle.sols()) {
            if (!compte(it)) continue;
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null) libres.computeIfAbsent(c, k -> new ArrayList<>()).add(it);
        }

        // 1. deja a sa place (case, hauteur, rotation) : garde ; l'etat seul est remis
        List<PoseTapis.Piece> reste = new ArrayList<>();
        for (PoseTapis.Piece v : voulues) {
            List<HFloorItem> l = libres.get(v.classe);
            HFloorItem trouve = null;
            if (l != null) {
                // d'abord celui qui a aussi le bon etat
                for (HFloorItem it : l)
                    if (aSaPlace(it, v) && memeEtat(v.etat, Generateur.etatDe(it))) { trouve = it; break; }
                if (trouve == null)
                    for (HFloorItem it : l) if (aSaPlace(it, v)) { trouve = it; break; }
            }
            if (trouve == null) { reste.add(v); continue; }
            l.remove(trouve);
            p.solsGardes++;
            if (v.cle != -1) p.cles.put(v.cle, trouve.getId());
            if (!memeEtat(v.etat, Generateur.etatDe(trouve))) p.etats.put(trouve.getId(), v.etat);
        }

        // 2. un mobi de meme classe qui ne sert pas : deplace (le plus proche)
        for (PoseTapis.Piece v : reste) {
            List<HFloorItem> l = libres.get(v.classe);
            HFloorItem pris = null;
            if (l != null && !l.isEmpty()) {
                int meilleur = Integer.MAX_VALUE;
                for (HFloorItem it : l) {
                    int d = Math.abs(it.getTile().getX() - v.x) + Math.abs(it.getTile().getY() - v.y)
                            + (memeEtat(v.etat, Generateur.etatDe(it)) ? 0 : 1);
                    if (d < meilleur) { meilleur = d; pris = it; }
                }
            }
            if (pris == null) { p.pieces.add(v); p.solsNouveaux++; continue; }
            l.remove(pris);
            int[] e = PoseTapis.emprise(v.classe, v.rot);
            // un wired deplace garde son id (pas de reprise a la dalle) : ses reglages suivent
            p.pieces.add(PoseTapis.Piece.deplace(pris.getId(), v.nom, v.x, v.y, v.z, v.rot, Salle.rotation(pris),
                    e[0], e[1], !Wired.estWired(v.classe)));
            p.solsDeplaces++;
            if (v.cle != -1) p.cles.put(v.cle, pris.getId());
            if (v.etat != null && !v.etat.isEmpty()) p.etats.put(pris.getId(), v.etat);
        }

        murs(p, cfg, coin);
        return p;
    }

    private static boolean aSaPlace(HFloorItem it, PoseTapis.Piece v) {
        return it.getTile().getX() == v.x && it.getTile().getY() == v.y
                && Math.abs(it.getTile().getZ() - v.z) <= 0.011
                && (Salle.rotation(it) & 7) == (v.rot & 7);
    }

    /** Les muraux : gardes s'ils sont deja a leur position (altitude et etat compris), sinon deplaces, sinon poses. */
    private static void murs(Plan p, CopieAppart cfg, HPoint coin) {
        Map<String, List<HWallItem>> libres = new HashMap<>();
        for (HWallItem w : Salle.murs()) {
            if (w == null || GroupeFantomes.estFantome(w.getId())) continue;
            String c = Salle.classe(w.getTypeId(), true);
            if (c != null && PositionMur.lireOuNull(w.getLocation()) != null)
                libres.computeIfAbsent(c, k -> new ArrayList<>()).add(w);
        }
        List<CopieAppart.MobiMur> reste = new ArrayList<>();
        List<PositionMur> voulues = new ArrayList<>();
        for (CopieAppart.MobiMur m : cfg.murs) {
            PositionMur v = new PositionMur(m.position.x() + coin.getX(), m.position.y() + coin.getY(),
                    m.position.decalageX(), m.position.decalageY(), m.position.cote(), m.position.altitude());
            List<HWallItem> l = libres.get(m.classe);
            HWallItem trouve = null;
            if (l != null) for (HWallItem w : l) {
                PositionMur a = PositionMur.lireOuNull(w.getLocation());
                if (a.toString().equals(v.toString()) && a.altitude() == v.altitude() && etatMur(m, w)) { trouve = w; break; }
            }
            if (trouve != null) { l.remove(trouve); p.mursGardes++; continue; }
            reste.add(m);
            voulues.add(v);
        }
        // deplacer : meme classe, meme etat (une affiche = une image), meme altitude
        // (l'altitude se regle a la pose, pas au deplacement)
        for (int i = 0; i < reste.size(); i++) {
            CopieAppart.MobiMur m = reste.get(i);
            PositionMur v = voulues.get(i);
            List<HWallItem> l = libres.get(m.classe);
            HWallItem pris = null;
            if (l != null) for (HWallItem w : l) {
                PositionMur a = PositionMur.lireOuNull(w.getLocation());
                if (a.altitude() == v.altitude() && etatMur(m, w)) { pris = w; break; }
            }
            if (pris == null) { p.mursAPoser.add(m); continue; }
            l.remove(pris);
            p.mursADeplacer.put(pris.getId(), v.toString());
            p.mursDeplaces++;
        }
    }

    private static boolean etatMur(CopieAppart.MobiMur m, HWallItem w) {
        return m.etat == null || m.etat.isEmpty() || PoseMuraux.memeEtat(m.etat, w.getState());
    }

    /** Deplace les muraux du plan (MoveWallItem), un par un. Rend le nombre arrives a leur place. */
    static int deplacerMuraux(Plan p, java.util.function.BooleanSupplier stop) {
        int ok = 0;
        for (Map.Entry<Integer, String> e : p.mursADeplacer.entrySet()) {
            if (stop.getAsBoolean()) break;
            Salle.espacer();
            Salle.deplacerMur(e.getKey(), e.getValue());
            String voulu = e.getValue();
            long fin = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < fin && !stop.getAsBoolean()) {
                HWallItem w = Salle.mur(e.getKey());
                if (w != null && voulu.equals(SelectionMur.normaliser(w.getLocation()))) { ok++; break; }
                Salle.sommeil(60);
            }
        }
        return ok;
    }

    /** « 12 gardés, 3 déplacés, 5 posés » pour le bilan. */
    static String resume(Plan p) {
        int g = p.solsGardes + p.mursGardes, d = p.solsDeplaces + p.mursDeplaces;
        return Ui.accorder(g + " déjà en place gardé(s), " + d + " déplacé(s)");
    }
}

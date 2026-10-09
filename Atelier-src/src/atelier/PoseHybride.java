package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Depuis le TAPIS DE DALLES (PoseTapis), qui pose et deplace maintenant pour
 * Dupliquer, Deplacer, Pivoter, Miroir et le collage d'appart, cette classe
 * sert a sa reprise : reprendre (dalle par mobi) pour les mobis refuses, et
 * pour les mobis en hauteur quand @altitude est introuvable ; altitudeDisponible.
 *
 * Pose HYBRIDE (ancienne methode) :
 *   1. tout part d'abord en rafale, vite (pose directe depuis l'inventaire ou
 *      le BC, ou MoveObject), puis la hauteur exacte par @altitude, au rythme
 *      commun (Salle.espacer) ;
 *   2. verification : les mobis refuses par le jeu (pas apparus, pas arrives
 *      a leur case) ou restes a une mauvaise hauteur, et SEULEMENT eux, sont
 *      repris avec la dalle magique (moteur de pose : la dalle passe outre ce
 *      qu'il y a dessous), tous ensemble dans un seul import (reprendre) ;
 *   3. sans @altitude (pas de droits wired, variable inconnue), tout ce qui
 *      n'est pas pose a meme le sol passe directement par la dalle
 *      (parRafale).
 *
 * Ici : le tri (logique pure, testee par PoseHybrideTest), le bilan unique,
 * et la reprise avec la dalle. Les appelants groupent toute l'action dans
 * l'historique (Historique.grouper) : un seul Ctrl+Z, rafale et reprise
 * comprises. La dalle posee par l'Atelier est ignoree par l'historique
 * (Generateur.Dalle.preparer : Historique.ignorerType).
 */
final class PoseHybride {

    private PoseHybride() { }

    /** Ecart de hauteur au-dela duquel un mobi n'est pas a sa hauteur. */
    static final double TOLERANCE = 0.02;

    // ============================================================ logique pure

    /** Ce qu'est devenu un mobi apres la rafale. */
    enum Issue { POSE, REFUSE, HAUTEUR }

    /**
     * Tri apres la rafale.
     * @param present  le mobi est dans la salle
     * @param aSaCase  il est a sa case d'arrivee (et dans une rotation acceptee)
     * @param z        son altitude actuelle
     * @param voulu    l'altitude voulue
     */
    static Issue trier(boolean present, boolean aSaCase, double z, double voulu) {
        if (!present || !aSaCase) return Issue.REFUSE;
        return Math.abs(z - voulu) > TOLERANCE ? Issue.HAUTEUR : Issue.POSE;
    }

    /**
     * Le mobi part-il dans la rafale ? Oui si @altitude est disponible ; sinon
     * seulement s'il est pose a meme le sol de sa case (il y arrive tout seul).
     * @param sol hauteur du sol nu de la case (-1 : inconnue)
     */
    static boolean parRafale(double voulu, int sol, boolean altitude) {
        if (altitude) return true;
        return Math.abs(voulu - Math.max(0, sol)) <= 0.01;
    }

    /**
     * Le bilan unique d'une action : « 52 mobis posés, dont 12 avec la dalle. »,
     * ou « 50/52 mobis déplacés, dont 12 avec la dalle ; 2 refusés, 1 pas pu
     * être mis à la bonne hauteur. » (les mots « refusé » et « pas pu être »
     * en font une erreur pour Journal.genre)
     * @param participe « posé », « déplacé », « pivoté »...
     */
    static String bilan(String participe, int voulus, int reussis, int parDalle, int hauteursFausses, boolean arrete) {
        StringBuilder b = new StringBuilder();
        if (arrete) b.append("Arrêté. ");
        int rates = Math.max(0, voulus - reussis);
        b.append(reussis == voulus ? String.valueOf(reussis) : reussis + "/" + voulus)
                .append(voulus > 1 || reussis > 1 ? " mobis " : " mobi ")
                .append(participe).append(reussis > 1 || (reussis != voulus && voulus > 1) ? "s" : "");
        if (parDalle > 0) b.append(", dont ").append(parDalle).append(" avec la dalle");
        List<String> soucis = new ArrayList<>();
        if (rates > 0 && !arrete)
            soucis.add(rates + (rates > 1 ? " refusés" : " refusé"));
        if (hauteursFausses > 0) soucis.add(hauteursFausses + " pas pu être mis à la bonne hauteur");
        if (!soucis.isEmpty()) b.append(" ; ").append(String.join(", ", soucis));
        return b.append(".").toString();
    }

    /**
     * Rapproche les mobis apparus des pieces attendues : meme type, meme case
     * (sols) ; meme type et meme position, a defaut meme type (muraux).
     * @param voulus   {type, x, y, mural(0/1)} par piece ; position murale dans positions
     * @param nouveaux {id, type, x, y, mural(0/1)} ; position murale dans positionsNouveaux
     * @return pour chaque piece, l'id trouve (-1 : pas apparu)
     */
    static int[] apparier(List<int[]> voulus, List<String> positions, List<int[]> nouveaux, List<String> positionsNouveaux) {
        int[] r = new int[voulus.size()];
        Arrays.fill(r, -1);
        boolean[] pris = new boolean[nouveaux.size()];
        for (int passe = 0; passe < 2; passe++) {           // 0 : place exacte ; 1 : muraux au type seul
            for (int i = 0; i < voulus.size(); i++) {
                if (r[i] != -1) continue;
                int[] v = voulus.get(i);
                for (int j = 0; j < nouveaux.size(); j++) {
                    if (pris[j]) continue;
                    int[] n = nouveaux.get(j);
                    if (n[1] != v[0] || n[4] != v[3]) continue;
                    boolean ok;
                    if (v[3] == 0) ok = passe == 0 && n[2] == v[1] && n[3] == v[2];
                    else ok = passe == 1 || Objects.equals(positions.get(i), positionsNouveaux.get(j));
                    if (!ok) continue;
                    pris[j] = true;
                    r[i] = n[0];
                    break;
                }
            }
        }
        return r;
    }

    // ============================================================ pieces

    /** Un mobi a (re)poser avec la dalle. ancien : id a ramasser d'abord (-1 : aucun). */
    static final class Piece {
        final String classe, etat, position;
        final int x, y, rot, ancien, cle;
        final double z;
        final boolean mural;
        private Piece(String classe, String etat, int x, int y, double z, int rot, String position,
                      boolean mural, int ancien, int cle) {
            this.classe = classe; this.etat = etat; this.x = x; this.y = y; this.z = z; this.rot = rot & 7;
            this.position = position; this.mural = mural; this.ancien = ancien; this.cle = cle;
        }
        /** z : altitude ABSOLUE voulue. */
        static Piece sol(String classe, String etat, int x, int y, double z, int rot, int ancien, int cle) {
            return new Piece(classe, etat, x, y, z, rot, null, false, ancien, cle);
        }
        static Piece mur(String classe, String etat, String position, int ancien) {
            return new Piece(classe, etat, 0, 0, 0, 0, position, true, ancien, -1);
        }
        static Piece depuis(PoseDirecte.Sol s, int ancien) {
            return sol(s.classe, s.etat, s.x, s.y, s.z, s.rot, ancien, s.cle);
        }
    }

    /** Resultat d'une reprise avec la dalle. */
    static final class Bilan {
        int voulus;
        /** Nouveaux mobis poses par la reprise. */
        final List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
        /** ancien id (ramasse) -> nouvel id. */
        final Map<Integer, Integer> remplacesSols = new LinkedHashMap<>(), remplacesMurs = new LinkedHashMap<>();
        /** Anciens ids ramasses (remplaces ou non). */
        final Set<Integer> ramassesSols = new LinkedHashSet<>(), ramassesMurs = new LinkedHashSet<>();
        /** cle de la piece -> nouvel id. */
        final Map<Integer, Integer> cles = new LinkedHashMap<>();
        int hauteursFausses;
        boolean arrete, sortie;
        /** Pourquoi la reprise n'a pas pu se faire (null : faite). */
        String raison;
        int obtenus() { return sols.size() + murs.size(); }
    }

    // ============================================================ @altitude

    /**
     * @altitude est-elle disponible ? Confirmee pendant cette session : oui
     * tout de suite ; sinon on demande la liste des variables au jeu (~1,5 s).
     * Une variable retenue mais pas encore confirmee compte : le premier mobi
     * la verifie (OutilMiroir.Altitude.ecrireVerifiee / mettre) ; si elle est
     * fausse, ses mobis restent a une mauvaise hauteur et sont repris a la dalle.
     */
    static boolean altitudeDisponible(Consumer<String> dire) {
        if (OutilMiroir.Altitude.confirmee()) return true;
        if (dire != null) dire.accept("Recherche de @altitude…");
        Salle.espacer();
        try { OutilMiroir.Altitude.demanderListe(); } finally { Salle.envoiFait(); }
        boolean ok = OutilMiroir.Altitude.possible();      // verifiee sur le premier mobi de la rafale
        Journal.debug("pose hybride : @altitude " + (ok ? "disponible (" + OutilMiroir.Altitude.variable() + ")" : "inconnue : les mobis en hauteur passent par la dalle"));
        return ok;
    }

    /** Texte a ajouter au bilan quand @altitude manquait. */
    static final String SANS_ALTITUDE = " @altitude inconnue : les mobis en hauteur sont passés par la dalle.";

    // ============================================================ reprise

    /**
     * Reprend ces pieces avec la dalle magique, en une seule pose (PoseCopie) :
     * les anciens mobis (mauvaise hauteur, ou pas arrives) sont d'abord
     * ramasses, puis tout est repose a sa case et sa hauteur exactes.
     * Hors fil JavaFX. Ne lance aucune exception.
     * @param progres (fait, total), « Reprise à la dalle : 3/12 »
     */
    static Bilan reprendre(List<Piece> pieces, Generateur.Source source, Consumer<String> dire,
                           BooleanSupplier stop, BiConsumer<Integer, Integer> progres) {
        Bilan b = new Bilan();
        b.voulus = pieces.size();
        if (pieces.isEmpty()) return b;
        try {
            return reprendre0(pieces, source, dire, stop, progres, b);
        } catch (Throwable t) {
            Journal.debug("reprise à la dalle : " + t);
            b.raison = "erreur (" + t.getClass().getSimpleName() + ")";
            return b;
        }
    }

    private static Bilan reprendre0(List<Piece> pieces, Generateur.Source source, Consumer<String> dire,
                                    BooleanSupplier stop, BiConsumer<Integer, Integer> progres, Bilan b) throws Exception {
        Moteur gp = Salle.gp();
        if (gp == null || !Salle.furnidataPrete()) { b.raison = "Atelier pas prêt"; return b; }
        if (PoseCopie.occupee()) { b.raison = "une autre pose est déjà en cours"; return b; }
        int salle = Groupes.salleCourante();
        Furnidata fd = gp.getFurniDataTools();

        // 1. les anciens d'abord (sinon ils resteraient en double)
        List<Piece> aRamasser = new ArrayList<>();
        for (Piece p : pieces) if (p.ancien >= 0 && present(p)) aRamasser.add(p);
        List<Integer> idsRamasses = new ArrayList<>();
        for (Piece p : aRamasser) idsRamasses.add(p.ancien);
        PoseOutils.Signaux.ramassageEnCours(idsRamasses);       // une demande de confirmation du jeu est acceptee
        try {
        if (!aRamasser.isEmpty()) {
            dire.accept("Reprise à la dalle : je ramasse " + aRamasser.size() + " mobi(s) mal placé(s)…");
            for (Piece p : aRamasser) {
                if (stop.getAsBoolean()) { b.arrete = true; break; }
                Salle.espacer();
                Salle.envoyer(PoseOutils.ramassage(p.ancien, p.mural, false));   // comme le client
            }
            PoseDirecte.suivre(() -> { int n = 0; for (Piece p : aRamasser) if (present(p)) n++; return n; }, 800, 3000);
            // ceux encore la : ramassage renvoye tout de suite, au plus REESSAIS fois
            int avantReessai = 0;
            for (Piece p : aRamasser) if (present(p)) avantReessai++;
            for (int k = 0; k < Salle.REESSAIS && !b.arrete && Groupes.salleCourante() == salle; k++) {
                List<Piece> encore = new ArrayList<>();
                for (Piece p : aRamasser) if (present(p)) encore.add(p);
                if (encore.isEmpty()) break;
                Salle.pauseReessai();
                for (Piece p : encore) {
                    if (stop.getAsBoolean()) { b.arrete = true; break; }
                    Salle.espacer();
                    Salle.envoyer(PoseOutils.ramassage(p.ancien, p.mural, false));   // comme le client
                }
                PoseDirecte.suivre(() -> { int n = 0; for (Piece p : encore) if (present(p)) n++; return n; }, 800, 3000);
            }
            int restes = 0;
            for (Piece p : aRamasser) if (present(p)) restes++;
            if (avantReessai > restes) Journal.debug("reprise à la dalle : " + (avantReessai - restes) + " mobi(s) ramassé(s) après réessai.");
            for (Piece p : aRamasser) if (!present(p)) (p.mural ? b.ramassesMurs : b.ramassesSols).add(p.ancien);
            if (!b.arrete && !stop.getAsBoolean() && Groupes.salleCourante() == salle) {
                int pris = b.ramassesSols.size() + b.ramassesMurs.size();
                Salle.signalerReussite(pris);
                Salle.signalerRefus("mobi pas ramassé", aRamasser.size() - pris);
            }
        }
        } finally {
            PoseOutils.Signaux.ramassageFini(idsRamasses);
        }
        if (b.arrete || stop.getAsBoolean()) { b.arrete = true; return b; }

        // 2. le preset : seulement ce qui peut etre pose (classe connue, ancien parti)
        List<Piece> prets = new ArrayList<>();
        for (Piece p : pieces) {
            if (p.ancien >= 0 && present(p)) continue;                    // pas ramasse : on ne double pas
            Integer type = p.mural ? fd.getWallTypeId(p.classe) : fd.getFloorTypeId(p.classe);
            if (type == null) continue;
            prets.add(p);
        }
        if (prets.isEmpty()) { b.raison = "rien n'a pu être repris"; return b; }
        int solMin = Integer.MAX_VALUE;
        for (Piece p : prets) if (!p.mural) { int h = Salle.hauteurSol(p.x, p.y); if (h >= 0) solMin = Math.min(solMin, h); }
        if (solMin == Integer.MAX_VALUE) solMin = 0;
        List<Generateur.Mobi> mobis = new ArrayList<>();
        List<GroupeActions.MurPose> murs = new ArrayList<>();
        for (Piece p : prets) {
            if (p.mural) murs.add(new GroupeActions.MurPose(p.classe, p.etat, p.position));
            else mobis.add(new Generateur.Mobi(p.classe, p.etat, p.x, p.y,
                    Generateur.arrondi(Math.max(0, p.z - solMin)), p.rot));
        }
        Set<Integer> avantS = new HashSet<>(), avantM = new HashSet<>();
        for (HFloorItem it : Salle.sols()) avantS.add(it.getId());
        for (HWallItem w : Salle.murs()) avantM.add(w.getId());
        Journal.debug("reprise à la dalle : " + mobis.size() + " sol(s), " + murs.size() + " mural(aux), sol min " + solMin + ".");

        // 3. la pose avec la dalle (posee et ramassee par la pose si la salle n'en a pas)
        String[] dernier = {null};
        Consumer<String> note = m -> { dernier[0] = m; dire.accept(m); };
        int total = prets.size();
        PoseCopie.Resultat pc = GroupeActions.poser(mobis, murs, source, note, stop, () -> {
            int n = 0;
            for (int id : apparier(prets, avantS, avantM, fd)) if (id != -1) n++;
            progres.accept(Math.min(n, total), total);
        });
        if (pc == null) { b.raison = dernier[0] == null ? "la pose avec la dalle n'a pas démarré" : Ui.majuscule(dernier[0]); return b; }
        if (Groupes.salleCourante() != salle) { b.sortie = true; return b; }
        if (pc.arrete || stop.getAsBoolean()) b.arrete = true;
        // les derniers en route
        PoseDirecte.suivre(() -> {
            int n = 0;
            for (int id : apparier(prets, avantS, avantM, fd)) if (id == -1) n++;
            return n;
        }, 1200, 3500);

        // 4. qui est qui
        int[] ids = apparier(prets, avantS, avantM, fd);
        for (int i = 0; i < prets.size(); i++) {
            int id = ids[i];
            if (id == -1) continue;
            Piece p = prets.get(i);
            (p.mural ? b.murs : b.sols).add(id);
            if (p.ancien >= 0) (p.mural ? b.remplacesMurs : b.remplacesSols).put(p.ancien, id);
            if (p.cle != -1) b.cles.put(p.cle, id);
            if (!p.mural) {
                HFloorItem it = Salle.sol(id);
                if (it != null && Math.abs(it.getTile().getZ() - p.z) > TOLERANCE) b.hauteursFausses++;
            }
        }
        progres.accept(b.obtenus(), total);
        if (!b.arrete) {
            Salle.signalerReussite(b.obtenus());
            Salle.signalerRefus("mobi pas reposé à la dalle", total - b.obtenus());
        }
        return b;
    }

    private static boolean present(Piece p) {
        return p.mural ? Salle.mur(p.ancien) != null : PoseTapis.encoreLa(p.ancien);
    }

    /** Les nouveaux mobis de la salle (depuis avant), rapproches des pieces. */
    private static int[] apparier(List<Piece> prets, Set<Integer> avantS, Set<Integer> avantM, Furnidata fd) {
        List<int[]> voulus = new ArrayList<>();
        List<String> positions = new ArrayList<>();
        for (Piece p : prets) {
            Integer type = p.mural ? fd.getWallTypeId(p.classe) : fd.getFloorTypeId(p.classe);
            voulus.add(new int[]{type == null ? -1 : type, p.x, p.y, p.mural ? 1 : 0});
            positions.add(p.mural ? SelectionMur.normaliser(p.position) : null);
        }
        List<int[]> nouveaux = new ArrayList<>();
        List<String> posN = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (avantS.contains(it.getId()) || GroupeFantomes.estFantome(it.getId())) continue;
            nouveaux.add(new int[]{it.getId(), it.getTypeId(), it.getTile().getX(), it.getTile().getY(), 0});
            posN.add(null);
        }
        for (HWallItem w : Salle.murs()) {
            if (avantM.contains(w.getId()) || GroupeFantomes.estFantome(w.getId())) continue;
            nouveaux.add(new int[]{w.getId(), w.getTypeId(), 0, 0, 1});
            posN.add(SelectionMur.normaliser(w.getLocation()));
        }
        return apparier(voulus, positions, nouveaux, posN);
    }
}

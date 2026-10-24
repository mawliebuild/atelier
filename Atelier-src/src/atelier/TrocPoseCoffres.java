package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import java.util.*;

/**
 * Les coffres de la boutique troc quand les wired de vente sont deja dans
 * l'appart mais qu'un coffre (ou les deux) n'y est plus : l'Atelier pose ceux
 * qui manquent depuis l'inventaire (les coffres ne sont pas au Builders Club),
 * pres des wired de la boutique, puis RELIE les wired aux coffres.
 *
 * Relier : les wired designent les coffres par leur id. Un coffre repose a un
 * nouvel id ; wf_act_init_transaction (coffres credits + mobis, contrat) et
 * wf_xtra_scan_chest_furni_by_type (coffre a mobis) qui ne designent plus un
 * coffre de l'appart recoivent le reglage de TrocModele avec les vrais ids
 * (coffres, contrat, variable « ctx »). Un wired qui designe deja les coffres
 * n'est pas touche. Sert aussi apres la pose d'une boutique qui a repris des
 * coffres deja dans l'appart (ConfigTroc.collerIci).
 *
 * Tout sur un fil de travail ; aucun message dans le jeu ici (l'appelant dit
 * le resultat, une fois).
 */
final class TrocPoseCoffres {

    private TrocPoseCoffres() { }

    static final String TRANSACTION = "wf_act_init_transaction", SCAN = "wf_xtra_scan_chest_furni_by_type",
            CONTRAT = "wf_xtra_custom_contract";

    /** Coffres poses, wired relies ; erreur null = c'est bon. */
    record Resultat(int poses, int relies, String erreur) {
        boolean ok() { return erreur == null; }
    }

    // ================================================================ logique pure

    /** Le premier mobi de sol de l'inventaire d'une de ces classes (dans l'ordre des classes) ; null sinon. */
    static HInventoryItem coffreDansInventaire(List<HInventoryItem> inv, List<String> classes,
                                               java.util.function.IntFunction<String> classeDe) {
        if (inv == null) return null;
        for (String k : classes)
            for (HInventoryItem it : inv)
                if (it != null && it.getType() == HProductType.FloorItem && k.equals(classeDe.apply(it.getTypeId())))
                    return it;
        return null;
    }

    /** Le wired designe-t-il deja un des coffres presents de chaque genre voulu ? */
    static boolean relie(String classe, Collection<Integer> items, Set<Integer> mobis, Set<Integer> credits) {
        boolean m = false, c = false;
        for (Integer i : items) { m |= mobis.contains(i); c |= credits.contains(i); }
        return TRANSACTION.equals(classe) ? m && c : m;
    }

    /** Le nom du coffre a se procurer, d'apres la furnidata (« Coffre à mobis wired »...). */
    static String nomCoffre(String classe) {
        Furnidata.Mobi d = Salle.details(classe);
        return d != null && d.name != null && !d.name.isBlank() ? d.name : classe;
    }

    // ================================================================ droits

    /** null si on peut poser et regler les wired ici, sinon la phrase. Droits inconnus : on essaie. */
    static String refusDroits() {
        Moteur gp = Salle.gp();
        Droits d = gp == null ? null : gp.getPermissions();
        if (d == null || d.niveau() < 0) return null;
        if (!d.peutDeplacer())
            return "Tu n'as pas les droits pour poser des mobis dans cet appart : les coffres de la boutique troc n'ont pas été posés.";
        if (!d.peutRegler())
            return "Tu n'as pas les droits pour régler les wired dans cet appart : les coffres de la boutique troc n'ont pas été posés.";
        return null;
    }

    // ================================================================ pose des coffres manquants

    /**
     * Pose les coffres qui manquent (les wired de vente sont la), puis relie
     * les wired. Rien a faire (coffres deja la) : Resultat(0, 0, null), sans
     * lecture des wired. Fil de travail.
     */
    static synchronized Resultat completer(List<HInventoryItem> inventaire) {   // une pose a la fois (onglet et vente)
        int salle = Salle.salleId();
        if (salle == -1) return new Resultat(0, 0, "Tu n'es pas dans un appart.");
        TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
        if (c.complets()) return new Resultat(0, 0, null);
        String droits = refusDroits();
        if (droits != null) return new Resultat(0, 0, droits);

        List<HInventoryItem> inv = inventaire;
        if (inv == null || inv.isEmpty()) {
            Moteur gp = Salle.gp();
            try { inv = gp == null ? List.of() : new ArrayList<>(gp.getInventory().getInventoryItems()); }
            catch (Throwable t) { inv = List.of(); }
        }
        if (inv.isEmpty())
            return new Resultat(0, 0, "L'Atelier ne connaît pas encore ton inventaire : ferme et rouvre l'inventaire du jeu, puis réessaie.");
        HInventoryItem mob = c.mobis() != 0 ? null : coffreDansInventaire(inv, TrocCoffres.CLASSES_MOBIS, t -> Salle.classe(t, false));
        HInventoryItem cre = c.credits() != 0 ? null : coffreDansInventaire(inv, TrocCoffres.CLASSES_CREDITS, t -> Salle.classe(t, false));
        List<String> manque = new ArrayList<>();
        if (c.mobis() == 0 && mob == null) manque.add("un coffre à mobis (« " + nomCoffre(TrocCoffres.COFFRE_MOBIS) + " »)");
        if (c.credits() == 0 && cre == null) manque.add("un coffre à crédits (« " + nomCoffre(TrocCoffres.COFFRE_CREDITS) + " »)");
        if (!manque.isEmpty())
            return new Resultat(0, 0, "Il te faut " + String.join(" et ", manque) + " dans ton inventaire pour la boutique troc "
                    + ": les coffres ne sont pas au Builders Club. Après l'achat, ferme et rouvre l'inventaire du jeu.");

        int[] pres = pointBoutique();
        if (pres == null) return new Resultat(0, 0, "Les wired de la boutique troc ne sont pas dans cet appart.");
        Set<Long> prises = new HashSet<>();
        int poses = 0;
        for (HInventoryItem it : Arrays.asList(mob, cre)) {
            if (it == null) continue;
            if (Salle.salleId() != salle) return new Resultat(poses, 0, "Tu as changé d'appart : coffres non posés.");
            String err = poser(it, pres, prises);
            if (err != null) return new Resultat(poses, 0, err);
            poses++;
        }
        Resultat r = relier();
        return new Resultat(poses, r.relies(), r.erreur());
    }

    /** La case des wired de la boutique (le wired de transaction, sinon l'analyse du coffre) ; null s'ils manquent. */
    private static int[] pointBoutique() {
        int[] repli = null;
        for (HFloorItem it : Salle.sols()) {
            String k = ConfigTroc.classeWired(Salle.classe(it.getTypeId(), false));
            if (TRANSACTION.equals(k)) return new int[]{it.getTile().getX(), it.getTile().getY()};
            if ((SCAN.equals(k) || CONTRAT.equals(k)) && repli == null) repli = new int[]{it.getTile().getX(), it.getTile().getY()};
        }
        return repli;
    }

    /** Case du plan sans aucun mobi (rien a empiler sur une boite wired ou un tapis). */
    private static boolean caseVide(int x, int y, List<HFloorItem> sols) {
        if (Salle.hauteurSol(x, y) < 0) return false;
        for (HFloorItem it : sols) {
            int[] e = Salle.emprise(it);
            int ix = it.getTile().getX(), iy = it.getTile().getY();
            if (x >= ix && y >= iy && x < ix + e[0] && y < iy + e[1]) return false;
        }
        return true;
    }

    /** Les cases vides de cette taille autour de (ax, ay), les plus proches d'abord (rayon 8). */
    private static List<int[]> casesLibres(int ax, int ay, int lx, int ly, Set<Long> prises) {
        List<HFloorItem> sols = Salle.sols();
        List<int[]> r = new ArrayList<>();
        for (int d = 1; d <= 8 && r.size() < 4; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    int x = ax + dx, y = ay + dy;
                    if (x < 0 || y < 0) continue;
                    boolean ok = true;
                    for (int i = 0; i < lx && ok; i++)
                        for (int j = 0; j < ly && ok; j++)
                            ok = !prises.contains(PoseDalle.cle(x + i, y + j)) && caseVide(x + i, y + j, sols)
                                    && Salle.hauteurSol(x + i, y + j) == Salle.hauteurSol(x, y);
                    if (ok) r.add(new int[]{x, y});
                }
        r.sort(Comparator.comparingInt(c -> (c[0] - ax) * (c[0] - ax) + (c[1] - ay) * (c[1] - ay)));
        return r;
    }

    /** Pose ce coffre de l'inventaire sur une case vide pres de la boutique ; null si c'est fait, sinon l'erreur. */
    private static String poser(HInventoryItem it, int[] pres, Set<Long> prises) {
        int type = it.getTypeId();
        String classe = Salle.classe(type, false);
        Furnidata.Mobi d = Salle.details(classe);
        int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
        List<int[]> cases = casesLibres(pres[0], pres[1], lx, ly, prises);
        String nom = nomCoffre(classe);
        if (cases.isEmpty()) return "Pas de case libre près des wired de la boutique troc pour poser « " + nom + " ».";
        PoseOutils.Signaux.installer();
        String raison = null;
        for (int k = 0; k < Math.min(3, cases.size()); k++) {
            int x = cases.get(k)[0], y = cases.get(k)[1];
            Set<Integer> avant = new HashSet<>();
            for (HFloorItem f : Salle.sols()) avant.add(f.getId());
            long t0 = System.currentTimeMillis();
            Salle.envoyerEspace(PoseOutils.poseSolInventaire(it.getId(), x, y, 0));
            boolean vu = PoseOutils.attendre(() -> {
                for (HFloorItem f : Salle.sols()) if (!avant.contains(f.getId()) && f.getTypeId() == type) return true;
                return false;
            }, 4000, null);
            if (vu) {
                for (int i = 0; i < lx; i++) for (int j = 0; j < ly; j++) prises.add(PoseDalle.cle(x + i, y + j));
                return null;
            }
            raison = PoseOutils.Signaux.raisonPose(t0, x, y);
            Journal.debug("Troc : « " + nom + " » refusé en (" + x + "," + y + ")" + (raison == null ? "" : " : " + raison));
        }
        return "« " + nom + " » n'a pas pu être posé" + (raison == null ? "" : " : " + raison) + ".";
    }

    // ================================================================ relier les wired aux coffres

    /** Le reglage de ce wired dans TrocModele (ids de la copie) ; null si la classe n'y est pas. */
    private static ReglageWired modele(String classe) {
        for (WiredCollage.Piece p : TrocModele.copie().pieces)
            if (classe.equals(p.classe) && p.config != null) {
                ReglageWired.Genre g = ReglageWired.Genre.deClasse(classe);
                return g == null ? null : ReglageWired.depuisJson(g, p.config);
            }
        return null;
    }

    /**
     * Relie aux coffres de l'appart les wired de vente qui ne les designent
     * plus. Resultat(0, n, null) : n wired relies (0 = tout etait deja bon).
     * Fil de travail.
     */
    static Resultat relier() {
        TrocCoffres.Coffres c = TrocCoffres.dansLaSalle();
        if (!c.complets()) return new Resultat(0, 0, "Les deux coffres de la boutique troc ne sont pas dans l'appart.");
        Set<Integer> mobis = new HashSet<>(), credits = new HashSet<>();
        List<HFloorItem> contrats = new ArrayList<>();
        Map<Integer, String> wired = new LinkedHashMap<>();
        Map<Integer, HFloorItem> parId = new HashMap<>();
        for (HFloorItem it : Salle.sols()) {
            String k0 = Salle.classe(it.getTypeId(), false), k = ConfigTroc.classeWired(k0);
            int g = TrocCoffres.genreCoffre(k0);
            if (g == 1) mobis.add(it.getId());
            else if (g == 2) credits.add(it.getId());
            else if (TRANSACTION.equals(k) || SCAN.equals(k)) { wired.put(it.getId(), k); parId.put(it.getId(), it); }
            else if (CONTRAT.equals(k)) contrats.add(it);
        }
        if (wired.isEmpty()) return new Resultat(0, 0, "Les wired de la boutique troc ne sont pas dans cet appart.");
        Map<Integer, WiredLecteur.Config> lus = WiredLecteur.lireMaintenant(wired.keySet(), () -> false, null, true);
        List<ReglageWired> aEnvoyer = new ArrayList<>();
        String ctx = null;
        for (Map.Entry<Integer, String> e : wired.entrySet()) {
            WiredLecteur.Config lu = lus.get(e.getKey());
            if (lu == null) return new Resultat(0, 0, "Les wired de la boutique troc n'ont pas pu être lus : réessaie.");
            if (relie(e.getValue(), lu.items, mobis, credits)) continue;
            ReglageWired m = modele(e.getValue());
            if (m == null) continue;
            if (ctx == null) ctx = ConfigTroc.idCtx(2500);
            if (ctx == null) return new Resultat(0, 0, "Variable « ctx » introuvable : la boutique troc est incomplète.");
            m.wiredId = e.getKey();
            if (TRANSACTION.equals(e.getValue())) {
                m.items = new ArrayList<>(List.of(c.credits(), c.mobis()));
                // le contrat : celui que le wired designe encore, sinon le plus proche
                List<Integer> k = new ArrayList<>();
                Set<Integer> idsContrats = new HashSet<>();
                for (HFloorItem f : contrats) idsContrats.add(f.getId());
                for (Integer i : lu.items2) if (idsContrats.contains(i)) k.add(i);
                if (k.isEmpty()) {
                    HFloorItem w = parId.get(e.getKey()), proche = null;
                    long best = Long.MAX_VALUE;
                    for (HFloorItem f : contrats) {
                        long dx = f.getTile().getX() - w.getTile().getX(), dy = f.getTile().getY() - w.getTile().getY();
                        if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; proche = f; }
                    }
                    if (proche == null) return new Resultat(0, 0, "Le contrat de la boutique troc n'est pas dans l'appart.");
                    k.add(proche.getId());
                }
                m.items2 = k;
            } else {
                m.items = new ArrayList<>(List.of(c.mobis()));
            }
            List<String> v = new ArrayList<>();
            for (String id : m.variables) v.add(TrocModele.V_CTX.equals(id) ? ctx : id);
            m.variables = v;
            aEnvoyer.add(m);
        }
        if (aEnvoyer.isEmpty()) return new Resultat(0, 0, null);
        Canal canal = Canal.duMoteur();
        if (canal == null) return new Resultat(0, 0, "L'Atelier n'est pas encore prêt.");
        for (ReglageWired m : aEnvoyer) {
            if (!PoseOutils.envoyer(canal, m.paquetUpdate()))
                return new Resultat(0, 0, "Les wired de la boutique troc n'ont pas pu être reliés aux coffres.");
            Salle.sommeil(400);
        }
        // verification : relecture forcee
        Salle.sommeil(800);
        List<Integer> ids = new ArrayList<>();
        for (ReglageWired m : aEnvoyer) ids.add(m.wiredId);
        Map<Integer, WiredLecteur.Config> apres = WiredLecteur.lireMaintenant(ids, () -> false, null, true);
        for (ReglageWired m : aEnvoyer) {
            WiredLecteur.Config lu = apres.get(m.wiredId);
            if (lu == null || !relie(wired.get(m.wiredId), lu.items, mobis, credits))
                return new Resultat(0, 0, "Le jeu n'a pas pris le nouveau réglage des wired de la boutique troc.");
        }
        return new Resultat(0, aEnvoyer.size(), null);
    }
}

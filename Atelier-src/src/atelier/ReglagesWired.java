package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;

import java.util.*;

/**
 * Reglages des wired d'une copie, appliques sur des wired DEJA poses
 * (PoseDirecte, PoseHybride), sans dalle magique ni pose de mobis.
 *
 * Le travail est fait par ReglageWiredPose (brique du moteur) : seuls les wired
 * dont l'id de la copie est dans la table (copie -> reel) sont regles, dans
 * l'ordre variables, conditions, effets, declencheurs, add-ons, selecteurs,
 * add-ons ; chaque enregistrement attend WiredSaveSuccess (3 essais) ; la
 * table des variables se remplit par la liste de la salle.
 *
 * Ici, en plus, comme avant :
 *   - les liaisons deja satisfaites (mobi deja a sa case, sa rotation, son
 *     altitude, son etat) sont videes : sinon le mobi serait deplace sans
 *     pouvoir retrouver son altitude (pas de dalle magique ici) ;
 *   - les mobis bouges par les liaisons reprennent ensuite leur place (et leur
 *     altitude par @altitude) ;
 *   - les fonds des publicites, puis les valeurs des variables portees par les
 *     mobis de sol ;
 *   - quelques wired relus et compares a la copie.
 * Pendant le reglage, la lecture automatique des wired attend (PoseCopie.prendre).
 */
final class ReglagesWired {

    private ReglagesWired() { }

    /** Resultat des reglages. possible = false : rien n'a ete tente (raison dite). */
    static final class Bilan {
        boolean possible;
        String raison;
        /** wired a regler (ceux dont le mobi a ete pose), regles (confirmes), rates */
        int attendus, regles, rates;
        /** verification : wired relus, et ceux qui ne correspondent pas */
        int relus, relusDifferents;
        static Bilan echec(String raison) { Bilan b = new Bilan(); b.raison = raison; return b; }
    }

    /**
     * Applique les reglages des wired de la copie sur les wired poses.
     * @param presetVersReel id de la copie -> id reel (PoseDirecte.Resultat.cles)
     * @param racine coin de la copie (liaisons des wired « instantane ») ; null =
     *               deduit d'un mobi pose
     * Ne lance aucune exception.
     */
    static Bilan appliquer(CopieAppart cfg, Map<Integer, Integer> presetVersReel, HPoint racine) {
        try {
            return appliquer0(cfg, presetVersReel, racine);
        } catch (Throwable t) {
            Journal.debug("reglages wired : " + t);
            return Bilan.echec("erreur (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static Bilan appliquer0(CopieAppart cfg, Map<Integer, Integer> presetVersReel, HPoint racine) {
        Moteur gp = Salle.gp();
        ReglageWiredPose rw = gp == null ? null : gp.reglageWired();
        if (rw == null || cfg == null || presetVersReel == null) return Bilan.echec("Atelier pas prêt");
        if (cfg.tousWired().isEmpty()) return Bilan.echec("pas de wired dans la copie");

        if (racine == null) racine = deduireRacine(cfg, presetVersReel);
        if (racine == null) return Bilan.echec("aucun mobi posé");

        // les wired a regler : ceux dont le mobi est pose
        List<ReglageWired> tous = cfg.tousWired();
        Set<Integer> wiredPreset = new LinkedHashSet<>();
        for (ReglageWired w : tous) if (presetVersReel.containsKey(w.wiredId)) wiredPreset.add(w.wiredId);
        Bilan b = new Bilan();
        b.possible = true;
        b.attendus = wiredPreset.size();
        if (wiredPreset.isEmpty()) return b;

        // copie de travail ; liaisons deja satisfaites videes
        CopieAppart travail = cfg.copie();
        travail.liaisons.clear();
        int videes = 0;
        for (CopieAppart.Liaison l : cfg.liaisons) {
            CopieAppart.Liaison n = liaisonUtile(l, presetVersReel, racine);
            if (n == null) { videes++; continue; }
            travail.liaisons.add(n);
        }
        Journal.debug("reglages wired : " + wiredPreset.size() + " wired, " + travail.liaisons.size() + " liaison(s) a faire, "
                + videes + " deja satisfaite(s)");

        // places de tous les mobis poses, pour reparer apres (deplacements des liaisons)
        Map<Integer, double[]> avant = new HashMap<>();
        for (int id : presetVersReel.values()) {
            HFloorItem it = Salle.sol(id);
            if (it != null) avant.put(id, new double[]{it.getTile().getX(), it.getTile().getY(),
                    it.getTile().getZ(), Salle.rotation(it)});
        }
        // verification : quelques wired pas encore lus par WiredLecteur (sinon sa relecture serait l'ancienne)
        List<ReglageWired> aRelire = new ArrayList<>();
        for (ReglageWired w : tous) {
            if (aRelire.size() >= 4) break;
            Integer reel = presetVersReel.get(w.wiredId);
            if (reel == null || w.genre == ReglageWired.Genre.VARIABLE) continue;
            HFloorItem it = Salle.sol(reel);
            String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
            if (cls == null || WiredCollage.A_LIAISONS.contains(cls)) continue;
            if (WiredLecteur.config(reel) != null) continue;
            aRelire.add(w);
        }

        if (!PoseCopie.prendre()) return Bilan.echec("une autre pose est en cours");
        Map<String, String> table = new HashMap<>();
        ReglageWiredPose.Bilan wb;
        int salle0 = Salle.salleId();
        java.util.function.BooleanSupplier stop = () -> PoseCopie.arretDemande() || Salle.salleId() != salle0;
        try {
            wb = rw.appliquer(travail, presetVersReel, table, racine, null, stop);
        } finally {
            PoseCopie.rendre();
        }
        if (wb.erreur != null) return Bilan.echec(wb.erreur);
        b.regles = wb.confirmes.size();
        b.rates = Math.max(0, b.attendus - b.regles);
        if (b.rates > 0) Journal.debug("reglages wired : sans confirmation (id de la copie) " + wb.rates.keySet());

        reparer(avant);
        rw.appliquerFonds(cfg, presetVersReel, null);
        int v = PoseCopie.variablesSols(gp, cfg, presetVersReel, table, () -> false);
        if (v > 0) Journal.debug("reglages wired : " + v + " valeur(s) de variables envoyee(s) aux mobis");
        verifier(aRelire, presetVersReel, b);
        return b;
    }

    /** Le coin de la copie, deduit d'un mobi pose (case reelle - case de la copie). */
    private static HPoint deduireRacine(CopieAppart cfg, Map<Integer, Integer> presetVersReel) {
        for (CopieAppart.MobiSol pf : cfg.sols) {
            Integer id = presetVersReel.get(pf.id);
            HFloorItem it = id == null ? null : Salle.sol(id);
            if (it == null) continue;
            return new HPoint(it.getTile().getX() - pf.x, it.getTile().getY() - pf.y);
        }
        return null;
    }

    /**
     * La liaison, sans ce qui est deja satisfait par le mobi pose ; null s'il
     * n'y a plus rien a faire (ou si le mobi n'est pas pose).
     */
    private static CopieAppart.Liaison liaisonUtile(CopieAppart.Liaison l, Map<Integer, Integer> presetVersReel,
                                                    HPoint racine) {
        Integer id = presetVersReel.get(l.mobiId);
        HFloorItem it = id == null ? null : Salle.sol(id);
        if (it == null) return null;                 // le reglage l'ignorerait aussi
        Integer x = l.x, y = l.y, rot = l.rotation, alt = l.altitude;
        String etat = l.etat;
        boolean bonneCase = !l.aPosition() || (l.x + racine.getX() == it.getTile().getX()
                && l.y + racine.getY() == it.getTile().getY());
        boolean bonneRot = rot == null || rot == Salle.rotation(it);
        boolean bonneAlt = alt == null || Math.abs(it.getTile().getZ() * 100 - alt) <= 1;
        boolean bonEtat = etat == null || etat.equals(Generateur.etatDe(it));
        if (bonneCase && bonneRot && bonneAlt) { x = null; y = null; rot = null; alt = null; }
        if (bonEtat) etat = null;
        if (x == null && rot == null && alt == null && etat == null) return null;
        return new CopieAppart.Liaison(l.mobiId, l.wiredId, x, y, rot, etat, alt);
    }

    /** Les mobis que les liaisons ont bouges (case, rotation, altitude) reprennent leur place. */
    private static void reparer(Map<Integer, double[]> avant) {
        Salle.sommeil(300);
        int n = 0;
        for (Map.Entry<Integer, double[]> e : avant.entrySet()) {
            HFloorItem it = Salle.sol(e.getKey());
            if (it == null) continue;
            double[] a = e.getValue();
            boolean place = it.getTile().getX() != (int) a[0] || it.getTile().getY() != (int) a[1]
                    || Salle.rotation(it) != (int) a[3];
            if (place) {
                Salle.espacer();
                Salle.deplacerSol(e.getKey(), (int) a[0], (int) a[1], (int) a[3]);
                Salle.sommeil(200);
                it = Salle.sol(e.getKey());
                if (it == null) continue;
            }
            if (place || Math.abs(it.getTile().getZ() - a[2]) > 0.01) {
                Salle.espacer();
                try { OutilMiroir.Altitude.mettre(e.getKey(), a[2]); } finally { Salle.envoiFait(); }
                n++;
            }
        }
        if (n > 0) Journal.debug("reglages wired : " + n + " mobi(s) remis a leur place apres les liaisons");
    }

    /** Relit quelques wired regles et les compare a la copie (options, texte, mobis choisis). */
    private static void verifier(List<ReglageWired> aRelire, Map<Integer, Integer> presetVersReel, Bilan b) {
        if (aRelire.isEmpty()) return;
        try {
            List<Integer> ids = new ArrayList<>();
            for (ReglageWired w : aRelire) ids.add(presetVersReel.get(w.wiredId));
            Map<Integer, WiredLecteur.Config> lus = WiredLecteur.lireMaintenant(ids, () -> false, (x, y) -> { });
            for (ReglageWired w : aRelire) {
                int reel = presetVersReel.get(w.wiredId);
                WiredLecteur.Config c = lus.get(reel);
                if (c == null) continue;
                b.relus++;
                List<String> ecarts = new ArrayList<>();
                if (!Objects.equals(new ArrayList<>(w.options), new ArrayList<>(c.options))) ecarts.add("options");
                String t = w.texte == null ? "" : w.texte;
                if (!t.equals(c.texte)) ecarts.add("texte");
                if (!memes(w.items, c.items, presetVersReel)) ecarts.add("mobis choisis");
                if (!memes(w.items2, c.items2, presetVersReel)) ecarts.add("seconde sélection");
                if (!ecarts.isEmpty()) {
                    b.relusDifferents++;
                    Journal.debug("reglages wired : wired " + reel + " relu different (" + String.join(", ", ecarts) + ")");
                }
            }
            Journal.debug("reglages wired : " + b.relus + " relu(s), " + b.relusDifferents + " different(s)");
        } catch (Throwable t) {
            Journal.debug("reglages wired : verification -> " + t);
        }
    }

    private static boolean memes(List<Integer> origine, List<Integer> lus, Map<Integer, Integer> presetVersReel) {
        Set<Integer> attendu = new HashSet<>();
        if (origine != null) for (Integer i : origine) { Integer n = presetVersReel.get(i); if (n != null) attendu.add(n); }
        return attendu.equals(new HashSet<>(lus == null ? List.of() : lus));
    }
}

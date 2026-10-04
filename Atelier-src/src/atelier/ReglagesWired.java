package atelier;

import extension.GPresets;
import extension.tools.GPresetImporter;
import extension.tools.presetconfig.PresetConfig;
import extension.tools.presetconfig.binding.PresetWiredFurniBinding;
import extension.tools.presetconfig.furni.PresetFurni;
import extension.tools.presetconfig.furni.PresetWallFurni;
import extension.tools.presetconfig.wired.PresetWiredBase;
import extension.tools.presetconfig.wired.PresetWireds;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Reglages des wired d'un preset, appliques par le moteur de pose sur des
 * wired DEJA poses (PoseDirecte), sans dalle magique ni pose de mobis.
 *
 * Le moteur (GPresetImporter, v1.3.8) regle les wired dans sa methode privee
 * setupWired() : elle lit workingPresetConfig, ne garde que les wired dont le
 * furniId du preset est dans realFurniIdMap (furniId du preset -> id reel),
 * et les enregistre un par un (saveWired : paquets Update*, attente de
 * WiredSaveSuccess, 3 essais). Les variables wired creees par l'utilisatrice
 * (wf_var_*) sont enregistrees en premier ; ensuite le moteur lit la liste des
 * variables de la salle (WiredAllVariablesDiffs, ecoute seulement dans l'etat
 * SETUP_WIRED) et remplit realVariableIdMap. Tout ca ne depend que de :
 * workingPresetConfig, realFurniIdMap, realVariableIdMap, needVariableIds,
 * rootLocation (liaisons des wired « instantane ») et state = SETUP_WIRED.
 * On les remplit par reflexion, on lance setupWired() sur un fil a part, puis
 * reset() remet le moteur a NONE.
 *
 * Ce que le moteur fait APRES setupWired (moveFurniture) et qu'on ne fait pas
 * ici : les valeurs de variables portees par les mobis (pf.getVariables(),
 * hors « @ »). Un preset qui en a doit garder l'ancien chemin : voir nonGere().
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

    // -------------------------------------------------------------- reflexion

    private static final String[] CHAMPS = {"lock", "workingPresetConfig", "realFurniIdMap", "realVariableIdMap",
            "needVariableIds", "state", "rootLocation", "allAvailableStackTiles", "stateChangerLocation",
            "stackTileLocation"};

    /** Les champs et la methode du moteur ; null si la version du jar ne les a pas. */
    private static Map<String, Field> champs(StringBuilder erreur) {
        Map<String, Field> m = new HashMap<>();
        for (String n : CHAMPS) {
            try {
                Field f = GPresetImporter.class.getDeclaredField(n);
                f.setAccessible(true);
                m.put(n, f);
            } catch (Throwable t) {
                erreur.append("champ ").append(n).append(" introuvable");
                return null;
            }
        }
        return m;
    }

    private static Method setupWired(StringBuilder erreur) {
        try {
            Method mt = GPresetImporter.class.getDeclaredMethod("setupWired");
            mt.setAccessible(true);
            return mt;
        } catch (Throwable t) {
            erreur.append("methode setupWired introuvable");
            return null;
        }
    }

    /**
     * A appeler AVANT toute pose : null si les reglages pourront etre appliques
     * sans le moteur de pose complet ; sinon la raison (l'appelant garde alors
     * l'ancien chemin, avec la dalle magique).
     */
    static String nonGere(PresetConfig cfg) {
        StringBuilder e = new StringBuilder();
        if (champs(e) == null || setupWired(e) == null) return "moteur de pose d'une autre version (" + e + ")";
        // valeurs de variables portees par les mobis : le moteur les pose dans
        // moveFurniture (apres setupWired), on ne sait pas les refaire ici
        for (PresetFurni pf : cfg.getFurniture())
            if (variablesUtilisatrice(pf.getVariables())) return "des mobis portent des valeurs de variables wired";
        if (cfg.getWallFurniture() != null)
            for (PresetWallFurni pw : cfg.getWallFurniture())
                if (variablesUtilisatrice(pw.getVariables())) return "des muraux portent des valeurs de variables wired";
        return null;
    }

    private static boolean variablesUtilisatrice(Map<String, Integer> v) {
        if (v == null) return false;
        for (String n : v.keySet()) if (n != null && !n.isEmpty() && !n.startsWith("@")) return true;
        return false;
    }

    // ------------------------------------------------- suivi des confirmations

    /**
     * realFurniIdMap espion : saveWired -> applyWiredConfig fait d'abord
     * containsKey(wiredId) puis get(wiredId) ; on retient ce wired comme celui
     * en cours d'enregistrement, et le WiredSaveSuccess qui suit lui revient.
     */
    private static final class TableEspion extends HashMap<Integer, Integer> {
        final Set<Integer> wired;
        volatile Integer candidat, courant;
        TableEspion(Map<Integer, Integer> m, Set<Integer> wired) { super(m); this.wired = wired; }
        @Override public boolean containsKey(Object k) {
            candidat = k instanceof Integer ? (Integer) k : null;
            return super.containsKey(k);
        }
        @Override public Integer get(Object k) {
            if (k != null && k.equals(candidat) && wired.contains(k)) { courant = (Integer) k; candidat = null; }
            return super.get(k);
        }
    }

    private static volatile TableEspion enCours = null;
    private static final Set<Integer> confirmes = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean ecoute = false;

    private static synchronized void ecouter(GPresets gp) {
        if (ecoute) return;
        gp.intercept(HMessage.Direction.TOCLIENT, "WiredSaveSuccess", m -> {
            TableEspion t = enCours;
            if (t != null && t.courant != null) confirmes.add(t.courant);
        });
        ecoute = true;
    }

    // ------------------------------------------------------------- reglages

    static Bilan appliquer(GPresets gp, GPresetImporter imp, PresetConfig cfg, Map<Integer, Integer> presetVersReel) {
        return appliquer(gp, imp, cfg, presetVersReel, null);
    }

    /**
     * Applique les reglages des wired du preset sur les wired poses.
     * @param presetVersReel furniId du preset -> id reel (PoseDirecte.Resultat.cles)
     * @param racine coin de la copie (liaisons des wired « instantane ») ; null =
     *               deduit d'un mobi pose
     * Ne lance aucune exception.
     */
    static Bilan appliquer(GPresets gp, GPresetImporter imp, PresetConfig cfg,
                           Map<Integer, Integer> presetVersReel, HPoint racine) {
        try {
            return appliquer0(gp, imp, cfg, presetVersReel, racine);
        } catch (Throwable t) {
            Journal.debug("reglages wired : " + t);
            return Bilan.echec("erreur (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static Bilan appliquer0(GPresets gp, GPresetImporter imp, PresetConfig cfg,
                                    Map<Integer, Integer> presetVersReel, HPoint racine) throws Exception {
        if (gp == null || imp == null || cfg == null || presetVersReel == null) return Bilan.echec("Atelier pas prêt");
        StringBuilder err = new StringBuilder();
        Map<String, Field> f = champs(err);
        Method setup = f == null ? null : setupWired(err);
        if (f == null || setup == null) return Bilan.echec("moteur de pose d'une autre version (" + err + ")");
        if (imp.getState() != GPresetImporter.BuildingImportState.NONE)
            return Bilan.echec("une autre pose est en cours");
        PresetWireds pw = cfg.getPresetWireds();
        if (pw == null) return Bilan.echec("pas de wired dans la copie");

        if (racine == null) racine = deduireRacine(cfg, presetVersReel);
        if (racine == null) return Bilan.echec("aucun mobi posé");

        // les wired a regler : ceux dont le mobi est pose (meme filtre que setupWired)
        List<PresetWiredBase> tous = new ArrayList<>();
        tous.addAll(pw.getVariables()); tous.addAll(pw.getConditions()); tous.addAll(pw.getEffects());
        tous.addAll(pw.getTriggers()); tous.addAll(pw.getAddons()); tous.addAll(pw.getSelectors());
        Set<Integer> wiredReels = new LinkedHashSet<>();
        Set<Integer> wiredPreset = new LinkedHashSet<>();
        for (PresetWiredBase w : tous) {
            Integer reel = presetVersReel.get(w.getWiredId());
            if (reel != null && wiredPreset.add(w.getWiredId())) wiredReels.add(reel);
        }
        Bilan b = new Bilan();
        b.possible = true;
        b.attendus = wiredPreset.size();
        if (wiredPreset.isEmpty()) return b;

        // copie de travail ; liaisons deja satisfaites (mobi deja a sa place, son
        // etat, sa rotation, son altitude) videes : sinon le moteur le deplacerait
        // sans pouvoir lui rendre son altitude (il n'a pas de dalle magique ici)
        List<PresetWiredFurniBinding> liaisons = new ArrayList<>();
        int videes = 0;
        for (PresetWiredFurniBinding l : cfg.getBindings()) {
            PresetWiredFurniBinding n = liaisonUtile(l, presetVersReel, racine);
            if (n == null) { videes++; continue; }
            liaisons.add(n);
        }
        PresetConfig travail = new PresetConfig(cfg.getFurniture(), cfg.getWallFurniture(), pw, liaisons,
                new ArrayList<>());
        Journal.debug("reglages wired : " + wiredPreset.size() + " wired, " + liaisons.size() + " liaison(s) a faire, "
                + videes + " deja satisfaite(s)");

        // places de tous les mobis poses, pour reparer apres (deplacements des liaisons)
        Map<Integer, double[]> avant = new HashMap<>();
        for (int id : presetVersReel.values()) {
            HFloorItem it = Salle.sol(id);
            if (it != null) avant.put(id, new double[]{it.getTile().getX(), it.getTile().getY(),
                    it.getTile().getZ(), Salle.rotation(it)});
        }
        // verification : quelques wired pas encore lus par WiredLecteur (sinon sa relecture serait l'ancienne)
        List<PresetWiredBase> aRelire = new ArrayList<>();
        for (PresetWiredBase w : tous) {
            if (aRelire.size() >= 4) break;
            Integer reel = presetVersReel.get(w.getWiredId());
            if (reel == null || w instanceof extension.tools.presetconfig.wired.PresetWiredVariable) continue;
            HFloorItem it = Salle.sol(reel);
            String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
            if (cls == null || WiredCollage.A_LIAISONS.contains(cls)) continue;
            if (WiredLecteur.config(reel) != null) continue;
            aRelire.add(w);
        }

        ecouter(gp);
        TableEspion table = new TableEspion(presetVersReel, wiredPreset);
        Object verrou = f.get("lock").get(imp);
        confirmes.clear();
        synchronized (verrou) {
            f.get("workingPresetConfig").set(imp, travail);
            f.get("realFurniIdMap").set(imp, table);
            f.get("realVariableIdMap").set(imp, new HashMap<String, String>());
            f.get("needVariableIds").set(imp, new ArrayList<String>());
            f.get("rootLocation").set(imp, racine);
            f.get("allAvailableStackTiles").set(imp, new ArrayList<>());   // pas de dalle : jamais de dalle a deplacer
            f.get("stateChangerLocation").set(imp, null);
            f.get("stackTileLocation").set(imp, null);
            enCours = table;
            f.get("state").set(imp, GPresetImporter.BuildingImportState.SETUP_WIRED);
        }

        Throwable[] erreur = {null};
        Thread fil = new Thread(() -> {
            try { setup.invoke(imp); }
            catch (java.lang.reflect.InvocationTargetException e) { erreur[0] = e.getCause(); }
            catch (Throwable t) { erreur[0] = t; }
        }, "atelier-reglages-wired");
        fil.setDaemon(true);
        try {
            fil.start();
            // les add-ons passent deux fois dans setupWired ; au pire 3 essais de 5 s chacun
            long limite = 60_000L + 20_000L * (tous.size() + pw.getAddons().size());
            fil.join(limite);
            if (fil.isAlive()) {
                Journal.debug("reglages wired : trop long, arret");
                synchronized (verrou) { f.get("state").set(imp, GPresetImporter.BuildingImportState.NONE); }
                fil.join(20_000);
            }
        } finally {
            enCours = null;
            try { imp.reset(); } catch (Throwable t) {
                try { f.get("state").set(imp, GPresetImporter.BuildingImportState.NONE); } catch (Throwable ignored) { }
            }
        }
        if (erreur[0] != null) Journal.debug("reglages wired : setupWired -> " + erreur[0]);

        Set<Integer> ok;
        synchronized (confirmes) { ok = new HashSet<>(confirmes); }
        ok.retainAll(wiredPreset);
        b.regles = ok.size();
        b.rates = b.attendus - b.regles;
        if (b.rates > 0) {
            List<Integer> rates = new ArrayList<>(wiredPreset);
            rates.removeAll(ok);
            Journal.debug("reglages wired : sans confirmation (furniId du preset) " + rates);
        }

        reparer(avant);
        fonds(gp, cfg, presetVersReel);
        verifier(aRelire, presetVersReel, b);
        return b;
    }

    /** Le coin de la copie, deduit d'un mobi pose (case reelle - case du preset). */
    private static HPoint deduireRacine(PresetConfig cfg, Map<Integer, Integer> presetVersReel) {
        for (PresetFurni pf : cfg.getFurniture()) {
            Integer id = presetVersReel.get(pf.getFurniId());
            HFloorItem it = id == null ? null : Salle.sol(id);
            if (it == null || pf.getLocation() == null) continue;
            return new HPoint(it.getTile().getX() - pf.getLocation().getX(), it.getTile().getY() - pf.getLocation().getY());
        }
        return null;
    }

    /**
     * La liaison, sans ce qui est deja satisfait par le mobi pose ; null s'il
     * n'y a plus rien a faire (ou si le mobi n'est pas pose).
     */
    private static PresetWiredFurniBinding liaisonUtile(PresetWiredFurniBinding l, Map<Integer, Integer> presetVersReel,
                                                        HPoint racine) {
        Integer id = presetVersReel.get(l.getFurniId());
        HFloorItem it = id == null ? null : Salle.sol(id);
        if (it == null) return null;                 // le moteur l'ignorerait aussi
        HPoint loc = l.getLocation();
        Integer rot = l.getRotation(), alt = l.getAltitude();
        String etat = l.getState();
        boolean bonneCase = loc == null || (loc.getX() + racine.getX() == it.getTile().getX()
                && loc.getY() + racine.getY() == it.getTile().getY());
        boolean bonneRot = rot == null || rot == Salle.rotation(it);
        boolean bonneAlt = alt == null || Math.abs(it.getTile().getZ() * 100 - alt) <= 1;
        boolean bonEtat = etat == null || etat.equals(Generateur.etatDe(it));
        if (bonneCase && bonneRot && bonneAlt) { loc = null; rot = null; alt = null; }
        if (bonEtat) etat = null;
        if (loc == null && rot == null && alt == null && etat == null) return null;
        return new PresetWiredFurniBinding(l.getFurniId(), l.getWiredId(), loc, rot, etat, alt);
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

    /** Fonds des publicites (« ads_ »), comme le moteur de pose (setupAds). */
    private static void fonds(GPresets gp, PresetConfig cfg, Map<Integer, Integer> presetVersReel) {
        try {
            if (cfg.getAdsBackgrounds() == null) return;
            for (extension.tools.presetconfig.ads_bg.PresetAdsBackground a : cfg.getAdsBackgrounds()) {
                Integer id = presetVersReel.get(a.getFurniId());
                if (id == null) continue;
                Salle.espacer();
                gp.sendToServer(new HPacket("SetObjectData", HMessage.Direction.TOSERVER, id, 8,
                        "imageUrl", a.getImageUrl(), "offsetX", a.getOffsetX(),
                        "offsetY", a.getOffsetY(), "offsetZ", a.getOffsetZ()));
            }
        } catch (Throwable t) {
            Journal.debug("reglages wired : fonds de publicite -> " + t);
        }
    }

    /** Relit quelques wired regles et les compare au preset (options, texte, mobis choisis). */
    private static void verifier(List<PresetWiredBase> aRelire, Map<Integer, Integer> presetVersReel, Bilan b) {
        if (aRelire.isEmpty()) return;
        try {
            List<Integer> ids = new ArrayList<>();
            for (PresetWiredBase w : aRelire) ids.add(presetVersReel.get(w.getWiredId()));
            Map<Integer, WiredLecteur.Config> lus = WiredLecteur.lireMaintenant(ids, () -> false, (x, y) -> { });
            for (PresetWiredBase w : aRelire) {
                int reel = presetVersReel.get(w.getWiredId());
                WiredLecteur.Config c = lus.get(reel);
                if (c == null) continue;
                b.relus++;
                List<String> ecarts = new ArrayList<>();
                if (!Objects.equals(new ArrayList<>(w.getOptions()), new ArrayList<>(c.options))) ecarts.add("options");
                String t = w.getStringConfig() == null ? "" : w.getStringConfig();
                if (!t.equals(c.texte)) ecarts.add("texte");
                if (!memes(w.getItems(), c.items, presetVersReel)) ecarts.add("mobis choisis");
                if (!memes(w.getSecondItems(), c.items2, presetVersReel)) ecarts.add("seconde sélection");
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

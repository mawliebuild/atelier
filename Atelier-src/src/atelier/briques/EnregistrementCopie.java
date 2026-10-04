package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.extensions.parsers.stuffdata.MapStuffData;
import gearth.protocol.HMessage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Enregistrement d'une copie (appart entier ou zone) sans G-Presets
 * (remplace GPresetExporter, que OngletApparts.copierSalleVersAppart pilote
 * aujourd'hui par reflexion). Produit le meme JSON que G-Presets
 * (CopieAppart), ecrit dans le dossier des copies actuelles
 * (OngletApparts.dossierApparts()).
 *
 * Comme l'exporteur (export, attemptExport, requestFurniVariablesInArea) :
 *   - mobis de sol de la zone, case par case ; etat au sens de StateExtractor
 *     (aucun pour les wired) ; fonds des « ads_background » ;
 *   - muraux : TOUS ceux de la salle (comme l'exporteur), altitude reprise de
 *     la variable « -123 » lue par WiredGetVariablesForObject (inspection,
 *     350 ms entre deux, 700 ms d'attente, 3 passes de reprise a 1200 ms) ;
 *   - reglages des wired : lus par WiredLecteur (LecteurReglages), sans
 *     ouvrir de fenetre ; les wired « instantane » rendent leurs liaisons
 *     (texte « id,etat,rot,x,y[,alt];... » de la reponse du serveur, sinon
 *     les options 0 a 3 et la place actuelle des mobis, comme maybeSaveBindings) ;
 *   - variables : la liste de la salle (WiredGetAllVariablesDiffs) donne
 *     « variables_map » (nom -> id, variables de l'utilisatrice) et l'id des
 *     wired variables ; les valeurs portees par les mobis (facultatif, comme la
 *     case « exporter les variables des mobis » de G-Presets, decochee par
 *     defaut) par WiredGetAllVariableHolders ;
 *   - renumerotation 1..n (sols puis muraux), positions relatives au coin de
 *     la zone, noms « classe[n] », disposition (roomLayout, signature du plan)
 *     et hauteur d'ancrage (le sol le plus bas de la zone).
 *
 * Aucun travail sur le fil JavaFX, aucun message dans le jeu.
 */
final class EnregistrementCopie {

    /** Le reglage d'un wired de la salle (id reel, classe), ou null s'il n'est pas connu. */
    interface LecteurReglages { ReglageWired reglage(int id, String classe); }

    /**
     * Les reglages deja lus par WiredLecteur (son cache de la salle). Seul lien
     * de cette brique avec les classes de G-Presets (Config.brut), a remplacer
     * quand WiredLecteur rendra des ReglageWired.
     */
    static LecteurReglages duWiredLecteur() {
        return (id, classe) -> {
            ReglageWired.Genre g = ReglageWired.Genre.deClasse(classe);
            WiredLecteur.Config c = g == null ? null : WiredLecteur.config(id);
            if (c == null || c.brut == null) return null;
            ReglageWired r = ReglageWired.depuisJson(g, c.brut.toJsonObject());
            r.typeId = c.typeId;
            return r;
        };
    }

    /** La zone a copier (coin et dimensions en cases). */
    record Zone(int x, int y, int largeur, int longueur) {
        /** Toute la salle (0, 0, largeur et longueur du plan), comme OngletApparts. */
        static Zone toute(EtatSalle salle) {
            return new Zone(0, 0, Math.max(1, salle.largeurPlan()), Math.max(1, salle.longueurPlan()));
        }
    }

    /** Ce qu'on copie. */
    static final class Options {
        boolean sols = true, murs = true, wired = true;
        /** Valeurs des variables portees par les mobis de sol (noExportFurniVariables de G-Presets, coche par defaut). */
        boolean variablesMobis = false;
        /** Refuser la copie si un wired de la zone n'a pas de reglage connu (comme l'exporteur). */
        boolean exigerWired = true;
    }

    /** Ce que rend l'enregistrement. */
    static final class Bilan {
        CopieAppart copie;
        File fichier;
        /** Wired de la zone sans reglage connu (ids reels). */
        final List<Integer> wiredNonLus = new ArrayList<>();
        /** Mobis dont la classe est inconnue de la furnidata (ignores). */
        int inconnus;
        int murauxInspectes, murauxSansReponse;
        boolean listeVariables;
        String avertissement;
        boolean arrete;
        String erreur;

        String texte() {
            if (erreur != null) return "Copie impossible : " + erreur + ".";
            CopieAppart c = copie;
            StringBuilder b = new StringBuilder("Copie : ");
            b.append(PoseOutils.nombre(c == null ? 0 : c.sols.size(), "mobi", "mobis"));
            b.append(", ").append(PoseOutils.nombre(c == null ? 0 : c.murs.size(), "mural", "muraux"));
            b.append(", ").append(PoseOutils.nombre(c == null ? 0 : c.tousWired().size(), "wired", "wired"));
            if (c != null && !c.liaisons.isEmpty()) b.append(", ").append(PoseOutils.nombre(c.liaisons.size(), "liaison", "liaisons"));
            if (!wiredNonLus.isEmpty()) b.append(", ").append(PoseOutils.nombre(wiredNonLus.size(), "wired non lu", "wired non lus"));
            if (murauxSansReponse > 0)
                b.append(", ").append(PoseOutils.nombre(murauxSansReponse, "mural sans altitude lue", "muraux sans altitude lue"));
            if (arrete) b.append(" (arrêtée)");
            String t = b.append(".").toString();
            return avertissement == null ? t : t + " " + avertissement;
        }
    }

    /** Les wired « instantane » dont les liaisons sont enregistrees (requireBindings). */
    static final Set<String> A_LIAISONS = Set.of("wf_act_match_to_sshot", "wf_cnd_match_snapshot",
            "wf_cnd_not_match_snap", "wf_trg_stuff_state");

    private final Canal canal;
    private final EtatSalle salle;
    private final Furnidata furnidata;
    private final Droits droits;
    private final LecteurReglages lecteur;

    private volatile boolean enCours;
    private final Semaphore listeRecue = new Semaphore(0);
    private final List<PoseOutils.Variable> variablesRecues = new ArrayList<>();
    private final Map<Integer, Map<String, Integer>> inspections = new ConcurrentHashMap<>();
    private volatile int inspectionAttendue = 0;
    private final Semaphore inspectionRecue = new Semaphore(0);
    private final Map<String, PoseOutils.Porteurs> porteurs = new ConcurrentHashMap<>();
    private volatile String porteursAttendus;
    private final Semaphore porteursRecus = new Semaphore(0);

    /** @param droits peut etre null (on ne verifie pas les droits wired) ; lecteur null = WiredLecteur */
    EnregistrementCopie(Canal canal, EtatSalle salle, Furnidata furnidata, Droits droits, LecteurReglages lecteur) {
        this.canal = canal;
        this.salle = salle;
        this.furnidata = furnidata;
        this.droits = droits;
        this.lecteur = lecteur != null ? lecteur : duWiredLecteur();
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "WiredAllVariablesDiffs", m -> {
            if (!enCours) return;
            PoseOutils.ListeVariables l = PoseOutils.lireListeVariables(m.getPacket());
            synchronized (variablesRecues) { variablesRecues.addAll(l.variables()); }
            if (l.dernier()) listeRecue.release();
        });
        canal.intercept(C, "WiredVariablesForObject", m -> {
            if (!enCours) return;
            PoseOutils.VariablesObjet v = PoseOutils.lireVariablesObjet(m.getPacket());
            if (v == null || v.objet() == 0) return;
            inspections.put(v.objet(), v.variables());
            if (v.objet() == inspectionAttendue) inspectionRecue.release();
        });
        canal.intercept(C, "WiredAllVariableHolders", m -> {
            if (!enCours) return;
            PoseOutils.Porteurs p = PoseOutils.lirePorteurs(m.getPacket());
            porteurs.put(p.variable(), p);
            if (p.variable().equals(porteursAttendus)) porteursRecus.release();
        });
    }

    /** Le dossier des copies actuelles. */
    static File dossierParDefaut() { return OngletApparts.dossierApparts(); }

    // ================================================================ enregistrement

    /** Construit la copie et l'ecrit dans le dossier des copies (« nom.json », remplace un fichier du meme nom). */
    Bilan enregistrer(String nom, Zone zone, Options o, BooleanSupplier stop) {
        return enregistrer(nom, zone, o, dossierParDefaut(), stop);
    }

    Bilan enregistrer(String nom, Zone zone, Options o, File dossier, BooleanSupplier stop) {
        Bilan b = construire(zone, o, stop);
        if (b.erreur != null || b.copie == null || b.arrete) return b;
        try {
            b.fichier = ecrire(b.copie, nom, dossier);
        } catch (IOException e) {
            b.erreur = "écriture impossible (" + e.getMessage() + ")";
        }
        Journal.debug("Enregistrement : " + b.texte() + (b.fichier == null ? "" : " -> " + b.fichier));
        return b;
    }

    /** Lit la salle (variables, muraux, wired) et construit la copie, sans l'ecrire. */
    Bilan construire(Zone zone, Options o, BooleanSupplier stop) {
        Bilan b = new Bilan();
        if (stop == null) stop = () -> false;
        if (o == null) o = new Options();
        if (!salle.dansUneSalle()) { b.erreur = "pas dans une salle"; return b; }
        if (furnidata == null || !furnidata.pret()) { b.erreur = "furnidata pas prête"; return b; }
        if (zone == null) zone = Zone.toute(salle);
        boolean wired = o.wired && o.sols;
        if (wired && droits != null && !droits.peutRegler()) {
            wired = false;
            b.avertissement = "Pas de droits wired ici : copie sans les réglages des wired.";
        }

        enCours = true;
        try {
            // 1. la liste des variables de la salle
            Map<String, String> nomVersId = new LinkedHashMap<>(), idVersNom = new HashMap<>();
            Map<String, PoseOutils.Variable> infos = new HashMap<>();
            if (wired || o.variablesMobis || o.murs) {
                b.listeVariables = demanderListe(stop);
                synchronized (variablesRecues) {
                    for (PoseOutils.Variable v : variablesRecues) {
                        idVersNom.put(v.id(), v.nom());
                        infos.put(v.id(), v);
                        if (v.utilisatrice()) nomVersId.put(v.nom(), v.id());
                    }
                }
            }

            // 2. les reglages des wired de la zone
            Map<Integer, ReglageWired> reglages = new HashMap<>();
            if (wired) {
                for (HFloorItem f : solsDeLaZone(zone)) {
                    String classe = furnidata.classeSol(f.getTypeId());
                    if (ReglageWired.Genre.deClasse(classe) == null) continue;
                    ReglageWired r = lecteur.reglage(f.getId(), classe);
                    if (r == null) b.wiredNonLus.add(f.getId());
                    else reglages.put(f.getId(), r);
                }
                if (!b.wiredNonLus.isEmpty() && o.exigerWired) {
                    b.erreur = PoseOutils.nombre(b.wiredNonLus.size(), "wired n'est pas encore lu", "wired ne sont pas encore lus")
                            + " (attends la fin de la lecture des wired)";
                    return b;
                }
            }

            // 3. les variables des muraux (altitude) et des mobis
            Map<Integer, Map<String, Integer>> parObjet = new HashMap<>();
            if (o.murs) {
                List<Integer> murs = PoseOutils.idsMuraux(salle);
                inspecterMuraux(murs, stop, b);
                for (int id : murs) { Map<String, Integer> v = inspections.get(id); if (v != null) parObjet.put(id, new HashMap<>(v)); }
            }
            if (o.variablesMobis && o.sols && !stop.getAsBoolean()) {
                Set<Integer> zoneIds = new LinkedHashSet<>();
                for (HFloorItem f : solsDeLaZone(zone)) zoneIds.add(f.getId());
                List<String> aLire = new ArrayList<>();
                for (String id : nomVersId.values()) { PoseOutils.Variable v = infos.get(id); if (v != null && v.deMobi()) aLire.add(id); }
                lirePorteurs(aLire, stop);
                for (PoseOutils.Porteurs p : porteurs.values())
                    p.valeurs().forEach((objet, valeur) -> {
                        if (zoneIds.contains(objet)) parObjet.computeIfAbsent(objet, k -> new HashMap<>()).put(p.variable(), valeur);
                    });
            }
            if (stop.getAsBoolean()) { b.arrete = true; return b; }

            // 4. la copie
            b.copie = assembler(salle, furnidata, zone, o.sols, o.murs, wired, reglages, nomVersId, idVersNom, parObjet, b);
            return b;
        } finally {
            enCours = false;
        }
    }

    // ================================================================ assemblage (sans reseau)

    /**
     * Assemble la copie a partir de l'etat de la salle et des lectures faites
     * (logique d'export, sans reseau ; teste hors du jeu).
     *
     * @param reglages  id reel d'un wired -> son reglage
     * @param nomVersId variables de l'utilisatrice, nom -> id (« variables_map »)
     * @param idVersNom toutes les variables, id -> nom
     * @param parObjet  id reel d'un mobi ou d'un mural -> variables (id ou nom -> valeur)
     */
    static CopieAppart assembler(EtatSalle salle, Furnidata fd, Zone z, boolean sols, boolean murs, boolean wired,
                                 Map<Integer, ReglageWired> reglages, Map<String, String> nomVersId,
                                 Map<String, String> idVersNom, Map<Integer, Map<String, Integer>> parObjet, Bilan b) {
        CopieAppart c = new CopieAppart();
        Map<Integer, List<CopieAppart.Liaison>> liaisons = new LinkedHashMap<>();

        if (sols) {
            for (HFloorItem f : solsDeLaZone(salle, z)) {
                String classe = fd.classeSol(f.getTypeId());
                if (classe == null) { if (b != null) b.inconnus++; continue; }
                CopieAppart.MobiSol m = new CopieAppart.MobiSol(f.getId(), classe, f.getTile().getX(), f.getTile().getY(),
                        f.getTile().getZ(), f.getFacing() == null ? 0 : f.getFacing().ordinal(), PoseOutils.etat(f));
                Map<String, Integer> v = exportables(parObjet.get(f.getId()), idVersNom);
                if (!v.isEmpty()) m.variables = v;
                c.sols.add(m);
                if (classe.equals("ads_background") && f.getStuff() instanceof MapStuffData) {
                    MapStuffData d = (MapStuffData) f.getStuff();
                    c.fonds.add(new CopieAppart.FondPub(f.getId(), d.get("imageUrl"), d.get("offsetX"), d.get("offsetY"), d.get("offsetZ")));
                }
                if (!wired) continue;
                ReglageWired r = reglages.get(f.getId());
                if (r == null || ReglageWired.Genre.deClasse(classe) == null) continue;
                r = r.copie();
                r.wiredId = f.getId();
                if (r.genre == ReglageWired.Genre.VARIABLE
                        && (r.variableId == null || r.variableId.isEmpty() || r.variableId.equals("0"))) {
                    String id = nomVersId.get(r.texte);
                    if (id != null) r.variableId = id;
                }
                if (A_LIAISONS.contains(classe)) {
                    List<CopieAppart.Liaison> l = liaisonsDuTexte(r);
                    if (l == null) l = liaisonsDeLaSalle(r, salle);
                    else r.texte = "";
                    if (!l.isEmpty()) liaisons.put(r.wiredId, l);
                }
                c.ajouter(r);
            }
        }
        if (murs) {
            for (HWallItem w : salle.getWallItems()) {
                String classe = fd.classeMur(w.getTypeId());
                PositionMur p = PositionMur.lireOuNull(w.getLocation());
                if (classe == null || p == null) { if (b != null) b.inconnus++; continue; }
                Map<String, Integer> vars = parObjet.get(w.getId());
                Integer alt = vars == null ? null : vars.get("-123");
                if (alt != null) p = p.avecAltitude(alt);
                CopieAppart.MobiMur m = new CopieAppart.MobiMur(w.getId(), classe, p, w.getState());
                Map<String, Integer> v = exportables(vars, idVersNom);
                if (!v.isEmpty()) m.variables = v;
                c.murs.add(m);
            }
        }

        // renumerotation 1..n (sols puis muraux) ; ids hors copie retires des wired
        Map<Integer, Integer> num = new HashMap<>();
        int n = 0;
        for (CopieAppart.MobiSol m : c.sols) num.put(m.id, ++n);
        for (CopieAppart.MobiMur m : c.murs) num.put(m.id, ++n);
        for (ReglageWired r : c.tousWired()) {
            r.items = renumeroter(r.items, num);
            r.items2 = renumeroter(r.items2, num);
            r.wiredId = num.get(r.wiredId);
        }
        for (List<CopieAppart.Liaison> l : liaisons.values())
            for (CopieAppart.Liaison x : l) {
                Integer mobi = num.get(x.mobiId);
                if (mobi == null) continue;
                x.mobiId = mobi;
                x.wiredId = num.get(x.wiredId);
                if (x.aPosition()) { x.x -= z.x(); x.y -= z.y(); }
                c.liaisons.add(x);
            }
        for (CopieAppart.FondPub f : c.fonds) f.mobiId = num.get(f.mobiId);

        // positions relatives, noms classe[n]
        Map<String, Integer> compte = new HashMap<>();
        for (CopieAppart.MobiSol m : c.sols) {
            m.id = num.get(m.id);
            m.x -= z.x();
            m.y -= z.y();
            int k = compte.merge(m.classe, 1, Integer::sum) - 1;
            m.nom = m.classe + "[" + k + "]";
            if (ReglageWired.Genre.deClasse(m.classe) != null) m.etat = null;
        }
        for (CopieAppart.MobiMur m : c.murs) {
            m.id = num.get(m.id);
            m.position = m.position.deplacee(-z.x(), -z.y());
            int k = compte.merge(m.classe, 1, Integer::sum) - 1;
            m.nom = m.classe + "[" + k + "]";
        }

        c.tableVariables = new LinkedHashMap<>(nomVersId);
        if (salle.planBrut() != null)
            c.disposition = CopieAppart.Disposition.depuisPlan(salle.modele(), salle.largeurPlan(), salle.longueurPlan(),
                    salle.echelle(), salle.hauteurMurs(), salle.planBrut());
        int bas = PoseOutils.solLePlusBas(salle, z.x(), z.y(), z.x() + z.largeur() - 1, z.y() + z.longueur() - 1);
        if (bas >= 0 && bas < 256) c.ancre = (double) bas;
        return c;
    }

    /** Les variables a garder (getExportableFurniVariables) : par leur nom, hors « @ », « ~ » et « - ». */
    static Map<String, Integer> exportables(Map<String, Integer> source, Map<String, String> idVersNom) {
        Map<String, Integer> r = new LinkedHashMap<>();
        if (source == null) return r;
        source.forEach((cle, valeur) -> {
            String nom = idVersNom == null ? cle : idVersNom.getOrDefault(cle, cle);
            if (nom == null || nom.isEmpty()) return;
            char c0 = nom.charAt(0);
            if (c0 == '@' || c0 == '~' || c0 == '-') return;
            r.put(nom, valeur);
        });
        return r;
    }

    /**
     * Les liaisons du texte de la reponse du serveur (maybeRetrieveBindings) :
     * « id,etat,rot,x,y[,alt] » separes par « ; », « N » pour absent. Positions
     * absolues. null si le texte est vide ou illisible.
     */
    static List<CopieAppart.Liaison> liaisonsDuTexte(ReglageWired r) {
        if (r.texte == null || r.texte.isEmpty()) return null;
        List<CopieAppart.Liaison> l = new ArrayList<>();
        try {
            for (String s : r.texte.split(";")) {
                String[] f = s.split(",");
                int mobi = Integer.parseInt(f[0]);
                String etat = f[1].equals("N") ? null : f[1];
                Integer rot = f[2].equals("N") ? null : Integer.parseInt(f[2]);
                boolean place = !f[3].equals("N") && !f[4].equals("N");
                Integer alt = f.length < 6 || f[5].equals("N") ? null : Integer.parseInt(f[5]);
                l.add(new CopieAppart.Liaison(mobi, r.wiredId, place ? Integer.parseInt(f[3]) : null,
                        place ? Integer.parseInt(f[4]) : null, rot, etat, alt));
            }
        } catch (RuntimeException e) {
            Journal.debug("Enregistrement : liaisons illisibles « " + r.texte + " » : " + e);
            return null;
        }
        return l;
    }

    /** Les liaisons d'apres les options 0 a 3 et la place actuelle des mobis (maybeSaveBindings). */
    static List<CopieAppart.Liaison> liaisonsDeLaSalle(ReglageWired r, EtatSalle salle) {
        List<CopieAppart.Liaison> l = new ArrayList<>();
        if (r.options.size() < 4) return l;
        boolean etat = r.options.get(0) == 1, rot = r.options.get(1) == 1, place = r.options.get(2) == 1, alt = r.options.get(3) == 1;
        for (int id : r.items) {
            HFloorItem f = salle.furniFromId(id);
            if (f == null) continue;
            l.add(new CopieAppart.Liaison(id, r.wiredId, place ? f.getTile().getX() : null, place ? f.getTile().getY() : null,
                    rot ? (Integer) (f.getFacing() == null ? 0 : f.getFacing().ordinal()) : null,
                    etat ? PoseOutils.etat(f) : null, alt ? (Integer) (int) Math.round(f.getTile().getZ() * 100) : null));
        }
        return l;
    }

    private static List<Integer> renumeroter(List<Integer> l, Map<Integer, Integer> num) {
        List<Integer> r = new ArrayList<>();
        for (Integer i : l) { Integer n = num.get(i); if (n != null) r.add(n); }
        return r;
    }

    /** Les mobis de sol de la zone, case par case (x puis y), dans l'ordre de l'etat de la salle. */
    static List<HFloorItem> solsDeLaZone(EtatSalle salle, Zone z) {
        List<HFloorItem> l = new ArrayList<>();
        for (int x = z.x(); x < z.x() + z.largeur(); x++)
            for (int y = z.y(); y < z.y() + z.longueur(); y++) l.addAll(salle.getFurniOnTile(x, y));
        return l;
    }

    private List<HFloorItem> solsDeLaZone(Zone z) { return solsDeLaZone(salle, z); }

    // ================================================================ ecriture

    /** Ecrit « nom.json » (indentation 4, UTF-8, comme savePreset), en remplacant un fichier existant. */
    static File ecrire(CopieAppart c, String nom, File dossier) throws IOException {
        String propre = nom == null ? "" : nom.replaceAll("[<>:\"/\\\\|?*]", "-").trim();
        if (propre.isEmpty()) propre = "Sans nom";
        if (!dossier.isDirectory() && !dossier.mkdirs()) throw new IOException("dossier " + dossier + " impossible à créer");
        File f = new File(dossier, propre + ".json");
        File tmp = new File(dossier, propre + ".json.tmp");
        Files.write(tmp.toPath(), c.texte().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    // ================================================================ lectures reseau

    /** Demande la liste des variables et attend son dernier morceau (5 s au plus). */
    private boolean demanderListe(BooleanSupplier stop) {
        synchronized (variablesRecues) { variablesRecues.clear(); }
        listeRecue.drainPermits();
        PoseOutils.envoyer(canal, PoseOutils.demandeVariables());
        long fin = System.currentTimeMillis() + 5000;
        try {
            while (System.currentTimeMillis() < fin && !stop.getAsBoolean())
                if (listeRecue.tryAcquire(100, TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Journal.debug("Enregistrement : liste des variables non reçue");
        return false;
    }

    /** inspectFurniWithAcks : un mural apres l'autre (350 ms), 700 ms d'attente, puis 3 passes de reprise (1200 ms). */
    private void inspecterMuraux(List<Integer> ids, BooleanSupplier stop, Bilan b) {
        inspections.keySet().removeAll(ids);
        List<Integer> manquants = new ArrayList<>();
        long dernier = 0;
        for (int id : ids) {
            if (stop.getAsBoolean()) return;
            dernier = inspecterUn(id, 700, dernier);
            if (!inspections.containsKey(id)) manquants.add(id);
        }
        for (int passe = 1; passe <= 3 && !manquants.isEmpty() && !stop.getAsBoolean(); passe++) {
            if (!PoseOutils.dormir(500, stop)) return;
            List<Integer> encore = new ArrayList<>();
            for (int id : manquants) {
                if (stop.getAsBoolean()) return;
                if (inspections.containsKey(id)) continue;
                dernier = inspecterUn(id, 1200, dernier);
                if (!inspections.containsKey(id)) encore.add(id);
            }
            manquants = encore;
        }
        b.murauxInspectes = ids.size();
        b.murauxSansReponse = manquants.size();
        if (!manquants.isEmpty()) Journal.debug("Enregistrement : muraux sans variables lues " + manquants);
    }

    private long inspecterUn(int id, long attenteMs, long dernier) {
        long reste = dernier + 350 - System.currentTimeMillis();
        if (reste > 0) Salle.sommeil(reste);
        inspectionRecue.drainPermits();
        inspectionAttendue = id;
        try {
            PoseOutils.envoyer(canal, PoseOutils.inspectionMural(id));
            long envoi = System.currentTimeMillis();
            inspectionRecue.tryAcquire(attenteMs, TimeUnit.MILLISECONDS);
            return envoi;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return System.currentTimeMillis();
        } finally {
            inspectionAttendue = 0;
        }
    }

    /** fetchVariablesSerial : une variable apres l'autre (1500 ms d'attente, 80 ms entre deux), une reprise. */
    private void lirePorteurs(List<String> ids, BooleanSupplier stop) {
        porteurs.clear();
        for (int passe = 0; passe < 2; passe++) {
            for (String id : ids) {
                if (stop.getAsBoolean()) return;
                if (porteurs.containsKey(id)) continue;
                porteursRecus.drainPermits();
                porteursAttendus = id;
                try {
                    PoseOutils.envoyer(canal, PoseOutils.demandePorteurs(id));
                    porteursRecus.tryAcquire(1500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    porteursAttendus = null;
                }
                Salle.sommeil(80);
            }
        }
    }

    /** Pour les essais : les variables lues d'un objet (inspection). */
    Map<String, Integer> inspection(int id) { return inspections.get(id); }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Applique les reglages des wired d'une copie (CopieAppart) sur des wired
 * deja poses.
 *
 * Deroulement :
 *   1. la liste des variables de la salle (avant), s'il y a des wired
 *      variables (wf_var_*) a regler ;
 *   2. les wired variables d'abord (ils creent les variables par leur nom) ;
 *   3. la liste des variables relue (apres), et CHAQUE nom de « variables_map »
 *      retrouve : il doit exister, avec la bonne cible (Variable.genre : mobi,
 *      utilisateur, salle, contexte, d'apres la classe du wf_var qui le cree)
 *      et ne pas etre une variable interne. Un nom en double est signale ; on
 *      prend la variable que vient de creer le wf_var de la copie (absente de
 *      la liste d'avant). Les wf_var qui citent une autre variable passent
 *      ensuite, puis la liste est relue une derniere fois ;
 *   4. les autres wired : conditions, effets, declencheurs, add-ons,
 *      selecteurs, puis les add-ons une seconde fois. Un wired qui cite une
 *      variable introuvable n'est PAS envoye (jamais de « 0 » a la place) ; un
 *      add-on varfx dont le style est inconnu du jeu, ou dont le wf_var de la
 *      pile n'a pas sa variable, non plus.
 *
 * Pour chaque wired, d'abord ses liaisons (wired « instantane ») : le mobi lie
 * est mis a l'etat voulu (jamais une boite wired : l'utiliser ouvre sa
 * fenetre), puis deplace a la case, a la rotation et a l'altitude voulues ;
 * apres l'enregistrement, il reprend sa place.
 *
 * L'enregistrement : 300 ms au moins apres le precedent du meme genre, paquet
 * Update* (ReglageWired.traduire / paquetUpdate), 3 essais. Chaque envoi
 * attend SA reponse : les reponses (WiredSaveSuccess ou WiredValidationError,
 * qui ne portent pas d'id) sont rendues aux envois dans l'ordre ; un envoi
 * reste en attente de sa reponse tardive quelques secondes apres son delai,
 * pour qu'elle ne soit jamais prise pour celle du suivant. Un refus
 * (WiredValidationError) compte comme un echec, sans nouvel essai.
 *
 * Aucun travail sur le fil JavaFX, aucun message dans le jeu.
 */
final class ReglageWiredPose {

    /** Deplace un mobi de sol a (x, y, rot), et a l'altitude z (en cases) si z n'est pas null. */
    interface Deplaceur { void deplacer(int id, int x, int y, int rot, Double z); }

    /** Ce que rend le reglage. */
    static final class Bilan {
        /** Wired a regler (dont l'id reel est connu). */
        int attendus;
        /** Ids (copie) des wired dont un Update* est parti. */
        final Set<Integer> regles = new LinkedHashSet<>();
        /** Ids (copie) des wired confirmes par le serveur (WiredSaveSuccess). */
        final Set<Integer> confirmes = new LinkedHashSet<>();
        /** Wired sans reponse : id (copie) -> nom lisible. */
        final Map<Integer, String> rates = new LinkedHashMap<>();
        /** Wired refuses par le serveur (WiredValidationError) : id (copie) -> nom lisible et motif. */
        final Map<Integer, String> refuses = new LinkedHashMap<>();
        /** Wired pas envoyes : id (copie) -> nom lisible et raison. */
        final Map<Integer, String> nonEnvoyes = new LinkedHashMap<>();
        /** Variables de la copie introuvables dans la salle : nom -> raison. */
        final Map<String, String> variablesManquantes = new LinkedHashMap<>();
        /** Noms de variables presents plusieurs fois dans la salle. */
        final Set<String> doublons = new LinkedHashSet<>();
        /** Liaisons dont l'etat n'a pas pu etre donne. */
        int etatsFaux;
        boolean arrete;
        String erreur;

        /** Ids (copie) des wired dont le reglage n'est pas parti ou a ete refuse. */
        Set<Integer> nonAppliques() {
            Set<Integer> s = new LinkedHashSet<>(nonEnvoyes.keySet());
            s.addAll(refuses.keySet());
            return s;
        }

        String texte() {
            if (erreur != null) return "Réglage des wired impossible : " + erreur + ".";
            StringBuilder b = new StringBuilder(PoseOutils.nombre(confirmes.size(), "wired réglé", "wired réglés"));
            b.append(" sur ").append(attendus);
            if (!rates.isEmpty()) b.append(", ").append(PoseOutils.nombre(rates.size(), "sans réponse", "sans réponse"));
            if (!refuses.isEmpty()) b.append(", ").append(PoseOutils.nombre(refuses.size(), "refusé par le jeu", "refusés par le jeu"));
            if (!nonEnvoyes.isEmpty()) b.append(", ").append(PoseOutils.nombre(nonEnvoyes.size(), "non envoyé", "non envoyés"));
            if (!variablesManquantes.isEmpty())
                b.append(", ").append(PoseOutils.nombre(variablesManquantes.size(), "variable introuvable", "variables introuvables"))
                        .append(" (").append(String.join(", ", variablesManquantes.keySet())).append(")");
            if (!doublons.isEmpty())
                b.append(", ").append(PoseOutils.nombre(doublons.size(), "nom de variable en double", "noms de variable en double"))
                        .append(" (").append(String.join(", ", doublons)).append(")");
            if (etatsFaux > 0) b.append(", ").append(PoseOutils.nombre(etatsFaux, "liaison sans son état", "liaisons sans leur état"));
            if (arrete) b.append(" (arrêté)");
            return Ui.majuscule(b.toString()) + ".";
        }

        /** Le detail lisible, une ligne par probleme ; vide si tout va bien. */
        List<String> details() {
            List<String> l = new ArrayList<>();
            for (Map.Entry<String, String> e : variablesManquantes.entrySet())
                l.add("Variable « " + e.getKey() + " » : " + e.getValue() + ".");
            for (String n : doublons)
                l.add("Variable « " + n + " » en double dans la salle : celle créée par la copie est utilisée.");
            for (String s : nonEnvoyes.values()) l.add(Ui.majuscule(s) + " : non envoyé.");
            for (String s : refuses.values()) l.add(Ui.majuscule(s) + " : refusé par le jeu.");
            for (String s : rates.values()) l.add(Ui.majuscule(s) + " : pas de réponse du jeu.");
            return l;
        }
    }

    /** Ce que les autres citent d'abord : selecteurs (sources 200), add-ons, conditions, effets, declencheurs. */
    private static final ReglageWired.Genre[] ORDRE = {
            ReglageWired.Genre.SELECTEUR, ReglageWired.Genre.ADDON, ReglageWired.Genre.CONDITION,
            ReglageWired.Genre.EFFET, ReglageWired.Genre.DECLENCHEUR, ReglageWired.Genre.ADDON};

    static final long CONFIRMATION_MS = 5000, LISTE_MS = 5000, ECART_GENRE_MS = 300;
    /** Pauses apres un wired « sans reponse » (le jeu peut se taire un moment apres un envoi qu'il n'aime pas). */
    static final long PAUSE_MS = 5000, PAUSE_LONGUE_MS = 15_000;
    /** Un envoi sans reponse garde sa place dans la file encore 2 s apres son delai. */
    static final long PERIME_MS = CONFIRMATION_MS + 2000;

    private final Canal canal;
    private final EtatSalle salle;
    private final Droits droits;

    private final Semaphore listeRecue = new Semaphore(0);
    private volatile boolean enCours;
    /** Variables de la salle recues depuis la derniere demande : id -> variable. */
    private final Map<String, PoseOutils.Variable> recues = new LinkedHashMap<>();
    private final Object verrouVariables = new Object();

    /** Un Update* parti, qui attend sa reponse. */
    private static final class Envoi {
        final long quand = System.currentTimeMillis();
        final CountDownLatch fait = new CountDownLatch(1);
        volatile boolean ok;
        volatile String motif;
    }

    /** Envois en attente de reponse, dans l'ordre d'envoi. */
    private final Deque<Envoi> enVol = new ArrayDeque<>();

    ReglageWiredPose(Canal canal, EtatSalle salle, Droits droits) {
        this.canal = canal;
        this.salle = salle;
        this.droits = droits;
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "WiredSaveSuccess", m -> { if (enCours) surReponse(true, null); });
        canal.intercept(C, "WiredValidationError", m -> {
            if (!enCours) return;
            String motif = null;
            try { motif = new gearth.protocol.HPacket(m.getPacket()).readString(); } catch (Throwable ignored) { }
            surReponse(false, motif);
        });
        canal.intercept(C, "WiredAllVariablesDiffs", m -> { if (enCours) surVariables(m); });
        // diagnostic : ce que le jeu envoie pendant l'attente d'une reponse (journalise si elle ne vient pas)
        canal.interceptTout(C, m -> {
            if (System.currentTimeMillis() > ecouteJusqua) return;
            HPacket p = m.getPacket();
            String n = canal.nomPaquet(C, p.headerId());
            synchronized (recusPendant) { recusPendant.merge(n == null ? "#" + p.headerId() : n, 1, Integer::sum); }
        });
    }

    /** Diagnostic : paquets recus pendant l'attente de la reponse en cours (nom -> nombre). */
    private final Map<String, Integer> recusPendant = new LinkedHashMap<>();
    private volatile long ecouteJusqua = 0;

    // ================================================================ ecouteurs

    /** Une reponse du serveur : elle revient au plus ancien envoi encore valable. */
    private void surReponse(boolean ok, String motif) {
        Envoi e;
        synchronized (enVol) {
            long now = System.currentTimeMillis();
            while (!enVol.isEmpty() && now - enVol.peekFirst().quand > PERIME_MS) enVol.pollFirst();
            e = enVol.pollFirst();
        }
        if (e == null) return;
        e.ok = ok;
        e.motif = motif;
        e.fait.countDown();
    }

    private void surVariables(HMessage m) {
        PoseOutils.ListeVariables l = PoseOutils.lireListeVariables(m.getPacket());
        synchronized (verrouVariables) {
            for (String id : l.retirees()) recues.remove(id);
            for (PoseOutils.Variable v : l.variables()) if (v.id() != null) recues.put(v.id(), v);
        }
        if (l.dernier()) listeRecue.release();
    }

    // ================================================================ variables (logique pure)

    /** Un id de variable (« 81151 », « -110 », « ~420 »). */
    static boolean estIdVariable(String s) { return s != null && s.matches("[-~]?\\d+"); }

    /**
     * « variables_map » ramenee a nom -> ancien id. Les copies l'ecrivent ainsi
     * (WiredCollage.versPreset, EnregistrementCopie) ; une table a l'envers
     * (id -> nom) est remise a l'endroit.
     */
    static Map<String, String> nomsVersAnciens(CopieAppart c) {
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : c.tableVariables.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || v == null) continue;
            if (estIdVariable(k) && !estIdVariable(v)) r.put(v, k);
            else r.put(k, v);
        }
        return r;
    }

    /** La cible (Variable.genre) d'une variable creee par ce wf_var ; null si elle depend du reglage. */
    static Integer cibleAttendue(String classe) {
        if (classe == null) return null;
        switch (Wired.normaliser(classe)) {
            case "wf_var_furni": return 0;
            case "wf_var_user": return 1;
            case "wf_var_room": return -10;
            case "wf_var_context": return -20;
            default: return null;
        }
    }

    static String nomCible(int g) {
        switch (g) {
            case 0: return "mobi";
            case 1: return "utilisateur";
            case -10: return "salle";
            case -20: return "contexte";
            default: return "genre " + g;
        }
    }

    /** La classe d'un mobi de la copie, ou null. */
    static String classe(CopieAppart c, int id) {
        for (CopieAppart.MobiSol m : c.sols) if (m.id == id) return m.classe;
        return null;
    }

    /**
     * Retrouve chaque nom de « variables_map » dans la liste de la salle et
     * remplit la table (ancien id -> id reel). Recalcule les variables
     * manquantes et les doublons du bilan. Logique pure.
     *
     * @param avant liste de la salle avant les wf_var (id -> variable), pour reconnaitre les nouvelles
     * @param apres liste de la salle apres les wf_var ; null = pas recue
     */
    static void associer(CopieAppart c, Map<Integer, Integer> ids, Map<String, PoseOutils.Variable> avant,
                         Map<String, PoseOutils.Variable> apres, Map<String, String> table, Bilan b) {
        b.variablesManquantes.clear();
        b.doublons.clear();
        Map<String, Integer> cibles = new HashMap<>();
        Set<String> creees = new HashSet<>();
        for (ReglageWired w : c.wired(ReglageWired.Genre.VARIABLE)) {
            if (!ids.containsKey(w.wiredId) || w.texte == null || w.texte.isBlank()) continue;
            creees.add(w.texte);
            Integer g = cibleAttendue(classe(c, w.wiredId));
            if (g != null) cibles.put(w.texte, g);
        }
        for (Map.Entry<String, String> e : nomsVersAnciens(c).entrySet()) {
            String nom = e.getKey(), ancien = e.getValue();
            if (ReglageWired.variableGardee(ancien)) continue;          // interne : pas a retrouver
            table.remove(ancien);
            if (apres == null) { b.variablesManquantes.put(nom, "liste des variables de la salle non reçue"); continue; }
            List<PoseOutils.Variable> memeNom = new ArrayList<>();
            for (PoseOutils.Variable v : apres.values()) if (nom.equals(v.nom())) memeNom.add(v);
            if (memeNom.isEmpty()) {
                b.variablesManquantes.put(nom, creees.contains(nom) ? "pas créée par son wired variable" : "absente de la salle");
                continue;
            }
            Integer cible = cibles.get(nom);
            List<PoseOutils.Variable> bonnes = new ArrayList<>();
            for (PoseOutils.Variable v : memeNom)
                if (v.typeInterne() != 1 && (cible == null || v.genre() == cible)) bonnes.add(v);
            if (bonnes.isEmpty()) {
                PoseOutils.Variable v = memeNom.get(0);
                b.variablesManquantes.put(nom, v.typeInterne() == 1 ? "c'est une variable interne"
                        : "pas du bon genre (attendu " + nomCible(cible) + ", trouvé " + nomCible(v.genre()) + ")");
                continue;
            }
            if (memeNom.size() > 1) {
                b.doublons.add(nom);
                List<PoseOutils.Variable> neuves = new ArrayList<>();
                for (PoseOutils.Variable v : bonnes) if (avant == null || !avant.containsKey(v.id())) neuves.add(v);
                if (creees.contains(nom) && !neuves.isEmpty()) bonnes = neuves;
            }
            table.put(ancien, bonnes.get(0).id());
        }
    }

    // ================================================================ reglage

    /** Ce dont un reglage a besoin, le temps d'un appel a appliquer. */
    private final class Travail {
        final CopieAppart copie;
        final Map<Integer, Integer> ids;
        final Map<String, String> table;
        final HPoint coin;
        final Deplaceur deplaceur;
        final BooleanSupplier stop;
        final Bilan b;
        final Map<Integer, List<CopieAppart.Liaison>> liaisons = new HashMap<>();
        final Map<ReglageWired.Genre, Long> derniers = new EnumMap<>(ReglageWired.Genre.class);
        /** ancien id de variable -> nom (pour le bilan). */
        final Map<String, String> nomsAnciens = new HashMap<>();

        Travail(CopieAppart copie, Map<Integer, Integer> ids, Map<String, String> table, HPoint coin,
                Deplaceur deplaceur, BooleanSupplier stop, Bilan b) {
            this.copie = copie; this.ids = ids; this.table = table; this.coin = coin;
            this.deplaceur = deplaceur; this.stop = stop; this.b = b;
            for (CopieAppart.Liaison l : copie.liaisons) liaisons.computeIfAbsent(l.wiredId, k -> new ArrayList<>()).add(l);
            nomsVersAnciens(copie).forEach((n, a) -> nomsAnciens.put(a, n));
        }
    }

    /**
     * Regle les wired de la copie.
     *
     * @param ids            id de la copie -> id reel (pose deja faite)
     * @param tableVariables id de variable de la copie -> id reel ; remplie ici (peut etre vide)
     * @param coin           coin de la copie dans la salle (positions des liaisons)
     * @param deplaceur      deplacement des mobis lies ; null = MoveObject seul, sans altitude
     */
    Bilan appliquer(CopieAppart copie, Map<Integer, Integer> ids, Map<String, String> tableVariables, HPoint coin,
                    Deplaceur deplaceur, BooleanSupplier stop) {
        Bilan b = new Bilan();
        if (stop == null) stop = () -> false;
        if (copie == null || ids == null) { b.erreur = "rien à régler"; return b; }
        if (tableVariables == null) tableVariables = new HashMap<>();
        if (deplaceur == null) deplaceur = (id, x, y, rot, z) -> PoseOutils.envoyer(canal, PoseOutils.deplacementSol(id, x, y, rot));

        List<ReglageWired> variables = new ArrayList<>(), autres = new ArrayList<>();
        Set<Integer> distincts = new LinkedHashSet<>();
        for (ReglageWired r : copie.wired(ReglageWired.Genre.VARIABLE))
            if (ids.containsKey(r.wiredId)) { variables.add(r); distincts.add(r.wiredId); }
        for (ReglageWired.Genre g : ORDRE)
            for (ReglageWired r : copie.wired(g))
                if (ids.containsKey(r.wiredId)) { autres.add(r); distincts.add(r.wiredId); }
        b.attendus = distincts.size();
        if (distincts.isEmpty()) return b;

        Travail t = new Travail(copie, ids, tableVariables, coin, deplaceur, stop, b);
        synchronized (enVol) { enVol.clear(); }
        enCours = true;
        try {
            // 1. la liste d'avant, puis les wired variables (ceux qui citent une autre variable : apres la liste)
            Map<String, PoseOutils.Variable> avant = null;
            List<ReglageWired> reportes = new ArrayList<>();
            if (!variables.isEmpty()) {
                avant = listeSalle(stop);
                for (ReglageWired r : variables) {
                    if (fin(t)) break;
                    if (citeVariable(r)) reportes.add(r);
                    else regler(t, r);
                }
            }
            // 2. chaque nom de la copie retrouve dans la salle
            if (!fin(t) && (!nomsVersAnciens(copie).isEmpty() || !reportes.isEmpty())) {
                Map<String, PoseOutils.Variable> apres = listeSalle(stop);
                synchronized (verrouVariables) { associer(copie, ids, avant, apres, tableVariables, b); }
                if (!reportes.isEmpty() && !fin(t)) {
                    for (ReglageWired r : reportes) { if (fin(t)) break; regler(t, r); }
                    apres = listeSalle(stop);
                    synchronized (verrouVariables) { associer(copie, ids, avant, apres, tableVariables, b); }
                }
            }
            // 3. les autres wired. Un « sans reponse » : le jeu a pu cesser de repondre
            // un moment (tout ce qui suivait restait muet) ; on le laisse souffler avant la suite
            int muets = 0;
            for (ReglageWired r : autres) {
                if (fin(t)) break;
                regler(t, r);
                if (b.rates.containsKey(r.wiredId)) {
                    muets++;
                    if (!PoseOutils.dormir(muets >= 2 ? PAUSE_LONGUE_MS : PAUSE_MS, stop)) break;
                } else {
                    muets = 0;
                }
            }
            // 4. une seconde chance pour les « sans reponse », apres une pause
            if (!b.rates.isEmpty() && !fin(t) && PoseOutils.dormir(PAUSE_LONGUE_MS, stop)) {
                Journal.debug("Réglage wired : nouvel essai de " + b.rates.size() + " sans réponse");
                for (ReglageWired r : autres) {
                    if (fin(t)) break;
                    if (b.rates.containsKey(r.wiredId)) regler(t, r);
                }
            }
        } finally {
            enCours = false;
            synchronized (enVol) { enVol.clear(); }
        }
        if (stop.getAsBoolean()) b.arrete = true;
        Journal.debug("Réglage wired : " + b.texte() + " Table des variables " + tableVariables
                + (b.details().isEmpty() ? "" : " ; " + String.join(" ", b.details())));
        return b;
    }

    private boolean fin(Travail t) {
        if (t.stop.getAsBoolean()) { t.b.arrete = true; return true; }
        return !salle.dansUneSalle();
    }

    private static boolean citeVariable(ReglageWired r) {
        for (String v : r.variables) if (!ReglageWired.variableGardee(v)) return true;
        return false;
    }

    /** Un wired : verifications, liaisons, enregistrement, bilan. */
    private void regler(Travail t, ReglageWired r) {
        Bilan b = t.b;
        String nom = nom(t.copie, r);
        ReglageWired tr;
        synchronized (verrouVariables) { tr = r.traduire(t.ids, t.table); }
        if (tr == null) return;
        if (!tr.manquantes.isEmpty()) {
            List<String> noms = new ArrayList<>();
            for (String id : tr.manquantes) noms.add(t.nomsAnciens.containsKey(id) ? "« " + t.nomsAnciens.get(id) + " »" : "id " + id);
            nonEnvoye(b, r, nom + " (" + (noms.size() > 1 ? "variables manquantes " : "variable manquante ") + String.join(", ", noms) + ")");
            return;
        }
        String refus = refusVarfx(t, r);
        if (refus != null) { nonEnvoye(b, r, nom + " (" + refus + ")"); return; }

        // liaisons, comme l'importeur : les etats d'abord (mobi par mobi), puis les
        // deplacements (case / rotation / altitude) ; {id, x, y, rot, centiZ ou MIN}
        List<int[]> allers = new ArrayList<>(), retours = new ArrayList<>();
        for (CopieAppart.Liaison l : t.liaisons.getOrDefault(r.wiredId, List.of())) {
            Integer reel = t.ids.get(l.mobiId);
            if (reel == null) continue;
            String cl = classe(t.copie, l.mobiId);
            boolean boite = cl != null && cl.startsWith("wf_");     // l'utiliser ouvrirait sa fenetre
            if (l.etat != null && !boite && !PoseOutils.mettreEtat(canal, salle, droits, reel, l.etat, t.stop)) b.etatsFaux++;
            HFloorItem f = salle.furniFromId(reel);
            if (f == null || (!l.aPosition() && l.rotation == null && l.altitude == null)) continue;
            int x = l.aPosition() ? l.x + t.coin.getX() : f.getTile().getX();
            int y = l.aPosition() ? l.y + t.coin.getY() : f.getTile().getY();
            int rot = l.rotation != null ? l.rotation : rotation(f);
            if (x == f.getTile().getX() && y == f.getTile().getY() && rot == rotation(f)
                    && (l.altitude == null || Math.abs(f.getTile().getZ() * 100 - l.altitude) <= 1)) continue;   // deja en place
            allers.add(new int[]{reel, x, y, rot, l.altitude == null ? Integer.MIN_VALUE : l.altitude});
            retours.add(new int[]{reel, f.getTile().getX(), f.getTile().getY(), rotation(f),
                    l.altitude == null ? Integer.MIN_VALUE : (int) Math.round(f.getTile().getZ() * 100)});
        }
        for (int[] a : allers)
            t.deplaceur.deplacer(a[0], a[1], a[2], a[3], a[4] == Integer.MIN_VALUE ? null : a[4] / 100.0);
        PoseOutils.attendre(() -> {
            for (int[] a : allers) {
                HFloorItem g = salle.furniFromId(a[0]);
                if (g != null && (g.getTile().getX() != a[1] || g.getTile().getY() != a[2] || rotation(g) != a[3])) return false;
            }
            return true;
        }, 1500, t.stop);

        String[] motif = {null};
        int issue = enregistrer(r, tr, t.derniers, b, t.stop, motif);
        if (issue == CONFIRME) {
            b.confirmes.add(r.wiredId);
            b.rates.remove(r.wiredId);
            b.refuses.remove(r.wiredId);
            b.nonEnvoyes.remove(r.wiredId);
        } else if (!b.confirmes.contains(r.wiredId)) {
            if (issue == REFUSE) b.refuses.put(r.wiredId, nom + (motif[0] == null || motif[0].isBlank() ? "" : " (" + motif[0] + ")"));
            else if (issue == SANS_REPONSE) b.rates.put(r.wiredId, nom);
        }

        // les mobis lies reprennent leur place
        for (int[] a : retours)
            t.deplaceur.deplacer(a[0], a[1], a[2], a[3], a[4] == Integer.MIN_VALUE ? null : a[4] / 100.0);
    }

    private static void nonEnvoye(Bilan b, ReglageWired r, String texte) {
        if (b.confirmes.contains(r.wiredId)) return;       // seconde passe des add-ons : deja regle
        b.nonEnvoyes.put(r.wiredId, texte);
    }

    /**
     * Un add-on varfx ne part pas si le couple (categorie, style) est inconnu du
     * jeu (EntreeSure), ou si un wf_var de sa pile n'a pas sa variable.
     * @return la raison du refus, ou null
     */
    private static String refusVarfx(Travail t, ReglageWired r) {
        if (r.genre != ReglageWired.Genre.ADDON) return null;
        String cl = classe(t.copie, r.wiredId);
        if (!EntreeSure.estVarfx(cl)) return null;
        int cat = EntreeSure.categorieVarfx(cl), style = EntreeSure.styleVarfx(r.options);
        if (!EntreeSure.styleConnu(cat, style))
            return "effet de variable inconnu du jeu : catégorie " + cat + ", style " + style;
        CopieAppart.MobiSol moi = null;
        for (CopieAppart.MobiSol m : t.copie.sols) if (m.id == r.wiredId) moi = m;
        if (moi == null) return null;
        for (ReglageWired v : t.copie.wired(ReglageWired.Genre.VARIABLE)) {
            CopieAppart.MobiSol m = null;
            for (CopieAppart.MobiSol s : t.copie.sols) if (s.id == v.wiredId) m = s;
            if (m == null || m.x != moi.x || m.y != moi.y) continue;
            if (!t.ids.containsKey(v.wiredId)) return "wired variable de sa pile non posé";
            if (v.texte != null && t.b.variablesManquantes.containsKey(v.texte)) return "variable « " + v.texte + " » manquante";
            if (!t.b.confirmes.contains(v.wiredId)) return "wired variable de sa pile non réglé";
        }
        return null;
    }

    /** Fonds des mobis publicitaires de la copie (setupAds) ; rend le nombre envoye. */
    int appliquerFonds(CopieAppart copie, Map<Integer, Integer> ids, BooleanSupplier stop) {
        int n = 0;
        for (CopieAppart.FondPub f : copie.fonds) {
            if (stop != null && stop.getAsBoolean()) break;
            Integer id = ids.get(f.mobiId);
            if (id == null) continue;
            if (PoseOutils.envoyer(canal, PoseOutils.fondPub(id, f.image, f.decalageX, f.decalageY, f.decalageZ))) n++;
        }
        return n;
    }

    // ================================================================ interne

    private static final int CONFIRME = 0, REFUSE = 1, SANS_REPONSE = 2, ARRETE = 3;

    /** saveWired : 3 essais, chaque envoi attend SA reponse ; un refus ne se retente pas. */
    private int enregistrer(ReglageWired r, ReglageWired t, Map<ReglageWired.Genre, Long> derniers, Bilan b,
                            BooleanSupplier stop, String[] motif) {
        for (int essai = 0; essai < 3; essai++) {
            if (stop.getAsBoolean()) return ARRETE;
            long depuis = System.currentTimeMillis() - derniers.getOrDefault(r.genre, 0L);
            if (depuis < ECART_GENRE_MS && !PoseOutils.dormir(ECART_GENRE_MS - depuis, stop)) return ARRETE;
            Envoi e = new Envoi();
            synchronized (enVol) { enVol.addLast(e); }
            synchronized (recusPendant) { recusPendant.clear(); }
            ecouteJusqua = System.currentTimeMillis() + CONFIRMATION_MS;
            boolean parti = PoseOutils.envoyer(canal, t.paquetUpdate());
            derniers.put(r.genre, System.currentTimeMillis());
            if (!parti) {
                synchronized (enVol) { enVol.remove(e); }
                continue;
            }
            b.regles.add(r.wiredId);
            boolean recu;
            try { recu = e.fait.await(CONFIRMATION_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException x) { Thread.currentThread().interrupt(); return ARRETE; }
            if (recu && e.ok) return CONFIRME;
            if (recu) {
                motif[0] = e.motif;
                Journal.debug("Réglage wired : " + r.genre + " " + r.wiredId + " -> " + t.wiredId + " refusé (" + e.motif + ")");
                return REFUSE;
            }
            String recus;
            synchronized (recusPendant) { recus = recusPendant.isEmpty() ? "rien" : recusPendant.toString(); }
            Journal.debug("Réglage wired : " + r.genre + " " + r.wiredId + " -> " + t.wiredId + " sans réponse (essai " + (essai + 1)
                    + ") ; reçu pendant l'attente : " + recus);
        }
        return SANS_REPONSE;
    }

    /** La liste complete des variables de la salle (id -> variable) ; null si elle n'est pas arrivee. */
    private Map<String, PoseOutils.Variable> listeSalle(BooleanSupplier stop) {
        for (int essai = 0; essai < 2; essai++) {
            synchronized (verrouVariables) { recues.clear(); }
            if (demanderListe(stop)) {
                synchronized (verrouVariables) { return new LinkedHashMap<>(recues); }
            }
            if (stop.getAsBoolean()) break;
        }
        Journal.debug("Réglage wired : liste des variables non reçue");
        return null;
    }

    /** Demande la liste des variables de la salle et attend son dernier morceau (5 s au plus). */
    private boolean demanderListe(BooleanSupplier stop) {
        listeRecue.drainPermits();
        PoseOutils.envoyer(canal, PoseOutils.demandeVariables());
        long fin = System.currentTimeMillis() + LISTE_MS;
        try {
            while (System.currentTimeMillis() < fin && !stop.getAsBoolean())
                if (listeRecue.tryAcquire(100, TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private static int rotation(HFloorItem f) { return f.getFacing() == null ? 0 : f.getFacing().ordinal(); }

    /** Nom lisible d'un wired de la copie : son nom, sa classe, ou son genre et son id. */
    static String nom(CopieAppart c, ReglageWired r) {
        for (CopieAppart.MobiSol m : c.sols)
            if (m.id == r.wiredId) return m.nom != null ? m.nom : m.classe;
        return r.genre.cle + " " + r.wiredId;
    }
}

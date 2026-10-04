package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Applique les reglages des wired d'une copie (CopieAppart) sur des wired
 * deja poses, sans G-Presets (remplace setupWired / saveWired de
 * GPresetImporter, que ReglagesWired pilote aujourd'hui par reflexion).
 *
 * Comme l'importeur :
 *   - ordre : variables, conditions, effets, declencheurs, add-ons,
 *     selecteurs, puis les add-ons une seconde fois ; seulement les wired dont
 *     l'id de la copie est dans la table des ids (copie -> reel) ;
 *   - pour chaque wired, d'abord ses liaisons (wired « instantane ») : le mobi
 *     lie est mis a l'etat voulu (variable -110, sinon UseFurniture), puis
 *     deplace a la case, a la rotation et a l'altitude voulues ; apres
 *     l'enregistrement, il reprend sa place ;
 *   - l'enregistrement : 300 ms au moins apres le precedent du meme genre,
 *     paquet Update* construit par ReglageWired.traduire / paquetUpdate,
 *     attente de WiredSaveSuccess 5 s, 3 essais en tout ;
 *   - la table des variables (id de la copie -> id reel) se remplit en lisant
 *     WiredAllVariablesDiffs (onWiredAllVariables) : par le nom
 *     (« variables_map » de la copie : nom -> ancien id) et par le texte des
 *     wired variables ; la liste est demandee apres le dernier wired variable,
 *     et avant un wired qui cite une variable encore inconnue.
 *
 * Le deplacement des mobis lies passe par un Deplaceur : par defaut MoveObject
 * seul (l'altitude n'est pas donnee) ; avec une dalle magique, on passe
 * PoseDalle.deplacerSurDalle (comme l'importeur, qui deplace sa dalle).
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
        /** Wired sans confirmation : id (copie) -> nom lisible. */
        final Map<Integer, String> rates = new LinkedHashMap<>();
        /** Liaisons dont l'etat n'a pas pu etre donne. */
        int etatsFaux;
        boolean arrete;
        String erreur;

        String texte() {
            if (erreur != null) return "Réglage des wired impossible : " + erreur + ".";
            StringBuilder b = new StringBuilder(PoseOutils.nombre(confirmes.size(), "wired réglé", "wired réglés"));
            b.append(" sur ").append(attendus);
            if (!rates.isEmpty()) b.append(", ").append(PoseOutils.nombre(rates.size(), "raté", "ratés"))
                    .append(" (").append(String.join(", ", rates.values())).append(")");
            if (etatsFaux > 0) b.append(", ").append(PoseOutils.nombre(etatsFaux, "liaison sans son état", "liaisons sans leur état"));
            if (arrete) b.append(" (arrêté)");
            return Ui.majuscule(b.toString()) + ".";
        }
    }

    private static final ReglageWired.Genre[] ORDRE = {
            ReglageWired.Genre.VARIABLE, ReglageWired.Genre.CONDITION, ReglageWired.Genre.EFFET,
            ReglageWired.Genre.DECLENCHEUR, ReglageWired.Genre.ADDON, ReglageWired.Genre.SELECTEUR,
            ReglageWired.Genre.ADDON};

    static final long CONFIRMATION_MS = 5000, LISTE_MS = 5000, ECART_GENRE_MS = 300;

    private final Canal canal;
    private final EtatSalle salle;
    private final Droits droits;

    private final Semaphore confirmation = new Semaphore(0);
    private final Semaphore listeRecue = new Semaphore(0);
    private volatile boolean enCours;
    /** Copie et table des variables en cours (lues par l'ecouteur des variables). */
    private volatile CopieAppart copieEnCours;
    private volatile Map<String, String> variablesEnCours;
    private final Object verrouVariables = new Object();

    ReglageWiredPose(Canal canal, EtatSalle salle, Droits droits) {
        this.canal = canal;
        this.salle = salle;
        this.droits = droits;
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "WiredSaveSuccess", m -> { if (enCours) confirmation.release(); });
        canal.intercept(C, "WiredAllVariablesDiffs", m -> { if (enCours) surVariables(m); });
    }

    // ================================================================ ecouteur des variables

    private void surVariables(HMessage m) {
        PoseOutils.ListeVariables l = PoseOutils.lireListeVariables(m.getPacket());
        CopieAppart c = copieEnCours;
        Map<String, String> table = variablesEnCours;
        if (c != null && table != null) remplir(c, table, l.variables(), verrouVariables);
        if (l.dernier()) listeRecue.release();
    }

    /**
     * La table des variables d'apres une liste recue (onWiredAllVariables) :
     * par le nom de « variables_map » (nom -> ancien id), puis par le texte des
     * wired variables de la copie (texte = nom de la variable). Logique pure.
     */
    static void remplir(CopieAppart c, Map<String, String> table, List<PoseOutils.Variable> recues, Object verrou) {
        synchronized (verrou) {
            for (PoseOutils.Variable v : recues) {
                String ancien = c.tableVariables.get(v.nom());
                if (ancien != null) table.put(ancien, v.id());
            }
            for (ReglageWired w : c.wired(ReglageWired.Genre.VARIABLE))
                for (PoseOutils.Variable v : recues)
                    if (v.nom() != null && v.nom().equals(w.texte) && w.variableId != null) table.put(w.variableId, v.id());
        }
    }

    // ================================================================ reglage

    /**
     * Regle les wired de la copie.
     *
     * @param ids            id de la copie -> id reel (pose deja faite)
     * @param tableVariables id de variable de la copie -> id reel ; completee ici (peut etre vide)
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

        List<ReglageWired> tous = new ArrayList<>();
        Set<Integer> distincts = new LinkedHashSet<>();
        int nbVariables = 0;
        for (ReglageWired.Genre g : ORDRE)
            for (ReglageWired r : copie.wired(g)) {
                if (!ids.containsKey(r.wiredId)) continue;
                tous.add(r);
                distincts.add(r.wiredId);
            }
        for (ReglageWired r : copie.wired(ReglageWired.Genre.VARIABLE)) if (ids.containsKey(r.wiredId)) nbVariables++;
        b.attendus = distincts.size();
        if (tous.isEmpty()) return b;

        Map<Integer, List<CopieAppart.Liaison>> liaisons = new HashMap<>();
        for (CopieAppart.Liaison l : copie.liaisons) liaisons.computeIfAbsent(l.wiredId, k -> new ArrayList<>()).add(l);

        Map<ReglageWired.Genre, Long> derniers = new EnumMap<>(ReglageWired.Genre.class);
        int variablesFaites = 0;
        boolean listeAJour = false;
        copieEnCours = copie;
        variablesEnCours = tableVariables;
        enCours = true;
        try {
            for (ReglageWired r : tous) {
                if (stop.getAsBoolean() || !salle.dansUneSalle()) { b.arrete = stop.getAsBoolean(); break; }

                // liaisons, comme l'importeur : les etats d'abord (mobi par mobi), puis les
                // deplacements (case / rotation / altitude) ; {id, x, y, rot, centiZ ou MIN}
                List<int[]> allers = new ArrayList<>(), retours = new ArrayList<>();
                for (CopieAppart.Liaison l : liaisons.getOrDefault(r.wiredId, List.of())) {
                    Integer reel = ids.get(l.mobiId);
                    if (reel == null) continue;
                    if (l.etat != null && !PoseOutils.mettreEtat(canal, salle, droits, reel, l.etat, stop)) b.etatsFaux++;
                    HFloorItem f = salle.furniFromId(reel);
                    if (f == null || (!l.aPosition() && l.rotation == null && l.altitude == null)) continue;
                    int x = l.aPosition() ? l.x + coin.getX() : f.getTile().getX();
                    int y = l.aPosition() ? l.y + coin.getY() : f.getTile().getY();
                    int rot = l.rotation != null ? l.rotation : rotation(f);
                    if (x == f.getTile().getX() && y == f.getTile().getY() && rot == rotation(f)
                            && (l.altitude == null || Math.abs(f.getTile().getZ() * 100 - l.altitude) <= 1)) continue;   // deja en place
                    allers.add(new int[]{reel, x, y, rot, l.altitude == null ? Integer.MIN_VALUE : l.altitude});
                    retours.add(new int[]{reel, f.getTile().getX(), f.getTile().getY(), rotation(f),
                            l.altitude == null ? Integer.MIN_VALUE : (int) Math.round(f.getTile().getZ() * 100)});
                }
                for (int[] a : allers)
                    deplaceur.deplacer(a[0], a[1], a[2], a[3], a[4] == Integer.MIN_VALUE ? null : a[4] / 100.0);
                PoseOutils.attendre(() -> {
                    for (int[] a : allers) {
                        HFloorItem g = salle.furniFromId(a[0]);
                        if (g != null && (g.getTile().getX() != a[1] || g.getTile().getY() != a[2] || rotation(g) != a[3])) return false;
                    }
                    return true;
                }, 1500, stop);

                // les variables encore inconnues : la liste de la salle, une fois tant qu'elle est a jour
                if (!listeAJour && citeVariableInconnue(r, tableVariables)) {
                    demanderListe(stop);
                    listeAJour = true;
                }

                boolean ok = enregistrer(r, ids, tableVariables, derniers, b, stop);
                if (r.genre == ReglageWired.Genre.VARIABLE) {
                    listeAJour = false;
                    if (++variablesFaites >= nbVariables) { demanderListe(stop); listeAJour = true; }
                }
                if (ok) { b.confirmes.add(r.wiredId); b.rates.remove(r.wiredId); }
                else if (!b.confirmes.contains(r.wiredId)) b.rates.put(r.wiredId, nom(copie, r));

                // les mobis lies reprennent leur place
                for (int[] a : retours)
                    deplaceur.deplacer(a[0], a[1], a[2], a[3], a[4] == Integer.MIN_VALUE ? null : a[4] / 100.0);
            }
        } finally {
            enCours = false;
            copieEnCours = null;
            variablesEnCours = null;
        }
        if (stop.getAsBoolean()) b.arrete = true;
        Journal.debug("Réglage wired : " + b.texte() + " table des variables " + tableVariables);
        return b;
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

    /** saveWired : 3 essais ; true si le serveur a confirme. */
    private boolean enregistrer(ReglageWired r, Map<Integer, Integer> ids, Map<String, String> table,
                                Map<ReglageWired.Genre, Long> derniers, Bilan b, BooleanSupplier stop) {
        for (int essai = 0; essai < 3; essai++) {
            if (stop.getAsBoolean()) return false;
            long depuis = System.currentTimeMillis() - derniers.getOrDefault(r.genre, 0L);
            if (depuis < ECART_GENRE_MS && !PoseOutils.dormir(ECART_GENRE_MS - depuis, stop)) return false;
            ReglageWired t;
            synchronized (verrouVariables) { t = r.traduire(ids, table); }
            if (t == null) return false;
            confirmation.drainPermits();
            boolean parti = PoseOutils.envoyer(canal, t.paquetUpdate());
            derniers.put(r.genre, System.currentTimeMillis());
            if (parti) b.regles.add(r.wiredId);
            boolean ok = false;
            try { ok = parti && confirmation.tryAcquire(CONFIRMATION_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            if (ok) return true;
            Journal.debug("Réglage wired : " + r.genre + " " + r.wiredId + " -> " + t.wiredId + " sans confirmation (essai " + (essai + 1) + ")");
        }
        return false;
    }

    /** Demande la liste des variables de la salle et attend son dernier morceau (5 s au plus). */
    private void demanderListe(BooleanSupplier stop) {
        listeRecue.drainPermits();
        PoseOutils.envoyer(canal, PoseOutils.demandeVariables());
        long fin = System.currentTimeMillis() + LISTE_MS;
        try {
            while (System.currentTimeMillis() < fin && !stop.getAsBoolean())
                if (listeRecue.tryAcquire(100, TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Journal.debug("Réglage wired : liste des variables non reçue");
    }

    private boolean citeVariableInconnue(ReglageWired r, Map<String, String> table) {
        synchronized (verrouVariables) {
            for (String v : r.variables)
                if (v != null && !v.isEmpty() && !v.equals("0") && !v.startsWith("-") && !table.containsKey(v)) return true;
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

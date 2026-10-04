package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calques facon Photoshop : l'API pour l'interface (moteur seulement).
 *
 *   calques de base, toujours en bas de la liste (voir GroupeModele) :
 *     « Mobis »  tous les mobis qui ne sont dans aucun autre calque ; se
 *                masque, se verrouille, ne se supprime pas ;
 *     « Mur »    les murs de l'appart : verrouille, l'oeil les masque chez
 *                toi (CalqueMurs) ;
 *     « Sol »    le sol de l'appart : verrouille, le jeu ne sait pas le cacher.
 *   les autres   crees depuis une selection de mobis : les mobis PASSENT dans
 *                le nouveau calque ; un mobi n'est que dans un calque ; ordre
 *                modifiable. SUPPRIMER un calque RAMASSE ses mobis (Ctrl+Z
 *                les repose) ; pour les garder, on fusionne.
 *   verrou       un calque verrouille refuse toute action qui le modifie
 *                (deplacer, pivoter, hauteur, supprimer, fusionner, ajouter
 *                ou retirer des mobis) ; enregistre par appart.
 *
 * Tout est par appart (id reel de la salle, RoomEntryInfo), enregistre dans
 * le dossier interne de l'Atelier (GroupeStockage : ~/Library/Application
 * Support/Atelier/calques/<idAppart>.json), a CHAQUE modification, hors du
 * fil appelant, en ecriture atomique. Noms, ordre, mobis ET calques masques
 * sont gardes : en revenant dans un appart (ou apres un redemarrage), ses
 * calques reviennent et ceux qui etaient masques sont remasques tout seuls.
 * Toute erreur de lecture / d'ecriture est dite (erreur(), chat du jeu).
 * Un id qui n'existe plus dans la salle est oublie, sauf s'il est masque par
 * nous (Calques.estMasque) ; jamais plus de la moitie d'un coup (salle pas
 * encore chargee ?).
 *
 * Fils : toutes les methodes sont appelables depuis n'importe quel fil (le
 * fil JavaFX compris : rien de lent n'y est fait). Les actions longues
 * (masquer, afficher, ramasser, deplacer, dupliquer) tournent dans leur
 * propre fil et rendent un {@link Tache} (Arreter) ; leur {@link Progression}
 * est appelee sur le fil JavaFX. Les ecouteurs ({@link #ecouter}) aussi.
 *
 * Masquer passe par Calques (cote client seulement), sous la raison
 * « groupe:<id> » : les autres calques de l'Atelier (wired, techniques...)
 * restent independants.
 */
public final class Groupes {

    private Groupes() { }

    /** Calques de base. */
    public static final String MOBIS = GroupeModele.MOBIS, MUR = GroupeModele.MUR, SOL = GroupeModele.SOL;
    /** Ancien nom : le calque de base des mobis. */
    public static final String CALQUE0 = MOBIS;
    /**
     * Pseudo-calque : la selection courante. Toutes les actions qui prennent un
     * calque (apercu en fantomes, simulation, dupliquer, miroir) marchent
     * aussi sur elle ; il n'apparait jamais dans la liste des calques.
     */
    public static final String SELECTION = "@selection";

    // ================================================================ types

    /** Une ligne de la liste des calques. */
    public static final class Info {
        public final String id, nom;
        public final int sols, murs;
        /** base : Mobis, Mur ou Sol ; decor : Mur ou Sol (rien d'autre que l'oeil). */
        public final boolean visible, base, decor, verrou, masquable;
        Info(String id, String nom, int sols, int murs, boolean visible, boolean verrou) {
            this.id = id; this.nom = nom; this.sols = sols; this.murs = murs; this.visible = visible;
            this.base = GroupeModele.estBase(id); this.decor = GroupeModele.estDecor(id);
            this.verrou = verrou; this.masquable = !SOL.equals(id);
        }
        public int nombre() { return sols + murs; }
        /** Un calque cree par l'utilisatrice (ni de base, ni la selection). */
        public boolean normal() { return !base && !SELECTION.equals(id); }
        @Override public String toString() { return nom + " (" + nombre() + ")" + (visible ? "" : " [masqué]") + (verrou ? " [verrou]" : ""); }
    }

    /** Ce que contient un calque, pour l'apercu chiffre avant d'agir. */
    public static final class Compte {
        /** Presents dans la salle. */
        public final int sols, murs;
        /** Ids du calque introuvables dans la salle (masques par nous, ou disparus). */
        public final int absents;
        /** Parmi les sols : wired (ignores par la duplication) et classes inconnues de la furnidata. */
        public final int wired, inconnus;
        Compte(int sols, int murs, int absents, int wired, int inconnus) {
            this.sols = sols; this.murs = murs; this.absents = absents; this.wired = wired; this.inconnus = inconnus;
        }
        public int total() { return sols + murs; }
        @Override public String toString() {
            return sols + " sol(s), " + murs + " mur(s)" + (absents > 0 ? ", " + absents + " absent(s)" : "")
                    + (wired > 0 ? ", " + wired + " wired" : "") + (inconnus > 0 ? ", " + inconnus + " inconnu(s)" : "");
        }
    }

    /** Simulation d'un deplacement / d'une duplication de (dx, dy). */
    public static final class Simulation {
        public final int sols, murs;
        /** Sols dont une case d'arrivee est hors du plan (ils ne pourront pas aller la). */
        public final int horsPlan;
        /** Murs dont la position est illisible (ne peuvent pas etre decales). */
        public final int mursIllisibles;
        /** Sols poses SUR un mobi qui n'est pas dans le calque (ils atterriront sur ce qu'il y a la-bas). */
        public final int surAutreChose;
        /** Cases d'arrivee deja occupees par des mobis hors du calque. */
        public final int casesOccupees;
        Simulation(int sols, int murs, int horsPlan, int mursIllisibles, int surAutreChose, int casesOccupees) {
            this.sols = sols; this.murs = murs; this.horsPlan = horsPlan; this.mursIllisibles = mursIllisibles;
            this.surAutreChose = surAutreChose; this.casesOccupees = casesOccupees;
        }
        public int total() { return sols + murs; }
        @Override public String toString() {
            return sols + " sol(s), " + murs + " mur(s)"
                    + (horsPlan > 0 ? ", " + horsPlan + " hors du plan" : "")
                    + (mursIllisibles > 0 ? ", " + mursIllisibles + " mur(s) illisible(s)" : "")
                    + (casesOccupees > 0 ? ", " + casesOccupees + " case(s) d'arrivée occupée(s)" : "");
        }
    }

    /** Bilan d'une action, verifie apres coup dans la salle. */
    public static final class Resultat {
        public final boolean ok, arrete;
        public final int voulus, reussis, echecs;
        public final String message;
        /** Duplication : id du nouveau calque (null sinon). */
        public final String nouveauCalque;
        Resultat(boolean ok, boolean arrete, int voulus, int reussis, int echecs, String message, String nouveau) {
            this.ok = ok; this.arrete = arrete; this.voulus = voulus; this.reussis = reussis; this.echecs = echecs;
            this.message = message; this.nouveauCalque = nouveau;
        }
        static Resultat refus(String m) { return new Resultat(false, false, 0, 0, 0, m, null); }
        @Override public String toString() { return message; }
    }

    /** Suivi d'une action ; appele sur le fil JavaFX. */
    public interface Progression {
        default void progres(int fait, int total, String texte) { }
        default void fin(Resultat r) { }
    }

    /** Une action en cours. */
    public static final class Tache {
        final String nom;
        private final Progression p;
        private volatile boolean arret = false, finie = false;
        private volatile Resultat resultat;
        private volatile long dernier = 0;

        Tache(String nom, Progression p) { this.nom = nom; this.p = p == null ? new Progression() { } : p; }

        public void arreter() { arret = true; }
        public boolean arretee() { return arret; }
        public boolean enCours() { return !finie; }
        public Resultat resultat() { return resultat; }

        /** Progression (au plus ~8 fois par seconde, sauf la derniere). */
        void progres(int fait, int total, String texte) {
            long t = System.currentTimeMillis();
            if (fait < total && t - dernier < 120) return;
            dernier = t;
            fx(() -> p.progres(fait, total, texte));
        }

        void dire(String texte) { dernier = System.currentTimeMillis(); fx(() -> p.progres(-1, -1, texte)); }

        void finir(Resultat r) {
            resultat = r;
            finie = true;
            Journal.debug("calques : " + nom + " : " + r.message);
            fx(() -> p.fin(r));                // le panneau dit le resultat (CalqueActions.resultat) : pas de second message
            prevenir();
        }
    }

    /** La selection courante (copie). */
    public static final class Selection {
        public final Set<Integer> sols, murs;
        Selection(Set<Integer> s, Set<Integer> m) { sols = Collections.unmodifiableSet(s); murs = Collections.unmodifiableSet(m); }
        public int nombre() { return sols.size() + murs.size(); }
        public boolean vide() { return nombre() == 0; }
    }

    // ================================================================ etat

    private static final Object V = new Object();
    private static GroupeModele.Plan plan;                   // salle courante (sous V)
    /** Calques masques (ids, calques de base compris). */
    private static final Set<String> masques = ConcurrentHashMap.newKeySet();
    /** Calque Mobis masque : les mobis effectivement masques (le complement d'alors, et ceux rendus depuis). */
    private static final Set<Integer> masques0S = ConcurrentHashMap.newKeySet(), masques0M = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static final ExecutorService DISQUE = executeur("calques-disque");
    private static final ExecutorService MASQUES = executeur("calques-masques");
    private static final AtomicBoolean occupe = new AtomicBoolean(false);
    static final File DOSSIER = GroupeStockage.dossier();
    /** Derniere erreur de disque (null = tout va bien). */
    private static volatile String erreur = null;
    /** Appart dont le fichier n'a pas pu etre lu : on n'ecrase pas son fichier. */
    private static volatile int salleIllisible = Integer.MIN_VALUE;
    private static volatile boolean migre = false;

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(Groupes::enregistrer, "atelier-calques-fin"));
        } catch (Throwable ignored) { }
    }

    private static ExecutorService executeur(String nom) {
        return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "atelier-" + nom); t.setDaemon(true); return t; });
    }

    // ================================================================ ecoute

    /** Prevenu sur le fil JavaFX a chaque changement (calques, salle, selection, apercu). */
    public static void ecouter(Runnable r) { if (r != null) ecouteurs.add(r); installer(); }

    public static void retirerEcouteur(Runnable r) { ecouteurs.remove(r); }

    private static final AtomicBoolean prevenirPrevu = new AtomicBoolean(false);

    /** Regroupe les rafales : un seul passage sur le fil FX. */
    static void prevenir() {
        if (!prevenirPrevu.compareAndSet(false, true)) return;
        fx(() -> {
            prevenirPrevu.set(false);
            for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable t) { System.err.println("[Atelier] calques : ecouteur : " + t); }
        });
    }

    static void fx(Runnable r) {
        try {
            if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
        } catch (IllegalStateException pasDeFx) { r.run(); }       // tests sans JavaFX
    }

    // ================================================================ salle

    static int salleCourante() {
        try { EtatSalle s = Salle.etat(); return s == null ? -1 : s.getRoomId(); } catch (Throwable t) { return -1; }
    }

    /** La salle dont la liste parle (-1 hors salle). */
    public static int salle() { synchronized (V) { GroupeModele.Plan p = plan(); return p == null ? -1 : p.salle; } }

    /** Le plan de la salle courante, charge au besoin. Sous V. */
    private static GroupeModele.Plan plan() {
        int s = salleCourante();
        if (s == -1) return null;
        if (plan == null || plan.salle != s) {
            plan = charger(s);
            masques.clear(); masques0S.clear(); masques0M.clear();
            CalqueMurs.oublier();
        }
        return plan;
    }

    static File fichier(int salle) { return GroupeStockage.fichier(DOSSIER, salle); }

    /** Derniere erreur de lecture / d'ecriture des calques (null = aucune). */
    public static String erreur() { return erreur; }

    /** Une erreur que l'utilisatrice doit voir : panneau (erreur()), chat du jeu, console. */
    static void signaler(String message) {
        erreur = message;
        try { Journal.erreur(message); } catch (Throwable ignored) { }
        prevenir();
    }

    /** Les calques de l'appart s depuis le disque. Sous V (petits fichiers : rapide). */
    private static GroupeModele.Plan charger(int s) {
        if (!migre) {
            migre = true;
            try {
                int n = GroupeStockage.migrer(DOSSIER, GroupeStockage.anciensDossiers());
                if (n > 0) Journal.debug("calques : " + n + " appart(s) recopie(s) dans " + DOSSIER);
            } catch (Throwable t) {
                signaler("Calques : anciens calques pas recopiés (" + t.getMessage() + ").");
            }
        }
        try {
            GroupeModele.Plan p = GroupeStockage.lire(DOSSIER, s);
            if (salleIllisible == s) salleIllisible = Integer.MIN_VALUE;
            Journal.debug("calques : appart " + s + " : " + p.calques.size() + " calque(s) relu(s).");
            return p;
        } catch (Throwable t) {
            boolean abime = String.valueOf(t.getMessage()).startsWith("Fichier des calques abîmé");
            if (!abime) salleIllisible = s;        // fichier present mais pas lisible : surtout ne pas l'ecraser
            signaler("Calques de cet appart pas relus : " + t.getMessage());
            return new GroupeModele.Plan(s);
        }
    }

    /** Enregistre (hors fil appelant). Sous V. */
    private static void sauver(GroupeModele.Plan p) {
        if (p == null) return;
        if (p.salle == salleIllisible) {
            signaler("Calques pas enregistrés : le fichier de cet appart n'a pas pu être lu (" + fichier(p.salle) + ").");
            return;
        }
        String texte = p.texte();
        int s = p.salle;
        DISQUE.submit(() -> ecrire(s, texte));
    }

    static void ecrire(int salle, String texte) {
        try {
            GroupeStockage.ecrire(DOSSIER, salle, texte);
            if (erreur != null && erreur.startsWith("Calques pas enregistrés")) { erreur = null; prevenir(); }
        } catch (Throwable t) {
            signaler("Calques pas enregistrés (" + fichier(salle) + ") : " + t);
        }
    }

    /** Attend la fin des ecritures en cours (fermeture de l'Atelier). */
    public static void enregistrer() {
        try { DISQUE.submit(() -> { }).get(5, TimeUnit.SECONDS); }
        catch (Throwable t) { System.err.println("[Atelier] calques : ecritures pas terminees a la fermeture : " + t); }
    }

    /** Ids des sols de la salle (sans fantomes ni grille). */
    static Set<Integer> idsSols() {
        Set<Integer> r = new LinkedHashSet<>();
        for (HFloorItem it : Salle.sols()) if (!GroupeFantomes.estFantome(it.getId())) r.add(it.getId());
        return r;
    }

    static Set<Integer> idsMurs() {
        Set<Integer> r = new LinkedHashSet<>();
        for (HWallItem it : Salle.murs()) if (!GroupeFantomes.estFantome(it.getId())) r.add(it.getId());
        return r;
    }

    // ================================================================ liste

    /** Les calques de la salle courante, du haut vers le bas ; puis Mobis, Mur et Sol. Vide hors salle. */
    public static List<Info> lister() {
        installer();
        Set<Integer> s = idsSols(), m = idsMurs();
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return List.of();
            List<Info> r = new ArrayList<>();
            for (GroupeModele.Calque c : p.calques)
                r.add(new Info(c.id, c.nom, c.sols.size(), c.murs.size(), vu(p, c.id), p.verrouille(c.id)));
            r.add(new Info(MOBIS, GroupeModele.NOM_MOBIS, p.complement(s, false).size(), p.complement(m, true).size(),
                    vu(p, MOBIS), p.verrouille(MOBIS)));
            r.add(new Info(MUR, GroupeModele.NOM_MUR, 0, 0, vu(p, MUR), true));
            r.add(new Info(SOL, GroupeModele.NOM_SOL, 0, 0, true, true));
            return r;
        }
    }

    /** Visible : ni masque maintenant, ni voulu masque. Sous V. */
    private static boolean vu(GroupeModele.Plan p, String id) { return !masques.contains(id) && !p.cache(id); }

    /** Une ligne, ou null. */
    public static Info info(String id) {
        if (SELECTION.equals(id)) {
            Selection s = selection();
            return s.vide() ? null : new Info(SELECTION, "Sélection", s.sols.size(), s.murs.size(), true, false);
        }
        for (Info i : lister()) if (i.id.equals(id)) return i;
        return null;
    }

    /** Le calque d'un mobi (MOBIS s'il n'est dans aucun) ; null hors salle. */
    public static String calqueDe(int id, boolean mural) {
        synchronized (V) { GroupeModele.Plan p = plan(); return p == null ? null : p.calqueDe(id, mural); }
    }

    /** Ids d'un calque (copie) : {sols, murs}. Mobis : le complement ; Mur et Sol : aucun mobi. */
    public static List<Set<Integer>> mobis(String id) {
        if (SELECTION.equals(id)) {
            Selection sel = selection();
            return List.of(new LinkedHashSet<>(sel.sols), new LinkedHashSet<>(sel.murs));
        }
        Set<Integer> s = idsSols(), m = idsMurs();
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return List.of(Set.of(), Set.of());
            if (MOBIS.equals(id)) return List.of(new LinkedHashSet<>(p.complement(s, false)), new LinkedHashSet<>(p.complement(m, true)));
            if (GroupeModele.estDecor(id)) return List.of(new LinkedHashSet<>(), new LinkedHashSet<>());
            GroupeModele.Calque c = p.calque(id);
            if (c == null) return List.of(Set.of(), Set.of());
            return List.of(new LinkedHashSet<>(c.sols), new LinkedHashSet<>(c.murs));
        }
    }

    // ================================================================ edition

    /** Nouveau calque en haut de la liste. @return son id, null hors salle ou sans mobi. */
    public static String creer(String nom, Collection<Integer> sols, Collection<Integer> murs) {
        return creer(nom, sols, murs, null);
    }

    /**
     * Nouveau calque (au-dessus de « dessus », ou en haut) : les mobis donnes
     * y PASSENT (ils sortent de leur ancien calque). Sans controle des verrous :
     * pour les mobis qu'on vient de poser (copies). Pour une selection de
     * l'utilisatrice, voir refusAjout.
     */
    static String creer(String nom, Collection<Integer> sols, Collection<Integer> murs, String dessus) {
        installer();
        String id;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return null;
            id = p.creer(nom, propre(sols, false), propre(murs, true), dessus);
            sauver(p);
        }
        resynchroniser();
        prevenir();
        return id;
    }

    /**
     * Pourquoi ces mobis ne peuvent pas aller dans ce calque (null = ils le
     * peuvent) : calque cible verrouille, ou mobis pris dans un calque
     * verrouille. cible null = un nouveau calque.
     */
    public static String refusAjout(String cible, Collection<Integer> sols, Collection<Integer> murs) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return "Entre dans un appart.";
            if (cible != null) {
                if (GroupeModele.estDecor(cible)) return "« " + p.nom(cible) + " » ne contient pas de mobis.";
                if (!p.existe(cible)) return "Calque introuvable.";
                if (p.verrouille(cible)) return "« " + p.nom(cible) + " » est verrouillé : clique son cadenas pour le déverrouiller.";
            }
            List<String> v = p.verrouillesTouches(sols, murs);
            if (cible != null) v.remove(cible);
            if (v.isEmpty()) return null;
            return (v.size() == 1 ? "Des mobis choisis sont dans « " + p.nom(v.get(0)) + " », verrouillé"
                    : "Des mobis choisis sont dans des calques verrouillés (" + noms(p, v) + ")")
                    + " : déverrouille-le d'abord.";
        }
    }

    private static String noms(GroupeModele.Plan p, Collection<String> ids) {
        List<String> l = new ArrayList<>();
        for (String id : ids) l.add("« " + p.nom(id) + " »");
        return String.join(", ", l);
    }

    /** Nouveau calque avec la selection courante (ses mobis y passent), puis la selection est videe. */
    public static String creerDepuisSelection(String nom) {
        Selection s = selection();
        if (s.vide() || refusAjout(null, s.sols, s.murs) != null) return null;
        String id = creer(nom, s.sols, s.murs);
        if (id != null) GroupeSelection.vider();
        return id;
    }

    /** Ajoute des mobis a un calque (les retire des autres). MOBIS = les sortir de tout calque. Verrous respectes. */
    public static boolean ajouter(String calqueId, Collection<Integer> sols, Collection<Integer> murs) {
        if (refusAjout(calqueId, sols, murs) != null) return false;
        return ajouterSansVerrou(calqueId, sols, murs);
    }

    /** Pour les actions de l'Atelier (dalles posees sous une copie) : sans controle des verrous. */
    static boolean ajouterSansVerrou(String calqueId, Collection<Integer> sols, Collection<Integer> murs) {
        boolean ok;
        List<Integer> s = propre(sols, false), m = propre(murs, true);
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return false;
            ok = p.ajouter(calqueId, s, m);
            if (ok) sauver(p);
        }
        if (ok && MOBIS.equals(calqueId) && masques.contains(MOBIS)) { masques0S.addAll(s); masques0M.addAll(m); }
        if (ok) { resynchroniser(); prevenir(); }
        return ok;
    }

    /** Sort des mobis de tout calque (ils vont dans Mobis). */
    public static boolean retirer(Collection<Integer> sols, Collection<Integer> murs) {
        return ajouter(MOBIS, sols, murs);
    }

    /** Les calques de base ne se renomment pas. */
    public static boolean renommer(String calqueId, String nom) {
        boolean ok;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return false;
            ok = p.renommer(calqueId, nom);
            if (ok) sauver(p);
        }
        if (ok) prevenir();
        return ok;
    }

    public static boolean monter(String calqueId) { return bouger(calqueId, -1); }

    public static boolean descendre(String calqueId) { return bouger(calqueId, 1); }

    private static boolean bouger(String id, int sens) {
        boolean ok;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return false;
            ok = p.bouger(id, sens);
            if (ok) sauver(p);
        }
        if (ok) prevenir();
        return ok;
    }

    /** Place le calque a la position voulue (0 = tout en haut ; glisser-deposer). Calques de base : fixes. */
    public static boolean placer(String calqueId, int place) {
        boolean ok;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return false;
            ok = p.placer(calqueId, place);
            if (ok) sauver(p);
        }
        if (ok) prevenir();
        return ok;
    }

    /** Nombre de calques crees (sans les calques de base). */
    public static int nombreCalques() {
        synchronized (V) { GroupeModele.Plan p = plan(); return p == null ? 0 : p.calques.size(); }
    }

    // ================================================================ verrou

    public static boolean verrouille(String calqueId) {
        if (SELECTION.equals(calqueId)) return false;
        synchronized (V) { GroupeModele.Plan p = plan(); return p != null && p.verrouille(calqueId); }
    }

    /**
     * Verrouille (ou deverrouille) ces calques ; enregistre pour l'appart.
     * Mur et Sol restent verrouilles. @return nombre de calques changes
     */
    public static int verrouiller(Collection<String> ids, boolean v) {
        int n = 0;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return 0;
            for (String id : ids) if (p.verrouiller(id, v)) n++;
            if (n > 0) sauver(p);
        }
        if (n > 0) prevenir();
        return n;
    }

    /**
     * Pour les autres outils de l'Atelier (deplacer, hauteur, aligner...) :
     * pourquoi ces mobis ne doivent pas bouger (ils sont dans un calque
     * verrouille, Mobis compris) ; null = libres. Hors salle : null.
     */
    public static String refusVerrouMobis(Collection<Integer> sols, Collection<Integer> murs) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return null;
            List<String> v = p.verrouillesTouches(sols, murs);
            if (v.isEmpty()) return null;
            return (v.size() == 1 ? "Ces mobis sont dans le calque verrouillé " : "Ces mobis sont dans les calques verrouillés ")
                    + noms(p, v) + " : déverrouille-le dans le panneau des calques.";
        }
    }

    /** Pourquoi une action qui MODIFIE ce calque (ou la selection) est refusee ; null = permise. */
    public static String refusVerrou(String calqueId) {
        if (SELECTION.equals(calqueId)) {
            Selection s = selection();
            synchronized (V) {
                GroupeModele.Plan p = plan();
                if (p == null) return null;
                List<String> v = p.verrouillesTouches(s.sols, s.murs);
                if (v.isEmpty()) return null;
                return "La sélection contient des mobis du calque verrouillé " + noms(p, v) + " : déverrouille-le d'abord.";
            }
        }
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null || !p.verrouille(calqueId)) return null;
            if (GroupeModele.estDecor(calqueId)) return "« " + p.nom(calqueId) + " » se masque seulement.";
            return "« " + p.nom(calqueId) + " » est verrouillé : clique son cadenas pour le déverrouiller.";
        }
    }

    // ============================================================= supprimer

    /** Ce que supprimer ces calques ramasserait, pour decider d'une confirmation. */
    public static final class Suppression {
        public final List<String> ids, noms;
        public final int mobis, wired;
        /** null si la suppression est possible. */
        public final String refus;
        Suppression(List<String> ids, List<String> noms, int mobis, int wired, String refus) {
            this.ids = ids; this.noms = noms; this.mobis = mobis; this.wired = wired; this.refus = refus;
        }
        /** Wired (reglages perdus) ou beaucoup de mobis : une confirmation courte. */
        public boolean aConfirmer() { return GroupeModele.confirmationVoulue(mobis, wired); }
        public String quoi() { return noms.size() == 1 ? "« " + noms.get(0) + " »" : noms.size() + " calques"; }
    }

    public static Suppression preparerSuppression(Collection<String> ids) {
        List<String> l = new ArrayList<>(new LinkedHashSet<>(ids));
        List<String> noms = new ArrayList<>();
        String refus;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return new Suppression(l, noms, 0, 0, "Entre dans un appart.");
            refus = p.refusSuppression(l);
            for (String id : l) noms.add(String.valueOf(p.nom(id)));
        }
        int n = 0, w = 0;
        if (refus == null) for (String id : l) { Compte c = compter(id); n += c.total(); w += c.wired; }
        return new Suppression(l, noms, n, w, refus);
    }

    /**
     * Supprime ces calques ET RAMASSE leurs mobis (comme Photoshop : un calque
     * supprime emporte son contenu). Historique les reprend en une action :
     * Ctrl+Z les repose. Les calques disparaissent tout de suite de la liste.
     * Calques de base et verrouilles : refuse.
     */
    public static Tache supprimer(Collection<String> ids, Progression prog) {
        installer();
        Tache t = new Tache("supprimer", prog);
        List<String> l = new ArrayList<>(new LinkedHashSet<>(ids));
        if (salle() == -1) { t.finir(Resultat.refus("Tu n'es pas dans une salle.")); return t; }
        // le verrou est pris AVANT de toucher au plan : lancer() ne peut plus refuser
        // apres coup (calques partis, mobis ni ramasses ni reaffiches)
        if (!occupe.compareAndSet(false, true)) { t.finir(Resultat.refus("Une autre action sur les calques est en cours.")); return t; }
        Set<Integer> sols = new LinkedHashSet<>(), murs = new LinkedHashSet<>();
        List<String> noms = new ArrayList<>();
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) { occupe.set(false); t.finir(Resultat.refus("Tu n'es pas dans une salle.")); return t; }
            String refus = p.refusSuppression(l);
            if (refus != null) { occupe.set(false); t.finir(Resultat.refus(refus)); return t; }
            for (String id : l) {
                GroupeModele.Calque c = p.supprimer(id);
                if (c == null) continue;
                noms.add(c.nom);
                sols.addAll(c.sols);
                murs.addAll(c.murs);
            }
            sauver(p);
        }
        // Les mobis masques le restent jusqu'au ramassage (le jeu les retire alors pour de bon).
        List<String> etaientMasques = new ArrayList<>();
        for (String id : l) if (masques.remove(id)) etaientMasques.add(id);
        String apercu = GroupeApercu.calque();
        if (apercu != null && l.contains(apercu)) annulerApercu();
        prevenir();
        String quoi = noms.size() == 1 ? "Calque « " + noms.get(0) + " » supprimé" : noms.size() + " calques supprimés";
        Runnable remontrer = () -> {        // ceux que le jeu a refuse de ramasser reviennent
            if (etaientMasques.isEmpty()) return;
            MASQUES.submit(() -> {
                for (String id : etaientMasques)
                    try { regler(id, List.of(), List.of()); } catch (Throwable e) { System.err.println("[Atelier] calques : supprimer : " + e); }
                prevenir();
            });
        };
        if (sols.isEmpty() && murs.isEmpty()) {
            occupe.set(false);
            remontrer.run();
            t.finir(new Resultat(true, false, 0, 0, 0, quoi + " (il était vide).", null));
            return t;
        }
        return lancer("supprimer", null, false, prog, true, x -> {
            Resultat res;
            try { res = GroupeActions.ramasserIds(x, sols, murs); }
            finally { remontrer.run(); }
            return new Resultat(res.ok, res.arrete, res.voulus, res.reussis, res.echecs, quoi + ". " + res.message, null);
        }, remontrer);
    }

    // ============================================================== fusionner

    /**
     * Fusionne « source » dans « cible » : ses mobis passent dans la cible, le
     * calque source disparait (sauvegarde comme le reste). Cible MOBIS : ils
     * retournent au calque Mobis. Le resultat suit l'etat de la cible : cible
     * masquee -> les mobis de la source sont masques aussi ; cible visible ->
     * ceux de la source reviennent s'ils etaient masques. Rien ne change dans
     * la salle cote serveur. Calques verrouilles : refuse.
     * @return nombre de mobis passes dans la cible, -1 si impossible
     */
    public static int fusionner(String source, String cible) {
        if (source == null || cible == null || SELECTION.equals(source) || SELECTION.equals(cible)) return -1;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return -1;
            List<Integer> s = new ArrayList<>(), m = new ArrayList<>();
            GroupeModele.Calque c = p.calque(source);
            if (c != null) { s.addAll(c.sols); m.addAll(c.murs); }
            int n = p.fusionner(source, cible);          // verrous, calques de base : -1
            if (n < 0) return -1;
            sauver(p);
            apresFusion(new GroupeModele.Fusion(cible, List.of(source), n), s, m);
            return n;
        }
    }

    /**
     * « Fusionner les calques » de Photoshop : plusieurs calques -> dans le
     * plus haut (ou dans Mobis s'il en est) ; un seul -> avec celui du dessous.
     * @return la fusion faite, ou null (voir refusFusion)
     */
    public static GroupeModele.Fusion fusionner(Collection<String> ids) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return null;
            return avantFusion(p, new ArrayList<>(ids));
        }
    }

    /** Pourquoi ces calques ne peuvent pas etre fusionnes (null = possible). */
    public static String refusFusion(Collection<String> ids) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            return p == null ? "Entre dans un appart." : p.refusFusion(new ArrayList<>(ids));
        }
    }

    /** Ou irait la fusion (null = nulle part). */
    public static String cibleFusion(Collection<String> ids) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            return p == null ? null : p.cibleFusion(new ArrayList<>(ids));
        }
    }

    /** Sous V. */
    private static GroupeModele.Fusion avantFusion(GroupeModele.Plan p, List<String> ids) {
        if (p.refusFusion(ids) != null) return null;
        List<Integer> s = new ArrayList<>(), m = new ArrayList<>();
        String cible = p.cibleFusion(ids);
        for (String id : ids) {
            if (id.equals(cible)) continue;
            GroupeModele.Calque c = p.calque(id);
            if (c != null) { s.addAll(c.sols); m.addAll(c.murs); }
        }
        GroupeModele.Fusion f = p.fusionnerTous(ids);
        if (f == null) return null;
        sauver(p);
        apresFusion(f, s, m);
        return f;
    }

    /** Les masques suivent la cible ; les sources ne masquent plus rien. */
    private static void apresFusion(GroupeModele.Fusion f, List<Integer> sols, List<Integer> murs) {
        List<String> sourcesMasquees = new ArrayList<>();
        for (String s : f.sources) if (masques.remove(s)) sourcesMasquees.add(s);
        if (MOBIS.equals(f.cible) && masques.contains(MOBIS)) { masques0S.addAll(sols); masques0M.addAll(murs); }
        // d'abord la cible (masquee : elle prend les nouveaux mobis), puis les
        // sources ne masquent plus rien : un mobi masque par la cible le reste.
        resynchroniser();
        if (!sourcesMasquees.isEmpty()) MASQUES.submit(() -> {
            for (String s : sourcesMasquees)
                try { regler(s, List.of(), List.of()); }
                catch (Throwable e) { System.err.println("[Atelier] calques : fusion : " + e); }
            prevenir();
        });
        String a = GroupeApercu.calque();
        if (a != null && f.sources.contains(a)) annulerApercu();
        prevenir();
    }

    /** Ce qu'il y a dans le calque (pour l'apercu chiffre, ex. avant de supprimer). */
    public static Compte compter(String calqueId) {
        List<Set<Integer>> ids = mobis(calqueId);
        int s = 0, m = 0, abs = 0, w = 0, inc = 0;
        for (int id : ids.get(0)) {
            HFloorItem it = Salle.sol(id);
            if (it == null) { abs++; continue; }
            s++;
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null) inc++; else if (Wired.estWired(c)) w++;
        }
        for (int id : ids.get(1)) { if (Salle.mur(id) == null) abs++; else m++; }
        return new Compte(s, m, abs, w, inc);
    }
    /** Ids : sans null, sans fantome. */
    private static List<Integer> propre(Collection<Integer> ids, boolean mural) {
        List<Integer> r = new ArrayList<>();
        if (ids != null) for (Integer i : ids)
            if (i != null && !GroupeFantomes.estFantome(i) && !GrilleCalcul.estFictif(i)) r.add(i);
        return r;
    }

    // ================================================================ masquer

    static String raison(String calqueId) { return "groupe:" + calqueId; }

    /** Moment du dernier reglage de Calques (la surveillance attend un peu apres). */
    private static volatile long derniereRegle = 0;

    private static void regler(String id, List<HFloorItem> sols, List<HWallItem> murs) {
        derniereRegle = System.currentTimeMillis();
        try { Calques.regler(raison(id), sols, murs); }
        finally { derniereRegle = System.currentTimeMillis(); }
    }

    public static boolean visible(String calqueId) {
        if (masques.contains(calqueId)) return false;
        synchronized (V) { GroupeModele.Plan p = plan(); return p == null || !p.cache(calqueId); }
    }

    /** Retient (et enregistre) qu'un calque est masque ou non, pour la prochaine entree dans l'appart. */
    private static void retenirCache(String id, boolean cache) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p != null && p.cacher(id, cache)) sauver(p);
        }
    }

    /** Masque le calque chez toi seulement (le serveur ne sait rien ; recharger la salle le fait revenir). */
    public static Tache masquer(String calqueId, Progression p) { return visibilite(calqueId, true, p); }

    /** Reaffiche le calque. */
    public static Tache afficher(String calqueId, Progression p) { return visibilite(calqueId, false, p); }

    private static Tache visibilite(String id, boolean masquer, Progression prog) {
        installer();
        Tache t = new Tache(masquer ? "masquer" : "afficher", prog);
        if (salle() == -1) { t.finir(Resultat.refus("Tu n'es pas dans une salle.")); return t; }
        if (SOL.equals(id)) {
            t.finir(Resultat.refus("Le sol ne peut pas être masqué : le jeu ne sait pas le cacher sans casser les clics."));
            return t;
        }
        if (MUR.equals(id)) {
            // Murs de l'appart : on renvoie au client ses reglages d'affichage, murs caches ou non.
            boolean ok = CalqueMurs.cacher(masquer);
            if (ok) { if (masquer) masques.add(id); else masques.remove(id); retenirCache(id, masquer); prevenir(); }
            t.finir(ok ? new Resultat(true, false, 1, 1, 0, masquer ? "Murs masqués chez toi."
                            : "Murs réaffichés." + (CalqueMurs.origineConnue() ? ""
                              : " Épaisseurs d'origine pas encore lues : recharge l'appart si elles ont changé."), null)
                       : Resultat.refus("Le jeu n'a pas accepté le réglage des murs."));
            return t;
        }
        if (info(id) == null) { t.finir(Resultat.refus("Calque introuvable.")); return t; }
        if (!Calques.pret()) { t.finir(Resultat.refus("Le moteur des calques n'est pas encore prêt (Atelier en cours de démarrage ?).")); return t; }
        retenirCache(id, masquer);
        MASQUES.submit(() -> {
            try {
                t.dire(masquer ? "Je masque..." : "Je réaffiche...");
                List<Set<Integer>> ids = mobis(id);
                if (masquer) {
                    masques.add(id);
                    if (MOBIS.equals(id)) { masques0S.clear(); masques0S.addAll(ids.get(0)); masques0M.clear(); masques0M.addAll(ids.get(1)); }
                } else {
                    masques.remove(id);
                    if (MOBIS.equals(id)) { masques0S.clear(); masques0M.clear(); }
                }
                prevenir();
                List<HFloorItem> sols = new ArrayList<>();
                List<HWallItem> murs = new ArrayList<>();
                if (masquer) {
                    for (int i : ids.get(0)) { HFloorItem it = Salle.sol(i); if (it != null) sols.add(it); }
                    for (int i : ids.get(1)) { HWallItem it = Salle.mur(i); if (it != null) murs.add(it); }
                }
                regler(id, sols, murs);
                // verification
                int voulus = 0, faits = 0;
                for (int i : ids.get(0)) if (Salle.sol(i) != null) { voulus++; if (Calques.estMasque(i, false) == masquer) faits++; }
                for (int i : ids.get(1)) if (Salle.mur(i) != null) { voulus++; if (Calques.estMasque(i, true) == masquer) faits++; }
                int ko = voulus - faits;
                String msg = (masquer ? faits + " mobi(s) masqué(s) chez toi" : faits + " mobi(s) visible(s)")
                        + (ko > 0 ? (masquer ? ", " + ko + " pas masqué(s)." : ", " + ko + " encore masqué(s) par un autre calque de l'Atelier.") : ".");
                t.finir(new Resultat(ko == 0, false, voulus, faits, ko, msg, null));
            } catch (Throwable e) {
                t.finir(Resultat.refus("Erreur : " + e));
            }
        });
        return t;
    }

    /**
     * Apres un changement d'appartenance : les calques masques masquent
     * exactement leurs mobis (un mobi ajoute a un calque masque disparait,
     * un mobi sorti d'un calque masque revient).
     */
    static void resynchroniser() {
        if (masques.isEmpty()) return;
        MASQUES.submit(() -> {
            try {
                for (String id : new ArrayList<>(masques)) {
                    if (GroupeModele.estDecor(id)) continue;
                    List<Set<Integer>> ids = mobis(id);
                    Set<Integer> s = ids.get(0), m = ids.get(1);
                    if (MOBIS.equals(id)) { s.retainAll(masques0S); m.retainAll(masques0M); }
                    List<HFloorItem> sols = new ArrayList<>();
                    List<HWallItem> murs = new ArrayList<>();
                    for (int i : s) { HFloorItem it = Salle.sol(i); if (it != null) sols.add(it); }
                    for (int i : m) { HWallItem it = Salle.mur(i); if (it != null) murs.add(it); }
                    regler(id, sols, murs);
                }
            } catch (Throwable e) { System.err.println("[Atelier] calques : resynchronisation : " + e); }
            prevenir();
        });
    }
    // ================================================================ apercu

    /**
     * Affiche (ou met a jour) dans le jeu les fantomes du calque decale de
     * (dx, dy), chez toi seulement. Appels rapproches : seul le dernier compte.
     * @return false si impossible (Mur, Sol, hors salle, calque inconnu)
     */
    public static boolean previsualiser(String calqueId, int dx, int dy) { return previsualiser(calqueId, dx, dy, 0); }

    /** Idem, le bloc tourne de « quarts » quarts de tour horaires. */
    public static boolean previsualiser(String calqueId, int dx, int dy, int quarts) {
        installer();
        if (GroupeModele.estDecor(calqueId) || info(calqueId) == null) return false;
        GroupeApercu.vouloir(calqueId, dx, dy, quarts);
        return true;
    }

    /** Retire les fantomes. */
    public static void annulerApercu() { GroupeApercu.vouloir(null, 0, 0); }

    /** Fantomes affiches en ce moment dans le jeu. */
    public static int fantomes() { return GroupeFantomes.nombre(); }

    /** Calque dont l'apercu est demande (null = aucun). */
    public static String calqueApercu() { return GroupeApercu.calque(); }

    /** Vrai si le moteur de l'Atelier voit les fantomes comme des mobis (voir le rapport : filtre conseille dans Salle.sols). */
    public static boolean fantomesVusParMoteur() { return GroupeFantomes.vusParMoteur; }

    static void apercuPerdu() { GroupeApercu.perdu(); prevenir(); }

    /**
     * Fantomes d'une copie a placer (Dupliquer, Coller) : les mobis du calque
     * (ids null) ou ceux donnes, transformes par tr, des sortes cochees.
     * Rien ne part au serveur. Appels rapproches : seul le dernier compte.
     */
    public static boolean previsualiserCopie(String calqueId, List<Set<Integer>> ids, GroupeCalcul.Transfo tr,
                                             boolean sols, boolean murs, boolean wired) {
        installer();
        if (ids == null && (calqueId == null || GroupeModele.estDecor(calqueId) || info(calqueId) == null)) return false;
        GroupeApercu.vouloir(new GroupeApercu.Demande(calqueId, ids, tr, sols, murs, wired));
        return true;
    }

    /** Calque au-dessus duquel ranger une copie collee (null : en haut). */
    static String dessusCollage(String dessus) {
        return dessus == null || SELECTION.equals(dessus) || GroupeModele.estBase(dessus) ? null : dessus;
    }

    /**
     * Pose VRAIE de la copie placee avec les fantomes, a leur place exacte
     * (pose hybride : rafale directe + @altitude, puis la dalle magique pour
     * les seuls mobis refuses ; voir PoseHybride). Devient un nouveau calque rangé au-dessus
     * de « dessus » (null : en haut). Permis sur un calque verrouille (copie).
     * @param calqueId calque d'origine (null si ids donnes : copie collee)
     * @param ids      null : les mobis du calque
     */
    public static Tache poserCopie(String calqueId, List<Set<Integer>> ids, String dessus, GroupeCalcul.Transfo tr,
                                   boolean sols, boolean murs, boolean wired, Generateur.Source source, Progression p) {
        String origine = ids == null ? calqueId : null;
        return lancer("dupliquer", origine, false, p, t -> GroupeActions.poserCopie(t,
                ids != null ? ids : mobis(calqueId), dessus, tr, sols, murs, wired, source));
    }

    /** Ce que donnerait un deplacement / une duplication de (dx, dy). */
    public static Simulation simuler(String calqueId, int dx, int dy) {
        List<Set<Integer>> ids = mobis(calqueId);
        List<GroupeCalcul.Element> els = GroupeActions.elements(ids.get(0), ids.get(1));
        List<GroupeCalcul.Cible> c = GroupeCalcul.cibles(els, dx, dy, Salle::hauteurSol);
        int s = 0, m = 0, hors = 0, illis = 0;
        for (GroupeCalcul.Cible k : c) {
            if (k.e.mural) { m++; if (k.position == null) illis++; }
            else { s++; if (k.horsPlan) hors++; }
        }
        int[] autour = GroupeActions.occupations(els, ids.get(0), dx, dy);
        return new Simulation(s, m, hors, illis, autour[0], autour[1]);
    }

    // ================================================================ actions

    /**
     * Pose une COPIE des mobis du calque decalee de (dx, dy), par un appart
     * temporaire du moteur de pose (dalle magique automatique). Les mobis poses
     * deviennent un nouveau calque « <nom> copie ». Wired ignores (sans leur
     * reglage), comme la copie miroir. Source des meubles : inventaire.
     */
    public static Tache dupliquer(String calqueId, int dx, int dy, Progression p) {
        return dupliquer(calqueId, dx, dy, Generateur.Source.INVENTAIRE, p);
    }

    public static Tache dupliquer(String calqueId, int dx, int dy, Generateur.Source source, Progression p) {
        return lancer("dupliquer", calqueId, false, p, t -> GroupeActions.dupliquer(t, calqueId, dx, dy, source));
    }

    /**
     * Copie miroir des mobis de sol du calque (ou de la selection), retournee
     * gauche↔droite (surX) ou haut↔bas, posee a cote a « ecart » cases. Les
     * mobis poses deviennent un nouveau calque « <nom> miroir ».
     */
    public static Tache dupliquerMiroir(String calqueId, boolean surX, int ecart, Generateur.Source source, Progression p) {
        return lancer("miroir", calqueId, false, p, t -> GroupeActions.dupliquer(t, calqueId,
                els -> GroupeCalcul.miroir(els, surX, ecart, Salle::hauteurSol), source, "miroir"));
    }

    /**
     * Copie TOURNEE du calque (quarts : 1 horaire, 3 inverse, 2 demi-tour),
     * posee a cote de l'original mobi par mobi (PoseDirecte : rotation, etat,
     * et @altitude : chaque mobi garde sa hauteur au-dessus du sol). Devient un nouveau calque.
     */
    public static Tache dupliquerTourne(String calqueId, int quarts, Generateur.Source source, Progression p) {
        return lancer("dupliquer", calqueId, false, p, t -> GroupeActions.dupliquer(t, calqueId,
                els -> GroupeCalcul.copieTournee(els, quarts, Salle::hauteurSol), source, "pivot"));
    }

    /** Pivote les mobis du calque (ou de la selection), un quart de tour ; une action a la fois. */
    public static Tache pivoter(String calqueId, boolean horaire, boolean toutLeCalque, Progression p) {
        return lancer("pivoter", calqueId, true, p, t -> GroupeActions.pivoter(t, calqueId, horaire, toutLeCalque));
    }

    /** Change l'altitude des mobis du calque (decalage si relatif, sinon valeur commune), d'un coup. */
    public static Tache hauteur(String calqueId, boolean relatif, double valeur, Progression p) {
        return lancer("hauteur", calqueId, true, p, t -> GroupeActions.hauteur(t, calqueId, relatif, valeur));
    }

    /** Deplace tout le calque de (dx, dy) : rotations gardees, hauteurs remises par @altitude. */
    public static Tache deplacer(String calqueId, int dx, int dy, Progression p) {
        return lancer("deplacer", calqueId, true, p, t -> GroupeActions.deplacer(t, calqueId, dx, dy));
    }

    /**
     * Deplace ET pivote (quarts de tour horaires) le calque, d'un seul coup :
     * MoveObject en rafale + @altitude, puis la dalle magique pour les seuls
     * mobis refuses ou restes a une mauvaise hauteur (PoseHybride).
     */
    public static Tache deplacer(String calqueId, int dx, int dy, int quarts, Progression p) {
        return lancer("deplacer", calqueId, true, p, t -> GroupeActions.deplacerAvecDalles(t, calqueId, quarts, dx, dy));
    }

    /** Dupliquer avec les choix de la fenetre (quoi copier, source) ; voir GroupeActions.dupliquerOptions. */
    public static Tache dupliquerOptions(String calqueId, boolean sols, boolean murs, boolean wired,
                                         Generateur.Source source, Progression p) {
        return lancer("dupliquer", calqueId, false, p, t -> GroupeActions.dupliquerOptions(t, calqueId, sols, murs, wired, source));
    }

    /** Ramasse VRAIMENT les mobis du calque (confirmation a demander avant ; voir compter). */
    public static Tache ramasser(String calqueId, Progression p) {
        return lancer("ramasser", calqueId, true, p, t -> GroupeActions.ramasser(t, calqueId));
    }

    /**
     * Colle (Ctrl+V) une copie de ces mobis, posee sur place dans un nouveau
     * calque numerote, comme Dupliquer. Les ids viennent d'une copie (Ctrl+C) :
     * le calque d'origine peut avoir change ou disparu depuis.
     * @param dessus calque au-dessus duquel ranger la copie (null ou disparu : en haut)
     */
    public static Tache collerCopie(Collection<Integer> sols, Collection<Integer> murs, String dessus, Progression p) {
        List<Set<Integer>> ids = List.of(new LinkedHashSet<>(sols), new LinkedHashSet<>(murs));
        String d = dessus == null || SELECTION.equals(dessus) || GroupeModele.estBase(dessus) ? null : dessus;
        return lancer("coller", null, false, p, t -> GroupeActions.dupliquer(t, ids, d,
                els -> GroupeCalcul.cibles(els, 0, 0, Salle::hauteurSol), Generateur.Source.INVENTAIRE));
    }

    /** Une action lourde a la fois. */
    public static boolean occupe() { return occupe.get(); }

    /**
     * calqueId null : action sans calque (coller une copie, supprimer).
     * modifie : l'action change les mobis du calque (deplacer, pivoter...) :
     * un calque verrouille la refuse ; une copie (dupliquer) est permise.
     */
    private static Tache lancer(String nom, String calqueId, boolean modifie, Progression p,
                                java.util.function.Function<Tache, Resultat> f) {
        return lancer(nom, calqueId, modifie, p, false, f, null);
    }

    /**
     * @param verrouPris le verrou « occupe » est deja pris par l'appelant (rendu ici en cas de refus)
     * @param siRefus    lance si l'action est refusee avant de demarrer (peut etre null)
     */
    private static Tache lancer(String nom, String calqueId, boolean modifie, Progression p, boolean verrouPris,
                                java.util.function.Function<Tache, Resultat> f, Runnable siRefus) {
        installer();
        Tache t = new Tache(nom, p);
        String refus = null;
        if (GroupeModele.estDecor(calqueId)) refus = "Ce calque peut seulement être masqué ou affiché.";
        else if (salle() == -1) refus = "Tu n'es pas dans une salle.";
        else if (calqueId != null && info(calqueId) == null) refus = "Calque introuvable.";
        else if (modifie && calqueId != null) refus = refusVerrou(calqueId);
        if (refus == null && !verrouPris && !occupe.compareAndSet(false, true))
            refus = "Une autre action sur les calques est en cours.";
        if (refus != null) {
            if (verrouPris) occupe.set(false);
            if (siRefus != null) siRefus.run();
            t.finir(Resultat.refus(refus));
            return t;
        }
        annulerApercu();
        Salle.tache("calques-" + nom, () -> {
            Resultat r;
            // les fantomes partent d'abord (ils ne doivent pas croiser les vrais mobis)
            for (int i = 0; i < 30 && GroupeFantomes.nombre() > 0; i++) Salle.sommeil(100);
            try { r = f.apply(t); }
            catch (Throwable e) { e.printStackTrace(); r = Resultat.refus("Erreur : " + e); }
            finally { occupe.set(false); }
            t.finir(r);
        });
        return t;
    }

    /** Ou sont des mobis (calque, selection), note AVANT de les ramasser. */
    static final class Appartenance {
        final Map<Integer, String> sols = new HashMap<>(), murs = new HashMap<>();
        final Set<Integer> selS = new HashSet<>(), selM = new HashSet<>();
    }

    /**
     * Pour la pose hybride : note le calque (et la selection) de ces mobis
     * avant qu'ils soient ramasses pour etre reposes avec la dalle (la
     * surveillance oublie les ids disparus de la salle).
     */
    static Appartenance appartenance(Collection<Integer> sols, Collection<Integer> murs) {
        Appartenance a = new Appartenance();
        for (int id : sols) { String c = calqueDe(id, false); if (c != null && !MOBIS.equals(c)) a.sols.put(id, c); }
        for (int id : murs) { String c = calqueDe(id, true); if (c != null && !MOBIS.equals(c)) a.murs.put(id, c); }
        Selection sel = selection();
        for (int id : sols) if (sel.sols.contains(id)) a.selS.add(id);
        for (int id : murs) if (sel.murs.contains(id)) a.selM.add(id);
        return a;
    }

    /**
     * Les mobis reposes avec la dalle (ancien id -> nouvel id) reprennent la
     * place notee dans « avant » : meme calque, et la selection s'ils y
     * etaient. Les anciens ids sont oublies. Sans controle des verrous : c'est
     * l'action en cours qui les a remplaces.
     */
    static void remplacerIds(Appartenance avant, Map<Integer, Integer> sols, Map<Integer, Integer> murs) {
        if (sols.isEmpty() && murs.isEmpty()) return;
        Map<String, List<Integer>> versS = new LinkedHashMap<>(), versM = new LinkedHashMap<>();
        List<Integer> selS = new ArrayList<>(), selM = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : sols.entrySet()) {
            String c = avant.sols.get(e.getKey());
            if (c != null) versS.computeIfAbsent(c, k -> new ArrayList<>()).add(e.getValue());
            if (avant.selS.contains(e.getKey())) selS.add(e.getValue());
        }
        for (Map.Entry<Integer, Integer> e : murs.entrySet()) {
            String c = avant.murs.get(e.getKey());
            if (c != null) versM.computeIfAbsent(c, k -> new ArrayList<>()).add(e.getValue());
            if (avant.selM.contains(e.getKey())) selM.add(e.getValue());
        }
        oublierIds(sols.keySet(), murs.keySet());
        Set<String> calques = new LinkedHashSet<>(versS.keySet());
        calques.addAll(versM.keySet());
        for (String c : calques)
            ajouterSansVerrou(c, versS.getOrDefault(c, List.of()), versM.getOrDefault(c, List.of()));
        if (!selS.isEmpty() || !selM.isEmpty()) {
            retirerSelection(sols.keySet(), murs.keySet());
            ajouterSelection(selS, selM);
        }
    }

    /** Pour GroupeActions : oublie des ids ramasses. */
    static void oublierIds(Collection<Integer> sols, Collection<Integer> murs) {
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return;
            if (p.retirer(sols, murs) > 0) sauver(p);
        }
        prevenir();
    }

    // ================================================================ selection

    /** La selection courante (copie). */
    public static Selection selection() { return GroupeSelection.copie(); }

    /** Mode selection : chaque clic sur un mobi (sol ou mur) dans le jeu l'ajoute a la selection. */
    public static void modeSelection(boolean actif) { installer(); GroupeSelection.mode(actif); prevenir(); }

    public static boolean modeSelection() { return GroupeSelection.mode(); }

    /** Ajoute a la selection les mobis de la zone (sols dont la case d'origine y est, murs dont :w=x,y y est). */
    public static int selectionnerZone() { int n = GroupeSelection.zone(); prevenir(); return n; }

    public static void ajouterSelection(Collection<Integer> sols, Collection<Integer> murs) {
        GroupeSelection.ajouter(propre(sols, false), propre(murs, true)); prevenir();
    }

    public static void retirerSelection(Collection<Integer> sols, Collection<Integer> murs) {
        GroupeSelection.retirer(sols, murs); prevenir();
    }

    /** Selectionne tout un calque. */
    public static void selectionnerCalque(String calqueId) {
        List<Set<Integer>> ids = mobis(calqueId);
        ajouterSelection(ids.get(0), ids.get(1));
    }

    public static void viderSelection() { GroupeSelection.vider(); prevenir(); }

    /** Fait clignoter la selection dans le jeu (hors fil FX). */
    public static void montrerSelection() {
        Selection s = selection();
        if (s.vide() || !Calques.pret()) return;
        // Client modifie : la selection est deja mise en valeur (GroupeSelection).
        if (ClientModifie.saitSurligner()) return;
        Salle.tache("calques-montrer", () -> {
            List<HFloorItem> sols = new ArrayList<>();
            List<HWallItem> murs = new ArrayList<>();
            for (int i : s.sols) { HFloorItem it = Salle.sol(i); if (it != null) sols.add(it); }
            for (int i : s.murs) { HWallItem it = Salle.mur(i); if (it != null) murs.add(it); }
            Calques.clignoter(sols, murs, 3, 350);
        });
    }

    // ================================================================ surveillance

    private static volatile boolean installe = false;

    /** Branche tout une fois (appele de lui-meme a la premiere utilisation). */
    public static void installer() {
        if (installe) return;
        synchronized (Groupes.class) {
            if (installe) return;
            installe = true;
        }
        Calques.installer();
        Calques.surOubli(() -> {
            // le client a tout recu de nouveau : les murs aussi (le calque Mur est remasque a l'entree)
            boolean mur = masques.contains(MUR);
            masques.clear(); masques0S.clear(); masques0M.clear();
            if (mur) masques.add(MUR);
            prevenir();
        });
        CalqueMurs.installer();
        GroupeSelection.installer();
        GroupeApercu.demarrer();
        Salle.tache("calques-surveillance", Groupes::surveiller);
    }

    /**
     * Jeton du chargement de salle : il change a chaque liste de mobis recue
     * (chaque entree, meme dans le meme appart). 0 hors salle.
     */
    private static int jeton() {
        EtatSalle s = Salle.etat();
        return s == null ? 0 : s.generation();
    }

    private static void surveiller() {
        int derniere = -2, dernierJeton = 0, signature = -1, stable = 0;
        long entree = 0;
        boolean nettoye = false, aRemasquer = false;
        while (true) {
            Salle.sommeil(700);
            try {
                GroupeFantomes.brancher();
                SelectionMur.installer();
                OutilMiroir.Altitude.installer();
                int s = salleCourante();
                int j = s == -1 ? 0 : jeton();
                boolean recharge = s != -1 && s == derniere && j != 0 && dernierJeton != 0 && j != dernierJeton;
                if (s != derniere || recharge) {
                    if (s != -1 && !recharge) Journal.info("Entrée dans l'appart " + s + ".");
                    if (recharge) {
                        // meme appart recharge : le client a tout recu de nouveau, plus rien n'est masque
                        Journal.debug("calques : appart " + s + " recharge.");
                        Calques.oublier();
                    }
                    derniere = s;
                    dernierJeton = j;
                    entree = System.currentTimeMillis();
                    nettoye = false; signature = -1; stable = 0;
                    aRemasquer = s != -1;
                    masques.clear(); masques0S.clear(); masques0M.clear();
                    CalqueMurs.oublier();
                    GroupeFantomes.oublier();
                    GroupeApercu.perdu();
                    GroupeSelection.vider();
                    synchronized (V) { plan(); }       // charge les calques de CET appart
                    prevenir();
                    continue;
                }
                if (s == -1) continue;
                if (j != 0) dernierJeton = j;
                Set<Integer> sols = idsSols(), murs = idsMurs();
                int sig = sols.hashCode() * 31 + murs.hashCode();
                if (sig != signature) { signature = sig; stable = 0; nettoye = false; prevenir(); continue; }
                ++stable;
                if (stable >= 2 && !nettoye && System.currentTimeMillis() - entree > 3000
                        && (!sols.isEmpty() || !murs.isEmpty())) {
                    nettoye = true;
                    nettoyer(sols, murs);
                }
                if (aRemasquer && stable >= 1 && System.currentTimeMillis() - entree > 1500 && Calques.pret()) {
                    aRemasquer = false;
                    remasquer();
                    continue;
                }
                // un calque masque que quelqu'un d'autre a reaffiche (Calques.reafficherTout...)
                if (System.currentTimeMillis() - derniereRegle < 3000) continue;
                for (String id : new ArrayList<>(masques)) {
                    if (GroupeModele.estDecor(id) || Calques.nombre(raison(id)) > 0) continue;
                    Set<Integer> ms = MOBIS.equals(id) ? masques0S : mobis(id).get(0);
                    Set<Integer> mm = MOBIS.equals(id) ? masques0M : mobis(id).get(1);
                    boolean present = false;
                    for (int i : ms) if (sols.contains(i)) { present = true; break; }
                    if (!present) for (int i : mm) if (murs.contains(i)) { present = true; break; }
                    if (present && salleCourante() == s) { masques.remove(id); retenirCache(id, false); prevenir(); }
                }
            } catch (Throwable t) {
                System.err.println("[Atelier] calques : surveillance : " + t);
            }
        }
    }

    /** Oublie les ids disparus, sauf si c'est la moitie ou plus (salle mal chargee ?). */
    private static void nettoyer(Set<Integer> sols, Set<Integer> murs) {
        int n = 0, total = 0;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null) return;
            for (GroupeModele.Calque c : p.calques) total += c.nombre();
            GroupeModele.Plan essai = GroupeModele.Plan.lire(p.texte(), p.salle);
            int perdus = essai.nettoyer(sols, murs, Calques::estMasque);
            if (perdus == 0) return;
            if (perdus > 3 && perdus * 2 >= total) {
                Journal.debug("calques : " + perdus + "/" + total
                        + " id(s) absents de la salle : gardes (salle peut-etre pas finie de charger).");
                return;
            }
            n = p.nettoyer(sols, murs, Calques::estMasque);
            if (n > 0) sauver(p);
        }
        if (n > 0) { Journal.debug("calques : " + n + " id(s) disparu(s) oublie(s)."); prevenir(); }
    }

    /** A l'entree dans un appart : remasque les calques qui y etaient masques. */
    private static void remasquer() {
        List<String> ids;
        synchronized (V) {
            GroupeModele.Plan p = plan();
            if (p == null || p.caches.isEmpty()) return;
            ids = new ArrayList<>(p.caches);
        }
        MASQUES.submit(() -> {
            int faits = 0, rates = 0;
            for (String id : ids) {
                try {
                    if (SOL.equals(id)) continue;
                    if (MUR.equals(id)) {
                        if (CalqueMurs.cacher(true)) { masques.add(id); faits++; } else rates++;
                        continue;
                    }
                    List<Set<Integer>> m = mobis(id);
                    List<HFloorItem> sols = new ArrayList<>();
                    List<HWallItem> murs = new ArrayList<>();
                    for (int i : m.get(0)) { HFloorItem it = Salle.sol(i); if (it != null) sols.add(it); }
                    for (int i : m.get(1)) { HWallItem it = Salle.mur(i); if (it != null) murs.add(it); }
                    if (MOBIS.equals(id)) { masques0S.clear(); masques0S.addAll(m.get(0)); masques0M.clear(); masques0M.addAll(m.get(1)); }
                    masques.add(id);
                    regler(id, sols, murs);
                    faits++;
                } catch (Throwable t) {
                    rates++;
                    Journal.debug("calques : remasquer " + id + " : " + t);
                }
            }
            prevenir();
            if (rates > 0) signaler("Calques : " + rates + " calque(s) masqué(s) pas remasqué(s) en entrant. Clique sur leur œil.");
            else if (faits > 0) Journal.debug("calques : " + faits + " calque(s) remasque(s).");
        });
    }
}

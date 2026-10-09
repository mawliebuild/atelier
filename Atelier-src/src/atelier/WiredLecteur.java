package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lecture des configurations des wired de la salle.
 *
 * Pour chaque BOITE wired (Wired.estBoite : declencheur, selecteur, condition,
 * effet, add-on, variable), on envoie Open(id) au serveur, qui repond par la
 * fenetre de reglage (WiredFurniTrigger / Condition / Action / Addon /
 * Selector / Variable). On BLOQUE cette reponse pour que la fenetre ne s'ouvre
 * pas dans le jeu, et on la decode (ReglageWired, sinon lecture tolerante).
 * Les mobis wired qui ne sont pas des boites (dalle colorée, antenne,
 * compteur...) ne sont jamais demandes.
 *
 * Seules les reponses a NOS demandes sont bloquees : un wired que
 * l'utilisatrice ouvre elle-meme dans le jeu s'ouvre normalement. Une demande
 * reste « a bloquer » 30 s : une reponse tardive (ou en double, apres un nouvel
 * essai) n'ouvre donc jamais de fenetre toute seule.
 *
 * Demandes robustes :
 *  - la reponse est rattachee a SON wired par l'id qu'elle porte (pas par
 *    l'ordre d'arrivee) ;
 *  - plusieurs demandes en vol quand le serveur suit (1, puis 2, puis 3 apres
 *    des reponses sans faute), une seule et plus espacees des qu'une reponse
 *    manque (Rythme, logique pure testee) ;
 *  - attente adaptative : environ 4 fois le temps de reponse observe, entre
 *    2,5 et 8 s ;
 *  - un wired sans reponse est redemande apres les autres, 3 essais en tout ;
 *    alors seulement il est « illisible », avec sa raison, et il est encore
 *    redemande tout seul plus tard (30 s, 2 min, 5 min).
 *
 * Sans droits wired dans la salle, le serveur ne repond pas : on le detecte
 * (permissions du moteur de l'Atelier, puis absence de toute reponse sur les
 * premiers wired) ; on reessaie une minute plus tard.
 *
 * SEULEMENT QUAND L'OUTIL WIRED EST OUVERT (actif(true)) : en entrant dans un
 * appart, rien n'est envoye. Outil ouvert, le suivi (toutes les 200 ms) lit
 * tous les wired de la salle ; ensuite il ne relit que ce qui change : wired
 * pose (id inconnu), wired modifie (paquet Update* envoye par le client),
 * wired dont un mobi choisi a disparu. Les changements sont regroupes (350 ms
 * de calme, 1 s au plus).
 *
 * Quand l'utilisatrice ouvre elle-meme un wired (Open de 10 octets vu sortir),
 * les envois se mettent en pause 2,5 s et la reponse a SON Open n'est jamais
 * bloquee, meme si on avait demande le meme wired.
 */
public final class WiredLecteur {

    private WiredLecteur() { }

    /** Configuration lue d'un wired. Listes jamais nulles. */
    public static final class Config {
        public final int id;
        /** declencheur, condition, effet, add-on, selecteur, variable */
        public final String genre;
        public final int typeId;
        /** mobis selectionnes */
        public final List<Integer> items;
        /** seconde selection (certains wired) */
        public final List<Integer> items2;
        /** parametres entiers */
        public final List<Integer> options;
        public final List<Integer> sourcesMobis, sourcesAvatars;
        public final String texte;
        public final List<String> variables;
        /** delai d'un effet (en demi-secondes), -1 sinon */
        public final int delai;
        /** Le reglage tel que le serveur l'a decrit (pour le recopier : WiredCollage, EnregistrementCopie). */
        public final ReglageWired brut;

        Config(ReglageWired w, String genre) {
            this.brut = w;
            this.id = w.wiredId;
            this.genre = genre;
            this.typeId = w.typeId;
            items = copie(w.items);
            items2 = copie(w.items2);
            options = copie(w.options);
            sourcesMobis = copie(w.sourcesMobis);
            sourcesAvatars = copie(w.sourcesAvatars);
            texte = w.texte == null ? "" : w.texte;
            List<String> v = new ArrayList<>();
            if (w.variables != null)
                for (String x : w.variables)
                    if (x != null && !x.isBlank() && !x.equals("0")) v.add(x);
            variables = List.copyOf(v);
            delai = w.genre == ReglageWired.Genre.EFFET ? w.delai : -1;
        }

        private static <T> List<T> copie(List<T> l) {
            if (l == null) return List.of();
            List<T> r = new ArrayList<>();
            for (T x : l) if (x != null) r.add(x);
            return List.copyOf(r);
        }

        /** Tous les mobis designes (premiere et seconde selection). */
        public Set<Integer> tousLesMobis() {
            Set<Integer> s = new LinkedHashSet<>(items);
            s.addAll(items2);
            return s;
        }
    }

    // ------------------------------------------------------------------ etat

    private static final Map<Integer, Config> cache = new ConcurrentHashMap<>();

    /** Un wired reste sans reglage : pourquoi, et quand le redemander. */
    private record Echec(String raison, long prochainEssai, int tours) { }

    /** Wired illisibles (apres plusieurs essais) : id -> raison et prochain essai. */
    private static final Map<Integer, Echec> echecs = new ConcurrentHashMap<>();
    /** Relances automatiques des illisibles : apres 30 s, 2 min, puis 5 min. */
    private static final long[] RELANCES_MS = {30_000, 120_000, 300_000};

    /** Une demande envoyee : reponses encore a bloquer, instant du dernier envoi. */
    private record Demande(long envoi, String classe, int enAttente) { }

    /** Ids pour lesquels on a envoye Open : leur reponse est a bloquer (30 s au plus). */
    private static final Map<Integer, Demande> demandes = new ConcurrentHashMap<>();
    private static final long DEMANDE_VALIDE_MS = 30_000;
    /** Instant de la derniere reponse recue par wired (reglage lu ou pas). */
    private static final Map<Integer, Long> recuA = new ConcurrentHashMap<>();
    /** Reponses recues mais pas decodables : id -> instant. */
    private static final Map<Integer, Long> formatInconnu = new ConcurrentHashMap<>();
    /** Wired ouverts par l'utilisatrice elle-meme : id -> instant. Jamais bloques. */
    private static final Map<Integer, Long> elleOuvre = new ConcurrentHashMap<>();
    /** Wired a relire (modifies) : id -> pas avant cet instant. */
    private static final Map<Integer, Long> aRelire = new ConcurrentHashMap<>();
    /** Variables de la salle, id -> nom ; null tant qu'inconnues. */
    private static volatile Map<String, String> variablesSalle = null;
    private static final Map<String, String> variablesRecues = new ConcurrentHashMap<>();
    private static volatile long attenteVariablesJusqua = 0;
    private static volatile boolean variablesDemandees = false;

    private static volatile boolean branche = false, enBranchement = false, suiviLance = false;
    /** true des qu'une interception par nom a servi : le repli par contenu se tait. */
    private static volatile boolean parNomOk = false;

    private static volatile boolean enLecture = false, arret = false;
    private static volatile String message = "En attente de la salle…";
    private static volatile int lus = 0, total = 0;
    /** Pas d'envoi avant cet instant (l'utilisatrice vient d'ouvrir un wired). */
    private static volatile long pauseJusqua = 0;

    /** Salle suivie (-1 hors salle). */
    private static volatile int salleCourante = -1;
    /** Le serveur n'a repondu a rien dans cette salle : on attend avant de reessayer. */
    private static volatile boolean sansReponse = false;
    private static volatile long sansReponseDepuis = 0;
    private static volatile Boolean derniersDroits = null;
    /** Ids des boites wired de la salle au dernier regroupement. */
    private static volatile Set<Integer> wiredConnus = Set.of();
    private static final Map<Integer, Boolean> estWiredParType = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> estBoiteParType = new ConcurrentHashMap<>();

    private static final List<Runnable> ecouteursProgres = new CopyOnWriteArrayList<>();
    private static final List<Runnable> ecouteursFin = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicBoolean progresPoste =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean finPostee =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Pause de la lecture apres un Open de l'utilisatrice. */
    private static final long PAUSE_ELLE_MS = 2500;
    /** Delai avant de relire un wired modifie (le serveur applique d'abord). */
    private static final long DELAI_RELECTURE_MS = 700;
    /** Essais d'un wired dans une lecture avant de le dire illisible. */
    static final int ESSAIS = 3;
    /** Sans aucune reponse dans la salle : nouvel essai apres ce delai. */
    private static final long REESSAI_SANS_REPONSE_MS = 60_000;

    /** Rythme des demandes, partage par toutes les lectures (il apprend du serveur). */
    private static final Rythme RYTHME = new Rythme();

    // -------------------------------------------------------------- lectures

    public static Config config(int id) { return cache.get(id); }

    /** true si ce wired n'a pas pu etre lu apres plusieurs essais (voir raison). */
    public static boolean illisible(int id) { return echecs.containsKey(id) && !cache.containsKey(id); }

    /**
     * Pourquoi ce wired est illisible, en francais (« pas de réponse du serveur
     * après 3 essais »...) ; null s'il ne l'est pas.
     */
    public static String raison(int id) {
        if (cache.containsKey(id)) return null;
        Echec e = echecs.get(id);
        return e == null ? null : e.raison();
    }

    public static boolean enLecture() { return enLecture; }
    /** Etat court : « 42 wired lus », « Lecture… 12 / 42 », « Pas de droits wired ici. »... */
    public static String message() { return message; }
    public static int lus() { return lus; }
    public static int total() { return total; }

    /** Variables de la salle (id -> nom), ou null si la liste n'a pas ete recue. */
    public static Map<String, String> variables() {
        Map<String, String> v = variablesSalle;
        return v == null ? null : Collections.unmodifiableMap(v);
    }

    /** Appele sur le fil JavaFX quand l'etat (message) change. Regroupe. */
    public static void ecouterProgres(Runnable r) { ecouteursProgres.add(r); }

    /**
     * Appele sur le fil JavaFX quand les donnees changent : wired lus, salle
     * modifiee (pose, deplacement, retrait), changement de salle. Regroupe.
     */
    public static void ecouterFin(Runnable r) { ecouteursFin.add(r); }

    private static volatile boolean actif = false;

    /**
     * L'outil Wired est ouvert (true) ou ferme (false). Fermer arrete la
     * lecture en cours ; ce qui a deja ete lu reste en memoire pour la salle.
     */
    public static void actif(boolean a) {
        actif = a;
        if (!a && enLecture) arret = true;
        if (a) installer();
    }

    /** L'outil Wired est-il ouvert dans la fenetre de l'Atelier ? */
    public static boolean actif() { return actif; }

    /** Arrete la lecture en cours (elle reprendra seule au prochain changement). */
    public static void arreter() { arret = true; }

    // ------------------------------------------------------------ branchement

    /** Branche les interceptions et lance le suivi automatique, une seule fois. */
    public static synchronized void installer() {
        if (!suiviLance) {
            suiviLance = true;
            Thread s = new Thread(WiredLecteur::suivi, "atelier-wired-suivi");
            s.setDaemon(true);
            s.start();
        }
        if (branche || enBranchement) return;
        enBranchement = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900 && !branche; i++) {
                if (brancher()) {
                    Journal.debug("lecture des wired : ecoute active.");
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-wired-lecteur");
        t.setDaemon(true);
        t.start();
    }

    /** Interceptions deja posees : un nouvel essai de brancher() ne les double pas. */
    private static final Set<String> poses = ConcurrentHashMap.newKeySet();

    private static boolean brancher() {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        nomme(gp, "WiredFurniTrigger", "declencheur");
        nomme(gp, "WiredFurniCondition", "condition");
        nomme(gp, "WiredFurniAction", "effet");
        nomme(gp, "WiredFurniAddon", "add-on");
        nomme(gp, "WiredFurniSelector", "selecteur");
        nomme(gp, "WiredFurniVariable", "variable");
        if (!poses.contains("WiredAllVariablesDiffs")) try {
            gp.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariablesDiffs", m -> {
                // Liste de toutes les variables de la salle (gros paquet, frequent dans
                // les salles de jeu) : copiee ici, lue sur le fil VARIABLES.
                try {
                    boolean pourMoi = System.currentTimeMillis() <= attenteVariablesJusqua;
                    HPacket copie = new HPacket(m.getPacket());
                    VARIABLES.execute(() -> { try { recevoirVariables(copie, pourMoi); } catch (Throwable ignored) { } });
                } catch (Throwable ignored) { }
            });
            poses.add("WiredAllVariablesDiffs");
        } catch (Throwable t) {
            Journal.debug("WiredAllVariablesDiffs non intercepte : " + t);
        }
        // Enregistrement d'un wired par l'utilisatrice : premier entier = id.
        for (String n : new String[]{"UpdateTrigger", "UpdateCondition", "UpdateAction",
                "UpdateAddon", "UpdateSelector", "UpdateVariable"}) {
            if (!poses.contains(n)) try {
                gp.intercept(HMessage.Direction.TOSERVER, n, m -> {
                    try { modifie(m.getPacket().readInteger(6)); } catch (Throwable ignored) { }
                });
                poses.add(n);
            } catch (Throwable t) {
                Journal.debug(n + " non intercepte : " + t);
            }
        }
        // Open de l'utilisatrice, reconnu a sa forme (10 octets : un entier).
        if (!poses.contains("open")) try {
            gp.intercept(HMessage.Direction.TOSERVER, m -> {
                try {
                    if (m.getPacket().getBytesLength() != 10) return;
                    elleOuvre(m.getPacket().readInteger(6));
                } catch (Throwable ignored) { }
            });
            poses.add("open");
        } catch (Throwable t) { return false; }
        // Le repli par contenu en dernier : les interceptions par nom passent avant.
        if (!poses.contains("contenu")) try {
            gp.intercept(HMessage.Direction.TOCLIENT, m -> {
                try { parContenu(m); } catch (Throwable ignored) { }
            });
            poses.add("contenu");
        } catch (Throwable t) { return false; }
        branche = true;
        return true;
    }

    private static void nomme(Moteur gp, String nom, String genre) {
        if (poses.contains(nom)) return;
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, nom, m -> {
                try { recevoir(m, genre, true); } catch (Throwable ignored) { }
            });
            poses.add(nom);
        } catch (Throwable t) {
            Journal.debug(nom + " non intercepte : " + t);
        }
    }

    /** Une boite wired connue de la salle ? (sans recopier la salle) */
    private static boolean estWiredDeLaSalle(int id) {
        if (id <= 0) return false;
        if (wiredConnus.contains(id)) return true;
        HFloorItem it = Salle.sol(id);
        return it != null && estBoiteType(it.getTypeId());
    }

    /** L'utilisatrice ouvre un wired : sa reponse passera, et on se met en pause. */
    private static void elleOuvre(int id) {
        long now = System.currentTimeMillis();
        // Nos propres envois ne repassent normalement pas ici ; par prudence,
        // un Open d'un wired qu'on vient de demander a l'instant est le notre.
        Demande d = demandes.get(id);
        if (d != null && now - d.envoi() < 150) return;
        if (!estWiredDeLaSalle(id)) return;
        elleOuvre.put(id, now);
        demandes.remove(id);
        pauseJusqua = now + PAUSE_ELLE_MS;
    }

    /** Ouvert par l'utilisatrice il y a moins de 10 s ; nettoie les vieilles entrees. */
    private static boolean ouvertParElle(int id) {
        long now = System.currentTimeMillis();
        elleOuvre.values().removeIf(t -> now - t > 10000);
        return elleOuvre.containsKey(id);
    }

    /** L'utilisatrice a enregistre un wired : on le relira, lui seul. */
    private static void modifie(int id) {
        if (!estWiredDeLaSalle(id)) return;
        aRelire.put(id, System.currentTimeMillis() + DELAI_RELECTURE_MS);
    }

    // --------------------------------------------------------------- reponses

    /** Ce qu'une reponse a donne : l'id du wired, et son reglage (null : format inconnu). */
    record Decodage(int id, ReglageWired reglage) { }

    /**
     * Decode une reponse (sur une copie du paquet). D'abord la lecture
     * complete (ReglageWired.lireEntrant) ; si elle echoue, une lecture
     * tolerante : les champs communs suffisent (les champs propres au genre,
     * en fin de paquet, gardent leur valeur par defaut). Si meme l'id ne se
     * lit pas, null : ce n'est pas une reponse de wired.
     */
    static Decodage decoder(HPacket brut, String genre) {
        ReglageWired.Genre g = genre(genre);
        if (g == null) return null;
        try {
            HPacket p = new HPacket(brut);
            p.resetReadIndex();
            ReglageWired w = ReglageWired.lireEntrant(g, p);
            if (w != null && w.wiredId > 0) return new Decodage(w.wiredId, w);
        } catch (Throwable ignored) { }
        HPacket p = new HPacket(brut);
        p.resetReadIndex();
        ReglageWired r = new ReglageWired(g);
        try {
            p.readInteger();
            r.items = entiers(p);
            r.items2 = entiers(p);
            r.typeId = p.readInteger();
            r.wiredId = p.readInteger();
        } catch (Throwable t) { return null; }
        if (r.wiredId <= 0) return null;
        try {
            r.texte = p.readString();
            r.options = entiers(p);
            int n = p.readInteger();
            if (n < 0 || n > 4096) throw new IllegalStateException("variables");
            List<String> v = new ArrayList<>();
            for (int i = 0; i < n; i++) v.add(p.readString());
            r.variables = v;
            r.sourcesMobis = entiers(p);
            r.sourcesAvatars = entiers(p);
        } catch (Throwable t) {
            return new Decodage(r.wiredId, null);           // reponse a nous, mais illisible
        }
        if (g == ReglageWired.Genre.VARIABLE) r.variableId = "";
        return new Decodage(r.wiredId, r);
    }

    /** Liste d'entiers bornee par la taille du paquet (une taille absurde echoue tout de suite). */
    private static List<Integer> entiers(HPacket p) {
        int n = p.readInteger();
        if (n < 0 || (long) n * 4 > p.getBytesLength() - p.getReadIndex()) throw new IllegalStateException("liste");
        List<Integer> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(p.readInteger());
        return l;
    }

    /** « declencheur », « condition »... -> le genre d'un reglage, ou null. */
    static ReglageWired.Genre genre(String genre) {
        if (genre == null) return null;
        switch (genre) {
            case "declencheur": return ReglageWired.Genre.DECLENCHEUR;
            case "condition":   return ReglageWired.Genre.CONDITION;
            case "effet":       return ReglageWired.Genre.EFFET;
            case "add-on":      return ReglageWired.Genre.ADDON;
            case "selecteur":   return ReglageWired.Genre.SELECTEUR;
            case "variable":    return ReglageWired.Genre.VARIABLE;
            default: return null;
        }
    }

    /**
     * Reponse reconnue par son nom de paquet (fil des paquets : decodage d'une
     * copie bornee a 20 000 octets, aucune attente).
     */
    private static void recevoir(HMessage m, String genre, boolean parNom) {
        if (demandes.isEmpty() && elleOuvre.isEmpty()) return;   // rien a faire
        if (m.getPacket().getBytesLength() > 20000) return;
        Decodage d = decoder(m.getPacket(), genre);
        if (d == null) return;
        int id = d.id();
        long now = System.currentTimeMillis();
        Long t = elleOuvre.remove(id);
        boolean elle = t != null && now - t < 10000;
        if (elle) {
            // SA fenetre : on ne bloque pas, on garde seulement la configuration.
            demandes.remove(id);
        } else {
            if (!reponseANous(id, now)) return;    // pas a nous : la fenetre s'ouvre
            m.setBlocked(true);
        }
        if (parNom) parNomOk = true;
        if (d.reglage() == null) {
            formatInconnu.put(id, now);
        } else {
            cache.put(id, new Config(d.reglage(), genre));
            echecs.remove(id);
            formatInconnu.remove(id);
        }
        recuA.put(id, now);
        if (elle) donneesChangees();
    }

    /** Cette reponse repond-elle a une de nos demandes (encore valable) ? La decompte. */
    private static boolean reponseANous(int id, long now) {
        boolean[] anous = {false};
        demandes.computeIfPresent(id, (k, d) -> {
            if (now - d.envoi() > DEMANDE_VALIDE_MS) return null;      // trop vieille : oubliee
            anous[0] = true;
            return d.enAttente() <= 1 ? null : new Demande(d.envoi(), d.classe(), d.enAttente() - 1);
        });
        return anous[0];
    }

    /**
     * Repli : si les noms de paquets ne se resolvent pas, on reconnait la
     * reponse attendue a son contenu (elle se decode et porte l'id demande).
     */
    private static void parContenu(HMessage m) {
        if (parNomOk || demandes.isEmpty() || m.isBlocked()) return;
        int taille = m.getPacket().getBytesLength();
        if (taille < 30 || taille > 20000) return;
        long now = System.currentTimeMillis();
        byte[] b = null;
        for (Map.Entry<Integer, Demande> e : demandes.entrySet()) {
            Demande d = e.getValue();
            if (now - d.envoi() > 10000) continue;          // seulement les demandes recentes
            // Avant de decoder, un simple balayage des octets (la reponse porte l'id demande).
            if (b == null) b = m.getPacket().toBytes();
            int id = e.getKey();
            if (!contientEntier(b, id)) continue;
            String g = genreDe(d.classe());
            recevoir(m, g, false);
            if (g.equals("add-on") && demandes.containsKey(id) && !cache.containsKey(id)) recevoir(m, "variable", false);
            return;
        }
    }

    /** Les 4 octets de v (gros-boutiste) figurent-ils apres l'en-tete ? Logique pure. */
    static boolean contientEntier(byte[] b, int v) {
        if (b == null) return false;
        byte b0 = (byte) (v >>> 24), b1 = (byte) (v >>> 16), b2 = (byte) (v >>> 8), b3 = (byte) v;
        for (int i = 6; i + 3 < b.length; i++)
            if (b[i] == b0 && b[i + 1] == b1 && b[i + 2] == b2 && b[i + 3] == b3) return true;
        return false;
    }

    /** Un seul fil pour lire les listes de variables, dans l'ordre d'arrivee. */
    private static final java.util.concurrent.ExecutorService VARIABLES =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-wired-variables");
                t.setDaemon(true);
                return t;
            });

    /** Genre de paquet attendu d'apres le nom technique du wired (variantes test/proto comprises). */
    static String genreDe(String classe) {
        String c = Wired.normaliser(classe);
        if (c == null) c = "";
        if (c.startsWith("wf_trg_")) return "declencheur";
        if (c.startsWith("wf_cnd_")) return "condition";
        if (c.startsWith("wf_act_")) return "effet";
        if (c.startsWith("wf_slc_")) return "selecteur";
        if (c.startsWith("wf_var_")) return "variable";
        return "add-on";
    }

    /**
     * WiredAllVariablesDiffs : (int, boolean, int n, n x String supprimes,
     * int m, m x (int, HWiredVariable)). Lecture recopiee de l'exporteur.
     */
    private static void recevoirVariables(HPacket p, boolean pourMoi) {
        p.resetReadIndex();
        p.readInteger();
        boolean dernier = p.readBoolean();
        int n = p.readInteger();
        for (int i = 0; i < n && i < 100000; i++) p.readString();
        int k = p.readInteger();
        Map<String, String> lues = new HashMap<>();
        for (int i = 0; i < k && i < 100000; i++) {
            p.readInteger();
            PoseOutils.Variable v = PoseOutils.lireVariable(p);
            if (v.id() != null) lues.put(v.id(), v.nom() == null ? "" : v.nom());
        }
        OutilMiroir.Altitude.depuisListe(lues);
        if (!pourMoi) return;
        variablesRecues.putAll(lues);
        variablesSalle = new HashMap<>(variablesRecues);
        if (dernier) attenteVariablesJusqua = 0;
    }

    // ------------------------------------------------------------------ rythme

    /**
     * Rythme adaptatif des demandes. Logique pure (testee hors du jeu) :
     *  - fenetre : demandes en vol a la fois ; 1 au depart, +1 apres 5 reponses
     *    de suite sans silence (3 au plus), retour a 1 au premier silence ;
     *  - pause entre deux envois : 260 ms, x1,6 a chaque silence (1,5 s au
     *    plus), -10 % a chaque reponse ;
     *  - attente d'une reponse : 4 x le temps de reponse moyen + 1,2 s,
     *    bornee a 2,5..8 s.
     */
    static final class Rythme {
        static final long PAUSE_MIN = 260, PAUSE_MAX = 1500, ATTENTE_MIN = 2500, ATTENTE_MAX = 8000;
        private double latence = 400;
        private int fenetre = 1, deSuite = 0;
        private double pause = PAUSE_MIN;

        synchronized int fenetre() { return fenetre; }
        synchronized long pause() { return Math.round(pause); }
        synchronized long attente() {
            return Math.max(ATTENTE_MIN, Math.min(ATTENTE_MAX, Math.round(latence * 4 + 1200)));
        }

        /** Une reponse est arrivee apres ms millisecondes. */
        synchronized void reponse(long ms) {
            latence = 0.7 * latence + 0.3 * Math.max(0, ms);
            pause = Math.max(PAUSE_MIN, pause * 0.9);
            if (++deSuite >= 5 && fenetre < 3) { fenetre++; deSuite = 0; }
        }

        /** Une demande est restee sans reponse. */
        synchronized void silence() {
            fenetre = 1;
            deSuite = 0;
            pause = Math.min(PAUSE_MAX, pause * 1.6);
        }
    }

    // ------------------------------------------------------------------ suivi

    private static boolean estWiredType(int typeId) {
        Boolean b = estWiredParType.get(typeId);
        if (b != null) return b;
        String c = Salle.classe(typeId, false);
        if (c == null) return false;               // furnidata pas prete : pas de cache
        boolean w = Wired.estWired(c);
        estWiredParType.put(typeId, w);
        return w;
    }

    private static boolean estBoiteType(int typeId) {
        Boolean b = estBoiteParType.get(typeId);
        if (b != null) return b;
        String c = Salle.classe(typeId, false);
        if (c == null) return false;               // furnidata pas prete : pas de cache
        boolean w = Wired.estBoite(c);
        estBoiteParType.put(typeId, w);
        return w;
    }

    /** Mobis de la famille wired presents dans la salle (boites ET mobis wired : dalles, antennes...). */
    public static List<HFloorItem> wiredDeLaSalle() {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            try { if (estWiredType(it.getTypeId())) r.add(it); }
            catch (Throwable ignored) { }
        }
        return r;
    }

    /** Boites wired presentes dans la salle (celles qui ont un reglage). */
    public static List<HFloorItem> boitesDeLaSalle() {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            try { if (estBoiteType(it.getTypeId())) r.add(it); }
            catch (Throwable ignored) { }
        }
        return r;
    }

    /** Melange 64 bits (splitmix) : empreintes independantes de l'ordre. */
    static long melange(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Empreinte d'un wired : id, case, altitude (au centieme), rotation. */
    static long empreinteWired(int id, int x, int y, double z, int rot) {
        long h = melange(id);
        h = melange(h ^ (((long) x << 20) ^ y));
        h = melange(h ^ Math.round(z * 100) ^ ((long) rot << 40));
        return h;
    }

    /**
     * Regroupe les changements : agir quand rien n'a bouge depuis « calme » ms,
     * ou au plus tard « max » ms apres le premier changement (une pose de
     * centaines de mobis ne fige donc pas l'affichage, sans le saccader).
     * Logique pure, testee.
     */
    public static final class Regroupeur {
        private final long calme, max;
        private Object vu;
        private boolean sale, initialise;
        private long premier, dernier;

        public Regroupeur(long calme, long max) { this.calme = calme; this.max = max; }

        /** A appeler a chaque tour ; true = il faut agir maintenant. */
        public synchronized boolean tic(Object signature, long maintenant) {
            if (!initialise || !Objects.equals(signature, vu)) {
                initialise = true;
                vu = signature;
                if (!sale) { sale = true; premier = maintenant; }
                dernier = maintenant;
            }
            if (sale && (maintenant - dernier >= calme || maintenant - premier >= max)) {
                sale = false;
                return true;
            }
            return false;
        }

        /** Agir au prochain tour, sans attendre. */
        public synchronized void forcer() {
            sale = true;
            premier = dernier = Long.MIN_VALUE / 4;
        }

        /** Un changement attend d'etre applique. */
        public synchronized boolean enAttente() { return sale; }
    }

    private static void suivi() {
        Regroupeur reg = new Regroupeur(350, 1000);
        while (true) {
            try { tour(reg); } catch (Throwable t) {
                Journal.debug("Suivi des wired en erreur : " + t);   // diagnostic : pas de spam toutes les 200 ms
            }
            Salle.sommeil(200);
        }
    }

    /** Oublie les reglages deja lus (« Vider le cache wired ») : ils seront relus. */
    static void viderCache() {
        cache.clear();
        echecs.clear();
        formatInconnu.clear();
        aRelire.clear();
        lus = 0;
        sansReponse = false;
        donneesChangees();
    }

    /** Oublie tout ce qui concerne la salle precedente. */
    private static void oublierSalle() {
        cache.clear();
        echecs.clear();
        formatInconnu.clear();
        recuA.clear();
        aRelire.clear();
        elleOuvre.clear();
        demandes.clear();
        wiredConnus = Set.of();
        variablesSalle = null;
        variablesRecues.clear();
        variablesDemandees = false;
        sansReponse = false;
        derniersDroits = null;
        lus = 0; total = 0;
    }

    private static void tour(Regroupeur reg) {
        Moteur gp = Salle.gp();
        if (gp == null) { etat("En attente du moteur de l'Atelier…"); return; }
        EtatSalle s = Salle.etat();
        if (s == null) {
            if (salleCourante != -1) { salleCourante = -1; oublierSalle(); donneesChangees(); }
            etat("Pas dans une salle.");
            return;
        }
        int salle = s.getRoomId();
        if (salle != salleCourante) {
            salleCourante = salle;
            oublierSalle();
            reg.forcer();
        }
        long now = System.currentTimeMillis();
        demandes.values().removeIf(d -> now - d.envoi() > DEMANDE_VALIDE_MS);
        if (!actif) { etat("Lecture des wired en pause (outil Wired fermé)."); return; }
        if (!Salle.furnidataPrete()) { etat("Furnidata en cours de chargement…"); return; }
        if (!branche) { etat("Écoute des paquets en préparation…"); return; }

        // Photographie : boites wired (avec position) + ensemble des ids de la salle.
        List<HFloorItem> sols = Salle.sols();
        List<HFloorItem> wired = new ArrayList<>();
        long sigW = 0, sigTous = 0;
        for (HFloorItem it : sols) {
            int id = it.getId();
            sigTous += melange(id);
            boolean w;
            try { w = estBoiteType(it.getTypeId()); } catch (Throwable t) { w = false; }
            if (!w) continue;
            wired.add(it);
            try {
                sigW += empreinteWired(id, it.getTile().getX(), it.getTile().getY(),
                        it.getTile().getZ(), Salle.rotation(it));
            } catch (Throwable t) { sigW += melange(id); }
        }
        String signature = sols.size() + "/" + sigTous + "/" + wired.size() + "/" + sigW;
        if (reg.tic(signature, now)) appliquer(sols, wired, now);
        total = wired.size();

        Boolean droits = droits(gp);
        if (!Objects.equals(droits, derniersDroits)) {
            derniersDroits = droits;
            sansReponse = false;
            echecs.clear();          // droits changes : on retente tout
        }
        if (Boolean.FALSE.equals(droits)) { etat("Pas de droits wired ici : les réglages ne peuvent pas être lus."); return; }
        if (sansReponse) {
            if (now - sansReponseDepuis < REESSAI_SANS_REPONSE_MS) {
                etat("Pas de réponse du serveur : pas de droits wired ici ? Nouvel essai dans une minute.");
                return;
            }
            sansReponse = false;     // une minute plus tard : on retente
        }
        if (reg.enAttente()) return;           // la salle bouge encore : on attend qu'elle se pose
        if (wired.isEmpty()) { etat("Aucun wired ici."); return; }
        String occ = occupe();
        if (occ != null) { etat(occ); return; }

        List<HFloorItem> aLire = choisirALire(wired, now);
        if (aLire.isEmpty()) { etat(bilan()); return; }
        synchronized (LECTURE) { lecture(gp, salle, aLire); }
        donneesChangees();
        if (!sansReponse) etat(bilan());
    }

    /**
     * Le moteur de l'Atelier pose une copie (Update* et poses en rafale) : on ne
     * lit pas en meme temps, ni pour le flood, ni pour ne pas lire un wired pas
     * encore regle. (La copie d'un appart lit elle-meme ses wired par
     * lireMaintenant : rien a attendre.)
     */
    private static String occupe() {
        return PoseCopie.occupee() ? "En attente : l'Atelier pose un appart." : null;
    }

    private static Boolean droits(Moteur gp) {
        try {
            Droits rp = gp.getPermissions();
            return rp == null ? null : rp.canModifyWired();
        } catch (Throwable t) { return null; }
    }

    /** Applique un changement regroupe de la salle (fil de suivi). */
    private static void appliquer(List<HFloorItem> sols, List<HFloorItem> wired, long now) {
        Set<Integer> ids = new HashSet<>();
        for (HFloorItem it : wired) ids.add(it.getId());
        cache.keySet().retainAll(ids);
        echecs.keySet().retainAll(ids);
        formatInconnu.keySet().retainAll(ids);
        recuA.keySet().retainAll(ids);
        aRelire.keySet().retainAll(ids);
        Set<Integer> tous = new HashSet<>(sols.size() * 2);
        for (HFloorItem it : sols) tous.add(it.getId());
        // Un mobi choisi par un wired a disparu : le serveur a change sa selection.
        for (Integer id : aRelireApresRetrait(cache, tous))
            aRelire.putIfAbsent(id, now);
        wiredConnus = Set.copyOf(ids);
        lus = compterLus(ids);
        donneesChangees();
    }

    /** Wired dont un mobi choisi n'est plus dans la salle. Logique pure. */
    static List<Integer> aRelireApresRetrait(Map<Integer, Config> configs, Set<Integer> presents) {
        List<Integer> r = new ArrayList<>();
        for (Map.Entry<Integer, Config> e : configs.entrySet())
            for (Integer m : e.getValue().tousLesMobis())
                if (m != null && m > 0 && !presents.contains(m)) { r.add(e.getKey()); break; }
        return r;
    }

    /**
     * Ce qu'il reste a lire : wired jamais lus, wired modifies murs, et
     * illisibles dont la relance automatique est venue.
     */
    private static List<HFloorItem> choisirALire(List<HFloorItem> wired, long now) {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : wired) {
            int id = it.getId();
            Long t = aRelire.get(id);
            Echec e = echecs.get(id);
            boolean lire;
            if (t != null) lire = t <= now;
            else if (cache.containsKey(id)) lire = false;
            else lire = e == null || (e.tours() <= RELANCES_MS.length && e.prochainEssai() <= now);
            if (lire) r.add(it);
        }
        // Du bas de la salle vers le haut, pour une progression lisible.
        r.sort(Comparator.comparingInt((HFloorItem it) -> it.getTile().getY())
                .thenComparingInt(it -> it.getTile().getX())
                .thenComparingDouble(it -> it.getTile().getZ()));
        return r;
    }

    private static String bilan() {
        Set<Integer> ids = wiredConnus;
        int nIll = 0;
        for (Integer i : ids) if (illisible(i)) nIll++;
        Map<String, String> v = variablesSalle;
        return lus + " wired lus"
                + (lus < total ? " / " + total : "")
                + (nIll > 0 ? " · " + nIll + " illisible(s), nouvel essai automatique plus tard" : "")
                + (v != null && !v.isEmpty() ? " · " + v.size() + " variable(s)" : "");
    }

    /** Note l'echec d'un wired (apres ESSAIS essais) et programme sa relance. */
    private static void noterEchec(int id, String raison, long now) {
        Echec avant = echecs.get(id);
        int tours = avant == null ? 1 : avant.tours() + 1;
        long relance = tours <= RELANCES_MS.length ? RELANCES_MS[tours - 1] : Long.MAX_VALUE / 4;
        echecs.put(id, new Echec(raison, now + relance, tours));
        Journal.debug("wired " + id + " illisible (" + raison + "), tour " + tours);
    }

    // ---------------------------------------------------------------- lecture

    /** Lit les wired donnes, dans le fil de suivi. S'arrete au changement de salle. */
    private static void lecture(Moteur gp, int salle, List<HFloorItem> aLire) {
        enLecture = true;
        arret = false;
        try {
            boolean variables = !variablesDemandees;
            for (HFloorItem it : aLire) {
                String c = Wired.normaliser(Salle.classe(it.getTypeId(), false));
                if (c != null && c.startsWith("wf_var_")) variables = true;
            }
            if (variables) {
                variablesDemandees = true;
                variablesRecues.clear();
                attenteVariablesJusqua = System.currentTimeMillis() + 8000;
                try {
                    if (attendrePause(salle, true)) {
                        Salle.espacer();
                        gp.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                    }
                } catch (Throwable ignored) { }
            }
            List<Integer> ids = new ArrayList<>();
            for (HFloorItem it : aLire) ids.add(it.getId());
            Set<Integer> connus = wiredConnus;
            boolean dejaLu = !cache.isEmpty();
            lireLot(gp, salle, ids, () -> arret, true, dejaLu,
                    (f, t) -> etat("Lecture… " + compterLus(connus) + " / " + total));
            lus = compterLus(wiredConnus);
        } finally {
            enLecture = false;
            if (!memeSalle(salle)) { salleCourante = -2; }  // le prochain tour repart propre
        }
    }

    /**
     * Lit un lot de wired, plusieurs demandes en vol au besoin (Rythme).
     * Chaque reponse est rattachee a son wired par l'id qu'elle porte. Un wired
     * sans reponse est redemande apres les autres ; apres ESSAIS essais il est
     * note illisible (avec la raison) et sera relance plus tard.
     *
     * @param auto   lecture automatique : arret au bouton/fermeture, et
     *               detection « aucune reponse dans cette salle »
     * @param dejaLu des reglages ont deja ete lus ici (le serveur repond)
     */
    private static void lireLot(Moteur gp, int salle, List<Integer> ids,
                                java.util.function.BooleanSupplier stop, boolean auto, boolean dejaLu,
                                java.util.function.BiConsumer<Integer, Integer> progres) {
        Deque<Integer> file = new ArrayDeque<>(ids);
        Map<Integer, Long> enVol = new LinkedHashMap<>();
        Map<Integer, Integer> essais = new HashMap<>();
        Map<Integer, Config> anciennes = new HashMap<>();
        int fait = 0, recues = 0, silences = 0, nb = ids.size();
        long prochain = 0, derniereMaj = System.currentTimeMillis();
        int faitAnnonce = -1;
        try {
            while (!file.isEmpty() || !enVol.isEmpty()) {
                if ((stop != null && stop.getAsBoolean()) || !memeSalle(salle)) break;
                long now = System.currentTimeMillis();

                // 1. reponses arrivees, et silences
                long attente = RYTHME.attente();
                for (Iterator<Map.Entry<Integer, Long>> i = enVol.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry<Integer, Long> e = i.next();
                    int id = e.getKey();
                    Long r = recuA.get(id);
                    if (r != null && r >= e.getValue()) {
                        i.remove();
                        fait++;
                        recues++;
                        RYTHME.reponse(r - e.getValue());
                        if (!cache.containsKey(id)) {
                            Config a = anciennes.get(id);
                            if (a != null) cache.putIfAbsent(id, a);       // garder l'ancienne
                            else noterEchec(id, "le jeu a répondu, mais sous une forme que l'Atelier ne sait pas lire", now);
                        }
                        aRelire.remove(id);
                        continue;
                    }
                    if (now - e.getValue() > attente) {
                        i.remove();
                        silences++;
                        RYTHME.silence();
                        int n = essais.merge(id, 1, Integer::sum);
                        Journal.debug("wired " + id + " : pas de réponse en " + attente + " ms (essai " + n + ")");
                        if (n < ESSAIS) file.addLast(id);          // redemande apres les autres
                        else {
                            fait++;
                            Config a = anciennes.get(id);
                            if (a != null) cache.putIfAbsent(id, a);
                            else noterEchec(id, "pas de réponse du serveur après " + n + " essais"
                                    + (Boolean.TRUE.equals(derniersDroits) ? " (tu as pourtant les droits wired)"
                                       : " : as-tu les droits wired ici ?"), now);
                        }
                    }
                }

                // Rien jamais lu ici et le serveur se tait : pas la peine d'insister.
                if (auto && !dejaLu && recues == 0 && silences >= 4) {
                    sansReponse = true;
                    sansReponseDepuis = now;
                    etat("Pas de réponse du serveur : pas de droits wired ici ? Nouvel essai dans une minute.");
                    for (Map.Entry<Integer, Config> e : anciennes.entrySet()) cache.putIfAbsent(e.getKey(), e.getValue());
                    return;
                }

                // L'Atelier pose un appart : on s'efface (la lecture reprendra seule apres).
                if (auto && enVol.isEmpty() && occupe() != null) return;

                // 2. une nouvelle demande, si la fenetre le permet
                if (!file.isEmpty() && enVol.size() < RYTHME.fenetre() && now >= prochain
                        && now >= pauseJusqua && (!auto || occupe() == null)) {
                    int id = file.pollFirst();
                    HFloorItem it = Salle.sol(id);
                    if (it == null || !estBoiteType(it.getTypeId())) { fait++; continue; }   // retire, ou pas une boite
                    if (!anciennes.containsKey(id)) {
                        Config avant = cache.remove(id);           // une relecture remplace l'ancienne
                        if (avant != null) anciennes.put(id, avant);
                    }
                    if (ouvertParElle(id)) {
                        enVol.put(id, now);                         // sa reponse a elle remplira le cache
                    } else {
                        Salle.espacer();
                        long envoi = System.currentTimeMillis();
                        String cls = Salle.classe(it.getTypeId(), false);
                        demandes.merge(id, new Demande(envoi, cls, 1),
                                (a, b) -> new Demande(envoi, cls, a.enAttente() + 1));
                        try {
                            gp.sendToServer(new HPacket("Open", HMessage.Direction.TOSERVER, id));
                        } catch (Throwable t) {
                            demandes.remove(id);
                            Journal.debug("Open " + id + " impossible : " + t);
                            fait++;
                            Config a = anciennes.get(id);
                            if (a != null) cache.putIfAbsent(id, a);
                            continue;
                        }
                        enVol.put(id, envoi);
                    }
                    prochain = System.currentTimeMillis() + RYTHME.pause();
                } else if (now < pauseJusqua && auto) {
                    etat("Lecture en pause : tu as ouvert un wired.");
                }

                if (fait != faitAnnonce && progres != null) {
                    faitAnnonce = fait;
                    try { progres.accept(fait, nb); } catch (Throwable ignored) { }
                }
                if (auto && System.currentTimeMillis() - derniereMaj > 2000) {
                    derniereMaj = System.currentTimeMillis();
                    donneesChangees();
                }
                Salle.sommeil(25);
            }
        } finally {
            // interrompu : on garde ce qui etait lu avant (les demandes restent a bloquer 30 s)
            for (Map.Entry<Integer, Config> e : anciennes.entrySet()) cache.putIfAbsent(e.getKey(), e.getValue());
            if (progres != null && fait != faitAnnonce) try { progres.accept(fait, nb); } catch (Throwable ignored) { }
        }
    }

    private static boolean memeSalle(int salle) {
        EtatSalle s = Salle.etat();
        try { return s != null && s.getRoomId() == salle; } catch (Throwable t) { return false; }
    }

    /** Attend la fin d'une pause (l'utilisatrice a ouvert un wired). false si salle quittee. */
    private static boolean attendrePause(int salle, boolean auto) {
        boolean annonce = false;
        while (System.currentTimeMillis() < pauseJusqua) {
            if ((auto && arret) || !memeSalle(salle)) return false;
            if (!annonce && auto) { etat("Lecture en pause : tu as ouvert un wired."); annonce = true; }
            Salle.sommeil(100);
        }
        return true;
    }

    private static int compterLus(Set<Integer> ids) {
        int n = 0;
        for (Integer i : ids) if (cache.containsKey(i)) n++;
        return n;
    }

    // ------------------------------------------------------- lecture a la demande

    /** Une seule lecture a la fois (suivi automatique ou lecture a la demande). */
    private static final Object LECTURE = new Object();

    /**
     * Lit TOUT DE SUITE les wired donnes, meme si l'outil Wired est ferme
     * (copie de configuration : WiredCollage). Un wired deja lu et pas modifie
     * depuis n'est pas redemande ; un mobi wired qui n'est pas une boite
     * (dalle, antenne...) n'est jamais demande. Bloquant : jamais sur le fil JavaFX.
     *
     * @param stop    true = s'arreter (bouton Arreter)
     * @param progres (faits, total), appele hors du fil JavaFX
     * @return les configurations obtenues (les wired absents n'ont pas repondu)
     */
    public static Map<Integer, Config> lireMaintenant(Collection<Integer> ids,
                                                      java.util.function.BooleanSupplier stop,
                                                      java.util.function.BiConsumer<Integer, Integer> progres) {
        return lireMaintenant(ids, stop, progres, false);
    }

    /**
     * Comme lireMaintenant ; avec forcer, le cache est ignore : chaque wired est
     * redemande, et seul un reglage recu PENDANT cette lecture est rendu (les
     * ids des wired BC sont reutilises : un ancien reglage en cache peut
     * appartenir a un autre wired). Sert a la verification d'un collage.
     */
    public static Map<Integer, Config> lireMaintenant(Collection<Integer> ids,
                                                      java.util.function.BooleanSupplier stop,
                                                      java.util.function.BiConsumer<Integer, Integer> progres,
                                                      boolean forcer) {
        Map<Integer, Config> r = new LinkedHashMap<>();
        long debut = System.currentTimeMillis();
        if (ids == null || ids.isEmpty()) return r;
        installer();
        for (int i = 0; i < 100 && !branche; i++) Salle.sommeil(100);
        Moteur gp = Salle.gp();
        EtatSalle s = Salle.etat();
        if (gp == null || s == null || !branche) return r;
        int salle = s.getRoomId();
        if (enLecture) arret = true;          // la lecture automatique cede la place, elle reprendra
        synchronized (LECTURE) {
            arret = false;
            List<Integer> aLire = new ArrayList<>();
            long attendreJusqua = 0;
            for (Integer id : ids) {
                if (id == null) continue;
                HFloorItem it = Salle.sol(id);
                if (it == null || !estBoiteType(it.getTypeId())) continue;
                Long t = aRelire.get(id);
                if (!forcer && cache.containsKey(id) && t == null) continue;
                if (t != null) attendreJusqua = Math.max(attendreJusqua, t);
                aLire.add(id);
            }
            long reste = Math.min(2000, attendreJusqua - System.currentTimeMillis());
            if (reste > 0) Salle.sommeil(reste);   // un wired tout juste enregistre : le serveur applique d'abord
            int n = ids.size(), dejaFaits = n - aLire.size();
            if (!aLire.isEmpty()) {
                lireLot(gp, salle, aLire, stop, false, true, (f, t) -> {
                    if (progres != null) progres.accept(dejaFaits + f, n);
                });
            } else if (progres != null) {
                try { progres.accept(n, n); } catch (Throwable ignored) { }
            }
            for (Integer id : ids) {
                if (id == null) continue;
                Config c = cache.get(id);
                if (c == null) continue;
                if (forcer) {
                    Long recu = recuA.get(id), inconnu = formatInconnu.get(id);
                    if (recu == null || recu < debut || (inconnu != null && inconnu >= debut)) continue;   // pas relu
                }
                r.put(id, c);
            }
        }
        donneesChangees();
        return r;
    }

    // ------------------------------------------------------------ notifications

    /** Change le message d'etat ; ne poste rien si rien n'a change. */
    private static void etat(String s) {
        if (s.equals(message)) return;
        message = s;
        if (!progresPoste.compareAndSet(false, true)) return;
        try {
            Platform.runLater(() -> {
                progresPoste.set(false);
                for (Runnable r : ecouteursProgres) try { r.run(); } catch (Throwable ignored) { }
            });
        } catch (Throwable t) { progresPoste.set(false); }
    }

    /** Previent les volets que les donnees ont change (regroupe). */
    private static void donneesChangees() {
        if (!finPostee.compareAndSet(false, true)) return;
        try {
            Platform.runLater(() -> {
                finPostee.set(false);
                for (Runnable r : ecouteursFin) try { r.run(); } catch (Throwable ignored) { }
            });
        } catch (Throwable t) { finPostee.set(false); }
    }
}

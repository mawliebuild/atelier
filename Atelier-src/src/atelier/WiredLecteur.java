package atelier;

import extension.GPresets;
import extension.parsers.HWiredVariable;
import extension.tools.presetconfig.wired.PresetWiredBase;
import extension.tools.presetconfig.wired.PresetWiredEffect;
import extension.tools.presetconfig.wired.incoming.RetrievedWired;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredAddon;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredCondition;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredEffect;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredSelector;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredTrigger;
import extension.tools.presetconfig.wired.incoming.RetrievedWiredVariable;
import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lecture des configurations des wired de la salle.
 *
 * Meme methode que l'export de l'Atelier (GPresetExporter) : pour chaque
 * wired, on envoie Open(id) au serveur, qui repond par la fenetre de reglage
 * (WiredFurniTrigger / Condition / Action / Addon / Selector / Variable). On
 * BLOQUE cette reponse pour que la fenetre ne s'ouvre pas dans le jeu, et on la
 * decode avec les classes RetrievedWired* du moteur de l'Atelier.
 *
 * Seules les reponses a NOS demandes sont bloquees : un wired que
 * l'utilisatrice ouvre elle-meme dans le jeu s'ouvre normalement.
 *
 * Sans droits wired dans la salle, le serveur ne repond pas : on le detecte
 * (permissions du moteur de l'Atelier, puis absence de reponse sur les premiers wired).
 *
 * SEULEMENT QUAND L'OUTIL WIRED EST OUVERT (actif(true)) : en entrant dans un
 * appart, rien n'est envoye — sinon le jeu ouvrait parfois la fenetre de
 * reglage d'un wired toute seule. Outil ouvert, le suivi (toutes les 200 ms)
 * lit tous les wired de la salle ; ensuite il
 * ne relit que ce qui change : wired pose (id inconnu), wired modifie (paquet
 * Update* envoye par le client), wired dont un mobi choisi a disparu. Un wired
 * deplace ou retire ne demande aucune relecture : l'analyse est seulement
 * refaite. Les changements sont regroupes (350 ms de calme, 1 s au plus).
 *
 * Quand l'utilisatrice ouvre elle-meme un wired (Open de 10 octets vu sortir),
 * la lecture se met en pause 2,5 s et la reponse a SON Open n'est jamais
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
        /** Le reglage tel que le moteur de l'Atelier l'a decode (pour le recopier : WiredCollage). */
        public final PresetWiredBase brut;

        Config(PresetWiredBase w, String genre) {
            this.brut = w;
            this.id = w.getWiredId();
            this.genre = genre;
            int t = -1;
            try { if (w instanceof RetrievedWired) t = ((RetrievedWired) w).getTypeId(); }
            catch (Throwable ignored) { }
            this.typeId = t;
            items = copie(w.getItems());
            items2 = copie(w.getSecondItems());
            options = copie(w.getOptions());
            sourcesMobis = copie(w.getPickedFurniSources());
            sourcesAvatars = copie(w.getPickedUserSources());
            String s = w.getStringConfig();
            texte = s == null ? "" : s;
            List<String> v = new ArrayList<>();
            try {
                if (w.getVariableIds() != null)
                    for (String x : w.getVariableIds())
                        if (x != null && !x.isBlank() && !x.equals("0")) v.add(x);
            } catch (Throwable ignored) { }
            variables = List.copyOf(v);
            int d = -1;
            try { if (w instanceof PresetWiredEffect) d = ((PresetWiredEffect) w).getDelay(); }
            catch (Throwable ignored) { }
            delai = d;
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
    private static final Set<Integer> illisibles = ConcurrentHashMap.newKeySet();
    /** Ids pour lesquels on a envoye Open et dont la reponse est a bloquer. */
    private static final Set<Integer> demandes = ConcurrentHashMap.newKeySet();
    /** Wired ouverts par l'utilisatrice elle-meme : id -> instant. Jamais bloques. */
    private static final Map<Integer, Long> elleOuvre = new ConcurrentHashMap<>();
    /** Wired a relire (modifies) : id -> pas avant cet instant. */
    private static final Map<Integer, Long> aRelire = new ConcurrentHashMap<>();
    /** Variables de la salle, id -> nom ; null tant qu'inconnues. */
    private static volatile Map<String, String> variablesSalle = null;
    private static final Map<String, String> variablesRecues = new ConcurrentHashMap<>();
    private static volatile long attenteVariablesJusqua = 0;
    private static volatile boolean variablesDemandees = false;

    private static volatile int attendu = 0;
    private static volatile String classeAttendue = null;
    private static volatile CountDownLatch reponse = new CountDownLatch(0);

    private static volatile boolean branche = false, enBranchement = false, suiviLance = false;
    /** true des qu'une interception par nom a servi : le repli par contenu se tait. */
    private static volatile boolean parNomOk = false;

    private static volatile boolean enLecture = false, arret = false;
    private static volatile String message = "En attente de la salle…";
    private static volatile int lus = 0, total = 0;
    private static volatile long dernierEnvoi = 0;
    /** Pas d'envoi avant cet instant (l'utilisatrice vient d'ouvrir un wired). */
    private static volatile long pauseJusqua = 0;

    /** Salle suivie (-1 hors salle). */
    private static volatile int salleCourante = -1;
    /** Le serveur n'a repondu a rien dans cette salle : on n'insiste plus. */
    private static volatile boolean sansReponse = false;
    private static volatile Boolean derniersDroits = null;
    /** Ids des wired de la salle au dernier regroupement. */
    private static volatile Set<Integer> wiredConnus = Set.of();
    private static final Map<Integer, Boolean> estWiredParType = new ConcurrentHashMap<>();

    private static final List<Runnable> ecouteursProgres = new CopyOnWriteArrayList<>();
    private static final List<Runnable> ecouteursFin = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicBoolean progresPoste =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean finPostee =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Pause minimale entre deux Open (anti-flood). */
    private static final long PAUSE_MS = 260;
    /** Attente d'une reponse pour un wired. */
    private static final long ATTENTE_MS = 1500;
    /** Pause de la lecture apres un Open de l'utilisatrice. */
    private static final long PAUSE_ELLE_MS = 2500;
    /** Delai avant de relire un wired modifie (le serveur applique d'abord). */
    private static final long DELAI_RELECTURE_MS = 700;

    // -------------------------------------------------------------- lectures

    public static Config config(int id) { return cache.get(id); }
    public static boolean illisible(int id) { return illisibles.contains(id); }
    public static boolean enLecture() { return enLecture; }
    /** Etat court : « 42 wired lus », « lecture… 12 / 42 », « pas de droits wired ici »... */
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
        GPresets gp = Salle.gp();
        if (gp == null) return false;
        nomme(gp, "WiredFurniTrigger", "declencheur");
        nomme(gp, "WiredFurniCondition", "condition");
        nomme(gp, "WiredFurniAction", "effet");
        nomme(gp, "WiredFurniAddon", "add-on");
        nomme(gp, "WiredFurniSelector", "selecteur");
        nomme(gp, "WiredFurniVariable", "variable");
        if (!poses.contains("WiredAllVariablesDiffs")) try {
            gp.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariablesDiffs", m -> {
                try { recevoirVariables(m); } catch (Throwable ignored) { }
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

    private static void nomme(GPresets gp, String nom, String genre) {
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

    /** Un wired connu de la salle ? (sans recopier la salle) */
    private static boolean estWiredDeLaSalle(int id) {
        if (id <= 0) return false;
        if (wiredConnus.contains(id)) return true;
        HFloorItem it = Salle.sol(id);
        return it != null && estWiredType(it.getTypeId());
    }

    /** L'utilisatrice ouvre un wired : sa reponse passera, et on se met en pause. */
    private static void elleOuvre(int id) {
        long now = System.currentTimeMillis();
        // Nos propres envois ne repassent normalement pas ici ; par prudence,
        // un Open du wired qu'on vient de demander a l'instant est le notre.
        if (id == attendu && now - dernierEnvoi < 150) return;
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

    private static PresetWiredBase decoder(HPacket brut, String genre) {
        HPacket p = new HPacket(brut);
        p.resetReadIndex();
        try {
            switch (genre) {
                case "declencheur": return RetrievedWiredTrigger.fromPacket(p);
                case "condition":   return RetrievedWiredCondition.fromPacket(p);
                case "effet":       return RetrievedWiredEffect.fromPacket(p);
                case "add-on":      return RetrievedWiredAddon.fromPacket(p);
                case "selecteur":   return RetrievedWiredSelector.fromPacket(p);
                case "variable":    return RetrievedWiredVariable.fromPacket(p);
                default: return null;
            }
        } catch (Throwable t) { return null; }
    }

    /** Reponse reconnue par son nom de paquet. */
    private static void recevoir(HMessage m, String genre, boolean parNom) {
        if (demandes.isEmpty() && elleOuvre.isEmpty()) return;   // rien a faire
        if (m.getPacket().getBytesLength() > 20000) return;
        PresetWiredBase w = decoder(m.getPacket(), genre);
        if (w == null) return;
        int id = w.getWiredId();
        Long t = elleOuvre.remove(id);
        boolean elle = t != null && System.currentTimeMillis() - t < 10000;
        if (elle) {
            // SA fenetre : on ne bloque pas, on garde seulement la configuration.
            demandes.remove(id);
        } else {
            if (!demandes.remove(id)) return;    // pas a nous : la fenetre s'ouvre
            m.setBlocked(true);
        }
        if (parNom) parNomOk = true;
        cache.put(id, new Config(w, genre));
        illisibles.remove(id);
        if (id == attendu) reponse.countDown();
        if (elle) donneesChangees();
    }

    /**
     * Repli : si les noms de paquets ne se resolvent pas, on reconnait la
     * reponse attendue a son contenu (elle se decode et porte l'id demande).
     */
    private static void parContenu(HMessage m) {
        if (parNomOk || attendu == 0 || m.isBlocked()) return;
        int taille = m.getPacket().getBytesLength();
        if (taille < 30 || taille > 20000) return;
        String g = genreDe(classeAttendue);
        recevoir(m, g, false);
        if (g.equals("add-on") && demandes.contains(attendu)) recevoir(m, "variable", false);
    }

    /** Genre de paquet attendu d'apres le nom technique du wired. */
    static String genreDe(String classe) {
        String c = classe == null ? "" : classe.toLowerCase(Locale.ROOT);
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
    private static void recevoirVariables(HMessage m) {
        boolean pourMoi = System.currentTimeMillis() <= attenteVariablesJusqua;
        HPacket p = new HPacket(m.getPacket());
        p.resetReadIndex();
        p.readInteger();
        boolean dernier = p.readBoolean();
        int n = p.readInteger();
        for (int i = 0; i < n && i < 100000; i++) p.readString();
        int k = p.readInteger();
        Map<String, String> lues = new HashMap<>();
        for (int i = 0; i < k && i < 100000; i++) {
            p.readInteger();
            HWiredVariable v = new HWiredVariable(p);
            if (v.id != null) lues.put(v.id, v.name == null ? "" : v.name);
        }
        OutilMiroir.Altitude.depuisListe(lues);
        if (!pourMoi) return;
        variablesRecues.putAll(lues);
        variablesSalle = new HashMap<>(variablesRecues);
        if (dernier) attenteVariablesJusqua = 0;
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

    /** Wired presents dans la salle (mobis de sol). */
    public static List<HFloorItem> wiredDeLaSalle() {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            try { if (estWiredType(it.getTypeId())) r.add(it); }
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
                Journal.info("Suivi des wired en erreur : " + t);   // dedoublonne : pas de spam toutes les 200 ms
            }
            Salle.sommeil(200);
        }
    }

    /** Oublie tout ce qui concerne la salle precedente. */
    private static void oublierSalle() {
        cache.clear();
        illisibles.clear();
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
        GPresets gp = Salle.gp();
        if (gp == null) { etat("En attente du moteur de l'Atelier…"); return; }
        game.FloorState s = Salle.etat();
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
        if (!actif) { etat("Lecture des wired en pause (outil Wired fermé)."); return; }
        if (!Salle.furnidataPrete()) { etat("Furnidata en cours de chargement…"); return; }
        if (!branche) { etat("Écoute des paquets en préparation…"); return; }

        // Photographie : wired (avec position) + ensemble des ids de la salle.
        List<HFloorItem> sols = Salle.sols();
        List<HFloorItem> wired = new ArrayList<>();
        long sigW = 0, sigTous = 0;
        for (HFloorItem it : sols) {
            int id = it.getId();
            sigTous += melange(id);
            boolean w;
            try { w = estWiredType(it.getTypeId()); } catch (Throwable t) { w = false; }
            if (!w) continue;
            wired.add(it);
            try {
                sigW += empreinteWired(id, it.getTile().getX(), it.getTile().getY(),
                        it.getTile().getZ(), Salle.rotation(it));
            } catch (Throwable t) { sigW += melange(id); }
        }
        String signature = sols.size() + "/" + sigTous + "/" + wired.size() + "/" + sigW;
        long now = System.currentTimeMillis();
        if (reg.tic(signature, now)) appliquer(sols, wired, now);
        total = wired.size();

        Boolean droits = droits(gp);
        if (!Objects.equals(droits, derniersDroits)) {
            derniersDroits = droits;
            sansReponse = false;
            illisibles.clear();          // droits changes : on retente tout
        }
        if (Boolean.FALSE.equals(droits)) { etat("Pas de droits wired ici."); return; }
        if (sansReponse) { etat("Pas de réponse du serveur : pas de droits wired ici ?"); return; }
        if (reg.enAttente()) return;           // la salle bouge encore : on attend qu'elle se pose
        if (wired.isEmpty()) { etat("Aucun wired ici."); return; }
        String occ = occupe(gp);
        if (occ != null) { etat(occ); return; }

        List<HFloorItem> aLire = choisirALire(wired, now);
        if (aLire.isEmpty()) { etat(bilan()); return; }
        synchronized (LECTURE) { lecture(gp, salle, aLire); }
        donneesChangees();
        etat(bilan());
    }

    /**
     * Le moteur de l'Atelier pose un appart (Update* et poses en rafale) ou exporte (il
     * ouvre lui-meme les wired) : on ne lit pas en meme temps, ni pour le
     * flood, ni pour ne pas lire un wired pas encore regle.
     */
    private static String occupe(GPresets gp) {
        try {
            Object e = gp.getImporter() == null ? null : gp.getImporter().getState();
            if (e != null && !"NONE".equals(String.valueOf(e)))
                return "En attente : l'Atelier pose un appart.";
        } catch (Throwable ignored) { }
        try {
            Object e = gp.getExporter() == null ? null : gp.getExporter().getState();
            if (e != null && "FETCHING_UNKNOWN_CONFIGS".equals(String.valueOf(e)))
                return "En attente : l'Atelier exporte.";
        } catch (Throwable ignored) { }
        return null;
    }

    private static Boolean droits(GPresets gp) {
        try {
            game.RoomPermissions rp = gp.getPermissions();
            return rp == null ? null : rp.canModifyWired();
        } catch (Throwable t) { return null; }
    }

    /** Applique un changement regroupe de la salle (fil de suivi). */
    private static void appliquer(List<HFloorItem> sols, List<HFloorItem> wired, long now) {
        Set<Integer> ids = new HashSet<>();
        for (HFloorItem it : wired) ids.add(it.getId());
        cache.keySet().retainAll(ids);
        illisibles.retainAll(ids);
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

    /** Ce qu'il reste a lire : wired jamais lus (hors illisibles) et wired modifies murs. */
    private static List<HFloorItem> choisirALire(List<HFloorItem> wired, long now) {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : wired) {
            int id = it.getId();
            Long t = aRelire.get(id);
            if (t != null ? t <= now : (!cache.containsKey(id) && !illisibles.contains(id))) r.add(it);
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
        for (Integer i : ids) if (illisibles.contains(i)) nIll++;
        Map<String, String> v = variablesSalle;
        return lus + " wired lus"
                + (lus < total ? " / " + total : "")
                + (nIll > 0 ? " · " + nIll + " illisible(s)" : "")
                + (v != null && !v.isEmpty() ? " · " + v.size() + " variable(s)" : "");
    }

    // ---------------------------------------------------------------- lecture

    /** Lit les wired donnes, dans le fil de suivi. S'arrete au changement de salle. */
    private static void lecture(GPresets gp, int salle, List<HFloorItem> aLire) {
        enLecture = true;
        arret = false;
        try {
            boolean variables = !variablesDemandees;
            for (HFloorItem it : aLire) {
                String c = Salle.classe(it.getTypeId(), false);
                if (c != null && c.toLowerCase(Locale.ROOT).startsWith("wf_var_")) variables = true;
            }
            if (variables) {
                variablesDemandees = true;
                variablesRecues.clear();
                attenteVariablesJusqua = System.currentTimeMillis() + 8000;
                try {
                    attendrePause(salle);
                    gp.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                    dernierEnvoi = System.currentTimeMillis();
                } catch (Throwable ignored) { }
            }

            Set<Integer> ids = wiredConnus;
            boolean dejaLu = !cache.isEmpty();
            int essais = 0, reussis = 0;
            long derniereMaj = System.currentTimeMillis();
            for (HFloorItem it : aLire) {
                if (arret || !memeSalle(salle)) return;
                if (!attendrePause(salle)) return;
                if (occupe(gp) != null) return;          // reprendra apres, tout seul
                int id = it.getId();
                if (!wiredConnus.contains(id) && Salle.sol(id) == null) continue;   // retire entre-temps
                aRelire.remove(id);
                Config avant = cache.remove(id);
                etat("Lecture… " + compterLus(ids) + " / " + total);
                String cls = Salle.classe(it.getTypeId(), false);
                boolean ok = demander(gp, id, cls, salle);
                if (!ok && !arret && memeSalle(salle)) ok = demander(gp, id, cls, salle);
                essais++;
                if (ok) reussis++;
                else if (avant != null) cache.putIfAbsent(id, avant);     // garder l'ancienne
                else illisibles.add(id);
                lus = compterLus(ids);
                // Trois premiers wired sans reponse, rien jamais lu ici : on n'insiste pas.
                if (essais >= 3 && reussis == 0 && !dejaLu) {
                    sansReponse = true;
                    return;
                }
                if (System.currentTimeMillis() - derniereMaj > 2000) {
                    derniereMaj = System.currentTimeMillis();
                    donneesChangees();
                }
            }
        } finally {
            enLecture = false;
            attendu = 0;
            demandes.clear();
            if (!memeSalle(salle)) { salleCourante = -2; }  // le prochain tour repart propre
        }
    }

    private static boolean memeSalle(int salle) {
        game.FloorState s = Salle.etat();
        try { return s != null && s.getRoomId() == salle; } catch (Throwable t) { return false; }
    }

    /** Attend la fin d'une pause (l'utilisatrice a ouvert un wired). false si salle quittee. */
    private static boolean attendrePause(int salle) {
        boolean annonce = false;
        while (System.currentTimeMillis() < pauseJusqua) {
            if (arret || !memeSalle(salle)) return false;
            if (!annonce) { etat("Lecture en pause : tu as ouvert un wired."); annonce = true; }
            Salle.sommeil(100);
        }
        return true;
    }

    private static int compterLus(Set<Integer> ids) {
        int n = 0;
        for (Integer i : ids) if (cache.containsKey(i)) n++;
        return n;
    }

    /** Envoie Open(id) et attend la reponse ; true si la configuration est arrivee. */
    private static boolean demander(GPresets gp, int id, String classe, int salle) {
        long attente = PAUSE_MS - (System.currentTimeMillis() - dernierEnvoi);
        if (attente > 0) Salle.sommeil(attente);
        if (!attendrePause(salle)) return false;
        if (ouvertParElle(id)) return cache.containsKey(id);   // c'est elle qui l'a ouvert
        CountDownLatch l = new CountDownLatch(1);
        reponse = l;
        classeAttendue = classe;
        attendu = id;
        demandes.add(id);
        try {
            gp.sendToServer(new HPacket("Open", HMessage.Direction.TOSERVER, id));
        } catch (Throwable t) {
            demandes.remove(id);
            attendu = 0;
            return false;
        }
        dernierEnvoi = System.currentTimeMillis();
        try { l.await(ATTENTE_MS, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        attendu = 0;
        // L'id reste dans « demandes » : une reponse tardive sera encore bloquee
        // et mise en cache. La liste est videe en fin de lecture.
        return cache.containsKey(id);
    }

    // ------------------------------------------------------- lecture a la demande

    /** Une seule lecture a la fois (suivi automatique ou lecture a la demande). */
    private static final Object LECTURE = new Object();

    /**
     * Lit TOUT DE SUITE les wired donnes, meme si l'outil Wired est ferme
     * (copie de configuration : WiredCollage). Un wired deja lu et pas modifie
     * depuis n'est pas redemande. Bloquant : jamais sur le fil JavaFX.
     *
     * @param arret   true = s'arreter (bouton Arreter)
     * @param progres (faits, total), appele hors du fil JavaFX
     * @return les configurations obtenues (les wired absents n'ont pas repondu)
     */
    public static Map<Integer, Config> lireMaintenant(Collection<Integer> ids,
                                                      java.util.function.BooleanSupplier stop,
                                                      java.util.function.BiConsumer<Integer, Integer> progres) {
        Map<Integer, Config> r = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return r;
        installer();
        for (int i = 0; i < 100 && !branche; i++) Salle.sommeil(100);
        GPresets gp = Salle.gp();
        game.FloorState s = Salle.etat();
        if (gp == null || s == null || !branche) return r;
        int salle = s.getRoomId();
        if (enLecture) arret = true;          // la lecture automatique cede la place, elle reprendra
        synchronized (LECTURE) {
            arret = false;
            int fait = 0;
            for (Integer id : ids) {
                if (id == null) continue;
                if (stop != null && stop.getAsBoolean()) break;
                if (!memeSalle(salle)) break;
                Config c = cache.get(id);
                Long t = aRelire.get(id);
                if (t != null && t > System.currentTimeMillis())
                    Salle.sommeil(Math.min(2000, t - System.currentTimeMillis()));
                if (c == null || t != null) {
                    HFloorItem it = Salle.sol(id);
                    String cls = it == null ? null : Salle.classe(it.getTypeId(), false);
                    if (it != null) {
                        boolean ok = demander(gp, id, cls, salle);
                        if (!ok && memeSalle(salle)) ok = demander(gp, id, cls, salle);
                        if (ok) { aRelire.remove(id); illisibles.remove(id); }
                    }
                    c = cache.get(id);
                }
                if (c != null) r.put(id, c);
                fait++;
                if (progres != null) try { progres.accept(fait, ids.size()); } catch (Throwable ignored) { }
            }
            attendu = 0;
            demandes.clear();
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
        Platform.runLater(() -> {
            progresPoste.set(false);
            for (Runnable r : ecouteursProgres) try { r.run(); } catch (Throwable ignored) { }
        });
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

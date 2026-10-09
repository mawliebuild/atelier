package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HProductType;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Annuler / Retablir pour les constructions dans la salle.
 *
 * Principe : on ne cherche pas a deviner QUI a modifie la salle (le jeu, un
 * outil de l'Atelier, le moteur de pose...). On photographie l'etat que tient
 * le moteur de l'Atelier (Salle.sols() / Salle.murs()) toutes les ~280 ms et on compare
 * avec la photo precedente :
 *
 *   present avant et apres, place differente  ->  deplace (ou pivote)
 *   absent avant, present apres               ->  pose
 *   present avant, absent apres               ->  ramasse
 *
 * Les changements arrives en rafale (moins de 600 ms d'ecart) forment UNE
 * action : une operation d'outil sur 30 mobis s'annule d'un coup.
 *
 * Annuler = envoyer l'inverse au serveur (MoveObject, MoveWallItem,
 * PickupObject, PlaceObject depuis l'inventaire). Les changements que ces
 * envois provoquent sont "attendus" : la photo suivante les absorbe sans
 * creer de nouvelle action.
 *
 * Limite connue : MoveObject ne choisit pas la hauteur. Si l'ancienne
 * altitude differe apres annulation, on le signale dans la ligne d'etat.
 */
public final class Historique {

    private Historique() { }

    private static final int  LIMITE = 100;
    private static final long PERIODE = 280;       // ms entre deux photos
    private static final long RAFALE = 600;        // ms : au-dela, nouvelle action
    private static final long REPOS_SALLE = 1500;  // ms d'attente apres entree en salle
    private static final long GRACE = 3000;        // ms pendant lesquelles un retour attendu est ignore

    // ------------------------------------------------------------- donnees

    /** Place d'un mobi a un instant donne (sans son identifiant). */
    private static final class Place {
        final boolean mural;
        final int type;
        final int x, y, rot;     // sol
        final double z;          // sol
        final String pos;        // mur
        Place(int type, int x, int y, double z, int rot) {
            this.mural = false; this.type = type; this.x = x; this.y = y; this.z = z; this.rot = rot; this.pos = null;
        }
        Place(int type, String pos) {
            this.mural = true; this.type = type; this.x = 0; this.y = 0; this.z = 0; this.rot = 0;
            this.pos = pos == null ? "" : pos;
        }
        /** Meme place, hauteur ignoree : MoveObject ne la choisit pas. */
        boolean memePlace(Place o) {
            if (o == null || o.mural != mural) return false;
            return mural ? pos.equals(o.pos) : (x == o.x && y == o.y && rot == o.rot);
        }
    }

    /** Un mobi dans une action : avant == null -> pose ; apres == null -> ramasse. */
    private static final class Changement {
        final boolean mural;
        volatile int id;
        Place avant, apres;
        Changement(boolean mural, int id, Place avant, Place apres) {
            this.mural = mural; this.id = id; this.avant = avant; this.apres = apres;
        }
        int type() { return apres != null ? apres.type : avant != null ? avant.type : -1; }
    }

    private static final class Action {
        final List<Changement> changements = new ArrayList<>();
        final long debut = System.currentTimeMillis();
        long derniere = debut;
    }

    /** Un retour du serveur que l'on a provoque soi-meme. */
    private static final class PoseAttendue {
        final boolean mural; final int type; final Place cible; final Changement ch;
        volatile long limite = Long.MAX_VALUE;
        boolean faite = false;
        PoseAttendue(Changement ch, Place cible) {
            this.mural = ch.mural; this.type = cible.type; this.cible = cible; this.ch = ch;
        }
    }

    // --------------------------------------------------------------- etat

    private static final Object VERROU = new Object();
    private static final Deque<Action> aAnnuler = new ArrayDeque<>();
    private static final Deque<Action> aRetablir = new ArrayDeque<>();
    private static Action ouverte = null;

    private static Map<Long, Place> base = null;     // derniere photo
    private static int salle = Integer.MIN_VALUE;    // id de la salle photographiee
    private static long entree = 0;                  // moment de l'entree en salle

    /** Cles (mural + id) dont les changements sont attendus, avec leur echeance. */
    private static final Map<Long, Long> attendus = new HashMap<>();
    private static final List<PoseAttendue> posesAttendues = new ArrayList<>();

    private static volatile boolean enPause = false;
    private static volatile boolean occupe = false;
    private static volatile boolean demarre = false;
    private static volatile String message = "En attente du moteur de l'Atelier…";

    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------ API publique

    /** Lance la surveillance de la salle (une seule fois). */
    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                try { photographier(); } catch (Throwable e) {
                    System.err.println("[Atelier] historique : " + e);
                }
                try { Thread.sleep(PERIODE); } catch (InterruptedException e) { return; }
            }
        }, "atelier-historique");
        t.setDaemon(true);
        t.start();
    }

    public static void annuler()  { executer(true); }
    public static void retablir() { executer(false); }

    public static boolean peutAnnuler()  { synchronized (VERROU) { return !occupe && !aAnnuler.isEmpty(); } }
    public static boolean peutRetablir() { synchronized (VERROU) { return !occupe && !aRetablir.isEmpty(); } }
    public static boolean occupe() { return occupe; }

    /** Appele sur le fil JavaFX a chaque changement de l'historique ou du message. */
    public static void ecouter(Runnable r) { if (r != null) ecouteurs.add(r); }

    /** Actions annulables, la plus recente en premier. */
    public static List<String> actions() {
        synchronized (VERROU) {
            List<String> l = new ArrayList<>();
            for (Action a : aAnnuler) l.add(decrire(a));
            return l;
        }
    }

    /** Actions annulees que l'on peut retablir, la plus recente (chronologiquement) en premier. */
    public static List<String> actionsAnnulees() {
        synchronized (VERROU) {
            List<String> l = new ArrayList<>();
            Iterator<Action> it = aRetablir.descendingIterator();
            while (it.hasNext()) l.add(decrire(it.next()));
            return l;
        }
    }

    /** true = l'enregistrement est suspendu (la salle est toujours suivie). */
    public static void pause(boolean p) {
        enPause = p;
        synchronized (VERROU) { ouverte = null; }
        String m = p ? "Enregistrement en pause." : "Enregistrement repris.";
        dire(m);
        Journal.succes(m);
    }

    public static boolean enPause() { return enPause; }

    private static volatile boolean groupe = false;

    /** Tant que vrai, tous les changements vont dans la meme action (outil en plusieurs passes). */
    public static void grouper(boolean g) {
        synchronized (VERROU) {
            if (g) ouverte = null;                       // une action neuve commence
            else if (ouverte != null) ouverte.derniere = System.currentTimeMillis();
            groupe = g;
        }
    }

    public static void vider() {
        synchronized (VERROU) {
            aAnnuler.clear(); aRetablir.clear(); ouverte = null;
        }
        dire("Historique vidé.");
        Journal.succes("Historique vidé.");
    }

    /** Types de mobis dont les changements ne sont pas enregistres, avec leur echeance. */
    private static final Map<Integer, Long> typesIgnores = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pendant ms millisecondes, les changements des mobis de ce type ne sont pas
     * enregistres (la dalle magique que le moteur de pose promene pendant un import, ou
     * que l'Atelier pose et ramasse lui-meme). Un nouvel appel remplace l'echeance.
     */
    public static void ignorerType(int type, long ms) {
        typesIgnores.put(type, System.currentTimeMillis() + Math.max(0, ms));
    }

    private static boolean ignore(Place a, Place b, long now) {
        if (typesIgnores.isEmpty()) return false;
        Place p = b != null ? b : a;
        if (p == null || p.mural) return false;
        int t = p.type;
        Long ech = typesIgnores.get(t);
        return ech != null && ech >= now;
    }

    /** Derniere ligne d'etat. */
    public static String message() { return message; }

    // ------------------------------------------------------------- photos

    private static long cle(boolean mural, int id) {
        return (mural ? (1L << 32) : 0L) | (id & 0xffffffffL);
    }

    /** Photo de la salle ; null si illisible pour ce tour (liste modifiee pendant la copie). */
    private static Map<Long, Place> lire() {
        for (int essai = 0; essai < 2; essai++) {
            try {
                Map<Long, Place> m = new HashMap<>();
                for (HFloorItem it : new ArrayList<>(Salle.sols())) {
                    if (it == null) continue;
                    HPoint t = it.getTile();
                    if (t == null) continue;
                    m.put(cle(false, it.getId()),
                            new Place(it.getTypeId(), t.getX(), t.getY(), t.getZ(), Salle.rotation(it)));
                }
                for (HWallItem it : new ArrayList<>(Salle.murs())) {
                    if (it == null) continue;
                    m.put(cle(true, it.getId()), new Place(it.getTypeId(), it.getLocation()));
                }
                return m;
            } catch (ConcurrentModificationException e) {
                Salle.sommeil(20);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void photographier() {
        if (Salle.gp() == null) { signaler("En attente du moteur de l'Atelier…"); return; }
        EtatSalle s = Salle.etat();
        long now = System.currentTimeMillis();
        if (s == null) {
            if (salle != -1) {
                salle = -1;
                synchronized (VERROU) { base = null; }
                videSilencieux();
                signaler("Hors salle : rien à suivre.");
            }
            return;
        }
        int rid;
        try { rid = s.getRoomId(); } catch (Throwable t) { rid = 0; }
        if (rid != salle) {
            // Changement de salle : on repart de zero, sans enregistrer "tout a disparu".
            salle = rid;
            entree = now;
            synchronized (VERROU) { base = null; }
            videSilencieux();
            dire("Nouvelle salle : l'historique repart de zéro.");
            return;
        }
        Map<Long, Place> photo = lire();
        if (photo == null) return;

        List<Changement> nouveaux = new ArrayList<>();
        synchronized (VERROU) {
            purger(now);
            if (base == null) {
                // On laisse la salle finir de charger avant de prendre la reference.
                if (now - entree < REPOS_SALLE) return;
                base = photo;
                dire("Salle suivie (" + photo.size() + " mobis) : chaque modification peut s'annuler.");
                return;
            }
            // Salle rechargee (meme id) : tout disparait d'un coup -> nouvelle reference.
            if (photo.isEmpty() && base.size() >= 5) {
                base = null; entree = now;
                return;
            }
            Set<Long> cles = new HashSet<>(base.keySet());
            cles.addAll(photo.keySet());
            for (Long k : cles) {
                Place a = base.get(k), b = photo.get(k);
                if (a == null && b == null) continue;
                if (a != null && b != null && a.memePlace(b)) continue;   // rien, ou hauteur seule
                if (ignore(a, b, now)) continue;                          // dalle magique d'un import
                Long ech = attendus.get(k);
                if (ech != null && ech >= now) continue;                 // provoque par nous
                boolean mural = (k >>> 32) != 0;
                int id = (int) (k & 0xffffffffL);
                if (a == null && poseAttendue(mural, id, b, now)) continue;
                nouveaux.add(new Changement(mural, id, a, b));
            }
            base = photo;
            if (nouveaux.isEmpty() || enPause) return;
            enregistrer(nouveaux, now);
        }
        notifier();
    }

    private static void purger(long now) {
        attendus.values().removeIf(e -> e < now);
        posesAttendues.removeIf(p -> p.faite || p.limite < now);
    }

    /**
     * Un mobi apparu correspond-il a une pose que l'on vient d'envoyer ?
     * Le nouvel id peut differer de l'ancien : on reporte alors le nouvel id
     * dans tout l'historique, pour que Retablir vise le bon mobi.
     */
    private static boolean poseAttendue(boolean mural, int id, Place b, long now) {
        PoseAttendue trouvee = null;
        for (PoseAttendue p : posesAttendues) {
            if (p.faite || p.mural != mural || p.type != b.type || p.limite < now) continue;
            if (p.cible.memePlace(b) || (!mural && p.cible.x == b.x && p.cible.y == b.y)) { trouvee = p; break; }
            if (trouvee == null) trouvee = p;   // repli : meme type
        }
        if (trouvee == null) return false;
        trouvee.faite = true;
        int ancien = trouvee.ch.id;
        if (ancien != id) renommer(mural, ancien, id);
        attendus.put(cle(mural, id), now + GRACE);
        return true;
    }

    private static void renommer(boolean mural, int ancien, int nouveau) {
        for (Deque<Action> pile : List.of(aAnnuler, aRetablir))
            for (Action a : pile)
                for (Changement c : a.changements)
                    if (c.mural == mural && c.id == ancien) c.id = nouveau;
    }

    /** Ajoute des changements a l'action ouverte (rafale) ou en cree une. Sous VERROU. */
    private static void enregistrer(List<Changement> nouveaux, long now) {
        if (ouverte == null || (!groupe && now - ouverte.derniere > RAFALE) || aAnnuler.peekFirst() != ouverte) {
            ouverte = new Action();
            aAnnuler.push(ouverte);
            while (aAnnuler.size() > LIMITE) aAnnuler.removeLast();
        }
        for (Changement n : nouveaux) {
            Changement deja = null;
            for (Changement c : ouverte.changements)
                if (c.mural == n.mural && c.id == n.id) { deja = c; break; }
            if (deja == null) ouverte.changements.add(n);
            else deja.apres = n.apres;   // on garde l'avant d'origine
        }
        // Pose puis ramasse dans la meme rafale, ou retour a la place d'origine : rien.
        ouverte.changements.removeIf(c -> (c.avant == null && c.apres == null)
                || (c.avant != null && c.avant.memePlace(c.apres)));
        ouverte.derniere = now;
        if (ouverte.changements.isEmpty()) { aAnnuler.remove(ouverte); ouverte = null; }
        aRetablir.clear();
        // pas de message a chaque modification : la liste de l'onglet suffit
    }

    private static void videSilencieux() {
        synchronized (VERROU) {
            aAnnuler.clear(); aRetablir.clear(); ouverte = null;
            attendus.clear(); posesAttendues.clear();
        }
        notifier();
    }

    // ------------------------------------------------------ annuler / retablir

    private static void executer(boolean arriere) {
        Action a;
        String quoi = arriere ? "Annulation" : "Rétablissement";
        String interrompu = arriere ? "Annulation interrompue" : "Rétablissement interrompu";
        synchronized (VERROU) {
            String refus = null;
            if (occupe) refus = quoi + " impossible : une annulation est déjà en cours.";
            else if (!Salle.dansUneSalle()) refus = quoi + " impossible : entre d'abord dans une salle.";
            a = refus != null ? null : arriere ? aAnnuler.poll() : aRetablir.poll();
            if (refus == null && a == null)
                refus = arriere ? "Rien à annuler." : "Rien à rétablir.";
            if (refus != null) {
                message = refus;
                notifierHorsVerrou(refus);
                return;
            }
            occupe = true;
            ouverte = null;
        }
        int salleDepart = Salle.salleId();
        dire(quoi + " : " + decrire(a) + "…");
        Salle.tache("historique-envoi", () -> {
            String bilan;
            boolean ok = false;
            try {
                bilan = jouer(a, arriere, salleDepart);
                ok = bilan != null && !bilan.contains("Échec") && !bilan.contains("introuvable");
            } catch (Throwable t) {
                bilan = interrompu + " : " + t;
                t.printStackTrace();
            }
            boolean memeSalle = Salle.salleId() == salleDepart;
            if (!memeSalle || bilan == null) { bilan = interrompu + " : tu as changé de salle."; ok = false; }
            synchronized (VERROU) {
                // dans une autre salle, l'action (ids, cases) ne veut plus rien dire : on la jette
                if (memeSalle) {
                    (arriere ? aRetablir : aAnnuler).push(a);
                    while (aAnnuler.size() > LIMITE) aAnnuler.removeLast();
                }
                occupe = false;
            }
            dire(bilan);
            if (ok) Journal.succes(bilan); else Journal.erreur(bilan);
        });
    }

    /** Refus d'executer : ligne d'etat + un message. */
    private static void notifierHorsVerrou(String refus) {
        notifier();
        if (refus.startsWith("Rien")) Journal.succes(refus); else Journal.erreur(refus);
    }

    /** Envoie l'inverse (arriere) ou la repetition (avant) d'une action. Hors fil FX. */
    private static String jouer(Action a, boolean arriere, int salleDepart) {
        List<Changement> ramasser = new ArrayList<>(), deplacer = new ArrayList<>(), poser = new ArrayList<>();
        for (Changement c : a.changements) {
            Place cible = arriere ? c.avant : c.apres;
            Place depart = arriere ? c.apres : c.avant;
            if (cible == null) ramasser.add(c);
            else if (depart == null) poser.add(c);
            else deplacer.add(c);
        }
        List<Long> mesCles = new ArrayList<>();
        List<PoseAttendue> mesPoses = new ArrayList<>();
        int introuvables = 0;
        java.util.function.BooleanSupplier partie = () -> Salle.salleId() != salleDepart;

        try {
        // 1. ramasser (libere la place), 2. deplacer, 3. reposer depuis l'inventaire.
        for (Changement c : ramasser) {
            if (partie.getAsBoolean()) return null;
            attendre(mesCles, c);
            Salle.espacer();
            Salle.ramasser(c.id, c.mural);
        }
        for (Changement c : deplacer) {
            if (partie.getAsBoolean()) return null;
            Place cible = arriere ? c.avant : c.apres;
            attendre(mesCles, c);
            Salle.espacer();
            if (c.mural) Salle.deplacerMur(c.id, cible.pos);
            else Salle.deplacerSol(c.id, cible.x, cible.y, cible.rot);
        }
        // Repasse : un mobi bloque par un voisin pas encore revenu retente sa place.
        if (!deplacer.isEmpty()) Salle.sommeil(900);
        for (Changement c : deplacer) {
            if (partie.getAsBoolean()) return null;
            if (c.mural) continue;
            Place cible = arriere ? c.avant : c.apres;
            HFloorItem it = Salle.sol(c.id);
            if (it != null && it.getTile() != null && (it.getTile().getX() != cible.x || it.getTile().getY() != cible.y)) {
                Salle.espacer();
                Salle.deplacerSol(c.id, cible.x, cible.y, cible.rot);
            }
        }
        if (!poser.isEmpty() && !partie.getAsBoolean()) {
            Moteur gp = Salle.gp();
            if (!ramasser.isEmpty()) Salle.sommeil(600);   // laisser l'inventaire se mettre a jour
            inventairePret(gp);
            Set<Integer> pris = new HashSet<>();
            for (Changement c : poser) {
                if (partie.getAsBoolean()) return null;
                Place cible = arriere ? c.avant : c.apres;
                Integer inv = chercherInventaire(gp, c, cible, pris);
                if (inv == null) { introuvables++; continue; }
                pris.add(inv);
                attendre(mesCles, c);
                PoseAttendue p = new PoseAttendue(c, cible);
                synchronized (VERROU) { posesAttendues.add(p); }
                mesPoses.add(p);
                Salle.espacer();
                if (c.mural) Salle.envoyer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        inv + " " + cible.pos));
                // Format du moteur de pose (v1.3.8) pour un mobi de sol depuis l'inventaire
                // (ancien moteur de pose : "-%d %d %d %d", HInventoryItem.getId()).
                else Salle.envoyer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        "-" + inv + " " + cible.x + " " + cible.y + " " + cible.rot));
            }
        }
        } finally {
            // Fin des envois (meme sur erreur) : les retours restent ignores encore quelques secondes.
            long fin = System.currentTimeMillis() + GRACE;
            synchronized (VERROU) {
                for (Long k : mesCles) attendus.put(k, fin);
                for (PoseAttendue p : mesPoses) p.limite = fin;
            }
        }
        if (partie.getAsBoolean()) return null;

        // Verification de la hauteur, une fois le serveur repondu.
        Salle.sommeil(1200);
        int hauteur = 0, absents = 0;
        for (Changement c : a.changements) {
            Place cible = arriere ? c.avant : c.apres;
            if (cible == null || c.mural) continue;
            HFloorItem it = Salle.sol(c.id);
            if (it == null || it.getTile() == null) { absents++; continue; }
            try {
                if (Math.abs(it.getTile().getZ() - cible.z) > 0.01) hauteur++;
            } catch (Throwable ignored) { }
        }
        absents = Math.max(0, absents - introuvables);

        StringBuilder sb = new StringBuilder(arriere ? "Annulé : " : "Rétabli : ").append(decrire(a)).append('.');
        if (hauteur > 0) sb.append(" Hauteur non restaurée sur ").append(mobis(hauteur)).append('.');
        if (introuvables > 0) sb.append(' ').append(mobis(introuvables)).append(" introuvable(s) dans l'inventaire.");
        if (absents > 0) sb.append(" Échec pour ").append(mobis(absents)).append(" : pas revenu(s) en place.");
        return sb.toString();
    }

    private static void attendre(List<Long> mesCles, Changement c) {
        long k = cle(c.mural, c.id);
        mesCles.add(k);
        synchronized (VERROU) { attendus.put(k, Long.MAX_VALUE); }
    }

    /** Charge l'inventaire si le moteur de l'Atelier ne l'a pas encore (attend au plus ~3 s). */
    private static void inventairePret(Moteur gp) {
        if (gp == null) return;
        try {
            if (gp.getInventory().getState() == Inventaire.Etat.LOADED) return;
            if (gp.getInventory().getState() != Inventaire.Etat.LOADING) {
                ChargementAuto.inventaireDemande();
                gp.demanderInventaire();
            }
            for (int i = 0; i < 30; i++) {
                if (gp.getInventory().getState() == Inventaire.Etat.LOADED) return;
                Salle.sommeil(100);
            }
        } catch (Throwable ignored) { }
    }

    /** Meme objet d'abord (id), sinon un objet du meme type. */
    private static Integer chercherInventaire(Moteur gp, Changement c, Place cible, Set<Integer> pris) {
        if (gp == null) return null;
        try {
            List<HInventoryItem> tous = new ArrayList<>(gp.getInventory().getInventoryItems());
            HProductType genre = c.mural ? HProductType.WallItem : HProductType.FloorItem;
            for (HInventoryItem it : tous) {
                if (it == null || pris.contains(it.getId())) continue;
                if (it.getType() != null && it.getType() != genre) continue;
                if (it.getId() == c.id || it.getPlacementId() == c.id) return it.getId();
            }
            List<HInventoryItem> memeType = c.mural
                    ? gp.getInventory().getWallItemsByType(cible.type)
                    : gp.getInventory().getFloorItemsByType(cible.type);
            if (memeType != null)
                for (HInventoryItem it : new ArrayList<>(memeType))
                    if (it != null && !pris.contains(it.getId())) return it.getId();
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------ textes

    private static String mobis(int n) { return n + (n > 1 ? " mobis" : " mobi"); }

    private static String decrire(Action a) {
        int dep = 0, piv = 0, pos = 0, ram = 0;
        Changement seul = null;
        for (Changement c : a.changements) {
            seul = c;
            if (c.avant == null) pos++;
            else if (c.apres == null) ram++;
            else if (!c.mural && c.avant.x == c.apres.x && c.avant.y == c.apres.y) piv++;
            else dep++;
        }
        if (a.changements.size() == 1 && seul != null) {
            String nom = Salle.nom(seul.type(), seul.mural);
            if (pos == 1) return "Posé " + nom;
            if (ram == 1) return "Ramassé " + nom;
            if (piv == 1) return "Pivoté " + nom;
            return "Déplacé " + nom;
        }
        List<String> morceaux = new ArrayList<>();
        if (dep > 0) morceaux.add("déplacé " + mobis(dep));
        if (piv > 0) morceaux.add("pivoté " + mobis(piv));
        if (pos > 0) morceaux.add("posé " + mobis(pos));
        if (ram > 0) morceaux.add("ramassé " + mobis(ram));
        if (morceaux.isEmpty()) return "Action vide";
        String s = String.join(", ", morceaux);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Comme dire(), sans reveiller l'interface si le message n'a pas change (boucle des photos). */
    private static void signaler(String m) {
        if (!Objects.equals(m, message)) dire(m);
    }

    private static void dire(String m) {
        message = m;
        notifier();
    }

    private static void notifier() {
        if (ecouteurs.isEmpty()) return;
        try {
            Platform.runLater(() -> {
                for (Runnable r : ecouteurs) {
                    try { r.run(); } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }   // JavaFX pas encore demarre
    }
}

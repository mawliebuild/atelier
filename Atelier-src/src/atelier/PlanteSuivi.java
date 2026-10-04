package atelier;

import gearth.extensions.parsers.HEntity;
import gearth.extensions.parsers.HEntityType;
import gearth.extensions.parsers.HEntityUpdate;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Suivi des monster plants de la salle ouverte.
 *
 * Le moteur de l'Atelier ne suit que les mobis, pas les avatars ni les animaux : on tient
 * donc ici notre propre liste, a partir des paquets du serveur.
 *
 *   Users        (TOCLIENT) liste des avatars / bots / animaux, parsee par
 *                la connexion (HEntity.parse). Une monster plant est un animal de
 *                type 16 : son « figure » commence par « 16 ».
 *   UserRemove   (TOCLIENT) un avatar / animal quitte la salle (index en texte).
 *   UserUpdate   (TOCLIENT) positions ; une plante ne bouge pas, mais on suit.
 *   RoomReady    (TOCLIENT) changement de salle : on vide tout.
 *   PetInfo      (TOCLIENT) fiche d'une plante, recue quand TU la cliques
 *                dans le jeu ou dans le tableau, ou lue sans bruit par l'Atelier : croissance,
 *                bien-etre, temps restant, rarete... Ordre des champs deduit
 *                du client Flash, decode prudemment, journal hexa en console.
 *   PetStatusUpdate / PetLevelUpdate / PetRespectNotification (TOCLIENT) :
 *                mises a jour sans tout redemander (noms supposes).
 *   UserObject   (TOCLIENT) a la connexion : mon id, mon nom, et les
 *                « respects pour animaux » restants du jour.
 *
 * Detection 100 % passive : rien n'est jamais envoye vers une plante hors
 * d'une action explicite (voir enActionExplicite), car le jeu traite une
 * demande de fiche comme un clic et selectionne la plante.
 *
 * Les intercepteurs ne font que copier le paquet : la lecture se fait sur un
 * fil a part (« atelier-plantes-lecture »), dans l'ordre d'arrivee, pour ne
 * jamais retenir le jeu. Seule la decision de bloquer une fiche silencieuse
 * reste dans l'intercepteur (lecture sans copie).
 *
 * Tous les intercepts sont par NOM de paquet : si un nom ne se resout pas,
 * rien n'arrive, et les compteurs ci-dessous le montrent dans les voyants.
 */
public final class PlanteSuivi {

    private PlanteSuivi() { }

    /** Type d'animal des monster plants dans Habbo. */
    public static final int TYPE_PLANTE = 16;

    // ------------------------------------------------------------- donnees

    /** Une plante de la salle. Champs ecrits par le fil des paquets. */
    public static final class Plante {
        public final int id;                 // id d'animal
        public volatile int index = -1;      // index dans la salle
        public volatile String nom = "?";
        public volatile int proprioId = -1;
        public volatile String proprioNom = "";
        public volatile int rarete = -1;
        public volatile int x = -1, y = -1;
        public volatile String posture = "";
        public volatile boolean recoltable;  // canHarvest (adulte)
        public volatile boolean morte;       // canRevive / posture « rip »
        public volatile boolean peutReproduire;   // canBreed
        public volatile boolean permissionReproduction; // hasBreedingPermission
        // --- lu dans PetInfo
        public volatile boolean infos;       // au moins un PetInfo decode
        public volatile boolean douteux;     // decodage incomplet
        public volatile int niveau = -1, niveauMax = -1;
        public volatile int respect = -1;
        public volatile int age = -1;
        public volatile int bienEtreMax = -1;
        public volatile long finVie = 0;     // epoque ms, 0 = inconnu
        public volatile long finCroissance = 0;
        public volatile long infoRecue = 0;  // epoque ms du dernier PetInfo

        Plante(int id) { this.id = id; }

        /** Secondes avant de mourir ; -1 inconnu. */
        public long resteVie() {
            if (morte) return 0;
            long f = finVie;
            if (f <= 0) return -1;
            return Math.max(0, (f - System.currentTimeMillis()) / 1000);
        }

        /** Secondes avant maturite ; -1 inconnu, 0 adulte. */
        public long resteCroissance() {
            long f = finCroissance;
            if (f <= 0) return adulte() ? 0 : -1;
            return Math.max(0, (f - System.currentTimeMillis()) / 1000);
        }

        public boolean adulte() {
            return recoltable || (niveau > 0 && niveauMax > 0 && niveau >= niveauMax);
        }

        /** A besoin d'un soin : bien-etre sous 75 % du maximum, ou inconnu. */
        public boolean aBesoin() {
            if (morte) return false;
            long r = resteVie();
            if (r < 0) return true;
            int m = bienEtreMax;
            if (m > 0) return r < m * 0.75;
            return r < 24 * 3600;
        }

        public String etat() {
            if (morte) return "Morte";
            if (recoltable) return "À récolter";
            if (adulte()) return "Adulte";
            return infos ? "Vivante" : "Vivante ?";
        }

        public String croissance() {
            if (adulte()) return "Adulte";
            String s = niveau > 0 ? niveau + "/" + (niveauMax > 0 ? niveauMax : 7) : "?";
            long r = resteCroissance();
            if (r > 0) s += " · " + duree(r);
            return s;
        }
    }

    private static final Map<Integer, Plante> plantes = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> indexVersId = new ConcurrentHashMap<>();
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static volatile int salleConnue = -1;

    // compteurs pour les voyants
    public static volatile long nbUsers, nbPetInfo, nbRespect, nbStatus, nbUserObject;
    /** Liste Users recue depuis la derniere entree dans un appart (sinon : ecoute installee trop tard). */
    public static volatile boolean listeRecue = false;
    public static volatile long dernierRespect;  // epoque ms de la derniere notification de respect
    public static volatile long dernierRefusSoin;                 // PetRespectFailed
    public static volatile long dernierePropositionReproduction;  // PetBreeding
    public static volatile long dernierResultatReproduction;      // PetBreedingResult

    // moi
    public static volatile int monId = -1;
    public static volatile String monNom = null;
    /** Soins (respects pour animaux) restants aujourd'hui ; -1 inconnu. */
    public static volatile int soinsRestants = -1;
    /** Dernier RespectPet parti (du jeu ou de l'Atelier). */
    private static volatile long dernierSoinEnvoye = 0;
    /** Fiches demandees par l'Atelier : id -> heure. Leur reponse n'ouvre rien dans le jeu. */
    private static final Map<Integer, Long> fichesSilencieuses = new java.util.concurrent.ConcurrentHashMap<>();

    public static List<Plante> plantes() { return new ArrayList<>(plantes.values()); }
    public static Plante plante(int id) { return plantes.get(id); }
    public static void ecouter(Runnable r) { ecouteurs.add(r); }

    public static void vider() {
        plantes.clear();
        indexVersId.clear();
        prevenir();
    }

    private static void prevenir() {
        for (Runnable r : ecouteurs) { try { r.run(); } catch (Throwable ignored) { } }
    }

    // -------------------------------------------------------- installation

    private static volatile boolean branche = false, enCours = false;
    public static boolean branche() { return branche; }

    public static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900 && !branche; i++) {
                Moteur gp = Salle.gp();
                if (gp != null) {
                    try {
                        brancher(gp);
                        branche = true;
                        Journal.debug("suivi des monster plants actif.");
                        return;
                    } catch (Throwable e) {
                        Journal.debug("suivi des plantes : " + e);
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-plantes-ecoute");
        t.setDaemon(true);
        t.start();
    }

    private static void ecoute(Moteur gp, String nom, java.util.function.Consumer<HPacket> f) {
        ecoute(gp, nom, () -> true, f);
    }

    /** Un seul fil demon : les paquets sont lus dans l'ordre d'arrivee, hors de l'intercepteur. */
    private static final java.util.concurrent.ExecutorService LECTURE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-plantes-lecture");
                t.setDaemon(true);
                return t;
            });

    /**
     * utile : test tres bon marche fait AVANT la copie du paquet (UserUpdate est
     * tres frequent). L'intercepteur copie seulement ; f tourne sur LECTURE.
     */
    private static void ecoute(Moteur gp, String nom, java.util.function.BooleanSupplier utile,
                               java.util.function.Consumer<HPacket> f) {
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, nom, m -> {
                try {
                    if (!utile.getAsBoolean()) return;
                    HPacket copie = new HPacket(m.getPacket());
                    LECTURE.execute(() -> {
                        try { f.accept(copie); }
                        catch (Throwable e) { System.err.println("[Atelier] " + nom + " : " + e); }
                    });
                } catch (Throwable e) { System.err.println("[Atelier] " + nom + " : " + e); }
            });
        } catch (Throwable e) {
            Journal.debug("intercept " + nom + " indisponible : " + e);
        }
    }

    private static void brancher(Moteur gp) {
        ecoute(gp, "RoomReady", p -> {
            salleConnue = -1; listeRecue = false; vider();
            // en entrant dans un appart : soins restants relus (profil redemande)
            Salle.tache("plantes-soins", () -> { Salle.sommeil(1500); try { demanderProfil(); } catch (Throwable ignored) { } });
        });
        ecoute(gp, "Users", PlanteSuivi::surUsers);
        ecoute(gp, "UserRemove", () -> !indexVersId.isEmpty(), PlanteSuivi::surUserRemove);
        ecoute(gp, "UserUpdate", () -> !indexVersId.isEmpty(), PlanteSuivi::surUserUpdate);
        ecoute(gp, "PetInfo", PlanteSuivi::surPetInfo);
        ecoute(gp, "PetStatusUpdate", PlanteSuivi::surPetStatus);
        ecoute(gp, "PetLevelUpdate", PlanteSuivi::surPetLevel);
        ecoute(gp, "PetRespectNotification", PlanteSuivi::surRespect);
        ecoute(gp, "UserObject", PlanteSuivi::surUserObject);
        // Le serveur refuse un soin (plus de soins aujourd'hui, ou deja traitee).
        ecoute(gp, "PetRespectFailed", p -> { dernierRefusSoin = System.currentTimeMillis(); dernierSoinEnvoye = 0; prevenir(); });
        try {
            gp.intercept(HMessage.Direction.TOSERVER, "RespectPet", m -> dernierSoinEnvoye = System.currentTimeMillis());
            // TON clic sur un animal dans le jeu : sa fiche doit s'ouvrir, meme si l'Atelier
            // avait demande la meme en silence juste avant (on ne bloque plus cette reponse).
            gp.intercept(HMessage.Direction.TOSERVER, "GetPetInfo", m -> {
                try { fichesSilencieuses.remove(m.getPacket().readInteger(6)); } catch (Throwable ignored) { }
            });
            // Fiche demandee par l'Atelier : lue ici, mais pas montree dans le jeu.
            gp.intercept(HMessage.Direction.TOCLIENT, "PetInfo", m -> {
                try {
                    int id = m.getPacket().readInteger(6);      // lecture sans copie
                    Long t = fichesSilencieuses.remove(id);
                    if (t != null && System.currentTimeMillis() - t < 5000) m.setBlocked(true);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable e) {
            Journal.debug("plantes : écoutes soins / fiches : " + e);
        }
        // Reproduction : demande de confirmation, puis resultat (une graine).
        ecoute(gp, "PetBreeding", p -> { dernierePropositionReproduction = System.currentTimeMillis(); });
        ecoute(gp, "PetBreedingResult", p -> { dernierResultatReproduction = System.currentTimeMillis(); prevenir(); });
        // Apprentissage : on retient ce que le jeu envoie quand TU fais une
        // reproduction a la main, pour pouvoir la refaire a l'identique.
        try {
            gp.intercept(HMessage.Direction.TOSERVER, "BreedPets", m -> {
                try { Reproduction.observer(new HPacket(m.getPacket())); } catch (Throwable ignored) { }
            });
        } catch (Throwable e) {
            Journal.debug("intercept BreedPets indisponible : " + e);
        }
    }

    /** Vide la liste si la salle a change depuis la derniere lecture. */
    private static void verifierSalle() {
        EtatSalle s = Salle.etat();
        int id = -1;
        try { if (s != null) id = s.getRoomId(); } catch (Throwable ignored) { }
        if (id <= 0) return;                       // etat pas encore connu : on ne touche a rien
        if (id != salleConnue) {
            if (salleConnue > 0) { plantes.clear(); indexVersId.clear(); }
            salleConnue = id;
        }
    }

    // ------------------------------------------------------------- paquets

    private static void surUsers(HPacket p) {
        nbUsers++;
        listeRecue = true;
        verifierSalle();
        HEntity[] ents;
        try { ents = HEntity.parse(p); }
        catch (Throwable e) {
            System.err.println("[Atelier] Users illisible : " + e);
            return;
        }
        boolean change = false;
        for (HEntity e : ents) {
            if (e == null || e.getEntityType() != HEntityType.PET) continue;
            Object[] st = e.getStuff();
            if (!estPlante(e, st)) continue;
            Plante pl = plantes.computeIfAbsent(e.getId(), Plante::new);
            pl.index = e.getIndex();
            pl.nom = e.getName() == null ? "?" : e.getName();
            if (e.getTile() != null) { pl.x = e.getTile().getX(); pl.y = e.getTile().getY(); }
            // Champs d'un animal (connexion de l'Atelier, ordre du client Flash) :
            // 0 type, 1 ownerId, 2 ownerName, 3 rarity, 4 hasSaddle, 5 isRiding,
            // 6 canBreed, 7 canHarvest, 8 canRevive, 9 hasBreedingPermission,
            // 10 petLevel, 11 posture
            try {
                if (st != null && st.length >= 12) {
                    pl.proprioId = entier(st[1], pl.proprioId);
                    pl.proprioNom = st[2] == null ? "" : String.valueOf(st[2]);
                    pl.rarete = entier(st[3], pl.rarete);
                    pl.recoltable = Boolean.TRUE.equals(st[7]);
                    pl.peutReproduire = Boolean.TRUE.equals(st[6]);
                    pl.permissionReproduction = Boolean.TRUE.equals(st[9]);
                    pl.posture = st[11] == null ? "" : String.valueOf(st[11]);
                    pl.morte = Boolean.TRUE.equals(st[8]) || postureMorte(pl.posture);
                    int niv = entier(st[10], -1);
                    if (niv > 0) pl.niveau = niv;
                }
            } catch (Throwable ignored) { }
            indexVersId.put(pl.index, pl.id);
            change = true;
        }
        if (change) prevenir();
    }

    private static boolean estPlante(HEntity e, Object[] st) {
        String f = e.getFigureId();
        if (f != null) {
            String t = f.trim();
            int sp = t.indexOf(' ');
            String premier = sp < 0 ? t : t.substring(0, sp);
            if (premier.equals(String.valueOf(TYPE_PLANTE))) return true;
        }
        return st != null && st.length > 0 && entier(st[0], -1) == TYPE_PLANTE;
    }

    private static boolean postureMorte(String p) {
        if (p == null) return false;
        String s = p.toLowerCase(Locale.ROOT);
        return s.contains("rip") || s.contains("dead");
    }

    private static int entier(Object o, int defaut) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Throwable t) { return defaut; }
    }

    private static void surUserRemove(HPacket p) {
        if (indexVersId.isEmpty()) return;
        int idx = -1;
        try { idx = Integer.parseInt(p.readString().trim()); }
        catch (Throwable e) {
            try { p.resetReadIndex(); idx = p.readInteger(); } catch (Throwable ignored) { }
        }
        Integer id = indexVersId.remove(idx);
        if (id != null) { plantes.remove(id); prevenir(); }
    }

    private static void surUserUpdate(HPacket p) {
        if (indexVersId.isEmpty()) return;
        HEntityUpdate[] us;
        try { us = HEntityUpdate.parse(p); } catch (Throwable e) { return; }
        boolean change = false;
        for (HEntityUpdate u : us) {
            if (u == null) continue;
            Integer id = indexVersId.get(u.getIndex());
            if (id == null) continue;
            Plante pl = plantes.get(id);
            if (pl == null || u.getTile() == null) continue;
            if (pl.x != u.getTile().getX() || pl.y != u.getTile().getY()) {
                pl.x = u.getTile().getX(); pl.y = u.getTile().getY(); change = true;
            }
        }
        if (change) prevenir();
    }

    /**
     * PetInfo, ordre suppose (client Flash, PetInfoMessageParser) :
     *   int id, String nom, int niveau, int niveauMax, int experience,
     *   int experienceNiveau, int energie, int energieMax, int nutrition,
     *   int nutritionMax, int respect, int proprioId, int age(jours),
     *   String proprioNom, int race/type, bool selle, bool monte,
     *   int nbSeuils + int[nbSeuils], int droits, bool peutReproduire,
     *   bool peutRecolter, bool peutRanimer, int rarete,
     *   int bienEtreMaxSecondes, int bienEtreRestantSecondes,
     *   int croissanceRestanteSecondes, bool permissionReproduction
     * Les derniers champs sont ceux des monster plants. Lecture defensive :
     * on garde ce qui a ete lu avant une erreur, et on journalise l'hexa.
     */
    private static void surPetInfo(HPacket p) {
        nbPetInfo++;
        long now = System.currentTimeMillis();
        journal("PetInfo", p);
        int id;
        try { id = p.readInteger(); } catch (Throwable e) { return; }
        Plante pl = plantes.get(id);
        StringBuilder lu = new StringBuilder("id=" + id);
        // Valeurs lues dans l'ordre ; appliquees seulement si coherentes.
        String nom = null, proprioNom = null;
        int niveau = -1, niveauMax = -1, respect = -1, proprioId = -1, age = -1, rarete = -1;
        int bienMax = -1, bienReste = -1, croisReste = -1;
        Boolean recoltable = null, ranimable = null, reproduire = null, permission = null;
        boolean complet = false;
        try {
            nom = NomSalle.utf8(p.readString());                 lu.append(" nom=").append(nom);
            niveau = p.readInteger();
            niveauMax = p.readInteger();          lu.append(" niveau=").append(niveau).append('/').append(niveauMax);
            p.readInteger(); p.readInteger();     // experience, experience du niveau
            p.readInteger(); p.readInteger();     // energie, energie max
            p.readInteger(); p.readInteger();     // nutrition, nutrition max
            respect = p.readInteger();            lu.append(" respect=").append(respect);
            proprioId = p.readInteger();
            age = p.readInteger();
            proprioNom = NomSalle.utf8(p.readString());          lu.append(" proprio=").append(proprioNom).append('#').append(proprioId);
            p.readInteger();                      // race / type
            p.readBoolean(); p.readBoolean();     // selle, monte
            int n = p.readInteger();
            if (n < 0 || n > 64) throw new IllegalStateException("seuils " + n);
            for (int i = 0; i < n; i++) p.readInteger();
            p.readInteger();                      // droits
            reproduire = p.readBoolean();         // peut se reproduire
            recoltable = p.readBoolean();
            ranimable = p.readBoolean();          lu.append(" recolte=").append(recoltable).append(" morte=").append(ranimable);
            rarete = p.readInteger();
            bienMax = p.readInteger();
            bienReste = p.readInteger();
            croisReste = p.readInteger();         lu.append(" rarete=").append(rarete)
                                                    .append(" bienEtre=").append(bienReste).append('/').append(bienMax)
                                                    .append(" croissance=").append(croisReste);
            try { permission = p.readBoolean(); } catch (Throwable ignored) { }
            complet = true;
        } catch (Throwable e) {
            lu.append(" [arret : ").append(e.getClass().getSimpleName()).append(']');
        }
        Journal.debug("PetInfo decode : " + lu);

        boolean coherent = niveau >= 0 && niveau <= 50 && niveauMax >= 0 && niveauMax <= 50;
        // Animal inconnu : on ne l'ajoute que s'il ressemble a une plante
        // (bien-etre max positif), par ex. quand on clique une plante dans le jeu.
        if (pl == null) {
            if (!(complet && coherent && bienMax > 0)) return;
            pl = plantes.computeIfAbsent(id, Plante::new);
        }
        if (nom != null && !nom.isEmpty()) pl.nom = nom;
        if (coherent) { pl.niveau = niveau; pl.niveauMax = niveauMax; }
        if (respect >= 0 && respect < 1_000_000) pl.respect = respect;
        if (proprioId > 0) pl.proprioId = proprioId;
        if (age >= 0 && age < 100_000) pl.age = age;
        if (proprioNom != null && !proprioNom.isEmpty()) pl.proprioNom = proprioNom;
        if (recoltable != null) pl.recoltable = recoltable;
        if (reproduire != null) pl.peutReproduire = reproduire;
        if (permission != null) pl.permissionReproduction = permission;
        if (ranimable != null) pl.morte = ranimable || postureMorte(pl.posture);
        if (complet && rarete >= 0 && rarete < 100) pl.rarete = rarete;
        if (complet && bienReste >= 0 && bienReste < 365 * 86400) {
            pl.bienEtreMax = bienMax;
            pl.finVie = now + bienReste * 1000L;
            if (bienReste == 0 && bienMax > 0) pl.morte = true;
        }
        if (complet && croisReste >= 0 && croisReste < 365 * 86400)
            pl.finCroissance = croisReste == 0 ? 0 : now + croisReste * 1000L;
        pl.douteux = !(complet && coherent);
        pl.infos = true;
        pl.infoRecue = now;
        prevenir();
    }

    /** PetStatusUpdate, suppose : int index, int idAnimal, bool reproduire, bool recolter, bool ranimer, bool permission. */
    private static void surPetStatus(HPacket p) {
        nbStatus++;
        try {
            p.readInteger();
            Plante pl = plantes.get(p.readInteger());
            if (pl == null) return;
            pl.peutReproduire = p.readBoolean();
            pl.recoltable = p.readBoolean();
            pl.morte = p.readBoolean() || postureMorte(pl.posture);
            prevenir();
        } catch (Throwable ignored) { }
    }

    /** PetLevelUpdate, suppose : int index, int idAnimal, int niveau. */
    private static void surPetLevel(HPacket p) {
        try {
            p.readInteger();
            Plante pl = plantes.get(p.readInteger());
            int n = p.readInteger();
            if (pl != null && n > 0 && n <= 50) { pl.niveau = n; prevenir(); }
        } catch (Throwable ignored) { }
    }

    /** PetRespectNotification, suppose : int respect, int proprioId, puis PetData (int idAnimal, ...). */
    private static void surRespect(HPacket p) {
        nbRespect++;
        dernierRespect = System.currentTimeMillis();
        // un soin vient d'etre donne (par toi dans le jeu, ou par l'Atelier) : un de moins
        if (dernierRespect - dernierSoinEnvoye < 4000 && soinsRestants > 0) {
            soinsRestants--;
            dernierSoinEnvoye = 0;
        }
        try {
            int r = p.readInteger();
            p.readInteger();
            Plante pl = plantes.get(p.readInteger());
            if (pl != null && r >= 0 && r < 1_000_000) { pl.respect = r; prevenir(); }
        } catch (Throwable ignored) { }
    }

    /**
     * UserObject, suppose : int id, String nom, String figure, String sexe,
     * String devise, String vraiNom, bool mail, int respectsRecus,
     * int respectsADonner, int respectsAnimauxADonner, ...
     */
    private static void surUserObject(HPacket p) {
        nbUserObject++;
        try {
            int id = p.readInteger();
            String nom = NomSalle.utf8(p.readString());
            if (id > 0) monId = id;
            if (nom != null && !nom.isBlank()) monNom = nom;
            p.readString(); p.readString(); p.readString(); p.readString();
            p.readBoolean();
            int recus = p.readInteger(), aDonner = p.readInteger();
            int r = p.readInteger();
            if (r >= 0 && r < 1000) soinsRestants = r;
            Journal.debug("UserObject : " + monNom + " #" + monId + ", respects reçus " + recus
                    + ", respects à donner " + aDonner + ", soins animaux restants " + r);
        } catch (Throwable e) {
            Journal.debug("UserObject partiel : " + monNom + " #" + monId);
        }
        prevenir();
    }

    // -------------------------------------------------------------- envois

    // ------------------------------------------------- garde des envois
    //
    // Un GetPetInfo (ou tout paquet vers une plante) fait reagir le jeu comme
    // un clic : il ouvre la fiche de la plante, qui parait selectionnee. Aucun
    // envoi vers une plante n'est donc permis hors d'une action explicite de
    // l'utilisatrice (un bouton). La detection, elle, est purement passive :
    // liste Users, PetStatusUpdate, PetLevelUpdate, et les PetInfo que le jeu
    // recoit quand TU cliques une plante toi-meme.

    private static final ThreadLocal<Boolean> explicite = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Envoi reel ; remplacable par un test. */
    static volatile java.util.function.Consumer<HPacket> envoi = p -> Salle.envoyer(p);
    /** Envois vers une plante refuses faute d'action explicite (devrait rester a 0). */
    public static volatile long envoisRefuses = 0;

    /** Execute r comme une action demandee par l'utilisatrice : les envois vers les plantes y sont permis. */
    public static void enActionExplicite(Runnable r) {
        Boolean avant = explicite.get();
        explicite.set(Boolean.TRUE);
        try { r.run(); } finally { explicite.set(avant); }
    }

    public static boolean explicite() { return explicite.get(); }

    /** Envoie un paquet vers une plante, seulement dans une action explicite. */
    static boolean envoyerVersPlante(String nom, Object... args) {
        if (!explicite.get()) {
            envoisRefuses++;
            System.err.println("[Atelier] " + nom + " bloque : aucune action explicite (fil "
                    + Thread.currentThread().getName() + ").");
            return false;
        }
        envoi.accept(new HPacket(nom, HMessage.Direction.TOSERVER, args));
        return true;
    }

    /**
     * Fait comme un clic sur la plante dans le jeu : l'avatar se tourne vers
     * elle (LookTo x, y) puis la fiche est demandee (GetPetInfo id) ; la
     * reponse n'est pas bloquee, le jeu ouvre donc sa fiche. Action explicite
     * seulement.
     */
    public static boolean cliquer(int idAnimal) {
        if (!explicite.get()) return envoyerVersPlante("GetPetInfo", idAnimal);   // refuse et compte
        fichesSilencieuses.remove(idAnimal);          // cette reponse-la doit s'ouvrir dans le jeu
        Plante p = plantes.get(idAnimal);
        if (p != null && p.x >= 0 && p.y >= 0) {
            try { envoyerVersPlante("LookTo", p.x, p.y); }
            catch (Throwable e) { Journal.debug("LookTo impossible : " + e); }
        }
        Journal.debug("clic sur la plante " + idAnimal + (p == null ? "" : " (" + p.nom + " en " + p.x + "," + p.y + ")"));
        return envoyerVersPlante("GetPetInfo", idAnimal);
    }

    /** Demande la fiche d'une plante (le jeu l'affiche comme apres un clic). */
    public static boolean demanderInfo(int idAnimal) {
        return envoyerVersPlante("GetPetInfo", idAnimal);
    }

    /** Le « Traiter » du jeu pour une plante : le respect d'animal. */
    public static boolean traiter(int idAnimal) {
        boolean ok = envoyerVersPlante("RespectPet", idAnimal);
        if (ok) dernierSoinEnvoye = System.currentTimeMillis();
        return ok;
    }

    /**
     * Lit la fiche d'une plante SANS rien ouvrir dans le jeu : la reponse est
     * lue par l'Atelier puis retenue (le jeu ne l'a pas demandee). Pas un clic :
     * permis hors action explicite.
     */
    public static void demanderInfoSilencieuse(int idAnimal) {
        fichesSilencieuses.put(idAnimal, System.currentTimeMillis());
        envoi.accept(new HPacket("GetPetInfo", HMessage.Direction.TOSERVER, idAnimal));
    }

    public static boolean recolter(int idAnimal) {
        return envoyerVersPlante("HarvestPet", idAnimal);
    }

    public static boolean composter(int idAnimal) {
        return envoyerVersPlante("CompostPlant", idAnimal);
    }

    /**
     * Les plantes dont la fiche manque ou date de plus de 10 min (les mortes
     * deja lues sont laissees). Logique pure : ne fait aucun envoi.
     */
    public static List<Plante> aLire(Collection<Plante> l, long maintenant) {
        List<Plante> r = new ArrayList<>();
        for (Plante p : l) {
            if (p == null) continue;
            if (p.morte && p.infos) continue;
            if (p.infos && maintenant - p.infoRecue < 10 * 60_000) continue;
            r.add(p);
        }
        return r;
    }

    /** Redemande mon profil (UserObject) : sans argument. */
    public static void demanderProfil() {
        Salle.envoyer(new HPacket("InfoRetrieve", HMessage.Direction.TOSERVER));
    }

    /**
     * Redemande le contenu de la salle sans la recharger : c'est ce que fait
     * le moteur de l'Atelier (EtatSalle.requestRoom envoie GetHeightMap). Le serveur
     * renvoie alors aussi la liste Users.
     */
    public static boolean redemanderSalle() {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        try {
            EtatSalle s = gp.getFloorState();
            if (s != null) { gp.demanderSalle(); return true; }
        } catch (Throwable ignored) { }
        Salle.envoyer(new HPacket("GetHeightMap", HMessage.Direction.TOSERVER));
        return true;
    }

    // --------------------------------------------------------------- divers

    public static boolean estAMoi(Plante pl, String nomSaisi) {
        if (pl == null) return false;
        if (monId > 0 && pl.proprioId == monId) return true;
        String n = (nomSaisi != null && !nomSaisi.isBlank()) ? nomSaisi.trim() : monNom;
        return n != null && !n.isBlank() && n.equalsIgnoreCase(pl.proprioNom);
    }

    private static void journal(String nom, HPacket p) {
        if (Journal.DEBUG) try {
            byte[] b = p.toBytes();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length && i < 400; i++) sb.append(String.format("%02x", b[i] & 0xff));
            if (b.length > 400) sb.append("...");
            Journal.debug(nom + " (" + b.length + " o) " + sb);
        } catch (Throwable ignored) { }
        try { p.resetReadIndex(); } catch (Throwable ignored) { }
    }

    public static String duree(long s) {
        if (s < 0) return "?";
        if (s < 60) return s + " s";
        long j = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (j > 0) return j + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }
}

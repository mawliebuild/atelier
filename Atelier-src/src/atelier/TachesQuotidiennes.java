package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Les taches quotidiennes du jeu (Missions > Taches quotidiennes) : suivies,
 * et faites a ta place quand elles ne coutent rien.
 *
 * Paquets (client Flash actuel, DailyTasksController ; noms de l'API) :
 *   GetDailyTasks          (au serveur, sans argument) : redemande la liste ;
 *   ClaimDailyTask         (au serveur, int idTache) : reclame la recompense ;
 *   DailyTasksActiveList   (au jeu) : la liste du jour, int n puis n taches ;
 *   DailyTasksTasksAdded   (au jeu) : taches ajoutees (la bonus), meme format ;
 *   DailyTasksTaskUpdate   (au jeu) : long id, int faits, byte statut, int secondes.
 * Une tache : long id, String code (« 1734011773612_G »), String type,
 * bool bonus, String version d'image, String nom catalogue, int requis,
 * int faits, byte statut (0 en cours, 1 faite, 2 reclamee), int secondes
 * restantes, puis int n recompenses (short, String, String, int).
 * Nos GetDailyTasks repondent aussi au jeu : sa fenetre se met a jour, sans gene.
 *
 * Ce que fait une tache vient de la table ACTIONS (code -> action), tiree de
 * la page de HabbGames et des textes du jeu ; la plupart se font chez Ness
 * (:roomid 45273021). Une tache qui fait depenser (credits, duckets,
 * diamants, effet a acheter) n'est JAMAIS faite. Le mobi d'une tache se
 * cherche par sa classe, sinon par un morceau de son nom ; quand tu fais une
 * tache toi-meme, le mobi que tu double-cliques juste avant sa progression
 * est retenu pour elle (preference « taches.appris.<code> »).
 *
 * Mon avatar : son index vient du paquet Users (mon id ou mon pseudo), sa
 * case de UserUpdate ; on ne lit UserUpdate que pendant l'automatisation.
 * Tous les envois passent au rythme de Salle.espacer, et les gestes sociaux
 * (chat, danse, signe) sont espaces de plusieurs secondes : pas de flood.
 */
public final class TachesQuotidiennes {

    private TachesQuotidiennes() { }

    private static final HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;

    /** L'appart « [HGS] Quête » de Ness. */
    public static final int APPART_NESS = 45273021;

    // ================================================================ donnees

    public static final int EN_COURS = 0, FAITE = 1, RECLAMEE = 2;

    /** Une tache du jour, telle que le serveur l'a dite. */
    public static final class Tache {
        public final long id;
        public final String code, type, catalogue;
        public final boolean bonus;
        public final int requis;
        public volatile int faits, statut, secondes;
        Tache(long id, String code, String type, boolean bonus, String catalogue, int requis, int faits, int statut, int secondes) {
            this.id = id; this.code = code; this.type = type; this.bonus = bonus; this.catalogue = catalogue;
            this.requis = requis; this.faits = faits; this.statut = statut; this.secondes = secondes;
        }
        public Action action() { return TachesQuotidiennes.action(code); }
        /** Tache d'un jour passe, faite mais pas reclamee. */
        public boolean ancienne() { return secondes < 0 && statut == FAITE; }
    }

    /** Ce que l'Atelier sait faire. */
    public enum Genre {
        DOUBLE_CLIC, ASSIS, COUCHE, MARCHER_SUR, DANSER_DUREE, DANSER_FOIS, SALUER, PANNEAU, SAUTER,
        PARLER, EXPLORER, CARESSER, DEPENSE, SOI_MEME
    }

    /**
     * Une ligne de la table. classes : noms techniques (« * » final = prefixe) ;
     * noms : morceaux du nom du mobi (mots entiers, sans accents ni casse) ;
     * nombre : repetitions ou secondes selon le genre ; salle : l'appart ou
     * aller (0 : n'importe ou, dans un appart).
     */
    public record Action(String nom, Genre genre, int salle, int nombre, String consigne,
                         String[] classes, String[] noms) {
        public boolean automatique() { return genre != Genre.DEPENSE && genre != Genre.SOI_MEME; }
    }

    private static Action a(String nom, Genre g, int salle, int nombre, String consigne, String[] classes, String... noms) {
        return new Action(nom, g, salle, nombre, consigne, classes, noms);
    }
    private static String[] cl(String... c) { return c; }
    private static final String[] AUCUNE = new String[0];

    /** Double-clic sur un mobi chez Ness. */
    private static Action mobi(String nom, String consigne, String[] classes, String... noms) {
        return a(nom, Genre.DOUBLE_CLIC, APPART_NESS, 1, consigne, classes, noms);
    }

    /**
     * La table : code de la tache (textes du jeu, « dailytask.<code>.name »)
     * -> action. Pour en ajouter une : une ligne ici. Les codes inconnus
     * s'affichent « à faire soi-même ».
     */
    static final Map<String, Action> ACTIONS = new LinkedHashMap<>();
    static {
        Map<String, Action> t = ACTIONS;
        t.put("1734011389107_G", a("Le plaisir des routards", Genre.EXPLORER, 0, 10, "Explorer 10 apparts.", AUCUNE));
        t.put("1734011773612_G", mobi("MANGER !", "Double-cliquer sur un barbecue.", cl("summer_grill*", "hblooza_bbq"), "barbecue"));
        t.put("1734011895115_G", mobi("Attrapez !", "Double-cliquer sur un ballon de plage.", cl("bw_bball"), "ballon de plage"));
        t.put("1734017532958_G", mobi("Patrouille des Ours", "Double-cliquer sur un Ours Jongleur.", AUCUNE, "ours jongleur", "ours"));
        t.put("1734079966714_G", mobi("Bienvenue à bord !", "Double-cliquer sur un canot de sauvetage.", cl("bw_boat*"), "canot"));
        t.put("1734080389144_G", mobi("Croisade de la vitamine A !", "Double-cliquer sur le Minibar HC (ou un autre frigo) pour une carotte.",
                cl("hcc_minibar"), "minibar", "frigo", "refrigerateur"));
        t.put("1734080703257_G", mobi("Où est Ma Nourriture ?", "Double-cliquer sur un Chat San Persan Poilu.", AUCUNE, "chat san persan", "chat"));
        t.put("1734081671274_G", mobi("Observation des oiseaux", "Double-cliquer sur un Poussin.", AUCUNE, "poussin"));
        t.put("1734081804428_G", mobi("Doucement...", "Double-cliquer sur un Crocodile.", AUCUNE, "crocodile"));
        t.put("1734083261885_G", mobi("Le Roi du Château", "Double-cliquer sur une Tour de sable.", cl("sand_cstl_twr"), "tour"));
        t.put("1734083605369_G", mobi("Hey Mr. DJ", "Double-cliquer sur une Table de mixage.", cl("party_djtable"), "table de mixage"));
        t.put("1734083877537_G", mobi("Un chien a besoin d'aide !", "Double-cliquer sur un chien Husky Nautique.", AUCUNE, "husky nautique", "husky"));
        t.put("1734084129952_G", mobi("Vol de Dragon", "Double-cliquer sur un Dragon Volcano.", AUCUNE, "dragon volcano", "dragon"));
        t.put("1734084263725_G", mobi("Des requins ? Où ça ?!", "Double-cliquer sur un Requin.", cl("bw_fin"), "requin"));
        t.put("1734084662772_G", mobi("C'est l'Happy Hour !", "Double-cliquer sur une Glacière.", cl("summer_icebox"), "glaciere"));
        t.put("1734084926818_G", a("Excursion dans la jungle", Genre.MARCHER_SUR, APPART_NESS, 5,
                "Marcher 5 fois sur des Patchs feuille de jungle.", cl("anc_jngl_patch"), "patch feuille de jungle"));
        t.put("1734085401642_G", mobi("Cherchez Leo", "Double-cliquer sur un Lion Inferno.", AUCUNE, "lion inferno", "lion"));
        t.put("1734085631955_G", mobi("Recherche d'un écran d'argent", "Double-cliquer sur l'Écran géant Habbowood.", cl("cine_screen"), "ecran geant"));
        t.put("1734085847884_G", a("Relax, mon vieux !", Genre.COUCHE, APPART_NESS, 5 * 60,
                "Rester sur un lit pendant 5 minutes.", cl("bed_armas_one"), "lit"));
        t.put("1734090080825_G", mobi("Chasseur de vitamine C !", "Double-cliquer sur le Panier d'Oranges (ou un autre frigo).",
                cl("china_c21_orangebasket"), "panier d'oranges"));
        t.put("1734091081760_G", mobi("Points chauds", "Double-cliquer sur le Parasol parisien.", cl("paris_c15_parasol"), "parasol parisien"));
        t.put("1735032138894_G", mobi("Un choix de pique-nique", "Double-cliquer sur le Panier de Picnic.", cl("picnic_basket"), "panier de picnic"));
        t.put("1735032535146_G", mobi("Quel sanglier !", "Double-cliquer sur un Cochon Kisentdeydi.", AUCUNE, "kisentdeydi", "sanglier", "cochon"));
        t.put("1735032866235_G", mobi("Hors des rails", "Double-cliquer sur un Rail de skate.", cl("sb_rail"), "rail de skate"));
        t.put("1735033170628_G", mobi("Slide", "Double-cliquer sur une Rampe de skate.", cl("sb_ramp"), "rampe de skate"));
        t.put("1735033733395_G", mobi("Alerte à la grenouille rare !", "Double-cliquer sur une Grenouille socratique.", AUCUNE, "grenouille socratique", "grenouille"));
        t.put("1735033914077_G", mobi("La justice par les cornes", "Double-cliquer sur un Rhino gris.", AUCUNE, "rhino gris", "rhino"));
        t.put("1736418045621_G", a("Asseyez-vous", Genre.ASSIS, APPART_NESS, 10 * 60,
                "Rester assise sur un Fauteuil de coiffeur pendant 10 minutes.", cl("runway_chair*"), "fauteuil de coiffeur"));
        t.put("1736418692846_G", mobi("On y va ?", "Double-cliquer sur un coquillage.", cl("coralking_c18_clamshell*"), "coquillage"));
        t.put("1736419490512_G", mobi("Le gel douche", "Double-cliquer sur une douche.", cl("bw_shower"), "douche"));
        t.put("1736419911400_G", mobi("Arachnophobia", "Double-cliquer sur une Araignée.", AUCUNE, "araignee"));
        t.put("1738665649831_G", mobi("Grrrr", "Double-cliquer sur un Westie.", AUCUNE, "westie"));
        t.put("1738669228963_G", mobi("L'idole oubliée", "Double-cliquer sur la Statue du Grand Frank.", cl("anc_artifact3"), "statue du grand frank"));
        t.put("1738669470836_G", mobi("Glouglou", "Double-cliquer sur une Chute grandes eaux.", cl("tiki_waterfall"), "chute grandes eaux"));
        t.put("1738669736503_G", a("Sens l'amour qui t'entoure", Genre.DEPENSE, 0, 0,
                "Porter l'effet Love Birds (75 au catalogue).", AUCUNE));
        t.put("1738670028343_G", a("Champion du monde?", Genre.DOUBLE_CLIC, APPART_NESS, 10,
                "Double-cliquer 10 fois sur un ballon de foot.", cl("fball_ball*"), "ballon de foot"));
        t.put("1738670908293_G", a("ROLLERDISCO!", Genre.MARCHER_SUR, APPART_NESS, 20,
                "Marcher sur 20 Rollerparks.", cl("val11_floor"), "rollerpark"));
        t.put("1738671194164_G", a("Rolling rolling...", Genre.DEPENSE, 0, 0,
                "Porter l'effet skate (acheté à la boutique) et rouler 40 fois sur du bitume. Si tu as déjà l'effet, fais-la toi-même.", AUCUNE));
        t.put("1738672360392_G", a("Super Skater", Genre.DEPENSE, 0, 0,
                "Porter l'effet skate (acheté à la boutique). Si tu as déjà l'effet, fais-la toi-même.", AUCUNE));
        t.put("1738672844085_G", a("Marche ici", Genre.MARCHER_SUR, APPART_NESS, 30,
                "Marcher sur 30 trottoirs différents.", cl("urban_sidewalk"), "trottoir"));
        t.put("1738674603656_G", a("Catalogue en folie !", Genre.DEPENSE, 0, 0, "Dépenser des crédits au catalogue (le marché ne compte pas).", AUCUNE));
        t.put("1738752019756_G", a("La course au diamant !", Genre.DEPENSE, 0, 0, "Dépenser des diamants au catalogue.", AUCUNE));
        t.put("1738752193145_G", a("Catalogue en folie !", Genre.DEPENSE, 0, 0, "Dépenser des crédits au catalogue (le marché ne compte pas).", AUCUNE));
        t.put("1740473719724_G", a("La course au diamant !", Genre.DEPENSE, 0, 0, "Dépenser 100 duckets au catalogue.", AUCUNE));
        t.put("1741850663726_G", a("Discutez !", Genre.PARLER, 0, 25,
                "Envoyer 25 bulles (dans un de tes apparts).", AUCUNE));
        t.put("1741855402906_G", a("Ils bougent !", Genre.DANSER_DUREE, 0, 150, "Danser pendant environ 2 minutes.", AUCUNE));
        t.put("1741855705682_G", a("Montrez vos mouvements !", Genre.DANSER_FOIS, 0, 15, "Danser 15 fois.", AUCUNE));
        t.put("1741863086174_G", a("Qu'est-ce que cela fait ?", Genre.DOUBLE_CLIC, APPART_NESS, 20,
                "Actionner un levier 20 fois.", cl("wf_floor_switch*"), "levier"));
        t.put("1741864304637_G", a("Plongez !", Genre.SOI_MEME, 0, 0, "Trouver une étendue d'eau et y nager un moment.", AUCUNE));
        t.put("1741864788753_G", a("C'est bien de saluer !", Genre.SALUER, 0, 7, "Faire signe (ou dire o/) 7 fois.", AUCUNE));
        t.put("1742192895786_G", a("Équipe de danse", Genre.DANSER_DUREE, APPART_NESS, 60,
                "Danser avec 4 autres Habbos (5 en tout, les bots comptent) pendant 30 secondes.", AUCUNE));
        t.put("1742193348839_G", mobi("L'heure de la boisson chaude", "Double-cliquer sur une Machine à café.", cl("mocchamaster"), "machine a cafe"));
        t.put("1742194750225_G", mobi("Une sensation de smoothie", "Double-cliquer sur une machine à jus.", cl("bonusrare18_1*"), "machine a jus"));
        t.put("1742195901294_G", mobi("Vite avant qu'il ne fonde", "Double-cliquer sur une sorbetière.", cl("rare_icecream*"), "sorbetiere"));
        t.put("1742198483809_G", a("Scritchy-scratchy !", Genre.CARESSER, APPART_NESS, 3, "Caresser un animal 3 fois.", AUCUNE));
        t.put("1742199338599_G", mobi("Un peu de jus aujourd'hui", "Double-cliquer sur un Lavabo sanglant.", cl("hween08_sink"), "lavabo sanglant"));
        t.put("1742369952117_G", a("De l'air pur", Genre.SOI_MEME, 0, 0, "Emprunter 3 téléporteurs (chez Ness : :roomid 45273021).", AUCUNE));
        t.put("1742371344744_G", a("Ce siège est-il occupé ?", Genre.ASSIS, APPART_NESS, 60,
                "S'asseoir sur un siège pendant 1 minute.", cl("runway_chair*"), "fauteuil", "chaise"));
        t.put("1742372923261_G", a("Introspection", Genre.SOI_MEME, 0, 0, "Modifier ton humeur sur ton profil.", AUCUNE));
        t.put("1742375669365_G", a("Montrez-lui qu'il est top !", Genre.PANNEAU, 0, 10, "Faire le panneau 10 (:sign 10).", AUCUNE));
        t.put("1742376443097_G", a("Sautez de joie !", Genre.SAUTER, 0, 0, "Sauter.", AUCUNE));
        t.put("1742376992244_G", a("L'heure de la décoration !", Genre.SOI_MEME, 0, 0, "Poser 10 mobis de ton inventaire chez toi (pas le BC).", AUCUNE));
        t.put("1742377131607_G", a("Organisez une soirée !", Genre.SOI_MEME, 0, 0, "Faire danser 3 Habbos avec toi, chez toi.", AUCUNE));
        t.put("1742377579479_G", a("Redécoration !", Genre.SOI_MEME, 0, 0, "Déplacer 10 fois un mobi chez toi.", AUCUNE));
        t.put("1742378786317_G", a("Faire des rencontres", Genre.SOI_MEME, 0, 0, "Envoyer 3 demandes d'amis.", AUCUNE));
        t.put("1742379817871_G", a("Reposez vos pieds !", Genre.COUCHE, APPART_NESS, 5 * 60,
                "S'allonger un moment sur un lit.", cl("bed_armas_one"), "lit"));
    }

    /** L'action d'un code ; un code inconnu : a faire soi-meme. */
    public static Action action(String code) {
        Action x = ACTIONS.get(code);
        if (x != null) return x;
        return a("Tâche " + code, Genre.SOI_MEME, 0, 0, "Tâche inconnue de l'Atelier : regarde sa consigne dans Missions > Tâches quotidiennes.", AUCUNE);
    }

    private static final Map<Long, Tache> taches = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static volatile long listeRecueLe = 0;

    /** Les taches connues, dans l'ordre du jeu (bonus a la fin). */
    public static List<Tache> taches() {
        synchronized (taches) { return new ArrayList<>(taches.values()); }
    }
    public static boolean listeRecue() { return listeRecueLe > 0; }
    public static void ecouter(Runnable r) { ecouteurs.add(r); }
    private static void prevenir() { for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { } }

    // ================================================================ ecoute

    /** Lecture des paquets copies, dans l'ordre d'arrivee, hors des intercepteurs. */
    private static final java.util.concurrent.ExecutorService LECTURE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-taches-lecture");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean branche = false, enCours = false;

    /** Branche les ecoutes une seule fois (le moteur arrive apres les onglets : on attend). */
    public static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Salle.tache("taches-ecoute", () -> {
            for (int i = 0; i < 1800 && !branche; i++) {
                Moteur gp = Salle.gp();
                if (gp != null) {
                    try { brancher(gp); branche = true; return; } catch (Throwable ignored) { }
                }
                Salle.sommeil(1000);
            }
        });
    }

    private static void brancher(Moteur gp) {
        gp.intercept(C, "DailyTasksActiveList", m -> lire(m, true));
        gp.intercept(C, "DailyTasksTasksAdded", m -> lire(m, false));
        gp.intercept(C, "DailyTasksTaskUpdate", m -> {
            HPacket p = new HPacket(m.getPacket());
            LECTURE.execute(() -> surMaj(p));
        });
        // mon avatar et les animaux de la salle (Users est rare : toujours lu)
        gp.intercept(C, "Users", m -> {
            HPacket p = new HPacket(m.getPacket());
            LECTURE.execute(() -> surUsers(p));
        });
        gp.intercept(C, "UserUpdate", m -> {
            if (!actif || monIndex < 0) return;
            HPacket p = new HPacket(m.getPacket());
            LECTURE.execute(() -> surPositions(p));
        });
        gp.intercept(C, "UserObject", m -> {
            try {
                HPacket p = new HPacket(m.getPacket());
                int id = p.readInteger();
                String nom = NomSalle.utf8(p.readString());
                if (id > 0) monId = id;
                if (nom != null && !nom.isBlank()) monNom = nom;
            } catch (Throwable ignored) { }
        });
        gp.intercept(C, "RoomReady", m -> { monIndex = -1; animaux.clear(); });
        // apprentissage : le mobi double-clique par le jeu juste avant une progression
        gp.intercept(S, "UseFurniture", m -> {
            try { dernierClicJeu = m.getPacket().readInteger(6); dernierClicJeuLe = System.currentTimeMillis(); }
            catch (Throwable ignored) { }
        });
        Journal.debug("tâches : écoute installée (en-têtes liste=" + RechercheReseau.entete(C, "DailyTasksActiveList")
                + " ajout=" + RechercheReseau.entete(C, "DailyTasksTasksAdded")
                + " maj=" + RechercheReseau.entete(C, "DailyTasksTaskUpdate")
                + " demande=" + RechercheReseau.entete(S, "GetDailyTasks")
                + " réclamer=" + RechercheReseau.entete(S, "ClaimDailyTask") + ").");
    }

    private static void lire(HMessage m, boolean liste) {
        HPacket p = new HPacket(m.getPacket());
        LECTURE.execute(() -> {
            List<Tache> l = lireTaches(p);
            if (l == null) {
                Journal.debug("tâches : " + (liste ? "DailyTasksActiveList" : "DailyTasksTasksAdded")
                        + " illisible, octets : " + hexa(p));
                return;
            }
            synchronized (taches) {
                if (liste) taches.clear();
                // comme le jeu : les taches normales d'abord, la bonus ensuite
                for (Tache t : l) if (!t.bonus) taches.putIfAbsent(t.id, t);
                for (Tache t : l) if (t.bonus) taches.putIfAbsent(t.id, t);
            }
            listeRecueLe = System.currentTimeMillis();
            for (Tache t : l)
                Journal.debug("tâche " + t.id + " « " + t.action().nom() + " » code=" + t.code + " type=" + t.type
                        + " catalogue=" + t.catalogue + (t.bonus ? " bonus" : "") + " : " + t.faits + "/" + t.requis
                        + ", statut " + t.statut + ", " + t.secondes + " s.");
            prevenir();
        });
    }

    /** int n, puis n taches ; null si le paquet ne se lit pas jusqu'au bout. */
    static List<Tache> lireTaches(HPacket p) {
        try {
            p.resetReadIndex();
            int n = p.readInteger();
            if (n < 0 || n > 100) return null;
            List<Tache> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long id = p.readLong();
                String code = p.readString(), type = p.readString();
                boolean bonus = p.readBoolean();
                p.readString();                                  // version de l'image
                String catalogue = p.readString();
                int requis = p.readInteger(), faits = p.readInteger();
                int statut = p.readByte();
                int secondes = p.readInteger();
                int r = p.readInteger();
                if (r < 0 || r > 50) return null;
                for (int k = 0; k < r; k++) { p.readShort(); p.readString(); p.readString(); p.readInteger(); }
                l.add(new Tache(id, code, type, bonus, catalogue, requis, faits, statut, secondes));
            }
            return l;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void surMaj(HPacket p) {
        long id; int faits, statut, secondes;
        try {
            p.resetReadIndex();
            id = p.readLong(); faits = p.readInteger(); statut = p.readByte(); secondes = p.readInteger();
        } catch (Throwable e) {
            Journal.debug("tâches : DailyTasksTaskUpdate illisible, octets : " + hexa(p));
            return;
        }
        Tache t = taches.get(id);
        Journal.debug("tâches : mise à jour " + id + (t == null ? " (inconnue)" : " « " + t.action().nom() + " »")
                + " : " + faits + (t == null ? "" : "/" + t.requis) + ", statut " + statut + ".");
        if (t == null) { demander(); return; }
        boolean avance = faits > t.faits || (statut != EN_COURS && t.statut == EN_COURS);
        t.faits = faits; t.statut = statut; t.secondes = secondes;
        derniereProgressionLe = System.currentTimeMillis();
        if (avance && !actif) apprendre(t);
        prevenir();
    }

    // ------------------------------------------------------- apprentissage

    private static volatile int dernierClicJeu = -1;
    private static volatile long dernierClicJeuLe = 0;
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");

    /** Tu fais la tache toi-meme : le mobi double-clique juste avant est retenu pour elle. */
    private static void apprendre(Tache t) {
        if (t.action().genre() != Genre.DOUBLE_CLIC) return;
        if (System.currentTimeMillis() - dernierClicJeuLe > 8000) return;
        HFloorItem it = Salle.sol(dernierClicJeu);
        String c = it == null ? null : Salle.classe(it.getTypeId(), false);
        if (c == null) return;
        PREFS.put("taches.appris." + t.code, c);
        Journal.debug("tâches : « " + t.action().nom() + " » (" + t.code + ") avance après un double-clic sur " + c
                + " : retenu pour la prochaine fois.");
    }

    static String appris(String code) { return PREFS.get("taches.appris." + code, null); }

    // ---------------------------------------------------------------- envois

    /** Redemande la liste au serveur (le jeu la recoit aussi). */
    public static void demander() {
        Moteur gp = Salle.gp();
        if (gp == null) return;
        installer();
        gp.sendToServer(RechercheReseau.paquet(S, new String[]{"GetDailyTasks"}));
    }

    private static void reclamer(Tache t) {
        Salle.espacer();
        Salle.envoyer(RechercheReseau.paquet(S, new String[]{"ClaimDailyTask"}, (int) t.id));
        Salle.envoiFait();
    }

    // ================================================================ avatar

    private static volatile int monId = -1, monIndex = -1, monX = -1, monY = -1;
    private static volatile String monNom = null;
    private static volatile long positionLe = 0;
    /** Animaux de la salle (id d'animal), pour les caresses. */
    private static final CopyOnWriteArrayList<Integer> animaux = new CopyOnWriteArrayList<>();

    private static int moi() {
        if (monId > 0) return monId;
        return PlanteSuivi.monId;
    }

    private static String monPseudo() {
        if (monNom != null) return monNom;
        String p = Patrimoine.pseudo();
        return p != null && !p.isBlank() ? p : PlanteSuivi.monNom;
    }

    /**
     * Users : mon index et ma case ; les animaux. Format : voir
     * PlanteSuivi.lireEntites. Lu avec puis sans le « badgesRank » des
     * avatars ; on garde la lecture qui va exactement au bout du paquet.
     */
    private static void surUsers(HPacket p) {
        if (!lireUsers(p, true, false)) lireUsers(p, false, false);
        if (!lireUsers(p, true, true)) lireUsers(p, false, true);
    }

    /** garder faux : verifie seulement que la lecture est exacte ; vrai : retient aussi. */
    private static boolean lireUsers(HPacket p, boolean avecRang, boolean garder) {
        try {
            p.resetReadIndex();
            int n = p.readInteger();
            if (n < 0 || n > 10_000) return false;
            int moi = moi();
            String pseudo = monPseudo();
            for (int i = 0; i < n; i++) {
                int id = p.readInteger();
                String nom = NomSalle.utf8(p.readString());
                p.readString(); p.readString();                  // devise, figure
                int index = p.readInteger(), x = p.readInteger(), y = p.readInteger();
                p.readString(); p.readInteger();                 // z, direction
                int type = p.readInteger();
                switch (type) {
                    case 1 -> {
                        p.readString(); p.readInteger(); p.readInteger();
                        p.readString(); p.readString(); p.readInteger(); p.readBoolean();
                        if (avecRang) p.readInteger();
                        boolean c = (id > 0 && id == moi) || (nom != null && nom.equalsIgnoreCase(pseudo));
                        if (c && garder) { monIndex = index; monX = x; monY = y; positionLe = System.currentTimeMillis(); }
                    }
                    case 2 -> {
                        if (garder) animaux.addIfAbsent(id);
                        p.readInteger(); p.readInteger(); p.readString(); p.readInteger();
                        p.readBoolean(); p.readBoolean(); p.readBoolean(); p.readBoolean(); p.readBoolean(); p.readBoolean();
                        p.readInteger(); p.readString();
                    }
                    case 3 -> { }
                    case 4 -> {
                        p.readString(); p.readInteger(); p.readString();
                        int k = p.readInteger();
                        if (k < 0 || k > 1000) return false;
                        for (int j = 0; j < k; j++) p.readShort();
                    }
                    default -> { return false; }
                }
            }
            return garder || p.getReadIndex() == p.getBytesLength();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void surPositions(HPacket p) {
        int idx = monIndex;
        for (int[] u : PlanteSuivi.lirePositions(p)) {
            if (u[0] != idx) continue;
            monX = u[1]; monY = u[2]; positionLe = System.currentTimeMillis();
        }
    }

    // ================================================================ automatisation

    private static volatile boolean actif = false, arret = false;
    private static volatile long enCoursId = 0;
    private static volatile long derniereProgressionLe = 0;
    /** Taches que l'automatisation n'a pas reussi : a faire soi-meme. */
    private static final Set<Long> echecs = ConcurrentHashMap.newKeySet();

    public static boolean actif() { return actif; }
    /** La progression de la tournee en cours (ou de la derniere), pour l'onglet. */
    private static volatile Avancement.Suivi suivi;
    static Avancement.Suivi suivi() { return suivi; }
    public static long enCours() { return enCoursId; }
    public static boolean echec(Tache t) { return echecs.contains(t.id); }

    public static void arreter() {
        if (!actif) return;
        arret = true;
    }

    // ---- choix des taches a faire (cases de l'onglet), retenu par genre de tache

    private static final String CLE_DECOCHEES = "taches.decochees";

    /** Taches decochees (par code : le meme genre de tache revient d'un jour a l'autre). */
    private static Set<String> decochees() {
        Set<String> r = new HashSet<>();
        for (String c : PREFS.get(CLE_DECOCHEES, "").split(",")) if (!c.isBlank()) r.add(c);
        return r;
    }

    public static boolean cochee(Tache t) { return !decochees().contains(t.code); }

    public static void cocher(Tache t, boolean oui) {
        Set<String> d = decochees();
        if (oui) d.remove(t.code); else d.add(t.code);
        PREFS.put(CLE_DECOCHEES, String.join(",", d));
    }

    /** Lance les taches cochees, l'une apres l'autre (sans effet si deja lance). */
    public static synchronized void lancer() { lancer(null); }

    /**
     * Lance seulement ces taches (ids) ; null : les taches cochees.
     * Sans effet si une tournee tourne deja.
     */
    public static synchronized void lancer(Set<Long> seulement) {
        if (actif) return;
        actif = true;
        arret = false;
        prevenir();
        Salle.tache("taches", () -> {
            try { travailler(seulement); }
            finally {
                actif = false; enCoursId = 0;
                danser(false);                                   // jamais de danse oubliee
                prevenir();
            }
        });
    }

    private static void travailler(Set<Long> seulement) {
        java.util.function.Predicate<Tache> voulue = t -> seulement != null ? seulement.contains(t.id) : cochee(t);
        Moteur gp = Salle.gp();
        if (gp == null) { InfoJeu.dire("Atelier pas encore connecté au jeu."); return; }
        installer();
        RechercheReseau.installer(gp);
        if (monId <= 0 && PlanteSuivi.monId <= 0) PlanteSuivi.demanderProfil();
        long debut = System.currentTimeMillis();
        demander();
        for (int i = 0; i < 40 && System.currentTimeMillis() - listeRecueLe > 6000; i++) {
            if (attendre(250)) return;
        }
        if (taches().isEmpty()) { InfoJeu.dire("Aucune tâche quotidienne reçue du jeu."); return; }

        int faites = 0, aMain = 0, aFaire = 0, k = 0;
        for (Tache t : taches()) {
            if (!voulue.test(t)) continue;
            Genre g = t.statut == EN_COURS ? t.action().genre() : null;
            if (g != null && g != Genre.DEPENSE && g != Genre.SOI_MEME) aFaire++;
        }
        Avancement.Suivi av = Avancement.debut("Tâches quotidiennes", TachesQuotidiennes::arreter).compte(0, aFaire);
        suivi = av;
        prevenir();
        List<String> depenses = new ArrayList<>(), soiMeme = new ArrayList<>();
        for (Tache t : taches()) {
            if (arret) break;
            if (t.statut != EN_COURS || !voulue.test(t)) continue;
            Action x = t.action();
            if (x.genre() == Genre.DEPENSE) { depenses.add(x.nom()); continue; }
            if (x.genre() == Genre.SOI_MEME) { soiMeme.add(x.nom()); continue; }
            enCoursId = t.id;
            echecs.remove(t.id);
            prevenir();
            av.texte(x.nom());                               // la barre dit la tache en cours : pas de message a chaque pas
            Journal.debug("tâches : « " + x.nom() + " » : " + x.consigne());
            boolean ok;
            try { ok = faire(t, x); }
            catch (Throwable e) { Journal.debug("tâches : « " + x.nom() + " » interrompue (" + e + ")."); ok = false; }
            enCoursId = 0;
            if (arret) break;
            av.compte(++k, aFaire);
            if (ok) { faites++; Journal.debug("tâches : « " + x.nom() + " » faite."); }
            else {
                echecs.add(t.id);
                aMain++;
                InfoJeu.dire("Tâche « " + x.nom() + " » pas réussie : à faire toi-même (" + x.consigne() + ")");
            }
            prevenir();
        }
        // les recompenses : elles ne coutent rien
        for (Tache t : taches()) if (!arret && t.statut == FAITE) reclamer(t);

        // on reste ou la derniere tache a mene : pas de retour a l'appart de depart (demande de Mawlie)
        String bilan;
        if (arret) bilan = "Tâches quotidiennes arrêtées.";
        else {
            bilan = Ui.accorder(faites + " tâche(s) faite(s)");
            if (aMain > 0) bilan += Ui.accorder(", " + aMain + " pas réussie(s)");
            bilan += ".";
            if (!depenses.isEmpty()) bilan += " Pas faites (elles font dépenser) : " + String.join(", ", depenses) + ".";
            if (!soiMeme.isEmpty()) bilan += " À faire toi-même : " + String.join(", ", soiMeme) + ".";
        }
        av.texte(null);
        av.fin(faites, aMain, arret);
        InfoJeu.dire(bilan);
        Journal.debug("tâches : fin en " + (System.currentTimeMillis() - debut) / 1000 + " s, " + bilan);
        Salle.sommeil(1500);
        demander();
    }

    /** Une tache, jusqu'a ce que le serveur la dise faite ; false si elle n'avance pas. */
    private static boolean faire(Tache t, Action x) {
        if (x.salle() > 0 && Salle.salleId() != x.salle() && !entrer(x.salle())) {
            InfoJeu.dire("Impossible d'entrer dans l'appart " + x.salle() + ".");
            return false;
        }
        if (x.salle() == 0 && !Salle.dansUneSalle() && !entrer(APPART_NESS)) return false;
        connaitreMoi();
        switch (x.genre()) {
            case DOUBLE_CLIC: return doubleClic(t, x);
            case ASSIS: case COUCHE: return installerSur(t, x);
            case MARCHER_SUR: return marcherSur(t, x);
            case DANSER_DUREE: {
                danser(true);
                boolean ok = attendreFaite(t, (x.nombre() + 90) * 1000L, () -> { });
                danser(false);
                return ok;
            }
            case DANSER_FOIS:
                return repeter(t, x.nombre() + 10, 3500, () -> { danser(true); Salle.sommeil(1500); danser(false); });
            case SALUER: return repeter(t, x.nombre() + 5, 3500, () -> geste("AvatarExpression", 1));
            case SAUTER: return repeter(t, 20, 3000, () -> geste("AvatarExpression", 6));
            case PANNEAU: return repeter(t, 5, 4000, () -> geste("Sign", x.nombre()));
            case PARLER: return parler(t, x);
            case EXPLORER: return explorer(t, x);
            case CARESSER: return caresser(t, x);
            default: return false;
        }
    }

    // ------------------------------------------------------------ gestes

    private static volatile boolean dansait = false;

    /** Danse (style 1) ou arret de la danse. */
    private static void danser(boolean on) {
        if (!on && !dansait) return;
        dansait = on;
        geste("Dance", on ? 1 : 0);
    }

    /** Un geste de l'avatar : Dance (style), AvatarExpression (1 signe, 6 saut), Sign (numero). */
    private static void geste(String nom, int v) {
        if (Salle.gp() == null || !Salle.dansUneSalle()) return;
        Salle.espacer();
        Salle.envoyer(RechercheReseau.paquet(S, new String[]{nom}, v));
        Salle.envoiFait();
    }

    /** Un geste repete jusqu'a ce que la tache soit faite (au plus fois, ecart ms entre deux). */
    private static boolean repeter(Tache t, int fois, long ecart, Runnable geste) {
        for (int i = 0; i < fois; i++) {
            if (arret) return false;
            if (t.statut != EN_COURS) return true;
            geste.run();
            if (attendre(ecart)) return false;
        }
        return attendreFaite(t, 5000, () -> { });
    }

    /** Attend que la tache soit faite ; pendant ce temps, entretien toutes les 20 s. */
    private static boolean attendreFaite(Tache t, long delai, Runnable entretien) {
        long debut = System.currentTimeMillis(), dernier = debut;
        while (System.currentTimeMillis() - debut < delai) {
            if (t.statut != EN_COURS) return true;
            if (attendre(500)) return false;
            if (System.currentTimeMillis() - dernier > 20_000) { dernier = System.currentTimeMillis(); entretien.run(); }
        }
        return t.statut != EN_COURS;
    }

    private static final String[] PHRASES = {
            "Coucou", "Bonne journée :)", "Je fais mes tâches du jour", "J'avance sur mon appart",
            "Il faut que je range ici", "Trop bien", "Hello", "On continue",
            "Encore quelques bulles", "C'est joli ici", "Presque fini", "Allez !", "Bonne soirée !"
    };

    /**
     * Un de mes apparts (bloc « my » du navigateur) : celui ou je suis s'il en
     * fait partie, sinon le premier ; 0 si aucun.
     */
    private static int monAppart() {
        RechercheReseau.actif = true;
        try {
            RechercheCalcul.Blocs b = RechercheReseau.chercher(Salle.gp(), "myworld_view", 8000);
            if (b == null) return 0;
            List<RechercheCalcul.Appart> l = new ArrayList<>();
            for (RechercheCalcul.Bloc k : b.blocs()) if ("my".equals(k.code())) l.addAll(k.apparts());
            Journal.debug("tâches : " + l.size() + " apparts à moi.");
            for (RechercheCalcul.Appart a : l) if (a.id() == Salle.salleId()) return a.id();
            return l.isEmpty() ? 0 : l.get(0).id();
        } catch (InterruptedException e) {
            return 0;
        } finally {
            RechercheReseau.actif = false;
        }
    }

    /** Des bulles espacees (6 s) : pas de flood. Dans un de mes apparts, pas chez les autres. */
    private static boolean parler(Tache t, Action x) {
        int moi = monAppart();
        if (moi == 0) { InfoJeu.dire("Aucun appart à toi trouvé : la tâche « Discutez ! » est à faire toi-même."); return false; }
        if (Salle.salleId() != moi && !entrer(moi)) { InfoJeu.dire("Impossible d'entrer dans ton appart pour discuter."); return false; }
        if (attendre(1500)) return false;
        int i = 0;
        for (int n = 0; n < x.nombre() + 10; n++) {
            if (arret) return false;
            if (t.statut != EN_COURS) return true;
            String phrase = PHRASES[i++ % PHRASES.length];
            Salle.espacer();
            Salle.envoyer(RechercheReseau.paquet(S, new String[]{"Chat"}, phrase, 0, -1));
            Salle.envoiFait();
            if (attendre(6000)) return false;
        }
        return attendreFaite(t, 5000, () -> { });
    }

    private static boolean caresser(Tache t, Action x) {
        if (animaux.isEmpty()) { PlanteSuivi.redemanderSalle(); attendre(2500); }
        if (animaux.isEmpty()) { InfoJeu.dire("Aucun animal dans l'appart."); return false; }
        List<Integer> l = new ArrayList<>(animaux);
        for (int n = 0; n < x.nombre() + 4; n++) {
            if (arret) return false;
            if (t.statut != EN_COURS) return true;
            Salle.espacer();
            Salle.envoyer(RechercheReseau.paquet(S, new String[]{"RespectPet"}, l.get(n % l.size())));
            Salle.envoiFait();
            if (attendre(3000)) return false;
        }
        return attendreFaite(t, 4000, () -> { });
    }

    /** Explorer des apparts : les plus populaires du navigateur, ouverts, quelques secondes chacun. */
    private static boolean explorer(Tache t, Action x) {
        Moteur gp = Salle.gp();
        List<RechercheCalcul.Appart> l = new ArrayList<>();
        Set<Integer> vus = new HashSet<>();
        RechercheReseau.actif = true;
        try {
            for (String code : new String[]{"popular", "hotel_view"}) {
                RechercheCalcul.Blocs b = RechercheReseau.chercher(gp, code, 8000);
                if (b == null) continue;
                for (RechercheCalcul.Appart a : b.apparts())
                    if (a.ouvert() && (a.maximum() <= 0 || a.presents() < a.maximum()) && vus.add(a.id())) l.add(a);
            }
        } catch (InterruptedException e) { return false; }
        finally { RechercheReseau.actif = false; }
        Journal.debug("tâches : " + l.size() + " apparts ouverts à explorer.");
        if (l.isEmpty()) return false;
        for (RechercheCalcul.Appart a : l) {
            if (arret) return false;
            if (t.statut != EN_COURS) return true;
            if (a.id() == Salle.salleId()) continue;
            entrer(a.id());
            if (attendre(4000)) return false;
        }
        return attendreFaite(t, 5000, () -> { });
    }

    // ------------------------------------------------------------ mobis

    /** Les mobis de la salle pour cette action : classe retenue, classes de la table, puis noms. */
    static List<HFloorItem> cibles(Tache t, Action x) {
        List<HFloorItem> sols = Salle.sols();
        List<HFloorItem> r = new ArrayList<>();
        String appris = appris(t.code);
        for (HFloorItem it : sols) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null && c.equals(appris)) r.add(it);
        }
        for (HFloorItem it : sols) {
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null && !r.contains(it) && classeVoulue(c, x.classes())) r.add(it);
        }
        if (r.isEmpty())
            for (String n : x.noms())
                for (HFloorItem it : sols)
                    if (!r.contains(it) && contientMot(simple(Salle.nom(it.getTypeId(), false)), simple(n))) r.add(it);
        // les plus proches d'abord
        int mx = monX, my = monY;
        if (mx >= 0) r.sort(Comparator.comparingInt(it -> distance(it.getTile().getX(), it.getTile().getY(), mx, my)));
        return r;
    }

    static boolean classeVoulue(String c, String[] classes) {
        for (String v : classes) {
            if (v.endsWith("*") ? c.startsWith(v.substring(0, v.length() - 1)) : c.equals(v)) return true;
        }
        return false;
    }

    /** Sans accents ni casse. */
    static String simple(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    /** Le morceau est-il dans le nom, en mots entiers ? */
    static boolean contientMot(String nom, String morceau) {
        if (morceau.isEmpty()) return false;
        int i = nom.indexOf(morceau);
        while (i >= 0) {
            boolean avant = i == 0 || !Character.isLetterOrDigit(nom.charAt(i - 1));
            int f = i + morceau.length();
            boolean apres = f >= nom.length() || !Character.isLetterOrDigit(nom.charAt(f));
            if (avant && apres) return true;
            i = nom.indexOf(morceau, i + 1);
        }
        return false;
    }

    private static int distance(int x1, int y1, int x2, int y2) { return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2)); }

    private static boolean doubleClic(Tache t, Action x) {
        List<HFloorItem> l = cibles(t, x);
        if (l.isEmpty()) {
            InfoJeu.dire("Pas de mobi pour « " + x.nom() + " » dans cet appart.");
            Journal.debug("tâches : aucun mobi pour " + t.code + " (classes " + String.join(",", x.classes())
                    + ", noms " + String.join(",", x.noms()) + ").");
            return false;
        }
        // un mobi apres l'autre (au plus 4) : marcher a cote, puis double-cliquer
        for (int k = 0; k < Math.min(4, l.size()); k++) {
            HFloorItem it = l.get(k);
            Journal.debug("tâches : « " + x.nom() + " » sur " + Salle.classe(it.getTypeId(), false) + " #" + it.getId()
                    + " en " + it.getTile().getX() + "," + it.getTile().getY() + ".");
            aCote(it);
            int faitsAvant = t.faits;
            for (int n = 0; n < x.nombre() + 3; n++) {
                if (arret) return false;
                if (t.statut != EN_COURS) return true;
                Salle.espacer();
                Salle.envoyer(RechercheReseau.paquet(S, new String[]{"UseFurniture"}, it.getId(), 0));
                Salle.envoiFait();
                if (attendre(x.nombre() > 1 ? 2500 : 4000)) return false;
                // trois essais sans aucun effet : ce mobi ne compte pas, au suivant
                if (n >= 2 && t.faits == faitsAvant && t.statut == EN_COURS) break;
            }
            if (t.statut != EN_COURS) return true;
            if (attendre(1500)) return false;
            if (t.statut != EN_COURS) return true;
        }
        return false;
    }

    /** S'asseoir ou se coucher sur le mobi, et y rester le temps voulu. */
    private static boolean installerSur(Tache t, Action x) {
        List<HFloorItem> l = cibles(t, x);
        if (l.isEmpty()) {
            // a defaut : n'importe quel mobi ou l'on s'assied (ou se couche)
            for (HFloorItem it : Salle.sols()) {
                Furnidata.Mobi d = Salle.details(Salle.classe(it.getTypeId(), false));
                if (d != null && (x.genre() == Genre.COUCHE ? d.canLayOn : d.canSitOn)) l.add(it);
            }
        }
        if (l.isEmpty()) { InfoJeu.dire("Pas de " + (x.genre() == Genre.COUCHE ? "lit" : "siège") + " dans cet appart."); return false; }
        for (int k = 0; k < Math.min(4, l.size()); k++) {
            HFloorItem it = l.get(k);
            int[] e = Salle.emprise(it);
            int cx = it.getTile().getX(), cy = it.getTile().getY();
            if (!marcherVers(cx, cy, e[0], e[1], 15_000)) continue;   // place prise : le suivant
            InfoJeu.dire("Installée pour « " + x.nom() + " » : environ " + Math.max(1, x.nombre() / 60) + " min.");
            final int fx = cx, fy = cy;
            boolean ok = attendreFaite(t, (x.nombre() + 120) * 1000L, () -> {
                // relevee (un autre joueur, un wired) : on se rassoit
                if (!sur(fx, fy, e[0], e[1])) marcherVers(fx, fy, e[0], e[1], 10_000);
            });
            return ok;
        }
        return false;
    }

    private static boolean marcherSur(Tache t, Action x) {
        List<HFloorItem> l = cibles(t, x);
        if (l.isEmpty()) { InfoJeu.dire("Pas de « " + x.noms()[0] + " » dans cet appart."); return false; }
        Set<Integer> faits = new HashSet<>();
        int pas = 0, max = x.nombre() * 3 + 10;
        while (pas++ < max) {
            if (arret) return false;
            if (t.statut != EN_COURS) return true;
            // la case la plus proche pas encore foulee (puis on recommence le tour)
            HFloorItem suivant = null;
            int mx = monX, my = monY, mieux = Integer.MAX_VALUE;
            for (HFloorItem it : l) {
                if (faits.contains(it.getId())) continue;
                if (it.getTile().getX() == mx && it.getTile().getY() == my) continue;
                int d = mx < 0 ? 0 : distance(it.getTile().getX(), it.getTile().getY(), mx, my);
                if (d < mieux) { mieux = d; suivant = it; }
            }
            if (suivant == null) { faits.clear(); continue; }
            faits.add(suivant.getId());
            marcherVers(suivant.getTile().getX(), suivant.getTile().getY(), 1, 1, 8000);
            if (attendre(700)) return false;
        }
        return attendreFaite(t, 4000, () -> { });
    }

    /** Marche a une case libre a cote du mobi (sans place libre : vers le mobi). */
    private static void aCote(HFloorItem it) {
        int[] e = Salle.emprise(it);
        int x0 = it.getTile().getX(), y0 = it.getTile().getY();
        if (monX >= 0 && monX >= x0 - 1 && monX <= x0 + e[0] && monY >= y0 - 1 && monY <= y0 + e[1]) return;   // deja a cote
        List<int[]> cases = new ArrayList<>();
        for (int x = x0 - 1; x <= x0 + e[0]; x++)
            for (int y = y0 - 1; y <= y0 + e[1]; y++) {
                boolean bord = x == x0 - 1 || x == x0 + e[0] || y == y0 - 1 || y == y0 + e[1];
                if (bord && libre(x, y)) cases.add(new int[]{x, y});
            }
        int mx = monX, my = monY;
        if (mx >= 0) cases.sort(Comparator.comparingInt(c -> distance(c[0], c[1], mx, my)));
        for (int i = 0; i < Math.min(3, cases.size()); i++)
            if (marcherVers(cases.get(i)[0], cases.get(i)[1], 1, 1, 10_000)) return;
        if (cases.isEmpty()) marcherVers(x0, y0, e[0], e[1], 8000);
    }

    /** Case du plan, sans mobi qui bloque (on peut marcher sur un tapis, s'asseoir...). */
    private static boolean libre(int x, int y) {
        if (Salle.hauteurSol(x, y) < 0) return false;
        for (HFloorItem it : Salle.sols()) {
            int[] e = Salle.emprise(it);
            int ix = it.getTile().getX(), iy = it.getTile().getY();
            if (x < ix || y < iy || x >= ix + e[0] || y >= iy + e[1]) continue;
            Furnidata.Mobi d = Salle.details(Salle.classe(it.getTypeId(), false));
            if (d == null || !d.canStandOn) return false;
        }
        return true;
    }

    private static boolean sur(int x, int y, int lx, int ly) {
        return monX >= x && monX < x + lx && monY >= y && monY < y + ly;
    }

    /** Envoie la marche et attend d'etre arrivee (sans position connue : on attend un peu). */
    private static boolean marcherVers(int x, int y, int lx, int ly, long delai) {
        Salle.espacer();
        Salle.envoyer(RechercheReseau.paquet(S, new String[]{"MoveAvatar"}, x, y));
        Salle.envoiFait();
        if (monIndex < 0) { attendre(Math.min(delai, 5000)); return true; }
        long debut = System.currentTimeMillis();
        while (System.currentTimeMillis() - debut < delai) {
            if (sur(x, y, lx, ly)) return true;
            if (attendre(250)) return false;
        }
        return sur(x, y, lx, ly);
    }

    /** Mon index dans la salle : sinon, on redemande la salle (le serveur renvoie Users). */
    private static void connaitreMoi() {
        if (monIndex >= 0) return;
        PlanteSuivi.redemanderSalle();
        for (int i = 0; i < 12 && monIndex < 0; i++) if (attendre(250)) return;
        if (monIndex < 0) Journal.debug("tâches : mon avatar n'est pas reconnu (id " + moi() + ", pseudo " + monPseudo() + ").");
    }

    // ------------------------------------------------------------ apparts

    /**
     * Entre dans un appart : RoomForward au jeu (il y va comme par un lien),
     * a defaut OpenFlatConnection au serveur. Attend qu'il soit charge.
     */
    private static boolean entrer(int id) {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        if (Salle.salleId() == id) return true;
        for (int essai = 0; essai < 2; essai++) {
            monIndex = -1;
            Salle.espacer();
            if (essai == 0) gp.sendToClient(RechercheReseau.paquet(C, new String[]{"RoomForward"}, id));
            else gp.sendToServer(RechercheReseau.paquet(S, new String[]{"OpenFlatConnection"}, id, "", -1));
            Salle.envoiFait();
            long debut = System.currentTimeMillis();
            while (System.currentTimeMillis() - debut < 12_000) {
                if (attendre(250)) return false;
                if (Salle.salleId() == id) {
                    attendre(1500);                              // les derniers mobis, la liste Users
                    return true;
                }
            }
            Journal.debug("tâches : entrée dans " + id + " sans effet (" + (essai == 0 ? "RoomForward" : "OpenFlatConnection") + ").");
        }
        return false;
    }

    // ------------------------------------------------------------ divers

    /** Dort ms (par tranches) ; vrai si l'arret a ete demande. */
    private static boolean attendre(long ms) {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            if (arret) return true;
            Salle.sommeil(Math.min(200, Math.max(1, fin - System.currentTimeMillis())));
        }
        return arret;
    }

    private static String hexa(HPacket p) {
        byte[] b = p.toBytes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < 600; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}

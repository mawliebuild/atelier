package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Pose par TAPIS DE DALLES, commune au collage d'appart, a Dupliquer / Coller
 * un calque, et a Deplacer / Pivoter / Miroir (mobis deja dans l'appart) :
 *
 *   1. D'ABORD toutes les dalles magiques sous les cases d'arrivee des mobis
 *      de sol (les plus grandes possibles, une seule hauteur de sol par dalle,
 *      comme OutilHauteur.couvrirCases), chacune reglee a la hauteur du sol de
 *      sa case (aucune surelevation) ;
 *   2. puis chaque mobi de sol, UN PAR UN, du bas vers le haut (z croissant) :
 *      chaque case de son emprise a sa dalle (une case restee nue en recoit
 *      une 1x1) ; POSE (PlaceObject / BC) ou deplacement (MoveObject) sur la
 *      dalle - le jeu l'accepte sur une case occupee, mais PAS si le haut de
 *      la pile est un mobi non empilable plus haut que la dalle
 *      (cant_set_item) : ses dalles montent alors juste au-dessus de la pile
 *      (Montee), puis reprennent leur hauteur ; un mobi qui refuse sa
 *      rotation (cant_set_item meme sur case vide) est d'abord repose dans une
 *      rotation equivalente, retenue pour sa classe (Rotations) -, attente qu'il apparaisse /
 *      arrive, puis son ETAT
 *      d'origine (UseFurniture), puis sa HAUTEUR exacte par @altitude, puis
 *      le suivant. Avant le premier reessai d'une pose refusee, les dalles
 *      sous ses cases redisent leur hauteur (le serveur recalcule la pile) ;
 *      un refus reste est diagnostique (Journal.debug) et sa raison donnee
 *      par le serveur (avertissement BC, erreur de pose) va au bilan. Une dalle, une pose ou un deplacement refuse est RENVOYE
 *      tout de suite (Salle.REESSAIS fois, apres Salle.pauseReessai) avant de
 *      passer au suivant ; une hauteur pas prise est renvoyee de meme, verifiee
 *      pendant la pose des mobis suivants. Seul ce qui reste refuse apres ses
 *      reessais compte pour le frein (Salle.signalerRefus) ;
 *   3. l'etape de l'appelant : muraux, reglages des wired... ;
 *   4. le ramassage des dalles posees a l'etape 1 (et des dalles arrivees en
 *      retard apres un refus apparent), jamais celles deja la : TOUJOURS fait,
 *      meme apres une erreur (finally) ; PickupObject(2, id, false) comme le
 *      client, aussi pour les dalles du BC (ids 0x7FFF....), une demande de
 *      confirmation du jeu (ObjectRemoveConfirm) acceptee ; le bilan dit
 *      celles qui restent. Pendant tout le tapis, ses dalles ne servent jamais
 *      de dalle de travail a une autre pose (PoseDalle.EXCLUES) ;
 *   5. la verification : un mobi manquant ou a une mauvaise hauteur a UNE
 *      nouvelle tentative (hauteur renvoyee, ou reprise a la dalle par mobi :
 *      PoseHybride.reprendre), puis le bilan unique (texte).
 * Sans @altitude (introuvable), les mobis en hauteur passent par la dalle par
 * mobi (PoseHybride), les autres gardent le tapis.
 *
 * Arreter : plus rien ne part, mais les dalles deja posees sont ramassees.
 * Salle quittee : arret net (rien a ramasser d'ici).
 *
 * Le deroulement (executer) ne parle qu'a l'interface Jeu : JeuSalle pour la
 * vraie salle, un faux serveur pour les tests (PoseTapisTest).
 */
final class PoseTapis {

    private PoseTapis() { }

    /** Ecart de hauteur au-dela duquel un mobi n'est pas a sa hauteur. */
    static final double TOLERANCE = PoseHybride.TOLERANCE;

    // ================================================================ pieces

    /** Un mobi de sol a mettre a sa place : nouveau (id = -1) ou deja dans la salle (deplacement). */
    static final class Piece {
        /** Mobi deja la (deplacement), -1 : nouveau, a poser depuis l'inventaire ou le BC. */
        final int id;
        final String classe, etat, nom;
        final int x, y, rot, rotOrigine, lx, ly;
        /** Altitude ABSOLUE voulue. */
        final double z;
        /** Cle libre de l'appelant (id dans la copie), -1 sans. */
        final int cle;
        /** Peut etre repris a la dalle (ramasse puis repose) : pas un wired deja regle. */
        final boolean reprenable;

        // ---- apres executer
        /** Id du mobi a sa case (-1 : pas obtenu). */
        int obtenu = -1;
        boolean introuvable, refuse, parDalle, sansTourner, hauteurOk, nonTente = true;
        /** Pose / deplace dans une autre rotation que la voulue (la voulue est refusee par ce mobi : Rotations). */
        boolean autreDirection;
        /**
         * Pas posable au Builders Club (absent du catalogue BC, offre de lot,
         * furnidata pas « bc », offre deja refusee) et pas pris dans
         * l'inventaire : ni reessai, ni dalle du tapis, ni reprise.
         */
        boolean pasAuBc;
        /** Avec pasAuBc : l'inventaire etait permis, mais il n'y en avait pas (assez). */
        boolean manqueInv;
        /** Raison d'un refus donnee par le serveur (avertissement BC, erreur de pose), null sans. */
        String raison;

        private Piece(int id, String classe, String etat, String nom, int x, int y, double z, int rot, int rotOrigine,
                      int lx, int ly, int cle, boolean reprenable) {
            this.id = id; this.classe = classe; this.etat = etat; this.nom = nom;
            this.x = x; this.y = y; this.z = z; this.rot = rot & 7; this.rotOrigine = rotOrigine & 7;
            this.lx = Math.max(1, lx); this.ly = Math.max(1, ly); this.cle = cle; this.reprenable = reprenable;
        }

        /** Un mobi a poser. lx, ly : emprise a l'arrivee (rotation comprise). */
        static Piece nouveau(String classe, String etat, String nom, int x, int y, double z, int rot, int lx, int ly, int cle) {
            return new Piece(-1, classe, etat, nom, x, y, z, rot, rot, lx, ly, cle, true);
        }

        /** Un mobi deja dans la salle, a deplacer. */
        static Piece deplace(int id, String nom, int x, int y, double z, int rot, int rotOrigine,
                             int lx, int ly, boolean reprenable) {
            return new Piece(id, null, null, nom, x, y, z, rot, rotOrigine, lx, ly, -1, reprenable);
        }

        boolean nouveau() { return id < 0; }

        @Override public String toString() {
            return (nouveau() ? classe : "#" + id) + "->(" + x + "," + y + " z" + z + " r" + rot + ")";
        }
    }

    /** Emprise d'un mobi de sol a l'arrivee : furnidata, axes echanges en rotation 2 ou 6. */
    static int[] emprise(String classe, int rot) {
        Furnidata.Mobi d = classe == null ? null : Salle.details(classe);
        int lx = d == null ? 1 : Math.max(1, d.xDim), ly = d == null ? 1 : Math.max(1, d.yDim);
        int r = rot & 7;
        return r == 2 || r == 6 ? new int[]{ly, lx} : new int[]{lx, ly};
    }

    // ================================================================ le jeu

    /** Ce que le deroulement demande au jeu. Chaque envoi attend son tour (Salle.espacer). */
    interface Jeu {
        /** Arreter demande (les dalles seront quand meme ramassees). */
        boolean arret();
        /** Salle quittee : on s'arrete net. */
        boolean sortie();
        /** Hauteur du sol nu (-1 hors plan). */
        int sol(int x, int y);
        /** Tailles de dalles disponibles, de la plus grande a la plus petite. */
        List<DalleMagique> dalles();
        /** Emprise {lx, ly} d'une dalle (rotation 0). */
        int[] emprise(DalleMagique t);
        /** Pose une dalle et attend qu'elle apparaisse : son id, -1 refusee, -2 pas de source (taille sautee). */
        int poserDalle(DalleMagique t, int x, int y, int rot);
        /** Regle la hauteur d'une dalle (SetCustomStackingHeight). */
        void reglerDalle(int id, double h);
        /** Altitude d'un mobi, NaN s'il n'est pas (plus) dans la salle. */
        double z(int id);
        /** {x, y, rotation} d'un mobi de sol, null s'il n'est pas dans la salle. */
        int[] position(int id);
        /**
         * Pose un nouveau mobi et attend qu'il apparaisse : son id, -1 refuse,
         * -2 introuvable dans la source, -3 pas posable au Builders Club (definitif).
         */
        int poser(Piece p, int rot);
        /** MoveObject et attente : true s'il est arrive a (x, y) avec cette rotation. */
        boolean deplacer(int id, int x, int y, int rot);
        /** Cherche @altitude une fois (avant le tapis, s'il y a des mobis en hauteur). */
        boolean preparerAltitude();
        /** @altitude utilisable maintenant (pas introuvable). */
        boolean altitudePossible();
        /** Ecrit l'altitude (le premier mobi verifie la variable). */
        void altitude(int id, double z);
        /** Laisse arriver les dernieres altitudes envoyees (suivi court). */
        void attendreHauteurs(Map<Integer, Double> voulu);
        /** Etats voulus (couleur, allume...) : rend le nombre de mobis restes dans un autre etat. */
        int etats(Map<Integer, String> voulus);
        /** Ramasse ces dalles (2 passes) : rend le nombre de dalles encore la. */
        int ramasserDalles(List<Integer> ids, BiConsumer<Integer, Integer> progres);
        /** Reprise a la dalle par mobi : piece -> nouvel id (absente : pas reprise). */
        Map<Piece, Integer> reprendre(List<Piece> pieces);
        /** Ids des vraies dalles magiques de la salle (pour ramasser aussi celles arrivees en retard). */
        default Set<Integer> dallesPresentes() { return Set.of(); }
        /** Courte attente avant de renvoyer une action refusee. */
        default void pauseReessai() { Salle.pauseReessai(); }
        /** Une action restee refusee apres ses reessais (frein). */
        default void refus(String quoi) { Salle.signalerRefus(quoi); }
        /** Une action prise par le jeu, au besoin apres reessai (frein). */
        default void reussite() { Salle.signalerReussite(); }
        /**
         * Avant de renvoyer une pose / un deplacement refuse : redit leur
         * hauteur aux dalles du tapis sous ces cases (SetCustomStackingHeight,
         * le serveur recalcule la hauteur de pile de leurs cases). Rend le
         * nombre de dalles reglees.
         */
        default int raviverDalles(Collection<Integer> dalles) {
            for (int id : dalles) {
                double z = z(id);
                if (!Double.isNaN(z)) reglerDalle(id, z);
            }
            return dalles.size();
        }
        /** Diagnostic d'un refus reste apres ses reessais (Journal.debug), depuis t (ms). */
        default void diagnostiquerRefus(Piece p, Collection<Integer> dalles, long t) { }
        /** La raison du refus donnee par le serveur depuis t (avertissement BC, erreur de pose), ou null. */
        default String raisonRefus(Piece p, long t) { return null; }
        /**
         * Avant le tapis, pour chaque nouveau mobi dans l'ordre : peut-il venir
         * de la source ? null oui (l'exemplaire de l'inventaire est reserve),
         * INTROUVABLE, ou PAS_AU_BC (ni inventaire possible, ni offre BC).
         */
        default String horsSource(Piece p) { return null; }
        /**
         * Une pose restee refusee apres ses reessais : si elle partait du BC,
         * son offre n'est plus envoyee pendant l'action (OffresBc.refusee).
         * true si le mobi n'a donc plus de source (compte « pas au BC », sans reprise).
         */
        default boolean offreRefusee(Piece p) { return false; }
        /**
         * La case est-elle « bloquee » : le haut de sa pile est un mobi NON
         * empilable plus haut que sa dalle (HeightMap du serveur, drapeau
         * 0x4000) ? Le serveur y refuse alors la pose sur la dalle (cant_set_item).
         */
        default boolean caseBloquee(int x, int y) { return false; }
        /**
         * Le haut de la pile sur l'emprise d'arrivee de la piece (z + hauteur
         * propre du plus haut mobi), sans les dalles magiques ni ces ids ; NaN
         * sans mobi.
         */
        default double hautPile(Piece p, Collection<Integer> exclus) { return Double.NaN; }
        /** Direction par defaut du mobi (furnidata), -1 inconnue. */
        default int directionParDefaut(Piece p) { return -1; }
    }

    /** La raison d'un refus est-elle « cant_set_item » (pile non empilable) ? Logique pure. */
    static boolean refusEmpilement(String raison) { return raison != null && raison.contains("cant_set_item"); }

    /** Hauteur de dalle juste au-dessus de ce haut de pile (au centieme, comme SetCustomStackingHeight). Logique pure. */
    static double auDessus(double haut) { return Math.ceil((Math.max(0, haut) + 0.01) * 100 - 1e-6) / 100.0; }

    static final String INTROUVABLE = "introuvable", PAS_AU_BC = "pas au BC", MANQUE = "manque";

    /** Progression : « Dalles : 12/40 », « Mobis : 120/483 », « Ramassage des dalles… ». */
    interface Suivi {
        void progres(int fait, int total, String texte);
    }

    // ================================================================ bilan

    /** Ce que rend executer. */
    static final class Bilan {
        final List<Piece> pieces;
        /** Dalles posees par l'etape 1 (ids). */
        final List<Integer> dalles = new ArrayList<>();
        int dallesPrevues, dallesRestees, dallesReglees, etatsFaux, dallesLaissees;
        /** Actions passees apres reessai ; actions restees refusees apres leurs reessais (debug). */
        int apresReessai, refusApresReessai;
        /** Dalles ramassees en plus de celles du tapis (arrivees en retard, apres un refus apparent). */
        int dallesTardives;
        boolean arrete, sortie, sansAltitude;
        /** Pourquoi le tapis est incomplet (dalles refusees...), null sinon. */
        String raisonDalles;
        /** Case (OutilHauteur.cle) -> dalle du tapis qui la couvre. */
        final Map<Long, Integer> couverture = new HashMap<>();
        /** Dalles 1x1 ajoutees sous une case restee sans dalle ; dalles re-reglees avant un reessai. */
        int dallesAjoutees, dallesRavivees;
        /**
         * Mobis poses sur une dalle montee au-dessus d'un mobi non empilable
         * (puis descendus par @altitude) ; refuses malgre la dalle montee ;
         * dont reperes seulement apres un refus cant_set_item.
         */
        int dallesMontees, monteesRefusees, monteesApresRefus;
        Bilan(List<Piece> pieces) { this.pieces = pieces; }

        /** Mobis obtenus dans une autre rotation que la voulue (refusee par le mobi). */
        int autresDirections() { int n = 0; for (Piece p : pieces) if (p.obtenu >= 0 && p.autreDirection) n++; return n; }

        int reussis() { int n = 0; for (Piece p : pieces) if (p.obtenu >= 0 && p.hauteurOk) n++; return n; }
        int obtenus() { int n = 0; for (Piece p : pieces) if (p.obtenu >= 0) n++; return n; }
        int hauteursFausses() { int n = 0; for (Piece p : pieces) if (p.obtenu >= 0 && !p.hauteurOk) n++; return n; }
        int refuses() { int n = 0; for (Piece p : pieces) if (refuse(p)) n++; return n; }
        int pasAuBc() { int n = 0; for (Piece p : pieces) if (p.obtenu < 0 && p.pasAuBc && !p.introuvable) n++; return n; }
        private static boolean refuse(Piece p) { return p.obtenu < 0 && !p.introuvable && !p.pasAuBc && !p.nonTente; }
        List<String> nomsPasAuBc() {
            List<String> r = new ArrayList<>();
            for (Piece p : pieces) if (p.obtenu < 0 && p.pasAuBc && !p.manqueInv && !p.introuvable) r.add(p.nom);
            return r;
        }
        /** Classes manquantes (ni assez en inventaire, ni au BC) : classe -> nombre. */
        Map<String, Integer> manquants() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Piece p : pieces) if (p.obtenu < 0 && p.manqueInv && !p.introuvable) m.merge(p.classe, 1, Integer::sum);
            return m;
        }
        int introuvables() { int n = 0; for (Piece p : pieces) if (p.obtenu < 0 && p.introuvable) n++; return n; }
        int parDalle() { int n = 0; for (Piece p : pieces) if (p.obtenu >= 0 && p.parDalle) n++; return n; }
        List<String> nomsRefuses() {
            List<String> r = new ArrayList<>();
            for (Piece p : pieces) if (refuse(p)) r.add(p.nom);
            return r;
        }
        /** Cle de la piece -> id obtenu. */
        Map<Integer, Integer> cles() {
            Map<Integer, Integer> m = new LinkedHashMap<>();
            for (Piece p : pieces) if (p.cle != -1 && p.obtenu >= 0) m.put(p.cle, p.obtenu);
            return m;
        }

        /** Le bilan en une phrase (voir texte). */
        String texte(String participe) {
            String t = PoseTapis.texte(participe, pieces.size(), reussis(), hauteursFausses(), nomsRefuses(),
                    introuvables(), parDalle(), arrete, dallesRestees);
            t = avecDirections(t, autresDirections(), participe);
            return avecRaisons(avecManquants(avecPasAuBc(avecLaissees(t, dallesLaissees), nomsPasAuBc()), manquants()), raisons());
        }

        /** Raisons donnees par le serveur pour les mobis restes refuses -> nombre de mobis. */
        Map<String, Integer> raisons() {
            Map<String, Integer> r = new LinkedHashMap<>();
            for (Piece p : pieces)
                if (refuse(p) && p.raison != null) r.merge(p.raison, 1, Integer::sum);
            return r;
        }

        /** Pour Journal.debug : dalles posees / ramassees. */
        String detailDalles() {
            return "tapis : " + dalles.size() + " dalle(s) posée(s) sur " + dallesPrevues + " prévue(s)"
                    + (dallesTardives > 0 ? " (dont " + dallesTardives + " arrivée(s) en retard)" : "")
                    + (dallesAjoutees > 0 ? " (dont " + dallesAjoutees + " 1x1 ajoutée(s) sous une case nue)" : "")
                    + (dallesRavivees > 0 ? ", " + dallesRavivees + " hauteur(s) de dalle redite(s) avant un réessai" : "")
                    + (dallesMontees + monteesRefusees > 0 ? ", " + PoseOutils.nombre(dallesMontees, "mobi posé", "mobis posés")
                            + " sur une dalle montée au-dessus d'un mobi non empilable"
                            + (monteesApresRefus > 0 ? " (" + monteesApresRefus + " repéré" + (monteesApresRefus > 1 ? "s" : "")
                                    + " après un refus cant_set_item)" : "")
                            + (monteesRefusees > 0 ? ", " + monteesRefusees + " refusé" + (monteesRefusees > 1 ? "s" : "")
                                    + " malgré la dalle montée" : "") : "") + ", "
                    + dallesReglees + " réglée(s) au sol" + (dalles.isEmpty() ? "" : dallesReglees == 0
                            ? " (posées directement au niveau du sol : rien à régler)" : " (les autres déjà au niveau du sol)") + ", " + (dallesLaissees > 0 ? dallesLaissees + " laissée(s) en place" : (dalles.size() - dallesRestees) + " ramassée(s)")
                    + (dallesRestees > 0 ? ", " + dallesRestees + " restée(s) dans la salle" : "")
                    + (raisonDalles != null ? " (" + raisonDalles + ")" : "") + ".";
        }

        /** Pour Journal.debug : les reessais. */
        String detailReessais() {
            return "réessais : " + apresReessai + " action(s) passée(s) après réessai, "
                    + refusApresReessai + " restée(s) refusée(s) après réessai.";
        }
    }

    /**
     * Le bilan unique (logique pure) : « 482 mobis posés à leur hauteur, 1 refusé (Trône). »
     * Les mots « refusé », « pas pu être », « introuvable » en font une erreur (Journal.genre).
     * @param participe « posé », « déplacé », « pivoté », « retourné »
     */
    static String texte(String participe, int voulus, int reussis, int hauteursFausses, List<String> refuses,
                        int introuvables, int parDalle, boolean arrete) {
        return texte(participe, voulus, reussis, hauteursFausses, refuses, introuvables, parDalle, arrete, 0);
    }

    /** Ajoute au bilan les mobis mis dans une autre rotation (logique pure) : « …, 3 posés avec une autre direction. » */
    static String avecDirections(String t, int n, String participe) {
        if (n <= 0) return t;
        String base = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
        return base + ", " + n + " " + participe + (n > 1 ? "s" : "") + " avec une autre direction.";
    }

    /** Ajoute au bilan les dalles du tapis laissees sous les mobis (GARDER_DALLES). */
    static String avecLaissees(String t, int n) {
        if (n <= 0) return t;
        String base = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
        return base + (n > 1 ? " ; " + n + " dalles magiques laissées sous les mobis." : " ; 1 dalle magique laissée sous les mobis.");
    }

    /** Meme bilan ; dallesRestees > 0 : les dalles du tapis pas ramassees sont dites (a ramasser a la main). */
    static String texte(String participe, int voulus, int reussis, int hauteursFausses, List<String> refuses,
                        int introuvables, int parDalle, boolean arrete, int dallesRestees) {
        String d = dallesRestees <= 0 ? ""
                : dallesRestees > 1 ? dallesRestees + " dalles magiques n'ont pas pu être ramassées (ramasse-les à la main)"
                : "1 dalle magique n'a pas pu être ramassée (ramasse-la à la main)";
        StringBuilder b = new StringBuilder();
        if (arrete) b.append("Arrêté : ");
        if (reussis == 0 && hauteursFausses == 0 && refuses.isEmpty() && introuvables == 0) {
            b.append(arrete ? "aucun mobi " + participe : "Aucun mobi " + participe);
            if (!d.isEmpty()) b.append(", ").append(d);
            return b.append(".").toString();
        }
        b.append(reussis).append(reussis > 1 ? " mobis " + participe + "s à leur hauteur" : " mobi " + participe + " à sa hauteur");
        if (arrete && voulus > reussis) b.append(" sur ").append(voulus);
        if (parDalle > 0) b.append(" (dont ").append(parDalle).append(" repris à la dalle)");
        List<String> soucis = new ArrayList<>();
        if (hauteursFausses > 0)
            soucis.add(hauteursFausses + (hauteursFausses > 1 ? " n'ont pas pu être mis à la bonne hauteur"
                    : " n'a pas pu être mis à la bonne hauteur"));
        if (!refuses.isEmpty()) {
            int n = refuses.size();
            StringBuilder s = new StringBuilder(n + (n > 1 ? " refusés" : " refusé"));
            LinkedHashSet<String> noms = new LinkedHashSet<>();
            for (String x : refuses) if (x != null && !x.isBlank()) noms.add(x);
            if (!noms.isEmpty()) {
                List<String> l = new ArrayList<>(noms);
                s.append(" (").append(String.join(", ", l.subList(0, Math.min(3, l.size()))))
                        .append(l.size() > 3 ? "…" : "").append(")");
            }
            soucis.add(s.toString());
        }
        if (introuvables > 0)
            soucis.add(introuvables + (introuvables > 1 ? " introuvables" : " introuvable") + " dans la source choisie");
        if (!d.isEmpty()) soucis.add(d);
        if (!soucis.isEmpty()) b.append(", ").append(String.join(", ", soucis));
        return b.append(".").toString();
    }

    /**
     * Le bilan suivi des mobis pas posables au Builders Club (logique pure),
     * une seule fois : « …, 26 pas au Builders Club (A, B, C…) : impossible
     * de les poser depuis le BC. »
     */
    /**
     * Le bilan suivi des mobis qui manquent (logique pure) : « …, 41 manquants :
     * pas assez dans ton inventaire et pas au BC (16 Herbe enneigée, 13 ……). »
     */
    static String avecManquants(String texte, Map<String, Integer> parClasse) {
        if (parClasse == null || parClasse.isEmpty()) return texte;
        int n = 0;
        for (int k : parClasse.values()) n += k;
        // regroupes par nom affiche : deux classes du meme nom (« Champignons infectieux ») font une ligne
        Map<String, Integer> parNom = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : parClasse.entrySet()) {
            String nom = null;
            try { Furnidata.Mobi d = Salle.details(e.getKey()); if (d != null) nom = d.name; } catch (Throwable ignored) { }
            parNom.merge(nom == null || nom.isBlank() ? e.getKey() : nom, e.getValue(), Integer::sum);
        }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(parNom.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> v = new ArrayList<>();
        for (Map.Entry<String, Integer> e : l.subList(0, Math.min(4, l.size()))) v.add(e.getValue() + " " + e.getKey());
        String t = texte.endsWith(".") ? texte.substring(0, texte.length() - 1) : texte;
        return t + ", " + n + (n > 1 ? " manquants" : " manquant") + " : pas assez dans ton inventaire et pas au BC ("
                + String.join(", ", v) + (l.size() > 4 ? "…" : "") + ").";
    }

    static String avecPasAuBc(String texte, List<String> noms) {
        if (noms == null || noms.isEmpty()) return texte;
        int n = noms.size();
        LinkedHashSet<String> l = new LinkedHashSet<>();
        for (String x : noms) if (x != null && !x.isBlank()) l.add(x);
        List<String> v = new ArrayList<>(l);
        String t = texte.endsWith(".") ? texte.substring(0, texte.length() - 1) : texte;
        return t + ", " + n + " pas au Builders Club"
                + (v.isEmpty() ? "" : " (" + String.join(", ", v.subList(0, Math.min(3, v.size()))) + (v.size() > 3 ? "…" : "") + ")")
                + " : impossible de " + (n > 1 ? "les" : "le") + " poser depuis le BC.";
    }

    /**
     * Le bilan suivi des raisons de refus donnees par le serveur (logique
     * pure) : « …, 26 refusés (A, B…) ; raison donnée par le jeu : X (26). »
     */
    static String avecRaisons(String texte, Map<String, Integer> raisons) {
        if (raisons == null || raisons.isEmpty()) return texte;
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : raisons.entrySet()) l.add(e.getKey() + " (" + e.getValue() + ")");
        String t = texte.endsWith(".") ? texte.substring(0, texte.length() - 1) : texte;
        return t + " ; " + (l.size() > 1 ? "raisons données par le jeu : " : "raison donnée par le jeu : ")
                + String.join(", ", l.subList(0, Math.min(3, l.size()))) + (l.size() > 3 ? "…" : "") + ".";
    }

    // ============================================================ plan des dalles

    /** Une dalle du plan : coin, emprise, rotation, taille. */
    static final class Dalle {
        final DalleMagique t;
        final int x, y, lx, ly, rot;
        Dalle(DalleMagique t, int x, int y, int lx, int ly, int rot) {
            this.t = t; this.x = x; this.y = y; this.lx = lx; this.ly = ly; this.rot = rot;
        }
        @Override public String toString() { return lx + "x" + ly + "@" + x + "," + y; }
    }

    /** Les cases d'arrivee des pieces (emprise comprise). */
    static Set<Long> cases(Collection<Piece> pieces) {
        Set<Long> r = new LinkedHashSet<>();
        for (Piece p : pieces)
            for (int i = 0; i < p.lx; i++)
                for (int j = 0; j < p.ly; j++) r.add(OutilHauteur.cle(p.x + i, p.y + j));
        return r;
    }

    /** Grille des hauteurs de sol, -1 hors des cases a couvrir (ou hors plan). */
    static int[][] grille(Set<Long> cases, Jeu jeu) {
        int n = 1, m = 1;
        for (long c : cases) { n = Math.max(n, (int) (c >> 32) + 1); m = Math.max(m, (int) c + 1); }
        int[][] g = new int[n][m];
        for (int[] l : g) Arrays.fill(l, -1);
        for (long c : cases) {
            int x = (int) (c >> 32), y = (int) c;
            if (x >= 0 && y >= 0) g[x][y] = jeu.sol(x, y);
        }
        return g;
    }

    /** Les formes essayees, de la plus grande a la plus petite (la 1x2 aussi couchee) : {lx, ly, rot}. */
    private static List<Object[]> formes(Jeu jeu) {
        List<Object[]> f = new ArrayList<>();
        for (DalleMagique t : jeu.dalles()) {
            int[] e = jeu.emprise(t);
            f.add(new Object[]{t, e[0], e[1], 0});
            if (e[0] != e[1]) f.add(new Object[]{t, e[1], e[0], 2});
        }
        return f;
    }

    /**
     * Une grande dalle ne sert que si elle couvre assez de cases nues (plus
     * qu'une 1x1), comme OutilHauteur.couvrirCases. Logique pure.
     */
    static boolean vautLaPeine(int lx, int ly, int nues) {
        return lx * ly == 1 ? nues > 0 : nues >= Math.max(2, lx * ly / 2);
    }

    /**
     * Le plan des dalles si le jeu acceptait tout (logique pure) : sert au
     * total de la progression et aux tests. Meme choix que le vrai tapis.
     */
    static List<Dalle> planDalles(int[][] sol, List<Object[]> formes) {
        List<Dalle> r = new ArrayList<>();
        Set<Long> prises = new HashSet<>(), vide = Set.of();
        for (Object[] f : formes) {
            int lx = (int) f[1], ly = (int) f[2];
            while (true) {
                int[] c = OutilHauteur.meilleurCoin(sol, lx, ly, prises, vide, vide);
                if (c == null || !vautLaPeine(lx, ly, c[2])) break;
                r.add(new Dalle((DalleMagique) f[0], c[0], c[1], lx, ly, (int) f[3]));
                for (int i = 0; i < lx; i++) for (int j = 0; j < ly; j++) prises.add(OutilHauteur.cle(c[0] + i, c[1] + j));
            }
        }
        return r;
    }

    static List<Dalle> planDalles(Set<Long> cases, Jeu jeu) { return planDalles(grille(cases, jeu), formes(jeu)); }

    /** Ordre de pose : du bas vers le haut (z), puis ligne par ligne. Logique pure. */
    static List<Piece> ordre(Collection<Piece> pieces) {
        List<Piece> l = new ArrayList<>(pieces);
        l.sort(Comparator.comparingDouble((Piece p) -> p.z).thenComparingInt(p -> p.y).thenComparingInt(p -> p.x)
                .thenComparingInt(p -> p.id));
        return l;
    }

    /** La piece est-elle au-dessus du sol nu de sa case (il lui faut @altitude) ? */
    static boolean enHauteur(Piece p, Jeu jeu) {
        return Math.abs(p.z - Math.max(0, jeu.sol(p.x, p.y))) > 0.01;
    }

    // ============================================================ deroulement

    /**
     * Le tapis complet. Hors fil JavaFX ; ne regroupe pas l'historique (a
     * l'appelant : Historique.grouper). Ne lance aucune exception du jeu.
     * @param etape3 entre les mobis et le ramassage (muraux, wired) ; pas
     *               lancee si arret ou salle quittee ; peut etre null
     */
    /** Poses par tapis en cours (les calques masques attendent la fin pour se reappliquer). */
    private static final java.util.concurrent.atomic.AtomicInteger EN_COURS = new java.util.concurrent.atomic.AtomicInteger();
    static boolean enCours() { return EN_COURS.get() > 0; }

    static Bilan executer(List<Piece> pieces, Jeu jeu, Suivi suivi, Runnable etape3) {
        Bilan b = new Bilan(pieces);
        EN_COURS.incrementAndGet();
        try {
            return executer0(b, pieces, jeu, suivi, etape3);
        } finally {
            PoseDalle.EXCLUES.removeAll(b.dalles);
            EN_COURS.decrementAndGet();
        }
    }

    private static Bilan executer0(Bilan b, List<Piece> pieces, Jeu jeu, Suivi suivi, Runnable etape3) {
        // les dalles deja la (celles de l'utilisatrice) : jamais ramassees
        Set<Integer> dallesAvant = new HashSet<>(jeu.dallesPresentes());
        // 0. @altitude : cherchee une fois s'il y a des mobis en hauteur
        boolean haut = false;
        for (Piece p : pieces) if (enHauteur(p, jeu)) { haut = true; break; }
        boolean alt = !haut || jeu.preparerAltitude();
        b.sansAltitude = !alt;
        List<Piece> tapis = new ArrayList<>(), directs = new ArrayList<>();
        for (Piece p : pieces) {
            if (!alt && p.reprenable && enHauteur(p, jeu)) directs.add(p);
            else tapis.add(p);
        }
        // les mobis deja a leur place (deplacement nul) n'ont pas besoin de dalle ; les
        // nouveaux sans source (pas au BC, introuvables) n'ont ni dalle, ni essai, ni reprise
        List<Piece> aBouger = new ArrayList<>();
        for (Piece p : tapis) {
            if (p.nouveau()) {
                String hors = jeu.horsSource(p);
                if (hors != null) {
                    p.nonTente = false;
                    if (MANQUE.equals(hors)) { p.pasAuBc = true; p.manqueInv = true; }
                    else if (PAS_AU_BC.equals(hors)) p.pasAuBc = true; else p.introuvable = true;
                    continue;
                }
            }
            if (!p.nouveau()) {
                int[] pos = jeu.position(p.id);
                if (pos != null && pos[0] == p.x && pos[1] == p.y && pos[2] == p.rot) { p.obtenu = p.id; p.nonTente = false; continue; }
            }
            aBouger.add(p);
        }

        try {
            // 1. le tapis de dalles
            if (!aBouger.isEmpty() && !jeu.arret() && !jeu.sortie()) poserTapis(b, cases(aBouger), jeu, suivi);

            // 2. les mobis, un par un, du bas vers le haut ; chacun reessaye tout de suite s'il est refuse
            List<Piece> ordre = ordre(aBouger);
            int fait = 0;
            Map<Integer, String> etats = new LinkedHashMap<>();
            Map<Integer, Double> hauteurs = new LinkedHashMap<>();       // id -> z voulu : @altitude en route
            Montee montee = new Montee();
            Rotations rotations = new Rotations();
            try {
            for (Piece p : ordre) {
                if (jeu.arret() || jeu.sortie()) break;
                fait++;
                // @altitude perdue en route : les mobis en hauteur passent par la dalle par mobi
                if (!jeu.altitudePossible() && p.reprenable && enHauteur(p, jeu)) {
                    b.sansAltitude = true;
                    directs.add(p);
                    suivi.progres(fait, ordre.size(), "Mobis : " + fait + "/" + ordre.size());
                    continue;
                }
                p.nonTente = false;
                // chaque case de son emprise a sa dalle (une case restee nue en recoit une 1x1)
                couvrir(p, jeu, b);
                // un mobi non empilable au-dessus de la dalle sur une case de son emprise : le serveur
                // refuserait la pose (cant_set_item) ; ses dalles montent au-dessus de la pile
                montee.avant(p, jeu, b);
                // 1. POSER (ou deplacer)
                if (p.nouveau()) poserNouveau(p, jeu, b, montee, rotations);
                else deplacer(p, jeu, b, montee, rotations);
                montee.apres(p, jeu, b);
                if (p.obtenu >= 0) {
                    // 2. son ETAT d'origine (couleur, allume...), tout de suite, avant sa hauteur
                    // (jamais une boite wired : l'utiliser ouvre sa fenetre dans le jeu)
                    if (p.nouveau() && p.etat != null && (p.classe == null || !p.classe.startsWith("wf_"))
                            && !jeu.arret() && !jeu.sortie()) {
                        int faux = jeu.etats(Map.of(p.obtenu, p.etat));
                        if (faux > 0) etats.put(p.obtenu, p.etat);
                    }
                }
                // les hauteurs envoyees aux mobis precedents ont eu le temps d'arriver : verifiees ici
                suivreHauteurs(hauteurs, jeu, b);
                if (p.obtenu >= 0 && !jeu.arret() && !jeu.sortie()) {
                    // 3. MONTER a son altitude (@altitude), puis le mobi suivant
                    double z = jeu.z(p.obtenu);
                    if (!Double.isNaN(z) && Math.abs(z - p.z) > TOLERANCE && jeu.altitudePossible()) {
                        jeu.altitude(p.obtenu, p.z);
                        hauteurs.put(p.obtenu, p.z);
                    }
                }
                suivi.progres(fait, ordre.size(), "Mobis : " + fait + "/" + ordre.size());
            }
            } finally {
                // les dalles montees reprennent leur hauteur d'avant (elles restent dans l'appart)
                if (!jeu.sortie()) {
                    try { montee.redescendre(Set.of(), jeu); }
                    catch (Throwable t) { Journal.debug("tapis : dalles montées pas redescendues : " + t); }
                }
            }
            // les dernieres hauteurs : laissees arriver, reessayees au besoin
            if (!hauteurs.isEmpty() && !jeu.arret() && !jeu.sortie()) {
                jeu.attendreHauteurs(new LinkedHashMap<>(hauteurs));
                suivreHauteurs(hauteurs, jeu, b);
            }
            // les etats restes faux juste apres leur pose : une derniere tentative, puis le compte
            if (!etats.isEmpty() && !jeu.arret() && !jeu.sortie()) b.etatsFaux = jeu.etats(etats);

            // 3. l'etape de l'appelant (muraux, wired) : son erreur n'empeche pas le ramassage
            if (etape3 != null && !jeu.arret() && !jeu.sortie()) {
                try { etape3.run(); }
                catch (Throwable t) { Journal.debug("tapis : étape des muraux / wired interrompue : " + t); }
            }
        } finally {
            // 4. le ramassage des dalles du tapis : TOUJOURS (meme apres Arreter ou une erreur)
            ramasserTapis(b, dallesAvant, jeu, suivi);
        }

        // 5. verification, une seule nouvelle tentative par mobi
        b.arrete = jeu.arret();
        b.sortie = jeu.sortie();
        if (!b.sortie) verifier(b, tapis, directs, jeu, suivi);
        return b;
    }

    /**
     * Etape 4 : les dalles posees par le tapis, plus les dalles magiques
     * apparues depuis le debut et qui ne sont ni a l'utilisatrice (deja la) ni
     * des mobis de la copie : une dalle arrivee apres son attente (comptee
     * refusee, donc reessayee) serait sinon laissee dans l'appart.
     */
    /** Demande de l'utilisatrice : les dalles du tapis restent dans l'appart (sous les mobis). Change par les tests seulement. */
    static volatile boolean GARDER_DALLES = true;

    private static void ramasserTapis(Bilan b, Set<Integer> dallesAvant, Jeu jeu, Suivi suivi) {
        try {
            if (jeu.sortie()) { b.dallesRestees = b.dalles.size(); return; }
            Set<Integer> copie = new HashSet<>();
            for (Piece p : b.pieces) { if (p.obtenu >= 0) copie.add(p.obtenu); if (p.id >= 0) copie.add(p.id); }
            for (int id : jeu.dallesPresentes()) {
                if (dallesAvant.contains(id) || copie.contains(id) || b.dalles.contains(id)) continue;
                b.dalles.add(id);
                PoseDalle.EXCLUES.add(id);          // jamais dalle de travail d'une autre pose (reprise...)
                b.dallesTardives++;
            }
            if (b.dalles.isEmpty()) return;
            if (GARDER_DALLES) { b.dallesLaissees = b.dalles.size(); return; }
            suivi.progres(0, b.dalles.size(), "Ramassage des dalles…");
            b.dallesRestees = jeu.ramasserDalles(b.dalles, (f, n) -> suivi.progres(f, n, "Ramassage des dalles…"));
        } catch (Throwable t) {
            Journal.debug("tapis : ramassage des dalles interrompu : " + t);
            Set<Integer> la = jeu.dallesPresentes();
            int n = 0;
            for (int id : b.dalles) if (la.contains(id)) n++;
            b.dallesRestees = la.isEmpty() ? b.dalles.size() : n;
        }
    }

    /**
     * Les hauteurs (@altitude) envoyees et pas encore verifiees (elles ont eu
     * le temps d'arriver pendant la pose du mobi suivant) : prise -> oubliee ;
     * pas prise -> renvoyee tout de suite et attendue, au plus Salle.REESSAIS
     * fois, puis comptee refusee (la verification de l'etape 5 la renverra).
     */
    private static void suivreHauteurs(Map<Integer, Double> enRoute, Jeu jeu, Bilan b) {
        for (Iterator<Map.Entry<Integer, Double>> i = enRoute.entrySet().iterator(); i.hasNext(); ) {
            if (jeu.arret() || jeu.sortie()) return;
            Map.Entry<Integer, Double> e = i.next();
            i.remove();
            int id = e.getKey();
            double voulu = e.getValue();
            double z = jeu.z(id);
            if (Double.isNaN(z)) continue;                                      // plus la : l'etape 5 s'en charge
            if (Math.abs(z - voulu) <= TOLERANCE) { jeu.reussite(); continue; }
            boolean prise = false;
            for (int k = 0; k < Salle.REESSAIS && !prise; k++) {
                if (jeu.arret() || jeu.sortie() || !jeu.altitudePossible()) break;   // @altitude perdue : definitif
                jeu.pauseReessai();
                jeu.altitude(id, voulu);
                jeu.attendreHauteurs(Map.of(id, voulu));
                z = jeu.z(id);
                prise = !Double.isNaN(z) && Math.abs(z - voulu) <= TOLERANCE;
            }
            if (prise) { b.apresReessai++; jeu.reussite(); }
            else if (!jeu.arret() && !jeu.sortie() && jeu.altitudePossible() && !Double.isNaN(z)) {
                b.refusApresReessai++;
                jeu.refus("hauteur pas prise");
            }
        }
    }

    /** Etape 1 : les dalles (plus grandes d'abord), puis leur hauteur au niveau du sol. */
    private static void poserTapis(Bilan b, Set<Long> cases, Jeu jeu, Suivi suivi) {
        int[][] sol = grille(cases, jeu);
        List<Object[]> formes = formes(jeu);
        b.dallesPrevues = planDalles(sol, formes).size();
        int total = Math.max(1, b.dallesPrevues);
        Set<Long> prises = new HashSet<>(), refusees = new HashSet<>(), exclus = new HashSet<>();
        Map<Integer, int[]> coins = new LinkedHashMap<>();
        int refusDebut = 0;
        suivi.progres(0, total, "Dalles : 0/" + total);
        dehors:
        for (Object[] f : formes) {
            DalleMagique t = (DalleMagique) f[0];
            int lx = (int) f[1], ly = (int) f[2], rot = (int) f[3];
            int refus = 0;
            while (refus < 6) {
                if (jeu.arret() || jeu.sortie()) break dehors;
                int[] c = OutilHauteur.meilleurCoin(sol, lx, ly, prises, refusees, exclus);
                if (c == null || !vautLaPeine(lx, ly, c[2])) break;
                int id = jeu.poserDalle(t, c[0], c[1], rot);
                // refusee : renvoyee tout de suite, au plus REESSAIS fois
                for (int k = 0; id == -1 && k < Salle.REESSAIS && !jeu.arret() && !jeu.sortie(); k++) {
                    jeu.pauseReessai();
                    id = jeu.poserDalle(t, c[0], c[1], rot);
                    if (id >= 0) b.apresReessai++;
                }
                if (id == -2) break;                          // pas de source pour cette taille : la suivante
                if (id < 0) {
                    if (jeu.arret() || jeu.sortie()) break dehors;
                    b.refusApresReessai++;
                    jeu.refus("dalle refusée");
                    exclus.add(OutilHauteur.cleForme(c[0], c[1], lx, ly));
                    if (lx * ly == 1) refusees.add(OutilHauteur.cle(c[0], c[1]));
                    refus++;
                    // tout refuse des le debut, meme apres reessais : pas la peine d'insister (BC inactif, limite...)
                    if (b.dalles.isEmpty() && ++refusDebut >= 2) {
                        b.raisonDalles = "le jeu refuse les dalles magiques (Builders Club inactif ou limite atteinte ?)";
                        break dehors;
                    }
                    continue;
                }
                jeu.reussite();
                refus = 0;
                b.dalles.add(id);
                PoseDalle.EXCLUES.add(id);          // jamais dalle de travail d'une autre pose (reprise...)
                coins.put(id, new int[]{c[0], c[1]});
                for (int i = 0; i < lx; i++) for (int j = 0; j < ly; j++) b.couverture.put(OutilHauteur.cle(c[0] + i, c[1] + j), id);
                for (int i = 0; i < lx; i++) for (int j = 0; j < ly; j++) prises.add(OutilHauteur.cle(c[0] + i, c[1] + j));
                total = Math.max(total, b.dalles.size());
                suivi.progres(b.dalles.size(), total, "Dalles : " + b.dalles.size() + "/" + total);
            }
        }
        // leur hauteur : celle du sol de leur case (pas de surelevation). Une dalle posee
        // sur des mobis (copie sur place) arrive en haut de la pile : elle est redescendue.
        for (Map.Entry<Integer, int[]> e : coins.entrySet()) {
            if (jeu.sortie()) break;
            double h = Math.max(0, jeu.sol(e.getValue()[0], e.getValue()[1]));
            double z = jeu.z(e.getKey());
            if (!Double.isNaN(z) && Math.abs(z - h) > 0.01) { jeu.reglerDalle(e.getKey(), h); b.dallesReglees++; }
        }
    }

    /** Les dalles du tapis sous l'emprise d'arrivee de la piece (sans doublon). */
    static List<Integer> dallesSous(Piece p, Bilan b) {
        LinkedHashSet<Integer> r = new LinkedHashSet<>();
        for (int i = 0; i < p.lx; i++)
            for (int j = 0; j < p.ly; j++) {
                Integer d = b.couverture.get(OutilHauteur.cle(p.x + i, p.y + j));
                if (d != null) r.add(d);
            }
        return new ArrayList<>(r);
    }

    /**
     * Avant la pose : chaque case jouable de l'emprise doit etre sur une dalle
     * du tapis. Une case restee nue (dalle refusee a l'etape 1, case oubliee)
     * recoit une dalle 1x1 a la hauteur du sol, un seul essai (pas de frein :
     * la pose qui suit dira si le mobi passe).
     */
    private static void couvrir(Piece p, Jeu jeu, Bilan b) {
        if (b.dallesPrevues == 0 || b.raisonDalles != null) return;     // pas de tapis, ou le jeu refuse les dalles
        DalleMagique un = null;
        for (DalleMagique t : jeu.dalles()) { int[] e = jeu.emprise(t); if (e[0] == 1 && e[1] == 1) un = t; }
        if (un == null) return;
        for (int i = 0; i < p.lx; i++)
            for (int j = 0; j < p.ly; j++) {
                int x = p.x + i, y = p.y + j;
                long c = OutilHauteur.cle(x, y);
                if (b.couverture.containsKey(c) || jeu.sol(x, y) < 0 || jeu.arret() || jeu.sortie()) continue;
                int id = jeu.poserDalle(un, x, y, 0);
                if (id < 0) {
                    Journal.debug("tapis : case (" + x + "," + y + ") sous " + p.nom + " sans dalle, et la dalle 1x1 est refusée.");
                    b.couverture.put(c, -1);                 // pas d'autre essai pour cette case
                    continue;
                }
                b.dalles.add(id);
                PoseDalle.EXCLUES.add(id);          // jamais dalle de travail d'une autre pose (reprise...)
                b.dallesAjoutees++;
                b.couverture.put(c, id);
                double h = Math.max(0, jeu.sol(x, y)), z = jeu.z(id);
                if (!Double.isNaN(z) && Math.abs(z - h) > 0.01) { jeu.reglerDalle(id, h); b.dallesReglees++; }
                Journal.debug("tapis : case (" + x + "," + y + ") sous " + p.nom + " sans dalle : dalle 1x1 " + id + " ajoutée.");
            }
    }

    /**
     * Les dalles du tapis MONTEES au-dessus d'une pile non empilable. Quand
     * une case de l'emprise d'un mobi porte, plus haut que sa dalle, un mobi
     * non empilable (rochers...), le serveur refuse la pose sur la dalle
     * (cant_set_item). Ses dalles sont alors reglees juste au-dessus du haut
     * de la pile de son emprise (la dalle redevient le haut de la pile, comme
     * a la main), il y est pose (ou deplace), recoit son etat, puis @altitude
     * le descend a son z. Repere AVANT la pose (HeightMap du serveur : case
     * bloquee ; cases deja vues bloquees pendant ce tapis), en repli apres un
     * premier refus cant_set_item. Une dalle montee reprend sa hauteur d'avant
     * des qu'un mobi suivant n'en a plus besoin, et a la fin du tapis.
     */
    static final class Montee {
        /** Dalle montee -> sa hauteur d'avant ; -> la hauteur envoyee. */
        private final Map<Integer, Double> avant = new LinkedHashMap<>(), reglee = new HashMap<>();
        /** Cases vues bloquees pendant ce tapis (elles le restent : la pile ne fait que monter). */
        private final Set<Long> bloquees = new HashSet<>();
        /** Piece en cours : hauteur de ses dalles montees (NaN : pas montees), haut de sa pile, repere apres un refus. */
        private double hauteur = Double.NaN, haut = Double.NaN;
        private boolean apresRefus;

        /** Avant la pose : dalles montees si une case de l'emprise est bloquee, les autres redescendues. */
        void avant(Piece p, Jeu jeu, Bilan b) {
            hauteur = Double.NaN; haut = Double.NaN; apresRefus = false;
            List<Integer> sous = dallesValides(p, b);
            double h = Double.NaN;
            if (!sous.isEmpty() && jeu.altitudePossible()) {
                boolean bloquee = false;
                for (int i = 0; i < p.lx; i++)
                    for (int j = 0; j < p.ly; j++) {
                        long c = OutilHauteur.cle(p.x + i, p.y + j);
                        if (bloquees.contains(c)) bloquee = true;
                        else if (jeu.caseBloquee(p.x + i, p.y + j)) { bloquees.add(c); bloquee = true; }
                    }
                if (bloquee) h = jeu.hautPile(p, exclus(p, b));
            }
            redescendre(Double.isNaN(h) ? Set.of() : new HashSet<>(sous), jeu);
            if (!Double.isNaN(h)) monter(sous, h, jeu, b);
        }

        /**
         * Pose refusee : si le serveur a dit cant_set_item et que ses dalles
         * ne sont pas deja montees, elles montent (le reessai part dessus).
         */
        boolean apresRefus(Piece p, Jeu jeu, Bilan b, long t0) {
            if (!Double.isNaN(hauteur) || !jeu.altitudePossible() || !refusEmpilement(jeu.raisonRefus(p, t0))) return false;
            List<Integer> sous = dallesValides(p, b);
            double h = sous.isEmpty() ? Double.NaN : jeu.hautPile(p, exclus(p, b));
            if (Double.isNaN(h)) return false;
            // ses cases : retenues bloquees, les mobis suivants y sont reperes avant la pose
            for (int i = 0; i < p.lx; i++) for (int j = 0; j < p.ly; j++) bloquees.add(OutilHauteur.cle(p.x + i, p.y + j));
            if (!monter(sous, h, jeu, b)) return false;
            apresRefus = true;
            return true;
        }

        /** Apres la pose : le compte, et une ligne de diagnostic par mobi traite ainsi. */
        void apres(Piece p, Jeu jeu, Bilan b) {
            if (Double.isNaN(hauteur)) return;
            boolean ok = p.obtenu >= 0;
            if (ok) b.dallesMontees++; else b.monteesRefusees++;
            if (apresRefus) b.monteesApresRefus++;
            Journal.debug("tapis : " + (p.nouveau() ? p.classe : "#" + p.id) + " en (" + p.x + "," + p.y + ") z" + p.z
                    + " : mobi non empilable dessous (pile jusqu'à z" + String.format(Locale.ROOT, "%.2f", haut) + ", "
                    + (apresRefus ? "repéré après un refus cant_set_item" : "repéré avant la pose") + "), dalle montée à z"
                    + hauteur + (ok ? " : posé dessus, puis descendu par @altitude." : " : refusé quand même."));
        }

        /** Les dalles montees qui ne sont pas a garder reprennent leur hauteur d'avant. */
        void redescendre(Set<Integer> garder, Jeu jeu) {
            for (Iterator<Map.Entry<Integer, Double>> it = avant.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Double> e = it.next();
                if (garder.contains(e.getKey())) continue;
                if (!Double.isNaN(jeu.z(e.getKey()))) jeu.reglerDalle(e.getKey(), e.getValue());
                reglee.remove(e.getKey());
                it.remove();
            }
        }

        /** Monte ces dalles juste au-dessus de ce haut de pile (une dalle deja assez haute reste). */
        private boolean monter(List<Integer> sous, double haut, Jeu jeu, Bilan b) {
            double h = auDessus(haut);
            // les cases de ces dalles bloquees AVANT la montee (la dalle montee les debloque) restent connues
            Set<Integer> aMonter = new HashSet<>(sous);
            aMonter.removeAll(avant.keySet());
            if (!aMonter.isEmpty())
                for (Map.Entry<Long, Integer> e : b.couverture.entrySet()) {
                    if (!aMonter.contains(e.getValue()) || bloquees.contains(e.getKey())) continue;
                    long c = e.getKey();
                    if (jeu.caseBloquee((int) (c >> 32), (int) c)) bloquees.add(c);
                }
            int n = 0;
            for (int d : sous) {
                Double r = reglee.get(d);
                double z = r != null ? r : jeu.z(d);
                if (Double.isNaN(z)) continue;
                n++;
                if (z >= h - 0.005) continue;
                avant.putIfAbsent(d, z);
                jeu.reglerDalle(d, h);
                reglee.put(d, h);
            }
            if (n == 0) return false;
            hauteur = h;
            this.haut = haut;
            return true;
        }

        /** Hors de la pile : les dalles du tapis et le mobi lui-meme (deplacement). */
        private static Set<Integer> exclus(Piece p, Bilan b) {
            Set<Integer> s = new HashSet<>(b.dalles);
            if (p.id >= 0) s.add(p.id);
            return s;
        }
    }

    /** Les dalles valides sous la piece. */
    private static List<Integer> dallesValides(Piece p, Bilan b) {
        List<Integer> l = dallesSous(p, b);
        l.removeIf(id -> id < 0);
        return l;
    }

    /**
     * Pose d'un nouveau mobi (reessayee tout de suite si refusee), puis sa
     * rotation si le jeu en a mis une autre. La rotation vient de Rotations
     * (celle qui passe pour sa classe). Un refus cant_set_item (ou sans raison,
     * apres un reessai normal) d'une rotation autre que 0 : les autres
     * rotations possibles d'abord (Rotations), et seulement si aucune ne passe,
     * la dalle montee (Montee) et les reessais.
     */
    private static void poserNouveau(Piece p, Jeu jeu, Bilan b, Montee montee, Rotations rots) {
        long t0 = System.currentTimeMillis();
        int rot = rots.choisir(p);
        int id = jeu.poser(p, rot);
        boolean ravivees = false;
        if (id == -1 && (rot != 0 || p.rot != 0) && !jeu.arret() && !jeu.sortie()) {
            String raison = jeu.raisonRefus(p, t0);
            if (raison == null) {
                // refus sans raison (peut-etre le rythme) : un reessai normal d'abord
                b.dallesRavivees += jeu.raviverDalles(dallesValides(p, b));
                ravivees = true;
                jeu.pauseReessai();
                long t = System.currentTimeMillis();
                id = jeu.poser(p, rot);
                if (id >= 0) b.apresReessai++;
                else if (id == -1) raison = jeu.raisonRefus(p, t);
            }
            // la rotation en cause ? les autres rotations, AVANT toute montee de dalle
            if (id == -1 && (raison == null || refusEmpilement(raison)) && !jeu.arret() && !jeu.sortie()) {
                int[] r = autreRotation(p, rot, jeu, b, rots);
                id = r[0];
                rot = r[1];
            }
        }
        boolean premier = true;
        for (int k = ravivees ? 1 : 0; id == -1 && k < Salle.REESSAIS && !jeu.arret() && !jeu.sortie(); k++) {
            // avant le premier reessai : refus cant_set_item (la rotation n'y est pour rien) -> ses dalles
            // montent au-dessus de la pile ; sinon les dalles sous ses cases redisent leur hauteur
            boolean monte = premier && montee.apresRefus(p, jeu, b, t0);
            if (premier && !monte && !ravivees) b.dallesRavivees += jeu.raviverDalles(dallesValides(p, b));
            premier = false;
            jeu.pauseReessai();
            long t = System.currentTimeMillis();
            id = jeu.poser(p, rot);
            if (id >= 0) b.apresReessai++;
            // case bloquee ET rotation refusee : sur la dalle montee, les autres rotations aussi
            else if (id == -1 && monte && (rot != 0 || p.rot != 0) && !jeu.arret() && !jeu.sortie()) {
                String raison = jeu.raisonRefus(p, t);
                if (raison == null || refusEmpilement(raison)) {
                    int[] r = autreRotation(p, rot, jeu, b, rots);
                    id = r[0];
                    rot = r[1];
                }
            }
        }
        if (id == -2) { p.introuvable = true; return; }       // pas dans la source : definitif
        if (id == -3) { p.pasAuBc = true; return; }           // pas posable au BC : definitif, sans reessai
        if (id < 0) {
            p.refuse = true;
            if (!jeu.arret() && !jeu.sortie()) {
                b.refusApresReessai++;
                jeu.refus("mobi pas apparu");
                p.raison = jeu.raisonRefus(p, t0);
                jeu.diagnostiquerRefus(p, dallesValides(p, b), t0);
                // refus repete d'une offre BC sans raison du serveur : plus envoyee, pas de reprise
                if (p.raison == null && jeu.offreRefusee(p)) p.pasAuBc = true;
            }
            return;
        }
        jeu.reussite();
        p.obtenu = id;
        rots.accepter(p, rot);
        p.autreDirection = rot != p.rot;
        int[] pos = jeu.position(id);
        if (pos != null && !GroupeCalcul.rotationAcceptee(pos[2], rot, rot, false)) {
            int repli = GroupeCalcul.rotationRepli(rot);
            if (!jeu.deplacer(id, p.x, p.y, rot)) jeu.deplacer(id, p.x, p.y, repli);
        }
    }

    /**
     * La pose refusee dans la rotation envoyee : renvoyee avec les autres
     * rotations possibles (Rotations.candidats), une fois chacune. Rend
     * {id, rotation} : la premiere qui passe est retenue pour la classe ;
     * aucune ne passe (rotation 0 refusee aussi) : la rotation n'est pas en
     * cause, rien n'est retenu, {-1, envoyee}.
     */
    private static int[] autreRotation(Piece p, int envoyee, Jeu jeu, Bilan b, Rotations rots) {
        List<Integer> marquees = new ArrayList<>();
        if (rots.refuser(p, envoyee)) marquees.add(envoyee);
        for (int r : rots.candidats(p, envoyee, jeu.directionParDefaut(p))) {
            if (jeu.arret() || jeu.sortie()) break;
            jeu.pauseReessai();
            long t = System.currentTimeMillis();
            int id = jeu.poser(p, r);
            if (id >= 0) {
                b.apresReessai++;
                rots.dire(p, envoyee, r, "posé");
                return new int[]{id, r};
            }
            if (id != -1) return new int[]{id, envoyee};     // pas de source : definitif
            String raison = jeu.raisonRefus(p, t);
            if (raison != null && !refusEmpilement(raison)) break;   // une autre cause
            if (rots.refuser(p, r)) marquees.add(r);
        }
        rots.oublier(p, marquees);
        return new int[]{-1, envoyee};
    }

    /**
     * Deplacement d'un mobi deja la : la rotation voulue ; si elle est
     * refusee et qu'il tourne, l'equivalente sur le meme axe, puis (emprise
     * carree qui change de case) celle d'origine. Une equivalente qui passe
     * apres un refus cant_set_item est retenue pour les mobis suivants du meme
     * nom (Rotations : ils partent directement avec elle). S'il n'est meme pas
     * arrive a sa case (deplacement refuse), le tout est renvoye, au plus
     * Salle.REESSAIS fois.
     */
    private static void deplacer(Piece p, Jeu jeu, Bilan b, Montee montee, Rotations rots) {
        boolean tourne = p.rot != p.rotOrigine;
        long t0 = System.currentTimeMillis();
        // l'equivalente d'abord si elle est deja connue pour ce mobi
        int[] ordre = tourne && rots.choisir(p) == GroupeCalcul.rotationEssai(p.rot, p.rotOrigine, 1)
                && rots.choisir(p) != p.rot ? new int[]{1, 0, 2} : new int[]{0, 1, 2};
        for (int tour = 0; tour <= Salle.REESSAIS; tour++) {
            if (tour > 0) {
                if (jeu.arret() || jeu.sortie()) return;
                if (tour == 1 && !montee.apresRefus(p, jeu, b, t0)) b.dallesRavivees += jeu.raviverDalles(dallesValides(p, b));
                jeu.pauseReessai();
            }
            boolean surCase = false, voulueRefusee = false;
            String raison = null;
            for (int essai : ordre) {
                if (essai > 0 && !tourne) continue;
                int rot = GroupeCalcul.rotationEssai(p.rot, p.rotOrigine, essai);
                if (rot < 0) continue;
                int[] avant = jeu.position(p.id);
                if (avant == null) { p.refuse = true; return; }               // plus dans la salle : definitif
                if (essai == 2 && ((avant[0] == p.x && avant[1] == p.y) || p.lx != p.ly)) continue;
                long t = System.currentTimeMillis();
                jeu.deplacer(p.id, p.x, p.y, rot);
                int[] pos = jeu.position(p.id);
                if (pos != null && pos[0] == p.x && pos[1] == p.y) {
                    surCase = true;
                    if (GroupeCalcul.rotationAcceptee(pos[2], p.rot, p.rotOrigine, essai == 2)) {
                        p.sansTourner = essai == 2;
                        p.obtenu = p.id;
                        if (essai == 1) {
                            p.autreDirection = true;
                            // retenue seulement si la voulue a ete refusee pour sa rotation (cant_set_item)
                            if (voulueRefusee && refusEmpilement(raison) && rots.refuser(p, p.rot)) rots.dire(p, p.rot, rot, "déplacé");
                        }
                        if (essai != 2) rots.accepter(p, rot);
                        if (tour > 0) b.apresReessai++;
                        jeu.reussite();
                        return;
                    }
                }
                if (essai == 0) { voulueRefusee = true; raison = jeu.raisonRefus(p, t); }
            }
            if (surCase) break;           // arrive dans une rotation refusee : pas un refus de rythme
        }
        p.refuse = true;
        if (!jeu.arret() && !jeu.sortie()) {
            b.refusApresReessai++;
            jeu.refus("mobi pas arrivé");
            p.raison = jeu.raisonRefus(p, t0);
            jeu.diagnostiquerRefus(p, dallesValides(p, b), t0);
        }
    }

    /**
     * Rotations refusees / acceptees par classe pendant l'action. Certains
     * mobis n'acceptent que certaines directions (santorini_c17_rocks : 0 et
     * 2) : le serveur refuse la pose (cant_set_item, meme sur une case vide).
     * Ce n'est pas un probleme d'empilement : la pose est d'abord renvoyee
     * avec une rotation equivalente (r4 -> r0, r6 -> r2, r5 -> r1, r7 -> r3),
     * puis en dernier recours la direction par defaut (furnidata) ou 0 si
     * l'emprise ne change pas. Ce qui passe est retenu : les mobis suivants de
     * la meme classe partent directement avec la bonne rotation (un seul refus
     * par classe ; une rotation refusee >= 4 rend aussi les autres >= 4
     * suspectes). Un refus de rotation ne marque aucune case bloquee (Montee).
     */
    static final class Rotations {
        private final Map<String, Set<Integer>> refusees = new HashMap<>(), acceptees = new HashMap<>();
        /** Classe -> rotation voulue -> rotation qui passe. */
        private final Map<String, Map<Integer, Integer>> remplacees = new HashMap<>();
        private final Set<String> dites = new HashSet<>();
        /** Debut des lignes de Journal.debug (« tapis », « pose directe »). */
        private final String qui;

        Rotations() { this("tapis"); }
        Rotations(String qui) { this.qui = qui; }

        /** La classe du mobi (a defaut son nom, pour un deplacement). */
        static String cle(Piece p) { return p.classe != null ? p.classe : p.nom; }

        /** Rotation equivalente (meme aspect pour un mobi a 2 directions) : r - 4 pour r >= 4, sinon -1. Logique pure. */
        static int equivalente(int r) { r &= 7; return r >= 4 ? r - 4 : -1; }

        private static int axe(int r) { r &= 7; return r == 2 || r == 6 ? 1 : 0; }

        /** Cette rotation garde l'emprise de la piece (lx, ly pas echanges) ? */
        static boolean memeEmprise(Piece p, int r) { return p.lx == p.ly || axe(r) == axe(p.rot); }

        private Set<Integer> de(Map<String, Set<Integer>> m, String c) { return m.getOrDefault(c, Set.of()); }

        /** La rotation a envoyer d'abord : la voulue, ou celle qui a passe pour sa classe. */
        int choisir(Piece p) {
            String c = cle(p);
            if (c == null) return p.rot;
            Integer m = remplacees.getOrDefault(c, Map.of()).get(p.rot);
            if (m != null) return m;
            if (de(acceptees, c).contains(p.rot)) return p.rot;
            boolean suspecte = de(refusees, c).contains(p.rot);
            // une rotation >= 4 deja refusee et remplacee par son equivalente : les autres >= 4 aussi
            if (!suspecte && p.rot >= 4)
                for (Map.Entry<Integer, Integer> e : remplacees.getOrDefault(c, Map.of()).entrySet())
                    if (e.getKey() >= 4 && e.getValue() == e.getKey() - 4) { suspecte = true; break; }
            if (!suspecte) return p.rot;
            for (int r : candidats(p, p.rot, -1)) return r;
            return p.rot;
        }

        /**
         * Les rotations a essayer apres un refus de « envoyee », dans l'ordre :
         * la voulue (si l'envoyee etait une autre), son equivalente, la
         * direction par defaut, 0 ; sans les rotations refusees pour la classe
         * ni celles qui changent l'emprise. Logique pure.
         */
        List<Integer> candidats(Piece p, int envoyee, int defaut) {
            LinkedHashSet<Integer> l = new LinkedHashSet<>();
            l.add(p.rot);
            if (equivalente(p.rot) >= 0) l.add(equivalente(p.rot));
            if (defaut >= 0) l.add(defaut & 7);
            l.add(0);
            l.remove(envoyee & 7);
            String c = cle(p);
            if (c != null) l.removeAll(de(refusees, c));
            l.removeIf(r -> !memeEmprise(p, r));
            return new ArrayList<>(l);
        }

        /** Rotation refusee pour la classe : true si elle ne l'etait pas encore. */
        boolean refuser(Piece p, int r) {
            String c = cle(p);
            return c != null && !de(acceptees, c).contains(r & 7) && refusees.computeIfAbsent(c, k -> new HashSet<>()).add(r & 7);
        }

        /** Aucune rotation ne passe : ces refus n'etaient pas dus a la rotation, oublies. */
        void oublier(Piece p, Collection<Integer> rots) {
            String c = cle(p);
            if (c != null && refusees.containsKey(c)) refusees.get(c).removeAll(rots);
        }

        /** Rotation prise par le jeu : retenue (et la voulue -> elle, si c'en est une autre). */
        void accepter(Piece p, int r) {
            String c = cle(p);
            if (c == null) return;
            acceptees.computeIfAbsent(c, k -> new HashSet<>()).add(r & 7);
            if ((r & 7) != p.rot && de(refusees, c).contains(p.rot))
                remplacees.computeIfAbsent(c, k -> new HashMap<>()).put(p.rot, r & 7);
        }

        /** Retient « voulue -> r » et le dit une fois par classe (Journal.debug). */
        void dire(Piece p, int refusee, int r, String participe) {
            String c = cle(p);
            if (c == null) return;
            remplacees.computeIfAbsent(c, k -> new HashMap<>()).put(p.rot, r & 7);
            if (dites.add(c)) Journal.debug(qui + " : rotation " + refusee + " refusée pour " + c + ", " + participe + " en " + r + ".");
        }
    }

    /** Etape 5 : une nouvelle tentative par mobi manquant ou a une mauvaise hauteur. */
    private static void verifier(Bilan b, List<Piece> tapis, List<Piece> directs, Jeu jeu, Suivi suivi) {
        suivi.progres(-1, -1, "Vérification…");
        // a. les mobis disparus ; les hauteurs fausses, renvoyees une fois
        Map<Integer, Double> faux = new LinkedHashMap<>();
        for (Piece p : tapis) {
            if (p.obtenu < 0) continue;
            double z = jeu.z(p.obtenu);
            if (Double.isNaN(z)) { p.obtenu = -1; p.refuse = true; continue; }
            if (Math.abs(z - p.z) > TOLERANCE) faux.put(p.obtenu, p.z);
        }
        if (!faux.isEmpty() && !b.arrete) {
            jeu.attendreHauteurs(faux);                      // les dernieres envoyees arrivent peut-etre encore
            faux.entrySet().removeIf(e -> { double z = jeu.z(e.getKey()); return !Double.isNaN(z) && Math.abs(z - e.getValue()) <= TOLERANCE; });
            if (!faux.isEmpty() && jeu.altitudePossible()) {
                for (Map.Entry<Integer, Double> e : faux.entrySet()) {
                    if (jeu.arret() || jeu.sortie()) break;
                    jeu.altitude(e.getKey(), e.getValue());
                }
                jeu.attendreHauteurs(faux);
            }
        }
        // b. la reprise a la dalle par mobi : refuses, mobis en hauteur sans @altitude
        if (!b.arrete && !jeu.sortie()) {
            List<Piece> reprise = new ArrayList<>(directs);
            for (Piece p : directs) p.nonTente = false;
            boolean alt = jeu.altitudePossible();
            for (Piece p : tapis) {
                if (!p.reprenable || p.nonTente || p.introuvable || p.pasAuBc) continue;
                if (p.obtenu < 0) { reprise.add(p); continue; }
                double z = jeu.z(p.obtenu);
                if (!alt && !Double.isNaN(z) && Math.abs(z - p.z) > TOLERANCE) reprise.add(p);
            }
            if (!reprise.isEmpty()) {
                Map<Piece, Integer> r = jeu.reprendre(reprise);
                for (Piece p : reprise) {
                    Integer id = r.get(p);
                    if (id == null) {
                        // pas reprise : un mobi refuse le reste ; un mobi pose garde son id s'il est encore la
                        if (p.obtenu >= 0 && Double.isNaN(jeu.z(p.obtenu))) p.obtenu = -1;
                        if (p.obtenu < 0) p.refuse = true;
                        continue;
                    }
                    p.obtenu = id;
                    p.parDalle = true;
                    p.refuse = false;
                }
            }
        }
        // c. le compte final
        for (Piece p : b.pieces) {
            if (p.obtenu < 0) { p.hauteurOk = false; continue; }
            double z = jeu.z(p.obtenu);
            if (Double.isNaN(z)) { p.obtenu = -1; p.refuse = !p.nonTente; continue; }
            p.hauteurOk = Math.abs(z - p.z) <= TOLERANCE;
        }
    }

    // ============================================================ ramassage des dalles

    /** Ecart entre deux ramassages de DALLES (les autres envois gardent Salle.espacer, reglable). */
    static final long ECART_DALLES_MS = 80;
    /** Le rythme rapide a ete refuse pendant cette session (dalles restees, reprises au rythme commun). */
    private static volatile boolean rapideRefuse = false;

    /** Rythme rapide des dalles : 80 ms, ou le reglage de l'utilisatrice s'il est plus bas. */
    static long ecartDalles() { return Math.min(ECART_DALLES_MS, Salle.ecartVoulu()); }

    /** Progression d'un ramassage : fait, total, numero de passe. */
    interface Avance { void a(int fait, int total, int passe); }

    /**
     * Ramasse ces dalles magiques au rythme des dalles (ecartDalles), en
     * 2 passes avec verification ; s'il en reste apres les 2 passes, une passe
     * de plus au rythme commun (Salle.ecart), notee en debug. Si le frein a
     * joue (Salle.freine), tout part au rythme commun. Chaque passe apres la
     * premiere est un reessai (courte attente avant) ; seules les dalles
     * encore la apres toutes les passes comptent comme refus pour le frein.
     * Rend le nombre de dalles encore la. Hors fil JavaFX.
     * @param stop arret (salle quittee, Arreter...) ; peut etre null
     */
    static int ramasserDalles(Collection<Integer> ids, BooleanSupplier stop, Avance avance) {
        List<Integer> l = new ArrayList<>(new LinkedHashSet<>(ids));
        // le jeu peut demander confirmation (ObjectRemoveConfirm) : acceptee pour ces dalles
        PoseOutils.Signaux.ramassageEnCours(l);
        try {
            return ramasserDalles0(l, stop, avance);
        } finally {
            PoseOutils.Signaux.ramassageFini(l);
        }
    }

    /** Encore dans la salle (un retrait groupe ObjectRemoveMultiple, que l'etat ne lit pas, compte comme parti). */
    static boolean encoreLa(int id) { return Salle.sol(id) != null && !PoseOutils.Signaux.retireGroupe(id); }

    private static int ramasserDalles0(List<Integer> l, BooleanSupplier stop, Avance avance) {
        BooleanSupplier s = stop == null ? () -> false : stop;
        int total = l.size(), n = 0;
        boolean rapide = !rapideRefuse && !Salle.freine();
        long ecartRapide = ecartDalles();
        long debut = System.currentTimeMillis();
        for (int passe = 1; passe <= 3; passe++) {
            List<Integer> encore = new ArrayList<>();
            for (int id : l) if (encoreLa(id)) encore.add(id);
            if (encore.isEmpty() || s.getAsBoolean()) break;
            if (passe == 3) {
                if (!rapide) break;                           // deja au rythme commun : 2 passes suffisent
                Journal.debug("dalles : " + encore.size() + " encore là après 2 passes à " + ecartRapide
                        + " ms : 3e passe à " + Salle.ecart() + " ms.");
                rapide = false;
            }
            if (passe > 1) Salle.pauseReessai();
            long dernier = 0;
            for (int id : encore) {
                if (s.getAsBoolean()) break;
                if (rapide && dernier > 0) {
                    long reste = ecartRapide - (System.currentTimeMillis() - dernier);
                    if (reste > 0) Salle.sommeil(reste);
                } else Salle.espacer();
                // comme le client : PickupObject(2, id, false), meme pour une dalle du BC (0x7FFF....)
                Salle.envoyer(PoseOutils.ramassage(id, false, false));
                dernier = System.currentTimeMillis();
                if (passe == 1) n++;
                if (avance != null) avance.a(n, total, passe);
            }
            Salle.envoiFait();
            PoseDirecte.suivre(() -> { int r = 0; for (int id : encore) if (encoreLa(id)) r++; return r; }, 800, 3000);
            if (passe == 3) {
                int restent = 0;
                for (int id : encore) if (encoreLa(id)) restent++;
                if (restent < encore.size()) {
                    rapideRefuse = true;
                    Journal.debug("dalles : le jeu refuse le rythme de " + ecartRapide + " ms, les prochains ramassages partent à "
                            + Salle.ecart() + " ms.");
                }
            }
        }
        int restent = 0, groupe = 0;
        List<Integer> restes = new ArrayList<>();
        for (int id : l) {
            if (encoreLa(id)) { restent++; restes.add(id); }
            else if (Salle.sol(id) != null) groupe++;
        }
        if (groupe > 0)
            Journal.debug("dalles : " + groupe + " retirée(s) par le serveur en groupe (ObjectRemoveMultiple) mais encore dans "
                    + "l'état de la salle (EtatSalle ne lit pas ce paquet) : comptées ramassées.");
        if (!s.getAsBoolean()) {
            // apres toutes les passes (reessais compris) : une dalle restee est un refus pour le frein
            Salle.signalerReussite(total - restent);
            Salle.signalerRefus("dalle pas ramassée", restent);
            if (restent > 0) {
                Journal.debug("dalles : " + restent + "/" + total + " encore là après toutes les passes.");
                Journal.debug(diagnosticDalles(restes, debut));
            }
        }
        return restent;
    }

    /**
     * Diagnostic d'un ramassage rate : droits, compte BC, ce que le serveur a
     * dit pendant le ramassage, et pour quelques dalles restees leur place,
     * leur hauteur et les mobis poses sur leur emprise.
     */
    static String diagnosticDalles(List<Integer> restes, long depuis) {
        StringBuilder b = new StringBuilder("dalles pas ramassées : ");
        try {
            Moteur gp = Salle.gp();
            Droits d = gp == null ? null : gp.getPermissions();
            b.append("droits ").append(d == null ? "?" : "niveau " + d.niveau() + (d.peutDeplacer() ? ", déplacer" : ", PAS de déplacement"));
            int bc = PoseOutils.Signaux.compteBc();
            if (bc >= 0) b.append(", mobis BC comptés ").append(bc);
            List<PoseOutils.Signaux.Signal> sig = PoseOutils.Signaux.depuis(depuis);
            b.append(" ; serveur pendant le ramassage : ").append(sig.isEmpty() ? "rien (ni confirmation, ni erreur)" : "");
            for (int i = 0; i < sig.size() && i < 5; i++) b.append(i > 0 ? " | " : "").append(sig.get(i).texte());
            int k = 0;
            for (int id : restes) {
                if (k++ >= 5) { b.append(" ; …"); break; }
                HFloorItem it = Salle.sol(id);
                if (it == null) continue;
                int[] e = Salle.emprise(it);
                int x = it.getTile().getX(), y = it.getTile().getY(), dessus = 0;
                for (HFloorItem o : Salle.sols()) {
                    if (o.getId() == id) continue;
                    int[] eo = Salle.emprise(o);
                    int ox = o.getTile().getX(), oy = o.getTile().getY();
                    if (ox < x + e[0] && ox + eo[0] > x && oy < y + e[1] && oy + eo[1] > y) dessus++;
                }
                b.append(" ; ").append(id).append(id >= 0x7FFF0000 ? " (BC)" : "").append(" en ").append(x).append(",").append(y)
                        .append(" ").append(e[0]).append("x").append(e[1]).append(" z").append(it.getTile().getZ())
                        .append(", ").append(dessus).append(" mobi(s) sur son emprise")
                        .append(PoseOutils.Signaux.confirme(id) ? ", confirmation demandée et envoyée" : "");
            }
        } catch (Throwable t) { b.append(" (").append(t).append(")"); }
        return b.toString();
    }

    // ============================================================ la vraie salle

    /** Le Jeu de la salle courante. */
    static final class JeuSalle implements Jeu {
        private final Moteur gp;
        private final Furnidata fd;
        private final Generateur.Source source;
        private final BooleanSupplier arret;
        private final int salle;
        private final Consumer<String> dire;
        private final Suivi suivi;
        /** Reprise : garder les calques (et la selection) des mobis ramasses puis reposes. */
        private final boolean calques;
        private final Set<Integer> invPris = new HashSet<>();
        private final Map<String, Integer> types = new HashMap<>();
        private boolean altitude = true;
        private long dernierReglage = 0;
        /** Le bilan de la derniere reprise a la dalle (null : aucune). */
        PoseHybride.Bilan reprise;

        /**
         * @param arret  Arreter demande (la salle quittee est suivie ici)
         * @param calques true pour les calques : les mobis repris gardent leur calque
         */
        JeuSalle(Generateur.Source source, BooleanSupplier arret, Consumer<String> dire, Suivi suivi, boolean calques) {
            this.gp = Salle.gp();
            this.fd = gp == null ? null : gp.getFurniDataTools();
            this.source = source == null ? Generateur.Source.INVENTAIRE_PUIS_BC : source;
            this.arret = arret == null ? () -> false : arret;
            this.salle = Groupes.salleCourante();
            this.dire = dire == null ? m -> { } : dire;
            this.suivi = suivi;
            this.calques = calques;
            PoseOutils.Signaux.installer();           // ce que dit le serveur (avertissement BC, erreurs, confirmations)
        }

        @Override public String raisonRefus(Piece p, long t) { return PoseOutils.Signaux.raisonPose(t, p.x, p.y); }

        @Override public int directionParDefaut(Piece p) {
            try { Furnidata.Mobi d = p.classe == null ? null : Salle.details(p.classe); return d == null ? -1 : d.defaultDir; }
            catch (Throwable t) { return -1; }
        }

        /** HeightMap du serveur : 0x4000 = empilement interdit (le haut de la pile n'est pas empilable). */
        @Override public boolean caseBloquee(int x, int y) {
            EtatSalle s = Salle.etat();
            int v = s == null ? -1 : s.hauteurBrute(x, y);
            return v >= 0 && v != 0x7FFF && (v & 0x8000) == 0 && (v & 0x4000) != 0 && Salle.hauteurSol(x, y) >= 0;
        }

        @Override public double hautPile(Piece p, Collection<Integer> exclus) {
            Set<Integer> dalles = Generateur.Dalle.typesDalles();
            double haut = Double.NaN;
            for (HFloorItem it : Salle.sols()) {
                if (exclus.contains(it.getId()) || dalles.contains(it.getTypeId())) continue;
                int[] e = Salle.emprise(it);
                int ox = it.getTile().getX(), oy = it.getTile().getY();
                if (ox >= p.x + p.lx || ox + e[0] <= p.x || oy >= p.y + p.ly || oy + e[1] <= p.y) continue;
                double top = it.getTile().getZ() + Salle.hauteur(it);
                if (Double.isNaN(haut) || top > haut) haut = top;
            }
            return haut;
        }

        /**
         * Diagnostic d'un refus : l'envoi, puis pour chaque case de l'emprise
         * le plan, la pile du serveur (HeightMap : hauteur, « bloquée » =
         * empilement interdit), la dalle du tapis qui la couvre (et sa
         * hauteur), les mobis deja dessus ; enfin ce que le serveur a dit.
         */
        @Override public void diagnostiquerRefus(Piece p, Collection<Integer> dalles, long t) {
            try {
                StringBuilder b = new StringBuilder("tapis : refus de ")
                        .append(p.nouveau() ? p.classe : "#" + p.id).append(" en (").append(p.x).append(",").append(p.y)
                        .append(") r").append(p.rot).append(" ").append(p.lx).append("x").append(p.ly).append(" z").append(p.z);
                if (p.nouveau()) b.append(", envoi ").append(PoseDirecte.dernierEnvoi);
                EtatSalle s = Salle.etat();
                List<HFloorItem> tous = Salle.sols();
                int n = 0;
                for (int i = 0; i < p.lx; i++)
                    for (int j = 0; j < p.ly; j++) {
                        if (n++ >= 6) { b.append(" ; …"); i = p.lx; break; }
                        int x = p.x + i, y = p.y + j;
                        b.append(" ; (").append(x).append(",").append(y).append(") plan ")
                                .append(s == null ? '?' : s.caseDuPlan(x, y));
                        int v = s == null ? -1 : s.hauteurBrute(x, y);
                        if (v >= 0) b.append(", pile ").append(String.format(Locale.ROOT, "%.2f", (v & 0x3FFF) / 256.0))
                                .append((v & 0x4000) != 0 ? " BLOQUÉE" : "").append((v & 0x8000) != 0 ? " hors salle" : "");
                        String dalle = "aucune dalle du tapis";
                        int surCase = 0;
                        double haut = Double.NEGATIVE_INFINITY;
                        String quiHaut = null;
                        for (HFloorItem it : tous) {
                            int[] e = Salle.emprise(it);
                            int ox = it.getTile().getX(), oy = it.getTile().getY();
                            if (x < ox || x >= ox + e[0] || y < oy || y >= oy + e[1]) continue;
                            if (dalles.contains(it.getId())) { dalle = "dalle " + it.getId() + " z" + it.getTile().getZ(); continue; }
                            surCase++;
                            double top = it.getTile().getZ() + Salle.hauteur(it);
                            if (top > haut) { haut = top; quiHaut = Salle.classe(it.getTypeId(), false) + " z" + it.getTile().getZ(); }
                        }
                        b.append(", ").append(dalle).append(", ").append(surCase).append(" mobi(s)");
                        if (quiHaut != null) b.append(" (le plus haut : ").append(quiHaut).append(")");
                    }
                List<PoseOutils.Signaux.Signal> sig = PoseOutils.Signaux.depuis(t);
                b.append(" ; serveur : ").append(sig.isEmpty() ? "rien" : "");
                for (int i = 0; i < sig.size() && i < 4; i++) b.append(i > 0 ? " | " : "").append(sig.get(i).texte());
                Journal.debug(b.toString());
            } catch (Throwable e) { Journal.debug("tapis : diagnostic du refus de " + p + " : " + e); }
        }

        /** Les dalles ne vont pas dans l'historique (Ctrl+Z) pendant toute l'action. */
        void debut() { for (int t : Generateur.Dalle.typesDalles()) Historique.ignorerType(t, 60 * 60_000L); }
        void fin() { for (int t : Generateur.Dalle.typesDalles()) Historique.ignorerType(t, 2500); }

        @Override public boolean arret() { return arret.getAsBoolean(); }
        /** Lectures de suite hors de la salle (un rechargement de la salle donne un instant 0 / -1). */
        private int horsSalle = 0;
        private long horsDepuis = 0;
        @Override public boolean sortie() {
            int sc = Groupes.salleCourante();
            boolean hors = (sc > 0 && sc != salle) || !Salle.dansUneSalle() || sc <= 0;
            if (!hors) { horsSalle = 0; return false; }
            long t = System.currentTimeMillis();
            if (horsSalle++ == 0) horsDepuis = t;
            // une autre vraie salle vue 2 fois, ou plus de salle du tout pendant 3 s
            boolean sorti = (sc > 0 && sc != salle && horsSalle >= 2) || t - horsDepuis > 3000;
            if (sorti && horsSalle < 1000) { Journal.debug("tapis : sortie de la salle (" + sc + " au lieu de " + salle + ")."); horsSalle = 1000; }
            return sorti;
        }
        @Override public int sol(int x, int y) { return Salle.hauteurSol(x, y); }

        @Override public List<DalleMagique> dalles() {
            List<DalleMagique> r = new ArrayList<>();
            if (fd == null) return r;
            for (DalleMagique t : new DalleMagique[]{DalleMagique.HUIT, DalleMagique.SIX, DalleMagique.QUATRE,
                    DalleMagique.DEUX, DalleMagique.UN_DEUX, DalleMagique.UN})
                if (fd.getFloorTypeId(t.classe()) != null) r.add(t);
            return r;
        }

        @Override public int[] emprise(DalleMagique t) { return Generateur.Dalle.empriseDalle(t); }

        private Set<Integer> idsSols() {
            Set<Integer> s = new HashSet<>();
            for (HFloorItem it : Salle.sols()) s.add(it.getId());
            return s;
        }

        /**
         * Le nouveau mobi de ce type sur (x, y), au plus maxMs : son id, ou -1.
         * Salle.sols() ne rend que de vrais mobis : un id fictif (0x7FFF....)
         * apparu a la place n'est jamais pris pour la dalle ou le mobi pose.
         */
        private int attendreNouveau(Set<Integer> avant, int type, int x, int y, long maxMs) {
            long fin = System.currentTimeMillis() + maxMs;
            while (System.currentTimeMillis() < fin) {
                for (HFloorItem it : Salle.sols())
                    if (!avant.contains(it.getId()) && it.getTypeId() == type
                            && it.getTile().getX() == x && it.getTile().getY() == y) return it.getId();
                if (sortie()) return -1;
                Salle.sommeil(40);
            }
            fictifVu(type, x, y);
            return -1;
        }

        /** Diagnostic : un « mobi » a id fictif de ce type sur (x, y) (pas un vrai, ignore). */
        private void fictifVu(int type, int x, int y) {
            EtatSalle s = Salle.etat();
            if (s == null) return;
            try {
                for (HFloorItem it : s.getItems())
                    if (Salle.idFictif(it.getId()) && it.getTypeId() == type && it.getTile().getX() == x && it.getTile().getY() == y)
                        Journal.debug("tapis : id fictif " + it.getId() + " vu en (" + x + "," + y + ") à la place du mobi attendu : ignoré.");
            } catch (Throwable ignored) { }
        }

        @Override public Set<Integer> dallesPresentes() {
            Set<Integer> types = Generateur.Dalle.typesDalles(), r = new HashSet<>();
            for (HFloorItem it : Salle.sols()) if (types.contains(it.getTypeId())) r.add(it.getId());
            return r;
        }

        @Override public int poserDalle(DalleMagique t, int x, int y, int rot) {
            Integer type = fd == null ? null : fd.getFloorTypeId(t.classe());
            if (type == null || gp == null) return -2;
            boolean bcDabord = source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE;
            Set<Integer> avant = idsSols(), invAvant = new HashSet<>(invPris);
            Salle.espacer();
            // BC d'abord : (BC seul, puis inventaire seul) ; sinon inventaire puis BC
            String d = bcDabord ? Generateur.Dalle.envoyer(gp, type, t, x, y, rot, invPris, true, false)
                                : Generateur.Dalle.envoyer(gp, type, t, x, y, rot, invPris, true, true);
            if (d == null && bcDabord) d = Generateur.Dalle.envoyer(gp, type, t, x, y, rot, invPris, false, true);
            Salle.envoiFait();
            if (d == null) return -2;
            int id = attendreNouveau(avant, type, x, y, 4000);
            if (id < 0) {
                Journal.debug("tapis : dalle " + t.classe() + " en (" + x + "," + y + ") " + d + " refusée.");
                invPris.retainAll(invAvant);          // la dalle de l'inventaire n'est pas partie : reprise au reessai
            }
            return id;
        }

        @Override public void reglerDalle(int id, double h) {
            // le serveur ignore les reglages de dalle trop rapproches : 250 ms au moins
            long reste = 250 - (System.currentTimeMillis() - dernierReglage);
            if (reste > 0) Salle.sommeil(reste);
            Salle.espacer();
            Salle.envoyer(new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, id,
                    (int) Math.round(Math.max(0, h) * 100)));
            dernierReglage = System.currentTimeMillis();
        }

        @Override public double z(int id) {
            HFloorItem it = Salle.sol(id);
            return it == null ? Double.NaN : it.getTile().getZ();
        }

        @Override public int[] position(int id) {
            HFloorItem it = Salle.sol(id);
            return it == null ? null : new int[]{it.getTile().getX(), it.getTile().getY(), Salle.rotation(it)};
        }

        private boolean invPermis() { return source != Generateur.Source.BC; }
        private boolean bcPermis() { return source != Generateur.Source.INVENTAIRE; }
        private boolean bcDabord() { return source == Generateur.Source.BC || source == Generateur.Source.BC_PUIS_INVENTAIRE; }

        private int type(String classe) {
            return types.computeIfAbsent(classe, c -> { Integer t = fd.getFloorTypeId(c); return t == null ? -1 : t; });
        }

        /** Exemplaires de ce type dans l'inventaire ; -1 si l'inventaire n'est pas (encore) lu. */
        private int inventaire(int type) {
            try {
                Inventaire inv = gp.getInventory();
                if (inv == null || inv.getState() != Inventaire.Etat.LOADED) return -1;
                List<gearth.extensions.parsers.HInventoryItem> l = inv.getFloorItemsByType(type);
                return l == null ? 0 : l.size();
            } catch (Throwable t) { return -1; }
        }

        /** Exemplaires de l'inventaire pas encore reserves par horsSource (type -> reste). */
        private final Map<Integer, Integer> invRestant = new HashMap<>();

        @Override public String horsSource(Piece p) {
            if (gp == null || fd == null || p.classe == null) return null;
            int type = type(p.classe);
            if (type < 0) return INTROUVABLE;
            OffresBc.Offre o = bcPermis() ? PoseDirecte.offreSol(gp, p.classe) : null;
            if (bcDabord() && o != null) return null;
            // source « BC » seule : un mobi absent du BC est quand meme cherche dans l'inventaire
            if (invPermis() || (source == Generateur.Source.BC && o == null)) {
                int n = invRestant.computeIfAbsent(type, this::inventaire);
                if (n < 0) return null;                          // inventaire pas lu : la pose dira
                if (n > 0) { invRestant.put(type, n - 1); return null; }
            }
            if (o != null) return null;
            // inventaire permis mais vide pour ce mobi, et pas au BC : il manque
            if (bcPermis() && (invPermis() || source == Generateur.Source.BC)) return MANQUE;
            return bcPermis() ? PAS_AU_BC : INTROUVABLE;
        }

        @Override public boolean offreRefusee(Piece p) {
            int offre = PoseDirecte.derniereOffre;
            if (offre <= 0) return false;                         // la pose venait de l'inventaire
            OffresBc.refusee(offre);
            if (!invPermis() || p.classe == null) return true;
            int type = type(p.classe), n = type < 0 ? 0 : inventaire(type);
            if (n > 0) for (gearth.extensions.parsers.HInventoryItem it : gp.getInventory().getFloorItemsByType(type))
                if (it != null && !invPris.contains(it.getId())) return false;   // la reprise passera par l'inventaire
            return true;
        }

        @Override public int poser(Piece p, int rot) {
            if (gp == null || fd == null) return -2;
            int type = type(p.classe);
            if (type < 0) return -2;
            Set<Integer> avant = idsSols(), invAvant = new HashSet<>(invPris);
            PoseDirecte.Sol s = new PoseDirecte.Sol(p.classe, p.x, p.y, p.z, rot, p.etat);
            Generateur.Source src = source == Generateur.Source.BC && PoseDirecte.offreSol(gp, p.classe) == null
                    ? Generateur.Source.INVENTAIRE : source;          // pas au BC : depuis l'inventaire
            if (!PoseDirecte.envoyerSol(gp, type, s, src, invPris))          // espace par PoseDirecte
                return bcPermis() && PoseDirecte.offreSol(gp, p.classe) == null ? -3 : -2;
            int id = attendreNouveau(avant, type, p.x, p.y, 2500);
            if (id < 0) {
                Journal.debug("tapis : " + p.classe + " en (" + p.x + "," + p.y + ") refusé.");
                invPris.retainAll(invAvant);          // le mobi de l'inventaire n'est pas parti : reprise au reessai
            }
            return id;
        }

        @Override public boolean deplacer(int id, int x, int y, int rot) {
            Salle.espacer();
            Salle.deplacerSol(id, x, y, rot);
            long debut = System.currentTimeMillis(), surCase = -1;
            while (System.currentTimeMillis() - debut < 1500 && !sortie()) {
                int[] p = position(id);
                if (p == null) return false;
                if (p[0] == x && p[1] == y) {
                    if (p[2] == (rot & 7)) return true;
                    if (surCase < 0) surCase = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - surCase > 400) return false;   // arrive, dans une autre rotation
                }
                Salle.sommeil(40);
            }
            return false;
        }

        @Override public boolean preparerAltitude() {
            altitude = PoseHybride.altitudeDisponible(dire);
            return altitude;
        }

        @Override public boolean altitudePossible() { return altitude && !OutilMiroir.Altitude.rate(); }

        @Override public void altitude(int id, double z) {
            Salle.espacer();
            try { OutilMiroir.Altitude.ecrireVerifiee(id, z); } finally { Salle.envoiFait(); }
        }

        @Override public void attendreHauteurs(Map<Integer, Double> voulu) {
            PoseDirecte.suivre(() -> {
                int n = 0;
                for (Map.Entry<Integer, Double> e : voulu.entrySet()) {
                    double z = z(e.getKey());
                    if (!Double.isNaN(z) && Math.abs(z - e.getValue()) > TOLERANCE) n++;
                }
                return n;
            }, 500, 1500);
        }

        @Override public int etats(Map<Integer, String> voulus) {
            if (gp == null) return 0;
            return PoseDirecte.etats(gp, voulus, () -> arret() || sortie());
        }

        @Override public int ramasserDalles(List<Integer> ids, BiConsumer<Integer, Integer> progres) {
            debut();          // une pose de l'etape 3 (PoseCopie) a pu remettre les dalles dans l'historique
            return PoseTapis.ramasserDalles(ids, this::sortie, (f, n, passe) -> progres.accept(f, n));
        }

        @Override public Map<Piece, Integer> reprendre(List<Piece> pieces) {
            Map<Piece, Integer> r = new HashMap<>();
            List<PoseHybride.Piece> l = new ArrayList<>();
            List<Piece> par = new ArrayList<>();
            for (Piece p : pieces) {
                int ancien = p.obtenu >= 0 ? p.obtenu : p.id;
                String cls = p.classe, etat = p.etat;
                if (ancien >= 0) {
                    HFloorItem it = Salle.sol(ancien);
                    if (it == null) ancien = -1;
                    else if (cls == null) { cls = Salle.classe(it.getTypeId(), false); etat = Generateur.etatDe(it); }
                }
                if (cls == null) continue;
                l.add(PoseHybride.Piece.sol(cls, etat, p.x, p.y, Generateur.arrondi(Math.max(0, p.z)), p.rot, ancien, par.size()));
                par.add(p);
            }
            if (l.isEmpty()) return r;
            Groupes.Appartenance avant = null;
            if (calques) {
                List<Integer> anciens = new ArrayList<>();
                for (PoseHybride.Piece x : l) if (x.ancien >= 0) anciens.add(x.ancien);
                try { avant = Groupes.appartenance(anciens, List.of()); } catch (Throwable ignored) { }
            }
            Journal.debug("tapis : " + l.size() + " mobi(s) repris à la dalle.");
            if (suivi != null) suivi.progres(0, l.size(), "Reprise à la dalle : 0/" + l.size());
            PoseHybride.Bilan b = PoseHybride.reprendre(l, source, m -> Journal.debug("reprise : " + m),
                    () -> arret() || sortie(),
                    (f, n) -> { if (suivi != null) suivi.progres(f, n, "Reprise à la dalle : " + f + "/" + n); });
            reprise = b;
            if (b.raison != null) Journal.debug("tapis : reprise à la dalle impossible : " + b.raison);
            for (Map.Entry<Integer, Integer> e : b.cles.entrySet()) r.put(par.get(e.getKey()), e.getValue());
            if (calques && avant != null) {
                List<Integer> perdus = new ArrayList<>();
                for (int id : b.ramassesSols) if (!b.remplacesSols.containsKey(id)) perdus.add(id);
                try {
                    if (!perdus.isEmpty()) Groupes.oublierIds(perdus, List.of());
                    Groupes.remplacerIds(avant, b.remplacesSols, Map.of());
                } catch (Throwable t) { Journal.debug("tapis : calques après reprise : " + t); }
            }
            return r;
        }
    }

    /**
     * Le tapis dans la salle courante, avec l'historique groupe par l'appelant.
     * Raccourci : JeuSalle + debut/fin (dalles hors historique) + Journal.debug des dalles.
     */
    static Bilan executer(List<Piece> pieces, JeuSalle jeu, Suivi suivi, Runnable etape3) {
        OffresBc.oublier();                     // les offres refusees par une action precedente sont retentees
        jeu.debut();
        try {
            Bilan b = executer(pieces, (Jeu) jeu, suivi, etape3);
            Journal.debug(b.detailDalles());
            Journal.debug("tapis : " + b.detailReessais());
            if (b.sansAltitude) Journal.debug("tapis : @altitude introuvable, mobis en hauteur repris à la dalle par mobi.");
            return b;
        } finally {
            jeu.fin();
        }
    }

    /** Texte a ajouter au bilan quand @altitude manquait. */
    static final String SANS_ALTITUDE = " @altitude introuvable (règle-la une fois dans l'éditeur :wired) :"
            + " les mobis en hauteur sont passés par la dalle, un par un.";
}

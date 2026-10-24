package atelier;

import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mes depenses : le journal de ce qui fait bouger tes credits, duckets et
 * diamants (Valeur > Mes depenses, OngletDepenses).
 *
 * Le journal commence le jour ou cette fonction existe : le jeu ne donne pas
 * l'historique des achats. Seule exception, les ventes de la boutique troc :
 * le journal des transactions wired de l'appart (WiredTransactionGetRoomLogs)
 * les garde, et on peut les relire (lireVentesTroc).
 *
 * Les paquets ecoutes (noms du proxy, structure du client v36) :
 *   - porte-monnaie : CreditBalance (String solde), ActivityPoints (n x
 *     (int type, int solde)), HabboActivityPointNotification (int solde,
 *     int variation, int type) ; type 0 duckets, 5 diamants ;
 *   - catalogue : PurchaseFromCatalog (int page, int offre, String extra,
 *     int quantite) et PurchaseFromCatalogAsGift (int page, int offre,
 *     String extra, String destinataire, ...) vers le serveur ; PurchaseOK
 *     (l'offre : int id, String nom, bool location, int credits, int points,
 *     int typePoints, bool, n produits (String type, [int classe, String,
 *     int nombre, bool unique, [int, int]]), int, bool) ;
 *   - marche : MarketPlaceOffers (les offres vues, pour le nom et le prix),
 *     BuyMarketplaceOffer (int offre), MarketplaceBuyOfferResult (int
 *     resultat, int offre, int nouveauPrix, int offreDemandee ; 1 = achete),
 *     MarketPlaceOwnOffers (int creditsEnAttente, offres), et
 *     RedeemMarketplaceOfferCredits (recuperer les ventes) ;
 *   - echanges : TradingOpen, TradingItemList (int id, mobis, int nombre,
 *     int credits, puis l'autre), TradingCompleted. La liste peut ne pas
 *     detailler tous les mobis : le nombre annonce fait foi ;
 *   - achat au troc wired (InventoryMessages du client : onWiredTrade...) :
 *     WiredTradeItemsUpdate (meme liste ; le premier cote est toujours toi,
 *     le second ce que donnent les coffres) puis WiredTradeCompleted. Les
 *     pieces (classes CF_n_..., CFC_n_...) valent n credits ;
 *   - boutique troc : WithdrawCoinsFromChest (int coffre, int montant) vers
 *     le serveur, et le journal des transactions de l'appart ;
 *   - taches quotidiennes : ClaimDailyTask (int tache) vers le serveur.
 *
 * Le solde fait foi : une action connue (achat, vente, tache...) prend les
 * variations qui la suivent (ATTENTE_MS ; plusieurs paquets de solde pour un
 * seul achat sont additionnes) et celles arrivees juste avant. Une variation
 * qu'aucune action n'explique devient une ligne « Autre ».
 *
 * Tout se passe sur un seul fil (atelier-depenses) ; l'interception ne fait
 * que copier le paquet. Fichier : depenses.json dans le dossier de l'Atelier.
 */
final class Depenses {

    private Depenses() { }

    // ================================================================ donnees

    /** Les types de mouvement, dans l'ordre des filtres. */
    enum Genre {
        CATALOGUE("Achat catalogue", "#3E7CB1"), MARCHE("Marché", "#D9822B"), ECHANGE("Échange", "#2A9D8F"),
        TROC("Troc", "#8C5A2B"), TACHE("Tâche quotidienne", "#8E5BB5"), AUTRE("Autre", "#8A8678");
        final String texte, couleur;
        Genre(String t, String c) { texte = t; couleur = c; }
        static Genre de(String s) {
            for (Genre g : values()) if (g.name().equals(s)) return g;
            return AUTRE;
        }
    }

    /** Les monnaies suivies : index 0 credits, 1 duckets, 2 diamants. */
    static final int CREDITS = 0, DUCKETS = 1, DIAMANTS = 2, NB = 3;
    static final String[] SINGULIER = {"crédit", "ducket", "diamant"};
    static final String[] PLURIEL = {"crédits", "duckets", "diamants"};

    /** Le type de points du jeu (0 duckets, 5 diamants) vers notre index ; -1 sinon. */
    static int monnaieDePoints(int type) { return type == 0 ? DUCKETS : type == 5 ? DIAMANTS : -1; }

    /**
     * Une ligne du journal. Montants signes : negatif = depense, positif =
     * gain. transfert : argent deja compte ailleurs (credits retires du
     * coffre troc, deja comptes a chaque vente) : affiche, pas totalise.
     * classe / mur : le mobi principal, pour son icone (null sinon).
     */
    record Mouvement(long id, long t, Genre genre, String detail, String personne,
                     long credits, long duckets, long diamants, boolean transfert, String cle,
                     String classe, boolean mur) {
        long montant(int m) { return m == CREDITS ? credits : m == DUCKETS ? duckets : diamants; }
        boolean touche(int m) { return montant(m) != 0; }
        Mouvement avec(long[] v) {
            return new Mouvement(id, t, genre, detail, personne, v[0], v[1], v[2], transfert, cle, classe, mur);
        }
    }

    /** Les soldes connus (-1 : pas encore donne par le jeu) et leur date. */
    record Soldes(long[] valeurs, long le) {
        long de(int m) { return valeurs[m]; }
    }

    /** Ce qu'une action decrit : le texte, la personne, le mobi de l'icone. */
    private record Quoi(Genre genre, String detail, String personne, String classe, boolean mur) { }

    private static final String FICHIER = "depenses.json";
    /** Au-dela, les plus anciens sont oublies. */
    private static final int MAX = 20_000;
    /** Une action prend les variations de solde qui la suivent ce temps-la... */
    static final long ATTENTE_MS = 10_000;
    /** ...tant qu'elles s'enchainent (une variation de plus dans ce delai apres la precedente). */
    static final long ENCHAINE_MS = 3_000;
    /** Une variation arrivee AVANT son action (ordre des paquets) lui revient si l'action suit dans ce delai. */
    static final long AVANT_MS = 6_000;
    /** Une variation sans action devient « Autre » apres ce delai. */
    static final long ORPHELIN_MS = 3_000;

    private static final ScheduledExecutorService FIL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "atelier-depenses");
        t.setDaemon(true);
        return t;
    });

    // --- etat : seulement sur FIL
    private static final List<Mouvement> journal = new ArrayList<>();
    private static final Set<String> cles = new HashSet<>();
    private static long prochainId = 1, debut = 0;
    private static final long[] soldes = {-1, -1, -1};
    /** Solde recu depuis la connexion (le premier sert de reference, sans variation). */
    private static final boolean[] connu = new boolean[NB];
    private static long soldesLe = 0;
    private static final List<Attente> attentes = new ArrayList<>();
    private static final List<Orphelin> orphelins = new ArrayList<>();
    private static boolean sauvegardePrevue = false;

    // --- lu par l'interface
    private static volatile List<Mouvement> instantane = List.of();
    private static volatile Soldes soldesVus = new Soldes(new long[]{-1, -1, -1}, 0);
    private static volatile long debutVu = 0;
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    private static volatile boolean demarre = false;

    /** Les mouvements, du plus ancien au plus recent (copie). N'importe quel fil. */
    static List<Mouvement> mouvements() { return instantane; }
    static Soldes soldes() { return soldesVus; }
    /** Debut du suivi (epoque ms), 0 si rien encore. */
    static long debut() { return debutVu; }
    /** Appele (hors fil FX) a chaque changement du journal ou des soldes. */
    static void ecouter(Runnable r) { if (r != null) ecouteurs.add(r); }

    /** Retire une ligne (mauvaise attribution, essai...). N'importe quel fil. */
    static void retirer(long id) {
        FIL.execute(() -> {
            if (journal.removeIf(m -> m.id() == id)) changement();
        });
    }

    // ================================================================ action et variation (logique, fil FIL)

    /** Une action connue qui attend ses variations de solde. */
    private static final class Attente {
        final long id, creeLe;
        /** Par monnaie : 0 rien attendu, -1 une baisse, +1 une hausse, 2 l'une ou l'autre. */
        final int[] sens = new int[NB];
        final long[] prevu = new long[NB], reel = new long[NB];
        final boolean[] regle = new boolean[NB];
        final long[] derniere = new long[NB];
        /** Sans variation : la ligne disparait (ex. troc wired paye du porte-monnaie, mais rien n'a bouge). */
        boolean garderSansVariation = true;

        Attente(long id, long creeLe) { this.id = id; this.creeLe = creeLe; }

        /** Cette variation lui revient-elle ? Plusieurs variations s'additionnent tant qu'elles s'enchainent. */
        boolean accepte(int m, long d, long t) {
            if (sens[m] == 0) return false;
            if (sens[m] != 2 && Long.signum(d) != sens[m]) return false;
            if (t - creeLe > ATTENTE_MS) return false;
            if (regle[m] && t - derniere[m] > ENCHAINE_MS) return false;
            // une seule hausse et une seule baisse ne se melangent pas (sens 2 : le premier signe gagne)
            return !regle[m] || Long.signum(reel[m]) == Long.signum(d);
        }

        void prendre(int m, long d, long t) {
            reel[m] = regle[m] ? reel[m] + d : d;
            regle[m] = true;
            derniere[m] = Math.max(derniere[m], t);
        }

        long[] montants() {
            long[] v = new long[NB];
            for (int i = 0; i < NB; i++) v[i] = regle[i] ? reel[i] : prevu[i];
            return v;
        }

        boolean varie() { for (boolean b : regle) if (b) return true; return false; }
    }

    private record Orphelin(int monnaie, long montant, long t) { }

    /** Nouvelle ligne tout de suite (montants prevus), confirmee ensuite par les soldes. */
    private static void action(long t, Quoi q, long[] prevu, int[] sens, boolean transfert, boolean garderSansVariation) {
        Attente a = new Attente(prochainId++, t);
        System.arraycopy(prevu, 0, a.prevu, 0, NB);
        System.arraycopy(sens, 0, a.sens, 0, NB);
        a.garderSansVariation = garderSansVariation;
        // variations arrivees juste avant (le serveur envoie parfois le solde d'abord) ...
        for (Iterator<Orphelin> it = orphelins.iterator(); it.hasNext(); ) {
            Orphelin o = it.next();
            if (t - o.t() > AVANT_MS || !compatibleAvant(a, o.monnaie(), o.montant())) continue;
            a.prendre(o.monnaie(), o.montant(), t);
            it.remove();
        }
        // ... et celles deja passees en « Autre » (le paquet de l'action a tarde)
        for (Iterator<Mouvement> it = journal.iterator(); it.hasNext(); ) {
            Mouvement m = it.next();
            if (m.genre() != Genre.AUTRE || m.cle() != null || t - m.t() > AVANT_MS || m.t() > t) continue;
            int seule = -1, n = 0;
            for (int i = 0; i < NB; i++) if (m.montant(i) != 0) { seule = i; n++; }
            if (n != 1 || !compatibleAvant(a, seule, m.montant(seule))) continue;
            a.prendre(seule, m.montant(seule), t);
            it.remove();
        }
        ajouter(new Mouvement(a.id, t, q.genre(), q.detail(), q.personne(), 0, 0, 0, transfert, null,
                q.classe(), q.mur()).avec(a.montants()));
        attentes.add(a);
        FIL.schedule(() -> clore(a), ATTENTE_MS, TimeUnit.MILLISECONDS);
    }

    /** Une variation d'avant l'action : meme sens, et du meme signe que ce qui est deja pris. */
    private static boolean compatibleAvant(Attente a, int m, long d) {
        if (a.sens[m] == 0 || (a.sens[m] != 2 && Long.signum(d) != a.sens[m])) return false;
        return !a.regle[m] || Long.signum(a.reel[m]) == Long.signum(d);
    }

    private static void clore(Attente a) {
        attentes.remove(a);
        if (a.garderSansVariation || a.varie()) return;
        if (journal.removeIf(m -> m.id() == a.id)) changement();
    }

    /** Une variation de solde (n'importe quelle origine). */
    private static void variation(int m, long d, long t) {
        if (d == 0) return;
        // l'action la plus recente qui attend cette monnaie dans ce sens
        for (int i = attentes.size() - 1; i >= 0; i--) {
            Attente a = attentes.get(i);
            if (!a.accepte(m, d, t)) continue;
            a.prendre(m, d, t);
            remplacer(a.id, a.montants());
            return;
        }
        Orphelin o = new Orphelin(m, d, t);
        orphelins.add(o);
        FIL.schedule(() -> {
            if (!orphelins.remove(o)) return;     // pris par une action
            long[] v = new long[NB];
            v[m] = d;
            ajouter(new Mouvement(prochainId++, o.t(), Genre.AUTRE,
                    d < 0 ? "Dépense sans action connue" : "Gain sans action connue", null,
                    v[0], v[1], v[2], false, null, null, false));
        }, ORPHELIN_MS, TimeUnit.MILLISECONDS);
    }

    private static void ajouter(Mouvement m) {
        if (m.cle() != null && !cles.add(m.cle())) return;
        if (debut == 0 || m.t() < debut) debut = m.t();
        journal.add(m);
        // une vente du troc lue apres coup : sa place dans le temps
        if (journal.size() > 1 && journal.get(journal.size() - 2).t() > m.t())
            journal.sort(Comparator.comparingLong(Mouvement::t));
        while (journal.size() > MAX) journal.remove(0);
        changement();
    }

    private static void remplacer(long id, long[] v) {
        for (int i = journal.size() - 1; i >= 0; i--)
            if (journal.get(i).id() == id) { journal.set(i, journal.get(i).avec(v)); changement(); return; }
    }

    private static void solde(int m, long valeur, long t) {
        if (valeur < 0) return;
        long avant = soldes[m];
        boolean reference = !connu[m];
        soldes[m] = valeur;
        connu[m] = true;
        soldesLe = t;
        if (debut == 0) debut = t;
        if (!reference && avant >= 0) variation(m, valeur - avant, t);
        changement();
    }

    private static void changement() {
        instantane = List.copyOf(journal);
        soldesVus = new Soldes(soldes.clone(), soldesLe);
        debutVu = debut;
        if (!sauvegardePrevue) {
            sauvegardePrevue = true;
            FIL.schedule(() -> { sauvegardePrevue = false; sauver(); }, 1500, TimeUnit.MILLISECONDS);
        }
        for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { }
    }

    // ================================================================ totaux (logique pure)

    /** Depense (positive) et gain d'une monnaie sur des mouvements, transferts exclus. */
    record Total(long depense, long gain) {
        long net() { return gain - depense; }
    }

    static Total total(List<Mouvement> l, int m) {
        long d = 0, g = 0;
        for (Mouvement x : l) {
            if (x.transfert()) continue;
            long v = x.montant(m);
            if (v < 0) d -= v; else g += v;
        }
        return new Total(d, g);
    }

    /** Un mobi compte : nom, classe (icone), mural, nombre, valeur en credits d'une piece (0 sinon). */
    static final class Article {
        final String nom, classe;
        final boolean mur;
        final int valeur;
        int nombre;
        Article(String nom, String classe, boolean mur, int valeur) {
            this.nom = nom; this.classe = classe; this.mur = mur; this.valeur = valeur;
        }
    }

    /** « 3 × Trône, 1 × Lampe » : les noms groupes, dans l'ordre d'arrivee. Logique pure. */
    static String liste(Collection<Article> l) {
        StringBuilder b = new StringBuilder();
        for (Article a : l) {
            if (b.length() > 0) b.append(", ");
            b.append(PrixTexte.nombre(a.nombre)).append(" × ").append(a.nom);
        }
        return b.toString();
    }

    private static final Pattern PIECE = Pattern.compile("^CFC?_(\\d+)_", Pattern.CASE_INSENSITIVE);

    /** La valeur en credits d'une piece ou d'un lingot (CF_50_goldbar : 50) ; 0 pour un autre mobi. Logique pure. */
    static int valeurPiece(String classe) {
        if (classe == null) return 0;
        Matcher m = PIECE.matcher(classe);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return 0; }
    }

    /** Le solde dit par CreditBalance (« 1234.0 ») ; -1 s'il est illisible. Logique pure. */
    static long lireSolde(String s) {
        if (s == null) return -1;
        try { return (long) Math.floor(Double.parseDouble(s.trim().replace(',', '.'))); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Horodatage du journal wired : secondes ou millisecondes. Logique pure. */
    static long enMillis(long ts) { return ts > 0 && ts < 100_000_000_000L ? ts * 1000 : ts; }

    // ================================================================ fichier

    private static void charger() {
        JSONObject o = PrixFichier.lire(FICHIER, null);
        if (o == null) return;
        try {
            debut = o.optLong("debut", 0);
            JSONObject s = o.optJSONObject("soldes");
            if (s != null) {
                soldes[CREDITS] = s.optLong("credits", -1);
                soldes[DUCKETS] = s.optLong("duckets", -1);
                soldes[DIAMANTS] = s.optLong("diamants", -1);
                soldesLe = s.optLong("le", 0);
            }
            JSONArray a = o.optJSONArray("mouvements");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                String cle = j.optString("cle", null);
                Mouvement m = new Mouvement(prochainId++, j.getLong("t"), Genre.de(j.optString("type")),
                        j.optString("detail", ""), j.optString("personne", null),
                        j.optLong("credits"), j.optLong("duckets"), j.optLong("diamants"),
                        j.optBoolean("transfert"), cle, j.optString("classe", null), j.optBoolean("mur"));
                if (cle != null) cles.add(cle);
                journal.add(m);
            }
            journal.sort(Comparator.comparingLong(Mouvement::t));
        } catch (Throwable t) {
            Journal.debug("dépenses : fichier illisible : " + t);
        }
    }

    private static void sauver() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("debut", debut);
            JSONObject s = new JSONObject();
            s.put("credits", soldes[CREDITS]);
            s.put("duckets", soldes[DUCKETS]);
            s.put("diamants", soldes[DIAMANTS]);
            s.put("le", soldesLe);
            o.put("soldes", s);
            JSONArray a = new JSONArray();
            for (Mouvement m : journal) {
                JSONObject j = new JSONObject();
                j.put("t", m.t());
                j.put("type", m.genre().name());
                j.put("detail", m.detail());
                if (m.personne() != null) j.put("personne", m.personne());
                if (m.credits() != 0) j.put("credits", m.credits());
                if (m.duckets() != 0) j.put("duckets", m.duckets());
                if (m.diamants() != 0) j.put("diamants", m.diamants());
                if (m.transfert()) j.put("transfert", true);
                if (m.cle() != null) j.put("cle", m.cle());
                if (m.classe() != null) j.put("classe", m.classe());
                if (m.mur()) j.put("mur", true);
                a.put(j);
            }
            o.put("mouvements", a);
            if (!PrixFichier.ecrire(FICHIER, o)) Journal.debug("dépenses : sauvegarde impossible.");
        } catch (Throwable t) {
            Journal.debug("dépenses : sauvegarde impossible : " + t);
        }
    }

    // ================================================================ ecoutes

    private static final HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;

    /** Demarre le suivi (une fois) : fichier, puis les ecoutes des que le moteur existe. */
    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        FIL.execute(() -> { charger(); changement(); });
        Thread t = new Thread(() -> {
            Moteur gp;
            while ((gp = AtelierLauncher.moteur()) == null) dormir(1000);
            brancher(gp);
            dormir(4000);
            demanderSoldes(gp);
        }, "atelier-depenses-demarrage");
        t.setDaemon(true);
        t.start();
    }

    /** Le jeu redonne les soldes (credits, puis duckets et diamants). */
    private static void demanderSoldes(Moteur gp) {
        try { gp.sendToServer(new HPacket("GetCreditsInfo", S)); } catch (Throwable ignored) { }
    }

    /** Copie le paquet et le traite sur FIL (l'intercepteur ne fait rien d'autre). */
    private interface Lecteur { void lire(HPacket p, long t) throws Exception; }

    private static void ecoute(Moteur gp, HMessage.Direction d, String nom, Lecteur l) {
        try {
            gp.intercept(d, nom, m -> {
                HPacket copie = new HPacket(m.getPacket());
                long t = System.currentTimeMillis();
                FIL.execute(() -> {
                    try { copie.resetReadIndex(); l.lire(copie, t); }
                    catch (Throwable e) { Journal.debug("dépenses : " + nom + " illisible : " + e); }
                });
            });
        } catch (Throwable e) {
            Journal.debug("dépenses : écoute de " + nom + " impossible : " + e);
        }
    }

    private static long[] zeros() { return new long[NB]; }

    private static void brancher(Moteur gp) {
        try {
            gp.onConnect((hote, port, a, b, client) -> FIL.execute(() -> {
                Arrays.fill(connu, false);       // nouvelle session : le premier solde est une reference
                FIL.schedule(() -> { if (!connu[CREDITS]) demanderSoldes(gp); }, 6, TimeUnit.SECONDS);
            }));
        } catch (Throwable ignored) { }

        // --- porte-monnaie
        ecoute(gp, C, "CreditBalance", (p, t) -> solde(CREDITS, lireSolde(p.readString()), t));
        ecoute(gp, C, "ActivityPoints", (p, t) -> {
            int n = p.readInteger();
            for (int i = 0; i < n && i < 100; i++) {
                int type = p.readInteger(), v = p.readInteger();
                int m = monnaieDePoints(type);
                if (m >= 0) solde(m, v, t);
            }
        });
        ecoute(gp, C, "HabboActivityPointNotification", (p, t) -> {
            int v = p.readInteger(), change = p.readInteger(), type = p.readInteger();
            int m = monnaieDePoints(type);
            if (m < 0) return;
            if (!connu[m] && change != 0) {      // solde inconnu : la variation dite par le jeu
                soldes[m] = v - change;
                connu[m] = true;
            }
            solde(m, v, t);
        });

        // --- identite et noms des joueurs de l'appart
        ecoute(gp, C, "UserObject", (p, t) -> {
            int id = p.readInteger();
            String nom = NomSalle.utf8(p.readString());
            if (id > 0) { moi = id; monNom = nom; }
        });
        ecoute(gp, C, "Users", (p, t) -> lireUsers(p));

        // --- catalogue
        ecoute(gp, S, "PurchaseFromCatalog", (p, t) -> {
            p.readInteger();
            int offre = p.readInteger();
            p.readString();
            int n = p.readInteger();
            demandes.put(offre, new Demande(Math.max(1, n), null, t));
        });
        ecoute(gp, S, "PurchaseFromCatalogAsGift", (p, t) -> {
            p.readInteger();
            int offre = p.readInteger();
            p.readString();
            String pour = NomSalle.utf8(p.readString());
            demandes.put(offre, new Demande(1, pour, t));
        });
        ecoute(gp, C, "PurchaseOK", Depenses::surAchatCatalogue);

        // --- marche
        ecoute(gp, C, "MarketPlaceOffers", (p, t) -> lireOffresMarche(gp, p));
        ecoute(gp, C, "MarketPlaceOwnOffers", (p, t) -> lireMesOffres(gp, p));
        ecoute(gp, S, "BuyMarketplaceOffer", (p, t) -> { achatMarche = p.readInteger(); achatMarcheLe = t; });
        ecoute(gp, C, "MarketplaceBuyOfferResult", (p, t) -> {
            int resultat = p.readInteger(), offre = p.readInteger();
            p.readInteger();
            int demandee = p.readInteger();
            if (resultat != 1) return;
            OffreVue o = offresVues.get(demandee);
            if (o == null) o = offresVues.get(offre);
            if (o == null && t - achatMarcheLe < 30_000) o = offresVues.get(achatMarche);
            long[] prevu = zeros();
            int[] sens = new int[NB];
            sens[CREDITS] = -1;
            if (o != null) prevu[CREDITS] = -o.prix();
            action(t, new Quoi(Genre.MARCHE, "Achat : " + (o == null ? "un mobi" : "1 × " + o.a().nom), null,
                    o == null ? null : o.a().classe, o != null && o.a().mur), prevu, sens, false, true);
        });
        ecoute(gp, S, "RedeemMarketplaceOfferCredits", (p, t) -> {
            long[] prevu = zeros();
            int[] sens = new int[NB];
            sens[CREDITS] = 1;
            prevu[CREDITS] = Math.max(0, enAttenteMarche);
            Article premier = ventesMarche.isEmpty() ? null : ventesMarche.values().iterator().next();
            String d = ventesMarche.isEmpty() ? "Crédits des ventes récupérés" : "Ventes récupérées : " + liste(ventesMarche.values());
            action(t, new Quoi(Genre.MARCHE, d, null, premier == null ? null : premier.classe, premier != null && premier.mur),
                    prevu, sens, false, false);
            enAttenteMarche = 0;
            ventesMarche = new LinkedHashMap<>();
        });

        // --- echanges entre joueurs
        ecoute(gp, C, "TradingOpen", (p, t) -> {
            int a = p.readInteger();
            p.readInteger();
            int b = p.readInteger();
            echangeAvec = estMoi(a) ? b : estMoi(b) ? a : b;
            listeEchange = null;
        });
        ecoute(gp, C, "TradingItemList", (p, t) -> listeEchange = lireListe(gp, p));
        ecoute(gp, C, "TradingCompleted", (p, t) -> {
            ListeEchange l = listeEchange;
            listeEchange = null;
            if (l == null) return;
            Cote moiC = l.cote(true), autre = l.cote(false);
            String personne = autre.id == null ? nomDe(echangeAvec) : nomDe(autre.id);
            long[] v = zeros();
            v[CREDITS] = autre.valeur() - moiC.valeur();
            Article icone = autre.premier() != null ? autre.premier() : moiC.premier();
            ajouter(new Mouvement(prochainId++, t, Genre.ECHANGE, detailEchange(moiC, autre), personne,
                    v[0], v[1], v[2], false, null, icone == null ? null : icone.classe, icone != null && icone.mur));
        });

        // --- achat au troc wired d'un autre joueur
        ecoute(gp, C, "WiredTradeItemsUpdate", (p, t) -> listeTroc = lireListe(gp, p));
        ecoute(gp, C, "WiredTradeCompleted", (p, t) -> surAchatTroc(t));
        // --- boutique troc : les credits du coffre vont au porte-monnaie
        ecoute(gp, S, "WithdrawCoinsFromChest", (p, t) -> {
            p.readInteger();
            int montant = p.readInteger();
            long[] prevu = zeros();
            int[] sens = new int[NB];
            sens[CREDITS] = 1;
            prevu[CREDITS] = Math.max(0, montant);
            action(t, new Quoi(Genre.TROC, "Crédits retirés du coffre de ta boutique troc", null, null, false),
                    prevu, sens, true, true);
        });
        // --- taches quotidiennes : la recompense reclamee
        ecoute(gp, S, "ClaimDailyTask", (p, t) -> tacheReclamee(p.readInteger(), t));
        // les reclamations faites par l'Atelier lui-meme : la tache passe a « reclamee »
        TachesQuotidiennes.ecouter(() -> {
            long t = System.currentTimeMillis();
            List<long[]> l = new ArrayList<>();
            try { for (TachesQuotidiennes.Tache x : TachesQuotidiennes.taches()) l.add(new long[]{x.id, x.statut}); }
            catch (Throwable ignored) { }
            FIL.execute(() -> {
                for (long[] x : l) {
                    Integer avant = statutsTaches.put(x[0], (int) x[1]);
                    if (avant != null && avant != TachesQuotidiennes.RECLAMEE && x[1] == TachesQuotidiennes.RECLAMEE)
                        tacheReclamee((int) x[0], t);
                }
            });
        });
        // --- journal des transactions wired (lecture des ventes du troc)
        gp.intercept(C, "WiredTransactionLogList", m -> {
            CompletableFuture<HPacket> f = attenteListe;
            if (f == null) return;
            m.setBlocked(true);
            f.complete(new HPacket(m.getPacket()));
        });
        gp.intercept(C, "WiredTransactionLogDetails", m -> {
            CompletableFuture<HPacket> f = attenteDetail;
            if (f == null) return;
            m.setBlocked(true);
            f.complete(new HPacket(m.getPacket()));
        });
        Journal.debug("dépenses : écoutes installées.");
    }

    // ================================================================ taches quotidiennes (fil FIL)

    private static final Map<Long, Integer> statutsTaches = new HashMap<>();
    private static final Map<Integer, Long> tachesReclamees = new HashMap<>();

    /** Une recompense reclamee (une fois par tache, par l'ecoute ou par le changement de statut). */
    private static void tacheReclamee(int id, long t) {
        Long avant = tachesReclamees.get(id);
        if (avant != null && t - avant < 60_000) return;
        tachesReclamees.put(id, t);
        String nom = null;
        try {
            for (TachesQuotidiennes.Tache x : TachesQuotidiennes.taches())
                if ((int) x.id == id) { nom = x.action().nom(); break; }
        } catch (Throwable ignored) { }
        int[] sens = {1, 1, 1};
        action(t, new Quoi(Genre.TACHE, nom == null || nom.startsWith("Tâche ") ? "Récompense d'une tâche quotidienne"
                : "Récompense : « " + nom + " »", null, null, false), zeros(), sens, false, true);
    }

    // ================================================================ catalogue (fil FIL)

    private record Demande(int quantite, String pour, long t) { }
    private static final Map<Integer, Demande> demandes = new HashMap<>();

    private static void surAchatCatalogue(HPacket p, long t) {
        Moteur gp = AtelierLauncher.moteur();
        int offre = p.readInteger();
        String nomOffre = p.readString();
        p.readBoolean();
        int credits = p.readInteger(), points = p.readInteger(), typePoints = p.readInteger();
        p.readBoolean();
        int n = p.readInteger();
        Map<String, Article> noms = new LinkedHashMap<>();
        Demande dm = demandes.remove(offre);
        int qte = dm != null && t - dm.t() < 60_000 ? dm.quantite() : 1;
        for (int i = 0; i < n && i < 200; i++) {
            String type = p.readString();
            if ("b".equals(type)) {
                p.readString();
                noms.computeIfAbsent("Badge", k -> new Article(k, null, false, 0)).nombre += qte;
                continue;
            }
            int classeId = p.readInteger();
            p.readString();
            int nombre = p.readInteger();
            if (p.readBoolean()) { p.readInteger(); p.readInteger(); }
            boolean mur = "i".equalsIgnoreCase(type);
            Article a;
            if ("s".equalsIgnoreCase(type) || mur) a = article(gp, mur, classeId);
            else a = new Article(nomOffre == null || nomOffre.isBlank() ? "Article" : nomOffre, null, false, 0);
            noms.computeIfAbsent(a.nom, k -> a).nombre += Math.max(1, nombre) * qte;
        }
        if (noms.isEmpty()) {
            Article a = new Article(nomOffre == null || nomOffre.isBlank() ? "Article" : nomOffre, null, false, 0);
            a.nombre = qte;
            noms.put(a.nom, a);
        }
        long[] prevu = zeros();
        int[] sens = new int[NB];
        String autres = "";
        if (credits > 0) { prevu[CREDITS] = -(long) credits * qte; sens[CREDITS] = -1; }
        if (points > 0) {
            int m = monnaieDePoints(typePoints);
            if (m >= 0) { prevu[m] = -(long) points * qte; sens[m] = -1; }
            else autres = " · " + PrixTexte.nombre((long) points * qte) + " points du jeu (type " + typePoints + ")";
        }
        Article premier = noms.values().iterator().next();
        String d = liste(noms.values()) + (dm != null && dm.pour() != null ? " (cadeau pour " + dm.pour() + ")" : "") + autres;
        action(t, new Quoi(Genre.CATALOGUE, d, null, premier.classe, premier.mur), prevu, sens, false, true);
    }

    /** Un mobi par son type : nom du jeu, classe, valeur s'il s'agit d'une piece. */
    private static Article article(Moteur gp, boolean mur, int typeId) {
        String classe = PrixTexte.classe(gp, mur, typeId);
        return new Article(PrixTexte.nom(gp, mur, typeId, classe), classe, mur, mur ? 0 : valeurPiece(classe));
    }

    // ================================================================ marche (fil FIL)

    private record OffreVue(Article a, int prix) { }
    private static final Map<Integer, OffreVue> offresVues = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, OffreVue> e) { return size() > 3000; }
    };
    private static int achatMarche = -1;
    private static long achatMarcheLe = 0;
    private static long enAttenteMarche = 0;
    private static Map<String, Article> ventesMarche = new LinkedHashMap<>();

    /** Une offre du marche : id, statut, mobi, prix. */
    private interface SurOffre { void offre(int id, int statut, Article a, int prix); }

    private static void lireOffres(Moteur gp, HPacket p, boolean miennes, SurOffre f) {
        int n = p.readInteger();
        for (int i = 0; i < n && i < 5000; i++) {
            int id = p.readInteger(), statut = p.readInteger(), type = p.readInteger();
            int typeId;
            boolean mur = false;
            if (type == 1 || type == 4) {
                typeId = p.readInteger();
                IStuffData.read(p);
                if (type == 4) p.readBoolean();
            } else if (type == 2) {
                typeId = p.readInteger();
                p.readString();
                mur = true;
            } else if (type == 3) {
                typeId = p.readInteger();
                p.readInteger();
                p.readInteger();
            } else return;
            int prix = p.readInteger();
            p.readInteger();                       // minutes restantes
            p.readInteger();                       // prix moyen
            if (miennes) { if (statut == 2 || statut == 3) p.readLong(); }
            else p.readInteger();                  // nombre d'offres
            f.offre(id, statut, article(gp, mur, typeId), prix);
        }
    }

    private static void lireOffresMarche(Moteur gp, HPacket p) {
        lireOffres(gp, p, false, (id, statut, a, prix) -> offresVues.put(id, new OffreVue(a, prix)));
    }

    private static void lireMesOffres(Moteur gp, HPacket p) {
        long attente = p.readInteger();
        Map<String, Article> vendus = new LinkedHashMap<>();
        lireOffres(gp, p, true, (id, statut, a, prix) -> {
            if (statut == 2) vendus.computeIfAbsent(a.nom, k -> a).nombre++;
        });
        enAttenteMarche = attente;
        ventesMarche = vendus;
    }

    // ================================================================ echanges (fil FIL)

    private static volatile int moi = -1;
    private static volatile String monNom = null;
    private static int echangeAvec = -1;
    /** id ou index dans l'appart -> pseudo (paquet Users). */
    private static final Map<Integer, String> pseudos = new HashMap<>(), pseudosParIndex = new HashMap<>();
    private static int monIndex = -1;

    private static int moi() { return moi > 0 ? moi : PlanteSuivi.monId; }

    private static String monNom() {
        if (monNom != null) return monNom;
        return PlanteSuivi.monNom != null ? PlanteSuivi.monNom : Patrimoine.pseudo();
    }

    private static boolean estMoi(Integer id) { return id != null && id > 0 && (id == moi() || id == monIndex); }

    private static String nomDe(Integer id) {
        if (id == null || id < 0) return null;
        String n = pseudos.get(id);
        return n != null ? n : pseudosParIndex.get(id);
    }

    /** Users : seulement les avatars (id, pseudo, index) ; format actuel puis ancien. */
    private static void lireUsers(HPacket p) {
        int debutLecture = p.getReadIndex();
        if (!lireUsers(p, debutLecture, true)) lireUsers(p, debutLecture, false);
    }

    private static boolean lireUsers(HPacket p, int debutLecture, boolean avecRang) {
        Map<Integer, String> ids = new HashMap<>(), index = new HashMap<>();
        try {
            p.setReadIndex(debutLecture);
            int n = p.readInteger();
            if (n < 0 || n > 10_000) return false;
            for (int i = 0; i < n; i++) {
                int id = p.readInteger();
                String nom = NomSalle.utf8(p.readString());
                p.readString(); p.readString();
                int idx = p.readInteger();
                p.readInteger(); p.readInteger(); p.readString(); p.readInteger();
                int type = p.readInteger();
                switch (type) {
                    case 1:
                        p.readString(); p.readInteger(); p.readInteger();
                        p.readString(); p.readString(); p.readInteger(); p.readBoolean();
                        if (avecRang) p.readInteger();
                        ids.put(id, nom);
                        index.put(idx, nom);
                        break;
                    case 2:
                        p.readInteger(); p.readInteger(); p.readString(); p.readInteger();
                        for (int k = 0; k < 6; k++) p.readBoolean();
                        p.readInteger(); p.readString();
                        break;
                    case 3:
                        break;
                    case 4: {
                        p.readString(); p.readInteger(); p.readString();
                        int k = p.readInteger();
                        if (k < 0 || k > 1000) return false;
                        for (int j = 0; j < k; j++) p.readShort();
                        break;
                    }
                    default:
                        return false;
                }
            }
            if (p.getReadIndex() != p.getBytesLength()) return false;
        } catch (Throwable t) {
            return false;
        }
        pseudos.putAll(ids);
        pseudosParIndex.putAll(index);
        String moiNom = monNom();
        for (Map.Entry<Integer, String> e : index.entrySet())
            if (moiNom != null && moiNom.equalsIgnoreCase(e.getValue())) monIndex = e.getKey();
        if (pseudos.size() > 5000) pseudos.clear();
        if (pseudosParIndex.size() > 5000) pseudosParIndex.clear();
        return true;
    }

    /** Un cote d'un echange : son id, ses mobis groupes par nom, ses credits (hors pieces). */
    static final class Cote {
        Integer id;
        final Map<String, Article> articles = new LinkedHashMap<>();
        long credits;

        /** Les pieces et lingots (valeur en credits). */
        List<Article> pieces() { return articles.values().stream().filter(a -> a.valeur > 0).toList(); }
        /** Les autres mobis. */
        List<Article> mobis() { return articles.values().stream().filter(a -> a.valeur == 0).toList(); }
        /** Credits + valeur des pieces. */
        long valeur() { long v = credits; for (Article a : pieces()) v += (long) a.valeur * a.nombre; return v; }
        boolean vide() { return articles.isEmpty() && credits == 0; }
        /** Le premier mobi (pas une piece), sinon la premiere piece ; null si rien. */
        Article premier() {
            List<Article> m = mobis();
            return !m.isEmpty() ? m.get(0) : articles.isEmpty() ? null : articles.values().iterator().next();
        }
    }

    private record ListeEchange(Cote premier, Cote second) {
        /** Mon cote (moi = true) ou l'autre ; sans identite connue, je suis le premier. */
        Cote cote(boolean moiDemande) {
            boolean premierEstMoi = !estMoi(second.id) || estMoi(premier.id);
            return premierEstMoi == moiDemande ? premier : second;
        }
    }

    private static ListeEchange lireListe(Moteur gp, HPacket p) {
        Cote a = lireCote(gp, p), b = lireCote(gp, p);
        return new ListeEchange(a, b);
    }

    /**
     * int id, n mobis, int nombre, int credits. Mobi : int, String type, int,
     * int classe, int, bool, stuff, 3 int, [int si sol]. La liste peut ne
     * montrer qu'une partie des mobis (piles) : avec un seul type de mobi, le
     * nombre annonce fait foi.
     */
    private static Cote lireCote(Moteur gp, HPacket p) {
        Cote c = new Cote();
        c.id = p.readInteger();
        int n = p.readInteger(), lus = 0;
        for (int i = 0; i < n && i < 10_000; i++) {
            p.readInteger();
            String type = p.readString();
            p.readInteger();
            int classe = p.readInteger();
            p.readInteger();
            p.readBoolean();
            IStuffData.read(p);
            p.readInteger(); p.readInteger(); p.readInteger();
            boolean mur = "I".equalsIgnoreCase(type);
            if (!mur) p.readInteger();
            Article a = article(gp, mur, classe);
            c.articles.computeIfAbsent(a.nom, k -> a).nombre++;
            lus++;
        }
        int annonce = p.readInteger();
        c.credits = p.readInteger();
        if (annonce > lus && c.articles.size() == 1) c.articles.values().iterator().next().nombre = annonce;
        return c;
    }

    private static String detailEchange(Cote moiC, Cote autre) {
        return "Donné : " + contenu(moiC) + " · Reçu : " + contenu(autre);
    }

    /** « 2 × Trône, 3 crédits » ; « rien ». */
    private static String contenu(Cote c) {
        String s = liste(c.articles.values());
        if (c.credits > 0) s = (s.isEmpty() ? "" : s + ", ") + PrixTexte.nombre(c.credits) + (c.credits > 1 ? " crédits" : " crédit");
        return s.isEmpty() ? "rien" : s;
    }

    private static volatile ListeEchange listeEchange = null, listeTroc = null;

    /**
     * WiredTradeCompleted : le premier cote est toi (ce que tu donnes), le
     * second ce que donnent les coffres. Les deux remplis : un achat au troc
     * d'un autre joueur (le vendeur est le proprietaire de l'appart). Un seul
     * cote : un depot ou un retrait dans tes coffres, pas une depense.
     */
    private static void surAchatTroc(long t) {
        ListeEchange l = listeTroc;
        listeTroc = null;
        if (l == null) return;
        Cote donne = l.premier(), recu = l.second();
        if (donne.vide() || recu.vide()) return;
        List<Article> achetes = recu.mobis();
        String quoi = achetes.isEmpty() ? contenu(recu) : liste(achetes);
        String paye = contenu(donne);
        String appart = NomSalle.nom();
        String d = "Achat au troc : " + quoi + " · Payé : " + paye
                + (appart == null || appart.isBlank() ? "" : " · Appart « " + appart + " »");
        String vendeur = NomSalle.proprietaire();
        Article icone = recu.premier();
        long valeur = donne.valeur() - recu.valeur();
        long[] prevu = zeros();
        int[] sens = new int[NB];
        Quoi q = new Quoi(Genre.TROC, d, vendeur, icone == null ? null : icone.classe, icone != null && icone.mur);
        if (valeur != 0) {
            // paye en pieces ou lingots : leur valeur en credits, le porte-monnaie ne bouge pas
            prevu[CREDITS] = -valeur;
            ajouter(new Mouvement(prochainId++, t, q.genre(), q.detail(), q.personne(), prevu[0], 0, 0, false, null,
                    q.classe(), q.mur()));
        } else {
            // paye autrement (porte-monnaie ?) : la variation qui suit
            sens[CREDITS] = -1; sens[DUCKETS] = -1; sens[DIAMANTS] = -1;
            action(t, q, prevu, sens, false, true);
        }
    }

    // ================================================================ ventes du troc (journal wired)

    private static volatile CompletableFuture<HPacket> attenteListe = null, attenteDetail = null;
    private static volatile boolean lectureEnCours = false;
    private static final int PAGE = 50, PAGES_MAX = 20, DETAILS_MAX = 150;

    /**
     * Relit le journal des transactions wired de l'appart (il faut etre dans
     * l'appart de ta boutique troc) : chaque achat d'un autre joueur qui a
     * mis des credits dans un coffre devient une vente « Troc ». Les ventes
     * deja notees ne sont pas recomptees. Fil de travail ; fin recoit le
     * bilan (ou l'erreur) en francais.
     */
    static void lireVentesTroc(Consumer<String> fin) {
        if (lectureEnCours) { fin.accept("Lecture des ventes du troc déjà en cours."); return; }
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null || !Salle.dansUneSalle()) { fin.accept("Entre dans l'appart de ta boutique troc d'abord."); return; }
        String proprio = NomSalle.proprietaire(), pseudo = monNom();
        if (proprio != null && pseudo != null && !proprio.equalsIgnoreCase(pseudo)) {
            fin.accept("Ce n'est pas ton appart : seules tes ventes comptent.");
            return;
        }
        String appart = NomSalle.nom();
        lectureEnCours = true;
        Avancement.Suivi suivi = Avancement.debut("Ventes du troc");
        try {
            record Info(long id, long t, int userId, String user, int pieces, int mobis) { }
            List<Info> infos = new ArrayList<>();
            int total;
            for (int page = 1; page <= PAGES_MAX; page++) {
                HPacket r = demander(gp, new HPacket("WiredTransactionGetRoomLogs", S, PAGE, page), true);
                if (r == null) {
                    if (page == 1) {
                        suivi.fin(Avancement.Issue.ECHEC);
                        fin.accept("Le jeu n'a pas donné le journal du troc : il faut être dans l'appart de ta boutique.");
                        return;
                    }
                    break;
                }
                r.resetReadIndex();
                r.readInteger(); r.readLong();
                total = r.readInteger();
                r.readInteger(); r.readInteger();
                int n = r.readInteger();
                for (int i = 0; i < n && i < 1000; i++) {
                    long id = r.readLong();
                    r.readInteger(); r.readInteger(); r.readString();
                    int userId = r.readInteger();
                    String user = NomSalle.utf8(r.readString());
                    long ts = r.readLong();
                    r.readString();
                    r.readInteger();
                    int retraitMobis = r.readInteger();
                    r.readInteger();
                    r.readInteger();
                    int depotPieces = r.readInteger();
                    infos.add(new Info(id, enMillis(ts), userId, user, depotPieces, retraitMobis));
                }
                suivi.texte("Ventes du troc : page " + page);
                if (n < PAGE || (total >= 0 && page * PAGE >= total)) break;
            }
            // les ventes : un autre joueur a depose des credits ; pas encore notees
            List<Info> ventes = new ArrayList<>();
            Set<String> deja = new HashSet<>();
            for (Mouvement m : instantane) if (m.cle() != null) deja.add(m.cle());
            int moiId = moi();
            for (Info i : infos)
                if (i.pieces() > 0 && i.userId() != moiId && !deja.contains("troc:" + i.id())) ventes.add(i);
            int fait = 0;
            for (Info i : ventes) {
                if (suivi.arretDemande()) break;
                String d = "Vente au troc : " + (i.mobis() > 0 ? Ui.accorder(i.mobis() + " mobi(s)") : "mobis");
                Article icone = null;
                if (fait < DETAILS_MAX) {
                    HPacket r = demander(gp, new HPacket("WiredTransactionGetLogDetails", S).appendLong(i.id()), false);
                    List<Article> l = r == null ? null : mobisRetires(gp, r);
                    if (l != null && !l.isEmpty()) { d = "Vente au troc : " + liste(l); icone = l.get(0); }
                }
                if (appart != null && !appart.isBlank()) d += " · Appart « " + appart + " »";
                final String detail = d;
                final Article ic = icone;
                FIL.execute(() -> ajouter(new Mouvement(prochainId++, i.t(), Genre.TROC, detail, i.user(),
                        i.pieces(), 0, 0, false, "troc:" + i.id(), ic == null ? null : ic.classe, ic != null && ic.mur)));
                fait++;
                suivi.compte(fait, ventes.size());
            }
            suivi.fin(fait, 0, suivi.arretDemande());
            fin.accept(ventes.isEmpty() ? "Aucune nouvelle vente dans le journal du troc."
                    : Ui.accorder(fait + " vente(s) du troc ajoutée(s)."));
        } catch (Throwable t) {
            suivi.fin(Avancement.Issue.ECHEC);
            Journal.debug("dépenses : journal du troc illisible : " + t);
            fin.accept("Journal du troc illisible.");
        } finally {
            attenteListe = null;
            attenteDetail = null;
            lectureEnCours = false;
        }
    }

    /** Envoie une demande et attend sa reponse (bloquee : le jeu ne l'a pas demandee) ; null sans reponse. */
    private static HPacket demander(Moteur gp, HPacket demande, boolean liste) {
        CompletableFuture<HPacket> f = new CompletableFuture<>();
        if (liste) attenteListe = f; else attenteDetail = f;
        try {
            if (!gp.sendToServer(demande)) return null;
            return f.get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return null;
        } finally {
            if (liste) attenteListe = null; else attenteDetail = null;
            dormir(150);                    // pas de rafale vers le serveur
        }
    }

    /** WiredTransactionLogDetails : les mobis retires des coffres (ce que l'acheteur a recu). */
    private static List<Article> mobisRetires(Moteur gp, HPacket r) {
        try {
            r.resetReadIndex();
            r.readLong(); r.readInteger(); r.readInteger(); r.readString(); r.readInteger(); r.readString();
            r.readLong(); r.readString();
            for (int k = 0; k < 5; k++) r.readInteger();
            int n = r.readInteger();
            for (int i = 0; i < n && i < 1000; i++) r.readInteger();
            n = r.readInteger();                                   // deposes
            for (int i = 0; i < n && i < 1000; i++) { r.readBoolean(); r.readInteger(); r.readString(); r.readInteger(); }
            n = r.readInteger();                                   // retires
            Map<String, Article> noms = new LinkedHashMap<>();
            for (int i = 0; i < n && i < 1000; i++) {
                boolean mur = r.readBoolean();
                int type = r.readInteger();
                r.readString();
                int q = r.readInteger();
                Article a = article(gp, mur, type);
                noms.computeIfAbsent(a.nom, k -> a).nombre += Math.max(1, q);
            }
            return new ArrayList<>(noms.values());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}

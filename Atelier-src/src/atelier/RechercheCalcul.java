package atelier;

import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HPacket;

import java.util.*;

/**
 * Recherche de mobis : la logique pure (sans reseau ni JavaFX), testee par
 * RechercheTest.
 *
 *   - lecture de NavigatorSearchResultBlocks (liste des apparts d'une
 *     categorie du navigateur) ;
 *   - reperage de la categorie « Troc » parmi les blocs ;
 *   - choix des apparts a visiter (ouverts seulement, avec du monde si demande,
 *     limite) ;
 *   - comptage des mobis cherches poses dans un appart, par type ;
 *   - lecture des offres de la place du marche (MarketPlaceOffers) et d'une
 *     offre du catalogue (ProductOffer) ;
 *   - agregation et verdict « quantite voulue atteignable ? ».
 *
 * Formats lus dans le client Flash (parseurs du navigateur, du marche et du
 * catalogue) :
 *
 *   NavigatorSearchResultBlocks : String code, String filtre, int nBlocs,
 *     nBlocs x (String code, String texte, int action, boolean ferme,
 *               int vue, int nApparts, nApparts x Appart)
 *   Appart : int id, String nom, int idProprio, String proprio, int porte
 *     (0 ouvert, 1 sonnette, 2 mot de passe, 3 invisible), int presents,
 *     int maximum, String description, int troc, int score, int classement,
 *     int categorie, int nTags, nTags x String, int drapeaux,
 *     [&1 String image], [&2 int idGroupe, String groupe, String badge],
 *     [&4 String evenement, String description, int minutes]
 *   MarketPlaceOffers : int n, n x (int idOffre, int statut, int type
 *     (1 sol, 2 mur, 3 sol unique, 4 sol utilisable), [1/4 : int typeId,
 *     stuffdata, 4 : boolean], [2 : int typeId, String], [3 : int typeId,
 *     int numero, int serie], int prix, int minutes, int prixMoyen,
 *     int nombreOffres), int total
 *   ProductOffer : int idOffre, String nom, boolean location, int credits,
 *     int points, int typePoints, ...
 */
final class RechercheCalcul {

    private RechercheCalcul() { }

    // ------------------------------------------------------------ navigateur

    /** Un appart de la liste du navigateur. */
    record Appart(int id, String nom, int proprioId, String proprio, int porte, int presents,
                  int maximum, int troc, int categorie) {
        boolean ouvert() { return porte == 0; }
    }

    /** Un bloc de resultats (une categorie, une liste). */
    record Bloc(String code, String texte, List<Appart> apparts) { }

    /** Une reponse du navigateur : le code cherche, et ses blocs. */
    record Blocs(String code, String filtre, List<Bloc> blocs) {
        /** Tous les apparts, sans doublon, dans l'ordre. */
        List<Appart> apparts() {
            Map<Integer, Appart> m = new LinkedHashMap<>();
            for (Bloc b : blocs) for (Appart a : b.apparts()) m.putIfAbsent(a.id(), a);
            return new ArrayList<>(m.values());
        }
    }

    /** Lit NavigatorSearchResultBlocks ; null si le paquet n'a pas ce format. */
    static Blocs lireBlocs(HPacket brut) {
        try {
            HPacket p = new HPacket(brut);
            p.setReadIndex(6);
            String code = texte(p), filtre = texte(p);
            int n = p.readInteger();
            if (n < 0 || n > 200) return null;
            List<Bloc> blocs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String bc = texte(p), bt = texte(p);
                p.readInteger();                 // actions permises
                p.readBoolean();                 // ferme d'office
                p.readInteger();                 // vue (liste, vignettes)
                int na = p.readInteger();
                if (na < 0 || na > 5000) return null;
                List<Appart> l = new ArrayList<>(na);
                for (int j = 0; j < na; j++) l.add(lireAppart(p));
                blocs.add(new Bloc(bc, bt, l));
            }
            return new Blocs(code, filtre, blocs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Appart lireAppart(HPacket p) {
        int id = p.readInteger();
        String nom = texte(p);
        int proprioId = p.readInteger();
        String proprio = texte(p);
        int porte = p.readInteger(), presents = p.readInteger(), maximum = p.readInteger();
        texte(p);                                // description
        int troc = p.readInteger();
        p.readInteger();                         // score
        p.readInteger();                         // classement
        int categorie = p.readInteger();
        int nt = p.readInteger();
        if (nt < 0 || nt > 50) throw new IllegalStateException("tags");
        for (int i = 0; i < nt; i++) texte(p);
        int d = p.readInteger();
        if ((d & 1) != 0) texte(p);
        if ((d & 2) != 0) { p.readInteger(); texte(p); texte(p); }
        if ((d & 4) != 0) { texte(p); texte(p); p.readInteger(); }
        return new Appart(id, nom, proprioId, proprio, porte, presents, maximum, troc, categorie);
    }

    /** Texte lu et repare (le proxy lit en Latin-1 ce que le jeu ecrit en UTF-8). */
    private static String texte(HPacket p) {
        String s = p.readString();
        return NomSalle.utf8(s);
    }

    /** Les codes de categorie « Troc » plausibles, quand le navigateur ne les donne pas. */
    static final List<String> CODES_TROC = List.of("category__Troc", "category__Trading",
            "category__TRADING", "category__trading", "category__troc");

    /** Le bloc dont le code ou le titre parle de troc (« Troc », « Trading », « Échange ») ; null sinon. */
    static Bloc blocTroc(List<Bloc> blocs) {
        Bloc meilleur = null;
        for (Bloc b : blocs) {
            String c = NomsMobis.normaliser(b.code()), t = NomsMobis.normaliser(b.texte());
            boolean cat = c.startsWith("category__");
            boolean troc = parleDeTroc(c) || parleDeTroc(t);
            if (!troc) continue;
            if (cat) return b;                       // une vraie categorie : la bonne
            if (meilleur == null) meilleur = b;
        }
        return meilleur;
    }

    static boolean parleDeTroc(String s) {
        if (s == null) return false;
        String n = NomsMobis.normaliser(s);
        return n.contains("troc") || n.contains("trad") || n.contains("echang");
    }

    /** Ce qu'on visitera, et ce qu'on laisse de cote. */
    record Choix(List<Appart> visites, int fermes, int vides, int auDela) { }

    /**
     * Les apparts a visiter : ouverts seulement (sonnette, mot de passe,
     * invisible : ignores), avec du monde si demande, l'appart de depart
     * d'abord (on y est deja), puis les plus peuples, au plus « limite ».
     */
    static Choix choisir(List<Appart> tous, boolean avecMonde, int limite, int salleDepart) {
        List<Appart> ok = new ArrayList<>();
        int fermes = 0, vides = 0;
        Set<Integer> vus = new HashSet<>();
        for (Appart a : tous) {
            if (!vus.add(a.id())) continue;
            if (!a.ouvert() && a.id() != salleDepart) { fermes++; continue; }
            if (avecMonde && a.presents() < 1 && a.id() != salleDepart) { vides++; continue; }
            ok.add(a);
        }
        ok.sort(Comparator.comparing((Appart a) -> a.id() != salleDepart)
                .thenComparing(Comparator.comparingInt(Appart::presents).reversed()));
        int auDela = Math.max(0, ok.size() - Math.max(1, limite));
        List<Appart> v = new ArrayList<>(ok.subList(0, Math.min(ok.size(), Math.max(1, limite))));
        return new Choix(v, fermes, vides, auDela);
    }

    // ------------------------------------------------------------ comptage

    /** Un mobi cherche : son type (sol ou mur) et la quantite voulue. */
    record Cible(String classe, boolean mur, int typeId, String nom, int revision, int offreCatalogue, int voulu) {
        String cle() { return RechercheCalcul.cle(mur, typeId); }
    }

    static String cle(boolean mur, int typeId) { return (mur ? "2:" : "1:") + typeId; }

    /** Combien de chaque mobi cherche dans ces listes de types (sols, murs). Les absents valent 0. */
    static Map<String, Integer> compter(Collection<Integer> typesSols, Collection<Integer> typesMurs, List<Cible> cibles) {
        Map<String, Integer> r = new LinkedHashMap<>();
        Set<Integer> sols = new HashSet<>(), murs = new HashSet<>();
        for (Cible c : cibles) { r.put(c.cle(), 0); (c.mur() ? murs : sols).add(c.typeId()); }
        for (Integer t : typesSols) if (t != null && sols.contains(t)) r.merge(cle(false, t), 1, Integer::sum);
        for (Integer t : typesMurs) if (t != null && murs.contains(t)) r.merge(cle(true, t), 1, Integer::sum);
        return r;
    }

    // ------------------------------------------------------------ marche et catalogue

    /** Une ligne de la place du marche : un type de mobi, son prix le plus bas, sa moyenne, ses offres. */
    record Offre(boolean mur, int typeId, int prix, int moyen, int nombre) { }

    /** Lit MarketPlaceOffers ; null si le paquet n'a pas ce format. */
    static List<Offre> lireOffres(HPacket brut) {
        try {
            HPacket p = new HPacket(brut);
            p.setReadIndex(6);
            int n = p.readInteger();
            if (n < 0 || n > 5000) return null;
            List<Offre> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                p.readInteger();                    // id de l'offre
                p.readInteger();                    // statut
                int type = p.readInteger();
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
                } else return null;
                int prix = p.readInteger();
                p.readInteger();                    // minutes restantes
                int moyen = p.readInteger(), nombre = p.readInteger();
                l.add(new Offre(mur, typeId, prix, moyen, nombre));
            }
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ce que la place du marche dit d'un mobi. */
    record Marche(int offres, int prixMin, int prixMoyen, boolean repondu) {
        static final Marche INCONNU = new Marche(0, -1, -1, false);
    }

    /** Les offres d'un type parmi celles lues : nombre total, prix le plus bas, moyenne. */
    static Marche marchePour(List<Offre> offres, boolean mur, int typeId) {
        int nombre = 0, min = -1, moyen = -1;
        for (Offre o : offres) {
            if (o.mur() != mur || o.typeId() != typeId) continue;
            nombre += Math.max(1, o.nombre());
            if (o.prix() > 0 && (min < 0 || o.prix() < min)) min = o.prix();
            if (o.moyen() > 0 && moyen < 0) moyen = o.moyen();
        }
        return new Marche(nombre, min, moyen, true);
    }

    /** Une offre du catalogue : son prix en credits et en points (diamants, ...). */
    record Catalogue(boolean vendu, int credits, int points, int typePoints, boolean repondu) {
        static final Catalogue NON = new Catalogue(false, 0, 0, 0, true);
        static final Catalogue INCONNU = new Catalogue(false, 0, 0, 0, false);
    }

    /** Lit ProductOffer s'il concerne cette offre ; null sinon. */
    static Catalogue lireOffreCatalogue(HPacket brut, int offre) {
        try {
            if (brut.getBytesLength() < 6 + 4 + 2 + 1 + 12) return null;
            if (brut.readInteger(6) != offre) return null;
            HPacket p = new HPacket(brut);
            p.setReadIndex(10);
            p.readString();                          // nom de l'offre
            p.readBoolean();                         // location
            int credits = p.readInteger(), points = p.readInteger(), type = p.readInteger();
            if (credits < 0 || points < 0) return null;
            return new Catalogue(true, credits, points, type, true);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ resultats

    /** Un appart ou le mobi est pose, et combien. */
    record Trouve(Appart appart, int quantite) { }

    /** Le resultat pour un mobi cherche. */
    static final class Resultat {
        final Cible cible;
        final List<Trouve> apparts = new ArrayList<>();
        Marche marche = Marche.INCONNU;
        Catalogue catalogue = Catalogue.INCONNU;

        Resultat(Cible c) { cible = c; }

        int totalPose() { int t = 0; for (Trouve x : apparts) t += x.quantite(); return t; }
        int nombreApparts() { return apparts.size(); }

        /** Ajoute le compte d'un appart (rien si 0) ; garde le tri, le plus gros d'abord. */
        void ajouter(Appart a, int quantite) {
            if (quantite <= 0) return;
            apparts.removeIf(x -> x.appart().id() == a.id());
            apparts.add(new Trouve(a, quantite));
            apparts.sort(Comparator.comparingInt(Trouve::quantite).reversed()
                    .thenComparing(x -> x.appart().nom() == null ? "" : x.appart().nom()));
        }
    }

    /** Le verdict « quantite voulue atteignable ? ». */
    enum Verdict {
        CATALOGUE("Oui : en vente au catalogue"),
        MARCHE("Oui : assez d'offres au marché"),
        AVEC_TROC("Peut-être : marché et apparts Troc ensemble (à négocier)"),
        TROC("Peut-être : posé dans les apparts Troc (à négocier)"),
        NON("Non : pas assez trouvé"),
        RIEN("Non : trouvé nulle part");
        final String texte;
        Verdict(String t) { texte = t; }
    }

    static Verdict verdict(int voulu, Catalogue cat, Marche m, int pose) {
        if (cat != null && cat.vendu()) return Verdict.CATALOGUE;
        int offres = m == null ? 0 : m.offres();
        if (offres >= voulu) return Verdict.MARCHE;
        if (offres > 0 && offres + pose >= voulu) return Verdict.AVEC_TROC;
        if (pose >= voulu) return Verdict.TROC;
        if (offres + pose > 0) return Verdict.NON;
        return Verdict.RIEN;
    }

    /** Duree estimee d'une tournee (en minutes, arrondie au-dessus). */
    static int minutes(int apparts, long pauseMoyenneMs, long chargementMs) {
        long ms = (long) apparts * (pauseMoyenneMs + chargementMs);
        return (int) Math.max(1, (ms + 59_999) / 60_000);
    }

    /** Pause entre deux apparts : de min a max, au hasard (rythme humain). */
    static long pause(Random r, long min, long max) {
        if (max <= min) return min;
        return min + (long) (r.nextDouble() * (max - min));
    }
}

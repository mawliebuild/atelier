package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.prefs.Preferences;

/**
 * Reproduction automatique des monster plants.
 *
 * Le paquet BreedPets (int etat, int plante1, int plante2, d'apres le client)
 * n'a pas de documentation fiable : les valeurs de « etat » (demande,
 * acceptation) ne sont pas sures. Plutot que de deviner, l'Atelier APPREND :
 * quand tu fais une reproduction a la main dans le jeu, il note la suite exacte
 * des paquets BreedPets envoyes, et ou figurent les deux plantes. Il rejoue
 * ensuite cette suite pour d'autres couples, en remplacant les plantes.
 *
 * Le modele appris est garde d'un lancement a l'autre.
 *
 * Couples : les plus hauts niveaux (rarete) ensemble ; une plante sans
 * partenaire de son niveau prend celle du niveau juste en dessous.
 */
public final class Reproduction {

    private Reproduction() { }

    /** Une etape apprise : chaque entier vaut une constante, ou « A » / « B » (les plantes). */
    static final class Etape {
        final String[] valeurs;
        Etape(String[] v) { valeurs = v; }
        @Override public String toString() { return String.join(",", valeurs); }
    }

    private static final Preferences prefs = Preferences.userRoot().node("atelier");
    private static volatile List<Etape> modele = lire(prefs.get("reproduction.modele", ""));

    // Paquets observes de la reproduction manuelle en cours.
    private static final List<int[]> vus = new ArrayList<>();
    private static long dernierVu = 0;
    /** Pendant qu'on rejoue nous-memes, on n'apprend pas de nos propres paquets. */
    private static volatile boolean rejeu = false;

    public static boolean appris() { return !modele.isEmpty(); }
    public static String modeleTexte() { return modele.toString(); }

    // ---------------------------------------------------------- apprentissage

    /** Appele pour chaque BreedPets envoye par le jeu. */
    static synchronized void observer(HPacket p) {
        if (rejeu) return;
        int n = (p.getBytesLength() - 6) / 4;
        if (n < 2 || n > 6) return;
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = p.readInteger(6 + 4 * i);
        long now = System.currentTimeMillis();
        if (now - dernierVu > 60_000) vus.clear();      // nouvelle reproduction
        // un autre couple dans la minute : nouvelle reproduction aussi (sinon ses
        // identifiants resteraient en dur dans le modele et seraient rejoues)
        else if (nouveauCouple(vus, v, PlanteSuivi.plantes())) vus.clear();
        dernierVu = now;
        vus.add(v);
        Journal.debug("BreedPets observe : " + Arrays.toString(v));
        List<Etape> m = deduire(vus, PlanteSuivi.plantes());
        if (!m.isEmpty()) {
            modele = m;
            prefs.put("reproduction.modele", ecrire(m));
            Journal.debug("reproduction apprise : " + m);
        }
    }

    /**
     * Deduit le modele d'une suite de paquets : les deux identifiants de
     * plantes connues qui y apparaissent deviennent A et B. Logique pure.
     */
    static List<Etape> deduire(List<int[]> paquets, Collection<PlanteSuivi.Plante> plantes) {
        int[] c = couple(paquets, ids(plantes));
        if (c == null) return List.of();
        int a = c[0], b = c[1];
        List<Etape> r = new ArrayList<>();
        for (int[] v : paquets) {
            String[] s = new String[v.length];
            for (int i = 0; i < v.length; i++)
                s[i] = v[i] == a ? "A" : v[i] == b ? "B" : String.valueOf(v[i]);
            r.add(new Etape(s));
        }
        return r;
    }

    private static Set<Integer> ids(Collection<PlanteSuivi.Plante> plantes) {
        Set<Integer> ids = new HashSet<>();
        for (PlanteSuivi.Plante p : plantes) ids.add(p.id);
        return ids;
    }

    /** Les deux premieres plantes connues (differentes) de la suite, ou null. Logique pure. */
    static int[] couple(List<int[]> paquets, Set<Integer> ids) {
        Integer a = null, b = null;
        for (int[] v : paquets)
            for (int x : v)
                if (ids.contains(x)) {
                    if (a == null) a = x;
                    else if (b == null && x != a) b = x;
                }
        return a == null || b == null ? null : new int[]{a, b};
    }

    /**
     * Le paquet v parle-t-il d'une plante qui n'est pas du couple deja vu dans
     * la suite ? Alors c'est une autre reproduction. Logique pure.
     */
    static boolean nouveauCouple(List<int[]> paquets, int[] v, Collection<PlanteSuivi.Plante> plantes) {
        Set<Integer> ids = ids(plantes);
        int[] c = couple(paquets, ids);
        if (c == null) return false;
        for (int x : v) if (ids.contains(x) && x != c[0] && x != c[1]) return true;
        return false;
    }

    static String ecrire(List<Etape> m) {
        StringBuilder sb = new StringBuilder();
        for (Etape e : m) { if (sb.length() > 0) sb.append(';'); sb.append(e); }
        return sb.toString();
    }

    static List<Etape> lire(String s) {
        List<Etape> r = new ArrayList<>();
        if (s == null || s.isBlank()) return r;
        for (String e : s.split(";")) r.add(new Etape(e.split(",")));
        return r;
    }

    /** Les entiers d'une etape pour un couple donne. Logique pure. */
    static int[] valeurs(Etape e, int a, int b) {
        int[] v = new int[e.valeurs.length];
        for (int i = 0; i < v.length; i++) {
            String s = e.valeurs[i];
            v[i] = "A".equals(s) ? a : "B".equals(s) ? b : Integer.parseInt(s);
        }
        return v;
    }

    // ------------------------------------------------------------- couples

    /** Une plante peut-elle entrer dans un couple maintenant ? */
    public static boolean disponible(PlanteSuivi.Plante p, String nomSaisi) {
        if (p == null || p.morte || !p.adulte()) return false;
        if (!p.peutReproduire) return false;
        return PlanteSuivi.estAMoi(p, nomSaisi) || p.permissionReproduction;
    }

    /**
     * Les couples. Logique pure : les plantes sont rangees du plus haut
     * niveau (rarete) au plus bas, puis associees deux par deux dans cet
     * ordre. Les plus hauts niveaux vont donc ensemble ; une plante sans
     * partenaire de son niveau prend la plante du niveau JUSTE en dessous (et
     * ainsi de suite). Une plante seule a la fin reste sans couple. Couples
     * rendus des plus hauts niveaux aux plus bas.
     */
    public static List<PlanteSuivi.Plante[]> couples(List<PlanteSuivi.Plante> candidates) {
        Comparator<PlanteSuivi.Plante> ordre = Comparator.comparingInt((PlanteSuivi.Plante p) -> p.rarete).reversed()
                .thenComparing(Comparator.comparingInt((PlanteSuivi.Plante p) -> p.niveau).reversed())
                .thenComparingInt(p -> p.id);
        List<PlanteSuivi.Plante> l = new ArrayList<>(candidates);
        l.sort(ordre);
        List<PlanteSuivi.Plante[]> r = new ArrayList<>();
        for (int i = 0; i + 1 < l.size(); i += 2) r.add(new PlanteSuivi.Plante[]{l.get(i), l.get(i + 1)});
        return r;
    }

    // ---------------------------------------------------------------- envoi

    /**
     * Rejoue le modele pour un couple. Entre deux etapes, on attend la reponse
     * du serveur (proposition ou resultat), 3 s au plus.
     * @return vrai si le serveur a annonce un resultat (une graine).
     */
    public static boolean reproduire(int a, int b) {
        List<Etape> m = modele;
        if (m.isEmpty()) return false;
        // Jamais en tache de fond : seulement dans une action que tu as lancee.
        if (!PlanteSuivi.explicite()) {
            PlanteSuivi.envoisRefuses++;
            System.err.println("[Atelier] BreedPets bloque : aucune action explicite.");
            return false;
        }
        long t0 = System.currentTimeMillis();
        rejeu = true;
        try {
            for (int i = 0; i < m.size(); i++) {
                long avant = System.currentTimeMillis();
                int[] v = valeurs(m.get(i), a, b);
                Object[] args = new Object[v.length];
                for (int k = 0; k < v.length; k++) args[k] = v[k];
                Salle.espacer();
                PlanteSuivi.envoi.accept(new HPacket("BreedPets", HMessage.Direction.TOSERVER, args));
                if (i < m.size() - 1) {
                    for (int k = 0; k < 30; k++) {
                        if (PlanteSuivi.dernierePropositionReproduction >= avant
                                || PlanteSuivi.dernierResultatReproduction >= avant) break;
                        Salle.sommeil(100);
                    }
                    Salle.sommeil(300);
                }
            }
            for (int k = 0; k < 50; k++) {
                if (PlanteSuivi.dernierResultatReproduction >= t0) return true;
                Salle.sommeil(100);
            }
            return false;
        } finally {
            rejeu = false;
        }
    }
}

package atelier;

import java.util.*;
import java.util.function.IntFunction;

/**
 * Logique pure de l'outil « Hauteur fixe » (testable sans le jeu) :
 *   - reperer les dalles magiques d'une salle (par type connu ou par nom de classe) ;
 *   - lire la case visee par une pose (PlaceObject texte) ;
 *   - rapprocher un mobi apparu (ObjectAdd) d'une pose que TU as demandee ;
 *   - decider si un mobi apparu doit etre remis a la hauteur (mode « Sans dalles »).
 */
final class HauteurLogique {

    private HauteurLogique() { }

    /** Prefixe des noms de classe des dalles magiques (tile_stackmagic, tile_stackmagic1, 2, 4x4...). */
    static final String PREFIXE_DALLE = "tile_stackmagic";

    /** Un mobi de sol vu de loin : id, type, case. */
    static final class Mobi {
        final int id, type, x, y;
        Mobi(int id, int type, int x, int y) { this.id = id; this.type = type; this.x = x; this.y = y; }
    }

    static boolean estDalle(int type, Set<Integer> typesDalles, IntFunction<String> classe) {
        if (typesDalles != null && typesDalles.contains(type)) return true;
        String c = classe == null ? null : classe.apply(type);
        return c != null && c.toLowerCase(Locale.ROOT).startsWith(PREFIXE_DALLE);
    }

    /** Ids des dalles magiques parmi les mobis, dans l'ordre. */
    static List<Integer> dalles(Collection<Mobi> mobis, Set<Integer> typesDalles, IntFunction<String> classe) {
        List<Integer> r = new ArrayList<>();
        for (Mobi m : mobis) if (estDalle(m.type, typesDalles, classe)) r.add(m.id);
        return r;
    }

    /** {total, dont deja la (pas posees par l'Atelier)}. */
    static int[] compte(List<Integer> toutes, Collection<Integer> nos) {
        Set<Integer> n = new HashSet<>(nos);
        int deja = 0;
        for (int id : toutes) if (!n.contains(id)) deja++;
        return new int[]{toutes.size(), deja};
    }

    static String resume(int total, int deja, String hauteur) {
        if (total == 0) return "Aucune dalle magique dans cet appart.";
        return total + " dalle(s)" + (deja > 0 ? " dont " + deja + " déjà là" : "") + ", à " + hauteur + ".";
    }

    /**
     * Case d'une pose de sol « [-]idInventaire x y rot » ; null pour un mural
     * (« id :w=... ») ou un texte illisible.
     */
    static int[] casePlaceObject(String s) {
        if (s == null || s.indexOf(':') >= 0) return null;
        String[] t = s.trim().split("\\s+");
        if (t.length != 4) return null;
        try {
            Long.parseLong(t[0]);
            int x = Integer.parseInt(t[1]), y = Integer.parseInt(t[2]);
            Integer.parseInt(t[3]);
            if (x < 0 || y < 0 || x > 255 || y > 255) return null;
            return new int[]{x, y};
        } catch (NumberFormatException e) { return null; }
    }

    /**
     * Poses demandees par le client (donc par toi), en attente de leur mobi.
     * Un mobi apparu n'est pris que s'il arrive sur la case d'une pose demandee
     * il y a moins de DELAI ms : ceux des autres, ou deplaces, ne passent pas.
     * Un seul fil s'en sert (pas de verrou).
     */
    static final class Poses {
        static final long DELAI = 6000;
        private final List<long[]> attente = new ArrayList<>();   // {x, y, echeance}

        void demandee(int x, int y, long now) { attente.add(new long[]{x, y, now + DELAI}); }

        /** Vrai (et la pose est consommee) si (x, y) correspond a une pose demandee. */
        boolean apparue(int x, int y, long now) {
            attente.removeIf(p -> p[2] < now);
            for (Iterator<long[]> i = attente.iterator(); i.hasNext(); ) {
                long[] p = i.next();
                if (p[0] == x && p[1] == y) { i.remove(); return true; }
            }
            return false;
        }

        int enAttente() { return attente.size(); }
    }

    /**
     * Ecritures d'altitude : chacune part tout de suite (sans pause), puis est
     * verifiee VERIF ms plus tard ; si le mobi n'est pas a la hauteur, on la
     * renvoie une fois et on reverifie. Un seul fil s'en sert.
     */
    static final class Ecritures {
        interface Monde {
            void ecrire(int id, double z);
            /** Altitude actuelle du mobi ; null s'il n'est plus dans la salle. */
            Double z(int id);
        }

        static final long VERIF = 700;
        static final double TOLERANCE = 0.05;

        private final Monde monde;
        private final long verif;
        /** id -> {z voulue, echeance, essais} (ordre d'arrivee). */
        private final Map<Integer, double[]> aVerifier = new LinkedHashMap<>();
        int reussies, ratees;

        Ecritures(Monde monde, long verif) { this.monde = monde; this.verif = verif; }

        /** Envoie tout de suite et programme la verification. */
        void ecrire(int id, double z, long now) {
            monde.ecrire(id, z);
            aVerifier.put(id, new double[]{z, now + verif, 1});
        }

        /** Prochaine verification due (ms), ou -1 s'il n'y en a pas. */
        long prochaine() {
            long r = -1;
            for (double[] v : aVerifier.values()) if (r < 0 || v[1] < r) r = (long) v[1];
            return r;
        }

        /** Fait les verifications dues ; renvoie les ids definitivement rates a ce tour. */
        List<Integer> verifier(long now) {
            List<Integer> rates = new ArrayList<>();
            for (Iterator<Map.Entry<Integer, double[]>> i = aVerifier.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<Integer, double[]> e = i.next();
                double[] v = e.getValue();
                if (v[1] > now) continue;
                Double z = monde.z(e.getKey());
                if (z == null) { i.remove(); continue; }              // ramasse entre-temps
                if (Math.abs(z - v[0]) <= TOLERANCE) { i.remove(); reussies++; continue; }
                if (v[2] < 2) {                                         // un seul nouvel essai
                    monde.ecrire(e.getKey(), v[0]);
                    v[1] = now + verif; v[2] = 2;
                    continue;
                }
                i.remove(); ratees++; rates.add(e.getKey());
            }
            return rates;
        }

        int enCours() { return aVerifier.size(); }
    }

    /**
     * Pourquoi un mobi apparu sur une de tes poses n'est pas remis a la hauteur ;
     * null s'il faut le traiter.
     */
    static String refus(boolean dalle, boolean importEnCours, boolean historiqueOccupe,
                        boolean atelierOccupe, boolean dejaTraite) {
        if (dejaTraite) return "déjà traité";
        if (dalle) return "dalle magique";
        if (importEnCours) return "collage ou import en cours";
        if (historiqueOccupe) return "annulation en cours";
        if (atelierOccupe) return "l'Atelier pose des dalles";
        return null;
    }
}

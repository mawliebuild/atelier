package atelier;

import java.text.Normalizer;
import java.util.*;

/**
 * Les noms de TOUS les mobis du jeu (furnidata), pour proposer un vrai nom
 * quand on en tape un bout : « couleur de d » donne « Couleur de décor ».
 *
 * La comparaison ignore la casse, les accents et les espaces en trop. Les
 * noms techniques (classname) sont cherches aussi : « wf_act » marche.
 *
 * FurniDataTools ne publie pas sa liste : on lit ses deux tables privees
 * (nameToFloorItems, nameToWallItems) une fois, des qu'elle est prete.
 */
public final class NomsMobis {

    private NomsMobis() { }

    /** Un nom propose : le nom affiche dans le jeu, et ses noms techniques. */
    public static final class Nom {
        public final String nom;
        final String cle;                       // nom normalise
        final Set<String> classes = new TreeSet<>();
        Nom(String nom) { this.nom = nom; this.cle = normaliser(nom); }
        @Override public String toString() { return nom; }
    }

    private static volatile List<Nom> tous = null;

    /** Minuscules, sans accents, espaces simples. */
    public static String normaliser(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** La liste, chargee a la premiere demande ; vide tant que la furnidata n'est pas prete. */
    public static List<Nom> tous() {
        List<Nom> l = tous;
        if (l != null) return l;
        if (!Salle.furnidataPrete()) return List.of();
        synchronized (NomsMobis.class) {
            if (tous != null) return tous;
            Map<String, Nom> parNom = new HashMap<>();
            try {
                furnidata.FurniDataTools fd = Salle.gp().getFurniDataTools();
                for (String champ : new String[]{"nameToFloorItems", "nameToWallItems"}) {
                    java.lang.reflect.Field f = furnidata.FurniDataTools.class.getDeclaredField(champ);
                    f.setAccessible(true);
                    Map<?, ?> m = (Map<?, ?>) f.get(fd);
                    if (m == null) continue;
                    for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                        String classe = String.valueOf(e.getKey());
                        String nom = null;
                        try {
                            nom = champ.startsWith("nameToFloor") ? fd.getFloorItemDetails(classe).name
                                                                  : fd.getWallItemDetails(classe).name;
                        } catch (Throwable ignored) { }
                        if (nom == null || nom.isBlank()) nom = classe;
                        ajouter(parNom, nom.trim(), classe);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[Atelier] noms des mobis illisibles : " + t);
                return List.of();
            }
            tous = Collections.unmodifiableList(new ArrayList<>(parNom.values()));
            Journal.debug(tous.size() + " noms de mobis connus.");
            return tous;
        }
    }

    /** Les mobis MURAUX du jeu : {nom affiche, nom technique}, tries par nom. Vide si pas pret. */
    public static List<String[]> muraux() {
        List<String[]> r = new ArrayList<>();
        if (!Salle.furnidataPrete()) return r;
        try {
            furnidata.FurniDataTools fd = Salle.gp().getFurniDataTools();
            java.lang.reflect.Field f = furnidata.FurniDataTools.class.getDeclaredField("nameToWallItems");
            f.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) f.get(fd);
            if (m != null) for (Object k : new ArrayList<>(m.keySet())) {
                String classe = String.valueOf(k), nom = null;
                try { nom = fd.getWallItemDetails(classe).name; } catch (Throwable ignored) { }
                r.add(new String[]{nom == null || nom.isBlank() ? classe : nom.trim(), classe});
            }
        } catch (Throwable t) { System.err.println("[Atelier] muraux illisibles : " + t); }
        r.sort(Comparator.comparing(a -> normaliser(a[0])));
        return r;
    }

    static void ajouter(Map<String, Nom> parNom, String nom, String classe) {
        Nom n = parNom.computeIfAbsent(normaliser(nom), k -> new Nom(nom));
        n.classes.add(classe.toLowerCase(Locale.ROOT));
    }

    /**
     * Les noms qui contiennent le texte, les meilleurs d'abord : nom exact,
     * puis nom qui commence par le texte, puis un mot qui commence par le
     * texte, puis le texte n'importe ou ; enfin les noms techniques. A rang
     * egal, le plus court.
     */
    public static List<Nom> chercher(String texte, int max) { return chercher(tous(), texte, max); }

    static List<Nom> chercher(List<Nom> liste, String texte, int max) {
        String t = normaliser(texte);
        if (t.isEmpty()) return List.of();
        List<Object[]> notes = new ArrayList<>();
        for (Nom n : liste) {
            int r = rang(n, t);
            if (r >= 0) notes.add(new Object[]{r, n});
        }
        notes.sort(Comparator.<Object[]>comparingInt(o -> (Integer) o[0])
                .thenComparingInt(o -> ((Nom) o[1]).nom.length())
                .thenComparing(o -> ((Nom) o[1]).cle));
        List<Nom> r = new ArrayList<>();
        for (Object[] o : notes) { r.add((Nom) o[1]); if (r.size() >= max) break; }
        return r;
    }

    private static int rang(Nom n, String t) {
        if (n.cle.equals(t)) return 0;
        if (n.cle.startsWith(t)) return 1;
        if (n.cle.contains(" " + t)) return 2;
        if (n.cle.contains(t)) return 3;
        for (String c : n.classes) if (c.equals(t)) return 4;
        for (String c : n.classes) if (c.contains(t)) return 5;
        return -1;
    }

    /**
     * Un mobi (nom affiche, nom technique) correspond-il a ce filtre ? Un
     * filtre choisi dans la liste vise ce nom exact (accents et casse mis a
     * part) ; un filtre libre (rien trouve) vise « contient ».
     */
    public static boolean correspond(String filtre, boolean exact, String nomMobi, String classeMobi) {
        String f = normaliser(filtre);
        if (f.isEmpty()) return false;
        String n = normaliser(nomMobi), c = classeMobi == null ? "" : classeMobi.toLowerCase(Locale.ROOT);
        if (exact) return n.equals(f) || c.equals(f);
        return n.contains(f) || c.contains(f);
    }
}

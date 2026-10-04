package atelier;

/**
 * Les dalles magiques (empilement) et leur cote en cases.
 *
 * Remplace extension.tools.StackTileSetting : memes classes, memes
 * dimensions (-1 = la dalle 1x2), memes regles de choix.
 *   Small  tile_stackmagic     1      XL    tile_stackmagic4x4  4
 *   Medium tile_stackmagic1   -1      XXL   tile_stackmagic6x6  6
 *   Large  tile_stackmagic2    2      XXXL  tile_stackmagic8x8  8
 */
enum DalleMagique {
    UN("tile_stackmagic", 1),
    UN_DEUX("tile_stackmagic1", -1),
    DEUX("tile_stackmagic2", 2),
    QUATRE("tile_stackmagic4x4", 4),
    SIX("tile_stackmagic6x6", 6),
    HUIT("tile_stackmagic8x8", 8);

    private final String classe;
    private final int dimension;

    DalleMagique(String classe, int dimension) {
        this.classe = classe;
        this.dimension = dimension;
    }

    String classe() { return classe; }

    /** Cote en cases ; -1 pour la dalle 1x2. */
    int dimension() { return dimension; }

    /** « 1x1 », « 1x2 », « 2x2 »... ; 2x2 par defaut (ex-fromString). */
    static DalleMagique depuisTexte(String s) {
        if (s != null) switch (s.trim()) {
            case "1x1": return UN;
            case "1x2": case "-1x-1": return UN_DEUX;
            case "2x2": return DEUX;
            case "4x4": return QUATRE;
            case "6x6": return SIX;
            case "8x8": return HUIT;
            default: break;
        }
        return DEUX;
    }

    /** La dalle de cette classe, ou null (ex-fromClassName). */
    static DalleMagique depuisClasse(String classe) {
        for (DalleMagique d : values()) if (d.classe.equals(classe)) return d;
        return null;
    }

    static boolean estDalle(String classe) { return depuisClasse(classe) != null; }

    /** La dalle d'une taille requise ; 8x8 par defaut (ex-forDimension). */
    static DalleMagique pourDimension(int requise) {
        switch (requise) {
            case -1: return UN_DEUX;
            case 1: return UN;
            case 2: return DEUX;
            case 4: return QUATRE;
            case 6: return SIX;
            default: return HUIT;
        }
    }

    /** « 2x2 » ; « 1x2 » pour la dalle 1x2 (relu par depuisTexte). */
    @Override public String toString() {
        return this == UN_DEUX ? "1x2" : dimension + "x" + dimension;
    }
}

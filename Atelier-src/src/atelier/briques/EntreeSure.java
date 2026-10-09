package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Entree sure dans un appart : le jeu plante s'il recoit une config d'effet de
 * variable (VariableFxConfigs) dont le couple (categorie, style) n'existe pas
 * dans sa table (client : _-M9.initialize, getByCategoryAndStyleId rend null).
 *
 * VariableFxConfigs (parseur client _-S2r) : int n, puis n entrees de
 *   int configId, boolean isUserFx, int showMode, int ?, boolean survol,
 *   int duree, int categorie, int style, int couleur, int largeur,
 *   int rendu, long min, long max, int k, k x (String, String).
 * Le paquet est reecrit sans les entrees inconnues ; s'il ne se lit pas comme
 * prevu, il est bloque en entier (purement visuel). Toujours actif.
 *
 * Sert aussi au reglage des wired (ReglageWiredPose) : un add-on varfx dont le
 * style est inconnu du jeu n'est jamais envoye.
 */
final class EntreeSure {

    private EntreeSure() { }

    /**
     * Styles connus par categorie (_-M9.initialize : categoryId -> ids de style).
     * 0 health_points, 1 progress_bar, 2 levelling_progress, 3 status_bar,
     * 4 boss_bar, 5 number_display.
     */
    private static final int[] STYLES_MAX = {3, 4, 1, 21, 1, 2};

    /** Le couple (categorie, style) existe-t-il pour ce client ? Logique pure. */
    static boolean styleConnu(int categorie, int style) {
        return categorie >= 0 && categorie < STYLES_MAX.length && style >= 0 && style <= STYLES_MAX[categorie];
    }

    /** Un add-on d'effet de variable (wf_xtra_varfx_*). */
    static boolean estVarfx(String classe) {
        return classe != null && classe.startsWith("wf_xtra_varfx_");
    }

    /** La categorie d'un add-on varfx d'apres sa classe (AddonCodes 1200..1205) ; -1 si inconnue. */
    static int categorieVarfx(String classe) {
        if (!estVarfx(classe)) return -1;
        switch (classe.substring("wf_xtra_varfx_".length())) {
            case "hp": return 0;
            case "prog": return 1;
            case "levelling": return 2;
            case "status": return 3;
            case "boss": return 4;
            case "number": return 5;
            default: return -1;
        }
    }

    /** Le style d'un add-on varfx : options[6] (_-416.readIntParams) ; -1 s'il manque. */
    static int styleVarfx(List<Integer> options) {
        return options == null || options.size() <= 6 || options.get(6) == null ? -1 : options.get(6);
    }

    // ================================================================ paquet entrant

    /** Salle ou un retrait a deja ete note (un seul Journal.debug par salle). */
    private static volatile int salleNotee = Integer.MIN_VALUE;
    private static volatile boolean branche;

    /** Ecoute VariableFxConfigs vers le jeu (une seule fois). */
    static synchronized void brancher(Canal canal) {
        if (branche || canal == null) return;
        canal.intercept(HMessage.Direction.TOCLIENT, "VariableFxConfigs", EntreeSure::surConfigs);
        branche = true;
    }

    private static void surConfigs(HMessage m) {
        HPacket p = m.getPacket();
        int[] retires = {0};
        byte[] neuf;
        try {
            neuf = filtrer(p, retires);
        } catch (Throwable t) {
            m.setBlocked(true);
            noter("VariableFxConfigs illisible, bloqué en entier (" + t.getClass().getSimpleName() + ")");
            return;
        } finally {
            p.resetReadIndex();
        }
        if (neuf == null) return;
        p.setBytes(neuf);
        p.resetReadIndex();
        noter(PoseOutils.nombre(retires[0], "effet de variable inconnu retiré", "effets de variable inconnus retirés")
                + " de VariableFxConfigs");
    }

    private static void noter(String s) {
        int salle;
        try { salle = Salle.salleId(); } catch (Throwable t) { salle = -1; }
        if (salle == salleNotee) return;
        salleNotee = salle;
        Journal.debug("Entrée sûre : " + s + " (salle " + salle + ").");
    }

    /**
     * Le paquet complet (taille, en-tete, contenu) sans les entrees dont le
     * couple (categorie, style) est inconnu ; null si rien n'est a retirer.
     * Leve une exception si le paquet ne se lit pas exactement. Logique pure.
     *
     * @param retires [0] recoit le nombre d'entrees retirees
     */
    static byte[] filtrer(HPacket p, int[] retires) {
        p.resetReadIndex();
        int n = p.readInteger();
        if (n < 0 || n > 100_000) throw new IllegalStateException("nombre d'entrées " + n);
        ByteArrayOutputStream garde = new ByteArrayOutputStream();
        int gardees = 0;
        for (int i = 0; i < n; i++) {
            int debut = p.getReadIndex();
            p.readInteger();                 // configId
            p.readBoolean();                 // isUserFx
            p.readInteger();                 // showMode
            p.readInteger();
            p.readBoolean();                 // survol
            p.readInteger();                 // duree
            int categorie = p.readInteger();
            int style = p.readInteger();
            p.readInteger();                 // couleur
            p.readInteger();                 // largeur
            p.readInteger();                 // rendu
            p.readLong();                    // min
            p.readLong();                    // max
            int k = p.readInteger();
            if (k < 0 || k > 10_000) throw new IllegalStateException("extras " + k);
            for (int j = 0; j < k; j++) { p.readString(); p.readString(); }
            int fin = p.getReadIndex();
            if (fin > p.getBytesLength()) throw new IllegalStateException("paquet trop court");
            if (styleConnu(categorie, style)) {
                garde.writeBytes(p.readBytes(fin - debut, debut));
                gardees++;
            }
        }
        if (p.isEOF() != 1) throw new IllegalStateException("octets en trop");
        if (gardees == n) return null;
        retires[0] = n - gardees;
        HPacket r = new HPacket(p.headerId());
        r.appendInt(gardees);
        r.appendBytes(garde.toByteArray());
        return r.toBytes();
    }
}

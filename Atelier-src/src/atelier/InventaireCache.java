package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.protocol.HPacket;

import java.util.*;

/**
 * L'inventaire du jeu garde en OCTETS BRUTS, mobi par mobi.
 *
 * Format d'un fragment d'inventaire (lu dans HInventoryItem de G-Earth) :
 *   taille (int) | en-tete (short) | total (int) | numero (int) | nombre (int) | mobis...
 * Chaque mobi est ecrit a la suite, sans separateur. On le decoupe en lisant
 * chaque mobi avec HInventoryItem et en notant la position de lecture avant et
 * apres : les octets entre les deux sont le mobi exact envoye par le serveur.
 *
 * Interet :
 *  - reconstruire une liste filtree = recoller des octets, aucune reecriture
 *    (constructPackets de G-Earth agrandit le paquet octet par octet a chaque
 *    champ : plusieurs secondes pour 25 000 mobis) ;
 *  - rien n'est perdu : un type special ou des donnees que G-Earth ne connait
 *    pas repartent tels quels.
 *
 * Le decoupage n'est retenu que s'il est sur : la lecture doit tomber pile sur
 * la fin du fragment, et recoller les morceaux doit redonner exactement les
 * octets d'origine. Sinon decouper() rend null et l'Atelier revient a
 * l'ancienne methode (constructPackets).
 */
final class InventaireCache {

    private InventaireCache() { }

    /** Mobis par fragment quand on reconstruit une serie (comme G-Earth). */
    static final int PAR_FRAGMENT = 600;

    /** Debut du premier mobi : taille(4) en-tete(2) total(4) numero(4) nombre(4). */
    static final int DEBUT_MOBIS = 18;

    /** Un mobi : l'objet lu, ses octets exacts, et sa fiche (calculee une fois). */
    static final class Mobi {
        final HInventoryItem item;
        final byte[] octets;
        /** null tant que la furnidata n'est pas prete : recalculee a la demande. */
        volatile Fiche fiche;

        Mobi(HInventoryItem item, byte[] octets) {
            this.item = item;
            this.octets = octets;
        }
    }

    /**
     * Decoupe un fragment brut en mobis.
     * @return les mobis dans l'ordre, ou null si le decoupage n'est pas fiable.
     */
    static List<Mobi> decouper(HPacket fragment) {
        try {
            byte[] b = fragment.toBytes();
            if (b == null || b.length < DEBUT_MOBIS) return null;
            HPacket p = new HPacket(b);                 // copie : l'original n'est pas touche
            int total = p.readInteger(6), numero = p.readInteger(10), n = p.readInteger(14);
            if (n < 0 || n > 100_000) return null;
            p.setReadIndex(DEBUT_MOBIS);
            List<Mobi> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int debut = p.getReadIndex();
                HInventoryItem it = new HInventoryItem(p);
                int fin = p.getReadIndex();
                if (fin <= debut || fin > b.length) return null;
                l.add(new Mobi(it, Arrays.copyOfRange(b, debut, fin)));
            }
            if (p.getReadIndex() != b.length) return null;   // octets en trop ou manquants
            // Controle : recoller les morceaux doit redonner le fragment a l'identique.
            byte[] refait = fragment(p.headerId(), total, numero, l, 0, l.size());
            return Arrays.equals(refait, b) ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Les mobis d'un paquet « ajout ou mise a jour » (nombre, puis mobis).
     * @return null si la lecture ne tombe pas pile sur la fin du paquet.
     */
    static List<Mobi> lireAjouts(HPacket paquet) {
        try {
            byte[] b = paquet.toBytes();
            if (b == null || b.length < 10) return null;
            HPacket p = new HPacket(b);
            int n = p.readInteger(6);
            if (n < 0 || n > 10_000) return null;
            p.setReadIndex(10);
            List<Mobi> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int debut = p.getReadIndex();
                HInventoryItem it = new HInventoryItem(p);
                int fin = p.getReadIndex();
                if (fin <= debut || fin > b.length) return null;
                l.add(new Mobi(it, Arrays.copyOfRange(b, debut, fin)));
            }
            return p.getReadIndex() == b.length ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Une serie complete et coherente (total, numeros) a partir de mobis.
     * Une liste vide donne UN fragment vide : le jeu affiche alors un inventaire
     * vide (constructPackets n'envoyait rien du tout, et le jeu gardait l'ancien).
     */
    static List<byte[]> construire(List<Mobi> mobis, int entete) {
        int n = mobis.size();
        int total = Math.max(1, (n + PAR_FRAGMENT - 1) / PAR_FRAGMENT);
        List<byte[]> l = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int de = i * PAR_FRAGMENT, a = Math.min(n, de + PAR_FRAGMENT);
            l.add(fragment(entete, total, i, mobis, de, a));
        }
        return l;
    }

    /** Un fragment : en-tete, total, numero, nombre, puis les octets des mobis [de, a). */
    static byte[] fragment(int entete, int total, int numero, List<Mobi> mobis, int de, int a) {
        int taille = DEBUT_MOBIS;
        for (int i = de; i < a; i++) taille += mobis.get(i).octets.length;
        byte[] b = new byte[taille];
        ecrireInt(b, 0, taille - 4);
        b[4] = (byte) (entete >> 8);
        b[5] = (byte) entete;
        ecrireInt(b, 6, total);
        ecrireInt(b, 10, numero);
        ecrireInt(b, 14, a - de);
        int pos = DEBUT_MOBIS;
        for (int i = de; i < a; i++) {
            byte[] o = mobis.get(i).octets;
            System.arraycopy(o, 0, b, pos, o.length);
            pos += o.length;
        }
        return b;
    }

    private static void ecrireInt(byte[] b, int pos, int v) {
        b[pos]     = (byte) (v >> 24);
        b[pos + 1] = (byte) (v >> 16);
        b[pos + 2] = (byte) (v >> 8);
        b[pos + 3] = (byte) v;
    }

    /**
     * Secours quand les octets bruts manquent : chaque mobi est reecrit seul
     * (appendToPacket de G-Earth, sur un petit paquet), puis recolle par
     * construire(). Bien plus rapide et plus juste que constructPackets, qui
     * recopie le paquet entier a chaque champ ET met dans le fragment n tous les
     * mobis de n*600 jusqu'a la fin (25 000 mobis -> des centaines de milliers
     * de mobis ecrits ; le jeu ne lisait que les 600 premiers de chaque fragment).
     * Les types speciaux inconnus doivent avoir ete remplaces avant.
     */
    static List<Mobi> depuisItems(List<HInventoryItem> items) {
        List<Mobi> l = new ArrayList<>(items.size());
        for (HInventoryItem it : items) {
            HPacket p = new HPacket(0);
            it.appendToPacket(p);
            byte[] b = p.toBytes();
            l.add(new Mobi(it, Arrays.copyOfRange(b, 6, b.length)));
        }
        return l;
    }

    /** Octets totaux d'une serie (pour les journaux). */
    static long octets(List<byte[]> serie) {
        long n = 0;
        for (byte[] b : serie) n += b.length;
        return n;
    }
}

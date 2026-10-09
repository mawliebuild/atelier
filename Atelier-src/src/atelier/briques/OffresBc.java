package atelier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L'offre du Builders Club a envoyer pour poser un mobi, comme le client :
 *
 *   - depuis le catalogue BC (HabboCatalog.onObjectPlacedInRoom) :
 *     BuildersClubPlaceRoomItem(page, offre, extra, x, y, dir, false) et
 *     BuildersClubPlaceWallItem(page, offre, extra, position, false), page et
 *     offre etant celles de la PAGE du catalogue BC ou le mobi est vendu ;
 *   - depuis la fiche d'un mobi (InfoStandWidget) : page -1 et
 *     furnidata.bcofferid, seulement si la furnidata dit « bc » ;
 *   - le 7e champ (5e pour un mural) n'est vrai qu'en reponse a
 *     BuildersClubPlacementWarning, apres que l'utilisatrice a accepte de
 *     cacher la salle : l'Atelier envoie toujours false.
 *
 * Le client ne propose au BC que les mobis dont la furnidata dit « bc »
 * (availableForBuildersClub) : un mobi « bc » faux, meme present dans une
 * page du catalogue BC (rouge-gorges, tuiles de Noel...), est refuse sans un
 * mot du serveur. L'offerid de la furnidata est celle du catalogue NORMAL :
 * jamais envoyee au BC. Un bcofferid partage par plusieurs mobis (17476 :
 * six mobis de Paques) est un lot, pas posable au BC.
 *
 * Regles (choisir, logique pure) :
 *   1. furnidata « bc » faux -> pas au BC ;
 *   2. produit du catalogue BC (page, offre, extra) -> celui-la ;
 *   3. catalogue BC charge mais sans ce mobi -> pas au BC ;
 *   4. catalogue pas encore lu : bcofferid de la furnidata s'il est > 0 et
 *      propre a ce mobi, page -1 (comme la fiche du mobi) ;
 *   5. une offre refusee par le serveur (pose restee refusee apres ses
 *      reessais) n'est plus envoyee jusqu'a la prochaine action (oublier).
 */
final class OffresBc {

    private OffresBc() { }

    /** Ce qu'il faut envoyer : page (-1 : sans page), offre, parametre (affiches). */
    record Offre(int page, int offre, String extra, String origine) {
        @Override public String toString() {
            return "offre " + offre + (page != -1 ? " page " + page : "") + (extra == null || extra.isEmpty() ? "" : " « " + extra + " »")
                    + " (" + origine + ")";
        }
    }

    /** Offres refusees pendant l'action en cours. */
    private static final Set<Integer> REFUSEES = ConcurrentHashMap.newKeySet();
    /** Classes deja signalees « pas au Builders Club » (un seul message de diagnostic par classe). */
    private static final Set<String> SIGNALEES = ConcurrentHashMap.newKeySet();

    /** Nouvelle action (collage, dupliquer...) : les refus de la precedente sont oublies. */
    static void oublier() { REFUSEES.clear(); SIGNALEES.clear(); }

    /** Cette offre est restee refusee apres ses reessais : plus envoyee pendant l'action. */
    static void refusee(int offre) {
        if (offre > 0 && REFUSEES.add(offre))
            Journal.debug("BC : offre " + offre + " refusée par le jeu, plus envoyée pendant cette action.");
    }

    static boolean estRefusee(int offre) { return REFUSEES.contains(offre); }

    // ================================================================ logique pure

    /**
     * L'offre a envoyer, ou null (pas posable au BC).
     * @param bc             furnidata « bc » (availableForBuildersClub)
     * @param produit        produit du catalogue BC, ou null
     * @param catalogueCharge le catalogue BC est lu (son absence veut dire « pas au BC »)
     * @param bcOfferId      furnidata « bcofferid »
     * @param partagee       ce bcofferid est aussi celui d'autres mobis (lot)
     */
    static Offre choisir(boolean bc, CatalogueBc.Produit produit, boolean catalogueCharge, int bcOfferId, boolean partagee) {
        Offre o = null;
        if (!bc) return null;
        if (produit != null && produit.offerId() > 0)
            o = new Offre(produit.pageId(), produit.offerId(), produit.extraParam() == null ? "" : produit.extraParam(), "catalogue BC");
        else if (!catalogueCharge && bcOfferId > 0 && !partagee)
            o = new Offre(-1, bcOfferId, "", "furnidata");
        if (o != null && REFUSEES.contains(o.offre())) return null;
        return o;
    }

    // ================================================================ dans le jeu

    /** L'offre BC d'un mobi de sol, ou null. */
    static Offre sol(CatalogueBc cat, Furnidata fd, String classe) {
        if (fd == null || classe == null) return null;
        Furnidata.Mobi d = fd.sol(classe);
        if (d == null) return null;
        CatalogueBc.Produit p = null;
        boolean charge = false;
        try { charge = cat != null && cat.pret(); p = charge ? cat.produitSol(d.id) : null; } catch (Throwable ignored) { }
        return signaler(classe, choisir(d.isBC, p, charge, d.bcOfferId, partagee(fd, d)), d, charge);
    }

    /** L'offre BC d'un mural (variante exacte pour les affiches), ou null. */
    static Offre mur(CatalogueBc cat, Furnidata fd, String classe, String etat) {
        if (fd == null || classe == null) return null;
        Furnidata.Mobi d = fd.mur(classe);
        if (d == null) return null;
        CatalogueBc.Produit p = null;
        boolean charge = false;
        try {
            charge = cat != null && cat.pret();
            p = charge ? cat.produitMur(d.id, etat) : null;
            if (p == null && charge && etat == null) p = cat.unProduitMur(d.id);     // variante inconnue : une quelconque
        } catch (Throwable ignored) { }
        return signaler(classe, choisir(d.isBC, p, charge, d.bcOfferId, partagee(fd, d)), d, charge);
    }

    /** Un seul Journal.debug par classe sans offre BC (pourquoi). */
    private static Offre signaler(String classe, Offre o, Furnidata.Mobi d, boolean charge) {
        // « pas bc » dans la furnidata : cas normal, rien a dire (le bilan compte les manquants)
        if (o == null && d.isBC && SIGNALEES.add(classe))
            Journal.debug("BC : " + classe + " pas posable au Builders Club ("
                    + (!d.isBC ? "furnidata : pas « bc »"
                    : d.bcOfferId > 0 && estRefusee(d.bcOfferId) ? "offre " + d.bcOfferId + " refusée"
                    : charge ? "absent du catalogue BC" + (d.bcOfferId > 0 ? ", bcofferid " + d.bcOfferId + " ignoré" : "")
                    : "catalogue BC pas lu, bcofferid " + d.bcOfferId + (d.bcOfferId > 0 ? " partagé (lot)" : "")) + ").");
        return o;
    }

    // ================================================================ offres partagees

    private static volatile Furnidata comptesDe;
    private static volatile Map<Integer, Integer> comptes = Map.of();

    /** Le bcofferid de ce mobi est-il aussi celui d'un autre mobi (lot) ? */
    static boolean partagee(Furnidata fd, Furnidata.Mobi d) {
        if (d == null || d.bcOfferId <= 0) return false;
        if (comptesDe != fd || comptes.isEmpty()) {
            Map<Integer, Integer> c = new HashMap<>();
            for (Furnidata.Mobi m : fd.tousSols()) if (m.bcOfferId > 0) c.merge(m.bcOfferId, 1, Integer::sum);
            for (Furnidata.Mobi m : fd.tousMurs()) if (m.bcOfferId > 0) c.merge(m.bcOfferId, 1, Integer::sum);
            if (c.isEmpty()) return false;
            comptes = c;
            comptesDe = fd;
        }
        return comptes.getOrDefault(d.bcOfferId, 0) > 1;
    }
}

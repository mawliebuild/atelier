package atelier;

import extension.GPresets;
import game.BCCatalog;
import gearth.extensions.parsers.HInventoryItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.List;
import java.util.Set;

/**
 * Pose un mobi mural a une position donnee, depuis l'inventaire ou le catalogue BC.
 *
 * Structures extraites du moteur de l'Atelier, pas devinees :
 *   PlaceObject                 (String "&lt;idInventaire&gt; :w=x,y l=oX,oY d")
 *   BuildersClubPlaceWallItem   (int pageId, int offerId, String etat, String position, false)
 */
public final class PoseMur {

    /** D'ou prendre le meuble. */
    public enum Source { INVENTAIRE, BC, INVENTAIRE_PUIS_BC }

    public static final class Resultat {
        public final boolean ok;
        public final String detail;
        Resultat(boolean ok, String detail) { this.ok = ok; this.detail = detail; }
    }

    private PoseMur() { }

    /**
     * @param dejaUtilises identifiants d'inventaire deja consommes dans la meme
     *                     serie : la liste du moteur de l'Atelier se met a jour de facon
     *                     asynchrone, on tient donc le compte nous-memes.
     */
    public static Resultat poser(GPresets gp, int typeId, String etat, String position,
                                 Source source, Set<Integer> dejaUtilises) {
        if (source != Source.BC) {
            Integer idInv = prochainInventaire(gp, typeId, dejaUtilises);
            if (idInv != null) {
                if (!envoyer(gp, new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        idInv + " " + position)))
                    return new Resultat(false, "envoi refusé (connexion ?)");
                dejaUtilises.add(idInv);
                return new Resultat(true, "depuis l'inventaire");
            }
            if (source == Source.INVENTAIRE)
                return new Resultat(false, "absent de l'inventaire");
        }

        BCCatalog.SingleFurniProduct p = produitBc(gp, typeId, etat);
        if (p == null) return new Resultat(false, "absent du catalogue BC");

        if (!envoyer(gp, new HPacket("BuildersClubPlaceWallItem", HMessage.Direction.TOSERVER,
                p.getPageId(), p.getOfferId(), p.getExtraParam(), position, false)))
            return new Resultat(false, "envoi refusé (connexion ?)");
        return new Resultat(true, "depuis le BC");
    }

    private static boolean envoyer(GPresets gp, HPacket p) {
        try { return gp.sendToServer(p); } catch (Throwable t) { return false; }
    }

    private static Integer prochainInventaire(GPresets gp, int typeId, Set<Integer> deja) {
        try {
            List<HInventoryItem> inv = gp.getInventory().getWallItemsByType(typeId);
            if (inv != null) for (HInventoryItem it : inv)
                if (!deja.contains(it.getId())) return it.getId();
        } catch (Throwable ignored) { }
        return null;
    }

    /** Variante d'etat exacte d'abord : un repli aveugle poserait le mauvais etat. */
    private static BCCatalog.SingleFurniProduct produitBc(GPresets gp, int typeId, String etat) {
        try {
            BCCatalog cat = gp.getCatalog();
            if (cat == null) return null;
            BCCatalog.SingleFurniProduct p = cat.getWallProduct(typeId, etat);
            return p != null ? p : cat.getAnyWallProduct(typeId);
        } catch (Throwable t) { return null; }
    }
}

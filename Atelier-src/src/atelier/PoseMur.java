package atelier;

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
 *   PlacePostIt                 (int idInventaire, String position) pour un post-it
 *   BuildersClubPlaceWallItem   (int pageId, int offerId, String extra du produit, String position, false)
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
    public static Resultat poser(Moteur gp, int typeId, String etat, String position,
                                 Source source, Set<Integer> dejaUtilises) {
        if (source != Source.BC) {
            boolean postIt = PoseOutils.postIt(Salle.classe(typeId, true));
            // un bloc de post-its sert a plusieurs poses (une feuille chacune)
            Integer idInv = prochainInventaire(gp, typeId, postIt ? Set.of() : dejaUtilises);
            if (idInv != null) {
                if (!envoyer(gp, postIt ? PoseOutils.posePostIt(idInv, position)
                        : new HPacket("PlaceObject", HMessage.Direction.TOSERVER, idInv + " " + position)))
                    return new Resultat(false, "envoi refusé (connexion ?)");
                dejaUtilises.add(idInv);
                return new Resultat(true, "depuis l'inventaire");
            }
            if (source == Source.INVENTAIRE)
                return new Resultat(false, "absent de l'inventaire");
        }

        OffresBc.Offre p = offreBc(gp, typeId, etat);
        if (p == null) return new Resultat(false, "pas au Builders Club");

        if (!envoyer(gp, PoseOutils.poseMurBc(p.page(), p.offre(), p.extra(), position)))
            return new Resultat(false, "envoi refusé (connexion ?)");
        return new Resultat(true, "depuis le BC");
    }

    private static boolean envoyer(Moteur gp, HPacket p) {
        try { return gp.sendToServer(p); } catch (Throwable t) { return false; }
    }

    private static Integer prochainInventaire(Moteur gp, int typeId, Set<Integer> deja) {
        try {
            List<HInventoryItem> inv = gp.getInventory().getWallItemsByType(typeId);
            if (inv != null) for (HInventoryItem it : inv)
                if (!deja.contains(it.getId())) return it.getId();
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Variante d'etat exacte d'abord (un repli aveugle poserait le mauvais
     * etat), puis une quelconque ; seulement un mobi « bc » (OffresBc).
     */
    private static OffresBc.Offre offreBc(Moteur gp, int typeId, String etat) {
        try {
            String classe = Salle.classe(typeId, true);
            OffresBc.Offre o = OffresBc.mur(gp.getCatalog(), gp.getFurniDataTools(), classe, etat == null ? "" : etat);
            return o != null ? o : OffresBc.mur(gp.getCatalog(), gp.getFurniDataTools(), classe, null);
        } catch (Throwable t) { return null; }
    }
}

package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

/**
 * Calque de base « Mur » : masquer les murs de l'appart chez toi seulement.
 *
 * Le serveur dit au client comment dessiner la piece par
 * RoomVisualizationSettings (boolean mursCaches, int epaisseurMur, int
 * epaisseurSol ; lu par FloorReseau). On lui renvoie ce paquet avec
 * mursCaches = vrai et les epaisseurs d'origine ; pour reafficher, l'etat
 * d'origine (celui que le serveur avait envoye).
 *
 * Tant que les murs sont masques, un RoomVisualizationSettings du serveur
 * (reglages de la salle changes) est remplace par une copie murs caches.
 * L'ecoute est tres bon marche : rien n'est lu quand les murs sont visibles,
 * et seulement un paquet de quelques octets sinon. L'original n'est jamais
 * modifie (FloorReseau le lit pour connaitre l'etat d'origine).
 *
 * Le SOL, lui, ne peut pas etre masque proprement : aucun paquet du jeu ne le
 * cache (une epaisseur de sol a -2 l'amincit seulement), et retirer les cases
 * du plan (FloorHeightMap) casse les clics et la marche. Le calque « Sol »
 * reste donc verrouille et visible.
 */
final class CalqueMurs {

    private CalqueMurs() { }

    /** Les murs doivent rester caches chez le client. */
    private static volatile boolean caches = false;
    private static volatile boolean installe = false, enCours = false;

    static boolean caches() { return caches; }

    /** Vrai si les reglages d'origine de la salle ont ete lus (sinon on reaffiche avec des epaisseurs par defaut). */
    static boolean origineConnue() { return FloorReseau.visuRecue > 0; }

    /** Le calque Mur n'est plus masque (changement de salle : le client recoit ses vrais reglages). */
    static void oublier() { caches = false; }

    /**
     * Masque (ou reaffiche) les murs chez toi.
     * @return false si le paquet n'a pas pu partir
     */
    static boolean cacher(boolean cacher) {
        installer();
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        caches = cacher;
        boolean ok = envoyer(gp, cacher || FloorReseau.mursCaches, FloorReseau.epMur, FloorReseau.epSol);
        if (!ok && cacher) caches = false;
        return ok;
    }

    private static boolean envoyer(Moteur gp, boolean murs, int epMur, int epSol) {
        try {
            HPacket p = new HPacket("RoomVisualizationSettings", HMessage.Direction.TOCLIENT);
            p.appendBoolean(murs);
            p.appendInt(epMur);
            p.appendInt(epSol);
            return gp.sendToClient(p);
        } catch (Throwable t) {
            System.err.println("[Atelier] calque Mur : envoi impossible : " + t);
            return false;
        }
    }

    static synchronized void installer() {
        if (installe || enCours) return;
        enCours = true;
        FloorReseau.installer();                 // l'etat d'origine (murs, epaisseurs)
        Salle.tache("calque-murs-ecoute", () -> {
            for (int i = 0; i < 900 && !installe; i++) {
                Moteur gp = Salle.gp();
                if (gp != null) {
                    try {
                        gp.intercept(HMessage.Direction.TOCLIENT, "RoomVisualizationSettings", CalqueMurs::examiner);
                        installe = true;
                    } catch (Throwable t) {
                        System.err.println("[Atelier] calque Mur : ecoute indisponible : " + t);
                    }
                    return;
                }
                Salle.sommeil(1000);
            }
        });
    }

    /** Intercepteur : rien a faire tant que les murs sont visibles. */
    private static void examiner(HMessage m) {
        if (!caches) return;
        HPacket brut = m.getPacket();
        if (brut == null || brut.getBytesLength() > 64) return;
        try {
            HPacket p = new HPacket(brut);
            p.resetReadIndex();
            boolean murs = p.readBoolean();
            int a = p.readInteger(), b = p.readInteger();
            if (murs) return;
            m.setBlocked(true);
            Moteur gp = Salle.gp();
            if (gp != null) envoyer(gp, true, a, b);
        } catch (Throwable ignored) { }
    }
}

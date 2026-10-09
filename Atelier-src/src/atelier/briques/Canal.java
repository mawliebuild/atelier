package atelier;

import gearth.extensions.IExtension;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.function.Consumer;

/**
 * Le seul lien des briques avec le proxy : ecouter un paquet par son nom,
 * envoyer au serveur, envoyer au jeu.
 *
 * Aujourd'hui, Canal.de(extension) enveloppe l'extension en place (Salle.gp() :
 * intercept, sendToServer, sendToClient, herites de gearth.extensions.ExtensionForm).
 * Plus tard, la meme enveloppe servira pour notre propre extension : les briques
 * n'en sauront rien.
 *
 * Contrat des ecouteurs : ils sont appeles sur le fil des paquets, l'un apres
 * l'autre, l'index de lecture remis au debut avant chacun (G-Earth le fait).
 * Une exception d'un ecouteur est rattrapee ici (Journal.debug) : elle ne doit
 * jamais empecher les autres ecouteurs du meme paquet de passer.
 */
interface Canal {

    /** Ecoute un paquet par son nom (direction TOCLIENT ou TOSERVER). */
    void intercept(HMessage.Direction direction, String nomPaquet, Consumer<HMessage> ecouteur);

    /** Ecoute tous les paquets d'une direction (diagnostic) ; par defaut : rien. */
    default void interceptTout(HMessage.Direction direction, Consumer<HMessage> ecouteur) { }

    /** Nom d'un paquet d'apres son numero, ou null. */
    default String nomPaquet(HMessage.Direction direction, int numero) { return null; }

    boolean sendToServer(HPacket paquet);

    boolean sendToClient(HPacket paquet);

    /** Le canal de l'extension actuelle (Salle.gp()), ou null si elle n'est pas encore prete. */
    static Canal duMoteur() {
        IExtension e = AtelierLauncher.moteur();
        return e == null ? null : de(e);
    }

    /** Enveloppe une extension G-Earth. */
    static Canal de(IExtension extension) {
        if (extension == null) throw new IllegalArgumentException("Extension absente.");
        return new SurExtension(extension);
    }

    /** L'enveloppe d'une extension G-Earth. */
    final class SurExtension implements Canal {
        private final IExtension extension;

        SurExtension(IExtension extension) { this.extension = extension; }

        @Override
        public void intercept(HMessage.Direction direction, String nomPaquet, Consumer<HMessage> ecouteur) {
            extension.intercept(direction, nomPaquet, m -> {
                try {
                    ecouteur.accept(m);
                } catch (Throwable t) {
                    Journal.debug("Briques : lecture de " + nomPaquet + " en erreur : " + t);
                }
            });
        }

        @Override
        public void interceptTout(HMessage.Direction direction, Consumer<HMessage> ecouteur) {
            extension.intercept(direction, m -> {
                try { ecouteur.accept(m); } catch (Throwable ignored) { }
            });
        }

        @Override
        public String nomPaquet(HMessage.Direction direction, int numero) {
            try {
                gearth.services.packet_info.PacketInfo i =
                        extension.getPacketInfoManager().getPacketInfoFromHeaderId(direction, numero);
                return i == null ? null : i.getName();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean sendToServer(HPacket paquet) { return extension.sendToServer(paquet); }

        @Override public boolean sendToClient(HPacket paquet) { return extension.sendToClient(paquet); }
    }
}

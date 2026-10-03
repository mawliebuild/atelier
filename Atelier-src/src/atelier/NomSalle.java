package atelier;

import extension.GPresets;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

/**
 * Retient le nom de la salle courante et son proprietaire.
 *
 * FloorState ne garde que l'identifiant et le modele. Le nom arrive dans
 * GetGuestRoomResult, dont la charge utile commence par :
 *     boolean entree, int idSalle, String nom, int idProprietaire,
 *     String proprietaire, int acces, int presents, int maximum, String description...
 *
 * Le paquet est reconnu A SON CONTENU, pas a son nom. L'interception par nom
 * echoue en silence quand le nom ne se resout pas cote G-Earth : l'installation
 * reussit, la trace annonce « interception active », et le rappel n'est jamais
 * appele. C'est exactement ce qui se passait ici — « Salle actuelle » restait
 * sur un tiret.
 *
 * On valide l'identifiant lu contre celui de FloorState quand il est connu :
 * sans ca, un paquet concernant une AUTRE salle (survol dans le navigateur, par
 * exemple) ecraserait le nom affiche.
 */
public final class NomSalle {

    private static volatile String nom;
    private static volatile String proprietaire;
    private static volatile int idRetenu = -1;
    private static boolean installe = false;

    private NomSalle() { }

    public static String nom() { return nom; }

    /** Pseudo du proprietaire de la salle, ou null. */
    public static String proprietaire() { return proprietaire; }

    public static synchronized void installer() {
        if (installe) return;
        GPresets gp = AtelierLauncher.gpresets();
        if (gp == null) return;
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, m -> {
                try {
                    HPacket brut = m.getPacket();
                    // Ecarter tout de suite ce qui ne peut pas etre le bon
                    // paquet : la salle en envoie de tres gros (des milliers de
                    // mobis), inutile d'en recopier un seul octet.
                    int n = brut.getBytesLength();
                    if (n < 30 || n > 4096) return;
                    // Premier champ : un booleen (0 ou 1). Teste sur l'original,
                    // avant toute copie : la plupart des paquets s'arretent la.
                    byte b0 = brut.readByte(6);
                    if (b0 != 0 && b0 != 1) return;
                    lire(gp, brut);
                } catch (Throwable ignored) { }
            });
            installe = true;
            System.out.println("[Atelier] nom de salle : écoute active (par contenu).");
        } catch (Throwable t) {
            System.err.println("[Atelier] nom de salle indisponible : " + t);
        }
    }

    /**
     * Reconnait GetGuestRoomResult a sa signature.
     *
     * Un booleen, un identifiant plausible, un nom de salle, un identifiant de
     * proprietaire, un pseudo, puis un mode d'acces de 0 a 3 : aucun autre
     * paquet du jeu n'enchaine ces six champs.
     */
    private static void lire(GPresets gp, HPacket paquet) {
        HPacket p = new HPacket(paquet);
        p.resetReadIndex();

        int entree = p.readByte();                    // booleen : 0 ou 1
        if (entree != 0 && entree != 1) return;

        int id = p.readInteger();
        if (id <= 0 || id > 900_000_000) return;

        // readString() de G-Earth lit en Latin-1 : on repare en UTF-8 (voir utf8).
        String lu = utf8(p.readString());
        if (lu == null || lu.isEmpty() || lu.length() > 60 || !lisible(lu)) return;

        int idProp = p.readInteger();
        if (idProp <= 0 || idProp > 900_000_000) return;

        String pseudo = utf8(p.readString());
        if (pseudo == null || pseudo.isEmpty() || pseudo.length() > 40 || !lisible(pseudo)) return;

        int acces = p.readInteger();
        if (acces < 0 || acces > 3) return;

        // N'accepter que la salle ou l'on se trouve reellement.
        int courant = -1;
        try {
            if (gp.getFloorState() != null) courant = gp.getFloorState().getRoomId();
        } catch (Throwable ignored) { }
        if (courant > 0 && id != courant) return;

        nom = lu;
        proprietaire = pseudo;
        idRetenu = id;
    }

    /**
     * Le client envoie ses textes en UTF-8, mais HPacket.readString() de
     * G-Earth les decode en Latin-1 : « © Loft bordélique » devient
     * « Â© Loft bordÃ©lique ». Latin-1 etant un decodage octet pour octet, on
     * retrouve les octets d'origine et on les relit en UTF-8, en mode strict :
     * si ce n'est pas de l'UTF-8 valide (texte deja correct, ou vrai Latin-1),
     * le texte est rendu tel quel. Un texte deja juste n'est donc jamais abime :
     * de l'UTF-8 valide qui serait AUSSI un texte Latin-1 sense est
     * pratiquement impossible (il faudrait « Ã » suivi d'un caractere de
     * controle C1 ou d'un symbole comme « © »).
     */
    public static String utf8(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean haut = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) return s;            // deja de l'Unicode : rien a faire
            if (c >= 0x80) haut = true;
        }
        if (!haut) return s;                   // ASCII pur : identique
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return s;
        }
    }

    /** Ecarte les suites d'octets qui ne sont pas du texte affichable. */
    private static boolean lisible(String s) {
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < 0x20) return false;
        return true;
    }

    /** Le nom n'est valable que pour la salle ou l'on est encore. */
    public static String nomValide(GPresets gp) {
        try {
            if (gp != null && gp.getFloorState() != null) {
                int courant = gp.getFloorState().getRoomId();
                if (courant > 0 && idRetenu > 0 && courant != idRetenu) return null;
            }
        } catch (Throwable ignored) { }
        return nom;
    }
}

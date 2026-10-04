package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

/**
 * Les droits dans la salle ouverte. Remplace RoomPermissions (ancien module), en ecoute seule.
 *
 * Memes paquets (TOCLIENT sauf Quit) :
 *   WiredPermissions     boolean                -> peutRegler (wired)
 *   YouAreController     (int salle, int niveau) -> peutDeplacer = vrai
 *   YouAreNotController                         -> peutDeplacer = faux
 *   CloseConnection, Quit (TOSERVER), RoomReady -> tout a faux
 * En plus : YouAreOwner et RoomEntryInfo (int salle, boolean proprietaire)
 * pour savoir si l'on est proprietaire, et le niveau de droits s'il est dit.
 */
final class Droits {

    private volatile boolean peutRegler, peutDeplacer, proprietaire;
    private volatile int niveau = -1;

    Droits(Canal canal) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "WiredPermissions", m -> peutRegler = m.getPacket().readBoolean());
        canal.intercept(C, "YouAreController", this::surControleur);
        canal.intercept(C, "YouAreNotController", m -> { peutDeplacer = false; niveau = 0; });
        canal.intercept(C, "YouAreOwner", m -> proprietaire = true);
        canal.intercept(C, "RoomEntryInfo", this::surEntree);
        canal.intercept(C, "CloseConnection", m -> vider());
        canal.intercept(HMessage.Direction.TOSERVER, "Quit", m -> vider());
        canal.intercept(C, "RoomReady", m -> vider());
    }

    private void surControleur(HMessage m) {
        peutDeplacer = true;
        HPacket p = m.getPacket();
        int n = p.getBytesLength();
        if (n >= 14) niveau = p.readInteger(10);          // int salle, int niveau
        else if (n >= 10) niveau = p.readInteger(6);      // int niveau seul
    }

    private void surEntree(HMessage m) {
        HPacket p = m.getPacket();
        if (p.getBytesLength() >= 11 && p.readBoolean(10)) proprietaire = true;
    }

    /** Peut deplacer, poser et ramasser les mobis (ex-canMoveFurni). */
    boolean peutDeplacer() { return peutDeplacer; }

    /** Peut regler les wired (ex-canModifyWired). */
    boolean peutRegler() { return peutRegler; }

    /** Proprietaire de la salle ouverte. */
    boolean proprietaire() { return proprietaire; }

    /** Niveau de droits dit par YouAreController (0 sans droits), -1 si inconnu. */
    int niveau() { return niveau; }

    void vider() {
        peutRegler = false;
        peutDeplacer = false;
        proprietaire = false;
        niveau = -1;
    }

    // noms d'origine (RoomPermissions)
    boolean canMoveFurni() { return peutDeplacer(); }

    boolean canModifyWired() { return peutRegler(); }

    void clear() { vider(); }
}

package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Diagnostic des poses et ramassages lents : le temps entre l'action du jeu
 * (PlaceObject, PickupObject, MoveObject, MoveWallItem) et la reponse du
 * serveur (ObjectAdd, ObjectRemove, ObjectUpdate, ItemAdd, ItemRemove,
 * ItemUpdate). Au-dela de LENT_MS, une ligne « lenteur : pose … » dans le
 * journal, avec ce que l'Atelier faisait en fond a ce moment (inventaire,
 * catalogue BC). Les ecouteurs ne font que noter l'heure : rien n'est retenu.
 */
final class MesureActions {

    private MesureActions() { }

    static final long LENT_MS = 600;
    /** Une action sans reponse depuis si longtemps : oubliee. */
    private static final long OUBLI_MS = 15_000;

    /** Poses en attente (le serveur donne un nouvel id : la premiere reponse d'ajout). */
    private static final ConcurrentLinkedDeque<long[]> poses = new ConcurrentLinkedDeque<>();
    /** Ramassages et deplacements en attente, par id (« s12 », « m34 »). */
    private static final Map<String, Long> parId = new ConcurrentHashMap<>();
    private static volatile boolean branche = false;

    /** Les derniers paquets vus (heure, sens, origine, nom) : pour dire ce qui s'est passe pendant l'attente. */
    private static final ConcurrentLinkedDeque<Object[]> derniers = new ConcurrentLinkedDeque<>();
    private static final int GARDES = 400;

    private static void noter(String sens, String nom) {
        derniers.add(new Object[]{System.currentTimeMillis(), sens, nom});
        while (derniers.size() > GARDES) derniers.poll();
    }

    private static final Map<String, String> noms = new ConcurrentHashMap<>();

    /** Nom d'un paquet (garde en cache une fois connu). */
    private static String nom(Moteur gp, HMessage.Direction d, int entete) {
        String k = (d == HMessage.Direction.TOSERVER ? "s" : "c") + entete;
        String n = noms.get(k);
        if (n != null) return n;
        try {
            gearth.services.packet_info.PacketInfo i = gp.getPacketInfoManager().getPacketInfoFromHeaderId(d, entete);
            if (i == null || i.getName() == null) return "#" + entete;
            noms.put(k, i.getName());
            return i.getName();
        } catch (Throwable t) { return "#" + entete; }
    }

    /** Un paquet envoye par l'Atelier lui-meme (appele par Moteur.sendToServer / sendToClient). */
    static void noteEnvoi(boolean versServeur, HPacket p) {
        if (!branche || p == null) return;
        String nom = "#" + p.headerId();
        try {
            String id = p.packetIncompleteIdentifier();     // paquet cree par son nom : pas encore d'en-tete
            if (id != null && !id.isEmpty()) { noter(versServeur ? "Atelier→serveur" : "Atelier→jeu", id); return; }
        } catch (Throwable ignored) { }
        try { Moteur gp = Salle.gp(); if (gp != null) nom = nom(gp, versServeur ? HMessage.Direction.TOSERVER : HMessage.Direction.TOCLIENT, p.headerId()); }
        catch (Throwable ignored) { }
        noter(versServeur ? "Atelier→serveur" : "Atelier→jeu", nom);
    }

    /** Branche les ecoutes des que le moteur est la. */
    static void demarrer() {
        Salle.tache("mesure-actions", () -> {
            while (Salle.gp() == null) Salle.sommeil(1000);
            brancher(Salle.gp());
        });
    }

    static synchronized void brancher(Moteur gp) {
        if (branche || gp == null) return;
        branche = true;
        HMessage.Direction S = HMessage.Direction.TOSERVER, C = HMessage.Direction.TOCLIENT;
        // tous les paquets : compte et silences pendant une attente
        try {
            gp.intercept(S, m -> { try { noter("jeu→serveur", nom(gp, S, m.getPacket().headerId())); } catch (Throwable ignored) { } });
            gp.intercept(C, m -> { try { noter("serveur→jeu", nom(gp, C, m.getPacket().headerId())); } catch (Throwable ignored) { } });
        } catch (Throwable t) { Journal.debug("mesure des poses : écoute générale indisponible : " + t); }
        ecoute(gp, S, "PlaceObject", m -> {
            boolean mur = false;
            try { String s = new HPacket(m.getPacket()).readString(); mur = s != null && s.indexOf(':') >= 0; } catch (Throwable ignored) { }
            poses.add(new long[]{System.currentTimeMillis(), mur ? 1 : 0});
        });
        ecoute(gp, S, "PickupObject", m -> {
            HPacket p = m.getPacket();
            if (p.getBytesLength() < 14) return;
            parId.put((p.readInteger(6) == 1 ? "m" : "s") + p.readInteger(10), System.currentTimeMillis());
        });
        ecoute(gp, S, "MoveObject", m -> {
            HPacket p = m.getPacket();
            if (p.getBytesLength() >= 10) parId.put("s" + p.readInteger(6), System.currentTimeMillis());
        });
        ecoute(gp, S, "MoveWallItem", m -> {
            HPacket p = m.getPacket();
            if (p.getBytesLength() >= 10) parId.put("m" + p.readInteger(6), System.currentTimeMillis());
        });
        ecoute(gp, C, "ObjectAdd", m -> reponsePose(false, "ObjectAdd"));
        ecoute(gp, C, "ItemAdd", m -> reponsePose(true, "ItemAdd"));
        ecoute(gp, C, "ObjectRemove", m -> reponse("s" + texte(m), "ramassage", "ObjectRemove"));
        ecoute(gp, C, "ItemRemove", m -> reponse("m" + texte(m), "ramassage", "ItemRemove"));
        ecoute(gp, C, "ObjectUpdate", m -> {
            HPacket p = m.getPacket();
            if (p.getBytesLength() >= 10) reponse("s" + p.readInteger(6), "déplacement", "ObjectUpdate");
        });
        ecoute(gp, C, "ItemUpdate", m -> reponse("m" + texte(m), "déplacement", "ItemUpdate"));
        Journal.debug("mesure des poses : écoutes actives.");
    }

    private static void ecoute(Moteur gp, HMessage.Direction d, String nom, java.util.function.Consumer<HMessage> l) {
        try {
            gp.intercept(d, nom, m -> { try { l.accept(m); } catch (Throwable ignored) { } });
        } catch (Throwable t) {
            Journal.debug("mesure des poses : écoute " + nom + " indisponible : " + t);
        }
    }

    /** Premier texte du paquet (l'id en texte de ObjectRemove, ItemRemove, ItemUpdate). */
    private static String texte(HMessage m) {
        try { return m.getPacket().readString(6).trim(); } catch (Throwable t) { return "?"; }
    }

    private static void reponsePose(boolean mur, String paquet) {
        long now = System.currentTimeMillis();
        for (long[] p : poses) {
            if (now - p[0] > OUBLI_MS) { poses.remove(p); continue; }
            if ((p[1] == 1) != mur) continue;
            poses.remove(p);
            signaler("pose", paquet, now - p[0]);
            return;
        }
    }

    private static void reponse(String cle, String action, String paquet) {
        Long t = parId.remove(cle);
        if (t == null) return;
        long ms = System.currentTimeMillis() - t;
        if (ms <= OUBLI_MS) signaler(action, paquet, ms);
        if (parId.size() > 200) parId.values().removeIf(v -> System.currentTimeMillis() - v > OUBLI_MS);
    }

    private static void signaler(String action, String paquet, long ms) {
        if (ms < LENT_MS) return;
        Journal.debug("lenteur : " + action + " — réponse du serveur (" + paquet + ") en " + ms + " ms ; en fond : " + fond()
                + " ; pendant l'attente : " + pendant(System.currentTimeMillis() - ms) + ".");
    }

    /** Ce qui est passe depuis « debut » : nombre de paquets par sens, plus long silence du serveur, envois de l'Atelier. */
    private static String pendant(long debut) {
        int recus = 0, jeu = 0;
        long dernierRecu = debut, silence = 0;
        java.util.Map<String, Integer> atelier = new java.util.TreeMap<>();
        for (Object[] o : derniers) {
            long t = (Long) o[0];
            if (t < debut) continue;
            String sens = (String) o[1], nom = (String) o[2];
            if (sens.equals("serveur→jeu")) { recus++; silence = Math.max(silence, t - dernierRecu); dernierRecu = t; }
            else if (sens.equals("jeu→serveur")) jeu++;
            else atelier.merge(sens + " " + nom, 1, Integer::sum);
        }
        silence = Math.max(silence, System.currentTimeMillis() - dernierRecu);
        StringBuilder sb = new StringBuilder();
        sb.append(recus).append(" paquets reçus du serveur (plus long silence ").append(silence).append(" ms), ")
          .append(jeu).append(" envoyés par le jeu");
        if (atelier.isEmpty()) sb.append(", rien envoyé par l'Atelier");
        else {
            sb.append(", envoyés par l'Atelier : ");
            List<String> l = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : atelier.entrySet()) l.add(e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : ""));
            sb.append(String.join(", ", l));
        }
        return sb.toString();
    }

    /** Ce que l'Atelier faisait en fond (ce qui peut occuper la connexion). */
    private static String fond() {
        StringBuilder sb = new StringBuilder();
        try {
            Moteur gp = Salle.gp();
            if (gp != null) {
                if (gp.inventaireEnChargement()) sb.append("inventaire en chargement, ");
                String bc = String.valueOf(gp.getCatalog().getState());
                if ("AWAITING_INDEX".equals(bc) || "COLLECTING_PAGES".equals(bc)) sb.append("catalogue BC en lecture, ");
            }
        } catch (Throwable ignored) { }
        if (sb.length() == 0) return "rien de particulier";
        return sb.substring(0, sb.length() - 2);
    }
}

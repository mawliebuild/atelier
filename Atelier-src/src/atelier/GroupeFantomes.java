package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apercu « fantome » d'un deplacement ou d'une duplication de calque : des
 * copies FICTIVES des mobis du calque, a la position cible, envoyees au
 * client seulement (ObjectAdd / ItemAdd construits ici). Le serveur n'en sait
 * rien. Meme principe que GrilleReseau, avec SA PROPRE plage d'ids :
 *
 *   2 141 000 000 .. 2 141 999 999   (GrilleCalcul : 2 140 000 000 .. 2 140 524 287)
 *
 * Chaque mobi original recoit un id fantome fixe tant que l'apercu dure : un
 * changement de (dx, dy) n'envoie qu'un ObjectUpdate / ItemUpdate par mobi
 * (pas de clignotement), un mobi qui sort du calque est retire.
 *
 * Securite (comme GrilleReseau) :
 *   - changement de salle / rechargement (RoomReady, Objects, FloorHeightMap) :
 *     le client a tout jete, on oublie ;
 *   - toute action du client sur un id fantome (clic, utiliser, deplacer,
 *     tourner, ramasser) est BLOQUEE vers le serveur ; « ramasser » le retire
 *     seulement chez toi ;
 *   - si le serveur annonce un vrai mobi avec un de nos ids, on le lui laisse.
 *
 * Paquets (verifies au javap de la bibliotheque du proxy) :
 *   ObjectAdd    HFloorItem.appendToPacket puis String nomProprio
 *   ObjectUpdate HFloorItem.appendToPacket (sans nom)
 *   ItemAdd      HWallItem.appendToPacket  puis String nomProprio
 *   ItemUpdate   HWallItem.appendToPacket  (sans nom)
 * Le fantome est un CLONE de l'original (relu par le vrai parseur) : meme
 * classe, etat (stuffdata de toute sorte), rotation ; seuls l'id et la place
 * changent.
 */
final class GroupeFantomes {

    private GroupeFantomes() { }

    static final int BASE = 2_141_000_000;
    static final int TAILLE = 1_000_000;
    static final String PROPRIO = "Aperçu (Atelier)";

    static boolean estFantome(int id) { return id >= BASE && id < BASE + TAILLE; }

    // ------------------------------------------------------- paquets (purs)

    /** Copie fidele d'un mobi de sol, par aller-retour dans le vrai parseur. */
    static HFloorItem cloner(HFloorItem it) {
        HPacket t = new HPacket(1);
        it.appendToPacket(t);
        t.resetReadIndex();
        HFloorItem c = new HFloorItem(t);
        c.setOwnerName(it.getOwnerName());
        return c;
    }

    static HWallItem cloner(HWallItem it) {
        HPacket t = new HPacket(1);
        it.appendToPacket(t);
        t.resetReadIndex();
        HWallItem c = new HWallItem(t);
        c.setOwnerName(it.getOwnerName());
        return c;
    }

    /** Corps d'un ObjectAdd (avec nom) ou ObjectUpdate (sans) pour le fantome. */
    static void ecrireSol(HPacket p, HFloorItem original, int idFantome, int x, int y, double z, boolean avecNom) {
        ecrireSol(p, original, idFantome, x, y, z, -1, avecNom);
    }

    static void ecrireSol(HPacket p, HFloorItem original, int idFantome, int x, int y, double z, int rot, boolean avecNom) {
        HFloorItem c = cloner(original);
        c.setId(idFantome);
        if (rot >= 0) c.setFacing(gearth.extensions.parsers.HDirection.values()[rot & 7]);
        c.setTile(new HPoint(x, y, Math.round(Math.max(0, z) * 100.0) / 100.0));
        c.setSecondsToExpiration(-1);
        c.appendToPacket(p);
        if (avecNom) p.appendString(PROPRIO);
    }

    static void ecrireMur(HPacket p, HWallItem original, int idFantome, String position, boolean avecNom) {
        HWallItem c = cloner(original);
        c.setId(idFantome);
        c.setLocation(position);
        c.setSecondsToExpiration(-1);
        c.appendToPacket(p);
        if (avecNom) p.appendString(PROPRIO);
    }

    static HPacket ajoutSol(HFloorItem o, int id, int x, int y, double z) {
        HPacket p = new HPacket("ObjectAdd", HMessage.Direction.TOCLIENT);
        ecrireSol(p, o, id, x, y, z, true);
        return p;
    }

    static HPacket majSol(HFloorItem o, int id, int x, int y, double z) {
        HPacket p = new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT);
        ecrireSol(p, o, id, x, y, z, false);
        return p;
    }

    static HPacket ajoutMur(HWallItem o, int id, String pos) {
        HPacket p = new HPacket("ItemAdd", HMessage.Direction.TOCLIENT);
        ecrireMur(p, o, id, pos, true);
        return p;
    }

    static HPacket majMur(HWallItem o, int id, String pos) {
        HPacket p = new HPacket("ItemUpdate", HMessage.Direction.TOCLIENT);
        ecrireMur(p, o, id, pos, false);
        return p;
    }

    /** ObjectRemove(String id, boolean expire, int idRamasseur, int delai), comme Calques. */
    static HPacket retraitSol(int id) {
        HPacket p = new HPacket("ObjectRemove", HMessage.Direction.TOCLIENT);
        p.appendString(String.valueOf(id));
        p.appendBoolean(false);
        p.appendInt(0);
        p.appendInt(0);
        return p;
    }

    /** ItemRemove(String id, int idRamasseur). */
    static HPacket retraitMur(int id) {
        HPacket p = new HPacket("ItemRemove", HMessage.Direction.TOCLIENT);
        p.appendString(String.valueOf(id));
        p.appendInt(0);
        return p;
    }

    // ------------------------------------------------------------- etat

    /** Un fantome voulu ou affiche. */
    static final class Fantome {
        final int idFantome, original;
        final boolean mural;
        final int x, y;
        final double z;
        final String position;
        /** sol : rotation montree, -1 = celle de l'original */
        final int rot;
        Fantome(int idFantome, int original, boolean mural, int x, int y, double z, String position) {
            this(idFantome, original, mural, x, y, z, position, -1);
        }
        Fantome(int idFantome, int original, boolean mural, int x, int y, double z, String position, int rot) {
            this.idFantome = idFantome; this.original = original; this.mural = mural;
            this.x = x; this.y = y; this.z = z; this.position = position; this.rot = rot;
        }
        boolean memePlace(Fantome o) {
            if (o == null || o.mural != mural) return false;
            return mural ? Objects.equals(position, o.position)
                    : (o.x == x && o.y == y && Math.abs(o.z - z) < 0.005 && o.rot == rot);
        }
    }

    /** Ce qui est affiche chez le client : id fantome -> fantome. */
    static final Map<Integer, Fantome> affiches = new ConcurrentHashMap<>();
    /** Id fantome attribue a chaque original (cle : m/s + id). */
    private static final Map<String, Integer> attribues = new ConcurrentHashMap<>();
    private static int compteur = 0;
    private static volatile int salle = -1;
    static volatile boolean vusParMoteur = false;

    static synchronized int idPour(int original, boolean mural) {
        String k = (mural ? "m" : "s") + original;
        Integer v = attribues.get(k);
        if (v != null) return v;
        int id = BASE + (compteur++ % TAILLE);
        attribues.put(k, id);
        return id;
    }

    static int nombre() { return affiches.size(); }

    /** Le client a tout jete : on oublie sans rien envoyer. */
    static void oublier() {
        affiches.clear();
        envoisRecents.clear();
        attribues.clear();
        salle = -1;
    }

    /** Ce qu'il faut faire pour passer de « affiche » a « voulu ». */
    static final class Diff {
        final List<Fantome> retirer = new ArrayList<>(), ajouter = new ArrayList<>(), bouger = new ArrayList<>();
        int total() { return retirer.size() + ajouter.size() + bouger.size(); }
    }

    static Diff diff(Map<Integer, Fantome> affiche, Collection<Fantome> voulu) {
        Diff d = new Diff();
        Map<Integer, Fantome> v = new LinkedHashMap<>();
        for (Fantome f : voulu) v.put(f.idFantome, f);
        for (Fantome a : affiche.values()) if (!v.containsKey(a.idFantome)) d.retirer.add(a);
        for (Fantome f : v.values()) {
            Fantome a = affiche.get(f.idFantome);
            if (a == null) d.ajouter.add(f);
            else if (!a.memePlace(f)) d.bouger.add(f);
        }
        return d;
    }

    // ------------------------------------------------------------- envoi

    static final int PAR_LOT = 50;
    static final long PAUSE = 150;

    /**
     * Applique la difference (hors fil FX). S'interrompt (sans perdre le fil :
     * « affiches » reste exact) si « encore » devient faux.
     * @return nombre d'envois echoues
     */
    static int appliquer(Diff d, java.util.function.BooleanSupplier encore) {
        GPresets gp = Salle.gp();
        if (gp == null) return d.total();
        int ici = Groupes.salleCourante();
        if (ici == -1) return d.total();
        if (salle != ici) { affiches.clear(); salle = ici; }
        int n = 0, ko = 0;
        for (Fantome f : d.retirer) {
            if (!encore.getAsBoolean()) return ko;
            boolean ok = envoyer(gp, f.mural ? retraitMur(f.idFantome) : retraitSol(f.idFantome));
            affiches.remove(f.idFantome);
            if (!ok) ko++;
            if (++n % PAR_LOT == 0) Salle.sommeil(PAUSE);
        }
        for (Fantome f : d.bouger) {
            if (!encore.getAsBoolean()) return ko;
            HPacket p = paquet(f, false);
            if (p != null && envoyer(gp, p)) affiches.put(f.idFantome, f); else ko++;
            if (++n % PAR_LOT == 0) Salle.sommeil(PAUSE);
        }
        for (Fantome f : d.ajouter) {
            if (!encore.getAsBoolean()) return ko;
            HPacket p = paquet(f, true);
            envoisRecents.put(f.idFantome, System.currentTimeMillis());
            if (p != null && envoyer(gp, p)) affiches.put(f.idFantome, f); else ko++;
            if (++n % PAR_LOT == 0) Salle.sommeil(PAUSE);
        }
        if (!d.ajouter.isEmpty()) {
            Fantome f = d.ajouter.get(0);
            Salle.sommeil(200);
            try {
                if (f.mural ? Salle.mur(f.idFantome) != null : Salle.sol(f.idFantome) != null) vusParMoteur = true;
            } catch (Throwable ignored) { }
        }
        return ko;
    }

    /** Tout retirer chez le client. */
    static void toutRetirer() {
        if (affiches.isEmpty()) return;
        Diff d = new Diff();
        d.retirer.addAll(affiches.values());
        appliquer(d, () -> true);
    }

    private static HPacket paquet(Fantome f, boolean ajout) {
        try {
            if (f.mural) {
                HWallItem o = Salle.mur(f.original);
                if (o == null) return null;
                return ajout ? ajoutMur(o, f.idFantome, f.position) : majMur(o, f.idFantome, f.position);
            }
            HFloorItem o = Salle.sol(f.original);
            if (o == null) return null;
            HPacket p = new HPacket(ajout ? "ObjectAdd" : "ObjectUpdate", HMessage.Direction.TOCLIENT);
            ecrireSol(p, o, f.idFantome, f.x, f.y, f.z, f.rot, ajout);
            return p;
        } catch (Throwable t) {
            System.err.println("[Atelier] calques : fantome impossible pour " + f.original + " : " + t);
            return null;
        }
    }

    /** Empreintes de nos propres envois, au cas ou ils repasseraient par nos ecoutes. */
    private static final Set<Integer> empreintes = ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<Integer> ordreEmpreintes = new ArrayDeque<>();

    private static boolean envoyer(GPresets gp, HPacket p) {
        try {
            int e = Arrays.hashCode(p.toBytes());
            synchronized (ordreEmpreintes) {
                empreintes.add(e);
                ordreEmpreintes.addLast(e);
                while (ordreEmpreintes.size() > 512) empreintes.remove(ordreEmpreintes.removeFirst());
            }
            return gp.sendToClient(p);
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------- ecoutes

    private static volatile boolean branche = false;

    static boolean branche() { return branche; }

    /** A appeler regulierement : ne branche qu'une fois, des que le moteur de l'Atelier est la. */
    static synchronized void brancher() {
        if (branche) return;
        GPresets gp = Salle.gp();
        if (gp == null) return;
        branche = true;
        HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;
        for (String nom : new String[]{"RoomReady", "Objects", "FloorHeightMap"}) {
            try { gp.intercept(C, nom, m -> { if (!affiches.isEmpty()) { oublier(); Groupes.apercuPerdu(); } }); }
            catch (Throwable t) { Journal.debug("calques : ecoute " + nom + " indisponible : " + t); }
        }
        for (String nom : new String[]{"ObjectAdd", "ItemAdd"}) {
            try {
                gp.intercept(C, nom, m -> {
                    if (affiches.isEmpty() || m.getPacket().getBytesLength() < 10) return;
                    try {
                        HPacket p = m.getPacket();
                        int id = nom.equals("ObjectAdd") ? p.readInteger(6) : entier(new HPacket(p).readString());
                        if (estFantome(id) && !nosEnvois(m, id) && affiches.remove(id) != null)
                            Journal.debug("calques : un vrai mobi porte l'id fantome " + id + ", laisse tel quel.");
                    } catch (Throwable ignored) { }
                });
            } catch (Throwable ignored) { }
        }
        // Actions du client sur un fantome : id en premier entier
        for (String nom : new String[]{"ClickFurni", "UseFurniture", "MoveObject", "MoveWallItem", "UseWallItem"}) {
            try {
                gp.intercept(S, nom, m -> {
                    if (affiches.isEmpty() || m.getPacket().getBytesLength() < 10) return;
                    try { if (estFantome(m.getPacket().readInteger(6))) m.setBlocked(true); } catch (Throwable ignored) { }
                });
            } catch (Throwable t) { Journal.debug("calques : ecoute " + nom + " indisponible : " + t); }
        }
        // PickupObject(int categorie, int id) : bloque, le fantome disparait chez toi seulement
        try {
            gp.intercept(S, "PickupObject", m -> {
                if (affiches.isEmpty() || m.getPacket().getBytesLength() < 14) return;
                try {
                    int id = m.getPacket().readInteger(10);
                    if (!estFantome(id)) return;
                    m.setBlocked(true);
                    Fantome f = affiches.remove(id);
                    if (f != null) Salle.tache("calques-fantome-ramasse", () -> {
                        envoyer(gp, f.mural ? retraitMur(id) : retraitSol(id));
                        Groupes.prevenir();
                    });
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
        // Filet : tout petit paquet sortant qui porte un id fantome (entier ou texte), quel que soit son nom.
        try {
            gp.intercept(S, m -> {
                if (affiches.isEmpty()) return;
                int taille = m.getPacket().getBytesLength();
                if (taille < 10 || taille > 60) return;
                try { if (porteFantome(m.getPacket())) m.setBlocked(true); } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
        Journal.debug("calques : ecoutes de l'apercu actives.");
    }

    /** Un id fantome affiche, en entier (n'importe quel decalage) ou en texte. */
    static boolean porteFantome(HPacket p) {
        int taille = p.getBytesLength();
        for (int off = 6; off + 4 <= taille; off++) {
            int v = p.readInteger(off);
            if (estFantome(v) && affiches.containsKey(v)) return true;
        }
        byte[] o = p.toBytes();
        for (int i = 6; i < o.length; ) {
            if (o[i] < '0' || o[i] > '9') { i++; continue; }
            int d = i;
            while (i < o.length && o[i] >= '0' && o[i] <= '9') i++;
            if (i - d != 10) continue;
            try {
                long v = Long.parseLong(new String(o, d, i - d, StandardCharsets.ISO_8859_1));
                if (v <= Integer.MAX_VALUE && estFantome((int) v) && affiches.containsKey((int) v)) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /** Notre propre ObjectAdd / ItemAdd qui repasse : meme empreinte, ou envoye il y a moins de 5 s. */
    private static boolean nosEnvois(HMessage m, int id) {
        Long t = envoisRecents.get(id);
        if (t != null && System.currentTimeMillis() - t < 5000) return true;
        try { return empreintes.contains(Arrays.hashCode(m.getPacket().toBytes())); } catch (Throwable e) { return false; }
    }

    private static final Map<Integer, Long> envoisRecents = new ConcurrentHashMap<>();

    private static int entier(String s) {
        try { return Integer.decode(s.trim()); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }
}

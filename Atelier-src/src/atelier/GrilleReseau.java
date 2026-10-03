package atelier;

import extension.GPresets;
import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Mobis FICTIFS envoyes au client seulement (la grille dans le jeu).
 *
 * Le serveur ne les connait pas : ce sont des ObjectAdd construits ici, avec
 * des identifiants de la plage GrilleCalcul (tout en haut des entiers). On les
 * retire par ObjectRemove, comme Calques.
 *
 * ObjectAdd : HFloorItem (int id, int type, int x, int y, int dir, String z,
 * String hauteur, int extra, stuffdata, int expiration, int usage, int idProprio)
 * puis String nomProprio — exactement ce qu'ecrit HFloorItem.appendToPacket
 * (javap) ; stuffdata « legacy » = int 0 puis String etat.
 *
 * Securite :
 *   - un changement de salle, un rechargement (FloorHeightMap, Objects,
 *     RoomReady) ou la sortie de salle vident le client : on oublie tout ;
 *   - ce que le client envoie au serveur au sujet d'un mobi fictif (clic,
 *     utiliser, deplacer, ramasser) est BLOQUE : le serveur ne recoit jamais
 *     d'identifiant inconnu. « Ramasser » le retire seulement chez toi ;
 *   - si le serveur annonce un vrai mobi avec un de nos identifiants (ne doit
 *     pas arriver), on le lui laisse et on l'oublie.
 */
final class GrilleReseau {

    private GrilleReseau() { }

    // ---------------------------------------------------------- marqueurs

    /** Un modele de marqueur : une ou plusieurs classes (couleurs), ou des etats. */
    static final class Modele {
        final String nom, aide;
        final String[] classes;
        final String[] etats;
        /** Resolus dans la furnidata : typeId par variante. */
        int[] types = new int[0];
        String[] etatsVariantes = new String[0];

        Modele(String nom, String aide, String[] classes, String[] etats) {
            this.nom = nom; this.aide = aide; this.classes = classes; this.etats = etats;
        }
        int variantes() { return types.length; }
        boolean disponible() { return types.length > 0; }
        @Override public String toString() { return nom; }
    }

    private static String[] serie(String base, int n) {
        String[] s = new String[n];
        for (int i = 0; i < n; i++) s[i] = base + "*" + (i + 1);
        return s;
    }

    /** Candidats, du plus discret au plus voyant. Tous existent sur habbo.fr (catalogue). */
    static final List<Modele> MODELES = List.of(
            new Modele("Dalle BC (couleurs)", "Dalle plate 1×1, une couleur par variante : idéale pour les hauteurs.",
                    serie("bc_tile", 14), new String[]{"0"}),
            new Modele("Paillasson uni", "Petit tapis plat 1×1, plusieurs couleurs.",
                    concat(new String[]{"doormat_plain"}, serie("doormat_plain", 6)), new String[]{"0"}),
            new Modele("Dalle magique 1×1", "La dalle de pile magique : une seule couleur.",
                    new String[]{"tile_stackmagic1", "tile_stackmagic"}, new String[]{"0"}),
            new Modele("Carrelage", "Dalle de sol 1×1 : une seule couleur.",
                    new String[]{"floortile"}, new String[]{"0"}),
            new Modele("Dalle Banzaï", "Dalle de Banzaï : couleur par état (non vérifié en jeu).",
                    new String[]{"bb_patch1"}, new String[]{"0", "1", "4", "7", "10", "13", "2", "5", "8", "11", "14"}));

    private static String[] concat(String[] a, String[] b) {
        String[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * Cherche les classes dans la furnidata (1×1 seulement). Une seule classe
     * trouvee + plusieurs etats : les variantes sont les etats.
     */
    static boolean resoudre() {
        if (!Salle.furnidataPrete()) return false;
        furnidata.FurniDataTools fd;
        try { fd = Salle.gp().getFurniDataTools(); } catch (Throwable t) { return false; }
        for (Modele m : MODELES) {
            List<Integer> t = new ArrayList<>();
            for (String c : m.classes) {
                try {
                    Integer id = fd.getFloorTypeId(c);
                    if (id == null) continue;
                    furnidata.details.FloorItemDetails d = fd.getFloorItemDetails(c);
                    if (d != null && (d.xDim > 1 || d.yDim > 1)) continue;
                    if (!t.contains(id)) t.add(id);
                } catch (Throwable ignored) { }
            }
            if (t.size() == 1 && m.etats.length > 1) {
                m.types = new int[m.etats.length];
                Arrays.fill(m.types, t.get(0));
                m.etatsVariantes = m.etats.clone();
            } else {
                m.types = t.stream().mapToInt(Integer::intValue).toArray();
                m.etatsVariantes = new String[m.types.length];
                Arrays.fill(m.etatsVariantes, m.etats[0]);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- etat

    /** Ce qui est affiche chez le client : id -> marqueur. */
    static final Map<Integer, GrilleCalcul.Marqueur> affiches = new ConcurrentHashMap<>();
    static volatile int salle = -1;
    /** Vrai si G-Presets a vu nos ObjectAdd (ils apparaitraient dans « la salle » des autres outils). */
    static volatile boolean vusParGPresets = false;
    static volatile String evenement = null;

    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    static void ecouter(Runnable r) { ecouteurs.add(r); installer(); }
    private static void prevenir() { for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { } }

    static int nombre(int calque) {
        int n = 0;
        for (int id : affiches.keySet()) if (GrilleCalcul.calqueDe(id) == calque) n++;
        return n;
    }

    /** Le client a tout jete (nouvelle salle, rechargement, sortie) : on oublie. */
    static void oublier(String pourquoi) {
        if (affiches.isEmpty()) return;
        affiches.clear();
        salle = -1;
        evenement = pourquoi;
        prevenir();
    }

    static int salleCourante() {
        try { game.FloorState s = Salle.etat(); return s == null ? -1 : s.getRoomId(); } catch (Throwable t) { return -1; }
    }

    /** Un vrai mobi de la salle porte-t-il un identifiant de notre plage ? */
    static boolean collision() {
        for (HFloorItem it : Salle.sols())
            if (GrilleCalcul.estFictif(it.getId()) && !affiches.containsKey(it.getId())) return true;
        return false;
    }

    // ------------------------------------------------------------- paquets

    static HPacket ajout(GrilleCalcul.Marqueur m, Modele mod) {
        int v = mod.variantes() == 0 ? 0 : Math.floorMod(m.variante, mod.variantes());
        HPacket p = new HPacket("ObjectAdd", HMessage.Direction.TOCLIENT);
        ecrireAjout(p, m, mod.types[v], mod.etatsVariantes[v]);
        return p;
    }

    /** Corps d'ObjectAdd (separe pour le test). */
    static void ecrireAjout(HPacket p, GrilleCalcul.Marqueur m, int type, String etat) {
        p.appendInt(m.id);
        p.appendInt(type);
        p.appendInt(m.x);
        p.appendInt(m.y);
        p.appendInt(0);                              // direction
        p.appendString(GrilleCalcul.altitude(m.z));
        p.appendString("0.0");                       // hauteur propre
        p.appendInt(0);                              // extra
        p.appendInt(0);                              // stuffdata legacy
        p.appendString(etat == null ? "0" : etat);
        p.appendInt(-1);                             // pas d'expiration
        p.appendInt(0);                              // usage
        p.appendInt(0);                              // id du proprietaire
        p.appendString("Grille (Atelier)");          // nom du proprietaire
    }

    /** ObjectRemove(String id, boolean expire, int idRamasseur, int delai), comme Calques. */
    static HPacket retrait(int id) {
        HPacket p = new HPacket("ObjectRemove", HMessage.Direction.TOCLIENT);
        p.appendString(String.valueOf(id));
        p.appendBoolean(false);
        p.appendInt(0);
        p.appendInt(0);
        return p;
    }

    // --------------------------------------------------------------- envoi

    /** Paquets par paquet de 50, 150 ms entre deux paquets. */
    static final int PAR_LOT = 50;
    static final long PAUSE = 150;

    /**
     * Applique une difference (hors fil FX). progres recoit le nombre d'envois
     * faits. Renvoie le nombre d'envois reussis ; s'arrete si arret[0].
     */
    static int appliquer(GrilleCalcul.Diff d, Modele mod, boolean[] arret, IntConsumer progres) {
        GPresets gp = Salle.gp();
        if (gp == null) return 0;
        int salleIci = salleCourante();
        if (salleIci == -1) return 0;
        if (salle != salleIci) { affiches.clear(); salle = salleIci; }
        int n = 0, ok = 0;
        for (int id : d.retirer) {
            if (arret[0] || salleCourante() != salleIci) break;
            try { if (gp.sendToClient(retrait(id))) ok++; } catch (Throwable ignored) { }
            affiches.remove(id);
            if (++n % PAR_LOT == 0) { progres.accept(n); Salle.sommeil(PAUSE); }
        }
        for (GrilleCalcul.Marqueur m : d.ajouter) {
            if (arret[0] || salleCourante() != salleIci) break;
            boolean envoye = false;
            try { envoye = gp.sendToClient(ajout(m, mod)); } catch (Throwable ignored) { }
            if (envoye) { ok++; affiches.put(m.id, m); }
            if (++n % PAR_LOT == 0) { progres.accept(n); Salle.sommeil(PAUSE); }
        }
        progres.accept(n);
        // G-Presets voit-il nos envois ? Alors ils sont dans Salle.sols() pour les autres outils.
        if (!d.ajouter.isEmpty()) {
            Salle.sommeil(300);
            try { if (Salle.sol(d.ajouter.get(0).id) != null) vusParGPresets = true; } catch (Throwable ignored) { }
        }
        prevenir();
        return ok;
    }

    // ------------------------------------------------------------- ecoutes

    private static volatile boolean branche = false, enCours = false;

    static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Salle.tache("grille-ecoute", () -> {
            for (int i = 0; i < 900 && !branche; i++) {
                GPresets gp = Salle.gp();
                if (gp != null) { brancher(gp); branche = true; return; }
                Salle.sommeil(1000);
            }
        });
    }

    private static void brancher(GPresets gp) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;
        for (String nom : new String[]{"RoomReady", "Objects", "FloorHeightMap"}) {
            try { gp.intercept(C, nom, m -> { if (!affiches.isEmpty()) oublier("La salle a été rechargée : la grille a disparu."); }); }
            catch (Throwable t) { System.err.println("[Atelier] grille : ecoute " + nom + " indisponible : " + t); }
        }
        try {
            gp.intercept(C, "ObjectAdd", m -> {
                if (affiches.isEmpty()) return;
                try {
                    int id = m.getPacket().readInteger(6);
                    if (GrilleCalcul.estFictif(id) && affiches.remove(id) != null) {
                        // Un vrai mobi du serveur avec notre identifiant : il passe, on oublie le notre.
                        evenement = "Un vrai mobi utilise un identifiant de la grille : il est laissé tel quel.";
                        prevenir();
                    }
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
        // Actions du client sur un mobi fictif : bloquees. id en premier entier.
        for (String nom : new String[]{"ClickFurni", "UseFurniture", "MoveObject"}) {
            try {
                gp.intercept(S, nom, m -> {
                    if (affiches.isEmpty() || m.getPacket().getBytesLength() < 10) return;
                    try { if (GrilleCalcul.estFictif(m.getPacket().readInteger(6))) m.setBlocked(true); } catch (Throwable ignored) { }
                });
            } catch (Throwable t) { System.err.println("[Atelier] grille : ecoute " + nom + " indisponible : " + t); }
        }
        // PickupObject(int categorie, int id) : bloque, et le marqueur disparait chez toi seulement.
        try {
            gp.intercept(S, "PickupObject", m -> {
                if (affiches.isEmpty() || m.getPacket().getBytesLength() < 14) return;
                try {
                    int id = m.getPacket().readInteger(10);
                    if (!GrilleCalcul.estFictif(id)) return;
                    m.setBlocked(true);
                    Salle.tache("grille-ramasser", () -> {
                        try { gp.sendToClient(retrait(id)); } catch (Throwable ignored) { }
                        affiches.remove(id);
                        prevenir();
                    });
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
        System.out.println("[Atelier] grille dans le jeu : ecoutes actives.");
    }
}

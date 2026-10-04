package atelier;

import extension.GPresets;
import extension.tools.GPresetImporter;
import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Hauteur fixe « Sans dalles » : tant que le mode est actif, chaque mobi de
 * sol que TU poses est remis a la hauteur choisie juste apres sa pose, par
 * @altitude (OutilMiroir.Altitude.ecrire). Rien n'est pose par l'Atelier.
 *
 * Reperage d'une pose a toi :
 *   1. le client envoie PlaceObject (« -id x y rot ») ou
 *      BuildersClubPlaceRoomItem (int, int, String, x, y, rot) : on note la case.
 *      Les envois de l'Atelier (outils et moteur de pose) ne passent pas par les intercepteurs ;
 *   2. le serveur repond ObjectAdd (id, type, x, y, ...) : s'il tombe sur une
 *      case notee il y a moins de 6 s, c'est ton mobi. Les mobis des autres et
 *      les deplacements (ObjectUpdate) ne passent donc pas.
 * Sont aussi laisses : les dalles magiques, et tout ce qui arrive pendant un
 * collage / import par le moteur de pose, une annulation (Historique) ou une pose de dalles.
 *
 * Les intercepteurs ne font que lire quelques entiers et poser un evenement
 * dans une file ; un fil dedie ecrit l'altitude des reception, sans pause,
 * verifie 0,7 s apres et renvoie une fois si besoin (HauteurLogique.Ecritures).
 */
final class HauteurSansDalles {

    private HauteurSansDalles() { }

    private static volatile boolean actif = false, branche = false, enCours = false;
    private static volatile DoubleSupplier hauteur = () -> 1.0;
    private static volatile Consumer<String> sortie = s -> { };
    private static volatile Runnable majUi = () -> { };

    /** Evenement : 0 pose demandee (x, y), 1 mobi apparu (id, type, x, y), 2 appliquer aux deja poses, 3 remise a zero. */
    private static final class Ev {
        final int k, a, b, c, d; final long t = System.currentTimeMillis();
        Ev(int k, int a, int b, int c, int d) { this.k = k; this.a = a; this.b = b; this.c = c; this.d = d; }
    }
    private static final LinkedBlockingQueue<Ev> file = new LinkedBlockingQueue<>();
    private static Thread fil;

    // etat du fil dedie (lu ailleurs pour l'affichage seulement)
    private static final Set<Integer> traites = Collections.synchronizedSet(new LinkedHashSet<>());
    private static volatile int ignores = 0, rates = 0;

    static void configurer(DoubleSupplier h, Consumer<String> dire, Runnable maj) {
        hauteur = h; sortie = dire; majUi = maj;
    }

    static boolean actif() { return actif; }
    static int traites() { return traites.size(); }

    static String etat() {
        if (!actif) return "Arrêté : tes poses gardent leur hauteur normale.";
        int n = traites.size();
        return "Actif : tes poses vont à " + OutilHauteur.texte(hauteur.getAsDouble()) + ". "
                + (n == 0 ? "Pose un mobi." : n + " mobi(s) mis à la hauteur.")
                + (rates > 0 ? " " + rates + " raté(s)." : "");
    }

    static void activer() {
        if (actif) return;
        traites.clear(); ignores = 0; rates = 0;
        file.offer(new Ev(3, 0, 0, 0, 0));
        actif = true;
        OutilMiroir.Altitude.installer();
        brancher();
        demarrerFil();
        dire(OutilMiroir.Altitude.connue() ? "Mode actif : pose tes mobis, ils montent à la hauteur choisie."
                : "Mode actif. @altitude sera cherchée au premier mobi posé.");
        majUi.run();
    }

    static void arreter() {
        if (!actif) return;
        actif = false;
        OutilHauteur.bilan("Mode arrêté : " + traites.size() + " mobi(s) mis à la hauteur pendant ce mode.");
        majUi.run();
    }

    /** Remet a la hauteur actuelle tous les mobis traites pendant ce mode. */
    static void appliquerDejaPoses() {
        demarrerFil();
        file.offer(new Ev(2, 0, 0, 0, 0));
    }

    private static void dire(String s) {
        try { sortie.accept(s); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------- ecoute

    private static synchronized void brancher() {
        if (branche || enCours) return;
        enCours = true;
        Salle.tache("hauteur-sans-dalles-ecoute", () -> {
            for (int i = 0; i < 900 && !branche; i++) {
                GPresets gp = Salle.gp();
                if (gp != null) {
                    try {
                        HMessage.Direction S = HMessage.Direction.TOSERVER, C = HMessage.Direction.TOCLIENT;
                        gp.intercept(S, "PlaceObject", HauteurSansDalles::surPlaceObject);
                        gp.intercept(S, "BuildersClubPlaceRoomItem", HauteurSansDalles::surPoseBC);
                        gp.intercept(C, "ObjectAdd", HauteurSansDalles::surObjectAdd);
                        branche = true;
                        Journal.debug("hauteur sans dalles : écoute des poses active.");
                        return;
                    } catch (Throwable t) {
                        Journal.debug("hauteur sans dalles : écoute indisponible : " + t);
                    }
                }
                Salle.sommeil(1000);
            }
            enCours = false;
        });
    }

    /** PlaceObject(String "[-]id x y rot") ; le mural (« :w= ») est ignore. */
    private static void surPlaceObject(HMessage m) {
        if (!actif) return;
        try {
            int n = m.getPacket().getBytesLength();
            if (n < 14 || n > 64) return;
            HPacket p = new HPacket(m.getPacket());
            p.resetReadIndex();
            int[] c = HauteurLogique.casePlaceObject(p.readString());
            if (c != null) file.offer(new Ev(0, c[0], c[1], 0, 0));
        } catch (Throwable ignored) { }
    }

    /** BuildersClubPlaceRoomItem(int, int offre, String, int x, int y, int rot). */
    private static void surPoseBC(HMessage m) {
        if (!actif) return;
        try {
            int n = m.getPacket().getBytesLength();
            if (n < 28 || n > 120) return;
            HPacket p = new HPacket(m.getPacket());
            p.resetReadIndex();
            p.readInteger(); p.readInteger(); p.readString();
            int x = p.readInteger(), y = p.readInteger();
            if (x >= 0 && y >= 0 && x < 256 && y < 256) file.offer(new Ev(0, x, y, 0, 0));
        } catch (Throwable ignored) { }
    }

    /** ObjectAdd : id, type, x, y en tete (lus sur place, sans copie). */
    private static void surObjectAdd(HMessage m) {
        if (!actif) return;
        try {
            HPacket p = m.getPacket();
            if (p.getBytesLength() < 30) return;
            file.offer(new Ev(1, p.readInteger(6), p.readInteger(10), p.readInteger(14), p.readInteger(18)));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------- fil dedie

    private static synchronized void demarrerFil() {
        if (fil != null && fil.isAlive()) return;
        fil = new Thread(HauteurSansDalles::travailler, "atelier-hauteur-sans-dalles");
        fil.setDaemon(true);
        fil.start();
    }

    private static final HauteurLogique.Ecritures ecritures = new HauteurLogique.Ecritures(
            new HauteurLogique.Ecritures.Monde() {
                @Override public void ecrire(int id, double z) {
                    // Une ecriture isolee part tout de suite ; en rafale (« Appliquer aux mobis
                    // deja poses », renvois de verifier()), au moins 150 ms entre deux envois.
                    long ecart = System.currentTimeMillis() - dernierEcrit;
                    if (ecart < PAUSE_RAFALE) Salle.sommeil(PAUSE_RAFALE - ecart);
                    dernierEcrit = System.currentTimeMillis();
                    OutilMiroir.Altitude.ecrire(id, z);
                }
                @Override public Double z(int id) {
                    HFloorItem it = Salle.sol(id);
                    return it == null || it.getTile() == null ? null : it.getTile().getZ();
                }
            }, HauteurLogique.Ecritures.VERIF);

    static final long PAUSE_RAFALE = 150;
    private static volatile long dernierEcrit = 0;

    private static void travailler() {
        HauteurLogique.Poses poses = new HauteurLogique.Poses();
        boolean chercheFaite = false, consigneDite = false;
        Set<Integer> types = null;
        while (true) {
            try {
                long prochaine = ecritures.prochaine();
                long attente = prochaine < 0 ? 2000 : Math.max(1, prochaine - System.currentTimeMillis());
                Ev e = file.poll(attente, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (e != null) switch (e.k) {
                    case 3: poses = new HauteurLogique.Poses(); chercheFaite = false; consigneDite = false; types = null; break;
                    case 0: if (actif) poses.demandee(e.a, e.b, e.t); break;
                    case 1: {
                        if (!actif || !poses.apparue(e.c, e.d, now)) break;
                        if (types == null || types.isEmpty()) types = Generateur.Dalle.typesDalles();
                        String refus = HauteurLogique.refus(
                                HauteurLogique.estDalle(e.b, types, t -> Salle.classe(t, false)),
                                importEnCours(), Historique.occupe(), OutilHauteur.occupe(),
                                false);   // une repose (meme id) est retraitee
                        if (refus != null) {
                            ignores++;
                            Journal.debug("hauteur sans dalles : mobi " + e.a + " laissé (" + refus + ").");
                            break;
                        }
                        double z = hauteur.getAsDouble();
                        if (!OutilMiroir.Altitude.connue() && !chercheFaite) {
                            chercheFaite = true;
                            dire("Recherche de @altitude sur ton mobi…");
                            for (int i = 0; i < 10 && Salle.sol(e.a) == null; i++) Salle.sommeil(100);
                            OutilMiroir.Altitude.chercher(e.a, z);
                        }
                        if (!OutilMiroir.Altitude.connue()) {
                            // une seule fois par activation du mode (pas a chaque pose)
                            if (!consigneDite) {
                                consigneDite = true;
                                Journal.erreur("Hauteur impossible : règle @altitude une fois dans l'éditeur :wired, puis repose le mobi.");
                            }
                            break;
                        }
                        ecritures.ecrire(e.a, z, System.currentTimeMillis());
                        traites.add(e.a);
                        majUi.run();
                        break;
                    }
                    case 2: {
                        if (!OutilMiroir.Altitude.connue()) {
                            OutilHauteur.echec("Hauteur impossible : règle @altitude une fois dans l'éditeur :wired.");
                            break;
                        }
                        double z = hauteur.getAsDouble();
                        int n = 0;
                        List<Integer> ids;
                        synchronized (traites) { ids = new ArrayList<>(traites); }
                        for (int id : ids)
                            if (Salle.sol(id) != null) { ecritures.ecrire(id, z, System.currentTimeMillis()); n++; }
                        OutilHauteur.bilan(n == 0 ? "Aucun mobi posé pendant ce mode n'est encore dans l'appart."
                                : n + " mobi(s) remis à " + OutilHauteur.texte(z) + ".");
                        break;
                    }
                    default: break;
                }
                List<Integer> perdus = ecritures.verifier(System.currentTimeMillis());
                if (!perdus.isEmpty()) {
                    rates += perdus.size();
                    OutilHauteur.echec("Échec pour " + perdus.size() + " mobi(s) : pas à la hauteur après deux essais (droits sur l'appart ?).");
                    majUi.run();
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                System.err.println("[Atelier] hauteur sans dalles : " + t);
            }
        }
    }

    private static boolean importEnCours() {
        try {
            GPresets gp = Salle.gp();
            GPresetImporter imp = gp == null ? null : gp.getImporter();
            return imp != null && imp.getState() != GPresetImporter.BuildingImportState.NONE;
        } catch (Throwable t) { return false; }
    }
}

package atelier;

import gearth.extensions.parsers.HEntity;
import gearth.extensions.parsers.HEntityType;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les limites d'un appart, et ou on en est :
 *   mobis           4000 par appart, pour tout le monde (Habbo, aout 2023 :
 *                   2500 -> 4000 ; HC ou pas ne change rien depuis) ;
 *   animaux / bots  plafonds du serveur, que Habbo ne publie pas : 50 retenu
 *                   (a ajuster ici si le jeu refuse plus tot). Les monster
 *                   plants sont des animaux pour le jeu.
 * Les animaux et bots sont comptes d'apres les paquets Users / UserRemove
 * (entites de la salle), remis a zero a chaque salle.
 */
final class LimitesSalle {

    private LimitesSalle() { }

    static final int MAX_MOBIS = 4000, MAX_ANIMAUX = 50, MAX_BOTS = 50;

    /** index d'entite -> type (animal, bot) ; les avatars ne sont pas gardes. */
    private static final Map<Integer, HEntityType> entites = new ConcurrentHashMap<>();
    private static volatile int salle = -1;
    private static volatile boolean branche = false;

    /** Chaque ecoute est marquee seulement une fois posee : un echec est retente au tour suivant. */
    private static volatile boolean brancheUsers = false, brancheRemove = false, brancheReady = false;

    private static synchronized void brancher() {
        if (branche) return;
        Moteur gp = Salle.gp();
        if (gp == null) return;
        try {
            // Users porte toutes les entites (avatars, figures...) : il est lu sur
            // le fil SUIVI, pas dans l'intercepteur. Les trois ecoutes y passent
            // pour garder l'ordre (une entite ajoutee puis retiree).
            if (!brancheUsers) {
                gp.intercept(HMessage.Direction.TOCLIENT, "Users", m -> {
                    try {
                        HPacket copie = new HPacket(m.getPacket());
                        SUIVI.execute(() -> lireUsers(copie));
                    } catch (Throwable ignored) { }
                });
                brancheUsers = true;
            }
            if (!brancheRemove) {
                gp.intercept(HMessage.Direction.TOCLIENT, "UserRemove", m -> {
                    try {
                        String index = m.getPacket().readString(6);      // lecture sur place, sans copie
                        SUIVI.execute(() -> {
                            if (entites.isEmpty()) return;
                            try { entites.remove(Integer.parseInt(index.trim())); } catch (Throwable ignored) { }
                        });
                    } catch (Throwable ignored) { }
                });
                brancheRemove = true;
            }
            if (!brancheReady) {
                gp.intercept(HMessage.Direction.TOCLIENT, "RoomReady", m -> SUIVI.execute(() -> { entites.clear(); salle = -1; }));
                brancheReady = true;
            }
            branche = true;
        } catch (Throwable t) {
            System.err.println("[Atelier] limites de l'appart : " + t);
        }
    }

    /** Un seul fil, demon : la lecture des entites, dans l'ordre des paquets. */
    private static final java.util.concurrent.ExecutorService SUIVI =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-limites-entites");
                t.setDaemon(true);
                return t;
            });

    private static void lireUsers(HPacket p) {
        try {
            verifierSalle();
            // Format actuel du client (int badgesRank en plus) : HEntity.parse de gearth s'y perd.
            PlanteSuivi.Entites lu = PlanteSuivi.lireEntites(p);
            for (java.util.Map.Entry<Integer, Integer> e : lu.autres.entrySet())
                entites.put(e.getKey(), e.getValue() == 2 ? HEntityType.PET : e.getValue() == 3 ? HEntityType.OLD_BOT : HEntityType.BOT);
        } catch (Throwable ignored) { }
    }

    private static void verifierSalle() {
        int s = Salle.salleId();
        if (s != salle) { entites.clear(); salle = s; }
    }

    /** Le bloc « Limites de l'appart », tenu a jour tout seul. Fil JavaFX. */
    static VBox bloc() {
        Label mobis = Ui.valeur("—"), animaux = Ui.valeur("—"), bots = Ui.valeur("—");
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(2000);
                try {
                    brancher();
                    if (!Salle.dansUneSalle()) {
                        Platform.runLater(() -> { mobis.setText("Hors appart"); animaux.setText("—"); bots.setText("—"); });
                        continue;
                    }
                    verifierSalle();
                    int nm = Salle.sols().size() + Salle.murs().size();
                    int na = 0, nb = 0;
                    for (HEntityType ty : entites.values()) { if (ty == HEntityType.PET) na++; else nb++; }
                    boolean connu = branche;   // sans ecoute des entites, 0 serait faux
                    String sm = ligne("Mobis", nm, MAX_MOBIS),
                           sa = connu ? ligne("Animaux et monster plants", na, MAX_ANIMAUX) : "Animaux et monster plants : inconnu",
                           sb = connu ? ligne("Bots", nb, MAX_BOTS) : "Bots : inconnu";
                    Platform.runLater(() -> { mobis.setText(sm); animaux.setText(sa); bots.setText(sb); });
                } catch (Throwable ignored) { }
            }
        }, "atelier-limites-salle");
        t.setDaemon(true);
        t.start();
        return Ui.bloc("Limites de l'appart", mobis, animaux, bots,
                Ui.aide("Mobis : 4000 par appart pour tout le monde, HC ou pas (Habbo, août 2023). "
                        + "Animaux et bots : Habbo ne publie pas ses plafonds, 50 est indicatif. "
                        + "Animaux et bots sont comptés depuis ton entrée dans l'appart."));
    }

    private static String ligne(String nom, int n, int max) {
        int reste = Math.max(0, max - n);
        return nom + " : " + n + " / " + max + (n >= max ? " — limite atteinte" : " (encore " + reste + ")");
    }
}

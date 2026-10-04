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
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        try {
            if (!brancheUsers) {
                gp.intercept(HMessage.Direction.TOCLIENT, "Users", m -> {
                    try {
                        verifierSalle();
                        for (HEntity e : HEntity.parse(new HPacket(m.getPacket()))) {
                            if (e == null) continue;
                            HEntityType t = e.getEntityType();
                            if (t == HEntityType.PET || t == HEntityType.BOT || t == HEntityType.OLD_BOT) entites.put(e.getIndex(), t);
                        }
                    } catch (Throwable ignored) { }
                });
                brancheUsers = true;
            }
            if (!brancheRemove) {
                gp.intercept(HMessage.Direction.TOCLIENT, "UserRemove", m -> {
                    if (entites.isEmpty()) return;          // test avant toute copie
                    try { entites.remove(Integer.parseInt(new HPacket(m.getPacket()).readString().trim())); }
                    catch (Throwable ignored) { }
                });
                brancheRemove = true;
            }
            if (!brancheReady) {
                gp.intercept(HMessage.Direction.TOCLIENT, "RoomReady", m -> { entites.clear(); salle = -1; });
                brancheReady = true;
            }
            branche = true;
        } catch (Throwable t) {
            System.err.println("[Atelier] limites de l'appart : " + t);
        }
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

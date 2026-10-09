package atelier;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inventaire pour le generateur d'apparts : quand l'inventaire est charge (ou
 * change), on ecrit Documents/Atelier/generation/inventaire.json
 * {"sol": {classe: nombre}, "mur": {classe: nombre}}. Le generateur s'en sert
 * pour ne choisir que des mobis qu'on a (ou qui sont au BC).
 */
final class InventaireExport {

    private InventaireExport() { }

    private static volatile boolean lance = false;
    /** Builders Club : limite de mobis et mobis BC deja poses (-1 = pas encore recu). */
    private static volatile int bcLimite = -1, bcUtilises = -1;

    private static void ecouterBc(Moteur gp) {
        try {
            // BuildersClubSubscriptionStatus : int secondes, int limite, int limite max[, int secondes avec grace]
            gp.intercept(gearth.protocol.HMessage.Direction.TOCLIENT, "BuildersClubSubscriptionStatus", m -> {
                try { gearth.protocol.HPacket p = m.getPacket(); p.readInteger(); bcLimite = p.readInteger(); }
                catch (Throwable ignored) { }
            });
            // BuildersClubFurniCount : int nombre de mobis BC poses
            gp.intercept(gearth.protocol.HMessage.Direction.TOCLIENT, "BuildersClubFurniCount", m -> {
                try { bcUtilises = m.getPacket().readInteger(); } catch (Throwable ignored) { }
            });
        } catch (Throwable t) {
            Journal.debug("Builders Club : écoute impossible (" + t + ")");
        }
    }

    static synchronized void demarrer() {
        if (lance) return;
        lance = true;
        Moteur m0 = Salle.gp();
        if (m0 != null) ecouterBc(m0);
        Salle.tache("inventaire-export", () -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            // Paresseux : rien n'est parcouru tant que l'inventaire (sa version) et
            // les valeurs BC n'ont pas change ; jamais pendant un chargement.
            String vu = null;
            while (true) {
                Salle.sommeil(10_000);
                try {
                    Moteur gp = Salle.gp();
                    Inventaire inv = gp == null ? null : gp.getInventory();
                    if (inv == null || inv.getState() != Inventaire.Etat.LOADED || gp.inventaireEnChargement()
                            || !Salle.furnidataPrete()) continue;
                    File d = new File(new File(Dossiers.maison(), "Documents"), "Atelier" + File.separator + "generation");
                    if (!d.isDirectory()) continue;                 // generateur pas installe : rien a ecrire
                    String sig = inv.version() + ":" + bcLimite + ":" + bcUtilises + ":" + System.identityHashCode(gp.getFurniDataTools());
                    if (sig.equals(vu)) continue;
                    // Les comptes par type (quelques milliers de types), pas les 25 000 mobis.
                    Map<String, Integer> sol = new TreeMap<>(), mur = new TreeMap<>();
                    inv.compteSols().forEach((type, n) -> { String c = Salle.classe(type, false); if (c != null) sol.merge(c, n, Integer::sum); });
                    inv.compteMurs().forEach((type, n) -> { String c = Salle.classe(type, true); if (c != null) mur.merge(c, n, Integer::sum); });
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("sol", new org.json.JSONObject(sol));
                    o.put("mur", new org.json.JSONObject(mur));
                    if (bcLimite >= 0) {
                        org.json.JSONObject bc = new org.json.JSONObject();
                        bc.put("limite", bcLimite);
                        bc.put("utilises", bcUtilises);
                        o.put("bc", bc);
                    }
                    File f = new File(d, "inventaire.json");
                    Files.writeString(f.toPath(), o.toString(1), StandardCharsets.UTF_8);
                    Capture.rendre(f);
                    vu = sig;
                } catch (Throwable t) {
                    Journal.debug("Inventaire pour le générateur : " + t);
                }
            }
        });
    }
}

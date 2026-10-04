package atelier;

import javafx.scene.image.Image;

import java.util.*;

/**
 * Vignette representant une famille de mobis.
 *
 * On prend le premier mobi de la famille present dans la furnidata et on
 * recupere son icone officielle :
 *     https://images.habbo.com/dcr/hof_furni/{revision}/{classname}_icon.png
 *
 * Tout est en tache de fond et sans blocage : une icone absente laisse
 * simplement la ligne sans image, l'interface reste utilisable.
 */
public final class Vignettes {

    private static final Map<String, Image> cache = new HashMap<>();
    private static final Set<String> enCours = new HashSet<>();

    private Vignettes() { }

    /**
     * @param apres appele quand l'icone vient d'arriver, pour redessiner.
     * @return l'icone si elle est deja la, sinon null.
     */
    public static synchronized Image pour(String famille, Runnable apres) {
        Image i = cache.get(famille);
        if (i != null) return i;
        if (enCours.contains(famille)) return null;
        // Une famille sans icone n'est pas retentee a chaque dessin de la liste :
        // c'etait des telechargements relances en boucle.
        Long echec = echecs.get(famille);
        if (echec != null && System.currentTimeMillis() - echec < REESSAI) return null;
        enCours.add(famille);

        file.submit(() -> {
            Image chargee = charger(famille);
            synchronized (Vignettes.class) {
                if (chargee != null) cache.put(famille, chargee);
                else if (pret()) echecs.put(famille, System.currentTimeMillis());
                enCours.remove(famille);
            }
            if (chargee != null && apres != null) apres.run();
        });
        return null;
    }

    /** Un echec est retente apres ce delai (nouveaux mobis dans l'inventaire). */
    private static final long REESSAI = 5 * 60_000;
    private static final Map<String, Long> echecs = new HashMap<>();
    /** Un telechargement a la fois, plutot qu'un fil par famille. */
    private static final java.util.concurrent.ExecutorService file =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-vignette");
                t.setDaemon(true);
                return t;
            });

    /** Furnidata et inventaire prets : sinon un echec ne veut rien dire. */
    private static boolean pret() {
        Moteur gp = AtelierLauncher.moteur();
        try {
            return gp != null && gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady()
                    && !gp.getInventory().getInventoryItems().isEmpty();
        } catch (Throwable t) { return false; }
    }

    private static Image charger(String famille) {
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return null;
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd == null || !fd.isReady()) return null;

            // Un mobi de l'inventaire appartenant a cette famille : sa vignette
            // parle mieux qu'un mobi choisi au hasard dans la furnidata.
            // Trois essais au plus : si trois mobis n'ont pas d'icone, les
            // suivants n'en auront probablement pas non plus.
            java.util.Set<String> essayes = new java.util.HashSet<>();
            for (gearth.extensions.parsers.HInventoryItem it
                    : gp.getInventory().getInventoryItems()) {
                String cls = fd.getFloorItemName(it.getTypeId());
                Furnidata.Mobi d =
                        (cls == null) ? null : fd.getFloorItemDetails(cls);
                if (d == null) continue;
                if (!famille.equals(Categories.famille(d.furniline))) continue;
                if (!essayes.add(cls)) continue;
                Image img = depuis(cls, d.revision);
                if (img != null) return img;
                if (essayes.size() >= 3) return null;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Image depuis(String classe, int revision) {
        if (classe == null || revision <= 0) return null;
        // Les variantes « classe*3 » n'ont pas d'icone propre.
        String c = classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe;
        String url = "https://images.habbo.com/dcr/hof_furni/" + revision + "/" + c + "_icon.png";
        try {
            Image img = new Image(url, 26, 26, true, true, false);
            return img.isError() ? null : img;
        } catch (Throwable t) {
            return null;
        }
    }
}

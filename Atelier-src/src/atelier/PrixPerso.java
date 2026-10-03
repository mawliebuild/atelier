package atelier;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les prix que tu as fixes toi-meme dans « Valeur de mes mobis ».
 *
 * Ils passent avant habbofurni et le marche du jeu, et sont gardes dans
 * repertoire/prix-perso.json. Cle : « 1:typeId » (sol) ou « 2:typeId » (mur).
 */
public final class PrixPerso {

    private static final File FICHIER = new File("repertoire", "prix-perso.json");
    private static final Map<String, Integer> prix = new ConcurrentHashMap<>();
    private static volatile boolean charge = false;

    private PrixPerso() { }

    /** Le prix fixe a la main, ou null. */
    public static Integer prix(boolean mur, int typeId) {
        charger();
        return prix.get(Marche.cle(mur, typeId));
    }

    /** valeur < 0 : on revient au prix automatique. */
    public static void fixer(boolean mur, int typeId, int valeur) {
        charger();
        if (valeur < 0) prix.remove(Marche.cle(mur, typeId));
        else prix.put(Marche.cle(mur, typeId), valeur);
        sauver();
    }

    private static synchronized void charger() {
        if (charge) return;
        charge = true;
        if (!FICHIER.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(FICHIER.toPath()), StandardCharsets.UTF_8));
            for (String k : o.keySet()) prix.put(k, o.getInt(k));
        } catch (Throwable t) {
            System.err.println("[Atelier] prix perso : fichier illisible : " + t);
        }
    }

    private static synchronized void sauver() {
        try {
            File d = FICHIER.getParentFile();
            if (!d.exists()) d.mkdirs();
            File tmp = new File(d, "prix-perso.json.tmp");
            Files.write(tmp.toPath(), new JSONObject(new TreeMap<>(prix)).toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FICHIER.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Atelier] prix perso : sauvegarde impossible : " + t);
        }
    }
}

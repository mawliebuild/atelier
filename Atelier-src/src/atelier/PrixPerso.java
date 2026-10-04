package atelier;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les prix que tu as fixes toi-meme dans « Valeur de mes mobis ».
 *
 * Ils passent avant habbofurni et le marche du jeu, et sont gardes dans
 * prix-perso.json (dossier de l'Atelier, voir PrixFichier ; repris de
 * l'ancien repertoire/ au premier lancement). Cle : « 1:typeId » (sol) ou
 * « 2:typeId » (mur).
 */
public final class PrixPerso {

    private static final String NOM = "prix-perso.json";
    private static final java.util.List<Runnable> ecouteurs = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { }
    }

    /** Appele (sur le fil de l'appelant) quand un prix perso change. */
    static void surMaj(Runnable r) { if (r != null) ecouteurs.add(r); }

    private static synchronized void charger() {
        if (charge) return;
        charge = true;
        JSONObject o = PrixFichier.lire(NOM, NOM);
        if (o == null) return;
        try {
            for (String k : o.keySet()) prix.put(k, o.getInt(k));
        } catch (Throwable t) {
            System.err.println("[Atelier] prix perso : fichier illisible : " + t);
        }
    }

    private static synchronized void sauver() {
        if (!PrixFichier.ecrire(NOM, new JSONObject(new TreeMap<>(prix))))
            Journal.erreur("Prix pas enregistré : il sera perdu au prochain lancement.");
    }
}

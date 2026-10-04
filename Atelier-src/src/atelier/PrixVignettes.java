package atelier;

import javafx.scene.image.Image;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Icone d'un mobi pour le tableau Patrimoine :
 *     https://images.habbo.com/dcr/hof_furni/{revision}/{classe}_icon.png
 *
 * Seules les lignes visibles demandent leur icone ; JavaFX la charge en
 * tache de fond (rien ne bloque le fil FX). On garde les 600 dernieres en
 * memoire. Une icone absente laisse simplement la case vide.
 * (Vignettes, lui, donne l'icone d'une FAMILLE de mobis.)
 */
final class PrixVignettes {

    private PrixVignettes() { }

    static final double TAILLE = 22;

    private static final Map<String, Image> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) { return size() > 600; }
    };

    /** L'icone (peut-etre encore en chargement), ou null si on ne sait pas la trouver. Fil FX. */
    static Image icone(String classe, int revision) {
        if (classe == null || classe.isBlank() || revision <= 0) return null;
        // Les variantes « classe*3 » n'ont pas d'icone propre.
        String c = classe.contains("*") ? classe.substring(0, classe.indexOf('*')) : classe;
        String url = "https://images.habbo.com/dcr/hof_furni/" + revision + "/" + c + "_icon.png";
        Image i = cache.get(url);
        if (i == null) {
            try { i = new Image(url, TAILLE, TAILLE, true, true, true); }
            catch (Throwable t) { return null; }
            cache.put(url, i);
        }
        return i.isError() ? null : i;
    }
}

package atelier;

import extension.GPresets;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Petits outils des prix : nombres et dates a la francaise, et ce que la
 * furnidata dit d'un mobi (classe, nom, revision de l'icone).
 */
final class PrixTexte {

    private PrixTexte() { }

    private static final ThreadLocal<NumberFormat> NOMBRE =
            ThreadLocal.withInitial(() -> NumberFormat.getIntegerInstance(Locale.FRANCE));
    private static final ThreadLocal<NumberFormat> LINGOTS = ThreadLocal.withInitial(() -> {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMaximumFractionDigits(1);
        f.setMinimumFractionDigits(0);
        return f;
    });
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE);
    private static final DateTimeFormatter DATE_HEURE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRANCE);

    /**
     * « 12 345 ». Java ecrit une espace fine insecable (U+202F) que la police
     * des tableaux n'a pas toujours (le nombre paraissait colle : « 299997 ») :
     * espace insecable ordinaire a la place.
     */
    static String nombre(long n) { return NOMBRE.get().format(n).replace('\u202F', '\u00A0'); }

    /** 50 credits = 1 lingot : « 246,9 ». */
    static String lingots(long credits) { return LINGOTS.get().format(credits / 50.0).replace('\u202F', '\u00A0'); }

    /** « 12 345 crédits · 246,9 lingots ». */
    static String credits(long c) { return nombre(c) + " crédits · " + lingots(c) + " lingots"; }

    /** « 4 oct. ». */
    static String date(long t) { return DATE.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())); }

    /** « 4 oct. 14:32 ». */
    static String dateHeure(long t) { return DATE_HEURE.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())); }

    // ---------------------------------------------------------------- furnidata

    private static furnidata.FurniDataTools fd(GPresets gp) {
        try {
            furnidata.FurniDataTools fd = gp == null ? null : gp.getFurniDataTools();
            return fd != null && fd.isReady() ? fd : null;
        } catch (Throwable t) { return null; }
    }

    /** Le nom de classe (« rare_dragonlamp*1 »), ou null. */
    static String classe(GPresets gp, boolean mur, int typeId) {
        furnidata.FurniDataTools fd = fd(gp);
        if (fd == null) return null;
        try { return mur ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId); }
        catch (Throwable t) { return null; }
    }

    /** Le nom affiche dans le jeu, sinon la classe, sinon « Type 123 ». */
    static String nom(GPresets gp, boolean mur, int typeId, String classe) {
        furnidata.FurniDataTools fd = fd(gp);
        if (fd != null && classe != null) {
            try {
                String n = mur ? (fd.getWallItemDetails(classe) == null ? null : fd.getWallItemDetails(classe).name)
                               : (fd.getFloorItemDetails(classe) == null ? null : fd.getFloorItemDetails(classe).name);
                return (n == null || n.isBlank()) ? classe : n.trim();
            } catch (Throwable ignored) { }
        }
        return classe != null ? classe : "Type " + typeId;
    }

    /** Revision de l'icone (images.habbo.com), 0 si inconnue. */
    static int revision(GPresets gp, boolean mur, String classe) {
        furnidata.FurniDataTools fd = fd(gp);
        if (fd == null || classe == null) return 0;
        try {
            return mur ? (fd.getWallItemDetails(classe) == null ? 0 : fd.getWallItemDetails(classe).revision)
                       : (fd.getFloorItemDetails(classe) == null ? 0 : fd.getFloorItemDetails(classe).revision);
        } catch (Throwable t) { return 0; }
    }
}

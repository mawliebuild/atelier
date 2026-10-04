package atelier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Les messages de l'Atelier, au meme endroit.
 *
 *   - succes / erreur : le RESULTAT d'une action. Dit dans le jeu (InfoJeu,
 *     chuchote a soi seulement) et ecrit dans la console.
 *   - info : ce qui aide a comprendre apres coup (console seulement).
 *
 * Plus d'encadre jaune dans les fenetres : la ligne d'etat (Ui.etat) ne montre
 * plus que la progression et les consignes, en texte discret ; les resultats
 * partent ici. Pas de spam : un meme texte n'est ecrit qu'une fois de suite
 * dans la console, et InfoJeu espace / dedoublonne les messages du jeu.
 */
public final class Journal {

    private Journal() { }

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static String dernier = null;
    private static long dernierA = 0;

    /** Echec franc : le message le dit deja (« échec », « impossible »…) ou non. */
    static final Pattern ERREUR = Pattern.compile(
            "(échec|échou|erreur|impossible|refusé|introuvable|interromp|n'a pas pu|pas pu être|n'a pas reçu|bloqué|⚠)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Resultat reussi. */
    static final Pattern SUCCES = Pattern.compile(
            "(terminé|copié|réussi|traité|posé|appliqu|enregistr|mis à jour|rétabli|annulé|ramassé|supprimé|créé|fusionn|réglé|masqué|réaffiché|collé|exporté|importé|(?<!pas encore )(?<!pas )chargé|vidé|remis)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Progression ou attente : « 3 / 10 », « … », « en cours ». */
    static final Pattern PROGRESSION = Pattern.compile(
            "(…\\s*$|\\.\\.\\.\\s*$|en cours|recherche de|attente|patiente)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    enum Genre { SUCCES, ERREUR, PROGRESSION, INFO }

    /** Logique pure : de quel genre est ce texte d'etat ? */
    static Genre genre(String t) {
        if (t == null || t.isBlank()) return Genre.INFO;
        if (PROGRESSION.matcher(t).find() || compteEnCours(t)) return Genre.PROGRESSION;
        if (ERREUR.matcher(t).find()) return Genre.ERREUR;
        if (SUCCES.matcher(t).find()) return Genre.SUCCES;
        return Genre.INFO;
    }

    private static final Pattern COMPTE = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    /** « 3 / 10 » : en cours ; « 10 / 10 » ou « 3/5 posés » dans un bilan termine ne l'est pas. */
    private static boolean compteEnCours(String t) {
        java.util.regex.Matcher m = COMPTE.matcher(t);
        while (m.find()) {
            try {
                if (Long.parseLong(m.group(1)) < Long.parseLong(m.group(2))
                        && !SUCCES.matcher(t).find() && !ERREUR.matcher(t).find()) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    public static void succes(String m) {
        if (vide(m)) return;
        console("OK", m, false);
        InfoJeu.dire(m);
    }

    public static void erreur(String m) {
        if (vide(m)) return;
        console("ERREUR", m, true);
        InfoJeu.dire(ERREUR.matcher(m).find() ? m : "Erreur : " + m);
    }

    public static void erreur(String m, Throwable t) {
        erreur(m + (t == null ? "" : " (" + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : " : " + t.getMessage()) + ")"));
        if (t != null) t.printStackTrace();
    }

    /** Console seulement. */
    public static void info(String m) {
        if (vide(m)) return;
        console("info", m, false);
    }

    /**
     * Diagnostic detaille (paquets, compteurs, essais) : rien dans le terminal,
     * sauf si l'Atelier est lance avec -Datelier.debug=true.
     */
    public static final boolean DEBUG = Boolean.getBoolean("atelier.debug");

    public static void debug(String m) {
        if (DEBUG && !vide(m)) System.out.println("[Atelier] debug : " + m);
    }

    /** Ecrit au journal ce qu'InfoJeu dit dans le jeu (sans le redire). */
    static void jeu(String m) { console("jeu", m, false); }

    private static boolean vide(String m) { return m == null || m.isBlank(); }

    private static synchronized void console(String niveau, String m, boolean err) {
        String t = Ui.majuscule(Ui.accorder(m.trim()));
        long now = System.currentTimeMillis();
        if (t.equals(dernier) && now - dernierA < 5000) return;   // pas deux fois de suite
        dernier = t;
        dernierA = now;
        String ligne = "[Atelier] " + LocalTime.now().format(HEURE) + " " + niveau + " : " + t;
        if (err) System.err.println(ligne); else System.out.println(ligne);
    }
}

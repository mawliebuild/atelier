package atelier;

import atelier.EcartsDefaut.Ecart;
import atelier.EcartsDefaut.Origine;
import atelier.EcartsDefaut.Valeur;

import java.util.*;

/**
 * Ecarts d'alignement par mobi mural : ceux enregistres par l'utilisatrice
 * (preferences, cle « aligner.<type>.droite / .haut / .nom / .classe »), plus
 * ceux livres avec le programme (EcartsDefaut). Prevenir les volets quand ca
 * change, pour que « En ligne », « En grille » et la liste restent d'accord.
 */
public final class Ecarts {

    private Ecarts() { }

    private static final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userRoot().node("atelier");

    private static final List<Runnable> ecouteurs = new ArrayList<>();

    public static void ecouter(Runnable r) { synchronized (ecouteurs) { ecouteurs.add(r); } }

    private static void prevenir() {
        List<Runnable> l;
        synchronized (ecouteurs) { l = new ArrayList<>(ecouteurs); }
        for (Runnable r : l) try { r.run(); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------- un mobi

    /** Ce qu'on sait d'un mobi pour l'aligner. */
    public static final class Mobi {
        public final int type;
        public final String classe, nom;
        public final Valeur droite, haut;
        public final boolean aUnDefaut;     // un defaut livre existe (pour « Revenir au défaut »)
        public final int defautDroite, defautHaut;   // ce que donnerait « Revenir au défaut »
        Mobi(int type, String classe, String nom, Valeur d, Valeur h, boolean def, int dd, int dh) {
            this.type = type; this.classe = classe; this.nom = nom; droite = d; haut = h;
            aUnDefaut = def; defautDroite = dd; defautHaut = dh;
        }
        public Valeur axe(boolean axeDroite) { return axeDroite ? droite : haut; }
        public boolean enregistre() { return droite.origine == Origine.ENREGISTRE || haut.origine == Origine.ENREGISTRE; }
    }

    /**
     * Les ecarts d'un mobi mural, avec leur origine. {@code position} : celle du
     * mobi choisi, pour mesurer sur ses copies deja posees (peut etre null).
     */
    public static Mobi pour(int type, String position, String nomSecours) {
        String classe = Salle.classe(type, true);
        if (classe == null) classe = prefs.get(cle(type, "classe"), null);
        String nom = nomLisible(type, nomSecours);
        Ecart def = EcartsDefaut.chercher(EcartsDefaut.livres(), classe, type);
        int[] mes = {-1, -1};
        if (position != null && (def == null || def.droite <= 0 || def.haut <= 0)) {
            List<String> autres = new ArrayList<>();
            try {
                for (gearth.extensions.parsers.HWallItem w : Salle.murs())
                    if (w.getTypeId() == type) autres.add(SelectionMur.normaliser(w.getLocation()));
            } catch (Throwable ignored) { }
            mes = EcartsDefaut.mesurer(position, autres);
        }
        int dDef = def == null ? -1 : def.droite, hDef = def == null ? -1 : def.haut;
        Valeur d = EcartsDefaut.resoudre(prefs.getInt(cle(type, "droite"), -1), dDef, mes[0], EcartsDefaut.BASE_DROITE);
        Valeur h = EcartsDefaut.resoudre(prefs.getInt(cle(type, "haut"), -1), hDef, mes[1], EcartsDefaut.BASE_HAUT);
        Valeur dSans = EcartsDefaut.resoudre(-1, dDef, mes[0], EcartsDefaut.BASE_DROITE);
        Valeur hSans = EcartsDefaut.resoudre(-1, hDef, mes[1], EcartsDefaut.BASE_HAUT);
        return new Mobi(type, classe, nom, d, h, def != null, dSans.valeur, hSans.valeur);
    }

    /** Nom affiche : furnidata, sinon celui retenu a l'enregistrement, sinon « Type 123 ». */
    public static String nomLisible(int type, String secours) {
        String n = Salle.furnidataPrete() ? Salle.nom(type, true) : null;
        if (n == null || n.isBlank() || n.startsWith("type ")) n = prefs.get(cle(type, "nom"), null);
        if ((n == null || n.isBlank() || "?".equals(n)) && secours != null && !"?".equals(secours)) n = secours;
        if (n == null || n.isBlank()) n = "Type " + type;
        return Ui.majuscule(n);
    }

    // ------------------------------------------------------- ecriture

    /** Enregistre un axe (ou les deux : passer -1 pour ne pas toucher a un axe). */
    public static void enregistrer(int type, int droite, int haut) {
        if (type < 0) return;
        if (droite > 0) prefs.putInt(cle(type, "droite"), droite);
        if (haut > 0) prefs.putInt(cle(type, "haut"), haut);
        String classe = Salle.classe(type, true);
        if (classe != null) prefs.put(cle(type, "classe"), classe);
        String nom = Salle.furnidataPrete() ? Salle.nom(type, true) : null;
        if (nom != null && !nom.startsWith("type ")) prefs.put(cle(type, "nom"), nom);
        prevenir();
    }

    /** Oublie l'ecart enregistre : le defaut livre (ou la mesure) reprend la main. */
    public static void oublier(int type) { oublier(type, true, true); }

    /** Oublie un axe, ou les deux. Sans plus rien d'enregistre, le nom retenu part aussi. */
    public static void oublier(int type, boolean droite, boolean haut) {
        prefs.remove(cle(type, "ligne"));            // ancienne cle, plus utilisee
        if (droite) prefs.remove(cle(type, "droite"));
        if (haut) prefs.remove(cle(type, "haut"));
        if (prefs.getInt(cle(type, "droite"), -1) <= 0 && prefs.getInt(cle(type, "haut"), -1) <= 0) {
            prefs.remove(cle(type, "nom")); prefs.remove(cle(type, "classe"));
        }
        prevenir();
    }

    // ------------------------------------------------------- la liste

    /** Les ecarts enregistres par l'utilisatrice. */
    public static List<Ecart> enregistres() {
        Map<Integer, Ecart> r = new TreeMap<>();
        try {
            for (String k : prefs.keys()) {
                if (!k.startsWith("aligner.")) continue;
                String[] p = k.split("\\.");
                if (p.length != 3 || !(p[2].equals("droite") || p[2].equals("haut"))) continue;
                int t;
                try { t = Integer.parseInt(p[1]); } catch (NumberFormatException e) { continue; }
                if (r.containsKey(t)) continue;
                String classe = Salle.classe(t, true);
                if (classe == null) classe = prefs.get(cle(t, "classe"), null);
                r.put(t, new Ecart(classe, t, nomLisible(t, null),
                        prefs.getInt(cle(t, "droite"), -1), prefs.getInt(cle(t, "haut"), -1)));
            }
        } catch (Throwable t) {
            System.out.println("[Atelier] Écarts : préférences illisibles : " + t);
        }
        List<Ecart> l = new ArrayList<>(r.values());
        l.removeIf(Ecart::vide);
        return l;
    }

    /** Defauts livres + enregistres, fusionnes ; les noms completes par la furnidata si possible. */
    public static List<EcartsDefaut.Ligne> liste() {
        List<Ecart> def = new ArrayList<>();
        for (Ecart e : EcartsDefaut.livres()) {
            Ecart c = e.copie();
            if (c.type < 0 && c.classe != null) c.type = typeDe(c.classe);
            def.add(c);
        }
        List<EcartsDefaut.Ligne> l = EcartsDefaut.fusionner(def, enregistres());
        for (EcartsDefaut.Ligne x : l) {
            Ecart e = x.effectif;
            if (e.type >= 0 && Salle.furnidataPrete()) {
                String n = Salle.nom(e.type, true);
                if (n != null && !n.startsWith("type ")) e.nom = n;
            }
            if (e.nom == null) e.nom = e.classe != null ? e.classe : "Type " + e.type;
            e.nom = Ui.majuscule(e.nom);
        }
        return l;
    }

    /** Le type d'une classe dans cet hotel, -1 si inconnu. */
    static int typeDe(String classe) {
        if (classe == null || !Salle.furnidataPrete()) return -1;
        try {
            Integer t = AtelierLauncher.gpresets().getFurniDataTools().getWallTypeId(classe);
            return t == null ? -1 : t;
        } catch (Throwable t) { return -1; }
    }

    private static String cle(int type, String k) { return "aligner." + type + "." + k; }
}

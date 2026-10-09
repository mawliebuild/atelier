package atelier;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rangement des copies d'apparts en dossiers (de vrais dossiers sur le disque).
 *
 * Dans le dossier des copies (OngletApparts.dossierApparts()), deux dossiers
 * fixes : « Apparts » (copies d'un appart entier) et « Zones ». Ils ne se
 * renomment, ne se suppriment ni ne se deplacent ; dedans, des dossiers et
 * sous-dossiers a volonte. Une copie ne change jamais d'arbre : une zone reste
 * sous « Zones », un appart sous « Apparts ».
 *
 * Une copie est designee par son chemin depuis le dossier des copies, sans
 * « .json » et avec des « / » (« Apparts/Noel/Loft ») : new File(dossier, nom
 * + ".json") la retrouve, sur Mac comme sous Windows. Son apercu « nom.png »
 * et ses sauvegardes « nom.json.… » la suivent partout.
 *
 * Les fichiers internes de l'Atelier (« _atelier… ») ne sont jamais des copies :
 * jamais listes, jamais deplaces.
 */
final class CopiesDossiers {

    private CopiesDossiers() { }

    static final String APPARTS = "Apparts", ZONES = "Zones";
    /** Garde-fou contre un lien qui boucle. */
    private static final int PROFONDEUR = 20;

    private static volatile boolean range = false;

    // ------------------------------------------------------------ chemins

    static File racine() { return OngletApparts.dossierApparts(); }

    static File apparts() { return new File(racine(), APPARTS); }

    static File zones() { return new File(racine(), ZONES); }

    /** Les deux dossiers fixes (et la racine) : ni renommes, ni supprimes, ni deplaces. */
    static boolean fixe(File d) {
        File r = racine();
        return d.equals(r) || d.equals(new File(r, APPARTS)) || d.equals(new File(r, ZONES));
    }

    static boolean interne(String nomFichier) { return nomFichier.startsWith("_atelier"); }

    static boolean estCopie(File f) {
        return f.isFile() && f.getName().endsWith(".json") && !interne(f.getName()) && !f.getName().startsWith(".");
    }

    /** « Apparts/Noel/Loft » pour …/presets/Apparts/Noel/Loft.json (copie) ou …/Apparts/Noel (dossier). */
    static String relatif(File f) {
        String rel = racine().toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
        return rel.endsWith(".json") ? rel.substring(0, rel.length() - 5) : rel;
    }

    /** Le fichier d'une copie, d'apres son chemin relatif. */
    static File fichier(String rel) { return new File(racine(), rel + ".json"); }

    /** Le dossier d'apres son chemin relatif (vide : la racine). */
    static File dossier(String rel) { return rel == null || rel.isEmpty() ? racine() : new File(racine(), rel); }

    /** Le nom seul d'une copie (« Loft » pour « Apparts/Noel/Loft »). */
    static String court(String rel) {
        if (rel == null) return null;
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    /** d est-il la racine des copies ou dedans (chemins canoniques) ? */
    static boolean dans(File d, File racine) {
        try {
            String r = racine.getCanonicalPath(), c = d.getCanonicalPath();
            return c.equals(r) || c.startsWith(r + File.separator);
        } catch (Exception e) { return false; }
    }

    /** L'arbre (« Apparts » ou « Zones ») ou se trouve d ; null pour la racine ou ailleurs. */
    static File arbre(File d) {
        File r = racine();
        File a = new File(r, APPARTS), z = new File(r, ZONES);
        for (File x = d; x != null; x = x.getParentFile()) {
            if (x.equals(a)) return a;
            if (x.equals(z)) return z;
            if (x.equals(r)) return null;
        }
        return null;
    }

    /** La copie est-elle une zone ? D'abord son arbre ; hors arbre, son contenu (pas de floor : zone). */
    static boolean estZone(File json) {
        File a = arbre(json.getParentFile());
        if (a != null) return a.getName().equals(ZONES);
        return zoneParContenu(json);
    }

    private static boolean zoneParContenu(File json) {
        try {
            return !new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8)).has("atelierFloor");
        } catch (Throwable t) {
            return false;       // illisible : rangee avec les apparts, rien n'est perdu
        }
    }

    /** L'arbre ou doit vivre cette copie. */
    static File arbreAttendu(File json) { return estZone(json) ? zones() : apparts(); }

    /** La copie peut-elle aller dans dir (meme arbre, autre dossier) ? */
    static boolean accepte(File dir, File json) {
        if (dir == null || json == null || !dir.isDirectory() || !json.isFile()) return false;
        if (dir.equals(json.getParentFile())) return false;
        File a = arbre(dir);
        return a != null && a.equals(arbreAttendu(json));
    }

    /** Ou enregistrer une nouvelle copie : le dossier ouvert s'il est du bon arbre, sinon la tete de l'arbre. */
    static File dossierPour(boolean zone, File ouvert) {
        File tete = zone ? zones() : apparts();
        if (ouvert != null && ouvert.isDirectory() && tete.equals(arbre(ouvert))) return ouvert;
        return tete;
    }

    /** « Apparts › Noel » (la racine : « Mes copies »). */
    static String lisible(File d) {
        File r = racine();
        if (d.equals(r)) return "Mes copies";
        return relatif(d).replace("/", " › ");
    }

    // ------------------------------------------------------------ listes

    /** Les sous-dossiers visibles de d, par ordre alphabetique. */
    static List<File> sousDossiers(File d) {
        File[] l = d.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<File> r = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        r.sort((a, b) -> COLLATOR.compare(a.getName(), b.getName()));
        return r;
    }

    /** Les copies de d (pas des sous-dossiers), par ordre alphabetique. */
    static List<File> copiesDe(File d) {
        File[] l = d.listFiles(CopiesDossiers::estCopie);
        List<File> r = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        r.sort((a, b) -> COLLATOR.compare(a.getName(), b.getName()));
        return r;
    }

    /** Toutes les copies (chemins relatifs) : la racine, puis Apparts, puis Zones, dossier par dossier. */
    static List<String> toutes() {
        List<String> r = new ArrayList<>();
        File rac = racine();
        for (File f : copiesDe(rac)) r.add(relatif(f));
        for (File d : sousDossiers(rac)) parcourir(d, r, 0);
        return r;
    }

    private static void parcourir(File d, List<String> r, int prof) {
        for (File f : copiesDe(d)) r.add(relatif(f));
        if (prof < PROFONDEUR) for (File s : sousDossiers(d)) parcourir(s, r, prof + 1);
    }

    /** Nombre de copies sous d (sous-dossiers compris). */
    static int compter(File d, int prof) {
        int n = copiesDe(d).size();
        if (prof < PROFONDEUR) for (File s : sousDossiers(d)) n += compter(s, prof + 1);
        return n;
    }

    /** Copies, autres fichiers (hors fichiers caches du systeme) et sous-dossiers sous d. */
    static void inventaire(File d, int[] c, int prof) {
        File[] l = d.listFiles();
        if (l == null) return;
        for (File f : l) {
            String n = f.getName();
            if (f.isDirectory()) { c[2]++; if (prof < PROFONDEUR) inventaire(f, c, prof + 1); else c[1]++; }
            else if (estCopie(f)) c[0]++;
            else if (!n.equals(".DS_Store") && !n.startsWith("._") && !n.equalsIgnoreCase("desktop.ini")
                    && !n.equalsIgnoreCase("Thumbs.db")) c[1]++;
        }
    }

    /** Efface d, deja verifie sans copie ni autre fichier : fichiers caches du systeme, sous-dossiers vides, puis lui. */
    static boolean effacer(File d, int prof) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) {
            if (f.isDirectory()) { if (prof >= PROFONDEUR || !effacer(f, prof + 1)) return false; }
            else if (estCopie(f)) return false;      // ceinture et bretelles
            else if (!f.delete()) return false;
        }
        return d.delete();
    }

    private static final java.text.Collator COLLATOR;
    static {
        COLLATOR = java.text.Collator.getInstance(Locale.FRENCH);
        COLLATOR.setStrength(java.text.Collator.SECONDARY);
    }

    // ------------------------------------------------------------ deplacer

    /** Un nom libre dans dir pour la copie « base » : ni .json ni .png deja pris (« base (2) »...). */
    static String libre(File dir, String base) {
        String n = base;
        for (int k = 2; new File(dir, n + ".json").exists() || new File(dir, n + ".png").exists(); k++) n = base + " (" + k + ")";
        return n;
    }

    /**
     * Deplace la copie dans dir, avec son apercu et ses sauvegardes (« nom.json.… »).
     * Un nom deja pris dans dir recoit (2), (3)... Rend le nouveau fichier.
     */
    static File deplacer(File json, File dir) throws java.io.IOException {
        String base = json.getName().substring(0, json.getName().length() - 5);
        String n = libre(dir, base);
        File dest = new File(dir, n + ".json");
        Files.move(json.toPath(), dest.toPath());
        Capture.rendre(dest);
        File png = ApercuPreset.de(json);
        if (png.isFile()) suivre(png, ApercuPreset.de(dest));
        File[] autres = json.getParentFile() == null ? null
                : json.getParentFile().listFiles(f -> f.isFile() && f.getName().startsWith(base + ".json."));
        if (autres != null) for (File f : autres)
            suivre(f, new File(dir, n + ".json" + f.getName().substring(base.length() + 5)));
        return dest;
    }

    private static void suivre(File de, File vers) {
        if (vers.exists()) { Journal.debug("copies : " + vers + " existe déjà, " + de + " reste en place."); return; }
        try {
            Files.move(de.toPath(), vers.toPath());
            Capture.rendre(vers);
        } catch (Throwable t) {
            Journal.debug("copies : " + de + " non déplacé (" + t + ").");
        }
    }

    // ------------------------------------------------------------ migration

    /** Un fichier de ce nom est-il deja range sous « Apparts » ou « Zones » de ce dossier des copies ? */
    static boolean dejaRange(File dossierCopies, String nomFichier) {
        for (String a : new String[]{APPARTS, ZONES}) {
            File t = new File(dossierCopies, a);
            if (t.isDirectory() && chercher(t, nomFichier, 0)) return true;
        }
        return false;
    }

    private static boolean chercher(File d, String nom, int prof) {
        if (new File(d, nom).exists()) return true;
        if (prof < PROFONDEUR) for (File s : sousDossiers(d)) if (chercher(s, nom, prof + 1)) return true;
        return false;
    }

    /**
     * Cree les deux dossiers fixes au besoin, et une fois par lancement range
     * les copies laissees a la racine : appart entier (avec son floor) dans
     * « Apparts », zone dans « Zones », avec leurs apercus. Rien n'est ecrase.
     * Les fichiers « _atelier… » restent ou ils sont.
     */
    static synchronized void preparer(File d) {
        for (String a : new String[]{APPARTS, ZONES}) {
            File t = new File(d, a);
            if (!t.isDirectory() && t.mkdirs()) Capture.rendre(t);
        }
        if (range) return;
        range = true;
        File ap = new File(d, APPARTS), zo = new File(d, ZONES);
        File[] l = d.listFiles(CopiesDossiers::estCopie);
        if (l == null || l.length == 0) return;
        int nA = 0, nZ = 0;
        for (File f : l) {
            boolean zone = zoneParContenu(f);
            try {
                File dest = deplacer(f, zone ? zo : ap);
                if (zone) nZ++; else nA++;
                if (!dest.getName().equals(f.getName()))
                    Journal.info("Copie « " + f.getName() + " » rangée sous le nom « " + dest.getName() + " » (nom déjà pris).");
            } catch (Throwable t) {
                Journal.debug("copies : " + f + " pas rangée (" + t + "), elle reste à la racine.");
            }
        }
        if (nA + nZ > 0)
            Journal.info(Ui.accorder("Copies rangées : " + nA + " appart(s) dans « Apparts », " + nZ + " zone(s) dans « Zones »."));
    }
}

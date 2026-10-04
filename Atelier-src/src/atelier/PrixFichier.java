package atelier;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Les fichiers des prix (et du patrimoine), au meme endroit.
 *
 * Dossier : celui de l'Atelier pour l'utilisatrice reelle (voir Dossiers :
 * macOS ~/Library/Application Support/Atelier, Windows %APPDATA%\Atelier).
 * Avant, ces fichiers etaient dans « repertoire/ » a cote de l'appli (chemin
 * relatif au dossier de lancement) : s'il n'y a rien au nouvel endroit, on
 * relit l'ancien fichier une fois, et la prochaine ecriture va au nouvel
 * endroit.
 *
 * Ecriture atomique : un fichier temporaire unique dans le meme dossier, puis
 * un deplacement par-dessus l'ancien. Un Atelier ferme en pleine ecriture
 * laisse l'ancien fichier intact.
 */
final class PrixFichier {

    private PrixFichier() { }

    /** Dossier impose (tests) ; null = Dossiers.donneesAtelier(). */
    static volatile File dossierImpose = null;

    static File dossier() {
        File d = dossierImpose;
        return d != null ? d : Dossiers.donneesAtelier();
    }

    static File fichier(String nom) { return new File(dossier(), nom); }

    /** L'ancien emplacement (« repertoire/ » relatif), ou null en test. */
    static File ancien(String nom) { return dossierImpose != null ? null : new File("repertoire", nom); }

    /** Le contenu du fichier « nom » (ou de l'ancien « ancienNom »), ou null. */
    static JSONObject lire(String nom, String ancienNom) {
        JSONObject o = lire(fichier(nom));
        if (o != null || ancienNom == null) return o;
        File a = ancien(ancienNom);
        if (a == null) return null;
        o = lire(a);
        if (o != null) Journal.debug("prix : " + ancienNom + " repris de l'ancien dossier.");
        return o;
    }

    /** null si absent ou illisible (dit en debug). */
    static JSONObject lire(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Journal.debug("fichier illisible, il sera refait : " + f + " (" + t + ")");
            return null;
        }
    }

    static boolean ecrire(String nom, JSONObject o) { return ecrire(fichier(nom), o); }

    /** Ecriture atomique ; false (et trace en debug) si impossible. */
    static boolean ecrire(File f, JSONObject o) {
        File tmp = null;
        try {
            File d = f.getAbsoluteFile().getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            tmp = File.createTempFile(f.getName() + ".", ".tmp", d);
            Files.write(tmp.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            Journal.debug("ecriture impossible : " + f + " (" + t + ")");
            if (tmp != null) tmp.delete();
            return false;
        }
    }

    /** Un prix lu a « date » est-il trop vieux ? (0 = jamais lu : perime). */
    static boolean perime(long date, long validite, long maintenant) {
        return date <= 0 || maintenant - date >= validite || date > maintenant + 3600_000L;
    }
}

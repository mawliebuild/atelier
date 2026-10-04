package atelier;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

/**
 * Disque des calques (aucun JavaFX, aucun reseau) : un fichier par appart,
 * dans le dossier de donnees INTERNE de l'Atelier, jamais relatif au dossier
 * de lancement :
 *
 *   macOS    ~/Library/Application Support/Atelier/calques/<idAppart>.json
 *   Windows  %APPDATA%/Atelier/calques/<idAppart>.json
 *   autres   ~/.atelier/calques/<idAppart>.json
 *
 * « ~ » est le dossier de l'utilisatrice REELLE : sous sudo, user.home vaut
 * celui de root, SUDO_USER donne le vrai nom (meme regle que PrixHabbofurni).
 * Les fichiers crees en root sont rendus a l'utilisatrice (au mieux).
 *
 * Avant : repertoire/calques/ RELATIF au dossier courant. Lancer l'Atelier
 * d'ailleurs faisait « perdre » tous les calques (ou echouer l'ecriture en
 * silence). Les anciens fichiers sont recopies une fois ici (migrer).
 *
 * Ecriture atomique : fichier temporaire + rename. Toute erreur remonte en
 * exception (Groupes l'affiche) : rien n'est avale.
 *
 * Tests : la propriete systeme « atelier.calques.dossier » remplace le dossier.
 */
final class GroupeStockage {

    private GroupeStockage() { }

    /** Le dossier de l'utilisatrice reelle (sous sudo : celui de SUDO_USER). */
    static String maison() {
        return Dossiers.maison().getPath();
    }

    private static String utilisatriceSudo() {
        return Dossiers.WINDOWS ? null : Dossiers.utilisatriceSudo(Dossiers.sudoUser());
    }

    /** Le dossier des calques (absolu). */
    static File dossier() {
        String force = System.getProperty("atelier.calques.dossier");
        if (force != null && !force.isBlank()) return new File(force).getAbsoluteFile();
        return new File(Dossiers.donneesAtelier(), "calques").getAbsoluteFile();
    }

    /** Les anciens emplacements (relatifs au lancement) a recopier une fois. */
    static List<File> anciensDossiers() {
        List<File> l = new ArrayList<>();
        l.add(new File("repertoire", "calques").getAbsoluteFile());
        l.add(new File(maison(), "Documents/Atelier/repertoire/calques").getAbsoluteFile());
        return l;
    }

    static File fichier(File dossier, int salle) { return new File(dossier, salle + ".json"); }

    /**
     * Les calques d'un appart. Pas de fichier : plan vide (premiere visite).
     * Fichier illisible : il est mis de cote (<id>.illisible.json) et l'erreur
     * remonte, pour que l'utilisatrice le sache.
     */
    static GroupeModele.Plan lire(File dossier, int salle) throws IOException {
        File f = fichier(dossier, salle);
        if (!f.exists()) return new GroupeModele.Plan(salle);
        String texte;
        try {
            texte = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Lecture impossible de " + f + " : " + e.getMessage(), e);
        }
        try {
            return GroupeModele.Plan.lire(texte, salle);
        } catch (RuntimeException e) {
            File cote = new File(dossier, salle + ".illisible.json");
            try { Files.move(f.toPath(), cote.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { cote = f; }
            throw new IOException("Fichier des calques abîmé (" + e.getMessage() + "), gardé dans " + cote, e);
        }
    }

    /** Ecriture atomique : <id>.json.tmp puis rename sur <id>.json. */
    static void ecrire(File dossier, int salle, String texte) throws IOException {
        if (!dossier.isDirectory()) {
            Files.createDirectories(dossier.toPath());
            rendre(dossier.toPath());
            File parent = dossier.getParentFile();
            if (parent != null) rendre(parent.toPath());
        }
        Path tmp = new File(dossier, salle + ".json.tmp").toPath();
        Path cible = fichier(dossier, salle).toPath();
        byte[] octets = texte.getBytes(StandardCharsets.UTF_8);
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(octets);
            while (b.hasRemaining()) c.write(b);
            c.force(true);
        }
        try {
            Files.move(tmp, cible, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, cible, StandardCopyOption.REPLACE_EXISTING);
        }
        rendre(cible);
    }

    /** Sous sudo : le fichier appartient a l'utilisatrice, pas a root (au mieux). */
    private static void rendre(Path p) {
        String sudo = utilisatriceSudo();
        if (sudo == null || !"root".equals(System.getProperty("user.name"))) return;
        try {
            UserPrincipal u = p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(sudo);
            if (!u.equals(Files.getOwner(p))) Files.setOwner(p, u);
        } catch (Throwable t) {
            Journal.debug("calques : proprietaire de " + p + " non change : " + t);
        }
    }

    /**
     * Recopie les <id>.json des anciens dossiers qui n'existent pas encore
     * dans le nouveau (les anciens restent en place). @return nombre recopies
     */
    static int migrer(File dossier, List<File> anciens) throws IOException {
        int n = 0;
        for (File a : anciens) {
            if (a == null || !a.isDirectory() || a.getAbsoluteFile().equals(dossier.getAbsoluteFile())) continue;
            File[] l = a.listFiles((d, nom) -> nom.matches("-?\\d+\\.json"));
            if (l == null) continue;
            for (File f : l) {
                int salle;
                try { salle = Integer.parseInt(f.getName().substring(0, f.getName().length() - 5)); }
                catch (NumberFormatException e) { continue; }
                if (fichier(dossier, salle).exists()) continue;
                String texte = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                try { GroupeModele.Plan.lire(texte, salle); }
                catch (RuntimeException illisible) { continue; }          // on ne recopie pas un fichier abime
                ecrire(dossier, salle, texte);
                n++;
            }
        }
        return n;
    }
}

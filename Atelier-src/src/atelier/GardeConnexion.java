package atelier;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Empeche le proxy de l'Atelier de se connecter a lui-meme en boucle.
 *
 * Avant chaque connexion, le proxy demande au systeme l'adresse du serveur
 * (game-fr.habbo.com) et la retient comme « vrai serveur », puis redirige ce
 * nom vers 127.0.0.x dans /etc/hosts (lignes marquees par le proxy, voir MARQUE
 * par la bibliotheque). Si, au
 * moment de la demande, le systeme repond deja 127.0.0.x — ligne restee apres
 * un crash, ou cache DNS de macOS qui s'en souvient encore juste apres une
 * deconnexion —, chaque connexion du jeu en ouvre une autre vers le proxy
 * lui-meme, avec un thread chacune. En une dizaine de secondes, la JVM atteint
 * la limite de macOS (4096 threads par processus) : OutOfMemoryError « unable
 * to create native thread », puis connexion perdue.
 *
 * Donc, avant chaque tentative : retirer les lignes restees, vider le cache
 * DNS, et attendre que le nom ne donne plus une adresse locale.
 */
final class GardeConnexion {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    /** Le fichier hosts du systeme (Windows : %SystemRoot%\\System32\\drivers\\etc\\hosts). */
    private static final Path HOSTS = WINDOWS
            ? Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "drivers", "etc", "hosts")
            : Paths.get("/etc/hosts");
    private static final String MARQUE = "G-Earth replacement";
    /** Le nom que le proxy redirige pour l'hotel francais. */
    private static final String SERVEUR = "game-fr.habbo.com";

    private GardeConnexion() { }

    /**
     * Sans cache Java : sinon une reponse 127.0.0.x resterait 30 s en memoire,
     * meme apres le nettoyage. A appeler avant tout le reste (main).
     */
    static void sansCacheDns() {
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Throwable ignored) { }
    }

    /**
     * Hors fil JavaFX, connexion NON etablie : remet le systeme d'aplomb.
     * Renvoie un message si quelque chose a ete corrige, null sinon.
     */
    static String assainir() {
        Set<String> noms = new LinkedHashSet<>();
        noms.add(SERVEUR);
        int retirees = 0;
        try {
            if (autreInstance()) {
                Journal.debug("une autre instance tourne : " + HOSTS + " laisse tel quel.");
            } else if (Files.isWritable(HOSTS)) {
                List<String> lignes = Files.readAllLines(HOSTS);
                List<String> gardees = new ArrayList<>();
                for (String l : lignes) {
                    if (!l.contains(MARQUE)) { gardees.add(l); continue; }
                    String[] t = l.trim().split("\\s+");
                    if (t.length >= 2) noms.add(t[1]);
                }
                retirees = lignes.size() - gardees.size();
                if (retirees > 0) ecrireAtomique(gardees);
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] lecture de " + HOSTS + " impossible : " + t);
        }

        // Toujours vider le cache DNS : juste apres une deconnexion, la ligne
        // n'est plus dans le fichier mais macOS peut encore s'en souvenir.
        viderCacheDns();
        boolean local = false;
        for (int i = 0; i < 12; i++) {
            local = false;
            for (String n : noms) if (local(n)) { local = true; break; }
            if (!local) break;
            Salle.sommeil(250);
            if (i % 4 == 3) viderCacheDns();
        }

        String r = null;
        if (retirees > 0)
            r = retirees + " redirection(s) de l'Atelier restée(s) dans le fichier hosts, retirée(s).";
        if (local)
            r = (r == null ? "" : r + " ") + SERVEUR + " pointe encore vers cet ordinateur : "
                    + "la connexion risque de boucler.";
        if (r != null) Journal.info(r);
        return r;
    }

    /**
     * Reecrit /etc/hosts sans jamais le laisser vide ou tronque : fichier
     * temporaire a cote (meme disque, droits 644), puis remplacement atomique.
     */
    private static void ecrireAtomique(List<String> lignes) throws java.io.IOException {
        Path tmp = HOSTS.resolveSibling("hosts.atelier.tmp");
        try {
            Files.write(tmp, lignes);
            try {
                Files.setPosixFilePermissions(tmp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (Throwable ignored) { }
            try {
                Files.move(tmp, HOSTS, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                if (!WINDOWS) throw e;
                Files.write(HOSTS, lignes);   // Windows : hosts parfois verrouille (antivirus) contre le remplacement
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
        }
    }

    /** Une adresse locale (127.x, ::1) pour ce nom ? false si le nom ne se resout pas. */
    private static boolean local(String nom) {
        try {
            for (InetAddress a : InetAddress.getAllByName(nom))
                if (a.isLoopbackAddress() || a.isAnyLocalAddress()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static void viderCacheDns() {
        try {
            if (WINDOWS) {
                new ProcessBuilder("ipconfig", "/flushdns").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            } else {
                new ProcessBuilder("dscacheutil", "-flushcache").start().waitFor();
                new ProcessBuilder("killall", "-HUP", "mDNSResponder").start().waitFor();
            }
            Salle.sommeil(150);
        } catch (Throwable ignored) { }
    }

    /** Une autre JVM de l'Atelier tourne : ses lignes de /etc/hosts sont legitimes. */
    private static boolean autreInstance() {
        // Nos ancetres ne comptent pas : « sudo java -jar Atelier.jar » a la meme
        // ligne de commande que nous, et c'est lui qui nous a lances.
        Set<Long> ancetres = new java.util.HashSet<>();
        ProcessHandle h = ProcessHandle.current();
        while (h != null) { ancetres.add(h.pid()); h = h.parent().orElse(null); }
        return ProcessHandle.allProcesses().anyMatch(p -> !ancetres.contains(p.pid())
                && p.info().command().map(c -> c.endsWith("/java")).orElse(false)
                && p.info().commandLine().map(c -> c.contains("Atelier.jar")).orElse(false));
    }

    /** Nombre de threads de la JVM : au-dela de quelques milliers, c'est la boucle. */
    static int threads() {
        try { return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount(); }
        catch (Throwable t) { return Thread.activeCount(); }
    }
}

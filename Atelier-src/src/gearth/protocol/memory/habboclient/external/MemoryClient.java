package gearth.protocol.memory.habboclient.external;

import gearth.encoding.HexEncoding;
import gearth.misc.OSValidator;
import gearth.protocol.HConnection;
import gearth.protocol.connection.HClient;
import gearth.protocol.memory.habboclient.HabboClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Remplace la classe du meme nom de G-Earth 1.5.4 (copiee par-dessus dans le jar).
 *
 * G-Earth lit la cle RC4 de Habbo avec un petit programme externe : G-MemZ sur
 * Mac (fourni), G-MemZ.exe sur Windows (introuvable : aucune version publique).
 *   - Mac / Linux : exactement comme l'original (G-MemZ a cote du jar).
 *   - Windows : si G-MemZ.exe est la, on s'en sert ; sinon la meme recherche
 *     est faite ici, en Java (LectureMemoire : portage fidele de G-Mem, code
 *     public de G-Earth).
 */
public class MemoryClient implements HabboClient {

    private final HConnection connection;

    public MemoryClient(HConnection connection) {
        this.connection = connection;
    }

    @Override
    public List<byte[]> getRC4Tables() {
        List<byte[]> r = new ArrayList<>();
        try {
            if (OSValidator.isWindows() && !new File(dossier(), "G-MemZ.exe").isFile()) {
                for (String h : LectureMemoire.tablesWindows()) r.add(HexEncoding.toBytes(h));
            } else {
                for (String h : dumpTables()) r.add(HexEncoding.toBytes(h));
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] Lecture de la clé RC4 impossible : " + t);
        }
        Collections.reverse(r);
        return r;
    }

    private String dossier() throws Exception {
        return new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
    }

    /** Comme l'original : G-MemZ (ou G-MemZ.exe) a cote du jar, lignes hexadecimales de 512 caracteres. */
    private HashSet<String> dumpTables() throws Exception {
        String exe = dossier() + (OSValidator.isWindows() ? "\\G-MemZ.exe" : "/G-MemZ");
        String type = connection.getClientType() == HClient.SHOCKWAVE ? "shockwave" : "flash";
        Process p = new ProcessBuilder(exe, type).start();
        HashSet<String> l = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String s;
            while ((s = in.readLine()) != null) if (s.length() == 512) l.add(s);
        } finally {
            p.destroy();
        }
        return l;
    }
}

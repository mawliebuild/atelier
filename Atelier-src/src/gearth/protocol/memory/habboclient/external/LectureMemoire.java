package gearth.protocol.memory.habboclient.external;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.util.*;

/**
 * Recherche des tables RC4 dans la memoire du client Habbo, en Java : portage
 * fidele de G-Mem (github.com/sirjonasxx/G-Mem, Rust/src/main.rs).
 *
 * Une table RC4 est une permutation de 0..255 ; Flash la garde en entiers de
 * 4 octets (un octet utile, trois nuls). On parcourt chaque zone memoire de 4
 * en 4 octets : quand les 256 derniers premiers-octets sont tous differents,
 * c'est un candidat ; on en tire les tables de 256 octets dont les trois
 * autres octets de chaque entier sont nuls. Resultat : lignes hexadecimales.
 */
final class LectureMemoire {

    private LectureMemoire() { }

    private static final int PROCESS_QUERY_INFORMATION = 0x0400, PROCESS_VM_READ = 0x0010, PROCESS_VM_OPERATION = 0x0008;
    private static final int MEM_COMMIT = 0x1000, PAGE_GUARD = 0x100, PAGE_NOACCESS = 0x01;
    private static final long ZONE_MAX = 256L * 1024 * 1024;

    /** Windows : toutes les tables trouvees dans les processus Habbo.exe. */
    static Set<String> tablesWindows() {
        Set<String> r = new LinkedHashSet<>();
        for (long pid : pidsHabbo()) {
            WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ | PROCESS_VM_OPERATION, false, (int) pid);
            if (h == null) continue;
            try {
                WinBase.SYSTEM_INFO si = new WinBase.SYSTEM_INFO();
                Kernel32.INSTANCE.GetSystemInfo(si);
                long adr = Pointer.nativeValue(si.lpMinimumApplicationAddress);
                long fin = Pointer.nativeValue(si.lpMaximumApplicationAddress);
                while (adr < fin) {
                    WinNT.MEMORY_BASIC_INFORMATION mbi = new WinNT.MEMORY_BASIC_INFORMATION();
                    BaseTSD.SIZE_T n = Kernel32.INSTANCE.VirtualQueryEx(h, new Pointer(adr), mbi, new BaseTSD.SIZE_T(mbi.size()));
                    if (n == null || n.longValue() == 0) break;
                    long taille = mbi.regionSize.longValue();
                    if (taille <= 0) break;
                    int etat = mbi.state.intValue(), prot = mbi.protect.intValue();
                    if (etat == MEM_COMMIT && (prot & PAGE_GUARD) == 0 && (prot & PAGE_NOACCESS) == 0 && taille <= ZONE_MAX) {
                        byte[] zone = lire(h, adr, (int) taille);
                        if (zone != null) chercher(zone, r);
                    }
                    adr += taille;
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(h);
            }
        }
        System.out.println("[Atelier] Clé RC4 : " + r.size() + " table(s) candidate(s) trouvée(s) dans Habbo.");
        return r;
    }

    private static List<Long> pidsHabbo() {
        List<Long> l = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            String c = p.info().command().orElse("");
            String n = c.replace('\\', '/');
            n = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (n.equals("habbo.exe") || n.startsWith("habbo") && n.endsWith(".exe") && !n.contains("launcher")) l.add(p.pid());
        });
        return l;
    }

    private static byte[] lire(WinNT.HANDLE h, long adr, int taille) {
        try {
            Memory m = new Memory(taille);
            IntByReference lu = new IntByReference();
            if (!Kernel32.INSTANCE.ReadProcessMemory(h, new Pointer(adr), m, taille, lu)) return null;
            return m.getByteArray(0, lu.getValue());
        } catch (Throwable t) {
            return null;
        }
    }

    /** La recherche elle-meme (logique pure : testee sur Mac avec une zone fabriquee). */
    static void chercher(byte[] mem, Set<String> sortie) {
        int[] nVersPlace = new int[256], aRetirer = new int[256];
        Arrays.fill(nVersPlace, -1);
        Arrays.fill(aRetirer, -1);
        int compte = 0;
        long debut = -1, fin = -1;
        int n = mem.length / 4;
        for (int i = 0; i < n; i++) {
            int b = ((mem[i * 4] & 0xff) + 128) % 256;
            int place = i % 256;
            int efface = aRetirer[place];
            if (efface != -1) {
                nVersPlace[efface] = -1;
                compte--;
                aRetirer[place] = -1;
            }
            if (nVersPlace[b] == -1) {
                compte++;
                aRetirer[place] = b;
                nVersPlace[b] = place;
            } else {
                aRetirer[nVersPlace[b]] = -1;
                aRetirer[place] = b;
                nVersPlace[b] = place;
            }
            if (compte == 256) {
                long ici = (long) i * 4, depuis = ici - 255L * 4;
                if (debut == -1) { debut = depuis; fin = ici; }
                if (fin < depuis) {
                    possibilites(mem, (int) debut, (int) (fin - debut + 4), sortie);
                    debut = depuis;
                }
                fin = ici;
            }
        }
        if (debut != -1) possibilites(mem, (int) debut, (int) (fin - debut + 4), sortie);
    }

    private static void possibilites(byte[] mem, int debut, int longueur, Set<String> sortie) {
        if (longueur < 1024 || longueur > 1024 + 8) return;
        for (int i = 0; i < longueur - 255 * 4; i += 4) {
            boolean ok = true;
            StringBuilder hex = new StringBuilder(512);
            for (int j = 0; j < 1024 && ok; j++) {
                byte v = mem[debut + i + j];
                if (j % 4 != 0) { if (v != 0) ok = false; }
                else hex.append(String.format("%02x", v & 0xff));
            }
            if (ok) sortie.add(hex.toString());
        }
    }
}

package atelier;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * La capture sous Windows : ce que Capture fait sur Mac avec AppleScript
 * (System Events) et CoreGraphics, fait ici avec user32 / gdi32 par JNA.
 *
 *   - trouver la fenetre du jeu : programme Habbo.exe, visible, la plus grande ;
 *   - la mettre au premier plan et taper « :screenshot » + Entree (SendInput,
 *     caracteres Unicode : la disposition AZERTY ne compte pas), ou la coller
 *     (presse-papiers + Ctrl+V) ;
 *   - lire / regler sa position et sa taille (GetWindowRect, SetWindowPos) ;
 *   - la photographier seule, meme recouverte (PrintWindow), sinon a l'ecran
 *     (BitBlt), zone client seulement (sans barre de titre).
 *
 * Aucune autorisation a demander sous Windows. Cette classe n'est appelee que
 * sous Windows : sur Mac, rien n'y est charge.
 */
final class CaptureWindows {

    private CaptureWindows() { }

    /** Ce qui manque a la User32 de jna-platform. */
    private interface U32 extends StdCallLibrary {
        boolean ClientToScreen(WinDef.HWND h, WinDef.POINT p);
        boolean IsIconic(WinDef.HWND h);
        boolean IsZoomed(WinDef.HWND h);
        void keybd_event(byte vk, byte scan, int flags, Pointer extra);
    }

    private static U32 u32;

    private static synchronized U32 u32() {
        if (u32 == null) u32 = Native.load("user32", U32.class);
        return u32;
    }

    /** user32, charge au premier appel seulement (jamais sur Mac). */
    private static final class N { static final User32 U = User32.INSTANCE; }

    private static final int SW_RESTORE = 9;
    private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4,
            SWP_NOACTIVATE = 0x10, SWP_NOSENDCHANGING = 0x400;
    private static final int PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;
    private static final int VK_RETURN = 0x0D, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_V = 0x56;

    // ------------------------------------------------------------ dossiers

    /** Le dossier Images du compte (meme deplace dans OneDrive), ou null. */
    static String dossierImages() {
        try { return Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures); }
        catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ fenetre du jeu

    private static final Map<Integer, String> programmes = new HashMap<>();

    /** Nom du programme d'un processus (« habbo.exe »), en minuscules, ou null. */
    private static synchronized String programme(int pid) {
        if (programmes.containsKey(pid)) return programmes.get(pid);
        String n = ProcessHandle.of(pid).flatMap(p -> p.info().command())
                .map(c -> new java.io.File(c).getName()).orElse(null);
        if (n == null) {
            WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid);   // QUERY_LIMITED_INFORMATION
            if (h != null) {
                try {
                    char[] b = new char[1024];
                    IntByReference t = new IntByReference(b.length);
                    if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, b, t))
                        n = new java.io.File(new String(b, 0, t.getValue())).getName();
                } finally { Kernel32.INSTANCE.CloseHandle(h); }
            }
        }
        if (n != null) n = n.toLowerCase(java.util.Locale.ROOT);
        if (programmes.size() > 500) programmes.clear();
        programmes.put(pid, n);
        return n;
    }

    /** Le programme est-il le jeu ? « habbo.exe » ; le Launcher a un autre nom. */
    static boolean estJeu(String programme, String titre) {
        if (programme != null) return programme.equals("habbo.exe");
        return "Habbo".equals(titre);
    }

    /** La fenetre du jeu (visible, la plus grande), ou null. */
    static WinDef.HWND fenetreHabbo() {
        WinDef.HWND[] meilleure = {null};
        long[] aire = {0};
        N.U.EnumWindows((h, data) -> {
            try {
                if (!N.U.IsWindowVisible(h)) return true;
                IntByReference pid = new IntByReference();
                N.U.GetWindowThreadProcessId(h, pid);
                char[] t = new char[256];
                int n = N.U.GetWindowText(h, t, t.length);
                String titre = n > 0 ? new String(t, 0, n) : "";
                if (!estJeu(programme(pid.getValue()), titre)) return true;
                WinDef.RECT r = new WinDef.RECT();
                if (!N.U.GetWindowRect(h, r)) return true;
                long a = (long) (r.right - r.left) * (r.bottom - r.top);
                boolean reduite = u32().IsIconic(h);
                if (!reduite && (r.right - r.left < 300 || r.bottom - r.top < 200)) return true;
                if (reduite) a = 1;                       // une fenetre reduite, faute de mieux
                if (a > aire[0]) { aire[0] = a; meilleure[0] = h; }
            } catch (Throwable ignored) { }
            return true;
        }, null);
        return meilleure[0];
    }

    private static final String ABSENT = "L'appli Habbo n'est pas ouverte : lance-la et entre dans la salle.";

    /** Met la fenetre devant ; true si elle y est. */
    private static boolean devant(WinDef.HWND h) {
        if (u32().IsIconic(h)) N.U.ShowWindow(h, SW_RESTORE);
        N.U.SetForegroundWindow(h);
        if (h.equals(N.U.GetForegroundWindow())) return true;
        // Windows refuse parfois de changer le premier plan : une frappe Alt le debloque.
        u32().keybd_event((byte) VK_MENU, (byte) 0, 0, null);
        u32().keybd_event((byte) VK_MENU, (byte) 0, 2, null);
        N.U.SetForegroundWindow(h);
        N.U.BringWindowToTop(h);
        for (int i = 0; i < 10; i++) {
            if (h.equals(N.U.GetForegroundWindow())) return true;
            Salle.sommeil(100);
        }
        return false;
    }

    // ------------------------------------------------------------ clavier

    private static void touche(int vk, int scan, int flags) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        in.input.ki.wVk = new WinDef.WORD(vk);
        in.input.ki.wScan = new WinDef.WORD(scan);
        in.input.ki.dwFlags = new WinDef.DWORD(flags);
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        N.U.SendInput(new WinDef.DWORD(1), new WinUser.INPUT[]{in}, in.size());
        Salle.sommeil(8);
    }

    private static void vk(int vk) {
        touche(vk, 0, 0);
        touche(vk, 0, WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
    }

    /** Tape un texte caractere par caractere, sans dependre de la disposition du clavier. */
    private static void taper(String s) {
        for (char c : s.toCharArray()) {
            touche(0, c, WinUser.KEYBDINPUT.KEYEVENTF_UNICODE);
            touche(0, c, WinUser.KEYBDINPUT.KEYEVENTF_UNICODE | WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
        }
    }

    private static String lirePressePapiers() {
        try {
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor))
                return (String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Throwable ignored) { }
        return null;
    }

    private static void ecrirePressePapiers(String s) throws Exception {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    /**
     * Fait taper « :screenshot » + Entree dans Habbo, puis rend la main a la
     * fenetre qui etait devant. @return null si c'est parti, sinon un message.
     */
    static String declencher(boolean coller) {
        try {
            WinDef.HWND h = fenetreHabbo();
            if (h == null) return ABSENT;
            WinDef.HWND avant = N.U.GetForegroundWindow();
            if (!devant(h))
                return "Windows n'a pas laissé l'Atelier mettre Habbo au premier plan. Clique une fois "
                        + "dans Habbo puis réessaie, ou coche « Traiter tout seul » et tape :screenshot toi-même.";
            Salle.sommeil(600);
            String ancien = null;
            if (coller) {
                ancien = lirePressePapiers();
                ecrirePressePapiers(":screenshot");
                Salle.sommeil(80);
                touche(VK_CONTROL, 0, 0);
                vk(VK_V);
                touche(VK_CONTROL, 0, WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
            } else {
                taper(":screenshot");
            }
            Salle.sommeil(200);
            vk(VK_RETURN);
            if (coller) {
                Salle.sommeil(200);
                if (ancien != null) try { ecrirePressePapiers(ancien); } catch (Throwable ignored) { }
            }
            Salle.sommeil(400);
            if (avant != null && !avant.equals(h)) try { N.U.SetForegroundWindow(avant); } catch (Throwable ignored) { }
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "La frappe au clavier n'est pas disponible sur ce Windows (" + e.getMessage() + ").";
        } catch (Throwable t) {
            return "La commande n'a pas pu être tapée : " + t;
        }
    }

    // ------------------------------------------------------------ cadre

    /** Position et taille de la fenetre (pixels de l'ecran). */
    static Capture.Ou<Capture.Cadre> lireCadre() {
        try {
            WinDef.HWND h = fenetreHabbo();
            if (h == null) return new Capture.Ou<>(null, ABSENT);
            if (u32().IsIconic(h)) N.U.ShowWindow(h, SW_RESTORE);
            WinDef.RECT r = new WinDef.RECT();
            if (!N.U.GetWindowRect(h, r)) return new Capture.Ou<>(null, "Taille de la fenêtre Habbo illisible.");
            return new Capture.Ou<>(new Capture.Cadre(r.left, r.top, r.right - r.left, r.bottom - r.top), null);
        } catch (Throwable t) {
            return new Capture.Ou<>(null, "Fenêtre Habbo illisible : " + t);
        }
    }

    /**
     * Place la fenetre. SWP_NOSENDCHANGING : Windows ne la ramene pas a la
     * taille de l'ecran, on peut l'agrandir au-dela. null si OK.
     */
    static String reglerCadre(Integer x, Integer y, int l, int hauteur) {
        try {
            WinDef.HWND h = fenetreHabbo();
            if (h == null) return ABSENT;
            if (u32().IsZoomed(h) || u32().IsIconic(h)) N.U.ShowWindow(h, SW_RESTORE);
            int f = SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOSENDCHANGING | (x == null ? SWP_NOMOVE : 0);
            if (!N.U.SetWindowPos(h, null, x == null ? 0 : x, y == null ? 0 : y, l, hauteur, f))
                return "Windows a refusé de redimensionner la fenêtre Habbo (erreur "
                        + Kernel32.INSTANCE.GetLastError() + ").";
            return null;
        } catch (Throwable t) {
            return "Fenêtre Habbo impossible à placer : " + t;
        }
    }

    // ------------------------------------------------------------ photo

    /**
     * La zone client de la fenetre (sans barre de titre). PrintWindow
     * d'abord (marche meme recouverte) ; si l'image est vide, copie de
     * l'ecran apres avoir mis Habbo devant.
     */
    static Capture.Ou<Capture.Photo> capturer() {
        try {
            WinDef.HWND h = fenetreHabbo();
            if (h == null) return new Capture.Ou<>(null, ABSENT);
            if (u32().IsIconic(h)) { N.U.ShowWindow(h, SW_RESTORE); Salle.sommeil(400); }
            WinDef.RECT c = new WinDef.RECT();
            if (!N.U.GetClientRect(h, c)) return new Capture.Ou<>(null, "Taille de la fenêtre Habbo illisible.");
            int w = c.right - c.left, ht = c.bottom - c.top;
            if (w <= 0 || ht <= 0) return new Capture.Ou<>(null, "Fenêtre Habbo vide (réduite ?).");
            BufferedImage b = photo(h, w, ht, true);
            if (b == null || Capture.sansContenu(b)) {
                Journal.debug("Capture Windows : PrintWindow vide, copie de l'écran.");
                devant(h);
                Salle.sommeil(300);
                b = photo(h, w, ht, false);
            }
            if (b == null || Capture.sansContenu(b)) return new Capture.Ou<>(null, Capture.AUTORISATION_ECRAN);
            return new Capture.Ou<>(new Capture.Photo(b, 1.0), null);
        } catch (UnsatisfiedLinkError e) {
            return new Capture.Ou<>(null, "Capture de fenêtre indisponible sur ce Windows (" + e.getMessage() + ").");
        } catch (Throwable t) {
            return new Capture.Ou<>(null, "Capture de la fenêtre impossible : " + t);
        }
    }

    /** Une image w x h de la zone client : par PrintWindow, ou par copie de l'ecran. */
    private static BufferedImage photo(WinDef.HWND h, int w, int ht, boolean parFenetre) {
        GDI32 g = GDI32.INSTANCE;
        WinDef.HDC ecran = N.U.GetDC(null);
        if (ecran == null) return null;
        WinDef.HDC mem = null;
        WinDef.HBITMAP bmp = null;
        try {
            mem = g.CreateCompatibleDC(ecran);
            bmp = g.CreateCompatibleBitmap(ecran, w, ht);
            if (mem == null || bmp == null) return null;
            WinNT.HANDLE ancien = g.SelectObject(mem, bmp);
            boolean ok;
            if (parFenetre) {
                ok = N.U.PrintWindow(h, mem, PW_CLIENTONLY | PW_RENDERFULLCONTENT);
            } else {
                WinDef.POINT o = new WinDef.POINT(0, 0);
                u32().ClientToScreen(h, o);
                ok = g.BitBlt(mem, 0, 0, w, ht, ecran, o.x, o.y, GDI32.SRCCOPY);
            }
            g.SelectObject(mem, ancien);                 // GetDIBits : bitmap hors de tout DC
            if (!ok) return null;
            WinGDI.BITMAPINFO bi = new WinGDI.BITMAPINFO();
            bi.bmiHeader.biSize = bi.bmiHeader.size();
            bi.bmiHeader.biWidth = w;
            bi.bmiHeader.biHeight = -ht;                 // de haut en bas
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            bi.bmiHeader.biCompression = WinGDI.BI_RGB;
            Memory m = new Memory((long) w * ht * 4);
            if (g.GetDIBits(mem, bmp, 0, ht, m, bi, WinGDI.DIB_RGB_COLORS) == 0) return null;
            int[] px = m.getIntArray(0, w * ht);         // BGRA petit-boutiste = 0xAARRGGBB
            for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
            BufferedImage b = new BufferedImage(w, ht, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, w, ht, px, 0, w);
            return b;
        } finally {
            if (bmp != null) g.DeleteObject(bmp);
            if (mem != null) g.DeleteDC(mem);
            N.U.ReleaseDC(null, ecran);
        }
    }
}

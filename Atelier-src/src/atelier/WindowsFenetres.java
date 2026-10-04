package atelier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le natif Windows des fenetres de l'Atelier (pendant de BarrePremierPlan,
 * Ancrage et GrilleTraitsMac sous macOS), en JNA :
 *
 *   - l'appli au premier plan : GetForegroundWindow, son pid, le nom de son
 *     executable (QueryFullProcessImageName) et son titre ;
 *   - cacher / reafficher TOUTES les fenetres de l'Atelier sans lui donner le
 *     focus (ShowWindow SW_HIDE / SW_SHOWNA) ;
 *   - l'empilement : sous Windows, toute Stage « toujours devant » est
 *     HWND_TOPMOST ; on remonte (SetWindowPos, sans activer) les fenetres de
 *     l'Atelier au-dessus des menus fixes, puis les bulles au-dessus de tout ;
 *   - le cadre de la fenetre Habbo (bords visibles par DWM), converti en
 *     coordonnees JavaFX (pixels logiques, mise a l'echelle de l'ecran) ;
 *   - rendre une fenetre transparente aux clics (WS_EX_TRANSPARENT).
 *
 * Rien n'est charge sur Mac : user32 / kernel32 / dwmapi ne sont touches que
 * dans la classe interne Natif, chargee au premier appel sous Windows. La
 * logique pure (en haut) se teste sur n'importe quel systeme.
 *
 * Les HWND circulent en long (0 = aucun) : le reste de l'Atelier ne voit
 * aucun type Win32.
 */
final class WindowsFenetres {

    private WindowsFenetres() { }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    // ============================================================ logique pure

    /** « C:\...\Habbo.exe » -> « Habbo » ; null si vide. */
    static String sansExe(String chemin) {
        if (chemin == null || chemin.isBlank()) return null;
        String n = chemin.trim();
        int i = Math.max(n.lastIndexOf('\\'), n.lastIndexOf('/'));
        if (i >= 0) n = n.substring(i + 1);
        if (n.toLowerCase().endsWith(".exe")) n = n.substring(0, n.length() - 4);
        return n.isBlank() ? null : n;
    }

    /**
     * Le client Habbo (AIR / Flash) ? Meme regle que RaccourcisGlobaux.estHabbo :
     * « Habbo » seul, « Habbo Launcher » est une autre appli. L'executable
     * decide quand on sait le lire ; sinon (rare) le titre exact « Habbo ».
     */
    static boolean estHabbo(String exe, String titre) {
        String e = sansExe(exe);
        if (e != null) return e.equalsIgnoreCase("Habbo");
        return titre != null && titre.trim().equalsIgnoreCase("Habbo");
    }

    /**
     * Fenetres de passage du systeme (barre des taches, Alt+Tab, vignettes) :
     * on ne sait pas encore qui va passer devant, on ne change rien.
     */
    static boolean neutre(String classe) {
        if (classe == null) return false;
        switch (classe) {
            case "Shell_TrayWnd": case "Shell_SecondaryTrayWnd":
            case "MultitaskingViewFrame": case "XamlExplorerHostIslandWindow":
            case "ForegroundStaging": case "TaskSwitcherWnd": case "TaskListThumbnailWnd":
            case "NotifyIconOverflowWindow": case "TopLevelWindowForOverflowXamlIsland":
                return true;
            default:
                return false;
        }
    }

    /**
     * Le nom a donner a la regle de BarrePremierPlan.doitMontrer : « Habbo »
     * pour le jeu, le nom de l'executable (ou a defaut le titre, la classe)
     * pour une autre appli, null si illisible ou fenetre de passage.
     */
    static String nomDevant(String exe, String titre, String classe) {
        if (neutre(classe)) return null;
        if (estHabbo(exe, titre)) return "Habbo";
        String e = sansExe(exe);
        if (e != null) return e;
        if (titre != null && !titre.isBlank()) return titre.trim();
        if (classe != null && !classe.isBlank()) return classe;
        return null;
    }

    /** Fenetres de notre processus a ne jamais cacher ni empiler (console, saisie). */
    static boolean ignoree(String classe) {
        return classe == null || classe.equals("ConsoleWindowClass") || classe.equals("PseudoConsoleWindow")
                || classe.equals("IME") || classe.equals("MSCTFIME UI");
    }

    /** Rang d'empilement : bulles au-dessus des fenetres, fenetres au-dessus des menus. */
    enum Genre {
        MENU(0), FENETRE(1), BULLE(2);
        final int rang;
        Genre(int r) { rang = r; }
    }

    /**
     * Empilement voulu. ordre = nos fenetres « toujours devant », de la plus
     * haute a la plus basse. Renvoie les indices a remonter, DANS L'ORDRE des
     * appels (chaque appel pose la fenetre tout en haut) : vide = deja bon.
     * On remonte les fenetres (de la plus basse a la plus haute : leur ordre
     * entre elles est garde) puis les bulles ; les menus ne bougent jamais.
     */
    static List<Integer> aMonter(List<Genre> ordre) {
        List<Integer> r = new ArrayList<>();
        boolean bon = true;
        for (int i = 1; i < ordre.size() && bon; i++)
            if (ordre.get(i).rang > ordre.get(i - 1).rang) bon = false;
        if (bon) return r;
        for (Genre g : new Genre[]{Genre.FENETRE, Genre.BULLE})
            for (int i = ordre.size() - 1; i >= 0; i--)
                if (ordre.get(i) == g) r.add(i);
        return r;
    }

    /**
     * Pixels physiques (Win32) -> coordonnees JavaFX. rect = {x, y, l, h},
     * moniteur = {gauche, haut, l, h} (rcMonitor, physique), ecrans = un
     * {minX, minY, l, h, echelleX, echelleY} par Screen JavaFX, le principal
     * en premier. On reconnait l'ecran JavaFX du moniteur a sa taille ; dans
     * cet ecran, JavaFX compte depuis son coin : x = minX + (px - gauche) / echelle.
     */
    static double[] versLogique(double[] rect, double[] moniteur, List<double[]> ecrans) {
        if (rect == null) return null;
        if (ecrans == null || ecrans.isEmpty()) return rect.clone();
        double[] choisi = null; double kx = 1, ky = 1, meilleur = Double.MAX_VALUE;
        if (moniteur != null) {
            for (double[] e : ecrans) {
                for (int essai = 0; essai < 2; essai++) {
                    double sx = essai == 0 ? e[4] : 1, sy = essai == 0 ? e[5] : 1;
                    if (Math.abs(e[2] * sx - moniteur[2]) > 2 || Math.abs(e[3] * sy - moniteur[3]) > 2) continue;
                    double d = Math.min(Math.abs(e[0] - moniteur[0] / sx) + Math.abs(e[1] - moniteur[1] / sy),
                                        Math.abs(e[0] - moniteur[0]) + Math.abs(e[1] - moniteur[1]));
                    if (d < meilleur) { meilleur = d; choisi = e; kx = sx; ky = sy; }
                }
            }
        }
        if (choisi == null) {
            // Moniteur inconnu : echelle de l'ecran principal, origine commune.
            double[] p = ecrans.get(0);
            return new double[]{rect[0] / p[4], rect[1] / p[5], rect[2] / p[4], rect[3] / p[5]};
        }
        return new double[]{choisi[0] + (rect[0] - moniteur[0]) / kx, choisi[1] + (rect[1] - moniteur[1]) / ky,
                            rect[2] / kx, rect[3] / ky};
    }

    // ================================================================ natif

    private static boolean tente = false;
    private static boolean pret = false;
    private static String probleme = null;

    static String probleme() { return probleme; }

    /** Charge user32 / kernel32 (dwmapi en option). false hors Windows ou en cas d'echec. */
    static synchronized boolean charger() {
        if (tente) return pret;
        tente = true;
        if (!windows()) { probleme = "Windows seulement"; return false; }
        try {
            Natif.essayer();
            pret = true;
        } catch (Throwable t) {
            probleme = String.valueOf(t);
            System.err.println("[Atelier] Fenêtres Windows indisponibles : " + t);
        }
        return pret;
    }

    /** Ce qui est au premier plan. */
    static final class Devant {
        String exe, titre, classe;
        boolean nous;
    }

    /** La fenetre au premier plan, ou null (aucune, ou illisible). N'importe quel fil. */
    static Devant devant() {
        if (!charger()) return null;
        try { return Natif.devant(); } catch (Throwable t) { return null; }
    }

    /** HWND de nos fenetres visibles (hors console), de la plus haute a la plus basse. N'importe quel fil. */
    static List<Long> notresVisibles() {
        if (!charger()) return List.of();
        try { return Natif.notres(false); } catch (Throwable t) { return List.of(); }
    }

    /** Idem, seulement celles « toujours devant » (HWND_TOPMOST) qui recoivent la souris. */
    static List<Long> notresDevant() {
        if (!charger()) return List.of();
        try { return Natif.notres(true); } catch (Throwable t) { return List.of(); }
    }

    /** Cache ces fenetres (SW_HIDE). Fil JavaFX. Renvoie celles effectivement cachees. */
    static List<Long> cacher(List<Long> hwnds) {
        List<Long> r = new ArrayList<>();
        if (!charger()) return r;
        for (long h : hwnds) {
            try { if (Natif.cacher(h)) r.add(h); }
            catch (Throwable t) { System.err.println("[Atelier] masquage d'une fenêtre impossible : " + t); }
        }
        return r;
    }

    /**
     * Reaffiche ces fenetres SANS les activer (SW_SHOWNA : etat et place
     * gardes, le jeu garde le focus). Une fenetre fermee entre-temps par
     * l'Atelier (JavaFX detruit la fenetre native a chaque hide) n'existe plus :
     * elle est sautee. Fil JavaFX.
     */
    static int montrer(Iterable<Long> hwnds) {
        int n = 0;
        if (!charger()) return 0;
        for (long h : hwnds) {
            try { if (Natif.montrer(h)) n++; }
            catch (Throwable t) { System.err.println("[Atelier] réaffichage d'une fenêtre impossible : " + t); }
        }
        return n;
    }

    /** Pose la fenetre tout en haut des « toujours devant », sans l'activer ni la bouger. Fil JavaFX. */
    static boolean monter(long hwnd) {
        if (!charger()) return false;
        try { return Natif.monter(hwnd); } catch (Throwable t) { return false; }
    }

    /** Fenetre encore la notre et vivante ? */
    static boolean valide(long hwnd) {
        if (hwnd == 0 || !charger()) return false;
        try { return Natif.valide(hwnd); } catch (Throwable t) { return false; }
    }

    /**
     * HWND d'une Stage AFFICHEE : titre unique le temps de la chercher
     * (FindWindow), puis on remet le sien — comme GrilleTraitsMac.nsWindow.
     * Glass pose le titre tout de suite (SetWindowText sur le fil JavaFX, qui
     * est le fil de la fenetre). Fil JavaFX. 0 si introuvable.
     */
    static long hwnd(javafx.stage.Stage s) {
        if (s == null || !s.isShowing() || s.titleProperty().isBound() || !charger()) return 0;
        String ancien = s.getTitle();
        String unique = "atelier-hwnd-" + System.identityHashCode(s) + "-" + System.nanoTime();
        try {
            s.setTitle(unique);
            return Natif.trouver(unique);
        } catch (Throwable t) {
            return 0;
        } finally {
            s.setTitle(ancien);
        }
    }

    /** Les clics traversent la fenetre (WS_EX_LAYERED | WS_EX_TRANSPARENT). Fil JavaFX. */
    static boolean traversante(long hwnd) {
        if (hwnd == 0 || !charger()) return false;
        try { return Natif.traversante(hwnd); } catch (Throwable t) { return false; }
    }

    /**
     * Cadre de la plus grande fenetre Habbo visible (ni reduite, ni sur un
     * autre bureau), en coordonnees JavaFX {x, y, l, h}, barre de titre
     * comprise, sans les bords invisibles de Windows 10/11 ; null si aucune.
     * N'importe quel fil (fil de l'ancrage).
     */
    static double[] cadreHabbo() {
        if (!charger()) return null;
        try {
            double[][] r = Natif.habbo();
            if (r == null) return null;
            return versLogique(r[0], r[1], ecrans());
        } catch (Throwable t) { return null; }
    }

    /** Les ecrans JavaFX (le principal en premier), pour versLogique. */
    private static List<double[]> ecrans() {
        List<double[]> l = new ArrayList<>();
        try {
            javafx.stage.Screen p = javafx.stage.Screen.getPrimary();
            List<javafx.stage.Screen> tous = new ArrayList<>(javafx.stage.Screen.getScreens());
            tous.remove(p);
            tous.add(0, p);
            for (javafx.stage.Screen s : tous) {
                javafx.geometry.Rectangle2D b = s.getBounds();
                l.add(new double[]{b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(),
                        s.getOutputScaleX() > 0 ? s.getOutputScaleX() : 1, s.getOutputScaleY() > 0 ? s.getOutputScaleY() : 1});
            }
        } catch (Throwable ignored) { }
        return l;
    }

    // ======================================================= Win32 (JNA)

    /** Seule classe qui touche aux bibliotheques Windows : chargee au premier appel sous Windows. */
    private static final class Natif {

        interface Dwm extends com.sun.jna.Library {
            int DwmGetWindowAttribute(com.sun.jna.platform.win32.WinDef.HWND h, int attribut,
                                      com.sun.jna.platform.win32.WinDef.RECT valeur, int taille);
            int DwmGetWindowAttribute(com.sun.jna.platform.win32.WinDef.HWND h, int attribut,
                                      com.sun.jna.ptr.IntByReference valeur, int taille);
        }

        /** Ce que jna-platform ne declare pas. */
        interface UserPlus extends com.sun.jna.Library {
            boolean IsIconic(com.sun.jna.platform.win32.WinDef.HWND h);
        }

        static final int GWL_EXSTYLE = -20;
        static final int WS_EX_TOPMOST = 0x8, WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000;
        static final int SW_HIDE = 0, SW_SHOWNA = 8;
        static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10;
        static final int LWA_ALPHA = 0x2;
        static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        static final int MONITOR_DEFAULTTONEAREST = 2;
        static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9, DWMWA_CLOAKED = 14;

        static final com.sun.jna.platform.win32.User32 U = com.sun.jna.platform.win32.User32.INSTANCE;
        static final com.sun.jna.platform.win32.Kernel32 K = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        static final com.sun.jna.platform.win32.WinDef.HWND HWND_TOPMOST =
                new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(-1));
        static final int NOTRE_PID = (int) ProcessHandle.current().pid();
        static final UserPlus U2 = com.sun.jna.Native.load("user32", UserPlus.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        static Dwm dwm;
        /** pid -> executable : EnumWindows passe des centaines de fenetres a chaque tour. */
        static final Map<Integer, String> exes = new java.util.concurrent.ConcurrentHashMap<>();

        static void essayer() {
            U.GetForegroundWindow();                 // charge user32
            K.GetCurrentProcessId();                 // charge kernel32
            try {
                dwm = com.sun.jna.Native.load("dwmapi", Dwm.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
            } catch (Throwable t) {
                dwm = null;
                Journal.debug("dwmapi indisponible, bords lus par GetWindowRect : " + t);
            }
            Journal.debug("fenêtres Windows : natif chargé (pid " + NOTRE_PID + ").");
        }

        static com.sun.jna.platform.win32.WinDef.HWND h(long v) {
            return new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(v));
        }
        static long v(com.sun.jna.platform.win32.WinDef.HWND h) {
            return h == null ? 0 : com.sun.jna.Pointer.nativeValue(h.getPointer());
        }

        static int pid(com.sun.jna.platform.win32.WinDef.HWND h) {
            com.sun.jna.ptr.IntByReference p = new com.sun.jna.ptr.IntByReference();
            U.GetWindowThreadProcessId(h, p);
            return p.getValue();
        }

        static String classe(com.sun.jna.platform.win32.WinDef.HWND h) {
            char[] b = new char[256];
            int n = U.GetClassName(h, b, b.length);
            return n <= 0 ? null : new String(b, 0, n);
        }

        static String titre(com.sun.jna.platform.win32.WinDef.HWND h) {
            char[] b = new char[512];
            int n = U.GetWindowText(h, b, b.length);
            return n <= 0 ? "" : new String(b, 0, n);
        }

        /** Chemin complet de l'executable du processus, ou null. */
        static String exe(int pid) {
            if (pid <= 0) return null;
            String c = exes.get(pid);
            if (c != null) return c;
            com.sun.jna.platform.win32.WinNT.HANDLE p = K.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (p == null) return null;
            try {
                char[] b = new char[1024];
                com.sun.jna.ptr.IntByReference n = new com.sun.jna.ptr.IntByReference(b.length);
                if (!K.QueryFullProcessImageName(p, 0, b, n)) return null;
                c = new String(b, 0, n.getValue());
            } finally {
                K.CloseHandle(p);
            }
            if (exes.size() > 256) exes.clear();
            exes.put(pid, c);
            return c;
        }

        static Devant devant() {
            com.sun.jna.platform.win32.WinDef.HWND h = U.GetForegroundWindow();
            if (h == null || v(h) == 0) return null;
            Devant d = new Devant();
            int pid = pid(h);
            d.nous = pid == NOTRE_PID;
            d.classe = classe(h);
            d.titre = titre(h);
            d.exe = d.nous ? null : exe(pid);
            return d;
        }

        static int exStyle(com.sun.jna.platform.win32.WinDef.HWND h) { return U.GetWindowLong(h, GWL_EXSTYLE); }

        /** EnumWindows parcourt les fenetres du haut vers le bas (ordre Z). */
        static List<Long> notres(boolean seulementDevant) {
            List<Long> l = new ArrayList<>();
            com.sun.jna.platform.win32.WinUser.WNDENUMPROC cb = (h, data) -> {
                try {
                    if (!U.IsWindowVisible(h) || pid(h) != NOTRE_PID || ignoree(classe(h))) return true;
                    if (seulementDevant) {
                        int ex = exStyle(h);
                        if ((ex & WS_EX_TOPMOST) == 0 || (ex & WS_EX_TRANSPARENT) != 0) return true;
                    }
                    l.add(v(h));
                } catch (Throwable ignored) { }
                return true;
            };
            U.EnumWindows(cb, null);
            return l;
        }

        static boolean valide(long v) {
            com.sun.jna.platform.win32.WinDef.HWND h = h(v);
            return U.IsWindow(h) && pid(h) == NOTRE_PID;
        }

        static boolean cacher(long v) {
            com.sun.jna.platform.win32.WinDef.HWND h = h(v);
            if (!valide(v) || !U.IsWindowVisible(h)) return false;
            U.ShowWindow(h, SW_HIDE);
            return !U.IsWindowVisible(h);
        }

        static boolean montrer(long v) {
            com.sun.jna.platform.win32.WinDef.HWND h = h(v);
            if (!valide(v) || U.IsWindowVisible(h)) return false;
            U.ShowWindow(h, SW_SHOWNA);
            return U.IsWindowVisible(h);
        }

        static boolean monter(long v) {
            if (!valide(v)) return false;
            return U.SetWindowPos(h(v), HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        }

        static long trouver(String titre) {
            com.sun.jna.platform.win32.WinDef.HWND h = U.FindWindow(null, titre);
            if (h == null || v(h) == 0 || pid(h) != NOTRE_PID) return 0;
            return v(h);
        }

        static boolean traversante(long v) {
            if (!valide(v)) return false;
            com.sun.jna.platform.win32.WinDef.HWND h = h(v);
            int ex = exStyle(h);
            if ((ex & WS_EX_LAYERED) == 0) {
                // Une fenetre devenue « layered » est invisible tant qu'on ne lui donne pas d'opacite.
                U.SetWindowLong(h, GWL_EXSTYLE, ex | WS_EX_LAYERED);
                U.SetLayeredWindowAttributes(h, 0, (byte) 255, LWA_ALPHA);
            }
            U.SetWindowLong(h, GWL_EXSTYLE, exStyle(h) | WS_EX_TRANSPARENT);
            return (exStyle(h) & WS_EX_TRANSPARENT) != 0;
        }

        /** {rect physique {x, y, l, h}, moniteur physique {gauche, haut, l, h}} de la plus grande fenetre Habbo. */
        static double[][] habbo() {
            final double[][] meilleure = {null, null};
            com.sun.jna.platform.win32.WinUser.WNDENUMPROC cb = (h, data) -> {
                try {
                    if (!U.IsWindowVisible(h) || U2.IsIconic(h)) return true;
                    int pid = pid(h);
                    if (pid == NOTRE_PID) return true;
                    if (!estHabbo(exe(pid), titre(h))) return true;
                    if (cachee(h)) return true;
                    com.sun.jna.platform.win32.WinDef.RECT r = bords(h);
                    if (r == null) return true;
                    double l = r.right - r.left, ha = r.bottom - r.top;
                    if (l < 400 || ha < 300) return true;
                    if (meilleure[0] == null || l * ha > meilleure[0][2] * meilleure[0][3]) {
                        meilleure[0] = new double[]{r.left, r.top, l, ha};
                        meilleure[1] = moniteur(h);
                    }
                } catch (Throwable ignored) { }
                return true;
            };
            U.EnumWindows(cb, null);
            return meilleure[0] == null ? null : meilleure;
        }

        /** Sur un autre bureau virtuel, ou masquee par le systeme. */
        static boolean cachee(com.sun.jna.platform.win32.WinDef.HWND h) {
            if (dwm == null) return false;
            try {
                com.sun.jna.ptr.IntByReference c = new com.sun.jna.ptr.IntByReference();
                return dwm.DwmGetWindowAttribute(h, DWMWA_CLOAKED, c, 4) == 0 && c.getValue() != 0;
            } catch (Throwable t) { return false; }
        }

        /** Bords visibles (DWM), ou GetWindowRect (avec les bords invisibles) a defaut. */
        static com.sun.jna.platform.win32.WinDef.RECT bords(com.sun.jna.platform.win32.WinDef.HWND h) {
            com.sun.jna.platform.win32.WinDef.RECT r = new com.sun.jna.platform.win32.WinDef.RECT();
            if (dwm != null) {
                try {
                    if (dwm.DwmGetWindowAttribute(h, DWMWA_EXTENDED_FRAME_BOUNDS, r, r.size()) == 0
                            && r.right > r.left) return r;
                } catch (Throwable ignored) { }
            }
            return U.GetWindowRect(h, r) ? r : null;
        }

        static double[] moniteur(com.sun.jna.platform.win32.WinDef.HWND h) {
            try {
                com.sun.jna.platform.win32.WinUser.HMONITOR m = U.MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST);
                if (m == null) return null;
                com.sun.jna.platform.win32.WinUser.MONITORINFO i = new com.sun.jna.platform.win32.WinUser.MONITORINFO();
                i.cbSize = i.size();
                if (!U.GetMonitorInfo(m, i).booleanValue()) return null;
                return new double[]{i.rcMonitor.left, i.rcMonitor.top,
                        i.rcMonitor.right - i.rcMonitor.left, i.rcMonitor.bottom - i.rcMonitor.top};
            } catch (Throwable t) { return null; }
        }
    }
}

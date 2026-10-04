package atelier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clavier sous Windows : raccourcis globaux (RegisterHotKey), etat des touches
 * (GetAsyncKeyState), appli au premier plan, et noms des touches dans les textes.
 *
 * Rien de Windows n'est charge sur Mac : la partie native est dans la classe
 * interne Natif, chargee seulement au premier appel fait sous Windows. Le reste
 * (noms des touches, table des modificateurs) est de la logique pure, utilisable
 * et testee partout.
 *
 * Raccourcis : un raccourci Windows est lie au FIL qui l'enregistre, et le
 * message WM_HOTKEY arrive dans la file de ce fil. On a donc un fil dedie avec sa
 * boucle GetMessage ; les autres fils lui demandent (PostThreadMessage) de
 * remettre la liste a jour. Comme sous Mac, un raccourci enregistre AVALE la
 * touche dans toutes les applis : RaccourcisGlobaux ne les enregistre que quand
 * Habbo est devant et le chat vide.
 *
 * Codes de touches : sous Windows, le code virtuel (VK) d'une lettre suit la
 * disposition clavier (en AZERTY, la touche marquee Z donne VK_Z = 0x5A), a
 * l'inverse du code physique du Mac. On le verifie quand meme par VkKeyScanEx dans
 * la disposition de la fenetre au premier plan (disposition exotique, Dvorak...).
 */
public final class WindowsClavier {

    private WindowsClavier() { }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    // ------------------------------------------------------------ logique pure

    /** Modificateurs de RegisterHotKey (winuser.h). */
    static final int MOD_ALT = 0x0001, MOD_CONTROL = 0x0002, MOD_SHIFT = 0x0004, MOD_NOREPEAT = 0x4000;
    /** Codes virtuels utiles (winuser.h). */
    static final int VK_TAB = 0x09, VK_ESCAPE = 0x1B, VK_MENU = 0x12,
                     VK_LCONTROL = 0xA2, VK_LMENU = 0xA4, VK_RMENU = 0xA5;
    /** Touche « non attribuee » envoyee pour que Windows n'ouvre pas le menu systeme apres Alt + lettre. */
    static final int VK_MASQUE = 0xE8;

    /** VK d'une lettre ASCII sur un clavier « standard » : la lettre majuscule (VK_A = 0x41...). */
    static int vkLettre(char c) {
        char m = Character.toUpperCase(c);
        if (m < 'A' || m > 'Z') throw new IllegalArgumentException("pas une lettre : " + c);
        return m;
    }

    /**
     * Resultat de VkKeyScanEx -> code VK utilisable seul, ou repli si la lettre
     * n'est pas sur une touche sans modificateur (octet haut non nul, ou -1).
     */
    static int vkDepuisScan(short scan, int repli) {
        if (scan == -1) return repli;
        int vk = scan & 0xff, etat = (scan >> 8) & 0xff;
        return etat == 0 && vk != 0 ? vk : repli;
    }

    /** Nom de touche Mac -> nom sur la plateforme donnee. Inchange sur Mac et si inconnu. */
    static String nomTouche(String mac, boolean windows) {
        if (!windows || mac == null) return mac;
        switch (mac.trim()) {
            case "Option": case "⌥": case "⌥ Option": case "Opt": return "Alt";
            case "Cmd": case "⌘": case "⌘ Cmd": case "Commande": case "Cmd/Ctrl": case "Ctrl/Cmd": return "Ctrl";
            case "Cmd+Q": case "Cmd + Q": return "Alt+F4";
            default: return mac;
        }
    }

    /** Nom de touche Mac -> nom sur la plateforme courante (« Option » -> « Alt » sous Windows). */
    public static String nomTouche(String mac) { return nomTouche(mac, WINDOWS); }

    private static final Pattern MOTS = Pattern.compile(
            "⌘ Cmd\\b|⌥ Option\\b|\\bCmd ?\\+ ?Q\\b|\\bCmd/Ctrl\\b|\\bCtrl/Cmd\\b|\\bCmd ou Ctrl\\b|\\bCtrl ou Cmd\\b"
            + "|\\bOption\\b(?!s)|\\bCmd\\b|⌘|⌥");

    /**
     * Phrase ecrite pour le Mac -> phrase pour la plateforme donnee :
     * « Option + C » -> « Alt + C », « Cmd/Ctrl+Z » -> « Ctrl+Z », « Cmd+Q » -> « Alt+F4 ».
     * Inchangee sur Mac. « Options » (le mot) n'est pas touche.
     */
    static String texte(String t, boolean windows) {
        if (!windows || t == null) return t;
        // copier / coller de calque : Ctrl + Maj sous Windows (Alt + Maj change la langue)
        t = t.replaceAll("(⌥\\s*)?(Option|⌥)\\s*\\+?\\s*(Maj|⇧)", "Ctrl + Maj");
        Matcher m = MOTS.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            String r;
            if (g.startsWith("Cmd") && g.endsWith("Q")) r = "Alt+F4";
            else if (g.contains("Ctrl")) r = "Ctrl";
            else r = nomTouche(g, true);
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Phrase pour la plateforme courante (voir texte(String, boolean)). */
    public static String texte(String t) { return texte(t, WINDOWS); }

    /** « Alt » enfonce d'apres les touches gauche/droite : AltGr (= Ctrl gauche + Alt droit) ne compte pas. */
    static boolean altSansAltGr(boolean altGauche, boolean altDroit, boolean ctrlGauche) {
        return altGauche || (altDroit && !ctrlGauche);
    }

    // ------------------------------------------------------------ API Windows

    /** true si la touche (code VK) est enfoncee maintenant. false hors Windows ou si indisponible. */
    static boolean enfoncee(int vk) {
        if (!WINDOWS) return false;
        try { return Natif.enfoncee(vk); } catch (Throwable t) { return false; }
    }

    /** Alt enfonce (role de la touche Option du Mac), AltGr exclu. */
    static boolean alt() {
        if (!WINDOWS) return false;
        try {
            return altSansAltGr(Natif.enfoncee(VK_LMENU), Natif.enfoncee(VK_RMENU), Natif.enfoncee(VK_LCONTROL));
        } catch (Throwable t) { return false; }
    }

    /** true si l'etat des touches est lisible (user32 charge). */
    static boolean disponible() {
        if (!WINDOWS) return false;
        try { return Natif.charger(); } catch (Throwable t) { return false; }
    }

    /** Programme de la fenetre au premier plan, sans « .exe » (« Habbo », « java »...), ou null. */
    static String appDevant() {
        if (!WINDOWS) return null;
        try { return Natif.appDevant(); } catch (Throwable t) { return null; }
    }

    /** VK de la touche qui produit cette lettre dans la disposition de la fenetre au premier plan. */
    static int vkPour(char lettre) {
        int repli = vkLettre(lettre);
        if (!WINDOWS) return repli;
        try { return Natif.vkPour(lettre, repli); } catch (Throwable t) { return repli; }
    }

    /**
     * Demande que les raccourcis enregistres soient exactement ceux-ci :
     * {id, vk, modificateurs MOD_*}. Tableau vide = tout desenregistrer.
     * Asynchrone : fait par le fil des raccourcis, qui appelle surRaccourci(id)
     * a chaque appui.
     */
    static void raccourcis(int[][] table, IntConsumer surRaccourci) {
        if (!WINDOWS) return;
        Natif.demander(table, surRaccourci);
    }

    /** Nombre de raccourcis actuellement enregistres (0 hors Windows). */
    static int actifs() { return WINDOWS ? Natif.actifs : 0; }

    /** Raison pour laquelle les raccourcis Windows ne marchent pas, ou null. */
    static String probleme() { return WINDOWS ? Natif.probleme : null; }

    // ------------------------------------------------------------ natif

    /** Tout ce qui touche a JNA / user32. Chargee seulement sous Windows. */
    private static final class Natif {

        /** keybd_event n'est pas dans jna-platform. */
        private interface User32Plus extends com.sun.jna.Library {
            void keybd_event(byte vk, byte scan, int drapeaux, com.sun.jna.Pointer extra);
        }

        private static final int WM_APP = 0x8000, WM_HOTKEY = 0x0312, PM_NOREMOVE = 0;
        private static final int KEYEVENTF_KEYUP = 0x0002;

        private static com.sun.jna.platform.win32.User32 u;
        private static User32Plus plus;
        private static boolean tente = false;
        static volatile String probleme = null;
        static volatile int actifs = 0;

        static synchronized boolean charger() {
            if (tente) return u != null;
            tente = true;
            try {
                u = com.sun.jna.platform.win32.User32.INSTANCE;
                u.GetAsyncKeyState(VK_MENU);
                Journal.debug("clavier Windows : user32 chargé.");
            } catch (Throwable t) {
                u = null;
                probleme = "user32 indisponible (" + t + ")";
                System.err.println("[Atelier] clavier Windows indisponible : " + t);
            }
            try {
                plus = com.sun.jna.Native.load("user32", User32Plus.class);
            } catch (Throwable t) {
                plus = null;
                Journal.debug("clavier Windows : keybd_event indisponible (" + t + ")");
            }
            return u != null;
        }

        static boolean enfoncee(int vk) {
            return charger() && (u.GetAsyncKeyState(vk) & 0x8000) != 0;
        }

        // ------------------------------------------------ premier plan

        private static int dernierPid = -1;
        private static String dernierNom = null;

        static synchronized String appDevant() {
            if (!charger()) return null;
            com.sun.jna.platform.win32.WinDef.HWND h = u.GetForegroundWindow();
            if (h == null) return null;
            com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
            u.GetWindowThreadProcessId(h, pid);
            int p = pid.getValue();
            if (p == 0) return null;
            if (p == dernierPid && dernierNom != null) return dernierNom;   // appele toutes les 150 ms
            String chemin = ProcessHandle.of(p).flatMap(x -> x.info().command()).orElse(null);
            if (chemin == null) chemin = cheminParKernel32(p);              // autre utilisateur / droits
            String nom = chemin == null ? null : sansExe(new java.io.File(chemin).getName());
            dernierPid = p;
            dernierNom = nom;
            return nom;
        }

        private static String cheminParKernel32(int pid) {
            try {
                com.sun.jna.platform.win32.Kernel32 k = com.sun.jna.platform.win32.Kernel32.INSTANCE;
                com.sun.jna.platform.win32.WinNT.HANDLE h =
                        k.OpenProcess(com.sun.jna.platform.win32.WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
                if (h == null) return null;
                try {
                    char[] buf = new char[1024];
                    com.sun.jna.ptr.IntByReference n = new com.sun.jna.ptr.IntByReference(buf.length);
                    return k.QueryFullProcessImageName(h, 0, buf, n) ? new String(buf, 0, n.getValue()) : null;
                } finally {
                    k.CloseHandle(h);
                }
            } catch (Throwable t) {
                return null;
            }
        }

        static String sansExe(String n) {
            return n.toLowerCase().endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
        }

        static int vkPour(char lettre, int repli) {
            if (!charger()) return repli;
            com.sun.jna.platform.win32.WinDef.HWND h = u.GetForegroundWindow();
            int fil = h == null ? 0 : u.GetWindowThreadProcessId(h, null);
            com.sun.jna.platform.win32.WinDef.HKL hkl = u.GetKeyboardLayout(fil);
            return vkDepuisScan(u.VkKeyScanExW(Character.toLowerCase(lettre), hkl), repli);
        }

        // ------------------------------------------------ raccourcis

        private static volatile int[][] voulus = new int[0][];
        private static volatile IntConsumer action = id -> { };
        private static volatile int filId = 0;
        private static Thread fil;
        /** id -> modificateurs, pour les raccourcis enregistres (fil des raccourcis seulement). */
        private static final Map<Integer, Integer> enregistres = new LinkedHashMap<>();
        private static String derniersRefus = "[]";

        static void demander(int[][] table, IntConsumer surRaccourci) {
            voulus = table == null ? new int[0][] : table;
            if (surRaccourci != null) action = surRaccourci;
            demarrer();
            int f = filId;
            if (f != 0) {
                try {
                    u.PostThreadMessage(f, WM_APP, new com.sun.jna.platform.win32.WinDef.WPARAM(0),
                            new com.sun.jna.platform.win32.WinDef.LPARAM(0));
                } catch (Throwable t) {
                    System.err.println("[Atelier] raccourcis Windows : message au fil refusé (" + t + ")");
                }
            }
            // sinon : le fil lit voulus des que sa file de messages existe
        }

        private static synchronized void demarrer() {
            if (fil != null) return;
            if (!charger()) return;
            fil = new Thread(Natif::boucle, "atelier-raccourcis-windows");
            fil.setDaemon(true);
            fil.start();
        }

        private static void boucle() {
            try {
                com.sun.jna.platform.win32.WinUser.MSG msg = new com.sun.jna.platform.win32.WinUser.MSG();
                // Cree la file de messages du fil avant d'annoncer son numero.
                u.PeekMessage(msg, null, 0x0400, 0x0400, PM_NOREMOVE);
                filId = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId();
                synchroniser();
                Journal.debug("raccourcis Windows : fil prêt.");
                while (true) {
                    int r = u.GetMessage(msg, null, 0, 0);
                    if (r == 0 || r == -1) break;
                    if (msg.message == WM_HOTKEY) {
                        int id = msg.wParam.intValue();
                        Integer mods = enregistres.get(id);
                        if (mods != null && (mods & MOD_ALT) != 0) masquerAlt();
                        try { action.accept(id); } catch (Throwable t) { System.err.println("[Atelier] raccourci " + id + " : " + t); }
                    } else if (msg.message == WM_APP) {
                        synchroniser();
                    }
                }
            } catch (Throwable t) {
                probleme = "raccourcis Windows arrêtés (" + t + ")";
                System.err.println("[Atelier] " + probleme);
            } finally {
                filId = 0;
            }
        }

        /** Enregistre exactement les raccourcis voulus (sur le fil des raccourcis). */
        private static void synchroniser() {
            int[][] v = voulus;
            for (Integer id : new ArrayList<>(enregistres.keySet())) u.UnregisterHotKey(null, id);
            enregistres.clear();
            List<String> refuses = new ArrayList<>();
            for (int[] k : v) {
                int mods = k[2] | MOD_NOREPEAT;
                if (u.RegisterHotKey(null, k[0], mods, k[1])) enregistres.put(k[0], k[2]);
                else refuses.add(k[0] + " (erreur " + com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError() + ")");
            }
            actifs = enregistres.size();
            String dit = refuses.toString();
            if (!refuses.isEmpty() && !dit.equals(derniersRefus))
                System.err.println("[Atelier] raccourcis Windows refusés (déjà pris par une autre appli ?) : " + dit);
            derniersRefus = dit;
            Journal.debug("raccourcis Windows : " + actifs + " enregistrés.");
        }

        /**
         * Alt + lettre avale la lettre : la fenetre ne voit qu'Alt appuye puis
         * relache, et Windows active alors son menu systeme (les touches suivantes
         * partent dans le menu). Une touche « non attribuee » glissee pendant
         * qu'Alt est tenu l'en empeche (meme astuce qu'AutoHotkey).
         */
        private static void masquerAlt() {
            if (plus == null || (u.GetAsyncKeyState(VK_MENU) & 0x8000) == 0) return;
            try {
                plus.keybd_event((byte) VK_MASQUE, (byte) 0, 0, null);
                plus.keybd_event((byte) VK_MASQUE, (byte) 0, KEYEVENTF_KEYUP, null);
            } catch (Throwable ignored) { }
        }
    }
}

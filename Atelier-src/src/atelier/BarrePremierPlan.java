package atelier;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Deux regles d'empilement des fenetres de l'Atelier.
 *
 * 1. Les menus fixes (barres du haut et du bas, panneau des calques) restent
 *    DESSOUS les fenetres de l'Atelier (Fenetre, editeur de floor, apercus,
 *    boites de dialogue...). Toutes sont « toujours devant » : Glass les pose
 *    au meme niveau macOS (flottant, 3), et toFront() n'y change rien quand
 *    c'est Habbo l'appli active. On pose donc des NIVEAUX NATIFS distincts
 *    (NSWindow setLevel:) :
 *        menus fixes ............ 3  (flottant)
 *        fenetres de l'Atelier .. 5
 *        bulles, menus contextuels 6
 *    tous sous la barre des menus et le Dock de macOS (20 et plus).
 *    Glass refait le NSWindow a chaque show() (niveau remis a 3) et remet le
 *    niveau a chaque setAlwaysOnTop : on le repose donc a chaque affichage
 *    et a chaque changement de « toujours devant ». Toute Stage qui n'est pas
 *    un menu fixe est une fenetre : rien a faire pour les nouvelles fenetres.
 *    Une Stage qui n'est pas « toujours devant » (reglage « Garder l'Atelier
 *    devant le jeu » decoche) garde le niveau que Glass lui donne.
 *
 * 2. L'Atelier est « integre » a Habbo : une fois connectee, si ni Habbo ni
 *    l'Atelier n'est l'appli au premier plan, tout l'Atelier se cache (comme
 *    Cmd+H), et reapparait tel quel des que Habbo (ou l'Atelier) revient
 *    devant — sans voler le focus a Habbo (unhideWithoutActivation).
 *    Lecture du premier plan : NSWorkspace.frontmostApplication, toutes les
 *    300 ms, sur un fil a part ; seuls hide/unhide passent par le fil JavaFX
 *    (le fil principal d'AppKit sous macOS), et seulement au changement.
 *
 * Sous Windows (WindowsFenetres), memes deux regles :
 *    1. toute Stage « toujours devant » est HWND_TOPMOST, sans niveaux : on
 *       remonte (SetWindowPos HWND_TOPMOST, sans activer ni bouger) les
 *       fenetres de l'Atelier au-dessus des menus fixes, puis les bulles,
 *       des que l'ordre est faux (apres un affichage, un clic, un changement
 *       de « toujours devant »). L'ordre des fenetres entre elles est garde.
 *    2. premier plan : GetForegroundWindow, son pid (le notre = l'Atelier) et
 *       son executable (« Habbo.exe » = le jeu ; « Habbo Launcher » non).
 *       Pour cacher : ShowWindow(SW_HIDE) sur chacune de nos fenetres
 *       visibles ; pour reafficher : SW_SHOWNA, qui ne donne PAS le focus et
 *       garde la place dans l'empilement. On passe par Win32 plutot que par
 *       Stage.hide/show : un hide JavaFX detruit la fenetre native (onHidden,
 *       Window.getWindows, isShowing changent, et show() active la fenetre),
 *       alors que SW_HIDE laisse JavaFX croire la fenetre affichee — comme le
 *       Cmd+H de macOS. La barre des taches et Alt+Tab ne comptent pas
 *       (on attend de savoir qui passe devant).
 */
public final class BarrePremierPlan {

    private BarrePremierPlan() { }

    static final boolean WIN = WindowsFenetres.windows();

    // =========================================================== empilement

    /** Niveaux NSWindow (kCGFloatingWindowLevel = 3 ; menus de macOS a partir de 20). */
    static final long NIVEAU_MENU = 3, NIVEAU_FENETRE = 5, NIVEAU_BULLE = 6;

    private static final List<Stage> menus = new ArrayList<>();
    private static final List<Stage> fenetres = new ArrayList<>();
    /** Stages deja suivies (affichage, « toujours devant ») ; faibles : rien ne fuit. */
    private static final java.util.Map<Stage, Boolean> suivies = new java.util.WeakHashMap<>();
    private static boolean surveille = false;

    /** Un menu fixe : sous les fenetres de l'Atelier. Fil JavaFX. */
    public static void menu(Stage s) {
        if (s == null || menus.contains(s)) return;
        menus.add(s);
        fenetres.remove(s);
        suivre(s);
        surveiller();
        if (s.isShowing()) niveau(s);
    }

    /**
     * Une fenetre de l'Atelier : au-dessus des menus fixes, et devant les autres
     * fenetres quand on clique dedans. Facultatif : toute Stage affichee qui
     * n'est pas un menu prend deja le niveau des fenetres. Fil JavaFX.
     */
    public static void fenetre(Stage s) {
        if (s == null || fenetres.contains(s) || menus.contains(s)) return;
        fenetres.add(s);
        s.focusedProperty().addListener((o, a, b) -> { if (b) devant(s); });
        s.sceneProperty().addListener((o, a, sc) -> brancherClic(s));
        brancherClic(s);
        suivre(s);
        surveiller();
        if (s.isShowing()) niveau(s);
    }

    private static void brancherClic(Stage s) {
        if (s.getScene() == null) return;
        s.getScene().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> devant(s));
    }

    /** Devant les autres fenetres du MEME niveau. */
    private static void devant(Stage s) {
        if (s.isShowing()) s.toFront();
        if (WIN) ordonnerPlusTard();
    }

    /** Repose le niveau quand « toujours devant » change (Glass remet 0 ou 3). */
    private static void suivre(Stage s) {
        if (suivies.containsKey(s)) return;
        suivies.put(s, Boolean.TRUE);
        s.alwaysOnTopProperty().addListener((o, a, b) -> Platform.runLater(() -> niveau(s)));
        // Windows : cliquer une fenetre (menu compris) la monte en haut des « toujours devant ».
        if (WIN) s.focusedProperty().addListener((o, a, b) -> ordonnerPlusTard());
    }

    /**
     * Le niveau voulu (logique pure) : null = ne pas toucher (pas « toujours
     * devant » : Glass decide).
     */
    static Long niveauVoulu(boolean estMenu, boolean toujoursDevant) {
        if (!toujoursDevant) return null;
        return estMenu ? NIVEAU_MENU : NIVEAU_FENETRE;
    }

    /** Pose le niveau natif d'une Stage affichee. Fil JavaFX. */
    static void niveau(Stage s) {
        if (WIN) { ordonnerPlusTard(); return; }
        if (s == null || !s.isShowing() || !GrilleTraitsMac.mac()) return;
        Long voulu = niveauVoulu(menus.contains(s), s.isAlwaysOnTop());
        if (voulu == null) return;
        com.sun.jna.Pointer w = GrilleTraitsMac.nsWindow(s);
        if (w == null) {
            if (!signale) { signale = true; Journal.debug("niveau des fenêtres : NSWindow introuvable (" + GrilleTraitsMac.probleme() + ")."); }
            return;
        }
        if (GrilleTraitsMac.ignoreLaSouris(w)) return;          // calque traversant : il garde son niveau
        if (GrilleTraitsMac.niveauLu(w) != voulu) GrilleTraitsMac.niveau(w, voulu);
    }
    private static boolean signale = false;

    /**
     * Chaque fenetre JavaFX affichee (Window.getWindows ne contient que les
     * fenetres visibles : un ajout = un show) prend son niveau. Le panneau des
     * calques est reconnu a son titre, « Calques ».
     */
    private static void surveiller() {
        if (surveille) return;
        surveille = true;
        for (Window w : Window.getWindows()) affichee(w);
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) for (Window w : c.getAddedSubList()) affichee(w);
        });
    }

    private static void affichee(Window w) {
        if (w instanceof Stage) {
            Stage s = (Stage) w;
            if (estPanneau(s.getTitle())) menu(s);
            suivre(s);
            if (WIN) hwnds.remove(s);       // nouvelle fenetre native a chaque show
            // apres le show : le NSWindow existe et Glass a fini de le regler
            Platform.runLater(() -> niveau(s));
        } else if (WIN) {
            ordonnerPlusTard();
        } else if (GrilleTraitsMac.mac()) {
            // bulle, menu contextuel, liste deroulante : au-dessus de tout l'Atelier
            Platform.runLater(() -> GrilleTraitsMac.monterPanneaux(NIVEAU_BULLE));
        }
    }

    // ------------------------------------------------- empilement (Windows)

    /** HWND de chaque Stage, retrouve a chaque affichage. */
    private static final java.util.Map<Stage, Long> hwnds = new java.util.WeakHashMap<>();
    private static boolean ordreDemande = false;
    private static boolean ordreSignale = false;

    /** Un seul reordonnancement par passage du fil JavaFX. N'importe quel fil. */
    private static void ordonnerPlusTard() {
        if (!WIN) return;
        synchronized (hwnds) {
            if (ordreDemande) return;
            ordreDemande = true;
        }
        try {
            Platform.runLater(() -> {
                synchronized (hwnds) { ordreDemande = false; }
                try { ordonnerWindows(); }
                catch (Throwable t) { System.err.println("[Atelier] Empilement des fenêtres impossible : " + t); }
            });
        } catch (IllegalStateException e) {
            synchronized (hwnds) { ordreDemande = false; }
        }
    }

    private static long hwndDe(Stage s) {
        Long h = hwnds.get(s);
        if (h != null && h != 0 && WindowsFenetres.valide(h)) return h;
        long n = WindowsFenetres.hwnd(s);
        if (n != 0) hwnds.put(s, n);
        return n;
    }

    /**
     * Menus fixes sous les fenetres, bulles au-dessus de tout (Windows). Ne
     * fait rien si l'ordre est deja bon. Fil JavaFX.
     */
    private static void ordonnerWindows() {
        if (!WIN || masque || !WindowsFenetres.charger()) return;
        java.util.Map<Long, WindowsFenetres.Genre> connus = new java.util.HashMap<>();
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (!(w instanceof Stage) || !w.isShowing()) continue;
            Stage s = (Stage) w;
            long h = hwndDe(s);
            if (h != 0) connus.put(h, menus.contains(s) ? WindowsFenetres.Genre.MENU : WindowsFenetres.Genre.FENETRE);
        }
        List<Long> ordre = WindowsFenetres.notresDevant();
        List<WindowsFenetres.Genre> genres = new ArrayList<>();
        // Inconnue = fenetre sans Stage (bulle, menu contextuel, liste deroulante).
        for (Long h : ordre) genres.add(connus.getOrDefault(h, WindowsFenetres.Genre.BULLE));
        List<Integer> monter = WindowsFenetres.aMonter(genres);
        for (int i : monter) WindowsFenetres.monter(ordre.get(i));
        if (!monter.isEmpty() && !ordreSignale) {
            ordreSignale = true;
            Journal.debug("empilement Windows : " + monter.size() + " fenêtre(s) remontée(s) au-dessus des menus.");
        }
    }

    /** Titre du panneau des calques (logique pure). */
    static boolean estPanneau(String titre) { return "Calques".equals(titre); }

    // ========================================================== premier plan

    private static volatile boolean connecte = false;
    private static volatile boolean masque = false;
    private static Thread suivi;

    /**
     * L'Atelier est-il VOLONTAIREMENT cache (ni Habbo ni l'Atelier devant) ?
     * ResterVisible, qui remontre l'appli quand macOS la masque (Option+clic
     * dans le jeu), doit alors s'abstenir. N'importe quel fil.
     */
    public static boolean masqueVoulu() { return masque; }

    /**
     * L'Atelier est affiche (connectee) ou non. Hors connexion, rien n'est
     * jamais cache (l'ecran de connexion doit rester visible).
     */
    public static synchronized void connecte(boolean c) {
        connecte = c;
        if (!c) { appliquer(false); return; }
        if (suivi != null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac") && !WIN) return;
        suivi = new Thread(() -> {
            while (true) {
                try { tour(); } catch (Throwable ignored) { }
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
        }, "atelier-premier-plan");
        suivi.setDaemon(true);
        suivi.start();
    }

    /**
     * Faut-il montrer l'Atelier ? (logique pure)
     * null = on ne sait pas (premier plan illisible) : ne rien changer.
     */
    static Boolean doitMontrer(boolean connecte, String appDevant, boolean nousDevant) {
        if (!connecte || nousDevant) return true;
        if (appDevant == null || appDevant.isBlank()) return null;
        return RaccourcisGlobaux.estHabbo(appDevant);
    }

    private static void tour() {
        if (WIN) { tourWindows(); return; }
        if (!Mac.charger()) return;
        Mac.Devant d = Mac.devant();
        Boolean montrer = doitMontrer(connecte, d == null ? null : d.nom, d != null && d.nous);
        if (montrer == null) return;
        if (montrer == !masque) {
            // Deja dans le bon etat. Cache : on verifie que rien ne l'a reaffiche.
            if (masque) Platform.runLater(() -> { if (masque && !Mac.cache()) Mac.cacher(); });
            return;
        }
        appliquer(!montrer);
    }

    private static void appliquer(boolean cacher) {
        if (cacher == masque) return;
        masque = cacher;
        try {
            Platform.runLater(() -> {
                if (WIN) { if (masque) Win.cacher(); else Win.montrer(); return; }
                if (!Mac.charger()) return;
                if (masque) Mac.cacher(); else Mac.montrer();
            });
        } catch (IllegalStateException e) {
            masque = !cacher;      // JavaFX pas encore lance : au tour suivant
        }
    }

    // ============================================================== Windows

    private static boolean winSignale = false;

    private static void tourWindows() {
        if (!WindowsFenetres.charger()) return;
        if (!winSignale) {
            winSignale = true;
            Journal.debug("premier plan : suivi actif (Windows, pid " + ProcessHandle.current().pid() + ").");
        }
        WindowsFenetres.Devant d = WindowsFenetres.devant();
        String nom = d == null ? null : WindowsFenetres.nomDevant(d.exe, d.titre, d.classe);
        Boolean montrer = doitMontrer(connecte, nom, d != null && d.nous);
        if (montrer == null) return;
        if (montrer == !masque) {
            // Deja cache : une fenetre affichee entre-temps (nouvelle Stage) est cachee a son tour.
            if (masque && !WindowsFenetres.notresVisibles().isEmpty())
                Platform.runLater(() -> { if (masque) Win.cacher(); });
            return;
        }
        appliquer(!montrer);
    }

    /** Cacher / reafficher toutes nos fenetres (Win32). Fil JavaFX seulement. */
    private static final class Win {
        /** Ce que NOUS avons cache, a reafficher (et rien d'autre). */
        private static final java.util.Set<Long> caches = new java.util.LinkedHashSet<>();

        static void cacher() {
            caches.addAll(WindowsFenetres.cacher(WindowsFenetres.notresVisibles()));
        }

        static void montrer() {
            // SW_SHOWNA ne touche pas a l'ordre Z : chacune revient a sa place.
            List<Long> l = new ArrayList<>(caches);
            caches.clear();
            WindowsFenetres.montrer(l);
            ordonnerPlusTard();
        }
    }

    // ================================================================ macOS

    /** NSWorkspace / NSApplication par le runtime Objective-C (JNA). */
    private static final class Mac {

        interface ObjC extends Library {
            Pointer objc_getClass(String nom);
            Pointer sel_registerName(String nom);
            Pointer objc_msgSend(Pointer r, Pointer s);
            Pointer objc_msgSend(Pointer r, Pointer s, Pointer a);
            Pointer objc_autoreleasePoolPush();
            void objc_autoreleasePoolPop(Pointer pool);
        }
        /** Meme fonction, lue comme un entier (pid, BOOL). */
        interface ObjCEntier extends Library {
            long objc_msgSend(Pointer r, Pointer s);
        }

        static final class Devant { String nom; boolean nous; }

        private static ObjC objc;
        private static ObjCEntier objcE;
        private static Pointer cWorkspace, cApp, cRunning;
        private static Pointer sShared, sFront, sNom, sUtf8, sPid, sSharedApp, sCurrent,
                sHide, sUnhide, sIsHidden;
        private static long notrePid = -1;
        private static String notreNom = null;
        private static boolean tente = false;

        static synchronized boolean charger() {
            if (tente) return objc != null;
            tente = true;
            try {
                objc = Native.load("objc", ObjC.class);
                objcE = Native.load("objc", ObjCEntier.class);
                NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit");
                cWorkspace = objc.objc_getClass("NSWorkspace");
                cApp = objc.objc_getClass("NSApplication");
                cRunning = objc.objc_getClass("NSRunningApplication");
                sShared = objc.sel_registerName("sharedWorkspace");
                sFront = objc.sel_registerName("frontmostApplication");
                sNom = objc.sel_registerName("localizedName");
                sUtf8 = objc.sel_registerName("UTF8String");
                sPid = objc.sel_registerName("processIdentifier");
                sSharedApp = objc.sel_registerName("sharedApplication");
                sCurrent = objc.sel_registerName("currentApplication");
                sHide = objc.sel_registerName("hide:");
                sUnhide = objc.sel_registerName("unhideWithoutActivation");
                sIsHidden = objc.sel_registerName("isHidden");
                if (cWorkspace == null || cApp == null) throw new IllegalStateException("AppKit introuvable");
                notrePid = ProcessHandle.current().pid();
                Pointer pool = objc.objc_autoreleasePoolPush();
                try { notreNom = nom(objc.objc_msgSend(cRunning, sCurrent)); }
                finally { objc.objc_autoreleasePoolPop(pool); }
                Journal.debug("premier plan : suivi actif (" + notreNom + ", pid " + notrePid + ").");
            } catch (Throwable t) {
                objc = null;
                System.err.println("[Atelier] premier plan illisible : " + t);
            }
            return objc != null;
        }

        private static String nom(Pointer app) {
            if (app == null) return null;
            Pointer n = objc.objc_msgSend(app, sNom);
            Pointer c = n == null ? null : objc.objc_msgSend(n, sUtf8);
            return c == null ? null : c.getString(0, "UTF-8");
        }

        /** L'appli au premier plan, ou null. N'importe quel fil. */
        static Devant devant() {
            Pointer pool = objc.objc_autoreleasePoolPush();
            try {
                Pointer ws = objc.objc_msgSend(cWorkspace, sShared);
                Pointer app = ws == null ? null : objc.objc_msgSend(ws, sFront);
                if (app == null) return null;
                Devant d = new Devant();
                d.nom = nom(app);
                long pid = objcE.objc_msgSend(app, sPid) & 0xFFFFFFFFL;
                d.nous = pid == notrePid || (pid <= 0 && notreNom != null && notreNom.equals(d.nom));
                return d;
            } finally {
                objc.objc_autoreleasePoolPop(pool);
            }
        }

        private static Pointer nsApp() { return objc.objc_msgSend(cApp, sSharedApp); }

        /** Fil JavaFX seulement. */
        static void cacher() {
            try { Pointer a = nsApp(); if (a != null) objc.objc_msgSend(a, sHide, null); }
            catch (Throwable t) { System.err.println("[Atelier] masquage impossible : " + t); }
        }

        /** Fil JavaFX seulement. Sans activer l'Atelier : Habbo garde le focus. */
        static void montrer() {
            try { Pointer a = nsApp(); if (a != null) objc.objc_msgSend(a, sUnhide); }
            catch (Throwable t) { System.err.println("[Atelier] réaffichage impossible : " + t); }
        }

        /** Fil JavaFX seulement. */
        static boolean cache() {
            try { Pointer a = nsApp(); return a != null && (objcE.objc_msgSend(a, sIsHidden) & 0xFF) != 0; }
            catch (Throwable t) { return true; }
        }
    }
}

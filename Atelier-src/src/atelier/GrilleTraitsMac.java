package atelier;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * Le natif macOS de la grille en traits (GrilleTraits), en JNA, sans AWT :
 *
 *   - trouver le NSWindow d'une Stage JavaFX et lui dire d'IGNORER la souris
 *     (setIgnoresMouseEvents:YES) : les clics traversent le calque et vont au jeu ;
 *   - le poser juste au-dessus des fenetres normales (niveau 1), donc sous la
 *     barre et la fenetre de l'Atelier (niveau « flottant » = 3) ;
 *   - lire la position de la souris a l'ecran (CGEventCreate + CGEventGetLocation)
 *     et le temps ecoule depuis le dernier clic gauche.
 *
 * Le NSWindow se trouve par le TITRE de la Stage, en parcourant [NSApp windows] :
 * cela marche sans aucune option de la JVM (le paquet com.sun.glass.ui n'est
 * pas ouvert quand on lance « java -jar Atelier.jar »). Teste sur ce Mac
 * (Zulu 17 FX, arm64) : trouve, ignoresMouseEvents relu a YES, et conserve
 * apres deplacement, redimension, hide/show. Window.getNativeWindow() (qui
 * exige --add-exports) ne sert qu'en secours.
 *
 * Tout ce qui touche a AppKit doit tourner sur le fil JavaFX (c'est le fil
 * principal de Cocoa sur macOS). La souris peut se lire de n'importe quel fil.
 * Rien ici ne leve d'exception : en cas d'echec, on renvoie false / null et
 * probleme() dit pourquoi.
 */
final class GrilleTraitsMac {

    private GrilleTraitsMac() { }

    interface ObjC extends Library {
        Pointer objc_getClass(String nom);
        Pointer sel_registerName(String nom);
        Pointer objc_msgSend(Pointer r, Pointer s);
        Pointer objc_msgSend(Pointer r, Pointer s, long a);
        Pointer objc_msgSend(Pointer r, Pointer s, Pointer a);
        Pointer objc_autoreleasePoolPush();
        void objc_autoreleasePoolPop(Pointer pool);
    }
    /** Memes fonctions, avec un BOOL ou un entier en retour (Java ne surcharge pas sur le retour). */
    interface ObjCOctet extends Library { byte objc_msgSend(Pointer r, Pointer s); byte objc_msgSend(Pointer r, Pointer s, Pointer a); }
    interface ObjCLong extends Library { long objc_msgSend(Pointer r, Pointer s); }

    public static class CGPoint extends Structure implements Structure.ByValue {
        public double x, y;
        @Override protected java.util.List<String> getFieldOrder() { return java.util.List.of("x", "y"); }
    }
    interface CG extends Library {
        Pointer CGEventCreate(Pointer source);
        CGPoint CGEventGetLocation(Pointer event);
        double CGEventSourceSecondsSinceLastEventType(int etat, int type);
    }
    interface CF extends Library { void CFRelease(Pointer p); }

    private static ObjC objc; private static ObjCOctet objcB; private static ObjCLong objcL;
    private static CG cg; private static CF cf;
    private static boolean tenteObjc = false, tenteCg = false;
    private static String probleme = null;
    /** Comment le NSWindow a ete trouve la derniere fois : « glass » ou « titre ». */
    static volatile String voie = null;

    static String probleme() { return probleme; }

    static boolean mac() { return System.getProperty("os.name", "").toLowerCase().contains("mac"); }

    private static synchronized boolean chargerObjc() {
        if (tenteObjc) return objc != null;
        tenteObjc = true;
        if (!mac()) { probleme = "calque transparent aux clics : macOS seulement"; return false; }
        try {
            objc = Native.load("objc", ObjC.class);
            objcB = Native.load("objc", ObjCOctet.class);
            objcL = Native.load("objc", ObjCLong.class);
            NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit");
            if (objc.objc_getClass("NSWindow") == null) throw new IllegalStateException("NSWindow introuvable");
        } catch (Throwable t) {
            objc = null;
            probleme = "runtime Objective-C indisponible (" + t + ")";
        }
        return objc != null;
    }

    private static synchronized boolean chargerCg() {
        if (tenteCg) return cg != null;
        tenteCg = true;
        if (!mac()) return false;
        try {
            cg = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", CG.class);
            cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CF.class);
            souris();   // appel a blanc : une erreur de bibliotheque tombe ici
        } catch (Throwable t) {
            cg = null;
            probleme = "position de la souris illisible (" + t + ")";
        }
        return cg != null;
    }

    private static Pointer sel(String s) { return objc.sel_registerName(s); }

    // ------------------------------------------------------------ fenetre

    /**
     * Le NSWindow de la Stage dont le titre est donne (la Stage doit etre
     * affichee). Fil JavaFX. null si introuvable.
     */
    static Pointer nsWindow(String titre) {
        if (!chargerObjc()) return null;
        Pointer w = parTitre(titre);
        if (w != null) { voie = "titre"; return w; }
        w = parGlass(titre);
        if (w != null) voie = "glass";
        return w;
    }

    /** Par com.sun.glass.ui.Window (exige --add-exports, sinon IllegalAccess : on se tait). */
    private static Pointer parGlass(String titre) {
        try {
            Class<?> c = Class.forName("com.sun.glass.ui.Window");
            java.util.List<?> l = (java.util.List<?>) c.getMethod("getWindows").invoke(null);
            for (Object o : l) {
                Object t = c.getMethod("getTitle").invoke(o);
                if (!titre.equals(t)) continue;
                long p = (Long) c.getMethod("getNativeWindow").invoke(o);
                if (p == 0) continue;
                Pointer w = new Pointer(p);
                if (estNSWindow(w)) return w;
                // Selon la version, c'est l'objet GlassWindow : on demande son NSWindow.
                Pointer n = objc.objc_msgSend(w, sel("window"));
                if (n != null && estNSWindow(n)) return n;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean estNSWindow(Pointer p) {
        try { return objcB.objc_msgSend(p, sel("isKindOfClass:"), objc.objc_getClass("NSWindow")) != 0; }
        catch (Throwable t) { return false; }
    }

    /** Par [NSApp windows] et le titre de chaque fenetre. */
    private static Pointer parTitre(String titre) {
        Pointer pool = objc.objc_autoreleasePoolPush();
        try {
            Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), sel("sharedApplication"));
            if (app == null) return null;
            Pointer fenetres = objc.objc_msgSend(app, sel("windows"));
            if (fenetres == null) return null;
            long n = objcL.objc_msgSend(fenetres, sel("count"));
            for (long i = 0; i < n; i++) {
                Pointer w = objc.objc_msgSend(fenetres, sel("objectAtIndex:"), i);
                if (w == null) continue;
                Pointer t = objc.objc_msgSend(w, sel("title"));
                if (t == null) continue;
                Pointer c = objc.objc_msgSend(t, sel("UTF8String"));
                if (c != null && titre.equals(c.getString(0, "UTF-8"))) return w;
            }
            return null;
        } catch (Throwable t) {
            probleme = "fenetres de l'appli illisibles (" + t + ")";
            return null;
        } finally {
            objc.objc_autoreleasePoolPop(pool);
        }
    }

    /**
     * Les clics traversent la fenetre. Fil JavaFX. Renvoie la valeur relue de
     * ignoresMouseEvents (true = reussi).
     */
    static boolean traversante(Pointer w) {
        if (w == null || !chargerObjc()) return false;
        try {
            objc.objc_msgSend(w, sel("setIgnoresMouseEvents:"), 1L);
            return ignoreLaSouris(w);
        } catch (Throwable t) {
            probleme = "setIgnoresMouseEvents a echoue (" + t + ")";
            return false;
        }
    }

    static boolean ignoreLaSouris(Pointer w) {
        try { return w != null && chargerObjc() && objcB.objc_msgSend(w, sel("ignoresMouseEvents")) != 0; }
        catch (Throwable t) { return false; }
    }

    /** Niveau de la fenetre (0 = normal, 3 = flottant). Fil JavaFX. */
    static boolean niveau(Pointer w, long niveau) {
        if (w == null || !chargerObjc()) return false;
        try {
            objc.objc_msgSend(w, sel("setLevel:"), niveau);
            return objcL.objc_msgSend(w, sel("level")) == niveau;
        } catch (Throwable t) { return false; }
    }

    /**
     * Le NSWindow d'une Stage AFFICHEE, meme si plusieurs fenetres portent le
     * meme titre (toutes les barres s'appellent « Atelier ») : on lui donne un
     * titre unique le temps de la chercher, puis on remet le sien. Glass
     * transmet le titre tout de suite (teste : trouve au premier coup).
     * Fil JavaFX. null si introuvable.
     */
    static Pointer nsWindow(javafx.stage.Stage s) {
        if (s == null || !s.isShowing() || s.titleProperty().isBound() || !chargerObjc()) return null;
        String ancien = s.getTitle();
        String unique = "atelier-niveau-" + System.identityHashCode(s) + "-" + System.nanoTime();
        try {
            s.setTitle(unique);
            return parTitre(unique);
        } catch (Throwable t) {
            return null;
        } finally {
            s.setTitle(ancien);
        }
    }

    /** Niveau actuel du NSWindow, ou Long.MIN_VALUE si illisible. Fil JavaFX. */
    static long niveauLu(Pointer w) {
        if (w == null || !chargerObjc()) return Long.MIN_VALUE;
        try { return objcL.objc_msgSend(w, sel("level")); }
        catch (Throwable t) { return Long.MIN_VALUE; }
    }

    /**
     * Monte au niveau donne les bulles et menus contextuels visibles (fenetres
     * JavaFX sans titre, classe GlassWindow_Panel), restes plus bas : Glass les
     * pose au niveau flottant (3), donc SOUS une fenetre de niveau superieur.
     * Fil JavaFX. Renvoie le nombre de fenetres montees.
     */
    static int monterPanneaux(long niveau) {
        if (!chargerObjc()) return 0;
        int n = 0;
        Pointer pool = objc.objc_autoreleasePoolPush();
        try {
            Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), sel("sharedApplication"));
            Pointer fenetres = app == null ? null : objc.objc_msgSend(app, sel("windows"));
            if (fenetres == null) return 0;
            long total = objcL.objc_msgSend(fenetres, sel("count"));
            for (long i = 0; i < total; i++) {
                Pointer w = objc.objc_msgSend(fenetres, sel("objectAtIndex:"), i);
                if (w == null || objcB.objc_msgSend(w, sel("isVisible")) == 0) continue;
                Pointer nc = objc.objc_msgSend(w, sel("className"));
                Pointer c = nc == null ? null : objc.objc_msgSend(nc, sel("UTF8String"));
                if (c == null || !c.getString(0, "UTF-8").startsWith("GlassWindow_Panel")) continue;
                long l = objcL.objc_msgSend(w, sel("level"));
                if (l >= 0 && l < niveau) { objc.objc_msgSend(w, sel("setLevel:"), niveau); n++; }
            }
        } catch (Throwable t) {
            probleme = "bulles illisibles (" + t + ")";
        } finally {
            objc.objc_autoreleasePoolPop(pool);
        }
        return n;
    }

    /** Ne jamais devenir la fenetre active ni apparaitre dans Exposé. Fil JavaFX. */
    static void discrete(Pointer w) {
        if (w == null || !chargerObjc()) return;
        try {
            // NSWindowCollectionBehaviorTransient (1<<3) | IgnoresCycle (1<<6) | FullScreenAuxiliary (1<<8)
            objc.objc_msgSend(w, sel("setCollectionBehavior:"), (1L << 3) | (1L << 6) | (1L << 8));
            objc.objc_msgSend(w, sel("setHasShadow:"), 0L);
        } catch (Throwable ignored) { }
    }

    /** Nom de l'appli au premier plan (NSWorkspace), ou null. Fil JavaFX conseille. */
    static String appliDevant() {
        if (!chargerObjc()) return null;
        Pointer pool = objc.objc_autoreleasePoolPush();
        try {
            Pointer ws = objc.objc_msgSend(objc.objc_getClass("NSWorkspace"), sel("sharedWorkspace"));
            Pointer a = ws == null ? null : objc.objc_msgSend(ws, sel("frontmostApplication"));
            Pointer n = a == null ? null : objc.objc_msgSend(a, sel("localizedName"));
            Pointer c = n == null ? null : objc.objc_msgSend(n, sel("UTF8String"));
            return c == null ? null : c.getString(0, "UTF-8");
        } catch (Throwable t) { return null; }
        finally { objc.objc_autoreleasePoolPop(pool); }
    }

    /** Nom de notre propre appli (NSRunningApplication), ou null. */
    static String notreAppli() {
        if (!chargerObjc()) return null;
        Pointer pool = objc.objc_autoreleasePoolPush();
        try {
            Pointer a = objc.objc_msgSend(objc.objc_getClass("NSRunningApplication"), sel("currentApplication"));
            Pointer n = a == null ? null : objc.objc_msgSend(a, sel("localizedName"));
            Pointer c = n == null ? null : objc.objc_msgSend(n, sel("UTF8String"));
            return c == null ? null : c.getString(0, "UTF-8");
        } catch (Throwable t) { return null; }
        finally { objc.objc_autoreleasePoolPop(pool); }
    }

    // ------------------------------------------------------------ souris

    /** Position de la souris a l'ecran, en points, origine en haut a gauche ; null si illisible. */
    static double[] souris() {
        if (cg == null && !chargerCg()) return null;
        Pointer e = null;
        try {
            e = cg.CGEventCreate(null);
            if (e == null) return null;
            CGPoint p = cg.CGEventGetLocation(e);
            return new double[]{p.x, p.y};
        } catch (Throwable t) {
            return null;
        } finally {
            if (e != null) try { cf.CFRelease(e); } catch (Throwable ignored) { }
        }
    }

    /** Secondes depuis le dernier bouton gauche enfonce (n'importe quelle appli) ; -1 si illisible. */
    static double depuisDernierClic() {
        if (cg == null && !chargerCg()) return -1;
        try { return cg.CGEventSourceSecondsSinceLastEventType(0, 1); }   // session combinee, kCGEventLeftMouseDown
        catch (Throwable t) { return -1; }
    }
}

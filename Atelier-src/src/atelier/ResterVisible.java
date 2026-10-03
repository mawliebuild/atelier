package atelier;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import javafx.application.Platform;

/**
 * Empeche macOS de masquer l'Atelier.
 *
 * Sur Mac, Option + clic dans la fenetre d'une AUTRE appli masque l'appli
 * qu'on quitte (raccourci systeme). Or l'Atelier se sert justement de
 * Option + clic dans le jeu : si l'Atelier avait le focus, macOS le masquait,
 * fenetres et barres comprises.
 *
 * Toutes les 300 ms, on demande a NSApplication si l'appli est masquee ; si
 * oui ET que le jeu est au premier plan, unhideWithoutActivation la remontre
 * SANS lui donner le focus (le jeu le garde). Masquee pendant qu'une autre
 * appli est devant (BarrePremierPlan le fait expres), elle le reste.
 * Les appels AppKit passent par le fil JavaFX, qui est le fil principal de l'appli sur macOS. Hors macOS, ou si le chargement echoue :
 * rien ne se passe.
 */
final class ResterVisible {

    private ResterVisible() { }

    private interface ObjC extends Library {
        Pointer objc_getClass(String nom);
        Pointer sel_registerName(String nom);
        Pointer objc_msgSend(Pointer recepteur, Pointer selecteur);
    }

    /** Meme fonction, lue comme un BOOL (un octet). */
    private interface ObjCBool extends Library {
        byte objc_msgSend(Pointer recepteur, Pointer selecteur);
    }

    private static ObjC objc;
    private static ObjCBool objcBool;
    private static Pointer cApp, sShared, sMasquee, sRemontrer;
    private static volatile boolean demarre = false;

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        try {
            objc = Native.load("objc", ObjC.class);
            objcBool = Native.load("objc", ObjCBool.class);
            NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit");
            cApp = objc.objc_getClass("NSApplication");
            sShared = objc.sel_registerName("sharedApplication");
            sMasquee = objc.sel_registerName("isHidden");
            sRemontrer = objc.sel_registerName("unhideWithoutActivation");
            if (cApp == null) throw new IllegalStateException("NSApplication introuvable");
        } catch (Throwable t) {
            System.err.println("[Atelier] Rester visible indisponible : " + t);
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                Platform.runLater(ResterVisible::verifier);
            }
        }, "atelier-rester-visible");
        t.setDaemon(true);
        t.start();
    }

    /** Sur le fil JavaFX (fil principal macOS). */
    private static void verifier() {
        try {
            Pointer app = objc.objc_msgSend(cApp, sShared);
            if (app == null) return;
            if (objcBool.objc_msgSend(app, sMasquee) == 0) return;
            // Seulement si c'est le JEU qui est devant (cas d'Option + clic dans Habbo).
            // Si une autre appli est devant, l'Atelier a ete masque expres
            // (BarrePremierPlan : on navigue ailleurs) : il le reste.
            if (!RaccourcisGlobaux.estHabbo(RaccourcisGlobaux.Devant.app())) return;
            if (BarrePremierPlan.masqueVoulu()) return;
            objc.objc_msgSend(app, sRemontrer);
            System.out.println("[Atelier] macOS avait masqué l'Atelier (Option + clic) : remis à l'écran.");
        } catch (Throwable ignored) { }
    }
}

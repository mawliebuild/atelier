package atelier;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Sait si la touche Option (Alt sous Windows), Tab ou Echap est enfoncee, a
 * l'instant ou on le demande. Sous Windows : GetAsyncKeyState (WindowsClavier) ;
 * AltGr (Ctrl gauche + Alt droit sur un clavier francais) ne compte pas comme Alt.
 *
 * Le modificateur n'apparait PAS dans les paquets Habbo : depuis le proxy, un
 * Option+clic et un clic simple sont identiques. Il faut donc interroger le
 * systeme. On appelle CGEventSourceFlagsState de CoreGraphics, qui renvoie les
 * modificateurs courants — sans demander l'autorisation d'accessibilite, contrairement
 * a un CGEventTap.
 *
 * Necessite un JNA avec bibliotheque native arm64 : celui livre avec la connexion de l'Atelier
 * (5.4.0) n'a que i386 et x86_64, d'ou l'UnsatisfiedLinkError observe. Les
 * dependances de l'Atelier embarquent desormais JNA 5.19.1, qui a darwin-aarch64.
 *
 * Si le chargement echoue, enfoncee() renvoie toujours false et l'appelant
 * retombe sur son comportement de repli : rien ne casse.
 */
public final class ToucheOption {

    private interface CoreGraphics extends Library {
        /** CGEventFlags CGEventSourceFlagsState(CGEventSourceStateID stateID) */
        long CGEventSourceFlagsState(int stateID);
        /** bool CGEventSourceKeyState(CGEventSourceStateID, CGKeyCode) */
        byte CGEventSourceKeyState(int stateID, short touche);   // bool C : un octet
    }

    /** kVK_Tab : code PHYSIQUE, le meme en AZERTY et en QWERTY. */
    private static final short MAC_TAB = 48;
    /** Windows : GetAsyncKeyState par WindowsClavier (Alt tient le role d'Option, AltGr exclu). */
    private static boolean windows = false;

    private static final int ETAT_SESSION_COMBINEE = 0;      // kCGEventSourceStateCombinedSessionState
    private static final long MASQUE_ALT           = 0x00080000L;  // kCGEventFlagMaskAlternate

    private static CoreGraphics cg;
    private static boolean tente = false;
    private static String probleme = null;

    private ToucheOption() { }

    /** true si Option est enfoncee maintenant. false si indisponible. */
    public static boolean enfoncee() {
        charger();
        try {
            if (cg != null)
                return (cg.CGEventSourceFlagsState(ETAT_SESSION_COMBINEE) & MASQUE_ALT) != 0;
            if (windows) return WindowsClavier.alt();
        } catch (Throwable ignored) { }
        return false;
    }

    /** kVK_Escape : code physique de la touche Echap. */
    private static final short MAC_ECHAP = 53;

    /** true si la touche Echap est enfoncee maintenant (lecture seule : rien n'est vole). */
    public static boolean echap() {
        charger();
        try {
            if (cg != null) return cg.CGEventSourceKeyState(ETAT_SESSION_COMBINEE, MAC_ECHAP) != 0;
            if (windows) return WindowsClavier.enfoncee(WindowsClavier.VK_ESCAPE);
        } catch (Throwable ignored) { }
        return false;
    }

    /** true si la touche Tab est enfoncee maintenant. false si indisponible. */
    public static boolean tab() {
        charger();
        try {
            if (cg != null) return cg.CGEventSourceKeyState(ETAT_SESSION_COMBINEE, MAC_TAB) != 0;
            if (windows) return WindowsClavier.enfoncee(WindowsClavier.VK_TAB);
        } catch (Throwable ignored) { }
        return false;
    }

    /** true si la detection fonctionne sur cette machine. */
    public static boolean disponible() {
        charger();
        return cg != null || windows;
    }

    /** Raison de l'indisponibilite, ou null. */
    public static String probleme() {
        charger();
        return probleme;
    }

    private static synchronized CoreGraphics charger() {
        if (tente) return cg;
        tente = true;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            windows = WindowsClavier.disponible();
            if (windows) Journal.debug("detection de la touche Alt active (Windows).");
            else {
                probleme = String.valueOf(WindowsClavier.probleme());
                System.err.println("[Atelier] detection Alt indisponible : " + probleme);
            }
            return null;
        }
        if (!os.contains("mac")) {
            probleme = "detection Option/Alt non prise en charge sur " + os;
            return null;
        }
        try {
            cg = Native.load(
                    "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                    CoreGraphics.class);
            // Un appel a blanc : si la bibliotheque native manque pour cette
            // architecture, l'erreur tombe ici et pas au premier clic.
            cg.CGEventSourceFlagsState(ETAT_SESSION_COMBINEE);
            Journal.debug("detection de la touche Option active.");
        } catch (Throwable t) {
            cg = null;
            probleme = String.valueOf(t);
            System.err.println("[Atelier] detection Option indisponible : " + t);
        }
        return cg;
    }
}

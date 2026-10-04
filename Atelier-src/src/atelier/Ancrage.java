package atelier;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import javafx.application.Platform;

/**
 * Garde la barre et la fenetre de l'Atelier posees sur la fenetre Habbo.
 *
 * macOS expose la position et la taille des fenetres par CoreGraphics. Les
 * TITRES sont masques sans l'autorisation « Enregistrement de l'ecran », mais
 * le NOM DE L'APPLICATION reste lisible sans rien autoriser — et Habbo en est
 * une, pas un onglet de navigateur. C'est donc sur ce nom qu'on la reconnait.
 *
 * Sous Windows (WindowsFenetres.cadreHabbo) : la plus grande fenetre visible
 * dont l'executable est « Habbo.exe », bords visibles lus par DWM (sans les
 * bords invisibles de Windows 10/11), convertis en coordonnees JavaFX
 * (mise a l'echelle de l'ecran ou elle se trouve).
 *
 * L'Atelier se pose PAR-DESSUS le jeu : la fenetre du jeu n'est jamais
 * redimensionnee.
 */
public final class Ancrage {

    private interface CG extends Library {
        Pointer CGWindowListCopyWindowInfo(int option, int relativeTo);
    }
    private interface CF extends Library {
        long CFArrayGetCount(Pointer a);
        Pointer CFArrayGetValueAtIndex(Pointer a, long i);
        Pointer CFDictionaryGetValue(Pointer d, Pointer k);
        Pointer CFStringCreateWithCString(Pointer alloc, String s, int enc);
        boolean CFStringGetCString(Pointer s, byte[] b, long n, int enc);
        boolean CFNumberGetValue(Pointer n, int type, double[] v);
        void CFRelease(Pointer p);
    }

    private static final int UTF8 = 0x08000100;
    private static final int DOUBLE = 13;          // kCFNumberDoubleType
    private static final int A_L_ECRAN = 1, SANS_BUREAU = 8;

    private static CG cg; private static CF cf;
    private static Pointer kNom, kCadre, kX, kY, kL, kH;
    private static boolean pret = false;

    private static volatile boolean actif = true;
    private static volatile boolean demarre = false;
    private static volatile double dernierX = -1, dernierY = -1, dernierL = -1, dernierH = -1;

    private Ancrage() { }

    /** Ce qui se place par rapport a la fenetre du jeu. */
    public interface Ancrable {
        void placer(double hx, double hy, double hl, double hh);
    }

    public static boolean actif() { return actif; }
    public static void actif(boolean a) { actif = a; dernierX = -1; }

    private static Ancrable[] panneaux = new Ancrable[0];

    /** Barre et fenetre suivent la fenetre du jeu. */
    public static synchronized void demarrer(Ancrable... p) {
        panneaux = (p == null) ? new Ancrable[0] : p;
        if (demarre) return;
        demarre = true;
        if (WindowsFenetres.windows() ? !WindowsFenetres.charger() : !charger()) {
            if (WindowsFenetres.windows()) probleme = WindowsFenetres.probleme();
            Journal.debug("ancrage indisponible : " + probleme);
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try { if (actif) suivre(); } catch (Throwable ignored) { }
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
            }
        }, "atelier-ancrage");
        t.setDaemon(true);
        t.start();
        Journal.debug("ancrage à la fenêtre Habbo actif.");
    }

    private static String probleme = null;
    /** Reutilise a chaque tour : seul le fil de l'ancrage s'en sert. */
    private static final byte[] tampon = new byte[128];

    private static synchronized boolean charger() {
        if (pret) return true;
        try {
            cg = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", CG.class);
            cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CF.class);
            kNom   = cf.CFStringCreateWithCString(null, "kCGWindowOwnerName", UTF8);
            kCadre = cf.CFStringCreateWithCString(null, "kCGWindowBounds", UTF8);
            kX = cf.CFStringCreateWithCString(null, "X", UTF8);
            kY = cf.CFStringCreateWithCString(null, "Y", UTF8);
            kL = cf.CFStringCreateWithCString(null, "Width", UTF8);
            kH = cf.CFStringCreateWithCString(null, "Height", UTF8);
            pret = true;
        } catch (Throwable t) {
            probleme = String.valueOf(t);
        }
        return pret;
    }

    /** Position et taille de la fenetre Habbo, ou null. */
    private static double[] fenetreHabbo() {
        Pointer liste = cg.CGWindowListCopyWindowInfo(A_L_ECRAN | SANS_BUREAU, 0);
        if (liste == null) return null;
        try {
            long n = cf.CFArrayGetCount(liste);
            double[] meilleure = null;
            for (long i = 0; i < n; i++) {
                Pointer d = cf.CFArrayGetValueAtIndex(liste, i);
                Pointer v = cf.CFDictionaryGetValue(d, kNom);
                if (v == null) continue;
                java.util.Arrays.fill(tampon, (byte) 0);
                if (!cf.CFStringGetCString(v, tampon, tampon.length, UTF8)) continue;
                String app = new String(tampon).trim().replace("\0", "");
                // « Habbo » seul : « Habbo Launcher » est une autre fenetre.
                if (!"Habbo".equals(app)) continue;

                Pointer c = cf.CFDictionaryGetValue(d, kCadre);
                if (c == null) continue;
                double[] x={0},y={0},l={0},h={0};
                cf.CFNumberGetValue(cf.CFDictionaryGetValue(c, kX), DOUBLE, x);
                cf.CFNumberGetValue(cf.CFDictionaryGetValue(c, kY), DOUBLE, y);
                cf.CFNumberGetValue(cf.CFDictionaryGetValue(c, kL), DOUBLE, l);
                cf.CFNumberGetValue(cf.CFDictionaryGetValue(c, kH), DOUBLE, h);
                if (l[0] < 400 || h[0] < 300) continue;
                // la plus grande, au cas ou il y en aurait plusieurs
                if (meilleure == null || l[0]*h[0] > meilleure[2]*meilleure[3])
                    meilleure = new double[]{x[0], y[0], l[0], h[0]};
            }
            return meilleure;
        } finally {
            try { cf.CFRelease(liste); } catch (Throwable ignored) { }
        }
    }

    private static void suivre() {
        double[] f = WindowsFenetres.windows() ? WindowsFenetres.cadreHabbo() : fenetreHabbo();
        if (f == null) return;

        // Ne rien faire tant que Habbo n'a pas bouge : sans ce garde-fou, il
        // serait impossible de deplacer la fenetre a la main.
        if (Math.abs(f[0] - dernierX) < 2 && Math.abs(f[1] - dernierY) < 2
                && Math.abs(f[2] - dernierL) < 2
                && Math.abs(f[3] - dernierH) < 2) return;
        dernierX = f[0]; dernierY = f[1]; dernierL = f[2]; dernierH = f[3];

        for (Ancrable p : panneaux)
            if (p != null) p.placer(f[0], f[1], f[2], f[3]);
    }

    /** Le dernier cadre connu de la fenetre Habbo {x, y, largeur, hauteur}, ou null. */
    public static double[] cadreHabbo() {
        return dernierL > 0 ? new double[]{dernierX, dernierY, dernierL, dernierH} : null;
    }

    /** Force un nouveau placement au prochain tour. */
    public static void recoller() { dernierX = -1; }
}

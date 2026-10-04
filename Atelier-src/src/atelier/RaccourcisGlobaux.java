package atelier;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import extension.GPresets;
import gearth.protocol.HMessage;

import javafx.application.Platform;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Annuler / Retablir au clavier PENDANT qu'on joue : Ctrl+Z / Cmd+Z annule,
 * (sous Windows : Ctrl a la place de Cmd, Alt a la place d'Option ; voir
 * raccourcisWindows et WindowsClavier, qui fait RegisterHotKey sur un fil dedie.)
 * Ctrl+Maj+Z, Cmd+Maj+Z, Ctrl+Y et Cmd+Y retablissent. En mode Construction
 * (panneau des calques actif) : Option+C (mode calque), Option+G (grille),
 * Option+Maj+C copie le calque vise, Option+Maj+V le colle (seulement quand un
 * calque est copie). Dans le panneau lui-meme, Cmd/Ctrl+C et V marchent aussi.
 *
 * Quand une fenetre de l'Atelier a le focus, c'est OutilHistorique.installerRaccourcis
 * (filtre JavaFX) qui s'en charge. Ici on couvre le cas ou l'appli Habbo est au
 * premier plan.
 *
 * Choix technique : raccourcis globaux Carbon (RegisterEventHotKey), appeles
 * directement par JNA — la meme API que jkeymaster, sans passer par
 * javax.swing.KeyStroke (qui charge la pile AWT, evitee partout dans l'Atelier).
 * Aucune autorisation macOS n'est demandee (un CGEventTap exigerait
 * Accessibilite / Surveillance de l'entree). Un raccourci Carbon AVALE la touche
 * dans toutes les applis : on ne l'enregistre donc QUE tant que :
 *   - l'appli au premier plan s'appelle « Habbo » (NSWorkspace.frontmostApplication,
 *     lu toutes les 150 ms ; aucun droit necessaire), et
 *   - le chat du jeu est vide : le client envoie StartTyping des qu'on tape
 *     dans la barre de chat, et CancelTyping quand elle redevient vide (ou
 *     Chat/Shout/Whisper a l'envoi). Pendant une saisie, Ctrl+Z reste donc au jeu.
 * Des que l'une des deux conditions tombe, les raccourcis sont desenregistres.
 *
 * Clavier AZERTY : RegisterEventHotKey prend un code de touche PHYSIQUE. Le Z
 * d'un clavier francais n'est pas a la place du Z americain (code 6 = W en
 * AZERTY). On cherche donc, dans la disposition clavier active (UCKeyTranslate),
 * la touche qui produit « z » et celle qui produit « y ».
 *
 * Tous les appels Carbon / TIS se font sur le fil JavaFX, qui est le fil
 * principal d'AppKit sous macOS.
 */
public final class RaccourcisGlobaux {

    private RaccourcisGlobaux() { }

    // ---------------------------------------------------------- logique pure

    /** Identifiants des six raccourcis (EventHotKeyID.id). */
    static final int CMD_Z = 1, CMD_MAJ_Z = 2, CMD_Y = 3, CTRL_Z = 4, CTRL_MAJ_Z = 5, CTRL_Y = 6;
    /** S seul : mode selection des calques. Enregistre seulement en mode Construction. (Nom historique : P.) */
    static final int TOUCHE_P = 7;
    /** Option + G : grille (mode Construction). */
    static final int TOUCHE_G = 12;
    private static volatile Runnable surG = () -> { };

    /** Ce que fait Option + G dans le jeu. */
    public static void surG(Runnable r) { surG = r == null ? () -> { } : r; }
    private static volatile boolean pVoulu = false;
    private static volatile Runnable surP = () -> { };

    /** Ce que fait la touche P dans le jeu. */
    public static void surP(Runnable r) { surP = r == null ? () -> { } : r; }

    /**
     * Touche P active (mode Construction) ou non. Comme les autres raccourcis,
     * elle n'est prise que si Habbo est devant et le chat vide : un message qui
     * commence par « s » ne se tape donc pas en mode Construction.
     */
    public static void toucheP(boolean on) {
        if (pVoulu == on) return;
        pVoulu = on;
        if (WIN) { Windows.appliquer(); return; }
        try {
            Platform.runLater(() -> { if (Carbon.enregistresAlors()) { Carbon.desenregistrer(); Carbon.enregistrer(); } });
        } catch (IllegalStateException ignored) { }
    }

    // ---------------------------------------------- copier / coller des calques

    /** Cmd/Ctrl+C copie le calque vise, Cmd/Ctrl+V le colle (panneau des calques). */
    static final int CMD_C = 8, CTRL_C = 9, CMD_V = 10, CTRL_V = 11;
    /** Mode Construction (panneau des calques actif) : C et V pris dans le jeu. */
    private static volatile boolean calquesVoulu = false;
    /** Une copie de calque existe : V pris dans le jeu (sinon Cmd+V reste au chat). */
    private static volatile boolean collerVoulu = false;
    private static volatile Runnable surCopier = () -> { }, surColler = () -> { };

    /** Ce que font Ctrl+C / Ctrl+V sur les calques (appele sur le fil JavaFX). */
    public static void surCalques(Runnable copier, Runnable coller) {
        surCopier = copier == null ? () -> { } : copier;
        surColler = coller == null ? () -> { } : coller;
    }

    /**
     * Panneau des calques actif (mode Construction) ou non. Comme Option + C,
     * Ctrl+C / Ctrl+V ne sont pris dans le jeu que si Habbo est devant, le chat
     * vide ET le panneau actif ; Ctrl+V seulement si un calque est copie.
     */
    public static void calques(boolean on) {
        if (calquesVoulu == on) return;
        calquesVoulu = on;
        reenregistrer();
    }

    /** Un calque est copie (Ctrl+V a quelque chose a coller). */
    public static void collagePossible(boolean on) {
        if (collerVoulu == on) return;
        collerVoulu = on;
        reenregistrer();
    }

    private static void reenregistrer() {
        if (WIN) { Windows.appliquer(); return; }
        try {
            Platform.runLater(() -> { if (Carbon.enregistresAlors()) { Carbon.desenregistrer(); Carbon.enregistrer(); } });
        } catch (IllegalStateException ignored) { }
    }

    /**
     * Pour les fenetres de l'Atelier (filtre JavaFX, OutilHistorique) :
     * Ctrl/Cmd+C ou V quand le panneau des calques est actif.
     * @return vrai si la touche a ete prise (a consommer)
     */
    public static boolean toucheCalques(boolean copier) {
        if (!calquesVoulu) return false;
        try { (copier ? surCopier : surColler).run(); } catch (Throwable t) { Journal.erreur("Le raccourci des calques a échoué", t); }
        return true;
    }

    /** 1 = copier, 2 = coller, 0 = autre. */
    static int calqueAction(int id) {
        switch (id) {
            case CMD_C: case CTRL_C: return 1;
            case CMD_V: case CTRL_V: return 2;
            default: return 0;
        }
    }

    /**
     * Logique pure : les raccourcis a enregistrer, {id, code de touche, modificateurs}.
     * z, y, s, c, v = codes physiques des touches qui produisent ces lettres.
     */
    static int[][] raccourcis(int z, int y, int s, int c, int v, int g, boolean toucheS, boolean calques, boolean coller) {
        List<int[]> r = new java.util.ArrayList<>(List.of(
                new int[]{CMD_Z, z, Carbon.CMD}, new int[]{CMD_MAJ_Z, z, Carbon.CMD | Carbon.MAJ}, new int[]{CMD_Y, y, Carbon.CMD},
                new int[]{CTRL_Z, z, Carbon.CTRL}, new int[]{CTRL_MAJ_Z, z, Carbon.CTRL | Carbon.MAJ}, new int[]{CTRL_Y, y, Carbon.CTRL}));
        // Option + lettre : jamais utile dans le chat (©, ﬁ...). Maj + C empechait
        // d'ecrire un C majuscule ; Cmd/Ctrl + C empechait de copier (capture d'ecran...).
        if (toucheS) {
            r.add(new int[]{TOUCHE_P, c, Carbon.OPTION});           // mode calque
            r.add(new int[]{TOUCHE_G, g, Carbon.OPTION});           // grille
        }
        if (calques) {
            r.add(new int[]{CMD_C, c, Carbon.OPTION | Carbon.MAJ}); // copier le calque : Option + Maj + C
            if (coller) r.add(new int[]{CMD_V, v, Carbon.OPTION | Carbon.MAJ});
        }
        return r.toArray(new int[0][]);
    }

    /**
     * Logique pure, Windows : {id, code VK, modificateurs MOD_*} a enregistrer.
     * Cmd (Mac) devient Ctrl, Option devient Alt. Pas de variante Cmd : la
     * touche Windows est reservee au systeme. z, y, c, v, g = codes VK des lettres
     * (en AZERTY aussi, VK_Z est la touche marquee Z).
     * Alt + lettre seul (jamais Ctrl + Alt : c'est AltGr sur un clavier francais,
     * qui sert a taper @, #, €...). RegisterHotKey ne declenche qu'avec exactement
     * ces modificateurs : AltGr + C ne prend donc pas Alt + C.
     */
    static int[][] raccourcisWindows(int z, int y, int c, int v, int g, boolean modeCalque, boolean calques, boolean coller) {
        int ctrl = WindowsClavier.MOD_CONTROL, alt = WindowsClavier.MOD_ALT, maj = WindowsClavier.MOD_SHIFT;
        List<int[]> r = new java.util.ArrayList<>(List.of(
                new int[]{CTRL_Z, z, ctrl}, new int[]{CTRL_MAJ_Z, z, ctrl | maj}, new int[]{CTRL_Y, y, ctrl}));
        if (modeCalque) {
            r.add(new int[]{TOUCHE_P, c, alt});                 // mode calque : Alt + C
            r.add(new int[]{TOUCHE_G, g, alt});                 // grille : Alt + G
        }
        if (calques) {
            // Ctrl + Maj (pas Alt + Maj : c'est le changement de langue du clavier sous Windows)
            r.add(new int[]{CTRL_C, c, ctrl | maj});             // copier le calque : Ctrl + Maj + C
            if (coller) r.add(new int[]{CTRL_V, v, ctrl | maj}); // coller : Ctrl + Maj + V
        }
        return r.toArray(new int[0][]);
    }

    /** -1 = annuler, +1 = retablir, 0 = inconnu. */
    static int action(int id) {
        switch (id) {
            case CMD_Z: case CTRL_Z: return -1;
            case CMD_MAJ_Z: case CMD_Y: case CTRL_MAJ_Z: case CTRL_Y: return 1;
            default: return 0;
        }
    }

    /** « Habbo » seul : « Habbo Launcher » est une autre appli (meme regle qu'Ancrage). */
    static boolean estHabbo(String app) {
        return app != null && app.trim().equalsIgnoreCase("Habbo");
    }

    /** Faut-il que les raccourcis soient enregistres ? */
    static boolean doitEcouter(String appDevant, boolean saisieChat) {
        return estHabbo(appDevant) && !saisieChat;
    }

    /**
     * Code de touche qui produit le caractere c dans la disposition donnee
     * (traduire(code) = texte produit sans modificateur, ou null). null si aucun.
     */
    static Integer codePour(char c, IntFunction<String> traduire) {
        char cible = Character.toLowerCase(c);
        for (int code = 0; code < 128; code++) {
            String s;
            try { s = traduire.apply(code); } catch (Throwable t) { s = null; }
            if (s != null && s.length() == 1 && Character.toLowerCase(s.charAt(0)) == cible) return code;
        }
        return null;
    }

    // ---------------------------------------------------------- etat

    private static volatile boolean installe = false;
    private static volatile boolean saisie = false;     // chat du jeu non vide
    private static volatile boolean voulu = false;      // dernier etat demande au fil FX
    private static volatile boolean enregistres = false;
    private static volatile String appDevant = null;
    private static volatile String probleme = null;

    /** Pour l'interface : une phrase sur l'etat des raccourcis dans le jeu. */
    public static String etat() {
        String pb = probleme != null ? probleme : WIN ? WindowsClavier.probleme() : null;
        if (pb != null) return "Raccourcis dans le jeu indisponibles : " + pb;
        if (!installe) return "Raccourcis dans le jeu pas encore actifs.";
        if (WIN ? WindowsClavier.actifs() > 0 : enregistres) return "Raccourcis actifs dans le jeu.";
        if (estHabbo(appDevant) && saisie) return "Chat en cours de saisie : Ctrl+Z laissé au jeu.";
        return "Raccourcis prêts : ils s'activent quand Habbo est au premier plan.";
    }

    /**
     * A appeler une fois au demarrage, apres le lancement de JavaFX.
     * Mac (Carbon) et Windows (RegisterHotKey) ; sans effet ailleurs (dit dans etat()).
     */
    public static synchronized void installer() {
        if (installe) return;
        installe = true;
        String os = System.getProperty("os.name", "").toLowerCase();
        WIN = os.contains("win");
        if (!os.contains("mac") && !WIN) {
            probleme = "seulement sur Mac et Windows";
            Journal.debug("raccourcis globaux : " + probleme);
            return;
        }
        Historique.demarrer();
        ecouterChat();
        Thread t = new Thread(() -> {
            while (true) {
                try { tour(); } catch (Throwable e) { /* un tour rate n'arrete rien */ }
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
        }, "atelier-raccourcis");
        t.setDaemon(true);
        t.start();
        Journal.debug("raccourcis globaux : surveillance du premier plan active.");
    }

    private static void tour() {
        if (probleme != null) return;
        if (saisie && !Salle.dansUneSalle()) saisie = false;   // hors salle : plus de chat en cours
        appDevant = Devant.app();
        boolean v = doitEcouter(appDevant, saisie);
        if (v == voulu) return;
        voulu = v;
        try {
            if (WIN) Windows.appliquer();
            else Platform.runLater(() -> {
                if (voulu) Carbon.enregistrer(); else Carbon.desenregistrer();
            });
        } catch (IllegalStateException e) {
            voulu = !v;            // JavaFX pas encore lance : on reessaiera au tour suivant
        }
    }

    // ---------------------------------------------------------- action

    private static volatile long dernier = 0;

    private static void declencher(int id) {
        if (id == TOUCHE_G) {
            long now = System.currentTimeMillis();
            if (now - dernier < 200) return;
            dernier = now;
            try { surG.run(); } catch (Throwable ignored) { }
            return;
        }
        if (id == TOUCHE_P) {
            long now = System.currentTimeMillis();
            if (now - dernier < 200) return;
            dernier = now;
            try { surP.run(); } catch (Throwable ignored) { }
            return;
        }
        int k = calqueAction(id);
        if (k != 0) {
            long now = System.currentTimeMillis();
            if (now - dernier < 200) return;
            dernier = now;
            Runnable r = k == 1 ? surCopier : surColler;
            try { Platform.runLater(() -> { try { r.run(); } catch (Throwable ignored) { } }); }
            catch (IllegalStateException ignored) { }
            return;
        }
        int a = action(id);
        if (a == 0) return;
        long now = System.currentTimeMillis();
        if (now - dernier < 120) return;       // rebond
        dernier = now;
        Salle.tache("raccourci", () -> {
            // Historique dit lui-meme son resultat (Journal) : rien a redire ici.
            if (a < 0) Historique.annuler(); else Historique.retablir();
        });
    }

    // ---------------------------------------------------------- chat du jeu

    /**
     * StartTyping = la barre de chat n'est plus vide ; CancelTyping = vide de
     * nouveau ; Chat / Shout / Whisper = message envoye (barre videe). Ecoute
     * passive, rien n'est bloque. Reessaie tant que le moteur de l'Atelier n'est pas la.
     */
    private static void ecouterChat() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900; i++) {
                GPresets gp = Salle.gp();
                if (gp != null) {
                    int ok = 0;
                    ok += brancher(gp, "StartTyping", true);
                    ok += brancher(gp, "CancelTyping", false);
                    ok += brancher(gp, "Chat", false);
                    ok += brancher(gp, "Shout", false);
                    ok += brancher(gp, "Whisper", false);
                    Journal.debug("raccourcis globaux : " + ok + "/5 paquets de chat suivis.");
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-raccourcis-chat");
        t.setDaemon(true);
        t.start();
    }

    private static int brancher(GPresets gp, String nom, boolean tape) {
        try {
            gp.intercept(HMessage.Direction.TOSERVER, nom, m -> saisie = tape);
            return 1;
        } catch (Throwable t) {
            Journal.debug("raccourcis : " + nom + " non suivi (" + t + ")");
            return 0;
        }
    }

    // ---------------------------------------------------------- premier plan

    /** Windows : raccourcis globaux par RegisterHotKey (WindowsClavier), memes ids que sous Mac. */
    private static volatile boolean WIN = false;

    static final class Windows {
        /** Envoie au fil des raccourcis Windows la liste voulue maintenant (vide si Habbo n'est pas devant). */
        static synchronized void appliquer() {
            int[][] r = voulu ? raccourcisWindows(WindowsClavier.vkPour('z'), WindowsClavier.vkPour('y'),
                    WindowsClavier.vkPour('c'), WindowsClavier.vkPour('v'), WindowsClavier.vkPour('g'),
                    pVoulu, calquesVoulu, collerVoulu) : new int[0][];
            WindowsClavier.raccourcis(r, RaccourcisGlobaux::declencher);
            enregistres = r.length > 0;
        }
    }

    static final class Devant {
        interface ObjC extends Library {
            Pointer objc_getClass(String nom);
            Pointer sel_registerName(String nom);
            Pointer objc_msgSend(Pointer recepteur, Pointer selecteur);
            Pointer objc_autoreleasePoolPush();
            void objc_autoreleasePoolPop(Pointer pool);
        }

        private static ObjC objc;
        private static Pointer cWorkspace, sShared, sFront, sNom, sUtf8;
        private static boolean tente = false;

        private static synchronized boolean charger() {
            if (tente) return objc != null;
            tente = true;
            try {
                objc = Native.load("objc", ObjC.class);
                // AppKit doit etre charge pour que la classe NSWorkspace existe
                NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit");
                cWorkspace = objc.objc_getClass("NSWorkspace");
                sShared = objc.sel_registerName("sharedWorkspace");
                sFront = objc.sel_registerName("frontmostApplication");
                sNom = objc.sel_registerName("localizedName");
                sUtf8 = objc.sel_registerName("UTF8String");
                if (cWorkspace == null) throw new IllegalStateException("NSWorkspace introuvable");
            } catch (Throwable t) {
                objc = null;
                probleme = "premier plan illisible (" + t.getMessage() + ")";
                System.err.println("[Atelier] raccourcis : " + t);
            }
            return objc != null;
        }

        /** Nom de l'appli au premier plan, ou null. */
        static String app() {
            if (WindowsClavier.WINDOWS) return WindowsClavier.appDevant();
            if (!charger()) return null;
            Pointer pool = objc.objc_autoreleasePoolPush();
            try {
                Pointer ws = objc.objc_msgSend(cWorkspace, sShared);
                if (ws == null) return null;
                Pointer app = objc.objc_msgSend(ws, sFront);
                if (app == null) return null;
                Pointer nom = objc.objc_msgSend(app, sNom);
                if (nom == null) return null;
                Pointer c = objc.objc_msgSend(nom, sUtf8);
                return c == null ? null : c.getString(0, "UTF-8");
            } finally {
                objc.objc_autoreleasePoolPop(pool);
            }
        }
    }

    /** Windows : le programme de la fenetre au premier plan, sans « .exe » (« Habbo », « java »...). */
    /** Meme lecture du premier plan que BarrePremierPlan (ignore barre des taches et Alt+Tab). */
    static String appWindows() {
        try {
            WindowsFenetres.Devant d = WindowsFenetres.devant();
            if (d != null) return WindowsFenetres.nomDevant(d.exe, d.titre, d.classe);
        } catch (Throwable t) { Journal.debug("premier plan Windows : " + t); }
        return WindowsClavier.appDevant();
    }

    // ---------------------------------------------------------- Carbon

    /** RegisterEventHotKey et compagnie. Tout ici tourne sur le fil JavaFX. */
    static final class Carbon {

        interface Lib extends Library {
            Pointer GetEventDispatcherTarget();
            int InstallEventHandler(Pointer cible, Gestionnaire g, int n, EventTypeSpec[] types,
                                    Pointer donnees, PointerByReference ref);
            int RegisterEventHotKey(int code, int modificateurs, EventHotKeyID.ByValue id,
                                    Pointer cible, int options, PointerByReference ref);
            int UnregisterEventHotKey(Pointer ref);
            int GetEventParameter(Pointer evt, int nom, int type, Pointer typeReel, int taille,
                                  IntByReference tailleReelle, EventHotKeyID sortie);
            // disposition clavier (HIToolbox)
            Pointer TISCopyCurrentKeyboardLayoutInputSource();
            Pointer TISGetInputSourceProperty(Pointer source, Pointer cle);
            byte LMGetKbdType();
            int UCKeyTranslate(Pointer disposition, short code, short action, int modificateurs,
                               int typeClavier, int options, IntByReference morte, long max,
                               LongByReference longueur, char[] texte);
        }

        interface CF extends Library {
            Pointer CFDataGetBytePtr(Pointer data);
            void CFRelease(Pointer p);
        }

        public interface Gestionnaire extends Callback {
            int callback(Pointer suivant, Pointer evenement, Pointer donnees);
        }

        public static class EventHotKeyID extends Structure {
            public int signature;
            public int id;
            @Override protected List<String> getFieldOrder() { return List.of("signature", "id"); }
            public static class ByValue extends EventHotKeyID implements Structure.ByValue { }
        }

        public static class EventTypeSpec extends Structure {
            public int eventClass;
            public int eventKind;
            @Override protected List<String> getFieldOrder() { return List.of("eventClass", "eventKind"); }
        }

        private static final String CHEMIN = "/System/Library/Frameworks/Carbon.framework/Carbon";
        static final int CMD = 256, MAJ = 512, OPTION = 2048, CTRL = 4096;
        private static final int SIGNATURE = ostype("Atlr");
        private static final int CLASSE_CLAVIER = ostype("keyb"), HOTKEY_APPUYEE = 5;
        private static final int PARAM_OBJET = ostype("----"), TYPE_HOTKEY_ID = ostype("hkid");
        private static final int ANSI_Z = 6, ANSI_Y = 16, ANSI_P = 1, ANSI_C = 8, ANSI_V = 9, ANSI_G = 5;

        private static Lib lib;
        private static CF cf;
        private static Gestionnaire gestionnaire;          // garde en vie (sinon ramasse par le GC)
        private static final Pointer[] refs = new Pointer[16];
        static boolean enregistresAlors() { return enregistres; }

        static int ostype(String s) {
            return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
        }

        private static boolean charger() {
            if (lib != null) return true;
            if (probleme != null) return false;
            try {
                lib = Native.load(CHEMIN, Lib.class);
                cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CF.class);
                gestionnaire = (suivant, evt, donnees) -> {
                    try {
                        EventHotKeyID id = new EventHotKeyID();
                        int err = lib.GetEventParameter(evt, PARAM_OBJET, TYPE_HOTKEY_ID, null,
                                id.size(), null, id);
                        if (err == 0) { id.read(); if (id.signature == SIGNATURE) declencher(id.id); }
                    } catch (Throwable ignored) { }
                    return 0;
                };
                EventTypeSpec[] types = (EventTypeSpec[]) new EventTypeSpec().toArray(1);
                types[0].eventClass = CLASSE_CLAVIER;
                types[0].eventKind = HOTKEY_APPUYEE;
                PointerByReference ref = new PointerByReference();
                int err = lib.InstallEventHandler(lib.GetEventDispatcherTarget(), gestionnaire, 1, types, null, ref);
                if (err != 0) throw new IllegalStateException("InstallEventHandler = " + err);
                return true;
            } catch (Throwable t) {
                lib = null;
                probleme = "raccourcis Carbon indisponibles (" + t.getMessage() + ")";
                System.err.println("[Atelier] raccourcis : " + t);
                return false;
            }
        }

        /** Texte produit par une touche, sans modificateur, dans la disposition active. */
        private static IntFunction<String> disposition() {
            Pointer source = lib.TISCopyCurrentKeyboardLayoutInputSource();
            if (source == null) return null;
            Pointer cle = NativeLibrary.getInstance(CHEMIN)
                    .getGlobalVariableAddress("kTISPropertyUnicodeKeyLayoutData").getPointer(0);
            Pointer data = lib.TISGetInputSourceProperty(source, cle);
            if (data == null) { cf.CFRelease(source); return null; }
            Pointer uchr = cf.CFDataGetBytePtr(data);
            int type = lib.LMGetKbdType() & 0xff;
            String[] table = new String[128];
            for (int code = 0; code < 128; code++) {
                char[] buf = new char[4];
                IntByReference morte = new IntByReference(0);
                LongByReference n = new LongByReference(0);
                int err = lib.UCKeyTranslate(uchr, (short) code, (short) 3 /* kUCKeyActionDisplay */,
                        0, type, 1 /* kUCKeyTranslateNoDeadKeysMask */, morte, buf.length, n, buf);
                if (err == 0 && n.getValue() > 0) table[code] = new String(buf, 0, (int) n.getValue());
            }
            cf.CFRelease(source);
            return code -> table[code];
        }

        static void enregistrer() {
            if (!voulu || enregistres || !charger()) return;
            int z = ANSI_Z, y = ANSI_Y, p = ANSI_P, c = ANSI_C, v = ANSI_V, g = ANSI_G;
            try {
                IntFunction<String> d = disposition();
                if (d != null) {
                    Integer cz = codePour('z', d), cy = codePour('y', d), cp = codePour('s', d);
                    Integer cc = codePour('c', d), cv = codePour('v', d), cg = codePour('g', d);
                    if (cg != null) g = cg;
                    if (cz != null) z = cz;
                    if (cy != null) y = cy;
                    if (cp != null) p = cp;
                    if (cc != null) c = cc;
                    if (cv != null) v = cv;
                }
            } catch (Throwable t) {
                Journal.debug("raccourcis : disposition clavier illisible, QWERTY suppose (" + t + ")");
            }
            int[][] r = raccourcis(z, y, p, c, v, g, pVoulu, calquesVoulu, collerVoulu);
            int ok = 0;
            for (int[] k : r) {
                EventHotKeyID.ByValue id = new EventHotKeyID.ByValue();
                id.signature = SIGNATURE;
                id.id = k[0];
                PointerByReference ref = new PointerByReference();
                int err = lib.RegisterEventHotKey(k[1], k[2], id, lib.GetEventDispatcherTarget(), 0, ref);
                if (err == 0) { refs[k[0]] = ref.getValue(); ok++; }
                else System.err.println("[Atelier] raccourci " + k[0] + " refusé (" + err + ") : déjà pris ?");
            }
            enregistres = ok > 0;
        }

        static void desenregistrer() {
            if (lib == null) { enregistres = false; return; }
            for (int i = 0; i < refs.length; i++) {
                if (refs[i] == null) continue;
                try { lib.UnregisterEventHotKey(refs[i]); } catch (Throwable ignored) { }
                refs[i] = null;
            }
            enregistres = false;
        }
    }
}

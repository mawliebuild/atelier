package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Acces partage a la salle ouverte, pour les outils de l'Atelier.
 *
 * Tout passe par le moteur de l'Atelier, qui tient deja l'etat de la salle (FloorState),
 * la furnidata et l'inventaire. Ici on ne fait que rassembler les lectures
 * que chaque outil recopiait, et une seule ecoute des clics du jeu :
 *
 *   surClicCase  un clic sur le sol (deplacement d'avatar : x,y)
 *   surClicMobi  un clic sur un mobi de sol (son identifiant dans le paquet)
 *
 * Reconnaissance par CONTENU, comme dans OngletWired : un intercept par nom
 * echoue en silence quand le nom ne se resout plus.
 */
public final class Salle {

    private Salle() { }

    // ------------------------------------------------------------- lectures

    public static GPresets gp() { return AtelierLauncher.moteur(); }

    /** L'etat de la salle, ou null hors salle / Atelier pas pret. */
    public static FloorState etat() {
        GPresets gp = gp();
        if (gp == null) return null;
        FloorState s = gp.getFloorState();
        return (s == null || !s.inRoom()) ? null : s;
    }

    public static boolean dansUneSalle() { return etat() != null; }

    /** Identifiant de la salle ouverte, -1 hors salle. */
    public static int salleId() {
        try { FloorState s = etat(); return s == null ? -1 : s.getRoomId(); } catch (Throwable t) { return -1; }
    }

    private static volatile int salleVue = -1;
    private static volatile long salleVueA = 0;

    /**
     * Est-on dans la meme salle depuis au moins ms millisecondes ? Le jeu charge
     * la salle juste apres l'entree : mieux vaut ne rien lui envoyer a ce moment.
     * (La premiere fois qu'une salle est vue compte comme l'entree.)
     */
    public static boolean installeeDepuis(long ms) {
        int id = salleId();
        long t = System.currentTimeMillis();
        if (id != salleVue) { salleVue = id; salleVueA = t; }
        return id != -1 && t - salleVueA >= ms;
    }

    /**
     * Copie des mobis de sol ; jamais null. Sans les dalles fictives de la
     * grille dans le jeu, si le moteur de l'Atelier les a vues passer.
     */
    public static List<HFloorItem> sols() {
        FloorState s = etat();
        if (s == null) return List.of();
        try {
            List<HFloorItem> l = s.getItems();
            if (l == null) return List.of();
            l.removeIf(it -> GrilleCalcul.estFictif(it.getId()) || GroupeFantomes.estFantome(it.getId()));
            return l;
        }
        catch (Throwable t) { return List.of(); }
    }

    /** Copie des mobis muraux ; jamais null. */
    public static List<HWallItem> murs() {
        FloorState s = etat();
        if (s == null) return List.of();
        try {
            List<HWallItem> l = s.getWallItems();
            if (l == null) return List.of();
            l.removeIf(it -> GroupeFantomes.estFantome(it.getId()));
            return l;
        }
        catch (Throwable t) { return List.of(); }
    }

    public static HFloorItem sol(int id) {
        FloorState s = etat();
        try { return s == null ? null : s.furniFromId(id); } catch (Throwable t) { return null; }
    }

    public static HWallItem mur(int id) {
        FloorState s = etat();
        try { return s == null ? null : s.wallItemFromId(id); } catch (Throwable t) { return null; }
    }

    public static boolean furnidataPrete() {
        GPresets gp = gp();
        try { return gp != null && gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady(); }
        catch (Throwable t) { return false; }
    }

    /** Nom technique (classname) d'un type ; null si inconnu. */
    public static String classe(int typeId, boolean mural) {
        if (!furnidataPrete()) return null;
        try {
            furnidata.FurniDataTools fd = gp().getFurniDataTools();
            return mural ? fd.getWallItemName(typeId) : fd.getFloorItemName(typeId);
        } catch (Throwable t) { return null; }
    }

    /** Nom affiche dans le jeu, a defaut le nom technique. */
    public static String nom(int typeId, boolean mural) {
        String c = classe(typeId, mural);
        if (c == null) return "type " + typeId;
        try {
            furnidata.FurniDataTools fd = gp().getFurniDataTools();
            String n = mural ? fd.getWallItemDetails(c).name : fd.getFloorItemDetails(c).name;
            if (n != null && !n.isBlank()) return n;
        } catch (Throwable ignored) { }
        return c;
    }

    public static furnidata.details.FloorItemDetails details(String classe) {
        if (classe == null || !furnidataPrete()) return null;
        try { return gp().getFurniDataTools().getFloorItemDetails(classe); }
        catch (Throwable t) { return null; }
    }

    /**
     * Emprise au sol d'un mobi, rotation comprise : {largeur en x, longueur en y}.
     * Rotation 2 et 6 echangent les deux cotes.
     */
    public static int[] emprise(HFloorItem it) {
        int lx = 1, ly = 1;
        furnidata.details.FloorItemDetails d = details(classe(it.getTypeId(), false));
        if (d != null) { lx = Math.max(1, d.xDim); ly = Math.max(1, d.yDim); }
        int rot = rotation(it);
        return (rot == 2 || rot == 6) ? new int[]{ly, lx} : new int[]{lx, ly};
    }

    /** Rotation 0..7 d'un mobi de sol. */
    public static int rotation(HFloorItem it) {
        try { return it.getFacing().ordinal(); } catch (Throwable t) { return 0; }
    }

    /**
     * Hauteur propre d'un mobi (champ prive sizeZ de HFloorItem), a defaut
     * 0 pour un mobi inconnu. Meme lecture que OngletWired.hauteurDe.
     */
    public static double hauteur(HFloorItem it) {
        if (it == null) return 0;
        try {
            java.lang.reflect.Field f = HFloorItem.class.getDeclaredField("sizeZ");
            f.setAccessible(true);
            Object v = f.get(it);
            if (v != null) return Double.parseDouble(String.valueOf(v));
        } catch (Throwable ignored) { }
        return 0;
    }

    /** Hauteur du sol nu d'une case (0..), -1 si case hors plan ou vide. */
    public static int hauteurSol(int x, int y) {
        FloorState s = etat();
        if (s == null) return -1;
        try {
            char c = s.floorHeight(x, y);
            if (c == 'x' || c == 'X' || c == 0) return -1;
            return extension.tools.PresetUtils.heightFromChar(c);
        } catch (Throwable t) { return -1; }
    }

    // -------------------------------------------------------------- envois

    public static void envoyer(HPacket p) {
        GPresets gp = gp();
        if (gp != null) gp.sendToServer(p);
    }

    /** MoveObject(id, x, y, rotation) : deplace un mobi de sol. */
    public static void deplacerSol(int id, int x, int y, int rot) {
        envoyer(new HPacket("MoveObject", HMessage.Direction.TOSERVER, id, x, y, rot));
    }

    /** MoveWallItem(id, position) : deplace un mobi mural. */
    public static void deplacerMur(int id, String position) {
        envoyer(new HPacket("MoveWallItem", HMessage.Direction.TOSERVER, id, position));
    }

    /** PickupObject(categorie, id) : 2 = sol, 1 = mur (client Flash). */
    public static void ramasser(int id, boolean mural) {
        envoyer(new HPacket("PickupObject", HMessage.Direction.TOSERVER, mural ? 1 : 2, id));
    }

    /** PlaceObject depuis l'inventaire : "idInventaire x y rot" pour un mobi de sol. */
    /** Format du moteur de pose (GPresetImporter) : « -idInventaire x y rot ». */
    public static void poserSol(int idInventaire, int x, int y, int rot) {
        envoyer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                "-" + Math.abs(idInventaire) + " " + x + " " + y + " " + rot));
    }

    // ------------------------------------------------------- ecoute des clics

    private static final List<Consumer<HPoint>> clicsCase = new CopyOnWriteArrayList<>();
    private static final List<Consumer<HFloorItem>> clicsMobi = new CopyOnWriteArrayList<>();
    private static volatile boolean branche = false, enCours = false;

    /** Appele (hors fil JavaFX) a chaque clic sur une case du sol. */
    public static void surClicCase(Consumer<HPoint> c) { clicsCase.add(c); installer(); }

    /** Appele (hors fil JavaFX) a chaque clic sur un mobi de sol. */
    public static void surClicMobi(Consumer<HFloorItem> c) { clicsMobi.add(c); installer(); }

    public static void retirer(Object ecouteur) {
        clicsCase.remove(ecouteur);
        clicsMobi.remove(ecouteur);
    }

    /**
     * Branche l'ecoute une seule fois. Les onglets sont construits AVANT que
     * le moteur de l'Atelier soit demarre : on reessaie donc jusqu'a ce qu'il soit la.
     */
    public static synchronized void installer() {
        if (branche || enCours) return;
        enCours = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900 && !branche; i++) {
                GPresets gp = gp();
                if (gp != null) {
                    try {
                        gp.intercept(HMessage.Direction.TOSERVER, m -> {
                            try { examiner(m); } catch (Throwable ignored) { }
                        });
                        branche = true;
                        Journal.debug("ecoute partagee des clics active.");
                        return;
                    } catch (Throwable ignored) { }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-salle-clics");
        t.setDaemon(true);
        t.start();
    }

    private static void examiner(HMessage m) {
        int taille = m.getPacket().getBytesLength();
        if (taille > 40) return;
        FloorState s = etat();
        if (s == null) return;
        HPacket p = new HPacket(m.getPacket());

        // Clic au sol : deplacement d'avatar, deux petits entiers x,y.
        if (taille >= 14 && taille <= 20 && !clicsCase.isEmpty()) {
            try {
                int a = p.readInteger(6), b = p.readInteger(10);
                if (a >= 0 && a < 200 && b >= 0 && b < 200 && s.floorHeight(a, b) != 'x') {
                    HPoint c = new HPoint(a, b);
                    for (Consumer<HPoint> k : clicsCase) k.accept(c);
                    return;
                }
            } catch (Throwable ignored) { }
        }

        if (clicsMobi.isEmpty()) return;
        for (int off = 6; off + 4 <= taille; off++) {
            int v;
            try { v = p.readInteger(off); } catch (Throwable e) { break; }
            HFloorItem it = s.furniFromId(v);
            if (it != null) { for (Consumer<HFloorItem> k : clicsMobi) k.accept(it); return; }
        }
        // forme texte des identifiants (client Flash)
        byte[] o = p.toBytes();
        for (int i = 6; i < o.length; ) {
            if (o[i] < '0' || o[i] > '9') { i++; continue; }
            int d = i;
            while (i < o.length && o[i] >= '0' && o[i] <= '9') i++;
            if (i - d > 10) continue;
            try {
                long v = Long.parseLong(new String(o, d, i - d, java.nio.charset.StandardCharsets.ISO_8859_1));
                if (v <= 0 || v > Integer.MAX_VALUE) continue;
                HFloorItem it = s.furniFromId((int) v);
                if (it != null) { for (Consumer<HFloorItem> k : clicsMobi) k.accept(it); return; }
            } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- divers

    public static void sommeil(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Lance un travail hors du fil JavaFX, en fil demon nomme. */
    public static void tache(String nom, Runnable r) {
        Thread t = new Thread(() -> {
            try { r.run(); } catch (Throwable e) {
                System.err.println("[Atelier] " + nom + " : " + e);
                e.printStackTrace();
            }
        }, "atelier-" + nom);
        t.setDaemon(true);
        t.start();
    }
}

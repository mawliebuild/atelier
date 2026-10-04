package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.extensions.parsers.stuffdata.IStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Outils communs des briques de pose et d'enregistrement (PoseDalle,
 * ReglageWiredPose, PoseMuraux, EnregistrementCopie) :
 *   - les paquets, construits exactement comme l'importeur et l'exporteur de
 *     G-Presets les construisent (memes champs, meme ordre : compares octet par
 *     octet dans les essais) ;
 *   - les envois au rythme commun (Salle.espacer, 150 ms), plus l'ecart de
 *     350 ms que l'importeur garde entre deux WiredSetObjectVariableValue ;
 *   - les attentes interruptibles (BooleanSupplier stop) ;
 *   - la lecture des paquets de variables wired (WiredAllVariablesDiffs,
 *     WiredVariablesForObject, WiredAllVariableHolders).
 * Rien ici ne touche au fil JavaFX ni n'ecrit dans le jeu.
 */
final class PoseOutils {

    private PoseOutils() { }

    /** D'ou viennent les mobis a poser (ex-ItemSource : ONLY_INVENTORY, ONLY_BC, PREFER_BC, PREFER_INVENTORY). */
    enum Source {
        INVENTAIRE, BC, BC_PUIS_INVENTAIRE, INVENTAIRE_PUIS_BC;

        boolean inventaire() { return this != BC; }

        boolean bc() { return this != INVENTAIRE; }

        boolean bcDabord() { return this == BC || this == BC_PUIS_INVENTAIRE; }
    }

    static final HMessage.Direction S = HMessage.Direction.TOSERVER;

    // ================================================================ paquets (comme l'importeur)

    /** Pose depuis l'inventaire d'un mobi de sol (dropFurni) : PlaceObject("-id x y rot"). */
    static HPacket poseSolInventaire(int idInventaire, int x, int y, int rot) {
        return new HPacket("PlaceObject", S, String.format("-%d %d %d %d", idInventaire, x, y, rot));
    }

    /** Pose depuis le BC d'un mobi de sol (dropFurni, acquireStackTileFromBC). */
    static HPacket poseSolBc(int offre, int x, int y, int rot) {
        return new HPacket("BuildersClubPlaceRoomItem", S, -1, offre, "", x, y, rot);
    }

    /** MoveObject(id, x, y, rot) (moveFurni). */
    static HPacket deplacementSol(int id, int x, int y, int rot) {
        return new HPacket("MoveObject", S, id, x, y, rot);
    }

    /** SetCustomStackingHeight(dalle, round(z * 100)) (moveFurni, placePresetStackTiles). */
    static HPacket hauteurDalle(int dalle, double z) {
        return new HPacket("SetCustomStackingHeight", S, dalle, (int) Math.round(z * 100.0));
    }

    /** PickupObject(2, id) : ramasse un mobi de sol (2 = sol, 1 = mural). */
    static HPacket ramassageSol(int id) {
        return new HPacket("PickupObject", S, 2, id);
    }

    /** Etat d'un mobi de sol par la variable -110 (attemptSetState) : 4 champs, sans le dernier entier. */
    static HPacket etatParVariable(int id, int etat) {
        return new HPacket("WiredSetObjectVariableValue", S, 0, id, "-110", etat);
    }

    /** Variable interne d'un mural (sendWiredVariable : « -123 » altitude, « -110 » etat) : (0, -id, var, valeur, 0). */
    static HPacket variableInterneMural(int id, String variable, int valeur) {
        return new HPacket("WiredSetObjectVariableValue", S, 0, -id, variable, valeur, 0);
    }

    /** Valeur d'une variable de l'utilisatrice sur un mobi de sol (sendFloorObjectVariable*) : (0, id, var, valeur, 1). */
    static HPacket variableSol(int id, String variable, int valeur) {
        return new HPacket("WiredSetObjectVariableValue", S, 0, id, variable, valeur, 1);
    }

    /** Valeur d'une variable de l'utilisatrice sur un mural (applyWallFurniVariables) : (0, -id, var, valeur, 1). */
    static HPacket variableMural(int id, String variable, int valeur) {
        return new HPacket("WiredSetObjectVariableValue", S, 0, -id, variable, valeur, 1);
    }

    static HPacket utilisationSol(int id) { return new HPacket("UseFurniture", S, id, 0); }

    static HPacket utilisationMural(int id) { return new HPacket("UseWallItem", S, id, 0); }

    /** Pose d'un mural depuis l'inventaire : PlaceObject("id :w=..."), sans le « - ». */
    static HPacket poseMurInventaire(int idInventaire, String position) {
        return new HPacket("PlaceObject", S, idInventaire + " " + position);
    }

    /** Pose d'un mural depuis le BC (placeWallItems) : (page, offre, etat, position, false). */
    static HPacket poseMurBc(int page, int offre, String etat, String position) {
        return new HPacket("BuildersClubPlaceWallItem", S, page, offre, etat == null ? "" : etat, position, false);
    }

    static HPacket deplacementMur(int id, String position) {
        return new HPacket("MoveWallItem", S, id, position);
    }

    /** Demande les variables d'un mural (fetchWallItemVariables, inspectFurniWithAcks) : (0, -id). */
    static HPacket inspectionMural(int id) {
        return new HPacket("WiredGetVariablesForObject", S, 0, -id);
    }

    /** Demande la liste des variables de la salle (reponse WiredAllVariablesDiffs). */
    static HPacket demandeVariables() {
        return new HPacket("WiredGetAllVariablesDiffs", S, 0);
    }

    /** Demande les porteurs d'une variable (fetchVariablesSerial). */
    static HPacket demandePorteurs(String variable) {
        return new HPacket("WiredGetAllVariableHolders", S, variable);
    }

    /** Fond d'un mobi publicitaire (setupAds). */
    static HPacket fondPub(int id, String image, String dx, String dy, String dz) {
        return new HPacket("SetObjectData", S, id, 8, "imageUrl", image, "offsetX", dx, "offsetY", dy, "offsetZ", dz);
    }

    // ================================================================ envois et attentes

    /** Envoie a son tour dans le rythme commun (Salle.espacer) ; false si la connexion refuse. */
    static boolean envoyer(Canal canal, HPacket p) {
        Salle.espacer();
        try {
            return canal.sendToServer(p);
        } catch (Throwable t) {
            Journal.debug("Briques : envoi refusé (" + t + ")");
            return false;
        }
    }

    private static final Object RYTHME_VARIABLES = new Object();
    private static long dernierVariable = 0;

    /** Comme envoyer, mais au moins 350 ms apres le WiredSetObjectVariableValue precedent (throttleWiredSetObjectVariableValue). */
    static boolean envoyerVariable(Canal canal, HPacket p) {
        long attente;
        synchronized (RYTHME_VARIABLES) {
            long t = System.currentTimeMillis();
            long a = Math.max(t, dernierVariable + 350);
            dernierVariable = a;
            attente = a - t;
        }
        if (attente > 0) Salle.sommeil(attente);
        return envoyer(canal, p);
    }

    /** Dort ms (par tranches de 50 ms) ; false si stop a ete demande entre-temps. */
    static boolean dormir(long ms, BooleanSupplier stop) {
        long fin = System.currentTimeMillis() + ms;
        while (true) {
            if (stop != null && stop.getAsBoolean()) return false;
            long r = fin - System.currentTimeMillis();
            if (r <= 0) return true;
            Salle.sommeil(Math.min(50, r));
        }
    }

    /**
     * Suivi : attend que « restants » tombe a 0 ; s'arrete aussi quand plus rien
     * n'a bouge depuis calmeMs, au plus maxMs, ou sur stop. Rend le dernier compte.
     */
    static int suivre(IntSupplier restants, long calmeMs, long maxMs, BooleanSupplier stop) {
        long debut = System.currentTimeMillis(), change = debut;
        int r = restants.getAsInt();
        while (r > 0) {
            long t = System.currentTimeMillis();
            if (t - debut >= maxMs || t - change >= calmeMs) break;
            if (stop != null && stop.getAsBoolean()) break;
            Salle.sommeil(40);
            int n = restants.getAsInt();
            if (n != r) { r = n; change = System.currentTimeMillis(); }
        }
        return r;
    }

    /** Attend qu'une condition soit vraie (au plus maxMs) ; false sinon. */
    static boolean attendre(BooleanSupplier condition, long maxMs, BooleanSupplier stop) {
        return suivre(() -> condition.getAsBoolean() ? 0 : 1, maxMs, maxMs, stop) == 0;
    }

    // ================================================================ lecture de la salle

    /** L'etat d'un mobi de sol au sens de G-Presets (StateExtractor) : null si non utilisable ou sans etat. */
    static String etat(HFloorItem f) {
        if (f == null || f.getUsagePolicy() < 1) return null;
        IStuffData d = f.getStuff();
        if (d == null) return null;
        int e;
        try { e = d.getState(); } catch (Throwable t) { return null; }
        return e < 0 ? null : Integer.toString(e);
    }

    /** Hauteur d'un caractere du plan (PresetUtils.heightFromChar) : 'x' -> 256, '0'..'9', 'a'..'z' -> 10..35. */
    static int hauteurCaractere(char c) {
        if (c == 'x') return 256;
        if (Character.isDigit(c)) return c - '0';
        if (Character.isLetter(c) && Character.isLowerCase(c)) return c - 'a' + 10;
        return 256;
    }

    /** Le plus bas point du sol dans le rectangle (PresetUtils.lowestFloorPoint), 256 si rien. */
    static int solLePlusBas(EtatSalle salle, int x1, int y1, int x2, int y2) {
        int bas = 256;
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++) bas = Math.min(bas, hauteurCaractere(salle.caseDuPlan(x, y)));
        return bas;
    }

    /** Ids des muraux presents. */
    static List<Integer> idsMuraux(EtatSalle salle) {
        List<Integer> l = new ArrayList<>();
        for (HWallItem w : salle.getWallItems()) l.add(w.getId());
        return l;
    }

    // ================================================================ etats

    /**
     * Met un mobi de sol a l'etat voulu (attemptSetState, sans case « changeur
     * d'etat ») : d'abord la variable -110 si l'on a des droits (wired ou
     * deplacement, comme l'importeur), puis, a defaut, UseFurniture sur place
     * jusqu'a l'etat voulu (20 fois au plus, arret si l'etat ne bouge plus).
     * @return true si le mobi est a l'etat voulu a la fin
     */
    static boolean mettreEtat(Canal canal, EtatSalle salle, Droits droits, int id, String voulu, BooleanSupplier stop) {
        HFloorItem f = salle.furniFromId(id);
        if (f == null || voulu == null) return false;
        String actuel = etat(f);
        if (voulu.equals(actuel)) return true;
        boolean variables = droits != null && (droits.peutRegler() || droits.peutDeplacer());
        if (variables) {
            try {
                int v = Integer.parseInt(voulu);
                envoyerVariable(canal, etatParVariable(id, v));
                if (attendre(() -> voulu.equals(etat(salle.furniFromId(id))), 700, stop)) return true;
            } catch (NumberFormatException ignored) {
                Journal.debug("Briques : état « " + voulu + " » non numérique pour " + id + ", essai par utilisation.");
            }
        }
        String cycle = etat(salle.furniFromId(id)), avant = "-1";
        for (int i = 0; i < 20 && cycle != null && !cycle.equals(voulu) && !cycle.equals(avant); i++) {
            if (stop != null && stop.getAsBoolean()) break;
            avant = cycle;
            envoyer(canal, utilisationSol(id));
            final String a = avant;
            attendre(() -> { String e = etat(salle.furniFromId(id)); return e == null || !e.equals(a); }, 700, stop);
            cycle = etat(salle.furniFromId(id));
        }
        return voulu.equals(etat(salle.furniFromId(id)));
    }

    // ================================================================ variables wired

    /** Une variable wired de la salle (ex-HWiredVariable, les champs utiles). */
    record Variable(String id, int typeInterne, String nom, int disponibilite, int genre) {
        /** Creee par l'utilisatrice (VariableInternalType.USER_CREATED). */
        boolean utilisatrice() { return typeInterne == 0; }

        /** Variable de mobi (variableType 0, comme requestFurniVariablesInArea). */
        boolean deMobi() { return genre == 0; }
    }

    /** Un morceau de WiredAllVariablesDiffs. */
    record ListeVariables(boolean dernier, List<String> retirees, List<Variable> variables) { }

    /**
     * WiredAllVariablesDiffs : int empreinte, boolean dernier morceau, int n,
     * n x String retirees, int m, m x (int, HWiredVariable). Les retirees sont
     * lues en textes comme l'exporteur et WiredLecteur (l'importeur les lit en
     * long : la liste est vide en reponse a une demande complete).
     */
    static ListeVariables lireListeVariables(HPacket p) {
        p.resetReadIndex();
        p.readInteger();
        boolean dernier = p.readBoolean();
        int n = p.readInteger();
        List<String> retirees = new ArrayList<>();
        for (int i = 0; i < n && i < 100_000; i++) retirees.add(p.readString());
        int m = p.readInteger();
        List<Variable> l = new ArrayList<>();
        for (int i = 0; i < m && i < 100_000; i++) {
            p.readInteger();
            l.add(lireVariable(p));
        }
        return new ListeVariables(dernier, retirees, l);
    }

    /** HWiredVariable : id, type interne, nom, disponibilite, genre, 8 booleens, table facultative. */
    static Variable lireVariable(HPacket p) {
        String id = p.readString();
        int interne = p.readInteger();
        String nom = p.readString();
        int dispo = p.readInteger();
        int genre = p.readInteger();
        for (int i = 0; i < 8; i++) p.readBoolean();
        if (p.readBoolean()) {
            int k = p.readInteger();
            for (int i = 0; i < k; i++) { p.readInteger(); p.readString(); }
        }
        return new Variable(id, interne, nom, dispo, genre);
    }

    /** WiredVariablesForObject d'un mobi (type 0) : objet (valeur absolue), variables id -> valeur ; null pour un avatar. */
    record VariablesObjet(int objet, Map<String, Integer> variables) { }

    static VariablesObjet lireVariablesObjet(HPacket p) {
        p.resetReadIndex();
        int type = p.readInteger();
        if (type != 0) return null;
        int objet = Math.abs(p.readInteger());
        int n = p.readInteger();
        Map<String, Integer> v = new HashMap<>();
        for (int i = 0; i < n && i < 100_000; i++) v.put(p.readString(), p.readInteger());
        return new VariablesObjet(objet, v);
    }

    /** WiredAllVariableHolders : la variable et ses porteurs (objet -> valeur). */
    record Porteurs(String variable, String nom, Map<Integer, Integer> valeurs) { }

    static Porteurs lirePorteurs(HPacket p) {
        p.resetReadIndex();
        p.readInteger();
        String id = p.readString();
        p.readInteger();
        String nom = p.readString();
        p.readInteger();
        p.readInteger();
        for (int i = 0; i < 8; i++) p.readBoolean();
        if (p.readBoolean()) {
            int k = p.readInteger();
            for (int i = 0; i < k; i++) { p.readInteger(); p.readString(); }
        }
        int n = p.readInteger();
        Map<Integer, Integer> v = new LinkedHashMap<>();
        for (int i = 0; i < n && i < 100_000; i++) {
            int objet = p.readInteger();
            v.put(objet, p.readInteger());
        }
        return new Porteurs(id, nom, v);
    }

    // ================================================================ textes

    /** « 1 mobi », « 3 mobis » (sans « (s) »). */
    static String nombre(int n, String un, String plusieurs) { return n + " " + (n > 1 ? plusieurs : un); }
}

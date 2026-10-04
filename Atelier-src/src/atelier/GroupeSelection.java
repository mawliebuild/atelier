package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La selection courante de mobis pour les calques (ids de sols et de murs).
 *
 * Mode calque (Option + C en mode Construction) : chaque clic sur un mobi dans
 * le jeu le fait entrer dans la selection, ou en sortir s'il y etait deja.
 * Hors mode calque, aucun clic ne selectionne et rien n'est mis en valeur.
 * Tant qu'un mobi est selectionne (en mode calque), il est mis en valeur chez toi : une lueur
 * jaune si le client du jeu est le client modifie par l'Atelier (option
 * « surlignage » de Atelier-swf/construire.py), sinon il clignote
 * (MAX_CLIGNOTANTS au plus). Refaire la selection eteint l'ancienne lueur.
 * (Le mode selection, s'il est actif, ajoute aussi au clic simple.)
 *   sols  Salle.surClicMobi (rien n'est bloque, le jeu reagit normalement) ;
 *   murs  SelectionMur (son clic fait deja foi pour les outils muraux).
 * Les fantomes de l'apercu ne sont jamais selectionnes. Changer de salle vide
 * la selection.
 */
final class GroupeSelection {

    private GroupeSelection() { }

    private static final Set<Integer> sols = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Integer> murs = Collections.synchronizedSet(new LinkedHashSet<>());
    private static volatile boolean mode = false;
    private static volatile boolean installe = false;
    /** Le dernier objet « mur selectionne » vu : un nouveau clic cree un nouvel objet. */
    private static volatile Object dernierMur = null;

    static boolean mode() { return mode; }

    static void mode(boolean actif) {
        mode = actif;
        dernierMur = SelectionMur.courant();      // l'ancienne selection murale ne compte pas
    }

    static synchronized void installer() {
        if (installe) return;
        installe = true;
        Salle.surClicMobi(GroupeSelection::clicSol);
        SelectionMur.ecouter(GroupeSelection::clicMur);
        Thread t = new Thread(GroupeSelection::montrerEnBoucle, "atelier-selection-montre");
        t.setDaemon(true);
        t.start();
    }

    /** Un clic peut produire plusieurs paquets avec le meme id : un seul compte. */
    private static final long REBOND_MS = 600;
    private static volatile int dernierSol = -1;
    private static volatile long dernierSolA = 0;

    private static void clicSol(HFloorItem it) {
        if (it == null || GroupeFantomes.estFantome(it.getId()) || GrilleCalcul.estFictif(it.getId())) return;
        // Seulement en mode calque (Option + C en mode Construction)
        if (!mode) return;
        long t = System.currentTimeMillis();
        int id = it.getId();
        if (id == dernierSol && t - dernierSolA < REBOND_MS) return;
        dernierSol = id; dernierSolA = t;
        if (basculer(sols, id, true)) Groupes.prevenir();
    }

    /** Ajoute, ou retire s'il y etait deja. */
    private static boolean basculer(Set<Integer> ens, int id, boolean option) {
        if (ens.add(id)) return true;
        if (option) { ens.remove(id); return true; }
        return false;
    }

    private static void clicMur() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null || m == dernierMur) return;
        dernierMur = m;
        if (!mode || GroupeFantomes.estFantome(m.id)) return;
        if (basculer(murs, m.id, true)) Groupes.prevenir();
    }

    // ------------------------------------------------------------ mise en valeur

    /** Ce qui a ete envoye au client en dernier (« s12,m34 »), et quand. */
    private static volatile String lueurEnvoyee = "";
    private static volatile int lueurSalle = -1;
    private static volatile long lueurA = 0;
    /**
     * Tant qu'il y a une selection, la lueur est renvoyee de temps en temps : un
     * mobi masque puis reaffiche (calques) revient sans elle dans le jeu.
     */
    private static final long LUEUR_RAPPEL_MS = 4000;
    private static volatile long clientVerifieA = 0;
    private static volatile boolean clientLueur = false;

    /** Lueur si le client sait la faire, clignotement sinon. */
    private static void montrerEnBoucle() {
        boolean cache = false;
        while (true) {
            try {
                long t = System.currentTimeMillis();
                if (t - clientVerifieA > 15_000) { clientLueur = ClientModifie.saitSurligner(); clientVerifieA = t; }
                if (clientLueur) {
                    if (cache) { Calques.regler(CLIGNOTE, List.of(), List.of()); cache = false; }
                    surligner();
                    Salle.sommeil(250);
                } else {
                    cache = clignoterUneFois(cache);
                }
            } catch (Throwable e) {
                Salle.sommeil(500);
            }
        }
    }

    /** Envoie la liste au client si elle a change (ou pour la rappeler). */
    private static void surligner() {
        if (!Salle.dansUneSalle()) { lueurEnvoyee = ""; lueurSalle = -1; return; }
        // Hors mode calque, rien n'est detoure (la selection reste pour les outils).
        Groupes.Selection s = mode ? copie() : new Groupes.Selection(Set.of(), Set.of());
        StringBuilder b = new StringBuilder();
        for (int i : s.sols) { if (b.length() > 0) b.append(','); b.append('s').append(i); }
        for (int i : s.murs) { if (b.length() > 0) b.append(','); b.append('m').append(i); }
        // et ce que la fenetre ouverte met en valeur (plante, couleur de decor, wired choisis)
        for (String j : MiseEnValeur.jetons()) { if (b.length() > 0) b.append(','); b.append(j); }
        String liste = b.toString();
        int salle = Salle.salleId();
        long t = System.currentTimeMillis();
        boolean change = !liste.equals(lueurEnvoyee) || salle != lueurSalle;
        boolean rappel = !liste.isEmpty() && t - lueurA > LUEUR_RAPPEL_MS;
        if (!change && !rappel) return;
        // Salle neuve et rien a allumer : rien a eteindre non plus, on n'envoie
        // rien (le jeu charge la salle a ce moment-la).
        if (liste.isEmpty() && salle != lueurSalle) { lueurEnvoyee = ""; lueurSalle = salle; lueurA = t; return; }
        if (!Salle.installeeDepuis(3000)) return;
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        // Un rappel (liste inchangee) ne redit pas « choisis cet animal » (jetons p…) : sinon
        // le jeu reprendrait la plante de l'Atelier juste apres un clic sur un autre animal.
        String envoi = liste;
        if (!change && liste.contains("p")) {
            StringBuilder sb = new StringBuilder();
            for (String j : liste.split(","))
                if (!j.isEmpty() && j.charAt(0) != 'p') { if (sb.length() > 0) sb.append(','); sb.append(j); }
            envoi = sb.toString();
        }
        // Chuchotement « atelier:surligner=... » : le client modifie ne l'affiche
        // pas, il eteint toutes les lueurs puis allume celles de la liste.
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:surligner=" + envoi, 0, 0, 0, -1));
        lueurEnvoyee = liste; lueurSalle = salle; lueurA = t;
        if (change)
            Journal.debug("Mise en valeur envoyée au jeu : "
                    + (liste.isEmpty() ? "aucun mobi (tout éteint)" : (s.sols.size() + s.murs.size()) + " mobi(s)") + ".");
    }

    // ------------------------------------------------------------ clignotement

    /** Raison de masquage propre au clignotement (independante des calques). */
    private static final String CLIGNOTE = "selection";
    /** Au-dela, on ne fait plus clignoter : trop de paquets a chaque battement. */
    static final int MAX_CLIGNOTANTS = 60;
    private static final long CACHE_MS = 300, VISIBLE_MS = 700;

    /**
     * Tant qu'un mobi est selectionne, il clignote chez toi : cache 0,3 s,
     * visible 0,7 s (il reste cliquable la plupart du temps). Selection videe,
     * ou devenue un calque : tout revient.
     */
    /** Un battement de clignotement (client d'origine). @return true si des mobis restent caches. */
    private static boolean clignoterUneFois(boolean cache) {
        Groupes.Selection s = copie();
        boolean actif = mode && !s.vide() && s.nombre() <= MAX_CLIGNOTANTS && Calques.pret();
        if (!actif) {
            if (cache) Calques.regler(CLIGNOTE, List.of(), List.of());
            Salle.sommeil(250);
            return false;
        }
        List<HFloorItem> fs = new ArrayList<>();
        List<HWallItem> ms = new ArrayList<>();
        for (int i : s.sols) { HFloorItem it = Salle.sol(i); if (it != null) fs.add(it); }
        for (int i : s.murs) { HWallItem it = Salle.mur(i); if (it != null) ms.add(it); }
        Calques.regler(CLIGNOTE, fs, ms);
        Salle.sommeil(CACHE_MS);
        Calques.regler(CLIGNOTE, List.of(), List.of());
        Salle.sommeil(VISIBLE_MS);
        return false;
    }

    static Groupes.Selection copie() {
        Set<Integer> s, m;
        synchronized (sols) { s = new LinkedHashSet<>(sols); }
        synchronized (murs) { m = new LinkedHashSet<>(murs); }
        return new Groupes.Selection(s, m);
    }

    static void ajouter(Collection<Integer> s, Collection<Integer> m) {
        if (s != null) sols.addAll(s);
        if (m != null) murs.addAll(m);
    }

    static void retirer(Collection<Integer> s, Collection<Integer> m) {
        if (s != null) sols.removeAll(s);
        if (m != null) murs.removeAll(m);
    }

    static void vider() {
        boolean avait = !sols.isEmpty() || !murs.isEmpty();
        sols.clear();
        murs.clear();
        if (avait) Groupes.prevenir();
    }

    /** Ajoute les mobis de la zone. @return nombre ajoutes */
    static int zone() {
        if (!Zone.definie()) return 0;
        int n = 0;
        for (HFloorItem it : Zone.mobisTouches())
            if (!GroupeFantomes.estFantome(it.getId()) && sols.add(it.getId())) n++;
        for (HWallItem w : Salle.murs()) {
            if (GroupeFantomes.estFantome(w.getId())) continue;
            int[] c = GroupeCalcul.caseMur(w.getLocation());
            if (c != null && Zone.contient(c[0], c[1]) && murs.add(w.getId())) n++;
        }
        return n;
    }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Edition du floor DIRECTEMENT dans l'appart (bouton Floor) : la grille du jeu
 * montre en permanence le plan en cours de travail, avec des cases fantomes
 * autour (la ou l'on peut en ajouter). Le client modifie
 * (« atelier:cases=1:<h> ») avale les clics et ecrit la case visee, meme dans
 * le vide, dans ~/.atelier-case.txt (« n:x,y ») ; chaque clic applique l'outil
 * choisi (comme l'ancien editeur : monter, descendre, fixer a N, ajouter,
 * supprimer, porte, pipette, ou « Auto » : ajoute dans le vide, retire sur une
 * case), au pinceau 1×1..5×5 ou en rectangle (deux clics).
 *
 * Marques envoyees a la grille (« plan|marques ») : a ajout (vert), r retrait
 * (rouge), h hauteur changee (bleu), p porte (orange), g case fantome.
 * Rien ne part au serveur avant « Appliquer » : un seul UpdateFloorProperties,
 * l'appart se recharge une fois. Une case sous un mobi ne se retire pas.
 */
final class ModeCases {

    private ModeCases() { }

    enum Outil {
        AUTO("Auto", "Clic dans le vide : ajoute une case. Clic sur une case : la retire."),
        MONTER("Monter", "Monte la case d'un niveau (+1)."),
        DESCENDRE("Descendre", "Descend la case d'un niveau (−1, jamais sous 0)."),
        FIXER("Fixer à N", "Met la case à la hauteur N."),
        AJOUTER("Ajouter", "Crée une case (hauteur N) là où il n'y en a pas."),
        SUPPRIMER("Supprimer", "Retire la case."),
        PORTE("Porte", "Clic : place la porte. Clic sur la porte : la tourne."),
        PIPETTE("Pipette", "Prend la hauteur d'une case comme valeur N.");
        final String libelle, aide;
        Outil(String l, String a) { libelle = l; aide = a; }
    }

    /** Taille maxi d'un plan Habbo (cases). */
    static final int MAX = 64;
    /** Cases fantomes au-dela du plan (a droite et en bas). */
    private static final int MARGE = 4;
    private static final File FICHIER = new File(Capture.maisonReelle(), ".atelier-case.txt");

    private static volatile boolean actif = false, occupe = false;
    /** base = floor de l'appart ; travail = ce qu'on prepare ; precedent = avant le dernier Appliquer. */
    private static volatile FloorModele base, travail, precedent;
    private static final Deque<FloorModele> annuler = new ArrayDeque<>(), retablir = new ArrayDeque<>();
    private static volatile Outil outil = Outil.AUTO;
    private static volatile int valeur = 0, pinceau = 1, hauteurFantome = 0;
    private static volatile boolean rectangle = false;
    private static volatile int[] coin = null;              // premier coin du rectangle
    private static volatile String dernierLu = null;
    private static volatile Runnable surChangement = () -> { };

    static boolean actif() { return actif; }

    static boolean occupe() { return occupe; }

    static void surChangement(Runnable r) { surChangement = r == null ? () -> { } : r; }

    static Outil outil() { return outil; }

    static void outil(Outil o) { outil = o; coin = null; }

    static int valeur() { return valeur; }

    static void valeur(int v) { valeur = Math.max(0, Math.min(FloorModele.HAUTEUR_MAX, v)); }

    static void pinceau(int n) { pinceau = Math.max(1, Math.min(5, n)); }

    static void rectangle(boolean r) { rectangle = r; coin = null; }

    static FloorModele travail() { return travail; }

    static boolean peutAnnuler() { synchronized (ModeCases.class) { return !annuler.isEmpty(); } }

    static boolean peutRetablir() { synchronized (ModeCases.class) { return !retablir.isEmpty(); } }

    static boolean peutRevenir() { return precedent != null && !occupe; }

    // ------------------------------------------------------------ cycle

    /** Entre dans le mode (hors fil JavaFX). null si tout va bien, sinon la raison. */
    static String demarrer() {
        if (!Salle.dansUneSalle()) return "Tu n'es pas dans un appart.";
        if (!ClientModifie.saitCases())
            return "Le jeu installé ne connaît pas encore l'édition du floor dans l'appart : ferme Habbo, "
                    + "relance « Lancer l'Atelier » (ou installer-mod.sh), puis rouvre Habbo.";
        String err = relire();
        if (err != null) return err;
        try { dernierLu = Files.exists(FICHIER.toPath()) ? Files.readString(FICHIER.toPath(), StandardCharsets.UTF_8) : null; }
        catch (Throwable t) { dernierLu = null; }
        actif = true;
        envoyerMode(true);
        redessiner();
        Salle.tache("mode-cases", ModeCases::ecouter);
        InfoJeu.consigne("Floor : clique les cases dans l'appart, puis « Appliquer ».");
        return null;
    }

    /** Relit le floor de l'appart ; le travail repart de lui. */
    private static String relire() {
        FloorReseau.installer();
        FloorReseau.demanderPorte();
        Salle.sommeil(700);
        FloorSession.Lecture l = FloorSession.lire();
        if (l.modele == null) return "Plan de l'appart illisible" + (l.erreur == null ? "." : " : " + l.erreur);
        synchronized (ModeCases.class) {
            base = l.modele;
            travail = l.modele.copie();
            annuler.clear();
            retablir.clear();
        }
        hauteurFantome = hauteurCourante(base);
        valeur = hauteurFantome;
        return null;
    }

    static void arreter() {
        if (!actif) return;
        actif = false;
        coin = null;
        envoyerMode(false);
        GrilleVue.imposer(null);
    }

    // ------------------------------------------------------------ clics

    private static void ecouter() {
        long rappel = 0;
        while (actif) {
            Salle.sommeil(80);
            if (!Salle.dansUneSalle()) { if (!occupe) { arreter(); surChangement.run(); return; } continue; }
            long t = System.currentTimeMillis();
            if (t - rappel > 4000 && Salle.installeeDepuis(3000)) {      // apres un rechargement, le jeu oublie tout
                rappel = t;
                envoyerMode(true);
            }
            String s;
            try { s = Files.exists(FICHIER.toPath()) ? Files.readString(FICHIER.toPath(), StandardCharsets.UTF_8) : null; }
            catch (Throwable e) { continue; }
            if (s == null || s.equals(dernierLu)) continue;
            dernierLu = s;
            int deux = s.indexOf(':'), v = s.indexOf(',');
            if (deux < 0 || v < deux) continue;
            try {
                clic(Integer.parseInt(s.substring(deux + 1, v).trim()), Integer.parseInt(s.substring(v + 1).trim()));
            } catch (NumberFormatException ignored) { }
        }
    }

    private static void clic(int x, int y) {
        if (occupe || travail == null || x < 0 || y < 0 || x >= MAX || y >= MAX) return;
        if (outil == Outil.PIPETTE) {
            int h = travail.at(x, y);
            if (h >= 0) { valeur = h; Journal.succes("Hauteur " + h + " prise."); surChangement.run(); }
            return;
        }
        if (outil == Outil.PORTE) { porte(x, y); return; }
        int x0, y0, x1, y1;
        if (rectangle) {
            int[] c = coin;
            if (c == null) { coin = new int[]{x, y}; InfoJeu.consigne("Rectangle : clique le deuxième coin."); return; }
            coin = null;
            x0 = Math.min(c[0], x); y0 = Math.min(c[1], y); x1 = Math.max(c[0], x); y1 = Math.max(c[1], y);
        } else {
            int d = (pinceau - 1) / 2;
            x0 = Math.max(0, x - d); y0 = Math.max(0, y - d);
            x1 = Math.min(MAX - 1, x0 + pinceau - 1); y1 = Math.min(MAX - 1, y0 + pinceau - 1);
        }
        // Auto : d'apres la case cliquee (vide -> ajouter, case -> supprimer) pour tout le pinceau
        Outil o = outil == Outil.AUTO ? (travail.existe(x, y) ? Outil.SUPPRIMER : Outil.AJOUTER) : outil;
        int bloquees = 0;
        synchronized (ModeCases.class) {
            FloorModele avant = travail.copie();
            FloorModele t = agrandi(travail, x1 + 1, y1 + 1);
            boolean change = false;
            for (int i = x0; i <= x1; i++) for (int j = y0; j <= y1; j++) {
                int h = t.at(i, j);
                int n;
                switch (o) {
                    case MONTER:    n = h < 0 ? -1 : Math.min(FloorModele.HAUTEUR_MAX, h + 1); break;
                    case DESCENDRE: n = h < 0 ? -1 : Math.max(0, h - 1); break;
                    case FIXER:     n = h < 0 ? -1 : valeur; break;
                    case AJOUTER:   n = h < 0 ? valeur : h; break;
                    case SUPPRIMER: n = -1; break;
                    default:        n = h;
                }
                if (n == h) continue;
                if (n < 0 && estPorte(t, i, j)) { bloquees++; continue; }
                if (n < 0 && base.existe(i, j) && occupee(i, j)) { bloquees++; continue; }
                t.h[i][j] = n;
                change = true;
            }
            if (change) { annuler.push(avant); retablir.clear(); travail = t; }
        }
        if (bloquees > 0) Journal.erreur(Ui.accorder(bloquees + " case(s) gardée(s) : la porte ou un mobi est dessus."));
        redessiner();
    }

    private static void porte(int x, int y) {
        synchronized (ModeCases.class) {
            if (!travail.existe(x, y)) { Journal.erreur("La porte doit être sur une case."); return; }
            annuler.push(travail.copie());
            retablir.clear();
            FloorModele t = travail.copie();
            if (t.porteConnue && t.porteX == x && t.porteY == y) t.porteDir = (t.porteDir + 2) % 8;
            else { t.porteX = x; t.porteY = y; t.porteConnue = true; }
            travail = t;
        }
        redessiner();
    }

    private static boolean estPorte(FloorModele m, int x, int y) { return m.porteConnue && m.porteX == x && m.porteY == y; }

    private static boolean occupee(int x, int y) {
        for (HFloorItem it : Salle.sols()) {
            int[] e = Salle.emprise(it);
            int ix = it.getTile().getX(), iy = it.getTile().getY();
            if (x >= ix && x < ix + e[0] && y >= iy && y < iy + e[1]) return true;
        }
        return false;
    }

    /** Copie du plan, agrandie au besoin pour contenir (w, l). */
    private static FloorModele agrandi(FloorModele b, int w, int l) {
        w = Math.max(b.largeur, w); l = Math.max(b.longueur, l);
        FloorModele m = new FloorModele(w, l);
        for (int x = 0; x < b.largeur; x++) for (int y = 0; y < b.longueur; y++) m.h[x][y] = b.h[x][y];
        m.porteX = b.porteX; m.porteY = b.porteY; m.porteDir = b.porteDir; m.porteConnue = b.porteConnue;
        m.hauteurMur = b.hauteurMur; m.epMur = b.epMur; m.epSol = b.epSol; m.epConnues = b.epConnues;
        m.echelle = b.echelle;
        return m;
    }

    // ------------------------------------------------- reglages du plan

    static void murs(int hauteurMur, int epMur, int epSol) {
        synchronized (ModeCases.class) {
            if (travail == null) return;
            if (travail.hauteurMur == hauteurMur && travail.epMur == epMur && travail.epSol == epSol) return;
            annuler.push(travail.copie());
            retablir.clear();
            FloorModele t = travail.copie();
            t.hauteurMur = hauteurMur; t.epMur = epMur; t.epSol = epSol;
            travail = t;
        }
        surChangement.run();
    }

    static void annuler() { deplacerHistorique(annuler, retablir); }

    static void retablir() { deplacerHistorique(retablir, annuler); }

    private static void deplacerHistorique(Deque<FloorModele> de, Deque<FloorModele> vers) {
        synchronized (ModeCases.class) {
            if (de.isEmpty()) return;
            vers.push(travail.copie());
            travail = de.pop();
        }
        redessiner();
    }

    /** Oublie tout ce qui n'est pas applique. */
    static void effacer() {
        synchronized (ModeCases.class) {
            if (base == null) return;
            annuler.push(travail.copie());
            retablir.clear();
            travail = base.copie();
        }
        redessiner();
    }

    // ---------------------------------------------------------- comptes

    /** {ajouts, retraits, hauteurs changees, autres (porte, murs)} par rapport au floor de l'appart. */
    static synchronized int[] changements() {
        int[] r = new int[4];
        FloorModele b = base, t = travail;
        if (b == null || t == null) return r;
        int w = Math.max(b.largeur, t.largeur), l = Math.max(b.longueur, t.longueur);
        for (int x = 0; x < w; x++) for (int y = 0; y < l; y++) {
            int a = b.at(x, y), n = t.at(x, y);
            if (a < 0 && n >= 0) r[0]++;
            else if (a >= 0 && n < 0) r[1]++;
            else if (a >= 0 && a != n) r[2]++;
        }
        if (b.porteX != t.porteX || b.porteY != t.porteY || b.porteDir != t.porteDir) r[3]++;
        if (b.hauteurMur != t.hauteurMur || b.epMur != t.epMur || b.epSol != t.epSol) r[3]++;
        return r;
    }

    // ---------------------------------------------------------- dessin

    private static void envoyerMode(boolean oui) {
        extension.GPresets gp = Salle.gp();
        if (gp == null) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, "atelier:cases=" + (oui ? "1:" + hauteurFantome : "0"), 0, 0, 0, -1));
    }

    private static void redessiner() {
        if (actif) GrilleVue.imposer(planEtMarques());
        surChangement.run();
    }

    /** « plan|marques » pour la grille du jeu (lignes separees par « / »). */
    static synchronized String planEtMarques() {
        FloorModele b = base, t = travail;
        if (b == null || t == null) return "";
        int w = Math.min(MAX, Math.max(b.largeur, t.largeur) + MARGE);
        int l = Math.min(MAX, Math.max(b.longueur, t.longueur) + MARGE);
        StringBuilder plan = new StringBuilder(), marques = new StringBuilder();
        for (int y = 0; y < l; y++) {
            if (y > 0) { plan.append('/'); marques.append('/'); }
            for (int x = 0; x < w; x++) {
                int a = b.at(x, y), n = t.at(x, y);
                char m;
                int h;
                if (n >= 0) {
                    h = n;
                    m = estPorte(t, x, y) ? 'p' : a < 0 ? 'a' : a != n ? 'h' : '.';
                } else if (a >= 0) { h = a; m = 'r'; }
                else { h = hauteurFantome; m = 'g'; }
                plan.append(FloorModele.car(Math.min(35, h)));
                marques.append(m);
            }
        }
        return plan + "|" + marques;
    }

    /** La hauteur la plus frequente du sol (celle des cases fantomes). */
    private static int hauteurCourante(FloorModele m) {
        int[] n = new int[36];
        for (int x = 0; x < m.largeur; x++) for (int y = 0; y < m.longueur; y++) {
            int v = m.h[x][y];
            if (v >= 0 && v < 36) n[v]++;
        }
        int best = 0;
        for (int i = 1; i < 36; i++) if (n[i] > n[best]) best = i;
        return Math.min(best, FloorModele.HAUTEUR_MAX);
    }

    // -------------------------------------------------------- appliquer

    /** Envoie le travail en un seul changement de floor (hors fil JavaFX). */
    static void appliquer() {
        if (occupe || travail == null) return;
        int[] c = changements();
        if (c[0] + c[1] + c[2] + c[3] == 0) { Journal.erreur("Rien à appliquer : le floor n'a pas changé."); return; }
        FloorModele m = rogne(travail);
        if (!m.porteConnue || !m.existe(m.porteX, m.porteY)) {
            Journal.erreur("Porte inconnue ou hors du sol : place-la avec l'outil Porte avant d'appliquer.");
            return;
        }
        // une case retiree a-t-elle recu un mobi entre-temps ?
        for (int x = 0; x < base.largeur; x++) for (int y = 0; y < base.longueur; y++)
            if (base.existe(x, y) && !m.existe(x, y) && occupee(x, y)) {
                Journal.erreur("Case " + x + "," + y + " occupée par un mobi : enlève-le d'abord.");
                return;
            }
        FloorModele avant = base;
        String bilan = Ui.accorder(c[0] + " case(s) ajoutée(s), " + c[1] + " retirée(s), " + c[2] + " hauteur(s) changée(s)");
        if (envoyer(m, bilan)) {
            precedent = avant;
            synchronized (ModeCases.class) { base = m; travail = m.copie(); annuler.clear(); retablir.clear(); }
            redessiner();
        }
    }

    /** Sans les colonnes / rangees vides de la fin (le jeu ne les renvoie pas). */
    private static FloorModele rogne(FloorModele t) {
        int w = 1, l = 1;
        for (int x = 0; x < t.largeur; x++) for (int y = 0; y < t.longueur; y++)
            if (t.h[x][y] >= 0) { w = Math.max(w, x + 1); l = Math.max(l, y + 1); }
        FloorModele m = agrandi(new FloorModele(w, l), w, l);
        for (int x = 0; x < w; x++) for (int y = 0; y < l; y++) m.h[x][y] = t.at(x, y);
        m.porteX = t.porteX; m.porteY = t.porteY; m.porteDir = t.porteDir; m.porteConnue = t.porteConnue;
        m.hauteurMur = t.hauteurMur; m.epMur = t.epMur; m.epSol = t.epSol; m.epConnues = t.epConnues;
        m.echelle = t.echelle;
        return m;
    }

    /** Remet le floor d'avant le dernier « Appliquer ». */
    static void revenir() {
        FloorModele p = precedent;
        if (p == null || occupe) return;
        if (envoyer(p, "Floor d'avant remis")) {
            synchronized (ModeCases.class) { base = p; travail = p.copie(); annuler.clear(); retablir.clear(); }
            precedent = null;
            redessiner();
        }
    }

    private static boolean envoyer(FloorModele m, String bilan) {
        occupe = true;
        surChangement.run();
        try {
            long t0 = System.currentTimeMillis();
            InfoJeu.consigne("J'applique le floor : l'appart va se recharger…");
            if (!FloorReseau.envoyerPlan(m)) { Journal.erreur("Envoi du floor impossible."); return false; }
            for (int i = 0; i < 100; i++) {          // 15 s au plus
                Salle.sommeil(150);
                if (FloorReseau.erreurRecue > t0) {
                    Journal.erreur("Le jeu a refusé le floor (" + FloorReseau.erreur + ").");
                    return false;
                }
                game.FloorState e = Salle.etat();
                String p = e == null ? null : e.getRawFloorplan();
                FloorModele r = p == null ? null : FloorModele.depuisTexte(p);
                if (FloorReseau.planRecu > t0 && r != null && r.texte().equals(m.texte())) {
                    Journal.succes(bilan + ".");
                    return true;
                }
            }
            Journal.erreur("Le floor n'est pas revenu du jeu en 15 s (droits de l'appart ?).");
            return false;
        } finally {
            occupe = false;
            surChangement.run();
        }
    }
}

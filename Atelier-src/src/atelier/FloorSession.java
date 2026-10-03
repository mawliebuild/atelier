package atelier;

import game.FloorState;
import gearth.extensions.parsers.HFloorItem;

import javafx.beans.property.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Etat partage de l'editeur de floor : le plan d'origine (lu dans la salle),
 * le plan en cours d'edition, l'historique, l'outil choisi et les mobis.
 *
 * Les deux vues (volet de l'Atelier et fenetre « Agrandir ») travaillent sur
 * la meme session : elles restent donc synchronisees par construction.
 * Tout se manipule sur le fil JavaFX, sauf lire() qui se fait dans un fil.
 */
final class FloorSession {

    enum Outil {
        MONTER("Monter", "Monte la case d'un niveau (+1)."),
        DESCENDRE("Descendre", "Descend la case d'un niveau (−1, jamais sous 0)."),
        FIXER("Fixer à N", "Met la case à la hauteur N choisie."),
        AJOUTER("Ajouter", "Crée une case (hauteur N) là où il n'y en a pas."),
        SUPPRIMER("Supprimer", "Retire la case (vide, « x »)."),
        PORTE("Porte", "Clic = place la porte · clic sur la porte = la tourner."),
        PIPETTE("Pipette", "Prend la hauteur d'une case comme valeur N.");
        final String libelle, aide;
        Outil(String l, String a) { libelle = l; aide = a; }
    }

    /** Comment montrer les mobis sur le plan. */
    enum Affichage {
        SANS("Sans mobis", "Seulement le sol : pour voir le plan nu."),
        AVEC("Avec mobis", "Les mobis posés, comme dans la salle."),
        TRANSPARENTS("Mobis en transparence", "Les mobis en fantôme : on voit le sol dessous."),
        TOUCHES("Mobis touchés", "Seulement les mobis posés sur une case que tu changes.");
        final String libelle, aide;
        Affichage(String l, String a) { libelle = l; aide = a; }
    }

    /** Un mobi de sol, tel qu'il sera dessine. */
    static final class Mobi {
        int id, typeId, x, y, ex, ey, revision;
        double z, haut;
        String nom, classe;
        boolean wired;
    }

    /** Derniere session creee : l'apercu de la grille dans le jeu la lit. */
    static volatile FloorSession active;

    FloorSession() { active = this; }

    // --------------------------------------------------------------- etat

    FloorModele original, courant;
    int salleId = -1;
    List<Mobi> mobis = List.of();
    long lu = 0;

    final ObjectProperty<Outil> outil = new SimpleObjectProperty<>(Outil.MONTER);
    final IntegerProperty pinceau = new SimpleIntegerProperty(1);
    final IntegerProperty valeur = new SimpleIntegerProperty(1);
    final BooleanProperty rectangle = new SimpleBooleanProperty(false);
    final ObjectProperty<Affichage> affichage = new SimpleObjectProperty<>(Affichage.AVEC);
    /** Icones officielles des mobis posees sur leur bloc. */
    final BooleanProperty icones = new SimpleBooleanProperty(true);
    final DoubleProperty relief = new SimpleDoubleProperty(0.5);
    /** Texte libre de la ligne d'etat partagee. */
    final StringProperty etat = new SimpleStringProperty("");

    private final Deque<FloorModele> annuler = new ArrayDeque<>(), retablir = new ArrayDeque<>();
    private static final int HISTORIQUE = 200;
    private final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    void ecouter(Runnable r) { ecouteurs.add(r); }

    /** Dernier bilan calcule (a chaque changement). */
    Bilan bilan = new Bilan();

    /** Previent les vues et panneaux (fil JavaFX). */
    void change() {
        try { bilan = bilan(); } catch (Throwable t) { bilan = new Bilan(); }
        for (Runnable r : ecouteurs) { try { r.run(); } catch (Throwable ignored) { } }
    }

    boolean pret() { return courant != null; }

    boolean modifie() {
        return courant != null && original != null
                && (!courant.memeTaille(original) || courant.differences(original) > 0 || !courant.memeReglages(original));
    }

    // ------------------------------------------------------------ lecture

    /** Ce qui a ete lu dans la salle, a appliquer ensuite sur le fil FX. */
    static final class Lecture {
        FloorModele modele;
        List<Mobi> mobis;
        int salleId;
        String erreur;
    }

    /** Lit la salle courante (hors fil FX). Jamais d'exception. */
    static Lecture lire() {
        Lecture l = new Lecture();
        try {
            if (Salle.gp() == null) { l.erreur = "G-Presets n'est pas encore prêt."; return l; }
            FloorState s = Salle.etat();
            if (s == null) { l.erreur = "Pas dans une salle (ou plan pas encore reçu)."; return l; }
            try { l.salleId = s.getRoomId(); } catch (Throwable ignored) { }
            FloorModele m = null;
            try { m = FloorModele.depuisTexte(s.getRawFloorplan()); } catch (Throwable ignored) { }
            if (m == null) {
                // Repli : case par case.
                int w = s.getFloorplanWidth(), h = s.getFloorplanHeight();
                if (w > 0 && h > 0 && w <= 512 && h <= 512) {
                    m = new FloorModele(w, h);
                    for (int x = 0; x < w; x++)
                        for (int y = 0; y < h; y++) {
                            try { m.h[x][y] = FloorModele.hauteurDe(s.floorHeight(x, y)); } catch (Throwable ignored) { }
                        }
                }
            }
            if (m == null) { l.erreur = "Plan de la salle pas encore reçu : il arrive dans un instant."; return l; }
            try { m.hauteurMur = s.getFloorWallHeight(); } catch (Throwable ignored) { }
            try { m.echelle = s.getFloorScale(); } catch (Throwable ignored) { }
            if (FloorReseau.porteRecue > 0 && (FloorReseau.porteSalle == l.salleId || FloorReseau.porteSalle == -1)) {
                m.porteX = FloorReseau.porteX; m.porteY = FloorReseau.porteY; m.porteDir = FloorReseau.porteDir;
                m.porteConnue = true;
            }
            if (FloorReseau.visuRecue > 0) {
                m.epMur = FloorReseau.epMur; m.epSol = FloorReseau.epSol; m.epConnues = true;
            }
            l.modele = m;

            List<Mobi> ms = new ArrayList<>();
            Map<Integer, Boolean> wired = new HashMap<>();
            Map<Integer, String> noms = new HashMap<>();
            Map<Integer, String> classes = new HashMap<>();
            for (HFloorItem it : Salle.sols()) {
                try {
                    if (GrilleCalcul.estFictif(it.getId())) continue;   // marqueurs de la grille dans le jeu
                    Mobi o = new Mobi();
                    o.id = it.getId(); o.typeId = it.getTypeId();
                    o.x = it.getTile().getX(); o.y = it.getTile().getY(); o.z = it.getTile().getZ();
                    int[] e = Salle.emprise(it);
                    o.ex = e[0]; o.ey = e[1];
                    o.haut = Salle.hauteur(it);
                    o.wired = wired.computeIfAbsent(o.typeId, t -> Wired.estWired(Salle.classe(t, false)));
                    o.nom = noms.computeIfAbsent(o.typeId, t -> Salle.nom(t, false));
                    o.classe = classes.computeIfAbsent(o.typeId, t -> Salle.classe(t, false));
                    try {
                        furnidata.details.FloorItemDetails d = Salle.details(o.classe);
                        if (d != null) o.revision = d.revision;
                    } catch (Throwable ignored) { }
                    ms.add(o);
                } catch (Throwable ignored) { }
            }
            l.mobis = ms;
        } catch (Throwable t) {
            l.erreur = "Lecture impossible : " + t.getMessage();
        }
        return l;
    }

    /** Applique une lecture (fil FX). garderEdition : ne remplace que l'original et les mobis. */
    void charger(Lecture l, boolean garderEdition) {
        if (l == null || l.modele == null) return;
        boolean autreSalle = l.salleId != salleId;
        original = l.modele;
        mobis = l.mobis == null ? List.of() : l.mobis;
        salleId = l.salleId;
        lu = System.currentTimeMillis();
        if (!garderEdition || autreSalle || courant == null) {
            courant = original.copie();
            annuler.clear(); retablir.clear();
        }
        change();
    }

    /** Une porte / des epaisseurs sont arrivees apres la lecture : on complete. */
    void completer() {
        if (original == null) return;
        boolean c = false;
        if (FloorReseau.porteRecue > 0 && (FloorReseau.porteSalle == salleId || FloorReseau.porteSalle == -1)) {
            boolean courantSuivait = courant != null && (!courant.porteConnue
                    || (courant.porteX == original.porteX && courant.porteY == original.porteY && courant.porteDir == original.porteDir));
            if (!original.porteConnue || original.porteX != FloorReseau.porteX || original.porteY != FloorReseau.porteY
                    || original.porteDir != FloorReseau.porteDir) {
                original.porteX = FloorReseau.porteX; original.porteY = FloorReseau.porteY;
                original.porteDir = FloorReseau.porteDir; original.porteConnue = true;
                if (courantSuivait) {
                    courant.porteX = original.porteX; courant.porteY = original.porteY;
                    courant.porteDir = original.porteDir; courant.porteConnue = true;
                }
                c = true;
            }
        }
        if (FloorReseau.visuRecue > 0 && (!original.epConnues
                || original.epMur != FloorReseau.epMur || original.epSol != FloorReseau.epSol)) {
            boolean suivait = courant != null && (!courant.epConnues
                    || (courant.epMur == original.epMur && courant.epSol == original.epSol));
            original.epMur = FloorReseau.epMur; original.epSol = FloorReseau.epSol; original.epConnues = true;
            if (suivait) { courant.epMur = original.epMur; courant.epSol = original.epSol; courant.epConnues = true; }
            c = true;
        }
        if (c) change();
    }

    // ---------------------------------------------------------- historique

    /** A appeler AVANT une modification (une fois par trait de pinceau). */
    void memoriser() {
        if (courant == null) return;
        annuler.push(courant.copie());
        while (annuler.size() > HISTORIQUE) annuler.removeLast();
        retablir.clear();
    }

    /** Retire le dernier instantane si le trait n'a rien change. */
    void oublierSiInutile() {
        FloorModele d = annuler.peek();
        if (d != null && courant != null && d.memeTaille(courant) && d.differences(courant) == 0
                && d.memeReglages(courant)) annuler.pop();
    }

    boolean peutAnnuler() { return !annuler.isEmpty(); }
    boolean peutRetablir() { return !retablir.isEmpty(); }

    void annuler() {
        if (annuler.isEmpty() || courant == null) return;
        retablir.push(courant);
        courant = annuler.pop();
        etat.set("Annulé.");
        change();
    }

    void retablir() {
        if (retablir.isEmpty() || courant == null) return;
        annuler.push(courant);
        courant = retablir.pop();
        etat.set("Rétabli.");
        change();
    }

    void revenir() {
        if (original == null) return;
        memoriser();
        courant = original.copie();
        etat.set("Retour au plan d'origine (Annuler pour reprendre tes changements).");
        change();
    }

    // ------------------------------------------------------------- edition

    /** Cases couvertes par le pinceau autour de (x, y). */
    List<int[]> pinceau(int x, int y) {
        int n = Math.max(1, Math.min(3, pinceau.get()));
        int d0 = n == 3 ? -1 : 0, d1 = n == 3 ? 1 : n - 1;
        List<int[]> l = new ArrayList<>();
        for (int dx = d0; dx <= d1; dx++) for (int dy = d0; dy <= d1; dy++) l.add(new int[]{x + dx, y + dy});
        return l;
    }

    /**
     * Applique l'outil sur une case. dejaVu evite de monter deux fois la meme
     * case pendant un meme trait. Renvoie vrai si quelque chose a change.
     */
    boolean appliquer(int x, int y, Set<Long> dejaVu) {
        FloorModele m = courant;
        if (m == null || !m.dansPlan(x, y)) return false;
        if (dejaVu != null && !dejaVu.add(FloorReseau.cle(x, y))) return false;
        int v = m.h[x][y];
        switch (outil.get()) {
            case MONTER:    return v >= 0 && m.poser(x, y, Math.min(FloorModele.HAUTEUR_MAX, v + 1));
            case DESCENDRE: return v > 0 && m.poser(x, y, v - 1);
            case FIXER:     return v >= 0 && m.poser(x, y, valeur.get());
            case AJOUTER:   return v < 0 && m.poser(x, y, valeur.get());
            case SUPPRIMER: return v >= 0 && m.poser(x, y, -1);
            default:        return false;
        }
    }

    /** Clic de l'outil Porte ou Pipette (une seule case). */
    boolean cliquer(int x, int y) {
        FloorModele m = courant;
        if (m == null || !m.dansPlan(x, y)) return false;
        if (outil.get() == Outil.PIPETTE) {
            int v = m.h[x][y];
            if (v < 0) { etat.set("Pas de case ici : rien à prendre."); return false; }
            valeur.set(Math.min(FloorModele.HAUTEUR_MAX, v));
            outil.set(Outil.FIXER);
            etat.set("Hauteur " + v + " prise : outil « Fixer à N » choisi.");
            return false;
        }
        if (outil.get() == Outil.PORTE) {
            memoriser();
            if (m.porteConnue && m.porteX == x && m.porteY == y) {
                m.porteDir = (m.porteDir + 1) % 8;
                etat.set("Porte tournée : direction " + FloorModele.direction(m.porteDir) + ".");
            } else {
                m.porteX = x; m.porteY = y; m.porteConnue = true;
                etat.set("Porte placée en (" + x + ", " + y + ")" + (m.h[x][y] < 0 ? " — attention, pas de case ici !" : "."));
            }
            change();
            return true;
        }
        return false;
    }

    void agrandir(int bord) {
        if (courant == null) return;
        if (courant.largeur >= 128 && (bord == 0 || bord == 2) || courant.longueur >= 128 && (bord == 1 || bord == 3)) {
            etat.set("Le plan est déjà très grand : agrandissement refusé.");
            return;
        }
        memoriser();
        courant = courant.agrandi(bord);
        etat.set(bord >= 2 ? "Rangée ajoutée au début : toutes les cases (et la porte) sont décalées d'un cran ; les mobis, eux, ne bougent pas."
                : "Rangée vide ajoutée. Utilise « Ajouter » pour y créer des cases.");
        change();
    }

    void rogner(boolean debutAussi) {
        if (courant == null) return;
        FloorModele r = courant.rogne(debutAussi);
        if (r == courant) { etat.set("Rien à rogner."); return; }
        memoriser();
        courant = r;
        etat.set("Plan rogné à " + r.largeur + " × " + r.longueur + ".");
        change();
    }

    void reglerMur(int hauteur) {
        if (courant == null || courant.hauteurMur == hauteur) return;
        memoriser(); courant.hauteurMur = hauteur; change();
    }

    void reglerEpaisseurs(int mur, int sol) {
        if (courant == null || (courant.epMur == mur && courant.epSol == sol && courant.epConnues)) return;
        memoriser(); courant.epMur = mur; courant.epSol = sol; courant.epConnues = true; change();
    }

    void reglerDirection(int d) {
        if (courant == null || courant.porteDir == d) return;
        memoriser(); courant.porteDir = ((d % 8) + 8) % 8; change();
    }

    // --------------------------------------------------------- verification

    /** Resume des changements et avertissements. */
    static final class Bilan {
        int casesModifiees, ajoutees, supprimees, changees;
        int mobisSurSupprimees, mobisHauteurChangee, occupeesSupprimees;
        final List<String> avertissements = new ArrayList<>();
        final List<String> erreurs = new ArrayList<>();
        final Set<Integer> mobisEnDanger = new HashSet<>();
    }

    Bilan bilan() {
        Bilan b = new Bilan();
        FloorModele c = courant, o = original;
        if (c == null) return b;
        int W = Math.max(c.largeur, o == null ? 0 : o.largeur), L = Math.max(c.longueur, o == null ? 0 : o.longueur);
        for (int x = 0; x < W; x++)
            for (int y = 0; y < L; y++) {
                int a = FloorModele.ancien(o, x, y), n = c.at(x, y);
                if (a == n) continue;
                b.casesModifiees++;
                if (a < 0) b.ajoutees++; else if (n < 0) b.supprimees++; else b.changees++;
            }
        for (Mobi m : mobis) {
            boolean surVide = false, change = false;
            for (int dx = 0; dx < m.ex; dx++)
                for (int dy = 0; dy < m.ey; dy++) {
                    int x = m.x + dx, y = m.y + dy;
                    int a = FloorModele.ancien(o, x, y), n = c.at(x, y);
                    if (n < 0) surVide = true;
                    else if (a != n) change = true;
                }
            if (surVide) { b.mobisSurSupprimees++; b.mobisEnDanger.add(m.id); }
            else if (change) { b.mobisHauteurChangee++; b.mobisEnDanger.add(m.id); }
        }
        for (long k : FloorReseau.occupees) {
            int x = (int) (k >> 32), y = (int) k;
            if (FloorModele.ancien(o, x, y) >= 0 && c.at(x, y) < 0) b.occupeesSupprimees++;
        }

        int nb = c.nbCases();
        if (nb == 0) b.erreurs.add("Le plan n'a plus aucune case.");
        if (!c.porteConnue) b.erreurs.add("Porte inconnue : place-la avec l'outil Porte (elle est aussi demandée au serveur).");
        else if (!c.existe(c.porteX, c.porteY)) b.erreurs.add("La porte (" + c.porteX + ", " + c.porteY + ") est sur une case inexistante.");
        else {
            boolean bloquee = false;
            for (Mobi m : mobis)
                if (c.porteX >= m.x && c.porteX < m.x + m.ex && c.porteY >= m.y && c.porteY < m.y + m.ey) { bloquee = true; break; }
            if (!bloquee && FloorReseau.occupees.contains(FloorReseau.cle(c.porteX, c.porteY))) bloquee = true;
            if (bloquee) b.avertissements.add("Un mobi est posé sur la case de la porte : les avatars pourraient rester bloqués à l'entrée.");
            int[] v = FloorModele.vecteur(c.porteDir);
            if (!c.existe(c.porteX + v[0], c.porteY + v[1]))
                b.avertissements.add("La porte regarde vers une case vide (direction " + FloorModele.direction(c.porteDir) + ").");
        }
        if (b.mobisSurSupprimees > 0)
            b.avertissements.add(b.mobisSurSupprimees + (b.mobisSurSupprimees > 1 ? " mobis sont posés" : " mobi est posé")
                    + " sur une case supprimée : ils seront renvoyés dans l'inventaire ou resteront bloqués.");
        else if (b.occupeesSupprimees > 0)
            b.avertissements.add(b.occupeesSupprimees + " case(s) occupée(s) selon le serveur vont disparaître.");
        if (b.mobisHauteurChangee > 0)
            b.avertissements.add(b.mobisHauteurChangee + (b.mobisHauteurChangee > 1 ? " mobis sont posés" : " mobi est posé")
                    + " sur une case dont la hauteur change : ils peuvent flotter ou s'enfoncer.");
        // Limites : 64 x 64 pour l'editeur du jeu (non verifie sur habbo.fr ; les
        // emulateurs refusent au-dela). Signale sans bloquer.
        if (c.largeur > 64 || c.longueur > 64)
            b.avertissements.add("Plan de " + c.largeur + " × " + c.longueur + " : au-delà de 64 × 64, Habbo refusera sans doute.");
        if (nb > 4096)
            b.avertissements.add(nb + " cases : probablement au-delà de la limite de Habbo.");
        if (!c.epConnues) b.avertissements.add("Épaisseurs des murs / du sol pas encore reçues : 0 (normal) sera envoyé.");
        return b;
    }
}

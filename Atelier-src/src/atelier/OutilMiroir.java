package atelier;

import extension.GPresets;
import game.FloorState;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.scene.control.*;

import java.util.*;

/**
 * Miroir : retourne les mobis de la zone, gauche↔droite ou haut↔bas.
 *
 * Geometrie. Un mobi occupe [x, x+lx-1] × [y, y+ly-1] depuis sa case d'origine
 * (le coin x min, y min). Son image par la symetrie d'axe vertical autour du
 * centre de la zone (S = minX + maxX) occupe [S-(x+lx-1), S-x] : la nouvelle
 * origine est donc S - x - lx + 1 — un 2×1 reste ainsi dans la zone.
 *
 * Rotations Habbo : 0 = N, 2 = E, 4 = S, 6 = O, impaires = diagonales.
 *   gauche↔droite (x → -x) : E↔O, N et S inchanges  →  r' = (8 - r) mod 8
 *   haut↔bas      (y → -y) : N↔S, E et O inchanges  →  r' = (4 - r) mod 8
 * Les deux gardent l'axe du mobi (2↔6, 0↔4) : l'emprise ne change pas. Un mobi
 * a deux rotations seulement (0 et 2) n'accepte pas 6 ou 4 : la troisieme passe
 * reessaie alors avec la rotation equivalente sur le meme axe (r' + 4).
 *
 * Deplacer avec MoveObject pose le mobi sur le dessus de la pile de la case
 * d'arrivee : les hauteurs empilees sont ensuite remises par la variable
 * wired @altitude, si elle est connue.
 */
public class OutilMiroir {

    private RadioButton axeX, axeY;
    private CheckBox copie, chercherAlt;
    private Spinner<Integer> ecartCopie;
    private Generateur.ChoixSource source;
    private Label apercu, altLbl, etat;
    private Button lancer;
    private volatile boolean enCours = false;

    /** Ce qu'un mobi devient. */
    private static final class Cible {
        final HFloorItem it;
        final int x, y, rot, rotAvant, lx, ly;
        final double zSol;          // altitude d'origine au-dessus du sol
        final boolean horsZone;
        Cible(HFloorItem it, int x, int y, int rot, int rotAvant, int lx, int ly, double zSol, boolean horsZone) {
            this.it = it; this.x = x; this.y = y; this.rot = rot; this.rotAvant = rotAvant;
            this.lx = lx; this.ly = ly; this.zSol = zSol; this.horsZone = horsZone;
        }
        boolean immobile() {
            return x == it.getTile().getX() && y == it.getTile().getY() && rot == rotAvant;
        }
    }

    public Tab construire() {
        etat = Ui.etat();
        ToggleGroup g = new ToggleGroup();
        axeX = Generateur.radio("Gauche ↔ droite (axe x)", g, true);
        axeY = Generateur.radio("Haut ↔ bas (axe y)", g, false);

        copie = new CheckBox("Copie miroir à côté (ne touche pas l'original)");
        ecartCopie = Generateur.entier(0, 20, 1);
        source = new Generateur.ChoixSource();
        source.bloc().disableProperty().bind(copie.selectedProperty().not());
        ecartCopie.disableProperty().bind(copie.selectedProperty().not());

        chercherAlt = new CheckBox("Chercher @altitude si elle est inconnue");
        chercherAlt.setSelected(true);
        altLbl = Ui.valeur(Altitude.texte());
        altLbl.setWrapText(true);
        Altitude.installer();
        Altitude.ecouter(() -> Platform.runLater(() -> altLbl.setText(Altitude.texte())));

        apercu = Ui.valeur("—");
        apercu.setWrapText(true);
        // L'apercu suit la salle tout seul (mobis poses ou bouges dans la zone).
        javafx.animation.Timeline suivi = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> { if (Zone.definie()) majApercu(); }));
        suivi.setCycleCount(javafx.animation.Animation.INDEFINITE);
        suivi.play();

        Zone.ecouter(this::majApercu);
        g.selectedToggleProperty().addListener((o, a, b) -> majApercu());
        copie.selectedProperty().addListener((o, a, b) -> {
            lancer.setText(b ? "Poser la copie miroir" : "Appliquer le miroir");
            majApercu();
        });

        lancer = Generateur.principal("Appliquer le miroir", this::lancer);

        Tab t = new Tab("Miroir", Generateur.defiler(
                Zone.bloc(),
                Ui.bloc("Symétrie", Ui.ligne(axeX, axeY),
                        Ui.aide("Autour du centre de la zone. Les rotations suivent "
                                + "(est ↔ ouest, ou nord ↔ sud).")),
                Ui.bloc("Copie", copie, Ui.ligne(Ui.etiquette("Écart (cases)"), ecartCopie),
                        Ui.aide("La copie est générée comme appart temporaire et posée par "
                                + "l'Atelier à droite de la zone (axe x) ou en dessous (axe y). "
                                + "Pas de dalle magique dans la salle ? Je la pose à côté, "
                                + "puis je la ramasse à la fin.")),
                source.bloc(),
                Ui.bloc("Hauteurs", altLbl, chercherAlt,
                        Ui.aide("Un mobi déplacé se pose sur le dessus de la pile : les hauteurs "
                                + "d'origine sont remises par la variable @altitude.")),
                Ui.bloc("Aperçu", apercu),
                lancer,
                etat));
        t.setClosable(false);
        majApercu();
        return t;
    }

    // ------------------------------------------------------------ calcul

    static int miroirRot(int r, boolean surX) {
        r &= 7;
        return surX ? (8 - r) & 7 : (12 - r) & 7;
    }

    private List<Cible> calculer(boolean surX) {
        if (!Zone.definie()) return new ArrayList<>();
        return calculer(Zone.mobis(), Zone.minX(), Zone.minY(), Zone.maxX(), Zone.maxY(), surX);
    }

    /** Symetrie des mobis dans le cadre [minX, maxX] × [minY, maxY]. */
    static List<Cible> calculer(Collection<HFloorItem> mobis, int minX, int minY, int maxX, int maxY, boolean surX) {
        List<Cible> r = new ArrayList<>();
        int sx = minX + maxX, sy = minY + maxY;
        for (HFloorItem it : mobis) {
            int rot = Salle.rotation(it);
            int rot2 = miroirRot(rot, surX);
            int[] e = Salle.emprise(it);
            // emprise sans rotation, pour la recalculer avec la nouvelle
            int bx = (rot == 2 || rot == 6) ? e[1] : e[0], by = (rot == 2 || rot == 6) ? e[0] : e[1];
            int lx = (rot2 == 2 || rot2 == 6) ? by : bx, ly = (rot2 == 2 || rot2 == 6) ? bx : by;
            int x = it.getTile().getX(), y = it.getTile().getY();
            int nx = surX ? sx - x - e[0] + 1 : x;
            int ny = surX ? y : sy - y - e[1] + 1;
            boolean hors = nx < minX || ny < minY || nx + lx - 1 > maxX || ny + ly - 1 > maxY;
            int sol = Math.max(0, Salle.hauteurSol(x, y));
            double z = Math.max(0, it.getTile().getZ() - sol);
            r.add(new Cible(it, nx, ny, rot2, rot, lx, ly, z, hors));
        }
        return r;
    }

    private void majApercu() {
        if (apercu == null) return;
        if (!Zone.definie()) { apercu.setText("Choisis d'abord la zone."); return; }
        if (!Salle.dansUneSalle()) { apercu.setText("Tu n'es pas dans une salle."); return; }
        List<Cible> c = calculer(axeX.isSelected());
        if (c.isEmpty()) { apercu.setText("Aucun mobi dans la zone."); return; }
        int bougent = 0, hors = 0, empiles = 0, wired = 0;
        for (Cible k : c) {
            if (!k.immobile()) bougent++;
            if (k.horsZone) hors++;
            if (k.zSol > 0.01) empiles++;
            if (Wired.estWired(Salle.classe(k.it.getTypeId(), false))) wired++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(c.size()).append(" mobi(s) dans la zone");
        if (!copie.isSelected()) sb.append(", ").append(bougent).append(" à déplacer");
        if (hors > 0) sb.append("\n⚠ ").append(hors).append(" mobi(s) dépasseraient de la zone après symétrie "
                + "(ils débordaient déjà de la zone).");
        if (empiles > 0 && !copie.isSelected())
            sb.append("\n").append(empiles).append(" mobi(s) surélevé(s) : ")
              .append(Altitude.connue() ? "hauteurs remises par @altitude."
                      : "hauteurs remises seulement si @altitude est trouvée.");
        if (copie.isSelected() && wired > 0)
            sb.append("\n").append(wired).append(" wired ignoré(s) par la copie (sans leur réglage).");
        apercu.setText(sb.toString());
    }

    // ------------------------------------------------------------ action

    private void lancer() {
        if (enCours) { refus("Un miroir est déjà en cours."); return; }
        if (Salle.gp() == null) { refus("L'Atelier n'est pas encore prêt."); return; }
        if (!Salle.dansUneSalle()) { refus("Tu n'es pas dans une salle."); return; }
        if (!Zone.definie()) { refus("Choisis d'abord la zone."); return; }
        boolean surX = axeX.isSelected();
        List<Cible> c = calculer(surX);
        if (c.isEmpty()) { refus("Aucun mobi dans la zone."); return; }
        if (copie.isSelected()) { copier(c, surX); return; }

        boolean chercher = chercherAlt.isSelected();
        enCours = true;
        lancer.setDisable(true);
        Salle.tache("miroir", () -> {
            try { deplacer(c, chercher, s -> Generateur.dire(etat, s)); }
            finally {
                enCours = false;
                Platform.runLater(() -> { lancer.setDisable(false); majApercu(); });
            }
        });
    }

    /** Copie miroir : un preset genere, pose a cote de la zone. */
    private void copier(List<Cible> c, boolean surX) {
        Generateur.prendre(ecartCopie);
        List<Generateur.Mobi> m = new ArrayList<>();
        int ignores = 0;
        for (Cible k : c) {
            String cls = Salle.classe(k.it.getTypeId(), false);
            if (cls == null || Wired.estWired(cls)) { ignores++; continue; }
            m.add(new Generateur.Mobi(cls, Generateur.etatDe(k.it),
                    k.x - Zone.minX(), k.y - Zone.minY(), k.zSol, k.rot));
        }
        if (m.isEmpty()) { refus("Rien à copier (classes inconnues ou wired seulement)."); return; }
        int ec = ecartCopie.getValue();
        HPoint racine = surX ? new HPoint(Zone.maxX() + 1 + ec, Zone.minY())
                             : new HPoint(Zone.minX(), Zone.maxY() + 1 + ec);
        Generateur.Source src = source.source();
        // les ignores sont dits des le depart : le resultat de la pose reste un seul message
        etat.setText("Préparation de la copie miroir (" + m.size() + " mobis"
                + (ignores > 0 ? ", " + ignores + " wired ou inconnu(s) laissé(s) de côté" : "") + ")...");
        enCours = true;
        lancer.setDisable(true);
        Salle.tache("miroir-copie", () -> {
            try { Generateur.poser("_atelier_miroir", m, src, racine, s -> Generateur.dire(etat, s)); }
            finally {
                enCours = false;
                Platform.runLater(() -> { lancer.setDisable(false); majApercu(); });
            }
        });
    }

    /**
     * Retourne SUR PLACE les mobis de sol donnes (la selection des calques),
     * dans le cadre qui les contient. Hors fil JavaFX. Les muraux sont ignores.
     */
    static void surPlace(Collection<HFloorItem> mobis, boolean surX, java.util.function.Consumer<String> dire) {
        if (mobis.isEmpty()) { dire.accept("Aucun mobi de sol à retourner."); return; }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (HFloorItem it : mobis) {
            int[] e = Salle.emprise(it);
            int x = it.getTile().getX(), y = it.getTile().getY();
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + e[0] - 1); maxY = Math.max(maxY, y + e[1] - 1);
        }
        Altitude.installer();
        deplacer(calculer(mobis, minX, minY, maxX, maxY, surX), true, dire);
    }

    /** Deplacements en place : deux passes, puis la rotation equivalente, puis les hauteurs. */
    private static void deplacer(List<Cible> toutes, boolean chercher, java.util.function.Consumer<String> dire) {
        List<Cible> reste = new ArrayList<>();
        for (Cible k : toutes) if (!k.immobile()) reste.add(k);
        // du bas vers le haut : un mobi empile se repose sur celui du dessous
        reste.sort(Comparator.comparingDouble((Cible k) -> k.it.getTile().getZ()).thenComparingInt(k -> k.it.getId()));
        if (reste.isEmpty()) { dire.accept("Rien à déplacer : la zone est déjà symétrique."); return; }

        for (int passe = 1; passe <= 3 && !reste.isEmpty(); passe++) {
            dire.accept("Passe " + passe + " : " + reste.size() + " mobi(s) à déplacer...");
            for (Cible k : reste) {
                int rot = (passe == 3 && k.rot != k.rotAvant) ? (k.rot + 4) & 7 : k.rot;
                Salle.deplacerSol(k.it.getId(), k.x, k.y, rot);
                Salle.sommeil(200);
            }
            Salle.sommeil(900);
            List<Cible> encore = new ArrayList<>();
            for (Cible k : reste) if (!arrive(k)) encore.add(k);
            reste = encore;
        }
        int bloques = reste.size();

        // les hauteurs
        Salle.sommeil(400);
        List<Cible> aRegler = new ArrayList<>();
        for (Cible k : toutes) {
            if (reste.contains(k)) continue;
            HFloorItem now = Salle.sol(k.it.getId());
            if (now == null) continue;
            double voulu = Math.max(0, Salle.hauteurSol(now.getTile().getX(), now.getTile().getY())) + k.zSol;
            if (Math.abs(now.getTile().getZ() - voulu) > 0.05) aRegler.add(k);
        }
        String hauteurs = "";
        if (!aRegler.isEmpty()) {
            if (!Altitude.connue() && chercher) {
                dire.accept("Recherche de @altitude...");
                Cible k = aRegler.get(0);
                Altitude.chercher(k.it.getId(), voulu(k));
            }
            if (Altitude.connue()) {
                int faux = 0;
                for (int passe = 1; passe <= 2; passe++) {
                    dire.accept("Hauteurs : passe " + passe + ", " + aRegler.size() + " mobi(s)...");
                    for (Cible k : aRegler) { Altitude.ecrire(k.it.getId(), voulu(k)); Salle.sommeil(200); }
                    Salle.sommeil(800);
                    List<Cible> encore = new ArrayList<>();
                    for (Cible k : aRegler) {
                        HFloorItem now = Salle.sol(k.it.getId());
                        if (now == null || Math.abs(now.getTile().getZ() - voulu(k)) > 0.05) encore.add(k);
                    }
                    aRegler = encore;
                    if (aRegler.isEmpty()) break;
                }
                faux = aRegler.size();
                hauteurs = faux == 0 ? " Hauteurs remises." : " ⚠ " + faux + " hauteur(s) pas remise(s).";
            } else {
                hauteurs = " ⚠ " + aRegler.size() + " mobi(s) ont changé de hauteur (posés sur le dessus "
                        + "de la pile) : @altitude inconnue — règle-la une fois dans l'éditeur :wired, ou "
                        + "coche « Chercher @altitude ».";
            }
        }
        // un seul message de resultat : la ligne d'etat (Ui.etat) le passe au Journal
        dire.accept("Miroir terminé : " + (toutes.size() - bloques) + " mobi(s) en place"
                + (bloques > 0 ? ", " + bloques + " bloqué(s) (case occupée, échange de place "
                        + "entre mobis non empilables ?) : à finir à la main." : ".")
                + hauteurs);
    }

    /** Altitude voulue a l'arrivee : sol de la case d'arrivee + hauteur d'origine au-dessus du sol. */
    /** Refus avant de commencer : ligne d'etat + Journal (une fois). */
    private void refus(String s) {
        etat.setText(s);
        if (Journal.genre(s) != Journal.Genre.ERREUR) Journal.erreur(s);
    }

    private static double voulu(Cible k) {
        return Generateur.arrondi(Math.max(0, Salle.hauteurSol(k.x, k.y)) + k.zSol);
    }

    private static boolean arrive(Cible k) {
        HFloorItem now = Salle.sol(k.it.getId());
        return now != null && now.getTile().getX() == k.x && now.getTile().getY() == k.y;
    }

    // ------------------------------------------------------------ @altitude

    /**
     * La variable wired @altitude, inscriptible avec
     * WiredSetObjectVariableValue(int 0, int idMobi, String idVariable, int valeur).
     *
     * Son identifiant n'est expose nulle part : OngletWired l'apprend pour lui
     * seul (champ prive). On l'apprend donc ici de la meme facon, en observant
     * un reglage fait dans l'editeur :wired, ou en essayant les identifiants
     * -100..-140 sur un mobi a remettre (la valeur voulue est ecrite, donc un
     * essai reussi est deja la correction).
     */
    static final class Altitude {
        private static final java.util.prefs.Preferences PREFS =
                java.util.prefs.Preferences.userRoot().node("atelier");
        /** Retenue d'une session a l'autre (la meme dans toutes les salles). */
        private static volatile String variable = PREFS.get("altitude.variable", null);
        /** Vue marcher sur un mobi pendant cette session. */
        private static volatile boolean confirmee = false;
        private static volatile int facteur = 100;
        private static volatile boolean branche = false, enCours = false;
        /** L'essai a l'aveugle (-100..-140) a deja echoue : on ne le refait pas sur d'autres mobis. */
        private static volatile boolean essaiRate = false;
        private static final List<Runnable> ecouteurs = new java.util.concurrent.CopyOnWriteArrayList<>();

        static boolean connue() { return variable != null; }

        private static void retenir(String var) {
            variable = var; facteur = 100; confirmee = true;
            try { PREFS.put("altitude.variable", var); } catch (Throwable ignored) { }
            prevenir();
        }

        /**
         * Liste des variables envoyee par le jeu (WiredAllVariablesDiffs,
         * internes comprises) : id -> nom. @altitude y est lue directement.
         */
        static void depuisListe(Map<String, String> idVersNom) {
            for (Map.Entry<String, String> e : idVersNom.entrySet()) {
                String n = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT);
                if (n.equals("@altitude") || n.equals("altitude")) {
                    if (!e.getKey().equals(variable) || !confirmee) {
                        Journal.debug("@altitude lue dans la liste : variable " + e.getKey());
                        retenir(e.getKey());
                    }
                    return;
                }
            }
        }

        /** Demande la liste des variables au jeu et attend @altitude (au plus ~1,5 s). */
        static boolean demanderListe() {
            if (confirmee) return true;
            Salle.envoyer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
            for (int i = 0; i < 25 && !confirmee; i++) Salle.sommeil(60);
            return confirmee;
        }

        /**
         * Donne l'altitude au mobi en s'assurant d'abord, une fois par session,
         * que la variable retenue marche ; sinon la retrouve (liste du jeu, puis essais).
         */
        static void mettre(int idMobi, double z) {
            if (!confirmee) demanderListe();
            if (variable != null && confirmee) { ecrire(idMobi, z); return; }
            if (variable != null) {
                ecrire(idMobi, z);
                for (int i = 0; i < 8; i++) {
                    Salle.sommeil(60);
                    HFloorItem it = Salle.sol(idMobi);
                    if (it != null && Math.abs(it.getTile().getZ() - z) < 0.05) { confirmee = true; return; }
                }
                variable = null;                  // retenue mais fausse : on cherche
            }
            chercher(idMobi, z);
        }

        static String variable() { return variable; }

        /** Appele par OngletWired quand il a appris @altitude de son cote. */
        static void apprendre(String var, int fact) {
            if (var == null || confirmee) return;
            retenir(var);                       // toujours en centiemes (voir OngletWired)
        }

        static String texte() {
            return variable == null ? "@altitude pas encore connue"
                    : "@altitude connue — variable « " + variable + " »";
        }

        static void ecouter(Runnable r) { ecouteurs.add(r); }

        private static void prevenir() { for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { } }

        static void ecrire(int idMobi, double z) {
            String v = variable;
            if (v == null) return;
            Salle.envoyer(new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER,
                    0, idMobi, v, (int) Math.round(Math.max(0, z) * facteur)));
        }

        /** Essaie les identifiants sur un mobi ; garde celui qui le met a la hauteur voulue. */
        static boolean chercher(int idMobi, double voulu) {
            HFloorItem it = Salle.sol(idMobi);
            if (it == null) return false;
            if (demanderListe()) { ecrire(idMobi, voulu); return true; }
            if (essaiRate) return false;
            variable = null;
            Journal.debug("@altitude absente de la liste du jeu : essai des variables -100 à -140 sur le mobi " + idMobi + ".");
            for (int v = -100; v >= -140 && variable == null; v--) {
                String cand = String.valueOf(v);
                Salle.envoyer(new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER,
                        0, idMobi, cand, (int) Math.round(Math.max(0, voulu) * 100)));
                Salle.sommeil(260);
                HFloorItem now = Salle.sol(idMobi);
                if (now != null && Math.abs(now.getTile().getZ() - voulu) < 0.05) {
                    Journal.debug("miroir : @altitude = variable " + cand);
                    retenir(cand);
                    return true;
                }
            }
            if (variable == null) {
                essaiRate = true;
                Journal.erreur("@altitude introuvable : règle-la une fois dans l'éditeur :wired, puis recommence.");
            }
            return variable != null;
        }

        /** Ecoute passive des reglages faits dans l'editeur :wired. */
        static synchronized void installer() {
            if (branche || enCours) return;
            enCours = true;
            Thread t = new Thread(() -> {
                for (int i = 0; i < 900 && !branche; i++) {
                    GPresets gp = Salle.gp();
                    if (gp != null) {
                        try {
                            gp.intercept(HMessage.Direction.TOSERVER, m -> {
                                try { apprendre(m); } catch (Throwable ignored) { }
                            });
                            branche = true;
                            return;
                        } catch (Throwable ignored) { }
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }, "atelier-miroir-altitude");
            t.setDaemon(true);
            t.start();
        }

        /** Forme (int 0, int idMobi, String "-nnn", int), rien apres. */
        private static void apprendre(HMessage m) {
            if (confirmee) return;
            int taille = m.getPacket().getBytesLength();
            if (taille < 18 || taille > 40) return;
            FloorState s = Salle.etat();
            if (s == null) return;
            HPacket p = new HPacket(m.getPacket());
            p.resetReadIndex();
            if (p.readInteger() != 0) return;
            int id = p.readInteger();
            String var = p.readString();
            int valeur = p.readInteger();
            if (p.getReadIndex() != p.getBytesLength()) return;
            if (var == null || !var.matches("-?\\d{1,6}")) return;
            if (s.furniFromId(id) == null) return;
            // N'importe quelle variable numerique peut avoir cette forme : on ne
            // la retient que si le mobi arrive vraiment a l'altitude valeur/100.
            final double voulu = valeur / 100.0;
            Salle.tache("altitude-verif", () -> {
                for (int i = 0; i < 8 && !confirmee; i++) {
                    Salle.sommeil(150);
                    HFloorItem it = Salle.sol(id);
                    if (it != null && Math.abs(it.getTile().getZ() - voulu) < 0.02) {
                        Journal.debug("@altitude apprise : variable " + var + ".");
                        retenir(var);
                        return;
                    }
                }
            });
        }
    }
}

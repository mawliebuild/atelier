package atelier;

import extension.GPresets;
import extension.tools.StackTileSetting;
import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

/**
 * Hauteur fixe, comme « :setz » sur les retros, avec un TAPIS de dalles magiques :
 *   1. « Couvrir » pose des dalles magiques sur toutes les cases (ou celles de la zone)
 *      libres : d'abord le plus de 8×8 possible, puis 6×6, 4×4, 2×2, 1×2 et
 *      1×1 pour suivre la forme. Chaque dalle vient du catalogue BC : les
 *      dalles de l'inventaire ne sont jamais touchees. Une dalle ne pose que sur des cases libres de meme
 *      hauteur de sol (le jeu refuse un mobi a cheval sur deux hauteurs).
 *   2. Changer la hauteur regle TOUTES ces dalles (SetCustomStackingHeight).
 *   3. Les mobis poses ensuite arrivent sur les dalles, donc a cette hauteur.
 *      Ils y restent quand on change la hauteur : seules les dalles bougent.
 *
 * Changer la hauteur regle aussi les dalles magiques DEJA dans l'appart (posees
 * par toi) : toutes les dalles de la salle montent. « Ramasser » reprend aussi
 * TOUTES les dalles magiques de l'appart, les tiennes comprises.
 *
 * La fenetre ne montre que la hauteur, les actions et la progression : la zone
 * choisie, le nombre de dalles, les cases restees nues... partent en messages
 * dans le jeu (Journal / InfoJeu), une fois, apres chaque action.
 *
 * L'ancien mode « Sans dalles » (@altitude a chaque pose) est retire : la
 * preference « hauteur.mode = sans » est remise a « avec » au chargement.
 */
public class OutilHauteur {

    private static final Preferences prefs = Preferences.userRoot().node("atelier");
    static {
        // Mode « Sans dalles » retire : s'il etait retenu, on repasse aux dalles.
        try { if ("sans".equals(prefs.get("hauteur.mode", "avec"))) prefs.put("hauteur.mode", "avec"); }
        catch (Throwable ignored) { }
    }
    /** Toujours 0 au lancement (demande de l'utilisatrice) : pas de valeur retenue. */
    private static volatile double hauteur = 0.0;
    /** Ligne de progression (phase en cours), vide sinon. */
    private static Label etat;
    /** Le champ Hauteur, mis a jour quand la hauteur vient de « :h » dans le chat. */
    private static Spinner<Double> champ;
    private static Button couvrirAppart, couvrirZone, ramasser;
    private static ProgressBar barre;
    private static HBox ligneProgres;
    /** Chantier en cours : true = seulement dans la zone (Zone), sinon tout l'appart. */
    private static volatile boolean zoneSeule = false;

    /** Choix de la zone lance par cet outil : on guide dans le jeu, point par point. */
    private static volatile boolean choixZone = false;
    private static volatile boolean deuxiemeDit = false;

    private static void choisirZone() {
        choixZone = true; deuxiemeDit = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    /** Ecouteur de Zone (fil JavaFX) : la zone fermee, on la dit dans le jeu et on la couvre. */
    private static void suivreZone() {
        if (!choixZone) return;
        if (!Zone.choixEnCours()) {
            choixZone = false;
            if (!Zone.definie()) { Journal.erreur("Zone pas choisie : clique « Couvrir une zone » pour recommencer."); return; }
            int l = Zone.largeur(), L = Zone.longueur();
            Journal.succes("Zone choisie : de (" + Zone.minX() + "," + Zone.minY() + ") à (" + Zone.maxX() + ","
                    + Zone.maxY() + "), " + l + " × " + L + " = " + (l * L) + " case(s).");
            lancer(true);
            return;
        }
        if (Zone.premierCoinChoisi() && !deuxiemeDit) {
            deuxiemeDit = true;
            InfoJeu.consigne("Choisis le deuxième point de la zone.");
        }
    }

    private static void lancer(boolean zone) {
        if (occupe) return;
        zoneSeule = zone;
        file.submit(OutilHauteur::couvrir);
    }

    /** Un seul chantier a la fois : poses, reglages et ramassage passent ici l'un apres l'autre. */
    private static final ExecutorService file = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atelier-hauteur-fixe"); t.setDaemon(true); return t; });
    private static volatile boolean arret = false, occupe = false;
    /** Un reglage de hauteur est en cours (barre visible, « Arreter » l'abandonne). */
    private static volatile boolean regle = false;
    /** Chaque reglage de hauteur incremente : seul le dernier est applique. */
    private static final AtomicInteger reglage = new AtomicInteger();

    /** Tailles essayees, de la plus grande a la plus petite. */
    private static final StackTileSetting[] ORDRE = {
            StackTileSetting.XXXL, StackTileSetting.XXL, StackTileSetting.XL,
            StackTileSetting.Large, StackTileSetting.Medium, StackTileSetting.Small };

    // ------------------------------------------------------------------ UI

    public Tab construire() {
        Spinner<Double> valeur = new Spinner<>(0.0, 40.0, hauteur, 0.25);
        champ = valeur;
        ecouterCommande();
        valeur.setEditable(true);
        valeur.setPrefWidth(110);
        // Saisie au clavier prise sans attendre Entree.
        valeur.getEditor().textProperty().addListener((o, a, b) -> {
            try { regler(Double.parseDouble(b.replace(',', '.'))); } catch (NumberFormatException ignored) { }
        });
        valeur.valueProperty().addListener((o, a, b) -> { if (b != null) regler(b); });

        etat = Ui.etat();
        couvrirAppart = Generateur.principal("Couvrir l'appart", () -> lancer(false));
        Icones.sur(couvrirAppart, Icones.COUVRIR);
        couvrirZone = Icones.sur(new Button("Couvrir une zone"), Icones.CIBLE);
        couvrirZone.setMaxWidth(Double.MAX_VALUE);
        couvrirZone.setTooltip(Ui.bulle("Clique deux points dans le jeu : la zone entre les deux est couverte."));
        couvrirZone.setOnAction(e -> { if (!occupe) choisirZone(); });
        ramasser = Icones.sur(new Button("Ramasser les dalles"), Icones.RAMASSER);
        ramasser.setMaxWidth(Double.MAX_VALUE);
        ramasser.setOnAction(e -> { if (!occupe) file.submit(OutilHauteur::ramasserTout); });
        Zone.ecouter(OutilHauteur::suivreZone);

        // --- progression : barre + Arreter, seulement pendant une action
        barre = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        barre.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(barre, Priority.ALWAYS);
        Button arreter = Icones.sur(new Button("Arrêter"), Icones.ARRET);
        arreter.setMinWidth(Region.USE_PREF_SIZE);
        arreter.setOnAction(e -> {
            if (occupe) arret = true;
            else if (regle) reglage.incrementAndGet();      // le reglage en cours s'arrete au tour suivant
            progres("Arrêt demandé…", -1);
        });
        ligneProgres = new HBox(8, barre, arreter);
        ligneProgres.setAlignment(Pos.CENTER_LEFT);
        ligneProgres.setVisible(false); ligneProgres.setManaged(false);

        VBox v = new VBox(12,
                Ui.bloc("Hauteur fixe",
                        Ui.ligne(Ui.etiquette("Hauteur"), valeur),
                        Ui.discret("Dans le jeu : tape :h 10 pour la changer."),
                        Ui.aide("Comme :setz sur les rétros. 0,25 = un quart de case ; une case pleine = 1. "
                                + "Changer la hauteur règle toutes les dalles magiques de l'appart, y compris "
                                + "celles déjà là : les mobis que tu poses dessus arrivent à cette hauteur, "
                                + "et y restent quand tu la changes.")),
                Ui.bloc("Dalles magiques",
                        couvrirAppart, couvrirZone, ramasser,
                        ligneProgres, etat,
                        Ui.aide("« Couvrir » pose des dalles magiques sur toutes les cases libres "
                                + "(8×8 d'abord, puis plus petites pour suivre la forme), prises au catalogue BC "
                                + "(ton inventaire n'est pas touché). « Couvrir une zone » : clique deux points "
                                + "dans le jeu. Le résultat s'affiche dans le jeu."),
                        Ui.aide("« Ramasser » reprend toutes les dalles magiques de l'appart, celles posées par l'Atelier comme les tiennes.")));
        v.setFillWidth(true);
        v.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));
        majBoutons();
        surveiller();

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        Tab t = new Tab("Hauteur fixe", sp);
        t.setClosable(false);
        return t;
    }

    // ------------------------------------------------------- commande :h

    private static volatile boolean commandeBranchee = false;

    /**
     * « :h 10 » tape dans le chat du jeu regle la hauteur a 10 (comme le champ
     * Hauteur), sans ouvrir l'outil. Le message n'est pas envoye au jeu.
     * « :h » seul rappelle la hauteur actuelle.
     */
    private static synchronized void ecouterCommande() {
        if (commandeBranchee) return;
        commandeBranchee = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 900; i++) {
                GPresets gp = Salle.gp();
                if (gp != null) {
                    try {
                        gp.intercept(HMessage.Direction.TOSERVER, "Chat", OutilHauteur::surChat);
                        gp.intercept(HMessage.Direction.TOSERVER, "Shout", OutilHauteur::surChat);
                        return;
                    } catch (Throwable ignored) { }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-hauteur-commande");
        t.setDaemon(true);
        t.start();
    }

    private static void surChat(gearth.protocol.HMessage m) {
        int n = m.getPacket().getBytesLength();
        if (n < 8 || n > 64) return;                         // un « :h 12,5 » est tout petit
        String txt;
        try { txt = new HPacket(m.getPacket()).readString(); } catch (Throwable e) { return; }
        Double h = commande(txt);
        if (h == null) return;
        m.setBlocked(true);
        if (h.isNaN()) { Journal.succes("Hauteur actuelle : " + texte(hauteur) + ". Tape :h 10 pour la changer."); return; }
        if (h < 0 || h > 40) { Journal.erreur("Hauteur impossible : choisis entre 0 et 40, par exemple :h 10."); return; }
        // Le message (dalles reglees) part a la fin du reglage, une seule fois.
        // Meme hauteur qu'avant : on reapplique quand meme (dalles posees depuis).
        if (Math.abs(h - hauteur) < 0.001) { file.submit(() -> appliquer(h, true)); return; }
        Platform.runLater(() -> {
            if (champ != null) champ.getValueFactory().setValue(h);   // declenche regler(h)
            else regler(h);
        });
    }

    /**
     * Logique pure : la hauteur demandee par « :h 10 » / « :h 2,5 », NaN pour
     * « :h » seul, null si ce n'est pas la commande (le message part au jeu).
     */
    static Double commande(String txt) {
        if (txt == null) return null;
        String t = txt.trim();
        if (!t.toLowerCase(java.util.Locale.ROOT).startsWith(":h")) return null;
        String reste = t.substring(2);
        if (reste.isEmpty()) return Double.NaN;
        if (!Character.isWhitespace(reste.charAt(0))) return null;   // « :habbo » n'est pas la commande
        try { return Double.parseDouble(reste.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    private static void regler(double h) {
        if (h < 0 || h > 40) return;
        if (Math.abs(h - hauteur) < 0.001) return;
        hauteur = h;
        prefs.putDouble("hauteur.fixe", h);
        // La frappe au clavier donne une valeur par touche : on attend qu'elle se pose.
        int n = reglage.incrementAndGet();
        Salle.tache("hauteur-reglage", () -> {
            Salle.sommeil(400);
            if (reglage.get() == n) file.submit(() -> { if (reglage.get() == n) appliquer(hauteur, true); });
        });
    }

    static String texte(double h) {
        return String.format(java.util.Locale.FRANCE, "%.2f", h);
    }

    /** Resultat reussi d'une action : un message (Journal : jeu + console), la progression disparait. */
    static void bilan(String s) {
        finProgres();
        Journal.succes(s);
    }

    /** Resultat en echec : un message d'erreur (Journal), la progression disparait. */
    static void echec(String s) {
        finProgres();
        Journal.erreur(s);
    }

    /**
     * Progression visible : phase (texte court, sans compte de dalles) et part
     * faite (0..1), ou negative pour une barre sans fin connue.
     */
    private static void progres(String phase, double part) {
        String t = Ui.majuscule(phase);
        Platform.runLater(() -> {
            if (ligneProgres != null) { ligneProgres.setVisible(true); ligneProgres.setManaged(true); }
            if (barre != null) barre.setProgress(part < 0 ? ProgressBar.INDETERMINATE_PROGRESS : Math.min(1, part));
            if (etat != null) etat.setText(t);
        });
    }

    private static void finProgres() {
        Platform.runLater(() -> {
            if (ligneProgres != null) { ligneProgres.setVisible(false); ligneProgres.setManaged(false); }
            if (etat != null) etat.setText("");
        });
    }

    static boolean occupe() { return occupe; }

    /** Boutons : grises pendant une action ; « Ramasser » grise s'il n'y a aucune dalle. */
    private static void majBoutons() {
        int n = toutesDalles().size();
        boolean o = occupe || regle;
        Platform.runLater(() -> {
            if (couvrirAppart != null) couvrirAppart.setDisable(o);
            if (couvrirZone != null) couvrirZone.setDisable(o);
            if (ramasser != null) ramasser.setDisable(o || n == 0);
        });
    }

    /** Boutons tenus a jour tout seuls (changement de salle, dalle posee ou ramassee) : rien n'est dit. */
    private static volatile boolean surveille = false;
    private static void surveiller() {
        if (surveille) return;
        surveille = true;
        Salle.tache("hauteur-resume", () -> {
            String vu = null;
            while (true) {
                Salle.sommeil(2000);
                try {
                    if (occupe) continue;
                    String sig = Salle.salleId() + "/" + toutesDalles().size();
                    if (!sig.equals(vu)) { vu = sig; majBoutons(); }
                } catch (Throwable ignored) { }
            }
        });
    }

    // ------------------------------------------------- dalles de l'Atelier

    private static String cleSalle() { return "hauteur.dalles." + Salle.salleId(); }

    /** Ids des dalles posees par l'Atelier et encore dans la salle. */
    static List<Integer> nosDalles() {
        List<Integer> r = new ArrayList<>();
        if (!Salle.dansUneSalle()) return r;
        for (String s : prefs.get(cleSalle(), "").split(","))
            try { int id = Integer.parseInt(s.trim()); if (Salle.sol(id) != null) r.add(id); }
            catch (NumberFormatException ignored) { }
        return r;
    }

    /**
     * Toutes les dalles magiques de la salle : celles de l'Atelier ET celles
     * deja la. Reconnues par type (StackTileSetting) ou nom de classe tile_stackmagic*.
     */
    static List<Integer> toutesDalles() {
        if (!Salle.dansUneSalle()) return new ArrayList<>();
        Set<Integer> types = Generateur.Dalle.typesDalles();
        List<HauteurLogique.Mobi> l = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (it == null || it.getTile() == null) continue;
            l.add(new HauteurLogique.Mobi(it.getId(), it.getTypeId(), it.getTile().getX(), it.getTile().getY()));
        }
        Map<Integer, String> classes = new HashMap<>();
        return HauteurLogique.dalles(l, types,
                t -> classes.computeIfAbsent(t, k -> Salle.classe(k, false)));
    }

    private static void retenir(Collection<Integer> ids) { retenir(ids, cleSalle()); }

    /** cle : celle de la salle ou les dalles ont ete posees (calculee au debut du chantier). */
    private static void retenir(Collection<Integer> ids, String cle) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) { if (b.length() > 0) b.append(','); b.append(id); }
        // Une preference ne depasse pas 8 Ko : au-dela, les plus anciennes sont oubliees.
        while (b.length() > Preferences.MAX_VALUE_LENGTH) b.delete(0, b.indexOf(",") + 1);
        prefs.put(cle, b.toString());
    }

    // ------------------------------------------------------------ chantier

    /** Hors fil JavaFX : couvre les cases libres de l'appart. */
    private static void couvrir() {
        GPresets gp = Salle.gp();
        if (gp == null || !Salle.dansUneSalle()) { echec("Pose impossible : entre d'abord dans un appart."); return; }
        if (zoneSeule && !Zone.definie()) { Platform.runLater(OutilHauteur::choisirZone); return; }
        final int salle = Salle.salleId();
        final String cle = cleSalle();
        String raisonArret = null;
        occupe = true; arret = false;
        majBoutons();
        progres(zoneSeule ? "Pose des dalles dans la zone…" : "Pose des dalles…", -1);
        Set<Integer> types = Generateur.Dalle.typesDalles();
        for (int type : types) Historique.ignorerType(type, 60 * 60_000L);
        int poses = 0;
        try {
            // 1. Les dalles deja la (appart entier, ou seulement celles de la zone)
            //    sont ramassees d'abord : le nouveau tapis part d'un sol propre.
            int dejaLa = viderAvantPose(salle);
            if (arret || Salle.salleId() != salle) {
                if (Salle.salleId() != salle) echec("Pose interrompue : tu as changé de salle.");
                else bilan("Arrêté avant la pose : " + dejaLa + " dalle(s) ramassée(s).");
                return;
            }
            if (dejaLa > 0) Journal.debug("hauteur : " + dejaLa + " dalle(s) ramassée(s) avant la pose");
            List<Integer> ids = new ArrayList<>(nosDalles());
            progres(zoneSeule ? "Pose des dalles dans la zone…" : "Pose des dalles…", -1);
            int[][] sol = plan();
            if (zoneSeule)       // hors zone : comme une case vide, aucune dalle n'y va
                for (int x = 0; x < sol.length; x++)
                    for (int y = 0; y < sol[x].length; y++)
                        if (!Zone.contient(x, y)) sol[x][y] = -1;
            Set<Long> prises = occupees();
            Set<Long> refusees = new HashSet<>();     // cases ou meme une 1×1 a ete refusee
            Set<Integer> invPris = new HashSet<>();
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            // Le BC peut refuser toutes les poses (pas de Builders Club, limite
            // atteinte...) : apres deux refus d'affilee sans aucune reussite, on arrete.
            int bcRefus = 0, bcReussies = 0;
            Map<String, int[]> bilan = new LinkedHashMap<>();   // taille -> {inventaire, BC}
            Map<String, String> sautees = new LinkedHashMap<>(); // taille -> pourquoi

            // Formes possibles, de la plus grande a la plus petite (la 1×2 aussi couchee).
            List<Object[]> formes = new ArrayList<>();          // {t, type, lx, ly, rot, taille, rang}
            int rang = 0;
            for (StackTileSetting t : ORDRE) {
                Integer type = fd.getFloorTypeId(t.getClassName());
                if (type == null) continue;
                int[] e = Generateur.Dalle.empriseDalle(t);
                String taille = e[0] + "x" + e[1];
                bilan.putIfAbsent(taille, new int[2]);
                formes.add(new Object[]{t, type, e[0], e[1], 0, taille, rang});
                if (e[0] != e[1]) formes.add(new Object[]{t, type, e[1], e[0], 2, taille, rang});
                rang++;
            }
            // Chaque taille se pose la ou elle couvre le plus de cases encore nues,
            // meme en chevauchant des dalles deja la (elles s'empilent) : moins de
            // dalles au total. Une taille ne sert que si elle couvre PLUS de cases
            // nues que la taille suivante ne le pourrait ; sinon on passe a celle-ci.
            Set<Long> exclus = new HashSet<>();                 // « x,y,lx,ly » refuses par le jeu
            Set<Integer> epuisees = new HashSet<>();            // rangs abandonnes
            for (int r = 0; r < rang && !arret; r++) {
                int refusDeSuite = 0;
                while (!arret && Salle.salleId() == salle && !epuisees.contains(r)) {
                    int[] meilleur = null; Object[] forme = null;
                    for (Object[] f : formes) {
                        if ((int) f[6] != r) continue;
                        int[] c = meilleurCoin(sol, (int) f[2], (int) f[3], prises, refusees, exclus);
                        if (c != null && (meilleur == null || c[2] > meilleur[2])) { meilleur = c; forme = f; }
                    }
                    if (meilleur == null) break;
                    int suivante = 0;
                    for (int r2 = r + 1; r2 < rang && suivante == 0; r2++) {
                        if (epuisees.contains(r2)) continue;
                        for (Object[] f : formes) {
                            if ((int) f[6] != r2) continue;
                            int[] c = meilleurCoin(sol, (int) f[2], (int) f[3], prises, refusees, exclus);
                            if (c != null) suivante = Math.max(suivante, c[2]);
                        }
                    }
                    if (meilleur[2] <= suivante) break;          // la taille suivante fait aussi bien
                    StackTileSetting t = (StackTileSetting) forme[0];
                    int type = (int) forme[1], lx = (int) forme[2], ly = (int) forme[3], rot = (int) forme[4];
                    String taille = (String) forme[5];
                    int[] c = meilleur;
                    Set<Integer> avant = new HashSet<>();
                    for (HFloorItem it : Salle.sols()) avant.add(it.getId());
                    // BC seulement : les dalles de ton inventaire ne sont pas touchees
                    String d = Generateur.Dalle.envoyer(gp, type, t, c[0], c[1], rot, invPris, true, false);
                    if (d == null) {                       // pas d'offre BC : taille suivante
                        sautees.put(taille, "pas d'offre au BC (catalogue BC pas chargé ?)");
                        epuisees.add(r);
                        break;
                    }
                    int id = attendrePose(avant, type, c[0], c[1]);
                    if (id < 0) {
                        String msg = "Dalle " + lx + "x" + ly + " en (" + c[0] + "," + c[1] + ") " + d
                                + " : refusée par le jeu.";
                        Journal.debug(msg);
                        // Le BC n'est fautif que s'il n'a encore rien pose : sinon,
                        // c'est la case qui bloque (meuble, avatar...), pas le BC.
                        if (Generateur.Dalle.BC.equals(d) && bcReussies == 0) {
                            if (++bcRefus >= 2) {
                                raisonArret = "le BC a refusé les dalles (Builders Club inactif ou limite atteinte ?)";
                                arret = true;
                            }
                            continue;
                        }
                        exclus.add(cleForme(c[0], c[1], lx, ly));
                        if (lx * ly == 1) refusees.add(cle(c[0], c[1]));
                        // une grande dalle refusee a la suite : les petites feront le reste
                        if (++refusDeSuite >= 6 && lx * ly > 1) {
                            Journal.debug("Dalles " + taille + " refusées 6 fois de suite : taille suivante.");
                            epuisees.add(r);
                        }
                        continue;
                    }
                    refusDeSuite = 0;
                    if (Generateur.Dalle.BC.equals(d)) { bcRefus = 0; bcReussies++; bilan.get(taille)[1]++; }
                    else bilan.get(taille)[0]++;
                    for (int i = 0; i < lx; i++)
                        for (int j = 0; j < ly; j++) prises.add(cle(c[0] + i, c[1] + j));
                    ids.add(id);
                    retenir(ids, cle);
                    poses++;
                }
            }
            boolean partie = Salle.salleId() != salle;
            if (poses > 0 && !partie) {
                Salle.sommeil(300);
                progres("Réglage de la hauteur…", -1);
                appliquer(hauteur, false);      // le bilan de la pose suffit : pas de 2e message
            }
            int libres = 0;
            for (int x = 0; x < sol.length; x++)
                for (int y = 0; y < sol[x].length; y++)
                    if (sol[x][y] >= 0 && !prises.contains(cle(x, y))) libres++;
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, int[]> b : bilan.entrySet()) {
                int[] n = b.getValue();
                String pourquoi = sautees.get(b.getKey());
                if (n[0] + n[1] == 0 && pourquoi == null) continue;   // pas de place pour cette taille
                if (detail.length() > 0) detail.append(" · ");
                detail.append(b.getKey()).append(" : ").append(n[0] + n[1]);
                if (n[0] > 0 && n[1] > 0) detail.append(" (").append(n[0]).append(" inv., ").append(n[1]).append(" BC)");
                else if (n[1] > 0) detail.append(" (BC)");
                else if (n[0] > 0) detail.append(" (inv.)");
                if (pourquoi != null && libres > 0) detail.append(", puis ").append(pourquoi);
            }
            String fin = (arret ? "Arrêté : " : "Terminé : ") + poses + " dalle(s) posée(s)"
                    + (libres > 0 ? ", " + libres + " case(s) restée(s) sans dalle." : ", tout l'appart est couvert.")
                    + (detail.length() > 0 ? " " + detail : "");
            Journal.info(fin);
            String lieu = zoneSeule ? "toute la zone" : "tout l'appart";
            if (partie) echec("Pose interrompue : tu as changé de salle (" + poses + " dalle(s) posée(s)).");
            else if (raisonArret != null)
                echec((poses == 0 ? "Aucune dalle posée : " : poses + " dalle(s) posée(s), puis arrêt : ") + raisonArret + ".");
            else if (arret) bilan("Pose arrêtée : " + poses + " dalle(s) posée(s).");
            else if (poses == 0 && libres == 0) bilan("Aucune dalle à poser : " + lieu + " est déjà couvert" + (zoneSeule ? "e." : "."));
            else if (poses == 0) echec("Aucune dalle posée : " + libres + " case(s) refusée(s) par le jeu.");
            else if (libres > 0) bilan(poses + " dalle(s) posée(s), " + libres + " case(s) restée(s) sans dalle.");
            else bilan(poses + " dalle(s) posée(s), " + lieu + " est couvert" + (zoneSeule ? "e." : "."));
        } catch (Throwable t) {
            Journal.erreur("Pose des dalles interrompue", t);
        } finally {
            for (int type : types) Historique.ignorerType(type, 2500);
            occupe = false; arret = false;
            finProgres();
            majBoutons();
        }
    }

    /** Id de la dalle apparue sur (x, y), ou -1 apres 4 s. */
    private static int attendrePose(Set<Integer> avant, int type, int x, int y) {
        for (int i = 0; i < 27; i++) {
            Salle.sommeil(150);
            for (HFloorItem it : Salle.sols())
                if (!avant.contains(it.getId()) && it.getTypeId() == type
                        && it.getTile().getX() == x && it.getTile().getY() == y) {
                    Salle.sommeil(120);
                    return it.getId();
                }
        }
        return -1;
    }

    /**
     * Met toutes les dalles magiques de la salle (les notres + celles deja la)
     * a la hauteur h. dire : un message dans le jeu a la fin (reglage demande
     * par toi) ; false quand « Couvrir » enchaine (son bilan suffit).
     */
    private static void appliquer(double h, boolean dire) {
        if (!dire) { appliquer0(h, false); return; }
        regle = true;
        majBoutons();
        try { appliquer0(h, true); }
        finally {
            regle = false;
            finProgres();
            majBoutons();
        }
    }

    private static void appliquer0(double h, boolean dire) {
        GPresets gp = Salle.gp();
        List<Integer> nos = nosDalles();
        LinkedHashSet<Integer> ids = new LinkedHashSet<>(nos);
        ids.addAll(toutesDalles());
        int verrou = ids.size();
        ids.removeIf(OutilHauteur::verrouillee);          // dalles d'un calque verrouille : intouchables
        verrou -= ids.size();
        if (gp == null || !Salle.dansUneSalle() || ids.isEmpty()) {
            if (dire) Journal.succes(HauteurLogique.messageReglage(texte(h), Salle.dansUneSalle(), 0, 0, 0, verrou));
            return;
        }
        if (dire) progres("Réglage de la hauteur…", 0);
        // Premier tour en rafale, sans pause : toutes les dalles montent ensemble.
        // Le serveur en laisse tomber une partie (constate : toutes sauf la
        // premiere) : on relit la hauteur de chaque dalle et on renvoie a celles
        // qui ne l'ont pas prise, en espacant de plus en plus, tant que ca avance.
        int valeur = (int) Math.round(Math.max(0, h) * 100);
        List<Integer> aFaire = new ArrayList<>(ids);
        int[] pauses = {0, 150, 300, 600, 1000, 1500};
        int demande = reglage.get();
        boolean relue = false;           // a-t-on deja vu une dalle prendre la hauteur ?
        for (int tour = 0; tour < 40 && !aFaire.isEmpty(); tour++) {
            if (reglage.get() != demande) break;      // une autre hauteur a ete demandee
            int pause = pauses[Math.min(tour, pauses.length - 1)];
            for (int id : aFaire) {
                gp.sendToServer(new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, id, valeur));
                if (pause > 0) Salle.sommeil(pause);
            }
            Salle.sommeil(800);
            List<Integer> manquees = new ArrayList<>();
            for (int id : aFaire) if (!prise(Salle.sol(id), h)) manquees.add(id);
            int prises = aFaire.size() - manquees.size();
            if (prises > 0) relue = true;
            Journal.debug("hauteur " + texte(h) + " : tour " + (tour + 1) + " (pause "
                    + pause + " ms), " + prises + " / " + aFaire.size() + " prise(s)." + (tour == 0 ? temoin(aFaire) : ""));
            // Rien de relu apres deux tours : on ne sait pas lire la hauteur, on arrete.
            if (!relue && tour >= 1) break;
            // A la pause la plus longue, un tour sans progres : le serveur ne veut plus.
            if (prises == 0 && tour >= pauses.length - 1) break;
            if (!manquees.isEmpty() && dire)
                progres("Réglage de la hauteur…", (ids.size() - manquees.size()) / (double) ids.size());
            aFaire = manquees;
        }
        int deja = HauteurLogique.compte(new ArrayList<>(ids), nos)[1];
        int ratees = !relue ? 0 : aFaire.size();
        String m = HauteurLogique.messageReglage(texte(h), true, ids.size(), deja, ratees, verrou);
        // Une autre hauteur demandee entre-temps : c'est elle qui donnera le message.
        if (!dire || reglage.get() != demande) Journal.info(m);
        else if (ratees > 0) Journal.erreur(m);
        else Journal.succes(m);
    }

    /**
     * La dalle a-t-elle pris la hauteur h ? Elle se lit dans son altitude (z,
     * absolue ou au-dessus du sol). Pas dans sa hauteur propre (sizeZ) : elle
     * vaut toujours 0, ce qui faisait croire qu'a 0 tout etait deja regle.
     */
    private static boolean prise(HFloorItem it, double h) {
        if (it == null) return true;                 // ramassee entre-temps : rien a faire
        try {
            double z = it.getTile().getZ();
            if (Math.abs(z - h) < 0.011) return true;
            int sol = Salle.hauteurSol(it.getTile().getX(), it.getTile().getY());
            return sol > 0 && Math.abs(z - sol - h) < 0.011;
        } catch (Throwable t) { return false; }
    }

    /** Pour le journal : ce que l'on lit sur la premiere dalle (sizeZ, z). */
    private static String temoin(List<Integer> ids) {
        if (ids.isEmpty()) return "";
        HFloorItem it = Salle.sol(ids.get(0));
        if (it == null) return "";
        try { return " Dalle " + it.getId() + " : sizeZ " + Salle.hauteur(it) + ", z " + it.getTile().getZ() + "."; }
        catch (Throwable t) { return ""; }
    }

    /**
     * Avant de couvrir : ramasse en rafale les dalles magiques deja la (toutes celles
     * de l'appart, ou seulement celles de la zone), sauf celles d'un calque verrouille.
     * Rend le nombre de dalles envoyees au ramassage.
     */
    private static int viderAvantPose(int salle) {
        List<Integer> ids = new ArrayList<>(new LinkedHashSet<>(toutesDalles()));
        ids.removeIf(OutilHauteur::verrouillee);
        if (zoneSeule) ids.removeIf(id -> {
            HFloorItem it = Salle.sol(id);
            return it == null || !Zone.contient(it.getTile().getX(), it.getTile().getY());
        });
        if (ids.isEmpty()) return 0;
        progres("Ramassage des dalles déjà là…", 0);
        int n = 0;
        for (int passe = 1; passe <= 2 && !arret; passe++) {
            List<Integer> encore = new ArrayList<>();
            for (int id : ids) if (Salle.sol(id) != null) encore.add(id);
            if (encore.isEmpty()) break;
            for (int id : encore) {
                if (arret || Salle.salleId() != salle) return n;
                Salle.espacer();
                Salle.ramasser(id, false);
                if (passe == 1) n++;
                progres("Ramassage des dalles déjà là…", n / (double) ids.size());
            }
            PoseDirecte.suivre(() -> { int r = 0; for (int id : encore) if (Salle.sol(id) != null) r++; return r; },
                    800, 3000);
        }
        Salle.sommeil(300);             // l'inventaire se met a jour : les dalles ramassees resservent
        return n;
    }

    private static void ramasserTout() {
        LinkedHashSet<Integer> tous = new LinkedHashSet<>(nosDalles());
        tous.addAll(toutesDalles());
        List<Integer> ids = new ArrayList<>(tous);
        int verrou = ids.size();
        ids.removeIf(OutilHauteur::verrouillee);          // dalles d'un calque verrouille : laissees
        verrou -= ids.size();
        String laissees = verrou > 0 ? " " + verrou + " dalle(s) d'un calque verrouillé laissée(s) en place." : "";
        if (!Salle.dansUneSalle()) { echec("Ramassage impossible : entre d'abord dans un appart."); return; }
        if (ids.isEmpty()) {
            bilan(verrou > 0 ? "Rien à ramasser :" + laissees : "Aucune dalle magique à ramasser dans cet appart.");
            majBoutons();
            return;
        }
        final int salle = Salle.salleId();
        final String cle = cleSalle();
        occupe = true; arret = false;
        majBoutons();
        progres("Ramassage des dalles…", 0);
        Set<Integer> types = Generateur.Dalle.typesDalles();
        for (int type : types) Historique.ignorerType(type, 30 * 60_000L);
        try {
            // En rafale : un envoi toutes les 150 ms (rythme commun), sans attendre
            // la disparition de chaque dalle ; une 2e passe reprend celles restees.
            int n = 0;
            for (int passe = 1; passe <= 2 && !arret; passe++) {
                List<Integer> encore = new ArrayList<>();
                for (int id : ids) if (Salle.sol(id) != null) encore.add(id);
                if (encore.isEmpty()) break;
                for (int id : encore) {
                    if (arret || Salle.salleId() != salle) break;
                    Salle.espacer();
                    Salle.ramasser(id, false);
                    if (passe == 1) n++;
                    progres(passe > 1 ? "Ramassage des dalles (2e passe)…" : "Ramassage des dalles…", n / (double) ids.size());
                }
                if (Salle.salleId() != salle) break;
                PoseDirecte.suivre(() -> { int r = 0; for (int id : encore) if (Salle.sol(id) != null) r++; return r; },
                        800, 3000);
            }
            if (Salle.salleId() != salle) {
                echec("Ramassage interrompu : tu as changé de salle (" + n + " / " + ids.size() + ").");
                return;
            }
            Salle.sommeil(300);
            List<Integer> reste = toutesDalles();
            reste.removeIf(OutilHauteur::verrouillee);    // celles-la sont laissees exprès
            int restent = reste.size();
            retenir(nosDalles(), cle);
            if (restent == 0) bilan((arret ? "Ramassage arrêté : " + n + " dalle(s) ramassée(s)." : "Dalles ramassées.") + laissees);
            else if (arret) bilan("Ramassage arrêté : " + n + " dalle(s) ramassée(s), " + restent + " encore là." + laissees);
            else echec("Échec pour " + restent + " dalle(s) : pas pu être ramassée(s)." + laissees);
        } finally {
            for (int type : types) Historique.ignorerType(type, 2500);
            occupe = false; arret = false;
            finProgres();
            majBoutons();
        }
    }

    // ------------------------------------------------------ lecture salle

    static long cle(int x, int y) { return Generateur.Dalle.cle(x, y); }

    /** Hauteur du sol de chaque case, -1 hors plan. */
    private static int[][] plan() {
        int w = 0, l = 0;
        try { w = Salle.etat().getFloorplanWidth(); l = Salle.etat().getFloorplanHeight(); }
        catch (Throwable ignored) { }
        int n = Math.max(64, Math.max(w, l));     // l'ordre largeur/longueur importe peu : on balaie un carre
        int[][] s = new int[n][n];
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) s[x][y] = Salle.hauteurSol(x, y);
        return s;
    }

    /**
     * Cases deja couvertes par une dalle magique. Les autres mobis ne comptent
     * pas : une dalle se glisse dessous (c'est ce que fait le moteur de pose en la
     * deplacant sous chaque meuble). Sans ca, un appart meuble ne recevait que
     * des petites dalles entre les meubles.
     */
    private static Set<Long> occupees() {
        Set<Long> r = new HashSet<>();
        Set<Integer> dalles = Generateur.Dalle.typesDalles();
        for (HFloorItem it : Salle.sols()) {
            if (!dalles.contains(it.getTypeId())) continue;
            int[] e = Salle.emprise(it);
            int x = it.getTile().getX(), y = it.getTile().getY();
            for (int i = 0; i < e[0]; i++) for (int j = 0; j < e[1]; j++) r.add(cle(x + i, y + j));
        }
        return r;
    }

    /**
     * Pose des dalles magiques (BC) qui couvrent les cases donnees, les plus
     * grandes d'abord, comme « Couvrir » (chevauchements permis). Pour les
     * calques : les dalles sous une copie, qui se deplaceront avec elle.
     * Hors fil JavaFX. Renvoie les ids des dalles posees.
     */
    static List<Integer> couvrirCases(Set<Long> cases, java.util.function.Consumer<String> dire,
                                      java.util.function.BooleanSupplier stop) {
        List<Integer> poses = new ArrayList<>();
        GPresets gp = Salle.gp();
        if (gp == null || cases.isEmpty()) return poses;
        int[][] sol = plan();
        for (int x = 0; x < sol.length; x++)
            for (int y = 0; y < sol[x].length; y++)
                if (!cases.contains(cle(x, y))) sol[x][y] = -1;
        furnidata.FurniDataTools fd = gp.getFurniDataTools();
        Set<Long> prises = new HashSet<>(), refusees = new HashSet<>(), exclus = new HashSet<>();
        Set<Integer> invPris = new HashSet<>();
        Set<Integer> types = Generateur.Dalle.typesDalles();
        for (int type : types) Historique.ignorerType(type, 10 * 60_000L);
        final int salle = Salle.salleId();
        java.util.function.BooleanSupplier stop0 = stop;
        stop = () -> stop0.getAsBoolean() || Salle.salleId() != salle;   // autre salle : on s'arrete
        try {
            for (StackTileSetting t : ORDRE) {
                Integer type = fd.getFloorTypeId(t.getClassName());
                if (type == null) continue;
                int[] e = Generateur.Dalle.empriseDalle(t);
                int[][] sens = (e[0] == e[1]) ? new int[][]{{e[0], e[1], 0}} : new int[][]{{e[0], e[1], 0}, {e[1], e[0], 2}};
                int refus = 0;
                for (int[] sn : sens) {
                    while (!stop.getAsBoolean() && refus < 6) {
                        int[] c = meilleurCoin(sol, sn[0], sn[1], prises, refusees, exclus);
                        // une grande dalle ne sert que si elle couvre plus qu'une 1×1 : sinon les petites
                        if (c == null || (sn[0] * sn[1] > 1 && c[2] < Math.max(2, sn[0] * sn[1] / 2))) break;
                        Set<Integer> avant = new HashSet<>();
                        for (HFloorItem it : Salle.sols()) avant.add(it.getId());
                        String d = Generateur.Dalle.envoyer(gp, type, t, c[0], c[1], sn[2], invPris, true, false);
                        if (d == null) break;
                        int id = attendrePose(avant, type, c[0], c[1]);
                        if (id < 0) {
                            exclus.add(cleForme(c[0], c[1], sn[0], sn[1]));
                            if (sn[0] * sn[1] == 1) refusees.add(cle(c[0], c[1]));
                            refus++;
                            continue;
                        }
                        refus = 0;
                        for (int i = 0; i < sn[0]; i++) for (int j = 0; j < sn[1]; j++) prises.add(cle(c[0] + i, c[1] + j));
                        poses.add(id);
                        dire.accept("Dalles magiques sous la copie : " + poses.size() + " posée(s)…");
                    }
                }
            }
        } finally {
            for (int type : types) Historique.ignorerType(type, 2500);
        }
        return poses;
    }

    /** La dalle est-elle dans un calque verrouille ? */
    private static boolean verrouillee(int id) {
        try { return Groupes.refusVerrouMobis(List.of(id), List.of()) != null; }
        catch (Throwable t) { return false; }
    }

    static long cleForme(int x, int y, int lx, int ly) {
        return (((long) x * 1024 + y) * 64 + lx) * 64 + ly;
    }

    /**
     * Meilleur coin pour une dalle lx × ly : toutes ses cases dans le plan, de
     * meme hauteur de sol et jamais refusees ; elle peut chevaucher des cases
     * deja couvertes. {x, y, cases nues couvertes} pour celle qui en couvre le
     * plus (au moins une), ou null. Logique pure.
     */
    static int[] meilleurCoin(int[][] sol, int lx, int ly, Set<Long> couvertes, Set<Long> refusees, Set<Long> exclus) {
        int n = sol.length, m = n == 0 ? 0 : sol[0].length;
        int[] best = null;
        for (int y = 0; y + ly <= m; y++)
            for (int x = 0; x + lx <= n; x++) {
                int h = sol[x][y];
                if (h < 0 || exclus.contains(cleForme(x, y, lx, ly))) continue;
                int nues = 0;
                boolean ok = true;
                for (int i = 0; i < lx && ok; i++)
                    for (int j = 0; j < ly && ok; j++) {
                        if (sol[x + i][y + j] != h || refusees.contains(cle(x + i, y + j))) ok = false;
                        else if (!couvertes.contains(cle(x + i, y + j))) nues++;
                    }
                if (ok && nues > 0 && (best == null || nues > best[2])) best = new int[]{x, y, nues};
            }
        return best;
    }

    /**
     * Premier coin (balayage ligne par ligne) ou une dalle lx × ly tient :
     * toutes ses cases dans le plan, libres et de meme hauteur de sol.
     * null s'il n'y en a plus. Logique pure.
     */
    static int[] premierCoin(int[][] sol, int lx, int ly,
                             java.util.function.BiPredicate<Integer, Integer> libre, Set<Long> exclus) {
        int n = sol.length, m = n == 0 ? 0 : sol[0].length;
        for (int y = 0; y + ly <= m; y++)
            for (int x = 0; x + lx <= n; x++) {
                if (exclus.contains(cle(x, y))) continue;
                int h = sol[x][y];
                if (h < 0) continue;
                boolean ok = true;
                for (int i = 0; i < lx && ok; i++)
                    for (int j = 0; j < ly && ok; j++)
                        if (sol[x + i][y + j] != h || !libre.test(x + i, y + j)) ok = false;
                if (ok) return new int[]{x, y};
            }
        return null;
    }
}

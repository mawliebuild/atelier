package atelier;

import extension.GPresets;
import extension.tools.StackTileSetting;
import gearth.extensions.parsers.HFloorItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.scene.control.*;
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
 * Mode « Sans dalles » (HauteurSansDalles) : rien n'est pose ; tant qu'il est
 * actif, chaque mobi que tu poses est remis a la hauteur par @altitude.
 * Le mode choisi est retenu (preference « hauteur.mode »).
 */
public class OutilHauteur {

    private static final Preferences prefs = Preferences.userRoot().node("atelier");
    /** Toujours 0 au lancement (demande de l'utilisatrice) : pas de valeur retenue. */
    private static volatile double hauteur = 0.0;
    private static Label etat, resume, etatSans;
    /** Le champ Hauteur, mis a jour quand la hauteur vient de « :h » dans le chat. */
    private static Spinner<Double> champ;
    private static Button couvrir, ramasser, interrupteur, dejaPoses;
    /** Ou poser les dalles : true = seulement dans la zone (Zone), sinon tout l'appart. */
    private static volatile boolean zoneSeule = prefs.getBoolean("hauteur.zone", false);

    private static String libelleCouvrir() { return zoneSeule ? "Couvrir la zone de dalles" : "Couvrir l'appart de dalles"; }

    /** Choix de la zone lance par cet outil : on guide dans le jeu, point par point. */
    private static volatile boolean choixZone = false;
    private static volatile boolean deuxiemeDit = false;

    private static void choisirZone() {
        choixZone = true; deuxiemeDit = false;
        Zone.demarrerChoix();
        InfoJeu.consigne("Choisis le premier point de la zone.");
    }

    /** Ecouteur de Zone (fil JavaFX). */
    private static void suivreZone() {
        if (!choixZone) return;
        if (!Zone.choixEnCours()) { choixZone = false; return; }
        if (Zone.premierCoinChoisi() && !deuxiemeDit) {
            deuxiemeDit = true;
            InfoJeu.consigne("Choisis le deuxième point de la zone.");
        }
    }

    /** Mode choisi : true = « Sans dalles ». */
    private static volatile boolean sansDalles = "sans".equals(prefs.get("hauteur.mode", "avec"));

    /** Un seul chantier a la fois : poses, reglages et ramassage passent ici l'un apres l'autre. */
    private static final ExecutorService file = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atelier-hauteur-fixe"); t.setDaemon(true); return t; });
    private static volatile boolean arret = false, occupe = false;
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
        resume = Ui.valeur("");
        couvrir = Generateur.principal(libelleCouvrir(), () -> {
            if (occupe) { arret = true; dire("Arrêt demandé…"); }
            else file.submit(OutilHauteur::couvrir);
        });
        ramasser = new Button("Ramasser toutes les dalles de l'appart");
        ramasser.setMaxWidth(Double.MAX_VALUE);
        ramasser.setOnAction(e -> { if (!occupe) file.submit(OutilHauteur::ramasserTout); });

        // --- mode : deux choix en haut de l'outil
        ToggleGroup g = new ToggleGroup();
        RadioButton avec = new RadioButton("Avec dalles");
        RadioButton sans = new RadioButton("Sans dalles");
        avec.setToggleGroup(g); sans.setToggleGroup(g);
        (sansDalles ? sans : avec).setSelected(true);

        // --- ou : tout l'appart, ou la zone choisie (deux clics dans le jeu)
        ToggleGroup gz = new ToggleGroup();
        RadioButton toutAppart = new RadioButton("Tout l'appart");
        RadioButton dansZone = new RadioButton("Zone seulement");
        toutAppart.setToggleGroup(gz); dansZone.setToggleGroup(gz);
        (zoneSeule ? dansZone : toutAppart).setSelected(true);
        Label zoneTexte = Ui.valeur(Zone.texte());
        Button zoneChoisir = new Button("Choisir dans le jeu");
        zoneChoisir.setOnAction(e -> choisirZone());
        Button zoneEffacer = new Button("Effacer");
        zoneEffacer.setOnAction(e -> Zone.effacer());
        Zone.ecouter(() -> zoneTexte.setText(Zone.texte()));
        Zone.ecouter(OutilHauteur::suivreZone);
        VBox blocZone = Ui.bloc("Zone", zoneTexte, Ui.ligne(zoneChoisir, zoneEffacer));
        blocZone.setVisible(zoneSeule); blocZone.setManaged(zoneSeule);
        gz.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { (zoneSeule ? dansZone : toutAppart).setSelected(true); return; }
            zoneSeule = b == dansZone;
            prefs.putBoolean("hauteur.zone", zoneSeule);
            blocZone.setVisible(zoneSeule); blocZone.setManaged(zoneSeule);
            if (zoneSeule && !Zone.definie()) choisirZone();
            majResume();
        });

        VBox blocAvec = Ui.bloc("Avec dalles",
                Ui.ligne(toutAppart, dansZone), blocZone,
                couvrir, ramasser, resume,
                Ui.aide("« Couvrir » pose des dalles magiques sur toutes les cases libres (de l'appart, "
                        + "ou de la zone choisie par deux clics dans le jeu) "
                        + "(8×8 d'abord, puis plus petites pour suivre la forme), prises au catalogue BC "
                        + "(ton inventaire n'est pas touché). Changer la hauteur règle toutes les dalles "
                        + "magiques de l'appart, y compris celles déjà là : les mobis que tu poses dessus "
                        + "arrivent à cette hauteur, et y restent quand tu la changes."),
                Ui.aide("« Ramasser » reprend toutes les dalles magiques de l'appart, celles posées par l'Atelier comme les tiennes."));

        etatSans = Ui.valeur("");
        interrupteur = new Button("Activer");
        interrupteur.getStyleClass().add("primaire");
        interrupteur.setMaxWidth(Double.MAX_VALUE);
        interrupteur.setOnAction(e -> {
            if (HauteurSansDalles.actif()) HauteurSansDalles.arreter(); else HauteurSansDalles.activer();
        });
        dejaPoses = new Button("Appliquer aux mobis déjà posés pendant ce mode");
        dejaPoses.setMaxWidth(Double.MAX_VALUE);
        dejaPoses.setOnAction(e -> HauteurSansDalles.appliquerDejaPoses());
        VBox blocSans = Ui.bloc("Sans dalles",
                interrupteur, etatSans, dejaPoses,
                Ui.aide("Rien n'est posé, ni inventaire ni BC. Tant que le mode est actif, chaque mobi "
                        + "de sol que tu poses est remis à la hauteur choisie juste après sa pose (@altitude). "
                        + "Les mobis déplacés, ceux des autres et les collages de l'Atelier ne sont pas touchés. "
                        + "Changer la hauteur vaut pour les poses suivantes."));

        Runnable montrer = () -> {
            blocAvec.setVisible(!sansDalles); blocAvec.setManaged(!sansDalles);
            blocSans.setVisible(sansDalles); blocSans.setManaged(sansDalles);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { (sansDalles ? sans : avec).setSelected(true); return; }
            sansDalles = b == sans;
            prefs.put("hauteur.mode", sansDalles ? "sans" : "avec");
            if (!sansDalles) HauteurSansDalles.arreter();
            montrer.run();
            majResume();
        });
        montrer.run();

        HauteurSansDalles.configurer(() -> hauteur, OutilHauteur::dire, OutilHauteur::majSans);

        VBox v = new VBox(12,
                Ui.bloc("Hauteur fixe",
                        Ui.ligne(avec, sans),
                        Ui.ligne(Ui.etiquette("Hauteur"), valeur),
                        Ui.aide("Comme :setz sur les rétros. 0,25 = un quart de case ; une case pleine = 1.")),
                blocAvec, blocSans,
                etat);
        v.setFillWidth(true);
        v.setPadding(new javafx.geometry.Insets(12, 14, 14, 14));
        majResume();
        majSans();
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
        Journal.succes("Hauteur réglée à " + texte(h) + ".");
        // Meme hauteur qu'avant : on reapplique quand meme (dalles posees depuis).
        if (!sansDalles && Math.abs(h - hauteur) < 0.001) { file.submit(() -> appliquer(h)); return; }
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
        // Sans dalles : la nouvelle hauteur vaut pour les poses suivantes.
        if (sansDalles) { majSans(); return; }
        // La frappe au clavier donne une valeur par touche : on attend qu'elle se pose.
        int n = reglage.incrementAndGet();
        Salle.tache("hauteur-reglage", () -> {
            Salle.sommeil(400);
            if (reglage.get() == n) file.submit(() -> { if (reglage.get() == n) appliquer(hauteur); });
        });
    }

    static String texte(double h) {
        return String.format(java.util.Locale.FRANCE, "%.2f", h);
    }

    /** Resultat reussi d'une action : un message (Journal : jeu + console), la ligne d'etat se vide. */
    static void bilan(String s) {
        dire("");
        Journal.succes(s);
    }

    /** Resultat en echec : un message d'erreur (Journal), la ligne d'etat se vide. */
    static void echec(String s) {
        dire("");
        Journal.erreur(s);
    }

    private static void dire(String s) {
        Label l = etat;
        if (l == null) return;
        String t = Ui.majuscule(s);
        Platform.runLater(() -> l.setText(t));
    }

    static boolean occupe() { return occupe; }

    private static void majResume() {
        int[] c = HauteurLogique.compte(toutesDalles(), nosDalles());
        int n = c[0];
        String t = HauteurLogique.resume(c[0], c[1], texte(hauteur));
        boolean o = occupe;
        Platform.runLater(() -> {
            if (resume != null) resume.setText(t);
            if (couvrir != null) couvrir.setText(o ? "Arrêter" : libelleCouvrir());
            if (ramasser != null) {
                ramasser.setDisable(o || n == 0);
                ramasser.setText(n == 0 ? "Ramasser toutes les dalles de l'appart"
                        : "Ramasser toutes les dalles de l'appart (" + n + ")");
            }
        });
    }

    private static void majSans() {
        boolean a = HauteurSansDalles.actif();
        String t = Ui.majuscule(HauteurSansDalles.etat());
        boolean rien = HauteurSansDalles.traites() == 0;
        Platform.runLater(() -> {
            if (etatSans != null) {
                etatSans.setText(t);
                etatSans.getStyleClass().removeAll("etat-ok", "etat-absent");
                etatSans.getStyleClass().add(a ? "etat-ok" : "etat-absent");
            }
            if (interrupteur != null) interrupteur.setText(a ? "Arrêter" : "Activer");
            if (dejaPoses != null) dejaPoses.setDisable(rien);
        });
    }

    /** Resume tenu a jour tout seul (changement de salle, dalle posee ou ramassee). */
    private static volatile boolean surveille = false;
    private static void surveiller() {
        if (surveille) return;
        surveille = true;
        Salle.tache("hauteur-resume", () -> {
            String vu = null;
            while (true) {
                Salle.sommeil(2000);
                try {
                    if (sansDalles || occupe) continue;
                    String sig = Salle.salleId() + "/" + toutesDalles().size() + "/" + nosDalles().size();
                    if (!sig.equals(vu)) { vu = sig; majResume(); }
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
        majResume();
        Set<Integer> types = Generateur.Dalle.typesDalles();
        for (int type : types) Historique.ignorerType(type, 60 * 60_000L);
        List<Integer> ids = new ArrayList<>(nosDalles());
        int poses = 0;
        try {
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
                        dire("Pose des dalles : " + poses + " posée(s), une refusée, je continue…");
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
                            dire("Pose des dalles : " + poses + " posée(s), taille suivante…");
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
                    dire("Pose des dalles : " + poses + " posée(s)…");
                    majResume();
                }
            }
            boolean partie = Salle.salleId() != salle;
            if (poses > 0 && !partie) {
                Salle.sommeil(300);
                appliquer(hauteur);
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
            dire("");
        } finally {
            for (int type : types) Historique.ignorerType(type, 2500);
            occupe = false; arret = false;
            majResume();
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

    /** Met toutes les dalles de l'Atelier a la hauteur h. */
    /** Met toutes les dalles magiques de la salle (les notres + celles deja la) a la hauteur h. */
    private static void appliquer(double h) {
        GPresets gp = Salle.gp();
        List<Integer> nos = nosDalles();
        LinkedHashSet<Integer> ids = new LinkedHashSet<>(nos);
        ids.addAll(toutesDalles());
        ids.removeIf(OutilHauteur::verrouillee);          // dalles d'un calque verrouille : intouchables
        if (gp == null || ids.isEmpty()) { majResume(); return; }
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
            if (!manquees.isEmpty())
                dire("Réglage : " + (ids.size() - manquees.size()) + " / " + ids.size() + " dalle(s)…");
            aFaire = manquees;
        }
        int deja = HauteurLogique.compte(new ArrayList<>(ids), nos)[1];
        // Pas de message dans le jeu a chaque changement de hauteur : journal seulement.
        Journal.info(ids.size() + " dalle(s) réglée(s) à " + texte(h)
                + (deja > 0 ? " (dont " + deja + " déjà là)" : "")
                + (!relue || aFaire.isEmpty() ? "." : ", " + aFaire.size() + " n'ont pas répondu."));
        dire("");
        majResume();
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

    private static void ramasserTout() {
        LinkedHashSet<Integer> tous = new LinkedHashSet<>(nosDalles());
        tous.addAll(toutesDalles());
        List<Integer> ids = new ArrayList<>(tous);
        int verrou = ids.size();
        ids.removeIf(OutilHauteur::verrouillee);          // dalles d'un calque verrouille : laissees
        verrou -= ids.size();
        String laissees = verrou > 0 ? " " + verrou + " dalle(s) d'un calque verrouillé laissée(s) en place." : "";
        if (ids.isEmpty()) {
            if (verrou > 0) bilan("Rien à ramasser :" + laissees);
            majResume();
            return;
        }
        final int salle = Salle.salleId();
        final String cle = cleSalle();
        occupe = true;
        majResume();
        Set<Integer> types = Generateur.Dalle.typesDalles();
        for (int type : types) Historique.ignorerType(type, 30 * 60_000L);
        try {
            int n = 0;
            for (int id : ids) {
                if (arret || Salle.salleId() != salle) break;
                Salle.ramasser(id, false);
                for (int i = 0; i < 10 && Salle.sol(id) != null; i++) Salle.sommeil(100);
                n++;
                dire("Ramassage : " + n + " / " + ids.size());
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
            majResume();
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

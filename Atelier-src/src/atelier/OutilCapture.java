package atelier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * Capture de l'appart.
 *
 * Le client Flash rend TOUTE la salle en image avec la commande chat
 * « :screenshot » (quelle que soit la taille de l'ecran), sur fond transparent
 * et sans la couleur de decor. L'Atelier :
 *
 *   1. fait taper « :screenshot » + Entree dans l'appli Habbo (AppleScript,
 *      autorisation « Accessibilite ») — ou, case « automatique » cochee,
 *      traite tout seul chaque :screenshot tape a la main ;
 *   2. repere le fichier image qui vient d'apparaitre (dossier memorise, dossiers
 *      habituels, sinon Spotlight) et memorise son dossier ;
 *   3. le traite (Capture) : detourage, recadrage, puis fond AUTOUR de la salle
 *      = couleur de decor (toner allume, ou couleur choisie) ou transparent ;
 *      la salle elle-meme (sol, murs, mobis) n'est jamais teintee ;
 *   4. l'enregistre dans Images › Atelier.
 *
 * Le toner de la salle est suivi tout seul (pas de bouton « relire »).
 *
 * En GIF anime, il declenche N captures d'affilee et les assemble avec une
 * meme decoupe.
 *
 * Un seul volet : « Capture ».
 */
public class OutilCapture {

    private static final String CLE_DOSSIER = "capture.dossierSource";
    private static final String CLE_AUTO = "capture.auto";
    private static final long ATTENTE_FICHIER_MS = 30_000;
    private static final long ATTENTE_JEU_MS = 8_000;
    private static final long ATTENTE_MAIN_MS = 5 * 60_000;

    // --- controles
    private Label etat, source, toner, nomDernier;
    private Button capturer;
    private CheckBox auto, coller, appliquerDecor, couleurManuelle, garderFond, seulementSalle,
            gifAnime, garderBrutes;
    private ColorPicker choixDecor, fondJpg;
    private RadioButton png, jpg, gif;
    private Spinner<Integer> tolerance, nbImages, intervalle, delai, largeurF, hauteurF, attenteF;
    private RadioButton modeScreenshot, modeFenetre;
    private CheckBox hautGauche;
    private ImageView apercu;

    // --- etat
    private final AtomicBoolean occupe = new AtomicBoolean(false);
    private volatile boolean annule = false;
    private volatile long depuisMain = 0;
    private volatile File dossierMemorise = null;
    private final Set<String> traites = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> refusesSignales = Collections.synchronizedSet(new HashSet<>());
    private Thread veilleur, suiviToner;

    // ------------------------------------------------------------------ UI

    private static volatile OutilCapture instance;

    /** Lance une capture avec les reglages du volet (bouton de la barre du bas). */
    public static void declencher() {
        OutilCapture c = instance;
        if (c != null) javafx.application.Platform.runLater(() -> { if (!c.occupe.get()) c.lancer(); });
    }

    /**
     * Photo directe (appareil photo de la barre du bas) : agrandit la fenetre
     * Habbo, la photographie, lui rend sa place, puis ouvre l'apercu ou l'on
     * choisit le fond (ApercuCapture). Les reglages du volet servent de base
     * (taille, attente, tolerance), sans rien a cliquer avant.
     */
    public static void photo(String css, Runnable reglages) {
        System.out.println("[Atelier] photo : clic sur l'appareil photo.");
        OutilCapture c = instance;
        if (c == null) { InfoJeu.consigne("La capture n'est pas encore prête : réessaie dans un instant."); return; }
        Platform.runLater(() -> {
            if (!c.occupe.compareAndSet(false, true)) {
                InfoJeu.consigne("Une capture est déjà en cours.");
                return;
            }
            Options o;
            try {
                c.annule = false;
                o = c.lireOptions();
            } catch (Throwable t) {
                c.occupe.set(false);
                System.err.println("[Atelier] photo : " + t);
                InfoJeu.consigne("Photo impossible : " + t);
                return;
            }
            InfoJeu.consigne("Photo de l'appart…");
            Salle.tache("capture-photo", () -> {
                try {
                    Capture.Planche p = c.photographier(o);
                    if (p != null) ApercuCapture.ouvrir(css, p, o.r, reglages);
                } catch (Throwable t) {
                    t.printStackTrace();
                    c.dire("Échec de la capture : " + t);
                    InfoJeu.consigne("Échec de la photo : " + t);
                } finally {
                    c.occupe.set(false);
                }
            });
        });
    }

    /** Agrandit, photographie une fois, remet la fenetre ; la planche detouree, ou null (message dit). */
    private Capture.Planche photographier(Options o) {
        if (ClientModifie.saitCapturer()) {
            // Le jeu se photographie lui-meme : pas d'autorisation macOS.
            Capture.Ou<File> j = Capture.captureParLeJeu(ATTENTE_JEU_MS);
            String erreur = j.erreur;
            Capture.Planche p = null;
            if (j.valeur != null) {
                try { p = Capture.traiter(Capture.lire(j.valeur), o.r); }
                catch (IllegalStateException e) { erreur = "Rien à garder : " + e.getMessage() + "."; }
                catch (Throwable t) { erreur = "Photo du jeu illisible : " + t; }
                finally { j.valeur.delete(); }
            }
            if (erreur != null) { dire(erreur); InfoJeu.consigne(erreur); }
            return p;
        }
        if (!Capture.autorisationEcran())
            dire("macOS demande l'autorisation « Enregistrement de l'écran » — essai quand même…");
        Capture.Ou<Capture.Cadre> lu = Capture.lireCadreHabbo();
        if (lu.erreur != null) { dire(lu.erreur); InfoJeu.consigne(lu.erreur); return null; }
        Capture.Cadre avant = lu.valeur;
        String erreur = null;
        Capture.Planche p = null;
        boolean ancrage = Ancrage.actif();
        try {
            Ancrage.actif(false);
            erreur = Capture.reglerCadreHabbo(o.hautGauche ? 0 : null, 0, o.largeurF, o.hauteurF);
            if (erreur == null) {
                Salle.sommeil(Math.max(0, o.attenteF));
                Capture.Ou<Capture.Photo> ph = Capture.capturerFenetreHabbo(28);
                if (ph.erreur != null) erreur = ph.erreur;
                else {
                    try { p = Capture.traiter(ph.valeur.image, o.r); }
                    catch (IllegalStateException e) { erreur = "Rien à garder : " + e.getMessage() + "."; }
                }
            }
        } catch (Throwable t) {
            erreur = "Échec de la capture de la fenêtre : " + t;
        } finally {
            String r = Capture.reglerCadreHabbo(avant.x, avant.y, avant.l, avant.h);
            if (r != null && erreur == null) erreur = "La fenêtre Habbo n'a pas pu être remise en place (" + r + ").";
            Ancrage.actif(ancrage);
            Ancrage.recoller();
        }
        if (erreur != null) { dire(erreur); InfoJeu.consigne(erreur); return null; }
        return p;
    }

    public Tab construire() {
        instance = this;
        etat = Ui.etat();

        capturer = new Button("Capturer l'appart");
        capturer.getStyleClass().add("primaire");
        capturer.setMaxWidth(Double.MAX_VALUE);
        capturer.setOnAction(e -> { if (occupe.get()) annuler(); else lancer(); });

        coller = new CheckBox("Coller la commande au lieu de la taper");

        // --- methode
        ToggleGroup gm = new ToggleGroup();
        modeScreenshot = new RadioButton(":screenshot (recommandé)");
        modeFenetre = new RadioButton("Fenêtre agrandie (expérimental)");
        modeScreenshot.setToggleGroup(gm); modeFenetre.setToggleGroup(gm);
        modeScreenshot.setSelected(true);
        largeurF = spinner(800, 8000, 3000, 100, 86);
        hauteurF = spinner(600, 6000, 2000, 100, 86);
        attenteF = spinner(200, 10_000, 1500, 100, 86);
        hautGauche = new CheckBox("Placer d'abord la fenêtre en haut à gauche (0, 0)");
        hautGauche.setSelected(true);
        VBox optionsFenetre = new VBox(Ui.DANS_BLOC,
                Ui.ligne(new Label("Taille"), largeurF, new Label("×"), hauteurF),
                Ui.ligne(new Label("Attente du redessin (ms)"), attenteF),
                hautGauche,
                Ui.aide("La fenêtre Habbo est agrandie au-delà de l'écran, photographiée seule, "
                        + "puis remise à sa place et à sa taille. Exige l'autorisation « Enregistrement "
                        + "de l'écran » en plus d'« Accessibilité ». Si la salle est quand même coupée, "
                        + "c'est que Habbo ou macOS a refusé la taille : reviens à :screenshot."));
        optionsFenetre.managedProperty().bind(optionsFenetre.visibleProperty());
        optionsFenetre.setVisible(false);

        auto = new CheckBox("Traiter tout seul chaque :screenshot que je tape moi-même");
        try { auto.setSelected(Preferences.userRoot().node("atelier").getBoolean(CLE_AUTO, false)); }
        catch (Throwable ignored) { }
        autoActif = auto.isSelected();
        auto.selectedProperty().addListener((o, a, b) -> {
            autoActif = b;
            memoriserAuto(b);
            if (b) { armerVeille(); dire("Surveillance : chaque :screenshot tapé dans le jeu sera traité."); }
            else dire("Surveillance arrêtée.");
        });
        Button existante = new Button("Traiter une image existante…");
        existante.setMaxWidth(Double.MAX_VALUE);
        existante.setOnAction(e -> traiterExistante());

        // --- dossier
        source = Ui.valeur("");
        Button choisir = new Button("Choisir…");
        choisir.setOnAction(e -> choisirDossier());
        Button oublier = new Button("Oublier");
        oublier.setOnAction(e -> { dossierMemorise = null; memoriser(null); majSource(); });
        try {
            String s = Preferences.userRoot().node("atelier").get(CLE_DOSSIER, "");
            if (!s.isBlank() && new File(s).isDirectory()) dossierMemorise = new File(s);
        } catch (Throwable ignored) { }
        majSource();

        // --- fond autour de la salle
        appliquerDecor = new CheckBox("Remplir le fond avec la couleur de décor");
        appliquerDecor.setSelected(true);
        toner = Ui.valeur("Toner : —");
        couleurManuelle = new CheckBox("Prendre ma couleur plutôt que le toner");
        choixDecor = new ColorPicker(Color.web("#ff56c2"));
        choixDecor.setPrefWidth(56);

        // --- resultat
        ToggleGroup g = new ToggleGroup();
        png = new RadioButton("PNG"); jpg = new RadioButton("JPG"); gif = new RadioButton("GIF");
        png.setToggleGroup(g); jpg.setToggleGroup(g); gif.setToggleGroup(g);
        png.setSelected(true);
        fondJpg = new ColorPicker(Color.WHITE);
        fondJpg.setPrefWidth(56);
        garderFond = new CheckBox("Garder l'arrière-plan d'origine (simple recadrage)");
        seulementSalle = new CheckBox("Ignorer les éléments séparés (bulles…)");
        tolerance = spinner(0, 80, 12, 1, 72);
        gifAnime = new CheckBox("GIF animé (plusieurs :screenshot d'affilée)");
        gifAnime.setSelected(true);
        nbImages = spinner(2, 30, 8, 1, 66);
        intervalle = spinner(100, 10_000, 1000, 100, 86);
        delai = spinner(50, 5000, 400, 50, 86);
        garderBrutes = new CheckBox("Garder les images brutes de la série");
        Runnable majFormat = () -> {
            boolean anime = gif.isSelected() && gifAnime.isSelected();
            gifAnime.setDisable(!gif.isSelected());
            nbImages.setDisable(!anime); intervalle.setDisable(!anime);
            delai.setDisable(!anime); garderBrutes.setDisable(!anime);
            boolean brut = garderFond.isSelected();
            boolean decor = appliquerDecor.isSelected() && !brut;
            appliquerDecor.setDisable(brut);
            couleurManuelle.setDisable(!decor);
            choixDecor.setDisable(!decor || !couleurManuelle.isSelected());
            // En JPG, la couleur unie sert quand le fond n'est pas la couleur de decor.
            fondJpg.setDisable(!jpg.isSelected() || decor || brut);
        };
        g.selectedToggleProperty().addListener((o, a, b) -> majFormat.run());
        gifAnime.selectedProperty().addListener((o, a, b) -> majFormat.run());
        garderFond.selectedProperty().addListener((o, a, b) -> majFormat.run());
        appliquerDecor.selectedProperty().addListener((o, a, b) -> majFormat.run());
        couleurManuelle.selectedProperty().addListener((o, a, b) -> majFormat.run());
        majFormat.run();

        // La vraie couleur de decor est deja dans la fenetre ; la barre du jeu
        // et ses fenetres s'ecartent par la plus grande composante.
        gm.selectedToggleProperty().addListener((o, a, b) -> {
            boolean f = modeFenetre.isSelected();
            optionsFenetre.setVisible(f);
            coller.setDisable(f);
            seulementSalle.setSelected(f);
            intervalle.getValueFactory().setValue(f ? 300 : 1000);
        });

        // --- apercu
        apercu = new ImageView();
        apercu.setPreserveRatio(true);
        apercu.setFitWidth(310);
        apercu.setFitHeight(220);
        nomDernier = Ui.discret("Aucune capture pour l'instant.");
        Button ouvrir = new Button("Ouvrir le dossier");
        ouvrir.setOnAction(e -> ouvrirDossier());

        VBox v = new VBox(12,
                Ui.bloc("Prendre la photo",
                        Ui.aide("L'Atelier tape « :screenshot » dans le jeu : Habbo rend toute "
                                + "la salle, quelle que soit la taille de l'écran. L'image est "
                                + "retouchée puis enregistrée dans Images › Atelier."),
                        Ui.ligne(modeScreenshot, modeFenetre),
                        optionsFenetre,
                        capturer,
                        Ui.aide("Avant : sois dans la salle et ne laisse aucun autre champ de "
                                + "saisie actif dans Habbo (recherche, messagerie…), sinon la "
                                + "commande y serait tapée. macOS demande l'autorisation "
                                + "« Accessibilité » pour Terminal (ou java)."),
                        coller,
                        auto,
                        Ui.aide("Coché : tape :screenshot dans le jeu quand tu veux, l'Atelier "
                                + "le retouche et l'enregistre tout seul."),
                        existante),
                Ui.bloc("Dossier où le jeu enregistre",
                        source,
                        Ui.ligne(choisir, oublier),
                        Ui.aide("Trouvé tout seul à la première capture, puis retenu. Sinon : "
                                + "Bureau, Images, Téléchargements, Documents, puis Spotlight.")),
                Ui.bloc("Fond autour de l'appart",
                        appliquerDecor,
                        toner,
                        Ui.ligne(couleurManuelle, choixDecor),
                        Ui.aide("Le :screenshot du jeu n'a pas de fond. Seul le vide AUTOUR de "
                                + "la salle prend la couleur de décor (toner allumé, ou ta "
                                + "couleur) : sol, murs et mobis gardent leurs vraies couleurs."),
                        Ui.aide("Case décochée : fond transparent en PNG et GIF, couleur unie "
                                + "ci-dessous en JPG.")),
                Ui.bloc("Résultat",
                        Ui.ligne(png, jpg, gif),
                        Ui.ligne(new Label("Fond uni du JPG"), fondJpg),
                        garderFond,
                        seulementSalle,
                        Ui.ligne(new Label("Tolérance du fond"), tolerance)),
                Ui.bloc("GIF animé",
                        gifAnime,
                        Ui.ligne(new Label("Images"), nbImages),
                        Ui.ligne(new Label("Entre deux captures (ms)"), intervalle),
                        Ui.ligne(new Label("Durée d'une image (ms)"), delai),
                        garderBrutes,
                        Ui.aide("Sans cette case, les images brutes de la série sont effacées "
                                + "(seulement celles apparues pendant la série) ; avec, elles "
                                + "vont dans Images › Atelier › Brutes.")),
                Ui.bloc("Dernière capture",
                        apercu, nomDernier, ouvrir),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        suivreToner();
        if (auto.isSelected()) armerVeille();

        Tab t = new Tab("Capture", sp);
        t.setClosable(false);
        return t;
    }

    private static Spinner<Integer> spinner(int min, int max, int val, int pas, double largeur) {
        Spinner<Integer> s = new Spinner<>(min, max, val, pas);
        s.setEditable(true);
        s.setPrefWidth(largeur);
        return s;
    }

    // ------------------------------------------------------------- dossier

    private void majSource() {
        File d = dossierMemorise;
        Platform.runLater(() -> {
            if (source != null)
                source.setText(d != null ? d.getAbsolutePath() : "Pas encore repéré");
        });
    }

    private void choisirDossier() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Dossier où le jeu enregistre ses :screenshot");
        File init = dossierMemorise != null ? dossierMemorise : Capture.maisonReelle();
        if (init.isDirectory()) dc.setInitialDirectory(init);
        File d = dc.showDialog(fenetre());
        if (d == null) return;
        retenirDossier(d);
        dire("Dossier retenu : " + d.getAbsolutePath());
    }

    private void retenirDossier(File d) {
        if (d == null || d.equals(dossierMemorise)) return;
        dossierMemorise = d;
        memoriser(d);
        majSource();
    }

    private static void memoriser(File d) {
        try {
            Preferences p = Preferences.userRoot().node("atelier");
            if (d == null) p.remove(CLE_DOSSIER); else p.put(CLE_DOSSIER, d.getAbsolutePath());
            p.flush();
        } catch (Throwable ignored) { }
    }

    private Window fenetre() {
        try { return etat.getScene().getWindow(); } catch (Throwable t) { return null; }
    }

    /** Le dossier memorise d'abord, puis les dossiers habituels. */
    private List<File> dossiers() {
        List<File> l = new ArrayList<>();
        if (dossierMemorise != null) l.add(dossierMemorise);
        try {
            for (File d : Capture.dossiersParDefaut()) if (!l.contains(d)) l.add(d);
        } catch (Throwable ignored) { }
        return l;
    }

    // ----------------------------------------------------- reperer le fichier

    private static String cle(File f) { return f.getAbsolutePath() + "|" + f.lastModified(); }

    /** La plus recente des images apparues depuis « depuis », pas encore prise. */
    private File chercherDansDossiers(long depuis) {
        File meilleur = null;
        File sortie = Capture.dossierSortie();
        for (File d : dossiers()) {
            File[] l = d.listFiles();
            if (l == null) {
                if (d.isDirectory() && refusesSignales.add(d.getAbsolutePath()))
                    dire("Accès refusé à « " + d.getName() + " ». Réglages Système › Confidentialité "
                            + "et sécurité › Fichiers et dossiers : autorise Terminal (ou java).");
                continue;
            }
            for (File f : l) {
                if (!Capture.estImage(f) || sortie.equals(f.getParentFile())) continue;
                long m = f.lastModified();
                if (m < depuis || traites.contains(cle(f))) continue;
                if (meilleur == null || m > meilleur.lastModified()) meilleur = f;
            }
        }
        return meilleur;
    }

    private File chercherSpotlight(long depuis) {
        File meilleur = null;
        for (File f : Capture.imagesRecentesSpotlight(depuis)) {
            if (traites.contains(cle(f))) continue;
            if (meilleur == null || f.lastModified() > meilleur.lastModified()) meilleur = f;
        }
        return meilleur;
    }

    /**
     * Attend la nouvelle image (complete) apparue depuis « depuis ». Retient
     * son dossier. null si rien avant la limite ou si annule.
     */
    private File attendreFichier(long depuis, long limiteMs, boolean spotlight) {
        long fin = System.currentTimeMillis() + limiteMs;
        int tour = 0;
        while (System.currentTimeMillis() < fin && !annule) {
            File f = chercherDansDossiers(depuis);
            if (f == null && spotlight && System.currentTimeMillis() - depuis > 2500 && tour % 3 == 0)
                f = chercherSpotlight(depuis);
            if (f != null && stable(f)) {
                traites.add(cle(f));
                retenirDossier(f.getParentFile());
                return f;
            }
            tour++;
            Salle.sommeil(500);
        }
        return null;
    }

    /** Le fichier a fini d'etre ecrit : taille non nulle et stable. */
    private static boolean stable(File f) {
        long avant = -1;
        for (int i = 0; i < 25; i++) {
            long t = f.length();
            if (t > 0 && t == avant) return true;
            avant = t;
            Salle.sommeil(300);
        }
        return false;
    }

    // ------------------------------------------------------ capture pilotee

    private void lancer() {
        if (!occupe.compareAndSet(false, true)) return;
        annule = false;
        capturer.setText("Annuler");
        Options o = lireOptions();
        Salle.tache("capture", () -> {
            try {
                if (!Salle.dansUneSalle())
                    dire("Attention : l'Atelier ne te voit dans aucune salle — capture quand même.");
                if (o.fenetre && !ClientModifie.saitCapturer()) fenetre(o);
                else if (o.format == Capture.Format.GIF && o.anime) serie(o);
                else une(o);
            } catch (Throwable t) {
                dire("Échec de la capture : " + t);
            } finally {
                occupe.set(false);
                Platform.runLater(() -> capturer.setText("Capturer l'appart"));
            }
        });
    }

    private void annuler() {
        annule = true;
        dire("Annulation…");
    }

    /** Declenche :screenshot ; renvoie l'heure de depart, ou -1 (message deja affiche). */
    private long declencher(boolean coller) {
        long t0 = System.currentTimeMillis() - 1000;
        String err = Capture.declencher(coller);
        if (err != null) { dire(err); return -1; }
        return t0;
    }

    /**
     * Une image brute : prise par le jeu (client modifie, sans autorisation
     * macOS) ou, sinon, par :screenshot. null si rien (message deja dit).
     */
    private File prendre(Options o) {
        if (ClientModifie.saitCapturer()) {
            Capture.Ou<File> j = Capture.captureParLeJeu(ATTENTE_JEU_MS);
            if (j.erreur != null) dire(j.erreur);
            return j.valeur;
        }
        long t0 = declencher(o.coller);
        if (t0 < 0) return null;
        File f = attendreFichier(t0, ATTENTE_FICHIER_MS, true);
        if (f == null && !annule) dire(rienRecu());
        return f;
    }

    private void une(Options o) {
        if (ClientModifie.saitCapturer()) {
            dire("Photo de l'appart par le jeu…");
            File f = prendre(o);
            if (f == null) return;
            traiterFichier(f, o);
            rangerBrutes(new ArrayList<>(List.of(f)), o.garderBrutes);
            return;
        }
        dire("Commande :screenshot envoyée au jeu…");
        long t0 = declencher(o.coller);
        if (t0 < 0) return;
        dire("En attente de l'image du jeu…");
        File f = attendreFichier(t0, ATTENTE_FICHIER_MS, true);
        if (annule) { dire("Capture annulée."); return; }
        if (f == null) { dire(rienRecu()); return; }
        traiterFichier(f, o);
    }

    private static String rienRecu() {
        return "Aucune image n'est apparue. As-tu vu le :screenshot partir dans le jeu ? Sinon, "
                + "essaie « Coller la commande », ou coche « Traiter tout seul » et tape-la "
                + "toi-même. Si l'image est ailleurs, indique son dossier.";
    }

    /** GIF anime : N :screenshot d'affilee, une meme decoupe, un seul toner. */
    private void serie(Options o) {
        int n = Math.max(2, o.nb);
        resoudreDecor(o);
        List<File> brutes = new ArrayList<>();
        for (int i = 1; i <= n && !annule; i++) {
            dire("Image " + i + " / " + n + "…");
            File f = prendre(o);
            if (f == null) break;
            brutes.add(f);
            dire("Image " + i + " / " + n + " reçue.");
            if (i < n && !annule) Salle.sommeil(Math.max(0, o.intervalle));
        }

        try {
            if (annule) { dire("Série annulée (" + brutes.size() + " image(s) reçue(s))."); return; }
            if (brutes.size() < 2) {
                if (brutes.size() == 1) {
                    dire("Une seule image reçue : GIF fixe.");
                    Options fixe = o; fixe.anime = false;
                    ecrire(List.of(Capture.traiter(Capture.lire(brutes.get(0)), o.r)), fixe, "");
                }
                return;
            }
            dire("Assemblage du GIF (" + brutes.size() + " images)…");
            List<Capture.Planche> planches = new ArrayList<>();
            for (File f : brutes) {
                try { planches.add(Capture.traiter(Capture.lire(f), o.r)); }
                catch (Throwable t) { System.err.println("[Atelier] capture : " + f + " : " + t); }
            }
            if (planches.isEmpty()) { dire("Aucune image exploitable dans la série."); return; }
            ecrire(planches, o, o.noteDecor);
        } catch (IllegalStateException e) {
            dire("Rien à garder : " + e.getMessage() + ". Essaie une tolérance plus basse, "
                    + "ou « Garder l'arrière-plan d'origine ».");
        } catch (Throwable t) {
            dire("Échec de l'assemblage : " + t);
        } finally {
            rangerBrutes(brutes, o.garderBrutes);
        }
    }

    /**
     * Mode experimental : agrandit la fenetre Habbo, la photographie seule
     * (une fois, ou N fois pour un GIF), puis lui rend TOUJOURS sa place et sa
     * taille d'origine.
     */
    private void fenetre(Options o) {
        resoudreDecor(o);
        if (!Capture.autorisationEcran())
            dire("macOS demande l'autorisation « Enregistrement de l'écran » — essai quand même…");
        Capture.Ou<Capture.Cadre> lu = Capture.lireCadreHabbo();
        if (lu.erreur != null) { dire(lu.erreur); return; }
        Capture.Cadre avant = lu.valeur;

        List<Capture.Planche> planches = new ArrayList<>();
        String erreur = null, note = "";
        boolean ancrage = Ancrage.actif();
        try {
            Ancrage.actif(false);             // l'Atelier ne suit pas la fenetre geante
            dire("Agrandissement de la fenêtre Habbo à " + o.largeurF + "×" + o.hauteurF + "…");
            erreur = Capture.reglerCadreHabbo(o.hautGauche ? 0 : null, 0, o.largeurF, o.hauteurF);
            if (erreur == null) {
                Salle.sommeil(Math.max(0, o.attenteF));
                Capture.Ou<Capture.Cadre> apres = Capture.lireCadreHabbo();
                if (apres.valeur != null && (apres.valeur.l < o.largeurF - 4 || apres.valeur.h < o.hauteurF - 4))
                    note = " Fenêtre limitée à " + apres.valeur.l + "×" + apres.valeur.h + " par macOS ou Habbo.";
                int n = o.anime ? Math.max(2, o.nb) : 1;
                for (int i = 1; i <= n && !annule; i++) {
                    dire("Image " + i + " / " + n + " : capture de la fenêtre…" + note);
                    Capture.Ou<Capture.Photo> ph = Capture.capturerFenetreHabbo(28);
                    if (ph.erreur != null) { erreur = ph.erreur; break; }
                    try { planches.add(Capture.traiter(ph.valeur.image, o.r)); }
                    catch (IllegalStateException e) { erreur = "Rien à garder : " + e.getMessage() + "."; break; }
                    if (i < n && !annule) Salle.sommeil(Math.max(0, o.intervalle));
                }
            }
        } catch (Throwable t) {
            erreur = "Échec de la capture de la fenêtre : " + t;
        } finally {
            String r = Capture.reglerCadreHabbo(avant.x, avant.y, avant.l, avant.h);
            if (r != null) note += " ATTENTION : la fenêtre n'a pas pu être remise à " + avant + " (" + r + ").";
            Ancrage.actif(ancrage);
            Ancrage.recoller();
        }

        if (annule) { dire("Capture annulée." + note); return; }
        if (erreur != null) { dire(erreur + note); return; }
        if (planches.isEmpty()) { dire("Aucune image capturée." + note); return; }
        try {
            Options fin = o;
            fin.anime = o.anime && planches.size() > 1;
            ecrire(planches, fin, o.noteDecor + note);
        } catch (Throwable t) {
            dire("Échec de l'enregistrement : " + t + note);
        }
    }

    /** Efface (ou range dans Atelier/Brutes) UNIQUEMENT les fichiers de la serie. */
    private void rangerBrutes(List<File> brutes, boolean garder) {
        if (brutes.isEmpty()) return;
        File dest = null;
        if (garder) {
            dest = new File(Capture.dossierSortie(), "Brutes");
            if (!dest.isDirectory() && dest.mkdirs()) Capture.rendre(dest);
        }
        for (File f : brutes) {
            try {
                if (garder) {
                    File cible = new File(dest, f.getName());
                    Files.move(f.toPath(), cible.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Capture.rendre(cible);
                } else {
                    Files.deleteIfExists(f.toPath());
                }
            } catch (Throwable t) {
                System.err.println("[Atelier] capture : rangement de " + f + " : " + t);
            }
        }
    }

    // ------------------------------------------ :screenshot tape a la main

    private static void memoriserAuto(boolean b) {
        try {
            Preferences p = Preferences.userRoot().node("atelier");
            p.putBoolean(CLE_AUTO, b);
            p.flush();
        } catch (Throwable ignored) { }
    }

    private synchronized void armerVeille() {
        depuisMain = System.currentTimeMillis() - 1500;
        if (veilleur != null && veilleur.isAlive()) return;
        veilleur = new Thread(this::veiller, "atelier-capture-veille");
        veilleur.setDaemon(true);
        veilleur.start();
    }

    private volatile boolean autoActif = false;

    private void veiller() {
        int tour = 0;
        while (true) {
            try {
                // Pendant une capture pilotee, c'est elle qui prend les fichiers.
                if (autoActif && !occupe.get()) {
                    File f = chercherDansDossiers(depuisMain);
                    // Dossier du jeu pas encore connu : Spotlight, de temps en temps.
                    if (f == null && dossierMemorise == null && tour % 6 == 0)
                        f = chercherSpotlight(depuisMain);
                    if (f != null && stable(f) && occupe.compareAndSet(false, true)) {
                        try {
                            traites.add(cle(f));
                            retenirDossier(f.getParentFile());
                            Options o = surFx(this::lireOptions);
                            traiterFichier(f, o);
                        } finally { occupe.set(false); }
                    }
                }
            } catch (Throwable t) {
                dire("Surveillance : " + t.getMessage());
            }
            tour++;
            Salle.sommeil(800);
        }
    }

    private void traiterExistante() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Image à retraiter");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File init = dossierMemorise != null ? dossierMemorise : Capture.maisonReelle();
        if (init.isDirectory()) fc.setInitialDirectory(init);
        File f = fc.showOpenDialog(fenetre());
        if (f == null) return;
        Options o = lireOptions();
        Salle.tache("capture-retraiter", () -> traiterFichier(f, o));
    }

    // ---------------------------------------------------------- traitement

    /** Les options lues sur le fil JavaFX, figees pour le traitement. */
    private static final class Options {
        Capture.Reglages r = new Capture.Reglages();
        Capture.Format format;
        int fondJpg;
        boolean anime, coller, garderBrutes;
        boolean fenetre, hautGauche;
        int largeurF, hauteurF, attenteF;
        int nb, intervalle, delai;
        boolean decor, manuel;
        int couleurManuelle;
        String noteDecor = "";
    }

    private Options lireOptions() {
        Options o = new Options();
        o.r.tolerance = valeur(tolerance, 12);
        o.r.garderFond = garderFond.isSelected();
        o.r.seulementSalle = seulementSalle.isSelected();
        o.format = jpg.isSelected() ? Capture.Format.JPG : gif.isSelected() ? Capture.Format.GIF : Capture.Format.PNG;
        o.fondJpg = rgb(fondJpg.getValue());
        o.anime = gif.isSelected() && gifAnime.isSelected();
        o.coller = coller.isSelected();
        o.fenetre = modeFenetre.isSelected();
        o.hautGauche = hautGauche.isSelected();
        o.largeurF = valeur(largeurF, 3000);
        o.hauteurF = valeur(hauteurF, 2000);
        o.attenteF = valeur(attenteF, 1500);
        o.garderBrutes = garderBrutes.isSelected();
        o.nb = valeur(nbImages, 8);
        o.intervalle = valeur(intervalle, 1000);
        o.delai = valeur(delai, 400);
        o.decor = appliquerDecor.isSelected() && !o.r.garderFond;
        o.manuel = couleurManuelle.isSelected();
        o.couleurManuelle = rgb(choixDecor.getValue());
        return o;
    }

    private static int valeur(Spinner<Integer> s, int defaut) {
        try {
            String t = s.getEditor().getText();
            if (t != null && !t.isBlank()) return Integer.parseInt(t.trim());
            return s.getValue();
        } catch (Throwable e) { return defaut; }
    }

    private static int rgb(Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16)
             | ((int) Math.round(c.getGreen() * 255) << 8)
             | (int) Math.round(c.getBlue() * 255);
    }

    private static <T> T surFx(Supplier<T> s) throws Exception {
        if (Platform.isFxApplicationThread()) return s.get();
        FutureTask<T> t = new FutureTask<>(s::get);
        Platform.runLater(t);
        return t.get();
    }

    /**
     * Fixe la couleur du fond AUTOUR de la salle : toner allume de la salle,
     * ou couleur choisie ; sinon fond transparent (ou fond uni du JPG).
     */
    private void resoudreDecor(Options o) {
        o.r.fond = -1;
        String sinon = o.format == Capture.Format.JPG ? "fond uni du JPG." : "fond transparent.";
        if (!o.decor) { o.noteDecor = ""; return; }
        if (o.manuel) {
            o.r.fond = o.couleurManuelle;
            o.noteDecor = String.format(" Fond : ta couleur #%06x.", o.couleurManuelle);
            return;
        }
        Capture.Toner t = Capture.tonerDeLaSalle();
        if (t != null && t.allume()) {
            o.r.fond = t.couleur();
            o.noteDecor = String.format(" Fond : couleur de décor #%06x.", t.couleur());
        } else {
            o.noteDecor = (t == null ? " Pas de toner dans la salle : " : " Toner éteint : ") + sinon;
        }
    }

    /** Hors fil JavaFX. Ne laisse jamais remonter d'exception. */
    private void traiterFichier(File f, Options o) {
        try {
            dire("Traitement de « " + f.getName() + " »…");
            resoudreDecor(o);
            Capture.Planche p = Capture.traiter(Capture.lire(f), o.r);
            Options fixe = o; fixe.anime = false;
            ecrire(List.of(p), fixe, o.noteDecor);
        } catch (IllegalStateException e) {
            dire("Rien à garder : " + e.getMessage() + ". Essaie une tolérance plus basse, "
                    + "ou « Garder l'arrière-plan d'origine ».");
        } catch (Throwable e) {
            dire("Échec du traitement de « " + f.getName() + " » : " + e);
        }
    }

    /** Enregistre une image (ou une animation) et met l'apercu a jour. */
    private void ecrire(List<Capture.Planche> planches, Options o, String noteDecor) throws Exception {
        String salle = null;
        try { salle = NomSalle.nomValide(Salle.gp()); } catch (Throwable ignored) { }
        File sortie = Capture.nomDeFichier(salle, o.format);
        Capture.Planche p = planches.get(planches.size() - 1);
        String note = "";
        switch (o.format) {
            case PNG -> Capture.ecrirePng(Capture.cadrer(p, o.r), sortie);
            case JPG -> Capture.ecrireJpg(Capture.cadrer(p, o.r), o.fondJpg, sortie);
            case GIF -> {
                if (o.anime && planches.size() > 1) {
                    List<BufferedImage> imgs = Capture.assembler(planches, o.r);
                    Capture.ecrireGif(imgs, o.delai, sortie);
                    note = " GIF animé de " + imgs.size() + " images.";
                } else {
                    Capture.ecrireGif(List.of(Capture.cadrer(p, o.r)), 0, sortie);
                }
            }
        }
        Capture.rendre(sortie);

        final String msg = "Enregistré : " + sortie.getName() + "." + note + noteDecor;
        InfoJeu.dire("Capture enregistrée (Images › Atelier).");
        Platform.runLater(() -> {
            try { apercu.setImage(new Image(sortie.toURI().toString(), 620, 440, true, true, true)); }
            catch (Throwable ignored) { }
            nomDernier.setText(sortie.getName());
            etat.setText(msg);
        });
    }

    /** Suit le toner de la salle tout seul (entree en salle, reglage, allumage). */
    private synchronized void suivreToner() {
        if (suiviToner != null && suiviToner.isAlive()) return;
        suiviToner = new Thread(() -> {
            String avant = null;
            while (true) {
                String s;
                try {
                    if (!Salle.dansUneSalle()) s = "Toner : tu n'es dans aucune salle.";
                    else {
                        Capture.Toner t = Capture.tonerDeLaSalle();
                        if (t == null) s = "Toner : aucun dans la salle (fond transparent).";
                        else if (!t.allume()) s = String.format("Toner éteint (#%06x) : fond transparent.", t.couleur());
                        else s = String.format("Toner allumé : #%06x.", t.couleur());
                    }
                } catch (Throwable e) { s = "Toner : —"; }
                if (!s.equals(avant)) {
                    final String fin = s;
                    avant = s;
                    Platform.runLater(() -> { if (toner != null) toner.setText(fin); });
                }
                Salle.sommeil(1500);
            }
        }, "atelier-capture-toner");
        suiviToner.setDaemon(true);
        suiviToner.start();
    }

    // ---------------------------------------------------------------- divers

    private void ouvrirDossier() {
        File d = Capture.dossierSortie();
        Salle.tache("capture-ouvrir", () -> {
            try {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    new ProcessBuilder("explorer", d.getAbsolutePath()).start();
                    return;
                }
                List<String> cmd = new ArrayList<>(Capture.prefixeUtilisateur());
                cmd.add("/usr/bin/open");
                cmd.add(d.getAbsolutePath());
                Process p = new ProcessBuilder(cmd).start();
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
                    new ProcessBuilder("/usr/bin/open", d.getAbsolutePath()).start();
            } catch (Throwable e) {
                dire("Impossible d'ouvrir le dossier : " + e.getMessage());
            }
        });
    }

    private void dire(String s) {
        Platform.runLater(() -> { if (etat != null) etat.setText(s); });
    }
}

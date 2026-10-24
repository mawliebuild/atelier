package atelier;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * La progression des actions longues (ramasser des dalles, poser un appart,
 * remplir une zone...), au meme endroit et avec le meme dessin partout.
 *
 *   - Une action longue ouvre un Suivi (debut), le fait avancer (compte ou
 *     part) et le termine (fin) : reussi, partiel ou echec.
 *   - Barre : le composant a poser dans la fenetre d'un outil. Il reste
 *     cache : seul le bandeau du haut montre l'avancement (pas de double). Une ligne
 *     « Nom · 34 / 120 · encore environ 20 s », la barre, et Arreter quand
 *     l'action sait s'interrompre. Couleur normale pendant l'action, puis
 *     verte (tout a reussi), orange (partiel, arrete) ou rouge (echec) ;
 *     elle reste visible quelques secondes, puis disparait.
 *   - Bandeau : une barre discrete sous la barre d'outils du haut, pour voir
 *     l'avancement en regardant le jeu. Seulement si l'action dure (au moins
 *     MONTRER_APRES) : une action eclair ne fait rien clignoter.
 *   - Les lignes d'etat (Ui.etat) qui affichent « Ramassage : 3 / 10 » ont
 *     leur barre d'elles-memes (depuisEtat) : rien a brancher.
 *
 * Tout est appelable depuis n'importe quel fil ; l'affichage passe par le fil
 * JavaFX, au plus quelques fois par seconde.
 */
public final class Avancement {

    private Avancement() { }

    /** Comment l'action s'est terminee. */
    public enum Issue { REUSSI, PARTIEL, ECHEC }

    /** Le bandeau n'apparait que pour une action qui dure. */
    static final long MONTRER_APRES = 800;
    /** Apres la fin, la barre reste en couleur ce temps-la. */
    static final long GARDER_FIN = 4000;
    /** Une ligne d'etat restee sur « 3 / 10 » sans bouger : oubliee. */
    static final long OUBLI_AUTO = 45000;
    /** Une action qui n'a plus donne de nouvelles depuis si longtemps (erreur imprevue) : oubliee. */
    static final long OUBLI = 300000;

    // ================================================================ suivi

    /** Une action longue en cours. */
    public static final class Suivi {
        final String nom;
        final long debut = System.currentTimeMillis();
        final boolean auto;
        /** Suivi d'une ligne d'etat : la ligne (il ne compte que si elle est a l'ecran). */
        java.lang.ref.WeakReference<Label> ligne;
        volatile int fait = -1, total = -1;
        volatile double part = -1;
        volatile String detail;
        volatile Runnable arret;
        volatile boolean arretDemande;
        volatile Issue issue;
        volatile long finA, majA = debut;
        /** Debut de la periode qui sert a estimer le temps restant (remis a zero si le total change). */
        private volatile long estimeDepuis = debut;
        private volatile double estimePart0 = 0;

        Suivi(String nom, boolean auto) { this.nom = Ui.majuscule(nom == null ? "" : nom.trim()); this.auto = auto; }

        /** Ou on en est : fait sur total (total <= 0 : inconnu). */
        public Suivi compte(int fait, int total) {
            if (total != this.total) { estimeDepuis = System.currentTimeMillis(); estimePart0 = total > 0 ? Math.max(0, fait) / (double) total : 0; }
            this.fait = fait;
            this.total = total;
            this.part = total > 0 ? Math.max(0, Math.min(1, fait / (double) total)) : -1;
            majA = System.currentTimeMillis();
            changer();
            return this;
        }

        /** Ou on en est, sans compte : 0..1 ; negatif = sans fin connue. */
        public Suivi part(double p) {
            this.fait = -1;
            this.total = -1;
            this.part = p < 0 ? -1 : Math.min(1, p);
            majA = System.currentTimeMillis();
            changer();
            return this;
        }

        /** Etape en cours, en clair (« Réglage de la hauteur »). Null : rien. */
        public Suivi texte(String t) {
            detail = t == null || t.isBlank() ? null : Ui.majuscule(Ui.accorder(t.trim()));
            majA = System.currentTimeMillis();
            changer();
            return this;
        }

        /** L'action sait s'arreter : le bouton Arreter appelle r. */
        public Suivi arret(Runnable r) { arret = r; changer(); return this; }

        /** Bouton Arreter : appelle l'arret de l'action (une fois). */
        public void arreter() {
            if (arretDemande || finie()) return;
            arretDemande = true;
            Runnable r = arret;
            try { if (r != null) r.run(); } catch (Throwable t) { Journal.debug("avancement : arrêt : " + t); }
            changer();
        }

        public boolean arretDemande() { return arretDemande; }

        public boolean finie() { return issue != null; }

        /** Fin de l'action. */
        public void fin(Issue i) {
            if (finie()) return;
            issue = i == null ? Issue.REUSSI : i;
            finA = System.currentTimeMillis();
            if (issue == Issue.REUSSI && total > 0) { fait = total; part = 1; }
            else if (part < 0 || issue == Issue.ECHEC && part < 0.05) part = 1;   // barre pleine, dans la couleur du resultat
            changer();
        }

        /** Fin d'apres un bilan : rien de rate = reussi, une partie = partiel, rien = echec. */
        public void fin(int reussis, int echecs, boolean arrete) { fin(issue(reussis, echecs, arrete)); }

        /** Fin d'apres le texte du bilan (« 3 échecs », « arrêté »...). */
        public void fin(String bilan) { fin(issue(bilan)); }

        /** Retire la barre sans couleur de fin (action abandonnee avant de commencer). */
        public void oublier() {
            if (finie()) return;
            issue = Issue.REUSSI;
            finA = 0;                                   // deja « vieille » : disparait tout de suite
            changer();
        }

        long age() { return System.currentTimeMillis() - debut; }

        /** Compte affiche : « 34 / 120 », « 45 % », ou rien. */
        String compteTexte() {
            if (total > 0 && fait >= 0) return Math.min(fait, total) + " / " + total;
            if (part >= 0 && issue == null) return Math.round(part * 100) + " %";
            return "";
        }

        /** « Encore environ 20 s », si l'estimation a un sens. */
        String resteTexte() {
            if (issue != null || part <= 0 || part >= 1) return "";
            long t = System.currentTimeMillis() - estimeDepuis;
            double fait = part - estimePart0;
            if (t < 3000 || fait < 0.04) return "";
            return reste((long) (t * (1 - part) / fait));
        }
    }

    /** Logique pure : temps restant en clair (arrondi, jamais au-dela d'une heure). */
    static String reste(long ms) {
        long s = Math.max(1, Math.round(ms / 1000.0));
        if (s < 10) return "Encore quelques secondes";
        if (s < 60) return "Encore environ " + (Math.round(s / 5.0) * 5) + " s";
        long m = Math.round(s / 60.0);
        if (m >= 60) return "Encore plus d'une heure";
        return "Encore environ " + m + " min";
    }

    /** Logique pure : l'issue d'un bilan chiffre. */
    static Issue issue(int reussis, int echecs, boolean arrete) {
        if (reussis <= 0 && echecs > 0 && !arrete) return Issue.ECHEC;
        if (echecs > 0 || arrete) return Issue.PARTIEL;
        return Issue.REUSSI;
    }

    private static final java.util.regex.Pattern ARRET = java.util.regex.Pattern.compile(
            "(arrêt|interromp|restée?\\(?s?\\)? sans|encore là|pas toutes?|en partie)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * Logique pure : l'issue d'un bilan ecrit. Echec avec quelque chose de
     * reussi (« 120 dalles ramassées, 3 échecs ») ou arret : partiel.
     */
    static Issue issue(String bilan) {
        if (bilan == null || bilan.isBlank()) return Issue.REUSSI;
        Journal.Genre g = Journal.genre(bilan);
        boolean arret = ARRET.matcher(bilan).find();
        if (g == Journal.Genre.ERREUR) {
            boolean unPeu = Journal.SUCCES.matcher(bilan).find() && bilan.matches("(?s).*[1-9].*")
                    && !bilan.matches("(?is).*\\b(aucun|aucune|0)\\s.*");
            return unPeu || arret ? Issue.PARTIEL : Issue.ECHEC;
        }
        return arret ? Issue.PARTIEL : Issue.REUSSI;
    }

    // ================================================================ registre

    private static final List<Suivi> suivis = new CopyOnWriteArrayList<>();

    /** Une action longue commence. */
    public static Suivi debut(String nom) {
        Suivi s = new Suivi(nom, false);
        suivis.add(s);
        changer();
        return s;
    }

    /** Une action longue qui sait s'arreter commence. */
    public static Suivi debut(String nom, Runnable arret) { return debut(nom).arret(arret); }

    /**
     * Le suivi a montrer dans le bandeau : le plus recent encore en cours
     * (les suivis explicites passent devant ceux des lignes d'etat), sinon le
     * dernier termine tant qu'il garde sa couleur.
     */
    static Suivi courant() {
        long now = System.currentTimeMillis();
        Suivi enCours = null, enCoursAuto = null, fini = null;
        for (Suivi s : suivis) {
            if (!s.finie()) {
                if (now - s.majA > (s.auto ? OUBLI_AUTO : OUBLI)) continue;
                if (s.auto && !aLEcran(s)) continue;          // fenetre fermee en cours de route
                if (s.auto) enCoursAuto = s; else enCours = s;
            } else if (now - s.finA < GARDER_FIN && (fini == null || s.finA >= fini.finA)) fini = s;
        }
        if (enCours != null) return enCours;
        if (enCoursAuto != null) return enCoursAuto;
        return fini;
    }

    /** La ligne d'etat d'un suivi automatique est-elle affichee ? Fil JavaFX. */
    private static boolean aLEcran(Suivi s) {
        Label l = s.ligne == null ? null : s.ligne.get();
        if (l == null || l.getScene() == null) return false;
        Window w = l.getScene().getWindow();
        return w != null && w.isShowing();
    }

    /** Retire les suivis termines (ou oublies) depuis longtemps. */
    private static void menage() {
        long now = System.currentTimeMillis();
        for (Suivi s : suivis) {
            boolean vieux = s.finie() ? now - s.finA > GARDER_FIN + 500
                                      : now - s.majA > (s.auto ? OUBLI_AUTO : OUBLI);
            if (vieux) suivis.remove(s);
        }
    }

    // ================================================================ affichage

    private static final List<java.lang.ref.WeakReference<Barre>> barres = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean prevu = new AtomicBoolean(false);
    private static javafx.animation.Timeline horloge;

    /** Quelque chose a change : un seul rafraichissement sur le fil JavaFX. */
    static void changer() {
        if (!prevu.compareAndSet(false, true)) return;
        try { Platform.runLater(Avancement::rafraichir); }
        catch (IllegalStateException pasDeFx) { prevu.set(false); }      // tests sans JavaFX
    }

    private static void rafraichir() {
        prevu.set(false);
        menage();
        for (java.lang.ref.WeakReference<Barre> r : barres) {
            Barre b = r.get();
            if (b == null) barres.remove(r); else b.majAffichage();
        }
        bandeau();
        // une horloge tant qu'il y a quelque chose a suivre : temps restant, fin de la couleur
        boolean actif = !suivis.isEmpty();
        if (actif && horloge == null) {
            horloge = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(300), e -> changer()));
            horloge.setCycleCount(javafx.animation.Animation.INDEFINITE);
            horloge.play();
        } else if (!actif && horloge != null) {
            horloge.stop();
            horloge = null;
        }
    }

    /**
     * La barre de progression d'un outil. Elle suit le suivi qu'on lui donne
     * (suivre), ou, sans suivi donne, le suivi courant de l'Atelier
     * (globale). Invisible et sans place quand il n'y a rien a montrer.
     */
    public static final class Barre extends VBox {
        private final boolean globale;
        private final boolean compacte;
        private volatile Suivi suivi;
        /** Suivi venu de la ligne d'etat rattachee (pour), montre si aucun suivi n'est donne. */
        private volatile Suivi auto;
        /** Rattachee a une ligne d'etat : ses « 3 / 10 » s'affichent ici, pas sous le texte. */
        private boolean rattachee;
        /** L'outil a deja son bouton Arreter a cote : pas de second. */
        private boolean sansArret;
        private final Label nom = new Label();
        private final Label compte = new Label();
        private final Label reste = new Label();
        private final ProgressBar barre = new ProgressBar(0);
        private final Button arreter = Icones.sur(new Button("Arrêter"), Icones.ARRET);
        private String classeIssue = null;

        /** Barre d'un outil : montre le suivi passe a suivre(). */
        public Barre() { this(false, false); }

        Barre(boolean globale, boolean compacte) {
            super(3);
            this.globale = globale;
            this.compacte = compacte;
            getStyleClass().add("avancement");
            setFillWidth(true);
            nom.getStyleClass().add("avancement-nom");
            nom.setMinWidth(0);
            nom.setMaxWidth(Double.MAX_VALUE);
            // un nom d'action long passe a la ligne au lieu de finir en « … »
            nom.setWrapText(true);
            nom.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(nom, Priority.ALWAYS);
            compte.getStyleClass().add("avancement-compte");
            compte.setMinWidth(Region.USE_PREF_SIZE);
            reste.getStyleClass().add("avancement-reste");
            reste.setMinWidth(0);
            reste.setWrapText(true);
            reste.setMinHeight(Region.USE_PREF_SIZE);
            reste.managedProperty().bind(reste.visibleProperty());
            barre.setMaxWidth(Double.MAX_VALUE);
            barre.setMinWidth(40);
            HBox.setHgrow(barre, Priority.ALWAYS);
            arreter.setMinWidth(Region.USE_PREF_SIZE);
            arreter.setFocusTraversable(false);
            arreter.getStyleClass().add("avancement-arreter");
            arreter.managedProperty().bind(arreter.visibleProperty());
            arreter.setOnAction(e -> { Suivi s = suivi(); if (s != null) s.arreter(); });
            if (globale) arreter.setGraphic(null);       // sur le gris du bandeau : le texte seul
            HBox ligneBarre = new HBox(8, barre, arreter);
            ligneBarre.setAlignment(Pos.CENTER_LEFT);
            if (compacte) {
                HBox bas = new HBox(8, reste);
                getChildren().addAll(ligneBarre, bas);
            } else {
                HBox tete = new HBox(8, nom, compte);
                tete.setAlignment(Pos.CENTER_LEFT);
                getChildren().addAll(tete, ligneBarre, reste);
            }
            managedProperty().bind(visibleProperty());
            setVisible(false);
            barres.add(new java.lang.ref.WeakReference<>(this));
        }

        /** La barre montre ce suivi (null : plus rien). N'importe quel fil. */
        public void suivre(Suivi s) { suivi = s; changer(); }

        /**
         * Les « 3 / 10 » de cette ligne d'etat (Ui.etat) s'affichent dans cette
         * barre plutot que sous le texte. Un suivi donne par suivre() passe devant.
         */
        public Barre pour(Label etat) {
            if (etat != null) { etat.getProperties().put(CLE_BARRE, this); rattachee = true; }
            return this;
        }

        void suivreAuto(Suivi s) { auto = s; changer(); }

        /** L'outil a deja son propre bouton Arreter : la barre n'en montre pas. */
        public Barre sansArret() { sansArret = true; changer(); return this; }

        Suivi suivi() {
            if (globale) return courant();
            Suivi s = suivi;
            if (s != null && (!s.finie() || System.currentTimeMillis() - s.finA < GARDER_FIN)) return s;
            return auto != null ? auto : s;
        }

        void majAffichage() {
            // une seule barre a l'ecran : le bandeau noir du haut (demande de Mawlie, pas de double affichage)
            if (!globale) { setVisible(false); return; }
            Suivi s = suivi();
            long now = System.currentTimeMillis();
            boolean montrer = s != null && (!s.finie() || now - s.finA < GARDER_FIN)
                    && !(s.auto && !s.finie() && now - s.majA > OUBLI_AUTO);
            if (!montrer) { setVisible(false); return; }
            String n = s.detail != null && !compacte ? (s.nom.isEmpty() ? s.detail : s.nom + " · " + s.detail) : s.nom;
            if (s.finie() && !compacte) n = s.nom + " · " + libelle(s.issue);
            if (!n.equals(nom.getText())) nom.setText(n);
            String c = s.compteTexte();
            if (!c.equals(compte.getText())) compte.setText(c);
            compte.setVisible(!c.isEmpty());
            String r = s.finie() ? "" : (s.arretDemande ? "Arrêt demandé…" : s.resteTexte());
            if (!r.equals(reste.getText())) reste.setText(r);
            reste.setVisible(!r.isEmpty());
            double p = s.part < 0 ? ProgressBar.INDETERMINATE_PROGRESS : s.part;
            if (barre.getProgress() != p) barre.setProgress(p);
            arreter.setVisible(s.arret != null && !s.finie() && !sansArret);
            arreter.setDisable(s.arretDemande);
            String cl = s.issue == null ? null : "avancement-" + s.issue.name().toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Objects.equals(cl, classeIssue)) {
                barre.getStyleClass().removeAll("avancement-reussi", "avancement-partiel", "avancement-echec");
                getStyleClass().removeAll("avancement-reussi", "avancement-partiel", "avancement-echec");
                if (cl != null) { barre.getStyleClass().add(cl); getStyleClass().add(cl); }
                classeIssue = cl;
            }
            setVisible(true);
        }
    }

    static String libelle(Issue i) {
        if (i == null) return "";
        switch (i) {
            case REUSSI:  return "Terminé";
            case PARTIEL: return "En partie";
            default:      return "Échec";
        }
    }

    /** Colore une barre de progression existante selon l'issue (null : couleur normale). */
    public static void colorer(ProgressBar b, Issue i) {
        if (b == null) return;
        Runnable r = () -> {
            b.getStyleClass().removeAll("avancement-reussi", "avancement-partiel", "avancement-echec");
            if (i != null) b.getStyleClass().add("avancement-" + i.name().toLowerCase(java.util.Locale.ROOT));
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    // ================================================================ bandeau

    private static Popup bandeau;
    private static Barre barreBandeau;
    private static Window proprietaire;

    /**
     * Le bandeau sous la barre d'outils du haut : une bulle (Popup), qui ne
     * prend jamais le focus au jeu. Absent hors d'un appart (la barre d'outils
     * n'est pas affichee) : la barre de la fenetre de l'outil suffit.
     */
    private static void bandeau() {
        Suivi s = courant();
        boolean montrer = s != null && (s.finie() ? s.finA - s.debut >= MONTRER_APRES : s.age() >= MONTRER_APRES);
        Window w = null;
        try {
            BarreOutils bo = BarreOutils.instance();
            if (bo != null && bo.fenetre().isShowing()) w = bo.fenetre();
        } catch (Throwable ignored) { }
        if (!montrer || w == null) {
            if (bandeau != null && bandeau.isShowing()) bandeau.hide();
            return;
        }
        if (bandeau == null) {
            barreBandeau = new Barre(true, false);
            barreBandeau.getStyleClass().add("avancement-bandeau");
            barreBandeau.setPrefWidth(340);
            barreBandeau.setMaxWidth(340);
            bandeau = new Popup();
            bandeau.setAutoHide(false);
            bandeau.setHideOnEscape(false);
            bandeau.setAutoFix(true);
            bandeau.getContent().add(barreBandeau);
        }
        barreBandeau.majAffichage();
        if (proprietaire != w) {
            if (bandeau.isShowing()) bandeau.hide();
            proprietaire = w;
            if (w.getScene() != null) {
                barreBandeau.getStylesheets().setAll(w.getScene().getStylesheets());
            }
            final Window f = w;
            f.xProperty().addListener((o, a, b) -> { if (proprietaire == f) placer(); });
            f.yProperty().addListener((o, a, b) -> { if (proprietaire == f) placer(); });
            f.widthProperty().addListener((o, a, b) -> { if (proprietaire == f) placer(); });
        }
        if (!bandeau.isShowing()) bandeau.show(w);
        placer();
    }

    private static void placer() {
        Window w = proprietaire;
        if (bandeau == null || w == null || !bandeau.isShowing()) return;
        double l = barreBandeau.getWidth() > 0 ? barreBandeau.getWidth() : 340;
        bandeau.setX(w.getX() + w.getWidth() / 2 - l / 2);
        bandeau.setY(w.getY() + w.getHeight() + 4);
    }

    // ================================================================ lignes d'etat

    private static final String CLE = "atelier.avancement.suivi";
    private static final String CLE_BARRE = "atelier.avancement.barre";
    private static final java.util.regex.Pattern COMPTE = java.util.regex.Pattern.compile(
            "(\\d[\\d  ]*)\\s*/\\s*(\\d[\\d  ]*)");

    /**
     * Une ligne d'etat (Ui.etat) vient de changer de texte. Un « 3 / 10 » en
     * cours ouvre (ou fait avancer) son suivi, avec une barre sous le texte ;
     * le resultat qui suit le termine, en couleur.
     *
     * @return vrai si la ligne doit rester visible un moment (barre en couleur
     *         de fin) ; cacher est alors appele quand c'est fini.
     */
    static boolean depuisEtat(Label l, String texte, Journal.Genre g, Runnable cacher) {
        Object o = l.getProperties().get(CLE);
        Suivi s = o instanceof Suivi ? (Suivi) o : null;
        if (s != null && s.finie()) { l.getProperties().remove(CLE); s = null; }
        int[] c = g == Journal.Genre.PROGRESSION || g == Journal.Genre.INFO ? compte(texte) : null;
        if (s == null && c != null) {
            // la fenetre suit deja une action donnee (suivre) : ses « 3 / 10 » en sont le detail
            Object b0 = l.getProperties().get(CLE_BARRE);
            Suivi x = b0 instanceof Barre ? ((Barre) b0).suivi : null;
            if (x != null && !x.finie()) return false;
        }
        if (c != null && (c[0] < c[1] && g == Journal.Genre.PROGRESSION || s != null)) {
            if (s == null) {
                s = new Suivi(nomDe(texte), true);
                s.ligne = new java.lang.ref.WeakReference<>(l);
                suivis.add(s);
                l.getProperties().put(CLE, s);
            }
            s.compte(c[0], c[1]);
            barreDe(l).suivreAuto(s);
            return false;
        }
        if (s == null) return false;
        if (g == Journal.Genre.PROGRESSION || g == Journal.Genre.INFO && texte != null && !texte.isBlank()) {
            s.majA = System.currentTimeMillis();          // une etape sans compte : la barre reste ou elle est
            return false;
        }
        l.getProperties().remove(CLE);
        Barre b = barreDe(l);
        if (texte == null || texte.isBlank()) {
            if (s.total > 0 && s.fait >= s.total) s.fin(Issue.REUSSI); else s.oublier();
            if (!b.rattachee) enleverBarre(l);
            return false;
        }
        s.fin(issue(texte));
        if (b.rattachee) return false;                   // la barre de la fenetre garde sa couleur seule
        if (s.finA - s.debut < MONTRER_APRES) { enleverBarre(l); return false; }
        final Suivi fini = s;
        javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(GARDER_FIN));
        p.setOnFinished(e -> {
            if (l.getProperties().get(CLE) != null) return;     // une autre action a repris la ligne
            if (b.auto == fini) enleverBarre(l);
            if (cacher != null) cacher.run();
        });
        p.play();
        return true;
    }

    /** La petite barre sous le texte d'une ligne d'etat (posee en graphique du Label). */
    private static Barre barreDe(Label l) {
        Object o = l.getProperties().get(CLE_BARRE);
        if (o instanceof Barre) {
            Barre b = (Barre) o;
            if (!b.rattachee && l.getGraphic() == null) l.setGraphic(b);
            return b;
        }
        // dans une colonne (VBox) : la barre se pose juste sous la ligne d'etat, alignee a gauche
        if (l.getParent() instanceof VBox) {
            VBox p = (VBox) l.getParent();
            Barre b = new Barre(false, true);
            b.getStyleClass().add("avancement-ligne");
            p.getChildren().add(p.getChildren().indexOf(l) + 1, b);
            return b.pour(l);
        }
        Barre b = new Barre(false, true);
        b.getStyleClass().add("avancement-ligne");
        // en graphique d'un Label, la barre garde sa place : le Label la mesure a chaque changement
        b.managedProperty().unbind();
        b.setManaged(true);
        b.visibleProperty().addListener((obs, x, y) -> l.requestLayout());
        b.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(80, l.widthProperty().subtract(2)));
        l.getProperties().put(CLE_BARRE, b);
        if (l.getGraphic() == null) {
            l.setContentDisplay(javafx.scene.control.ContentDisplay.BOTTOM);
            l.setGraphicTextGap(4);
            l.setGraphic(b);
        }
        return b;
    }

    private static void enleverBarre(Label l) {
        Object o = l.getProperties().get(CLE_BARRE);
        if (o instanceof Barre) {
            ((Barre) o).suivreAuto(null);
            if (l.getGraphic() == o) l.setGraphic(null);
        }
    }

    /** Logique pure : le dernier « n / total » d'un texte, ou null. */
    static int[] compte(String t) {
        if (t == null) return null;
        java.util.regex.Matcher m = COMPTE.matcher(t);
        int[] r = null;
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1).replaceAll("[\\s  ]", ""));
                int b = Integer.parseInt(m.group(2).replaceAll("[\\s  ]", ""));
                if (b > 0) r = new int[]{a, b};
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /** Logique pure : le nom de l'action, d'apres « Ramassage : 3 / 10 » -> « Ramassage ». */
    static String nomDe(String t) {
        if (t == null) return "";
        java.util.regex.Matcher m = COMPTE.matcher(t);
        String avant = m.find() ? t.substring(0, m.start()) : t;
        avant = avant.replaceAll("[\\s:(….\\-—]+$", "").trim();
        return avant.isEmpty() ? "En cours" : avant;
    }

    /** Pour les tests : les suivis connus. */
    static List<Suivi> tous() { return new ArrayList<>(suivis); }
}

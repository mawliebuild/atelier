package atelier;

import extension.GPresets;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Bloc « Prérequis » commun a tous les outils : ce qu'il faut pour que
 * l'outil marche, avec un voyant par condition, mis a jour en temps reel.
 *
 * Seules les conditions qui manquent s'affichent (nom, puis ce qu'il faut
 * faire) ; quand tout est bon, le bloc entier disparait.
 */
public final class Prerequis extends VBox {

    public enum Condition {
        SALLE("Appart"), DROITS("Droits"), DROITS_WIRED("Droits wired"), NOMS("Noms"),
        INVENTAIRE("Inventaire"), BC("Catalogue BC"), MUR("Mur choisi"), COULEUR("Couleur de décor"), DALLE("Dalle magique");
        final String nom;
        Condition(String n) { nom = n; }
    }

    private final Condition[] conditions;
    /** Vrai quand TOUTES les conditions sont remplies : l'outil dessous n'est actif qu'alors. */
    private final javafx.beans.property.ReadOnlyBooleanWrapper pret = new javafx.beans.property.ReadOnlyBooleanWrapper(false);
    public javafx.beans.property.ReadOnlyBooleanProperty pretProperty() { return pret.getReadOnlyProperty(); }
    private final Ui.Voyant[] voyants;

    public Prerequis(Condition... conditions) {
        super(Ui.DANS_BLOC);
        this.conditions = conditions;
        this.voyants = new Ui.Voyant[conditions.length];
        Label titre = Ui.intitule("Prérequis");
        VBox boite = new VBox(Ui.DANS_BLOC);
        boite.getStyleClass().add("boite");
        for (int i = 0; i < conditions.length; i++) {
            voyants[i] = new Ui.Voyant(conditions[i].nom);
            boite.getChildren().add(voyants[i]);
        }
        getChildren().addAll(titre, boite);
        tous.add(new WeakReference<>(this));
        demarrer();
        majUne();
    }

    // ------------------------------------------------------------ mise a jour

    private static final List<WeakReference<Prerequis>> tous = new ArrayList<>();
    private static Timeline horloge;

    private static void demarrer() {
        if (horloge != null) return;
        horloge = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            tous.removeIf(r -> r.get() == null);
            for (WeakReference<Prerequis> r : tous) {
                Prerequis p = r.get();
                if (p != null && p.getScene() != null) {
                    try { p.majUne(); } catch (Throwable ignored) { }
                }
            }
        }));
        horloge.setCycleCount(Timeline.INDEFINITE);
        horloge.play();
    }

    private void majUne() {
        boolean tout = true;
        for (int i = 0; i < conditions.length; i++) {
            String[] r = etat(conditions[i]);
            boolean ok = "ok".equals(r[0]);
            voyants[i].regler(r[0], r[1]);
            // Une condition remplie ne s'affiche pas : seulement ce qui manque.
            voyants[i].setVisible(!ok);
            voyants[i].setManaged(!ok);
            tout &= ok;
        }
        pret.set(tout);
        // Tout est bon : le bloc entier disparait.
        setVisible(!tout);
        setManaged(!tout);
    }

    /** {niveau, raison} : niveau « ok », « attente » ou « absent ». */
    static String[] etat(Condition c) {
        GPresets gp = Salle.gp();
        if (gp == null) return new String[]{"attente", "Connexion au jeu…"};
        try {
            switch (c) {
                case SALLE:
                    return Salle.dansUneSalle() ? ok(nomAppart(gp))
                            : new String[]{"absent", "Entre dans un appart."};
                case DROITS:
                    if (!Salle.dansUneSalle()) return new String[]{"absent", "Entre dans un appart."};
                    return gp.getPermissions() != null && gp.getPermissions().canMoveFurni()
                            ? ok("Oui.")
                            : new String[]{"absent", "Il te les faut dans cet appart."};
                case DROITS_WIRED:
                    if (!Salle.dansUneSalle()) return new String[]{"absent", "Entre dans un appart."};
                    return gp.getPermissions() != null && gp.getPermissions().canModifyWired()
                            ? ok("Oui.")
                            : new String[]{"absent", "Il te les faut dans cet appart."};
                case NOMS:
                    return Salle.furnidataPrete() ? ok("Chargés.")
                            : new String[]{"attente", "Chargement depuis habbo.fr…"};
                case INVENTAIRE: {
                    String e = String.valueOf(gp.getInventory().getState());
                    return "LOADED".equals(e) ? ok("Chargé.")
                            : new String[]{"attente", "Chargement…"};
                }
                case BC: {
                    String e = String.valueOf(gp.getCatalog().getState());
                    return "COLLECTED".equals(e) ? ok("Chargé.")
                            : new String[]{"attente", "Lecture…"};
                }
                case COULEUR: {
                    if (!Salle.dansUneSalle()) return new String[]{"absent", "Entre dans un appart."};
                    if (!Salle.furnidataPrete()) return new String[]{"attente", "Chargement des noms des mobis…"};
                    return OutilCouleur.trouver() != null ? ok("Trouvée.")
                            : new String[]{"absent", "Pose-en une dans l'appart."};
                }
                case DALLE: {
                    if (!Salle.dansUneSalle()) return new String[]{"absent", "Entre dans un appart."};
                    if (gp.stackTile() != null) return ok("Trouvée.");
                    if (OutilMiroir.Altitude.connue()) return ok("Pas besoin : @altitude connue.");
                    return new String[]{"absent", "Pose-en une dans l'appart."};
                }
                case MUR: {
                    SelectionMur.Mur m = SelectionMur.courant();
                    return m != null ? ok(m.nom)
                            : new String[]{"absent", "Clique un mobi mural dans le jeu."};
                }
            }
        } catch (Throwable ignored) { }
        return new String[]{"attente", "Vérification…"};
    }

    private static String[] ok(String s) { return new String[]{"ok", s}; }

    /** Le nom de l'appart ouvert, a defaut « Oui. ». */
    private static String nomAppart(GPresets gp) {
        try { String n = NomSalle.nomValide(gp); if (n != null && !n.isBlank()) return n; } catch (Throwable ignored) { }
        return "Oui.";
    }
}

package atelier;

import atelier.EcartsDefaut.Origine;
import atelier.EcartsDefaut.Valeur;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Sous les ecarts de « En ligne » et « En grille » : d'ou vient l'ecart du
 * mobi choisi, et de quoi l'enregistrer ou revenir au defaut sans changer de
 * volet.
 *
 *     Écart de « Poster » : livré avec le programme.
 *     [Enregistrer cet écart]  [Revenir au défaut]
 *
 * Les boutons n'apparaissent que quand ils servent. Remplit aussi les champs
 * d'ecart quand on clique un autre type de mobi dans le jeu.
 */
public final class EcartsBandeau extends VBox {

    private final Label texte = Ui.discret("");
    private final Button enregistrer = new Button("Enregistrer cet écart");
    private final Button revenir = new Button("Revenir au défaut");

    /** Les champs suivis : {axe droite ?, champ}. Pour « En ligne », l'axe suit le sens. */
    private final java.util.function.Supplier<List<Object[]>> axes;

    private Ecarts.Mobi mobi;
    private int typeCharge = -2;

    private EcartsBandeau(java.util.function.Supplier<List<Object[]>> axes, List<Spinner<Integer>> champs) {
        super(6);
        this.axes = axes;
        texte.setWrapText(true);
        enregistrer.setOnAction(e -> enregistrerMaintenant());
        revenir.setOnAction(e -> revenirAuDefaut());
        FlowPane boutons = new FlowPane(6, 6, enregistrer, revenir);
        getChildren().addAll(texte, boutons);
        for (Spinner<Integer> s : champs) s.valueProperty().addListener((o, a, n) -> majTexte());
        SelectionMur.installer();
        SelectionMur.ecouter(() -> Platform.runLater(() -> maj(false)));
        Ecarts.ecouter(() -> Platform.runLater(this::rafraichir));
        maj(false);
    }

    /** « En grille » : deux champs, un par axe. */
    public static EcartsBandeau grille(Spinner<Integer> droite, Spinner<Integer> haut) {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{true, droite});
        l.add(new Object[]{false, haut});
        return new EcartsBandeau(() -> l, List.of(droite, haut));
    }

    /** « En ligne » : un champ, en pans quand {@code horizontal}, en pixels sinon. */
    public static EcartsBandeau ligne(Spinner<Integer> ecart, BooleanSupplier horizontal) {
        return new EcartsBandeau(() -> {
            List<Object[]> l = new ArrayList<>();
            l.add(new Object[]{horizontal.getAsBoolean(), ecart});
            return l;
        }, List.of(ecart));
    }

    /** Le sens a change (« En ligne ») : le champ prend l'ecart de l'autre axe. Fil JavaFX. */
    public void sensChange() { maj(true); }

    @SuppressWarnings("unchecked")
    private static Spinner<Integer> champ(Object[] a) { return (Spinner<Integer>) a[1]; }

    /** Fil JavaFX. {@code forcer} : remettre les champs aux valeurs connues. */
    private void maj(boolean forcer) {
        SelectionMur.Mur m = SelectionMur.courant();
        if (m == null || m.typeId < 0) {
            mobi = null; typeCharge = -2;
            texte.setText(m == null ? "Clique un mobi mural dans le jeu : son écart s'affiche ici."
                    : "Type du mobi inconnu : reclique-le dans le jeu.");
            montrer(enregistrer, false); montrer(revenir, false);
            return;
        }
        mobi = Ecarts.pour(m.typeId, m.position, m.nom);
        if (forcer || m.typeId != typeCharge) {    // meme type : on garde le reglage en cours
            typeCharge = m.typeId;
            for (Object[] a : axes.get()) champ(a).getValueFactory().setValue(mobi.axe((Boolean) a[0]).valeur);
        }
        majTexte();
    }

    /** Un ecart a change ailleurs : les champs non retouches suivent. */
    private void rafraichir() {
        SelectionMur.Mur m = SelectionMur.courant();
        if (mobi == null || m == null || m.typeId != mobi.type) { maj(false); return; }
        Ecarts.Mobi avant = mobi;
        mobi = Ecarts.pour(m.typeId, m.position, m.nom);
        for (Object[] a : axes.get()) {
            boolean d = (Boolean) a[0];
            Spinner<Integer> s = champ(a);
            if (s.getValue() != null && s.getValue() == avant.axe(d).valeur) s.getValueFactory().setValue(mobi.axe(d).valeur);
        }
        majTexte();
    }

    private void majTexte() {
        if (mobi == null) return;
        boolean modifie = false, enregistreIci = false;
        List<String> origines = new ArrayList<>();
        Origine premiere = null; boolean memes = true;
        for (Object[] a : axes.get()) {
            boolean d = (Boolean) a[0];
            Valeur v = mobi.axe(d);
            Integer x = champ(a).getValue();
            if (x == null || x != v.valeur) modifie = true;
            if (v.origine == Origine.ENREGISTRE) enregistreIci = true;
            if (premiere == null) premiere = v.origine; else if (premiere != v.origine) memes = false;
            origines.add((d ? "→ " : "↑ ") + v.origine.texte);
        }
        String nom = "« " + mobi.nom + " »";
        String t;
        if (modifie) t = "Écart de " + nom + " modifié. Il sera retenu quand tu poseras les copies.";
        else if (memes && premiere != null) t = "Écart de " + nom + " : " + premiere.texte + ".";
        else t = "Écart de " + nom + " : " + String.join(", ", origines) + ".";
        texte.setText(t);
        montrer(enregistrer, modifie);
        montrer(revenir, modifie || enregistreIci);
    }

    private static void montrer(Button b, boolean v) { b.setVisible(v); b.setManaged(v); }

    private void enregistrerMaintenant() {
        if (mobi == null) return;
        int d = -1, h = -1;
        for (Object[] a : axes.get()) {
            Spinner<Integer> s = champ(a);
            Generateur.prendre(s);
            if ((Boolean) a[0]) d = s.getValue(); else h = s.getValue();
        }
        Ecarts.enregistrer(mobi.type, d, h);
    }

    private void revenirAuDefaut() {
        if (mobi == null) return;
        boolean d = false, h = false;
        for (Object[] a : axes.get()) if ((Boolean) a[0]) d = true; else h = true;
        if ((d && mobi.droite.origine == Origine.ENREGISTRE) || (h && mobi.haut.origine == Origine.ENREGISTRE))
            Ecarts.oublier(mobi.type, d, h);
        maj(true);
    }

    /**
     * A la pose : enregistre les ecarts qui different de ceux connus.
     * Fil JavaFX. Renvoie une phrase a ajouter au bilan, ou "".
     */
    public String enregistrerAPose() {
        if (mobi == null) return "";
        int d = -1, h = -1;
        for (Object[] a : axes.get()) {
            boolean dr = (Boolean) a[0];
            Integer x = champ(a).getValue();
            if (x == null || x == mobi.axe(dr).valeur) continue;
            if (dr) d = x; else h = x;
        }
        if (d < 0 && h < 0) return "";
        Ecarts.enregistrer(mobi.type, d, h);
        return " Écart enregistré pour « " + mobi.nom + " ».";
    }
}

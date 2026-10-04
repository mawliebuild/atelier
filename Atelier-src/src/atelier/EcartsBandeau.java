package atelier;

import atelier.EcartsDefaut.Origine;
import atelier.EcartsDefaut.Valeur;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Sous les ecarts de « En ligne » et « En grille » : une ligne courte sur le
 * mobi choisi, l'origine de son ecart, et un ⚙ pour les actions.
 *
 *     [vignette]  Poster  [enregistré]  [⚙]
 *
 * La pastille dit d'ou vient l'ecart (enregistré / livré / mesuré / de base,
 * ou « modifié » quand on a touche aux champs) ; sa bulle donne le detail.
 * Le ⚙ propose « Enregistrer cet écart » et « Revenir au défaut » quand ils
 * servent, et disparait sinon. Remplit aussi les champs d'ecart quand on
 * clique un autre type de mobi dans le jeu.
 */
public final class EcartsBandeau extends HBox {

    private final ImageView image = EcartsVolet.vignetteVue();
    private final StackPane cadre = EcartsVolet.cadre(image, 24);
    private final Label nom = Ui.discret("");
    private final Label pastille = EcartsVolet.pastilleVue();
    private final Button reglages = EcartsVolet.boutonReglages("Enregistrer, revenir au défaut", this::menu);
    private boolean modifie, enregistreIci;

    /** Les champs suivis : {axe droite ?, champ}. Pour « En ligne », l'axe suit le sens. */
    private final java.util.function.Supplier<List<Object[]>> axes;

    private Ecarts.Mobi mobi;
    private int typeCharge = -2;

    private EcartsBandeau(java.util.function.Supplier<List<Object[]>> axes, List<Spinner<Integer>> champs) {
        super(6);
        this.axes = axes;
        setAlignment(Pos.CENTER_LEFT);
        nom.setMinWidth(0);
        nom.setWrapText(false);
        getChildren().addAll(cadre, nom, pastille, reglages);
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
            nom.setText(m == null ? "Clique un mobi mural dans le jeu." : "Type inconnu : reclique le mobi.");
            image.setImage(null);
            EcartsVolet.montrer(cadre, false);
            EcartsVolet.montrer(pastille, false);
            EcartsVolet.montrer(reglages, false);
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
        modifie = false; enregistreIci = false;
        List<Object[]> l = axes.get();
        boolean[] droite = new boolean[l.size()];
        Origine[] origines = new Origine[l.size()];
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            boolean d = (Boolean) l.get(i)[0];
            Valeur v = mobi.axe(d);
            Integer x = champ(l.get(i)).getValue();
            if (x == null || x != v.valeur) modifie = true;
            if (v.origine == Origine.ENREGISTRE) enregistreIci = true;
            droite[i] = d; origines[i] = v.origine;
            if (detail.length() > 0) detail.append("\n");
            detail.append(d ? "→ " : "↑ ").append(v.valeur).append(d ? (v.valeur > 1 ? " pans" : " pan") : " px")
                    .append(" : ").append(v.origine.texte).append(".");
        }
        nom.setText(mobi.nom);
        EcartsVolet.montrer(cadre, true);
        EcartsVolet.montrerVignette(image, mobi.classe, 24);
        EcartsVolet.montrer(pastille, true);
        if (modifie)
            EcartsVolet.majPastille(pastille, "modifié",
                    "Écart modifié. Il sera retenu quand tu poseras les copies.", EcartsVolet.Ton.ATTENTION);
        else
            EcartsVolet.majPastille(pastille, EcartsDefaut.pastille(droite, origines), detail.toString(),
                    enregistreIci ? EcartsVolet.Ton.ACCENT : EcartsVolet.Ton.DISCRET);
        EcartsVolet.montrer(reglages, modifie || enregistreIci);
    }

    /** Le menu du ⚙, selon l'etat. */
    private List<MenuItem> menu() {
        List<MenuItem> m = new ArrayList<>();
        if (mobi == null) return m;
        if (modifie) {
            MenuItem e = new MenuItem("Enregistrer cet écart");
            e.setOnAction(x -> enregistrerMaintenant());
            m.add(e);
        }
        if (modifie || enregistreIci) {
            MenuItem r = new MenuItem("Revenir au défaut");
            r.setOnAction(x -> revenirAuDefaut());
            m.add(r);
        }
        return m;
    }

    private void enregistrerMaintenant() {
        if (mobi == null) return;
        int d = -1, h = -1;
        for (Object[] a : axes.get()) {
            Spinner<Integer> s = champ(a);
            Generateur.prendre(s);
            if ((Boolean) a[0]) d = s.getValue(); else h = s.getValue();
        }
        Ecarts.enregistrer(mobi.type, d, h);
        Journal.succes("Écart de « " + mobi.nom + " » enregistré.");
    }

    private void revenirAuDefaut() {
        if (mobi == null) return;
        boolean d = false, h = false;
        for (Object[] a : axes.get()) if ((Boolean) a[0]) d = true; else h = true;
        if ((d && mobi.droite.origine == Origine.ENREGISTRE) || (h && mobi.haut.origine == Origine.ENREGISTRE)) {
            Ecarts.oublier(mobi.type, d, h);
            Journal.succes("« " + mobi.nom + " » reprend son écart par défaut.");
        }
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

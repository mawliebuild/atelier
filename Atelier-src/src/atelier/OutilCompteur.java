package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.*;

/**
 * Volet « Compteur » : ce que contient la salle, ou seulement la zone.
 *
 * Sols (dont wired), murs, types differents, top des types, altitude maximale,
 * piles (cases couvertes par au moins deux mobis), BC / hors BC et rares d'apres
 * la furnidata. Se met a jour toutes les 2 s tant que le volet est affiche.
 *
 * Pour la zone, un mobi de sol compte si sa case d'origine est dedans ; un mobi
 * mural si la case de mur de sa position (:w=x,y) est dedans.
 */
public class OutilCompteur {

    /** Resultat d'un comptage (calcule hors fil JavaFX). */
    private static final class Comptage {
        boolean salle, zone;
        int sols, murs, wired, piles, bc, horsBc, inconnus, rares;
        double altMax = Double.NaN;
        String ouAltMax = "";
        final Map<String, Integer> types = new HashMap<>();
        long signature;
    }

    private CheckBox seulementZone;
    private Label resume, details, etat;
    private TextField filtre;
    private ListView<String> liste;
    private Comptage dernier;
    private volatile boolean enCours = false;
    private VBox racine;

    public VBox construire() {
        seulementZone = new CheckBox("Seulement la zone");
        seulementZone.selectedProperty().addListener((o, a, b) -> lancer());
        Zone.ecouter(() -> { if (seulementZone.isSelected()) lancer(); });

        resume = Ui.valeur("—");
        resume.setWrapText(true);
        details = new Label("");
        details.setWrapText(true);
        etat = Ui.etat();

        filtre = new TextField();
        filtre.setPromptText("Filtrer les types (nom)");
        filtre.textProperty().addListener((o, a, b) -> afficherTypes());
        liste = new ListView<>();
        liste.setPrefHeight(240);
        liste.setMinHeight(140);

        Button copier = new Button("Copier");
        copier.getStyleClass().add("primaire");
        copier.setMaxWidth(Double.MAX_VALUE);
        copier.setOnAction(e -> copier());
        Button actualiser = new Button("Recompter");
        actualiser.setOnAction(e -> lancer());

        racine = new VBox(12,
                Zone.bloc(),
                Ui.bloc("Portée", seulementZone,
                        Ui.aide("Sans zone définie, c'est toute la salle qui est comptée.")),
                Ui.bloc("Totaux", resume, details),
                Ui.bloc("Types les plus présents", filtre, liste),
                copier, Ui.ligne(actualiser),
                etat);
        racine.setFillWidth(true);
        racine.setPadding(new Insets(12, 14, 14, 14));

        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (Plan.estAffiche(racine)) lancer();
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
        return racine;
    }

    // ------------------------------------------------------------ comptage

    private void lancer() {
        if (enCours) return;
        enCours = true;
        final boolean zone = seulementZone.isSelected();
        Salle.tache("compteur", () -> {
            Comptage c = null;
            String err = null;
            try { c = compter(zone); } catch (Throwable t) { err = String.valueOf(t); }
            final Comptage r = c; final String e = err;
            Platform.runLater(() -> {
                enCours = false;
                if (e != null) { derniereNote = null; etat.setText("Erreur de comptage : " + e); return; }
                appliquer(r);
            });
        });
    }

    private static Comptage compter(boolean seulementZone) {
        Comptage c = new Comptage();
        if (!Salle.dansUneSalle()) return c;
        c.salle = true;
        c.zone = seulementZone && Zone.definie();
        boolean fd = Salle.furnidataPrete();

        Map<Long, Integer> parCase = new HashMap<>();
        long sig = c.zone ? 7 + Zone.minX() * 31L + Zone.maxX() * 977L + Zone.minY() * 7919L + Zone.maxY() * 104729L : 3;
        sig = sig * 31 + (fd ? 1 : 0);

        for (HFloorItem it : Salle.sols()) {
            try {
                int x0 = it.getTile().getX(), y0 = it.getTile().getY();
                if (c.zone && !Zone.contient(x0, y0)) continue;
                String cls = Salle.classe(it.getTypeId(), false);
                c.sols++;
                if (Wired.estWired(cls)) c.wired++;
                c.types.merge(Salle.nom(it.getTypeId(), false), 1, Integer::sum);
                double haut = it.getTile().getZ() + Salle.hauteur(it);
                if (Double.isNaN(c.altMax) || haut > c.altMax) {
                    c.altMax = haut;
                    c.ouAltMax = "(" + x0 + ", " + y0 + ") · " + Salle.nom(it.getTypeId(), false);
                }
                int[] e = Salle.emprise(it);
                for (int dx = 0; dx < e[0]; dx++)
                    for (int dy = 0; dy < e[1]; dy++)
                        parCase.merge(((long) (x0 + dx) << 32) | (y0 + dy), 1, Integer::sum);
                drapeaux(c, Salle.details(cls));
                sig = sig * 31 + it.getId();
                sig = sig * 31 + Double.hashCode(haut) + x0 * 1000L + y0;
            } catch (Throwable ignored) { }
        }
        for (HWallItem w : Salle.murs()) {
            try {
                if (c.zone) {
                    int[] p = caseMur(w.getLocation());
                    if (p == null || !Zone.contient(p[0], p[1])) continue;
                }
                c.murs++;
                c.types.merge(Salle.nom(w.getTypeId(), true), 1, Integer::sum);
                drapeaux(c, detailsMur(Salle.classe(w.getTypeId(), true)));
                sig = sig * 31 + w.getId();
                sig = sig * 31 + String.valueOf(w.getLocation()).hashCode();
            } catch (Throwable ignored) { }
        }
        for (int n : parCase.values()) if (n >= 2) c.piles++;
        c.signature = sig;
        return c;
    }

    private static void drapeaux(Comptage c, Object d) {
        boolean bc, rare;
        if (d instanceof furnidata.details.FloorItemDetails) {
            furnidata.details.FloorItemDetails f = (furnidata.details.FloorItemDetails) d;
            bc = f.isBC; rare = f.isRare;
        } else if (d instanceof furnidata.details.WallItemDetails) {
            furnidata.details.WallItemDetails f = (furnidata.details.WallItemDetails) d;
            bc = f.isBC; rare = f.isRare;
        } else { c.inconnus++; return; }
        if (bc) c.bc++; else c.horsBc++;
        if (rare) c.rares++;
    }

    static furnidata.details.WallItemDetails detailsMur(String classe) {
        if (classe == null || !Salle.furnidataPrete()) return null;
        try { return Salle.gp().getFurniDataTools().getWallItemDetails(classe); }
        catch (Throwable t) { return null; }
    }

    /** Case de mur d'une position murale « :w=3,5 l=12,40 r » ; null si illisible. */
    static int[] caseMur(String pos) {
        if (pos == null) return null;
        int i = pos.indexOf("w=");
        if (i < 0) return null;
        try {
            String s = pos.substring(i + 2).trim();
            int fin = s.indexOf(' ');
            if (fin > 0) s = s.substring(0, fin);
            String[] p = s.split(",");
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ affichage

    private void appliquer(Comptage c) {
        if (c == null) return;
        if (!c.salle) {
            dernier = null;
            resume.setText("—");
            details.setText("");
            liste.getItems().clear();
            derniereNote = null;
            etat.setText(Salle.gp() == null ? "L'Atelier n'est pas encore prêt." : "Pas dans une salle.");
            return;
        }
        boolean change = dernier == null || dernier.signature != c.signature;
        dernier = c;
        resume.setText((c.zone ? "Zone " + Zone.minX() + "," + Zone.minY() + " → " + Zone.maxX() + "," + Zone.maxY()
                : "Toute la salle") + " : " + (c.sols + c.murs) + " mobis");
        details.setText(texteDetails(c));
        if (change) afficherTypes();
        // Pas d'heure ni de « mis a jour » : la ligne d'etat ne change que si
        // la remarque change, pour ne pas effacer le resultat d'une action (Copier).
        String note = "";
        if (seulementZone.isSelected() && !Zone.definie()) note = "Aucune zone définie : toute la salle est comptée.";
        if (!Salle.furnidataPrete()) note = "La furnidata n'est pas encore là : noms techniques, BC et rares inconnus. " + note;
        note = note.trim();
        if (!note.equals(derniereNote)) { derniereNote = note; etat.setText(note); }
    }

    /** Derniere remarque automatique ecrite dans l'etat (pour ne pas la reecrire toutes les 2 s). */
    private String derniereNote = null;

    private static String texteDetails(Comptage c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sols : ").append(c.sols).append(" (dont ").append(c.wired).append(" wired)\n");
        sb.append("Murs : ").append(c.murs).append("\n");
        sb.append("Wired : ").append(c.wired).append("\n");
        sb.append("Types différents : ").append(c.types.size()).append("\n");
        sb.append("Altitude max : ").append(Double.isNaN(c.altMax) ? "—" : Plan.fmt(c.altMax) + " — " + c.ouAltMax).append("\n");
        sb.append("Piles (cases avec 2 mobis ou plus) : ").append(c.piles).append("\n");
        sb.append("BC : ").append(c.bc).append(" · hors BC : ").append(c.horsBc);
        if (c.inconnus > 0) sb.append(" · inconnus : ").append(c.inconnus);
        sb.append("\nRares : ").append(c.rares);
        return sb.toString();
    }

    private List<Map.Entry<String, Integer>> typesTries(Comptage c) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(c.types.entrySet());
        l.sort((a, b) -> b.getValue() != a.getValue().intValue() ? Integer.compare(b.getValue(), a.getValue())
                : a.getKey().compareToIgnoreCase(b.getKey()));
        return l;
    }

    private void afficherTypes() {
        Comptage c = dernier;
        if (c == null) { liste.getItems().clear(); return; }
        String f = filtre.getText() == null ? "" : filtre.getText().trim().toLowerCase();
        List<String> vue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : typesTries(c))
            if (f.isEmpty() || e.getKey().toLowerCase().contains(f))
                vue.add(e.getValue() + " × " + e.getKey());
        if (!vue.equals(liste.getItems())) liste.getItems().setAll(vue);
    }

    private void copier() {
        Comptage c = dernier;
        if (c == null) { etat.setText("Rien à copier."); return; }
        StringBuilder sb = new StringBuilder();
        sb.append(resume.getText()).append("\n").append(texteDetails(c)).append("\n\nTypes :\n");
        for (Map.Entry<String, Integer> e : typesTries(c))
            sb.append("- ").append(e.getKey()).append(" x").append(e.getValue()).append("\n");
        ClipboardContent cc = new ClipboardContent();
        cc.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(cc);
        etat.setText("Comptage copié dans le presse-papiers.");
    }
}

package atelier;

import gearth.extensions.parsers.HFloorItem;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Remplacer dans une zone (Actions des calques) : tous les mobis d'un type
 * dont l'origine est dans la zone sont ramasses, puis le mobi de remplacement
 * est pose a leur place (meme case, altitude, rotation ; etat garde si
 * possible), en une rafale (PoseDirecte). La zone est choisie par
 * CalqueActions.remplacerZone, qui ouvre la fenetre ; ici, son contenu et
 * le travail. Le bilan part dans le jeu.
 */
final class RemplacerZone {

    private RemplacerZone() { }

    /** Un type de mobi present dans la zone (liste deroulante). */
    private static final class Type {
        final int typeId, n;
        final String nom;
        Type(int typeId, String nom, int n) { this.typeId = typeId; this.nom = nom; this.n = n; }
        @Override public String toString() { return Ui.majuscule(nom) + " ×" + n; }
    }

    /** Un mobi ramasse : ce qu'il faut pour poser son remplacant au meme endroit. */
    private static final class Ancien {
        final int id, x, y, rot;
        final double z;
        final String etat;
        Ancien(HFloorItem it) {
            id = it.getId();
            x = it.getTile().getX(); y = it.getTile().getY();
            z = it.getTile().getZ();                       // altitude absolue
            rot = Salle.rotation(it);
            etat = Generateur.etatDe(it);
        }
    }

    // Garde d'une fenetre a l'autre : le choix du modele reste, et ChoixModele
    // ecoute les clics pour toujours (un seul, pas un par fenetre).
    private static Generateur.ChoixModele par;
    private static Generateur.ChoixSource source;
    private static final Label etatModele = Ui.etat();
    private static final CheckBox garderEtat = new CheckBox("Garder l'état");

    private static volatile boolean enCours = false;
    /** Arreter : 1 = pendant le ramassage (on pose quand meme ceux deja ramasses), 2 = pendant la pose. */
    private static volatile int phase = 0;
    private static volatile boolean arretRamassage = false, arretPose = false;

    static boolean enCours() { return enCours; }

    /** Remplit la fenetre (fil JavaFX). autreZone : relancer le choix de zone. */
    static void fenetre(CalqueFenetre f, Runnable autreZone) {
        if (par == null) {
            par = new Generateur.ChoixModele("Remplacer par", false, etatModele);
            source = new Generateur.ChoixSource();
            garderEtat.setSelected(true);
            garderEtat.setWrapText(true);
        }
        Label quoi = Ui.valeur("");
        ComboBox<Type> liste = new ComboBox<>();
        liste.setMaxWidth(Double.MAX_VALUE);
        Runnable maj = () -> {
            Type avant = liste.getValue();
            List<Type> types = types();
            liste.getItems().setAll(types);
            Type garde = null;
            if (avant != null) for (Type t : types) if (t.typeId == avant.typeId) garde = t;
            if (garde != null) liste.setValue(garde);
            else if (!types.isEmpty()) liste.setValue(types.get(0));
            quoi.setText(Ui.accorder("Zone " + Zone.largeur() + " × " + Zone.longueur()
                    + " : " + Zone.mobis().size() + " mobi(s)."));
        };
        maj.run();

        // pipette : le prochain clic sur un mobi de la salle choisit son type dans la liste
        java.util.concurrent.atomic.AtomicReference<Consumer<HFloorItem>> ecoute = new java.util.concurrent.atomic.AtomicReference<>();
        Button pipette = CalqueFenetre.bouton(Icones.PIPETTE, "Pipette",
                "Clique ensuite un mobi de la zone dans le jeu : son type est choisi dans la liste", false, () -> { });
        Runnable finPipette = () -> {
            Consumer<HFloorItem> c = ecoute.getAndSet(null);
            if (c != null) Salle.retirer(c);
        };
        pipette.setOnAction(e -> {
            if (ecoute.get() != null) { finPipette.run(); f.dire(""); return; }      // second clic : annule
            Consumer<HFloorItem> c = it -> Platform.runLater(() -> {
                if (ecoute.get() == null) return;
                finPipette.run();
                f.dire("");
                maj.run();
                for (Type t : liste.getItems())
                    if (t.typeId == it.getTypeId()) { liste.setValue(t); return; }
                InfoJeu.consigne("Ce mobi n'est pas dans la zone.");
            });
            ecoute.set(c);
            Salle.surClicMobi(c);
            f.dire("Clique le mobi à remplacer dans le jeu…");
        });
        HBox ligne = new HBox(5, liste, pipette);
        HBox.setHgrow(liste, javafx.scene.layout.Priority.ALWAYS);

        // confirmation : un second clic sur « Remplacer » avec le meme avertissement
        String[] attendu = {null};
        Button arreter = CalqueFenetre.bouton("Arrêter", false, () -> {
            if (phase == 1) arretRamassage = true; else arretPose = true;
        });
        arreter.setVisible(false);
        arreter.managedProperty().bind(arreter.visibleProperty());
        Button fermer = CalqueFenetre.bouton("Fermer", false, f::fermer);
        Button autre = CalqueFenetre.bouton("Autre zone", false, () -> { f.fermer(); autreZone.run(); });
        Button remplacer = CalqueFenetre.bouton("Remplacer", true, () -> { });
        List<Button> tous = List.of(fermer, autre, remplacer, pipette);
        liste.valueProperty().addListener((o, a, b) -> attendu[0] = null);
        par.ecouter(() -> attendu[0] = null);

        remplacer.setOnAction(e -> {
            if (enCours) { CalqueActions.refus("Un remplacement est déjà en cours."); return; }
            Type t = liste.getValue();
            Generateur.Modele m = par.modele();
            if (t == null) { CalqueActions.refus("Aucun mobi à remplacer dans la zone."); return; }
            if (m == null) { CalqueActions.refus("Choisis d'abord le mobi de remplacement."); return; }
            List<HFloorItem> cibles = new ArrayList<>();
            for (HFloorItem it : Zone.mobis()) if (it.getTypeId() == t.typeId) cibles.add(it);
            if (cibles.isEmpty()) { maj.run(); CalqueActions.refus("Plus aucun mobi de ce type dans la zone."); return; }
            Generateur.Source src = source.source();
            String avert = avertissements(t, cibles.size(), m, src);
            if (avert != null && !avert.equals(attendu[0])) {
                attendu[0] = avert;
                InfoJeu.consigne(avert);
                f.dire(avert + " Clique encore « Remplacer » pour continuer.");
                return;
            }
            attendu[0] = null;
            boolean garder = garderEtat.isSelected();
            enCours = true;
            for (Button b : tous) b.setDisable(true);
            arreter.setVisible(true);
            Salle.tache("remplacer-zone", () -> {
                try { remplacer(cibles, m, src, garder, f); }
                finally {
                    enCours = false;
                    phase = 0;
                    Platform.runLater(() -> {
                        for (Button b : tous) b.setDisable(false);
                        arreter.setVisible(false);
                        f.dire("");
                        maj.run();
                    });
                }
            });
        });

        f.contenu(Ui.bloc("Zone", quoi),
                Ui.bloc("Mobi à remplacer", ligne),
                par.bloc(), etatModele,
                source.bloc(),
                Ui.bloc("Options", garderEtat,
                        Ui.aide("Le nouveau mobi prend l'état de l'ancien quand c'est possible, sinon celui du modèle. "
                                + "Même case, même hauteur, même rotation.")));
        f.boutons(fermer, autre, arreter, remplacer);
        f.surFermeture(finPipette);
        f.montrer();
    }

    /** Les types des mobis dont l'origine est dans la zone, du plus nombreux au moins nombreux. */
    private static List<Type> types() {
        Map<Integer, Integer> n = new LinkedHashMap<>();
        for (HFloorItem it : Zone.mobis()) n.merge(it.getTypeId(), 1, Integer::sum);
        List<Type> r = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : n.entrySet()) r.add(new Type(e.getKey(), Salle.nom(e.getKey(), false), e.getValue()));
        r.sort((a, b) -> b.n != a.n ? Integer.compare(b.n, a.n) : a.nom.compareToIgnoreCase(b.nom));
        return r;
    }

    /**
     * Ce qu'il faut dire avant de ramasser : taille differente (on laisse
     * faire), source qui n'a pas de quoi tout poser (confirmation). null : rien.
     */
    private static String avertissements(Type t, int n, Generateur.Modele m, Generateur.Source src) {
        List<String> r = new ArrayList<>();
        Furnidata.Mobi d = Salle.details(Salle.classe(t.typeId, false));
        if (d != null && (Math.max(1, d.xDim) != m.xDim || Math.max(1, d.yDim) != m.yDim))
            r.add("Taille différente : " + Math.max(1, d.xDim) + " × " + Math.max(1, d.yDim)
                    + " remplacé par " + m.xDim + " × " + m.yDim + ".");
        String manque = manque(t, n, m, src);
        if (manque != null) r.add(manque);
        return r.isEmpty() ? null : String.join(" ", r);
    }

    /** null si la source a de quoi poser n remplacants ; sinon ce qui manque. */
    private static String manque(Type t, int n, Generateur.Modele m, Generateur.Source src) {
        Moteur gp = Salle.gp();
        if (gp == null) return null;
        Integer type = null;
        try { type = gp.getFurniDataTools().getFloorTypeId(m.classe); } catch (Throwable ignored) { }
        if (type == null) return "« " + m.classe + " » inconnu de la furnidata.";
        if (src != Generateur.Source.INVENTAIRE) {
            boolean bc = false;
            try { CatalogueBc cat = gp.getCatalog(); bc = cat != null && cat.getFloorProduct(type) != null; } catch (Throwable ignored) { }
            if (!bc) { Furnidata.Mobi d = Salle.details(m.classe); bc = d != null && d.bcOfferId > 0; }
            if (bc) return null;
            if (src == Generateur.Source.BC) return "Ce mobi n'est pas au BC : rien ne pourra être posé.";
        }
        Inventaire inv = gp.getInventory();
        if (inv == null || inv.getState() != Inventaire.Etat.LOADED)
            return "Inventaire pas encore lu : je ne sais pas s'il y a de quoi tout poser.";
        int k = 0;
        try { List<?> l = inv.getFloorItemsByType(type); k = l == null ? 0 : l.size(); } catch (Throwable ignored) { }
        if (type == t.typeId) k += n;                       // meme mobi : ceux ramasses reviennent dans l'inventaire
        if (k >= n) return null;
        return Ui.accorder("Il manque " + (n - k) + " exemplaire(s) dans l'inventaire (" + k + " sur " + n + ").");
    }

    /** Ramasse puis pose les remplacants. Hors fil JavaFX. */
    private static void remplacer(List<HFloorItem> cibles, Generateur.Modele m, Generateur.Source src,
                                  boolean garder, CalqueFenetre f) {
        arretRamassage = false;
        arretPose = false;
        List<Ancien> anciens = new ArrayList<>();
        for (HFloorItem it : cibles) if (it.getTile() != null) anciens.add(new Ancien(it));
        List<Integer> ids = new ArrayList<>();
        for (Ancien a : anciens) ids.add(a.id);

        // 1. ramassage, au rythme commun ; une seconde passe pour ceux encore la
        phase = 1;
        PoseOutils.Signaux.ramassageEnCours(ids);         // une demande de confirmation du jeu est acceptee
        try {
            for (int passe = 1; passe <= 2 && !arretRamassage; passe++) {
                List<Integer> encore = new ArrayList<>();
                for (int id : ids) if (PoseTapis.encoreLa(id)) encore.add(id);
                if (encore.isEmpty()) break;
                if (passe > 1) Salle.pauseReessai();
                int k = 0;
                for (int id : encore) {
                    if (arretRamassage || !Salle.dansUneSalle()) break;
                    Salle.espacer();
                    Salle.envoyer(PoseOutils.ramassage(id, false, false));     // comme le client
                    int fait = ++k, total = encore.size();
                    Platform.runLater(() -> f.dire("Ramassage : " + fait + "/" + total));
                }
                Salle.envoiFait();
                PoseDirecte.suivre(() -> { int r = 0; for (int id : encore) if (PoseTapis.encoreLa(id)) r++; return r; }, 800, 3000);
            }
        } finally {
            PoseOutils.Signaux.ramassageFini(ids);
        }
        List<Ancien> partis = new ArrayList<>();
        int restes = 0;
        for (Ancien a : anciens) if (PoseTapis.encoreLa(a.id)) restes++; else partis.add(a);
        if (restes > 0 && !arretRamassage) Salle.signalerRefus("ramassage", restes);
        if (partis.isEmpty()) {
            Journal.erreur(Ui.accorder("Rien n'a été ramassé" + (restes > 0 ? " (" + restes + " mobi(s) refusé(s) par le jeu)" : "") + "."));
            return;
        }

        // 2. pose des remplacants, en une rafale
        phase = 2;
        List<PoseDirecte.Sol> sols = new ArrayList<>();
        for (Ancien a : partis) {
            String etat = garder && a.etat.matches("\\d{1,2}") ? a.etat : m.etat;
            sols.add(new PoseDirecte.Sol(m.classe, a.x, a.y, a.z, a.rot, etat));
        }
        PoseDirecte.Resultat r = PoseDirecte.poser(sols, List.of(), src, x -> { }, () -> arretPose,
                (fait, total) -> Platform.runLater(() -> f.dire("Pose : " + fait + "/" + total)));
        int refuses = r.solsRefuses.size(), manquants = Math.max(0, r.manquants - refuses);
        String fin = r.sols.size() + " mobi(s) remplacé(s)";
        if (manquants > 0) fin += ", " + manquants + " manquant(s) (ni dans l'inventaire ni au BC selon la source)";
        if (refuses > 0) fin += ", " + refuses + " refusé(s) par le jeu";
        if (restes > 0) fin += ", " + restes + " pas ramassé(s)";
        if (r.hauteursFausses > 0) fin += ", " + r.hauteursFausses + " hauteur(s) à reprendre";
        if (arretPose || arretRamassage) fin += " (arrêté)";
        fin = Ui.accorder(fin + ".");
        if (r.sols.isEmpty()) Journal.erreur(fin); else Journal.succes(fin);
    }
}

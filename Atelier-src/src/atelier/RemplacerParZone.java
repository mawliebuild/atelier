package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * Remplacer mes mobis par ceux d'une zone (onglet Salle, « Remplacer mes
 * mobis… ») : la zone est choisie par deux clics dans le jeu (comme les
 * calques), puis une petite fenetre (CalqueFenetre) montre ce qui va se
 * passer. La source se regle dans la fenetre (retenue d'une fois a l'autre) :
 *   - « Mobis du Builders Club » : les mobis BC de la zone (drapeau bc de la
 *     furnidata, comme le filtre Origine), pas ceux d'une autre personne ;
 *   - « Mobis de la personne » : les mobis de la zone d'un autre joueur
 *     (liste deroulante s'il y en a plusieurs).
 * Les cibles sont MES mobis hors de la zone. Appariement par type exact (sol :
 * typeId ; mural : typeId, et la variante pour les affiches), dans un ordre
 * fixe (par position) : chaque cible recoit une source du meme type.
 *
 * Travail (hors fil JavaFX) : 1. mes mobis apparies sont ramasses (ils
 * retournent dans mon inventaire et liberent leurs cases) ; 2. les sources
 * sont deplacees par le tapis de dalles (PoseTapis : meme case, rotation,
 * altitude absolue), les muraux par MoveWallItem a la position exacte du
 * mien ; 3. l'etat du mien est remis (PoseDirecte.etats) ; 4. verification
 * dans la salle et un seul message de resultat dans le jeu. Une seule
 * action pour Cmd+Z (Historique.grouper).
 */
final class RemplacerParZone {

    private RemplacerParZone() { }

    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String CLE_SOURCE = "remplacerParZone.source", CLE_PERSONNE = "remplacerParZone.personne";
    private static final String SOURCE_BC = "bc", SOURCE_PERSONNE = "personne";

    private static volatile boolean enCours = false, arret = false;
    /** Choix de la zone en cours pour nous (Zone est partagee avec les calques). */
    private static boolean attente = false, deuxieme = false, ecoute = false;
    private static String css;
    private static CalqueFenetre ouverte;

    static boolean enCours() { return enCours; }

    // ================================================================ zone

    /** Le bouton de l'onglet Salle (fil JavaFX). cssScene : la feuille de style de l'onglet. */
    static void lancer(String cssScene) {
        if (enCours) { CalqueActions.refus("Un remplacement est déjà en cours."); return; }
        if (!Salle.dansUneSalle()) { CalqueActions.refus("Entre d'abord dans une salle."); return; }
        if (cssScene != null) css = cssScene;
        if (!ecoute) { ecoute = true; Zone.ecouter(() -> Platform.runLater(RemplacerParZone::suivreZone)); }
        if (ouverte != null) ouverte.fermer();
        attente = true;
        deuxieme = false;
        Zone.demarrerChoix();                // grille et clics a travers les mobis (GrilleVue.attente)
        InfoJeu.consigne("Remplacer mes mobis : clique le premier coin de la zone.");
    }

    private static void suivreZone() {
        if (!attente) return;
        if (Zone.choixEnCours()) {
            if (Zone.premierCoinChoisi() && !deuxieme) {
                deuxieme = true;
                InfoJeu.consigne("Clique le coin opposé de la zone.");
            }
            return;
        }
        attente = false;
        if (!Zone.definie()) return;
        fenetre();
    }

    // ================================================================ lecture

    /** Un mobi de la salle, reduit a ce qu'il faut pour l'apparier et le remettre en place. */
    private static final class Mobi {
        final int id, typeId;
        final boolean mural;
        final String cle, classe, proprio;
        final int x, y, rot;          // sol : case d'origine et rotation ; mural : case du mur
        final double z;               // sol : altitude absolue
        final String etat;            // sol : etat legacy ; mural : etat du mural
        final String position;        // mural : position complete
        final boolean bc, dansZone;

        Mobi(HFloorItem it, String classe) {
            id = it.getId(); typeId = it.getTypeId(); mural = false;
            this.classe = classe;
            cle = "s" + typeId;
            proprio = proprio(it.getOwnerName());
            x = it.getTile().getX(); y = it.getTile().getY(); z = it.getTile().getZ();
            rot = Salle.rotation(it);
            etat = Generateur.etatDe(it);
            position = null;
            bc = estBc(classe, false);
            dansZone = Zone.contient(x, y);
        }

        Mobi(HWallItem w, String classe, int[] c) {
            id = w.getId(); typeId = w.getTypeId(); mural = true;
            this.classe = classe;
            etat = w.getState();
            // les affiches : meme mobi mais une image par etat
            cle = "m" + typeId + (classe != null && classe.startsWith("poster") ? ":" + etat : "");
            proprio = proprio(w.getOwnerName());
            x = c[0]; y = c[1]; z = 0; rot = 0;
            position = w.getLocation();
            bc = estBc(classe, true);
            dansZone = Zone.contient(x, y);
        }

        String nom() { return Ui.majuscule(Salle.nom(typeId, mural)); }
    }

    /** Une paire : mon mobi (ramasse) et la source qui prend sa place. */
    private static final class Paire {
        final Mobi mien, source;
        Paire(Mobi mien, Mobi source) { this.mien = mien; this.source = source; }
    }

    /** Ce que la fenetre montre et ce que le travail fait. */
    private static final class Plan {
        final List<Paire> paires = new ArrayList<>();
        /** Nom -> nombre de mes mobis sans source du meme type dans la zone. */
        final Map<String, Integer> manques = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int sources, enTrop, sansEquivalent;
    }

    private static String proprio(String s) { return s == null ? "" : s.trim(); }

    /** Mon pseudo (UserObject), null s'il n'est pas encore connu. */
    private static String moi() {
        String p = Patrimoine.pseudo();
        if (p == null || p.isBlank()) p = PlanteSuivi.monNom;
        return p == null || p.isBlank() ? null : p.trim();
    }

    /** BC ou non, comme la colonne Origine de l'onglet Salle (drapeau bc de la furnidata). */
    private static boolean estBc(String classe, boolean mural) {
        if (classe == null) return false;
        try {
            Furnidata fd = Salle.gp().getFurniDataTools();
            Furnidata.Mobi d = mural ? fd.getWallItemDetails(classe) : fd.getFloorItemDetails(classe);
            return d != null && d.isBC;
        } catch (Throwable t) { return false; }
    }

    /** Les mobis de la salle qui comptent (ni fantomes, ni dalles magiques, ni wired). */
    private static List<Mobi> lire() {
        List<Mobi> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (it == null || it.getTile() == null) continue;
            if (GroupeFantomes.estFantome(it.getId()) || GrilleCalcul.estFictif(it.getId())) continue;
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || DalleMagique.estDalle(c) || Wired.estWired(c)) continue;
            r.add(new Mobi(it, c));
        }
        for (HWallItem w : Salle.murs()) {
            if (w == null || GroupeFantomes.estFantome(w.getId())) continue;
            int[] c = GroupeCalcul.caseMur(w.getLocation());
            if (c == null) continue;
            r.add(new Mobi(w, Salle.classe(w.getTypeId(), true), c));
        }
        return r;
    }

    /** Les autres personnes qui ont des mobis dans la zone, par ordre alphabetique. */
    private static List<String> personnes(List<Mobi> mobis, String moi) {
        TreeSet<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Mobi m : mobis)
            if (m.dansZone && !m.proprio.isEmpty() && !m.proprio.equalsIgnoreCase(moi)) s.add(m.proprio);
        return new ArrayList<>(s);
    }

    /** Ordre fixe : par case (y puis x), puis du bas vers le haut, puis id. */
    private static final Comparator<Mobi> ORDRE = Comparator.<Mobi>comparingInt(m -> m.mural ? 1 : 0)
            .thenComparingInt(m -> m.y).thenComparingInt(m -> m.x)
            .thenComparingDouble(m -> m.z)
            .thenComparing(m -> m.position == null ? "" : m.position)
            .thenComparingInt(m -> m.id);

    /**
     * Le plan : sources (zone, BC ou la personne choisie), cibles (mes mobis
     * hors de la zone), appariees par type. personne : null en mode BC.
     */
    private static Plan plan(List<Mobi> mobis, String moi, String personne) {
        Plan p = new Plan();
        boolean bc = personne == null;
        List<Mobi> sources = new ArrayList<>(), cibles = new ArrayList<>();
        for (Mobi m : mobis) {
            if (m.dansZone) {
                boolean autre = !m.proprio.isEmpty() && !m.proprio.equalsIgnoreCase(moi);
                if (bc ? m.bc && !autre : m.proprio.equalsIgnoreCase(personne)) sources.add(m);
            } else if (m.proprio.equalsIgnoreCase(moi)) cibles.add(m);
        }
        sources.sort(ORDRE);
        cibles.sort(ORDRE);
        p.sources = sources.size();
        Map<String, Deque<Mobi>> parType = new HashMap<>();
        for (Mobi s : sources) parType.computeIfAbsent(s.cle, k -> new ArrayDeque<>()).add(s);
        // seuls les types presents dans la zone comptent : les autres de mes mobis ne sont pas concernes
        for (Mobi c : cibles) {
            Deque<Mobi> d = parType.get(c.cle);
            if (d == null) continue;
            Mobi s = d.poll();
            if (s != null) p.paires.add(new Paire(c, s));
            else { p.manques.merge(c.nom(), 1, Integer::sum); p.sansEquivalent++; }
        }
        for (Deque<Mobi> d : parType.values()) p.enTrop += d.size();
        return p;
    }

    // ================================================================ fenetre

    private static void fenetre() {
        CalqueFenetre f = new CalqueFenetre(css, "Remplacer mes mobis", null);
        ouverte = f;
        f.surFermeture(() -> { if (ouverte == f) ouverte = null; });

        Label zone = Ui.valeur("");
        ToggleGroup g = new ToggleGroup();
        RadioButton rBc = new RadioButton("Mobis du Builders Club");
        RadioButton rPers = new RadioButton("Mobis de la personne");
        rBc.setToggleGroup(g); rPers.setToggleGroup(g);
        rBc.setTooltip(Ui.bulle("Les mobis BC posés dans la zone remplacent les tiens."));
        rPers.setTooltip(Ui.bulle("Les mobis d'un autre joueur posés dans la zone remplacent les tiens."));
        (SOURCE_PERSONNE.equals(PREFS.get(CLE_SOURCE, SOURCE_BC)) ? rPers : rBc).setSelected(true);
        ComboBox<String> qui = new ComboBox<>();
        qui.setMaxWidth(Double.MAX_VALUE);
        qui.visibleProperty().bind(rPers.selectedProperty().and(javafx.beans.binding.Bindings.isNotEmpty(qui.getItems())));
        qui.managedProperty().bind(qui.visibleProperty());
        Label resume = new Label();
        resume.setWrapText(true);
        resume.setMaxWidth(272);
        ProgressBar barre = new ProgressBar(0);
        barre.setMaxWidth(Double.MAX_VALUE);
        barre.setVisible(false);
        barre.managedProperty().bind(barre.visibleProperty());

        Button fermer = CalqueFenetre.bouton("Fermer", false, f::fermer);
        Button autre = CalqueFenetre.bouton("Autre zone", false, () -> { f.fermer(); lancer(null); });
        Button arreter = CalqueFenetre.bouton("Arrêter", false, () -> { arret = true; f.dire("Arrêt demandé…"); });
        arreter.setVisible(false);
        arreter.managedProperty().bind(arreter.visibleProperty());
        Button remplacer = CalqueFenetre.bouton("Remplacer", true, () -> { });

        Plan[] courant = {null};
        boolean[] remplissage = {false};
        Runnable maj = () -> {
            if (enCours) return;
            String moi = moi();
            List<Mobi> mobis = lire();
            int n = 0;
            for (Mobi m : mobis) if (m.dansZone) n++;
            zone.setText(Ui.accorder("(" + Zone.minX() + "," + Zone.minY() + ") → (" + Zone.maxX() + "," + Zone.maxY()
                    + ") : " + n + " mobi(s)."));
            List<String> pers = personnes(mobis, moi);
            remplissage[0] = true;
            String garde = qui.getValue() != null ? qui.getValue() : PREFS.get(CLE_PERSONNE, "");
            if (!pers.equals(qui.getItems())) qui.getItems().setAll(pers);
            String choisi = null;
            for (String s : pers) if (s.equalsIgnoreCase(garde)) choisi = s;
            if (choisi == null && !pers.isEmpty()) choisi = pers.get(0);
            qui.setValue(choisi);
            remplissage[0] = false;
            boolean bc = rBc.isSelected();
            if (moi == null) {
                courant[0] = null;
                resume.setText("Ton pseudo n'est pas encore connu : attends quelques secondes, puis rouvre la fenêtre.");
            } else if (!bc && pers.isEmpty()) {
                courant[0] = null;
                resume.setText("Aucun mobi d'une autre personne dans la zone.");
            } else {
                Plan p = plan(mobis, moi, bc ? null : choisi);
                courant[0] = p;
                resume.setText(texte(p, bc, choisi, !bc && pers.size() > 1));
            }
            remplacer.setDisable(courant[0] == null || courant[0].paires.isEmpty());
            f.ajuster();
        };
        rBc.selectedProperty().addListener((o, a, b) -> {
            PREFS.put(CLE_SOURCE, b ? SOURCE_BC : SOURCE_PERSONNE);
            maj.run();
        });
        qui.valueProperty().addListener((o, a, b) -> {
            if (remplissage[0]) return;
            if (b != null) PREFS.put(CLE_PERSONNE, b);
            maj.run();
        });
        maj.run();

        List<Control> pendant = List.of(fermer, autre, remplacer, rBc, rPers, qui);
        remplacer.setOnAction(e -> {
            maj.run();                                   // la salle a pu changer depuis
            Plan p = courant[0];
            if (p == null || p.paires.isEmpty()) { CalqueActions.refus("Rien à remplacer."); return; }
            if (enCours || RemplacerZone.enCours() || Groupes.occupe() || PoseTapis.enCours()) {
                CalqueActions.refus("Une action est déjà en cours.");
                return;
            }
            List<Integer> sols = new ArrayList<>(), murs = new ArrayList<>();
            for (Paire x : p.paires) {
                (x.mien.mural ? murs : sols).add(x.mien.id);
                (x.source.mural ? murs : sols).add(x.source.id);
            }
            String v = Groupes.refusVerrouMobis(sols, murs);
            if (v != null) { InfoJeu.consigne(v); return; }
            enCours = true;
            arret = false;
            for (Control c : pendant) c.setDisable(true);
            arreter.setVisible(true);
            barre.setProgress(0);
            barre.setVisible(true);
            f.ajuster();
            Salle.tache("remplacer-par-zone", () -> {
                try { travailler(p, f, barre); }
                finally {
                    enCours = false;
                    Platform.runLater(() -> {
                        for (Control c : pendant) c.setDisable(false);
                        arreter.setVisible(false);
                        barre.setVisible(false);
                        f.dire("");
                        maj.run();
                    });
                }
            });
        });

        VBox source = new VBox(6, rBc, rPers, qui);
        f.contenu(Ui.bloc("Zone", zone),
                Ui.bloc("Source", source),
                resume, barre);
        f.boutons(fermer, autre, arreter, remplacer);
        f.montrer();
    }

    /** Le resume de la fenetre. */
    private static String texte(Plan p, boolean bc, String personne, boolean plusieurs) {
        StringBuilder s = new StringBuilder();
        if (plusieurs) s.append("Plusieurs personnes ont des mobis dans la zone : seuls ceux de ")
                .append(personne).append(" sont pris. ");
        if (p.sources == 0) {
            s.append(bc ? "Aucun mobi du Builders Club dans la zone." : "Aucun mobi de " + personne + " dans la zone.");
            return s.toString();
        }
        if (p.paires.isEmpty() && p.sansEquivalent == 0) {
            s.append("Aucun de tes mobis hors de la zone n'est du même type que ceux de la zone.");
            return s.toString();
        }
        s.append(Ui.accorder(p.paires.size() + " mobi(s) remplacé(s) par ceux de la zone, "
                + p.sansEquivalent + " sans équivalent."));
        if (!p.manques.isEmpty()) {
            List<String> l = new ArrayList<>();
            int k = 0;
            for (Map.Entry<String, Integer> e : p.manques.entrySet()) {
                if (k++ == 6) { l.add("…"); break; }
                l.add(e.getKey() + " ×" + e.getValue());
            }
            s.append(" Pas assez dans la zone : ").append(String.join(", ", l)).append('.');
        }
        if (p.enTrop > 0) s.append(' ').append(Ui.accorder(p.enTrop + " mobi(s) de la zone en trop : ils restent à leur place."));
        s.append(" Tes mobis remplacés retournent dans ton inventaire.");
        return s.toString();
    }

    // ================================================================ travail

    private static boolean present(Mobi m) {
        return m.mural ? Salle.mur(m.id) != null : PoseTapis.encoreLa(m.id);
    }

    private static void avance(CalqueFenetre f, ProgressBar barre, double v, String texte) {
        Platform.runLater(() -> {
            barre.setProgress(Math.max(0, Math.min(1, v)));
            if (texte != null) f.dire(texte);
        });
    }

    /** Ramasse mes mobis apparies, puis met les sources a leur place. Hors fil JavaFX. */
    private static void travailler(Plan p, CalqueFenetre f, ProgressBar barre) {
        int salle = Groupes.salleCourante();
        java.util.function.BooleanSupplier stop = () -> arret || Groupes.salleCourante() != salle;
        List<Paire> paires = p.paires;
        List<Integer> ids = new ArrayList<>();
        for (Paire x : paires) ids.add(x.mien.id);
        Journal.debug("remplacer par la zone : " + paires.size() + " paire(s), " + p.sansEquivalent
                + " sans équivalent, " + p.enTrop + " source(s) en trop.");

        Historique.grouper(true);               // une seule action pour Cmd+Z
        try {
            // 1. ramassage de mes mobis, au rythme commun ; une seconde passe pour ceux encore la
            PoseOutils.Signaux.ramassageEnCours(ids);     // une demande de confirmation du jeu est acceptee
            try {
                for (int passe = 1; passe <= 2 && !stop.getAsBoolean(); passe++) {
                    List<Mobi> encore = new ArrayList<>();
                    for (Paire x : paires) if (present(x.mien)) encore.add(x.mien);
                    if (encore.isEmpty()) break;
                    if (passe > 1) Salle.pauseReessai();
                    int k = 0;
                    for (Mobi m : encore) {
                        if (stop.getAsBoolean()) break;
                        Salle.espacer();
                        Salle.envoyer(PoseOutils.ramassage(m.id, m.mural, false));     // comme le client
                        k++;
                        avance(f, barre, 0.3 * k / encore.size(), "Ramassage : " + k + "/" + encore.size());
                    }
                    Salle.envoiFait();
                    PoseDirecte.suivre(() -> { int r = 0; for (Mobi m : encore) if (present(m)) r++; return r; }, 800, 3000);
                }
            } finally {
                PoseOutils.Signaux.ramassageFini(ids);
            }
            if (Groupes.salleCourante() != salle) { Journal.erreur("Tu as quitté la salle pendant le remplacement."); return; }
            List<Paire> libres = new ArrayList<>();
            int pasRamasses = 0;
            for (Paire x : paires) {
                if (present(x.mien)) pasRamasses++;
                else if (present(x.source)) libres.add(x);
            }
            if (pasRamasses > 0 && !arret) Salle.signalerRefus("ramassage", pasRamasses);
            if (libres.isEmpty()) {
                CalqueActions.resultat(Ui.accorder("Rien n'a été remplacé" + (pasRamasses > 0
                        ? " : " + pasRamasses + " de tes mobis refusé(s) par le jeu au ramassage (droits ?)" : "") + "."));
                return;
            }

            // 2. les sources a la place des miens : sols par le tapis de dalles, muraux ensuite (etape 3 du tapis)
            List<PoseTapis.Piece> pieces = new ArrayList<>();
            List<Paire> murs = new ArrayList<>();
            for (Paire x : libres) {
                if (x.source.mural) { murs.add(x); continue; }
                HFloorItem it = Salle.sol(x.source.id);
                if (it == null) continue;
                int[] e = PoseTapis.emprise(x.source.classe, x.mien.rot);
                // pas reprenable : ramasser le mobi d'une autre personne l'enverrait dans SON inventaire
                pieces.add(PoseTapis.Piece.deplace(x.source.id, x.source.nom(), x.mien.x, x.mien.y,
                        Generateur.arrondi(Math.max(0, x.mien.z)), x.mien.rot, Salle.rotation(it), e[0], e[1], false));
            }
            PoseTapis.Suivi suivi = (fait, total, texte) ->
                    avance(f, barre, 0.3 + 0.65 * (total <= 0 ? 0 : (double) fait / total), texte);
            Runnable etape3 = murs.isEmpty() ? null : () -> deplacerMuraux(murs, stop, suivi);
            if (!pieces.isEmpty()) {
                PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(Generateur.Source.INVENTAIRE_PUIS_BC, stop,
                        m -> Platform.runLater(() -> f.dire(m)), suivi, false);
                PoseTapis.Bilan b = PoseTapis.executer(pieces, jeu, suivi, etape3);
                Journal.debug("remplacer par la zone : tapis, " + b.reussis() + "/" + pieces.size() + " sol(s) à leur hauteur.");
            } else if (etape3 != null) etape3.run();

            // 3. l'etat du mien (couleur, allume...) sur la source
            Map<Integer, String> etats = new LinkedHashMap<>();
            for (Paire x : libres) {
                if (x.source.mural) continue;
                HFloorItem it = Salle.sol(x.source.id);
                if (it != null && x.mien.etat.matches("\\d{1,2}") && !Generateur.etatDe(it).equals(x.mien.etat))
                    etats.put(x.source.id, x.mien.etat);
            }
            int etatsFaux = 0;
            if (!etats.isEmpty() && !stop.getAsBoolean()) {
                avance(f, barre, 0.97, "États : " + etats.size());
                etatsFaux = PoseDirecte.etats(Salle.gp(), etats, stop);
            }

            // 4. verification dans la salle
            int ok = 0, ailleurs = 0, hauteur = 0;
            for (Paire x : libres) {
                if (x.source.mural) {
                    HWallItem w = Salle.mur(x.source.id);
                    if (w != null && SelectionMur.normaliser(w.getLocation()).equals(SelectionMur.normaliser(x.mien.position))) ok++;
                    else ailleurs++;
                    continue;
                }
                HFloorItem it = Salle.sol(x.source.id);
                if (it == null || it.getTile().getX() != x.mien.x || it.getTile().getY() != x.mien.y) { ailleurs++; continue; }
                if (Math.abs(it.getTile().getZ() - x.mien.z) > PoseTapis.TOLERANCE) hauteur++;
                ok++;
            }
            avance(f, barre, 1, null);
            Journal.debug("remplacer par la zone : " + ok + " à leur case, " + ailleurs + " pas arrivé(s), "
                    + hauteur + " hauteur(s) fausse(s), " + etatsFaux + " état(s) faux, " + pasRamasses + " pas ramassé(s).");

            String fin = ok + " mobi(s) remplacé(s)";
            if (p.sansEquivalent > 0) fin += ", " + p.sansEquivalent + " sans équivalent dans la zone";
            if (ailleurs > 0) fin += ", " + ailleurs + " mobi(s) de la zone pas déplacé(s) (droits ?) : les tiens sont dans ton inventaire";
            if (hauteur > 0) fin += ", " + hauteur + " hauteur(s) à reprendre";
            if (etatsFaux > 0) fin += ", " + etatsFaux + " état(s) différent(s)";
            if (pasRamasses > 0) fin += ", " + pasRamasses + " de tes mobis refusé(s) au ramassage";
            if (arret) fin += " (arrêté)";
            if (ok > 0) fin += ". " + WindowsClavier.texte("Cmd+Z pour annuler");
            fin = Ui.accorder(fin + ".");
            if (ok == 0) Journal.erreur(fin); else CalqueActions.resultat(fin);
        } finally {
            Historique.grouper(false);
        }
    }

    /** MoveWallItem a la position exacte du mien, puis un renvoi pour ceux pas arrives. */
    private static void deplacerMuraux(List<Paire> murs, java.util.function.BooleanSupplier stop, PoseTapis.Suivi suivi) {
        List<Paire> reste = new ArrayList<>(murs);
        for (int tour = 0; tour <= Salle.REESSAIS && !reste.isEmpty() && !stop.getAsBoolean(); tour++) {
            if (tour > 0) Salle.pauseReessai();
            int n = 0;
            for (Paire x : reste) {
                if (stop.getAsBoolean()) break;
                Salle.espacer();
                Salle.deplacerMur(x.source.id, SelectionMur.normaliser(x.mien.position));
                n++;
                if (tour == 0) suivi.progres(n, reste.size(), "Muraux : " + n + "/" + reste.size());
            }
            final List<Paire> l = reste;
            PoseDirecte.suivre(() -> { int r = 0; for (Paire x : l) if (!muralArrive(x)) r++; return r; }, 700, 1500);
            List<Paire> encore = new ArrayList<>();
            for (Paire x : reste) if (!muralArrive(x) && Salle.mur(x.source.id) != null) encore.add(x);
            reste = encore;
        }
        if (!reste.isEmpty() && !stop.getAsBoolean()) Salle.signalerRefus("mural pas déplacé", reste.size());
    }

    private static boolean muralArrive(Paire x) {
        HWallItem w = Salle.mur(x.source.id);
        return w != null && SelectionMur.normaliser(w.getLocation()).equals(SelectionMur.normaliser(x.mien.position));
    }
}

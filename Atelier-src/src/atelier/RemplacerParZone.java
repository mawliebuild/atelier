package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * Remplacer des mobis de l'appart par ceux d'autres personnes (onglet Salle,
 * « Remplacer des mobis… ») : le bouton ouvre directement une petite fenetre
 * (CalqueFenetre) :
 *   - « Personnes » : une case par personne qui a des mobis dans l'appart
 *     (choix multiple, retenu) ; les mobis des personnes cochees sont les sources ;
 *   - « Remplacent » : les mobis du Builders Club (vrais ids de la plage
 *     0x7FFF0000.., quel que soit leur proprietaire) ou mes mobis (inventaire) ;
 *   - « Sols » (case d'activation) : les sources sont les mobis de sol des
 *     personnes dans une zone choisie par deux clics dans le jeu ; les cibles
 *     sont hors de la zone ;
 *   - « Muraux » (case d'activation) : aucune zone, les muraux des personnes
 *     des types coches, ou qu'ils soient.
 * Appariement par type exact (sol : typeId ; mural : typeId, et la variante
 * pour les affiches), dans un ordre fixe (par position) : chaque cible recoit
 * une source du meme type.
 *
 * Travail (hors fil JavaFX) : 1. les cibles appariees sont ramassees (elles
 * liberent leurs cases) ; 2. les sources sont deplacees par le tapis de dalles
 * (PoseTapis : meme case, rotation, altitude absolue), les muraux par
 * MoveWallItem a la position exacte ; 3. l'etat de la cible est remis
 * (PoseDirecte.etats) ; 4. verification dans la salle, remise en place des
 * cibles de sol dont la source n'est pas arrivee, un seul message de resultat
 * dans le jeu et un bilan detaille dans la fenetre. Une seule action pour
 * Cmd+Z (Historique.grouper).
 */
final class RemplacerParZone {

    private RemplacerParZone() { }

    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("atelier");
    private static final String CLE_SOURCE = "remplacerParZone.cible", CLE_PERSONNES = "remplacerParZone.personnes",
            CLE_SOLS = "remplacerParZone.sols", CLE_MURS = "remplacerParZone.murs";
    private static final String CIBLE_BC = "bc", CIBLE_MIENS = "miens", CIBLE_DEUX = "deux";
    /** Ce qui est remplace : 0 les mobis du BC, 1 les miens, 2 les deux. */
    private static final int BC = 0, MIENS = 1, DEUX = 2;

    private static volatile boolean enCours = false, arret = false;
    /** Le remplacement en cours, dans la barre commune (fenetre et bandeau du haut, Arreter compris). */
    private static volatile Avancement.Suivi avCourant;
    /** Choix de la zone en cours pour nous (Zone est partagee avec les calques). */
    private static boolean attente = false, deuxieme = false, ecoute = false;
    private static String css;
    private static CalqueFenetre ouverte;
    /** Mise a jour de la fenetre ouverte (zone choisie...), null sans fenetre. */
    private static Runnable majOuverte;
    /** La zone des sols (minX, minY, maxX, maxY), choisie pour nous, et sa salle. */
    private static volatile int[] zone;
    private static volatile int zoneSalle = -1;
    /** Le bilan du dernier remplacement, garde jusqu'au suivant. */
    private static Resultat dernier;

    static boolean enCours() { return enCours; }

    // ================================================================ zone

    /** Le bouton de l'onglet Salle (fil JavaFX). cssScene : la feuille de style de l'onglet. */
    static void lancer(String cssScene) {
        if (cssScene != null) css = cssScene;
        if (ouverte != null && ouverte.ouverte()) { ouverte.montrer(); return; }
        if (enCours) { CalqueActions.refus("Un remplacement est déjà en cours."); return; }
        if (!Salle.dansUneSalle()) { CalqueActions.refus("Entre d'abord dans un appart."); return; }
        if (!ecoute) { ecoute = true; Zone.ecouter(() -> Platform.runLater(RemplacerParZone::suivreZone)); }
        fenetre();
    }

    /** Le bouton « Choisir la zone » de la section Sols. */
    private static void choisirZone() {
        if (enCours) return;
        attente = true;
        deuxieme = false;
        Zone.demarrerChoix();                // grille et clics a travers les mobis (GrilleVue.attente)
        InfoJeu.consigne("Clique le premier coin de la zone des sols.");
        if (majOuverte != null) majOuverte.run();
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
        if (Zone.definie()) {
            zone = new int[]{Zone.minX(), Zone.minY(), Zone.maxX(), Zone.maxY()};
            zoneSalle = Salle.salleId();
        }
        if (majOuverte != null) majOuverte.run();
        else if (Zone.definie() && Salle.dansUneSalle() && !enCours) fenetre();
    }

    /** La zone des sols vaut-elle pour la salle ouverte ? */
    private static boolean zoneValide() { return zone != null && zoneSalle == Salle.salleId(); }

    private static boolean dansZone(int x, int y) {
        int[] z = zone;
        return z != null && zoneSalle == Salle.salleId() && x >= z[0] && x <= z[2] && y >= z[1] && y <= z[3];
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
            bc = estBc(id);
            dansZone = RemplacerParZone.dansZone(x, y);
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
            bc = estBc(id);
            dansZone = false;          // les muraux ne dependent pas de la zone
        }

        String nom() { return Ui.majuscule(Salle.nom(typeId, mural)); }

        /** « case 4,7 » ou « mur 3,5 », pour le bilan de la fenetre. */
        String ou() { return (mural ? "mural " : "case ") + x + "," + y; }

        String jeton() { return (mural ? "m" : "s") + id; }

        /** Nom avec l'etat des affiches (une image par etat). */
        String nomComplet() { return nom() + (cle.indexOf(':') > 0 ? " (" + etat + ")" : ""); }

        /** Icone du mobi (chargee en fond), null si inconnue. */
        javafx.scene.image.Image icone() {
            if (classe == null) return null;
            int r = REVISIONS.computeIfAbsent((mural ? "m:" : "s:") + classe,
                    k -> PrixTexte.revision(Salle.gp(), mural, classe));
            return PrixVignettes.icone(classe, r);
        }
    }

    /** Revision de chaque classe, pour l'adresse de son icone. */
    private static final Map<String, Integer> REVISIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Une paire : la cible (ramassee) et la source qui prend sa place. */
    private static final class Paire {
        final Mobi mien, source;
        Paire(Mobi mien, Mobi source) { this.mien = mien; this.source = source; }
    }

    /** Ce que la fenetre montre et ce que le travail fait. */
    private static final class Plan {
        final List<Paire> paires = new ArrayList<>();
        /** Nom -> nombre de cibles sans source du meme type. */
        final Map<String, Integer> manques = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        /** Les cibles sans source du meme type (elles restent en place). */
        final List<Mobi> sansSource = new ArrayList<>();
        /** Les mobis des personnes sans cible du meme type (ils ne bougent pas). */
        final List<Mobi> inutilises = new ArrayList<>();
        int sources, enTrop, sansEquivalent;
        int sourcesSols, sourcesMurs, pairesSols, pairesMurs, enTropSols, enTropMurs;
    }

    private static String proprio(String s) { return s == null ? "" : s.trim(); }

    /** Mon pseudo (UserObject), null s'il n'est pas encore connu. */
    private static String moi() {
        String p = Patrimoine.pseudo();
        if (p == null || p.isBlank()) p = PlanteSuivi.monNom;
        return p == null || p.isBlank() ? null : p.trim();
    }

    /**
     * Pose depuis le Builders Club : le serveur leur donne des ids de la plage
     * 0x7FFF0000.. (le drapeau bc de la furnidata dit seulement que le TYPE
     * existe au BC, pas d'ou vient ce mobi-ci).
     */
    private static boolean estBc(int id) { return id >= Salle.ID_FICTIF_MIN; }

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

    /** Les autres personnes qui ont des mobis (non BC) dans l'appart, avec leur nombre, par ordre alphabetique. */
    private static TreeMap<String, Integer> personnes(List<Mobi> mobis, String moi) {
        TreeMap<String, Integer> s = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Mobi m : mobis)
            if (!m.bc && !m.proprio.isEmpty() && (moi == null || !m.proprio.equalsIgnoreCase(moi)))
                s.merge(m.proprio, 1, Integer::sum);
        return s;
    }

    /** Les types de muraux de ces personnes (cle -> « Nom ×n »), dans l'ordre des noms. */
    private static LinkedHashMap<String, String> mursDe(List<Mobi> mobis, Set<String> personnes) {
        Map<String, int[]> n = new HashMap<>();
        Map<String, String> noms = new HashMap<>();
        for (Mobi m : mobis)
            if (m.mural && !m.bc && personnes.contains(m.proprio)) {
                n.computeIfAbsent(m.cle, k -> new int[1])[0]++;
                noms.putIfAbsent(m.cle, m.nomComplet());
            }
        List<String> cles = new ArrayList<>(n.keySet());
        cles.sort(Comparator.comparing(noms::get, String.CASE_INSENSITIVE_ORDER));
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        for (String c : cles) r.put(c, noms.get(c) + " ×" + n.get(c)[0]);
        return r;
    }

    /** Ordre fixe : par case (y puis x), puis du bas vers le haut, puis id. */
    private static final Comparator<Mobi> ORDRE = Comparator.<Mobi>comparingInt(m -> m.mural ? 1 : 0)
            .thenComparingInt(m -> m.y).thenComparingInt(m -> m.x)
            .thenComparingDouble(m -> m.z)
            .thenComparing(m -> m.position == null ? "" : m.position)
            .thenComparingInt(m -> m.id);

    /**
     * Le plan : sources (les mobis des personnes : sols dans la zone, muraux
     * des types coches), cibles (les mobis BC, ou mes mobis ; hors de la zone
     * pour les sols), appariees par type. personnes : insensible a la casse.
     * Les sols ne comptent que si avecSols et une zone est choisie ; les
     * muraux que si avecMurs.
     */
    private static Plan plan(List<Mobi> mobis, String moi, Set<String> personnes, int mode,
                             boolean avecSols, boolean avecMurs, Set<String> mursChoisis) {
        Plan p = new Plan();
        TreeSet<String> qui = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        qui.addAll(personnes);
        boolean sols = avecSols && zoneValide();
        List<Mobi> sources = new ArrayList<>(), cibles = new ArrayList<>();
        for (Mobi m : mobis) {
            boolean deLaPersonne = !m.bc && qui.contains(m.proprio);
            boolean mien = !m.bc && moi != null && m.proprio.equalsIgnoreCase(moi);
            boolean cible = !deLaPersonne && (mode == BC ? m.bc : mode == MIENS ? mien : m.bc || mien);
            if (m.mural) {
                // muraux : pas de zone ; ceux des personnes, des types coches, ou qu'ils soient
                if (!avecMurs) continue;
                if (deLaPersonne) { if (mursChoisis.contains(m.cle)) sources.add(m); }
                else if (cible) cibles.add(m);
                continue;
            }
            if (!sols) continue;
            if (m.dansZone) { if (deLaPersonne) sources.add(m); }
            else if (cible) cibles.add(m);
        }
        sources.sort(ORDRE);
        cibles.sort(ORDRE);
        p.sources = sources.size();
        Map<String, Deque<Mobi>> parType = new HashMap<>();
        for (Mobi s : sources) {
            parType.computeIfAbsent(s.cle, k -> new ArrayDeque<>()).add(s);
            if (s.mural) p.sourcesMurs++; else p.sourcesSols++;
        }
        // seuls les types des sources comptent : les autres de mes mobis ne sont pas concernes
        for (Mobi c : cibles) {
            Deque<Mobi> d = parType.get(c.cle);
            if (d == null) continue;
            Mobi s = d.poll();
            if (s != null) {
                p.paires.add(new Paire(c, s));
                if (c.mural) p.pairesMurs++; else p.pairesSols++;
            } else {
                p.manques.merge(c.nom(), 1, Integer::sum);
                p.sansSource.add(c);
                p.sansEquivalent++;
            }
        }
        for (Deque<Mobi> d : parType.values())
            for (Mobi s : d) { p.enTrop++; p.inutilises.add(s); if (s.mural) p.enTropMurs++; else p.enTropSols++; }
        return p;
    }

    // ================================================================ fenetre

    /** Une section avec sa case d'activation en tete (a cote de l'intitule). */
    private static VBox section(String titre, CheckBox active, Node... contenu) {
        VBox v = Ui.bloc(titre, contenu);
        Object t = v.getProperties().get("atelier.bloc.tete");
        if (t instanceof HBox) ((HBox) t).getChildren().add(0, active);
        else v.getChildren().add(0, active);              // repli : en premiere ligne
        active.setFocusTraversable(false);
        return v;
    }

    private static Label texte(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    /** Une liste verticale qui defile (jamais de barre horizontale). */
    private static ScrollPane defilement(Node contenu, double max) {
        ScrollPane sp = new ScrollPane(contenu);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        if (contenu instanceof javafx.scene.layout.Region)
            sp.prefViewportHeightProperty().bind(Bindings.min(max, ((javafx.scene.layout.Region) contenu).heightProperty()));
        sp.setMaxHeight(max + 4);
        return sp;
    }

    private static Set<String> retenues() {
        TreeSet<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String x : PREFS.get(CLE_PERSONNES, "").split("\n")) if (!x.isBlank()) s.add(x.trim());
        return s;
    }

    private static void fenetre() {
        CalqueFenetre f = new CalqueFenetre(css, "Remplacer des mobis", null);
        ouverte = f;

        // ---- personnes
        VBox listePers = new VBox(4);
        Label sansPers = Ui.discret("Personne d'autre n'a de mobis dans l'appart.");
        sansPers.managedProperty().bind(sansPers.visibleProperty());
        ScrollPane defPers = defilement(listePers, 104);
        defPers.managedProperty().bind(defPers.visibleProperty());
        Map<String, CheckBox> cochesPers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        // ---- cibles
        ToggleGroup g = new ToggleGroup();
        RadioButton rBc = new RadioButton("Les mobis du Builders Club");
        RadioButton rPers = new RadioButton("Mes mobis");
        RadioButton rDeux = new RadioButton("Les deux");
        rBc.setToggleGroup(g); rPers.setToggleGroup(g); rDeux.setToggleGroup(g);
        rBc.setTooltip(Ui.bulle("Les mobis remplacés retournent au Builders Club."));
        rPers.setTooltip(Ui.bulle("Tes mobis de l'inventaire. Les remplacés y retournent."));
        rDeux.setTooltip(Ui.bulle("Ceux du Builders Club et les tiens, chacun là où il était pris."));
        String cibleRetenue = PREFS.get(CLE_SOURCE, CIBLE_DEUX);
        (CIBLE_MIENS.equals(cibleRetenue) ? rPers : CIBLE_DEUX.equals(cibleRetenue) ? rDeux : rBc).setSelected(true);

        // ---- sols
        CheckBox actSols = new CheckBox();
        actSols.setSelected(PREFS.getBoolean(CLE_SOLS, true));
        actSols.setTooltip(Ui.bulle("Remplacer les mobis de sol"));
        Runnable[] majRef = {null};
        Button choisir = CalqueFenetre.bouton(Icones.CADRE, "Choisir la zone",
                "Deux clics dans le jeu : les coins de la zone", false, RemplacerParZone::choisirZone);
        Label zoneTexte = Ui.valeur("");
        HBox ligneZone = new HBox(8, choisir, zoneTexte);
        ligneZone.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label lSols = texte("");
        VBox corpsSols = new VBox(6, ligneZone, lSols);

        // ---- muraux : une case par type de mural des personnes (cochees par defaut)
        CheckBox actMurs = new CheckBox();
        actMurs.setSelected(PREFS.getBoolean(CLE_MURS, true));
        actMurs.setTooltip(Ui.bulle("Remplacer les muraux"));
        VBox listeMurs = new VBox(4);
        ScrollPane defMurs = defilement(listeMurs, 110);
        defMurs.managedProperty().bind(defMurs.visibleProperty());
        Label sansMurs = Ui.discret("Aucun mural de ces personnes dans l'appart.");
        sansMurs.managedProperty().bind(sansMurs.visibleProperty());
        Label lMurs = texte("");
        VBox corpsMurs = new VBox(6, sansMurs, defMurs, lMurs);
        Map<String, CheckBox> cochesMurs = new LinkedHashMap<>();
        Set<String> decoches = new HashSet<>();

        // ---- bilan avant de lancer : un resume, puis les mobis en images
        Label bilan = texte("");
        VBox apercu = new VBox(2);
        ScrollPane defApercu = defilement(apercu, 210);
        defApercu.managedProperty().bind(defApercu.visibleProperty());

        // ---- dernier remplacement (garde jusqu'au suivant)
        Label tete = Ui.valeur("");
        tete.setWrapText(true);
        tete.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox lignes = new VBox(3);
        ScrollPane defRes = defilement(lignes, 150);
        VBox blocRes = Ui.bloc("Dernier remplacement", tete, defRes,
                Ui.aide("Un clic sur une ligne met le mobi en valeur dans le jeu."));
        blocRes.managedProperty().bind(blocRes.visibleProperty());
        List<String> fixe = new ArrayList<>();          // jetons mis en valeur par un clic
        f.surFermeture(() -> {
            if (ouverte == f) { ouverte = null; majOuverte = null; }
            if (!fixe.isEmpty()) { fixe.clear(); MiseEnValeur.survol(null); }
        });

        ProgressBar barre = new ProgressBar(0);
        barre.setMaxWidth(Double.MAX_VALUE);
        barre.setVisible(false);
        barre.managedProperty().bind(barre.visibleProperty());

        Button fermer = CalqueFenetre.bouton("Fermer", false, f::fermer);
        Button arreter = CalqueFenetre.bouton("Arrêter", false, () -> arret = true);
        arreter.setVisible(false);
        arreter.managedProperty().bind(arreter.visibleProperty());
        Button remplacer = CalqueFenetre.bouton("Remplacer", true, () -> { });

        boolean[] debugMursFait = {false};
        Plan[] courant = {null};
        Runnable maj = () -> {
            if (enCours) return;
            String moi = moi();
            List<Mobi> mobis = lire();
            int mode = rBc.isSelected() ? BC : rPers.isSelected() ? MIENS : DEUX;
            if (!debugMursFait[0]) { debugMursFait[0] = true; debugMurs(mobis, moi); }

            // personnes : on garde les coches en cours, sinon celles retenues, sinon toutes
            TreeMap<String, Integer> pers = personnes(mobis, moi);
            if (!pers.keySet().equals(cochesPers.keySet())) {
                Set<String> ret = retenues();
                boolean uneRetenue = false;
                for (String s : pers.keySet()) if (ret.contains(s)) uneRetenue = true;
                Map<String, Boolean> avant = new HashMap<>();
                for (Map.Entry<String, CheckBox> e : cochesPers.entrySet()) avant.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isSelected());
                cochesPers.clear();
                List<CheckBox> l = new ArrayList<>();
                for (String s : pers.keySet()) {
                    CheckBox c = new CheckBox();
                    Boolean a = avant.get(s.toLowerCase(Locale.ROOT));
                    c.setSelected(a != null ? a : !uneRetenue || ret.contains(s));
                    c.setWrapText(true);
                    c.selectedProperty().addListener((o, x, y) -> {
                        List<String> garde = new ArrayList<>();
                        for (Map.Entry<String, CheckBox> e : cochesPers.entrySet()) if (e.getValue().isSelected()) garde.add(e.getKey());
                        PREFS.put(CLE_PERSONNES, String.join("\n", garde));
                        if (majRef[0] != null) majRef[0].run();
                    });
                    cochesPers.put(s, c);
                    l.add(c);
                }
                listePers.getChildren().setAll(l);
            }
            for (Map.Entry<String, CheckBox> e : cochesPers.entrySet())
                e.getValue().setText(e.getKey() + " ×" + pers.getOrDefault(e.getKey(), 0));
            sansPers.setVisible(pers.isEmpty());
            defPers.setVisible(!pers.isEmpty());
            TreeSet<String> qui = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, CheckBox> e : cochesPers.entrySet()) if (e.getValue().isSelected()) qui.add(e.getKey());
            String deQui = qui.size() == 1 ? "de " + qui.first() : "de ces personnes";
            String leurs = mode == BC ? "ceux du Builders Club" : mode == MIENS ? "les tiens" : "les tiens ou ceux du Builders Club";

            // muraux : les types des personnes cochees
            LinkedHashMap<String, String> types = mursDe(mobis, qui);
            Map<String, Mobi> exemple = new HashMap<>();
            for (Mobi m : mobis) exemple.putIfAbsent(m.cle, m);
            if (!types.keySet().equals(cochesMurs.keySet())) {
                cochesMurs.clear();
                List<CheckBox> l = new ArrayList<>();
                for (Map.Entry<String, String> e : types.entrySet()) {
                    CheckBox c = new CheckBox(e.getValue());
                    c.setWrapText(true);
                    c.setGraphic(vignette(exemple.get(e.getKey())));
                    c.setSelected(!decoches.contains(e.getKey()));
                    String cle = e.getKey();
                    c.selectedProperty().addListener((o, a, b) -> {
                        if (b) decoches.remove(cle); else decoches.add(cle);
                        if (majRef[0] != null) majRef[0].run();
                    });
                    cochesMurs.put(cle, c);
                    l.add(c);
                }
                listeMurs.getChildren().setAll(l);
            } else for (Map.Entry<String, String> e : types.entrySet()) cochesMurs.get(e.getKey()).setText(e.getValue());
            sansMurs.setVisible(types.isEmpty());
            defMurs.setVisible(!types.isEmpty());
            Set<String> mursChoisis = new HashSet<>();
            for (Map.Entry<String, CheckBox> e : cochesMurs.entrySet()) if (e.getValue().isSelected()) mursChoisis.add(e.getKey());

            // zone des sols
            boolean z = zoneValide();
            int[] zz = zone;
            zoneTexte.setText(z ? "(" + zz[0] + "," + zz[1] + ") → (" + zz[2] + "," + zz[3] + ")" : "");
            choisir.setText(attente ? "Clique dans le jeu…" : z ? "Autre zone" : "Choisir la zone");
            corpsSols.setDisable(!actSols.isSelected());
            corpsMurs.setDisable(!actMurs.isSelected());

            Plan p = null;
            if (moi == null && mode != BC) {
                bilan.setText("Ton pseudo n'est pas encore connu : attends quelques secondes, puis rouvre la fenêtre.");
            } else if (qui.isEmpty()) {
                bilan.setText(pers.isEmpty() ? "Rien à remplacer : personne d'autre n'a de mobis ici." : "Coche au moins une personne.");
            } else {
                p = plan(mobis, moi, qui, mode, actSols.isSelected(), actMurs.isSelected(), mursChoisis);
                bilan.setText(bilanAvant(p, mode, actSols.isSelected() && !z, actSols.isSelected() || actMurs.isSelected()));
            }
            courant[0] = p;
            remplirApercu(p, mode, apercu, fixe);
            defApercu.setVisible(!apercu.getChildren().isEmpty());

            // lignes des sections
            if (!actSols.isSelected()) lSols.setText("Les mobis de sol ne sont pas remplacés.");
            else if (!z) lSols.setText("Choisis la zone où ces personnes ont posé leurs mobis.");
            else if (p == null) lSols.setText("");
            else if (p.sourcesSols == 0) lSols.setText("Aucun mobi " + deQui + " dans la zone.");
            else if (p.pairesSols == 0) lSols.setText(Ui.accorder(p.sourcesSols + " mobi(s) " + deQui
                    + " dans la zone, aucun du même type que " + leurs + "."));
            else lSols.setText(Ui.accorder(p.sourcesSols + " mobi(s) " + deQui + " dans la zone : " + p.pairesSols + " "
                    + (p.pairesSols == 1 ? "remplacera" : "remplaceront") + " " + leurs
                    + (p.enTropSols > 0 ? ", " + p.enTropSols + " sans équivalent" : "") + "."));
            if (!actMurs.isSelected()) lMurs.setText("Les muraux ne sont pas remplacés.");
            else if (p == null || types.isEmpty()) lMurs.setText("");
            else if (mursChoisis.isEmpty()) lMurs.setText("Coche au moins un type de mural.");
            else if (p.pairesMurs == 0) lMurs.setText("Aucun mural de " + leurs + " n'est d'un type coché.");
            else lMurs.setText(Ui.accorder(p.pairesMurs + " mural(aux) à remplacer"
                    + (p.enTropMurs > 0 ? ", " + p.enTropMurs + " sans équivalent" : "") + "."));
            lMurs.setVisible(!lMurs.getText().isEmpty());
            lMurs.setManaged(lMurs.isVisible());

            remplacer.setDisable(p == null || p.paires.isEmpty());
            montrerResultat(dernier, tete, lignes, blocRes, fixe);
            f.ajuster();
        };
        majRef[0] = maj;
        majOuverte = maj;
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) return;
            PREFS.put(CLE_SOURCE, b == rBc ? CIBLE_BC : b == rPers ? CIBLE_MIENS : CIBLE_DEUX);
            maj.run();
        });
        actSols.selectedProperty().addListener((o, a, b) -> { PREFS.putBoolean(CLE_SOLS, b); maj.run(); });
        actMurs.selectedProperty().addListener((o, a, b) -> { PREFS.putBoolean(CLE_MURS, b); maj.run(); });

        VBox config = new VBox(Ui.ENTRE_BLOCS / 2,
                Ui.bloc("Personnes", sansPers, defPers,
                        Ui.aide("Les mobis des personnes cochées remplacent les mobis du même type de l'appart.")),
                Ui.bloc("Remplacent", new VBox(6, rBc, rPers, rDeux)),
                section("Sols", actSols, corpsSols,
                        Ui.aide("Les mobis de sol de ces personnes dans la zone prennent la case, la hauteur, la rotation "
                                + "et l'état des mobis du même type hors de la zone.")),
                section("Muraux", actMurs, corpsMurs,
                        Ui.aide("Pas de zone : les muraux cochés, où qu'ils soient, prennent la position exacte "
                                + "des muraux du même type.")));
        config.setFillWidth(true);
        ScrollPane defConfig = defilement(config, 290);
        VBox blocApercu = Ui.bloc("Aperçu", bilan, defApercu,
                Ui.aide("Survole ou clique une ligne pour voir ces mobis dans le jeu."));
        // la hauteur suit le contenu
        config.heightProperty().addListener((o, a, b) -> f.ajuster());
        lignes.heightProperty().addListener((o, a, b) -> f.ajuster());
        apercu.heightProperty().addListener((o, a, b) -> f.ajuster());
        maj.run();

        List<Control> pendant = new ArrayList<>(List.of(fermer, remplacer, rBc, rPers, rDeux, actSols, actMurs, choisir, defPers, defMurs));
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
            // sans droit de deplacer, les mobis de la personne ne viendraient pas : rien n'est ramasse
            Droits d = Salle.gp() == null ? null : Salle.gp().getPermissions();
            if (d != null && d.niveau() >= 0 && !d.peutDeplacer()) {
                CalqueActions.refus("Tu n'as pas les droits pour déplacer les mobis dans cet appart : rien n'a été ramassé.");
                return;
            }
            enCours = true;
            arret = false;
            int mode = rBc.isSelected() ? BC : rPers.isSelected() ? MIENS : DEUX;
            dernier = null;
            if (!fixe.isEmpty()) { fixe.clear(); MiseEnValeur.survol(null); }
            montrerResultat(null, tete, lignes, blocRes, fixe);
            for (Control c : pendant) c.setDisable(true);
            // la barre commune de la fenetre porte la progression et le bouton Arreter
            Avancement.Suivi av = Avancement.debut("Remplacement par la zone", () -> arret = true).part(0);
            avCourant = av;
            f.suivre(av);
            barre.setProgress(0);
            f.ajuster();
            Salle.tache("remplacer-par-zone", () -> {
                Resultat r = null;
                try { r = travailler(p, f, barre, mode); }
                finally {
                    enCours = false;
                    avCourant = null;
                    av.texte(null);
                    if (r == null) av.fin(Avancement.Issue.ECHEC);
                    else av.fin(r.remplaces, r.refuses + r.pasArrives, arret);
                    Resultat fin = r;
                    Platform.runLater(() -> {
                        dernier = fin;
                        for (Control c : pendant) c.setDisable(false);
                        arreter.setVisible(false);
                        barre.setVisible(false);
                        f.dire("");
                        maj.run();
                    });
                }
            });
        });

        f.contenu(defConfig, blocApercu, blocRes, barre);
        f.boutons(fermer, arreter, remplacer);
        f.montrer();
    }

    /** Le bilan avant de lancer : ce qui sera remplace, les manques, ou vont les remplaces. */
    private static String bilanAvant(Plan p, int mode, boolean zoneManque, boolean uneSection) {
        if (!uneSection) return "Coche les sols, les muraux, ou les deux.";
        StringBuilder s = new StringBuilder();
        if (p.paires.isEmpty()) {
            s.append(p.sources == 0 ? "Rien à remplacer pour l'instant." : "Rien à remplacer : aucun mobi du même type.");
            if (zoneManque) s.append(" Choisis la zone des sols.");
            return s.toString();
        }
        List<String> quoi = new ArrayList<>();
        if (p.pairesSols > 0) quoi.add(Ui.accorder(p.pairesSols + " mobi(s) de sol"));
        if (p.pairesMurs > 0) quoi.add(Ui.accorder(p.pairesMurs + " mural(aux)"));
        s.append("À remplacer : ").append(String.join(" et ", quoi)).append('.');
        if (p.sansEquivalent > 0)
            s.append(p.sansEquivalent == 1 ? " 1 mobi sans équivalent reste en place."
                    : " " + p.sansEquivalent + " mobis sans équivalent restent en place.");
        if (zoneManque) s.append("\nSols : choisis la zone pour les ajouter.");
        s.append(Ui.accorder(mode == BC ? "\nLes " + p.paires.size() + " remplacé(s) retournent au Builders Club."
                : mode == MIENS ? "\nLes " + p.paires.size() + " remplacé(s) retournent dans ton inventaire."
                : "\nLes " + p.paires.size() + " remplacé(s) retournent au Builders Club ou dans ton inventaire, selon leur origine."));
        return s.toString();
    }

    // ================================================================ apercu

    /** L'icone d'un mobi en petit (place gardee si elle manque, pour aligner les lignes). */
    private static Node vignette(Mobi m) {
        javafx.scene.image.ImageView v = new javafx.scene.image.ImageView(m == null ? null : m.icone());
        v.setFitWidth(PrixVignettes.TAILLE);
        v.setFitHeight(PrixVignettes.TAILLE);
        v.setPreserveRatio(true);
        javafx.scene.layout.StackPane cadre = new javafx.scene.layout.StackPane(v);
        cadre.setMinSize(PrixVignettes.TAILLE + 4, PrixVignettes.TAILLE + 4);
        cadre.setMaxSize(PrixVignettes.TAILLE + 4, PrixVignettes.TAILLE + 4);
        return cadre;
    }

    /** « 2 du Builders Club, 1 à toi » : d'ou viennent ces mobis de l'appart. */
    private static String origines(List<Mobi> l, String moi) {
        int bc = 0, miens = 0, autres = 0;
        for (Mobi m : l) {
            if (m.bc) bc++;
            else if (moi != null && m.proprio.equalsIgnoreCase(moi)) miens++;
            else autres++;
        }
        List<String> r = new ArrayList<>();
        if (bc > 0) r.add(bc + " du Builders Club");
        if (miens > 0) r.add(miens + " des tiens");
        if (autres > 0) r.add(Ui.accorder(autres + " autre(s)"));
        return String.join(", ", r);
    }

    /** Un petit badge colore : la destination ou l'origine. */
    private static Label badge(String texte, String fond) {
        Label b = new Label(texte);
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setStyle("-fx-background-color: " + fond + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 10.5px; -fx-padding: 1 6 1 6; -fx-background-radius: 8;");
        return b;
    }

    /** Une rangee : icone, nom ×n en gras, detail discret dessous, badges a droite ; survol et clic montrent les mobis. */
    private static HBox rangee(Mobi m, int n, String detail, List<String> jetons, List<String> fixe, Label... badges) {
        Label nom = new Label(Ui.majuscule(m.nomComplet()) + (n > 1 ? "  ×" + n : ""));
        nom.setStyle("-fx-font-weight: bold;");
        nom.setWrapText(true);
        nom.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox textes = new VBox(0, nom);
        if (detail != null && !detail.isEmpty()) {
            Label d = Ui.discret(Ui.majuscule(detail));
            d.setWrapText(true);
            d.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            textes.getChildren().add(d);
        }
        HBox.setHgrow(textes, javafx.scene.layout.Priority.ALWAYS);
        textes.setMaxWidth(Double.MAX_VALUE);
        textes.setMinWidth(0);
        HBox h = new HBox(8, vignette(m), textes);
        h.getChildren().addAll(badges);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        h.setStyle("-fx-padding: 2 6 2 2; -fx-background-radius: 6;");
        if (jetons.isEmpty()) return h;
        h.setCursor(Cursor.HAND);
        h.setOnMouseEntered(e -> {
            h.setStyle("-fx-padding: 2 6 2 2; -fx-background-radius: 6; -fx-background-color: rgba(128,128,128,0.16);");
            MiseEnValeur.survol(jetons);
        });
        h.setOnMouseExited(e -> {
            h.setStyle("-fx-padding: 2 6 2 2; -fx-background-radius: 6;");
            MiseEnValeur.survol(fixe.isEmpty() ? null : fixe);
        });
        h.setOnMouseClicked(e -> {
            boolean meme = fixe.equals(jetons);
            fixe.clear();
            if (!meme) fixe.addAll(jetons);
            MiseEnValeur.survol(fixe.isEmpty() ? null : fixe);
        });
        return h;
    }

    /** Titre d'un groupe de l'apercu, avec sa pastille de couleur et son nombre. */
    private static Node titreGroupe(String titre, int n, String couleur, String aide) {
        javafx.scene.shape.Circle point = new javafx.scene.shape.Circle(4, javafx.scene.paint.Color.web(couleur));
        Label t = new Label(titre + " (" + n + ")");
        t.setStyle("-fx-font-weight: bold;");
        Label a = Ui.discret(aide);
        a.setWrapText(true);
        a.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox h = new HBox(6, point, t);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox v = new VBox(0, h, a);
        v.setStyle("-fx-padding: 6 0 2 0;");
        return v;
    }

    private static final String VERT = "#3C9A4E", GRIS = "#8A857A", ORANGE = "#E8862A", BLEU = "#4A7FC1";

    /**
     * L'apercu en images : ce qui sera remplace (par type et par personne), ce
     * qui reste en place faute d'equivalent, et les mobis des personnes qui ne
     * serviront pas.
     */
    private static void remplirApercu(Plan p, int mode, VBox apercu, List<String> fixe) {
        List<Node> n = new ArrayList<>();
        if (p != null) {
            String moi = moi();
            // 1. remplaces : par type et par personne
            LinkedHashMap<String, List<Paire>> grp = new LinkedHashMap<>();
            for (Paire x : p.paires) grp.computeIfAbsent(x.source.cle + "\u0000" + x.source.proprio.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(x);
            if (!grp.isEmpty()) {
                n.add(titreGroupe("Seront remplacés", p.paires.size(), VERT,
                        "Le mobi de la personne prend la place exacte du mobi de l'appart."));
                for (List<Paire> l : trier(grp.values(), x -> x.source)) {
                    List<Mobi> cibles = new ArrayList<>();
                    List<String> j = new ArrayList<>();
                    int bc = 0;
                    for (Paire x : l) {
                        cibles.add(x.mien);
                        j.add(x.mien.jeton());
                        j.add(x.source.jeton());
                        if (x.mien.bc) bc++;
                    }
                    Mobi s = l.get(0).source;
                    List<Label> b = new ArrayList<>();
                    if (bc > 0) b.add(badge(bc == l.size() ? "→ BC" : bc + " → BC", ORANGE));
                    if (bc < l.size()) b.add(badge(bc == 0 ? "→ Inventaire" : (l.size() - bc) + " → Inv.", BLEU));
                    n.add(rangee(s, l.size(), "De " + s.proprio + " · remplace " + origines(cibles, moi),
                            j, fixe, b.toArray(new Label[0])));
                }
            }
            // 2. restent en place : pas d'equivalent chez les personnes
            LinkedHashMap<String, List<Mobi>> reste = new LinkedHashMap<>();
            for (Mobi m : p.sansSource) reste.computeIfAbsent(m.cle, k -> new ArrayList<>()).add(m);
            if (!reste.isEmpty()) {
                n.add(titreGroupe("Restent en place", p.sansSource.size(), GRIS,
                        "Ces personnes n'en ont pas assez du même type : ils ne bougent pas."));
                for (List<Mobi> l : trier(reste.values(), x -> x)) {
                    List<String> j = new ArrayList<>();
                    for (Mobi m : l) j.add(m.jeton());
                    n.add(rangee(l.get(0), l.size(), origines(l, moi), j, fixe));
                }
            }
            // 3. mobis des personnes qui ne serviront pas
            LinkedHashMap<String, List<Mobi>> trop = new LinkedHashMap<>();
            for (Mobi m : p.inutilises) trop.computeIfAbsent(m.cle + "\u0000" + m.proprio.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
            if (!trop.isEmpty()) {
                String leurs = mode == BC ? "du Builders Club" : mode == MIENS ? "à toi" : "à toi ni du Builders Club";
                n.add(titreGroupe("Pas utilisés", p.inutilises.size(), GRIS,
                        "Aucun mobi du même type " + leurs + " à remplacer : ils restent où ils sont."));
                for (List<Mobi> l : trier(trop.values(), x -> x)) {
                    List<String> j = new ArrayList<>();
                    for (Mobi m : l) j.add(m.jeton());
                    n.add(rangee(l.get(0), l.size(), "De " + l.get(0).proprio, j, fixe));
                }
            }
        }
        apercu.getChildren().setAll(n);
    }

    /** Les groupes par nom de mobi (ordre francais), le plus nombreux d'abord a nom egal. */
    private static <T> List<List<T>> trier(Collection<List<T>> groupes, java.util.function.Function<T, Mobi> mobi) {
        java.text.Collator c = java.text.Collator.getInstance(Locale.FRANCE);
        c.setStrength(java.text.Collator.SECONDARY);
        List<List<T>> l = new ArrayList<>(groupes);
        l.sort((a, b) -> {
            int r = c.compare(mobi.apply(a.get(0)).nomComplet(), mobi.apply(b.get(0)).nomComplet());
            return r != 0 ? r : Integer.compare(b.size(), a.size());
        });
        return l;
    }

    // ================================================================ bilan apres

    /** Une ligne du bilan : un mobi remplace ou pas, avec sa raison. */
    private static final class Ligne {
        final boolean ok;
        final String nom, ou, raison;
        final Mobi mobi;
        final List<String> jetons = new ArrayList<>();
        Ligne(boolean ok, Mobi m, String raison, String jeton) {
            this.ok = ok; this.nom = m.nom(); this.ou = m.ou(); this.raison = raison; this.mobi = m;
            if (jeton != null) jetons.add(jeton);
        }
    }

    /** Le resultat d'un remplacement, montre dans la fenetre jusqu'au suivant. */
    private static final class Resultat {
        final List<Ligne> lignes = new ArrayList<>();
        int remplaces, sansEquivalent, refuses, remis, pasArrives;
        String note;

        String tete() {
            List<String> l = new ArrayList<>();
            l.add(remplaces + " remplacé(s)");
            if (sansEquivalent > 0) l.add(sansEquivalent + " sans équivalent");
            if (refuses > 0) l.add(refuses + " refusé(s)");
            if (pasArrives > 0) l.add(pasArrives + " pas arrivé(s)");
            if (remis > 0) l.add(remis + (remis == 1 ? " des tiens remis à sa place" : " des tiens remis à leur place"));
            return Ui.accorder(String.join(", ", l) + "." + (note == null ? "" : " " + note));
        }
    }

    private static void montrerResultat(Resultat r, Label tete, VBox lignes, VBox bloc, List<String> fixe) {
        bloc.setVisible(r != null);
        if (r == null) { lignes.getChildren().clear(); return; }
        if (bloc.getProperties().get("atelier.remplacer.vu") == r) return;
        bloc.getProperties().put("atelier.remplacer.vu", r);
        tete.setText(r.tete());
        List<Node> n = new ArrayList<>();
        for (boolean ok : new boolean[]{true, false}) {
            List<Ligne> l = new ArrayList<>();
            for (Ligne x : r.lignes) if (x.ok == ok) l.add(x);
            if (l.isEmpty()) continue;
            n.add(titreGroupe(ok ? "Remplacés" : "Pas remplacés", l.size(), ok ? VERT : ORANGE,
                    ok ? "Ils sont à la place des mobis de l'appart." : "Avec la raison pour chacun."));
            if (r.lignes.size() > 30) {
                // trop long : regroupe par nom et raison, avec un compteur
                LinkedHashMap<String, List<Ligne>> grp = new LinkedHashMap<>();
                for (Ligne x : l) grp.computeIfAbsent(x.nom + "\u0000" + (x.raison == null ? "" : x.raison), k -> new ArrayList<>()).add(x);
                for (List<Ligne> gl : grp.values()) {
                    Ligne x = gl.get(0);
                    List<String> j = new ArrayList<>();
                    for (Ligne y : gl) j.addAll(y.jetons);
                    n.add(rangee(x.mobi, gl.size(), (gl.size() > 1 ? "" : x.ou + (x.raison == null ? "" : " · "))
                            + (x.raison == null ? "" : x.raison), j, fixe));
                }
            } else for (Ligne x : l)
                n.add(rangee(x.mobi, 1, x.ou + (x.raison == null ? "" : " · " + x.raison), x.jetons, fixe));
        }
        lignes.getChildren().setAll(n);
    }

    // ================================================================ travail

    private static boolean present(Mobi m) {
        return m.mural ? Salle.mur(m.id) != null : PoseTapis.encoreLa(m.id);
    }

    private static void avance(CalqueFenetre f, ProgressBar barre, double v, String texte) {
        Avancement.Suivi a = avCourant;
        if (a != null) {
            a.part(Math.max(0, Math.min(1, v)));
            if (texte != null) a.texte(Avancement.nomDe(texte));
        }
        // l'avancement n'est que dans le bandeau du haut : rien en double dans la fenetre
    }

    /** Ramasse les cibles appariees, puis met les sources a leur place. Hors fil JavaFX. */
    private static Resultat travailler(Plan p, CalqueFenetre f, ProgressBar barre, int mode) {
        Resultat res = new Resultat();
        int salle = Groupes.salleCourante();
        java.util.function.BooleanSupplier stop = () -> arret || Groupes.salleCourante() != salle;
        List<Paire> paires = p.paires;
        List<Integer> ids = new ArrayList<>();
        for (Paire x : paires) ids.add(x.mien.id);
        Journal.debug("remplacer par la zone : " + paires.size() + " paire(s), " + p.sansEquivalent
                + " sans équivalent" + (p.manques.isEmpty() ? "" : " " + p.manques) + ", " + p.enTrop + " source(s) en trop.");
        for (Mobi m : p.sansSource)
            res.lignes.add(new Ligne(false, m, "pas d'équivalent chez ces personnes", m.jeton()));
        res.sansEquivalent = p.sansSource.size();

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
            if (Groupes.salleCourante() != salle) {
                Journal.erreur("Tu as quitté l'appart pendant le remplacement.");
                res.note = "Tu as quitté l'appart pendant le remplacement.";
                return res;
            }
            List<Paire> libres = new ArrayList<>();
            int pasRamasses = 0;
            List<String> raisons = new ArrayList<>();
            for (Paire x : paires) {
                if (present(x.mien)) {
                    pasRamasses++;
                    raisons.add(decrire(x.mien) + " : refusé au ramassage, laissé en place");
                    res.lignes.add(new Ligne(false, x.mien, arret ? "pas traité (arrêté)" : "refusé au ramassage (droits ?)", x.mien.jeton()));
                }
                else if (present(x.source)) libres.add(x);
                else {
                    raisons.add(decrire(x.mien) + " : ramassé, mais sa source " + x.source.id + " a disparu de l'appart");
                    res.lignes.add(new Ligne(false, x.mien, "le mobi de la personne a disparu de l'appart ; " + ouVa(x.mien), null));
                }
            }
            res.refuses = pasRamasses;
            if (pasRamasses > 0 && !arret) Salle.signalerRefus("ramassage", pasRamasses);
            if (libres.isEmpty()) {
                CalqueActions.resultat(Ui.accorder("Rien n'a été remplacé" + (pasRamasses > 0
                        ? " : " + pasRamasses + " mobi(s) à remplacer refusé(s) par le jeu au ramassage (droits ?)" : "") + "."));
                for (int i = 0; i < Math.min(40, raisons.size()); i++) Journal.debug("remplacer : " + raisons.get(i) + ".");
                return res;
            }

            // 2. les sources a la place des miens : sols par le tapis de dalles, muraux ensuite (etape 3 du tapis)
            List<PoseTapis.Piece> pieces = new ArrayList<>();
            Map<Integer, PoseTapis.Piece> pieceDe = new HashMap<>();
            List<Paire> murs = new ArrayList<>();
            for (Paire x : libres) {
                if (x.source.mural) { murs.add(x); continue; }
                HFloorItem it = Salle.sol(x.source.id);
                if (it == null) continue;
                int[] e = PoseTapis.emprise(x.source.classe, x.mien.rot);
                // pas reprenable : ramasser le mobi d'une autre personne l'enverrait dans SON inventaire
                PoseTapis.Piece pc = PoseTapis.Piece.deplace(x.source.id, x.source.nom(), x.mien.x, x.mien.y,
                        Generateur.arrondi(Math.max(0, x.mien.z)), x.mien.rot, Salle.rotation(it), e[0], e[1], false);
                pieces.add(pc);
                pieceDe.put(x.source.id, pc);
            }
            PoseTapis.Suivi suivi = (fait, total, texte) ->
                    avance(f, barre, 0.3 + 0.65 * (total <= 0 ? 0 : (double) fait / total), texte);
            Runnable etape3 = murs.isEmpty() ? null : () -> deplacerMuraux(murs, stop, suivi);
            if (!pieces.isEmpty()) {
                PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(Generateur.Source.INVENTAIRE_PUIS_BC, stop,
                        m -> { Avancement.Suivi a = avCourant; if (a != null) a.texte(Avancement.nomDe(m)); }, suivi, false);
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
            List<Mobi> manques = new ArrayList<>();
            Map<Integer, String> pourquoiPas = new HashMap<>();   // id du mien -> raison (sols pas arrives)
            for (Paire x : libres) {
                if (x.source.mural) {
                    HWallItem w = Salle.mur(x.source.id);
                    if (w != null && SelectionMur.normaliser(w.getLocation()).equals(SelectionMur.normaliser(x.mien.position))) {
                        ok++;
                        res.lignes.add(new Ligne(true, x.mien, null, "m" + x.source.id));
                    } else {
                        ailleurs++;
                        raisons.add(decrire(x.mien) + " : sa source " + x.source.id + " n'est pas arrivée ("
                                + (w == null ? "disparue" : "en " + w.getLocation()) + ", attendu " + x.mien.position + ")");
                        res.lignes.add(new Ligne(false, x.mien, (arret ? "arrêté avant" : "le mural de la personne n'est pas arrivé")
                                + " ; " + ouVa(x.mien), w == null ? null : "m" + x.source.id));
                    }
                    continue;
                }
                HFloorItem it = Salle.sol(x.source.id);
                if (it == null || it.getTile().getX() != x.mien.x || it.getTile().getY() != x.mien.y) {
                    ailleurs++;
                    manques.add(x.mien);
                    PoseTapis.Piece pc = pieceDe.get(x.source.id);
                    pourquoiPas.put(x.mien.id, arret ? "arrêté avant"
                            : it == null ? "le mobi de la personne a disparu"
                            : pc != null && (pc.refuse || pc.raison != null) ? "case refusée par le jeu (droits ?)"
                            : "le mobi de la personne n'est pas arrivé");
                    raisons.add(decrire(x.mien) + " : sa source " + x.source.id + " n'est pas arrivée ("
                            + (it == null ? "disparue" : "en " + it.getTile().getX() + "," + it.getTile().getY()) + ")");
                    continue;
                }
                if (Math.abs(it.getTile().getZ() - x.mien.z) > PoseTapis.TOLERANCE) {
                    hauteur++;
                    raisons.add(decrire(x.mien) + " : remplacé, hauteur " + it.getTile().getZ() + " au lieu de " + x.mien.z);
                    res.lignes.add(new Ligne(true, x.mien, "hauteur à reprendre", "s" + x.source.id));
                } else res.lignes.add(new Ligne(true, x.mien, null, "s" + x.source.id));
                ok++;
            }
            // 5. un mobi remplace dont la source n'est pas arrivee est repose a sa place (inventaire ou BC)
            int reposes = 0;
            Set<Integer> traites = new HashSet<>();
            if (!manques.isEmpty() && Groupes.salleCourante() == salle) {
                avance(f, barre, 0.98, "Remise en place : " + manques.size());
                List<PoseTapis.Piece> remises = new ArrayList<>();
                Map<Integer, Mobi> parId = new HashMap<>();
                for (Mobi m : manques) {
                    int[] e = PoseTapis.emprise(m.classe, m.rot);
                    remises.add(PoseTapis.Piece.nouveau(m.classe, m.etat, m.nom(), m.x, m.y,
                            Generateur.arrondi(Math.max(0, m.z)), m.rot, e[0], e[1], m.id));
                    parId.put(m.id, m);
                }
                // chacun d'ou il venait : les BC d'abord du Builders Club, les miens d'abord de l'inventaire
                int remisOk = 0;
                for (boolean deBc : new boolean[]{true, false}) {
                    List<PoseTapis.Piece> lot = new ArrayList<>();
                    for (PoseTapis.Piece pc : remises) { Mobi m = parId.get(pc.cle); if (m != null && m.bc == deBc) lot.add(pc); }
                    if (lot.isEmpty()) continue;
                    PoseTapis.JeuSalle jeu = new PoseTapis.JeuSalle(
                            deBc ? Generateur.Source.BC_PUIS_INVENTAIRE : Generateur.Source.INVENTAIRE_PUIS_BC, () -> false,
                            m -> { Avancement.Suivi a = avCourant; if (a != null) a.texte(Avancement.nomDe(m)); }, (a, bb, t) -> { }, false);
                    remisOk += PoseTapis.executer(lot, jeu, (a, bb, t) -> { }, null).reussis();
                }
                for (PoseTapis.Piece pc : remises) {
                    Mobi m = parId.get(pc.cle);
                    String pq = m == null ? null : pourquoiPas.get(m.id);
                    if (pc.obtenu >= 0) {
                        reposes++;
                        raisons.add("source absente : mobi " + pc.cle + " reposé à sa place");
                        if (m != null) res.lignes.add(new Ligne(false, m, pq + " ; le tien est remis à sa place", "s" + pc.obtenu));
                    } else {
                        raisons.add("source absente : mobi " + pc.cle + " PAS reposé" + (pc.raison == null ? "" : " (" + pc.raison + ")"));
                        if (m != null) res.lignes.add(new Ligne(false, m, pq + " ; le tien n'a pas pu être remis à sa place", null));
                    }
                    if (m != null) traites.add(m.id);
                }
                Journal.debug("remplacer par la zone : " + remisOk + "/" + remises.size() + " mobi(s) remis à leur place.");
            }
            for (Mobi m : manques)
                if (!traites.contains(m.id))
                    res.lignes.add(new Ligne(false, m, pourquoiPas.get(m.id) + " ; le tien n'a pas été remis à sa place", null));
            res.remplaces = ok;
            res.remis = reposes;
            res.pasArrives = ailleurs;

            avance(f, barre, 1, null);
            // une ligne de debug par mobi pas (ou mal) remplace, avec sa raison (au plus 40)
            for (int i = 0; i < Math.min(40, raisons.size()); i++) Journal.debug("remplacer : " + raisons.get(i) + ".");
            if (raisons.size() > 40) Journal.debug("remplacer : … et " + (raisons.size() - 40) + " autre(s).");
            Journal.debug("remplacer par la zone : " + ok + " à leur case, " + ailleurs + " pas arrivé(s), "
                    + hauteur + " hauteur(s) fausse(s), " + etatsFaux + " état(s) faux, " + pasRamasses + " pas ramassé(s).");

            String fin = ok + " mobi(s) remplacé(s)";
            if (p.sansEquivalent > 0) fin += ", " + p.sansEquivalent + " sans équivalent chez ces personnes";
            if (ailleurs > 0) fin += ", " + ailleurs + " mobi(s) de ces personnes pas déplacé(s)"
                    + (reposes > 0 ? " : " + reposes + " des tiens remis à leur place" : "");
            if (hauteur > 0) fin += ", " + hauteur + " hauteur(s) à reprendre";
            if (etatsFaux > 0) fin += ", " + etatsFaux + " état(s) différent(s)";
            if (pasRamasses > 0) fin += ", " + pasRamasses + " mobi(s) à remplacer refusé(s) au ramassage";
            if (arret) fin += " (arrêté)";
            if (arret) res.note = "Arrêté.";
            if (ok > 0) fin += ". " + WindowsClavier.texte("Cmd+Z pour annuler");
            fin = Ui.accorder(fin + ".");
            if (ok == 0) Journal.erreur(fin); else CalqueActions.resultat(fin);
            return res;
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

    /** Une ligne de debug a l'ouverture : comment les muraux de l'appart sont classes (BC, a moi, autres). */
    private static void debugMurs(List<Mobi> mobis, String moi) {
        int bc = 0, miens = 0, autres = 0;
        List<String> ex = new ArrayList<>();
        for (Mobi m : mobis) {
            if (!m.mural) continue;
            if (m.bc) bc++;
            else if (moi != null && m.proprio.equalsIgnoreCase(moi)) miens++;
            else autres++;
            if (ex.size() < 6) ex.add(m.id + "/" + (m.proprio.isEmpty() ? "?" : m.proprio) + (m.bc ? "/BC" : ""));
        }
        Journal.debug("remplacer : muraux de l'appart, " + bc + " BC, " + miens + " à moi (" + moi + "), "
                + autres + " à d'autres ; exemples id/poseur : " + ex + ".");
    }

    /** Ou va le mobi remplace, selon son origine. */
    private static String ouVa(Mobi m) {
        return m.bc ? "le tien est retourné au Builders Club" : "le tien est dans ton inventaire";
    }

    /** « Chaise 2147418113 (BC) en 4,7 » ou « Affiche 123 au mur », pour le debug. */
    private static String decrire(Mobi m) {
        return m.nom() + " " + m.id + (m.bc ? " (BC)" : "") + (m.mural ? " " + m.position : " en " + m.x + "," + m.y);
    }

    private static boolean muralArrive(Paire x) {
        HWallItem w = Salle.mur(x.source.id);
        return w != null && SelectionMur.normaliser(w.getLocation()).equals(SelectionMur.normaliser(x.mien.position));
    }
}

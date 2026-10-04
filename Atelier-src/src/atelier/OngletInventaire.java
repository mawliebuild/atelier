package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;

/**
 * Filtre la fenetre d'inventaire du jeu par categorie.
 *
 * L'Atelier ne peut pas modifier la fenetre du client Flash, mais il voit les
 * paquets qui la remplissent : on intercepte la liste d'inventaire, on ecarte
 * les mobis hors categorie, et on renvoie au client une liste reconstruite.
 * La grille du jeu n'affiche alors que la categorie choisie, et sa pagination
 * se reduit d'autant.
 *
 * Cache : le dernier inventaire COMPLET recu du serveur est garde en memoire.
 * Changer de categorie renvoie aussitot au jeu la liste filtree depuis ce
 * cache, sans attendre le serveur. Puis on redemande quand meme l'inventaire
 * en arriere-plan : s'il a change (nouveaux mobis, mobis poses ou vendus), sa
 * reponse remplace le cache et la liste est renvoyee a jour. Le cache sert a
 * aller vite, jamais a montrer un inventaire perime.
 *
 * Vitesse (25 000 mobis) : le cache garde les fragments BRUTS du serveur et
 * chaque mobi en octets bruts (InventaireCache). « Tout afficher » renvoie les
 * fragments du serveur tels quels ; une liste filtree se construit en recollant
 * des octets, puis reste en memoire pour ce filtre. constructPackets de la connexion
 * (plusieurs secondes, voire minutes, sur un gros inventaire) n'est plus utilise.
 * Les mobis poses / ramasses en jeu sont reportes dans le cache au passage.
 */
public class OngletInventaire {

    private ComboBox<String> famille, annee, type;
    private Label etat, apercu;
    private boolean installe = false;

    /**
     * Toutes les lignes de furnidata vues passer, AVANT filtrage.
     *
     * Indispensable : le filtre appauvrit aussi l'inventaire que voit le moteur de l'Atelier,
     * donc y puiser la liste des annees la vidait, et la selection sautait —
     * « Noël 2024 » retombait sur « toutes les années » au premier rechargement.
     */
    private final java.util.Set<String> lignesVues =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private volatile String familleChoisie = Categories.TOUTES;
    private volatile String anneeChoisie   = Categories.TOUTES_ANNEES;
    private volatile String typeChoisi     = Fiche.TOUS_TYPES;
    private volatile Fiche.Bc bcChoisi     = Fiche.Bc.TOUS;
    private volatile boolean raresChoisis  = false;

    /** Le choix courant, fige d'un bloc : le fil des paquets le lit. */
    private Fiche.Filtre filtre() {
        return new Fiche.Filtre(familleChoisie, anneeChoisie, typeChoisi, bcChoisi, raresChoisis);
    }


    // ------------------------------------------------------------------ UI

    public Tab construire() {
        famille = new ComboBox<>();
        // Vignette du premier mobi de chaque famille, dans la liste et une fois
        // choisie. Les icones viennent de images.habbo.com, en tache de fond :
        // une icone manquante laisse simplement la ligne sans image.
        famille.setCellFactory(l -> new CelluleFamille());
        famille.setButtonCell(new CelluleFamille());
        famille.getItems().setAll(Categories.familles());
        famille.setValue(Categories.TOUTES);
        famille.setMaxWidth(Double.MAX_VALUE);
        famille.setOnAction(e -> {
            if (majEnCours || famille.getValue() == null) return;
            familleChoisie = famille.getValue();
            // Changer de famille remet l'annee a zero : c'est voulu, les annees
            // d'une famille n'ont pas de sens dans une autre.
            anneeChoisie = Categories.TOUTES_ANNEES;
            majAnnees(); majApercu(); rafraichirJeu();
        });

        annee = new ComboBox<>();
        annee.setMaxWidth(Double.MAX_VALUE);
        annee.setOnAction(e -> {
            if (majEnCours || annee.getValue() == null) return;
            anneeChoisie = annee.getValue();
            majApercu(); rafraichirJeu();
        });

        type = new ComboBox<>();
        type.getItems().setAll(Fiche.TYPES);
        type.setValue(Fiche.TOUS_TYPES);
        type.setMaxWidth(Double.MAX_VALUE);
        type.setOnAction(e -> {
            if (majEnCours || type.getValue() == null) return;
            typeChoisi = type.getValue();
            majApercu(); rafraichirJeu();
        });

        ToggleGroup gBc = new ToggleGroup();
        RadioButton bcTous = new RadioButton("Tous"), bcOui = new RadioButton("BC seulement"),
                    bcNon = new RadioButton("Hors BC");
        for (RadioButton r : new RadioButton[]{bcTous, bcOui, bcNon}) r.setToggleGroup(gBc);
        bcTous.setSelected(true);
        gBc.selectedToggleProperty().addListener((o, a, b) -> {
            if (majEnCours || b == null) return;
            bcChoisi = (b == bcOui) ? Fiche.Bc.BC : (b == bcNon) ? Fiche.Bc.HORS_BC : Fiche.Bc.TOUS;
            majApercu(); rafraichirJeu();
        });

        CheckBox rares = new CheckBox("Rares seulement");
        rares.selectedProperty().addListener((o, a, b) -> {
            if (majEnCours) return;
            raresChoisis = b;
            majApercu(); rafraichirJeu();
        });

        Button toutVoir = new Button("Tout afficher");
        toutVoir.setOnAction(e -> {
            majEnCours = true;
            try {
                famille.setValue(Categories.TOUTES);
                type.setValue(Fiche.TOUS_TYPES);
                bcTous.setSelected(true);
                rares.setSelected(false);
            } finally { majEnCours = false; }
            familleChoisie = Categories.TOUTES;
            anneeChoisie = Categories.TOUTES_ANNEES;
            typeChoisi = Fiche.TOUS_TYPES;
            bcChoisi = Fiche.Bc.TOUS;
            raresChoisis = false;
            majAnnees(); majApercu(); rafraichirJeu();
        });

        apercu = Ui.valeur("—");
        apercu.setWrapText(true);
        etat = Ui.etat();

        VBox v = new VBox(12,
                Ui.bloc("Collection",
                        Ui.ligne(new Label("Collection"), famille),
                        Ui.ligne(new Label("Année"), annee)),
                Ui.bloc("Mobi",
                        Ui.ligne(new Label("Type"), type),
                        Ui.ligne(bcTous, bcOui, bcNon),
                        rares),
                Ui.bloc("Résultat", apercu, toutVoir),
                etat);
        v.setFillWidth(true);
        v.setPadding(new Insets(12, 14, 14, 14));

        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        installer();
        suivreInventaire();
        // Le repertoire des mobis (catalogue, annees) se construit et se
        // complete tout seul ; quand il a fini, les annees sont recalculees.
        Repertoire.surMaj(() -> { Fiche.invalider(); oublierListesFiltrees(); Platform.runLater(() -> { majAnnees(); majApercu(); }); });
        Repertoire.demarrer();

        Tab t = new Tab("Inventaire", sp);
        t.setClosable(false);
        return t;
    }

    /**
     * Met a jour la fenetre d'inventaire du jeu apres un changement de filtre :
     * plus besoin de la fermer et de la rouvrir.
     */
    private void rafraichirJeu() {
        Journal.debug("inventaire : changement de filtre.");
        // Tout de suite : la liste filtree depuis le cache, s'il y en a un.
        // Un seul fil, dans l'ordre : deux changements rapides ne se croisent
        // plus (le jeu finit toujours sur le dernier filtre choisi).
        travail.execute(this::envoyerDepuisCache);
        // Sans cache (ou cache vieux), on redemande l'inventaire au serveur.
        verifier(false);
    }

    /**
     * Redemande l'inventaire au serveur pour tenir le cache a jour (nouveaux
     * mobis, mobis poses ou vendus). Groupe : plusieurs changements rapproches
     * ne declenchent qu'une demande, et un cache de moins de 60 s n'est pas
     * reverifie.
     *
     * La reponse a NOTRE demande est bloquee : le jeu a deja la bonne liste
     * (envoyee depuis le cache). On ne lui renvoie quelque chose que si
     * l'inventaire a vraiment change. Avant, il recevait l'inventaire complet
     * deux fois de suite, ce qui figeait le jeu sur un gros inventaire.
     */
    private void verifier(boolean forcer) {
        // Un gros inventaire (des dizaines de milliers de mobis) met plusieurs
        // secondes a arriver du serveur : on ne le redemande qu'au plus toutes
        // les 10 minutes. Le jeu renvoie de lui-meme l'inventaire quand il change.
        if (!forcer && serie != null && System.currentTimeMillis() - completLe < 10 * 60_000) return;
        derniereDemande = System.currentTimeMillis();
        if (demandeEnCours) return;
        demandeEnCours = true;
        Thread t = new Thread(() -> {
            try {
                long attendu;
                do {
                    attendu = derniereDemande;
                    Thread.sleep(400);
                } while (derniereDemande != attendu);
                Moteur gp = AtelierLauncher.moteur();
                if (gp != null) {
                    verifDemandeeLe = System.currentTimeMillis();
                    ChargementAuto.inventaireDemande();
                    gp.demanderInventaire();
                }
            } catch (Throwable ignored) {
            } finally { demandeEnCours = false; }
        }, "atelier-inventaire-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** Heure de notre derniere demande d'inventaire ; 0 quand sa reponse est arrivee. */
    private volatile long verifDemandeeLe = 0;
    private volatile long completLe = 0;

    private volatile long derniereDemande = 0;
    private volatile boolean demandeEnCours = false;
    private volatile long derniereLigneNeuve = 0;
    private volatile boolean majAnneesPrevue = false;

    /** Regroupe les recalculs : au plus un, une fois les fragments passes. */
    private void demanderMajAnnees() {
        derniereLigneNeuve = System.currentTimeMillis();
        if (majAnneesPrevue) return;
        majAnneesPrevue = true;
        Thread t = new Thread(() -> {
            try {
                long vu;
                do { vu = derniereLigneNeuve; Thread.sleep(700); }
                while (derniereLigneNeuve != vu);
                Platform.runLater(() -> { majAnnees(); majApercu(); });
            } catch (InterruptedException ignored) {
            } finally { majAnneesPrevue = false; }
        }, "atelier-annees");
        t.setDaemon(true);
        t.start();
    }

    /**
     * L'inventaire arrive apres la construction de l'onglet : sans ce suivi,
     * l'apercu restait fige sur « 0 mobis » et la liste des annees vide.
     */
    private void suivreInventaire() {
        Thread t = new Thread(() -> {
            int vu = -1;
            while (true) {
                try {
                    Moteur gp = AtelierLauncher.moteur();
                    int n = -1;
                    if (gp != null) {
                        try { n = gp.getInventory().getInventoryItems().size(); }
                        catch (Throwable ignored) { }
                    }
                    if (n != vu) {
                        vu = n;
                        final int taille = n;
                        // Seulement l'apercu : la liste des annees est reconstruite
                        // par demanderMajAnnees, une seule fois par rechargement.
                        Platform.runLater(() -> majApercu(taille));
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-inventaire-suivi");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Les annees reellement presentes dans l'inventaire, pour la collection
     * choisie. Elles viennent du repertoire (catalogue, nouveautes, estimation)
     * et, a defaut, de la collection.
     */
    private void majAnnees() {
        Set<String> trouvees = new TreeSet<>(Comparator.reverseOrder());
        Moteur gp = AtelierLauncher.moteur();
        if (gp != null) {
            for (HInventoryItem it : inventaireConnu(gp)) {
                Fiche f = Fiche.de(gp, it);
                if (f == null) continue;
                if (f.ligne != null) lignesVues.add(f.ligne);
                if (!Categories.TOUTES.equals(familleChoisie) && !familleChoisie.equals(f.famille())) continue;
                String a = f.annee();
                if (a != null) trouvees.add(a);
            }
        }
        List<String> l = new ArrayList<>();
        l.add(Categories.TOUTES_ANNEES);
        l.addAll(trouvees);
        // une annee choisie dans l'inventaire du jeu reste choisie, meme sans mobi
        if (!Categories.TOUTES_ANNEES.equals(anneeChoisie) && !l.contains(anneeChoisie)) l.add(anneeChoisie);

        // Conserver le choix en cours. Sans ca, chaque rechargement d'inventaire
        // — declenche par le filtre lui-meme — remettait l'annee a zero, en
        // boucle : on ne pouvait jamais rester sur « Halloween 2024 ».
        String garde = anneeChoisie;
        majEnCours = true;
        try {
            annee.getItems().setAll(l);
            annee.setValue(l.contains(garde) ? garde : Categories.TOUTES_ANNEES);
        } finally { majEnCours = false; }
        anneeChoisie = annee.getValue();
    }

    /**
     * Un choix fait dans l'inventaire du jeu modifie : « categorie=3 » (index
     * dans Categories.familles(), meme ordre que le SWF) ou « annee=2024 » /
     * « annee=tout ». Les menus de l'Atelier suivent.
     */
    private void ordreDuJeu(String ordre) {
        Journal.debug("inventaire du jeu : " + ordre);
        Platform.runLater(() -> {
            try {
                if (ordre.startsWith("categorie=")) {
                    List<String> f = Categories.familles();
                    int i = Integer.parseInt(ordre.substring(10).trim());
                    if (i < 0 || i >= f.size()) return;
                    // Le jeu envoie « Toutes les categories » en remplissant son menu :
                    // rien ne change, on ne refiltre pas. SAUF si le jeu affiche encore
                    // une liste filtree alors que plus aucun filtre n'est actif : il
                    // faut alors lui rendre l'inventaire complet. (Pas de boucle : une
                    // fois l'inventaire complet renvoye, jeuFiltre redevient faux.)
                    boolean rienAChanger = i != 0
                            || (Categories.TOUTES_ANNEES.equals(anneeChoisie) && (!jeuFiltre || filtre().actif()));
                    if (f.get(i).equals(familleChoisie) && rienAChanger) return;
                    familleChoisie = f.get(i);
                    anneeChoisie = Categories.TOUTES_ANNEES;
                    majEnCours = true;
                    try { famille.setValue(familleChoisie); } finally { majEnCours = false; }
                    majAnnees(); majApercu(); rafraichirJeu();
                } else if (ordre.startsWith("annee=")) {
                    String v = ordre.substring(6).trim();
                    String a = "tout".equals(v) ? Categories.TOUTES_ANNEES : v;
                    if (!a.equals(Categories.TOUTES_ANNEES) && !a.matches("\\d{4}")) return;
                    // rien ne change (sauf liste filtree a rendre complete, voir plus haut)
                    if (a.equals(anneeChoisie) && (!jeuFiltre || filtre().actif())) return;
                    anneeChoisie = a;
                    majEnCours = true;
                    try {
                        if (!annee.getItems().contains(a)) annee.getItems().add(a);
                        annee.setValue(a);
                    } finally { majEnCours = false; }
                    majApercu(); rafraichirJeu();
                }
            } catch (Throwable t) {
                Journal.debug("ordre de l'inventaire du jeu illisible : " + ordre + " (" + t + ")");
            }
        });
    }

    /** Vrai pendant qu'on remplit un menu : les evenements sont alors ignores. */
    private volatile boolean majEnCours = false;

    /** Combien de mobis passeraient le filtre, avant de l'activer. */
    private void majApercu() { majApercu(-1); }

    private void majApercu(int connus) {
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) { apercu.setText("L'Atelier n'est pas encore prêt"); return; }

        boolean fd = false;
        try { fd = gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady(); }
        catch (Throwable ignored) { }

        int garde = 0, total = 0, estimees = 0;
        Fiche.Filtre f = filtre();
        try {
            for (HInventoryItem it : inventaireConnu(gp)) {
                total++;
                Fiche fi = Fiche.de(gp, it);
                if (f.garde(fi)) {
                    garde++;
                    if (fi != null && fi.anneeEstimee()) estimees++;
                }
            }
        } catch (Throwable t) { apercu.setText("Inventaire indisponible"); return; }

        if (total == 0) {
            apercu.setText(lignesVues.isEmpty()
                    ? "Inventaire vide ou pas encore chargé — entre dans une salle"
                    : "Inventaire en cours de chargement…");
            return;
        }
        if (!fd) {
            apercu.setText(total + " mobis, mais la furnidata n'est pas chargée : "
                    + "les catégories ne peuvent pas encore être déterminées");
            return;
        }
        boolean anneeChoisieActive = !Categories.TOUTES_ANNEES.equals(anneeChoisie);
        apercu.setText(garde + " mobis sur " + total + " correspondent"
                + (anneeChoisieActive && estimees > 0
                    ? "   (dont " + estimees + " dont l'année est estimée)" : ""));
    }

    // -------------------------------------------------------------- filtrage

    private synchronized void installer() {
        if (installe) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 600 && !installe; i++) {
                brancher();
                if (installe) { Journal.debug("filtre d'inventaire actif."); return; }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "atelier-inventaire");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void brancher() {
        if (installe) return;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return;
        try {
            // Reconnaissance par CONTENU, pas par nom : un intercept par nom
            // echoue en silence quand le nom ne se resout plus.
            // Toujours actif, meme sans filtre : c'est ce qui tient le cache a jour.
            gp.intercept(HMessage.Direction.TOCLIENT, m -> {
                try { filtrer(gp, m); } catch (Throwable ignored) { }
            });
            // Le client Habbo modifie (Atelier-swf) envoie les choix de ses menus
            // Categorie / Annee dans un petit message texte « atelier:... ». On le
            // reconnait a son contenu, on le bloque (il ne part jamais au serveur)
            // et on applique le filtre comme depuis les menus de l'Atelier.
            gp.intercept(HMessage.Direction.TOSERVER, m -> {
                try {
                    HPacket p = m.getPacket();
                    int n = p.getBytesLength();
                    if (n < 16 || n > 120) return;
                    String t = p.readString(6);             // lecture sur place, sans copie
                    if (t == null || !t.startsWith("atelier:")) return;
                    m.setBlocked(true);
                    ordreDuJeu(t.substring(8));
                } catch (Throwable ignored) { }
            });
            // Changements de l'inventaire en cours de jeu (mobi pose, ramasse,
            // achete), pour garder le cache juste sans tout redemander. Par NOM,
            // comme le moteur de l'Atelier : si un nom ne se resout pas, on perd seulement ce
            // raccourci (le cache est alors rafraichi par la serie suivante).
            try {
                // Lecture sur place (ou copie d'un petit paquet), puis le travail
                // (recopie d'un cache de 25 000 mobis) sur le fil de lecture.
                gp.intercept(HMessage.Direction.TOCLIENT, "FurniListRemove", m -> {
                    try {
                        HPacket p = m.getPacket();
                        if (p.getBytesLength() < 10) return;
                        int placement = p.readInteger(6);
                        lecture.execute(() -> { try { mobiRetire(placement); } catch (Throwable ignored) { } });
                    } catch (Throwable ignored) { }
                });
                gp.intercept(HMessage.Direction.TOCLIENT, "FurniListAddOrUpdate", m -> {
                    try {
                        HPacket copie = new HPacket(m.getPacket());
                        lecture.execute(() -> { try { mobisAjoutes(gp, copie); } catch (Throwable ignored) { } });
                    } catch (Throwable ignored) { }
                });
                gp.intercept(HMessage.Direction.TOCLIENT, "FurniListInvalidate", m -> {
                    try { inventairePerime(); } catch (Throwable ignored) { }
                });
            } catch (Throwable t) {
                Journal.debug("inventaire : suivi des mobis posés/ramassés indisponible (" + t + ")");
            }
            installe = true;
            // L'inventaire a pu arriver avant que l'ecoute soit en place : sans
            // cette demande, le cache restait vide et les comptes faux (0 mobi).
            verifier(true);
        } catch (Throwable t) {
            dire("Filtre indisponible : " + t);
        }
    }

    /**
     * Accumule une serie de fragments d'inventaire, la garde en cache, et
     * renvoie au jeu la version filtree en UN SEUL jeu de fragments.
     *
     * Filtrer fragment par fragment ne marchait pas : renumeroter chaque
     * morceau en « fragment 1 sur 1 » faisait recevoir au client 25 inventaires
     * complets successifs, et il n'affichait que le dernier.
     *
     * Le cache garde les fragments BRUTS du serveur (pour tout reafficher sans
     * rien reconstruire) et chaque mobi en octets bruts (pour reconstruire une
     * liste filtree en recollant des octets, voir InventaireCache).
     *
     * Sans filtre, les fragments passent tels quels : on se contente de les
     * copier dans le cache.
     */
    private void filtrer(Moteur gp, HMessage m) {
        HPacket brut = m.getPacket();
        // Appele pour CHAQUE paquet recu : rejet bon marche, sans copie.
        // Une fois l'en-tete de l'inventaire connu, un seul test suffit.
        int connu = entete;
        if (connu >= 0 && brut.headerId() != connu) return;
        if (brut.getBytesLength() < 14) return;
        int total, numero;
        try { total = brut.readInteger(6); numero = brut.readInteger(10); }
        catch (Throwable t) { return; }
        if (total <= 0 || total > 500 || numero < 0 || numero >= total) return;

        if (connu >= 0) {
            // En-tete connu : ici seulement la decision de bloquer. La lecture des
            // mobis du fragment (600 par fragment) se fait sur le fil de lecture,
            // dans l'ordre d'arrivee : le jeu n'attend plus pendant ce temps.
            boolean filtreActif = filtre().actif();
            boolean notre = notreSerie(numero, total);
            if (filtreActif || notre) m.setBlocked(true);   // rien ne passe avant la fin
            HPacket copie = new HPacket(brut);              // la connexion peut reutiliser l'original
            lecture.execute(() -> {
                try { recevoirFragment(gp, copie, total, numero, null, null, filtreActif, notre); }
                catch (Throwable t) { System.err.println("[Atelier] inventaire : " + t); }
            });
            return;
        }

        // En-tete encore inconnu (premiere serie seulement) : la lecture des mobis
        // sert de verification — un paquet etranger ne se lit pas comme un
        // inventaire —, elle reste donc ici.
        long tLecture = System.nanoTime();
        List<InventaireCache.Mobi> morceaux = InventaireCache.decouper(brut);
        HInventoryItem[] items = lireItems(brut, morceaux);
        if (items == null) return;
        lectureNs += System.nanoTime() - tLecture;
        // Un paquet etranger de forme (1, 0, 0 mobi) passerait pour un inventaire
        // vide et fixerait un faux en-tete : une serie ne compte comme inventaire
        // que si elle contient un mobi.
        if (total == 1 && items.length == 0) return;
        boolean filtreActif = filtre().actif();
        boolean notre = notreSerie(numero, total);
        if (filtreActif || notre) m.setBlocked(true);
        recevoirFragment(gp, brut, total, numero, morceaux, items, filtreActif, notre);
    }

    /** Les mobis d'un fragment : ceux du decoupage s'il a reussi, sinon lus par la connexion ; null si illisible. */
    private static HInventoryItem[] lireItems(HPacket brut, List<InventaireCache.Mobi> morceaux) {
        if (morceaux != null) {
            HInventoryItem[] items = new HInventoryItem[morceaux.size()];
            for (int i = 0; i < items.length; i++) items[i] = morceaux.get(i).item;
            return items;
        }
        try {
            HPacket p = new HPacket(brut);
            p.resetReadIndex();
            return HInventoryItem.parse(p);
        } catch (Throwable t) { return null; }
    }

    /** Serie en cours demandee par l'Atelier ? Fixe au premier fragment (fil des paquets). */
    private volatile boolean serieNotreVue = false;

    private boolean notreSerie(int numero, int total) {
        if (numero == 0)
            serieNotreVue = verifDemandeeLe > 0 && System.currentTimeMillis() - verifDemandeeLe < 15_000;
        boolean n = serieNotreVue;
        if (n && numero == total - 1) verifDemandeeLe = 0;
        return n;
    }

    /**
     * Un fragment, deja bloque si besoin : accumule, et a la fin de la serie,
     * mise en cache puis renvoi au jeu (fil d'envoi). Fil de lecture, sauf pour
     * la toute premiere serie (en-tete inconnu).
     */
    private void recevoirFragment(Moteur gp, HPacket brut, int total, int numero,
                                  List<InventaireCache.Mobi> morceaux, HInventoryItem[] items,
                                  boolean filtreActif, boolean notre) {
        if (items == null) {
            long tLecture = System.nanoTime();
            morceaux = InventaireCache.decouper(brut);
            items = lireItems(brut, morceaux);
            lectureNs += System.nanoTime() - tLecture;
            if (items == null) {
                // Deja bloque peut-etre : on le garde tel quel (fragments bruts),
                // pour que la serie se termine et que le jeu recoive quelque chose.
                items = new HInventoryItem[0];
                morceaux = null;
                Journal.debug("inventaire : fragment " + numero + " illisible, gardé tel quel.");
            }
        }
        Serie serie, avant;
        synchronized (verrou) {
            // Premier fragment d'une nouvelle serie : on repart de zero.
            if (numero == 0) {
                accumules.clear();
                accumulesBruts.clear();
                accumulesMobis = new ArrayList<>();
                fragmentsVus = 0;
                totalSerie = total;
                lectureNs = 0;
                debutSerie = System.currentTimeMillis();
                serieNotre = notre;
            }
            accumules.addAll(Arrays.asList(items));
            // Copie : la connexion peut reutiliser le paquet d'origine.
            accumulesBruts.add(brut.toBytes().clone());
            if (accumulesMobis != null) {
                if (morceaux != null && morceaux.size() == items.length) accumulesMobis.addAll(morceaux);
                else {
                    accumulesMobis = null;       // un fragment non decoupable : ancienne methode
                    Journal.debug("inventaire : fragment " + numero
                            + " non découpable en octets bruts, reconstruction classique pour cette série.");
                }
            }
            fragmentsVus++;
            if (numero < total - 1) return;                 // on attend les suivants

            // Serie incomplete (un fragment perdu, ou deux series melangees) :
            // pas de mise en cache. Le jeu recoit l'ancien cache filtre s'il
            // existe, sinon ce qu'on a recu, filtre — jamais rien.
            if (fragmentsVus != total || totalSerie != total) {
                Journal.debug("inventaire : serie incomplete ("
                        + fragmentsVus + "/" + total + "), cache conserve.");
                Serie secours = (this.serie != null) ? this.serie
                        : new Serie(brut.headerId(), null, accumulesMobis, new ArrayList<>(accumules));
                accumules.clear(); accumulesBruts.clear(); accumulesMobis = null;
                // Hors verrou, sur le fil d'envoi : construire et renvoyer un gros
                // inventaire prend du temps.
                final boolean bloque = filtreActif || notre;
                travail.execute(() -> {
                    try {
                        if (filtre().actif()) envoyerFiltre(gp, secours);
                        else if (bloque) envoyerComplet(gp, secours, "série incomplète", false);
                    } catch (Throwable t) { System.err.println("[Atelier] inventaire : " + t); }
                });
                return;
            }
            if (entete < 0 && accumules.isEmpty()) {
                // Pas d'inventaire reconnu (aucun mobi) : ni cache, ni en-tete.
                accumules.clear(); accumulesBruts.clear(); accumulesMobis = null;
                return;
            }
            serie = new Serie(brut.headerId(), new ArrayList<>(accumulesBruts), accumulesMobis,
                    new ArrayList<>(accumules));
            accumules.clear(); accumulesBruts.clear(); accumulesMobis = null;
            Journal.debug("inventaire reçu du serveur : " + serie.items.size() + " mobis en "
                    + total + " morceau(x), " + (System.currentTimeMillis() - debutSerie) + " ms"
                    + (serieNotre ? " (vérification de l'Atelier)" : " (demandé par le jeu)")
                    + ", lecture " + (lectureNs / 1_000_000) + " ms"
                    + (serie.mobis != null ? ", octets bruts gardés" : ", octets bruts NON gardés"));
            avant = this.serie;
            this.serie = serie;
            completLe = System.currentTimeMillis();
            dernierInventaire = serie.items;
            entete = brut.headerId();
        }
        // Le reste (fiches de 25 000 mobis, renvoi au jeu) se fait sur le fil
        // d'envoi : les fragments sont deja bloques si besoin.
        final Serie recue = serie, ancienne = avant;
        final boolean bloque = filtreActif || notre;
        travail.execute(() -> {
            try { apresSerie(gp, recue, ancienne, notre, bloque); }
            catch (Throwable t) { System.err.println("[Atelier] inventaire : " + t); }
        });
    }

    /**
     * Fil de lecture : les fragments d'inventaire et les changements en jeu
     * (pose, ramassage), dans l'ordre d'arrivee, hors du fil des paquets.
     */
    private final java.util.concurrent.ExecutorService lecture =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-inventaire-lecture");
                t.setDaemon(true);
                return t;
            });

    /** Fiches, puis renvoi au jeu si besoin. Fil d'envoi (travail), jamais l'intercepteur. */
    private void apresSerie(Moteur gp, Serie serie, Serie avant, boolean notre, boolean bloque) {
        // Fiche de chaque mobi, une fois pour toutes (le filtrage ne fait plus
        // que la relire).
        long t0 = System.currentTimeMillis();
        if (serie.mobis != null) {
            for (InventaireCache.Mobi mo : serie.mobis) {
                mo.fiche = Fiche.de(gp, mo.item);
                if (mo.fiche != null && mo.fiche.ligne != null) lignesVues.add(mo.fiche.ligne);
            }
        } else {
            for (HInventoryItem it : serie.items) {
                String l = ligne(gp, it);
                if (l != null) lignesVues.add(l);
            }
        }
        Journal.debug("inventaire : fiches calculées en " + (System.currentTimeMillis() - t0) + " ms.");
        boolean actif = filtre().actif();       // le choix le plus recent
        if (!bloque) {
            // la serie du serveur est passee telle quelle
            if (actif) envoyerFiltre(gp, serie); else jeuFiltre = false;
        } else if (notre && avant != null && memesMobis(avant.items, serie.items)) {
            // Notre propre verification : le jeu a deja la bonne liste.
            Journal.debug("inventaire : vérification, rien n'a changé, rien renvoyé au jeu.");
        } else if (actif) envoyerFiltre(gp, serie);
        else if (!notre || jeuFiltre) envoyerComplet(gp, serie, notre ? "vérification" : "filtre retiré", false);
        else {
            // Notre verification, sans filtre, et le jeu n'affiche pas de liste
            // filtree : il n'a rien demande et tient sa liste a jour lui-meme
            // (ajouts et retraits envoyes par le serveur). Lui pousser 25 000
            // mobis le figeait plusieurs secondes (au demarrage de l'Atelier).
            Journal.debug("inventaire : vérification de l'Atelier, rien à renvoyer au jeu (pas de liste filtrée affichée).");
        }
        demanderMajAnnees();
    }

    /** Un seul fil pour construire et renvoyer les listes, dans l'ordre des demandes. */
    private final java.util.concurrent.ExecutorService travail =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atelier-inventaire-envoi");
                t.setDaemon(true);
                return t;
            });

    private boolean serieNotre = false;
    private long debutSerie = 0;
    private int totalSerie = -1;
    private long lectureNs = 0;

    /** Memes objets (identifiants), dans n'importe quel ordre. */
    private static boolean memesMobis(List<HInventoryItem> a, List<HInventoryItem> b) {
        if (a.size() != b.size()) return false;
        Set<Integer> ids = new HashSet<>(a.size() * 2);
        for (HInventoryItem it : a) ids.add(it.getId());
        for (HInventoryItem it : b) if (!ids.contains(it.getId())) return false;
        return true;
    }

    /** L'inventaire complet le plus recent : le cache s'il existe, sinon celui du moteur de l'Atelier. */
    private List<HInventoryItem> inventaireConnu(Moteur gp) {
        Serie s = serie;
        if (s != null) return s.items;
        try { return new ArrayList<>(gp.getInventory().getInventoryItems()); }
        catch (Throwable t) { return new ArrayList<>(); }
    }

    /** Renvoie au jeu, depuis le cache, la liste correspondant au choix actuel. */
    private void envoyerDepuisCache() {
        Moteur gp = AtelierLauncher.moteur();
        Serie s = serie;
        if (gp == null) return;
        if (s == null) {
            // Rien en memoire : on redemande au serveur, et sa reponse passe
            // (filtree ou non) jusqu'au jeu.
            Journal.debug("inventaire : pas encore en mémoire, demandé au serveur.");
            dire("Inventaire pas encore en mémoire : je le demande au jeu…");
            verifier(true);
            return;
        }
        if (!filtre().actif()) envoyerComplet(gp, s, "tout afficher", true);
        else envoyerFiltre(gp, s);
    }

    /**
     * L'inventaire complet : les fragments du serveur tels quels si le cache
     * n'a pas bouge depuis, sinon une serie recollee depuis les octets bruts,
     * et seulement en dernier recours les mobis reecrits un par un.
     */
    /**
     * @param parler dire la reussite (action de l'utilisatrice) ; un echec est toujours dit
     * @return vrai si le jeu a recu l'inventaire complet
     */
    private boolean envoyerComplet(Moteur gp, Serie s, String raison, boolean parler) {
        long t0 = System.nanoTime();
        List<byte[]> fragments;
        String source;
        if (s.bruts != null) { fragments = s.bruts; source = "fragments bruts du serveur"; }
        else if (s.mobis != null) { fragments = InventaireCache.construire(s.mobis, s.entete); source = "octets bruts recollés"; }
        else { fragments = construireClassique(s.items, s.entete); source = "mobis réécrits un par un (secours)"; }
        long t1 = System.nanoTime();
        boolean ok = fragments != null && envoyer(gp, fragments);
        long t2 = System.nanoTime();
        Journal.debug("inventaire complet rétabli (" + raison + ") : " + s.items.size() + " mobis, "
                + (fragments == null ? 0 : fragments.size()) + " fragment(s) "
                + (fragments == null ? 0 : InventaireCache.octets(fragments) / 1024) + " Ko via " + source
                + ", préparation " + ms(t0, t1) + " ms, envoi " + ms(t1, t2) + " ms" + (ok ? "" : " (ÉCHEC)"));
        if (ok) jeuFiltre = false;
        if (!ok) dire(ECHEC_COMPLET);
        else if (parler) dire("Inventaire complet rétabli (" + s.items.size() + " mobis). Le jeu peut mettre un moment à tout réafficher.");
        return ok;
    }

    private static final String ECHEC_COMPLET =
            "Échec : le jeu n'a pas reçu l'inventaire complet. Ferme et rouvre ton inventaire.";

    private void envoyerFiltre(Moteur gp, Serie s) {
        long t0 = System.nanoTime();
        Fiche.Filtre f = filtre();
        List<byte[]> fragments = s.filtrees.get(f);
        boolean depuisCache = fragments != null;
        int gardes;
        long t1, t2;
        if (depuisCache) {
            gardes = s.gardesParFiltre.getOrDefault(f, -1);
            t1 = t2 = System.nanoTime();
        } else {
            boolean fichesCompletes = true;
            if (s.mobis != null) {
                List<InventaireCache.Mobi> l = new ArrayList<>();
                for (InventaireCache.Mobi mo : s.mobis) {
                    Fiche fi = mo.fiche;
                    if (fi == null) { fi = Fiche.de(gp, mo.item); mo.fiche = fi; }
                    if (fi == null) fichesCompletes = false;
                    if (f.garde(fi)) l.add(mo);
                }
                gardes = l.size();
                t1 = System.nanoTime();
                fragments = InventaireCache.construire(l, s.entete);
            } else {
                List<HInventoryItem> l = new ArrayList<>();
                for (HInventoryItem it : s.items) {
                    Fiche fi = Fiche.de(gp, it);
                    if (fi == null) fichesCompletes = false;
                    if (f.garde(fi)) l.add(it);
                }
                gardes = l.size();
                t1 = System.nanoTime();
                fragments = construireClassique(l, s.entete);
            }
            t2 = System.nanoTime();
            // Pas de cache tant que la furnidata manque : le resultat changera.
            if (fragments != null && fichesCompletes) {
                s.filtrees.put(f, fragments);
                s.gardesParFiltre.put(f, gardes);
            }
        }
        // Le filtre a change pendant la construction : la demande suivante
        // (deja en file) enverra le bon ; celle-ci est abandonnee.
        if (!f.equals(filtre())) {
            Journal.debug("inventaire : filtre changé entre-temps, envoi abandonné.");
            return;
        }
        boolean ok = fragments != null && envoyer(gp, fragments);
        long t3 = System.nanoTime();
        if (ok) {
            jeuFiltre = true;
            Journal.debug("inventaire filtré : " + gardes + "/" + s.items.size() + " mobis"
                    + (depuisCache ? " (liste déjà prête en cache)"
                       : " (tri " + ms(t0, t1) + " ms, fragments " + ms(t1, t2) + " ms"
                         + (s.mobis != null ? " par octets bruts" : " par réécriture des mobis (secours)") + ")")
                    + ", " + fragments.size() + " fragment(s) " + InventaireCache.octets(fragments) / 1024 + " Ko"
                    + ", envoi " + ms(t2, t3) + " ms");
            dire(gardes + " mobis affichés sur " + s.items.size() + " — « " + f.libelle() + " »");
        } else {
            // Echec : l'inventaire complet plutot qu'un inventaire vide.
            System.err.println("[Atelier] inventaire : filtre impossible, envoi de l'inventaire complet.");
            if (envoyerComplet(gp, s, "filtre impossible", false))
                dire("Filtre impossible, inventaire complet affiché.");
            // sinon envoyerComplet a deja dit l'echec
        }
    }

    private static long ms(long de, long a) { return (a - de) / 1_000_000; }

    /** Un seul envoi a la fois : deux listes entrelacees se melangeraient chez le client. */
    private boolean envoyer(Moteur gp, List<byte[]> fragments) {
        synchronized (envoi) {
            try {
                for (byte[] b : fragments)
                    if (!gp.sendToClient(new HPacket(b))) {
                        System.err.println("[Atelier] inventaire : la connexion a refusé un fragment.");
                        return false;
                    }
                return true;
            } catch (Throwable t) {
                System.err.println("[Atelier] inventaire : envoi impossible : " + t);
                return false;
            }
        }
    }

    /**
     * Secours quand le decoupage en octets bruts n'est pas sur : chaque mobi est
     * reecrit par la connexion (appendToPacket), un par un, puis recolle.
     *
     * Plus de constructPackets : il recopie tout le paquet a chaque champ ecrit
     * et, bogue de la connexion, met dans le fragment n TOUS les mobis de n*600 a la
     * fin (le fragment 0 contenait les 25 000 mobis). D'ou les 7 s et plus.
     */
    private static List<byte[]> construireClassique(List<HInventoryItem> liste, int h) {
        try {
            // La connexion ne connait pas les types speciaux recents : le champ reste
            // vide a la lecture et la reecriture plantait (NullPointerException),
            // laissant l'inventaire du jeu vide. Type « Default » a la place.
            int inconnus = typesSpeciauxParDefaut(liste);
            if (inconnus > 0)
                Journal.debug("inventaire : " + inconnus + " mobi(s) au type spécial inconnu, envoyés en type par défaut.");
            return InventaireCache.construire(InventaireCache.depuisItems(liste), h);
        } catch (Throwable t) {
            System.err.println("[Atelier] inventaire : reconstruction impossible : " + t);
            return null;
        }
    }

    private static java.lang.reflect.Field champTypeSpecial;

    /** Met HSpecialType.Default la ou le type special n'a pas ete reconnu. @return combien. */
    private static int typesSpeciauxParDefaut(List<HInventoryItem> liste) {
        int n = 0;
        try {
            if (champTypeSpecial == null) {
                champTypeSpecial = HInventoryItem.class.getDeclaredField("specialType");
                champTypeSpecial.setAccessible(true);
            }
            for (HInventoryItem it : liste)
                if (it != null && champTypeSpecial.get(it) == null) {
                    champTypeSpecial.set(it, gearth.extensions.parsers.HSpecialType.Default);
                    n++;
                }
        } catch (Throwable t) {
            Journal.debug("inventaire : type spécial non corrigé (" + t + ")");
        }
        return n;
    }

    // --------------------------------------------------- changements en jeu

    /**
     * Un mobi a quitte l'inventaire (pose dans une salle, vendu, echange) :
     * paquet « FurniListRemove », numero de placement du mobi. Le jeu l'a deja
     * retire de sa fenetre ; on le retire du cache pour que le prochain
     * changement de filtre ne le fasse pas reapparaitre.
     */
    private void mobiRetire(int placement) {
        synchronized (verrou) {
            Serie s = serie;
            if (s == null) return;
            List<HInventoryItem> items = new ArrayList<>(s.items.size());
            List<InventaireCache.Mobi> mobis = (s.mobis == null) ? null : new ArrayList<>(s.mobis.size());
            boolean trouve = false;
            for (int i = 0; i < s.items.size(); i++) {
                if (!trouve && s.items.get(i).getPlacementId() == placement) { trouve = true; continue; }
                items.add(s.items.get(i));
                if (mobis != null) mobis.add(s.mobis.get(i));
            }
            if (!trouve) return;
            remplacer(new Serie(s.entete, null, mobis, items));
        }
        Journal.debug("inventaire : mobi retiré (posé, vendu…), cache mis à jour sans le redemander.");
        demanderMajAnnees();
    }

    /**
     * Mobis ajoutes ou modifies (ramasses dans une salle, achetes, recus) :
     * paquet « FurniListAddOrUpdate ». Mis a jour dans le cache ; si on ne sait
     * pas le lire de facon sure, le cache est marque perime et sera redemande
     * au serveur au prochain changement de filtre.
     */
    private void mobisAjoutes(Moteur gp, HPacket paquet) {
        List<InventaireCache.Mobi> nouveaux = InventaireCache.lireAjouts(paquet);
        synchronized (verrou) {
            Serie s = serie;
            if (s == null) return;
            if (nouveaux == null || s.mobis == null) {
                completLe = 0;
                Journal.debug("inventaire : mobis ajoutés, cache à revérifier auprès du serveur.");
                return;
            }
            List<InventaireCache.Mobi> mobis = new ArrayList<>(s.mobis);
            Map<Integer, Integer> index = new HashMap<>(mobis.size() * 2);
            for (int i = 0; i < mobis.size(); i++) index.put(mobis.get(i).item.getPlacementId(), i);
            for (InventaireCache.Mobi mo : nouveaux) {
                mo.fiche = Fiche.de(gp, mo.item);
                Integer i = index.get(mo.item.getPlacementId());
                if (i != null) mobis.set(i, mo);
                else { index.put(mo.item.getPlacementId(), mobis.size()); mobis.add(mo); }
            }
            List<HInventoryItem> items = new ArrayList<>(mobis.size());
            for (InventaireCache.Mobi mo : mobis) items.add(mo.item);
            remplacer(new Serie(s.entete, null, mobis, items));
        }
        Journal.debug("inventaire : " + nouveaux.size() + " mobi(s) ajouté(s) ou modifié(s), cache mis à jour.");
        demanderMajAnnees();
    }

    /** Le serveur previent que l'inventaire a change : le cache sera reverifie. */
    private void inventairePerime() {
        if (serie == null) return;
        completLe = 0;
        Journal.debug("inventaire : le serveur signale un changement, cache à revérifier.");
    }

    /** Remplace le cache par une version modifiee localement (appele sous verrou). */
    private void remplacer(Serie s) {
        serie = s;
        dernierInventaire = s.items;
    }

    /** Le repertoire a change : familles et annees aussi, les listes filtrees sont a refaire. */
    private void oublierListesFiltrees() {
        Serie s = serie;
        if (s != null) { s.filtrees.clear(); s.gardesParFiltre.clear(); }
    }

    // ----------------------------------------------------------------- cache

    /**
     * Un inventaire complet, jamais modifie une fois cree (une modification
     * locale en cree un nouveau).
     */
    private static final class Serie {
        final int entete;
        /** Les fragments exacts du serveur, dans l'ordre ; null si le cache a ete modifie depuis. */
        final List<byte[]> bruts;
        /** Chaque mobi en octets bruts ; null si le decoupage n'etait pas sur. */
        final List<InventaireCache.Mobi> mobis;
        /** Les memes mobis, lus (jamais modifiee). */
        final List<HInventoryItem> items;
        /** Listes filtrees deja construites, par filtre. */
        final Map<Fiche.Filtre, List<byte[]>> filtrees = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Fiche.Filtre, Integer> gardesParFiltre = new java.util.concurrent.ConcurrentHashMap<>();

        Serie(int entete, List<byte[]> bruts, List<InventaireCache.Mobi> mobis, List<HInventoryItem> items) {
            this.entete = entete;
            this.bruts = bruts;
            this.mobis = mobis;
            this.items = Collections.unmodifiableList(items);
        }
    }

    private final Object verrou = new Object(), envoi = new Object();
    /** Les fragments de la serie en cours : mobis lus, fragments bruts, mobis en octets. */
    private final List<HInventoryItem> accumules = new ArrayList<>();
    private final List<byte[]> accumulesBruts = new ArrayList<>();
    private List<InventaireCache.Mobi> accumulesMobis = null;
    private int fragmentsVus = 0;
    /** Le dernier inventaire complet recu du serveur (et mis a jour en jeu). */
    private volatile Serie serie = null;
    /** Vrai quand le jeu affiche une liste filtree envoyee par l'Atelier. */
    private volatile boolean jeuFiltre = false;
    /** Le meme, lisible par les autres outils (valeur de l'inventaire). Liste jamais modifiee. */
    private static volatile List<HInventoryItem> dernierInventaire = null;

    /** Le dernier inventaire complet recu du serveur, non filtre ; null si aucun encore. */
    public static List<HInventoryItem> dernierInventaire() { return dernierInventaire; }
    private volatile int entete = -1;


    // ---------------------------------------------------------------- outils

    /** La furniline d'un mobi (sa collection), via la fiche en cache. */
    private static String ligne(Moteur gp, HInventoryItem it) {
        Fiche f = Fiche.de(gp, it);
        return (f == null) ? null : f.ligne;
    }

    private void dire(String s) {
        Platform.runLater(() -> { etat.setText(s); majApercu(); });
    }

    // ------------------------------------------------------------- vignettes

    /** Une ligne du menu : la vignette d'un mobi de la famille, puis son nom. */
    private final class CelluleFamille extends ListCell<String> {
        private final javafx.scene.image.ImageView vue = new javafx.scene.image.ImageView();
        CelluleFamille() {
            vue.setFitWidth(26); vue.setFitHeight(26);
            vue.setPreserveRatio(true);
        }
        @Override protected void updateItem(String f, boolean vide) {
            super.updateItem(f, vide);
            if (vide || f == null) { setText(null); setGraphic(null); return; }
            setText(f);
            javafx.scene.image.Image img = Vignettes.pour(f, this::rafraichir);
            vue.setImage(img);
            setGraphic(img == null ? null : vue);
        }
        private void rafraichir() {
            Platform.runLater(() -> updateItem(getItem(), isEmpty()));
        }
    }
}

package atelier;

import gearth.protocol.HConnection;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.services.packet_info.PacketInfo;
import gearth.services.packet_info.PacketInfoManager;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Recherche de mobis : les echanges avec le jeu.
 *
 *   - liste d'une categorie du navigateur : NewNavigatorSearch(code, filtre),
 *     reponse NavigatorSearchResultBlocks (reconnue a son en-tete, ou a son
 *     premier texte, le code demande) ;
 *   - entree dans un appart : OpenFlatConnection(id, "", -1) au serveur ; si le
 *     jeu ne suit pas, RoomForward(id) au jeu (il y va comme par un lien) ;
 *     en dernier recours la commande de chat « :roomid id ». La methode qui
 *     marche est gardee pour la suite. Refus : FlatAccessDenied, CantConnect,
 *     Doorbell (attente a la sonnette) ;
 *   - place du marche : GetMarketplaceOffers(-1, -1, nom, -1, true), reponse
 *     MarketPlaceOffers ; a defaut, les statistiques de Marche ;
 *   - catalogue : GetProductOffer(idOffre), reponse ProductOffer.
 *
 * Les en-tetes sont resolus par nom aupres du proxy (PacketInfoManager) au
 * debut de chaque recherche. Les reponses a NOS demandes sont bloquees : le
 * jeu ne les a pas demandees (ProductOffer ouvrirait une fenetre d'achat).
 *
 * L'intercepteur ne fait que des tests d'entier tant qu'une demande attend
 * (et rien du tout sinon) ; les paquets retenus sont copies et lus sur un fil
 * a part. Rien n'est jamais pose ni achete.
 */
final class RechercheReseau {

    private RechercheReseau() { }

    private static final HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;

    /** Lecture des paquets retenus, hors de l'intercepteur. */
    private static final ExecutorService LECTURE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atelier-recherche-lecture");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean installe = false;
    /** Vrai pendant une recherche : sinon l'intercepteur sort tout de suite. */
    static volatile boolean actif = false;

    // en-tetes resolus (-1 : inconnu)
    private static volatile int hBlocs = -1, hRecherche = -1, hOffres = -1, hProduit = -1,
            hRefus = -1, hImpossible = -1, hSonnette = -1;

    // demandes en attente
    private static volatile String codeAttendu = null;
    private static volatile CompletableFuture<RechercheCalcul.Blocs> attenteBlocs = null;
    private static volatile boolean jeuCherche = false;
    private static volatile CompletableFuture<List<RechercheCalcul.Offre>> attenteOffres = null;
    private static volatile int offreAttendue = 0;
    private static volatile CompletableFuture<RechercheCalcul.Catalogue> attenteProduit = null;
    private static volatile long dernierRefus = 0;

    // ------------------------------------------------------------ installation

    static synchronized void installer(Moteur gp) {
        resoudre();
        if (installe || gp == null) return;
        gp.intercept(C, RechercheReseau::surClient);
        gp.intercept(S, m -> {
            if (!actif || hRecherche < 0) return;
            // le jeu cherche lui-meme dans le navigateur : sa reponse lui revient
            if (m.getPacket().headerId() == hRecherche) jeuCherche = true;
        });
        installe = true;
        Journal.debug("recherche : écoute installée.");
    }

    /** Resout les en-tetes aupres du proxy (la liste change avec la version du jeu). */
    static void resoudre() {
        hBlocs = entete(C, "NavigatorSearchResultBlocks", "Navigator2SearchResultBlocks");
        hRecherche = entete(S, "NewNavigatorSearch");
        hOffres = entete(C, "MarketPlaceOffers", "MarketplaceOffers", "MarketplaceOpenOfferList");
        hProduit = entete(C, "ProductOffer");
        hRefus = entete(C, "FlatAccessDenied");
        hImpossible = entete(C, "CantConnect");
        hSonnette = entete(C, "Doorbell", "DoorbellRinging");
        Journal.debug("recherche : en-têtes blocs=" + hBlocs + " recherche=" + hRecherche + " offres=" + hOffres
                + " produit=" + hProduit + " refus=" + hRefus + "/" + hImpossible + "/" + hSonnette);
    }

    static int entete(HMessage.Direction d, String... noms) {
        try {
            HConnection c = AtelierLauncher.connexionHabbo();
            PacketInfoManager pim = c == null ? null : c.getPacketInfoManager();
            if (pim != null)
                for (String n : noms) {
                    PacketInfo i = pim.getPacketInfoFromName(d, n);
                    if (i != null) return i.getHeaderId();
                }
        } catch (Throwable ignored) { }
        return -1;
    }

    /** Un paquet par son en-tete resolu, sinon par son nom (le proxy le resoudra s'il peut). */
    static HPacket paquet(HMessage.Direction d, String[] noms, Object... v) {
        int h = entete(d, noms);
        return h >= 0 ? new HPacket(h, v) : new HPacket(noms[0], d, v);
    }

    // ------------------------------------------------------------ intercepteur (bon marche)

    private static void surClient(HMessage m) {
        if (!actif) return;
        try {
            HPacket p = m.getPacket();
            int h = p.headerId();
            int n = p.getBytesLength();

            CompletableFuture<RechercheCalcul.Blocs> fb = attenteBlocs;
            if (fb != null && n > 10 && (h == hBlocs || hBlocs < 0) && commencePar(p, codeAttendu)) {
                if (!jeuCherche) m.setBlocked(true);
                HPacket copie = new HPacket(p);
                LECTURE.execute(() -> {
                    RechercheCalcul.Blocs b = RechercheCalcul.lireBlocs(copie);
                    if (b != null) fb.complete(b);
                });
                return;
            }
            CompletableFuture<List<RechercheCalcul.Offre>> fo = attenteOffres;
            if (fo != null && h >= 0 && h == hOffres) {
                m.setBlocked(true);
                HPacket copie = new HPacket(p);
                LECTURE.execute(() -> {
                    List<RechercheCalcul.Offre> l = RechercheCalcul.lireOffres(copie);
                    if (l != null) fo.complete(l);
                });
                return;
            }
            CompletableFuture<RechercheCalcul.Catalogue> fp = attenteProduit;
            int offre = offreAttendue;
            if (fp != null && offre > 0 && (h == hProduit || hProduit < 0) && n >= 23 && p.readInteger(6) == offre) {
                m.setBlocked(true);
                HPacket copie = new HPacket(p);
                LECTURE.execute(() -> {
                    RechercheCalcul.Catalogue c = RechercheCalcul.lireOffreCatalogue(copie, offre);
                    if (c != null) fp.complete(c);
                });
                return;
            }
            if (h >= 0 && (h == hRefus || h == hImpossible)) dernierRefus = System.currentTimeMillis();
            // Doorbell sans nom : c'est nous qui attendons a la sonnette
            else if (h >= 0 && h == hSonnette && n <= 8) dernierRefus = System.currentTimeMillis();
        } catch (Throwable ignored) { }
    }

    /** Le premier texte du paquet est-il ce code ? (comparaison d'octets, sans copie du paquet) */
    private static boolean commencePar(HPacket p, String code) {
        if (code == null) return false;
        byte[] b = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (p.getBytesLength() < 8 + b.length || p.readUshort(6) != b.length) return false;
        String lu = p.readString(6, java.nio.charset.StandardCharsets.UTF_8);
        return code.equals(lu);
    }

    // ------------------------------------------------------------ navigateur

    /** La liste d'un code du navigateur (« hotel_view », « category__Troc »...) ; null sans reponse. */
    static RechercheCalcul.Blocs chercher(Moteur gp, String code, long delaiMs) throws InterruptedException {
        CompletableFuture<RechercheCalcul.Blocs> f = new CompletableFuture<>();
        codeAttendu = code;
        jeuCherche = false;
        attenteBlocs = f;
        try {
            Salle.espacer();
            gp.sendToServer(paquet(S, new String[]{"NewNavigatorSearch"}, code, ""));
            return f.get(delaiMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            Journal.debug("recherche : pas de réponse du navigateur pour " + code + ".");
            return null;
        } finally {
            attenteBlocs = null;
            codeAttendu = null;
        }
    }

    // ------------------------------------------------------------ entree dans un appart

    enum Entree { OK, REFUS, DELAI, ARRET }

    /** 0 : OpenFlatConnection ; 1 : RoomForward ; 2 : « :roomid » ; -1 : pas encore su. */
    private static volatile int methode = -1;
    private static final String[] METHODES = {"OpenFlatConnection", "RoomForward", ":roomid"};

    static String methode() { return methode < 0 ? "pas encore essayée" : METHODES[methode]; }

    /**
     * Entre dans l'appart et attend qu'il soit charge (sols, muraux, plan) :
     * au plus delaiMs. La premiere fois, essaie les methodes l'une apres
     * l'autre et garde celle qui marche.
     */
    static Entree entrer(Moteur gp, int id, long delaiMs, BooleanSupplier arret) {
        int m = methode;
        if (m >= 0) return essayer(gp, id, m, delaiMs, arret);
        for (int i = 0; i < METHODES.length; i++) {
            Entree e = essayer(gp, id, i, delaiMs, arret);
            if (e == Entree.OK) {
                methode = i;
                Journal.debug("recherche : entrée par " + METHODES[i] + ".");
                return e;
            }
            if (e != Entree.DELAI) return e;      // refus ou arret : la methode n'y est pour rien
            Journal.debug("recherche : " + METHODES[i] + " sans effet, essai suivant.");
        }
        return Entree.DELAI;
    }

    private static Entree essayer(Moteur gp, int id, int m, long delaiMs, BooleanSupplier arret) {
        long depart = System.currentTimeMillis();
        Salle.espacer();
        switch (m) {
            case 0 -> gp.sendToServer(paquet(S, new String[]{"OpenFlatConnection"}, id, "", -1));
            case 1 -> gp.sendToClient(paquet(C, new String[]{"RoomForward"}, id));
            default -> gp.sendToServer(paquet(S, new String[]{"Chat"}, ":roomid " + id, 0, -1));
        }
        Salle.envoiFait();
        while (System.currentTimeMillis() - depart < delaiMs) {
            if (arret.getAsBoolean()) return Entree.ARRET;
            if (dernierRefus >= depart) return Entree.REFUS;
            if (chargee(gp, id)) {
                Salle.sommeil(500);              // les derniers muraux et mises a jour
                return Entree.OK;
            }
            Salle.sommeil(150);
        }
        return Entree.DELAI;
    }

    /** L'appart id est-il charge (numero, plan, sols et muraux recus) ? */
    static boolean chargee(Moteur gp, int id) {
        try {
            EtatSalle s = gp.getFloorState();
            return s != null && s.inRoom() && s.getRoomId() == id;
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------ marche et catalogue

    private static final Object RYTHME = new Object();
    private static long derniereDemande = 0;
    /** Au moins 1 s entre deux demandes de marche ou de catalogue. */
    static final long ECART_MS = 1000;

    private static void attendreTour() {
        long attente;
        synchronized (RYTHME) {
            long t = System.currentTimeMillis();
            long d = Math.max(t, derniereDemande + ECART_MS);
            derniereDemande = d;
            attente = d - t;
        }
        if (attente > 0) Salle.sommeil(attente);
        Salle.espacer();
    }

    /** Ce que la place du marche propose pour ce mobi (offres, prix le plus bas, moyenne). */
    static RechercheCalcul.Marche marche(Moteur gp, RechercheCalcul.Cible c) {
        RechercheCalcul.Marche r = null;
        if (hOffres >= 0 && c.nom() != null && !c.nom().isBlank()) {
            CompletableFuture<List<RechercheCalcul.Offre>> f = new CompletableFuture<>();
            attenteOffres = f;
            try {
                attendreTour();
                gp.sendToServer(paquet(S, new String[]{"GetMarketplaceOffers", "MarketplaceSearchOffers"},
                        -1, -1, c.nom(), -1, true));
                r = RechercheCalcul.marchePour(f.get(4000, TimeUnit.MILLISECONDS), c.mur(), c.typeId());
            } catch (Throwable t) {
                Journal.debug("recherche : marché sans réponse pour " + c.nom() + " (" + t.getClass().getSimpleName() + ").");
            } finally {
                attenteOffres = null;
            }
        }
        // la moyenne des ventes (et le nombre d'offres si la recherche n'a rien dit) : Marche
        if (r == null || r.prixMoyen() <= 0) {
            Marche.Prix p = Marche.prix(c.mur(), c.typeId());
            if (p == null || !Marche.aJour(c.mur(), c.typeId())) {
                try { Marche.Prix q = Marche.demander(gp, c.mur(), c.typeId()); if (q != null) p = q; }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (p != null) {
                r = r == null ? new RechercheCalcul.Marche(p.offres, -1, p.moyen > 0 ? p.moyen : -1, true)
                        : new RechercheCalcul.Marche(r.offres(), r.prixMin(), p.moyen > 0 ? p.moyen : -1, true);
            }
        }
        return r == null ? RechercheCalcul.Marche.INCONNU : r;
    }

    /** Le mobi est-il vendu au catalogue (normal), et a quel prix ? */
    static RechercheCalcul.Catalogue catalogue(Moteur gp, RechercheCalcul.Cible c) {
        if (c.offreCatalogue() <= 0) return RechercheCalcul.Catalogue.NON;
        CompletableFuture<RechercheCalcul.Catalogue> f = new CompletableFuture<>();
        offreAttendue = c.offreCatalogue();
        attenteProduit = f;
        try {
            attendreTour();
            gp.sendToServer(paquet(S, new String[]{"GetProductOffer"}, c.offreCatalogue()));
            return f.get(3000, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // pas de reponse : l'offre n'est plus en vente (le jeu ne repond qu'aux offres actives)
            Journal.debug("recherche : catalogue sans réponse pour " + c.nom() + " (offre " + c.offreCatalogue() + ").");
            return RechercheCalcul.Catalogue.INCONNU;
        } finally {
            attenteProduit = null;
            offreAttendue = 0;
        }
    }
}

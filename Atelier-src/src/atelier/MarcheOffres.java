package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * L'offre la moins chere de la place du marche, pour le mobi que tu cliques.
 *
 * Le jeu envoie ClickFurni(int id, int 0) quand on clique un mobi de l'appart
 * (id negatif pour un mural) ; on en tire son type (Salle.sol / Salle.mur),
 * puis on cherche ses offres comme la fenetre du marche :
 *     GetMarketplaceOffers(int prixMin -1, int prixMax -1, String nom,
 *                          int tri -1, boolean regrouper true)
 * reponse MarketPlaceOffers (lue par RechercheCalcul.lireOffres) : une ligne
 * par type, avec le prix le plus bas. C'est le prix que paie l'acheteur
 * (commission comprise) : le client le compare a ton porte-monnaie avant achat.
 *
 * Trafic minimal : seulement le mobi clique, jamais tout l'appart ; une
 * demande a la fois, au moins 2 s entre deux ; resultat garde 15 min (avec
 * ou sans offre, ou sans reponse). Rien pendant une recherche de mobis (meme
 * reponse) ni pour un mobi a ton prix (il passe avant). Si le jeu cherche
 * lui-meme au marche pendant l'attente, sa reponse lui revient.
 */
final class MarcheOffres {

    private MarcheOffres() { }

    private static final HMessage.Direction C = HMessage.Direction.TOCLIENT, S = HMessage.Direction.TOSERVER;
    static final long VALIDITE = 15 * 60_000L, ECART_MS = 2000, DELAI_MS = 4000;

    /** Prix le plus bas (-1 : aucune offre) et moment de la lecture. */
    private record Offre(int prix, long le) { }

    private static final Map<String, Offre> cache = new ConcurrentHashMap<>();
    private static volatile boolean installe = false;
    private static volatile int hOffres = -1, hRecherche = -1;
    /** Le dernier mobi clique pas encore demande (les clics rapides ne gardent que le dernier). */
    private static volatile int[] prochain = null;
    private static volatile boolean enCours = false;
    private static volatile long derniereDemande = 0;
    private static volatile CompletableFuture<List<RechercheCalcul.Offre>> attente = null;
    private static volatile boolean jeuCherche = false;

    /** Offre la moins chere connue (> 0) depuis moins de 15 min, ou null. */
    static Integer prix(boolean mur, int typeId) {
        Offre o = cache.get(Marche.cle(mur, typeId));
        if (o == null || o.prix <= 0 || System.currentTimeMillis() - o.le > VALIDITE) return null;
        return o.prix;
    }

    static void installer(Moteur gp) {
        if (installe || gp == null) return;                 // appele a chaque table de PrixFiche
        installerUneFois(gp);
    }

    private static synchronized void installerUneFois(Moteur gp) {
        if (installe) return;
        installe = true;
        try {
            gp.intercept(S, "ClickFurni", m -> {
                try {
                    HPacket p = m.getPacket();
                    if (p.getBytesLength() < 10) return;
                    int id = p.readInteger(6);                 // index fixe, le reste sur un autre fil
                    Salle.tache("marche-clic", () -> clic(id));
                } catch (Throwable ignored) { }
            });
            gp.intercept(S, m -> {
                if (attente == null || hRecherche < 0) return;
                if (m.getPacket().headerId() == hRecherche) jeuCherche = true;
            });
            gp.intercept(C, m -> {
                CompletableFuture<List<RechercheCalcul.Offre>> f = attente;
                if (f == null || hOffres < 0) return;
                try {
                    HPacket p = m.getPacket();
                    if (p.headerId() != hOffres) return;
                    if (!jeuCherche) m.setBlocked(true);    // reponse a NOTRE demande
                    HPacket copie = new HPacket(p);
                    Salle.tache("marche-offres-lecture", () -> {
                        List<RechercheCalcul.Offre> l = RechercheCalcul.lireOffres(copie);
                        if (l != null) f.complete(l);
                    });
                } catch (Throwable ignored) { }
            });
            Journal.debug("marché : écoute des clics sur les mobis installée.");
        } catch (Throwable t) {
            Journal.debug("marché : écoute des clics indisponible : " + t);
        }
    }

    /** Un mobi clique : son type, et une demande si rien de frais n'est connu. */
    private static void clic(int id) {
        if (id == 0 || !ClientModifie.saitPrix()) return;
        boolean mur = id < 0;
        int typeId;
        if (mur) { HWallItem w = Salle.mur(-id); if (w == null) return; typeId = w.getTypeId(); }
        else { HFloorItem f = Salle.sol(id); if (f == null) return; typeId = f.getTypeId(); }
        Offre o = cache.get(Marche.cle(mur, typeId));
        if (o != null && System.currentTimeMillis() - o.le < VALIDITE) {
            Journal.debug("marché : " + Salle.classe(typeId, mur) + ", depuis le cache ("
                    + (o.prix > 0 ? o.prix : "aucune offre") + ").");
            return;
        }
        if (PrixPerso.prix(mur, typeId) != null) return;    // ton prix passe avant
        prochain = new int[]{mur ? 1 : 0, typeId};
        demarrer();
    }

    private static synchronized void demarrer() {
        if (enCours) return;
        enCours = true;
        Salle.tache("marche-offres", () -> {
            try {
                int[] d;
                while ((d = prochain) != null) {
                    prochain = null;
                    long reste = derniereDemande + ECART_MS - System.currentTimeMillis();
                    if (reste > 0) Salle.sommeil(reste);
                    demander(d[0] == 1, d[1]);
                }
            } finally {
                synchronized (MarcheOffres.class) { enCours = false; }
                if (prochain != null) demarrer();
            }
        });
    }

    private static void demander(boolean mur, int typeId) {
        String k = Marche.cle(mur, typeId);
        Offre o = cache.get(k);
        if (o != null && System.currentTimeMillis() - o.le < VALIDITE) return;
        if (RechercheReseau.actif) return;                  // recherche de mobis : meme reponse
        if (occupe()) { Journal.debug("marché : action en cours, demande abandonnée."); return; }
        Moteur gp = Salle.gp();
        String classe = Salle.classe(typeId, mur);
        if (gp == null || classe == null || classe.startsWith("poster")) return;  // affiches : prix par affiche
        String nom = Salle.nom(typeId, mur);
        if (nom == null || nom.isBlank() || nom.equals(Salle.nomDeClasse(classe))) return;  // pas de vrai nom
        hOffres = RechercheReseau.entete(C, "MarketPlaceOffers", "MarketplaceOffers", "MarketplaceOpenOfferList");
        hRecherche = RechercheReseau.entete(S, "GetMarketplaceOffers", "MarketplaceSearchOffers");
        if (hOffres < 0) { Journal.debug("marché : réponse des offres inconnue du proxy, pas de demande."); return; }
        CompletableFuture<List<RechercheCalcul.Offre>> f = new CompletableFuture<>();
        jeuCherche = false;
        attente = f;
        try {
            Salle.espacer();
            if (occupe()) { Journal.debug("marché : action en cours, demande abandonnée."); return; }
            Journal.debug("marché : demande des offres pour " + classe + ".");
            derniereDemande = System.currentTimeMillis();
            gp.sendToServer(RechercheReseau.paquet(S, new String[]{"GetMarketplaceOffers", "MarketplaceSearchOffers"},
                    -1, -1, nom, -1, true));
            RechercheCalcul.Marche r = RechercheCalcul.marchePour(f.get(DELAI_MS, TimeUnit.MILLISECONDS), mur, typeId);
            cache.put(k, new Offre(r.prixMin() > 0 ? r.prixMin() : -1, System.currentTimeMillis()));
            Journal.debug("marché : " + nom + (r.prixMin() > 0 ? ", offre la moins chère " + r.prixMin()
                    + Ui.accorder(" (" + r.offres() + " offre(s))") : ", aucune offre") + ".");
        } catch (Throwable t) {
            cache.put(k, new Offre(-1, System.currentTimeMillis()));   // pas de nouvel essai avant 15 min
            Journal.debug("marché : offres sans réponse pour " + nom + " (" + t.getClass().getSimpleName() + ").");
        } finally {
            attente = null;
            Salle.envoiFait();
        }
    }

    /** Une pose, un collage, un remplacement ou les taches quotidiennes : pas de demande au marche. */
    private static boolean occupe() {
        return PoseTapis.enCours() || Groupes.occupe() || RemplacerParZone.enCours() || PoseCopie.occupee()
                || WiredCollage.occupe() || TachesQuotidiennes.actif() || TachesQuotidiennes.enCours() != 0;
    }
}

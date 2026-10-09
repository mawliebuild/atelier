package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Pose des muraux d'une copie (remplace placeWallItems et
 * applyWallFurniVariables de l'ancien importeur, que OngletApparts.poserMuraux
 * pilote aujourd'hui par « :ip » sans mobi de sol).
 *
 * Ce que le jeu affiche (client lu : onItemUpdate / addWallItem) : la position
 * d'un mural est ENTIEREMENT sa position murale « :w=x,y l=dx,dy r|l » ; le
 * client la convertit en point 3D avec la hauteur de la case (x, y) du plan
 * (case vide derriere un mur : min(20, hauteurMurs) + hauteur du mur ; case de
 * porte : sol + hauteur du mur ; hors du plan : 0). Il n'y a aucun autre champ.
 * Les variables internes « -123 » (altitude) et « -190 » (decalage X) que le
 * serveur rend pour un mural sont CALCULEES depuis cette position (-190 = dx ;
 * -123 depend aussi des hauteurs du plan cote serveur) : les ecrire fait
 * recalculer et DEPLACER le mural (WiredMovements genre 2). Elles ne sont donc
 * plus jamais ecrites ici : la position seule fait foi (MoveWallItem si besoin).
 *
 * Pour chaque mural, l'un apres l'autre : POSE -> ETAT -> POSITION :
 *   1. position absolue = position de la copie + coin (PositionMur), envoyee
 *      sans l'altitude ; source inventaire ou BC (affiches : l'inventaire de
 *      meme etat ; BC : OffresBc, page / offre / extra du produit du catalogue
 *      BC, comme le client ; post-its : PlacePostIt puis leur couleur par
 *      SetItemData) ; si le jeu refuse la case (rien n'apparait) et qu'on a des
 *      droits, nouvel essai sur une case sure (celle d'un mural deja la) ;
 *   2. son etat tout de suite (variable « -110 », sinon UseWallItem, l'etat
 *      relu par ItemStateUpdate / ItemsStateUpdate, comme le client) ;
 *   3. sa position : relue (ItemAdd, ItemUpdate, WiredMovements) et remise
 *      par MoveWallItem si elle differe (une variable d'etat peut l'avoir
 *      bougee) ;
 *   4. les etats restes faux : une derniere tentative, puis le compte ;
 *   5. verification : jusqu'a 4 passes sur la position complete relue ;
 *   6. les valeurs des variables de l'utilisatrice (hors « @ » et « - »).
 * L'importeur attend 230 ms fixes apres une pose BC : ici on suit l'arrivee
 * (au plus 2 s) avant de conclure au refus.
 *
 * Aucun travail sur le fil JavaFX, aucun message dans le jeu.
 */
final class PoseMuraux {

    /** Ce que rend la pose. */
    static final class Bilan {
        /** Id du mural dans la copie -> id reel. */
        final Map<Integer, Integer> ids = new LinkedHashMap<>();
        /** Muraux non poses : nom (classe) et raison. */
        final List<String> manquants = new ArrayList<>();
        /** Parmi les manquants : les classes pas posables au Builders Club (ni dans l'inventaire). */
        final List<String> pasAuBc = new ArrayList<>();
        /** Corrections envoyees (variables ou deplacements). */
        int corrections;
        /** Muraux encore faux apres les verifications. */
        final List<Integer> faux = new ArrayList<>();
        int etatsFaux, variablesEnvoyees;
        /** La salle n'a pas la meme forme que la salle d'origine (les muraux peuvent etre decales). */
        String avertissement;
        /** Raisons de refus donnees par le serveur (avertissement BC, erreur) -> nombre de muraux. */
        final Map<String, Integer> raisons = new LinkedHashMap<>();
        boolean arrete;
        String erreur;

        String texte() {
            if (erreur != null) return "Pose des muraux impossible : " + erreur + ".";
            StringBuilder b = new StringBuilder(PoseOutils.nombre(ids.size(), "mural posé", "muraux posés"));
            if (!manquants.isEmpty()) b.append(", ").append(PoseOutils.nombre(manquants.size(), "manquant", "manquants"));
            if (!pasAuBc.isEmpty()) b.append(" (dont ").append(pasAuBc.size()).append(" pas au Builders Club)");
            if (!faux.isEmpty()) b.append(", ").append(PoseOutils.nombre(faux.size(), "mal placé", "mal placés"));
            if (etatsFaux > 0) b.append(", ").append(PoseOutils.nombre(etatsFaux, "état faux", "états faux"));
            if (arrete) b.append(" (arrêtée)");
            if (!raisons.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (Map.Entry<String, Integer> e : raisons.entrySet()) l.add(e.getKey() + " (" + e.getValue() + ")");
                b.append(" ; raison donnée par le jeu : ").append(String.join(", ", l));
            }
            String t = Ui.majuscule(b.toString()) + ".";
            return avertissement == null ? t : t + " " + avertissement;
        }
    }

    private final Canal canal;
    private final EtatSalle salle;
    private final Droits droits;
    private final Inventaire inventaire;
    private final CatalogueBc catalogue;
    private final Furnidata furnidata;

    private final Map<Integer, Map<String, Integer>> inspections = new ConcurrentHashMap<>();
    private volatile int inspectionAttendue = 0;
    private final Semaphore inspectionRecue = new Semaphore(0);

    /** Attente d'une inspection (fetchWallItemVariables : 600 ms). */
    static final long INSPECTION_MS = 600;
    /** Pauses entre deux passes de verification (settleAfterAttemptMs). */
    static final int[] PAUSES_VERIFICATION = {350, 600, 900, 1200};

    PoseMuraux(Canal canal, EtatSalle salle, Droits droits, Inventaire inventaire, CatalogueBc catalogue, Furnidata furnidata) {
        this.canal = canal;
        this.salle = salle;
        this.droits = droits;
        this.inventaire = inventaire;
        this.catalogue = catalogue;
        this.furnidata = furnidata;
        canal.intercept(HMessage.Direction.TOCLIENT, "WiredVariablesForObject", m -> {
            PoseOutils.VariablesObjet v = PoseOutils.lireVariablesObjet(m.getPacket());
            if (v == null || v.objet() == 0) return;
            inspections.put(v.objet(), v.variables());
            if (v.objet() == inspectionAttendue) inspectionRecue.release();
        });
    }

    /** Un mural pose, a verifier. */
    private record Place(int id, PositionMur voulue) { }

    /**
     * Pose les muraux de la copie.
     *
     * @param coin           coin de la copie dans la salle
     * @param tableVariables id de variable de la copie -> id reel (ReglageWiredPose) ; null = par le nom seulement
     */
    Bilan poser(CopieAppart copie, HPoint coin, PoseOutils.Source source, Map<String, String> tableVariables,
                BooleanSupplier stop) {
        Bilan b = new Bilan();
        if (stop == null) stop = () -> false;
        if (copie == null || copie.murs.isEmpty()) return b;
        if (!salle.dansUneSalle()) { b.erreur = "pas dans une salle"; return b; }
        if (furnidata == null || !furnidata.pret()) { b.erreur = "furnidata pas prête"; return b; }
        b.avertissement = comparerDisposition(copie, coin);
        PoseOutils.Signaux.installer();              // raisons de refus du serveur (avertissement BC...)

        boolean variables = peutVariables(), deplacer = droits != null && droits.peutDeplacer();
        Map<String, LinkedList<HInventoryItem>> stock = new HashMap<>();
        List<Place> places = new ArrayList<>();
        List<int[]> etats = new ArrayList<>();                 // {id, etat}

        for (CopieAppart.MobiMur m : copie.murs) {
            if (stop.getAsBoolean() || !salle.dansUneSalle()) { b.arrete = stop.getAsBoolean(); break; }
            String etat = m.etat != null ? m.etat : "";
            Integer type = furnidata.typeMur(m.classe);
            if (type == null) { b.manquants.add(m.classe + " (inconnu de la furnidata)"); continue; }
            PositionMur voulue = new PositionMur(m.position.x() + coin.getX(), m.position.y() + coin.getY(),
                    m.position.decalageX(), m.position.decalageY(), m.position.cote(), m.position.altitude());
            String ou = voulue.toString();
            boolean affiche = "poster".equals(m.classe);
            boolean postIt = PoseOutils.postIt(m.classe);

            LinkedList<HInventoryItem> inv = stock.computeIfAbsent(affiche ? type + ":" + etat : String.valueOf(type), k -> {
                LinkedList<HInventoryItem> l = new LinkedList<>();
                if (inventaire != null && inventaire.charge())
                    for (HInventoryItem it : inventaire.mursDeType(type)) {
                        String e = "";
                        try { e = it.getStuff() != null ? it.getStuff().getLegacyString() : ""; } catch (Exception ignored) { }
                        if (!affiche || etat.isEmpty() || etat.equals(e)) l.add(it);
                    }
                return l;
            });
            // offre BC comme le client (OffresBc) : page et offre du catalogue BC, l'extra du PRODUIT
            // (numero d'une affiche), jamais l'etat du mural ni l'offerid de la furnidata
            OffresBc.Offre bc = OffresBc.mur(catalogue, furnidata, m.classe, affiche ? etat : "");
            if (bc == null && !affiche) bc = OffresBc.mur(catalogue, furnidata, m.classe, null);
            boolean parBc = switch (source) {
                case BC -> true;
                case BC_PUIS_INVENTAIRE -> bc != null || inv.isEmpty();
                case INVENTAIRE_PUIS_BC -> inv.isEmpty();
                case INVENTAIRE -> false;
            };

            Set<Integer> avant = new HashSet<>(PoseOutils.idsMuraux(salle));
            long t0 = System.currentTimeMillis();
            String sure = null;
            for (HWallItem w : salle.getWallItems()) { sure = w.getLocation(); break; }
            if (sure == null) sure = ":w=0,0 l=0,0 l";

            boolean surCaseSure = false;
            Integer nouveau;
            if (parBc) {
                if (bc == null) {
                    b.manquants.add(m.classe + " (pas au Builders Club)");
                    b.pasAuBc.add(m.classe);
                    Journal.debug("Pose muraux : " + m.classe + " " + ou + " pas posé : pas au Builders Club"
                            + (source.inventaire() ? " et absent de l'inventaire" : "") + ".");
                    continue;
                }
                final String o = ou; final OffresBc.Offre of = bc;
                nouveau = avecReessais(() -> PoseOutils.envoyer(canal, PoseOutils.poseMurBc(of.page(), of.offre(), of.extra(), o)), avant, type, stop);
                if (nouveau == null && deplacer) {
                    PoseOutils.envoyer(canal, PoseOutils.poseMurBc(bc.page(), bc.offre(), bc.extra(), sure));
                    surCaseSure = true;
                    nouveau = attendreNouveau(avant, type, stop);
                }
            } else {
                // un bloc de post-its pose une feuille a chaque fois : il reste dans l'inventaire
                HInventoryItem it = postIt ? inv.peekFirst() : inv.pollFirst();
                if (it == null) {
                    b.manquants.add(m.classe + (etat.isEmpty() ? "" : " (état " + etat + ")") + " (absent de l'inventaire)");
                    Journal.debug("Pose muraux : " + m.classe + (etat.isEmpty() ? "" : " état " + etat) + " " + ou
                            + " pas posé : absent de l'inventaire" + (source.bc() ? " et pas au Builders Club" : "") + ".");
                    continue;
                }
                if (postIt) {
                    // PlacePostIt avec le premier bloc ; refuse (bloc vide ?) : le bloc suivant
                    nouveau = null;
                    for (int essai = 0; nouveau == null && !inv.isEmpty() && essai < 4 && !stop.getAsBoolean(); essai++) {
                        HInventoryItem bloc = inv.peekFirst();
                        PoseOutils.envoyer(canal, PoseOutils.posePostIt(bloc.getId(), ou));
                        nouveau = attendreNouveau(avant, type, stop);
                        if (nouveau == null) {
                            inv.pollFirst();
                            Journal.debug("Pose muraux : post-it refusé avec le bloc " + bloc.getId() + (inv.isEmpty() ? "" : ", essai avec le suivant") + ".");
                        }
                    }
                } else {
                    final String o = ou;
                    nouveau = avecReessais(() -> PoseOutils.envoyer(canal, PoseOutils.poseMurInventaire(it.getId(), o)), avant, type, stop);
                    if (nouveau == null && deplacer) {
                        PoseOutils.envoyer(canal, PoseOutils.poseMurInventaire(it.getId(), sure));
                        surCaseSure = true;
                        nouveau = attendreNouveau(avant, type, stop);
                    }
                }
            }
            if (nouveau == null) {
                Salle.signalerRefus("pose d'un mural");
                String raison = PoseOutils.Signaux.raisonMur(t0);
                Journal.debug("Pose muraux : " + m.classe + " " + ou + (parBc ? " (BC " + bc + ")" : postIt ? " (post-it de l'inventaire)" : " (inventaire)")
                        + " refusé" + (raison != null ? " : " + raison : ", sans message du serveur") + ".");
                b.manquants.add(m.classe + " (refusé par le jeu" + (raison != null ? " : " + raison : "") + ")");
                if (raison != null) b.raisons.merge(raison, 1, Integer::sum);
                continue;
            }
            Salle.signalerReussite();
            int id = nouveau;
            b.ids.put(m.id, id);
            places.add(new Place(id, voulue));
            HWallItem w = salle.wallItemFromId(id);

            // 1. POSE faite ; 2. son ETAT tout de suite (avant sa hauteur / sa position) ;
            // un post-it : sa couleur (SetItemData), son texte n'est pas dans la copie
            if (postIt) {
                if (PoseOutils.couleurPostIt(etat) && w != null && !etat.equalsIgnoreCase(w.getState()) && !stop.getAsBoolean()) {
                    PoseOutils.envoyer(canal, PoseOutils.donneesPostIt(id, etat.toUpperCase(java.util.Locale.ROOT), ""));
                    PoseOutils.attendre(() -> { HWallItem x = salle.wallItemFromId(id); return x == null || etat.equalsIgnoreCase(x.getState()); }, 1000, stop);
                }
            } else if (!affiche && !etat.isEmpty() && w != null && !memeEtat(etat, w.getState()) && !stop.getAsBoolean())
                try {
                    int e = Integer.parseInt(etat);
                    if (!etatMur(id, e, variables, stop)) etats.add(new int[]{id, e});
                } catch (NumberFormatException ignored) { }
            w = salle.wallItemFromId(id);

            // 3. sa POSITION : la position murale complete fait foi ; jamais « -123 » ni « -190 »
            // (le serveur les calcule depuis la position, les ecrire deplace le mural)
            PositionMur lue = w == null ? null : PositionMur.lireOuNull(w.getLocation());
            if (!surCaseSure && memePosition(lue, voulue)) continue;
            if (deplacer) {
                b.corrections++;
                PoseOutils.envoyer(canal, PoseOutils.deplacementMur(id, ou));
            } else {
                Journal.debug("Pose muraux : " + m.classe + " (" + id + ") posé en " + (w == null ? "?" : w.getLocation())
                        + " au lieu de " + ou + ", pas de droit de déplacement pour le remettre.");
            }
        }

        // les etats restes faux apres leur pose : une derniere tentative, puis le compte ;
        // AVANT la verification des positions (un changement d'etat peut deplacer un mural)
        if (!etats.isEmpty() && !stop.getAsBoolean()) etats(etats, variables, b, stop);
        if (!places.isEmpty() && !stop.getAsBoolean()) verifier(places, deplacer, b, stop);
        if (!stop.getAsBoolean()) variablesUtilisatrice(copie, b.ids, tableVariables, variables, b, stop);
        if (stop.getAsBoolean()) b.arrete = true;
        Journal.debug("Pose muraux : " + b.texte());
        return b;
    }

    /** « Pousse » un mural d'un pixel puis le remet (nudgeWallItem) : le jeu redessine sa position. */
    void pousser(int id, PositionMur p) {
        PoseOutils.envoyer(canal, PoseOutils.deplacementMur(id,
                new PositionMur(p.x(), p.y(), p.decalageX() + 1, p.decalageY(), p.cote()).toString()));
        PoseOutils.envoyer(canal, PoseOutils.deplacementMur(id, p.toString()));
    }

    /**
     * Les variables d'un mural (WiredGetVariablesForObject), id de variable ->
     * valeur ; null sans reponse dans le delai.
     */
    Map<String, Integer> inspecter(int id, long delaiMs) {
        inspections.remove(id);
        inspectionRecue.drainPermits();
        inspectionAttendue = id;
        try {
            PoseOutils.envoyer(canal, PoseOutils.inspectionMural(id));
            inspectionRecue.tryAcquire(delaiMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inspectionAttendue = 0;
        }
        return inspections.get(id);
    }

    // ================================================================ interne

    private boolean peutVariables() { return droits != null && (droits.peutRegler() || droits.peutDeplacer()); }

    /** Le nouveau mural de ce type (2 s au plus), ou null. */
    /**
     * Pose sur la case voulue : si rien n'apparait, renvoie jusqu'a
     * Salle.REESSAIS fois apres Salle.pauseReessai() (refus passager du jeu).
     */
    private Integer avecReessais(Runnable poser, Set<Integer> avant, int type, BooleanSupplier stop) {
        Integer nouveau = null;
        for (int essai = 0; essai <= Salle.REESSAIS && nouveau == null && !stop.getAsBoolean(); essai++) {
            if (essai > 0) {
                Salle.pauseReessai();
                // arrive en retard (apres les 2 s) : jamais de deuxieme pose
                nouveau = nouveauDejaLa(avant, type);
                if (nouveau != null) break;
            }
            poser.run();
            nouveau = attendreNouveau(avant, type, stop);
        }
        if (nouveau == null && !stop.getAsBoolean()) {
            // derniere chance avant le repli sur la case sure (qui reposerait le mobi)
            Salle.pauseReessai();
            nouveau = nouveauDejaLa(avant, type);
        }
        return nouveau;
    }

    /** Un mural de ce type apparu depuis « avant » (lecture immediate, sans attente) ; null sinon. */
    private Integer nouveauDejaLa(Set<Integer> avant, int type) {
        for (HWallItem w : salle.getWallItems())
            if (!avant.contains(w.getId()) && w.getTypeId() == type) return w.getId();
        return null;
    }

    private Integer attendreNouveau(Set<Integer> avant, int type, BooleanSupplier stop) {
        Integer[] r = {null};
        PoseOutils.attendre(() -> {
            for (HWallItem w : salle.getWallItems())
                if (!avant.contains(w.getId()) && w.getTypeId() == type) { r[0] = w.getId(); return true; }
            return false;
        }, 2000, stop);
        return r[0];
    }

    /**
     * wallValidate : jusqu'a 4 passes, relecture de la position murale complete
     * (ItemAdd, ItemUpdate et WiredMovements la tiennent a jour dans EtatSalle) ;
     * MoveWallItem si elle differe et qu'on peut deplacer.
     */
    private void verifier(List<Place> places, boolean deplacer, Bilan b, BooleanSupplier stop) {
        if (!PoseOutils.dormir(200, stop)) return;
        List<Place> aVoir = new ArrayList<>(places);
        for (int passe = 1; passe <= 4 && !aVoir.isEmpty() && !stop.getAsBoolean(); passe++) {
            List<Place> encore = new ArrayList<>();
            int corrections = 0;
            for (Place p : aVoir) {
                if (stop.getAsBoolean()) break;
                HWallItem w = salle.wallItemFromId(p.id);
                if (w == null) { Journal.debug("Pose muraux : " + p.id + " absent de la salle"); continue; }
                PositionMur lue = PositionMur.lireOuNull(w.getLocation());
                if (memePosition(lue, p.voulue)) continue;
                Journal.debug("Pose muraux : passe " + passe + ", " + p.id + " attendu " + p.voulue + ", lu " + w.getLocation()
                        + (deplacer ? " : remis par MoveWallItem" : " : pas de droit de déplacement"));
                encore.add(p);
                if (deplacer) {
                    corrections++;
                    PoseOutils.envoyer(canal, PoseOutils.deplacementMur(p.id, p.voulue.toString()));
                }
            }
            b.corrections += corrections;
            aVoir = encore;
            if (corrections == 0) break;
            if (passe < 4 && !PoseOutils.dormir(PAUSES_VERIFICATION[passe - 1], stop)) break;
            if (passe == 4) {
                // derniere relecture apres la derniere correction
                PoseOutils.dormir(PAUSES_VERIFICATION[3], stop);
                List<Place> restent = new ArrayList<>();
                for (Place p : aVoir) {
                    HWallItem w = salle.wallItemFromId(p.id);
                    if (w != null && !memePosition(PositionMur.lireOuNull(w.getLocation()), p.voulue)) restent.add(p);
                }
                aVoir = restent;
            }
        }
        for (Place p : aVoir) b.faux.add(p.id);
    }

    /** Meme position murale (case, decalages, cote), l'altitude ignoree. Logique pure. */
    static boolean memePosition(PositionMur a, PositionMur b) {
        return a != null && b != null && a.x() == b.x() && a.y() == b.y() && a.decalageX() == b.decalageX()
                && a.decalageY() == b.decalageY() && a.cote() == b.cote();
    }

    /**
     * Meme etat au sens du client (parseItemData : int(data) si c'est un
     * nombre, sinon 0) ; un etat non numerique (couleur de post-it) compare
     * sans la casse. Logique pure.
     */
    static boolean memeEtat(String voulu, String lu) {
        if (voulu == null || lu == null) return false;
        Integer a = entier(voulu), c = entier(lu);
        if (a != null && c != null) return a.intValue() == c.intValue();
        return voulu.equalsIgnoreCase(lu);
    }

    private static Integer entier(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Une altitude enregistree dans la copie (0 : pas lue a l'enregistrement). Logique pure. */
    static boolean altitudeConnue(PositionMur p) { return p != null && p.altitude() != 0; }

    /** Les etats des muraux restes faux (variable -110, sinon UseWallItem), puis le compte. */
    private void etats(List<int[]> etats, boolean variables, Bilan b, BooleanSupplier stop) {
        for (int[] e : etats) {
            if (stop.getAsBoolean()) break;
            HWallItem w0 = salle.wallItemFromId(e[0]);
            if (w0 == null) continue;
            String avant = w0.getState();
            if (!etatMur(e[0], e[1], variables, stop)) {
                b.etatsFaux++;
                HWallItem w = salle.wallItemFromId(e[0]);
                String lu = w == null ? "?" : w.getState();
                Journal.debug("Pose muraux : état faux pour " + Salle.classe(w0.getTypeId(), true) + " (" + e[0] + ") : voulu " + e[1]
                        + ", lu " + lu + (memeEtat(avant, lu)
                        ? " (le jeu n'a renvoyé aucun changement d'état : mobi sans états à cycler, ou droits insuffisants)"
                        : " (il change, mais n'atteint pas l'état voulu)") + ".");
            }
        }
    }

    /**
     * Met un mural a l'etat voulu ; true s'il y est. L'etat est relu par
     * ItemStateUpdate / ItemsStateUpdate (EtatSalle), comme le client :
     *   - variable -110 si l'on a des droits ;
     *   - sinon (ou sans effet) UseWallItem(id, 0), un clic a la fois, chaque
     *     clic attendu (1 s) avant le suivant ; arret des que l'etat voulu est
     *     la, si un clic ne change rien, ou si le cycle revient a un etat deja
     *     vu (l'etat voulu n'existe pas). Plus jamais de clic « en trop » pendant
     *     qu'un changement est encore en route.
     * Si l'etat a deplace le mural, la position est remise ensuite (etape 3).
     */
    private boolean etatMur(int id, int etat, boolean variables, BooleanSupplier stop) {
        String voulu = String.valueOf(etat);
        HWallItem w0 = salle.wallItemFromId(id);
        if (w0 == null) return false;
        if (memeEtat(voulu, w0.getState())) return true;
        String position = w0.getLocation();
        if (variables) {
            PoseOutils.envoyerVariable(canal, PoseOutils.variableInterneMural(id, "-110", etat));
            PoseOutils.attendre(() -> { HWallItem w = salle.wallItemFromId(id); return w == null || memeEtat(voulu, w.getState()); }, 1000, stop);
        }
        Set<String> vus = new HashSet<>();
        for (int i = 0; i < 20 && !stop.getAsBoolean(); i++) {
            HWallItem w = salle.wallItemFromId(id);
            if (w == null || memeEtat(voulu, w.getState())) break;
            String avant = w.getState();
            if (!vus.add(String.valueOf(entier(String.valueOf(avant))))) break;   // cycle complet
            PoseOutils.envoyer(canal, PoseOutils.utilisationMural(id));
            boolean change = PoseOutils.attendre(() -> {
                HWallItem x = salle.wallItemFromId(id);
                return x == null || !memeEtat(avant, x.getState());
            }, 1000, stop);
            if (!change) break;                         // l'utilisation ne change rien : inutile d'insister
        }
        HWallItem w = salle.wallItemFromId(id);
        if (w != null && position != null && !position.equals(w.getLocation()))
            Journal.debug("Pose muraux : le changement d'état a déplacé " + id + " (" + position + " -> " + w.getLocation() + "), remis ensuite.");
        return w != null && memeEtat(voulu, w.getState());
    }

    /** applyWallFurniVariables : valeurs des variables de l'utilisatrice (hors « @ » et « - »). */
    private void variablesUtilisatrice(CopieAppart copie, Map<Integer, Integer> ids, Map<String, String> table,
                                       boolean variables, Bilan b, BooleanSupplier stop) {
        if (!variables) return;
        for (CopieAppart.MobiMur m : copie.murs) {
            if (m.variables == null || m.variables.isEmpty()) continue;
            Integer reel = ids.get(m.id);
            if (reel == null) continue;
            for (Map.Entry<String, Integer> e : m.variables.entrySet()) {
                if (stop.getAsBoolean()) return;
                String nom = e.getKey();
                if (nom == null || nom.isEmpty() || e.getValue() == null || nom.startsWith("@") || nom.startsWith("-")) continue;
                String ancien = copie.tableVariables.get(nom);
                String id = ancien != null && table != null ? table.get(ancien) : null;
                PoseOutils.envoyerVariable(canal, PoseOutils.variableMural(reel, id != null ? id : nom, e.getValue()));
                b.variablesEnvoyees++;
            }
        }
    }

    /**
     * null si la salle a la forme de la salle d'origine (ou si on ne sait pas),
     * sinon un avertissement. La signature est le SHA-1 de « echelle|hauteur
     * des murs|plan brut » (FloorHeightMap) : elle differe des qu'une case du
     * plan (hauteur, porte, case vide) differe. Une copie de l'ancien module a
     * « atelierFloor » RECONSTITUE (OutilFloorAnciennes : sol plat devine,
     * porte devinee) : le floor colle est alors celui reconstitue, pas celui
     * d'origine, et la signature ne peut pas correspondre.
     * Pour un mural, seule compte la hauteur de la case d'ancrage (x, y) de sa
     * position : les muraux ancres sur une case de sol ou de porte peuvent etre
     * a une autre hauteur qu'a l'origine ; ceux ancres sur une case vide
     * (derriere un mur) ne bougent pas si la hauteur des murs est la meme.
     */
    private String comparerDisposition(CopieAppart copie, HPoint coin) {
        CopieAppart.Disposition origine = copie.disposition;
        if (origine == null || salle.planBrut() == null) return null;
        CopieAppart.Disposition ici = CopieAppart.Disposition.depuisPlan(salle.modele(), salle.largeurPlan(),
                salle.longueurPlan(), salle.echelle(), salle.hauteurMurs(), salle.planBrut());
        if (origine.correspond(ici)) return null;
        org.json.JSONObject af = copie.autres == null ? null : copie.autres.optJSONObject("atelierFloor");
        boolean reconstitue = af != null && af.optBoolean("atelierFloorReconstitue");
        List<String> exposes = new ArrayList<>();
        for (CopieAppart.MobiMur m : copie.murs) {
            if (m.position == null) continue;
            int x = m.position.x() + coin.getX(), y = m.position.y() + coin.getY();
            if (!caseVide(salle.caseDuPlan(x, y))) exposes.add(m.classe + "(" + x + "," + y + ")");
        }
        Journal.debug("Pose muraux : salle différente de l'origine (" + origine.json() + " / " + ici.json() + ")"
                + (reconstitue ? " ; le floor de la copie est RECONSTITUÉ (ancien module), le plan d'origine exact est perdu" : "")
                + (exposes.isEmpty() ? " ; aucun mural ancré sur une case de sol"
                   : " ; muraux ancrés sur une case de sol ou de porte (hauteur peut-être différente) : " + exposes));
        if (reconstitue)
            return "Le floor de cette copie a été reconstitué : il n'est pas exactement celui d'origine"
                    + (exposes.isEmpty() ? "." : ", " + PoseOutils.nombre(exposes.size(), "mural peut", "muraux peuvent")
                    + " être à une autre hauteur.");
        return "La salle n'a pas la forme de la salle d'origine" + (origine.modele != null ? " (modèle " + origine.modele + ")" : "")
                + " : des muraux peuvent être décalés.";
    }

    /** Case vide du plan (« x » ou hors du plan) : derriere un mur. Logique pure. */
    static boolean caseVide(char c) { return c == 'x' || c == 'X' || c == 0 || c == ' '; }
}

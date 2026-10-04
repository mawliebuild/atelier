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
 * Pose des muraux d'une copie, sans G-Presets (remplace placeWallItems et
 * applyWallFurniVariables de GPresetImporter, que OngletApparts.poserMuraux
 * pilote aujourd'hui par « :ip » sans mobi de sol).
 *
 * Pour chaque mural, comme l'importeur :
 *   1. position absolue = position de la copie + coin (PositionMur), envoyee
 *      sans l'altitude ; source inventaire ou BC (affiches : l'inventaire de
 *      meme etat ; BC : la variante exacte du catalogue, sinon l'offre de la
 *      furnidata) ; si le jeu refuse la case (rien n'apparait) et qu'on a des
 *      droits, nouvel essai sur une case sure (celle d'un mural deja la) ;
 *   2. correction : l'altitude par la variable « -123 » (droits wired ou de
 *      deplacement), sinon MoveWallItem ;
 *   3. verification (wallValidate) : jusqu'a 4 passes ; chaque mural est relu
 *      dans l'etat de la salle et par WiredGetVariablesForObject (« -123 »
 *      altitude, « -190 » decalage X) ; altitude fausse -> « -123 » ;
 *      en plus de l'importeur : case ou decalage faux -> MoveWallItem quand on
 *      peut deplacer (l'importeur ne corrige que l'altitude s'il a les droits
 *      wired), et decalage X lu par « -190 » encore faux -> « -190 » ;
 *   4. les etats (variable « -110 », sinon UseWallItem) ;
 *   5. les valeurs des variables de l'utilisatrice (hors « @ » et « - »).
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
        /** Corrections envoyees (variables ou deplacements). */
        int corrections;
        /** Muraux encore faux apres les verifications. */
        final List<Integer> faux = new ArrayList<>();
        int etatsFaux, variablesEnvoyees;
        /** La salle n'a pas la meme forme que la salle d'origine (les muraux peuvent etre decales). */
        String avertissement;
        boolean arrete;
        String erreur;

        String texte() {
            if (erreur != null) return "Pose des muraux impossible : " + erreur + ".";
            StringBuilder b = new StringBuilder(PoseOutils.nombre(ids.size(), "mural posé", "muraux posés"));
            if (!manquants.isEmpty()) b.append(", ").append(PoseOutils.nombre(manquants.size(), "manquant", "manquants"));
            if (!faux.isEmpty()) b.append(", ").append(PoseOutils.nombre(faux.size(), "mal placé", "mal placés"));
            if (etatsFaux > 0) b.append(", ").append(PoseOutils.nombre(etatsFaux, "état faux", "états faux"));
            if (arrete) b.append(" (arrêtée)");
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
        b.avertissement = comparerDisposition(copie.disposition);

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
            CatalogueBc.Produit produit = catalogue != null && catalogue.pret() ? catalogue.produitMur(type, etat) : null;
            int offre = produit != null ? produit.offerId() : -1, page = produit != null ? produit.pageId() : -1;
            Furnidata.Mobi fd = furnidata.mur(m.classe);
            if (offre == -1 && fd != null && fd.isBC) { offre = fd.offerId; page = -1; }
            boolean parBc = switch (source) {
                case BC -> true;
                case BC_PUIS_INVENTAIRE -> produit != null || (inv.isEmpty() && offre != -1);
                case INVENTAIRE_PUIS_BC -> inv.isEmpty() && offre != -1;
                case INVENTAIRE -> false;
            };

            Set<Integer> avant = new HashSet<>(PoseOutils.idsMuraux(salle));
            String sure = null;
            for (HWallItem w : salle.getWallItems()) { sure = w.getLocation(); break; }
            if (sure == null) sure = ":w=0,0 l=0,0 l";

            boolean surCaseSure = false;
            Integer nouveau;
            if (parBc) {
                if (offre == -1) { b.manquants.add(m.classe + " (absent du catalogue BC)"); continue; }
                PoseOutils.envoyer(canal, PoseOutils.poseMurBc(page, offre, etat, ou));
                nouveau = attendreNouveau(avant, type, stop);
                if (nouveau == null && (variables || deplacer)) {
                    PoseOutils.envoyer(canal, PoseOutils.poseMurBc(page, offre, etat, sure));
                    surCaseSure = true;
                    nouveau = attendreNouveau(avant, type, stop);
                }
            } else {
                HInventoryItem it = inv.pollFirst();
                if (it == null) { b.manquants.add(m.classe + (etat.isEmpty() ? "" : " (état " + etat + ")") + " (absent de l'inventaire)"); continue; }
                PoseOutils.envoyer(canal, PoseOutils.poseMurInventaire(it.getId(), ou));
                nouveau = attendreNouveau(avant, type, stop);
                if (nouveau == null && (variables || deplacer)) {
                    PoseOutils.envoyer(canal, PoseOutils.poseMurInventaire(it.getId(), sure));
                    surCaseSure = true;
                    nouveau = attendreNouveau(avant, type, stop);
                }
            }
            if (nouveau == null) { b.manquants.add(m.classe + " (refusé par le jeu)"); continue; }
            int id = nouveau;
            b.ids.put(m.id, id);
            places.add(new Place(id, voulue));
            HWallItem w = salle.wallItemFromId(id);
            if (!affiche && !etat.isEmpty() && w != null && !etat.equals(w.getState()))
                try { etats.add(new int[]{id, Integer.parseInt(etat)}); } catch (NumberFormatException ignored) { }

            // correction immediate (placeWallItems)
            PositionMur lue = w == null ? null : PositionMur.lireOuNull(w.getLocation());
            boolean ecart = surCaseSure || lue == null || lue.x() != voulue.x() || lue.y() != voulue.y()
                    || lue.altitude() != voulue.altitude() || lue.decalageY() != voulue.decalageY();
            if (!ecart) continue;
            if (variables) {
                b.corrections++;
                if (!PoseOutils.dormir(300, stop)) break;
                if (surCaseSure && deplacer) PoseOutils.envoyer(canal, PoseOutils.deplacementMur(id, ou));
                if (!surCaseSure && lue != null && lue.altitude() == voulue.altitude()) continue;
                PoseOutils.envoyerVariable(canal, PoseOutils.variableInterneMural(id, "-123", voulue.altitude()));
            } else if (deplacer) {
                b.corrections++;
                PoseOutils.envoyer(canal, PoseOutils.deplacementMur(id, ou));
            }
        }

        if (!places.isEmpty() && !stop.getAsBoolean()) verifier(places, variables, deplacer, b, stop);
        if (!etats.isEmpty() && !stop.getAsBoolean()) etats(etats, variables, b, stop);
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
    private Integer attendreNouveau(Set<Integer> avant, int type, BooleanSupplier stop) {
        Integer[] r = {null};
        PoseOutils.attendre(() -> {
            for (HWallItem w : salle.getWallItems())
                if (!avant.contains(w.getId()) && w.getTypeId() == type) { r[0] = w.getId(); return true; }
            return false;
        }, 2000, stop);
        return r[0];
    }

    /** wallValidate : jusqu'a 4 passes, relecture des positions et des variables -123 / -190. */
    private void verifier(List<Place> places, boolean variables, boolean deplacer, Bilan b, BooleanSupplier stop) {
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
                if (lue == null) { Journal.debug("Pose muraux : position illisible " + w.getLocation()); continue; }
                Integer alt = null, decX = null;
                if (variables) {
                    Map<String, Integer> v = inspecter(p.id, INSPECTION_MS);
                    if (v != null) { alt = v.get("-123"); decX = v.get("-190"); }
                }
                int dx = decX != null ? decX : lue.decalageX();
                PositionMur voulue = p.voulue;
                boolean caseFausse = lue.x() != voulue.x() || lue.y() != voulue.y() || lue.decalageY() != voulue.decalageY()
                        || lue.cote() != voulue.cote();
                boolean decalageFaux = dx != voulue.decalageX();
                boolean altitudeFausse = alt != null && alt != voulue.altitude();
                if (!caseFausse && !decalageFaux && !altitudeFausse) continue;
                Journal.debug("Pose muraux : passe " + passe + ", " + p.id + " attendu " + voulue.complet() + ", lu "
                        + w.getLocation() + " alt=" + alt + " -190=" + decX);
                if (variables) {
                    corrections++;
                    if ((caseFausse || (decalageFaux && decX == null)) && deplacer)
                        PoseOutils.envoyer(canal, PoseOutils.deplacementMur(p.id, voulue.toString()));
                    else if (decalageFaux && decX != null)
                        PoseOutils.envoyerVariable(canal, PoseOutils.variableInterneMural(p.id, "-190", voulue.decalageX()));
                    if (altitudeFausse)
                        PoseOutils.envoyerVariable(canal, PoseOutils.variableInterneMural(p.id, "-123", voulue.altitude()));
                    encore.add(p);
                } else if (deplacer) {
                    corrections++;
                    PoseOutils.envoyer(canal, PoseOutils.deplacementMur(p.id, voulue.toString()));
                    encore.add(p);
                }
            }
            b.corrections += corrections;
            if (corrections == 0) { aVoir = encore; break; }
            if (passe < 4 && !encore.isEmpty() && !PoseOutils.dormir(PAUSES_VERIFICATION[passe - 1], stop)) break;
            aVoir = encore;
        }
        for (Place p : aVoir) b.faux.add(p.id);
    }

    /** Les etats des muraux (variable -110, sinon UseWallItem en boucle). */
    private void etats(List<int[]> etats, boolean variables, Bilan b, BooleanSupplier stop) {
        for (int[] e : etats) {
            if (stop.getAsBoolean()) break;
            int id = e[0];
            String voulu = String.valueOf(e[1]);
            if (salle.wallItemFromId(id) == null) continue;
            if (variables) {
                PoseOutils.envoyerVariable(canal, PoseOutils.variableInterneMural(id, "-110", e[1]));
                PoseOutils.attendre(() -> { HWallItem w = salle.wallItemFromId(id); return w == null || voulu.equals(w.getState()); }, 700, stop);
            } else {
                for (int i = 0; i < 20 && !stop.getAsBoolean(); i++) {
                    HWallItem w = salle.wallItemFromId(id);
                    if (w == null || voulu.equals(w.getState())) break;
                    String avant = w.getState();
                    PoseOutils.envoyer(canal, PoseOutils.utilisationMural(id));
                    PoseOutils.attendre(() -> { HWallItem x = salle.wallItemFromId(id); return x == null || !String.valueOf(avant).equals(x.getState()); }, 700, stop);
                }
            }
            HWallItem w = salle.wallItemFromId(id);
            if (w != null && !voulu.equals(w.getState())) b.etatsFaux++;
        }
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

    /** null si la salle a la forme de la salle d'origine (ou si on ne sait pas), sinon un avertissement. */
    private String comparerDisposition(CopieAppart.Disposition origine) {
        if (origine == null || salle.planBrut() == null) return null;
        CopieAppart.Disposition ici = CopieAppart.Disposition.depuisPlan(salle.modele(), salle.largeurPlan(),
                salle.longueurPlan(), salle.echelle(), salle.hauteurMurs(), salle.planBrut());
        if (origine.correspond(ici)) return null;
        Journal.debug("Pose muraux : salle différente de l'origine (" + origine.json() + " / " + ici.json() + ")");
        return "La salle n'a pas la forme de la salle d'origine" + (origine.modele != null ? " (modèle " + origine.modele + ")" : "")
                + " : des muraux peuvent être décalés.";
    }
}

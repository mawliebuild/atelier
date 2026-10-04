package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'inventaire de mobis. Remplace Inventory (ancien module), en ecoute seule : la demande
 * au serveur n'est pas faite ici (elle passera par ChargementAuto, qui saura
 * ne pas renvoyer la reponse au jeu), et aucun FurniList n'est bloque.
 *
 * Paquets (TOCLIENT) :
 *   FurniList             int total, int index, int n, n x HInventoryItem (par morceaux)
 *   FurniListAddOrUpdate  int n, n x HInventoryItem
 *   FurniListRemove       int id de placement
 *   FurniListInvalidate   l'inventaire du serveur a change (on le note seulement)
 *
 * Etats : memes noms que l'original (UNAVAILABLE, LOADING, LOADED), compares
 * aujourd'hui par leur texte. Lectures depuis tous les fils : verrou unique,
 * listes rendues en copie.
 */
final class Inventaire {

    enum Etat { UNAVAILABLE, LOADING, LOADED }

    private final Object verrou = new Object();
    private volatile Etat etat = Etat.UNAVAILABLE;
    private volatile boolean perime;
    private List<HInventoryItem> morceaux;                               // chargement en cours
    private final Map<Integer, HInventoryItem> parPlacement = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, HInventoryItem>> solsParType = new HashMap<>();
    private final Map<Integer, Map<Integer, HInventoryItem>> mursParType = new HashMap<>();

    Inventaire(Canal canal) {
        HMessage.Direction C = HMessage.Direction.TOCLIENT;
        canal.intercept(C, "FurniList", this::surListe);
        canal.intercept(C, "FurniListAddOrUpdate", this::surAjouts);
        canal.intercept(C, "FurniListRemove", m -> retirer(m.getPacket().readInteger()));
        canal.intercept(C, "FurniListInvalidate", m -> perime = true);
    }

    // ================================================================ ecouteurs

    private void surListe(HMessage m) {
        HPacket p = m.getPacket();
        int total = p.readInteger();
        int index = p.readInteger();
        HInventoryItem[] lus = HInventoryItem.parse(p);      // repart de l'octet 14 (apres total et index)
        synchronized (verrou) {
            if (index == 0) {
                viderSansVerrou();
                morceaux = new ArrayList<>((int) Math.min(100_000L, Math.max(16L, (long) lus.length * Math.max(1, total))));
                etat = Etat.LOADING;
            }
            // Morceau d'une serie commencee avant l'ecoute : on attend la suivante.
            if (morceaux == null) return;
            Collections.addAll(morceaux, lus);
            if (index == total - 1) {
                etat = Etat.LOADED;                     // avant l'ajout : ajouter() l'exige « disponible »
                for (HInventoryItem it : morceaux) ajouter(it);
                morceaux = null;
                perime = false;
                Journal.debug("Inventaire : " + parPlacement.size() + (parPlacement.size() > 1 ? " mobis" : " mobi")
                        + " en " + total + (total > 1 ? " morceaux." : " morceau."));
            }
        }
    }

    private void surAjouts(HMessage m) {
        HPacket p = m.getPacket();
        int n = p.readInteger();
        List<HInventoryItem> lus = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lus.add(new HInventoryItem(p));
        synchronized (verrou) {
            if (etat == Etat.UNAVAILABLE) return;               // comme l'original
            for (HInventoryItem it : lus) ajouter(it);
        }
    }

    private void retirer(int placement) {
        synchronized (verrou) {
            if (etat != Etat.LOADED) return;
            HInventoryItem it = parPlacement.remove(placement);
            if (it == null) return;
            Map<Integer, HInventoryItem> t = (it.getType() == HProductType.FloorItem ? solsParType : mursParType)
                    .get(it.getTypeId());
            if (t != null) t.remove(it.getId());
        }
    }

    /** Sous verrou. */
    private void ajouter(HInventoryItem it) {
        parPlacement.put(it.getPlacementId(), it);
        (it.getType() == HProductType.FloorItem ? solsParType : mursParType)
                .computeIfAbsent(it.getTypeId(), k -> new LinkedHashMap<>()).put(it.getId(), it);
    }

    private void viderSansVerrou() {
        morceaux = null;
        parPlacement.clear();
        solsParType.clear();
        mursParType.clear();
        etat = Etat.UNAVAILABLE;
    }

    // ================================================================ API francaise

    Etat etat() { return etat; }

    boolean charge() { return etat == Etat.LOADED; }

    /** Vrai si le serveur a signale un changement (FurniListInvalidate) depuis le dernier chargement. */
    boolean perime() { return perime; }

    int nombre() { synchronized (verrou) { return parPlacement.size(); } }

    List<HInventoryItem> mobis() { synchronized (verrou) { return new ArrayList<>(parPlacement.values()); } }

    List<HInventoryItem> solsDeType(int type) { return copie(solsParType, type); }

    List<HInventoryItem> mursDeType(int type) { return copie(mursParType, type); }

    /** Nombre de mobis de sol de chaque type (type -> nombre). */
    Map<Integer, Integer> compteSols() { return comptes(solsParType); }

    /** Nombre de muraux de chaque type (type -> nombre). */
    Map<Integer, Integer> compteMurs() { return comptes(mursParType); }

    void vider() { synchronized (verrou) { viderSansVerrou(); } }

    private List<HInventoryItem> copie(Map<Integer, Map<Integer, HInventoryItem>> m, int type) {
        synchronized (verrou) {
            Map<Integer, HInventoryItem> t = m.get(type);
            return t == null ? Collections.emptyList() : new ArrayList<>(t.values());
        }
    }

    private Map<Integer, Integer> comptes(Map<Integer, Map<Integer, HInventoryItem>> m) {
        synchronized (verrou) {
            Map<Integer, Integer> r = new HashMap<>();
            m.forEach((k, v) -> { if (!v.isEmpty()) r.put(k, v.size()); });
            return r;
        }
    }

    // ================================================================ noms d'origine (Inventory)

    Etat getState() { return etat; }

    List<HInventoryItem> getInventoryItems() { return mobis(); }

    List<HInventoryItem> getFloorItemsByType(int type) { return solsDeType(type); }

    List<HInventoryItem> getWallItemsByType(int type) { return mursDeType(type); }

    void clear() { vider(); }
}

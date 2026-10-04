package atelier;

import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Le chargement des prix, en tache de fond, sans attendre que la fenetre
 * Patrimoine soit ouverte.
 *
 *   1. habbofurni.xyz (PrixSite) : le fichier d'abord (immediat), puis le
 *      site s'il a plus de 24 h ;
 *   2. pour les mobis absents du site, la place du marche du jeu (Marche) :
 *      un mobi a la fois, au moins 0,5 s entre deux demandes, seulement
 *      quand l'inventaire est recu (donc connectee), gardes 24 h.
 *
 * Un fil surveille l'inventaire et les apparts (toutes les 3 s) : un mobi
 * nouveau relance l'etape 2 pour lui seul. Les ecouteurs (le tableau) sont
 * prevenus hors du fil FX ; a eux de regrouper les mises a jour.
 *
 * Messages : rien dans le jeu pour les passages automatiques (console en
 * debug) ; « Actualiser les prix » dit son resultat une fois (Journal).
 */
final class PrixChargement {

    private PrixChargement() { }

    private static volatile boolean demarre = false;
    private static volatile boolean jeuEnCours = false, stopJeu = false;
    private static volatile int jeuFait = 0, jeuTotal = 0;
    private static volatile long jeuFiniLe = 0;
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    /** Le jeu ne repond plus : erreur dite une fois par session. */
    private static volatile boolean jeuMuetDit = false;
    /** Aucun prix du site (ni lu ni en fichier) : erreur dite une fois par session. */
    private static volatile boolean siteMuetDit = false;
    /** « Arreter » : pas de relance automatique pendant 10 min. */
    private static volatile long arretLe = 0;

    /** Ou en est le chargement, pour la barre de progression. */
    static final class Etat {
        final boolean enCours;
        /** « Prix du site » (habbofurni.xyz) ou « Prix du jeu » (marche) ; fait / total (total 0 : pas encore connu). */
        final String source;
        final int fait, total;
        final String unite;
        Etat(boolean enCours, String source, int fait, int total, String unite) {
            this.enCours = enCours; this.source = source; this.fait = fait; this.total = total; this.unite = unite;
        }
    }

    static Etat etat() {
        if (PrixSite.enLecture()) {
            int t = PrixSite.pagesTotal();
            return new Etat(true, t == 0 ? "Chargement des prix" : "Prix du site", PrixSite.pagesFaites(), t, "pages");
        }
        if (jeuEnCours) return new Etat(true, "Prix du jeu", jeuFait, jeuTotal, "mobis");
        return new Etat(false, "", 0, 0, "");
    }

    /** Date du dernier rafraichissement des prix (0 : jamais). */
    static long derniereMaj() { return PrixSite.misAJour(); }

    /** Ajoute un ecouteur (appele hors fil FX, souvent : a regrouper). */
    static void ecouter(Runnable r) { if (r != null) ecouteurs.add(r); }

    static void notifier() {
        for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable t) { Journal.debug("prix : écouteur : " + t); }
    }

    /** Une seule fois, n'importe quel fil : a appeler au demarrage de l'Atelier. */
    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        PrixSite.surMaj(() -> {
            notifier();
            if (!PrixSite.fini() || PrixSite.enLecture()) return;
            // Rien du tout (ni lu, ni en fichier) : c'est un vrai manque, dit une fois.
            if (PrixSite.dernierEchec() != null && PrixSite.nombre() == 0 && !siteMuetDit
                    && !"lecture arrêtée".equals(PrixSite.dernierEchec())) {
                siteMuetDit = true;
                Journal.debug("Les prix de habbofurni.xyz n'ont pas pu être lus (" + PrixSite.dernierEchec()
                        + ") : prix du marché du jeu seulement. « Actualiser les prix » pour réessayer.");
            }
            // prix du site connus : on complete par le marche du jeu
            lancerJeu(false, null);
        });
        PrixPerso.surMaj(PrixChargement::notifier);
        Patrimoine.surMaj(() -> { notifier(); lancerJeu(false, null); });
        Patrimoine.demarrer();
        PrixSite.demarrer();
        Thread t = new Thread(PrixChargement::surveiller, "atelier-prix-suivi");
        t.setDaemon(true);
        t.start();
    }

    /** L'inventaire change (nouvelle liste) : tableau a refaire, prix manquants a demander. */
    private static void surveiller() {
        List<HInventoryItem> vu = null;
        while (true) {
            try {
                List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
                if (inv != null && inv != vu) {
                    vu = inv;
                    notifier();
                    lancerJeu(false, null);
                }
            } catch (Throwable t) { Journal.debug("prix : suivi : " + t); }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * « Actualiser les prix » : relit le site puis redemande au jeu les prix
     * manquants, meme recents. Le resultat est dit une fois a la fin.
     */
    static void actualiser() {
        demarrer();
        stopJeu = false;
        arretLe = 0;
        boolean lance = PrixSite.actualiser(probleme -> {
            notifier();
            if ("lecture arrêtée".equals(probleme)) return;
            if (probleme != null) {
                long d = PrixSite.misAJour();
                Journal.debug("Le site habbofurni.xyz n'a pas pu être relu (" + probleme + ")"
                        + (d > 0 ? " : prix gardés du " + PrixTexte.date(d) + "." : "."));
            }
            lancerJeu(true, nJeu -> {
                if (probleme == null)
                    Journal.succes("Prix mis à jour : " + PrixTexte.nombre(PrixSite.nombre()) + " mobis sur habbofurni.xyz"
                            + (nJeu > 0 ? ", " + PrixTexte.nombre(nJeu) + " au marché du jeu." : "."));
            });
        });
        if (!lance) Journal.debug("prix : lecture déjà en cours.");
        notifier();
    }

    /** Arrete ce qui est en cours (site et jeu) ; le deja-lu est garde. */
    static void arreter() {
        arretLe = System.currentTimeMillis();
        PrixSite.arreter();
        stopJeu = true;
    }

    // ------------------------------------------------------------ marche du jeu

    /** Les mobis vendables de l'inventaire et des apparts (cles Marche). */
    static Set<String> clesVendables(List<HInventoryItem> inv) {
        Map<String, PrixCalcul.Compte> g = new LinkedHashMap<>();
        if (inv != null) for (HInventoryItem it : inv) {
            boolean mur = it.getType() == HProductType.WallItem;
            if (!mur && it.getType() != HProductType.FloorItem) continue;
            PrixCalcul.compter(g, mur, it.getTypeId(), it.isSellable() && it.getSecondsToExpiration() <= 0);
        }
        Set<String> r = new LinkedHashSet<>();
        for (Map.Entry<String, PrixCalcul.Compte> e : g.entrySet()) if (e.getValue().vendable) r.add(e.getKey());
        for (Patrimoine.Appart a : Patrimoine.apparts()) r.addAll(a.mobis.keySet());
        return r;
    }

    /**
     * Demande au jeu les prix des mobis absents de habbofurni (et pas deja
     * connus depuis moins de 24 h, sauf forcer). fin(n) : nombre de prix recus,
     * appele meme s'il n'y avait rien a demander.
     */
    static synchronized void lancerJeu(boolean forcer, java.util.function.IntConsumer fin) {
        if (jeuEnCours) { if (fin != null) fin.accept(0); return; }
        if (!forcer && !PrixSite.fini()) return;
        if (!forcer && System.currentTimeMillis() - arretLe < 10 * 60_000L) return;   // tu as dit « Arrêter »
        Moteur gp = AtelierLauncher.moteur();
        List<HInventoryItem> inv = OngletInventaire.dernierInventaire();
        // Pas d'inventaire : pas connectee (ou pas encore) ; le jeu ne repondrait pas.
        if (gp == null || inv == null) { if (fin != null) fin.accept(0); return; }
        List<String> aDemander = new ArrayList<>();
        for (String k : clesVendables(inv)) {
            boolean mur = k.startsWith("2:");
            int typeId;
            try { typeId = Integer.parseInt(k.substring(2)); } catch (NumberFormatException e) { continue; }
            if (PrixSite.prix(PrixTexte.classe(gp, mur, typeId)) != null) continue;
            if (PrixPerso.prix(mur, typeId) != null) continue;
            if (Marche.sansReponseRecente(mur, typeId)) continue;
            if (forcer || !Marche.aJour(mur, typeId)) aDemander.add(k);
        }
        if (aDemander.isEmpty()) { if (fin != null) fin.accept(0); return; }
        jeuEnCours = true;
        stopJeu = false;
        jeuFait = 0;
        jeuTotal = aDemander.size();
        notifier();
        Thread t = new Thread(() -> {
            int recus = 0, muets = 0, suite = 0;
            long t0 = System.currentTimeMillis();
            try {
                for (String k : aDemander) {
                    if (stopJeu) break;
                    boolean mur = k.startsWith("2:");
                    int typeId = Integer.parseInt(k.substring(2));
                    try {
                        if (Marche.demander(gp, mur, typeId) == null) { muets++; suite++; }
                        else { recus++; suite = 0; }
                    } catch (InterruptedException e) { break; }
                    catch (Throwable e) { muets++; suite++; Journal.debug("prix du jeu : " + e); }
                    jeuFait++;
                    if (jeuFait % 10 == 0) notifier();
                    if (jeuFait % 25 == 0) Marche.sauver();
                    // 5 sans reponse de suite : le jeu ne repond pas (deconnectee ?)
                    if (suite >= 5) {
                        if (!jeuMuetDit) {
                            jeuMuetDit = true;
                            Journal.debug("Le marché du jeu ne répond pas : prix manquants redemandés plus tard.");   // chargement de fond : pas de message dans le jeu
                        }
                        break;
                    }
                }
            } finally {
                Marche.sauver();
                jeuEnCours = false;
                jeuFiniLe = System.currentTimeMillis();
                Journal.debug("prix du marché du jeu : " + recus + " reçus, " + muets + " sans réponse, sur "
                        + aDemander.size() + " en " + ((jeuFiniLe - t0) / 1000) + " s.");
                notifier();
                if (fin != null) try { fin.accept(recus); } catch (Throwable ignored) { }
            }
        }, "atelier-prix-jeu");
        t.setDaemon(true);
        t.start();
    }
}

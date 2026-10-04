package atelier;

import gearth.extensions.parsers.HPoint;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * La pose d'une copie avec la dalle magique, par les briques du moteur :
 *   1. les mobis de sol a leur case et a leur hauteur exactes (PoseDalle) ;
 *   2. les fonds des mobis publicitaires, puis les reglages des wired et leurs
 *      liaisons (ReglageWiredPose) ;
 *   3. les valeurs des variables portees par les mobis de sol ;
 *   4. les muraux, leur altitude, leur etat et leurs variables (PoseMuraux).
 * Remplace le moteur de pose d'avant (« :ip ») pour Escalier, Remplir, Miroir,
 * Dupliquer, Coller un appart, Coller des wired et la reprise a la dalle.
 *
 * Les positions de la copie sont relatives au coin. Les hauteurs suivent la
 * meme regle qu'avant : avec une hauteur d'ancrage (« srcAnchorFloorHeight »),
 * z est decale du sol le plus bas sous la copie dans la salle moins cette
 * ancre ; une ancre 0 fait donc de z une altitude au-dessus de ce sol. Sans
 * ancre, z est absolu.
 *
 * Synchrone (hors fil JavaFX) ; une seule pose a la fois. Arret par stop, par
 * arreter() ou « :abort » dans le chat (Moteur), ou si l'on quitte la salle.
 * Pendant la pose, les va-et-vient des dalles magiques ne vont pas dans
 * l'historique (Ctrl+Z).
 */
final class PoseCopie {

    private PoseCopie() { }

    /** Ce que rend la pose. */
    static final class Resultat {
        /** La pose a commence (sinon raison dit pourquoi). */
        boolean lancee;
        String raison;
        PoseDalle.Bilan sols;
        ReglageWiredPose.Bilan wired;
        PoseMuraux.Bilan murs;
        /** Id dans la copie -> id reel (sols et muraux). */
        final Map<Integer, Integer> ids = new LinkedHashMap<>();
        /** Valeurs de variables envoyees aux mobis de sol. */
        int variables;
        int voulus;
        boolean arrete;

        int obtenus() { return (sols == null ? 0 : sols.ids.size()) + (murs == null ? 0 : murs.ids.size()); }

        /** Le bilan en une phrase, sans « (s) ». */
        String texte() {
            if (!lancee) return "Pose impossible : " + (raison == null ? "raison inconnue" : raison) + ".";
            StringBuilder b = new StringBuilder();
            if (arrete) b.append("Arrêtée. ");
            int n = obtenus();
            b.append(n == voulus ? String.valueOf(n) : n + "/" + voulus)
                    .append(voulus > 1 ? " mobis posés" : " mobi posé").append(" avec la dalle magique");
            if (sols != null && !sols.hauteursFausses.isEmpty())
                b.append(", ").append(PoseOutils.nombre(sols.hauteursFausses.size(), "hauteur fausse", "hauteurs fausses"));
            int etats = (sols == null ? 0 : sols.etatsFaux.size()) + (murs == null ? 0 : murs.etatsFaux);
            if (etats > 0) b.append(", ").append(PoseOutils.nombre(etats, "état faux", "états faux"));
            if (sols != null && sols.dallePosee && !sols.dalleRamassee)
                b.append(", la dalle magique est restée dans la salle (ramasse-la à la main)");
            if (wired != null && wired.attendus > 0) {
                b.append(" ; ").append(PoseOutils.nombre(wired.confirmes.size(), "wired réglé", "wired réglés"))
                        .append(" sur ").append(wired.attendus);
                if (!wired.rates.isEmpty())
                    b.append(" (").append(PoseOutils.nombre(wired.rates.size(), "raté", "ratés")).append(")");
            }
            if (murs != null && murs.avertissement != null) b.append(". ").append(murs.avertissement);
            String t = b.toString().trim();
            return t.endsWith(".") ? t : t + ".";
        }
    }

    private static final AtomicBoolean OCCUPEE = new AtomicBoolean(false);
    private static volatile boolean arret = false;

    /** Une pose est en cours. */
    static boolean occupee() { return OCCUPEE.get(); }

    /** Arrete la pose en cours (ex-« :abort ») : plus rien ne part, la dalle est rangee. */
    static void arreter() { if (OCCUPEE.get()) arret = true; }

    /**
     * Prend la main pour un travail qui pose ou regle hors de poser() (reglages
     * des wired apres une pose rapide) : false si une pose est deja en cours.
     * A rendre par rendre().
     */
    static boolean prendre() {
        if (!OCCUPEE.compareAndSet(false, true)) return false;
        arret = false;
        return true;
    }

    static void rendre() {
        arret = false;
        OCCUPEE.set(false);
    }

    /** Arret demande (arreter, « :abort ») pendant un travail pris par prendre(). */
    static boolean arretDemande() { return arret; }

    static PoseOutils.Source source(Generateur.Source s) {
        return s == null ? PoseOutils.Source.INVENTAIRE : PoseOutils.Source.valueOf(s.name());
    }

    /**
     * Pose la copie (synchrone).
     *
     * @param coin      coin de la copie dans la salle
     * @param caseDalle case ou poser une dalle magique s'il en faut une ; null = case libre cherchee
     * @param stop      arret demande par l'appelant (peut etre null)
     * @param suivi     appele toutes les 400 ms pendant la pose, sur un autre fil (peut etre null)
     */
    static Resultat poser(CopieAppart copie, HPoint coin, Generateur.Source source, HPoint caseDalle,
                          Consumer<String> dire, BooleanSupplier stop, Runnable suivi) {
        Resultat r = new Resultat();
        if (dire == null) dire = m -> { };
        Moteur m = Salle.gp();
        if (copie == null || coin == null) { r.raison = "rien à poser"; return r; }
        r.voulus = copie.sols.size() + copie.murs.size();
        if (m == null || m.canal() == null) { r.raison = "l'Atelier n'est pas encore prêt"; return r; }
        if (!Salle.dansUneSalle()) { r.raison = "tu n'es pas dans une salle"; return r; }
        if (!Salle.furnidataPrete()) { r.raison = "furnidata pas encore chargée"; return r; }
        if (r.voulus == 0) { r.raison = "rien à poser"; return r; }
        if (!OCCUPEE.compareAndSet(false, true)) { r.raison = "une autre pose n'est pas finie"; return r; }

        arret = false;
        r.lancee = true;
        int salle0 = Salle.salleId();
        BooleanSupplier fin = () -> arret || (stop != null && stop.getAsBoolean()) || Salle.salleId() != salle0;
        for (int type : Generateur.Dalle.typesDalles()) Historique.ignorerType(type, 30 * 60_000L);
        Thread fil = null;
        if (suivi != null) {
            fil = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { suivi.run(); } catch (Throwable ignored) { }
                    try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                }
            }, "atelier-pose-suivi");
            fil.setDaemon(true);
            fil.start();
        }
        try {
            poser0(m, copie, coin, source(source), caseDalle, dire, fin, r);
        } catch (Throwable t) {
            Journal.debug("pose de la copie : " + t);
            if (r.raison == null) r.raison = "erreur (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (fil != null) fil.interrupt();
            r.arrete = arret || (stop != null && stop.getAsBoolean());
            Salle.sommeil(300);
            for (int type : Generateur.Dalle.typesDalles()) Historique.ignorerType(type, 2500);
            arret = false;
            OCCUPEE.set(false);
        }
        if (suivi != null) try { suivi.run(); } catch (Throwable ignored) { }
        Journal.debug("pose de la copie : " + r.texte());
        return r;
    }

    private static void poser0(Moteur m, CopieAppart c, HPoint coin, PoseOutils.Source src, HPoint caseDalle,
                               Consumer<String> dire, BooleanSupplier fin, Resultat r) {
        EtatSalle salle = m.getFloorState();
        Map<String, String> tableVariables = new HashMap<>();
        PoseDalle pd = m.poseDalle();
        if (pd == null) { r.raison = "furnidata pas encore chargée"; return; }

        // 1. les sols, hauteurs decalees comme avant (sol le plus bas sous la copie - ancre)
        if (!c.sols.isEmpty()) {
            double delta = decalage(c, coin, salle);
            List<PoseDalle.Mobi> l = new java.util.ArrayList<>();
            for (CopieAppart.MobiSol s : c.sols)
                l.add(new PoseDalle.Mobi(s.id, s.classe, coin.getX() + s.x, coin.getY() + s.y,
                        Generateur.arrondi(s.z + delta), s.rotation & 7, s.etat));
            dire.accept(PoseOutils.nombre(l.size(), "mobi part", "mobis partent") + " avec la dalle magique…");
            r.sols = pd.poser(l, src, caseDalle, fin);
            r.ids.putAll(r.sols.ids);
            if (r.sols.erreur != null && r.sols.ids.isEmpty()) { r.raison = r.sols.erreur; r.lancee = false; return; }
        }
        if (fin.getAsBoolean()) return;

        // 2. fonds des publicites, puis les wired
        ReglageWiredPose rw = m.reglageWired();
        if (rw != null && !c.fonds.isEmpty()) rw.appliquerFonds(c, r.ids, fin);
        if (rw != null && !c.tousWired().isEmpty() && !fin.getAsBoolean()) {
            dire.accept("Réglages des wired…");
            PoseDalle.Dalle d = pd.dalleDeLaSalle(1);
            ReglageWiredPose.Deplaceur dep = d == null ? null : (id, x, y, rot, z) -> {
                if (z == null) PoseOutils.envoyer(m.canal(), PoseOutils.deplacementSol(id, x, y, rot));
                else pd.deplacerSurDalle(d, id, x, y, rot, z);
            };
            try {
                r.wired = rw.appliquer(c, r.ids, tableVariables, coin, dep, fin);
            } finally {
                if (d != null) pd.ranger(d, null);
            }
        }

        // 3. valeurs des variables des mobis de sol (hors « @ » et « - »)
        if (!fin.getAsBoolean()) r.variables = variablesSols(m, c, r.ids, tableVariables, fin);

        // 4. les muraux
        if (!c.murs.isEmpty() && !fin.getAsBoolean()) {
            PoseMuraux pm = m.poseMuraux();
            if (pm != null) {
                dire.accept("Pose des muraux…");
                r.murs = pm.poser(c, coin, src, tableVariables, fin);
                r.ids.putAll(r.murs.ids);
            }
        }
    }

    /**
     * Le decalage des hauteurs (computeImportZDelta) : sol le plus bas du
     * rectangle [coin, coin + dimensions] moins l'ancre ; 0 sans ancre ou
     * hors du plan.
     */
    static double decalage(CopieAppart c, HPoint coin, EtatSalle salle) {
        if (c.ancre == null || c.sols.isEmpty() || salle == null) return 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (CopieAppart.MobiSol s : c.sols) {
            minX = Math.min(minX, s.x); minY = Math.min(minY, s.y);
            maxX = Math.max(maxX, s.x); maxY = Math.max(maxY, s.y);
        }
        int dimX = maxX - minX + 1, dimY = maxY - minY + 1;
        int bas = PoseOutils.solLePlusBas(salle, coin.getX(), coin.getY(), coin.getX() + dimX, coin.getY() + dimY);
        if (bas < 0 || bas >= 256) return 0;
        return bas - c.ancre;
    }

    /** Les valeurs des variables de l'utilisatrice portees par les mobis de sol ; rend le nombre envoye. */
    static int variablesSols(Moteur m, CopieAppart c, Map<Integer, Integer> ids, Map<String, String> table,
                                     BooleanSupplier fin) {
        Droits d = m.getPermissions();
        if (d == null || !(d.peutRegler() || d.peutDeplacer())) return 0;
        int n = 0;
        for (CopieAppart.MobiSol s : c.sols) {
            if (s.variables == null || s.variables.isEmpty()) continue;
            Integer reel = ids.get(s.id);
            if (reel == null) continue;
            for (Map.Entry<String, Integer> e : s.variables.entrySet()) {
                if (fin.getAsBoolean()) return n;
                String nom = e.getKey();
                if (nom == null || nom.isEmpty() || e.getValue() == null || nom.startsWith("@") || nom.startsWith("-")) continue;
                String ancien = c.tableVariables.get(nom);
                String id = ancien != null ? table.get(ancien) : null;
                PoseOutils.envoyerVariable(m.canal(), PoseOutils.variableSol(reel, id != null ? id : nom, e.getValue()));
                n++;
            }
        }
        return n;
    }
}

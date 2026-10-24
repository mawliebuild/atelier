package atelier;

import gearth.extensions.parsers.HPoint;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Le choix de la case d'une vitrine, avec son APERÇU dans le jeu : un clic sur
 * une case y montre le mobi (un faux mobi, chez toi seulement, id de la plage
 * des fantomes : jamais pris pour un vrai), un autre clic le deplace. Une
 * petite fenetre : Tourner, Poser, et la croix (annuler la vente).
 *
 * Le proxy ne voit pas la souris survoler les cases (seulement les clics) :
 * l'empreinte noire du client modifie suit la souris, l'aperçu suit le clic.
 *
 * Touches (fenetre ouverte, aussi Habbo devant) : fleches = deplacer l'aperçu
 * d'une case (Maj : 5), Entree = Poser, Echap = annuler.
 *
 * Fil de travail (attend les clics) ; la fenetre est sur le fil JavaFX.
 */
final class TrocApercu {

    private TrocApercu() { }

    /** La case choisie et la rotation ; annule = croix / Echap ; null de choix = pas de clic a temps. */
    record Choix(int x, int y, int rot, boolean annule) { }

    /** Un seul id : le dernier de la plage des fantomes (filtre partout comme fictif). */
    static final int ID = GroupeFantomes.BASE + GroupeFantomes.TAILLE - 1;

    // ================================================================ paquets (logique pure)

    /** ObjectAdd (avec le nom du proprietaire) ou ObjectUpdate du faux mobi. */
    static HPacket paquet(boolean ajout, int type, int x, int y, int rot, double z) {
        HPacket p = new HPacket(ajout ? "ObjectAdd" : "ObjectUpdate", HMessage.Direction.TOCLIENT);
        p.appendInt(ID);
        p.appendInt(type);
        p.appendInt(x);
        p.appendInt(y);
        p.appendInt(rot & 7);
        p.appendString(GrilleCalcul.altitude(Math.max(0, z)));
        p.appendString("0.0");                       // hauteur propre
        p.appendInt(0);                              // extra
        p.appendInt(0);                              // stuffdata legacy
        p.appendString("0");
        p.appendInt(-1);                             // pas d'expiration
        p.appendInt(0);                              // usage
        p.appendInt(0);                              // id du proprietaire
        if (ajout) p.appendString(GroupeFantomes.PROPRIO);
        return p;
    }

    /** La rotation suivante (0, 2, 4, 6). Logique pure. */
    static int tourner(int rot) { return ((rot & 6) + 2) % 8; }

    /** L'emprise l x p d'un mobi a cette rotation (2 et 6 : largeur et profondeur echangees). Logique pure. */
    static int[] emprise(int l, int p, int rot) { return (rot & 2) != 0 ? new int[]{p, l} : new int[]{l, p}; }

    // ================================================================ choix

    /**
     * Fait rendre la main a l'attente du clic. Deux fois (la seconde 200 ms
     * apres) : un bouton presse juste entre deux attentes ne doit pas laisser la
     * suivante courir 2 minutes ; un reveil de trop relance seulement l'attente.
     */
    private static void reveiller() {
        Generateur.Dalle.annulerClic();
        Salle.tache("troc-apercu-reveil", () -> { Salle.sommeil(200); Generateur.Dalle.annulerClic(); });
    }

    private static void envoyer(HPacket p) {
        Moteur gp = Salle.gp();
        try { if (gp != null) gp.sendToClient(p); } catch (Throwable t) { Journal.debug("Troc : aperçu non envoyé (" + t + ")"); }
    }

    /**
     * Attend la case de la vitrine (au plus delaiMs sans action). null : pas de
     * clic a temps (ou plus d'appart). Fil de travail.
     */
    static Choix choisir(int type, String classe, String nom, long delaiMs) {
        Furnidata.Mobi d = Salle.details(classe);
        int l = d == null ? 1 : Math.max(1, d.xDim), pr = d == null ? 1 : Math.max(1, d.yDim);
        int salle = Salle.salleId();
        AtomicReference<String> action = new AtomicReference<>(null);     // "poser", "annuler"
        AtomicReference<int[]> ici = new AtomicReference<>(null);
        AtomicInteger rot = new AtomicInteger(0);
        boolean[] montre = {false};
        Object verrou = new Object();

        // aperçu a jour (ajout ou deplacement) ; fil quelconque
        Runnable maj = () -> {
            int[] c = ici.get();
            if (c == null) return;
            synchronized (verrou) {
                envoyer(paquet(!montre[0], type, c[0], c[1], rot.get(), Salle.hauteurSol(c[0], c[1])));
                montre[0] = true;
            }
        };

        AtomicReference<CalqueFenetre> fenetre = new AtomicReference<>();
        AtomicReference<Button> poserRef = new AtomicReference<>();
        Platform.runLater(() -> {
            CalqueFenetre f = new CalqueFenetre(TrocVente.css(), "Vitrine", null);
            fenetre.set(f);
            Label texte = new Label("Re-clique ailleurs dans le jeu pour déplacer la vitrine.");
            texte.setWrapText(true);
            Button annuler = CalqueFenetre.bouton(Icones.FERMER, "", "Annuler la vente (Échap)", false, () -> {
                action.compareAndSet(null, "annuler");
                reveiller();
            });
            Button tourner = CalqueFenetre.bouton(Icones.PIVOTER, "Tourner", "Tourner la vitrine d'un quart de tour", false, () -> {
                rot.set(tourner(rot.get()));
                Salle.tache("troc-apercu-tourner", maj);
                reveiller();                // l'empreinte reprend la nouvelle emprise
            });
            Button poser = CalqueFenetre.bouton(Icones.TROC, "Poser", "Poser la vitrine ici (Entrée)", true, () -> {
                if (ici.get() == null) return;
                action.compareAndSet(null, "poser");
                reveiller();
            });
            poser.setDisable(true);
            poserRef.set(poser);
            for (Button b : new Button[]{annuler, tourner, poser}) b.setCursor(Cursor.HAND);
            f.contenu(texte);
            f.boutons(annuler, tourner, poser);
            f.surFermeture(() -> { action.compareAndSet(null, "annuler"); reveiller(); });
            f.fleches(new RaccourcisGlobaux.Fleches() {
                @Override public void fleche(int direction, boolean maj2) {
                    int[] c = ici.get();
                    if (c == null || !f.ouverte()) return;
                    int[] pas = CalqueActions.pasFleche(direction, maj2);
                    ici.set(new int[]{c[0] + pas[0], c[1] + pas[1]});
                    Salle.tache("troc-apercu-fleche", maj);
                }
                @Override public void entree() { if (f.ouverte() && !poser.isDisabled()) poser.fire(); }
                @Override public void echap() { if (f.ouverte()) annuler.fire(); }
            });
            f.montrer();
        });

        long fin = System.currentTimeMillis() + delaiMs;
        try {
            while (action.get() == null) {
                long reste = fin - System.currentTimeMillis();
                if (reste <= 0) return null;
                int[] e = emprise(l, pr, rot.get());
                HPoint c = Generateur.Dalle.attendreClic(reste, e[0], e[1]);
                if (action.get() != null) break;
                if (Salle.salleId() != salle) return null;
                if (c == null) {
                    if (System.currentTimeMillis() >= fin) return null;
                    continue;                                   // attente relancee (Tourner)
                }
                ici.set(new int[]{c.getX(), c.getY()});
                maj.run();
                fin = System.currentTimeMillis() + delaiMs;     // le delai court depuis la derniere action
                Platform.runLater(() -> { Button b = poserRef.get(); if (b != null) b.setDisable(false); });
            }
            int[] c = ici.get();
            if ("poser".equals(action.get()) && c != null) return new Choix(c[0], c[1], rot.get(), false);
            return new Choix(0, 0, 0, true);
        } finally {
            synchronized (verrou) { if (montre[0]) envoyer(GroupeFantomes.retraitSol(ID)); montre[0] = false; }
            Platform.runLater(() -> {
                CalqueFenetre f = fenetre.get();
                if (f != null) { f.surFermeture(null); f.fermer(); }
            });
        }
    }
}

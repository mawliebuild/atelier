package atelier;

import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Le mur actuellement selectionne, partage par les outils muraux.
 *
 * Selection par simple clic sur un mur dans le jeu.
 *
 * Aucun paquet n'est bloque : le double-clic continue de changer l'etat du
 * meuble normalement. Le clic ne fait que renseigner l'Atelier.
 *
 * On suit aussi ItemUpdate (entrant) pour que la position affichee reste juste
 * apres un deplacement fait dans le jeu plutot que depuis l'Atelier.
 */
public final class SelectionMur {

    public static final class Mur {
        public final int id;
        public String position;
        public String nom = "?";
        public int typeId = -1;
        public String etat = "0";
        /** Dernier envoi de l'Atelier pour ce mur (ms) : sa position locale est alors plus fraiche que la salle. */
        volatile long bougeA = 0L;
        Mur(int id, String position) { this.id = id; this.position = position; }
    }

    private static volatile Mur courant;
    private static final List<Runnable> ecouteurs = new ArrayList<>();
    private static boolean installe = false;
    /** Quand actif, le PREMIER clic sur un mur le selectionne et est bloque. */
    private static volatile boolean modeClic = true;
    /**
     * Trace des paquets sortants, pour diagnostiquer la selection. Coupee par
     * defaut : ecrire chaque paquet dans le terminal retardait chaque clic.
     */
    private static volatile boolean trace = false;
    /** Compteurs : disent si les paquets atteignent reellement l'extension. */
    private static final java.util.concurrent.atomic.AtomicLong sortants =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong entrants =
            new java.util.concurrent.atomic.AtomicLong();
    public static long sortants() { return sortants.get(); }
    public static long entrants() { return entrants.get(); }
    public static boolean trace() { return trace; }
    public static void trace(boolean t) { trace = t; }
    /** Suivi du dernier clic, pour laisser passer le double-clic. */
    private static volatile int dernierId = -1;
    private static volatile long dernierClic = 0L;
    private static final long FENETRE_DOUBLE_CLIC = 700L;   // ms

    private SelectionMur() { }

    public static Mur courant() { return courant; }

    // ------------------------------------------------- selection multiple

    /**
     * Les muraux choisis ensemble (Deplacer un mur), dans l'ordre des clics.
     * Le dernier clic reste aussi courant() pour les autres outils muraux.
     */
    private static final java.util.LinkedHashMap<Integer, Mur> liste = new java.util.LinkedHashMap<>();
    /** La salle des muraux de la liste : en changer vide la liste. */
    private static volatile int salleListe = -1;

    /** Copie des muraux choisis (fil quelconque). */
    public static List<Mur> liste() {
        synchronized (liste) { return new ArrayList<>(liste.values()); }
    }

    /** Ids des muraux choisis (fil quelconque). */
    public static List<Integer> ids() {
        synchronized (liste) { return new ArrayList<>(liste.keySet()); }
    }

    public static boolean contient(int id) {
        synchronized (liste) { return liste.containsKey(id); }
    }

    public static int nombre() {
        synchronized (liste) { return liste.size(); }
    }

    public static int salleListe() { return salleListe; }

    /** Ajoute ce mural, ou le retire s'il y est deja. @return true s'il est maintenant choisi */
    public static boolean basculer(Mur m) {
        if (m == null) return false;
        boolean dedans;
        synchronized (liste) {
            if (liste.remove(m.id) != null) dedans = false;
            else { liste.put(m.id, m); dedans = true; salleListe = Salle.salleId(); }
        }
        prevenir();
        return dedans;
    }

    /** Ajoute ces muraux (ceux deja choisis restent). */
    public static void ajouter(java.util.Collection<Mur> l) {
        if (l == null || l.isEmpty()) return;
        synchronized (liste) {
            for (Mur m : l) if (m != null) liste.putIfAbsent(m.id, m);
            salleListe = Salle.salleId();
        }
        prevenir();
    }

    public static void retirer(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return;
        synchronized (liste) { for (Integer i : ids) liste.remove(i); }
        prevenir();
    }

    public static void vider() {
        synchronized (liste) {
            if (liste.isEmpty()) return;
            liste.clear();
        }
        prevenir();
    }

    /**
     * Logique pure : deux muraux sont-ils « du meme type » ? Meme typeId ;
     * pour les posters (une seule classe pour tous), meme numero de poster
     * (l'etat du mobi).
     */
    static boolean memeType(int typeA, String etatA, int typeB, String etatB, String classe) {
        if (typeA != typeB) return false;
        if (classe != null && classe.toLowerCase(java.util.Locale.ROOT).startsWith("poster"))
            return java.util.Objects.equals(etatA == null ? "" : etatA.trim(), etatB == null ? "" : etatB.trim());
        return true;
    }

    /** Tous les muraux de la salle du meme type que m (m compris). */
    public static List<Mur> memeTypeDansLaSalle(Mur m) {
        List<Mur> r = new ArrayList<>();
        if (m == null || m.typeId < 0) { if (m != null) r.add(m); return r; }
        String classe = Salle.classe(m.typeId, true);
        for (HWallItem w : Salle.murs()) {
            if (!memeType(m.typeId, m.etat, w.getTypeId(), w.getState(), classe)) continue;
            if (w.getId() == m.id) { r.add(m); continue; }
            r.add(depuis(w, m.nom));
        }
        if (r.stream().noneMatch(x -> x.id == m.id)) r.add(0, m);
        return r;
    }

    /** Un Mur lu dans la salle. */
    static Mur depuis(HWallItem w, String nom) {
        Mur x = new Mur(w.getId(), normaliser(w.getLocation()));
        x.typeId = w.getTypeId();
        x.etat = w.getState();
        x.nom = nom != null ? nom : Salle.nom(w.getTypeId(), true);
        return x;
    }

    /** Ces ids viennent d'etre deplaces par l'Atelier : leurs paquets ne comptent pas comme des clics. */
    public static void ignorerEnvoi(int id, long ms) {
        ignores.put(id, System.currentTimeMillis() + ms);
    }

    private static final java.util.Map<Integer, Long> ignores = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean modeClic() { return modeClic; }
    public static void modeClic(boolean actif) { modeClic = actif; }

    public static void ecouter(Runnable r) {
        synchronized (ecouteurs) { ecouteurs.add(r); }
    }

    /** Selection manuelle, depuis une liste deroulante. */
    public static void definir(int id, String position, String nom, int typeId, String etat) {
        Mur m = new Mur(id, normaliser(position));
        m.nom = nom; m.typeId = typeId; m.etat = etat;
        courant = m;
        prevenir();
    }

    /** Met a jour la position sans changer l'objet selectionne. */
    public static void majPosition(String position) {
        Mur m = courant;
        if (m != null) { m.position = normaliser(position); prevenir(); }
    }

    /** Met a jour la position de ce mural (choisi parmi plusieurs) apres un envoi de l'Atelier. */
    public static void majPosition(Mur m, String position) {
        if (m == null) return;
        m.position = normaliser(position);
        m.bougeA = System.currentTimeMillis();
        prevenir();
    }

    /**
     * Position la plus juste de ce mural : la sienne s'il vient d'etre deplace
     * par l'Atelier (le serveur n'a peut-etre pas encore confirme), sinon celle
     * de la salle (un deplacement fait dans le jeu compte). null : plus dans la salle.
     */
    static String positionActuelle(Mur m, long fraicheurMs) {
        HWallItem w = Salle.mur(m.id);
        if (w == null) return null;
        if (System.currentTimeMillis() - m.bougeA < fraicheurMs) return m.position;
        String p = normaliser(w.getLocation());
        if (!p.isEmpty()) m.position = p;
        return m.position;
    }

    /** getLocation() peut porter un suffixe " a=<altitude>" que MoveWallItem n'attend pas. */
    public static String normaliser(String loc) {
        if (loc == null) return "";
        int i = loc.indexOf(" a=");
        return (i >= 0 ? loc.substring(0, i) : loc).trim();
    }

    /**
     * Branche les interceptions une seule fois, sur l'instance du moteur de l'Atelier
     * embarquee. Sans effet si elle n'est pas encore prete : les outils rappellent.
     */
    public static synchronized void installer() {
        if (installe) return;
        Moteur gp = AtelierLauncher.moteur();
        if (gp == null) return;
        try {
            // Interception SANS nom de paquet. Un intercept par nom echoue en
            // SILENCE quand le nom ne se resout plus — c'est ce qui empechait la
            // selection de fonctionner. On identifie le clic par son contenu :
            // un petit paquet sortant dont un entier est l'id d'un mur de la salle.
            gp.intercept(HMessage.Direction.TOSERVER, m -> {
                sortants.incrementAndGet();
                try { examiner(gp, m); } catch (Throwable ignored) { }
            });
            // Compteur entrant : si les deux restent a zero, l'extension ne
            // recoit aucun paquet et tout le reste en decoule.
            gp.intercept(HMessage.Direction.TOCLIENT, m -> entrants.incrementAndGet());
            installe = true;
            Journal.debug("selection au clic active (par contenu).");
            resumePeriodique(gp);
        } catch (Throwable t) {
            System.err.println("[Atelier] selection au clic indisponible : " + t);
        }
    }

    /** Resume regulier dans le terminal : dit si le flux atteint l'extension. */
    private static void resumePeriodique(Moteur gp) {
        if (!Journal.DEBUG) return;   // seulement en mode diagnostic
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                try {
                    EtatSalle s = gp.getFloorState();
                    int murs = -1;
                    boolean dans = false;
                    if (s != null) {
                        dans = s.inRoom();
                        try { java.util.List<HWallItem> w = s.getWallItems(); murs = (w == null) ? -1 : w.size(); }
                        catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
            }
        }, "atelier-resume");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reconnait un clic sur un mur sans connaitre le nom du paquet.
     *
     * Le paquet n'est JAMAIS bloque : le double-clic doit continuer de changer
     * l'etat du meuble dans le jeu.
     */
    private static void examiner(Moteur gp, HMessage m) {
        // Ce code s'execute pour CHAQUE paquet envoye, pendant que le jeu attend :
        // tout ce qui suit doit rester bon marche. Taille d'abord, sans copie.
        int taille = m.getPacket().getBytesLength();
        if (!trace && taille > 40) return;        // un clic est un petit paquet
        HPacket p = m.getPacket();                  // lectures a position fixe : pas de copie

        // Trace AVANT tout test : c'est elle qui doit dire si les paquets
        // arrivent. La mettre apres le test inRoom() la rendait muette des que
        // l'etat de salle etait faux — exactement le cas qu'on cherche a lire.
        if (trace && taille <= 48) {
            StringBuilder sb = new StringBuilder();
            sb.append("sortant header=").append(p.headerId())
              .append(" taille=").append(taille).append("  entiers:");
            for (int off = 6; off + 4 <= taille && off < 30; off += 4) {
                try { sb.append(' ').append(p.readInteger(off)); }
                catch (Throwable e) { break; }
            }
            Journal.debug(sb.toString());
        }

        if (taille > 40) return;
        EtatSalle s = gp.getFloorState();
        if (s == null || !s.inRoom()) return;

        // On balaie TOUS les offsets, pas seulement les multiples de 4 : rien ne
        // garantit que l'identifiant soit aligne. Une recherche directe par id
        // par offset, plutot que de recopier tous les murs de la salle a chaque
        // paquet.
        for (int off = 6; off + 4 <= taille; off++) {
            int v;
            try { v = p.readInteger(off); } catch (Throwable e) { break; }
            if (v <= 0) continue;
            HWallItem it = s.wallItemFromId(v);
            if (it != null) { retenir(gp, v, it.getLocation()); return; }
        }

        // Forme TEXTE : le client Flash envoie certains identifiants en chaine,
        // ce qui donne des entiers absurdes quand on les lit comme des octets.
        // On ne teste que les suites de chiffres presentes dans le paquet.
        byte[] octets = p.toBytes();
        for (int i = 6; i < octets.length; ) {
            if (octets[i] < '0' || octets[i] > '9') { i++; continue; }
            int debut = i;
            while (i < octets.length && octets[i] >= '0' && octets[i] <= '9') i++;
            if (i - debut > 10) continue;
            try {
                long v = Long.parseLong(new String(octets, debut, i - debut,
                        java.nio.charset.StandardCharsets.ISO_8859_1));
                if (v <= 0 || v > Integer.MAX_VALUE) continue;
                HWallItem it = s.wallItemFromId((int) v);
                if (it != null) { retenir(gp, (int) v, it.getLocation()); return; }
            } catch (Throwable ignored) { }
        }

        // Aucun identifiant reconnu : afficher les deux cotes une seule fois,
        // c'est le seul moyen de voir pourquoi la comparaison echoue.
        if (trace && taille <= 20 && !compareAffichee) {
            compareAffichee = true;
            StringBuilder sb = new StringBuilder("AUCUN ID RECONNU. paquet:");
            for (int off = 6; off + 4 <= taille; off += 4) {
                try { sb.append(' ').append(p.readInteger(off)); } catch (Throwable e) { break; }
            }
            sb.append("   |   ids de murs connus (5 premiers):");
            int n = 0;
            try {
                for (HWallItem w : s.getWallItems()) { sb.append(' ').append(w.getId()); if (++n >= 5) break; }
            } catch (Throwable ignored) { }
            Journal.debug(sb.toString());
        }
    }

    private static volatile boolean compareAffichee = false;

    private static void retenir(Moteur gp, int id, String loc) {
        long t = System.currentTimeMillis();
        Long fin = ignores.get(id);
        if (fin != null) {
            if (t < fin) return;              // notre propre envoi, pas un clic
            ignores.remove(id);
        }
        // Un meme geste peut produire plusieurs paquets (double-clic, prise puis
        // depot) : dans la fenetre du double-clic, c'est le meme clic. On garde
        // le meme objet, pour que les outils qui basculent au clic ne basculent
        // pas deux fois.
        Mur c = courant;
        if (c != null && c.id == id && id == dernierId && t - dernierClic < FENETRE_DOUBLE_CLIC) {
            dernierClic = t;
            c.position = normaliser(loc);
            prevenir();
            return;
        }
        dernierId = id;
        dernierClic = t;
        Mur m = new Mur(id, normaliser(loc));
        try {
            EtatSalle s = gp.getFloorState();
            HWallItem it = (s == null) ? null : s.wallItemFromId(id);
            if (it != null) {
                m.typeId = it.getTypeId();
                m.etat = it.getState();
                m.nom = nom(gp, it.getTypeId());
            }
        } catch (Throwable ignored) { }
        courant = m;
        prevenir();
    }

    static String nom(Moteur gp, int typeId) {
        try {
            Furnidata fd = gp.getFurniDataTools();
            if (fd != null && fd.isReady()) {
                String cls = fd.getWallItemName(typeId);
                if (cls != null) {
                    Furnidata.Mobi d = fd.getWallItemDetails(cls);
                    if (d != null && d.name != null && !d.name.isEmpty()) return d.name;
                    return cls;
                }
            }
        } catch (Throwable ignored) { }
        return "type " + typeId;
    }

    private static void prevenir() {
        List<Runnable> copie;
        synchronized (ecouteurs) { copie = new ArrayList<>(ecouteurs); }
        for (Runnable r : copie) Platform.runLater(r);
    }
}

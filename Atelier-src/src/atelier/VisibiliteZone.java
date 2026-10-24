package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import javafx.application.Platform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visibilite zone : cacher une zone de l'appart CHEZ TOI SEULEMENT, comme le
 * mobi « Sélecteur de zone » (conf_area_hide), pour cliquer les cases qui sont
 * derriere. Le serveur ne sait rien ; recharger l'appart remet tout.
 *
 * Le sol : le paquet serveur AreaHide (en-tete 3384 dans le client d'origine
 * etudie, nom resolu par le proxy). C'est EXACTEMENT ce que recoit le jeu
 * quand on allume un vrai Sélecteur de zone : le client lit
 *   int idMobi, boolean allume, int x, int y, int largeur, int longueur, boolean inverse
 * puis RoomEngine.updateAreaHide fait un « trou » dans le plan du sol
 * (RoomObjectRoomFloorHoleUpdateMessage, RoomPlaneParser.addFloorHole) : les
 * cases de la zone ne sont plus dessinees ni cliquables, les clics passent aux
 * cases derriere. updateAreaHide ne cherche PAS le mobi : un identifiant
 * fictif (negatif, jamais un vrai mobi) suffit, aucun faux mobi a poser.
 * « allume = false » rebouche le trou. Le plan est redessine, l'appart n'est
 * pas recharge, l'avatar marche comme avant (la marche est cote serveur).
 *
 * Les mobis : le client v36 ne cache pas les mobis d'un Sélecteur de zone (le
 * trou ne touche que le sol). On passe par les calques (Calques.regler, raison
 * « zone:<n> ») : ObjectRemove / ItemRemove au client, mises a jour retenues
 * puis rejouees au retour, position et etat a jour.
 *
 * Changement d'appart, rechargement (nouvelle liste de mobis, nouveau plan) :
 * le client a tout jete : toutes les zones repassent visibles (rien n'est
 * remasque tout seul). Les zones et leurs raccourcis sont enregistres par
 * appart dans le dossier de donnees de l'Atelier (zones/<idAppart>.json).
 */
final class VisibiliteZone {

    private VisibiliteZone() { }

    // ---------------------------------------------------------------- modele

    /** Une zone de l'appart, avec son etat voulu et son raccourci. */
    static final class ZoneVue {
        final int num;
        volatile String nom;
        final int x1, y1, x2, y2;
        volatile boolean masquee;
        volatile boolean muraux = true;
        /** Raccourci : touche (« k », « 5 », « F3 ») et modificateurs RaccourcisGlobaux.Carbon ; null = aucun. */
        volatile String touche;
        volatile int mods;

        ZoneVue(int num, String nom, int ax, int ay, int bx, int by) {
            this.num = num; this.nom = nom;
            x1 = Math.min(ax, bx); y1 = Math.min(ay, by); x2 = Math.max(ax, bx); y2 = Math.max(ay, by);
        }
        int largeur() { return x2 - x1 + 1; }
        int longueur() { return y2 - y1 + 1; }
        String raison() { return "zone:" + num; }
        /** Identifiant du trou chez le client : negatif, jamais un vrai mobi ni un fantome. */
        int trou() { return -770000 - num; }
        String taille() { return largeur() + " × " + longueur(); }
    }

    private static final List<ZoneVue> zones = new CopyOnWriteArrayList<>();
    private static volatile int salle = -1;
    /** Zones dont le trou et les masques sont en place chez le client. */
    private static final Set<Integer> appliquees = ConcurrentHashMap.newKeySet();
    /** Dernier jeu de mobis masques par zone (ids tries), pour ne regler que ce qui change. */
    private static final Map<Integer, String> derniers = new ConcurrentHashMap<>();
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();
    /** Un seul fil pour tout ce qui part vers le client, dans l'ordre. */
    private static final ExecutorService FIL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atelier-visibilite-zone");
        t.setDaemon(true);
        return t;
    });

    static List<ZoneVue> zones() { return new ArrayList<>(zones); }

    /** L'appart dont la liste parle (-1 hors appart). */
    static int salle() { return salle; }

    /** Prevenu sur le fil JavaFX a chaque changement. */
    static void ecouter(Runnable r) { ecouteurs.add(r); }

    private static void prevenir() {
        Platform.runLater(() -> { for (Runnable r : ecouteurs) try { r.run(); } catch (Throwable ignored) { } });
    }

    // ------------------------------------------------------------- actions

    /** Nouvelle zone (visible) ; null hors appart. */
    static ZoneVue ajouter(int ax, int ay, int bx, int by) {
        if (salle == -1 || salle != Salle.salleId()) return null;
        int num = 1;
        for (ZoneVue z : zones) num = Math.max(num, z.num + 1);
        ZoneVue z = new ZoneVue(num, "Zone " + num, ax, ay, bx, by);
        zones.add(z);
        sauver();
        prevenir();
        return z;
    }

    static void renommer(ZoneVue z, String nom) {
        if (z == null || nom == null || nom.isBlank() || nom.trim().equals(z.nom)) return;
        z.nom = Ui.majuscule(nom.trim());
        sauver();
        prevenir();
    }

    static void supprimer(ZoneVue z) {
        if (z == null || !zones.remove(z)) return;
        boolean avait = appliquees.contains(z.num);
        FIL.submit(() -> { if (avait) retirer(z); });
        sauver();
        publierRaccourcis();
        prevenir();
    }

    static void muraux(ZoneVue z, boolean oui) {
        if (z == null || z.muraux == oui) return;
        z.muraux = oui;
        sauver();
        if (z.masquee) FIL.submit(() -> { if (appliquees.contains(z.num)) suivre(z, true); });
        prevenir();
    }

    /** Masque ou rend visible (le resultat se voit dans le jeu : aucun message). */
    static void masquer(ZoneVue z, boolean oui) {
        if (z == null || !zones.contains(z)) return;
        z.masquee = oui;
        sauver();
        prevenir();
        FIL.submit(() -> {
            if (!Salle.dansUneSalle() || Salle.salleId() != salle) return;
            if (oui) appliquer(z); else retirer(z);
            prevenir();
        });
    }

    static void basculer(ZoneVue z) { if (z != null) masquer(z, !z.masquee); }

    /**
     * Donne un raccourci a la zone (touche null = le retirer). @return null si
     * c'est fait, sinon la raison du refus (deja pris par l'Atelier ou une zone).
     */
    static String raccourci(ZoneVue z, String touche, int mods) {
        if (z == null) return "Zone introuvable.";
        if (touche != null) {
            String pris = RaccourcisGlobaux.prisParAtelier(touche, mods, WindowsClavier.WINDOWS);
            if (pris != null) return "Raccourci refusé : " + texteTouche(touche, mods) + ", " + pris + ".";
            int n = 0;
            for (ZoneVue a : zones) {
                if (a == z || a.touche == null) continue;
                n++;
                if (a.touche.equals(touche) && memesMods(a.mods, mods))
                    return "Raccourci refusé : " + texteTouche(touche, mods) + " est déjà pris par la zone « " + a.nom + " ».";
            }
            if (n >= RaccourcisGlobaux.CHOISIS_MAX) return "Raccourci refusé : " + RaccourcisGlobaux.CHOISIS_MAX + " raccourcis de zone au plus par appart.";
        }
        z.touche = touche;
        z.mods = touche == null ? 0 : mods;
        sauver();
        publierRaccourcis();
        prevenir();
        return null;
    }

    /** Sous Windows, Cmd et Ctrl sont la meme touche Ctrl. */
    private static boolean memesMods(int a, int b) {
        if (!WindowsClavier.WINDOWS) return a == b;
        int ctrl = RaccourcisGlobaux.Carbon.CTRL, cmd = RaccourcisGlobaux.Carbon.CMD;
        return ((a & ctrl) != 0 ? (a & ~ctrl) | cmd : a) == ((b & ctrl) != 0 ? (b & ~ctrl) | cmd : b);
    }

    /** « ⌥⇧K » sur Mac, « Alt+Maj+K » sous Windows. */
    static String texteTouche(String touche, int mods) {
        if (touche == null) return "Aucun";
        boolean win = WindowsClavier.WINDOWS;
        List<String> p = new ArrayList<>();
        if ((mods & RaccourcisGlobaux.Carbon.CTRL) != 0) p.add(win ? "Ctrl" : "⌃");
        if ((mods & RaccourcisGlobaux.Carbon.OPTION) != 0) p.add(win ? "Alt" : "⌥");
        if ((mods & RaccourcisGlobaux.Carbon.MAJ) != 0) p.add(win ? "Maj" : "⇧");
        if ((mods & RaccourcisGlobaux.Carbon.CMD) != 0 && !(win && p.contains("Ctrl"))) p.add(win ? "Ctrl" : "⌘");
        p.add(touche.toUpperCase(Locale.ROOT));
        return String.join(win ? "+" : "", p);
    }

    /** Les raccourcis des zones de l'appart ouvert, pris dans le jeu par RaccourcisGlobaux. */
    private static void publierRaccourcis() {
        List<RaccourcisGlobaux.Choisi> l = new ArrayList<>();
        List<ZoneVue> cibles = new ArrayList<>();
        if (salle != -1)
            for (ZoneVue z : zones) {
                if (z.touche == null) continue;
                l.add(new RaccourcisGlobaux.Choisi(z.touche, z.mods));
                cibles.add(z);
            }
        RaccourcisGlobaux.choisis(l, i -> {
            if (i < 0 || i >= cibles.size()) return;
            ZoneVue z = cibles.get(i);
            if (!zones.contains(z)) return;
            if (!Salle.dansUneSalle()) { InfoJeu.consigne("Entre dans l'appart pour masquer une zone."); return; }
            basculer(z);
        });
    }

    // ------------------------------------------------- envois vers le client

    /** Mobis de sol dont une case est dans la zone (meme un grand mobi qui deborde). */
    private static List<HFloorItem> sols(ZoneVue z) {
        List<HFloorItem> r = new ArrayList<>();
        for (HFloorItem it : Salle.sols()) {
            if (it == null || it.getTile() == null) continue;
            int[] e = Salle.emprise(it);
            int x = it.getTile().getX(), y = it.getTile().getY();
            if (x <= z.x2 && x + e[0] - 1 >= z.x1 && y <= z.y2 && y + e[1] - 1 >= z.y1) r.add(it);
        }
        return r;
    }

    /**
     * Muraux accroches au bord de la zone : leur case « :w=x,y » est dans la
     * zone, ou juste avant elle (les murs sont du cote des petits x et y).
     */
    private static List<HWallItem> muraux(ZoneVue z) {
        List<HWallItem> r = new ArrayList<>();
        if (!z.muraux) return r;
        for (HWallItem it : Salle.murs()) {
            int[] c = it == null ? null : GroupeCalcul.caseMur(it.getLocation());
            if (c == null) continue;
            if (c[0] >= z.x1 - 1 && c[0] <= z.x2 && c[1] >= z.y1 - 1 && c[1] <= z.y2) r.add(it);
        }
        return r;
    }

    /**
     * Jetons de mise en valeur (« s12 » sol, « m34 » mural) des mobis de la zone,
     * sans les dalles magiques ni les mobis fictifs ; les muraux si la case
     * « Muraux » de la zone est cochee.
     */
    static List<String> jetons(ZoneVue z) {
        List<String> r = new ArrayList<>();
        if (z == null || salle == -1 || Salle.salleId() != salle) return r;
        for (HFloorItem it : sols(z)) {
            if (Salle.idFictif(it.getId())) continue;           // grille, fantomes des calques
            String c = Salle.classe(it.getTypeId(), false);
            if (c != null && DalleMagique.estDalle(c)) continue;
            r.add("s" + it.getId());
        }
        for (HWallItem w : muraux(z)) if (!Salle.idFictif(w.getId())) r.add("m" + w.getId());
        return r;
    }

    // ------------------------------------------------- souris dans les zones masquees

    /** Dernier « atelier:masque= » envoye (null : rien envoye dans cet appart) et quand. */
    private static volatile String masqueEnvoye = null;
    private static volatile long masqueEnvoyeA = 0;

    /**
     * Dit au client (modifie) quelles zones sont masquees : la souris y passe a
     * travers les avatars, comme elle passe deja a travers le sol (trou) et les
     * mobis (retires). Rien si le client ne connait pas ce message.
     */
    private static void publierMasques(boolean force) {
        if (!ClientModifie.saitMasque()) return;
        if (!Salle.dansUneSalle() || Salle.salleId() != salle || salle == -1) return;
        StringBuilder b = new StringBuilder();
        for (ZoneVue z : zones) {
            if (!appliquees.contains(z.num)) continue;
            if (b.length() > 0) b.append('|');
            b.append(z.x1).append(';').append(z.y1).append(';').append(z.x2).append(';').append(z.y2);
        }
        String m = b.toString();
        if (!force && m.equals(masqueEnvoye)) return;
        if (masqueEnvoye == null && m.isEmpty()) { masqueEnvoye = m; return; }   // rien a dire
        Moteur gp = Salle.gp();
        if (gp == null) return;
        try {
            gp.sendToClient(new HPacket("Whisper", HMessage.Direction.TOCLIENT, -1, "atelier:masque=" + m, 0, 0, 0, -1));
            masqueEnvoye = m;
            masqueEnvoyeA = System.currentTimeMillis();
        } catch (Throwable t) {
            Journal.debug("visibilité zone : masque pas envoyé : " + t);
        }
    }

    private static volatile int enteteTrou = -1;
    /** Chargement de l'appart (EtatSalle.generation) au moment du dernier masquage. */
    private static volatile int jetonApplique = 0;
    private static volatile boolean trouSignale = false;

    /** AreaHide vers le client ; false si le paquet ne se resout pas. */
    private static boolean envoyerTrou(ZoneVue z, boolean on) {
        return envoyerTrou(z.trou(), on, z.x1, z.y1, z.largeur(), z.longueur());
    }

    private static boolean envoyerTrou(int id, boolean on, int x, int y, int largeur, int longueur) {
        Moteur gp = Salle.gp();
        if (gp == null) return false;
        int h = enteteTrou;
        if (h < 0) {
            h = RechercheReseau.entete(HMessage.Direction.TOCLIENT, "AreaHide", "AreaHideMessage");
            enteteTrou = h;
            Journal.debug("visibilité zone : en-tête AreaHide = " + h);
        }
        if (h < 0) {
            if (!trouSignale) {
                trouSignale = true;
                InfoJeu.consigne("Le sol de la zone ne peut pas être caché (paquet AreaHide inconnu du proxy) : seuls les mobis sont masqués.");
            }
            return false;
        }
        try {
            HPacket p = new HPacket(h);
            p.appendInt(id);
            p.appendBoolean(on);
            p.appendInt(x);
            p.appendInt(y);
            p.appendInt(largeur);
            p.appendInt(longueur);
            p.appendBoolean(false);                           // pas inverse : la zone seule
            return gp.sendToClient(p);
        } catch (Throwable t) {
            Journal.debug("visibilité zone : AreaHide pas envoyé : " + t);
            return false;
        }
    }

    // ------------------------------------------------------------- sol de tout l'appart

    /** Identifiant (fictif) du trou qui couvre tout l'appart. */
    private static final int TROU_SOL = -769999;
    private static volatile boolean solCache = false;

    /** Le sol de tout l'appart est-il cache (chez moi) ? */
    static boolean solCache() { return solCache; }

    /**
     * Cache (ou remontre) le sol de tout l'appart, chez moi seulement : un seul
     * AreaHide sur tout le plan. Les mobis restent (le trou ne touche que le sol),
     * les clics passent aux cases derriere. Un changement d'appart le remet.
     */
    static void cacherSol(boolean oui) {
        EtatSalle s = Salle.etat();
        if (s == null || !Salle.dansUneSalle()) { InfoJeu.consigne("Entre d'abord dans un appart."); return; }
        int l = s.getFloorplanWidth(), p = s.getFloorplanHeight();
        if (l <= 0 || p <= 0) { InfoJeu.consigne("Le plan de l'appart n'est pas encore connu, réessaie dans un instant."); return; }
        if (!envoyerTrou(TROU_SOL, oui, 0, 0, l, p)) return;
        solCache = oui;
        prevenir();
    }

    /** Cache le sol et les mobis de la zone. Sur FIL. */
    private static void appliquer(ZoneVue z) {
        if (!Calques.pret()) {
            InfoJeu.consigne("Masquer la zone « " + z.nom + " » : l'Atelier n'est pas encore prêt, réessaie dans un instant.");
            return;
        }
        envoyerTrou(z, true);
        jetonApplique = jeton();
        appliquees.add(z.num);
        suivre(z, true);
        publierMasques(false);
    }

    /** Rebouche le trou et rend les mobis (etat et position a jour). Sur FIL. */
    private static void retirer(ZoneVue z) {
        appliquees.remove(z.num);
        derniers.remove(z.num);
        if (!Salle.dansUneSalle()) return;
        envoyerTrou(z, false);
        Calques.reafficher(z.raison());
        publierMasques(false);
    }

    /**
     * Les mobis de la zone ont-ils change (pose, deplacement, wired) ? Alors
     * le calque de la zone est regle de nouveau : un mobi entre est cache, un
     * mobi sorti revient. Sur FIL.
     */
    private static void suivre(ZoneVue z, boolean force) {
        List<HFloorItem> s = sols(z);
        List<HWallItem> m = muraux(z);
        StringBuilder sig = new StringBuilder();
        s.stream().mapToInt(HFloorItem::getId).sorted().forEach(i -> sig.append('s').append(i));
        m.stream().mapToInt(HWallItem::getId).sorted().forEach(i -> sig.append('m').append(i));
        String n = sig.toString();
        if (!force && n.equals(derniers.get(z.num))) {
            // rien n'a bouge, mais plus aucun mobi masque : « Tout réafficher » des calques
            if (!n.isEmpty() && Calques.nombre(z.raison()) == 0 && jeton() == jetonApplique
                    && Salle.installeeDepuis(3000)) {
                Journal.debug("visibilité zone : « " + z.nom + " » réaffichée par les calques, elle repasse visible.");
                z.masquee = false;
                envoyerTrou(z, false);
                appliquees.remove(z.num);
                derniers.remove(z.num);
                publierMasques(false);
                sauver();
                prevenir();
            }
            return;
        }
        derniers.put(z.num, n);
        Calques.regler(z.raison(), s, m);
    }

    // ------------------------------------------------------------ surveillance

    private static volatile boolean installe = false;
    /** Un nouveau plan est arrive (FloorHeightMap) : le client a refait le sol, les trous sont perdus. */
    private static volatile long planRecuA = 0;

    static synchronized void installer() {
        if (installe) return;
        installe = true;
        Calques.installer();
        Salle.tache("visibilite-zone-ecoute", () -> {
            for (int i = 0; i < 900; i++) {
                Moteur gp = Salle.gp();
                if (gp != null) {
                    try { gp.intercept(HMessage.Direction.TOCLIENT, "FloorHeightMap", m -> planRecuA = System.currentTimeMillis()); }
                    catch (Throwable t) { Journal.debug("visibilité zone : écoute FloorHeightMap indisponible : " + t); }
                    break;
                }
                Salle.sommeil(1000);
            }
        });
        Salle.tache("visibilite-zone", VisibiliteZone::surveiller);
    }

    private static int jeton() {
        EtatSalle s = Salle.etat();
        return s == null ? 0 : s.generation();
    }

    private static void surveiller() {
        int derniere = -2, dernierJeton = 0;
        long entree = 0, planVu = 0;
        while (true) {
            Salle.sommeil(700);
            try {
                int s = Salle.salleId();
                int j = s == -1 ? 0 : jeton();
                if (s != derniere) {                       // nouvel appart (ou sortie)
                    derniere = s;
                    dernierJeton = j;
                    planVu = planRecuA;
                    entree = System.currentTimeMillis();
                    appliquees.clear(); derniers.clear();
                    solCache = false;
                    masqueEnvoye = null;                       // le client a un nouveau canevas
                    charger(s);
                    publierRaccourcis();
                    prevenir();
                    continue;
                }
                if (s == -1) continue;
                long plan = planRecuA;
                if ((j != 0 && j != dernierJeton) || plan != planVu) {
                    // meme appart recharge : le client a tout recu de nouveau (Calques a oublie ses masques)
                    dernierJeton = j; planVu = plan;
                    entree = System.currentTimeMillis();
                    appliquees.clear(); derniers.clear();
                    solCache = false;
                    FIL.submit(() -> publierMasques(true));    // plus rien de masque chez le client
                    toutVisible();
                    continue;
                }
                if (appliquees.isEmpty()) continue;
                FIL.submit(() -> {
                    for (ZoneVue z : zones)
                        if (appliquees.contains(z.num) && z.masquee && Salle.salleId() == salle) suivre(z, false);
                    // rappel : un canevas refait par le jeu (zoom, vue) garde la grille, mais au cas ou
                    if (System.currentTimeMillis() - masqueEnvoyeA > 15000) publierMasques(true);
                });
            } catch (Throwable t) {
                System.err.println("[Atelier] visibilité zone : surveillance : " + t);
            }
        }
    }

    /** Appart recharge : le client a tout redessine, les zones sont toutes visibles. */
    private static synchronized void toutVisible() {
        boolean change = false;
        for (ZoneVue z : zones) if (z.masquee) { z.masquee = false; change = true; }
        if (change) sauver();
        prevenir();
    }

    // ------------------------------------------------------------- disque

    /** Dossier des zones : a cote des calques, dans le dossier de donnees de l'Atelier. */
    static File dossier() { return new File(Dossiers.donneesAtelier(), "zones").getAbsoluteFile(); }

    /** Charge les zones de cet appart (vide hors appart ou premiere visite). */
    private static synchronized void charger(int s) {
        zones.clear();
        salle = s;
        if (s == -1) return;
        File f = GroupeStockage.fichier(dossier(), s);
        if (!f.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("zones");
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                JSONObject z = a.getJSONObject(i);
                ZoneVue v = new ZoneVue(z.getInt("num"), z.optString("nom", "Zone"),
                        z.getInt("x1"), z.getInt("y1"), z.getInt("x2"), z.getInt("y2"));
                v.masquee = false;                         // nouvelle visite : tout visible
                v.muraux = z.optBoolean("muraux", true);
                String t = z.optString("touche", "");
                if (!t.isEmpty()) { v.touche = t; v.mods = z.optInt("mods", 0); }
                zones.add(v);
            }
        } catch (Throwable t) {
            Journal.erreur("Zones de l'appart illisibles (" + f + ")", t);
        }
    }

    private static synchronized void sauver() {
        int s = salle;
        if (s == -1) return;
        JSONArray a = new JSONArray();
        for (ZoneVue z : zones) {
            JSONObject o = new JSONObject();
            o.put("num", z.num); o.put("nom", z.nom);
            o.put("x1", z.x1); o.put("y1", z.y1); o.put("x2", z.x2); o.put("y2", z.y2);
            o.put("masquee", z.masquee); o.put("muraux", z.muraux);
            if (z.touche != null) { o.put("touche", z.touche); o.put("mods", z.mods); }
            a.put(o);
        }
        try {
            GroupeStockage.ecrire(dossier(), s, new JSONObject().put("salle", s).put("zones", a).toString(2));
        } catch (Throwable t) {
            Journal.erreur("Zones pas enregistrées (" + dossier() + ")", t);
        }
    }
}

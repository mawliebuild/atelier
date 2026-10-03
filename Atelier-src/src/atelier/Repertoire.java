package atelier;

import extension.GPresets;
import gearth.extensions.parsers.catalog.HCatalogIndex;
import gearth.extensions.parsers.catalog.HCatalogPageIndex;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repertoire des mobis : pour chacun, sa page du catalogue et son annee.
 *
 * Aucun site ne donne l'annee de TOUS les mobis. On combine donc, du plus
 * fiable au moins fiable :
 *   1. catalogue  — l'arborescence du catalogue de habbo.fr (« Noël 2024 »).
 *                   Le jeu l'envoie d'un bloc ; chaque page liste ses numeros
 *                   d'offre, et la furnidata donne le numero d'offre de chaque
 *                   mobi. Pas besoin d'ouvrir les pages une par une.
 *   2. collection — l'annee ecrite dans la furniline (xmas_2024, nft2023).
 *   3. nouveaute  — un mobi apparu dans la furnidata depuis la derniere fois :
 *                   on verifie sa date sur HabboTravel, sinon on retient le
 *                   jour ou l'Atelier l'a vu arriver.
 *   4. estimation — pour le reste : les numeros de type des mobis croissent
 *                   avec le temps ; on prend l'annee des 5 voisins connus.
 *                   Toujours marquee comme estimee.
 *
 * Tout est stocke dans repertoire/mobis.json, a cote de l'Atelier, et
 * complete tout seul a chaque demarrage : rien a faire a la main.
 */
public final class Repertoire {

    public static final class Entree {
        public String page, chemin, annee, source;
        public String vuLe = LocalDate.now().toString();
        public boolean estimee() { return "estimation".equals(source); }
    }

    private static final Map<String, Entree> mobis = new ConcurrentHashMap<>();
    private static final File FICHIER = new File("repertoire", "mobis.json");
    /** HabboTravel a importe tout son catalogue ce jour-la : ses dates d'avant ne veulent rien dire. */
    private static final LocalDate IMPORT_HABBOTRAVEL = LocalDate.of(2026, 9, 26);
    private static final Pattern ANNEE = Pattern.compile("(?<!\\d)(19\\d\\d|20[0-3]\\d)(?!\\d)");

    private static volatile boolean demarre = false;
    /** Vrai une fois le premier passage termine : ensuite, chaque index recu est enregistre. */
    private static volatile boolean lu = false;
    private static volatile Runnable surMaj = () -> { };

    private Repertoire() { }

    /** Appele quand le repertoire a ete complete (pour rafraichir les listes). */
    public static void surMaj(Runnable r) { surMaj = (r == null) ? () -> { } : r; }

    public static Entree entree(String classe, boolean mur) {
        return (classe == null) ? null : mobis.get(cle(classe, mur));
    }

    private static String cle(String classe, boolean mur) { return (mur ? "m:" : "s:") + classe; }

    // ------------------------------------------------------------- demarrage

    public static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(Repertoire::travailler, "atelier-repertoire");
        t.setDaemon(true);
        t.start();
    }

    private static void travailler() {
        try {
            GPresets gp = attendre();
            if (gp == null) return;
            boolean premiereFois = !FICHIER.exists();
            charger();

            Map<String, Object[]> furni = toutLaFurnidata(gp);   // cle -> {classe, mur, details}
            if (furni.isEmpty()) { System.err.println("[Atelier] répertoire : furnidata illisible."); return; }

            // Mobis jamais vus : nouveaux depuis le dernier demarrage.
            String aujourdhui = LocalDate.now().toString();
            List<String> nouveaux = new ArrayList<>();
            for (String k : furni.keySet()) {
                if (mobis.containsKey(k)) continue;
                Entree e = new Entree();
                e.vuLe = aujourdhui;
                mobis.put(k, e);
                if (!premiereFois) nouveaux.add(k);
            }

            ecouterCatalogue(gp, furni);
            demanderCatalogue(gp);
            Thread.sleep(20_000);                 // le temps que les index arrivent

            completerParCollection(furni);
            completerNouveautes(nouveaux, furni);
            estimer(furni);
            sauver();
            lu = true;
            System.out.println("[Atelier] répertoire : " + mobis.size() + " mobis, "
                    + compter("catalogue") + " par le catalogue, " + compter("collection")
                    + " par la collection, " + compter("nouveauté") + " nouveautés, "
                    + compter("estimation") + " estimés" + (nouveaux.isEmpty() ? ""
                    : " — " + nouveaux.size() + " nouveaux mobis ajoutés"));
            surMaj.run();
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : " + t);
        }
    }

    private static GPresets attendre() throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            GPresets gp = AtelierLauncher.gpresets();
            try {
                if (gp != null && gp.getFurniDataTools() != null && gp.getFurniDataTools().isReady())
                    return gp;
            } catch (Throwable ignored) { }
            Thread.sleep(1000);
        }
        return null;
    }

    private static long compter(String source) {
        return mobis.values().stream().filter(e -> source.equals(e.source)).count();
    }

    // ------------------------------------------------------------- furnidata

    /** Toute la furnidata ; ses tables sont privees, on les lit par reflexion. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object[]> toutLaFurnidata(GPresets gp) {
        Map<String, Object[]> r = new HashMap<>();
        try {
            furnidata.FurniDataTools fd = gp.getFurniDataTools();
            Field fs = furnidata.FurniDataTools.class.getDeclaredField("nameToFloorItems");
            Field fm = furnidata.FurniDataTools.class.getDeclaredField("nameToWallItems");
            fs.setAccessible(true);
            fm.setAccessible(true);
            Map<String, furnidata.details.FloorItemDetails> sols =
                    (Map<String, furnidata.details.FloorItemDetails>) fs.get(fd);
            Map<String, furnidata.details.WallItemDetails> murs =
                    (Map<String, furnidata.details.WallItemDetails>) fm.get(fd);
            if (sols != null) for (Map.Entry<String, furnidata.details.FloorItemDetails> e : sols.entrySet())
                r.put(cle(e.getKey(), false), new Object[]{e.getKey(), false, e.getValue()});
            if (murs != null) for (Map.Entry<String, furnidata.details.WallItemDetails> e : murs.entrySet())
                r.put(cle(e.getKey(), true), new Object[]{e.getKey(), true, e.getValue()});
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : lecture de la furnidata impossible : " + t);
        }
        return r;
    }

    // ------------------------------------------------------------- catalogue

    private static void ecouterCatalogue(GPresets gp, Map<String, Object[]> furni) {
        // Numero d'offre -> mobis. Un meme numero peut servir a plusieurs variantes.
        Map<Integer, List<String>> parOffre = new HashMap<>();
        Map<Integer, List<String>> parOffreBc = new HashMap<>();
        for (Map.Entry<String, Object[]> e : furni.entrySet()) {
            Object d = e.getValue()[2];
            if (d instanceof furnidata.details.FloorItemDetails) {
                furnidata.details.FloorItemDetails f = (furnidata.details.FloorItemDetails) d;
                if (f.offerId > 0) parOffre.computeIfAbsent(f.offerId, k -> new ArrayList<>()).add(e.getKey());
                if (f.bcOfferId > 0) parOffreBc.computeIfAbsent(f.bcOfferId, k -> new ArrayList<>()).add(e.getKey());
            } else if (d instanceof furnidata.details.WallItemDetails) {
                furnidata.details.WallItemDetails w = (furnidata.details.WallItemDetails) d;
                if (w.offerId > 0) parOffre.computeIfAbsent(w.offerId, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        try {
            gp.intercept(HMessage.Direction.TOCLIENT, "CatalogIndex", m -> {
                try {
                    HCatalogIndex idx = new HCatalogIndex(new HPacket(m.getPacket()));
                    boolean bc = "BUILDERS_CLUB".equals(idx.getCatalogType());
                    int n = parcourir(idx.getRoot(), new ArrayList<>(), bc ? parOffreBc : parOffre);
                    System.out.println("[Atelier] répertoire : catalogue " + idx.getCatalogType()
                            + " lu, " + n + " mobis rattachés à une page.");
                    // Le catalogue peut revenir plus tard (ouvert dans le jeu) :
                    // on enregistre a chaque fois, hors du fil des paquets.
                    if (lu) {
                        Thread t = new Thread(() -> { sauver(); surMaj.run(); }, "atelier-repertoire-sauve");
                        t.setDaemon(true);
                        t.start();
                    }
                } catch (Throwable t) {
                    System.err.println("[Atelier] répertoire : index illisible : " + t);
                }
            });
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : écoute du catalogue impossible : " + t);
        }
    }

    /** Le catalogue normal ; celui du BC est deja demande par G-Presets. */
    private static void demanderCatalogue(GPresets gp) {
        try {
            gp.sendToServer(new HPacket("GetCatalogIndex", HMessage.Direction.TOSERVER, "NORMAL"));
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : demande du catalogue impossible : " + t);
        }
    }

    private static int parcourir(HCatalogPageIndex page, List<String> chemin,
                                 Map<Integer, List<String>> parOffre) {
        int n = 0;
        String nom = page.getLocalization();
        boolean nomme = nom != null && !nom.trim().isEmpty();
        if (nomme) chemin.add(nom.trim());
        if (page.getOfferIds() != null && nomme) {
            String c = String.join(" › ", chemin);
            String a = derniereAnnee(c);
            for (Integer offre : page.getOfferIds()) {
                List<String> cles = parOffre.get(offre);
                if (cles == null) continue;
                for (String k : cles) {
                    Entree e = mobis.computeIfAbsent(k, x -> new Entree());
                    // Un mobi peut figurer sur plusieurs pages : sa page d'origine
                    // et des pages de mise en avant (« Rentrée 2026 »). Une page
                    // datee l'emporte sur une page generale (« Polyfon »), et
                    // entre deux pages datees on garde la plus ancienne.
                    boolean plusAncienne = a != null && (!"catalogue".equals(e.source)
                            || e.annee == null || a.compareTo(e.annee) < 0);
                    if (e.page == null || plusAncienne) {
                        e.page = chemin.get(chemin.size() - 1);
                        e.chemin = c;
                    }
                    if (plusAncienne) {
                        e.annee = a;
                        e.source = "catalogue";
                    }
                    n++;
                }
            }
        }
        if (page.getChildren() != null)
            for (HCatalogPageIndex enfant : page.getChildren()) n += parcourir(enfant, chemin, parOffre);
        if (nomme) chemin.remove(chemin.size() - 1);
        return n;
    }

    static String derniereAnnee(String texte) {
        if (texte == null) return null;
        Matcher m = ANNEE.matcher(texte);
        String a = null;
        while (m.find()) a = m.group(1);
        return a;
    }

    // ------------------------------------------------------------ collection

    private static void completerParCollection(Map<String, Object[]> furni) {
        for (Map.Entry<String, Object[]> e : furni.entrySet()) {
            Entree en = mobis.get(e.getKey());
            if (en == null || "catalogue".equals(en.source)) continue;
            Object d = e.getValue()[2];
            String ligne = (d instanceof furnidata.details.FloorItemDetails)
                    ? ((furnidata.details.FloorItemDetails) d).furniline
                    : ((furnidata.details.WallItemDetails) d).furniline;
            // L'annee dans le nom de classe vaut aussi (xmas_c24_tree -> c24 n'est
            // PAS une annee fiable : on ne lit que les annees ecrites en entier).
            String a = derniereAnnee(ligne);
            if (a != null) { en.annee = a; en.source = "collection"; }
        }
    }

    // ------------------------------------------------------------- nouveautes

    /**
     * Un mobi qui vient d'apparaitre dans la furnidata est sorti maintenant.
     * On le confirme sur HabboTravel quand il le connait (date posterieure a
     * son import en masse), sinon on garde le jour ou on l'a vu arriver.
     */
    private static void completerNouveautes(List<String> nouveaux, Map<String, Object[]> furni) {
        int interroges = 0;
        for (String k : nouveaux) {
            Entree e = mobis.get(k);
            if (e == null || e.annee != null) continue;
            String a = null;
            if (interroges < 60) {
                interroges++;
                a = anneeHabboTravel((String) furni.get(k)[0]);
                try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
            }
            if (a == null) a = e.vuLe.substring(0, 4);
            e.annee = a;
            e.source = "nouveauté";
        }
    }

    static String anneeHabboTravel(String classe) {
        try {
            URL u = new URL("https://www.habbotravel.com/api/furni_info?name="
                    + java.net.URLEncoder.encode(classe, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Atelier (outil de build Habbo)");
            if (c.getResponseCode() != 200) return null;
            String html = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return anneeDepuisFiche(html);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lit « Date Found » dans la fiche HabboTravel ; null si absente ou anterieure a leur import. */
    static String anneeDepuisFiche(String html) {
        String texte = html.replaceAll("(?s)<(script|style).*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        Matcher m = Pattern.compile("Date Found (\\d{2})-(\\d{2})-(\\d{4})").matcher(texte);
        if (!m.find()) return null;
        LocalDate d = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(1)));
        return d.isAfter(IMPORT_HABBOTRAVEL) ? m.group(3) : null;
    }

    // ------------------------------------------------------------- estimation

    private static void estimer(Map<String, Object[]> furni) {
        // Points connus, separes sols / murs : les deux numerotations sont distinctes.
        TreeMap<Integer, Integer> sols = new TreeMap<>(), murs = new TreeMap<>();
        for (Map.Entry<String, Object[]> e : furni.entrySet()) {
            Entree en = mobis.get(e.getKey());
            if (en == null || en.annee == null || en.estimee()) continue;
            int id = idDe(e.getValue()[2]);
            if (id <= 0) continue;
            ((Boolean) e.getValue()[1] ? murs : sols).put(id, Integer.parseInt(en.annee));
        }
        for (Map.Entry<String, Object[]> e : furni.entrySet()) {
            Entree en = mobis.get(e.getKey());
            if (en == null || (en.annee != null && !en.estimee())) continue;
            Integer a = estimer(idDe(e.getValue()[2]), (Boolean) e.getValue()[1] ? murs : sols);
            if (a == null) continue;
            en.annee = String.valueOf(a);
            en.source = "estimation";
        }
    }

    /**
     * Mediane des annees des 5 voisins connus les plus proches par numero.
     * Hors des bornes connues, l'annee du point extreme. Logique pure.
     */
    static Integer estimer(int id, NavigableMap<Integer, Integer> connus) {
        if (id <= 0 || connus.size() < 5) return null;
        if (id <= connus.firstKey()) return connus.firstEntry().getValue();
        if (id >= connus.lastKey()) return connus.lastEntry().getValue();
        List<Integer> voisins = new ArrayList<>();
        Iterator<Map.Entry<Integer, Integer>> bas = connus.headMap(id, true).descendingMap().entrySet().iterator();
        Iterator<Map.Entry<Integer, Integer>> haut = connus.tailMap(id, false).entrySet().iterator();
        Map.Entry<Integer, Integer> b = bas.hasNext() ? bas.next() : null, h = haut.hasNext() ? haut.next() : null;
        while (voisins.size() < 5 && (b != null || h != null)) {
            if (h == null || (b != null && id - b.getKey() <= h.getKey() - id)) {
                voisins.add(b.getValue()); b = bas.hasNext() ? bas.next() : null;
            } else {
                voisins.add(h.getValue()); h = haut.hasNext() ? haut.next() : null;
            }
        }
        Collections.sort(voisins);
        return voisins.get(voisins.size() / 2);
    }

    private static int idDe(Object details) {
        if (details instanceof furnidata.details.FloorItemDetails) return ((furnidata.details.FloorItemDetails) details).id;
        if (details instanceof furnidata.details.WallItemDetails) return ((furnidata.details.WallItemDetails) details).id;
        return -1;
    }

    // ------------------------------------------------------------- stockage

    private static void charger() {
        if (!FICHIER.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(FICHIER.toPath()), StandardCharsets.UTF_8));
            JSONObject m = o.optJSONObject("mobis");
            if (m == null) return;
            for (String k : m.keySet()) {
                JSONObject j = m.getJSONObject(k);
                Entree e = new Entree();
                e.page = j.optString("page", null);
                e.chemin = j.optString("chemin", null);
                e.annee = j.optString("annee", null);
                e.source = j.optString("source", null);
                e.vuLe = j.optString("vuLe", LocalDate.now().toString());
                mobis.put(k, e);
            }
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : fichier illisible, il sera reconstruit : " + t);
        }
    }

    private static synchronized void sauver() {
        try {
            JSONObject m = new JSONObject();
            for (Map.Entry<String, Entree> en : new TreeMap<>(mobis).entrySet()) {
                Entree e = en.getValue();
                JSONObject j = new JSONObject();
                if (e.page != null) j.put("page", e.page);
                if (e.chemin != null) j.put("chemin", e.chemin);
                if (e.annee != null) j.put("annee", e.annee);
                if (e.source != null) j.put("source", e.source);
                j.put("vuLe", e.vuLe);
                m.put(en.getKey(), j);
            }
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("misAJour", LocalDate.now().toString());
            o.put("mobis", m);
            File dossier = FICHIER.getParentFile();
            if (!dossier.exists()) dossier.mkdirs();
            File tmp = new File(dossier, "mobis.json.tmp");
            Files.write(tmp.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FICHIER.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Atelier] répertoire : sauvegarde impossible : " + t);
        }
    }
}

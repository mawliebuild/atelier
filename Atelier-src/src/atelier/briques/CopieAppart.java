package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Une copie d'appart (ou d'une partie) : mobis au sol, muraux, reglages des
 * wired, liaisons, fonds publicitaires, disposition de la salle.
 *
 * Remplace PresetConfig, PresetFurni, PresetWallFurni, PresetWireds,
 * PresetWiredFurniBinding, PresetAdsBackground et RoomLayoutInfo. Lit et ecrit
 * le meme JSON que le format d'origine, pour relire les copies existantes :
 * <pre>
 * { "furni": [ {id, className, name, location{x,y,z}, rotation, state?, variables?} ],
 *   "wallFurni": [ {id, className, name, location{x,y,offsetX,offsetY,direction,altitude},
 *                   state?, srcFloorHeight?, variables?} ],            (absent si vide)
 *   "wired": { triggers, conditions, effects, addons, selectors, variables, variables_map },
 *   "bindings": [ {furniId, wiredId, location{x,y}?, rotation?, state?, altitude?} ],
 *   "adsBackgrounds": [ {furniId, imageUrl, offsetX, offsetY, offsetZ} ], (absent si vide)
 *   "roomLayout": {modelName?, floorplanWidth, floorplanHeight, scale, wallHeight, floorplanSignature?},
 *   "srcAnchorFloorHeight": 0.0 }
 * </pre>
 * Les cles inconnues du premier niveau (par exemple « atelierFloor ») sont
 * gardees telles quelles et reecrites.
 *
 * Objets modifiables, comme l'original ; copie() donne une copie profonde.
 */
final class CopieAppart {

    // ------------------------------------------------------------ elements

    /** Un mobi au sol (ex-PresetFurni). Position relative au coin de la copie. */
    static final class MobiSol {
        int id;
        String classe;
        /** « name » : nom lisible (la classe si la furnidata ne le connait pas). */
        String nom;
        int x, y;
        double z;
        int rotation;
        /** « state », ou null. */
        String etat;
        /** « variables » : nom ou id de variable -> valeur ; null ou vide = absent. */
        Map<String, Integer> variables;

        MobiSol(int id, String classe, int x, int y, double z, int rotation, String etat) {
            this.id = id; this.classe = classe; this.x = x; this.y = y; this.z = z;
            this.rotation = rotation; this.etat = etat;
        }

        MobiSol copie() {
            MobiSol m = new MobiSol(id, classe, x, y, z, rotation, etat);
            m.nom = nom;
            m.variables = variables == null ? null : new LinkedHashMap<>(variables);
            return m;
        }

        static MobiSol depuisJson(JSONObject o) {
            JSONObject l = o.getJSONObject("location");
            MobiSol m = new MobiSol(o.getInt("id"), o.getString("className"),
                    l.getInt("x"), l.getInt("y"), l.optDouble("z", 0), o.optInt("rotation"), texte(o, "state"));
            m.nom = texte(o, "name");
            m.variables = variablesDe(o);
            return m;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("className", classe);
            JSONObject l = new JSONObject();
            l.put("x", x);
            l.put("y", y);
            l.put("z", z);
            o.put("location", l);
            o.put("rotation", rotation);
            if (etat != null) o.put("state", etat);
            if (variables != null && !variables.isEmpty()) o.put("variables", new JSONObject(variables));
            if (nom != null) o.put("name", nom);
            return o;
        }
    }

    /** Un mural (ex-PresetWallFurni). */
    static final class MobiMur {
        int id;
        String classe;
        String nom;
        PositionMur position;
        String etat;
        /** « srcFloorHeight » : hauteur du sol sous le mural dans la salle d'origine, ou null. */
        Double hauteurSolOrigine;
        Map<String, Integer> variables;

        MobiMur(int id, String classe, PositionMur position, String etat) {
            this.id = id; this.classe = classe; this.position = position; this.etat = etat;
        }

        MobiMur copie() {
            MobiMur m = new MobiMur(id, classe, position, etat);
            m.nom = nom;
            m.hauteurSolOrigine = hauteurSolOrigine;
            m.variables = variables == null ? null : new LinkedHashMap<>(variables);
            return m;
        }

        static MobiMur depuisJson(JSONObject o) {
            JSONObject l = o.getJSONObject("location");
            String d = l.optString("direction", "l");
            PositionMur p = new PositionMur(l.getInt("x"), l.getInt("y"), l.optInt("offsetX"), l.optInt("offsetY"),
                    d.isEmpty() ? 'l' : d.charAt(0), l.optInt("altitude"));
            MobiMur m = new MobiMur(o.getInt("id"), o.getString("className"), p, texte(o, "state"));
            m.nom = texte(o, "name");
            if (o.has("srcFloorHeight") && !o.isNull("srcFloorHeight")) m.hauteurSolOrigine = o.getDouble("srcFloorHeight");
            m.variables = variablesDe(o);
            return m;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("className", classe);
            JSONObject l = new JSONObject();
            l.put("x", position.x());
            l.put("y", position.y());
            l.put("offsetX", position.decalageX());
            l.put("offsetY", position.decalageY());
            l.put("direction", String.valueOf(position.cote()));
            l.put("altitude", position.altitude());
            o.put("location", l);
            if (etat != null) o.put("state", etat);
            if (nom != null) o.put("name", nom);
            if (hauteurSolOrigine != null) o.put("srcFloorHeight", hauteurSolOrigine);
            if (variables != null && !variables.isEmpty()) o.put("variables", new JSONObject(variables));
            return o;
        }
    }

    /**
     * Une liaison d'un wired « instantane » a un mobi (ex-PresetWiredFurniBinding) :
     * position, rotation, etat et altitude que le mobi doit avoir quand on
     * enregistre le wired. Chaque champ facultatif est null s'il est absent.
     */
    static final class Liaison {
        int mobiId;
        int wiredId;
        Integer x, y;
        Integer rotation;
        String etat;
        Integer altitude;

        Liaison(int mobiId, int wiredId, Integer x, Integer y, Integer rotation, String etat, Integer altitude) {
            this.mobiId = mobiId; this.wiredId = wiredId; this.x = x; this.y = y;
            this.rotation = rotation; this.etat = etat; this.altitude = altitude;
        }

        Liaison copie() { return new Liaison(mobiId, wiredId, x, y, rotation, etat, altitude); }

        boolean aPosition() { return x != null && y != null; }

        static Liaison depuisJson(JSONObject o) {
            JSONObject l = o.optJSONObject("location");
            return new Liaison(o.getInt("furniId"), o.getInt("wiredId"),
                    l == null ? null : l.getInt("x"), l == null ? null : l.getInt("y"),
                    o.has("rotation") && !o.isNull("rotation") ? o.getInt("rotation") : null,
                    texte(o, "state"),
                    o.has("altitude") && !o.isNull("altitude") ? o.getInt("altitude") : null);
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("furniId", mobiId);
            o.put("wiredId", wiredId);
            if (aPosition()) {
                JSONObject l = new JSONObject();
                l.put("x", x);
                l.put("y", y);
                o.put("location", l);
            }
            if (rotation != null) o.put("rotation", rotation);
            if (etat != null) o.put("state", etat);
            if (altitude != null) o.put("altitude", altitude);
            return o;
        }
    }

    /** Le fond d'un mobi publicitaire (ads_background) (ex-PresetAdsBackground). */
    static final class FondPub {
        int mobiId;
        String image, decalageX, decalageY, decalageZ;

        FondPub(int mobiId, String image, String decalageX, String decalageY, String decalageZ) {
            this.mobiId = mobiId; this.image = image;
            this.decalageX = decalageX; this.decalageY = decalageY; this.decalageZ = decalageZ;
        }

        FondPub copie() { return new FondPub(mobiId, image, decalageX, decalageY, decalageZ); }

        /**
         * Lecture tolerante. Le format d'origine ecrivait cette liste en objets
         * Java convertis par org.json (par les accesseurs) : on accepte donc un
         * objet avec les cles normales, les nombres a la place des textes, un
         * texte qui contient un objet JSON ; sinon null (element ignore).
         */
        static FondPub depuisJson(Object brut) {
            JSONObject o = null;
            if (brut instanceof JSONObject) o = (JSONObject) brut;
            else if (brut instanceof Map) o = new JSONObject((Map<?, ?>) brut);
            else if (brut instanceof String) {
                String s = ((String) brut).trim();
                if (s.startsWith("{")) try { o = new JSONObject(s); } catch (RuntimeException ignored) { }
            }
            if (o == null || !o.has("furniId")) return null;
            try {
                return new FondPub(o.getInt("furniId"), o.optString("imageUrl", ""),
                        o.optString("offsetX", "0"), o.optString("offsetY", "0"), o.optString("offsetZ", "0"));
            } catch (RuntimeException e) {
                return null;
            }
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("furniId", mobiId);
            o.put("imageUrl", image == null ? "" : image);
            o.put("offsetX", decalageX == null ? "0" : decalageX);
            o.put("offsetY", decalageY == null ? "0" : decalageY);
            o.put("offsetZ", decalageZ == null ? "0" : decalageZ);
            return o;
        }
    }

    /** La forme de la salle d'origine (ex-RoomLayoutInfo). */
    static final class Disposition {
        /** « modelName », ou null. */
        final String modele;
        final int largeur, hauteur, echelle, hauteurMur;
        /** « floorplanSignature », ou null. */
        final String signature;

        Disposition(String modele, int largeur, int hauteur, int echelle, int hauteurMur, String signature) {
            this.modele = modele; this.largeur = largeur; this.hauteur = hauteur;
            this.echelle = echelle; this.hauteurMur = hauteurMur; this.signature = signature;
        }

        /** D'apres le plan brut de la salle (la signature en est calculee). */
        static Disposition depuisPlan(String modele, int largeur, int hauteur, int echelle, int hauteurMur, String planBrut) {
            return new Disposition(modele, largeur, hauteur, echelle, hauteurMur, signature(echelle, hauteurMur, planBrut));
        }

        static Disposition depuisJson(JSONObject o) {
            return new Disposition(o.has("modelName") && !o.isNull("modelName") ? o.optString("modelName") : null,
                    o.optInt("floorplanWidth", 0), o.optInt("floorplanHeight", 0),
                    o.optInt("scale", 0), o.optInt("wallHeight", 0),
                    o.has("floorplanSignature") && !o.isNull("floorplanSignature") ? o.optString("floorplanSignature") : null);
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            if (modele != null) o.put("modelName", modele);
            o.put("floorplanWidth", largeur);
            o.put("floorplanHeight", hauteur);
            o.put("scale", echelle);
            o.put("wallHeight", hauteurMur);
            if (signature != null) o.put("floorplanSignature", signature);
            return o;
        }

        /** Meme salle (ex-matches) : dimensions egales, signatures egales si les deux existent. */
        boolean correspond(Disposition autre) {
            if (autre == null) return false;
            if (largeur != autre.largeur || hauteur != autre.hauteur
                    || echelle != autre.echelle || hauteurMur != autre.hauteurMur) return false;
            return signature == null || autre.signature == null || signature.equals(autre.signature);
        }

        /** 16 premiers chiffres hexadecimaux du SHA-1 de « echelle|hauteurMur|plan », null sans plan. */
        static String signature(int echelle, int hauteurMur, String planBrut) {
            if (planBrut == null) return null;
            String entree = echelle + "|" + hauteurMur + "|" + planBrut;
            try {
                byte[] d = MessageDigest.getInstance("SHA-1").digest(entree.getBytes(StandardCharsets.UTF_8));
                StringBuilder b = new StringBuilder(d.length * 2);
                for (byte x : d) b.append(String.format("%02x", x));
                return b.substring(0, 16);
            } catch (Exception e) {
                return Integer.toHexString(entree.hashCode());
            }
        }
    }

    // ------------------------------------------------------------ la copie

    List<MobiSol> sols = new ArrayList<>();
    List<MobiMur> murs = new ArrayList<>();
    /** Les reglages des wired, par genre (toujours les six listes). */
    final Map<ReglageWired.Genre, List<ReglageWired>> wired = new EnumMap<>(ReglageWired.Genre.class);
    /** « variables_map » : id de variable dans la copie -> nom (ou id d'origine). */
    Map<String, String> tableVariables = new LinkedHashMap<>();
    List<Liaison> liaisons = new ArrayList<>();
    List<FondPub> fonds = new ArrayList<>();
    /** « roomLayout », ou null. */
    Disposition disposition;
    /** « srcAnchorFloorHeight » : hauteur du sol au coin d'origine, ou null. */
    Double ancre;
    /** Cles inconnues du premier niveau, gardees telles quelles. */
    JSONObject autres = new JSONObject();

    CopieAppart() {
        for (ReglageWired.Genre g : ReglageWired.Genre.values()) wired.put(g, new ArrayList<>());
    }

    List<ReglageWired> wired(ReglageWired.Genre g) { return wired.get(g); }

    /** Tous les reglages, dans l'ordre des genres. */
    List<ReglageWired> tousWired() {
        List<ReglageWired> l = new ArrayList<>();
        for (List<ReglageWired> x : wired.values()) l.addAll(x);
        return l;
    }

    /** Ajoute un reglage dans la liste de son genre. */
    void ajouter(ReglageWired r) { wired.get(r.genre).add(r); }

    CopieAppart copie() {
        CopieAppart c = new CopieAppart();
        for (MobiSol m : sols) c.sols.add(m.copie());
        for (MobiMur m : murs) c.murs.add(m.copie());
        for (Map.Entry<ReglageWired.Genre, List<ReglageWired>> e : wired.entrySet())
            for (ReglageWired r : e.getValue()) c.wired.get(e.getKey()).add(r.copie());
        c.tableVariables = new LinkedHashMap<>(tableVariables);
        for (Liaison l : liaisons) c.liaisons.add(l.copie());
        for (FondPub f : fonds) c.fonds.add(f.copie());
        c.disposition = disposition;
        c.ancre = ancre;
        c.autres = new JSONObject(autres.toString());
        return c;
    }

    // ------------------------------------------------------------ JSON

    private static final List<String> CONNUES = List.of(
            "furni", "wallFurni", "wired", "bindings", "adsBackgrounds", "roomLayout", "srcAnchorFloorHeight");

    /**
     * Lit une copie. « furni » est obligatoire (comme l'original) ; « wired » et
     * « bindings » absents donnent des listes vides (l'original refusait).
     * @throws org.json.JSONException si un element est illisible
     */
    static CopieAppart lire(JSONObject o) {
        CopieAppart c = new CopieAppart();
        JSONArray a = o.getJSONArray("furni");
        for (int i = 0; i < a.length(); i++) c.sols.add(MobiSol.depuisJson(a.getJSONObject(i)));
        a = o.optJSONArray("wallFurni");
        if (a != null) for (int i = 0; i < a.length(); i++) c.murs.add(MobiMur.depuisJson(a.getJSONObject(i)));
        JSONObject w = o.optJSONObject("wired");
        if (w != null) {
            for (ReglageWired.Genre g : ReglageWired.Genre.values()) {
                JSONArray l = w.optJSONArray(g.cle);
                if (l != null) for (int i = 0; i < l.length(); i++)
                    c.wired.get(g).add(ReglageWired.depuisJson(g, l.getJSONObject(i)));
            }
            JSONObject t = w.optJSONObject("variables_map");
            if (t != null) for (String k : t.keySet()) c.tableVariables.put(k, String.valueOf(t.get(k)));
        }
        a = o.optJSONArray("bindings");
        if (a != null) for (int i = 0; i < a.length(); i++) c.liaisons.add(Liaison.depuisJson(a.getJSONObject(i)));
        a = o.optJSONArray("adsBackgrounds");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            FondPub f = FondPub.depuisJson(a.isNull(i) ? null : a.get(i));
            if (f != null) c.fonds.add(f);
            else Journal.debug("Copie : fond publicitaire illisible ignoré : " + a.opt(i));
        }
        JSONObject d = o.optJSONObject("roomLayout");
        if (d != null) c.disposition = Disposition.depuisJson(d);
        if (o.has("srcAnchorFloorHeight") && !o.isNull("srcAnchorFloorHeight")) c.ancre = o.getDouble("srcAnchorFloorHeight");
        for (String k : o.keySet()) if (!CONNUES.contains(k)) c.autres.put(k, o.get(k));
        return c;
    }

    /** Lit le texte d'un fichier de copie. */
    static CopieAppart lire(String json) { return lire(new JSONObject(json)); }

    /** Le JSON du format d'origine (plus les cles inconnues gardees). */
    JSONObject json() {
        JSONObject o = new JSONObject();
        for (String k : autres.keySet()) o.put(k, autres.get(k));
        JSONArray a = new JSONArray();
        for (MobiSol m : sols) a.put(m.json());
        o.put("furni", a);
        if (!murs.isEmpty()) {
            a = new JSONArray();
            for (MobiMur m : murs) a.put(m.json());
            o.put("wallFurni", a);
        }
        JSONObject w = new JSONObject();
        for (ReglageWired.Genre g : ReglageWired.Genre.values()) {
            JSONArray l = new JSONArray();
            for (ReglageWired r : wired.get(g)) l.put(r.json());
            w.put(g.cle, l);
        }
        JSONObject t = new JSONObject();
        tableVariables.forEach(t::put);
        w.put("variables_map", t);
        o.put("wired", w);
        a = new JSONArray();
        for (Liaison l : liaisons) a.put(l.json());
        o.put("bindings", a);
        if (!fonds.isEmpty()) {
            a = new JSONArray();
            for (FondPub f : fonds) a.put(f.json());
            o.put("adsBackgrounds", a);
        }
        if (disposition != null) o.put("roomLayout", disposition.json());
        if (ancre != null) o.put("srcAnchorFloorHeight", ancre);
        return o;
    }

    /** Le texte du fichier (indentation 4, comme l'original). */
    String texte() { return json().toString(4); }

    // ------------------------------------------------------------ outils

    private static String texte(JSONObject o, String cle) {
        if (!o.has(cle) || o.isNull(cle)) return null;
        return String.valueOf(o.get(cle));
    }

    private static Map<String, Integer> variablesDe(JSONObject o) {
        JSONObject v = o.optJSONObject("variables");
        if (v == null) return null;
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : v.keySet()) m.put(k, v.getInt(k));
        return m;
    }
}

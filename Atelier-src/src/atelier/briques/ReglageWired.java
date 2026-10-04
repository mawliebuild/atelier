package atelier;

import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le reglage d'un wired : ce que l'on enregistre dans une copie et ce que l'on
 * renvoie au serveur pour le reconfigurer.
 *
 * Remplace PresetWiredBase et ses six sous-classes (Trigger, Condition, Effect,
 * Addon, Selector, Variable), et la lecture de RetrievedWired.fromPacket.
 * Le JSON est celui du format d'origine :
 *   wiredId, options[], config, items[], secondItems[], furniSources[],
 *   userSources[], variableIds[] ; plus delay (effet), quantifier (condition),
 *   filter / inverse (selecteur), variableId (variable).
 * Les paquets Update* gardent l'ordre exact des champs du format d'origine.
 *
 * Objet modifiable (comme l'original) ; copie() pour une copie profonde.
 */
final class ReglageWired {

    /** Le genre d'un wired : cle de la liste dans « wired », paquet de mise a jour. */
    enum Genre {
        DECLENCHEUR("triggers", "UpdateTrigger", "WiredFurniTrigger", "wf_trg_"),
        CONDITION("conditions", "UpdateCondition", "WiredFurniCondition", "wf_cnd_"),
        EFFET("effects", "UpdateAction", "WiredFurniAction", "wf_act_"),
        ADDON("addons", "UpdateAddon", "WiredFurniAddon", "wf_xtra_"),
        SELECTEUR("selectors", "UpdateSelector", "WiredFurniSelector", "wf_slc_"),
        VARIABLE("variables", "UpdateVariable", "WiredFurniVariable", "wf_var_");

        /** Cle de la liste dans l'objet « wired » du JSON. */
        final String cle;
        /** Paquet sortant qui enregistre le reglage. */
        final String paquet;
        /** Paquet entrant qui decrit le reglage (ouverture du wired). */
        final String paquetEntrant;
        /** Debut de la classe des mobis de ce genre. */
        final String prefixe;

        Genre(String cle, String paquet, String paquetEntrant, String prefixe) {
            this.cle = cle;
            this.paquet = paquet;
            this.paquetEntrant = paquetEntrant;
            this.prefixe = prefixe;
        }

        /** Le genre d'apres la classe d'un mobi (wf_trg_..., wf_cnd_...), ou null. */
        static Genre deClasse(String classe) {
            if (classe == null) return null;
            for (Genre g : values()) if (classe.startsWith(g.prefixe)) return g;
            return null;
        }

        /** Le genre d'apres la cle JSON (« triggers »...), ou null. */
        static Genre deCle(String cle) {
            for (Genre g : values()) if (g.cle.equals(cle)) return g;
            return null;
        }
    }

    Genre genre;
    int wiredId;
    List<Integer> options = new ArrayList<>();
    /** « config » : le texte du wired. */
    String texte = "";
    List<Integer> items = new ArrayList<>();
    /** « secondItems ». */
    List<Integer> items2 = new ArrayList<>();
    /** « furniSources ». */
    List<Integer> sourcesMobis = new ArrayList<>();
    /** « userSources ». */
    List<Integer> sourcesAvatars = new ArrayList<>();
    /** « variableIds ». */
    List<String> variables = new ArrayList<>();
    /** Effet : « delay ». */
    int delai;
    /** Condition : « quantifier ». */
    int quantificateur;
    /** Selecteur : « filter », « inverse ». */
    boolean filtre, inverse;
    /** Variable : « variableId » (null s'il vient d'un paquet sortant, comme l'original). */
    String variableId;
    /** Type du wired dans le jeu (lu dans le paquet entrant, 0 sinon ; pas dans le JSON). */
    int typeId;

    ReglageWired(Genre genre) { this.genre = genre; }

    ReglageWired copie() {
        ReglageWired r = new ReglageWired(genre);
        r.wiredId = wiredId;
        r.options = new ArrayList<>(options);
        r.texte = texte;
        r.items = new ArrayList<>(items);
        r.items2 = new ArrayList<>(items2);
        r.sourcesMobis = new ArrayList<>(sourcesMobis);
        r.sourcesAvatars = new ArrayList<>(sourcesAvatars);
        r.variables = new ArrayList<>(variables);
        r.delai = delai;
        r.quantificateur = quantificateur;
        r.filtre = filtre;
        r.inverse = inverse;
        r.variableId = variableId;
        r.typeId = typeId;
        return r;
    }

    // ------------------------------------------------------------ JSON

    /**
     * Lit un reglage du JSON d'une copie. Plus tolerant que l'original : les
     * listes absentes sont vides, « delay » absent vaut 0 (l'original refusait).
     */
    static ReglageWired depuisJson(Genre genre, JSONObject o) {
        ReglageWired r = new ReglageWired(genre);
        r.wiredId = o.getInt("wiredId");
        r.options = entiers(o.optJSONArray("options"));
        r.texte = o.optString("config", "");
        r.items = entiers(o.optJSONArray("items"));
        r.items2 = entiers(o.optJSONArray("secondItems"));
        r.sourcesMobis = entiers(o.optJSONArray("furniSources"));
        r.sourcesAvatars = entiers(o.optJSONArray("userSources"));
        r.variables = textes(o.optJSONArray("variableIds"));
        switch (genre) {
            case EFFET: r.delai = o.optInt("delay"); break;
            case CONDITION: r.quantificateur = o.optInt("quantifier"); break;
            case SELECTEUR: r.filtre = o.optBoolean("filter"); r.inverse = o.optBoolean("inverse"); break;
            case VARIABLE: r.variableId = o.has("variableId") && !o.isNull("variableId") ? String.valueOf(o.get("variableId")) : ""; break;
            default: break;
        }
        return r;
    }

    /** Le JSON du format d'origine (memes cles, memes champs propres au genre). */
    JSONObject json() {
        JSONObject o = new JSONObject();
        o.put("wiredId", wiredId);
        o.put("options", new JSONArray(options));
        o.put("config", texte == null ? "" : texte);
        o.put("items", new JSONArray(items));
        o.put("secondItems", new JSONArray(items2));
        o.put("furniSources", new JSONArray(sourcesMobis));
        o.put("userSources", new JSONArray(sourcesAvatars));
        o.put("variableIds", new JSONArray(variables));
        switch (genre) {
            case EFFET: o.put("delay", delai); break;
            case CONDITION: o.put("quantifier", quantificateur); break;
            case SELECTEUR: o.put("filter", filtre); o.put("inverse", inverse); break;
            case VARIABLE: if (variableId != null) o.put("variableId", variableId); break;
            default: break;
        }
        return o;
    }

    static List<Integer> entiers(JSONArray a) {
        List<Integer> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.getInt(i));
        return l;
    }

    static List<String> textes(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.isNull(i) ? "" : String.valueOf(a.get(i)));
        return l;
    }

    // ------------------------------------------------------------ paquets

    /**
     * Lit un paquet sortant Update* (ce que le jeu envoie quand on enregistre un
     * wired) : wiredId, options, texte, items, partie propre au genre, sources
     * mobis, sources avatars, variables, items2.
     */
    static ReglageWired lireSortant(Genre genre, HPacket p) {
        ReglageWired r = new ReglageWired(genre);
        r.wiredId = p.readInteger();
        r.options = lireEntiers(p);
        r.texte = p.readString();
        r.items = lireEntiers(p);
        switch (genre) {
            case EFFET: r.delai = p.readInteger(); break;
            case CONDITION: r.quantificateur = p.readInteger(); break;
            case SELECTEUR: r.filtre = p.readBoolean(); r.inverse = p.readBoolean(); break;
            default: break;
        }
        r.sourcesMobis = lireEntiers(p);
        r.sourcesAvatars = lireEntiers(p);
        r.variables = lireTextes(p);
        r.items2 = lireEntiers(p);
        return r;
    }

    /**
     * Lit un paquet entrant WiredFurni* (reponse a l'ouverture d'un wired),
     * jusqu'au contexte exclu : int ignore, items, items2, typeId, wiredId,
     * texte, options, variables, sources mobis, sources avatars, int ignore,
     * partie propre au genre. La suite (mode avance, sources permises, contexte
     * des variables) n'est pas lue : variableId reste « » pour une variable,
     * a retrouver par son nom dans le contexte.
     */
    static ReglageWired lireEntrant(Genre genre, HPacket p) {
        ReglageWired r = new ReglageWired(genre);
        p.readInteger();
        r.items = lireEntiers(p);
        r.items2 = lireEntiers(p);
        r.typeId = p.readInteger();
        r.wiredId = p.readInteger();
        r.texte = p.readString();
        r.options = lireEntiers(p);
        r.variables = lireTextes(p);
        r.sourcesMobis = lireEntiers(p);
        r.sourcesAvatars = lireEntiers(p);
        p.readInteger();
        switch (genre) {
            case EFFET: r.delai = p.readInteger(); break;
            case CONDITION: r.quantificateur = p.readInteger(); break;
            case SELECTEUR: r.filtre = p.readBoolean(); r.inverse = p.readBoolean(); break;
            case VARIABLE: r.variableId = ""; break;
            default: break;
        }
        return r;
    }

    /** Le paquet Update* qui enregistre ce reglage (a envoyer au serveur). */
    HPacket paquetUpdate() {
        HPacket p = new HPacket(genre.paquet, HMessage.Direction.TOSERVER, wiredId);
        ecrireEntiers(p, options);
        p.appendString(texte == null ? "" : texte);
        ecrireEntiers(p, items);
        switch (genre) {
            case EFFET: p.appendInt(delai); break;
            case CONDITION: p.appendInt(quantificateur); break;
            case SELECTEUR: p.appendBoolean(filtre); p.appendBoolean(inverse); break;
            default: break;
        }
        ecrireEntiers(p, sourcesMobis);
        ecrireEntiers(p, sourcesAvatars);
        p.appendInt(variables.size());
        for (String v : variables) p.appendString(v);
        ecrireEntiers(p, items2);
        return p;
    }

    private static List<Integer> lireEntiers(HPacket p) {
        int n = p.readInteger();
        List<Integer> l = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
        for (int i = 0; i < n; i++) l.add(p.readInteger());
        return l;
    }

    private static List<String> lireTextes(HPacket p) {
        int n = p.readInteger();
        List<String> l = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
        for (int i = 0; i < n; i++) l.add(p.readString());
        return l;
    }

    private static void ecrireEntiers(HPacket p, List<Integer> l) {
        p.appendInt(l.size());
        for (int v : l) p.appendInt(v);
    }

    // ------------------------------------------------------------ pose

    /**
     * Le reglage pour la salle de destination (ex-applyWiredConfig, sans l'envoi) :
     * wiredId, items et items2 passent des ids de la copie aux ids reels (ceux qui
     * manquent sont retires) ; les variables passent par la table des variables
     * (« 0 » et les ids negatifs, variables internes, restent tels quels).
     * Comme l'original, une variable qui a un seul id s'ajoute a la table
     * (id -> lui-meme) et sert de valeur par defaut ; sinon le defaut est « 0 ».
     *
     * @return la copie traduite, ou null si le wired lui-meme n'a pas d'id reel
     */
    ReglageWired traduire(Map<Integer, Integer> ids, Map<String, String> tableVariables) {
        Integer reel = ids.get(wiredId);
        if (reel == null) return null;
        ReglageWired r = copie();
        r.wiredId = reel;
        r.items = traduireIds(items, ids);
        r.items2 = traduireIds(items2, ids);
        String defaut = "0";
        if (genre == Genre.VARIABLE && variables.size() == 1) {
            defaut = variables.get(0);
            tableVariables.put(defaut, defaut);
        }
        List<String> v = new ArrayList<>(variables.size());
        for (String id : variables)
            v.add(id.equals("0") || id.startsWith("-") ? id : tableVariables.getOrDefault(id, defaut));
        r.variables = v;
        return r;
    }

    private static List<Integer> traduireIds(List<Integer> l, Map<Integer, Integer> ids) {
        List<Integer> r = new ArrayList<>(l.size());
        for (Integer i : l) { Integer x = ids.get(i); if (x != null) r.add(x); }
        return r;
    }

    @Override public String toString() {
        return genre + " " + wiredId + " " + json();
    }
}

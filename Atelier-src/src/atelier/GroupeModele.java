package atelier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Logique PURE des calques facon Photoshop (aucun JavaFX, aucun reseau) :
 * qui appartient a quel calque, l'ordre, les verrous, et le fichier JSON.
 *
 * Calques de BASE, toujours la, tout en bas de la liste (jamais stockes comme
 * calques) :
 *   « Mobis » (MOBIS)  le COMPLEMENT : tous les mobis de la salle (sols et
 *                      murs) qui ne sont dans aucun autre calque. Ne se
 *                      supprime pas, ne se renomme pas ; se masque, se verrouille.
 *   « Mur »   (MUR)    les murs de l'appart : verrouille, se masque seulement.
 *   « Sol »   (SOL)    le sol de l'appart : verrouille.
 * Les autres : un nom + des ids de sols + des ids de murs. Un mobi n'est que
 * dans UN calque : l'y mettre le sort des autres. Ordre de la liste = ordre
 * affiche, du haut vers le bas ; les calques de base sont toujours dessous.
 *
 * Les ids de sols et de murs sont deux espaces separes (un sol et un mur
 * peuvent porter le meme numero).
 *
 * Fichier (voir GroupeStockage pour le dossier : <idAppart>.json) :
 *   {"version":3, "salle":123, "prochain":4, "caches":["c1","mur"],
 *    "verrous":["c2","mobis"],
 *    "calques":[{"id":"c1","nom":"Toit","sols":[..],"murs":[..]}, ...]}
 * Une version 1 ou 2 se lit telle quelle (sans verrou) ; l'ancien « 0 » des
 * calques masques (le calque 0 = murs et sol) devient « mur ».
 *
 * Non thread-safe : Groupes synchronise.
 */
final class GroupeModele {

    private GroupeModele() { }

    static final String MOBIS = "mobis", MUR = "mur", SOL = "sol";
    static final String NOM_MOBIS = "Mobis", NOM_MUR = "Mur", NOM_SOL = "Sol";
    /** Ancien identifiant du calque 0 (fichiers version 2). */
    static final String ANCIEN0 = "0";
    /** Au-dela, supprimer un calque (donc ramasser ses mobis) demande confirmation. */
    static final int SEUIL_CONFIRMATION = 50;

    static boolean estBase(String id) { return MOBIS.equals(id) || MUR.equals(id) || SOL.equals(id); }

    /** Mur et Sol : rien a faire d'autre que les masquer. */
    static boolean estDecor(String id) { return MUR.equals(id) || SOL.equals(id); }

    static String nomBase(String id) {
        return MOBIS.equals(id) ? NOM_MOBIS : MUR.equals(id) ? NOM_MUR : SOL.equals(id) ? NOM_SOL : null;
    }

    /** Supprimer ramasse les mobis : confirmation courte s'il y a des wired ou beaucoup de mobis. */
    static boolean confirmationVoulue(int mobis, int wired) { return wired > 0 || mobis > SEUIL_CONFIRMATION; }

    // ------------------------------------------------------------ calque

    static final class Calque {
        final String id;
        String nom;
        final LinkedHashSet<Integer> sols = new LinkedHashSet<>();
        final LinkedHashSet<Integer> murs = new LinkedHashSet<>();

        Calque(String id, String nom) { this.id = id; this.nom = nom; }

        int nombre() { return sols.size() + murs.size(); }

        Set<Integer> ids(boolean mural) { return mural ? murs : sols; }

        boolean contient(int id, boolean mural) { return ids(mural).contains(id); }

        @Override public String toString() { return nom + " (" + id + ", " + sols.size() + " sols, " + murs.size() + " murs)"; }
    }

    /** Bilan d'une fusion de plusieurs calques. */
    static final class Fusion {
        final String cible;
        final List<String> sources;
        final int mobis;
        Fusion(String cible, List<String> sources, int mobis) { this.cible = cible; this.sources = sources; this.mobis = mobis; }
    }

    // -------------------------------------------------------------- plan

    /** Les calques d'une salle. */
    static final class Plan {
        final int salle;
        final List<Calque> calques = new ArrayList<>();
        int prochain = 1;
        /** Calques masques voulus (ids, calques de base compris) : enregistres avec le reste. */
        final LinkedHashSet<String> caches = new LinkedHashSet<>();
        /** Calques verrouilles (ids des calques et MOBIS ; Mur et Sol le sont toujours). */
        final LinkedHashSet<String> verrous = new LinkedHashSet<>();

        Plan(int salle) { this.salle = salle; }

        Calque calque(String id) {
            if (id == null) return null;
            for (Calque c : calques) if (c.id.equals(id)) return c;
            return null;
        }

        /** Le calque existe (calque de base compris). */
        boolean existe(String id) { return estBase(id) || calque(id) != null; }

        int index(String id) {
            for (int i = 0; i < calques.size(); i++) if (calques.get(i).id.equals(id)) return i;
            return -1;
        }

        String nom(String id) {
            String b = nomBase(id);
            if (b != null) return b;
            Calque c = calque(id);
            return c == null ? null : c.nom;
        }

        /** Le calque d'un mobi : son id, ou MOBIS s'il n'est dans aucun. */
        String calqueDe(int id, boolean mural) {
            for (Calque c : calques) if (c.contient(id, mural)) return c.id;
            return MOBIS;
        }

        // ------------------------------------------------------ verrous

        boolean verrouille(String id) {
            if (estDecor(id)) return true;
            return id != null && verrous.contains(id);
        }

        /** Verrouille / deverrouille. @return vrai si ca change quelque chose */
        boolean verrouiller(String id, boolean v) {
            if (id == null || estDecor(id) || !existe(id)) return false;
            return v ? verrous.add(id) : verrous.remove(id);
        }

        /** Les calques verrouilles qui contiennent au moins un de ces mobis (MOBIS compris). */
        List<String> verrouillesTouches(Collection<Integer> sols, Collection<Integer> murs) {
            LinkedHashSet<String> r = new LinkedHashSet<>();
            if (sols != null) for (Integer s : sols) if (s != null) { String c = calqueDe(s, false); if (verrouille(c)) r.add(c); }
            if (murs != null) for (Integer m : murs) if (m != null) { String c = calqueDe(m, true); if (verrouille(c)) r.add(c); }
            return new ArrayList<>(r);
        }

        // -------------------------------------------------------- noms

        /**
         * Le numero du prochain « Calque N » : le plus grand N des calques qui
         * EXISTENT + 1 (on commence a 1). Supprimer le dernier calque libere
         * donc son numero. Les ids internes (« cN »), eux, ne sont jamais
         * reutilises (les masques et les verrous suivent les ids).
         */
        int prochainNumero() {
            int max = 0;
            for (Calque c : calques) max = Math.max(max, numero(c.nom));
            return max + 1;
        }

        /** « Calque 7 » -> 7 ; tout autre nom -> 0. */
        static int numero(String nom) {
            if (nom == null) return 0;
            String n = nom.trim();
            if (n.length() < 8 || !n.regionMatches(true, 0, "Calque ", 0, 7)) return 0;
            try { return Math.max(0, Integer.parseInt(n.substring(7).trim())); }
            catch (NumberFormatException e) { return 0; }
        }

        /** Un nom pas encore pris : « base », sinon « base 2 », « base 3 »... Sans base : « Calque N ». */
        String nomLibre(String base) {
            if (base == null || base.isBlank()) {
                for (int i = prochainNumero(); ; i++) if (!nomPris("Calque " + i)) return "Calque " + i;
            }
            String b = base.trim();
            if (!nomPris(b)) return b;
            for (int i = 2; ; i++) if (!nomPris(b + " " + i)) return b + " " + i;
        }

        private boolean nomPris(String n) {
            if (NOM_MOBIS.equalsIgnoreCase(n) || NOM_MUR.equalsIgnoreCase(n) || NOM_SOL.equalsIgnoreCase(n)) return true;
            for (Calque c : calques) if (c.nom.equalsIgnoreCase(n)) return true;
            return false;
        }

        // ------------------------------------------------------ edition

        /**
         * Nouveau calque en haut de la liste (ou juste au-dessus de « dessus »
         * si donne ; MOBIS = juste au-dessus des calques de base), avec ces
         * mobis retires de leurs anciens calques.
         * @return son id
         */
        String creer(String nom, Collection<Integer> sols, Collection<Integer> murs, String dessus) {
            String n = nomLibre(nom);                    // avant prochain++ : le nom ne depend pas de l'id
            Calque c = new Calque("c" + (prochain++), n);
            int i = dessus == null ? 0 : estBase(dessus) ? calques.size() : Math.max(0, index(dessus));
            calques.add(Math.min(i, calques.size()), c);
            attribuer(c, sols, murs);
            return c.id;
        }

        /** Ajoute des mobis a un calque (MOBIS = les rendre au complement). false si calque inconnu. */
        boolean ajouter(String id, Collection<Integer> sols, Collection<Integer> murs) {
            if (MOBIS.equals(id)) { retirer(sols, murs); return true; }
            Calque c = calque(id);
            if (c == null) return false;
            attribuer(c, sols, murs);
            return true;
        }

        /** Rend ces mobis au calque Mobis. @return nombre retires d'un calque */
        int retirer(Collection<Integer> sols, Collection<Integer> murs) {
            int n = 0;
            for (Calque c : calques) {
                if (sols != null) for (Integer s : sols) if (s != null && c.sols.remove(s)) n++;
                if (murs != null) for (Integer m : murs) if (m != null && c.murs.remove(m)) n++;
            }
            return n;
        }

        private void attribuer(Calque cible, Collection<Integer> sols, Collection<Integer> murs) {
            for (Calque c : calques) {
                if (c == cible) continue;
                if (sols != null) c.sols.removeAll(sols);
                if (murs != null) c.murs.removeAll(murs);
            }
            if (sols != null) for (Integer s : sols) if (s != null) cible.sols.add(s);
            if (murs != null) for (Integer m : murs) if (m != null) cible.murs.add(m);
        }

        /** Les calques de base ne se renomment pas. */
        boolean renommer(String id, String nom) {
            Calque c = calque(id);
            if (c == null || nom == null || nom.isBlank()) return false;
            String n = nom.trim();
            if (n.equalsIgnoreCase(c.nom)) { c.nom = n; return true; }
            c.nom = nomLibre(n);
            return true;
        }

        /** sens -1 = monter (vers le haut de la liste), +1 = descendre. Calques de base : fixes. */
        boolean bouger(String id, int sens) {
            int i = index(id);
            int j = i + Integer.signum(sens);
            if (i < 0 || j < 0 || j >= calques.size()) return false;
            Collections.swap(calques, i, j);
            return true;
        }

        /** Met le calque a la place voulue (0 = en haut ; au-dela : juste au-dessus des calques de base). */
        boolean placer(String id, int place) {
            int i = index(id);
            if (i < 0) return false;
            Calque c = calques.remove(i);
            calques.add(Math.max(0, Math.min(place, calques.size())), c);
            return i != calques.indexOf(c);
        }

        /**
         * Pourquoi ces calques ne peuvent pas etre supprimes (null = ils le
         * peuvent tous). Calques de base et calques verrouilles refusent.
         */
        String refusSuppression(Collection<String> ids) {
            if (ids == null || ids.isEmpty()) return "Choisis d'abord un calque.";
            for (String id : ids) {
                if (estBase(id)) return "« " + nomBase(id) + " » est un calque de base : il ne se supprime pas.";
                Calque c = calque(id);
                if (c == null) return "Calque introuvable.";
                if (verrouille(id)) return "« " + c.nom + " » est verrouillé : clique son cadenas pour le déverrouiller.";
            }
            return null;
        }

        /** Supprime le calque (pas ses mobis : c'est Groupes qui les ramasse). */
        Calque supprimer(String id) {
            int i = index(id);
            if (i < 0) return null;
            caches.remove(id);
            verrous.remove(id);
            return calques.remove(i);
        }

        /**
         * Fusion : tous les mobis de « source » passent dans « cible » (a la
         * suite des siens), puis « source » disparait. Cible MOBIS : les mobis
         * retournent au complement. Calques verrouilles, Mur et Sol : refuse.
         * @return nombre de mobis passes dans la cible, -1 si impossible
         */
        int fusionner(String source, String cible) {
            if (source == null || cible == null || source.equals(cible) || estBase(source) || estDecor(cible)) return -1;
            if (verrouille(source) || verrouille(cible)) return -1;
            Calque s = calque(source);
            if (s == null) return -1;
            if (MOBIS.equals(cible)) {
                int n = s.nombre();
                supprimer(source);
                return n;
            }
            Calque c = calque(cible);
            if (c == null) return -1;
            int n = s.nombre();
            c.sols.addAll(s.sols);
            c.murs.addAll(s.murs);
            supprimer(source);
            return n;
        }

        /**
         * Ou va une fusion de ces calques (« Fusionner les calques » de
         * Photoshop) : dans Mobis s'il en fait partie, sinon dans le plus haut.
         * Un seul calque : avec celui du dessous (Mobis pour le dernier).
         * Mur et Sol sont ignores. null si rien a fusionner.
         */
        String cibleFusion(Collection<String> ids) {
            List<String> l = new ArrayList<>();
            for (String id : ids) if (id != null && !estDecor(id) && existe(id) && !l.contains(id)) l.add(id);
            if (l.isEmpty()) return null;
            if (l.contains(MOBIS)) return l.size() >= 2 ? MOBIS : null;
            if (l.size() == 1) {
                int i = index(l.get(0));
                return i + 1 < calques.size() ? calques.get(i + 1).id : MOBIS;
            }
            String haut = null;
            int min = Integer.MAX_VALUE;
            for (String id : l) { int i = index(id); if (i >= 0 && i < min) { min = i; haut = id; } }
            return haut;
        }

        /** Pourquoi la fusion de ces calques est impossible (null = possible). */
        String refusFusion(Collection<String> ids) {
            String cible = cibleFusion(ids);
            if (cible == null) return "Choisis au moins deux calques (Cmd ou Ctrl + clic), ou un calque à fusionner avec celui du dessous.";
            List<String> tous = new ArrayList<>(ids);
            if (ids.size() == 1) tous.add(cible);
            for (String id : tous) {
                if (estDecor(id)) continue;
                if (verrouille(id)) return "« " + nom(id) + " » est verrouillé : déverrouille-le pour fusionner.";
            }
            return null;
        }

        /** Fusionne ces calques (voir cibleFusion). null si impossible. */
        Fusion fusionnerTous(Collection<String> ids) {
            if (refusFusion(ids) != null) return null;
            String cible = cibleFusion(ids);
            List<String> sources = new ArrayList<>();
            int n = 0;
            List<String> l = new ArrayList<>(ids);
            if (l.size() == 1) { sources.add(l.get(0)); }
            else for (String id : l) if (!id.equals(cible) && !estBase(id) && calque(id) != null && !sources.contains(id)) sources.add(id);
            for (String s : sources) {
                int k = fusionner(s, cible);
                if (k >= 0) n += k;
            }
            return new Fusion(cible, sources, n);
        }

        /** Note qu'un calque est masque ou non. @return vrai si ca change quelque chose */
        boolean cacher(String id, boolean cache) {
            if (id == null || !existe(id)) return false;
            return cache ? caches.add(id) : caches.remove(id);
        }

        boolean cache(String id) { return id != null && caches.contains(id); }

        /** Le complement : les ids de la salle qui ne sont dans aucun calque (le calque Mobis). */
        List<Integer> complement(Collection<Integer> salle, boolean mural) {
            Set<Integer> pris = new HashSet<>();
            for (Calque c : calques) pris.addAll(c.ids(mural));
            List<Integer> r = new ArrayList<>();
            for (Integer id : salle) if (id != null && !pris.contains(id)) r.add(id);
            return r;
        }

        /**
         * Oublie les ids qui ne sont plus dans la salle, sauf ceux que garder
         * accepte (mobis masques par nous : absents chez le client mais pas du
         * serveur). @return nombre d'ids oublies
         */
        int nettoyer(Set<Integer> solsSalle, Set<Integer> mursSalle, BiPredicate<Integer, Boolean> garder) {
            int n = 0;
            for (Calque c : calques) {
                for (Iterator<Integer> it = c.sols.iterator(); it.hasNext(); ) {
                    int id = it.next();
                    if (!solsSalle.contains(id) && (garder == null || !garder.test(id, false))) { it.remove(); n++; }
                }
                for (Iterator<Integer> it = c.murs.iterator(); it.hasNext(); ) {
                    int id = it.next();
                    if (!mursSalle.contains(id) && (garder == null || !garder.test(id, true))) { it.remove(); n++; }
                }
            }
            return n;
        }

        // ---------------------------------------------------------- JSON

        JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("version", 3);
            o.put("salle", salle);
            o.put("prochain", prochain);
            o.put("caches", new JSONArray(caches));
            o.put("verrous", new JSONArray(verrous));
            JSONArray a = new JSONArray();
            for (Calque c : calques) {
                JSONObject j = new JSONObject();
                j.put("id", c.id);
                j.put("nom", c.nom);
                j.put("sols", new JSONArray(c.sols));
                j.put("murs", new JSONArray(c.murs));
                a.put(j);
            }
            o.put("calques", a);
            return o;
        }

        String texte() { return json().toString(1); }

        /**
         * Relit un fichier ; les doublons (un mobi dans deux calques) sont
         * laisses au premier. La salle est TOUJOURS celle attendue (celle du
         * nom du fichier) : un « salle » different dans le texte ne doit pas
         * faire recharger le plan a chaque appel.
         */
        static Plan lire(String texte, int salleAttendue) {
            JSONObject o = new JSONObject(texte);
            Plan p = new Plan(salleAttendue);
            p.prochain = Math.max(1, o.optInt("prochain", 1));
            JSONArray a = o.optJSONArray("calques");
            Set<Integer> vusS = new HashSet<>(), vusM = new HashSet<>();
            Set<String> ids = new HashSet<>();
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                String id = j.optString("id", "");
                if (id.isEmpty() || ANCIEN0.equals(id) || estBase(id) || !ids.add(id)) {
                    // id regenere : jamais un id deja lu, et retenu pour les suivants
                    do id = "c" + (p.prochain++); while (!ids.add(id));
                }
                Calque c = new Calque(id, j.optString("nom", "Calque"));
                JSONArray s = j.optJSONArray("sols"), m = j.optJSONArray("murs");
                if (s != null) for (int k = 0; k < s.length(); k++) { int v = s.optInt(k, 0); if (v != 0 && vusS.add(v)) c.sols.add(v); }
                if (m != null) for (int k = 0; k < m.length(); k++) { int v = m.optInt(k, 0); if (v != 0 && vusM.add(v)) c.murs.add(v); }
                p.calques.add(c);
                // « prochain » doit rester au-dessus de tous les ids lus
                if (id.startsWith("c")) try { p.prochain = Math.max(p.prochain, Integer.parseInt(id.substring(1)) + 1); }
                catch (NumberFormatException ignored) { }
            }
            // un ancien calque qui porterait le nom d'un calque de base
            for (Calque c : p.calques)
                if (nomBase(c.id) == null && (NOM_MOBIS.equalsIgnoreCase(c.nom) || NOM_MUR.equalsIgnoreCase(c.nom)
                        || NOM_SOL.equalsIgnoreCase(c.nom))) c.nom = c.nom + " 2";
            JSONArray k = o.optJSONArray("caches");
            if (k != null) for (int i = 0; i < k.length(); i++) {
                String id = k.optString(i, "");
                if (ANCIEN0.equals(id)) id = MUR;                // version 2 : le calque 0 = murs et sol
                if (SOL.equals(id)) continue;                    // le sol ne se masque pas
                if (p.existe(id)) p.caches.add(id);
            }
            JSONArray v = o.optJSONArray("verrous");
            if (v != null) for (int i = 0; i < v.length(); i++) {
                String id = v.optString(i, "");
                if (!estDecor(id) && p.existe(id)) p.verrous.add(id);
            }
            return p;
        }
    }
}

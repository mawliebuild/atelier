package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import java.util.*;

/**
 * Photographie des wired de la salle : piles, liens entre piles, problemes,
 * et recherches. Construite a partir de la salle (Salle.sols) et des
 * configurations deja lues (WiredLecteur).
 *
 * Une pile = les wired poses sur une meme case (x,y) : c'est un bloc de regle.
 */
public final class WiredAnalyse {

    // ------------------------------------------------------------------ modele

    /** Un wired de la salle. */
    public static final class Fil {
        public final int id;
        public final String classe, nom;
        public final Wired.Rang rang;
        public final int x, y;
        public final double z;
        /** null si pas (encore) lu */
        public final WiredLecteur.Config conf;
        public final boolean illisible;
        /** Pourquoi il est illisible (« pas de réponse du serveur après 3 essais »...), null sinon. */
        public final String raison;
        Pile pile;

        Fil(HFloorItem it, String classe) {
            id = it.getId();
            this.classe = classe;
            nom = nomLisible(classe);
            rang = Wired.rang(classe);
            x = it.getTile().getX();
            y = it.getTile().getY();
            z = it.getTile().getZ();
            conf = WiredLecteur.config(id);
            illisible = conf == null && WiredLecteur.illisible(id);
            raison = illisible ? WiredLecteur.raison(id) : null;
        }

        public boolean estEffet() {
            return rang == Wired.Rang.EFFET || rang == Wired.Rang.EFFET_SIGNAL
                    || rang == Wired.Rang.EFFET_NEGATIF;
        }
        public boolean estSelecteur() {
            return rang == Wired.Rang.SELECTEUR || rang == Wired.Rang.SELECTEUR_FILTRE;
        }
        public boolean estSignal() {
            return classe != null && classe.toLowerCase(Locale.ROOT).equals("wf_act_send_signal");
        }
        public boolean recoitSignal() {
            return rang == Wired.Rang.DECLENCHEUR && classe != null
                    && classe.toLowerCase(Locale.ROOT).contains("signal");
        }
        public List<Integer> items() { return conf == null ? List.of() : conf.items; }
        public Set<Integer> tousLesMobis() { return conf == null ? Set.of() : conf.tousLesMobis(); }
        public String caseTexte() { return "(" + x + "," + y + ")"; }
    }

    /**
     * Une pile : les BOITES wired d'une case, du bas vers le haut. Les mobis
     * wired qui ne sont pas des boites (dalle colorée, antenne, compteur...)
     * n'y sont jamais : ni lus, ni verifies, ni comptes.
     */
    public static final class Pile {
        public final int x, y;
        public final List<Fil> wired = new ArrayList<>();
        public final List<Lien> sortants = new ArrayList<>(), entrants = new ArrayList<>();
        /** envoie un signal sans destinataire retrouve */
        public boolean signalSansLien;
        /** recoit un signal sans emetteur retrouve */
        public boolean receptionSansLien;
        // mise en page (coordonnees du graphe)
        public int niveau;
        public double gx, gy, larg, haut;

        Pile(int x, int y) { this.x = x; this.y = y; }

        public boolean a(Wired.Rang... rangs) {
            for (Fil f : wired) for (Wired.Rang r : rangs) if (f.rang == r) return true;
            return false;
        }
        public boolean aDeclencheur() { return a(Wired.Rang.DECLENCHEUR); }
        public boolean aEffet() {
            return a(Wired.Rang.EFFET, Wired.Rang.EFFET_SIGNAL, Wired.Rang.EFFET_NEGATIF);
        }
        public boolean aSelecteur() { return a(Wired.Rang.SELECTEUR, Wired.Rang.SELECTEUR_FILTRE); }
        public String caseTexte() { return "(" + x + "," + y + ")"; }
    }

    /** De la pile « de » vers la pile « vers ». */
    public static final class Lien {
        public final Pile de, vers;
        public final boolean signal;
        public final Set<Integer> mobis;
        Lien(Pile de, Pile vers, boolean signal, Set<Integer> mobis) {
            this.de = de; this.vers = vers; this.signal = signal; this.mobis = mobis;
        }
    }

    public enum Gravite {
        ERREUR("erreur"), ATTENTION("attention"), INFO("info");
        public final String libelle;
        Gravite(String l) { libelle = l; }
    }

    public static final class Probleme {
        public final Gravite gravite;
        public final int x, y;
        public final String texte;
        /** Le wired en cause, ou 0 si le probleme concerne toute la pile. */
        public final int id;
        Probleme(Gravite g, int x, int y, String t) { this(g, x, y, t, 0); }
        Probleme(Gravite g, int x, int y, String t, int id) { gravite = g; this.x = x; this.y = y; texte = t; this.id = id; }
    }

    public static final class Resultat {
        public final Fil fil;
        public final String role;
        Resultat(Fil f, String r) { fil = f; role = r; }
    }

    // ------------------------------------------------------------ construction

    public final List<Pile> piles = new ArrayList<>();
    public final List<Lien> liens = new ArrayList<>();
    public final Map<Integer, Fil> parId = new LinkedHashMap<>();
    public final int nbLus, nbIllisibles;
    public final boolean salle;
    /** null si l'analyse s'est bien passee, sinon la raison de l'echec (a montrer). */
    public final String erreur;
    /** Mobis wired ignores parce qu'illisibles (case pas encore connue…). */
    public final int nbIgnores;

    private WiredAnalyse() {
        Map<Long, Pile> parCase = new LinkedHashMap<>();
        int l = 0, ill = 0, ign = 0;
        for (HFloorItem it : Salle.sols()) {
            String cls;
            try { cls = Salle.classe(it.getTypeId(), false); } catch (Throwable t) { continue; }
            if (!Wired.estBoite(cls)) continue;      // mobis wired (dalles, antennes...) : hors analyse
            Fil f;
            // un mobi illisible (en cours d'arrivee, case nulle) est ignore seul
            try { f = new Fil(it, cls); } catch (Throwable t) { ign++; continue; }
            parId.put(f.id, f);
            if (f.conf != null) l++;
            if (f.illisible) ill++;
            long cle = ((long) f.x << 32) | (f.y & 0xffffffffL);
            Pile p = parCase.computeIfAbsent(cle, k -> new Pile(f.x, f.y));
            p.wired.add(f);
            f.pile = p;
        }
        nbLus = l;
        nbIllisibles = ill;
        nbIgnores = ign;
        erreur = null;
        salle = Salle.dansUneSalle();
        for (Pile p : parCase.values()) p.wired.sort(Comparator.comparingDouble(f -> f.z));
        piles.addAll(parCase.values());
        piles.sort(Comparator.comparingInt((Pile p) -> p.y).thenComparingInt(p -> p.x));
        relier();
        disposer();
    }

    /** Photographie de la salle maintenant. Jamais d'exception. */
    public static WiredAnalyse maintenant() {
        try { return new WiredAnalyse(); }
        catch (Throwable t) {
            t.printStackTrace();
            return new WiredAnalyse(t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " : " + t.getMessage()));
        }
    }

    private WiredAnalyse(String erreur) {
        nbLus = 0; nbIllisibles = 0; nbIgnores = 0; salle = false; this.erreur = erreur;
    }

    /**
     * Fleches : de A vers B quand un effet ou selecteur de A designe un mobi
     * que surveille un declencheur de B. Si A envoie un signal par ce mobi
     * (antenne commune), ou si le declencheur de B recoit un signal, la fleche
     * est marquee « signal ».
     */
    private void relier() {
        for (Pile a : piles) {
            Set<Integer> cibles = new HashSet<>(), antennes = new HashSet<>();
            for (Fil f : a.wired) {
                if (!(f.estEffet() || f.estSelecteur())) continue;
                cibles.addAll(f.tousLesMobis());
                if (f.estSignal()) antennes.addAll(f.tousLesMobis());
            }
            if (cibles.isEmpty()) continue;
            for (Pile b : piles) {
                if (b == a) continue;
                Set<Integer> communs = new LinkedHashSet<>();
                boolean signal = false;
                for (Fil t : b.wired) {
                    if (t.rang != Wired.Rang.DECLENCHEUR) continue;
                    for (Integer m : t.items()) {
                        if (cibles.contains(m)) {
                            communs.add(m);
                            if (antennes.contains(m) || t.recoitSignal()) signal = true;
                        }
                    }
                }
                if (communs.isEmpty()) continue;
                Lien li = new Lien(a, b, signal, communs);
                liens.add(li);
                a.sortants.add(li);
                b.entrants.add(li);
            }
        }
        for (Pile p : piles) {
            boolean envoie = false, recoit = false;
            for (Fil f : p.wired) { if (f.estSignal()) envoie = true; if (f.recoitSignal()) recoit = true; }
            if (envoie) {
                boolean trouve = false;
                for (Lien li : p.sortants) if (li.signal) trouve = true;
                p.signalSansLien = !trouve;
            }
            if (recoit) {
                boolean trouve = false;
                for (Lien li : p.entrants) if (li.signal) trouve = true;
                p.receptionSansLien = !trouve;
            }
        }
    }

    // --------------------------------------------------------------- mise en page

    public static final double LARGEUR_BOITE = 150, LIGNE = 13, ENTETE = 18;
    public static final int LIGNES_MAX = 6, PAR_RANGEE = 5;
    public static final double ECART_X = 30, ECART_Y = 46;

    /**
     * Couches par profondeur : niveau 0 pour les piles sans fleche entrante,
     * puis distance depuis elles (parcours en largeur, les cycles ne bouclent
     * pas). Une couche trop large passe sur plusieurs rangees.
     */
    private void disposer() {
        for (Pile p : piles) p.niveau = -1;
        Deque<Pile> file = new ArrayDeque<>();
        for (Pile p : piles) if (p.entrants.isEmpty()) { p.niveau = 0; file.add(p); }
        while (true) {
            while (!file.isEmpty()) {
                Pile p = file.poll();
                for (Lien li : p.sortants) {
                    if (li.vers.niveau < 0) { li.vers.niveau = p.niveau + 1; file.add(li.vers); }
                }
            }
            // Piles prises dans un cycle sans entree : on en prend une comme racine.
            Pile reste = null;
            for (Pile p : piles) if (p.niveau < 0) { reste = p; break; }
            if (reste == null) break;
            reste.niveau = 0;
            file.add(reste);
        }

        int max = 0;
        for (Pile p : piles) max = Math.max(max, p.niveau);
        for (Pile p : piles) {
            int n = Math.min(p.wired.size(), LIGNES_MAX) + (p.wired.size() > LIGNES_MAX ? 1 : 0);
            p.larg = LARGEUR_BOITE;
            p.haut = ENTETE + n * LIGNE + 6;
        }
        double y = 0;
        for (int niv = 0; niv <= max; niv++) {
            List<Pile> couche = new ArrayList<>();
            for (Pile p : piles) if (p.niveau == niv) couche.add(p);
            // Celles qui ont des fleches d'abord, les isolees ensuite.
            couche.sort(Comparator.comparingInt((Pile p) -> (p.sortants.isEmpty() && p.entrants.isEmpty()) ? 1 : 0)
                    .thenComparingInt(p -> p.y).thenComparingInt(p -> p.x));
            for (int i = 0; i < couche.size(); i += PAR_RANGEE) {
                List<Pile> rangee = couche.subList(i, Math.min(couche.size(), i + PAR_RANGEE));
                double largeurTotale = rangee.size() * LARGEUR_BOITE + (rangee.size() - 1) * ECART_X;
                double x = -largeurTotale / 2;
                double hMax = 0;
                for (Pile p : rangee) {
                    p.gx = x; p.gy = y;
                    x += LARGEUR_BOITE + ECART_X;
                    hMax = Math.max(hMax, p.haut);
                }
                y += hMax + ECART_Y;
            }
        }
    }

    // ------------------------------------------------------------- verification

    public List<Probleme> verifier() {
        List<Probleme> r = new ArrayList<>();
        Set<Integer> existants = new HashSet<>();
        for (HFloorItem it : Salle.sols()) existants.add(it.getId());
        for (HWallItem it : Salle.murs()) existants.add(it.getId());
        Map<String, String> vars = WiredLecteur.variables();

        for (Pile p : piles) {
            boolean dec = p.aDeclencheur(), eff = p.aEffet();
            boolean queVariables = true;
            for (Fil f : p.wired)
                if (!String.valueOf(Wired.normaliser(f.classe)).startsWith("wf_var_")) queVariables = false;

            if (!dec && eff) {
                r.add(new Probleme(Gravite.ERREUR, p.x, p.y,
                        "Pile avec effet(s) mais sans déclencheur : elle ne se lancera jamais."));
            } else if (!dec && !queVariables) {
                boolean condOuSel = p.a(Wired.Rang.CONDITION, Wired.Rang.SELECTEUR, Wired.Rang.SELECTEUR_FILTRE);
                if (condOuSel)
                    r.add(new Probleme(Gravite.ATTENTION, p.x, p.y,
                            "Condition ou sélecteur hors d'une pile avec déclencheur : sans effet."));
                else
                    r.add(new Probleme(Gravite.INFO, p.x, p.y,
                            "Add-on seul, sans déclencheur sur la case."));
            }
            if (dec && !eff) {
                r.add(new Probleme(Gravite.ATTENTION, p.x, p.y,
                        "Déclencheur sans aucun effet dans la pile."));
            }

            // Ordre de la pile (comme OngletWired.verdict).
            for (int i = 1; i < p.wired.size(); i++) {
                Fil a = p.wired.get(i - 1), b = p.wired.get(i);
                if (b.rang.ordre < a.rang.ordre) {
                    r.add(new Probleme(Gravite.ATTENTION, p.x, p.y,
                            "Pile dans le désordre : « " + b.rang.libelle + " » ("
                                    + b.nom + ") est au-dessus de « " + a.rang.libelle + " ».", b.id));
                    break;
                }
            }

            for (Fil f : p.wired) {
                if (f.illisible) {
                    r.add(new Probleme(Gravite.INFO, p.x, p.y,
                            "« " + f.nom + " » pas encore lu : "
                                    + (f.raison == null ? "pas de réponse du serveur" : f.raison)
                                    + ". Nouvel essai automatique plus tard.", f.id));
                    continue;
                }
                if (f.conf == null) continue;
                int absents = 0;
                for (Integer m : f.tousLesMobis()) if (!existants.contains(m)) absents++;
                if (absents > 0)
                    r.add(new Probleme(Gravite.ERREUR, p.x, p.y,
                            "« " + f.nom + " » sélectionne " + absents
                                    + " mobi(s) qui n'existe(nt) plus dans la salle.", f.id));
                if (demandeDesMobis(f) && f.tousLesMobis().isEmpty()
                        && !(f.estEffet() && p.aSelecteur()))
                    r.add(new Probleme(Gravite.ATTENTION, p.x, p.y,
                            "« " + f.nom + " » attend des mobis mais n'en sélectionne aucun.", f.id));
                if (vars != null) {
                    for (String v : f.conf.variables) {
                        if (v.startsWith("-")) continue;       // variables internes
                        if (!vars.containsKey(v))
                            r.add(new Probleme(Gravite.ATTENTION, p.x, p.y,
                                    "« " + f.nom + " » utilise la variable " + v
                                            + " introuvable dans la salle.", f.id));
                    }
                }
            }
            if (p.signalSansLien)
                r.add(new Probleme(Gravite.INFO, p.x, p.y,
                        "Envoie un signal : aucune pile réceptrice retrouvée par ses antennes."));
        }
        r.sort(Comparator.comparingInt((Probleme q) -> q.gravite.ordinal())
                .thenComparingInt(q -> q.y).thenComparingInt(q -> q.x));
        return r;
    }

    /**
     * Effets / selecteurs qui agissent sur des mobis choisis a la main.
     * Liste prudente, d'apres les noms techniques connus.
     */
    static boolean demandeDesMobis(Fil f) {
        if (!(f.estEffet() || f.estSelecteur())) return false;
        String c = f.classe.toLowerCase(Locale.ROOT);
        if (f.estSelecteur()) return c.contains("pick");
        String[] motifs = { "toggle_state", "toggle_to_rnd", "move_rotate", "match_to_sshot",
                "teleport_to", "move_to_dir", "chase", "flee", "send_signal", "furni_to" };
        for (String m : motifs) if (c.contains(m)) return true;
        return false;
    }

    // ---------------------------------------------------------------- recherche

    /** Wired qui selectionnent ce mobi. */
    public List<Resultat> parMobi(int idMobi) {
        List<Resultat> r = new ArrayList<>();
        for (Fil f : parId.values()) {
            if (f.conf == null) continue;
            boolean un = f.conf.items.contains(idMobi), deux = f.conf.items2.contains(idMobi);
            if (!un && !deux) continue;
            String role = f.rang.libelle + (f.rang == Wired.Rang.DECLENCHEUR ? " — surveille ce mobi"
                    : f.estEffet() ? " — agit sur ce mobi"
                    : f.estSelecteur() ? " — sélectionne ce mobi"
                    : " — le sélectionne");
            if (deux && !un) role += " (2e sélection)";
            r.add(new Resultat(f, role));
        }
        return r;
    }

    /** Par nom lisible, nom technique ou texte de configuration. */
    public List<Resultat> parTexte(String q) {
        List<Resultat> r = new ArrayList<>();
        String t = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return r;
        for (Fil f : parId.values()) {
            String ou = null;
            if (f.nom.toLowerCase(Locale.ROOT).contains(t)) ou = "nom";
            else if (f.classe.toLowerCase(Locale.ROOT).contains(t)) ou = "nom technique";
            else if (f.conf != null && f.conf.texte.toLowerCase(Locale.ROOT).contains(t))
                ou = "texte « " + court(f.conf.texte, 40) + " »";
            if (ou != null) r.add(new Resultat(f, f.rang.libelle + " — trouvé dans : " + ou));
        }
        return r;
    }

    /** Par identifiant de variable (ou par son nom si la liste de la salle est connue). */
    public List<Resultat> parVariable(String q) {
        List<Resultat> r = new ArrayList<>();
        String t = q == null ? "" : q.trim();
        if (t.isEmpty()) return r;
        Set<String> ids = new HashSet<>();
        ids.add(t);
        Map<String, String> vars = WiredLecteur.variables();
        if (vars != null)
            for (Map.Entry<String, String> e : vars.entrySet())
                if (e.getValue() != null && e.getValue().equalsIgnoreCase(t)) ids.add(e.getKey());
        for (Fil f : parId.values()) {
            if (f.conf == null) continue;
            for (String v : f.conf.variables) {
                if (ids.contains(v)) {
                    String nomVar = vars != null && vars.get(v) != null && !vars.get(v).isEmpty()
                            ? " « " + vars.get(v) + " »" : "";
                    r.add(new Resultat(f, f.rang.libelle + " — utilise la variable " + v + nomVar));
                    break;
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ outils

    /** Nom affiche dans le jeu, sans le prefixe « Effet WIRED : ». */
    public static String nomLisible(String cls) {
        if (cls == null) return "(inconnu)";
        try {
            Furnidata.Mobi d = Salle.details(cls);
            if (d != null && d.name != null && !d.name.isEmpty()) {
                int i = d.name.indexOf(':');
                return i > 0 && i < d.name.length() - 2 ? d.name.substring(i + 1).trim() : d.name;
            }
        } catch (Throwable ignored) { }
        return cls;
    }

    public static String court(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\t', ' ');
        return t.length() <= n ? t : t.substring(0, n - 1) + "…";
    }

    /** Description d'un wired pour le panneau de detail. */
    public static String detail(Fil f) {
        StringBuilder b = new StringBuilder();
        b.append(f.nom).append("  ·  ").append(f.rang.libelle);
        b.append("\n   ").append(f.classe).append("  ·  id ").append(f.id);
        if (f.conf == null) {
            b.append(f.illisible ? "\n   illisible : " + (f.raison == null ? "pas de réponse du serveur" : f.raison)
                    : "\n   pas encore lu");
            return b.toString();
        }
        WiredLecteur.Config c = f.conf;
        b.append("\n   ").append(c.items.size()).append(" mobi(s) sélectionné(s)");
        if (!c.items2.isEmpty()) b.append(" + ").append(c.items2.size()).append(" en 2e sélection");
        if (!c.options.isEmpty()) b.append("\n   options ").append(c.options);
        if (!c.texte.isEmpty()) b.append("\n   texte « ").append(court(c.texte, 80)).append(" »");
        if (c.delai > 0) b.append("\n   délai ").append(c.delai).append(" (")
                .append(String.format(Locale.ROOT, "%.1f", c.delai * 0.5)).append(" s)");
        if (!c.variables.isEmpty()) b.append("\n   variables ").append(c.variables);
        return b.toString();
    }
}

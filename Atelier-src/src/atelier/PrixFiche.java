package atelier;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;

import java.util.Map;
import java.util.TreeMap;

/**
 * Prix estime dans la fiche du mobi du jeu : le client ne connait pas les prix,
 * l'Atelier lui envoie ceux des mobis de l'appart (« atelier:prix=,classe=texte, »),
 * toute la table a chaque envoi. Renvoyee quand l'appart ou un prix change.
 */
final class PrixFiche {

    private PrixFiche() { }

    private static volatile String envoye = null;
    private static volatile long envoyeA = 0;
    private static volatile int salleEnvoi = 0, envoisSalle = 0;
    private static volatile boolean demarre = false;

    static synchronized void demarrer() {
        if (demarre) return;
        demarre = true;
        Thread t = new Thread(() -> {
            while (true) {
                Salle.sommeil(4000);
                try { verifier(); } catch (Throwable ignored) { }
            }
        }, "atelier-prix-fiche");
        t.setDaemon(true);
        t.start();
    }

    private static void verifier() {
        if (!ClientModifie.saitPrix() || !Salle.dansUneSalle()) { envoye = null; return; }
        Moteur gp = Salle.gp();
        if (gp == null) return;
        Map<String, String> table = new TreeMap<>();
        Map<String, Boolean> vus = new java.util.HashMap<>();
        for (HFloorItem it : Salle.sols()) if (it != null) ajouter(gp, table, vus, false, it.getTypeId());
        for (HWallItem w : Salle.murs()) if (w != null) ajouter(gp, table, vus, true, w.getTypeId());
        StringBuilder sb = new StringBuilder("atelier:prix=,");
        for (Map.Entry<String, String> e : table.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(',');
        String texte = sb.toString();
        String cle = Salle.salleId() + "|" + texte;
        // la table est liee a la salle affichee : envoyee avant que la salle soit
        // prete, elle serait perdue ; on la redit donc une fois, puis toutes les 30 s
        int salle = Salle.salleId();
        if (salle != salleEnvoi) { salleEnvoi = salle; envoisSalle = 0; diagnosticCouleurs(); }
        long t = System.currentTimeMillis();
        if (cle.equals(envoye) && envoisSalle >= 2 && t - envoyeA < 30_000) return;
        gp.sendToClient(new gearth.protocol.HPacket("Whisper", gearth.protocol.HMessage.Direction.TOCLIENT,
                -1, texte, 0, 0, 0, -1));
        envoye = cle;
        envoyeA = t;
        envoisSalle++;
    }

    private static void ajouter(Moteur gp, Map<String, String> table, Map<String, Boolean> vus, boolean mur, int typeId) {
        if (vus.putIfAbsent((mur ? "m" : "s") + typeId, Boolean.TRUE) != null) return;
        String c = Salle.classe(typeId, mur);
        if (c == null || c.isBlank() || c.indexOf(',') >= 0 || c.indexOf('=') >= 0) return;
        int p = OngletValeur.prixUnitaire(gp, mur, typeId);
        if (p < 0) return;
        // jamais de virgule ni de « = » dans le texte (separateurs de la table)
        if (ClientModifie.saitMarges()) table.put(c, texteFiche(p));   // fiche avec icones credit / lingot
        else table.put(c, PrixTexte.nombre(p).replace(' ', ' ') + (p > 1 ? " crédits" : " crédit"));
    }

    /**
     * « 120|2.4 » : credits (« 12 345 »), puis lingots (50 credits = 1 lingot ;
     * rien sous 50 credits : « 6| »),
     * deux decimales sous 10 lingots, une au-dela, sans zero inutile. Point
     * decimal : la virgule separe la table ; le client la remet a l'affichage.
     */
    static String texteFiche(long credits) {
        java.text.DecimalFormatSymbols sy = new java.text.DecimalFormatSymbols(java.util.Locale.FRANCE);
        sy.setDecimalSeparator('.');
        sy.setGroupingSeparator('\u00A0');
        if (credits < 50) return PrixTexte.nombre(credits) + "|";   // moins d'un lingot : pas de ligne
        double l = credits / 50.0;
        java.text.DecimalFormat f = new java.text.DecimalFormat(l < 10 ? "#,##0.##" : "#,##0.#", sy);
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return PrixTexte.nombre(credits) + "|" + f.format(l);
    }

    /**
     * Diagnostic des blocs recolorables (couleur perdue au collage) : ce que le
     * jeu envoie pour quelques-uns, une fois par appart.
     */
    private static void diagnosticCouleurs() {
        int n = 0;
        for (HFloorItem it : Salle.sols()) {
            if (it == null) continue;
            String c = Salle.classe(it.getTypeId(), false);
            if (c == null || !c.contains("recolorable")) continue;
            if (n++ >= 4) break;
            StringBuilder sb = new StringBuilder("couleur : " + c + " #" + it.getId());
            try {
                Object st = it.getStuff();
                sb.append(", données ").append(st == null ? "aucune" : st.getClass().getSimpleName());
                if (st != null) {
                    gearth.extensions.parsers.stuffdata.IStuffData d = (gearth.extensions.parsers.stuffdata.IStuffData) st;
                    sb.append(" état « ").append(d.getLegacyString()).append(" », drapeaux ").append(d.getFlags());
                    if (st instanceof Map) sb.append(", table ").append(new TreeMap<>((Map<?, ?>) st));
                    else if (st instanceof Iterable) { sb.append(", valeurs ["); for (Object o : (Iterable<?>) st) sb.append(o).append(' '); sb.append(']'); }
                }
                for (String champ : new String[]{"extra", "staticClass", "sizeZ"}) {
                    java.lang.reflect.Field f = HFloorItem.class.getDeclaredField(champ);
                    f.setAccessible(true);
                    sb.append(", ").append(champ).append(' ').append(f.get(it));
                }
            } catch (Throwable t) { sb.append(" (").append(t).append(')'); }
            Journal.debug(sb.append('.').toString());
        }
    }
}

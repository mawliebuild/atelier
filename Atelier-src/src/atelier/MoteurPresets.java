package atelier;

import extension.GPresets;
import gearth.extensions.ExtensionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * le module Presets de l'Atelier, avec ses messages dans le jeu en francais.
 *
 * Le module Presets ecrit tout ce qu'il dit au joueur par sendVisualChatInfo (un
 * Whisper envoye au client), en anglais. Cette sous-classe le traduit au
 * passage ; un message inconnu passe tel quel. AppartsCreator la fait
 * instancier par le FXMLLoader a la place de GPresets.
 *
 * L'annotation est recopiee : ExtensionInfo n'est pas @Inherited, et le proxy
 * la lit sur la classe exacte de l'extension.
 */
@ExtensionInfo(
        Title = "Atelier",
        Description = "Presets de l'Atelier",
        Version = "1.3.7",
        Author = "Atelier"
)
public class MoteurPresets extends GPresets {

    @Override
    public void sendVisualChatInfo(String message) {
        super.sendVisualChatInfo(pourLeChat(traduire(message)));
    }

    /**
     * Le chat de Habbo affiche mal les guillemets francais (le » devient un
     * symbole) : guillemets droits a la place. Tous les messages affiches dans
     * le jeu passent ici, ceux de l'Atelier (InfoJeu) compris.
     */
    static String pourLeChat(String m) {
        if (m == null) return null;
        return m.replace("« ", "\"").replace(" »", "\"").replace("«", "\"").replace("»", "\"")
                .replace(' ', ' ').replace('×', 'x');
    }

    // ------------------------------------------------------------ traductions

    private static final List<Pattern> MOTIFS = new ArrayList<>();
    private static final List<String> FR = new ArrayList<>();

    /** %s = un texte, %d = un nombre ; le reste est pris a la lettre. */
    private static void t(String en, String fr) {
        StringBuilder re = new StringBuilder("^");
        int i = 0, g = 0;
        Matcher m = Pattern.compile("%[sd]").matcher(en);
        while (m.find()) {
            re.append(Pattern.quote(en.substring(i, m.start())));
            re.append(m.group().equals("%d") ? "(\\d+)" : "(.*?)");
            i = m.end();
            g++;
        }
        re.append(Pattern.quote(en.substring(i))).append("$");
        MOTIFS.add(Pattern.compile(re.toString(), Pattern.DOTALL));
        String r = fr.replace("$", "\\$");
        for (int k = 1; k <= g; k++) r = r.replaceFirst("%[sd]", "\\$" + k);
        FR.add(r);
    }

    static {
        // import d'un appart
        t("Select where the preset should be imported", "Clique la case où poser l'appart");
        t("Select where the stack tile should be placed (empty area)", "Clique une case libre pour la dalle magique");
        t("Preparing required stacktiles from BC...", "Je prépare les dalles magiques depuis le BC...");
        t("Placing %d stack tile(s)...", "Pose de %d dalle(s) magique(s)...");
        t("Some items were not available, building anyways..", "Certains mobis manquent, je pose quand même le reste...");
        t("ERROR: Some items were not available, check the availability first!", "ERREUR : certains mobis manquent, vérifie d'abord la disponibilité !");
        t("Already importing preset.. finish up or abort first", "Une pose est déjà en cours : attends la fin ou tape :abort");
        t("Adding furniture...", "Pose des mobis...");
        t("Setting furni in their correct position..", "Mise en place des mobis...");
        t("Placing wall items..", "Pose des mobis muraux...");
        t("Setting wall item states..", "Réglage des mobis muraux...");
        t("Setting up wired..", "Réglage des wired...");
        t("Setting up ads backgrounds..", "Réglage des fonds publicitaires...");
        t("Applying %d floor furni variable assignment(s)..", "Application de %d variable(s) de mobis au sol...");
        t("Applying %d wall furni variable assignment(s)..", "Application de %d variable(s) de mobis muraux...");
        t("Imported the preset successfully", "Appart posé avec succès !");
        t("Successfully aborted importing", "Pose annulée.");
        t("ERROR: not all furniture were placed", "ERREUR : tous les mobis n'ont pas été posés");
        t("Don't adjust furniture while the importer is active!", "Ne touche pas aux mobis pendant la pose !");
        t("Can't drop furniture while the importer is active, please await the procedure or abort",
                "Impossible de poser un mobi pendant la pose : attends la fin ou tape :abort");
        t("No wired permissions - select a tile for state changing furni (empty area near you)",
                "Pas de droits wired : clique une case libre près de toi pour régler les mobis à états");
        t("You've enabled use Room Furni, please select the start rectangle around the furni you want to use as backup or type 'all'",
                "« Mobis de la salle » activé : clique le début du rectangle des mobis à réutiliser, ou tape 'all'");
        t("Select the start of the rectangle", "Clique le début du rectangle");
        t("Select the end of the rectangle", "Clique la fin du rectangle");
        t("Select the end of the rectangle where the furni is", "Clique la fin du rectangle où sont les mobis");

        // mobis introuvables
        t("Couldn't find '%s' in inventory or room.. continuing", "« %s » introuvable dans l'inventaire ou la salle, je continue");
        t("Couldn't find '%s' in inventory.. continuing", "« %s » introuvable dans l'inventaire, je continue");
        t("Couldn't find the item '%s' in BC warehouse.. continuing", "« %s » introuvable au BC, je continue");
        t("Couldn't find wall item '%s' (state=%s) in inventory.. continuing", "Mobi mural « %s » (état %s) introuvable dans l'inventaire, je continue");
        t("Couldn't find wall item '%s' in BC catalog.. continuing", "Mobi mural « %s » introuvable au BC, je continue");
        t("ERROR: Couldn't find '%s' in inventory or room.. aborting", "ERREUR : « %s » introuvable dans l'inventaire ou la salle, pose arrêtée");
        t("ERROR: Couldn't find '%s' in inventory.. aborting", "ERREUR : « %s » introuvable dans l'inventaire, pose arrêtée");
        t("ERROR: Couldn't find the item '%s' in BC warehouse.. aborting", "ERREUR : « %s » introuvable au BC, pose arrêtée");
        t("ERROR: Couldn't find wall item '%s' (state=%s) in inventory.. aborting", "ERREUR : mobi mural « %s » (état %s) introuvable dans l'inventaire, pose arrêtée");
        t("ERROR: Couldn't find wall item '%s' in BC catalog.. aborting", "ERREUR : mobi mural « %s » introuvable au BC, pose arrêtée");
        t("WARNING: Unknown wall item '%s', skipping", "ATTENTION : mobi mural « %s » inconnu, ignoré");

        // erreurs generales
        t("ERROR: extension not fully initialized yet", "ERREUR : l'Atelier n'a pas fini de démarrer");
        t("ERROR: Inventory, catalog or furnidata is unavailable", "ERREUR : inventaire, catalogue BC ou furnidata indisponible");
        t("ERROR: No preset selected!", "ERREUR : aucun appart choisi !");
        t("ERROR: select the preset first", "ERREUR : choisis d'abord un appart");
        t("Error: no room detected or furnidata not available", "Erreur : pas de salle détectée ou furnidata indisponible");
        t("Do not open wired while the extension is fetching configurations", "N'ouvre pas de wired pendant la lecture des réglages");

        // export d'un appart
        t("Enter the name of the preset", "Tape le nom de l'appart");
        t("A preset named '%s' already exists. Type y/yes to override or n/no to cancel.",
                "Un appart « %s » existe déjà. Tape y pour le remplacer, n pour annuler.");
        t("Type y/yes to override or n/no to cancel", "Tape y pour remplacer, n pour annuler");
        t("Export cancelled", "Enregistrement annulé");
        t("Aborted preset export", "Enregistrement arrêté");
        t("Already exporting preset.. finish up or abort first", "Un enregistrement est déjà en cours : attends la fin ou tape :abort");
        t("Fetching additional %s wired configurations before exporting... do not alter the room",
                "Lecture de %s réglage(s) wired avant l'enregistrement... ne touche pas à la salle");
        t("WARNING - Did not retrieve all wired. Retrying %d missing wired..", "ATTENTION : wired incomplets, nouvel essai pour %d wired manquant(s)...");
        t("WARNING - No wired permissions in this room. Exporting without wired configs.",
                "ATTENTION : pas de droits wired ici, enregistrement sans les réglages wired.");
        t("ERROR - Couldn't export due to missing floorstate or furnidata", "ERREUR : enregistrement impossible (salle ou furnidata pas chargée)");
        t("ERROR - Couldn't export due to missing wired configurations", "ERREUR : enregistrement impossible (réglages wired manquants)");
        t("ERROR - Couldn't export due to unsufficient resources", "ERREUR : enregistrement impossible (ressources insuffisantes)");
        t("ERROR - Something went wrong while fetching configurations..", "ERREUR : la lecture des réglages a échoué");

        // dons
        t("Aborted auto donate", "Don automatique arrêté");
        t("Please wait for the auto donate to finish before donating yourself furni!", "Attends la fin du don automatique avant de te donner des mobis !");
    }

    /** Le message en francais, ou tel quel s'il n'est pas connu. */
    static String traduire(String m) {
        if (m == null) return null;
        for (int i = 0; i < MOTIFS.size(); i++) {
            Matcher x = MOTIFS.get(i).matcher(m);
            if (x.matches()) return x.replaceFirst(FR.get(i));
        }
        if (m.startsWith("Invalid characters in name"))
            return "Caractères interdits dans le nom : < > : / \\ | ? *";
        if (m.startsWith("ERROR: ")) return "ERREUR : " + m.substring(7);
        return m;
    }
}

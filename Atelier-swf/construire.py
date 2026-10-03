#!/usr/bin/env python3
"""
Construit HabboAir.swf modifie pour l'Atelier, a partir du SWF d'origine.

On ne recompile JAMAIS une classe entiere (FFDec ne la reproduit pas a
l'identique et Flash la rejetait : onglet Mobilier vide). On remplace :
  - la mise en page de l'inventaire (binaryData 2330) ;
  - le corps de quelques methodes, en P-code, en gardant le code d'origine
    et en ajoutant nos instructions ;
  - six images d'icones globales que le jeu n'utilise nulle part (forum et
    emotions de Frank) par nos dessins.

Usage : python3 construire.py [sortie.swf] [--sans icones,categories,pagination]
"""
import json
import os, re, subprocess, sys, xml.dom.minidom

ICI = os.path.dirname(os.path.abspath(__file__))
ORIGINE = os.path.join(ICI, "Habbo.app.origine/Contents/Resources/HabboAir.swf")
FFDEC = os.path.join(ICI, "ffdec/ffdec-cli.jar")
def _java():
    """Le Java de l'Atelier sur Mac ; ailleurs (Windows), JAVA_HOME ou le « java » du PATH."""
    mac = "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java"
    if os.path.exists(mac):
        return mac
    jh = os.environ.get("JAVA_HOME")
    if jh:
        for nom in ("java.exe", "java"):
            c = os.path.join(jh, "bin", nom)
            if os.path.exists(c):
                return c
    import shutil
    return shutil.which("java") or "java"


JAVA = _java()
TRAVAIL = os.path.join(ICI, "travail")

# ------------------------------------------------------------------ options
args = sys.argv[1:]
SORTIE = os.path.join(TRAVAIL, "HabboAir-atelier.swf")
SANS = set()
i = 0
while i < len(args):
    if args[i] == "--sans":
        SANS |= set(x for x in args[i + 1].split(",") if x); i += 2
    else:
        SORTIE = os.path.abspath(args[i]); i += 1
ICONES = "icones" not in SANS          # 4 boutons-icones a la place du menu Tous/Sol/...
CATEGORIES = "categories" not in SANS  # menus Categorie / Annee (filtres de l'Atelier)
PAGINATION = "pagination" not in SANS  # ◀ Page [n] / N ▶
RECHERCHE = "recherche" not in SANS  # la recherche du haut trouve aussi categorie, ligne, annee, type
CHAMP_RECHERCHE = False  # ancien champ a gauche de Categorie (retire : une seule recherche)
GRILLE = "grille" not in SANS  # « Voir grille » : traits autour des cases, par-dessus les mobis
SURLIGNAGE = "surlignage" not in SANS  # lueur sur les mobis selectionnes dans les calques

# ------------------------------------------------------------------ references P-code (forme d'origine)
FV = 'PrivateNamespace("com.sulake.habbo.inventory.furni:FurniView")'
FGV = 'PrivateNamespace("com.sulake.habbo.inventory.furni:FurniGridView")'
IW = 'Namespace("com.sulake.core.window:IWindow")'
DD = 'Namespace("com.sulake.core.window.components:IDropMenuWindow")'
Y1 = 'getlex QName(%s,"_-y1")' % FV
W14 = 'getlex QName(%s,"_-w14")' % FV
L1Y = 'getlex QName(%s,"_-L1Y")' % FV
FIND = 'callproperty QName(%s,"findChildByName"), 1' % IW
NAME = 'getproperty QName(%s,"name")' % IW
CAPTION_GET = 'getproperty QName(%s,"caption")' % IW
CAPTION_SET = 'setproperty QName(%s,"caption")' % IW
COLOR = 'setproperty QName(%s,"color")' % IW
SEL_SET = 'setproperty QName(%s,"selection")' % DD
SEL_GET = 'getproperty QName(%s,"selection")' % DD
POP = 'callpropvoid QName(%s,"populate"), 1' % DD
X6 = 'QName(PackageNamespace("com.sulake.core.window.components"),"_-X6")'
R1T = 'QName(PackageNamespace("_-910"),"_-R1T")'
TYPE = 'getproperty QName(PackageNamespace(""),"type")'
KBD = 'QName(PackageNamespace("com.sulake.core.window.events"),"WindowKeyboardEvent")'
JAUNE, BLANC = 15384347, 16777215
HALO = 16769354   # 0xFFE14A : couleur du halo de selection, sert aussi de signature   # jaune des selecteurs wired (0xEABF1B), blanc

CATS = ["Toutes les catégories", "Halloween", "Noël", "Pâques", "Saint-Valentin", "Été", "Rares",
        "Builders Club", "Wired", "NFT", "Habbo Club", "Duckets", "Diamants", "Trophées", "Classiques",
        "Publicités", "Jeux", "Animaux", "Bazar", "Nouvel An", "Carnaval", "Autres"]
# Regles de Categories.java (meme ordre que CATS, sans « Toutes » ni « Autres »)
FAMILLES = ["^h?ween.*|^halloween.*", "^xmas.*|^christmas.*|^santa.*|^nordic.*", "^easter.*|^bunny.*",
            "^val.*|^valentine.*|^love.*", "^summer.*|^beach.*|^tiki.*|^sunny.*",
            "^rare$|^bonusrare$|^rare_.*|^ltd.*", "^buildersclub.*|^bc_.*", "^wired.*", "^nft.*",
            "^habbo_club.*|^hc_.*|^club_.*", "^duckets.*", "^diamond.*", "^trophies.*|^trophy.*",
            "^classics.*|^hhistory.*", "^ad_.*", "^bb_.*|^snowwar.*|^football.*|^game.*|^sf_.*",
            "^pet.*|^horse.*|^petfood.*", "^bazaar.*|^pura.*|^iced.*|^mode.*|^plasto.*|^diner.*",
            "^nye.*|^newyear.*", "^carnival.*|^brasil.*"]
TOUTES_FAM = "|".join(FAMILLES)
# motif par index de CATS : "" (toutes), "<famille>@@<toutes>" , "!<toutes>" (autres)
MOTIFS = [""] + [f + "@@" + TOUTES_FAM for f in FAMILLES] + ["!" + TOUTES_FAM]
assert len(MOTIFS) == 22
# Collections (furnilines) et leur nom francais (pixelsemotion / habbotravel) :
# le menu Categorie les liste, d'apres les mobis de l'inventaire.
COLLECTIONS = json.load(open(os.path.join(ICI, "donnees", "collections_fr.json"), encoding="utf-8"))
def famille_de(nom):
    """« Habboween 2016 - Chasseurs de fantômes » -> « Habboween » : une entree par famille."""
    m = re.search(r"\b(19|20)\d{2}\b", nom)
    if m and m.start() > 0:
        nom = nom[:m.start()]
    return re.sub(r"\s{2,}", " ", nom).strip(" -–")


TABLE = "|" + "|".join("%s=%s" % (k, famille_de(v)) for k, v in sorted(COLLECTIONS.items())) + "|"
SANS_COLLECTION = "Sans collection"
CATS_DEBUT = ["Toutes les catégories"]
ANNEE_MAX = 2026
ANNEES = ["Toutes les années"]   # complete par annees_inventaire(), d'apres les mobis

# Images remplacees : id de l'image dans le SWF -> (dessin, nom de l'icone globale)
IMAGES = {1167: ("tous.png", "forum_forum_list0"), 1264: ("sol.png", "forum_forum_list1"),
          1082: ("mur.png", "forum_forum_list2"), 1307: ("dispo.png", "forum_forum_edit"),
          1518: ("gauche.png", "franks_emotions_angry"), 1388: ("droite.png", "franks_emotions_poop")}
ICONE = {k: v[1] for k, v in [("tous", IMAGES[1167]), ("sol", IMAGES[1264]), ("mur", IMAGES[1082]),
                               ("dispo", IMAGES[1307]), ("gauche", IMAGES[1518]), ("droite", IMAGES[1388])]}


# ================================================================== mise en page
def curseur_main(s):
    """
    Curseur « main » au survol de tout ce qui est cliquable : le style de
    certains elements (zones, menus) le desactive par defaut
    (interactive_cursor_disabled). On le force a false.
    """
    VAR = '<var key="interactive_cursor_disabled" value="false" type="Boolean"/>'
    def ajoute(m):
        balise, attrs, fermee = m.group(1), m.group(2), m.group(3)
        if 'name="' not in attrs and balise != "button":
            return m.group(0)
        if fermee:
            return '<%s%s><variables>%s</variables></%s>' % (balise, attrs, VAR, balise)
        return m.group(0)
    s = re.sub(r'<(button|container_button|region|dropmenu)(\s[^>]*?)(/?)>', ajoute, s)
    # elements avec bloc <variables> : on y ajoute la variable
    out, i = [], 0
    for m in re.finditer(r'<(button|container_button|region|dropmenu)\s[^>]*?(?<!/)>', s):
        balise = m.group(1)
        fin = s.find('</%s>' % balise, m.end())
        bloc = s[m.end():fin]
        if VAR in bloc or '<variables>' not in bloc:
            continue
        # le bloc <variables> qui suit directement les enfants de cet element
        j = bloc.rfind('<variables>')
        if j < 0: continue
        pos = m.end() + j + len('<variables>')
        out.append(pos)
    for pos in sorted(out, reverse=True):
        s = s[:pos] + VAR + s[pos:]
    return s


def mise_en_page():
    src = [f for f in os.listdir(os.path.join(ICI, "export-bin")) if "inventory_xml" in f][0]
    s = open(os.path.join(ICI, "export-bin", src), encoding="utf-8").read()
    debut = s.index('name="furni" visible="false">')
    fin = s.index('name="preview_container"', debut)
    seg = s[debut:fin]

    def rep(a, b):
        nonlocal seg
        assert seg.count(a) == 1, a
        seg = seg.replace(a, b, 1)

    ligne1 = ''
    if ICONES:
        # Boutons colles, comme les selecteurs de l'editeur wired : gauche 104,
        # milieu 106, droite 105, un trait entre chacun ; icone centree, gravee.
        def bouton(i, x, w, style, icone, iw, ih, aide):
            return ('''                        <container_button x="%d" y="3" width="%d" height="19" params="1" style="%d" dynamic_style="button" name="atelier_f_%d">
                          <children>
                            <static_bitmap x="%d" y="%d" width="%d" height="%d" params="16" style="3">
                              <variables>
                                <var key="asset_uri" value="%s" type="String"/>
                                <var key="stretched_x" value="false" type="Boolean"/>
                                <var key="stretched_y" value="false" type="Boolean"/>
                                <var key="etching_color" value="0x48000000" type="hex"/>
                              </variables>
                            </static_bitmap>
                          </children>
                          <variables>
                            <var key="tool_tip_caption" value="%s" type="String"/>
                          </variables>
                        </container_button>
''' % (x, w, style, i, (w - iw) // 2, (19 - ih) // 2, iw, ih, icone, aide))

        def trait(x):
            return ('''                        <container x="%d" y="3" width="1" height="19" params="16" style="3">
                          <children>
                            <background x="0" y="0" width="1" height="18" params="16" style="3" color="0xffff919191" background="true"/>
                            <background x="0" y="18" width="1" height="1" params="16" style="3" color="0xfffff2f2f2" background="true"/>
                          </children>
                        </container>
''' % x)
        x = 150
        # icones de 11 px de haut, 2 px de marge a gauche et a droite
        icones = [(0, 104, ICONE["tous"], 11, "Tous"), (1, 106, ICONE["sol"], 9, "Sol"),
                  (2, 106, ICONE["mur"], 7, "Articles muraux"), (3, 105, ICONE["dispo"], 11, "Disposition de l%27appart")]
        ligne1 = ''
        for k, (i, style, icone, iw, aide) in enumerate(icones):
            w = 24
            ligne1 += bouton(i, x, w, style, icone, iw, 11, aide)
            x += w
            if k < 3:
                ligne1 += trait(x)
                x += 1
        filtre = '<dropmenu x="150" y="2" width="119" height="21" params="17" style="0" name="filter.options" visible="false"/>\n'
        type_x, type_w = 254, 210
    else:
        filtre = '<dropmenu x="150" y="2" width="119" height="21" params="17" style="0" name="filter.options"/>\n'
        type_x, type_w = 274, 119
    rep('''                        <dropmenu x="150" y="2" width="119" height="21" params="17" style="0" name="filter.options"/>
                        <dropmenu x="274" y="2" width="119" height="21" params="17" style="0" name="placement.options"/>
''', '                        ' + filtre + ligne1
        + '                        <dropmenu x="%d" y="2" width="%d" height="21" params="17" style="0" name="placement.options"/>\n' % (type_x, type_w))

    # La grille (231 de haut, s'etire avec la fenetre) : Categorie / Annee en haut,
    # dans sa colonne (rien ne recouvre l'apercu a droite), pagination en bas.
    haut_menus = 25 if CATEGORIES else 0
    bas = 22 if PAGINATION else 10
    grille_h = 231 - haut_menus - bas
    rep('<scrollable_itemgrid_vertical x="0" y="0" width="284" height="221" params="2065" style="3" name="item_grid" tags="FURNI_ITEM_GRID">',
        '<scrollable_itemgrid_vertical x="0" y="%d" width="284" height="%d" params="2065" style="3" name="item_grid" tags="FURNI_ITEM_GRID">' % (haut_menus, grille_h))
    debut_grille = ''
    if CATEGORIES:
        if CHAMP_RECHERCHE:
            debut_grille += ('''                    <border x="0" y="1" width="74" height="21" params="16" style="0">
                      <children>
                        <input x="3" y="3" width="68" height="15" params="1" style="3" name="atelier_cat_recherche">
                          <variables>
                            <var key="mouse_wheel_enabled" value="false" type="Boolean"/>
                            <var key="spacing" value="0" type="Number"/>
                            <var key="leading" value="0" type="Number"/>
                          </variables>
                        </input>
                      </children>
                    </border>
                    <dropmenu x="78" y="1" width="118" height="21" params="17" style="0" name="atelier_categorie"/>
                    <dropmenu x="200" y="1" width="84" height="21" params="17" style="0" name="atelier_annee"/>
''')
        else:
            debut_grille += ('                    <dropmenu x="0" y="1" width="140" height="21" params="17" style="0" name="atelier_categorie"/>\n'
                             '                    <dropmenu x="144" y="1" width="140" height="21" params="17" style="0" name="atelier_annee"/>\n')
    if PAGINATION:
        rep('<itemlist_horizontal x="0" y="220" width="280" height="10" params="1040" style="3" name="item_grid_pages">',
            '<itemlist_horizontal x="0" y="%d" width="280" height="10" params="1040" style="3" name="item_grid_pages" visible="false">' % (haut_menus + grille_h - 1))
        py = haut_menus + grille_h + 3
        x0 = 69
        # params 1040/1041 : suivent le bas de la grille quand la fenetre change de hauteur
        debut_grille += '''                    <region x="%d" y="%d" width="10" height="13" params="1041" style="3" name="atelier_page_prec" treshold="0">
                      <children>
                        <static_bitmap x="0" y="0" width="8" height="13" params="16" style="3">
                          <variables><var key="asset_uri" value="%s" type="String"/></variables>
                        </static_bitmap>
                      </children>
                      <variables><var key="tool_tip_caption" value="Page pr%%C3%%A9c%%C3%%A9dente" type="String"/></variables>
                    </region>
                    <text x="%d" y="%d" width="32" height="16" params="1040" style="3" caption="Page">
                      <variables><var key="auto_size" value="left" type="String"/><var key="text_style" value="u_regular" type="String"/></variables>
                    </text>
                    <border x="%d" y="%d" width="34" height="17" params="1040" style="0">
                      <children>
                        <input x="3" y="1" width="28" height="15" params="1" style="3" name="atelier_page_saisie" caption="1">
                          <variables>
                            <var key="mouse_wheel_enabled" value="false" type="Boolean"/>
                            <var key="spacing" value="0" type="Number"/>
                            <var key="leading" value="0" type="Number"/>
                          </variables>
                        </input>
                      </children>
                    </border>
                    <text x="%d" y="%d" width="40" height="16" params="1040" style="3" name="atelier_page_total" caption="/ 1">
                      <variables><var key="auto_size" value="left" type="String"/><var key="text_style" value="u_regular" type="String"/></variables>
                    </text>
                    <region x="%d" y="%d" width="10" height="13" params="1041" style="3" name="atelier_page_suiv" treshold="0">
                      <children>
                        <static_bitmap x="0" y="0" width="8" height="13" params="16" style="3">
                          <variables><var key="asset_uri" value="%s" type="String"/></variables>
                        </static_bitmap>
                      </children>
                      <variables><var key="tool_tip_caption" value="Page suivante" type="String"/></variables>
                    </region>
''' % (x0, py + 1, ICONE["gauche"], x0 + 16, py, x0 + 50, py, x0 + 90, py, x0 + 136, py + 1, ICONE["droite"])
    else:
        rep('<itemlist_horizontal x="0" y="220" width="280" height="10" params="1040" style="3" name="item_grid_pages">',
            '<itemlist_horizontal x="0" y="%d" width="280" height="10" params="1040" style="3" name="item_grid_pages">' % (haut_menus + grille_h - 1))
    if CATEGORIES:
        # regles du filtre Categorie / Annee, relues par FurniGridView.passFilter
        debut_grille += ('                    <text x="0" y="0" width="1" height="1" params="16" style="3" name="atelier_motif_cat" visible="false"/>\n'
                         '                    <text x="0" y="0" width="1" height="1" params="16" style="3" name="atelier_motif_annee" visible="false"/>\n')
    if debut_grille:
        rep('<scrollable_itemgrid_vertical', debut_grille + '                    <scrollable_itemgrid_vertical')
    s = s[:debut] + seg + s[fin:]
    s = curseur_main(s)
    xml.dom.minidom.parseString(s.encode("utf-8"))
    chemin = os.path.join(TRAVAIL, "inventory_xml.bin")
    open(chemin, "w", encoding="utf-8").write(s)
    return chemin


# ================================================================== P-code
def connexion():
    return [W14, 'getproperty QName(PackageNamespace(""),"controller")',
            'getproperty QName(PackageNamespace(""),"communication")',
            'getproperty QName(Namespace("com.sulake.habbo.communication:IHabboCommunicationManager"),"connection")',
            'findpropstrict ' + R1T]

ENVOI = ['constructprop %s, 1' % R1T,
         'callpropvoid QName(Namespace("com.sulake.core.communication.connection:IConnection"),"send"), 1']


def couleurs(prefixe, reg=None, actif=None):
    c = []
    for k in range(4):
        c += [Y1, 'pushstring "atelier_f_%d"' % k, FIND, 'coerce_a', 'setlocal 14',
              'getlocal 14', 'iffalse %s_c%d' % (prefixe, k), 'getlocal 14']
        if actif is not None:
            c += ['pushint %d' % (JAUNE if k == actif else BLANC)]
        else:
            c += ['getlocal %d' % reg, 'pushbyte %d' % k, 'ifne %s_b%d' % (prefixe, k),
                  'pushint %d' % JAUNE, 'jump %s_d%d' % (prefixe, k),
                  '%s_b%d:' % (prefixe, k), 'pushint %d' % BLANC, '%s_d%d:' % (prefixe, k)]
        c += [COLOR, '%s_c%d:' % (prefixe, k)]
    return c


ENUM = 'callproperty QName(%s,"enumerateSelection"), 0' % DD
MULTI_L = 'getproperty MultinameL([PackageNamespace(""),Namespace("http://adobe.com/AS3/2006/builtin")])'
AS3 = 'Namespace("http://adobe.com/AS3/2006/builtin")'
INDEXOF = 'callproperty QName(%s,"indexOf"), 1' % AS3
LOWER = 'callproperty QName(%s,"toLowerCase"), 0' % AS3
PUSH = 'callpropvoid QName(%s,"push"), 1' % AS3
LENGTH = 'getproperty QName(PackageNamespace(""),"length")'


def p_table():
    return 'pushstring "%s"' % TABLE.replace('\\', '\\\\').replace('"', '\\"')


def nom_ligne(L, OUT, I, J, et):
    """Registre L (ligne en minuscules) -> registre OUT : nom francais de sa
    collection, sinon la ligne elle-meme, « Sans collection » si vide."""
    return [p_table(), 'pushstring "|"', 'getlocal %d' % L, 'add', 'pushstring "="', 'add', INDEXOF,
            'convert_i', 'setlocal %d' % I,
            'getlocal %d' % I, 'pushbyte 0', 'iflt %s_brut' % et,
            'getlocal %d' % I, 'getlocal %d' % L, LENGTH, 'add', 'pushbyte 2', 'add', 'convert_i', 'setlocal %d' % I,
            p_table(), 'pushstring "|"', 'getlocal %d' % I, 'callproperty QName(%s,"indexOf"), 2' % AS3,
            'convert_i', 'setlocal %d' % J,
            p_table(), 'getlocal %d' % I, 'getlocal %d' % J, 'callproperty QName(%s,"substring"), 2' % AS3,
            'coerce_s', 'setlocal %d' % OUT, 'jump %s_fin' % et,
            '%s_brut:' % et, 'getlocal %d' % L, 'setlocal %d' % OUT,
            'getlocal %d' % L, LENGTH, 'pushbyte 0', 'ifne %s_fin' % et,
            'pushstring "%s"' % SANS_COLLECTION, 'setlocal %d' % OUT,
            '%s_fin:' % et]


def liste_cats():
    """Une seule instruction-chaine : la liste complete des categories sur la pile."""
    return "\n".join(['pushstring "%s"' % c for c in CATS] + ['newarray %d' % len(CATS)])


def recherche_categorie():
    """Saisie dans la recherche : le menu Categorie ne garde que les correspondances
    (en tete « Toutes les categories ») ; Entree choisit la premiere trouvee."""
    return [
        'getlocal2', CAPTION_GET, 'coerce_s', LOWER, 'coerce_s', 'setlocal 13',      # 13 : texte cherche
        liste_cats(), 'setlocal 11',                                                  # 11 : liste complete
        'newarray 0', 'setlocal 10',                                                  # 10 : resultat
        'pushbyte 0', 'setlocal 12',                                                  # 12 : i
        'jump atl_rq_t',
        'atl_rq_b:', 'label',
        'getlocal 11', 'getlocal 12', MULTI_L, 'coerce_s', 'setlocal 9',
        'getlocal 12', 'pushbyte 0', 'ifeq atl_rq_a',                                 # « Toutes » toujours en tete
        'getlocal 9', LOWER, 'getlocal 13', INDEXOF, 'pushbyte 0', 'iflt atl_rq_n',
        'atl_rq_a:', 'getlocal 10', 'getlocal 9', PUSH,
        'atl_rq_n:', 'inclocal_i 12',
        'atl_rq_t:', 'getlocal 12', 'getlocal 11', LENGTH, 'iflt atl_rq_b',
        Y1, 'pushstring "atelier_categorie"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 14',
        'getlocal 14', 'iffalse atl_rq_f',
        # libelle choisi avant la saisie (pour le garder s'il est encore dans la liste)
        'pushstring ""', 'setlocal 9',
        'getlocal 14', ENUM, 'coerce_a', 'setlocal 11',
        'getlocal 11', 'iffalse atl_rq_l',
        'getlocal 14', SEL_GET, 'convert_i', 'setlocal 12',
        'getlocal 12', 'pushbyte 0', 'iflt atl_rq_l',
        'getlocal 12', 'getlocal 11', LENGTH, 'ifge atl_rq_l',
        'getlocal 11', 'getlocal 12', MULTI_L, 'coerce_s', 'setlocal 9',
        'atl_rq_l:',
        # renomme le temps du remplissage : la selection qu'il provoque n'est pas
        # un choix (sinon elle retirait le filtre de l'Atelier a chaque touche)
        'getlocal 14', 'pushstring "atelier_categorie_maj"', 'setproperty QName(%s,"name")' % IW,
        'getlocal 14', 'getlocal 10', POP,
        'getlocal 10', 'getlocal 9', INDEXOF, 'convert_i', 'setlocal 12',
        'getlocal 12', 'pushbyte 0', 'ifge atl_rq_s', 'pushbyte 0', 'setlocal 12',
        'atl_rq_s:', 'getlocal 14', 'getlocal 12', SEL_SET,
        'getlocal 14', 'pushstring "atelier_categorie"', 'setproperty QName(%s,"name")' % IW,
        # Entree : la premiere categorie trouvee est choisie (s'il y en a une)
        'getlocal1', 'getlex ' + KBD, 'astypelate', 'coerce ' + KBD,
        'getproperty QName(PackageNamespace(""),"keyCode")', 'pushbyte 13', 'ifne atl_rq_o',
        'getlocal 10', LENGTH, 'pushbyte 1', 'ifle atl_rq_f',
        'getlocal 14', 'pushbyte 1', SEL_SET,
        # pas d'ouverture du menu a chaque touche : il prendrait le clavier a la saisie
        'atl_rq_o:',
        'atl_rq_f:', 'returnvoid']


def aller_page():
    """Pile : rien ; registre 12 = page voulue (0 = premiere). La grille borne elle-meme."""
    return [L1Y, 'getlocal 12', 'pushfalse', 'callpropvoid QName(%s,"changeToPage"), 2' % FGV,
            L1Y, 'callpropvoid QName(%s,"updatePaging"), 0' % FGV, 'returnvoid']


def annees_de_la_famille():
    """
    Apres le choix d'une categorie (registre 9 : « =,codes, » ou vide) : le menu
    Annee ne garde que les annees des mobis de cette famille (toutes si vide),
    de la plus recente a la plus ancienne ; « Toutes les annees » choisi.
    Registres 18 a 25.
    """
    IFD = 'Namespace("com.sulake.habbo.session.furniture:IFurnitureData")'
    RX = 'QName(PackageNamespace(""),"RegExp")'
    return [
        Y1, 'pushstring "atelier_annee"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 18',
        'getlocal 18', 'iffalse atl_fa_fin',
        'newobject 0', 'setlocal 19',
        'newarray 0', 'setlocal 20',
        'findpropstrict ' + RX, 'pushstring "(19|20)[0-9][0-9]"', 'constructprop %s, 1' % RX, 'coerce_a', 'setlocal 21',
        W14, 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 22',
        'getlocal 22', 'iffalse atl_fa_r',
        'pushbyte 0', 'setlocal 23',
        'jump atl_fa_t',
        'atl_fa_b:', 'label',
        'getlocal 22', 'getlocal 23', MULTI_L, 'coerce_a', 'setlocal 24',
        'getlocal 24', 'iffalse atl_fa_n',
        'getlocal 24', 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 24',
        'getlocal 24', 'iffalse atl_fa_n',
        'getlocal 24', 'getproperty QName(%s,"furniLine")' % IFD, 'coerce_s', 'setlocal 25',
        'getlocal 25', 'iftrue atl_fa_v', 'pushstring ""', 'setlocal 25', 'atl_fa_v:',
        # famille choisie : la ligne du mobi doit en faire partie
        'getlocal 9', LENGTH, 'pushbyte 0', 'ifeq atl_fa_ok',
        'getlocal 9', 'pushstring ","', 'getlocal 25', LOWER, 'add', 'pushstring ","', 'add', INDEXOF,
        'pushbyte 0', 'iflt atl_fa_n',
        'atl_fa_ok:',
        'getlocal 21', 'getlocal 25', 'pushstring " "', 'add',
        'getlocal 24', 'getproperty QName(%s,"className")' % IFD, 'coerce_s', 'add',
        'callproperty QName(%s,"exec"), 1' % AS3, 'coerce_a', 'setlocal 24',
        'getlocal 24', 'iffalse atl_fa_n',
        'getlocal 24', 'pushbyte 0', MULTI_L, 'coerce_s', 'setlocal 25',
        'getlocal 19', 'getlocal 25', MULTI_L, 'iftrue atl_fa_n',
        'getlocal 19', 'getlocal 25', 'pushtrue', 'setproperty MultinameL([PackageNamespace(""),Namespace("http://adobe.com/AS3/2006/builtin")])',
        'getlocal 20', 'getlocal 25', PUSH,
        'atl_fa_n:', 'inclocal_i 23',
        'atl_fa_t:', 'getlocal 23', 'getlocal 22', LENGTH, 'iflt atl_fa_b',
        'atl_fa_r:',
        'getlocal 20', 'pushbyte 2', 'callpropvoid QName(%s,"sort"), 1' % AS3,
        'pushstring "Toutes les années"', 'newarray 1',
        'getlocal 20', 'callproperty QName(%s,"concat"), 1' % AS3, 'coerce_a', 'setlocal 20',
        'getlocal 18', 'pushstring "atelier_annee_maj"', 'setproperty QName(%s,"name")' % IW,
        'getlocal 18', 'getlocal 20', POP,
        'getlocal 18', 'pushbyte 0', SEL_SET,
        'getlocal 18', 'pushstring "atelier_annee"', 'setproperty QName(%s,"name")' % IW,
        Y1, 'pushstring "atelier_motif_annee"', FIND, 'coerce_a', 'setlocal 24',
        'getlocal 24', 'iffalse atl_fa_fin', 'getlocal 24', 'pushstring ""', CAPTION_SET,
        'atl_fa_fin:']


def window_event_proc():
    w = open(os.path.join(TRAVAIL, "wep.orig.pcode"), encoding="utf-8").read().split("\n")
    assert w[7] == "maxstack 4" and w[8] == "localcount 11", (w[7], w[8])
    w[7] = "maxstack 48"; w[8] = "localcount 26"
    p = []
    # ---- clics
    p += ['getlocal1', TYPE, 'pushstring "WME_CLICK"', 'ifne atl_clav',
          'getlocal2', NAME, 'coerce_s', 'setlocal 11']
    if ICONES:
        p += ['pushbyte -1', 'setlocal 12']
        for k in range(4):
            p += ['getlocal 11', 'pushstring "atelier_f_%d"' % k, 'ifne atl_n%d' % k,
                  'pushbyte %d' % k, 'setlocal 12', 'atl_n%d:' % k]
        p += ['getlocal 12', 'pushbyte 0', 'iflt atl_pg',
              Y1, 'pushstring "filter.options"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'getlocal 12', SEL_SET,
              'getlex QName(%s,"MAIN_FILTER_IDS")' % FV, 'getlocal 12',
              'getproperty MultinameL([PackageNamespace(""),Namespace("http://adobe.com/AS3/2006/builtin")])',
              'coerce_s', 'setlocal 13',
              'findpropstrict QName(%s,"populateTypeFilterOptions")' % FV, 'getlocal 13',
              'findpropstrict QName(%s,"getPreservedTypeFilter")' % FV, 'getlocal 13',
              'callproperty QName(%s,"getPreservedTypeFilter"), 1' % FV,
              'callpropvoid QName(%s,"populateTypeFilterOptions"), 2' % FV,
              'findpropstrict QName(PackageNamespace(""),"updateGridFilters")',
              'callpropvoid QName(PackageNamespace(""),"updateGridFilters"), 0']
        p += couleurs('atlk', reg=12) + ['returnvoid']
    p += ['atl_pg:']
    if PAGINATION:
        for nom, delta, et in (("atelier_page_prec", -1, "atl_pp"), ("atelier_page_suiv", 1, "atl_ps")):
            p += ['getlocal 11', 'pushstring "%s"' % nom, 'ifne ' + et,
                  L1Y, 'getproperty QName(%s,"_-B2M")' % FGV, 'convert_i',
                  'pushbyte %d' % delta, 'add', 'convert_i', 'setlocal 12'] + aller_page() + [et + ':']
    p += ['jump atl_fin']
    # ---- clavier : Entree dans la case de page
    p += ['atl_clav:']
    if PAGINATION or CHAMP_RECHERCHE:
        p += ['getlocal1', TYPE, 'pushstring "WKE_KEY_UP"', 'ifne atl_sel',
              'getlocal2', NAME, 'coerce_s', 'setlocal 11']
    if CHAMP_RECHERCHE:
        p += ['getlocal 11', 'pushstring "atelier_cat_recherche"', 'ifne atl_kp'] + recherche_categorie() + ['atl_kp:']
    if PAGINATION:
        p += ['getlocal 11', 'pushstring "atelier_page_saisie"', 'ifne atl_fin',
              'getlocal1', 'getlex ' + KBD, 'astypelate', 'coerce ' + KBD,
              'getproperty QName(PackageNamespace(""),"keyCode")', 'pushbyte 13', 'ifne atl_fin',
              'getlocal2', CAPTION_GET, 'convert_i', 'pushbyte 1', 'subtract', 'convert_i', 'setlocal 12'] + aller_page()
    if PAGINATION or CHAMP_RECHERCHE:
        p += ['jump atl_fin']
    # ---- selections : Categorie / Annee
    p += ['atl_sel:']
    if CATEGORIES:
        p += ['getlocal1', TYPE, 'pushstring "WE_SELECTED"', 'ifne atl_fin',
              'getlocal2', NAME, 'coerce_s', 'setlocal 11',
              'getlocal 11', 'pushstring "atelier_categorie"', 'ifne atl_an']
        # collection choisie (libelle) -> « =,code1,code2, » : les lignes de furnidata
        # qui portent ce nom ; « Toutes » -> rien ; un libelle sans nom connu est la ligne
        p += ['getlocal2', 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 14',
              'getlocal 14', SEL_GET, 'convert_i', 'setlocal 12',
              'pushstring ""', 'setlocal 9',
              'getlocal 12', 'pushbyte 0', 'ifle atl_ci_m',
              'getlocal 14', ENUM, 'coerce_a', 'setlocal 13',
              'getlocal 13', 'iffalse atl_ci_m',
              'getlocal 12', 'getlocal 13', LENGTH, 'ifge atl_ci_m',
              'getlocal 13', 'getlocal 12', MULTI_L, 'coerce_s', 'setlocal 13',
              p_table(), 'pushstring "|"', 'callproperty QName(%s,"split"), 1' % AS3, 'coerce_a', 'setlocal 15',
              'pushstring ""', 'setlocal 16',
              'pushbyte 0', 'setlocal 17',
              'jump atl_ci_t',
              'atl_ci_b:', 'label',
              'getlocal 15', 'getlocal 17', MULTI_L, 'coerce_s', 'setlocal 9',
              'getlocal 9', 'pushstring "="', INDEXOF, 'convert_i', 'setlocal 10',
              'getlocal 10', 'pushbyte 0', 'ifle atl_ci_n',
              'getlocal 9', 'getlocal 10', 'pushbyte 1', 'add', 'callproperty QName(%s,"substr"), 1' % AS3,
              'coerce_s', 'getlocal 13', 'ifne atl_ci_n',
              'getlocal 16', 'pushstring ","', 'add', 'getlocal 9', 'pushbyte 0', 'getlocal 10',
              'callproperty QName(%s,"substring"), 2' % AS3, 'add', 'coerce_s', 'setlocal 16',
              'atl_ci_n:', 'inclocal_i 17',
              'atl_ci_t:', 'getlocal 17', 'getlocal 15', LENGTH, 'iflt atl_ci_b',
              'getlocal 13', 'pushstring "%s"' % SANS_COLLECTION, 'ifne atl_ci_sc',
              'getlocal 16', 'pushstring ","', 'add', 'coerce_s', 'setlocal 16',
              'atl_ci_sc:',
              'getlocal 16', LENGTH, 'pushbyte 0', 'ifne atl_ci_ok',
              'pushstring ","', 'getlocal 13', LOWER, 'add', 'coerce_s', 'setlocal 16',
              'atl_ci_ok:',
              'pushstring "="', 'getlocal 16', 'add', 'pushstring ","', 'add', 'coerce_s', 'setlocal 9',
              'atl_ci_m:']
        p += [Y1, 'pushstring "atelier_motif_cat"', FIND, 'coerce_a', 'setlocal 13',
              'getlocal 13', 'iffalse atl_cm', 'getlocal 13', 'getlocal 9', CAPTION_SET, 'atl_cm:']
        p += annees_de_la_famille()
        p += [Y1, 'pushstring "atelier_annee"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 14',
              'getlocal 14', 'iffalse atl_r', 'getlocal 14', SEL_GET, 'pushbyte 0', 'ifle atl_r',
              'getlocal 14', 'pushbyte 0', SEL_SET, 'atl_r:',
              'findpropstrict QName(PackageNamespace(""),"updateGridFilters")',
              'callpropvoid QName(PackageNamespace(""),"updateGridFilters"), 0', 'returnvoid',
              'atl_an:', 'getlocal 11', 'pushstring "atelier_annee"', 'ifne atl_fin',
              'getlocal2', 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 14',
              'getlocal 14', SEL_GET, 'convert_i', 'setlocal 12',
              # annee choisie = libelle affiche ; « Toutes » (0) ou illisible = vide
              'pushstring ""', 'setlocal 11',
              'getlocal 12', 'pushbyte 0', 'ifle atl_y',
              'getlocal 14', ENUM, 'coerce_a', 'setlocal 10',
              'getlocal 10', 'iffalse atl_y',
              'getlocal 12', 'getlocal 10', LENGTH, 'ifge atl_y',
              'getlocal 10', 'getlocal 12', MULTI_L, 'coerce_s', 'setlocal 11',
              'atl_y:']
        p += [Y1, 'pushstring "atelier_motif_annee"', FIND, 'coerce_a', 'setlocal 13',
              'getlocal 13', 'iffalse atl_y3', 'getlocal 13', 'getlocal 11', CAPTION_SET, 'atl_y3:',
              'findpropstrict QName(PackageNamespace(""),"updateGridFilters")',
              'callpropvoid QName(PackageNamespace(""),"updateGridFilters"), 0', 'returnvoid']
    p += ['atl_fin:']
    i = w.index("pushscope")
    w = w[:i + 1] + p + w[i + 1:]
    chemin = os.path.join(TRAVAIL, "wep.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(w))
    return chemin


def populate_filter_options():
    p = open(os.path.join(TRAVAIL, "pf.orig.pcode"), encoding="utf-8").read().split("\n")
    assert p[5] == "maxstack 3" and p[6] == "localcount 6", (p[5], p[6])
    p[5] = "maxstack 48"; p[6] = "localcount 15"
    fin = len(p) - 1 - p[::-1].index("returnvoid")
    aj = []
    if CATEGORIES:
        # l'Annee d'abord : choisir la categorie remet l'annee a zero (menu vide = erreur)
        for nom, liste, et in (("atelier_annee", ANNEES, "atl_p2"), ("atelier_categorie", CATS_DEBUT, "atl_p1")):
            aj += [Y1, 'pushstring "%s"' % nom, FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 7',
                   'getlocal 7', 'iffalse ' + et, 'getlocal 7']
            aj += ['pushstring "%s"' % v for v in liste]
            aj += ['newarray %d' % len(liste), POP, 'getlocal 7', 'pushbyte 0', SEL_SET, et + ':']
    if ICONES:
        aj += couleurs('atlp', actif=0)
    p = p[:fin] + aj + p[fin:]
    chemin = os.path.join(TRAVAIL, "pf.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(p))
    return chemin


def update_paging():
    """Garde la liste de numeros d'origine cachee ; met a jour « Page [n] / N »."""
    u = open(os.path.join(TRAVAIL, "up.orig.pcode"), encoding="utf-8").read().split("\n")
    assert u[5] == "maxstack 3" and u[6] == "localcount 7", (u[5], u[6])
    u[5] = "maxstack 12"; u[6] = "localcount 10"
    fin = len(u) - 1 - u[::-1].index("returnvoid")
    pages = 'getlex QName(%s,"_pages")' % FGV
    aj = [pages, 'pushfalse', 'setproperty QName(%s,"visible")' % IW,
          pages, 'getproperty QName(%s,"parent")' % IW, 'coerce_a', 'setlocal 7',
          'getlocal 7', 'iffalse atl_u1',
          'getlocal 7', 'pushstring "atelier_page_saisie"', FIND, 'coerce_a', 'setlocal 8',
          'getlocal 8', 'iffalse atl_u0',
          'getlocal 8', 'getlex QName(%s,"_-B2M")' % FGV, 'pushbyte 1', 'add', 'convert_s', CAPTION_SET,
          'atl_u0:',
          'getlocal 7', 'pushstring "atelier_page_total"', FIND, 'coerce_a', 'setlocal 8',
          'getlocal 8', 'iffalse atl_u1',
          'getlocal 8', 'pushstring "/ "', 'getlex QName(%s,"pageCount")' % FGV, 'convert_i', 'add', CAPTION_SET,
          'atl_u1:']
    u = u[:fin] + aj + u[fin:]
    chemin = os.path.join(TRAVAIL, "up.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


# ================================================================== surlignage
def capture_salle(CEH):
    """
    « atelier:capture » (chuchotement de l'Atelier, non affiche) : photographie
    la vue de la salle comme :screenshot (canevas -> takeScreenShot -> PNG avec
    l'encodeur du jeu), mais l'ecrit sans boite de dialogue dans
    CAPTURE_FICHIER, ou l'Atelier la lit. Aucune autorisation macOS en jeu.
    Registres (libres ici, la branche se termine par returnvoid) : 7 moteur,
    8 salle, 9 numero de canevas, 10 canevas puis flux, 11 image, 12 fichier.
    """
    PUB = 'PackageNamespace("")'
    FILE = 'QName(PackageNamespace("flash.filesystem"),"File")'
    FS = 'QName(PackageNamespace("flash.filesystem"),"FileStream")'
    return [
        'getlocal 6', 'pushstring "atelier:capture"', INDEXOF, 'pushbyte 0', 'ifne atl_pas_capture',
        'getlex QName(%s,"_-017")' % CEH, 'getproperty QName(PackageNamespace(""),"roomEngine")', 'coerce_a', 'setlocal 7',
        'getlocal1', 'getproperty QName(PackageNamespace(""),"session")',
        'getproperty QName(Namespace("com.sulake.habbo.session:IRoomSession"),"roomId")', 'convert_i', 'setlocal 8',
        'pushbyte 0', 'setlocal 9',
        'atl_cv:', 'label',
        'getlocal 9', 'pushbyte 20', 'ifge atl_cap_fin',
        'getlocal 7', 'getlocal 8', 'getlocal 9',
        'callproperty QName(%s,"getRoomCanvas"), 2' % PUB, 'coerce_a', 'setlocal 10',
        'getlocal 10', 'iftrue atl_cv_ok',
        'inclocal_i 9', 'jump atl_cv',
        'atl_cv_ok:',
        'getlocal 10', 'callproperty QName(Namespace("com.sulake.room.renderer:IRoomRenderingCanvas"),"takeScreenShot"), 0',
        'coerce_a', 'setlocal 11',
        'getlocal 11', 'iffalse atl_cap_fin',
        'getlex QName(PackageNamespace("com.sulake.core.utils.images"),"_-s1R")', 'getlocal 11',
        'callproperty QName(%s,"encode"), 1' % PUB, 'coerce_a', 'setlocal 11',
        # dossier personnel (Mac et Windows) : File.userDirectory.resolvePath(...)
        'getlex ' + FILE, 'getproperty QName(%s,"userDirectory")' % PUB,
        'pushstring "%s"' % CAPTURE_FICHIER, 'callproperty QName(%s,"resolvePath"), 1' % PUB, 'coerce_a', 'setlocal 12',
        'findpropstrict ' + FS, 'constructprop %s, 0' % FS, 'coerce_a', 'setlocal 10',
        'getlocal 10', 'getlocal 12', 'pushstring "write"', 'callpropvoid QName(%s,"open"), 2' % PUB,
        'getlocal 10', 'getlocal 11', 'callpropvoid QName(%s,"writeBytes"), 1' % PUB,
        'getlocal 10', 'callpropvoid QName(%s,"close"), 0' % PUB,
        'atl_cap_fin:', 'returnvoid',
        'atl_pas_capture:']


CAPTURE_FICHIER = ".atelier-capture.png"   # dans le dossier personnel


def annuler_deplacement(CEH):
    """
    « atelier:annuler » (Echap, vu par l'Atelier) : le mobi pris pour etre
    deplace (Option + clic) est relache et remis a sa place d'origine, comme le
    fait le jeu quand on ferme l'inventaire (cancelRoomObjectInsert).
    """
    return ['getlocal 6', 'pushstring "atelier:annuler"', 'ifne atl_pas_annuler',
            'getlex QName(%s,"_-017")' % CEH, 'getproperty QName(PackageNamespace(""),"roomEngine")',
            'callpropvoid QName(Namespace("com.sulake.habbo.room:IRoomEngine"),"cancelRoomObjectInsert"), 0',
            'returnvoid', 'atl_pas_annuler:']


def trouver_canevas(CEH, n):
    """Registres 7 moteur, 8 salle, 10 canevas de la salle (sinon saute a atl_<n>_fin)."""
    return [
        'getlex QName(%s,"_-017")' % CEH, 'getproperty QName(PackageNamespace(""),"roomEngine")', 'coerce_a', 'setlocal 7',
        'getlocal1', 'getproperty QName(PackageNamespace(""),"session")',
        'getproperty QName(Namespace("com.sulake.habbo.session:IRoomSession"),"roomId")', 'convert_i', 'setlocal 8',
        'pushbyte 0', 'setlocal 9',
        'atl_%s_cv:' % n, 'label',
        'getlocal 9', 'pushbyte 20', 'ifge atl_%s_fin' % n,
        'getlocal 7', 'getlocal 8', 'getlocal 9',
        'callproperty QName(PackageNamespace(""),"getRoomCanvas"), 2', 'coerce_a', 'setlocal 10',
        'getlocal 10', 'iftrue atl_%s_ok' % n,
        'inclocal_i 9', 'jump atl_%s_cv' % n,
        'atl_%s_ok:' % n]


def grille_salle(CEH):
    """
    « atelier:grille=<plan> » : le plan du sol (lignes separees par « / »,
    « x » = pas de case, sinon hauteur en base 36) est confie a un MovieClip
    « atelier_grille » pose AU-DESSUS des mobis dans le canevas de la salle.
    Le dessin des traits se fait dans le rendu (rendu_grille), qui suit la vue.
    « atelier:grille= » seul cache la grille. Registres : 11 conteneur, 12 grille.
    """
    PUB = 'PackageNamespace("")'
    MC = 'QName(PackageNamespace("flash.display"),"MovieClip")'
    def pose(nom, val):
        return ['getlocal 12', val, 'setproperty QName(%s,"%s")' % (PUB, nom)]
    return (['getlocal 6', 'pushstring "atelier:grille="', INDEXOF, 'pushbyte 0', 'ifne atl_pas_grille']
        + trouver_canevas(CEH, 'gr')
        + ['getlocal 10', 'getproperty QName(%s,"displayObject")' % PUB, 'coerce_a', 'setlocal 11',
           'getlocal 11', 'pushstring "atelier_grille"', 'callproperty QName(%s,"getChildByName"), 1' % PUB,
           'coerce_a', 'setlocal 12',
           'getlocal 12', 'iftrue atl_gr_a',
           'findpropstrict ' + MC, 'constructprop %s, 0' % MC, 'coerce_a', 'setlocal 12']
        + pose('name', 'pushstring "atelier_grille"') + pose('mouseEnabled', 'pushfalse')
        + pose('mouseChildren', 'pushfalse') + pose('alpha', 'pushdouble 0.55')
        + ['getlocal 11', 'getlocal 12', 'callpropvoid QName(%s,"addChild"), 1' % PUB,
           'atl_gr_a:',
           'getlocal 12', 'getlocal 6', 'pushbyte 15', 'callproperty QName(%s,"substr"), 1' % AS3,
           'setproperty QName(%s,"atl_d")' % PUB]
        + pose('atl_u', 'pushbyte -1')
        + ['getlocal 6', LENGTH, 'pushbyte 15', 'ifgt atl_gr_vis']
        + pose('visible', 'pushfalse') + ['jump atl_gr_fin', 'atl_gr_vis:']
        + pose('visible', 'pushtrue')
        + ['atl_gr_fin:', 'returnvoid', 'atl_pas_grille:'])


GRILLE_COULEUR = 0x000000
GRILLE_REMONTE = 2      # pixels, au zoom 1 (case de 64 px)


def rendu_grille():
    """
    RoomSpriteCanvas.render (fin) : si le canevas porte la grille de l'Atelier
    (« atelier_grille », voir grille_salle), elle prend la position et
    l'echelle du calque des mobis ; et quand la geometrie change (zoom, salle),
    ses traits sont redessines : le contour de chaque case, a sa hauteur, avec
    la projection du jeu (getScreenPoint), donc juste a tous les zooms.
    Registres ajoutes : 11 grille, 12 graphics, 13 lignes, 14 y, 15 ligne,
    16 x, 17 hauteur, 18 caractere puis point.
    """
    PUB = 'PackageNamespace("")'
    RSC = 'PrivateNamespace("com.sulake.room.renderer:RoomSpriteCanvas")'
    V3 = 'QName(PackageNamespace("com.sulake.room.utils"),"Vector3d")'
    GEOM = 'getlex QName(%s,"_geometry")' % RSC
    DISP = 'getlex QName(%s,"_display")' % RSC
    u = open(os.path.join(TRAVAIL, "render.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = u.index("maxstack 7"), u.index("localcount 11")
    u[im] = "maxstack 16"; u[il] = "localcount 20"
    c = ['getlex QName(%s,"_-Tr")' % RSC, 'pushstring "atelier_grille"',
         'callproperty QName(%s,"getChildByName"), 1' % PUB, 'coerce_a', 'setlocal 11',
         'getlocal 11', 'iffalse atl_g_fin']
    # Le jeu place chaque sprite a « point ecran + int(largeur/2) » (et la
    # moitie de la hauteur) dans le calque des mobis : la grille aussi.
    for k, ech, moitie in (("x", "scaleX", "_-Yd"), ("y", "scaleY", "_-E1E")):
        c += ['getlocal 11', DISP, 'getproperty QName(%s,"%s")' % (PUB, k),
              DISP, 'getproperty QName(%s,"%s")' % (PUB, ech),
              'getlex QName(%s,"%s")' % (RSC, moitie), 'pushbyte 2', 'divide', 'convert_i', 'multiply', 'add',
              'setproperty QName(%s,"%s")' % (PUB, k)]
    # Les traits tombaient ~2 px trop bas (zoom 1) : remontes d'autant, a l'echelle du zoom.
    c += ['getlocal 11', 'getlocal 11', 'getproperty QName(%s,"y")' % PUB,
          GEOM, 'getproperty QName(%s,"scale")' % PUB, 'pushbyte %d' % (64 // GRILLE_REMONTE), 'divide',
          DISP, 'getproperty QName(%s,"scaleY")' % PUB, 'multiply', 'subtract',
          'setproperty QName(%s,"y")' % PUB]
    for k in ("scaleX", "scaleY"):
        c += ['getlocal 11', DISP, 'getproperty QName(%s,"%s")' % (PUB, k), 'setproperty QName(%s,"%s")' % (PUB, k)]
    c += ['getlocal 11', 'getproperty QName(%s,"atl_u")' % PUB, GEOM, 'getproperty QName(%s,"updateId")' % PUB,
          'ifeq atl_g_fin',
          'getlocal 11', GEOM, 'getproperty QName(%s,"updateId")' % PUB, 'setproperty QName(%s,"atl_u")' % PUB,
          'getlocal 11', 'getproperty QName(%s,"graphics")' % PUB, 'coerce_a', 'setlocal 12',
          'getlocal 12', 'callpropvoid QName(%s,"clear"), 0' % PUB,
          'getlocal 12', 'pushbyte 1', 'pushint %d' % GRILLE_COULEUR, 'pushbyte 1', 'pushtrue', 'pushstring "none"',
          'callpropvoid QName(%s,"lineStyle"), 5' % PUB,
          'getlocal 11', 'getproperty QName(%s,"atl_d")' % PUB, 'coerce_s', 'pushstring "/"',
          'callproperty QName(%s,"split"), 1' % AS3, 'coerce_a', 'setlocal 13',
          'pushbyte 0', 'setlocal 14',
          'atl_gy:', 'label',
          'getlocal 14', 'getlocal 13', LENGTH, 'ifge atl_g_fin',
          'getlocal 13', 'getlocal 14', MULTI_L, 'coerce_s', 'setlocal 15',
          'pushbyte 0', 'setlocal 16',
          'atl_gx:', 'label',
          'getlocal 16', 'getlocal 15', LENGTH, 'ifge atl_gy_next',
          'getlocal 15', 'getlocal 16', 'callproperty QName(%s,"charAt"), 1' % AS3, 'coerce_s', 'setlocal 18',
          'getlocal 18', 'pushstring "x"', 'ifeq atl_gx_next',
          'findpropstrict QName(PackageNamespace(""),"parseInt")', 'getlocal 18', 'pushbyte 36',
          'callproperty QName(PackageNamespace(""),"parseInt"), 2', 'convert_d', 'setlocal 17',
          'getlocal 17', 'getlocal 17', 'ifne atl_gx_next']          # NaN : pas une case
    coins = [('subtract', 'subtract', 'moveTo'), ('add', 'subtract', 'lineTo'), ('add', 'add', 'lineTo'),
             ('subtract', 'add', 'lineTo'), ('subtract', 'subtract', 'lineTo')]
    for ox, oy, trait in coins:
        c += [GEOM, 'findpropstrict ' + V3,
              'getlocal 16', 'convert_d', 'pushdouble 0.5', ox,
              'getlocal 14', 'convert_d', 'pushdouble 0.5', oy,
              'getlocal 17', 'constructprop %s, 3' % V3,
              'callproperty QName(%s,"getScreenPoint"), 1' % PUB, 'coerce_a', 'setlocal 18',
              'getlocal 18', 'iffalse atl_gx_next',
              'getlocal 12', 'getlocal 18', 'getproperty QName(%s,"x")' % PUB,
              'getlocal 18', 'getproperty QName(%s,"y")' % PUB, 'callpropvoid QName(%s,"%s"), 2' % (PUB, trait)]
    c += ['atl_gx_next:', 'inclocal_i 16', 'jump atl_gx',
          'atl_gy_next:', 'inclocal_i 14', 'jump atl_gy',
          'atl_g_fin:']
    k = len(u) - 1 - u[::-1].index("returnvoid")
    u = u[:k] + c + u[k:]
    chemin = os.path.join(TRAVAIL, "render.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


def zone_salle(CEH):
    """
    « atelier:zone=1 » / « atelier:zone=0 » : pendant que l'Atelier attend les
    coins d'une zone, les mobis ne prennent plus les clics (clic_sol) ; on vise
    la case du sol derriere, meme cachee par un mobi haut. Le drapeau « atl_z »
    est porte par le MovieClip de la grille (cree vide au besoin, atl_d = "").
    Registres : 11 conteneur, 12 grille.
    """
    PUB = 'PackageNamespace("")'
    MC = 'QName(PackageNamespace("flash.display"),"MovieClip")'
    def pose(nom, val):
        return ['getlocal 12', val, 'setproperty QName(%s,"%s")' % (PUB, nom)]
    return (['getlocal 6', 'pushstring "atelier:zone="', INDEXOF, 'pushbyte 0', 'ifne atl_pas_zone']
        + trouver_canevas(CEH, 'zo')
        + ['getlocal 10', 'getproperty QName(%s,"displayObject")' % PUB, 'coerce_a', 'setlocal 11',
           'getlocal 11', 'pushstring "atelier_grille"', 'callproperty QName(%s,"getChildByName"), 1' % PUB,
           'coerce_a', 'setlocal 12',
           'getlocal 12', 'iftrue atl_zo_a',
           'findpropstrict ' + MC, 'constructprop %s, 0' % MC, 'coerce_a', 'setlocal 12']
        + pose('name', 'pushstring "atelier_grille"') + pose('mouseEnabled', 'pushfalse')
        + pose('mouseChildren', 'pushfalse') + pose('alpha', 'pushdouble 0.55')
        + pose('atl_d', 'pushstring ""') + pose('atl_u', 'pushbyte -1')
        + ['getlocal 11', 'getlocal 12', 'callpropvoid QName(%s,"addChild"), 1' % PUB,
           'atl_zo_a:',
           'getlocal 12', 'getlocal 6', 'pushstring "atelier:zone=1"', 'equals',
           'setproperty QName(%s,"atl_z")' % PUB,
           'atl_zo_fin:', 'returnvoid', 'atl_pas_zone:'])


def dalles_salle(CEH):
    """
    « atelier:dalles=1 » : le client note les numeros d'instance de ses dalles
    magiques (type tile_stackmagic*) dans « atl_t » de la grille (« ,12,34, ») ;
    clic_sol leur fait ignorer les clics : un clic ou un double-clic tombe sur
    le mobi pose dessus (changer son etat). « atelier:dalles=0 » : comme avant.
    Registres : 11 conteneur, 12 grille, 9 indice, 10 objet, 6 liste.
    """
    PUB = 'PackageNamespace("")'
    MC = 'QName(PackageNamespace("flash.display"),"MovieClip")'
    RE = 'Namespace("com.sulake.habbo.room:IRoomEngine")'
    RO = 'Namespace("com.sulake.room.object:IRoomObject")'
    def pose(nom, val):
        return ['getlocal 12', val, 'setproperty QName(%s,"%s")' % (PUB, nom)]
    return (['getlocal 6', 'pushstring "atelier:dalles="', INDEXOF, 'pushbyte 0', 'ifne atl_pas_dalles']
        + trouver_canevas(CEH, 'da')
        + ['getlocal 10', 'getproperty QName(%s,"displayObject")' % PUB, 'coerce_a', 'setlocal 11',
           'getlocal 11', 'pushstring "atelier_grille"', 'callproperty QName(%s,"getChildByName"), 1' % PUB,
           'coerce_a', 'setlocal 12',
           'getlocal 12', 'iftrue atl_da_a',
           'findpropstrict ' + MC, 'constructprop %s, 0' % MC, 'coerce_a', 'setlocal 12']
        + pose('name', 'pushstring "atelier_grille"') + pose('mouseEnabled', 'pushfalse')
        + pose('mouseChildren', 'pushfalse') + pose('alpha', 'pushdouble 0.55')
        + pose('atl_d', 'pushstring ""') + pose('atl_u', 'pushbyte -1')
        + ['getlocal 11', 'getlocal 12', 'callpropvoid QName(%s,"addChild"), 1' % PUB,
           'atl_da_a:',
           'pushstring ","', 'setlocal 6',
           'pushbyte 0', 'setlocal 9',
           'atl_da_b:', 'label',
           'getlocal 9', 'getlocal 7', 'getlocal 8', 'pushbyte 10',
           'callproperty QName(%s,"getRoomObjectCount"), 2' % RE, 'ifge atl_da_f',
           'getlocal 7', 'getlocal 8', 'getlocal 9', 'pushbyte 10',
           'callproperty QName(%s,"getRoomObjectWithIndex"), 3' % RE, 'coerce_a', 'setlocal 10',
           'getlocal 10', 'iffalse atl_da_n',
           'getlocal 10', 'callproperty QName(%s,"getType"), 0' % RO, 'coerce_s',
           'pushstring "tile_stackmagic"', INDEXOF, 'pushbyte 0', 'ifne atl_da_n',
           'getlocal 6', 'getlocal 10', 'callproperty QName(%s,"getInstanceId"), 0' % RO, 'add',
           'pushstring ","', 'add', 'coerce_s', 'setlocal 6',
           'atl_da_n:', 'inclocal_i 9', 'jump atl_da_b',
           'atl_da_f:',
           # « =0 » : plus rien d'ignore
           'getlocal1', 'getproperty QName(PackageNamespace(""),"text")', 'coerce_s', 'pushstring "atelier:dalles=1"',
           'ifeq atl_da_o', 'pushstring ""', 'setlocal 6', 'atl_da_o:']
        + pose('atl_t', 'getlocal 6')
        + ['atl_da_fin:', 'returnvoid', 'atl_pas_dalles:'])


def clic_sol():
    """
    Sprite de la salle (§_-s9§).hitTestPoint : si la grille de l'Atelier porte
    atl_z (choix d'une zone en cours), seuls les plans (sol, murs : etiquette
    « plane... ») repondent au clic ; les mobis et avatars laissent passer.
    Le sprite est dans le calque des mobis, lui-meme dans le conteneur du
    canevas qui porte la grille : parent.parent.
    """
    PUB = 'PackageNamespace("")'
    return "\n".join([
        'method', 'name "hitTestPoint"', 'flag HAS_OPTIONAL',
        'param QName(PackageNamespace(""),"Number")', 'param QName(PackageNamespace(""),"Number")',
        'param QName(PackageNamespace(""),"Boolean")', 'optional False()',
        'returns QName(PackageNamespace(""),"Boolean")', '',
        'body', 'maxstack 10', 'localcount 7', 'initscopedepth 0', 'maxscopedepth 1', '',
        'code', 'getlocal0', 'pushscope',
        'getlocal0', 'getproperty QName(%s,"tag")' % PUB, 'coerce_s', 'setlocal 4',
        'getlocal 4', 'iffalse atl_h_z',
        'getlocal 4', 'pushstring "plane"', INDEXOF, 'pushbyte 0', 'ifeq atl_h_ok',
        'atl_h_z:',
        'getlocal0', 'getproperty QName(%s,"parent")' % PUB, 'coerce_a', 'setlocal 5',
        'getlocal 5', 'iffalse atl_h_ok',
        'getlocal 5', 'getproperty QName(%s,"parent")' % PUB, 'coerce_a', 'setlocal 5',
        'getlocal 5', 'iffalse atl_h_ok',
        'getlocal 5', 'pushstring "atelier_grille"', 'callproperty QName(%s,"getChildByName"), 1' % PUB,
        'coerce_a', 'setlocal 6',
        'getlocal 6', 'iffalse atl_h_ok',
        'getlocal 6', 'getproperty QName(%s,"atl_z")' % PUB, 'iffalse atl_h_t',
        'pushfalse', 'returnvalue',
        # dalles magiques (atelier:dalles=1) : le clic passe au mobi pose dessus
        'atl_h_t:',
        'getlocal 6', 'getproperty QName(%s,"atl_t")' % PUB, 'coerce_s', 'setlocal 4',
        'getlocal 4', 'iffalse atl_h_ok',
        'getlocal 4', 'pushstring ","', 'getlocal0', 'getproperty QName(%s,"identifier")' % PUB, 'add',
        'pushstring ","', 'add', INDEXOF, 'pushbyte 0', 'iflt atl_h_ok',
        'pushfalse', 'returnvalue',
        'atl_h_ok:',
        'findpropstrict QName(PackageNamespace(""),"hitTest")', 'getlocal1', 'getlocal2',
        'callproperty QName(PackageNamespace(""),"hitTest"), 2', 'returnvalue',
        'end ; code', 'end ; body', 'end ; method'])


def ecrire_clic_sol():
    chemin = os.path.join(TRAVAIL, "hittest.pcode")
    open(chemin, "w", encoding="utf-8").write(clic_sol())
    return chemin


def chat_salle():
    """
    ChatEventHandler.onRoomChat : un chuchotement « atelier:surligner=s12,s34,m56 »
    envoye par l'Atelier n'est pas affiche ; il eteint la lueur de tous les mobis
    de la salle, puis l'allume sur ceux de la liste (s = sol, m = mural).
    « atelier:surligner= » seul eteint tout. Les autres messages passent.
    """
    CEH = 'PrivateNamespace("com.sulake.habbo.freeflowchat.data:ChatEventHandler")'
    RE = 'Namespace("com.sulake.habbo.room:IRoomEngine")'
    RO = 'Namespace("com.sulake.room.object:IRoomObject")'
    FVIS = 'QName(PackageNamespace("com.sulake.habbo.room.object.visualization.furniture"),"FurnitureVisualization")'
    CMF = 'QName(PackageNamespace("flash.filters"),"ColorMatrixFilter")'
    GLOW = 'QName(PackageNamespace("flash.filters"),"GlowFilter")'
    FILTRES = 'setproperty QName(PackageNamespace(""),"filters")'
    c = open(os.path.join(TRAVAIL, "chat.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = c.index("maxstack 6"), c.index("localcount 6")
    c[im] = "maxstack 40"; c[il] = "localcount 13"
    p = ['getlocal1', 'getproperty QName(PackageNamespace(""),"text")', 'coerce_s', 'setlocal 6']
    p += capture_salle(CEH)
    p += annuler_deplacement(CEH)
    if GRILLE:
        p += grille_salle(CEH)
        p += zone_salle(CEH)
        p += dalles_salle(CEH)
    p += ['getlocal 6', 'pushstring "atelier:surligner="', INDEXOF, 'pushbyte 0', 'ifne atl_normal',
         'getlex QName(%s,"_-017")' % CEH, 'getproperty QName(PackageNamespace(""),"roomEngine")', 'coerce_a', 'setlocal 7',
         'getlocal1', 'getproperty QName(PackageNamespace(""),"session")',
         'getproperty QName(Namespace("com.sulake.habbo.session:IRoomSession"),"roomId")', 'convert_i', 'setlocal 8',
         # 1. eteindre : tous les mobis de sol (10) puis muraux (20)
         'pushbyte 10', 'setlocal 9',
         'atl_cat:', 'label',
         'pushbyte 0', 'setlocal 10',
         'atl_clr:', 'label',
         'getlocal 10', 'getlocal 7', 'getlocal 8', 'getlocal 9',
         'callproperty QName(%s,"getRoomObjectCount"), 2' % RE, 'ifge atl_clr_fin',
         'getlocal 7', 'getlocal 8', 'getlocal 10', 'getlocal 9',
         'callproperty QName(%s,"getRoomObjectWithIndex"), 3' % RE, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'iffalse atl_clr_next',
         'getlocal 12', 'callproperty QName(%s,"getVisualization"), 0' % RO, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'getlex ' + FVIS, 'istypelate', 'iffalse atl_clr_next',
         'getlocal 12', 'pushnull', FILTRES,
         'atl_clr_next:', 'inclocal_i 10', 'jump atl_clr',
         'atl_clr_fin:',
         'getlocal 9', 'pushbyte 20', 'ifeq atl_parse',
         'pushbyte 20', 'setlocal 9', 'jump atl_cat',
         # 2. allumer chaque mobi de la liste
         'atl_parse:',
         'getlocal 6', 'pushbyte 18', 'callproperty QName(%s,"substr"), 1' % AS3,
         'pushstring ","', 'callproperty QName(%s,"split"), 1' % AS3, 'coerce_a', 'setlocal 11',
         'atl_tok:', 'label',
         'getlocal 11', LENGTH, 'pushbyte 0', 'ifle atl_fin',
         'getlocal 11', 'callproperty QName(%s,"shift"), 0' % AS3, 'coerce_s', 'setlocal 12',
         'pushbyte 10', 'setlocal 9',
         'getlocal 12', 'pushbyte 0', 'callproperty QName(%s,"charAt"), 1' % AS3, 'pushstring "m"', 'ifne atl_sol',
         'pushbyte 20', 'setlocal 9',
         'atl_sol:',
         'findpropstrict QName(PackageNamespace(""),"parseInt")',
         'getlocal 12', 'pushbyte 1', 'callproperty QName(%s,"substr"), 1' % AS3,
         'callproperty QName(PackageNamespace(""),"parseInt"), 1', 'convert_i', 'setlocal 10',
         'getlocal 7', 'getlocal 8', 'getlocal 10', 'getlocal 9',
         'callproperty QName(%s,"getRoomObject"), 3' % RE, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'iffalse atl_tok',
         'getlocal 12', 'callproperty QName(%s,"getVisualization"), 0' % RO, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'getlex ' + FVIS, 'istypelate', 'iffalse atl_tok',
         'getlocal 12',
         # halo jaune seul ; FurnitureVisualization.updateSpriteFilters le reconnait
         # a sa couleur et ne le pose que sur la couche de base (pas de trait
         # au milieu du mobi, aux jonctions des couches).
         'findpropstrict ' + GLOW,
         'pushint %d' % HALO,
         'pushbyte 1', 'pushbyte 8', 'pushbyte 8', 'pushbyte 3', 'pushbyte 2', 'pushfalse', 'pushfalse',
         'constructprop %s, 8' % GLOW,
         'newarray 1', FILTRES,
         'jump atl_tok',
         'atl_fin:', 'returnvoid',
         'atl_normal:']
    i = c.index("pushscope")
    c = c[:i + 1] + p + c[i + 1:]
    chemin = os.path.join(TRAVAIL, "chat.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(c))
    return chemin


def filtres_couche():
    """
    FurnitureVisualization.updateSpriteFilters(echelle, sprite, couche) : quand
    les filtres du mobi sont exactement notre halo (un GlowFilter de couleur HALO),
    seule la couche 0 (la base) le recoit ; les autres couches n'ont rien. Le
    jeu dessine chaque couche a part, sans groupe par mobi : un halo sur chaque
    couche tracait des traits au milieu du mobi. Tout autre filtre (selection
    de l'editeur wired...) suit le chemin d'origine.
    """
    SPR = 'Namespace("com.sulake.room.object.visualization:IRoomObjectSprite")'
    FVN = 'PrivateNamespace("com.sulake.habbo.room.object.visualization.furniture:FurnitureVisualization")'
    GLOWQ = 'QName(PackageNamespace("flash.filters"),"GlowFilter")'
    u = open(os.path.join(TRAVAIL, "usf.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = u.index("maxstack 4"), u.index("localcount 6")
    u[im] = "maxstack 12"; u[il] = "localcount 8"
    p = ['getlex QName(%s,"_filters")' % FVN, 'coerce_a', 'setlocal 6',
         'getlocal 6', 'iffalse atl_v_fin',
         'getlocal 6', LENGTH, 'pushbyte 1', 'ifne atl_v_fin',
         'getlocal 6', 'pushbyte 0', MULTI_L, 'coerce_a', 'setlocal 7',
         'getlocal 7', 'getlex ' + GLOWQ, 'istypelate', 'iffalse atl_v_fin',
         'getlocal 7', 'getproperty QName(PackageNamespace(""),"color")', 'pushint %d' % HALO, 'ifne atl_v_fin',
         # mobi surligne : aucune couche ne recoit le halo flou, c'est le contour
         # (dessine dans le sprite d'ombre par updateSprites) qui le montre
         'getlocal2', 'pushnull', 'setproperty QName(%s,"filters")' % SPR, 'returnvoid',
         'atl_v_fin:']
    i = u.index("pushscope")
    u = u[:i + 1] + p + u[i + 1:]
    chemin = os.path.join(TRAVAIL, "usf.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


def recherche_etendue():
    """
    La recherche du haut (texte deja en minuscules dans « _-E26 ») trouve un mobi
    par son nom, sa description, son coffre (comme a l'origine), mais aussi sa
    ligne, son nom de classe (donc l'annee), sa categorie (meme regles que
    l'Atelier) et son type (assise, lit, tapis). Registres : 6 texte cherche,
    10 donnees du mobi, 11 ligne, 12 classe.
    """
    IFD = 'Namespace("com.sulake.habbo.session.furniture:IFurnitureData")'
    RX = 'QName(PackageNamespace(""),"RegExp")'
    TEST = 'callproperty QName(%s,"test"), 1' % AS3
    c = ['getlocal 6', 'iffalse atl_q_ok', 'getlocal 6', LENGTH, 'pushbyte 0', 'ifeq atl_q_ok',
         'getlocal 6', LOWER, 'coerce_s', 'setlocal 6']
    def contient(src):
        return src + ['getlocal 6', INDEXOF, 'pushbyte 0', 'ifge atl_q_ok']
    c += contient(['getlocal1', 'getproperty QName(PackageNamespace(""),"name")', 'coerce_s', LOWER])
    c += contient(['getlocal1', 'getproperty QName(PackageNamespace(""),"description")', 'coerce_s', LOWER])
    c += ['getlocal1', 'getproperty QName(PackageNamespace(""),"stuffData")', 'coerce_a', 'setlocal 7',
          'getlocal 7', 'iffalse atl_q_c',
          'getlocal 7', 'getproperty QName(Namespace("com.sulake.habbo.room:IStuffData"),"chestName")', 'coerce_s', 'setlocal 7',
          'getlocal 7', 'iffalse atl_q_c',
          'getlocal 7', LOWER, 'getlocal 6', INDEXOF, 'pushbyte 0', 'ifge atl_q_ok',
          'atl_q_c:']
    c += contient(['getlocal 11']) + contient(['getlocal 12'])
    # collection : son nom francais contient le texte cherche
    c += nom_ligne(11, 7, 10, 9, 'atl_q_col')
    c += ['getlocal 7', LOWER, 'getlocal 6', INDEXOF, 'pushbyte 0', 'ifge atl_q_ok']
    # type : assise / lit / tapis
    c += ['getlocal1', 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 10',
          'getlocal 10', 'iffalse atl_q_non']
    for mot, prop, et in (("assise", "canSitOn", "atl_q_t1"), ("lit", "canLayOn", "atl_q_t2"), ("tapis", "canStandOn", "atl_q_t3")):
        c += ['pushstring "%s"' % mot, 'getlocal 6', INDEXOF, 'pushbyte 0', 'iflt ' + et,
              'getlocal 10', 'getproperty QName(%s,"%s")' % (IFD, prop), 'iftrue atl_q_ok', et + ':']
    c += ['atl_q_non:', 'jump atl_pf_non', 'atl_q_ok:']
    return c


def pass_filter():
    """
    FurniGridView.passFilter(GroupItem) : avant les filtres d'origine, la
    categorie et l'annee choisies (champs caches atelier_motif_*). Famille :
    la ligne du mobi correspond, ou sa ligne n'est d'aucune famille et son nom
    de classe correspond (comme Categories.famille de l'Atelier). Annee : dans
    la ligne ou le nom de classe. Pas d'envoi au serveur : le jeu filtre la
    liste qu'il a deja, instantanement.
    """
    IFD = 'Namespace("com.sulake.habbo.session.furniture:IFurnitureData")'
    RX = 'QName(PackageNamespace(""),"RegExp")'
    TEST = 'callproperty QName(%s,"test"), 1' % AS3
    u = open(os.path.join(TRAVAIL, "pfi.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = u.index("maxstack 2"), u.index("localcount 5")
    u[im] = "maxstack 12"; u[il] = "localcount 13"
    E26 = 'getlex QName(%s,"_-E26")' % FGV
    p = ['getlex QName(%s,"_pages")' % FGV, 'coerce_a', 'setlocal 5',
         'getlocal 5', 'iffalse atl_pf_fin',
         'getlocal 5', 'getproperty QName(%s,"parent")' % IW, 'coerce_a', 'setlocal 5',
         'getlocal 5', 'iffalse atl_pf_fin',
         'pushstring ""', 'setlocal 8', 'pushstring ""', 'setlocal 9',
         'getlocal 5', 'pushstring "atelier_motif_cat"', FIND, 'coerce_a', 'setlocal 6',
         'getlocal 6', 'iffalse atl_pf_a', 'getlocal 6', CAPTION_GET, 'coerce_s', 'setlocal 8', 'atl_pf_a:',
         'getlocal 5', 'pushstring "atelier_motif_annee"', FIND, 'coerce_a', 'setlocal 7',
         'getlocal 7', 'iffalse atl_pf_b', 'getlocal 7', CAPTION_GET, 'coerce_s', 'setlocal 9', 'atl_pf_b:',
         # registre 6 = texte de la recherche du haut, lu sur TOUS les chemins
         # (avant, il gardait le champ cache de la categorie et la recherche plantait)
         (E26 if RECHERCHE else 'pushnull'), 'coerce_s', 'setlocal 6',
         'getlocal 8', 'iffalse atl_pf_c0', 'getlocal 8', LENGTH, 'pushbyte 0', 'ifne atl_pf_go', 'atl_pf_c0:',
         'getlocal 9', 'iffalse atl_pf_q0', 'getlocal 9', LENGTH, 'pushbyte 0', 'ifne atl_pf_go', 'atl_pf_q0:',
         'getlocal 6', 'iffalse atl_pf_fin', 'getlocal 6', LENGTH, 'pushbyte 0', 'ifeq atl_pf_fin',
         'atl_pf_go:',
         # ligne et nom de classe du mobi (en minuscules ; vides si inconnus)
         'pushstring ""', 'setlocal 11', 'pushstring ""', 'setlocal 12',
         'getlocal1', 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 10',
         'getlocal 10', 'iffalse atl_pf_c',
         'getlocal 10', 'getproperty QName(%s,"furniLine")' % IFD, 'coerce_s', 'setlocal 11',
         'getlocal 10', 'getproperty QName(%s,"className")' % IFD, 'coerce_s', 'setlocal 12',
         'atl_pf_c:',
         'getlocal 11', 'iftrue atl_pf_d', 'pushstring ""', 'setlocal 11', 'atl_pf_d:',
         'getlocal 12', 'iftrue atl_pf_e', 'pushstring ""', 'setlocal 12', 'atl_pf_e:',
         'getlocal 11', LOWER, 'coerce_s', 'setlocal 11',
         'getlocal 12', LOWER, 'coerce_s', 'setlocal 12',
         # categorie
         'getlocal 8', 'iffalse atl_pf_y', 'getlocal 8', LENGTH, 'pushbyte 0', 'ifeq atl_pf_y',
         # collection : « =,code1,code2, » contient « ,ligne, »
         'getlocal 8', 'pushbyte 0', 'callproperty QName(%s,"charAt"), 1' % AS3, 'pushstring "="', 'ifne atl_pf_old',
         'getlocal 8', 'pushstring ","', 'getlocal 11', 'add', 'pushstring ","', 'add', INDEXOF,
         'pushbyte 0', 'ifge atl_pf_y', 'jump atl_pf_non',
         'atl_pf_old:',
         'getlocal 8', 'pushbyte 0', 'callproperty QName(%s,"charAt"), 1' % AS3, 'pushstring "!"', 'ifne atl_pf_fam',
         # « Autres » : ni la ligne ni le nom de classe ne sont d'une famille
         'findpropstrict ' + RX, 'getlocal 8', 'pushbyte 1', 'callproperty QName(%s,"substr"), 1' % AS3,
         'pushstring "i"', 'constructprop %s, 2' % RX, 'coerce_a', 'setlocal 10',
         'getlocal 10', 'getlocal 11', TEST, 'iftrue atl_pf_non',
         'getlocal 10', 'getlocal 12', TEST, 'iftrue atl_pf_non',
         'jump atl_pf_y',
         'atl_pf_fam:',
         'getlocal 8', 'pushstring "@@"', 'callproperty QName(%s,"split"), 1' % AS3, 'coerce_a', 'setlocal 10',
         'findpropstrict ' + RX, 'getlocal 10', 'pushbyte 0', MULTI_L, 'pushstring "i"',
         'constructprop %s, 2' % RX, 'coerce_a', 'setlocal 7',
         'getlocal 7', 'getlocal 11', TEST, 'iftrue atl_pf_y',
         'findpropstrict ' + RX, 'getlocal 10', 'pushbyte 1', MULTI_L, 'pushstring "i"',
         'constructprop %s, 2' % RX, 'getlocal 11', TEST, 'iftrue atl_pf_non',
         'getlocal 7', 'getlocal 12', TEST, 'iffalse atl_pf_non',
         # annee
         'atl_pf_y:',
         'getlocal 9', 'iffalse atl_pf_y2', 'getlocal 9', LENGTH, 'pushbyte 0', 'ifeq atl_pf_y2',
         'getlocal 11', 'getlocal 9', INDEXOF, 'pushbyte 0', 'ifge atl_pf_y2',
         'getlocal 12', 'getlocal 9', INDEXOF, 'pushbyte 0', 'ifge atl_pf_y2',
         'jump atl_pf_non',          # annee absente de la ligne et du nom de classe : rejete
         'atl_pf_y2:'] + (recherche_etendue() if RECHERCHE else []) + ['jump atl_pf_fin',
         'atl_pf_non:', 'pushfalse', 'returnvalue',
         'atl_pf_fin:']
    if RECHERCHE:
        # le test de texte d'origine (nom, description, coffre) est fait par
        # recherche_etendue : on le saute (premier « _-E26 length > 0 »)
        k = u.index(E26)
        assert u[k + 1] == LENGTH and u[k + 2] == "pushbyte 0" and u[k + 3].startswith("ifngt "), u[k:k + 4]
        u[k:k + 4] = ["jump " + u[k + 3].split()[1]]
    i = u.index("pushscope")
    u = u[:i + 1] + p + u[i + 1:]
    chemin = os.path.join(TRAVAIL, "pfi.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


def contour():
    """
    FurnitureVisualization.updateSprites : quand le mobi porte le marqueur de
    surlignage (_filters = [GlowFilter couleur HALO]), toutes ses couches sont
    dessinees dans une seule image, puis un GlowFilter « knockout » fort et peu
    flou n'en garde qu'un anneau net de 1 a 2 px : le contour du mobi ENTIER
    (2x1, 2x2, plusieurs morceaux). Il prend la place du sprite d'ombre, juste
    derriere la couche la plus profonde ; l'ombre revient quand le surlignage
    s'eteint (mise a jour complete). Ne tourne que quand l'apparence change.
    """
    FVP = 'PrivateNamespace("com.sulake.habbo.room.object.visualization.furniture:FurnitureVisualization")'
    FVR = 'ProtectedNamespace("com.sulake.habbo.room.object.visualization.furniture:FurnitureVisualization")'
    SPR = 'Namespace("com.sulake.room.object.visualization:IRoomObjectSprite")'
    GLOWQ = 'QName(PackageNamespace("flash.filters"),"GlowFilter")'
    BMD = 'QName(PackageNamespace("flash.display"),"BitmapData")'
    MTX = 'QName(PackageNamespace("flash.geom"),"Matrix")'
    PT = 'QName(PackageNamespace("flash.geom"),"Point")'
    def P(n): return 'QName(PackageNamespace(""),"%s")' % n
    def S(n): return 'QName(%s,"%s")' % (SPR, n)
    OMBRE = 'getlex QName(%s,"_-7i")' % FVR
    u = open(os.path.join(TRAVAIL, "us.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = u.index("maxstack 3"), u.index("localcount 6")
    u[im] = "maxstack 24"; u[il] = "localcount 15"
    c = ['getlex QName(%s,"_filters")' % FVP, 'coerce_a', 'setlocal 6',
         'getlocal 6', 'iffalse atl_c_fin',
         'getlocal 6', LENGTH, 'pushbyte 1', 'ifne atl_c_fin',
         'getlocal 6', 'pushbyte 0', MULTI_L, 'coerce_a', 'setlocal 7',
         'getlocal 7', 'getlex ' + GLOWQ, 'istypelate', 'iffalse atl_c_fin',
         'getlocal 7', 'getproperty ' + P("color"), 'pushint %d' % HALO, 'ifne atl_c_fin',
         OMBRE, 'pushbyte 0', 'iflt atl_c_fin',
         'findpropstrict ' + P("getSprite"), OMBRE, 'callproperty %s, 1' % P("getSprite"), 'coerce_a', 'setlocal 7',
         'getlocal 7', 'iffalse atl_c_fin',
         'getlocal 7', 'pushnull', 'setproperty ' + S("asset"),
         'getlex ' + P("boundingRectangle"), 'coerce_a', 'setlocal 8',
         'getlocal 8', 'iffalse atl_c_fin',
         'getlocal 8', 'getproperty ' + P("width"), 'pushbyte 0', 'ifle atl_c_fin',
         'getlocal 8', 'getproperty ' + P("height"), 'pushbyte 0', 'ifle atl_c_fin',
         'findpropstrict ' + BMD,
         'getlocal 8', 'getproperty ' + P("width"), 'pushbyte 8', 'add',
         'getlocal 8', 'getproperty ' + P("height"), 'pushbyte 8', 'add',
         'pushtrue', 'pushbyte 0', 'constructprop %s, 4' % BMD, 'coerce_a', 'setlocal 9',
         'findpropstrict ' + MTX, 'constructprop %s, 0' % MTX, 'coerce_a', 'setlocal 10',
         'pushshort -1000', 'setlocal 13',
         'pushbyte 0', 'setlocal 11',
         'jump atl_c_test',
         'atl_c_boucle:', 'label',
         'getlocal 11', OMBRE, 'ifeq atl_c_suiv',
         'getlocal 11', 'getlex QName(%s,"_-gT")' % FVP, 'ifeq atl_c_suiv',
         'findpropstrict ' + P("getSprite"), 'getlocal 11', 'callproperty %s, 1' % P("getSprite"), 'coerce_a', 'setlocal 12',
         'getlocal 12', 'iffalse atl_c_suiv',
         'getlocal 12', 'getproperty ' + S("visible"), 'iffalse atl_c_suiv',
         'getlocal 12', 'getproperty ' + S("asset"), 'iffalse atl_c_suiv',
         'getlocal 12', 'getproperty ' + S("alpha"), 'pushbyte 0', 'ifle atl_c_suiv',
         'getlocal 12', 'getproperty ' + S("blendMode"), 'pushstring "add"', 'ifeq atl_c_suiv',
         'getlocal 10', 'callpropvoid %s, 0' % P("identity"),
         'getlocal 12', 'getproperty ' + S("flipH"), 'iffalse atl_c_nf',
         'getlocal 10', 'pushbyte -1', 'pushbyte 1', 'callpropvoid %s, 2' % P("scale"),
         'getlocal 10', 'getlocal 12', 'getproperty ' + S("width"), 'pushbyte 0', 'callpropvoid %s, 2' % P("translate"),
         'atl_c_nf:',
         'getlocal 10',
         'getlocal 12', 'getproperty ' + S("offsetX"), 'getlocal 8', 'getproperty ' + P("left"), 'subtract', 'pushbyte 4', 'add',
         'getlocal 12', 'getproperty ' + S("offsetY"), 'getlocal 8', 'getproperty ' + P("top"), 'subtract', 'pushbyte 4', 'add',
         'callpropvoid %s, 2' % P("translate"),
         'getlocal 9', 'getlocal 12', 'getproperty ' + S("asset"), 'getlocal 10', 'callpropvoid %s, 2' % P("draw"),
         'getlocal 12', 'getproperty ' + S("relativeDepth"), 'getlocal 13', 'ifle atl_c_suiv',
         'getlocal 12', 'getproperty ' + S("relativeDepth"), 'setlocal 13',
         'atl_c_suiv:', 'inclocal_i 11',
         'atl_c_test:', 'getlocal 11', 'getlex ' + P("spriteCount"), 'iflt atl_c_boucle',
         'getlocal 13', 'pushshort -1000', 'ifle atl_c_fin',
         # anneau net : glow knockout tres fort, peu flou
         # bmp.applyFilter(bmp, bmp.rect, new Point(0, 0), glow) : objet, puis 4 arguments
         'getlocal 9', 'getlocal 9', 'getlocal 9', 'getproperty ' + P("rect"),
         'findpropstrict ' + PT, 'pushbyte 0', 'pushbyte 0', 'constructprop %s, 2' % PT,
         'findpropstrict ' + GLOWQ, 'pushint %d' % HALO, 'pushbyte 1', 'pushbyte 3', 'pushbyte 3',
         'pushshort 255', 'pushbyte 1', 'pushfalse', 'pushtrue', 'constructprop %s, 8' % GLOWQ,
         'callpropvoid %s, 4' % P("applyFilter"),
         'getlocal 7', 'getlocal 9', 'setproperty ' + S("asset"),
         'getlocal 7', 'pushtrue', 'setproperty ' + S("visible"),
         'getlocal 7', 'getlocal 8', 'getproperty ' + P("left"), 'pushbyte 4', 'subtract', 'setproperty ' + S("offsetX"),
         'getlocal 7', 'getlocal 8', 'getproperty ' + P("top"), 'pushbyte 4', 'subtract', 'setproperty ' + S("offsetY"),
         'getlocal 7', 'pushshort 255', 'setproperty ' + S("alpha"),
         'getlocal 7', 'pushint 16777215', 'setproperty ' + S("color"),
         'getlocal 7', 'pushstring "normal"', 'setproperty ' + S("blendMode"),
         'getlocal 7', 'pushfalse', 'setproperty ' + S("flipH"),
         'getlocal 7', 'pushfalse', 'setproperty ' + S("flipV"),
         'getlocal 7', 'pushnull', 'setproperty ' + S("filters"),
         'getlocal 7', 'pushshort 256', 'setproperty ' + S("alphaTolerance"),
         'getlocal 7', 'pushtrue', 'setproperty ' + S("skipMouseHandling"),
         'getlocal 7', 'pushfalse', 'setproperty ' + S("clickHandling"),
         'getlocal 7', 'pushstring "atelier_contour"', 'setproperty ' + S("assetName"),
         'getlocal 7', 'getlocal 13', 'pushdouble 0.001', 'add', 'setproperty ' + S("relativeDepth"),
         'atl_c_fin:']
    k = u.index("returnvoid")
    u = u[:k] + c + u[k:]
    chemin = os.path.join(TRAVAIL, "us.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


def annees_inventaire():
    """
    FurniView.setViewToState : quand la vue passe en mode contenu (inventaire
    charge), le menu Annee ne propose que les annees ou il y a des mobis (annee
    lue dans la ligne ou le nom de classe, comme le filtre), de la plus recente a
    la plus ancienne. L'annee deja choisie reste choisie. Le menu est renomme le
    temps du remplissage : la selection provoquee n'est pas un choix.
    """
    IFD = 'Namespace("com.sulake.habbo.session.furniture:IFurnitureData")'
    RX = 'QName(PackageNamespace(""),"RegExp")'
    u = open(os.path.join(TRAVAIL, "svs.orig.pcode"), encoding="utf-8").read().split("\n")
    im, il = u.index("maxstack 2"), u.index("localcount 2")
    u[im] = "maxstack 16"; u[il] = "localcount 24"
    c = ['getlocal1', 'pushbyte 3', 'ifne atl_a_fin',
         Y1, 'pushstring "atelier_annee"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 2',
         'getlocal 2', 'iffalse atl_a_fin',
         # annee choisie avant
         'pushstring ""', 'setlocal 3',
         'getlocal 2', ENUM, 'coerce_a', 'setlocal 4',
         'getlocal 4', 'iffalse atl_a_l',
         'getlocal 2', SEL_GET, 'convert_i', 'setlocal 5',
         'getlocal 5', 'pushbyte 0', 'iflt atl_a_l',
         'getlocal 5', 'getlocal 4', LENGTH, 'ifge atl_a_l',
         'getlocal 4', 'getlocal 5', MULTI_L, 'coerce_s', 'setlocal 3',
         'atl_a_l:',
         # parcours des mobis
         'newobject 0', 'setlocal 6',            # deja vues
         'newarray 0', 'setlocal 7',             # annees
         'findpropstrict ' + RX, 'pushstring "(19|20)[0-9][0-9]"', 'constructprop %s, 1' % RX, 'coerce_a', 'setlocal 8',
         W14, 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 9',
         'getlocal 9', 'iffalse atl_a_r',
         'pushbyte 0', 'setlocal 5',
         'jump atl_a_t',
         'atl_a_b:', 'label',
         'getlocal 9', 'getlocal 5', MULTI_L, 'coerce_a', 'setlocal 10',
         'getlocal 10', 'iffalse atl_a_n',
         'getlocal 10', 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 10',
         'getlocal 10', 'iffalse atl_a_n',
         'getlocal 10', 'getproperty QName(%s,"furniLine")' % IFD, 'coerce_s',
         'pushstring " "', 'add',
         'getlocal 10', 'getproperty QName(%s,"className")' % IFD, 'coerce_s', 'add', 'coerce_s', 'setlocal 11',
         'getlocal 8', 'getlocal 11', 'callproperty QName(%s,"exec"), 1' % AS3, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'iffalse atl_a_n',
         'getlocal 12', 'pushbyte 0', MULTI_L, 'coerce_s', 'setlocal 11',
         'getlocal 6', 'getlocal 11', MULTI_L, 'iftrue atl_a_n',
         'getlocal 6', 'getlocal 11', 'pushtrue', 'setproperty MultinameL([PackageNamespace(""),Namespace("http://adobe.com/AS3/2006/builtin")])',
         'getlocal 7', 'getlocal 11', PUSH,
         'atl_a_n:', 'inclocal_i 5',
         'atl_a_t:', 'getlocal 5', 'getlocal 9', LENGTH, 'iflt atl_a_b',
         'atl_a_r:',
         # de la plus recente a la plus ancienne (Array.DESCENDING = 2)
         'getlocal 7', 'pushbyte 2', 'callpropvoid QName(%s,"sort"), 1' % AS3,
         'pushstring "Toutes les années"', 'newarray 1',
         'getlocal 7', 'callproperty QName(%s,"concat"), 1' % AS3, 'coerce_a', 'setlocal 7',
         # remplissage sans declencher de choix ; l'annee choisie reste si elle existe
         'getlocal 2', 'pushstring "atelier_annee_maj"', 'setproperty QName(%s,"name")' % IW,
         'getlocal 2', 'getlocal 7', POP,
         'getlocal 7', 'getlocal 3', INDEXOF, 'convert_i', 'setlocal 5',
         'getlocal 5', 'pushbyte 0', 'ifgt atl_a_s',
         'pushbyte 0', 'setlocal 5',
         Y1, 'pushstring "atelier_motif_annee"', FIND, 'coerce_a', 'setlocal 12',
         'getlocal 12', 'iffalse atl_a_s', 'getlocal 12', 'pushstring ""', CAPTION_SET,
         'atl_a_s:',
         'getlocal 2', 'getlocal 5', SEL_SET,
         'getlocal 2', 'pushstring "atelier_annee"', 'setproperty QName(%s,"name")' % IW]
    # Categorie : les collections ou il y a des mobis, par nom (registres 13 a 23)
    c += [Y1, 'pushstring "atelier_categorie"', FIND, 'getlex ' + X6, 'astypelate', 'coerce ' + X6, 'coerce_a', 'setlocal 13',
          'getlocal 13', 'iffalse atl_a_fin',
          'pushstring ""', 'setlocal 14',
          'getlocal 13', ENUM, 'coerce_a', 'setlocal 15',
          'getlocal 15', 'iffalse atl_k_l',
          'getlocal 13', SEL_GET, 'convert_i', 'setlocal 16',
          'getlocal 16', 'pushbyte 0', 'iflt atl_k_l',
          'getlocal 16', 'getlocal 15', LENGTH, 'ifge atl_k_l',
          'getlocal 15', 'getlocal 16', MULTI_L, 'coerce_s', 'setlocal 14',
          'atl_k_l:',
          'newobject 0', 'setlocal 17',
          'newarray 0', 'setlocal 18',
          W14, 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 19',
          'getlocal 19', 'iffalse atl_k_r',
          'pushbyte 0', 'setlocal 16',
          'jump atl_k_t',
          'atl_k_b:', 'label',
          'getlocal 19', 'getlocal 16', MULTI_L, 'coerce_a', 'setlocal 20',
          'getlocal 20', 'iffalse atl_k_n',
          'getlocal 20', 'getproperty QName(PackageNamespace(""),"furniData")', 'coerce_a', 'setlocal 20',
          'getlocal 20', 'iffalse atl_k_n',
          'getlocal 20', 'getproperty QName(%s,"furniLine")' % IFD, 'coerce_s', 'setlocal 21',
          'getlocal 21', 'iftrue atl_k_v', 'pushstring ""', 'setlocal 21', 'atl_k_v:',
          'getlocal 21', LOWER, 'coerce_s', 'setlocal 21']
    c += nom_ligne(21, 22, 23, 15, 'atl_k_nom')
    c += ['getlocal 17', 'getlocal 22', MULTI_L, 'iftrue atl_k_n',
          'getlocal 17', 'getlocal 22', 'pushtrue', 'setproperty MultinameL([PackageNamespace(""),Namespace("http://adobe.com/AS3/2006/builtin")])',
          'getlocal 18', 'getlocal 22', PUSH,
          'atl_k_n:', 'inclocal_i 16',
          'atl_k_t:', 'getlocal 16', 'getlocal 19', LENGTH, 'iflt atl_k_b',
          'atl_k_r:',
          # par nom, sans tenir compte des majuscules (Array.CASEINSENSITIVE = 1)
          'getlocal 18', 'pushbyte 1', 'callpropvoid QName(%s,"sort"), 1' % AS3,
          'pushstring "Toutes les catégories"', 'newarray 1',
          'getlocal 18', 'callproperty QName(%s,"concat"), 1' % AS3, 'coerce_a', 'setlocal 18',
          'getlocal 13', 'pushstring "atelier_categorie_maj"', 'setproperty QName(%s,"name")' % IW,
          'getlocal 13', 'getlocal 18', POP,
          'getlocal 18', 'getlocal 14', INDEXOF, 'convert_i', 'setlocal 16',
          'getlocal 16', 'pushbyte 0', 'ifgt atl_k_s',
          'pushbyte 0', 'setlocal 16',
          Y1, 'pushstring "atelier_motif_cat"', FIND, 'coerce_a', 'setlocal 20',
          'getlocal 20', 'iffalse atl_k_s', 'getlocal 20', 'pushstring ""', CAPTION_SET,
          'atl_k_s:',
          'getlocal 13', 'getlocal 16', SEL_SET,
          'getlocal 13', 'pushstring "atelier_categorie"', 'setproperty QName(%s,"name")' % IW,
          'atl_a_fin:']
    k = len(u) - 1 - u[::-1].index("returnvoid")
    u = u[:k] + c + u[k:]
    chemin = os.path.join(TRAVAIL, "svs.pcode")
    open(chemin, "w", encoding="utf-8").write("\n".join(u))
    return chemin


# ================================================================== garde-fou
def verifier_pile(chemin):
    """
    Flash rejette une classe entiere si une methode empile plus que son
    « maxstack » (VerifyError) : onglet Mobilier blanc, chat muet. Estimation
    prudente de la pile par blocs lineaires ; on refuse de construire si elle
    depasse la valeur declaree.
    """
    t = [l.strip() for l in open(chemin, encoding="utf-8").read().split("\n")]
    decl = int([l for l in t if l.startswith("maxstack ")][0].split()[1])
    run = pire = 0
    for l in t:
        if l.startswith(("pushstring", "pushbyte", "pushint", "pushdouble", "pushshort", "pushnull", "pushtrue",
                         "pushfalse", "getlocal", "getlex", "findpropstrict", "findproperty", "dup")):
            run += 1
        m = re.match(r"newarray (\d+)", l)
        if m: run -= int(m.group(1)) - 1
        if l.startswith(("setlocal", "setproperty", "callpropvoid", "returnvoid", "iffalse", "iftrue", "ifne", "ifeq",
                         "iflt", "ifgt", "ifle", "ifge", "ifnge", "jump", "pop")) or l.endswith(":"):
            run = 0
        pire = max(pire, run)
    if pire + 4 > decl:
        sys.exit("Pile trop petite dans %s : ~%d utilisee, maxstack %d" % (os.path.basename(chemin), pire, decl))


# ================================================================== assemblage
def main():
    os.makedirs(TRAVAIL, exist_ok=True)
    rempl = ["2330", mise_en_page()]
    fv = "com.sulake.habbo.inventory.furni.FurniView"
    if ICONES or CATEGORIES or PAGINATION:
        rempl += [fv, window_event_proc(), "29880"]
    if ICONES or CATEGORIES:
        rempl += [fv, populate_filter_options(), "29882"]
    if PAGINATION:
        rempl += ["com.sulake.habbo.inventory.furni.FurniGridView", update_paging(), "60336"]
    if CATEGORIES or RECHERCHE:
        rempl += ["com.sulake.habbo.inventory.furni.FurniGridView", pass_filter(), "60338"]
    if CATEGORIES:
        rempl += ["com.sulake.habbo.inventory.furni.FurniView", annees_inventaire(), "29863"]
    if SURLIGNAGE or GRILLE:
        rempl += ["com.sulake.habbo.freeflowchat.data.ChatEventHandler", chat_salle(), "24437"]
    if GRILLE:
        rempl += ["com.sulake.room.renderer.§_-b2M§", rendu_grille(), "37144"]
        rempl += ["com.sulake.room.renderer.utils.§_-s9§", ecrire_clic_sol(), "54531"]
    if SURLIGNAGE:
        rempl += ["com.sulake.habbo.room.object.visualization.furniture.FurnitureVisualization", filtres_couche(), "13794"]
        rempl += ["com.sulake.habbo.room.object.visualization.furniture.FurnitureVisualization", contour(), "13792"]
    if ICONES or PAGINATION:
        for ident, (dessin, _) in IMAGES.items():
            rempl += [str(ident), os.path.join(ICI, "dessins", dessin), "lossless2"]
    for k in range(len(rempl)):
        if rempl[k].endswith(".pcode"):
            verifier_pile(rempl[k])
    cmd = [JAVA, "-Xmx6g", "-jar", FFDEC, "-replace", ORIGINE, SORTIE] + rempl
    r = subprocess.run(cmd, capture_output=True, text=True)
    sortie = "\n".join(l for l in (r.stdout + r.stderr).splitlines()
                       if "WARNING" not in l and "checkUnique" not in l)
    if r.returncode != 0 or not os.path.isfile(SORTIE):
        print(sortie); sys.exit("ECHEC de la construction")
    # Relecture : FFDec affiche « §§pop » / « §§push » quand la pile ne tombe pas
    # juste (argument oublie...). Flash rejetterait la classe entiere.
    classes = sorted(set(rempl[k] for k in range(len(rempl)) if rempl[k].startswith("com.sulake.")))
    import tempfile, glob
    d = tempfile.mkdtemp(prefix="relecture-", dir=TRAVAIL)
    subprocess.run([JAVA, "-Xmx6g", "-jar", FFDEC, "-selectclass", ",".join(classes), "-export", "script", d, SORTIE],
                   capture_output=True, text=True)
    fautes = []
    for f in glob.glob(os.path.join(d, "**", "*.as"), recursive=True):
        for n, l in enumerate(open(f, encoding="utf-8", errors="ignore"), 1):
            if "\u00a7\u00a7pop" in l or "\u00a7\u00a7push" in l:
                fautes.append("%s:%d" % (os.path.basename(f), n))
    subprocess.run(["rm", "-rf", d])
    if fautes:
        os.remove(SORTIE)
        sys.exit("Pile desequilibree (SWF refuse) : " + ", ".join(fautes[:10]))
    print("Construit :", SORTIE, "(sans : %s)" % (",".join(sorted(SANS)) or "rien"))


if __name__ == "__main__":
    main()

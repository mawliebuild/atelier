"""
Noms obfusques d'une version de Habbo a l'autre.

construire.py est ecrit pour une version de reference du client (REFERENCE) : il y
nomme des classes et des membres obfusques (« _-y1 », « _-b2M »...). A chaque mise a
jour, Habbo les renomme tous. Ce module retrouve le nouveau nom de chacun :

  - une classe a nom lisible (FurniView) garde son chemin ; une classe obfusquee est
    la plus ressemblante du meme dossier (texte normalise : noms obfusques effaces) ;
  - dans deux classes appariees, les declarations (var, const, function) sont alignees
    dans l'ordre sur leur texte normalise ; le membre garde sa place, donc son nouveau
    nom se lit en face.

Le nouveau client est decompile une fois (FFDec, une demi-minute), en cache. La
reference n'est pas livree (aucun fichier de Habbo dans le paquet) : ce qu'il en faut
(texte normalise et declarations des classes utilisees) est dans REFERENCE_NOMS,
ecrit par « python3 renommage.py » sur l'ordinateur qui a la reference.
"""
import difflib, glob, json, os, re, subprocess, sys

ICI = os.path.dirname(os.path.abspath(__file__))
REFERENCE = os.path.join(ICI, "origines", "HabboAir-5882575595f1.swf")
REFERENCE_EMPREINTE = "5882575595f1"
REFERENCE_NOMS = os.path.join(ICI, "donnees", "reference-noms.json")
CLASSES = [
    "com/sulake/habbo/inventory/furni/FurniView",
    "com/sulake/habbo/inventory/furni/FurniGridView",
    "com/sulake/room/renderer/\u00a7_-b2M\u00a7",
    "com/sulake/room/renderer/utils/\u00a7_-s9\u00a7",
    "com/sulake/core/window/components/\u00a7_-X6\u00a7",
    "\u00a7_-910\u00a7/\u00a7_-R1T\u00a7",
    "com/sulake/core/utils/images/\u00a7_-s1R\u00a7",
    "com/sulake/habbo/freeflowchat/data/ChatEventHandler",
    "com/sulake/habbo/room/object/visualization/furniture/FurnitureVisualization",
    "com/sulake/habbo/inventory/bots/BotsView",
    "com/sulake/habbo/ui/widget/infostand/InfoStandFurniView",
]

_OBF = re.compile(r"§[^§]*§")
_DECL = re.compile(r"^\s*(?:override\s+)?(?:public|private|protected|internal|[^\s]*namespace[^\s]*)?\s*"
                   r"(?:static\s+)?(?:final\s+)?(?:override\s+)?(var|const|function(?:\s+get|\s+set)?)\s+([^\s(:;=]+)")


class Renommage:
    def __init__(self, origine, travail, java, memoire, ffdec, empreinte):
        self.identique = empreinte(origine) == REFERENCE_EMPREINTE
        self.args = (java, memoire, ffdec)
        self.travail = travail
        self.empreinte = empreinte
        self.origine = origine
        self._classes, self._membres = {}, {}
        self._exports = None

    # ------------------------------------------------------------ exports
    def _export(self, swf):
        d = os.path.join(self.travail, "origines", self.empreinte(swf), "tout")
        if not glob.glob(os.path.join(d, "scripts", "com", "*")):
            java, memoire, ffdec = self.args
            subprocess.run([java, memoire, "-jar", ffdec, "-export", "script", d, swf],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        return os.path.join(d, "scripts")

    def _neuf(self):
        if self._exports is None:
            self._exports = self._export(self.origine)
        return self._exports

    _ref = None

    @classmethod
    def _reference(cls, chemin):
        """(texte normalise, declarations) d'une classe de la reference."""
        if cls._ref is None:
            with open(REFERENCE_NOMS, encoding="utf-8") as x:
                cls._ref = json.load(x)
        if chemin not in cls._ref:
            raise KeyError("classe absente de la reference : " + chemin)
        r = cls._ref[chemin]
        return r["texte"], [tuple(d) for d in r["decls"]]

    @staticmethod
    def _texte(f):
        with open(f, encoding="utf-8", errors="ignore") as x:
            return x.read()

    @staticmethod
    def _normal(t):
        t = _OBF.sub("§", t)
        t = re.sub(r"_loc\d+_", "L", t)
        return [l.strip() for l in t.splitlines() if l.strip() and not l.strip().startswith("import ")]

    # ------------------------------------------------------------ classes
    def _chemin_nouveau(self, chemin):
        """chemin : relatif au dossier scripts de la reference, sans .as (« com/sulake/.../FurniView »)."""
        if chemin in self._classes:
            return self._classes[chemin]
        neuf = self._neuf()
        a = self._reference(chemin)[0]
        parties = chemin.split("/")
        if not any("§" in p for p in parties):
            r = chemin if os.path.isfile(os.path.join(neuf, chemin + ".as")) else None
        else:
            # dossier lisible : les classes du meme dossier ; sinon tous les dossiers obfusques
            dossier = "/".join(parties[:-1])
            if "§" in dossier:
                candidats = glob.glob(os.path.join(neuf, "§*§", "*.as"))
            else:
                candidats = glob.glob(os.path.join(neuf, dossier, "*.as"))
            meilleur, score = None, 0.0
            for c in candidats:
                b = self._normal(self._texte(c))
                if not b or abs(len(b) - len(a)) > max(10, len(a) // 5):
                    continue
                m = difflib.SequenceMatcher(None, a, b, autojunk=False)
                if m.quick_ratio() <= score:
                    continue
                s = m.ratio()
                if s > score:
                    meilleur, score = c, s
            r = None
            if meilleur and score > 0.6:
                r = os.path.relpath(meilleur, neuf)[:-3].replace(os.sep, "/")
        if r is None:
            raise KeyError("classe introuvable dans la nouvelle version : " + chemin)
        self._classes[chemin] = r
        return r

    @staticmethod
    def _nu(nom):
        return nom[1:-1] if nom.startswith("§") and nom.endswith("§") else nom

    def classe(self, chemin):
        """Nouveau nom (« _-x1 ») de la classe obfusquee de la reference a ce chemin."""
        if self.identique:
            return self._nu(chemin.split("/")[-1])
        return self._nu(self._chemin_nouveau(chemin).split("/")[-1])

    def paquet(self, chemin):
        """Nouveau nom du paquet (dossier) obfusque de cette classe de la reference."""
        if self.identique:
            return self._nu(chemin.split("/")[-2])
        return self._nu(self._chemin_nouveau(chemin).split("/")[-2])

    def chemin(self, chemin):
        """Chemin pointe (« com.sulake.room.renderer.§_-b2M§ ») de la classe dans la nouvelle version."""
        c = chemin if self.identique else self._chemin_nouveau(chemin)
        return c.replace("/", ".")

    # ------------------------------------------------------------ membres
    @classmethod
    def _decls(cls, f):
        r = []
        for l in cls._texte(f).splitlines():
            m = _DECL.match(l)
            if m:
                r.append((cls._nu(m.group(2)), " ".join(cls._normal(l))))
        return r

    def membre(self, chemin, ancien):
        """Nouveau nom du membre « ancien » (var, const ou methode) de la classe de reference « chemin »."""
        if self.identique:
            return ancien
        cle = (chemin, ancien)
        if cle in self._membres:
            return self._membres[cle]
        neuf = self._neuf()
        a = self._reference(chemin)[1]
        b = self._decls(os.path.join(neuf, self._chemin_nouveau(chemin) + ".as"))
        sm = difflib.SequenceMatcher(None, [x[1] for x in a], [x[1] for x in b], autojunk=False)
        for op, i1, i2, j1, j2 in sm.get_opcodes():
            if op in ("equal", "replace") and i2 - i1 == j2 - j1:
                for k in range(i2 - i1):
                    self._membres.setdefault((chemin, a[i1 + k][0]), b[j1 + k][0])
        if cle not in self._membres:
            raise KeyError("membre introuvable dans la nouvelle version : %s.%s" % (chemin, ancien))
        return self._membres[cle]


def ecrire_reference(scripts):
    """Ecrit REFERENCE_NOMS depuis l'export (dossier « scripts ») de la version de reference."""
    r = {}
    for c in CLASSES:
        f = os.path.join(scripts, c + ".as")
        r[c] = {"texte": Renommage._normal(Renommage._texte(f)), "decls": Renommage._decls(f)}
    os.makedirs(os.path.dirname(REFERENCE_NOMS), exist_ok=True)
    with open(REFERENCE_NOMS, "w", encoding="utf-8") as x:
        json.dump(r, x, ensure_ascii=False)
    print("Ecrit :", REFERENCE_NOMS)


if __name__ == "__main__":
    ecrire_reference(sys.argv[1] if len(sys.argv) > 1
                     else os.path.join(ICI, "travail", "origines", REFERENCE_EMPREINTE, "tout", "scripts"))

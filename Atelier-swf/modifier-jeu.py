#!/usr/bin/env python3
"""
Adapte et installe les modifs de l'Atelier dans le client Habbo de CET ordinateur,
quelle que soit sa version (Mac ou Windows, et les prochaines mises a jour).

  python modifier-jeu.py              modifie le jeu installe (s'il ne l'est pas deja)
  python modifier-jeu.py --restaurer  remet le jeu d'origine

Pour chaque client Habbo trouve (dossiers du Habbo Launcher) :
  - deja modifie par nous (empreinte connue) : rien a faire ;
  - sinon c'est un original (nouvelle version, ou premiere fois) : on le garde
    dans origines/, on construit les modifs a partir de LUI (construire.py
    --origine), puis on l'installe. Sur Mac, l'appli est re-signee localement.
  - si la construction echoue (Habbo a change son code), le jeu reste intact.
Codes de sortie : 0 tout va bien, 2 Habbo est ouvert, 1 autre souci (dit en clair).
"""
import glob, hashlib, json, os, shutil, subprocess, sys

ICI = os.path.dirname(os.path.abspath(__file__))
WIN = os.name == "nt"
ETAT = os.path.join(ICI, "etat-jeu.json")


JOURNAL = os.path.join(ICI, "modifier-jeu.log")


def dire(m):
    print(m, flush=True)
    try:
        import time
        with open(JOURNAL, "a", encoding="utf-8") as j:
            j.write(time.strftime("%Y-%m-%d %H:%M:%S ") + m + "\n")
    except Exception:
        pass


def empreinte(f):
    h = hashlib.sha256()
    with open(f, "rb") as x:
        for b in iter(lambda: x.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def clients():
    """Les HabboAir.swf installes par le Habbo Launcher : [(chemin du swf, appli Mac ou None)]."""
    r = []
    if WIN:
        base = os.path.join(os.environ.get("APPDATA", ""), "Habbo Launcher", "downloads", "air")
        for f in glob.glob(os.path.join(base, "*", "**", "HabboAir.swf"), recursive=True):
            r.append((f, None))
    else:
        base = os.path.expanduser("~/Library/Application Support/Habbo Launcher/downloads/air")
        for app in glob.glob(os.path.join(base, "*", "Habbo.app")):
            f = os.path.join(app, "Contents", "Resources", "HabboAir.swf")
            if os.path.isfile(f):
                r.append((f, app))
    return r


def deja_modifie(f):
    """Le client contient deja des modifs de l'Atelier (installees par une version precedente) ?"""
    import zlib
    try:
        d = open(f, "rb").read()
        if d[:3] == b"CWS":
            d = zlib.decompress(d[8:])
        return b"atelier:" in d
    except Exception:
        return False


def habbo_ouvert():
    try:
        if WIN:
            o = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Habbo.exe", "/NH"], capture_output=True, text=True, errors="replace").stdout
            return "habbo.exe" in o.lower()
        return subprocess.run(["pgrep", "-f", "Habbo.app/Contents/MacOS/Habbo"], capture_output=True).returncode == 0
    except Exception:
        return False


def lire_etat():
    try:
        return json.load(open(ETAT, encoding="utf-8"))
    except Exception:
        return {"modifies": {}}


def ecrire_etat(e):
    json.dump(e, open(ETAT, "w", encoding="utf-8"), indent=1)


def signer(app):
    if app:
        subprocess.run(["codesign", "--force", "--deep", "--sign", "-", app], capture_output=True)
        subprocess.run(["xattr", "-dr", "com.apple.quarantine", app], capture_output=True)


def modifier():
    dire("--- %s, Python %s" % ("Windows" if WIN else "Mac", sys.version.split()[0]))
    l = clients()
    for swf, _ in l:
        dire("    client trouvé : %s" % swf)
    if not l:
        dire("! Habbo introuvable : ouvre le Habbo Launcher, lance Habbo une fois, ferme-le, puis relance.")
        return 1
    etat = lire_etat()
    code = 0
    for swf, app in l:
        version = os.path.basename(os.path.dirname(app)) if app else os.path.relpath(swf, os.path.dirname(os.path.dirname(swf))).split(os.sep)[0]
        h = empreinte(swf)
        recette = empreinte(os.path.join(ICI, "construire.py"))
        connu = etat["modifies"].get(h)
        maj = False
        if connu is not None:
            o = connu["origine"] if isinstance(connu, dict) else connu
            faite = connu.get("construire") if isinstance(connu, dict) else None
            if faite == recette or not os.path.isfile(os.path.join(ICI, "origines", "HabboAir-%s.swf" % o[:12])):
                dire("OK  Modifs du jeu déjà installées (version %s)" % version)
                continue
            # l'Atelier a ete mis a jour : on refait les modifs depuis l'original garde
            dire("… Mise à jour des modifs du jeu (version %s)" % version)
            h = o
            maj = True
        if not maj and deja_modifie(swf):
            # modifie par une version precedente de l'Atelier : on ne remodifie pas par-dessus
            dire("OK  Modifs du jeu déjà installées (version %s, installées avant)" % version)
            continue
        if habbo_ouvert():
            dire("! Habbo est ouvert : ferme-le puis relance pour avoir les modifs du jeu (version %s)." % version)
            code = 2
            continue
        os.makedirs(os.path.join(ICI, "origines"), exist_ok=True)
        os.makedirs(os.path.join(ICI, "travail"), exist_ok=True)
        orig = os.path.join(ICI, "origines", "HabboAir-%s.swf" % h[:12])
        if not os.path.isfile(orig):
            if empreinte(swf) != h:
                dire("! Original introuvable : ton jeu reste tel quel.")
                code = 1
                continue
            shutil.copyfile(swf, orig)
        sortie = os.path.join(ICI, "travail", "HabboAir-atelier-%s-%s.swf" % (h[:12], recette[:8]))
        if not os.path.isfile(sortie):
            if not maj:
                dire("… Nouvelle version de Habbo (%s) : préparation des modifs (une minute environ)" % version)
            r = subprocess.run([sys.executable, os.path.join(ICI, "construire.py"), sortie, "--origine", orig],
                               capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=ICI)
            if r.returncode != 0 or not os.path.isfile(sortie):
                dire("! Les modifs n'ont pas pu être adaptées à cette version de Habbo : ton jeu reste normal, "
                     "l'Atelier marche quand même.")
                fin = (r.stdout + r.stderr).strip().splitlines()
                for x in fin[-3:]:
                    print("    " + x, flush=True)
                try:
                    with open(JOURNAL, "a", encoding="utf-8") as j:
                        j.write("\n".join(fin[-60:]) + "\n")
                except Exception:
                    pass
                code = 1
                continue
        try:
            shutil.copyfile(sortie, swf)
            signer(app)
        except Exception as e:
            dire("! Installation impossible (%s) : ton jeu reste normal." % e)
            code = 1
            continue
        etat["modifies"][empreinte(swf)] = {"origine": h, "construire": recette}
        ecrire_etat(etat)
        dire("OK  Modifs du jeu installées (version %s)" % version)
    return code


def restaurer():
    etat = lire_etat()
    if habbo_ouvert():
        dire("! Ferme Habbo d'abord.")
        return 2
    n = 0
    for swf, app in clients():
        h = empreinte(swf)
        o = etat["modifies"].get(h)
        if not o:
            continue
        if isinstance(o, dict):
            o = o["origine"]
        orig = os.path.join(ICI, "origines", "HabboAir-%s.swf" % o[:12])
        if os.path.isfile(orig):
            shutil.copyfile(orig, swf)
            signer(app)
            n += 1
    dire("OK  Jeu d'origine remis (%d client(s))." % n if n else "Rien à remettre : le jeu n'était pas modifié.")
    return 0


if __name__ == "__main__":
    sys.exit(restaurer() if "--restaurer" in sys.argv else modifier())

#!/bin/bash
# Construit l'Atelier : G-Earth 1.5.4 + G-Presets + G-BuildTools + mes classes,
# dans un seul jar, sans notion d'extension.
#
#   bash ~/Documents/Atelier-src/build.sh
set -e

SRC="$HOME/Documents/Atelier-src"
CIBLE="$HOME/Documents/Atelier"
JDK="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"

GPRESETS="$HOME/Downloads/GPresets-v1.3.8-fix by Zyker.jar"
GBUILD=""
DEPS="$CIBLE/Dependencies"

for f in "$GPRESETS"; do
  [ -f "$f" ] || { echo "ERREUR : introuvable -> $f"; exit 1; }
done
[ -d "$DEPS" ] || { echo "ERREUR : Dependencies introuvable -> $DEPS"; exit 1; }

cd "$SRC"
rm -rf out etape
mkdir -p out etape

echo "== compilation =="
CP="$GPRESETS:$(ls "$DEPS"/*.jar | tr '\n' ':')"
# Le statut de javac doit faire echouer le script : sans ca, un jar bancal
# s'installait quand meme (le "|| true" masquait l'erreur).
if ! "$JDK/bin/javac" -encoding UTF-8 -cp "$CP" -d out $(find src -name '*.java') 2> "$SRC/erreurs.txt"; then
  echo "ECHEC DE COMPILATION :"
  grep -v "unchecked\|Recompile" "$SRC/erreurs.txt" || cat "$SRC/erreurs.txt"
  exit 1
fi
grep -v "unchecked\|Recompile" "$SRC/erreurs.txt" 2>/dev/null || true

echo "== assemblage =="
# 1) base : le fat jar de Zyker (contient G-Earth 1.5.4-beta-10 + G-Presets)
(cd etape && unzip -q "$GPRESETS")

# 2) G-BuildTools n'est PAS fusionne : sa classe furnidata.FurniDataTools est
#    homonyme de celle de G-Presets avec un constructeur different, ce qui
#    provoquait un NoSuchMethodError et la deconnexion. Son Poster mover est
#    reecrit dans atelier/OutilDeplacer.java.

# 3) mes ressources : la feuille de style « Papier » remplace celle du theme
if [ -d "$SRC/ressources" ]; then
  cp -R "$SRC/ressources/." etape/
fi

# 4) mes classes (et ma version de la lecture de la cle RC4 de G-Earth, par-dessus la sienne)
cp -R out/atelier etape/
[ -d out/gearth ] && cp -R out/gearth etape/

# 5) manifeste : point d'entree + chemin de classe vers les dependances
python3 - "$SRC" "$DEPS" "$GPRESETS" <<'PY'
import os, sys, zipfile
src, dep, base = sys.argv[1], sys.argv[2], sys.argv[3]
jars = sorted(f for f in os.listdir(dep) if f.endswith('.jar'))
cp = ' '.join('Dependencies/' + j for j in jars)
with zipfile.ZipFile(base) as z:
    raw = z.read('META-INF/MANIFEST.MF').decode('utf-8')
attrs, out = [], []
for l in raw.split('\n'):
    if not l.strip(): continue
    if l.startswith(' '): attrs[-1] += l[1:]
    else: attrs.append(l.rstrip('\r'))
for a in attrs:
    if a.startswith(('Main-Class:', 'Class-Path:')): continue
    out.append(a)
out.append('Main-Class: atelier.AtelierLauncher')
out.append('Class-Path: ' + cp)
def fold(s):
    b, res, first = s.encode('utf-8'), [], True
    while b:
        n = 72 if first else 71
        res.append((b'' if first else b' ') + b[:n]); b = b[n:]; first = False
    return b'\r\n'.join(res)
open(os.path.join(src, 'manifeste.txt'), 'wb').write(
    b'\r\n'.join(fold(a) for a in out) + b'\r\n\r\n')
print(f'  {len(jars)} dependances referencees')
PY

rm -rf etape/META-INF/MANIFEST.MF
(cd etape && "$JDK/bin/jar" cfm "$SRC/Atelier.jar" "$SRC/manifeste.txt" .)

echo "== installation =="
# Copie puis RENOMMAGE, jamais de cp par-dessus : un Atelier en cours lit ses
# classes dans le jar au fil de l'eau. Reecrit sur place, il perdait les classes
# pas encore chargees (NoClassDefFoundError, puis deconnexion). Renomme, l'ancien
# fichier reste lisible par l'Atelier en cours jusqu'a sa fermeture.
cp "$SRC/Atelier.jar" "$CIBLE/.Atelier.jar.nouveau"
mv -f "$CIBLE/.Atelier.jar.nouveau" "$CIBLE/Atelier.jar"
if pgrep -f "java -jar Atelier.jar" >/dev/null 2>&1; then
  echo "  ATTENTION : un Atelier tourne. Il garde l'ancienne version : relance-le pour la nouvelle."
fi
echo "  $(ls -lh "$CIBLE/Atelier.jar" | awk '{print $5}')  ->  $CIBLE/Atelier.jar"
echo
echo "Lancer :"
echo "  cd ~/Documents/Atelier"
echo "  sudo $JDK/bin/java -jar Atelier.jar"

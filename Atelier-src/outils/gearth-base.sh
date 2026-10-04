#!/bin/bash
# Fabrique Dependencies/gearth-base.jar a partir du fat jar de Zyker
# (G-Earth 1.5.4-beta-10 + G-Presets + bibliotheques).
#
# Garde : gearth/** (licence MIT), build.properties, logback.xml, org/** (json,
# commons-io, richtextfx, reactfx, flowless, undofx, wellbehavedfx), META-INF
# (licences, versions, poms de G-Earth et des bibliotheques).
# Retire : tout ce qui est a G-Presets, c'est-a-dire extension/** (dont
# extension/ui/gpresets.fxml et styles.css), game/**, furnidata/**, utils/**,
# META-INF/maven/gearthextensions/** ; et la ligne Main-Class du manifeste
# (elle designait extension.GPresetsLauncher).
#
#   bash ~/Documents/Atelier-src/outils/gearth-base.sh [jar-source] [jar-cible]
#
# Le script echoue si une classe gardee reference encore un paquet retire.
set -e

SOURCE="${1:-$HOME/Downloads/GPresets-v1.3.8-fix by Zyker.jar}"
# Dans les sources, pas dans ~/Documents/Atelier/Dependencies : build.sh met
# tout ce dossier-la dans le classpath et le manifeste.
CIBLE="${2:-$HOME/Documents/Atelier-src/Dependencies/gearth-base.jar}"
JDK="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"

[ -f "$SOURCE" ] || { echo "ERREUR : introuvable -> $SOURCE"; exit 1; }
mkdir -p "$(dirname "$CIBLE")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$SOURCE" "$TMP/base.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
RETIRES = ('extension/', 'game/', 'furnidata/', 'utils/', 'META-INF/maven/gearthextensions/')
gardes = retires = 0
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    # Le manifeste en premier, comme le veut java.util.jar.
    noms = zin.namelist()
    ordre = [n for n in noms if n.upper() == 'META-INF/MANIFEST.MF'] + \
            [n for n in noms if n.upper() != 'META-INF/MANIFEST.MF']
    for n in ordre:
        if n.startswith(RETIRES):
            retires += 1
            continue
        data = zin.read(n)
        if n.upper() == 'META-INF/MANIFEST.MF':
            lignes = data.decode('utf-8').replace('\r\n', '\n').split('\n')
            lignes = [l for l in lignes if not l.startswith('Main-Class:')]
            texte = '\r\n'.join(l for l in lignes if l.strip()) + '\r\n\r\n'
            data = texte.encode('utf-8')
        zout.writestr(zin.getinfo(n), data)
        gardes += 1
print(f'  {gardes} entrees gardees, {retires} retirees')
PY

# Controle : aucune classe gardee ne doit pointer vers un paquet retire.
mkdir "$TMP/x"
(cd "$TMP/x" && unzip -q "$TMP/base.jar")
FAUTES=$(cd "$TMP/x" && grep -rlE 'L(extension|game|furnidata|utils)/[A-Za-z]' --include='*.class' . || true)
if [ -n "$FAUTES" ]; then
  echo "ERREUR : des classes gardees referencent un paquet retire :"
  echo "$FAUTES"
  exit 1
fi

# Copie puis renommage (un Atelier en cours peut lire l'ancien fichier).
cp "$TMP/base.jar" "$CIBLE.nouveau"
mv -f "$CIBLE.nouveau" "$CIBLE"
echo "  $(ls -lh "$CIBLE" | awk '{print $5}')  ->  $CIBLE"

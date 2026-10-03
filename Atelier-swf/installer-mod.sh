#!/bin/sh
# Installe le HabboAir.swf modifie dans le client du Launcher, puis re-signe l'app.
# Usage : sh installer-mod.sh [fichier.swf]   (par defaut : le dernier test)
set -e
APP="$HOME/Library/Application Support/Habbo Launcher/downloads/air/16/Habbo.app"
SWF="${1:-$HOME/Documents/Atelier-swf/travail/HabboAir-atelier.swf}"
if pgrep -f "Habbo.app/Contents/MacOS/Habbo" >/dev/null; then
  echo "Ferme d'abord Habbo (le jeu), puis relance ce script."; exit 1
fi
[ -f "$SWF" ] || { echo "Introuvable : $SWF"; exit 1; }
cp "$SWF" "$APP/Contents/Resources/HabboAir.swf"
# La signature Apple ne correspond plus au SWF modifie : signature locale a la place.
codesign --force --deep --sign - "$APP"
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true
echo "SWF modifie installe. Lance Habbo par le Launcher, comme d'habitude."

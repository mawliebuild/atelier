#!/bin/sh
# Remet le client Habbo d'origine (sauvegarde faite avant toute modification).
set -e
APP="$HOME/Library/Application Support/Habbo Launcher/downloads/air/16/Habbo.app"
if pgrep -f "Habbo.app/Contents/MacOS/Habbo" >/dev/null; then
  echo "Ferme d'abord Habbo (le jeu), puis relance ce script."; exit 1
fi
rm -rf "$APP"
cp -R "$HOME/Documents/Atelier-swf/Habbo.app.origine" "$APP"
echo "Client d'origine remis."

#!/bin/bash
# Prepare deux archives a donner (Mac et Windows), sans les donnees personnelles :
#   ~/Desktop/Atelier-Mac.zip et ~/Desktop/Atelier-Windows.zip
# Chacune : un lanceur a double-cliquer, Java embarque, le client modifie deja construit.
set -e
B="$HOME/Desktop"
T=$(mktemp -d)
A="$HOME/Documents/Atelier"; S="$HOME/Documents/Atelier-swf"
[ -d "$S/jre/mac" ] && [ -d "$S/jre/win" ] || { echo "Java embarque manquant dans $S/jre (mac/ et win/)."; exit 1; }
[ -d "$S/python/mac" ] && [ -d "$S/python/win" ] || { echo "Python embarque manquant dans $S/python (mac/ et win/)."; exit 1; }
for os in Mac Windows; do
  R="$T/$os"
  mkdir -p "$R/Atelier/java" "$R/Atelier/python" "$R/Atelier-swf/donnees"
  cp "$A/Atelier.jar" "$R/Atelier/"
  cp -R "$A/Dependencies" "$R/Atelier/"
  # listes des messages du jeu deja connues (plus besoin de les telecharger)
  [ -d "$A/messages" ] && cp -R "$A/messages" "$R/Atelier/"
  # Les modifs du jeu se construisent sur chaque ordinateur, a partir de SON client Habbo :
  # aucun fichier de Habbo dans le paquet, et chaque nouvelle version est prise en charge.
  cp "$S/construire.py" "$S/modifier-jeu.py" "$R/Atelier-swf/"
  cp -R "$S/ffdec" "$S/dessins" "$R/Atelier-swf/"
  cp "$S/donnees/collections_fr.json" "$R/Atelier-swf/donnees/"
  if [ "$os" = Mac ]; then
    cp "$A/G-MemZ" "$R/Atelier/"
    cp -R "$S"/jre/mac/* "$R/Atelier/java/"
    cp -R "$S"/python/mac/* "$R/Atelier/python/"
    cp "$S/Lancer-Atelier-Mac.command" "$R/Lancer l'Atelier.command"
    cp "$S/LISEZMOI-Mac.txt" "$R/LISEZMOI.txt"
  else
    cp -R "$S"/jre/win/* "$R/Atelier/java/"
    cp -R "$S"/python/win/* "$R/Atelier/python/"
    cp "$S/Lancer-Atelier-Windows.bat" "$R/Lancer l'Atelier.bat"
    cp "$S/LISEZMOI-Windows.txt" "$R/LISEZMOI.txt"
  fi
done
rm -f "$B/Atelier-Mac.zip" "$B/Atelier-Windows.zip"
(cd "$T/Mac" && zip -qry "$B/Atelier-Mac.zip" .)
(cd "$T/Windows" && zip -qr "$B/Atelier-Windows.zip" .)
rm -rf "$T"
echo "Prêt : $B/Atelier-Mac.zip et $B/Atelier-Windows.zip"

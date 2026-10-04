#!/bin/bash
# Prepare deux archives a donner (Mac et Windows), sans les donnees personnelles :
#   ~/Desktop/Atelier-Mac.zip et ~/Desktop/Atelier-Windows.zip
# Chacune : un lanceur a double-cliquer, Java embarque, le client modifie deja construit.
set -e
B="$HOME/Desktop"
T=$(mktemp -d)
A="$HOME/Documents/Atelier"; S="$HOME/Documents/Atelier-swf"
[ -d "$S/jre/mac" ] && [ -d "$S/jre/win" ] || { echo "Java embarque manquant dans $S/jre (mac/ et win/)."; exit 1; }
for os in Mac Windows; do
  R="$T/$os"
  mkdir -p "$R/Atelier/java" "$R/Atelier-swf/travail" "$R/Atelier-swf/donnees" "$R/Atelier-swf/export-bin"
  cp "$A/Atelier.jar" "$R/Atelier/"
  cp -R "$A/Dependencies" "$R/Atelier/"
  cp "$S/construire.py" "$R/Atelier-swf/"
  cp -R "$S/ffdec" "$S/dessins" "$R/Atelier-swf/"
  cp "$S/donnees/collections_fr.json" "$R/Atelier-swf/donnees/"
  cp "$S"/export-bin/*inventory_xml* "$R/Atelier-swf/export-bin/"
  cp "$S"/travail/*.orig.pcode "$R/Atelier-swf/travail/"
  cp "$S/travail/HabboAir-atelier.swf" "$R/Atelier-swf/travail/"     # client modifie pret a installer
  if [ "$os" = Mac ]; then
    cp "$A/G-MemZ" "$R/Atelier/"
    cp "$S/installer-mod.sh" "$S/restaurer.sh" "$R/Atelier-swf/"
    cp -R "$S/Habbo.app.origine" "$R/Atelier-swf/"
    cp -R "$S"/jre/mac/* "$R/Atelier/java/"
    cp "$S/Lancer-Atelier-Mac.command" "$R/Lancer l'Atelier.command"
    cp "$S/LISEZMOI-Mac.txt" "$R/LISEZMOI.txt"
  else
    mkdir -p "$R/Atelier-swf/Habbo.app.origine/Contents/Resources"
    cp "$S/Habbo.app.origine/Contents/Resources/HabboAir.swf" "$R/Atelier-swf/Habbo.app.origine/Contents/Resources/"
    cp -R "$S"/jre/win/* "$R/Atelier/java/"
    mkdir -p "$R/Atelier-swf/origines"
    cp "$S"/origines/HabboAir-win-*.swf "$R/Atelier-swf/origines/"
    cp "$S"/travail/HabboAir-atelier-win-*.swf "$R/Atelier-swf/travail/"
    [ -f "$S/G-MemZ.exe" ] && cp "$S/G-MemZ.exe" "$R/Atelier/"
    cp "$S/Lancer-Atelier-Windows.bat" "$R/Lancer l'Atelier.bat"
    cp "$S/LISEZMOI-Windows.txt" "$R/LISEZMOI.txt"
  fi
done
rm -f "$B/Atelier-Mac.zip" "$B/Atelier-Windows.zip"
(cd "$T/Mac" && zip -qry "$B/Atelier-Mac.zip" .)
(cd "$T/Windows" && zip -qr "$B/Atelier-Windows.zip" .)
rm -rf "$T"
echo "Prêt : $B/Atelier-Mac.zip et $B/Atelier-Windows.zip"

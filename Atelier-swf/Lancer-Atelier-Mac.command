#!/bin/bash
# L'Atelier — double-clic : installe (dans Documents), applique les modifs du jeu, puis lance.
# En cas de probleme, tout est explique ici, dans cette fenetre.
clear
echo "================  L'ATELIER  ================"
echo
ICI="$(cd "$(dirname "$0")" && pwd)"
DOCS="$HOME/Documents"
APP="$HOME/Library/Application Support/Habbo Launcher/downloads/air/16/Habbo.app"

probleme() {
  echo
  echo "⚠️  PROBLÈME : $1"
  [ -n "$2" ] && echo "    → $2"
  echo
  read -r -p "Appuie sur Entrée pour fermer cette fenêtre." _
  exit 1
}
ok() { echo "✓ $1"; }

# 1. Les fichiers, dans Documents
if [ -d "$ICI/Atelier" ] && [ "$ICI" != "$DOCS" ]; then
  echo "… Installation dans ton dossier Documents"
  for d in Atelier Atelier-swf; do
    mkdir -p "$DOCS/$d" && rsync -a "$ICI/$d/" "$DOCS/$d/" || probleme "Copie de « $d » impossible." "Vérifie qu'il reste de la place sur le disque."
  done
fi
[ -f "$DOCS/Atelier/Atelier.jar" ] || probleme "Atelier.jar introuvable dans Documents/Atelier." "Décompresse tout le zip, puis double-clique ce fichier depuis le dossier décompressé."
xattr -dr com.apple.quarantine "$DOCS/Atelier" "$DOCS/Atelier-swf" 2>/dev/null
chmod +x "$DOCS/Atelier/G-MemZ" 2>/dev/null
ok "Fichiers en place"

# 2. Java (embarque)
JAVA="$(ls -d "$DOCS"/Atelier/java/*/Contents/Home 2>/dev/null | head -1)/bin/java"
[ -x "$JAVA" ] || probleme "Java embarqué introuvable." "Re-télécharge le paquet complet de l'Atelier."
[ "$(uname -m)" = "arm64" ] || probleme "Ce Mac est un Mac Intel." "L'Atelier est prévu pour les Mac Apple Silicon (M1, M2…)."
ok "Java prêt"

# 3. Les modifs du jeu, adaptees a la version de Habbo de cet ordinateur (et aux mises a jour)
PY="$DOCS/Atelier/python/python/bin/python3"
if [ -x "$PY" ]; then
  export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"
  "$PY" "$DOCS/Atelier-swf/modifier-jeu.py"
else
  echo "! Python embarqué introuvable : les modifs du jeu ne sont pas installées (l'Atelier marche quand même)."
fi

# 4. Lancement (droits administrateur : l'Atelier se branche sur la connexion de Habbo)
echo
echo "Ton mot de passe Mac va être demandé (rien ne s'affiche quand tu le tapes, c'est normal)."
echo "Ensuite : ouvre Habbo par le Launcher, l'Atelier se connecte tout seul."
echo "Garde cette fenêtre ouverte tant que tu utilises l'Atelier."
echo
cd "$DOCS/Atelier" || probleme "Dossier Documents/Atelier inaccessible."
sudo "$JAVA" -jar Atelier.jar
code=$?
[ $code -ne 0 ] && probleme "L'Atelier s'est arrêté (code $code)." "Regarde les dernières lignes ci-dessus et envoie-les à celle qui t'a donné l'Atelier."

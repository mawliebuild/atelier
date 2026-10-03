#!/bin/bash
# Envoie le code (Atelier-src + Atelier-swf) sur GitHub : bash ~/Documents/Atelier-swf/publier.sh "message"
g() { git --git-dir="$HOME/Documents/.atelier-git" --work-tree="$HOME/Documents" "$@"; }
g add -A && g commit -q -m "${1:-Mise à jour}" && g push -q && echo "Envoyé sur GitHub." || echo "Rien de nouveau à envoyer (ou envoi impossible)."

# L'Atelier

Outils de construction pour Habbo (client AIR), par-dessus G-Earth et G-Presets :
calques, dalles magiques et hauteurs, wired, inventaire par collections, Monster Plants,
galerie, capture d'appart…

## Dossiers

À cloner **dans `~/Documents`** (les chemins `~/Documents/Atelier-src` et `~/Documents/Atelier-swf` sont attendus).

- `Atelier-src/` — le code de l'Atelier (Java 17 + JavaFX). `build.sh` construit `~/Documents/Atelier/Atelier.jar`.
- `Atelier-swf/` — le client Habbo modifié : `construire.py` modifie `HabboAir.swf` (P-code, avec FFDec),
  `installer-mod.sh` / `restaurer.sh` l'installent ou remettent l'original (Mac).
  Les lanceurs `Lancer-Atelier-Mac.command` / `Lancer-Atelier-Windows.bat` et `preparer-paquet.sh` font les zips à donner.

## Pas dans le dépôt (à fournir à part)

- `~/Downloads/GPresets-v1.3.8-fix by Zyker.jar` (G-Earth 1.5.4 + G-Presets) et `~/Documents/Atelier/Dependencies/`.
- `Atelier-swf/ffdec/` (FFDec), `Atelier-swf/Habbo.app.origine/` (le client Habbo d'origine), `Atelier-swf/jre/` (Java embarqué des paquets).
- Les données personnelles (patrimoine, calques, presets).

## Construire et lancer (Mac)

```
bash ~/Documents/Atelier-src/build.sh
cd ~/Documents/Atelier && sudo /Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java -jar Atelier.jar
```

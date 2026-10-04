@echo off
rem Le chemin du fichier passe par la variable SELF : une apostrophe dans son nom cassait la demande administrateur.
chcp 65001 >nul
title L'Atelier
rem L'Atelier - double-clic : installe (dans Documents), applique les modifs du jeu, puis lance.
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Demande des droits administrateur...
  set "SELF=%~f0"
  powershell -NoProfile -Command "Start-Process -FilePath $env:SELF -Verb RunAs"
  if errorlevel 1 (
    echo.
    echo PROBLEME : la demande administrateur n'a pas pu s'ouvrir.
    echo     -^> Fais clic droit sur ce fichier ^> Executer en tant qu'administrateur.
    pause
  )
  exit /b
)
cd /d "%~dp0"
set "ICI=%~dp0"
set "DOCS=%USERPROFILE%\Documents"
echo ================  L'ATELIER  ================
echo.

rem 1. Les fichiers, dans Documents
if exist "%ICI%Atelier\Atelier.jar" (
  echo ... Installation dans ton dossier Documents
  robocopy "%ICI%Atelier" "%DOCS%\Atelier" /E /NFL /NDL /NJH /NJS /NP >nul
  robocopy "%ICI%Atelier-swf" "%DOCS%\Atelier-swf" /E /NFL /NDL /NJH /NJS /NP >nul
)
if not exist "%DOCS%\Atelier\Atelier.jar" (
  call :probleme "Atelier.jar introuvable dans Documents\Atelier." "Decompresse tout le zip, puis double-clique ce fichier depuis le dossier decompresse."
  exit /b 1
)
echo OK  Fichiers en place

rem 2. Java (embarque)
set "JAVA="
for /d %%D in ("%DOCS%\Atelier\java\*") do if exist "%%D\bin\java.exe" set "JAVA=%%D\bin\java.exe"
if not defined JAVA (
  call :probleme "Java embarque introuvable." "Re-telecharge le paquet complet de l'Atelier."
  exit /b 1
)
echo OK  Java pret

rem 3. Les modifs du jeu, adaptees a la version de Habbo de cet ordinateur (et aux mises a jour)
for %%J in ("%JAVA%") do set "JBIN=%%~dpJ"
for %%H in ("%JBIN%..") do set "JAVA_HOME=%%~fH"
if exist "%DOCS%\Atelier\python\python.exe" (
  "%DOCS%\Atelier\python\python.exe" "%DOCS%\Atelier-swf\modifier-jeu.py"
  if errorlevel 1 (
    echo.
    echo Les modifs du jeu ne sont pas installees ^(voir le message au-dessus^).
    echo Le detail est dans Documents\Atelier-swf\modifier-jeu.log : envoie ce fichier si besoin.
    echo Appuie sur une touche pour lancer l'Atelier quand meme.
    pause >nul
  )
) else (
  echo !   Python embarque introuvable : les modifs du jeu ne sont pas installees ^(l'Atelier marche quand meme^).
)

:lancer
echo.
echo Ouvre Habbo par le Launcher : l'Atelier se connecte tout seul.
echo Garde cette fenetre ouverte tant que tu utilises l'Atelier.
echo.
cd /d "%DOCS%\Atelier"
"%JAVA%" -jar Atelier.jar
if %errorlevel% neq 0 call :probleme "L'Atelier s'est arrete (code %errorlevel%)." "Envoie les dernieres lignes ci-dessus a celle qui t'a donne l'Atelier."
exit /b

:probleme
echo.
echo PROBLEME : %~1
echo     -^> %~2
echo.
pause
exit /b

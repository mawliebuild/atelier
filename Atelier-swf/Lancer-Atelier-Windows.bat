@echo off
chcp 65001 >nul
title L'Atelier
rem L'Atelier - double-clic : installe (dans Documents), applique les modifs du jeu, puis lance.
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Demande des droits administrateur...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
cd /d "%~dp0"
set "ICI=%~dp0"
set "DOCS=%USERPROFILE%\Documents"
set "HABBO=%APPDATA%\Habbo Launcher\downloads\air\16"
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

rem 3. Habbo et les modifs du jeu : seulement sur le client prevu (meme fichier que l'original fourni)
set "ORIG=%DOCS%\Atelier-swf\Habbo.app.origine\Contents\Resources\HabboAir.swf"
set "MOD=%DOCS%\Atelier-swf\travail\HabboAir-atelier.swf"
set "TROUVE="
set "DEJA="
for /r "%APPDATA%\Habbo Launcher\downloads\air" %%F in (HabboAir.swf) do (
  if exist "%%F" (
    fc /b "%%F" "%MOD%" >nul 2>&1 && set "DEJA=%%F"
    fc /b "%%F" "%ORIG%" >nul 2>&1 && set "TROUVE=%%F"
  )
)
if defined DEJA (
  echo OK  Modifs du jeu deja installees
  goto lancer
)
if not defined TROUVE (
  echo !   Ton client Habbo n'est pas la version prevue pour les modifs du jeu ^(grille, categories...^).
  echo     Elles ne sont pas installees : ton jeu reste intact. L'Atelier marche quand meme.
  goto lancer
)
tasklist /FI "IMAGENAME eq Habbo.exe" /NH | find /I "Habbo.exe" >nul
if %errorlevel% equ 0 (
  echo !   Habbo est ouvert : ferme-le puis relance ce fichier pour avoir les modifs du jeu.
  goto lancer
)
if not exist "%DOCS%\Atelier-swf\HabboAir-origine-windows.swf" copy /Y "%TROUVE%" "%DOCS%\Atelier-swf\HabboAir-origine-windows.swf" >nul
copy /Y "%MOD%" "%TROUVE%" >nul && echo OK  Modifs du jeu installees || echo !   Les modifs du jeu n'ont pas pu s'installer.

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

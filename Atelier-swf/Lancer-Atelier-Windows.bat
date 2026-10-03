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

rem 4. Habbo et les modifs du jeu
set "SWFJEU="
for /r "%HABBO%" %%F in (HabboAir.swf) do if exist "%%F" set "SWFJEU=%%F"
if not defined SWFJEU (
  echo !   Habbo ^(version 16^) introuvable : ouvre le Habbo Launcher, lance Habbo une fois, ferme-le, puis relance ce fichier.
  echo     L'Atelier s'ouvre quand meme, sans les modifs du jeu.
  goto lancer
)
tasklist /FI "IMAGENAME eq Habbo.exe" /NH | find /I "Habbo.exe" >nul
if %errorlevel% equ 0 (
  echo !   Habbo est ouvert : ferme-le puis relance ce fichier pour avoir les modifs du jeu.
  goto lancer
)
if not exist "%DOCS%\Atelier-swf\HabboAir-origine-windows.swf" copy /Y "%SWFJEU%" "%DOCS%\Atelier-swf\HabboAir-origine-windows.swf" >nul
copy /Y "%DOCS%\Atelier-swf\travail\HabboAir-atelier.swf" "%SWFJEU%" >nul && echo OK  Modifs du jeu installees || echo !   Les modifs du jeu n'ont pas pu s'installer.

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

#!/bin/zsh
# Build MineMetal (NeoForge) and drop it into the ATM10 Prism instance, replacing any older MineMetal jar.
set -e
cd "$(dirname "$0")"
MODS="$HOME/Library/Application Support/PrismLauncher/instances/All the Mods 10 - ATM10/minecraft/mods"
./gradlew build -q
JAR=$(ls -t build/libs/minemetal-neoforge-*.jar | grep -v sources | head -1)
setopt null_glob
rm -f "$MODS"/minemetal-neoforge-*.jar
cp "$JAR" "$MODS/"
echo "Installed $(basename "$JAR") into ATM10 — start the instance in Prism."

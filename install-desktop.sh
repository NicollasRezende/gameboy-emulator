#!/usr/bin/env bash
# Instala um atalho clicável "GB Emulator" no menu de aplicativos (Linux).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "==> Buildando o app (self-contained)..."
./gradlew -q :desktop:installDist

APP="$ROOT/desktop/build/install/desktop"
SHARE="$HOME/.local/share/gb-emulator"
APPS="$HOME/.local/share/applications"
mkdir -p "$SHARE" "$APPS"

echo "==> Gerando o ícone..."
"$APP/bin/desktop" --render-icon "$SHARE/icon.png"

cat > "$APPS/gb-emulator.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=GB Emulator
Comment=Emulador de Game Boy / Game Boy Color
Exec=$APP/bin/desktop %f
Icon=$SHARE/icon.png
Terminal=false
Categories=Game;Emulator;
EOF
chmod +x "$APPS/gb-emulator.desktop" 2>/dev/null || true
update-desktop-database "$APPS" 2>/dev/null || true

echo "==> Pronto! Procure 'GB Emulator' no menu de aplicativos e clique."
echo "    Atalho: $APPS/gb-emulator.desktop"
echo "    Também dá pra clicar direto no launcher: $APP/bin/desktop"

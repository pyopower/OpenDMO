#!/bin/sh
# Empaqueta un .ipk de OpenWrt (formato tar.gz moderno) a partir de un binario
# ya cross-compilado. Uso: build-ipk.sh <arch_opkg> <binario> <version>
#   ej: build-ipk.sh mipsel_24kc dist/opendmo-mango 0.1.0
set -e

ARCH="$1"; BIN="$2"; VERSION="$3"
[ -n "$ARCH" ] && [ -f "$BIN" ] && [ -n "$VERSION" ] || {
	echo "uso: $0 <arch_opkg> <binario> <version>" >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# --- data.tar.gz ---
mkdir -p "$TMP/data/usr/bin" "$TMP/data/etc/init.d" "$TMP/data/etc/config"
cp "$BIN" "$TMP/data/usr/bin/opendmo"
chmod 755 "$TMP/data/usr/bin/opendmo"
cp "$HERE/opendmo.init" "$TMP/data/etc/init.d/opendmo"
chmod 755 "$TMP/data/etc/init.d/opendmo"
cp "$HERE/opendmo.config" "$TMP/data/etc/config/opendmo"
chmod 600 "$TMP/data/etc/config/opendmo"   # lleva la passphrase

# --- control.tar.gz ---
mkdir -p "$TMP/control"
SIZE=$(wc -c < "$TMP/data/usr/bin/opendmo")
cat > "$TMP/control/control" <<EOF
Package: opendmo
Version: $VERSION
Depends: libc, kmod-usb-acm
Section: net
Architecture: $ARCH
Installed-Size: $SIZE
Maintainer: pyopower
Description: Gateway DMO OpenGD77 <-> master DMR Homebrew (hblink/BM/TGIF/ADN).
 La radio OpenGD77 en modo hotspot se conecta al USB del router y el daemon
 puentea sus bursts DMR con un master HBP. Config en /etc/config/opendmo.
EOF
cat > "$TMP/control/conffiles" <<EOF
/etc/config/opendmo
EOF
# postinst estilo OpenWrt: habilita el init script al instalar en el router
cat > "$TMP/control/postinst" <<'EOF'
#!/bin/sh
[ -n "$IPKG_INSTROOT" ] || {
	/etc/init.d/opendmo enable
	echo "OpenDMO instalado. Edita /etc/config/opendmo y ejecuta: /etc/init.d/opendmo start"
}
exit 0
EOF
chmod 755 "$TMP/control/postinst"
cat > "$TMP/control/prerm" <<'EOF'
#!/bin/sh
[ -n "$IPKG_INSTROOT" ] || {
	/etc/init.d/opendmo stop 2>/dev/null
	/etc/init.d/opendmo disable 2>/dev/null
}
exit 0
EOF
chmod 755 "$TMP/control/prerm"

# --- ensamblado ---
echo "2.0" > "$TMP/debian-binary"
TARFLAGS="--owner=0 --group=0 --numeric-owner"
tar -C "$TMP/control" -czf "$TMP/control.tar.gz" $TARFLAGS .
tar -C "$TMP/data"    -czf "$TMP/data.tar.gz"    $TARFLAGS .

OUT="$(dirname "$BIN")/opendmo_${VERSION}_${ARCH}.ipk"
tar -C "$TMP" -czf "$OUT" $TARFLAGS ./debian-binary ./control.tar.gz ./data.tar.gz
echo "OK: $OUT"

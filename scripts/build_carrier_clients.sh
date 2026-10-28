#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
[[ "$REF" =~ ^[0-9a-f]{40}$ ]]
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/qthrone-carriers.XXXXXX")
OUT="$ROOT/deployment/carriers/$ABI"
mkdir -p "$OUT"
# Prevent an old local build from leaking a removed, non-commercial transport into the APK.
rm -f "$OUT/libcsqtt_transport.so" "$ROOT/app/src/main/assets/carrier-licenses/$ABI/CSQTT-LICENSE"
fetch_source() {
  local folder=$1 url=$2 revision=$3
  git init -q "$folder"
  git -C "$folder" fetch -q --depth=1 "$url" "$revision"
  git -C "$folder" checkout -q --detach FETCH_HEAD
  test "$(git -C "$folder" rev-parse HEAD)" = "$revision"
}
fetch_source "$SOURCE/qThrone" https://github.com/ttrroyy/qThrone.git "$REF"
# Keep the safe transport counters available to app builds until the pinned upstream revision includes them.
git -C "$SOURCE/qThrone" apply "$ROOT/patches/qwdtt-network-diagnostics.patch"
case "$ABI" in
  arm64-v8a) GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) GOARCH=arm; export GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOARCH GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
test -x "$CC"
# Only the executable name changes; the desktop's encrypted transport and private IPC stay intact.
python3 - "$SOURCE/qThrone/qwdtt/csqtt_bridge.go" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text(encoding='utf-8')
old='name := "csqtt-transport"'
assert s.count(old)==1
s=s.replace(old, old+'\n\tif runtime.GOOS == "android" { name = "libcsqtt_transport.so" }')
p.write_text(s,encoding='utf-8')
PY
python3 "$ROOT/scripts/prepare_android_captcha.py" "$SOURCE/qThrone/qwdtt"
(cd "$SOURCE/qThrone/qwdtt"; gofmt -w creds.go csqtt_bridge.go bridge_config.go vk_account.go; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libqwdtt.so" .)
cp "$SOURCE/qThrone/qwdtt/LICENSE" "$OUT/qWDTT-LICENSE"
# WDTT Plus: its own native client, built as its Gradle task builds libclient.so.
WDTTPLUS=f706ccd373685bbde20cae33b2b7f400e8335ecc
fetch_source "$SOURCE/wdttplus" https://github.com/Ivan4537/WDTT-Plus.git "$WDTTPLUS"
(cd "$SOURCE/wdttplus/go_client"; go build -buildvcs=false -trimpath -buildmode=pie \
  -ldflags='-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT/libwdttplus.so" .)
cp "$SOURCE/wdttplus/LICENSE" "$OUT/WDTT-Plus-LICENSE"
OLC=f3ad8fb7c0d0fa981423f83269526fa94077c23d
fetch_source "$SOURCE/olcrtc" https://github.com/openlibrecommunity/olcrtc.git "$OLC"
mkdir -p "$SOURCE/olcrtc/cmd/qthrone-olcrtc"
cp "$ROOT/carriers/olcrtc/"*.go "$SOURCE/olcrtc/cmd/qthrone-olcrtc/"
(cd "$SOURCE/olcrtc"; GOOS= GOARCH= CGO_ENABLED=0 go test -count=1 ./cmd/qthrone-olcrtc)
(cd "$SOURCE/olcrtc"; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libolcrtc.so" ./cmd/qthrone-olcrtc)
cp "$SOURCE/olcrtc/LICENSE" "$OUT/OLCRTC-LICENSE"
# The client runs through OpenFlux's own mobile package, as the original Android app does.
FLUX=edcf77a1848a72370cfba9f346d3b897b61e7e7d
fetch_source "$SOURCE/openflux" https://github.com/p1neappleXpress/OpenFlux.git "$FLUX"
mkdir -p "$SOURCE/openflux/mobile/cmd/qthrone-openflux"
cp "$ROOT/carriers/openflux/"*.go "$SOURCE/openflux/mobile/cmd/qthrone-openflux/"
(cd "$SOURCE/openflux/mobile"; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libopenflux.so" ./cmd/qthrone-openflux)
cp "$SOURCE/openflux/LICENSE" "$OUT/OpenFlux-LICENSE"
# DNSTT and VayDNS: VayDNS's client library (DNSTT in its wire compatibility mode).
VAYDNS=a0ff70110d5e96686ab8f4c8e1c44cd09be07a75
fetch_source "$SOURCE/vaydns" https://github.com/net2share/vaydns.git "$VAYDNS"
mkdir -p "$SOURCE/vaydns/cmd/qthrone-dnstun"
cp "$ROOT/carriers/dnstun/"*.go "$SOURCE/vaydns/cmd/qthrone-dnstun/"
(cd "$SOURCE/vaydns"; go get github.com/shadowsocks/go-shadowsocks2@v0.1.5; go build -trimpath -buildmode=pie -ldflags='-s -w' -o "$OUT/libdnstun.so" ./cmd/qthrone-dnstun)
cp "$SOURCE/vaydns/COPYING" "$OUT/VayDNS-LICENSE"
MASTERDNS=acbf1c61f90786f41b975d2e2f616afbce292b29
fetch_source "$SOURCE/masterdns" https://github.com/masterking32/MasterDnsVPN.git "$MASTERDNS"
mkdir -p "$SOURCE/masterdns/cmd/qthrone-masterdns"
cp "$ROOT/carriers/masterdns/"*.go "$SOURCE/masterdns/cmd/qthrone-masterdns/"
(cd "$SOURCE/masterdns"; go build -trimpath -buildmode=pie -ldflags='-s -w' -o "$OUT/libmasterdns.so" ./cmd/qthrone-masterdns)
cp "$SOURCE/masterdns/LICENSE" "$OUT/MasterDnsVPN-LICENSE"
# S3 Bypass: the fedarisha protocol of Fedarisha/Xray-core-fedarisha, which the original app runs.
FEDARISHA=a04f4c09972b2aa7d6255197732c1c20e3fdc8a1
fetch_source "$SOURCE/fedarisha" https://github.com/Fedarisha/Xray-core-fedarisha.git "$FEDARISHA"
mkdir -p "$SOURCE/fedarisha/main/qthrone-s3bypass"
cp "$ROOT/carriers/s3bypass/"*.go "$SOURCE/fedarisha/main/qthrone-s3bypass/"
(cd "$SOURCE/fedarisha"; go build -trimpath -buildmode=pie -ldflags='-s -w -buildid= -checklinkname=0' -o "$OUT/libs3bypass.so" ./main/qthrone-s3bypass)
cp "$SOURCE/fedarisha/LICENSE" "$OUT/Xray-fedarisha-LICENSE"
# Slipstream uses the same pinned Rust toolchain and cargo-ndk as the Android client build.
rustup toolchain install 1.97.1 --profile minimal
rustup target add --toolchain 1.97.1 "$RUST"
mkdir -p "$RUNNER_TEMP/qthrone-cargo-tools"
if [[ ! -x "$RUNNER_TEMP/qthrone-cargo-tools/bin/cargo-ndk" ]]; then
  cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/qthrone-cargo-tools"
fi
export PATH="$RUNNER_TEMP/qthrone-cargo-tools/bin:$PATH"
export CARGO_TARGET_DIR="$SOURCE/rust-build"
# Slipstream: slipstream-rust with picoquic and vendored OpenSSL; the mobile client listens on a private Unix socket.
SLIPSTREAM=7de506b222c83ae97a19a618e33fe26899af1bd5
fetch_source "$SOURCE/slipstream" https://github.com/Mygod/slipstream-rust.git "$SLIPSTREAM"
git -C "$SOURCE/slipstream" submodule update -q --init --recursive --depth 1
python3 "$ROOT/scripts/prepare_mobile_slipstream.py" "$SOURCE/slipstream"
# pkg-config would hand picotls the runner's own brotli; the Android build goes without it.
mkdir -p "$SOURCE/no-pkgconfig"
(cd "$SOURCE/slipstream"; ANDROID_ABI="$ABI" ANDROID_PLATFORM=android-24 PICOQUIC_BUILD_DIR="$SOURCE/picoquic-$ABI" \
  PKG_CONFIG_LIBDIR="$SOURCE/no-pkgconfig" PKG_CONFIG_PATH= QTHRONE_ANDROID_CC="$CC" \
  cargo +1.97.1 ndk -t "$ABI" -P 24 build --release --locked -p slipstream-client --features openssl-vendored,picoquic-minimal-build)
cp "$CARGO_TARGET_DIR/$RUST/release/slipstream-client" "$OUT/libslipstream.so"
cp "$SOURCE/slipstream/LICENSE" "$OUT/Slipstream-LICENSE"
printf 'qThrone %s\nolcRTC %s\nOpenFlux %s\nVayDNS %s\nMasterDnsVPN %s\nXray-core-fedarisha %s\nslipstream-rust %s\nWDTT-Plus %s\nAndroid ABI %s\n' \
  "$REF" "$OLC" "$FLUX" "$VAYDNS" "$MASTERDNS" "$FEDARISHA" "$SLIPSTREAM" "$WDTTPLUS" "$ABI" > "$OUT/SOURCES.txt"
(cd "$OUT"; sha256sum *.so > SHA256SUMS)
for binary in "$OUT/"*.so; do
  test -s "$binary"
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done

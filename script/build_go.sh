#!/bin/bash
set -e

TAGS="with_clash_api,with_quic,with_wireguard,with_utls,with_dhcp,with_tailscale,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0"

rm -rf $DEST
mkdir -p $DEST

[[ "$GOOS" =~ legacy$ ]] && IS_LEGACY=true && GOCMD="$PWD/golang.org/go/bin/go" && GOOS="${GOOS%legacy}" || { IS_LEGACY=false; GOCMD="go"; }
# a go.mod newer than the patched toolchain must fail the build, not auto-fetch an unpatched official Go
if $IS_LEGACY; then
  export GOTOOLCHAIN=local
fi

if [[ "${ONLINESETI_EDITION:-0}" == "1" ]]; then
    PRODUCT_NAME="OnlineSetiVPN"
    UPDATE_ARCHIVE="OnlineSetiVPN.zip"
    CORE_NAME="OnlineSetiCore"
    CORE_BRAND="OnlineSeti"
    UPDATER_NAME="OnlineSetiUpdater"
    WDTT_NAME="OnlineSetiWDTT"
    WDTT_PLUS_NAME="OnlineSetiWDTTPlus"
    OPENFLUX_NAME="OnlineSetiOpenFlux"
    OLCRTC_NAME="OnlineSetiOLCRTC"
else
    PRODUCT_NAME="qThrone"
    UPDATE_ARCHIVE="qThrone.zip"
    CORE_NAME="qThroneCore"
    CORE_BRAND="qThrone"
    UPDATER_NAME="qThroneUpdater"
    WDTT_NAME="qwdtt"
    WDTT_PLUS_NAME="wdttplus"
    OPENFLUX_NAME="openflux"
    OLCRTC_NAME="olcrtc"
fi
export CORE_NAME CORE_BRAND UPDATER_NAME WDTT_NAME WDTT_PLUS_NAME OPENFLUX_NAME OLCRTC_NAME PRODUCT_NAME UPDATE_ARCHIVE

if [[ "$GOOS" == "windows" || "$GOOS" == "linux" ]]; then
    pushd updater
    CGO_ENABLED=0 $GOCMD build -trimpath -ldflags="-s -w -X main.productName=$PRODUCT_NAME -X main.coreName=$CORE_NAME -X main.archiveName=$UPDATE_ARCHIVE" -o "$DEST/$UPDATER_NAME$([[ "$GOOS" == "windows" ]] && echo ".exe")" .
    popd
fi

case "$GOOS" in
  windows)
    export CGO_ENABLED=0
    if ! $IS_LEGACY; then
      TAGS+=",with_purego,with_naive_outbound"
    fi
    ;;
  darwin)
    TAGS+=",with_naive_outbound"
    export CGO_ENABLED=1 CGO_LDFLAGS="-weak_framework UniformTypeIdentifiers"
    # cgo otherwise builds for the runner's SDK and hard-links every API newer than 10.15
    if $IS_LEGACY; then
      export MACOSX_DEPLOYMENT_TARGET=10.15
    fi
    ;;
  linux)
    TAGS+=",with_naive_outbound"
    export CGO_ENABLED=1
    ;;
esac

#### qWDTT: portable userspace bridge (RAW/WG, no host WireGuard interface) ####
pushd qwdtt
CGO_ENABLED=0 $GOCMD build -trimpath -ldflags="-s -w" -o "$DEST/$WDTT_NAME$([[ "$GOOS" == "windows" ]] && echo ".exe")" .
cp LICENSE "$DEST/qwdtt-LICENSE"
popd

#### WDTT Plus: native Go client used by the Android profile ####
WDTT_PLUS_REVISION=f706ccd373685bbde20cae33b2b7f400e8335ecc
if [ ! -d wdttplus-source/.git ]; then
    git init wdttplus-source
    git -C wdttplus-source fetch --depth=1 https://github.com/Ivan4537/WDTT-Plus.git "$WDTT_PLUS_REVISION"
    git -C wdttplus-source checkout FETCH_HEAD
fi
test "$(git -C wdttplus-source rev-parse HEAD)" = "$WDTT_PLUS_REVISION"
pushd wdttplus-source/go_client
CGO_ENABLED=0 "$GOCMD" build -trimpath -buildvcs=false -ldflags='-s -w' -o "$DEST/$WDTT_PLUS_NAME$([[ "$GOOS" == windows ]] && echo '.exe')" .
cp ../LICENSE "$DEST/WDTT-Plus-LICENSE"
printf '%s\n' 'https://github.com/Ivan4537/WDTT-Plus' "Revision: $WDTT_PLUS_REVISION" > "$DEST/WDTT-Plus-SOURCE"
tar -czf "$DEST/wdttplus-source.tar.gz" --exclude=.git --exclude=.idea -C .. .
popd

#### Go: core ####
source script/build_openflux.sh
source script/build_olcrtc.sh
pushd core
pushd gen
protoc -I . --go_out=. --go-grpc_out=. libcore.proto
popd
if [[ "$GOOS" == "windows" ]] && ! $IS_LEGACY; then
  # The lib module ships the DLL, so it is always the binding's generation.
  CRONET_LIB=github.com/sagernet/cronet-go/lib/windows_$GOARCH
  go mod download $CRONET_LIB
  install -m 644 "$(go list -m -f '{{.Dir}}' $CRONET_LIB)/libcronet.dll" $DEST/
fi
if [[ "$GOOS" == "darwin" ]]; then
  # cgo cannot detect a binding/lib ABI skew, so compare the headers the darwin lib was built against.
  CRONET_LIB=github.com/sagernet/cronet-go/lib/darwin_$GOARCH
  go mod download github.com/sagernet/cronet-go $CRONET_LIB
  CRONET_LIB_INCLUDE="$(go list -m -f '{{.Dir}}' $CRONET_LIB)/include"
  for header in "$(go list -m -f '{{.Dir}}' github.com/sagernet/cronet-go)"/include/*.h; do
    cmp -s "$header" "$CRONET_LIB_INCLUDE/${header##*/}" || { echo "cronet-go binding and $CRONET_LIB differ in ${header##*/}" >&2; exit 1; }
  done
fi
VERSION_SINGBOX=$(go list -m -f '{{.Version}}' github.com/sagernet/sing-box)
$GOCMD build -v -o "$DEST/$CORE_NAME$([[ "$GOOS" == "windows" ]] && echo ".exe")" -trimpath -ldflags "-w -s -X 'github.com/sagernet/sing-box/constant.Version=${VERSION_SINGBOX}' -X 'main.coreBrand=${CORE_BRAND}' -X 'internal/godebug.defaultGODEBUG=multipathtcp=0' -checklinkname=0" -tags "$TAGS"
popd

#!/usr/bin/env bash
set -euo pipefail
export GOOS=linux GOARCH=amd64
DEST="$PWD/openflux-test-bin"
mkdir -p "$DEST"
GOCMD=go
source script/build_openflux.sh
(cd openflux-source && go test -race ./share ./socks5 ./transport ./tunnel)
python3 script/test_openflux.py "$DEST/openflux"

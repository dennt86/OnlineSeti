#!/bin/bash
set -e

rm -rf $DEST
mkdir -p $DEST

#### copy exe ####
if [[ "${ONLINESETI_EDITION:-0}" == "1" ]]; then
  cp "$GITHUB_WORKSPACE/build/OnlineSetiVPN.exe" "$DEST"
  cp "$GITHUB_WORKSPACE/build/OnlineSetiVPN.pdb" "$DEST" || true
else
  cp "$GITHUB_WORKSPACE/build/qThrone.exe" "$DEST"
  cp "$GITHUB_WORKSPACE/build/qThrone.pdb" "$DEST" || true
fi

source "$(dirname "$0")/extract_core_artifact.sh"

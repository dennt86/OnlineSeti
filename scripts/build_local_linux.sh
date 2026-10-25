#!/usr/bin/env bash
set -euo pipefail
SOURCE=$(cd "$(dirname "$0")/.." && pwd)
export SOURCE
WORK="$HOME/onlineseti-build"
export WORK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/onlineseti-toolchain/android"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$HOME/onlineseti-toolchain/go/bin:$JAVA_HOME/bin:$HOME/go/bin:$PATH"
python3 - <<'PY'
import os,shutil,pathlib
src=pathlib.Path(os.environ['SOURCE']);work=pathlib.Path(os.environ['WORK'])
def copy_changed(source, dest):
    source=pathlib.Path(source);dest=pathlib.Path(dest)
    if dest.exists() and source.stat().st_size == dest.stat().st_size and source.stat().st_mtime_ns == dest.stat().st_mtime_ns:
        return str(dest)
    return shutil.copy2(source,dest)
for source,dest in [(src,work/'app'),(src.parent/'onlineseti-core',work/'core')]:
    shutil.copytree(source,dest,dirs_exist_ok=True,copy_function=copy_changed,ignore=shutil.ignore_patterns('.git','.gradle','build','artifacts','local.properties','__pycache__'))
(work/'app/deployment/carriers').mkdir(parents=True,exist_ok=True)
for abi in ['arm64-v8a','armeabi-v7a','x86_64']:
    (work/'app/deployment/carriers'/abi/'libcsqtt_transport.so').unlink(missing_ok=True)
    (work/'app/app/src/main/assets/carrier-licenses'/abi/'CSQTT-LICENSE').unlink(missing_ok=True)
(work/'app/local.properties').write_text('sdk.dir='+os.environ['ANDROID_HOME']+'\n')
for name in ['gradlew']:
    p=work/'app'/name;p.write_bytes(p.read_bytes().replace(b'\r\n',b'\n'));p.chmod(0o755)
PY
cd "$WORK/app"
python3 scripts/fetch_local_assets.py
mkdir -p app/src/main/assets/schema
python3 scripts/prepare_mobile_core.py "$WORK/core/core"
python3 scripts/patch_mobile_trusttunnel.py "$WORK/core/core"
python3 - <<'PY'
import os,pathlib
p=pathlib.Path(os.environ['WORK'])/'core/script/build_android.sh'
s=p.read_text().replace('ThroneCore.aar','qThroneCore.aar').replace('-javapkg=io.throneproj','-javapkg=app.qthrone.core').replace('-libname=throne','-libname=qthrone')
p.write_text(s)
PY
if [ ! -s app/libs/qThroneCore.aar ]; then
    (cd "$WORK/core" && DEST="$WORK/app/app/libs" bash script/build_android.sh)
fi
(cd "$WORK/core/core" && CGO_ENABLED=0 go run -trimpath -ldflags=-checklinkname=0 -tags 'with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_openvpn,with_openconnect,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_purego' ./cmd/schemagen -o "$WORK/app/app/src/main/assets/schema/sing-box.json")
./gradlew :app:testOssDebugUnitTest :app:assembleOssDebug --console=plain
mkdir -p "$SOURCE/artifacts"
cp app/build/outputs/apk/oss/debug/*.apk "$SOURCE/artifacts/"
printf 'ONLINESETI_APK_READY\n'

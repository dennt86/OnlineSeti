#!/usr/bin/env bash
set -euo pipefail
TOOLS="$HOME/onlineseti-toolchain"
mkdir -p "$TOOLS"
export TOOLS
python3 - <<'PY'
import hashlib,json,os,pathlib,urllib.request,tarfile,zipfile
p=pathlib.Path(os.environ['TOOLS'])
if not (p/'go/bin/go').exists():
    releases=json.load(urllib.request.urlopen('https://go.dev/dl/?mode=json&include=all',timeout=60))
    release=next(r for r in releases if r['version']=='go1.27.0')
    file=next(f for f in release['files'] if f['os']=='linux' and f['arch']=='amd64' and f['kind']=='archive')
    archive=p/file['filename']
    urllib.request.urlretrieve('https://go.dev/dl/'+file['filename'],archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==file['sha256']
    with tarfile.open(archive) as t:t.extractall(p,filter='data')
if not (p/'android/cmdline-tools/latest/bin/sdkmanager').exists():
    archive=p/'commandlinetools.zip'
    urllib.request.urlretrieve('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip',archive)
    dest=p/'android/cmdline-tools/latest'
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            name=pathlib.PurePosixPath(entry.filename)
            assert name.parts[0]=='cmdline-tools' and '..' not in name.parts
            target=dest.joinpath(*name.parts[1:])
            if entry.is_dir():target.mkdir(parents=True,exist_ok=True)
            else:
                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(entry))
                if 'bin' in name.parts:target.chmod(0o755)
print('TOOL_DOWNLOADS_VERIFIED',flush=True)
PY
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$TOOLS/android"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$TOOLS/go/bin:$JAVA_HOME/bin:$HOME/go/bin:$PATH"
# SDK licences are accepted for this local build environment.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
set -o pipefail
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-35' 'build-tools;35.0.1' 'ndk;28.2.13676358'
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
printf 'LOCAL_LINUX_TOOLCHAIN_READY\n'

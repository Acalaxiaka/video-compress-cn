#!/bin/bash
# 视频压缩 APK 构建：resources.arsc / classes.dex 以 STORED 存放（避免小米 -124）
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/home/box/android}; BT=$SDK/build-tools/35.0.0; AJ=$SDK/platforms/android-35/android.jar
[ -d /home/box/jdk ] && export PATH=/home/box/jdk/bin:$PATH
OUT=${1:-out.apk}; rm -rf build && mkdir -p build/cls build/gen
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml build/res.zip --java build/gen --min-sdk-version 26 --target-sdk-version 35
javac --release 11 -Xlint:-options -cp $AJ -d build/cls $(find src build/gen -name '*.java') 2>&1 | grep -v "^Note:" || true
[ -f build/cls/com/acalaxiaka/videocompress/MainActivity.class ] || { echo "javac failed"; exit 1; }
$BT/d8 --release --lib $AJ --min-api 26 --output build $(find build/cls -name '*.class')
python3 - <<'PY'
import zipfile
src=zipfile.ZipFile('build/base.apk');out=zipfile.ZipFile('build/u.apk','w')
def put(name,data):
    st=name=='resources.arsc' or name.endswith('.dex') or name.endswith('.png')
    zi=zipfile.ZipInfo(name,(1980,1,1,0,0,0));zi.compress_type=zipfile.ZIP_STORED if st else zipfile.ZIP_DEFLATED;out.writestr(zi,data)
for i in src.infolist(): put(i.filename,src.read(i))
put('classes.dex',open('build/classes.dex','rb').read())
out.close()
PY
$BT/zipalign -f -p 4 build/u.apk build/a.apk
$BT/apksigner sign --ks ../android-keystore/release.jks --ks-key-alias videocompress --ks-pass file:../android-keystore/password.txt --out "$OUT" build/a.apk
$BT/apksigner verify "$OUT" && echo "signed OK: $OUT"

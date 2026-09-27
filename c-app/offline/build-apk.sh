#!/usr/bin/env bash
#
# C 端 APK 离线打包 —— 一条命令走完操作手册的六步。
#   docs/technical/C端App离线打包-操作手册.md
#
# 用法：
#   c-app/offline/build-apk.sh                       # 打包 + 强制体检
#   c-app/offline/build-apk.sh --install <设备序列号>   # 再装机自检
#   c-app/offline/build-apk.sh --no-build            # www 已是最新，跳过 npm run build:app
#   OFFLINE_PROJECT=<路径> c-app/offline/build-apk.sh   # 换机器时指工程
#
# ─────────────────────────────────────────────────────────────────────────
# 这条链路**一半在仓库里、一半在仓库外**：DCloud 离线 SDK 工程走网盘分发、
# 不受版本控制，而仓库里的两道闸门（version-alignment.gradle）是靠工程
# build.gradle 末尾那一行 `apply from:` 接进去的。
#
# 所以 ⓪ 先认工程：路径在、应用名对、接线在、keystore 与 JDK 在 —— 缺一样就停，
# **不自作主张去解压 SDK**。ai-shop 那边正是凭「工程没了就重新解压」打出过一个
# 厂商默认工程的包：应用名 HBuilder-SimpleDemo-AS、原生库一个都没有、产物 30M，
# 而 gradle 一路 BUILD SUCCESSFUL —— 用错工程时闸门不是失败，是**根本没跑**。
# ─────────────────────────────────────────────────────────────────────────
#
# ⚠️ 全篇不用 `grep -q` / `head -1` 接在长命令后面：命中即关管道，上游吃到 SIGPIPE，
# 在 `set -o pipefail` 下整条脚本以 141 退出，而输出看起来像正常结束。
# 一律先存进变量再挑行。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # c-app/
cd "$ROOT"

OFFLINE_PROJECT="${OFFLINE_PROJECT:-$HOME/Downloads/powerbank-offline/HBuilder-Integrate-AS}"
PKG="${PB_PKG:-ai.neargo.powerbank}"
APP_LABEL="${PB_APP_LABEL:-搜电}"
KEYSTORE="${PB_KEYSTORE:-$HOME/keys/neargo-release.jks}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
EXPECT_API_BASE="${PB_API_BASE:-https://powerbank.ichain.top}"
OUT_DIR="${OUT_DIR:-$HOME/Downloads}"

DO_BUILD=1
INSTALL_TO=""
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) DO_BUILD=0; shift ;;
        --install)  INSTALL_TO="${2:?--install 要跟设备序列号}"; shift 2 ;;
        -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "不认识的参数：$1（--help 看用法）" >&2; exit 2 ;;
    esac
done

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# manifest.json 带 /* */ 注释（HBuilderX 的方言），标准 json 解析器吃不下，先剥掉
mfget() {
    python3 - "$1" <<'PY'
import json, re, sys
raw = open("src/manifest.json", encoding="utf-8").read()
raw = re.sub(r"/\*.*?\*/", "", raw, flags=re.S)
cur = json.loads(raw)
for k in sys.argv[1].split("."):
    cur = cur.get(k, "") if isinstance(cur, dict) else ""
print(cur if cur is not None else "")
PY
}

APPID="$(mfget appid)"
VERSION_CODE="$(mfget versionCode)"
VERSION_NAME="$(mfget versionName)"

GRADLE_FILE="$OFFLINE_PROJECT/simpleDemo/build.gradle"
STRINGS_FILE="$OFFLINE_PROJECT/simpleDemo/src/main/res/values/strings.xml"
WWW="$OFFLINE_PROJECT/simpleDemo/src/main/assets/apps/$APPID/www"
APK_OUT="$OFFLINE_PROJECT/simpleDemo/build/outputs/apk/release/simpleDemo-release.apk"

# ── ⓪ 认工程 ────────────────────────────────────────────────────────────
say "⓪ 认工程"

[ -n "$APPID" ] || die "c-app/src/manifest.json 的 appid 是空的 —— 还没有 DCloud 应用标识。
    离线包的 www 必须放在 assets/apps/<appid>/ 下，运行时按它找页面代码，编不出也装不上。
    这一步**只能人去办**（要 DCloud 账号）：
      1) dev.dcloud.net.cn 创建应用，拿到 __UNI__XXXXXXX；
      2) 填进 c-app/src/manifest.json 的 appid；
      3) 在同一后台按「包名 $PKG + 证书 SHA1」申请**离线 AppKey**，
         写进离线工程 AndroidManifest 的 dcloud_appkey。
    SHA1 从签名证书取：c-app/offline/gen-release-keystore.sh 会打出来。"

[ -d "$OFFLINE_PROJECT" ] || die "离线工程不在：$OFFLINE_PROJECT
    它是 DCloud 网盘发布的 Android 离线 SDK 里的 HBuilder-Integrate-AS，**不在本仓库**。
    ⚠️ powerbank 要用**自己的一份**，不能跟 ai-shop 共用那份 ——
    那份的 applicationId 是 top.hxmall.bapp、appkey 绑的是它的包名，
    改一份会把另一条线打坏，而两边都不会报错。"

[ -f "$GRADLE_FILE" ]  || die "找不到 $GRADLE_FILE"
[ -f "$STRINGS_FILE" ] || die "找不到 $STRINGS_FILE"

LABEL_LINE=$(awk '/name="app_name"/{print; exit}' "$STRINGS_FILE" || true)
case "$LABEL_LINE" in
    *"$APP_LABEL"*) ok "应用名：$APP_LABEL" ;;
    *) die "应用名不对，这多半是厂商默认工程，拒绝打包。
    读到的：${LABEL_LINE:-（没读到 app_name）}
    期望含：$APP_LABEL
    文件：  $STRINGS_FILE
    默认工程打出来的包：名字叫 HBuilder-SimpleDemo-AS、图标是小机器人、
    原生库一个都没有 —— 而 gradle 会一路 BUILD SUCCESSFUL。" ;;
esac

APPLY_LINE=$(grep -c "c-app/offline/version-alignment\.gradle" "$GRADLE_FILE" || true)
[ "${APPLY_LINE:-0}" -ge 1 ] || die "离线工程少了 apply from 接线。
    把这一行加到 $GRADLE_FILE 末尾：
      apply from: \"$ROOT/offline/version-alignment.gradle\"
    它把「两处 versionCode 对齐」「这份 www 不是 mock 构建」两道闸门接进构建。
    没有它，构建照样成功 —— 坏包要等到真机上点到下单才发现。"
ok "apply from 接线在"

[ -f "$KEYSTORE" ] || die "签名 keystore 不在：$KEYSTORE
    生成：c-app/offline/signing/keystore.properties 填好后跑 c-app/offline/gen-release-keystore.sh
    ⚠️ 别直接借用 ai-shop 的 hxmall-release.jks —— 证书主体会永久显示在证书里，
    而签发后改不了。"
ok "keystore 在"
[ -d "$JDK" ] || die "JDK 不在：$JDK（用 JAVA_HOME 指一个 17）"
ok "JDK：$JDK"

# 编译器版本 ↔ 离线 SDK 版本。对不上时 App 启动会弹「不匹配的版本可能造成应用异常」，
# 而更麻烦的是某些 API 静默不工作。取 @dcloudio/uni-app 版本号尾部的日期与 SDK 目录名比。
DEP_VER=$(python3 -c 'import json;print(json.load(open("package.json"))["dependencies"]["@dcloudio/uni-app"])')
DEP_DATE=$(printf '%s' "$DEP_VER" | sed -E 's/.*-[0-9]{6}([0-9]{8})[0-9]+$/\1/')
SDK_DIR=$(basename "$(cd "$OFFLINE_PROJECT/.." && pwd)")
case "$SDK_DIR" in
    *"$DEP_DATE"*) ok "工具链与离线 SDK 同版（$DEP_DATE）" ;;
    *) die "工具链与离线 SDK 版本对不上，拒绝打包。
    @dcloudio/uni-app：$DEP_VER（$DEP_DATE）
    离线 SDK 目录：    $SDK_DIR
    症状是启动弹「不匹配的版本可能造成应用异常」，部分原生 API 静默失效。
    对齐方法二选一：把 package.json 的 @dcloudio/* 换成与 SDK 同日期的那版并重装，
    或换一份与当前工具链同日期的离线 SDK。" ;;
esac

# ── ① 版本号：以仓库里那份为单一真源 ────────────────────────────────────
# 手册要求两处一起抬，而「要改两处」本身就是漏改的来源。这里让
# c-app/src/manifest.json（**在仓库里、可提交、可 review**）当唯一真源，
# 由脚本同步进仓库外的 gradle。人只改一处，另一处不可能落后。
say "① 版本号 → $VERSION_NAME ($VERSION_CODE)"
[ -n "$VERSION_CODE" ] || die "src/manifest.json 里没有 versionCode"
python3 - "$GRADLE_FILE" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import re, sys
path, code, name = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
s2 = re.sub(r"versionCode\s+\d+", f"versionCode {code}", s, count=1)
s2 = re.sub(r'versionName\s+"[^"]*"', f'versionName "{name}"', s2, count=1)
if s2 == s:
    print("  ! gradle 里的 versionCode/versionName 没匹配上，已跳过同步 —— 构建期闸门会拦")
open(path, "w", encoding="utf-8").write(s2)
PY
ok "已同步进离线工程 build.gradle"

# ── ② 构建 App 资源 ─────────────────────────────────────────────────────
if [ "$DO_BUILD" = 1 ]; then
    say "② npm run build:app（production 模式，读 .env.production）"
    npm run build:app >/dev/null
    ok "产物：dist/build/app"
else
    say "② 跳过构建（--no-build）"
fi

# ── ③ 产物闸门：这份 www 连的是谁 ───────────────────────────────────────
# 在**拷进工程之前**核。gradle 里那道是最后一道保险，不是第一道 ——
# 在这里失败，错误就落在「刚跑的这次构建」上，而不是半小时后的 gradle 日志里。
say "③ 产物闸门"
BI="dist/build/app/pb-build-info.json"
[ -f "$BI" ] || die "没有 $BI —— 产物是老工具链出的（该文件由 vite.config.mts 的 pbBuildInfo 插件产出）。
    没有它就无法判断包连的是 mock 还是真后端：产物是压缩过的，按字符串判定只会得到假绿。"
python3 - "$BI" "$EXPECT_API_BASE" <<'PY'
import json, re, sys
info = json.load(open(sys.argv[1], encoding="utf-8"))
want = sys.argv[2]
bad = []
if info.get("useMock"):
    bad.append("这是 **mock 构建**：界面全对、门店是迪拜那四个假点、登录随便填都能进、\n"
               "     充值立刻到账，而它一个后端请求都不发。\n"
               "     `src/api/index.ts` 的判定是 VITE_USE_MOCK !== \"0\" —— **不写就是 mock**。")
base = info.get("apiBase") or ""
if not base:
    bad.append("后端源为空：离线包里没有同源反代这回事，请求会打到 file:// 上。")
elif re.search(r"localhost|127\.0\.0\.1|10\.0\.2\.2|0\.0\.0\.0", base, re.I):
    bad.append(f"后端源是本地地址 {base}：模拟器上一切正常，真机上每个列表都是「网络异常」。")
elif base != want:
    bad.append(f"后端源是 {base}，预期 {want}（确实要发别处就给 PB_API_BASE=）。")
if not info.get("hasGmapsKey"):
    bad.append("没有 Google Maps key：首页地图降级成占位块，不报错，看着像地图坏了。\n"
               "     key 放 c-app/.env.local 的 VITE_GMAPS_KEY。")
if bad:
    print("产物闸门未通过：")
    for b in bad:
        print("   ✗ " + b)
    sys.exit(1)
print(f"   api={base} · mock=false · maps=on · v{info.get('versionName')}({info.get('versionCode')})")
PY
ok "产物闸门通过"

# ── ④ 换 www ────────────────────────────────────────────────────────────
say "④ 换 www → $WWW"
mkdir -p "$(dirname "$WWW")"
if [ -d "$WWW" ]; then
    rm -rf "$WWW.prev"
    cp -R "$WWW" "$WWW.prev"
fi
rm -rf "$WWW"
cp -R dist/build/app "$WWW"
# 留 .prev 不只是为了回退：它还是「我的改动到底进没进包」的判据 ——
# 产物压缩过、变量名全变了，按源码里的名字去 grep 一定搜不到。
if [ -d "$WWW.prev" ] && cmp -s "$WWW/app-service.js" "$WWW.prev/app-service.js"; then
    printf '  \033[33m!\033[0m app-service.js 与上一版逐字节相同 —— 这次的改动没进产物（改了源码没重新 build？）\n'
fi
ok "已换（上一版留在 $(basename "$WWW").prev）"

# ── ⑤ 打包 ──────────────────────────────────────────────────────────────
say "⑤ gradle assembleRelease"
# 打 release 不打 debug —— debug 包会弹一串 HTML5+ Runtime 调试提示
( cd "$OFFLINE_PROJECT" && JAVA_HOME="$JDK" ./gradlew :simpleDemo:assembleRelease )
[ -f "$APK_OUT" ] || die "gradle 说成功，但产物不在：$APK_OUT"
STAMP="$(date +%m%d-%H%M)"
FINAL="$OUT_DIR/powerbank-c-$VERSION_NAME-$VERSION_CODE-$STAMP.apk"
cp "$APK_OUT" "$FINAL"
ok "APK：$FINAL"

# ── ⑥ 体检 ──────────────────────────────────────────────────────────────
say "⑥ 产物体检"
PREV=$(ls -t "$OUT_DIR"/powerbank-c-*.apk 2>/dev/null | awk 'NR==2{print}' || true)
"$ROOT/offline/verify-apk.sh" "$FINAL" ${PREV:+"$PREV"}

# ── ⑦ 装机（可选）────────────────────────────────────────────────────────
if [ -n "$INSTALL_TO" ]; then
    say "⑦ 装机 $INSTALL_TO"
    adb -s "$INSTALL_TO" install -r "$FINAL"
    adb -s "$INSTALL_TO" shell dumpsys package "$PKG" | awk '/version(Name|Code)/{print "  · " $1}'
    adb -s "$INSTALL_TO" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 3
    PID=$(adb -s "$INSTALL_TO" shell pidof "$PKG" || true)
    [ -n "$PID" ] && ok "进程活着（pid $PID）" || die "启动即退 —— 看 adb logcat -d | grep -i AndroidRuntime"
    # 从**手机这一侧**探后端：本机 curl 验不了这件事（两边走的不是一条网络）
    HEALTH=$(adb -s "$INSTALL_TO" shell "curl -s --max-time 12 $EXPECT_API_BASE/actuator/health" || true)
    case "$HEALTH" in
        *'"status":"UP"'*) ok "手机侧能连后端（$EXPECT_API_BASE）" ;;
        *) printf '  \033[33m!\033[0m 手机侧探后端没拿到 UP：%s\n' "${HEALTH:0:120}" ;;
    esac
fi

echo
echo "完成。下一步：把 c-app/src/manifest.json 的版本号提交进仓库（gradle 工程在仓库外，提交不了）。"

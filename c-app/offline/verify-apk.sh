#!/usr/bin/env bash
#
# 离线包产物体检：拿新打的 APK 和**上一个已知能用的包**逐项对。
#
# 为什么不是「构建成功就行」：这条链路上的坏包，构建全都是成功的 ——
#   · VITE_USE_MOCK 没关 → 包里是 mock，界面全对、一个请求都不发；
#   · 重解压 DCloud SDK → 应用名与图标退回出厂值，装上叫「HBuilder」；
#   · 少一行相机权限 → 扫码页打开、黑屏、写一句「未获得相机权限」，
#     看着像用户拒了授权，其实是没声明（授权框根本不弹，adb grant 也授不上）；
#   · assets 换了但 dex 没换，`unzip -l` 看不出来（库在不在要数 dex 里的类）。
# 每一种都要装到手机上、点到那个功能才会发现，所以体检要在装机之前跑。
#
# ⚠️ 全篇不用 `grep -q` / `head -1` 接在长命令后面：命中即关管道，上游 aapt2/apkanalyzer
# 吃到 SIGPIPE，在 pipefail 下整条脚本以 141 退出，而输出看起来像「跑到第一项就正常结束」。
# 一律先把输出存进变量再挑行。
#
# 用法：
#   c-app/offline/verify-apk.sh <新包.apk> [旧包.apk]
# 不给旧包就只做绝对检查（不做对比）。
set -euo pipefail

NEW=${1:?用法: verify-apk.sh <新包.apk> [旧包.apk]}
OLD=${2:-}
EXPECT_API_BASE="${PB_API_BASE:-https://powerbank.ichain.top}"
APP_LABEL="${PB_APP_LABEL:-搜电}"
CMDLINE="${ANDROID_CMDLINE:-/opt/homebrew/share/android-commandlinetools}"
APKAN="$CMDLINE/cmdline-tools/latest/bin/apkanalyzer"
AAPT=$(ls "$CMDLINE"/build-tools/*/aapt2 | tail -1)
fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=1; }

echo "== 绝对检查（不依赖旧包）"
MANIFEST=$("$APKAN" manifest print "$NEW")
BADGING=$("$AAPT" dump badging "$NEW")

# 1. 这个包连的是谁。**这一条是这个脚本存在的理由。**
#    产物是压缩过的，从 app-service.js 里看不出 mock 有没有关（mock 数据即使没启用
#    也照样打进包里，摇不掉），所以构建时把结论写成了 pb-build-info.json，这里读它。
BI=$(unzip -p "$NEW" 'assets/apps/*/www/pb-build-info.json' 2>/dev/null || true)
if [ -z "$BI" ]; then
    bad "包里没有 www/pb-build-info.json —— 用老工具链打的，无法判断它连的是 mock 还是真后端"
else
    BI_OUT=$(printf '%s' "$BI" | python3 -c '
import json, re, sys
info = json.load(sys.stdin)
want = sys.argv[1]
msgs = []
if info.get("useMock"):
    msgs.append("BAD mock 构建 —— 界面全对，一个后端请求都不发")
base = info.get("apiBase") or ""
if not base:
    msgs.append("BAD 后端源为空 —— 请求会打到 file:// 上")
elif re.search(r"localhost|127\.0\.0\.1|10\.0\.2\.2", base, re.I):
    msgs.append(f"BAD 后端源是本地地址 {base} —— 模拟器正常、真机全是「网络异常」")
elif base != want:
    msgs.append(f"BAD 后端源 {base} ≠ 预期 {want}")
else:
    msgs.append(f"OK  后端源 {base}")
msgs.append(("OK  " if info.get("hasGmapsKey") else "BAD 没有 ") + "Google Maps key"
            + ("" if info.get("hasGmapsKey") else " —— 首页地图会降级成占位块，且不报错"))
print("\n".join(msgs))
' "$EXPECT_API_BASE")
    while IFS= read -r line; do
        case "$line" in
            OK*)  ok "${line#OK  }" ;;
            BAD*) bad "${line#BAD }" ;;
        esac
    done <<< "$BI_OUT"
fi

# 2. 相机权限。扫码要用，而它极容易缺：离线工程的 AndroidManifest 在仓库外，
#    重解压 SDK 会把那行清掉，症状看着完全不像「少了个权限」。
CAM=$(printf '%s\n' "$MANIFEST" | grep -c 'android.permission.CAMERA' || true)
if [ "${CAM:-0}" -ge 1 ]; then
    ok "相机权限已声明（扫码要用）"
else
    bad "manifest 里没有 android.permission.CAMERA —— 扫码页会黑屏并写「未获得相机权限」，
     而那看着像用户拒了授权。把这行粘回离线工程的 AndroidManifest：
       <uses-permission android:name=\"android.permission.CAMERA\" />"
fi

# 2b. 定位权限。首页整页就是「按距离排序的附近网点」，缺了它首页等于废掉一半。
LOC=$(printf '%s\n' "$MANIFEST" | grep -c 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION' || true)
[ "${LOC:-0}" -ge 1 ] && ok "定位权限已声明" || bad "没有定位权限 —— 首页拿不到坐标，列表退化成「按建站顺序」且不报错"

# 3. DCloud 离线 AppKey。它绑「包名 + 证书 SHA1」，错了或缺了**运行时才炸**：
#    启动白屏或弹 appkey 校验失败，编译期一个字都不说。
KEYVAL=$(printf '%s\n' "$MANIFEST" | awk '/dcloud_appkey/{f=1} f&&/android:value=/{print; exit}')
if [ -z "$KEYVAL" ]; then
    bad "manifest 里没有 dcloud_appkey —— 装上去启动白屏，而构建不会报错"
else
    k=$(printf '%s' "$KEYVAL" | sed 's/.*android:value="\([^"]*\)".*/\1/')
    case "$k" in
        ""|*'${'*) bad "dcloud_appkey 是空的或占位符没替换：$KEYVAL" ;;
        # 只报长度与尾号：这份输出会贴进工单，key 不该跟着走
        *) ok "dcloud_appkey 已注入（${#k} 位，尾号 ${k: -4}）" ;;
    esac
fi

# 4. 应用名。重解压后会退回 SDK 自带的名字，而界面上看不出来
label=$(printf '%s\n' "$BADGING" | awk -F"'" '/^application-label:/{print $2; exit}')
[ "$label" = "$APP_LABEL" ] && ok "应用名：$label" || bad "应用名是「$label」，应为「$APP_LABEL」"

# 5. versionCode 两处一致。
#    APK manifest 的那个给 Android 系统判覆盖安装；包内 www/manifest.json 的那个
#    给 DCloud 运行时判要不要重新解压 www。只抬前者 = 静默坏包：
#    装得上、版本号是新的、零报错，而**页面代码走新、页面注册表走旧** ——
#    新增页面 navigateTo 静默不跳，logcat 一个字都没有。
printf '%s\n' "$BADGING" | awk '/^package:/{print "  · " $0; exit}'
apk_vc=$(printf '%s\n' "$BADGING" | awk -F"'" '/^package:/{for(i=1;i<=NF;i++) if($(i)~/versionCode=$/){print $(i+1); exit}}')
www_json=$(unzip -p "$NEW" 'assets/apps/*/www/manifest.json' 2>/dev/null || true)
if [ -z "$www_json" ]; then
    bad "包里找不到 www/manifest.json —— assets 没打进去？"
else
    www_vc=$(printf '%s' "$www_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("version",{}).get("code",""))' 2>/dev/null || true)
    if [ -z "$www_vc" ]; then
        bad "www/manifest.json 里读不到 version.code"
    elif [ "$apk_vc" != "$www_vc" ]; then
        bad "versionCode 两处不一致：APK=$apk_vc，包内 www=$www_vc
       → 装上去系统显示 $apk_vc，跑的却是 $www_vc 那一版的页面代码。
       正常流程不该撞到这条（build-apk.sh 以 src/manifest.json 为单一真源）"
    else
        ok "versionCode 两处一致（$apk_vc）"
    fi
fi

if [ -z "$OLD" ]; then
    echo
    [ $fail = 0 ] && echo "绝对检查通过（没给旧包，未做对比）" || echo "有失败项 —— 别发这个包"
    exit $fail
fi

echo "== 与旧包对比：$(basename "$OLD")"

# 6. 原生库。整族丢失一定看得出
if diff -q <(unzip -l "$OLD" | awk '/\.so$/{print $4}' | sort) \
           <(unzip -l "$NEW" | awk '/\.so$/{print $4}' | sort) >/dev/null; then
    ok ".so 清单一致"
else
    bad ".so 清单变了："
    diff <(unzip -l "$OLD" | awk '/\.so$/{print $4}' | sort) \
         <(unzip -l "$NEW" | awk '/\.so$/{print $4}' | sort) | head -6 || true
fi

# 7. 启动图标。按文件名筛会**恒绿**（release 资源名混淆过，两边都筛出 0 个），
#    所以问 aapt 要 `application: icon=` —— 那是运行时真正用的那一个，再比字节。
#    判据方向也是反的：图标本来就该偶尔变（换 logo），真正该失败的是**取不到图标**。
NEW_IC=$(printf '%s\n' "$BADGING" | grep -oE "icon='[^']*'" | tail -1 | sed "s/icon='//;s/'//" || true)
if [ -z "$NEW_IC" ]; then
    bad "取不到启动图标 —— 这种包装上去图标是系统默认的小机器人"
else
    NEW_IC_MD5=$(unzip -p "$NEW" "$NEW_IC" 2>/dev/null | md5 -q || true)
    OLD_IC=$("$AAPT" dump badging "$OLD" 2>/dev/null | grep -oE "icon='[^']*'" | tail -1 | sed "s/icon='//;s/'//" || true)
    OLD_IC_MD5=$(unzip -p "$OLD" "$OLD_IC" 2>/dev/null | md5 -q || true)
    [ "$NEW_IC_MD5" = "$OLD_IC_MD5" ] \
        && ok "启动图标与旧包一致（$NEW_IC）" \
        || ok "启动图标**变了**：$OLD_IC_MD5 → $NEW_IC_MD5（有意换过就对，没换过要查）"
fi

# 8. dex 里的关键 SDK。**数类，不数文件** —— aar 在不在只有 dex 看得出
DEX_OLD=$("$APKAN" dex packages "$OLD" 2>/dev/null || true)
DEX_NEW=$("$APKAN" dex packages "$NEW" 2>/dev/null || true)
for pkg in io.dcloud; do
    a=$(printf '%s\n' "$DEX_OLD" | grep -c "$pkg" || true)
    b=$(printf '%s\n' "$DEX_NEW" | grep -c "$pkg" || true)
    [ "$a" = "$b" ] && ok "$pkg：$b 项（与旧包同）" || bad "$pkg：旧 $a → 新 $b"
done

echo
[ $fail = 0 ] && echo "全部通过" || echo "有失败项 —— 别发这个包"
exit $fail

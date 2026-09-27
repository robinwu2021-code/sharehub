# C 端 App 离线打包 · 操作手册

状态：**待首次执行**（2026-09-27 建立；仓库内那半条链路已就绪并自验，仓库外那半条要人先办三件事，见 §0）
关联：[TDD-C端App打包方案](TDD-C端App打包方案.md) · [ADR-031](ADR/ADR-031-C端App生产形态为uni离线打包.md)
参照：ai-shop `docs/technical/design/App离线打包-操作手册.md`（同机仓库）

这份文档存**打一个能装上真机的 C 端 APK 要按顺序做的那几件事**。

它存在的理由和 ai-shop 那边一样：这条链路**一半在仓库里、一半在仓库外** ——
DCloud 离线 SDK 工程走网盘分发、不受版本控制，而仓库里的构建期闸门是靠工程
`build.gradle` 末尾那一行 `apply from:` 接进去的。中间「怎么从代码走到 APK」
不写下来，下一个人就得再反推一遍。

> ⚠️ **不要用 `c-app/android/`（Capacitor 壳）打要发出去的包。** 那是开发预览壳，
> 装的是 H5 产物，WebView 里 `uni.scanCode` 不生效 —— **扫码按钮点不动**，
> 而扫码是 C 端唯一的借出入口。它的包名已改成 `ai.neargo.powerbank.preview`，
> 与真包不同名，可以并存。见 [ADR-031](ADR/ADR-031-C端App生产形态为uni离线打包.md)。

---

## 先看这个：整条链路已经有一条命令

```bash
c-app/offline/build-apk.sh                       # 打包 + 强制体检
c-app/offline/build-apk.sh --install <真机序列号>   # 再装机自检
c-app/offline/build-apk.sh --no-build            # www 已是最新，跳过 npm run build:app
```

它把 §1–§6 串起来了，并且**先认工程**（§0）：路径在、应用名是「搜电」、
`apply from:` 接线在、keystore 与 JDK 在、工具链与离线 SDK 同版 —— 缺一样就停，
**不自作主张去解压 SDK**。

立这一道是抄 ai-shop 的教训：那边有人凭「工程被清掉就重新解压」那句话，
把 SDK 压缩包解压到 /tmp 打了一份，结果是**厂商默认工程** —— 应用名
`HBuilder-SimpleDemo-AS`、原生库一个都没有、产物 30M（真包 55M），
而 gradle 一路 BUILD SUCCESSFUL。关键在于：构建期的闸门**是靠工程里那行
`apply from:` 接进来的**，用错工程时它不是失败，是**根本没跑**。

---

## 0. 前置（一次性）

| 东西 | 在哪 | 状态 |
| --- | --- | --- |
| **DCloud appid** | `c-app/src/manifest.json` 的 `appid`（`__UNI__XXXXXXX`） | ⬜ **空的，必须先办**。dev.dcloud.net.cn 创建应用取得。离线包的 www 必须放在 `assets/apps/<appid>/`，没有它编不出也装不上 |
| **离线 AppKey** | 离线工程 `AndroidManifest` 的 `dcloud_appkey` | ⬜ 同一后台按「包名 `ai.neargo.powerbank` + 证书 SHA1」申请。**错了或缺了运行时才炸**（启动白屏），编译期一个字都不说 |
| **签名 keystore** | `~/keys/neargo-release.jks` | ⬜ `c-app/offline/gen-release-keystore.sh` 生成，它会打出 MD5/SHA1/SHA256。**SHA1 就是上一行要用的那个** |
| **离线 SDK 工程** | `~/Downloads/powerbank-offline/HBuilder-Integrate-AS`（可用 `OFFLINE_PROJECT=` 覆盖） | ⬜ powerbank 要**自己的一份**，不能跟 ai-shop 共用 —— 那份的 applicationId 是 `top.hxmall.bapp`、appkey 绑的是它的包名，改一份会把另一条线打坏，而两边都不报错 |
| 工具链版本 | `c-app/package.json` 的 `@dcloudio/*` | ✅ 已对齐到 `3.0.0-5020420260813003`（5.24），与本机 SDK `Android-SDK@5.24.82669_20260813` 同日期 |
| Android 命令行工具 | `/opt/homebrew/share/android-commandlinetools` | ✅ 体检脚本读这个路径（`ANDROID_CMDLINE=` 可覆盖） |
| Google Maps Key | `c-app/.env.local` 的 `VITE_GMAPS_KEY` | ✅ 本机有。⚠️ Android 限制要加「包名 + SHA1」那一对，否则真机上地图空白 |

**这一行 `apply from:` 必须在**（离线工程 `simpleDemo/build.gradle` 末尾）：

```
apply from: "/Users/robin/work/ai/powerbank/c-app/offline/version-alignment.gradle"
```

它把两道构建期闸门接进来：**两处 versionCode 对齐**、**这份 www 不是 mock 构建**。
把逻辑放仓库里、工程里只留一行，是因为**一行的缺失一眼看得出，几十行的逻辑丢了看不出** ——
ai-shop 那边整段写在工程里的注入逻辑，被重解压覆盖后断了六天没人发现。

---

## 1. 抬版本号 —— **只改仓库里那一处**

改 `c-app/src/manifest.json` 的 `versionCode` / `versionName`，
离线工程 gradle 那处由 `build-apk.sh` 同步。

为什么要强调：这两处是**两个来源、两个消费者** ——

| 改哪 | 决定什么 |
| --- | --- |
| 离线工程 `build.gradle` 的 `versionCode` | **APK manifest** 的版本。Android 系统用它判断能不能覆盖安装 |
| `c-app/src/manifest.json` 的 `versionCode` | 流进 `www/manifest.json`。**DCloud 运行时**用它决定要不要重新解压 www |

只抬 gradle 那一处 → **静默坏包**：装得上、系统里版本号是新的、零报错，
而运行时沿用手机上已解压的旧 www。症状极难认：**页面代码走新、页面注册表走旧** ——
老页面的新内容都对，**新增页面 `navigateTo` 静默不跳，logcat 一个字都没有**。
排查这种症状先 `adb uninstall` 重装，再怀疑代码。

脚本把它收成单一真源，所以正常流程撞不到这条；撞到了说明有人手改了其中一处。

---

## 2. 构建 App 资源

```bash
cd c-app && npm run build:app     # production 模式，读 .env.production
```

产物在 `c-app/dist/build/app/`。**不需要 HBuilderX。**

⚠️ **这一步的头号坑是 mock 没关**，powerbank 特有：
`src/api/index.ts` 的判定是 `VITE_USE_MOCK !== "0"` —— **不写就是 mock**。
mock 包的样子是：界面全对、门店是迪拜那四个假点、登录随便填都能进、
充值立刻到账，而它**一个后端请求都不发**，线上挂没挂都看不出来。
`c-app/.env.production` 就是为此存在的（`VITE_USE_MOCK=0` + `VITE_API_BASE`）。

也别为了跑模拟器把 base 改成 `10.0.2.2`/`localhost` 再打包 ——
那种包在模拟器上一切正常，到真机上每个列表都是「网络异常」。

---

## 3. 产物闸门 —— 在拷进工程**之前**核

```bash
cat c-app/dist/build/app/pb-build-info.json
# {"useMock": false, "apiBase": "https://powerbank.ichain.top", "hasGmapsKey": true, ...}
```

这份文件由 `c-app/vite.config.mts` 的 `pbBuildInfo` 插件在构建时写出。

**为什么要有它**：产物是压缩过的，事后想从 `app-service.js` 里看出「mock 关没关」
**不可靠** —— 变量名全变了，而且 mock 数据即使没被启用也照样打进包里（摇不掉），
按字符串判定只会得到假绿。所以把结论在构建时写成文件，让闸门读文件而不是猜代码。

它跟着 `dist/build/app` 一起进 `www/`，所以**装机后的 APK 里也有这一份** ——
`verify-apk.sh` 直接从 apk 里 unzip 出来核，核的是真正要发出去的那个包。
里面不含密钥（地图 key 只记「在不在」）。

---

## 4. 换 www

```bash
OFF=~/Downloads/powerbank-offline/HBuilder-Integrate-AS
W="$OFF/simpleDemo/src/main/assets/apps/<appid>/www"

rm -rf "$W.prev" && cp -R "$W" "$W.prev"      # 留一份可回退的
rm -rf "$W"      && cp -R c-app/dist/build/app "$W"
```

`<appid>` 就是 `c-app/src/manifest.json` 的 `appid`，两边必须一致。

留 `.prev` 不只为回退：**它还是「我的改动到底进没进包」的判据**。
产物压缩过、变量名被改掉，按源码里的名字去 grep 一定搜不到；可行的做法是与上一版比：

```bash
cmp -s "$W/app-service.js" "$W.prev/app-service.js" && echo "逐字节相同 —— 改动没进去"
```

（`build-apk.sh` 会自动做这件事并提醒。）

---

## 5. 打包

```bash
cd "$OFF" && ./gradlew :simpleDemo:assembleRelease
```

产物：`simpleDemo/build/outputs/apk/release/simpleDemo-release.apk`

**打 release 不打 debug** —— debug 包会弹一串 HTML5+ Runtime 调试提示。

构建期的两道闸门在这一步跑（§0 那行 `apply from:` 接进来的）：versionCode 两处对齐、
www 不是 mock 构建且后端源合规。它们挂在 `preBuild` 上，`assemble`/`bundle` 哪条路都绕不过。

---

## 6. 产物体检 —— **构建成功不等于包是好的**

```bash
c-app/offline/verify-apk.sh <新包.apk> <上一个已知能用的包.apk>
```

核这几样，都是**装上手机点到那个功能才会发现**的那一类：

- 包里那份 `pb-build-info.json`：连的是 mock 还是真后端、base 对不对、地图 key 在不在；
- **相机权限**（扫码要用）。缺了的症状看着完全不像少权限：扫码页正常打开、黑屏、
  写一句「未获得相机权限」，像是用户自己拒了授权。实际是**根本没声明** ——
  授权框不会弹，`adb shell pm grant` 也授不上（不能授一个未声明的权限）；
- 定位权限（首页整页就是「按距离排序的附近网点」）；
- `dcloud_appkey` 非空、非占位符（错了启动白屏，编译期不报）；
- 应用名是「搜电」（重解压 SDK 会退回出厂值）；
- 两处 versionCode 一致；
- 与旧包比：`.so` 清单、启动图标、dex 里 `io.dcloud` 的类计数（**数类不数文件** ——
  aar 在不在只有 dex 看得出）。

没给旧包就只做绝对检查。**第一个包没有对照**，所以第一次发之前多留意界面与权限。

---

## 7. 装机与验证

```bash
adb devices -l                                  # 认准真机序列号，别装到模拟器上
adb -s <序列号> install -r <新包.apk>
adb -s <序列号> shell pidof ai.neargo.powerbank   # 进程活着＝没启动即崩
adb -s <序列号> logcat -d -t 200 | grep -iE "FATAL|AndroidRuntime"
```

**从手机这一侧探一次后端** —— 本机 curl 验不了这件事（两边走的不是一条网络）：

```bash
adb -s <序列号> shell 'curl -s --max-time 12 https://powerbank.ichain.top/actuator/health'
# 期望 {"groups":[...],"status":"UP"}
```

界面要点的话得解锁手机，那一步交给人。**第一个真包至少点这四样**：
扫码能拉起相机 · 首页地图有点位 · 登录能收到 OTP · 下单能走到「弹出中」。

> ⚠️ 第四样现在**看着通、其实是编的**：`order/confirm.vue` 的「等弹出」是
> `setTimeout(1000)` 直接宣布成功，与柜机是否真弹出无关。
> 见 [C端App功能清单与优化方案 §3.1](C端App功能清单与优化方案.md)。
> 已定的顺序是**先修 B1 异常路径再对外发包**。

---

## 8. 收尾

- `c-app/src/manifest.json` 的版本号**提交进仓库**（gradle 工程在仓库外，提交不了）。
- 旧包留一份：下次体检要拿它当对照，没有对照的体检只剩绝对检查那几条。

---

## 常见坑速查

| 症状 | 多半是 |
| --- | --- |
| 界面全对但列表是迪拜四个假门店、登录随便填都进 | §2 mock 没关 —— 三道闸门任一条都会拦，除非有人绕过脚本手拷 www |
| 装上了，新页面打不开，版本号却是新的 | §1 只抬了 gradle 那一处，运行时沿用旧 www |
| 真机上每个列表都「网络异常」，模拟器正常 | §2 base 指了 `10.0.2.2`/`localhost` |
| 启动白屏 | `dcloud_appkey` 缺/错，或与「包名+SHA1」对不上 |
| 扫码页黑屏写「未获得相机权限」 | 相机权限没声明（不是用户拒了）—— §6 会拦 |
| 首页地图是灰块 | `VITE_GMAPS_KEY` 没进构建，或 Google 那边没加「包名+SHA1」限制 |
| 装上叫「HBuilder」、图标是小机器人 | 重解压 SDK 覆盖了应用名与图标 —— §0 认工程会拦 |
| 装不上，提示签名冲突 | 机上装的是预览壳？两者已改成不同包名，仍冲突就是同包名的旧包 |
| 弹「不匹配的版本可能造成应用异常」 | 工具链与离线 SDK 版本不一致 —— `build-apk.sh` ⓪ 会拦 |

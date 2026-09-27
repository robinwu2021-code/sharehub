# C 端 App 离线打包 · 操作手册

状态：**已首次打通**（2026-09-27）—— `build-apk.sh` 一条命令出包，体检全绿；
模拟器（Pixel 7 / Android 14）装机：启动正常（AppKey 运行时校验通过，无白屏）、
系统弹出「允许搜电访问位置」、点扫码弹出相机授权且相机被本应用打开（模拟器无预览画面，**取景与识码须真机验**）。
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
| **DCloud appid** | `c-app/src/manifest.json` 的 `appid` | ✅ `__UNI__C1F3262`（应用名「搜电」，所有者深圳市虹选科技） |
| **离线 AppKey** | 未跟踪的 `c-app/offline/signing/keystore.properties` 的 `dcloudAppKey`，由 `offline-config.gradle` 注入 manifest | ✅ 已生成。绑「包名 `ai.neargo.powerbank` + 证书 SHA1」—— **换证书或包名即作废**，且重新生成占一个 Key 名额（每 AppID 前 6 个免费）|
| **签名 keystore** | `~/keys/neargo-release.jks`，口令同在上面那份 properties | ✅ 已生成（有效期 30 年）。**两样都要备份** —— 丢了包名就再也发不出更新 |
| **离线 SDK 工程** | `~/Downloads/powerbank-offline/HBuilder-Integrate-AS`（旁边 `SDK_VERSION` 记着 SDK 版本） | ✅ 自 5.24 SDK 解出的**独立一份**（不与 ai-shop 共用）：应用名、appid 控制文件、权限、临时图标、两行 `apply from:` 已配 |
| 工具链版本 | `c-app/package.json` 的 `@dcloudio/*` | ✅ `3.0.0-5020420260813003`，与 SDK `5.24.82669_20260813` 同日期（`build-apk.sh` ⓪ 会核） |
| Android 命令行工具 | `/opt/homebrew/share/android-commandlinetools` | ✅ |
| Google Maps Key | `c-app/.env.local` 的 `VITE_GMAPS_KEY` | ✅ 本机有。⚠️ Android 限制要加「包名 + SHA1」那一对，否则真机上地图空白 |

**离线工程 manifest 里必须有的一处覆盖**：DCloud 基础库 `lib.5plus.base-release.aar` 自带
`usesCleartextTraffic="true"`，合并进来后**每个离线包默认都开着明文 HTTP**
（2026-09-27 发现：首个正式包就是这样；ai-shop 的包大概率同样如此）。
我们的 `<application>` 上写 `android:usesCleartextTraffic="${PB_CLEARTEXT}"` + `tools:replace="android:usesCleartextTraffic"`
（`<manifest>` 需声明 `xmlns:tools`），值由 `offline-config.gradle` 注入：正式包 false、测试包 true。
重解压 SDK 丢了这两处的话，SDK 的 true 会悄悄合并进来 —— **体检会拦**（正式包明文开着即失败）。
关掉后已装机验证：运行时正常、对线上的 HTTPS 请求正常。

**DCloud 后台填写时的一个坑**：「Android 应用签名 MD5」按 keytool 口径填（冒号分隔大写）会被判
「填写错误」，这一项是可选的（只有一键登录要用），**留空即可提交**。

**已知未完成**：
- 启动图标是临时生成的（主色方块 + 闪电），正式品牌图标到位后替换 `simpleDemo/src/main/res/drawable/icon.png`；
- `uni.openLocation`（唤起导航）在 App 端需要地图模块，当前未集成 —— 点「导航」会走端上的失败提示，不会崩；
- **`targetSdkVersion 35`**（2026-09-27 起；SDK 自带 demo 是 33，重解压工程会退回去）。Google Play 要求 ≥ 35，
  `verify-apk.sh` 会拦。≥ 34 时 `android {}` 里还须有 `packagingOptions { jniLibs { useLegacyPackaging true } }`
  （DCloud 文档；缺了原生库加载失败）。Android 15 模拟器（`pb_souDian35`，API 35）实测：边到边强制没有带来额外遮挡 ——
  顶部是**本来就有**的缺陷（全局 `navigationStyle: custom` 而页面没给状态栏留位，标题压在时间上，刘海机更明显），
  已在 `pb-scaffold` 用 `var(--status-bar-height)` 占位修掉；底部 Tab 栏早已处理 `safe-area-inset-bottom`。

**这一行 `apply from:` 必须在**（离线工程 `simpleDemo/build.gradle` 末尾）：

```
apply from: "/Users/robin/work/ai/powerbank/c-app/offline/offline-config.gradle"
apply from: "/Users/robin/work/ai/powerbank/c-app/offline/version-alignment.gradle"
```

前者注入**包名 / 正式签名 / DCloud AppKey**（口令与 Key 从未跟踪的 properties 读，读不到就让构建失败，
不回落到 SDK 自带的 test.jks —— 那样签出的包装得上、但 AppKey 校验不过，启动白屏）；
后者是两道构建期闸门：**两处 versionCode 对齐**、**这份 www 不是 mock 构建**。
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

## 7½. 模拟器上把借还走一遍（测试包）

正式包连的是 `powerbank.ichain.top`，而线上没有开发模式、短信通道也没接 ——
**在模拟器里登录不进去**，借还一步都测不了。所以有一个**显式的测试包模式**：

```bash
# 1) 起一个开 dev-mode 的本机后端（验证码 000000；出宝回执默认模拟）
#    别复用别的会话在跑的实例；从 target/classes 起，别覆盖它们正在用的 jar
# 2) 打测试包：模拟器里 10.0.2.2 = 宿主机
c-app/offline/build-apk.sh --test-api http://10.0.2.2:8095
```

测试包与正式包的区别，**全部由这一个开关带出，闸门不拆**：
后端源放行本地地址（且必须等于 `--test-api` 给的那个）、`usesCleartextTraffic=true`
（Android 9 起默认禁明文，不开就是一屏「网络异常」；正式包体检会查这一项必须为关）、
产物文件名带 `TEST`、体检只与上一个测试包对比。**测试包不能发给任何人。**

模拟器用专门的 AVD `pb_souDian`（`emulator -avd pb_souDian -port 5560 -no-window …`），
别动 ai-shop 的 `hxmall_b`。GPS 设到迪拜：`adb -s emulator-5560 emu geo fix 55.1386 25.0450`。

### 2026-09-27 首轮实测结论

**走通**：登录（000000）→ 附近网点（真数据、按距离排序）→ 地图（真瓦片 + 我的位置 + 点位）→
点位弹门店卡 → 借 → 出宝中 → 成功 → 使用中详情（计时 + 服务端试算 AED 3.00）→
柜机侧归还 → **详情页自动刷新为已结算 AED 3.00**（与试算一致；明细逐项相加 = 合计）→ 订单列表。

**当场修掉的四处（都是只在原生 App 才暴露的）**：

| 症状 | 根因 |
|---|---|
| 首页地图永远是灰块 | `pb-map` 在逻辑层 `document.createElement` 注入 Maps 脚本 —— 那是按 Capacitor（H5）写的；**原生 App 的逻辑层没有 `document`**。改为 renderjs（视图层执行），H5 / App 同一份 |
| 列表里点「借」没反应 | `pb-button` 派发 click 不带事件对象，父级 `@click.stop` 对 `undefined` 调 `stopPropagation()` 抛错，处理函数一次都不执行 |
| 登录成功却卡在登录页，再点一下 App 退出 | 登录页是 401 守卫 `reLaunch` 出来的栈底，`navigateBack()` 无处可回 → App 里变成「再按一次退出」 |
| 所有整宽按钮向右溢出、贴屏幕边 | `width:100%` + 左右内边距，默认 `content-box`。**H5 截图里其实也在，之前被当成了截图裁切** |

另修体检脚本一处误报：`launchable-activity` 行也带 `icon=''`，取「最后一个 icon」取到了空值。

**第二轮（同日）修掉的**：

| 症状 | 根因 |
|---|---|
| 游客打开 App 直接被踢到登录页 | 找柜接口在 SecurityConfig 放行、控制器里又 `require()` 登录（两处打架 → 403）；首页公告 / 进行中订单对游客 401；会话守卫不分「会话失效」与「本来没登录」。三处都改：找柜 / 门店卡 / 公告对游客开放，游客不调进行中订单，守卫只在原本有会话时才跳登录 |
| 门店卡写「可借 29」，那家店每台柜都「不可用」；从门店卡点「借」进的是借不了的柜 | 门店卡把离线 / 故障 / 停用柜的宝也算进可借（「同一个事实两个来源」第三处，前两处已由 1f690b2 统一）；端上选柜取的是那家店第一台 |
| 时间线比本地时间差 8 小时 | 端上 `new Date()` 把**不带 Z 的 UTC** 当本地时间解。（更正：此前记作「时间线跟着服务器时区、与 rentStartAt 不同口径」是错的 —— 两者同经 `OrderSupport.now()` 写入，同为 UTC） |
| 「导航」先进一页空白的内置位置页 | App / H5 改为直接唤起 Google Maps 导航 URL；小程序仍用微信内置地图 |
| **首页偶发整页空白、搜索没反应**（请求根本没发出去） | `uni.getLocation` 在 App 端可能既不成功也不失败，而进行中的定位 Promise 全局共享 —— 挂住一次，此后所有取定位的地方永远等着。加 5 秒超时；公告 / 进行中订单不再排在定位后面；列表只认最后一次发起的结果（先搜的后回来会盖掉新结果） |
| 输入 `Yas` 搜索，请求里是 `keyword=Y` | 安卓输入法组字期间 v-model 不更新，点键盘搜索键时 confirm 先到。改为取 `e.detail.value` |

**第三轮（同日）修掉的**（新号码登录验证过）：

| 症状 | 根因 |
|---|---|
| 游客点「我的 → 钱包」看到「暂无流水」，读起来像有账户只是没钱 | 第二轮让守卫不再对游客 401 跳登录，入口却没补门禁。「我的」页上要登录的入口（钱包/收藏/优惠券/会员/报告/资料）改为先去登录、登录后回跳 |
| 游客「订单」页签写「暂无订单」 | 同上，接口 401 被当成空列表。游客改为不调接口、显示登录引导卡 |
| 从首页公告条点进来一页空白 | 消息页把公告与站内信绑在一个 `Promise.all`，游客的站内信 401 让公告一起失败；且默认停在「站内信」页签。现在游客不拉站内信，游客与从公告条进来的默认停在「公告」 |
| 首页公告条在英文界面下显示中文 | 公告条直接用 `title`，没按语种取列。与消息页共用 `shared/notice.ts` |
| 门店卡的收藏心形点了没反应 | 首页从没监听 `pb-store-sheet` 的 `favorite` 事件 |
| 新用户资料卡一片空白 / 门店卡有个后面没字的钟 | 没昵称改显示灰色「你的昵称」占位，空手机号不出那一行；没营业时间整行不出 |

**第四轮（同日）**：

| 症状 | 根因 |
|---|---|
| App 一启动就被切到后台（来电/通知/系统页），回来后地图**整个会话都是占位**「Map unavailable」 | 地图看门狗 8 秒没看到绘制就判失败，而 WebView 在后台本来不绘制。改为只在可见时计时；后台时的居中回前台补一次（上一轮「停在市中心、没蓝点」同源） |
| 冷启动定位超时后，坐标到了也没人用 | 超时的 null 被缓存 60 秒、迟到的坐标被丢。改为只缓存拿到的位置，迟到坐标经 `onUserLocation` 通知地图与首页列表 |
| 真柜机报的「出宝成功」到不了订单（潜伏：网关转发未建，现被模拟弹出盖住） | 驱动词表 `EJECT_OK/ITEM_INSERTED` 与字典码 `SLOT_EJECT_OK/RETURN_SN_SEEN` 两套词，入口按原样查字典全当未知码丢。入口译一次（后端 `49355c5`） |

H5 回归（游客首页/公告页签/订单引导/钱包门禁→登录回跳/收藏心形）通过；小程序仅验证编译通过，交互回归待做。

**仍未修**：

- ~~登录用户的手机号不显示~~ 已修（`57b5023`）：登录时存一份真号的**掩码**（V131 `usr_identity.display_mask`，不存可逆密文），「我的」显示 `+971****8151`；老用户下次登录补上。
- ~~收藏切换接口的返回值两边对不上~~ 已修：c-app 契约改为后端实际返的裸 `boolean`；收藏页「取消」借此防住列表过期时把收藏**加回去**（接口是切换不是取消）。
- ~~同一站点的多台柜各占一张卡~~ 已修（`29fad07`，按建议拍板）：附近列表与收藏页一家店一张卡，可借/可还与门店卡同一个数，「借」指向能借的柜里宝最多的那台。
- 模拟器网络偶尔 wifi↔蜂窝抖动，请求已到后端（访问日志 200）但响应迟到 ~30 秒，界面表现为按钮一直转圈 —— **是模拟器问题不是 App**，判断办法：logcat 里看 `NetworkMonitor` 探测失败的时间点是否正好对上。
- 扫码：相机已被本应用打开，但模拟器无取景 —— **识码要真机验**。
- 一次「所有页面都空」的现象在修掉定位超时之前出现过一次，强杀重启后恢复、原样重放未复现；定位挂住能解释首页，解释不了订单页 —— 若再现，先看后端访问日志有没有请求到达。

**测试手段补充**：本机后端加 `--server.tomcat.accesslog.enabled=true` 看 App 实际发了什么请求（上面两处搜索问题都是靠它定位的，界面上两者长得一模一样）；
模拟器默认把软键盘藏起来（当成有实体键盘），`adb shell settings put secure show_ime_with_hard_keyboard 1` 之后才能按真实用户的方式点输入法上的「搜索」键 —— adb 的回车键与之不等价。

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

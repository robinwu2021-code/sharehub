# TDD · C 端 App 打包方案（参照 ai-shop B 端）

> 2026-09-27。对象 `c-app/`（uni-app · Vue3 · App 优先）。
> 参照：ai-shop [`App离线打包-操作手册`](../../../ai-shop/docs/technical/design/App离线打包-操作手册.md)
> 与 `ai-shop/android-shell/README.md`（同机仓库，路径仅作索引）。
> **本文是方案**；§6 的四项已于 2026-09-27 拍板，实现落点见 §4，
> 逐步操作见 [C端App离线打包-操作手册](C端App离线打包-操作手册.md)，形态决策见 [ADR-031](ADR/ADR-031-C端App生产形态为uni离线打包.md)。

---

## 一、形态决策

| | 真包（发给用户 / 上架） | 预览壳（自己看界面） |
|---|---|---|
| 怎么出 | **DCloud uni 离线打包**（Android 离线 SDK 工程 + `npm run build:app` 的 www） | 现有 Capacitor 壳（`c-app/android`，装 H5 产物） |
| 原生能力 | HTML5+ 运行时全在：`uni.scanCode`、定位、推送、原生导航 | **只有 WebView**：扫码点不动、`uni.*` 原生 API 不生效 |
| 包名 / 应用名 | `ai.neargo.powerbank` · 「搜电」 | `ai.neargo.powerbank.preview` · 「搜电(预览)」（**已改**） |
| 改了页面 | 要重打 APK | 发 H5 到 `/c/` 即可，壳不用重装 |

**为什么真包不能是 Capacitor**：C 端唯一的借出入口是扫码（`ports/scan.ts` 走
`uni.scanCode`），而 Capacitor 壳里装的是 H5 产物（`capacitor.config.ts` 的
`webDir: dist/build/h5`），WebView 里 uni 原生插件**不生效** —— 扫码按钮点不动。
定位与推送同理。这与 ai-shop 的结论一致：壳用来看界面，联调扫码/定位/支付必须离线包。

**为什么预览壳的包名现在就得改**：Capacitor 壳的 `applicationId` 当前正是
`ai.neargo.powerbank`，**与将来的真包一模一样**。ai-shop 在这件事上吃过亏并写进了
README：同包名互相顶掉，而且**交付时拿错包不会有任何报错** —— 界面几乎一样，
只是原生能力少一半，等到验扫码/定位才发现，那时已经过了几轮。
→ 预览壳改成 `ai.neargo.powerbank.preview`，应用名加后缀，两个包可以并存。

**不与 ai-shop 共用**：离线工程、证书、DCloud AppKey 三样都要 powerbank 自己的一份。
AppKey 绑「包名 + 证书 SHA1」，共用会把另一条线打坏，而两边都不报错。

## 二、这条链路的坏法（决定闸门放在哪）

ai-shop 那边的事故**构建全都是成功的**，powerbank 还多一种自己的：

| 坏法 | 症状 | 闸门 |
|---|---|---|
| **mock 没关**（powerbank 特有） | 界面全对、门店是迪拜那四个假点、登录随便填都进、充值立刻到账，**一个后端请求都不发** | 构建后 + 打包期 + 产物体检，三处 |
| API base 指了本机 | 模拟器全正常，真机每个列表「网络异常」 | 同上 |
| 两处 versionCode 错开 | 装得上、版本号是新的，**页面代码走新、注册表走旧** → 新页面静默不跳 | 打包期 gradle 闸门 |
| 重解压 SDK 覆盖工程 | 应用名变 HBuilder、图标是小机器人、原生库全丢 | 打包前认工程 + 体检对比旧包 |
| 少一行相机权限 | 扫码页黑屏写「未获得相机权限」，**看着像用户拒了授权** | 体检查 manifest |
| 缺 Google Maps key | 首页地图降级成占位块，**不报错** | 三处闸门 |

`VITE_USE_MOCK` 那条是 powerbank 独有且最危险的：`src/api/index.ts` 的判定是
`!== "0"`，**不写就是 mock**，而在 `.env.production` 落地之前，`npm run build:app`
打出来的就是这种包。

## 三、打包链路（六步）

```
① 认工程      路径/应用名/apply-from 接线/keystore/JDK/工具链版本 —— 缺一样就停，不自动解压 SDK
② 版本号      以 c-app/src/manifest.json 为单一真源，脚本同步进仓库外的 gradle
③ 构建资源    npm run build:app（production → 读 .env.production）
④ 产物闸门    读 dist/build/app/pb-build-info.json：mock 关了没、base 对不对、地图 key 在不在
⑤ 换 www      拷进 assets/apps/<appid>/www，旧的留成 .prev（用来判「改动进没进包」）
⑥ 打包+体检   gradlew assembleRelease（构建期再核一次）→ verify-apk.sh 对着上一个好包逐项体检
   可选 ⑦     adb 装机自检：进程活着 + 从手机侧探一次后端 /actuator/health
```

一条命令：`c-app/offline/build-apk.sh [--install <设备序列号>]`。

**为什么闸门重复三处**：④ 在本地产物上（错误落在刚跑的那次构建上）、
gradle 那道在打包那一刻（`apply from:` 接进去，assemble 绕不过）、
体检那道在**真正要发出去的 APK** 上。ai-shop 的教训是
**只能靠人记得跑的守卫等于没有守卫** —— 它有体检脚本、脚本里有版本号那一节，
仍然发出过两处错开的包，因为那节只打印不比对、且没有任何流程调用它。

## 四、仓库内已就绪（本次已落地，可 review / 可回退）

| 落点 | 做了什么 | 验证 |
|---|---|---|
| `c-app/package.json` | `@dcloudio/*` 从 `…4080420251103001`(4.84) 升到 `…5020420260813003`(5.24)，与本机离线 SDK（`Android-SDK@5.24.82669_20260813`）同版 | `type-check` 零报错；`build:app` / `build:h5` 均通过，编译器版本打印 5.24 |
| `c-app/.env.production` | `VITE_USE_MOCK=0` + `VITE_API_BASE=https://powerbank.ichain.top` | 产物里已烘进该 base，`IS_MOCK` 折叠为 `false` |
| `c-app/vite.config.mts` | 新增 `pbBuildInfo` 插件，产物里写 `pb-build-info.json`（mock/base/地图 key/版本，**不含密钥**） | 负对照：`VITE_USE_MOCK=1` 重打，文件翻成 `useMock:true`、产物里 `unref(true)` |
| `c-app/offline/build-apk.sh` | 六步全串 + ⓪认工程 + ④产物闸门 | 现在跑会**如实停在 ⓪**：appid 为空（见 §5） |
| `c-app/offline/verify-apk.sh` | 产物体检：连的是谁 / 相机 / 定位 / dcloud_appkey / 应用名 / 两处 versionCode / 对比旧包的 .so·图标·dex | 待有 APK 后实测 |
| `c-app/offline/version-alignment.gradle` | 打包期闸门（versionCode 对齐 + 非 mock + base 合规 + 地图 key） | 待接进离线工程后实测 |
| `c-app/offline/gen-release-keystore.sh` + `signing/*.example` | 生成证书并打 MD5/SHA1/SHA256（SHA1 是申请 AppKey 要的那个） | 未执行（要密码） |

**升级 `@dcloudio/*` 的理由**：本机只有 5.24 的离线 SDK，而 c-app 的工具链是 4.84。
版本不匹配的症状是启动弹「不匹配的版本可能造成应用异常」、部分原生 API 静默失效。
两条路里只有「升工具链」在本机可行（换 SDK 要去 DCloud 网盘下另一版）。

## 五、只能人去办的三件（当前的真实阻塞）

1. **DCloud 应用标识 + 离线 AppKey**（`c-app/src/manifest.json` 的 `appid` 现在是空的）
   - dev.dcloud.net.cn 创建应用 → 拿 `__UNI__XXXXXXX` → 填进 manifest；
   - 同一后台按「包名 `ai.neargo.powerbank` + 证书 SHA1」申请**离线 AppKey** →
     写进离线工程 `AndroidManifest` 的 `dcloud_appkey`。
   - 缺 appid 编不出（www 要放在 `assets/apps/<appid>/`）；AppKey 错了**运行时才炸**（启动白屏）。
2. **签名证书**：跑 `c-app/offline/gen-release-keystore.sh`（要填密码与主体）。
   主体签发后不可改，**不要借用 ai-shop 的 `hxmall-release.jks`**（主体写的是深圳虹选）。
3. **离线 SDK 工程副本**：把 DCloud Android 离线 SDK 解一份到
   `~/Downloads/powerbank-offline/`，配应用名/图标/包名/AppKey，并在
   `simpleDemo/build.gradle` 末尾加一行
   `apply from: "<仓库>/c-app/offline/version-alignment.gradle"`。

这三件齐了，`build-apk.sh` 就能一条命令跑完。

## 六、已拍板（2026-09-27）

| # | 问题 | 定的是 |
|---|---|---|
| 1 | 应用名 | **搜电**（预览壳叫「搜电(预览)」）。⚠️ 与中国市场已有的同名充电宝品牌重名，MENA 上架前建议做一次商标检索 —— 包名可以不动，商店名改起来便宜 |
| 2 | 包名 | **`ai.neargo.powerbank`**（真包）· `ai.neargo.powerbank.preview`（预览壳）。上架后永久不可改 |
| 3 | iOS | 本轮不排，方案只覆盖 Android |
| 4 | 与功能缺口的先后 | **先修 B1 异常路径再对外发包**（见 §8） |

## 六·五、重新评估（2026-09-27 下午）：Capacitor 作为备选 —— **裁决：维持原生打包**

> **结论（用户 2026-09-27 拍板）：要用原生打包。** 本节保留为评估记录：
> 对比表与迁移成本估算仍然成立，将来若要摆脱 DCloud 可以直接拿来用。
> 原生路线当日已**首次打通**，见 [操作手册](C端App离线打包-操作手册.md) 的状态行。

### 原评估（下文「建议：B」是当时的建议，未被采纳）

> 起因：用户指出「打包 app 需要 dcloud 平台，非常不友好」，并要求三端（小程序 / H5 / App）同时支持。
> 这一节推翻本文 §一的形态选择，[ADR-031](ADR/ADR-031-C端App生产形态为uni离线打包.md) 需随之修订。

### 我先前的理由，只对了一半

§一 里我否掉 Capacitor 的理由是「壳里 `uni.scanCode` 不生效，而扫码是唯一的借出入口」。
这句话本身是对的，但结论下早了：**扫码不生效是因为端能力没换实现，不是因为壳不行。**
`src/ports/*` 这一层当初就是为换实现建的。

实测本仓库真正用到的 uni **原生** API 只有四处：

| 用处 | 现在 | Capacitor 里换成 | 工作量 |
|---|---|---|---|
| 扫码（借出唯一入口） | `uni.scanCode` | `@capacitor-mlkit/barcode-scanning` | 一个 port 实现 + 权限声明 |
| 定位 | `uni.getLocation` | **零改动** —— `ports/location.ts` 的 H5 分支本来就走 `navigator.geolocation`，WebView 里可用 | 0 |
| 唤起导航 | `uni.openLocation` | 开 `geo:` / Google Maps URL | 几行 |
| 报障选图 | `uni.chooseImage` | `@capacitor/camera` | 一个 port 实现（且这功能**目前本来就是摆设**，后端无附件字段） |
| 推送（尚未实现） | UniPush | `@capacitor/push-notifications` + FCM/APNs | 与走 uni 一样要从零接 |

小程序与 H5 **完全不受影响**：仍由 uni 编译，Capacitor 只接管「把 H5 产物装成 App」这一段。

### 三条路对比

| | A. uni 离线打包（本文原方案） | **B. Capacitor 壳（建议）** | C. HBuilderX 云端打包 |
|---|---|---|---|
| 平台账号 | DCloud appid + **离线 AppKey**（绑「包名+SHA1」，改一次占一个名额，每 AppID 前 6 个免费） | **不需要** | DCloud 账号，且排队/付费 |
| 构建工程在哪 | **仓库外**（网盘发的 SDK 解压出来），闸门靠一行 `apply from:` 接进去，重解压就没了 | 仓库内（`c-app/android`，已存在） | 黑盒 |
| 构建命令 | 本机 Gradle + 手工换 www | 标准 `cap sync` + Gradle / Xcode | 网页点按钮 |
| CI | 难：工程不在仓库、SDK 不可分发 | 容易 | 有 API，受额度限制 |
| 渲染 | WebView（vue 页面本来就是 WebView；nvue 才是原生） | WebView | 同 A |
| iOS | 同样要离线 SDK + 证书 | 标准 Xcode 工程 | 同 A |
| 原生能力 | uni 全家桶 + DCloud 插件市场 | Capacitor 插件（官方 + 社区），缺的自己写 | 同 A |

### 建议：B

代价对比很清楚 —— B 的迁移成本是**四个 port 实现**（其中一个是 0），
A 的成本是**长期的**：一个平台账号、一份不在版本控制里的工程、一套只有本机能跑的构建、
以及「改包名或 SHA1 就占一个 Key 名额」这种与工程无关的约束。

### 风险（要认下来的）

1. **Apple Pay 在 WKWebView 里不可用**（需原生 PassKit 或 Safari）。若 nearpay 只提供原生 SDK，
   A 与 B 都得写原生桥 —— 但 B 写的是标准 Capacitor plugin，A 写的是 uni 原生插件（更小众、更难招人）。
   **这条与选哪条路无关，但要在接支付前排期。**
2. 用不了 DCloud 插件市场。目前一个都没用。
3. 已落地的 `offline/` 那套要改写。**思路可整体复用**：产物自述 `pb-build-info.json`、
   版本号单一真源、打包期闸门、对着旧包的产物体检 —— 换成 Capacitor 的 Gradle 工程后逐条搬。
4. 蓝牙近场（C-BT-*，P2）：`@capacitor-community/bluetooth-le`，可行。

### 已经做了、且两条路都用得上的

- **签名证书已生成**（`~/keys/neargo-release.jks`，口令在未跟踪的 `offline/signing/keystore.properties`，
  两者都要备份 —— 丢了包名 `ai.neargo.powerbank` 就再也发不出更新）。
  SHA1 `FE:2B:…:CF:9A` / SHA256 `41:AE:…:4C:7D`。任何 Android 正式包都要它。
- DCloud 应用已建（`__UNI__C1F3262`，应用名「搜电」），「各平台信息」表单已按上面的包名与指纹**填好待提交**
  —— 走 B 的话这一步不需要，留着不提交也无妨。

## 七、发布纪律（写进脚本，不靠人记）

- 版本号**只改** `c-app/src/manifest.json`，其余由脚本同步；改完**提交进仓库**（gradle 工程在仓库外，提交不了）。
- 旧包留一份：体检要拿它当对照，没有对照只剩三条绝对检查。
- 每次装机后从**手机那一侧**探一次后端 —— 本机 curl 验不了这件事（两边走的不是一条网络）。
- 预览壳与真包**永远不同包名**，交付前认包名再发。

## 八、不做 / 边界

- 不把新的原生 SDK 写进 Capacitor 预览壳：写进去的集成不会跟着上架，到时要在离线包里重做一遍。
- 本方案不解决 [C端App功能清单与优化方案](C端App功能清单与优化方案.md) §3 的 P0 缺口
  （等弹出是假的、使用中不计时、欠费无补缴入口）。**它们比打包更影响真机体验** ——
  打出来的第一个真包就会撞上：扫码终于能用了，而弹出结果仍然是 `setTimeout` 编的。
  建议顺序：B1 异常路径 → 首个真包，或两条并行，但别在 B1 之前对外发包。

## 九、验收

- [ ] `build-apk.sh` 一条命令出包，⓪–⑥ 全绿
- [ ] `verify-apk.sh` 对着上一个包全绿（首个包只有绝对检查）
- [ ] 真机：扫码能拉起相机、首页地图有点位、登录能收到 OTP、下单能到「弹出中」
- [ ] 负对照：手动把 `.env.production` 改成 mock 重打一次，**闸门必须拦下来**

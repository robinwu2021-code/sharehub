# ADR-031 C 端 App 生产形态 —— uni 离线打包，Capacitor 壳降为预览

状态：**已接受**（2026-09-27，依据用户「c 端 app 打包成 app，参照 ai-shop b 端 app 的方案」与当日三项裁决）
· 细化 [ADR-005](./ADR-005-C端载体与MENA支付.md) 的「App 优先（P0）」—— 那份定了**载体**，没定**产物怎么出**
· 方案与手册：[TDD-C端App打包方案](../TDD-C端App打包方案.md) · [C端App离线打包-操作手册](../C端App离线打包-操作手册.md)
· 对标 ai-shop ADR-018（App 生产形态与推送通道）

---

## 背景

`c-app/` 是 uni-app 工程，但仓库里已经有一个 **Capacitor 壳**（`c-app/android/`，
`webDir: dist/build/h5`）。它能把 H5 产物装成 APK，界面看着是完整的，
于是很容易被当成「C 端 App 已经能打包了」。

但 Capacitor 壳里跑的是 WebView，**uni 的原生插件不生效**：

- `ports/scan.ts` 走 `uni.scanCode` —— **扫码按钮点不动**，而扫码是 C 端唯一的借出入口；
- 定位、推送同理（推送通道本身也还没接）。

也就是说：这个壳打出来的包，**看得见的部分都对，唯独借不了电**。

## 决策

1. **真包（发给用户 / 上架）走 DCloud uni 离线打包**：Android 离线 SDK 工程 +
   `npm run build:app` 的 www，包名 `ai.neargo.powerbank`，应用名「搜电」。
2. **Capacitor 壳降为开发预览壳**，包名改为 `ai.neargo.powerbank.preview`、
   应用名「搜电(预览)」，与真包**永不同名**，可并存。
3. **powerbank 不与 ai-shop 共用**离线工程、签名证书、DCloud AppKey 三样中的任何一样。
4. **闸门进仓库、逻辑不进离线工程**：离线工程只留一行 `apply from:`，
   指向 `c-app/offline/version-alignment.gradle`。
5. 工具链与离线 SDK **必须同版**：`@dcloudio/*` 已升到 `3.0.0-5020420260813003`(5.24)，
   与本机 `Android-SDK@5.24.82669_20260813` 对齐。

## 理由

**为什么预览壳现在就得改包名。** 它当前的 `applicationId` 正是 `ai.neargo.powerbank` ——
与真包一模一样。ai-shop 那边两者同名，写进了 README 的结论是：
互相顶掉，而且**交付时拿错包不会有任何报错** —— 界面几乎一样，只是原生能力少一半，
等到验扫码/定位/支付时才发现，而那时已经过了几轮。这是一次**零成本的改名**换掉
一整类难以归因的事故。

**为什么不共用 ai-shop 的那一份。** 离线 AppKey 绑「包名 + 证书 SHA1」，
证书主体签发后不可改（那张写的是深圳虹选）。共用会把另一条线打坏，而两边都不报错。

**为什么闸门要放三处**（本地产物 / 打包期 gradle / 产物体检）。这条链路的坏法
有个共同形状：**构建全都是成功的**。powerbank 还多一种 ai-shop 没有的 ——
`src/api/index.ts` 的判定是 `VITE_USE_MOCK !== "0"`，**不写就是 mock**，
于是 `npm run build:app` 的默认产物是一个「界面全对、一个请求都不发」的假 App。
ai-shop 的教训更直接：**一个只能靠人记得跑的守卫等于没有守卫** ——
它有体检脚本、脚本里有版本号那一节，仍然发出过两处 versionCode 错开的包，
因为那一节只打印不比对，且没有任何流程调用它。

## 备选与否决

- **继续用 Capacitor 打真包** → 否决：扫码/定位/推送都拿不到，C 端主流程不成立。
- **改用 Capacitor 原生插件（`@capacitor/barcode-scanner` 等）逐个补** → 否决：
  等于把 uni 的原生能力在另一套体系里重做一遍，且 `ports/*` 要维护两套端实现。
- **用 HBuilderX 云端打包** → 未采纳（可作应急）：云端打包不进 CI、不留产物体检的入口，
  且这条链路上真正伤人的是「构建成功但包是假的」，云打包同样拦不住。
- **共用 ai-shop 的离线工程与证书** → 否决，见上。

## 影响

- 新增 `c-app/offline/`（build-apk.sh / verify-apk.sh / version-alignment.gradle /
  gen-release-keystore.sh），新增 `c-app/.env.production`，
  `vite.config.mts` 增 `pbBuildInfo` 插件（产物自述文件 `pb-build-info.json`）。
- `c-app/android/`（Capacitor）保留，但**只用于看界面**；
  别把新的原生 SDK 写进去 —— 写进去的集成不会跟着上架，到时要在离线包里重做一遍。
- 阻塞项（只能人办）：DCloud appid + 离线 AppKey、签名 keystore、离线 SDK 工程副本。
- 发版纪律：版本号单一真源在 `c-app/src/manifest.json`，改完提交进仓库。

## 未决

- **iOS**：本 ADR 只覆盖 Android；iOS 同为离线打包（需 Apple 开发者账号 + Xcode），未排期。
- **分发渠道**：首版内部 APK 直发；上商店要补隐私政策链接、数据安全表单、阿语商店文案。
- **品牌「搜电」**：与中国市场已有的同名充电宝品牌重名，MENA 上架前建议做一次商标检索。

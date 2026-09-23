# ADR-023 平台代号保留 `sharehub`（不用 RentOps，也不改 share）

状态：**已决**（2026-09-23，用户定：「先保留 sharehub，share 容易和 shared 混淆」）
· 维持 [ADR-014](./ADR-014-平台更名ShareHub与设备类型抽象.md) 的平台定名
· 库名维持 [v4/03](../v4/03-数据库.md) 的 `sharehub_*`（`sharehub_core` · `sharehub_auth` · `sharehub_pii` · `sharehub_pay` · `sharehub_gw` · `sharehub_job`）

## 背景

- 「ShareHub」对外很拥挤（Google Play 上有 Pega 的同名 App、尼泊尔有同名股票应用、Crunchbase 有同名公司），用户问能否改为「RentOps」，或在现有名字上弱化「ops」；
- 本 ADR 初稿曾建议把代号改为 `share`（与兄弟项目 ai-shop 的 `shop` 对称）。

## 决策

| 层 | 用在哪 | 决定 |
|---|---|---|
| **平台代号** | 仓库、Java 包、Maven、库名、配置前缀、进程名 | **保留 `sharehub`**：`ai.neargo.sharehub` · `sharehub-*` · `sharehub_*` · `sharehub.*` · `SHAREHUB_*` |
| **运营后台名** | ops-web 标题、B 端文档 | 「〈品牌〉运营台」；「Ops」只用在这里 |
| **C 端品牌** | App / 小程序名称、应用商店、域名 | 单独选定：需做阿联酋 / 沙特商标检索、应用商店重名检查、域名、阿语发音（阿语无 P 音）检查；不用 Rent / Ops / Hub |

## 否决

| 方案 | 理由 |
|---|---|
| **RentOps** | ① 已被两家物业管理 SaaS 使用（rentops.io · rentops.app），且在相邻的「租赁运营」领域；② 「Rent」只描述借还型（充电宝），与就地使用型设备（充电桩、按摩椅、储物柜）冲突，而命名卡口正在把 `rent` 从平台层清掉（[ADR-021](./ADR-021-通用设备命名与使用形态.md)）；③ 「Ops」在代码里已有三种含义（`ops-web` 运营后台 · `sharehub-svc-ops` 告警工单客服 · `/api/ops` 运营侧前缀）；④ 读作内部运维工具，不适合面向消费者 |
| **`share`** | **用户否决**：与 `shared` 容易混淆 —— 代码与文档里「shared」本就常用（`portal.shared` 包、「共享模块 / 共用组件」等），代号再叫 `share` 会让「这是平台名还是『共享的』」反复需要辨认 |
| `nearshare` | 有同名开源传文件工具；包名会变成 `ai.neargo.nearshare`（near 重复） |

## 后果

- 代码与库**不做平台级改名**；v4 各篇以 `sharehub` 为准；
- 同样为了避免「share / shared」歧义，财务里的分润包**不叫 `share`**，叫 `profit`（表前缀仍为 `share_`，[v4/09 §九](../v4/09-后端代码架构.md)）；
- C 端品牌仍待选定，这是与代号无关的独立决定。

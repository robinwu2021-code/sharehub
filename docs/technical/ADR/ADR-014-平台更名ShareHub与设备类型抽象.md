# ADR-014 · 平台更名 ShareHub 与设备类型抽象

状态：**已接受并执行**（决策 2026-07-12 · 更名执行 2026-07-30）

> ✅ **M1/M2/M4 已完成**（2026-07-30）：611 个 Java 文件包路径、3 个 Maven 模块坐标、
> 目录名、启动类、配置命名空间 `sharehub.*`、6 个脚本路径全部迁移；`BUILD SUCCESS` + 104 测试全绿。
> **`powerbank` 作设备类型语义全部保留**（`POWERBANK` 枚举 / `powerbank_no` 列 / `DemoPowerbankDriver`）
> —— 这正是本 ADR「powerbank 由平台名降级为一个 device-type 模块」的落地形态。
> DB schema `pb_core` 与数据库用户名按原决策保留不改。
> **M3 设备类型抽象**已由 [ADR-018](./ADR-018-多设备类型与多供应商的抽象边界.md) 展开并实现。
关联：[ADR-003 硬件接入 driver 插件化](./ADR-003-硬件接入driver插件化.md) · [ADR-001 技术栈与服务粒度](./ADR-001-技术栈与服务粒度.md) · [代码结构](../代码结构.md) · [实现状态总表](../实现状态总表.md)

## 背景（Context）

现名 **powerbank** 暗示"只做共享充电宝"。产品规划要求平台后续承载 **充电桩（EV/设备充电桩）以及其他共享设备**（储物柜、雨伞、换电柜等）。

关键事实：当前代码在 `dev`（柜机/仓位）与 `gw`（厂商/驱动，ADR-003）两层**已是设备无关抽象**——柜机=容器、仓位=货位、`vendor+driver` 是硬件适配层；现挂的 `cd-tech/sd-power/chargenow` 只是一组充电宝厂商驱动，并未写死进领域模型。因此"powerbank"是**命名残留 + 一组驱动**，不是架构约束。

→ 更名是**品牌与命名问题**，非重构问题。目标：名字不再暗示单一设备类型，并显式化"多设备类型"抽象。

## 决策（Decision）

1. **平台定名 `ShareHub`**（neargo ShareHub，中文对外"共享设备运营平台"）。命名内核=共享设备的**柜机/站点网络**，对充电宝/充电桩/储物柜等一视同仁，避免二次改名后悔。备选 StationOS（站点 OS）/ RentFlow（租赁生命周期）未采纳——前者偏硬件、后者偏交易，均不如"共享网络"中性。

2. **Java 根包 `ai.neargo.powerbank` → `ai.neargo.sharehub`**；Maven `powerbank-parent/powerbank-app` → `sharehub-parent/sharehub-app`；仓库目录 `ai/powerbank` → `ai/sharehub`。

3. **设备类型抽象显式化**：引入 `device_type`（POWERBANK / EV_PILE / LOCKER…）为一等概念。
   - `dev`（柜机/仓位）、`gw`（厂商/驱动）保持设备无关；`Cabinet`/`Vendor` 增加 `deviceType` 维度。
   - 设备特有逻辑归入 `device/<type>` 子包：`ai.neargo.sharehub.device.powerbank`、将来 `…device.evpile`。**powerbank 由"平台名"降级为"一个 device-type 模块"**——命名本身即表达多设备。

4. **DB schema `pb_core`**：内部标识，改名收益低、风险有（连接串/迁移脚本/两条并行会话）。**本 ADR 不改**，保留 `pb_core`（或未来大版本一并迁 `sharehub_core`）。表名前缀（`loc_/dev_/wo_/agt_/usr_`）本就设备无关，无需动。

## 命名原则（沉淀，防再次跑偏）

平台层命名**不得含 power/charge/单一设备语义**；体现 共享 + 设备 + 运营；中英双可用（MENA/全球）；契合 `ai.neargo.*`。任何具体设备语义只出现在 `device.<type>` 与驱动层。

## 迁移方案（Migration）— 单次协调式切换，暂不即刻执行

**为何不即刻执行**：`auth/`、`config/` 包正由并行「权限对话」活跃编辑；仓库级包移动会与其在途改动冲突（移动文件→合并撞车），且会打断当前绿色构建。故**记录决策为先，迁移作为一次原子切换**，择两条会话空档执行（或用户显式放行）。

分步（执行时）：
- [ ] M1 包移动：`ai.neargo.powerbank` → `ai.neargo.sharehub`（IDE 重构/`git mv` + 包声明与 import 批量替换）；`@MapperScan`/`@SpringBootApplication` 扫描基包同步。
- [ ] M2 Maven：artifactId、`<name>`、目录 `powerbank-app`→`sharehub-app`；`PowerbankApplication`→`SharehubApplication`。
- [ ] M3 设备类型：`dev` 实体/DTO 加 `deviceType`（默认 POWERBANK 向后兼容）；新建 `device/powerbank` 归置充电宝特有项（充电宝驱动、仓位电量语义）。
- [ ] M4 前端与配置：`ops-web`/`cend` 内 powerbank 标识、包名、`README`；DB 连接串保持 `pb_core`。
- [ ] M5 文档与记忆：`docs/` 全量、记忆文件 `powerbank-*`（保留别名指向，避免断链）。
- [ ] M6 验证：全量 26+ 测试转绿；`ops-web`/`cend` 联调回归。

**回退**：M1–M2 为纯机械重命名，`git revert` 即回退；M3 设备类型加列向后兼容（默认值），不破坏既有数据。

## 影响（Consequences）

- 正面：品牌与代码表达"多设备共享运营"；新增充电桩=加 `device_type` + 一套驱动，零骨架改动；命名不再有改名后悔。
- 成本：一次面广但机械的重命名；需与并行会话协调时机。
- 兼容：DB schema/表名不变；设备类型加列带默认值，存量数据无损。

---
确认记录：2026-07-12 用户选定 **ShareHub 共享网络** 方向（四选一）。迁移执行时机待用户放行。

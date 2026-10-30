# 运营端功能矩阵与设备生命周期开发任务

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按新版菜单矩阵补齐真实业务闭环，并完成设备全生命周期分角色验收。

**Architecture:** 保留当前Web、服务端领域与独立库存边界；先核实已有能力再补开发。仓内数量只由受控库存单据过账，设备仓外生命周期不重复增减仓内库存；跨系统调用采用持久化操作、稳定幂等键和可恢复步骤。

**Tech Stack:** Java21、Spring、MyBatis／MySQL、Next.js／React、Vitest、真实库存服务与OpsKit条件接入。

**Spec:** [多级菜单与实现状态矩阵](../../requirements/运营端功能矩阵-多级菜单与实现状态.md)；[设备生命周期角色菜单对照](../../requirements/设备生命周期-角色菜单功能对照.md)。

## Global Constraints

- 用户确认：出库必须选择商家；安装按商家选择已出库的待安装设备；前端筛选与服务端提交校验同时执行。
- 原矩阵186节点不是186个独立开发项；待验收／待核实先审计，不重复开发。14个CHK工作包覆盖所有节点，40个DEV任务处理已确认差距、设计和交付。
- Zoho一期按ADR-032只预留；第三方费用按C15待确认，不编造本期必交付。通知短信沿当前一期mock决定，STUB不伪装发送成功。
- LOCAL与SERVICE余额分开；SERVICE物料和盘点只在条件满足时开放；业务操作不执行中切换引擎。
- 每任务从待办开始，未在本清单声称已开发完成；已有本地V211命名改动属于待复核成果，DEV-37复用，不重做。
- 本文件是开发工作分解与验收清单。标为先设计／核查的任务在补齐契约后再写实施步骤；不凭未知实现捏造接口签名。

## Review Focus

1. 跨商家设备号、批量混商家及直调API：服务端拒绝，不能只过滤下拉。
2. 远程过账成功而响应丢失／本地失败：查原结果、同键重试，数量只变一次。
3. 实收80但合格70：实物80、待检10、可用70，不把质检放行再当收货。
4. 同人提交和复核、仓库／商家越权、只有读取权限导出：明确拒绝。
5. 修复、撤机、迁点与重新投放：正确分支、重新复检和历史归属，不直接改状态冒充闭环。

## 一 优先级与实施顺序

| 里程碑 | 交付闭环 | 任务 |
|---|---|---|
| M0 验证与授权基线 | 可构建、可测，逐功能核实，角色边界可验证 | DEV-01/02；CHK-01—14按模块进入开发前完成 |
| M1 库存正确性与商家部署 | 修调拨、恢复；商家出库→签收→候选→安装→上线 | DEV-03—07 |
| M2 采购与库存 | 采购→实收→质检→可用；物料、盘点 | DEV-08—13 |
| M3 设备维护与退出 | 停用、维修、撤机退库、复检、处置及履历报表 | DEV-14—19；DEV-20/21真实设备链路 |
| M4 运营与资金 | 通知、投诉、退款、分润、付款、财务与票据 | DEV-22—29；DEV-24资金正确性应提前推进 |
| M5 其余功能与角色体验 | 会员、版本、流程、导出、集成、门户及工作台 | DEV-30—36；只对核实的差距实施 |
| M6 上线验收 | 菜单落地、全生命周期、报表与发布 | DEV-37—40 |

依赖为业务前置，不表示需要同时开发全部任务。推荐先交付M0+M1，采购与设备链路分别完成后在完整UAT汇合。外部能力不可用时保留依赖状态，不用mock验收替代。

## 二 模块核查任务

| 任务 | 模块与范围 | 验收产物 |
|---|---|---|
| CHK-01 | 工作台：卡片、提醒、下钻、待办、审批领取及仓管任务范围 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-02 | 商家管理：入驻、账号、分润、整站归属、经营与退出，不覆盖历史归属 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-03 | 网点管理：CRM、合同、会签、提醒、站点点位与生命周期 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-04 | 设备管理：台账、签收绑定、安装门禁、监控维修、状态及履历 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-05 | 库存管理：主数据、单据、质检、数量／SN、盘点、余额、流水和恢复 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-06 | 销售管理：计费、订单、支付、售后、营销、会员广告，尤其资金与幂等 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-07 | 用户管理：脱敏、注销、风控、白名单、钱包与充值的动作权限和财务结果 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-08 | 运维与客服：告警、客服、工单SLA与巡检；跨域动作和通知真实结果 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-09 | 结算与付款：规则、明细、结算确认、部分多次付款、账户及双人复核 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-10 | 财务管理：对账、凭证、调整、跨期应付与条件网关数据 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-11 | 发票与税务：租赁／商家票据、贷项、主体税率、下载、VAT及不可修改 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-12 | 报表与导出：统一筛选、历史归属、金额币种、数量及权限，勿只核菜单 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-13 | 系统管理：员工权限、主数据、规则、版本发布、通知、流程及恢复 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |
| CHK-14 | 商家门户：会话商家范围、深链／API越权、待安装设备及收益票据 | 逐节点记录Web→API→方法→存储／外部服务→权限→运行证据；已覆盖关闭核查，缺口关联DEV或新增独立任务 |

每个CHK按下面步骤完成：

- [ ] 按附录功能列表核对真实调用与页内操作，核实第三方条件及角色范围。
- [ ] 对待核实项给出存在／部分／不存在的源码和运行证据；不以名字或菜单判断缺失。
- [ ] 对已有功能执行正向、越权和失败场景，补写必要回归；完整覆盖的项不进入重建。
- [ ] 新发现缺口单独编号，关联功能ID、责任模块与验收条件；不得埋在核查结论里。

## 三 开发任务清单

| 编号 | 任务 | 优先级 | 前置任务 | 主责 |
|---|---|---|---|---|
| DEV-01 | 恢复可复现构建与真实服务验证基线 | P0 | 无 | 工程环境＋测试 |
| DEV-02 | 固化角色动作与数据范围并补越权回归 | P0 | DEV-01 | 后端＋Web＋测试 |
| DEV-03 | 收口调拨状态写入并保证库存副作用唯一 | P0 | DEV-01 | 后端＋Web＋测试 |
| DEV-04 | 建立库存跨系统幂等与操作恢复机制 | P0 | DEV-01 | 后端＋Web＋测试 |
| DEV-05 | 完善按商家出库与交付凭证 | P0 | DEV-02, DEV-04 | 后端＋Web＋测试 |
| DEV-06 | 统一按商家选择待安装设备及安装API校验 | P0 | DEV-02, DEV-05 | 后端＋Web＋测试 |
| DEV-07 | 完善安装验收和上线门禁的关联 | P1 | DEV-06 | 后端＋Web＋测试 |
| DEV-08 | 补齐采购台账与采购行 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-09 | 补齐分批收货、采购余额与来源防重 | P1 | DEV-04, DEV-08 | 后端＋Web＋测试 |
| DEV-10 | 补齐缺票期初资产的依据和价值记录 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-11 | 统一实物、待检和可用口径及复检凭证 | P1 | DEV-09, DEV-10 | 后端＋Web＋测试 |
| DEV-12 | 完善物料主数据、领退料和余额保护 | P1 | DEV-04, DEV-02 | 后端＋Web＋测试 |
| DEV-13 | 闭合盘点、差异复核和库存调整 | P1 | DEV-02, DEV-04 | 后端＋Web＋测试 |
| DEV-14 | 设计并实现现场停用与恢复 | P1 | DEV-02, DEV-07 | 后端＋Web＋测试 |
| DEV-15 | 拆分维修路径并加入维修验收 | P1 | DEV-07, DEV-11, DEV-12, DEV-14 | 后端＋Web＋测试 |
| DEV-16 | 闭合撤机、交接、退库和再次部署 | P1 | DEV-03, DEV-04, DEV-11, DEV-14 | 后端＋Web＋测试 |
| DEV-17 | 完善采购退货、报废和遗失处理 | P1 | DEV-02, DEV-04 | 后端＋Web＋测试 |
| DEV-18 | 统一设备履历与历史商家归属 | P1 | DEV-05, DEV-07, DEV-15, DEV-16 | 后端＋Web＋测试 |
| DEV-19 | 补齐君盛库存与资产报表 | P1 | DEV-09, DEV-13, DEV-15, DEV-18 | 后端＋Web＋测试 |
| DEV-20 | 接通真实设备指令与可查询回执 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-21 | 实现设备日志读写闭环 | P1 | DEV-20 | 后端＋Web＋测试 |
| DEV-22 | 接通告警处置与真实通知结果 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-23 | 接通投诉退款和转工单 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-24 | 实现退款执行与财务后续一致性 | P0 | DEV-02 | 后端＋Web＋测试 |
| DEV-25 | 统一商家分润规则、生效与版本 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-26 | 实现商家绩效并修正经营视图 | P2 | DEV-01 | 后端＋Web＋测试 |
| DEV-27 | 完善结算付款与跨期应付 | P1 | DEV-02, DEV-25 | 后端＋Web＋测试 |
| DEV-28 | 完善凭证、调整与对账处理 | P1 | DEV-02, DEV-27 | 后端＋Web＋测试 |
| DEV-29 | 完善租赁票据、结算票据和更正闭环 | P1 | DEV-24, DEV-27 | 后端＋Web＋测试 |
| DEV-30 | 界定会员发卡的支付和权益生效流程 | P2 | DEV-02 | 后端＋Web＋测试 |
| DEV-31 | 补应用版本发布动作 | P2 | DEV-02 | 后端＋Web＋测试 |
| DEV-32 | 完成OpsKit待办审批真实接入与仓管任务 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-33 | 建设统一导出任务及权限审计 | P2 | DEV-02 | 后端＋Web＋测试 |
| DEV-34 | 建设集成状态与受控恢复入口 | P2 | DEV-04, DEV-20, DEV-22, DEV-32 | 后端＋Web＋测试 |
| DEV-35 | 完善商家门户只读经营与资产视图 | P1 | DEV-02, DEV-27, DEV-29 | 后端＋Web＋测试 |
| DEV-36 | 完善角色工作台及实时运营提醒 | P2 | DEV-02, DEV-19, DEV-22, DEV-32 | 后端＋Web＋测试 |
| DEV-37 | 落地新版菜单及全局命名 | P1 | DEV-02 | 后端＋Web＋测试 |
| DEV-38 | 闭合设备全生命周期的分角色UAT | P1 | DEV-06, DEV-07, DEV-09, DEV-11, DEV-13, DEV-15, DEV-16, DEV-17, DEV-18, DEV-19, DEV-20, DEV-21 | 业务验收＋测试＋研发 |
| DEV-39 | 核对所有报表口径及非库存运营功能 | P2 | DEV-02, DEV-19, DEV-27, DEV-29 | 后端＋Web＋测试 |
| DEV-40 | 发布前回归、数据迁移与状态回填 | P1 | DEV-38, DEV-37, DEV-39 | 业务验收＋测试＋研发 |

## 四 逐任务交付与验收

### DEV-01 恢复可复现构建与真实服务验证基线

优先级：P0；前置：无。

功能关联：全模块横向工作；生命周期：横向／非设备领域。

**修改范围／源码锚点：** backend/pom.xml；ops-web/package.json；backend/scripts/inventory-local/README.md。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** JDK21构建通过；定位并修复或重建.next生成类型，保留typecheck前后结果；建立隔离库存服务测试租户，记录LOCAL／SERVICE配置，不接生产账。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-02 固化角色动作与数据范围并补越权回归

优先级：P0；前置：DEV-01。

功能关联：OM11-03、OM11-04、OM11-05、RF-06、LC-04；生命周期：D09、D10、D13。

**修改范围／源码锚点：** `backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/iam/RolePerms.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DevicePoolController.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java`；ops-web/lib/permissions.ts；ops-web/lib/perm-map.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 逐项确定八类角色的查看／执行／复核／导出和仓库／商家范围；服务端拒绝跨商家、跨仓和自审；WAREHOUSE主数据维护与质检独立授权。现有宽通配收敛先给出迁移影响，再实施。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-03 收口调拨状态写入并保证库存副作用唯一

优先级：P0；前置：DEV-01。

功能关联：OM03-09；生命周期：D07、D21。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryTransferServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/TransferOperatorTest.java`；ops-web/components/inventory/transfer.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** save只编辑合法草稿单头，不接受IN_TRANSIT／DONE状态迁移；专用ship／receive同时完成条件更新、设备与库存动作；空单、并发发货和缺件多件签收回归通过。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-04 建立库存跨系统幂等与操作恢复机制

优先级：P0；前置：DEV-01。

功能关联：RF-01、RF-04、CF-028、CF-030、CF-037；生命周期：D04、D16、D22、D24、D25。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/MaterialServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/DisposalServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/InventoryEngineRouter.java`；inv/remote；新增库存操作记录、迁移与恢复服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 远程调用前持久化稳定操作号、请求键及载荷摘要；状态记录不会随失败业务事务丢失。覆盖出库成功入库失败、远端成功响应丢失、本地提交失败和重启续做；同键异请求拒绝；恢复固定原引擎。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-05 完善按商家出库与交付凭证

优先级：P0；前置：DEV-02, DEV-04。

功能关联：CF-029、LC-04；生命周期：D08、D09、D10、D12、D13、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetPoolServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DevicePoolController.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/dto/PoolDtos.java`；ops-web/components/inventory/issued-pool.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DevicePoolTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 出库商家必选、目的站点归属一致；设备在仓、未绑定、质检放行；员工签收与商家安装交付分别记证据；当面交付标明方式。重复提交不重复扣库。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-06 统一按商家选择待安装设备及安装API校验

优先级：P0；前置：DEV-02, DEV-05。

功能关联：LC-01、CF-013；生命周期：D08、D11、D12、D13、D20、D23。

**修改范围／源码锚点：** ops-web/app/work-orders/page.tsx；ops-web/components/device/lifecycle-views.tsx；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetBindServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DeviceBindTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 先选商家，按商家服务端分页筛已出库设备；换商家清空设备站点点位；待签收不可绑定，已绑定待安装单列。覆盖出库页、设备页、通用INSTALL和派生安装；直接API、混商家批次与并发选择必须拒绝非法设备。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-07 完善安装验收和上线门禁的关联

优先级：P1；前置：DEV-06。

功能关联：CF-014、OM06-05、OM06-06；生命周期：D13、D14、D18、D19、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/port/InstallCompletedListener.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DeviceLifecycleTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 安装完工照片、扫码点位和商家一致；门禁记录关联本次安装／绑定，合同勘测豁免可追溯。完工不等于上线；事件失败可定位并恢复；上线不重复出库，旧安装单不能替代新部署验收。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-08 补齐采购台账与采购行

优先级：P1；前置：DEV-02。

功能关联：OM03-05、OM03-06；生命周期：D01、D08。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/SupplierServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java`；新增采购单头／行模型、API、迁移和Web页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 采购日期、供应商发票号、型号、订购数量、单价、税基／VAT、币种、附件和状态结构化；建立订购未收，不增加实物。复用已有供应商，不建设完整采购ERP；采购经办授权和单据修改边界明确。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-09 补齐分批收货、采购余额与来源防重

优先级：P1；前置：DEV-04, DEV-08。

功能关联：OM03-07；生命周期：D03、D04、D05、D21、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/IntakeDtos.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvIntake.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvIntakeItem.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；ops-web/components/inventory/intakes.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/IntakeReceiptTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 订100收80：实收80未收20；重复来源收货不增库，后续收20累计100。明确超收、拒收、采购退货后的余额和关闭规则；外部来源键预留，Zoho本期不接。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-10 补齐缺票期初资产的依据和价值记录

优先级：P1；前置：DEV-02。

功能关联：OM03-03、OM03-01、OM03-02；生命周期：D02。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/IntakeDtos.java`；ops-web/components/inventory/intakes.tsx；设备导入接口与文件绑定服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 缺票设备可导入，保留来源、数量、可靠价值、币种及佐证；财务核值，仓管核数量；重复导入不建重复资产，OPENING不会再被当成采购到货入账。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-11 统一实物、待检和可用口径及复检凭证

优先级：P1；前置：DEV-09, DEV-10。

功能关联：OM03-07、CF-025；生命周期：D03、D04、D05、D21、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/QcServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/LocalInventoryPort.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/remote/RemoteInventoryPort.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/IntakeFlowTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 80实收70合格10待检分别显示；实收后质检放行不重复增加实物；新采购不得无依据免检。退库用本单新凭证，重复消费、并发入库和不合格放行均拒绝。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-12 完善物料主数据、领退料和余额保护

优先级：P1；前置：DEV-04, DEV-02。

功能关联：CF-024、CF-028、CF-035；生命周期：D16、D22。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/MaterialServiceImpl.java`；ops-web/components/inventory/material-docs.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/MaterialServiceTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 数量与SN物料分别验证；同单重复行按合计校验，负数／超库存／错仓序列拒绝；内置机柜宝不走物料单据。有流水锁单位跟踪方式，有库存不得归档；领退料关联工单与来源。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-13 闭合盘点、差异复核和库存调整

优先级：P1；前置：DEV-02, DEV-04。

功能关联：OM03-11、OM03-12、RF-02、RF-03；生命周期：D06、D19、D24、D25。

**修改范围／源码锚点：** `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java` counts系列；inv/remote；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；ops-web/components/inventory/stock-counts.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** SERVICE真实盘点；盘点期间变动策略明确，录入与复核不同人；盘盈盘亏通过调整记流水，原始实盘不可覆盖；运输短少、多件与仓外核查不冒充仓内盘点。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-14 设计并实现现场停用与恢复

优先级：P1；前置：DEV-02, DEV-07。

功能关联：LC-02；生命周期：D18。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/CabinetLifecycleService.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DeviceOpsController.java`；设备运行页。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 先明确新借、归还、已有租赁和计费行为，再独立实现停用／恢复；保留现场绑定，不用undeploy代替停用。恢复失败须显示原因和责任待办。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-15 拆分维修路径并加入维修验收

优先级：P1；前置：DEV-07, DEV-11, DEV-12, DEV-14。

功能关联：CF-021、LC-03；生命周期：D16、D17、D22、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/CabinetStateMachine.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；维修工单与库存物料单据。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 分现场、回仓和返厂维修；记录故障、处理人、供应商、保修、配件、工时费用、测试与验收。不能通用FAULT→DEPLOYED；修复后按所在位置复检、恢复或重新投放。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-16 闭合撤机、交接、退库和再次部署

优先级：P1；前置：DEV-03, DEV-04, DEV-11, DEV-14。

功能关联：CF-022、OM03-07、OM03-09；生命周期：D03、D04、D05、D07、D19、D20、D21、D23。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/RemovalCompletedListener.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；ops-web/components/device/lifecycle-views.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 撤机照片、清点、去向留痕；留商家待安装不虚构仓内动作；退库先发货再实收待检，差异可追责。事件处理失败可恢复；旧出库与签收凭证不能用于下一次交付。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-17 完善采购退货、报废和遗失处理

优先级：P1；前置：DEV-02, DEV-04。

功能关联：CF-030、CF-023；生命周期：D04、D24。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/DisposalServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；ops-web/components/inventory/disposals.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DisposalTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 区分仓内与仓外处置；供应商、原因、证据、复核、损失和反向单关联；仓外遗失不再次扣仓内库存；退役与库存账、财务价值一致。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-18 统一设备履历与历史商家归属

优先级：P1；前置：DEV-05, DEV-07, DEV-15, DEV-16。

功能关联：OM03-10、OM01-05、CF-036；生命周期：D05、D20、D25。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/movement/MovementRecorder.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetBindServiceImpl.java`；商家归属变更服务；ops-web/components/device/movements.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 采购、质检、交付、安装、维修、迁点、撤机与处置可从单设备串单；日期、前后位置、员工、原因齐全。HotelA→MallB后旧订单／票据／分润仍归A，新业务归B。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-19 补齐君盛库存与资产报表

优先级：P1；前置：DEV-09, DEV-13, DEV-15, DEV-18。

功能关联：OM03-08、OM10-13、OM10-14、OM10-15；生命周期：D25。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/InventoryReportDtos.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryReportServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/StockViewServiceImpl.java`；ops-web/components/report/inventory-report.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/InventoryReportTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 逐项覆盖君盛11类统计及6类筛选；采购未收、调拨在途、待检、可用、待安装、在场、维修分列。仓内期初+入−出±调整=期末；价税按币种核算与字段权限显示。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-20 接通真实设备指令与可查询回执

优先级：P1；前置：DEV-02。

功能关联：OM03-14、OM03-17；生命周期：D15。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetServiceImpl.java`；`backend/sharehub-svc-gateway/src/main/java/ai/neargo/sharehub/gw/service/impl/VendorServiceImpl.java`；gateway端口及适配；ops-web/components/device/vendors-tab.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 定义厂商能力并接真实下发，重复请求复用commandId；超时不伪造成功，查询真实执行结果。连接探测真实或明确标不支持，危险命令独立授权。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-21 实现设备日志读写闭环

优先级：P1；前置：DEV-20。

功能关联：OM03-15；生命周期：D15。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/DeviceLogServiceImpl.java`；gateway日志写入链路；ops-web/lib/api/https/device.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 先落指令／上报日志，再提供带时间范围、分页和来源引用的查询；重复事件幂等，未知不当成功，不再固定返回空页。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-22 接通告警处置与真实通知结果

优先级：P1；前置：DEV-02。

功能关联：OM06-01、OM06-02、OM06-03、OM06-04、OM11-16、OM11-17；生命周期：D15。

**修改范围／源码锚点：** `backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/alarm/service/impl/AlarmServiceImpl.java`；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/notify/service/impl/NotifySendServiceImpl.java`；通知通道与告警判定器。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 重发保留源记录与幂等；区分真实成功、失败、排队、STUB，不直接写SENT。逾期租赁和重试耗尽告警按明确规则验证；短信沿现有一期mock决定标STUB，不擅自要求采购短信通道。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-23 接通投诉退款和转工单

优先级：P1；前置：DEV-02。

功能关联：OM04-08、OM06-09；生命周期：D17。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/trade/order/service/impl/ComplaintServiceImpl.java`；退款／工单Port；ops-web/lib/api/https/order.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 投诉REFUND处置创建可追踪退款申请，不把记录解决当退款执行；转工单复用source_ref防重，失败保持待处理，不抛通用不支持。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-24 实现退款执行与财务后续一致性

优先级：P0；前置：DEV-02。

功能关联：OM04-09；生命周期：横向／非设备领域。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/trade/order/service/impl/RefundServiceImpl.java`；支付退款端口；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/RefundIdempotencyTest.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/RefundShareReversalTest.java`；`ops-web/components/finance/finance-workspace.tsx`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 申请、审批、执行、成功／失败分离；重复回调／重试不重复出款；复核分润冲回和贷项的触发时点，执行失败不能留下无解释的财务结果；未授权执行拒绝。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-25 统一商家分润规则、生效与版本

优先级：P1；前置：DEV-02。

功能关联：OM01-06、OM08-01、OM08-02、OM08-03、CF-087、CF-088；生命周期：横向／非设备领域。

**修改范围／源码锚点：** `backend/sharehub-svc-finance/src/main/java/ai/neargo/sharehub/finance/service/impl/ShareGeneratorImpl.java`；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/agent/port/LocalAgentDirectory.java`；AgentCommissionService；规则与分润Web页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 区分现有固定额链路和比例来源，确定唯一规则版本／生效时间；固定额和比例真实算数，历史快照不变，试算与生成一致。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-26 实现商家绩效并修正经营视图

优先级：P2；前置：DEV-01。

功能关联：OM01-08、OM01-07；生命周期：横向／非设备领域。

**修改范围／源码锚点：** `backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/agent/ext/service/impl/AgentPerformanceServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/platform/AgentExtController.java`；商家经营／绩效页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 跨域统计按全量聚合排名后分页，日期区间和商家范围正确；不再固定空页，经营视图不能把缺失绩效当真实0。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-27 完善结算付款与跨期应付

优先级：P1；前置：DEV-02, DEV-25。

功能关联：OM08-05、OM08-06、OM08-07、OM08-08、OM07-03；生命周期：横向／非设备领域。

**修改范围／源码锚点：** `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/finance/FinanceController.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/finance/PayoutTaskController.java`；`ops-web/components/finance/finance-workspace.tsx`；`ops-web/components/finance/payables-panel.tsx`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 复用已有PayablesPanel与付款任务；确认结算、部分多次付款、外部单号唯一、双人复核、账户变更权限、未结余额可核对。先核实付款登记现有码，再补缺口。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-28 完善凭证、调整与对账处理

优先级：P1；前置：DEV-02, DEV-27。

功能关联：OM07-01、OM07-02、OM07-04、OM07-05；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 财务凭证／调整／对账服务；`ops-web/components/finance/finance-workspace.tsx`；审批适配。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 手工凭证／调整审批、借贷平衡、跨期冲回与不可覆盖历史；对账差异有来源和处置。网关手续费与到账先核真实数据能力，不伪造清算数据。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-29 完善租赁票据、结算票据和更正闭环

优先级：P1；前置：DEV-24, DEV-27。

功能关联：OM09-01、OM09-02、OM09-03、OM09-04、OM09-05、OM09-06、OM09-07、OM09-08；生命周期：横向／非设备领域。

**修改范围／源码锚点：** TaxInvoicePanel；`backend/sharehub-svc-finance/src/main/java/ai/neargo/sharehub/finance/service/impl/InvoiceServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/user/core/service/impl/UserInvoiceServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/InvoiceIssuedLockTest.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/B2cTaxInvoiceTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 复用TaxInvoicePanel与票据API；开票关联订单、金额税率和主体快照，已开票不可修改；失败重试、贷项关联原票、PDF批量下载及VAT台账验收。不得重复新建已有票据模块。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-30 界定会员发卡的支付和权益生效流程

优先级：P2；前置：DEV-02。

功能关联：OM04-18、OM04-19；生命周期：横向／非设备领域。

**修改范围／源码锚点：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/user/member/service/impl/MemberServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/UserOpsController.java`；ops-web/components/user/membership.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 先确认收费／赠送、支付结果和权益生效规则；未接通前Web明确禁用发卡；接通后幂等发卡、失败不生效，实例查询与权益一致。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-31 补应用版本发布动作

优先级：P2；前置：DEV-02。

功能关联：OM11-14；生命周期：横向／非设备领域。

**修改范围／源码锚点：** ops-web/lib/backend-ready.ts；应用版本服务／Controller；ops-web/app/operation/app-versions/page.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 草稿、发布、生效、强更与撤回边界明确，服务端提供受控发布；PARTIAL_READY只在真实链路验证后置true，C端只取已发布版本。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-32 完成OpsKit待办审批真实接入与仓管任务

优先级：P1；前置：DEV-02。

功能关联：OM00-04、OM00-05、OM00-06、OM00-07、CF-133、CF-134、CF-135、CF-136、CF-137；生命周期：横向／非设备领域。

**修改范围／源码锚点：** ops-web/lib/flow；ops-web/app/flow；流程回调适配；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/iam/RolePerms.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 真实接入、业务回写、领取超时和幂等；仓管能看到授权收发／盘点任务；流程任务与领域工单来源清楚，未接通明确不可用；禁止申请人自审。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-33 建设统一导出任务及权限审计

优先级：P2；前置：DEV-02。

功能关联：OM10-16、OM09-06；生命周期：横向／非设备领域。

**修改范围／源码锚点：** ops-web/lib/api/download.ts；报表／票据导出服务；拟新增导出任务模型与界面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 先复用既有导出，补异步进度、失败重试、限时下载和审计；查询范围沿用，只有读权限不能越权导出敏感字段；Excel／PDF实际打开验收。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-34 建设集成状态与受控恢复入口

优先级：P2；前置：DEV-04, DEV-20, DEV-22, DEV-32。

功能关联：RF-05；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 集成适配及错误记录；系统管理页面；库存恢复服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 支付、库存、设备、通知、OpsKit的错误及处理状态可追溯，密钥脱敏；恢复调用原业务幂等服务，不能新增绕过领域权限的通用重放。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-35 完善商家门户只读经营与资产视图

优先级：P1；前置：DEV-02, DEV-27, DEV-29。

功能关联：OM12-01、OM12-02、OM12-03、OM12-04、OM12-05、OM12-06、OM12-07、OM12-08、OM12-09、CF-005、CF-006；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 商家门户NAV／profile；商家专用Controller；复用资产／订单／财务页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 本商家数据范围、收益、结算、付款记录、票据、待安装／已安装设备和报修；一期不开放商家申请提现或后台经营配置；深链和API不能切换其他agentNo。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-36 完善角色工作台及实时运营提醒

优先级：P2；前置：DEV-02, DEV-19, DEV-22, DEV-32。

功能关联：OM00-01、OM00-02、OM00-03；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 经营看板服务；ops-web工作台；合同提醒规则。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 八角色卡片按职责和范围展示；仓管收发待办、运维故障、客服在租异常、财务待付；支付状态与归还分开，卡片能下钻一致口径；合同提醒不硬编码为唯一期限。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-37 落地新版菜单及全局命名

优先级：P1；前置：DEV-02。

功能关联：OM11-04；生命周期：横向／非设备领域。

**修改范围／源码锚点：** ops-web/lib/nav.ts；`ops-web/lib/i18n/nav-labels.ts`；nav-seed.test.ts；Flyway新迁移；路由重定向。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 按新版模块分组实施菜单迁移与默认页；V211出库管理在静态NAV与数据库一致；保留旧深链跳转、三语和分期接入提示，不能改历史迁移。语义相同复用页内视图。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-38 闭合设备全生命周期的分角色UAT

优先级：P1；前置：DEV-06, DEV-07, DEV-09, DEV-11, DEV-13, DEV-15, DEV-16, DEV-17, DEV-18, DEV-19, DEV-20, DEV-21。

功能关联：全部生命周期节点；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 设备生命周期-角色菜单功能对照.md；backend/scripts/inventory-local/e2e.py；后端scenario与Web端到端测试。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** D01—D25逐阶段执行，含LOCAL／SERVICE差异及充电宝／物料；八角色正反权限、100/80/70案例、跨商家安装、返修复检再投放、并发和超时恢复；保留数据、截图／日志及对账结果。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-39 核对所有报表口径及非库存运营功能

优先级：P2；前置：DEV-02, DEV-19, DEV-27, DEV-29。

功能关联：全模块横向工作；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 报表、商家／网点、销售、用户等各模块现有实现；矩阵中的逐项核查记录。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 依据各模块核查任务补齐已确认缺口；报表与订单／支付／分润／票据／资产来源一致。统一筛选、历史时点和角色范围，不从缺少菜单推断接口缺失。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

### DEV-40 发布前回归、数据迁移与状态回填

优先级：P1；前置：DEV-38, DEV-37, DEV-39。

功能关联：全模块横向工作；生命周期：横向／非设备领域。

**修改范围／源码锚点：** 新版矩阵；生命周期表；Flyway；部署与恢复脚本；验收记录。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**可验收结果：** 备份恢复演练、迁移核对、角色菜单与数据范围、真实依赖及失败恢复全部留证；阻断资金重复、库存数量错误和历史归属覆盖；只对具备证据的范围回填已验证，预留项不算失败。

- [ ] 核对关联CHK结果，确定已有代码复用范围；涉及新状态／单据／外部协议时先补该任务契约。
- [ ] 为上面的具体失败条件写回归用例，运行并确认因目标缺口失败；纯文案／配置调整用现有契约检查。
- [ ] 实现最小完整链路：Web、API、业务、存储或外部适配及权限同时交付；本任务所有具体断言都通过。
- [ ] 执行适用的下述验证命令及真实服务场景，保存结果；评审后独立提交，回填矩阵与任务状态。

## 五 验证命令与完成定义

以下为实施时运行的命令，本轮只整理任务，没有重新声称这些检查通过。已知阻塞：当前后端JDK21环境、.next生成类型错误；DEV-01先消除。

Web（目录ops-web）：

```sh
npm run typecheck
npm test -- lib/nav.test.ts lib/nav-seed.test.ts lib/inventory-routes.test.ts lib/mock/db/device-pool.test.ts lib/device-lifecycle.test.ts
```

库存与设备后端（目录backend，JDK21）：

```sh
mvn -o -pl sharehub-app -am -Dtest=DevicePoolTest,DeviceBindTest,DeviceLifecycleTest,TransferOperatorTest,IntakeFlowTest,IntakeReceiptTest,MaterialServiceTest,InventoryServiceEngineTest,DisposalTest,InventoryReportTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退款票据回归可用既有RefundIdempotencyTest、RefundShareReversalTest、InvoiceIssuedLockTest和B2cTaxInvoiceTest；任务新增回归须加入对应运行清单。如离线依赖不足，先解决环境并记录，不把环境失败视为功能通过。

真实库存服务按backend/scripts/inventory-local/README.md和e2e.py运行在隔离测试业主。读取脚本操作和清理行为后执行；额外新增超时／部分成功故障注入用例。OpsKit与真实厂商测试必须有可用接入条件。

Done：关联功能在指定角色与范围下满足可验收结果、失败和重试场景有证据、迁移与兼容检查通过、没有遗留虚假成功，矩阵及生命周期状态回填。未知或条件不具备的项保留待核实／条件状态。

## 六 全186节点任务追溯

每个功能必须有一个模块核查任务；DEV表示已有明确开发或设计方向。只有CHK的功能先核查再决定是否新增开发任务。预留项明确不进入一期编码。

| 功能编号 | 功能 | 当前状态 | 核查任务 | 开发／交付任务 |
|---|---|---|---|---|
| OM00-01 | 经营看板 | 待验收 | CHK-01 | DEV-36 |
| OM00-02 | 租赁实时看板 | 待核实 | CHK-01 | DEV-36 |
| OM00-03 | 到期与提醒 | 待核实 | CHK-01 | DEV-36 |
| OM00-04 | 待办中心 | 条件／部分 | CHK-01 | DEV-32 |
| OM00-05 | 审批列表 | 条件／部分 | CHK-01 | DEV-32 |
| OM00-06 | 审核队列 | 条件／部分 | CHK-01 | DEV-32 |
| OM00-07 | 工单工作台 | 条件／部分 | CHK-01 | DEV-32 |
| OM01-01 | 入驻审核 | 待验收 | CHK-02 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM01-02 | 商家档案 | 待验收 | CHK-02 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM01-03 | 商家账号 | 待验收 | CHK-02 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM01-04 | 渠道商户（进件） | 待核实 | CHK-02 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM01-06 | 商家分润规则 | 部分实现 | CHK-02 | DEV-25 |
| OM01-05 | 整站换商家 | 待验收 | CHK-02 | DEV-18 |
| OM01-07 | 商家经营视图 | 部分实现 | CHK-02 | DEV-26 |
| OM01-08 | 商家绩效 | 待开发 | CHK-02 | DEV-26 |
| OM01-09 | 退出与清算 | 待验收 | CHK-02 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-08 | BD 拓展 CRM | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-09 | 门店 Onboarding | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-05 | 场地方 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-06 | 进场合同 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-07 | 合同提醒设置 | 待核实 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-02 | 站点管理 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-03 | 点位管理 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-01 | 站点概览 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-04 | 站点坪效 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM02-10 | 门店生命周期 | 待验收 | CHK-03 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM03-01 | 设备台账 | 待验收 | CHK-04 | DEV-10 |
| OM03-02 | 充电宝管理 | 待验收 | CHK-04 | DEV-10 |
| OM03-04 | 设备编码 | 待验收 | CHK-04 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM03-10 | 设备履历 | 待核实 | CHK-04 | DEV-18 |
| OM03-17 | 供应商接入 | 部分实现 | CHK-04 | DEV-20 |
| OM03-13 | 实时监控 | 待验收 | CHK-04 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM03-14 | 远程控制与指令记录 | 部分实现 | CHK-04 | DEV-20 |
| OM03-15 | 设备日志 | 待开发 | CHK-04 | DEV-21 |
| OM03-16 | 固件 OTA | 待验收 | CHK-04 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-013 | 部署与安装 | 部分实现 | CHK-04 | DEV-06 |
| CF-014 | 上线验收 | 部分实现 | CHK-04 | DEV-07 |
| CF-021 | 故障与维修 | 部分实现 | CHK-04 | DEV-15 |
| CF-022 | 撤机与退库 | 待验收 | CHK-04 | DEV-16 |
| CF-023 | 报废与遗失 | 待验收 | CHK-04 | DEV-17 |
| LC-03 | 维修验收与复检 | 部分实现 | CHK-04 | DEV-15 |
| LC-02 | 停用与恢复 | 待验收 | CHK-04 | DEV-14 |
| OM03-05 | 供应商 | 待验收 | CHK-05 | DEV-08 |
| CF-024 | 物料档案 | 待验收 | CHK-05 | DEV-12 |
| CF-025 | 仓库 | 待验收 | CHK-05 | DEV-11 |
| OM03-06 | 采购单 | 待补齐 | CHK-05 | DEV-08 |
| OM03-07 | 入库单 | 部分实现 | CHK-05 | DEV-09、DEV-11、DEV-16 |
| OM03-03 | 期初登记 | 部分实现 | CHK-05 | DEV-10 |
| OM03-08 | 库存余额 | 待验收 | CHK-05 | DEV-19 |
| CF-035 | 物料库存 | 条件／部分 | CHK-05 | DEV-12 |
| CF-036 | 库存流水 | 待验收 | CHK-05 | DEV-18 |
| RF-06 | 库存作业审计 | 待设计 | CHK-05 | DEV-02 |
| OM03-09 | 库存调拨 | 部分实现 | CHK-05 | DEV-03、DEV-16 |
| OM03-11 | 盘点 | 条件／部分 | CHK-05 | DEV-13 |
| OM03-12 | 资产差异 | 待验收 | CHK-05 | DEV-13 |
| RF-01 | 库存异常与恢复 | 待设计 | CHK-05 | DEV-04 |
| RF-02 | 库存账实与跨系统对账 | 待设计 | CHK-05 | DEV-13 |
| RF-03 | 库存反向单据与差异结案 | 待设计 | CHK-05 | DEV-13 |
| CF-028 | 物料单据 | 条件／部分 | CHK-05 | DEV-04、DEV-12 |
| CF-029 | 出库管理 | 部分实现 | CHK-05 | DEV-05 |
| CF-030 | 处置出库 | 部分实现 | CHK-05 | DEV-04、DEV-17 |
| LC-01 | 待安装设备 | 部分实现 | CHK-05 | DEV-06 |
| LC-04 | 交付记录 | 部分实现 | CHK-05 | DEV-02、DEV-05 |
| CF-037 | 库存设置 | 部分实现 | CHK-05 | DEV-04 |
| RF-04 | 库存引擎切换检查 | 待设计 | CHK-05 | DEV-04 |
| OM04-10 | 收费方案 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-11 | 预约调价 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-01 | 订单列表 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-05 | 预约订单 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-02 | 逾期与最高收费 | 待核实 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-03 | 押金与欠费 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-06 | 免费订单 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-12 | 支付流水 | 待核实 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-04 | 扣款失败与重试 | 待核实 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-13 | 支付渠道 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-07 | 异常订单 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-08 | 投诉订单 | 部分实现 | CHK-06 | DEV-23 |
| OM04-09 | 退款记录 | 部分实现 | CHK-06 | DEV-24 |
| OM04-14 | 优惠券 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-15 | 活动 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-16 | 推送触达 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-17 | 邀请裂变 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-18 | 会员套餐 | 待验收 | CHK-06 | DEV-30 |
| OM04-19 | 会员／次卡 | 部分实现 | CHK-06 | DEV-30 |
| OM04-20 | 广告位管理 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-21 | 广告活动 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM04-22 | 投放与曝光 | 待验收 | CHK-06 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-01 | 用户列表 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-02 | 注销申请 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-03 | 风控用户 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-04 | 黑名单 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-05 | 免押白名单 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-06 | 钱包 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM05-07 | 充值订单与套餐 | 待验收 | CHK-07 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM06-01 | 告警记录 | 待验收 | CHK-08 | DEV-22 |
| OM06-02 | 告警通知 | 部分实现 | CHK-08 | DEV-22 |
| OM06-03 | 告警代码 | 待验收 | CHK-08 | DEV-22 |
| OM06-04 | 通知规则 | 待验收 | CHK-08 | DEV-22 |
| OM06-09 | 报障受理 | 待验收 | CHK-08 | DEV-23 |
| OM06-10 | 客服会话 | 待验收 | CHK-08 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM06-05 | 工单列表 | 待验收 | CHK-08 | DEV-07 |
| OM06-06 | 工单看板 | 待验收 | CHK-08 | DEV-07 |
| OM06-08 | 巡检计划 | 待验收 | CHK-08 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM06-07 | SLA 管理 | 待验收 | CHK-08 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM08-01 | 分润规则 | 待验收 | CHK-09 | DEV-25 |
| OM08-02 | 分润明细 | 待验收 | CHK-09 | DEV-25 |
| OM08-03 | 分润统计 | 待验收 | CHK-09 | DEV-25 |
| CF-087 | 站点分成 | 待验收 | CHK-09 | DEV-25 |
| CF-088 | 分成方分成 | 待验收 | CHK-09 | DEV-25 |
| OM08-05 | 结算单 | 待验收 | CHK-09 | DEV-27 |
| OM08-06 | 付款登记 | 待核实 | CHK-09 | DEV-27 |
| OM08-07 | 付款任务 | 待验收 | CHK-09 | DEV-27 |
| OM08-08 | 收款账户 | 待验收 | CHK-09 | DEV-27 |
| OM07-04 | 对账 | 待验收 | CHK-10 | DEV-28 |
| OM07-05 | 网关手续费与到账 | 待核实 | CHK-10 | DEV-28 |
| OM07-01 | 账务分录 | 待验收 | CHK-10 | DEV-28 |
| OM07-02 | 调整单 | 待验收 | CHK-10 | DEV-28 |
| OM07-03 | 应付台账 | 部分实现 | CHK-10 | DEV-27 |
| OM07-06 | Zoho 连接与映射 | 预留 | CHK-10 | 一期不编码 |
| OM09-01 | 租赁票据 | 部分实现 | CHK-11 | DEV-29 |
| OM09-03 | 开票申请（C 端） | 待验收 | CHK-11 | DEV-29 |
| OM09-04 | 开票任务与异常 | 待核实 | CHK-11 | DEV-29 |
| OM09-02 | 结算票据 | 部分实现 | CHK-11 | DEV-29 |
| OM09-05 | 贷项通知单 | 待核实 | CHK-11 | DEV-29 |
| OM09-06 | 批量下载与登记表 | 待核实 | CHK-11 | DEV-29、DEV-33 |
| OM09-07 | VAT 台账 | 待核实 | CHK-11 | DEV-29 |
| OM09-08 | 开票主体与税率 | 待验收 | CHK-11 | DEV-29 |
| OM10-01 | 租赁销售额 | 部分实现 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-02 | 租赁交易明细 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-03 | 进行中／逾期／最高收费 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-05 | 设备交易与收入 | 部分实现 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-04 | 商家销售与分成 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-06 | 支付状态记录 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-09 | 网关手续费与结算 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-10 | 退款、贷项与调整 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-08 | 商家应付未结 | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-07 | 发票登记与 VAT | 待核实 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-11 | 第三方费用 | 预留 | CHK-12 | 一期不编码 |
| OM10-12 | 会计同步与对账 | 预留 | CHK-12 | 一期不编码 |
| OM10-14 | 采购与采购金额 | 待补齐 | CHK-12 | DEV-19 |
| OM10-13 | 库存余额与变动 | 部分实现 | CHK-12 | DEV-19 |
| OM10-15 | 安装／调拨／撤出／维修 | 待核实 | CHK-12 | DEV-19 |
| OM10-16 | 导出中心 | 待核实 | CHK-12 | DEV-33 |
| OM10-17 | 点位坪效 | 待验收 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-18 | 实时大屏 | 待验收 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-19 | 自定义报表 | 待验收 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM10-20 | 消费者分析 | 待验收 | CHK-12 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-01 | 员工 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-02 | 组织架构 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-03 | 角色权限 | 待验收 | CHK-13 | DEV-02 |
| OM11-04 | 菜单管理 | 待验收 | CHK-13 | DEV-02、DEV-37 |
| OM11-05 | 操作审计 | 待验收 | CHK-13 | DEV-02 |
| OM11-06 | 绩效报表 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-07 | 业务规则 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-08 | 登录设置 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-09 | 系统参数 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-10 | 多国家市场 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-11 | OpenAPI 应用 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-12 | 参数字典 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-14 | 应用版本 | 部分实现 | CHK-13 | DEV-31 |
| OM11-15 | 公告管理 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-124 | 地区库 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-125 | 银行管理 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-126 | 品牌管理 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-127 | 问题管理 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-16 | 通知模板 | 待验收 | CHK-13 | DEV-22 |
| OM11-17 | 发送记录 | 待验收 | CHK-13 | DEV-22 |
| OM11-18 | 触达拉黑 | 待验收 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| OM11-21 | 备份与恢复 | 待核实 | CHK-13 | 先完成CHK；已有覆盖则仅验收，缺口再建DEV |
| CF-133 | 流程配置 | 条件／部分 | CHK-13 | DEV-32 |
| CF-134 | 运营配置 | 条件／部分 | CHK-13 | DEV-32 |
| CF-135 | 接入管理 | 条件／部分 | CHK-13 | DEV-32 |
| CF-136 | 流程实例 | 条件／部分 | CHK-13 | DEV-32 |
| CF-137 | 流程统计 | 条件／部分 | CHK-13 | DEV-32 |
| RF-05 | 集成运行状态 | 待设计 | CHK-13 | DEV-34 |
| OM12-01 | 我的看板 | 待验收 | CHK-14 | DEV-35 |
| OM12-02 | 我的收益 | 待验收 | CHK-14 | DEV-35 |
| OM12-03 | 我的结算 | 待验收 | CHK-14 | DEV-35 |
| OM12-04 | 我的应付对账单 | 待核实 | CHK-14 | DEV-35 |
| OM12-05 | 我的付款记录 | 待验收 | CHK-14 | DEV-35 |
| OM12-06 | 我的发票 | 待核实 | CHK-14 | DEV-35 |
| OM12-07 | 我的设备 | 待验收 | CHK-14 | DEV-35 |
| OM12-08 | 我的订单 | 待验收 | CHK-14 | DEV-35 |
| OM12-09 | 设备报修 | 待验收 | CHK-14 | DEV-35 |
| CF-005 | 商家资料 | 待验收 | CHK-14 | DEV-35 |
| CF-006 | 我的站点 | 待验收 | CHK-14 | DEV-35 |

## 七 全25生命周期阶段任务追溯

| 阶段 | 核心功能实现任务 | 最终验收 |
|---|---|---|
| D01 采购需求与下单 | DEV-08 | DEV-38；阶段数据与角色交接留证 |
| D02 期初存量登记 | DEV-10 | DEV-38；阶段数据与角色交接留证 |
| D03 实际收货 | DEV-09、DEV-11、DEV-16 | DEV-38；阶段数据与角色交接留证 |
| D04 验收质检与不合格处理 | DEV-04、DEV-09、DEV-11、DEV-16、DEV-17 | DEV-38；阶段数据与角色交接留证 |
| D05 合格入库与保管 | DEV-09、DEV-11、DEV-16、DEV-18 | DEV-38；阶段数据与角色交接留证 |
| D06 仓内盘点与差异 | DEV-13 | DEV-38；阶段数据与角色交接留证 |
| D07 仓间调拨 | DEV-03、DEV-16 | DEV-38；阶段数据与角色交接留证 |
| D08 部署计划与备货 | DEV-05、DEV-06、DEV-08 | DEV-38；阶段数据与角色交接留证 |
| D09 按商家出库 | DEV-02、DEV-05 | DEV-38；阶段数据与角色交接留证 |
| D10 运输与签收 | DEV-02、DEV-05 | DEV-38；阶段数据与角色交接留证 |
| D11 按商家选待安装设备 | DEV-06 | DEV-38；阶段数据与角色交接留证 |
| D12 绑定站点与点位 | DEV-05、DEV-06 | DEV-38；阶段数据与角色交接留证 |
| D13 现场安装与交付验收 | DEV-02、DEV-05、DEV-06、DEV-07 | DEV-38；阶段数据与角色交接留证 |
| D14 上线检查与营业 | DEV-07 | DEV-38；阶段数据与角色交接留证 |
| D15 运行监控与告警 | DEV-20、DEV-21、DEV-22 | DEV-38；阶段数据与角色交接留证 |
| D16 巡检与保养 | DEV-04、DEV-12、DEV-15 | DEV-38；阶段数据与角色交接留证 |
| D17 故障分诊与现场维修 | DEV-15、DEV-23 | DEV-38；阶段数据与角色交接留证 |
| D18 暂停营业与恢复 | DEV-07、DEV-14 | DEV-38；阶段数据与角色交接留证 |
| D19 下线清点与撤机 | DEV-07、DEV-13、DEV-16 | DEV-38；阶段数据与角色交接留证 |
| D20 商家留存与迁点／换商家 | DEV-06、DEV-16、DEV-18 | DEV-38；阶段数据与角色交接留证 |
| D21 退库运输与仓库接收 | DEV-03、DEV-09、DEV-11、DEV-16 | DEV-38；阶段数据与角色交接留证 |
| D22 回仓维修／供应商返修 | DEV-04、DEV-12、DEV-15 | DEV-38；阶段数据与角色交接留证 |
| D23 修复复检与再投放 | DEV-05、DEV-06、DEV-07、DEV-09、DEV-11、DEV-15、DEV-16 | DEV-38；阶段数据与角色交接留证 |
| D24 采购退货、报废与遗失 | DEV-04、DEV-13、DEV-17 | DEV-38；阶段数据与角色交接留证 |
| D25 履历、对账与报表 | DEV-04、DEV-13、DEV-18、DEV-19 | DEV-38；阶段数据与角色交接留证 |

## 八 首批开工建议

先完成DEV-01并启动设备／库存／权限的CHK，随后DEV-03/04保证数量正确，DEV-05/06/07打通商家出库安装上线；DEV-24作为资金正确性独立优先项。完成本批后再接采购、维修和报表，不先做大范围页面重排。

本任务表未给出未经估算的工期。每个DEV开工时依据CHK结果补最终接口、迁移号和新增测试文件，再评估工作量；本轮没有实施任务、提交代码或部署。

## 配套分层开发清单

[Web、API、数据库与代码清单](2026-10-30-ops-development-checklist.md)：逐DEV拆解，并关联API契约提案与数据库迁移工作包。

## 实际执行顺序

以[数据库先行与模块交付顺序](2026-10-30-ops-execution-order.md)为准：数据库兼容扩展先完成，再按模块修改持久层、业务、API和Web；回填与强约束在代码切换后收口。原DEV任务依赖保持有效。

# 服务端功能评审 · 任务汇总

> **本文件由脚本生成，勿手改** —— `python3 backend/scripts/review-summary.py`。改任务请改各阶段文档 §三，再重跑。
>
> 规划与方法见 [00-任务规划](00-任务规划.md)。优先级：**P0** 缺陷或阻塞第一阶段 · **P1** 第一阶段需要、可后置 · **P2** 质量提升。括号内为前提（如「K7 前提」= 运营方裁决 K7 后才成立或才需要做；「上线闸门」= 上线前必须完成）。

## 一、阶段索引

| 阶段 | 评审文档 | 任务 | P0 | 其中带前提或闸门 | P1 | P2 |
|---|---|---:|---:|---:|---:|---:|
| S0 | [S0-平台底座](S0-平台底座.md) | 33 | 8 | 2 | 17 | 8 |
| S1 | [S1-商家入驻](S1-商家入驻.md) | 27 | 6 | — | 14 | 7 |
| S2 | [S2-拓展与签约](S2-拓展与签约.md) | 28 | 5 | — | 15 | 8 |
| S3 | [S3-建站点与点位](S3-建站点与点位.md) | 26 | 5 | — | 10 | 11 |
| S4 | [S4-设备采购入库](S4-设备采购入库.md) | 21 | 10 | 8 | 7 | 4 |
| S5 | [S5-设备上线与归属](S5-设备上线与归属.md) | 30 | 9 | 4 | 14 | 7 |
| S6 | [S6-定价与借还付款](S6-定价与借还付款.md) | 31 | 7 | — | 19 | 5 |
| S7 | [S7-异常收费与售后](S7-异常收费与售后.md) | 23 | 6 | — | 13 | 4 |
| S8 | [S8-开票](S8-开票.md) | 24 | 9 | 6 | 13 | 2 |
| S9 | [S9-监控告警工单客服](S9-监控告警工单客服.md) | 32 | 5 | — | 19 | 8 |
| S10 | [S10-资产流转与撤场](S10-资产流转与撤场.md) | 24 | 5 | — | 14 | 5 |
| S11 | [S11-分钱](S11-分钱.md) | 30 | 8 | 1 | 15 | 7 |
| S12 | [S12-账务与对账](S12-账务与对账.md) | 17 | 6 | 2 | 8 | 3 |
| S13 | [S13-经营分析与报表](S13-经营分析与报表.md) | 26 | 4 | — | 14 | 8 |
| S14 | [S14-用户运营与营销](S14-用户运营与营销.md) | 29 | 6 | — | 15 | 8 |
| S15 | [S15-系统配置与基础资料](S15-系统配置与基础资料.md) | 22 | 1 | — | 14 | 7 |
| **合计** | | **423** | **100** | 23 | **221** | **102** |

## 二、P0 清单

### 2.1 无前提的 P0（77）

| 编号 | 模块 | 功能 | 内容 | 规模 | 依赖 |
|---|---|---|---|---|---|
| [OPT-S0-10](S0-平台底座.md) | sharehub-svc-platform | SYS-02 | 在线摘角色即时生效 | S | — |
| [OPT-S0-11](S0-平台底座.md) | sharehub-svc-platform | SYS-02 | 数据范围 fail-closed | S | — |
| [OPT-S0-12](S0-平台底座.md) | sharehub-svc-platform | SYS-02 | 订单衍生 5 表 | M | — |
| [OPT-S0-21](S0-平台底座.md) | sharehub-common | INF-01 | 进程内事件可重投 | M | OPT-S0-23 |
| [OPT-S0-22](S0-平台底座.md) | sharehub-common | INF-01 | 事件必须在事务内发布 | M | — |
| [OPT-S0-28](S0-平台底座.md) | 跨模块 | 跨功能 | 基类拆 create / update | M | — |
| [OPT-S1-01](S1-商家入驻.md) | sharehub-svc-platform | OPR-01 | 审核通过先以条件更新抢状态（影响行数必须为 1），再激活 | S | — |
| [OPT-S1-02](S1-商家入驻.md) | sharehub-svc-platform | OPR-01 | 自助申请忽略 shareRate 与 regionScope | S | — |
| [OPT-S1-09](S1-商家入驻.md) | sharehub-svc-platform | OPR-03 | 以「添加成员」替代通用新建账号 | M | — |
| [OPT-S1-12](S1-商家入驻.md) | sharehub-svc-platform | OPR-02 | 修改商家遇不存在编号返回 404，删除新建分支 | S | — |
| [OPT-S1-13](S1-商家入驻.md) | sharehub-svc-platform | OPR-02 | 请求体改 AgentUpdateReq（不含状态） | S | — |
| [OPT-S1-25](S1-商家入驻.md) | sharehub-svc-platform | OPR-02（OM01-06） | 商家分润规则页面编辑的 agt_commission 不参与分润计算 | M | — |
| [OPT-S2-01](S2-拓展与签约.md) | sharehub-svc-platform | NET-01 | 拓展归因改由合同生效写入 | M | — |
| [OPT-S2-11](S2-拓展与签约.md) | sharehub-svc-platform | NET-05 | 会签收紧 | S | — |
| [OPT-S2-12](S2-拓展与签约.md) | sharehub-svc-platform | NET-05 | 牵线费只付一次 | S | — |
| [OPT-S2-13](S2-拓展与签约.md) | sharehub-svc-platform | NET-05 | 签署件只增不删 | S | — |
| [OPT-S2-14](S2-拓展与签约.md) | sharehub-svc-platform | NET-05（落在 S11） | 第一阶段场地方分成只记录 | M | — |
| [OPT-S3-01](S3-建站点与点位.md) | sharehub-svc-platform | NET-03 ① | 站点编辑改白名单条件更新（不写状态与运营列，带版本号、0 行 409） | S | — |
| [OPT-S3-11](S3-建站点与点位.md) | sharehub-svc-platform | NET-03 ⑥ | 责任编辑校验「路径站点存在且可见、被编辑行原属该站点」，不符 404 | S | — |
| [OPT-S3-12](S3-建站点与点位.md) | sharehub-svc-platform | NET-03 ⑥ | 责任读先带范围读站点 | S | — |
| [OPT-S3-21](S3-建站点与点位.md) | sharehub-svc-platform | NET-04 | 点位新建与编辑拆开 | S | — |
| [OPT-S3-22](S3-建站点与点位.md) | sharehub-svc-platform | NET-04 | 点位归属服务端派生 | S | — |
| [OPT-S4-11](S4-设备采购入库.md) | sharehub-svc-core | DEV-03 | 修期初导入 | S | — |
| [OPT-S4-13](S4-设备采购入库.md) | sharehub-svc-core | DEV-02 | 充电宝修改只受理运营动作 | S | — |
| [OPT-S5-01](S5-设备上线与归属.md) | sharehub-svc-core | DEV-01 | 机柜编辑改白名单条件更新（带 version、递增、0 行 409） | S | — |
| [OPT-S5-02](S5-设备上线与归属.md) | sharehub-svc-core | DEV-01 | 导入改 CabinetImportReq、不收 siteNo，逐行走与新建相同的归属派生，点位不存在 / 已归档 /… | S | — |
| [OPT-S5-03](S5-设备上线与归属.md) | sharehub-svc-core | DEV-01 | 新表 dev_cabinet_status_log，transit 同事务写（含原因、原新点位、操作人） | M | — |
| [OPT-S5-27](S5-设备上线与归属.md) | 跨模块 | 跨功能 | /internal/\*\* 全前缀验服务凭证 | S | OPT-S3-20 · OPT-S5-18 |
| [OPT-S5-29](S5-设备上线与归属.md) | sharehub-svc-platform | OPR-05 · DEV-01 | 从出库池绑定（U1、U2，一期主流程） | M | OPT-S10-10 · OPT-S10-24 · OPT-S5-11 |
| [OPT-S6-01](S6-定价与借还付款.md) | sharehub-svc-core | SAL-09 · SAL-10 | 收费方案写入面 = 费用项 | M | — |
| [OPT-S6-02](S6-定价与借还付款.md) | sharehub-svc-core | SAL-09 | 封顶名实一致 | M | — |
| [OPT-S6-10](S6-定价与借还付款.md) | sharehub-svc-platform | NET-08 · SAL-02 | 买断价单一来源（D4） | S | — |
| [OPT-S6-12](S6-定价与借还付款.md) | sharehub-svc-core | SAL-01 · SAL-11 | 预授权编排 | M | — |
| [OPT-S6-16](S6-定价与借还付款.md) | sharehub-svc-core | SAL-11 · SAL-02 | 请款幂等与三段式（D3） | M | — |
| [OPT-S6-21](S6-定价与借还付款.md) | sharehub-svc-core | SAL-02 | 结算条件迁移（D2） | M | OPT-S0-29 · OPT-S6-16 |
| [OPT-S6-25](S6-定价与借还付款.md) | sharehub-svc-core | SAL-03 | 审计表越权 | S | OPT-S0-12 |
| [OPT-S7-01](S7-异常收费与售后.md) | sharehub-svc-core | SAL-04 | 最高收费独立记录 | M | OPT-S6-16 · OPT-S6-21 |
| [OPT-S7-09](S7-异常收费与售后.md) | sharehub-svc-core | SAL-07 | 退款执行（D7） | M | OPT-S6-16 · OPT-S6-18 · OPT-S7-10 |
| [OPT-S7-10](S7-异常收费与售后.md) | sharehub-svc-core | SAL-07 | 退款入口闸 | S | — |
| [OPT-S7-11](S7-异常收费与售后.md) | sharehub-svc-core | SAL-07 | 审批闸 | S | — |
| [OPT-S7-14](S7-异常收费与售后.md) | sharehub-svc-core | SAL-06 | 售后资金动作收敛 | M | OPT-S7-10 |
| [OPT-S7-15](S7-异常收费与售后.md) | sharehub-svc-core | SAL-06 | 干预操作人只取会话 | S | — |
| [OPT-S8-01](S8-开票.md) | sharehub-svc-platform | SYS-07 | 税率版本化并被计算读取 | M | — |
| [OPT-S8-05](S8-开票.md) | sharehub-svc-finance | INV-02 | 申请按订单、金额服务端定 | M | — |
| [OPT-S8-06](S8-开票.md) | sharehub-svc-finance | INV-02 | 开具不再回填任意地址 | S | OPT-S8-05 |
| [OPT-S9-12](S9-监控告警工单客服.md) | sharehub-svc-ops | OPS-02 | 工单写入统一为「原状态条件更新 + 版本递增 + 行数检查」 | M | OPT-S0-29 |
| [OPT-S9-13](S9-监控告警工单客服.md) | sharehub-svc-ops | OPS-02 | 代理承接工单的处理入口 | M | — |
| [OPT-S9-14](S9-监控告警工单客服.md) | sharehub-svc-ops | OPS-02 · OPS-03 | SLA 口径统一 | S | — |
| [OPT-S9-19](S9-监控告警工单客服.md) | sharehub-svc-ops | OPS-04 | 报障越权 | S | OPT-S0-13 |
| [OPT-S9-22](S9-监控告警工单客服.md) | sharehub-svc-platform | SYS-10 | 通知不再假发送 | S | OPT-S5-27 |
| [OPT-S10-01](S10-资产流转与撤场.md) | sharehub-svc-core | DEV-07 | 调拨「保存」只建单与改草稿单头 | S | — |
| [OPT-S10-02](S10-资产流转与撤场.md) | sharehub-svc-core | DEV-07 | 仓库主数据 | M | OPT-S4-04 |
| [OPT-S10-10](S10-资产流转与撤场.md) | sharehub-svc-core | DEV-08 | 新表 dev_movement_log | M | OPT-S0-22 · OPT-S5-03 |
| [OPT-S10-11](S10-资产流转与撤场.md) | sharehub-svc-core | DEV-08 | 写入方接入 | M | OPT-S4-05 · OPT-S5-01 |
| [OPT-S10-24](S10-资产流转与撤场.md) | sharehub-svc-core | DEV-07 | 出库给商家（U1，一期主流程） | M | OPT-S10-01 · OPT-S5-29 |
| [OPT-S11-01](S11-分钱.md) | sharehub-svc-finance | STL-03 | 确认即冻结 | M | — |
| [OPT-S11-02](S11-分钱.md) | sharehub-svc-finance | FIN-02 | 已出账调整项不可作废 | S | — |
| [OPT-S11-03](S11-分钱.md) | sharehub-svc-finance | STL-04 | 提现申请重写 | M | OPT-S11-04 |
| [OPT-S11-04](S11-分钱.md) | sharehub-svc-finance | FIN-03 | 应付台账 | L | OPT-S0-20 |
| [OPT-S11-05](S11-分钱.md) | sharehub-svc-finance | STL-04 · STL-03 | 付款登记 + 结算单 PAID 派生 | M | OPT-S10-19 · OPT-S11-04 |
| [OPT-S11-06](S11-分钱.md) | sharehub-svc-finance | STL-01 · STL-03 · FIN-02 | 场地方不进结算单（ADR-032） | S | — |
| [OPT-S11-07](S11-分钱.md) | sharehub-svc-finance | STL-02 | 退款冲回 | M | OPT-S0-21 · OPT-S7-09 |
| [OPT-S12-01](S12-账务与对账.md) | sharehub-svc-finance | FIN-01 | 凭证头表与库层只追加 | M | — |
| [OPT-S12-02](S12-账务与对账.md) | sharehub-svc-finance | FIN-01 | 过账引擎与写入方接入 | L | OPT-S0-21 |
| [OPT-S12-03](S12-账务与对账.md) | sharehub-svc-finance | FIN-01 | 手工凭证收紧并审批生效 | M | — |
| [OPT-S12-08](S12-账务与对账.md) | sharehub-svc-finance | FIN-04 | 每日对账跑批 | L | OPT-S0-25 |
| [OPT-S13-01](S13-经营分析与报表.md) | sharehub-svc-report 🆕 | RPT-01 | 运营流程指标受数据范围约束 | S | — |
| [OPT-S13-02](S13-经营分析与报表.md) | sharehub-svc-report 🆕 | RPT-01 · RPT-02 | 金额口径重定 | M | — |
| [OPT-S13-03](S13-经营分析与报表.md) | sharehub-svc-report 🆕 | RPT-03 | 导出中心 | L | — |
| [OPT-S13-04](S13-经营分析与报表.md) | sharehub-svc-report 🆕 | RPT-02 | 报表目录化 + 首批 6 张 | L | OPT-S13-02 |
| [OPT-S14-01](S14-用户运营与营销.md) | sharehub-svc-core | CON-02 | 拉黑写入口迁 B 端 | S | OPT-S5-27 |
| [OPT-S14-02](S14-用户运营与营销.md) | sharehub-svc-core | CON-02 · SAL-01 | 借出与免押冻结拦黑名单 | S | OPT-S6-14 |
| [OPT-S14-04](S14-用户运营与营销.md) | sharehub-svc-core | CON-02 | 免单授予收紧 | M | — |
| [OPT-S14-05](S14-用户运营与营销.md) | sharehub-svc-core | CON-02 | 「免押白名单」更名与实现不符 | S | — |
| [OPT-S14-08](S14-用户运营与营销.md) | sharehub-svc-core | CON-03 | 注销止血 | S | — |
| [OPT-S14-16](S14-用户运营与营销.md) | sharehub-svc-core | SAL-12 | 领券重做 | M | — |
| [OPT-S15-17](S15-系统配置与基础资料.md) | sharehub-svc-platform | SYS-13 | 备份落地（上线闸门） | M | — |

### 2.2 上线闸门（8）

| 编号 | 优先级 | 模块 | 内容 | 规模 |
|---|---|---|---|---|
| [OPT-S0-02](S0-平台底座.md) | P0 | sharehub-auth 🆕 | 多副本前置 | L |
| [OPT-S0-33](S0-平台底座.md) | P0 | 跨模块 | sharehub_pii 落地 | L |
| [OPT-S5-13](S5-设备上线与归属.md) | P0 | sharehub-svc-gateway | 指令失败关闭 | S |
| [OPT-S5-14](S5-设备上线与归属.md) | P0 | sharehub-svc-gateway | 契约入 sharehub-api | M |
| [OPT-S5-15](S5-设备上线与归属.md) | P0 | sharehub-svc-gateway | 指令生命周期 | L |
| [OPT-S5-16](S5-设备上线与归属.md) | P0 | sharehub-svc-gateway | 首家真实驱动 + http-webhook 传输 | L |
| [OPT-S8-02](S8-开票.md) | P1 | sharehub-svc-platform | 开票主体 fin_tax_entity（法定名称与地址英 / 阿、TRN）与编号系列配置端点 | M |
| [OPT-S14-09](S14-用户运营与营销.md) | P1 | sharehub-svc-core | 注销执行 | M |

### 2.3 带前提的 P0（17）

前提不成立时按 P1 处理；裁决出来前先不排期。

| 前提 | 任务 |
|---|---|
| K7 前提 | [OPT-S4-01](S4-设备采购入库.md) 迁移 · [OPT-S4-02](S4-设备采购入库.md) 供应商与采购单端点 · [OPT-S4-03](S4-设备采购入库.md) 权限码 device:procurement:read / :update / :audit / :receive … · [OPT-S4-04](S4-设备采购入库.md) 迁移 · [OPT-S4-05](S4-设备采购入库.md) 收货登记与确认事务（抢状态 → 锁行 → 条件累加实收 → 建档 IN_STOCK + PENDING → 推进采购… · [OPT-S4-06](S4-设备采购入库.md) dev_cabinet、dev_powerbank 加 origin（存量回填 LEGACY）、origin_ref… · [OPT-S4-07](S4-设备采购入库.md) 统一建档服务 DeviceRegistrar · [OPT-S4-08](S4-设备采购入库.md) 新设备只经收货或期初入库 |
| K6 前提 | [OPT-S8-10](S8-开票.md) 票据四表迁移 · [OPT-S8-11](S8-开票.md) 取号与税额 · [OPT-S8-12](S8-开票.md) 开票规则引擎与补开 · [OPT-S8-13](S8-开票.md) 开票载荷 · [OPT-S8-16](S8-开票.md) 贷项通知单 · [OPT-S8-18](S8-开票.md) PDF 渲染 |
| 网关前提 | [OPT-S12-13](S12-账务与对账.md) 支付侧三事件与镜像表 · [OPT-S12-14](S12-账务与对账.md) 清算拉取、到账核销、手续费过账 |
| C07 前提 | [OPT-S11-08](S11-分钱.md) 分润基数口径 |

## 三、按 Maven 模块的工作量

人天按规模下限折算（S = 0.5、M = 2、L = 5），只用于排期比例，不是承诺。「跨模块」= 任务功能列为「跨功能」或多个功能。模块按[服务端包名规划](../服务端包名规划.md)的目标模块，🆕 为待新建。

| 模块 | P0 | P1 | P2 | 合计 | 约人天 | 其中 P0 人天 |
|---|---:|---:|---:|---:|---:|---:|
| `sharehub-svc-platform` | 24 | 72 | 37 | 133 | 120 | 24 |
| `sharehub-svc-core` | 35 | 63 | 23 | 121 | 126 | 44 |
| `sharehub-svc-ops` | 4 | 13 | 5 | 22 | 20 | 5 |
| `sharehub-svc-finance` | 22 | 30 | 7 | 59 | 84 | 52 |
| `sharehub-svc-report 🆕` | 4 | 11 | 5 | 20 | 32 | 12 |
| `sharehub-auth 🆕` | 1 | 4 | 3 | 8 | 12 | 5 |
| `sharehub-svc-gateway` | 4 | 2 | 2 | 8 | 22 | 12 |
| `sharehub-common` | 2 | 4 | 1 | 7 | 14 | 4 |
| `sharehub-app` | 0 | 4 | 2 | 6 | 4 | 0 |
| `跨模块` | 4 | 18 | 17 | 39 | 42 | 10 |
| **合计** | 100 | 221 | 102 | 423 | **477** | **168** |

## 四、依赖关系

### 4.1 被依赖最多的任务（先做）

| 任务 | 优先级 | 被依赖次数 | 内容 |
|---|---|---:|---|
| [OPT-S0-21](S0-平台底座.md) | P0 | 10 | 进程内事件可重投 |
| [OPT-S0-29](S0-平台底座.md) | P1 | 8 | Updates.exactlyOne + 台账 known-unchecked-updates.txt（基线 209… |
| [OPT-S0-32](S0-平台底座.md) | P1 | 8 | 时间口径统一 UTC |
| [OPT-S0-33](S0-平台底座.md) | P0 | 8 | sharehub_pii 落地 |
| [OPT-S6-16](S6-定价与借还付款.md) | P0 | 6 | 请款幂等与三段式（D3） |
| [OPT-S0-13](S0-平台底座.md) | P1 | 5 | 其余 45 张按 §二.6 分桶登记，每张配正反用例 |
| [OPT-S0-01](S0-平台底座.md) | P1 | 4 | 会话表只存 SHA-256、revoked_at 软撤销、按主体索引 |
| [OPT-S0-22](S0-平台底座.md) | P0 | 4 | 事件必须在事务内发布 |
| [OPT-S5-27](S5-设备上线与归属.md) | P0 | 4 | /internal/\*\* 全前缀验服务凭证 |
| [OPT-S7-09](S7-异常收费与售后.md) | P0 | 4 | 退款执行（D7） |
| [OPT-S8-10](S8-开票.md) | P0 | 4 | 票据四表迁移 |
| [OPT-S8-01](S8-开票.md) | P0 | 4 | 税率版本化并被计算读取 |
| [OPT-S11-04](S11-分钱.md) | P0 | 4 | 应付台账 |
| [OPT-S0-23](S0-平台底座.md) | P1 | 3 | 事件信封 |
| [OPT-S0-28](S0-平台底座.md) | P0 | 3 | 基类拆 create / update |
| [OPT-S5-29](S5-设备上线与归属.md) | P0 | 3 | 从出库池绑定（U1、U2，一期主流程） |
| [OPT-S5-15](S5-设备上线与归属.md) | P0 | 3 | 指令生命周期 |
| [OPT-S5-03](S5-设备上线与归属.md) | P0 | 3 | 新表 dev_cabinet_status_log，transit 同事务写（含原因、原新点位、操作人） |
| [OPT-S6-21](S6-定价与借还付款.md) | P0 | 3 | 结算条件迁移（D2） |
| [OPT-S7-10](S7-异常收费与售后.md) | P0 | 3 | 退款入口闸 |

### 4.2 依赖了非 P0 任务的 P0（排期时须一并提前）

| P0 任务 | 依赖的非 P0 任务 |
|---|---|
| [OPT-S0-21](S0-平台底座.md) | OPT-S0-23（P1） |
| [OPT-S5-27](S5-设备上线与归属.md) | OPT-S3-20（P2） · OPT-S5-18（P1） |
| [OPT-S5-29](S5-设备上线与归属.md) | OPT-S5-11（P1） |
| [OPT-S6-21](S6-定价与借还付款.md) | OPT-S0-29（P1） |
| [OPT-S7-09](S7-异常收费与售后.md) | OPT-S6-18（P1） |
| [OPT-S8-13](S8-开票.md) | OPT-S6-17（P1） |
| [OPT-S9-12](S9-监控告警工单客服.md) | OPT-S0-29（P1） |
| [OPT-S9-19](S9-监控告警工单客服.md) | OPT-S0-13（P1） |
| [OPT-S11-04](S11-分钱.md) | OPT-S0-20（P2） |
| [OPT-S11-05](S11-分钱.md) | OPT-S10-19（P1） |
| [OPT-S12-08](S12-账务与对账.md) | OPT-S0-25（P1） |
| [OPT-S14-02](S14-用户运营与营销.md) | OPT-S6-14（P1） |

### 4.3 跨仓依赖（共用支付 / 调度 / 网关）（9）

| 任务 | 优先级 | 依赖 |
|---|---|---|
| [OPT-S0-24](S0-平台底座.md) | P1 | 共用支付服务 · S5 |
| [OPT-S0-25](S0-平台底座.md) | P1 | 共用调度器（跨仓） |
| [OPT-S1-21](S1-商家入驻.md) | P1 | 共用支付服务 |
| [OPT-S5-03](S5-设备上线与归属.md) | P0 | 与 S10 SF-DEV-08 共用 |
| [OPT-S6-12](S6-定价与借还付款.md) | P0 | 共用支付服务能力确认（RQ-BIL-08） |
| [OPT-S6-18](S6-定价与借还付款.md) | P1 | 共用支付服务（v4 §五.3） |
| [OPT-S7-05](S7-异常收费与售后.md) | P1 | OPT-S6-16 · 共用支付服务存卡能力（RQ-BIL-08） |
| [OPT-S12-14](S12-账务与对账.md) | P0 | 02 · 13 · 共用支付服务 · C09 |
| [OPT-S14-14](S14-用户运营与营销.md) | P1 | 共用支付服务；OPT-S0-21 |

## 五、需运营方裁决

下表只统计**任务的依赖或优先级列**里点名的裁决编号，即「不裁决就排不了」的那部分；各阶段 §五（S7 为 §2.7）另有不卡任务的裁决项与选项代价，裁决时以阶段文档为准。

| 裁决 | 卡住的任务数 | 其中 P0 | 任务 |
|---|---:|---:|---|
| K7 | 8 | 8 | [OPT-S4-01](S4-设备采购入库.md) · [OPT-S4-02](S4-设备采购入库.md) · [OPT-S4-03](S4-设备采购入库.md) · [OPT-S4-04](S4-设备采购入库.md) · [OPT-S4-05](S4-设备采购入库.md) · [OPT-S4-06](S4-设备采购入库.md) · [OPT-S4-07](S4-设备采购入库.md) · [OPT-S4-08](S4-设备采购入库.md) |
| K6 | 6 | 6 | [OPT-S8-10](S8-开票.md) · [OPT-S8-11](S8-开票.md) · [OPT-S8-12](S8-开票.md) · [OPT-S8-13](S8-开票.md) · [OPT-S8-16](S8-开票.md) · [OPT-S8-18](S8-开票.md) |
| K4 | 4 | 0 | [OPT-S6-04](S6-定价与借还付款.md) · [OPT-S6-24](S6-定价与借还付款.md) · [OPT-S7-04](S7-异常收费与售后.md) · [OPT-S8-15](S8-开票.md) |
| C-S14-5 | 3 | 0 | [OPT-S14-12](S14-用户运营与营销.md) · [OPT-S14-15](S14-用户运营与营销.md) · [OPT-S14-26](S14-用户运营与营销.md) |
| C-S14-7 | 3 | 1 | [OPT-S14-16](S14-用户运营与营销.md) · [OPT-S14-17](S14-用户运营与营销.md) · [OPT-S14-24](S14-用户运营与营销.md) |
| C-S13-1 | 2 | 1 | [OPT-S13-02](S13-经营分析与报表.md) · [OPT-S13-25](S13-经营分析与报表.md) |
| C-S14-3 | 2 | 0 | [OPT-S14-06](S14-用户运营与营销.md) · [OPT-S14-07](S14-用户运营与营销.md) |
| C-S14-4 | 2 | 1 | [OPT-S14-08](S14-用户运营与营销.md) · [OPT-S14-09](S14-用户运营与营销.md) |
| C-S9-3 | 2 | 0 | [OPT-S9-27](S9-监控告警工单客服.md) · [OPT-S9-28](S9-监控告警工单客服.md) |
| C04 | 2 | 2 | [OPT-S6-02](S6-定价与借还付款.md) · [OPT-S13-04](S13-经营分析与报表.md) |
| C05 | 2 | 1 | [OPT-S8-12](S8-开票.md) · [OPT-S8-15](S8-开票.md) |
| C11 | 2 | 1 | [OPT-S1-02](S1-商家入驻.md) · [OPT-S11-10](S11-分钱.md) |
| K5 | 2 | 0 | [OPT-S6-04](S6-定价与借还付款.md) · [OPT-S7-04](S7-异常收费与售后.md) |
| C-S13-3 | 1 | 0 | [OPT-S13-26](S13-经营分析与报表.md) |
| C-S13-4 | 1 | 0 | [OPT-S13-09](S13-经营分析与报表.md) |
| C-S14-1 | 1 | 1 | [OPT-S14-04](S14-用户运营与营销.md) |
| C-S14-2 | 1 | 1 | [OPT-S14-05](S14-用户运营与营销.md) |
| C-S14-6 | 1 | 0 | [OPT-S14-18](S14-用户运营与营销.md) |
| C-S9-1 | 1 | 1 | [OPT-S9-13](S9-监控告警工单客服.md) |
| C-S9-2 | 1 | 1 | [OPT-S9-14](S9-监控告警工单客服.md) |
| C-S9-4 | 1 | 0 | [OPT-S9-30](S9-监控告警工单客服.md) |
| C-S9-5 | 1 | 0 | [OPT-S9-04](S9-监控告警工单客服.md) |
| C-S9-6 | 1 | 1 | [OPT-S9-22](S9-监控告警工单客服.md) |
| C01 | 1 | 0 | [OPT-S8-02](S8-开票.md) |
| C06 | 1 | 1 | [OPT-S13-04](S13-经营分析与报表.md) |
| C07 | 1 | 1 | [OPT-S11-08](S11-分钱.md) |
| C08 | 1 | 1 | [OPT-S8-11](S8-开票.md) |
| C09 | 1 | 1 | [OPT-S12-14](S12-账务与对账.md) |
| C14 | 1 | 0 | [OPT-S12-17](S12-账务与对账.md) |

各阶段裁决节：[S0](S0-平台底座.md) · [S1](S1-商家入驻.md) · [S2](S2-拓展与签约.md) · [S3](S3-建站点与点位.md) · [S4](S4-设备采购入库.md) · [S5](S5-设备上线与归属.md) · [S6](S6-定价与借还付款.md) · [S7](S7-异常收费与售后.md) · [S8](S8-开票.md) · [S9](S9-监控告警工单客服.md) · [S10](S10-资产流转与撤场.md) · [S11](S11-分钱.md) · [S12](S12-账务与对账.md) · [S13](S13-经营分析与报表.md) · [S14](S14-用户运营与营销.md) · [S15](S15-系统配置与基础资料.md)

## 六、全部任务（按优先级、阶段排序）

| 编号 | 优先级 | 规模 | 模块 | 功能 | 维度 | 内容 |
|---|---|---|---|---|---|---|
| [OPT-S0-10](S0-平台底座.md) | P0 | S | sharehub-svc-platform | SYS-02 | CODE · TEST | 在线摘角色即时生效 |
| [OPT-S0-11](S0-平台底座.md) | P0 | S | sharehub-svc-platform | SYS-02 | CODE · API · TEST | 数据范围 fail-closed |
| [OPT-S0-12](S0-平台底座.md) | P0 | M | sharehub-svc-platform | SYS-02 | DB · CODE · TEST | 订单衍生 5 表 |
| [OPT-S0-21](S0-平台底座.md) | P0 | M | sharehub-common | INF-01 | CODE · TEST | 进程内事件可重投 |
| [OPT-S0-22](S0-平台底座.md) | P0 | M | sharehub-common | INF-01 | CODE · TEST | 事件必须在事务内发布 |
| [OPT-S0-28](S0-平台底座.md) | P0 | M | 跨模块 | 跨功能 | CODE · API | 基类拆 create / update |
| [OPT-S1-01](S1-商家入驻.md) | P0 | S | sharehub-svc-platform | OPR-01 | CODE · TEST | 审核通过先以条件更新抢状态（影响行数必须为 1），再激活 |
| [OPT-S1-02](S1-商家入驻.md) | P0 | S | sharehub-svc-platform | OPR-01 | API | 自助申请忽略 shareRate 与 regionScope |
| [OPT-S1-09](S1-商家入驻.md) | P0 | M | sharehub-svc-platform | OPR-03 | API · CODE | 以「添加成员」替代通用新建账号 |
| [OPT-S1-12](S1-商家入驻.md) | P0 | S | sharehub-svc-platform | OPR-02 | API | 修改商家遇不存在编号返回 404，删除新建分支 |
| [OPT-S1-13](S1-商家入驻.md) | P0 | S | sharehub-svc-platform | OPR-02 | API · CODE | 请求体改 AgentUpdateReq（不含状态） |
| [OPT-S1-25](S1-商家入驻.md) | P0 | M | sharehub-svc-platform | OPR-02（OM01-06） | API · DB | 商家分润规则页面编辑的 agt_commission 不参与分润计算 |
| [OPT-S2-01](S2-拓展与签约.md) | P0 | M | sharehub-svc-platform | NET-01 | API · CODE · TEST | 拓展归因改由合同生效写入 |
| [OPT-S2-11](S2-拓展与签约.md) | P0 | S | sharehub-svc-platform | NET-05 | API · TEST | 会签收紧 |
| [OPT-S2-12](S2-拓展与签约.md) | P0 | S | sharehub-svc-platform | NET-05 | CODE · TEST | 牵线费只付一次 |
| [OPT-S2-13](S2-拓展与签约.md) | P0 | S | sharehub-svc-platform | NET-05 | API · CODE | 签署件只增不删 |
| [OPT-S2-14](S2-拓展与签约.md) | P0 | M | sharehub-svc-platform | NET-05（落在 S11） | CODE | 第一阶段场地方分成只记录 |
| [OPT-S3-01](S3-建站点与点位.md) | P0 | S | sharehub-svc-platform | NET-03 ① | CODE · TEST | 站点编辑改白名单条件更新（不写状态与运营列，带版本号、0 行 409） |
| [OPT-S3-11](S3-建站点与点位.md) | P0 | S | sharehub-svc-platform | NET-03 ⑥ | API · CODE · TEST | 责任编辑校验「路径站点存在且可见、被编辑行原属该站点」，不符 404 |
| [OPT-S3-12](S3-建站点与点位.md) | P0 | S | sharehub-svc-platform | NET-03 ⑥ | API · DB · TEST | 责任读先带范围读站点 |
| [OPT-S3-21](S3-建站点与点位.md) | P0 | S | sharehub-svc-platform | NET-04 | API · CODE · TEST | 点位新建与编辑拆开 |
| [OPT-S3-22](S3-建站点与点位.md) | P0 | S | sharehub-svc-platform | NET-04 | API · CODE | 点位归属服务端派生 |
| [OPT-S4-11](S4-设备采购入库.md) | P0 | S | sharehub-svc-core | DEV-03 | CODE · API · TEST | 修期初导入 |
| [OPT-S4-13](S4-设备采购入库.md) | P0 | S | sharehub-svc-core | DEV-02 | API · CODE · TEST | 充电宝修改只受理运营动作 |
| [OPT-S5-01](S5-设备上线与归属.md) | P0 | S | sharehub-svc-core | DEV-01 | CODE · TEST | 机柜编辑改白名单条件更新（带 version、递增、0 行 409） |
| [OPT-S5-02](S5-设备上线与归属.md) | P0 | S | sharehub-svc-core | DEV-01 | API · CODE · TEST | 导入改 CabinetImportReq、不收 siteNo，逐行走与新建相同的归属派生，点位不存在 / 已归档 /… |
| [OPT-S5-03](S5-设备上线与归属.md) | P0 | M | sharehub-svc-core | DEV-01 | DB · API · CODE | 新表 dev_cabinet_status_log，transit 同事务写（含原因、原新点位、操作人） |
| [OPT-S5-27](S5-设备上线与归属.md) | P0 | S | 跨模块 | 跨功能 | CODE · TEST | /internal/\*\* 全前缀验服务凭证 |
| [OPT-S5-29](S5-设备上线与归属.md) | P0 | M | sharehub-svc-platform | OPR-05 · DEV-01 | DB · API · CODE · TEST | 从出库池绑定（U1、U2，一期主流程） |
| [OPT-S6-01](S6-定价与借还付款.md) | P0 | M | sharehub-svc-core | SAL-09 · SAL-10 | DB · API · CODE · TEST | 收费方案写入面 = 费用项 |
| [OPT-S6-02](S6-定价与借还付款.md) | P0 | M | sharehub-svc-core | SAL-09 | CODE · TEST · DB | 封顶名实一致 |
| [OPT-S6-10](S6-定价与借还付款.md) | P0 | S | sharehub-svc-platform | NET-08 · SAL-02 | API · CODE · TEST | 买断价单一来源（D4） |
| [OPT-S6-12](S6-定价与借还付款.md) | P0 | M | sharehub-svc-core | SAL-01 · SAL-11 | API · DB · CODE · TEST | 预授权编排 |
| [OPT-S6-16](S6-定价与借还付款.md) | P0 | M | sharehub-svc-core | SAL-11 · SAL-02 | DB · CODE · TEST | 请款幂等与三段式（D3） |
| [OPT-S6-21](S6-定价与借还付款.md) | P0 | M | sharehub-svc-core | SAL-02 | CODE · TEST | 结算条件迁移（D2） |
| [OPT-S6-25](S6-定价与借还付款.md) | P0 | S | sharehub-svc-core | SAL-03 | DB · API · TEST | 审计表越权 |
| [OPT-S7-01](S7-异常收费与售后.md) | P0 | M | sharehub-svc-core | SAL-04 | DB · CODE · TEST | 最高收费独立记录 |
| [OPT-S7-09](S7-异常收费与售后.md) | P0 | M | sharehub-svc-core | SAL-07 | DB · CODE · TEST | 退款执行（D7） |
| [OPT-S7-10](S7-异常收费与售后.md) | P0 | S | sharehub-svc-core | SAL-07 | DB · API · CODE · TEST | 退款入口闸 |
| [OPT-S7-11](S7-异常收费与售后.md) | P0 | S | sharehub-svc-core | SAL-07 | CODE · API · TEST | 审批闸 |
| [OPT-S7-14](S7-异常收费与售后.md) | P0 | M | sharehub-svc-core | SAL-06 | API · CODE · TEST | 售后资金动作收敛 |
| [OPT-S7-15](S7-异常收费与售后.md) | P0 | S | sharehub-svc-core | SAL-06 | API · CODE · TEST | 干预操作人只取会话 |
| [OPT-S8-01](S8-开票.md) | P0 | M | sharehub-svc-platform | SYS-07 | DB · API · CODE · TEST | 税率版本化并被计算读取 |
| [OPT-S8-05](S8-开票.md) | P0 | M | sharehub-svc-finance | INV-02 | DB · API · CODE · TEST | 申请按订单、金额服务端定 |
| [OPT-S8-06](S8-开票.md) | P0 | S | sharehub-svc-finance | INV-02 | API · CODE | 开具不再回填任意地址 |
| [OPT-S9-12](S9-监控告警工单客服.md) | P0 | M | sharehub-svc-ops | OPS-02 | CODE · TEST | 工单写入统一为「原状态条件更新 + 版本递增 + 行数检查」 |
| [OPT-S9-13](S9-监控告警工单客服.md) | P0 | M | sharehub-svc-ops | OPS-02 | API · CODE · TEST | 代理承接工单的处理入口 |
| [OPT-S9-14](S9-监控告警工单客服.md) | P0 | S | sharehub-svc-ops | OPS-02 · OPS-03 | CODE · TEST | SLA 口径统一 |
| [OPT-S9-19](S9-监控告警工单客服.md) | P0 | S | sharehub-svc-ops | OPS-04 | API · DB · TEST | 报障越权 |
| [OPT-S9-22](S9-监控告警工单客服.md) | P0 | S | sharehub-svc-platform | SYS-10 | CODE · API · 跨仓 | 通知不再假发送 |
| [OPT-S10-01](S10-资产流转与撤场.md) | P0 | S | sharehub-svc-core | DEV-07 | API · CODE · TEST | 调拨「保存」只建单与改草稿单头 |
| [OPT-S10-02](S10-资产流转与撤场.md) | P0 | M | sharehub-svc-core | DEV-07 | DB · API · CODE | 仓库主数据 |
| [OPT-S10-10](S10-资产流转与撤场.md) | P0 | M | sharehub-svc-core | DEV-08 | DB · CODE | 新表 dev_movement_log |
| [OPT-S10-11](S10-资产流转与撤场.md) | P0 | M | sharehub-svc-core | DEV-08 | CODE · TEST | 写入方接入 |
| [OPT-S10-24](S10-资产流转与撤场.md) | P0 | M | sharehub-svc-core | DEV-07 | DB · API · CODE · TEST | 出库给商家（U1，一期主流程） |
| [OPT-S11-01](S11-分钱.md) | P0 | M | sharehub-svc-finance | STL-03 | DB · CODE · TEST | 确认即冻结 |
| [OPT-S11-02](S11-分钱.md) | P0 | S | sharehub-svc-finance | FIN-02 | CODE · TEST | 已出账调整项不可作废 |
| [OPT-S11-03](S11-分钱.md) | P0 | M | sharehub-svc-finance | STL-04 | API · CODE · TEST | 提现申请重写 |
| [OPT-S11-04](S11-分钱.md) | P0 | L | sharehub-svc-finance | FIN-03 | DB · API · CODE · TEST | 应付台账 |
| [OPT-S11-05](S11-分钱.md) | P0 | M | sharehub-svc-finance | STL-04 · STL-03 | DB · API · CODE · TEST | 付款登记 + 结算单 PAID 派生 |
| [OPT-S11-06](S11-分钱.md) | P0 | S | sharehub-svc-finance | STL-01 · STL-03 · FIN-02 | CODE · TEST | 场地方不进结算单（ADR-032） |
| [OPT-S11-07](S11-分钱.md) | P0 | M | sharehub-svc-finance | STL-02 | DB · CODE · TEST | 退款冲回 |
| [OPT-S12-01](S12-账务与对账.md) | P0 | M | sharehub-svc-finance | FIN-01 | DB · CODE · TEST | 凭证头表与库层只追加 |
| [OPT-S12-02](S12-账务与对账.md) | P0 | L | sharehub-svc-finance | FIN-01 | CODE · TEST | 过账引擎与写入方接入 |
| [OPT-S12-03](S12-账务与对账.md) | P0 | M | sharehub-svc-finance | FIN-01 | API · CODE · TEST | 手工凭证收紧并审批生效 |
| [OPT-S12-08](S12-账务与对账.md) | P0 | L | sharehub-svc-finance | FIN-04 | CODE · TEST | 每日对账跑批 |
| [OPT-S13-01](S13-经营分析与报表.md) | P0 | S | sharehub-svc-report 🆕 | RPT-01 | API · CODE · TEST | 运营流程指标受数据范围约束 |
| [OPT-S13-02](S13-经营分析与报表.md) | P0 | M | sharehub-svc-report 🆕 | RPT-01 · RPT-02 | CODE · API · TEST | 金额口径重定 |
| [OPT-S13-03](S13-经营分析与报表.md) | P0 | L | sharehub-svc-report 🆕 | RPT-03 | DB · API · CODE · TEST | 导出中心 |
| [OPT-S13-04](S13-经营分析与报表.md) | P0 | L | sharehub-svc-report 🆕 | RPT-02 | API · CODE | 报表目录化 + 首批 6 张 |
| [OPT-S14-01](S14-用户运营与营销.md) | P0 | S | sharehub-svc-core | CON-02 | API · CODE | 拉黑写入口迁 B 端 |
| [OPT-S14-02](S14-用户运营与营销.md) | P0 | S | sharehub-svc-core | CON-02 · SAL-01 | CODE · TEST | 借出与免押冻结拦黑名单 |
| [OPT-S14-04](S14-用户运营与营销.md) | P0 | M | sharehub-svc-core | CON-02 | API · CODE · TEST | 免单授予收紧 |
| [OPT-S14-05](S14-用户运营与营销.md) | P0 | S | sharehub-svc-core | CON-02 | DOC | 「免押白名单」更名与实现不符 |
| [OPT-S14-08](S14-用户运营与营销.md) | P0 | S | sharehub-svc-core | CON-03 | API · 前端 | 注销止血 |
| [OPT-S14-16](S14-用户运营与营销.md) | P0 | M | sharehub-svc-core | SAL-12 | DB · API · CODE · TEST | 领券重做 |
| [OPT-S15-17](S15-系统配置与基础资料.md) | P0 | M | sharehub-svc-platform | SYS-13 | DOC · 运维 · TEST | 备份落地（上线闸门） |
| [OPT-S0-02](S0-平台底座.md) | P0（上线闸门） | L | sharehub-auth 🆕 | AUTH-01 · SYS-02 · INF-02 | CODE · DB | 多副本前置 |
| [OPT-S0-33](S0-平台底座.md) | P0（上线闸门） | L | 跨模块 | 跨功能 | DB · CODE | sharehub_pii 落地 |
| [OPT-S4-01](S4-设备采购入库.md) | P0（K7 前提） | M | sharehub-svc-core | DEV-05 | DB | 迁移 |
| [OPT-S4-02](S4-设备采购入库.md) | P0（K7 前提） | M | sharehub-svc-core | DEV-05 | API · CODE · TEST | 供应商与采购单端点 |
| [OPT-S4-03](S4-设备采购入库.md) | P0（K7 前提） | S | sharehub-svc-core | DEV-05 · DEV-06 | API | 权限码 device:procurement:read / :update / :audit / :receive … |
| [OPT-S4-04](S4-设备采购入库.md) | P0（K7 前提） | S | sharehub-svc-core | DEV-06 | DB | 迁移 |
| [OPT-S4-05](S4-设备采购入库.md) | P0（K7 前提） | M | sharehub-svc-core | DEV-06 | CODE · API · TEST | 收货登记与确认事务（抢状态 → 锁行 → 条件累加实收 → 建档 IN_STOCK + PENDING → 推进采购… |
| [OPT-S4-06](S4-设备采购入库.md) | P0（K7 前提） | S | sharehub-svc-core | DEV-03 · DEV-06 | DB | dev_cabinet、dev_powerbank 加 origin（存量回填 LEGACY）、origin_ref… |
| [OPT-S4-07](S4-设备采购入库.md) | P0（K7 前提） | M | 跨模块 | 跨功能 | CODE · TEST | 统一建档服务 DeviceRegistrar |
| [OPT-S4-08](S4-设备采购入库.md) | P0（K7 前提） | S | sharehub-svc-core | DEV-06 | API | 新设备只经收货或期初入库 |
| [OPT-S5-13](S5-设备上线与归属.md) | P0（上线闸门） | S | sharehub-svc-gateway | GW-01 · DEV-11 | CODE · TEST | 指令失败关闭 |
| [OPT-S5-14](S5-设备上线与归属.md) | P0（上线闸门） | M | sharehub-svc-gateway | GW-01 · DEV-10 | CODE | 契约入 sharehub-api |
| [OPT-S5-15](S5-设备上线与归属.md) | P0（上线闸门） | L | sharehub-svc-gateway | GW-01 · DEV-11 | DB · API · CODE | 指令生命周期 |
| [OPT-S5-16](S5-设备上线与归属.md) | P0（上线闸门） | L | sharehub-svc-gateway | GW-01 | CODE · 外部 | 首家真实驱动 + http-webhook 传输 |
| [OPT-S8-10](S8-开票.md) | P0（K6 前提） | M | sharehub-svc-finance | INV-01 | DB | 票据四表迁移 |
| [OPT-S8-11](S8-开票.md) | P0（K6 前提） | M | sharehub-svc-finance | INV-01 | CODE · TEST | 取号与税额 |
| [OPT-S8-12](S8-开票.md) | P0（K6 前提） | L | sharehub-svc-finance | INV-01 | CODE · TEST | 开票规则引擎与补开 |
| [OPT-S8-13](S8-开票.md) | P0（K6 前提） | S | sharehub-svc-finance | INV-01 | API | 开票载荷 |
| [OPT-S8-16](S8-开票.md) | P0（K6 前提） | M | sharehub-svc-finance | INV-04 | DB · API · CODE · TEST | 贷项通知单 |
| [OPT-S8-18](S8-开票.md) | P0（K6 前提） | M | sharehub-svc-finance | INV-05 | CODE · DB | PDF 渲染 |
| [OPT-S11-08](S11-分钱.md) | P0（C07 前提） | S | sharehub-svc-finance | STL-02 | DB · CODE | 分润基数口径 |
| [OPT-S12-13](S12-账务与对账.md) | P0（网关前提） | M | sharehub-svc-finance | FIN-05 | DB · CODE | 支付侧三事件与镜像表 |
| [OPT-S12-14](S12-账务与对账.md) | P0（网关前提） | L | sharehub-svc-finance | FIN-05 | CODE · 跨仓 · TEST | 清算拉取、到账核销、手续费过账 |
| [OPT-S0-01](S0-平台底座.md) | P1 | M | sharehub-auth 🆕 | AUTH-01 · AUTH-02 | DB · CODE | 会话表只存 SHA-256、revoked_at 软撤销、按主体索引 |
| [OPT-S0-03](S0-平台底座.md) | P1 | S | sharehub-auth 🆕 | AUTH-01 · AUTH-02 | CODE · TEST | 登录防爆破 |
| [OPT-S0-04](S0-平台底座.md) | P1 | S | sharehub-auth 🆕 | AUTH-01 | CODE | 员工登录不产出代理会话 |
| [OPT-S0-05](S0-平台底座.md) | P1 | S | sharehub-auth 🆕 | AUTH-01 | CODE · TEST | 代理会话回查账号、商家、成员关系有效性 |
| [OPT-S0-13](S0-平台底座.md) | P1 | M | sharehub-svc-platform | SYS-02 | DB · TEST | 其余 45 张按 §二.6 分桶登记，每张配正反用例 |
| [OPT-S0-14](S0-平台底座.md) | P1 | S | sharehub-svc-platform | SYS-02 | CODE · API | 角色 |
| [OPT-S0-15](S0-平台底座.md) | P1 | S | sharehub-svc-platform | SYS-01 · SYS-02 | API | 角色分配拆独立端点、判 org:role:assign |
| [OPT-S0-16](S0-平台底座.md) | P1 | S | sharehub-svc-platform | SYS-02 | CODE | 数据范围单一真源 |
| [OPT-S0-18](S0-平台底座.md) | P1 | M | sharehub-svc-platform | SYS-04 | CODE | /mp/\*\* 写操作与认证事件（登录成败、锁定、改密、切换、登出）入审计 |
| [OPT-S0-19](S0-平台底座.md) | P1 | S | sharehub-svc-platform | SYS-04 | API | 审计查询补操作人、动作、对象类型与编号、结果、链路号、端筛选，时间区间必填 |
| [OPT-S0-23](S0-平台底座.md) | P1 | M | sharehub-common | INF-01 · INF-04 | CODE · DB | 事件信封 |
| [OPT-S0-24](S0-平台底座.md) | P1 | L | sharehub-common | INF-01 | CODE · 跨仓 | 跨进程投递器（单体 → 设备对接 / 支付服务 /internal/events/\*\*），首批事件按功能清单 7 个 |
| [OPT-S0-25](S0-平台底座.md) | P1 | M | sharehub-common | INF-02 | DB · API | sys_job_run 留痕 |
| [OPT-S0-26](S0-平台底座.md) | P1 | S | sharehub-common | INF-04 | CODE · TEST | RemoteCaller、OpsKitClient 出站带 traceparent |
| [OPT-S0-29](S0-平台底座.md) | P1 | M | 跨模块 | 跨功能 | CODE · TEST | Updates.exactlyOne + 台账 known-unchecked-updates.txt（基线 209… |
| [OPT-S0-30](S0-平台底座.md) | P1 | S | 跨模块 | 跨功能 | CODE · TEST | 更新路径不补状态 |
| [OPT-S0-32](S0-平台底座.md) | P1 | M | 跨模块 | 跨功能 | DB · CODE | 时间口径统一 UTC |
| [OPT-S1-03](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-01 | API | /apply/mine 改 POST，手机号与验证码进请求体 |
| [OPT-S1-04](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-01 | API · TEST | 列表只返回掩码与摘要 |
| [OPT-S1-05](S1-商家入驻.md) | P1 | M | sharehub-svc-platform | OPR-01 · OPR-02 | DB · API | agt_apply 与 agt_agent 加法定名称、执照号、TRN、注册地址 |
| [OPT-S1-08](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-01 | CODE | 激活改为调用账号域服务，申请模块不再直接操作其他域的 Mapper |
| [OPT-S1-10](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-03 | CODE | 「找或建自然人 + 建账号」只保留 AgentMembershipService 一份 |
| [OPT-S1-14](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-02 | CODE | save() 加事务 |
| [OPT-S1-15](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-02 | DB · CODE | 分润比例改 BigDecimal |
| [OPT-S1-17](S1-商家入驻.md) | P1 | M | sharehub-svc-platform | OPR-02 | API | 商家经营视图端点，放报表模块，读应付与分润统计 |
| [OPT-S1-19](S1-商家入驻.md) | P1 | M | sharehub-svc-platform | OPR-02 | CODE | 按包名规划拆 AgentExtController 为四个域的控制器 |
| [OPT-S1-23](S1-商家入驻.md) | P1 | M | 跨模块 | 跨功能 | CODE · TEST | Updates.exactlyOne + 新台账 known-unchecked-updates.txt（全仓落地在… |
| [OPT-S1-26](S1-商家入驻.md) | P1 | M | sharehub-svc-platform | OPR-02（OM01-06） | DB · API · CODE | 分润规则版本化与启停 |
| [OPT-S1-27](S1-商家入驻.md) | P1 | S | sharehub-svc-platform | OPR-07（OM01-09） | API | 清退列表端点（含已关闭），替代前端逐个查询 |
| [OPT-S2-02](S2-拓展与签约.md) | P1 | M | sharehub-svc-platform | NET-01 | API | 商机请求体改 LeadCreateReq / LeadUpdateReq，台账删 LocLead 与 31 条可写字段 |
| [OPT-S2-03](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-01 | API | 码族收成 location:crm:\*（新增 :create 先登记真源表） |
| [OPT-S2-04](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-01 · NET-02 | API · CODE | 转化与直建场地方的口径 |
| [OPT-S2-07](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-02 | API · CODE · TEST | 进件审核 |
| [OPT-S2-08](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-02 | API | 修改场地方遇不存在编号 404 |
| [OPT-S2-09](S2-拓展与签约.md) | P1 | M | sharehub-svc-platform | NET-02 | DB · API | 场地方实体补 regionId |
| [OPT-S2-15](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-05 | CODE · TEST | 编辑草稿检查影响行数（0 → 409，不写日志、不记字段审计） |
| [OPT-S2-16](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-05 | API | 续签 / 补充协议草稿锁定场地方与站点 |
| [OPT-S2-17](S2-拓展与签约.md) | P1 | M | sharehub-svc-platform | NET-05 | DB · API | share_rate / entry_fee 改 BigDecimal、start_at / end_at 改 Lo… |
| [OPT-S2-20](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-05 | API | 审批人与签署权限口径 |
| [OPT-S2-22](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-06（OM02-07） | DB · API · CODE · TEST | 提前量可配 |
| [OPT-S2-23](S2-拓展与签约.md) | P1 | S | sharehub-svc-platform | NET-06 | API | BD 种子补 dashboard:todo:read |
| [OPT-S2-25](S2-拓展与签约.md) | P1 | M | 跨模块 | 跨功能 | DB | 线索、场地方、进件的 contact 落 sharehub_pii，本表只存掩码 |
| [OPT-S2-26](S2-拓展与签约.md) | P1 | M | 跨模块 | 跨功能 | DB | 数据范围 |
| [OPT-S3-02](S3-建站点与点位.md) | P1 | S | sharehub-svc-platform | NET-03 ① | API · DB | 有生效 / 在途合同时禁止改场地方 |
| [OPT-S3-04](S3-建站点与点位.md) | P1 | M | sharehub-svc-platform | NET-03 ① · NET-04 | DB | loc_site / loc_location 的 agent_no 去 NULL，建站必须指定运营主体（§P3 步… |
| [OPT-S3-05](S3-建站点与点位.md) | P1 | S | sharehub-svc-platform | NET-03 ③④ | CODE · TEST | SiteOpeningPolicy 一份判据 |
| [OPT-S3-06](S3-建站点与点位.md) | P1 | M | sharehub-svc-platform | NET-03 ⑤ | CODE · 跨阶段 | 下游认站点状态 |
| [OPT-S3-07](S3-建站点与点位.md) | P1 | M | sharehub-svc-platform | NET-03 ⑤ | API · CODE | 撤场接审批（审批通过回调执行撤场） |
| [OPT-S3-10](S3-建站点与点位.md) | P1 | S | sharehub-svc-platform | NET-03 | API | 真源表补「站点」权限行 |
| [OPT-S3-13](S3-建站点与点位.md) | P1 | M | sharehub-svc-platform | NET-03 ⑥ | DB · CODE | 同站同时段至多一个生效 OPERATE |
| [OPT-S3-23](S3-建站点与点位.md) | P1 | S | sharehub-svc-platform | NET-04 | API · CODE | 请求体改 LocationCreateReq / LocationUpdateReq（不含状态） |
| [OPT-S3-24](S3-建站点与点位.md) | P1 | S | sharehub-svc-platform | NET-04 | API | 点位列表补 siteNo、status 筛选，size 上限 200 |
| [OPT-S3-25](S3-建站点与点位.md) | P1 | M | sharehub-svc-platform | NET-04 | CODE · DB | 点位端点从 OpsController 迁至 network.location 控制器与服务 |
| [OPT-S4-09](S4-设备采购入库.md) | P1 | S | sharehub-svc-core | DEV-06 | API | 在途与库存查询 |
| [OPT-S4-12](S4-设备采购入库.md) | P1 | M | sharehub-svc-core | DEV-03 | DB · API · CODE | 期初登记批次 inv_opening_batch / _item |
| [OPT-S4-14](S4-设备采购入库.md) | P1 | S | sharehub-svc-core | DEV-02 | CODE | 充电宝新建 qc_status = PENDING |
| [OPT-S4-15](S4-设备采购入库.md) | P1 | S | sharehub-svc-core | DEV-02 | DB · CODE | 确认丢失 / 解除误判 / 找回的说明落库（履历建成前写审计变更） |
| [OPT-S4-16](S4-设备采购入库.md) | P1 | M | sharehub-svc-core | DEV-02 · DEV-01 | API | 派生展示态 assetStatus（§二.3） |
| [OPT-S4-18](S4-设备采购入库.md) | P1 | M | sharehub-svc-core | DEV-04 | CODE · DB · TEST | 试借还占用与释放 |
| [OPT-S4-21](S4-设备采购入库.md) | P1 | S | 跨模块 | 跨功能 | CODE | 本阶段 9 处写后不检查行数（§二.1）按 Updates.exactlyOne 修 |
| [OPT-S5-04](S5-设备上线与归属.md) | P1 | M | sharehub-svc-core | DEV-01 | DB · CODE · API | 状态词表扩充 WITHDRAWN / RETURNED / DAMAGED / REPAIRING / LOST，撤… |
| [OPT-S5-05](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-01 | CODE · TEST | 上线门禁窗口 |
| [OPT-S5-06](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-01 | API | 新建 / 编辑拆 CabinetCreateReq / UpdateReq（台账 −2），编辑判 :update、导… |
| [OPT-S5-08](S5-设备上线与归属.md) | P1 | M | sharehub-svc-platform | OPR-05 | DB · CODE · TEST | 划拨回写保序 |
| [OPT-S5-09](S5-设备上线与归属.md) | P1 | M | sharehub-svc-platform | OPR-05 | DB · CODE | ADR-019 ③ 对账 |
| [OPT-S5-10](S5-设备上线与归属.md) | P1 | S | sharehub-svc-platform | OPR-05 | API · CODE · DB | 操作人取会话、请求体删 operator / operatorName |
| [OPT-S5-11](S5-设备上线与归属.md) | P1 | S | sharehub-svc-platform | OPR-05 | CODE · 文档 | 已裁决 A |
| [OPT-S5-17](S5-设备上线与归属.md) | P1 | M | sharehub-svc-gateway | GW-01 | CODE · DB · TEST | 网关独立部署 |
| [OPT-S5-18](S5-设备上线与归属.md) | P1 | M | sharehub-svc-gateway | GW-01 | API · DB | 厂商目录对运营端改 /api/ops/vendors\*（现网 nginx 不转发 /internal），经 Ven… |
| [OPT-S5-19](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-10 | DB · API · CODE | 未知设备 / 未知码不消费 eventId |
| [OPT-S5-20](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-10 | CODE · TEST | 在线统一由心跳窗口派生（可借判定改用派生值） |
| [OPT-S5-21](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-10 | DB · API | 监控视图改读 dev_cabinet（带范围） |
| [OPT-S5-23](S5-设备上线与归属.md) | P1 | S | sharehub-svc-core | DEV-11 | API · CODE | 远控 |
| [OPT-S5-30](S5-设备上线与归属.md) | P1 | M | sharehub-svc-core | DEV-01 | CODE · TEST | 撤机两种去向 |
| [OPT-S6-03](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-09 | CODE | 取价健壮性 |
| [OPT-S6-04](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-09 | DB · API | 方案加最长时限、遗失费（或合一）字段并进快照 |
| [OPT-S6-08](S6-定价与借还付款.md) | P1 | S | sharehub-svc-platform | SYS-05 | API · CODE | 下线「计费默认值」分区 |
| [OPT-S6-09](S6-定价与借还付款.md) | P1 | S | sharehub-svc-platform | SYS-05 | API | 业务规则权限按分区拆（提现归财务码） |
| [OPT-S6-11](S6-定价与借还付款.md) | P1 | S | sharehub-svc-platform | NET-08 | API | 确认页补披露字段（计费周期、免费时长、封顶语义、最高收费、最长时限、倍率说明） |
| [OPT-S6-13](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-01 | CODE | 计费起点改为出宝回执时刻 |
| [OPT-S6-14](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-01 | API · CODE | 借出准入 |
| [OPT-S6-15](S6-定价与借还付款.md) | P1 | M | sharehub-svc-core | SAL-01 | CODE | 借出事务边界 |
| [OPT-S6-17](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-11 | DB · CODE | 记录支付方式 |
| [OPT-S6-18](S6-定价与借还付款.md) | P1 | M | sharehub-svc-core | SAL-11 | DB · API · 跨仓 | 支付四态（含已退款）与三事件（发起 / 成功 / 到账） |
| [OPT-S6-19](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-11 | API | 支付流水查询 GET /api/trade/payments（OM04-12 / 13、OM10-06） |
| [OPT-S6-20](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-11 | CODE | pay_no / auth_no / refund_no 取号改 IdGenerator（并发撞号今天会被误判为请款… |
| [OPT-S6-22](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-02 | CODE | 收款态补偿 |
| [OPT-S6-23](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-02 | API · CODE | 内部归还端点 ReturnReq（台账 −1）、已结算返回幂等 200 |
| [OPT-S6-24](S6-定价与借还付款.md) | P1 | S | sharehub-svc-core | SAL-02 | DB · CODE | 买断后的归还不再静默 |
| [OPT-S6-26](S6-定价与借还付款.md) | P1 | M | sharehub-svc-core | SAL-03 | API · DB | 订单列表补筛选（日期、商家、站点、机柜、充电宝、客户号、支付状态、租赁状态）与索引 |
| [OPT-S6-27](S6-定价与借还付款.md) | P1 | M | sharehub-svc-core | SAL-03 | DB · API · CODE | 金额类型 |
| [OPT-S6-30](S6-定价与借还付款.md) | P1 | M | 跨模块 | 跨功能 | TEST | 资金并发与配置生效用例集 |
| [OPT-S6-31](S6-定价与借还付款.md) | P1 | S | 跨模块 | 跨功能 | DB · CODE | 本模块时间口径落地 |
| [OPT-S7-02](S7-异常收费与售后.md) | P1 | M | sharehub-svc-core | SAL-04 | CODE · TEST | 任务改造 order-max-charge |
| [OPT-S7-03](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-04 | API | GET /api/trade/orders/overdue（临近 / 已超时，带 basis）、GET /api/t… |
| [OPT-S7-04](S7-异常收费与售后.md) | P1 | M | sharehub-svc-core | SAL-04 | CODE · DB | K4 裁决后 |
| [OPT-S7-05](S7-异常收费与售后.md) | P1 | M | sharehub-svc-core | SAL-05 | DB · API · CODE · TEST | 欠费台账与自动重试 |
| [OPT-S7-06](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-05 | API · CODE | 催缴迁到欠费台账 |
| [OPT-S7-07](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-05 | DB · API · CODE | 押金单一来源 |
| [OPT-S7-08](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-05 | API · CODE | 坏账核销 |
| [OPT-S7-12](S7-异常收费与售后.md) | P1 | M | sharehub-svc-core | SAL-07 | API · 跨阶段 | RefundSucceeded 契约定稿并登记事件目录 |
| [OPT-S7-13](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-07 | API | 退款详情端点 |
| [OPT-S7-16](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-06 | API · CODE | 投诉转工单接 WorkOrderCommandPort.openFromComplaint（今天恒 500） |
| [OPT-S7-17](S7-异常收费与售后.md) | P1 | M | sharehub-svc-core | SAL-06 | DB · CODE | 异常订单生产者（重试用尽、最高收费卡住、最高收费后归还、长期在租无封顶 |
| [OPT-S7-20](S7-异常收费与售后.md) | P1 | M | 跨模块 | 跨功能 | TEST | 资金动作生效用例集 |
| [OPT-S7-23](S7-异常收费与售后.md) | P1 | S | sharehub-svc-core | SAL-07 | API · CODE · TEST | 执行失败人工重试出款 |
| [OPT-S8-03](S8-开票.md) | P1 | S | sharehub-svc-platform | SYS-07 | API | 税务、市场写接口改 Req 记录类（台账 −2 / −16） |
| [OPT-S8-07](S8-开票.md) | P1 | S | sharehub-svc-finance | INV-02 | DB · API | 抬头加地址、TRN 15 位 CHECK、InvoiceTitleReq（台账 −1 / −6） |
| [OPT-S8-08](S8-开票.md) | P1 | S | sharehub-svc-finance | INV-02 | CODE · TEST | 受理改条件更新（行数为 1，否则 409）+ 并发用例 |
| [OPT-S8-09](S8-开票.md) | P1 | S | sharehub-svc-finance | INV-02 | CODE · TEST | BizNoAllocator 字符串取号缺陷 |
| [OPT-S8-14](S8-开票.md) | P1 | M | sharehub-svc-finance | INV-01 | API | 票据查询、详情、重发（幂等、不生成新记录）、任务列表与重试 |
| [OPT-S8-15](S8-开票.md) | P1 | S | sharehub-svc-finance | INV-01 | CODE | 订阅 MaxChargeTriggered，按规则在触发时点开具 |
| [OPT-S8-17](S8-开票.md) | P1 | S | sharehub-svc-finance | INV-04 | CODE | 订阅 RefundSucceeded，按比例拆税开贷项，末笔吃尾差 |
| [OPT-S8-19](S8-开票.md) | P1 | M | sharehub-svc-finance | INV-05 | API | 批量 PDF 打包与 Excel 登记表走导出中心 |
| [OPT-S8-21](S8-开票.md) | P1 | M | sharehub-svc-finance | INV-06 | DB · API | VAT 台账 |
| [OPT-S8-22](S8-开票.md) | P1 | S | 跨模块 | 跨功能 | TEST | 配置生效用例 |
| [OPT-S8-23](S8-开票.md) | P1 | S | 跨模块 | 跨功能 | DB · CODE | 合规留存 |
| [OPT-S8-24](S8-开票.md) | P1 | S | 跨模块 | 跨功能 | API | 新权限码先登记真源表（finance:receipt:\*、finance:credit_note:\*、agent… |
| [OPT-S9-01](S9-监控告警工单客服.md) | P1 | M | sharehub-svc-ops | OPS-01 | CODE · TEST | 判定失败隔离补全 |
| [OPT-S9-02](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | CODE · TEST | 判定结果截断不得当成恢复 |
| [OPT-S9-03](S9-监控告警工单客服.md) | P1 | M | sharehub-svc-ops | OPS-01 | DB · API · CODE · TEST | 配置项收敛到「一个可写处 + 有读取方」 |
| [OPT-S9-04](S9-监控告警工单客服.md) | P1 | M | sharehub-svc-ops | OPS-01 | DB · CODE · TEST | 🆕 新增告警来源 |
| [OPT-S9-05](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | DB · CODE · TEST | OpsKit 模式处置在途不重发 |
| [OPT-S9-06](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | DB · CODE | 告警写入条件化 |
| [OPT-S9-07](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | API · CODE | autoRaiseWorkOrders 去外层事务、逐条独立并返回失败明细 |
| [OPT-S9-08](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | DB · CODE | 告警通知幂等落库 |
| [OPT-S9-09](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 · OPS-02 · OPS-03 | CODE · TEST | 时间口径 |
| [OPT-S9-15](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-02 | API · CODE · TEST | 执行人取会话（HandleReq / AcceptReq 删 assigneeNo） |
| [OPT-S9-17](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-03 | DB · API · CODE | 巡检频率认枚举（存量中文迁移） |
| [OPT-S9-20](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-04 | DB · CODE | 转工单 / 转退款条件回写 |
| [OPT-S9-23](S9-监控告警工单客服.md) | P1 | M | sharehub-svc-platform | SYS-10 | DB · API · CODE | 发送按 (scene, channel, lang) 取模板渲染 |
| [OPT-S9-24](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-platform | SYS-10 | DB · CODE · TEST | 拉黑改按 target_hash 匹配，target 只展示 |
| [OPT-S9-25](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-platform | SYS-10 | DB · API | 重发可用 |
| [OPT-S9-30](S9-监控告警工单客服.md) | P1 | S | sharehub-app | INF-03 | — | RQ-SEC-03 审批覆盖面 |
| [OPT-S9-32](S9-监控告警工单客服.md) | P1 | S | sharehub-svc-ops | OPS-01 | API · CODE · TEST | 告警强制开工单 + 转工单出参纠正 |
| [OPT-S10-03](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-07 | API · DB | GET /api/ops/inventory/balance 由设备行现算（仓 × 品类 × 型号 × 派生资产状态… |
| [OPT-S10-04](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-07 | DB · API | 调拨单加 reason_code（词表 CHECK）/ reason_note，建单必填 |
| [OPT-S10-05](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-07 | API · CODE | 建单校验两端存在、可见、可用（站点非撤场 / 关闭 / 归档） |
| [OPT-S10-06](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-07 | DB · CODE · TEST | 一件同时只在一张未完结单 |
| [OPT-S10-07](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-07 · DEV-09 | DB · CODE | 撤机后续 |
| [OPT-S10-12](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-core | DEV-08 | API | GET /api/ops/devices/{deviceNo}/movements（柜机与宝共用）与 GET /ap… |
| [OPT-S10-13](S10-资产流转与撤场.md) | P1 | M | sharehub-svc-core | DEV-08 | DB | 存量回填 |
| [OPT-S10-14](S10-资产流转与撤场.md) | P1 | M | sharehub-svc-core | DEV-09 | DB · API · CODE · TEST | 差异处置改为带审批的调整 |
| [OPT-S10-16](S10-资产流转与撤场.md) | P1 | M | sharehub-svc-core | DEV-09 | API · CODE | 撤机与巡检逐颗清点 |
| [OPT-S10-18](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-platform | OPR-07 | API · CODE | 结清门禁可达且对口 ADR-032 |
| [OPT-S10-19](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-platform | OPR-07 | API · CODE | 资产门禁补「未结订单」「已归档未撤机机柜」「点位归属」 |
| [OPT-S10-20](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-platform | OPR-07 | DB · CODE · TEST | start 加事务 |
| [OPT-S10-21](S10-资产流转与撤场.md) | P1 | S | sharehub-svc-platform | OPR-07 | API | 清退权限码 agent:exit:start / :advance / :close 先登记真源表，关闭四眼 |
| [OPT-S11-09](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-01 | API · CODE | 规则「修改」遇不存在编号 404 |
| [OPT-S11-10](S11-分钱.md) | P1 | M | sharehub-svc-finance | STL-01 | DB · API · CODE · TEST | 固定金额与死字段 |
| [OPT-S11-11](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-01 | DB | share_rule 版本化随 OPT-S1-26，本条只补 |
| [OPT-S11-12](S11-分钱.md) | P1 | M | sharehub-svc-finance | STL-02 | DB · CODE · TEST | 漏算可见 |
| [OPT-S11-13](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-03 | CODE · TEST | 迟到分润不沉底 |
| [OPT-S11-14](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-03 | CODE · TEST | 出账并发与粒度 |
| [OPT-S11-15](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-03 | API | 构成明细端点按 settle_no 精确查（现查成关键字） |
| [OPT-S11-16](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-03 · STL-04 | API | 商家视图 |
| [OPT-S11-17](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-03 | CODE | 结算单确认经 Outbox 发 SettlementConfirmed（ADR-032 接缝① |
| [OPT-S11-18](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-02 | API | 统计口径 |
| [OPT-S11-19](S11-分钱.md) | P1 | M | sharehub-svc-finance | FIN-02 | DB · API · CODE · TEST | 人工调整单 + 审批 |
| [OPT-S11-20](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-04 | CODE | 手续费 |
| [OPT-S11-21](S11-分钱.md) | P1 | M | sharehub-svc-finance | STL-05 | DB · API · CODE | 收款账户改动收紧 |
| [OPT-S11-22](S11-分钱.md) | P1 | S | sharehub-svc-finance | STL-04 | CODE | 提现 updateById 查行数（并入 OPT-S0-29 首批） |
| [OPT-S11-23](S11-分钱.md) | P1 | M | sharehub-svc-finance | INV-03 | DB · API | 结算票据关联结算单 |
| [OPT-S12-04](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-01 | API · CODE | 红冲端点 POST /api/trade/ledger/vouchers/{voucherNo}/reverse |
| [OPT-S12-05](S12-账务与对账.md) | P1 | M | sharehub-svc-finance | FIN-01 · FIN-06 | DB · API | 科目表 |
| [OPT-S12-06](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-01 | CODE · API | 删除 applyBalance，balance 列标废弃 |
| [OPT-S12-09](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-04 | API · CODE · TEST | 处置不改跑批结论 |
| [OPT-S12-10](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-04 | API · TEST | /stats 修复 |
| [OPT-S12-11](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-04 | DB | recon_task.status 扩 RUNNING / FAILED / SIMULATED + CHECK、运… |
| [OPT-S12-15](S12-账务与对账.md) | P1 | S | sharehub-svc-finance | FIN-05 | API | GET /api/trade/settlement-batches（+ 详情、明细）与 POST …/import |
| [OPT-S12-16](S12-账务与对账.md) | P1 | M | sharehub-app | FIN-06 | CODE · 跨功能 | 资金事件补齐会计字段 |
| [OPT-S13-05](S13-经营分析与报表.md) | P1 | L | sharehub-svc-report 🆕 | RPT-02 | API | 其余 8 张登记目录位并按依赖接入 |
| [OPT-S13-06](S13-经营分析与报表.md) | P1 | M | sharehub-svc-report 🆕 | RPT-02 · NET-07 | DB · CODE | 聚合键改编号 |
| [OPT-S13-07](S13-经营分析与报表.md) | P1 | S | sharehub-svc-report 🆕 | RPT-01 · RPT-02 · NET-07 | API | 所有报表与看板的区间参数解析校验、跨度 ≤ 366 天、非法 400（ops-flow-metrics、sites/… |
| [OPT-S13-08](S13-经营分析与报表.md) | P1 | M | sharehub-svc-report 🆕 | RPT-01 · RPT-02 | DB · CODE | 日汇总读模型 rpt_order_daily（事件增量 + 每日补算对平） |
| [OPT-S13-09](S13-经营分析与报表.md) | P1 | M | sharehub-svc-report 🆕 | RPT-01 | API · CODE | 看板按权限码组卡 |
| [OPT-S13-11](S13-经营分析与报表.md) | P1 | S | sharehub-svc-report 🆕 | RPT-02 · NET-07 · OPR-06 | CODE | 业务时区单一来源 |
| [OPT-S13-12](S13-经营分析与报表.md) | P1 | M | sharehub-svc-report 🆕 | RPT-02 | DB · TEST | 报表涉及表补登记 |
| [OPT-S13-13](S13-经营分析与报表.md) | P1 | S | sharehub-svc-report 🆕 | RPT-03 | DOC · API | 真源表按报表目录展开 report:<code>:read / :export，新增 report:export:m… |
| [OPT-S13-14](S13-经营分析与报表.md) | P1 | M | sharehub-svc-report 🆕 | RPT-03 | CODE | 前端 CSV 导出改调导出中心（19 个页面），lib/export-csv.ts 退役 |
| [OPT-S13-21](S13-经营分析与报表.md) | P1 | S | 跨模块 | 跨功能 | CODE · TEST | 棘轮 known-jdbc-read-paths.txt |
| [OPT-S13-24](S13-经营分析与报表.md) | P1 | S | sharehub-svc-report 🆕 | RPT-01 · RPT-02 | TEST | 口径回归 |
| [OPT-S13-25](S13-经营分析与报表.md) | P1 | S | sharehub-svc-report 🆕 | RPT-02 | DOC | 每张报表的口径说明（definition）入目录与报表页头 |
| [OPT-S14-06](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | CON-02 | DB · CODE | 信用分单一真源 |
| [OPT-S14-11](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | CON-04 | API | 钱包写入口止血 |
| [OPT-S14-13](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | CON-04 | DB · CODE · TEST | 余额条件更新（balance + ? >= 0）+ 影响行数为 1 |
| [OPT-S14-17](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | SAL-12 | CODE | 券有效期 |
| [OPT-S14-18](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | SAL-12 | CODE | 退款返还券、券成本出资方（分润基数是否加回券额）按裁决实现 |
| [OPT-S14-19](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | SAL-12 | API · CODE | 推送如实 |
| [OPT-S14-21](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | SAL-13 | API · CODE | 会员写入口改 MemberGrantReq（校验用户、方案、等级、有效期，原因必填） |
| [OPT-S14-23](S14-用户运营与营销.md) | P1 | S | sharehub-svc-core | SAL-15 | API · CODE · TEST | 公告 |
| [OPT-S14-26](S14-用户运营与营销.md) | P1 | S | 跨模块 | 跨功能 | DOC · API | 权限码对齐 |
| [OPT-S14-27](S14-用户运营与营销.md) | P1 | S | 跨模块 | 跨功能 | CODE · TEST | 本阶段资金路径 8 处 updateById 改条件更新（§二.3），其余 13 处进 known-unchecke… |
| [OPT-S15-01](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-06 | CODE · TEST | 登录设置三项接通 |
| [OPT-S15-03](S15-系统配置与基础资料.md) | P1 | M | sharehub-svc-platform | SYS-05 | CODE · API · DB | 系统参数键注册表 |
| [OPT-S15-04](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-05 | API · CODE | 资金与合规参数改判独立权限码（先登记真源表） |
| [OPT-S15-05](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-05 | CODE · API | 业务规则提现分区 |
| [OPT-S15-09](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-09 | API · CODE · DB · TEST | 应用版本发布入口 |
| [OPT-S15-10](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-09 | API · CODE · DB | 检查接口收 buildNo / deviceId、校验平台、服务端判定 hasUpdate / forceUpdat… |
| [OPT-S15-11](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-11 | CODE · TEST | 文件下载补读权限码（FileAccessChecker.readPerm()） |
| [OPT-S15-12](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-11 | CODE · DOC | 本地存储签名密钥 fail-fast（非 dev-mode 下为默认值或短于 32 字节即启动失败） |
| [OPT-S15-13](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-11 | CODE | 上传防滥用 |
| [OPT-S15-14](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-11 | DB · CODE | 保留与清理 |
| [OPT-S15-18](S15-系统配置与基础资料.md) | P1 | M | sharehub-svc-platform | SYS-13 | DB · API · CODE | 备份记录 |
| [OPT-S15-19](S15-系统配置与基础资料.md) | P1 | S | sharehub-svc-platform | SYS-13 | DOC · CODE | 正式恢复 |
| [OPT-S15-20](S15-系统配置与基础资料.md) | P1 | S | 跨模块 | 跨功能 | CODE · TEST | 配置写入的改前改后值统一入审计 |
| [OPT-S15-21](S15-系统配置与基础资料.md) | P1 | M | 跨模块 | 跨功能 | CODE · TEST | 「可配即被读」卡口 |
| [OPT-S1-20](S1-商家入驻.md) | P1（可延后） | M | sharehub-svc-platform | OPR-04 | DB · CODE | 新表 pay_merchant、MerchantStateMachine、operator.merchant 包 |
| [OPT-S1-21](S1-商家入驻.md) | P1（可延后） | L | sharehub-svc-platform | OPR-04 | API · 跨仓 | 进件端点与回调 |
| [OPT-S2-21](S2-拓展与签约.md) | P1（定 B / C 则升 P0，L） | S | sharehub-svc-platform | NET-05 · NET-06 | — | ADR-032 之后 RQ-MER-03/04/07 的「合同」指进场合同还是商家合同（§2.7） |
| [OPT-S8-02](S8-开票.md) | P1（上线闸门） | M | sharehub-svc-platform | SYS-07 | DB · API | 开票主体 fin_tax_entity（法定名称与地址英 / 阿、TRN）与编号系列配置端点 |
| [OPT-S9-27](S9-监控告警工单客服.md) | P1（启用即 P0） | S | sharehub-app | INF-03 | CODE · TEST | OpsKit 告警单 / 报障单带 site / agent / region 范围标签 |
| [OPT-S9-28](S9-监控告警工单客服.md) | P1（启用即 P0） | S | sharehub-app | INF-03 | CODE · TEST | OpsKit 降级 |
| [OPT-S10-15](S10-资产流转与撤场.md) | P1（裁决后可能升 P0） | L | sharehub-svc-core | DEV-09 | DB · API · CODE · TEST | 盘点单 |
| [OPT-S13-15](S13-经营分析与报表.md) | P1（二期） | M | sharehub-svc-platform | OPR-06 | DB · CODE | 运维考核版本化与冻结 |
| [OPT-S13-16](S13-经营分析与报表.md) | P1（二期） | S | sharehub-svc-platform | OPR-06 | CODE · TEST | 考核按 assignee_type / assignee_id 计 |
| [OPT-S14-07](S14-用户运营与营销.md) | P1（二期） | M | sharehub-svc-core | CON-02 | CODE | 信用分自动来源（结单、欠费、清欠事件落流水）与 risk_level 派生 |
| [OPT-S14-09](S14-用户运营与营销.md) | P1（二期上线闸门） | M | sharehub-svc-core | CON-03 | DB · CODE · TEST | 注销执行 |
| [OPT-S14-12](S14-用户运营与营销.md) | P1（二期启用前必须） | M | sharehub-svc-core | CON-04 | API · CODE | 人工调账重做 |
| [OPT-S14-14](S14-用户运营与营销.md) | P1（二期） | M | sharehub-svc-core | CON-04 | API · CODE | 充值接真通道前置 |
| [OPT-S14-22](S14-用户运营与营销.md) | P1（二期） | M | sharehub-svc-core | SAL-13 | CODE | 会员可配字段读取方（depositFree、月度券、积分与升级、次卡扣次、购卡） |
| [OPT-S0-06](S0-平台底座.md) | P2 | S | sharehub-auth 🆕 | AUTH-01 | CODE · API | mustChange 服务端强制 |
| [OPT-S0-07](S0-平台底座.md) | P2 | M | sharehub-auth 🆕 | AUTH-01 · AUTH-02 | CODE | 认证代码抽 sharehub-auth（纯搬迁），会话与凭据表迁 sharehub_auth |
| [OPT-S0-08](S0-平台底座.md) | P2 | S | sharehub-auth 🆕 | AUTH-02 | CODE · API | C 端 |
| [OPT-S0-09](S0-平台底座.md) | P2 | S | sharehub-svc-platform | SYS-01 | API · CODE | 部门 DeptSaveReq、修改判 org:dept:update |
| [OPT-S0-17](S0-平台底座.md) | P2 | S | sharehub-svc-platform | SYS-03 | API · CODE | 菜单写权限改 org:menu:update（先登记真源表） |
| [OPT-S0-20](S0-平台底座.md) | P2 | S | sharehub-svc-platform | SYS-04 | DB | WORM 物理加固（只授 INSERT / SELECT 或触发器） |
| [OPT-S0-27](S0-平台底座.md) | P2 | S | sharehub-common | INF-04 | TEST | LoggingConventionTest 增「异常日志须带业务键」「敏感字段须掩码」两条启发式检查 |
| [OPT-S0-31](S0-平台底座.md) | P2 | S | 跨模块 | 跨功能 | DB | 可空唯一键 |
| [OPT-S1-06](S1-商家入驻.md) | P2 | S | sharehub-svc-platform | OPR-01 | CODE | ApplyStateMachine |
| [OPT-S1-07](S1-商家入驻.md) | P2 | S | sharehub-svc-platform | OPR-01 | CODE | 审核结果通知申请人 |
| [OPT-S1-11](S1-商家入驻.md) | P2 | S | sharehub-svc-platform | OPR-03 | DB · API | 停写 username / login_phone |
| [OPT-S1-16](S1-商家入驻.md) | P2 | S | sharehub-svc-platform | OPR-02 | API | 删除恒为 0 的「机柜数」出参 |
| [OPT-S1-18](S1-商家入驻.md) | P2 | S | sharehub-svc-platform | OPR-02 | API | 列表补类型、状态、归档筛选 |
| [OPT-S1-22](S1-商家入驻.md) | P2 | S | 跨模块 | 跨功能 | TEST | 卡口扩展 |
| [OPT-S1-24](S1-商家入驻.md) | P2 | S | 跨模块 | 跨功能 | DB | 推广 active_key 生成列做法 |
| [OPT-S2-05](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-01 | CODE | 线索任务时间走 BizClock |
| [OPT-S2-06](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-01 · NET-05 | DB | 索引 |
| [OPT-S2-10](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-02 | CODE | 进件状态枚举与状态机、错误码三语（台账 3 条） |
| [OPT-S2-18](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-05 | DB · CODE | 同站点至多一份 ACTIVE |
| [OPT-S2-19](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-05 | CODE | 时间走 BizClock（11 处） |
| [OPT-S2-24](S2-拓展与签约.md) | P2 | S | sharehub-svc-platform | NET-06 | CODE | 在途续签只认主合同且起期晚于本合同止期 |
| [OPT-S2-27](S2-拓展与签约.md) | P2 | S | 跨模块 | 跨功能 | TEST | 实体请求体卡口识别所有 @TableName 类，LocVenue 入台账后迁出 |
| [OPT-S2-28](S2-拓展与签约.md) | P2 | M | 跨模块 | 跨功能 | CODE | 包归位 |
| [OPT-S3-03](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ① | DB · CODE | loc_site.status 默认值改 PREPARING |
| [OPT-S3-08](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑤ | CODE | 撤场为在库绑定机柜开解绑回仓任务 |
| [OPT-S3-09](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑤ | CODE | RentabilityGuard 站点缺失或 Port 不可用时拒借（fail-closed） |
| [OPT-S3-14](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑥ | API · CODE | 责任请求体改 SiteAgentReq |
| [OPT-S3-15](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑦ | CODE | 生命周期与漏斗改 SQL 聚合与数据库分页，移入报表读模型 |
| [OPT-S3-16](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑦ | CODE | 单站统计按订单快照 site_no 归属 |
| [OPT-S3-17](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 ⑦ | DB · CODE | loc_site_lifecycle / _log 改名 \*_deprecated，删实体与 Mapper |
| [OPT-S3-18](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 · NET-04 | TEST | 已有枚举校验的 7 个词表列补 VocabularyRejectedOnWriteTest 行后出账 |
| [OPT-S3-19](S3-建站点与点位.md) | P2 | M | sharehub-svc-platform | NET-03 | CODE | 拆 SiteServiceImpl 为档案 / 生命周期 / 勘察三个服务 |
| [OPT-S3-20](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-03 | API | /internal/platform/sites/briefs 纳入服务凭证前缀 |
| [OPT-S3-26](S3-建站点与点位.md) | P2 | S | sharehub-svc-platform | NET-04 | DB | 点位、勘察、状态日志的 REGION 数据范围 |
| [OPT-S4-10](S4-设备采购入库.md) | P2 | S | sharehub-svc-core | DEV-06 | CODE | 外部来源预留 |
| [OPT-S4-17](S4-设备采购入库.md) | P2 | S | sharehub-svc-core | DEV-02 | DB · API | dev_powerbank.status CHECK、qc_status 进词表（台账 −1） |
| [OPT-S4-19](S4-设备采购入库.md) | P2 | S | sharehub-svc-core | DEV-04 | API · DB | 码批次 CodeBatchReq、作废动作端点、号段 CHECK 与不重叠校验、total 服务端算 |
| [OPT-S4-20](S4-设备采购入库.md) | P2 | S | sharehub-svc-core | DEV-04 | API | 码批次与质检记录改挂运营专属读码，代理不可读（随真源表已知不一致 #4） |
| [OPT-S5-07](S5-设备上线与归属.md) | P2 | S | sharehub-svc-core | DEV-01 | CODE | 归档检查行数 |
| [OPT-S5-12](S5-设备上线与归属.md) | P2 | S | sharehub-svc-platform | OPR-05 | API | POST /api/agent/assignments/batch（一个事务） |
| [OPT-S5-22](S5-设备上线与归属.md) | P2 | S | sharehub-svc-core | DEV-10 | API | 设备日志双流 UNION（指令表 + 报文表，from / to 必填下推分区） |
| [OPT-S5-24](S5-设备上线与归属.md) | P2 | S | sharehub-svc-core | DEV-11 | API | 批量指令端点并强制 device:command:batch（台账 −1） |
| [OPT-S5-26](S5-设备上线与归属.md) | P2 | S | sharehub-svc-gateway | DEV-12 | API | OTA 请求体改 Req |
| [OPT-S5-28](S5-设备上线与归属.md) | P2 | S | 跨模块 | 跨功能 | TEST | 卡口两处盲区 |
| [OPT-S6-05](S6-定价与借还付款.md) | P2 | S | sharehub-svc-core | SAL-09 | API · DB | 改方案 / 范围判 pricing:plan:update |
| [OPT-S6-06](S6-定价与借还付款.md) | P2 | S | sharehub-svc-core | SAL-09 · NET-08 | CODE | 取价范围行与 ACTIVE 方案集合 30 秒缓存，保存即失效 |
| [OPT-S6-07](S6-定价与借还付款.md) | P2 | S | sharehub-svc-core | SAL-10 | API · CODE | 调价单 Req 记录类（台账 −2） |
| [OPT-S6-28](S6-定价与借还付款.md) | P2 | S | sharehub-svc-core | SAL-03 | API · CODE | IN_USE 行附实时试算 |
| [OPT-S6-29](S6-定价与借还付款.md) | P2 | S | sharehub-svc-core | CON-01 | API | 用户列表 orders 改为真实计数或删除 |
| [OPT-S7-18](S7-异常收费与售后.md) | P2 | S | sharehub-svc-core | SAL-08 | API | 预约「未启用」显式化 |
| [OPT-S7-19](S7-异常收费与售后.md) | P2 | S | sharehub-svc-core | SAL-08 | API · CODE | 免费订单并入已执行的人工减免（MANUAL_WAIVE） |
| [OPT-S7-21](S7-异常收费与售后.md) | P2 | S | 跨模块 | 跨功能 | TEST | 对齐脚本补「请求体字段名」维度，冻结 §二.5 的 4 条后逐条消除 |
| [OPT-S7-22](S7-异常收费与售后.md) | P2 | S | 跨模块 | 跨功能 | API · DB | 台账收敛 |
| [OPT-S8-04](S8-开票.md) | P2 | M | sharehub-svc-platform | SYS-07 | DB · CODE | 市场加 minor_unit |
| [OPT-S8-20](S8-开票.md) | P2 | S | sharehub-svc-finance | INV-05 | CODE | 结算对账单改用同一渲染器出服务端 PDF |
| [OPT-S9-10](S9-监控告警工单客服.md) | P2 | M | sharehub-svc-ops | OPS-01 | API · CODE | 三语与杂项 |
| [OPT-S9-11](S9-监控告警工单客服.md) | P2 | S | sharehub-svc-ops | OPS-01 | CODE · TEST | 安全域「误报」关闭同时释放对应信号锁仓（或误报须先现场确认），消除锁仓兜底的反复补开 |
| [OPT-S9-16](S9-监控告警工单客服.md) | P2 | S | sharehub-svc-ops | OPS-02 | DB · CODE | 工单成本币种取市场、成本进站点效益读模型 |
| [OPT-S9-18](S9-监控告警工单客服.md) | P2 | S | sharehub-svc-ops | OPS-03 | API | SLA 规则出参补 priority、resolveEscalateTo |
| [OPT-S9-21](S9-监控告警工单客服.md) | P2 | S | sharehub-svc-ops | OPS-04 | API · CODE | C 端转工单频控与并单 |
| [OPT-S9-26](S9-监控告警工单客服.md) | P2 | S | sharehub-svc-platform | SYS-10 · 跨功能 | API | blockedAt / expireAt 服务端定、BlockReq |
| [OPT-S9-29](S9-监控告警工单客服.md) | P2 | S | sharehub-app | INF-03 | CODE · 跨仓 | 审批结论按 instanceNo 认单 |
| [OPT-S9-31](S9-监控告警工单客服.md) | P2 | S | 跨模块 | 跨功能 | DB · API | 待办 |
| [OPT-S10-08](S10-资产流转与撤场.md) | P2 | S | sharehub-svc-core | DEV-07 | CODE | 调拨端点收进 device.inventory 一个控制器（路径不变） |
| [OPT-S10-09](S10-资产流转与撤场.md) | P2 | S | sharehub-svc-core | DEV-07 | DB · TEST | inv_warehouse（REGION）、inv_transfer（经两端派生的 site_no / wareho… |
| [OPT-S10-17](S10-资产流转与撤场.md) | P2 | S | sharehub-svc-core | DEV-09 | DB · API | inv_asset_diff 加 resolution / proposed_\* / approved_\* / … |
| [OPT-S10-22](S10-资产流转与撤场.md) | P2 | S | sharehub-svc-platform | OPR-07 | CODE | 考核口径 |
| [OPT-S10-23](S10-资产流转与撤场.md) | P2 | S | 跨模块 | 跨功能 | CODE | 本阶段其余写后不检查行数处（§2.5）按 Updates.exactlyOne 修 |
| [OPT-S11-24](S11-分钱.md) | P2 | S | 跨模块 | 跨功能 | TEST | 「改配置 → 新单 → 金额变化」补两条 |
| [OPT-S11-25](S11-分钱.md) | P2 | S | sharehub-svc-finance | STL-02 | CODE | 牵线费按签约时刻定生效日与账期 |
| [OPT-S11-26](S11-分钱.md) | P2 | S | sharehub-svc-finance | STL-03 | DB · CODE | 结算单按币种分组，uk_settle_period 扩 currency |
| [OPT-S11-27](S11-分钱.md) | P2 | S | 跨模块 | 跨功能 | CODE | 中文错误字面量与裸状态（台账命中）改错误码 / 枚举 |
| [OPT-S11-28](S11-分钱.md) | P2 | S | sharehub-svc-finance | STL-02 | TEST · 文档 | 舍入口径成文并加用例 |
| [OPT-S11-29](S11-分钱.md) | P2 | S | sharehub-svc-finance | STL-02 | CODE | 分润明细列表无上限的内存分页改 SQL 子查询计数 |
| [OPT-S11-30](S11-分钱.md) | P2 | M | 跨模块 | 跨功能 | CODE | 按包名规划归位 |
| [OPT-S12-07](S12-账务与对账.md) | P2 | S | sharehub-svc-finance | FIN-01 | API · CODE | 分录列表补日期区间、来源、凭证状态筛选 |
| [OPT-S12-12](S12-账务与对账.md) | P2 | S | sharehub-svc-finance | FIN-01 · FIN-04 | CODE · TEST | acct_\* / recon_\* 登记为平台级（带范围会话拒绝，台账 −1） |
| [OPT-S12-17](S12-账务与对账.md) | P2 | S | sharehub-app | FIN-06 | 文档 | 在契约规划登记 AccountingPort（方法、以 voucher_no / event_no / accoun… |
| [OPT-S13-10](S13-经营分析与报表.md) | P2 | S | sharehub-svc-report 🆕 | RPT-01 | CODE | 看板端点迁 report.dashboard |
| [OPT-S13-19](S13-经营分析与报表.md) | P2 | S | sharehub-svc-report 🆕 | RPT-02 | API | /reports/screen 改为 ScreenKpi[]（不包 PageResult） |
| [OPT-S13-20](S13-经营分析与报表.md) | P2 | S | sharehub-svc-report 🆕 | RPT-02 | CODE | 指标目录只在后端，前端经 /reports/catalog 取 |
| [OPT-S13-22](S13-经营分析与报表.md) | P2 | S | 跨模块 | 跨功能 | CODE | 「端点先通」统一 501 |
| [OPT-S13-23](S13-经营分析与报表.md) | P2 | S | sharehub-svc-report 🆕 | RPT-02 | CODE | 多币种 |
| [OPT-S13-26](S13-经营分析与报表.md) | P2 | S | sharehub-svc-report 🆕 | RPT-03 | DB · CODE | 导出文件进 sharehub_pii 同区域的个人数据桶 |
| [OPT-S14-03](S14-用户运营与营销.md) | P2 | S | sharehub-svc-core | CON-02 | DB · CODE | usr_blacklist 加 active_key 唯一键与 release_reason |
| [OPT-S14-10](S14-用户运营与营销.md) | P2 | S | sharehub-svc-core | CON-03 | DB · API | 撤销记 cancelled_by / cancel_reason（客服代撤与自助撤销可区分） |
| [OPT-S14-20](S14-用户运营与营销.md) | P2 | S | sharehub-svc-core | SAL-12 | API | 活动、裂变二期前 501 或运营端标「未启用」 |
| [OPT-S14-24](S14-用户运营与营销.md) | P2 | S | sharehub-svc-core | SAL-15 | DB · API | 公告按市场定向（多市场前） |
| [OPT-S14-28](S14-用户运营与营销.md) | P2 | S | 跨模块 | 跨功能 | DB · TEST | usr_\*、mkt_\*、usr_coupon、usr_membership、usr_wallet\*、usr_r… |
| [OPT-S14-29](S14-用户运营与营销.md) | P2 | S | 跨模块 | 跨功能 | TEST | 卡口 known-unread-config-fields.txt（§二.4），基线 11，只准变短 |
| [OPT-S15-02](S15-系统配置与基础资料.md) | P2 | S | sharehub-svc-platform | SYS-06 | API | LoginSettingReq（台账 −1 / −9） |
| [OPT-S15-06](S15-系统配置与基础资料.md) | P2 | S | sharehub-svc-platform | SYS-08 | DB · API | 字典 |
| [OPT-S15-07](S15-系统配置与基础资料.md) | P2 | M | sharehub-svc-platform | SYS-08 | CODE · API | 引用完整性 |
| [OPT-S15-08](S15-系统配置与基础资料.md) | P2 | S | sharehub-svc-platform | SYS-08 | API · CODE | FAQ 排除已归档 |
| [OPT-S15-15](S15-系统配置与基础资料.md) | P2 | S | sharehub-svc-platform | SYS-11 | CODE · API | 三个死用途 |
| [OPT-S15-16](S15-系统配置与基础资料.md) | P2 | S | sharehub-svc-platform | SYS-12 | API | OpenAPI 冻结 |
| [OPT-S15-22](S15-系统配置与基础资料.md) | P2 | S | 跨模块 | 跨功能 | DOC | 真源表登记与对齐 |
| [OPT-S5-25](S5-设备上线与归属.md) | P2（二期） | L | sharehub-svc-gateway | DEV-12 | DB · CODE | OTA 迁入网关 |
| [OPT-S13-17](S13-经营分析与报表.md) | P2（二期） | S | sharehub-svc-platform | OPR-06 | API | /api/agent/performance 并入 GET /api/ops/reports/operators（O… |
| [OPT-S13-18](S13-经营分析与报表.md) | P2（三期） | S | sharehub-svc-platform | NET-07 | API | 坪效 |
| [OPT-S14-15](S14-用户运营与营销.md) | P2（二期） | M | sharehub-svc-core | CON-04 | DOC · CODE | 储值的出口设计 |
| [OPT-S14-25](S14-用户运营与营销.md) | P2（三期） | S | sharehub-svc-core | SAL-14 | API | 广告三期前 501 或标「未启用」 |


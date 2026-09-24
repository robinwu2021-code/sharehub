# 接口参考（全量 408 个端点）

> **本文件由脚本生成，不要手改**：
>
> ```bash
> python3 backend/scripts/api-extract.py   # 从控制器源码抽真值
> python3 backend/scripts/gen-api-doc.py    # 渲染本文件
> ```
>
> 所以它不可能与代码不一致 —— 它就是代码的另一种呈现。**接口该怎么设计**（前缀划分、权限码约定、文档与代码冲突谁赢）看 [README.md](./README.md)。

## 怎么读

| 约定 | 说明 |
|---|---|
| 出参包装 | `ApiResponseWrapper` 把 **`ai.neargo.sharehub` 下所有控制器**的返回值（含 `/internal`、`/gw`，不只是 `/api`）包成 `{code, message, data}`，`code=0` 为成功；返回值已是 `Result` 的原样透传。**下文「出参」写的是 `data` 的形状**，不含这层壳 |
| 分页 | `分页<T>` = `{list: T[], total: number}`；入参统一 `page`（从 1 起）与 `size`（默认 10，上限 200，见 `AbstractCrudService`） |
| 时间 | 一律字符串 `yyyy-MM-dd HH:mm:ss`，不用时间戳（前端直接展示，不做时区换算） |
| 金额 | 一律带 2 位小数的数字 + 独立 `currency` 字段，**不用浮点做计算** |
| 权限 | 「权限码」列即 `@PreAuthorize("@perm.can(...)")`。缺权限码 → 403；未登录 → 401 |
| 受信头 | `X-Merchant-Id`（租户）/ `X-User-Id` / `X-Roles` 由边缘注入，**客户端同名头一律剥离**；业务代码只读不写 |

## 覆盖情况

| | |
|---|---|
| 端点 | **408** |
| 带功能权限码 | 348 |
| 有请求体 | 170 |
| 数据结构 | 218 个（文末统一定义） |

### ⚠️ 3 个 `/api/**` 端点没有功能权限码

它们**不是匿名可读** —— `SecurityConfig` 对 `/api/**` 是 `anyRequest().authenticated()`，未登录仍是 401。但没有 `@PreAuthorize` 意味着**任何已登录员工都能读**，不分角色：客服能读账务分录、能读员工名册、能读审计日志。

这与「四层权限」的设计（认证 → 功能权限 → 数据范围 → 属主守卫）第二层缺失，全部落在待拆分的旧骨架控制器上。

| 端点 | 落在 |
|---|---|
| `POST /api/agent/apply` | `AgentApplyController#selfServiceApply` |
| `GET /api/agent/apply/mine` | `AgentApplyController#mine` |
| `POST /api/agent/apply/otp` | `AgentApplyController#applyOtp` |

---


## 登录与会话

8 个端点。

### `POST /api/auth/login`

登录出参。

**无权限码** · `AuthController#login`

**请求体** [`LoginReq`](#loginreq)

**出参** [`LoginResp`](#loginresp)

### `POST /api/auth/logout`

**无权限码** · `AuthController#logout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 请求头 | `auth` | `String` | 否 |

**出参** `对象（自由键）`

### `GET /api/auth/me`

**无权限码** · `AuthController#me`

**出参** `对象（自由键）`

### `GET /api/auth/menus`

当前登录人可见菜单树（动态，随权限增减）。

**无权限码** · `AuthController#menus`

**出参** 数组<[`MenuNode`](#menunode)>

### `GET /api/auth/operators`

我的运营主体列表（必要功能清单 ⑤ / ADR-030 §2.2）。

**无权限码** · `AuthController#operators`

**出参** 数组<[`OperatorMembership`](#operatormembership)>

### `POST /api/auth/operators/{agentNo}/switch`

切换当前运营主体：**换发 token**，而不是改会话里的一个字段。

**无权限码** · `AuthController#switchOperator`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |
| 请求头 | `auth` | `String` | 否 |

**出参** [`LoginResp`](#loginresp)

### `POST /api/auth/otp`

发送代理端登录验证码（匿名）。

**无权限码** · `AuthController#loginOtp`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `GET /api/auth/permissions`

当前登录人权限码集合（前端 can() 用）。

**无权限码** · `AuthController#permissions`

**出参** `List<String>`


## 运营：设备 · 场地 · 工单 · 告警 · 库存

127 个端点。

### `GET /api/ops/ad-slots`

权限码 `marketing:ad:read` · `MarketingController#adSlots`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |
| 查询 | `position` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AdSlotVO`](#adslotvo)>

### `POST /api/ops/ad-slots`

权限码 `marketing:ad:update` · `MarketingController#createAdSlot`

**请求体** `AdSlot`

**出参** [`AdSlotVO`](#adslotvo)

### `POST /api/ops/ad-slots/{slotNo}`

权限码 `marketing:ad:update` · `MarketingController#updateAdSlot`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slotNo` | `String` | 是 |

**请求体** `AdSlot`

**出参** [`AdSlotVO`](#adslotvo)

### `POST /api/ops/alarms/auto-work-orders`

未处理告警批量自动开单。

权限码 `workorder:alarm:update` · `AlarmController#autoRaiseWorkOrders`

**出参** `Object`

### `GET /api/ops/alarms/codes`

权限码 `workorder:wo:read` · `AlarmController#codes`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `level` | `String` | 否 |
| 查询 | `autoWorkOrder` | `String` | 否 |

**出参** 分页<[`AlarmCode`](#alarmcode)>

### `POST /api/ops/alarms/codes`

权限码 `workorder:wo:update` · `AlarmController#createCode`

**请求体** `DevAlarmCode`

**出参** [`AlarmCode`](#alarmcode)

### `POST /api/ops/alarms/codes/{code}`

权限码 `workorder:wo:update` · `AlarmController#updateCode`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `code` | `String` | 是 |

**请求体** `DevAlarmCode`

**出参** [`AlarmCode`](#alarmcode)

### `POST /api/ops/alarms/codes/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `workorder:alarm:update` · `AlarmController#archiveAlarmCode`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/alarms/codes/{no}/unarchive`

取消归档AlarmCode：清空时间戳，回到默认列表。

权限码 `workorder:alarm:update` · `AlarmController#unarchiveAlarmCode`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/alarms/notices`

权限码 `workorder:wo:read` · `AlarmController#notices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `alarmNo` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AlarmNotice`](#alarmnotice)>

### `POST /api/ops/alarms/notices/{noticeNo}/resend`

重发告警通知。

权限码 `workorder:alarm:update` · `AlarmController#resendAlarmNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `noticeNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `GET /api/ops/alarms/records`

权限码 `workorder:wo:read` · `AlarmController#records`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `level` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |

**出参** 分页<[`AlarmRecord`](#alarmrecord)>

### `POST /api/ops/alarms/records/{alarmNo}/ack`

确认告警（OPEN → ACKED）。

权限码 `workorder:wo:read` · `AlarmController#ack`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`AckResult`](#ackresult)

### `POST /api/ops/alarms/records/{alarmNo}/work-order`

一键转工单，**以 `alarmNo` 为幂等键**（[api/README §3.3]）。

权限码 `workorder:wo:create` · `AlarmController#toWorkOrder`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**出参** [`WorkOrderRef`](#workorderref)

### `GET /api/ops/alarms/rules`

权限码 `workorder:wo:read` · `AlarmController#rules`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `alarmCode` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `method` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AlarmRule`](#alarmrule)>

### `POST /api/ops/alarms/rules`

权限码 `workorder:wo:update` · `AlarmController#createRule`

**请求体** `DevAlarmRule`

**出参** [`AlarmRule`](#alarmrule)

### `POST /api/ops/alarms/rules/{no}/archive`

归档AlarmRule。

权限码 `workorder:alarm:update` · `AlarmController#archiveAlarmRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/alarms/rules/{no}/unarchive`

取消归档AlarmRule：清空时间戳，回到默认列表。

权限码 `workorder:alarm:update` · `AlarmController#unarchiveAlarmRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/alarms/rules/{ruleNo}`

权限码 `workorder:wo:update` · `AlarmController#updateRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** `DevAlarmRule`

**出参** [`AlarmRule`](#alarmrule)

### `GET /api/ops/cabinet-monitor`

权限码 `device:cabinet:read` · `DeviceController#cabinetMonitor`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `online` | `Boolean` | 否 |

**出参** 分页<[`CabinetMonitorRow`](#cabinetmonitorrow)>

### `GET /api/ops/cabinets`

权限码 `device:cabinet:read` · `OpsController#cabinets`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `onlineStatus` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`Cabinet`](#cabinet)>

### `POST /api/ops/cabinets`

机柜建档 / 编辑。

权限码 `device:cabinet:create` · `OpsController#saveCabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/ops/cabinets/import`

批量导入机柜。

权限码 `device:cabinet:create` · `OpsController#importCabinets`

**请求体** `对象（自由键）`

**出参** `Object`

### `GET /api/ops/cabinets/{cabinetNo}`

权限码 `device:cabinet:read` · `OpsController#cabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**出参** [`CabinetDetail`](#cabinetdetail)

### `POST /api/ops/cabinets/{cabinetNo}`

机柜建档 / 编辑。

权限码 `device:cabinet:create` · `OpsController#saveCabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/ops/cabinets/{cabinetNo}/commands`

权限码 `device:command:send` · `OpsController#sendCommand`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`CommandResult`](#commandresult)

### `POST /api/ops/cabinets/{no}/archive`

归档Cabinet。

权限码 `device:cabinet:update` · `OpsController#archiveCabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/cabinets/{no}/unarchive`

取消归档Cabinet：清空时间戳，回到默认列表。

权限码 `device:cabinet:update` · `OpsController#unarchiveCabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/command-records`

权限码 `device:command:send` · `InventoryController#commandRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |
| 查询 | `type` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CommandRecord`](#commandrecord)>

### `GET /api/ops/contracts`

权限码 `location:contract:read` · `OpsController#contracts`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Contract`](#contract)>

### `POST /api/ops/contracts`

新建 / 修改进场合同。

权限码 `location:contract:create` · `OpsController#createContract`

**请求体** `LocContract`

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}`

权限码 `location:contract:update` · `OpsController#updateContract`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** `LocContract`

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/attachments`

添加合同附件元数据。

权限码 `location:contract:update` · `LocExtController#addContractAttachment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/ops/contracts/{contractNo}/attachments/{attachNo}/remove`

移除合同附件。

权限码 `location:contract:update` · `LocExtController#removeContractAttachment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |
| 路径 | `attachNo` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/cs/sessions`

权限码 `cs:session:read` · `CsController#sessions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CsSessionVO`](#cssessionvo)>

### `GET /api/ops/cs/sessions/{sessionNo}/messages`

权限码 `cs:session:read` · `CsController#messages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `sessionNo` | `String` | 是 |
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`CsMessageVO`](#csmessagevo)>

### `POST /api/ops/cs/sessions/{sessionNo}/messages`

客服回复。

权限码 `cs:session:reply` · `CsController#reply`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `sessionNo` | `String` | 是 |

**请求体** [`ReplyReq`](#replyreq)

**出参** [`CsMessageVO`](#csmessagevo)

### `GET /api/ops/cs/tickets`

权限码 `cs:ticket:read` · `CsController#tickets`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |

**出参** 分页<[`CsTicketVO`](#csticketvo)>

### `POST /api/ops/cs/tickets`

受理 / 更新（状态、处理人、补充描述）。

权限码 `cs:ticket:handle` · `CsController#createTicket`

**请求体** [`TicketCreateReq`](#ticketcreatereq)

**出参** [`CsTicketVO`](#csticketvo)

### `POST /api/ops/cs/tickets/{ticketNo}`

权限码 `cs:ticket:update` · `CsController#updateTicket`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ticketNo` | `String` | 是 |

**请求体** [`TicketUpdateReq`](#ticketupdatereq)

**出参** [`CsTicketVO`](#csticketvo)

### `POST /api/ops/cs/tickets/{ticketNo}/refund`

出口②：报障 → 转退款申请。

权限码 `order:refund:apply` · `CsController#toRefund`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ticketNo` | `String` | 是 |

**出参** [`CsTicketVO`](#csticketvo)

### `POST /api/ops/cs/tickets/{ticketNo}/work-order`

出口①：报障 → 转工单。

权限码 `workorder:wo:create` · `CsController#toWorkOrder`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ticketNo` | `String` | 是 |

**出参** [`CsTicketVO`](#csticketvo)

### `GET /api/ops/dashboard`

工作台聚合：真表实算（报表域读模型），内存种子骨架已退役。

权限码 `dashboard:overview:read` · `OpsController#dashboard`

**出参** [`DashboardStats`](#dashboardstats)

### `GET /api/ops/device-code-batches`

权限码 `device:cabinet:read` · `DeviceController#codeBatches`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `vendorCode` | `String` | 否 |
| 查询 | `codeType` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CodeBatchRow`](#codebatchrow)>

### `POST /api/ops/device-code-batches`

权限码 `device:cabinet:update` · `DeviceController#createCodeBatch`

**请求体** `DevCodeBatch`

**出参** [`CodeBatchRow`](#codebatchrow)

### `POST /api/ops/device-code-batches/{batchNo}`

权限码 `device:cabinet:update` · `DeviceController#updateCodeBatch`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `batchNo` | `String` | 是 |

**请求体** `DevCodeBatch`

**出参** [`CodeBatchRow`](#codebatchrow)

### `GET /api/ops/device-logs`

双流日志（`stream=COMMAND|REPORT` + `from/to`）。

权限码 `device:cabinet:read` · `DeviceController#deviceLogs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |
| 查询 | `stream` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 分页<[`DeviceLogRow`](#devicelogrow)>

### `GET /api/ops/inspection-plans`

权限码 `workorder:wo:read` · `WoExtController#inspectionPlans`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `active` | `String` | 否 |

**出参** 分页<[`InspectionPlan`](#inspectionplan)>

### `POST /api/ops/inspection-plans`

权限码 `workorder:wo:update` · `WoExtController#createInspectionPlan`

**请求体** `WoInspectionPlan`

**出参** [`InspectionPlan`](#inspectionplan)

### `GET /api/ops/inspection-plans/{planNo}`

权限码 `workorder:wo:read` · `WoExtController#inspectionPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**出参** [`InspectionPlan`](#inspectionplan)

### `POST /api/ops/inspection-plans/{planNo}`

权限码 `workorder:wo:update` · `WoExtController#updateInspectionPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**请求体** `WoInspectionPlan`

**出参** [`InspectionPlan`](#inspectionplan)

### `POST /api/ops/inspection-plans/{planNo}/run`

立即执行一次巡检计划。

权限码 `workorder:inspection:update` · `WoExtController#runInspectionPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/inventory-transfers`

权限码 `device:inventory:read` · `InventoryController#transfers`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `itemType` | `String` | 否 |

**出参** 分页<[`InventoryTransfer`](#inventorytransfer)>

### `POST /api/ops/inventory-transfers`

建调拨单（落 DRAFT）。

权限码 `device:inventory:transfer` · `InventoryController#createTransfer`

**请求体** `InvTransfer`

**出参** [`InventoryTransfer`](#inventorytransfer)

### `GET /api/ops/inventory-transfers/{transferNo}`

单据详情（含逐件明细，收货核对用）。

权限码 `device:inventory:read` · `InventoryController#transfer`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `transferNo` | `String` | 是 |

**出参** [`InventoryTransferDetail`](#inventorytransferdetail)

### `POST /api/ops/inventory-transfers/{transferNo}`

更新调拨单：发出（→IN_TRANSIT）/ 收货（→DONE）/ 草稿期改单头。

权限码 `device:inventory:transfer` · `InventoryController#updateTransfer`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `transferNo` | `String` | 是 |

**请求体** `InvTransfer`

**出参** [`InventoryTransfer`](#inventorytransfer)

### `GET /api/ops/leads`

权限码 `location:crm:read` · `LocExtController#leads`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `stage` | `String` | 否 |
| 查询 | `owner` | `String` | 否 |

**出参** 分页<[`Lead`](#lead)>

### `POST /api/ops/leads`

权限码 `location:crm:update` · `LocExtController#createLead`

**请求体** `LocLead`

**出参** [`Lead`](#lead)

### `GET /api/ops/leads/{leadNo}`

权限码 `location:crm:read` · `LocExtController#lead`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |

**出参** [`Lead`](#lead)

### `POST /api/ops/leads/{leadNo}`

权限码 `location:crm:update` · `LocExtController#updateLead`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |

**请求体** `LocLead`

**出参** [`Lead`](#lead)

### `GET /api/ops/leads/{leadNo}/follow-ups`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `location:lead:read` · `LocExtController#leadFollowUps`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** `Object`

### `POST /api/ops/leads/{leadNo}/follow-ups`

记一条跟进，**可同时推进线索阶段**（同事务）。

权限码 `location:lead:update` · `LocExtController#addLeadFollowUp`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |

**请求体** [`LeadFollowUpReq`](#leadfollowupreq)

**出参** `Object`

### `GET /api/ops/locations`

权限码 `location:poi:read` · `OpsController#locations`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Location`](#location)>

### `POST /api/ops/locations`

权限码 `location:poi:create` · `OpsController#savePoint`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `locationNo` | `String` | 是 |

**请求体** [`Location`](#location)

**出参** [`Location`](#location)

### `POST /api/ops/locations/{locationNo}`

权限码 `location:poi:create` · `OpsController#savePoint`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `locationNo` | `String` | 是 |

**请求体** [`Location`](#location)

**出参** [`Location`](#location)

### `POST /api/ops/locations/{no}/archive`

权限码 `location:poi:update` · `OpsController#archiveLocation`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/locations/{no}/unarchive`

权限码 `location:poi:update` · `OpsController#unarchiveLocation`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/marketing/notices`

权限码 `marketing:notice:read` · `MarketingController#notices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `type` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`NoticeVO`](#noticevo)>

### `POST /api/ops/marketing/notices`

权限码 `marketing:notice:update` · `MarketingController#createNotice`

**请求体** `MktNotice`

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{noticeNo}`

权限码 `marketing:notice:update` · `MarketingController#updateNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `noticeNo` | `String` | 是 |

**请求体** `MktNotice`

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `marketing:notice:update` · `MarketingController#archiveNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/marketing/notices/{no}/unarchive`

取消归档Notice：清空时间戳，回到默认列表。

权限码 `marketing:notice:update` · `MarketingController#unarchiveNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/operation/overview`

权限码 `location:overview:read` · `OperationController#overview`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** [`OperationOverview`](#operationoverview)

### `GET /api/ops/ota-releases`

权限码 `device:ota:manage` · `DeviceController#otaReleases`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `fwType` | `String` | 否 |
| 查询 | `vendorCode` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`OtaReleaseRow`](#otareleaserow)>

### `POST /api/ops/ota-releases`

权限码 `device:ota:manage` · `DeviceController#createOtaRelease`

**请求体** `DevOtaRelease`

**出参** [`OtaReleaseRow`](#otareleaserow)

### `GET /api/ops/ota-rollouts`

权限码 `device:ota:read` · `DeviceController#otaRollouts`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `releaseNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `strategy` | `String` | 否 |

**出参** 分页<[`OtaRolloutRow`](#otarolloutrow)>

### `POST /api/ops/ota-rollouts`

权限码 `device:ota:manage` · `DeviceController#createOtaRollout`

**请求体** `DevOtaRollout`

**出参** [`OtaRolloutRow`](#otarolloutrow)

### `POST /api/ops/ota-rollouts/{rolloutNo}`

更新投放（灰度比例调整、回滚 `status=ROLLBACK`）。

权限码 `device:ota:manage` · `DeviceController#updateOtaRollout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `rolloutNo` | `String` | 是 |

**请求体** `DevOtaRollout`

**出参** [`OtaRolloutRow`](#otarolloutrow)

### `GET /api/ops/ota-rollouts/{rolloutNo}/tasks`

投放的逐设备任务明细（`dev_ota_task` 下钻）。

权限码 `device:ota:read` · `DeviceController#otaTasks`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `rolloutNo` | `String` | 是 |

**出参** 数组<[`OtaTaskRow`](#otataskrow)>

### `GET /api/ops/powerbanks`

权限码 `device:powerbank:read` · `DeviceController#powerbanks`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |

**出参** 分页<[`PowerbankRow`](#powerbankrow)>

### `POST /api/ops/powerbanks`

权限码 `device:powerbank:update` · `DeviceController#createPowerbank`

**请求体** [`PowerbankCmd`](#powerbankcmd)

**出参** [`PowerbankRow`](#powerbankrow)

### `POST /api/ops/powerbanks/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `device:powerbank:update` · `DeviceController#archivePowerbank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/powerbanks/{no}/unarchive`

取消归档Powerbank：清空时间戳，回到默认列表。

权限码 `device:powerbank:update` · `DeviceController#unarchivePowerbank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/powerbanks/{powerbankNo}`

状态变更（报废/丢失/投放…）与属性更新；状态迁移由 `PowerbankStateMachine` 把关。

权限码 `device:powerbank:update` · `DeviceController#updatePowerbank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `powerbankNo` | `String` | 是 |

**请求体** [`PowerbankCmd`](#powerbankcmd)

**出参** [`PowerbankRow`](#powerbankrow)

### `GET /api/ops/reports/consumer-insight`

权限码 `report:consumer:read` · `ReportController#consumerInsight`

**出参** [`ConsumerInsight`](#consumerinsight)

### `GET /api/ops/reports/consumer-segments`

人群分层表。

权限码 `report:consumer:read` · `ReportController#consumerSegments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`ConsumerSegment`](#consumersegment)>

### `GET /api/ops/reports/custom`

权限码 `report:custom:read` · `ReportController#custom`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |
| 查询 | `dim` | `String` | 否 |
| 查询 | `metrics` | `String` | 否 |

**出参** 分页<[`ReportCustom`](#reportcustom)>

### `GET /api/ops/reports/device`

权限码 `report:device:read` · `ReportController#device`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** 分页<[`ReportDevice`](#reportdevice)>

### `GET /api/ops/reports/finance`

权限码 `report:finance:read` · `ReportController#finance`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** 分页<[`ReportFinance`](#reportfinance)>

### `GET /api/ops/reports/location`

权限码 `report:location:read` · `ReportController#location`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** 分页<[`ReportLocation`](#reportlocation)>

### `GET /api/ops/reports/metrics`

权限码 `report:custom:read` · `ReportController#metrics`

**出参** 数组<[`ReportMetricDef`](#reportmetricdef)>

### `GET /api/ops/reports/screen`

权限码 `report:screen:read` · `ReportController#screen`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`ReportScreen`](#reportscreen)>

### `GET /api/ops/reports/screen-board`

权限码 `report:screen:read` · `ReportController#screenBoard`

**出参** [`ScreenBoard`](#screenboard)

### `GET /api/ops/reports/trend`

权限码 `report:device:read` · `ReportController#trend`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `kind` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** [`ReportTrend`](#reporttrend)

### `GET /api/ops/site-analysis`

读模型：`loc_site ⋈ ord_order` 聚合，不落表。

权限码 `location:analysis:read` · `LocExtController#siteAnalysis`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 分页<[`SiteAnalysis`](#siteanalysis)>

### `GET /api/ops/site-lifecycles`

权限码 `location:venue:read` · `LocExtController#siteLifecycles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `stage` | `String` | 否 |

**出参** 分页<[`SiteLifecycle`](#sitelifecycle)>

### `POST /api/ops/site-lifecycles/{siteNo}/stage`

阶段流转，留痕到 `loc_site_lifecycle_log`。

权限码 `location:venue:update` · `LocExtController#changeStage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`StageChangeReq`](#stagechangereq)

**出参** [`SiteLifecycle`](#sitelifecycle)

### `GET /api/ops/sites`

权限码 `location:poi:read` · `OpsController#sites`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Site`](#site)>

### `POST /api/ops/sites`

权限码 `location:poi:create` · `OpsController#saveSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`Site`](#site)

**出参** [`Site`](#site)

### `POST /api/ops/sites/{no}/archive`

权限码 `location:site:update` · `OpsController#archiveSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/sites/{no}/unarchive`

权限码 `location:site:update` · `OpsController#unarchiveSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/sites/{siteNo}`

权限码 `location:poi:create` · `OpsController#saveSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`Site`](#site)

**出参** [`Site`](#site)

### `GET /api/ops/sites/{siteNo}/agents`

权限码 `location:poi:read` · `OperationController#siteAgents`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** 数组<[`SiteAgentRow`](#siteagentrow)>

### `POST /api/ops/sites/{siteNo}/agents`

权限码 `location:poi:update` · `OperationController#saveSiteAgent`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`SiteAgentRow`](#siteagentrow)

**出参** [`SiteAgentRow`](#siteagentrow)

### `POST /api/ops/sites/{siteNo}/agents/{id}/remove`

契约禁止 delete*，用 remove。

权限码 `location:poi:update` · `OperationController#removeSiteAgent`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |
| 路径 | `id` | `Long` | 是 |

**出参** [`OkResult`](#okresult)

### `POST /api/ops/sites/{siteNo}/pause`

暂停 / 恢复营业（运营管理清单 OM-S3）。

权限码 `location:poi:update` · `OpsController#pauseSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`Site`](#site)

### `POST /api/ops/sites/{siteNo}/resume`

权限码 `location:poi:update` · `OpsController#resumeSite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Site`](#site)

### `GET /api/ops/sites/{siteNo}/stats`

权限码 `location:poi:read` · `OperationController#siteStats`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** [`SiteStats`](#sitestats)

### `GET /api/ops/sla-rules`

权限码 `workorder:wo:read` · `WoExtController#slaRules`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `woType` | `String` | 否 |
| 查询 | `active` | `String` | 否 |

**出参** 分页<[`SlaRule`](#slarule)>

### `POST /api/ops/sla-rules`

权限码 `workorder:wo:update` · `WoExtController#createSlaRule`

**请求体** `WoSlaRule`

**出参** [`SlaRule`](#slarule)

### `GET /api/ops/sla-rules/{slaNo}`

权限码 `workorder:wo:read` · `WoExtController#slaRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slaNo` | `String` | 是 |

**出参** [`SlaRule`](#slarule)

### `POST /api/ops/sla-rules/{slaNo}`

权限码 `workorder:wo:update` · `WoExtController#updateSlaRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slaNo` | `String` | 是 |

**请求体** `WoSlaRule`

**出参** [`SlaRule`](#slarule)

### `GET /api/ops/venue-onboardings`

权限码 `location:venue:read` · `LocExtController#onboardings`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`VenueOnboarding`](#venueonboarding)>

### `GET /api/ops/venue-onboardings/{onboardingNo}`

权限码 `location:venue:read` · `LocExtController#onboarding`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `onboardingNo` | `String` | 是 |

**出参** [`VenueOnboarding`](#venueonboarding)

### `POST /api/ops/venue-onboardings/{onboardingNo}/review`

审核。

权限码 `location:venue:create` · `LocExtController#review`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `onboardingNo` | `String` | 是 |

**请求体** [`OnboardingReviewReq`](#onboardingreviewreq)

**出参** [`VenueOnboarding`](#venueonboarding)

### `GET /api/ops/venues`

权限码 `location:venue:read` · `OpsController#venues`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Venue`](#venue)>

### `POST /api/ops/venues/{no}/archive`

权限码 `location:venue:update` · `OpsController#archiveVenue`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/venues/{no}/unarchive`

权限码 `location:venue:update` · `OpsController#unarchiveVenue`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/work-orders`

权限码 `workorder:wo:read` · `OpsController#workOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** 分页<[`WorkOrder`](#workorder)>

### `POST /api/ops/work-orders`

手工开单（补 [api/README §6] 的 G6 缺口）。

权限码 `workorder:wo:create` · `WoExtController#createWorkOrder`

**请求体** [`WorkOrderDraft`](#workorderdraft)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/accept`

权限码 `workorder:wo:process` · `WoExtController#accept`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`AcceptReq`](#acceptreq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/close`

完成 / 审核关单。

权限码 `workorder:wo:audit` · `WoExtController#close`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`CloseReq`](#closereq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/complete`

完工：`PROCESSING → DONE`。

权限码 `workorder:wo:handle` · `WoExtController#complete`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`HandleReq`](#handlereq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/dispatch`

权限码 `workorder:wo:dispatch` · `OpsController#dispatch`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/handle`

权限码 `workorder:wo:process` · `WoExtController#handle`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`HandleReq`](#handlereq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/reject`

驳回退回待派单：`DISPATCHED/ACCEPTED/PROCESSING → CREATED`，`reason` 必填。

权限码 `workorder:wo:dispatch` · `WoExtController#reject`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`RejectReq`](#rejectreq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/rework`

验收不合格退回返工：`DONE → PROCESSING`，`reason` 必填，受理人不变。

权限码 `workorder:wo:close` · `WoExtController#rework`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`RejectReq`](#rejectreq)

**出参** [`WorkOrder`](#workorder)


## 交易：订单 · 计价 · 支付 · 结算 · 分润 · 发票

71 个端点。

### `GET /api/trade/complaints`

权限码 `order:exception:read` · `OrderOpsController#complaints`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `issueType` | `String` | 否 |

**出参** 分页<[`OrderComplaint`](#ordercomplaint)>

### `POST /api/trade/complaints`

客服代客登记（电话/线下投诉）；C 端提交走 `POST /mp/user/report` 自动派生。

权限码 `order:exception:handle` · `OrderOpsController#createComplaint`

**请求体** [`ComplaintCreateReq`](#complaintcreatereq)

**出参** [`OrderComplaint`](#ordercomplaint)

### `POST /api/trade/complaints/{no}/handle`

权限码 `order:exception:handle` · `OrderOpsController#handleComplaint`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** [`ComplaintHandleReq`](#complainthandlereq)

**出参** [`OrderComplaint`](#ordercomplaint)

### `POST /api/trade/complaints/{no}/work-order`

转工单（**幂等**）：已转过则返回已有 `workOrderNo`，不重复开单。

权限码 `workorder:wo:create` · `OrderOpsController#complaintToWorkOrder`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** [`OrderComplaint`](#ordercomplaint)

### `GET /api/trade/deposits`

权限码 `order:order:read` · `OrderOpsController#deposits`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`DepositRecord`](#depositrecord)>

### `POST /api/trade/deposits/{depositNo}/buyout`

押金转买断：用户不还了，押金抵购机款，充电宝转 SOLD。

权限码 `order:deposit:update` · `FinanceController#buyoutDeposit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `depositNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/trade/deposits/{depositNo}/dun`

欠款催缴：**只留痕不改状态** —— 催缴不改变欠款事实，改状态会让「已催缴」被误读成「已解决」。

权限码 `order:deposit:update` · `FinanceController#dunDeposit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `depositNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/trade/deposits/{no}/release`

权限码 `order:intervene:execute` · `OrderOpsController#releaseDeposit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** [`OkResult`](#okresult)

### `GET /api/trade/free-orders`

权限码 `order:order:read` · `OrderOpsController#freeOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `whitelistReason` | `String` | 否 |

**出参** 分页<[`FreeOrder`](#freeorder)>

### `GET /api/trade/free-orders/stats`

页头统计：**全量口径**（本月单量/累计减免额），不是当前页合计。

权限码 `order:order:read` · `OrderOpsController#freeOrderStats`

**出参** [`FreeOrderStats`](#freeorderstats)

### `GET /api/trade/invoices`

权限码 `finance:invoice:read` · `FinanceController#invoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`Invoice`](#invoice)>

### `POST /api/trade/invoices`

权限码 `finance:invoice:issue` · `FinanceController#createInvoice`

**请求体** [`InvoiceSaveReq`](#invoicesavereq)

**出参** [`InvoiceView`](#invoiceview)

### `GET /api/trade/invoices/{invoiceNo}`

权限码 `finance:invoice:read` · `FinanceController#invoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**出参** [`InvoiceView`](#invoiceview)

### `POST /api/trade/invoices/{invoiceNo}`

权限码 `finance:invoice:issue` · `FinanceController#updateInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**请求体** [`InvoiceSaveReq`](#invoicesavereq)

**出参** [`InvoiceView`](#invoiceview)

### `POST /api/trade/invoices/{invoiceNo}/issue`

开具发票。

权限码 `finance:invoice:update` · `FinanceController#issueInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**出参** `Object`

### `POST /api/trade/invoices/{invoiceNo}/void`

作废发票。

权限码 `finance:invoice:update` · `FinanceController#voidInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `GET /api/trade/ledger`

账务分录列表（自 `TradeController` SeedData 骨架迁入，走 `acct_ledger` 表）。

权限码 `finance:ledger:read` · `FinanceController#ledger`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `accountNo` | `String` | 否 |
| 查询 | `bizType` | `String` | 否 |

**出参** 分页<[`LedgerEntry`](#ledgerentry)>

### `POST /api/trade/ledger/vouchers`

建凭证 = 记一组借贷分录（服务端校验借贷平衡，不平拒收）。

权限码 `finance:ledger:create` · `FinanceController#createVoucher`

**请求体** [`LedgerPostReq`](#ledgerpostreq)

**出参** `Object`

### `GET /api/trade/ledger/vouchers/{voucherNo}`

凭证下钻：同一 voucherNo 的全部分录 + 借贷合计与平衡判定。

权限码 `finance:ledger:read` · `FinanceController#voucherDetail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `voucherNo` | `String` | 是 |

**出参** `Object`

### `GET /api/trade/order-exceptions`

权限码 `order:exception:read` · `OrderOpsController#orderExceptions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** 分页<[`OrderException`](#orderexception)>

### `POST /api/trade/order-exceptions/{no}/handle`

权限码 `order:exception:handle` · `OrderOpsController#handleOrderException`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** [`ExceptionHandleReq`](#exceptionhandlereq)

**出参** [`OkResult`](#okresult)

### `GET /api/trade/order-interventions`

干预留痕分页（审计视图，append-only 表 `ord_intervention`）。

权限码 `order:order:read` · `TradeController#interventions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `orderNo` | `String` | 否 |
| 查询 | `action` | `String` | 否 |

**出参** 分页<[`OrderIntervention`](#orderintervention)>

### `GET /api/trade/orders`

权限码 `order:order:read` · `TradeController#orders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`RentOrder`](#rentorder)>

### `GET /api/trade/orders/{orderNo}`

权限码 `order:order:read` · `TradeController#order`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** [`RentOrder`](#rentorder)

### `POST /api/trade/orders/{orderNo}/intervene`

权限码 `order:intervene:execute` · `TradeController#intervene`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**请求体** [`InterveneReq`](#intervenereq)

**出参** [`OrderInterveneResult`](#orderinterveneresult)

### `GET /api/trade/payee-sharing`

权限码 `finance:share_rule:read` · `OperationController#payeeSharing`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `payeeType` | `String` | 否 |

**出参** 分页<[`PayeeSharingRow`](#payeesharingrow)>

### `GET /api/trade/payout-accounts`

收款账户列表。

权限码 `finance:payout_account:read` · `FinanceController#payoutAccounts`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `payeeType` | `String` | 否 |
| 查询 | `payeeNo` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`PayoutAccount`](#payoutaccount)>

### `POST /api/trade/payout-accounts`

新增 / 修改收款账户。

权限码 `finance:payout_account:update` · `FinanceController#savePayoutAccount`

**请求体** [`PayoutAccountReq`](#payoutaccountreq)

**出参** [`PayoutAccount`](#payoutaccount)

### `POST /api/trade/payout-accounts/{accountNo}/disable`

停用收款账户。

权限码 `finance:payout_account:update` · `FinanceController#disablePayoutAccount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `accountNo` | `String` | 是 |

**出参** [`PayoutAccount`](#payoutaccount)

### `GET /api/trade/price-adjustments`

权限码 `pricing:rule:read` · `OperationController#adjustments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `planNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** `PageResult<对象（自由键）>`

### `POST /api/trade/price-adjustments`

新建或编辑调价单。

权限码 `pricing:rule:config` · `OperationController#saveAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}`

新建或编辑调价单。

权限码 `pricing:rule:config` · `OperationController#saveAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/cancel`

权限码 `pricing:rule:config` · `OperationController#cancelAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/retry`

权限码 `pricing:rule:config` · `OperationController#retryAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/revert`

权限码 `pricing:rule:config` · `OperationController#revertAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**出参** `对象（自由键）`

### `GET /api/trade/price-plans`

权限码 `pricing:plan:read` · `PricingController#pricePlans`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`PricePlanEntry`](#priceplanentry)>

### `POST /api/trade/price-plans`

权限码 `pricing:plan:create` · `PricingController#createPlan`

**请求体** [`PricePlan`](#priceplan)

**出参** [`PricePlanEntry`](#priceplanentry)

### `POST /api/trade/price-plans/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在基类里等价于「不过滤」。

权限码 `pricing:plan:update` · `PricingController#archivePricePlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/trade/price-plans/{no}/unarchive`

取消归档PricePlan：清空时间戳，回到默认列表。

权限码 `pricing:plan:update` · `PricingController#unarchivePricePlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/trade/price-plans/{planNo}`

权限码 `pricing:plan:create` · `PricingController#updatePlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**请求体** [`PricePlan`](#priceplan)

**出参** [`PricePlanEntry`](#priceplanentry)

### `GET /api/trade/price-plans/{planNo}/scopes`

权限码 `pricing:plan:read` · `PricingController#planScopes`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**出参** 数组<[`PlanScopeEntry`](#planscopeentry)>

### `POST /api/trade/price-plans/{planNo}/scopes`

权限码 `pricing:plan:create` · `PricingController#savePlanScope`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**请求体** [`PlanScopeEntry`](#planscopeentry)

**出参** [`PlanScopeEntry`](#planscopeentry)

### `POST /api/trade/price-plans/{planNo}/scopes/{id}/remove`

契约禁止 delete*，用 remove（软删语义由实体的

权限码 `pricing:plan:create` · `PricingController#removePlanScope`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |
| 路径 | `id` | `Long` | 是 |

**出参** [`OkResult`](#okresult)

### `GET /api/trade/pricing-schedules`

权限码 `pricing:rule:read` · `PricingController#pricingSchedules`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `active` | `String` | 否 |

**出参** 分页<[`PricingSchedule`](#pricingschedule)>

### `POST /api/trade/pricing-schedules`

权限码 `pricing:rule:update` · `PricingController#createPricingSchedule`

**请求体** `PriceSchedule`

**出参** [`PricingSchedule`](#pricingschedule)

### `POST /api/trade/pricing-schedules/{ruleNo}`

权限码 `pricing:rule:update` · `PricingController#updatePricingSchedule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** `PriceSchedule`

**出参** [`PricingSchedule`](#pricingschedule)

### `GET /api/trade/reconciles`

审批入参。

权限码 `finance:recon:read` · `FinanceController#reconciles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** 分页<[`Reconcile`](#reconcile)>

### `GET /api/trade/reconciles/stats`

对账页头统计：任务数 / 差异数 / 已处理 / 待处理。

权限码 `finance:reconcile:read` · `FinanceController#reconcileStats`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `period` | `String` | 否 |

**出参** `Object`

### `GET /api/trade/reconciles/{batchNo}/diffs`

权限码 `finance:recon:read` · `FinanceController#reconDiffs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `batchNo` | `String` | 是 |

**出参** 数组<[`ReconDiffRow`](#recondiffrow)>

### `POST /api/trade/reconciles/{batchNo}/resolve`

差错平账处置。

权限码 `finance:recon:resolve` · `FinanceController#resolveRecon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `batchNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`Reconcile`](#reconcile)

### `GET /api/trade/refunds`

权限码 `order:refund:audit` · `OrderOpsController#refunds`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`RefundRecord`](#refundrecord)>

### `POST /api/trade/refunds`

退款申请。

权限码 `order:refund:apply` · `OrderOpsController#applyRefund`

**请求体** [`RefundApplyReq`](#refundapplyreq)

**出参** [`RefundRecord`](#refundrecord)

### `POST /api/trade/refunds/{refundNo}/audit`

退款审批。

权限码 `order:refund:audit` · `OrderOpsController#auditRefund`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `refundNo` | `String` | 是 |

**请求体** [`RefundAuditReq`](#refundauditreq)

**出参** [`RefundRecord`](#refundrecord)

### `GET /api/trade/reservations`

权限码 `order:order:read` · `OrderOpsController#reservations`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** 分页<[`Reservation`](#reservation)>

### `POST /api/trade/reservations/{no}/cancel`

取消预约：**仅 PENDING 可取消，服务端复校**。

权限码 `order:order:update` · `OrderOpsController#cancelReservation`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** [`OkResult`](#okresult)

### `GET /api/trade/settlements`

结算单列表（自 `TradeController` SeedData 骨架迁入，走 `stl_settlement` 表）。

权限码 `finance:settlement:read` · `FinanceController#settlements`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`Settlement`](#settlement)>

### `POST /api/trade/settlements/generate`

周期出账（运营端手动触发入口）。

权限码 `finance:settlement:generate` · `FinanceController#generateSettlementsPublic`

**请求体** `对象（字符串值）`

**出参** `List<String>`

### `GET /api/trade/settlements/{settleNo}`

权限码 `finance:settlement:read` · `FinanceController#settlement`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |

**出参** [`SettlementView`](#settlementview)

### `POST /api/trade/settlements/{settleNo}/confirm`

权限码 `finance:settlement:confirm` · `FinanceController#confirmSettlement`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |

**出参** [`SettlementView`](#settlementview)

### `GET /api/trade/settlements/{settleNo}/records`

结算单构成明细：这张单的钱是哪几笔分润凑出来的。

权限码 `finance:settlement:read` · `FinanceController#settlementRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** `Object`

### `GET /api/trade/share-records`

权限码 `finance:share_record:read` · `FinanceController#shareRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `dimension` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`ShareRecord`](#sharerecord)>

### `GET /api/trade/share-rules`

分润规则列表（自 `TradeController` SeedData 骨架迁入，走 `share_rule` 表）。

权限码 `finance:share_rule:read` · `FinanceController#shareRules`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`ShareRule`](#sharerule)>

### `POST /api/trade/share-rules`

权限码 `finance:share_rule:create` · `FinanceController#createShareRule`

**请求体** [`ShareRule`](#sharerule)

**出参** [`ShareRule`](#sharerule)

### `POST /api/trade/share-rules/{ruleNo}`

权限码 `finance:share_rule:create` · `FinanceController#updateShareRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** [`ShareRule`](#sharerule)

**出参** [`ShareRule`](#sharerule)

### `GET /api/trade/share-summaries`

分润统计 —— **读模型，无对应表**：由 `share_record` 按 (dimension, payeeNo, period) 聚合。

权限码 `finance:share_record:read` · `FinanceController#shareSummaries`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `dimension` | `String` | 否 |
| 查询 | `period` | `String` | 否 |
| 查询 | `sortKey` | `String` | 否 |
| 查询 | `sortDir` | `String` | 否 |

**出参** 分页<[`ShareSummary`](#sharesummary)>

### `GET /api/trade/site-sharing`

权限码 `finance:share_rule:read` · `OperationController#siteSharing`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `state` | `String` | 否 |

**出参** 分页<[`SiteSharingRow`](#sitesharingrow)>

### `GET /api/trade/site-sharing/stats`

权限码 `finance:share_rule:read` · `OperationController#siteSharingStats`

**出参** [`SharingStats`](#sharingstats)

### `GET /api/trade/withdrawals`

提现审核队列（菜单叶：财务管理 › 提现审核）。

权限码 `finance:withdrawal:read` · `FinanceController#withdrawals`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`Withdrawal`](#withdrawal)>

### `POST /api/trade/withdrawals`

提现申请。

权限码 `finance:withdrawal:apply` · `FinanceController#applyWithdrawal`

**请求体** [`WithdrawApplyReq`](#withdrawapplyreq)

**出参** [`Withdrawal`](#withdrawal)

### `POST /api/trade/withdrawals/{withdrawNo}/audit`

提现审批。

权限码 `finance:withdrawal:audit` · `FinanceController#auditWithdrawal`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `withdrawNo` | `String` | 是 |

**请求体** [`WithdrawAuditReq`](#withdrawauditreq)

**出参** [`Withdrawal`](#withdrawal)

### `POST /api/trade/withdrawals/{withdrawNo}/pay`

打款回执登记：把「出款在途」推到终态（必要功能清单 ⑮）。

权限码 `finance:withdrawal:pay` · `FinanceController#payWithdrawal`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `withdrawNo` | `String` | 是 |

**请求体** [`PayReceiptReq`](#payreceiptreq)

**出参** [`Withdrawal`](#withdrawal)


## 用户：账号 · 钱包 · 券 · 会员 · 客服

47 个端点。

### `GET /api/user/ad-campaigns`

权限码 `marketing:ad:read` · `MarketingController#adCampaigns`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `advertiserNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AdCampaignVO`](#adcampaignvo)>

### `POST /api/user/ad-campaigns`

权限码 `marketing:ad:update` · `MarketingController#createAdCampaign`

**请求体** `AdCampaign`

**出参** [`AdCampaignVO`](#adcampaignvo)

### `POST /api/user/ad-campaigns/{adNo}`

权限码 `marketing:ad:update` · `MarketingController#updateAdCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adNo` | `String` | 是 |

**请求体** `AdCampaign`

**出参** [`AdCampaignVO`](#adcampaignvo)

### `POST /api/user/ad-campaigns/{adNo}/{action}`

广告投放状态迁移，语义同活动。

权限码 `marketing:ad:update` · `MarketingController#transitionAdCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adNo` | `String` | 是 |
| 路径 | `action` | `String` | 是 |

**出参** `Object`

### `GET /api/user/ad-deliveries`

投放与曝光统计。

权限码 `marketing:ad:read` · `MarketingController#adDeliveries`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `adNo` | `String` | 否 |
| 查询 | `slotNo` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 分页<[`AdDeliveryVO`](#addeliveryvo)>

### `GET /api/user/blacklist`

权限码 `user:risk:read` · `UserOpsController#blacklist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`UserBlacklist`](#userblacklist)>

### `GET /api/user/campaigns`

权限码 `marketing:campaign:read` · `MarketingController#campaigns`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CampaignVO`](#campaignvo)>

### `POST /api/user/campaigns`

权限码 `marketing:campaign:update` · `MarketingController#createCampaign`

**请求体** `MktCampaign`

**出参** [`CampaignVO`](#campaignvo)

### `POST /api/user/campaigns/{campaignNo}`

权限码 `marketing:campaign:update` · `MarketingController#updateCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `campaignNo` | `String` | 是 |

**请求体** `MktCampaign`

**出参** [`CampaignVO`](#campaignvo)

### `POST /api/user/campaigns/{campaignNo}/{action}`

活动状态迁移（start/pause/end）。

权限码 `marketing:campaign:update` · `MarketingController#transitionCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `campaignNo` | `String` | 是 |
| 路径 | `action` | `String` | 是 |

**出参** `Object`

### `GET /api/user/coupon-issue-records`

券发放记录（append，只增不改）。

权限码 `marketing:coupon:read` · `MarketingController#couponIssueRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `couponNo` | `String` | 否 |

**出参** `Object`

### `GET /api/user/coupons`

权限码 `marketing:coupon:read` · `UserController#coupons`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`CouponTplVO`](#coupontplvo)>

### `POST /api/user/coupons`

权限码 `marketing:coupon:create` · `MarketingController#createCoupon`

**请求体** `CouponTpl`

**出参** [`CouponTplVO`](#coupontplvo)

### `POST /api/user/coupons/{couponNo}`

权限码 `marketing:coupon:create` · `MarketingController#updateCoupon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `couponNo` | `String` | 是 |

**请求体** `CouponTpl`

**出参** [`CouponTplVO`](#coupontplvo)

### `POST /api/user/coupons/{no}/archive`

归档Coupon。

权限码 `marketing:coupon:update` · `MarketingController#archiveCoupon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/user/coupons/{no}/unarchive`

取消归档Coupon：清空时间戳，回到默认列表。

权限码 `marketing:coupon:update` · `MarketingController#unarchiveCoupon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/user/coupons/{tplNo}/issue`

定向发券。

权限码 `marketing:coupon:issue` · `MarketingController#issueCoupon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `tplNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** 数组<[`UserCouponVO`](#usercouponvo)>

### `GET /api/user/credit-score-changes`

信用分变更流水（append，只增不改）。

权限码 `user:risk:read` · `UserOpsController#creditScoreChanges`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `cUserNo` | `String` | 否 |

**出参** `Object`

### `GET /api/user/free-whitelist`

权限码 `user:risk:read` · `UserOpsController#freeWhitelist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `reason` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`FreeUserWhitelist`](#freeuserwhitelist)>

### `POST /api/user/free-whitelist`

权限码 `user:risk:update` · `UserOpsController#grantWhitelist`

**请求体** `UsrFreeWhitelist`

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `POST /api/user/free-whitelist/{userNo}`

权限码 `user:risk:update` · `UserOpsController#updateWhitelist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**请求体** `UsrFreeWhitelist`

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `POST /api/user/free-whitelist/{userNo}/revoke`

撤销 = 软删除（`status=REVOKED` 留记录），不是 DELETE（[api §1.5] 全站零 DELETE）。

权限码 `user:risk:update` · `UserOpsController#revokeWhitelist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `GET /api/user/member-benefits`

会员等级权益，**按等级由低到高**返回（页面排序与单调性校验共用同一顺序）。

权限码 `user:member:read` · `UserOpsController#memberBenefits`

**出参** `Object`

### `POST /api/user/member-benefits/{level}`

保存某等级的权益。

权限码 `user:member:update` · `UserOpsController#saveMemberBenefit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `level` | `String` | 是 |

**请求体** [`MemberBenefit`](#memberbenefit)

**出参** `Object`

### `GET /api/user/member-cards`

会员卡列表。

权限码 `user:member:read` · `UserOpsController#memberCards`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `level` | `String` | 否 |

**出参** `Object`

### `POST /api/user/member-cards`

发卡。

权限码 `user:member:update` · `UserOpsController#grantMemberCard`

**请求体** [`MemberCardGrantReq`](#membercardgrantreq)

**出参** `Object`

### `GET /api/user/members`

权限码 `user:member:read` · `UserOpsController#members`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `level` | `String` | 否 |

**出参** 分页<[`MemberRow`](#memberrow)>

### `POST /api/user/members`

权限码 `user:member:update` · `UserOpsController#createMember`

**请求体** `UsrMembership`

**出参** [`MemberRow`](#memberrow)

### `POST /api/user/members/{userNo}`

权限码 `user:member:update` · `UserOpsController#updateMember`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**请求体** `UsrMembership`

**出参** [`MemberRow`](#memberrow)

### `GET /api/user/push-messages`

权限码 `marketing:push:send` · `MarketingController#pushMessages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`PushMessageVO`](#pushmessagevo)>

### `POST /api/user/push-messages`

权限码 `marketing:push:send` · `MarketingController#createPush`

**请求体** `MktPush`

**出参** [`PushMessageVO`](#pushmessagevo)

### `POST /api/user/push-messages/{pushNo}`

权限码 `marketing:push:send` · `MarketingController#updatePush`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `pushNo` | `String` | 是 |

**请求体** `MktPush`

**出参** [`PushMessageVO`](#pushmessagevo)

### `POST /api/user/push-messages/{pushNo}/send`

发送推送。

权限码 `marketing:push:update` · `MarketingController#sendPushMessage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `pushNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `GET /api/user/recharge-orders`

权限码 `user:wallet:read` · `UserOpsController#rechargeOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 分页<[`RechargeOrderRow`](#rechargeorderrow)>

### `GET /api/user/recharge-packages`

权限码 `user:wallet:read` · `UserOpsController#rechargePackages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`RechargePackageRow`](#rechargepackagerow)>

### `POST /api/user/recharge-packages`

权限码 `user:wallet:update` · `UserOpsController#createRechargePackage`

**请求体** `UsrRechargePkg`

**出参** [`RechargePackageRow`](#rechargepackagerow)

### `POST /api/user/recharge-packages/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `marketing:recharge:update` · `UserOpsController#archiveRechargePackage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/user/recharge-packages/{no}/unarchive`

取消归档RechargePackage：清空时间戳，回到默认列表。

权限码 `marketing:recharge:update` · `UserOpsController#unarchiveRechargePackage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/user/recharge-packages/{packageNo}`

权限码 `user:wallet:update` · `UserOpsController#updateRechargePackage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `packageNo` | `String` | 是 |

**请求体** `UsrRechargePkg`

**出参** [`RechargePackageRow`](#rechargepackagerow)

### `GET /api/user/referral-rules`

裂变规则列表。

权限码 `marketing:referral:read` · `MarketingController#referralRules`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** `Object`

### `GET /api/user/referrals`

权限码 `marketing:campaign:read` · `MarketingController#referrals`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `inviterNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`ReferralVO`](#referralvo)>

### `GET /api/user/risk-users`

权限码 `user:risk:read` · `UserOpsController#riskUsers`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `riskLevel` | `String` | 否 |

**出参** 分页<[`UserRisk`](#userrisk)>

### `GET /api/user/users`

权限码 `user:cuser:read` · `UserController#users`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`CUserRow`](#cuserrow)>

### `POST /api/user/users/{cUserNo}/credit-score`

调整信用分。

权限码 `user:risk:update` · `UserOpsController#adjustCreditScore`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cUserNo` | `String` | 是 |

**请求体** [`CreditScoreAdjustReq`](#creditscoreadjustreq)

**出参** `Object`

### `GET /api/user/users/{cUserNo}/profile`

用户 360 档案（列表行点开的详情抽屉），镜像前端 `UserProfile`。

权限码 `user:cuser:read` · `UserController#profile`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cUserNo` | `String` | 是 |

**出参** [`UserProfileVO`](#userprofilevo)

### `GET /api/user/wallets`

权限码 `user:wallet:read` · `UserOpsController#wallets`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`WalletRow`](#walletrow)>

### `GET /api/user/wallets/{userNo}/txns`

权限码 `user:wallet:read` · `UserOpsController#walletTxns`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** 分页<[`WalletTxnRow`](#wallettxnrow)>


## 代理商

25 个端点。

### `GET /api/agent/accounts`

权限码 `agent:account:manage` · `AgentExtController#accounts`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AgentAccount`](#agentaccount)>

### `POST /api/agent/accounts`

权限码 `agent:account:manage` · `AgentExtController#createAccount`

**请求体** `AgtAccount`

**出参** [`AgentAccount`](#agentaccount)

### `GET /api/agent/accounts/{accountNo}`

权限码 `agent:account:manage` · `AgentExtController#account`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `accountNo` | `String` | 是 |

**出参** [`AgentAccount`](#agentaccount)

### `POST /api/agent/accounts/{accountNo}`

权限码 `agent:account:manage` · `AgentExtController#updateAccount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `accountNo` | `String` | 是 |

**请求体** `AgtAccount`

**出参** [`AgentAccount`](#agentaccount)

### `GET /api/agent/agents`

agt 域运营端端点（ADR-012 代理商）：薄控制器——路由 +

权限码 `agent:agent:read` · `AgentController#agents`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Agent`](#agent)>

### `POST /api/agent/agents`

权限码 `agent:agent:update` · `AgentController#save`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**请求体** [`Agent`](#agent)

**出参** [`Agent`](#agent)

### `POST /api/agent/agents/{agentNo}`

权限码 `agent:agent:update` · `AgentController#save`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**请求体** [`Agent`](#agent)

**出参** [`Agent`](#agent)

### `POST /api/agent/agents/{no}/archive`

归档Agent。

权限码 `agent:agent:update` · `AgentController#archiveAgent`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/agent/agents/{no}/unarchive`

取消归档Agent：清空时间戳，回到默认列表。

权限码 `agent:agent:update` · `AgentController#unarchiveAgent`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/agent/applies`

运营端待办队列与历史检索。

权限码 `agent:apply:read` · `AgentApplyController#applies`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `status` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`ApplyView`](#applyview)>

### `POST /api/agent/applies`

**运营代建**：替线下签约的商家录入（`source = OPS_CREATED`）。

权限码 `agent:apply:create` · `AgentApplyController#opsCreateApply`

**请求体** [`SubmitReq`](#submitreq)

**出参** [`ApplyResult`](#applyresult)

### `POST /api/agent/applies/{applyNo}/accept`

受理：SUBMITTED → REVIEWING。

权限码 `agent:apply:approve` · `AgentApplyController#accept`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `applyNo` | `String` | 是 |

**出参** [`ApplyResult`](#applyresult)

### `POST /api/agent/applies/{applyNo}/audit`

审核：通过 / 驳回。

权限码 `agent:apply:approve` · `AgentApplyController#audit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `applyNo` | `String` | 是 |

**请求体** [`AuditReq`](#auditreq)

**出参** [`ApplyResult`](#applyresult)

### `POST /api/agent/apply`

**自助注册**：商家自己提交（免鉴权 + 手机号 OTP）。

**无权限码** · `AgentApplyController#selfServiceApply`

**请求体** [`SubmitReq`](#submitreq)

**出参** [`ApplyResult`](#applyresult)

### `GET /api/agent/apply/mine`

申请人查进度与驳回原因（免鉴权，凭手机号 + OTP）。

**无权限码** · `AgentApplyController#mine`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `phone` | `String` | 是 |
| 查询 | `otp` | `String` | 是 |

**出参** [`MyApplyView`](#myapplyview)

### `POST /api/agent/apply/otp`

提交入驻申请 —— **自助与代建共用同一个端点**。

**无权限码** · `AgentApplyController#applyOtp`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `GET /api/agent/assignable-assets`

可划拨资产候选池（划拨抽屉的选项源）。

权限码 `agent:scope:assign` · `AgentExtController#assignableAssets`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `keyword` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `assetType` | `String` | 否 |
| 查询 | `limit` | `Integer` | 否 |

**出参** `List<AssignableAsset>`

### `GET /api/agent/assignments`

归属**现状**（每个代理手上有多少台柜/多少个站）。

权限码 `agent:scope:assign` · `AgentExtController#assignments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `view` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `targetType` | `String` | 否 |
| 查询 | `targetNo` | `String` | 否 |

**出参** `PageResult<?>`

### `POST /api/agent/assignments`

划拨 / 收回，留痕到 `agt_assignment`（append）。

权限码 `agent:scope:assign` · `AgentExtController#assign`

**请求体** [`AssignReq`](#assignreq)

**出参** [`AssignmentLog`](#assignmentlog)

### `POST /api/agent/assignments/reclaim`

批量回收到平台直营。

权限码 `agent:scope:assign` · `AgentExtController#reclaim`

**请求体** [`ReclaimReq`](#reclaimreq)

**出参** `List<AssignmentLog>`

### `GET /api/agent/commissions`

权限码 `agent:share:config` · `AgentExtController#commissions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `dimension` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AgentCommission`](#agentcommission)>

### `POST /api/agent/commissions`

权限码 `agent:share:config` · `AgentExtController#createCommission`

**请求体** `AgtCommission`

**出参** [`AgentCommission`](#agentcommission)

### `GET /api/agent/commissions/{ruleNo}`

权限码 `agent:share:config` · `AgentExtController#commission`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**出参** [`AgentCommission`](#agentcommission)

### `POST /api/agent/commissions/{ruleNo}`

权限码 `agent:share:config` · `AgentExtController#updateCommission`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** `AgtCommission`

**出参** [`AgentCommission`](#agentcommission)

### `GET /api/agent/performance`

读模型：`agt_agent ⋈ ord_order`/`dev_cabinet` 聚合，不落表。

权限码 `agent:performance:read` · `AgentExtController#performance`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 分页<[`AgentPerformance`](#agentperformance)>


## 平台：组织 · 权限 · 主数据 · 系统设置 · 通知

77 个端点。

### `GET /api/platform/app-versions`

权限码 `system:app_version:read` · `SysSettingController#appVersions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `platform` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`AppVersion`](#appversion)>

### `POST /api/platform/app-versions`

权限码 `system:app_version:update` · `SysSettingController#createAppVersion`

**请求体** `SysAppVersion`

**出参** [`AppVersion`](#appversion)

### `POST /api/platform/app-versions/{versionId}`

权限码 `system:app_version:update` · `SysSettingController#updateAppVersion`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `versionId` | `String` | 是 |

**请求体** `SysAppVersion`

**出参** [`AppVersion`](#appversion)

### `POST /api/platform/app-versions/{versionId}/rollback`

软回滚：`status=ROLLBACK` + `rolloutPercent=0`，**记录保留**。

权限码 `system:app_version:update` · `SysSettingController#rollbackAppVersion`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `versionId` | `String` | 是 |

**出参** [`AppVersion`](#appversion)

### `GET /api/platform/audit-logs`

操作审计列表（WORM 只增）。

权限码 `org:audit:read` · `PlatformController#auditLogs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`AuditLogEntry`](#auditlogentry)>

### `GET /api/platform/audit-logs/{id}`

审计详情（列表行点开抽屉）。

权限码 `org:audit:read` · `PlatformController#auditLogDetail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `id` | `String` | 是 |

**出参** [`AuditDetail`](#auditdetail)

### `GET /api/platform/banks`

权限码 `system:bank:read` · `SysDictController#banks`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `country` | `String` | 否 |
| 查询 | `currency` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`BankEntry`](#bankentry)>

### `POST /api/platform/banks`

权限码 `system:bank:update` · `SysDictController#createBank`

**请求体** `MdBank`

**出参** [`BankEntry`](#bankentry)

### `POST /api/platform/banks/{bankCode}`

权限码 `system:bank:update` · `SysDictController#updateBank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `bankCode` | `String` | 是 |

**请求体** `MdBank`

**出参** [`BankEntry`](#bankentry)

### `POST /api/platform/banks/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在基类里等价于「不过滤」。

权限码 `system:bank:update` · `SysDictController#archiveBank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/platform/banks/{no}/unarchive`

取消归档Bank：清空时间戳，回到默认列表。

权限码 `system:bank:update` · `SysDictController#unarchiveBank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/platform/biz-rules`

读全量三分区；缺失分区由 service 兜默认值，前端不必再写一遍默认。

权限码 `system:biz_rule:read` · `SysSettingController#bizRules`

**出参** [`BizRules`](#bizrules)

### `POST /api/platform/biz-rules`

分区保存：body 是 `Partial`，页面三个保存按钮 → 三次独立 POST。

权限码 `system:biz_rule:update` · `SysSettingController#saveBizRules`

**请求体** [`BizRules`](#bizrules)

**出参** [`BizRules`](#bizrules)

### `GET /api/platform/brands`

权限码 `system:brand:read` · `SysDictController#brands`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `showArchived` | `Boolean` | 否 |

**出参** 分页<[`BrandEntry`](#brandentry)>

### `POST /api/platform/brands`

权限码 `system:brand:update` · `SysDictController#createBrand`

**请求体** [`BrandEntry`](#brandentry)

**出参** [`BrandEntry`](#brandentry)

### `POST /api/platform/brands/{brandNo}`

权限码 `system:brand:update` · `SysDictController#updateBrand`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `brandNo` | `String` | 是 |

**请求体** [`BrandEntry`](#brandentry)

**出参** [`BrandEntry`](#brandentry)

### `POST /api/platform/brands/{no}/archive`

权限码 `system:brand:update` · `SysDictController#archiveBrand`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/platform/brands/{no}/unarchive`

权限码 `system:brand:update` · `SysDictController#unarchiveBrand`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/platform/data-scopes/{subjectType}/{subjectNo}`

权限码 `org:role:read` · `OrgController#dataScope`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `subjectType` | `String` | 是 |
| 路径 | `subjectNo` | `String` | 是 |

**出参** [`DataScopeEntry`](#datascopeentry)

### `PUT /api/platform/data-scopes/{subjectType}/{subjectNo}`

保存数据权限（整体覆盖）。

权限码 `org:role:update` · `OrgController#saveDataScope`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `subjectType` | `String` | 是 |
| 路径 | `subjectNo` | `String` | 是 |

**请求体** [`DataScopeReq`](#datascopereq)

**出参** [`DataScopeEntry`](#datascopeentry)

### `GET /api/platform/departments`

权限码 `org:dept:read` · `OrgController#departments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `parentNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`Department`](#department)>

### `POST /api/platform/departments`

权限码 `org:dept:create` · `OrgController#createDepartment`

**请求体** `IamDept`

**出参** [`Department`](#department)

### `POST /api/platform/departments/{deptNo}`

权限码 `org:dept:create` · `OrgController#updateDepartment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deptNo` | `String` | 是 |

**请求体** `IamDept`

**出参** [`Department`](#department)

### `GET /api/platform/dict-entries`

权限码 `system:dict:read` · `SysConfigController#dictEntries`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `groupCode` | `String` | 否 |
| 查询 | `enabled` | `String` | 否 |

**出参** 分页<[`DictEntry`](#dictentry)>

### `POST /api/platform/dict-entries`

权限码 `system:dict:update` · `SysConfigController#createDictEntry`

**请求体** `DictItem`

**出参** [`DictEntry`](#dictentry)

### `POST /api/platform/dict-entries/{dictNo}`

权限码 `system:dict:update` · `SysConfigController#updateDictEntry`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `dictNo` | `String` | 是 |

**请求体** `DictItem`

**出参** [`DictEntry`](#dictentry)

### `GET /api/platform/employees`

权限码 `org:employee:read` · `PlatformController#employees`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Employee`](#employee)>

### `POST /api/platform/employees`

权限码 `org:employee:create` · `OrgController#createEmployee`

**请求体** `IamEmployee`

**出参** [`Employee`](#employee)

### `POST /api/platform/employees/{employeeNo}`

权限码 `org:employee:create` · `OrgController#updateEmployee`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `employeeNo` | `String` | 是 |

**请求体** `IamEmployee`

**出参** [`Employee`](#employee)

### `GET /api/platform/iam/menus`

全量菜单树（后台维护用）。

权限码 `org:role:read` · `IamAdminController#menus`

**出参** `List<IamMenu>`

### `GET /api/platform/iam/permissions`

权限码目录（构建分配选择器）。

权限码 `org:role:read` · `IamAdminController#permissions`

**出参** `List<IamPermission>`

### `GET /api/platform/iam/roles`

角色列表。

权限码 `org:role:read` · `IamAdminController#roles`

**出参** `List<IamRole>`

### `GET /api/platform/iam/roles/{roleNo}/permissions`

读某角色已分配的权限码（勾选树回显）。

权限码 `org:role:read` · `IamAdminController#rolePermissions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `roleNo` | `String` | 是 |

**出参** `List<String>`

### `PUT /api/platform/iam/roles/{roleNo}/permissions`

给角色分配权限码（覆盖写）→ 失效缓存 + bump 版本（在线员工下一请求即生效）。

权限码 `org:role:update` · `IamAdminController#setRolePermissions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `roleNo` | `String` | 是 |

**请求体** `Map<String, List<String>>`

**出参** `对象（自由键）`

### `GET /api/platform/login-settings`

权限码 `system:login_setting:read` · `SysSettingController#loginSettings`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `country` | `String` | 否 |

**出参** 分页<[`LoginSetting`](#loginsetting)>

### `POST /api/platform/login-settings`

权限码 `system:login_setting:update` · `SysSettingController#createLoginSetting`

**请求体** `SysLoginSetting`

**出参** [`LoginSetting`](#loginsetting)

### `POST /api/platform/login-settings/{country}`

权限码 `system:login_setting:update` · `SysSettingController#updateLoginSetting`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `country` | `String` | 是 |

**请求体** `SysLoginSetting`

**出参** [`LoginSetting`](#loginsetting)

### `GET /api/platform/markets`

权限码 `system:market:read` · `SysSettingController#markets`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `currency` | `String` | 否 |

**出参** 分页<[`MarketCountry`](#marketcountry)>

### `POST /api/platform/markets`

权限码 `system:market:update` · `SysSettingController#createMarket`

**请求体** `MdMarketCountry`

**出参** [`MarketCountry`](#marketcountry)

### `POST /api/platform/markets/{countryCode}`

权限码 `system:market:update` · `SysSettingController#updateMarket`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countryCode` | `String` | 是 |

**请求体** `MdMarketCountry`

**出参** [`MarketCountry`](#marketcountry)

### `GET /api/platform/notify-blacklist`

权限码 `system:notify_blacklist:read` · `NotifyController#blacklist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `reason` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`NotifyBlacklistVO`](#notifyblacklistvo)>

### `POST /api/platform/notify-blacklist`

权限码 `system:notify_blacklist:update` · `NotifyController#block`

**请求体** `NotifyBlacklist`

**出参** [`NotifyBlacklistVO`](#notifyblacklistvo)

### `POST /api/platform/notify-blacklist/{blockNo}/release`

解除（软删：`status=RELEASED` + 留痕，不物理删）。

权限码 `system:notify_blacklist:update` · `NotifyController#release`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `blockNo` | `String` | 是 |

**出参** [`NotifyBlacklistVO`](#notifyblacklistvo)

### `GET /api/platform/notify-logs`

`sort` 受控：仅 `sentAt|cost`，其它值由 service 拒绝（防注入）。

权限码 `system:notify_log:read` · `NotifyController#notifyLogs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `scene` | `String` | 否 |
| 查询 | `sort` | `String` | 否 |
| 查询 | `dir` | `String` | 否 |

**出参** 分页<[`NotifyLogVO`](#notifylogvo)>

### `GET /api/platform/notify-logs/stats`

页头统计（全量口径，非当前分页合计）。

权限码 `system:notify_log:read` · `NotifyController#notifyLogStats`

**出参** [`NotifyLogStats`](#notifylogstats)

### `POST /api/platform/notify-logs/{no}/resend`

重发一条发送记录。

权限码 `system:notify_log:update` · `NotifyController#resendLog`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** [`NotifyResendReq`](#notifyresendreq)

**出参** `Object`

### `GET /api/platform/notify-templates`

权限码 `system:notify_template:read` · `NotifyController#templates`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `channel` | `String` | 否 |
| 查询 | `lang` | `String` | 否 |
| 查询 | `scene` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`NotifyTemplateVO`](#notifytemplatevo)>

### `POST /api/platform/notify-templates`

权限码 `system:notify_template:update` · `NotifyController#createTemplate`

**请求体** `NotifyTemplate`

**出参** [`NotifyTemplateVO`](#notifytemplatevo)

### `POST /api/platform/notify-templates/{no}/preview`

当前操作人；无会话（域间/定时任务）时记 SYSTEM。

权限码 `system:notify_template:read` · `NotifyController#previewTemplate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `Object`

### `POST /api/platform/notify-templates/{no}/test-send`

模板试发。

权限码 `system:notify_template:update` · `NotifyController#testSendTemplate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** [`NotifyTestSendReq`](#notifytestsendreq)

**出参** `Object`

### `POST /api/platform/notify-templates/{templateNo}`

权限码 `system:notify_template:update` · `NotifyController#updateTemplate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `templateNo` | `String` | 是 |

**请求体** `NotifyTemplate`

**出参** [`NotifyTemplateVO`](#notifytemplatevo)

### `GET /api/platform/openapi-apps`

权限码 `system:openapi:read` · `SysSettingController#openApiApps`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`OpenApiApp`](#openapiapp)>

### `POST /api/platform/openapi-apps`

权限码 `system:openapi:update` · `SysSettingController#createOpenApiApp`

**请求体** `OpenapiApp`

**出参** [`OpenApiApp`](#openapiapp)

### `POST /api/platform/openapi-apps/{appNo}`

权限码 `system:openapi:update` · `SysSettingController#updateOpenApiApp`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `appNo` | `String` | 是 |

**请求体** `OpenapiApp`

**出参** [`OpenApiApp`](#openapiapp)

### `POST /api/platform/openapi-apps/{appNo}/reset-secret`

重置 OpenAPI 应用密钥。

权限码 `system:openapi:update` · `SysSettingController#resetOpenApiAppSecret`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `appNo` | `String` | 是 |

**出参** [`OpenApiApp`](#openapiapp)

### `GET /api/platform/payment-channels`

权限码 `system:payment_channel:read` · `PaymentChannelController#channels`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `mode` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`PaymentChannelEntry`](#paymentchannelentry)>

### `POST /api/platform/payment-channels`

权限码 `system:payment_channel:update` · `PaymentChannelController#createChannel`

**请求体** `ChannelBody`

**出参** [`PaymentChannelEntry`](#paymentchannelentry)

### `POST /api/platform/payment-channels/{channelCode}`

权限码 `system:payment_channel:update` · `PaymentChannelController#updateChannel`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `channelCode` | `String` | 是 |

**请求体** `ChannelBody`

**出参** [`PaymentChannelEntry`](#paymentchannelentry)

### `GET /api/platform/problems`

权限码 `system:problem:read` · `SysConfigController#problems`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `category` | `String` | 否 |
| 查询 | `suggestedAction` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`ProblemEntry`](#problementry)>

### `POST /api/platform/problems`

权限码 `system:problem:update` · `SysConfigController#createProblem`

**请求体** `MdProblem`

**出参** [`ProblemEntry`](#problementry)

### `POST /api/platform/problems/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `system:problem:update` · `SysConfigController#archiveProblem`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/platform/problems/{no}/unarchive`

取消归档问题目录项。

权限码 `system:problem:update` · `SysConfigController#unarchiveProblem`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/platform/problems/{problemNo}`

权限码 `system:problem:update` · `SysConfigController#updateProblem`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `problemNo` | `String` | 是 |

**请求体** `MdProblem`

**出参** [`ProblemEntry`](#problementry)

### `GET /api/platform/regions`

权限码 `system:dict:read` · `SysConfigController#regions`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `parentId` | `String` | 否 |
| 查询 | `level` | `String` | 否 |

**出参** 分页<[`Region`](#region)>

### `POST /api/platform/regions`

权限码 `system:dict:update` · `SysConfigController#createRegion`

**请求体** `MdRegion`

**出参** [`Region`](#region)

### `GET /api/platform/regions/tree`

地区树：一次查全表在内存建树（地区是百量级且极少变动，递归 SQL 换不来收益）。

权限码 `system:region:read` · `SysConfigController#regionTree`

**出参** `Object`

### `POST /api/platform/regions/{regionId}`

权限码 `system:dict:update` · `SysConfigController#updateRegion`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `regionId` | `String` | 是 |

**请求体** `MdRegion`

**出参** [`Region`](#region)

### `GET /api/platform/roles`

权限码 `org:role:read` · `PlatformController#roles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `showArchived` | `String` | 否 |

**出参** 数组<[`RoleRowVO`](#rolerowvo)>

### `POST /api/platform/roles/{roleNo}/archive`

归档角色（内置角色服务端拒绝）。

权限码 `org:role:update` · `PlatformController#archiveRole`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `roleNo` | `String` | 是 |

**出参** [`RoleRowVO`](#rolerowvo)

### `POST /api/platform/roles/{roleNo}/unarchive`

权限码 `org:role:update` · `PlatformController#unarchiveRole`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `roleNo` | `String` | 是 |

**出参** [`RoleRowVO`](#rolerowvo)

### `GET /api/platform/staff-performance`

权限码 `org:performance:read` · `OrgController#staffPerformance`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |
| 查询 | `role` | `String` | 否 |

**出参** 分页<[`StaffPerformance`](#staffperformance)>

### `GET /api/platform/sys-params`

权限码 `system:param:read` · `SysConfigController#sysParams`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `groupName` | `String` | 否 |

**出参** 分页<[`SysParamEntry`](#sysparamentry)>

### `POST /api/platform/sys-params`

权限码 `system:param:update` · `SysConfigController#createSysParam`

**请求体** `SysParam`

**出参** [`SysParamEntry`](#sysparamentry)

### `POST /api/platform/sys-params/{paramKey}`

权限码 `system:param:update` · `SysConfigController#updateSysParam`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `paramKey` | `String` | 是 |

**请求体** `SysParam`

**出参** [`SysParamEntry`](#sysparamentry)

### `GET /api/platform/tax-settings`

权限码 `system:tax:read` · `SysSettingController#taxSettings`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `country` | `String` | 否 |

**出参** 分页<[`TaxSetting`](#taxsetting)>

### `POST /api/platform/tax-settings`

权限码 `system:tax:update` · `SysSettingController#createTaxSetting`

**请求体** `SysTaxSetting`

**出参** [`TaxSetting`](#taxsetting)

### `POST /api/platform/tax-settings/{country}`

权限码 `system:tax:update` · `SysSettingController#updateTaxSetting`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `country` | `String` | 是 |

**请求体** `SysTaxSetting`

**出参** [`TaxSetting`](#taxsetting)


## C 端（`/mp`，消费者会话）

38 个端点。

### `GET /mp/app/version`

版本检查：取该平台 `RELEASED` 且灰度 &gt; 0 的最高构建号版本。

**无权限码** · `MpMetaController#version`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `platform` | `String` | 是 |
| 查询 | `lang` | `String` | 否 |

**出参** [`AppVersionCheck`](#appversioncheck)

### `POST /mp/auth/login`

统一登录：grantType 分发（phone_otp/wechat_miniapp/wechat_oauth/apple/google）。

**无权限码** · `ConsumerAuthController#login`

**请求体** [`ConsumerLoginReq`](#consumerloginreq)

**出参** [`ConsumerLoginVO`](#consumerloginvo)

### `POST /mp/auth/logout`

**无权限码** · `ConsumerAuthController#logout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 请求头 | `auth` | `String` | 否 |

**出参** `对象（自由键）`

### `POST /mp/auth/otp`

发送 OTP。

**无权限码** · `ConsumerAuthController#otp`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `POST /mp/auth/password/reset`

密码重置：**能力未开通**（凭据库 pb_auth 未建，[未完成清单 B6]）——如实 400，不假 ok。

**无权限码** · `ConsumerAuthController#resetPassword`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `POST /mp/auth/register`

注册 = OTP 验证 + 建户 + 直接发 token（复用 phone_otp 登录链路，带显式昵称）。

**无权限码** · `ConsumerAuthController#register`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `GET /mp/faq`

帮助中心 FAQ / 报障问题列表（只取启用项，按排序号升序）。

**无权限码** · `MpMetaController#faq`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `category` | `String` | 否 |
| 查询 | `lang` | `String` | 否 |

**出参** 数组<[`FaqItem`](#faqitem)>

### `GET /mp/nearby/cabinets`

附近机柜（镜像 c-app `NearbyCabinet[]`）。

**无权限码** · `MpNearbyController#nearby`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `keyword` | `String` | 否 |
| 查询 | `returnable` | `Boolean` | 否 |
| 查询 | `lat` | `Double` | 否 |
| 查询 | `lng` | `Double` | 否 |

**出参** `List<对象（自由键）>`

### `GET /mp/nearby/cabinets/{cabinetNo}/availability`

借出可用性校验（借出确认页，镜像 c-app `CabinetAvailability`）。

**无权限码** · `MpNearbyController#availability`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**出参** `对象（自由键）`

### `GET /mp/notice`

运营公告（首页公告条 + 公告页）。

**无权限码** · `MpSupportController#notices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`NoticeVO`](#noticevo)>

### `GET /mp/sites/{siteNo}`

门店详情（镜像 c-app `StoreDetail`）。

**无权限码** · `MpNearbyController#site`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** `对象（自由键）`

### `POST /mp/trade/deposit/free`

免押预授权（借出前）：冻结额度走 `pay_auth`（StubPaymentPort，ADR-005 不真扣款）。

**无权限码** · `RentController#depositFree`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `GET /mp/trade/orders`

我的订单（属主过滤，只见自己）。

**无权限码** · `RentController#myOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`RentOrder`](#rentorder)>

### `GET /mp/trade/orders/ongoing`

进行中订单（首页快捷入口）：无则 200 + null data，端上按无单渲染。

**无权限码** · `RentController#ongoing`

**出参** [`RentOrder`](#rentorder)

### `POST /mp/trade/orders/rent`

扫码借出：`{cabinetNo`} → 创单（免押/弹仓骨架），返回订单号 + 充电宝 + 指令号。

**无权限码** · `RentController#rent`

**请求体** `对象（字符串值）`

**出参** [`RentResult`](#rentresult)

### `GET /mp/trade/orders/{orderNo}`

订单详情：属主鉴权，非本人 → 403（防横向越权 IDOR）。

**无权限码** · `RentController#detail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** [`RentOrder`](#rentorder)

### `POST /mp/trade/orders/{orderNo}/buyout`

买断：不还了，按 `sys_biz_rule` 计费兜底分区的买断价结单（属主鉴权）。

**无权限码** · `RentController#buyout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** [`RentOrder`](#rentorder)

### `POST /mp/trade/pay`

支付（`scene`=ORDER/…）。

**无权限码** · `RentController#pay`

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `GET /mp/user/coupons`

我的券包 / 领券中心。

**无权限码** · `MpSupportController#myCoupons`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`UserCouponVO`](#usercouponvo)>

### `POST /mp/user/coupons/{couponNo}/claim`

领券。

**无权限码** · `MpSupportController#claim`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `couponNo` | `String` | 是 |

**出参** [`UserCouponVO`](#usercouponvo)

### `GET /mp/user/favorites`

**无权限码** · `MpUserController#favorites`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`FavoriteItem`](#favoriteitem)>

### `POST /mp/user/favorites/{siteNo}`

收藏 / 取消收藏（切换）。

**无权限码** · `MpUserController#toggleFavorite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** `boolean`

### `GET /mp/user/invoice-titles`

**无权限码** · `MpUserController#invoiceTitles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`InvoiceTitleItem`](#invoicetitleitem)>

### `POST /mp/user/invoice-titles`

**无权限码** · `MpUserController#saveInvoiceTitle`

**请求体** `UsrInvoiceTitle`

**出参** [`InvoiceTitleItem`](#invoicetitleitem)

### `GET /mp/user/invoices`

**无权限码** · `MpUserController#invoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`InvoiceItem`](#invoiceitem)>

### `POST /mp/user/invoices`

**无权限码** · `MpUserController#applyInvoice`

**请求体** `UsrInvoice`

**出参** [`InvoiceItem`](#invoiceitem)

### `POST /mp/user/logoff`

提交注销申请；返回冷静期截止时间，期内可撤销。

**无权限码** · `MpUserController#applyLogoff`

**出参** [`LogoffItem`](#logoffitem)

### `GET /mp/user/membership`

会员方案列表（C-MB 选购页）：全部在售方案 + 我是否已开通。

**无权限码** · `MpUserController#membership`

**出参** 数组<[`MembershipPlanVO`](#membershipplanvo)>

### `GET /mp/user/messages`

**无权限码** · `MpUserController#messages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `type` | `String` | 否 |
| 查询 | `read` | `Boolean` | 否 |

**出参** 分页<[`MessageItem`](#messageitem)>

### `POST /mp/user/messages/{messageNo}/read`

**无权限码** · `MpUserController#markRead`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `messageNo` | `String` | 是 |

**出参** [`MessageItem`](#messageitem)

### `GET /mp/user/profile`

我的资料（镜像 c-app `UserProfile`）。

**无权限码** · `MpUserController#profile`

**出参** `对象（自由键）`

### `POST /mp/user/profile`

资料编辑：仅 `nickname`/`avatar`。

**无权限码** · `MpUserController#updateProfile`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `GET /mp/user/recharge-packages`

可购充值套餐，按用户所在市场过滤。

**无权限码** · `MpUserController#rechargePackages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `country` | `String` | 否 |

**出参** 数组<[`RechargePackageRow`](#rechargepackagerow)>

### `POST /mp/user/report`

自助报障 —— 落 `cs_ticket`（C 端诉求的**唯一受理单**，[api/README §6A.2]）， 随即按 `md_problem.suggested_action` 字典分流到工单 / 退款 / 人工会话。

**无权限码** · `MpSupportController#report`

**请求体** [`ReportReq`](#reportreq)

**出参** [`ReportResultVO`](#reportresultvo)

### `GET /mp/user/reports`

我的报障列表。

**无权限码** · `MpSupportController#myReports`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CsTicketVO`](#csticketvo)>

### `GET /mp/user/reports/{reportNo}`

报障进度 / 结果（联动工单与退款状态）。

**无权限码** · `MpSupportController#myReport`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `reportNo` | `String` | 是 |

**出参** [`CsTicketVO`](#csticketvo)

### `GET /mp/user/wallet`

**无权限码** · `MpUserController#wallet`

**出参** [`WalletOverview`](#walletoverview)

### `GET /mp/user/wallet/txns`

路径是 `/txns` 不是 `/transactions`（[api §十 冲突裁决 4]，以 c-app 现状为准）。

**无权限码** · `MpUserController#walletTxns`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** 分页<[`WalletTxnRow`](#wallettxnrow)>


## 内部（`/internal`，服务间与批处理，不对外暴露）

15 个端点。

### `GET /internal/dev/cabinets/assignable`

`CabinetQueryPort` 的服务端（仅服务间调用）。

**无权限码** · `CabinetQueryInternalController#assignable`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `keyword` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`CabinetBrief`](#cabinetbrief)>

### `POST /internal/dev/cabinets/briefs`

**无权限码** · `CabinetQueryInternalController#briefs`

**请求体** `List<String>`

**出参** 数组<[`CabinetBrief`](#cabinetbrief)>

### `POST /internal/dev/ownership/by-locations`

`DeviceOwnershipPort` 的服务端（仅服务间调用）。

**无权限码** · `OwnershipInternalController#byLocations`

**请求体** `对象（自由键）`

**出参** `int`

### `POST /internal/dev/ownership/cabinet`

**无权限码** · `OwnershipInternalController#cabinet`

**请求体** `对象（自由键）`

**出参** `int`

### `GET /internal/dev/ownership/exists`

**无权限码** · `OwnershipInternalController#exists`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `cabinetNo` | `String` | 是 |

**出参** `boolean`

### `GET /internal/gw/heartbeats`

`TelemetryQueryPort` 的服务端（仅服务间调用）。

**无权限码** · `TelemetryInternalController#heartbeats`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `cabinetNo` | `String` | 是 |
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`HeartbeatRecord`](#heartbeatrecord)>

### `GET /internal/gw/vendors`

权限码 `device:vendor:read` · `VendorController#vendors`

**出参** 数组<[`VendorVO`](#vendorvo)>

### `POST /internal/gw/vendors/{vendorCode}/config`

权限码 `device:vendor:config` · `VendorController#saveConfig`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `vendorCode` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`VendorVO`](#vendorvo)

### `POST /internal/gw/vendors/{vendorCode}/test`

连通性探测（S7）：「配置能存」≠「对得上」，填错要在配置页当场知道。

权限码 `device:vendor:config` · `VendorController#test`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `vendorCode` | `String` | 是 |

**出参** [`VendorProbeResult`](#vendorproberesult)

### `POST /internal/platform/notify/send`

**无权限码** · `NotifyController#send`

**请求体** [`SendReq`](#sendreq)

**出参** [`SendResult`](#sendresult)

### `POST /internal/platform/sites/briefs`

`SiteQueryPort` 的服务端（`/internal/**`，仅服务间调用，不对外暴露）。

**无权限码** · `SiteInternalController#briefs`

**请求体** `List<String>`

**出参** 数组<[`SiteBrief`](#sitebrief)>

### `POST /internal/trade/orders/{orderNo}/return`

归还结单：IN_USE→RETURNED→SETTLED（计费）。

**无权限码** · `TradeInternalController#returnOrder`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**请求体** `对象（字符串值）`

**出参** [`OkResult`](#okresult)

### `POST /internal/trade/price-adjustments/tick`

执行到点的调价 / 到期的恢复。

**无权限码** · `OperationController#tick`

**出参** `对象（自由键）`

### `POST /internal/trade/settlements/generate`

周期出账（[api/README §4.3]）—— **批处理作业调用，非页面入口**，内网受信， 故不挂 `

**无权限码** · `FinanceController#generateSettlements`

**请求体** `对象（字符串值）`

**出参** `List<String>`

### `POST /internal/user/credit/blacklist`

内部拉黑/解除。

权限码 `user:risk:update` · `UserController#blacklist`

**请求体** `对象（自由键）`

**出参** [`OkResult`](#okresult)


---

## 数据结构

共 218 个。同一结构常被多个端点复用，故在此定义一次、上文引用。

### AcceptReq

| 字段 | 类型 |
|---|---|
| `assigneeNo` | `String` |

### AckResult

| 字段 | 类型 |
|---|---|
| `alarmNo` | `String` |
| `status` | `String` |

### AdCampaignVO

| 字段 | 类型 |
|---|---|
| `adNo` | `String` |
| `advertiserNo` | `String` |
| `advertiser` | `String` |
| `creative` | `String` |
| `budget` | `BigDecimal` |
| `currency` | `String` |
| `targeting` | `String` |
| `status` | `String` |
| `startAt` | `String` |
| `endAt` | `String` |

### AdDeliveryVO

| 字段 | 类型 |
|---|---|
| `deliveryNo` | `String` |
| `adNo` | `String` |
| `slotNo` | `String` |
| `cabinetNo` | `String` |
| `impressions` | `Integer` |
| `plays` | `Integer` |
| `date` | `String` |

### AdSlotVO

| 字段 | 类型 |
|---|---|
| `slotNo` | `String` |
| `cabinetNo` | `String` |
| `position` | `String` |
| `size` | `String` |
| `status` | `String` |
| `createdAt` | `String` |

### Agent

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `name` | `String` |
| `contact` | `String` |
| `regionScope` | `String` |
| `agentType` | `String` |
| `shareRate` | `double` |
| `cabinetCount` | `int` |
| `status` | `String` |

### AgentAccount

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `agentNo` | `String` |
| `agentName` | `String` |
| `username` | `String` |
| `loginPhone` | `String` |
| `status` | `String` |
| `dataScope` | `String` |
| `createdAt` | `String` |

### AgentCommission

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `agentNo` | `String` |
| `agentName` | `String` |
| `basis` | `String` |
| `rate` | `BigDecimal` |
| `fixedAmount` | `BigDecimal` |
| `currency` | `String` |
| `mode` | `String` |
| `effectiveAt` | `String` |
| `status` | `String` |

### AgentPerformance

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `agentName` | `String` |
| `gmv` | `BigDecimal` |
| `cabinetCount` | `Integer` |
| `onlineRate` | `BigDecimal` |
| `rank` | `Integer` |
| `currency` | `String` |

### AlarmCode

| 字段 | 类型 |
|---|---|
| `code` | `String` |
| `message` | `String` |
| `messageEn` | `String` |
| `messageAr` | `String` |
| `level` | `String` |
| `suggestion` | `String` |
| `autoWorkOrder` | `boolean` |

### AlarmNotice

| 字段 | 类型 |
|---|---|
| `noticeNo` | `String` |
| `alarmNo` | `String` |
| `channel` | `String` |
| `target` | `String` |
| `sentAt` | `String` |
| `status` | `String` |
| `failReason` | `String` |
| `idempotencyKey` | `String` |
| `resendOf` | `String` |

### AlarmRecord

| 字段 | 类型 |
|---|---|
| `alarmNo` | `String` |
| `cabinetNo` | `String` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `agentNo` | `String` |
| `vendorCode` | `String` |
| `alarmCode` | `String` |
| `vendorErrorCode` | `String` |
| `level` | `String` |
| `source` | `String` |
| `occurredAt` | `String` |
| `status` | `String` |
| `workOrderNo` | `String` |
| `remark` | `String` |
| `dedupKey` | `String` |
| `count` | `Integer` |

### AlarmRule

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `alarmCode` | `String` |
| `target` | `String` |
| `channel` | `String` |
| `method` | `String` |
| `quietStart` | `String` |
| `quietEnd` | `String` |
| `escalateMinutes` | `Integer` |
| `status` | `String` |

### AppVersion

| 字段 | 类型 |
|---|---|
| `versionId` | `String` |
| `versionNo` | `String` |
| `platform` | `String` |
| `buildNo` | `Integer` |
| `releaseNote` | `String` |
| `releaseNoteEn` | `String` |
| `releaseNoteAr` | `String` |
| `forceUpdate` | `Boolean` |
| `minSupported` | `String` |
| `rolloutPercent` | `BigDecimal` |
| `downloadUrl` | `String` |
| `status` | `String` |
| `releasedAt` | `String` |

### AppVersionCheck

| 字段 | 类型 |
|---|---|
| `hasUpdate` | `Boolean` |
| `versionNo` | `String` |
| `buildNo` | `Integer` |
| `forceUpdate` | `Boolean` |
| `minSupported` | `String` |
| `releaseNote` | `String` |
| `downloadUrl` | `String` |

### ApplyResult

| 字段 | 类型 |
|---|---|
| `applyNo` | `String` |
| `status` | `String` |
| `operatorNo` | `String` |

### ApplyView

| 字段 | 类型 |
|---|---|
| `applyNo` | `String` |
| `source` | `String` |
| `status` | `String` |
| `operatorName` | `String` |
| `operatorType` | `String` |
| `phoneMask` | `String` |
| `emailMask` | `String` |
| `regionScope` | `String` |
| `shareRate` | `BigDecimal` |
| `payload` | `String` |
| `principalNo` | `String` |
| `rejectReason` | `String` |
| `submittedBy` | `String` |
| `submittedAt` | `LocalDateTime` |
| `reviewedBy` | `String` |
| `reviewedAt` | `LocalDateTime` |
| `operatorNo` | `String` |
| `phoneAlreadyKnown` | `boolean` |
| `knownEmailMask` | `String` |

### AssignReq

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `targetType` | `String` |
| `targetNo` | `String` |
| `action` | `String` |
| `operator` | `String` |

### AssignmentLog

| 字段 | 类型 |
|---|---|
| `assignNo` | `String` |
| `agentNo` | `String` |
| `targetType` | `String` |
| `targetNo` | `String` |
| `action` | `String` |
| `operator` | `String` |
| `createdAt` | `String` |

### AttentionItem

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `kind` | `String` |
| `severity` | `String` |
| `detail` | `String` |

### AuditDetail

| 字段 | 类型 |
|---|---|
| `id` | `String` |
| `actor` | `String` |
| `actorName` | `String` |
| `action` | `String` |
| `targetType` | `String` |
| `targetNo` | `String` |
| `target` | `String` |
| `detail` | `String` |
| `ip` | `String` |
| `createdAt` | `String` |
| `requestId` | `String` |
| `userAgent` | `String` |
| `changes` | `List<AuditFieldChange>` |

### AuditLogEntry

| 字段 | 类型 |
|---|---|
| `id` | `String` |
| `actor` | `String` |
| `actorName` | `String` |
| `action` | `String` |
| `targetType` | `String` |
| `targetNo` | `String` |
| `target` | `String` |
| `detail` | `String` |
| `ip` | `String` |
| `createdAt` | `String` |

### AuditReq

| 字段 | 类型 |
|---|---|
| `approve` | `boolean` |
| `rejectReason` | `String` |
| `shareRate` | `BigDecimal` |
| `regionScope` | `String` |

### BankEntry

| 字段 | 类型 |
|---|---|
| `bankCode` | `String` |
| `bankName` | `String` |
| `bankNameEn` | `String` |
| `country` | `String` |
| `currency` | `String` |
| `swiftPrefix` | `String` |
| `ibanLength` | `Integer` |
| `status` | `String` |

### BillingDefaultRule

| 字段 | 类型 |
|---|---|
| `freeMinutes` | `Integer` |
| `unitMinutes` | `Integer` |
| `capDaily` | `BigDecimal` |
| `buyoutPrice` | `BigDecimal` |
| `overdueHours` | `Integer` |

### BizRules

| 字段 | 类型 |
|---|---|
| `withdraw` | [`WithdrawRule`](#withdrawrule) |
| `reservation` | [`ReservationRule`](#reservationrule) |
| `billing` | [`BillingDefaultRule`](#billingdefaultrule) |
| `currency` | `String` |
| `updatedAt` | `String` |

### BrandEntry

| 字段 | 类型 |
|---|---|
| `brandNo` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `logoUrl` | `String` |
| `supportPhone` | `String` |
| `marketCode` | `String` |
| `status` | `String` |
| `archivedAt` | `String` |

### CUserRow

| 字段 | 类型 |
|---|---|
| `cUserNo` | `String` |
| `nickname` | `String` |
| `avatar` | `String` |
| `phone` | `String` |
| `creditScore` | `Integer` |
| `blacklisted` | `Boolean` |
| `orders` | `Long` |
| `registeredAt` | `String` |

### Cabinet

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `sn` | `String` |
| `vendorCode` | `String` |
| `model` | `String` |
| `locationNo` | `String` |
| `locationName` | `String` |
| `slotTotal` | `int` |
| `availableCount` | `int` |
| `onlineStatus` | `String` |
| `status` | `String` |
| `fwVersion` | `String` |
| `lastHeartbeatAt` | `String` |
| `siteNo` | `String` |

### CabinetBrief

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `name` | `String` |
| `agentNo` | `String` |
| `siteNo` | `String` |

### CabinetDetail

| 字段 | 类型 |
|---|---|
| `cabinet` | [`Cabinet`](#cabinet) |
| `slots` | 数组<[`Slot`](#slot)> |

### CabinetMonitorRow

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `locationName` | `String` |
| `online` | `Boolean` |
| `heartbeatAt` | `String` |
| `signal` | `Integer` |
| `temp` | `Integer` |
| `faultCount` | `Integer` |

### CampaignVO

| 字段 | 类型 |
|---|---|
| `campaignNo` | `String` |
| `name` | `String` |
| `kind` | `String` |
| `rule` | `String` |
| `status` | `String` |
| `startAt` | `String` |
| `endAt` | `String` |

### CloseReq

| 字段 | 类型 |
|---|---|
| `closeReason` | `String` |
| `auditResult` | `String` |
| `auditNote` | `String` |
| `auditorNo` | `String` |

### CodeBatchRow

| 字段 | 类型 |
|---|---|
| `batchNo` | `String` |
| `vendorCode` | `String` |
| `codeType` | `String` |
| `rangeStart` | `String` |
| `rangeEnd` | `String` |
| `total` | `Integer` |
| `bound` | `Integer` |
| `producedAt` | `String` |
| `status` | `String` |

### CommandRecord

| 字段 | 类型 |
|---|---|
| `commandId` | `String` |
| `sn` | `String` |
| `cabinetNo` | `String` |
| `type` | `String` |
| `slotIndex` | `Integer` |
| `status` | `String` |
| `retry` | `Integer` |
| `orderNo` | `String` |
| `operator` | `String` |
| `sentAt` | `String` |
| `confirmedAt` | `String` |
| `createdAt` | `String` |

### CommandResult

| 字段 | 类型 |
|---|---|
| `commandId` | `String` |

### ComplaintCreateReq

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `userNo` | `String` |
| `issueType` | `String` |
| `description` | `String` |
| `screenshotUrl` | `String` |

### ComplaintHandleReq

| 字段 | 类型 |
|---|---|
| `resolution` | `String` |
| `resolutionNote` | `String` |

### ConsumerFunnelStage

| 字段 | 类型 |
|---|---|
| `stage` | `String` |
| `users` | `Long` |
| `rate` | `Double` |
| `dropRate` | `Double` |

### ConsumerInsight

| 字段 | 类型 |
|---|---|
| `funnel` | 数组<[`ConsumerFunnelStage`](#consumerfunnelstage)> |
| `profiles` | 数组<[`ConsumerProfileSlice`](#consumerprofileslice)> |
| `totalUsers` | `Long` |

### ConsumerLoginReq

| 字段 | 类型 |
|---|---|
| `grantType` | `String` |
| `tenantNo` | `String` |
| `phone` | `String` |
| `otp` | `String` |
| `regionCode` | `String` |
| `jsCode` | `String` |
| `code` | `String` |
| `unionid` | `String` |
| `identityToken` | `String` |
| `idToken` | `String` |
| `nickname` | `String` |
| `avatar` | `String` |

### ConsumerLoginVO

| 字段 | 类型 |
|---|---|
| `token` | `String` |
| `cUserNo` | `String` |
| `isNew` | `boolean` |
| `tenantNo` | `String` |

### ConsumerProfileSlice

| 字段 | 类型 |
|---|---|
| `dim` | `String` |
| `dimLabel` | `String` |
| `label` | `String` |
| `value` | `Long` |
| `share` | `Double` |

### ConsumerSegment

| 字段 | 类型 |
|---|---|
| `segmentNo` | `String` |
| `segment` | `String` |
| `userCount` | `Long` |
| `repeatRate` | `Double` |
| `avgOrderValue` | `BigDecimal` |
| `currency` | `String` |

### Contract

| 字段 | 类型 |
|---|---|
| `contractNo` | `String` |
| `venueName` | `String` |
| `siteName` | `String` |
| `shareRate` | `double` |
| `entryFee` | `double` |
| `startAt` | `String` |
| `endAt` | `String` |
| `status` | `String` |
| `attachments` | `List<ContractAttachment>` |

### CouponTplVO

| 字段 | 类型 |
|---|---|
| `couponNo` | `String` |
| `name` | `String` |
| `type` | `String` |
| `value` | `BigDecimal` |
| `threshold` | `BigDecimal` |
| `currency` | `String` |
| `stock` | `Integer` |
| `issued` | `Integer` |
| `status` | `String` |
| `archivedAt` | `String` |

### CreditScoreAdjustReq

| 字段 | 类型 |
|---|---|
| `delta` | `Integer` |
| `reason` | `String` |

### CreditScoreChange

| 字段 | 类型 |
|---|---|
| `changeNo` | `String` |
| `cUserNo` | `String` |
| `before` | `Integer` |
| `after` | `Integer` |
| `delta` | `Integer` |
| `reason` | `String` |
| `operatorName` | `String` |
| `createdAt` | `String` |

### CsMessageVO

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `sessionNo` | `String` |
| `senderType` | `String` |
| `senderNo` | `String` |
| `content` | `String` |
| `attach` | `String` |
| `createdAt` | `String` |

### CsSessionVO

| 字段 | 类型 |
|---|---|
| `sessionNo` | `String` |
| `userNo` | `String` |
| `agentName` | `String` |
| `lastMessage` | `String` |
| `status` | `String` |
| `updatedAt` | `String` |

### CsTicketVO

| 字段 | 类型 |
|---|---|
| `ticketNo` | `String` |
| `userNo` | `String` |
| `orderNo` | `String` |
| `cabinetNo` | `String` |
| `problemNo` | `String` |
| `issue` | `String` |
| `channel` | `String` |
| `status` | `String` |
| `handlerNo` | `String` |
| `woNo` | `String` |
| `refundNo` | `String` |
| `createdAt` | `String` |

### DashboardAlert

| 字段 | 类型 |
|---|---|
| `id` | `String` |
| `type` | `String` |
| `cabinetNo` | `String` |
| `message` | `String` |
| `href` | `String` |

### DashboardRankItem

| 字段 | 类型 |
|---|---|
| `rank` | `Integer` |
| `siteName` | `String` |
| `gmv` | `BigDecimal` |
| `orderCount` | `Long` |
| `currency` | `String` |

### DashboardStats

| 字段 | 类型 |
|---|---|
| `gmvToday` | `BigDecimal` |
| `ordersToday` | `Long` |
| `activeCabinets` | `Integer` |
| `onlineRate` | `Double` |
| `openWorkOrders` | `Long` |
| `currency` | `String` |
| `trend` | 数组<[`DashboardTrendDay`](#dashboardtrendday)> |
| `todos` | [`DashboardTodos`](#dashboardtodos) |
| `alerts` | 数组<[`DashboardAlert`](#dashboardalert)> |
| `rankings` | 数组<[`DashboardRankItem`](#dashboardrankitem)> |

### DashboardTodos

| 字段 | 类型 |
|---|---|
| `pendingWorkOrders` | `Long` |
| `pendingRefunds` | `Long` |
| `pendingWithdrawals` | `Long` |

### DashboardTrendDay

| 字段 | 类型 |
|---|---|
| `day` | `String` |
| `gmv` | `BigDecimal` |
| `orders` | `Long` |

### DataScopeEntry

| 字段 | 类型 |
|---|---|
| `subjectType` | `String` |
| `subjectNo` | `String` |
| `scopeType` | `String` |
| `scopeRefs` | `String` |

### DataScopeReq

| 字段 | 类型 |
|---|---|
| `scopeType` | `String` |
| `scopeRefs` | `String` |

### Department

| 字段 | 类型 |
|---|---|
| `deptNo` | `String` |
| `name` | `String` |
| `parent` | `String` |
| `memberCount` | `long` |
| `leader` | `String` |
| `path` | `String` |
| `sort` | `Integer` |
| `status` | `String` |

### DepositRecord

| 字段 | 类型 |
|---|---|
| `depositNo` | `String` |
| `orderNo` | `String` |
| `userNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `arrearsAmount` | `BigDecimal` |
| `releasedAt` | `String` |
| `createdAt` | `String` |
| `buyoutAmount` | `BigDecimal` |
| `buyoutAt` | `String` |
| `dunCount` | `Integer` |
| `lastDunAt` | `String` |
| `lastDunChannel` | `String` |
| `operatorName` | `String` |
| `note` | `String` |

### DeviceLogRow

| 字段 | 类型 |
|---|---|
| `logNo` | `String` |
| `cabinetNo` | `String` |
| `stream` | `String` |
| `direction` | `String` |
| `eventType` | `String` |
| `payload` | `String` |
| `vendorCode` | `String` |
| `occurredAt` | `String` |
| `result` | `String` |

### DictEntry

| 字段 | 类型 |
|---|---|
| `dictNo` | `String` |
| `group` | `String` |
| `code` | `String` |
| `label` | `String` |
| `sort` | `Integer` |
| `enabled` | `Boolean` |

### Employee

| 字段 | 类型 |
|---|---|
| `employeeNo` | `String` |
| `name` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `deptName` | `String` |
| `roleName` | `String` |
| `status` | `String` |

### ExceptionHandleReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |
| `action` | `String` |
| `handleResult` | `String` |
| `refundNo` | `String` |
| `workOrderNo` | `String` |

### FaqItem

| 字段 | 类型 |
|---|---|
| `problemNo` | `String` |
| `category` | `String` |
| `title` | `String` |
| `answer` | `String` |
| `suggestedAction` | `String` |
| `sortNo` | `Integer` |

### FavoriteItem

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `createdAt` | `String` |

### FreeOrder

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `userNo` | `String` |
| `nickname` | `String` |
| `whitelistReason` | `String` |
| `waivedAmount` | `BigDecimal` |
| `currency` | `String` |
| `siteName` | `String` |
| `cabinetNo` | `String` |
| `startedAt` | `String` |
| `endedAt` | `String` |
| `duration` | `Integer` |

### FreeOrderStats

| 字段 | 类型 |
|---|---|
| `monthCount` | `long` |
| `waivedTotal` | `BigDecimal` |
| `currency` | `String` |

### FreeUserWhitelist

| 字段 | 类型 |
|---|---|
| `whitelistNo` | `String` |
| `userNo` | `String` |
| `nickname` | `String` |
| `phone` | `String` |
| `reason` | `String` |
| `quotaType` | `String` |
| `quotaValue` | `BigDecimal` |
| `usedValue` | `BigDecimal` |
| `currency` | `String` |
| `validFrom` | `String` |
| `validTo` | `String` |
| `grantedBy` | `String` |
| `status` | `String` |

### GeoReady

| 字段 | 类型 |
|---|---|
| `withGeo` | `int` |
| `total` | `int` |

### HandleReq

| 字段 | 类型 |
|---|---|
| `assigneeNo` | `String` |
| `photos` | `String` |
| `handleNote` | `String` |
| `partChanged` | `Boolean` |
| `deviceChanged` | `Boolean` |

### HeartbeatRecord

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `beatAt` | `String` |
| `metrics` | `String` |

### InspectionPlan

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `route` | `String` |
| `frequency` | `String` |
| `cron` | `String` |
| `nextAt` | `String` |
| `assignee` | `String` |
| `active` | `Boolean` |
| `lastRunAt` | `String` |
| `lastRunPeriod` | `String` |
| `lastRunWoNos` | `List<String>` |

### InterveneReq

| 字段 | 类型 |
|---|---|
| `action` | `String` |
| `reason` | `String` |
| `amount` | `BigDecimal` |
| `operatorName` | `String` |

### InventoryTransfer

| 字段 | 类型 |
|---|---|
| `transferNo` | `String` |
| `fromLocation` | `String` |
| `toLocation` | `String` |
| `fromType` | `String` |
| `fromRef` | `String` |
| `toType` | `String` |
| `toRef` | `String` |
| `itemType` | `String` |
| `powerbankCount` | `Integer` |
| `status` | `String` |
| `operator` | `String` |
| `createdAt` | `String` |

### InventoryTransferDetail

| 字段 | 类型 |
|---|---|
| `transfer` | [`InventoryTransfer`](#inventorytransfer) |
| `items` | 数组<[`TransferItem`](#transferitem)> |

### Invoice

| 字段 | 类型 |
|---|---|
| `invoiceNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `amount` | `BigDecimal` |
| `vatTrn` | `String` |
| `currency` | `String` |
| `status` | `String` |
| `issuedAt` | `String` |
| `fileUrl` | `String` |
| `sourceType` | `String` |
| `sourceNo` | `String` |
| `invoiceCode` | `String` |
| `invoiceNumber` | `String` |
| `issuedBy` | `String` |
| `voidedAt` | `String` |
| `voidedBy` | `String` |
| `voidReason` | `String` |

### InvoiceItem

| 字段 | 类型 |
|---|---|
| `invoiceNo` | `String` |
| `titleNo` | `String` |
| `title` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `fileUrl` | `String` |
| `appliedAt` | `String` |
| `issuedAt` | `String` |

### InvoiceSaveReq

| 字段 | 类型 |
|---|---|
| `invoiceNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `amount` | `BigDecimal` |
| `vatTrn` | `String` |
| `currency` | `String` |
| `status` | `String` |
| `orderNos` | `List<String>` |

### InvoiceTitleItem

| 字段 | 类型 |
|---|---|
| `titleNo` | `String` |
| `type` | `String` |
| `title` | `String` |
| `vatTrn` | `String` |
| `isDefault` | `boolean` |

### InvoiceView

| 字段 | 类型 |
|---|---|
| `invoice` | [`Invoice`](#invoice) |
| `orderNos` | `List<String>` |

### Lead

| 字段 | 类型 |
|---|---|
| `leadNo` | `String` |
| `venueName` | `String` |
| `contact` | `String` |
| `stage` | `String` |
| `owner` | `String` |
| `ownerType` | `String` |
| `siteNo` | `String` |
| `expectSites` | `Integer` |
| `nextFollowAt` | `String` |
| `updatedAt` | `String` |

### LeadFollowUpReq

| 字段 | 类型 |
|---|---|
| `channel` | `String` |
| `toStage` | `String` |
| `content` | `String` |
| `nextAt` | `String` |

### LedgerEntry

| 字段 | 类型 |
|---|---|
| `entryNo` | `String` |
| `voucherNo` | `String` |
| `orderNo` | `String` |
| `accountNo` | `String` |
| `account` | `String` |
| `direction` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `summary` | `String` |
| `bizType` | `String` |
| `bizNo` | `String` |
| `createdAt` | `String` |

### LedgerLine

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `account` | `String` |
| `direction` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |

### LedgerPostReq

| 字段 | 类型 |
|---|---|
| `voucherNo` | `String` |
| `orderNo` | `String` |
| `bizType` | `String` |
| `bizNo` | `String` |
| `summary` | `String` |
| `lines` | 数组<[`LedgerLine`](#ledgerline)> |

### Location

| 字段 | 类型 |
|---|---|
| `locationNo` | `String` |
| `name` | `String` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `spotDesc` | `String` |
| `cabinetCount` | `Integer` |
| `status` | `String` |

### LoginReq

| 字段 | 类型 |
|---|---|
| `username` | `String` |
| `password` | `String` |
| `role` | `String` |
| `agentNo` | `String` |
| `phone` | `String` |
| `otp` | `String` |

### LoginResp

| 字段 | 类型 |
|---|---|
| `token` | `String` |
| `username` | `String` |
| `role` | `String` |
| `agentNo` | `String` |
| `perms` | `List<String>` |
| `principalNo` | `String` |
| `operators` | 数组<[`OperatorMembership`](#operatormembership)> |

### LoginSetting

| 字段 | 类型 |
|---|---|
| `country` | `String` |
| `countryName` | `String` |
| `otpEnabled` | `Boolean` |
| `passwordEnabled` | `Boolean` |
| `appleEnabled` | `Boolean` |
| `googleEnabled` | `Boolean` |
| `otpExpireSec` | `Integer` |
| `otpDailyLimit` | `Integer` |
| `forceRealName` | `Boolean` |

### LogoffItem

| 字段 | 类型 |
|---|---|
| `cUserNo` | `String` |
| `requestedAt` | `String` |
| `coolingUntil` | `String` |
| `status` | `String` |
| `purgedAt` | `String` |

### MarketCountry

| 字段 | 类型 |
|---|---|
| `countryCode` | `String` |
| `name` | `String` |
| `currency` | `String` |
| `timezone` | `String` |
| `compliance` | `String` |
| `cityCount` | `Integer` |
| `status` | `String` |

### MemberBenefit

| 字段 | 类型 |
|---|---|
| `level` | `String` |
| `name` | `String` |
| `rentDiscount` | `BigDecimal` |
| `freeMinutes` | `Integer` |
| `depositFree` | `Boolean` |
| `monthlyCoupons` | `Integer` |
| `pointsRate` | `BigDecimal` |
| `upgradePoints` | `Integer` |
| `status` | `String` |
| `updatedBy` | `String` |
| `updatedAt` | `String` |

### MemberCard

| 字段 | 类型 |
|---|---|
| `mbrNo` | `String` |
| `cUserNo` | `String` |
| `planNo` | `String` |
| `level` | `String` |
| `startAt` | `String` |
| `endAt` | `String` |
| `status` | `String` |
| `points` | `Integer` |
| `autoRenew` | `Boolean` |

### MemberCardGrantReq

| 字段 | 类型 |
|---|---|
| `cUserNo` | `String` |
| `planNo` | `String` |
| `level` | `String` |
| `months` | `Integer` |

### MemberRow

| 字段 | 类型 |
|---|---|
| `userNo` | `String` |
| `nickname` | `String` |
| `level` | `String` |
| `points` | `Integer` |
| `cardType` | `String` |
| `planNo` | `String` |
| `expireAt` | `String` |
| `status` | `String` |

### MembershipPlanVO

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `name` | `String` |
| `price` | `BigDecimal` |
| `benefits` | `List<String>` |
| `active` | `Boolean` |
| `expireAt` | `String` |

### MenuNode

| 字段 | 类型 |
|---|---|
| `menuNo` | `String` |
| `parentNo` | `String` |
| `name` | `String` |
| `nameAr` | `String` |
| `type` | `String` |
| `path` | `String` |
| `icon` | `String` |
| `sort` | `Integer` |
| `perm` | `String` |
| `children` | 数组<[`MenuNode`](#menunode)> |

### MessageItem

| 字段 | 类型 |
|---|---|
| `messageNo` | `String` |
| `type` | `String` |
| `title` | `String` |
| `body` | `String` |
| `read` | `boolean` |
| `readAt` | `String` |
| `createdAt` | `String` |

### MyApplyView

| 字段 | 类型 |
|---|---|
| `applyNo` | `String` |
| `status` | `String` |
| `operatorName` | `String` |
| `phoneMask` | `String` |
| `emailMask` | `String` |
| `rejectReason` | `String` |
| `submittedAt` | `LocalDateTime` |

### NoticeVO

| 字段 | 类型 |
|---|---|
| `noticeNo` | `String` |
| `title` | `String` |
| `titleEn` | `String` |
| `titleAr` | `String` |
| `content` | `String` |
| `contentEn` | `String` |
| `contentAr` | `String` |
| `type` | `String` |
| `pinned` | `boolean` |
| `startAt` | `String` |
| `endAt` | `String` |
| `status` | `String` |
| `publishedBy` | `String` |
| `createdAt` | `String` |
| `archivedAt` | `String` |

### NotifyBlacklistVO

| 字段 | 类型 |
|---|---|
| `blockNo` | `String` |
| `target` | `String` |
| `channel` | `String` |
| `reason` | `String` |
| `blockedAt` | `String` |
| `blockedBy` | `String` |
| `expireAt` | `String` |
| `releasedAt` | `String` |
| `releasedBy` | `String` |
| `status` | `String` |

### NotifyLogStats

| 字段 | 类型 |
|---|---|
| `sentToday` | `long` |
| `failedToday` | `long` |
| `failRate` | `BigDecimal` |
| `costToday` | `BigDecimal` |
| `currency` | `String` |

### NotifyLogVO

| 字段 | 类型 |
|---|---|
| `logNo` | `String` |
| `channel` | `String` |
| `templateNo` | `String` |
| `target` | `String` |
| `scene` | `String` |
| `sentAt` | `String` |
| `status` | `String` |
| `failReason` | `String` |
| `cost` | `BigDecimal` |
| `currency` | `String` |
| `idempotencyKey` | `String` |
| `resendOf` | `String` |

### NotifyResendReq

| 字段 | 类型 |
|---|---|
| `idempotencyKey` | `String` |

### NotifyTemplateVO

| 字段 | 类型 |
|---|---|
| `templateNo` | `String` |
| `name` | `String` |
| `channel` | `String` |
| `lang` | `String` |
| `scene` | `String` |
| `content` | `String` |
| `params` | `String` |
| `status` | `String` |

### NotifyTestSendReq

| 字段 | 类型 |
|---|---|
| `target` | `String` |
| `vars` | `对象（字符串值）` |
| `idempotencyKey` | `String` |

### OkResult

| 字段 | 类型 |
|---|---|
| `ok` | `boolean` |

### OnboardingReviewReq

| 字段 | 类型 |
|---|---|
| `approve` | `Boolean` |
| `note` | `String` |
| `reviewBy` | `String` |

### OpenApiApp

| 字段 | 类型 |
|---|---|
| `appNo` | `String` |
| `name` | `String` |
| `appKey` | `String` |
| `rateLimit` | `Integer` |
| `status` | `String` |
| `createdAt` | `String` |
| `appSecretMasked` | `String` |
| `secretResetAt` | `String` |

### OperationOverview

| 字段 | 类型 |
|---|---|
| `scale` | [`OverviewScale`](#overviewscale) |
| `business` | [`OverviewBusiness`](#overviewbusiness) |
| `trend` | 数组<[`TrendPoint`](#trendpoint)> |
| `ranking` | 数组<[`SiteRankRow`](#siterankrow)> |
| `attention` | 数组<[`AttentionItem`](#attentionitem)> |
| `scenes` | 数组<[`SceneShare`](#sceneshare)> |
| `geoReady` | [`GeoReady`](#geoready) |

### OperatorMembership

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `agentName` | `String` |
| `accountNo` | `String` |
| `displayName` | `String` |
| `isOwner` | `Boolean` |
| `isPrimary` | `Boolean` |
| `status` | `String` |

### OrderComplaint

| 字段 | 类型 |
|---|---|
| `complaintNo` | `String` |
| `orderNo` | `String` |
| `userNo` | `String` |
| `issueType` | `String` |
| `description` | `String` |
| `screenshotUrl` | `String` |
| `submittedAt` | `String` |
| `status` | `String` |
| `handlerName` | `String` |
| `handledAt` | `String` |
| `resolution` | `String` |
| `resolutionNote` | `String` |
| `workOrderNo` | `String` |

### OrderException

| 字段 | 类型 |
|---|---|
| `exceptionNo` | `String` |
| `orderNo` | `String` |
| `type` | `String` |
| `cabinetNo` | `String` |
| `userNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `handledBy` | `String` |
| `handledAt` | `String` |
| `createdAt` | `String` |
| `handleAction` | `String` |
| `handleResult` | `String` |
| `refundNo` | `String` |
| `workOrderNo` | `String` |

### OrderInterveneResult

| 字段 | 类型 |
|---|---|
| `order` | [`RentOrder`](#rentorder) |
| `intervention` | [`OrderIntervention`](#orderintervention) |

### OrderIntervention

| 字段 | 类型 |
|---|---|
| `interventionNo` | `String` |
| `orderNo` | `String` |
| `action` | `String` |
| `operatorName` | `String` |
| `reason` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `beforeStatus` | `String` |
| `afterStatus` | `String` |
| `createdAt` | `String` |

### OrderStats

| 字段 | 类型 |
|---|---|
| `count` | `Long` |
| `amount` | `BigDecimal` |
| `currency` | `String` |

### OtaReleaseRow

| 字段 | 类型 |
|---|---|
| `releaseNo` | `String` |
| `fwType` | `String` |
| `vendorCode` | `String` |
| `version` | `String` |
| `versionCode` | `Integer` |
| `artifactUrl` | `String` |
| `checksum` | `String` |
| `mandatory` | `Boolean` |
| `status` | `String` |
| `releaseNotes` | `String` |

### OtaRolloutRow

| 字段 | 类型 |
|---|---|
| `rolloutNo` | `String` |
| `releaseNo` | `String` |
| `fwVersion` | `String` |
| `vendorCode` | `String` |
| `strategy` | `String` |
| `scope` | `String` |
| `targetRef` | `String` |
| `progress` | `Integer` |
| `status` | `String` |
| `createdAt` | `String` |

### OtaTaskRow

| 字段 | 类型 |
|---|---|
| `taskNo` | `String` |
| `rolloutNo` | `String` |
| `cabinetNo` | `String` |
| `status` | `String` |
| `progress` | `Integer` |
| `previousVersion` | `String` |
| `error` | `String` |

### OverviewBusiness

| 字段 | 类型 |
|---|---|
| `orders` | `int` |
| `gmv` | `BigDecimal` |
| `currency` | `String` |
| `avgOrderValue` | `BigDecimal` |
| `ordersPerCabinetPerDay` | `double` |

### OverviewScale

| 字段 | 类型 |
|---|---|
| `siteTotal` | `int` |
| `siteActive` | `int` |
| `sitePaused` | `int` |
| `pointTotal` | `int` |
| `cabinetTotal` | `int` |
| `cabinetOnline` | `int` |
| `onlineRate` | `double` |
| `powerbankTotal` | `int` |
| `powerbankInCabinet` | `int` |
| `powerbankRented` | `int` |
| `powerbankFault` | `int` |

### PayReceiptReq

| 字段 | 类型 |
|---|---|
| `success` | `Boolean` |
| `channel` | `String` |
| `payRef` | `String` |
| `failReason` | `String` |

### PayeeSharingRow

| 字段 | 类型 |
|---|---|
| `payeeType` | `String` |
| `payeeName` | `String` |
| `siteCount` | `int` |
| `minRate` | `BigDecimal` |
| `maxRate` | `BigDecimal` |
| `amount30d` | `BigDecimal` |
| `currency` | `String` |
| `sites` | 数组<[`PayeeSiteRef`](#payeesiteref)> |

### PayeeSiteRef

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `rate` | `BigDecimal` |

### PaymentChannelEntry

| 字段 | 类型 |
|---|---|
| `channelCode` | `String` |
| `channelName` | `String` |
| `channelNameEn` | `String` |
| `channelNameAr` | `String` |
| `mode` | `String` |
| `status` | `String` |
| `countries` | `String` |
| `currencies` | `String` |
| `capabilities` | `String` |
| `apiBase` | `String` |
| `merchantId` | `String` |
| `apiKeyMasked` | `String` |
| `apiSecretMasked` | `String` |
| `updatedAt` | `String` |

### PayoutAccount

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `bankCode` | `String` |
| `accountName` | `String` |
| `accountMasked` | `String` |
| `currency` | `String` |
| `isDefault` | `boolean` |
| `status` | `String` |

### PayoutAccountReq

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `bankCode` | `String` |
| `accountName` | `String` |
| `accountMasked` | `String` |
| `currency` | `String` |
| `makeDefault` | `Boolean` |

### PlanScopeEntry

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `planNo` | `String` |
| `scopeType` | `String` |
| `scopeRef` | `String` |
| `deviceType` | `String` |
| `vendorCode` | `String` |
| `model` | `String` |
| `brandNo` | `String` |
| `priority` | `Integer` |
| `effectiveFrom` | `String` |
| `effectiveTo` | `String` |

### PointStat

| 字段 | 类型 |
|---|---|
| `locationNo` | `String` |
| `locationName` | `String` |
| `cabinetCount` | `int` |
| `orders` | `int` |
| `gmv` | `BigDecimal` |
| `perCabinet` | `double` |

### PowerbankCmd

| 字段 | 类型 |
|---|---|
| `powerbankNo` | `String` |
| `sn` | `String` |
| `vendorCode` | `String` |
| `cabinetNo` | `String` |
| `slotIndex` | `Integer` |
| `battery` | `Integer` |
| `cycles` | `Integer` |
| `health` | `String` |
| `status` | `String` |
| `event` | `String` |

### PowerbankRow

| 字段 | 类型 |
|---|---|
| `powerbankNo` | `String` |
| `sn` | `String` |
| `vendorCode` | `String` |
| `cabinetNo` | `String` |
| `slotIndex` | `Integer` |
| `battery` | `Integer` |
| `cycles` | `Integer` |
| `health` | `String` |
| `status` | `String` |

### PricePlan

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `name` | `String` |
| `freeMinutes` | `int` |
| `unitMinutes` | `int` |
| `unitPrice` | `double` |
| `capDaily` | `double` |
| `capTotal` | `double` |
| `currency` | `String` |
| `scope` | `String` |
| `status` | `String` |

### PricePlanEntry

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `name` | `String` |
| `freeMinutes` | `Integer` |
| `unitMinutes` | `Integer` |
| `unitPrice` | `BigDecimal` |
| `capDaily` | `BigDecimal` |
| `buyoutPrice` | `BigDecimal` |
| `currency` | `String` |
| `scope` | `String` |
| `status` | `String` |
| `archivedAt` | `String` |

### PricingSchedule

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `name` | `String` |
| `period` | `String` |
| `multiplier` | `BigDecimal` |
| `active` | `boolean` |

### ProblemEntry

| 字段 | 类型 |
|---|---|
| `problemNo` | `String` |
| `category` | `String` |
| `title` | `String` |
| `titleEn` | `String` |
| `titleAr` | `String` |
| `answer` | `String` |
| `answerEn` | `String` |
| `answerAr` | `String` |
| `suggestedAction` | `String` |
| `sortNo` | `Integer` |
| `status` | `String` |

### PushMessageVO

| 字段 | 类型 |
|---|---|
| `pushNo` | `String` |
| `title` | `String` |
| `channel` | `String` |
| `audience` | `String` |
| `sentCount` | `Integer` |
| `status` | `String` |
| `sentAt` | `String` |

### RechargeOrderRow

| 字段 | 类型 |
|---|---|
| `rechargeNo` | `String` |
| `userNo` | `String` |
| `nickname` | `String` |
| `packageNo` | `String` |
| `payAmount` | `BigDecimal` |
| `giftAmount` | `BigDecimal` |
| `creditAmount` | `BigDecimal` |
| `currency` | `String` |
| `channelCode` | `String` |
| `status` | `String` |
| `createdAt` | `String` |
| `paidAt` | `String` |
| `pspTxnNo` | `String` |

### RechargePackageRow

| 字段 | 类型 |
|---|---|
| `packageNo` | `String` |
| `name` | `String` |
| `payAmount` | `BigDecimal` |
| `giftAmount` | `BigDecimal` |
| `currency` | `String` |
| `markets` | `String` |
| `validDays` | `Integer` |
| `sortNo` | `Integer` |
| `status` | `String` |
| `archivedAt` | `String` |

### ReclaimReq

| 字段 | 类型 |
|---|---|
| `cabinetNos` | `List<String>` |
| `siteNos` | `List<String>` |
| `operatorName` | `String` |

### ReconDiffRow

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `batchNo` | `String` |
| `payNo` | `String` |
| `diffType` | `String` |
| `detail` | `String` |
| `resolved` | `Boolean` |

### Reconcile

| 字段 | 类型 |
|---|---|
| `batchNo` | `String` |
| `channel` | `String` |
| `period` | `String` |
| `billDate` | `String` |
| `nearpayTotal` | `BigDecimal` |
| `ledgerTotal` | `BigDecimal` |
| `diff` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `createdAt` | `String` |
| `handleStatus` | `String` |
| `handleResult` | `String` |
| `handleNote` | `String` |
| `handledBy` | `String` |
| `handledAt` | `String` |

### ReferralVO

| 字段 | 类型 |
|---|---|
| `inviteNo` | `String` |
| `inviter` | `String` |
| `invitee` | `String` |
| `reward` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `createdAt` | `String` |

### RefundApplyReq

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `userNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `reason` | `String` |
| `idempotencyKey` | `String` |

### RefundAuditReq

| 字段 | 类型 |
|---|---|
| `approved` | `Boolean` |
| `rejectReason` | `String` |

### RefundRecord

| 字段 | 类型 |
|---|---|
| `refundNo` | `String` |
| `orderNo` | `String` |
| `userNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `reason` | `String` |
| `applicantName` | `String` |
| `appliedAt` | `String` |
| `status` | `String` |
| `idempotencyKey` | `String` |
| `psgTxnNo` | `String` |
| `auditorName` | `String` |
| `auditedAt` | `String` |
| `rejectReason` | `String` |

### Region

| 字段 | 类型 |
|---|---|
| `regionId` | `String` |
| `name` | `String` |
| `parent` | `String` |
| `parentId` | `String` |
| `level` | `Integer` |
| `cityCount` | `Integer` |

### RejectReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

### RentOrder

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `cUserNo` | `String` |
| `cabinetNo` | `String` |
| `returnCabinetNo` | `String` |
| `powerbankNo` | `String` |
| `locationName` | `String` |
| `status` | `String` |
| `rentStartAt` | `String` |
| `rentEndAt` | `String` |
| `durationMin` | `Integer` |
| `feeAmount` | `double` |
| `depositAmount` | `double` |
| `currency` | `String` |
| `waivedAmount` | `BigDecimal` |
| `compensateAmount` | `BigDecimal` |
| `ejectCount` | `Integer` |
| `lastEjectAt` | `String` |

### RentResult

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `powerbankNo` | `String` |
| `commandId` | `String` |

### ReplyReq

| 字段 | 类型 |
|---|---|
| `content` | `String` |
| `attach` | `String` |

### ReportCustom

| 字段 | 类型 |
|---|---|
| `dim` | `String` |
| `metric` | `String` |
| `value` | `BigDecimal` |

### ReportDevice

| 字段 | 类型 |
|---|---|
| `locationName` | `String` |
| `onlineRate` | `Double` |
| `turnover` | `BigDecimal` |
| `faultRate` | `Double` |
| `cabinetCount` | `Integer` |
| `orders` | `Long` |

### ReportFinance

| 字段 | 类型 |
|---|---|
| `period` | `String` |
| `gmv` | `BigDecimal` |
| `share` | `BigDecimal` |
| `settle` | `BigDecimal` |
| `net` | `BigDecimal` |
| `currency` | `String` |

### ReportLocation

| 字段 | 类型 |
|---|---|
| `siteName` | `String` |
| `revenue` | `BigDecimal` |
| `cost` | `BigDecimal` |
| `payback` | `Integer` |
| `roi` | `BigDecimal` |
| `orders` | `Long` |
| `currency` | `String` |

### ReportMetricDef

| 字段 | 类型 |
|---|---|
| `key` | `String` |
| `label` | `String` |
| `format` | `String` |

### ReportReq

| 字段 | 类型 |
|---|---|
| `problemNo` | `String` |
| `orderNo` | `String` |
| `cabinetNo` | `String` |
| `issue` | `String` |
| `channel` | `String` |

### ReportResultVO

| 字段 | 类型 |
|---|---|
| `reportNo` | `String` |
| `status` | `String` |
| `suggestedAction` | `String` |
| `woNo` | `String` |
| `refundNo` | `String` |
| `sessionNo` | `String` |
| `createdAt` | `String` |

### ReportScreen

| 字段 | 类型 |
|---|---|
| `metric` | `String` |
| `value` | `BigDecimal` |
| `unit` | `String` |
| `trend` | `Double` |

### ReportSummaryItem

| 字段 | 类型 |
|---|---|
| `label` | `String` |
| `value` | `BigDecimal` |
| `format` | `String` |

### ReportTrend

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `period` | `String` |
| `points` | 数组<[`ReportTrendPoint`](#reporttrendpoint)> |
| `summary` | 数组<[`ReportSummaryItem`](#reportsummaryitem)> |
| `currency` | `String` |

### ReportTrendPoint

| 字段 | 类型 |
|---|---|
| `bucket` | `String` |
| `orders` | `Long` |
| `revenue` | `BigDecimal` |
| `cost` | `BigDecimal` |
| `share` | `BigDecimal` |
| `net` | `BigDecimal` |
| `onlineRate` | `Double` |
| `faultRate` | `Double` |
| `turnover` | `BigDecimal` |

### Reservation

| 字段 | 类型 |
|---|---|
| `reservationNo` | `String` |
| `userNo` | `String` |
| `type` | `String` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `cabinetNo` | `String` |
| `reservedFrom` | `String` |
| `reservedTo` | `String` |
| `holdFee` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `orderNo` | `String` |

### ReservationRule

| 字段 | 类型 |
|---|---|
| `maxDurationMin` | `Integer` |
| `advanceHours` | `Integer` |
| `holdFeePerMin` | `BigDecimal` |
| `maxConcurrent` | `Integer` |

### RoleRowVO

| 字段 | 类型 |
|---|---|
| `roleNo` | `String` |
| `code` | `String` |
| `name` | `String` |
| `permCount` | `Long` |
| `memberCount` | `Long` |
| `builtin` | `Boolean` |
| `dataScope` | `String` |
| `scopeRefs` | `String` |
| `archivedAt` | `String` |

### SceneShare

| 字段 | 类型 |
|---|---|
| `sceneType` | `String` |
| `siteCount` | `int` |
| `gmv` | `BigDecimal` |

### ScreenBoard

| 字段 | 类型 |
|---|---|
| `updatedAt` | `String` |
| `currency` | `String` |
| `kpis` | 数组<[`ReportScreen`](#reportscreen)> |
| `today` | 数组<[`ScreenBoardPoint`](#screenboardpoint)> |
| `ranking` | 数组<[`ScreenRankRow`](#screenrankrow)> |
| `cabinetStatus` | 数组<[`ScreenStatusSlice`](#screenstatusslice)> |

### ScreenBoardPoint

| 字段 | 类型 |
|---|---|
| `hour` | `String` |
| `gmv` | `BigDecimal` |
| `orders` | `Long` |

### ScreenRankRow

| 字段 | 类型 |
|---|---|
| `rank` | `Integer` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `gmv` | `BigDecimal` |
| `orders` | `Long` |

### ScreenStatusSlice

| 字段 | 类型 |
|---|---|
| `label` | `String` |
| `value` | `Long` |

### SendReq

| 字段 | 类型 |
|---|---|
| `channel` | `String` |
| `templateNo` | `String` |
| `target` | `String` |
| `scene` | `String` |
| `content` | `String` |
| `cost` | `BigDecimal` |
| `currency` | `String` |

### SendResult

| 字段 | 类型 |
|---|---|
| `logNo` | `String` |
| `status` | `String` |
| `blocked` | `boolean` |
| `failReason` | `String` |

### Settlement

| 字段 | 类型 |
|---|---|
| `settleNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `period` | `String` |
| `totalAmount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `confirmedBy` | `String` |
| `confirmedAt` | `String` |
| `createdAt` | `String` |
| `recordCount` | `Long` |

### SettlementDetail

| 字段 | 类型 |
|---|---|
| `refType` | `String` |
| `refNo` | `String` |
| `amount` | `BigDecimal` |

### SettlementView

| 字段 | 类型 |
|---|---|
| `settlement` | [`Settlement`](#settlement) |
| `details` | 数组<[`SettlementDetail`](#settlementdetail)> |

### ShareRecord

| 字段 | 类型 |
|---|---|
| `recordNo` | `String` |
| `orderNo` | `String` |
| `dimension` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `basis` | `String` |
| `amount` | `BigDecimal` |
| `rate` | `BigDecimal` |
| `currency` | `String` |
| `mode` | `String` |
| `status` | `String` |
| `settleNo` | `String` |
| `createdAt` | `String` |
| `period` | `String` |
| `grossAmount` | `BigDecimal` |

### ShareRule

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `dimension` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `basis` | `String` |
| `mode` | `String` |
| `rate` | `BigDecimal` |
| `priority` | `Integer` |
| `formula` | `String` |
| `currency` | `String` |

### ShareSummary

| 字段 | 类型 |
|---|---|
| `dimension` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `period` | `String` |
| `orderCount` | `Long` |
| `gmv` | `BigDecimal` |
| `shareAmount` | `BigDecimal` |
| `settledAmount` | `BigDecimal` |
| `pendingAmount` | `BigDecimal` |
| `currency` | `String` |

### SharingStats

| 字段 | 类型 |
|---|---|
| `total` | `int` |
| `ok` | `int` |
| `missing` | `int` |
| `invalid` | `int` |

### Site

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `name` | `String` |
| `venueNo` | `String` |
| `venueName` | `String` |
| `agentNo` | `String` |
| `brandNo` | `String` |
| `regionId` | `String` |
| `regionName` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `sceneType` | `String` |
| `pointCount` | `Integer` |
| `cabinetCount` | `Integer` |
| `status` | `String` |

### SiteAgentRow

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `siteNo` | `String` |
| `agentNo` | `String` |
| `agentName` | `String` |
| `agentType` | `String` |
| `role` | `String` |
| `ruleNo` | `String` |
| `oneOffAmount` | `BigDecimal` |
| `effectiveFrom` | `String` |
| `effectiveTo` | `String` |
| `remark` | `String` |

### SiteAnalysis

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `revenue` | `BigDecimal` |
| `orders` | `Integer` |
| `turnover` | `BigDecimal` |
| `paybackDays` | `Integer` |
| `cabinetCount` | `Integer` |
| `currency` | `String` |

### SiteBrief

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `name` | `String` |
| `regionId` | `String` |
| `venueNo` | `String` |
| `sceneType` | `String` |
| `brandNo` | `String` |

### SiteLifecycle

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `stage` | `String` |
| `stageAt` | `String` |
| `owner` | `String` |
| `currency` | `String` |
| `gmvLtm` | `BigDecimal` |

### SitePayee

| 字段 | 类型 |
|---|---|
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `rate` | `BigDecimal` |
| `mode` | `String` |
| `source` | `String` |
| `sourceNo` | `String` |

### SiteRankRow

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `venueName` | `String` |
| `cabinetCount` | `int` |
| `gmv` | `BigDecimal` |
| `orders` | `int` |
| `perCabinet` | `double` |
| `onlineRate` | `double` |

### SiteSharingRow

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `venueName` | `String` |
| `payees` | 数组<[`SitePayee`](#sitepayee)> |
| `totalRate` | `BigDecimal` |
| `platformRate` | `BigDecimal` |
| `contractEndAt` | `String` |
| `state` | `String` |
| `stateDetail` | `String` |

### SiteStats

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `siteName` | `String` |
| `from` | `String` |
| `to` | `String` |
| `orders` | `int` |
| `gmv` | `BigDecimal` |
| `currency` | `String` |
| `avgOrderValue` | `BigDecimal` |
| `avgDurationMin` | `int` |
| `ordersPerCabinetPerDay` | `double` |
| `onlineRate` | `double` |
| `cabinetCount` | `int` |
| `trend` | 数组<[`TrendPoint`](#trendpoint)> |
| `byPoint` | 数组<[`PointStat`](#pointstat)> |

### SlaRule

| 字段 | 类型 |
|---|---|
| `slaNo` | `String` |
| `woType` | `String` |
| `responseMins` | `Integer` |
| `resolveMins` | `Integer` |
| `escalateTo` | `String` |
| `active` | `Boolean` |

### Slot

| 字段 | 类型 |
|---|---|
| `slotIndex` | `int` |
| `powerbankNo` | `String` |
| `battery` | `Integer` |
| `lockStatus` | `String` |
| `health` | `String` |

### StaffPerformance

| 字段 | 类型 |
|---|---|
| `employeeNo` | `String` |
| `name` | `String` |
| `role` | `String` |
| `period` | `String` |
| `handled` | `Integer` |
| `avgResolveMins` | `Integer` |
| `score` | `BigDecimal` |

### StageChangeReq

| 字段 | 类型 |
|---|---|
| `stage` | `String` |
| `reason` | `String` |
| `operator` | `String` |
| `gmvLtm` | `BigDecimal` |
| `currency` | `String` |

### SubmitReq

| 字段 | 类型 |
|---|---|
| `phone` | `String` |
| `email` | `String` |
| `otp` | `String` |
| `operatorName` | `String` |
| `operatorType` | `String` |
| `regionScope` | `String` |
| `shareRate` | `BigDecimal` |
| `payload` | `String` |

### SysParamEntry

| 字段 | 类型 |
|---|---|
| `paramKey` | `String` |
| `label` | `String` |
| `value` | `String` |
| `groupName` | `String` |
| `updatedAt` | `String` |

### TaxSetting

| 字段 | 类型 |
|---|---|
| `country` | `String` |
| `countryName` | `String` |
| `taxName` | `String` |
| `ratePercent` | `BigDecimal` |
| `trn` | `String` |
| `invoiceTitle` | `String` |
| `includedInPrice` | `Boolean` |
| `effectiveFrom` | `String` |

### TicketCreateReq

| 字段 | 类型 |
|---|---|
| `ticketNo` | `String` |
| `cUserNo` | `String` |
| `orderNo` | `String` |
| `cabinetNo` | `String` |
| `problemNo` | `String` |
| `issue` | `String` |
| `channel` | `String` |
| `status` | `String` |

### TicketUpdateReq

| 字段 | 类型 |
|---|---|
| `status` | `String` |
| `handlerNo` | `String` |
| `issue` | `String` |

### TransferItem

| 字段 | 类型 |
|---|---|
| `transferNo` | `String` |
| `itemNo` | `String` |
| `checked` | `boolean` |

### TrendPoint

| 字段 | 类型 |
|---|---|
| `day` | `String` |
| `orders` | `int` |
| `gmv` | `BigDecimal` |

### UserBlacklist

| 字段 | 类型 |
|---|---|
| `blacklistNo` | `String` |
| `userNo` | `String` |
| `nickname` | `String` |
| `phone` | `String` |
| `reason` | `String` |
| `blacklistedAt` | `String` |
| `blacklistedBy` | `String` |
| `releasedAt` | `String` |
| `releasedBy` | `String` |
| `status` | `String` |

### UserCouponVO

| 字段 | 类型 |
|---|---|
| `couponNo` | `String` |
| `cUserNo` | `String` |
| `tplNo` | `String` |
| `tplName` | `String` |
| `tplType` | `String` |
| `value` | `BigDecimal` |
| `threshold` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `usedOrderNo` | `String` |
| `expireAt` | `String` |

### UserProfileVO

| 字段 | 类型 |
|---|---|
| `user` | [`CUserRow`](#cuserrow) |
| `risk` | [`UserRisk`](#userrisk) |
| `blacklist` | 数组<[`UserBlacklist`](#userblacklist)> |
| `whitelist` | [`FreeUserWhitelist`](#freeuserwhitelist) |
| `creditChanges` | 数组<[`CreditScoreChange`](#creditscorechange)> |
| `wallet` | [`WalletRow`](#walletrow) |
| `walletTxns` | 数组<[`WalletTxnRow`](#wallettxnrow)> |
| `member` | [`MemberRow`](#memberrow) |
| `cards` | 数组<[`MemberCard`](#membercard)> |
| `orders` | 数组<[`RentOrder`](#rentorder)> |
| `orderStats` | [`OrderStats`](#orderstats) |

### UserRisk

| 字段 | 类型 |
|---|---|
| `riskNo` | `String` |
| `userNo` | `String` |
| `nickname` | `String` |
| `phone` | `String` |
| `creditScore` | `Integer` |
| `riskLevel` | `String` |
| `reason` | `String` |
| `flaggedAt` | `String` |

### VendorProbeResult

| 字段 | 类型 |
|---|---|
| `vendorCode` | `String` |
| `ok` | `Boolean` |
| `endpoint` | `String` |
| `latencyMs` | `Long` |
| `checkedAt` | `String` |
| `message` | `String` |
| `detail` | `String` |

### VendorVO

| 字段 | 类型 |
|---|---|
| `vendorCode` | `String` |
| `name` | `String` |
| `accessMode` | `String` |
| `status` | `String` |
| `apiBase` | `String` |
| `deviceCount` | `Integer` |

### Venue

| 字段 | 类型 |
|---|---|
| `venueNo` | `String` |
| `name` | `String` |
| `contact` | `String` |
| `industry` | `String` |
| `locationCount` | `int` |

### VenueOnboarding

| 字段 | 类型 |
|---|---|
| `onboardingNo` | `String` |
| `venueName` | `String` |
| `contact` | `String` |
| `industry` | `String` |
| `requestedAt` | `String` |
| `status` | `String` |
| `reviewAt` | `String` |
| `reviewNote` | `String` |
| `venueNo` | `String` |

### WalletOverview

| 字段 | 类型 |
|---|---|
| `balance` | `BigDecimal` |
| `giftBalance` | `BigDecimal` |
| `depositAmount` | `BigDecimal` |
| `frozenAmount` | `BigDecimal` |
| `currency` | `String` |

### WalletRow

| 字段 | 类型 |
|---|---|
| `userNo` | `String` |
| `nickname` | `String` |
| `balance` | `BigDecimal` |
| `bonus` | `BigDecimal` |
| `currency` | `String` |
| `updatedAt` | `String` |
| `orderCount` | `long` |
| `orderAmount` | `BigDecimal` |
| `rechargeCount` | `long` |
| `rechargeAmount` | `BigDecimal` |

### WalletTxnRow

| 字段 | 类型 |
|---|---|
| `txnNo` | `String` |
| `type` | `String` |
| `direction` | `String` |
| `title` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `bizType` | `String` |
| `bizNo` | `String` |
| `createdAt` | `String` |

### WithdrawApplyReq

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `bankCode` | `String` |

### WithdrawAuditReq

| 字段 | 类型 |
|---|---|
| `approve` | `Boolean` |
| `rejectReason` | `String` |

### WithdrawRule

| 字段 | 类型 |
|---|---|
| `minAmount` | `BigDecimal` |
| `feeRate` | `BigDecimal` |
| `feeCap` | `BigDecimal` |
| `settleDays` | `Integer` |
| `dailyLimit` | `BigDecimal` |
| `needApproval` | `Boolean` |

### Withdrawal

| 字段 | 类型 |
|---|---|
| `withdrawNo` | `String` |
| `accountNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `amount` | `BigDecimal` |
| `fee` | `BigDecimal` |
| `netAmount` | `BigDecimal` |
| `currency` | `String` |
| `bankCode` | `String` |
| `status` | `String` |
| `appliedAt` | `String` |
| `applicantNo` | `String` |
| `auditorName` | `String` |
| `auditedAt` | `String` |
| `rejectReason` | `String` |
| `paidAt` | `String` |
| `payChannel` | `String` |
| `payRef` | `String` |
| `payerName` | `String` |
| `failReason` | `String` |

### WorkOrder

| 字段 | 类型 |
|---|---|
| `woNo` | `String` |
| `type` | `String` |
| `source` | `String` |
| `priority` | `String` |
| `cabinetNo` | `String` |
| `locationName` | `String` |
| `status` | `String` |
| `assigneeName` | `String` |
| `slaDueAt` | `String` |
| `description` | `String` |
| `createdAt` | `String` |
| `sourceNo` | `String` |
| `expectedAt` | `String` |
| `dispatchedAt` | `String` |
| `acceptedAt` | `String` |
| `handlerName` | `String` |
| `handledAt` | `String` |
| `handleNote` | `String` |
| `partsReplaced` | `String` |
| `completedAt` | `String` |
| `auditorName` | `String` |
| `auditedAt` | `String` |
| `auditResult` | `String` |
| `auditNote` | `String` |
| `rejectReason` | `String` |
| `rejectCount` | `Integer` |

### WorkOrderDraft

| 字段 | 类型 |
|---|---|
| `type` | `String` |
| `source` | `String` |
| `sourceNo` | `String` |
| `priority` | `String` |
| `cabinetNo` | `String` |
| `locationNo` | `String` |
| `locationName` | `String` |
| `agentNo` | `String` |
| `siteNo` | `String` |
| `description` | `String` |
| `expectedAt` | `String` |

### WorkOrderRef

| 字段 | 类型 |
|---|---|
| `alarmNo` | `String` |
| `woNo` | `String` |
| `created` | `boolean` |


## 枚举

- **AccountStatus** — `ACTIVE`
- **AgentStatus** — `ENABLED`
- **AgentType** — `AGENT`
- **AlarmLevel** — `INFO` `WARN`
- **AlarmNoticeStatus** — `SENT`
- **AlarmRuleStatus** — `ACTIVE`
- **AlarmStatus** — `OPEN` `ACKED`
- **ApplyStatus** — `DRAFT` `SUBMITTED` `REVIEWING` `APPROVED` `SUBMITTED`
- **AssignTargetType** — `CABINET` `LOCATION`
- **CabinetStatus** — `IN_STOCK` `DEPLOYED` `FAULT`
- **CodeBatchStatus** — `PENDING` `PARTIAL` `BOUND`
- **CsSenderType** — `USER`
- **CsSessionStatus** — `ACTIVE`
- **CsTicketStatus** — `OPEN` `PROCESSING`
- **DeviceCodeType** — `QR`
- **DeviceKind** — `POWERBANK` `EV_PILE` `LOCKER`
- **InvoiceStatus** — `DRAFT` `ISSUED`
- **LeadOwnerType** — `STAFF`
- **OnlineStatus** — `ONLINE`
- **OtaReleaseStatus** — `DRAFT` `PUBLISHED` `PAUSED` `COMPLETED`
- **OtaRolloutStatus** — `PENDING` `RUNNING` `DONE`
- **OutboxStatus** — `PENDING` `SENT` `FAILED`
- **Outcome** — `OK` `NOT_CONFIGURED` `UNREACHABLE` `REMOTE_ERROR`
- **Period** — `LAST_7D` `LAST_30D` `LAST_13W` `LAST_12M`
- **PowerbankStatus** — `IN_STOCK` `IN_CABINET` `RENTED` `FAULT` `LOST` `SOLD`
- **Provider** — `WECHAT_MP` `WECHAT_OA` `APPLE` `GOOGLE` `PHONE`
- **Realm** — `STAFF` `AGENT` `CONSUMER`
- **ReconTaskStatus** — `MATCHED`
- **ScopeLevel** — `DEVICE` `LOCATION` `SITE` `VENUE` `AGENT` `SCENE` `REGION`
- **SettlementRefType** — `ORDER`
- **SettlementStatus** — `GEN` `CONFIRMED`
- **ShareMode** — `CHANNEL_SPLIT`
- **ShareRecordStatus** — `PENDING`
- **SiteAgentRole** — `INVEST` `DEVELOP` `OPERATE`
- **VendorStatus** — `ENABLED`
- **WithdrawalStatus** — `APPLY` `AUDIT` `PAYING` `PAID`
- **WoDispatchAction** — `DISPATCH`
- **WorkOrderStatus** — `CREATED` `DISPATCHED` `ACCEPTED` `PROCESSING` `DONE` `AUDITED`
- **WorkOrderType** — `FAULT` `REFILL` `INSPECT` `INSTALL` `REMOVE` `COMPLAINT`


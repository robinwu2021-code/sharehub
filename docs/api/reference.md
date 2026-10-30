# 接口参考（全量 627 个端点）

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
| 端点 | **627** |
| 带功能权限码 | 544 |
| 有请求体 | 257 |
| 数据结构 | 437 个（文末统一定义） |

### ⚠️ 7 个 `/api/**` 端点没有功能权限码

它们**不是匿名可读** —— `SecurityConfig` 对 `/api/**` 是 `anyRequest().authenticated()`，未登录仍是 401。但没有 `@PreAuthorize` 意味着**任何已登录员工都能读**，不分角色：客服能读账务分录、能读员工名册、能读审计日志。

这与「四层权限」的设计（认证 → 功能权限 → 数据范围 → 属主守卫）第二层缺失，全部落在待拆分的旧骨架控制器上。

| 端点 | 落在 |
|---|---|
| `POST /api/agent/apply` | `AgentApplyController#selfServiceApply` |
| `GET /api/agent/apply/mine` | `AgentApplyController#mine` |
| `POST /api/agent/apply/otp` | `AgentApplyController#applyOtp` |
| `POST /api/platform/files` | `FileController#upload` |
| `GET /api/platform/files/raw/{fileNo}` | `FileController#raw` |
| `GET /api/platform/files/{fileNo}` | `FileController#download` |
| `GET /api/platform/files/{fileNo}/url` | `FileController#url` |

---


## 登录与会话

8 个端点。

### `POST /api/auth/login`

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

当前会话的身份与**当场重算的权限码**。

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

**无权限码** · `AuthController#loginOtp`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`

### `POST /api/auth/password`

发送代理端登录验证码（匿名）。

**无权限码** · `AuthController#changePassword`

**请求体** `对象（字符串值）`

**出参** `对象（自由键）`


## 运营：设备 · 场地 · 工单 · 告警 · 库存

266 个端点。

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

**请求体** [`AdSlot`](#adslot)

**出参** [`AdSlotVO`](#adslotvo)

### `POST /api/ops/ad-slots/{slotNo}`

权限码 `marketing:ad:update` · `MarketingController#updateAdSlot`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slotNo` | `String` | 是 |

**请求体** [`AdSlot`](#adslot)

**出参** [`AdSlotVO`](#adslotvo)

### `GET /api/ops/alarm-todos`

告警待办（经营看板 › 待办中心）：合作 / 经营类告警的处置落点（TDD/05 §7.4）。

权限码 `dashboard:todo:read` · `AlarmTodoController#page`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `mine` | `boolean` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`AlarmTodo`](#alarmtodo)>

### `GET /api/ops/alarm-todos/count`

权限码 `dashboard:todo:read` · `AlarmTodoController#openCount`

**出参** `Map<String, Long>`

### `POST /api/ops/alarm-todos/{todoNo}/done`

完成待办：告警条件已不成立 → 告警关闭；仍成立 → 下一轮重新生成待办。

权限码 `dashboard:todo:read` · `AlarmTodoController#done`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `todoNo` | `String` | 是 |

**请求体** [`NoteReq`](#notereq)

**出参** [`AlarmTodo`](#alarmtodo)

### `POST /api/ops/alarms/auto-work-orders`

未处理告警批量自动开单。

权限码 `workorder:alarm:update` · `AlarmController#autoRaiseWorkOrders`

**出参** `Object`

### `GET /api/ops/alarms/codes`

权限码 `workorder:alarm:read` · `AlarmController#codes`

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

权限码 `workorder:alarm:config` · `AlarmController#createCode`

**请求体** [`DevAlarmCode`](#devalarmcode)

**出参** [`AlarmCode`](#alarmcode)

### `GET /api/ops/alarms/codes/stats`

每码统计（近 N 天）：数量、误报率、自愈率 —— 调规则的依据。

权限码 `workorder:alarm:read` · `AlarmController#codeStats`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `days` | `int` | 否 |

**出参** `List<ai.neargo.sharehub.alarm.dto.AlarmDtos.CodeStat>`

### `POST /api/ops/alarms/codes/{code}`

权限码 `workorder:alarm:config` · `AlarmController#updateCode`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `code` | `String` | 是 |

**请求体** [`DevAlarmCode`](#devalarmcode)

**出参** [`AlarmCode`](#alarmcode)

### `GET /api/ops/alarms/codes/{code}/routes`

根因路由：同一业务告警，根因不同派的活不同。

权限码 `workorder:alarm:read` · `AlarmController#routes`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `code` | `String` | 是 |

**出参** `List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute>`

### `POST /api/ops/alarms/codes/{code}/routes`

权限码 `workorder:alarm:config` · `AlarmController#saveRoutes`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `code` | `String` | 是 |

**请求体** `List<ai.neargo.sharehub.alarm.dto.AlarmDtos.RouteReq>`

**出参** `List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute>`

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

权限码 `workorder:alarm:read` · `AlarmController#notices`

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

权限码 `workorder:alarm:read` · `AlarmController#records`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `level` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |
| 查询 | `domain` | `String` | 否 |
| 查询 | `subjectType` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `cause` | `String` | 否 |
| 查询 | `disposition` | `String` | 否 |
| 查询 | `from` | `.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate` | 否 |
| 查询 | `to` | `.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate` | 否 |
| 查询 | `topOnly` | `Boolean` | 否 |
| 查询 | `preset` | `String` | 否 |

**出参** 分页<[`AlarmRecord`](#alarmrecord)>

### `GET /api/ops/alarms/records/{alarmNo}`

告警详情：证据、时间线、同对象近 7 天。

权限码 `workorder:alarm:read` · `AlarmController#detail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**出参** [`AlarmDetail`](#alarmdetail)

### `POST /api/ops/alarms/records/{alarmNo}/ack`

确认告警（OPEN → ACKED）。

权限码 `workorder:alarm:ack` · `AlarmController#ack`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`AckResult`](#ackresult)

### `POST /api/ops/alarms/records/{alarmNo}/close`

关闭告警。

权限码 `workorder:alarm:close` · `AlarmController#closeAlarm`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`AckResult`](#ackresult)

### `POST /api/ops/alarms/records/{alarmNo}/dispose`

人工立即处置（忽略开单延迟）。

权限码 `workorder:wo:create` · `AlarmController#dispose`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**出参** `对象（自由键）`

### `GET /api/ops/alarms/records/{alarmNo}/disposition-preview`

处置预览：现在处置会开什么单、派给谁、是否并入已有工单。

权限码 `workorder:alarm:read` · `AlarmController#dispositionPreview`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**出参** [`DispositionPreview`](#dispositionpreview)

### `POST /api/ops/alarms/records/{alarmNo}/work-order`

一键转工单，**以 `alarmNo` 为幂等键**（[api/README §3.3]）。

权限码 `workorder:wo:create` · `AlarmController#toWorkOrder`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `alarmNo` | `String` | 是 |

**出参** [`WorkOrderRef`](#workorderref)

### `GET /api/ops/alarms/rules`

权限码 `workorder:alarm:read` · `AlarmController#rules`

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

权限码 `workorder:alarm:config` · `AlarmController#createRule`

**请求体** [`DevAlarmRule`](#devalarmrule)

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

权限码 `workorder:alarm:config` · `AlarmController#updateRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** [`DevAlarmRule`](#devalarmrule)

**出参** [`AlarmRule`](#alarmrule)

### `GET /api/ops/alarms/summary`

告警中心摘要条：按业务域的未关闭 / 严重数，已处置未关闭，今日自动恢复。

权限码 `workorder:alarm:read` · `AlarmController#summary`

**出参** [`AlarmSummary`](#alarmsummary)

### `GET /api/ops/asset-diffs`

权限码 `device:inventory:read` · `AssetOpsController#diffs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `sourceType` | `String` | 否 |
| 查询 | `sourceRef` | `String` | 否 |

**出参** 分页<[`AssetDiff`](#assetdiff)>

### `POST /api/ops/asset-diffs/{diffNo}/resolve`

权限码 `device:inventory:diff` · `AssetOpsController#resolve`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `diffNo` | `String` | 是 |

**请求体** [`ResolveReq`](#resolvereq)

**出参** [`AssetDiff`](#assetdiff)

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
| 查询 | `issuedAgentNo` | `String` | 否 |
| 查询 | `pool` | `String` | 否 |

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

权限码 `device:cabinet:import` · `OpsController#importCabinets`

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

**请求体** [`CommandReq`](#commandreq)

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

权限码 `location:contract:read` · `ContractController#page`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `venueNo` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `endFrom` | `LocalDate` | 否 |
| 查询 | `endTo` | `LocalDate` | 否 |
| 查询 | `pendingMine` | `Boolean` | 否 |

**出参** 分页<[`Contract`](#contract)>

### `POST /api/ops/contracts`

权限码 `location:contract:create` · `ContractController#create`

**请求体** [`ContractReq`](#contractreq)

**出参** [`Contract`](#contract)

### `GET /api/ops/contracts/summary`

权限码 `location:contract:read` · `ContractController#summary`

**出参** [`ContractSummary`](#contractsummary)

### `GET /api/ops/contracts/{contractNo}`

权限码 `location:contract:read` · `ContractController#get`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}`

权限码 `location:contract:update` · `ContractController#update`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`ContractReq`](#contractreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/attachments`

权限码 `location:contract:update` · `ContractController#attach`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`AttachReq`](#attachreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/attachments/{attachNo}/remove`

权限码 `location:contract:update` · `ContractController#removeAttachment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |
| 路径 | `attachNo` | `String` | 是 |

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/audit`

权限码 `location:contract:audit` · `ContractController#audit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`AuditReq`](#auditreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/cosign`

财务会签（条件加签）：运营通过后超阈值的合同停在 PENDING（auditStage=FINANCE）。

权限码 `location:contract:cosign` · `ContractController#cosign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`AuditReq`](#auditreq)

**出参** [`Contract`](#contract)

### `GET /api/ops/contracts/{contractNo}/logs`

权限码 `location:contract:read` · `ContractController#logs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**出参** 数组<[`ContractLogItem`](#contractlogitem)>

### `POST /api/ops/contracts/{contractNo}/renew`

权限码 `location:contract:create` · `ContractController#renew`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/sign`

权限码 `location:contract:update` · `ContractController#sign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`SignReq`](#signreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/submit`

权限码 `location:contract:submit` · `ContractController#submit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/supplement`

生效中合同改条款：生成补充协议草稿（关联原合同），走同样的提交 → 审批 → 签署。

权限码 `location:contract:create` · `ContractController#supplement`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`SupplementReq`](#supplementreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/terminate`

发起提前终止申请（裁决 #4）：审批期间合同照常生效。

权限码 `location:contract:terminate` · `ContractController#terminate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`TerminateReq`](#terminatereq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/termination/audit`

权限码 `location:contract:audit` · `ContractController#auditTermination`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`AuditReq`](#auditreq)

**出参** [`Contract`](#contract)

### `POST /api/ops/contracts/{contractNo}/withdraw`

权限码 `location:contract:submit` · `ContractController#withdraw`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `contractNo` | `String` | 是 |

**请求体** [`NoteReq`](#notereq)

**出参** [`Contract`](#contract)

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

### `GET /api/ops/cs/tickets/{ticketNo}`

单张详情 —— 列表放不下报障截图，而截图往往是唯一能看清现场的东西。

权限码 `cs:ticket:read` · `CsController#ticket`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ticketNo` | `String` | 是 |

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

**请求体** [`DevCodeBatch`](#devcodebatch)

**出参** [`CodeBatchRow`](#codebatchrow)

### `POST /api/ops/device-code-batches/{batchNo}`

权限码 `device:cabinet:update` · `DeviceController#updateCodeBatch`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `batchNo` | `String` | 是 |

**请求体** [`DevCodeBatch`](#devcodebatch)

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

### `GET /api/ops/device-movements`

设备履历（V196，仓外段）：位置与归属的每一次变化，新的在前。

权限码 `device:cabinet:read` · `AssetOpsController#movements`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `itemNo` | `String` | 是 |

**出参** 数组<[`DeviceMovement`](#devicemovement)>

### `GET /api/ops/device-signals`

权限码 `device:cabinet:read` · `DeviceSignalController#codes`

**出参** 数组<[`SignalCode`](#signalcode)>

### `POST /api/ops/devices/bind-batch`

批量绑定：同一商家的一批出库池设备，一个事务（任一台不满足整批不动）。

权限码 `device:cabinet:update` · `DevicePoolController#bindBatch`

**请求体** [`BindBatchReq`](#bindbatchreq)

**出参** 数组<[`BindResult`](#bindresult)>

### `POST /api/ops/devices/claim`

接收人签收（V195）：只能签给自己的 —— 权限码只是门槛，「本人 = 接收人」在服务里校验。

权限码 `device:cabinet:update` · `DevicePoolController#claim`

**请求体** [`ClaimReq`](#claimreq)

**出参** 数组<[`Cabinet`](#cabinet)>

### `POST /api/ops/devices/issue`

权限码 `device:inventory:transfer` · `DevicePoolController#issue`

**请求体** [`IssueReq`](#issuereq)

**出参** 数组<[`Cabinet`](#cabinet)>

### `POST /api/ops/devices/issue-redirect`

出库调整（V195）：改商家 / 站点 / 接收人，只收未绑定的。

权限码 `device:inventory:transfer` · `DevicePoolController#redirect`

**请求体** [`RedirectReq`](#redirectreq)

**出参** 数组<[`Cabinet`](#cabinet)>

### `POST /api/ops/devices/pool-return`

权限码 `device:inventory:transfer` · `DevicePoolController#poolReturn`

**请求体** [`PoolReturnReq`](#poolreturnreq)

**出参** 数组<[`Cabinet`](#cabinet)>

### `POST /api/ops/devices/protections/{protectionNo}/release`

只能解除人工持有的保护；信号 / 告警持有的随恢复自动解除。

权限码 `device:cabinet:update` · `DeviceOpsController#releaseProtection`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `protectionNo` | `String` | 是 |

**请求体** [`ReasonReq`](#reasonreq)

**出参** [`Protection`](#protection)

### `POST /api/ops/devices/{cabinetNo}/qc`

权限码 `device:qc:inspect` · `AssetOpsController#inspectCabinet`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cabinetNo` | `String` | 是 |

**请求体** [`QcReq`](#qcreq)

**出参** [`QcRecord`](#qcrecord)

### `POST /api/ops/devices/{deviceNo}/bind`

绑定（V164）：出库池设备绑到点位；不给点位 = 出库池商家默认站点的默认点位。

权限码 `device:cabinet:update` · `DevicePoolController#bind`

**请求体** [`BindReq`](#bindreq)

**出参** [`BindResult`](#bindresult)

### `POST /api/ops/devices/{deviceNo}/go-live`

门禁全过才上线；站点若在筹备中随之转营业。

权限码 `device:cabinet:update` · `DeviceOpsController#goLive`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**出参** [`Cabinet`](#cabinet)

### `GET /api/ops/devices/{deviceNo}/go-live-gate`

权限码 `device:cabinet:read` · `DeviceOpsController#goLiveGate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**出参** [`Checklist`](#checklist)

### `POST /api/ops/devices/{deviceNo}/mark-fault`

权限码 `device:cabinet:update` · `DeviceOpsController#markFault`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**请求体** [`ReasonReq`](#reasonreq)

**出参** [`Cabinet`](#cabinet)

### `GET /api/ops/devices/{deviceNo}/protections`

权限码 `device:cabinet:read` · `DeviceOpsController#protections`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |
| 查询 | `activeOnly` | `boolean` | 否 |

**出参** 数组<[`Protection`](#protection)>

### `POST /api/ops/devices/{deviceNo}/protections`

人工停借 / 禁仓。

权限码 `device:cabinet:update` · `DeviceOpsController#applyProtection`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**请求体** [`ProtectionReq`](#protectionreq)

**出参** [`Protection`](#protection)

### `POST /api/ops/devices/{deviceNo}/repair`

权限码 `device:cabinet:update` · `DeviceOpsController#repair`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**出参** [`Cabinet`](#cabinet)

### `POST /api/ops/devices/{deviceNo}/retire`

权限码 `device:cabinet:update` · `DeviceOpsController#retire`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**请求体** [`ReasonReq`](#reasonreq)

**出参** [`Cabinet`](#cabinet)

### `GET /api/ops/devices/{deviceNo}/trial-rents`

权限码 `device:cabinet:read` · `DeviceOpsController#trialRents`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**出参** 数组<[`TrialRent`](#trialrent)>

### `POST /api/ops/devices/{deviceNo}/trial-rents`

发起试借还：下发弹出指令（异步），结果以设备信号回推。

权限码 `device:command:send` · `DeviceOpsController#startTrialRent`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**出参** [`TrialRent`](#trialrent)

### `POST /api/ops/devices/{deviceNo}/unbind`

解绑：仅已绑定未上线；回同一商家的出库池。

权限码 `device:cabinet:update` · `DevicePoolController#unbind`

**请求体** [`UnbindReq`](#unbindreq)

**出参** [`Cabinet`](#cabinet)

### `POST /api/ops/devices/{deviceNo}/undeploy`

权限码 `device:cabinet:update` · `DeviceOpsController#undeploy`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deviceNo` | `String` | 是 |

**请求体** [`ReasonReq`](#reasonreq)

**出参** [`Cabinet`](#cabinet)

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

权限码 `workorder:inspection:update` · `WoExtController#createInspectionPlan`

**请求体** [`InspectionPlanReq`](#inspectionplanreq)

**出参** [`InspectionPlan`](#inspectionplan)

### `GET /api/ops/inspection-plans/{planNo}`

权限码 `workorder:wo:read` · `WoExtController#inspectionPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**出参** [`InspectionPlan`](#inspectionplan)

### `POST /api/ops/inspection-plans/{planNo}`

权限码 `workorder:inspection:update` · `WoExtController#updateInspectionPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `planNo` | `String` | 是 |

**请求体** [`InspectionPlanReq`](#inspectionplanreq)

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

**请求体** [`InvTransferReq`](#invtransferreq)

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

**请求体** [`InvTransferReq`](#invtransferreq)

**出参** [`InventoryTransfer`](#inventorytransfer)

### `POST /api/ops/inventory-transfers/{transferNo}/items`

权限码 `device:inventory:transfer` · `AssetOpsController#setItems`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `transferNo` | `String` | 是 |

**请求体** [`ItemsReq`](#itemsreq)

**出参** [`InventoryTransferDetail`](#inventorytransferdetail)

### `POST /api/ops/inventory-transfers/{transferNo}/receive`

权限码 `device:inventory:transfer` · `AssetOpsController#receive`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `transferNo` | `String` | 是 |

**请求体** [`ReceiveReq`](#receivereq)

**出参** [`ReceiveResult`](#receiveresult)

### `POST /api/ops/inventory-transfers/{transferNo}/ship`

权限码 `device:inventory:transfer` · `AssetOpsController#ship`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `transferNo` | `String` | 是 |

**请求体** [`ShipInfo`](#shipinfo)

**出参** [`InventoryTransferDetail`](#inventorytransferdetail)

### `GET /api/ops/inventory/counts`

权限码 `device:inventory:read` · `InventoryController#counts`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `warehouseNo` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** [`StockCount`](#stockcount)

### `POST /api/ops/inventory/counts`

权限码 `device:inventory:stocktake` · `InventoryController#openCount`

**请求体** [`OpenCountReq`](#opencountreq)

**出参** [`StockCountDetail`](#stockcountdetail)

### `GET /api/ops/inventory/counts/{countNo}`

权限码 `device:inventory:read` · `InventoryController#count`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/approve`

权限码 `device:inventory:stocktake` · `InventoryController#approveCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**请求体** [`CountNoteReq`](#countnotereq)

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/cancel`

权限码 `device:inventory:stocktake` · `InventoryController#cancelCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/post`

权限码 `device:inventory:stocktake` · `InventoryController#postCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/reject`

权限码 `device:inventory:stocktake` · `InventoryController#rejectCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**请求体** [`CountNoteReq`](#countnotereq)

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/scans`

权限码 `device:inventory:stocktake` · `InventoryController#scanCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**请求体** [`CountScanReq`](#countscanreq)

**出参** [`StockCountDetail`](#stockcountdetail)

### `POST /api/ops/inventory/counts/{countNo}/submit`

权限码 `device:inventory:stocktake` · `InventoryController#submitCount`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countNo` | `String` | 是 |

**出参** [`StockCountDetail`](#stockcountdetail)

### `GET /api/ops/inventory/disposals`

权限码 `device:inventory:read` · `InventoryController#disposals`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `warehouseNo` | `String` | 否 |
| 查询 | `supplierNo` | `String` | 否 |

**出参** 分页<[`Disposal`](#disposal)>

### `POST /api/ops/inventory/disposals`

建单即过账：报废须原因码，退供应商须供应商。

权限码 `device:inventory:transfer` · `InventoryController#createDisposal`

**请求体** [`DisposalReq`](#disposalreq)

**出参** [`DisposalDetail`](#disposaldetail)

### `GET /api/ops/inventory/disposals/{disposalNo}`

权限码 `device:inventory:read` · `InventoryController#disposal`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `disposalNo` | `String` | 是 |

**出参** [`DisposalDetail`](#disposaldetail)

### `GET /api/ops/inventory/engine`

当前库存引擎：盘点只在 SERVICE 下可用，页面据此提示。

权限码 `device:inventory:read` · `InventoryController#engine`

**出参** [`InventoryEngine`](#inventoryengine)

### `GET /api/ops/inventory/intakes`

权限码 `device:inventory:read` · `InventoryController#intakes`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `warehouseNo` | `String` | 否 |

**出参** 分页<[`Intake`](#intake)>

### `POST /api/ops/inventory/intakes`

权限码 `device:inventory:inbound` · `InventoryController#createIntake`

**请求体** [`CreateReq`](#createreq)

**出参** [`IntakeDetail`](#intakedetail)

### `GET /api/ops/inventory/intakes/{intakeNo}`

权限码 `device:inventory:read` · `InventoryController#intake`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `intakeNo` | `String` | 是 |

**出参** [`IntakeDetail`](#intakedetail)

### `POST /api/ops/inventory/intakes/{intakeNo}/dispose`

权限码 `device:inventory:inbound` · `InventoryController#disposeIntake`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `intakeNo` | `String` | 是 |

**请求体** [`DisposeReq`](#disposereq)

**出参** [`IntakeDetail`](#intakedetail)

### `POST /api/ops/inventory/intakes/{intakeNo}/inbound`

权限码 `device:inventory:inbound` · `InventoryController#inboundIntake`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `intakeNo` | `String` | 是 |

**请求体** [`ItemsReq`](#itemsreq)

**出参** [`IntakeDetail`](#intakedetail)

### `POST /api/ops/inventory/intakes/{intakeNo}/items/{itemType}/{itemNo}/qc`

权限码 `device:qc:inspect` · `InventoryController#inspectIntakeItem`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `intakeNo` | `String` | 是 |
| 路径 | `itemType` | `String` | 是 |
| 路径 | `itemNo` | `String` | 是 |

**请求体** [`QcReq`](#qcreq)

**出参** [`IntakeDetail`](#intakedetail)

### `GET /api/ops/inventory/issued-summary`

出库池页左侧的商家汇总。

权限码 `device:inventory:read` · `DevicePoolController#summary`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `agentNo` | `String` | 否 |

**出参** 数组<[`IssuedSummary`](#issuedsummary)>

### `GET /api/ops/inventory/ledger`

权限码 `device:inventory:read` · `InventoryController#ledger`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `warehouseNo` | `String` | 否 |
| 查询 | `itemType` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`StockLedgerRow`](#stockledgerrow)>

### `GET /api/ops/inventory/material-balances`

权限码 `device:inventory:read` · `InventoryController#materialBalances`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `warehouseNo` | `String` | 否 |
| 查询 | `materialNo` | `String` | 否 |
| 查询 | `category` | `String` | 否 |
| 查询 | `lowOnly` | `Boolean` | 否 |

**出参** [`MaterialBalance`](#materialbalance)

### `GET /api/ops/inventory/material-docs`

权限码 `device:inventory:read` · `InventoryController#materialDocs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `purpose` | `String` | 否 |
| 查询 | `warehouseNo` | `String` | 否 |
| 查询 | `materialNo` | `String` | 否 |

**出参** 分页<[`MaterialDoc`](#materialdoc)>

### `POST /api/ops/inventory/material-docs`

入库类用途判 :inbound，出库与调拨判 :transfer（与设备单据同口径）。

权限码 `device:inventory:inbound` 或 `device:inventory:transfer` · `InventoryController#createMaterialDoc`

**请求体** [`MaterialDocReq`](#materialdocreq)

**出参** [`MaterialDoc`](#materialdoc)

### `GET /api/ops/inventory/material-docs/{docNo}`

权限码 `device:inventory:read` · `InventoryController#materialDoc`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `docNo` | `String` | 是 |

**出参** [`MaterialDoc`](#materialdoc)

### `POST /api/ops/inventory/material-thresholds`

权限码 `device:inventory:warehouse` · `InventoryController#saveMaterialMinQty`

**请求体** [`MaterialMinQtyReq`](#materialminqtyreq)

**出参** [`MaterialBalance`](#materialbalance)

### `GET /api/ops/inventory/materials`

权限码 `device:inventory:read` · `InventoryController#materials`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `category` | `String` | 否 |
| 查询 | `showArchived` | `Boolean` | 否 |

**出参** 分页<[`Material`](#material)>

### `POST /api/ops/inventory/materials`

权限码 `device:inventory:warehouse` · `InventoryController#createMaterial`

**请求体** [`MaterialReq`](#materialreq)

**出参** [`Material`](#material)

### `POST /api/ops/inventory/materials/{materialNo}`

权限码 `device:inventory:warehouse` · `InventoryController#updateMaterial`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `materialNo` | `String` | 是 |

**请求体** [`MaterialReq`](#materialreq)

**出参** [`Material`](#material)

### `POST /api/ops/inventory/materials/{materialNo}/archive`

权限码 `device:inventory:warehouse` · `InventoryController#archiveMaterial`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `materialNo` | `String` | 是 |

**出参** [`Material`](#material)

### `POST /api/ops/inventory/materials/{materialNo}/unarchive`

权限码 `device:inventory:warehouse` · `InventoryController#unarchiveMaterial`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `materialNo` | `String` | 是 |

**出参** [`Material`](#material)

### `GET /api/ops/inventory/migration`

存量迁移试跑（S7）：要入库的、要出库的、跳过的（附原因）、已完成的。

权限码 `system:param:read` · `InventoryController#migrationPlan`

**出参** [`InventoryMigrationPlan`](#inventorymigrationplan)

### `POST /api/ops/inventory/migration`

存量迁移执行一批（S7）：每台独立、可重跑；失败逐台列出。

权限码 `system:param:update` · `InventoryController#runMigration`

**请求体** [`MigrationRunReq`](#migrationrunreq)

**出参** [`InventoryMigrationResult`](#inventorymigrationresult)

### `GET /api/ops/inventory/period-summary`

期间报表（S6）：期初 / 入 / 出 / 期末，按仓与设备类型。

权限码 `device:inventory:read` · `InventoryController#periodSummary`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `LocalDate` | 是 |
| 查询 | `to` | `LocalDate` | 是 |
| 查询 | `warehouseNo` | `String` | 否 |

**出参** `List<ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow>`

### `GET /api/ops/inventory/shipments`

权限码 `device:inventory:read` · `InventoryController#shipments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `docType` | `String` | 否 |
| 查询 | `docRef` | `String` | 否 |
| 查询 | `itemNo` | `String` | 否 |

**出参** `List<ai.neargo.sharehub.inv.dto.ShipmentDtos.Shipment>`

### `GET /api/ops/inventory/stage-items`

阶段下钻（P2 #6）：某阶段 × 类型的设备清单，口径同 stages。

权限码 `device:inventory:read` · `InventoryController#stageItems`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `stage` | `String` | 是 |
| 查询 | `itemType` | `String` | 否 |

**出参** [`StageItems`](#stageitems)

### `GET /api/ops/inventory/stages`

统一库存视图（S6）：按生命周期阶段的台数。

权限码 `device:inventory:read` · `InventoryController#stages`

**出参** [`StockStage`](#stockstage)

### `GET /api/ops/inventory/stock-thresholds`

权限码 `device:inventory:read` · `InventoryController#stockThresholds`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `warehouseNo` | `String` | 否 |

**出参** [`StockThreshold`](#stockthreshold)

### `POST /api/ops/inventory/stock-thresholds`

权限码 `device:inventory:warehouse` · `InventoryController#saveStockThreshold`

**请求体** [`StockThresholdReq`](#stockthresholdreq)

**出参** [`StockThreshold`](#stockthreshold)

### `GET /api/ops/inventory/suppliers`

权限码 `device:inventory:read` · `InventoryController#suppliers`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `showArchived` | `Boolean` | 否 |

**出参** 分页<[`Supplier`](#supplier)>

### `POST /api/ops/inventory/suppliers`

权限码 `device:inventory:warehouse` · `InventoryController#createSupplier`

**请求体** [`SupplierReq`](#supplierreq)

**出参** [`Supplier`](#supplier)

### `GET /api/ops/inventory/suppliers/stats`

供应商绩效（V207）：到货 / 入库 / 不合格 / 退货台数与合格率。

权限码 `device:inventory:read` · `InventoryController#supplierStats`

**出参** `List<ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierStats>`

### `POST /api/ops/inventory/suppliers/{supplierNo}`

权限码 `device:inventory:warehouse` · `InventoryController#updateSupplier`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `supplierNo` | `String` | 是 |

**请求体** [`SupplierReq`](#supplierreq)

**出参** [`Supplier`](#supplier)

### `POST /api/ops/inventory/suppliers/{supplierNo}/archive`

权限码 `device:inventory:warehouse` · `InventoryController#archiveSupplier`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `supplierNo` | `String` | 是 |

**出参** [`Supplier`](#supplier)

### `POST /api/ops/inventory/suppliers/{supplierNo}/unarchive`

权限码 `device:inventory:warehouse` · `InventoryController#unarchiveSupplier`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `supplierNo` | `String` | 是 |

**出参** [`Supplier`](#supplier)

### `GET /api/ops/inventory/warehouses`

权限码 `device:inventory:read` · `InventoryController#warehouses`

**出参** [`WarehouseItem`](#warehouseitem)

### `POST /api/ops/inventory/warehouses`

权限码 `device:inventory:warehouse` · `InventoryController#createWarehouse`

**请求体** [`WarehouseReq`](#warehousereq)

**出参** [`WarehouseItem`](#warehouseitem)

### `POST /api/ops/inventory/warehouses/{warehouseNo}`

权限码 `device:inventory:warehouse` · `InventoryController#updateWarehouse`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `warehouseNo` | `String` | 是 |

**请求体** [`WarehouseReq`](#warehousereq)

**出参** [`WarehouseItem`](#warehouseitem)

### `POST /api/ops/inventory/warehouses/{warehouseNo}/archive`

权限码 `device:inventory:warehouse` · `InventoryController#archiveWarehouse`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `warehouseNo` | `String` | 是 |

**出参** [`WarehouseItem`](#warehouseitem)

### `POST /api/ops/inventory/warehouses/{warehouseNo}/unarchive`

权限码 `device:inventory:warehouse` · `InventoryController#unarchiveWarehouse`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `warehouseNo` | `String` | 是 |

**出参** [`WarehouseItem`](#warehouseitem)

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
| 查询 | `inPool` | `Boolean` | 否 |

**出参** 分页<[`Lead`](#lead)>

### `POST /api/ops/leads`

权限码 `location:crm:update` · `LocExtController#createLead`

**请求体** [`LocLead`](#loclead)

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

**请求体** [`LocLead`](#loclead)

**出参** [`Lead`](#lead)

### `POST /api/ops/leads/{leadNo}/claim`

从公共线索池认领（超 M 天无跟进的商机会被回收进池）。

权限码 `location:crm:update` · `LocExtController#claimLead`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |

**出参** [`Lead`](#lead)

### `POST /api/ops/leads/{leadNo}/convert`

签约转化：场地方 + 站点（筹备中）+ 带谈判条款的合同草稿，一次生成，不重复录入。

权限码 `location:crm:update` · `LocExtController#convertLead`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `leadNo` | `String` | 是 |

**请求体** [`LeadConvertReq`](#leadconvertreq)

**出参** [`LeadConversion`](#leadconversion)

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
| 查询 | `showArchived` | `Boolean` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |

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

**请求体** [`NoticeReq`](#noticereq)

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{noticeNo}`

权限码 `marketing:notice:update` · `MarketingController#updateNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `noticeNo` | `String` | 是 |

**请求体** [`NoticeReq`](#noticereq)

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{no}/archive`

`Map.of` 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。

权限码 `marketing:notice:update` · `MarketingController#archiveNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/ops/marketing/notices/{no}/offline`

权限码 `marketing:notice:update` · `MarketingController#offlineNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{no}/publish`

发布 / 下线公告。

权限码 `marketing:notice:update` · `MarketingController#publishNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** [`NoticeVO`](#noticevo)

### `POST /api/ops/marketing/notices/{no}/unarchive`

取消归档Notice：清空时间戳，回到默认列表。

权限码 `marketing:notice:update` · `MarketingController#unarchiveNotice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `GET /api/ops/merchant-revenue/alerts`

设备提醒：在用设备里离线几台、在线但没有可借电池几台，各带最多 20 个柜号供点进去处理。

权限码 `dashboard:overview:read` · `MerchantRevenueController#alerts`

**出参** [`DeviceAlerts`](#devicealerts)

### `GET /api/ops/merchant-revenue/by-device`

权限码 `dashboard:overview:read` · `MerchantRevenueController#byDevice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`RevenueByDevice`](#revenuebydevice)>

### `GET /api/ops/merchant-revenue/summary`

权限码 `dashboard:overview:read` · `MerchantRevenueController#summary`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** [`RevenueSummary`](#revenuesummary)

### `GET /api/ops/merchant-revenue/trend`

权限码 `dashboard:overview:read` · `MerchantRevenueController#trend`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** 数组<[`RevenueDay`](#revenueday)>

### `GET /api/ops/operation/overview`

权限码 `location:overview:read` · `OperationController#overview`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** [`OperationOverview`](#operationoverview)

### `GET /api/ops/ops-flow-metrics`

运营核心流程指标（G5）。

权限码 `dashboard:overview:read` · `OpsFlowMetricsController#metrics`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `LocalDate` | 否 |
| 查询 | `to` | `LocalDate` | 否 |

**出参** [`OpsFlowMetrics`](#opsflowmetrics)

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

**请求体** [`DevOtaRelease`](#devotarelease)

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

**请求体** [`DevOtaRollout`](#devotarollout)

**出参** [`OtaRolloutRow`](#otarolloutrow)

### `POST /api/ops/ota-rollouts/{rolloutNo}`

更新投放（灰度比例调整、回滚 `status=ROLLBACK`）。

权限码 `device:ota:manage` · `DeviceController#updateOtaRollout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `rolloutNo` | `String` | 是 |

**请求体** [`DevOtaRollout`](#devotarollout)

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
| 查询 | `suspectedLost` | `Boolean` | 否 |

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

### `POST /api/ops/powerbanks/{powerbankNo}/confirm-lost`

疑似丢失经核实：确认丢失（RENTED → LOST，状态机 CONFIRM_LOST）。

权限码 `device:powerbank:update` · `DeviceController#confirmLost`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `powerbankNo` | `String` | 是 |

**请求体** [`LostVerdictReq`](#lostverdictreq)

**出参** [`PowerbankRow`](#powerbankrow)

### `POST /api/ops/powerbanks/{powerbankNo}/dismiss-lost`

疑似丢失经核实：已找回 / 误判 —— 清标记并重置失联计时。

权限码 `device:powerbank:update` · `DeviceController#dismissLost`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `powerbankNo` | `String` | 是 |

**请求体** [`LostVerdictReq`](#lostverdictreq)

**出参** [`PowerbankRow`](#powerbankrow)

### `POST /api/ops/powerbanks/{powerbankNo}/qc`

权限码 `device:qc:inspect` · `AssetOpsController#inspectPowerbank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `powerbankNo` | `String` | 是 |

**请求体** [`QcReq`](#qcreq)

**出参** [`QcRecord`](#qcrecord)

### `GET /api/ops/qc-records`

权限码 `device:cabinet:read` · `AssetOpsController#qcRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `itemNo` | `String` | 是 |

**出参** 数组<[`QcRecord`](#qcrecord)>

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

### `GET /api/ops/reports/inventory`

库存报表（V206）：现算，不走 T+1 快照；期间默认本月。

权限码 `report:inventory:read` · `ReportController#inventory`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `LocalDate` | 否 |
| 查询 | `to` | `LocalDate` | 否 |
| 查询 | `warehouseNo` | `String` | 否 |

**出参** [`InventoryReport`](#inventoryreport)

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

权限码 `report:device:read` 或 `report:location:read` 或 `report:finance:read` · `ReportController#trend`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `kind` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** [`ReportTrend`](#reporttrend)

### `GET /api/ops/reports/{kind}/export`

三张周期报表的后端导出（K8 / RQ-RPT-03 / AC-09）：同列表的筛选、全量（到 `ExportSupport#MAX_ROWS`）， `format=xlsx|pdf`。

权限码 `report:device:read` 或 `report:location:read` 或 `report:finance:read` · `ReportController#export`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `kind` | `String` | 是 |
| 查询 | `format` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** `void`

### `GET /api/ops/settlement-adjustments`

权限码 `finance:settlement:read` · `AdjustmentController#page`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `payeeNo` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `source` | `String` | 否 |
| 查询 | `period` | `String` | 否 |

**出参** 分页<[`Adjustment`](#adjustment)>

### `POST /api/ops/settlement-adjustments/{adjNo}/confirm`

权限码 `finance:settlement:confirm` · `AdjustmentController#confirm`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjNo` | `String` | 是 |

**请求体** [`ConfirmReq`](#confirmreq)

**出参** [`Adjustment`](#adjustment)

### `POST /api/ops/settlement-adjustments/{adjNo}/void`

权限码 `finance:settlement:confirm` · `AdjustmentController#voidIt`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjNo` | `String` | 是 |

**请求体** [`VoidReq`](#voidreq)

**出参** [`Adjustment`](#adjustment)

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

只读漏斗（2026-09-25：站点状态与门店生命周期合并）：签约前是商机阶段，签约后是站点状态。

权限码 `location:venue:read` · `LocExtController#siteLifecycles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `phase` | `String` | 否 |

**出参** 分页<[`LifecycleRow`](#lifecyclerow)>

### `GET /api/ops/site-lifecycles/funnel`

权限码 `location:venue:read` · `LocExtController#siteLifecycleFunnel`

**出参** 数组<[`FunnelStage`](#funnelstage)>

### `GET /api/ops/sites`

权限码 `location:poi:read` · `SiteController#page`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `showArchived` | `Boolean` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `missingOwner` | `Boolean` | 否 |
| 查询 | `missingOpenHours` | `Boolean` | 否 |
| 查询 | `agentNo` | `String` | 否 |

**出参** 分页<[`Site`](#site)>

### `POST /api/ops/sites`

权限码 `location:poi:create` · `SiteController#create`

**请求体** [`SiteReq`](#sitereq)

**出参** [`Site`](#site)

### `GET /api/ops/sites/summary`

权限码 `location:poi:read` · `SiteController#summary`

**出参** [`SiteSummary`](#sitesummary)

### `GET /api/ops/sites/{siteNo}`

权限码 `location:poi:read` · `SiteController#get`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Site`](#site)

### `POST /api/ops/sites/{siteNo}`

权限码 `location:poi:create` · `SiteController#update`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`SiteReq`](#sitereq)

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

### `POST /api/ops/sites/{siteNo}/archive`

归档 / 取消归档（前端契约 Archivable）。

权限码 `location:site:update` · `SiteController#archive`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Site`](#site)

### `POST /api/ops/sites/{siteNo}/close`

关闭：无设备、无未结工单、无在借订单（见 close-gate）。

权限码 `location:poi:update` · `SiteController#close`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`CloseReq`](#closereq)

**出参** [`Site`](#site)

### `GET /api/ops/sites/{siteNo}/close-gate`

权限码 `location:poi:read` · `SiteController#closeGate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Checklist`](#checklist)

### `GET /api/ops/sites/{siteNo}/opening-checklist`

权限码 `location:poi:read` · `SiteController#openingChecklist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Checklist`](#checklist)

### `POST /api/ops/sites/{siteNo}/pause`

暂停营业：停借保还 —— 已借出的仍可归还，停业不该把用户的充电宝扣在手里。

权限码 `location:poi:update` · `SiteController#pause`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`PauseReq`](#pausereq)

**出参** [`Site`](#site)

### `POST /api/ops/sites/{siteNo}/resume`

恢复营业：站点须有生效合同。

权限码 `location:poi:update` · `SiteController#resume`

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

### `GET /api/ops/sites/{siteNo}/status-logs`

权限码 `location:poi:read` · `SiteController#statusLogs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** 数组<[`SiteStatusLogItem`](#sitestatuslogitem)>

### `GET /api/ops/sites/{siteNo}/surveys`

权限码 `location:poi:read` · `SiteController#surveys`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** 数组<[`SiteSurvey`](#sitesurvey)>

### `POST /api/ops/sites/{siteNo}/surveys`

现场勘测（C1）：信号 / 电源 / 可摆位置 / 照片 / 结论；以最近一次为准，首台设备上线须通过。

权限码 `location:poi:update` · `SiteController#recordSurvey`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`SurveyReq`](#surveyreq)

**出参** [`SiteSurvey`](#sitesurvey)

### `POST /api/ops/sites/{siteNo}/unarchive`

权限码 `location:site:update` · `SiteController#unarchive`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**出参** [`Site`](#site)

### `POST /api/ops/sites/{siteNo}/withdraw`

权限码 `location:poi:update` · `SiteController#withdraw`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `siteNo` | `String` | 是 |

**请求体** [`WithdrawReq`](#withdrawreq)

**出参** [`Site`](#site)

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

权限码 `workorder:sla:update` · `WoExtController#createSlaRule`

**请求体** [`WoSlaRule`](#woslarule)

**出参** [`SlaRule`](#slarule)

### `GET /api/ops/sla-rules/{slaNo}`

权限码 `workorder:wo:read` · `WoExtController#slaRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slaNo` | `String` | 是 |

**出参** [`SlaRule`](#slarule)

### `POST /api/ops/sla-rules/{slaNo}`

权限码 `workorder:sla:update` · `WoExtController#updateSlaRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `slaNo` | `String` | 是 |

**请求体** [`WoSlaRule`](#woslarule)

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

### `POST /api/ops/venue-onboardings`

审核。

权限码 `location:venue:create` · `LocExtController#createOnboarding`

**请求体** [`VenueOnboarding`](#venueonboarding)

**出参** [`VenueOnboarding`](#venueonboarding)

### `GET /api/ops/venue-onboardings/{onboardingNo}`

权限码 `location:venue:read` · `LocExtController#onboarding`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `onboardingNo` | `String` | 是 |

**出参** [`VenueOnboarding`](#venueonboarding)

### `POST /api/ops/venue-onboardings/{onboardingNo}`

权限码 `location:venue:update` · `LocExtController#updateOnboarding`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `onboardingNo` | `String` | 是 |

**请求体** [`VenueOnboarding`](#venueonboarding)

**出参** [`VenueOnboarding`](#venueonboarding)

### `POST /api/ops/venue-onboardings/{onboardingNo}/review`

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
| 查询 | `showArchived` | `Boolean` | 否 |

**出参** 分页<[`Venue`](#venue)>

### `POST /api/ops/venues`

新建 / 修改场地方。

权限码 `location:venue:create` · `OpsController#createVenue`

**请求体** [`LocVenue`](#locvenue)

**出参** [`Venue`](#venue)

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

### `POST /api/ops/venues/{venueNo}`

权限码 `location:venue:update` · `OpsController#updateVenue`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `venueNo` | `String` | 是 |

**请求体** [`LocVenue`](#locvenue)

**出参** [`Venue`](#venue)

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
| 查询 | `priority` | `String` | 否 |
| 查询 | `source` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `assigneeNo` | `String` | 否 |
| 查询 | `slaState` | `String` | 否 |
| 查询 | `reviewStatus` | `String` | 否 |

**出参** 分页<[`WorkOrder`](#workorder)>

### `POST /api/ops/work-orders`

手工开单（补 [api/README §6] 的 G6 缺口）。

权限码 `workorder:wo:create` · `WoExtController#createWorkOrder`

**请求体** [`WorkOrderDraft`](#workorderdraft)

**出参** [`WorkOrder`](#workorder)

### `GET /api/ops/work-orders/assignee-candidates`

派单候选人：站点运维责任人置顶，其后是该站点运营代理与 OPS 角色员工。

权限码 `workorder:wo:dispatch` · `WoExtController#assigneeCandidates`

**出参** `List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate>`

### `GET /api/ops/work-orders/costs`

工单成本汇总（G4）：按承担方（站点效益 / 代理运维）聚合完工工单的配件 + 人工金额。

权限码 `workorder:wo:read` · `WoExtController#costs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `from` | `.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate` | 否 |
| 查询 | `to` | `.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate` | 否 |
| 查询 | `bearerType` | `String` | 否 |

**出参** `List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CostRow>`

### `GET /api/ops/work-orders/pool`

抢单池（E3）：未派出的工单，按数据范围 —— 区域员工看到本区域的池。

权限码 `workorder:wo:handle` · `WoExtController#pool`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `type` | `String` | 否 |

**出参** `ai.neargo.common.core.PageResult<WorkOrder>`

### `GET /api/ops/work-orders/summary`

工单摘要条：待派 · 即将超时（2 小时内）· 已超时 · 复核未通过（2026-09-25）。

权限码 `workorder:wo:read` · `WoExtController#summary`

**出参** [`WoSummary`](#wosummary)

### `GET /api/ops/work-orders/{woNo}`

工单详情：处理时间线（派单 + 现场处理合并）、现场照片、关联的业务告警。

权限码 `workorder:wo:read` · `WoExtController#detail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**出参** [`WorkOrderDetailView`](#workorderdetailview)

### `POST /api/ops/work-orders/{woNo}/accept`

权限码 `workorder:wo:handle` · `WoExtController#accept`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`AcceptReq`](#acceptreq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/close`

完成 / 审核关单。

权限码 `workorder:wo:close` · `WoExtController#close`

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

### `POST /api/ops/work-orders/{woNo}/derive`

巡检派生（D4）：巡检现场发现问题直接开维修 / 补宝 / 清洁单，来源挂巡检单号。

权限码 `workorder:wo:handle` · `WoExtController#derive`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`DeriveReq`](#derivereq)

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/dispatch`

权限码 `workorder:wo:dispatch` · `OpsController#dispatch`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/grab`

抢单（E3）：派给自己并接单。

权限码 `workorder:wo:handle` · `WoExtController#grab`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**出参** [`WorkOrder`](#workorder)

### `POST /api/ops/work-orders/{woNo}/handle`

权限码 `workorder:wo:handle` · `WoExtController#handle`

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

### `POST /api/ops/work-orders/{woNo}/takeover`

平台接管（F4）：代理的单 SLA 超时后改派平台员工；employeeNo 空 = 按站点责任人 / 区域负载选。

权限码 `workorder:wo:dispatch` · `WoExtController#takeover`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `woNo` | `String` | 是 |

**请求体** [`TakeoverReq`](#takeoverreq)

**出参** [`WorkOrder`](#workorder)


## 交易：订单 · 计价 · 支付 · 结算 · 分润 · 发票

96 个端点。

### `GET /api/trade/approvals`

权限码 `order:refund:audit` 或 `finance:ledger:create` · `ApprovalController#list`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |

**出参** 分页<[`ApprovalRequest`](#approvalrequest)>

### `POST /api/trade/approvals/{requestNo}/approve`

权限码 `order:refund:audit` 或 `finance:ledger:create` · `ApprovalController#approve`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `requestNo` | `String` | 是 |

**出参** [`ApprovalRequest`](#approvalrequest)

### `POST /api/trade/approvals/{requestNo}/reject`

权限码 `order:refund:audit` 或 `finance:ledger:create` · `ApprovalController#reject`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `requestNo` | `String` | 是 |

**请求体** [`RejectReq`](#rejectreq)

**出参** [`ApprovalRequest`](#approvalrequest)

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

权限码 `order:deposit:manage` · `FinanceController#buyoutDeposit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `depositNo` | `String` | 是 |

**请求体** [`DepositBuyoutReq`](#depositbuyoutreq)

**出参** `Object`

### `POST /api/trade/deposits/{depositNo}/dun`

欠款催缴：**只留痕不改状态** —— 催缴不改变欠款事实，改状态会让「已催缴」被误读成「已解决」。

权限码 `order:arrears:dun` · `FinanceController#dunDeposit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `depositNo` | `String` | 是 |

**请求体** [`DepositDunReq`](#depositdunreq)

**出参** `Object`

### `POST /api/trade/deposits/{no}/release`

权限码 `order:deposit:manage` · `OrderOpsController#releaseDeposit`

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

权限码 `finance:invoice:void` · `FinanceController#voidInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**请求体** [`InvoiceVoidReq`](#invoicevoidreq)

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

### `GET /api/trade/order-events`

订单状态流转留痕（审计视图，append 表 `ord_event_log`）。

权限码 `order:order:read` · `TradeController#orderEvents`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `orderNo` | `String` | 是 |

**出参** 数组<[`OrderEvent`](#orderevent)>

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
| 查询 | `payStatus` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |

**出参** 分页<[`RentOrder`](#rentorder)>

### `GET /api/trade/orders/export`

订单导出（RNT-03 / AC-09）：同列表筛选、全量（上限 `ai.neargo.sharehub.portal.shared.ExportSupport#MAX_ROWS`）， `format=xlsx|pdf`。

权限码 `order:order:export` · `TradeController#exportOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `format` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `payStatus` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `siteNo` | `String` | 否 |
| 查询 | `cabinetNo` | `String` | 否 |

**出参** `void`

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

**出参** `Object`

### `GET /api/trade/payables`

权限码 `finance:share_record:read` · `FinanceController#payables`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `payeeType` | `String` | 否 |
| 查询 | `payeeNo` | `String` | 否 |
| 查询 | `fromPeriod` | `String` | 否 |
| 查询 | `toPeriod` | `String` | 否 |
| 查询 | `onlyOutstanding` | `Boolean` | 否 |

**出参** 分页<[`PayableRow`](#payablerow)>

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

### `GET /api/trade/payout-tasks`

权限码 `finance:withdrawal:read` · `PayoutTaskController#page`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`PayoutTask`](#payouttask)>

### `POST /api/trade/payout-tasks`

权限码 `finance:withdrawal:create` · `PayoutTaskController#create`

**请求体** [`PayoutTaskCreateReq`](#payouttaskcreatereq)

**出参** [`PayoutTask`](#payouttask)

### `GET /api/trade/payout-tasks/preview`

付款任务（商家一期 B4，需求方案 §八）：平台向商家付款，财务在运营端手动执行。

权限码 `finance:withdrawal:read` · `PayoutTaskController#preview`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `cutoffDate` | `String` | 是 |

**出参** [`PayoutPreview`](#payoutpreview)

### `GET /api/trade/payout-tasks/{taskNo}`

权限码 `finance:withdrawal:read` · `PayoutTaskController#detail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `taskNo` | `String` | 是 |

**出参** [`PayoutTask`](#payouttask)

### `POST /api/trade/payout-tasks/{taskNo}/audit`

权限码 `finance:withdrawal:audit` · `PayoutTaskController#audit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `taskNo` | `String` | 是 |

**请求体** [`PayoutTaskAuditReq`](#payouttaskauditreq)

**出参** [`PayoutTask`](#payouttask)

### `POST /api/trade/payout-tasks/{taskNo}/confirm`

权限码 `finance:withdrawal:pay` · `PayoutTaskController#confirm`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `taskNo` | `String` | 是 |

**请求体** [`PayoutConfirmReq`](#payoutconfirmreq)

**出参** [`PayoutTask`](#payouttask)

### `GET /api/trade/payout-tasks/{taskNo}/export`

转账文件下载。

权限码 `finance:withdrawal:export` · `PayoutTaskController#export`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `taskNo` | `String` | 是 |

**出参** `void`

### `GET /api/trade/price-adjustments`

权限码 `pricing:adjustment:read` · `OperationController#adjustments`

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

权限码 `pricing:adjustment:create` · `OperationController#saveAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}`

新建或编辑调价单。

权限码 `pricing:adjustment:create` · `OperationController#saveAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/cancel`

权限码 `pricing:adjustment:cancel` · `OperationController#cancelAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**请求体** `对象（自由键）`

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/retry`

权限码 `pricing:adjustment:create` · `OperationController#retryAdjustment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adjustNo` | `String` | 是 |

**出参** `对象（自由键）`

### `POST /api/trade/price-adjustments/{adjustNo}/revert`

权限码 `pricing:adjustment:cancel` · `OperationController#revertAdjustment`

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

**请求体** [`PricePlanReq`](#priceplanreq)

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

**请求体** [`PricePlanReq`](#priceplanreq)

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

**请求体** [`PricingScheduleReq`](#pricingschedulereq)

**出参** [`PricingSchedule`](#pricingschedule)

### `POST /api/trade/pricing-schedules/{ruleNo}`

权限码 `pricing:rule:update` · `PricingController#updatePricingSchedule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** [`PricingScheduleReq`](#pricingschedulereq)

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

权限码 `finance:recon:handle` · `FinanceController#resolveRecon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `batchNo` | `String` | 是 |

**请求体** [`ReconResolveReq`](#reconresolvereq)

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

### `GET /api/trade/self-bills`

权限码 `finance:invoice:read` · `FinanceController#selfBills`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `agentNo` | `String` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`SelfBillView`](#selfbillview)>

### `POST /api/trade/self-bills/issue`

补出：开关当时关着、商家后补了税号、或自动出单失败时，对一笔已到账的提现补一张。

权限码 `finance:invoice:issue` · `FinanceController#issueSelfBill`

**请求体** [`SelfBillIssueReq`](#selfbillissuereq)

**出参** [`SelfBillView`](#selfbillview)

### `GET /api/trade/self-bills/{billNo}/pdf`

权限码 `finance:invoice:read` · `FinanceController#selfBillPdf`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `billNo` | `String` | 是 |

**出参** `void`

### `GET /api/trade/settlements`

结算单列表（自 `TradeController` SeedData 骨架迁入，走 `stl_settlement` 表）。

权限码 `finance:settlement:read` 或 `agent:settlement:read` · `FinanceController#settlements`

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

**请求体** [`SettlementGenerateReq`](#settlementgeneratereq)

**出参** `List<String>`

### `GET /api/trade/settlements/{settleNo}`

权限码 `finance:settlement:read` 或 `agent:settlement:read` · `FinanceController#settlement`

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

权限码 `finance:settlement:read` 或 `agent:settlement:read` · `FinanceController#settlementRecords`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** `Object`

### `GET /api/trade/settlements/{settleNo}/statement`

场地方对账单（运营核心流程 G3，裁决 #2）：结算单导出对账单线下发给场地方，首版不做场地方门户。

权限码 `finance:settlement:read` 或 `agent:settlement:read` · `StatementController#statement`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |

**出参** [`Statement`](#statement)

### `GET /api/trade/settlements/{settleNo}/statement.html`

可打印对账单：浏览器打开后「打印 → 存为 PDF」。

权限码 `finance:settlement:read` 或 `agent:settlement:read` · `StatementController#printable`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `settleNo` | `String` | 是 |
| 查询 | `lang` | `String` | 否 |

**出参** `void`

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

**请求体** [`ShareRuleReq`](#sharerulereq)

**出参** [`ShareRule`](#sharerule)

### `POST /api/trade/share-rules/{ruleNo}`

权限码 `finance:share_rule:create` · `FinanceController#updateShareRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** [`ShareRuleReq`](#sharerulereq)

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

### `GET /api/trade/tax-invoices`

权限码 `finance:invoice:read` · `FinanceController#taxInvoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `orderNo` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`TaxInvoiceView`](#taxinvoiceview)>

### `GET /api/trade/tax-invoices/export`

导出（K8 / RQ-INV-07~08）：`format=xlsx|pdf` 出登记表，`format=zip` 把区间内已开出的票逐张出 PDF 打包。

权限码 `finance:invoice:read` · `FinanceController#exportTaxInvoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `format` | `String` | 否 |
| 查询 | `orderNo` | `String` | 否 |
| 查询 | `kind` | `String` | 否 |
| 查询 | `status` | `String` | 否 |
| 查询 | `from` | `String` | 否 |
| 查询 | `to` | `String` | 否 |

**出参** `void`

### `GET /api/trade/tax-invoices/{invoiceNo}`

权限码 `finance:invoice:read` · `FinanceController#taxInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**出参** [`TaxInvoiceView`](#taxinvoiceview)

### `GET /api/trade/tax-invoices/{invoiceNo}/pdf`

单张票 PDF（RQ-INV-06）。

权限码 `finance:invoice:read` · `FinanceController#taxInvoicePdf`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**出参** `void`

### `POST /api/trade/tax-invoices/{invoiceNo}/retry`

BLOCKED（开票主体不全）的票在补齐主体后重开；ISSUED 的不可动。

权限码 `finance:invoice:issue` · `FinanceController#retryTaxInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**出参** [`TaxInvoiceView`](#taxinvoiceview)

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
| 查询 | `payeeNo` | `String` | 否 |

**出参** 分页<[`Withdrawal`](#withdrawal)>

### `POST /api/trade/withdrawals`

提现申请。

权限码 `finance:withdrawal:apply` · `FinanceController#applyWithdrawal`

**请求体** [`WithdrawApplyReq`](#withdrawapplyreq)

**出参** [`Withdrawal`](#withdrawal)

### `POST /api/trade/withdrawals/{withdrawNo}/approval/resubmit`

OpsKit 送审失败（死信）后重新送审（M5-1）。

权限码 `finance:withdrawal:audit` · `FinanceController#resubmitWithdrawalApproval`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `withdrawNo` | `String` | 是 |

**出参** [`Withdrawal`](#withdrawal)

### `POST /api/trade/withdrawals/{withdrawNo}/approval/retry-effect`

OpsKit 已通过但业务检查没过（如收款账户缺失），补齐后重试生效（M5-1）。

权限码 `finance:withdrawal:audit` · `FinanceController#retryWithdrawalApprovalEffect`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `withdrawNo` | `String` | 是 |

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

62 个端点。

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

**请求体** [`AdCampaignReq`](#adcampaignreq)

**出参** [`AdCampaignVO`](#adcampaignvo)

### `POST /api/user/ad-campaigns/{adNo}`

权限码 `marketing:ad:update` · `MarketingController#updateAdCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `adNo` | `String` | 是 |

**请求体** [`AdCampaignReq`](#adcampaignreq)

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

**请求体** [`CampaignReq`](#campaignreq)

**出参** [`CampaignVO`](#campaignvo)

### `POST /api/user/campaigns/{campaignNo}`

权限码 `marketing:campaign:update` · `MarketingController#updateCampaign`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `campaignNo` | `String` | 是 |

**请求体** [`CampaignReq`](#campaignreq)

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

**请求体** [`CouponTplReq`](#coupontplreq)

**出参** [`CouponTplVO`](#coupontplvo)

### `POST /api/user/coupons/{couponNo}`

权限码 `marketing:coupon:create` · `MarketingController#updateCoupon`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `couponNo` | `String` | 是 |

**请求体** [`CouponTplReq`](#coupontplreq)

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

**请求体** [`CouponIssueReq`](#couponissuereq)

**出参** [`CouponIssueResultVO`](#couponissueresultvo)

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

### `GET /api/user/cuser-invoices`

C 端开票申请队列。

权限码 `user:invoice:read` · `UserOpsController#cuserInvoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`CUserInvoiceRow`](#cuserinvoicerow)>

### `POST /api/user/cuser-invoices/{invoiceNo}/issue`

开具：置 ISSUED 并回填发票文件地址。

权限码 `user:invoice:handle` · `UserOpsController#issueCUserInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**请求体** [`InvoiceIssueReq`](#invoiceissuereq)

**出参** [`CUserInvoiceRow`](#cuserinvoicerow)

### `POST /api/user/cuser-invoices/{invoiceNo}/reject`

驳回：原因必填 —— 只说「已驳回」等于让用户无从改正后重提。

权限码 `user:invoice:handle` · `UserOpsController#rejectCUserInvoice`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `invoiceNo` | `String` | 是 |

**请求体** [`InvoiceRejectReq`](#invoicerejectreq)

**出参** [`CUserInvoiceRow`](#cuserinvoicerow)

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

**请求体** [`UsrFreeWhitelist`](#usrfreewhitelist)

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `POST /api/user/free-whitelist/{userNo}`

权限码 `user:risk:update` · `UserOpsController#updateWhitelist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**请求体** [`UsrFreeWhitelist`](#usrfreewhitelist)

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `POST /api/user/free-whitelist/{userNo}/revoke`

撤销 = 软删除（`status=REVOKED` 留记录），不是 DELETE（[api §1.5] 全站零 DELETE）。

权限码 `user:risk:update` · `UserOpsController#revokeWhitelist`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**出参** [`FreeUserWhitelist`](#freeuserwhitelist)

### `GET /api/user/logoffs`

操作人以会话为准，不信前端传值 —— 手工调账必须回答「谁改的」。

权限码 `user:logoff:read` · `UserOpsController#logoffs`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`LogoffItem`](#logoffitem)>

### `POST /api/user/logoffs/{cUserNo}/revoke`

代为撤销注销申请。

权限码 `user:logoff:revoke` · `UserOpsController#revokeLogoff`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `cUserNo` | `String` | 是 |

**出参** [`LogoffItem`](#logoffitem)

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

### `GET /api/user/member-plans`

会员方案列表（`mbr_plan`）。

权限码 `user:member:read` · `UserOpsController#memberPlans`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** `Object`

### `POST /api/user/member-plans`

新建 / 修改会员方案。

权限码 `user:member:update` · `UserOpsController#saveMemberPlan`

**请求体** [`MemberPlanReq`](#memberplanreq)

**出参** `Object`

### `POST /api/user/member-plans/{no}/disable`

下架。

权限码 `user:member:update` · `UserOpsController#disableMemberPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**出参** `Object`

### `POST /api/user/member-plans/{no}/enable`

上架：对 C 端可见可买。

权限码 `user:member:update` · `UserOpsController#enableMemberPlan`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

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

**请求体** [`UsrMembership`](#usrmembership)

**出参** [`MemberRow`](#memberrow)

### `POST /api/user/members/{userNo}`

权限码 `user:member:update` · `UserOpsController#updateMember`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**请求体** [`UsrMembership`](#usrmembership)

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

**请求体** [`PushReq`](#pushreq)

**出参** [`PushMessageVO`](#pushmessagevo)

### `POST /api/user/push-messages/sweep-due`

扫描到点的排期推送并发出。

权限码 `marketing:push:update` · `MarketingController#sweepDuePushMessages`

**请求体** [`PushSweepReq`](#pushsweepreq)

**出参** `Object`

### `POST /api/user/push-messages/{pushNo}`

权限码 `marketing:push:send` · `MarketingController#updatePush`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `pushNo` | `String` | 是 |

**请求体** [`PushReq`](#pushreq)

**出参** [`PushMessageVO`](#pushmessagevo)

### `POST /api/user/push-messages/{pushNo}/finish`

推送收尾：SENDING → SENT，落触达统计。

权限码 `marketing:push:update` · `MarketingController#finishPushMessage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `pushNo` | `String` | 是 |

**请求体** [`PushFinishReq`](#pushfinishreq)

**出参** `Object`

### `POST /api/user/push-messages/{pushNo}/send`

发送推送。

权限码 `marketing:push:update` · `MarketingController#sendPushMessage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `pushNo` | `String` | 是 |

**请求体** [`PushSendReq`](#pushsendreq)

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

**请求体** [`RechargePackageReq`](#rechargepackagereq)

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

**请求体** [`RechargePackageReq`](#rechargepackagereq)

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

### `POST /api/user/referral-rules`

新建 / 修改裂变规则。

权限码 `marketing:referral:update` · `MarketingController#createReferralRule`

**请求体** [`ReferralRuleVO`](#referralrulevo)

**出参** `Object`

### `POST /api/user/referral-rules/{ruleNo}`

权限码 `marketing:referral:update` · `MarketingController#updateReferralRule`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `ruleNo` | `String` | 是 |

**请求体** [`ReferralRuleVO`](#referralrulevo)

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

### `POST /api/user/wallets`

运营手工调整钱包。

权限码 `user:wallet:update` · `UserOpsController#adjustWallet`

**请求体** [`WalletAdjustReq`](#walletadjustreq)

**出参** [`WalletRow`](#walletrow)

### `POST /api/user/wallets/{userNo}`

权限码 `user:wallet:update` · `UserOpsController#adjustWalletOf`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `userNo` | `String` | 是 |

**请求体** [`WalletAdjustReq`](#walletadjustreq)

**出参** [`WalletRow`](#walletrow)

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

37 个端点。

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

**请求体** [`AgentAccountReq`](#agentaccountreq)

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

**请求体** [`AgentAccountReq`](#agentaccountreq)

**出参** [`AgentAccount`](#agentaccount)

### `GET /api/agent/agents`

权限码 `agent:agent:read` · `AgentController#agents`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `keyword` | `String` | 否 |

**出参** 分页<[`Agent`](#agent)>

### `POST /api/agent/agents/{agentNo}`

修改代理商档案。

权限码 `agent:agent:update` · `AgentController#update`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**请求体** [`Agent`](#agent)

**出参** [`Agent`](#agent)

### `GET /api/agent/agents/{agentNo}/exit`

该代理**当前在途的**清退单；没有则返回 null（不是 404 —— 「没有在清退」是正常状态，不是错误）。

权限码 `agent:agent:read` · `AgentExitController#openExit`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**出参** [`AgentExit`](#agentexit)

### `POST /api/agent/agents/{agentNo}/exit`

权限码 `agent:agent:update` · `AgentExitController#start`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**请求体** [`ExitReq`](#exitreq)

**出参** [`AgentExit`](#agentexit)

### `POST /api/agent/agents/{agentNo}/invite`

重发开通邀请：作废旧口令、发新的一次性口令（首登强制改）并重寄邀请邮件。

权限码 `agent:apply:approve` · `AgentApplyController#resendInvite`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**出参** `对象（自由键）`

### `POST /api/agent/agents/{agentNo}/login-account`

给代理商开通代理端登录。

权限码 `agent:account:manage` · `AgentController#grantLogin`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**请求体** [`GrantLoginReq`](#grantloginreq)

**出参** [`GrantLoginResult`](#grantloginresult)

### `GET /api/agent/agents/{agentNo}/ops-assessments`

运维月度考核历史（F5）：达成率、在线率、客诉、被接管数与由此定出的运维分成系数。

权限码 `agent:performance:read` · `AgentExitController#assessments`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `agentNo` | `String` | 是 |

**出参** `List<ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment>`

### `POST /api/agent/agents/{no}/archive`

开通结果：成员关系号（`agt_account.account_no`）。

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

自助入口关闭时三个免鉴权端点一律 404。

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
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `excludeAgentNo` | `String` | 否 |

**出参** 分页<[`AssignableAsset`](#assignableasset)>

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

### `POST /api/agent/assignments/batch`

批量回收到平台直营。

权限码 `agent:scope:assign` · `AgentExtController#assignBatch`

**请求体** [`AssignBatchReq`](#assignbatchreq)

**出参** `List<AssignmentLog>`

### `POST /api/agent/assignments/reclaim`

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

**请求体** [`AgentCommissionReq`](#agentcommissionreq)

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

**请求体** [`AgentCommissionReq`](#agentcommissionreq)

**出参** [`AgentCommission`](#agentcommission)

### `GET /api/agent/exits/{exitNo}`

权限码 `agent:agent:read` · `AgentExitController#get`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `exitNo` | `String` | 是 |

**出参** [`AgentExit`](#agentexit)

### `POST /api/agent/exits/{exitNo}/advance`

权限码 `agent:agent:update` · `AgentExitController#advance`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `exitNo` | `String` | 是 |

**出参** [`AgentExit`](#agentexit)

### `GET /api/agent/exits/{exitNo}/gate`

权限码 `agent:agent:read` · `AgentExitController#gate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `exitNo` | `String` | 是 |

**出参** [`Checklist`](#checklist)

### `POST /api/agent/me/contact`

只改联系人（姓名、电话、邮箱）；名称、位置、行业由运营维护。

权限码 `agent:profile:update` · `MerchantProfileController#updateContact`

**请求体** [`ContactReq`](#contactreq)

**出参** [`MerchantProfile`](#merchantprofile)

### `GET /api/agent/me/profile`

权限码 `agent:profile:read` · `MerchantProfileController#profile`

**出参** [`MerchantProfile`](#merchantprofile)

### `GET /api/agent/me/self-bills`

权限码 `finance:withdrawal:read` · `MerchantSelfBillController#mine`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`SelfBillView`](#selfbillview)>

### `GET /api/agent/me/self-bills/{billNo}/pdf`

权限码 `finance:withdrawal:read` · `MerchantSelfBillController#pdf`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `billNo` | `String` | 是 |

**出参** `void`

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

86 个端点。

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

权限码 `system:app_version:release` · `SysSettingController#createAppVersion`

**请求体** [`AppVersionReq`](#appversionreq)

**出参** [`AppVersion`](#appversion)

### `POST /api/platform/app-versions/{versionId}`

权限码 `system:app_version:release` · `SysSettingController#updateAppVersion`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `versionId` | `String` | 是 |

**请求体** [`AppVersionReq`](#appversionreq)

**出参** [`AppVersion`](#appversion)

### `POST /api/platform/app-versions/{versionId}/rollback`

软回滚：`status=ROLLBACK` + `rolloutPercent=0`，**记录保留**。

权限码 `system:app_version:release` · `SysSettingController#rollbackAppVersion`

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

**请求体** [`MdBank`](#mdbank)

**出参** [`BankEntry`](#bankentry)

### `POST /api/platform/banks/{bankCode}`

权限码 `system:bank:update` · `SysDictController#updateBank`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `bankCode` | `String` | 是 |

**请求体** [`MdBank`](#mdbank)

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

**请求体** [`IamDept`](#iamdept)

**出参** [`Department`](#department)

### `POST /api/platform/departments/{deptNo}`

权限码 `org:dept:create` · `OrgController#updateDepartment`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `deptNo` | `String` | 是 |

**请求体** [`IamDept`](#iamdept)

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

**请求体** [`DictEntry`](#dictentry)

**出参** [`DictEntry`](#dictentry)

### `POST /api/platform/dict-entries/{dictNo}`

权限码 `system:dict:update` · `SysConfigController#updateDictEntry`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `dictNo` | `String` | 是 |

**请求体** [`DictEntry`](#dictentry)

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

**请求体** [`EmployeeSaveReq`](#employeesavereq)

**出参** [`Employee`](#employee)

### `POST /api/platform/employees/{employeeNo}`

权限码 `org:employee:update` · `OrgController#updateEmployee`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `employeeNo` | `String` | 是 |

**请求体** [`EmployeeSaveReq`](#employeesavereq)

**出参** [`Employee`](#employee)

### `POST /api/platform/employees/{employeeNo}/credential`

给员工建登录号 / 重置口令，返回**一次性初始口令**。

权限码 `org:employee:update` · `OrgController#resetEmployeeCredential`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `employeeNo` | `String` | 是 |

**出参** `对象（自由键）`

### `POST /api/platform/files`

**无权限码** · `FileController#upload`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `file` | `MultipartFile` | 是 |
| 查询 | `category` | `String` | 是 |

**出参** [`FileRef`](#fileref)

### `GET /api/platform/files/raw/{fileNo}`

本地存储的限时签名链接（生产走 COS 预签名，不经过这里）。

**无权限码** · `FileController#raw`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `fileNo` | `String` | 是 |
| 查询 | `exp` | `long` | 是 |
| 查询 | `sig` | `String` | 是 |

**出参** `ResponseEntity<StreamingResponseBody>`

### `GET /api/platform/files/{fileNo}`

**无权限码** · `FileController#download`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `fileNo` | `String` | 是 |
| 查询 | `inline` | `boolean` | 是 |

**出参** `ResponseEntity<StreamingResponseBody>`

### `GET /api/platform/files/{fileNo}/url`

**无权限码** · `FileController#url`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `fileNo` | `String` | 是 |

**出参** [`SignedUrl`](#signedurl)

### `GET /api/platform/iam/menus`

回得来的那扇门：菜单管理自己就在这一支下面。

权限码 `org:role:read` · `IamAdminController#menus`

**出参** 数组<[`MenuNode`](#menunode)>

### `PUT /api/platform/iam/menus/{menuNo}`

**完整**菜单树（不按权限剪枝）。

权限码 `org:role:update` · `IamAdminController#updateMenu`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `menuNo` | `String` | 是 |

**请求体** [`MenuPatch`](#menupatch)

**出参** [`MenuNode`](#menunode)

### `GET /api/platform/iam/permissions`

权限码目录（构建分配选择器）。

权限码 `org:role:read` · `IamAdminController#permissions`

**出参** `List<IamPermission>`

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

**请求体** [`SysLoginSetting`](#sysloginsetting)

**出参** [`LoginSetting`](#loginsetting)

### `POST /api/platform/login-settings/{country}`

权限码 `system:login_setting:update` · `SysSettingController#updateLoginSetting`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `country` | `String` | 是 |

**请求体** [`SysLoginSetting`](#sysloginsetting)

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

**请求体** [`MdMarketCountry`](#mdmarketcountry)

**出参** [`MarketCountry`](#marketcountry)

### `POST /api/platform/markets/{countryCode}`

权限码 `system:market:update` · `SysSettingController#updateMarket`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `countryCode` | `String` | 是 |

**请求体** [`MdMarketCountry`](#mdmarketcountry)

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

**请求体** [`NotifyBlacklist`](#notifyblacklist)

**出参** [`NotifyBlacklistVO`](#notifyblacklistvo)

### `POST /api/platform/notify-blacklist/{blockNo}`

修改拉黑记录（只改渠道/原因，target 冻结 —— 见 service 注释）。

权限码 `system:notify_blacklist:update` · `NotifyController#updateBlock`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `blockNo` | `String` | 是 |

**请求体** [`NotifyBlacklist`](#notifyblacklist)

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

**请求体** [`NotifyTemplate`](#notifytemplate)

**出参** [`NotifyTemplateVO`](#notifytemplatevo)

### `POST /api/platform/notify-templates/{no}/preview`

当前操作人；无会话（域间/定时任务）时记 SYSTEM。

权限码 `system:notify_template:read` · `NotifyController#previewTemplate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `no` | `String` | 是 |

**请求体** [`NotifyTemplatePreviewReq`](#notifytemplatepreviewreq)

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

**请求体** [`NotifyTemplate`](#notifytemplate)

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

**请求体** [`OpenApiAppReq`](#openapiappreq)

**出参** [`OpenApiApp`](#openapiapp)

### `POST /api/platform/openapi-apps/{appNo}`

权限码 `system:openapi:update` · `SysSettingController#updateOpenApiApp`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `appNo` | `String` | 是 |

**请求体** [`OpenApiAppReq`](#openapiappreq)

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

**请求体** [`ChannelBody`](#channelbody)

**出参** [`PaymentChannelEntry`](#paymentchannelentry)

### `POST /api/platform/payment-channels/{channelCode}`

权限码 `system:payment_channel:update` · `PaymentChannelController#updateChannel`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `channelCode` | `String` | 是 |

**请求体** [`ChannelBody`](#channelbody)

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

**请求体** [`MdProblem`](#mdproblem)

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

**请求体** [`MdProblem`](#mdproblem)

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

**请求体** [`MdRegion`](#mdregion)

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

**请求体** [`MdRegion`](#mdregion)

**出参** [`Region`](#region)

### `GET /api/platform/roles`

权限码 `org:role:read` · `PlatformController#roles`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `showArchived` | `String` | 否 |

**出参** 数组<[`RoleRowVO`](#rolerowvo)>

### `POST /api/platform/roles`

新建 / 修改角色。

权限码 `org:role:update` · `PlatformController#createRole`

**请求体** [`RoleRowVO`](#rolerowvo)

**出参** [`RoleRowVO`](#rolerowvo)

### `POST /api/platform/roles/{roleNo}`

权限码 `org:role:update` · `PlatformController#updateRole`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `roleNo` | `String` | 是 |

**请求体** [`RoleRowVO`](#rolerowvo)

**出参** [`RoleRowVO`](#rolerowvo)

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
| 查询 | `pendingDecision` | `String` | 否 |

**出参** 分页<[`SysParamEntry`](#sysparamentry)>

### `POST /api/platform/sys-params`

权限码 `system:param:update` · `SysConfigController#createSysParam`

**请求体** [`SysParam`](#sysparam)

**出参** [`SysParamEntry`](#sysparamentry)

### `GET /api/platform/sys-params/timezones`

业务时区的候选与当前值。

权限码 `system:param:read` · `SysConfigController#timezoneOptions`

**出参** `对象（自由键）`

### `POST /api/platform/sys-params/{paramKey}`

权限码 `system:param:update` · `SysConfigController#updateSysParam`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `paramKey` | `String` | 是 |

**请求体** [`SysParam`](#sysparam)

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

**请求体** [`SysTaxSetting`](#systaxsetting)

**出参** [`TaxSetting`](#taxsetting)

### `POST /api/platform/tax-settings/{country}`

权限码 `system:tax:update` · `SysSettingController#updateTaxSetting`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `country` | `String` | 是 |

**请求体** [`SysTaxSetting`](#systaxsetting)

**出参** [`TaxSetting`](#taxsetting)


## C 端（`/mp`，消费者会话）

51 个端点。

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

### `GET /mp/auth/methods`

可用登录方式（需求 OPS-SYS-07，`sys_login_setting`）。

**无权限码** · `ConsumerAuthController#methods`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `country` | `String` | 否 |

**出参** `对象（自由键）`

### `POST /mp/auth/otp`

发送 OTP。

**无权限码** · `ConsumerAuthController#otp`

**请求体** [`OtpReq`](#otpreq)

**出参** `对象（自由键）`

### `POST /mp/auth/password/reset`

忘记密码：**验 OTP → 用户自设新口令**。

**无权限码** · `ConsumerAuthController#resetPassword`

**请求体** [`ResetPwdReq`](#resetpwdreq)

**出参** `对象（自由键）`

### `POST /mp/auth/register`

注册 = OTP 验证 + 建户 + 直接发 token（复用 phone_otp 登录链路，带显式昵称）。

**无权限码** · `ConsumerAuthController#register`

**请求体** [`RegisterReq`](#registerreq)

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

### `POST /mp/files`

报障截图。

**无权限码** · `MpFileController#uploadEvidence`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `file` | `MultipartFile` | 是 |

**出参** [`FileRef`](#fileref)

### `GET /mp/files/{fileNo}`

C 端上传（`/mp/files`）—— 目前只有报障截图一种用途。

**无权限码** · `MpFileController#get`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `fileNo` | `String` | 是 |

**出参** `ResponseEntity<StreamingResponseBody>`

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

**出参** 数组<[`NearbyCabinetVO`](#nearbycabinetvo)>

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
| 查询 | `lat` | `Double` | 否 |
| 查询 | `lng` | `Double` | 否 |

**出参** `对象（自由键）`

### `POST /mp/trade/deposit/free`

免押预授权（借出前）：冻结额度走 `pay_auth`（StubPaymentPort，ADR-005 不真扣款）。

**无权限码** · `RentController#depositFree`

**请求体** [`DepositFreeReq`](#depositfreereq)

**出参** `对象（自由键）`

### `GET /mp/trade/orders`

我的订单（属主过滤，只见自己）。

**无权限码** · `RentController#myOrders`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |

**出参** 分页<[`ConsumerOrderVO`](#consumerordervo)>

### `GET /mp/trade/orders/ongoing`

进行中订单（首页快捷入口）：无则 200 + null data，端上按无单渲染。

**无权限码** · `RentController#ongoing`

**出参** [`ConsumerOrderVO`](#consumerordervo)

### `POST /mp/trade/orders/rent`

扫码借出：`{cabinetNo, useFreeDeposit, couponNo`} → 创单，返回订单号 + 充电宝 + 指令号。

**无权限码** · `RentController#rent`

**请求体** [`RentReq`](#rentreq)

**出参** [`RentResult`](#rentresult)

### `GET /mp/trade/orders/{orderNo}`

订单详情：属主鉴权，非本人 → 403（防横向越权 IDOR）。

**无权限码** · `RentController#detail`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** [`ConsumerOrderVO`](#consumerordervo)

### `POST /mp/trade/orders/{orderNo}/buyout`

**无权限码** · `RentController#buyout`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** [`ConsumerOrderVO`](#consumerordervo)

### `GET /mp/trade/orders/{orderNo}/invoices`

买断：不还了，按 `sys_biz_rule` 计费兜底分区的买断价结单（属主鉴权）。

**无权限码** · `RentController#invoices`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |

**出参** 数组<[`TaxInvoiceView`](#taxinvoiceview)>

### `GET /mp/trade/orders/{orderNo}/invoices/{invoiceNo}/pdf`

下载本人某张税务票据的 PDF（K8 / RQ-APP-05）。

**无权限码** · `RentController#invoicePdf`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `orderNo` | `String` | 是 |
| 路径 | `invoiceNo` | `String` | 是 |

**出参** `void`

### `POST /mp/trade/pay`

补缴欠费单（`scene`=RENT/BUYOUT/…）。

**无权限码** · `RentController#pay`

**请求体** [`PayReq`](#payreq)

**出参** `对象（自由键）`

### `GET /mp/user/coupons`

我的券包（**已领到手的券实例**，不含可领模板；后者见下面的 claimable）。

**无权限码** · `MpSupportController#myCoupons`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `page` | `Integer` | 否 |
| 查询 | `size` | `Integer` | 否 |
| 查询 | `status` | `String` | 否 |

**出参** 分页<[`UserCouponVO`](#usercouponvo)>

### `GET /mp/user/coupons/claimable`

领券中心 —— 当前可领的券模板。

**无权限码** · `MpSupportController#claimableCoupons`

**出参** 数组<[`ClaimableCouponVO`](#claimablecouponvo)>

### `POST /mp/user/coupons/{couponNo}/claim`

领券。

**无权限码** · `MpSupportController#claim`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `couponNo` | `String` | 是 |

**出参** [`UserCouponVO`](#usercouponvo)

### `GET /mp/user/cs-session`

我的人工会话。

**无权限码** · `MpSupportController#mySession`

**出参** [`CsSessionVO`](#cssessionvo)

### `GET /mp/user/cs-sessions/{sessionNo}/messages`

会话消息（正序）。

**无权限码** · `MpSupportController#myMessages`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `sessionNo` | `String` | 是 |
| 查询 | `limit` | `Integer` | 否 |

**出参** 数组<[`CsMessageVO`](#csmessagevo)>

### `POST /mp/user/cs-sessions/{sessionNo}/messages`

我在会话里说一句。

**无权限码** · `MpSupportController#sendMessage`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `sessionNo` | `String` | 是 |

**请求体** [`SendMessageReq`](#sendmessagereq)

**出参** [`CsMessageVO`](#csmessagevo)

### `GET /mp/user/favorites`

我的收藏（镜像 c-app `NearbyCabinet[]`）—— 与找柜列表**同一张卡片、同一个出参**。

**无权限码** · `MpNearbyController#myFavorites`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `lat` | `Double` | 否 |
| 查询 | `lng` | `Double` | 否 |

**出参** 数组<[`NearbyCabinetVO`](#nearbycabinetvo)>

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

**请求体** [`UsrInvoiceTitle`](#usrinvoicetitle)

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

**请求体** [`InvoiceApplyReq`](#invoiceapplyreq)

**出参** [`InvoiceItem`](#invoiceitem)

### `GET /mp/user/logoff`

当前注销申请；没有则返回 `null`。

**无权限码** · `MpUserController#currentLogoff`

**出参** [`LogoffItem`](#logoffitem)

### `POST /mp/user/logoff`

提交注销申请；返回冷静期截止时间，期内可撤销。

**无权限码** · `MpUserController#applyLogoff`

**出参** [`LogoffItem`](#logoffitem)

### `POST /mp/user/logoff/cancel`

冷静期内撤销注销。

**无权限码** · `MpUserController#cancelLogoff`

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

### `POST /mp/user/password`

本人改密。

**无权限码** · `MpUserController#changePassword`

**请求体** [`ChangePwdReq`](#changepwdreq)

**出参** `对象（自由键）`

### `GET /mp/user/profile`

我的资料（镜像 c-app `UserProfile`）。

**无权限码** · `MpUserController#profile`

**出参** `对象（自由键）`

### `POST /mp/user/profile`

资料编辑：仅 `nickname`/`avatar`。

**无权限码** · `MpUserController#updateProfile`

**请求体** [`ProfilePatchReq`](#profilepatchreq)

**出参** `对象（自由键）`

### `POST /mp/user/recharge`

按套餐充值（C-WA-02）。

**无权限码** · `MpUserController#recharge`

**请求体** [`RechargeReq`](#rechargereq)

**出参** [`RechargeResultVO`](#rechargeresultvo)

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

21 个端点。

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

### `GET /internal/dev/cabinets/installation-candidate`

**无权限码** · `CabinetQueryInternalController#installationCandidate`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `cabinetNo` | `String` | 是 |
| 查询 | `agentNo` | `String` | 否 |

**出参** [`CabinetBrief`](#cabinetbrief)

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

### `POST /internal/events/device`

**无权限码** · `DeviceEventInternalController#device`

**请求体** [`DeviceEventBatch`](#deviceeventbatch)

**出参** [`IngestResult`](#ingestresult)

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

### `GET /internal/job/declarations`

全部任务声明（调度器接入后由它拉取；现在供运维核对 cron 清单）。

**无权限码** · `InternalJobController#declarations`

**出参** 数组<[`JobDeclaration`](#jobdeclaration)>

### `POST /internal/job/{name}/trigger`

触发一个任务。

**无权限码** · `InternalJobController#trigger`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 路径 | `name` | `String` | 是 |
| 查询 | `type` | `JobInvocation.Type` | 否 |
| 查询 | `bizDate` | `LocalDate` | 否 |

**出参** [`JobResult`](#jobresult)

### `POST /internal/opskit/introspect`

测试用：不展开通配码

**无权限码** · `OpsKitIntrospectController#introspect`

**入参**

| 位置 | 名 | 类型 | 必填 |
|---|---|---|---|
| 查询 | `token` | `String` | 否 |

**出参** `void`

### `POST /internal/opskit/webhook`

**无权限码** · `OpsKitWebhookController#receive`

**请求体** `String`

**出参** `void`

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

**请求体** [`SettlementGenerateReq`](#settlementgeneratereq)

**出参** `List<String>`

### `POST /internal/user/credit/blacklist`

内部拉黑/解除。

权限码 `user:risk:update` · `UserController#blacklist`

**请求体** [`BlacklistReq`](#blacklistreq)

**出参** [`OkResult`](#okresult)


---

## 数据结构

共 437 个。同一结构常被多个端点复用，故在此定义一次、上文引用。

### AcceptReq

| 字段 | 类型 |
|---|---|
| `assigneeNo` | `String` |

### AckResult

| 字段 | 类型 |
|---|---|
| `alarmNo` | `String` |
| `status` | `String` |

### AdCampaignReq

| 字段 | 类型 |
|---|---|
| `adNo` | `String` |
| `advertiserNo` | `String` |
| `advertiser` | `String` |
| `creative` | `String` |
| `budget` | `BigDecimal` |
| `currency` | `String` |
| `targeting` | `String` |
| `startAt` | `String` |
| `endAt` | `String` |

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

### AdSlot

| 字段 | 类型 |
|---|---|
| `slotNo` | `String` |
| `cabinetNo` | `String` |
| `position` | `String` |
| `size` | `String` |
| `status` | `String` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### AdSlotVO

| 字段 | 类型 |
|---|---|
| `slotNo` | `String` |
| `cabinetNo` | `String` |
| `position` | `String` |
| `size` | `String` |
| `status` | `String` |
| `createdAt` | `String` |

### AdjustLine

| 字段 | 类型 |
|---|---|
| `adjNo` | `String` |
| `kind` | `String` |
| `contractNo` | `String` |
| `siteNo` | `String` |
| `period` | `String` |
| `amount` | `BigDecimal` |
| `note` | `String` |

### Adjustment

| 字段 | 类型 |
|---|---|
| `adjNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `kind` | `String` |
| `siteNo` | `String` |
| `contractNo` | `String` |
| `amount` | `BigDecimal` |
| `suggestedAmount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `settleNo` | `String` |
| `source` | `String` |
| `note` | `String` |
| `confirmedBy` | `String` |
| `confirmedAt` | `LocalDateTime` |
| `createdAt` | `LocalDateTime` |
| `period` | `String` |

### Agent

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `name` | `String` |
| `contact` | `String` |
| `regionScope` | `String` |
| `agentType` | `String` |
| `shareRate` | `Double` |
| `cabinetCount` | `Integer` |
| `status` | `String` |
| `archivedAt` | `String` |
| `contactName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `industry` | `String` |
| `defaultSiteNo` | `String` |
| `vatTrn` | `String` |
| `licenseNo` | `String` |

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

### AgentAccountReq

| 字段 | 类型 |
|---|---|
| `accountNo` | `String` |
| `agentNo` | `String` |
| `username` | `String` |
| `displayName` | `String` |
| `loginPhone` | `String` |
| `status` | `String` |

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

### AgentCommissionReq

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `agentNo` | `String` |
| `basis` | `String` |
| `rate` | `BigDecimal` |
| `fixedAmount` | `BigDecimal` |
| `currency` | `String` |
| `mode` | `String` |
| `effectiveAt` | `String` |

### AgentExit

| 字段 | 类型 |
|---|---|
| `exitNo` | `String` |
| `agentNo` | `String` |
| `status` | `String` |
| `reason` | `String` |
| `startedBy` | `String` |
| `startedAt` | `LocalDateTime` |
| `reclaimedAt` | `LocalDateTime` |
| `settledAt` | `LocalDateTime` |
| `closedAt` | `LocalDateTime` |
| `closedBy` | `String` |

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
| `archivedAt` | `String` |
| `business` | [`BusinessCode`](#businesscode) |

### AlarmDetail

| 字段 | 类型 |
|---|---|
| `record` | [`AlarmRecord`](#alarmrecord) |
| `codeName` | `String` |
| `suggestion` | `String` |
| `evidence` | `String` |
| `timeline` | `List<AlarmLogItem>` |
| `recentSameSubject` | `List<AlarmRecord>` |

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
| `closeReason` | `String` |
| `closeNote` | `String` |
| `closedBy` | `String` |
| `closedAt` | `String` |
| `business` | [`BusinessInfo`](#businessinfo) |

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
| `archivedAt` | `String` |

### AlarmSummary

| 字段 | 类型 |
|---|---|
| `byDomain` | `Map<String, DomainCount>` |
| `disposedOpen` | `long` |
| `autoRecoveredToday` | `long` |

### AlarmTodo

| 字段 | 类型 |
|---|---|
| `todoNo` | `String` |
| `alarmNo` | `String` |
| `alarmCode` | `String` |
| `roleCode` | `String` |
| `assigneeNo` | `String` |
| `title` | `String` |
| `status` | `String` |
| `siteNo` | `String` |
| `createdAt` | `LocalDateTime` |
| `doneAt` | `LocalDateTime` |
| `doneBy` | `String` |
| `doneNote` | `String` |

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

### AppVersionReq

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
| `releasedAt` | `String` |

### ApplyResult

| 字段 | 类型 |
|---|---|
| `applyNo` | `String` |
| `status` | `String` |
| `operatorNo` | `String` |
| `initialPassword` | `String` |

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
| `contactName` | `String` |
| `contactPhone` | `String` |
| `contactEmail` | `String` |
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

### ApprovalRequest

| 字段 | 类型 |
|---|---|
| `requestNo` | `String` |
| `kind` | `String` |
| `refNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `reason` | `String` |
| `status` | `String` |
| `applicantName` | `String` |
| `approverName` | `String` |
| `decidedAt` | `LocalDateTime` |
| `decideNote` | `String` |
| `resultRef` | `String` |
| `createdAt` | `LocalDateTime` |

### AssetDiff

| 字段 | 类型 |
|---|---|
| `diffNo` | `String` |
| `sourceType` | `String` |
| `sourceRef` | `String` |
| `kind` | `String` |
| `itemType` | `String` |
| `itemNo` | `String` |
| `siteNo` | `String` |
| `cabinetNo` | `String` |
| `expectedQty` | `Integer` |
| `actualQty` | `Integer` |
| `status` | `String` |
| `resolveNote` | `String` |
| `resolvedBy` | `String` |
| `resolvedAt` | `LocalDateTime` |
| `createdAt` | `LocalDateTime` |

### AssignBatchReq

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `siteNos` | `List<String>` |
| `action` | `String` |
| `operator` | `String` |

### AssignReq

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `targetType` | `String` |
| `targetNo` | `String` |
| `action` | `String` |
| `operator` | `String` |

### AssignableAsset

| 字段 | 类型 |
|---|---|
| `assetType` | `String` |
| `assetNo` | `String` |
| `name` | `String` |
| `currentAgentNo` | `String` |
| `currentAgentName` | `String` |

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

### AttachReq

| 字段 | 类型 |
|---|---|
| `fileNos` | `List<String>` |

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
| `clientCode` | `String` |
| `action` | `String` |
| `outcome` | `String` |
| `traceId` | `String` |
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
| `clientCode` | `String` |
| `action` | `String` |
| `outcome` | `String` |
| `traceId` | `String` |
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
| `archivedAt` | `String` |

### BillingDefaultRule

| 字段 | 类型 |
|---|---|
| `freeMinutes` | `Integer` |
| `unitMinutes` | `Integer` |
| `capDaily` | `BigDecimal` |
| `buyoutPrice` | `BigDecimal` |
| `overdueHours` | `Integer` |

### BindBatchReq

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `locationNo` | `String` |
| `deviceNos` | `List<String>` |
| `openInstallWo` | `Boolean` |
| `expectedAt` | `String` |

### BindReq

| 字段 | 类型 |
|---|---|
| `locationNo` | `String` |
| `openInstallWo` | `Boolean` |
| `expectedAt` | `String` |

### BindResult

| 字段 | 类型 |
|---|---|
| `cabinet` | [`Cabinet`](#cabinet) |
| `woNo` | `String` |

### BizRules

| 字段 | 类型 |
|---|---|
| `withdraw` | [`WithdrawRule`](#withdrawrule) |
| `reservation` | [`ReservationRule`](#reservationrule) |
| `billing` | [`BillingDefaultRule`](#billingdefaultrule) |
| `currency` | `String` |
| `updatedAt` | `String` |

### BlacklistReq

| 字段 | 类型 |
|---|---|
| `cUserNo` | `String` |
| `blacklisted` | `Boolean` |
| `reason` | `String` |

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

### BusinessCode

| 字段 | 类型 |
|---|---|
| `domain` | `String` |
| `subjectType` | `String` |
| `evalType` | `String` |
| `holdMinutes` | `Integer` |
| `windowMinutes` | `Integer` |
| `threshold` | `BigDecimal` |
| `businessHoursOnly` | `boolean` |
| `basePriority` | `String` |
| `impactAdjust` | `boolean` |
| `disposition` | `String` |
| `ownerRole` | `String` |
| `woDelayMinutes` | `Integer` |
| `mergeScope` | `String` |
| `recoverRule` | `String` |
| `recoverHoldMinutes` | `Integer` |
| `supersedes` | `String` |
| `enabled` | `boolean` |
| `builtin` | `boolean` |

### BusinessInfo

| 字段 | 类型 |
|---|---|
| `domain` | `String` |
| `subjectType` | `String` |
| `subjectNo` | `String` |
| `cause` | `String` |
| `priority` | `String` |
| `impactScope` | `String` |
| `impactPeriod` | `String` |
| `siteTier` | `String` |
| `inFlightOrders` | `Integer` |
| `dispositionType` | `String` |
| `dispositionRef` | `String` |
| `firstOccurredAt` | `LocalDateTime` |
| `lastOccurredAt` | `LocalDateTime` |
| `dueAt` | `LocalDateTime` |
| `recoveredAt` | `LocalDateTime` |
| `parentAlarmNo` | `String` |

### CUserInvoiceRow

| 字段 | 类型 |
|---|---|
| `invoiceNo` | `String` |
| `cUserNo` | `String` |
| `nickname` | `String` |
| `titleNo` | `String` |
| `title` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `fileUrl` | `String` |
| `rejectReason` | `String` |
| `handledBy` | `String` |
| `handledAt` | `String` |
| `appliedAt` | `String` |
| `issuedAt` | `String` |

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
| `agentNo` | `String` |
| `archivedAt` | `String` |
| `qcStatus` | `String` |
| `warehouseNo` | `String` |
| `issuedAgentNo` | `String` |
| `issuedAt` | `String` |
| `issuedSiteNo` | `String` |
| `issueReceiverNo` | `String` |
| `issueClaimedAt` | `String` |

### CabinetBrief

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `name` | `String` |
| `agentNo` | `String` |
| `siteNo` | `String` |
| `locationNo` | `String` |

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

### CampaignReq

| 字段 | 类型 |
|---|---|
| `campaignNo` | `String` |
| `name` | `String` |
| `kind` | `String` |
| `rule` | `String` |
| `startAt` | `String` |
| `endAt` | `String` |

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

### ChangePwdReq

| 字段 | 类型 |
|---|---|
| `country` | `String` |
| `oldPassword` | `String` |
| `newPassword` | `String` |

### ChannelBody

| 字段 | 类型 |
|---|---|
| `channelCode` | `String` |
| `channelName` | `String` |
| `channelNameEn` | `String` |
| `channelNameAr` | `String` |
| `mode` | `String` |
| `status` | `String` |
| `apiBase` | `String` |
| `merchantId` | `String` |
| `countries` | `String` |
| `currencies` | `String` |
| `capabilities` | `String` |

### Checklist

| 字段 | 类型 |
|---|---|
| `allPassed` | `boolean` |
| `items` | 数组<[`Item`](#item)> |

### ClaimReq

| 字段 | 类型 |
|---|---|
| `cabinetNos` | `List<String>` |

### ClaimableCouponVO

| 字段 | 类型 |
|---|---|
| `tplNo` | `String` |
| `name` | `String` |
| `type` | `String` |
| `value` | `BigDecimal` |
| `threshold` | `BigDecimal` |
| `currency` | `String` |
| `remaining` | `Integer` |
| `claimed` | `boolean` |

### CloseReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |

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

### CommandReq

| 字段 | 类型 |
|---|---|
| `type` | `String` |
| `params` | `对象（自由键）` |

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

### ConfirmReq

| 字段 | 类型 |
|---|---|
| `amount` | `BigDecimal` |
| `note` | `String` |

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
| `password` | `String` |
| `country` | `String` |
| `jsCode` | `String` |
| `code` | `String` |
| `unionid` | `String` |
| `identityToken` | `String` |
| `idToken` | `String` |
| `nickname` | `String` |
| `avatar` | `String` |
| `countryCode` | `String` |

### ConsumerLoginVO

| 字段 | 类型 |
|---|---|
| `token` | `String` |
| `cUserNo` | `String` |
| `isNew` | `boolean` |
| `tenantNo` | `String` |

### ConsumerOrderVO

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `cUserNo` | `String` |
| `status` | `String` |
| `cabinetNo` | `String` |
| `siteName` | `String` |
| `returnCabinetNo` | `String` |
| `returnSiteName` | `String` |
| `powerbankNo` | `String` |
| `locationName` | `String` |
| `rentStartAt` | `String` |
| `rentEndAt` | `String` |
| `durationMin` | `Integer` |
| `feeAmount` | `BigDecimal` |
| `depositAmount` | `BigDecimal` |
| `currency` | `String` |
| `fees` | 数组<[`FeeItemVO`](#feeitemvo)> |
| `timeline` | 数组<[`OrderStepVO`](#orderstepvo)> |
| `estimatedFee` | `BigDecimal` |
| `estimatedDurationMin` | `Integer` |
| `capped` | `Boolean` |
| `payStatus` | `String` |

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

### ContactReq

| 字段 | 类型 |
|---|---|
| `contactName` | `String` |
| `phone` | `String` |
| `email` | `String` |

### Contract

| 字段 | 类型 |
|---|---|
| `contractNo` | `String` |
| `venueNo` | `String` |
| `siteNo` | `String` |
| `venueName` | `String` |
| `siteName` | `String` |
| `shareRate` | `double` |
| `entryFee` | `double` |
| `startAt` | `String` |
| `endAt` | `String` |
| `status` | `String` |
| `attachments` | `List<ContractAttachment>` |
| `terms` | [`ContractTerms`](#contractterms) |
| `flow` | [`ContractFlow`](#contractflow) |
| `remainingDays` | `Integer` |

### ContractFlow

| 字段 | 类型 |
|---|---|
| `signedAt` | `LocalDate` |
| `submittedBy` | `String` |
| `submittedAt` | `LocalDateTime` |
| `auditedBy` | `String` |
| `auditedAt` | `LocalDateTime` |
| `auditNote` | `String` |
| `activatedAt` | `LocalDateTime` |
| `endedAt` | `LocalDateTime` |
| `endReason` | `String` |
| `prevContractNo` | `String` |
| `sourceLeadNo` | `String` |
| `contractKind` | `String` |
| `parentContractNo` | `String` |
| `auditStage` | `String` |
| `financeAuditedBy` | `String` |
| `financeAuditedAt` | `LocalDateTime` |
| `financeAuditNote` | `String` |
| `termination` | [`TerminationRequest`](#terminationrequest) |

### ContractLogItem

| 字段 | 类型 |
|---|---|
| `event` | `String` |
| `fromStatus` | `String` |
| `toStatus` | `String` |
| `operator` | `String` |
| `note` | `String` |
| `at` | `LocalDateTime` |

### ContractReq

| 字段 | 类型 |
|---|---|
| `venueNo` | `String` |
| `siteNo` | `String` |
| `shareMode` | `String` |
| `shareBase` | `String` |
| `shareRate` | `BigDecimal` |
| `guaranteeAmount` | `BigDecimal` |
| `entryFee` | `BigDecimal` |
| `currency` | `String` |
| `settlePeriod` | `String` |
| `startAt` | `LocalDate` |
| `endAt` | `LocalDate` |
| `autoRenew` | `Boolean` |
| `exclusive` | `Boolean` |
| `deviceQuota` | `Integer` |
| `placementNote` | `String` |
| `depositAmount` | `BigDecimal` |
| `depositTerms` | `String` |
| `signerName` | `String` |
| `remark` | `String` |

### ContractSummary

| 字段 | 类型 |
|---|---|
| `pendingMine` | `long` |
| `pendingCosign` | `long` |
| `terminationPending` | `long` |
| `expiring60` | `long` |
| `expiredNotRenewed` | `long` |
| `missingScan` | `long` |

### ContractTerms

| 字段 | 类型 |
|---|---|
| `shareMode` | `String` |
| `shareBase` | `String` |
| `guaranteeAmount` | `BigDecimal` |
| `currency` | `String` |
| `settlePeriod` | `String` |
| `depositAmount` | `BigDecimal` |
| `depositTerms` | `String` |
| `exclusive` | `Boolean` |
| `deviceQuota` | `Integer` |
| `placementNote` | `String` |
| `autoRenew` | `Boolean` |
| `signerName` | `String` |
| `remark` | `String` |

### CountNoteReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |

### CountReview

| 字段 | 类型 |
|---|---|
| `state` | `String` |
| `approvalNo` | `String` |
| `round` | `int` |
| `submitterNo` | `String` |
| `decision` | `String` |
| `deciderName` | `String` |
| `lastError` | `String` |
| `configured` | `boolean` |

### CountScanReq

| 字段 | 类型 |
|---|---|
| `serialNos` | `List<String>` |

### CouponIssueRecord

| 字段 | 类型 |
|---|---|
| `issueNo` | `String` |
| `couponNo` | `String` |
| `couponName` | `String` |
| `targetType` | `String` |
| `targetDesc` | `String` |
| `quantity` | `Integer` |
| `operatorName` | `String` |
| `createdAt` | `String` |

### CouponIssueReq

| 字段 | 类型 |
|---|---|
| `targetType` | `String` |
| `targetValue` | `String` |
| `quantity` | `Integer` |

### CouponIssueResultVO

| 字段 | 类型 |
|---|---|
| `coupon` | [`CouponTplVO`](#coupontplvo) |
| `record` | [`CouponIssueRecord`](#couponissuerecord) |

### CouponTplReq

| 字段 | 类型 |
|---|---|
| `tplNo` | `String` |
| `name` | `String` |
| `type` | `String` |
| `value` | `BigDecimal` |
| `threshold` | `BigDecimal` |
| `currency` | `String` |
| `validRule` | `String` |
| `stock` | `Integer` |
| `status` | `String` |

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

### CreateReq

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `warehouseNo` | `String` |
| `supplierNo` | `String` |
| `sourceRef` | `String` |
| `arrivedAt` | `String` |
| `note` | `String` |
| `currency` | `String` |
| `unitCosts` | `Map<String, BigDecimal>` |
| `cabinetNos` | `List<String>` |
| `powerbankNos` | `List<String>` |

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
| `photos` | `List<FileRef>` |
| `photoCount` | `Integer` |

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

### DepositBuyoutReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

### DepositDunReq

| 字段 | 类型 |
|---|---|
| `channel` | `String` |
| `note` | `String` |

### DepositFreeReq

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |

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

### DeriveReq

| 字段 | 类型 |
|---|---|
| `type` | `String` |
| `priority` | `String` |
| `cabinetNo` | `String` |
| `description` | `String` |

### DevAlarmCode

| 字段 | 类型 |
|---|---|
| `code` | `String` |
| `message` | `String` |
| `messageEn` | `String` |
| `messageAr` | `String` |
| `level` | `String` |
| `suggestion` | `String` |
| `autoWorkOrder` | `Integer` |
| `archivedAt` | `LocalDateTime` |
| `domain` | `String` |
| `subjectType` | `String` |
| `evalType` | `String` |
| `holdMinutes` | `Integer` |
| `windowMinutes` | `Integer` |
| `threshold` | `BigDecimal` |
| `businessHoursOnly` | `Integer` |
| `basePriority` | `String` |
| `impactAdjust` | `Integer` |
| `disposition` | `String` |
| `ownerRole` | `String` |
| `woDelayMinutes` | `Integer` |
| `mergeScope` | `String` |
| `recoverRule` | `String` |
| `recoverHoldMinutes` | `Integer` |
| `supersedes` | `String` |
| `enabled` | `Integer` |
| `builtin` | `Integer` |
| `id` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### DevAlarmRule

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
| `archivedAt` | `LocalDateTime` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### DevCodeBatch

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
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### DevOtaRelease

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `releaseNo` | `String` |
| `tenantId` | `String` |
| `fwType` | `String` |
| `vendorCode` | `String` |
| `version` | `String` |
| `versionCode` | `Integer` |
| `artifactUrl` | `String` |
| `checksum` | `String` |
| `mandatory` | `Integer` |
| `status` | `String` |
| `releaseNotes` | `String` |
| `createdAt` | `String` |
| `updatedAt` | `String` |
| `lockVersion` | `Long` |
| `deleted` | `Integer` |

### DevOtaRollout

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
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
| `updatedAt` | `String` |

### DeviceAlerts

| 字段 | 类型 |
|---|---|
| `deployed` | `long` |
| `offline` | `long` |
| `noBorrowable` | `long` |
| `offlineSample` | `List<String>` |
| `noBorrowableSample` | `List<String>` |

### DeviceEvent

| 字段 | 类型 |
|---|---|
| `eventId` | `String` |
| `type` | `String` |
| `deviceNo` | `String` |
| `slotIndex` | `Integer` |
| `powerbankNo` | `String` |
| `vendorCode` | `String` |
| `vendorErrorCode` | `String` |
| `occurredAt` | `LocalDateTime` |
| `attrs` | `对象（字符串值）` |

### DeviceEventBatch

| 字段 | 类型 |
|---|---|
| `events` | 数组<[`DeviceEvent`](#deviceevent)> |

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

### DeviceMovement

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `itemType` | `String` |
| `itemNo` | `String` |
| `event` | `String` |
| `fromRef` | `String` |
| `toRef` | `String` |
| `agentBefore` | `String` |
| `agentAfter` | `String` |
| `reason` | `String` |
| `sourceRef` | `String` |
| `operatorNo` | `String` |
| `occurredAt` | `LocalDateTime` |

### DictEntry

| 字段 | 类型 |
|---|---|
| `dictNo` | `String` |
| `group` | `String` |
| `code` | `String` |
| `label` | `String` |
| `sort` | `Integer` |
| `enabled` | `Boolean` |

### Disposal

| 字段 | 类型 |
|---|---|
| `disposalNo` | `String` |
| `kind` | `String` |
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `supplierNo` | `String` |
| `supplierName` | `String` |
| `reasonCode` | `String` |
| `note` | `String` |
| `itemCount` | `int` |
| `createdBy` | `String` |
| `createdAt` | `LocalDateTime` |

### DisposalDetail

| 字段 | 类型 |
|---|---|
| `disposal` | [`Disposal`](#disposal) |
| `items` | 数组<[`DisposalItem`](#disposalitem)> |

### DisposalItem

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `itemNo` | `String` |

### DisposalReq

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `warehouseNo` | `String` |
| `supplierNo` | `String` |
| `reasonCode` | `String` |
| `note` | `String` |
| `cabinetNos` | `List<String>` |
| `powerbankNos` | `List<String>` |
| `carrier` | `String` |
| `trackingNo` | `String` |

### DisposeReq

| 字段 | 类型 |
|---|---|
| `items` | 数组<[`ItemRef`](#itemref)> |
| `disposition` | `String` |
| `note` | `String` |

### DispositionPreview

| 字段 | 类型 |
|---|---|
| `type` | `String` |
| `woType` | `String` |
| `priority` | `String` |
| `assigneeType` | `String` |
| `assigneeNo` | `String` |
| `mergeIntoWoNo` | `String` |
| `todoRole` | `String` |
| `fallback` | `String` |

### Employee

| 字段 | 类型 |
|---|---|
| `employeeNo` | `String` |
| `name` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `deptNo` | `String` |
| `deptName` | `String` |
| `roleNo` | `String` |
| `roleName` | `String` |
| `roleNos` | `List<String>` |
| `status` | `String` |

### EmployeeSaveReq

| 字段 | 类型 |
|---|---|
| `employeeNo` | `String` |
| `name` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `deptNo` | `String` |
| `roleNo` | `String` |
| `roleNos` | `List<String>` |
| `status` | `String` |

### ExceptionHandleReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |
| `action` | `String` |
| `handleResult` | `String` |
| `refundNo` | `String` |
| `workOrderNo` | `String` |

### ExitReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

### FaqItem

| 字段 | 类型 |
|---|---|
| `problemNo` | `String` |
| `category` | `String` |
| `title` | `String` |
| `answer` | `String` |
| `suggestedAction` | `String` |
| `sortNo` | `Integer` |

### FeeItemVO

| 字段 | 类型 |
|---|---|
| `type` | `String` |
| `amount` | `BigDecimal` |

### FileRef

| 字段 | 类型 |
|---|---|
| `fileNo` | `String` |
| `category` | `String` |
| `status` | `String` |
| `originalName` | `String` |
| `contentType` | `String` |
| `sizeBytes` | `long` |
| `previewable` | `boolean` |
| `imageWidth` | `Integer` |
| `imageHeight` | `Integer` |
| `uploadedAt` | `LocalDateTime` |

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

### FunnelStage

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `phase` | `String` |
| `count` | `long` |
| `avgDaysInPhase` | `Double` |

### GeoReady

| 字段 | 类型 |
|---|---|
| `withGeo` | `int` |
| `total` | `int` |

### GrantLoginReq

| 字段 | 类型 |
|---|---|
| `phone` | `String` |
| `email` | `String` |
| `displayName` | `String` |

### GrantLoginResult

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `accountNo` | `String` |

### HandleReq

| 字段 | 类型 |
|---|---|
| `assigneeNo` | `String` |
| `photos` | `String` |
| `handleNote` | `String` |
| `partChanged` | `Boolean` |
| `deviceChanged` | `Boolean` |
| `faultReasonCode` | `String` |
| `fileNos` | `List<String>` |
| `locationNo` | `String` |
| `countedQty` | `Integer` |
| `partCost` | `BigDecimal` |
| `laborCost` | `BigDecimal` |

### HeartbeatRecord

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `beatAt` | `String` |
| `metrics` | `String` |

### IamDept

| 字段 | 类型 |
|---|---|
| `deptNo` | `String` |
| `parentNo` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `leaderNo` | `String` |
| `leaderName` | `String` |
| `path` | `String` |
| `sort` | `Integer` |
| `status` | `String` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### IngestResult

| 字段 | 类型 |
|---|---|
| `accepted` | `int` |
| `duplicated` | `int` |
| `unknownCode` | `int` |

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

### InspectionPlanReq

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `route` | `String` |
| `frequency` | `String` |
| `cron` | `String` |
| `nextAt` | `String` |
| `assignee` | `String` |
| `active` | `Boolean` |

### Intake

| 字段 | 类型 |
|---|---|
| `intakeNo` | `String` |
| `kind` | `String` |
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `sourceRef` | `String` |
| `supplierNo` | `String` |
| `supplierName` | `String` |
| `currency` | `String` |
| `totalCost` | `BigDecimal` |
| `arrivedAt` | `LocalDateTime` |
| `status` | `String` |
| `note` | `String` |
| `total` | `int` |
| `pending` | `int` |
| `passed` | `int` |
| `failed` | `int` |
| `inbound` | `int` |
| `disposed` | `int` |
| `createdBy` | `String` |
| `createdAt` | `LocalDateTime` |

### IntakeDetail

| 字段 | 类型 |
|---|---|
| `intake` | [`Intake`](#intake) |
| `items` | 数组<[`IntakeItem`](#intakeitem)> |

### IntakeItem

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `itemNo` | `String` |
| `unitCost` | `BigDecimal` |
| `sn` | `String` |
| `model` | `String` |
| `qcNo` | `String` |
| `qcResult` | `String` |
| `disposition` | `String` |
| `dispositionNote` | `String` |
| `handledBy` | `String` |
| `handledAt` | `LocalDateTime` |

### InterveneReq

| 字段 | 类型 |
|---|---|
| `action` | `String` |
| `reason` | `String` |
| `amount` | `BigDecimal` |
| `operatorName` | `String` |

### InvTransferReq

| 字段 | 类型 |
|---|---|
| `fromType` | `String` |
| `fromRef` | `String` |
| `fromName` | `String` |
| `toType` | `String` |
| `toRef` | `String` |
| `toName` | `String` |
| `itemType` | `String` |
| `powerbankCount` | `Integer` |
| `status` | `String` |

### InventoryEngine

| 字段 | 类型 |
|---|---|
| `engine` | `String` |
| `configured` | `boolean` |
| `endpointMode` | `String` |
| `reachable` | `Boolean` |
| `failure` | `String` |

### InventoryMigrationPlan

| 字段 | 类型 |
|---|---|
| `configured` | `boolean` |
| `toInbound` | `int` |
| `toIssue` | `int` |
| `skipped` | `int` |
| `done` | `int` |
| `skippedItems` | 数组<[`MigrationCandidate`](#migrationcandidate)> |

### InventoryMigrationResult

| 字段 | 类型 |
|---|---|
| `processed` | `int` |
| `failed` | `int` |
| `remaining` | `int` |
| `failures` | 数组<[`MigrationFailure`](#migrationfailure)> |

### InventoryReport

| 字段 | 类型 |
|---|---|
| `from` | `String` |
| `to` | `String` |
| `stages` | 数组<[`StockStage`](#stockstage)> |
| `rows` | 数组<[`WarehouseRow`](#warehouserow)> |
| `disposals` | `Map<String, Long>` |
| `purchases` | 数组<[`PurchaseRow`](#purchaserow)> |

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

### InvoiceApplyReq

| 字段 | 类型 |
|---|---|
| `titleNo` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |

### InvoiceIssueReq

| 字段 | 类型 |
|---|---|
| `fileUrl` | `String` |

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

### InvoiceRejectReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

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

### InvoiceVoidReq

| 字段 | 类型 |
|---|---|
| `voidReason` | `String` |

### IssueReq

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `siteNo` | `String` |
| `receiverNo` | `String` |
| `cabinetNos` | `List<String>` |
| `carrier` | `String` |
| `trackingNo` | `String` |

### IssuedSummary

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `agentName` | `String` |
| `unbound` | `int` |
| `bound` | `int` |
| `live` | `int` |

### Item

| 字段 | 类型 |
|---|---|
| `key` | `String` |
| `label` | `String` |
| `passed` | `boolean` |
| `detail` | `String` |
| `fixHref` | `String` |

### ItemRef

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `itemNo` | `String` |

### ItemsReq

| 字段 | 类型 |
|---|---|
| `itemNos` | `List<String>` |

### JobDeclaration

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `title` | `String` |
| `ownerModule` | `String` |
| `defaultCron` | `String` |
| `timeoutSec` | `int` |
| `lockAtMostSec` | `int` |
| `manualTrigger` | `boolean` |
| `logEveryRun` | `boolean` |

### JobResult

| 字段 | 类型 |
|---|---|
| `status` | `Status` |
| `detail` | `String` |
| `error` | `String` |

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
| `address` | `String` |
| `venueNo` | `String` |
| `contractNo` | `String` |
| `lostReason` | `String` |
| `lostAt` | `LocalDateTime` |
| `lastFollowAt` | `LocalDateTime` |
| `inPool` | `boolean` |
| `prevOwner` | `String` |
| `competitorName` | `String` |
| `competitorExclusiveUntil` | `LocalDate` |
| `reactivatedAt` | `LocalDateTime` |
| `terms` | [`LeadTerms`](#leadterms) |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |

### LeadConversion

| 字段 | 类型 |
|---|---|
| `leadNo` | `String` |
| `venueNo` | `String` |
| `venueCreated` | `boolean` |
| `siteNo` | `String` |
| `siteCreated` | `boolean` |
| `contractNo` | `String` |

### LeadConvertReq

| 字段 | 类型 |
|---|---|
| `venueNo` | `String` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `regionId` | `String` |
| `address` | `String` |
| `openHours` | `String` |
| `shareMode` | `String` |
| `shareRate` | `BigDecimal` |
| `entryFee` | `BigDecimal` |
| `guaranteeAmount` | `BigDecimal` |
| `startAt` | `LocalDate` |
| `termMonths` | `Integer` |
| `exclusive` | `Boolean` |

### LeadFollowUpReq

| 字段 | 类型 |
|---|---|
| `channel` | `String` |
| `toStage` | `String` |
| `content` | `String` |
| `nextAt` | `String` |

### LeadTerms

| 字段 | 类型 |
|---|---|
| `shareMode` | `String` |
| `shareRate` | `BigDecimal` |
| `entryFee` | `BigDecimal` |
| `guaranteeAmount` | `BigDecimal` |
| `termMonths` | `Integer` |
| `exclusive` | `Boolean` |

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

### LifecycleRow

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `no` | `String` |
| `name` | `String` |
| `phase` | `String` |
| `phaseSince` | `LocalDateTime` |
| `daysInPhase` | `Long` |
| `owner` | `String` |

### LocLead

| 字段 | 类型 |
|---|---|
| `leadNo` | `String` |
| `regionId` | `String` |
| `venueName` | `String` |
| `contact` | `String` |
| `stage` | `String` |
| `owner` | `String` |
| `ownerType` | `String` |
| `siteNo` | `String` |
| `expectSites` | `Integer` |
| `nextFollowAt` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `venueNo` | `String` |
| `contractNo` | `String` |
| `lostReason` | `String` |
| `lostAt` | `LocalDateTime` |
| `lastFollowAt` | `LocalDateTime` |
| `remindAt` | `LocalDateTime` |
| `inPool` | `Integer` |
| `pooledAt` | `LocalDateTime` |
| `prevOwner` | `String` |
| `competitorName` | `String` |
| `competitorExclusiveUntil` | `LocalDate` |
| `reactivatedAt` | `LocalDateTime` |
| `shareMode` | `String` |
| `shareRate` | `BigDecimal` |
| `entryFee` | `BigDecimal` |
| `guaranteeAmount` | `BigDecimal` |
| `termMonths` | `Integer` |
| `exclusiveFlag` | `Boolean` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### LocVenue

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `venueNo` | `String` |
| `tenantId` | `String` |
| `name` | `String` |
| `contact` | `String` |
| `industry` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `createdAt` | `LocalDateTime` |
| `updatedAt` | `LocalDateTime` |
| `version` | `Long` |
| `deleted` | `Integer` |
| `archivedAt` | `LocalDateTime` |

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
| `isDefault` | `Boolean` |
| `agentNo` | `String` |

### LoginReq

| 字段 | 类型 |
|---|---|
| `username` | `String` |
| `password` | `String` |
| `role` | `String` |
| `agentNo` | `String` |
| `phone` | `String` |
| `otp` | `String` |
| `identifier` | `String` |
| `realm` | `String` |

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
| `mustChange` | `boolean` |

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

### LostVerdictReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |

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

### Material

| 字段 | 类型 |
|---|---|
| `materialNo` | `String` |
| `name` | `String` |
| `category` | `String` |
| `trackingMode` | `String` |
| `uom` | `String` |
| `spec` | `String` |
| `vendorCode` | `String` |
| `defaultSupplierNo` | `String` |
| `builtin` | `boolean` |
| `hasMovements` | `boolean` |
| `archivedAt` | `LocalDateTime` |
| `createdAt` | `LocalDateTime` |

### MaterialBalance

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `materialNo` | `String` |
| `materialName` | `String` |
| `category` | `String` |
| `trackingMode` | `String` |
| `uom` | `String` |
| `onHand` | `int` |
| `minQty` | `int` |
| `low` | `boolean` |
| `serialNos` | `List<String>` |

### MaterialDoc

| 字段 | 类型 |
|---|---|
| `docNo` | `String` |
| `kind` | `String` |
| `purpose` | `String` |
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `toWarehouseNo` | `String` |
| `toWarehouseName` | `String` |
| `supplierNo` | `String` |
| `supplierName` | `String` |
| `reasonCode` | `String` |
| `receiverNo` | `String` |
| `woNo` | `String` |
| `currency` | `String` |
| `note` | `String` |
| `totalQty` | `int` |
| `lines` | 数组<[`MaterialDocLine`](#materialdocline)> |
| `createdBy` | `String` |
| `createdAt` | `LocalDateTime` |

### MaterialDocLine

| 字段 | 类型 |
|---|---|
| `materialNo` | `String` |
| `materialName` | `String` |
| `qty` | `Integer` |
| `serialNos` | `List<String>` |
| `unitCost` | `BigDecimal` |

### MaterialDocReq

| 字段 | 类型 |
|---|---|
| `purpose` | `String` |
| `warehouseNo` | `String` |
| `toWarehouseNo` | `String` |
| `supplierNo` | `String` |
| `reasonCode` | `String` |
| `receiverNo` | `String` |
| `woNo` | `String` |
| `currency` | `String` |
| `note` | `String` |
| `lines` | 数组<[`MaterialDocLine`](#materialdocline)> |
| `requestKey` | `String` |

### MaterialMinQtyReq

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `materialNo` | `String` |
| `minQty` | `Integer` |

### MaterialReq

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `category` | `String` |
| `trackingMode` | `String` |
| `uom` | `String` |
| `spec` | `String` |
| `vendorCode` | `String` |
| `defaultSupplierNo` | `String` |

### MdBank

| 字段 | 类型 |
|---|---|
| `bankCode` | `String` |
| `bankName` | `String` |
| `bankNameEn` | `String` |
| `bankNameAr` | `String` |
| `country` | `String` |
| `currency` | `String` |
| `swiftPrefix` | `String` |
| `ibanLength` | `Integer` |
| `status` | `String` |
| `archivedAt` | `LocalDateTime` |
| `id` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### MdMarketCountry

| 字段 | 类型 |
|---|---|
| `countryCode` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `currency` | `String` |
| `timezone` | `String` |
| `compliance` | `String` |
| `status` | `String` |
| `id` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### MdProblem

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
| `archivedAt` | `LocalDateTime` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### MdRegion

| 字段 | 类型 |
|---|---|
| `regionId` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `parentId` | `String` |
| `level` | `Integer` |
| `cityCount` | `Integer` |
| `id` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

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

### MemberPlanReq

| 字段 | 类型 |
|---|---|
| `planNo` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `cardType` | `String` |
| `price` | `BigDecimal` |
| `currency` | `String` |
| `periodDays` | `Integer` |
| `timesTotal` | `Integer` |
| `rights` | [`PlanRightsVO`](#planrightsvo) |
| `autoRenew` | `Boolean` |
| `sortNo` | `Integer` |

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
| `currency` | `String` |
| `cardType` | `String` |
| `rights` | [`PlanRightsVO`](#planrightsvo) |
| `active` | `Boolean` |
| `expireAt` | `String` |

### MenuNode

| 字段 | 类型 |
|---|---|
| `menuNo` | `String` |
| `parentNo` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `type` | `String` |
| `path` | `String` |
| `icon` | `String` |
| `group` | `String` |
| `sort` | `Integer` |
| `perm` | `String` |
| `phase` | `Integer` |
| `ready` | `boolean` |
| `module` | `String` |
| `modules` | `List<String>` |
| `match` | `List<String>` |
| `pinBottom` | `boolean` |
| `portalFor` | `List<String>` |
| `children` | 数组<[`MenuNode`](#menunode)> |

### MenuPatch

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `nameEn` | `String` |
| `nameAr` | `String` |
| `groupName` | `String` |
| `sort` | `Integer` |
| `visible` | `Integer` |
| `perm` | `String` |

### MerchantProfile

| 字段 | 类型 |
|---|---|
| `agentNo` | `String` |
| `name` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `industry` | `String` |
| `contactName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `defaultSiteNo` | `String` |
| `status` | `String` |

### MessageItem

| 字段 | 类型 |
|---|---|
| `messageNo` | `String` |
| `type` | `String` |
| `title` | `String` |
| `titleEn` | `String` |
| `titleAr` | `String` |
| `body` | `String` |
| `bodyEn` | `String` |
| `bodyAr` | `String` |
| `read` | `boolean` |
| `readAt` | `String` |
| `createdAt` | `String` |

### MigrationCandidate

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `itemNo` | `String` |
| `kind` | `String` |
| `warehouseNo` | `String` |
| `agentNo` | `String` |
| `siteNo` | `String` |
| `claimed` | `boolean` |
| `reason` | `String` |

### MigrationFailure

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `itemNo` | `String` |
| `code` | `String` |
| `detail` | `String` |

### MigrationRunReq

| 字段 | 类型 |
|---|---|
| `limit` | `Integer` |

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

### NearbyCabinetVO

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `siteNo` | `String` |
| `siteName` | `String` |
| `address` | `String` |
| `distanceM` | `Integer` |
| `lat` | `BigDecimal` |
| `lng` | `BigDecimal` |
| `availableBorrow` | `int` |
| `availableReturn` | `int` |
| `pricePerHour` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |

### NoteReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |

### NoticeReq

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
| `pinned` | `Boolean` |
| `startAt` | `String` |
| `endAt` | `String` |

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

### NotifyBlacklist

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
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

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

### NotifyTemplate

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
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### NotifyTemplatePreviewReq

| 字段 | 类型 |
|---|---|
| `vars` | `对象（字符串值）` |

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

### OpenApiAppReq

| 字段 | 类型 |
|---|---|
| `appNo` | `String` |
| `name` | `String` |
| `appKey` | `String` |
| `scopes` | `String` |
| `rateLimit` | `Integer` |
| `status` | `String` |

### OpenCountReq

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |

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

### OpsFlowMetrics

| 字段 | 类型 |
|---|---|
| `from` | `LocalDate` |
| `to` | `LocalDate` |
| `woWithSla` | `long` |
| `woSlaRate` | `BigDecimal` |
| `faultResolved` | `long` |
| `mttrMinutes` | `Long` |
| `contractsEnded` | `long` |
| `contractsRenewed` | `long` |
| `renewalRate` | `BigDecimal` |
| `expiredNotRenewedRatio` | `BigDecimal` |
| `sitesWentLive` | `long` |
| `installLeadDaysAvg` | `BigDecimal` |
| `firstTrials` | `long` |
| `firstTrialPassRate` | `BigDecimal` |
| `leadsCreated` | `long` |
| `leadsSigned` | `long` |
| `leadConversionRate` | `BigDecimal` |
| `signingCycleDaysAvg` | `BigDecimal` |
| `removalCounted` | `long` |
| `removalExpected` | `long` |
| `removalRecoveryRate` | `BigDecimal` |

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

### OrderEvent

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `fromStatus` | `String` |
| `toStatus` | `String` |
| `event` | `String` |
| `operator` | `String` |
| `createdAt` | `String` |

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

### OrderStepVO

| 字段 | 类型 |
|---|---|
| `status` | `String` |
| `at` | `String` |

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

### OtpReq

| 字段 | 类型 |
|---|---|
| `countryCode` | `String` |
| `phone` | `String` |
| `scene` | `String` |

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
| `sitePreparing` | `int` |
| `siteWithdrawing` | `int` |
| `siteClosed` | `int` |

### ParamOption

| 字段 | 类型 |
|---|---|
| `value` | `String` |
| `label` | `String` |

### PauseReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |
| `pauseUntil` | `LocalDate` |

### PayReceiptReq

| 字段 | 类型 |
|---|---|
| `success` | `Boolean` |
| `channel` | `String` |
| `payRef` | `String` |
| `failReason` | `String` |

### PayReq

| 字段 | 类型 |
|---|---|
| `orderNo` | `String` |
| `scene` | `String` |

### PayableRow

| 字段 | 类型 |
|---|---|
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `period` | `String` |
| `currency` | `String` |
| `earned` | `BigDecimal` |
| `settled` | `BigDecimal` |
| `paid` | `BigDecimal` |
| `outstanding` | `BigDecimal` |

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

### PayoutConfirmItem

| 字段 | 类型 |
|---|---|
| `withdrawNo` | `String` |
| `success` | `Boolean` |
| `payRef` | `String` |
| `failReason` | `String` |

### PayoutConfirmReq

| 字段 | 类型 |
|---|---|
| `bankBatchRef` | `String` |
| `transferDate` | `String` |
| `results` | 数组<[`PayoutConfirmItem`](#payoutconfirmitem)> |

### PayoutPreview

| 字段 | 类型 |
|---|---|
| `cutoffDate` | `String` |
| `currency` | `String` |
| `payableCount` | `int` |
| `payableTotal` | `BigDecimal` |
| `items` | 数组<[`PayoutPreviewItem`](#payoutpreviewitem)> |

### PayoutPreviewItem

| 字段 | 类型 |
|---|---|
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `settleNos` | `List<String>` |
| `periods` | `List<String>` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `accountMasked` | `String` |
| `blockedReason` | `String` |

### PayoutTask

| 字段 | 类型 |
|---|---|
| `taskNo` | `String` |
| `cutoffDate` | `String` |
| `currency` | `String` |
| `itemCount` | `int` |
| `totalAmount` | `BigDecimal` |
| `status` | `String` |
| `createdBy` | `String` |
| `createdName` | `String` |
| `createdAt` | `String` |
| `reviewedName` | `String` |
| `reviewedAt` | `String` |
| `rejectReason` | `String` |
| `bankBatchRef` | `String` |
| `transferDate` | `String` |
| `confirmedAt` | `String` |
| `exportCount` | `int` |
| `items` | 数组<[`PayoutTaskItem`](#payouttaskitem)> |

### PayoutTaskAuditReq

| 字段 | 类型 |
|---|---|
| `approve` | `Boolean` |
| `rejectReason` | `String` |

### PayoutTaskCreateReq

| 字段 | 类型 |
|---|---|
| `cutoffDate` | `String` |

### PayoutTaskItem

| 字段 | 类型 |
|---|---|
| `seq` | `int` |
| `withdrawNo` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `amount` | `BigDecimal` |
| `currency` | `String` |
| `accountName` | `String` |
| `accountMasked` | `String` |
| `status` | `String` |
| `settleNos` | `List<String>` |
| `payRef` | `String` |
| `paidAt` | `String` |
| `failReason` | `String` |

### PlanRightsVO

| 字段 | 类型 |
|---|---|
| `rentDiscount` | `BigDecimal` |
| `freeMinutes` | `Integer` |
| `depositFree` | `Boolean` |

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

### PoolReturnReq

| 字段 | 类型 |
|---|---|
| `cabinetNos` | `List<String>` |
| `warehouseNo` | `String` |
| `reason` | `String` |

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
| `archivedAt` | `String` |
| `suspectedLostAt` | `String` |
| `qcStatus` | `String` |
| `warehouseNo` | `String` |

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
| `lostFee` | `BigDecimal` |
| `unitRounding` | `String` |

### PricePlanReq

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
| `deviceType` | `String` |
| `lostFee` | `BigDecimal` |
| `unitRounding` | `String` |

### PricingSchedule

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `name` | `String` |
| `period` | `String` |
| `days` | `String` |
| `timeFrom` | `String` |
| `timeTo` | `String` |
| `expr` | `String` |
| `multiplier` | `BigDecimal` |
| `active` | `boolean` |

### PricingScheduleReq

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `regionId` | `String` |
| `name` | `String` |
| `period` | `String` |
| `days` | `String` |
| `timeFrom` | `String` |
| `timeTo` | `String` |
| `expr` | `String` |
| `multiplier` | `BigDecimal` |
| `active` | `Boolean` |

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
| `archivedAt` | `String` |

### ProfilePatchReq

| 字段 | 类型 |
|---|---|
| `nickname` | `String` |
| `avatar` | `String` |
| `email` | `String` |

### Protection

| 字段 | 类型 |
|---|---|
| `protectionNo` | `String` |
| `cabinetNo` | `String` |
| `slotIndex` | `Integer` |
| `action` | `String` |
| `holderType` | `String` |
| `holderRef` | `String` |
| `reason` | `String` |
| `active` | `boolean` |
| `createdAt` | `LocalDateTime` |
| `releasedAt` | `LocalDateTime` |
| `releaseReason` | `String` |

### ProtectionReq

| 字段 | 类型 |
|---|---|
| `action` | `String` |
| `slotIndex` | `Integer` |
| `reason` | `String` |

### PurchaseRow

| 字段 | 类型 |
|---|---|
| `currency` | `String` |
| `qty` | `long` |
| `amount` | `BigDecimal` |

### PushFinishReq

| 字段 | 类型 |
|---|---|
| `targetCount` | `Integer` |
| `successCount` | `Integer` |

### PushMessageVO

| 字段 | 类型 |
|---|---|
| `pushNo` | `String` |
| `title` | `String` |
| `content` | `String` |
| `channel` | `String` |
| `audience` | `String` |
| `audienceType` | `String` |
| `audienceValue` | `String` |
| `targetCount` | `Integer` |
| `successCount` | `Integer` |
| `sentCount` | `Integer` |
| `status` | `String` |
| `scheduledAt` | `String` |
| `sentAt` | `String` |
| `operatorName` | `String` |
| `idempotencyKey` | `String` |

### PushReq

| 字段 | 类型 |
|---|---|
| `pushNo` | `String` |
| `title` | `String` |
| `content` | `String` |
| `channel` | `String` |
| `audience` | `String` |
| `audienceType` | `String` |
| `audienceValue` | `String` |
| `scheduledAt` | `String` |

### PushSendReq

| 字段 | 类型 |
|---|---|
| `idempotencyKey` | `String` |
| `scheduledAt` | `String` |

### PushSweepReq

| 字段 | 类型 |
|---|---|
| `now` | `String` |

### QcRecord

| 字段 | 类型 |
|---|---|
| `qcNo` | `String` |
| `itemType` | `String` |
| `itemNo` | `String` |
| `powerOn` | `Boolean` |
| `slotsOk` | `Boolean` |
| `battery` | `Integer` |
| `cycles` | `Integer` |
| `result` | `String` |
| `note` | `String` |
| `inspectedBy` | `String` |
| `inspectedAt` | `LocalDateTime` |
| `ruleSnapshot` | `String` |
| `intakeNo` | `String` |
| `consumedBy` | `String` |

### QcReq

| 字段 | 类型 |
|---|---|
| `powerOn` | `Boolean` |
| `slotsOk` | `Boolean` |
| `battery` | `Integer` |
| `cycles` | `Integer` |
| `result` | `String` |
| `note` | `String` |

### ReasonReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

### ReceiveReq

| 字段 | 类型 |
|---|---|
| `receivedNos` | `List<String>` |
| `note` | `String` |

### ReceiveResult

| 字段 | 类型 |
|---|---|
| `transferNo` | `String` |
| `status` | `String` |
| `received` | `int` |
| `missing` | `List<String>` |
| `extra` | `List<String>` |
| `diffs` | 数组<[`AssetDiff`](#assetdiff)> |

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

### RechargePackageReq

| 字段 | 类型 |
|---|---|
| `packageNo` | `String` |
| `regionId` | `String` |
| `name` | `String` |
| `payAmount` | `BigDecimal` |
| `giftAmount` | `BigDecimal` |
| `currency` | `String` |
| `validDays` | `Integer` |
| `sortNo` | `Integer` |
| `status` | `String` |
| `markets` | `String` |

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

### RechargeReq

| 字段 | 类型 |
|---|---|
| `packageNo` | `String` |

### RechargeResultVO

| 字段 | 类型 |
|---|---|
| `rechargeNo` | `String` |
| `packageNo` | `String` |
| `payAmount` | `BigDecimal` |
| `giftAmount` | `BigDecimal` |
| `creditAmount` | `BigDecimal` |
| `currency` | `String` |
| `status` | `String` |
| `balance` | `BigDecimal` |
| `bonus` | `BigDecimal` |

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
| `handleResult` | `String` |
| `handleNote` | `String` |
| `handledBy` | `String` |
| `handledAt` | `String` |

### ReconResolveReq

| 字段 | 类型 |
|---|---|
| `diffId` | `Long` |
| `action` | `String` |
| `handleNote` | `String` |

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

### RedirectReq

| 字段 | 类型 |
|---|---|
| `cabinetNos` | `List<String>` |
| `agentNo` | `String` |
| `siteNo` | `String` |
| `receiverNo` | `String` |
| `reason` | `String` |

### ReferralRuleVO

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `name` | `String` |
| `rewardTo` | `String` |
| `rewardAmount` | `BigDecimal` |
| `currency` | `String` |
| `trigger` | `String` |
| `maxPerInviter` | `Integer` |
| `startAt` | `String` |
| `endAt` | `String` |
| `status` | `String` |

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
| `pspTxnNo` | `String` |
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

### RegisterReq

| 字段 | 类型 |
|---|---|
| `countryCode` | `String` |
| `country` | `String` |
| `phone` | `String` |
| `otp` | `String` |
| `nickname` | `String` |
| `password` | `String` |

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
| `couponAmount` | `BigDecimal` |
| `memberDiscountAmount` | `BigDecimal` |
| `payStatus` | `String` |
| `agentNo` | `String` |
| `agentName` | `String` |
| `siteNo` | `String` |
| `maxCharge` | `BigDecimal` |
| `liveDurationMin` | `Integer` |
| `accruedFee` | `BigDecimal` |
| `payNo` | `String` |
| `payChannel` | `String` |

### RentReq

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `useFreeDeposit` | `Boolean` |
| `couponNo` | `String` |

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
| `fileNos` | `List<String>` |

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

### ResetPwdReq

| 字段 | 类型 |
|---|---|
| `countryCode` | `String` |
| `country` | `String` |
| `phone` | `String` |
| `otp` | `String` |
| `password` | `String` |

### ResolveReq

| 字段 | 类型 |
|---|---|
| `note` | `String` |

### RevenueByDevice

| 字段 | 类型 |
|---|---|
| `cabinetNo` | `String` |
| `orderCount` | `int` |
| `gross` | `BigDecimal` |

### RevenueDay

| 字段 | 类型 |
|---|---|
| `date` | `String` |
| `orderCount` | `int` |
| `gross` | `BigDecimal` |

### RevenueSummary

| 字段 | 类型 |
|---|---|
| `from` | `String` |
| `to` | `String` |
| `orderCount` | `int` |
| `gross` | `BigDecimal` |
| `refund` | `BigDecimal` |
| `net` | `BigDecimal` |
| `preTax` | `BigDecimal` |
| `myShare` | `BigDecimal` |
| `pending` | `BigDecimal` |
| `settledUnpaid` | `BigDecimal` |
| `paid` | `BigDecimal` |
| `prevOrderCount` | `int` |
| `prevGross` | `BigDecimal` |
| `prevMyShare` | `BigDecimal` |

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

### SelfBillIssueReq

| 字段 | 类型 |
|---|---|
| `withdrawNo` | `String` |

### SelfBillView

| 字段 | 类型 |
|---|---|
| `billNo` | `String` |
| `withdrawNo` | `String` |
| `agentNo` | `String` |
| `supplierName` | `String` |
| `supplierAddress` | `String` |
| `supplierTrn` | `String` |
| `recipientName` | `String` |
| `recipientTrn` | `String` |
| `taxRatePercent` | `BigDecimal` |
| `netAmount` | `BigDecimal` |
| `taxAmount` | `BigDecimal` |
| `grossAmount` | `BigDecimal` |
| `currency` | `String` |
| `payRef` | `String` |
| `issuedAt` | `LocalDateTime` |

### SendMessageReq

| 字段 | 类型 |
|---|---|
| `content` | `String` |

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

### SettlementGenerateReq

| 字段 | 类型 |
|---|---|
| `period` | `String` |
| `payeeType` | `String` |
| `payeeNos` | `List<String>` |

### SettlementView

| 字段 | 类型 |
|---|---|
| `settlement` | [`Settlement`](#settlement) |
| `details` | 数组<[`SettlementDetail`](#settlementdetail)> |

### ShareLine

| 字段 | 类型 |
|---|---|
| `contractNo` | `String` |
| `rate` | `BigDecimal` |
| `orders` | `long` |
| `gross` | `BigDecimal` |
| `amount` | `BigDecimal` |

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
| `baseMode` | `String` |
| `taxRate` | `BigDecimal` |
| `baseAmount` | `BigDecimal` |
| `refundNo` | `String` |

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

### ShareRuleReq

| 字段 | 类型 |
|---|---|
| `ruleNo` | `String` |
| `dimension` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `basis` | `String` |
| `mode` | `String` |
| `rate` | `BigDecimal` |
| `formula` | `String` |
| `priority` | `Integer` |
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

### ShipInfo

| 字段 | 类型 |
|---|---|
| `carrier` | `String` |
| `trackingNo` | `String` |
| `shippedAt` | `String` |
| `note` | `String` |

### SignReq

| 字段 | 类型 |
|---|---|
| `signedAt` | `LocalDate` |
| `fileNos` | `List<String>` |

### SignalCode

| 字段 | 类型 |
|---|---|
| `code` | `String` |
| `name` | `String` |
| `nameEn` | `String` |
| `category` | `String` |
| `scope` | `String` |
| `protectiveAction` | `String` |
| `clearsCode` | `String` |
| `feeds` | `String` |

### SignedUrl

| 字段 | 类型 |
|---|---|
| `url` | `String` |
| `expiresAt` | `LocalDateTime` |

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
| `archivedAt` | `String` |
| `nameAr` | `String` |
| `openHours` | `String` |
| `ops` | [`SiteOps`](#siteops) |
| `opsEmployeeNo` | `String` |
| `defaultLocationNo` | `String` |

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
| `status` | `String` |
| `openHours` | `String` |
| `operateAgentNo` | `String` |
| `opsEmployeeNo` | `String` |
| `agentNo` | `String` |
| `surveyPassed` | `Boolean` |
| `firstLiveOn` | `LocalDate` |

### SiteOps

| 字段 | 类型 |
|---|---|
| `opsEmployeeNo` | `String` |
| `operateAgentNo` | `String` |
| `firstLiveAt` | `LocalDateTime` |
| `pauseReason` | `String` |
| `pauseUntil` | `LocalDate` |
| `withdrawReason` | `String` |
| `withdrawPlannedAt` | `LocalDate` |
| `closedAt` | `LocalDateTime` |
| `activeContractNo` | `String` |

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

### SiteReq

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `nameAr` | `String` |
| `venueNo` | `String` |
| `brandNo` | `String` |
| `regionId` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |
| `sceneType` | `String` |
| `openHours` | `String` |
| `opsEmployeeNo` | `String` |
| `agentNo` | `String` |

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

### SiteStatusLogItem

| 字段 | 类型 |
|---|---|
| `event` | `String` |
| `fromStatus` | `String` |
| `toStatus` | `String` |
| `operator` | `String` |
| `reason` | `String` |
| `at` | `LocalDateTime` |

### SiteSummary

| 字段 | 类型 |
|---|---|
| `preparing` | `long` |
| `active` | `long` |
| `paused` | `long` |
| `withdrawing` | `long` |
| `closed` | `long` |
| `missingOwner` | `long` |
| `missingOpenHours` | `long` |

### SiteSurvey

| 字段 | 类型 |
|---|---|
| `surveyNo` | `String` |
| `siteNo` | `String` |
| `signalLevel` | `String` |
| `powerOk` | `boolean` |
| `placementNote` | `String` |
| `fileNos` | `List<String>` |
| `result` | `String` |
| `note` | `String` |
| `surveyedBy` | `String` |
| `surveyedAt` | `LocalDateTime` |

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

### StageItems

| 字段 | 类型 |
|---|---|
| `stage` | `String` |
| `itemType` | `String` |
| `total` | `long` |
| `items` | `List<StageItem>` |

### Statement

| 字段 | 类型 |
|---|---|
| `settleNo` | `String` |
| `payeeType` | `String` |
| `payeeNo` | `String` |
| `payeeName` | `String` |
| `period` | `String` |
| `currency` | `String` |
| `status` | `String` |
| `orderCount` | `long` |
| `grossTotal` | `BigDecimal` |
| `shareTotal` | `BigDecimal` |
| `shares` | 数组<[`ShareLine`](#shareline)> |
| `adjustTotal` | `BigDecimal` |
| `adjustments` | 数组<[`AdjustLine`](#adjustline)> |
| `total` | `BigDecimal` |

### StockCount

| 字段 | 类型 |
|---|---|
| `countNo` | `String` |
| `warehouseNo` | `String` |
| `status` | `String` |
| `openedAt` | `LocalDateTime` |

### StockCountDetail

| 字段 | 类型 |
|---|---|
| `count` | [`StockCount`](#stockcount) |
| `lines` | 数组<[`StockCountLine`](#stockcountline)> |
| `exceptions` | 数组<[`StockCountScan`](#stockcountscan)> |
| `missingSerials` | `List<String>` |
| `review` | [`CountReview`](#countreview) |

### StockCountLine

| 字段 | 类型 |
|---|---|
| `itemType` | `String` |
| `bookQty` | `int` |
| `countedQty` | `Integer` |
| `diffQty` | `Integer` |

### StockCountScan

| 字段 | 类型 |
|---|---|
| `serialNo` | `String` |
| `verdict` | `String` |

### StockLedgerRow

| 字段 | 类型 |
|---|---|
| `id` | `String` |
| `warehouseNo` | `String` |
| `itemType` | `String` |
| `itemNo` | `String` |
| `docKind` | `String` |
| `docNo` | `String` |
| `qtyDelta` | `int` |
| `occurredAt` | `LocalDateTime` |
| `operator` | `String` |

### StockStage

| 字段 | 类型 |
|---|---|
| `stage` | `String` |
| `cabinets` | `long` |
| `powerbanks` | `long` |

### StockThreshold

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `itemType` | `String` |
| `minQty` | `int` |
| `available` | `int` |
| `low` | `boolean` |

### StockThresholdReq

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `itemType` | `String` |
| `minQty` | `Integer` |

### SubmitReq

| 字段 | 类型 |
|---|---|
| `phone` | `String` |
| `email` | `String` |
| `otp` | `String` |
| `operatorName` | `String` |
| `operatorType` | `String` |
| `contactName` | `String` |
| `regionScope` | `String` |
| `shareRate` | `BigDecimal` |
| `payload` | `String` |

### SupplementReq

| 字段 | 类型 |
|---|---|
| `startAt` | `LocalDate` |

### Supplier

| 字段 | 类型 |
|---|---|
| `supplierNo` | `String` |
| `name` | `String` |
| `vendorCode` | `String` |
| `contactName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `address` | `String` |
| `note` | `String` |
| `archivedAt` | `String` |

### SupplierReq

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `vendorCode` | `String` |
| `contactName` | `String` |
| `phone` | `String` |
| `email` | `String` |
| `address` | `String` |
| `note` | `String` |

### SurveyReq

| 字段 | 类型 |
|---|---|
| `signalLevel` | `String` |
| `powerOk` | `Boolean` |
| `placementNote` | `String` |
| `fileNos` | `List<String>` |
| `result` | `String` |
| `note` | `String` |

### SysLoginSetting

| 字段 | 类型 |
|---|---|
| `country` | `String` |
| `countryName` | `String` |
| `otpEnabled` | `Integer` |
| `passwordEnabled` | `Integer` |
| `appleEnabled` | `Integer` |
| `googleEnabled` | `Integer` |
| `otpExpireSec` | `Integer` |
| `otpDailyLimit` | `Integer` |
| `forceRealName` | `Integer` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### SysParam

| 字段 | 类型 |
|---|---|
| `paramKey` | `String` |
| `label` | `String` |
| `value` | `String` |
| `groupName` | `String` |
| `valueType` | `String` |
| `valueOptions` | `String` |
| `minValue` | `BigDecimal` |
| `maxValue` | `BigDecimal` |
| `pattern` | `String` |
| `nullable` | `Integer` |
| `hint` | `String` |
| `pendingDecision` | `Integer` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### SysParamEntry

| 字段 | 类型 |
|---|---|
| `paramKey` | `String` |
| `label` | `String` |
| `value` | `String` |
| `groupName` | `String` |
| `updatedAt` | `String` |
| `valueType` | `String` |
| `options` | 数组<[`ParamOption`](#paramoption)> |
| `minValue` | `BigDecimal` |
| `maxValue` | `BigDecimal` |
| `pattern` | `String` |
| `nullable` | `Boolean` |
| `hint` | `String` |
| `pendingDecision` | `Boolean` |

### SysTaxSetting

| 字段 | 类型 |
|---|---|
| `country` | `String` |
| `countryName` | `String` |
| `taxName` | `String` |
| `ratePercent` | `BigDecimal` |
| `trn` | `String` |
| `invoiceTitle` | `String` |
| `issuerLegalName` | `String` |
| `issuerAddress` | `String` |
| `includedInPrice` | `Integer` |
| `effectiveFrom` | `String` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### TakeoverReq

| 字段 | 类型 |
|---|---|
| `employeeNo` | `String` |
| `reason` | `String` |

### TaxInvoiceView

| 字段 | 类型 |
|---|---|
| `invoiceNo` | `String` |
| `kind` | `String` |
| `refInvoiceNo` | `String` |
| `orderNo` | `String` |
| `cUserNo` | `String` |
| `payNo` | `String` |
| `refundNo` | `String` |
| `issuerCountry` | `String` |
| `issuerLegalName` | `String` |
| `issuerAddress` | `String` |
| `issuerTrn` | `String` |
| `issuerTitle` | `String` |
| `taxRatePercent` | `BigDecimal` |
| `taxIncluded` | `boolean` |
| `netAmount` | `BigDecimal` |
| `taxAmount` | `BigDecimal` |
| `grossAmount` | `BigDecimal` |
| `currency` | `String` |
| `lineDesc` | `String` |
| `status` | `String` |
| `failReason` | `String` |
| `issuedAt` | `LocalDateTime` |
| `createdAt` | `LocalDateTime` |

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
| `issuerLegalName` | `String` |
| `issuerAddress` | `String` |

### TerminateReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |
| `effectiveAt` | `LocalDate` |

### TerminationRequest

| 字段 | 类型 |
|---|---|
| `status` | `String` |
| `reason` | `String` |
| `effectiveAt` | `LocalDate` |
| `requestedBy` | `String` |
| `requestedAt` | `LocalDateTime` |
| `auditedBy` | `String` |
| `auditedAt` | `LocalDateTime` |
| `auditNote` | `String` |

### TicketCreateReq

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

### TicketUpdateReq

| 字段 | 类型 |
|---|---|
| `status` | `String` |
| `handlerNo` | `String` |
| `issue` | `String` |

### TimelineItem

| 字段 | 类型 |
|---|---|
| `kind` | `String` |
| `action` | `String` |
| `actor` | `String` |
| `note` | `String` |
| `faultReasonCode` | `String` |
| `fileNos` | `List<String>` |
| `at` | `String` |

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

### TrialRent

| 字段 | 类型 |
|---|---|
| `trialNo` | `String` |
| `cabinetNo` | `String` |
| `slotIndex` | `Integer` |
| `powerbankNo` | `String` |
| `status` | `String` |
| `ejectedAt` | `LocalDateTime` |
| `returnedAt` | `LocalDateTime` |
| `failReason` | `String` |
| `operator` | `String` |
| `createdAt` | `LocalDateTime` |

### UnbindReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

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

### UsrFreeWhitelist

| 字段 | 类型 |
|---|---|
| `whitelistNo` | `String` |
| `regionId` | `String` |
| `cUserNo` | `String` |
| `reason` | `String` |
| `quotaType` | `String` |
| `quotaValue` | `BigDecimal` |
| `usedValue` | `BigDecimal` |
| `currency` | `String` |
| `validFrom` | `String` |
| `validTo` | `String` |
| `grantedBy` | `String` |
| `status` | `String` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### UsrInvoiceTitle

| 字段 | 类型 |
|---|---|
| `titleNo` | `String` |
| `cUserNo` | `String` |
| `type` | `String` |
| `title` | `String` |
| `vatTrn` | `String` |
| `isDefault` | `Integer` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### UsrMembership

| 字段 | 类型 |
|---|---|
| `id` | `Long` |
| `mbrNo` | `String` |
| `tenantId` | `String` |
| `cUserNo` | `String` |
| `planNo` | `String` |
| `level` | `String` |
| `points` | `Integer` |
| `startAt` | `String` |
| `endAt` | `String` |
| `autoRenew` | `Integer` |
| `status` | `String` |
| `createdAt` | `String` |
| `updatedAt` | `String` |

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
| `archivedAt` | `String` |
| `address` | `String` |
| `lng` | `BigDecimal` |
| `lat` | `BigDecimal` |

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

### VoidReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |

### WalletAdjustReq

| 字段 | 类型 |
|---|---|
| `userNo` | `String` |
| `balance` | `BigDecimal` |
| `bonus` | `BigDecimal` |
| `currency` | `String` |

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

### WarehouseItem

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `name` | `String` |
| `regionId` | `String` |
| `address` | `String` |
| `isDefault` | `boolean` |
| `archivedAt` | `String` |

### WarehouseReq

| 字段 | 类型 |
|---|---|
| `name` | `String` |
| `regionId` | `String` |
| `address` | `String` |

### WarehouseRow

| 字段 | 类型 |
|---|---|
| `warehouseNo` | `String` |
| `warehouseName` | `String` |
| `itemType` | `String` |
| `opening` | `long` |
| `inQty` | `long` |
| `outQty` | `long` |
| `closing` | `long` |
| `available` | `int` |
| `minQty` | `int` |
| `low` | `boolean` |

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

### WithdrawReq

| 字段 | 类型 |
|---|---|
| `reason` | `String` |
| `plannedAt` | `LocalDate` |

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
| `sameOperator` | `boolean` |
| `approvalState` | `String` |
| `approvalNo` | `String` |
| `approvalNote` | `String` |

### WoOps

| 字段 | 类型 |
|---|---|
| `siteNo` | `String` |
| `assigneeType` | `String` |
| `slaRemainMinutes` | `Long` |
| `reviewStatus` | `String` |
| `faultReasonCode` | `String` |
| `closeReason` | `String` |
| `mergedIntoWoNo` | `String` |
| `alarmRecoveredAt` | `String` |
| `alarmCount` | `int` |

### WoSlaRule

| 字段 | 类型 |
|---|---|
| `slaNo` | `String` |
| `woType` | `String` |
| `responseMins` | `Integer` |
| `resolveMins` | `Integer` |
| `escalateTo` | `String` |
| `resolveEscalateTo` | `String` |
| `active` | `Integer` |
| `priority` | `String` |
| `id` | `?` |
| `tenantId` | `?` |
| `createdAt` | `?` |
| `createdBy` | `?` |
| `updatedAt` | `?` |
| `updatedBy` | `?` |
| `version` | `?` |
| `deleted` | `?` |

### WoSummary

| 字段 | 类型 |
|---|---|
| `toDispatch` | `long` |
| `dueSoon` | `long` |
| `overdue` | `long` |
| `reviewFailed` | `long` |

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
| `ops` | [`WoOps`](#woops) |

### WorkOrderDetailView

| 字段 | 类型 |
|---|---|
| `order` | [`WorkOrder`](#workorder) |
| `timeline` | 数组<[`TimelineItem`](#timelineitem)> |
| `photos` | 数组<[`FileRef`](#fileref)> |
| `alarms` | 数组<[`AlarmRecord`](#alarmrecord)> |

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
| `disposition` | `String` |

### WorkOrderRef

| 字段 | 类型 |
|---|---|
| `alarmNo` | `String` |
| `woNo` | `String` |
| `created` | `boolean` |


## 枚举

- **AccountStatus** — `ACTIVE`
- **AdSlotStatus** — `IDLE`
- **AdjustmentKind** — `DEPOSIT_REFUND` `ENTRY_FEE_SETTLE` `GUARANTEE_TOPUP`
- **AdjustmentStatus** — `PENDING` `CONFIRMED` `SETTLED` `VOID`
- **AgentAccountStatus** — `ACTIVE`
- **AgentExitStatus** — `RECLAIMING` `SETTLING` `CLOSING` `CLOSED`
- **AgentStatus** — `ENABLED`
- **AgentType** — `AGENT`
- **AlarmCause** — `OFFLINE` `UNSTABLE` `NO_STOCK` `FULL` `FAULT` `MIXED` `NO_CONTRACT` `EXPIRING` `LOW_BATTERY` `AGED` `MISSING` `SLA_BELOW` `LOW_YIELD` `CANCEL_FAILED` `SN_SEEN` `HAZARD`
- **AlarmCloseReason** — `RESOLVED` `FALSE_ALARM` `SELF_HEALED` `AUTO_FIXED`
- **AlarmDisposition** — `AUTO_FIX` `WORK_ORDER` `CS_CASE` `TODO`
- **AlarmDomain** — `AVAILABILITY` `RETURNABILITY` `TRANSACTION` `SAFETY` `ASSET` `REVENUE` `SERVICE` `PARTNER`
- **AlarmEvalType** — `EVENT` `STATE` `COUNT`
- **AlarmLevel** — `INFO` `WARN`
- **AlarmNoticeStatus** — `SENT`
- **AlarmNotifyChannel** — `EMAIL` `PUSH` `SMS`
- **AlarmNotifyMethod** — `INSTANT`
- **AlarmRuleStatus** — `ACTIVE`
- **AlarmStatus** — `OPEN` `ACKED`
- **AlarmSubjectType** — `SITE` `CABINET` `SLOT` `ORDER` `USER` `POWERBANK` `CONTRACT` `PAYEE` `AGENT` `WORK_ORDER` `PAYMENT` `INTAKE`
- **AlarmTodoStatus** — `OPEN` `DONE`
- **AppPlatform** — `IOS` `ANDROID`
- **ApplyStatus** — `DRAFT` `SUBMITTED` `REVIEWING` `APPROVED` `SUBMITTED`
- **AssetDiffKind** — `MISSING` `EXTRA` `COUNT_MISMATCH`
- **AssetDiffStatus** — `OPEN` `RESOLVED`
- **AssignTargetType** — `CABINET` `LOCATION`
- **AudienceType** — `ALL` `MEMBER_LEVEL` `SEGMENT`
- **AuditOutcome** — `SUCCESS` `DENIED`
- **CabinetStatus** — `IN_STOCK` `IN_TRANSIT` `DEPLOYED` `FAULT`
- **CampaignKind** — `NEW_USER` `RECHARGE_GIFT` `COUPON_PUSH` `REFERRAL`
- **CampaignStatus** — `DRAFT` `RUNNING` `PAUSED`
- **ClientCode** — `OPS` `AGENT`
- **CodeBatchStatus** — `PENDING` `PARTIAL` `BOUND`
- **CommissionDimension** — `GMV`
- **ContractAuditStage** — `OPS` `FINANCE`
- **ContractKind** — `MAIN` `SUPPLEMENT`
- **ContractSettlePeriod** — `MONTH`
- **ContractShareBase** — `NET`
- **ContractShareMode** — `SHARE` `ENTRY_FEE` `GUARANTEE`
- **ContractStatus** — `DRAFT` `PENDING` `SIGNED` `ACTIVE` `EXPIRED`
- **ContractTermReqStatus** — `PENDING` `APPROVED` `REJECTED`
- **CountReviewDecision** — `APPROVED` `REJECTED` `WITHDRAWN`
- **CountReviewState** — `DRAFT` `PREPARING` `SUBMITTING` `IN_REVIEW` `SUBMIT_FAILED` `DECIDED` `APPLY_FAILED` `APPLIED`
- **CountStatus** — `OPEN` `SUBMITTED` `APPROVED` `POSTED` `CANCELLED`
- **CouponAudience** — `ALL` `SEGMENT`
- **CouponTplStatus** — `ACTIVE`
- **CouponType** — `CUT`
- **CredentialStatus** — `ACTIVE`
- **CsSenderType** — `USER`
- **CsSessionStatus** — `ACTIVE`
- **CsTicketChannel** — `APP` `MP` `H5` `PHONE` `EMAIL` `MANUAL`
- **CsTicketStatus** — `OPEN` `PROCESSING`
- **DataScopeSubject** — `ROLE`
- **DataScopeType** — `ALL` `REGION` `SITE` `LOCATION` `VENUE` `AGENT`
- **DepositStatus** — `HELD` `RELEASED` `BOUGHT_OUT`
- **DeptStatus** — `ACTIVE`
- **DeviceCodeType** — `QR`
- **DeviceKind** — `POWERBANK` `EV_PILE` `LOCKER`
- **DisposalKind** — `SCRAP` `RETURN_SUPPLIER`
- **DisposalReason** — `BROKEN` `OBSOLETE` `OTHER`
- **DunChannel** — `SMS` `PUSH`
- **EmployeeStatus** — `ACTIVE`
- **ExportFormat** — `XLSX`
- **FileCategory** — `CONTRACT_SCAN` `JPEG` `WO_PHOTO` `SURVEY_PHOTO` `LEAD_PHOTO` `AGENT_QUALIFICATION` `JPEG` `CS_EVIDENCE` `PUBLIC_IMAGE` `PNG` `PII` `PUBLIC`
- **FileStatus** — `TEMP` `BOUND` `REMOVED` `PURGED`
- **ImpactPeriod** — `PEAK` `OPEN`
- **ImpactScope** — `SITE` `CABINET` `SLOT` `ORDER`
- **InboxStatus** — `DONE` `FAILED`
- **IntakeStatus** — `OPEN` `DONE`
- **InvTransferStatus** — `DRAFT` `IN_TRANSIT`
- **InvoiceStatus** — `DRAFT` `ISSUED`
- **InvoiceTitleType** — `PERSONAL`
- **LeadOwnerType** — `STAFF`
- **LeadStatus** — `NEW` `CONTACTED` `NEGOTIATING` `SIGNED`
- **LocationStatus** — `ACTIVE`
- **MaterialCategory** — `DEVICE` `SPARE_PART` `CONSUMABLE`
- **MaterialDocKind** — `INBOUND` `OUTBOUND` `TRANSFER`
- **MaterialDocPurpose** — `PURCHASE` `OPENING` `RETURN` `REQUISITION` `SCRAP` `RETURN_SUPPLIER` `TRANSFER`
- **MaterialTracking** — `SERIAL` `QUANTITY`
- **MediaKind** — `PDF` `JPEG` `PNG` `WEBP`
- **MergeScope** — `DEVICE` `SITE`
- **MovementEvent** — `ISSUE` `CLAIM` `REDIRECT` `POOL_RETURN` `BIND` `UNBIND` `SHIP` `RECEIVE` `UNDEPLOY` `RETURN_INTAKE` `INBOUND` `REASSIGN` `RETIRE` `LOST`
- **NotifyBlacklistChannel** — `ALL` `EMAIL` `PUSH` `SMS`
- **NotifyBlacklistReason** — `USER_OPT_OUT` `HARD_BOUNCE` `ABUSE`
- **NotifyLogStatus** — `SENT`
- **NotifyTemplateChannel** — `INAPP` `PUSH` `SMS`
- **NotifyTemplateStatus** — `ENABLED`
- **OnlineStatus** — `ONLINE`
- **OrderStatus** — `CREATED` `DISPENSING` `IN_USE` `OVERDUE_CHARGED` `RETURNED` `SETTLED` `CLOSED`
- **OtaReleaseStatus** — `DRAFT` `PUBLISHED` `PAUSED` `COMPLETED`
- **OtaRolloutScope** — `DEVICE` `LOCATION`
- **OtaRolloutStatus** — `PENDING` `RUNNING` `DONE`
- **OtaRolloutStrategy** — `GRAY`
- **OutboxStatus** — `PENDING` `SENT` `FAILED`
- **Outcome** — `OK` `NOT_CONFIGURED` `UNREACHABLE` `REMOTE_ERROR`
- **PayAuthStatus** — `FROZEN` `CAPTURED`
- **PayChannelMode** — `DELEGATED`
- **PayOrderStatus** — `INIT` `PAYING` `PAID` `FAILED`
- **PayRefundStatus** — `INIT` `SUCCESS`
- **PayoutTaskStatus** — `PENDING_REVIEW` `PENDING_TRANSFER` `DONE`
- **Period** — `LAST_7D` `LAST_30D` `LAST_13W` `LAST_12M`
- **PowerbankHealth** — `OK` `FAULT`
- **PowerbankStatus** — `IN_STOCK` `IN_CABINET` `RENTED` `FAULT` `LOST` `SOLD`
- **PriceAdjustmentStatus** — `SCHEDULED` `APPLIED` `CANCELLED` `REVERTED`
- **PricePlanStatus** — `ACTIVE`
- **ProblemCategory** — `ACCOUNT` `BILLING` `DEVICE` `RENT` `RETURN`
- **ProblemStatus** — `ENABLED`
- **ProblemSuggestedAction** — `SELF_SERVICE` `TO_CS` `TO_REFUND`
- **ProtectionAction** — `STOP_RENT` `SLOT_DISABLE` `SLOT_LOCK`
- **ProtectionHolder** — `SIGNAL` `ALARM` `MANUAL`
- **Provider** — `WECHAT_MP` `WECHAT_OA` `APPLE` `GOOGLE` `PHONE`
- **PushChannel** — `APP_PUSH`
- **PushStatus** — `DRAFT` `SCHEDULED` `SENDING`
- **QcStatus** — `PENDING` `PASSED`
- **Realm** — `STAFF` `AGENT` `CONSUMER`
- **RechargeOrderStatus** — `PENDING` `PAID` `FAILED`
- **ReconTaskStatus** — `MATCHED`
- **RecoverRule** — `SIGNAL_CLEAR` `DISPOSITION_DONE`
- **RemovalDisposition** — `RETURN_TO_POOL`
- **ScopeLevel** — `DEVICE` `LOCATION` `SITE` `VENUE` `AGENT` `SCENE` `REGION`
- **SettlementRefType** — `ORDER` `SHARE`
- **SettlementStatus** — `GEN` `CONFIRMED`
- **ShareBaseMode** — `PRE_TAX`
- **ShareBasis** — `INVEST` `DEVELOP` `OPERATE`
- **ShareDimension** — `VENUE`
- **ShareMode** — `CHANNEL_SPLIT`
- **ShareRecordStatus** — `PENDING`
- **ShipmentDocType** — `TRANSFER` `ISSUE` `DISPOSAL`
- **SignalLevel** — `STRONG` `GOOD` `WEAK`
- **SiteAgentRole** — `INVEST` `DEVELOP` `OPERATE`
- **SiteStatus** — `PREPARING` `ACTIVE` `PAUSED` `WITHDRAWING`
- **Status** — `SUCCESS` `FAILED` `SKIPPED`
- **SurveyResult** — `PASS`
- **TransferEndpointType** — `WAREHOUSE` `SITE` `LOCATION`
- **TransferSource** — `MANUAL` `REMOVAL`
- **TrialRentStatus** — `EJECTING` `WAIT_RETURN` `PASSED` `FAILED`
- **Type** — `SCHEDULED` `MANUAL`
- **VendorStatus** — `ENABLED`
- **VenueIndustry** — `SHOPPING_MALL` `COMMERCIAL_RE` `AIRPORT` `TRANSPORT_HUB` `F_AND_B` `HOTEL` `MEDICAL` `EDUCATION` `OFFICE` `ENTERTAINMENT` `RETAIL` `SCENIC`
- **WithdrawalApprovalState** — `SUBMITTING` `IN_REVIEW` `SUBMIT_FAILED` `APPROVED_BLOCKED`
- **WithdrawalOrigin** — `APPLY` `PAYOUT_TASK`
- **WithdrawalSeparation** — `REQUIRE` `WARN`
- **WithdrawalStatus** — `APPLY` `AUDIT` `PAYING` `PAID`
- **WoAuditResult** — `PASS` `PASS_WITH_ISSUE`
- **WoDispatchAction** — `DISPATCH` `ACCEPT` `REASSIGN`
- **WoFaultReason** — `NETWORK` `POWER` `SLOT_MECH` `LOCK` `BATTERY` `SCREEN` `DAMAGE`
- **WoPriority** — `LOW` `MEDIUM` `HIGH`
- **WoReviewStatus** — `PASSED` `FAILED`
- **WorkOrderStatus** — `CREATED` `DISPATCHED` `ACCEPTED` `PROCESSING` `DONE` `AUDITED`
- **WorkOrderType** — `FAULT` `REFILL` `INSPECT` `INSTALL` `REMOVE` `COMPLAINT`


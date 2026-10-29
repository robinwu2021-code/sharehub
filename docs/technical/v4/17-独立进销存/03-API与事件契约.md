# 独立进销存：API 与事件契约

> 状态：开发设计草案（未实施） · 2026-10-29
> 已确认边界：仓内库存、进出库、盘点；出库后不跟踪业务库存；通过出库池交接；独立部署并提供 API。其余具体规则为本轮建议设计。

## 1. 公共约定

前缀 `/api/inventory/v1`。独立服务以 OpenAPI 为契约真源，SDK/前端类型从它生成。此文是待实现契约，不等于已有端点。ID 为字符串，时间 ISO-8601 带时区，数量整数基本单位。分页 `{list,total,page,size}`，size 默认 20、上限 200；流水与事件采用不透明 cursor。

统一响应 `{code,message,data,traceId}`，成功 code=0。失败以 HTTP 400/401/403/404/409/422/503 区分参数/认证/权限/不存在/状态冲突/业务校验/暂不可用，稳定字符串业务码例如 `INV_SERIAL_ALREADY_STOCKED`，不直接沿用宿主商城数字码。客户端按 code 分支，不解析文案。

机器调用：TLS＋服务凭证（首版沿用现有 App-Key/App-Secret 的哈希存储和轮换机制，扩展 scopes）；凭证绑定 clientId/systemCode/可访问 ownerIds。选业主可传 `X-Inventory-Owner`，必须经服务端授权。所有写命令用 `Idempotency-Key`，`X-Actor-Ref` 只接受可信 BFF/执行器并与审计一起存，不能让普通浏览器持有系统密钥或伪装人员。

## 2. 接口清单

以下均在公共前缀下；所有写入均要求幂等键。过账读取已保存草稿，不重新接收数量。

| 方法与资源 | 输入/规则 | 输出 |
|---|---|---|
| GET/POST `/items`；PATCH `/items/{id}` | 查询/建物料/改可变字段 | Item |
| GET/POST `/warehouses`；PATCH `/warehouses/{id}` | 仓配置，停用须清理开放业务 | Warehouse |
| PUT `/external-targets/{system}/{type}/{ref}` | name,status,sourceVersion；仅所属系统可同步 | Target |
| GET `/external-targets` | system,type,keyword,status | Page<Target> |
| GET `/balances` | warehouseId,itemId | Page<Balance> |
| GET `/serials` | warehouseId,itemId,serialNo,qcStatus | Page<StockSerial> |
| POST `/inbounds`；PATCH `/inbounds/{no}`；GET `/inbounds/{no}` | 来源、仓、行、SN；RETURN 关联池/原出库 | Inbound |
| POST `/inbounds/{no}/post`、`/cancel` | 实物核验后过账；仅草稿可 cancel | CommandResult |
| POST `/inbounds/{no}/business-clearance` | businessRef,clearanceRef；原领取系统确认本地关联已解除 | CommandResult |
| POST `/outbounds`；PATCH `/outbounds/{no}`；GET `/outbounds/{no}` | 见示例；仅草稿可修改 | Outbound |
| POST `/outbounds/{no}/post`、`/cancel` | 原子出库；已过账禁止 cancel | CommandResult |
| POST `/outbounds/{no}/corrections` | reason,approvalRef；仅有权限且未交实物等前提成立 | Correction |
| GET `/dispatch-pool` | targetSystem,targetType,targetRef,receiverRef,itemId,serialNo,state | Page<DispatchItem> |
| POST `/dispatch-pool/claims` | dispatchItemIds,businessType,businessRef | ClaimResult |
| GET `/dispatch-pool/claims/by-request/{requestId}` | 请求号按 client/owner 命名空间解析 | ClaimResult |
| POST `/dispatch-pool/claims/{claimNo}/release` | reason,compensationRef；验证原领取系统 | CommandResult |
| POST `/counts`；GET `/counts/{no}` | warehouseId,itemIds；获取范围锁后建快照 | Count |
| POST `/counts/{no}/scans` | serialNos,scanBatchId；扫码批次幂等 | ScanResult |
| PUT `/counts/{no}/lines/{itemId}` | quantity 模式实盘数/原因，SERIAL 由扫描生成 | CountLine |
| POST `/counts/{no}/submit`、`/reject`、`/approve`、`/post`、`/cancel` | 明确状态前置；复核与录入人分离 | Count |
| GET `/qc/templates`；POST `/qc/templates`、`/{id}/versions/{v}/publish` | 模板配置/发布；版本不可原位修改 | QcTemplate |
| GET `/qc/tasks`、`/qc/tasks/{no}` | 查询任务与检查项 | QcTask |
| POST `/qc/tasks` | episodeId,reason；初检通常由入库自动创建 | QcTask |
| POST `/qc/tasks/{no}/start`、`/results`、`/complete`、`/reinspect` | results 含采样/证据；complete 服务端计算 | QcTask |
| POST `/qc/tasks/{no}/acquire`、`/heartbeat` | executor 领取/续租，fenceToken 防旧执行器回写 | QcExecution |
| POST `/qc/episodes/{id}/hold`、`/exempt`；POST `/qc/holds/{holdId}/release` | 原因、授权、期限；仅解除指定持有原因 | QcDisposition |
| GET `/ledger`、`/documents` | 单据/流水查询 | LedgerPage/Page<Document> |
| GET `/events`；POST `/event-acks` | 订阅级 cursor/ack | EventPage |
| GET `/commands/{requestId}` | 读写命令最终结果 | CommandResult |

查询 serials 只返回仓内周期，历史用单据/流水查询；出库池绝不伪装成 serials 的库存筛选。批量限制：单次命令最多 200 SN，超过须分批制单，不能在服务端悄悄截断。

## 3. 出库请求示例

```json
{
  "warehouseId": "WH001",
  "purpose": "ISSUE",
  "target": {"systemCode":"POWERBANK","type":"SITE","ref":"S001"},
  "receiverRef": "U001",
  "source": {"systemCode":"POWERBANK","type":"INSTALL_REQUEST","ref":"IR001"},
  "lines": [{"itemId":"MODEL001","qty":2,"serialNos":["SN001","SN002"]}]
}
```

target 的名称服务端从授权同步的映射解析；不接受任意终端提交名称当权威。receiverRef 属于 target.systemCode 的人员命名空间，服务端验证已同步有效人员和授权关系；历史池保留当时姓名。sourceSystem 必须等于调用方身份或明确委托范围。

ISSUE 必须是 SERIAL，生成逐件池。QUANTITY 物料内部领用继续使用 INTERNAL，不产生 SN 池；未来数量交接必须新扩展契约，不让一个 qty=5 的行冒充单件设备。

## 4. 领取请求与响应

请求头 `Idempotency-Key: install-WO001-devices`：

```json
{
  "dispatchItemIds": ["DI001","DI002"],
  "businessType": "INSTALL_WORK_ORDER",
  "businessRef": "WO001"
}
```

```json
{
  "code":0,"message":"ok","traceId":"trace-001",
  "data":{
    "requestId":"install-WO001-devices",
    "claims":[
      {"claimNo":"CL001","dispatchItemId":"DI001","serialNo":"SN001","state":"CLAIMED"},
      {"claimNo":"CL002","dispatchItemId":"DI002","serialNo":"SN002","state":"CLAIMED"}
    ]
  }
}
```

请求号统一来自 Idempotency-Key，不在 body 重复定义另一套。调用方不得指定 businessSystem 冒充其它系统。批量全成全败；同键同内容返回原响应，同键异内容 `INV_IDEMPOTENCY_MISMATCH`；他人已领取 `INV_DISPATCH_CLAIMED`；错误目标 `INV_TARGET_FORBIDDEN`。列表只供候选，领取时重新校验。

系统凭证只能证明业务系统身份，不能单独证明具体操作者可领某站点设备。业务 BFF 必须验证操作者的站点/接收人范围；库存再验证池属于目标系统/业主及显式授权范围。禁止前端传 ref 就放行。领取、退仓、纠正共享池/周期锁。

release 是业务系统对“业务关联未生效或已经安全撤销”的受控补偿承诺，附 compensationRef 并保留审计。库存不能跨库核实现场使用状态；宿主必须在本地冻结关联、确认无实际使用后才能释放。正常业务换站点不 release、不重领。

## 5. 质检结果契约

```json
{
  "sourceEventId":"executor-event-001",
  "executionId":"EX001",
  "fenceToken":3,
  "templateVersion":1,
  "results":[
    {"checkCode":"BATTERY","value":82,"unit":"PERCENT","measuredAt":"2026-10-29T10:00:00+08:00","evidenceRefs":["media:qc001"]}
  ]
}
```

任务号在路径，任务内部固定 episodeId，执行器不能改绑定。来源事件去重；不同 task 不复用结果。evidenceRefs 为可访问的文件服务受控引用，校验归属、媒体类型和存在性；禁止服务端任意抓取用户 URL。缺项/错误单位/超有效期采样返回明确检查项错误。complete 返回检查项评估和库存计算结论。

## 6. 事件与恢复

事件类型：InboundPosted、OutboundPosted、DispatchClaimed、DispatchClaimReleased、ReturnInboundPosted、ReturnBusinessCleared、StockCountPosted、QcTaskReady、QcCompleted、QcHoldChanged。

事件信封包含 eventId,eventType,schemaVersion,ownerId,sourceSystem,resourceType,resourceId,resourceVersion,occurredAt,data。OutboundPosted 提供单号、目的地、SN/池 ID（大单分页查详情）；不宣称业务设备已创建。消费以 eventId 去重，以 resourceVersion 防旧事件覆盖新投影。

不能直接把 outbox 自增 ID 当可靠提交顺序游标：小 ID 事务可能晚提交而被跳过。投递器只读取已提交 outbox，在每订阅流中串行分配 sequence 并持久化；同订阅并发派发使用 DB 锁确保序列事务顺序。GET events 使用不透明 cursor，客户端保存处理与 ack 进度；至少一次投递，重复正常，业务 inbox 与本地结果同事务提交。

订阅被授权到目标系统/业主；不得拉取其它系统事件。事件保留建议至少 90 天，可配置；游标过期返回 `INV_CURSOR_EXPIRED`，通过时间区间单据查询（含历史和终态池）及资源版本重建快照，再续订，不能仅从当前 AVAILABLE 列表恢复。

写超时先查 commands；服务不可用时返回待处理，不允许本地假成功或直接回写旧库存表。

## 7. 退仓业务确认与检测执行

business-clearance 只能由原领取系统或单独授权的异常处置者调用，必须引用已提交的本地 clearanceRef 和原 businessRef；校验入库、SN、原池关联一致。只释放 BUSINESS_RETURN_CLEARANCE，不释放人工冻结/待检；同键幂等并发布 ReturnBusinessCleared。未领取设备的免确认由库存事务核验，无终端跳过参数。

执行器 `GET /qc/tasks?assignedToMe=true&executable=true` 仅返回自身任务；acquire 返回 executionId,fenceToken,leaseUntil，heartbeat 需当前 token。机器 results/complete 带 executionId/fenceToken，与 02 §8 的租约和重试规则一致；旧执行器返回 INV_QC_EXECUTION_STALE。人工 start/results 用 inspect 权限，不允许机器伪装人工绕过 fence。

## 8. ai-shop 预留与销售 API（M5 必做）

| 方法与资源 | 契约 |
|---|---|
| POST `/reservations` | externalRef,ttlSeconds,lines[{itemId,warehouseId,qty}]；可用库存原子预留，全成全败 |
| GET `/reservations/by-external-ref/{ref}` | 读取 HELD/COMMITTED/RELEASED/EXPIRED、expiresAt、销售出库号；按 client/owner 作用域 |
| POST `/reservations/{id}/commit` | orderRef；HELD→COMMITTED，原子扣实存/释放预留/生成 SALE 出库；同状态重试返回原销售单 |
| POST `/reservations/{id}/release` | reason；HELD→RELEASED，仅释放预留；已 COMMITTED 返回 INV_RESERVATION_COMMITTED |
| POST `/sale-returns` | afterSaleRef,originalOutboundNo,lines；退货实收授权后创建 RETURN 入库，源售后号去重 |

预留身份 UK(ownerId,clientId,externalRef)，首次请求的行摘要冻结；同 externalRef 异行冲突。ttlSeconds 默认 900，范围 60–86400，预留到期与 commit 锁同一预留头/行；只有未到期 HELD 可 commit，已 EXPIRED/RELEASED 拒绝，不能靠换 requestId 再生成 SALE。成功 commit 可跨请求键幂等返回原单。数量 SKU 走此链，SERIAL 销售预留本期明确拒绝，需另增指定 SN 协议后开放。

sale-returns 按售后来源唯一，累计退货不得超过原销售行未退数量；不接受退款状态参数让库存决定是否实物退货，宿主负责确认 RETURN_REFUND 与实收，调用权限和审计保留来源。REFUND_ONLY 不调用此接口。销售物品不进入 ISSUE 出库池。

## 9. 原仓间数量调拨 API（M5 必做）

POST `/transfers`（fromWarehouseId,toWarehouseId,lines）；GET `/transfers/{no}`；POST `/transfers/{no}/ship`、`/receive`、`/cancel`。相同业主、不同有效仓，状态 DRAFT→SHIPPED→RECEIVED，DRAFT 可 CANCELLED；每一步有单头 CAS 和动作唯一键，重复 ship/receive 返回原出入库凭证。

保留原数量调拨整单收货语义，receive 必须显式提交 matched=true 并由操作人确认实物全到；实收不符拒绝本接口，走在途差异处置单（POST `/transfers/{no}/exceptions`，提交实收量及原因，需库存复核后逐笔在途出库/目标入库/损失处置，未解决余量仍留在途）。不允许端上自动默认 matched。例外单支持多批次、累计实收/损失不超过发出量，以例外单号幂等过账，全部去向核清后才结单。

内部在途库位不进入业务池；首版仅 QUANTITY 仓间调拨，SERIAL 仓间调拨返回 INV_TRANSFER_SERIAL_UNSUPPORTED，powerbank 不依赖它。后续如需 SN 仓间流转必须增加逐件在途和实收，不能将其转换为普通业务 ISSUE 丢掉仓间责任。此限制不删除 ai-shop 现有数量调拨能力。

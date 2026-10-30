"""S5 本机联调：ShareHub(8085, inv.engine=SERVICE) ↔ ai-shop 独立进销存(8086)。只读凭证文件，不打印密钥。

用法：python3 e2e.py [凭证文件]（默认 ~/.local/etc/inventory-sharehub-cred.json）。
在跑着的运营台上验（backend-local 8080 / pb_local）：SH_BASE=http://127.0.0.1:8080 SH_DB=pb_local LIVE=1 ADMIN_PASSWORD=… INVENTORY_BASE=http://127.0.0.1:8088 python3 e2e.py ~/.local/etc/inventory-pblocal-cred.json
LIVE=1 不清映射表、不切也不复原引擎（须已在库存设置切到 SERVICE）、跳过 S7（存量已从页面真迁过，测试的造数会把迁移标记写乱）。
前提：.claude/launch.json 的 inventory-svc-local 与 backend-inv 已启动（backend-inv 用的运行时类路径：
mvn -o dependency:build-classpath -pl sharehub-app -Dmdep.outputFile=sharehub-app/target/runtime-classpath.txt）；运行期间临时把 test_sharehub 的 inv.engine 改成 SERVICE，结束（含失败）复原 LOCAL。
"""
import json, os, sys, time, urllib.request, urllib.error, subprocess, random

SH = os.environ.get("SH_BASE", "http://127.0.0.1:8085")
DB = os.environ.get("SH_DB", "test_sharehub")
LIVE = os.environ.get("LIVE") == "1"
INV = os.environ.get("INVENTORY_BASE", "http://127.0.0.1:8086") + "/api/inventory/v1"
import os
CRED = json.load(open(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.local/etc/inventory-sharehub-cred.json")))
MYSQL = ["/Users/workingowl/.local/opt/mysql/bin/mysql", "-uroot", "-S", "/tmp/mysql.sock", "-N", "-e"]
results = []

def sql(q):  # 语句里统一写 test_sharehub.，按 SH_DB 换库
    return subprocess.run(MYSQL + [q.replace("test_sharehub.", DB + ".")], capture_output=True, text=True).stdout.strip()

def req(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")

def sh(method, path, body=None, token=None):
    st, b = req(method, SH + path, body, {"Authorization": "Bearer " + token} if token else None)
    return st, (b.get("data") if isinstance(b, dict) and "data" in b else b), b

def inv(path):
    return req("GET", INV + path, None, {"Authorization": f"Bearer {CRED['appKey']}:{CRED['appSecret']}"})[1]

def inv_status(path):
    return req("GET", INV + path, None, {"Authorization": f"Bearer {CRED['appKey']}:{CRED['appSecret']}"})

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("✓ " if ok else "✗ ") + name + ("" if ok else f"  —— {detail}"))

def login(role, user=None):
    # LIVE：运营台开着 admin 口令闸，只有 admin 能登（口令取环境变量 ADMIN_PASSWORD），三个角色都用它
    body = {"username": "admin", "password": os.environ["ADMIN_PASSWORD"]} if LIVE else {"username": user or role.lower() + ".user", "role": role}
    st, d, raw = sh("POST", "/api/auth/login", body)
    assert st == 200, raw
    return d["token"]

tag = hex(int(time.time()))[2:].upper()
if not LIVE:
    sql("UPDATE test_sharehub.sys_param SET value='SERVICE' WHERE param_key='inv.engine'; DELETE FROM test_sharehub.inventory_sync_state")
try:
    admin, wh, bd = login("ADMIN"), login("WAREHOUSE"), login("BD")
    st, eng, _ = sh("GET", "/api/ops/inventory/engine", token=admin)
    check("引擎 = SERVICE、凭证已配、库存服务可达", eng["engine"] == "SERVICE" and eng["configured"] and eng["reachable"] is True, eng)

    # 0. 契约探测（只读，阶段 0 封装的接口）：查不到的号返回库存服务自己的 not_found，说明路由在；未知路由是另一种 404
    for path, what in (("/commands/E2E-NONE-" + tag, "命令查回"), ("/dispatch-pool/claims/by-request/E2E-NONE-" + tag, "领取查回"),
                       ("/inbounds/E2E-NONE", "入库单详情"), ("/outbounds/E2E-NONE", "出库单详情")):
        st, b = inv_status(path)
        check(f"契约：{what}接口存在", st == 404 and isinstance(b, dict) and b.get("code") == "inventory.not_found", (st, b))
    st, b = inv_status("/dispatch-pool?status=AVAILABLE&status=CLAIMED&rootTargetRef=E2E-NONE")
    check("契约：出库池多条件查询", st == 200 and b == [], (st, b))
    st, b = inv_status("/in-transit")
    check("契约：出库在途汇总", st == 200 and isinstance(b, list), (st, b))
    st, b = inv_status("/definitely-not-a-route")
    check("契约：未知路由与 not_found 可区分", st == 404 and (not isinstance(b, dict) or b.get("code") != "inventory.not_found"), (st, b))

    # 1. 入库：到货登记 → 质检 → 入库 → 库存服务在 WH001 对应位置有这个序列号
    cab = "CABE2E" + tag
    st, _, raw = sh("POST", "/api/ops/cabinets", {"cabinetNo": cab, "sn": cab, "vendorCode": "TEST", "slotTotal": 8}, admin)
    sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no=NULL WHERE cabinet_no='{cab}'")
    st, ik, raw = sh("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH001", "sourceRef": "DN-E2E-" + tag, "cabinetNos": [cab]}, wh)
    no = ik["intake"]["intakeNo"]
    sh("POST", f"/api/ops/inventory/intakes/{no}/items/CABINET/{cab}/qc", {"powerOn": True, "slotsOk": True}, wh)
    st, d, raw = sh("POST", f"/api/ops/inventory/intakes/{no}/inbound", {"items": [{"itemType": "CABINET", "itemNo": cab}]}, wh)
    check("入库：ShareHub 返回成功", st == 200, raw)
    loc = sql(f"SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:WH001'")
    serials = inv(f"/serials?locationId={loc}")
    s = next((x for x in serials if x.get("serialNo") == cab), None) if isinstance(serials, list) else None
    check("入库：库存服务在库，gateRef = 质检记录号", s and str(s.get("gateRef", "")).startswith("QC"), serials)

    # 2. 出库给商家（只选商户）→ 出库池记录 CLAIMED
    if LIVE:  # 入驻审核要求另一人放行，单账号建不了商家：用库里已启用的商户
        st, cur, raw = sh("GET", "/api/agent/agents?page=1&size=50", token=admin)
        agent = next(a["agentNo"] for a in cur["list"] if a.get("status") == "ENABLED" and a.get("agentType") == "AGENT"
                     and a.get("address") and a.get("lng") is not None and a.get("industry"))  # 能接收设备的
    else:
        phone = "+97156" + str(1000000 + random.randint(0, 8999999))
        st, ap, raw = sh("POST", "/api/agent/applies", {"phone": phone, "email": f"e2e{tag}@example.com", "operatorName": "联调商家 " + tag, "operatorType": "AGENT"}, bd)
        sh("POST", f"/api/agent/applies/{ap['applyNo']}/accept", {}, admin)
        st, au, raw = sh("POST", f"/api/agent/applies/{ap['applyNo']}/audit", {"approve": True}, admin)
        agent = au["operatorNo"]
        st, cur, _ = sh("GET", f"/api/agent/agents?page=1&size=5&keyword={agent}", token=admin)
        nm = cur["list"][0]["name"]
        sh("POST", f"/api/agent/agents/{agent}", {"name": nm, "agentType": "AGENT", "status": "ENABLED", "address": "Al Barsha", "lng": 55.2, "lat": 25.11, "industry": "HOTEL"}, admin)
    st, d, raw = sh("POST", "/api/ops/devices/issue", {"agentNo": agent, "cabinetNos": [cab]}, admin)
    check("出库：ShareHub 返回成功", st == 200, raw)
    pool = inv(f"/dispatch-pool?serialNo={cab}")
    check("出库：库存服务出库池 CLAIMED、商户 = 本地商家", isinstance(pool, list) and pool and pool[-1]["status"] == "CLAIMED" and pool[-1]["rootTargetRef"] == agent, pool)
    tgt = inv(f"/external-targets?type=MERCHANT")
    check("主数据：商户已同步为外部目标", isinstance(tgt, list) and any(t["ref"] == agent for t in tgt), tgt)

    # 3. 退回仓库 → 释放 + 撤回，库存服务回到在库
    st, d, raw = sh("POST", "/api/ops/devices/pool-return", {"cabinetNos": [cab], "reason": "联调退回"}, admin)
    check("退回：ShareHub 返回成功", st == 200, raw)
    pool = inv(f"/dispatch-pool?serialNo={cab}")
    check("退回：出库池 REVOKED", pool and pool[-1]["status"] == "REVOKED", pool)
    serials = inv(f"/serials?locationId={loc}")
    check("退回：库存服务重新在库", any(x.get("serialNo") == cab for x in serials), serials)

    # 4. 库存服务拒绝 → 409 带对方错误码、本地不动（对一台不在库存服务里的柜出库）
    ghost = "CABGHOST" + tag
    sh("POST", "/api/ops/cabinets", {"cabinetNo": ghost, "sn": ghost, "vendorCode": "TEST", "slotTotal": 8}, admin)
    sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no='WH001', qc_status='PASSED' WHERE cabinet_no='{ghost}'")
    st, d, raw = sh("POST", "/api/ops/devices/issue", {"agentNo": agent, "cabinetNos": [ghost]}, admin)
    local = sql(f"SELECT IFNULL(issued_agent_no,'') FROM test_sharehub.dev_cabinet WHERE cabinet_no='{ghost}'")
    check("拒绝：409 带 inventory.* 错误码，本地出库池不动", st == 409 and "inventory." in json.dumps(raw, ensure_ascii=False) and local == "", (st, raw, local))

    # 5. 事件拉取：手动触发任务，收件箱有记录、游标前进
    st, b = req("POST", SH + "/internal/job/inventory-event-pull/trigger")
    seq = sql("SELECT v FROM test_sharehub.inventory_sync_state WHERE k='events:seq'")
    n = sql("SELECT COUNT(*) FROM test_sharehub.inventory_inbox WHERE status='DONE'")
    check("事件：拉取任务成功、游标前进、收件箱有 DONE", st == 200 and seq and int(seq) > 0 and int(n) > 0, (st, b, seq, n))
    st, b = req("POST", SH + "/internal/job/inventory-event-pull/trigger")
    n2 = sql("SELECT COUNT(*) FROM test_sharehub.inventory_inbox")
    check("事件：再拉一次不重复入箱", int(n2) == int(sql("SELECT COUNT(DISTINCT event_id) FROM test_sharehub.inventory_inbox")), (n2,))

    # 6. 库存流水（经 BFF 读库存服务）
    st, led, raw = sh("GET", "/api/ops/inventory/ledger?warehouseNo=WH001&size=50", token=wh)
    check("流水：经 BFF 读到库存服务流水", st == 200 and led["total"] > 0, raw)

    # 7. 盘点：开单 → 扫码 → 提交。复核与过账只走统一审批（OpsKit，CountReviewService），运营接口上不能直接复核；
    #    本机没配 OpsKit 时提交给出明确的 409，不改库存服务。最后作废，免得盘点单一直冻结这个仓
    st, c, raw = sh("POST", "/api/ops/inventory/counts", {"warehouseNo": "WH001"}, wh)
    check("盘点：开单", st == 200, raw)
    cn = c["count"]["countNo"]
    check("盘点：库存服务的盘点中显示为 OPEN", c["count"]["status"] == "OPEN", c["count"])
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/scans", {"serialNos": [cab]}, wh)
    check("盘点：扫码", st == 200, raw)
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/submit", {}, wh)
    if st == 200:
        check("盘点：提交进统一审批", True)
        st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/approve", {}, admin)
        check("盘点：运营接口不能绕过统一审批直接复核", st == 409, (st, raw))
    else:
        check("盘点：未配统一审批时提交被拒且原因明确", st == 409 and "审批" in json.dumps(raw, ensure_ascii=False), (st, raw))
        st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/cancel", {}, wh)
        check("盘点：作废（解除仓库冻结）", st == 200 and c["count"]["status"] == "CANCELLED", raw)

    # 8. 撤机回仓：出库（已领取）→ 绑定 → 投放 → 撤机完工（回仓）→ 回仓调拨发货 / 签收 → 回仓待检 → 复检 → RETURN 入库 + 业务确认
    cab2 = "CABRET" + tag
    sh("POST", "/api/ops/cabinets", {"cabinetNo": cab2, "sn": cab2, "vendorCode": "TEST", "slotTotal": 8}, admin)
    sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no=NULL WHERE cabinet_no='{cab2}'")
    st, ik, _ = sh("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH001", "cabinetNos": [cab2]}, wh)
    ino = ik["intake"]["intakeNo"]
    sh("POST", f"/api/ops/inventory/intakes/{ino}/items/CABINET/{cab2}/qc", {"powerOn": True, "slotsOk": True}, wh)
    sh("POST", f"/api/ops/inventory/intakes/{ino}/inbound", {"items": [{"itemType": "CABINET", "itemNo": cab2}]}, wh)
    sh("POST", "/api/ops/devices/issue", {"agentNo": agent, "cabinetNos": [cab2]}, admin)
    st, b, raw = sh("POST", f"/api/ops/devices/{cab2}/bind", {}, admin)
    check("撤机回仓：出库后绑定点位", st == 200, raw)
    site = sql(f"SELECT site_no FROM test_sharehub.dev_cabinet WHERE cabinet_no='{cab2}'")
    # 投放：上线门禁不在本次联调范围，直接置为已投放
    sql(f"UPDATE test_sharehub.dev_cabinet SET status='DEPLOYED', online_status='ONLINE', available_count=0 WHERE cabinet_no='{cab2}'")
    wo = "WOE2E" + tag
    sql(f"INSERT INTO test_sharehub.wo_order (wo_no, tenant_id, type, source, status, cabinet_no, site_no, removal_disposition) "
        f"VALUES ('{wo}', 'MAIN', 'REMOVE', 'MANUAL', 'PROCESSING', '{cab2}', '{site}', 'RETURN_TO_WAREHOUSE')")
    st, b, raw = sh("POST", f"/api/ops/work-orders/{wo}/complete", {"photos": '["remove.jpg"]', "countedQty": 0}, admin)
    check("撤机回仓：撤机工单完工", st == 200, raw)
    tr = ""
    for _ in range(20):
        tr = sql(f"SELECT transfer_no FROM test_sharehub.inv_transfer WHERE source_type='REMOVAL' AND source_ref='{wo}'")
        if tr: break
        time.sleep(0.5)
    check("撤机回仓：生成回仓调拨单", bool(tr), tr)
    st, b, raw = sh("POST", f"/api/ops/inventory-transfers/{tr}/ship", {}, admin)
    check("撤机回仓：回仓调拨发货", st == 200, raw)
    st, b, raw = sh("POST", f"/api/ops/inventory-transfers/{tr}/receive", {"receivedNos": [cab2]}, admin)
    check("撤机回仓：签收", st == 200, raw)
    rik = sql(f"SELECT intake_no FROM test_sharehub.inv_intake WHERE kind='RETURN' AND source_ref='{wo}'")
    check("撤机回仓：生成回仓待检单", bool(rik), rik)
    sh("POST", f"/api/ops/inventory/intakes/{rik}/items/CABINET/{cab2}/qc", {"powerOn": True, "slotsOk": True}, wh)
    st, b, raw = sh("POST", f"/api/ops/inventory/intakes/{rik}/inbound", {"items": [{"itemType": "CABINET", "itemNo": cab2}]}, wh)
    check("撤机回仓：复检合格入库（RETURN）", st == 200, raw)
    pool = inv(f"/dispatch-pool?serialNo={cab2}")
    check("撤机回仓：库存服务出库池 RETURNED", pool and pool[-1]["status"] == "RETURNED", pool)
    whs = sql(f"SELECT warehouse_no FROM test_sharehub.dev_cabinet WHERE cabinet_no='{cab2}'")
    loc2 = sql(f"SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:{whs}'")
    s2 = next((x for x in inv(f"/serials?locationId={loc2}") if x.get("serialNo") == cab2), None)
    check("撤机回仓：库存服务重新在库、无冻结（业务确认已解冻）", s2 and not s2.get("holdReasons"), s2)
    st, b, raw = sh("POST", "/api/ops/devices/issue", {"agentNo": agent, "cabinetNos": [cab2]}, admin)
    check("撤机回仓：可再次出库", st == 200, raw)
    sh("POST", "/api/ops/devices/pool-return", {"cabinetNos": [cab2], "reason": "联调收尾"}, admin)

    # 9. S6 统一视图与期间报表（期间报表读库存服务）
    st, stg, raw = sh("GET", "/api/ops/inventory/stages", token=admin)
    check("S6：统一视图有数据", st == 200 and any(r["stage"] == "IN_STOCK" and r["cabinets"] > 0 for r in stg), raw)
    day = time.strftime("%Y-%m-%d", time.gmtime())
    st, rows, raw = sh("GET", f"/api/ops/inventory/period-summary?from={day}&to={day}&warehouseNo=WH001", token=admin)
    ok = st == 200 and rows and all(r["opening"] + r["inQty"] - r["outQty"] == r["closing"] for r in rows) and any(r["inQty"] > 0 for r in rows)
    check("S6：库存服务期间汇总有进有出、期初 + 入 − 出 = 期末", ok, raw)

    # 10. S7 存量迁移：一台本地在 WH002、库存服务里没有的柜（模拟存量）；其余候选先标为已完成，免得把整个测试库推过去
    if not LIVE:
        legacy = "CABMIG" + tag
        sh("POST", "/api/ops/cabinets", {"cabinetNo": legacy, "sn": legacy, "vendorCode": "TEST", "slotTotal": 8}, admin)
        sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no='WH002', qc_status='PASSED' WHERE cabinet_no='{legacy}'")
        sql(f"INSERT INTO test_sharehub.inventory_sync_state (k, v) SELECT CONCAT('mig:CABINET:', cabinet_no), 'OUT' "
            f"FROM test_sharehub.dev_cabinet WHERE cabinet_no <> '{legacy}' ON DUPLICATE KEY UPDATE v = VALUES(v)")
        sql("INSERT INTO test_sharehub.inventory_sync_state (k, v) SELECT CONCAT('mig:POWERBANK:', powerbank_no), 'IN' "
            "FROM test_sharehub.dev_powerbank ON DUPLICATE KEY UPDATE v = VALUES(v)")
        st, plan, raw = sh("GET", "/api/ops/inventory/migration", token=admin)
        check("S7：试跑看得到 1 台待入库", st == 200 and plan["toInbound"] == 1, raw)
        st, res, raw = sh("POST", "/api/ops/inventory/migration", {"limit": 10}, admin)
        check("S7：执行一批成功 1 台", st == 200 and res["processed"] == 1 and res["failed"] == 0, raw)
        loc_b = sql("SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:WH002'")
        check("S7：库存服务在 WH002 对应位置有这台", any(x.get("serialNo") == legacy for x in inv(f"/serials?locationId={loc_b}")), loc_b)
        st, res, raw = sh("POST", "/api/ops/inventory/migration", {"limit": 10}, admin)
        check("S7：重跑不重复", st == 200 and res["processed"] == 0, raw)

    # 11. 物料（V210）：物料档案同步为库存服务物料；数量物料期初 → 领用；逐件物料采购 → 调拨 → 退供应商。账以库存服务为准
    st, mq, raw = sh("POST", "/api/ops/inventory/materials", {"name": "锁舌E2E" + tag, "category": "SPARE_PART", "trackingMode": "QUANTITY", "uom": "个"}, admin)
    mat = mq["materialNo"]
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "OPENING", "warehouseNo": "WH001", "note": "E2E 期初 " + tag,
        "lines": [{"materialNo": mat, "qty": 10}]}, wh)
    check("物料：期初入库（仓管）", st == 200 and d["kind"] == "INBOUND", raw)
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "REQUISITION", "warehouseNo": "WH001", "woNo": "WO-E2E-" + tag,
        "note": "E2E 领用 " + tag, "lines": [{"materialNo": mat, "qty": 11}]}, wh)
    check("物料：领用超过在手被拒（预检列出不够的数）", st == 409 and "不够出 11" in json.dumps(raw, ensure_ascii=False), raw)
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "REQUISITION", "warehouseNo": "WH001", "woNo": "WO-E2E-" + tag,
        "note": "E2E 领用 " + tag, "lines": [{"materialNo": mat, "qty": 3}]}, wh)
    check("物料：领用 3", st == 200, raw)
    item = sql(f"SELECT v FROM test_sharehub.inventory_sync_state WHERE k='mat:{mat}'")
    loc = sql("SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:WH001'")
    remote_qty = sum(float(b["onHand"]) for b in inv(f"/balances?locationId={loc}&itemId={item}"))
    st, bal, raw = sh("GET", f"/api/ops/inventory/material-balances?warehouseNo=WH001&materialNo={mat}", token=admin)
    check("物料库存：ShareHub = 库存服务 = 7", st == 200 and bal and bal[0]["onHand"] == 7 and remote_qty == 7, (bal, remote_qty))

    st, sp, raw = sh("POST", "/api/ops/inventory/suppliers", {"name": "E2E 供应商 " + tag}, admin)
    st, pq, raw = sh("POST", "/api/ops/inventory/materials", {"name": "POS-E2E" + tag, "category": "DEVICE", "trackingMode": "SERIAL", "uom": "台"}, admin)
    pos = pq["materialNo"]
    sns = ["POSE2E" + tag + "1", "POSE2E" + tag + "2"]
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "PURCHASE", "supplierNo": sp["supplierNo"], "warehouseNo": "WH001",
        "currency": "AED", "note": "E2E 采购 " + tag, "lines": [{"materialNo": pos, "serialNos": sns, "unitCost": 500}]}, wh)
    check("逐件物料：采购入库 2 台（带单价）", st == 200 and d["totalQty"] == 2, raw)
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "TRANSFER", "warehouseNo": "WH001", "toWarehouseNo": "WH002",
        "note": "E2E 调拨 " + tag, "lines": [{"materialNo": pos, "serialNos": [sns[0]]}]}, wh)
    loc2 = sql("SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:WH002'")
    check("逐件物料：调拨后序列号在库存服务的 WH002", st == 200 and any(x.get("serialNo") == sns[0] for x in inv(f"/serials?locationId={loc2}")), raw)
    st, d, raw = sh("POST", "/api/ops/inventory/material-docs", {"purpose": "RETURN_SUPPLIER", "supplierNo": sp["supplierNo"], "warehouseNo": "WH001",
        "note": "E2E 退货 " + tag, "lines": [{"materialNo": pos, "serialNos": [sns[1]]}]}, wh)
    check("逐件物料：退供应商（SUPPLIER 去向）后库存服务里没有了",
          st == 200 and not any(x.get("serialNo") == sns[1] for x in inv(f"/serials?locationId={loc}")), raw)
    st, _, raw = sh("POST", f"/api/ops/inventory/materials/{mat}/archive", {}, admin)
    check("物料：有库存不能归档", st == 409, raw)

    # 13. 仓到仓调拨：发货在库存服务调出、签收调入目的仓（少收的留在已调出）
    xa, xb = "CABXFA" + tag, "CABXFB" + tag
    for x in (xa, xb):
        sh("POST", "/api/ops/cabinets", {"cabinetNo": x, "sn": x, "vendorCode": "TEST", "slotTotal": 8}, admin)
        sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no=NULL WHERE cabinet_no='{x}'")
    st, ik, raw = sh("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH001", "sourceRef": "DN-XF-" + tag, "cabinetNos": [xa, xb]}, wh)
    no = ik["intake"]["intakeNo"]
    for x in (xa, xb):
        sh("POST", f"/api/ops/inventory/intakes/{no}/items/CABINET/{x}/qc", {"powerOn": True, "slotsOk": True}, wh)
    sh("POST", f"/api/ops/inventory/intakes/{no}/inbound", {"items": [{"itemType": "CABINET", "itemNo": x} for x in (xa, xb)]}, wh)
    st, t, raw = sh("POST", "/api/ops/inventory-transfers", {"fromType": "WAREHOUSE", "fromRef": "WH001", "toType": "WAREHOUSE",
        "toRef": "WH002", "itemType": "CABINET"}, wh)
    xno = t["transferNo"]
    sh("POST", f"/api/ops/inventory-transfers/{xno}/items", {"itemNos": [xa, xb]}, wh)
    st, d, raw = sh("POST", f"/api/ops/inventory-transfers/{xno}/ship", {}, wh)
    out1 = {x.get("serialNo") for x in inv(f"/serials?locationId={loc}")}
    check("调拨：发货后库存服务调出仓没有这两台", st == 200 and xa not in out1 and xb not in out1, raw)
    st, d, raw = sh("POST", f"/api/ops/inventory-transfers/{xno}/receive", {"receivedNos": [xa]}, wh)
    loc_x = sql("SELECT v FROM test_sharehub.inventory_sync_state WHERE k='wh:WH002'")
    in2 = {x.get("serialNo") for x in inv(f"/serials?locationId={loc_x}")}
    check("调拨：签收后实收的在目的仓、少收的不在", st == 200 and xa in in2 and xb not in in2, raw)

    # 12. 处置出库在 SERVICE 引擎下：报废同步库存服务（SCRAP 带原因码）
    scrap = "CABSCR" + tag
    sh("POST", "/api/ops/cabinets", {"cabinetNo": scrap, "sn": scrap, "vendorCode": "TEST", "slotTotal": 8}, admin)
    sql(f"UPDATE test_sharehub.dev_cabinet SET warehouse_no=NULL WHERE cabinet_no='{scrap}'")
    st, ik, raw = sh("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH001", "sourceRef": "DN-SCR-" + tag, "cabinetNos": [scrap]}, wh)
    no = ik["intake"]["intakeNo"]
    sh("POST", f"/api/ops/inventory/intakes/{no}/items/CABINET/{scrap}/qc", {"powerOn": True, "slotsOk": True}, wh)
    sh("POST", f"/api/ops/inventory/intakes/{no}/inbound", {"items": [{"itemType": "CABINET", "itemNo": scrap}]}, wh)
    st, d, raw = sh("POST", "/api/ops/inventory/disposals", {"kind": "SCRAP", "reasonCode": "BROKEN", "warehouseNo": "WH001",
        "note": "E2E 报废 " + tag, "cabinetNos": [scrap]}, wh)
    check("处置出库（SERVICE）：报废后库存服务里没有这台",
          st == 200 and not any(x.get("serialNo") == scrap for x in inv(f"/serials?locationId={loc}")), raw)
finally:
    if not LIVE:
        sql("UPDATE test_sharehub.sys_param SET value='LOCAL' WHERE param_key='inv.engine'")
    print(f"\n{sum(ok for _, ok, _ in results)}/{len(results)} 通过" + ("" if LIVE else "；引擎已复原 LOCAL"))

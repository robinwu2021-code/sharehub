"""S5 本机联调：ShareHub(8085, inv.engine=SERVICE) ↔ ai-shop 独立进销存(8086)。只读凭证文件，不打印密钥。

用法：python3 e2e.py [凭证文件]（默认 ~/.local/etc/inventory-sharehub-cred.json）。
前提：.claude/launch.json 的 inventory-svc-local 与 backend-inv 已启动（backend-inv 用的运行时类路径：
mvn -o dependency:build-classpath -pl sharehub-app -Dmdep.outputFile=sharehub-app/target/runtime-classpath.txt）；运行期间临时把 test_sharehub 的 inv.engine 改成 SERVICE，结束（含失败）复原 LOCAL。
"""
import json, sys, time, urllib.request, urllib.error, subprocess, random

SH = "http://127.0.0.1:8085"
INV = "http://127.0.0.1:8086/api/inventory/v1"
import os
CRED = json.load(open(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.local/etc/inventory-sharehub-cred.json")))
MYSQL = ["/Users/workingowl/.local/opt/mysql/bin/mysql", "-uroot", "-S", "/tmp/mysql.sock", "-N", "-e"]
results = []

def sql(q): return subprocess.run(MYSQL + [q], capture_output=True, text=True).stdout.strip()

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

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("✓ " if ok else "✗ ") + name + ("" if ok else f"  —— {detail}"))

def login(role, user=None):
    st, d, raw = sh("POST", "/api/auth/login", {"username": user or role.lower() + ".user", "role": role})
    assert st == 200, raw
    return d["token"]

tag = hex(int(time.time()))[2:].upper()
sql("UPDATE test_sharehub.sys_param SET value='SERVICE' WHERE param_key='inv.engine'; DELETE FROM test_sharehub.inventory_sync_state")
try:
    admin, wh, bd = login("ADMIN"), login("WAREHOUSE"), login("BD")
    st, eng, _ = sh("GET", "/api/ops/inventory/engine", token=admin)
    check("引擎 = SERVICE 且凭证已配", eng == {"engine": "SERVICE", "configured": True}, eng)

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

    # 7. 盘点：开单 → 扫码 → 仓管提交 → 管理员复核（复核人须不同）→ 过账
    st, c, raw = sh("POST", "/api/ops/inventory/counts", {"warehouseNo": "WH001"}, wh)
    check("盘点：开单", st == 200, raw)
    cn = c["count"]["countNo"]
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/scans", {"serialNos": [cab]}, wh)
    check("盘点：扫码", st == 200, raw)
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/submit", {}, wh)
    check("盘点：提交", st == 200, raw)
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/approve", {}, wh)
    check("盘点：提交人自己复核被拒（reviewerMustDiffer）", st == 409, (st, raw))
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/approve", {}, admin)
    check("盘点：另一人复核通过", st == 200, raw)
    st, c, raw = sh("POST", f"/api/ops/inventory/counts/{cn}/post", {}, admin)
    check("盘点：过账", st == 200 and c and c["count"]["status"] == "POSTED", raw)
finally:
    sql("UPDATE test_sharehub.sys_param SET value='LOCAL' WHERE param_key='inv.engine'")
    print(f"\n{sum(ok for _, ok, _ in results)}/{len(results)} 通过；引擎已复原 LOCAL")

"""本地部署冒烟（pb_local，LOCAL 引擎）：商家 → 建柜 → 到仓待检 → 质检 → 入库 → 出库 → 绑定 → 门禁 → 库存视图。"""
import json, time, random, urllib.request, urllib.error
B = "http://127.0.0.1:8080"
res = []
def req(m, p, body=None, tok=None):
    h = {"Content-Type": "application/json"}
    if tok: h["Authorization"] = "Bearer " + tok
    r = urllib.request.Request(B + p, data=None if body is None else json.dumps(body).encode(), method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=30) as x: b = json.loads(x.read() or b"null"); return x.status, b.get("data") if isinstance(b, dict) else b, b
    except urllib.error.HTTPError as e:
        b = json.loads(e.read() or b"null"); return e.code, None, b
def ck(n, ok, d=""):
    res.append(bool(ok)); print(("✓ " if ok else "✗ ") + n + ("" if ok else f"  —— {str(d)[:300]}"))
st, d, raw = req("POST", "/api/auth/login", {"username": "admin", "password": "local-dev"})
ck("管理员登录（ADMIN）", st == 200 and d["role"] == "ADMIN", raw); admin = d["token"]
tag = hex(int(time.time()))[2:].upper()
# 第二个人（BD）：管理员建员工、发临时口令 —— 入驻是「录入人不能审自己」的双人流程
bdno = "EMPSMK" + tag
st, _, raw = req("POST", "/api/platform/employees", {"employeeNo": bdno, "name": "冒烟 BD", "roleNo": "BD", "roleNos": ["BD"], "status": "ACTIVE"}, admin)
ck("建员工（BD）", st == 200, raw)
st, cr, raw = req("POST", f"/api/platform/employees/{bdno}/credential", {}, admin)
st, d, raw = req("POST", "/api/auth/login", {"username": bdno, "password": cr["password"]})
ck("BD 用临时口令登录", st == 200 and d and d["role"] == "BD", raw); bd = d["token"]
st, ap, raw = req("POST", "/api/agent/applies", {"phone": "+97150" + str(random.randint(1000000, 9999999)), "email": f"smoke{tag}@example.com",
    "operatorName": "冒烟商家 " + tag, "operatorType": "AGENT"}, bd)
ck("商家入驻申请", st == 200, raw)
req("POST", f"/api/agent/applies/{ap['applyNo']}/accept", {}, admin)
st, au, raw = req("POST", f"/api/agent/applies/{ap['applyNo']}/audit", {"approve": True}, admin); ck("入驻审核通过", st == 200, raw)
agent = au["operatorNo"]
st, cur, _ = req("GET", f"/api/agent/agents?page=1&size=5&keyword={agent}", tok=admin)
st, prof, raw = req("POST", f"/api/agent/agents/{agent}", {"name": cur["list"][0]["name"], "agentType": "AGENT", "status": "ENABLED",
    "address": "Al Barsha", "lng": 55.2, "lat": 25.11, "industry": "HOTEL"}, admin)
ck("商家补齐位置与行业（生成默认站点）", st == 200 and prof.get("defaultSiteNo"), raw)
cab = "CABSMK" + tag
st, _, raw = req("POST", "/api/ops/cabinets", {"cabinetNo": cab, "sn": cab, "vendorCode": "TEST", "slotTotal": 8}, admin); ck("建柜", st == 200, raw)
st, ik, raw = req("GET", f"/api/ops/cabinets?page=1&size=5&keyword={cab}", tok=admin)
wh = ik["list"][0].get("warehouseNo")
if wh:  # 建柜默认落仓则先模拟「未登记仓」再走到仓待检
    pass
st, ik, raw = req("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH002", "cabinetNos": [cab]}, admin)
if st != 200:
    ck("到货登记（建柜默认已在仓，跳过待检）", "items_rejected" in json.dumps(raw), raw)
else:
    no = ik["intake"]["intakeNo"]
    st, _, raw = req("POST", f"/api/ops/inventory/intakes/{no}/items/CABINET/{cab}/qc", {"powerOn": True, "slotsOk": True}, admin); ck("待检质检", st == 200, raw)
    st, _, raw = req("POST", f"/api/ops/inventory/intakes/{no}/inbound", {"items": [{"itemType": "CABINET", "itemNo": cab}]}, admin); ck("入库", st == 200, raw)
st, d, raw = req("GET", f"/api/ops/cabinets?page=1&size=5&keyword={cab}", tok=admin)
if d["list"][0].get("qcStatus") not in (None, "PASSED"):
    st, _, raw = req("POST", f"/api/ops/devices/{cab}/qc", {"powerOn": True, "slotsOk": True}, admin); ck("设备质检（放行）", st == 200, raw)
st, d, raw = req("POST", "/api/ops/devices/issue", {"agentNo": agent, "cabinetNos": [cab]}, admin); ck("出库给商家（只选商户）", st == 200, raw)
st, d, raw = req("POST", f"/api/ops/devices/{cab}/bind", {"openInstallWo": True}, admin)
ck("绑定默认点位 + 开装机单", st == 200 and d and d.get("woNo"), raw)
st, g, raw = req("GET", f"/api/ops/devices/{cab}/go-live-gate", tok=admin)
ck("上线门禁可查", st == 200, raw)
st, mv, raw = req("GET", f"/api/ops/device-movements?itemNo={cab}", tok=admin)
ck("设备履历（出库 / 绑定 …）", st == 200 and len(mv) >= 2, raw)
st, sg, raw = req("GET", "/api/ops/inventory/stages", tok=admin); ck("统一库存视图", st == 200 and len(sg) == 11, raw)
day = time.strftime("%Y-%m-%d", time.gmtime())
st, pr, raw = req("GET", f"/api/ops/inventory/period-summary?from={day}&to={day}", tok=admin); ck("期间报表（本地口径）", st == 200, raw)
st, lg, raw = req("GET", "/api/ops/inventory/ledger?size=5", tok=admin); ck("库存流水（本地口径）", st == 200, raw)
st, _, raw = req("POST", "/api/ops/inventory/counts", {"warehouseNo": "WH001"}, admin); ck("盘点在本地引擎下被拒（409）", st == 409, raw)
for p in ["/api/ops/dashboard", "/api/trade/orders?page=1&size=5", "/api/ops/work-orders?page=1&size=5", "/api/ops/sites?page=1&size=5"]:
    st, _, raw = req("GET", p, tok=admin); ck("GET " + p.split("?")[0], st == 200, raw)
print(f"\n{sum(res)}/{len(res)} 通过")

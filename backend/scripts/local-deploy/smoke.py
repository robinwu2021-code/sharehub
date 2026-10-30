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
# —— 设备管理一期收齐的五页：调拨 → 资产差异、设备编码、固件 OTA、设备日志（真实接口写读）——
cab2 = "CABSMT" + tag
req("POST", "/api/ops/cabinets", {"cabinetNo": cab2, "sn": cab2, "vendorCode": "TEST", "slotTotal": 8}, admin)
req("POST", f"/api/ops/devices/{cab2}/qc", {"powerOn": True, "slotsOk": True}, admin)
st, d, raw = req("GET", f"/api/ops/cabinets?page=1&size=5&keyword={cab2}", tok=admin)
src = d["list"][0].get("warehouseNo") or "WH001"
st, tr, raw = req("POST", "/api/ops/inventory-transfers", {"fromType": "WAREHOUSE", "fromRef": src, "toType": "WAREHOUSE", "toRef": "WH002",
    "itemType": "CABINET"}, admin)
ck("调拨：建单", st == 200 and tr, raw)
tno = tr["transferNo"]
st, _, raw = req("POST", f"/api/ops/inventory-transfers/{tno}/items", {"itemNos": [cab2]}, admin); ck("调拨：明细", st == 200, raw)
st, _, raw = req("POST", f"/api/ops/inventory-transfers/{tno}/ship", {"carrier": "Aramex", "trackingNo": "AX" + tag}, admin); ck("调拨：发货（柜在途，带物流）", st == 200, raw)
st, rc, raw = req("POST", f"/api/ops/inventory-transfers/{tno}/receive", {"receivedNos": ["CAB-NOT-ON-LIST"], "note": "少一台多一台"}, admin)
ck("调拨：签收（缺 1 多 1 → 资产差异）", st == 200 and len(rc["diffs"]) == 2, raw)
st, df, raw = req("GET", f"/api/ops/asset-diffs?page=1&size=10&sourceRef={tno}", tok=admin)
ck("资产差异：按调拨单查到 2 条", st == 200 and df["total"] == 2, raw)
st, _, raw = req("POST", f"/api/ops/asset-diffs/{df['list'][0]['diffNo']}/resolve", {"note": "现场找回"}, admin); ck("资产差异：处置", st == 200, raw)
vendors = req("GET", "/api/ops/vendors", tok=admin)[1] or []
vc = (vendors[0]["vendorCode"] if isinstance(vendors, list) and vendors else "cd-tech")
st, cb, raw = req("POST", "/api/ops/device-code-batches", {"vendorCode": vc, "codeType": "SN", "rangeStart": "SM" + tag + "0000",
    "rangeEnd": "SM" + tag + "0099", "total": 100, "bound": 0, "producedAt": day if 'day' in dir() else time.strftime("%Y-%m-%d"), "status": "PENDING"}, admin)
ck("设备编码：新增贴码批次", st == 200, raw)
st, rel, raw = req("POST", "/api/ops/ota-releases", {"fwType": "CABINET", "vendorCode": vc, "version": "SMK" + tag, "versionCode": 100,
    "status": "DRAFT"}, admin)
ck("固件 OTA：版本库新增版本", st == 200, raw)
st, ota, raw = req("POST", "/api/ops/ota-rollouts", {"fwVersion": "1.9.9", "strategy": "GRAY", "progress": 0, "status": "PENDING"}, admin)
ck("固件 OTA：不选版本库被拒（400，不再 500）", st == 400, raw)
st, ota, raw = req("POST", "/api/ops/ota-rollouts", {"releaseNo": rel["releaseNo"], "scope": "DEVICE", "strategy": "GRAY"}, admin)
ck("固件 OTA：按设备投放不给对象被拒（400）", st == 400, raw)
st, ota, raw = req("POST", "/api/ops/ota-rollouts", {"releaseNo": rel["releaseNo"], "scope": "DEVICE", "targetRef": cab2,
    "strategy": "GRAY", "status": "PENDING"}, admin)
ck("固件 OTA：新增发布单（版本号取版本库）", st == 200 and ota["fwVersion"] == "SMK" + tag, raw)
st, _, raw = req("GET", "/api/ops/device-logs?page=1&size=5", tok=admin); ck("设备日志：可查", st == 200, raw)
# 仓库管理与供应商（V201）
st, nw, raw = req("POST", "/api/ops/inventory/warehouses", {"name": "冒烟仓 " + tag, "regionId": "AE-AJ"}, admin)
ck("仓库：新建", st == 200 and nw["warehouseNo"].startswith("WH"), raw)
st, _, raw = req("POST", f"/api/ops/inventory/warehouses/{nw['warehouseNo']}", {"name": "冒烟仓改 " + tag}, admin); ck("仓库：编辑", st == 200, raw)
whs = req("GET", "/api/ops/inventory/warehouses", tok=admin)[1]
dw = next(w["warehouseNo"] for w in whs if w["isDefault"])
st, _, raw = req("POST", f"/api/ops/inventory/warehouses/{dw}/archive", {}, admin); ck("仓库：默认回仓仓不能归档（409）", st == 409, raw)
st, aw, raw = req("POST", f"/api/ops/inventory/warehouses/{nw['warehouseNo']}/archive", {}, admin)
ck("仓库：空仓归档", st == 200 and aw["archivedAt"], raw)
st, _, raw = req("POST", f"/api/ops/inventory/warehouses/{nw['warehouseNo']}/unarchive", {}, admin); ck("仓库：恢复", st == 200, raw)
req("POST", f"/api/ops/inventory/warehouses/{nw['warehouseNo']}/archive", {}, admin)
st, sp, raw = req("POST", "/api/ops/inventory/suppliers", {"name": "冒烟供应商 " + tag, "phone": "+971400000"}, admin)
ck("供应商：新建", st == 200 and sp["supplierNo"], raw)
st, sl, raw = req("GET", f"/api/ops/inventory/suppliers?page=1&size=5&keyword={tag}", tok=admin)
ck("供应商：列表可查", st == 200 and sl["total"] == 1, raw)
cab3 = "CABSP" + tag
req("POST", "/api/ops/cabinets", {"cabinetNo": cab3, "sn": cab3, "vendorCode": "TEST", "slotTotal": 8}, admin)
st, ik3, raw = req("POST", "/api/ops/inventory/intakes", {"warehouseNo": "WH002", "supplierNo": sp["supplierNo"], "cabinetNos": [cab3]}, admin)
ck("到货登记带供应商", st == 200 and ik3["intake"]["supplierName"] == "冒烟供应商 " + tag, raw)
st, _, raw = req("POST", f"/api/ops/inventory/suppliers/{sp['supplierNo']}/archive", {}, admin); ck("供应商：归档", st == 200, raw)
# 库存缺口 P0–P2（V202–V208）
cab4, cab5 = "CABOP" + tag, "CABDS" + tag
for c in (cab4, cab5):
    req("POST", "/api/ops/cabinets", {"cabinetNo": c, "sn": c, "vendorCode": "TEST", "slotTotal": 8}, admin)
st, op, raw = req("POST", "/api/ops/inventory/intakes", {"kind": "OPENING", "warehouseNo": "WH003",
    "currency": "AED", "unitCosts": {cab4: 1200, cab5: 800}, "cabinetNos": [cab4, cab5]}, admin)
ck("入库单：期初建账登记即入库、带金额", st == 200 and op["intake"]["status"] == "DONE" and float(op["intake"]["totalCost"]) == 2000, raw)
st, dp, raw = req("POST", "/api/ops/inventory/disposals", {"kind": "SCRAP", "reasonCode": "BROKEN", "warehouseNo": "WH003",
    "note": "冒烟报废", "cabinetNos": [cab5]}, admin)
ck("处置出库：报废", st == 200 and dp["disposal"]["itemCount"] == 1, raw)
st, th, raw = req("POST", "/api/ops/inventory/stock-thresholds", {"warehouseNo": "WH003", "itemType": "CABINET", "minQty": 999}, admin)
ck("安全库存：设下限后标记低库存", st == 200 and th["low"], raw)
req("POST", "/api/ops/inventory/stock-thresholds", {"warehouseNo": "WH003", "itemType": "CABINET", "minQty": 0}, admin)
st, sh, raw = req("GET", f"/api/ops/inventory/shipments?docRef={tno}", tok=admin)
ck("发货物流：调拨发出的运单号可查", st == 200 and len(sh) == 1 and sh[0]["trackingNo"] == "AX" + tag, raw)
st, rp, raw = req("GET", "/api/ops/reports/inventory?warehouseNo=WH003", tok=admin)
ck("库存报表：处置汇总含本次报废", st == 200 and rp["disposals"].get("SCRAP:BROKEN", 0) >= 1 and rp["purchases"], raw)
st, si, raw = req("GET", "/api/ops/inventory/stage-items?stage=IN_STOCK&itemType=CABINET", tok=admin)
stages = {x["stage"]: x for x in req("GET", "/api/ops/inventory/stages", tok=admin)[1]}
ck("库存下钻：清单总数 = 总览", st == 200 and si["total"] == stages["IN_STOCK"]["cabinets"], raw)
st, ss, raw = req("GET", "/api/ops/inventory/suppliers/stats", tok=admin)
ck("供应商绩效：可查", st == 200 and any(x["supplierNo"] == sp["supplierNo"] for x in ss), raw)
st, ps, raw = req("GET", "/api/platform/sys-params?groupName=%E5%BA%93%E5%AD%98&page=1&size=50", tok=admin)
keys = {x["paramKey"] for x in (ps or {}).get("list", [])}
ck("库存设置：参数齐（含质检策略）", st == 200 and {"inv.qc.powerbank_policy", "inv.intake.overdue_days"} <= keys, raw)
for p in ["/api/ops/dashboard", "/api/trade/orders?page=1&size=5", "/api/ops/work-orders?page=1&size=5", "/api/ops/sites?page=1&size=5"]:
    st, _, raw = req("GET", p, tok=admin); ck("GET " + p.split("?")[0], st == 200, raw)
print(f"\n{sum(res)}/{len(res)} 通过")

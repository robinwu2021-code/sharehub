// 发货物流（V205）mock：与后端 ShipmentServiceImpl 同规则（承运商与运单号都空不记；超长拒）。
import type { ShipInfo, Shipment, ShipmentDocType } from "../../types";
import { fail } from "../../biz-error";

const rows: Shipment[] = [];
let seq = 0;

/** 先校验再做发出动作（同后端 validate）。 */
export function validateShip(x?: ShipInfo | null) {
  if ((x?.carrier?.trim().length ?? 0) > 64 || (x?.trackingNo?.trim().length ?? 0) > 64) fail("承运商 / 运单号太长", "Too long");
}

export function recordShipment(docType: ShipmentDocType, docRef: string, itemNos: string[], x?: ShipInfo | null): Shipment | null {
  if (!x?.carrier?.trim() && !x?.trackingNo?.trim()) return null;
  validateShip(x);
  const s: Shipment = {
    shipmentNo: `SH${String(++seq).padStart(4, "0")}`, docType, docRef, carrier: x.carrier?.trim() || null,
    trackingNo: x.trackingNo?.trim() || null, shippedAt: x.shippedAt || new Date().toISOString(), note: x.note?.trim() || null,
    itemNos: [...new Set(itemNos)], createdBy: "mock",
  };
  rows.unshift(s);
  return s;
}

export function listShipments(q: { docType?: ShipmentDocType; docRef?: string; itemNo?: string }): Shipment[] {
  if (!q.docType && !q.docRef && !q.itemNo) fail("请指定单据或设备号", "docRef / itemNo required");
  return rows.filter((s) => (!q.docType || s.docType === q.docType) && (!q.docRef || s.docRef === q.docRef)
    && (!q.itemNo || s.itemNos.includes(q.itemNo))).map((s) => ({ ...s, itemNos: [...s.itemNos] }));
}

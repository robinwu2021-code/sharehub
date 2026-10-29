"use client";

// 发货物流（V205）：随调拨发出 / 出库给商家 / 退供应商一起填，可空（自提、当面交付不记）。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import type { ShipInfo, Shipment, ShipmentDocType } from "@/lib/types";
import { Field } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { DataTable, type Column } from "@/components/ui/data-table";

/** 承运商 / 运单号两格（受控）。 */
export function ShipFields({ value, onChange }: { value: ShipInfo; onChange: (v: ShipInfo) => void }) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <Field label="承运商（可选）">
        <Input aria-label="承运商" maxLength={64} placeholder="Aramex / DHL / 自有车辆" value={value.carrier ?? ""}
          onChange={(e) => onChange({ ...value, carrier: e.target.value })} />
      </Field>
      <Field label="运单号（可选）">
        <Input aria-label="运单号" maxLength={64} value={value.trackingNo ?? ""}
          onChange={(e) => onChange({ ...value, trackingNo: e.target.value })} />
      </Field>
    </div>
  );
}

/** 一张单据上记过的物流（没有就不渲染）。 */
export function ShipmentList({ docType, docRef }: { docType: ShipmentDocType; docRef: string | null | undefined }) {
  const q = useQuery({
    queryKey: ["shipments", docType, docRef], enabled: !!docRef,
    queryFn: () => api.listShipments({ docType, docRef: docRef! }),
  });
  if (!q.data?.length && !q.error) return null;
  const cols: Column<Shipment>[] = [
    { header: "承运商", className: "whitespace-nowrap", cell: (s) => s.carrier ?? "-" },
    { header: "运单号", cell: (s) => <span className="font-mono">{s.trackingNo ?? "-"}</span> },
    { header: "件数", className: "text-right tabular-nums whitespace-nowrap", cell: (s) => s.itemNos.length },
    { header: "发出", className: "whitespace-nowrap", cell: (s) => fmtTime(s.shippedAt) },
  ];
  return (
    <Field label="物流">
      <DataTable rowKey={(s: Shipment) => s.shipmentNo} columns={cols} rows={q.data} loading={q.isLoading}
        error={q.error} onRetry={q.refetch} empty="无" />
    </Field>
  );
}

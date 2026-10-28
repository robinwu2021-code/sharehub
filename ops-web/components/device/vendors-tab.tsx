"use client";

// 供应商接入（硬件厂商目录 · OM03-17）。
//
// 2026-10-28 从「系统设置」搬到「设备管理 › 设备接入」（docs/requirements/运营端菜单清单-按运营顺序.md）：
// 新厂商的设备要先在这里接入才能上线，它是设备布放（P4）的前置一步，不是系统参数。
// 内容原样搬出（查询 / 配置抽屉 / 连通性测试），行为不变；系统页不再留第二份。
//
// 与「采购供应商」不是一回事：这里是**和谁的协议对接**（driver、接入方式、能力），
// 采购供应商是**向谁买货**（OM03-05，待后端补实体）。
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { EnabledBadge } from "@/components/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type { Vendor, AccessMode, VendorProbeResult } from "@/lib/types";

export const ACCESS_MODE_LABEL: Record<AccessMode, string> = { TCP: "TCP 私有协议", MQTT: "MQTT 直连", HTTP_API: "HTTP 云对接" };

export function VendorsTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();

  // 非分页列表；配置走行内抽屉，无搜索 / 新增（厂商由平台管理员登记 driver）
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const [edit, setEdit] = useState<Vendor | null>(null);
  const [form, setForm] = useState<Partial<Vendor>>({});
  const save = useMutation({
    mutationFn: (v: Partial<Vendor> & { vendorCode: string }) => api.saveVendor(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["vendors"] }); setEdit(null); },
  });
  function openVendor(v: Vendor) { setEdit(v); setForm(v); }

  // 连通性测试：探测不改配置，结果落抽屉（一句话结论 + 原始信息）。
  // 探测权限用 device:vendor:read 而非 :config —— 排障是运维日常，不该要改配置的权。
  const canVendorProbe = allow("device:vendor:read");
  const canConfig = allow("device:vendor:config");
  const [probe, setProbe] = useState<VendorProbeResult | null>(null);
  const [probingCode, setProbingCode] = useState<string | null>(null);
  const testVendor = useMutation({
    mutationFn: (code: string) => api.testVendorConnectivity(code),
    onSuccess: (r) => {
      setProbe(r);
      // 失败结论不用 success 提示条 —— 结论本身要带情绪，否则运维会以为「测过就是通了」
      if (r.ok) notify.success(`${r.vendorCode} ${r.message}`); else notify.error(`${r.vendorCode} 探测失败：${r.message}`);
    },
    onSettled: () => setProbingCode(null),
  });

  const cols: Column<Vendor>[] = [
    { header: "供应商码", cell: (v) => <span className="font-medium">{v.vendorCode}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "接入方式", cell: (v) => <Badge tone="outline">{ACCESS_MODE_LABEL[v.accessMode]}</Badge> },
    { header: "设备数", cell: (v) => <span className="tabular-nums">{v.deviceCount}</span> },
    { header: "状态", cell: (v) => <EnabledBadge on={v.status === "ENABLED"} /> },
    {
      header: "操作",
      cell: (v) => (
        <div className="flex gap-2">
          {canConfig && <Button size="sm" variant="outline" onClick={() => openVendor(v)}>配置</Button>}
          {canVendorProbe && (
            <Button
              size="sm"
              variant="outline"
              disabled={testVendor.isPending}
              onClick={() => { setProbingCode(v.vendorCode); testVendor.mutate(v.vendorCode); }}
            >
              {probingCode === v.vendorCode ? "探测中…" : "连通性测试"}
            </Button>
          )}
          {!canConfig && !canVendorProbe && <span className="text-muted-foreground">-</span>}
        </div>
      ),
    },
  ];

  return (
    <>
      <Toolbar
        onExport={() => exportCsv<Vendor>("供应商接入", [
          { header: "供应商码", value: (v) => v.vendorCode },
          { header: "名称", value: (v) => v.name },
          { header: "接入方式", value: (v) => ACCESS_MODE_LABEL[v.accessMode] },
          { header: "设备数", value: (v) => v.deviceCount },
          { header: "状态", value: (v) => (v.status === "ENABLED" ? "启用" : "停用") },
        ], vendorsQ.data ?? [])}
      />
      <DataTable
        rowKey={(v: Vendor) => v.vendorCode}
        columns={cols}
        rows={vendorsQ.data}
        loading={vendorsQ.isLoading} error={vendorsQ.error} onRetry={vendorsQ.refetch}
        empty="暂无供应商——还没有硬件厂商注册 driver，设备台账将无法接入，请先由平台管理员登记供应商"
      />

      <Drawer
        open={!!edit}
        onOpenChange={(o) => !o && setEdit(null)}
        title={`配置供应商 ${edit?.vendorCode ?? ""}`}
        desc="driver 接入参数（mock 保存到内存；接后端写 gw_vendor_config）"
        footer={
          <>
            <Button variant="outline" onClick={() => setEdit(null)}>取消</Button>
            <Button onClick={() => form.vendorCode && save.mutate(form as Vendor)} disabled={save.isPending}>保存</Button>
          </>
        }
      >
        <Field label="名称"><Input value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="接入方式">
          <Select className="w-full" value={form.accessMode ?? "HTTP_API"} onChange={(e) => setForm({ ...form, accessMode: e.target.value as AccessMode })}>
            <option value="TCP">TCP 私有协议</option>
            <option value="MQTT">MQTT 直连</option>
            <option value="HTTP_API">HTTP 云对接</option>
          </Select>
        </Field>
        <Field label="API 基址（云对接型）"><Input value={form.apiBase ?? ""} onChange={(e) => setForm({ ...form, apiBase: e.target.value })} placeholder="https://api.vendor.example" /></Field>
        <Field label="状态">
          <Select className="w-full" value={form.status ?? "ENABLED"} onChange={(e) => setForm({ ...form, status: e.target.value as Vendor["status"] })}>
            <option value="ENABLED">启用</option>
            <option value="DISABLED">停用</option>
          </Select>
        </Field>
      </Drawer>

      {/* 连通性测试结果：只读抽屉。结论 + 原始信息分开摆——前者给判断，后者给厂商对质 */}
      <Drawer
        open={!!probe}
        onOpenChange={(o) => !o && setProbe(null)}
        title={`连通性测试 ${probe?.vendorCode ?? ""}`}
        desc="仅探测，不改任何接入配置"
        footer={<Button variant="outline" onClick={() => setProbe(null)}>{t("common.cancel")}</Button>}
      >
        {probe && (
          <>
            <Field label="结论">
              <Badge tone={probe.ok ? "success" : "danger"}>{probe.ok ? "连通" : "不通"}</Badge>
            </Field>
            <Field label="探测目标"><span className="tabular-nums">{probe.endpoint || "-"}</span></Field>
            <Field label="往返延迟">{probe.ok ? <span className="tabular-nums">{probe.latencyMs} ms</span> : <span className="text-muted-foreground">-</span>}</Field>
            <Field label="探测时间">{fmtTime(probe.checkedAt)}</Field>
            <Field label="说明">{probe.message}</Field>
            <Field label="原始信息">
              <div className="rounded-card bg-muted px-3.5 py-2 text-sm">{probe.detail}</div>
            </Field>
          </>
        )}
      </Drawer>
    </>
  );
}

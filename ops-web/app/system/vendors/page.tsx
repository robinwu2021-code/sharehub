"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle } from "@/components/ui/misc";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, Field } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { useCan } from "@/lib/use-can";
import type { Vendor, AccessMode } from "@/lib/types";

const MODE_LABEL: Record<AccessMode, string> = { TCP: "TCP 私有协议", MQTT: "MQTT 直连", HTTP_API: "HTTP 云对接" };

export default function VendorsPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { data, isLoading } = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const [edit, setEdit] = useState<Vendor | null>(null);
  const [form, setForm] = useState<Partial<Vendor>>({});

  const save = useMutation({
    mutationFn: (v: Partial<Vendor> & { vendorCode: string }) => api.saveVendor(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["vendors"] }); setEdit(null); },
  });

  function open(v: Vendor) { setEdit(v); setForm(v); }

  const cols: Column<Vendor>[] = [
    { header: "供应商码", cell: (v) => <span className="font-medium">{v.vendorCode}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "接入方式", cell: (v) => <Badge tone="outline">{MODE_LABEL[v.accessMode]}</Badge> },
    { header: "设备数", cell: (v) => <span className="tabular-nums">{v.deviceCount}</span> },
    { header: "状态", cell: (v) => v.status === "ENABLED" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "操作", cell: (v) => allow("device:vendor:config") ? <Button size="sm" variant="outline" onClick={() => open(v)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <div>
      <PageTitle title="供应商接入" desc="硬件供应商 driver 注册、密钥与回调配置（对应 access-gateway gw_vendor）" />
      <DataTable rowKey={(v: Vendor) => v.vendorCode} columns={cols} rows={data} loading={isLoading} />

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
    </div>
  );
}

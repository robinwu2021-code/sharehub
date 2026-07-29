"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { Vendor, AccessMode, NotifyTemplate, DictEntry, Region, SysParam, OpenApiApp, MarketCountry, PaymentChannel, PageResult } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "vendors", label: "供应商接入" },
  // 支付渠道：竞品 7 个渠道各占一菜单，我们合并为一页（列表 + 各自配置抽屉）
  { key: "payment", label: "支付渠道" },
  { key: "notify", label: "通知模板" },
  { key: "dict", label: "参数字典" },
  { key: "region", label: "地区库" },
  { key: "params", label: "系统参数" },
  { key: "markets", label: "多国家市场", phase: 3 as const },
  { key: "openapi", label: "OpenAPI 应用", phase: 3 as const },
];

const MODE_LABEL: Record<AccessMode, string> = { TCP: "TCP 私有协议", MQTT: "MQTT 直连", HTTP_API: "HTTP 云对接" };
const CHANNEL_LABEL: Record<NotifyTemplate["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "推送", WHATSAPP: "WhatsApp" };
const LANG_LABEL: Record<NotifyTemplate["lang"], string> = { ar: "阿拉伯语", en: "英语" };

const NOTIFY_FIELDS: FieldDef[] = [
  { key: "templateNo", label: "模板号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "订单完成通知" },
  { key: "channel", label: "渠道", type: "select", options: [{ value: "SMS", label: "短信" }, { value: "EMAIL", label: "邮件" }, { value: "PUSH", label: "推送" }, { value: "WHATSAPP", label: "WhatsApp" }] },
  { key: "lang", label: "语言", type: "select", options: [{ value: "ar", label: "阿拉伯语" }, { value: "en", label: "英语" }] },
  { key: "status", label: "状态", type: "select", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
];

const DICT_FIELDS: FieldDef[] = [
  { key: "dictNo", label: "字典号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "group", label: "分组", placeholder: "order_status" },
  { key: "code", label: "编码", placeholder: "PAID" },
  { key: "label", label: "标签", placeholder: "已支付" },
  { key: "sort", label: "排序", type: "number" },
  { key: "enabled", label: "启用", type: "switch" },
];

const REGION_FIELDS: FieldDef[] = [
  { key: "regionId", label: "区域 ID", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "迪拜" },
  { key: "parent", label: "上级区域", placeholder: "阿联酋" },
  { key: "level", label: "层级", type: "number" },
  { key: "cityCount", label: "城市数", type: "number" },
];

const PARAM_FIELDS: FieldDef[] = [
  { key: "paramKey", label: "参数键", readOnlyOnEdit: true, placeholder: "order.timeout.minutes" },
  { key: "label", label: "说明", placeholder: "订单超时分钟数" },
  { key: "value", label: "取值", placeholder: "30" },
  { key: "groupName", label: "分组", placeholder: "订单" },
];

// 密钥类字段一律 password 型 + 掩码占位，前端永不承载真实密钥（真实值仅后端保管）
const PAYMENT_FIELDS: FieldDef[] = [
  { key: "channelCode", label: "渠道码", readOnlyOnEdit: true, placeholder: "NEARPAY / STRIPE / PAYPAL" },
  { key: "channelName", label: "渠道名称", placeholder: "NearPay（聚合收单）" },
  { key: "mode", label: "接入模式", type: "select", options: [{ value: "DELEGATED", label: "委托" }, { value: "DIRECT", label: "直连" }] },
  { key: "status", label: "状态", type: "select", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "countries", label: "适用国家", placeholder: "AE,SA" },
  { key: "currencies", label: "币种", placeholder: "AED,SAR" },
  { key: "capabilities", label: "能力", placeholder: "支付,退款,预授权,分账" },
  { key: "apiBase", label: "API 基址", placeholder: "https://api.nearpay.example" },
  { key: "merchantId", label: "商户号", placeholder: "MID-AE-100286" },
  { key: "apiKeyMasked", label: "API 密钥（掩码）", type: "password", placeholder: "sk_test_****" },
];

const OPENAPI_FIELDS: FieldDef[] = [
  { key: "appNo", label: "应用号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "合作方对接" },
  { key: "appKey", label: "AppKey", placeholder: "ak_xxx" },
  { key: "rateLimit", label: "限流（次/秒）", type: "number" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
];

function SystemInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "vendors");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  // —— 供应商接入（Vendor[]，非分页；含配置抽屉，保留）——
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors(), enabled: tab === "vendors" });
  const [edit, setEdit] = useState<Vendor | null>(null);
  const [form, setForm] = useState<Partial<Vendor>>({});
  const save = useMutation({
    mutationFn: (v: Partial<Vendor> & { vendorCode: string }) => api.saveVendor(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["vendors"] }); setEdit(null); },
  });
  function openVendor(v: Vendor) { setEdit(v); setForm(v); }

  // —— 编辑门控与表单 state ——
  const canNotify = allow("system:notify_template:update");
  const canDict = allow("system:dict:update");
  const canRegion = allow("system:region:update");
  const canParam = allow("system:param:update");
  const canOpenapi = allow("system:openapi:update");
  const canPayment = allow("system:payment_channel:update");

  const [notifyForm, setNotifyForm] = useState<Partial<NotifyTemplate> | null>(null);
  const [dictForm, setDictForm] = useState<Partial<DictEntry> | null>(null);
  const [regionForm, setRegionForm] = useState<Partial<Region> | null>(null);
  const [paramForm, setParamForm] = useState<Partial<SysParam> | null>(null);
  const [openapiForm, setOpenapiForm] = useState<Partial<OpenApiApp> | null>(null);
  const [paymentForm, setPaymentForm] = useState<Partial<PaymentChannel> | null>(null);

  const onSaved = (setter: (v: null) => void) => () => { qc.invalidateQueries({ queryKey: ["sys"] }); notify.success(t("common.success")); setter(null); };
  const saveNotify = useMutation({ mutationFn: (v: Partial<NotifyTemplate>) => api.saveNotifyTemplate(v), onSuccess: onSaved(setNotifyForm) });
  const saveDict = useMutation({ mutationFn: (v: Partial<DictEntry>) => api.saveDictEntry(v), onSuccess: onSaved(setDictForm) });
  const saveRegion = useMutation({ mutationFn: (v: Partial<Region>) => api.saveRegion(v), onSuccess: onSaved(setRegionForm) });
  const saveParam = useMutation({ mutationFn: (v: Partial<SysParam>) => api.saveSysParam(v), onSuccess: onSaved(setParamForm) });
  const saveOpenapi = useMutation({ mutationFn: (v: Partial<OpenApiApp>) => api.saveOpenApiApp(v), onSuccess: onSaved(setOpenapiForm) });
  const savePayment = useMutation({ mutationFn: (v: Partial<PaymentChannel>) => api.savePaymentChannel(v), onSuccess: onSaved(setPaymentForm) });

  // —— 其余分页 tab ——
  const q = useQuery<PageResult<NotifyTemplate | DictEntry | Region | SysParam | OpenApiApp | MarketCountry | PaymentChannel>>({
    queryKey: ["sys", tab, page, keyword],
    queryFn: () =>
      tab === "payment" ? api.listPaymentChannels({ page, size: SIZE, keyword })
      : tab === "notify" ? api.listNotifyTemplates({ page, size: SIZE, keyword })
      : tab === "dict" ? api.listDictEntries({ page, size: SIZE, keyword })
      : tab === "region" ? api.listRegions({ page, size: SIZE, keyword })
      : tab === "params" ? api.listSysParams({ page, size: SIZE, keyword })
      : tab === "markets" ? api.listMarketCountries({ page, size: SIZE, keyword })
      : api.listOpenApiApps({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab !== "vendors",
  });

  const MARKET_STATUS: Record<MarketCountry["status"], { label: string; tone: "success" | "outline" | "muted" }> = {
    LIVE: { label: "已开城", tone: "success" },
    PILOT: { label: "试点", tone: "outline" },
    PLANNED: { label: "规划", tone: "muted" },
  };
  const marketCols: Column<MarketCountry>[] = [
    { header: "国家", cell: (m) => <span className="font-medium">{m.name}（{m.countryCode}）</span> },
    { header: "币种", cell: (m) => <Badge tone="outline">{m.currency}</Badge> },
    { header: "时区", cell: (m) => <span className="text-muted-foreground">{m.timezone}</span> },
    { header: "合规主体", cell: (m) => <span className="text-muted-foreground">{m.compliance}</span> },
    { header: "开城数", cell: (m) => <span className="tabular-nums">{m.cityCount}</span> },
    { header: "状态", cell: (m) => <Badge tone={MARKET_STATUS[m.status].tone}>{MARKET_STATUS[m.status].label}</Badge> },
  ];

  const paymentCols: Column<PaymentChannel>[] = [
    { header: "渠道码", cell: (c) => <span className="font-medium">{c.channelCode}</span> },
    { header: "名称", cell: (c) => c.channelName },
    { header: "模式", cell: (c) => <Badge tone="outline">{c.mode === "DELEGATED" ? "委托" : "直连"}</Badge> },
    { header: "适用国家", cell: (c) => <span className="tabular-nums">{c.countries}</span> },
    { header: "币种", cell: (c) => <span className="tabular-nums">{c.currencies}</span> },
    // 能力矩阵：决定能否走预授权（免押）与分账（场地方/代理商）
    { header: "能力", cell: (c) => <span className="text-muted-foreground">{c.capabilities}</span> },
    { header: "商户号", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.merchantId}</span> },
    { header: "密钥", cell: () => <span className="text-muted-foreground tabular-nums">****</span> },
    { header: "状态", cell: (c) => c.status === "ENABLED" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "更新时间", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.updatedAt)}</span> },
    { header: "操作", cell: (c) => canPayment ? <Button size="sm" variant="outline" onClick={() => setPaymentForm(c)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const editBtn = <T,>(can: boolean, open: (r: T) => void) => (row: T) =>
    can ? <Button size="sm" variant="outline" onClick={() => open(row)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span>;

  const vendorCols: Column<Vendor>[] = [
    { header: "供应商码", cell: (v) => <span className="font-medium">{v.vendorCode}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "接入方式", cell: (v) => <Badge tone="outline">{MODE_LABEL[v.accessMode]}</Badge> },
    { header: "设备数", cell: (v) => <span className="tabular-nums">{v.deviceCount}</span> },
    { header: "状态", cell: (v) => v.status === "ENABLED" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "操作", cell: (v) => allow("device:vendor:config") ? <Button size="sm" variant="outline" onClick={() => openVendor(v)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const notifyCols: Column<NotifyTemplate>[] = [
    { header: "模板号", cell: (t) => <span className="font-medium">{t.templateNo}</span> },
    { header: "名称", cell: (t) => t.name },
    { header: "渠道", cell: (t) => <Badge tone="outline">{CHANNEL_LABEL[t.channel]}</Badge> },
    { header: "语言", cell: (t) => <span className="text-muted-foreground">{LANG_LABEL[t.lang]}</span> },
    { header: "状态", cell: (t) => t.status === "ENABLED" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: t("common.actions"), cell: editBtn<NotifyTemplate>(canNotify, setNotifyForm) },
  ];

  const dictCols: Column<DictEntry>[] = [
    { header: "字典号", cell: (d) => <span className="font-medium">{d.dictNo}</span> },
    { header: "分组", cell: (d) => <Badge tone="outline">{d.group}</Badge> },
    { header: "编码", cell: (d) => <span className="text-muted-foreground tabular-nums">{d.code}</span> },
    { header: "标签", cell: (d) => d.label },
    { header: "排序", cell: (d) => <span className="tabular-nums">{d.sort}</span> },
    { header: "状态", cell: (d) => d.enabled ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: t("common.actions"), cell: editBtn<DictEntry>(canDict, setDictForm) },
  ];

  const regionCols: Column<Region>[] = [
    { header: "区域 ID", cell: (r) => <span className="font-medium">{r.regionId}</span> },
    { header: "名称", cell: (r) => r.name },
    { header: "上级", cell: (r) => <span className="text-muted-foreground">{r.parent || "-"}</span> },
    { header: "层级", cell: (r) => <span className="tabular-nums">{r.level}</span> },
    { header: "城市数", cell: (r) => <span className="tabular-nums">{r.cityCount}</span> },
    { header: t("common.actions"), cell: editBtn<Region>(canRegion, setRegionForm) },
  ];

  const paramCols: Column<SysParam>[] = [
    { header: "参数键", cell: (p) => <span className="font-medium">{p.paramKey}</span> },
    { header: "说明", cell: (p) => p.label },
    { header: "取值", cell: (p) => <span className="tabular-nums">{p.value}</span> },
    { header: "分组", cell: (p) => <Badge tone="outline">{p.groupName}</Badge> },
    { header: "更新时间", cell: (p) => <span className="text-muted-foreground">{fmtTime(p.updatedAt)}</span> },
    { header: t("common.actions"), cell: editBtn<SysParam>(canParam, setParamForm) },
  ];

  const openapiCols: Column<OpenApiApp>[] = [
    { header: "应用号", cell: (a) => <span className="font-medium">{a.appNo}</span> },
    { header: "名称", cell: (a) => a.name },
    { header: "AppKey", cell: (a) => <span className="text-muted-foreground tabular-nums">{a.appKey}</span> },
    { header: "限流（次/秒）", cell: (a) => <span className="tabular-nums">{a.rateLimit}</span> },
    { header: "状态", cell: (a) => a.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "创建时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    { header: t("common.actions"), cell: editBtn<OpenApiApp>(canOpenapi, setOpenapiForm) },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); }} />

      {tab === "payment" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索渠道码 / 名称 / 国家 / 币种"
          onAdd={canPayment ? () => setPaymentForm({ mode: "DIRECT", status: "DISABLED", countries: "AE", currencies: "AED", capabilities: "支付,退款", apiBase: "", merchantId: "", apiKeyMasked: "sk_test_****" }) : undefined} addLabel="新增支付渠道" />
      )}
      {tab === "notify" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索模板号 / 名称"
          onAdd={canNotify ? () => setNotifyForm({ channel: "SMS", lang: "ar", status: "ENABLED" }) : undefined} addLabel="新增模板" />
      )}
      {tab === "dict" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索分组 / 编码 / 标签"
          onAdd={canDict ? () => setDictForm({ sort: 0, enabled: true }) : undefined} addLabel="新增字典项" />
      )}
      {tab === "region" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索区域 ID / 名称"
          onAdd={canRegion ? () => setRegionForm({ level: 1, cityCount: 0, parent: "" }) : undefined} addLabel="新增地区" />
      )}
      {tab === "params" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索参数键 / 说明"
          onAdd={canParam ? () => setParamForm({ value: "", groupName: "" }) : undefined} addLabel="新增参数" />
      )}
      {tab === "openapi" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索应用号 / 名称 / AppKey"
          onAdd={canOpenapi ? () => setOpenapiForm({ rateLimit: 10, status: "ACTIVE" }) : undefined} addLabel="新增应用" />
      )}
      {tab === "markets" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索国家 / 币种" />
      )}

      {tab === "vendors" && <DataTable rowKey={(v: Vendor) => v.vendorCode} columns={vendorCols} rows={vendorsQ.data} loading={vendorsQ.isLoading} />}
      {tab === "payment" && <DataTable rowKey={(c: PaymentChannel) => c.channelCode} columns={paymentCols} rows={q.data?.list as PaymentChannel[]} loading={q.isLoading} />}
      {tab === "notify" && <DataTable rowKey={(t: NotifyTemplate) => t.templateNo} columns={notifyCols} rows={q.data?.list as NotifyTemplate[]} loading={q.isLoading} />}
      {tab === "dict" && <DataTable rowKey={(d: DictEntry) => d.dictNo} columns={dictCols} rows={q.data?.list as DictEntry[]} loading={q.isLoading} />}
      {tab === "region" && <DataTable rowKey={(r: Region) => r.regionId} columns={regionCols} rows={q.data?.list as Region[]} loading={q.isLoading} />}
      {tab === "params" && <DataTable rowKey={(p: SysParam) => p.paramKey} columns={paramCols} rows={q.data?.list as SysParam[]} loading={q.isLoading} />}
      {tab === "openapi" && <DataTable rowKey={(a: OpenApiApp) => a.appNo} columns={openapiCols} rows={q.data?.list as OpenApiApp[]} loading={q.isLoading} />}
      {tab === "markets" && <DataTable rowKey={(m: MarketCountry) => m.countryCode} columns={marketCols} rows={q.data?.list as MarketCountry[]} loading={q.isLoading} />}
      {tab !== "vendors" && q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      {/* 供应商 配置抽屉（保留）*/}
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

      {/* 支付渠道 配置抽屉（密钥仅掩码，真实值由后端保管）*/}
      <FormDrawer open={!!paymentForm} onOpenChange={(o) => !o && setPaymentForm(null)}
        titleNew="新增支付渠道" titleEdit={`配置支付渠道 ${paymentForm?.channelCode ?? ""}`} isEdit={!!paymentForm?.channelCode}
        fields={PAYMENT_FIELDS} value={(paymentForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPaymentForm(v as Partial<PaymentChannel>)}
        onSubmit={() => paymentForm && savePayment.mutate(paymentForm)} submitting={savePayment.isPending} />

      {/* 通知模板 编辑抽屉 */}
      <FormDrawer open={!!notifyForm} onOpenChange={(o) => !o && setNotifyForm(null)}
        titleNew="新增通知模板" titleEdit={`编辑通知模板 ${notifyForm?.templateNo ?? ""}`} isEdit={!!notifyForm?.templateNo}
        fields={NOTIFY_FIELDS} value={(notifyForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setNotifyForm(v as Partial<NotifyTemplate>)}
        onSubmit={() => notifyForm && saveNotify.mutate(notifyForm)} submitting={saveNotify.isPending} />

      {/* 参数字典 编辑抽屉 */}
      <FormDrawer open={!!dictForm} onOpenChange={(o) => !o && setDictForm(null)}
        titleNew="新增字典项" titleEdit={`编辑字典项 ${dictForm?.dictNo ?? ""}`} isEdit={!!dictForm?.dictNo}
        fields={DICT_FIELDS} value={(dictForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setDictForm(v as Partial<DictEntry>)}
        onSubmit={() => dictForm && saveDict.mutate(dictForm)} submitting={saveDict.isPending} />

      {/* 地区库 编辑抽屉 */}
      <FormDrawer open={!!regionForm} onOpenChange={(o) => !o && setRegionForm(null)}
        titleNew="新增地区" titleEdit={`编辑地区 ${regionForm?.regionId ?? ""}`} isEdit={!!regionForm?.regionId}
        fields={REGION_FIELDS} value={(regionForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRegionForm(v as Partial<Region>)}
        onSubmit={() => regionForm && saveRegion.mutate(regionForm)} submitting={saveRegion.isPending} />

      {/* 系统参数 编辑抽屉 */}
      <FormDrawer open={!!paramForm} onOpenChange={(o) => !o && setParamForm(null)}
        titleNew="新增系统参数" titleEdit={`编辑系统参数 ${paramForm?.paramKey ?? ""}`} isEdit={!!paramForm?.paramKey}
        fields={PARAM_FIELDS} value={(paramForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setParamForm(v as Partial<SysParam>)}
        onSubmit={() => paramForm && saveParam.mutate(paramForm)} submitting={saveParam.isPending} />

      {/* OpenAPI 应用 编辑抽屉 */}
      <FormDrawer open={!!openapiForm} onOpenChange={(o) => !o && setOpenapiForm(null)}
        titleNew="新增 OpenAPI 应用" titleEdit={`编辑 OpenAPI 应用 ${openapiForm?.appNo ?? ""}`} isEdit={!!openapiForm?.appNo}
        fields={OPENAPI_FIELDS} value={(openapiForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setOpenapiForm(v as Partial<OpenApiApp>)}
        onSubmit={() => openapiForm && saveOpenapi.mutate(openapiForm)} submitting={saveOpenapi.isPending} />
    </div>
  );
}

export default function SystemPage() {
  return <Suspense fallback={null}><SystemInner /></Suspense>;
}

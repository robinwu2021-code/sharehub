"use client";

// 支付渠道（OM04-13）：渠道启停、市场与币种（经共用支付服务）。
//
// 2026-10-28 从「系统设置」搬到「销售管理 › 支付」（docs/requirements/运营端菜单清单-按运营顺序.md）：
// 渠道决定 C 端能不能下单收钱，是收入线的一环，与支付流水、扣款重试放在一起看。
// 内容原样搬出（列表 / 配置抽屉），行为不变；系统页不再留第二份。
// 此前它借用系统页共用的分页查询，搬出来后自带查询与分页。
import { useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { EnabledBadge } from "@/components/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type { PaymentChannel } from "@/lib/types";

// 密钥类字段不在表单里：前端永不承载真实密钥（真实值仅后端保管）
/**
 * @form POST /api/platform/payment-channels
 * @form POST /api/platform/payment-channels/{channelCode}
 */
const PAYMENT_FIELDS: FieldDef[] = [
  { key: "channelCode", label: "渠道码", readOnlyOnEdit: true, placeholder: "NEARPAY / STRIPE / PAYPAL" },
  { key: "channelName", label: "渠道名称", placeholder: "NearPay（聚合收单）" },
  // 英/阿名后端一直存着，此前表单里没有 —— 等于只能由 DBA 直接改库。
  // 展示仍用 channelName：本仓还没有「按当前语言取名」的统一做法（品牌/站点同理）。
  { key: "channelNameEn", label: "渠道名称（English）", placeholder: "NearPay (Aggregated)" },
  { key: "channelNameAr", label: "渠道名称（العربية）", placeholder: "نير باي" },
  { key: "mode", label: "接入模式", type: "select", options: [{ value: "DELEGATED", label: "委托" }, { value: "DIRECT", label: "直连" }] },
  { key: "status", label: "状态", type: "select", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "countries", label: "适用国家", placeholder: "AE,SA" },
  { key: "currencies", label: "币种", placeholder: "AED,SAR" },
  { key: "capabilities", label: "能力", placeholder: "支付,退款,预授权,分账" },
  { key: "apiBase", label: "API 基址", placeholder: "https://api.nearpay.example" },
  {
    key: "merchantId", label: "商户号", placeholder: "MID-AE-100286",
    help: "密钥不在这里配：明文走 KMS/vault 轮换（ADR-005），库里只存掩码供核对末四位，列表「密钥」列可读",
  },
  // 这里原本有 apiKeyMasked / apiSecretMasked 两个 password 输入框。
  // **后端刻意不收**（PaymentChannelController 类注释：「入参即使带了明文密钥字段也无处可落
  // —— 实体压根没有那个字段」，ChannelBody 里确实没有这两项）。
  // 掩码是**出参**，不是入参：可读不可填，读的地方挪到列表那一列。
];

export function PaymentChannelsTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const canPayment = allow("system:payment_channel:update");
  const [paymentForm, setPaymentForm] = useState<Partial<PaymentChannel> | null>(null);

  const q = useQuery({
    queryKey: ["payment-channels", paging.page, paging.size, keyword],
    queryFn: () => api.listPaymentChannels({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
  });
  const savePayment = useMutation({
    mutationFn: (v: Partial<PaymentChannel>) => api.savePaymentChannel(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["payment-channels"] }); notify.success(t("common.success")); setPaymentForm(null); },
  });

  const cols: Column<PaymentChannel>[] = [
    { header: "渠道码", cell: (c) => <span className="font-medium">{c.channelCode}</span> },
    { header: "名称", cell: (c) => c.channelName },
    {
      header: "密钥",
      className: "whitespace-nowrap",
      // 只说"配没配"，不显示掩码本身 —— 列表是扫描用的。key 有 secret 没有是最常见的半配状态，单独标出来。
      cell: (c) => !c.apiKeyMasked
        ? <Badge tone="danger">未配置</Badge>
        : !c.apiSecretMasked
          ? <Badge tone="warning">缺 Secret</Badge>
          : <Badge tone="success">已配置</Badge>,
    },
    { header: "模式", cell: (c) => <Badge tone="outline">{c.mode === "DELEGATED" ? "委托" : "直连"}</Badge> },
    { header: "适用国家", cell: (c) => <span className="tabular-nums">{c.countries}</span> },
    { header: "币种", cell: (c) => <span className="tabular-nums">{c.currencies}</span> },
    // 能力矩阵：决定能否走预授权（免押）与分账
    { header: "能力", cell: (c) => <span className="text-muted-foreground">{c.capabilities}</span> },
    { header: "商户号", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.merchantId}</span> },
    {
      // 掩码本来就是为了给人核对末四位的（明文永不落前端），所以这里出真值。
      header: "密钥掩码",
      cell: (c) => <span className="text-muted-foreground tabular-nums">{c.apiKeyMasked || "—"}{c.apiSecretMasked ? ` / ${c.apiSecretMasked}` : ""}</span>,
    },
    { header: "状态", cell: (c) => <EnabledBadge on={c.status === "ENABLED"} /> },
    { header: "更新时间", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.updatedAt)}</span> },
    { header: "操作", cell: (c) => canPayment ? <Button size="sm" variant="outline" onClick={() => setPaymentForm(c)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索渠道码 / 名称 / 国家 / 币种"
        // 密钥列导出为掩码占位，与表格一致——CSV 落到本地更不能带真实密钥
        onExport={() => exportCsv<PaymentChannel>("支付渠道", [
          { header: "渠道码", value: (c) => c.channelCode },
          { header: "名称", value: (c) => c.channelName },
          { header: "模式", value: (c) => (c.mode === "DELEGATED" ? "委托" : "直连") },
          { header: "适用国家", value: (c) => c.countries },
          { header: "币种", value: (c) => c.currencies },
          { header: "能力", value: (c) => c.capabilities },
          { header: "商户号", value: (c) => c.merchantId },
          { header: "密钥", value: () => "****" },
          { header: "状态", value: (c) => (c.status === "ENABLED" ? "启用" : "停用") },
          { header: "更新时间", value: (c) => fmtTime(c.updatedAt) },
        ], q.data?.list ?? [])}
        onAdd={canPayment ? () => setPaymentForm({ mode: "DIRECT", status: "DISABLED", countries: "AE", currencies: "AED", capabilities: "支付,退款", apiBase: "", merchantId: "" }) : undefined}
        addLabel="新增支付渠道" />
      <DataTable rowKey={(c: PaymentChannel) => c.channelCode} columns={cols} rows={q.data?.list} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无支付渠道——未配置渠道时 C 端无法下单支付，请先新增并启用至少一个渠道" />
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
      <FormDrawer open={!!paymentForm} onOpenChange={(o) => !o && setPaymentForm(null)}
        titleNew="新增支付渠道" titleEdit={`配置支付渠道 ${paymentForm?.channelCode ?? ""}`} isEdit={!!paymentForm?.channelCode}
        fields={PAYMENT_FIELDS} value={(paymentForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPaymentForm(v as Partial<PaymentChannel>)}
        onSubmit={() => paymentForm && savePayment.mutate(paymentForm)} submitting={savePayment.isPending} />
    </>
  );
}

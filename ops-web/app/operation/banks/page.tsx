"use client";

// 运营管理 › 基础管理 › 银行管理（清单 OM-B3，对标简电「银行管理」）
// 提现收款时选择的银行字典；IBAN 长度用于校验收款账号位数。
//
// 与旧入口（系统设置 › 银行管理）读写同一份数据，旧页面不动（TDD §1.1）。
// 规则见 lib/operation-rules#validateBank，表单与 mock 共用。
//
// 与清单的差异（如实记录，不做假数据）：
// - 名称只有中 / 英两语：后端返回结构没有阿语名称（表里有列，接口未返回），阿语界面回退显示英文名
// - 「引用数」列与「被引用只能停用」规则暂不做：前后端都没有收款账户引用这张字典的数据
import { useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Pencil, Power } from "lucide-react";
import { api } from "@/lib/api";
import type { BankEntry } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { IBAN_LENGTH_BY_COUNTRY, CURRENCY_BY_COUNTRY, validateBank } from "@/lib/operation-rules";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";

const SIZE = 20;

const BANK_STATUS: StatusMap<BankEntry["status"]> = {
  ENABLED: { label: "启用", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};

const COUNTRY_LABEL: Record<string, string> = {
  AE: "阿联酋", SA: "沙特", QA: "卡塔尔", KW: "科威特", BH: "巴林", OM: "阿曼", JO: "约旦", EG: "埃及",
};
const COUNTRY_OPTIONS = Object.entries(COUNTRY_LABEL).map(([value, label]) => ({ value, label: `${label} ${value}` }));
const CURRENCY_OPTIONS = ["AED", "SAR", "QAR", "KWD", "BHD", "OMR", "JOD", "EGP"].map((c) => ({ value: c, label: c }));

const FIELDS: FieldDef[] = [
  { key: "bankCode", label: "银行代码", required: true, readOnlyOnEdit: true, maxLength: 12, section: "基本信息", placeholder: "ENBD", help: "2～12 位大写字母或数字，保存时自动转大写；创建后不能修改" },
  { key: "bankName", label: "名称（中文）", required: true, maxLength: 40, section: "基本信息", placeholder: "阿联酋国民银行" },
  { key: "bankNameEn", label: "名称（English）", required: true, maxLength: 60, section: "基本信息", placeholder: "Emirates NBD", help: "阿语界面也显示英文名（后端暂未返回阿语名称）" },
  { key: "country", label: "国家", type: "select", required: true, section: "归属市场", options: COUNTRY_OPTIONS, help: "新增时选国家会自动预填币种和 IBAN 长度" },
  { key: "currency", label: "币种", type: "select", required: true, section: "归属市场", options: CURRENCY_OPTIONS },
  { key: "swiftPrefix", label: "SWIFT 前缀", required: true, maxLength: 11, section: "账户校验", placeholder: "EBILAEAD" },
  { key: "ibanLength", label: "IBAN 长度", type: "number", required: true, min: 15, max: 34, section: "账户校验", help: "提现收款账号按这个位数校验，填错会导致打款失败" },
];

const EMPTY_FORM: Partial<BankEntry> = { country: "AE", currency: "AED", ibanLength: IBAN_LENGTH_BY_COUNTRY.AE, status: "ENABLED" };

export default function BanksPage() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav, locale } = useI18n();
  const { confirm, dialog } = useConfirm();
  const canWrite = allow("system:bank:update");

  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [country, setCountry] = useState("");
  const [currency, setCurrency] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Partial<BankEntry> | null>(null);
  const [editing, setEditing] = useState<BankEntry | undefined>();

  const q = useQuery({
    queryKey: ["op", "banks", page, keyword, country, currency, status, showArchived],
    queryFn: () => api.listBanks({ page, size: SIZE, keyword, country, currency, status, showArchived }),
    placeholderData: keepPreviousData,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "banks"] });

  const save = useMutation({
    mutationFn: (v: Partial<BankEntry>) => api.saveBank(v),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (code: string) => api.archiveBank(code), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (code: string) => api.unarchiveBank(code), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const openNew = () => { setEditing(undefined); setForm({ ...EMPTY_FORM }); };
  const openEdit = (b: BankEntry) => { setEditing(b); setForm({ ...b }); };

  // 新增时切换国家 → 预填 IBAN 长度与常用币种；编辑时不自动改，避免覆盖人工确认过的值
  const onFormChange = (v: Record<string, unknown>) => {
    const next = v as Partial<BankEntry>;
    if (!editing && next.country && next.country !== form?.country) {
      const len = IBAN_LENGTH_BY_COUNTRY[next.country];
      if (len) next.ibanLength = len;
      const cur = CURRENCY_BY_COUNTRY[next.country];
      if (cur) next.currency = cur;
    }
    setForm(next);
  };

  const submit = () => {
    if (!form) return;
    const up = (s?: string) => (s ?? "").trim().toUpperCase();
    const v = { ...form, bankCode: up(form.bankCode), swiftPrefix: up(form.swiftPrefix), ibanLength: Number(form.ibanLength) };
    const errors = validateBank(v, editing, q.data?.list ?? []);
    if (errors.length) { notify.error(errors[0]); return; }
    save.mutate(v);
  };

  const toggleStatus = async (b: BankEntry) => {
    const disabling = b.status === "ENABLED";
    const ok = await confirm({
      title: `${disabling ? "停用" : "启用"}银行 ${b.bankName}`,
      desc: disabling
        ? "停用后提现时不能再选择这家银行，已经绑定这家银行的收款账户不受影响。"
        : "启用后提现时可以选择这家银行。",
      danger: disabling,
      confirmText: disabling ? "停用" : "启用",
    });
    if (ok) save.mutate({ ...b, status: disabling ? "DISABLED" : "ENABLED" });
  };

  const nameOf = (b: BankEntry) => (locale === "zh" ? b.bankName : b.bankNameEn || b.bankName);

  const cols: Column<BankEntry>[] = [
    { header: "银行代码", cell: (b) => <span className="font-mono">{b.bankCode}</span> },
    { header: "名称", cell: (b) => (
      <div className="min-w-0">
        <div className="truncate">{nameOf(b)}</div>
        {locale === "zh" && <div className="truncate txt-caption text-muted-foreground">{b.bankNameEn}</div>}
      </div>
    ) },
    { header: "国家", className: "whitespace-nowrap", cell: (b) => <span title={COUNTRY_LABEL[b.country]}>{b.country} <span className="text-muted-foreground">{COUNTRY_LABEL[b.country] ?? ""}</span></span> },
    { header: "币种", cell: (b) => b.currency },
    { header: "SWIFT 前缀", cell: (b) => <span className="font-mono">{b.swiftPrefix}</span> },
    { header: "IBAN 长度", cell: (b) => `${b.ibanLength} 位`, className: "whitespace-nowrap text-right" },
    { header: "状态", className: "whitespace-nowrap", cell: (b) => <StatusBadge map={BANK_STATUS} value={b.status} /> },
    {
      header: "操作",
      // 包一层 w-max：ArchiveActions 的按钮容器会自动换行，窄表格里三个按钮会竖着排
      cell: (b) => (
        <div className="w-max"><ArchiveActions
          archived={!!b.archivedAt}
          canWrite={canWrite}
          onArchive={async () => { if (await confirm(archiveConfirm("银行", b.bankName))) archive.mutate(b.bankCode); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("银行", b.bankName))) unarchive.mutate(b.bankCode); }}
          actions={
            <>
              <Button size="sm" variant="outline" onClick={() => openEdit(b)}><Pencil className="size-4" /> 编辑</Button>
              <Button size="sm" variant="outline" onClick={() => toggleStatus(b)}>
                <Power className="size-4" /> {b.status === "ENABLED" ? "停用" : "启用"}
              </Button>
            </>
          }
        /></div>
      ),
    },
  ];

  const filtered = !!(keyword || country || currency || status);

  return (
    <div>
      <PageTitle title={tNav("银行管理")} desc="提现收款时选择的银行；IBAN 长度用于校验收款账号位数" />
      {!canWrite && <ReadOnlyNotice what="银行字典维护" perm="system:bank:update" note="不能新增、编辑、停用或归档" className="mb-3" />}
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder="搜索代码 / 名称 / SWIFT"
        onAdd={openNew}
        addLabel="新增银行"
        canAdd={canWrite}
      >
        <FilterSelect aria-label="国家" value={country} onChange={(v) => { setCountry(v); setPage(1); }} options={COUNTRY_OPTIONS} allLabel="全部国家" />
        <FilterSelect aria-label="币种" value={currency} onChange={(v) => { setCurrency(v); setPage(1); }} options={CURRENCY_OPTIONS} allLabel="全部币种" />
        <FilterSelect aria-label="状态" value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={BANK_STATUS} allLabel="全部状态" />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
      </Toolbar>
      <DataTable
        rowKey={(b: BankEntry) => b.bankCode}
        columns={cols}
        rows={q.data?.list}
        loading={q.isLoading}
        rowClassName={archivedRowClass}
        empty={filtered
          ? "没有符合筛选条件的银行。试试清空筛选，或打开「显示已归档」。"
          : "还没有银行。商户和代理提现时要从这里选择收款银行，没有银行就无法提现，请先新增。"}
      />
      <Pagination page={page} size={SIZE} total={q.data?.total ?? 0} onPage={setPage} />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增银行"
        titleEdit={`编辑银行 ${editing?.bankCode ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={onFormChange}
        onSubmit={submit}
        submitting={save.isPending}
      />
      {dialog}
    </div>
  );
}

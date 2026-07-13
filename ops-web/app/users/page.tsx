"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Input } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { CUser, Member, Wallet } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "list", label: "用户", phase: 2 as const },
  { key: "members", label: "会员/次卡", phase: 3 as const },
  { key: "wallets", label: "钱包", phase: 3 as const },
];

const MEMBER_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, placeholder: "U0001" },
  { key: "nickname", label: "昵称", placeholder: "会员昵称" },
  { key: "level", label: "等级", type: "select", options: [
    { value: "SILVER", label: "白银" },
    { value: "GOLD", label: "黄金" },
    { value: "PLATINUM", label: "铂金" },
  ] },
  { key: "points", label: "积分", type: "number" },
  { key: "cardType", label: "次卡类型", placeholder: "月卡 / 季卡 / 无" },
  { key: "expireAt", label: "到期时间", placeholder: "2026-12-31T00:00:00Z" },
];

const WALLET_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, placeholder: "U0001" },
  { key: "nickname", label: "昵称", placeholder: "用户昵称" },
  { key: "balance", label: "余额", type: "number" },
  { key: "bonus", label: "赠额", type: "number" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

function UsersInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "list");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [memberForm, setMemberForm] = useState<Partial<Member> | null>(null);
  const [walletForm, setWalletForm] = useState<Partial<Wallet> | null>(null);
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const canEditMember = allow("user:member:update");
  const canEditWallet = allow("user:wallet:update");

  const users = useQuery({
    queryKey: ["users", page, keyword],
    queryFn: () => api.listUsers({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  const members = useQuery({
    queryKey: ["members", page, keyword],
    queryFn: () => api.listMembers({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "members",
  });

  const wallets = useQuery({
    queryKey: ["wallets", page, keyword],
    queryFn: () => api.listWallets({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "wallets",
  });

  const bl = useMutation({
    mutationFn: (v: { no: string; blacklisted: boolean }) => api.setBlacklist(v.no, v.blacklisted),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });

  const saveMember = useMutation({
    mutationFn: (v: Partial<Member>) => api.saveMember(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["members"] }); notify.success(t("common.success")); setMemberForm(null); },
  });

  const saveWallet = useMutation({
    mutationFn: (v: Partial<Wallet>) => api.saveWallet(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["wallets"] }); notify.success(t("common.success")); setWalletForm(null); },
  });

  const userCols: Column<CUser>[] = [
    { header: "用户号", cell: (u) => <span className="font-medium">{u.cUserNo}</span> },
    { header: "昵称", cell: (u) => u.nickname },
    { header: "手机", cell: (u) => <span className="text-muted-foreground">{u.phone}</span> },
    { header: "信用分", cell: (u) => <span className="tabular-nums">{u.creditScore}</span> },
    { header: "订单数", cell: (u) => <span className="tabular-nums">{u.orders}</span> },
    { header: "注册", cell: (u) => <span className="text-muted-foreground">{fmtTime(u.registeredAt)}</span> },
    { header: "状态", cell: (u) => u.blacklisted ? <Badge tone="danger">黑名单</Badge> : <Badge tone="success">正常</Badge> },
    {
      header: "操作",
      cell: (u) => allow("user:risk:update") ? (
        <Button size="sm" variant="outline" disabled={bl.isPending} onClick={() => bl.mutate({ no: u.cUserNo, blacklisted: !u.blacklisted })}>
          {u.blacklisted ? "解除拉黑" : "拉黑"}
        </Button>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const LEVEL: Record<Member["level"], { label: string; tone: "muted" | "warning" | "success" }> = {
    SILVER: { label: "白银", tone: "muted" },
    GOLD: { label: "黄金", tone: "warning" },
    PLATINUM: { label: "铂金", tone: "success" },
  };
  const memberCols: Column<Member>[] = [
    { header: "用户号", cell: (m) => <span className="font-medium">{m.userNo}</span> },
    { header: "昵称", cell: (m) => m.nickname },
    { header: "等级", cell: (m) => <Badge tone={LEVEL[m.level].tone}>{LEVEL[m.level].label}</Badge> },
    { header: "积分", cell: (m) => <span className="tabular-nums">{Math.round(m.points)}</span> },
    { header: "次卡", cell: (m) => m.cardType },
    { header: "到期", cell: (m) => <span className="text-muted-foreground">{fmtTime(m.expireAt)}</span> },
    { header: t("common.actions"), cell: (m) => canEditMember ? <Button size="sm" variant="outline" onClick={() => setMemberForm(m)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const walletCols: Column<Wallet>[] = [
    { header: "用户号", cell: (w) => <span className="font-medium">{w.userNo}</span> },
    { header: "昵称", cell: (w) => w.nickname },
    { header: "余额", cell: (w) => <span className="tabular-nums">{money(w.balance, w.currency)}</span> },
    { header: "赠额", cell: (w) => <span className="tabular-nums">{money(w.bonus, w.currency)}</span> },
    { header: "币种", cell: (w) => <Badge tone="outline">{w.currency}</Badge> },
    { header: "更新时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.updatedAt)}</span> },
    { header: t("common.actions"), cell: (w) => canEditWallet ? <Button size="sm" variant="outline" onClick={() => setWalletForm(w)}>调整余额</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const active = tab === "list" ? users : tab === "members" ? members : wallets;

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      {tab === "list" && (
        <>
          <div className="mb-4"><Input className="w-64" placeholder="搜索昵称 / 手机 / 用户号" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} /></div>
          <DataTable rowKey={(u: CUser) => u.cUserNo} columns={userCols} rows={users.data?.list} loading={users.isLoading} />
        </>
      )}
      {tab === "members" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索昵称 / 用户号"
            onAdd={canEditMember ? () => setMemberForm({ level: "SILVER", points: 0, cardType: "无", nickname: "" }) : undefined}
            addLabel="新增会员"
          />
          <DataTable rowKey={(m: Member) => m.userNo} columns={memberCols} rows={members.data?.list} loading={members.isLoading} />
        </>
      )}
      {tab === "wallets" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索昵称 / 用户号"
          />
          <DataTable rowKey={(w: Wallet) => w.userNo} columns={walletCols} rows={wallets.data?.list} loading={wallets.isLoading} />
        </>
      )}
      {active.data && <Pagination page={page} size={SIZE} total={active.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!memberForm}
        onOpenChange={(o) => !o && setMemberForm(null)}
        titleNew="新增会员"
        titleEdit={`编辑会员 ${memberForm?.userNo ?? ""}`}
        isEdit={!!memberForm?.userNo}
        fields={MEMBER_FIELDS}
        value={(memberForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setMemberForm(v as Partial<Member>)}
        onSubmit={() => memberForm && saveMember.mutate(memberForm)}
        submitting={saveMember.isPending}
      />

      <FormDrawer
        open={!!walletForm}
        onOpenChange={(o) => !o && setWalletForm(null)}
        titleNew="新增钱包"
        titleEdit={`调整钱包 ${walletForm?.userNo ?? ""}`}
        isEdit={!!walletForm?.userNo}
        fields={WALLET_FIELDS}
        value={(walletForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setWalletForm(v as Partial<Wallet>)}
        onSubmit={() => walletForm && saveWallet.mutate(walletForm)}
        submitting={saveWallet.isPending}
      />
    </div>
  );
}

export default function UsersPage() {
  return <Suspense fallback={null}><UsersInner /></Suspense>;
}

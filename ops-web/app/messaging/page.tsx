"use client";

// 消息中心（msg 模块）：发送记录 / 客服会话 / 黑名单 / 消息模板。
// 消息模板为 P1；其余 P2（导航仅作标识，可正常点击）。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "records", label: "发送记录", phase: 2 as const },
  { key: "sessions", label: "客服会话", phase: 2 as const },
  { key: "blocked", label: "黑名单", phase: 2 as const },
  { key: "templates", label: "消息模板", phase: 1 as const },
];

interface Rec { id: string; channel: string; target: string; tpl: string; result: "SENT" | "FAILED" | "PENDING"; at: string; }
const REC_ROWS: Rec[] = [
  { id: "M-70021", channel: "短信", target: "1358****201", tpl: "归还提醒", result: "SENT", at: "07-14 09:22" },
  { id: "M-70020", channel: "Push", target: "全部用户", tpl: "暑期优惠", result: "SENT", at: "07-14 09:00" },
  { id: "M-70019", channel: "短信", target: "1899****663", tpl: "扣费通知", result: "FAILED", at: "07-14 08:47" },
  { id: "M-70018", channel: "站内信", target: "1370****118", tpl: "工单回复", result: "PENDING", at: "07-14 08:31" },
];
const REC_STATUS: Record<Rec["result"], [string, "success" | "danger" | "warning"]> = {
  SENT: ["已送达", "success"], FAILED: ["失败", "danger"], PENDING: ["发送中", "warning"],
};
const REC_COLS: Column<Rec>[] = [
  { header: "消息ID", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "渠道", cell: (r) => <Badge tone="outline">{r.channel}</Badge> },
  { header: "接收方", cell: (r) => r.target },
  { header: "模板", cell: (r) => <span className="text-muted-foreground">{r.tpl}</span> },
  { header: "结果", cell: (r) => <Badge tone={REC_STATUS[r.result][1]}>{REC_STATUS[r.result][0]}</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Sess { id: string; user: string; agent: string; last: string; status: "OPEN" | "WAITING" | "CLOSED"; at: string; }
const SESS_ROWS: Sess[] = [
  { id: "S-3301", user: "1358****201", agent: "客服 · 小美", last: "好的，已为您提交退款", status: "OPEN", at: "07-14 09:25" },
  { id: "S-3300", user: "1899****663", agent: "未分配", last: "设备弹不出来", status: "WAITING", at: "07-14 09:10" },
  { id: "S-3298", user: "1370****118", agent: "客服 · 阿强", last: "问题已解决，感谢", status: "CLOSED", at: "07-13 21:40" },
];
const SESS_STATUS: Record<Sess["status"], [string, "success" | "warning" | "muted"]> = {
  OPEN: ["进行中", "success"], WAITING: ["待接入", "warning"], CLOSED: ["已结束", "muted"],
};
const SESS_COLS: Column<Sess>[] = [
  { header: "会话", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "用户", cell: (r) => r.user },
  { header: "坐席", cell: (r) => r.agent },
  { header: "最后消息", cell: (r) => <span className="truncate text-muted-foreground">{r.last}</span> },
  { header: "状态", cell: (r) => <Badge tone={SESS_STATUS[r.status][1]}>{SESS_STATUS[r.status][0]}</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Blk { id: string; user: string; reason: string; scope: string; at: string; }
const BLK_ROWS: Blk[] = [
  { id: "BK1", user: "1521****904", reason: "恶意投诉刷单", scope: "短信 + Push", at: "07-10" },
  { id: "BK2", user: "1666****027", reason: "退订营销消息", scope: "营销 Push", at: "07-08" },
  { id: "BK3", user: "1802****551", reason: "号码停用退信", scope: "短信", at: "07-05" },
];
const BLK_COLS: Column<Blk>[] = [
  { header: "用户", cell: (r) => <span className="font-medium">{r.user}</span> },
  { header: "拉黑原因", cell: (r) => r.reason },
  { header: "屏蔽范围", cell: (r) => <Badge tone="outline">{r.scope}</Badge> },
  { header: "加入时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Tpl { id: string; code: string; name: string; channel: string; on: boolean; }
const TPL_ROWS: Tpl[] = [
  { id: "T1", code: "SMS_RETURN", name: "归还提醒", channel: "短信", on: true },
  { id: "T2", code: "SMS_CHARGE", name: "扣费通知", channel: "短信", on: true },
  { id: "T3", code: "PUSH_PROMO", name: "营销活动推送", channel: "Push", on: true },
  { id: "T4", code: "INBOX_WO", name: "工单回复站内信", channel: "站内信", on: true },
  { id: "T5", code: "SMS_VERIFY", name: "验证码", channel: "短信", on: true },
];
const TPL_COLS: Column<Tpl>[] = [
  { header: "模板编码", cell: (r) => <Badge tone="outline">{r.code}</Badge> },
  { header: "名称", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "渠道", cell: (r) => r.channel },
  { header: "状态", cell: (r) => r.on ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "sessions":
      return <MockTabView stats={[{ label: "进行中会话", value: 18 }, { label: "待接入", value: 5, tone: "down" }, { label: "今日已结束", value: 132 }, { label: "平均首响", value: "42s" }]} columns={SESS_COLS} rows={SESS_ROWS} />;
    case "blocked":
      return <MockTabView stats={[{ label: "黑名单用户", value: 37 }, { label: "本周新增", value: 4 }, { label: "短信屏蔽", value: 22 }, { label: "Push 屏蔽", value: 15 }]} columns={BLK_COLS} rows={BLK_ROWS} />;
    case "templates":
      return <MockTabView stats={[{ label: "消息模板", value: 18 }, { label: "启用", value: 16 }, { label: "短信模板", value: 9 }, { label: "Push 模板", value: 7 }]} columns={TPL_COLS} rows={TPL_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "今日发送", value: "2,418" }, { label: "已送达", value: "2,301" }, { label: "失败", value: 117, tone: "down" }, { label: "送达率", value: "95.2%" }]} columns={REC_COLS} rows={REC_ROWS} />;
  }
}

function Inner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : TABS[0].key);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);
  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={setTab} />
      <Body tab={tab} />
    </div>
  );
}

export default function MessagingPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}

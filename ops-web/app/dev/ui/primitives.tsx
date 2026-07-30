"use client";

// 第一层：原语（components/ui/*，无业务语义）。判据见 components/README.md。
import * as React from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";
import { Tabs } from "@/components/ui/tabs";
import { Progress } from "@/components/ui/progress";
import { Notice } from "@/components/ui/notice";
import { StatCard, EmptyState, Skeleton, PageTitle, Pagination } from "@/components/ui/misc";
import { Tooltip } from "@/components/ui/tooltip";
import { Tree, type TreeNode } from "@/components/ui/tree";
import { Section, Row, Cell, Flaw, Hint, Missing, Specimen } from "./kit";
import { Probe } from "./probe";

const BTN_VARIANTS = ["default", "outline", "secondary", "ghost", "destructive"] as const;
const BTN_SIZES = ["default", "sm", "lg", "icon"] as const;
/** 各 variant 的 hover 终态类（从 button.tsx 的 cva 里逐条抄下来做**静态复刻**）。
 *  CSS 没法从外部强制 :hover，所以这里并排放一份"长这样"的复刻件。 */
const BTN_HOVER: Record<(typeof BTN_VARIANTS)[number], string> = {
  default: "opacity-90",
  outline: "bg-accent text-accent-foreground",
  secondary: "bg-accent",
  ghost: "bg-accent text-accent-foreground",
  destructive: "opacity-90",
};

const TONES: BadgeTone[] = ["default", "success", "warning", "danger", "info", "muted", "outline"];

export function PrimitiveSections() {
  return (
    <>
      <ButtonSection />
      <BadgeSection />
      <InputSection />
      <SelectSection />
      <DateInputSection />
      <CardSection />
      <TableSection />
      <TabsSection />
      <ProgressSection />
      <NoticeSection />
      <StatCardSection />
      <EmptyStateSection />
      <SkeletonSection />
      <PageTitleSection />
      <PaginationSection />
      <TooltipSection />
      <TreeSection />
    </>
  );
}

// ────────────────────────────────────────────────────────────── Button
function ButtonSection() {
  return (
    <Section id="button" name="Button" layer="原语" file="ui/button.tsx" purpose="所有可点击动作的唯一入口；5 variant × 4 size。">
      {BTN_VARIANTS.map((v) => (
        <Row key={v} label={v} note="default / hover(复刻) / focus 环(复刻) / disabled">
          {BTN_SIZES.map((s) => (
            <Cell key={s} label={s}>
              <Button variant={v} size={s}>{s === "icon" ? <Loader2 /> : "操作"}</Button>
            </Cell>
          ))}
          <Cell label="hover 复刻">
            <Button variant={v} className={BTN_HOVER[v]}>操作</Button>
          </Cell>
          <Cell label="focus 环复刻">
            <Button variant={v} className="ring-2 ring-ring">操作</Button>
          </Cell>
          <Cell label="disabled">
            <Button variant={v} disabled>操作</Button>
          </Cell>
        </Row>
      ))}

      <Row label="文字对比度" note="主按钮/危险按钮的文字压在实色底上，AA 阈值 4.5（14px bold 不算大字）">
        {BTN_VARIANTS.map((v) => (
          <Probe key={v}><Button variant={v}>操作</Button></Probe>
        ))}
      </Row>

      <Row label="disabled 对比度" note="opacity-50 后文字对比度会掉一半；WCAG 对 disabled 不做强制要求，但可读性仍是真实问题">
        <Probe><Button disabled>操作</Button></Probe>
        <Probe><Button variant="outline" disabled>操作</Button></Probe>
      </Row>

      <Missing>
        <b>没有 loading 态</b>。全站每个提交按钮都得自己拼 <code>disabled={"{"}submitting{"}"}</code> + 自己塞 spinner，
        于是"提交中"长什么样各页不一样。下面是手工拼的样子，不是组件能力：
        <Specimen className="mt-2">
          <Button disabled><Loader2 className="animate-spin" /> 保存中…</Button>
          <Button variant="outline" disabled><Loader2 className="animate-spin" /> 保存中…</Button>
        </Specimen>
      </Missing>
      <Flaw>
        <b>焦点环缺 offset</b>：cva 里只有 <code>focus-visible:ring-2 focus-visible:ring-ring</code>，
        没有 globals.css 要求的 <code>ring-offset-2 ring-offset-[--ring-offset-bg]</code>。
        实心主按钮上环贴着按钮边缘，与按钮本体几乎糊在一起（切到 mono 皮肤最明显：近黑环压在近黑底上）。
      </Flaw>
      <Flaw>
        <b>尺寸不走密度 token</b>：<code>h-9 / h-8 / h-10</code> 硬写，未用 <code>h-[var(--ctl-h)]</code>。
        结果是顶部的「密度」开关对按钮完全无效 —— 自己切一下就能看到。
      </Flaw>
      <Flaw>
        <b>圆角绕过四档</b>：写的是 <code>rounded-full</code>（Tailwind 默认阶）而不是 <code>rounded-chip</code>。
        当前两者数值相同所以看不出来，但改 <code>--r-chip</code> 时按钮不会跟着变。
      </Flaw>
      <Hint>
        真正的 <code>:focus-visible</code> 无法用 CSS 从外部强制触发，上面第 6 列是**静态复刻**。
        要验真实效果：点一下这段文字，然后连按 Tab 走一遍。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Badge
function BadgeSection() {
  return (
    <Section id="badge" name="Badge" layer="原语" file="ui/badge.tsx" purpose="全站色调联合的唯一真源（BadgeTone）；状态徽标的底层件。">
      <Row label="全部 tone" note="11px / font-bold / 药丸" stack>
        {TONES.map((t) => (
          <Cell key={t} label={t}>
            <Badge tone={t}>运行中</Badge>
          </Cell>
        ))}
      </Row>

      <Row label="实测对比度" note="每个 tone 的 --*-ink 压在 --*-tint 上的真实比值；随明暗/皮肤实时重量" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-4">
          {TONES.map((t) => (
            <div key={t} className="rounded-field bg-muted/60 p-2">
              <div className="mb-1 text-[10px] font-medium text-muted-foreground">tone=&quot;{t}&quot;</div>
              <Probe><Badge tone={t}>运行中</Badge></Probe>
            </div>
          ))}
        </div>
      </Row>

      <Row label="长文本 / 数字" note="徽标不换行；表格里常塞进「已归还(超时)」这类长词">
        <Badge tone="warning">已归还 · 超时 3 小时</Badge>
        <Badge tone="info" className="tabular-nums">1,204 台</Badge>
        <Badge tone="muted">—</Badge>
      </Row>

      <Hint>
        Badge 没有 disabled / loading / error 态 —— 它是纯展示件，这是**对的**，不算缺陷。
        它的"状态"维度就是 tone。
      </Hint>
      <Flaw>
        <b>圆角绕过四档</b>：<code>rounded-full</code> 而非 <code>rounded-chip</code>（同 Button）。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Input
const ERR_RING = "ring-2 ring-destructive"; // 抄自 form-drawer.tsx，组件本身不提供 invalid 入参

function InputSection() {
  const [v, setV] = React.useState("已填内容");
  return (
    <Section id="input" name="Input" layer="原语" file="ui/input.tsx" purpose="裸文本输入。筛选下拉请用 FilterSelect，搜索框在调用处传 rounded-full。">
      <Row label="default / 有值">
        <Input className="w-52" placeholder="请输入名称" />
        <Input className="w-52" value={v} onChange={(e) => setV(e.target.value)} />
      </Row>
      <Row label="focus 环复刻" note="真实 focus 请按 Tab">
        <Input className="w-52 ring-2 ring-ring" defaultValue="聚焦态" />
      </Row>
      <Row label="disabled / readOnly">
        <Input className="w-52" disabled placeholder="禁用" />
        <Input className="w-52" readOnly value="只读值" />
      </Row>
      <Row label="error" note="错误态由 FormDrawer 在调用处拼 ring-2 ring-destructive，Input 自己没有 invalid 入参">
        <div className="flex flex-col gap-1">
          <Input className={`w-52 ${ERR_RING}`} defaultValue="abc" />
          <span className="text-xs text-destructive">请输入数字</span>
        </div>
      </Row>
      <Row label="type 变体">
        <Input className="w-40" type="number" defaultValue={42} />
        <Input className="w-40" type="password" defaultValue="secret" />
        <Input className="w-52 rounded-full" placeholder="搜索（药丸变体）" />
      </Row>
      <Row label="对比度">
        <Probe pick={(r) => r.firstElementChild}><Input className="w-52" defaultValue="正文对比度" /></Probe>
        <Probe pick={(r) => r.firstElementChild}><Input className="w-52" placeholder="占位文字对比度" /></Probe>
      </Row>
      <Flaw>
        <b>错误态没有沉到组件里</b>：Input 不认 <code>invalid</code>，每个调用处自己拼 ring 类。
        FormDrawer 之外的手写表单（如 quick-actions 里的目标选择器）就没有错误态。
      </Flaw>
      <Flaw>
        <b>圆角写成 <code>rounded-[11px]</code> 魔数</b>，不是 <code>rounded-field</code>。数值对，但改 token 不跟随。
      </Flaw>
      <Flaw><b>高度 h-9 硬写</b>，密度开关无效。</Flaw>
      <Hint>
        占位文字用 <code>--muted-foreground</code>（对白底 3.9:1）。它低于 4.5 是**已知取舍**：
        占位符不是必读内容。但同一个色也用在表头/次要说明上，那些地方就需要复核。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Select
function SelectSection() {
  return (
    <Section id="select" name="Select" layer="原语" file="ui/input.tsx" purpose="裸原生下拉。列表页筛选请用组合件 FilterSelect。">
      <Row label="default / 有值">
        <Select defaultValue="a"><option value="a">已部署</option><option value="b">故障</option></Select>
        <Select className="w-52" defaultValue="b"><option value="a">已部署</option><option value="b">故障</option></Select>
      </Row>
      <Row label="focus 环复刻"><Select className="ring-2 ring-ring" defaultValue="a"><option value="a">聚焦态</option></Select></Row>
      <Row label="disabled"><Select disabled defaultValue="a"><option value="a">禁用</option></Select></Row>
      <Row label="error（调用处拼）"><Select className={ERR_RING} defaultValue=""><option value="">请选择</option></Select></Row>
      <Row label="空选项 / 长选项">
        <Select defaultValue=""><option value="">（无可选项）</option></Select>
        <Select className="max-w-64" defaultValue="a"><option value="a">迪拜 · 朱美拉海滩路 · 星巴克 JBR 店（3 号柜）</option></Select>
      </Row>
      <Flaw>
        <b>无 disabled 视觉</b>：Input 有 <code>disabled:opacity-50</code>，Select 的 cva 里**没有**，
        禁用下拉和可用下拉长得一模一样（上面那格自己看）。这是真缺陷不是风格差异。
      </Flaw>
      <Flaw><b>圆角魔数 <code>rounded-[11px]</code> + 高度 h-9 硬写</b>（同 Input）。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── DateInput
function DateInputSection() {
  return (
    <Section id="date-input" name="DateInput" layer="原语" file="ui/date-input.tsx" purpose="原生 date 输入，值恒为 YYYY-MM-DD。">
      <Row label="default / 有值">
        <DateInput className="w-44" />
        <DateInput className="w-44" defaultValue="2026-07-30" />
      </Row>
      <Row label="focus 环复刻"><DateInput className="w-44 ring-2 ring-ring" defaultValue="2026-07-30" /></Row>
      <Row label="disabled"><DateInput className="w-44" disabled defaultValue="2026-07-30" /></Row>
      <Row label="error（调用处拼）"><DateInput className={`w-44 ${ERR_RING}`} defaultValue="2026-07-30" /></Row>
      <Row label="min / max"><DateInput className="w-44" min="2026-07-01" max="2026-07-31" defaultValue="2026-07-30" /></Row>
      <Flaw>
        <b>与 Input 圆角不一致</b>：注释写「样式与 Input 完全一致」，实际 DateInput 用的是
        <code> rounded-lg</code>（= --radius-lg ≈ 13px），Input 是 <code>rounded-[11px]</code>。
        并排放在同一个筛选行里就是两种圆角 —— 上面那格 Input 与 DateInput 挨着看。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Card
function CardSection() {
  return (
    <Section id="card" name="Card" layer="原语" file="ui/card.tsx" purpose="内容容器。无 tone = 白底+阴影；有 tone = 语义 tint 底、无阴影。">
      <Row label="default（含子件）" stack>
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>机柜 CAB-000123</CardTitle>
            <CardDescription>迪拜 · JBR 星巴克 · 12 仓</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">CardContent 内容区（p-5 pt-0）。</CardContent>
        </Card>
      </Row>
      <Row label="全部 tone" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {(["primary", "success", "warning", "danger", "info"] as const).map((t) => (
            <Card key={t} tone={t} className="p-4 text-xs font-medium">tone=&quot;{t}&quot;</Card>
          ))}
        </div>
      </Row>
      <Row label="tone 上的文字对比度" note="tint 底 + 继承 --card-foreground，没有配套的 ink 色" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {(["primary", "success", "warning", "danger", "info"] as const).map((t) => (
            <Probe key={t}><Card tone={t} className="p-4 text-xs font-medium">tone={t}</Card></Probe>
          ))}
        </div>
      </Row>
      <Flaw>
        <b>圆角用 <code>rounded-[var(--radius)]</code></b>，不是 <code>rounded-card</code>。
        <code>--radius</code> 是为兼容老调用点留的别名，新写法应直接用四档。
      </Flaw>
      <Flaw>
        <b>tone 卡片的文字色没跟着换</b>：底换成了 tint，文字仍是 <code>--card-foreground</code>。
        暗色下 tint 底会变深，读数看上面那排 —— 目前勉强够，但这是巧合不是设计。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Table
function TableSection() {
  return (
    <Section id="table" name="Table / THead / TBody / TR / TH / TD" layer="原语" file="ui/table.tsx" purpose="裸表格：色块表头 + 隔行浅底，无行线。列表页请用 DataTable。">
      <Row label="有数据" stack>
        <Table>
          <THead><TR><TH>编号</TH><TH>站点</TH><TH className="text-end">仓位</TH></TR></THead>
          <TBody>
            {[1, 2, 3].map((i) => (
              <TR key={i}><TD>CAB-00{i}</TD><TD>JBR 星巴克</TD><TD className="text-end tabular-nums">12</TD></TR>
            ))}
          </TBody>
        </Table>
      </Row>
      <Row label="空 tbody" note="裸 Table 不管空态 —— 空态是 DataTable 的职责" stack>
        <Table><THead><TR><TH>编号</TH></TR></THead><TBody /></Table>
      </Row>
      <Row label="表头文字对比度" note="--muted-foreground 压在 --muted 上">
        <Probe pick={(r) => r.querySelector("th")}>
          <Table><THead><TR><TH>编号</TH></TR></THead><TBody /></Table>
        </Probe>
      </Row>
      <Flaw>
        <b>行高硬写 <code>[&amp;_th]:h-11</code> + <code>py-3</code></b>，没走 <code>--row-h</code>。
        「密度」开关对表格完全无效 —— 而列表页正是密度开关唯一要服务的场景。
      </Flaw>
      <Flaw>
        <b>表头文字对比度不足</b>：<code>--muted-foreground</code>（#8a8d97）压在 <code>--muted</code>（#f2f3f6）上，
        实测见上（明色下约 3.6:1，低于 4.5）。表头是**必读**内容，不是占位符，这条要修。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tabs
function TabsSection() {
  const [a, setA] = React.useState("all");
  const [b, setB] = React.useState("t3");
  return (
    <Section id="tabs" name="Tabs" layer="原语" file="ui/tabs.tsx" purpose="页内维度切换的分段控件（非 tab 导航，导航用 TabHeader）。">
      <Row label="两项" stack>
        <Tabs tabs={[{ key: "all", label: "全部" }, { key: "on", label: "在线" }]} value={a} onChange={setA} />
      </Row>
      <Row label="多项换行" note="容器 flex-wrap，窄屏会折行" stack>
        <Tabs
          tabs={Array.from({ length: 8 }, (_, i) => ({ key: `t${i}`, label: `维度 ${i + 1}` }))}
          value={b}
          onChange={setB}
        />
      </Row>
      <Row label="单项" note="只有一项时仍渲染整条槽（没有退化处理）" stack>
        <Tabs tabs={[{ key: "only", label: "唯一维度" }]} value="only" onChange={() => {}} />
      </Row>
      <Flaw><b>没有 focus-visible 环</b>：tab 按钮键盘走过去完全没有可见焦点。</Flaw>
      <Flaw>
        <b>圆角 <code>rounded-xl</code> / <code>rounded-lg</code> 绕过四档</b>；
        而 TabHeader 里的同类分段控件用的是 <code>rounded-full</code> —— 两个分段控件形状不一样，
        同一个页面里可能同时出现（TabHeader 在顶、Tabs 在内容区）。
      </Flaw>
      <Flaw><b>无 disabled 项能力</b>：分期屏蔽只在 TabHeader 里做了，Tabs 没有。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Progress
function ProgressSection() {
  return (
    <Section id="progress" name="Progress" layer="原语" file="ui/progress.tsx" purpose="「已用/总数 (百分比)」+ 细条；warnAt 到阈值转红。">
      <Row label="0 / 中间 / 满" stack>
        <div className="flex flex-wrap gap-6">
          <Progress value={0} total={12} />
          <Progress value={7} total={12} />
          <Progress value={12} total={12} />
        </div>
      </Row>
      <Row label="warnAt=90" note="未达阈值走主色，达到转 destructive" stack>
        <div className="flex flex-wrap gap-6">
          <Progress value={8} total={10} warnAt={90} />
          <Progress value={10} total={10} warnAt={90} />
        </div>
      </Row>
      <Row label="showText=false"><Progress className="w-40" value={3} total={10} showText={false} /></Row>
      <Row label="total=0（除零）" note="组件内做了 total>0 判断，退化为 0%"><Progress value={0} total={0} /></Row>
      <Row label="超额（value>total）" note="pct 被 Math.min 夹到 100，但文字仍显示 15/12"><Progress value={15} total={12} /></Row>
      <Flaw>
        <b>无 loading / 不确定态</b>：数据未回来时调用方只能传 0/0，看起来像"额度为零"，
        与"还没加载"分不清。
      </Flaw>
      <Flaw>
        <b>无 a11y 语义</b>：没有 <code>role=&quot;progressbar&quot;</code> / aria-valuenow，读屏读不出进度。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Notice
function NoticeSection() {
  return (
    <Section id="notice" name="Notice" layer="原语" file="ui/notice.tsx" purpose="页内灰底提示条，说明「当前视图为什么少了点什么」。">
      <Row label="default" stack><Notice>当前仅展示未归档记录。</Notice></Row>
      <Row label="长文本" stack>
        <Notice>
          当前角色的数据权限为「本机构及下级」，列表已按此过滤；跨机构数据需要平台超管授权后才能查看，
          导出同样受此范围限制。
        </Notice>
      </Row>
      <Row label="对比度"><Probe><Notice className="mb-0">灰底灰字</Notice></Probe></Row>
      <Flaw>
        <b>只有一种 tone</b>：告警/危险类提示没有对应形态，页面遇到就自己拼一个彩色 div，
        于是「提示条」在不同页面长得不一样。
      </Flaw>
      <Flaw><b>圆角 <code>rounded-lg</code> 绕过四档</b>。</Flaw>
      <Flaw>
        <b>灰字压灰底的对比度不足</b>（读数见上）。提示条的内容是**要读的**，不是装饰。
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── StatCard
function StatCardSection() {
  return (
    <Section id="stat-card" name="StatCard" layer="原语" file="ui/misc.tsx" purpose="工作台 KPI 卡：标签 + 大数 + 同比小字。">
      <Row label="全部形态" stack>
        <div className="grid w-full gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="今日订单" value="1,204" />
          <StatCard label="今日营收" value="AED 8,430" sub="+12.4% 环比" tone="up" />
          <StatCard label="离线机柜" value="37" sub="-3 台" tone="down" />
          <StatCard label="加载中" value={<Skeleton className="h-6 w-20" />} />
        </div>
      </Row>
      <Row label="sub 文字对比度" note="tone=up 直接用 --success 原值当文字色（不是 --success-ink）" stack>
        <div className="flex flex-wrap gap-3">
          <Probe pick={(r) => r.querySelector(".text-xs")}><StatCard label="上升" value="1" sub="+12.4%" tone="up" /></Probe>
          <Probe pick={(r) => r.querySelector(".text-xs")}><StatCard label="下降" value="1" sub="-3 台" tone="down" /></Probe>
        </div>
      </Row>
      <Flaw>
        <b>直接用 <code>--success</code> / <code>--destructive</code> 当文字色</b>，没走 <code>--*-ink</code>。
        这正是 Badge 修过的那个坑，在 StatCard 里原样存在（读数见上，明色下绿字不过 AA）。
      </Flaw>
      <Flaw>
        <b>没复用 Card</b>：自己写了 <code>rounded-xl bg-card p-5 shadow-…</code>，
        圆角比 Card 大一档，工作台上 KPI 卡与下面的表格卡圆角对不齐。
      </Flaw>
      <Missing>无 loading 骨架入参。上面第四格是调用方自己塞 Skeleton 进 value 拼的。</Missing>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── EmptyState
function EmptyStateSection() {
  return (
    <Section id="empty-state" name="EmptyState" layer="原语" file="ui/misc.tsx" purpose="空态占位。文案要写清「为什么空、下一步做什么」。">
      <Row label="仅标题" stack><EmptyState title="暂无数据" /></Row>
      <Row label="标题 + 说明（推荐写法）" stack>
        <EmptyState title="当前筛选条件下没有机柜" desc="试试清空「状态」筛选，或到「设备台账」新增一台。" />
      </Row>
      <Row label="对比度">
        <Probe pick={(r) => r.querySelector(".text-xs")}>
          <EmptyState title="标题" desc="说明文字压在 muted/50 底上" />
        </Probe>
      </Row>
      <Flaw><b>圆角 <code>rounded-xl</code> 绕过四档</b>。</Flaw>
      <Missing>
        无插图 / 无动作按钮槽位。「下一步做什么」只能塞进 desc 文字里，点不了。
      </Missing>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Skeleton
function SkeletonSection() {
  return (
    <Section id="skeleton" name="Skeleton" layer="原语" file="ui/misc.tsx" purpose="加载骨架块。">
      <Row label="常见尺寸" stack>
        <div className="w-full max-w-md space-y-2">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="size-10 rounded-full" />
        </div>
      </Row>
      <Flaw><b>圆角 <code>rounded-md</code> 绕过四档</b>：骨架块和它要替代的真实控件（11px/14px）圆角不同，加载完会"跳形状"。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── PageTitle
function PageTitleSection() {
  return (
    <Section id="page-title" name="PageTitle" layer="原语" file="ui/misc.tsx" purpose="紧凑页头：标题 + 说明小字 + 右侧动作。">
      <Row label="仅标题" stack><PageTitle title="设备台账" /></Row>
      <Row label="标题 + 说明" stack><PageTitle title="设备台账" desc="共 1,204 台，其中离线 37 台" /></Row>
      <Row label="带动作" stack><PageTitle title="设备台账" desc="共 1,204 台" action={<Button size="sm">新增机柜</Button>} /></Row>
      <Row label="超长说明（截断）" stack>
        <PageTitle title="设备台账" desc={"很长的说明".repeat(20)} action={<Button size="sm">新增</Button>} />
      </Row>
      <Hint>与 TabHeader 是竞争关系：两者都是页头，字号一致（17px/extrabold）但一个带 tab 一个不带。</Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Pagination
function PaginationSection() {
  const [p, setP] = React.useState(3);
  return (
    <Section id="pagination" name="Pagination" layer="原语" file="ui/misc.tsx" purpose="上一页/下一页 + 总数。">
      <Row label="首页（上一页禁用）" stack><Pagination page={1} size={20} total={200} onPage={() => {}} /></Row>
      <Row label="中间页（可交互）" stack><Pagination page={p} size={20} total={200} onPage={setP} /></Row>
      <Row label="末页（下一页禁用）" stack><Pagination page={10} size={20} total={200} onPage={() => {}} /></Row>
      <Row label="只有一页" stack><Pagination page={1} size={20} total={3} onPage={() => {}} /></Row>
      <Row label="空列表（total=0）" stack><Pagination page={1} size={20} total={0} onPage={() => {}} /></Row>
      <Flaw><b>没用 Button 原语</b>：自己写了两个裸 <code>&lt;button&gt;</code>（<code>rounded-lg bg-secondary</code>），
        既绕过四档圆角，也**没有 focus-visible 环** —— 键盘翻页看不到焦点在哪。</Flaw>
      <Missing>无每页条数选择、无跳页输入。数据量大的页面（订单/流水）实际不够用。</Missing>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tooltip
function TooltipSection() {
  return (
    <Section id="tooltip" name="Tooltip" layer="原语" file="ui/tooltip.tsx" purpose="自绘文字提示（portal + fixed），替代原生 title。">
      <Row label="四个方向" note="悬停或键盘聚焦触发，延迟 120ms">
        {(["top", "right", "bottom", "left"] as const).map((s) => (
          <Tooltip key={s} label={`提示：${s}`} side={s}>
            {(p) => <Button {...p} variant="outline" size="sm">{s}</Button>}
          </Tooltip>
        ))}
      </Row>
      <Row label="label 为空" note="完全透传，不挂任何事件">
        <Tooltip>{(p) => <Button {...p} variant="ghost" size="sm">无提示</Button>}</Tooltip>
      </Row>
      <Row label="超长文本" note="max-w-240px + truncate">
        <Tooltip label="这是一段很长很长的提示文本，用来验证浮层是否会被截断以及是否会撑破视口边界">
          {(p) => <Button {...p} variant="outline" size="sm">长文本</Button>}
        </Tooltip>
      </Row>
      <Flaw>
        <b>层级硬写 <code>z-[200]</code></b>，没走 <code>z-[var(--z-tooltip)]</code>（=80）。
        虽然 200 &gt; 80 目前"碰巧"仍在最上，但层级阶就此失效 —— 下一个硬写 z-999 的浮层会把它盖掉。
      </Flaw>
      <Flaw><b>圆角 <code>rounded-md</code> 绕过四档</b>。</Flaw>
      <Flaw><b>过渡缺失</b>：浮层是瞬现瞬灭，没有用 <code>--dur/--ease</code>，与其它浮层（抽屉有 animate-in）观感不一致。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tree
const TREE: TreeNode[] = [
  {
    key: "org",
    label: "迪拜运营中心",
    extra: <Badge tone="muted">32 人</Badge>,
    children: [
      { key: "org-a", label: "运维一组", extra: <Badge tone="muted">12 人</Badge>, children: [
        { key: "org-a1", label: "张三（组长）" },
        { key: "org-a2", label: "李四" },
      ] },
      { key: "org-b", label: "客服组", extra: <Badge tone="muted">8 人</Badge> },
    ],
  },
];

function TreeSection() {
  const [checked, setChecked] = React.useState<string[]>(["org-a1"]);
  return (
    <Section id="tree" name="Tree" layer="原语" file="ui/tree.tsx" purpose="层级本身即信息的列表：组织架构、权限码目录。勾选树只认叶子 key。">
      <Row label="只读树" stack><Tree nodes={TREE} empty="暂无组织" /></Row>
      <Row label="勾选树（含父节点半选）" stack>
        <Tree nodes={TREE} empty="暂无组织" checkable checkedKeys={checked} onCheckedChange={setChecked} />
      </Row>
      <Row label="disabled" stack>
        <Tree nodes={TREE} empty="暂无组织" checkable checkedKeys={checked} onCheckedChange={() => {}} disabled />
      </Row>
      <Row label="collapseFrom=1（深层默认收起）" stack><Tree nodes={TREE} empty="暂无组织" collapseFrom={1} /></Row>
      <Row label="loading" stack><Tree nodes={TREE} empty="暂无组织" loading /></Row>
      <Row label="empty" stack><Tree nodes={[]} empty="该角色还没有分配任何功能权限，点右上「编辑」开始分配。" /></Row>
      <Flaw><b>展开箭头按钮无 focus-visible 环</b>，且圆角写的是 <code>rounded</code>/<code>rounded-md</code>。</Flaw>
      <Flaw>
        <b>loading / empty 直接返回裸 <code>&lt;span&gt;</code></b>，不是 Skeleton / EmptyState。
        同一个页面里"加载中"三种长相：DataTable 是骨架块、Tree 是一行灰字、Timeline 又是另一行灰字。
      </Flaw>
      <Flaw><b>loadingText 默认值是中文硬编码</b>（&quot;加载中…&quot;），没走 i18n —— 阿语/英语下会漏出中文。</Flaw>
      <Flaw><b>展开/收起的 aria-label 也是中文硬编码</b>（&quot;收起&quot;/&quot;展开&quot;）。</Flaw>
    </Section>
  );
}

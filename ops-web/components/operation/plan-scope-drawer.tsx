"use client";

// 收费方案的「适用范围」维护抽屉 —— **取价的唯一依据**（ADR-028 / V49）。
//
// 2026-09-23 之前这里根本没有入口：方案表单上的「适用范围」是一个自由文本描述框
// （「默认 / 机场点位 / 商场点位」），而真正被取价引擎读的 `price_plan_scope`
// 只有种子能写。「配了不生效」的另一半是**压根没法配**。
import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { notify } from "@/lib/notify";
import { Drawer } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { SCOPE_LEVELS, SCOPE_LEVEL_LABEL, SCOPE_ALL_REF, type PlanScope, type PricePlan } from "@/lib/types";

/** 每一层的引用取自哪张表 —— 决定表单里那个下拉用什么选项。 */
type RefSource = "site" | "location" | "venue" | "agent" | "scene" | "region" | "cabinet" | "none";
const REF_SOURCE: Record<PlanScope["scopeType"], RefSource> = {
  DEVICE: "cabinet", LOCATION: "location", SITE: "site", VENUE: "venue",
  AGENT: "agent", SCENE: "scene", REGION: "region", ALL: "none",
};

export function PlanScopeDrawer({
  plan, onClose, canWrite,
}: { plan: PricePlan | null; onClose: () => void; canWrite: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [form, setForm] = React.useState<Partial<PlanScope> | null>(null);
  const planNo = plan?.planNo ?? "";

  const q = useQuery({
    queryKey: ["op", "plan-scopes", planNo],
    queryFn: () => api.listPlanScopes(planNo),
    enabled: !!planNo,
  });

  // 各层引用的选项源。一次拉全量：这些都是配置量级，分页的下拉会让人以为「我的站点不见了」。
  const opts = useQuery({
    queryKey: ["op", "scope-ref-options"],
    queryFn: async () => {
      const [sites, locations, venues, agents, regions, cabinets] = await Promise.all([
        api.listSites({ page: 1, size: UNPAGED_SIZE }),
        api.listLocations({ page: 1, size: UNPAGED_SIZE }),
        api.listVenues({ page: 1, size: UNPAGED_SIZE }),
        api.listAgents({ page: 1, size: UNPAGED_SIZE }),
        api.listRegions({ page: 1, size: UNPAGED_SIZE }),
        api.listCabinets({ page: 1, size: UNPAGED_SIZE }),
      ]);
      return {
        site: sites.list.map((x) => ({ value: x.siteNo, label: `${x.name}（${x.siteNo}）` })),
        location: locations.list.map((x) => ({ value: x.locationNo, label: `${x.name}（${x.locationNo}）` })),
        venue: venues.list.map((x) => ({ value: x.venueNo, label: `${x.name}（${x.venueNo}）` })),
        agent: agents.list.map((x) => ({ value: x.agentNo, label: `${x.name}（${x.agentNo}）` })),
        region: regions.list.filter((x) => x.level === 3)
          .map((x) => ({ value: x.regionId, label: `${x.name}（${x.regionId}）` })),
        cabinet: cabinets.list.map((x) => ({ value: x.cabinetNo, label: `${x.cabinetNo}` })),
        scene: [...new Set(sites.list.map((x) => x.sceneType))].map((s) => ({ value: s, label: s })),
        vendors: [...new Set(cabinets.list.map((c) => c.vendorCode))].filter(Boolean)
          .map((v) => ({ value: v as string, label: v as string })),
        models: [...new Set(cabinets.list.map((c) => c.model))].filter(Boolean)
          .map((v) => ({ value: v as string, label: v as string })),
        // 设备类型目前只有充电宝：`Cabinet` 上还没有这一列（ADR-021 的设备类型抽象未落到机柜档案）。
        // 写死一项而不是留空下拉 —— 留空会让「设备类型必填」变成一个填不了的必填。
        // 等机柜带上 deviceType 后，这里改成从机柜去重即可。
        deviceTypes: [{ value: "POWERBANK", label: "充电宝 POWERBANK" }],
      };
    },
    enabled: !!planNo,
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ["op", "plan-scopes", planNo] });
  const save = useMutation({
    mutationFn: (v: Partial<PlanScope>) => api.savePlanScope(planNo, v),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api.removePlanScope(planNo, id),
    onSuccess: () => { refresh(); notify.success("已删除"); },
  });

  const level = (form?.scopeType ?? "SITE") as PlanScope["scopeType"];
  const src = REF_SOURCE[level];
  const refOptions = src === "none" ? [] : (opts.data?.[src] ?? []);

  /**
   * @form POST /api/trade/price-plans/{planNo}/scopes
   */
  const fields: FieldDef[] = [
    {
      key: "deviceType", label: "设备类型", type: "select", required: true, section: "范围",
      options: [{ value: "", label: "请选择设备类型" },
        ...(opts.data?.deviceTypes ?? [])],
      help: "硬过滤：只有同类型设备的订单才会命中这条。缺了它，站点规则会把充电宝的价给按摩椅",
    },
    {
      key: "scopeType", label: "层", type: "select", required: true, section: "范围",
      options: SCOPE_LEVELS.map((l) => ({ value: l, label: `${SCOPE_LEVEL_LABEL[l]}（${l}）` })),
      help: "越具体越优先：单台 > 点位 > 站点 > 场地方 > 代理商 > 场景 > 区域 > 默认",
    },
    src === "none"
      ? { key: "scopeRef", label: "引用", section: "范围", readOnlyOnEdit: true,
          placeholder: SCOPE_ALL_REF, help: "默认层对该设备类型的所有订单生效，无需指定对象" }
      : { key: "scopeRef", label: SCOPE_LEVEL_LABEL[level], type: "select", required: true, section: "范围",
          options: [{ value: "", label: `请选择${SCOPE_LEVEL_LABEL[level]}` }, ...refOptions],
          help: "必须指向真实存在的对象——悬空的范围取价永远命中不到，而且不报错" },
    {
      key: "vendorCode", label: "厂商（可选）", type: "select", section: "过滤条件",
      options: [{ value: "", label: "不限" }, ...(opts.data?.vendors ?? [])],
      help: "留空 = 不限。填了就比同层不填的更具体，同层内胜出",
    },
    { key: "model", label: "型号（可选）", type: "select", section: "过滤条件",
      options: [{ value: "", label: "不限" }, ...(opts.data?.models ?? [])] },
    { key: "priority", label: "优先级", type: "number", min: 0, section: "裁决",
      help: "层与过滤条件都并列时才看它，数值大者优先" },
    { key: "effectiveFrom", label: "生效起", type: "date", section: "裁决", help: "留空 = 立即生效" },
    { key: "effectiveTo", label: "生效止", type: "date", section: "裁决", help: "留空 = 长期有效" },
  ];

  const cols: Column<PlanScope>[] = [
    { header: "层", className: "whitespace-nowrap", cell: (x) => (
      <div>
        <Badge tone={x.scopeType === "ALL" ? "muted" : "default"}>{SCOPE_LEVEL_LABEL[x.scopeType]}</Badge>
        <div className="truncate txt-caption text-muted-foreground">{x.scopeRef}</div>
      </div>
    ) },
    { header: "设备类型", className: "whitespace-nowrap", cell: (x) => x.deviceType },
    { header: "过滤", className: "whitespace-nowrap", cell: (x) => {
      const ps = [x.vendorCode, x.model, x.brandNo].filter(Boolean);
      return ps.length ? <span className="txt-caption">{ps.join(" · ")}</span> : <span className="text-muted-foreground">不限</span>;
    } },
    { header: "优先级", className: "whitespace-nowrap text-right", cell: (x) => x.priority ?? 0 },
    { header: "生效期", className: "whitespace-nowrap", cell: (x) => (
      <span className="txt-caption text-muted-foreground">
        {(x.effectiveFrom ?? "").slice(0, 10) || "立即"} ~ {(x.effectiveTo ?? "").slice(0, 10) || "长期"}
      </span>
    ) },
    { header: "操作", cell: (x) => canWrite ? (
      <div className="flex w-max gap-2">
        <Button size="sm" variant="outline" onClick={() => setForm({ ...x })}>编辑</Button>
        <Button size="sm" variant="outline" onClick={async () => {
          const ok = await confirm({
            title: "删除这条适用范围",
            desc: `删除后，原本命中它的订单会按更宽的层取价（比如落到默认方案）。`,
            danger: true, confirmText: "删除",
          });
          if (ok && x.id != null) remove.mutate(x.id);
        }}><Trash2 className="size-4" /></Button>
      </div>
    ) : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <>
      <Drawer
        open={!!plan}
        onOpenChange={(o) => !o && onClose()}
        title={`适用范围 · ${plan?.name ?? ""}`}
        desc="订单落在哪里就按哪条收费。越具体的层越优先"
        width="w-[760px]"
      >
        <Notice>
          取价时按「越具体越优先」裁决：单台 &gt; 点位 &gt; 站点 &gt; 场地方 &gt; 代理商 &gt; 场景 &gt; 区域 &gt; 默认。
          同一层里，填了厂商/型号的比没填的更具体。
          <b>同一个范围只能属于一个方案</b>——否则取价只能靠优先级猜。
        </Notice>
        {canWrite && (
          <div className="my-3">
            <Button size="sm" onClick={() => setForm({ scopeType: "SITE", deviceType: "POWERBANK", priority: 0 })}>
              <Plus className="size-4" /> 新增范围
            </Button>
          </div>
        )}
        <DataTable
          rowKey={(x: PlanScope) => String(x.id)}
          columns={cols}
          rows={q.data}
          loading={q.isLoading}
          error={q.error}
          onRetry={q.refetch}
          empty="这个方案还没有任何适用范围——没有范围的方案永远不会被命中，等于没启用。"
        />
        {dialog}
      </Drawer>

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增适用范围"
        titleEdit="编辑适用范围"
        isEdit={form?.id != null}
        fields={fields}
        value={(form ?? {}) as Record<string, unknown>}
        // 换层就清空引用：站点号塞进点位层是悬空引用，服务端会拒，但别让人到提交才发现
        onChange={(v) => {
          const next = v as Partial<PlanScope>;
          setForm((prev) => (prev && next.scopeType !== prev.scopeType
            ? { ...next, scopeRef: next.scopeType === "ALL" ? SCOPE_ALL_REF : "" }
            : next));
        }}
        onSubmit={() => form && save.mutate({
          ...form,
          scopeRef: form.scopeType === "ALL" ? SCOPE_ALL_REF : form.scopeRef,
        })}
        submitting={save.isPending}
        width="w-[520px]"
      />
    </>
  );
}

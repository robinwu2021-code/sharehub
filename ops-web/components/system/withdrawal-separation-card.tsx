"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/input";
import { ReadOnlyNotice } from "@/components/read-only-notice";

const KEY = "finance.withdrawal.separation";

/** 三档各自的后果 —— 只给档位名等于没给，看的人要能据此选。 */
const MODES = [
  { value: "REQUIRE", label: "禁止（默认）", desc: "审批人不能再登记打款回执，违反时拒绝并提示换人。适合有两个及以上财务。" },
  { value: "WARN", label: "允许但记录", desc: "放行，同时记一条告警日志并在提现列表标「同一人经办」。适合暂时只有一个人、但要留痕。" },
  { value: "OFF", label: "不检查", desc: "不做判定。注意：列表上的「同一人经办」标记照旧 —— 关掉的是拦截，不是记录。" },
];

/**
 * 提现「审批人是否可兼放款人」（2026-09-28 裁决：不硬拦，由管理员配）。
 *
 * <h3>为什么三档而不是开关</h3>
 * 布尔只有「拦」和「不拦」，而真实处境常是第三种：知道不合规、暂时只有一个人、
 * 但希望这件事被记录下来。只给两态会逼人选「不检查」，那条信息就永远丢了。
 *
 * <h3>为什么不放进通用的系统参数表单</h3>
 * 那里的 value 是自由文本。打成 `REQUIER` 时服务端会**静默回落成 REQUIRE**
 * （合规配置认不出的值一律从严，不能变成「不检查」）—— 安全，但操作的人
 * 会以为自己改成了别的档。三选一就不会有这个问题。
 */
export function WithdrawalSeparationCard({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const [picked, setPicked] = useState<string>("");

  const q = useQuery({
    queryKey: ["sys", "params", KEY],
    queryFn: async () => {
      /*
       * 按 param_key 关键词筛，**不要拉一页再在前端找** ——
       * 那样要写死一个 size，参数一旦超过它就静默漏读（table-wiring 棘轮拦的正是这个）。
       * 服务端的 keywordColumns 含 param_key，筛得到。
       */
      const page = await api.listSysParams({ keyword: KEY });
      return page.list.find((p) => p.paramKey === KEY)?.value || "REQUIRE";
    },
  });

  const save = useMutation({
    mutationFn: (mode: string) =>
      api.saveSysParam({ paramKey: KEY, value: mode, label: "提现审批放款分离", groupName: "财务" }),
    onSuccess: () => {
      setPicked("");
      qc.invalidateQueries({ queryKey: ["sys", "params", KEY] });
      qc.invalidateQueries({ queryKey: ["system"] });
    },
  });

  const current = q.data ?? "REQUIRE";
  const value = picked || current;
  const changed = !!picked && picked !== current;
  const hint = MODES.find((m) => m.value === value)?.desc ?? "";

  return (
    <Card className="mb-4 p-4">
      <div className="mb-1 txt-section">提现：审批人能否兼放款人</div>
      <p className="mb-3 txt-caption text-muted-foreground">
        审批与放款是两个动作，各有各的权限码。这里决定同一个人能不能两步都做。
      </p>

      {q.isLoading && <div className="txt-body text-muted-foreground">读取中…</div>}
      {q.error && (
        <div className="txt-body text-danger">
          读取失败。<Button variant="outline" size="sm" onClick={() => q.refetch()}>重试</Button>
        </div>
      )}

      {!q.isLoading && !q.error && (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="txt-caption text-muted-foreground">执行强度</span>
              <Select
                value={value}
                disabled={!canEdit || save.isPending}
                onChange={(e) => setPicked(e.target.value)}
                className="w-48"
              >
                {MODES.map((m) => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </Select>
            </label>
            {canEdit && (
              <Button disabled={!changed || save.isPending} onClick={() => save.mutate(picked)}>
                {save.isPending ? "保存中…" : "保存"}
              </Button>
            )}
          </div>

          {/* 把选中那一档的后果写出来 —— 档位名（「允许但记录」）本身说不清它到底记了什么 */}
          <p className="mt-2 txt-caption text-muted-foreground">{hint}</p>

          {save.error && <p className="mt-2 txt-caption text-danger">{(save.error as Error).message}</p>}
          {!canEdit && (
            <ReadOnlyNotice className="mt-3" what="提现审批放款分离设置"
                            perm="system:param:update" note="只能查看当前配置，不能修改" />
          )}
        </>
      )}
    </Card>
  );
}

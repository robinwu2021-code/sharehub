"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/input";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";

/**
 * 业务时区设置（2026-09-28 裁决：UTC+4 阿联酋，可配，改动需管理员权限）。
 *
 * <h3>为什么不是系统参数表里的一行</h3>
 * 那张表的 `value` 是自由文本。时区打错一个字母（`Asia/Dubay`）后端会 400，
 * 但运营看到的只是一句报错，不知道正确写法长什么样。改成下拉，选不错。
 *
 * <h3>候选与当前值都由后端给</h3>
 * 前端写死一份列表，早晚与后端 `ZoneId` 能解析的集合漂移 ——
 * 漂移的表现是「界面上选得到、保存时报 400」。
 * 同时回显**当前业务时刻**：光看 `Asia/Dubai` 看不出选对没有，
 * 看到「现在是 09-28 15:20」就能一眼核。
 *
 * <h3>为什么要二次确认，且必须写那句话</h3>
 * 改时区**不重算任何历史数据** —— 时间戳、UTC 串、人填的业务日期、
 * 已定格的分润归属月一律不动。这句话如果只写在文档里，操作的人看不到，
 * 而他最可能的误解恰恰是「改完历史数据会跟着对齐」。
 */
export function BizTimezoneCard({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [picked, setPicked] = useState<string>("");

  const q = useQuery({
    queryKey: ["sys", "timezones"],
    queryFn: () => api.getTimezoneOptions(),
  });

  const save = useMutation({
    mutationFn: (zone: string) =>
      api.saveSysParam({ paramKey: "biz.timezone", value: zone, label: "业务时区", groupName: "系统" }),
    onSuccess: () => {
      setPicked("");
      // 两个都要失效：时区卡片自己，以及系统参数列表（那里也显示这一行）
      qc.invalidateQueries({ queryKey: ["sys", "timezones"] });
      qc.invalidateQueries({ queryKey: ["system"] });
    },
  });

  const current = q.data?.current ?? "";
  const value = picked || current;
  const changed = !!picked && picked !== current;

  return (
    <Card className="mb-4 p-4">
      <div className="mb-1 txt-section">业务时区</div>
      <p className="mb-3 txt-caption text-muted-foreground">
        合同起止日、券有效期、免费额度的自然月、分润归属月都按这个时区切。
        服务器时区与它不同是正常的 —— 业务日期只认这里。
      </p>

      {q.isLoading && <div className="txt-body text-muted-foreground">读取中…</div>}
      {q.error && (
        <div className="txt-body text-danger">
          读取失败。<Button variant="outline" size="sm" onClick={() => q.refetch()}>重试</Button>
        </div>
      )}

      {q.data && (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="txt-caption text-muted-foreground">时区</span>
              <Select
                value={value}
                disabled={!canEdit || save.isPending}
                onChange={(e) => setPicked(e.target.value)}
                className="w-56"
              >
                {q.data.options.map((z) => (
                  <option key={z} value={z}>{z}</option>
                ))}
              </Select>
            </label>

            <div className="flex flex-col gap-1">
              <span className="txt-caption text-muted-foreground">当前业务时刻</span>
              {/* 回显真实值而不是前端自己算 —— 前端算等于把同一个口径实现两遍 */}
              <span className="txt-body tabular-nums">{q.data.currentBizTime}</span>
            </div>

            {canEdit && (
              <Button
                disabled={!changed || save.isPending}
                onClick={async () => {
                  const ok = await confirm({
                    title: `把业务时区改成 ${picked}？`,
                    desc:
                      "改后只影响新产生的业务日期，已有记录不重算：" +
                      "已开出的合同、已定格的分润归属月、已判定的券有效期都保持原样。" +
                      "这不是遗漏 —— 追溯重算会让已经发出去的结算单金额变化。",
                    confirmText: "确认修改",
                  });
                  if (ok) save.mutate(picked);
                }}
              >
                {save.isPending ? "保存中…" : "保存"}
              </Button>
            )}
          </div>

          {save.error && (
            <p className="mt-2 txt-caption text-danger">{(save.error as Error).message}</p>
          )}
          {!canEdit && <ReadOnlyNotice className="mt-3" what="业务时区设置" perm="system:param:update" note="只能查看当前时区，不能修改" />}
        </>
      )}
      {dialog}
    </Card>
  );
}

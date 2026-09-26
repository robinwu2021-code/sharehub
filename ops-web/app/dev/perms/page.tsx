"use client";

// 角色 × 权限对照表。**dev-only 工具页**：不在 lib/nav.ts 里、不进阶段门禁、
// 不出现在任何菜单，只能靠 URL 直达（/dev/perms）。不是给运营用的。
//
// ── 为什么值得存在 ──────────────────────────────────────────────────────
// RBAC 的错误几乎都不是「报错」，而是**沉默的**：
//   · 某个角色少了一个码 → 他登录后只是「看不到那个菜单」，没人会收到任何提示；
//   · 多了一个码更糟 → 不该动钱的人能点分账，要等出事才发现。
// 守卫测试（lib/perm-map.test.ts）锁住了关键几条，这一页负责让人**一眼扫完全局**。
//
// 2026-09-23 本页落地时，前端镜像与后端 RolePerms.java 刚对平 29 处漂移。
// 数据源就是 BACKEND_ROLE_PERMS —— 它与后端逐码一致由守卫测试强制。

import * as React from "react";
import { NAV } from "@/lib/nav";
import { BACKEND_ROLE_PERMS, ROLE_LABEL, roleHas, canModule, permsOf } from "@/lib/permissions";
import { UI_PERM_MAP, UNIMPLEMENTED } from "@/lib/perm-map";
import type { Role } from "@/lib/auth";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { PageTitle } from "@/components/ui/misc";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";

const ROLES = Object.keys(ROLE_LABEL) as Role[];

/** 高危动作：不该并进模块通配，谁持有必须一眼看得出来。 */
const CRITICAL = [
  "finance:withdrawal:audit",
  "finance:share_rule:config",
  "order:refund:audit",
  "agent:scope:assign",
  "org:role:update",
  "system:login_setting:update",
] as const;

/** 导航里出现过的码，按 section 归组 —— 这就是「实际会影响界面」的码集合。 */
function permGroups() {
  return NAV.map((s) => ({
    key: s.key,
    label: s.label,
    codes: [...new Set((s.children ?? []).map((l) => l.perm).filter((x): x is string => !!x))].sort(),
  })).filter((g) => g.codes.length > 0);
}

export default function DevPermsPage() {
  const groups = React.useMemo(permGroups, []);
  const unmapped = React.useMemo(
    () => groups.flatMap((g) => g.codes).filter((c) => !(c in UI_PERM_MAP)),
    [groups],
  );

  return (
    <div className="space-y-8 p-6">
      <div className="space-y-2">
        <PageTitle title="角色 × 权限对照" />
        <p className="txt-body text-muted-foreground">
          判权真源是后端下发的 <code>perms</code>；本页读 <code>BACKEND_ROLE_PERMS</code>（后端
          <code>RolePerms.java</code> 的前端镜像，逐码一致由 <code>perm-map.test.ts</code> 强制）。
          <strong className="text-foreground">看得到入口 ≠ 调得通接口</strong> —— 后端每个端点独立判权。
        </p>
      </div>

      {unmapped.length > 0 && (
        <div className="rounded-card border border-destructive/40 bg-destructive/5 p-4">
          <div className="txt-body font-medium text-destructive">
            {unmapped.length} 个导航码没登记进 UI_PERM_MAP —— 它们在界面上一律判无权限
          </div>
          <div className="mt-1 txt-caption text-muted-foreground">{unmapped.join(" · ")}</div>
        </div>
      )}

      <section className="space-y-3">
        <h2 className="txt-section">高危动作谁持有</h2>
        <p className="txt-caption text-muted-foreground">
          动钱、授权、改登录方式。<b>每一行至少要有一个人</b>，否则那个功能没人做得了；
          而多一个人就是一次越权。
        </p>
        <Table>
          <THead>
            <TR>
              <TH className="w-[22rem]">权限码</TH>
              {ROLES.map((r) => <TH key={r} className="text-center">{ROLE_LABEL[r]}</TH>)}
            </TR>
          </THead>
          <TBody>
            {CRITICAL.map((code) => {
              const holders = ROLES.filter((r) => roleHas(r, code));
              return (
                <TR key={code}>
                  <TD>
                    <code className="txt-caption">{code}</code>
                    {holders.length === 0 && (
                      <Badge tone="danger" className="ml-2">没人持有</Badge>
                    )}
                  </TD>
                  {ROLES.map((r) => (
                    <TD key={r} className="text-center">
                      {roleHas(r, code)
                        ? <span className="text-primary">●</span>
                        : <span className="text-muted-foreground/30">·</span>}
                    </TD>
                  ))}
                </TR>
              );
            })}
          </TBody>
        </Table>
      </section>

      {groups.map((g) => (
        <section key={g.key} className="space-y-2">
          <h2 className="txt-section">
            {g.label}
            <span className="ml-2 txt-caption text-muted-foreground">
              {ROLES.filter((r) => canModule(permsOf(r), g.key)).length} / {ROLES.length} 个角色可见
            </span>
          </h2>
          <Table>
            <THead>
              <TR>
                <TH className="w-[22rem]">权限码</TH>
                {ROLES.map((r) => <TH key={r} className="text-center">{ROLE_LABEL[r]}</TH>)}
              </TR>
            </THead>
            <TBody>
              {g.codes.map((code) => {
                const mapped = UI_PERM_MAP[code];
                return (
                  <TR key={code}>
                    <TD>
                      <code className="txt-caption">{code}</code>
                      {mapped === UNIMPLEMENTED && <Badge tone="muted" className="ml-2">后端未实现</Badge>}
                      {typeof mapped === "string" && mapped !== code && (
                        <span className="ml-2 txt-caption text-muted-foreground">→ {mapped}</span>
                      )}
                    </TD>
                    {ROLES.map((r) => (
                      <TD key={r} className="text-center">
                        {roleHas(r, code)
                          ? <span className="text-primary">●</span>
                          : <span className="text-muted-foreground/30">·</span>}
                      </TD>
                    ))}
                  </TR>
                );
              })}
            </TBody>
          </Table>
        </section>
      ))}

      <section className="space-y-2">
        <h2 className="txt-section">各角色持码数</h2>
        <div className="flex flex-wrap gap-2">
          {ROLES.map((r) => (
            <span key={r} className={cn(
              "rounded-chip border border-border px-3 py-1 txt-caption",
              BACKEND_ROLE_PERMS[r].includes("*") && "border-primary text-primary",
            )}>
              {ROLE_LABEL[r]} · {BACKEND_ROLE_PERMS[r].includes("*") ? "全量（通配）" : `${BACKEND_ROLE_PERMS[r].length} 码`}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}

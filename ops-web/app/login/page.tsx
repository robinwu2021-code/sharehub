"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, type Role, type Realm } from "@/lib/auth";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

/**
 * 登录页。
 *
 * **两个 tab 选账号池，不是选权限**（v4/06 §2.6：realm 只选账号池）。
 * 员工与合作伙伴是两批完全不同的人，分开比「单输入框后端两池都查」清楚：
 * 后者要多一条「同一邮箱两边都命中」的选择态分支，而那条分支几乎不会触发、
 * 却必须写、必须测；错误信息也只能含糊成「账号或密码错误」（不能说哪个池没有，否则可枚举）。
 *
 * **输入框只有一个 `identifier`**：手机号或邮箱，含 `@` 走邮箱。
 * 分两个字段的话，前端要先判断用户输的是什么，那个判断迟早和后端不一致。
 *
 * **不传 role** —— 角色由账号决定（D6a）。此前这里写死 `"ADMIN"`。
 */
const TABS = [
  { realm: "STAFF" as const, label: "员工登录", hint: "邮箱" },
  { realm: "AGENT" as const, label: "合作伙伴登录", hint: "手机号或邮箱" },
];

export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [realm, setRealm] = useState<Exclude<Realm, "">>("STAFF");
  const [identifier, setIdentifier] = useState("admin");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  const tab = TABS.find((x) => x.realm === realm)!;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      const r = await api.login(realm, identifier, password);
      login({
        realm,
        subjectNo: r.subjectNo,
        username: r.username,
        role: r.role as Role,
        token: r.token,
        // 判权唯一依据。后端一直在下发，而前端此前一个字节都没读过 —— 两套表漂了 29 处
        perms: r.perms ?? [],
        memberships: r.operators ?? [],
        currentOperatorNo: r.currentOperatorNo,
      });
      router.replace("/");
    } catch (e) {
      setErr((e as Error).message || t("common.failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-screen items-center justify-center bg-muted/30">
      <Card className="w-[360px]">
        <CardHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-field bg-primary text-primary-foreground text-sm font-medium">SH</div>
          <CardTitle>{t("common.appTitle")}</CardTitle>
          <p className="text-sm text-muted-foreground">{t("login.subtitle")}</p>
        </CardHeader>
        <CardContent>
          <div role="tablist" aria-label="账号池" className="mb-4 flex gap-1 rounded-field bg-muted p-1">
            {TABS.map((x) => (
              <button
                key={x.realm}
                role="tab"
                type="button"
                aria-selected={realm === x.realm}
                onClick={() => { setRealm(x.realm); setErr(""); }}
                className={cn(
                  "flex-1 rounded-control px-3 py-1.5 text-sm transition-colors",
                  realm === x.realm
                    ? "bg-surface text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {x.label}
              </button>
            ))}
          </div>
          <form className="space-y-3" onSubmit={submit}>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground" htmlFor="identifier">{tab.hint}</label>
              <Input
                id="identifier"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder={tab.hint}
                autoComplete="username"
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground" htmlFor="password">{t("login.password")}</label>
              <Input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("login.password")} autoComplete="current-password" />
            </div>
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 text-sm text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy}>{busy ? t("common.loading") : t("login.submit")}</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

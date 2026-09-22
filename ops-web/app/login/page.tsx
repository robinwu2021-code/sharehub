"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, type Role } from "@/lib/auth";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

// 生产登录：用户名 + 密码；角色由账号决定（后端根据 username 派发 role/perms），
// 前端不再让用户挑角色 —— 之前 mock 期为「MVP：选角色 + 用户名即可进入」，容易误选。
// mock 模式（NEXT_PUBLIC_USE_MOCK != 0）下 api.login 会走 mocks/dashboard.ts，那里的 role 用默认 ADMIN。
export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      // 后端会依据 username 决定 role/perms；此处 role 只作 mock 兜底与 AGENT 场景 agentNo 判断的存量参数
      const r = await api.login(username, password, "ADMIN");
      login({ username: r.username, role: r.role as Role, token: r.token, agentNo: r.agentNo });
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
          <div className="mb-1 flex size-9 items-center justify-center rounded-field bg-primary text-primary-foreground text-sm font-medium">PB</div>
          <CardTitle>{t("common.appTitle")}</CardTitle>
          <p className="text-sm text-muted-foreground">{t("login.subtitle")}</p>
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={submit}>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">{t("login.username")}</label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={t("login.username")} autoComplete="username" />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">{t("login.password")}</label>
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("login.password")} autoComplete="current-password" />
            </div>
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 text-sm text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy}>{busy ? t("common.loading") : t("login.submit")}</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

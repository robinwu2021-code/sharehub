"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, type Role } from "@/lib/auth";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const ROLES: Role[] = ["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER", "AGENT"];

// MVP 登录（mock）：选角色 + 用户名即可进入。接后端后换 auth-core（OTP/密码 + JWT）。
export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<Role>("ADMIN");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      // 换后端 token（mock 模式返回 mock token）；后端据 token 角色鉴权
      const r = await api.login(username, password, role, role === "AGENT" ? "AG001" : undefined);
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
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">{t("login.role")}</label>
              <Select className="w-full" value={role} onChange={(e) => setRole(e.target.value as Role)}>
                {ROLES.map((r) => <option key={r} value={r}>{t(`role.${r}`)}</option>)}
              </Select>
            </div>
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 text-sm text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy}>{busy ? t("common.loading") : t("login.submit")}</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

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
 * **员工用邮箱 + 口令；合作伙伴用手机号 + 验证码**（2026-09-24）。
 * 不是两种风格并存，是两边的凭据现实不同：运营端有口令闸，而代理端
 * **全仓没有凭据存储** —— `cred_credential` 还是 V4 里注释掉的设计。
 * 验证码登录本身就是完整的登录方式，不是等口令做好之前的临时方案。
 *
 * **不传 role** —— 角色由账号决定（D6a）。此前这里写死 `"ADMIN"`。
 */
const TABS = [
  { realm: "STAFF" as const, label: "员工登录", hint: "邮箱" },
  // 合作伙伴只能用**手机号**：验证码要发到手机上，而库里存的是 hash + 掩码 ——
  // phone_enc/email_enc 都是空的，掩码不可逆，服务端根本拿不到明文去发信。
  // 所以必须由本人输入手机号，服务端规范化后按 hash 反查（见 AgentIdentityPort）。
  { realm: "AGENT" as const, label: "合作伙伴登录", hint: "手机号" },
];

export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [realm, setRealm] = useState<Exclude<Realm, "">>("STAFF");
  const [identifier, setIdentifier] = useState("admin");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  const tab = TABS.find((x) => x.realm === realm)!;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      const r = await api.login(realm, identifier, password, realm === "AGENT" ? otp : undefined);
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

  /** 发送登录验证码。查无此号后端也回 ok —— 所以这里不能据响应判断号存不存在。 */
  async function sendOtp() {
    if (!identifier.trim()) { setErr("请先填写手机号"); return; }
    setSending(true); setErr("");
    try {
      const r = await api.sendLoginOtp(identifier.trim());
      setOtpSent(true);
      // dev/mock 下回显验证码，省去联调时翻日志；生产不回传，这里就是 undefined
      if (r.code) setOtp(r.code);
    } catch (e) {
      setErr((e as Error).message || t("common.failed"));
    } finally {
      setSending(false);
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
                onClick={() => {
                  setRealm(x.realm); setErr(""); setOtp(""); setOtpSent(false);
                  // 标识也要跟着换：员工池的联调默认值 "admin" 放在手机号栏里是纯误导
                  setIdentifier(x.realm === "STAFF" ? "admin" : "");
                }}
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
            {realm === "STAFF" ? (
              <div className="space-y-1">
                <label className="text-sm text-muted-foreground" htmlFor="password">{t("login.password")}</label>
                <Input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("login.password")} autoComplete="current-password" />
              </div>
            ) : (
              <div className="space-y-1">
                <label className="text-sm text-muted-foreground" htmlFor="otp">验证码</label>
                <div className="flex gap-2">
                  <Input id="otp" value={otp} onChange={(e) => setOtp(e.target.value)}
                         placeholder="6 位验证码" inputMode="numeric" autoComplete="one-time-code" />
                  <Button type="button" variant="outline" className="shrink-0" disabled={sending} onClick={sendOtp}>
                    {sending ? t("common.loading") : otpSent ? "重新发送" : "发送验证码"}
                  </Button>
                </div>
                {otpSent && (
                  <p className="text-xs text-muted-foreground">
                    验证码已发送。若手机号未注册，同样显示已发送 —— 这样这个入口才不能被用来试探谁是平台代理商。
                  </p>
                )}
              </div>
            )}
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 text-sm text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy}>{busy ? t("common.loading") : t("login.submit")}</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

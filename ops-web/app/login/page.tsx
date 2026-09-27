"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, type Role, type Realm } from "@/lib/auth";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { looksLikePhone } from "@/lib/login-realm";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * 登录页。
 *
 * **一个输入框，没有 tab**（2026-09-27 改）。此前是「员工登录 / 合作伙伴登录」两个 tab
 * 选账号池，理由写着「单输入框要后端两池都查，还要处理『同一邮箱两边都命中』」。
 *
 * <p>那条理由在本仓库**不成立**：两个池的标识**形状不重叠** ——
 * 员工用邮箱或员工号（`E1005` / `admin` / 带 `@` 的邮箱），合作伙伴只能用**手机号**。
 * 所以 realm 可以**纯按输入形状判**，不查库、不问后端、不存在「两边都命中」，
 * 也因此**不引入任何可枚举性**（判的是字符形状，不是「这个账号存不存在」）。
 *
 * <p>realm 仍然照旧显式传给后端 —— 前端只决定「该向用户要口令还是验证码」，
 * 不代替后端判账号归属。{@code dashboardHttp.login} 上那句「免得前端先判断这是哪种登录」
 * 担心的是前端与后端判得不一致；这里的形状判定只影响**问用户要什么**，
 * 判错的后果是「要错了凭据、登不进去」，而不是「进了错的池」。
 *
 * <p>⚠️ 这条形状规则依赖当前的标识约定：员工号带字母前缀（`E1005`）、邮箱带 `@`。
 * **哪天员工号变成纯数字，这里会把员工误判成合作伙伴** —— 那时要么改标识约定，
 * 要么把判定挪到后端（回一个「该用哪种凭据」的探测端点，但那个端点本身就是可枚举面）。
 * 界面上把判定结果明写出来（下面那行提示），让判错时用户看得见，而不是只收到一句登录失败。
 *
 * **员工用邮箱 + 口令；合作伙伴用手机号 + 验证码**（2026-09-24）。
 * 不是两种风格并存，是两边的凭据现实不同：运营端有口令闸，而代理端
 * **全仓没有凭据存储** —— `cred_credential` 还是 V4 里注释掉的设计。
 * 验证码登录本身就是完整的登录方式，不是等口令做好之前的临时方案。
 *
 * **不传 role** —— 角色由账号决定（D6a）。此前这里写死 `"ADMIN"`。
 */

export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [identifier, setIdentifier] = useState("admin");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  // realm 不再是状态 —— 它是输入的**函数**。存成状态就会出现「输入改了、realm 还是旧的」
  // 那一类不一致，而它只在切换的那一瞬出现，最难复现。
  const realm: Exclude<Realm, ""> = looksLikePhone(identifier) ? "AGENT" : "STAFF";

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
        // 一次性口令未改 → 外壳会挡住整个应用，只留改密表单（见 MustChangePasswordGate）
        mustChange: r.mustChange,
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
          <form className="space-y-3" onSubmit={submit}>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground" htmlFor="identifier">账号</label>
              <Input
                id="identifier"
                value={identifier}
                onChange={(e) => { setIdentifier(e.target.value); setErr(""); }}
                placeholder="邮箱 / 员工号 / 手机号"
                autoComplete="username"
              />
              {/*
                把推导结果明写出来。判错时（比如员工号哪天变成纯数字）用户看得见
                「这里要发验证码」，而不是只收到一句「登录失败」再去猜为什么。
              */}
              <p className="text-xs text-muted-foreground">
                {realm === "AGENT" ? "手机号 —— 用验证码登录" : "邮箱或员工号 —— 用口令登录"}
              </p>
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

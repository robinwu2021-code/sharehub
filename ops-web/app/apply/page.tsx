"use client";

// 商家自助入驻申请 —— **全站唯一面向陌生人的写入口**。
//
// ── 它与运营端其它页面的三点不同 ────────────────────────────────────────
// 1. **不在 lib/nav.ts 里**，也进不了菜单：访问者还没有账号，看不到任何导航。
//    路由白名单在 components/layout/app-shell.tsx 的 PUBLIC_PATHS。
// 2. **不能暴露任何可枚举信息**。最典型的是「这个手机号已经有主体了」——
//    那句话本身就是一个查号工具。手机号已有主体在服务端是合法的多主体申请，
//    这里一律按普通提交处理，差异只给审核台看。
// 3. **防刷不在前端**。OTP、单 IP 限流、同手机号至多一张在途都在服务端；
//    前端这一层只管别把错误信息说得太细。
import { useState } from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { notify } from "@/lib/notify";
import { cn } from "@/lib/utils";
import type { OperatorType } from "@/lib/types";

type Step = 1 | 2 | 3;

const STEPS: { no: Step; label: string }[] = [
  { no: 1, label: "验证手机号" },
  { no: 2, label: "填写主体信息" },
  { no: 3, label: "提交完成" },
];

export default function ApplyPage() {
  const [step, setStep] = useState<Step>(1);
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [email, setEmail] = useState("");
  const [operatorName, setOperatorName] = useState("");
  const [operatorType, setOperatorType] = useState<OperatorType>("AGENT");
  const [regionScope, setRegionScope] = useState("");
  const [payload, setPayload] = useState("");
  const [applyNo, setApplyNo] = useState("");

  const sendOtp = useMutation({
    mutationFn: () => api.sendApplyOtp(phone),
    onSuccess: (r) => {
      // devCode 只在 dev-mode 回传；生产走短信，这里拿不到
      notify.success(r.devCode ? `验证码已发送（联调码 ${r.devCode}）` : "验证码已发送");
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const submit = useMutation({
    mutationFn: () => api.selfServiceApply({
      phone, otp, email, operatorName, operatorType,
      regionScope: regionScope || undefined,
      payload: payload || undefined,
    }),
    onSuccess: (r) => { setApplyNo(r.applyNo); setStep(3); },
    onError: (e: Error) => notify.error(e.message),
  });

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-[520px]">
        <CardHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-field bg-primary text-primary-foreground text-sm font-medium">SH</div>
          <CardTitle>申请成为合作伙伴</CardTitle>
          <p className="txt-body text-muted-foreground">
            提交后由运营团队审核，结果会发送到你填写的手机号与邮箱。
          </p>
        </CardHeader>
        <CardContent className="space-y-5">
          <ol className="flex items-center gap-2" aria-label="申请步骤">
            {STEPS.map((s, i) => (
              <li key={s.no} className="flex flex-1 items-center gap-2">
                {/* 五档圆角之外一律不许（规范 §5）—— 圆点用 chip，它就是为这类小徽记定的 */}
                <span className={cn(
                  "flex size-6 shrink-0 items-center justify-center rounded-chip txt-caption",
                  step >= s.no ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground",
                )}>{s.no}</span>
                <span className={cn("txt-caption", step >= s.no ? "text-foreground" : "text-muted-foreground")}>
                  {s.label}
                </span>
                {i < STEPS.length - 1 && <span className="h-px flex-1 bg-border" />}
              </li>
            ))}
          </ol>

          {step === 1 && (
            <div className="space-y-3">
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="phone">手机号</label>
                <div className="flex gap-2">
                  <Input
                    id="phone" value={phone} onChange={(e) => setPhone(e.target.value)}
                    placeholder="可带国际区号，如 +971 50 123 4567" autoComplete="tel"
                  />
                  <Button
                    variant="outline" type="button"
                    disabled={!phone.trim() || sendOtp.isPending}
                    onClick={() => sendOtp.mutate()}
                  >获取验证码</Button>
                </div>
              </div>
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="otp">验证码</label>
                <Input id="otp" value={otp} onChange={(e) => setOtp(e.target.value)} placeholder="6 位数字" inputMode="numeric" />
              </div>
              <Button
                className="w-full"
                disabled={!phone.trim() || !otp.trim()}
                onClick={() => setStep(2)}
              >下一步</Button>
              {/*
                * 验证码不在这一步校验 —— 它和申请内容一起提交给服务端。
                * 分两次校验意味着码要么被消费掉（后面就用不了了），要么要多存一个"已验证"状态，
                * 而那个状态在前端是不可信的。
                */}
              <p className="txt-caption text-muted-foreground">
                验证码会随申请一起提交校验；填错了会在最后一步告诉你。
              </p>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-3">
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="email">邮箱</label>
                <Input
                  id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                  placeholder="用于接收审核结果与结算通知" autoComplete="email"
                />
              </div>
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="operatorName">主体名称</label>
                <Input
                  id="operatorName" value={operatorName} onChange={(e) => setOperatorName(e.target.value)}
                  placeholder="营业执照上的名称"
                />
              </div>
              <div className="space-y-1">
                <span className="txt-caption text-muted-foreground">主体类型</span>
                <div className="flex gap-2">
                  {([["AGENT", "代理商"], ["CITY_PARTNER", "城市合伙人"]] as const).map(([v, label]) => (
                    <button
                      key={v} type="button"
                      onClick={() => setOperatorType(v)}
                      aria-pressed={operatorType === v}
                      className={cn(
                        "flex-1 rounded-control border px-3 py-2 txt-body transition-colors",
                        operatorType === v
                          ? "border-primary bg-primary/5 text-primary"
                          : "border-border text-muted-foreground hover:text-foreground",
                      )}
                    >{label}</button>
                  ))}
                </div>
              </div>
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="regionScope">意向区域（选填）</label>
                <Input id="regionScope" value={regionScope} onChange={(e) => setRegionScope(e.target.value)} placeholder="如：迪拜 · 商业湾" />
              </div>
              <div className="space-y-1">
                <label className="txt-caption text-muted-foreground" htmlFor="payload">资质材料说明（选填）</label>
                <Input id="payload" value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="营业执照 / 法人身份证等，审核时会联系你补交" />
              </div>
              <div className="flex gap-2">
                <Button variant="outline" className="flex-1" onClick={() => setStep(1)}>上一步</Button>
                <Button
                  className="flex-1"
                  disabled={!email.trim() || !operatorName.trim() || submit.isPending}
                  onClick={() => submit.mutate()}
                >{submit.isPending ? "提交中…" : "提交申请"}</Button>
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="space-y-4">
              <div className="rounded-card border border-success/40 bg-success/5 p-4">
                <div className="txt-body font-medium">申请已提交</div>
                <div className="mt-1 txt-caption text-muted-foreground">
                  申请单号 <span className="font-mono text-foreground">{applyNo}</span>
                  ——请记下它，查询进度时用得上。
                </div>
              </div>
              <p className="txt-body text-muted-foreground">
                运营团队会尽快审核。通过后你会收到开通通知；
                如果材料需要补正，驳回原因会一并告诉你，可以直接修改后重新提交。
              </p>
              <Link href="/apply/status" className="block">
                <Button variant="outline" className="w-full">查询审核进度</Button>
              </Link>
            </div>
          )}

          <div className="border-t border-border pt-4 txt-caption text-muted-foreground">
            已经提交过？<Link href="/apply/status" className="text-primary hover:underline">查询进度</Link>
            <span className="mx-2">·</span>
            已有账号？<Link href="/login" className="text-primary hover:underline">去登录</Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

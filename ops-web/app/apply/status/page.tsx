"use client";

// 申请进度查询 —— 公开页（PUBLIC_PATHS 覆盖 /apply 及其子路径）。
//
// 这一页存在的理由只有一个：**让被驳回的人知道自己错在哪**。
// 不给驳回原因的话，申请人只能反复猜着重提，而每一次重提都要运营再看一遍。
//
// 同样不能暴露可枚举信息：查不到时统一说「没有查到」，不区分
// 「这个号没申请过」与「验证码错了」—— 否则它就是个查号工具。
import { useState } from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { ApplyStatus, MyApplyView } from "@/lib/types";

/**
 * 申请人视角的状态文案 —— 与运营端**有意不同**：
 * 运营看的是「待受理 / 审核中」这类工作流状态，申请人要的是「我该做什么」。
 */
const MY_STATUS: StatusMap<ApplyStatus> = {
  DRAFT: { label: "草稿", tone: "muted" },
  SUBMITTED: { label: "已提交，等待审核", tone: "warning" },
  REVIEWING: { label: "审核中", tone: "info" },
  APPROVED: { label: "已通过", tone: "success" },
  REJECTED: { label: "需要补正", tone: "danger" },
};

export default function ApplyStatusPage() {
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [result, setResult] = useState<MyApplyView | null>(null);

  const sendOtp = useMutation({
    mutationFn: () => api.sendApplyOtp(phone),
    onSuccess: (r) => notify.success(r.devCode ? `验证码已发送（联调码 ${r.devCode}）` : "验证码已发送"),
    onError: (e: Error) => notify.error(e.message),
  });

  const query = useMutation({
    mutationFn: () => api.myApply(phone, otp),
    onSuccess: (r) => setResult(r),
    // 不区分「没申请过」与「码错了」—— 区分开就是一个查号工具
    onError: () => notify.error("没有查到申请记录，请确认手机号与验证码"),
  });

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-[520px]">
        <CardHeader>
          <CardTitle>查询审核进度</CardTitle>
          <p className="txt-body text-muted-foreground">用提交申请时的手机号查询。</p>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="space-y-3">
            <div className="space-y-1">
              <label className="txt-caption text-muted-foreground" htmlFor="phone">手机号</label>
              <div className="flex gap-2">
                <Input
                  id="phone" value={phone} onChange={(e) => setPhone(e.target.value)}
                  placeholder="提交申请时填的号码" autoComplete="tel"
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
              disabled={!phone.trim() || !otp.trim() || query.isPending}
              onClick={() => query.mutate()}
            >{query.isPending ? "查询中…" : "查询"}</Button>
          </div>

          {result && (
            <div className="space-y-3 border-t border-border pt-4">
              <div className="flex items-center justify-between">
                <span className="font-mono txt-caption text-muted-foreground">{result.applyNo}</span>
                <StatusBadge map={MY_STATUS} value={result.status} />
              </div>
              <div className="space-y-1 txt-body">
                <div>{result.operatorName}</div>
                <div className="txt-caption text-muted-foreground">
                  <span className="font-mono">{result.phoneMask}</span>
                  <span className="mx-2">·</span>
                  <span className="font-mono">{result.emailMask}</span>
                </div>
                {result.submittedAt && (
                  <div className="txt-caption text-muted-foreground">提交于 {fmtTime(result.submittedAt)}</div>
                )}
              </div>

              {/* 被驳回时，原因是这一页存在的全部意义 —— 原样显示，不做任何改写 */}
              {result.status === "REJECTED" && result.rejectReason && (
                <div className="rounded-card border border-destructive/40 bg-destructive/5 p-3">
                  <div className="txt-body font-medium text-destructive">需要补正</div>
                  <div className="mt-1 txt-body">{result.rejectReason}</div>
                  <Link href="/apply" className="mt-3 block">
                    <Button variant="outline" className="w-full">修改后重新提交</Button>
                  </Link>
                </div>
              )}

              {result.status === "APPROVED" && (
                <div className="rounded-card border border-success/40 bg-success/5 p-3 txt-body">
                  申请已通过，可以用这个手机号登录了。
                  <Link href="/login" className="ml-1 text-primary hover:underline">去登录</Link>
                </div>
              )}
            </div>
          )}

          <div className="border-t border-border pt-4 txt-caption text-muted-foreground">
            还没申请？<Link href="/apply" className="text-primary hover:underline">提交入驻申请</Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

"use client";

// 自助注册关闭时的落地页（一期只走运营代建，V155 `agent.apply.self_service_enabled=0`）。
//
// 只在**发码**返回 404 时出现：发码是公开页的第一步，关闭时后端对它一律 404。
// 不在「查进度」的 404 上出现 —— 那里的 404 也可能是「没有这张申请单」，两者不能混。
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export function SelfServiceClosed() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-[520px]">
        <CardHeader>
          <CardTitle>暂未开放自助注册</CardTitle>
          <p className="txt-body text-muted-foreground">
            商家账号目前由运营团队开通。请联系与你对接的运营人员，开通后你会收到一封邀请邮件。
          </p>
        </CardHeader>
        <CardContent>
          <Button asChild variant="outline" className="w-full">
            <Link href="/login">已有账号，去登录</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

/** 发码失败时判断是不是「入口已关闭」。 */
export function isSelfServiceClosed(e: unknown): boolean {
  return typeof e === "object" && e !== null && (e as { code?: unknown }).code === 404;
}

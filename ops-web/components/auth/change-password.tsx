"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import { signOut } from "@/lib/api/session";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * 改密表单（P3b·B2）。两处复用：
 *
 * 1. {@link MustChangePasswordGate} —— 一次性口令还没改，**挡住整个应用**；
 * 2. 顶栏「修改密码」—— 本人自愿改。
 *
 * 口令长度这道闸前后端各有一份（服务端那份才算数），
 * 前端这一份只是省掉一次往返 —— 不是替代。
 */
export function ChangePasswordForm({ onDone, requireNew }: { onDone?: () => void; requireNew?: boolean }) {
  const passwordChanged = useAuth((s) => s.passwordChanged);
  const [oldPwd, setOldPwd] = useState("");
  const [newPwd, setNewPwd] = useState("");
  const [again, setAgain] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    // 两次不一致在前端拦住就好 —— 这个信息服务端没有（它只收一个 newPassword）
    if (newPwd !== again) { setErr("两次输入的新密码不一致"); return; }
    if (newPwd.trim().length < 8) { setErr("新密码至少 8 位"); return; }
    setBusy(true); setErr("");
    try {
      await api.changePassword(oldPwd, newPwd);
      passwordChanged();
      onDone?.();
    } catch (e) {
      setErr((e as Error).message || "改密失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="space-y-3" onSubmit={submit}>
      <div className="space-y-1">
        <label className="txt-body text-muted-foreground" htmlFor="cp-old">
          {requireNew ? "管理员给的一次性密码" : "原密码"}
        </label>
        <Input id="cp-old" type="password" autoComplete="current-password"
               value={oldPwd} onChange={(e) => setOldPwd(e.target.value)} />
      </div>
      <div className="space-y-1">
        <label className="txt-body text-muted-foreground" htmlFor="cp-new">新密码（至少 8 位）</label>
        <Input id="cp-new" type="password" autoComplete="new-password"
               value={newPwd} onChange={(e) => setNewPwd(e.target.value)} />
      </div>
      <div className="space-y-1">
        <label className="txt-body text-muted-foreground" htmlFor="cp-again">再输一次</label>
        <Input id="cp-again" type="password" autoComplete="new-password"
               value={again} onChange={(e) => setAgain(e.target.value)} />
      </div>
      {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 txt-body text-destructive">{err}</div>}
      <Button className="w-full" type="submit" disabled={busy}>{busy ? "提交中…" : "确认修改"}</Button>
    </form>
  );
}

/**
 * 一次性口令未改前挡住整个应用。
 *
 * <b>为什么挡在外壳而不是登录页</b>：一次性口令是管理员口头或邮件转述给本人的，
 * 转述路径上的人都知道它。若只在登录页提示一次，刷新一下就带着它进了应用 ——
 * 那个口令会一直有效，而「他早就该改了」这件事没有任何症状。
 *
 * 留一个「退出」：忘了一次性口令的人不该被困在这一屏里，只能清浏览器数据。
 */
export function MustChangePasswordGate() {
  return (
    <div className="flex h-screen items-center justify-center bg-muted/30">
      <Card className="w-[380px]">
        <CardHeader>
          <CardTitle>请先设置新密码</CardTitle>
          <p className="txt-body text-muted-foreground">
            当前用的是管理员发的一次性密码，改完才能进入系统。
          </p>
        </CardHeader>
        <CardContent className="space-y-3">
          <ChangePasswordForm requireNew />
          <Button variant="ghost" className="w-full" onClick={() => signOut().then(() => location.assign("/login"))}>
            退出登录
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

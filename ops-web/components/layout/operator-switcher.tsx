"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ChevronsUpDown, Check } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import {
  DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem,
} from "@/components/ui/dropdown-menu";

/**
 * 运营主体切换器（ADR-030 / 必要功能清单 ⑤）。
 *
 * <h3>为什么到现在才有</h3>
 * ADR-030 定的「一个人对多行 `agt_account`」在库里和 store 里都备好了很久，
 * 但**后端一直没有端点**把「我属于哪几个主体」告诉前端，顶栏只能把
 * `currentOperatorNo` 当一行字显示。一个人给两家运营商干活时，登录后无从选择。
 *
 * <h3>用 DropdownMenu 而不是自绘</h3>
 * 方向键 / typeahead / Esc / 关闭后焦点归还，自绘必漏，且漏了在纯鼠标测试里看不出来
 * （`components/ui/dropdown-menu.tsx` 的类注释原话）。
 *
 * <h3>只有一个主体时整个不渲染</h3>
 * 永远只有一个选项的下拉框是纯噪音，还会让人以为自己漏配了什么。
 */
export function OperatorSwitcher() {
  const { realm, memberships, currentOperatorNo, switchOperator } = useAuth();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);

  if (realm !== "AGENT" || memberships.length < 2) return null;

  const current = memberships.find((m) => m.operatorNo === currentOperatorNo);

  async function pick(operatorNo: string) {
    if (operatorNo === currentOperatorNo || busy) return;
    setBusy(true);
    try {
      const r = await api.switchOperator(operatorNo);
      switchOperator(operatorNo, { token: r.token, perms: r.perms ?? [], username: r.username });
      /*
       * **切完必须清缓存**：react-query 里缓着的是上一个主体的数据，
       * 不清的话切换后会先闪一屏别人家的数字再刷新 —— 那一瞬足够让人误判。
       */
      qc.clear();
      notify.success(`已切换到 ${memberships.find((m) => m.operatorNo === operatorNo)?.name ?? operatorNo}`);
    } catch (e) {
      notify.error((e as Error).message || "切换失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        disabled={busy}
        className="flex items-center gap-1.5 rounded-control px-2 py-1 txt-body text-muted-foreground outline-none hover:bg-muted hover:text-foreground"
      >
        <span className="text-foreground">{current?.name ?? currentOperatorNo ?? "-"}</span>
        <ChevronsUpDown className="size-3.5 opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="min-w-56">
        {memberships.map((m) => (
          <DropdownMenuItem key={m.operatorNo} onSelect={() => pick(m.operatorNo)}>
            <span className="flex w-full items-center justify-between gap-3">
              <span>
                <span className="block">{m.name}</span>
                {/* 属主与被授权成员看到的东西不一样，这里点明，免得以为是权限出错 */}
                <span className="block text-xs text-muted-foreground">
                  {m.operatorNo}{m.isOwner ? " · 属主" : ""}
                </span>
              </span>
              {m.operatorNo === currentOperatorNo && <Check className="size-4 shrink-0" />}
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

"use client";

// 状态动作条（方案 C2 / 规则 R1、R4）：详情头与列表行上「这个对象现在能做什么」。
//
// - 状态只能由这里的动作改，编辑表单不放状态下拉（R1）。
// - 合法与否由调用方按迁移表算好传 `when`；**不合法的动作不渲染**（它不是「暂时不行」，是「这个状态下没有这回事」）。
// - **缺权限的动作渲染为禁用 + 提示缺哪个码**，不静默隐藏（规范 §13）：
//   藏起来的话用户以为系统没这功能，去找开发；禁用 + 说明的话他知道去找管理员。
// - 有副作用的都要确认：可逆的走普通确认，不可逆的传 `requireText`（R4）。
import { Button } from "@/components/ui/button";
import { RowActions } from "@/components/ui/dropdown-menu";
import { useConfirm, type ConfirmOptions } from "@/components/ui/confirm-dialog";
import { useCan } from "@/lib/hooks/use-can";

export interface ActionSpec {
  key: string;
  label: string;
  /** 调用该动作要求的权限码。 */
  perm?: string;
  /** 当前状态下是否合法（按迁移表算）。false = 不渲染。默认 true。 */
  when?: boolean;
  /** 业务上暂不可用（门禁未过等）：渲染禁用并在提示里写原因。 */
  blockedReason?: string | null;
  /** 主动作：渲染成按钮；其余收进「更多」菜单。 */
  primary?: boolean;
  danger?: boolean;
  /** 需要确认；不传则直接执行（只适合打开抽屉这类无副作用的动作）。 */
  confirm?: ConfirmOptions;
  onRun: () => void | Promise<unknown>;
}

export function StateActions({ actions, size = "sm" }: { actions: ActionSpec[]; size?: "sm" | "default" }) {
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const visible = actions.filter((a) => a.when !== false);
  if (visible.length === 0) return null;

  const reason = (a: ActionSpec) =>
    a.perm && !allow(a.perm) ? `无权限（需要 ${a.perm}）` : a.blockedReason || undefined;
  const run = async (a: ActionSpec) => {
    if (a.confirm && !(await confirm({ danger: a.danger, ...a.confirm }))) return;
    await a.onRun();
  };

  const primary = visible.filter((a) => a.primary);
  const rest = visible.filter((a) => !a.primary);
  return (
    <>
      {primary.map((a) => {
        const r = reason(a);
        return (
          <Button
            key={a.key}
            size={size}
            variant={a.danger ? "destructive" : "default"}
            disabled={!!r}
            title={r}
            onClick={() => run(a)}
          >
            {a.label}
          </Button>
        );
      })}
      <RowActions
        actions={rest.map((a) => {
          const r = reason(a);
          return { label: a.label, danger: a.danger, disabled: !!r, hint: r, onSelect: () => { void run(a); } };
        })}
      />
      {dialog}
    </>
  );
}

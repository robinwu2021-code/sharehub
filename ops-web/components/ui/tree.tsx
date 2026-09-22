"use client";

// 树形列表（原语）：给「层级本身就是信息」的列表用 —— 组织架构、权限码目录。
//
// 存在的唯一理由：扁平表格加一列「上级」时，读者得一行一行用眼睛把父子关系拼回来；
// 三层以上基本拼不出来。缩进 + 展开 + 竖线在这里定死一份，调用方只给数据。
//
// 两种形态共用同一份缩进/展开逻辑：
//   - 只读树（组织架构）：extra 放成员数、负责人、「编辑」按钮
//   - 勾选树（角色功能权限）：checkable=true，父节点三态；**值只认叶子 key**，
//     父节点不入选中集合 —— 否则「父勾了但后来新增了子」会留下语义不明的半真状态。
import * as React from "react";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

export interface TreeNode {
  key: string;
  label: React.ReactNode;
  /** 行右侧：统计数字、徽标、操作按钮 */
  extra?: React.ReactNode;
  children?: TreeNode[];
}

/** 子孙叶子的 key。父节点的勾选态、以及点父节点时要改哪些值，都由它算出来。 */
export function leafKeysOf(n: TreeNode): string[] {
  if (!n.children?.length) return [n.key];
  return n.children.flatMap(leafKeysOf);
}

/** 三态 checkbox。项目无 checkbox 原语（DataTable 里那个未导出），这里就地实现，保持扁平无描边风格。 */
function TriCheckbox({
  state, onToggle, label, disabled,
}: {
  state: "on" | "off" | "partial";
  onToggle: () => void;
  label: string;
  disabled?: boolean;
}) {
  const ref = React.useRef<HTMLInputElement>(null);
  // indeterminate 只能用 DOM 属性设，没有对应的 React prop
  React.useEffect(() => { if (ref.current) ref.current.indeterminate = state === "partial"; }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      aria-label={label}
      disabled={disabled}
      checked={state === "on"}
      onChange={onToggle}
      className="size-4 shrink-0 cursor-pointer accent-[var(--primary)] disabled:cursor-not-allowed disabled:opacity-50"
    />
  );
}

function Row({
  node, depth, checkable, checked, disabled, onCheck, collapseFrom, open, setOpen,
}: {
  node: TreeNode;
  depth: number;
  checkable?: boolean;
  checked: Set<string>;
  disabled?: boolean;
  onCheck?: (leaves: string[], next: boolean) => void;
  collapseFrom?: number;
  open: Record<string, boolean>;
  setOpen: React.Dispatch<React.SetStateAction<Record<string, boolean>>>;
}) {
  const kids = node.children ?? [];
  const hasKids = kids.length > 0;
  // 未被用户手动切过的节点按 collapseFrom 决定初始展开：undefined = 全展开
  const expanded = open[node.key] ?? depth < (collapseFrom ?? Number.MAX_SAFE_INTEGER);
  const leaves = React.useMemo(() => leafKeysOf(node), [node]);
  const on = leaves.filter((k) => checked.has(k)).length;
  const state = on === 0 ? "off" : on === leaves.length ? "on" : "partial";

  return (
    <li>
      <div
        className="flex items-center gap-2 rounded-md py-1.5 pe-2 hover:bg-accent/60"
        style={{ paddingInlineStart: `${depth * 20}px` }}
      >
        {hasKids ? (
          <button
            type="button"
            aria-expanded={expanded}
            aria-label={expanded ? "收起" : "展开"}
            className="rounded p-0.5 text-muted-foreground hover:bg-accent"
            onClick={() => setOpen((o) => ({ ...o, [node.key]: !expanded }))}
          >
            <ChevronRight className={cn("size-4 transition-transform", expanded && "rotate-90")} />
          </button>
        ) : (
          <span className="size-5 shrink-0" />
        )}
        {checkable && (
          <TriCheckbox
            state={state}
            disabled={disabled}
            label={typeof node.label === "string" ? node.label : node.key}
            onToggle={() => onCheck?.(leaves, state !== "on")}
          />
        )}
        <div className="min-w-0 flex-1 text-sm">{node.label}</div>
        {node.extra != null && <div className="flex shrink-0 items-center gap-1.5">{node.extra}</div>}
      </div>
      {hasKids && expanded && (
        <ul>
          {kids.map((c) => (
            <Row
              key={c.key} node={c} depth={depth + 1} checkable={checkable} checked={checked}
              disabled={disabled} onCheck={onCheck} collapseFrom={collapseFrom} open={open} setOpen={setOpen}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

export function Tree({
  nodes, empty, checkable, checkedKeys, onCheckedChange, disabled, collapseFrom, loading, loadingText = "加载中…",
}: {
  nodes: TreeNode[];
  /** 空树文案（与 DataTable 的 empty 同义：要写清「为什么空」）*/
  empty: string;
  checkable?: boolean;
  /** 已勾选的**叶子** key */
  checkedKeys?: string[];
  /** 回调给完整的叶子 key 列表（受控，勾选态由调用方持有） */
  onCheckedChange?: (keys: string[]) => void;
  disabled?: boolean;
  /** 深度 >= 该值的节点默认收起；不传 = 全展开。用户手动切过的节点不受影响。 */
  collapseFrom?: number;
  loading?: boolean;
  loadingText?: string;
}) {
  const [open, setOpen] = React.useState<Record<string, boolean>>({});
  const checked = React.useMemo(() => new Set(checkedKeys ?? []), [checkedKeys]);

  const onCheck = (leaves: string[], next: boolean) => {
    const s = new Set(checked);
    for (const k of leaves) { if (next) s.add(k); else s.delete(k); }
    onCheckedChange?.([...s]);
  };

  if (loading) return <span className="text-muted-foreground">{loadingText}</span>;
  if (!nodes.length) return <span className="text-muted-foreground">{empty}</span>;
  return (
    <ul role="tree">
      {nodes.map((n) => (
        <Row
          key={n.key} node={n} depth={0} checkable={checkable} checked={checked}
          disabled={disabled} onCheck={onCheck} collapseFrom={collapseFrom} open={open} setOpen={setOpen}
        />
      ))}
    </ul>
  );
}

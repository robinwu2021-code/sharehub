"use client";

// G1 软删除的页面侧统一件（TDD §10.1）。
//
// 归档要铺到 15 个主数据页。**不把这几件收敛掉，15 个页面就是 15 种归档写法**——
// 有的写"删除"有的写"归档"、已归档行有的灰有的不灰、确认弹窗文案各说各话。
// 放在 components/ 根而不是 components/ui/：ui/ 是 B0 定稿的通用原语，这里是业务约定层。
import * as React from "react";
import { Archive, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";

/** 归档动作的统一文案（按钮叫「归档」不叫「删除」——叫删除会让人以为数据没了）。 */
export const ARCHIVE_LABEL = "归档";
export const UNARCHIVE_LABEL = "恢复";

/**
 * Toolbar 筛选槽里的「显示已归档」开关。
 * 刻意做成带边框的小胶囊而非裸 checkbox：它和旁边的 Select 同处一行，视觉重量要对齐。
 */
export function ShowArchivedToggle({
  checked, onChange, label = "显示已归档",
}: { checked: boolean; onChange: (v: boolean) => void; label?: string }) {
  return (
    <label className="inline-flex h-9 cursor-pointer select-none items-center gap-2 rounded-[var(--radius)] bg-secondary px-3 text-sm">
      <input
        type="checkbox"
        className="size-4 cursor-pointer accent-[var(--primary)]"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}

/** 已归档行整行弱化。传给 `DataTable` 的 `rowClassName`。 */
export const archivedRowClass = (row: { archivedAt?: string | null }) =>
  row.archivedAt ? "opacity-60" : undefined;

/** 尾列的归档时间（未归档显示 `-`，不留空白让人以为是渲染坏了）。 */
export function ArchivedAt({ at }: { at?: string | null }) {
  return <span className="text-muted-foreground">{at ? fmtTime(at) : "-"}</span>;
}

/**
 * 操作列的归档 / 恢复按钮。
 *
 * **已归档行只出「恢复」，其余动作按钮一律不出**——这是本组件存在的主要理由：
 * 让"已归档行还能编辑/下发指令"这类错误在调用点就不可能写出来（`actions` 只在未归档时渲染）。
 */
export function ArchiveActions({
  archived, onArchive, onUnarchive, canWrite = true, canArchive = true, archiveHint, actions,
}: {
  archived: boolean;
  onArchive: () => void;
  onUnarchive: () => void;
  /** 无写权限时不出动作按钮（页面另有「仅可查看」提示，不做静默隐藏） */
  canWrite?: boolean;
  /**
   * 该行是否**允许归档**（区别于 canWrite 的"整体有无写权限"）。
   * 用于内置角色、系统预置数据这类"有写权限但这一行不能归档"的场景——
   * 否则页面只能绕过本组件自己拼一套按钮，样式就此发散。
   */
  canArchive?: boolean;
  /** canArchive=false 时的悬浮说明（如「内置角色不可归档」） */
  archiveHint?: string;
  /** 未归档时才显示的其它动作（编辑、指令…） */
  actions?: React.ReactNode;
}) {
  if (!canWrite) return <span className="text-muted-foreground">-</span>;
  if (archived) {
    return (
      <Button size="sm" variant="outline" onClick={onUnarchive}>
        <RotateCcw className="size-4" /> {UNARCHIVE_LABEL}
      </Button>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      {actions}
      <Button
        disabled={!canArchive}
        title={canArchive ? undefined : archiveHint} size="sm" variant="outline" onClick={onArchive}>
        <Archive className="size-4" /> {ARCHIVE_LABEL}
      </Button>
    </div>
  );
}

/**
 * 归档二次确认的统一配置，直接喂 `useConfirm().confirm(...)`。
 *
 * @param entity 实体名，如「机柜」「站点」
 * @param name   这一条的可读标识（编号或名称），进标题让人确认删对了行
 * @param requireText 主数据类（机柜/站点/场地方/代理商/角色）传编号，要求手输确认
 */
export const archiveConfirm = (entity: string, name: string, requireText?: string) => ({
  title: `归档${entity} ${name}`,
  desc: requireText
    ? `归档后该${entity}不再出现在默认列表，历史数据与关联记录全部保留，可随时在「显示已归档」中恢复。这是主数据，请输入编号确认。`
    : `归档后该${entity}不再出现在默认列表，历史数据全部保留，可随时在「显示已归档」中恢复。`,
  danger: true,
  confirmText: ARCHIVE_LABEL,
  requireText,
});

/** 恢复的二次确认（低危，不要求输入文本）。 */
export const unarchiveConfirm = (entity: string, name: string) => ({
  title: `恢复${entity} ${name}`,
  desc: `恢复后该${entity}重新出现在默认列表，可正常编辑与使用。`,
  confirmText: UNARCHIVE_LABEL,
});

// 权限降级提示（业务件）。放 components/ 根而非 components/ui/：同 archive.tsx，
// ui/ 是通用原语，这里是「运营端怎么表达权限不足」的业务约定层。
//
// 约定（TDD：不静默隐藏操作，要说清缺什么）：
//   仅可查看：当前角色无<what>权限（<perm>）[，<note>]
// 原先 30 处各写各的 —— 有的写「不能新增、编辑或归档」，有的写「既无 A 也无 B」，
// 有的干脆只写权限码。句式在此统一：主句恒定，差异一律落到 note。
import { Notice } from "@/components/ui/notice";

export function ReadOnlyNotice({
  what, perm, note, className,
}: {
  /** 缺的是什么能力，填名词短语，不带「权限」二字：如「预约取消」「结算单生成/确认」 */
  what: string;
  /** 权限码；多码传数组，展示为 `a / b`（缺任一即降级时，把码都列出来） */
  perm: string | string[];
  /** 附加说明：降级后具体不能做什么。如「不能新增、编辑或归档」 */
  note?: string;
  className?: string;
}) {
  const codes = Array.isArray(perm) ? perm.join(" / ") : perm;
  return (
    <Notice className={className}>
      仅可查看：当前角色无{what}权限（{codes}）{note ? `，${note}` : ""}
    </Notice>
  );
}

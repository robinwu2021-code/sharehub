/** 产品分级配置（对齐 `docs/requirements/功能清单-分级矩阵.md`，2026-09-23 起四级）。
 *
 * L0 最小闭环   — 缺了今天业务就做不了：一单真实的钱、一台真实的设备、一个真实的加盟商跑完整
 * L1 首批站点运营 — 开站后一个月内必然撞上：投诉与欺诈、押金欠费、月底对账、协作留痕、App 强更
 * L2 规模化      — 多站点 / 多加盟商 / 多人才出现：资产流转、SLA、深度分析、会员钱包、账务分录；第二种设备
 * L3 增长与生态   — 增长杠杆与外部连接：营销裂变广告、大屏与自定义分析、多国家市场、OpenAPI、充电桩、小程序
 *
 * 取代此前的三值 Phase 1/2/3（PDF 路线图 T1-T9）：旧「阶段 1」内部分不出
 * 「开不了张」与「有了更好」，L0/L1 正是为此拆开的。映射：旧 1 → L0 或 L1、旧 2 → L2、旧 3 → L3，
 * 逐叶的新级由分级矩阵 §四 给出（28 个叶子换了级，见矩阵 §六 裁决表）。
 *
 * ⚠️ 分级 ≠ 门禁。本文件只回答「该不该开放」；「能不能开放」由 NavLeaf.ready 逐叶决定
 * （2026-07-30 拆分，见 nav.ts）。ready 只放宽本级限制，不放宽 perm 与 soon。
 *
 * 切换：.env.local 改 NEXT_PUBLIC_CURRENT_PHASE=1/2/3 后重启 dev server（构建期注入，非运行时热切）。
 */
export type Phase = 0 | 1 | 2 | 3;

/** 当前交付到第几级；缺省 L0（尚未交付 L1）。 */
export const CURRENT_PHASE: Phase =
  (Number(process.env.NEXT_PUBLIC_CURRENT_PHASE ?? "0") as Phase) || 0;

/** 徽章用短标（L0 永不显示——它从不被锁）。 */
export const PHASE_LABEL: Record<Phase, string> = {
  0: "L0",
  1: "L1",
  2: "L2",
  3: "L3",
};

/** 描述用全称（锁屏文案）。 */
export const PHASE_NAME: Record<Phase, string> = {
  0: "L0 最小闭环",
  1: "L1 首批站点运营",
  2: "L2 规模化",
  3: "L3 增长与生态",
};

/** 判断某功能是否被当前分级屏蔽（级 > CURRENT_PHASE）。undefined 与 0 同义 = L0，永不屏蔽。 */
export function isPhaseLocked(phase: Phase | undefined): boolean {
  return phase !== undefined && phase > CURRENT_PHASE;
}

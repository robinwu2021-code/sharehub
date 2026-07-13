/** 产品分期配置（对齐 充電寶專案_V4 路线图）。
 *
 * Phase 1 (T1-T3) MVP  — 扫码租借→计费→支付→商户结算 闭环
 * Phase 2 (T4-T6) 规模化 — 100X 商户运营能力
 * Phase 3 (T7-T9) 生态   — 平台化、营销生态、资本叙事
 *
 * 切换：.env.local 改 NEXT_PUBLIC_CURRENT_PHASE=2 后重启 dev server。
 */
export type Phase = 1 | 2 | 3;

export const CURRENT_PHASE: Phase =
  (Number(process.env.NEXT_PUBLIC_CURRENT_PHASE || "1") as Phase) || 1;

export const PHASE_LABEL: Record<Phase, string> = {
  1: "P1 MVP",
  2: "P2",
  3: "P3",
};

/** 判断某功能是否被当前阶段屏蔽（phase > CURRENT_PHASE）。 */
export function isPhaseLocked(phase: Phase | undefined): boolean {
  return !!phase && phase > CURRENT_PHASE;
}

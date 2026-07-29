// 覆盖范围：跨域基础类型——响应 envelope、分页结果、通用查询参数。
// 新增判断：只有「所有域都可能用到」的才放这里，业务实体一律进对应域文件。

// 契约对齐 neargo-common-core（TDD-接入层分端与common复用 envelope 方案①）。
export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page?: number;
  size?: number;
}

export interface PageQuery {
  page?: number;
  size?: number;
  keyword?: string;
  [k: string]: unknown;
}

/**
 * 审批留痕三件套（台账 T6）。提现审核与退款审批原本各写一份同名字段。
 * ⚠️ 后端阶段：这三个字段应落到**统一的审批流水表**，而不是在每张业务表上各加三列。
 */
export interface AuditTrail {
  auditorName: string | null; // 审批人
  auditedAt: string | null;   // 审批时间
  rejectReason: string | null; // 驳回原因（通过时为 null）
}

/**
 * G1 软删除（TDD §10.1「B6 三项横向能力的统一模式」）：可归档主数据统一带归档时间。
 *
 * 为什么是 `archivedAt` 而不是 `deleted: boolean`：**归档时间本身就是审计信息**，
 * 布尔位丢掉了「什么时候没的」，出问题时无从回溯。null = 在用，非空 = 已归档。
 * 全平台**不做物理删除**，契约里禁止出现 `deleteXxx`。
 */
export interface Archivable {
  archivedAt: string | null;
}

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

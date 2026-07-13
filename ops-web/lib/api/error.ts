// 统一 API 错误类型（mock 与真实后端同抛此类）。code 对齐后端 ErrorCode（0=成功）。
export class ApiError extends Error {
  code: number;
  constructor(code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

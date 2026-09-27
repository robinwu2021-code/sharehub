// 422 校验失败：带逐条错误（21/06 §二：errors[{path, error}]），页面据此把错误挂到对应字段。
import { ApiError } from "@/lib/api/error";
import type { FieldError } from "./types";

export class FlowValidationError extends ApiError {
  errors: FieldError[];
  constructor(message: string, errors: FieldError[]) {
    super(422, message);
    this.name = "FlowValidationError";
    this.errors = errors;
  }
}

export const isValidationError = (e: unknown): e is FlowValidationError => e instanceof FlowValidationError;

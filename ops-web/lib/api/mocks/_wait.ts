// mock 公共延迟工具：所有 mock 切片共用，保持各域延迟口径一致（列表 200ms / 保存 350ms / 动作 400ms）。
export const wait = <T>(v: T, ms = 200): Promise<T> => new Promise((r) => setTimeout(() => r(v), ms));

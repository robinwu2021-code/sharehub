// OpsKit 客户端的线上约定（21/06 §二）：请求头、空间寻址、裸响应、错误体映射。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type Call = { url: string; init: RequestInit };
let calls: Call[] = [];
function reply(status: number, body: unknown) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return new Response(status === 204 ? null : JSON.stringify(body), { status });
  }));
}

async function load() {
  vi.resetModules();
  vi.stubEnv("NEXT_PUBLIC_FLOW_API", "http");
  vi.stubEnv("NEXT_PUBLIC_OPSKIT_BASE", "http://opskit.test/api/opskit/v1");
  vi.stubEnv("NEXT_PUBLIC_OPSKIT_WS", "ext:T001");
  vi.stubEnv("NEXT_PUBLIC_OPSKIT_ACCOUNT", "acc_1");
  return import("./client");
}

beforeEach(() => { calls = []; });
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

describe("httpFlow：请求形状", () => {
  it("空间寻址 + 编码；POST 带幂等键与 JSON；读接口按语言", async () => {
    const { flow } = await load();
    reply(200, { list: [], total: 0, page: 1, size: 20 });
    await flow.todos({ origin: "ALL", page: 1, size: 20 });
    const c = calls[0];
    expect(c.url).toBe("http://opskit.test/api/opskit/v1/workspaces/ext%3AT001/todos/query");
    const h = c.init.headers as Record<string, string>;
    expect(c.init.method).toBe("POST");
    expect(h["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(h["Content-Type"]).toBe("application/json");
    expect(h["Accept-Language"]).toBeTruthy();
    expect(JSON.parse(String(c.init.body))).toEqual({ origin: "ALL", page: 1, size: 20 });
  });

  it("GET 不带幂等键；两次写入的幂等键不同", async () => {
    const { flow } = await load();
    reply(200, {});
    await flow.approval("AP1");
    await flow.withdraw("AP1");
    await flow.withdraw("AP1");
    expect((calls[0].init.headers as Record<string, string>)["Idempotency-Key"]).toBeUndefined();
    const k1 = (calls[1].init.headers as Record<string, string>)["Idempotency-Key"];
    const k2 = (calls[2].init.headers as Record<string, string>)["Idempotency-Key"];
    expect(k1).not.toBe(k2);
  });

  it("退回抢单池走独立端点；改派 to 是主体引用", async () => {
    const { flow } = await load();
    reply(200, {});
    await flow.pool("WO/1", "联系不上");
    await flow.assign("WO1", { ref: "omar", displayName: "Omar" }, "休假");
    expect(calls[0].url).toMatch(/\/tickets\/WO%2F1\/pool$/);
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ reason: "联系不上" });
    expect(JSON.parse(String(calls[1].init.body))).toEqual({ to: "member:omar", reason: "休假" });
  });

  it("账户级资源走 /accounts/{a}", async () => {
    const { flow } = await load();
    reply(200, []);
    await flow.definitionVersions();
    expect(calls[0].url).toBe("http://opskit.test/api/opskit/v1/accounts/acc_1/definitions?workspace=ext%3AT001");
  });
});

describe("httpFlow：错误体映射", () => {
  it("422 带 errors[] → FlowValidationError，路径原样给页面", async () => {
    const { flow } = await load();
    const { isValidationError } = await import("./errors");
    reply(422, { error: "opskit.template.invalid", message: "保存被拒", errors: [{ path: "steps[0].mode", error: "会签不可用于角色" }] });
    const e = await flow.publishTemplate({ code: "X", name: { zh: "x" }, steps: [], rules: { selfApproval: false, crossStepExclusive: true } }, "", false).catch((x) => x);
    expect(isValidationError(e)).toBe(true);
    expect(e.errors).toEqual([{ path: "steps[0].mode", error: "会签不可用于角色" }]);
  });

  it("403 / 409 → ApiError，服务端本地化 message 优先", async () => {
    const { flow } = await load();
    reply(409, { error: "opskit.task.already_claimed", message: "已被 Sara 领取" });
    const e = await flow.claimTask("TK1").catch((x) => x);
    expect(e).toMatchObject({ name: "ApiError", code: 409, message: "已被 Sara 领取" });
  });

  it("没有 message 时按状态码给本地化兜底文案，不抛原始 JSON", async () => {
    const { flow } = await load();
    reply(403, {});
    const e = await flow.todoCount().catch((x) => x);
    expect(e.code).toBe(403);
    expect(e.message).not.toMatch(/[{}]/);
  });
});

describe("开关", () => {
  it("缺省走 mock，不发任何请求", async () => {
    vi.resetModules();
    reply(200, {});
    const { flow } = await import("./client");
    await flow.todoCount();
    expect(calls).toHaveLength(0);
  });
});

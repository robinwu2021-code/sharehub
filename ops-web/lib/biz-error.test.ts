// 业务报错三语单测：错误提示是用户最需要看懂的那一句，不能永远是中文。
import { describe, it, expect, afterEach } from "vitest";
import { fail, notFound } from "./biz-error";
import { isApiError } from "./api/error";
import { useLocaleStore } from "./stores/locale";

const setLocale = (l: "zh" | "en" | "ar") => useLocaleStore.setState({ locale: l });
afterEach(() => setLocale("zh"));

const caught = (fn: () => never) => {
  try { fn(); } catch (e) { return e; }
  throw new Error("应当抛出");
};

describe("fail", () => {
  it("抛 ApiError(400)，不是裸 Error —— 页面据此区分业务拒绝与系统故障", () => {
    const e = caught(() => fail("不行", "No"));
    expect(isApiError(e) && e.code).toBe(400);
  });

  it("按当前语言定稿", () => {
    setLocale("zh"); expect(caught(() => fail("不行", "No", "لا")).message).toBe("不行");
    setLocale("en"); expect(caught(() => fail("不行", "No", "لا")).message).toBe("No");
    setLocale("ar"); expect(caught(() => fail("不行", "No", "لا")).message).toBe("لا");
  });

  it("缺阿语时回退英文，不回退中文", () => {
    setLocale("ar");
    expect(caught(() => fail("不行", "No")).message).toBe("No");
  });
});

describe("notFound", () => {
  it("三语句式一致，带上编号", () => {
    setLocale("zh"); expect(caught(() => notFound("站点", "Site", "S1")).message).toBe("站点不存在：S1");
    setLocale("en"); expect(caught(() => notFound("站点", "Site", "S1")).message).toBe("Site not found: S1");
    setLocale("ar"); expect(caught(() => notFound("站点", "Site", "S1")).message).toContain("S1");
  });

  it("抛 404 而不是 400 —— 「找不到」和「规则不允许」不是一回事", () => {
    const e = caught(() => notFound("站点", "Site", "S1"));
    expect(isApiError(e) && e.code).toBe(404);
  });
});

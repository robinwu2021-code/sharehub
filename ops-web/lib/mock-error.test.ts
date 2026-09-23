// mock 层的业务报错必须走 `fail` / `notFound`（三语 + ApiError），不许裸 `throw new Error`。
//
// **为什么**：界面切到 EN / AR 之后，页面是英文或阿语、错误提示还是中文 ——
// 而错误提示恰恰是用户最需要看懂的那一句。真实后端那条路已经做对了
// （`http-client.ts` 发 Accept-Language、用后端本地化 message），mock 这条路要同构，
// 否则两种模式下体验不一致，而 mock 正是演示与验收时用的那一种。
//
// `throw new ApiError(400, …)` 也算合格：它类型对，只是消息还来自单语的 validate*。
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const DIRS = [join(__dirname, "mock", "db"), join(__dirname, "api", "mocks")];

/**
 * 唯一豁免：mock 种子数据自相矛盾时的断言。
 *
 * 它不是业务拒绝，是 bug 信号，只给开发看 —— 翻译它没有意义，
 * 把它变成 ApiError 反而会让它看起来像一次正常的业务失败。
 */
const ALLOWED = 1;

describe("mock 业务报错不许是裸 Error", () => {
  it(`裸 throw new Error 只剩下那条开发期断言（${ALLOWED} 处）`, () => {
    const hits: string[] = [];
    for (const dir of DIRS) {
      for (const name of readdirSync(dir)) {
        if (!name.endsWith(".ts") || name.endsWith(".test.ts")) continue;
        const src = readFileSync(join(dir, name), "utf8");
        for (const [i, line] of src.split("\n").entries()) {
          if (/throw new Error\(/.test(line)) hits.push(`${name}:${i + 1}`);
        }
      }
    }
    expect(hits.length, hits.join(", ")).toBe(ALLOWED);
  });
});

// 前后端漂移棘轮：契约缺口与幽灵权限码**只许减少**。
//
// ## 为什么要把它搬进 vitest
//
// 这两个检查本来就有（`scripts/check-backend-parity.py` / `check-perm-parity.py`），
// 挂在 `npm run check:drift` 下 —— 也就是**要有人记得去跑**。实际没人跑，
// 于是「漂移可被发现」退化成「漂移可被发现，如果你已经知道它在那儿」。
//
// 搬进测试之后 `npm run check` 就跑到了。不用 `--strict` 硬卡口：今天确有缺口，
// 硬卡会把所有人的 check 打挂，而这些缺口是后端待办、不是本次能修的东西。
// 棘轮的口径是「不许变多」，这才是并行开发里真正要守住的那条线。
//
// ## 这些数字代表什么
//
//  · **契约缺口**：前端已经在调、后端连路径都没有。页面上的表现是点了没反应或 404。
//  · **动词不符**：路径对、方法不对（多半是 `save*` 对着一个只有 GET 的路径）。
//  · **幽灵权限码**：前端以为自己在鉴权，而后端没有这个码 —— 等于没门禁。
//
// 要抬高其中任何一个数字，必须在这里显式改，改动会在 review 里显形。
import { describe, it, expect } from "vitest";
import { execFileSync } from "node:child_process";
import { join } from "node:path";

const ROOT = join(__dirname, "..");

function run(script: string): string {
  try {
    return execFileSync("python3", [join("scripts", script)], {
      cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (e) {
    // 脚本自身跑挂了（缺 python3、contract.json 路径变了…）也要显形，不能静默放行
    throw new Error(`${script} 跑不起来：${(e as Error).message}`);
  }
}

describe("前后端漂移棘轮（数字只许降）", () => {
  it("契约缺口 ≤ 0、动词不符 ≤ 6", () => {
    const head = run("check-backend-parity.py").split("\n")[0];
    const gap = Number(head.match(/缺 (\d+)/)?.[1]);
    const verb = Number(head.match(/动词不符 (\d+)/)?.[1]);
    expect(Number.isFinite(gap) && Number.isFinite(verb), `没解析出数字：${head}`).toBe(true);
    expect(gap, head).toBeLessThanOrEqual(0);
    expect(verb, head).toBeLessThanOrEqual(6);
  });

  it("幽灵权限码 ≤ 19", () => {
    const out = run("check-perm-parity.py");
    const ghost = Number(out.match(/前端在用但后端不存在的码（(\d+) 个）/)?.[1] ?? 0);
    expect(ghost, out.split("\n").slice(0, 3).join("\n")).toBeLessThanOrEqual(19);
  });
});

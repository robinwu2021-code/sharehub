// 设计 token 的自动化守卫（规范 §14：可自动化检查的硬指标）。
//
// 为什么需要这个：设计系统调了七八轮，每轮都留下一些"迁移期兜底"。
// 兜底本身没错，错在**没有机制阻止它变成长期状态** —— 圆角明明收成了五档，
// 组件层却一直混用 Tailwind 默认阶，五档形同虚设。
// 这个测试把清理结果**锁住**：清过的目录不许回流，未清的目录明确列为豁免，
// 豁免清单只能变短不能变长。
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(name)) out.push(p);
  }
  return out;
}

/** 废弃的圆角类：Tailwind 默认阶，应改用 control/field/card/sheet/chip 五档。 */
const DEPRECATED_RADIUS = /\brounded-(sm|md|lg|xl|2xl|3xl|full)\b/g;

/**
 * 豁免清单 —— **只允许变短**。
 * 这两个文件此刻有另一并发会话的在途改动（合计 3548 行业务功能），
 * 改它们会与对方的工作绞在一起。等对方提交后清理并从本清单移除。
 */
const RADIUS_EXEMPT = ["components/ui/tree.tsx", "components/quick-actions.tsx"];

describe("设计 token 守卫", () => {
  it("components/ 不再使用废弃的圆角类（豁免清单外）", () => {
    const offenders: string[] = [];
    for (const file of walk(join(ROOT, "components"))) {
      const rel = file.slice(ROOT.length).replace(/^\/+/, "");
      if (RADIUS_EXEMPT.some((e) => rel.endsWith(e))) continue;
      const hits = readFileSync(file, "utf8").match(DEPRECATED_RADIUS);
      if (hits) offenders.push(`${rel}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `改用五档圆角（rounded-control/field/card/sheet/chip）：\n${offenders.join("\n")}`)
      .toEqual([]);
  });

  it("组件层不写死颜色字面量（hex / rgb / oklch）", () => {
    // 允许 site-map.tsx：Google Maps JS API 只接受字符串色值，拿不到 CSS 变量。
    const EXEMPT = ["components/ui/site-map.tsx"];
    const offenders: string[] = [];
    for (const file of walk(join(ROOT, "components"))) {
      const rel = file.slice(ROOT.length).replace(/^\/+/, "");
      if (EXEMPT.some((e) => rel.endsWith(e))) continue;
      const src = readFileSync(file, "utf8");
      // 只看真正的颜色字面量，跳过注释行（注释里常引用 hex 说明来由）
      const hits = src
        .split("\n")
        .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
        .join("\n")
        .match(/#[0-9a-fA-F]{6}\b|\brgb\(|\boklch\(/g);
      if (hits) offenders.push(`${rel}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `颜色一律走 token：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("豁免清单不许变长", () => {
    // 这条是防止"顺手加豁免"的棘轮。要加豁免必须先改这个数字，
    // 从而在 code review 里显形。
    expect(RADIUS_EXEMPT.length).toBeLessThanOrEqual(2);
  });
});

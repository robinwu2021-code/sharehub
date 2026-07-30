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

/* ────────────────────────────────────────────────────────────────────────────
   页面层规范一致性（棘轮基线）

   这几条不是"现在就全绿"，而是**冻结当前偏离数量、只允许下降**。
   为什么用基线而不是豁免文件名：那 14 个页面此刻压着另一并发会话 3548 行在途
   业务改动，改不了；但**新页面必须一开始就合规**，而已有页面每清理一处，
   基线就该跟着降一处。数字写死在这里，涨了就红。
   ──────────────────────────────────────────────────────────────────────────── */
describe("页面层规范一致性（只允许变好）", () => {
  const pageFiles = () =>
    walk(join(ROOT, "app")).filter((f) => f.endsWith("page.tsx") && !f.includes("/dev/ui/"));

  const countAll = (re: RegExp) =>
    pageFiles().reduce((n, f) => n + (readFileSync(f, "utf8").match(re)?.length ?? 0), 0);

  it("手写「仅可查看」不增加 —— 应改用 <ReadOnlyNotice>（规范 §13）", () => {
    // 基线 17：句式统一 + 说清缺哪个权限码，全靠组件保证，手写必然发散
    expect(countAll(/仅可查看/g)).toBeLessThanOrEqual(17);
  });

  it("内联 <Badge tone={…}> 不增加 —— 枚举应走 StatusMap + <StatusBadge>", () => {
    // 基线 58：内联 ternary 让"状态→文案→色调"的映射散落在页面里，
    // 同一个枚举在两个页面可能配出不同颜色
    expect(countAll(/Badge tone=\{/g)).toBeLessThanOrEqual(58);
  });

  it("筛选用的裸 <Select> 不增加 —— 应改用 <FilterSelect>", () => {
    // 判据：<Select> 里带 `option value=""`（"全部 XX"）的才是**筛选**下拉；
    // 表单里的 <Select> 是合法用法，不算违规（清单里把两者混在一起数了）。
    // FilterSelect 的价值不是省一层标签，是让**筛选项文案与徽标文案同源**
    // （传 StatusMap 时选项由映射表派生），否则改文案要改两处。
    // 基线 17（页面里 <Select> 共 39 处，其余 22 处是表单）。
    // 注：先前用行级 grep 估成 16，块匹配实测 17 —— **以断言用的同一段代码为准**，
    // 估算与断言口径不同就会出现"基线一写上就红"。
    const bareFilterSelects = pageFiles().reduce((n, f) => {
      const src = readFileSync(f, "utf8");
      // 取每个 <Select …> 到 </Select> 的块，块内有空值 option 即视为筛选
      const blocks = src.match(/<Select[\s\S]*?<\/Select>/g) ?? [];
      return n + blocks.filter((b) => /<option value=""/.test(b)).length;
    }, 0);
    expect(bareFilterSelects).toBeLessThanOrEqual(17);
  });

  it("金额不许手写格式 —— 一律走 money()（规范 §12 数字列）", () => {
    // 这条基线是 **0**：当前已全部走 money()（120 处调用、0 处手写）。
    // 锁在 0 是为了防回归 —— 手写 `AED ${x}` 会绕过 Intl 本地化与货币符号位置，
    // 阿语 RTL 下符号位置是反的，手写必错。
    expect(countAll(/`AED \$\{|"AED " *\+|AED ` *\+/g)).toBe(0);
  });

  it("泛化空态文案不增加 —— 空态要说清**为什么**空（规范 §12）", () => {
    // 「暂无数据」「暂无记录」这类说不出原因的空态。基线 1（且那 1 处本身带解释）
    expect(countAll(/empty="暂无(数据|记录)"/g)).toBeLessThanOrEqual(1);
  });
});

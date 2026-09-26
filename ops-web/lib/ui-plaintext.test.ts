// 界面文案里不许出现 markdown 强调（`**…**`）—— 它会**原样显示成星号**。
//
// 【为什么值得一条卡口】
// 这个仓库的注释风格大量用 `**…**` 强调，而注释与界面文案写在同一个文件、
// 常常只隔一行。手一滑就把注释的写法带进 `help:` / `desc:` / `title:`，
// 而这些地方全是**纯文本渲染**（Radix Dialog.Description / FieldDef.help
// 都直接 {children}），没有任何一层会把它变成粗体。
//
// 症状是「看着像坏了但不报错」：用户看到一串星号，以为数据出了问题。
// 2026-09-26 实测撞到 4 处 —— 一处是当天新写的确认框，另三处是存量，
// 从写下那天起就一直那样显示着，没人报过。
//
// 判据只认**成对**的 `**…**`：掩码（`1000****00003`、`sk_live_****`）里
// 星号是连着的，中间没有内容，不会被误判。实测过这一点，见下方用例二。
import { describe, it, expect } from "vitest";
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(__dirname, "..");
/** 只扫会渲染到界面上的目录。lib/ 里的测试 DisplayName 用 `**…**` 是正常的。 */
const DIRS = ["app", "components"];

/** 成对的 markdown 强调。中间至少一个非星号字符 —— 掩码因此不会命中。 */
const MD_BOLD = /\*\*[^*\n]{1,80}\*\*/;

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const e of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    const rel = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...sourceFiles(rel));
    else if (e.name.endsWith(".tsx") && !e.name.includes(".test.")) out.push(rel);
  }
  return out;
}

/** 去掉块注释与行注释：注释里用 `**…**` 是本仓的写作风格，不是缺陷。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").split("\n")
    .map((l) => (l.includes("//") ? l.slice(0, l.indexOf("//")) : l)).join("\n");
}

describe("界面文案不含 markdown 强调", () => {
  it("★ app/ 与 components/ 的代码里没有成对的 **…**", () => {
    const offenders: string[] = [];
    for (const f of DIRS.flatMap(sourceFiles)) {
      stripComments(fs.readFileSync(path.join(ROOT, f), "utf8")).split("\n").forEach((line, i) => {
        const m = line.match(MD_BOLD);
        if (m) offenders.push(`${f}:${i + 1}  ${m[0]}`);
      });
    }
    expect(offenders, "这些 **…** 会原样显示成星号——改成纯文字强调（「必须」「不会生效」），\n"
      + "或换个说法。注释里怎么写不受影响。\n" + offenders.join("\n")).toEqual([]);
  });

  it("判据本身：掩码不误判，真强调必命中", () => {
    // 掩码里的星号是连着的，中间没有内容
    expect(MD_BOLD.test("1000****00003")).toBe(false);
    expect(MD_BOLD.test("sk_live_****")).toBe(false);
    expect(MD_BOLD.test("m***@bayside.ae")).toBe(false);
    // 真的 markdown 强调
    expect(MD_BOLD.test("如果他已经有口令，**原口令立刻失效**。")).toBe(true);
    expect(MD_BOLD.test("后端本期**不参与计算**")).toBe(true);
  });

  it("判据本身：注释里的 **…** 被剥掉，不算违例", () => {
    expect(stripComments("/** 这里**强调**一下 */\nconst a = 1;")).not.toMatch(MD_BOLD);
    expect(stripComments('const a = 1; // 说明：**必须**这样')).not.toMatch(MD_BOLD);
    // 但代码里的就要留下来
    expect(stripComments('const s = "**会显示成星号**";')).toMatch(MD_BOLD);
  });
});

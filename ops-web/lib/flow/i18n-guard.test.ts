// 流程与工单页面的三语与 RTL 卡口（22/01 NF-1）：
//  ① 注释以外不许出现中文 —— 文案一律进 lib/i18n/messages/flow/<页>.ts（三语同处）；
//  ② 不许用物理方向的类名（ml-/mr-/pl-/pr-/left-/right-/text-left/border-l…）—— 阿语下不会镜像，
//     改用逻辑属性（ms-/me-/ps-/pe-/start-/end-/text-start/border-s…）。
// mock-*.ts 不在范围内：那是「服务端」的数据与报错（真服务按 Accept-Language 给好）。
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(__dirname, "..", "..");
const SCOPES = ["app/flow", "components/flow", "lib/flow/present.ts", "lib/flow/labels.ts", "lib/flow/i18n.ts", "lib/flow/client.ts"];

function files(p: string): string[] {
  const abs = join(ROOT, p);
  if (statSync(abs).isFile()) return [abs];
  return readdirSync(abs).flatMap((n) => files(join(p, n))).filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f));
}
/** 去掉注释：块注释、JSX 注释、行注释（`//` 前须是行首或空白，避开 https://） */
const stripComments = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|\s)\/\/.*$/gm, "$1");

const ALL = SCOPES.flatMap(files);

describe("流程页面三语 / RTL", () => {
  it("注释以外没有中文", () => {
    const hits = ALL.flatMap((f) => stripComments(readFileSync(f, "utf8")).split("\n")
      .map((l, i) => [i + 1, l] as const).filter(([, l]) => /[一-鿿]/.test(l))
      .map(([n, l]) => `${relative(ROOT, f)}:${n} ${l.trim().slice(0, 80)}`));
    expect(hits, "文案进 lib/i18n/messages/flow/<页>.ts，页面用 useFlowT").toEqual([]);
  });

  it("不用物理方向类名", () => {
    const re = /(?<![\w-])(?:-?(?:ml|mr|pl|pr|left|right|rounded-l|rounded-r|border-l|border-r)-[\w[\]./-]+|text-left|text-right|float-left|float-right)(?![\w-])/g;
    const hits = ALL.flatMap((f) => [...stripComments(readFileSync(f, "utf8")).matchAll(re)].map((m) => `${relative(ROOT, f)} ${m[0]}`));
    expect(hits, "改用 ms-/me-/ps-/pe-/start-/end-/text-start/text-end/border-s/border-e/rounded-s/rounded-e").toEqual([]);
  });
});

describe("页面名与菜单同一译法", () => {
  it("common.page.* 与菜单叶（nav-labels）三语一致", async () => {
    const { translate, tNav } = await import("@/lib/i18n");
    const LEAF: Record<string, string> = {
      todo: "待办中心", approvals: "审批列表", queue: "审核队列", tickets: "工单工作台",
      processes: "流程配置", settings: "运营配置", access: "接入管理",
    };
    for (const loc of ["zh", "en", "ar"] as const) {
      for (const [k, zh] of Object.entries(LEAF)) {
        expect(translate(loc, `flow.common.page.${k}`), `${loc}:${k}`).toBe(loc === "zh" ? zh : tNav(zh, loc));
      }
    }
  });
});

import { describe, it, expect } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { UI_PERM_MAP, UNIMPLEMENTED } from "./perm-map";
import { BACKEND_ROLE_PERMS, can } from "./permissions";
import type { Role } from "./auth";

/**
 * 权限码的四条守卫。
 *
 * <h2>为什么要有</h2>
 * 2026-09-23 实测：前端镜像与后端 `RolePerms.java` **已经漂了 29 处** ——
 * 26 处「前端渲染入口、后端 403」、3 处「后端允许、界面上没有入口」，
 * 而两边的注释都写着「同源」。**没有任何东西在拦。**
 *
 * 这一类缺陷的共同点是**沉默**：漏一个码只是「那个按钮不见了」或者
 * 「点了报没权限」，不报错、不留日志，要等用户投诉才发现。
 */

const ROOT = path.resolve(__dirname, "..", "..");
const read = (p: string) => fs.readFileSync(path.join(ROOT, p), "utf8");
const CODE_RE = /"([a-z_]+:[a-z_]+:[a-z_*]+|[a-z_]+:\*|\*)"/g;

/** 括号配对取一段 —— 不能用正则贪婪匹配，嵌套括号会取错边界。 */
function span(text: string, from: number, open: string, close: string): string {
  let depth = 0;
  for (let i = from; i < text.length; i++) {
    if (text[i] === open) depth++;
    else if (text[i] === close && --depth === 0) return text.slice(from, i + 1);
  }
  throw new Error("括号不配对");
}

const stripComments = (s: string) => s.replace(/\/\/[^\n]*/g, "");

/** 后端 RolePerms.java 的角色 → 码。 */
function backendRolePerms(): Record<string, Set<string>> {
  const src = read("backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/iam/RolePerms.java");
  const out: Record<string, Set<string>> = {};
  for (const m of src.matchAll(/"([A-Z_]+)",\s*List\.of\(/g)) {
    const body = span(src, src.indexOf("(", m.index! + m[0].length - 1), "(", ")");
    out[m[1]] = new Set([...stripComments(body).matchAll(CODE_RE)].map((x) => x[1]));
  }
  return out;
}

/** 后端契约里端点真正判过的码。 */
function backendEndpointPerms(): Set<string> {
  const c = JSON.parse(read("docs/api/contract.json"));
  return new Set(c.endpoints.map((e: { perm?: string }) => e.perm).filter(Boolean));
}

/** nav.ts 里用到的码 —— 两种写法都要扫到（`perm: "x"` 与元组 `[..., "x", ...]`）。 */
function walk(dir: string): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) return walk(full);
    return /\.tsx?$/.test(e.name) && !e.name.includes(".test.") ? [full] : [];
  });
}

function navCodes(): Set<string> {
  return new Set([...read("ops-web/lib/nav.ts").matchAll(CODE_RE)].map((m) => m[1]));
}

describe("UI 权限码映射表", () => {
  it("解析没失效 —— 扫不到码时不能静默通过", () => {
    expect(navCodes().size).toBeGreaterThan(40);
    expect(Object.keys(backendRolePerms())).toContain("ADMIN");
    expect(backendEndpointPerms().size).toBeGreaterThan(100);
  });

  it("★★★ nav.ts 用到的每个码都要在映射表里 —— 漏一个就是一整片消失的菜单", () => {
    /*
     * 这条不是假想。D6a 落地时就漏过：提取脚本只认 `perm: "…"`，
     * 而 operation section 的叶子用的是**元组**写法
     * （["overview","站点概览","location:overview:read","场站管理"]），
     * 结果「计费与调价」「基础管理」两组菜单整组消失。
     */
    const missing = [...navCodes()].filter((c) => !(c in UI_PERM_MAP)).sort();
    expect(missing, `这些码在 nav.ts 里用了但没登记进 UI_PERM_MAP：\n${missing.join("\n")}`).toEqual([]);
  });

  it("★★★ 页面里 allow()/can() 用到的码也要在映射表里 —— 未登记 = 连超管都点不到", () => {
    /*
     * 上一条只扫 nav.ts。**页面里的按钮级判权扫不到**，而那正是 2026-09-28 实测到的洞：
     * `app/agents/page.tsx` 从 09-23 起就在 `allow("agent:apply:create")`，
     * 而这个码一直没进映射表。后果比菜单消失更隐蔽：
     *
     *   · 未登记 → can() 走「拒绝优先」返回 false；
     *   · 而**查映射在判通配之前**，所以 `perms=["*"]` 的超管同样被挡；
     *   · 那条 `console.warn` 只在非 production 打 —— 生产上零信号。
     *
     * 于是「新增代理商」按钮对所有人不可见。而 2026-09-27 收掉直连建档之后，
     * 代建录入是唯一的建档路 —— 等于**界面上建不了代理商**，四天没人发现。
     *
     * `permissions.ts` 里原本写着「守卫（perm-map.test.ts）会在 CI 拦住」，
     * 那句话对调用点**是不成立的**；这条用例就是来让它成立的。
     */
    const files = ["app", "components"].flatMap((d) => walk(path.join(ROOT, "ops-web", d)));
    const CALL_RE = /\ballow\(\s*"([^"]+)"|\bcan\([^,)]+,\s*"([^"]+)"/g;
    const missing = new Map<string, string>();
    for (const f of files) {
      // 剥注释：`need-perm.tsx` 的说明里就有 `allow("…")` 这样的示例文案
      const src = stripComments(fs.readFileSync(f, "utf8"));
      for (const m of src.matchAll(CALL_RE)) {
        const code = m[1] ?? m[2];
        if (!(code in UI_PERM_MAP)) missing.set(code, path.relative(ROOT, f));
      }
    }
    expect(missing.size, [...missing].map(([c, f]) => `  ${c}  ←  ${f}`).join("\n")).toBe(0);
  });

  it("★★★ 映射到的后端码必须真的被某个端点判过 —— 手滑一个字母 = 那个入口对所有人永久消失", () => {
    const real = backendEndpointPerms();
    // 外部强制模块（backend/external-perm-modules.txt，五处读取方口径一致）的码由别的服务判，
    // ShareHub 契约里本就没有 —— 不算悬空
    const external = new Set(
      read("backend/external-perm-modules.txt").split("\n")
        .filter((l) => l.trim() && !l.startsWith("#")).map((l) => l.split("\t")[0].trim()),
    );
    const covered = (c: string) =>
      real.has(c) || external.has(c.split(":")[0])
      || (c.endsWith(":*") && [...real].some((r) => r.startsWith(c.slice(0, -1))));

    /*
     * **棘轮，不是白名单**：当前有 39 个 UI 码映射到「后端一个端点都没判过」的码。
     * （2026-09-24：43 → 39。四个界面码补上了翻译 —— inventory:update→:transfer、
     * share_rule:config→:create、ad:manage→:update、alarm:notice_resend→alarm:update，
     * 后端本来就有这些端点，只是界面码没译过去。顺带收掉基线里原有的 2 格余量：
     * 基线写 45 而实测 43，余量会让两次新增漂移不报警。）
     * 它们是待建功能（清单里定了、端点还没做）与历史遗留的混合，逐个查证不在本批范围；
     * 但这个数字**只准降不准升** —— 加一个新的没有端点的码，这条就红。
     *
     * 其中至少有一个是**已知会 403 的真缺口**：`agent:settlement:read`
     * ——[接口清单 §3.4 G1] 记着「没有任何后端端点挂载，代理点『我的结算』必 403」。
     * 本守卫把那份人工清单变成了自动检出。
     *
     * ⚠️ 契约禁止 `delete*`（软删除语义，用 `archive*`），所以 `*:delete` 这类
     * 本来就不该有端点 —— 它们该从码表里退役，不是补端点。
     */
    // 2026-09-28：39 → 21。实测只有 21，余量 18 —— 足够悄悄放进 18 个新悬空码不报警
    // （上面那段注释警告过的正是这种余量）。发现于给 flow 模块加外部强制豁免时：
    // 拿掉豁免做负对照，这条居然还是绿的。
    const DANGLING_BASELINE = 21;

    const dangling = Object.entries(UI_PERM_MAP)
      .filter(([, m]) => m !== UNIMPLEMENTED && !covered(m as string))
      .map(([ui, m]) => `${ui} → ${String(m)}`)
      .sort();

    expect(
      dangling.length,
      `没有任何端点判的码从 ${DANGLING_BASELINE} 变成了 ${dangling.length}。`
        + `**只准降不准升**。新增的话先确认端点是否真的存在：\n${dangling.join("\n")}`,
    ).toBeLessThanOrEqual(DANGLING_BASELINE);
  });

  it("★★★ 前端镜像必须与后端 RolePerms 逐码一致 —— 这正是漂了 29 处的那道缝", () => {
    const be = backendRolePerms();
    const problems: string[] = [];
    for (const role of Object.keys(BACKEND_ROLE_PERMS) as Role[]) {
      const fe = new Set(BACKEND_ROLE_PERMS[role]);
      const b = be[role] ?? new Set<string>();
      for (const c of [...fe].filter((x) => !b.has(x)).sort()) {
        problems.push(`${role} 仅前端有 ${c} → 菜单渲染得出来、点进去 403`);
      }
      for (const c of [...b].filter((x) => !fe.has(x)).sort()) {
        problems.push(`${role} 仅后端有 ${c} → 后端允许，而界面上没有入口`);
      }
    }
    expect(problems, `\n${problems.join("\n")}\n`).toEqual([]);
  });
});

describe("can() 的语义", () => {
  it("超管通配", () => expect(can(["*"], "agent:apply:read")).toBe(true));

  it("★★ 没登录 / 空 perms 一律 false —— 「空即无限制」是权限系统里最常见的漏洞", () => {
    expect(can([], "agent:apply:read")).toBe(false);
    expect(can(undefined, "agent:apply:read")).toBe(false);
  });

  it("★★★ 没登记的码判 false（拒绝优先）—— 默认放行会静默开一个没人知道的口子", () => {
    expect(can(["*"], "no:such:code")).toBe(false);
  });

  it("★★ 模块通配匹配得上", () => {
    expect(can(["device:*"], "device:cabinet:read")).toBe(true);
    expect(can(["device:*"], "order:order:read")).toBe(false);
  });

  it("★★ 翻译条目按**后端真实的码**判，不按 UI 码", () => {
    /*
     * 这条原先拿押金举例，断言 order:deposit:manage **在后端没有端点**、
     * 要映到 order:deposit:update 才判得过。
     *
     * 2026-09-25 那个前提被修掉了：押金三个端点已换回真源表的码
     * （解冻/买断 → order:deposit:manage 仅 FIN，催缴 → order:arrears:dun 给 CS+FIN）。
     * 此前它们用的是 order:deposit:update（真源表里没有、也没有角色持有）
     * 与 order:intervene:execute（客服的码）—— 实测后果正好反了：
     * **财务不能催缴也不能买断，而客服能解冻押金**。
     *
     * 所以这条改用仍然存在的翻译（system:notify_log:resend → :update）来守同一个语义，
     * 而押金那两行翻译已从 UI_PERM_MAP 撤掉 —— 撤掉本身由上一条用例（镜像一致）守着。
     */
    expect(can(["system:notify_log:update"], "system:notify_log:resend")).toBe(true);
    expect(can(["system:notify_log:resend"], "system:notify_log:resend")).toBe(false);

    // 押金现在是直通的：后端判什么码，界面就用什么码
    expect(can(["order:deposit:manage"], "order:deposit:manage")).toBe(true);
    expect(can(["order:deposit:update"], "order:deposit:manage")).toBe(false);
  });
});

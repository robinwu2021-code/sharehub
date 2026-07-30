// 规范体检：在**已渲染的 DOM 上**跑扫描，而不是读源码。
//
// 为什么在 DOM 上跑：这页把 23 个组件的全部状态都渲染出来了，所以"页面上出现过的
// 违规"就等于"组件层的违规"。DOM 扫描的好处是**不会过期**——组件改好了，
// 这里的清单自动变短，不需要有人回来维护一份手抄的问题列表。
//
// 局限（诚实记在这里，别让读者高估它）：
// 1. 只看得到当前渲染出来的节点。抽屉/弹窗关着时其内容不在 DOM 里，需要打开后再点「重新体检」。
// 2. class 字符串扫描判断不了「这个 h-9 是不是刻意的」，所以结论是**线索**不是判决。
// 3. 不覆盖没在本页出现的组件（quick-actions、layout/*）。

export type Finding = {
  /** 归属组件（最近的 [data-comp] 祖先） */
  comp: string;
  /** 违反了哪条规范 */
  rule: string;
  /** 实测到的值 */
  detail: string;
  /** 定位线索：标签名 + 截断后的 class */
  sample: string;
};

export type AuditResult = {
  findings: Finding[];
  /** 扫过多少个元素 / 多少个可聚焦元素 */
  scanned: number;
  focusable: number;
};

const RULES = {
  radius: "圆角只允许 chip/field/card/sheet 四档",
  focus: "可聚焦元素必须有 focus-visible 环",
  focusOffset: "焦点环缺 ring-offset（globals.css 统一写法要求 offset-2 + offset-[--ring-offset-bg]）",
  z: "浮层层级必须走 z-[var(--z-…)]",
  hex: "颜色必须走 token，不许写死 hex",
  shadow: "阴影只有 shadow-card / shadow-pop 两档",
  dur: "过渡时长走 var(--dur) / var(--dur-fast)",
  ctlH: "控件高走 var(--ctl-h)、表格行高走 var(--row-h)（否则密度切换无效）",
  numAlign: "数字单元格应右对齐（规范 §12：数字右对齐 + 等宽）",
  pkWeight: "主键列应加强字重（规范 §12：扫描时需要锚点）",
} as const;

function classOf(el: Element): string {
  return el.getAttribute("class") ?? "";
}

function compOf(el: Element): string {
  const owner = el.closest("[data-comp]");
  return owner?.getAttribute("data-comp") ?? "(未归属)";
}

function sampleOf(el: Element): string {
  const cls = classOf(el).replace(/\s+/g, " ").trim();
  return `<${el.tagName.toLowerCase()}> ${cls.length > 90 ? cls.slice(0, 90) + "…" : cls}`;
}

const FOCUSABLE = 'button,a[href],input,select,textarea,[tabindex]:not([tabindex="-1"])';

/** 允许的圆角档位（px 数值）。rounded-full 在 Tailwind 4 下会算成极大值，单独按"药丸"处理。 */
function allowedRadii(): number[] {
  const cs = getComputedStyle(document.documentElement);
  const read = (name: string) => parseFloat(cs.getPropertyValue(name)) || NaN;
  // --r-control 是刻意只给 16px 级小控件（Checkbox）开的第五档，见 globals.css 里的注释：
  // 四档最小的 field(11px) 会把 16px 方块夹成正圆、与单选点撞脸。漏掉它会把 Checkbox
  // 唯一正确的圆角误判成"违规"。
  return [0, read("--r-control"), read("--r-field"), read("--r-card"), read("--r-sheet")].filter((x) => !Number.isNaN(x));
}

/** 四个角是否同一个值；返回该值（px），不一致返回 null（不一致本身不判违规，四角不同是合法设计） */
function uniformRadius(el: Element): number | null {
  const cs = getComputedStyle(el);
  const corners = [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius];
  // 只处理简单 <n>px 形态；百分比/椭圆(两值)一律跳过
  const vals = corners.map((c) => (/^-?[\d.]+px$/.test(c.trim()) ? parseFloat(c) : NaN));
  if (vals.some(Number.isNaN)) return null;
  return vals.every((v) => Math.abs(v - vals[0]) < 0.51) ? vals[0] : null;
}

/**
 * 只扫 `[data-specimen]` 容器内的节点 —— 也就是**真正的组件实例**。
 * 本页自己的骨架（分区标题、状态标签、这份体检表本身）不参与扫描，
 * 否则清单里一半是这页自己的 div，读的人没法当工作单用。
 */
function specimens(root: HTMLElement): HTMLElement[] {
  const out: HTMLElement[] = [];
  for (const box of Array.from(root.querySelectorAll<HTMLElement>("[data-specimen]"))) {
    out.push(...Array.from(box.querySelectorAll<HTMLElement>("*")));
  }
  return out;
}

/**
 * 表格级检查：数字列右对齐 + 主键列字重。
 *
 * **这两条只能在 DOM 上查，源码 grep 查不了** —— 判断"这一列是不是数字列"
 * 需要看渲染出来的内容，判断"对齐/字重对不对"需要看计算样式。
 * 所以它们不在 lib/design-tokens.test.ts（那是源码扫描），而在这里。
 */
function auditTables(root: HTMLElement, findings: Finding[]) {
  for (const table of Array.from(root.querySelectorAll("table"))) {
    const rows = Array.from(table.querySelectorAll<HTMLTableRowElement>("tbody tr"));
    if (rows.length < 2) continue;
    const colCount = rows[0].cells.length;

    for (let c = 0; c < colCount; c++) {
      const cells = rows.map((r) => r.cells[c]).filter(Boolean);
      if (cells.length < 2) continue;
      const texts = cells.map((td) => (td.textContent ?? "").trim()).filter(Boolean);
      if (texts.length < 2) continue;

      // 纯数字列：只含数字/千分位/小数点/货币符号/斜杠（如 "7/8"）与空白
      const numeric = texts.every((t) => /^[\d.,\s/%+-]+$|^[A-Z]{3}\s[\d.,]+$/.test(t));
      if (numeric) {
        const align = getComputedStyle(cells[0]).textAlign;
        if (align !== "right" && align !== "end") {
          findings.push({
            comp: compOf(table), rule: RULES.numAlign,
            detail: `第 ${c + 1} 列 text-align: ${align}（示例 "${texts[0]}"）`,
            sample: sampleOf(cells[0]),
          });
        }
      }

      // 主键列：第一个非选择框列，内容形如 CAB1000 / ORD500001（字母前缀 + 数字）
      const isPk = c <= 2 && texts.every((t) => /^[A-Z]{2,4}[-_]?\d{3,}$/.test(t));
      if (isPk) {
        const w = Number(getComputedStyle(cells[0]).fontWeight);
        if (w < 500) {
          findings.push({
            comp: compOf(table), rule: RULES.pkWeight,
            detail: `第 ${c + 1} 列 font-weight: ${w}（示例 "${texts[0]}"）`,
            sample: sampleOf(cells[0]),
          });
        }
      }
    }
  }
}

export function audit(root: HTMLElement): AuditResult {
  const findings: Finding[] = [];
  const allowed = allowedRadii();
  const pill = 100; // ≥100px 视为药丸（= --r-chip 的 9999px）
  const all = specimens(root);

  for (const el of all) {
    const cls = classOf(el);
    if (el.hasAttribute("data-audit-skip")) continue;

    // ── 圆角
    const r = uniformRadius(el);
    if (r != null && r > 0.51 && r < pill && !allowed.some((a) => Math.abs(a - r) < 0.51)) {
      findings.push({ comp: compOf(el), rule: RULES.radius, detail: `border-radius: ${r}px`, sample: sampleOf(el) });
    }

    // ── 硬编码颜色（class 里的 hex 与 inline style 里的 hex）
    const styleAttr = el.getAttribute("style") ?? "";
    const hex = [...cls.matchAll(/#[0-9a-fA-F]{3,8}\b/g), ...styleAttr.matchAll(/#[0-9a-fA-F]{3,8}\b/g)];
    if (hex.length) {
      findings.push({ comp: compOf(el), rule: RULES.hex, detail: hex.map((m) => m[0]).join(" "), sample: sampleOf(el) });
    }

    // ── 硬编码 z-index（放过 z-[var(--z-…)] 与 z-0/z-10 这类语义无关的层内排序？——
    //    不放过：z-40/50 以上一定是浮层，正是踩过坑的地方）
    const z = cls.match(/(?:^|\s)z-\[?(\d+)\]?(?:\s|$)/);
    if (z && Number(z[1]) >= 20) {
      findings.push({ comp: compOf(el), rule: RULES.z, detail: z[0].trim(), sample: sampleOf(el) });
    }

    // ── 阴影
    const sh = cls.match(/(?:^|\s)shadow-(sm|md|lg|xl|2xl|inner)(?:\s|$)/);
    if (sh) {
      findings.push({ comp: compOf(el), rule: RULES.shadow, detail: sh[0].trim(), sample: sampleOf(el) });
    }

    // ── 过渡时长
    const du = cls.match(/(?:^|\s)duration-(\d+)(?:\s|$)/);
    if (du) {
      findings.push({ comp: compOf(el), rule: RULES.dur, detail: du[0].trim(), sample: sampleOf(el) });
    }

    // ── 控件高度 / 行高硬写
    const isCtl = /^(button|input|select|textarea)$/.test(el.tagName.toLowerCase());
    const h = cls.match(/(?:^|\s)h-(8|9|10|11)(?:\s|$)/);
    if (isCtl && h) {
      findings.push({ comp: compOf(el), rule: RULES.ctlH, detail: h[0].trim(), sample: sampleOf(el) });
    }
  }

  // ── 焦点环
  const focusables = all.filter(
    (el) => el.matches(FOCUSABLE) && !el.hasAttribute("disabled") && !el.hasAttribute("data-audit-skip"),
  );
  for (const el of focusables) {
    const cls = classOf(el);
    const hasRing = /focus-visible:(ring|outline-|shadow)/.test(cls);
    if (!hasRing) {
      findings.push({ comp: compOf(el), rule: RULES.focus, detail: "class 里没有 focus-visible:*", sample: sampleOf(el) });
    } else if (!/focus-visible:ring-offset/.test(cls)) {
      findings.push({ comp: compOf(el), rule: RULES.focusOffset, detail: "有 ring 无 offset", sample: sampleOf(el) });
    }
  }

  auditTables(root, findings);

  return { findings, scanned: all.length, focusable: focusables.length };
}

/** 同 comp + 同 rule 合并，便于当工作单读 */
export type Grouped = { comp: string; rule: string; count: number; details: string[]; samples: string[] };

export function groupFindings(findings: Finding[]): Grouped[] {
  const map = new Map<string, Grouped>();
  for (const f of findings) {
    const k = `${f.comp}||${f.rule}`;
    const g = map.get(k) ?? { comp: f.comp, rule: f.rule, count: 0, details: [], samples: [] };
    g.count++;
    if (!g.details.includes(f.detail)) g.details.push(f.detail);
    if (g.samples.length < 3) g.samples.push(f.sample);
    map.set(k, g);
  }
  return [...map.values()].sort((a, b) => b.count - a.count || a.comp.localeCompare(b.comp));
}

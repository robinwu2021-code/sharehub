// 运行时颜色解析 + WCAG 对比度计算（仅服务 /dev/ui 这张体检页）。
//
// 为什么必须**运行时算**而不是把数值写进代码：
// token 是 color-mix 派生的（--success-ink = color-mix(in oklch, --success 72%, black)），
// 明暗两态、三套皮肤各不相同；一旦写死，改 token 后这页就开始说谎，
// 而"Badge 文字对比度不足"这类缺陷恰恰是靠这页才发现的。
//
// 解析链路：getComputedStyle → 现代色彩语法字符串 → 手写解析器 → sRGB。
// 不用 canvas fillStyle 兜底：Chrome 对 oklab()/color() 的 canvas 解析在不同版本
// 行为不一，静默失败会给出错误数字（比不给数字更糟）。解析不了就明确报 null。

export type RGBA = { r: number; g: number; b: number; a: number };

const clamp01 = (x: number) => (x < 0 ? 0 : x > 1 ? 1 : x);

/** 逗号或空格分隔的数字/百分比列表 → number[]（百分比按 scale 归一） */
function nums(body: string): (number | null)[] {
  return body
    .replace(/\//g, " ")
    .split(/[\s,]+/)
    .filter(Boolean)
    .map((tok) => {
      if (tok === "none") return 0;
      if (tok.endsWith("%")) {
        const v = parseFloat(tok);
        return Number.isFinite(v) ? v / 100 : null;
      }
      const v = parseFloat(tok);
      return Number.isFinite(v) ? v : null;
    });
}

/** 线性 sRGB → 8bit sRGB */
function encodeGamma(c: number): number {
  const v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(Math.max(c, 0), 1 / 2.4) - 0.055;
  return clamp01(v) * 255;
}

/** Oklab → sRGB（Björn Ottosson 的原始矩阵） */
function oklabToRgb(L: number, a: number, b: number): { r: number; g: number; b: number } {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  return {
    r: encodeGamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    g: encodeGamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    b: encodeGamma(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  };
}

/**
 * 解析任意计算值颜色字符串。支持：transparent / #hex / rgb()/rgba() /
 * color(srgb …) / oklab() / oklch() / hsl()。解析不了返回 null（调用方要显式展示"未知"）。
 */
export function parseColor(input: string): RGBA | null {
  const css = input.trim().toLowerCase();
  if (!css || css === "none") return null;
  if (css === "transparent") return { r: 0, g: 0, b: 0, a: 0 };

  if (css.startsWith("#")) {
    const h = css.slice(1);
    const ex = h.length <= 4 ? h.split("").map((c) => c + c).join("") : h;
    if (ex.length !== 6 && ex.length !== 8) return null;
    const n = (i: number) => parseInt(ex.slice(i * 2, i * 2 + 2), 16);
    return { r: n(0), g: n(1), b: n(2), a: ex.length === 8 ? n(3) / 255 : 1 };
  }

  const fn = css.match(/^([a-z-]+)\((.*)\)$/);
  if (!fn) return null;
  const [, name, bodyRaw] = fn;
  let body = bodyRaw;
  let space = "";
  if (name === "color") {
    const m = body.match(/^([a-z0-9-]+)\s+(.*)$/);
    if (!m) return null;
    space = m[1];
    body = m[2];
  }
  const v = nums(body);
  if (v.some((x) => x === null)) return null;
  const n = v as number[];
  const alpha = (i: number) => (n.length > i ? clamp01(n[i]) : 1);

  switch (name) {
    case "rgb":
    case "rgba": {
      // 注意：rgb() 里 0~255 是裸数字，百分比已在 nums 里除过 100，需还原
      const ch = (i: number) => (bodyRaw.includes("%") ? n[i] * 255 : n[i]);
      return { r: ch(0), g: ch(1), b: ch(2), a: alpha(3) };
    }
    case "hsl":
    case "hsla": {
      const [h, s, l] = [n[0], clamp01(n[1]), clamp01(n[2])];
      const c = (1 - Math.abs(2 * l - 1)) * s;
      const hp = (((h % 360) + 360) % 360) / 60;
      const x = c * (1 - Math.abs((hp % 2) - 1));
      const seg: [number, number, number][] = [
        [c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x],
      ];
      const [r1, g1, b1] = seg[Math.floor(hp) % 6];
      const m = l - c / 2;
      return { r: (r1 + m) * 255, g: (g1 + m) * 255, b: (b1 + m) * 255, a: alpha(3) };
    }
    case "oklab": {
      const c = oklabToRgb(n[0], n[1], n[2]);
      return { ...c, a: alpha(3) };
    }
    case "oklch": {
      const hRad = ((n[2] ?? 0) * Math.PI) / 180;
      const c = oklabToRgb(n[0], (n[1] ?? 0) * Math.cos(hRad), (n[1] ?? 0) * Math.sin(hRad));
      return { ...c, a: alpha(3) };
    }
    case "color": {
      // color(srgb r g b) —— Chrome 把 color-mix(in srgb, …) 计算成这个形态
      if (space !== "srgb") return null;
      return { r: clamp01(n[0]) * 255, g: clamp01(n[1]) * 255, b: clamp01(n[2]) * 255, a: alpha(3) };
    }
    default:
      return null;
  }
}

/** 前景（可能半透明）合成到背景上 */
export function over(fg: RGBA, bg: RGBA): RGBA {
  const a = fg.a + bg.a * (1 - fg.a);
  if (a === 0) return { r: 0, g: 0, b: 0, a: 0 };
  const ch = (f: number, b: number) => (f * fg.a + b * bg.a * (1 - fg.a)) / a;
  return { r: ch(fg.r, bg.r), g: ch(fg.g, bg.g), b: ch(fg.b, bg.b), a };
}

function luminance({ r, g, b }: RGBA): number {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** WCAG 2.1 对比度（1~21）。两色都当作不透明处理，调用前请先合成。 */
export function contrast(a: RGBA, b: RGBA): number {
  const [l1, l2] = [luminance(a), luminance(b)];
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * 元素的**实际**背景色：自身背景半透明时向上合成祖先背景，直到遇到不透明层。
 * 只看 background-color（不看 background-image/渐变，本项目组件层没有渐变底）。
 */
export function effectiveBg(el: Element): RGBA {
  const stack: RGBA[] = [];
  let cur: Element | null = el;
  while (cur) {
    const c = parseColor(getComputedStyle(cur).backgroundColor);
    if (c && c.a > 0) {
      stack.push(c);
      if (c.a >= 0.999) break;
    }
    cur = cur.parentElement;
  }
  // 兜底：文档根一律按白/黑（canvas 变量），避免栈为空时除零
  let base: RGBA = stack.length ? stack[stack.length - 1] : { r: 255, g: 255, b: 255, a: 1 };
  if (base.a < 0.999) base = over(base, { r: 255, g: 255, b: 255, a: 1 });
  for (let i = stack.length - 2; i >= 0; i--) base = over(stack[i], base);
  return base;
}

export type Measured = {
  /** 实测对比度；null = 颜色语法解析不了 */
  ratio: number | null;
  fg: string;
  bg: string;
};

/** 量一个已挂载元素的「文字 vs 实际背景」对比度 */
export function measure(el: Element): Measured {
  const cs = getComputedStyle(el);
  const fgRaw = cs.color;
  const bg = effectiveBg(el);
  const fg = parseColor(fgRaw);
  if (!fg) return { ratio: null, fg: fgRaw, bg: rgbText(bg) };
  const solidFg = fg.a >= 0.999 ? fg : over(fg, bg);
  return { ratio: contrast(solidFg, bg), fg: rgbText(solidFg), bg: rgbText(bg) };
}

export function rgbText({ r, g, b }: RGBA): string {
  const h = (x: number) => Math.round(clamp01(x / 255) * 255).toString(16).padStart(2, "0");
  return `#${h(r)}${h(g)}${h(b)}`;
}

/** AA 判据：正文 4.5、大字（≥18.66px 或 ≥14px 且 bold）3.0 */
export function aaThreshold(el: Element): number {
  const cs = getComputedStyle(el);
  const px = parseFloat(cs.fontSize) || 14;
  const weight = parseInt(cs.fontWeight, 10) || 400;
  const large = px >= 24 || (px >= 18.66 && weight >= 700);
  return large ? 3 : 4.5;
}

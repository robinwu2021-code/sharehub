// i18n 单测：三语 key 齐平（防漏译）+ 插值/回退 + DIR。对照 TDD-国际化i18n §4。
import { describe, it, expect } from "vitest";
import { zh } from "./messages/zh";
import { en } from "./messages/en";
import { ar } from "./messages/ar";
import { translate, DIR, LOCALE_TAG } from "./index";

function keys(obj: Record<string, unknown>, prefix = ""): string[] {
  return Object.entries(obj).flatMap(([k, v]) => {
    const path = prefix ? `${prefix}.${k}` : k;
    return typeof v === "object" && v !== null ? keys(v as Record<string, unknown>, path) : [path];
  });
}

describe("catalog 三语齐平（无漏译）", () => {
  const zk = keys(zh).sort();
  it("en 与 zh key 一致", () => expect(keys(en as never).sort()).toEqual(zk));
  it("ar 与 zh key 一致", () => expect(keys(ar as never).sort()).toEqual(zk));
  it("无空串", () => {
    for (const [name, cat] of [["en", en], ["ar", ar]] as const) {
      for (const k of zk) {
        // lang.zh/lang.en/lang.ar 三处本就跨语言固定，不校验
        if (k.startsWith("lang.")) continue;
        expect(translate(name, k), `${name}:${k}`).not.toBe("");
      }
    }
  });
});

describe("translate 行为", () => {
  it("插值 {n}", () => expect(translate("zh", "common.totalItems", { n: 5 })).toBe("共 5 条"));
  it("en 插值", () => expect(translate("en", "common.totalItems", { n: 5 })).toBe("5 items"));
  it("缺失语言值回退 zh", () => expect(translate("en", "common.appName")).toBe(en.common.appName));
  it("未知 key 原样返回", () => expect(translate("en", "no.such.key")).toBe("no.such.key"));
});

describe("方向与 locale tag", () => {
  it("ar=rtl, zh/en=ltr", () => {
    expect(DIR.ar).toBe("rtl");
    expect(DIR.zh).toBe("ltr");
    expect(DIR.en).toBe("ltr");
  });
  it("locale tag", () => expect(LOCALE_TAG.ar).toBe("ar-AE"));
});

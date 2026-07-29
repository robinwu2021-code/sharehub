// FormDrawer 校验纯函数单测：required/min/max/maxLength/pattern + 优先级 + 禁用跳过 + csv/分区。
import { describe, it, expect } from "vitest";
import {
  isEmptyValue, isFieldDisabled, validateField, validateAll,
  csvToArray, arrayToCsv, groupBySection, type ValidatableField,
} from "./form-validate";

// 桩 t：把 key 与参数拼成可断言的字符串，避免依赖 i18n catalog。
const t = (k: string, p?: Record<string, string | number>) =>
  p ? `${k}(${Object.entries(p).map(([a, b]) => `${a}=${b}`).join(",")})` : k;

const F = (over: Partial<ValidatableField>): ValidatableField => ({ key: "k", label: "字段", ...over });

describe("isEmptyValue", () => {
  it("null/undefined/空串/空白串/空数组为空", () => {
    for (const v of [null, undefined, "", "   ", []]) expect(isEmptyValue(v)).toBe(true);
  });
  it("0 / false / 非空串 / 非空数组不为空", () => {
    for (const v of [0, false, "a", ["a"]]) expect(isEmptyValue(v)).toBe(false);
  });
});

describe("required", () => {
  const f = F({ required: true });
  it("空值报 form.required", () => {
    expect(validateField(f, { k: "" }, t)).toBe("form.required(label=字段)");
    expect(validateField(f, {}, t)).toBe("form.required(label=字段)");
    expect(validateField(f, { k: [] }, t)).toBe("form.required(label=字段)");
  });
  it("有值通过", () => expect(validateField(f, { k: "x" }, t)).toBeNull());
  it("数字 0 视为有值", () => expect(validateField(F({ required: true, type: "number" }), { k: 0 }, t)).toBeNull());
  it("switch 不判必填", () => expect(validateField(F({ required: true, type: "switch" }), { k: false }, t)).toBeNull());
  it("非必填时空值直接放行（不触发 min/pattern）", () => {
    expect(validateField(F({ min: 5, pattern: { re: "^\\d+$", msg: "m" } }), { k: "" }, t)).toBeNull();
  });
});

describe("min / max", () => {
  it("小于 min 报错", () =>
    expect(validateField(F({ type: "number", min: 1 }), { k: 0 }, t)).toBe("form.minValue(label=字段,min=1)"));
  it("大于 max 报错", () =>
    expect(validateField(F({ type: "number", max: 100 }), { k: 101 }, t)).toBe("form.maxValue(label=字段,max=100)"));
  it("区间内通过", () =>
    expect(validateField(F({ type: "number", min: 1, max: 100 }), { k: 50 }, t)).toBeNull());
  it("字符串数字也能比较", () =>
    expect(validateField(F({ type: "number", min: 10 }), { k: "3" }, t)).toBe("form.minValue(label=字段,min=10)"));
  it("非数字报 numberInvalid", () =>
    expect(validateField(F({ type: "number", min: 1 }), { k: "abc" }, t)).toBe("form.numberInvalid(label=字段)"));
  it("number 型无 min/max 但非数字也报 numberInvalid", () =>
    expect(validateField(F({ type: "number" }), { k: "abc" }, t)).toBe("form.numberInvalid(label=字段)"));
});

describe("maxLength", () => {
  it("超长报错", () =>
    expect(validateField(F({ maxLength: 3 }), { k: "abcd" }, t)).toBe("form.maxLength(label=字段,max=3)"));
  it("等于上限通过", () => expect(validateField(F({ maxLength: 3 }), { k: "abc" }, t)).toBeNull());
});

describe("pattern", () => {
  const f = F({ pattern: { re: "^[A-Z]{2}$", msg: "只能是两位大写字母" } });
  it("不匹配返回自定义 msg", () => expect(validateField(f, { k: "ae" }, t)).toBe("只能是两位大写字母"));
  it("匹配通过", () => expect(validateField(f, { k: "AE" }, t)).toBeNull());
  it("msg 可用 i18n key", () =>
    expect(validateField(F({ pattern: { re: "^x$", msg: "form.invalidFormat" } }), { k: "y" }, t)).toBe("form.invalidFormat"));
  it("正则写错不误伤", () =>
    expect(validateField(F({ pattern: { re: "([", msg: "m" } }), { k: "y" }, t)).toBeNull());
});

describe("多错误优先级：required → min/max → maxLength → pattern", () => {
  const f = F({
    required: true, type: "number", min: 10, max: 20, maxLength: 1,
    pattern: { re: "^\\d{5}$", msg: "五位数" },
  });
  it("空值先报 required", () => expect(validateField(f, { k: "" }, t)).toBe("form.required(label=字段)"));
  it("越界先报 min（早于 maxLength/pattern）", () =>
    expect(validateField(f, { k: 5 }, t)).toBe("form.minValue(label=字段,min=10)"));
  it("越上界先报 max", () => expect(validateField(f, { k: 99 }, t)).toBe("form.maxValue(label=字段,max=20)"));
  it("范围内但超长报 maxLength（早于 pattern）", () =>
    expect(validateField(f, { k: 15 }, t)).toBe("form.maxLength(label=字段,max=1)"));
  it("长度合规后才轮到 pattern", () => {
    const g = F({ required: true, min: 10, maxLength: 5, pattern: { re: "^\\d{5}$", msg: "五位数" } });
    expect(validateField(g, { k: "12" }, t)).toBe("五位数");
  });
});

describe("禁用字段跳过校验", () => {
  it("readOnlyOnEdit 在编辑态跳过", () => {
    const f = F({ required: true, readOnlyOnEdit: true });
    expect(validateField(f, { k: "" }, t, true)).toBeNull();
    expect(validateField(f, { k: "" }, t, false)).toBe("form.required(label=字段)");
  });
  it("disabledWhen 为真时跳过", () => {
    const f = F({ required: true, disabledWhen: (v) => v.mode === "auto" });
    expect(validateField(f, { k: "", mode: "auto" }, t)).toBeNull();
    expect(validateField(f, { k: "", mode: "manual" }, t)).toBe("form.required(label=字段)");
  });
  it("disabledWhen 抛错时按未禁用处理", () => {
    const f = F({ required: true, disabledWhen: () => { throw new Error("x"); } });
    expect(isFieldDisabled(f, {}, false)).toBe(false);
  });
});

describe("validateAll", () => {
  it("聚合多字段错误", () => {
    const fields = [
      F({ key: "a", label: "A", required: true }),
      F({ key: "b", label: "B", type: "number", max: 5 }),
      F({ key: "c", label: "C" }),
    ];
    expect(validateAll(fields, { a: "", b: 9, c: "ok" }, t)).toEqual({
      a: "form.required(label=A)",
      b: "form.maxValue(label=B,max=5)",
    });
  });
  it("全部合法返回空对象", () => {
    expect(validateAll([F({ required: true })], { k: "v" }, t)).toEqual({});
  });
  it("无任何校验属性的字段（现存 13 页用法）永不产生错误", () => {
    const legacy = [
      F({ key: "no", label: "编号", readOnlyOnEdit: true }),
      F({ key: "name", label: "名称" }),
      F({ key: "amount", label: "金额", type: "number" }),
      F({ key: "on", label: "启用", type: "switch" }),
    ];
    expect(validateAll(legacy, {}, t)).toEqual({});
    expect(validateAll(legacy, { name: "x", amount: 1, on: true }, t)).toEqual({});
  });
});

describe("csv 互转", () => {
  it("字符串转数组并去空白", () => expect(csvToArray(" AE , SA ,")).toEqual(["AE", "SA"]));
  it("数组原样", () => expect(csvToArray(["AE"])).toEqual(["AE"]));
  it("空/非法转空数组", () => {
    expect(csvToArray("")).toEqual([]);
    expect(csvToArray(null)).toEqual([]);
  });
  it("数组转 csv", () => expect(arrayToCsv(["AE", "SA"])).toBe("AE,SA"));
});

describe("groupBySection", () => {
  it("相邻同名合段，无 section 自成一段", () => {
    const segs = groupBySection([
      { key: "a", section: undefined },
      { key: "b", section: "基础" },
      { key: "c", section: "基础" },
      { key: "d", section: "高级" },
      { key: "e", section: "基础" },
    ] as { key: string; section?: string }[]);
    expect(segs.map((s) => [s.section, s.fields.length])).toEqual([
      [undefined, 1], ["基础", 2], ["高级", 1], ["基础", 1],
    ]);
  });
  it("全无 section 时只有一段（保持现有平铺行为）", () => {
    const segs = groupBySection([{ key: "a" }, { key: "b" }] as { key: string; section?: string }[]);
    expect(segs).toHaveLength(1);
    expect(segs[0].section).toBeUndefined();
  });
});

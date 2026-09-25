import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

/**
 * **内联的状态联合类型，跨端词表卡口一个字都比不了。**
 *
 * <h3>怎么发现的</h3>
 * 后端 `StatusVocabularyAcrossEndsTest` 的判据是**两端同名即比对**：
 * 扫后端 `public enum X`、扫本目录 `export type X = "A" | "B"`，名字相同的成对检查。
 * 它的类注释写着「自动发现，不靠人登记」—— 但那只对**具名**类型成立。
 * 写成 `status: "ACTIVE" | "EXPIRED";` 内联在 interface 里的，它**配不上对，看不见**。
 *
 * 2026-09-25 实测：后端 90 个枚举，两端同名能比对上的只有 34 对，
 * 而本目录里有 **75 处内联状态联合**。合同就藏在其中 ——
 * 后端 6 态（DRAFT/PENDING/SIGNED/ACTIVE/EXPIRED/TERMINATED），
 * 前端 `Contract.status` 只有 `"ACTIVE" | "EXPIRED"`，差 4 个态而卡口全绿。
 *
 * <h3>这条卡口做什么、不做什么</h3>
 * **不要求**把 71 处全抽成具名 —— 其中大多数（`ACTIVE|DISABLED` 这类通用两值集）
 * 在后端没有对应的同名枚举，硬起个名字只会造出**假配对**，
 * 让卡口去强制一段本来无关的耦合，那比看不见更糟。
 *
 * **只做一件事：不让这个数字再涨。** 新增状态字段时写成具名 `export type`，
 * 这样只要后端有同名枚举，跨端卡口立刻自动覆盖它，不必有人记得去登记。
 *
 * <h3>红了怎么办</h3>
 * 你多半刚加了一个内联的状态联合。抽成 `export type`（放在所属 interface 之前），
 * 数字会自己降回去 —— 顺手把下面的基线也改小。基线**只准降**。
 */

const TYPES_DIR = "lib/types";

/** 目录下所有 .ts（含子目录），跳过测试文件本身。 */
function typeFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return typeFiles(full);
    if (!name.endsWith(".ts") || name.endsWith(".test.ts")) return [];
    return [full];
  });
}

/** interface 成员上、由大写字面量组成的联合（≥2 个取值）。 */
const INLINE_UNION = /^\s*\w+\??:\s*(?:"[A-Z][A-Z0-9_]*"\s*\|\s*)+"[A-Z][A-Z0-9_]*"\s*;/gm;
const INTERFACE_BLOCK = /export interface (\w+)[^{]*\{([\s\S]*?)\n\}/g;

function inlineUnions(): { file: string; iface: string; line: string }[] {
  const found: { file: string; iface: string; line: string }[] = [];
  for (const file of typeFiles(TYPES_DIR)) {
    const src = readFileSync(file, "utf-8");
    for (const block of src.matchAll(INTERFACE_BLOCK)) {
      for (const m of block[2].matchAll(INLINE_UNION)) {
        found.push({ file, iface: block[1], line: m[0].trim() });
      }
    }
  }
  return found;
}

describe("跨端词表卡口的可见性", () => {
  it("内联状态联合不增加 —— 新字段一律写成具名 export type", () => {
    const found = inlineUnions();
    // 基线（原 75，2026-09-25 抽出 CsSenderType/DeviceCodeType/CodeBatchStatus/ReconTaskStatus 四个）
    expect(
      found.length,
      `内联状态联合变多了。它们对跨端词表卡口**不可见**（见本文件类注释）。
新增的状态字段请写成具名 \`export type\`：
${found.slice(0, 5).map((f) => `  ${f.file} · ${f.iface} · ${f.line}`).join("\n")}`,
    ).toBeLessThanOrEqual(71);
  });

  it("前提：真的扫到了东西 —— 扫不到会让本条恒绿", () => {
    expect(typeFiles(TYPES_DIR).length).toBeGreaterThan(10);
    expect(inlineUnions().length).toBeGreaterThan(0);
  });
});

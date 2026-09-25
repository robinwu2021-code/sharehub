#!/usr/bin/env python3
"""表单字段 vs 后端写入面 一致性检查。

## 为什么要有这条

前端表单里写 `{ key: "operator" }`，后端请求体上的字段叫 `operatorNo` —— **没有任何东西会响**。
`@RequestBody` 静默忽略不认识的字段，前端拿回 200，运营填完保存看起来一切正常，
只是那一格永远存不进去。2026-09-25 在库存调拨单上一次撞见三个：

    operator      → 后端读 operatorNo     经办人输入框填了没用，列表里那一列一直空
    fromLocation  → 后端读 fromName       调出点位存不进去
    toLocation    → 后端读 toName         调入点位存不进去

整张调拨表单对真后端基本是空转，而它是靠人眼逐字比对 `invFields` 与 `InvTransferReq`
才发现的。现有 5 个漂移检查器管的是类型 / 枚举 / 端点是否存在，**没有一个**管
「表单里这个 key 后端到底接不接得住」。

## 判据

表单字段（`FieldDef.key`，以及 `latKey`/`lngKey` 这两个也会往 values 里写键的）
必须出现在该表单提交到的端点的 `requestShape` 里。两个例外**不算漂移**：

  · 端点路径变量同名的键（如 `transferNo` 对 `/inventory-transfers/{transferNo}`）
    —— 前端拿它决定 POST 到哪个 URL，不是指望后端从 body 里读；
  · 标了 `readOnlyOnEdit` 且同时是路径变量的业务主键，同上。

## 怎么挂上端点

表单声明上方写一行注解，多个端点写多行（建单与更新常常是两个）：

    /** @form POST /api/ops/inventory-transfers */
    const invFields = (...): FieldDef[] => [ ... ];

**不用自动推断**。试过按文件内的 `api.saveXxx` 调用反查，一个页面里有七张表单时
根本对不准，而一条对错了的告警比没有告警更糟 —— 它会让人开始忽略整个检查器。
注解是「这张表单发到哪」被显式决定过一次的记录。

尚未注解的表单登记在 `backend/known-unmapped-forms.txt`，**只准变短**：
新表单必须一开始就注解，存量慢慢补。

用法（在 ops-web 下）：python3 scripts/check-form-fields.py [--strict]
真值来源 `docs/api/contract.json`，由 `python3 backend/scripts/api-extract.py` 生成。
"""
import json, re, sys, pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
LEDGER = REPO / "backend/known-unmapped-forms.txt"
DRIFT_LEDGER = REPO / "backend/known-form-field-drift.txt"
STRICT = "--strict" in sys.argv

norm = lambda p: (re.sub(r"\{[^}]*\}", "{}", p).rstrip("/") or "/")
PATHVARS = lambda p: set(re.findall(r"\{(\w+)\}", p))


def contract():
    p = REPO / "docs/api/contract.json"
    if not p.exists():
        sys.exit("缺少 %s —— 先在仓库根跑 `python3 backend/scripts/api-extract.py` 生成。" % p)
    data = json.loads(p.read_text())
    if "entityRequestShape" not in (data.get("features") or []):
        # 旧版抽取结果里，实体请求体的 requestShape 一律是 null —— 与「没有请求体」
        # 同一个值。拿它核对会把实体请求体的那批表单整批判成漂移。
        #
        # 这种情况**不判红**：它不是漂移，是真源过期。判红唯一的消音办法是重新生成
        # contract.json，而并行开发时那会把别人在途的端点一起带进提交 ——
        # 一条会诱导人做错事的卡口，比没有卡口更糟。
        print("== 表单字段 vs 后端写入面 ==")
        print("跳过：docs/api/contract.json 是旧版抽取结果（缺 features.entityRequestShape）。")
        print("在仓库根跑 `python3 backend/scripts/api-extract.py` 重新生成后本检查才有意义。")
        sys.exit(0)
    out = {}
    for e in data["endpoints"]:
        shape = e.get("requestShape") or []
        out[(e["verb"], norm(e["path"]))] = (
            {f["name"] for f in shape}, e["path"], e.get("bodyType"))
    return out


# 声明有三种写法，统一按「`: FieldDef[]` 之后紧跟 `=> [` 或函数体 `{`」来找，
# 再往回找最近的 const / function 取名字 —— 比为每种写法各写一条正则稳，
# 尤其是参数列表换行的那几处（`): FieldDef[] {` 与声明名隔着好几行）。
DECL = re.compile(r":\s*FieldDef\[\]\s*(=>?\s*\[|\{)")
NAME_BACK = re.compile(r"(?:const|function)\s+(\w+)")


def forms():
    """扫出每个 FieldDef[] 声明：名字、所在文件行、字段键、注解的端点。"""
    found = []
    roots = [REPO / "ops-web/app", REPO / "ops-web/components"]
    files = sorted(f for r in roots for f in r.rglob("*.tsx"))
    files += sorted(f for r in roots for f in r.rglob("*.ts"))
    for f in files:
        src = f.read_text()
        lines = src.splitlines()
        for m in DECL.finditer(src):
            head = src[max(0, m.start() - 600): m.start()]
            names = list(NAME_BACK.finditer(head))
            if not names:
                continue
            nm = names[-1]        # 最近的那个声明名，不是窗口里最早的
            name = nm.group(1)
            open_at = m.end() - 1
            body = balanced(src, open_at)
            keys = re.findall(r'\bkey:\s*"([^"]+)"', body)
            keys += re.findall(r'\b(?:latKey|lngKey):\s*"([^"]+)"', body)
            if not keys:
                continue          # 不是表单定义（如 FieldDef[] 形参的类型标注）
            line = src[: max(0, m.start() - 600) + nm.start()].count("\n") + 1
            eps, syn = annotations(lines, line - 1)
            found.append({
                "file": str(f.relative_to(REPO)), "line": line, "name": name,
                "keys": sorted(set(keys)), "endpoints": eps, "synthetic": syn,
            })
    return found


def balanced(src, i):
    """从 `[` 起按括号配平取出数组字面量（字符串里的括号不算）。"""
    depth, j, quote = 0, i, None
    while j < len(src):
        c = src[j]
        if quote:
            if c == "\\":
                j += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
        elif c in "[{(":
            depth += 1
        elif c in "]})":
            depth -= 1
            if depth == 0:
                return src[i: j + 1]
        j += 1
    return src[i:]


ANNOT = re.compile(r"@form\s+(GET|POST|PUT|PATCH|DELETE)\s+(\S+)")
# 合成字段：只活在表单里，提交前已经拆成后端认的字段（如目标人群下拉
# `audienceKey` = `类型:值`，提交时拆回 audienceType/audienceValue）。
# 这类键后端本来就不该认，报出来是诬告。必须写理由——一个能随手加的豁免
# 迟早会被用来消音真漂移。
IGNORE = re.compile(r"@form-synthetic\s+(\w+)\s+(.+?)\s*(?:\*/)?$")


def annotations(lines, idx):
    """声明上方连续的注释块里找 @form / @form-synthetic；空行即止，避免蹭到上一个声明的注释。"""
    eps, syn, i = [], {}, idx - 1
    while i >= 0:
        s = lines[i].strip()
        if not s:
            break
        if not (s.startswith("*") or s.startswith("/*") or s.startswith("//")):
            break
        for verb, path in ANNOT.findall(s):
            eps.append((verb, path))
        for key, why in IGNORE.findall(s):
            syn[key] = why.strip()
        i -= 1
    return list(reversed(eps)), syn


def main():
    api = contract()
    read = lambda p: ({l.strip() for l in p.read_text().splitlines()
                       if l.strip() and not l.startswith("#")} if p.exists() else set())
    known, known_drift = read(LEDGER), read(DRIFT_LEDGER)

    drift, stale, unmapped, unverifiable, checked = [], [], [], [], 0
    for fm in forms():
        ident = "%s::%s" % (fm["file"], fm["name"])
        if not fm["endpoints"]:
            unmapped.append(ident)
            continue
        checked += 1
        accept, pathvars, missing_ep, blind = set(), set(), [], []
        for verb, path in fm["endpoints"]:
            hit = api.get((verb, norm(path)))
            if hit is None:
                missing_ep.append("%s %s" % (verb, path))
                continue
            shape, real, body_type = hit
            # 有请求体却抽不出字段 = **不知道**，不是「什么都不认」。
            # 这两件事在 contract.json 里都是空集合，第一版把它们读成同一个，
            # 于是整张表单被判成全字段漂移 —— 一条诬告就够让人不再看这个检查器。
            if body_type and not shape:
                blind.append("%s %s（bodyType=%s）" % (verb, path, body_type))
                continue
            accept |= shape
            pathvars |= PATHVARS(real)
        if missing_ep:
            stale.append((ident, missing_ep))
            continue
        if blind and not accept:
            unverifiable.append((ident, blind))
            continue
        bad = [k for k in fm["keys"]
               if k not in accept and k not in pathvars and k not in fm["synthetic"]]
        for k in bad:
            drift.append(("%s %s" % (ident, k), ident, fm["line"], k, sorted(accept)))

    print("== 表单字段 vs 后端写入面 ==")
    print("已挂端点的表单 %d 张，未挂 %d 张（台账 %d 条）\n" % (checked, len(unmapped), len(known)))

    new_drift = [d for d in drift if d[0] not in known_drift]
    fixed_drift = sorted(known_drift - {d[0] for d in drift})
    if drift:
        print("【漂移】表单里这些键，后端请求体接不住 —— 填了不报错，就是存不进去：")
        for ident, _f, line, k, accept in drift:
            mark = "  " if ident in known_drift else "新 "
            print("%s%s:%d  发出 %s" % (mark, _f, line, k))
            print("      后端认：%s" % (", ".join(accept) or "（该端点无请求体）"))
        print()
    if fixed_drift:
        print("【台账该变短了】这些漂移已经没了，请从 %s 删掉：" % DRIFT_LEDGER.name)
        for x in fixed_drift:
            print("  %s" % x)
        print()

    if unverifiable:
        print("【核不了】端点声明了请求体，但 api-extract 抽不出它的字段表 ——")
        print("  多半是内部类 DTO 没被扫到。补 api-extract.py，别在这里放宽判据：")
        for ident, eps in unverifiable:
            print("  %s → %s" % (ident, "; ".join(eps)))
        print()

    if stale:
        print("【注解失效】@form 指的端点在 contract.json 里不存在（端点改了名？注解没跟上？）：")
        for ident, eps in stale:
            print("  %s → %s" % (ident, "; ".join(eps)))
        print()

    new = sorted(set(unmapped) - known)
    gone = sorted(known - set(unmapped))
    if new:
        print("【新增未挂端点的表单】新表单必须一开始就写 @form 注解：")
        for x in new:
            print("  %s" % x)
        print()
    if gone:
        print("【台账该变短了】这些已经挂上端点，请从 %s 删掉：" % LEDGER.name)
        for x in gone:
            print("  %s" % x)
        print()

    bad = bool(new_drift or fixed_drift or stale or new or gone)   # 核不了不算红：它是覆盖率问题，不是漂移
    if not bad:
        print("没有新漂移。" if drift else "没有发现漂移。")
    if STRICT and bad:
        sys.exit(1)


if __name__ == "__main__":
    main()

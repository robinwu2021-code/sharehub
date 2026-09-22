#!/usr/bin/env python3
"""权限码 前端 vs 后端 一致性检查。

为什么需要它：本仓一天内就撞出 **4 处**前后端权限码漂移，且全都「今天不炸」——
ADMIN 有 `*`、各角色有模块通配（`workorder:*`），所以窄授权之前谁都看不出问题：

  · finance:recon:handle（前端） vs finance:recon:resolve（后端）
  · device:ota:publish（前端） vs device:ota:manage（后端），而登记表里只有 read
  · org:role:write（SSOT 文档） vs org:role:update（后端与前端实际）
  · location:lead:update（前端） vs location:crm:read/update（后端）

逐个拍板是治标。判据只有两条，都能自动查：
  (a) 前端用到的码，后端必须真的有 —— 否则前端在给一个不存在的码做门禁，等于没门禁；
  (b) 后端要求的码，`lib/permissions.ts` 必须有角色覆盖得到 —— 否则除 ADMIN 外无人可用
      （device:ota:manage 就是这个情况：功能上线了，OPS 点不到）。

用法（在 ops-web 下）：python3 scripts/check-perm-parity.py [--strict]
  --strict：有问题时以非零码退出，可用于 CI 卡口。

⚠️ 本脚本刻意**不**读 docs/requirements/功能权限清单.md：那份是人写的 SSOT，
   与代码不一致时该改文档还是改代码需要人判断，脚本不该替人决定。这里只比对
   「后端强制的」与「前端登记/使用的」——两者都是代码，不一致就是 bug。
"""
import re, sys, pathlib, collections

REPO = pathlib.Path(__file__).resolve().parents[2]
OPS = REPO / "ops-web"

def backend_required():
    """后端 @PreAuthorize("@perm.can('x')") 里出现的权限码 → {code: [文件…]}。"""
    out = collections.defaultdict(list)
    for f in (REPO / "backend").rglob("*.java"):
        sf = str(f)
        if "worktrees" in sf or "/target/" in sf or "/test/" in sf:
            continue
        for m in re.finditer(r"@perm\.can\('([a-z_]+:[a-z_]+:[a-z_]+)'\)", f.read_text(errors="ignore")):
            out[m.group(1)].append(f.name)
    return out

def registered_patterns():
    """lib/permissions.ts 里各角色登记的码/通配（`*`、`device:*`、具体码）。"""
    src = (OPS / "lib/permissions.ts").read_text()
    body = src[src.index("ROLE_PERMS"):src.index("function match")]
    return set(re.findall(r'"([a-z_*][a-z_:*]*)"', body))

def frontend_used():
    """页面/组件里 allow("x") / can(role,"x") 实际做门禁用的码 → {code: [文件…]}。"""
    out = collections.defaultdict(list)
    for sub in ("app", "lib", "components"):
        for f in (OPS / sub).rglob("*.ts*"):
            if ".test." in f.name or "/dev/ui/" in str(f):
                continue
            for m in re.finditer(r'(?:allow|can)\(\s*(?:role,\s*)?"([a-z_]+:[a-z_]+:[a-z_]+)"', f.read_text(errors="ignore")):
                out[m.group(1)].append(f.name)
    return out

def covered(code, pats):
    """该码是否被任一登记项覆盖（支持 `*` 与 `<模块>:*` 前缀通配，与 permissions.ts 的 match 同语义）。"""
    for p in pats:
        if p == code or p == "*":
            return True
        if p.endswith("*") and code.startswith(p[:-1]):
            return True
    return False

be, pats, fe = backend_required(), registered_patterns(), frontend_used()

# (a) 前端拿来做门禁、后端却没有这个码 —— 门禁形同虚设
ghost = {c: v for c, v in fe.items() if c not in be}
# (b) 后端强制、前端没有任何角色覆盖 —— 功能除 ADMIN(*) 外无人可用
uncovered = {c: v for c, v in be.items() if not covered(c, pats)}

print(f"后端强制 {len(be)} 个码 · 前端登记 {len(pats)} 项 · 页面使用 {len(fe)} 个码")

if ghost:
    print(f"\n❌ 前端在用但后端不存在的码（{len(ghost)} 个）—— 前端以为在鉴权，其实没有：")
    for c, files in sorted(ghost.items()):
        near = [b for b in be if b.rsplit(":", 1)[0] == c.rsplit(":", 1)[0]]
        hint = f"  后端同资源有：{', '.join(sorted(near))}" if near else ""
        print(f"  {c:34s} 用于 {sorted(set(files))[0]}{hint}")

if uncovered:
    print(f"\n⚠️ 后端强制但前端无角色覆盖的码（{len(uncovered)} 个）—— 除 ADMIN 外无人可用：")
    for c, files in sorted(uncovered.items()):
        print(f"  {c:34s} 后端于 {sorted(set(files))[0]}")

if not ghost and not uncovered:
    print("\n✅ 无漂移。")

sys.exit(1 if ((ghost or uncovered) and "--strict" in sys.argv) else 0)

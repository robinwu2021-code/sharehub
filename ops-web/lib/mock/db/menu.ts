import type { MenuPatch } from "../../api/contracts/org";
import type { MenuNode } from "../../api/contracts/dashboard";
import { fail } from "../../biz-error";

/**
 * mock 的菜单库。
 *
 * <h3>为什么要有「真的 db」而不是每次从 NAV 现算</h3>
 * 仓库约定：**mock 必须真改 db，重开能读回**。菜单管理要是只在界面上改了个 state，
 * 离线开发时「保存」看着成功、刷新就没了 —— 而那正是这一轮在反复修的缺陷类型。
 * 这里用一层**覆盖表**：底稿仍是 nav.ts（唯一真源），改动落在覆盖表里，
 * 读的时候叠上去。
 *
 * <h3>两道闸在 mock 层也要强制</h3>
 * 与后端 `IamAdminController.updateMenu` 同规则：
 * perm 必须在目录里 · 不能把「员工与权限」藏掉。
 * mock 放行而后端拒绝的话，离线调一路顺、切后端当场吃 500 ——
 * 这条坑本仓库已经踩过四次（saveShareRule / saveVenueOnboarding /
 * reviewVenueOnboarding / 两个详情端点）。
 */

/** 回得来的那扇门：菜单管理自己就在这一支下面。与后端的 ADMIN_SECTION 同值。 */
const ADMIN_SECTION = "M_org";

/** menuNo → 改过的字段。只存差异，底稿永远是 nav.ts。 */
const overrides = new Map<string, MenuPatch>();

/** 把覆盖叠到一个节点上。 */
function apply(n: MenuNode): MenuNode {
  const o = overrides.get(n.menuNo);
  if (!o) return n;
  return {
    ...n,
    name: o.name ?? n.name,
    nameEn: o.nameEn ?? n.nameEn,
    nameAr: o.nameAr ?? n.nameAr,
    group: o.groupName ?? n.group,
    sort: o.sort ?? n.sort,
    perm: o.perm === undefined ? n.perm : (o.perm || null),
    // visible 不在 MenuNode 上：下发的树里本就没有「隐藏的」，
    // 隐藏与否体现在 allMenus/visibleMenus 两个读法的差异里
  };
}

function hidden(menuNo: string): boolean {
  return overrides.get(menuNo)?.visible === 0;
}

/** nav.ts → 服务端形状。底稿，未叠覆盖。 */
async function draft(): Promise<MenuNode[]> {
  const { NAV } = await import("../../nav");
  const { tNav } = await import("../../i18n/nav-labels");
  return NAV.map((s, i) => ({
    menuNo: `M_${s.key}`, parentNo: null, name: s.label,
    nameEn: tNav(s.label, "en"), nameAr: tNav(s.label, "ar"),
    type: "MENU" as const, path: s.href, icon: s.icon ?? null, group: null, sort: i + 1,
    perm: s.perm ?? null, phase: s.phase ?? 1, ready: false, module: s.module ?? null,
    modules: s.modules ?? [], match: s.match ?? [], pinBottom: !!s.pinBottom,
    portalFor: (s.portalFor ?? []) as string[],
    children: (s.children ?? []).map((l, k) => ({
      menuNo: `M_${s.key}__${k + 1}`, parentNo: `M_${s.key}`, name: l.label,
      nameEn: tNav(l.label, "en"), nameAr: tNav(l.label, "ar"),
      type: "ITEM" as const, path: l.href, icon: null, group: l.group ?? null,
      sort: k + 1, perm: l.perm ?? null, phase: l.phase ?? 1, ready: !!l.ready,
      module: null, modules: [], match: [], pinBottom: false, portalFor: [], children: [],
    })),
  }));
}

/** **全量**菜单树（连停用的也在）。管理界面用 —— 藏了还得改得回来。 */
export async function allMenus(): Promise<MenuNode[]> {
  return (await draft()).map((s) => ({
    ...apply(s),
    children: s.children.map(apply),
  }));
}

/** 某个身份**看得到**的菜单树。规则与后端 MenuService.visibleFor 一致。 */
export async function visibleMenus(perms: string[], role: string): Promise<MenuNode[]> {
  const { can } = await import("../../permissions");
  const tree = await allMenus();
  const ok = (p: string | null) => !p || can(perms, p);
  const portalUser = tree.some((s) => s.portalFor.includes(role));
  return tree
    .filter((s) => (portalUser ? s.portalFor.includes(role) : !s.portalFor.length))
    .filter((s) => !hidden(s.menuNo))
    .map((s) => ({ ...s, children: s.children.filter((c) => !hidden(c.menuNo) && ok(c.perm)) }))
    .filter((s) => (s.children.length > 0) || (!hidden(s.menuNo) && !draftHasChildren(s) && ok(s.perm)));
}

function draftHasChildren(s: MenuNode): boolean {
  return s.children.length > 0;
}

/**
 * 改一个菜单项。两道闸与后端同规则，**违规抛错**而不是静默放行。
 *
 * @param catalog 权限码目录（`iam_permission`），用于校验 perm
 */
export async function updateMenu(
  menuNo: string, patch: MenuPatch, catalog: string[],
): Promise<MenuNode> {
  const tree = await allMenus();
  const flat = tree.flatMap((s) => [s, ...s.children]);
  if (!flat.some((n) => n.menuNo === menuNo)) {
    fail(`菜单不存在：${menuNo}`, `Menu not found: ${menuNo}`, `القائمة غير موجودة: ${menuNo}`);
  }
  if (patch.perm && !catalog.includes(patch.perm)) {
    fail(
      `权限码不在目录里：${patch.perm}。挂上去这个菜单对谁都不可见（除超管），而且不会报错`,
      `Permission code not in catalog: ${patch.perm}`,
      `رمز الصلاحية غير موجود في الدليل: ${patch.perm}`,
    );
  }
  // 先写再验，违规就回滚 —— 与后端「在事务里拦」同一个形状
  const before = overrides.get(menuNo);
  overrides.set(menuNo, { ...before, ...patch });
  if (patch.visible === 0 && menuNo === ADMIN_SECTION) {
    if (before === undefined) overrides.delete(menuNo); else overrides.set(menuNo, before);
    fail(
      "这一改之后「员工与权限」对谁都不可见了——菜单是运营端唯一的入口，改成这样之后谁都进不来把它改回去",
      "That would hide the only way back in",
      "هذا سيخفي المدخل الوحيد للعودة",
    );
  }
  const after = await allMenus();
  return after.flatMap((s) => [s, ...s.children]).find((n) => n.menuNo === menuNo)!;
}

/** 这个菜单是不是被停用了。管理界面要显示停用态。 */
export function isHidden(menuNo: string): boolean {
  return hidden(menuNo);
}

package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 菜单树下发（真源 = {@code iam_menu}，2026-09-24 从 ops-web/lib/nav.ts 迁过来）。
 *
 * <h2>可见性在这里判，一处</h2>
 * 规则三条，与前端 {@code visibleSections}/{@code visibleLeaves} 等价：
 * <ol>
 *   <li><b>门户</b>：命中某个 section 的 {@code portal_for} 的角色，
 *       <b>只</b>看得到这些门户 section；其余角色<b>看不到</b>门户 section。
 *       代理商是运营方体内的受限外部伙伴（ADR-012），此前靠「无 perm 的叶子
 *       跟随父模块」漏出过 SLA 管理 / 巡检计划 / BD 拓展 CRM。</li>
 *   <li><b>叶子</b>：{@code perm} 为空则可见，否则要求 {@code hasPerm}。
 *       V74 之后 109 个叶子全部带码，"为空"这一支实际已无使用者 ——
 *       留着是因为将来新增菜单时漏填码的表现应当是"谁都看得到"而不是"谁都看不到"，
 *       前者会被 review 发现，后者只会被当成"功能没做"。</li>
 *   <li><b>section</b>：有子节点则要求<b>至少一个子节点可见</b>；
 *       没有子节点（目前只有「经营看板」）则看它自己的 {@code perm}。</li>
 * </ol>
 *
 * <h2>为什么曾经不敢在这里过滤</h2>
 * 58ec85f 把本类做成了「下发完整树、前端过滤」，理由是前端 {@code can()} 会先经
 * {@code UI_PERM_MAP} 把界面码翻译成后端码，而后端没有这层翻译，过滤会误剪。
 * <b>该理由经实测对菜单不成立</b>：96 个（V74 后 109 个）带码的菜单叶，
 * 需要翻译的是 <b>0 个</b> —— 那 8 条翻译全服务于页内按钮。
 *
 * <h2>这不是安全边界</h2>
 * 闸在每个端点的 {@code @PreAuthorize} 上，收了权的会话调接口当场 403
 * （{@code PermsRefreshWithoutReloginTest} 验过）。这里过滤是为了
 * <b>不给用户一个点下去必然 403 的入口</b>，不是为了拦截。
 *
 * <h2>卡口</h2>
 * {@code MenuVisibilityParityTest} 拿本类算出的树与前端快照
 * {@code ops-web/lib/nav-visibility.snapshot.txt} 逐项对拍 ——
 * 两套独立实现比同一份提交物，任何一边先漂都会红。
 */
@Service
public class MenuService {

    private final MenuMapper menuMapper;
    private final ObjectMapper json = new ObjectMapper();

    public MenuService(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /**
     * 菜单节点（下发前端）。字段与 {@code NavSection}/{@code NavLeaf} 一一对应 ——
     * 少一个，前端就得为它留一份本地数据，那就又回到"两处真源"。
     *
     * <p>{@code soon} 不在这里，也不该在：它由前端 {@code pageReady(page, useMock)}
     * 算出来，取决于前端连的是 mock 还是真后端 —— 构建的属性，不是菜单的属性。
     */
    public record MenuNode(String menuNo, String parentNo, String name, String nameEn, String nameAr,
                           String type, String path, String icon, String group, Integer sort,
                           String perm, Integer phase, boolean ready,
                           String module, List<String> modules, List<String> match,
                           boolean pinBottom, List<String> portalFor,
                           List<MenuNode> children) {
    }

    /** 完整菜单树，不过滤。给「菜单管理」这类要看全量的场景用。 */
    public List<MenuNode> tree() {
        return build(all(), null);
    }

    /** 当前登录人可见的菜单树（规则见类注释）。 */
    public List<MenuNode> visibleFor(LoginUser u) {
        List<IamMenu> rows = all();
        String role = u.role() == null ? "" : u.role();
        // 这个人是不是某个专属门户的主人 —— 决定他看门户还是看通用运营项
        boolean portalUser = rows.stream()
                .anyMatch(m -> m.getParentNo() == null && arr(m.getPortalFor()).contains(role));

        List<MenuNode> out = new java.util.ArrayList<>();
        for (IamMenu s : rows) {
            if (s.getParentNo() != null) continue;
            boolean isPortal = !arr(s.getPortalFor()).isEmpty();
            /*
             * 门户的人只看门户；非门户的人看不到门户。
             *
             * ⚠️ 别写成 `portalUser != (isPortal && contains(role))` ——
             * 对「非门户用户 × 门户 section」两边都是 false，于是**不跳过**，
             * 运营角色就把代理门户全看见了。这个写法我连错两次
             * （一次在测量脚本里，一次在这里），两次都是对拍卡口抓出来的。
             */
            boolean keep = portalUser
                    ? (isPortal && arr(s.getPortalFor()).contains(role))
                    : !isPortal;
            if (!keep) continue;

            List<MenuNode> kids = rows.stream()
                    .filter(m -> s.getMenuNo().equals(m.getParentNo()))
                    .filter(m -> permitted(m, u))
                    .map(m -> toNode(m, List.of()))
                    .toList();
            boolean hasChildren = rows.stream().anyMatch(m -> s.getMenuNo().equals(m.getParentNo()));
            // 有子节点：至少一个可见；没有子节点：看自己的码
            if (hasChildren ? kids.isEmpty() : !permitted(s, u)) continue;
            out.add(toNode(s, kids));
        }
        return out;
    }

    /** 码为空 = 不受限（V74 后叶子已无此情况，理由见类注释）。 */
    private static boolean permitted(IamMenu m, LoginUser u) {
        return m.getPerm() == null || m.getPerm().isBlank() || u.hasPerm(m.getPerm());
    }

    private List<IamMenu> all() {
        return menuMapper.selectList(new LambdaQueryWrapper<IamMenu>()
                .eq(IamMenu::getVisible, 1).eq(IamMenu::getStatus, "ACTIVE")
                .orderByAsc(IamMenu::getSort));
    }

    private List<MenuNode> build(List<IamMenu> all, String parentNo) {
        return all.stream()
                .filter(m -> Objects.equals(m.getParentNo(), parentNo))
                .map(m -> toNode(m, build(all, m.getMenuNo())))
                .toList();
    }

    private MenuNode toNode(IamMenu m, List<MenuNode> children) {
        return new MenuNode(
                m.getMenuNo(), m.getParentNo(), m.getName(), m.getNameEn(), m.getNameAr(),
                m.getType(), m.getPath(), m.getIcon(), m.getGroupName(), m.getSort(),
                m.getPerm(), m.getPhase(), one(m.getReady()),
                m.getModule(), arr(m.getModules()), arr(m.getMatchPaths()),
                one(m.getPinBottom()), arr(m.getPortalFor()),
                children);
    }

    private static boolean one(Integer v) {
        return v != null && v == 1;
    }

    /**
     * JSON 数组列 → List。**解析不了就当空**而不是抛：
     * 一行脏数据不该让整个菜单接口 500 —— 那时用户看到的是空白外壳，
     * 而真实原因藏在一列 JSON 里。空表的后果只是那一项少一个修饰。
     */
    private List<String> arr(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}

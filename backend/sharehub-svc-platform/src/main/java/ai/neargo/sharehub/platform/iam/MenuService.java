package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 菜单树下发（真源 = {@code iam_menu}，2026-09-24 从 ops-web/lib/nav.ts 迁过来，见 V67）。
 *
 * <h2>为什么这里不按权限过滤</h2>
 * 此前这个方法按 {@code perm} 剪枝。**那会和前端的判权规则分叉**：
 * 前端的 {@code can()} 先把界面码经 {@code UI_PERM_MAP} 翻译成后端码再判
 * （目前 8 条翻译，如 {@code location:overview:read → location:poi:read}），
 * 后端不做这层翻译。于是带这类码的叶子会被后端剪掉、而前端本来是显示的 ——
 * 结果是**菜单少了一项，没有任何报错**。
 *
 * <p>所以下发完整树，可见性**只由前端那一套规则决定**
 * （{@code visibleSections} / {@code visibleLeaves}，它们同时处理
 * module / modules / portalFor / phase / ready，后端表达不了这些组合）。
 *
 * <p><b>这不降低安全性</b>：菜单从来不是安全边界，闸在每个端点的
 * {@code @PreAuthorize} 上 —— 收了权的会话调接口当场 403
 * （{@code PermsRefreshWithoutReloginTest} 验过）。在这里再过滤一次
 * 换不来任何强制力，只会多出一套要和前端保持一致的规则。
 * 树里只有菜单名与路径，没有业务数据。
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

    /** 完整菜单树（不按权限剪枝，理由见类注释）。 */
    public List<MenuNode> tree() {
        List<IamMenu> all = menuMapper.selectList(new LambdaQueryWrapper<IamMenu>()
                .eq(IamMenu::getVisible, 1).eq(IamMenu::getStatus, "ACTIVE")
                .orderByAsc(IamMenu::getSort));
        return build(all, null);
    }

    private List<MenuNode> build(List<IamMenu> all, String parentNo) {
        return all.stream()
                .filter(m -> Objects.equals(m.getParentNo(), parentNo))
                .map(m -> new MenuNode(
                        m.getMenuNo(), m.getParentNo(), m.getName(), m.getNameEn(), m.getNameAr(),
                        m.getType(), m.getPath(), m.getIcon(), m.getGroupName(), m.getSort(),
                        m.getPerm(), m.getPhase(), one(m.getReady()),
                        m.getModule(), arr(m.getModules()), arr(m.getMatchPaths()),
                        one(m.getPinBottom()), arr(m.getPortalFor()),
                        build(all, m.getMenuNo())))
                .toList();
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

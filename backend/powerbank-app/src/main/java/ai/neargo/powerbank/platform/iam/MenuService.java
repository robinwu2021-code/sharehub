package ai.neargo.powerbank.platform.iam;

import ai.neargo.powerbank.auth.LoginUser;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 动态菜单：按登录人权限过滤菜单树、剪除无可见子节点的空目录。 */
@Service
public class MenuService {

    private final MenuMapper menuMapper;

    public MenuService(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /** 菜单节点（下发前端）。 */
    public record MenuNode(String menuNo, String parentNo, String name, String nameAr, String type,
                           String path, String icon, Integer sort, String perm, List<MenuNode> children) {
    }

    /** 当前登录人可见菜单树。 */
    public List<MenuNode> visibleFor(LoginUser u) {
        List<IamMenu> all = menuMapper.selectList(new LambdaQueryWrapper<IamMenu>()
                .eq(IamMenu::getVisible, 1).eq(IamMenu::getStatus, "ACTIVE").orderByAsc(IamMenu::getSort));
        return build(all, null, u);
    }

    /** 递归建树 + 权限过滤 + 剪空目录。 */
    private List<MenuNode> build(List<IamMenu> all, String parentNo, LoginUser u) {
        return all.stream()
                .filter(m -> java.util.Objects.equals(m.getParentNo(), parentNo))
                .filter(m -> permitted(m, u))
                .map(m -> {
                    List<MenuNode> children = build(all, m.getMenuNo(), u);
                    return new MenuNode(m.getMenuNo(), m.getParentNo(), m.getName(), m.getNameAr(),
                            m.getType(), m.getPath(), m.getIcon(), m.getSort(), m.getPerm(), children);
                })
                // 目录无可见子节点 → 剪除
                .filter(n -> !"DIR".equals(n.type()) || !n.children().isEmpty())
                .toList();
    }

    private boolean permitted(IamMenu m, LoginUser u) {
        return m.getPerm() == null || m.getPerm().isBlank() || u.hasPerm(m.getPerm());
    }
}

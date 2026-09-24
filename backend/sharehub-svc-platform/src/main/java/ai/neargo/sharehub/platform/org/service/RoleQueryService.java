package ai.neargo.sharehub.platform.org.service;

import ai.neargo.sharehub.platform.org.dto.OrgDtos.RoleRowVO;

import java.util.List;

/**
 * 角色列表读模型 + 归档（{@code /api/platform/roles**}，运营端员工权限页）。
 *
 * <p>放 org 而不是 iam：{@code memberCount} 要数 {@code iam_employee_role}（org 表），
 * iam 是 L0 底座不该反向依赖 L1 组织；org 依赖 iam 的 mapper 则是正向。
 * 角色的**授权**（权限集读写）仍在 {@code IamAdminController}（/api/platform/iam/**）。
 */
public interface RoleQueryService {

    /** 角色全列表（角色表很小，不分页）。{@code showArchived=false} 时过滤已归档。 */
    List<RoleRowVO> list(boolean showArchived);

    /** 归档角色。内置角色（builtin=1）拒绝：内置角色是权限体系的地基。 */
    /**
     * 新建 / 修改角色。
     *
     * <p><b>不写 dataScope</b>：数据范围有专门的写入口（`saveRoleDataScope`）。
     * 同一个字段开两条写路径，迟早有一条忘了校验 —— 而这个字段决定谁能看到谁的数据。
     *
     * <p><b>内置角色不可改</b>：`RolePerms` 按 `code` 认它们，改掉 code 等于把一整套
     * 权限判定悄悄指向一个不存在的角色（同 {@link #archive} 拒绝内置的理由）。
     */
    RoleRowVO save(String roleNo, RoleRowVO in);

    RoleRowVO archive(String roleNo);

    /** 取消归档。 */
    RoleRowVO unarchive(String roleNo);
}

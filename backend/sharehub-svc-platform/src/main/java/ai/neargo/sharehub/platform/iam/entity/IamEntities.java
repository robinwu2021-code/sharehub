package ai.neargo.sharehub.platform.iam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 运营端动态权限实体集（iam_*）。运行时可配置——见 docs/technical/运营端权限-动态配置实现步骤.md。 */
public final class IamEntities {

    private IamEntities() {
    }

    /** 角色（role_no=code，MVP 简化）。 */
    @Data
    @TableName("iam_role")
    public static class IamRole implements ai.neargo.sharehub.common.crud.Archivable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String roleNo;
        private String tenantId;
        private String code;
        private String name;
        private Integer builtin;      // 1=内置只读
        private String dataScope;     // ALL/REGION/SITE/AGENT/SELF（角色默认，明细在 iam_data_scope）
        private String scopeRefs;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        @Version
        private Long version;
        @TableLogic
        private Integer deleted;
    
        /** 归档时间；null=在用。内置角色不允许归档（由 service 层拦）。 */
        private java.time.LocalDateTime archivedAt;
}

    /** 权限码字典（供后台"分配权限"选择器 + 校验）。 */
    @Data
    @TableName("iam_permission")
    public static class IamPermission {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String code;
        private String module;
        private String name;
        private LocalDateTime createdAt;
    }

    /** 角色→权限码（支持通配）。 */
    @Data
    @TableName("iam_role_perm")
    public static class IamRolePerm {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String roleNo;
        private String permCode;
        private LocalDateTime createdAt;
    }

    /** 数据范围（subject_type=ROLE/EMPLOYEE 并集）。 */
    @Data
    @TableName("iam_data_scope")
    public static class IamDataScope {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String subjectType;   // ROLE / EMPLOYEE
        private String subjectNo;
        private String scopeType;     // ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF
        private String scopeRefs;     // 逗号/JSON 数组
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 菜单树（导航 UI，绑定查看权限码）。 */
    @Data
    @TableName("iam_menu")
    public static class IamMenu {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String menuNo;
        private String parentNo;
        private String name;
        private String nameAr;
        private String type;          // DIR / MENU
        private String path;
        private String icon;
        private Integer sort;
        private String perm;          // 所需权限码（空=公开）
        private Integer visible;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}

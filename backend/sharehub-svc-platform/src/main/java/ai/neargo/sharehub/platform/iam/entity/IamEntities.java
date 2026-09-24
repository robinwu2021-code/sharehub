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
        private String nameEn;
        private String type;          // MENU（一级）/ ITEM（叶子）
        private String path;
        private String icon;
        /** L2 分组标题：同 group 的连续叶子共用一个小标题（仅 ITEM 行）。 */
        private String groupName;
        private Integer sort;
        private String perm;          // 所需权限码（空=跟随所属 section）
        /** 产品分期；phase > 当前期时灰显不可点。 */
        private Integer phase;
        /** 就绪度覆盖：无视 phase 直接解锁（逐叶推进，见 nav.ts NavLeaf.ready）。 */
        private Integer ready;
        /** 权限码模块前缀（canModule 过滤，仅 MENU 行）。 */
        private String module;
        /** 跨模块 section 的全部模块前缀，JSON 数组；任一可见即显示。 */
        private String modules;
        /** 路径归属前缀，JSON 数组；缺省取 path 的 path 部分。 */
        private String matchPaths;
        private Integer pinBottom;
        /** 专属门户角色，JSON 数组；命中者只看得到门户 section。 */
        private String portalFor;
        private Integer visible;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}

package ai.neargo.sharehub.platform.iam.mapper;

import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamPermission;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** iam 域 Mapper（嵌套接口，随 @MapperScan markerInterface 扫描）。 */
public final class IamMappers {

    private IamMappers() {
    }

    public interface RoleMapper extends BaseMapper<IamRole> {
    }

    public interface PermissionMapper extends BaseMapper<IamPermission> {
    }

    public interface RolePermMapper extends BaseMapper<IamRolePerm> {
    }

    public interface DataScopeMapper extends BaseMapper<IamDataScope> {
    }

    public interface MenuMapper extends BaseMapper<IamMenu> {
    }
}

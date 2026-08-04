package ai.neargo.sharehub.platform.org.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Department;
import ai.neargo.sharehub.platform.org.entity.IamDept;

/** 组织架构。CRUD + 两条规则：保存时重算 {@code path}、出参实时聚合 {@code memberCount}。 */
public interface DepartmentService extends CrudService<IamDept, Department> {
}

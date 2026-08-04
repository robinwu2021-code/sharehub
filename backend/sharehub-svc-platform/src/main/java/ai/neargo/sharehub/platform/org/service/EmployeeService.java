package ai.neargo.sharehub.platform.org.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;

/**
 * 员工。读写形态是 CRUD，但保存时要**同步主角色到 {@code iam_employee_role}**
 * （实现里覆写 {@code save}），故仍走通用 CRUD 基类 + 一个覆写。
 */
public interface EmployeeService extends CrudService<IamEmployee, Employee> {
}

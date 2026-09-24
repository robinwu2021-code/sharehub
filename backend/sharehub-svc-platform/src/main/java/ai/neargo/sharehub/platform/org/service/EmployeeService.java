package ai.neargo.sharehub.platform.org.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;

/**
 * 员工。读写形态是 CRUD，但保存时要**同步主角色到 {@code iam_employee_role}**
 * （实现里覆写 {@code save}），故仍走通用 CRUD 基类 + 一个覆写。
 */
public interface EmployeeService extends CrudService<IamEmployee, Employee> {

    /**
     * 保存员工。**收 Req 而不是实体** —— 实体当请求体时客户端传哪些字段就能改哪些字段。
     *
     * <p>{@code req.roleNos()} 为 null 表示**不动角色**：改个电话号码不该把角色清掉。
     */
    ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee save(
            ai.neargo.sharehub.platform.org.dto.OrgDtos.EmployeeSaveReq req);

}

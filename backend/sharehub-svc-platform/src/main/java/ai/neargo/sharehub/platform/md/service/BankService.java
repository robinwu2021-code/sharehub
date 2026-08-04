package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BankEntry;
import ai.neargo.sharehub.platform.md.entity.MdBank;

/** 银行字典。纯配置读写，无业务规则 → 继承通用 CRUD。 */
public interface BankService extends CrudService<MdBank, BankEntry> {
}

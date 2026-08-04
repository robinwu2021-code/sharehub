package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.DictEntry;
import ai.neargo.sharehub.platform.md.entity.DictItem;

/** 参数字典。纯配置读写，无业务规则 → 继承通用 CRUD。 */
public interface DictEntryService extends CrudService<DictItem, DictEntry> {
}

package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BrandEntry;
import ai.neargo.sharehub.platform.md.entity.MdBrand;

/** 品牌字典。纯配置读写 → 继承通用 CRUD（样板见 BankService）。 */
public interface BrandService extends CrudService<MdBrand, BrandEntry> {
}

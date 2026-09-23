package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BankEntry;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BrandEntry;
import ai.neargo.sharehub.platform.md.entity.MdBank;
import ai.neargo.sharehub.platform.md.entity.MdBrand;
import ai.neargo.sharehub.platform.md.service.BankService;
import ai.neargo.sharehub.platform.md.service.BrandService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 系统设置 · 基础字典（[api/README §7.5]）—— **控制器的金标准样板**。
 *
 * <p>控制器职责只有三件：路由、鉴权、调 service。**不写业务逻辑、不碰 mapper**。
 *
 * <p>路径与 {@link PlatformController} 同为 {@code /api/platform} 前缀但**子路径不重叠**
 * （那边是 employees/roles/audit-logs），拆开是因为字典类走通用 CRUD、与组织权限的手写逻辑不同源。
 * 新增控制器前务必先核对已占用路径，重复映射会让 Spring 启动直接失败。
 *
 * <p>写操作形态遵循 [api/README §1.5]：{@code POST /{collection}} 建、
 * {@code POST /{collection}/{no}} 改，**全站无 DELETE**（软删走 status 或专用动作端点）。
 */
@RestController
@RequestMapping("/api/platform")
public class SysDictController {

    private final BankService bankService;
    private final BrandService brandService;

    public SysDictController(BankService bankService, BrandService brandService) {
        this.bankService = bankService;
        this.brandService = brandService;
    }

    // —— 银行管理（菜单叶：系统设置 › 基础字典 › 银行管理）——

    @GetMapping("/banks")
    @PreAuthorize("@perm.can('system:bank:read')")
    public PageResult<BankEntry> banks(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String country,
                                       @RequestParam(required = false) String currency,
                                       @RequestParam(required = false) String status) {
        return bankService.page(page, size, keyword,
                Map.of("country", nz(country), "currency", nz(currency), "status", nz(status)));
    }

    @PostMapping("/banks")
    @PreAuthorize("@perm.can('system:bank:update')")
    public BankEntry createBank(@RequestBody MdBank body) {
        return bankService.save(body);
    }

    @PostMapping("/banks/{bankCode}")
    @PreAuthorize("@perm.can('system:bank:update')")
    public BankEntry updateBank(@PathVariable String bankCode, @RequestBody MdBank body) {
        body.setBankCode(bankCode); // 路径为准，忽略 body 里的键，防越权改他行
        return bankService.save(body);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档Bank。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/banks/{no}/archive")
    @PreAuthorize("@perm.can('system:bank:update')")
    public Object archiveBank(@PathVariable String no) {
        return bankService.archive(no);
    }

    /** 取消归档Bank：清空时间戳，回到默认列表。 */
    @PostMapping("/banks/{no}/unarchive")
    @PreAuthorize("@perm.can('system:bank:update')")
    public Object unarchiveBank(@PathVariable String no) {
        return bankService.unarchive(no);
    }

    // —— 品牌管理（B1；菜单叶：运营管理 › 基础管理 › 品牌管理）——
    //
    // 为什么放在这个控制器：品牌是**字典类**实体（低频维护、纯配置读写），
    // 与银行/问题同族，走同一套通用 CRUD。菜单位置在运营管理是**使用动线**的选择
    // （见菜单收敛方案），与后端按域归类不矛盾 —— 路径仍是 /api/platform。

    @GetMapping("/brands")
    @PreAuthorize("@perm.can('system:brand:read')")
    public PageResult<BrandEntry> brands(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Boolean showArchived) {
        // showArchived 走 filters（基类在 AbstractCrudService#page 里认这个键），不是单独的入参
        return brandService.page(page, size, keyword,
                Map.of("status", nz(status), "showArchived", nz(showArchived == null ? null : showArchived.toString())));
    }

    /*
     * 写操作收 **DTO 而不是实体**（B3 纪律，EntityRequestBodyRatchetTest 守着）。
     * 上面的 banks 收实体是历史包袱、已在台账里豁免；新端点不再增加。
     * 实体当请求体等于把 id/version/deleted/tenantId/archivedAt 一起开放给客户端，
     * 能不能改全靠服务层记得拦 —— 而「记得」不是一种机制。
     */
    @PostMapping("/brands")
    @PreAuthorize("@perm.can('system:brand:update')")
    public BrandEntry createBrand(@RequestBody BrandEntry body) {
        return brandService.save(toEntity(body, body.brandNo()));
    }

    @PostMapping("/brands/{brandNo}")
    @PreAuthorize("@perm.can('system:brand:update')")
    public BrandEntry updateBrand(@PathVariable String brandNo, @RequestBody BrandEntry body) {
        return brandService.save(toEntity(body, brandNo));   // 路径为准，防越权改他行
    }

    /** DTO → 实体。**只搬业务字段**：id/version/deleted/archivedAt 一律由服务端掌握。 */
    private static MdBrand toEntity(BrandEntry in, String brandNo) {
        MdBrand e = new MdBrand();
        e.setBrandNo(brandNo);
        e.setName(in.name());
        e.setNameEn(in.nameEn());
        e.setNameAr(in.nameAr());
        e.setLogoUrl(in.logoUrl());
        e.setSupportPhone(in.supportPhone());
        e.setMarketCode(in.marketCode());
        e.setStatus(in.status());
        return e;
    }

    @PostMapping("/brands/{no}/archive")
    @PreAuthorize("@perm.can('system:brand:update')")
    public Object archiveBrand(@PathVariable String no) {
        return brandService.archive(no);
    }

    @PostMapping("/brands/{no}/unarchive")
    @PreAuthorize("@perm.can('system:brand:update')")
    public Object unarchiveBrand(@PathVariable String no) {
        return brandService.unarchive(no);
    }
}

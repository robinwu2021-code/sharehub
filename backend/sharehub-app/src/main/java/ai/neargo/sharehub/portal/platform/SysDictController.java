package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BankEntry;
import ai.neargo.sharehub.platform.md.entity.MdBank;
import ai.neargo.sharehub.platform.md.service.BankService;
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

    public SysDictController(BankService bankService) {
        this.bankService = bankService;
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
}

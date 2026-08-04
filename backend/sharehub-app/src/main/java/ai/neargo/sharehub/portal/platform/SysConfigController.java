package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.DictEntry;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.ProblemEntry;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.Region;
import ai.neargo.sharehub.platform.md.entity.DictItem;
import ai.neargo.sharehub.platform.md.entity.MdProblem;
import ai.neargo.sharehub.platform.md.entity.MdRegion;
import ai.neargo.sharehub.platform.md.service.DictEntryService;
import ai.neargo.sharehub.platform.md.service.ProblemService;
import ai.neargo.sharehub.platform.md.service.RegionService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.SysParamEntry;
import ai.neargo.sharehub.platform.sys.entity.SysParam;
import ai.neargo.sharehub.platform.sys.service.SysParamService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统设置 · 基础字典（[api/README §7.5]）—— 参数字典 / 地区库 / 问题字典 / 系统参数。
 *
 * <p>与 {@link SysDictController}（银行）同前缀不同子路径：银行先落地做了金标准样板，
 * 剩下四叶集中在这里。**新增端点前先核对已占用路径**，重复映射会让 Spring 启动直接失败。
 *
 * <p>写操作形态遵循 [api/README §1.5]：{@code POST /{collection}} 建、
 * {@code POST /{collection}/{no}} 改，**全站无 DELETE**（软删走 {@code status}/{@code enabled}）。
 * 更新端点一律以**路径键为准**覆盖 body 里的键 —— 防「改 A 提交 B」的越权改。
 */
@RestController
@RequestMapping("/api/platform")
public class SysConfigController {

    private final DictEntryService dictEntries;
    private final RegionService regions;
    private final ProblemService problems;
    private final SysParamService sysParams;

    public SysConfigController(DictEntryService dictEntries, RegionService regions,
                               ProblemService problems, SysParamService sysParams) {
        this.dictEntries = dictEntries;
        this.regions = regions;
        this.problems = problems;
        this.sysParams = sysParams;
    }

    // —— 参数字典（菜单叶：系统设置 › 基础字典 › 参数字典）——

    @GetMapping("/dict-entries")
    @PreAuthorize("@perm.can('system:dict:read')")
    public PageResult<DictEntry> dictEntries(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String groupCode,
                                             @RequestParam(required = false) String enabled) {
        return dictEntries.page(page, size, keyword,
                Map.of("groupCode", nz(groupCode), "enabled", nz(enabled)));
    }

    @PostMapping("/dict-entries")
    @PreAuthorize("@perm.can('system:dict:update')")
    public DictEntry createDictEntry(@RequestBody DictItem body) {
        return dictEntries.save(body);
    }

    @PostMapping("/dict-entries/{dictNo}")
    @PreAuthorize("@perm.can('system:dict:update')")
    public DictEntry updateDictEntry(@PathVariable String dictNo, @RequestBody DictItem body) {
        body.setDictNo(dictNo);
        return dictEntries.save(body);
    }

    // —— 地区库（菜单叶：系统设置 › 基础字典 › 地区库）——

    @GetMapping("/regions")
    @PreAuthorize("@perm.can('system:dict:read')")
    public PageResult<Region> regions(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String parentId,
                                      @RequestParam(required = false) String level) {
        return regions.page(page, size, keyword,
                Map.of("parentId", nz(parentId), "level", nz(level)));
    }

    @PostMapping("/regions")
    @PreAuthorize("@perm.can('system:dict:update')")
    public Region createRegion(@RequestBody MdRegion body) {
        return regions.save(body); // region_id 是自然键，新建必须由调用方给
    }

    @PostMapping("/regions/{regionId}")
    @PreAuthorize("@perm.can('system:dict:update')")
    public Region updateRegion(@PathVariable String regionId, @RequestBody MdRegion body) {
        body.setRegionId(regionId);
        return regions.save(body);
    }

    // —— 问题字典（菜单叶：系统设置 › 基础字典 › 问题管理）——

    @GetMapping("/problems")
    @PreAuthorize("@perm.can('system:problem:read')")
    public PageResult<ProblemEntry> problems(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String suggestedAction,
                                             @RequestParam(required = false) String status) {
        return problems.page(page, size, keyword, Map.of(
                "category", nz(category),
                "suggestedAction", nz(suggestedAction),
                "status", nz(status)));
    }

    @PostMapping("/problems")
    @PreAuthorize("@perm.can('system:problem:update')")
    public ProblemEntry createProblem(@RequestBody MdProblem body) {
        return problems.save(body);
    }

    @PostMapping("/problems/{problemNo}")
    @PreAuthorize("@perm.can('system:problem:update')")
    public ProblemEntry updateProblem(@PathVariable String problemNo, @RequestBody MdProblem body) {
        body.setProblemNo(problemNo);
        return problems.save(body);
    }

    // —— 系统参数（菜单叶：系统设置 › 基础字典 › 系统参数）——

    @GetMapping("/sys-params")
    @PreAuthorize("@perm.can('system:param:read')")
    public PageResult<SysParamEntry> sysParams(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String groupName) {
        return sysParams.page(page, size, keyword, Map.of("groupName", nz(groupName)));
    }

    @PostMapping("/sys-params")
    @PreAuthorize("@perm.can('system:param:update')")
    public SysParamEntry createSysParam(@RequestBody SysParam body) {
        return sysParams.save(body); // param_key 是自然键，新建必须由调用方给
    }

    @PostMapping("/sys-params/{paramKey}")
    @PreAuthorize("@perm.can('system:param:update')")
    public SysParamEntry updateSysParam(@PathVariable String paramKey, @RequestBody SysParam body) {
        body.setParamKey(paramKey);
        return sysParams.save(body);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 归档问题目录项。**不是删除** —— 已归档的问题不再出现在 C 端报障选项里，但历史工单仍可追溯其含义。 */
    @PostMapping("/problems/{no}/archive")
    @PreAuthorize("@perm.can('system:problem:update')")
    public Object archiveProblem(@PathVariable String no) {
        return problems.archive(no);
    }

    /** 取消归档问题目录项。 */
    @PostMapping("/problems/{no}/unarchive")
    @PreAuthorize("@perm.can('system:problem:update')")
    public Object unarchiveProblem(@PathVariable String no) {
        return problems.unarchive(no);
    }

    /** 地区树：一次查全表在内存建树（地区是百量级且极少变动，递归 SQL 换不来收益）。 */
    @GetMapping("/regions/tree")
    @PreAuthorize("@perm.can('system:region:read')")
    public Object regionTree() {
        return regions.tree();
    }
}

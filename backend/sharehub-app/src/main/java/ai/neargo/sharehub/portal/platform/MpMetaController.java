package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.platform.md.dto.MdDtos2.FaqItem;
import ai.neargo.sharehub.platform.md.service.ProblemService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersionCheck;
import ai.neargo.sharehub.platform.sys.service.AppVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * C端元数据 BFF（[api/README §8]）：版本检查与帮助中心 FAQ。
 *
 * <p>两个端点共同的特点是**读运营端维护的同一份配置**，端上不再硬编码：
 * 版本走 {@code sys_app_version}（按平台 + 灰度），FAQ 走 {@code md_problem}（三语 + 建议处置）。
 * 后者同时是 C 端报障分流的下拉来源 —— 报障与帮助中心两处若各存一份，运营改一处就会不一致。
 *
 * <p>语言参数：显式 {@code lang}（{@code zh}/{@code en}/{@code ar}），缺省回落默认语列。
 * 之所以不直接读 {@code Accept-Language}，是因为 C 端允许「系统语言 ≠ 应用内选择的语言」。
 */
@RestController
@RequestMapping("/mp")
public class MpMetaController {

    private final AppVersionService appVersions;
    private final ProblemService problems;

    public MpMetaController(AppVersionService appVersions, ProblemService problems) {
        this.appVersions = appVersions;
        this.problems = problems;
    }

    /**
     * 版本检查：取该平台 {@code RELEASED} 且灰度 &gt; 0 的最高构建号版本。
     * 无在架版本时返回 {@code hasUpdate=false}，端上不弹窗。
     */
    @GetMapping("/app/version")
    public AppVersionCheck version(@RequestParam String platform,
                                   @RequestParam(required = false) String lang) {
        return appVersions.check(platform, lang);
    }

    /** 帮助中心 FAQ / 报障问题列表（只取启用项，按排序号升序）。 */
    @GetMapping("/faq")
    public List<FaqItem> faq(@RequestParam(required = false) String category,
                             @RequestParam(required = false) String lang) {
        return problems.faq(category, lang);
    }
}

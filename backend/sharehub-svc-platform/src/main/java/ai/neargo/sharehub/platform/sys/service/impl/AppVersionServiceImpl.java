package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersion;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersionCheck;
import ai.neargo.sharehub.platform.sys.entity.SysAppVersion;
import ai.neargo.sharehub.platform.sys.mapper.SysAppVersionMapper;
import ai.neargo.sharehub.platform.sys.service.AppVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 应用版本实现。
 *
 * <p>三处业务动作，其余走基类：
 * <ol>
 *   <li>{@link #save} 前补业务键：{@code versionId = PLATFORM-versionNo}（复合自然键）。
 *       {@code versionNo} 在不同平台会重复，单用它当行键必撞；</li>
 *   <li>{@link #rollback} <b>软回滚</b>：{@code status=ROLLBACK} + {@code rolloutPercent=0}，
 *       **保留记录**——事故复盘要能查出当时发了什么、发给了多少人；</li>
 *   <li>{@link #check} C端版本检查：只认 {@code RELEASED} 且灰度 &gt; 0 的最高构建号。</li>
 * </ol>
 */
@Service
public class AppVersionServiceImpl extends AbstractCrudService<SysAppVersion, AppVersion> implements AppVersionService {

    private final SysAppVersionMapper versionMapper;

    public AppVersionServiceImpl(SysAppVersionMapper mapper) {
        super(mapper);
        this.versionMapper = mapper;
    }

    @Override
    protected String keyColumn() {
        return "version_id";
    }

    @Override
    protected String keyOf(SysAppVersion e) {
        return e.getVersionId();
    }

    @Override
    protected void setKey(SysAppVersion e, String no) {
        e.setVersionId(no);
    }

    // keyPrefix() 不覆盖 → 自然键；新建时由 save() 按 平台-版本号 拼出，不走前缀取号

    @Override
    protected String[] keywordColumns() {
        return new String[]{"version_id", "version_no", "release_note"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"platform", "status"};
    }

    /**
     * 状态由**专门入口**迁移（{@link #rollback} 软回滚、发布流程），不许经通用更新接口改。
     *
     * <p>否则「回滚」形同虚设：回滚把 status 置为 ROLLBACK，而更新接口能原样改回 RELEASED——
     * 事故版本可以被一次普通保存重新推给用户。
     * —— 2026-09-23 批量赋值加固（TDD-mass-assignment-hardening）
     */
    @Override
    protected void beforeUpdate(SysAppVersion e, SysAppVersion current) {
        e.setStatus(current.getStatus());
        // 业务键 versionId = platform + "-" + versionNo，建单时拼好。改 platform 会让键不再自洽：
        // 一条 IOS-… 的记录 platform 变成 ANDROID 之后，C 端 check 按 platform 查会把它选出来，
        // 下发的是**另一个平台的安装包地址** —— 症状不是报错，是「安卓用户点更新下到一个 ipa」。
        // 换平台应当新建一条（版本号相同也无妨，键含平台，不会撞）。
        e.setPlatform(current.getPlatform());
    }

    @Override
    protected void beforeCreate(SysAppVersion e) {
        if (e.getStatus() == null) e.setStatus("DRAFT");
        if (e.getRolloutPercent() == null) e.setRolloutPercent(BigDecimal.ZERO);
        if (e.getForceUpdate() == null) e.setForceUpdate(0);
    }

    /**
     * 补齐复合自然键后交给基类 upsert。
     *
     * @throws IllegalArgumentException 既没给 {@code versionId}，又凑不出 {@code platform}+{@code versionNo}
     */
    @Override
    public AppVersion save(SysAppVersion body) {
        if (body.getVersionId() == null || body.getVersionId().isBlank()) {
            if (body.getPlatform() == null || body.getVersionNo() == null) {
                throw new IllegalArgumentException("新建版本必须提供 platform 与 versionNo（业务键为 平台-版本号）");
            }
            body.setVersionId(body.getPlatform() + "-" + body.getVersionNo());
        }
        return super.save(body);
    }

    @Override
    public AppVersion rollback(String versionId) {
        SysAppVersion e = selectByKey(versionId);
        if (e == null) throw new IllegalArgumentException("版本不存在: " + versionId);

        // 软回滚：改状态 + 灰度归零，记录保留（全站零 DELETE）
        e.setStatus("ROLLBACK");
        e.setRolloutPercent(BigDecimal.ZERO);
        versionMapper.updateById(e);
        return toVO(selectByKey(versionId));
    }

    @Override
    public AppVersionCheck check(String platform, String lang) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 必填（IOS/ANDROID/H5）");
        }
        SysAppVersion e = versionMapper.selectOne(new LambdaQueryWrapper<SysAppVersion>()
                .eq(SysAppVersion::getPlatform, platform)
                .eq(SysAppVersion::getStatus, "RELEASED")
                .gt(SysAppVersion::getRolloutPercent, BigDecimal.ZERO)
                .orderByDesc(SysAppVersion::getBuildNo)
                .orderByDesc(SysAppVersion::getId)
                .last("limit 1"));

        if (e == null) {
            return new AppVersionCheck(false, null, null, false, null, null, null);
        }
        return new AppVersionCheck(true, e.getVersionNo(), e.getBuildNo(),
                SysCtx.bool(e.getForceUpdate()), e.getMinSupported(),
                note(lang, e), e.getDownloadUrl());
    }

    /** 三语取一，目标语言为空则回落默认列。 */
    private static String note(String lang, SysAppVersion e) {
        String v = "en".equalsIgnoreCase(lang) ? e.getReleaseNoteEn()
                : ("ar".equalsIgnoreCase(lang) ? e.getReleaseNoteAr() : e.getReleaseNote());
        return (v == null || v.isBlank()) ? e.getReleaseNote() : v;
    }

    @Override
    protected AppVersion toVO(SysAppVersion e) {
        return new AppVersion(e.getVersionId(), e.getVersionNo(), e.getPlatform(), e.getBuildNo(),
                e.getReleaseNote(), e.getReleaseNoteEn(), e.getReleaseNoteAr(),
                SysCtx.bool(e.getForceUpdate()), e.getMinSupported(), e.getRolloutPercent(),
                e.getDownloadUrl(), e.getStatus(), e.getReleasedAt());
    }
}

package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersion;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersionCheck;
import ai.neargo.sharehub.platform.sys.entity.SysAppVersion;

/**
 * C端应用版本。CRUD 之外有两个动作：
 * <ul>
 *   <li>{@link #rollback} —— **软回滚**（{@code status=ROLLBACK} + {@code rolloutPercent=0}，留记录）；</li>
 *   <li>{@link #check} —— C端版本检查（按平台取最新在架版本）。</li>
 * </ul>
 */
public interface AppVersionService extends CrudService<SysAppVersion, AppVersion> {

    /**
     * 软回滚：置 {@code ROLLBACK} 并把灰度归零，**不删记录**（事故复盘要看当时发给了谁）。
     * 已是 {@code ROLLBACK} 的重复调用是幂等的。
     *
     * @throws IllegalArgumentException 版本不存在
     */
    AppVersion rollback(String versionId);

    /**
     * C端版本检查：取该平台 {@code RELEASED} 且灰度 &gt; 0 的最高 {@code buildNo} 版本。
     *
     * @param platform IOS/ANDROID/H5
     * @param lang     更新说明取哪一语（{@code zh}/{@code en}/{@code ar}）
     */
    AppVersionCheck check(String platform, String lang);
}

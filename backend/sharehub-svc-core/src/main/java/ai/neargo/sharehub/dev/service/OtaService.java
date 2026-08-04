package ai.neargo.sharehub.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaReleaseRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaRolloutRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaTaskRow;
import ai.neargo.sharehub.dev.entity.DevOtaRelease;
import ai.neargo.sharehub.dev.entity.DevOtaRollout;

import java.util.List;

/**
 * 固件 OTA 服务：版本（release）+ 投放（rollout）+ 逐设备任务（task）。
 *
 * <p><b>为什么不走 {@code AbstractCrudService}</b>：
 * {@code dev_ota_release} 的 {@code version} 列是固件版本号、乐观锁在 {@code version_col}，
 * 实体无法继承 {@code BaseEntity}（基类泛型上界）；{@code dev_ota_rollout} 同样无
 * {@code version}/{@code deleted} 列，且投放有「灰度→全量→回滚」的状态约束，属聚合根形态。
 */
public interface OtaService {

    // —— 固件版本 ——

    /** 版本分页：keyword 匹配 release_no/version；fwType/vendorCode/status 等值筛选。 */
    PageResult<OtaReleaseRow> releases(Integer page, Integer size, String keyword,
                                       String fwType, String vendorCode, String status);

    /** upsert 版本：{@code releaseNo} 空则取号新建，否则按业务键更新。 */
    OtaReleaseRow saveRelease(DevOtaRelease body);

    // —— 投放 ——

    /** 投放分页：keyword 匹配 rollout_no/fw_version；releaseNo/status/strategy 等值筛选。 */
    PageResult<OtaRolloutRow> rollouts(Integer page, Integer size, String keyword,
                                       String releaseNo, String status, String strategy);

    /** 按业务键单查投放；不存在返回 {@code null}。 */
    OtaRolloutRow rollout(String rolloutNo);

    /** upsert 投放：{@code rolloutNo} 空则取号新建（{@code OTA*}），否则更新（状态迁移受校验）。 */
    OtaRolloutRow saveRollout(DevOtaRollout body);

    /** 某次投放的逐设备任务明细（详情下钻）。 */
    List<OtaTaskRow> tasks(String rolloutNo);
}

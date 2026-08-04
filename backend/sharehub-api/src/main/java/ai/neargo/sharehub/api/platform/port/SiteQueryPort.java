package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.SiteBrief;

import java.util.Collection;
import java.util.List;

/**
 * 场地查询 —— platform 服务暴露给其它服务的**只读**面（ADR-017 §5.2）。
 *
 * <p>调用方只依赖本接口。单体形态下装配 platform 内的本地实现（一次方法调用），
 * 拆分后 classpath 上没有本地实现，自动装配 HTTP 实现 —— **调用方代码一字不改**。
 *
 * <p>只读是刻意的：站点的写入口在 platform 自己的 Controller，
 * 开放写接口等于让别的服务绕过 platform 的业务校验改它的数据。
 */
public interface SiteQueryPort {

    /** 批量取站点摘要。批量而非单个 —— 单个接口会诱导调用方写出 N+1。 */
    List<SiteBrief> briefsByNos(Collection<String> siteNos);
}

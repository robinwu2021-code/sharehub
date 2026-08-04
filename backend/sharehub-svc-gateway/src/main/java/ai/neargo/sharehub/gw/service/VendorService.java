package ai.neargo.sharehub.gw.service;

import ai.neargo.sharehub.gw.dto.GwDtos.VendorProbeResult;
import ai.neargo.sharehub.gw.dto.GwDtos.VendorVO;

import java.util.List;
import java.util.Map;

/**
 * 供应商目录与接入配置（{@code gw_vendor} / {@code gw_vendor_config}）。
 *
 * <p>密钥类字段（appKey/appSecret/verifyKey）**出参一律掩码**（[api §1.6]），
 * 入参传掩码占位视为「未修改」不覆盖 —— 防止「读回来再存回去」把真密钥抹成星号。
 */
public interface VendorService {

    /** 供应商列表（含设备数）。 */
    List<VendorVO> list();

    /** 接入配置 upsert（vendor 不存在则先建目录行）。 */
    VendorVO saveConfig(String vendorCode, Map<String, Object> body);

    /**
     * 连通性探测。有注册驱动 → 按接入方式给结论；无驱动 → {@code ok=false} 提示未接入。
     * 静态阶段不发真实网络请求（驱动是 Demo/占位），但结论字段与真探测一致，接真驱动不改契约。
     */
    VendorProbeResult probe(String vendorCode);
}

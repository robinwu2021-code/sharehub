package ai.neargo.sharehub.api.platform.port;

/**
 * 下载鉴权 SPI：由拥有该业务对象的域实现，文件服务按 {@link #bizType()} 找实现。
 *
 * <p>找不到实现的 bizType 一律拒绝（fail-closed）。实现方用本域已有的**带数据范围**的读查询即可 ——
 * 查不到即无权，与「不存在」同一结果，不泄露存在性。
 */
public interface FileAccessChecker {

    String bizType();

    boolean canRead(String bizNo);
}

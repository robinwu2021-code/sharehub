package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.FileRef;

import java.util.List;

/**
 * 把上传好的文件绑到业务对象上（合同签署件、工单照片……）。
 *
 * <p><b>先传后绑</b>：上传只产生 TEMP 文件；业务保存时在<b>同一事务</b>里调 {@link #bind}。
 * 业务写失败则绑定一起回滚，文件回到 TEMP，由清理任务回收 —— 不留无主对象。
 */
public interface FileBindingPort {

    /**
     * 绑定。逐个校验：状态 TEMP、上传人即当前操作人、用途与 {@code bizType} 相符。
     * 任一不符整体失败（400）；并发下被别人抢先绑定同样失败。
     *
     * @param agentNo 业务对象所属代理（数据范围锚点）；平台直营传 null
     */
    List<FileRef> bind(List<String> fileNos, String bizType, String bizNo, String agentNo);

    /** 业务移除：BOUND → REMOVED（软删；对象按保留期清理）。不属于该业务对象的文件拒绝。 */
    void remove(String fileNo, String bizType, String bizNo);

    List<FileRef> listBound(String bizType, String bizNo);

    /** 批量取引用（按号，不校验归属；给已经鉴过权的业务读取用）。 */
    List<FileRef> refs(List<String> fileNos);
}

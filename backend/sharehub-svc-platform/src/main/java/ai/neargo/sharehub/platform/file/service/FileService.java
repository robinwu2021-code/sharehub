package ai.neargo.sharehub.platform.file.service;

import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.api.platform.port.FileBindingPort;
import ai.neargo.sharehub.platform.file.FileCategory;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

/** 文件：上传、限时地址、个人数据流式读、清理。绑定能力经 {@link FileBindingPort} 对外。 */
public interface FileService extends FileBindingPort {

    @FunctionalInterface
    interface Source {
        InputStream open() throws IOException;
    }

    record Upload(FileCategory category, String originalName, long declaredSize, Source content,
                  String uploaderRealm, String uploaderNo, String uploaderAgentNo) {}

    record SignedUrl(String url, LocalDateTime expiresAt) {}

    record Stream(InputStream in, String contentType, long size, String fileName) {}

    FileRef upload(Upload cmd);

    /** 非个人数据：鉴权后给限时地址。个人数据拒绝（只能 {@link #openStream}）。 */
    SignedUrl signedUrl(String fileNo);

    /** 鉴权后流式读（个人数据唯一出口；其余文件也可用）。 */
    Stream openStream(String fileNo);

    /** 本地存储的限时地址：验签后流式读（不再鉴权 —— 签名即授权）。 */
    Stream openSigned(String fileNo, long exp, String sig);

    /** 清理：TEMP 超 24h、REMOVED 超 90 天的对象删除，元数据置 PURGED。返回处理条数。 */
    int purgeExpired(LocalDateTime now);
}

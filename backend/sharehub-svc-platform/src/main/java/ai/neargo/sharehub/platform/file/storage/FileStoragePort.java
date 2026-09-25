package ai.neargo.sharehub.platform.file.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 对象存储端口（模块内）。生产 COS，测试与离线开发用本地目录 —— 两者走<b>同一条</b>「302 到限时地址」路径。
 * 由 {@code sharehub.storage.type=cos|local} 选择实现。
 */
public interface FileStoragePort {

    /** 存储类型标识，落 sys_file.storage。 */
    String type();

    /** 桶名：按用途的桶类型取配置。 */
    String bucketOf(String bucketKind);

    /** 写入。从本地临时文件上传（带确定的长度），不把整个文件读进内存。 */
    void put(String bucket, String key, Path file, String contentType);

    /** 私有对象的限时读取地址。 */
    String presignGet(StoredObject obj, Duration ttl);

    /** 流式读（个人数据转发、清理前核对）。 */
    InputStream open(String bucket, String key);

    void remove(String bucket, String key);
}

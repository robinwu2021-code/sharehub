package ai.neargo.sharehub.platform.file;

/** 文件状态：TEMP 已上传未绑定 · BOUND 已绑定 · REMOVED 业务已移除（软删）· PURGED 对象已清理。 */
public enum FileStatus {
    TEMP, BOUND, REMOVED, PURGED
}

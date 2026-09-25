package ai.neargo.sharehub.platform.file.storage;

/** 存储位置 + 下载时需要的元信息。 */
public record StoredObject(String fileNo, String bucket, String key, String contentType, String downloadName) {
}

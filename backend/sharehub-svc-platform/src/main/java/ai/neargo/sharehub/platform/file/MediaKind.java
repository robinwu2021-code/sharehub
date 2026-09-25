package ai.neargo.sharehub.platform.file;

/** 允许的媒体类型。按**文件头**判定（{@link ai.neargo.sharehub.platform.file.storage.MediaSniffer}），不信扩展名与客户端声明。 */
public enum MediaKind {
    PDF("application/pdf", "pdf", false),
    JPEG("image/jpeg", "jpg", true),
    PNG("image/png", "png", true),
    WEBP("image/webp", "webp", true);

    public final String contentType;
    public final String ext;
    public final boolean image;

    MediaKind(String contentType, String ext, boolean image) {
        this.contentType = contentType;
        this.ext = ext;
        this.image = image;
    }
}

package ai.neargo.sharehub.platform.file;

import java.util.Arrays;
import java.util.Set;

import static ai.neargo.sharehub.platform.file.MediaKind.*;

/**
 * 用途即策略：谁能传、传什么、多大、怎么处理、放哪个桶、绑到什么对象。**不接受「通用上传」。**
 *
 * <p>上传权限复用各业务已有的写权限码，不新增码（方案-文件上传与COS存储 §三）。
 * {@code uploadPerm == null} 的用途不走运营端上传入口（入驻资质走入驻端点、C 端截图走 /mp）。
 */
public enum FileCategory {

    CONTRACT_SCAN("location:contract:update", Set.of(PDF, JPEG, PNG), 20, false, Bucket.PRIVATE, false, Set.of("CONTRACT")),
    WO_PHOTO("workorder:wo:handle", Set.of(JPEG, PNG), 10, false, Bucket.PRIVATE, false, Set.of("WORK_ORDER")),
    SURVEY_PHOTO("location:poi:update", Set.of(JPEG, PNG), 10, false, Bucket.PRIVATE, false, Set.of("LOCATION", "SITE_SURVEY")),
    LEAD_PHOTO("location:lead:update", Set.of(JPEG, PNG), 10, false, Bucket.PRIVATE, false, Set.of("LEAD")),
    AGENT_QUALIFICATION(null, Set.of(PDF, JPEG, PNG), 10, false, Bucket.PII, true, Set.of("AGENT_APPLY")),
    CS_EVIDENCE(null, Set.of(JPEG, PNG), 5, true, Bucket.PII, true, Set.of("CS_TICKET")),
    PUBLIC_IMAGE("marketing:notice:update", Set.of(JPEG, PNG, WEBP), 5, true, Bucket.PUBLIC, false, Set.of("BRAND", "NOTICE", "AD"));

    public enum Bucket { PRIVATE, PII, PUBLIC }

    public final String uploadPerm;
    public final Set<MediaKind> allowed;
    public final long maxBytes;
    /** 去掉全部元数据（EXIF 定位等）后再存。C 端截图、公开图片必须；运维照片保留（到过现场的证据）。 */
    public final boolean stripMetadata;
    public final Bucket bucket;
    public final boolean pii;
    public final Set<String> bizTypes;

    FileCategory(String uploadPerm, Set<MediaKind> allowed, int maxMb, boolean stripMetadata,
                 Bucket bucket, boolean pii, Set<String> bizTypes) {
        this.uploadPerm = uploadPerm;
        this.allowed = allowed;
        this.maxBytes = maxMb * 1024L * 1024L;
        this.stripMetadata = stripMetadata;
        this.bucket = bucket;
        this.pii = pii;
        this.bizTypes = bizTypes;
    }

    public static FileCategory of(String v) {
        if (v == null || v.isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "category");
        return Arrays.stream(values()).filter(c -> c.name().equalsIgnoreCase(v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文件用途非法: " + v));
    }
}

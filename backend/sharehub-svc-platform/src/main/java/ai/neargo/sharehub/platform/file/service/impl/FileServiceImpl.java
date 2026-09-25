package ai.neargo.sharehub.platform.file.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.api.platform.port.FileAccessChecker;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.platform.file.FileCategory;
import ai.neargo.sharehub.platform.file.FileStatus;
import ai.neargo.sharehub.platform.file.MediaKind;
import ai.neargo.sharehub.platform.file.entity.SysFile;
import ai.neargo.sharehub.platform.file.mapper.SysFileMapper;
import ai.neargo.sharehub.platform.file.service.FileService;
import ai.neargo.sharehub.platform.file.storage.FileStoragePort;
import ai.neargo.sharehub.platform.file.storage.LocalFileStorage;
import ai.neargo.sharehub.platform.file.storage.MediaSniffer;
import ai.neargo.sharehub.platform.file.storage.StoredObject;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文件服务（方案-文件上传与COS存储；TDD-运营核心流程/01）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li>对象键由服务端生成（{@code {用途}/{yyyy}/{MM}/{fileNo}.{ext}}），不含原始名与业务号 ——
 *       个人信息与奇怪字符不进对象键，客户端也无法指定键去覆盖别人的文件；</li>
 *   <li>类型按文件头判定，大小按实际字节数判定 —— 客户端声明的都只作参考；</li>
 *   <li>下载一律经服务端按<b>归属对象</b>鉴权；{@code fileNo} 不可预测但<b>不作为鉴权依据</b>。</li>
 * </ol>
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy/MM");

    private final SysFileMapper files;
    private final FileStoragePort storage;
    private final Map<String, FileAccessChecker> checkers;
    private final Duration presignTtl;

    public FileServiceImpl(SysFileMapper files, FileStoragePort storage, List<FileAccessChecker> checkers,
                           @Value("${sharehub.storage.presign-ttl:PT5M}") Duration presignTtl) {
        this.files = files;
        this.storage = storage;
        this.checkers = checkers.stream().collect(Collectors.toMap(FileAccessChecker::bizType, Function.identity()));
        this.presignTtl = presignTtl;
    }

    // —— 上传 ——

    @Override
    public FileRef upload(Upload cmd) {
        FileCategory c = cmd.category();
        if (cmd.declaredSize() > c.maxBytes) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.too_large", c.maxBytes >> 20);
        Path tmp = null, processed = null;
        try {
            tmp = Files.createTempFile("sharehub-up-", ".bin");
            byte[] head = new byte[16];
            int headLen;
            try (InputStream in = cmd.content().open(); OutputStream out = Files.newOutputStream(tmp)) {
                headLen = in.readNBytes(head, 0, head.length);
                out.write(head, 0, headLen);
                long total = headLen;
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > c.maxBytes) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.too_large", c.maxBytes >> 20);
                    out.write(buf, 0, n);
                }
            }
            MediaKind kind = MediaSniffer.sniff(head, headLen);
            if (kind == null || !c.allowed.contains(kind) || !MediaSniffer.extCompatible(cmd.originalName(), kind)) {
                throw ai.neargo.sharehub.common.BizException.badRequest("error.file.type_mismatch",
                        c.allowed.stream().map(k -> k.ext.toUpperCase()).sorted().collect(Collectors.joining(" / ")));
            }
            Integer width = null, height = null;
            processed = tmp;
            if (kind.image && kind != MediaKind.WEBP) {
                BufferedImage img = ImageIO.read(tmp.toFile());
                if (img == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.image_unreadable");
                width = img.getWidth();
                height = img.getHeight();
                if (c.stripMetadata) {
                    // 重编码一次即去掉全部元数据（EXIF 定位等）：C 端截图与公开图片不该带拍摄地点
                    processed = Files.createTempFile("sharehub-strip-", "." + kind.ext);
                    ImageIO.write(img, kind == MediaKind.PNG ? "png" : "jpg", processed.toFile());
                }
            }
            long size = Files.size(processed);
            String sha = sha256(processed);

            SysFile dup = files.selectOne(new LambdaQueryWrapper<SysFile>()
                    .eq(SysFile::getUploaderNo, cmd.uploaderNo()).eq(SysFile::getCategory, c.name())
                    .eq(SysFile::getSha256, sha).eq(SysFile::getStatus, FileStatus.TEMP.name())
                    .ge(SysFile::getCreatedAt, LocalDateTime.now().minusHours(24)).last("limit 1"));
            if (dup != null) return toRef(dup);   // 同人同用途同内容 24h 内重复上传：直接复用

            String fileNo = IdGenerator.next(BizKey.FILE);
            String key = c.name().toLowerCase() + "/" + LocalDateTime.now(ZoneId.of("UTC")).format(YM) + "/" + fileNo + "." + kind.ext;
            String bucket = storage.bucketOf(c.bucket.name());
            storage.put(bucket, key, processed, kind.contentType);   // 先写对象、后写元数据；元数据失败留下的孤儿由清理对账

            SysFile f = new SysFile();
            f.setFileNo(fileNo);
            f.setTenantId("MAIN");
            f.setCategory(c.name());
            f.setStatus(FileStatus.TEMP.name());
            f.setOriginalName(safeName(cmd.originalName()));
            f.setContentType(kind.contentType);
            f.setSizeBytes(size);
            f.setSha256(sha);
            f.setStorage(storage.type());
            f.setBucket(bucket);
            f.setObjectKey(key);
            f.setPii(c.pii);
            f.setImageWidth(width);
            f.setImageHeight(height);
            f.setUploaderRealm(cmd.uploaderRealm());
            f.setUploaderNo(cmd.uploaderNo());
            f.setAgentNo(cmd.uploaderAgentNo());
            files.insert(f);
            log.info("文件已上传 fileNo={} category={} size={} uploader={}", fileNo, c, size, cmd.uploaderNo());
            return toRef(f);
        } catch (IOException e) {
            throw new UncheckedIOException("文件处理失败", e);
        } finally {
            deleteQuietly(tmp);
            if (processed != null && !processed.equals(tmp)) deleteQuietly(processed);
        }
    }

    // —— 绑定（FileBindingPort）——

    @Override
    @Transactional
    public List<FileRef> bind(List<String> fileNos, String bizType, String bizNo, String agentNo) {
        if (fileNos == null || fileNos.isEmpty()) return listBound(bizType, bizNo);
        String me = SecurityUtils.userNo();
        for (String no : fileNos.stream().distinct().toList()) {
            SysFile f = byNo(no);
            // 「不是你的」与「不存在」同一文案：不泄露别人上传过什么
            if (f == null || !f.getUploaderNo().equals(me)) throw BizException.notFound(no);
            if (!FileStatus.TEMP.name().equals(f.getStatus())) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.unusable", no);
            if (!FileCategory.of(f.getCategory()).bizTypes.contains(bizType)) {
                throw ai.neargo.sharehub.common.BizException.badRequest("error.file.category_mismatch", no);
            }
            int n = files.update(null, new LambdaUpdateWrapper<SysFile>()
                    .eq(SysFile::getFileNo, no).eq(SysFile::getStatus, FileStatus.TEMP.name())
                    .set(SysFile::getStatus, FileStatus.BOUND.name())
                    .set(SysFile::getBizType, bizType).set(SysFile::getBizNo, bizNo)
                    .set(SysFile::getBoundAt, LocalDateTime.now())
                    .set(agentNo != null, SysFile::getAgentNo, agentNo));
            if (n == 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.unusable", no);   // 并发下被抢先绑定
        }
        return listBound(bizType, bizNo);
    }

    @Override
    @Transactional
    public void remove(String fileNo, String bizType, String bizNo) {
        int n = files.update(null, new LambdaUpdateWrapper<SysFile>()
                .eq(SysFile::getFileNo, fileNo).eq(SysFile::getBizType, bizType).eq(SysFile::getBizNo, bizNo)
                .eq(SysFile::getStatus, FileStatus.BOUND.name())
                .set(SysFile::getStatus, FileStatus.REMOVED.name()).set(SysFile::getRemovedAt, LocalDateTime.now()));
        if (n == 0) throw BizException.notFound(fileNo);
    }

    @Override
    public List<FileRef> listBound(String bizType, String bizNo) {
        return DataScopeContext.executeWithoutScope(() -> files.selectList(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getBizType, bizType).eq(SysFile::getBizNo, bizNo)
                .eq(SysFile::getStatus, FileStatus.BOUND.name()).orderByAsc(SysFile::getId)))
                .stream().map(FileServiceImpl::toRef).toList();
    }

    @Override
    public List<FileRef> refs(List<String> fileNos) {
        if (fileNos == null || fileNos.isEmpty()) return List.of();
        return DataScopeContext.executeWithoutScope(() -> files.selectList(new LambdaQueryWrapper<SysFile>()
                .in(SysFile::getFileNo, fileNos))).stream().map(FileServiceImpl::toRef).toList();
    }

    // —— 读取 ——

    @Override
    public SignedUrl signedUrl(String fileNo) {
        SysFile f = requireReadable(fileNo);
        if (Boolean.TRUE.equals(f.getPii())) throw ai.neargo.sharehub.common.BizException.badRequest("error.file.pii_no_link");
        String url = storage.presignGet(obj(f), presignTtl);
        return new SignedUrl(url, LocalDateTime.now().plus(presignTtl));
    }

    @Override
    public Stream openStream(String fileNo) {
        SysFile f = requireReadable(fileNo);
        return new Stream(storage.open(f.getBucket(), f.getObjectKey()), f.getContentType(), f.getSizeBytes(), f.getOriginalName());
    }

    @Override
    public Stream openSigned(String fileNo, long exp, String sig) {
        if (!(storage instanceof LocalFileStorage local) || !local.verify(fileNo, exp, sig)) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.file.link_invalid");
        }
        SysFile f = DataScopeContext.executeWithoutScope(() -> byNo(fileNo));
        if (f == null || Boolean.TRUE.equals(f.getPii()) || !readableStatus(f)) throw ai.neargo.sharehub.common.BizException.notFound(fileNo);
        return new Stream(storage.open(f.getBucket(), f.getObjectKey()), f.getContentType(), f.getSizeBytes(), f.getOriginalName());
    }

    /** TEMP 只有上传人本人可看；BOUND 交给归属对象所在域判定（找不到判定者一律拒）。 */
    private SysFile requireReadable(String fileNo) {
        SysFile f = DataScopeContext.executeWithoutScope(() -> byNo(fileNo));
        if (f == null || !readableStatus(f)) throw ai.neargo.sharehub.common.BizException.notFound(fileNo);
        if (FileStatus.TEMP.name().equals(f.getStatus())) {
            if (!f.getUploaderNo().equals(SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null))) {
                throw ai.neargo.sharehub.common.BizException.notFound(fileNo);
            }
            return f;
        }
        FileAccessChecker checker = checkers.get(f.getBizType());
        if (checker == null || !checker.canRead(f.getBizNo())) throw ai.neargo.sharehub.common.BizException.notFound(f.getFileNo());
        return f;
    }

    private static boolean readableStatus(SysFile f) {
        return FileStatus.TEMP.name().equals(f.getStatus()) || FileStatus.BOUND.name().equals(f.getStatus());
    }

    // —— 清理 ——

    @Override
    public int purgeExpired(LocalDateTime now) {
        List<SysFile> due = DataScopeContext.executeWithoutScope(() -> files.selectList(new LambdaQueryWrapper<SysFile>()
                .and(w -> w.eq(SysFile::getStatus, FileStatus.TEMP.name()).lt(SysFile::getCreatedAt, now.minusHours(24))
                        .or().eq(SysFile::getStatus, FileStatus.REMOVED.name()).lt(SysFile::getRemovedAt, now.minusDays(90)))
                .last("limit 500")));
        int n = 0;
        for (SysFile f : due) {
            try {
                storage.remove(f.getBucket(), f.getObjectKey());
                files.update(null, new LambdaUpdateWrapper<SysFile>().eq(SysFile::getId, f.getId())
                        .eq(SysFile::getStatus, f.getStatus()).set(SysFile::getStatus, FileStatus.PURGED.name()));
                n++;
            } catch (UncheckedIOException e) {
                // 单个对象删失败不影响其他；下一轮会再捞到它
                log.warn("文件清理失败 fileNo={} key={}：检查存储可达性，下一轮自动重试", f.getFileNo(), f.getObjectKey(), e);
            }
        }
        return n;
    }

    // —— 工具 ——

    private SysFile byNo(String no) {
        return files.selectOne(new LambdaQueryWrapper<SysFile>().eq(SysFile::getFileNo, no).last("limit 1"));
    }

    private static StoredObject obj(SysFile f) {
        return new StoredObject(f.getFileNo(), f.getBucket(), f.getObjectKey(), f.getContentType(), f.getOriginalName());
    }

    static FileRef toRef(SysFile f) {
        boolean previewable = f.getContentType() != null && (f.getContentType().startsWith("image/") || f.getContentType().equals("application/pdf"));
        return new FileRef(f.getFileNo(), f.getCategory(), f.getStatus(), f.getOriginalName(), f.getContentType(),
                f.getSizeBytes() == null ? 0 : f.getSizeBytes(), previewable, f.getImageWidth(), f.getImageHeight(), f.getCreatedAt());
    }

    private static String safeName(String n) {
        if (n == null || n.isBlank()) return "file";
        String s = n.replaceAll("[\\\\/\\r\\n\\t\\x00-\\x1F]", "_");
        return s.length() > 200 ? s.substring(s.length() - 200) : s;
    }

    private static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.ProviderException("SHA-256 不可用", e);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响业务；操作系统会回收临时目录
        }
    }
}

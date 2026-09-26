package ai.neargo.sharehub.platform.cred.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.platform.cred.CredentialStatus;
import ai.neargo.sharehub.platform.cred.entity.CredCredential;
import ai.neargo.sharehub.platform.cred.mapper.CredCredentialMapper;
import ai.neargo.sharehub.platform.cred.service.CredentialService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * 凭据实现。
 *
 * <h2>两个不肯让步的地方</h2>
 * <ol>
 *   <li><b>锁定期内口令正确也拒</b>：只要放行，爆破者试到正确口令那一刻锁就白上了；</li>
 *   <li><b>一次性口令只回一次</b>：能再查出来的初始口令等于没有初始口令。
 *       它不落日志 —— 日志的读者远多于接口的调用者。</li>
 * </ol>
 */
@Service
public class CredentialServiceImpl implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialServiceImpl.class);

    /** 连续失败多少次上锁。太松等于没锁，太紧会被人拿来锁别人的账号。 */
    private static final int MAX_FAILED = 5;
    /** 锁多久。短到不至于变成拒绝服务，长到让爆破无利可图。 */
    private static final int LOCK_MINUTES = 15;
    /** 一次性口令长度。 */
    private static final int TEMP_LEN = 12;
    /** 去掉了 0/O/1/l/I —— 一次性口令要靠人转述，形近字符会变成「登不进去」的投诉。 */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredCredentialMapper creds;
    private final PasswordEncoder encoder;

    public CredentialServiceImpl(CredCredentialMapper creds, PasswordEncoder encoder) {
        this.creds = creds;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public Check verify(String realm, String subjectNo, String rawPassword) {
        CredCredential c = find(realm, subjectNo);
        if (c == null) return null;   // 没有凭据 → 调用方回落到共享口令闸

        if (!CredentialStatus.ACTIVE.is(c.getStatus())) {
            return new Check(false, "credential_disabled", false);
        }
        LocalDateTime now = LocalDateTime.now();
        if (c.getLockedUntil() != null && c.getLockedUntil().isAfter(now)) {
            // **口令对不对都不看**：这一步先于校验，正是锁的意义所在
            log.warn("凭据处于锁定期 realm={} subject={} until={}", realm, subjectNo, c.getLockedUntil());
            return new Check(false, "locked", false);
        }
        if (rawPassword == null || !encoder.matches(rawPassword, c.getHash())) {
            int failed = (c.getFailedCount() == null ? 0 : c.getFailedCount()) + 1;
            LambdaUpdateWrapper<CredCredential> u = new LambdaUpdateWrapper<CredCredential>()
                    .eq(CredCredential::getId, c.getId()).set(CredCredential::getFailedCount, failed);
            if (failed >= MAX_FAILED) {
                u.set(CredCredential::getLockedUntil, now.plusMinutes(LOCK_MINUTES));
                log.warn("凭据连续失败 {} 次，锁定 {} 分钟 realm={} subject={}", failed, LOCK_MINUTES, realm, subjectNo);
            }
            creds.update(null, u);
            return new Check(false, "bad_credentials", false);
        }
        // 成功：清零失败计数与锁
        creds.update(null, new LambdaUpdateWrapper<CredCredential>().eq(CredCredential::getId, c.getId())
                .set(CredCredential::getFailedCount, 0).set(CredCredential::getLockedUntil, null));
        return new Check(true, null, Integer.valueOf(1).equals(c.getMustChange()));
    }

    @Override
    @Transactional
    public String resetPassword(String realm, String subjectNo) {
        String temp = randomPassword();
        CredCredential c = find(realm, subjectNo);
        LocalDateTime now = LocalDateTime.now();
        if (c == null) {
            c = new CredCredential();
            c.setCredNo(BizKey.CREDENTIAL + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
            c.setTenantId("MAIN");
            c.setRealm(realm);
            c.setSubjectNo(subjectNo);
            c.setAlgo("BCRYPT");
            c.setHash(encoder.encode(temp));
            c.setMustChange(1);
            c.setFailedCount(0);
            c.setStatus(CredentialStatus.ACTIVE.name());
            c.setPwdChangedAt(now);
            creds.insert(c);
        } else {
            creds.update(null, new LambdaUpdateWrapper<CredCredential>().eq(CredCredential::getId, c.getId())
                    .set(CredCredential::getHash, encoder.encode(temp))
                    .set(CredCredential::getMustChange, 1)
                    .set(CredCredential::getFailedCount, 0)
                    .set(CredCredential::getLockedUntil, null)
                    .set(CredCredential::getStatus, CredentialStatus.ACTIVE.name())
                    .set(CredCredential::getPwdChangedAt, now));
        }
        // 只记"给谁重置了"，**不记口令本身**
        log.info("重置登录口令 realm={} subject={}", realm, subjectNo);
        return temp;
    }

    @Override
    @Transactional
    public void changePassword(String realm, String subjectNo, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.trim().length() < 8) {
            throw BizException.badRequest("error.cred.password_too_short");
        }
        CredCredential c = find(realm, subjectNo);
        if (c == null) throw BizException.badRequest("error.cred.not_found");
        if (oldPassword == null || !encoder.matches(oldPassword, c.getHash())) {
            throw BizException.badRequest("error.cred.old_password_wrong");
        }
        if (encoder.matches(newPassword, c.getHash())) {
            throw BizException.badRequest("error.cred.password_unchanged");
        }
        creds.update(null, new LambdaUpdateWrapper<CredCredential>().eq(CredCredential::getId, c.getId())
                .set(CredCredential::getHash, encoder.encode(newPassword.trim()))
                .set(CredCredential::getMustChange, 0)
                .set(CredCredential::getFailedCount, 0)
                .set(CredCredential::getLockedUntil, null)
                .set(CredCredential::getPwdChangedAt, LocalDateTime.now()));
        log.info("已改密 realm={} subject={}", realm, subjectNo);
    }

    private CredCredential find(String realm, String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank()) return null;
        return creds.selectOne(new LambdaQueryWrapper<CredCredential>()
                .eq(CredCredential::getRealm, realm).eq(CredCredential::getSubjectNo, subjectNo.trim()).last("limit 1"));
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(TEMP_LEN);
        for (int i = 0; i < TEMP_LEN; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}

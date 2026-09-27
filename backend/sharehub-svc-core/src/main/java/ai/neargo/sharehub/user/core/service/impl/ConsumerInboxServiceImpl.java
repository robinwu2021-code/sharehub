package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.BizNoSequence;
import ai.neargo.sharehub.user.core.entity.UsrMessage;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrMessageMapper;
import ai.neargo.sharehub.user.core.service.ConsumerInboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 站内信写入实现。 */
@Service
public class ConsumerInboxServiceImpl implements ConsumerInboxService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConsumerInboxServiceImpl.class);

    private static final String TENANT_MAIN = "MAIN";

    private final UsrMessageMapper mapper;

    /** 取号走 {@link BizNoSequence}（原生 SQL，连软删的号一起算），不用 mapper —— 见其类注释。 */
    private final JdbcTemplate jdbc;

    public ConsumerInboxServiceImpl(UsrMessageMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    public String post(String cUserNo, String type, Text title, Text body) {
        if (cUserNo == null || cUserNo.isBlank()) {
            // 无主的消息谁也看不到，只会让「消息总数」虚高。不静默丢，留一条能查的 WARN
            log.warn("站内信没有收件人，已丢弃 type={}", type);
            return null;
        }
        UsrMessage e = new UsrMessage();
        e.setMessageNo(BizNoSequence.next(jdbc, "usr_message", "message_no", BizKey.MESSAGE, 8));
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setType(type);
        e.setTitle(title.zh());
        e.setTitleEn(title.en());
        e.setTitleAr(title.ar());
        e.setBody(body.zh());
        e.setBodyEn(body.en());
        e.setBodyAr(body.ar());
        e.setIsRead(0);   // 新消息未读 —— 置 1 会让未读角标永远不亮
        mapper.insert(e);
        return e.getMessageNo();
    }
}

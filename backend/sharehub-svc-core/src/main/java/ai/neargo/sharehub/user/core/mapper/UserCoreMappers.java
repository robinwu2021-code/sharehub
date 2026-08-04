package ai.neargo.sharehub.user.core.mapper;

import ai.neargo.sharehub.user.core.entity.UsrBlacklist;
import ai.neargo.sharehub.user.core.entity.UsrConsent;
import ai.neargo.sharehub.user.core.entity.UsrCredit;
import ai.neargo.sharehub.user.core.entity.UsrFavorite;
import ai.neargo.sharehub.user.core.entity.UsrFreeWhitelist;
import ai.neargo.sharehub.user.core.entity.UsrInvoice;
import ai.neargo.sharehub.user.core.entity.UsrInvoiceTitle;
import ai.neargo.sharehub.user.core.entity.UsrLogoff;
import ai.neargo.sharehub.user.core.entity.UsrMessage;
import ai.neargo.sharehub.user.core.entity.UsrNotifyPref;
import ai.neargo.sharehub.user.core.entity.UsrPushToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * user/core 子域 Mapper（风控 + C 端专属）。嵌套接口形态与 {@code user/mapper/UserMappers} 一致，
 * 随 {@code MybatisPlusConfig} 的 {@code markerInterface = BaseMapper} 扫描。
 */
public final class UserCoreMappers {

    private UserCoreMappers() {
    }

    public interface UsrCreditMapper extends BaseMapper<UsrCredit> {
    }

    public interface UsrBlacklistMapper extends BaseMapper<UsrBlacklist> {
    }

    public interface UsrFreeWhitelistMapper extends BaseMapper<UsrFreeWhitelist> {
    }

    public interface UsrFavoriteMapper extends BaseMapper<UsrFavorite> {

        /**
         * 复活一条被软删的收藏（重新收藏同一门店）。
         *
         * <p>必须手写 SQL：{@code @TableLogic} 会给所有 MP 生成的语句自动追加 {@code deleted = 0}，
         * 因此 {@code selectOne}/{@code update} 都<b>看不见</b>已软删的行；而 UK(c_user_no, site_no)
         * 不含 {@code deleted}，直接 INSERT 会撞唯一键。
         *
         * @return 命中行数；0 表示从未收藏过，调用方改走 INSERT
         */
        @Update("UPDATE usr_favorite SET deleted = 0, updated_at = CURRENT_TIMESTAMP(3) "
                + "WHERE c_user_no = #{cUserNo} AND site_no = #{siteNo} AND deleted = 1")
        int revive(@Param("cUserNo") String cUserNo, @Param("siteNo") String siteNo);
    }

    public interface UsrMessageMapper extends BaseMapper<UsrMessage> {
    }

    public interface UsrPushTokenMapper extends BaseMapper<UsrPushToken> {
    }

    public interface UsrNotifyPrefMapper extends BaseMapper<UsrNotifyPref> {
    }

    public interface UsrInvoiceTitleMapper extends BaseMapper<UsrInvoiceTitle> {
    }

    public interface UsrInvoiceMapper extends BaseMapper<UsrInvoice> {
    }

    public interface UsrLogoffMapper extends BaseMapper<UsrLogoff> {
    }

    public interface UsrConsentMapper extends BaseMapper<UsrConsent> {
    }
}

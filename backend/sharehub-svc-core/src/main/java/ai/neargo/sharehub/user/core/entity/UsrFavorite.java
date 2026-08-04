package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收藏门店（usr_favorite，[db-design §6.6]）。UK(c_user_no, site_no) 防重复收藏。
 * 取消收藏走软删（{@code @TableLogic}），不物理删 —— 便于「常去门店」的行为分析。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_favorite")
public class UsrFavorite extends BaseEntity {

    private String cUserNo;

    private String siteNo;
}

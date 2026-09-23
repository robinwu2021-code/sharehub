package ai.neargo.sharehub.loc.ext.mapper;

import ai.neargo.sharehub.loc.ext.entity.LocSiteAgent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LocSiteAgentMapper extends BaseMapper<LocSiteAgent> {

    /**
     * 找一条**已撤销**的同名责任行（绕过逻辑删除过滤）。
     *
     * <p>为什么需要它：{@code deleted} 是 {@code @TableLogic}，撤销只是置标志；
     * 而唯一键 {@code uk_site_agent_role (site_no, agent_no, role)} <b>不含 deleted</b>，
     * 那一行仍然占着这个键。于是「撤销一条责任，之后又想加回来」会撞唯一键 ——
     * 而常规查询看不见它，友好提示也就永远轮不到，直接漏成 500。
     *
     * <p>不把 deleted 加进唯一键，是因为那样会让**多次撤销**的同名行互撞（都是 deleted=1）。
     * 复活既保住了唯一键的语义，也留住了这一行的历史（created_at / created_by 不变）。
     */
    @Select("SELECT id FROM loc_site_agent WHERE site_no = #{siteNo} AND agent_no = #{agentNo} "
            + "AND role = #{role} AND deleted = 1 ORDER BY id DESC LIMIT 1")
    Long findRevokedId(@Param("siteNo") String siteNo, @Param("agentNo") String agentNo,
                       @Param("role") String role);

    /** 复活一条已撤销的责任行。{@code updateById} 会被逻辑删除拦住，故用裸 SQL。 */
    @Update("UPDATE loc_site_agent SET deleted = 0 WHERE id = #{id}")
    int revive(@Param("id") Long id);
}

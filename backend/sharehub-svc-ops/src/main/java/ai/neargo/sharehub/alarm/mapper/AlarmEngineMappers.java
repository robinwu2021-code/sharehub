package ai.neargo.sharehub.alarm.mapper;

import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmCondition;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmSiteProfile;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmTodo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 业务告警引擎的 Mapper。 */
public final class AlarmEngineMappers {

    private AlarmEngineMappers() {
    }

    public interface RouteMapper extends BaseMapper<DevAlarmRoute> {

        /**
         * **物理**删掉一个告警码的全部路由。
         *
         * <p>不能用 {@code delete(wrapper)}：{@code DevAlarmRoute} 带 {@code @TableLogic}，
         * 那是**逻辑删**，而 {@code uk_alarm_route(alarm_code, cause)} <b>不含 deleted</b> ——
         * 于是「整体替换」的第二次保存必定撞唯一键，同一个告警码的路由**改第二次就 500**。
         * 代码注释当时写的是「物理删旧路由」，而注解让它变成了逻辑删，两者不一致了很久。
         *
         * <p>路由是全局配置表、没有业务历史意义（改动由审计拦截器留痕），
         * 所以这里确实该物理删 —— 而不是去给唯一索引加 deleted 列（那等于允许无限堆重复）。
         */
        @Delete("DELETE FROM dev_alarm_route WHERE alarm_code = #{alarmCode}")
        int deleteByAlarmCode(@Param("alarmCode") String alarmCode);
    }

    public interface ConditionMapper extends BaseMapper<DevAlarmCondition> {
    }

    public interface AlarmLogMapper extends BaseMapper<DevAlarmLog> {
    }

    public interface TodoMapper extends BaseMapper<DevAlarmTodo> {
    }

    public interface SiteProfileMapper extends BaseMapper<DevAlarmSiteProfile> {
    }
}

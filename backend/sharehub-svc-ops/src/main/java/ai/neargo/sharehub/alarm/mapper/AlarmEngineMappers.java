package ai.neargo.sharehub.alarm.mapper;

import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmCondition;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmSiteProfile;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmTodo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 业务告警引擎的 Mapper。 */
public final class AlarmEngineMappers {

    private AlarmEngineMappers() {
    }

    public interface RouteMapper extends BaseMapper<DevAlarmRoute> {
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

package ai.neargo.sharehub.dev.mapper;

import ai.neargo.sharehub.dev.entity.DevEventCode;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.entity.DevQcRecord;
import ai.neargo.sharehub.dev.entity.DevSignalLog;
import ai.neargo.sharehub.dev.entity.DevTrialRent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 机柜运维（保护动作 / 试借还 / 信号字典）的 Mapper。 */
public final class DeviceOpsMappers {

    private DeviceOpsMappers() {
    }

    public interface ProtectionMapper extends BaseMapper<DevProtection> {
    }

    public interface TrialRentMapper extends BaseMapper<DevTrialRent> {
    }

    public interface EventCodeMapper extends BaseMapper<DevEventCode> {
    }

    public interface QcRecordMapper extends BaseMapper<DevQcRecord> {
    }

    public interface SignalLogMapper extends BaseMapper<DevSignalLog> {
    }
}

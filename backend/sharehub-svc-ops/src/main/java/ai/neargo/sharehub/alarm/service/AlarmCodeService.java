package ai.neargo.sharehub.alarm.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmCode;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;

/**
 * 告警代码字典。纯配置读写，无状态流转 → 继承通用 CRUD（[SKELETON_BRIEF §3] 字典类）。
 *
 * <p>虽然 {@code autoWorkOrder} 会驱动自动开单，但那是**消费方**（告警上报链路）读这张字典时的行为，
 * 字典本身仍只是键值维护，故归字典类。
 */
public interface AlarmCodeService extends CrudService<DevAlarmCode, AlarmCode> {
}

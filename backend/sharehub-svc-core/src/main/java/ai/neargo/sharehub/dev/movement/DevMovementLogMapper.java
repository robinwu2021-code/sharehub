package ai.neargo.sharehub.dev.movement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 设备履历 Mapper —— 只许 {@link MovementRecorder} 用（ArchitectureTest 守）：写入只追加、不改不删。 */
public interface DevMovementLogMapper extends BaseMapper<DevMovementLog> {
}

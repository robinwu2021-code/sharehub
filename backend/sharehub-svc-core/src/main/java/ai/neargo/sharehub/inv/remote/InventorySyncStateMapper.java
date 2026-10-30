package ai.neargo.sharehub.inv.remote;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface InventorySyncStateMapper extends BaseMapper<InventorySyncState> {

    @Insert("INSERT INTO inventory_sync_state (k, v) VALUES (#{k}, #{v}) ON DUPLICATE KEY UPDATE v = VALUES(v)")
    int put(@Param("k") String k, @Param("v") String v);
}

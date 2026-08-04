package ai.neargo.sharehub.auth.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;

import java.time.LocalDateTime;

/** MySQL 会话表（{@code token-store=mysql} 模式用）。token 为主键（不透明串）。 */
@Data
@TableName("sys_token")
public class SysToken {
    @TableId(value = "token", type = IdType.INPUT)
    private String token;
    private String realm;
    private String subjectNo;
    private String roleNos;      // CSV
    private Long permStamp;
    private String payload;      // JSON(SessionData)
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;

    /** 嵌套 Mapper（随 @MapperScan markerInterface 扫描）。 */
    public interface SysTokenMapper extends BaseMapper<SysToken> {
    }
}

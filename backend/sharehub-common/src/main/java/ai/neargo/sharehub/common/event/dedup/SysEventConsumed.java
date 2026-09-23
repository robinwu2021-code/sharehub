package ai.neargo.sharehub.common.event.dedup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事件消费去重记录。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：它不是业务实体 —— 没有租户归属（平台级事件本就跨租户）、
 * 不需要软删（删了去重就失效）、不需要乐观锁（只插不改）。
 * 让它继承会带来四个用不上却必须维护的列，以及「为什么这张表也能被归档」的疑问。
 */
@Getter
@Setter
@TableName("sys_event_consumed")
public class SysEventConsumed {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventNo;

    /** 消费者标识（一般是实现类名）—— 与 eventNo 组成去重键。 */
    private String handler;

    private String eventType;

    private LocalDateTime consumedAt;
}

package ai.neargo.sharehub.inv.mapper;

import ai.neargo.sharehub.inv.entity.InvCountReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface InvCountReviewMapper extends BaseMapper<InvCountReview> {
    @Insert("INSERT IGNORE INTO inv_count_review(count_no,warehouse_no) VALUES(#{no},#{wh})")
    int ensure(@Param("no") String no,@Param("wh") String wh);
}

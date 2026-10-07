package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.MaterialUsage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MaterialUsageMapper extends BaseMapper<MaterialUsage> {

    /**
     * 领用单行锁：对领用单行执行一次 UPDATE 占位，H2 行级排他锁持有到当前
     * 事务提交——同一领用单的并发退料（重复点击/接口重试/异常重放）在此串行，
     * 锁内回读：同退料单号幂等回放，否则按最新累计退料量判定。
     * 必须在事务内调用。
     * （不用 SELECT ... FOR UPDATE：映射结束即关闭 ResultSet，行锁随之释放。）
     */
    @Update("UPDATE t_material_usage SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}

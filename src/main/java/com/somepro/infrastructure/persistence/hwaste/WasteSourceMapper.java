package com.somepro.infrastructure.persistence.hwaste;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.hwaste.po.WasteSourcePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * t_waste_source 的 MyBatis-Plus Mapper（基础设施层，阻塞 JDBC）。
 */
@Mapper
public interface WasteSourceMapper extends BaseMapper<WasteSourcePO> {

    /**
     * 行锁读产废单位（SELECT ... FOR UPDATE）：停用 / 恢复 / 新入库 / 开新联单
     * 都先锁单位行再判状态，把状态切换与开新业务的竞态在数据库层串行化。
     * 必须在事务内调用，锁持有到事务提交。
     */
    @Select("SELECT * FROM t_waste_source WHERE del_flag = 0 AND id = #{id} FOR UPDATE")
    WasteSourcePO selectByIdForUpdate(@Param("id") Long id);
}

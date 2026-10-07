package com.somepro.infrastructure.persistence.hwaste;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * t_waste_category 的 MyBatis-Plus Mapper（基础设施层，阻塞 JDBC）。
 */
@Mapper
public interface WasteCategoryMapper extends BaseMapper<WasteCategoryPO> {

    /**
     * 行锁读类别（SELECT ... FOR UPDATE）：停用 / 开新联单 / 新入库 / 新计划
     * 都先锁类别行再判状态，把「停用」与「开新单」的前后脚竞态在数据库层串行化。
     * 必须在事务内调用，锁持有到事务提交。
     */
    @Select("SELECT * FROM t_waste_category WHERE del_flag = 0 AND category_code = #{categoryCode} FOR UPDATE")
    WasteCategoryPO selectByCodeForUpdate(@Param("categoryCode") String categoryCode);
}

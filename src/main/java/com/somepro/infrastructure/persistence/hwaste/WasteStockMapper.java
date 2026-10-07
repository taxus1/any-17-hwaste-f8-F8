package com.somepro.infrastructure.persistence.hwaste;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.hwaste.po.WasteStockPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * t_waste_stock 的 MyBatis-Plus Mapper（基础设施层，阻塞 JDBC）。
 *
 * 自定义 SQL 里的 del_flag = 0 要手写（@TableLogic 只自动拼 MyBatis-Plus 生成的 SQL）。
 */
@Mapper
public interface WasteStockMapper extends BaseMapper<WasteStockPO> {

    /** 该单位该类别在库（IN_STOCK）批次重量合计；没有记录时返回 0。 */
    @Select("SELECT IFNULL(SUM(weight_kg), 0) FROM t_waste_stock "
            + "WHERE del_flag = 0 AND source_id = #{sourceId} AND category_code = #{categoryCode} "
            + "AND status = 'IN_STOCK'")
    BigDecimal sumInStockWeight(@Param("sourceId") Long sourceId, @Param("categoryCode") String categoryCode);

    /** 该类别在库（IN_STOCK）批次数（停用影响面，不分产废单位）；没有记录时返回 0。 */
    @Select("SELECT COUNT(*) FROM t_waste_stock "
            + "WHERE del_flag = 0 AND category_code = #{categoryCode} AND status = 'IN_STOCK'")
    long countInStockByCategory(@Param("categoryCode") String categoryCode);

    /** 该类别在库（IN_STOCK）重量合计（停用影响面，千克）；没有记录时返回 0。 */
    @Select("SELECT IFNULL(SUM(weight_kg), 0) FROM t_waste_stock "
            + "WHERE del_flag = 0 AND category_code = #{categoryCode} AND status = 'IN_STOCK'")
    BigDecimal sumInStockWeightByCategory(@Param("categoryCode") String categoryCode);

    @Select("SELECT MAX(batch_no) FROM t_waste_stock WHERE batch_no LIKE CONCAT(#{prefix}, '%') FOR UPDATE")
    String maxBatchNo(@Param("prefix") String prefix);
}

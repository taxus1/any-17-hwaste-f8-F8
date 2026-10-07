package com.somepro.application.hwaste;

import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.SplitItem;
import com.somepro.domain.hwaste.model.WasteStock;
import com.somepro.domain.hwaste.repository.StockCheckRepository;
import com.somepro.domain.hwaste.repository.WasteCategoryRepository;
import com.somepro.domain.hwaste.repository.WasteSourceRepository;
import com.somepro.domain.hwaste.repository.WasteStockRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库 / 转出 / 拆分 / 合并用例编排（应用层）。
 *
 * 入库看两头：产废单位处在正常（ACTIVE）、危废类别处在启用（ENABLED）才让登，
 * 不存在或停用的单位 / 类别都挡回去；仓储创建时再锁单位、类别行复核，防止停用与新入库并发穿透。
 *
 * 盘点冻结规则：该单位该类别一旦有单子进到盘点中（COUNTING / PENDING_APPROVAL / APPROVED），
 * 新入库与联单转出都先停下来，等调完账或作废再放行；没在盘点中的组合照常放行。
 */
@Service
public class StockAppService {

    private final WasteStockRepository wasteStockRepository;
    private final StockCheckRepository stockCheckRepository;
    private final WasteSourceRepository wasteSourceRepository;
    private final WasteCategoryRepository wasteCategoryRepository;

    public StockAppService(WasteStockRepository wasteStockRepository, StockCheckRepository stockCheckRepository,
                           WasteSourceRepository wasteSourceRepository,
                           WasteCategoryRepository wasteCategoryRepository) {
        this.wasteStockRepository = wasteStockRepository;
        this.stockCheckRepository = stockCheckRepository;
        this.wasteSourceRepository = wasteSourceRepository;
        this.wasteCategoryRepository = wasteCategoryRepository;
    }

    /** 新入库：单位 / 类别状态先校验，盘点冻结中的组合再挡回。 */
    public Mono<WasteStock> inbound(Long sourceId, String categoryCode, BigDecimal weightKg, String packageType) {
        return Mono.defer(() -> {
            WasteStock stock = WasteStock.inbound(sourceId, categoryCode, weightKg, packageType);
            return wasteSourceRepository.findById(stock.getSourceId())
                    .switchIfEmpty(Mono.error(new BizException("产废单位不存在")))
                    .flatMap(source -> source.isActive()
                            ? Mono.empty()
                            : Mono.error(new BizException("产废单位非正常状态，禁止入库")))
                    .then(wasteCategoryRepository.findByCode(stock.getCategoryCode()))
                    .switchIfEmpty(Mono.error(new BizException("危废类别不存在")))
                    .flatMap(category -> category.isEnabled()
                            ? Mono.empty()
                            : Mono.error(new BizException("危废类别已停用，禁止入库")))
                    .then(rejectIfFrozen(stock.getSourceId(), stock.getCategoryCode(), "新入库"))
                    .then(wasteStockRepository.inbound(stock));
        });
    }

    /** 联单转出：盘点冻结中的组合先挡回；在库不足也挡回。 */
    public Mono<BigDecimal> transferOut(Long sourceId, String categoryCode, BigDecimal weightKg) {
        return Mono.defer(() -> {
            if (sourceId == null) {
                return Mono.error(new BizException("产废单位不能为空"));
            }
            if (categoryCode == null || categoryCode.isBlank()) {
                return Mono.error(new BizException("危废类别不能为空"));
            }
            if (weightKg == null || weightKg.signum() <= 0) {
                return Mono.error(new BizException("转出重量必须大于 0"));
            }
            return rejectIfFrozen(sourceId, categoryCode.trim(), "联单转出")
                    .then(wasteStockRepository.transferOut(sourceId, categoryCode.trim(), weightKg));
        });
    }

    /**
     * 拆分：把一个在库批次按重量拆成若干子批。packageTypes 与 weights 按下标一一对应，
     * 不传或某项为空表示继承父批包装。重复拆同一批是幂等空操作（回现有子批）。
     */
    public Mono<List<WasteStock>> split(Long batchId, List<BigDecimal> weights, List<String> packageTypes) {
        return Mono.defer(() -> {
            if (batchId == null) {
                return Mono.error(new BizException("批次 id 不能为空"));
            }
            if (weights == null || weights.isEmpty()) {
                return Mono.error(new BizException("拆分重量列表不能为空"));
            }
            boolean withPackages = packageTypes != null && !packageTypes.isEmpty();
            if (withPackages && packageTypes.size() != weights.size()) {
                return Mono.error(new BizException("包装方式列表与重量列表数量不一致"));
            }
            List<SplitItem> items = new ArrayList<>();
            for (int i = 0; i < weights.size(); i++) {
                items.add(new SplitItem(weights.get(i), withPackages ? packageTypes.get(i) : null));
            }
            return wasteStockRepository.split(batchId, items);
        });
    }

    /**
     * 合并：把同单位、同类别、同包装的若干在库批次并成一票。
     * 重复并同一批是幂等空操作（返回空）。
     */
    public Mono<WasteStock> merge(List<Long> batchIds) {
        return Mono.defer(() -> {
            if (batchIds == null || batchIds.isEmpty()) {
                return Mono.error(new BizException("批次 id 列表不能为空"));
            }
            return wasteStockRepository.merge(batchIds);
        });
    }

    /** 该单位该类别当前在库重量合计。 */
    public Mono<BigDecimal> sumInStock(Long sourceId, String categoryCode) {
        return Mono.defer(() -> {
            if (sourceId == null) {
                return Mono.error(new BizException("产废单位不能为空"));
            }
            if (categoryCode == null || categoryCode.isBlank()) {
                return Mono.error(new BizException("危废类别不能为空"));
            }
            return wasteStockRepository.sumInStock(sourceId, categoryCode.trim());
        });
    }

    public Mono<PageResult<WasteStock>> page(int pageNum, int pageSize, Long sourceId, String categoryCode,
                                             String packageType, String status,
                                             LocalDate inDateFrom, LocalDate inDateTo) {
        return wasteStockRepository.page(pageNum, pageSize, sourceId, categoryCode, packageType, status,
                inDateFrom, inDateTo);
    }

    /** 盘点冻结校验：该单位该类别有盘点中的单子就挡回。 */
    private Mono<Void> rejectIfFrozen(Long sourceId, String categoryCode, String action) {
        return stockCheckRepository.countFreezing(sourceId, categoryCode)
                .flatMap(frozen -> frozen != null && frozen > 0
                        ? Mono.error(new BizException("该单位该类别正在盘点中，" + action + "暂停，待盘点调账或作废后放行"))
                        : Mono.empty());
    }
}

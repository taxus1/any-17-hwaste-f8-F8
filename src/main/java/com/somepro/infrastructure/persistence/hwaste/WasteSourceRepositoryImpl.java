package com.somepro.infrastructure.persistence.hwaste;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.hwaste.repository.WasteSourceRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.base.BaseBlockingRepository;
import com.somepro.infrastructure.persistence.hwaste.converter.WasteSourcePoConverter;
import com.somepro.infrastructure.persistence.hwaste.po.WasteSourcePO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 产废单位仓储适配器（基础设施层）。
 *
 * 并发约定（本模块的关键账）：
 * - 停用 / 恢复与「新入库 / 开新联单」都在各自事务里先 {@code SELECT ... FOR UPDATE}
 *   锁单位行，拿到锁后看到的状态即裁决依据 —— 两个人几乎同时对同一家一停一复，
 *   两笔在单位行锁上串行，后到的看到先到的结果，条件更新再兜一道，落库状态唯一，不会互相覆盖。
 * - 停用 / 恢复允许重复按：锁里看到已在目标态就原样返回（幂等空操作，不改状态、不重写审计）；
 *   连按两回状态不会来回跳。
 * - 已关闭（CLOSED）是终态，停用 / 恢复都挡回；清单查询也永远不带它。
 * - 办理人 / 办理时刻走审计列 update_by / update_time，由 MetaObjectHandler 在条件更新时自动填充。
 */
@Repository
public class WasteSourceRepositoryImpl extends BaseBlockingRepository implements WasteSourceRepository {

    private final WasteSourceMapper wasteSourceMapper;
    private final TransactionTemplate txTemplate;

    public WasteSourceRepositoryImpl(WasteSourceMapper wasteSourceMapper,
                                     PlatformTransactionManager transactionManager) {
        this.wasteSourceMapper = wasteSourceMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<WasteSource> findById(Long id) {
        return blocking(() -> {
            WasteSourcePO po = wasteSourceMapper.selectById(id);
            return po == null ? null : WasteSourcePoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<WasteSource> findBySourceNo(String sourceNo) {
        return blocking(() -> {
            WasteSourcePO po = wasteSourceMapper.selectOne(Wrappers.<WasteSourcePO>lambdaQuery()
                    .eq(WasteSourcePO::getSourceNo, sourceNo));
            return po == null ? null : WasteSourcePoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, SourceStatus status) {
        return this.<PageResult<WasteSource>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<WasteSourcePO> wrapper = Wrappers.<WasteSourcePO>lambdaQuery()
                        // 已关闭的单位不算在这次查询范围内：任何状态条件下都不带 CLOSED
                        .ne(WasteSourcePO::getStatus, SourceStatus.CLOSED.name())
                        .eq(status != null, WasteSourcePO::getStatus, status == null ? null : status.name())
                        .orderByAsc(WasteSourcePO::getSourceNo);
                List<WasteSourcePO> rows = wasteSourceMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<WasteSource> content = rows.stream()
                        .map(WasteSourcePoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    @Override
    public Mono<WasteSource> suspend(Long id) {
        return blocking(() -> txTemplate.execute(tx -> changeStatus(
                id, SourceStatus.ACTIVE, SourceStatus.SUSPENDED)));
    }

    @Override
    public Mono<WasteSource> resume(Long id) {
        return blocking(() -> txTemplate.execute(tx -> changeStatus(
                id, SourceStatus.SUSPENDED, SourceStatus.ACTIVE)));
    }

    /**
     * 锁单位行后的状态推进：expect → target。
     * 已在目标态 → 幂等空操作原样返回；已关闭 → 挡回；条件更新 0 行（理论上行锁内不会发生）
     * 按锁内最新状态幂等返回，保证重复按永远不把状态来回改。
     */
    private WasteSource changeStatus(Long id, SourceStatus expect, SourceStatus target) {
        WasteSourcePO po = wasteSourceMapper.selectByIdForUpdate(id);
        if (po == null) {
            throw new BizException("产废单位不存在");
        }
        SourceStatus current = parseStatus(po.getStatus());
        if (current == SourceStatus.CLOSED) {
            throw new BizException("产废单位 " + po.getSourceNo() + " 已关闭，不能"
                    + (target == SourceStatus.SUSPENDED ? "停用" : "恢复"));
        }
        if (current == target) {
            // 重复按：已经在目标态，不动状态也不重写「谁办的、什么时候办的」
            return WasteSourcePoConverter.toDomain(po);
        }
        WasteSourcePO patch = new WasteSourcePO();
        patch.setStatus(target.name());
        int rows = wasteSourceMapper.update(patch, Wrappers.<WasteSourcePO>lambdaUpdate()
                .eq(WasteSourcePO::getId, id)
                .eq(WasteSourcePO::getStatus, expect.name()));
        if (rows == 0) {
            // 行锁内正常走不到；兜底按最新状态幂等返回，绝不报错让调用方以为状态乱了
            return WasteSourcePoConverter.toDomain(wasteSourceMapper.selectById(id));
        }
        return WasteSourcePoConverter.toDomain(wasteSourceMapper.selectById(id));
    }

    private static SourceStatus parseStatus(String status) {
        try {
            return status == null ? null : SourceStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BizException("产废单位状态取值非法：" + status);
        }
    }
}

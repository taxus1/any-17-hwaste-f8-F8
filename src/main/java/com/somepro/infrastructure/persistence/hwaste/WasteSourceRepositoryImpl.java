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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 产废单位仓储适配器（基础设施层）。
 *
 * 停用 / 恢复先在事务里 {@code SELECT ... FOR UPDATE} 锁单位行，再做条件更新：
 * ACTIVE → SUSPENDED 或 SUSPENDED → ACTIVE。两个人几乎同时一停一复时，数据库行锁
 * 把两笔事务串行化，后提交者基于最新状态裁决，不会用旧快照互相覆盖；重复操作在锁内
 * 看到已经是目标状态时直接返回，不重复改写，也不覆盖上一次操作审计。
 *
 * 新入库 / 新开联单同样锁单位行，因此停用提交后不会再放进新的入库批次或联单；
 * 已开出联单的签收、处置确认不取这把状态闸，仍可继续走。
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
    public Mono<WasteSource> suspend(Long id) {
        return transition(id, SourceStatus.ACTIVE, SourceStatus.SUSPENDED);
    }

    @Override
    public Mono<WasteSource> resume(Long id) {
        return transition(id, SourceStatus.SUSPENDED, SourceStatus.ACTIVE);
    }

    @Override
    public Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, SourceStatus status) {
        // CLOSED 是关闭终态，明确不纳入状态清单；不查库也能避免把它误带进总数。
        if (status == SourceStatus.CLOSED) {
            return Mono.just(new PageResult<>(Collections.emptyList(), 0, pageNum, pageSize));
        }
        return this.<PageResult<WasteSource>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<WasteSourcePO> wrapper = Wrappers.<WasteSourcePO>lambdaQuery()
                        .eq(status != null, WasteSourcePO::getStatus, status == null ? null : status.name())
                        .ne(status == null, WasteSourcePO::getStatus, SourceStatus.CLOSED.name())
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

    /**
     * 锁内状态切换：current 才允许推到 target；锁里看到已是 target 则幂等返回。
     * 条件更新与行锁双保险，避免并发旧快照覆盖。
     */
    private Mono<WasteSource> transition(Long id, SourceStatus current, SourceStatus target) {
        return blocking(() -> txTemplate.execute(tx -> {
            WasteSourcePO po = wasteSourceMapper.selectByIdForUpdate(id);
            if (po == null) {
                throw new BizException("产废单位不存在");
            }
            SourceStatus currentStatus = SourceStatus.valueOf(po.getStatus());
            if (currentStatus == target) {
                return WasteSourcePoConverter.toDomain(po);
            }

            // 状态规则收口在领域对象；仓储只负责把它和行锁、条件更新接上。
            WasteSource source = WasteSourcePoConverter.toDomain(po);
            if (target == SourceStatus.SUSPENDED) {
                source.suspend();
            } else {
                source.resume();
            }
            WasteSourcePO patch = new WasteSourcePO();
            patch.setStatus(target.name());
            int rows = wasteSourceMapper.update(patch, Wrappers.<WasteSourcePO>lambdaUpdate()
                    .eq(WasteSourcePO::getId, id)
                    .eq(WasteSourcePO::getStatus, current.name()));
            if (rows == 0) {
                throw new BizException("产废单位状态已变更，本次操作未生效");
            }
            return WasteSourcePoConverter.toDomain(wasteSourceMapper.selectById(id));
        }));
    }
}

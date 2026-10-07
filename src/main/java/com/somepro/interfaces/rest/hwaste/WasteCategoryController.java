package com.somepro.interfaces.rest.hwaste;

import com.somepro.application.hwaste.WasteCategoryAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.hwaste.converter.WasteCategoryVoConverter;
import com.somepro.interfaces.rest.hwaste.vo.CategoryImpactVO;
import com.somepro.interfaces.rest.hwaste.vo.DisableCategoryVO;
import com.somepro.interfaces.rest.hwaste.vo.PageVO;
import com.somepro.interfaces.rest.hwaste.vo.WasteCategoryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 危废类别名录接口（用户接口层）：录入、修改、查看、停用，外加停用前影响面评估。
 *
 * 名录定位参数：id 或 categoryCode 传其一。停用是高牵动操作 —— 先调 impact 看影响面，
 * 有在库批次 / 在办联单时 disable 需带 force=true 表示看清后确认。
 */
@RestController
@RequestMapping("/api/hwaste/category")
public class WasteCategoryController {

    private final WasteCategoryAppService wasteCategoryAppService;

    public WasteCategoryController(WasteCategoryAppService wasteCategoryAppService) {
        this.wasteCategoryAppService = wasteCategoryAppService;
    }

    /**
     * 录入名录：代码照 HW08 写法排（全局唯一，重复挡回），留名称、危险特性、跨省标志；
     * 状态不写按启用 ENABLED 落。
     */
    @PostMapping("/create")
    public Mono<Result<WasteCategoryVO>> create(@RequestParam(required = false) String categoryCode,
                                                @RequestParam(required = false) String name,
                                                @RequestParam(required = false) String hazardType,
                                                @RequestParam(required = false) Integer crossProvince) {
        return wasteCategoryAppService.create(categoryCode, name, hazardType, crossProvince)
                .map(WasteCategoryVoConverter::toVo)
                .map(Result::ok);
    }

    /** 修改名录：只动名称、危险特性、跨省标志；代码不能改，状态走停用接口。 */
    @PostMapping("/update")
    public Mono<Result<WasteCategoryVO>> update(@RequestParam(required = false) Long id,
                                                @RequestParam(required = false) String categoryCode,
                                                @RequestParam(required = false) String name,
                                                @RequestParam(required = false) String hazardType,
                                                @RequestParam(required = false) Integer crossProvince) {
        return wasteCategoryAppService.update(id, categoryCode, name, hazardType, crossProvince)
                .map(WasteCategoryVoConverter::toVo)
                .map(Result::ok);
    }

    /** 名录详情：id 或 categoryCode 传其一。 */
    @GetMapping("/detail")
    public Mono<Result<WasteCategoryVO>> detail(@RequestParam(required = false) Long id,
                                                @RequestParam(required = false) String categoryCode) {
        return wasteCategoryAppService.detail(id, categoryCode)
                .map(WasteCategoryVoConverter::toVo)
                .map(Result::ok);
    }

    /** 分页查询：可按危险特性和状态过滤，每条都把类别代码回出来。 */
    @GetMapping("/page")
    public Mono<Result<PageVO<WasteCategoryVO>>> page(@RequestParam(defaultValue = "1") int pageNum,
                                                      @RequestParam(defaultValue = "20") int pageSize,
                                                      @RequestParam(required = false) String hazardType,
                                                      @RequestParam(required = false) String status) {
        return wasteCategoryAppService.page(pageNum, pageSize, hazardType, status)
                .map(WasteCategoryVoConverter::toPageVo)
                .map(Result::ok);
    }

    /**
     * 停用前影响面：这个类别当前在库批次数与重量、没走完的联单张数、没批下来的计划份数。
     * blocksDisable=true 表示有在库批次或在办联单，停用要先给用户明白提示。
     */
    @GetMapping("/impact")
    public Mono<Result<CategoryImpactVO>> impact(@RequestParam(required = false) Long id,
                                                 @RequestParam(required = false) String categoryCode) {
        return wasteCategoryAppService.impact(id, categoryCode)
                .map(WasteCategoryVoConverter::toImpactVo)
                .map(Result::ok);
    }

    /**
     * 停用：有在库批次或在办联单时默认挡回并把影响面写在 msg 里；
     * 看清后带 force=true 再提交。停用只挡新单，已在办的老单子照走。
     */
    @PostMapping("/disable")
    public Mono<Result<DisableCategoryVO>> disable(@RequestParam(required = false) Long id,
                                                   @RequestParam(required = false) String categoryCode,
                                                   @RequestParam(defaultValue = "false") boolean force) {
        return wasteCategoryAppService.disable(id, categoryCode, force)
                .map(WasteCategoryVoConverter::toDisableVo)
                .map(Result::ok);
    }
}

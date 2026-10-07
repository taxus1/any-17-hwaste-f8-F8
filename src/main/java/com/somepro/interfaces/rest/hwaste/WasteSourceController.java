package com.somepro.interfaces.rest.hwaste;

import com.somepro.application.hwaste.WasteSourceAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.hwaste.converter.WasteSourceVoConverter;
import com.somepro.interfaces.rest.hwaste.vo.PageVO;
import com.somepro.interfaces.rest.hwaste.vo.WasteSourceVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 产废单位接口（用户接口层）：停用、恢复与按状态分页查看。
 *
 * 单位定位参数：id 或 sourceNo 传其一。停用 / 恢复可重复提交；关闭单位不进分页清单。
 */
@RestController
@RequestMapping("/api/hwaste/source")
public class WasteSourceController {

    private final WasteSourceAppService wasteSourceAppService;

    public WasteSourceController(WasteSourceAppService wasteSourceAppService) {
        this.wasteSourceAppService = wasteSourceAppService;
    }

    /** 停用：正常 ACTIVE → 停用 SUSPENDED；重复停用直接回当前状态。 */
    @PostMapping("/suspend")
    public Mono<Result<WasteSourceVO>> suspend(@RequestParam(required = false) Long id,
                                               @RequestParam(required = false) String sourceNo) {
        return wasteSourceAppService.suspend(id, sourceNo)
                .map(WasteSourceVoConverter::toVo)
                .map(Result::ok);
    }

    /** 恢复：停用 SUSPENDED → 正常 ACTIVE；重复恢复直接回当前状态。 */
    @PostMapping("/resume")
    public Mono<Result<WasteSourceVO>> resume(@RequestParam(required = false) Long id,
                                              @RequestParam(required = false) String sourceNo) {
        return wasteSourceAppService.resume(id, sourceNo)
                .map(WasteSourceVoConverter::toVo)
                .map(Result::ok);
    }

    /** 按状态分页：status 可传 ACTIVE / SUSPENDED；不传列正常和停用，CLOSED 始终不纳入。 */
    @GetMapping("/page")
    public Mono<Result<PageVO<WasteSourceVO>>> page(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(required = false) String status) {
        return wasteSourceAppService.page(pageNum, pageSize, status)
                .map(WasteSourceVoConverter::toPageVo)
                .map(Result::ok);
    }
}

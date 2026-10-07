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
 * 产废单位接口（用户接口层）：停用、恢复，以及按状态看的单位清单。
 *
 * 单位停产、搬迁、被要求整治时先调 suspend 摁住，整治完 resume 恢复；
 * 两笔都允许重复按（已停用再停、已正常再恢复都算成功，状态不跳）。
 * 单位定位参数：id 或 sourceNo 传其一。
 *
 * 停用后名下登不了新入库批次、名下计划开不出新联单；已在途的联单该签收签收、
 * 该处置确认确认，不受影响。清单按状态分页翻，每行带单位编号，不含已关闭单位。
 */
@RestController
@RequestMapping("/api/hwaste/source")
public class WasteSourceController {

    private final WasteSourceAppService wasteSourceAppService;

    public WasteSourceController(WasteSourceAppService wasteSourceAppService) {
        this.wasteSourceAppService = wasteSourceAppService;
    }

    /** 停用：正常 ACTIVE → 停用 SUSPENDED，办理人 / 办理时刻随状态落库；重复停用幂等。 */
    @PostMapping("/suspend")
    public Mono<Result<WasteSourceVO>> suspend(@RequestParam(required = false) Long id,
                                               @RequestParam(required = false) String sourceNo) {
        return wasteSourceAppService.suspend(id, sourceNo)
                .map(WasteSourceVoConverter::toVo)
                .map(Result::ok);
    }

    /** 恢复：停用 SUSPENDED → 正常 ACTIVE；重复恢复幂等。 */
    @PostMapping("/resume")
    public Mono<Result<WasteSourceVO>> resume(@RequestParam(required = false) Long id,
                                              @RequestParam(required = false) String sourceNo) {
        return wasteSourceAppService.resume(id, sourceNo)
                .map(WasteSourceVoConverter::toVo)
                .map(Result::ok);
    }

    /**
     * 按状态看单位清单：status 传 ACTIVE / SUSPENDED（不传则两档都列），分页往下翻；
     * 每行带单位编号；已关闭（CLOSED）的单位永远不在结果里。
     */
    @GetMapping("/page")
    public Mono<Result<PageVO<WasteSourceVO>>> page(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(required = false) String status) {
        return wasteSourceAppService.page(pageNum, pageSize, status)
                .map(WasteSourceVoConverter::toPageVo)
                .map(Result::ok);
    }
}

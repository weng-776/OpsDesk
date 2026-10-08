package com.opsdesk.common;

import com.opsdesk.OpsDeskApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * common 层验收测试（规格基线 §20.1 / §20.2 / §23.6）
 *
 * <p>用 MockMvc 打一个**测试专用**的探针 Controller，验证四件事：
 * <ol>
 *   <li>成功响应结构是 {@code {code:0, message:"success", data:...}}</li>
 *   <li>业务异常：**HTTP 状态码与业务 code 对齐**（不是 HTTP 恒 200）</li>
 *   <li>参数校验失败：40001 + {@code data} 里是「字段 → 错误信息」</li>
 *   <li>traceId 回写响应头</li>
 * </ol>
 */
@SpringBootTest(classes = OpsDeskApplication.class)
@AutoConfigureMockMvc
@Import(CommonLayerTest.ProbeController.class)
class CommonLayerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 成功响应结构符合统一约定() throws Exception {
        mockMvc.perform(get("/_test/common/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("hello"));
    }

    @Test
    void 业务异常时HTTP状态码与业务code对齐() throws Exception {
        // 40900 → HTTP 409
        mockMvc.perform(get("/_test/common/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("当前状态不允许挂起"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 40400 → HTTP 404
        mockMvc.perform(get("/_test/common/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void 参数校验失败返回40001与字段级明细() throws Exception {
        mockMvc.perform(post("/_test/common/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.data.title").value("标题不能为空"));
    }

    @Test
    void traceId写入响应头() throws Exception {
        // 上游没传 → 自己生成
        mockMvc.perform(get("/_test/common/ok"))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));

        // 上游传了 → 沿用（跨服务链路串联）
        mockMvc.perform(get("/_test/common/ok")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace-from-upstream"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "trace-from-upstream"));
    }

    @Test
    void 未知异常兜底为50000() throws Exception {
        mockMvc.perform(get("/_test/common/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
    }

    // ==================================================================
    //  测试专用探针 Controller（只在测试上下文里注册，不进生产代码）
    // ==================================================================

    @RestController
    @RequestMapping("/_test/common")
    static class ProbeController {

        @GetMapping("/ok")
        Result<String> ok() {
            return Result.ok("hello");
        }

        @GetMapping("/conflict")
        Result<Void> conflict() {
            throw new BizException(ErrorCode.CONFLICT, "当前状态不允许挂起");
        }

        @GetMapping("/not-found")
        Result<Void> notFound() {
            throw BizException.notFound("工单不存在");
        }

        @GetMapping("/boom")
        Result<Void> boom() {
            throw new IllegalStateException("模拟未预期异常");
        }

        @PostMapping("/validate")
        Result<Void> validate(@Valid @RequestBody Form form) {
            return Result.ok();
        }
    }

    @Data
    static class Form {
        @NotBlank(message = "标题不能为空")
        private String title;
    }
}

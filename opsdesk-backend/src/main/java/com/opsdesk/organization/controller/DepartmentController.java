package com.opsdesk.organization.controller;

import com.opsdesk.common.Result;
import com.opsdesk.organization.dto.DepartmentCreateDTO;
import com.opsdesk.organization.dto.DepartmentUpdateDTO;
import com.opsdesk.organization.service.DepartmentManageService;
import com.opsdesk.organization.vo.DepartmentCreatedVO;
import com.opsdesk.organization.vo.DepartmentNodeVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口（API 文档 §6.1–§6.4）
 *
 * <p>鉴权：所有接口需要登录 —— {@code AuthInterceptor}（D1-02）已覆盖 {@code /api/**}。
 *
 * <p>⚠️ <b>权限码校验本工单先不做</b>（按工单 §3 的依赖说明）：
 * 按 API 文档 §6 的抬头，这一组接口应当限 {@code ADMIN}，
 * 但 {@code @RequirePermission} 注解与权限拦截器是 <b>D2-02</b> 的交付物，
 * D2-02 完成后回来补。在那之前任何已登录用户都能调用本组接口 —— 已知的临时状态。
 *
 * <p>Controller 保持「薄」：只做参数绑定与结果包装，业务与异常都在 Service 层。
 */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentManageService departmentManageService;

    public DepartmentController(DepartmentManageService departmentManageService) {
        this.departmentManageService = departmentManageService;
    }

    /**
     * 部门树（§6.1）—— 返回嵌套数组，同级按 {@code sort} 升序。
     *
     * <p>注意 {@code path} <b>不在</b>出参里：它是子树前缀匹配的实现细节，不进对外契约。
     */
    @GetMapping("/tree")
    public Result<List<DepartmentNodeVO>> tree() {
        return Result.ok(departmentManageService.tree());
    }

    /** 创建部门（§6.2）—— 返回 {@code {"id": 新部门ID}}；父部门不存在返回 40001 */
    @PostMapping
    public Result<DepartmentCreatedVO> create(@Valid @RequestBody DepartmentCreateDTO dto) {
        return Result.ok(new DepartmentCreatedVO(departmentManageService.create(dto)));
    }

    /** 修改部门（§6.3）—— 变更父部门会级联重算整棵子树的 path；成环返回 40900 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DepartmentUpdateDTO dto) {
        departmentManageService.update(id, dto);
        return Result.ok();
    }

    /** 删除部门（§6.4，逻辑删除）—— 存在子部门或部门下有用户返回 40900 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        departmentManageService.delete(id);
        return Result.ok();
    }
}

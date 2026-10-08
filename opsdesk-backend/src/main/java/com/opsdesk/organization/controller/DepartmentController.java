package com.opsdesk.organization.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
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
 * <p><b>权限码校验（D2-02 已补全）</b>：按 API 文档 §6 的抬头，这一组接口限 {@code ADMIN}，
 * 已给 4 个方法逐个加上 {@code @RequirePermission}（权限码见 §3.6，用
 * {@code PermissionCodes} 常量）。已登录但缺码 → 40300；未登录 → 40100。
 *
 * <p>⚠️ 本组只做<b>权限码</b>校验，不做<b>数据范围</b>校验（§8 / 40301，归 D2-04）。
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
    @RequirePermission(PermissionCodes.DEPARTMENT_LIST)
    @GetMapping("/tree")
    public Result<List<DepartmentNodeVO>> tree() {
        return Result.ok(departmentManageService.tree());
    }

    /** 创建部门（§6.2）—— 返回 {@code {"id": 新部门ID}}；父部门不存在返回 40001 */
    @RequirePermission(PermissionCodes.DEPARTMENT_CREATE)
    @PostMapping
    public Result<DepartmentCreatedVO> create(@Valid @RequestBody DepartmentCreateDTO dto) {
        return Result.ok(new DepartmentCreatedVO(departmentManageService.create(dto)));
    }

    /** 修改部门（§6.3）—— 变更父部门会级联重算整棵子树的 path；成环返回 40900 */
    @RequirePermission(PermissionCodes.DEPARTMENT_UPDATE)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DepartmentUpdateDTO dto) {
        departmentManageService.update(id, dto);
        return Result.ok();
    }

    /** 删除部门（§6.4，逻辑删除）—— 存在子部门或部门下有用户返回 40900 */
    @RequirePermission(PermissionCodes.DEPARTMENT_DELETE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        departmentManageService.delete(id);
        return Result.ok();
    }
}

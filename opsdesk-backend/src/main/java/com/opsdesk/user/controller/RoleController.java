package com.opsdesk.user.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.user.dto.RolePermissionDTO;
import com.opsdesk.user.service.RoleManageService;
import com.opsdesk.user.vo.RoleVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色接口（API 文档 §7.1 / §7.3）
 *
 * <p>鉴权：需登录（{@code AuthInterceptor}）+ 权限码校验（{@code PermissionInterceptor}）。
 * 按 §7 的抬头，这一组接口限 {@code ADMIN}。
 *
 * <p>V1 固定三个系统角色，<b>不提供角色增删接口</b>（§7.1）—— 所以本类只有查询与「设置权限」。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleManageService roleManageService;

    public RoleController(RoleManageService roleManageService) {
        this.roleManageService = roleManageService;
    }

    /**
     * 角色列表（§7.1）。
     *
     * <p>每个角色带 {@code permissionIds}（D2-03 追加字段），供权限分配页回显勾选状态。
     */
    @RequirePermission(PermissionCodes.ROLE_LIST)
    @GetMapping
    public Result<List<RoleVO>> list() {
        return Result.ok(roleManageService.listRoles());
    }

    /** 设置角色权限（§7.3，全量覆盖；空数组表示清空） */
    @RequirePermission(PermissionCodes.ROLE_ASSIGN_PERMISSION)
    @PutMapping("/{roleId}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long roleId,
                                          @Valid @RequestBody RolePermissionDTO dto) {
        roleManageService.assignPermissions(roleId, dto);
        return Result.ok();
    }
}

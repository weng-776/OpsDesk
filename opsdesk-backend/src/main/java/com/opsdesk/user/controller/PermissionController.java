package com.opsdesk.user.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.user.service.RoleManageService;
import com.opsdesk.user.vo.PermissionNodeVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限接口（API 文档 §7.2）
 *
 * <p>鉴权：需登录 + {@code permission:list} 权限码（§3.6），按 §7 的抬头限 {@code ADMIN}。
 *
 * <p>与 {@link RoleController} 分属两个类，是因为 base path 不同
 * （{@code /api/permissions} vs {@code /api/roles}）—— 一个 Controller 只能有一个
 * {@code @RequestMapping} 前缀，硬凑到一起会让路径语义变模糊。
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final RoleManageService roleManageService;

    public PermissionController(RoleManageService roleManageService) {
        this.roleManageService = roleManageService;
    }

    /**
     * 权限树（§7.2）—— 含 MENU 与 API 两类，按 {@code parent_id} 组装、同级按 {@code sort} 升序。
     *
     * <p>⚠️ 按种子数据，33 个 API 权限的 {@code parent_id} 都是 0，所以它们会是顶层节点
     * （详见 {@link PermissionNodeVO}）。返回的根节点数是 4 + 33 = 37，节点总数 48。
     */
    @RequirePermission(PermissionCodes.PERMISSION_LIST)
    @GetMapping("/tree")
    public Result<List<PermissionNodeVO>> tree() {
        return Result.ok(roleManageService.permissionTree());
    }
}

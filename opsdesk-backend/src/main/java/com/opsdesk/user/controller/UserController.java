package com.opsdesk.user.controller;

import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.user.dto.UserCreateDTO;
import com.opsdesk.user.dto.UserQuery;
import com.opsdesk.user.dto.UserRoleDTO;
import com.opsdesk.user.dto.UserStatusDTO;
import com.opsdesk.user.dto.UserUpdateDTO;
import com.opsdesk.user.service.UserManageService;
import com.opsdesk.user.vo.UserCreatedVO;
import com.opsdesk.user.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（API 文档 §5.1–§5.7）
 *
 * <p>鉴权：所有接口需要登录 —— {@code AuthInterceptor}（D1-02）已覆盖 {@code /api/**}。
 *
 * <p>⚠️ <b>权限码校验本工单先不做</b>（按工单 §3 的依赖说明）：
 * 按 API 文档 §5 的抬头，这一组接口应当限 {@code ADMIN}，
 * 但 {@code @RequirePermission} 注解与权限拦截器是 <b>D2-02</b> 的交付物，
 * D2-02 完成后回来补 {@code @RequirePermission("user:list")} 等。
 * 在那之前，任何已登录用户都能调用本组接口 —— 这是<b>已知的临时状态</b>，不是遗漏。
 *
 * <p>Controller 保持「薄」：只做参数绑定与结果包装，业务与异常都在 Service 层
 * （§20.2 的统一响应与错误码由 {@code GlobalExceptionHandler} 负责）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManageService userManageService;

    public UserController(UserManageService userManageService) {
        this.userManageService = userManageService;
    }

    /**
     * 用户分页列表（§5.1）。
     *
     * <p>{@code ?page=1&size=10&keyword=&departmentId=&status=}；
     * {@code size} 上限 50，超出由 {@code PageQuery} 静默收敛。
     */
    @GetMapping
    public Result<PageResult<UserVO>> page(@Valid UserQuery query) {
        return Result.ok(userManageService.page(query));
    }

    /** 用户详情（§5.2）—— 用户不存在返回 40400 */
    @GetMapping("/{id}")
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userManageService.detail(id));
    }

    /** 创建用户（§5.3）—— 用户名冲突返回 40900；返回 {@code {"id": 新用户ID}} */
    @PostMapping
    public Result<UserCreatedVO> create(@Valid @RequestBody UserCreateDTO dto) {
        return Result.ok(new UserCreatedVO(userManageService.create(dto)));
    }

    /** 修改用户（§5.4，全量更新；{@code password} 为空表示不改密码） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        userManageService.update(id, dto);
        return Result.ok();
    }

    /** 修改用户状态（§5.5） */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        userManageService.updateStatus(id, dto);
        return Result.ok();
    }

    /** 分配角色（§5.6，全量覆盖） */
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody UserRoleDTO dto) {
        userManageService.assignRoles(id, dto);
        return Result.ok();
    }

    /** 删除用户（§5.7，逻辑删除）—— 存在未关闭工单返回 40900 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userManageService.delete(id);
        return Result.ok();
    }
}

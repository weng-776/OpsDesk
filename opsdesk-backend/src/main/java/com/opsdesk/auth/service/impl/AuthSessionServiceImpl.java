package com.opsdesk.auth.service.impl;

import com.opsdesk.auth.interceptor.AuthInterceptor;
import com.opsdesk.auth.interceptor.BearerTokenResolver;
import com.opsdesk.auth.service.AuthSessionService;
import com.opsdesk.auth.vo.LoginUserVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentService;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 会话 Service 实现（工单 D1-02）
 *
 * <p>规格依据：API 文档 §4.2（当前用户）/ §4.3（登出）、规格基线 §23.2。
 */
@Slf4j
@Service
public class AuthSessionServiceImpl implements AuthSessionService {

    private final UserService userService;
    private final DepartmentService departmentService;
    private final JwtHelper jwtHelper;
    private final StringRedisTemplate redisTemplate;
    private final BearerTokenResolver tokenResolver;

    public AuthSessionServiceImpl(UserService userService,
                                  DepartmentService departmentService,
                                  JwtHelper jwtHelper,
                                  StringRedisTemplate redisTemplate,
                                  BearerTokenResolver tokenResolver) {
        this.userService = userService;
        this.departmentService = departmentService;
        this.jwtHelper = jwtHelper;
        this.redisTemplate = redisTemplate;
        this.tokenResolver = tokenResolver;
    }

    // ==================== /me ====================

    @Override
    public LoginUserVO currentUser() {
        Long userId = UserContext.requireUserId();

        // user / department 各 1 条，命中主键；deleted = 0 由 @TableLogic 自动追加
        User user = userService.getById(userId);
        if (user == null) {
            // token 合法但用户已被删（逻辑删除）—— 当未登录处理
            log.warn("[当前用户] token 合法但用户不存在或已删除。userId={}", userId);
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        LoginUserVO vo = new LoginUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setDepartmentName(loadDepartmentName(user.getDepartmentId()));
        vo.setStatus(user.getStatus());
        // 角色 / 权限：本请求的拦截器刚查过库并放进了 UserContext，这里不再查库。
        // ⚠️ 代价：permissions 的顺序是字典序（UserContext 内部是 Set），
        //    与登录接口的 (sort, id) 顺序可能不同 —— 集合语义，不影响判定。
        vo.setRoles(UserContext.getRoles().stream().sorted().map(Role::name).toList());
        vo.setPermissions(UserContext.getPermissions().stream().sorted().toList());
        return vo;
    }

    /** 部门名；未设部门或部门已删除时返回 null */
    private String loadDepartmentName(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentService.getById(departmentId);
        return department == null ? null : department.getName();
    }

    // ==================== /logout ====================

    @Override
    public void logout(String authorizationHeader) {
        String token = tokenResolver.extract(authorizationHeader);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        JwtHelper.JwtPayload payload = jwtHelper.parse(token);
        if (payload == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        long ttlMillis = payload.remainingMillis();
        if (ttlMillis <= 0) {
            // 已经过期的 token 不需要吊销：它本来就通不过校验
            log.debug("[登出] token 已过期，无需写黑名单。jti={}", payload.jti());
            return;
        }

        // TTL = token 剩余有效期（§23.2）。到期即自动清理，黑名单不会无限增长
        redisTemplate.opsForValue().set(
                AuthInterceptor.TOKEN_BLACKLIST_PREFIX + payload.jti(),
                AuthInterceptor.TOKEN_BLACKLIST_VALUE,
                ttlMillis, TimeUnit.MILLISECONDS);

        log.info("[登出] userId={} jti={} 黑名单 TTL={}ms", payload.userId(), payload.jti(), ttlMillis);
    }
}

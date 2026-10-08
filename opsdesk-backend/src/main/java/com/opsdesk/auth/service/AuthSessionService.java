package com.opsdesk.auth.service;

import com.opsdesk.auth.vo.LoginUserVO;

/**
 * 会话 Service（工单 D1-02）
 *
 * <p>与 {@link AuthService}（登录，D1-01）分开的理由：D1-02 的「允许改动」
 * 只覆盖 {@code auth/controller/}，不能回头改 D1-01 已验收的
 * {@code AuthService} / {@code AuthServiceImpl}。所以会话相关的两个操作
 * （{@code /me}、{@code /logout}）独立成接口 —— 顺带也是干净的分工：
 * 登录 = 建立会话，本接口 = 查询与销毁会话。
 */
public interface AuthSessionService {

    /**
     * 当前登录用户（API 文档 §4.2）。
     *
     * <p>用户 id 取 {@code UserContext}；角色与权限取本请求拦截器刚加载进
     * {@code UserContext} 的那一份，<b>不重复查库</b>。
     *
     * @return 与登录接口同一结构的 {@link LoginUserVO}
     * @throws com.opsdesk.common.BizException 未登录（40100）或用户已不存在（40100）
     */
    LoginUserVO currentUser();

    /**
     * 登出（API 文档 §4.3）：把当前 token 的 {@code jti} 写进 Redis 黑名单
     * {@code auth:token:{jti}}，TTL = token 剩余有效期（规格基线 §23.2）。
     *
     * <p>不返回任何内容。重复登出会先被鉴权拦截器按黑名单拒掉（40100），
     * 这是<b>有意为之</b> —— 该 token 已失效，不是幂等失败。
     *
     * @param authorizationHeader 原始 {@code Authorization} 头（形如 {@code Bearer xxx}）。
     *                            收原始头而不是从 {@code UserContext} 取，是因为写黑名单
     *                            需要 token 的 <b>剩余有效期</b>，而 {@code UserContext.CurrentUser}
     *                            里没有 {@code exp}，只能重新解析一次。
     * @throws com.opsdesk.common.BizException token 缺失或不可解析（40100）
     */
    void logout(String authorizationHeader);
}

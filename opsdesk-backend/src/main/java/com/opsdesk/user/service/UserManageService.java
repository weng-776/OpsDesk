package com.opsdesk.user.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.user.dto.UserCreateDTO;
import com.opsdesk.user.dto.UserQuery;
import com.opsdesk.user.dto.UserRoleDTO;
import com.opsdesk.user.dto.UserStatusDTO;
import com.opsdesk.user.dto.UserUpdateDTO;
import com.opsdesk.user.vo.UserVO;

/**
 * 用户管理业务 Service（工单 D1-03）
 *
 * <p>规格依据：API 文档 §5.1–§5.7、规格基线 §21.2（逻辑删除 + 删除前校验未关闭工单）。
 *
 * <p><b>为什么不把方法加在生成的 {@code UserService} 上</b>：
 * {@code tools/gen_entities.py} 会<b>无条件覆盖</b> {@code user/service/UserService.java}
 * 与 {@code impl/UserServiceImpl.java}（脚本 L359-365 直接 {@code open(path, "w")}，
 * 只有 {@code --check} 模式才不写），往生成物里加方法下次重跑脚本就静默丢失。
 * 而生成物自带的 javadoc 却写着「业务方法请直接加在本接口上」—— 两者自相矛盾。
 * 所以业务方法落在本接口，生成的 {@code UserService} 只当裸 CRUD 用。
 *
 * <p>⚠️ 本工单<b>先不加权限注解</b>（按工单 §3 依赖说明）；`@RequirePermission("user:list")`
 * 等由 D2-02 完成后回来补。
 */
public interface UserManageService {

    /**
     * 用户分页列表（API 文档 §5.1）。
     *
     * <p>{@code departmentId} 筛选<b>含子部门</b>（靠 {@code department.path} 前缀匹配）。
     * 若 {@code departmentId} 指向不存在的部门，返回空页而不是报错。
     *
     * @param query 分页与筛选条件；{@code page} / {@code size} 由 {@code PageQuery} 收敛
     */
    PageResult<UserVO> page(UserQuery query);

    /**
     * 用户详情（API 文档 §5.2）。
     *
     * @throws com.opsdesk.common.BizException 用户不存在（40400）
     */
    UserVO detail(Long id);

    /**
     * 创建用户（API 文档 §5.3）。
     *
     * @return 新用户 ID
     * @throws com.opsdesk.common.BizException 参数非法（40001，如部门不存在 / 角色 ID 无效）；
     *                                         用户名已存在（40900，含「已被逻辑删除」的同名用户）
     */
    Long create(UserCreateDTO dto);

    /**
     * 修改用户（API 文档 §5.4，<b>全量更新</b>）。
     *
     * <p>{@code password} 为空表示不修改密码；{@code roleIds} 为全量覆盖。
     * 因涉及角色变更，会一并清除该用户的权限缓存。
     *
     * @throws com.opsdesk.common.BizException 用户不存在（40400）；参数非法（40001）；用户名冲突（40900）
     */
    void update(Long id, UserUpdateDTO dto);

    /**
     * 修改用户状态（API 文档 §5.5）。
     *
     * <p>按 §5.5 的说明清除权限缓存 {@code auth:perms:{userId}}。
     * ⚠️ 注意：<b>仅清缓存并不能真正拦住已禁用用户的存量 token</b>（详见实现类注释）。
     *
     * @throws com.opsdesk.common.BizException 用户不存在（40400）
     */
    void updateStatus(Long id, UserStatusDTO dto);

    /**
     * 分配角色（API 文档 §5.6，<b>全量覆盖</b>）。
     *
     * <p>清除该用户的权限缓存，使角色变更<b>无需重新登录即生效</b>（§23.2）。
     *
     * @throws com.opsdesk.common.BizException 用户不存在（40400）；角色 ID 无效（40001）
     */
    void assignRoles(Long id, UserRoleDTO dto);

    /**
     * 删除用户（API 文档 §5.7）—— <b>逻辑删除</b>，不做物理删除。
     *
     * <p>删除前校验该用户创建或受理的<b>未关闭</b>工单：有则拒绝。
     * 已删除的用户名<b>不可复用</b>（{@code uk_user_username} 不排除已删记录，见 §5.7）。
     *
     * @throws com.opsdesk.common.BizException 用户不存在（40400）；存在未关闭工单（40900）
     */
    void delete(Long id);
}

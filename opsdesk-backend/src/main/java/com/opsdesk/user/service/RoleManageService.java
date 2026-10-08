package com.opsdesk.user.service;

import com.opsdesk.user.dto.RolePermissionDTO;
import com.opsdesk.user.vo.PermissionNodeVO;
import com.opsdesk.user.vo.RoleVO;

import java.util.List;

/**
 * 角色与权限管理业务 Service（工单 D2-03）
 *
 * <p>规格依据：API 文档 §7.1–§7.3、规格基线 §3.6（权限码约定）、§23.2（权限变更即时生效）。
 *
 * <p><b>为什么不把方法加在生成的 {@code RoleService} / {@code PermissionService} 上</b>：
 * {@code tools/gen_entities.py} 会<b>无条件覆盖</b> {@code user/service/*.java} 与
 * {@code impl/*.java}（脚本 L359-365），往生成物里加方法下次重跑脚本就静默丢失
 * —— 沿用 D1-03 起确立的约定：业务方法一律另建 Service。
 *
 * <p>V1 固定三个系统角色（EMPLOYEE / AGENT / ADMIN），<b>不提供角色的增删接口</b>（§7.1）。
 */
public interface RoleManageService {

    /**
     * 角色列表（API 文档 §7.1）。
     *
     * <p>返回 3 个系统角色，每个带上<b>当前已授予的权限 ID 列表</b>（D2-03 对 §16.5 的追加字段），
     * 供权限分配页回显勾选状态。
     */
    List<RoleVO> listRoles();

    /**
     * 权限树（API 文档 §7.2）。
     *
     * <p>严格按 {@code permission.parent_id} 组装，同级按 {@code sort} 升序。
     * 含 MENU 与 API 两类；<b>按种子数据 API 权限的 parent_id 都是 0，所以它们会是顶层节点</b>
     * （详见 {@link PermissionNodeVO} 的说明）。
     */
    List<PermissionNodeVO> permissionTree();

    /**
     * 设置角色权限（API 文档 §7.3）—— <b>全量覆盖</b>，同一事务内先删后插。
     *
     * <p>变更后<b>批量清除该角色下所有用户的权限缓存</b>（§7.3 / §23.2），
     * 使这些用户<b>无需重新登录</b>，下一次请求的权限立即变化。
     *
     * @param roleId 角色 ID
     * @param dto    {@code permissionIds} 为新的权限 ID 集合；空数组表示清空该角色的全部权限
     * @throws com.opsdesk.common.BizException 角色不存在（40400）；含无效权限 ID（40001）
     */
    void assignPermissions(Long roleId, RolePermissionDTO dto);
}

package com.opsdesk.user.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 角色出参（API 文档 §16.5 + D2-03 追加字段）
 *
 * <p>{@code code} 是字符串（{@code EMPLOYEE} / {@code AGENT} / {@code ADMIN}），
 * 不是枚举 —— 出参 VO 与实体 {@code com.opsdesk.user.entity.Role} 分离，
 * 实体里那个 {@code Role} 是 {@code com.opsdesk.common.enums.Role} 枚举类型。
 *
 * <h2>⚠️ {@code permissionIds} 是 D2-03 对 §16.5 的追加</h2>
 * §16.5 的 {@code RoleVO} 只有 4 个字段，但 §7.3 要求 {@code PUT /api/roles/{id}/permissions}
 * 设置角色权限 —— 前端若拿不到「该角色当前有哪些权限」，权限分配页就没法回显勾选状态，
 * 功能不闭环。所以追加 {@code permissionIds}（<b>加字段，向后兼容</b>）。
 *
 * <p>{@link JsonInclude.Include#NON_NULL}：只有 {@code GET /api/roles}（D2-03）会填充该字段；
 * {@code GET /api/users/{id}} 里的 {@code roles}（D1-03 的 {@code UserVO.roles}）不填充，
 * 此时该字段<b>直接不出现在 JSON 里</b>，而不是吐一个 {@code null} 让前端多一种分支要判。
 * 之所以不在用户详情里也填上：那会给用户列表带来按角色数放大的额外查询，收益为零。
 */
@Data
public class RoleVO {

    /** 角色 ID */
    private Long id;

    /** 角色名称 */
    private String name;

    /** 角色码：EMPLOYEE / AGENT / ADMIN（规格基线 §3.1） */
    private String code;

    /** 说明 */
    private String description;

    /**
     * 该角色当前拥有的权限 ID 列表（升序）。
     *
     * <p>仅 {@code GET /api/roles} 填充；为 {@code null} 时序列化会省略该字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Long> permissionIds;
}

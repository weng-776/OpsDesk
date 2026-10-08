package com.opsdesk.common.constant;

/**
 * 权限码常量（规格基线 §3.6 —— 全项目权限码的唯一定义处）
 *
 * <p>用途：给 {@code @RequirePermission} 引用，避免权限码以裸字符串散落在各个 Controller 上
 * （SOP §6 禁止「散落字符串字面量」）。
 *
 * <p><b>为什么是常量类而不是 enum</b>：§3.6 的权限码是<b>字符串</b>（`menu:ticket:mine`
 * 这种带冒号的多段结构），而且真正的权限集合存在数据库
 * {@code permission} 表里、可以随角色配置变化。枚举适合「取值封闭、编译期穷尽」的场景，
 * 这里两者都不满足 —— 所以用常量类。
 *
 * <p>⚠️ <b>本类的交付归属</b>：按开发工单，权限码常量类是 <b>D2-03</b> 的交付物
 * （D2-03 的「允许改动」里明确列了 `com.opsdesk.common.constant.PermissionCodes`）。
 * 但 D2-02 的 `@RequirePermission` 注解值必须是<b>编译期常量</b>，没有它就编译不过 ——
 * 所以 <b>D2-02 已按 §3.6 把 48 个码全量建好</b>（15 个 MENU + 33 个 API，
 * 与 `OpsDesk_Seed_V1.sql` 灌进 `permission` 表的 48 条<b>逐条一致</b>，
 * 由 {@code PermissionCodesTest} 反射比对守住）。
 *
 * <p>➡️ <b>D2-03 注意：本类已存在且已完整，直接引用即可，不要再建一个。</b>
 * D2-03 只需要把 §3.6 之外的「权限树组装 / 角色权限全量覆盖 / evict」做掉。
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    // ========================================================================
    //  MENU 权限码 —— §3.6 约定：menu:<路由段>[:<子路由段>]
    // ========================================================================

    /** 菜单：Dashboard */
    public static final String MENU_DASHBOARD = "menu:dashboard";

    /** 菜单：工单（父节点） */
    public static final String MENU_TICKET = "menu:ticket";

    /** 菜单：我的工单 */
    public static final String MENU_TICKET_MINE = "menu:ticket:mine";

    /** 菜单：创建工单 */
    public static final String MENU_TICKET_CREATE = "menu:ticket:create";

    /** 菜单：待受理公共池 */
    public static final String MENU_TICKET_POOL = "menu:ticket:pool";

    /** 菜单：工单管理 */
    public static final String MENU_TICKET_MANAGE = "menu:ticket:manage";

    /** 菜单：知识库 */
    public static final String MENU_KNOWLEDGE = "menu:knowledge";

    /** 菜单：系统管理（父节点） */
    public static final String MENU_SYSTEM = "menu:system";

    /** 菜单：系统 / 用户管理 */
    public static final String MENU_SYSTEM_USER = "menu:system:user";

    /** 菜单：系统 / 部门管理 */
    public static final String MENU_SYSTEM_DEPT = "menu:system:dept";

    /** 菜单：系统 / 角色权限 */
    public static final String MENU_SYSTEM_ROLE = "menu:system:role";

    /** 菜单：系统 / SLA 配置 */
    public static final String MENU_SYSTEM_SLA = "menu:system:sla";

    /** 菜单：系统 / 知识库管理 */
    public static final String MENU_SYSTEM_KNOWLEDGE = "menu:system:knowledge";

    /** 菜单：系统 / 审计日志 */
    public static final String MENU_SYSTEM_AUDIT = "menu:system:audit";

    /** 菜单：系统 / 死信管理 */
    public static final String MENU_SYSTEM_MQ = "menu:system:mq";

    // ========================================================================
    //  API 权限码
    // ========================================================================

    // ---------------- 工单（§3.6「# 工单」） ----------------

    /** 创建工单 */
    public static final String TICKET_CREATE = "ticket:create";

    /** 工单列表 */
    public static final String TICKET_LIST = "ticket:list";

    /** 工单详情 */
    public static final String TICKET_DETAIL = "ticket:detail";

    /** 分派工单 */
    public static final String TICKET_ASSIGN = "ticket:assign";

    /** 转派工单 */
    public static final String TICKET_TRANSFER = "ticket:transfer";

    /** 接单 */
    public static final String TICKET_ACCEPT = "ticket:accept";

    /** 处理工单 */
    public static final String TICKET_PROCESS = "ticket:process";

    /** 工单评论 */
    public static final String TICKET_COMMENT = "ticket:comment";

    /** 提交解决 */
    public static final String TICKET_RESOLVE = "ticket:resolve";

    /** 确认关闭 */
    public static final String TICKET_CLOSE = "ticket:close";

    /** 拒绝（重开） */
    public static final String TICKET_REOPEN = "ticket:reopen";

    /** 撤销工单 */
    public static final String TICKET_CANCEL = "ticket:cancel";

    /** 导出工单 */
    public static final String TICKET_EXPORT = "ticket:export";

    // ---------------- 知识库 ----------------

    /** 查看知识库 */
    public static final String KNOWLEDGE_VIEW = "knowledge:view";

    /** 管理知识库 */
    public static final String KNOWLEDGE_MANAGE = "knowledge:manage";

    // ---------------- 用户 / 组织 ----------------

    /** 用户列表 */
    public static final String USER_LIST = "user:list";

    /** 创建用户 */
    public static final String USER_CREATE = "user:create";

    /** 修改用户 */
    public static final String USER_UPDATE = "user:update";

    /** 修改用户状态 */
    public static final String USER_STATUS = "user:status";

    /** 删除用户 */
    public static final String USER_DELETE = "user:delete";

    /** 分配用户角色 */
    public static final String USER_ASSIGN_ROLE = "user:assign-role";

    /** 部门列表 / 部门树 */
    public static final String DEPARTMENT_LIST = "department:list";

    /** 创建部门 */
    public static final String DEPARTMENT_CREATE = "department:create";

    /** 修改部门 */
    public static final String DEPARTMENT_UPDATE = "department:update";

    /** 删除部门 */
    public static final String DEPARTMENT_DELETE = "department:delete";

    /** 角色列表 */
    public static final String ROLE_LIST = "role:list";

    /** 设置角色权限 */
    public static final String ROLE_ASSIGN_PERMISSION = "role:assign-permission";

    /** 权限列表 / 权限树 */
    public static final String PERMISSION_LIST = "permission:list";

    // ---------------- 系统 ----------------

    /** SLA 策略列表 */
    public static final String SLA_LIST = "sla:list";

    /** 修改 SLA 策略 */
    public static final String SLA_UPDATE = "sla:update";

    /** 审计日志查询 */
    public static final String AUDIT_VIEW = "audit:view";

    /** Dashboard 查看 */
    public static final String DASHBOARD_VIEW = "dashboard:view";

    /** 通知查看 */
    public static final String NOTIFICATION_VIEW = "notification:view";
}

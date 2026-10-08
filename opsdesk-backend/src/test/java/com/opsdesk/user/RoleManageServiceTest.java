package com.opsdesk.user;

import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.user.dto.RolePermissionDTO;
import com.opsdesk.user.service.RoleManageService;
import com.opsdesk.user.vo.PermissionNodeVO;
import com.opsdesk.user.vo.RoleVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色 / 权限接口验收测试（工单 D2-03）
 *
 * <p>规格依据：API 文档 §7.1–§7.3、规格基线 §3.6（权限码）、§23.2（变更即时生效）。
 *
 * <h2>覆盖的三条验收</h2>
 * <ol>
 *   <li>权限树与种子数据 48 条权限一致，且 MENU 是真正的树、API 是平铺的</li>
 *   <li>{@code PUT /api/roles/{id}/permissions} 后 {@code role_permission} 被<b>整体替换</b>
 *       （不是追加、不是部分更新）</li>
 *   <li>改完角色权限，<b>该角色下所有用户</b>的权限缓存被清掉，权限立即变化</li>
 * </ol>
 *
 * <p>整类 {@link Transactional} —— 对 {@code role_permission} 的改动跑完自动回滚。
 * 这里可以放心加事务：改动全部走 <b>MyBatis</b>（service），写入会清 MyBatis 一级缓存，
 * 不存在「绕过 ORM 改库后读到旧值」的问题（那是 {@code PermissionLoaderTest} 踩过的坑）。
 * Redis 不参与事务，所以在 {@code @AfterEach} 里显式清理。
 */
@SpringBootTest
@Transactional
class RoleManageServiceTest {

    /** 种子角色：1=EMPLOYEE(15 个权限) / 2=AGENT(20) / 3=ADMIN(48) */
    private static final long ROLE_EMPLOYEE = 1L;
    private static final long ROLE_ADMIN = 3L;

    /** EMPLOYEE 角色下的两个种子用户，用来验证「批量」失效缓存 */
    private static final List<Long> EMPLOYEE_USER_IDS = List.of(4L, 5L);

    @Autowired
    private RoleManageService roleManageService;

    @Autowired
    private PermissionLoader permissionLoader;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        EMPLOYEE_USER_IDS.forEach(userId ->
                redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + userId));
    }

    // ==================== 验收 1：权限树 ====================

    @Test
    @DisplayName("验收1：权限树节点总数 48，与 permission 表逐条一致（无遗漏、无重复）")
    void 权限树覆盖全部48条权限() {
        List<PermissionNodeVO> roots = roleManageService.permissionTree();
        List<PermissionNodeVO> flat = flatten(roots);

        Set<String> codesFromTree = flat.stream().map(PermissionNodeVO::getCode).collect(Collectors.toSet());
        Set<String> codesFromDb = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT code FROM permission", String.class));

        assertThat(flat).as("节点总数").hasSize(48);
        assertThat(codesFromTree).as("树里的权限码与库一致").isEqualTo(codesFromDb);
        assertThat(codesFromTree).as("无重复节点").hasSize(48);
    }

    @Test
    @DisplayName("验收1：MENU 是真三层树（4 根 + 11 子），API 33 条因 parent_id=0 全是顶层节点")
    void 权限树的分层形状符合种子数据() {
        List<PermissionNodeVO> roots = roleManageService.permissionTree();

        List<PermissionNodeVO> menuRoots = roots.stream()
                .filter(n -> "MENU".equals(n.getType())).toList();
        List<PermissionNodeVO> apiRoots = roots.stream()
                .filter(n -> "API".equals(n.getType())).toList();

        assertThat(roots).as("根节点数 = 4 MENU 根 + 33 API 平铺").hasSize(37);
        assertThat(menuRoots).as("MENU 根：工作台/工单中心/知识库/系统管理")
                .hasSize(4)
                .extracting(PermissionNodeVO::getCode)
                .containsExactly("menu:dashboard", "menu:ticket", "menu:knowledge", "menu:system");
        assertThat(apiRoots).as("API 权限在种子数据里 parent_id 全是 0 → 33 个顶层节点")
                .hasSize(33);

        PermissionNodeVO ticketMenu = menuRoots.get(1);
        assertThat(ticketMenu.getCode()).isEqualTo("menu:ticket");
        assertThat(ticketMenu.getChildren()).as("工单中心下 4 个菜单").hasSize(4)
                .extracting(PermissionNodeVO::getCode)
                .containsExactly("menu:ticket:mine", "menu:ticket:create",
                        "menu:ticket:pool", "menu:ticket:manage");

        PermissionNodeVO systemMenu = menuRoots.get(3);
        assertThat(systemMenu.getCode()).isEqualTo("menu:system");
        assertThat(systemMenu.getChildren()).as("系统管理下 7 个菜单").hasSize(7);
        assertThat(systemMenu.getChildren()).allSatisfy(child ->
                assertThat(child.getChildren()).as("第三层是叶子").isEmpty());

        assertThat(menuRoots.get(0).getChildren()).as("工作台是叶子").isEmpty();
    }

    @Test
    @DisplayName("验收1：同级按 sort 升序（系统管理下 用户管理→…→死信管理）")
    void 同级按sort升序() {
        List<PermissionNodeVO> roots = roleManageService.permissionTree();
        PermissionNodeVO systemMenu = roots.stream()
                .filter(n -> "menu:system".equals(n.getCode())).findFirst().orElseThrow();

        assertThat(systemMenu.getChildren()).extracting(PermissionNodeVO::getCode)
                .containsExactly("menu:system:user", "menu:system:dept", "menu:system:role",
                        "menu:system:sla", "menu:system:knowledge", "menu:system:audit",
                        "menu:system:mq");
    }

    // ==================== §7.1 角色列表 ====================

    @Test
    @DisplayName("§7.1：返回 3 个系统角色，permissionIds 与 role_permission 逐条一致")
    void 角色列表带权限ID() {
        List<RoleVO> roles = roleManageService.listRoles();

        assertThat(roles).hasSize(3);
        assertThat(roles).extracting(RoleVO::getCode)
                .containsExactly("EMPLOYEE", "AGENT", "ADMIN");
        assertThat(roles).allSatisfy(role -> {
            assertThat(role.getDescription()).isNotNull();
            assertThat(role.getPermissionIds()).isNotNull();
        });

        // 与库里的关联数对齐：EMPLOYEE 15 / AGENT 20 / ADMIN 48
        assertThat(roles.get(0).getPermissionIds()).hasSize(15);
        assertThat(roles.get(1).getPermissionIds()).hasSize(20);
        assertThat(roles.get(2).getPermissionIds()).hasSize(48);

        assertThat(roles.get(2).getPermissionIds())
                .as("ADMIN 的权限 ID 与 role_permission 表一致（升序）")
                .isSorted()
                .containsExactlyElementsOf(jdbcTemplate.queryForList(
                        "SELECT permission_id FROM role_permission WHERE role_id = ? ORDER BY permission_id",
                        Long.class, ROLE_ADMIN));
    }

    // ==================== 验收 2：全量覆盖 ====================

    @Test
    @DisplayName("验收2：设置角色权限是「整体替换」，不是追加")
    void 设置权限为全量覆盖() {
        Long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_id = ?", Long.class, ROLE_EMPLOYEE);
        assertThat(before).as("前置：EMPLOYEE 原有 15 条").isEqualTo(15L);

        // 只给两个权限 → 结果必须恰好是这两个，旧的 15 条全没了
        roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf(131L, 132L));

        List<Long> after = jdbcTemplate.queryForList(
                "SELECT permission_id FROM role_permission WHERE role_id = ? ORDER BY permission_id",
                Long.class, ROLE_EMPLOYEE);
        assertThat(after).as("整体替换为新集合").containsExactly(131L, 132L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission", Long.class))
                .as("其他角色的关联不受影响（原 83 条 - 15 + 2）").isEqualTo(70L);
    }

    @Test
    @DisplayName("验收2：空数组表示清空该角色的全部权限（§7.3「全量覆盖」的应有语义）")
    void 空数组表示清空() {
        roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_id = ?", Long.class, ROLE_EMPLOYEE))
                .as("该角色权限被清空").isZero();
        assertThat(roleManageService.listRoles().get(0).getPermissionIds()).isEmpty();
    }

    // ==================== 验收 3：改完批量失效缓存 ====================

    @Test
    @DisplayName("验收3：改角色权限后，该角色下所有用户的缓存被清，权限立即变化（不重新登录）")
    void 改完角色权限后受影响用户立即生效() {
        // 1) 先让两个 EMPLOYEE 用户的缓存 warm 成 15 条
        for (Long userId : EMPLOYEE_USER_IDS) {
            assertThat(permissionLoader.load(userId).permissions()).hasSize(15);
            assertThat(redisTemplate.hasKey(PermissionLoader.PERMS_CACHE_PREFIX + userId)).isTrue();
        }

        // 2) 把 EMPLOYEE 角色改成只有 2 个权限（含 user:list，便于断言）
        roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf(131L, 132L));

        // 3) 两个用户的缓存都应被清掉（§7.3 要求「批量清除该角色下所有用户」）
        for (Long userId : EMPLOYEE_USER_IDS) {
            assertThat(redisTemplate.hasKey(PermissionLoader.PERMS_CACHE_PREFIX + userId))
                    .as("userId=%s 的缓存应被清除", userId).isFalse();
        }

        // 4) 下一次 load 直接拿到新权限 —— 用户没有重新登录
        for (Long userId : EMPLOYEE_USER_IDS) {
            assertThat(permissionLoader.load(userId).permissions())
                    .as("userId=%s 立即看到新权限", userId)
                    .containsExactlyInAnyOrder("user:list", "user:create");
        }
    }

    @Test
    @DisplayName("验收3：只失效「该角色下」的用户，别的角色用户缓存不受影响")
    void 只失效受影响角色的用户() {
        String adminCacheKey = PermissionLoader.PERMS_CACHE_PREFIX + 1L;   // admin 是 ADMIN
        permissionLoader.load(1L);
        assertThat(redisTemplate.hasKey(adminCacheKey)).isTrue();

        roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf(131L));

        assertThat(redisTemplate.hasKey(adminCacheKey))
                .as("ADMIN 角色没变 → admin 的缓存不该被动").isTrue();
        redisTemplate.delete(adminCacheKey);
    }

    // ==================== 失败分支 ====================

    @Test
    @DisplayName("角色不存在 → 40400")
    void 角色不存在返回40400() {
        assertThat(catchBiz(() -> roleManageService.assignPermissions(999_999L, dtoOf(131L))).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("含无效权限 ID → 40001，且不产生任何写入")
    void 无效权限ID返回40001() {
        Long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_id = ?", Long.class, ROLE_EMPLOYEE);

        assertThat(catchBiz(() -> roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf(131L, 999_999L)))
                .getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_id = ?", Long.class, ROLE_EMPLOYEE))
                .as("校验失败发生在写入之前").isEqualTo(before);
    }

    @Test
    @DisplayName("重复的权限 ID 会被去重，不产生重复关联（role_permission 是复合主键）")
    void 重复权限ID被去重() {
        roleManageService.assignPermissions(ROLE_EMPLOYEE, dtoOf(131L, 131L, 132L));

        assertThat(jdbcTemplate.queryForList(
                "SELECT permission_id FROM role_permission WHERE role_id = ? ORDER BY permission_id",
                Long.class, ROLE_EMPLOYEE)).containsExactly(131L, 132L);
    }

    // ==================== 工具 ====================

    /**
     * 执行动作并断言它抛 {@link BizException}，返回该异常以便继续断言错误码。
     *
     * <p>手写而不用 {@code catchThrowableOfType}：后者在 AssertJ 3.21 前后参数顺序变过，
     * 升级依赖时会踩坑（与 {@code UserManageDeleteGuardTest} 保持一致的做法）。
     */
    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }

    private RolePermissionDTO dtoOf(Long... ids) {
        RolePermissionDTO dto = new RolePermissionDTO();
        dto.setPermissionIds(new ArrayList<>(List.of(ids)));
        return dto;
    }

    private List<PermissionNodeVO> flatten(List<PermissionNodeVO> nodes) {
        List<PermissionNodeVO> flat = new ArrayList<>();
        for (PermissionNodeVO node : nodes) {
            flat.add(node);
            flat.addAll(flatten(node.getChildren()));
        }
        return flat;
    }
}

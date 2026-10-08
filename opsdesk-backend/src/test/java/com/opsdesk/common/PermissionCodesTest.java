package com.opsdesk.common;

import com.opsdesk.common.constant.PermissionCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限码常量类与种子数据的一致性守卫（工单 D2-02 附带）
 *
 * <h2>为什么值得单独一个测试</h2>
 * {@code PermissionCodes} 是 {@code @RequirePermission} 的取值来源，而真正的权限集合存在
 * {@code permission} 表里（由 {@code OpsDesk_Seed_V1.sql} 灌入）。
 * 这两处一旦漂移，症状是<b>「注解写了一个数据库里不存在的权限码」</b> ——
 * 后果不是报错，而是该接口<b>对所有人都 40300</b>（谁都拿不到那个码），
 * 或者反过来，§3.6 新增了码但常量类漏了，写注解时只能裸写字符串。
 *
 * <p>所以这里用<b>反射</b>把常量类的值和数据库的 48 条码<b>逐条比对</b>：
 * 任何一边改动而另一边没跟上，这个测试立刻红。
 */
@SpringBootTest
class PermissionCodesTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 反射取出 {@code PermissionCodes} 里所有 {@code public static final String} 常量的值 */
    private Set<String> constantValues() {
        Set<String> values = new LinkedHashSet<>();
        for (Field field : PermissionCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                }
                catch (IllegalAccessException ex) {
                    throw new IllegalStateException("读取常量失败：" + field.getName(), ex);
                }
            }
        }
        return values;
    }

    private Set<String> codesOfType(String type) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT code FROM permission WHERE type = ?", String.class, type));
    }

    @Test
    @DisplayName("常量类的值与 permission 表的权限码逐条一致（双向，多一个少一个都算不一致）")
    void 常量与数据库权限码完全一致() {
        Set<String> fromConstants = constantValues();
        Set<String> fromDb = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT code FROM permission", String.class));

        assertThat(fromConstants)
                .as("常量类与 permission 表必须完全一致（缺的：DB 有常量没有；多的：常量有 DB 没有）")
                .containsExactlyInAnyOrderElementsOf(fromDb);
    }

    @Test
    @DisplayName("§3.6 定义 48 个权限码（15 MENU + 33 API），常量类与库都应恰为 48")
    void 权限码总数与规格一致() {
        assertThat(constantValues()).as("§3.6 的权限码总数").hasSize(48);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permission", Long.class))
                .as("种子数据灌入的权限码总数").isEqualTo(48L);
    }

    @Test
    @DisplayName("MENU 码 15 个 / API 码 33 个，且常量类的前缀分组与 permission.type 对得上")
    void MENU与API分组正确() {
        Set<String> fromConstants = constantValues();
        Set<String> menuFromConstants = fromConstants.stream()
                .filter(code -> code.startsWith("menu:")).collect(java.util.stream.Collectors.toSet());
        Set<String> apiFromConstants = fromConstants.stream()
                .filter(code -> !code.startsWith("menu:")).collect(java.util.stream.Collectors.toSet());

        assertThat(menuFromConstants).as("MENU 码").hasSize(15);
        assertThat(apiFromConstants).as("API 码").hasSize(33);

        // 常量类里以 menu: 开头的，必须正好是库里 type = MENU 的那批；反之亦然
        assertThat(menuFromConstants).containsExactlyInAnyOrderElementsOf(codesOfType("MENU"));
        assertThat(apiFromConstants).containsExactlyInAnyOrderElementsOf(codesOfType("API"));
    }

    @Test
    @DisplayName("D1-03/D1-04 用到的 10 个权限码都在常量类里（11 个接口，user:list 被列表与详情共用）")
    void 本工单用到的权限码齐全() {
        List<String> usedByD1AndD2 = List.of(
                PermissionCodes.USER_LIST, PermissionCodes.USER_CREATE, PermissionCodes.USER_UPDATE,
                PermissionCodes.USER_STATUS, PermissionCodes.USER_DELETE, PermissionCodes.USER_ASSIGN_ROLE,
                PermissionCodes.DEPARTMENT_LIST, PermissionCodes.DEPARTMENT_CREATE,
                PermissionCodes.DEPARTMENT_UPDATE, PermissionCodes.DEPARTMENT_DELETE);

        assertThat(constantValues()).containsAll(usedByD1AndD2);
        assertThat(usedByD1AndD2).as("6 个 user:* + 4 个 department:*").hasSize(10);
    }
}

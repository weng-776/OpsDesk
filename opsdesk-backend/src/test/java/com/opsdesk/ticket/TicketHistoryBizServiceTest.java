package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.ticket.service.TicketHistoryBizService;
import com.opsdesk.ticket.vo.TicketHistoryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单历史查询验收测试（工单 D4-05，API 文档 §8.5 / §16.9）
 *
 * <p>种子数据：工单 3 是 {@code WAITING_CONFIRM}（creator=4 emp_wang、assignee=3 agent_li），
 * 它必然走过 {@code ASSIGNED → IN_PROGRESS → WAITING_CONFIRM}，所以自带若干历史行。
 *
 * <p>整类 {@link Transactional}：只读，跑完回滚。
 */
@SpringBootTest
@Transactional
class TicketHistoryBizServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    private static final long TICKET_WAITING_CONFIRM = 3L;

    @Autowired
    private TicketHistoryBizService ticketHistoryBizService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("§8.5：返回 §16.9 的 8 个字段，且按 created_at 升序（+ id tiebreaker）")
    void 历史字段与排序() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);   // 工单 3 的创建人

        List<TicketHistoryVO> list = ticketHistoryBizService.list(TICKET_WAITING_CONFIRM);

        assertThat(list).as("种子数据里工单 3 应有流转历史").isNotEmpty();

        // 升序：每一行的 createdAt 都不早于前一行
        for (int i = 1; i < list.size(); i++) {
            assertThat(list.get(i).getCreatedAt())
                    .as("第 %d 行的时间不应早于第 %d 行", i, i - 1)
                    .isAfterOrEqualTo(list.get(i - 1).getCreatedAt());
        }

        // 字段装配（§16.9 的 8 个）
        TicketHistoryVO first = list.get(0);
        assertThat(first.getId()).isNotNull();
        assertThat(first.getOperatorId()).isNotNull();
        assertThat(first.getOperatorName()).as("operatorName 由 SQL JOIN user 装配").isNotBlank();
        assertThat(first.getAction()).isNotNull();
        assertThat(first.getToStatus()).as("每行都有目标状态").isNotNull();
        assertThat(first.getCreatedAt()).isNotNull();

        // ⚠️ fromStatus 可以为 null —— 首行是 CREATE 动作，「创建」没有来源状态。
        //    所以只能断言「存在某个非 CREATE 的行带 fromStatus」，不能要求每行都非空
        assertThat(list).anyMatch(vo -> vo.getFromStatus() != null);
        // remark 同理：不是所有动作都有备注
    }

    @Test
    @DisplayName("§8.5：operatorName 与列表/评论口径一致（昵称优先，无昵称回落用户名）")
    void 操作人姓名口径一致() {
        setCurrentUser(ADMIN, Role.ADMIN);
        List<TicketHistoryVO> list = ticketHistoryBizService.list(TICKET_WAITING_CONFIRM);
        assertThat(list).isNotEmpty();

        // 与库里直接算出来的显示名逐个核对
        for (TicketHistoryVO vo : list) {
            String expected = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(nickname, username) FROM `user` WHERE id = ?",
                    String.class, vo.getOperatorId());
            assertThat(vo.getOperatorName()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("§8.5：越权访问 → 40301；不存在的工单 → 40400（顺序不能反）")
    void 可见性与存在性() {
        // emp_zhao(5) 看不到工单 3（不是创建人、不是处理人、不在其部门子树、也不在公共池）
        setCurrentUser(EMP_ZHAO, Role.EMPLOYEE);
        assertThat(catchBiz(() -> ticketHistoryBizService.list(TICKET_WAITING_CONFIRM)))
                .as("越权 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 不存在的 id 一律 40400（不能因为「先判可见性」变成 40301，否则泄露存在性）
        assertThat(catchBiz(() -> ticketHistoryBizService.list(999_999L)))
                .as("不存在 → 40400").isEqualTo(ErrorCode.NOT_FOUND);

        // 处理人本人可见
        setCurrentUser(3L, Role.AGENT);
        assertThat(ticketHistoryBizService.list(TICKET_WAITING_CONFIRM)).isNotEmpty();
    }

    @Test
    @DisplayName("§8.5：ADMIN 能看任意工单的历史")
    void 管理员看全部() {
        setCurrentUser(ADMIN, Role.ADMIN);
        for (long id = 1L; id <= 5L; id++) {
            assertThat(ticketHistoryBizService.list(id)).as("工单 %d 的历史", id).isNotNull();
        }
    }

    @Test
    @DisplayName("越权与不存在不能混：数据范围受限的用户查不存在的 id 也是 40400")
    void 不泄露存在性() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(catchBiz(() -> ticketHistoryBizService.list(999_999L)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== 工具 ====================

    private void setCurrentUser(long userId, Role role) {
        Long deptId = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId)
                .stream().findFirst().orElse(null);
        UserContext.set(new UserContext.CurrentUser(userId, "test-jti", Set.of(role), Set.of(), deptId));
    }

    private static ErrorCode catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex.getErrorCode();
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }
}

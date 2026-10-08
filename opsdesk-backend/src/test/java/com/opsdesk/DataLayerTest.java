package com.opsdesk;

import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据层地基验收：证明生成的 entity / mapper 真能读写数据库（不只是「能编译」）。
 *
 * <p>整类 {@link Transactional} —— 每条用例跑完自动回滚，**不会污染种子数据**。
 *
 * <p>验证三件事：
 * <ol>
 *   <li><b>枚举字段往返</b>：§3 的枚举 ↔ DB 里的 VARCHAR 能正确互转</li>
 *   <li><b>逻辑删除</b>：{@code @TableLogic} 让删除变成 UPDATE，且查询自动带 {@code deleted = 0}</li>
 *   <li><b>时间戳归数据库维护</b>：应用不写 {@code created_at}，DB 默认值也要填上</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class DataLayerTest {

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 枚举字段能与数据库正确往返() {
        Ticket t = new Ticket();
        t.setTicketNo("OD2099010100001");
        t.setTitle("VPN 连不上");
        t.setDescription("连上后立刻掉线");
        t.setType(TicketType.INCIDENT);
        t.setCategory(TicketCategory.NETWORK);
        t.setPriority(TicketPriority.P2);
        t.setStatus(TicketStatus.OPEN);
        t.setSource(TicketSource.WEB);
        t.setCreatorId(1L);
        t.setSlaResponseState(SlaState.NORMAL);
        t.setSlaResolutionState(SlaState.NORMAL);

        assertThat(ticketMapper.insert(t)).isEqualTo(1);
        assertThat(t.getId()).as("自增主键回填").isNotNull();

        // ⚠️ 已知行为（不是 bug）：MyBatis-Plus 的 insert **不会**把 DB 默认值回填到对象
        //    —— MySQL 没有 RETURNING。created_at / updated_at 由 DB 维护，
        //    所以 insert 后对象里是 null，必须重新查一次才有值。
        //    工单创建流程（Day 3）需要它们算 SLA 截止时间，注意要 re-select。
        assertThat(t.getCreatedAt()).as("insert 后对象里仍为 null").isNull();

        Ticket loaded = ticketMapper.selectById(t.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCreatedAt()).as("created_at 已由 DB 默认值写入").isNotNull();
        assertThat(loaded.getUpdatedAt()).as("updated_at 已由 DB 默认值写入").isNotNull();
        assertThat(loaded.getType()).isEqualTo(TicketType.INCIDENT);
        assertThat(loaded.getCategory()).isEqualTo(TicketCategory.NETWORK);
        assertThat(loaded.getPriority()).isEqualTo(TicketPriority.P2);
        assertThat(loaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(loaded.getSource()).isEqualTo(TicketSource.WEB);
        assertThat(loaded.getSlaResponseState()).isEqualTo(SlaState.NORMAL);
        assertThat(loaded.getTicketNo()).isEqualTo("OD2099010100001");

        // 直接读原始列，确认存进去的就是枚举的 code 而不是 ordinal
        String rawType = jdbcTemplate.queryForObject(
                "select type from ticket where id = ?", String.class, t.getId());
        assertThat(rawType).as("落库的是 code，不是 ordinal").isEqualTo("INCIDENT");
    }

    @Test
    void 逻辑删除只打标记且查询自动过滤() {
        User u = new User();
        u.setUsername("probe_del_" + System.nanoTime());
        u.setPassword("not-a-real-hash");
        u.setNickname("探针用户");
        u.setStatus(1);
        assertThat(userMapper.insert(u)).isEqualTo(1);
        Long id = u.getId();

        long before = userMapper.selectCount(null);

        // @TableLogic → 实际执行 UPDATE user SET deleted = 1 WHERE id = ?
        assertThat(userMapper.deleteById(id)).isEqualTo(1);

        assertThat(userMapper.selectById(id)).as("逻辑删除后 selectById 应为 null").isNull();
        assertThat(userMapper.selectCount(null)).as("查询自动带 deleted = 0，总数减 1").isEqualTo(before - 1);

        Integer deleted = jdbcTemplate.queryForObject(
                "select deleted from user where id = ?", Integer.class, id);
        assertThat(deleted).as("行还在，只是被标记 deleted = 1").isEqualTo(1);
    }

    @Test
    void 种子数据可读且数量符合预期() {
        // 5 演示账号 / 3 角色 / 48 权限码 / 5 工单 / 3 条 SLA 策略（见 OpsDesk_Seed_V1.sql）
        assertThat(userMapper.selectCount(null)).isEqualTo(5L);
        assertThat(ticketMapper.selectCount(null)).isEqualTo(5L);
    }
}

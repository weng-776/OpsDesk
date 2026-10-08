package com.opsdesk.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MybatisPlusConfiguration} 验收：证明插件**真的生效**，而不是「配置类存在」。
 *
 * <p>整类 {@link Transactional} —— 跑完自动回滚，不污染种子数据。
 *
 * <p>验证四件事：
 * <ol>
 *   <li>Mapper 无需 {@code @MapperScan} 即被自动注册（回答「为什么没配扫包也能跑」）</li>
 *   <li>分页拦截器生成 {@code LIMIT} 并回填 {@code total}（不注册时 total 恒为 0）</li>
 *   <li>{@code maxLimit} 兜底生效</li>
 *   <li>乐观锁：过期版本号的更新必须失败（§8 工单流转并发安全的前提）</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class MybatisPlusConfigurationTest {

    /** 17 张业务表，每表一个 Mapper（见 tools/gen_entities.py） */
    private static final int MAPPER_COUNT = 17;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 证明「没有 @MapperScan 也能跑」：Mapper 接口全部由
     * MybatisPlusAutoConfiguration 的自动扫描器（按 {@code @Mapper} 注解过滤）注册。
     */
    @Test
    void Mapper无需MapperScan即被自动注册() {
        var mappers = applicationContext.getBeansOfType(BaseMapper.class);
        System.out.println("[实测] 自动注册的 Mapper 数量 = " + mappers.size());
        assertThat(mappers).as("17 张表各一个 Mapper").hasSize(MAPPER_COUNT);
        assertThat(mappers.values())
                .as("TicketMapper 必须在其中")
                .anyMatch(m -> m instanceof TicketMapper);
    }

    /**
     * 分页拦截器生效：{@code total} 由额外的 count 查询回填，当页只返回 size 条。
     * <p>若拦截器缺失，{@code getTotal()} 会是 0，且 records 会包含全部 5 条。
     */
    @Test
    void 分页拦截器生效_总数与当页条数均正确() {
        Page<Ticket> page = new Page<>(1, 2);
        Page<Ticket> result = ticketMapper.selectPage(page, null);

        System.out.println("[实测] 分页 total = " + result.getTotal()
                + ", 当页记录数 = " + result.getRecords().size()
                + ", pages = " + result.getPages());

        assertThat(result.getTotal()).as("种子数据 5 张工单").isEqualTo(5L);
        assertThat(result.getRecords()).as("size=2 只返回 2 条").hasSize(2);
        assertThat(result.getPages()).isEqualTo(3L);
    }

    /**
     * {@code maxLimit} 兜底：size 传 1000 会被静默收敛到 500。
     * <p>注意断言的是 {@code page.getSize()}——拦截器会把它改写成收敛后的值。
     */
    @Test
    void 分页size超过maxLimit时被收敛() {
        Page<Ticket> page = new Page<>(1, 1000);
        ticketMapper.selectPage(page, null);

        System.out.println("[实测] 请求 size=1000 → 实际生效 size = " + page.getSize());
        assertThat(page.getSize()).as("被 maxLimit=500 收敛").isEqualTo(500L);
    }

    /**
     * 乐观锁：拿着已过期的版本号更新必须失败。
     *
     * <p>这是 §8 要求的「工单状态流转并发安全」的底座——两人同时 accept 同一工单时，
     * 后到的那次 {@code updateById} 影响行数为 0，业务层据此返回 {@code 40900}。
     */
    @Test
    void 乐观锁_过期版本号的更新失败() {
        Ticket t = new Ticket();
        t.setTicketNo("OD2099010100099");
        t.setTitle("乐观锁探针");
        t.setDescription("验证 @Version 是否真的生效");
        t.setType(TicketType.INCIDENT);
        t.setCategory(TicketCategory.NETWORK);
        t.setPriority(TicketPriority.P2);
        t.setStatus(TicketStatus.OPEN);
        t.setSource(TicketSource.WEB);
        t.setCreatorId(1L);
        t.setSlaResponseState(SlaState.NORMAL);
        t.setSlaResolutionState(SlaState.NORMAL);
        assertThat(ticketMapper.insert(t)).isEqualTo(1);
        Long id = t.getId();

        // 请求 A：读到 version = 0，先到，改成功（版本 0 → 1）
        Ticket reqA = ticketMapper.selectById(id);
        assertThat(reqA.getVersion()).as("DDL 默认值 version = 0").isZero();
        reqA.setTitle("先到的改动");
        assertThat(ticketMapper.updateById(reqA)).as("版本匹配，更新成功").isEqualTo(1);

        // 请求 B：也是基于 version = 0（在 A 提交之前就读到了），后到，必须改不动。
        //
        // ⚠️ 这里**不能**再用 selectById 取第二个对象 —— 同一 SqlSession 内 MyBatis 一级缓存
        //    对相同语句+相同参数会返回**同一个对象实例**（下面「同一事务内重复查询…」用例已实测），
        //    那样两次改动其实落在同一个对象上，根本测不出并发。
        //    所以手工构造「陈旧快照」，等价于另一个请求早先读到的副本。
        Ticket reqB = new Ticket();
        reqB.setId(id);
        reqB.setVersion(0);
        reqB.setTitle("后到的改动");
        int affected = ticketMapper.updateById(reqB);
        System.out.println("[实测] 陈旧版本号的 updateById 影响行数 = " + affected);
        assertThat(affected).as("版本已过期，影响行数为 0").isZero();

        Ticket reloaded = ticketMapper.selectById(id);
        assertThat(reloaded.getVersion()).as("版本自增到 1").isEqualTo(1);
        assertThat(reloaded.getTitle()).as("库里是先到的那次改动").isEqualTo("先到的改动");
    }

    /**
     * 记录一个会咬人的框架行为：<b>同一事务内重复查同一条记录，拿到的是同一个对象</b>
     * （MyBatis 一级缓存 / SqlSession 级缓存）。
     *
     * <p>⚠️ Day 4 写状态流转时注意：若在同一事务里「查出来判断 → 更新 → 再查一次拿最新值」，
     * 第二次查询可能命中缓存返回<b>旧对象</b>；而且它和第一次是同一引用，
     * 改其中任何一个都会同时影响另一个。需要新快照时用
     * {@code SqlSession.clearCache()}、换一个事务，或重新 {@code new} 一个对象。
     */
    @Test
    void 同一事务内重复查询返回同一对象实例() {
        Long id = ticketMapper.selectList(null).get(0).getId();
        Ticket a = ticketMapper.selectById(id);
        Ticket b = ticketMapper.selectById(id);
        System.out.println("[实测] 同一事务内两次 selectById 是同一实例 = " + (a == b));
        assertThat(a).as("MyBatis 一级缓存，返回同一引用").isSameAs(b);
    }
}

package com.opsdesk.notification;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.common.enums.Role;
import com.opsdesk.notification.dto.NotificationQuery;
import com.opsdesk.notification.service.NotificationBizService;
import com.opsdesk.notification.service.NotificationEvent;
import com.opsdesk.notification.service.NotificationService;
import com.opsdesk.notification.vo.NotificationVO;
import com.opsdesk.notification.vo.ReadAllResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 站内通知验收测试（工单 D6-03，API 文档 §12.1 ~ §12.4 / §16.15）
 *
 * <p>{@code @Transactional}：整类跑完自动回滚，不会改动种子通知的已读状态。
 *
 * <h2>种子通知（OpsDesk_Seed_V1.sql）</h2>
 * <pre>
 * id=1  user=2  未读   2026-10-06 08:00
 * id=2  user=3  已读   2026-10-06 10:20
 * id=3  user=4  未读   2026-10-06 14:00
 * id=4  user=3  未读   2026-10-06 16:00
 * </pre>
 * 所以：<b>user 3 有 2 条（1 未读）</b>、user 2 有 1 条未读、user 4 有 1 条未读。
 */
@SpringBootTest
@Transactional
class NotificationBizServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;

    /** 种子里属于 user 3 的两条 */
    private static final long NOTIF_OWN_READ = 2L;
    private static final long NOTIF_OWN_UNREAD = 4L;

    /** 种子里属于 user 2 的那条（用于越权用例） */
    private static final long NOTIF_OTHERS = 1L;

    @Autowired
    private NotificationBizService notificationBizService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：未读数 / 标记已读 / 全部已读 ====================

    @Test
    @DisplayName("验收1：未读数正确 → 标记已读减 1 → read-all 归零")
    void 未读数与标记已读() {
        setCurrentUser(AGENT_LI);

        assertThat(notificationBizService.unreadCount().getCount())
                .as("种子里 user 3 有 2 条通知，其中 1 条未读").isEqualTo(1L);

        notificationBizService.markRead(NOTIF_OWN_UNREAD);

        assertThat(notificationBizService.unreadCount().getCount())
                .as("标记已读后未读数减 1").isZero();
        assertThat(readFlagOf(NOTIF_OWN_UNREAD)).isEqualTo(1);

        // 已经没有未读了 → read-all 影响 0 行（幂等，不报错）
        ReadAllResultVO again = notificationBizService.readAll();
        assertThat(again.getAffected()).as("没有未读时 read-all 影响 0 行").isZero();
    }

    @Test
    @DisplayName("验收1：read-all 把当前用户的所有未读一次清掉，返回 affected")
    void 全部已读() {
        setCurrentUser(AGENT_ZHANG);

        assertThat(notificationBizService.unreadCount().getCount()).isEqualTo(1L);

        ReadAllResultVO result = notificationBizService.readAll();

        assertThat(result.getAffected()).as("user 2 恰好 1 条未读").isEqualTo(1);
        assertThat(notificationBizService.unreadCount().getCount()).as("归零").isZero();
        assertThat(readFlagOf(NOTIF_OTHERS)).isEqualTo(1);
    }

    @Test
    @DisplayName("验收1：read-all 只影响【自己】的通知，别人的一条都不动")
    void 全部已读不越界() {
        setCurrentUser(AGENT_LI);   // user 3

        notificationBizService.readAll();

        assertThat(notificationBizService.unreadCount().getCount()).isZero();
        // user 2 / user 4 的未读通知必须原封不动
        assertThat(readFlagOf(NOTIF_OTHERS)).as("user 2 的通知未被波及").isZero();
        assertThat(readFlagOf(3L)).as("user 4 的通知未被波及").isZero();
    }

    @Test
    @DisplayName("标记已读是幂等的：已读的再标一次不报错")
    void 标记已读幂等() {
        setCurrentUser(AGENT_LI);

        notificationBizService.markRead(NOTIF_OWN_READ);   // 种子本来就是已读

        assertThat(readFlagOf(NOTIF_OWN_READ)).isEqualTo(1);
    }

    // ==================== 验收 2：越权 ====================

    @Test
    @DisplayName("验收2：标记【他人】的通知 → 40301；不存在的 id → 40400")
    void 越权标记被拒() {
        setCurrentUser(AGENT_LI);   // user 3

        assertThatThrownBy(() -> notificationBizService.markRead(NOTIF_OTHERS))
                .as("id=1 属于 user 2").isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        assertThatThrownBy(() -> notificationBizService.markRead(999_999L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("验收2：列表只返回自己的通知（接口没有 userId 入参，天然无法越权）")
    void 列表只含自己的通知() {
        setCurrentUser(AGENT_LI);

        PageResult<NotificationVO> result = notificationBizService.page(new NotificationQuery());

        assertThat(result.getTotal()).as("user 3 恰好 2 条").isEqualTo(2);
        assertThat(result.getList()).extracting(NotificationVO::getId)
                .as("只含 id=2 与 id=4").containsExactlyInAnyOrder(NOTIF_OWN_READ, NOTIF_OWN_UNREAD);
    }

    // ==================== 验收 3：notify 落库后能查到 ====================

    @Test
    @DisplayName("验收3：notify(...) 落库后能在列表里查到，未读数同步 +1")
    void notify落库后可查() {
        setCurrentUser(AGENT_LI);
        long unreadBefore = notificationBizService.unreadCount().getCount();

        notificationService.notify(AGENT_LI, NotificationType.SLA_BREACHED,
                "工单已超时", "工单 OD2026100600005 已超过解决时限。", "TICKET", 5L);

        assertThat(notificationBizService.unreadCount().getCount())
                .as("新通知默认未读 → 未读数 +1").isEqualTo(unreadBefore + 1);

        PageResult<NotificationVO> result = notificationBizService.page(new NotificationQuery());
        NotificationVO latest = result.getList().get(0);
        assertThat(latest.getType()).isEqualTo(NotificationType.SLA_BREACHED);
        assertThat(latest.getTitle()).isEqualTo("工单已超时");
        assertThat(latest.getBizType()).isEqualTo("TICKET");
        assertThat(latest.getBizId()).isEqualTo(5L);
        assertThat(latest.isReadFlag()).as("新通知是未读").isFalse();
        assertThat(latest.getCreatedAt()).as("created_at 由 DB 默认值填").isNotNull();
    }

    @Test
    @DisplayName("notify：标题超过 VARCHAR(255) 会被截断，不让整条通知失败")
    void 超长标题被截断() {
        String longTitle = "T".repeat(300);

        notificationService.notify(EMP_WANG, NotificationType.TICKET_COMMENT, longTitle,
                "正文", "TICKET", 1L);

        String stored = jdbcTemplate.queryForObject(
                "SELECT title FROM notification WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, EMP_WANG);
        assertThat(stored).hasSize(255).isEqualTo("T".repeat(255));
    }

    @Test
    @DisplayName("notify：接收人缺失时只记日志、不抛异常（通知不该拖垮主业务）")
    void 接收人缺失不抛异常() {
        notificationService.notify(null, NotificationType.TICKET_COMMENT, "标题", "正文", "TICKET", 1L);
        // 走到这里就说明没抛
        assertThat(notificationService.count()).isGreaterThanOrEqualTo(0);
    }

    // ==================== 列表筛选与出参（§16.15）====================

    @Test
    @DisplayName("列表：readFlag 筛选生效；不传时未读已读都要")
    void 列表筛选与排序() {
        setCurrentUser(AGENT_LI);

        NotificationQuery unreadOnly = new NotificationQuery();
        unreadOnly.setReadFlag(false);
        PageResult<NotificationVO> unread = notificationBizService.page(unreadOnly);
        assertThat(unread.getList()).extracting(NotificationVO::getId).containsExactly(NOTIF_OWN_UNREAD);
        assertThat(unread.getList()).allSatisfy(vo -> assertThat(vo.isReadFlag()).isFalse());

        NotificationQuery readOnly = new NotificationQuery();
        readOnly.setReadFlag(true);
        PageResult<NotificationVO> read = notificationBizService.page(readOnly);
        assertThat(read.getList()).extracting(NotificationVO::getId).containsExactly(NOTIF_OWN_READ);
        assertThat(read.getList()).allSatisfy(vo -> assertThat(vo.isReadFlag()).isTrue());

        // 不传 → 两条都在，且按时间倒序（16:00 在前）
        PageResult<NotificationVO> all = notificationBizService.page(new NotificationQuery());
        assertThat(all.getList()).extracting(NotificationVO::getId)
                .containsExactly(NOTIF_OWN_UNREAD, NOTIF_OWN_READ);
    }

    @Test
    @DisplayName("出参：§16.15 恰好 8 个字段；readFlag 是 boolean 不是 0/1")
    void 出参字段与类型() throws Exception {
        setCurrentUser(AGENT_LI);

        NotificationVO vo = notificationBizService.page(new NotificationQuery()).getList().get(0);

        Set<String> fields = java.util.Arrays.stream(NotificationVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(fields).as("§16.15 恰好这 8 个").containsExactlyInAnyOrder(
                "id", "type", "title", "content", "bizType", "bizId", "readFlag", "createdAt");

        // 库里是 TINYINT，出参必须是 boolean —— 直接断言类型，防止有人改回 Integer
        assertThat(NotificationVO.class.getDeclaredField("readFlag").getType())
                .as("§16.15 要求 boolean").isEqualTo(boolean.class);
    }

    // ==================== §24.3 的 ApplicationEvent 落点 ====================

    @Test
    @DisplayName("§24.3 降级方案：发布 NotificationEvent → 监听器落到 notify → 通知可查")
    void 事件落点可用() {
        setCurrentUser(EMP_WANG);
        long unreadBefore = notificationBizService.unreadCount().getCount();

        eventPublisher.publishEvent(new NotificationEvent(EMP_WANG, NotificationType.TICKET_ASSIGNED,
                "工单已分派给你", "工单 OD2026100600001 已分派给你处理。", "TICKET", 1L));

        assertThat(notificationBizService.unreadCount().getCount())
                .as("事件被监听器接住并落了库").isEqualTo(unreadBefore + 1);

        NotificationVO latest = notificationBizService.page(new NotificationQuery()).getList().get(0);
        assertThat(latest.getType()).isEqualTo(NotificationType.TICKET_ASSIGNED);
        assertThat(latest.getBizId()).isEqualTo(1L);
    }

    // ==================== 工具 ====================

    private int readFlagOf(long notificationId) {
        Integer flag = jdbcTemplate.queryForObject(
                "SELECT read_flag FROM notification WHERE id = ?", Integer.class, notificationId);
        return flag == null ? 0 : flag;
    }

    private void setCurrentUser(long userId) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "d603-jti", Set.of(Role.EMPLOYEE), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}

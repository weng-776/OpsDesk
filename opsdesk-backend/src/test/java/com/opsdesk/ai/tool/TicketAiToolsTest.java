package com.opsdesk.ai.tool;

import com.opsdesk.common.BizException;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.ticket.vo.TicketListVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D11-01 验收：AI 工具集的入参、身份落点与越权处理
 *
 * <h2>为什么是【纯单测】</h2>
 * 工具方法是普通方法，本身不依赖 Spring 容器 / DB / Redis。用 mock 直接调方法，
 * 比 {@code @SpringBootTest}（要起 MySQL + Redis + RabbitMQ）快得多，也更聚焦 ——
 * 「不经过 LLM 直接测工具」正是工单验收 1 的要求。
 */
class TicketAiToolsTest {

    private static final long EMP_WANG_ID = 4L;
    private static final long OWN_TICKET_ID = 1L;
    private static final long OTHER_TICKET_ID = 999L;

    private TicketQueryService ticketQueryService;
    private TicketCreateService ticketCreateService;
    private TicketAiTools tools;

    @BeforeEach
    void setUp() {
        ticketQueryService = mock(TicketQueryService.class);
        ticketCreateService = mock(TicketCreateService.class);
        tools = new TicketAiTools(currentUser(EMP_WANG_ID), ticketQueryService, ticketCreateService);

        // 模拟 AI 线程池线程的真实处境：调用工具时线程上【没有任何身份】
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：查自己的有数据 / 查他人的返回「无权查看该工单」 ====================

    @Test
    @DisplayName("验收1：queryTicket 查自己的工单 → 返回工单信息")
    void 查自己的工单有数据() {
        when(ticketQueryService.detail(OWN_TICKET_ID)).thenReturn(detailVo(OWN_TICKET_ID, "OD20261012-0001"));

        String text = tools.queryTicket(String.valueOf(OWN_TICKET_ID));

        assertThat(text).contains("OD20261012-0001")
                .contains("VPN 连不上")
                .contains("待受理")
                .contains("P2");
    }

    @Test
    @DisplayName("验收1：queryTicket 查他人工单 → 「无权查看该工单」（不泄露存在性）")
    void 查他人工单无权查看() {
        when(ticketQueryService.detail(anyLong()))
                .thenThrow(BizException.dataScopeDenied("数据范围越权"));

        String text = tools.queryTicket(String.valueOf(OTHER_TICKET_ID));

        assertThat(text).as("§11.3：越权必须返回这句话，且不能出现「不存在」之类的存在性线索")
                .isEqualTo("无权查看该工单");
    }

    @Test
    @DisplayName("验收1 补充：ID 传了非数字 → 友好提示，且不碰业务服务")
    void ID非数字给提示() {
        String text = tools.queryTicket("OD20261012-0001");

        assertThat(text).contains("必须是数字");
        verifyNoInteractions(ticketQueryService);
    }

    @Test
    @DisplayName("验收1 补充：工单不存在 → 与越权返回不同文案（两者语义必须能分清）")
    void 工单不存在() {
        when(ticketQueryService.detail(anyLong())).thenThrow(BizException.notFound("工单不存在"));

        String text = tools.queryTicket("12345");

        assertThat(text).contains("没有找到这张工单").isNotEqualTo("无权查看该工单");
    }

    // ==================== 验收 2：queryMyTickets 无参 ====================

    @Test
    @DisplayName("验收2：queryMyTickets 零参数（「My」由 UserContext 决定，LLM 无法干预）")
    void 我的工单无参() {
        Method method = toolMethod("queryMyTickets");

        assertThat(method.getParameterCount()).as("一旦加参数，LLM 就能填别人的 userId").isZero();
    }

    @Test
    @DisplayName("验收2 补充：queryMyTickets 限制条数并在结果里说明")
    void 我的工单限制条数() {
        TicketListVO one = new TicketListVO();
        one.setId(1L);
        one.setTicketNo("OD20261012-0001");
        one.setTitle("VPN 连不上");
        one.setStatus(TicketStatus.OPEN);
        one.setPriority(TicketPriority.P2);
        when(ticketQueryService.mine(any())).thenReturn(PageResult.of(List.of(one), 25L, 1L, 10L));

        String text = tools.queryMyTickets();

        assertThat(text).contains("共 25 张").contains("仅显示最近 1 条").contains("OD20261012-0001");

        ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
        verify(ticketQueryService).mine(captor.capture());
        assertThat(captor.getValue().getSize()).as("必须限制条数：用户可能有几百张单").isEqualTo(10L);
    }

    @Test
    @DisplayName("验收2 补充：没有工单 → 友好文案，不返回空串")
    void 没有工单() {
        when(ticketQueryService.mine(any())).thenReturn(PageResult.of(List.of(), 0L, 1L, 10L));

        assertThat(tools.queryMyTickets()).contains("没有任何工单");
    }

    // ==================== 验收 3：createTicket 的身份与来源 ====================

    @Test
    @DisplayName("验收3：createTicket → 执行时处于当前用户身份下，且 source=AI_ASSISTANT")
    void 创建工单的身份与来源() {
        AtomicReference<Long> userIdSeenByService = new AtomicReference<>();
        when(ticketCreateService.create(any(), isNull(), eq(TicketSource.AI_ASSISTANT)))
                .thenAnswer(invocation -> {
                    // 业务服务正是这样取 creator_id 的（UserContext.requireUserId()）
                    userIdSeenByService.set(UserContext.getUserId());
                    return new TicketCreatedVO(100L, "OD20261012-0100");
                });

        String text = tools.createTicket("打印机不出纸", "三楼打印机卡纸", "INCIDENT", "PERIPHERAL");

        assertThat(text).contains("OD20261012-0100").contains("待受理");

        ArgumentCaptor<TicketCreateDTO> dtoCaptor = ArgumentCaptor.forClass(TicketCreateDTO.class);
        verify(ticketCreateService).create(dtoCaptor.capture(), isNull(), eq(TicketSource.AI_ASSISTANT));

        assertThat(userIdSeenByService.get())
                .as("creator_id 的来源：工具必须在当前用户身份下调用业务服务")
                .isEqualTo(EMP_WANG_ID);
        assertThat(dtoCaptor.getValue().getTitle()).isEqualTo("打印机不出纸");
        assertThat(dtoCaptor.getValue().getType()).isEqualTo(TicketType.INCIDENT);
        assertThat(dtoCaptor.getValue().getCategory()).isEqualTo(TicketCategory.PERIPHERAL);
        assertThat(dtoCaptor.getValue().getPriority())
                .as("§11.1 的工具签名里没有 priority → 缺省 P3（由服务端决定）").isNull();
    }

    @Test
    @DisplayName("验收3 补充：非法枚举 → 友好提示让模型自己纠正，不调业务服务")
    void 非法枚举给提示() {
        String badType = tools.createTicket("标题", "描述", "HACK", "NETWORK");
        assertThat(badType).contains("类型不合法").contains("INCIDENT");

        String badCategory = tools.createTicket("标题", "描述", "INCIDENT", "HACK");
        assertThat(badCategory).contains("分类不合法").contains("NETWORK");

        verifyNoInteractions(ticketCreateService);
    }

    @Test
    @DisplayName("防滥用：一个实例最多创建 1 张工单")
    void 最多创建一张() {
        when(ticketCreateService.create(any(), any(), any()))
                .thenReturn(new TicketCreatedVO(100L, "OD20261012-0100"));

        tools.createTicket("标题一", "描述一", "INCIDENT", "NETWORK");
        String second = tools.createTicket("标题二", "描述二", "INCIDENT", "NETWORK");

        assertThat(second).contains("已经提交过工单");
        verify(ticketCreateService, times(1)).create(any(), any(), any());
    }

    @Test
    @DisplayName("防滥用补充：参数校验失败【不消耗】那一次创建机会（模型改了还能重试）")
    void 参数错误不消耗机会() {
        tools.createTicket("", "描述", "INCIDENT", "NETWORK");           // 标题为空 → 被挡

        when(ticketCreateService.create(any(), any(), any()))
                .thenReturn(new TicketCreatedVO(100L, "OD20261012-0100"));
        String retry = tools.createTicket("标题", "描述", "INCIDENT", "NETWORK");

        assertThat(retry).contains("OD20261012-0100");
        verify(ticketCreateService, times(1)).create(any(), any(), any());
    }

    // ==================== 验收 4：方法签名里没有身份参数 ====================

    @Test
    @DisplayName("验收4：4 个工具方法都不含 userId/creatorId 之类的身份参数")
    void 工具方法不含身份参数() {
        int toolCount = 0;
        for (Method method : TicketAiTools.class.getDeclaredMethods()) {
            if (method.getAnnotation(Tool.class) == null) {
                continue;
            }
            toolCount++;
            for (Parameter parameter : method.getParameters()) {
                String desc = method.getName() + "#" + parameter.getName();
                assertThat(parameter.getType())
                        .as("工具入参只能是业务字段（String）：%s", desc)
                        .isEqualTo(String.class);
                assertThat(parameter.getName().toLowerCase())
                        .as("工具入参不得出现身份参数：%s", desc)
                        .doesNotContain("user").doesNotContain("creator");
            }
        }
        assertThat(toolCount).as("§11.1 的 4 个工具一个都不能少").isEqualTo(4);
    }

    @Test
    @DisplayName("验收4 补充：工具实例持有身份，但没有任何 setter 可以中途换人")
    void 身份不可中途更换() {
        // 只读字段 + 构造注入：LLM 拿不到任何能改身份的入口
        assertThat(TicketAiTools.class.getMethods())
                .as("公开方法里不应出现 setCurrentUser / setUserId 之类")
                .noneMatch(m -> m.getName().toLowerCase().startsWith("set"));
    }

    // ==================== 验收 5：queryKnowledge 友好提示 ====================

    @Test
    @DisplayName("验收5：queryKnowledge 在 Day 12 前返回友好提示，不抛异常")
    void 知识库暂不可用() {
        String text = tools.queryKnowledge("VPN 连不上");

        assertThat(text).contains("尚未上线").contains("VPN 连不上");
        verifyNoInteractions(ticketQueryService, ticketCreateService);
    }

    // ==================== 跨线程身份（本单最关键的一条） ====================

    @Test
    @DisplayName("🔴 跨线程：在【没有身份】的新线程上执行工具 → 业务服务仍能拿到当前用户，且执行完立即清理")
    void 跨线程恢复身份并清理() {
        AtomicReference<Long> userIdSeenAtCallTime = new AtomicReference<>();
        when(ticketQueryService.detail(OWN_TICKET_ID)).thenAnswer(invocation -> {
            userIdSeenAtCallTime.set(UserContext.getUserId());
            return detailVo(OWN_TICKET_ID, "OD20261012-0001");
        });

        // 在另一个线程执行 —— 模拟 AiChatGateway 的线程池（那里 ThreadLocal 本来是空的）
        String text = CompletableFuture.supplyAsync(() -> tools.queryTicket(String.valueOf(OWN_TICKET_ID)))
                .join();

        assertThat(text).contains("OD20261012-0001");
        assertThat(userIdSeenAtCallTime.get())
                .as("工具内必须把身份放回当前线程，否则业务服务的 UserContext 取不到 → 一律 40301")
                .isEqualTo(EMP_WANG_ID);
        // 线程池线程会被复用：不清身份就会把 A 用户的身份带到 B 用户的请求上
        assertThat(UserContext.getUserId()).as("执行完必须清理 ThreadLocal").isNull();
    }

    @Test
    @DisplayName("🔴 无身份时构造工具 → 直接失败（不留「匿名调用业务接口」的可能）")
    void 无身份不能构造() {
        assertThatThrownBy(() -> new TicketAiTools(null, ticketQueryService, ticketCreateService))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentUser 不能为空");
    }

    @Test
    @DisplayName("补充：queryKnowledge 同样不碰业务服务，也不依赖身份")
    void 知识库工具不需要身份() {
        tools.queryKnowledge("邮箱配置");
        verify(ticketQueryService, never()).detail(anyLong());
    }

    // ==================== 工具方法 ====================

    private Method toolMethod(String name) {
        return java.util.Arrays.stream(TicketAiTools.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到工具方法：" + name));
    }

    private UserContext.CurrentUser currentUser(long userId) {
        return new UserContext.CurrentUser(userId, "jti-test", Set.of(Role.EMPLOYEE),
                Set.of("ticket:create"), 2L);
    }

    private TicketDetailVO detailVo(long id, String ticketNo) {
        TicketDetailVO vo = new TicketDetailVO();
        vo.setId(id);
        vo.setTicketNo(ticketNo);
        vo.setTitle("VPN 连不上");
        vo.setStatus(TicketStatus.OPEN);
        vo.setPriority(TicketPriority.P2);
        vo.setCategory(TicketCategory.NETWORK);
        vo.setType(TicketType.INCIDENT);
        vo.setDescription("今天上午开始 VPN 一直连接失败");
        vo.setResolutionDeadline(LocalDateTime.of(2026, 10, 12, 18, 0));
        return vo;
    }
}

package com.opsdesk.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 助手会话记忆装配（工单 D11-02，规格基线 §11.2；yml 的 `spring.ai.chat.memory.repository.jdbc`）
 *
 * <h2>记忆存哪里：MySQL，不是内存</h2>
 * 「会话记忆落 MySQL」是工单的明确要求，所以这里必须确认拿到的 repository 是
 * {@code JdbcChatMemoryRepository}，而不是默认的 {@code InMemoryChatMemoryRepository}
 * （后者一重启就全没了）。
 *
 * <p><b>已反编译核实</b>，这两件事都有保证，不需要我们兜底：
 * <ol>
 *   <li>{@code JdbcChatMemoryRepositoryAutoConfiguration} 类上标注了
 *       {@code @AutoConfiguration(before = ChatMemoryAutoConfiguration.class)} ——
 *       <b>先</b>注册 JDBC 仓库；</li>
 *   <li>{@code ChatMemoryAutoConfiguration#chatMemoryRepository()} 是
 *       {@code @ConditionalOnMissingBean} —— 于是那个 InMemory 的实现会<b>退让</b>。</li>
 * </ol>
 * 所以上下文里的 {@link ChatMemoryRepository} 就是 JDBC 实现。表由 yml 的
 * {@code initialize-schema: always} + {@code classpath:sql/schema-mysql.sql} 自动创建。
 *
 * <h2>那这个类还显式声明 {@link ChatMemory} 干什么</h2>
 * 自动配置确实也会造一个 {@code MessageWindowChatMemory}（同样是 {@code @ConditionalOnMissingBean}），
 * 但它的<b>窗口大小是框架默认值</b>。窗口大小直接决定「多轮对话还能记住多久」——
 * 这属于产品行为，不该是一个碰巧的默认值。所以在这里显式写出来，让它成为一个<b>经过考虑的决定</b>。
 */
@Configuration
public class AiChatConfiguration {

    /**
     * 会话窗口：最多保留最近多少条消息（用户 + 助手各算一条，见 {@code AiChatServiceImpl} 只写问答对）。
     *
     * <p>数值与框架默认一致（20）。没有再调大：窗口越大，每轮请求的 token 成本越高；
     * 而多轮对话的「跨轮记忆」通常只需要最近几轮。
     */
    static final int MEMORY_WINDOW_MESSAGES = 20;

    @Bean
    public ChatMemory aiChatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MEMORY_WINDOW_MESSAGES)
                .build();
    }
}

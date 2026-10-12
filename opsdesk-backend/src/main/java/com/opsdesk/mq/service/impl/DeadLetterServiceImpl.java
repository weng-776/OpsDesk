package com.opsdesk.mq.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.dto.DeadLetterQuery;
import com.opsdesk.mq.service.DeadLetterService;
import com.opsdesk.mq.support.OpsDeskMessageRecoverer;
import com.opsdesk.mq.vo.DeadLetterVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信管理实现（工单 D9-05）
 *
 * <h2>数据源：RabbitMQ 管理 HTTP API + {@code ack_requeue_true}（非破坏性读）</h2>
 * {@code POST /api/queues/{vhost}/{queue}/get} 用 {@code ackmode=ack_requeue_true}
 * 拿到消息后<b>原样放回</b> —— 所以「查看列表」不会把死信取走（这是方案 A 相对 B 的全部意义）。
 *
 * <h2>⚠️ 分页怎么做的（管理 API 不支持 offset）</h2>
 * 管理 API 的 {@code get} 只有 {@code count}，没有 offset。所以：
 * <pre>
 * 取 page*size 条 → 内存里切掉前 (page-1)*size 条
 * </pre>
 * ⚠️ 为避免「翻到第 100 页把整个队列拉进内存」，单次抓取量有上限
 * （{@link #MAX_FETCH}）。死信是人工介入才看的东西，量级远小于业务队列；
 * 真超过上限时返回的是「前 MAX_FETCH 条」，日志会记 warn。
 *
 * <h2>⚠️ {@code id} = 聚合列表里的 1-based 序号</h2>
 * 队列按 {@link #DLQ_ORDER} 聚合（与 {@code RabbitMQConfig} 的声明顺序一致），
 * 所以同一个列表快照里序号稳定；purge / 消费之后序号会变 —— 见 {@code DeadLetterVO} 的说明。
 *
 * <h2>⚠️ requeue 是「取出 + 回写」，不是「就地转发」</h2>
 * 管理 API 不能定位「第 N 条」并只取它，只能取前 N 条。所以重投的实现是：
 * <ol>
 *   <li>把目标队列的前 {@code 序号+1} 条<b>取出来</b>（{@code ack_requeue_false}）</li>
 *   <li>其中最后一条是目标 → 幂等校验 → 投回原业务交换机</li>
 *   <li>其余几条<b>原样投回该队列的死信交换机</b>（一条都不能丢）</li>
 * </ol>
 * 副作用：DLQ 里其它消息的顺序会变、且会多一次死信记录（{@code x-death} 计数 +1）。
 * 管理端是人工低频操作，可接受；已在返回值与日志里说明。
 */
@Slf4j
@Service
public class DeadLetterServiceImpl implements DeadLetterService {

    /** 死信队列的聚合顺序（与 RabbitMQConfig 的声明顺序一致，保证序号稳定） */
    private static final List<String> DLQ_ORDER = List.of(
            RabbitMQConfig.DLQ_TICKET_CREATED,
            RabbitMQConfig.DLQ_TICKET_ASSIGNED,
            RabbitMQConfig.DLQ_TICKET_STATUS,
            RabbitMQConfig.DLQ_KNOWLEDGE_INDEX,
            RabbitMQConfig.DLQ_NOTIFICATION);

    /**
     * DLQ → 原业务 Routing Key。
     *
     * <p>重投时用 {@code x-original-routingKey} 头优先（recoverer 路径会带），
     * 取不到再用这张表兜底 —— 两条死信路径都能重投。
     */
    private static final Map<String, String> DLQ_TO_ROUTING_KEY = Map.of(
            RabbitMQConfig.DLQ_TICKET_CREATED, RabbitMQConfig.RK_TICKET_CREATED,
            RabbitMQConfig.DLQ_TICKET_ASSIGNED, RabbitMQConfig.RK_TICKET_ASSIGNED,
            RabbitMQConfig.DLQ_TICKET_STATUS, RabbitMQConfig.RK_TICKET_STATUS_CHANGED,
            RabbitMQConfig.DLQ_KNOWLEDGE_INDEX, RabbitMQConfig.RK_KNOWLEDGE_INDEX,
            RabbitMQConfig.DLQ_NOTIFICATION, RabbitMQConfig.RK_NOTIFICATION_REQUESTED);

    /** 单次抓取上限（见类注释「分页怎么做的」） */
    private static final int MAX_FETCH = 200;

    /** {@code payload} 返回上限 —— 消息体可能几 MB，原样返回会让响应爆掉 */
    static final int MAX_PAYLOAD_LENGTH = 2000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 消息头：Jackson 写入的类名，用来还原 {@code eventType} */
    private static final String HEADER_TYPE_ID = "__TypeId__";

    /** 消息头：投递器写入的事件类型（= {@code event_outbox.event_type}），D9-05 起由 OutboxPublisher 设置 */
    private static final String HEADER_EVENT_TYPE = "eventType";

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient managementClient;
    /** 管理 API 的基础地址，末尾不带 / */
    private final String managementBaseUrl;
    /** vhost 的 URL 路径段（`/` → `%2F`），已编码 */
    private final String vhostPathSegment;

    public DeadLetterServiceImpl(RabbitTemplate rabbitTemplate,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${opsdesk.mq.management-base-url:http://${spring.rabbitmq.host}:15672}")
                                 String managementBaseUrl,
                                 @Value("${spring.rabbitmq.username}") String username,
                                 @Value("${spring.rabbitmq.password}") String password,
                                 @Value("${spring.rabbitmq.virtual-host:/}") String virtualHost) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // 管理端口（15672）与 AMQP 端口（5672）不同：这里从 spring.rabbitmq.host 推导默认值，
        // 需要指向别处时用 opsdesk.mq.management-base-url 覆盖，不必改主配置。
        this.managementBaseUrl = managementBaseUrl.endsWith("/")
                ? managementBaseUrl.substring(0, managementBaseUrl.length() - 1) : managementBaseUrl;
        this.managementClient = RestClient.builder()
                .baseUrl(this.managementBaseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
        this.vhostPathSegment = UriUtils.encodePathSegment(virtualHost, StandardCharsets.UTF_8);
    }

    /**
     * 拼管理 API 的 URL。
     *
     * <p>⚠️ <b>必须用 {@code URI.create} 传完整地址，不能走 {@code uri(template, vars)}</b>：
     * 模板写法会对变量<b>再编码一次</b> —— vhost 的 {@code /} 已经是 {@code %2F}，
     * 再编一次变成 {@code %252F}，管理 API 直接 404。
     * （踩过：现象是「所有死信接口都返回 50300」，而根因只是一个被编码两次的斜杠。）
     */
    private URI managementUri(String path) {
        return URI.create(managementBaseUrl + "/api/" + path);
    }

    // ==================== §15.1 列表 ====================

    @Override
    public PageResult<DeadLetterVO> page(DeadLetterQuery query) {
        query.normalize();
        long pageNo = query.pageOrDefault();
        long size = query.sizeOrDefault();

        List<String> queues = resolveQueues(query.getQueue());
        long skip = (pageNo - 1) * size;
        long need = pageNo * size;

        List<DeadLetterVO> all = new ArrayList<>();
        long total = 0;
        for (String queue : queues) {
            // ⚠️ 队列详情里的 messages 是**统计值**（管理插件约 5s 刷新一次），
            //    刚进死信队列的消息在它眼里还是 0；而 /get 是实时的。
            //    所以 total 取「统计值 vs 实际取到的条数」的较大者 —— 两边都不会漏。
            int statsDepth = queueDepth(queue);
            List<DeadLetterVO> fetched = fetch(queue, (int) Math.min(need, MAX_FETCH), true);
            all.addAll(fetched);
            total += Math.max(statsDepth, fetched.size());
        }

        List<DeadLetterVO> pageItems = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            // id 是「聚合列表里的 1-based 序号」，跨队列连续
            DeadLetterVO vo = all.get(i);
            vo.setId((long) i + 1);
            if (vo.getId() > skip && pageItems.size() < size) {
                pageItems.add(vo);
            }
        }

        return PageResult.of(pageItems, total, pageNo, size);
    }

    // ==================== §15.2 重新投递 ====================

    @Override
    public boolean requeue(long id) {
        Locator locator = locate(id);

        // ⚠️ 先做幂等校验，再动队列 —— 已消费过的消息不该被「取出来又投回去」（会白白扰动队列）
        String messageId = locator.message().getMessageProperties().getMessageId();
        String consumedKey = consumedKey(locator.queue(), messageId);
        if (consumedKey != null && Boolean.TRUE.equals(redisTemplate.hasKey(consumedKey))) {
            log.info("[死信重投] 该消息已成功消费过，按 §15.2 标记为已处理、不重复执行业务。"
                    + "queue={} messageId={} key={}", locator.queue(), messageId, consumedKey);
            // 即便目标已消费，也仍要从 DLQ 移除；但 drain 会同时拿到前面的消息，必须原样回写它们。
            List<Message> drained = drain(locator.queue(), locator.localIndex() + 1, locator.message());
            publishBackToDlq(locator.queue(), drained, locator.localIndex());
            return false;
        }

        // 取出前 localIndex+1 条；最后一条是目标，其余原样投回本队列的死信交换机
        List<Message> drained = drain(locator.queue(), locator.localIndex() + 1, locator.message());
        // ⚠️ 回写时必须**跳过目标本身** —— 目标要投回业务队列，不能又回到 DLQ
        //    （踩过：不跳过的话重投完 DLQ 里还留着它，看起来「重投没生效」）
        publishBackToDlq(locator.queue(), drained, locator.localIndex());

        // 目标投回原业务交换机 —— 路由键优先取 x-original-routingKey（recoverer 路径会带）
        String routingKey = originalRoutingKey(locator.message(), locator.queue());
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, routingKey, locator.message());
        log.info("[死信重投] 已重新投递。queue={} messageId={} routingKey={}",
                locator.queue(), messageId, routingKey);
        return true;
    }

    // ==================== 管理 API 交互 ====================

    /** 定位第 {@code id} 条死信：它属于哪个队列、在该队列里是第几条 */
    private Locator locate(long id) {
        if (id < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "死信序号必须从 1 开始");
        }
        long cursor = 0;
        for (String queue : DLQ_ORDER) {
            // ⚠️ 这里必须用 /get 实际取消息来定位，**不能**用队列详情的 messages：
            //    那是统计值（管理插件约 5s 刷一次），刚到的死信在它眼里还是 0 →
            //    序号会全部错位（踩过：现象是「刚看到的那条 requeue 说序号越界」）。
            List<Message> batch = fetchRaw(queue, (int) Math.min(id - cursor, MAX_FETCH), true);
            if (id <= cursor + batch.size()) {
                int localIndex = (int) (id - cursor - 1);
                return new Locator(queue, localIndex, batch.get(localIndex));
            }
            cursor += batch.size();
        }
        throw BizException.notFound("死信不存在（序号超出当前列表，请刷新后重试）");
    }

    /**
     * 从队列里取出前 {@code count} 条（{@code ack_requeue_false} = <b>真的移除</b>）。
     *
     * <p>⚠️ 调用方必须负责把不需要的消息投回去（{@link #publishBackToDlq}）——
     * 「取出却不回写」正是 SOP 里点名禁止的「看一眼消息就丢了」。
     */
    private List<Message> drain(String queue, int count, Message target) {
        List<Message> drained = fetchRaw(queue, count, false);
        boolean targetSeen = drained.stream().anyMatch(m -> sameMessage(m, target));
        if (!targetSeen) {
            // 目标没取到（并发变化）→ 把已取出的全部投回去，别丢
            log.warn("[死信重投] 目标消息不在取出的批次里，已全部回写。queue={} count={}", queue, count);
            publishBackToDlq(queue, drained, -1);
            throw BizException.conflict("死信队列内容已变化，请刷新列表后重试");
        }
        return drained;
    }

    /**
     * 把取出来但不是目标的那些消息原样投回该队列的死信交换机（一条都不能丢）。
     *
     * <p>⚠️ {@code targetLocalIndex} 必须排除：它是本次要重投（或已消费、应从 DLQ 清理）的目标，
     * 不能又回到 DLQ。按位置跳过而不是按 messageId 跳过，避免异常/手工消息 messageId 缺失或重复时误丢多条。
     */
    private void publishBackToDlq(String queue, List<Message> drained, int targetLocalIndex) {
        String routingKey = DLQ_TO_ROUTING_KEY.getOrDefault(queue, queue);
        for (int i = 0; i < drained.size(); i++) {
            if (i == targetLocalIndex) {
                continue;   // 目标不回 DLQ
            }
            // 回写时保持原样（含 x-retry-count / x-death），只多一层死信记录
            rabbitTemplate.send(RabbitMQConfig.DLX, routingKey, drained.get(i));
        }
    }

    /** 调管理 API 取消息；{@code requeue=true} 表示读完放回（非破坏性） */
    private List<Message> fetchRaw(String queue, int count, boolean requeue) {
        if (count <= 0) {
            return List.of();
        }
        String body = "{\"count\":" + Math.min(count, MAX_FETCH)
                + ",\"ackmode\":\"" + (requeue ? "ack_requeue_true" : "ack_requeue_false")
                + "\",\"encoding\":\"auto\"}";
        try {
            String response = managementClient.post()
                    .uri(managementUri("queues/" + vhostPathSegment + "/"
                            + UriUtils.encodePathSegment(queue, StandardCharsets.UTF_8) + "/get"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseMessages(response);
        }
        catch (RestClientException ex) {
            // §20.2：依赖服务不可用 → 50300，不是 50000
            log.error("[死信] 调 RabbitMQ 管理接口失败。queue={} 原因={}", queue, ex.toString());
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "RabbitMQ 管理接口不可用（检查 15672 端口与 rabbitmq_management 插件）", ex);
        }
    }

    /** 只读地取一批并转成 VO（列表用） */
    private List<DeadLetterVO> fetch(String queue, int count, boolean requeue) {
        List<Message> messages = fetchRaw(queue, count, requeue);
        List<DeadLetterVO> vos = new ArrayList<>(messages.size());
        for (Message message : messages) {
            vos.add(toVO(queue, message));
        }
        return vos;
    }

    /** 队列消息数（管理 API 的队列详情） */
    private int queueDepth(String queue) {
        try {
            String response = managementClient.get()
                    .uri(managementUri("queues/" + vhostPathSegment + "/"
                            + UriUtils.encodePathSegment(queue, StandardCharsets.UTF_8)))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            JsonNode messages = node.get("messages");
            return messages == null ? 0 : messages.asInt();
        }
        catch (RestClientException ex) {
            log.error("[死信] 查队列深度失败。queue={} 原因={}", queue, ex.toString());
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "RabbitMQ 管理接口不可用（检查 15672 端口与 rabbitmq_management 插件）", ex);
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "解析 RabbitMQ 管理接口响应失败", ex);
        }
    }

    // ==================== 转换 ====================

    private List<Message> parseMessages(String response) {
        try {
            JsonNode array = objectMapper.readTree(response);
            List<Message> messages = new ArrayList<>();
            for (JsonNode item : array) {
                MessageProperties props = new MessageProperties();
                JsonNode properties = item.get("properties");
                if (properties != null) {
                    if (properties.hasNonNull("message_id")) {
                        props.setMessageId(properties.get("message_id").asText());
                    }
                    if (properties.hasNonNull("content_type")) {
                        props.setContentType(properties.get("content_type").asText());
                    }
                    JsonNode headers = properties.get("headers");
                    if (headers != null && headers.isObject()) {
                        headers.fields().forEachRemaining(entry ->
                                props.setHeader(entry.getKey(), toJavaValue(entry.getValue())));
                    }
                }
                if (item.hasNonNull("routing_key")) {
                    props.setReceivedRoutingKey(item.get("routing_key").asText());
                }
                String payload = item.hasNonNull("payload") ? item.get("payload").asText() : "";
                messages.add(new Message(payload.getBytes(StandardCharsets.UTF_8), props));
            }
            return messages;
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "解析 RabbitMQ 管理接口响应失败", ex);
        }
    }

    /** JSON 值 → Java 值（只处理我们真正会用到的几种） */
    private Object toJavaValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(toJavaValue(child)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toJavaValue(e.getValue())));
            return map;
        }
        return node.asText();
    }

    private DeadLetterVO toVO(String queue, Message message) {
        MessageProperties props = message.getMessageProperties();
        DeadLetterVO vo = new DeadLetterVO();
        vo.setMessageId(props.getMessageId());
        vo.setQueue(queue);
        vo.setEventType(eventTypeOf(props));
        vo.setPayload(truncate(new String(message.getBody(), StandardCharsets.UTF_8)));
        vo.setFailedAt(failedAtOf(props));
        vo.setRetryCount(retryCountOf(props));
        return vo;
    }

    /**
     * 事件类型：优先读投递器写的 {@code eventType} 头（最准，就是 {@code event_outbox.event_type}），
     * 退化为 Jackson 的 {@code __TypeId__}，再退化为路由键。
     *
     * <p>⚠️ 三级兜底是因为死信可能来自不同版本的生产端：老版本没有 {@code eventType} 头，
     * 而手工发的消息连 {@code __TypeId__} 都没有 —— 此时至少给出路由键，别返回 null 让前端显示空白。
     */
    private String eventTypeOf(MessageProperties props) {
        Object eventType = props.getHeader(HEADER_EVENT_TYPE);
        if (eventType != null && StringUtils.hasText(eventType.toString())) {
            return eventType.toString();
        }
        Object typeId = props.getHeader(HEADER_TYPE_ID);
        if (typeId != null && StringUtils.hasText(typeId.toString())) {
            String fullName = typeId.toString();
            int dot = fullName.lastIndexOf('.');
            return dot < 0 ? fullName : fullName.substring(dot + 1);
        }
        return props.getReceivedRoutingKey();
    }

    /**
     * 重试次数（§13.4 约束 4）：两条路径取值不同 ——
     * recoverer 路径读 {@code x-retry-count}，broker 原生死信读 {@code x-death[0].count}。
     * 两者都取不到返回 {@code null}（「不知道」≠「0 次」）。
     */
    private Integer retryCountOf(MessageProperties props) {
        Object custom = props.getHeader(OpsDeskMessageRecoverer.HEADER_RETRY_COUNT);
        if (custom instanceof Number number) {
            return number.intValue();
        }
        Object death = props.getHeader("x-death");
        if (death instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object count = first.get("count");
            if (count instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    /**
     * 进入死信的时间：recoverer 路径读 {@code x-failed-at}；broker 原生路径读 {@code x-death[0].time}。
     * 两条都没有时为 {@code null}。
     */
    private String failedAtOf(MessageProperties props) {
        Object customTime = props.getHeader(OpsDeskMessageRecoverer.HEADER_FAILED_AT);
        if (customTime != null && StringUtils.hasText(customTime.toString())) {
            return customTime.toString();
        }
        Object death = props.getHeader("x-death");
        if (death instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object time = first.get("time");
            if (time instanceof Number number) {
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(number.longValue()), ZoneId.systemDefault())
                        .format(TIME_FORMAT);
            }
            if (time instanceof java.util.Date date) {
                return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(TIME_FORMAT);
            }
            if (time instanceof String text) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault()).format(TIME_FORMAT);
                }
                catch (Exception ignored) {
                    return text;   // 未知但可显示的格式，保留原文
                }
            }
        }
        return null;
    }

    /** 截断消息体 —— 直接返回几 MB 的 body 会让响应爆掉 */
    private String truncate(String payload) {
        if (payload == null || payload.length() <= MAX_PAYLOAD_LENGTH) {
            return payload;
        }
        return payload.substring(0, MAX_PAYLOAD_LENGTH) + "...(truncated, total " + payload.length() + ")";
    }

    /** 原业务路由键：优先消息头（recoverer 会带），退化用队列映射表 */
    private String originalRoutingKey(Message message, String queue) {
        Object header = message.getMessageProperties().getHeader("x-original-routingKey");
        if (header != null && StringUtils.hasText(header.toString())) {
            return header.toString();
        }
        return DLQ_TO_ROUTING_KEY.getOrDefault(queue, queue);
    }

    /**
     * 消费端幂等键：{@code mq:consumed:{consumerName}:{messageId}}。
     *
     * <p>consumerName 是<b>业务队列名</b>（见 {@code ConsumeIdempotencyGuard} 的调用点），
     * 而这里是死信队列名 → 把 {@code .dlq} 换成 {@code .queue} 即可（5 个队列都满足这个命名约定）。
     */
    private String consumedKey(String dlqName, String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return null;   // 没有 messageId 就无法判重（与消费端 fail-open 的取舍一致）
        }
        String consumerName = dlqName.endsWith(".dlq")
                ? dlqName.substring(0, dlqName.length() - ".dlq".length()) + ".queue"
                : dlqName;
        return com.opsdesk.mq.support.ConsumeIdempotencyGuard.KEY_PREFIX + consumerName + ":" + messageId;
    }

    private boolean sameMessage(Message left, Message right) {
        if (right == null) {
            return false;
        }
        String leftId = left.getMessageProperties().getMessageId();
        String rightId = right.getMessageProperties().getMessageId();
        return leftId != null && leftId.equals(rightId);
    }

    private List<String> resolveQueues(String queue) {
        if (!StringUtils.hasText(queue)) {
            return DLQ_ORDER;
        }
        String trimmed = queue.trim();
        if (!DLQ_ORDER.contains(trimmed)) {
            // 队列名写错属于调用方 bug：报错比「静默返回空列表」好（后者会让人以为死信队列是空的）
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "未知的死信队列：" + trimmed + "，可选值 " + DLQ_ORDER);
        }
        return List.of(trimmed);
    }

    /** 定位结果：队列 + 队列内下标 + 原始消息 */
    private record Locator(String queue, int localIndex, Message message) {
    }
}

package com.opsdesk.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.BizException;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.notification.dto.NotificationQuery;
import com.opsdesk.notification.entity.Notification;
import com.opsdesk.notification.mapper.NotificationMapper;
import com.opsdesk.notification.service.NotificationBizService;
import com.opsdesk.notification.service.NotificationService;
import com.opsdesk.notification.vo.NotificationVO;
import com.opsdesk.notification.vo.ReadAllResultVO;
import com.opsdesk.notification.vo.UnreadCountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 站内通知查询与已读实现（工单 D6-03，API 文档 §12.1 ~ §12.4）
 *
 * <h2>索引</h2>
 * {@code notification} 上只有 {@code idx_notif_user(user_id, read_flag, created_at)}。
 * 列表（{@code user_id [+ read_flag] ORDER BY created_at DESC}）与未读数
 * （{@code user_id + read_flag}）都能命中它的最左前缀 —— 排序方向也与索引尾列一致。
 * ⚠️ 排序仍要带 {@code id DESC} 做 tiebreaker（种子通知里就有同秒的行）。
 *
 * <h2>⚠️ 接收人一律取自 UserContext，绝不从入参拿</h2>
 * 见 {@link NotificationBizService} 类注释。
 */
@Slf4j
@Service
public class NotificationBizServiceImpl implements NotificationBizService {

    /** 未读 */
    private static final int UNREAD = 0;

    /** 已读 */
    private static final int READ = 1;

    private final NotificationService notificationService;
    private final NotificationMapper notificationMapper;

    public NotificationBizServiceImpl(NotificationService notificationService,
                                      NotificationMapper notificationMapper) {
        this.notificationService = notificationService;
        this.notificationMapper = notificationMapper;
    }

    @Override
    public PageResult<NotificationVO> page(NotificationQuery query) {
        long userId = UserContext.requireUserId();
        long pageNo = query.pageOrDefault();
        long size = query.sizeOrDefault();

        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        // 只取 VO 需要的列（既不是 SELECT *，也不多捞）
        wrapper.select(Notification::getId, Notification::getType, Notification::getTitle,
                Notification::getContent, Notification::getBizType, Notification::getBizId,
                Notification::getReadFlag, Notification::getCreatedAt);

        // ① 数据隔离 —— 硬编码「只查自己」，与任何客户端参数无关
        wrapper.eq(Notification::getUserId, userId);

        // ② 可选筛选
        if (query.getReadFlag() != null) {
            wrapper.eq(Notification::getReadFlag, query.getReadFlag() ? READ : UNREAD);
        }

        // ③ 最新在前；id 做 tiebreaker（同一秒可能有多条）
        wrapper.orderByDesc(Notification::getCreatedAt).orderByDesc(Notification::getId);

        Page<Notification> page = notificationService.page(new Page<>(pageNo, size), wrapper);
        log.debug("[通知列表] userId={} readFlag={} page={} size={} total={}",
                userId, query.getReadFlag(), pageNo, size, page.getTotal());

        return PageResult.of(toVoList(page.getRecords()), page.getTotal(), pageNo, size);
    }

    @Override
    public UnreadCountVO unreadCount() {
        long userId = UserContext.requireUserId();

        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD));

        return new UnreadCountVO(count == null ? 0L : count);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long notificationId) {
        long userId = UserContext.requireUserId();

        Notification notification = notificationService.getById(notificationId);
        if (notification == null) {
            throw BizException.notFound("通知不存在");
        }
        // ⚠️ 顺序不能反：先 40400 再 40301（与工单详情/流转一致）。
        //    这里两个分支都是「明确的错误」，不存在泄露 id 是否存在的问题 ——
        //    通知 id 是自增的，猜 id 本身就能探到存在性，挡也挡不住。
        if (!Objects.equals(notification.getUserId(), userId)) {
            log.warn("[通知] 越权标记已读被拒：notificationId={} owner={} operator={}",
                    notificationId, notification.getUserId(), userId);
            throw BizException.dataScopeDenied("只能操作自己的通知");
        }

        // 幂等：已读再标一次也返回成功（前端可能重复点击）
        if (notification.getReadFlag() != null && notification.getReadFlag() == READ) {
            return;
        }

        LambdaUpdateWrapper<Notification> update = new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notificationId)
                .eq(Notification::getUserId, userId)   // 再带一次归属条件，避免「查完到改之间」被换主
                .set(Notification::getReadFlag, READ);
        notificationMapper.update(null, update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReadAllResultVO readAll() {
        long userId = UserContext.requireUserId();

        LambdaUpdateWrapper<Notification> update = new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD)
                .set(Notification::getReadFlag, READ);

        // 用 mapper 拿影响行数（IService.update 只返回 boolean，拿不到 affected）
        int affected = notificationMapper.update(null, update);
        log.info("[通知] 全部已读 userId={} affected={}", userId, affected);

        return new ReadAllResultVO(affected);
    }

    // ==================== 私有 ====================

    private List<NotificationVO> toVoList(List<Notification> rows) {
        return rows.stream().map(this::toVo).toList();
    }

    private NotificationVO toVo(Notification row) {
        NotificationVO vo = new NotificationVO();
        vo.setId(row.getId());
        vo.setType(row.getType());
        vo.setTitle(row.getTitle());
        vo.setContent(row.getContent());
        vo.setBizType(row.getBizType());
        vo.setBizId(row.getBizId());
        // §16.15 要求 boolean，库里是 TINYINT(0/1) —— 这里必须转换
        vo.setReadFlag(row.getReadFlag() != null && row.getReadFlag() == READ);
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }
}

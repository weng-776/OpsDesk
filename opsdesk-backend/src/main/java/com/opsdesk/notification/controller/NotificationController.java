package com.opsdesk.notification.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.notification.dto.NotificationQuery;
import com.opsdesk.notification.service.NotificationBizService;
import com.opsdesk.notification.vo.NotificationVO;
import com.opsdesk.notification.vo.ReadAllResultVO;
import com.opsdesk.notification.vo.UnreadCountVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口（API 文档 §12.1 ~ §12.4）
 *
 * <pre>
 * GET  /api/notifications?page=1&amp;size=10&amp;readFlag=
 * GET  /api/notifications/unread-count
 * PUT  /api/notifications/{id}/read
 * PUT  /api/notifications/read-all
 * </pre>
 *
 * <p>鉴权 {@code notification:view}（§22：EMPLOYEE / AGENT / ADMIN 都有 —— 通知是每个人都该看的）。
 *
 * <h2>⚠️ 四个接口都「只看自己」</h2>
 * <b>没有一个</b>接口接受 {@code userId} 参数，接收人一律取
 * {@code UserContext.requireUserId()}。{@code {id}/read} 传别人的通知 id →
 * <b>40301</b>。理由见 {@code NotificationBizService} 类注释。
 *
 * <h2>路径说明</h2>
 * {@code /read-all} 与 {@code /{id}/read} 段数不同（1 段 vs 2 段），不会互相吃掉；
 * {@code /unread-count} 同理。所以本类不需要靠声明顺序来避免歧义。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationBizService notificationBizService;

    public NotificationController(NotificationBizService notificationBizService) {
        this.notificationBizService = notificationBizService;
    }

    /** 我的通知（§12.1） */
    @RequirePermission(PermissionCodes.NOTIFICATION_VIEW)
    @GetMapping
    public Result<PageResult<NotificationVO>> page(@Valid NotificationQuery query) {
        return Result.ok(notificationBizService.page(query));
    }

    /** 未读数量（§12.2） */
    @RequirePermission(PermissionCodes.NOTIFICATION_VIEW)
    @GetMapping("/unread-count")
    public Result<UnreadCountVO> unreadCount() {
        return Result.ok(notificationBizService.unreadCount());
    }

    /**
     * 标记单条已读（§12.3）。
     *
     * <p>返回 {@code data: null} —— 前端只需要知道「成功了」，不需要回读整条通知。
     */
    @RequirePermission(PermissionCodes.NOTIFICATION_VIEW)
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationBizService.markRead(id);
        return Result.ok();
    }

    /** 全部标记已读（§12.4） */
    @RequirePermission(PermissionCodes.NOTIFICATION_VIEW)
    @PutMapping("/read-all")
    public Result<ReadAllResultVO> readAll() {
        return Result.ok(notificationBizService.readAll());
    }
}

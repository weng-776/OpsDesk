package com.opsdesk.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知（第一版仅站内信，不做邮件/IM） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}

package com.opsdesk.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <h3>为什么必须有这个类</h3>
 * <p>MyBatis-Plus 的分页和乐观锁都是**插件（Interceptor）**，不注册就完全不生效，
 * 而且**不会报错**——只是行为悄悄不对：
 * <ul>
 *   <li>不注册 {@link PaginationInnerInterceptor}：{@code selectPage} 不生成 {@code LIMIT}，
 *       <b>把整表捞进内存</b>，且 {@code IPage.getTotal()} 恒为 {@code 0}（前端分页器直接失灵）</li>
 *   <li>不注册 {@link OptimisticLockerInnerInterceptor}：实体上的 {@code @Version} 被忽略，
 *       {@code updateById} 不带版本条件 → §8 要求的「工单状态流转并发安全」形同虚设</li>
 * </ul>
 *
 * <h3>拦截器顺序（MyBatis-Plus 官方建议）</h3>
 * <p>「对 SQL 做单次改造的放前面，不做改造的放后面」：
 * 分页 → 乐观锁 → （若将来加）防全表更新。
 *
 * <h3>为什么没有 {@code @MapperScan}</h3>
 * <p><b>不需要，且刻意不加。</b>mybatis-spring-boot-starter 的
 * {@code MybatisPlusAutoConfiguration$AutoConfiguredMapperScannerRegistrar}
 * 会自动注册一个 {@code MapperScannerConfigurer}，
 * 以 <b>自动配置包</b>（即主类 {@code com.opsdesk.OpsDeskApplication} 所在包）为根、
 * 以 {@code @Mapper} 注解为过滤条件扫描 Mapper 接口。
 * 本项目 17 个 Mapper 全部标了 {@code @Mapper}，因此天然被注册。
 *
 * <p>⚠️ <b>不要改成 {@code @MapperScan("com.opsdesk")}</b>：{@code @MapperScan} 在不指定
 * {@code annotationClass} 时会注册包内<b>所有接口</b>，会把 {@code com.opsdesk.common.enums.CodeEnum}
 * 这类普通接口也当成 Mapper 注册。若确要显式扫描，必须写成
 * {@code @MapperScan(basePackages = "...", annotationClass = Mapper.class)}。
 *
 * <p>另注：MyBatis-Plus 3.5.7 新增的 {@code MybatisPlusInnerInterceptorAutoConfiguration}
 * 声明了 {@code @ConditionalOnMissingBean(MybatisPlusInterceptor.class)}，
 * 因此本类一旦存在，它会自动退避，不会出现两个 {@code MybatisPlusInterceptor}。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfiguration {

    /**
     * 单页最大条数（兜底）。
     *
     * <p>§20.1 的接口约定 {@code size} 上限是 50，由 {@code com.opsdesk.common.PageQuery} 收敛；
     * 这里是**第二道防线**：防止绕过 {@code PageQuery} 的代码（自定义查询、内部调用）
     * 一次把整表拖出来。超过即被静默收敛到该值，不报错。
     */
    private static final long MAX_PAGE_SIZE = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ① 分页：指定 DbType.MYSQL 可省掉一次「探测数据库类型」的连接开销
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // overflow = false：页码超出总页数时返回空列表，而不是悄悄回到第 1 页
        // （管理员按 URL 翻页时，返回空比「莫名回到首页」更容易定位问题）
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // ② 乐观锁：让实体上的 @Version 真正生效
        //    UPDATE ... SET version = version + 1 WHERE id = ? AND version = ? AND deleted = 0
        //    影响行数为 0 即代表「别人已改过」，由 ticket 模块（§7 状态流转）判定为 40900
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }
}

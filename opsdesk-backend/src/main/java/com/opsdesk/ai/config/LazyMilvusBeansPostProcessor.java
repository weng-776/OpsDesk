package com.opsdesk.ai.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 让 Milvus 相关的两个 bean <b>延迟创建</b>（工单 D12-03，规格基线 §12.2 / 本批特有约束「外部依赖不可用不能拖垮启动」）
 *
 * <h2>🔴 不这么做会怎样（实测，不是推测）</h2>
 * 把 Milvus 指向不可达地址启动应用，日志是：
 * <pre>
 * ERROR i.m.client.AbstractMilvusGrpcClient - Failed to initialize connection.
 *       DEADLINE_EXCEEDED: deadline exceeded after 9.95s [wait_for_ready, waiting_for_connection]
 * WARN  ... AnnotationConfigServletWebServerApplicationContext - Exception encountered during
 *       context initialization - cancelling refresh attempt:
 *       UnsatisfiedDependencyException: Error creating bean with name 'vectorStore'
 *         → Error creating bean with name 'milvusClient'
 *             → Failed to instantiate [io.milvus.client.MilvusServiceClient]:
 *               Failed to initialize connection
 *               at io.milvus.client.MilvusServiceClient.&lt;init&gt;(MilvusServiceClient.java:191)
 * </pre>
 * <b>整个 Spring 上下文起不来</b> —— 连单元测试都跑不了（这正是 Day 3 连踩两次的坑）。
 *
 * <h2>根因</h2>
 * <ol>
 *   <li>{@code MilvusVectorStoreAutoConfiguration} 的类级条件只有
 *       {@code @ConditionalOnProperty(spring.ai.vectorstore.type=milvus, matchIfMissing=true)} ——
 *       <b>只看属性、不看 Milvus 活着没有</b>，而且 yml 不配也生效；</li>
 *   <li>它注册的 {@code milvusClient} / {@code vectorStore} 都是<b>非 lazy 单例</b>
 *       → Spring 启动时必然实例化；</li>
 *   <li>{@code MilvusServiceClient} 的<b>构造函数</b>里就做连接检查（{@code wait_for_ready}），
 *       连不上直接抛，还会<b>阻塞约 10 秒</b>；
 *       {@code MilvusVectorStore#afterPropertiesSet()} 里的 {@code createCollection()} 也没有 try-catch。</li>
 * </ol>
 * ⚠️ 注意<b>不是</b> {@code initialize-schema} 的锅：把它设为 {@code false} 也没用 ——
 * bean 构造阶段就失败了，{@code afterPropertiesSet()} 根本走不到。
 *
 * <h2>做法</h2>
 * 在「bean 定义都注册完、单例开始创建前」这个时机，把这两个定义标记成 lazy：
 * <pre>
 *   启动时       → 不创建 → 不连 Milvus → 应用正常启动 ✅（验收：Milvus 停掉时应用仍能起）
 *   首次调用     → 创建 → 成功则正常索引 / 失败则被 KnowledgeIndexService 捕获 → 文档置 FAILED
 *   Milvus 恢复后 → 下次调用重新尝试（Spring 不缓存单例创建失败）→ 无需重启应用
 * </pre>
 *
 * <h2>⚠️ 为什么不是其它方案</h2>
 * <ul>
 *   <li><b>不用 {@code @Lazy} 注解</b>：那是框架的自动配置类，改不了。在注入点标 {@code @Lazy} 只能延后代理，
 *       <b>不会</b>把 bean 定义本身变 lazy —— 启动时 {@code preInstantiateSingletons()} 照样创建它；</li>
 *   <li><b>不自己装配 {@code MilvusVectorStore}</b>：要复刻 8+ 个参数（databaseName / collectionName /
 *       embeddingDimension / indexType / metricType / initializeSchema / ...），漏一个就与 yml 语义不一致，
 *       框架升级时更跟不上；</li>
 *   <li><b>不用全局 {@code spring.main.lazy-initialization=true}</b>：会作用于<b>所有</b> bean
 *       （含三十多个集成测试上下文），把启动期错误推迟到运行期才暴露，副作用面太大。</li>
 * </ul>
 *
 * <h2>⚠️ 光靠这里还不够</h2>
 * bean 定义变 lazy 只保证「启动不创建」。业务侧<b>必须</b>用 {@code ObjectProvider<VectorStore>} 取用
 * —— 构造器直接注入 {@code VectorStore} 会因为「必需依赖」而立刻触发创建，等于白改。
 * 见 {@code KnowledgeIndexServiceImpl}。
 */
@Component
public class LazyMilvusBeansPostProcessor implements BeanFactoryPostProcessor {

    /**
     * 需要变成 lazy 的 bean 名字。
     *
     * <p><b>两个都要</b> —— 实测显示第一个失败的其实是 {@code milvusClient}（它在构造函数里就连），
     * {@code vectorStore} 只是被它连带。只改后者没用。
     */
    private static final List<String> LAZY_BEAN_NAMES = List.of("milvusClient", "vectorStore");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        for (String name : LAZY_BEAN_NAMES) {
            // 用 containsBeanDefinition 保护：万一将来换了向量库实现（比如降级成 SimpleVectorStore），
            // 这些 bean 不存在也不该让启动失败
            if (beanFactory.containsBeanDefinition(name)) {
                beanFactory.getBeanDefinition(name).setLazyInit(true);
            }
        }
    }
}

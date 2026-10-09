package com.opsdesk.common.storage;

import java.io.InputStream;

/**
 * 对象存储门面（工单 D3-05）
 *
 * <h2>为什么再包一层，而不直接在 Service 里用 {@code MinioClient}</h2>
 * <ul>
 *   <li><b>可测</b>：MinIO 是外部依赖，CI / 本地不一定起得来。Service 依赖本接口，
 *       测试可以换成内存实现，把「路径拼接 / 白名单 / 数据范围」这些<b>真正要审的逻辑</b>
 *       和「网络存储」解耦开来测。</li>
 *   <li><b>可替换</b>：将来换 S3 / OSS / 本地磁盘，只换实现类。</li>
 *   <li><b>收口异常</b>：MinIO SDK 抛一堆受检异常（{@code MinioException} 家族），
 *       让它们别漏到业务层 —— 换成 {@code BizException(50300)} 这一个出口。</li>
 * </ul>
 *
 * <p>⚠️ 本接口<b>只做存储</b>，不碰任何业务规则：不校验类型、不校验大小、不判数据范围。
 * 那些是 Service 的职责（SOP §5：安全边界要在一个地方看得清）。
 */
public interface ObjectStorage {

    /**
     * 上传对象。
     *
     * @param objectName  对象名（调用方保证已 UUID 化、已限定前缀，见 {@code AttachmentServiceImpl}）
     * @param in          数据流；实现负责关闭
     * @param size        字节数（用于 MinIO 分片阈值判断，必须准确 —— 传 -1 会让 SDK 走 -1 的慢路径）
     * @param contentType MIME，可为空
     */
    void put(String objectName, InputStream in, long size, String contentType);

    /**
     * 取对象流。调用方负责关闭。
     *
     * @throws com.opsdesk.common.BizException {@code 50300} —— 依赖服务不可用
     */
    InputStream get(String objectName);

    /** 对象是否存在（下载前的兜底检查：DB 有记录但对象被误删时要给出明确错误而不是空流） */
    boolean exists(String objectName);
}

package com.opsdesk.common.storage.impl;

import com.opsdesk.common.BizException;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.config.properties.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * {@link ObjectStorage} 的 MinIO 实现（工单 D3-05）
 *
 * <p>把 {@code MinioClient} 的受检异常统一转成 {@code BizException}，业务层只需要认一个出口。
 */
@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioObjectStorage(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /**
     * 启动时确保 bucket 存在（不存在则建）。
     *
     * <p>⚠️ 这里<b>故意不让它失败就中断启动</b>：MinIO 没起来时整个应用起不来，
     * 会让「只想跑个单元测试」也起不来。所以只打 warn，真正用的时候再报错。
     * 这与 D3-03 测试里 Milvus 未启动导致整个 ApplicationContext 加载失败是同一类坑
     * —— 外部依赖不应该成为启动的硬门槛。
     */
    @PostConstruct
    void ensureBucket() {
        String bucket = properties.getBucketName();
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[对象存储] bucket 不存在，已创建：{}", bucket);
            }
        }
        catch (Exception ex) {
            log.warn("[对象存储] 启动时确认 bucket 失败（不阻断启动，使用时再报错）：bucket={} cause={}",
                    bucket, ex.getMessage());
        }
    }

    @Override
    public void put(String objectName, InputStream in, long size, String contentType) {
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .stream(in, size, -1);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            minioClient.putObject(builder.build());
        }
        catch (Exception ex) {
            log.error("[对象存储] 上传失败 objectName={} size={}", objectName, size, ex);
            throw new BizException(com.opsdesk.common.ErrorCode.SERVICE_UNAVAILABLE,
                    "附件存储服务不可用，请稍后重试");
        }
    }

    @Override
    public InputStream get(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .build());
        }
        catch (ErrorResponseException ex) {
            // 对象不存在 → 这不是「依赖不可用」，是数据不一致（DB 有记录、MinIO 没有）
            log.error("[对象存储] 取流失败：对象不存在 objectName={}", objectName, ex);
            throw new BizException(com.opsdesk.common.ErrorCode.NOT_FOUND,
                    "附件文件已丢失，请联系管理员");
        }
        catch (Exception ex) {
            log.error("[对象存储] 取流失败 objectName={}", objectName, ex);
            throw new BizException(com.opsdesk.common.ErrorCode.SERVICE_UNAVAILABLE,
                    "附件存储服务不可用，请稍后重试");
        }
    }

    @Override
    public boolean exists(String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .build());
            return true;
        }
        catch (ErrorResponseException ex) {
            // NoSuchKey / NoSuchBucket → 明确不存在（不是故障）
            return false;
        }
        catch (Exception ex) {
            log.warn("[对象存储] 判断对象是否存在失败 objectName={} cause={}", objectName, ex.getMessage());
            return false;
        }
    }
}

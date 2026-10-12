package com.opsdesk.knowledge.parser;

import java.io.InputStream;

/**
 * 知识文档解析器（工单 D12-01，规格基线 §12.2）
 *
 * <h2>为什么做成「接口 + 多实现」</h2>
 * 规格 §12.2 的表格里列了 4 种格式（md / txt / docx / pdf），但同一格的理由栏写着
 * 「不引入重型解析框架」，而 §0.5 决策 2 拍板为 <b>B：第一版只支持 md / txt</b>
 * （新增 POI / PDFBox 属 SOP §6 黑名单）。
 *
 * <p>所以这里把「解析」抽象成接口，本版只提供 {@link TextDocumentParser}。
 * 将来要开放 docx / pdf，<b>只需新增一个实现类</b>：
 * <pre>
 * class DocxDocumentParser implements KnowledgeDocumentParser { supports("docx") ... }
 * </pre>
 * 调用方按 {@link #supports(String)} 路由（Spring 注入 {@code List<KnowledgeDocumentParser>}），
 * <b>接口契约与上传流程一行都不用改</b>。
 *
 * <h2>⚠️ 解析器的职责边界</h2>
 * 只做「字节 → 纯文本」。<b>不</b>负责：大小校验（上传层做）、文件类型白名单
 * （{@link KnowledgeDocumentTypes} 做）、切分 / Embedding / 入库（D12-05 的异步流水线做）。
 */
public interface KnowledgeDocumentParser {

    /**
     * 是否支持该扩展名。
     *
     * @param extension <b>已归一化</b>的扩展名（小写、不含点）—— 归一化由
     *                  {@link KnowledgeDocumentTypes#validate(String, String)} 保证
     */
    boolean supports(String extension);

    /**
     * 把文档解析成纯文本。
     *
     * @param in        文档流（调用方保证大小已在 20MB 上限内）
     * @param extension 已归一化的扩展名
     * @return 解析出的纯文本（可能为空串 —— 「内容为空」由调用方决定怎么处理）
     * @throws com.opsdesk.common.BizException 编码非法（40001）或读取失败（50000）
     */
    String parse(InputStream in, String extension);
}

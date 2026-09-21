package com.szh.memory;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆条目（L4）：脱离事件日志独立存储的结构化知识单元。
 * <p>
 * 一条记忆由「标题 + 正文 + 分类 + 作用域 + 关键词」构成，配合时间戳与来源会话，
 * 支持全文检索召回、按作用域过滤、以及后续的生命周期管理（去重/更新/淘汰）。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Data
public class Memory {

    /** 逻辑主键（存储层不强制唯一，save 以“先删后插”实现 upsert） */
    private String id;

    /** 简短标题，便于召回结果展示与人工审阅 */
    private String title;

    /** 记忆正文，检索与注入的核心内容 */
    private String content;

    private MemoryCategory category = MemoryCategory.OTHER;

    private MemoryScope scope = MemoryScope.PROJECT;

    /** 关键词，用于辅助召回与展示 */
    private List<String> keywords = new ArrayList<>();

    /** 产生该记忆的来源会话 id（反思抽取时写入），可为空 */
    private String sourceSessionId;

    private long createdAt;

    private long updatedAt;
}

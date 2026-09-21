package com.szh.model;

/**
 * @author demussong
 * @describe 模型流式输出监听器：SSE 增量 token 到达时回调，让上层（控制台/UI）边生成边展示，
 * 消除长回答阻塞等待的体验问题。
 * <p>
 * 回调在模型 IO 线程上同步执行，实现方应只做轻量输出，不要阻塞；
 * 回调抛出的异常会向上传播终止本次调用。默认全部 no-op，按需覆写。
 * @date 2026/9/21
 */
public interface StreamListener {

    /**
     * 思维链增量（reasoning_content / reasoning delta）
     */
    default void onReasoningDelta(String delta) {
    }

    /**
     * 回答正文增量（content / output_text delta）
     */
    default void onTextDelta(String delta) {
    }

    /**
     * 流结束（正常完成或出错前都会回调一次），供 UI 收尾换行等
     */
    default void onComplete() {
    }

    /**
     * 空实现：调用方未开启流式输出时使用
     */
    StreamListener NOOP = new StreamListener() {
    };
}

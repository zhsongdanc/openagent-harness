package com.szh.model;

/**
 * @author demussong
 * @describe 控制台流式监听器：正文增量实时打到 stdout（token-by-token），
 * 思维链增量默认丢弃（可通过开关打到 stderr 便于调试），流结束时补换行。
 * @date 2026/9/21
 */
public class ConsoleStreamListener implements StreamListener {

    /**
     * 是否把思维链增量输出到 stderr（默认关闭，避免刷屏）
     */
    private final boolean printReasoning;

    public ConsoleStreamListener() {
        this(false);
    }

    public ConsoleStreamListener(boolean printReasoning) {
        this.printReasoning = printReasoning;
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (printReasoning && delta != null && !delta.isEmpty()) {
            System.err.print(delta);
            System.err.flush();
        }
    }

    @Override
    public void onTextDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            System.out.print(delta);
            System.out.flush();
        }
    }

    @Override
    public void onComplete() {
        // 流式打印不带结尾换行，收尾补一个，避免后续日志与正文粘在同一行
        System.out.println();
        System.out.flush();
    }
}

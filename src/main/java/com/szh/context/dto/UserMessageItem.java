package com.szh.context.dto;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:50
 */
public class UserMessageItem implements MessageItem {

    private String content;

    public UserMessageItem(String content) {
        this.content = content;
    }

    @Override
    public String transfer2prompt() {
        return content;
    }

}

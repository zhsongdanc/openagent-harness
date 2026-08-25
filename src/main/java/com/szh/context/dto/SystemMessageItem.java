package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 14:00
 */
public class SystemMessageItem implements MessageItem{

    private String content;

    public SystemMessageItem(String content) {
        this.content = content;
    }


    @Override
    public String role() {
        return ROLEEnum.SYSTEM.getRole();
    }

    @Override
    public String transfer2prompt() {
        return content;
    }
}

package com.szh.context.dto;

import com.szh.model.dto.ROLEEnum;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 13:50
 */
@Data
@NoArgsConstructor
public class UserMessageItem implements MessageItem {

    private String content;

    public UserMessageItem(String content) {
        this.content = content;
    }

    @Override
    public String role() {
        return ROLEEnum.USER.getRole();
    }

    @Override
    public String transfer2prompt() {
        return content;
    }

}

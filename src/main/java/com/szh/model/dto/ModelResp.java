package com.szh.model.dto;

import com.szh.context.dto.AssistantMessageItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:23
 */
@Data
@AllArgsConstructor
public class ModelResp {

    private AssistantMessageItem message;

    private ActionEnum action;
}

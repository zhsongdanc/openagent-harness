package com.szh.model.dto;

import lombok.Data;

import java.util.List;

/**
 * @author demussong
 * @describe
 * @date 2026/8/25 12:23
 */
@Data
public class ModelResp {

    private InstructionEnum instructionEnum;
    private String reasoning;
    private String reply;
    private String func;
    private List<Object> funcArguments;
    private String skillName;
    private List<Object> skillArguments;
}

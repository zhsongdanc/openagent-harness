package com.szh.test;

import com.szh.agent.AgentResponseExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe
 * @date 2026/9/3 10:49
 */
@Slf4j
public class ShellTest {
    public static void main(String[] args) {
        testShellTool();
    }


    public static void testShellTool() {
        String userInput = "你能帮我看一下当前项目有哪些类型文件吗";
        AgentResponseExecutor agentExecutor = new AgentResponseExecutor();
        String reply = agentExecutor.runNewSession(userInput);
        System.out.println(reply);
    }
}

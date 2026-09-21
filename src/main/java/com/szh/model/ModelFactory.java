package com.szh.model;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author demussong
 * @describe 模型工厂：按配置装配 Chat Completions（{@link Model}）与 Responses API（{@link ResponseModel}）
 * 两条协议链的 Provider 实例，解除运行时对 DeepSeek 的硬编码依赖。
 * <p>
 * 配置口径（application.properties，均可用 -Dkey=value 覆盖）：
 * <ul>
 *   <li>model.provider：deepseek（默认）/ openai / ollama / vllm / lmstudio，
 *       后四者都走 OpenAI 兼容协议，只是 baseUrl 与鉴权约定不同；</li>
 *   <li>model.name / model.response.name：两条链路各自的模型名；</li>
 *   <li>model.{provider}.baseUrl / model.{provider}.apiKey：接入点与密钥，
 *       apiKey 未配置时按 Provider 约定回退环境变量（DEEPSEEK_API_KEY / OPENAI_API_KEY），
 *       本地服务（ollama 等）允许空密钥。</li>
 * </ul>
 * Gemini 等非 OpenAI 兼容协议暂未实现，接入时在此工厂新增分支即可，运行时无需改动。
 * @date 2026/9/21
 */
@Slf4j
public class ModelFactory {

    private ModelFactory() {
    }

    /**
     * 创建 Chat Completions 协议模型（AgentRuntime 链路）
     */
    public static Model createChatModel() {
        String provider = ConfigUtil.get("model.provider", "deepseek").toLowerCase();
        String modelName = ConfigUtil.get("model.name", defaultChatModel(provider));
        return switch (provider) {
            case "deepseek" -> new DeepSeekModel(resolveApiKey(provider), modelName);
            case "openai", "ollama", "vllm", "lmstudio" -> new OpenAiCompatModel(
                    resolveBaseUrl(provider), modelName, resolveApiKey(provider), providerLabel(provider));
            default -> throw new IllegalArgumentException(
                    "unsupported model.provider: " + provider + "（可选 deepseek/openai/ollama/vllm/lmstudio）");
        };
    }

    /**
     * 创建 Responses API 协议模型（AgentResponseRuntime 链路）。
     * Responses API 目前仅 DeepSeek/OpenAI 提供，其余 Provider 显式报错而非静默降级，
     * 避免用户以为在用本地模型实际却在烧远端 API。
     */
    public static ResponseModel createResponseModel() {
        String provider = ConfigUtil.get("model.response.provider",
                ConfigUtil.get("model.provider", "deepseek")).toLowerCase();
        String modelName = ConfigUtil.get("model.response.name", defaultResponseModel(provider));
        return switch (provider) {
            case "deepseek" -> new DeepSeekResponseModel(resolveApiKey(provider), modelName);
            case "openai" -> new OpenAiCompatResponseModel(
                    resolveBaseUrl(provider), modelName, resolveApiKey(provider), providerLabel(provider));
            default -> throw new IllegalArgumentException(
                    "provider " + provider + " 不支持 Responses API（可选 deepseek/openai），"
                            + "请改用 AgentRuntime(Chat Completions) 链路");
        };
    }

    private static String defaultChatModel(String provider) {
        return switch (provider) {
            case "openai" -> "gpt-4o";
            case "ollama" -> "llama3.1";
            default -> "deepseek-chat";
        };
    }

    private static String defaultResponseModel(String provider) {
        return switch (provider) {
            case "openai" -> "gpt-4o";
            default -> "deepseek-v4-flash";
        };
    }

    /**
     * baseUrl 解析：优先 model.{provider}.baseUrl，其次按 Provider 给默认值
     */
    private static String resolveBaseUrl(String provider) {
        String configured = ConfigUtil.get("model." + provider + ".baseUrl");
        if (configured != null) {
            return configured;
        }
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openai" -> "https://api.openai.com/v1";
            case "ollama" -> "http://localhost:11434/v1";
            case "vllm" -> "http://localhost:8000/v1";
            case "lmstudio" -> "http://localhost:1234/v1";
            default -> throw new IllegalArgumentException("no default baseUrl for provider: " + provider);
        };
    }

    /**
     * apiKey 解析：优先 model.{provider}.apiKey 配置，其次 Provider 约定的环境变量；
     * 本地推理服务（ollama/vllm/lmstudio）不校验密钥，返回占位值即可
     */
    private static String resolveApiKey(String provider) {
        String configured = ConfigUtil.get("model." + provider + ".apiKey");
        if (configured != null) {
            return configured;
        }
        String envKey = switch (provider) {
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            default -> null;
        };
        if (envKey != null) {
            String fromEnv = System.getenv(envKey);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
            throw new IllegalStateException("missing api key for provider " + provider
                    + ": set env " + envKey + " or config model." + provider + ".apiKey");
        }
        // 本地服务无鉴权，OpenAI 兼容层要求 Bearer 头非空，给占位值
        return "not-needed";
    }

    private static String providerLabel(String provider) {
        return provider.substring(0, 1).toUpperCase() + provider.substring(1);
    }
}

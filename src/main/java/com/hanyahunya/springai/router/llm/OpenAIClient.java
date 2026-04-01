package com.hanyahunya.springai.router.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI HTTP 클라이언트 (Responses API).
 *
 * ── 프롬프트 캐싱 (24시간 Extended Retention) ─────────────────────
 * 요청 바디에 "prompt_cache_retention": "24h" 포함.
 * instructions(고정) + tools(고정) → 자동 캐시 prefix.
 * input(변경) → 캐시 제외.
 * 캐시 확인: usage.input_tokens_details.cached_tokens
 *
 * ── 디버그 로그 활성화 방법 ────────────────────────────────────────
 * application.yml:
 *   logging:
 *     level:
 *       com.hanyahunya.springai.router.llm.OpenAIClient: DEBUG
 */
public class OpenAIClient extends AbstractHttpLLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClient.class);

    private static final String BASE_URL = "https://api.openai.com";
    private static final String ENDPOINT = "/v1/responses";

    public OpenAIClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE,  "application/json")
                .build();
    }

    @Override
    protected String getEndpointPath() {
        return ENDPOINT;
    }

    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getEffectiveModel());
        body.put("prompt_cache_retention", "24h");

        // ── instructions: system prompt (캐시 prefix, 고정) ───────
        body.put("instructions", SYSTEM_PROMPT);

        // ── input: 대화 기록 (캐시 제외) ──────────────────────────
        ArrayNode inputArray = objectMapper.createArrayNode();
        for (Message m : messages) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role",    m.role());
            msg.put("content", m.content());
            inputArray.add(msg);
        }
        body.set("input", inputArray);

        // ── tools (캐시 prefix, 알파벳순 정렬로 순서 고정) ─────────
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ToolSpec t : tools) {
                ObjectNode tool = objectMapper.createObjectNode();
                tool.put("type",        "function");
                tool.put("name",        t.name());
                tool.put("description", t.description());

                ObjectNode parameters = objectMapper.createObjectNode();
                parameters.put("type", "object");
                parameters.set("properties", buildJsonSchemaProperties(t.parameterSchema()));
                tool.set("parameters", parameters);

                toolsArray.add(tool);
            }
            body.set("tools", toolsArray);
        }

        return body;
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolSpec> tools) {
        try {
            ObjectNode requestBody = buildRequestBody(messages, tools);

            // ── 디버그: 요청 프롬프트 ─────────────────────────────
            if (log.isDebugEnabled()) {
                log.debug("""

                        //====== OpenAI 실제 프롬프트 ======//
                        모델: {}
                        턴 수 (input): {}
                        Tool 수: {}
                        prompt_cache_retention: {}
                        --- instructions (캐시 prefix) ---
                        {}
                        --- tools (캐시 prefix) ---
                        {}
                        --- input (캐시 제외) ---
                        {}
                        //====== 프롬프트 끝 ======//
                        """,
                        props.getEffectiveModel(),
                        messages.size(),
                        tools.size(),
                        requestBody.path("prompt_cache_retention").asText(),
                        requestBody.path("instructions").asText(),
                        requestBody.path("tools").toPrettyString(),
                        requestBody.path("input").toPrettyString()
                );
            }

            String responseStr = restClient.post()
                    .uri(getEndpointPath())
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode responseJson = objectMapper.readTree(responseStr);

            // ── 디버그: 토큰 사용량 ───────────────────────────────
            if (log.isDebugEnabled()) {
                JsonNode usage       = responseJson.path("usage");
                int inputTokens      = usage.path("input_tokens").asInt();
                int outputTokens     = usage.path("output_tokens").asInt();
                int cachedTokens     = usage.path("input_tokens_details").path("cached_tokens").asInt();

                log.debug("""

                        //====== OpenAI 토큰 사용량 ======//
                        input_tokens:  {}
                        cached_tokens: {}
                        output_tokens: {}
                        total_tokens:  {}
                        //====== 토큰 끝 ======//
                        """,
                        inputTokens,
                        cachedTokens,
                        outputTokens,
                        inputTokens + outputTokens
                );
            }

            return parseResponse(responseJson);

        } catch (Exception e) {
            log.error("OpenAI API 호출 실패: {}", e.getMessage(), e);
            return new LLMResponse(LLMResponse.Type.TEXT,
                    "LLM 요청 중 오류가 발생했습니다: " + e.getMessage(), null, null);
        }
    }

    @Override
    protected LLMResponse parseResponse(JsonNode root) {
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            log.warn("OpenAI 응답에 output 배열이 없습니다: {}", root.toPrettyString());
            return new LLMResponse(LLMResponse.Type.TEXT, "응답을 파싱할 수 없습니다.", null, null);
        }

        for (JsonNode item : output) {
            String type = item.path("type").asText();

            if ("function_call".equals(type)) {
                String toolName = item.path("name").asText();
                String argsJson = item.path("arguments").asText();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(argsJson, HashMap.class);
                    log.debug("OpenAI TOOL_CALL: {} args={}", toolName, args);
                    return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
                } catch (Exception e) {
                    return new LLMResponse(LLMResponse.Type.TEXT,
                            "Tool 파라미터 파싱 오류: " + e.getMessage(), null, null);
                }
            }

            if ("message".equals(type)) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("output_text".equals(block.path("type").asText())) {
                            String text = block.path("text").asText();
                            log.debug("OpenAI TEXT 응답: {}", text);
                            return new LLMResponse(LLMResponse.Type.TEXT, text, null, null);
                        }
                    }
                }
            }
        }

        return new LLMResponse(LLMResponse.Type.TEXT, "빈 응답이 반환되었습니다.", null, null);
    }
}
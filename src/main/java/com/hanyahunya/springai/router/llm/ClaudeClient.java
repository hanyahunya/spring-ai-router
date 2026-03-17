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
 * Claude (Anthropic) HTTP 클라이언트.
 *
 * ── 프롬프트 캐싱 ─────────────────────────────────────────────────
 * cache_control: { "type": "ephemeral" }
 * system prompt + 마지막 tool에 적용.
 * 최소 캐시 토큰: Opus 4.6 = 4096, Sonnet 4.5 = 1024
 *
 * ── 디버그 로그 활성화 방법 ────────────────────────────────────────
 * application.yml:
 *   logging:
 *     level:
 *       com.hanyahunya.springai.router.llm.ClaudeClient: DEBUG
 */
public class ClaudeClient extends AbstractHttpLLMClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private static final String BASE_URL    = "https://api.anthropic.com";
    private static final String ENDPOINT    = "/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private static final ObjectNode CACHE_CONTROL;

    static {
        CACHE_CONTROL = STATIC_MAPPER.createObjectNode();
        CACHE_CONTROL.put("type", "ephemeral");
    }

    public ClaudeClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key",         props.getApiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader("anthropic-beta",    "prompt-caching-2024-07-31")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    @Override
    protected String getEndpointPath() {
        return ENDPOINT;
    }

    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",      props.getEffectiveModel());
        body.put("max_tokens", 1024);

        // ── system prompt (캐시 적용) ─────────────────────────────
        ArrayNode  systemArray = objectMapper.createArrayNode();
        ObjectNode systemBlock = objectMapper.createObjectNode();
        systemBlock.put("type", "text");
        systemBlock.put("text", SYSTEM_PROMPT);
        systemBlock.set("cache_control", CACHE_CONTROL.deepCopy());
        systemArray.add(systemBlock);
        body.set("system", systemArray);

        // ── messages (캐시 제외, 요청마다 변경) ───────────────────
        body.set("messages", buildMessagesArray(messages));

        // ── tools (캐시 적용 - 마지막 tool에 cache_control) ───────
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (int i = 0; i < tools.size(); i++) {
                ToolSpec   t    = tools.get(i);
                ObjectNode tool = objectMapper.createObjectNode();
                tool.put("name",        t.name());
                tool.put("description", t.description());

                ObjectNode inputSchema = objectMapper.createObjectNode();
                inputSchema.put("type", "object");
                inputSchema.set("properties", buildJsonSchemaProperties(t.parameterSchema()));
                tool.set("input_schema", inputSchema);

                if (i == tools.size() - 1) {
                    tool.set("cache_control", CACHE_CONTROL.deepCopy());
                }
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
                        
                        //====== Claude 실제 프롬프트 ======//
                        모델: {}
                        턴 수 (messages): {}
                        Tool 수: {}
                        --- system ---
                        {}
                        --- tools ---
                        {}
                        --- messages (캐시 제외) ---
                        {}
                        //====== 프롬프트 끝 ======//
                        """, props.getEffectiveModel(), messages.size(), tools.size(),
                        requestBody.path("system").toPrettyString(),
                        requestBody.path("tools").toPrettyString(),
                        requestBody.path("messages").toPrettyString()
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
                JsonNode usage = responseJson.path("usage");
                log.debug("""
                        
                        //====== Claude 토큰 사용량 ======//
                        input_tokens:                {}
                        cache_creation_input_tokens: {}
                        cache_read_input_tokens:     {}
                        output_tokens:               {}
                        //====== 토큰 끝 ======//
                        """,
                        usage.path("input_tokens").asInt(),
                        usage.path("cache_creation_input_tokens").asInt(),
                        usage.path("cache_read_input_tokens").asInt(),
                        usage.path("output_tokens").asInt()
                );
            }

            return parseResponse(responseJson);

        } catch (Exception e) {
            log.error("Claude API 호출 실패: {}", e.getMessage(), e);
            return new LLMResponse(LLMResponse.Type.TEXT,
                    "LLM 요청 중 오류가 발생했습니다: " + e.getMessage(), null, null);
        }
    }

    @Override
    protected LLMResponse parseResponse(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            return new LLMResponse(LLMResponse.Type.TEXT, "응답을 파싱할 수 없습니다.", null, null);
        }

        for (JsonNode block : content) {
            String type = block.path("type").asText();

            if ("tool_use".equals(type)) {
                String toolName = block.path("name").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.convertValue(block.path("input"), HashMap.class);
                log.debug("Claude TOOL_CALL: {} args={}", toolName, args);
                return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
            }

            if ("text".equals(type)) {
                String text = block.path("text").asText();
                log.debug("Claude TEXT 응답: {}", text);
                return new LLMResponse(LLMResponse.Type.TEXT, text, null, null);
            }
        }

        return new LLMResponse(LLMResponse.Type.TEXT, "빈 응답이 반환되었습니다.", null, null);
    }
}
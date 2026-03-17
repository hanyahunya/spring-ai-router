package com.hanyahunya.springai.router.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini (Google) HTTP 클라이언트.
 *
 * ── 프롬프트 캐싱 (Explicit, TTL 3600s = 1시간) ───────────────────
 * 앱 시작 시 cachedContents API로 system_instruction + tools를 캐시 등록.
 * 이후 요청에서 cached_content 이름 참조 → 토큰 절약.
 * TTL 형식: "3600s" (Duration 형식, 초단위 + 's' 접미사)
 *
 * ── 디버그 로그 활성화 방법 ────────────────────────────────────────
 * application.yml:
 *   logging:
 *     level:
 *       com.hanyahunya.springai.router.llm.GeminiClient: DEBUG
 */
public class GeminiClient extends AbstractHttpLLMClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final String BASE_URL  = "https://generativelanguage.googleapis.com";
    private static final String CACHE_TTL = "3600s";

    private volatile String  cachedContentName;
    private volatile Instant cacheExpiresAt;

    public GeminiClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    protected String getEndpointPath() {
        String model = props.getEffectiveModel();
        return "/v1beta/models/" + model + ":generateContent?key=" + props.getApiKey();
    }

    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools) {
        ObjectNode body = objectMapper.createObjectNode();

        if (isCacheValid()) {
            log.debug("Gemini 캐시 사용: {}", cachedContentName);
            body.put("cached_content", cachedContentName);
        } else {
            log.debug("Gemini 캐시 없음 또는 만료 → 직접 전송");
            body.set("system_instruction", buildSystemInstruction());
            if (!tools.isEmpty()) {
                body.set("tools", buildToolsNode(tools));
            }
        }

        body.set("contents", buildContents(messages));
        return body;
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolSpec> tools) {
        try {
            ObjectNode requestBody = buildRequestBody(messages, tools);

            // ── 디버그: 요청 프롬프트 ─────────────────────────────
            if (log.isDebugEnabled()) {
                log.debug("""

                        //====== Gemini 실제 프롬프트 ======//
                        모델: {}
                        턴 수 (contents): {}
                        Tool 수: {}
                        캐시 이름: {}
                        --- system_instruction ---
                        {}
                        --- tools ---
                        {}
                        --- contents (캐시 제외) ---
                        {}
                        //====== 프롬프트 끝 ======//
                        """,
                        props.getEffectiveModel(),
                        messages.size(),
                        tools.size(),
                        cachedContentName != null ? cachedContentName : "없음 (직접 전송)",
                        requestBody.has("system_instruction")
                                ? requestBody.path("system_instruction").toPrettyString()
                                : "(캐시 사용 중 - 생략)",
                        requestBody.has("tools")
                                ? requestBody.path("tools").toPrettyString()
                                : "(캐시 사용 중 - 생략)",
                        requestBody.path("contents").toPrettyString()
                );
            }

            String responseStr = restClient.post()
                    .uri(getEndpointPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode responseJson = objectMapper.readTree(responseStr);

            // ── 디버그: 토큰 사용량 ───────────────────────────────
            if (log.isDebugEnabled()) {
                JsonNode usage = responseJson.path("usageMetadata");
                log.debug("""

                        //====== Gemini 토큰 사용량 ======//
                        promptTokenCount:        {}
                        cachedContentTokenCount: {}
                        candidatesTokenCount:    {}
                        totalTokenCount:         {}
                        //====== 토큰 끝 ======//
                        """,
                        usage.path("promptTokenCount").asInt(),
                        usage.path("cachedContentTokenCount").asInt(),
                        usage.path("candidatesTokenCount").asInt(),
                        usage.path("totalTokenCount").asInt()
                );
            }

            return parseResponse(responseJson);

        } catch (Exception e) {
            log.error("Gemini API 호출 실패: {}", e.getMessage(), e);
            return new LLMResponse(LLMResponse.Type.TEXT,
                    "LLM 요청 중 오류가 발생했습니다: " + e.getMessage(), null, null);
        }
    }

    public void initializeCache(List<ToolSpec> tools) {
        try {
            ObjectNode cacheBody = objectMapper.createObjectNode();
            cacheBody.put("model", "models/" + props.getEffectiveModel());
            cacheBody.put("ttl",   CACHE_TTL);
            cacheBody.set("system_instruction", buildSystemInstruction());
            if (!tools.isEmpty()) {
                cacheBody.set("tools", buildToolsNode(tools));
            }

            log.debug("Gemini 캐시 생성 요청:\n{}", cacheBody.toPrettyString());

            String response = restClient.post()
                    .uri("/v1beta/cachedContents?key=" + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cacheBody.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode responseJson = objectMapper.readTree(response);
            cachedContentName = responseJson.path("name").asText();
            cacheExpiresAt    = Instant.now().plusSeconds(3600);

            log.info("[spring-ai-router] Gemini 캐시 생성 완료: {} (TTL: {})", cachedContentName, CACHE_TTL);

        } catch (Exception e) {
            log.warn("[spring-ai-router] Gemini 캐시 생성 실패, 직접 요청으로 fallback: {}", e.getMessage());
            cachedContentName = null;
            cacheExpiresAt    = null;
        }
    }

    public void renewCacheIfNeeded(List<ToolSpec> tools) {
        if (cachedContentName != null
                && cacheExpiresAt != null
                && Instant.now().isAfter(cacheExpiresAt.minusSeconds(60))) {
            log.info("[spring-ai-router] Gemini 캐시 만료 임박, 갱신 중...");
            initializeCache(tools);
        }
    }

    private boolean isCacheValid() {
        return cachedContentName != null
                && cacheExpiresAt != null
                && Instant.now().isBefore(cacheExpiresAt.minusSeconds(60));
    }

    private ObjectNode buildSystemInstruction() {
        ObjectNode systemInstruction = objectMapper.createObjectNode();
        ArrayNode  sysParts          = objectMapper.createArrayNode();
        ObjectNode sysPart           = objectMapper.createObjectNode();
        sysPart.put("text", SYSTEM_PROMPT);
        sysParts.add(sysPart);
        systemInstruction.set("parts", sysParts);
        return systemInstruction;
    }

    private ArrayNode buildToolsNode(List<ToolSpec> tools) {
        ArrayNode  toolsArray       = objectMapper.createArrayNode();
        ObjectNode toolObj          = objectMapper.createObjectNode();
        ArrayNode  funcDeclarations = objectMapper.createArrayNode();

        for (ToolSpec t : tools) {
            ObjectNode func = objectMapper.createObjectNode();
            func.put("name",        t.name());
            func.put("description", t.description());

            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "OBJECT");
            parameters.set("properties", buildJsonSchemaProperties(t.parameterSchema()));
            func.set("parameters", parameters);
            funcDeclarations.add(func);
        }
        toolObj.set("function_declarations", funcDeclarations);
        toolsArray.add(toolObj);
        return toolsArray;
    }

    private ArrayNode buildContents(List<Message> messages) {
        ArrayNode contents = objectMapper.createArrayNode();
        for (Message m : messages) {
            ObjectNode content = objectMapper.createObjectNode();
            content.put("role", "assistant".equals(m.role()) ? "model" : "user");
            ArrayNode  parts   = objectMapper.createArrayNode();
            ObjectNode part    = objectMapper.createObjectNode();
            part.put("text", m.content());
            parts.add(part);
            content.set("parts", parts);
            contents.add(content);
        }
        return contents;
    }

    @Override
    protected LLMResponse parseResponse(JsonNode root) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");

        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (!part.path("functionCall").isMissingNode()) {
                    String toolName = part.path("functionCall").path("name").asText();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.convertValue(
                            part.path("functionCall").path("args"), HashMap.class);
                    log.debug("Gemini TOOL_CALL: {} args={}", toolName, args);
                    return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
                }
                if (!part.path("text").isMissingNode()) {
                    String text = part.path("text").asText();
                    log.debug("Gemini TEXT 응답: {}", text);
                    return new LLMResponse(LLMResponse.Type.TEXT, text, null, null);
                }
            }
        }
        return new LLMResponse(LLMResponse.Type.TEXT, "응답을 파싱할 수 없습니다.", null, null);
    }
}
package com.hanyahunya.springai.router.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * HTTP 기반 LLM 클라이언트 공통 추상 클래스.
 * Claude, OpenAI, Gemini 구현체는 이 클래스를 상속받아
 * 공급자별 요청/응답 변환만 구현합니다.
 */
public abstract class AbstractHttpLLMClient implements LLMClient {

    // static 블록에서 사용할 수 있도록 static으로 선언
    protected static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    protected final SpringAIRouterProperties props;
    protected final RestClient               restClient;
    protected final ObjectMapper             objectMapper = new ObjectMapper();

    protected static final String SYSTEM_PROMPT = """
            You are an AI router for a backend service.
            Your job is to understand the user's intent and call the appropriate tool.
            If required parameters are missing, ask the user for them naturally \
            in the same language the user used.
            Do not call a tool until you have all required parameters.
            Keep your questions concise and clear.
            """;

    protected AbstractHttpLLMClient(SpringAIRouterProperties props, String baseUrl) {
        this.props      = props;
        this.restClient = buildRestClient(baseUrl);
    }

    // ── 공급자별 구현 메서드 ────────────────────────────────────────

    protected abstract ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools);
    protected abstract String     getEndpointPath();
    protected abstract LLMResponse parseResponse(JsonNode responseJson);
    protected abstract RestClient  buildRestClient(String baseUrl);

    // ── 공통 실행 로직 ─────────────────────────────────────────────

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolSpec> tools) {
        try {
            ObjectNode requestBody = buildRequestBody(messages, tools);

            String responseStr = restClient.post()
                    .uri(getEndpointPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode responseJson = objectMapper.readTree(responseStr);
            return parseResponse(responseJson);

        } catch (Exception e) {
            return new LLMResponse(
                    LLMResponse.Type.TEXT,
                    "LLM 요청 중 오류가 발생했습니다: " + e.getMessage(),
                    null, null
            );
        }
    }

    // ── 공통 유틸 ──────────────────────────────────────────────────

    protected ArrayNode buildMessagesArray(List<Message> messages) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Message m : messages) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role",    m.role());
            node.put("content", m.content());
            arr.add(node);
        }
        return arr;
    }

    protected ObjectNode buildJsonSchemaProperties(Map<String, Object> schema) {
        return objectMapper.valueToTree(schema);
    }
}
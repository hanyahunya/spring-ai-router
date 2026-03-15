package com.hanyahunya.springai.router.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * HTTP 기반 LLM 클라이언트 공통 추상클래스.
 *
 * 모든 LLM 공급자(Claude, OpenAI, Gemini)는 이 클래스를 상속받아
 * 공급자별 요청/응답 형식 변환만 구현합니다.
 *
 * 공통 제공:
 *  - RestClient (HTTP 클라이언트)
 *  - ObjectMapper (JSON 직렬화)
 *  - 시스템 프롬프트
 *  - HTTP POST 요청 전송
 */
public abstract class AbstractHttpLLMClient implements LLMClient {

    protected final SpringAIRouterProperties props;
    protected final RestClient restClient;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected static final String SYSTEM_PROMPT = """
            You are an AI router for a backend service.
            Your job is to understand the user's intent and call the appropriate tool.
            If required parameters are missing, ask the user for them naturally \
            in the same language the user used.
            Do not call a tool until you have all required parameters.
            Keep your questions concise and clear.
            """;

    protected AbstractHttpLLMClient(SpringAIRouterProperties props, String baseUrl) {
        this.props = props;
        this.restClient = buildRestClient(baseUrl);
    }

    // ── 공급자별 구현 메서드 ────────────────────────────────────────

    /**
     * LLM API에 전달할 요청 바디를 공급자 형식에 맞게 생성합니다.
     */
    protected abstract ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools);

    /**
     * LLM API 호출 URL (경로 포함).
     * 예: /v1/chat/completions
     */
    protected abstract String getEndpointPath();

    /**
     * 응답 JSON을 LLMResponse로 파싱합니다.
     */
    protected abstract LLMResponse parseResponse(JsonNode responseJson);

    /**
     * 인증 헤더를 설정합니다 (공급자마다 형식이 다름).
     */
    protected abstract RestClient buildRestClient(String baseUrl);

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
            node.put("role", m.role());
            node.put("content", m.content());
            arr.add(node);
        }
        return arr;
    }

    protected ObjectNode buildJsonSchemaProperties(Map<String, Object> schema) {
        return objectMapper.valueToTree(schema);
    }
}

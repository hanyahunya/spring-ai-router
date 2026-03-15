package com.hanyahunya.springai.router.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI HTTP 클라이언트.
 * Chat Completions API: https://api.openai.com/v1/chat/completions
 */
public class OpenAIClient extends AbstractHttpLLMClient {

    private static final String BASE_URL = "https://api.openai.com";
    private static final String ENDPOINT = "/v1/chat/completions";

    public OpenAIClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
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
        body.put("model", props.getEffectiveModel());

        // 시스템 프롬프트 + 대화 기록
        ArrayNode msgs = objectMapper.createArrayNode();
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        msgs.add(systemMsg);
        msgs.addAll(buildMessagesArray(messages));
        body.set("messages", msgs);

        // Tool 목록
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ToolSpec t : tools) {
                ObjectNode tool      = objectMapper.createObjectNode();
                ObjectNode function  = objectMapper.createObjectNode();
                ObjectNode parameters = objectMapper.createObjectNode();

                parameters.put("type", "object");
                parameters.set("properties", buildJsonSchemaProperties(t.parameterSchema()));

                function.put("name", t.name());
                function.put("description", t.description());
                function.set("parameters", parameters);

                tool.put("type", "function");
                tool.set("function", function);
                toolsArray.add(tool);
            }
            body.set("tools", toolsArray);
            body.put("tool_choice", "auto");
        }

        return body;
    }

    @Override
    protected LLMResponse parseResponse(JsonNode json) {
        JsonNode message = json.path("choices").path(0).path("message");

        // tool_calls 확인
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            JsonNode toolCall = toolCalls.get(0);
            String toolName   = toolCall.path("function").path("name").asText();
            String argsStr    = toolCall.path("function").path("arguments").asText();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(argsStr, HashMap.class);
                return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
            } catch (Exception e) {
                return new LLMResponse(LLMResponse.Type.TEXT,
                        "Tool 파라미터 파싱 오류: " + e.getMessage(), null, null);
            }
        }

        // 텍스트 응답
        String content = message.path("content").asText();
        return new LLMResponse(LLMResponse.Type.TEXT, content, null, null);
    }
}

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
 * Google Gemini HTTP 클라이언트.
 * GenerateContent API: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 */
public class GeminiClient extends AbstractHttpLLMClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";

    public GeminiClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        // Gemini는 API 키를 쿼리 파라미터로 전달 → 헤더 불필요
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    @Override
    protected String getEndpointPath() {
        // API 키를 쿼리 파라미터로 포함
        return "/v1beta/models/" + props.getEffectiveModel()
                + ":generateContent?key=" + props.getApiKey();
    }

    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolSpec> tools) {
        ObjectNode body = objectMapper.createObjectNode();

        // 시스템 지시사항
        ObjectNode systemInstruction = objectMapper.createObjectNode();
        ArrayNode  sysParts          = objectMapper.createArrayNode();
        ObjectNode sysPart           = objectMapper.createObjectNode();
        sysPart.put("text", SYSTEM_PROMPT);
        sysParts.add(sysPart);
        systemInstruction.set("parts", sysParts);
        body.set("system_instruction", systemInstruction);

        // 대화 기록 (Gemini는 "model" 사용, "assistant" 아님)
        ArrayNode contents = objectMapper.createArrayNode();
        for (Message m : messages) {
            ObjectNode content = objectMapper.createObjectNode();
            content.put("role", "assistant".equals(m.role()) ? "model" : "user");

            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode part = objectMapper.createObjectNode();
            part.put("text", m.content());
            parts.add(part);
            content.set("parts", parts);
            contents.add(content);
        }
        body.set("contents", contents);

        // Tool 목록
        if (!tools.isEmpty()) {
            ArrayNode toolsArray    = objectMapper.createArrayNode();
            ObjectNode toolObj      = objectMapper.createObjectNode();
            ArrayNode funcDecls     = objectMapper.createArrayNode();

            for (ToolSpec t : tools) {
                ObjectNode func       = objectMapper.createObjectNode();
                ObjectNode parameters = objectMapper.createObjectNode();

                parameters.put("type", "OBJECT");
                parameters.set("properties", buildJsonSchemaProperties(t.parameterSchema()));

                func.put("name", t.name());
                func.put("description", t.description());
                func.set("parameters", parameters);
                funcDecls.add(func);
            }

            toolObj.set("function_declarations", funcDecls);
            toolsArray.add(toolObj);
            body.set("tools", toolsArray);
        }

        return body;
    }

    @Override
    protected LLMResponse parseResponse(JsonNode json) {
        JsonNode parts = json.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return new LLMResponse(LLMResponse.Type.TEXT, "응답을 파싱할 수 없습니다.", null, null);
        }

        for (JsonNode part : parts) {
            // function call
            if (!part.path("functionCall").isMissingNode()) {
                String toolName = part.path("functionCall").path("name").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.convertValue(
                        part.path("functionCall").path("args"), HashMap.class);
                return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
            }
            // text
            if (!part.path("text").isMissingNode()) {
                return new LLMResponse(
                        LLMResponse.Type.TEXT, part.path("text").asText(), null, null);
            }
        }

        return new LLMResponse(LLMResponse.Type.TEXT, "빈 응답이 반환되었습니다.", null, null);
    }
}

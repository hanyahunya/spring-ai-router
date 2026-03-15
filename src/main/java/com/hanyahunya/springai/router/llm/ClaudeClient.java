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
 * Anthropic Claude HTTP 클라이언트.
 * Messages API: https://api.anthropic.com/v1/messages
 */
public class ClaudeClient extends AbstractHttpLLMClient {

    private static final String BASE_URL    = "https://api.anthropic.com";
    private static final String ENDPOINT    = "/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    public ClaudeClient(SpringAIRouterProperties props) {
        super(props, BASE_URL);
    }

    @Override
    protected RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", props.getApiKey())
                .defaultHeader("anthropic-version", API_VERSION)
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
        body.put("max_tokens", 1024);
        body.put("system", SYSTEM_PROMPT);
        body.set("messages", buildMessagesArray(messages));

        // Tool 목록
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ToolSpec t : tools) {
                ObjectNode tool = objectMapper.createObjectNode();
                tool.put("name", t.name());
                tool.put("description", t.description());

                ObjectNode inputSchema = objectMapper.createObjectNode();
                inputSchema.put("type", "object");
                inputSchema.set("properties", buildJsonSchemaProperties(t.parameterSchema()));
                tool.set("input_schema", inputSchema);

                toolsArray.add(tool);
            }
            body.set("tools", toolsArray);
        }

        return body;
    }

    @Override
    protected LLMResponse parseResponse(JsonNode json) {
        JsonNode content = json.path("content");
        if (!content.isArray()) {
            return new LLMResponse(LLMResponse.Type.TEXT, "응답을 파싱할 수 없습니다.", null, null);
        }

        for (JsonNode block : content) {
            String type = block.path("type").asText();

            if ("tool_use".equals(type)) {
                String toolName = block.path("name").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.convertValue(
                        block.path("input"), HashMap.class);
                return new LLMResponse(LLMResponse.Type.TOOL_CALL, null, toolName, args);
            }

            if ("text".equals(type)) {
                return new LLMResponse(
                        LLMResponse.Type.TEXT, block.path("text").asText(), null, null);
            }
        }

        return new LLMResponse(LLMResponse.Type.TEXT, "빈 응답이 반환되었습니다.", null, null);
    }
}

package com.hanyahunya.springai.router.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.springai.router.config.SpringAIRouterProperties;
import com.hanyahunya.springai.router.core.MethodInvoker;
import com.hanyahunya.springai.router.core.RegisteredTool;
import com.hanyahunya.springai.router.core.ToolRegistry;
import com.hanyahunya.springai.router.llm.LLMClient;
import com.hanyahunya.springai.router.llm.LLMClient.Message;
import com.hanyahunya.springai.router.session.SessionStore;
import com.hanyahunya.springai.router.usage.UsageTracker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * /ai-chat 엔드포인트 (Streamable HTTP).
 *
 * 하나의 HTTP 연결에서 서버가 청크 단위로 응답을 스트리밍합니다.
 * 클라이언트(AI Agent)는 스트림을 읽으며 맥락을 파악하고 대화를 이어갑니다.
 *
 * ── 요청 ──────────────────────────────────────────────────────────
 * POST /ai-chat
 * Content-Type: application/json
 * {
 *   "sessionId": "uuid",          // 선택. 없으면 신규 세션 생성
 *   "message":   "홍길동으로 회원가입하고 싶어"
 * }
 *
 * ── 응답 스트림 (NDJSON, 줄바꿈으로 구분) ─────────────────────────
 *
 * [1] 처리 시작 즉시:
 * {"status":"processing","sessionId":"uuid","message":"Analyzing your request and available tools."}
 *
 * [2-A] 파라미터 부족 시:
 * {"status":"needs_input","sessionId":"uuid",
 *  "message":"To complete the signup, I need your email address. Please reply with your email.",
 *  "hint":"Send your next message to POST /ai-chat with the same sessionId."}
 *
 * [2-B] 파라미터 충족 시:
 * {"status":"executing","sessionId":"uuid","message":"All required information collected. Calling createUser."}
 *
 * [3] 최종 결과:
 * {"status":"complete","sessionId":"uuid","message":"Successfully created user.",
 *  "data":{"id":1,"name":"홍길동","email":"hong@example.com"}}
 *
 * [오류]:
 * {"status":"error","sessionId":"uuid","message":"Reason for failure."}
 *
 * ── 설계 의도 ─────────────────────────────────────────────────────
 * - status 값과 message는 AI Agent가 자연어로 맥락을 파악할 수 있도록 작성됨
 * - needs_input 수신 시 AI Agent는 hint를 따라 같은 sessionId로 재요청
 * - complete/error 수신 시 세션 기록 즉시 삭제 (TTL 불필요)
 */
@RestController
public class AIChatController {

    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MethodInvoker methodInvoker;
    private final SessionStore sessionStore;
    private final UsageTracker usageTracker;
    private final SpringAIRouterProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AIChatController(LLMClient llmClient,
                            ToolRegistry toolRegistry,
                            MethodInvoker methodInvoker,
                            SessionStore sessionStore,
                            UsageTracker usageTracker,
                            SpringAIRouterProperties props) {
        this.llmClient     = llmClient;
        this.toolRegistry  = toolRegistry;
        this.methodInvoker = methodInvoker;
        this.sessionStore  = sessionStore;
        this.usageTracker  = usageTracker;
        this.props         = props;
    }

    @PostMapping(
            value    = "/ai-chat",
            produces = "application/x-ndjson"   // Newline-Delimited JSON (Streamable HTTP)
    )
    public ResponseEntity<StreamingResponseBody> chat(
            @RequestBody Map<String, String> request) {

        String userMessage = request.get("message");
        String sessionId   = Optional.ofNullable(request.get("sessionId"))
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString());

        // 기본 검증은 스트림 밖에서 처리
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        StreamingResponseBody stream = outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);

            try {
                // ── 한도 체크 ──────────────────────────────────────────
                if (!usageTracker.canProceed()) {
                    writeChunk(writer, Map.of(
                            "status",    "error",
                            "sessionId", sessionId,
                            "message",   "Request limit exceeded. Please try again later."
                    ));
                    return;
                }

                // ── 1. 처리 시작 즉시 청크 전송 ───────────────────────
                writeChunk(writer, Map.of(
                        "status",    "processing",
                        "sessionId", sessionId,
                        "message",   "Analyzing your request and available tools."
                ));

                // ── 세션 기록 로드 + 새 메시지 추가 ───────────────────
                List<Message> history = sessionStore.load(sessionId);

                int maxTurns = props.getSession().getMaxTurns();
                if (history.size() >= maxTurns * 2) {
                    sessionStore.delete(sessionId);
                    writeChunk(writer, Map.of(
                            "status",    "error",
                            "sessionId", sessionId,
                            "message",   "Conversation exceeded maximum turns. Please start a new session."
                    ));
                    return;
                }

                history.add(new Message("user", userMessage));

                // ── 2. LLM 호출 ───────────────────────────────────────
                List<LLMClient.ToolSpec> tools = toolRegistry.getToolSpecs();
                LLMClient.LLMResponse llmResponse = llmClient.chat(history, tools);
                usageTracker.record();

                // ── 3. 응답 분기 ──────────────────────────────────────
                if (llmResponse.type() == LLMClient.LLMResponse.Type.TEXT) {
                    // 파라미터 부족 → AI Agent가 이해할 수 있게 안내
                    history.add(new Message("assistant", llmResponse.message()));
                    sessionStore.save(sessionId, history);

                    writeChunk(writer, Map.of(
                            "status",    "needs_input",
                            "sessionId", sessionId,
                            "message",   llmResponse.message(),
                            "hint",      "Please reply with the requested information by sending " +
                                    "another POST /ai-chat request with the same sessionId."
                    ));

                } else {
                    // 파라미터 충족 → 메서드 호출
                    String toolName = llmResponse.toolName();

                    writeChunk(writer, Map.of(
                            "status",    "executing",
                            "sessionId", sessionId,
                            "message",   "All required information collected. Calling " + toolName + "."
                    ));

                    Optional<RegisteredTool> toolOpt = toolRegistry.findByName(toolName);
                    if (toolOpt.isEmpty()) {
                        sessionStore.delete(sessionId);  // 완료 → 세션 즉시 삭제
                        writeChunk(writer, Map.of(
                                "status",    "error",
                                "sessionId", sessionId,
                                "message",   "Requested tool not found: " + toolName
                        ));
                        return;
                    }

                    try {
                        Object result    = methodInvoker.invoke(toolOpt.get(), llmResponse.toolArgs());
                        String resultStr = objectMapper.writeValueAsString(result);

                        sessionStore.delete(sessionId);  // 완료 → 세션 즉시 삭제

                        writeChunk(writer, Map.of(
                                "status",    "complete",
                                "sessionId", sessionId,
                                "message",   "Successfully executed " + toolName + ".",
                                "data",      objectMapper.readValue(resultStr, Object.class)
                        ));

                    } catch (Exception e) {
                        sessionStore.delete(sessionId);  // 오류 → 세션 즉시 삭제
                        writeChunk(writer, Map.of(
                                "status",    "error",
                                "sessionId", sessionId,
                                "message",   "Execution failed: " + e.getMessage()
                        ));
                    }
                }

            } catch (Exception e) {
                sessionStore.delete(sessionId);
                try {
                    writeChunk(writer, Map.of(
                            "status",    "error",
                            "sessionId", sessionId,
                            "message",   "Unexpected error: " + e.getMessage()
                    ));
                } catch (Exception ignored) {}
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(stream);
    }

    /**
     * JSON 객체를 NDJSON 한 줄로 직렬화해서 flush.
     * 각 청크는 줄바꿈으로 구분되어 클라이언트가 즉시 파싱 가능합니다.
     */
    private void writeChunk(Writer writer, Map<String, Object> data) throws Exception {
        writer.write(objectMapper.writeValueAsString(data));
        writer.write("\n");
        writer.flush();
    }
}
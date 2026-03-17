package com.hanyahunya.springai.router.config;

import com.hanyahunya.springai.router.core.AIToolScanner;
import com.hanyahunya.springai.router.core.MethodInvoker;
import com.hanyahunya.springai.router.core.ToolRegistry;
import com.hanyahunya.springai.router.llm.ClaudeClient;
import com.hanyahunya.springai.router.llm.GeminiClient;
import com.hanyahunya.springai.router.llm.LLMClient;
import com.hanyahunya.springai.router.llm.OpenAIClient;
import com.hanyahunya.springai.router.session.InMemorySessionStore;
import com.hanyahunya.springai.router.session.SessionStore;
import com.hanyahunya.springai.router.usage.InMemoryUsageTracker;
import com.hanyahunya.springai.router.usage.UsageTracker;
import com.hanyahunya.springai.router.web.AIChatController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;

@AutoConfiguration
@EnableConfigurationProperties(SpringAIRouterProperties.class)
@ConditionalOnProperty(prefix = "spring-ai-router", name = "enabled", matchIfMissing = true)
public class SpringAIRouterAutoConfiguration {

    // ── LLM 클라이언트 ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(LLMClient.class)
    @ConditionalOnProperty(
            prefix = "spring-ai-router", name = "provider",
            havingValue = "claude", matchIfMissing = true)
    public LLMClient claudeClient(SpringAIRouterProperties props) {
        validateApiKey(props, "Claude");
        return new ClaudeClient(props);
    }

    @Bean
    @ConditionalOnMissingBean(LLMClient.class)
    @ConditionalOnProperty(prefix = "spring-ai-router", name = "provider", havingValue = "openai")
    public LLMClient openAIClient(SpringAIRouterProperties props) {
        validateApiKey(props, "OpenAI");
        return new OpenAIClient(props);
    }

    @Bean
    @ConditionalOnMissingBean(LLMClient.class)
    @ConditionalOnProperty(prefix = "spring-ai-router", name = "provider", havingValue = "gemini")
    public LLMClient geminiClient(SpringAIRouterProperties props) {
        validateApiKey(props, "Gemini");
        return new GeminiClient(props);
    }

    // ── 세션 저장소 ────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore sessionStore(SpringAIRouterProperties props) {
        return new InMemorySessionStore(props.getSession().getTtlMinutes());
    }

    // ── 사용량 추적기 ───────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(UsageTracker.class)
    public UsageTracker usageTracker(SpringAIRouterProperties props) {
        return new InMemoryUsageTracker(props);
    }

    // ── 코어 ───────────────────────────────────────────────────────

    @Bean
    public AIToolScanner aiToolScanner() {
        return new AIToolScanner();
    }

    @Bean
    public ToolRegistry toolRegistry(AIToolScanner scanner) {
        return new ToolRegistry(scanner);
    }

    @Bean
    public MethodInvoker methodInvoker() {
        return new MethodInvoker();
    }

    // ── 웹 ─────────────────────────────────────────────────────────

    @Bean
    public AIChatController aiChatController(
            LLMClient llmClient,
            ToolRegistry toolRegistry,
            MethodInvoker methodInvoker,
            SessionStore sessionStore,
            UsageTracker usageTracker,
            SpringAIRouterProperties props) {
        return new AIChatController(
                llmClient, toolRegistry, methodInvoker,
                sessionStore, usageTracker, props);
    }

    /**
     * Gemini explicit 캐시 초기화.
     * 모든 빈이 완전히 준비된 후(ContextRefreshedEvent) 실행되어
     * ToolRegistry의 Tool 목록이 이미 채워진 상태에서 캐시를 생성합니다.
     *
     * Gemini 이외의 공급자는 아무 동작도 하지 않습니다.
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> geminiCacheInitializer(
            LLMClient llmClient,
            ToolRegistry toolRegistry) {
        return event -> {
            if (llmClient instanceof GeminiClient geminiClient) {
                geminiClient.initializeCache(toolRegistry.getToolSpecs());
            }
        };
    }

    // ── 유틸 ───────────────────────────────────────────────────────

    private void validateApiKey(SpringAIRouterProperties props, String provider) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "[spring-ai-router] " + provider + " API 키가 없습니다. " +
                            "application.yml에 spring-ai-router.api-key를 설정해주세요."
            );
        }
    }
}
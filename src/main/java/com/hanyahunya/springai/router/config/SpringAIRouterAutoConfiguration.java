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
import org.springframework.context.annotation.Bean;


/**
 * Spring AI Router 자동 설정.
 *
 * 의존성 추가만 해도 동작합니다.
 * application.yml의 spring-ai-router.* 로 동작을 제어합니다.
 *
 * Bean 우선순위:
 *  - LLMClient    : 사용자 Bean > provider 설정 기반 자동 생성
 *  - SessionStore : 사용자 Bean > InMemorySessionStore (기본값)
 *  - UsageTracker : 사용자 Bean > InMemoryUsageTracker (기본값)
 */
@AutoConfiguration
@EnableConfigurationProperties(SpringAIRouterProperties.class)
@ConditionalOnProperty(prefix = "spring-ai-router", name = "enabled", matchIfMissing = true)
public class SpringAIRouterAutoConfiguration {

    // ── LLM 클라이언트 ─────────────────────────────────────────────
    // provider 값에 따라 셋 중 하나만 등록됨
    // 사용자가 LLMClient Bean을 직접 등록하면 아래 셋 모두 무시됨

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


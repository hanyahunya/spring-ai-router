package com.hanyahunya.springai.router.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml / .properties 설정 바인딩
 *
 * 최소 설정:
 * <pre>
 * spring-ai-router:
 *   provider: claude
 *   api-key: ${API_KEY}
 * </pre>
 *
 * 전체 설정:
 * <pre>
 * spring-ai-router:
 *   provider: claude       # claude | openai | gemini (기본값: claude)
 *   api-key: ${API_KEY}
 *   model: claude-haiku-4-5-20251001
 *   session:
 *     ttl-minutes: 30
 *     max-turns: 20
 *   limits:
 *     daily-max: 1000
 *     monthly-max: 30000
 * </pre>
 *
 * ── 프롬프트 캐싱 (자동 적용) ──────────────────────────────────────
 * Claude : cache_control TTL 1h. 자동 적용.
 * OpenAI : Prefer-Caching: retain=86400 (24h). 자동 적용.
 * Gemini : explicit 캐싱 TTL 1h. 앱 시작 시 자동 캐시 생성.
 *          별도 설정 불필요.
 */
@ConfigurationProperties(prefix = "spring-ai-router")
public class SpringAIRouterProperties {

    private boolean enabled  = true;
    private String  provider = "claude";
    private String  apiKey;
    private String  model;
    private Session session  = new Session();
    private Limits  limits   = new Limits();

    public static class Session {
        private int ttlMinutes = 30;
        private int maxTurns   = 20;

        public int  getTtlMinutes()      { return ttlMinutes; }
        public void setTtlMinutes(int v) { this.ttlMinutes = v; }
        public int  getMaxTurns()        { return maxTurns; }
        public void setMaxTurns(int v)   { this.maxTurns = v; }
    }

    public static class Limits {
        private int dailyMax   = -1;
        private int monthlyMax = -1;

        public int  getDailyMax()         { return dailyMax; }
        public void setDailyMax(int v)    { this.dailyMax = v; }
        public int  getMonthlyMax()       { return monthlyMax; }
        public void setMonthlyMax(int v)  { this.monthlyMax = v; }
    }

    public String getEffectiveModel() {
        if (model != null && !model.isBlank()) return model;
        return switch (provider.toLowerCase()) {
            case "openai"  -> "gpt-4o-mini";
            case "gemini"  -> "gemini-2.5-flash";
            default        -> "claude-haiku-4-5-20251001";
        };
    }

    public boolean isEnabled()           { return enabled; }
    public void    setEnabled(boolean v) { this.enabled = v; }
    public String  getProvider()         { return provider; }
    public void    setProvider(String v) { this.provider = v; }
    public String  getApiKey()           { return apiKey; }
    public void    setApiKey(String v)   { this.apiKey = v; }
    public String  getModel()            { return model; }
    public void    setModel(String v)    { this.model = v; }
    public Session getSession()          { return session; }
    public void    setSession(Session v) { this.session = v; }
    public Limits  getLimits()           { return limits; }
    public void    setLimits(Limits v)   { this.limits = v; }
}
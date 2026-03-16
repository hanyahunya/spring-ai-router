package com.hanyahunya.springai.router.core;

import com.hanyahunya.springai.router.llm.LLMClient;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final AIToolScanner scanner;
    private List<RegisteredTool> registeredTools;
    private boolean initialized = false;

    public ToolRegistry(AIToolScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * 모든 빈이 완전히 초기화된 후 실행됨.
     * @PostConstruct 대신 사용 → 순환 참조 방지
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!initialized) {
            this.registeredTools = scanner.scanTools();
            this.initialized = true;
        }
    }

    public List<LLMClient.ToolSpec> getToolSpecs() {
        return registeredTools.stream()
                .map(t -> new LLMClient.ToolSpec(
                        t.getName(),
                        t.getDescription(),
                        t.getParameterSchema()
                ))
                .collect(Collectors.toList());
    }

    public Optional<RegisteredTool> findByName(String name) {
        return registeredTools.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst();
    }

    public List<RegisteredTool> getAllTools() {
        return registeredTools;
    }
}
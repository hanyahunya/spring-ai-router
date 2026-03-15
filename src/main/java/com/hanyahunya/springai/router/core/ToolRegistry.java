package com.hanyahunya.springai.router.core;

import com.hanyahunya.springai.router.llm.LLMClient;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 스캔된 Tool 목록을 관리하고 LLM에 전달할 형태로 변환합니다.
 */
public class ToolRegistry {

    private final AIToolScanner scanner;
    private List<RegisteredTool> tools;

    public ToolRegistry(AIToolScanner scanner) {
        this.scanner = scanner;
    }

    @PostConstruct
    public void init() {
        this.tools = scanner.scanTools();
    }

    public List<LLMClient.ToolSpec> getToolSpecs() {
        return tools.stream()
                .map(t -> new LLMClient.ToolSpec(
                        t.getName(),
                        t.getDescription(),
                        t.getParameterSchema()))
                .collect(Collectors.toList());
    }

    public Optional<RegisteredTool> findByName(String name) {
        return tools.stream().filter(t -> t.getName().equals(name)).findFirst();
    }

    public int getToolCount() { return tools.size(); }
}

package com.hanyahunya.springai.router.core;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * @AIController 스캔 결과로 생성된 Tool 정보.
 * LLM에 전달할 스펙과 실제 메서드 호출에 필요한 정보를 모두 포함합니다.
 */
public class RegisteredTool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameterSchema;
    private final Method method;
    private final Object beanInstance;

    public RegisteredTool(String name, String description,
                          Map<String, Object> parameterSchema,
                          Method method, Object beanInstance) {
        this.name            = name;
        this.description     = description;
        this.parameterSchema = parameterSchema;
        this.method          = method;
        this.beanInstance    = beanInstance;
    }

    public String getName()                        { return name; }
    public String getDescription()                 { return description; }
    public Map<String, Object> getParameterSchema(){ return parameterSchema; }
    public Method getMethod()                      { return method; }
    public Object getBeanInstance()                { return beanInstance; }
}

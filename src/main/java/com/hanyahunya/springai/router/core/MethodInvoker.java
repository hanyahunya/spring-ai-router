package com.hanyahunya.springai.router.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * LLM이 결정한 Tool 이름과 파라미터로 실제 Bean 메서드를 리플렉션으로 호출합니다.
 */
public class MethodInvoker {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Object invoke(RegisteredTool tool, Map<String, Object> args) throws Exception {
        Method method   = tool.getMethod();
        method.setAccessible(true);

        Parameter[] params  = method.getParameters();
        Object[]    invokeArgs = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            Class<?> type   = param.getType();

            if (param.isAnnotationPresent(RequestBody.class)) {
                // @RequestBody → Map 전체를 해당 타입으로 변환
                invokeArgs[i] = objectMapper.convertValue(args, type);

            } else if (param.isAnnotationPresent(PathVariable.class)) {
                String name = param.getAnnotation(PathVariable.class).value();
                if (name.isBlank()) name = param.getName();
                invokeArgs[i] = convertValue(args.get(name), type);

            } else if (param.isAnnotationPresent(RequestParam.class)) {
                String name = param.getAnnotation(RequestParam.class).value();
                if (name.isBlank()) name = param.getName();
                invokeArgs[i] = convertValue(args.get(name), type);

            } else {
                invokeArgs[i] = convertValue(args.get(param.getName()), type);
            }
        }

        return method.invoke(tool.getBeanInstance(), invokeArgs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertValue(Object value, Class<?> target) {
        if (value == null) return null;
        if (target == String.class)                           return String.valueOf(value);
        if (target == Long.class    || target == long.class)  return Long.parseLong(String.valueOf(value));
        if (target == Integer.class || target == int.class)   return Integer.parseInt(String.valueOf(value));
        if (target == Boolean.class || target == boolean.class) return Boolean.parseBoolean(String.valueOf(value));
        if (target == Double.class  || target == double.class) return Double.parseDouble(String.valueOf(value));
        if (target.isEnum()) return Enum.valueOf((Class<Enum>) target, String.valueOf(value).toUpperCase());
        return objectMapper.convertValue(value, target);
    }
}

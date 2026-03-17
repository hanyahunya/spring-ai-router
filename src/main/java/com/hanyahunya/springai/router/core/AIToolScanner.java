package com.hanyahunya.springai.router.core;

import com.hanyahunya.springai.router.annotation.AIController;
import com.hanyahunya.springai.router.annotation.AIHidden;
import com.hanyahunya.springai.router.annotation.AIParam;
import com.hanyahunya.springai.router.annotation.AITool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * @AIController 빈을 스캔해 Tool 목록을 생성합니다.
 *
 * ── 스캔 규칙 ──────────────────────────────────────────────────
 * 1. @AIController 붙은 클래스의 모든 @*Mapping 메서드를 자동 등록
 * 2. @AIHidden 붙은 메서드는 제외
 * 3. @AITool description 있으면 우선 사용, 없으면 "HTTP메서드 /경로" 자동 생성
 * 4. @AIParam 있으면 그 설명 사용, 없으면 필드명/파라미터명으로 유추
 *
 * ── Tool 순서 보장 (캐시 히트를 위해 중요) ──────────────────────
 * JVM의 getDeclaredMethods()는 호출마다 순서가 달라질 수 있습니다.
 * Tool 순서가 바뀌면 LLM에 전달되는 prefix가 달라져 캐시 미스 발생.
 * → 메서드명 + 필드명을 알파벳순으로 정렬하여 항상 동일한 순서 보장.
 * → 사용자가 메서드명/필드명을 변경하지 않는 한 캐시가 유지됩니다.
 */
public class AIToolScanner implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<RegisteredTool> scanTools() {
        List<RegisteredTool> tools = new ArrayList<>();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object   bean      = applicationContext.getBean(beanName);
            Class<?> beanClass = getTargetClass(bean);

            if (!beanClass.isAnnotationPresent(AIController.class)) continue;

            // ── 메서드 알파벳순 정렬 ────────────────────────────────
            // 동일한 순서 보장 → LLM prefix 일치 → 캐시 히트 유지
            Method[] methods = beanClass.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));

            for (Method method : methods) {
                if (method.isAnnotationPresent(AIHidden.class)) continue;

                String[] httpInfo = extractHttpInfo(method);
                if (httpInfo == null) continue;

                String              description = resolveDescription(method, httpInfo);
                Map<String, Object> paramSchema = buildParameterSchema(method);

                tools.add(new RegisteredTool(
                        method.getName(),
                        description,
                        paramSchema,
                        method,
                        bean
                ));
            }
        }

        // ── 최종 정렬: 여러 @AIController 클래스가 있을 때도 순서 고정
        tools.sort(Comparator.comparing(RegisteredTool::getName));

        return tools;
    }

    // ── description 결정 ───────────────────────────────────────────

    private String resolveDescription(Method method, String[] httpInfo) {
        AITool aiTool = method.getAnnotation(AITool.class);
        if (aiTool != null && !aiTool.description().isBlank()) {
            return aiTool.description();
        }
        return httpInfo[0] + " " + httpInfo[1];
    }

    // ── HTTP 메서드 + 경로 추출 ────────────────────────────────────

    private String[] extractHttpInfo(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return new String[]{"GET",    firstPath(method.getAnnotation(GetMapping.class).value())};
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return new String[]{"POST",   firstPath(method.getAnnotation(PostMapping.class).value())};
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return new String[]{"PUT",    firstPath(method.getAnnotation(PutMapping.class).value())};
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return new String[]{"PATCH",  firstPath(method.getAnnotation(PatchMapping.class).value())};
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return new String[]{"DELETE", firstPath(method.getAnnotation(DeleteMapping.class).value())};
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping rm = method.getAnnotation(RequestMapping.class);
            String httpMethod = rm.method().length > 0 ? rm.method()[0].name() : "REQUEST";
            return new String[]{httpMethod, firstPath(rm.value())};
        }
        return null;
    }

    private String firstPath(String[] paths) {
        return (paths != null && paths.length > 0) ? paths[0] : "/";
    }

    // ── 파라미터 스키마 구성 ───────────────────────────────────────

    private Map<String, Object> buildParameterSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();

        for (Parameter param : method.getParameters()) {
            Class<?> type = param.getType();

            if (param.isAnnotationPresent(RequestBody.class)) {
                properties.putAll(extractFieldSchema(type));
                if (param.isAnnotationPresent(AIParam.class)) {
                    properties.put("_description",
                            Map.of("description", param.getAnnotation(AIParam.class).value()));
                }
                continue;
            }

            String              paramName = extractParamName(param);
            Map<String, Object> schema    = new LinkedHashMap<>(toJsonSchemaType(type));
            if (param.isAnnotationPresent(AIParam.class)) {
                schema.put("description", param.getAnnotation(AIParam.class).value());
            }
            properties.put(paramName, schema);
        }

        return properties;
    }

    private Map<String, Object> extractFieldSchema(Class<?> clazz) {
        Map<String, Object> properties = new LinkedHashMap<>();

        // 필드도 알파벳순 정렬 → schema 내부 순서 고정 → 캐시 히트 유지
        List<Field> fields = getAllFields(clazz);
        fields.sort(Comparator.comparing(Field::getName));

        for (Field field : fields) {
            String   fieldName = field.getName();
            Class<?> fieldType = field.getType();

            Map<String, Object> schema = new LinkedHashMap<>();
            if (isSimpleType(fieldType)) {
                schema.putAll(toJsonSchemaType(fieldType));
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                schema.put("type", "array");
            } else if (!fieldType.isPrimitive() && !fieldType.getName().startsWith("java")) {
                schema.put("type",       "object");
                schema.put("properties", extractFieldSchema(fieldType));
            } else {
                schema.putAll(toJsonSchemaType(fieldType));
            }

            if (field.isAnnotationPresent(AIParam.class)) {
                schema.put("description", field.getAnnotation(AIParam.class).value());
            }
            properties.put(fieldName, schema);
        }

        return properties;
    }

    // ── 유틸 ──────────────────────────────────────────────────────

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields  = new ArrayList<>();
        Class<?>    current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type.isEnum();
    }

    private Map<String, Object> toJsonSchemaType(Class<?> type) {
        if (type == String.class)                                   return Map.of("type", "string");
        if (type == Boolean.class || type == boolean.class)         return Map.of("type", "boolean");
        if (type == Long.class    || type == long.class
                || type == Integer.class || type == int.class)             return Map.of("type", "integer");
        if (type == Double.class  || type == double.class
                || type == Float.class   || type == float.class)           return Map.of("type", "number");
        if (type.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object c : type.getEnumConstants()) values.add(c.toString());
            return Map.of("type", "string", "enum", values);
        }
        return Map.of("type", "string");
    }

    private String extractParamName(Parameter param) {
        if (param.isAnnotationPresent(PathVariable.class)) {
            String v = param.getAnnotation(PathVariable.class).value();
            return v.isBlank() ? param.getName() : v;
        }
        if (param.isAnnotationPresent(RequestParam.class)) {
            String v = param.getAnnotation(RequestParam.class).value();
            return v.isBlank() ? param.getName() : v;
        }
        return param.getName();
    }

    private Class<?> getTargetClass(Object bean) {
        Class<?> clazz = bean.getClass();
        return clazz.getName().contains("$$") ? clazz.getSuperclass() : clazz;
    }
}
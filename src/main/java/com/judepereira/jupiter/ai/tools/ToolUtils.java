package com.judepereira.jupiter.ai.tools;

import com.judepereira.jupiter.dtos.ToolCallTrace;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.ProxyFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public class ToolUtils {
    public static Object @NonNull [] wrap(List<Object> tools, Consumer<ToolCallTrace> traceConsumer) {
        return tools.stream().map(tool -> {
            ProxyFactory pf = new ProxyFactory(tool);
            pf.setProxyTargetClass(true);
            pf.addAdvice((MethodInterceptor) invocation -> {
                var method = invocation.getMethod();
                boolean isTool = method.getAnnotation(Tool.class) != null;
                if (!isTool) {
                    try {
                        Method targetMethod = tool.getClass().getMethod(method.getName(), method.getParameterTypes());
                        isTool = targetMethod.getAnnotation(Tool.class) != null;
                    } catch (NoSuchMethodException ignored) {
                    }
                }

                if (!isTool) {
                    return invocation.proceed();
                }

                Instant startedAt = Instant.now();
                String argsPayload = "";
                Object[] argsArr = invocation.getArguments();

                if (argsArr.length > 0) {
                    StringBuilder ap = new StringBuilder();
                    for (int i = 0; i < argsArr.length; i++) {
                        Object a = argsArr[i];
                        ap.append(invocation.getMethod().getParameters()[i].getName()).append(": ");
                        ap.append(a == null ? "null" : a.toString());
                        if (i < argsArr.length - 1) ap.append(", ");
                    }
                    argsPayload = ap.toString();
                }

                String toolName = method.getName();

                try {
                    Object res = invocation.proceed();
                    String resultPayload = res == null ? "null" : res.toString();
                    long duration = Duration.between(startedAt, Instant.now()).toMillis();
                    if (traceConsumer != null) {
                        var trace = new ToolCallTrace(toolName, argsPayload, resultPayload, null, startedAt, duration);
                        traceConsumer.accept(trace);
                    }
                    return res;
                } catch (Throwable ex) {
                    String errPayload = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    long duration = Duration.between(startedAt, Instant.now()).toMillis();
                    if (traceConsumer != null) {
                        var trace = new ToolCallTrace(toolName, argsPayload, null, errPayload, startedAt, duration);
                        traceConsumer.accept(trace);
                    }
                    throw ex;
                }
            });
            return pf.getProxy();
        }).toArray(Object[]::new);
    }
}

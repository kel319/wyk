package com.wyk.redis.util;


import com.wyk.redis.exception.CustomizeException;
import com.wyk.redis.cache.Status;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.util.Optional;

public class SpELUtil {
    private static final SpelExpressionParser parser = new SpelExpressionParser();

    public String parseSpEL(String value,String defaultVal, ProceedingJoinPoint joinPoint) {
        if (!StringUtils.hasText(value)) {
            throw new CustomizeException("SpEL表达式为空", Status.INTERNAL_SERVER_ERROR.getCode());
        }
        EvaluationContext context = getContext(joinPoint,defaultVal);
        try {
            return parser.parseExpression(value).getValue(context, String.class);
        } catch (Exception e) {
            throw new CustomizeException("SpEL表达式解析失败", Status.INTERNAL_SERVER_ERROR.getCode());
        }
    }
    public Object parseBloomSpEL(String value,String defaultVal, ProceedingJoinPoint joinPoint) {
        if (!StringUtils.hasText(value)) {
            throw new CustomizeException("SpEL表达式为空", Status.INTERNAL_SERVER_ERROR.getCode());
        }
        EvaluationContext context = getContext(joinPoint,defaultVal);
        try {
            return parser.parseExpression(value).getValue(context);
        } catch (Exception e) {
            throw new CustomizeException("SpEL表达式解析失败", Status.INTERNAL_SERVER_ERROR.getCode());
        }
    }
    private EvaluationContext getContext(ProceedingJoinPoint joinPoint, String defaultVal) {
        String[] parameterNames = Optional.of(joinPoint)
                .map(ProceedingJoinPoint::getSignature)
                .filter(MethodSignature.class::isInstance)
                .map(MethodSignature.class::cast)
                .map(MethodSignature::getParameterNames)
                .orElseThrow(() -> new CustomizeException("获取方法名失败", Status.INTERNAL_SERVER_ERROR.getCode()));
        Object[] parameterArgs = joinPoint.getArgs();
        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i],
                    parameterArgs[i] != null ? parameterArgs[i] : String.format("%s:%s",parameterNames[i],defaultVal));
        }
        return context;
    }

}
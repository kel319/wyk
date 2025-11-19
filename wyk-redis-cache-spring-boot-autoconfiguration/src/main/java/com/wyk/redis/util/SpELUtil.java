package com.wyk.redis.util;


import com.wyk.redis.exception.CustomizeException;
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
            throw CustomizeException.internalServerError("SpEL表达式不能为空");
        }
        EvaluationContext context = getContext(joinPoint,defaultVal);
        try {
            return parser.parseExpression(value).getValue(context, String.class);
        } catch (Exception e) {
            throw CustomizeException.internalServerError("SpEL表达式解析失败");
        }
    }
    public Object parseBloomSpEL(String value,String defaultVal, ProceedingJoinPoint joinPoint) {
        if (!StringUtils.hasText(value)) {
            throw CustomizeException.internalServerError("布隆SpEL表达式不能为空");
        }
        EvaluationContext context = getContext(joinPoint,defaultVal);
        try {
            return parser.parseExpression(value).getValue(context);
        } catch (Exception e) {
            throw CustomizeException.internalServerError("SpEL表达式解析失败");
        }
    }
    private EvaluationContext getContext(ProceedingJoinPoint joinPoint, String defaultVal) {
        String[] parameterNames = Optional.of(joinPoint)
                .map(ProceedingJoinPoint::getSignature)
                .filter(MethodSignature.class::isInstance)
                .map(MethodSignature.class::cast)
                .map(MethodSignature::getParameterNames)
                .orElseThrow(() -> CustomizeException.internalServerError("获取方法名失败"));
        Object[] parameterArgs = joinPoint.getArgs();
        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i],
                    parameterArgs[i] != null ? parameterArgs[i] : String.format("%s:%s",parameterNames[i],defaultVal));
        }
        return context;
    }

}
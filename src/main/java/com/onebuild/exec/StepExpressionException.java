package com.onebuild.exec;

/**
 * 步骤表达式解析异常。
 */
public class StepExpressionException extends RuntimeException {

    public StepExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}

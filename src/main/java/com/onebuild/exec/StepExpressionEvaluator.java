package com.onebuild.exec;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * 步骤命令 / 环境变量中的 SpEL 模板渲染。
 *
 * <p>使用 Spring EL 标准模板前缀 {@code #{...}}，避免与 shell 的 {@code ${VAR}} 冲突。
 * 通过 {@link SimpleEvaluationContext} 只开放只读属性访问，禁止类型引用、构造器与 Bean 引用。</p>
 */
@Component
public class StepExpressionEvaluator {

    private static final TemplateParserContext TEMPLATE = new TemplateParserContext();

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 判断文本是否包含 SpEL 模板表达式。
     *
     * @param text 文本
     * @return 是否包含 {@code #{}
     */
    public boolean containsExpression(String text) {
        return text != null && text.contains("#{");
    }

    /**
     * 渲染模板。
     *
     * @param template 模板文本
     * @param root 根对象
     * @return 渲染结果
     * @throws StepExpressionException 表达式非法或取值失败
     */
    public String render(String template, StepExpressionRoot root) {
        if (!containsExpression(template)) {
            return template;
        }
        try {
            SimpleEvaluationContext context = SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())
                    .build();
            Object value = parser.parseExpression(template, TEMPLATE)
                    .getValue(context, root);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            throw new StepExpressionException("表达式解析失败: " + brief(template)
                    + " -> " + rootMessage(e), e);
        }
    }

    /**
     * 批量渲染（命令 + 非 OB_* 环境变量值）。
     *
     * <p>OB_* 由系统生成，不参与渲染，避免步骤输出中恰好包含 {@code #{}} 时被二次求值。</p>
     *
     * @param command 命令
     * @param env 环境变量（原地修改）
     * @param root 根对象
     * @return 渲染后的命令
     */
    public String renderCommand(String command, java.util.Map<String, String> env,
                                StepExpressionRoot root) {
        String rendered = render(command, root);
        if (env != null) {
            for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith("OB_")) {
                    continue;
                }
                entry.setValue(render(entry.getValue(), root));
            }
        }
        return rendered;
    }

    private String brief(String text) {
        String single = text.replace('\n', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) + "…" : single;
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}

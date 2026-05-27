package logic;

import org.logicng.formulas.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 比较两种规范化方法：
 * 1. 字母排序（当前方法）
 * 2. LogicNG 规范化
 */
public class NormalizationMethodComparison {
    
    private static final FormulaFactory ff = new FormulaFactory();
    
    public static void main(String[] args) {
        System.out.println("=" .repeat(70));
        System.out.println("规范化方法性能比较：字母排序 vs LogicNG");
        System.out.println("=" .repeat(70));
        
        // 生成测试表达式
        List<LogicalExpression> testExpressions = generateTestExpressions(10000);
        
        System.out.println("\n测试数据: " + testExpressions.size() + " 个表达式");
        
        // 预热
        System.out.println("\n预热中...");
        for (int i = 0; i < 1000; i++) {
            extractPatternAlphaSort(testExpressions.get(i % testExpressions.size()));
            extractPatternLogicNG(testExpressions.get(i % testExpressions.size()));
        }
        
        // 测试1: 字母排序方法
        System.out.println("\n[测试1] 字母排序规范化");
        System.out.println("-".repeat(50));
        
        Set<String> alphaSortPatterns = new HashSet<>();
        long startAlpha = System.nanoTime();
        for (LogicalExpression expr : testExpressions) {
            String pattern = extractPatternAlphaSort(expr);
            alphaSortPatterns.add(pattern);
        }
        long alphaTime = System.nanoTime() - startAlpha;
        
        System.out.println("  耗时: " + String.format("%.2f ms", alphaTime / 1_000_000.0));
        System.out.println("  唯一模式数: " + alphaSortPatterns.size());
        
        // 测试2: LogicNG 规范化方法
        System.out.println("\n[测试2] LogicNG 规范化");
        System.out.println("-".repeat(50));
        
        Set<String> logicNGPatterns = new HashSet<>();
        long startLogicNG = System.nanoTime();
        for (LogicalExpression expr : testExpressions) {
            String pattern = extractPatternLogicNG(expr);
            logicNGPatterns.add(pattern);
        }
        long logicNGTime = System.nanoTime() - startLogicNG;
        
        System.out.println("  耗时: " + String.format("%.2f ms", logicNGTime / 1_000_000.0));
        System.out.println("  唯一模式数: " + logicNGPatterns.size());
        
        // 比较
        System.out.println("\n[比较结果]");
        System.out.println("-".repeat(50));
        System.out.println("  字母排序: " + String.format("%.2f ms", alphaTime / 1_000_000.0));
        System.out.println("  LogicNG:  " + String.format("%.2f ms", logicNGTime / 1_000_000.0));
        System.out.println("  速度比:   " + String.format("%.2fx", (double) logicNGTime / alphaTime));
        System.out.println();
        System.out.println("  模式数相同: " + (alphaSortPatterns.size() == logicNGPatterns.size() ? "✅" : "❌"));
        
        // 检查模式是否一致
        System.out.println("\n[模式一致性检查]");
        System.out.println("-".repeat(50));
        
        for (int i = 0; i < Math.min(10, testExpressions.size()); i++) {
            LogicalExpression expr = testExpressions.get(i);
            String alphaPattern = extractPatternAlphaSort(expr);
            String logicNGPattern = extractPatternLogicNG(expr);
            
            boolean match = alphaPattern.equals(logicNGPattern);
            
            System.out.println("  表达式 " + (i+1) + ": " + (match ? "✅" : "❌"));
            if (!match) {
                System.out.println("    Alpha:   " + alphaPattern);
                System.out.println("    LogicNG: " + logicNGPattern);
            }
        }
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("结论");
        System.out.println("=".repeat(70));
        
        if (alphaTime < logicNGTime) {
            System.out.println(String.format("""
                
                字母排序更快 (%.2fx)
                
                原因：
                1. LogicNG 规范化需要：
                   - 将 LogicalExpression 转换为 LogicNG Formula
                   - LogicNG 内部进行规范化
                   - 从 Formula 提取字符串表示
                   
                2. 字母排序只需要：
                   - 收集符号名称
                   - Collections.sort()
                   - 字符串拼接
                   
                3. LogicNG 的优势在于：
                   - 可以检测更多等价性（如 De Morgan 定律）
                   - 但对于简单的结构匹配，这是过度设计
                """, (double) logicNGTime / alphaTime));
        } else {
            System.out.println(String.format("""
                
                LogicNG 规范化更快 (%.2fx)
                
                可以考虑切换到 LogicNG 规范化方法。
                """, (double) alphaTime / logicNGTime));
        }
    }
    
    /**
     * 方法1: 字母排序规范化（当前实现）
     */
    static String extractPatternAlphaSort(LogicalExpression expr) {
        // 收集符号并排序
        Set<String> symbols = expr.getFreeSymbols();
        List<String> sortedSymbols = new ArrayList<>(symbols);
        Collections.sort(sortedSymbols);
        
        // 创建映射
        Map<String, String> symbolToPlaceholder = new HashMap<>();
        int idx = 0;
        for (String symbol : sortedSymbols) {
            symbolToPlaceholder.put(symbol, "$" + idx++);
        }
        
        // 生成模式字符串
        return toPatternStringAlphaSort(expr, symbolToPlaceholder);
    }
    
    static String toPatternStringAlphaSort(LogicalExpression expr, Map<String, String> mapping) {
        if (expr instanceof SymbolExpr sym) {
            return mapping.getOrDefault(sym.getName(), sym.getName());
        } else if (expr instanceof NotExpr not) {
            return "Not(" + toPatternStringAlphaSort(not.getArg(), mapping) + ")";
        } else if (expr instanceof AndExpr and) {
            List<String> args = and.getArgs().stream()
                .map(arg -> toPatternStringAlphaSort(arg, mapping))
                .sorted()
                .collect(Collectors.toList());
            return "And(" + String.join(",", args) + ")";
        } else if (expr instanceof OrExpr or) {
            List<String> args = or.getArgs().stream()
                .map(arg -> toPatternStringAlphaSort(arg, mapping))
                .sorted()
                .collect(Collectors.toList());
            return "Or(" + String.join(",", args) + ")";
        }
        return expr.toString();
    }
    
    /**
     * 方法2: LogicNG 规范化
     */
    static String extractPatternLogicNG(LogicalExpression expr) {
        // 转换为 LogicNG Formula（自动规范化）
        Formula formula = toLogicNGFormula(expr);
        
        // 从规范化的 Formula 提取模式
        // LogicNG 的 toString() 已经是规范化的
        String formulaStr = formula.toString();
        
        // 将变量名替换为占位符
        List<String> varNames = formula.variables().stream()
            .map(Variable::name)
            .sorted()
            .collect(Collectors.toList());
        
        Map<String, String> mapping = new HashMap<>();
        for (int i = 0; i < varNames.size(); i++) {
            mapping.put(varNames.get(i), "$" + i);
        }
        
        // 替换变量名（需要小心处理，避免部分替换）
        String pattern = formulaStr;
        // 按名称长度降序排列，避免短名称先替换导致问题
        List<String> sortedByLength = new ArrayList<>(varNames);
        sortedByLength.sort((a, b) -> b.length() - a.length());
        
        for (String varName : sortedByLength) {
            pattern = pattern.replace(varName, mapping.get(varName));
        }
        
        return pattern;
    }
    
    static Formula toLogicNGFormula(LogicalExpression expr) {
        if (expr instanceof SymbolExpr sym) {
            return ff.variable(sym.getName());
        } else if (expr instanceof NotExpr not) {
            return ff.not(toLogicNGFormula(not.getArg()));
        } else if (expr instanceof AndExpr and) {
            Formula[] args = and.getArgs().stream()
                .map(NormalizationMethodComparison::toLogicNGFormula)
                .toArray(Formula[]::new);
            return ff.and(args);
        } else if (expr instanceof OrExpr or) {
            Formula[] args = or.getArgs().stream()
                .map(NormalizationMethodComparison::toLogicNGFormula)
                .toArray(Formula[]::new);
            return ff.or(args);
        }
        throw new IllegalArgumentException("Unknown expression type: " + expr.getClass());
    }
    
    /**
     * 生成测试表达式
     */
    static List<LogicalExpression> generateTestExpressions(int count) {
        List<LogicalExpression> expressions = new ArrayList<>();
        Random random = new Random(42);
        
        String[] prefixes = {"cls_", "nk_", "prop_", "type_", "val_", "x_", "y_", "z_"};
        
        for (int i = 0; i < count; i++) {
            expressions.add(generateRandomExpression(random, prefixes, 3, 0));
        }
        
        return expressions;
    }
    
    static LogicalExpression generateRandomExpression(Random random, String[] prefixes, int maxDepth, int depth) {
        if (depth >= maxDepth || random.nextDouble() < 0.3) {
            // 叶子节点：符号
            String prefix = prefixes[random.nextInt(prefixes.length)];
            return new SymbolExpr(prefix + random.nextInt(10));
        }
        
        int type = random.nextInt(3);
        if (type == 0) {
            // Not
            return new NotExpr(generateRandomExpression(random, prefixes, maxDepth, depth + 1));
        } else if (type == 1) {
            // And
            int numArgs = 2 + random.nextInt(3);
            List<LogicalExpression> args = new ArrayList<>();
            for (int i = 0; i < numArgs; i++) {
                args.add(generateRandomExpression(random, prefixes, maxDepth, depth + 1));
            }
            return new AndExpr(args);
        } else {
            // Or
            int numArgs = 2 + random.nextInt(3);
            List<LogicalExpression> args = new ArrayList<>();
            for (int i = 0; i < numArgs; i++) {
                args.add(generateRandomExpression(random, prefixes, maxDepth, depth + 1));
            }
            return new OrExpr(args);
        }
    }
}

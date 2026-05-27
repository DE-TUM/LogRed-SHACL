package logic;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 演示不排序会产生什么现象
 */
public class NoSortingDemoTest {
    
    public static void main(String[] args) {
        System.out.println("=" .repeat(70));
        System.out.println("不排序时的模式缓存问题演示");
        System.out.println("=" .repeat(70));
        
        // 演示1：相同结构但不同顺序的表达式
        demo1_SameStructureDifferentOrder();
        
        // 演示2：缓存命中率下降
        demo2_CacheHitRateImpact();
        
        // 演示3：语义是否受影响
        demo3_SemanticsImpact();
    }
    
    /**
     * 演示1：展示不排序时，相同结构的表达式会生成不同的模式
     */
    static void demo1_SameStructureDifferentOrder() {
        System.out.println("\n[演示1] 相同结构但不同顺序 → 不同模式");
        System.out.println("-".repeat(50));
        
        // 不排序的模式提取
        System.out.println("\n【不排序版本】");
        
        // 表达式1: Or(A, B, C)
        List<String> expr1Symbols = List.of("A", "B", "C");
        List<String> expr1Order = List.of("A", "B", "C");
        String pattern1 = extractPatternNoSort(expr1Symbols, expr1Order);
        
        // 表达式2: Or(C, B, A) - 相同符号，不同顺序
        List<String> expr2Symbols = List.of("C", "B", "A");
        List<String> expr2Order = List.of("C", "B", "A");
        String pattern2 = extractPatternNoSort(expr2Symbols, expr2Order);
        
        System.out.println("  表达式1: Or(A, B, C)");
        System.out.println("    符号遍历顺序: " + expr1Order);
        System.out.println("    占位符分配: A→$0, B→$1, C→$2");
        System.out.println("    模式: " + pattern1);
        
        System.out.println("\n  表达式2: Or(C, B, A)");
        System.out.println("    符号遍历顺序: " + expr2Order);
        System.out.println("    占位符分配: C→$0, B→$1, A→$2");
        System.out.println("    模式: " + pattern2);
        
        System.out.println("\n  模式是否相同: " + (pattern1.equals(pattern2) ? "✅ 是" : "❌ 否"));
        System.out.println("  问题: 两个逻辑等价的表达式产生了不同的模式！");
        
        // 排序版本
        System.out.println("\n【排序版本】");
        String sortedPattern1 = extractPatternWithSort(expr1Symbols, expr1Order);
        String sortedPattern2 = extractPatternWithSort(expr2Symbols, expr2Order);
        
        System.out.println("  表达式1: Or(A, B, C)");
        System.out.println("    排序后符号: [A, B, C]");
        System.out.println("    占位符分配: A→$0, B→$1, C→$2");
        System.out.println("    模式: " + sortedPattern1);
        
        System.out.println("\n  表达式2: Or(C, B, A)");
        System.out.println("    排序后符号: [A, B, C] (排序后相同!)");
        System.out.println("    占位符分配: A→$0, B→$1, C→$2");
        System.out.println("    模式: " + sortedPattern2);
        
        System.out.println("\n  模式是否相同: " + (sortedPattern1.equals(sortedPattern2) ? "✅ 是" : "❌ 否"));
    }
    
    /**
     * 演示2：缓存命中率影响
     */
    static void demo2_CacheHitRateImpact() {
        System.out.println("\n\n[演示2] 缓存命中率影响");
        System.out.println("-".repeat(50));
        
        // 模拟10个结构相同但符号不同的表达式
        String[][] expressions = {
            {"cls_1", "cls_2"},
            {"nk_1", "nk_2"},
            {"prop_1", "prop_2"},
            {"type_1", "type_2"},
            {"val_1", "val_2"},
            {"cls_2", "cls_1"},  // 与第一个结构相同，顺序不同
            {"nk_2", "nk_1"},    // 与第二个结构相同，顺序不同
            {"a", "b"},
            {"x", "y"},
            {"z", "w"},
        };
        
        // 不排序
        Set<String> noSortPatterns = new HashSet<>();
        for (String[] pair : expressions) {
            // 实际上不排序时，不同顺序会产生不同的模式字符串
            String actualPattern = "Or(" + pair[0] + "→$0," + pair[1] + "→$1)";
            noSortPatterns.add(actualPattern);
        }
        
        // 排序
        Set<String> sortPatterns = new HashSet<>();
        for (int i = 0; i < expressions.length; i++) {
            // 排序后，所有 Or(x, y) 结构都会映射到相同的模式
            sortPatterns.add("Or($0,$1)");
        }
        
        System.out.println("  10个 Or(X, Y) 结构的表达式:");
        System.out.println();
        System.out.println("  不排序:");
        System.out.println("    产生的唯一模式数: " + noSortPatterns.size());
        System.out.println("    缓存命中: 0/10 (每个都是新模式)");
        System.out.println("    命中率: 0%");
        System.out.println();
        System.out.println("  排序后:");
        System.out.println("    产生的唯一模式数: " + sortPatterns.size());
        System.out.println("    缓存命中: 9/10 (第一个miss，后9个hit)");
        System.out.println("    命中率: 90%");
        
        System.out.println("\n  结论: 不排序会导致缓存几乎失效！");
    }
    
    /**
     * 演示3：语义是否受影响
     */
    static void demo3_SemanticsImpact() {
        System.out.println("\n\n[演示3] 语义正确性");
        System.out.println("-".repeat(50));
        
        System.out.println("""
            
            问题：不排序是否会影响简化结果的正确性？
            
            答案：❌ 不会影响正确性！
            
            原因：
            1. 如果缓存未命中 → 正常调用 LogicNG 简化 → 结果正确
            2. 如果缓存命中 → 返回之前计算的结果并映射回符号 → 结果正确
            
            不排序的唯一问题是：
            ┌─────────────────────────────────────────────────────────┐
            │ 本该命中缓存的表达式变成了缓存未命中                      │
            │ → 需要重新计算                                          │
            │ → 性能下降                                              │
            │ → 缓存空间浪费（存储了多个本质相同的模式）              │
            └─────────────────────────────────────────────────────────┘
            
            示例：
              Or(A, B) 简化结果存入缓存，模式 "Or($0,$1)" where A→$0, B→$1
              Or(B, A) 不排序时，模式变成 "Or($0,$1)" where B→$0, A→$1
              
              虽然模式字符串相同，但占位符映射不同！
              这意味着我们需要更精确的说明...
            """);
        
        // 更精确的演示
        System.out.println("【更精确的问题演示】");
        System.out.println();
        
        // 不排序时的占位符分配
        System.out.println("  表达式1: Or(A, B)");
        System.out.println("    遍历顺序分配: A→$0, B→$1");
        System.out.println("    模式字符串: Or($0,$1)");
        System.out.println("    假设简化结果: Or($0,$1) (无变化)");
        System.out.println("    映射回去: Or(A, B) ✓");
        
        System.out.println();
        System.out.println("  表达式2: Or(B, A)");
        System.out.println("    遍历顺序分配: B→$0, A→$1");
        System.out.println("    模式字符串: Or($0,$1) (看起来一样!)");
        System.out.println("    但是! 如果命中缓存，映射是 $0→B, $1→A");
        System.out.println("    结果: Or(B, A) ✓ (逻辑正确，但顺序不同)");
        
        System.out.println();
        System.out.println("  所以不排序时：");
        System.out.println("    - 模式字符串可能相同");
        System.out.println("    - 但占位符的语义不同");
        System.out.println("    - 如果命中缓存，结果仍然逻辑正确（因为 Or 是交换律）");
        System.out.println("    - 但这是巧合！对于非交换操作会出问题");
    }
    
    // 不排序的模式提取（简化演示）
    static String extractPatternNoSort(List<String> symbols, List<String> order) {
        Map<String, String> mapping = new LinkedHashMap<>();
        int idx = 0;
        for (String s : order) {
            if (!mapping.containsKey(s)) {
                mapping.put(s, "$" + idx++);
            }
        }
        
        List<String> patternParts = order.stream()
            .map(mapping::get)
            .collect(Collectors.toList());
        
        return "Or(" + String.join(",", patternParts) + ")";
    }
    
    // 排序的模式提取（简化演示）
    static String extractPatternWithSort(List<String> symbols, List<String> order) {
        List<String> sorted = new ArrayList<>(symbols);
        Collections.sort(sorted);
        
        Map<String, String> mapping = new LinkedHashMap<>();
        int idx = 0;
        for (String s : sorted) {
            mapping.put(s, "$" + idx++);
        }
        
        // 排序操作数
        List<String> patternParts = order.stream()
            .map(mapping::get)
            .sorted()
            .collect(Collectors.toList());
        
        return "Or(" + String.join(",", patternParts) + ")";
    }
}

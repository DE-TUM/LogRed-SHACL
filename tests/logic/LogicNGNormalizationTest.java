package logic;

import org.logicng.formulas.*;

/**
 * 测试 LogicNG 是否已经对表达式进行规范化排序
 */
public class LogicNGNormalizationTest {
    
    public static void main(String[] args) throws Exception {
        FormulaFactory f = new FormulaFactory();
        
        System.out.println("=" .repeat(70));
        System.out.println("LogicNG 规范化排序测试");
        System.out.println("=" .repeat(70));
        
        // 测试1: Or 的操作数顺序
        testOrOrdering(f);
        
        // 测试2: And 的操作数顺序
        testAndOrdering(f);
        
        // 测试3: 嵌套表达式
        testNestedOrdering(f);
        
        // 测试4: 检查内部表示
        testInternalRepresentation(f);
        
        // 测试5: 简化后的顺序
        testSimplificationOrdering(f);
    }
    
    static void testOrOrdering(FormulaFactory f) {
        System.out.println("\n[测试1] Or 操作数顺序");
        System.out.println("-".repeat(50));
        
        Variable a = f.variable("A");
        Variable b = f.variable("B");
        Variable c = f.variable("C");
        
        // 不同顺序创建 Or
        Formula or1 = f.or(a, b, c);
        Formula or2 = f.or(c, b, a);
        Formula or3 = f.or(b, c, a);
        
        System.out.println("f.or(A, B, C) = " + or1);
        System.out.println("f.or(C, B, A) = " + or2);
        System.out.println("f.or(B, C, A) = " + or3);
        
        System.out.println("\n对象相等性 (==):");
        System.out.println("  or1 == or2: " + (or1 == or2));
        System.out.println("  or1 == or3: " + (or1 == or3));
        
        System.out.println("\nequals():");
        System.out.println("  or1.equals(or2): " + or1.equals(or2));
        
        System.out.println("\nhashCode:");
        System.out.println("  or1: " + or1.hashCode());
        System.out.println("  or2: " + or2.hashCode());
        System.out.println("  or3: " + or3.hashCode());
    }
    
    static void testAndOrdering(FormulaFactory f) {
        System.out.println("\n\n[测试2] And 操作数顺序");
        System.out.println("-".repeat(50));
        
        Variable x = f.variable("X");
        Variable y = f.variable("Y");
        Variable z = f.variable("Z");
        
        Formula and1 = f.and(x, y, z);
        Formula and2 = f.and(z, y, x);
        
        System.out.println("f.and(X, Y, Z) = " + and1);
        System.out.println("f.and(Z, Y, X) = " + and2);
        
        System.out.println("\n对象相等性: " + (and1 == and2));
    }
    
    static void testNestedOrdering(FormulaFactory f) {
        System.out.println("\n\n[测试3] 嵌套表达式顺序");
        System.out.println("-".repeat(50));
        
        Variable a = f.variable("A");
        Variable b = f.variable("B");
        Variable c = f.variable("C");
        
        // Or(And(A, B), C)
        Formula nested1 = f.or(f.and(a, b), c);
        // Or(C, And(A, B))
        Formula nested2 = f.or(c, f.and(a, b));
        // Or(C, And(B, A))
        Formula nested3 = f.or(c, f.and(b, a));
        
        System.out.println("f.or(f.and(A, B), C) = " + nested1);
        System.out.println("f.or(C, f.and(A, B)) = " + nested2);
        System.out.println("f.or(C, f.and(B, A)) = " + nested3);
        
        System.out.println("\n对象相等性:");
        System.out.println("  nested1 == nested2: " + (nested1 == nested2));
        System.out.println("  nested1 == nested3: " + (nested1 == nested3));
        System.out.println("  nested2 == nested3: " + (nested2 == nested3));
    }
    
    static void testInternalRepresentation(FormulaFactory f) {
        System.out.println("\n\n[测试4] 内部表示检查");
        System.out.println("-".repeat(50));
        
        Variable a = f.variable("A");
        Variable b = f.variable("B");
        Variable c = f.variable("C");
        
        Formula or = f.or(c, a, b);
        
        System.out.println("创建顺序: f.or(C, A, B)");
        System.out.println("toString(): " + or);
        System.out.println("类型: " + or.getClass().getSimpleName());
        
        if (or instanceof Or) {
            Or orFormula = (Or) or;
            System.out.println("\n操作数遍历顺序:");
            int i = 0;
            for (Formula operand : orFormula) {
                System.out.println("  [" + i++ + "] " + operand);
            }
        }
    }
    
    static void testSimplificationOrdering(FormulaFactory f) {
        System.out.println("\n\n[测试5] 简化后的顺序");
        System.out.println("-".repeat(50));
        
        // 使用不同变量名测试
        Variable cls1 = f.variable("cls_1");
        Variable cls2 = f.variable("cls_2");
        Variable nk1 = f.variable("nk_1");
        
        // 创建可简化的表达式: Or(A, And(A, B)) = A (吸收律)
        Formula expr1 = f.or(cls1, f.and(cls1, cls2));
        Formula expr2 = f.or(nk1, f.and(nk1, cls2));
        
        System.out.println("表达式1: " + expr1);
        System.out.println("表达式2: " + expr2);
        
        // 手动简化
        // LogicNG 的 simplify() 应该会应用吸收律
        
        // 检查 LogicNG 是否使用工厂缓存
        System.out.println("\nLogicNG FormulaFactory 缓存测试:");
        
        Formula a1 = f.or(f.variable("test_a"), f.variable("test_b"));
        Formula a2 = f.or(f.variable("test_a"), f.variable("test_b"));
        Formula a3 = f.or(f.variable("test_b"), f.variable("test_a"));
        
        System.out.println("  f.or(test_a, test_b) 创建两次:");
        System.out.println("    第一次: " + System.identityHashCode(a1));
        System.out.println("    第二次: " + System.identityHashCode(a2));
        System.out.println("    相同对象? " + (a1 == a2));
        
        System.out.println("\n  f.or(test_b, test_a) 顺序不同:");
        System.out.println("    identityHashCode: " + System.identityHashCode(a3));
        System.out.println("    与 a1 相同对象? " + (a1 == a3));
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("结论");
        System.out.println("=".repeat(70));
        
        boolean sameObject = (a1 == a3);
        if (sameObject) {
            System.out.println("""
                
                ✅ LogicNG 已经进行了规范化！
                
                - FormulaFactory 内部对操作数进行排序
                - 相同逻辑的表达式会返回同一个对象（工厂缓存）
                - Or(A, B) 和 Or(B, A) 返回相同的 Formula 实例
                
                这意味着我们的 PatternCache 的排序可能是多余的，
                因为 LogicNG 已经保证了规范化形式。
                """);
        } else {
            System.out.println("""
                
                ⚠️ LogicNG 没有完全规范化！
                
                - Or(A, B) 和 Or(B, A) 返回不同的对象
                - 我们的 PatternCache 排序是必要的
                - 但需要确保排序方式与 LogicNG 兼容
                """);
        }
    }
}

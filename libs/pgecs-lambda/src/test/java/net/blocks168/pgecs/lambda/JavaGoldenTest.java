package net.blocks168.pgecs.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Golden tests for what javac emits: records, String methods, method references. */
class JavaGoldenTest {
    record Mail(String address, Instant verifiedAt, int score) {}

    enum Level { LOW, HIGH }

    record Entry(String name, Level level, int n) {}

    private static <T> String pred(QueryPredicate<T> p) {
        return LExprKt.render(LambdaDecompiler.INSTANCE.decompile(p).getBody());
    }

    private static <T, R> String fn(QueryFunction<T, R> f) {
        return LExprKt.render(LambdaDecompiler.INSTANCE.decompile(f).getBody());
    }

    @Test
    void recordAccessorAndEquals() {
        var addr = "aea";
        assertEquals("(arg0.address() == cap0)", JavaGoldenTest.<Mail>pred(m -> m.address().equals(addr)));
        var l = LambdaDecompiler.INSTANCE.decompile((QueryPredicate<Mail>) m -> m.address().equals(addr));
        assertEquals(List.of(addr), l.getCaptured());
    }

    @Test
    void lowerCase() {
        var addr = "AEA";
        assertEquals(
            "(arg0.address().toLowerCase() == cap0.toLowerCase())",
            JavaGoldenTest.<Mail>pred(m -> m.address().toLowerCase().equals(addr.toLowerCase())));
    }

    @Test
    void booleanLogic() {
        assertEquals(
            "((arg0.score() > 3) && ((arg0.score() < 10) || (arg0.verifiedAt() == null)))",
            JavaGoldenTest.<Mail>pred(m -> m.score() > 3 && (m.score() < 10 || m.verifiedAt() == null)));
        assertEquals(
            "!arg0.address().isEmpty()",
            JavaGoldenTest.<Mail>pred(m -> !m.address().isEmpty()));
    }

    @Test
    void instantComparison() {
        var now = Instant.EPOCH;
        assertEquals("arg0.verifiedAt().isBefore(cap0)", JavaGoldenTest.<Mail>pred(m -> m.verifiedAt().isBefore(now)));
        assertEquals("(arg0.verifiedAt() < cap0)", JavaGoldenTest.<Mail>pred(m -> m.verifiedAt().compareTo(now) < 0));
    }

    @Test
    void methodReference() {
        assertEquals("arg0.address()", JavaGoldenTest.<Mail, String>fn(Mail::address));
    }

    @Test
    void ternary() {
        assertEquals(
            "(if (arg0.score() <= 0) then \"none\" else arg0.address())",
            JavaGoldenTest.<Mail, String>fn(m -> m.score() > 0 ? m.address() : "none"));
    }

    @Test
    void stringSwitch() {
        assertEquals(
            "(if (arg0.name() == \"a\") then 1 else (if (arg0.name() == \"b\") then 2 else 0))",
            JavaGoldenTest.<Entry, Integer>fn(e -> switch (e.name()) { case "a" -> 1; case "b" -> 2; default -> 0; }));
    }

    @Test
    void enumAndIntSwitch() {
        assertEquals(
            "(if (arg0.level() == const(JavaGoldenTest$Level)) then 1 else 2)",
            JavaGoldenTest.<Entry, Integer>fn(e -> switch (e.level()) { case LOW -> 1; case HIGH -> 2; }));
        assertEquals(
            "(if (arg0.n() == 1) then \"one\" else (if (arg0.n() == 7) then \"seven\" else \"many\"))",
            JavaGoldenTest.<Entry, String>fn(e -> switch (e.n()) { case 1 -> "one"; case 7 -> "seven"; default -> "many"; }));
    }

    @Test
    void collectionLiterals() {
        assertEquals("listOf(\"a\", \"b\").contains(arg0.name())", JavaGoldenTest.<Entry>pred(e -> List.of("a", "b").contains(e.name())));
        assertEquals("setOf(\"a\").contains(arg0.name())", JavaGoldenTest.<Entry>pred(e -> java.util.Set.of("a").contains(e.name())));
        assertEquals("listOf(\"x\", \"y\").contains(arg0.name())", JavaGoldenTest.<Entry>pred(e -> java.util.Arrays.asList("x", "y").contains(e.name())));
    }
}

package dev.vxcc.alexs168.pgecs.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Golden tests for what javac emits: records, String methods, method references. */
class JavaGoldenTest {
    record Mail(String address, Instant verifiedAt, int score) {}

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
}

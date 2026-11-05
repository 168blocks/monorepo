package dev.vxcc.alexs168.pgecs.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import dev.vxcc.alexs168.pgecs.ChildOf;
import dev.vxcc.alexs168.pgecs.EntityId;
import org.junit.jupiter.api.Test;

/** The Java DSL from the design doc, against the same components as the Kotlin tests. */
class JavaQueryDslTest {
    @Test
    void designDocExample() {
        var addr = "AEA";
        Query<List<Object>> q = QueryScopeKt.query(null, new QueryBody<List<Object>>() {
            @Override
            public Selection<List<Object>> build(QueryBuilder b) {
                var acc = b.entity("acc");
                var mail = b.entity("mail");
                b.has(acc, Account.INSTANCE);
                b.has(acc, SmpMainAccess.INSTANCE);
                b.hasNot(acc, LobbyDenied.INSTANCE);
                b.pair(ChildOf.INSTANCE, mail, acc);
                b.has(mail, EmailComponent.INSTANCE, e -> e.getAddress().toLowerCase().equals(addr.toLowerCase()));
                return b.select(acc, b.value(mail, EmailComponent.INSTANCE));
            }
        });
        var terms = q.getIr().getTerms().stream().map(Object::toString).toList();
        assertEquals(List.of(
            "b8:iam:account:v1($acc)",
            "b8:iam:smpmain-access-allowed($acc)",
            "filter !exists(b8:iam:lobby-access-denied($acc))",
            "pgecs:child-of($mail, $acc)",
            "b8:iam:email:v1($mail) -> $mail_email",
            "filter (pgecs:lowercase($mail_email.address) == pgecs:lowercase(?0))"), terms);
        assertEquals(List.of(addr), q.getParams());
    }

    @Test
    void lambdaBodyAndDecoding() {
        var id = UUID.randomUUID();
        var ex = new FakeExecutor(List.<Object[]>of(new Object[] {"x@y"}));
        Query<String> q = QueryScopeKt.query(ex, b -> {
            var mail = b.entity("mail");
            b.has(mail, EmailComponent.INSTANCE, e -> e.getVerifiedAt() == null);
            return b.select(() -> b.get(mail, EmailComponent.INSTANCE).getAddress());
        });
        assertEquals("filter ($mail_email.verifiedAt == null)", q.getIr().getTerms().get(1).toString());
        assertEquals("x@y", q.fetchOne());
        EntityId unused = EntityId.of(id);
        assertEquals(id, unused.getId());
    }
}

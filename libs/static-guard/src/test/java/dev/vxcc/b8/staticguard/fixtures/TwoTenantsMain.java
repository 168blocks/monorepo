package dev.vxcc.b8.staticguard.fixtures;

import dev.vxcc.b8.staticguard.agent.Guard;
import dev.vxcc.b8.staticguard.agent.Tenants;

/** Run with the agent: two "servers" on their own threads, each starting the mod. */
public final class TwoTenantsMain {
    public static void main(String[] args) throws Exception {
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int tenant = i + 1;
            ts[i] = new Thread(() -> {
                Tenants.bind(tenant);
                JavaMod.onStart(new Server("s" + tenant));
                JavaMod.remember("k" + tenant, "v");
            }, "server-" + tenant);
            ts[i].start();
            ts[i].join(); // one after the other: deterministic order
        }
        System.out.println("violations=" + Guard.violations().stream().map(v -> v.kind() + " " + v.field()).sorted().toList());
    }
}

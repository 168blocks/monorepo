package dev.vxcc.b8.staticguard.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java agent: {@code -javaagent:static-guard-agent.jar=<option>;<option>...}
 *
 * <ul>
 *   <li>{@code include=a/b/,c/} only instrument classes (and fields of owners) with these prefixes;</li>
 *   <li>{@code exclude=...} more prefixes to leave alone (added to {@link GuardTransformer#DEFAULT_EXCLUDE});</li>
 *   <li>{@code gets=all|watch|none} which {@code getstatic} to hook (default: watch if {@code watch=} is given, else all);</li>
 *   <li>{@code watch=<file>} fields ({@code owner/Name.field}, one per line, e.g. the scanner's {@code --fields}) whose reads are tracked;</li>
 *   <li>{@code isolate=<file>} fields to give per-tenant copies: {@code owner/Name.field [share|copy|reset]};</li>
 *   <li>{@code bind=<regex>=<tenant>} bind threads by name (repeatable), for hosts that do not call {@link Tenants#bind};</li>
 *   <li>{@code allow=<file>} fields ({@code owner/Name.field}) never reported: reviewed shared state;</li>
 *   <li>{@code strict} throw on a write by a second tenant;</li>
 *   <li>{@code trace} count every static write, split into startup and runtime writes at the seal:
 *       {@code sealAfterMs=<ms>} after agent start, or {@code sealFile=<path>} once that file exists
 *       (a host script touches it when the instance is up), or {@link Guard#seal()};</li>
 *   <li>{@code report=<file>} where the report goes at exit (default: stderr).</li>
 * </ul>
 */
public final class StaticGuardAgent {
    private StaticGuardAgent() {}

    public static void premain(String args, Instrumentation inst) { start(args, inst); }

    public static void agentmain(String args, Instrumentation inst) { start(args, inst); }

    static GuardTransformer start(String args, Instrumentation inst) {
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>(GuardTransformer.DEFAULT_EXCLUDE);
        String gets = null;
        Path watch = null, isolate = null, report = null, allow = null;
        long sealAfter = -1;
        Path sealFile = null;
        for (String opt : args == null ? new String[0] : args.split(";")) {
            if (opt.isBlank()) continue;
            int eq = opt.indexOf('=');
            String k = eq < 0 ? opt : opt.substring(0, eq), v = eq < 0 ? "" : opt.substring(eq + 1);
            switch (k) {
                case "include" -> include.addAll(List.of(v.split(",")));
                case "exclude" -> exclude.addAll(List.of(v.split(",")));
                case "gets" -> gets = v;
                case "watch" -> watch = Path.of(v);
                case "isolate" -> isolate = Path.of(v);
                case "allow" -> allow = Path.of(v);
                case "report" -> report = Path.of(v);
                case "strict" -> Guard.strict = true;
                case "trace" -> Guard.trace = true;
                case "sealAfterMs" -> sealAfter = Long.parseLong(v);
                case "sealFile" -> sealFile = Path.of(v);
                case "quiet" -> Guard.printViolations = false;
                case "bind" -> {
                    int last = v.lastIndexOf('=');
                    Tenants.bindByName(v.substring(0, last), Integer.parseInt(v.substring(last + 1)));
                }
                default -> throw new IllegalArgumentException("static-guard: unknown option " + k);
            }
        }
        GuardTransformer.Gets g = gets != null ? GuardTransformer.Gets.valueOf(gets.toUpperCase())
            : watch != null ? GuardTransformer.Gets.WATCH : GuardTransformer.Gets.ALL;
        GuardTransformer t = new GuardTransformer(include, exclude, g);
        if (watch != null) lines(watch).forEach(t::watch);
        if (allow != null) lines(allow).forEach(l -> Guard.allow(l.split("\\s+")[0]));
        if (isolate != null) {
            for (String line : lines(isolate)) {
                String[] p = line.split("\\s+");
                t.isolate(p[0], p.length > 1 ? TenantStatics.Seed.valueOf(p[1].toUpperCase()) : TenantStatics.Seed.SHARE);
            }
        }
        if (inst != null) inst.addTransformer(t);
        if (sealAfter >= 0 || sealFile != null) {
            long ms = sealAfter;
            Path file = sealFile;
            Thread s = new Thread(() -> {
                try {
                    if (ms >= 0) Thread.sleep(ms);
                    while (file != null && !Files.exists(file)) Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                Guard.seal();
                System.err.println("[static-guard] sealed: writes from now on count as runtime writes");
            }, "static-guard-seal");
            s.setDaemon(true);
            s.start();
        }
        Path out = report;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String r = Guard.report() + "\nclasses transformed: " + t.transformed.get() + ", failed: " + t.failed.get() + "\n"
                + String.join("\n", t.failedClasses) + "\n";
            try {
                if (out != null) Files.writeString(out, r); else System.err.print(r);
            } catch (IOException e) {
                System.err.print(r);
            }
        }, "static-guard-report"));
        return t;
    }

    private static List<String> lines(Path p) {
        try {
            return Files.readAllLines(p).stream().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

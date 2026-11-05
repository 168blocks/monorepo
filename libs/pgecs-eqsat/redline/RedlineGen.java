// Build-time generator for the Redline (native) egglog module, run by nix/egglog-redline.nix.
//
// Does what Endive's Maven plugin does with <redlineExperimental>: the build-time compiler's bytecode
// module (Generator), then Cranelift native code for each target (RedlineGenerator), and the loader
// methods (nativeProvider(), builder(), imports()) added to the generated module class.
// Endive's GeneratorMain (used by Gradle for the bytecode-only module) has no Redline option.
//
// Usage: RedlineGen <wasm> <class name> <class dir> <source dir> <wasm dir> <target triple>...

import java.nio.file.Path;
import java.util.Arrays;
import run.endive.build.time.compiler.Config;
import run.endive.build.time.compiler.Generator;
import run.endive.compiler.InterpreterFallback;
import run.endive.redline.experimental.build.RedlineGenerator;

public final class RedlineGen {
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: RedlineGen <wasm> <name> <classDir> <sourceDir> <wasmDir> <target>...");
        }
        var targets = Arrays.asList(args).subList(5, args.length);
        var config = Config.builder()
                .withWasmFile(Path.of(args[0]))
                .withName(args[1])
                .withTargetClassFolder(Path.of(args[2]))
                .withTargetSourceFolder(Path.of(args[3]))
                .withTargetWasmFolder(Path.of(args[4]))
                // the bytecode in this module is only Redline's own fallback; fail rather than interpret
                .withInterpreterFallback(InterpreterFallback.FAIL)
                .withRedlineTargets(targets)
                .build();
        var generator = new Generator(config);
        generator.generateMetaWasm(generator.generateResources());
        generator.generateSources();
        for (var target : targets) {
            System.err.println("redline: compiling native code for " + target);
        }
        var start = System.nanoTime();
        var redline = new RedlineGenerator(config);
        redline.generateNativeCode();
        redline.extendGeneratedSources();
        System.err.printf("redline: done in %d s%n", (System.nanoTime() - start) / 1_000_000_000L);
    }
}

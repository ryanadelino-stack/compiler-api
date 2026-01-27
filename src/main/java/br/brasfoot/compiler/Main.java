package br.brasfoot.compiler;

import java.nio.file.*;
import java.util.*;

import br.brasfoot.compiler.BanCompiler;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);

        if (a.containsKey("--inspect")) {
            Path p = requirePath(a, "--inspect");
            BanInspector.inspect(p);
            return;
        }

        boolean hasInput = a.containsKey("--input");
        boolean hasInputDir = a.containsKey("--inputDir");

        if (!hasInput && !hasInputDir) {
            usageAndFail("Missing --input or --inputDir (or use --inspect).");
        }

        // template agora é OPCIONAL (pode ser null)
        Path template = optionalPath(a, "--template");

        Integer teamId = optionalInt(a, "--teamId");
        Integer countryId = optionalInt(a, "--countryId");

        if (hasInput) {
            Path input = requirePath(a, "--input");
            Path out = requirePath(a, "--out");

            BanCompiler.compileTeamJsonToBan(input, template, out, teamId, countryId);
            System.out.println("OK: " + out);
            return;
        }

        // inputDir mode
        Path inputDir = requirePath(a, "--inputDir");
        Path outDir = requirePath(a, "--outDir");

        if (!Files.isDirectory(inputDir)) {
            usageAndFail("--inputDir is not a directory: " + inputDir);
        }

        Files.createDirectories(outDir);

        try (var stream = Files.list(inputDir)) {
            stream
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted()
                .forEach(p -> {
                    try {
                        String base = p.getFileName().toString();
                        base = base.substring(0, base.length() - 5); // remove .json
                        Path out = outDir.resolve(base + ".ban");

                        BanCompiler.compileTeamJsonToBan(p, template, out, teamId, countryId);
                        System.out.println("OK: " + out);
                    } catch (Exception e) {
                        System.err.println("FAIL: " + p + " -> " + (e.getMessage() != null ? e.getMessage() : e));
                    }
                });
        }
    }

    // =========================
    // CLI helpers
    // =========================
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) continue;
            String v = null;
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                v = args[++i];
            }
            m.put(k, v);
        }
        return m;
    }

    private static Path requirePath(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            usageAndFail("Missing " + key);
        }
        return Paths.get(v);
    }

    private static Path optionalPath(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) return null;
        return Paths.get(v);
    }

    private static Integer optionalInt(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            usageAndFail("Invalid int for " + key + ": " + v);
            return null;
        }
    }

    private static void usageAndFail(String msg) {
        System.err.println("Usage:");
        System.err.println("  --inspect <file.ban>");
        System.err.println("  --input <team.json> [--template <template.ban>] --out <out.ban> [--teamId N] [--countryId N]");
        System.err.println("  --inputDir <dir> [--template <template.ban>] --outDir <dir> [--teamId N] [--countryId N]");
        if (msg != null && !msg.isBlank()) System.err.println("\n" + msg);
        throw new IllegalArgumentException(msg);
    }
}
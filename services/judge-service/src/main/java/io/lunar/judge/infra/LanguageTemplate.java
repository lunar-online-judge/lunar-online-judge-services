package io.lunar.judge.infra;

import java.util.List;
import java.util.Map;

/**
 * 语言编译/运行模板（见 docs/middleware.md go-judge 节）。
 * 沙箱工作目录为 /w：源码/编译产物经 copyIn/copyOutCached 进出该目录。
 * 已支持 C / C++ / Java / Python / JavaScript / TypeScript；Go 等待语言配置能力落地后补充。
 * 模板硬编码是过渡方案，后续迁到 problem-service 配置。
 */
enum LanguageTemplate {

    C(List.of("gcc", "-O2", "-std=c11", "-o", "main", "main.c"),
            "main.c", List.of("main"), List.of("./main")),

    CPP(List.of("g++", "-O2", "-std=c++17", "-o", "main", "main.cpp"),
            "main.cpp", List.of("main"), List.of("./main")),

    // 多编译产物（内部类 Main$1.class 等）暂不支持，只保留主类
    JAVA(List.of("javac", "Main.java"),
            "Main.java", List.of("Main.class"), List.of("java", "-cp", ".", "Main")),

    // 解释型语言：无编译步骤，源码每个测试点随 copyIn 进入沙箱
    PYTHON(null, "main.py", null, List.of("python3", "main.py")),

    // 同为解释型：node 直接执行源码
    JAVASCRIPT(null, "main.js", null, List.of("node", "main.js")),

    /**
     * TypeScript 先经 tsc 生成 main.js 再由 node 运行（不能把 .ts 直接交给 node）。
     * {@code --typeRoots} 指向镜像里 node-typescript 附带的 @types（Debian 的 /usr/share/nodejs/@types），
     * 学生代码因此可以直接用 require / process 等 Node 全局，不必自己 declare。
     * 注意：tsc 把编译诊断写到 stdout 而不是 stderr，适配器的 compileStderr 已做回落。
     */
    TYPESCRIPT(List.of("tsc", "--target", "es2020", "--module", "commonjs",
                    "--typeRoots", "/usr/share/nodejs/@types", "main.ts"),
            "main.ts", List.of("main.js"), List.of("node", "main.js"));

    final List<String> compileArgs;
    final String sourceFileName;
    final List<String> compileOutputs;
    final List<String> runArgs;

    LanguageTemplate(List<String> compileArgs, String sourceFileName,
                     List<String> compileOutputs, List<String> runArgs) {
        this.compileArgs = compileArgs;
        this.sourceFileName = sourceFileName;
        this.compileOutputs = compileOutputs;
        this.runArgs = runArgs;
    }

    private static final Map<String, LanguageTemplate> ALIASES = Map.ofEntries(
            Map.entry("c", C), Map.entry("cpp", CPP), Map.entry("c++", CPP), Map.entry("cc", CPP),
            Map.entry("java", JAVA),
            Map.entry("python", PYTHON), Map.entry("py", PYTHON),
            Map.entry("js", JAVASCRIPT), Map.entry("javascript", JAVASCRIPT), Map.entry("node", JAVASCRIPT),
            Map.entry("ts", TYPESCRIPT), Map.entry("typescript", TYPESCRIPT));

    static LanguageTemplate of(String language) {
        return language == null ? null : ALIASES.get(language.trim().toLowerCase());
    }
}

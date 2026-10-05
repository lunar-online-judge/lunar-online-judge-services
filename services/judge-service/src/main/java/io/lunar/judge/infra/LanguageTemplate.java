package io.lunar.judge.infra;

import java.util.List;
import java.util.Map;

/**
 * 语言编译/运行模板（首批语言，见 docs/middleware.md go-judge 节）。
 * 沙箱工作目录为 /w：源码/编译产物经 copyIn/copyOutCached 进出该目录。
 * TS/Go/JavaScript 待语言配置能力落地后补充；模板硬编码是过渡方案，后续迁到 problem-service 配置。
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
    PYTHON(null, "main.py", null, List.of("python3", "main.py"));

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

    private static final Map<String, LanguageTemplate> ALIASES = Map.of(
            "c", C, "cpp", CPP, "c++", CPP, "cc", CPP,
            "java", JAVA, "python", PYTHON, "py", PYTHON);

    static LanguageTemplate of(String language) {
        return language == null ? null : ALIASES.get(language.trim().toLowerCase());
    }
}

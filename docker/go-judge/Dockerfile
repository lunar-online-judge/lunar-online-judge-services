# 评测沙箱镜像：go-judge 官方镜像基于 Debian，但**不含任何编译器**——
# 真机编译会直接失败：`execve: handle: g++: executable file not found in $PATH`，
# 即使请求本身正确也拿不到 verdict。因此在这里补上工具链。
#
# 语言清单与 judge-service 的 LanguageTemplate 保持一致：c / cpp / java / python / javascript / typescript。
# openjdk-21 是刻意固定的：官方 mount.yaml 里为 Java 预留的路径就是 /etc/java-21-openjdk。
# node-typescript 同时提供 tsc 与 /usr/share/nodejs/@types（TS 模板的 --typeRoots 指向它）。
# 生产部署前请把基础镜像锁定到 digest（compose 里同样是这个要求）。
FROM criyle/go-judge:latest

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        gcc g++ python3 openjdk-21-jdk-headless nodejs node-typescript \
    && rm -rf /var/lib/apt/lists/*

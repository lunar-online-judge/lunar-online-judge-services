import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 用官方 nacos-client 把 docker/nacos/config/*.yml 发布到 Nacos（配置内容的 git 真源 → Nacos）。
 *
 * 为什么不走 HTTP API：Nacos 3.x 已移除 v1 配置 API，v3 的 admin API 需要控制台登录凭据；
 * 而客户端 API（gRPC）在 nacos.core.auth.enabled=false 下免鉴权——用官方客户端最省事，
 * 也不受 HTTP API 版本差异影响。日常用 docker/nacos/publish-configs.sh 调用。
 *
 *   java -cp "<nacos-client 及其依赖>" NacosConfigPublisher.java <serverAddr> <namespace> <配置目录>
 * namespace 传空字符串表示 public 命名空间。
 */
public class NacosConfigPublisher {

    private static final String GROUP = "DEFAULT_GROUP";

    public static void main(String[] args) throws Exception {
        String serverAddr = args[0];
        String namespace = args[1];
        Path configDir = Path.of(args[2]);

        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        if (!namespace.isEmpty()) {
            properties.put("namespace", namespace);
        }
        ConfigService configService = NacosFactory.createConfigService(properties);
        System.out.println("客户端连上 " + serverAddr + "（namespace='" + namespace + "'）");

        try (Stream<Path> files = Files.list(configDir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted().toList()) {
                String dataId = file.getFileName().toString();
                String content = Files.readString(file);
                boolean published = configService.publishConfig(dataId, GROUP, content);
                String readBack = configService.getConfig(dataId, GROUP, 5000);
                System.out.println("  " + dataId
                        + " 发布=" + published
                        + " 读回=" + (content.equals(readBack) ? "一致" : "不一致/为空"));
            }
        }
        System.exit(0);
    }
}

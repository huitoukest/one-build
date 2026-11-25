package com.onebuild.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class HomeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String home = environment.getProperty("one-build.home");
        if (isBlank(home)) {
            home = environment.getProperty("ONE_BUILD_HOME");
        }
        if (isBlank(home)) {
            home = System.getenv("ONE_BUILD_HOME");
        }
        Path homePath = isBlank(home)
                ? resolveJarDir()
                : Paths.get(home).toAbsolutePath().normalize();
        try {
            Files.createDirectories(homePath);
            for (String sub : new String[]{"repos", "workspace", "logs", "tmp"}) {
                Files.createDirectories(homePath.resolve(sub));
            }
            if (!Files.isWritable(homePath)) {
                throw new IllegalStateException("数据目录不可写: " + homePath);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化数据目录: " + homePath + ", " + e.getMessage(), e);
        }
        String normalized = homePath.toString().replace('\\', '/');
        Map<String, Object> props = new HashMap<>();
        props.put("one-build.home", normalized);
        props.put("spring.datasource.url",
                "jdbc:h2:file:" + normalized + "/one-build-h2;MODE=MySQL;AUTO_SERVER=TRUE");
        environment.getPropertySources().addFirst(new MapPropertySource("oneBuildHome", props));
    }

    private Path resolveJarDir() {
        try {
            URL location = HomeEnvironmentPostProcessor.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isDirectory(path)) {
                return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            }
            Path parent = path.getParent();
            if (parent != null) {
                return parent.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}

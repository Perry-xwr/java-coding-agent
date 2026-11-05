package com.agent.tui.prototype;

import java.nio.file.*;
import java.io.IOException;

/** Fixed, trusted local test fixture; creates new files only, never runs Maven itself. */
public final class Tui4BFixture {
    private Tui4BFixture() { }
    public static void create(Path workspace)throws IOException{
        Files.createDirectories(workspace.resolve(".mvn"));Files.createDirectories(workspace.resolve("src/test/java/fixture"));
        Files.writeString(workspace.resolve("pom.xml"),"""
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>tui4b.fixture</groupId><artifactId>approval-smoke</artifactId><version>1</version>
            <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
            <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope></dependency></dependencies>
            <build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
            </plugins></build></project>
            """,StandardOpenOption.CREATE_NEW);
        Files.writeString(workspace.resolve(".mvn/settings.xml"),"<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>",StandardOpenOption.CREATE_NEW);
        Files.writeString(workspace.resolve(".mvn/maven.config"),"--offline\n--settings=.mvn/settings.xml\n--global-settings=.mvn/settings.xml\n",StandardOpenOption.CREATE_NEW);
        Files.writeString(workspace.resolve("src/test/java/fixture/SmokeTest.java"),"""
            package fixture;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class SmokeTest { @Test void arithmetic(){ assertEquals(4,2+2); } }
            """,StandardOpenOption.CREATE_NEW);
    }
}

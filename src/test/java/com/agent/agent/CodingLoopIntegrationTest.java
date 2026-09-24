package com.agent.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "phase3.integration", matches = "true")
class CodingLoopIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void patchesFixtureAndRunsRealMavenTest() throws IOException {
        createFixture();
        FakeLLMClient llm = new FakeLLMClient(List.of(
                toolResponse("read", "read_file", "{\"path\":\"src/main/java/Calculator.java\"}"),
                toolResponse(
                        "patch",
                        "apply_patch",
                        "{\"path\":\"src/main/java/Calculator.java\",\"oldText\":\"a - b\",\"newText\":\"a + b\"}"
                ),
                toolResponse("test", "run_maven_test", "{\"testClass\":\"CalculatorTest\"}"),
                new LLMResponse("Fixed and verified.", List.of())
        ));
        Path projectRepository = Path.of(System.getProperty("user.dir"))
                .resolve(".m2/repository");
        Agent agent = new Agent(
                llm,
                ToolRegistry.withCodingTools(
                        workspace,
                        new com.agent.tool.execution.DefaultProcessRunner(),
                        projectRepository
                )
        );

        AgentRunResult result = agent.runWithTrajectory("Fix the calculator test.");

        assertEquals("Fixed and verified.", result.finalAnswer());
        assertTrue(Files.readString(workspace.resolve("src/main/java/Calculator.java"))
                .contains("a + b"));
        AgentStep testStep = result.trajectory().steps().stream()
                .filter(step -> "run_maven_test".equals(step.toolName()))
                .findFirst()
                .orElseThrow();
        assertTrue(
                testStep.toolResult().success(),
                testStep.toolResult().errorMessage() + System.lineSeparator()
                        + testStep.toolResult().output()
        );
        assertEquals(0, testStep.toolResult().metadata().get("exitCode"));
    }

    private void createFixture() throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.createDirectories(workspace.resolve("src/test/java"));
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId>
                  <artifactId>calculator</artifactId>
                  <version>1.0</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(workspace.resolve("src/main/java/Calculator.java"), """
                public class Calculator {
                    int add(int a, int b) { return a - b; }
                }
                """);
        Files.writeString(workspace.resolve("src/test/java/CalculatorTest.java"), """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class CalculatorTest {
                    @Test void adds() { assertEquals(5, new Calculator().add(2, 3)); }
                }
                """);
    }

    private static LLMResponse toolResponse(String id, String name, String arguments) {
        return new LLMResponse("", List.of(new ToolCall(id, name, arguments)));
    }

    private static final class FakeLLMClient implements LLMClient {
        private final Deque<LLMResponse> responses;

        private FakeLLMClient(List<LLMResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LLMResponse chat(List<Message> messages) {
            return responses.removeFirst();
        }
    }
}

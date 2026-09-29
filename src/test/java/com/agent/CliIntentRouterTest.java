package com.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliIntentRouterTest {
    private final CliIntentRouter router = new CliIntentRouter();

    @Test
    void routesGeneralQuestionToChat() {
        assertDecision("Java 和 C++ 有什么区别", CliMode.CHAT, RoutingConfidence.HIGH,
                RoutingReason.GENERAL_KNOWLEDGE);
    }

    @Test
    void routesRepositoryReadRequestToRead() {
        assertDecision("读取 README.md 并总结", CliMode.READ, RoutingConfidence.HIGH,
                RoutingReason.WORKSPACE_READ_REQUEST);
    }

    @Test
    void routesModificationRequestToCode() {
        assertDecision("修改 Calculator.java 的 add 方法", CliMode.CODE, RoutingConfidence.HIGH,
                RoutingReason.EXPLICIT_MUTATION_TARGET);
    }

    @Test
    void givesCodePriorityWhenRequestAlsoNeedsReading() {
        assertDecision("读取 Calculator.java 并修复 add 方法", CliMode.CODE, RoutingConfidence.HIGH,
                RoutingReason.EXPLICIT_MUTATION_TARGET);
    }

    @Test
    void routesAmbiguousWorkspaceRepairsToCode() {
        assertEquals(CliMode.CODE, router.route("帮我看看 Calculator.java 为什么 add 不对，并修一下").mode());
    }

    @Test
    void routesClassMethodAdditionsToCode() {
        assertEquals(CliMode.CODE, router.route("给 Parser.java 增加一个 parse 方法").mode());
    }

    @Test
    void routesReadmeEditsToCode() {
        assertEquals(CliMode.CODE, router.route("把 README.md 的安装步骤改一下").mode());
    }

    @Test
    void routesWorkspaceFunctionBugHandlingToCode() {
        assertEquals(CliMode.CODE, router.route("这个函数有 bug，帮我处理一下").mode());
    }

    @Test
    void routesWorkspaceAnalysisWithoutMutationToRead() {
        assertEquals(CliMode.READ, router.route("帮我看看 Calculator.java 为什么 add 不对").mode());
        assertEquals(CliMode.READ, router.route("检查这个项目为什么启动失败").mode());
        assertEquals(CliMode.READ, router.route("解释 README.md 讲了什么").mode());
    }

    @Test
    void routesGeneralTechnicalQuestionsToChatWithoutWorkspaceReference() {
        assertEquals(CliMode.CHAT, router.route("怎么修 Java 的 NullPointerException").mode());
        assertEquals(CliMode.CHAT, router.route("什么是 ReAct Agent").mode());
        assertEquals(CliMode.CHAT, router.route("Java 函数怎么写").mode());
    }

    @Test
    void routesUnclearWorkspaceReferenceToReadForSafety() {
        assertDecision("Calculator.java 有点问题", CliMode.READ, RoutingConfidence.MEDIUM,
                RoutingReason.AMBIGUOUS_WORKSPACE_REQUEST);
    }

    @Test
    void routesExplicitFileCreationWithHighConfidence() {
        assertDecision("创建 test.py", CliMode.CODE, RoutingConfidence.HIGH,
                RoutingReason.EXPLICIT_FILE_CREATION);
    }

    @Test
    void routesAmbiguousProjectReferenceToSafeReadFallback() {
        assertDecision("这个项目感觉有问题", CliMode.READ, RoutingConfidence.MEDIUM,
                RoutingReason.AMBIGUOUS_WORKSPACE_REQUEST);
    }

    @Test
    void routesDescriptiveImplementationQueriesToRead() {
        assertEquals(CliMode.READ,
                router.route("读取 README.md，告诉我这个项目目前实现了哪些功能。").mode());
        assertEquals(CliMode.READ, router.route("这个项目已经实现了什么").mode());
        assertEquals(CliMode.READ, router.route("README 里的功能实现情况怎么样").mode());
    }

    @Test
    void keepsActionOrientedImplementationRequestsInCode() {
        assertEquals(CliMode.CODE, router.route("帮我实现一个 parse 方法").mode());
        assertEquals(CliMode.CODE, router.route("在 Parser.java 中实现 parse 方法").mode());
        assertEquals(CliMode.CODE, router.route("实现一个新的 test.py").mode());
    }

    @Test
    void marksIncompleteContextDependentActionsAsLowConfidence() {
        assertLowContextRequest("读你刚刚找到的Python文件");
        assertLowContextRequest("把这些文件打开看看");
        assertLowContextRequest("继续处理");
        assertLowContextRequest("继续讲");
    }

    @Test
    void routesGenericFilenameAndRelativePathMutationsToCode() {
        assertCode("修改 hello.cpp 文件");
        assertCode("修改hello.cpp文件，将里面的内容修改为，打印hello from agent");
        assertCode("请将hello.cpp里面的内容修改为hello from agent");
        assertCode("把 config.yaml 里的端口改成 8080");
        assertCode("更新 package.json");
        assertCode("修复 src/main/App.ts");
    }

    @Test
    void routesExplicitAdditiveWorkspaceMutationsToCode() {
        assertCode("在 hello.cpp 文件里添加 subtract 函数");
        assertCode("在 Parser.java 中新增 parse 方法");
        assertCode("给 config.yaml 增加一个字段");
        assertCode("在 README.md 里加入一段说明");
        assertCode("修改 hello.cpp");
    }

    @Test
    void keepsGeneralAddInsertQuestionsInChatWithoutWorkspaceTarget() {
        assertDecision("C++ 里怎么添加函数？", CliMode.CHAT, RoutingConfidence.HIGH,
                RoutingReason.GENERAL_KNOWLEDGE);
        assertDecision("Java 中如何新增方法？", CliMode.CHAT, RoutingConfidence.HIGH,
                RoutingReason.GENERAL_KNOWLEDGE);
        assertDecision("什么是 insert 操作？", CliMode.CHAT, RoutingConfidence.HIGH,
                RoutingReason.GENERAL_KNOWLEDGE);
    }

    @Test
    void routesGenericFilenameAndRelativePathReadsToRead() {
        assertDecision("读取 hello.cpp", CliMode.READ, RoutingConfidence.HIGH,
                RoutingReason.WORKSPACE_READ_REQUEST);
        assertDecision("看看 config.yaml 的内容", CliMode.READ, RoutingConfidence.HIGH,
                RoutingReason.WORKSPACE_READ_REQUEST);
        assertDecision("读取 foo/bar/test.go", CliMode.READ, RoutingConfidence.HIGH,
                RoutingReason.WORKSPACE_READ_REQUEST);
    }

    @Test
    void keepsFileRelatedGeneralKnowledgeInChat() {
        assertEquals(CliMode.CHAT, router.route("C++ 文件一般怎么写").mode());
        assertEquals(CliMode.CHAT, router.route("Java 文件和 C++ 文件有什么区别").mode());
        assertEquals(CliMode.CHAT, router.route("package.json 是干什么的").mode());
        assertEquals(CliMode.CHAT, router.route("Dockerfile 是什么").mode());
        assertEquals(CliMode.CHAT, router.route("Java 文件是什么").mode());
        assertEquals(CliMode.CHAT, router.route("Python 文件一般怎么组织").mode());
    }

    @Test
    void routesFileDiscoveryRequestsToReadWithoutAnExplicitWorkspaceReference() {
        assertFileDiscovery("找一下名字里包含Agent的Java文件");
        assertFileDiscovery("找所有Python文件");
        assertFileDiscovery("有哪些Java测试文件");
        assertFileDiscovery("列出所有md文件");
        assertFileDiscovery("找名字包含Test的文件");
    }

    @Test
    void routesContentSearchRequestsToReadInsteadOfChat() {
        assertEquals(CliMode.READ, router.route("找一下代码里哪里出现 TODO").mode());
        assertEquals(CliMode.READ, router.route("搜索字符串 hello").mode());
        assertEquals(CliMode.READ, router.route("哪些文件内容包含 Agent").mode());
        assertEquals(CliMode.READ, router.route("哪里调用了 create_file").mode());
    }

    @ParameterizedTest(name = "{index}: {0} -> {1}")
    @MethodSource("codingActionReliabilityMatrix")
    void routesCodingActionReliabilityMatrix(
            String instruction,
            CliMode expectedMode,
            RoutingConfidence expectedConfidence,
            RoutingReason expectedReason
    ) {
        assertDecision(instruction, expectedMode, expectedConfidence, expectedReason);
    }

    @ParameterizedTest(name = "workspace file creation {index}: {0}")
    @MethodSource("workspaceFileCreationRoutingMatrix")
    void routesWorkspaceFileCreationWithoutRequiringConcreteFilename(
            String instruction,
            CliMode expectedMode,
            RoutingConfidence expectedConfidence,
            RoutingReason expectedReason
    ) {
        assertDecision(instruction, expectedMode, expectedConfidence, expectedReason);
    }

    private static Stream<Arguments> workspaceFileCreationRoutingMatrix() {
        return Stream.of(
                Arguments.of("请在该目录下创建一个java文件用于计算斐波那契数列", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("请在当前目录创建一个 Java 文件", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("帮我在这个目录下新建一个 Java 类", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("在这里创建一个 Python 文件", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("给当前项目增加一个配置文件", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("在该文件夹下创建 README", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("帮我创建一个名为 Fibonacci.java 的文件", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("Java 文件怎么创建？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("如何创建一个 Java 文件？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("怎么在 Maven 项目中新建 Java 类？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("创建 Java 文件需要注意什么？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("Python 文件应该怎么创建？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("看看该目录下有哪些 Java 文件，不要修改", CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST),
                Arguments.of("分析这个目录里的 Java 代码", CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST),
                Arguments.of("找一下当前目录有哪些配置文件", CliMode.READ, RoutingConfidence.MEDIUM, RoutingReason.AMBIGUOUS_WORKSPACE_REQUEST)
        );
    }

    private static Stream<Arguments> codingActionReliabilityMatrix() {
        return Stream.of(
                Arguments.of("Maven test 和 package 有什么区别？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("怎么运行 mvn test？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("JUnit 的 assertion 是什么？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("Java 中如何修复单元测试失败？", CliMode.CHAT, RoutingConfidence.HIGH, RoutingReason.GENERAL_KNOWLEDGE),
                Arguments.of("看看这个项目的 Greeter 是怎么实现的，不要修改。", CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST),
                Arguments.of("分析一下当前 Maven 测试可能为什么失败，但先不要改代码。", CliMode.READ, RoutingConfidence.HIGH, RoutingReason.WORKSPACE_READ_REQUEST),
                Arguments.of("找一下哪些文件引用了 Greeter。", CliMode.READ, RoutingConfidence.HIGH, RoutingReason.FILE_DISCOVERY_REQUEST),
                Arguments.of("把 Greeter.greet() 改成返回 hello。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("运行 Maven 测试并修复失败。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("先运行 Maven 测试观察当前失败，再将 Greeter 的 greet 修复为返回 hello。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("执行测试，如果失败就根据错误修复代码。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("修改 Calculator.add() 让它正确执行加法，并运行测试验证。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("给当前项目新建一个 Utils.java。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_FILE_CREATION),
                Arguments.of("帮我直接修改这个类中的空指针问题。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET),
                Arguments.of("先检查失败测试，再完成必要的代码修改。", CliMode.CODE, RoutingConfidence.HIGH, RoutingReason.EXPLICIT_MUTATION_TARGET)
        );
    }

    private void assertCode(String input) {
        assertDecision(input, CliMode.CODE, RoutingConfidence.HIGH,
                RoutingReason.EXPLICIT_MUTATION_TARGET);
    }

    private void assertLowContextRequest(String input) {
        assertDecision(input, CliMode.CHAT, RoutingConfidence.LOW,
                RoutingReason.INCOMPLETE_CONTEXTUAL_REQUEST);
    }

    private void assertFileDiscovery(String input) {
        assertDecision(input, CliMode.READ, RoutingConfidence.HIGH,
                RoutingReason.FILE_DISCOVERY_REQUEST);
    }

    private void assertDecision(
            String input,
            CliMode mode,
            RoutingConfidence confidence,
            RoutingReason reason
    ) {
        RoutingDecision decision = router.route(input);
        assertEquals(mode, decision.mode());
        assertEquals(confidence, decision.confidence());
        assertEquals(reason, decision.reason());
    }
}

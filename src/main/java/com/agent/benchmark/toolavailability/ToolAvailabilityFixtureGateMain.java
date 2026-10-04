package com.agent.benchmark.toolavailability;

import java.nio.file.Path;
import java.util.List;

/** Runs only the local, offline initial-fixture gate; it never constructs a provider. */
public final class ToolAvailabilityFixtureGateMain {
    private ToolAvailabilityFixtureGateMain() { }

    public static void main(String[] args) throws Exception {
        Path protocol = args.length > 0 ? Path.of(args[0]) : Path.of("benchmark/tool-availability-v1");
        Path repository = args.length > 1 ? Path.of(args[1]) : Path.of(".m2/repository");
        List<ToolAvailabilityTask> tasks = new ToolAvailabilityTaskLoader().load(protocol.resolve("manifest.json"));
        ToolAvailabilityInfrastructureGate.Result result = new ToolAvailabilityInfrastructureGate()
                .check(tasks, protocol.resolve("fixtures"), repository.toAbsolutePath().normalize());
        for (ToolAvailabilityInfrastructureGate.FixtureCheck check : result.checks()) {
            System.out.println(check.taskId() + " initial_fixture=" + (check.valid() ? "PASS" : "FAIL")
                    + (check.detail() == null ? "" : " detail=" + check.detail()));
        }
        System.out.println("fixture_gate=" + (result.ready() ? "PASS" : "FAIL")
                + " valid=" + result.checks().stream().filter(ToolAvailabilityInfrastructureGate.FixtureCheck::valid).count()
                + "/" + result.checks().size());
        if (!result.ready()) throw new IllegalStateException("Tool availability fixture gate failed");
    }
}

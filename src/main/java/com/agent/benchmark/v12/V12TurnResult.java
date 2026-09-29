package com.agent.benchmark.v12;

import com.agent.CliMode;
import com.agent.RoutingConfidence;
import com.agent.RoutingReason;
import com.agent.agent.AgentTrajectory;

public record V12TurnResult(
        int turn,
        String userInput,
        CliMode requestedMode,
        CliMode selectedRoute,
        RoutingConfidence routeConfidence,
        RoutingReason routeReason,
        AgentTrajectory trajectory
) {}

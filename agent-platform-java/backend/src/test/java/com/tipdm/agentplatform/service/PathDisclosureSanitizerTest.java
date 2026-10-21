package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PathDisclosureSanitizerTest {

    @Test
    void removesHostAndRuntimeIdentifiersFromUnixPath() {
        String input = "- /Volumes/Data/Work/project/agent-platform-java/workspace/runtimes/agent-id/wb-id/全局DB/教材/项目实现_v1.0.md";

        String output = PathDisclosureSanitizer.sanitize(input);

        assertEquals("- workspace/全局DB/教材/项目实现_v1.0.md", output);
        assertFalse(output.contains("/Volumes/"));
        assertFalse(output.contains("agent-id"));
        assertFalse(output.contains("wb-id"));
    }

    @Test
    void removesAgentLevelPathsWithoutSessionSegment() {
        // 2026-09-15 补：runtimes/<agentId>/.harness 这类无会话段布局此前漏网
        String masked = PathDisclosureSanitizer.sanitize(
                "cd \"/Volumes/Data/Work/project/agent-platform-java/workspace/runtimes/agent-id/.harness\" && ls");

        assertFalse(masked.contains("/Volumes/"));
        assertFalse(masked.contains("agent-id"));
        assertEquals("cd \"workspace/.harness\" && ls", masked);
    }

    @Test
    void leavesRelativeAndWebPathsUntouched() {
        assertEquals("workspace/全局DB/a.md", PathDisclosureSanitizer.sanitize("workspace/全局DB/a.md"));
        assertEquals("https://example.com/a", PathDisclosureSanitizer.sanitize("https://example.com/a"));
    }
}

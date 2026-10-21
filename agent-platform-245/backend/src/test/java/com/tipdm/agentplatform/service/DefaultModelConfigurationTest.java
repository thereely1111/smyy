package com.tipdm.agentplatform.service;

import io.agentscope.core.model.ModelCreationContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class DefaultModelConfigurationTest {

    @Test
    void forwardsConfiguredKeyAndRelayToDefaultModel() {
        AgentAssemblyService service = service("openai:deployment-model", " deployment-key ",
                " https://relay.example/v1 ", "other-provider-key");
        ModelCreationContext context = ReflectionTestUtils.invokeMethod(service, "defaultModelContext");
        assertEquals("deployment-key", context.getApiKey());
        assertEquals("https://relay.example/v1", context.getBaseUrl());
    }

    @Test
    void usesDashscopeKeyOnlyForDashscopeDefaultModel() {
        AgentAssemblyService service = service("dashscope:deployment-model", "", "", "dashscope-key");
        assertEquals("dashscope-key", ReflectionTestUtils.invokeMethod(service, "defaultModelApiKey"));
    }

    @Test
    void doesNotSendDashscopeKeyToAnOpenAiDefaultModel() {
        AgentAssemblyService service = service("openai:deployment-model", "", "", "dashscope-key");
        assertNull(ReflectionTestUtils.invokeMethod(service, "defaultModelApiKey"));
    }

    private AgentAssemblyService service(String model, String key, String baseUrl, String dashscopeKey) {
        AgentAssemblyService service = mock(AgentAssemblyService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "platformModel", model);
        ReflectionTestUtils.setField(service, "modelApiKey", key);
        ReflectionTestUtils.setField(service, "modelBaseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "dashscopeApiKey", dashscopeKey);
        return service;
    }
}

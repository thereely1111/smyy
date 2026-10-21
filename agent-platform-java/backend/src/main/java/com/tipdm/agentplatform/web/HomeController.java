package com.tipdm.agentplatform.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of("service", "agent-platform", "status", "ok");
    }
}

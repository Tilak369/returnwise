package com.returnwise.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.returnwise.agent.AgentTools;
import com.returnwise.agent.GeminiReturnAgent;
import com.returnwise.agent.OfflineRulesAgent;
import com.returnwise.agent.ReturnAgent;
import com.returnwise.ai.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public ReturnAgent returnAgent(ReturnWiseProperties props, GeminiClient gemini, AgentTools tools, ObjectMapper mapper) {
        if (props.useGemini()) {
            log.info("Return agent: Gemini {} on Vertex AI ({})", props.model(), props.location());
            return new GeminiReturnAgent(gemini, tools, mapper);
        }
        log.warn("Return agent: OFFLINE rule-based stand-in (local development only)");
        return new OfflineRulesAgent(tools);
    }
}

package com.returnwise.agent;

import com.returnwise.model.Models.AgentStep;
import com.returnwise.model.Models.Decision;

import java.util.List;

public record AgentResult(Decision decision, List<AgentStep> trace, String engine) {
}

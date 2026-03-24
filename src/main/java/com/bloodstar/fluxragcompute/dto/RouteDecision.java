package com.bloodstar.fluxragcompute.dto;

import lombok.Data;

@Data
public class RouteDecision {

    private String target;

    private String reason;

    public String normalizedTarget() {
        return target == null ? "RAG" : target.trim().toUpperCase();
    }
}

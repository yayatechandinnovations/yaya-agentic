package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlow;
import com.yayatechandinnovations.yayaagentic.startflow.StartStep;

import java.util.List;

/**
 * Wire shapes for the Session Start Flow admin API. Steps / greeting / policy
 * ride as their domain records — Jackson (de)serializes the sealed step/value/
 * target unions via their {@code kind} discriminator, so the builder UI POSTs
 * the same JSON the engine consumes. See {@code docs/design/session-start-flows.md} §14.
 */
public final class StartFlowDtos {

    private StartFlowDtos() {}

    public record CreateRequest(
            String id,
            String name,
            String description,
            List<StartStep> steps,
            GreetingPolicy greeting,
            FlowPolicy policy,
            Boolean publish) {}

    public record UpdateRequest(
            String name,
            String description,
            List<StartStep> steps,
            GreetingPolicy greeting,
            FlowPolicy policy) {}

    public record FlowResponse(
            String id,
            int version,
            String tenantId,
            String name,
            String description,
            List<StartStep> steps,
            GreetingPolicy greeting,
            FlowPolicy policy,
            String status,
            String createdAt,
            String updatedAt) {

        public static FlowResponse of(StartFlow f) {
            return new FlowResponse(
                    f.id().value(),
                    f.version(),
                    f.tenant().value(),
                    f.name(),
                    f.description(),
                    f.steps(),
                    f.greeting(),
                    f.policy(),
                    f.status().name(),
                    f.createdAt() == null ? null : f.createdAt().toString(),
                    f.updatedAt() == null ? null : f.updatedAt().toString());
        }
    }
}

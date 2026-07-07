package io.github.parkkevinsb.flower.action.runtime.audit;

public interface AuditSink {
    void record(AuditEvent event);

    static AuditSink noop() {
        return event -> {
        };
    }
}

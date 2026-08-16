package com.dcbate.batemcpserver.claude;

/** Thrown by {@link ClaudeClient#complete} — becomes an MCP tool error result, not an HTTP 5xx. */
public class ClaudeUnavailableException extends RuntimeException {

    public ClaudeUnavailableException(String message) {
        super(message);
    }
}

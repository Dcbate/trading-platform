package com.dcbate.batemcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A real Model Context Protocol server (JSON-RPC over Streamable HTTP, via Spring AI's official
 * {@code spring-ai-starter-mcp-server-webmvc}) — not a REST API dressed up to look like one. See
 * README.md in this module for the protocol details and why this is a standalone Maven project
 * rather than a module of bate-banking-core.
 */
@SpringBootApplication
public class BateMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BateMcpServerApplication.class, args);
    }
}

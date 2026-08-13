package com.dcbate.tradingplatform.ai;

/** Turns a plain-text event description into a short, customer-facing summary. */
public interface ClaudeSummarizer {

    /** Returns an AI-written summary, or {@code context} unchanged if the API is unavailable. */
    String summarize(String context);
}

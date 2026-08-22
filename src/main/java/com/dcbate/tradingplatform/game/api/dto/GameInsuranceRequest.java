package com.dcbate.tradingplatform.game.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GameInsuranceRequest(@NotBlank String symbol) {
}

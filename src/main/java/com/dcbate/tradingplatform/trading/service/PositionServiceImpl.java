package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.PositionResponse;
import com.dcbate.tradingplatform.trading.repository.PositionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see PositionService */
@Service
@RequiredArgsConstructor
public class PositionServiceImpl implements PositionService {

    private final PositionRepository positionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PositionResponse> listPositionsForClient(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);
        return positionRepository.findByClientId(clientId).stream().map(PositionResponse::from).toList();
    }
}

package com.oreki.cas_injector.stocks.dto;

import lombok.Data;
import java.util.List;

@Data
public class IngestRequest {
    private String pan;
    private List<NormalizedTransactionDTO> transactions;
}

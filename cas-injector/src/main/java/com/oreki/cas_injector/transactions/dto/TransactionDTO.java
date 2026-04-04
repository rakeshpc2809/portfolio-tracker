package com.oreki.cas_injector.transactions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TransactionDTO {
    private BigDecimal amount;
    private LocalDate date;
}
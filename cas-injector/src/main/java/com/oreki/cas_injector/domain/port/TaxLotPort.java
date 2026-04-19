package com.oreki.cas_injector.domain.port;

import java.util.List;
import com.oreki.cas_injector.domain.model.TaxLotDomain;

public interface TaxLotPort {
    List<TaxLotDomain> findOpenLotsBySchemeAndInvestor(String schemeName, String investorPan);
    double getInvestorSlabRate(String investorPan);
}

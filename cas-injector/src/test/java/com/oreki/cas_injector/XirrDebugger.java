import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class XirrDebugger {
    static class TransactionDTO {
        BigDecimal amount;
        LocalDate date;
        TransactionDTO(BigDecimal amount, LocalDate date) {
            this.amount = amount;
            this.date = date;
        }
    }

    private static double calculateNpv(double rate, List<TransactionDTO> txs) {
        double npv = 0.0;
        LocalDate d0 = txs.get(0).date;
        for (TransactionDTO tx : txs) {
            double amount = tx.amount.doubleValue();
            long days = ChronoUnit.DAYS.between(d0, tx.date);
            npv += amount / Math.pow(1.0 + rate, days / 365.25);
        }
        return npv;
    }

    public static double solveXirr(List<TransactionDTO> txs) {
        if (txs == null || txs.size() < 2) return 0.0;
        txs.sort(Comparator.comparing(t -> t.date));
        
        double rate = 0.1;
        for (int i = 0; i < 50; i++) {
            double epsilon = 0.0001;
            double npv = calculateNpv(rate, txs);
            double derivative = (calculateNpv(rate + epsilon, txs) - npv) / epsilon;
            if (Math.abs(derivative) < 1e-9) break;
            rate = rate - (npv / derivative);
        }
        
        if (!Double.isFinite(rate) || rate < -0.99 || rate > 10.0) {
            double lo = -0.99, hi = 10.0;
            for (int j = 0; j < 100; j++) {
                double mid = (lo + hi) / 2.0;
                if (calculateNpv(mid, txs) > 0) lo = mid; else hi = mid;
            }
            rate = (lo + hi) / 2.0;
        }
        return rate * 100;
    }

    public static void main(String[] args) {
        // We will populate this from the DB output
        List<TransactionDTO> txs = new ArrayList<>();
        // ... (data will be inserted here)
    }
}

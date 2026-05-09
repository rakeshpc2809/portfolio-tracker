package com.oreki.cas_injector.core.dto.fiu;

import lombok.Data;
import java.util.List;

@Data
public class ConsentDetail {
    private String consentStart;
    private String consentExpiry;
    private String consentMode = "VIEW";
    private String fetchMode = "PERIODIC";
    private String consentType = "INDIVIDUAL";
    private List<String> fiTypes;
    private DataConsumer dataConsumer;
    private Customer customer;
    private Purpose purpose;
    private FIDataRange fiDataRange;
    private DataLife dataLife;
    private Frequency frequency;

    @Data
    public static class DataConsumer {
        private String id;
    }

    @Data
    public static class Customer {
        private String id; // vpa@aa
    }

    @Data
    public static class Purpose {
        private String code;
        private String text;
        private String refUri;
        private Category category;

        @Data
        public static class Category {
            private String type;
        }
    }

    @Data
    public static class FIDataRange {
        private String from;
        private String to;
    }

    @Data
    public static class DataLife {
        private String unit;
        private int value;
    }

    @Data
    public static class Frequency {
        private String unit;
        private int value;
    }
}

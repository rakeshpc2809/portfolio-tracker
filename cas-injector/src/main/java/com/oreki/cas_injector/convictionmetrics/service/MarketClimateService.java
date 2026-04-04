package com.oreki.cas_injector.convictionmetrics.service;

import com.oreki.cas_injector.core.model.IndexFundamentals;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.IndexFundamentalsRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.convictionmetrics.dto.FundConvictionDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.LocalDate;

@Service
public class MarketClimateService {

    @Autowired
    private SchemeRepository schemeRepository;

    @Autowired
    private IndexFundamentalsRepository indexFundamentalsRepository;

    // This path is relative to the current working directory of the Spring Boot application
    // which, in the Docker setup, is /app within the cas-injector container.
    // The Python script is located at /app/cas-scraper/scraper.py relative to the WORKDIR of the main project.
    private static final String PYTHON_SCRIPT_PATH = "/app/cas-scraper/scraper.py";
    private static final String PYTHON_ENV_PATH = "/usr/bin/python3"; // Or the path to the python executable in your Docker image


    public void syncMarketClimateData() {
        try {
            // Use the Python executable from the .venv
            // In a Docker environment, the .venv might not be directly accessible in the same way
            // as on the host. The Dockerfile for cas-scraper should handle the Python environment.
            // We'll just call python and assume the Docker image has it set up.
            ProcessBuilder processBuilder = new ProcessBuilder("python3", PYTHON_SCRIPT_PATH);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("Python Script Output: " + line);
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Python script executed successfully.");
            } else {
                System.err.println("Python script failed with exit code " + exitCode);
            }

        } catch (Exception e) {
            System.err.println("Error syncing market climate data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public List<FundConvictionDTO> assessFundValuations() {
        List<Scheme> schemes = schemeRepository.findAll();
        return schemes.stream().map(scheme -> {
            FundConvictionDTO dto = new FundConvictionDTO(
                scheme.getAmfiCode(), // schemeCode
                scheme.getName(), // schemeName
                null, // currentPe
                null, // benchmarkPe
                "N/A", // valuationFlag
                0.0, // currentAllocation (default)
                0,   // convictionScore (default)
                0.0, // sortinoRatio (default)
                0.0, // maxDrawdown (default)
                0.0, // cvar5 (default)
                0.0, // winRate (default)
                "HOLD" // status (default)
            );

            Optional<IndexFundamentals> benchmarkFundamentals = indexFundamentalsRepository.findByIndexName(scheme.getBenchmarkIndex());

            if (benchmarkFundamentals.isPresent()) {
                IndexFundamentals fundamentals = benchmarkFundamentals.get();
                dto = new FundConvictionDTO(
                    dto.schemeCode(),
                    dto.schemeName(),
                    fundamentals.getPe(), // currentPe (for now, assume fund PE is benchmark PE)
                    fundamentals.getPe(), // benchmarkPe
                    getValuationFlag(fundamentals.getPe()),
                    dto.currentAllocation(),
                    dto.convictionScore(),
                    dto.sortinoRatio(),
                    dto.maxDrawdown(),
                    dto.cvar5(),
                    dto.winRate(),
                    dto.status()
                );
            }
            return dto;
        }).collect(Collectors.toList());
    }

    private String getValuationFlag(Double peRatio) {
        if (peRatio == null) return "N/A";
        if (peRatio > 25) {
            return "Overvalued";
        } else if (peRatio < 18) {
            return "Undervalued";
        } else {
            return "Fair Value";
        }
    }
}

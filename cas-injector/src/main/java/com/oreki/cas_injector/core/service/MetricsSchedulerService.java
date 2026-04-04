package com.oreki.cas_injector.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

@Service
public class MetricsSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsSchedulerService.class);

    @Scheduled(cron = "0 30 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPipeline() {
        logger.info("Starting scheduled execution of Market & MF Metrics Pipeline...");

        // 1. Run Benchmark Index Scraper
        runPythonScript("scraper.py");

        // 2. Run Mutual Fund Metrics Engine
        runPythonScript("metrics_engine.py");

        logger.info("Market & MF Metrics Pipeline execution finished.");
    }

    private void runPythonScript(String scriptName) {
        logger.info("Preparing to run script: {}", scriptName);

        // Determine the script path based on whether we are in Docker or running locally
        String scriptPath = "/app/cas-scraper/" + scriptName;
        File scriptFile = new File(scriptPath);

        if (!scriptFile.exists()) {
            // Fallback to local workspace relative path
            File localPath = new File("cas-scraper/" + scriptName);
            if (!localPath.exists()) {
                localPath = new File("../cas-scraper/" + scriptName);
            }
            scriptPath = localPath.getAbsolutePath();
            scriptFile = new File(scriptPath);
        }

        if (!scriptFile.exists()) {
            logger.error("Could not find script: {}. Skipping execution.", scriptName);
            return;
        }

        List<String> command = Arrays.asList("python3", scriptPath);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            
            if (scriptFile.getParentFile() != null && scriptFile.getParentFile().exists()) {
                processBuilder.directory(scriptFile.getParentFile());
            }

            logger.info("Executing command: {}", String.join(" ", command));
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[Python: {}] {}", scriptName, line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Script {} completed successfully.", scriptName);
            } else {
                logger.error("Script {} failed with exit code: {}", scriptName, exitCode);
            }

        } catch (Exception e) {
            logger.error("Error occurred while triggering the script: " + scriptName, e);
        }
    }
}

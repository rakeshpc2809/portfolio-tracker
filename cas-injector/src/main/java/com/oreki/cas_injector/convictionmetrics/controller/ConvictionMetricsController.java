package com.oreki.cas_injector.convictionmetrics.controller;

import org.springframework.web.bind.annotation.*;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/metrics")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ConvictionMetricsController {

    private final ConvictionMetricsRepository convictionMetricsRepository;

    /**
     * Fetches the latest quantitative metrics for all funds 
     * currently held by a specific investor.
     */
    @GetMapping("/latest/{pan}")
    public List<Map<String, Object>> getLatestMetrics(@PathVariable String pan) {
        return convictionMetricsRepository.findLatestMetricsByPan(pan);
    }
}
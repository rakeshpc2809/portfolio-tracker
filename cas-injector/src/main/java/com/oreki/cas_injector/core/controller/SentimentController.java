package com.oreki.cas_injector.core.controller;

import com.oreki.cas_injector.core.service.SentimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sentiment")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentService sentimentService;

    @GetMapping("/alpha-feed")
    public ResponseEntity<List<Map<String, Object>>> getAlphaFeed() {
        return ResponseEntity.ok(sentimentService.getAlphaFeed());
    }
}

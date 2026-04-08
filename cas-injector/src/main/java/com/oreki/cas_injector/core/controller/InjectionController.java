package com.oreki.cas_injector.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.core.service.CasProcessingService;

import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/")
public class InjectionController {

    @Autowired
    private CasProcessingService casProcessingService;

    @PostMapping("/cas/inject")
    public ResponseEntity<String> inject(@RequestBody JsonNode root) {
        casProcessingService.processJson(root);
        return ResponseEntity.ok("Successfully processed portfolio data");
    }
}

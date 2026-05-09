package com.oreki.cas_injector.goals.controller;

import com.oreki.cas_injector.goals.model.PortfolioGoal;
import com.oreki.cas_injector.goals.repository.GoalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalRepository goalRepo;

    @GetMapping
    public List<PortfolioGoal> getGoals(@RequestHeader("X-PAN") String pan) {
        return goalRepo.findByInvestorPan(pan);
    }

    @PostMapping
    public PortfolioGoal saveGoal(@RequestBody PortfolioGoal goal, @RequestHeader("X-PAN") String pan) {
        goal.setInvestorPan(pan);
        return goalRepo.save(goal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(@PathVariable Long id) {
        goalRepo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}

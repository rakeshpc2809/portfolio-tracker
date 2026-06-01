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
    public List<PortfolioGoal> getGoals() {
        String pan = getPanFromSecurityContext();
        return goalRepo.findByInvestorPan(pan);
    }

    @PostMapping
    public PortfolioGoal saveGoal(@RequestBody PortfolioGoal goal) {
        String pan = getPanFromSecurityContext();
        goal.setInvestorPan(pan);
        return goalRepo.save(goal);
    }

    private String getPanFromSecurityContext() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("Unauthorized: Missing or invalid JWT token");
        }
        return auth.getName();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(@PathVariable Long id) {
        goalRepo.deleteById(id);
        return ResponseEntity.ok().build();
    }
}

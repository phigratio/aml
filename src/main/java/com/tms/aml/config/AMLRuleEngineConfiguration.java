package com.tms.aml.config;

import com.tms.aml.engine.AMLRuleEngine;
import com.tms.aml.engine.rule.impl.Rule001DerivedAccountAgeCreditor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * AML Rule Engine Configuration
 * 
 * Spring Boot Configuration class that sets up the AML rule engine
 * with proper dependency injection, rule registration, and configuration
 * properties management.
 * 
 * Configuration Properties:
 * aml.engine.rule001.age-threshold-days (default: 30)
 * aml.engine.rule001.amount-threshold (default: 5000)
 * aml.engine.rule001.enabled (default: true)
 * aml.engine.rule001.apply-risk-adjustments (default: true)
 * 
 * @author AML Engineering Team
 */
@Configuration
public class AMLRuleEngineConfiguration {
    
    /**
     * Initialize and configure the AML Rule Engine
     * Registers all available rules and applies configuration
     * 
     * @param rule001 Autowired Rule 001 implementation
     * @param properties AML configuration properties
     * @return Configured RuleEngine instance
     */
    @Bean
    public AMLRuleEngine amlRuleEngine(
        Rule001DerivedAccountAgeCreditor rule001,
        AMLRuleEngineProperties properties
    ) {
        AMLRuleEngine engine = new AMLRuleEngine();
        
        // Apply Rule 001 configuration
        rule001.setAgeThresholdDays(properties.getRule001().getAgeThresholdDays());
        rule001.setAmountThreshold(new BigDecimal(properties.getRule001().getAmountThreshold()));
        rule001.setEnabled(properties.getRule001().isEnabled());
        rule001.setApplyRiskBasedAdjustments(properties.getRule001().isApplyRiskAdjustments());
        
        // Register Rule 001
        engine.registerRule(rule001);
        
        // TODO: Register additional rules (002, 003, ... 040+) as they are implemented
        
        return engine;
    }
    
    /**
     * Rule 001 Bean - Ensures single instance managed by Spring
     * Enables autowiring and configuration injection
     * 
     * @return Rule 001 implementation
     */
    @Bean
    public Rule001DerivedAccountAgeCreditor rule001DerivedAccountAgeCreditor() {
        return new Rule001DerivedAccountAgeCreditor();
    }
}

/**
 * AML Engine Configuration Properties
 * 
 * Maps application.yml configuration to strongly-typed properties
 * Example YAML:
 * 
 * aml:
 *   engine:
 *     rule001:
 *       age-threshold-days: 30
 *       amount-threshold: 5000.00
 *       enabled: true
 *       apply-risk-adjustments: true
 */
@Component
@ConfigurationProperties(prefix = "aml.engine")
class AMLRuleEngineProperties {
    
    private Rule001Properties rule001 = new Rule001Properties();
    
    public Rule001Properties getRule001() {
        return rule001;
    }
    
    public void setRule001(Rule001Properties rule001) {
        this.rule001 = rule001;
    }
    
    /**
     * Rule 001 Specific Configuration
     */
    static class Rule001Properties {
        
        private int ageThresholdDays = 30;
        private String amountThreshold = "5000.00";
        private boolean enabled = true;
        private boolean applyRiskAdjustments = true;
        
        // Getters and Setters
        
        public int getAgeThresholdDays() {
            return ageThresholdDays;
        }
        
        public void setAgeThresholdDays(int ageThresholdDays) {
            this.ageThresholdDays = ageThresholdDays;
        }
        
        public String getAmountThreshold() {
            return amountThreshold;
        }
        
        public void setAmountThreshold(String amountThreshold) {
            this.amountThreshold = amountThreshold;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isApplyRiskAdjustments() {
            return applyRiskAdjustments;
        }
        
        public void setApplyRiskAdjustments(boolean applyRiskAdjustments) {
            this.applyRiskAdjustments = applyRiskAdjustments;
        }
    }
}

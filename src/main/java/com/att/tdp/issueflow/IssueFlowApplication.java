package com.att.tdp.issueflow;

import com.att.tdp.issueflow.auth.jwt.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class IssueFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssueFlowApplication.class, args);
    }
}
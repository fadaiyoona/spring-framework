package com.ConfigurationClassPostProcessor;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
//@ComponentScan("com.selftag")
public class ComponentScanConfiguration {

    @ComponentScan("com.selftag")
    @Configuration
    @Order(90)
    class InnerClass{

    }

}

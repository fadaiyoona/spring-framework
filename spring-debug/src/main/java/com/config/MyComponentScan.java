package com.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@ComponentScan("com.selftag")
public class MyComponentScan {

    @ComponentScan("com.selftag")
    @Configuration
    @Order(90)
    class InnerClass{

    }

}

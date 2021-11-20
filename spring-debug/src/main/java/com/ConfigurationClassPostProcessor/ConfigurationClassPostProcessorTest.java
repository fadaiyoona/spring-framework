package com.ConfigurationClassPostProcessor;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class ConfigurationClassPostProcessorTest {

    public static void main(String[] args) {
        ApplicationContext ac = new ClassPathXmlApplicationContext("configurationClassPostProcessor.xml");
    }
}

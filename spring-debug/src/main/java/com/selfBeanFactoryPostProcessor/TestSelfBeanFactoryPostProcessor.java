package com.selfBeanFactoryPostProcessor;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class TestSelfBeanFactoryPostProcessor {

    public static void main(String[] args) {
        ClassPathXmlApplicationContext ac = new ClassPathXmlApplicationContext("selfbdrpp.xml");
	}
}
